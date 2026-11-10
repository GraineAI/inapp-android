package ai.graine.inapp

import android.app.Activity
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/*
 * THE FACE STAGE — the agent's face edge to edge, with what a call needs drawn
 * over it: who the agent is and how long the call has been on, the line being
 * said, the component the agent drew, and the call's buttons
 * (contract/DESIGN.md "Face stage"). [GraineAvatarStageView] draws it;
 * [GraineStage] is everything it reads, public, so an app can draw its own
 * over a [GraineAvatarView] instead (contract/PUBLIC-STATE.md "The face
 * stage").
 *
 * One rule decides what it shows — [AvatarStage.of], the port of the React
 * Native SDK's avatarStage(); sdk/contract/avatar-stage.json holds the vectors
 * it must reproduce — from state this SDK already publishes: the native call
 * ([GraineCalls]) and the voice bar's call ([GraineVoice]).
 */

/** Which call the face stage shows: none, the voice bar's ([GraineVoice]), or a native call ([GraineCalls]). */
enum class GraineStageCall(val wire: String) { NONE("none"), VOICE("voice"), NATIVE("native") }

/** Where the stage's call is. [FAILED]: the voice bar's call could not start or go on (a native call that fails ends). */
enum class GraineStagePhase(val wire: String) {
    IDLE("idle"), RINGING("ringing"), CONNECTING("connecting"), LIVE("live"), RECONNECTING("reconnecting"),
    ENDED("ended"), FAILED("failed"),
}

/** The stage's buttons: [CALL] places a call, [ANSWER] takes a ringing one, [END] hangs up, cancels or declines. */
enum class GraineStageControl(val wire: String) {
    CALL("call"), ANSWER("answer"), MUTE("mute"), CHAT("chat"), END("end"), SPEAKER("speaker"),
}

/** Which call the stage's Call button places: the voice bar's (the default), or a native call (the WebRTC module draws its face). */
enum class GraineStageCalls { VOICE, NATIVE }

/** A line of the call's transcript, as the stage shows it: who said it, the words, and whether they are all in. */
data class GraineStageCaption(val who: Role, val text: String, val final: Boolean)

/**
 * EVERYTHING THE FACE STAGE DRAWS, in one state ([GraineStage.state]).
 *
 * The first eleven come from the stage's rule (contract avatar-stage.json):
 * [call] — which call; [phase] — where it is; [status] — the words beside the
 * name ("Connecting…", "Reconnecting…", "Call ended", "Couldn’t connect",
 * "Incoming call"; empty while live and while idle); [live] — the LIVE mark
 * and the clock show; [startedAt] — when the call went live, ms since 1970,
 * while it is live or coming back ([GraineStage.clock] is the clock's words);
 * [caption] — the line being said, never an empty one; [muted], [speaker];
 * [controls] — the buttons left to right; [enabled] — those that can act now;
 * [endLabel] — "End", "Cancel" while a call is being placed, "Decline" while
 * one rings. The rest ride along: [face] ([GraineAvatar.state]), [name] and
 * [role] (the header's words), and the agent's [component] (or one on its
 * way: [componentPending]).
 */
data class GraineStageState(
    val call: GraineStageCall = GraineStageCall.NONE,
    val phase: GraineStagePhase = GraineStagePhase.IDLE,
    val status: String = "",
    val live: Boolean = false,
    val startedAt: Long? = null,
    val caption: GraineStageCaption? = null,
    val muted: Boolean = false,
    val speaker: Boolean = true,
    val controls: List<GraineStageControl> = listOf(GraineStageControl.CALL),
    val enabled: List<GraineStageControl> = listOf(GraineStageControl.CALL),
    val endLabel: String = AvatarStage.END,
    val face: GraineAvatarState = GraineAvatarState.OFF,
    val name: String = "Assistant",
    val role: String? = null,
    val component: GraineWidget? = null,
    val componentPending: Boolean = false,
) {
    /** Whether [control] is drawn. */
    fun shows(control: GraineStageControl): Boolean = control in controls

    /** Whether [control] can act now (the microphone waits for the call to be on). */
    fun canUse(control: GraineStageControl): Boolean = control in enabled

    /** The clock's words at [nowMs] (ms since 1970); "" when the call is not live or coming back. */
    @JvmOverloads
    fun clock(nowMs: Long = System.currentTimeMillis()): String = startedAt?.let { AvatarStage.clock(nowMs - it) } ?: ""
}

