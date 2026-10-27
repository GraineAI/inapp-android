@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import android.content.Context
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

    /** What the app asked for, as the transport row (CallAnalytics) says it. */
    fun requested(app: GraineCallTransport): String = when (app) {
        GraineCallTransport.AUTO -> "auto"
        GraineCallTransport.WEBRTC -> "rtc"
        GraineCallTransport.WEBSOCKET -> "ws"
    }

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

/**
 * One call's transport: what was decided at the tap or the ring, and what was
 * prepared for it. [requested] is what the app asked for ("auto", "rtc", "ws"),
 * and [fallbackReason] why WebRTC is not carrying the call — for its
 * transport row.
 */
internal class CallTransportPlan(
    val callId: String,
    val decision: CallTransportChoice.Decision,
    val owner: CallTransportOwner,
    val requested: String? = null,
) {
    @Volatile var prepared: RtcCallSession? = null
    @Volatile var preparedRequest: RtcCallRequest? = null
    @Volatile var fallbackReason: String? = decision.reason
}

/** What a call is prepared for, compared whole: the agent, who the user is — every field a mint carries — and the call's variables. */
internal data class PrepareKey(val agent: GraineInApp, val identity: IdentityEnvelope?, val variables: Map<String, String>)

/**
 * THE NEXT CALL, PREPARED AHEAD OF THE TAP (GraineCalls.prepareCall): minted
 * — at the telephony gateway with the warm grant for its id, else through the
 * web route — its peer's candidate pool gathering and its socket registered,
 * and never dialled until startCall takes it for exactly what it was prepared
 * for ([key]). Nothing is billed, and nothing records: no offer is made, no
 * INVITE leaves, no conversation starts.
 */
internal class PreparedCall(val key: PrepareKey, val callId: String, val request: RtcCallRequest, val session: RtcCallSession, val preparedAt: Long) {
    /** Its four minutes' timer. Main thread. */
    var expiry: (() -> Unit)? = null
    /** It can no longer be dialled: the registration or the socket went, the mint failed. */
    @Volatile var lost = false
}

/**
 * THE TRANSPORT OF GraineCalls' CALLS: decided when a call starts — at the
 * tap, or when a ring arrives — and prepared there, so that the dial finds the
 * WebRTC call registered and its candidates pooled, and its offer and INVITE go
 * at once. A ring declined, missed or failed releases what
 * was prepared; nothing was ever dialled. Keeps what the call snapshot shows:
 * [transport] and [fallbackReason].
 *
 * Earlier still, when the app asks (GraineCalls.prepareCall): each agent's
 * next call, prepared before the tap — at most one per agent, for four
 * minutes, and only for exactly what it was prepared for. The tap that
 * matches takes it, and its dial is the INVITE.
 */
