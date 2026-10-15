@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * How a native call ([GraineCalls]) carries its audio. Set in
 * [GraineCallsConfig.callTransport]; the dashboard can still turn WebRTC off
 * for an agent, whatever the app asked.
 */
enum class GraineCallTransport {
    /**
     * WebRTC when the app includes the WebRTC module
     * (`ai.graine:inapp-android-rtc`), else the WebSocket. The default.
     */
    AUTO,
    /** Always the WebSocket. */
    WEBSOCKET,
    /** WebRTC; the WebSocket only when the WebRTC module is missing, or WebRTC cannot place this call. */
    WEBRTC,
}

/** WHICH TRANSPORT A CALL TRIES — the rule, pure (WEBRTC-CALLS.md §3). */
internal object CallTransportChoice {
    /** [webrtc]: try it. [reason]: why a call that wanted WebRTC will not try it (null when it did not want it). */
    class Decision(val webrtc: Boolean, val reason: String?)

    /**
     * The app's word ([app]), then the dashboard's switch ([dashboard]:
     * "websocket" turns WebRTC off whatever the app asked), then whether this
     * build can run it ([moduleAvailable], [osSupported]). AUTO wants WebRTC
     * only where the module is.
     */
    fun decide(app: GraineCallTransport, dashboard: String?, moduleAvailable: Boolean, osSupported: Boolean): Decision {
        if (app == GraineCallTransport.WEBSOCKET) return Decision(false, null)
        val wanted = app == GraineCallTransport.WEBRTC || moduleAvailable
        if (dashboard.equals(WEBSOCKET, ignoreCase = true)) return Decision(false, if (wanted) RtcFallback.DASHBOARD else null)
        if (!moduleAvailable || !osSupported) return Decision(false, if (app == GraineCallTransport.WEBRTC) RtcFallback.UNAVAILABLE else null)
        return Decision(true, null)
    }

    const val WEBRTC = "webrtc"
    const val WEBSOCKET = "websocket"

    /** The oldest Android the WebRTC library runs on is older than the SDK's own minimum (24). */
    const val MIN_SDK = 24
}

/** What a call's session asks of whoever planned its transport (CallTransports; a fake in the tests). */
internal interface CallTransportOwner {
    /** The WebRTC call to dial now, or null for the WebSocket. */
    fun rtcFor(plan: CallTransportPlan, config: CallSessionConfig): RtcCallSession?
    /** WebRTC carries the call. */
    fun carried(plan: CallTransportPlan)
    /** WebRTC could not carry the call: it goes on, on the WebSocket. */
    fun fellBack(plan: CallTransportPlan, reason: String, detail: String)
}

/** One call's transport: what was decided at the tap or the ring, and what was prepared for it. */
internal class CallTransportPlan(val callId: String, val decision: CallTransportChoice.Decision, val owner: CallTransportOwner) {
    @Volatile var prepared: RtcCallSession? = null
    @Volatile var preparedRequest: RtcCallRequest? = null
}

/**
 * THE TRANSPORT OF GraineCalls' CALLS: decided when a call starts — at the
 * tap, or when a ring arrives — and prepared there, so that the call's
 * foreground service finds the WebRTC call registered and its candidates
 * gathered, and dials at once. A ring declined, missed or failed releases what
 * was prepared; nothing was ever dialled. Keeps what the call snapshot shows:
 * [transport] and [fallbackReason].
 */