/** Hears the face stage's state, on the main thread. */
fun interface GraineStageListener {
    fun onStage(state: GraineStageState)
}

/**
 * THE STAGE'S RULE — pure, so the JVM proves every case against
 * contract/avatar-stage.json, and no view words a moment its own way.
 */
internal object AvatarStage {
    const val END = "End"
    const val CANCEL = "Cancel"
    const val DECLINE = "Decline"

    /** The words beside the name, per phase. The apostrophe is the curly one (DESIGN.md "Voice bar"). */
    val STATUS: Map<GraineStagePhase, String> = linkedMapOf(
        GraineStagePhase.IDLE to "",
        GraineStagePhase.RINGING to "Incoming call",
        GraineStagePhase.CONNECTING to "Connecting…",
        GraineStagePhase.LIVE to "",
        GraineStagePhase.RECONNECTING to "Reconnecting…",
        GraineStagePhase.ENDED to "Call ended",
        GraineStagePhase.FAILED to "Couldn’t connect",
    )

    private val NATIVE_ON = setOf("ringing", "calling", "connecting", "active", "reconnecting")
    private val VOICE_ON = setOf("connecting", "listening", "thinking", "speaking", "reconnecting", "muted")

    /** A line of a call's transcript. */
    data class Line(val who: Role, val text: String, val final: Boolean)

    /** The native call: its public phase (idle · ringing · calling · connecting · active · reconnecting · ended · missed). */
    data class Native(
        val phase: String = "idle",
        val muted: Boolean = false,
        val speaker: Boolean = true,
        val startedAt: Long? = null,
        val captions: List<Line> = emptyList(),
    )

    /** The voice bar's call: its public status (idle · connecting · listening · thinking · speaking · reconnecting · muted · failed · ended). */
    data class Voice(
        val status: String = "idle",
        val muted: Boolean = false,
        val speaker: Boolean = true,
        val startedAt: Long? = null,
        val captions: List<Line> = emptyList(),
        val captionsOn: Boolean = true,
    )

    /** `chat`: the app handles the chat button (it opens its own chat) — only then is it drawn. */
    data class Input(val native: Native = Native(), val voice: Voice = Voice(), val chat: Boolean = false)

    /** What the rule decides: the state without the pieces that ride along. */
    data class Shown(
        val call: GraineStageCall,
        val phase: GraineStagePhase,
        val status: String,
        val live: Boolean,
        val startedAt: Long?,
        val caption: GraineStageCaption?,
        val muted: Boolean,
        val speaker: Boolean,
        val controls: List<GraineStageControl>,
        val enabled: List<GraineStageControl>,
        val endLabel: String,
    )

    private fun phaseOf(call: GraineStageCall, input: Input): GraineStagePhase = when (call) {
        GraineStageCall.NATIVE -> when (input.native.phase) {
            "ringing" -> GraineStagePhase.RINGING
            "calling", "connecting" -> GraineStagePhase.CONNECTING
            "active" -> GraineStagePhase.LIVE
            "reconnecting" -> GraineStagePhase.RECONNECTING
            "ended" -> GraineStagePhase.ENDED
            else -> GraineStagePhase.IDLE
        }
        GraineStageCall.VOICE -> when (input.voice.status) {
            "connecting" -> GraineStagePhase.CONNECTING
            "listening", "thinking", "speaking", "muted" -> GraineStagePhase.LIVE
            "reconnecting" -> GraineStagePhase.RECONNECTING
            "ended" -> GraineStagePhase.ENDED
            "failed" -> GraineStagePhase.FAILED
            else -> GraineStagePhase.IDLE
        }
        GraineStageCall.NONE -> GraineStagePhase.IDLE
    }

