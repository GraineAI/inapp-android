package ai.graine.inapp

import android.app.Activity
import android.net.Uri

/*
 * THE CALL, AS THE APP SEES IT — sdk/NATIVE-CALLS.md "The call (both platforms)".
 * The public types here, and the state machine behind GraineCalls, which is
 * pure (the clock is handed in) so every transition and outcome is proven on
 * the JVM (CallStateTest).
 */

/**
 * Where the one call is.
 *
 *   IDLE        no call
 *   RINGING     an agent is calling the customer (incoming only)
 *   CONNECTING  answered, or placed by the customer; the line is being opened
 *   ACTIVE      the agent and the customer can hear each other
 *   ENDED       over — reported once, then back to IDLE
 */
enum class GraineCallState { IDLE, RINGING, CONNECTING, ACTIVE, ENDED }

/** The call in progress: who placed it, why, and the id every outcome carries back. */
data class GraineCall @JvmOverloads constructor(
    /** From the push for an incoming call; generated for one the customer places. */
    val callId: String,
    val direction: Direction,
    /** What the ring shows: the push's `caller_name`, else [GraineCallsConfig.displayName]. */
    val callerName: String?,
    /** The push's `reason`, or startCall's — the prompt's `call_reason`. */
    val reason: String?,
    /** The call's own prompt variables (`who_called`, `call_reason`, the push's `variables`). */
    val variables: Map<String, String>,
    /**
     * Graine rang it (the push said `hosted: "1"`): a call booked with the
     * agent — by the agent in a conversation, by the user, or from the
     * dashboard. The ring says "Scheduled call"; outcomes go to Graine too.
     */
    val hosted: Boolean = false,
) {
    enum class Direction { INCOMING, OUTGOING }
}

/**
 * What became of a call, for the app's server. Every outcome carries the call's id.
 *
 *   ANSWERED  the customer picked up an incoming call (an ENDED or FAILED follows)
 *   DECLINED  the customer declined it
 *   MISSED    nobody answered within the ring timeout — or another call was live (busy)
 *   ENDED     a call that connected is over: [endReason] says who ended it
 *   FAILED    the call never connected: [error] says why
 */
data class GraineCallOutcome @JvmOverloads constructor(
    val kind: Kind,
    val endReason: EndReason? = null,
    val error: String? = null,
    /** The call's id — the same one [GraineCallListener.onCallOutcome] carries beside it. */
    val callId: String = "",
    /** How long a call that connected lasted, in whole seconds ([Kind.ENDED]); null otherwise. */
    val durationSeconds: Int? = null,
    /** Graine rang it (a call booked with the agent): Graine hears this outcome too. */
    val hosted: Boolean = false,
    /** A [Kind.MISSED] ring refused because another call was live. */
    val busy: Boolean = false,
    /**
     * How the call connected ([GraineCallConnection]), as it stood when the
     * call ended — on [Kind.ENDED] and [Kind.FAILED]; null otherwise, and for
     * a call that never got as far as connecting.
     */
    val connection: GraineCallConnection? = null,
    /**
     * Who or what ended the call, on [Kind.ENDED] and [Kind.FAILED]:
     * `local_end` (this app or the user hung up), `remote_bye` (the agent's
     * side hung up), `media_drop` (the audio path dropped and did not
     * recover), `audio_watch` (nothing arrived for too long: the SDK's own
     * watch ended it) or `failed` (anything else — the call could not go on).
     * Null otherwise.
     */
    val endCause: String? = null,
) {
    enum class Kind(val wire: String) { ANSWERED("answered"), DECLINED("declined"), MISSED("missed"), ENDED("ended"), FAILED("failed") }

    /** LOCAL: this app hung up. REMOTE: the agent did. FAILED: the connection broke mid-call. */
    enum class EndReason(val wire: String) { LOCAL("local"), REMOTE("remote"), FAILED("failed") }

    /** One word for the app's server: answered · declined · missed · ended · failed. */
    val wire: String get() = kind.wire

    /** WIRE.md's `outcome`: the same word as [wire]. */
    val outcome: String get() = kind.wire

    /** Why, in one word or sentence: who ended a call ([Kind.ENDED]), why it failed ([Kind.FAILED]), `busy` for a refused ring. */
    val reason: String? get() = when (kind) {
        Kind.ENDED -> endReason?.wire
        Kind.FAILED -> error
        Kind.MISSED -> if (busy) "busy" else null
        else -> null
    }

    companion object {
        @JvmField val ANSWERED = GraineCallOutcome(Kind.ANSWERED)
        @JvmField val DECLINED = GraineCallOutcome(Kind.DECLINED)
        @JvmField val MISSED = GraineCallOutcome(Kind.MISSED)
        @JvmStatic @JvmOverloads fun ended(reason: EndReason, error: String? = null) = GraineCallOutcome(Kind.ENDED, reason, error)
        @JvmStatic fun failed(error: String) = GraineCallOutcome(Kind.FAILED, null, error)
    }
}