internal class CallTransports(
    private val context: Context,
    private val callsConfig: () -> GraineCallsConfig,
    private val agentFor: (String?) -> GraineInApp,
    private val scheduler: () -> CallScheduler,
) : CallTransportOwner {
    /** The call in progress's transport: "webrtc" or "websocket"; null with no call. */
    @Volatile var transport: String? = null
        private set
    /** Why a call that wanted WebRTC is on the WebSocket; null otherwise. */
    @Volatile var fallbackReason: String? = null
        private set
    @Volatile private var currentCall: String? = null

    /** The call moved: plan it when it starts, release what it did not use when it is over. Main thread. */
    fun onState(s: GraineCallState, c: GraineCall?) {
        when (s) {
            GraineCallState.RINGING, GraineCallState.CONNECTING -> if (c != null && plans[c.callId] == null && currentCall != c.callId) plan(c)
            GraineCallState.ENDED -> c?.let { release(it.callId) }
            GraineCallState.IDLE -> { transport = null; fallbackReason = null; currentCall = null }
            GraineCallState.ACTIVE -> Unit
        }
    }

    private fun plan(c: GraineCall) {
        currentCall = c.callId
        val agent = agentFor(c.callId)
        val decision = CallTransportChoice.decide(
            callsConfig().callTransport, agent.schedulingRules.audioTransport, RtcModule.media() != null,
            Build.VERSION.SDK_INT >= CallTransportChoice.MIN_SDK,
        )
        transport = if (decision.webrtc) CallTransportChoice.WEBRTC else CallTransportChoice.WEBSOCKET
        fallbackReason = decision.reason
        decision.reason?.let { announce(it, null) }
        val plan = CallTransportPlan(c.callId, decision, this)
        put(plan)
        if (!decision.webrtc) return
        // What the mint will carry: the call's variables, the app's, who the user is, and the call's id.
        val kept = agent.cachedSession
        val request = RtcCallRequest(
            baseUrl = agent.config.baseUrl,
            publishableKey = agent.config.publishableKey,
            variables = RtcMint.variables(c.variables, agent.currentVariables()),
            identity = runCatching { agent.identityEnvelope() }.getOrNull(),
            callId = c.callId,
            wsHost = agent.session?.wsHost ?: kept?.wsHost,
        )
        val rtc = RtcCallSession(request, env(agent))
        plan.prepared = rtc
        plan.preparedRequest = request
        rtc.prepare()
    }

    /**
     * The WebRTC call to dial now, at the call's start (the scheduler's
     * thread), or null for the WebSocket: the dashboard is asked again (its
     * session may only have arrived now), and a call prepared with other
     * variables or another user — the app identified someone meanwhile — is
     * minted again rather than dialled with what the agent should not hear.
     */
    override fun rtcFor(plan: CallTransportPlan, config: CallSessionConfig): RtcCallSession? {
        val prepared = plan.prepared
        plan.prepared = null
        if (!plan.decision.webrtc) { prepared?.hangUp(); return null }
        val agent = agentFor(plan.callId)
        if (agent.schedulingRules.audioTransport.equals(CallTransportChoice.WEBSOCKET, ignoreCase = true)) {
            prepared?.hangUp()
            fellBack(plan, RtcFallback.DASHBOARD, "the dashboard's call audio is the WebSocket")
            return null
        }
        val request = RtcCallRequest.of(config)
        if (prepared != null && plan.preparedRequest?.mintKey == request.mintKey) return prepared
        if (prepared != null) {
            CallLog.debug("webrtc: the call's variables changed after it was prepared; minting again")
            prepared.hangUp()
        }
        return RtcCallSession(request, env(agent))
    }

    override fun carried(plan: CallTransportPlan) {
        if (currentCall != plan.callId) return
        transport = CallTransportChoice.WEBRTC
        fallbackReason = null
    }

    override fun fellBack(plan: CallTransportPlan, reason: String, detail: String) {
        if (currentCall == plan.callId) {
            transport = CallTransportChoice.WEBSOCKET
            fallbackReason = reason
        }
        announce(reason, detail)
    }

    /** Said in the log, always: a permanent misconfiguration should read as a fallback on every call, not as nothing. */
    private fun announce(reason: String, detail: String?) {
        Log.w(GraineInApp.TAG, "This call's audio goes over the WebSocket, not WebRTC ($reason${detail?.let { ": $it" } ?: ""}).")
    }

    private fun release(callId: String) {
        val plan = plans.remove(callId) ?: return
        plan.prepared?.hangUp()
        plan.prepared = null
    }

    private fun env(agent: GraineInApp): RtcEnv {
        val media = RtcModule.media() ?: error("the WebRTC module is not in this build")
        val appContext = context.applicationContext ?: context
        return RtcEnv(
            scheduler = scheduler(),
            createPeer = { servers, listener -> media.createPeer(appContext, servers, listener) },
            http = agent.env.http,
        )
    }

    companion object {
        /** Each call's plan until its session claims it, by call id. One call engine per app, so one table. */
        private val plans = ConcurrentHashMap<String, CallTransportPlan>()

        /** The plan made for [callId] at its start, taken by the session that carries the call. */
        fun claim(callId: String?): CallTransportPlan? = callId?.let { plans.remove(it) }

        /** A plan for a call — made at its start; the tests make their own. */
        fun put(plan: CallTransportPlan) { plans[plan.callId] = plan }
    }
}