internal class CallTransports(
    private val context: Context,
    private val callsConfig: () -> GraineCallsConfig,
    private val agentFor: (String?) -> GraineInApp,
    private val scheduler: () -> CallScheduler,
    /** [task] on the main thread after [delayMs]; the returned function cancels it. */
    private val mainAfter: (delayMs: Long, task: () -> Unit) -> (() -> Unit),
    /** A clock that counts the phone's sleep (elapsedRealtime): a prepared call's age. */
    private val clock: () -> Long,
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
            callsConfig().callTransport, agent.schedulingRules.audioTransport, RtcModule.media() != null, RtcModule.runsHere(),
        )
        transport = if (decision.webrtc) CallTransportChoice.WEBRTC else CallTransportChoice.WEBSOCKET
        fallbackReason = decision.reason
        decision.reason?.let { announce(it, null) }
        val plan = CallTransportPlan(c.callId, decision, this, CallTransportChoice.requested(callsConfig().callTransport))
        put(plan)
        // Prepared ahead of the tap (prepareCall) and taken by startCall: minted and registered already, so the dial is the INVITE.
        val ahead = adopted.remove(c.callId)
        if (!decision.webrtc) {
            ahead?.session?.hangUp()
            return
        }
        if (ahead != null) {
            plan.prepared = ahead.session
            plan.preparedRequest = ahead.request
            return
        }
        // A ring: its own grant, wanted now, so the mint at the answer can go to the gateway (RtcDirect.kt).
        if (c.direction == GraineCall.Direction.INCOMING) runCatching { agent.warmRing(c.callId) }
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
        plan.fallbackReason = reason
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

    // ── the call prepared ahead of the tap ──────────────────────────────────

    /** Each agent's prepared call: at most one. Main thread. */
    private val preparedCalls = HashMap<GraineInApp, PreparedCall>()
    /** Prepared calls startCall took, until their call's plan claims them, by call id. Main thread. */
    private val adopted = HashMap<String, PreparedCall>()

    /** What a call placed with [agent] now, for [reason] and [variables], is prepared for: who the user is now, and what the call carries. */
    private fun keyFor(agent: GraineInApp, reason: String?, variables: Map<String, String>): PrepareKey =
        PrepareKey(agent, agent.identityEnvelope(), RtcMint.variables(CallMachine.outgoingVariables(reason, variables), agent.currentVariables()))

    /** A call placed with [agent] now would go over WebRTC: the decision the call itself makes. */
    private fun webRtcFor(agent: GraineInApp): Boolean =
        CallTransportChoice.decide(callsConfig().callTransport, agent.schedulingRules.audioTransport, RtcModule.media() != null, RtcModule.runsHere()).webrtc

    /**
     * Prepare [agent]'s next call for [reason] and [variables] — the mint, the
     * candidates, the registration — so its tap is the INVITE. Nothing at all
     * when a call prepared for exactly this is in hand, younger than four
     * minutes and still registered; otherwise that one goes and this one is
     * prepared. Nothing either before the session, or for calls that do not go
     * over WebRTC (no module, the app's or the dashboard's WebSocket). Main
     * thread; no call or ring is live (the caller asked).
     */
    fun prepare(agent: GraineInApp, reason: String?, variables: Map<String, String>) {
        if (agent.session == null || !webRtcFor(agent)) return
        val key = keyFor(agent, reason, variables)
        preparedCalls[agent]?.let { held ->
            if (held.key == key && !held.lost && clock() - held.preparedAt < PREPARED_MAX_AGE_MS) return
            release(held)
        }
        // The id the warm grant was asked for, when there is one: this mint then goes to the gateway (RtcDirect.kt).
        val callId = agent.nextCallId() ?: CallIds.next()
        val kept = agent.cachedSession
        val request = RtcCallRequest(
            baseUrl = agent.config.baseUrl,
            publishableKey = agent.config.publishableKey,
            variables = key.variables,
            identity = key.identity,
            callId = callId,
            wsHost = agent.session?.wsHost ?: kept?.wsHost,
        )
        val session = RtcCallSession(request, env(agent))
        val p = PreparedCall(key, callId, request, session, clock())
        preparedCalls[agent] = p
        p.expiry = mainAfter(PREPARED_MAX_AGE_MS) { if (preparedCalls[agent] === p) release(p) }
        session.prepare(object : RtcCallSession.Preparation {
            // Its registration or socket went, or its mint failed: let go without a word, and the tap prepares as always.
            override fun onLost(why: String) {
                p.lost = true
                mainAfter(0) { if (preparedCalls[agent] === p) release(p) }
            }
        })
    }

    /**
     * startCall's: the call prepared for exactly the call being placed — the
     * same agent, the same user in every field, the same reason and variables,
     * younger than four minutes, still registered — whose id the call takes,
     * and whose session its plan takes, so the dial is the INVITE. Else null,
     * and what was prepared goes: the tap prepares, as it always did. Every
     * other agent's prepared call goes too — one call at a time. Main thread.
     */
    fun takePrepared(agent: GraineInApp, reason: String?, variables: Map<String, String>): String? {
        val p = preparedCalls.remove(agent)
        releasePrepared()
        if (p == null) return null
        p.expiry?.invoke()
        p.expiry = null
        if (p.lost || p.key != keyFor(agent, reason, variables) || clock() - p.preparedAt >= PREPARED_MAX_AGE_MS) {
            p.session.hangUp()
            return null
        }
        adopted[p.callId] = p
        return p.callId
    }

    /** A call startCall placed with a prepared call did not start after all: the prepared call goes. Main thread. */
    fun dropTaken(callId: String) {
        adopted.remove(callId)?.session?.hangUp()
    }

    /** Let go of every prepared call [which] picks: nothing more is sent for it, and nothing was dialled or billed. Main thread. */
    fun releasePrepared(which: (PreparedCall) -> Boolean = { true }) {
        for (p in preparedCalls.values.filter(which)) release(p)
    }

    /** Who the user is changed for [agent] — identify, sign-out, the memory choice, the time zone: a call prepared for anyone else goes. */
    fun whoChanged(agent: GraineInApp, who: IdentityEnvelope) = releasePrepared { it.key.agent === agent && it.key.identity != who }

    /** The calls' configuration changed: a call prepared for an agent whose calls no longer go over WebRTC goes. */
    fun transportChanged() = releasePrepared { !webRtcFor(it.key.agent) }

    /** [agent]'s prepared call, if any — what the tests look at. Main thread. */
    internal fun preparedFor(agent: GraineInApp): PreparedCall? = preparedCalls[agent]

    private fun release(p: PreparedCall) {
        if (preparedCalls[p.key.agent] === p) preparedCalls.remove(p.key.agent)
        p.expiry?.invoke()
        p.expiry = null
        p.session.hangUp()
    }

    private fun env(agent: GraineInApp): RtcEnv {
        agent.env.rtcEnv?.let { return it(agent) }
        val media = RtcModule.media() ?: error("the WebRTC module is not in this build")
        val appContext = context.applicationContext ?: context
        return RtcEnv(
            scheduler = scheduler(),
            createPeer = { servers, listener -> media.createPeer(appContext, servers, listener) },
            http = agent.env.http,
            // The grant this agent kept warm for the call (RtcDirect.kt): its mint goes to the gateway.
            direct = agent.env.directMint,
            // The phone's network, for the call's recovery (never restarted or redialled into none).
            network = runCatching { AndroidRtcNetwork(appContext) }.getOrDefault(RtcNetwork.NONE),
            // The web route's mint carries the agent's server token (CLIENT-TOKENS.md).
            tokens = agent.clientTokens,
        )
    }

    companion object {
        /**
         * How long a prepared call is kept: the voice bar's prepared peer's
         * life — well inside a relay allocation's ten minutes, and the
         * credential's — after which it is let go and the tap prepares again.
         */
        const val PREPARED_MAX_AGE_MS = 4 * 60_000L

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
 *
 * Its analytics rows (CallAnalytics) go on whichever channel carries the
 * conversation, counted from the tap: the WebRTC call's own, or the voice
 * socket's — which then also says why WebRTC is not carrying the call, and
 * carries what the WebRTC attempt left behind.
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
    /** Rows a WebRTC attempt left before it fell back, for the WebSocket conversation. Scheduler thread. */
    private var carried: List<String> = emptyList()

    fun start() = deps.scheduler.post {
        if (hungUp) return@post
        val p = plan
        val r = p?.owner?.rtcFor(p, config)
        if (p == null || r == null) { carryOnSocket(); return@post }
        rtc = r
        if (hungUp) { r.hangUp(); return@post }
        r.setMuted(muted)
        if (onHold) r.setOnHold(true)
        r.dial(RtcDialDeps(deps.runAction, deps.contextFrame, config.wsHost, config.tapAt, p.requested), object : RtcCallSession.Listener {
            override fun onMedia() {
                p.owner.carried(p)
                events.onOpen()
            }
            override fun onRows(frames: List<String>) { carried = carried + frames }
            override fun onFallback(reason: String, detail: String) {
                p.owner.fellBack(p, reason, detail)
                rtc = null
                if (!hungUp) carryOnSocket(reason)
            }
            override fun onRefused(message: String) {
                // As the WebSocket shows the same refusal: its socket opens, then the far side closes it with
                // this reason — so the call ends `ended` / `remote` with this message, never a second attempt.
                events.onOpen()
                events.onEnded(GraineCallOutcome.EndReason.REMOTE, message)
            }
            // Refused for the server token or the key: as the WebSocket's ticket would be — the call fails before it
            // starts, with the code and Graine's sentence, and no second attempt.
            override fun onDenied(error: String) = events.onFailed(error)
            override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) = events.onEnded(reason, error)
            override fun onCaption(role: Role, text: String, live: Boolean) = events.onCaption(role, text, live)
            override fun onAgentSpeaking(speaking: Boolean) = events.onAgentSpeaking(speaking)
            override fun onAudioStats(stats: GraineRtcStats) = events.onAgentAudio(stats.audioLevel, stats.totalAudioEnergy, stats.totalSamplesDuration)
            override fun onReconnecting(reconnecting: Boolean) = events.onReconnecting(reconnecting)
            override fun onRow(name: String, data: Map<String, Any?>) = events.onAnalytics(name, data)
        })
    }

    /** The WebSocket carries the conversation: [why] WebRTC is not, when it was meant to. */
    private fun carryOnSocket(why: String? = plan?.fallbackReason) {
        val s = VoiceCallSession(config, deps, events, SocketRows(plan?.requested, why, carried))
        socket = s
        s.setMuted(muted)
        if (onHold) s.setOnHold(true)
        s.start()
        if (hungUp) s.hangUp()
    }

    /** Hang up whichever is carrying the call, saying why ([cause]). The WebSocket's audio is let go on this thread, at once. */
    fun hangUp(cause: String = CallEnd.LOCAL_END) {
        hungUp = true
        socket?.hangUp(cause)
        rtc?.hangUp(cause)
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