/**
 * WHAT A LOST FOREGROUND SERVICE DOES TO A CALL — the rule, pure. The service
 * keeps the microphone and the process alive in the background and carries the
 * ongoing notification; it never carries the call's audio. So a call already
 * dialled CARRIES ON without it; one still waiting for it STARTS NOW when the
 * app is in front and unlocked (Android lets an app in front record); anything
 * else cannot record, and FAILS.
 */
internal object CallServiceLoss {
    enum class Act { CARRY_ON, START_NOW, FAIL }

    fun act(dialled: Boolean, inFront: Boolean, locked: Boolean): Act = when {
        dialled -> Act.CARRY_ON
        inFront && !locked -> Act.START_NOW
        else -> Act.FAIL
    }
}

/** Why a call ended — [GraineCallOutcome.endCause], and the `ended` row's `cause`. */
internal object CallEnd {
    const val LOCAL_END = "local_end"
    const val REMOTE_BYE = "remote_bye"
    const val MEDIA_DROP = "media_drop"
    const val AUDIO_WATCH = "audio_watch"
    const val FAILED = "failed"

    /** An end nobody said more about, from its outcome: who ended it, else a failure. */
    fun of(o: GraineCallOutcome): String = when (o.endReason) {
        GraineCallOutcome.EndReason.LOCAL -> LOCAL_END
        GraineCallOutcome.EndReason.REMOTE -> REMOTE_BYE
        else -> FAILED
    }
}

/** Everything a native call reports, on the main thread. Every method is optional. */
interface GraineCallListener {
    /** A push rang the phone. The SDK shows the ring; this is for the app's own UI or analytics. */
    fun onIncomingCall(call: GraineCall) {}
    /** The call moved — [call] is null once it is back to IDLE. */
    fun onCallState(state: GraineCallState, call: GraineCall?) {}
    /** What became of a call. Send it to your server with [callId]. */
    fun onCallOutcome(callId: String, outcome: GraineCallOutcome) {}
    /** The agent's words as they are said (live), then the line once its turn is complete; the customer's likewise. */
    fun onCallCaption(role: Role, text: String, live: Boolean) {}
    /** The agent's voice started or stopped being heard. */
    fun onAgentSpeaking(speaking: Boolean) {}
    /** Muted or speaker changed — from this app, or from the call screen. */
    fun onCallControls(muted: Boolean, speaker: Boolean) {}
    /**
     * The agent's voice level, 0–1 (linear), as it is heard: about five times
     * a second on a call carried over WebRTC, about ten times a second on the
     * WebSocket while the agent's audio plays (0 once it has played out) —
     * what the call screen's visualiser follows.
     */
    fun onAgentLevel(level: Float) {}
    /**
     * The call's connection dropped after the call was up and is being
     * recovered (true), or it is back (false). On calls carried over WebRTC;
     * false is told again if the call ends while recovering. The SDK's call
     * screen shows its reconnecting moment meanwhile.
     */
    fun onCallReconnecting(reconnecting: Boolean) {}
    /**
     * The call's connection report grew ([GraineCalls.connection]): the
     * transport and the tap's wait once it connected, the agent's first
     * words, a recovery, the audio — about once a second on WebRTC while the
     * call is live.
     */
    fun onCallConnection(connection: GraineCallConnection) {}
}

/**
 * HOW A CALL CONNECTED — the same facts as the call's Connection in
 * Execution Logs, live while the call goes ([GraineCalls.connection],
 * [GraineCallListener.onCallConnection]) and final on its outcome
 * ([GraineCallOutcome.connection]). Each fact is null until it is known (a
 * call carried over the WebSocket has no audio figures).
 * Timings and network figures only — nothing personal.
 *
 * The tap's wait, in order: [setupMs], [loginMs], [answerMs], [firstWordsMs];
 * [tapToVoiceMs] is all of it, as the customer felt it.
 */