/**
 * ONE CALL'S CONVERSATION, OVER WHICHEVER TRANSPORT CARRIES IT — the session
 * GraineCalls drives (start, hang up, mute, hold, the screen), with the same
 * events as the WebSocket's [VoiceCallSession].
 *
 * It dials over WebRTC when the call's plan says so; if WebRTC cannot carry the
 * call before any audio flowed, the WebSocket session starts INSIDE THE SAME
 * CALL — the same ring, notification, call screen and foreground service, the
 * screen still saying "Connecting…" — and the plan records why. Once WebRTC
 * carries audio it never moves: a second conversation would be worse than a
 * dropped one.
 */
internal class CallTransportSession(
    private val config: CallSessionConfig,
    private val deps: CallSessionDeps,
    private val events: CallSessionEvents,
) {
    private val plan = CallTransports.claim(config.callId)
    @Volatile private var rtc: RtcCallSession? = null
    @Volatile private var socket: VoiceCallSession? = null
    @Volatile private var hungUp = false
    @Volatile private var muted = false
    @Volatile private var onHold = false

    fun start() = deps.scheduler.post {
        if (hungUp) return@post
        val p = plan
        val r = p?.owner?.rtcFor(p, config)
        if (p == null || r == null) { carryOnSocket(); return@post }
        rtc = r
        if (hungUp) { r.hangUp(); return@post }
        r.setMuted(muted)
        if (onHold) r.setOnHold(true)
        r.dial(RtcDialDeps(deps.runAction, deps.contextFrame, config.wsHost), object : RtcCallSession.Listener {
            override fun onMedia() {
                p.owner.carried(p)
                events.onOpen()
            }
            override fun onFallback(reason: String, detail: String) {
                p.owner.fellBack(p, reason, detail)
                rtc = null
                if (!hungUp) carryOnSocket()
            }
            override fun onRefused(message: String) {
                // As the WebSocket shows the same refusal: its socket opens, then the far side closes it with
                // this reason — so the call ends `ended` / `remote` with this message, never a second attempt.
                events.onOpen()
                events.onEnded(GraineCallOutcome.EndReason.REMOTE, message)
            }
            override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) = events.onEnded(reason, error)
            override fun onCaption(role: Role, text: String, live: Boolean) = events.onCaption(role, text, live)
            override fun onAgentSpeaking(speaking: Boolean) = events.onAgentSpeaking(speaking)
        })
    }

    private fun carryOnSocket() {
        val s = VoiceCallSession(config, deps, events)
        socket = s
        s.setMuted(muted)
        if (onHold) s.setOnHold(true)
        s.start()
        if (hungUp) s.hangUp()
    }

    /** Hang up whichever is carrying the call. The WebSocket's audio is let go on this thread, at once. */
    fun hangUp() {
        hungUp = true
        socket?.hangUp()
        rtc?.hangUp()
    }

    fun setMuted(value: Boolean) {
        muted = value
        socket?.setMuted(value)
        rtc?.setMuted(value)
    }

    fun setOnHold(value: Boolean) {
        onHold = value
        socket?.setOnHold(value)
        rtc?.setOnHold(value)
    }

    fun sendContext(frame: JSONObject) {
        socket?.sendContext(frame)
        rtc?.sendContext(frame)
    }
}
