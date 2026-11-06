@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import java.net.HttpURLConnection
import java.net.URL

/*
 * THE AGENT'S FACE ON A NATIVE CALL — one call's face, from its frame to the
 * voice it carries. No Android: the JVM proves it with a fake viewer.
 *
 *   the frame     `avatar` off the call's app channel; read by AvatarWire,
 *                 folded by AvatarFold
 *   the surface   whether an avatar view is on screen (GraineAvatar): a face
 *                 nobody can see is held — never watched, never given the voice
 *   the viewer    made by the WebRTC module on the call's own media
 *                 (GraineRtcPeer.createAvatarViewer): GET /ice, a complete
 *                 offer, POST /offer, the answer — WebRTC only, never another
 *                 road. No module, or a call on the voice socket: the face is
 *                 `unsupported` and the call stays voice-only
 *   the clock     the service's `clock` answered with `played` once heard;
 *                 `interrupted` drops what is pending and silences the face
 *                 while stale audio drains; a ping every 15 s
 *   the voice     the call's copy silenced only while the fold says LIVE, and
 *                 given back at once when it does not
 *   patience      the first connection is tried again every 2 s for 20 s; a
 *                 connection lost mid-call (failed, or disconnected 4 s) is made
 *                 again — a fresh peer and offer — for 40 s, the stall rule
 *                 giving the voice back meanwhile. 401/403/404/410 are final
 *
 * Every method runs on the scheduler's thread.
 */

/** One request of the face's viewer; null when there was no answer. Blocking: run off the scheduler. */
internal fun interface AvatarHttp {
    fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply?
}

/** The face's requests: no cookies, no cache — the viewer's token is the only credential. */
internal object PlatformAvatarHttp : AvatarHttp {
    override fun request(method: String, url: String, headers: Map<String, String>, body: String?): HttpReply? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = AvatarViewerWire.REQUEST_MS
            readTimeout = AvatarViewerWire.REQUEST_MS
            useCaches = false
            instanceFollowRedirects = false
            for ((k, v) in headers) setRequestProperty(k, v)
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        conn.disconnect()
        HttpReply(code, text)
    } catch (_: Exception) {
        null
    }
}

