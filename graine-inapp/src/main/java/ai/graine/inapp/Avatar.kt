package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/*
 * THE AGENT'S FACE (avatar) — the wire and the rules, no Android.
 *
 * When an agent has a face, the runtime says so on the call's app channel
 * (the WebRTC call's control channel, or the voice socket):
 *
 *   {"type":"avatar","state":"ready","session":{"id","url","token","expires_at"}}
 *   {"type":"avatar","state":"unavailable","reason":"busy"|"error"|"off"}
 *
 * `session` is the VIEWER's descriptor only. The face's video carries the
 * agent's voice in sync with its lips, so while the face is on screen and
 * playing, the call's own copy of the voice is silenced — and given back the
 * moment the face stops. Exactly one voice is ever heard.
 *
 *   AvatarWire        the frame's reader and the host messages (contract/avatar.json)
 *   AvatarFold        who carries the voice, step by step (contract/avatar-voice.json)
 *   AvatarViewerWire  the face's viewer protocol: ICE servers, the offer and
 *                     answer, the service's clock on the data channel
 */

/** The viewer's descriptor: enough to watch one face, nothing that could drive it. Never logged whole. */
@GraineRtcInternal
class GraineAvatarSession(val id: String, val url: String, val token: String, val expiresAt: Double?) {
    override fun equals(other: Any?): Boolean =
        other is GraineAvatarSession && other.id == id && other.url == url && other.token == token && other.expiresAt == expiresAt
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = "GraineAvatarSession(id=$id)"
}

/** An `avatar` frame, read. */
@OptIn(GraineRtcInternal::class)
internal sealed class AvatarFrame {
    data class Ready(val session: GraineAvatarSession) : AvatarFrame()
    data class Unavailable(val reason: String) : AvatarFrame()
    object Ended : AvatarFrame()
}

@OptIn(GraineRtcInternal::class)
internal object AvatarWire {
    const val FRAME = "avatar"
    val REASONS = listOf("busy", "error", "off")
    const val SESSION_ID_MAX = 128
    const val URL_MAX = 2048
    const val TOKEN_MAX = 4096
    /** page → host: `{type: "graine:avatar", state: available|live|off, reason?}`. */
    const val HOST_STATE = "graine:avatar"
    /** host → page: `{type: "graine:avatar-surface", shown}`. */
    const val SURFACE = "graine:avatar-surface"
    val HOST_STATES = listOf("available", "live", "off")

    private val LOCAL = setOf("localhost", "127.0.0.1", "::1")
    private val ID = Regex("^[A-Za-z0-9._:-]+$")
    private val REASON = Regex("^[a-z_]{1,24}$")

    /** Where a face is served, cleaned — or null (sdk/inapp protocol.ts `avatarServiceUrl`). */
    fun serviceUrl(raw: Any?): String? {
        val text = raw as? String ?: return null
        if (text.isEmpty() || text.length > URL_MAX || text.any { it.isWhitespace() }) return null
        val u = try { URI(text) } catch (_: Exception) { return null }
        val scheme = u.scheme?.lowercase() ?: return null
        val rawHost = u.host ?: return null
        if (rawHost.isEmpty() || u.rawUserInfo != null) return null
        val bare = rawHost.trim('[', ']').lowercase()
        val local = bare in LOCAL
        if (scheme != "https" && !(scheme == "http" && local)) return null
        val host = if (':' in bare) "[$bare]" else bare
        val port = u.port.takeIf { it >= 0 && !(scheme == "https" && it == 443) && !(scheme == "http" && it == 80) }?.let { ":$it" } ?: ""
        val path = (u.rawPath ?: "").trimEnd('/')
        return "$scheme://$host$port$path"
    }