    /**
     * What the stage shows. Which call: a native call that is ringing, being
     * placed or on; else the voice bar's call while it is being placed or on;
     * else the voice bar's call that just ended or failed; else a native call
     * that just ended; else none. An unknown word reads as idle.
     */
    fun of(input: Input): Shown {
        val call = when {
            input.native.phase in NATIVE_ON -> GraineStageCall.NATIVE
            input.voice.status in VOICE_ON -> GraineStageCall.VOICE
            input.voice.status == "ended" || input.voice.status == "failed" -> GraineStageCall.VOICE
            input.native.phase == "ended" -> GraineStageCall.NATIVE
            else -> GraineStageCall.NONE
        }
        val phase = phaseOf(call, input)
        val native = call == GraineStageCall.NATIVE
        val on = phase == GraineStagePhase.LIVE || phase == GraineStagePhase.RECONNECTING
        val captionsOn = if (call == GraineStageCall.VOICE) input.voice.captionsOn else true
        val last = when (call) {
            GraineStageCall.NATIVE -> input.native.captions.lastOrNull()
            GraineStageCall.VOICE -> input.voice.captions.lastOrNull()
            GraineStageCall.NONE -> null
        }
        val caption = if (on && captionsOn && last != null && last.text.isNotBlank()) GraineStageCaption(last.who, last.text, last.final) else null
        val chat = if (input.chat) listOf(GraineStageControl.CHAT) else emptyList()
        val controls: List<GraineStageControl>
        val enabled: List<GraineStageControl>
        when (phase) {
            GraineStagePhase.RINGING -> {
                controls = listOf(GraineStageControl.END, GraineStageControl.ANSWER)
                enabled = controls
            }
            GraineStagePhase.CONNECTING -> {
                controls = listOf(GraineStageControl.MUTE) + chat + listOf(GraineStageControl.END, GraineStageControl.SPEAKER)
                enabled = chat + listOf(GraineStageControl.END, GraineStageControl.SPEAKER)
            }
            GraineStagePhase.LIVE, GraineStagePhase.RECONNECTING -> {
                controls = listOf(GraineStageControl.MUTE) + chat + listOf(GraineStageControl.END, GraineStageControl.SPEAKER)
                enabled = controls
            }
            else -> {
                controls = chat + GraineStageControl.CALL
                enabled = controls
            }
        }
        return Shown(
            call = call,
            phase = phase,
            status = STATUS.getValue(phase),
            live = phase == GraineStagePhase.LIVE,
            startedAt = if (on) (if (native) input.native.startedAt else input.voice.startedAt) else null,
            caption = caption,
            muted = if (native) input.native.muted else input.voice.muted,
            speaker = if (native) input.native.speaker else input.voice.speaker,
            controls = controls,
            enabled = enabled,
            endLabel = when (phase) {
                GraineStagePhase.CONNECTING -> CANCEL
                GraineStagePhase.RINGING -> DECLINE
                else -> END
            },
        )
    }

    /** The clock: m:ss under an hour, h:mm:ss after — the call screen's own words. Below zero reads as zero. */
    fun clock(elapsedMs: Long): String {
        val s = maxOf(0L, elapsedMs) / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        fun two(n: Long) = n.toString().padStart(2, '0')
        return if (h > 0) "$h:${two(m)}:${two(sec)}" else "$m:${two(sec)}"
    }

    /** The native call's public phase, from what [GraineCalls] holds (contract/PUBLIC-STATE.md §3; a missed ring is not a call here). */
    fun nativePhase(state: GraineCallState, direction: GraineCall.Direction?, reconnecting: Boolean): String = when (state) {
        GraineCallState.IDLE -> "idle"
        GraineCallState.RINGING -> "ringing"
        GraineCallState.CONNECTING -> if (direction == GraineCall.Direction.OUTGOING) "calling" else "connecting"
        GraineCallState.ACTIVE -> if (reconnecting) "reconnecting" else "active"
        GraineCallState.ENDED -> "ended"
    }

    fun lines(captions: List<GraineCaption>): List<Line> = captions.map { Line(it.role, it.text, !it.live) }

    /** The header's name: the app's, else the chat header's title, else the voice bar's (the dashboard's heading, else "Assistant"). */
    fun name(app: String?, chatTitle: String?, heading: String): String =
        app?.trim()?.takeIf { it.isNotEmpty() } ?: chatTitle?.trim()?.takeIf { it.isNotEmpty() } ?: heading