data class GraineCallConnection @JvmOverloads constructor(
    /** What carried the call: "webrtc" or "websocket". */
    val transport: String? = null,
    /** Prepared before the tap ([GraineCalls.prepareCall], or a ring's, ready by its answer); false: set up after the tap. */
    val prepared: Boolean? = null,
    /** How long before the tap a prepared call was ready, in seconds. */
    val preparedAgeSeconds: Long? = null,
    /** Where the call's credential came from: "direct" (issued directly, WebRTC) or "web" (issued through the web). */
    val credential: String? = null,
    /** From the tap to the call being placed — without the login, on a call set up after the tap. */
    val setupMs: Long? = null,
    /** The device signing in to the call server, inside the setup (a prepared call logged in before the tap: null). */
    val loginMs: Long? = null,
    /** From the call being placed to it being answered. */
    val answerMs: Long? = null,
    /** From the answer to the agent's voice reaching the phone. */
    val firstWordsMs: Long? = null,
    /** From the tap to the agent's voice: the wait the customer felt. */
    val tapToVoiceMs: Long? = null,
    /** The audio — the readings so far while the call is live, the call's own at its end. WebRTC only. */
    val audio: Audio? = null,
    /** How many times the connection recovered on its own. */
    val recoveries: Int = 0,
) {
    /** The audio as the phone measured it. */
    data class Audio @JvmOverloads constructor(
        /** Packets lost of all the far side sent, in percent (two decimals). */
        val lossPct: Double? = null,
        val jitterAvgMs: Long? = null,
        val jitterPeakMs: Long? = null,
        /** The round trip, on average. */
        val rttMs: Long? = null,
        /** In a word: [GOOD], [FAIR] or [POOR] — Execution Logs' verdict, on the thresholds of [ConnectionQuality]. */
        val quality: String? = null,
    )

    companion object {
        const val GOOD = "good"
        const val FAIR = "fair"
        const val POOR = "poor"
    }
}

/** How native calls look and ring. Pass to GraineCalls.configure once, in Application.onCreate. */
data class GraineCallsConfig @JvmOverloads constructor(
    /** The agent's name on the ring and the call screen, when the push names no caller. */
    val displayName: String = "Assistant",
    /** A monochrome drawable for the notifications' status-bar icon. 0 → the platform's call icon. */
    val smallIcon: Int = 0,
    /** The ring. Null → the phone's own ringtone. */
    val ringtone: Uri? = null,
    /** The app's own call screen. Null → the SDK's GraineCallActivity. */
    val callActivity: Class<out Activity>? = null,
    /** How long an incoming call rings before it is missed. */
    val ringTimeoutMs: Long = 30_000,
    /**
     * How a call carries its audio: [GraineCallTransport.AUTO] (WebRTC when
     * the app includes `ai.graine:inapp-android-rtc`, else the WebSocket),
     * WEBSOCKET or WEBRTC. A call that cannot be placed over WebRTC continues
     * over the WebSocket within the same call.
     */
    val callTransport: GraineCallTransport = GraineCallTransport.AUTO,
)

/**
 * THE ONE CALL, AS STATES AND OUTCOMES. Main-thread state in the SDK; here,
 * just rules — the clock is [schedule], the effects are callbacks.
 *
 *   idle → ringing (incoming) → connecting → active → ended → idle
 *   idle → connecting (outgoing) → active → ended → idle
 *
 * One call at a time: a push while a call is live is reported MISSED for its
 * own id and leaves the live call alone — unless it is the live call's own id
 * again, which is ignored; a second outgoing call is refused.
 */
/** Who the agent hears called for an answered ring: "scheduled" for a call Graine rang (hosted), else "agent". */
internal fun whoCalled(hosted: Boolean): String = if (hosted) "scheduled" else "agent"