    /** An `avatar` frame, read — or null when it is not one, or names a session nobody could watch. Never throws. */
    fun read(frame: Any?): AvatarFrame? {
        val f = frame as? JSONObject ?: return null
        if (f.opt("type") != FRAME) return null
        when (f.opt("state")) {
            "unavailable" -> {
                val reason = f.opt("reason") as? String
                return AvatarFrame.Unavailable(if (reason != null && reason in REASONS) reason else "error")
            }
            "ended" -> return AvatarFrame.Ended
            "ready" -> Unit
            else -> return null
        }
        val s = f.opt("session") as? JSONObject ?: return null
        val transport = s.opt("transport")
        if (transport != null && transport != JSONObject.NULL && transport != "webrtc") return null
        val id = s.opt("id") as? String ?: ""
        if (id.isEmpty() || id.length > SESSION_ID_MAX || !ID.matches(id)) return null
        val url = serviceUrl(s.opt("url")) ?: return null
        val token = s.opt("token") as? String ?: ""
        if (token.isEmpty() || token.length > TOKEN_MAX || token.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return null
        val exp = s.opt("expires_at")
        val expiresAt = (exp as? Number)?.toDouble()?.takeIf { exp !is Boolean && !it.isNaN() && !it.isInfinite() && it > 0 }
        return AvatarFrame.Ready(GraineAvatarSession(id, url, token, expiresAt))
    }

    /** The message a host is told, or null for a state it is not told about (protocol.ts `avatarHostMessage`). */
    fun hostMessage(state: String, reason: String?): JSONObject? {
        if (state !in HOST_STATES) return null
        val m = JSONObject().put("type", HOST_STATE).put("state", state)
        if (state == "off" && reason != null && REASON.matches(reason)) m.put("reason", reason)
        return m
    }

    /** A page's `graine:avatar`, read: (state, reason) — null when it is not one. */
    fun readHostMessage(message: Any?): Pair<String, String?>? {
        val m = message as? JSONObject ?: return null
        if (m.opt("type") != HOST_STATE) return null
        val state = m.opt("state") as? String ?: return null
        if (state !in HOST_STATES) return null
        val reason = (m.opt("reason") as? String)?.takeIf { state == "off" && REASON.matches(it) }
        return state to reason
    }

    /** A host's `graine:avatar-surface`, read: whether the face is on screen — null when it is not one. */
    fun readSurface(message: Any?): Boolean? {
        val m = message as? JSONObject ?: return null
        if (m.opt("type") != SURFACE) return null
        return m.opt("shown") as? Boolean
    }

    /** What the host posts into the page: the engine's view is (or is no longer) on screen as the face. */
    fun surfaceMessage(shown: Boolean): JSONObject = JSONObject().put("type", SURFACE).put("shown", shown)

    /** The app's view of the face, for a bridge: `{"phase":"off"|"available"|"live","reason":"…"|null}`. */
    fun stateJson(state: GraineAvatarState): String {
        val reason = state.reason?.takeIf { REASON.matches(it) }?.let { "\"$it\"" } ?: "null"
        return "{\"phase\":\"${state.phase.word}\",\"reason\":$reason}"
    }
}

// ── Who carries the voice (lib/avatar-state.ts, line for line) ──────────────

internal enum class AvatarPhase(val word: String) {
    OFF("off"), HELD("held"), CONNECTING("connecting"), WAITING("waiting"), LIVE("live"), STALLED("stalled"), BLOCKED("blocked")
}

@OptIn(GraineRtcInternal::class)
internal data class AvatarFoldState(
    val shown: Boolean,
    val phase: AvatarPhase = AvatarPhase.OFF,
    val session: GraineAvatarSession? = null,
    val reason: String? = null,
    val since: Double = 0.0,
    val frames: Double = 0.0,
    val lastFrameAt: Double = 0.0,
    val flowingSince: Double = 0.0,
    val stalls: Int = 0,
    val quiet: Int = 0,
    /** The last samples while live: (the call's agent speaking, the face heard). */
    val heard: List<Pair<Boolean, Boolean>> = emptyList(),
)

internal sealed class AvatarFoldEvent {
    abstract val at: Double
    data class Frame(val frame: AvatarFrame, override val at: Double) : AvatarFoldEvent()
    data class Surface(val shown: Boolean, override val at: Double) : AvatarFoldEvent()
    data class Playing(override val at: Double) : AvatarFoldEvent()
    data class Blocked(override val at: Double) : AvatarFoldEvent()
    data class Failed(override val at: Double) : AvatarFoldEvent()
    /** Times in ms. [frames]: decoded so far (null: unknown). [face]: its level (null: unknown). [agent]: the call's, on the meter scale, before any mute. */
    data class Sample(override val at: Double, val frames: Double?, val face: Double?, val agent: Double) : AvatarFoldEvent()
    data class CallEnded(override val at: Double) : AvatarFoldEvent()
}

@OptIn(GraineRtcInternal::class)
internal object AvatarFold {
    const val SAMPLE_MS = 250.0
    const val STALL_MS = 2_000.0
    const val FIRST_FRAME_MS = 25_000.0
    const val RESUME_MS = 1_000.0
    const val STALLED_MAX_MS = 45_000.0
    const val MAX_STALLS = 3
    const val QUIET_SAMPLES = 2
    const val QUIET_LEVEL = 0.08
    const val SPEECH_LEVEL = 0.15
    const val FACE_QUIET = 0.01
    const val FACE_HEARD = 0.02
    const val VOICE_WINDOW = 12
    const val VOICE_SPEECH_MIN = 8