    /** The header's role: the app's, else the chat header's subtitle; null for none. */
    fun role(app: String?, chatSubtitle: String?): String? =
        app?.trim()?.takeIf { it.isNotEmpty() } ?: chatSubtitle?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * THE FACE STAGE'S STATE AND ACTIONS — `GraineInApp.stage`. Everything
 * [GraineAvatarStageView] draws over the agent's face, as one
 * [GraineStageState], and what its buttons do, for a screen of your own over a
 * [GraineAvatarView]: the face draws inside that view, beneath anything you
 * lay over it. New in 0.15.4. Main thread.
 *
 *     val stop = graine.stage.addListener { s ->
 *         header.text = s.name
 *         clock.isVisible = s.live
 *         caption.text = s.caption?.text
 *     }
 *     endButton.setOnClickListener { graine.stage.end() }
 */
class GraineStage internal constructor(private val graine: GraineInApp) {
    /** Everything the stage draws, now. Each change reaches the listeners. */
    @Volatile var state: GraineStageState = GraineStageState()
        private set

    /** The header's name. Null: the dashboard chat header's title, else the voice bar's name (the dashboard's heading). */
    var name: String? = null
        set(v) { field = v; refresh() }

    /** The header's second line ("AI care assistant"). Null: the dashboard chat header's subtitle, else none. */
    var role: String? = null
        set(v) { field = v; refresh() }

    /** Which call [call] places: the voice bar's (the default), or a native call through [GraineCalls]. */
    var calls: GraineStageCalls = GraineStageCalls.VOICE

    /**
     * What the chat button does — your app opens its own chat. The stage draws
     * the button only while this is set: one conversation at a time is yours
     * to decide (a call ends when a chat starts).
     */
    var onChat: (() -> Unit)? = null
        set(v) { field = v; refresh() }

    private val listeners = CopyOnWriteArrayList<GraineStageListener>()

    /**
     * [state] as JSON, for a bridge — the iOS SDK's own words (`stageStateJSON`), keys sorted:
     * `{"call","caption" ({final,text,who} or null),"componentId" (or null),"componentPending","controls",
     * "enabled","endLabel","face" ({phase,reason}),"live","muted","name","phase","role" (or null),"speaker",
     * "startedAt" (ms since 1970, or null),"status"}`. Pair it with [addListener] to follow it.
     */
    fun stateJson(): String = StageWire.json(state)

    /** Hear [state] now and on every change; the returned function stops it. */
    fun addListener(listener: GraineStageListener): () -> Unit {
        onMain {
            listeners += listener
            listener.onStage(state)
        }
        return { onMain { listeners.remove(listener) } }
    }

    fun removeListener(listener: GraineStageListener) = onMain { listeners.remove(listener) }

    /**
     * Place the stage's call: the voice bar's — mounting the voice engine in
     * [activity] (else the one it was attached to) if nothing has — or, with
     * [calls] set to NATIVE, a native call from [activity] (else the app's
     * screen in front).
     */
    @JvmOverloads
    fun call(activity: Activity? = null) {
        if (calls == GraineStageCalls.NATIVE) {
            val from = activity ?: graine.voice.attachedActivity ?: AppShared.callsIfMade()?.inFront
            if (from == null) { graine.reportError("GraineStage.call() needs an activity for a native call."); return }
            graine.calls.startCall(from, agent = graine.name)
            return
        }
        val voice = graine.voice
        if (!voice.attached) {
            val at = activity ?: AppShared.callsIfMade()?.inFront
            if (at == null) { graine.reportError("GraineStage.call() needs an activity: GraineVoice.attach(activity) was not called."); return }
            voice.attach(at)
        }
        voice.start()
    }

    /** Take the native call that is ringing. */
    fun answer() {
        if (state.call == GraineStageCall.NATIVE && state.phase == GraineStagePhase.RINGING) graine.calls.answer()
    }