internal class CallMachine(
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val ringTimeoutMs: () -> Long,
    private val onState: (GraineCallState, GraineCall?) -> Unit,
    private val onOutcome: (String, GraineCallOutcome) -> Unit,
) {
    var state = GraineCallState.IDLE
        private set
    var call: GraineCall? = null
        private set
    private var cancelRing: (() -> Unit)? = null

    /** How long the ringing call rings before it is missed: its push's own time (a ring Graine sends), else the configured one. */
    var ringingMs = 0L
        private set

    val live: Boolean get() = state != GraineCallState.IDLE

    /** A push. Null when a call is live: another call's push was reported missed, the live call's own was ignored. */
    fun incoming(push: AgentCallPush.Call): GraineCall? {
        if (live) {
            // The same call pushed twice (a push can be delivered again): one ring is enough, and
            // it is not missed — a missed outcome would tell the server the call in progress was.
            if (push.callId != call?.callId) onOutcome(push.callId, GraineCallOutcome.MISSED)
            return null
        }
        val vars = LinkedHashMap<String, String>()
        // Who the agent hears called (contract §3.4): "scheduled" for a call Graine rang at a booked time,
        // "agent" for a ring from the app's own server. Graine adds the booking's reason when the call connects.
        vars["who_called"] = whoCalled(push.hosted)
        push.reason?.let { vars["call_reason"] = it }
        for ((k, v) in push.variables) if (k != "who_called" && !(k == "call_reason" && push.reason != null)) vars[k] = v
        val c = GraineCall(push.callId, GraineCall.Direction.INCOMING, push.callerName, push.reason, vars, push.hosted)
        call = c
        // Known before the ring is shown: its notification rings for exactly as long.
        ringingMs = push.ringTimeoutMs?.takeIf { it > 0 } ?: ringTimeoutMs()
        move(GraineCallState.RINGING)
        cancelRing = schedule(ringingMs) { ringTimedOut(c.callId) }
        return c
    }

    /** The customer places a call. Null when another call is live. */
    fun outgoing(callId: String, reason: String?, variables: Map<String, String>): GraineCall? {
        if (live) return null
        val c = GraineCall(callId, GraineCall.Direction.OUTGOING, null, reason, outgoingVariables(reason, variables))
        call = c
        move(GraineCallState.CONNECTING)
        return c
    }

    companion object {
        /**
         * A call the customer places carries these as its own: `who_called` is
         * "customer", [reason] is `call_reason`, and the app's [variables] fill
         * the rest. One rule for the call and for the call prepared ahead of it
         * (GraineCalls.prepareCall), so the two compare alike.
         */
        fun outgoingVariables(reason: String?, variables: Map<String, String>): Map<String, String> {
            val vars = LinkedHashMap<String, String>()
            vars["who_called"] = "customer"
            reason?.let { vars["call_reason"] = it }
            for ((k, v) in variables) if (k != "who_called" && !(k == "call_reason" && reason != null)) vars[k] = v
            return vars
        }
    }

    fun answer(): Boolean {
        val c = call ?: return false
        if (state != GraineCallState.RINGING) return false
        stopRing()
        onOutcome(c.callId, GraineCallOutcome.ANSWERED)
        move(GraineCallState.CONNECTING)
        return true
    }

    fun decline(): Boolean = finishRinging(GraineCallOutcome.DECLINED)

    /** The line is open: the agent can hear the customer. */
    fun connected(): Boolean {
        if (state != GraineCallState.CONNECTING) return false
        move(GraineCallState.ACTIVE)
        return true
    }

    /**
     * The call is over. While ringing that is a decline. While connecting, a
     * local end is ENDED(LOCAL); anything else there is a call that never
     * connected — FAILED. While active it is ENDED with [reason].
     */
    fun end(reason: GraineCallOutcome.EndReason, error: String? = null): Boolean {
        val c = call ?: return false
        return when (state) {
            GraineCallState.RINGING -> decline()
            GraineCallState.CONNECTING -> {
                finish(c, if (reason == GraineCallOutcome.EndReason.LOCAL) GraineCallOutcome.ended(reason)
                    else GraineCallOutcome.failed(error ?: "The call could not be connected."))
                true
            }
            GraineCallState.ACTIVE -> { finish(c, GraineCallOutcome.ended(reason, error)); true }
            else -> false
        }
    }

    /** The call could not go on: never connected → FAILED; connected → ENDED(FAILED). */
    fun fail(error: String): Boolean {
        val c = call ?: return false
        return when (state) {
            GraineCallState.RINGING, GraineCallState.CONNECTING -> { stopRing(); finish(c, GraineCallOutcome.failed(error)); true }
            GraineCallState.ACTIVE -> { finish(c, GraineCallOutcome.ended(GraineCallOutcome.EndReason.FAILED, error)); true }
            else -> false
        }
    }

    private fun ringTimedOut(callId: String) {
        cancelRing = null
        if (call?.callId == callId && state == GraineCallState.RINGING) finishRinging(GraineCallOutcome.MISSED)
    }

    private fun finishRinging(outcome: GraineCallOutcome): Boolean {
        val c = call ?: return false
        if (state != GraineCallState.RINGING) return false
        stopRing()
        finish(c, outcome)
        return true
    }

    private fun stopRing() { cancelRing?.invoke(); cancelRing = null }

    private fun finish(c: GraineCall, outcome: GraineCallOutcome) {
        onOutcome(c.callId, outcome)
        move(GraineCallState.ENDED)
        // Reported, then idle: the next call may start.
        call = null
        move(GraineCallState.IDLE)
    }

    private fun move(next: GraineCallState) {
        state = next
        onState(next, call)
    }
}