internal class AvatarFace(
    private val scheduler: CallScheduler,
    private val hooks: Hooks,
    private val http: AvatarHttp = PlatformAvatarHttp,
    /** Where blocking requests run. */
    private val io: (() -> Unit) -> Unit = { work -> Thread({ work() }, "graine-avatar").apply { isDaemon = true }.start() },
    private val wallSeconds: () -> Double = { System.currentTimeMillis() / 1000.0 },
) {
    class Hooks(
        /** A viewer on the call's media, or null when this call cannot show a face. */
        val makeViewer: (GraineAvatarViewer.Listener) -> GraineAvatarViewer?,
        /** The call's agent voice on the meter scale, before any silencing (null: unknown). */
        val agentLevel: () -> Double?,
        /** Silence (false) or restore (true) the call's own copy of the agent's voice. */
        val setAgentAudible: (Boolean) -> Unit,
        /** What the app is told. */
        val onState: (GraineAvatarState) -> Unit,
        /** The viewer whose view draws the face while it is on screen (playing, live or stalled); null otherwise. */
        val onViewer: (GraineAvatarViewer?) -> Unit,
        val log: (String) -> Unit = {},
    )

    var fold = AvatarFoldState(shown = false)
        private set
    private var viewer: GraineAvatarViewer? = null
    private var generation = 0
    private var clock = AvatarClock()
    private var unsupported = false
    /** Until when (scheduler ms) a connection is still tried for; null once connected. */
    private var deadline: Long? = null
    private var retry: (() -> Unit)? = null
    private var connected = false
    private var framesBase = 0.0
    private var framesLast = 0.0
    private var sampling: (() -> Unit)? = null
    private var sampleInFlight = false
    private var ping: (() -> Unit)? = null
    private var disconnectTimer: (() -> Unit)? = null
    private val acks = mutableMapOf<Int, () -> Unit>()
    private var nextAck = 0
    private var interruptUntil = 0L
    private var interruptTimer: (() -> Unit)? = null
    private var agentAudible = true
    private var faceAudible = false
    private var published: GraineAvatarState? = null
    private var shownViewer: GraineAvatarViewer? = null
    private var finished = false

    private fun nowMs(): Double = scheduler.now().toDouble()

    // ── In ──

    /** A frame off the call's channel; one that is not an avatar frame changes nothing. */
    fun frame(obj: Any?) {
        if (finished) return
        val read = AvatarWire.read(obj) ?: return
        if (read is AvatarFrame.Ready && unsupported) { publish(); return }
        step(AvatarFoldEvent.Frame(read, nowMs()))
    }

    /** Whether an avatar view is on screen to draw the face. */
    fun surface(shown: Boolean) {
        if (finished) return
        step(AvatarFoldEvent.Surface(shown, nowMs()))
    }

    /** The call is over: its face goes with it. */
    fun callEnded() {
        if (finished) return
        step(AvatarFoldEvent.CallEnded(nowMs()))
        finished = true
        stopConnection()
        stopSampling()
    }

    // ── The fold, applied ──

    private fun step(e: AvatarFoldEvent) {
        val before = fold
        fold = AvatarFold.fold(fold, e)
        if (before.phase != fold.phase) hooks.log("avatar: ${before.phase.word} → ${fold.phase.word}${fold.reason?.let { " ($it)" } ?: ""}")
        apply(before.session)
    }

    private fun apply(previous: GraineAvatarSession?) {
        val session = fold.session
        if (AvatarFold.mounted(fold) && session != null) {
            if ((deadline != null || viewer != null) && previous?.id != session.id) stopConnection()
            if (viewer == null && deadline == null && fold.phase == AvatarPhase.CONNECTING) begin(session, AvatarViewerWire.CONNECT_MS)
        } else if (viewer != null || deadline != null) {
            stopConnection()
        }
        setAgent(AvatarFold.agentAudible(fold))
        applyFace()
        if (!finished && fold.phase in AvatarFold.SAMPLED) startSampling() else stopSampling()
        publish()
    }

    private fun setAgent(audible: Boolean) {
        if (audible == agentAudible) return
        agentAudible = audible
        hooks.setAgentAudible(audible)
    }

    private fun applyFace() {
        val audible = AvatarFold.faceAudible(fold) && scheduler.now() >= interruptUntil
        if (audible == faceAudible) return
        faceAudible = audible
        viewer?.setAudible(audible)
    }

    private fun publish() {
        val state = if (unsupported) GraineAvatarState(GraineAvatarState.Phase.OFF, "unsupported") else GraineAvatarState.of(AvatarFold.hostState(fold))
        val drawn = if (AvatarFold.visible(fold)) viewer else null
        if (drawn !== shownViewer) {
            shownViewer = drawn
            hooks.onViewer(drawn)
        }
        if (state == published) return
        published = state
        hooks.onState(state)
    }

    // ── The connection, patiently ──

    private fun begin(session: GraineAvatarSession, withinMs: Long) {
        deadline = scheduler.now() + withinMs
        attempt(session)
    }

    private fun attempt(session: GraineAvatarSession) {
        retry = null
        if (deadline == null || !AvatarFold.mounted(fold)) return
        if (AvatarViewerWire.expired(session, wallSeconds())) { giveUp("the viewer's token had expired"); return }
        dropViewer()
        generation++
        val gen = generation
        val listener = object : GraineAvatarViewer.Listener {
            override fun onChannelOpen() = scheduler.post { if (generation == gen) channelOpen() }
            override fun onMessage(text: String) = scheduler.post { if (generation == gen) message(text, session) }
            override fun onConnection(state: String) = scheduler.post { if (generation == gen) connection(state, session) }
        }
        val made = hooks.makeViewer(listener)
        if (made == null) {
            hooks.log("avatar: this call cannot show a face (no WebRTC viewer): voice only")
            unsupported = true
            deadline = null
            fold = AvatarFoldState(shown = fold.shown)
            setAgent(true)
            publish()
            return
        }
        viewer = made
        clock = AvatarClock()
        faceAudible = false
        made.setAudible(false)
        publish()
        val auth = mapOf("Authorization" to AvatarViewerWire.authorization(session))
        io {
            val reply = http.request("GET", AvatarViewerWire.iceUrl(session), auth, null)
            scheduler.post {
                val v = viewer
                if (generation != gen || v == null) return@post
                if (reply?.code != 200) {
                    attemptFailed("ice ${reply?.code ?: "no answer"}", AvatarViewerWire.isFinal(reply?.code), session)
                    return@post
                }
                v.offer(AvatarViewerWire.iceServers(reply.body), AvatarViewerWire.GATHER_MS) { sdp, error ->
                    scheduler.post {
                        if (generation != gen) return@post
                        if (sdp == null) attemptFailed("offer: ${error ?: "none"}", false, session)
                        else postOffer(sdp, auth, gen, session)
                    }
                }
            }
        }
    }

    private fun postOffer(sdp: String, auth: Map<String, String>, gen: Int, session: GraineAvatarSession) {
        io {
            val reply = http.request("POST", AvatarViewerWire.offerUrl(session), auth + ("Content-Type" to "application/json"), AvatarViewerWire.offerBody(sdp))
            scheduler.post {
                val v = viewer
                if (generation != gen || v == null) return@post
                // 409: the service still holds the viewer this one replaces — worth another try, as anything not final is.
                val answer = if (reply?.code == 200) AvatarViewerWire.answer(reply.body) else null
                if (answer == null) {
                    attemptFailed("offer ${reply?.code ?: "no answer"}", AvatarViewerWire.isFinal(reply?.code), session)
                    return@post
                }
                v.applyAnswer(answer) { error ->
                    scheduler.post { if (generation == gen && error != null) attemptFailed("answer: $error", false, session) }
                }
            }
        }
    }

    private fun attemptFailed(why: String, final: Boolean, session: GraineAvatarSession) {
        hooks.log("avatar: viewer $why")
        dropViewer()
        val until = deadline
        if (final || until == null || scheduler.now() + AvatarViewerWire.RETRY_MS >= until) { giveUp(why); return }
        retry = scheduler.schedule(AvatarViewerWire.RETRY_MS) { attempt(session) }
        publish()
    }

    private fun giveUp(why: String) {
        hooks.log("avatar: the face could not be shown ($why): voice only")
        stopConnection()
        step(AvatarFoldEvent.Failed(nowMs()))
    }

    private fun channelOpen() {
        viewer?.send(AvatarViewerWire.READY)
        schedulePing()
    }

    private fun message(text: String, session: GraineAvatarSession) {
        when (val s = clock.receive(text) ?: return) {
            is AvatarClockStep.Ack -> {
                val id = ++nextAck
                acks[id] = scheduler.schedule(s.delayMs.toLong()) {
                    if (acks.remove(id) == null) return@schedule
                    if (clock.current(s.epoch) && connected) viewer?.send(AvatarViewerWire.played(s.samples, s.epoch))
                }
            }
            is AvatarClockStep.Interrupted -> {
                acks.values.forEach { it() }
                acks.clear()
                interruptUntil = scheduler.now() + s.muteMs.toLong()
                applyFace()
                interruptTimer?.invoke()
                interruptTimer = scheduler.schedule(s.muteMs.toLong() + 20) { applyFace() }
            }
            AvatarClockStep.Failed -> lost("the face reported an error", session)
        }
    }

    private fun connection(state: String, session: GraineAvatarSession) {
        when (state) {
            "connected" -> {
                connected = true
                deadline = null
                disconnectTimer?.invoke()
                disconnectTimer = null
                // Native video plays as it arrives, and its sound is never refused: connected is playing.
                step(AvatarFoldEvent.Playing(nowMs()))
            }
            "disconnected" -> {
                if (!connected || disconnectTimer != null) return
                disconnectTimer = scheduler.schedule(AvatarViewerWire.DISCONNECTED_MS) {
                    disconnectTimer = null
                    lost("disconnected", session)
                }
            }
            "failed", "closed" -> if (connected) lost(state, session) else attemptFailed(state, false, session)
        }
    }

    private fun lost(why: String, session: GraineAvatarSession) {
        if (!AvatarFold.mounted(fold)) return
        hooks.log("avatar: the face's connection was lost ($why); connecting it again")
        dropViewer()
        if (deadline == null) deadline = scheduler.now() + AvatarViewerWire.RECONNECT_MS
        attempt(session)
    }

    private fun dropViewer() {
        generation++
        acks.values.forEach { it() }
        acks.clear()
        ping?.invoke(); ping = null
        disconnectTimer?.invoke(); disconnectTimer = null
        interruptTimer?.invoke(); interruptTimer = null
        connected = false
        sampleInFlight = false
        framesBase += framesLast
        framesLast = 0.0
        val v = viewer ?: return
        viewer = null
        v.close()
        faceAudible = false
    }

    private fun stopConnection() {
        retry?.invoke(); retry = null
        deadline = null
        dropViewer()
        publish()
    }

    private fun schedulePing() {
        ping?.invoke()
        ping = scheduler.schedule(AvatarViewerWire.PING_MS) {
            val v = viewer ?: return@schedule
            v.send(AvatarViewerWire.PING)
            schedulePing()
        }
    }

    // ── Samples, four a second ──

    private fun startSampling() {
        if (sampling != null) return
        sampling = scheduler.schedule(AvatarFold.SAMPLE_MS.toLong()) {
            sampling = null
            takeSample()
            if (!finished && fold.phase in AvatarFold.SAMPLED) startSampling()
        }
    }

    private fun stopSampling() {
        sampling?.invoke()
        sampling = null
    }

    private fun takeSample() {
        val agent = hooks.agentLevel() ?: 0.0
        val v = viewer
        if (v == null || !connected) {
            // No connection now: the frames stand still (the stall rule and the first-frame backstop read that).
            step(AvatarFoldEvent.Sample(nowMs(), framesBase + framesLast, null, agent))
            return
        }
        if (sampleInFlight) return
        sampleInFlight = true
        val gen = generation
        v.sample { frames, face, delay, emitted ->
            scheduler.post {
                if (generation != gen) return@post
                sampleInFlight = false
                if (delay != null && emitted != null) AvatarViewerWire.playoutDelayMs(delay, emitted)?.let { clock.playoutDelayMs = it }
                if (frames != null) framesLast = Math.max(framesLast, frames)
                step(AvatarFoldEvent.Sample(nowMs(), if (frames == null) null else framesBase + framesLast, face, agent))
            }
        }
    }
}