    private val MOUNTED = setOf(AvatarPhase.CONNECTING, AvatarPhase.WAITING, AvatarPhase.LIVE, AvatarPhase.STALLED, AvatarPhase.BLOCKED)
    val SAMPLED = setOf(AvatarPhase.CONNECTING, AvatarPhase.WAITING, AvatarPhase.LIVE, AvatarPhase.STALLED)

    fun mounted(s: AvatarFoldState) = s.phase in MOUNTED
    fun visible(s: AvatarFoldState) = s.phase == AvatarPhase.WAITING || s.phase == AvatarPhase.LIVE || s.phase == AvatarPhase.STALLED
    fun agentAudible(s: AvatarFoldState) = s.phase != AvatarPhase.LIVE
    fun faceAudible(s: AvatarFoldState) = s.phase == AvatarPhase.LIVE

    /** What a host is told: a face to show, a face on screen carrying the voice, or none. */
    fun hostState(s: AvatarFoldState): Pair<String, String?> = when (s.phase) {
        AvatarPhase.OFF -> "off" to s.reason
        AvatarPhase.LIVE -> "live" to null
        AvatarPhase.BLOCKED -> "off" to "blocked"
        else -> "available" to null
    }

    private fun off(s: AvatarFoldState, reason: String?, at: Double) = AvatarFoldState(shown = s.shown, reason = reason, since = at)

    private fun watching(s: AvatarFoldState, session: GraineAvatarSession, at: Double) =
        AvatarFoldState(shown = s.shown, phase = if (s.shown) AvatarPhase.CONNECTING else AvatarPhase.HELD, session = session, since = at)

    fun fold(s: AvatarFoldState, e: AvatarFoldEvent): AvatarFoldState = when (e) {
        is AvatarFoldEvent.Frame -> when (val f = e.frame) {
            is AvatarFrame.Ready ->
                if (s.session != null && s.session.id == f.session.id && s.phase != AvatarPhase.OFF) s else watching(s, f.session, e.at)
            is AvatarFrame.Unavailable -> off(s, f.reason, e.at)
            AvatarFrame.Ended -> off(s, "ended", e.at)
        }
        is AvatarFoldEvent.Surface -> when {
            e.shown == s.shown -> s
            e.shown && s.phase == AvatarPhase.HELD && s.session != null -> watching(s.copy(shown = true), s.session, e.at)
            !e.shown && mounted(s) && s.session != null ->
                AvatarFoldState(shown = false, phase = AvatarPhase.HELD, session = s.session, since = e.at)
            else -> s.copy(shown = e.shown)
        }
        is AvatarFoldEvent.Playing ->
            if (s.phase != AvatarPhase.CONNECTING && s.phase != AvatarPhase.BLOCKED) s
            else s.copy(phase = AvatarPhase.WAITING, since = e.at, lastFrameAt = e.at, quiet = 0)
        is AvatarFoldEvent.Blocked ->
            if (!mounted(s) || s.phase == AvatarPhase.BLOCKED) s
            else s.copy(phase = AvatarPhase.BLOCKED, since = e.at, quiet = 0, heard = emptyList())
        is AvatarFoldEvent.Failed -> if (mounted(s)) off(s, "failed", e.at) else s
        // Clean for the next call — the reason too.
        is AvatarFoldEvent.CallEnded -> if (s.phase == AvatarPhase.OFF && s.session == null && s.reason == null) s else off(s, null, e.at)
        is AvatarFoldEvent.Sample -> sample(s, e)
    }