    /** Hang up the stage's call — cancel one being placed, decline one that rings. */
    fun end() {
        when (state.call) {
            GraineStageCall.NATIVE ->
                if (state.phase == GraineStagePhase.RINGING) graine.calls.decline() else graine.calls.end()
            GraineStageCall.VOICE -> graine.voice.end()
            GraineStageCall.NONE -> Unit
        }
    }

    /** The microphone of the stage's call. */
    fun setMuted(muted: Boolean) {
        if (state.call == GraineStageCall.NATIVE) graine.calls.setMuted(muted) else graine.voice.setMuted(muted)
    }

    /** The loudspeaker of the stage's call (with none, the voice bar's next call starts where this says). */
    fun setSpeaker(on: Boolean) {
        if (state.call == GraineStageCall.NATIVE) graine.calls.setSpeaker(on) else graine.voice.setSpeaker(on)
    }

    /** What the chat button does: the app's [onChat]. */
    fun chat() {
        onChat?.invoke()
    }

    // ── what it hears ──

    private val heard = object : GraineListener {
        override fun onCallState(connected: Boolean, connecting: Boolean) = refresh()
        override fun onCaption(role: Role, text: String, live: Boolean) = refresh()
        override fun onWidget(widget: org.json.JSONObject) = refresh()
        override fun onWidgetPending(hint: org.json.JSONObject) = refreshAndAfter(Widgets.PENDING_WIDGET_MS + 50)
        override fun onEnded() = refresh()
        override fun onMicDenied(reason: String) = refresh()
        override fun onReconnecting(active: Boolean) = refresh()
        override fun onConnectProgress(stage: ConnectStage?, slow: Boolean, failed: Boolean) = refresh()
        override fun onConnectivity(state: Connectivity, attempt: Int, reason: String?) = refresh()
        override fun onError(message: String) = refresh()
        override fun onSessionReady(session: SessionConfig) = refresh()
        override fun onVoiceControls(muted: Boolean, speaker: Boolean) = refresh()
    }

    private val callsHeard = object : GraineCallListener {
        override fun onCallState(state: GraineCallState, call: GraineCall?) = refresh()
        override fun onCallCaption(role: Role, text: String, live: Boolean) = refresh()
        override fun onCallControls(muted: Boolean, speaker: Boolean) = refresh()
        override fun onCallReconnecting(reconnecting: Boolean) = refresh()
    }
    private var callsListened: GraineCalls? = null

    private val faceHeard = GraineAvatarListener { refresh() }

    init {
        graine.addListener(heard)
        GraineAvatar.addListener(faceHeard)
        graine.onComponentChange { refresh() }
        graine.onDesignChange { refresh() }
        graine.voice.onCaptionsChange { refresh() }
        refresh()
    }

    // ── the state, again ──

    /** The native call's wall-clock start, worked out once per call from its elapsed-realtime one. */
    private var nativeStart: Pair<Long, Long>? = null

    /** The component as adopted, kept while the retained frame is the same one — so an unchanged state stays equal. */
    private var component: Pair<org.json.JSONObject, GraineWidget?>? = null

    private val again = Runnable { refresh() }

    private fun refreshAndAfter(ms: Long) {
        refresh()
        graine.main.removeCallbacks(again)
        graine.main.postDelayed(again, ms)
    }

    /** Read everything again and tell the listeners if anything changed. Any thread; the work is on the main thread. */
    internal fun refresh() {
        if (Looper.myLooper() != Looper.getMainLooper() && Looper.getMainLooper() != null) { graine.main.post(again); return }
        val next = compute()
        if (next == state) return
        state = next
        listeners.forEach { runCatching { it.onStage(next) } }
    }