    private fun sample(s: AvatarFoldState, e: AvatarFoldEvent.Sample): AvatarFoldState {
        val at = e.at
        if (s.phase == AvatarPhase.CONNECTING) return if (at - s.since >= FIRST_FRAME_MS) off(s, "failed", at) else s
        if (s.phase != AvatarPhase.WAITING && s.phase != AvatarPhase.LIVE && s.phase != AvatarPhase.STALLED) return s

        val frames = e.frames?.takeIf { !it.isNaN() && !it.isInfinite() }
        val moved = frames != null && frames > s.frames
        var next = if (moved) s.copy(frames = frames!!, lastFrameAt = at) else s

        if (s.phase == AvatarPhase.STALLED) {
            if (at - s.since >= STALLED_MAX_MS) return off(s, "stalled", at)
            if (!moved) return if (next.flowingSince != 0.0) next.copy(flowingSince = 0.0) else next
            val flowingSince = if (next.flowingSince != 0.0) next.flowingSince else at
            if (at - flowingSince >= RESUME_MS) {
                return next.copy(phase = AvatarPhase.WAITING, since = at, flowingSince = 0.0, quiet = 0, heard = emptyList())
            }
            return next.copy(flowingSince = flowingSince)
        }

        if (at - next.lastFrameAt >= STALL_MS) {
            val stalls = next.stalls + 1
            if (stalls >= MAX_STALLS) return off(s, "stalled", at)
            return next.copy(phase = AvatarPhase.STALLED, since = at, stalls = stalls, flowingSince = 0.0, quiet = 0, heard = emptyList())
        }

        val agent = e.agent.takeIf { !it.isNaN() && !it.isInfinite() } ?: 0.0
        val face = e.face?.takeIf { !it.isNaN() && !it.isInfinite() }

        if (s.phase == AvatarPhase.WAITING) {
            val gap = agent < QUIET_LEVEL && (face == null || face < FACE_QUIET)
            val quiet = if (gap) next.quiet + 1 else 0
            if (quiet >= QUIET_SAMPLES) return next.copy(phase = AvatarPhase.LIVE, since = at, quiet = 0, heard = emptyList())
            return if (quiet == next.quiet) next else next.copy(quiet = quiet)
        }

        if (face == null) return next
        val heard = (next.heard + ((agent >= SPEECH_LEVEL) to (face >= FACE_HEARD))).takeLast(VOICE_WINDOW)
        if (heard.size == VOICE_WINDOW) {
            val speaking = heard.count { it.first }
            val faceHeard = heard.any { it.second }
            if (speaking >= VOICE_SPEECH_MIN && !faceHeard) return off(s, "no_voice", at)
        }
        next = next.copy(heard = heard)
        return next
    }

    /** The meter scale the rules are written on (the web engine's `meterLevel`): from the mean absolute sample, 0–1. */
    fun meterLevel(meanAbs: Double): Double {
        val x = if (meanAbs.isNaN() || meanAbs.isInfinite()) 0.0 else Math.max(0.0, meanAbs)
        return Math.round(Math.min(1.0, Math.sqrt(x / 0.25)) * 100) / 100.0
    }
}

// ── The face's viewer protocol ───────────────────────────────────────────────

/** One step the viewer's clock asks for. */
internal sealed class AvatarClockStep {
    /** Say `{type: played, samples, epoch}` after [delayMs], if the epoch is still current then. */
    data class Ack(val samples: Double, val epoch: Double, val delayMs: Double) : AvatarClockStep()
    /** Interrupted: drop pending acks, silence the face for [muteMs] while stale audio drains. */
    data class Interrupted(val muteMs: Double) : AvatarClockStep()
    object Failed : AvatarClockStep()
}

/**
 * THE VIEWER PROTOCOL (the avatar service's browser playback, implemented
 * natively): GET {url}/v1/sessions/{id}/ice and POST …/offer with the
 * viewer's bearer token; a complete offer (the service never trickles); one
 * data channel carries the service's clock, answered with what was played.
 */
@OptIn(GraineRtcInternal::class)
internal object AvatarViewerWire {
    const val REQUEST_MS = 8_000
    const val GATHER_MS = 5_000L
    const val PING_MS = 15_000L
    const val CHANNEL_LABEL = "playback"
    /** The first connection: tried again (every [RETRY_MS]) until this long has passed. */
    const val CONNECT_MS = 20_000L
    /** A connection lost mid-call: made again until this long has passed (the service holds a dead viewer up to ~30 s, 409 meanwhile). */
    const val RECONNECT_MS = 40_000L
    const val RETRY_MS = 2_000L
    /** `disconnected` this long is a lost connection. */
    const val DISCONNECTED_MS = 4_000L

    fun isFinal(status: Int?): Boolean = status == 401 || status == 403 || status == 404 || status == 410

    fun base(s: GraineAvatarSession): String = "${s.url}/v1/sessions/${CallUrls.encodeURIComponent(s.id)}"
    fun iceUrl(s: GraineAvatarSession): String = base(s) + "/ice"
    fun offerUrl(s: GraineAvatarSession): String = base(s) + "/offer"
    fun authorization(s: GraineAvatarSession): String = "Bearer ${s.token}"
    fun expired(s: GraineAvatarSession, nowSeconds: Double): Boolean = s.expiresAt?.let { nowSeconds > it } ?: false