    private fun compute(): GraineStageState {
        val calls = AppShared.callsIfMade()
        if (calls != null && callsListened !== calls) {
            callsListened?.removeListener(callsHeard)
            calls.addListener(callsHeard)
            callsListened = calls
        }
        val native = calls?.let { c ->
            val phase = AvatarStage.nativePhase(c.state, c.call?.direction, c.reconnecting)
            AvatarStage.Native(
                phase = phase,
                muted = c.muted,
                speaker = c.speaker,
                startedAt = wallStart(c.connectedAt),
                captions = AvatarStage.lines(c.captions),
            )
        } ?: AvatarStage.Native()
        val v = if (graine.voiceMade) graine.voice else null
        val voice = v?.let {
            AvatarStage.Voice(
                // The call as the customer has it: a misconfiguration it survived never takes its End button away.
                status = it.stageStatus.wire,
                muted = it.muted,
                speaker = it.speaker,
                startedAt = it.startedAt,
                captions = AvatarStage.lines(it.captions),
                captionsOn = it.captionsOn,
            )
        } ?: AvatarStage.Voice()
        val shown = AvatarStage.of(AvatarStage.Input(native, voice, chat = onChat != null))
        val tokens = runCatching { graine.themeTokens() }.getOrNull()
        val heading = Appearance.resolve(graine.appearance, Appearance.DEFAULT_ACCENT, "Assistant", origin = graine.config.baseUrl).heading
        return GraineStageState(
            call = shown.call,
            phase = shown.phase,
            status = shown.status,
            live = shown.live,
            startedAt = shown.startedAt,
            caption = shown.caption,
            muted = shown.muted,
            speaker = shown.speaker,
            controls = shown.controls,
            enabled = shown.enabled,
            endLabel = shown.endLabel,
            face = GraineAvatar.state,
            name = AvatarStage.name(name, tokens?.chat?.title, heading),
            role = AvatarStage.role(role, tokens?.chat?.subtitle),
            component = adopted(),
            componentPending = graine.loadingWidget != null,
        )
    }

    private fun adopted(): GraineWidget? {
        val raw = graine.pendingWidget ?: run { component = null; return null }
        component?.let { (frame, widget) -> if (frame === raw) return widget }
        val widget = GraineWidget.adopt(raw, graine.config.baseUrl)
        component = raw to widget
        return widget
    }

    /** [GraineCalls.connectedAt] (elapsed realtime) as ms since 1970, once per call; null before it connected. */
    private fun wallStart(connectedAt: Long): Long? {
        if (connectedAt <= 0L) { nativeStart = null; return null }
        nativeStart?.let { (at, wall) -> if (at == connectedAt) return wall }
        val wall = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - connectedAt)
        nativeStart = connectedAt to wall
        return wall
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else graine.main.post(Runnable(block))
    }

    companion object {
        /** The stage's clock in words — m:ss, h:mm:ss after an hour — from `now − startedAt`. */
        @JvmStatic
        fun clock(elapsedMs: Long): String = AvatarStage.clock(elapsedMs)
    }
}

/** The stage's state on the wire to a bridge: the iOS SDK's StageWire, key for key, keys sorted. */
internal object StageWire {
    private fun q(s: String): String = org.json.JSONObject.quote(s)
    private fun words(list: List<GraineStageControl>): String = list.joinToString(",", "[", "]") { q(it.wire) }

    fun json(s: GraineStageState): String {
        val caption = s.caption?.let { c ->
            "{\"final\":${c.final},\"text\":${q(c.text)},\"who\":${q(if (c.who == Role.AGENT) "agent" else "customer")}}"
        } ?: "null"
        val face = "{\"phase\":${q(s.face.phase.word)},\"reason\":${s.face.reason?.let { q(it) } ?: "null"}}"
        return buildString {
            append('{')
            append("\"call\":").append(q(s.call.wire))
            append(",\"caption\":").append(caption)
            append(",\"componentId\":").append(s.component?.id?.let { q(it) } ?: "null")
            append(",\"componentPending\":").append(s.componentPending)
            append(",\"controls\":").append(words(s.controls))
            append(",\"enabled\":").append(words(s.enabled))
            append(",\"endLabel\":").append(q(s.endLabel))
            append(",\"face\":").append(face)
            append(",\"live\":").append(s.live)
            append(",\"muted\":").append(s.muted)
            append(",\"name\":").append(q(s.name))
            append(",\"phase\":").append(q(s.phase.wire))
            append(",\"role\":").append(s.role?.let { q(it) } ?: "null")
            append(",\"speaker\":").append(s.speaker)
            append(",\"startedAt\":").append(s.startedAt?.toString() ?: "null")
            append(",\"status\":").append(q(s.status))
            append('}')
        }
    }
}