    /** The ICE servers the /ice route answers, kept only when they look like ICE servers (at most 8). */
    fun iceServers(body: String?): List<GraineRtcIceServer> {
        val list = try { JSONObject(body ?: return emptyList()).optJSONArray("iceServers") } catch (_: Exception) { null } ?: return emptyList()
        val out = mutableListOf<GraineRtcIceServer>()
        for (i in 0 until Math.min(8, list.length())) {
            val s = list.opt(i) as? JSONObject ?: continue
            val urls: List<String> = when (val u = s.opt("urls")) {
                is String -> listOf(u)
                is JSONArray -> if (u.length() > 0 && (0 until u.length()).all { u.opt(it) is String }) (0 until u.length()).map { u.getString(it) } else continue
                else -> continue
            }
            if (!urls.all { ICE.containsMatchIn(it) }) continue
            out += GraineRtcIceServer(urls, s.opt("username") as? String, s.opt("credential") as? String)
        }
        return out
    }

    private val ICE = Regex("^(stuns?|turns?):", RegexOption.IGNORE_CASE)

    fun offerBody(sdp: String): String = JSONObject().put("sdp", sdp).put("type", "offer").toString()

    /** The answer's SDP, or null when the body is not an answer. */
    fun answer(body: String?): String? {
        val o = try { JSONObject(body ?: return null) } catch (_: Exception) { return null }
        if (o.opt("type") != "answer") return null
        return (o.opt("sdp") as? String)?.takeIf { it.isNotEmpty() }
    }

    const val READY = "{\"type\":\"ready\"}"
    const val PING = "{\"type\":\"ping\"}"

    fun played(samples: Double, epoch: Double): String = "{\"type\":\"played\",\"samples\":${num(samples)},\"epoch\":${num(epoch)}}"

    private fun num(x: Double): String = if (x == Math.rint(x) && Math.abs(x) < 1e15) x.toLong().toString() else x.toString()

    /** How far behind the clock the face is heard, from its jitter buffer (ms), 60–500. */
    fun playoutDelayMs(jitterBufferDelay: Double, emittedCount: Double): Double? {
        if (emittedCount <= 0 || jitterBufferDelay.isNaN() || jitterBufferDelay.isInfinite()) return null
        return Math.min(500.0, Math.max(60.0, 1000 * jitterBufferDelay / emittedCount + 40))
    }
}

/** The viewer's clock on the data channel: what each service message asks of it. */
internal class AvatarClock {
    var epoch = 0.0
        private set
    private var lastSamples = -1.0
    /** When to say a sample was played, from the jitter buffer; 150 ms until measured. */
    var playoutDelayMs = 150.0

    fun receive(text: String): AvatarClockStep? {
        val ev = try { JSONObject(text) } catch (_: Exception) { return null }
        (ev.opt("epoch") as? Number)?.let { if (it !is Boolean) epoch = it.toDouble() }
        return when (ev.opt("type")) {
            "clock" -> {
                val samples = (ev.opt("samples") as? Number)?.toDouble() ?: return null
                if (samples == lastSamples) return null
                lastSamples = samples
                AvatarClockStep.Ack(samples, epoch, playoutDelayMs)
            }
            "interrupted" -> {
                lastSamples = -1.0
                AvatarClockStep.Interrupted(playoutDelayMs + 60)
            }
            "error" -> AvatarClockStep.Failed
            else -> null
        }
    }

    fun current(e: Double) = e == epoch
}

/**
 * THE AGENT'S FACE, AS THE APP SEES IT — [GraineAvatar.state].
 *
 * [Phase.OFF] — no face on this call (or no call). [reason] says why when a
 * call's agent has a face that is not shown: `busy`, `error`, `off` (the agent
 * has none), `ended`, `failed`, `stalled`, `no_voice`, `blocked`, `unsupported`
 * (this call cannot show one — see the README). [Phase.AVAILABLE] — a face is
 * ready: show your avatar view. [Phase.LIVE] — the face is on screen and
 * carries the agent's voice.
 */
class GraineAvatarState(val phase: Phase, reason: String? = null) {
    enum class Phase(val word: String) { OFF("off"), AVAILABLE("available"), LIVE("live") }

    val reason: String? = if (phase == Phase.OFF) reason else null

    /** A face to draw: available or live. */
    val showsFace: Boolean get() = phase != Phase.OFF

    override fun equals(other: Any?): Boolean = other is GraineAvatarState && other.phase == phase && other.reason == reason
    override fun hashCode(): Int = phase.hashCode() * 31 + (reason?.hashCode() ?: 0)
    override fun toString(): String = "GraineAvatarState(${phase.word}${reason?.let { ", $it" } ?: ""})"

    companion object {
        @JvmField val OFF = GraineAvatarState(Phase.OFF)

        internal fun of(host: Pair<String, String?>): GraineAvatarState =
            GraineAvatarState(Phase.values().firstOrNull { it.word == host.first } ?: Phase.OFF, host.second)
    }
}
