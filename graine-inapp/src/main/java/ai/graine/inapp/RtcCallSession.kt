@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONObject
import java.util.Locale

/** What a WebRTC call is for: who it reaches and what the agent is told — the mint's inputs. */
internal class RtcCallRequest(
    val baseUrl: String,
    val publishableKey: String,
    val variables: Map<String, String>,
    val identity: IdentityEnvelope?,
    /** The call's id, as the ticket carries it — for a call Graine rang, its graine_call_id. */
    val callId: String?,
    val wsHost: String?,
) {
    /** The mint's body without its session id: a prepared call is used only if this is unchanged. */
    val mintKey: String get() = RtcMint.body(publishableKey, "", variables, identity, callId).toString()

    companion object {
        fun of(config: CallSessionConfig) = RtcCallRequest(
            baseUrl = config.baseUrl,
            publishableKey = config.publishableKey,
            variables = RtcMint.variables(config.callVariables, config.sdkVariables),
            identity = config.identity,
            callId = config.callId,
            wsHost = config.wsHost,
        )
    }
}

/** Everything outside a WebRTC call, injected: the media, the network, the clocks. Fakes in the tests. */
internal class RtcEnv(
    val scheduler: CallScheduler,
    /** A peer connection for the call, from the WebRTC module. */
    val createPeer: (servers: List<GraineRtcIceServer>, listener: GraineRtcPeer.Listener) -> GraineRtcPeer,
    val http: HttpPost = PlatformHttp,
    /** Where blocking work (the mint) runs. */
    val io: (() -> Unit) -> Unit = { work -> Thread({ work() }, "graine-call-mint").apply { isDaemon = true }.start() },
    val openSip: (url: String, protocols: List<String>, listener: CallSocketListener) -> CallSocket =
        { url, protocols, listener -> WebSocketClient(url, protocols, listener).connect() },
    val openControl: (url: String, listener: CallSocketListener) -> CallSocket =
        { url, listener -> WebSocketClient(url, emptyList(), listener, openTimeoutMs = RtcControl.OPEN_TIMEOUT_MS).connect() },
    val sipRandom: SipRandom = SipRandom.SECURE,
    val newSessionId: () -> String = { RtcSessionId.next() },
    /** The wall clock, for the credential's expiry. */
    val wallClock: () -> Long = { System.currentTimeMillis() },
    /** Every SIP message both ways — the live test's trace. Never logged by the SDK. */
    val sipTrace: (direction: Char, text: String) -> Unit = { _, _ -> },
    /** Every step, from the first (the mint, before any dial) — the live test's timings. */
    val steps: (step: String, detail: String) -> Unit = { _, _ -> },
)

/** What the call needs from the app once it dials: its actions and its screen. */
internal class RtcDialDeps(
    val runAction: (name: String, args: JSONObject, answer: (ActionResult) -> Unit) -> Unit,
    val contextFrame: () -> JSONObject?,
    /** Where the agent's sockets live, as the session now says (a ring may have been prepared before it arrived). */
    val wsHost: String? = null,
)

/**
 * ONE NATIVE CALL ON WEBRTC — the voice bar's own sequence, done natively
 * (WEBRTC-CALLS.md §1; the bar's lib/webcall-rtc.ts).
 *
 *   prepare  the mint (credentials, ICE servers) → a peer connection gathering
 *            its candidates, and the signalling socket registering — together
 *   dial     the control channel, then an INVITE carrying the gathered offer →
 *            100 → 200 → ACK → the answer applied → ICE connected: the call is up
 *
 * [prepare] runs at the tap, or when a ring arrives; [dial] runs once the
 * call's foreground service is up, so the INVITE never leaves before the
 * phone may record. A dial that is not answered in time is tried once more on
 * a fresh connection when the failure was the network's; anything else before
 * media — the mint refused, no registration, no reply, no answer, no media
 * path, any error — is [Listener.onFallback]: the call goes on, on the
 * WebSocket, inside the same ring and screen. The whole attempt has 12 s.
 * After media nothing falls back: a lost path gets one ICE restart, then the
 * call ends as dropped.
 *
 * All state lives on the scheduler's thread.
 */
internal class RtcCallSession(private val request: RtcCallRequest, private val env: RtcEnv) {
    interface Listener {
        /** Media flows: ICE connected after the answer. */
        fun onMedia() {}
        /** WebRTC cannot carry this call, and nothing was heard yet: carry it on the WebSocket. Everything here is released. */
        fun onFallback(reason: String, detail: String) {}
        /**
         * Refused by a gate the WebSocket would refuse too (no credit, the
         * agent switched off or deleted): the call ends now, with [message] —
         * the WebSocket's own words for that refusal. Everything here is released.
         */
        fun onRefused(message: String) {}
        /** A call that had media is over: the agent hung up (REMOTE), or the connection was lost (FAILED). */
        fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) {}
        fun onCaption(role: Role, text: String, live: Boolean) {}
        fun onAgentSpeaking(speaking: Boolean) {}
        /** Each step and how long it took — the debug log and the live test. Never personal data. */
        fun onStep(step: String, detail: String) {}
        /** The control channel joined the call. */
        fun onControlJoined() {}
        /** The far side's audio, measured about five times a second while the call is up. */
        fun onAudioStats(stats: GraineRtcStats) {}
    }

    private enum class Phase { IDLE, PREPARING, DIALING, ANSWERED, LIVE, DONE }

    private val scheduler get() = env.scheduler
    private fun now(): Long = scheduler.now()

    /** The control channel's id, and the INVITE's join to it. */
    val sessionId: String = env.newSessionId()

    private var phase = Phase.IDLE
    private var listener: Listener = object : Listener {}
    private var deps: RtcDialDeps? = null
    private var dialWanted = false
    private var dialAt = 0L
    private var preparedBeforeDial = false

    // The mint.
    private var creds: RtcCredentials? = null
    private var minting = false
    private var mintFailure: String? = null
    /** The mint was refused by a gate: the call cannot happen on either transport. */
    private var refusal: String? = null
    @Volatile private var released = false

    // The peer.
    private var peer: GraineRtcPeer? = null
    private var peerGen = 0
    private var offerReady = false
    private var mediaFailure: String? = null
    private var gathering: Gather? = null

    // Signalling.
    private var ua: SipUserAgent? = null
    private var uaGen = 0
    private var registered = false
    private var registerMs: Long? = null
    private var registerFailure: SipFailure? = null
    private var registerTimer: (() -> Unit)? = null
    private var probing = false
    private var probed = false

    // The dial.
    private var call: SipUserAgent.Call? = null
    private var dialSeq = 0
    private var dials = 0
    private var heard = false
    private var inviteAt = 0L
    private var answeredAt = 0L
    private var heardTimer: (() -> Unit)? = null
    private var answerTimer: (() -> Unit)? = null
    private var iceTimer: (() -> Unit)? = null
    private var budgetTimer: (() -> Unit)? = null

    // The call.
    private var control: RtcControl? = null
    private var iceState = "new"
    private var muted = false
    private var onHold = false
    private var dropStartedAt: Long? = null
    private var restartTried = false
    private var restarting = false
    private var restartTimer: (() -> Unit)? = null
    private var dropTimer: (() -> Unit)? = null
    private var statsTimer: (() -> Unit)? = null
    private val speaking = SpeakingDetector()
    private var spokenTurn = ""
    private var firstAudioSeen = false
    private var firstVoiceSeen = false

    /** The credential is still good for a while (rtc-warm.ts): one about to expire is minted again. */
    private fun credsUsable(c: RtcCredentials): Boolean = c.expiresAtMs == null || c.expiresAtMs - env.wallClock() >= RtcMint.MIN_REMAINING_MS

    // ── prepare ─────────────────────────────────────────────────────────────

    /** Mint, then gather and register — everything a call needs before the INVITE. Safe to call once; later calls do nothing. */
    fun prepare() = scheduler.post {
        if (phase != Phase.IDLE) return@post
        phase = Phase.PREPARING
        mint()
    }

    private fun mint() {
        minting = true
        mintFailure = null
        val url = request.baseUrl.trimEnd('/') + RtcMint.PATH
        val body = RtcMint.body(request.publishableKey, sessionId, request.variables, request.identity, request.callId).toString()
        val started = now()
        env.io {
            var reply: HttpReply? = null
            var error: String? = null
            // A request the network lost is sent again (after 1 s, then 2 s); an answer, whatever its status, is final.
            for (attempt in 0..RtcMint.RETRY_DELAYS_MS.size) {
                if (released) return@io
                try {
                    reply = env.http.post(url, body)
                    error = null
                    break
                } catch (e: Exception) {
                    error = e.javaClass.simpleName
                    if (attempt < RtcMint.RETRY_DELAYS_MS.size) try { Thread.sleep(RtcMint.RETRY_DELAYS_MS[attempt]) } catch (_: InterruptedException) { break }
                }
            }
            scheduler.post { minted(reply, error, now() - started) }
        }
    }

    private fun minted(reply: HttpReply?, error: String?, ms: Long) {
        minting = false
        if (phase == Phase.DONE) return
        if (reply != null && reply.code != 200) RtcMint.refusal(reply.code, reply.body)?.let { message ->
            refusal = message
            step("mint", "refused after $ms ms: HTTP ${reply.code}${errorCode(reply.body)?.let { " $it" } ?: ""}")
            maybeProceed()
            return
        }
        val c = if (reply != null && reply.code == 200) RtcMint.parse(reply.body, env.wallClock()) else null
        if (c == null) {
            mintFailure = when {
                reply == null -> "the request failed ($error)"
                reply.code != 200 -> "HTTP ${reply.code}${errorCode(reply.body)?.let { " $it" } ?: ""}"
                else -> "the credentials could not be read (or name no agent)"
            }
            step("mint", "failed after $ms ms: $mintFailure")
            maybeProceed()
            return
        }
        creds = c
        step("mint", "$ms ms, ${c.iceServers.size} ICE server(s)")
        startPeer(c.iceServers)
        startSignalling(c)
    }

    /** The mint's own error code (`key_unknown`, `origin_not_allowed`, `rate_limited`) — never its message, which may name the app. */
    private fun errorCode(body: String): String? =
        runCatching { JSONObject(body).optString("code").takeIf { it.isNotEmpty() && it.length <= 40 } }.getOrNull()

    // ── the peer and its candidates ─────────────────────────────────────────

    /** One gathering — the first offer's, or an ICE restart's: when it began, how long it may run, and what to do after. */
    private inner class Gather(val gen: Int, val wantRelay: Boolean, val after: () -> Unit) {
        val startedAt = now()
        val cap = RtcIce.capFor(wantRelay)
        var timer: (() -> Unit)? = null
        var done = false
    }

    private fun startPeer(servers: List<GraineRtcIceServer>) {
        val gen = ++peerGen
        offerReady = false
        mediaFailure = null
        val p = try {
            env.createPeer(servers, PeerEvents(gen))
        } catch (e: Throwable) {
            mediaFailure = "the peer connection could not be made (${e.javaClass.simpleName})"
            step("peer", mediaFailure!!)
            maybeProceed()
            return
        }
        peer = p
        p.setMicrophoneEnabled(!muted)
        if (onHold) p.setAudioActive(recording = false, playout = false)
        gather(gen, RtcIce.hasTurn(servers), iceRestart = false) {
            offerReady = true
            maybeProceed()
        }
    }

    private fun gather(gen: Int, wantRelay: Boolean, iceRestart: Boolean, after: () -> Unit) {
        val g = Gather(gen, wantRelay, after)
        gathering = g
        val p = peer ?: return
        p.createOffer(iceRestart) { error ->
            scheduler.post {
                if (gen != peerGen || g.done) return@post
                if (error != null) {
                    gathering = null
                    g.done = true
                    if (iceRestart) dropped("the ICE restart's offer failed") else {
                        mediaFailure = "the offer failed"
                        step("peer", mediaFailure!!)
                        maybeProceed()
                    }
                    return@post
                }
                val left = g.cap - (now() - g.startedAt)
                g.timer = scheduler.schedule(Math.max(0L, left)) { gathered(g, "cap") }
            }
        }
    }

    private fun candidate(gen: Int, kind: RtcIce.Kind) {
        val g = gathering ?: return
        if (g.gen != gen || g.done) return
        if (RtcIce.stopNow(kind, now() - g.startedAt, g.cap, g.wantRelay)) gathered(g, kind.name.lowercase())
    }

    private fun gathered(g: Gather, why: String) {
        if (g.done) return
        g.done = true
        g.timer?.invoke()
        if (gathering === g) gathering = null
        step("gather", "${now() - g.startedAt} ms ($why): ${RtcSdp.candidateTypes(peer?.localDescription())}")
        g.after()
    }

    private inner class PeerEvents(private val gen: Int) : GraineRtcPeer.Listener {
        override fun onCandidate(candidate: String) = scheduler.post { candidate(gen, RtcIce.kind(candidate)) }
        override fun onGatheringComplete() = scheduler.post { candidate(gen, RtcIce.Kind.END) }
        override fun onIceState(state: String) = scheduler.post { if (gen == peerGen) iceChanged(state) }
        override fun onAudioError(message: String) = scheduler.post { if (gen == peerGen) audioFailed(message) }
    }

    private fun closePeer() {
        peerGen++
        gathering?.let { it.done = true; it.timer?.invoke() }
        gathering = null
        val p = peer ?: return
        peer = null
        runCatching { p.close() }
    }

    // ── signalling ──────────────────────────────────────────────────────────

    private fun startSignalling(c: RtcCredentials) {
        val gen = ++uaGen
        registered = false
        registerFailure = null
        val u = SipUserAgent(SipAccount(c.server, c.username, c.password, c.realm), scheduler, env.openSip, UaEvents(gen), random = env.sipRandom)
        ua = u
        registerTimer?.invoke()
        registerTimer = scheduler.schedule(RtcDial.REGISTER_DEADLINE_MS) {
            if (gen == uaGen && !registered) registrationFailed(SipFailure(null, SipFailure.REQUEST_TIMEOUT))
        }
        u.start()
    }

    private inner class UaEvents(private val gen: Int) : SipUserAgent.Listener {
        override fun onRegistered(elapsedMs: Long) {
            if (gen != uaGen || registered) return
            registered = true
            registerTimer?.invoke(); registerTimer = null
            if (registerMs == null) registerMs = elapsedMs
            step("register", "$elapsedMs ms")
            maybeProceed()
        }
        override fun onRegistrationFailed(failure: SipFailure) {
            if (gen == uaGen && !registered) registrationFailed(failure)
        }
        override fun onConnectionLost(error: String) {
            if (gen == uaGen) step("signalling", "connection lost${if (phase == Phase.LIVE) "; reconnecting" else ""}")
        }
        override fun onMessage(direction: Char, text: String) = env.sipTrace(direction, text)
    }

    private fun registrationFailed(failure: SipFailure) {
        registerTimer?.invoke(); registerTimer = null
        registerFailure = failure
        step("register", "failed: $failure")
        ua?.stop()
        maybeProceed()
    }

    private fun stopSignalling() {
        registerTimer?.invoke(); registerTimer = null
        uaGen++
        call?.hangUp()
        call = null
        ua?.stop()
        ua = null
        registered = false
    }

    // ── dial ────────────────────────────────────────────────────────────────

    /**
     * The call's foreground service is up: dial — at once when prepared, else
     * as soon as the mint, the candidates and the registration are in. The
     * 12-second budget starts now.
     */
    fun dial(deps: RtcDialDeps, listener: Listener) = scheduler.post {
        this.listener = listener
        this.deps = deps
        if (phase == Phase.DONE) return@post
        if (phase == Phase.IDLE) { phase = Phase.PREPARING; mint() }
        dialWanted = true
        dialAt = now()
        preparedBeforeDial = registered && offerReady
        budgetTimer = scheduler.schedule(RtcDial.ATTEMPT_BUDGET_MS) { budgetSpent() }
        openControl()
        runCatching { deps.contextFrame() }.getOrNull()?.let { control?.send("app_context", CallFrames.appContext(it)) }
        maybeProceed()
    }

    /** Whatever the call is waiting for has come: fall back on a failure, else send the INVITE once everything is in. */
    private fun maybeProceed() {
        if (!dialWanted || phase != Phase.PREPARING && phase != Phase.DIALING || call != null || probing) return
        refusal?.let { refused(it); return }
        mintFailure?.let { fallback(RtcFallback.MINT_FAILED, it); return }
        val c = creds ?: return
        mediaFailure?.let { fallback(RtcFallback.ERROR, it); return }
        registerFailure?.let { fallback(RtcFallback.REGISTER_FAILED, it.toString()); return }
        if (!registered || !offerReady) return
        val u = ua ?: return
        // A registration made a while ago — a ring answered after twenty seconds — is asked first.
        if (!probed && now() - u.heardAt > RtcDial.PROBE_SKIP_MS) { probe(u, c); return }
        invite(c)
    }

    private fun probe(u: SipUserAgent, c: RtcCredentials) {
        probing = true
        val gen = uaGen
        val began = now()
        u.probe(RtcDial.probeDeadlineMs(registerMs)) { alive ->
            probing = false
            if (gen != uaGen || phase == Phase.DONE) return@probe
            probed = true
            if (alive) {
                step("probe", "the socket answered in ${now() - began} ms")
                maybeProceed()
            } else {
                // Silent: replaced at once on the credential it already has (or a fresh one near its expiry).
                step("probe", "the socket was silent for ${now() - began} ms; registering again")
                stopSignalling()
                if (credsUsable(c)) startSignalling(c) else { creds = null; mint() }
            }
        }
    }

    private fun invite(c: RtcCredentials) {
        val p = peer ?: return
        val u = ua ?: return
        val offer = p.localDescription()
        if (offer.isNullOrEmpty()) { fallback(RtcFallback.ERROR, "no offer to send"); return }
        val sdp = RtcSdp.preferCodecs(offer)
        dials++
        val seq = ++dialSeq
        phase = Phase.DIALING
        heard = false
        inviteAt = now()
        step("invite", "dial $dials, candidates: ${RtcSdp.candidateTypes(sdp)}")
        val headers = listOf(
            "X-Graine-Session-Id" to sessionId,
            // The header the server knows a WebRTC call and its agent by: never left out.
            "X-Graine-Agent-Id" to c.agentId,
            "X-Application-Sid" to c.applicationSid,
        )
        call = u.invite(c.target, headers, sdp, CallEvents(seq))
        heardTimer = scheduler.schedule(RtcDial.heardDeadlineMs(registerMs)) {
            if (seq == dialSeq && phase == Phase.DIALING && !heard) dialFailed(null, "no_response")
        }
        answerTimer = scheduler.schedule(RtcDial.ANSWER_DEADLINE_MS) {
            if (seq == dialSeq && phase == Phase.DIALING) dialFailed(null, "no_answer")
        }
    }

    private inner class CallEvents(private val seq: Int) : SipCallListener {
        private val current: Boolean get() = seq == dialSeq && phase != Phase.DONE

        override fun onProvisional(code: Int) {
            if (!current) return
            if (!heard) step("provisional", "$code after ${now() - inviteAt} ms")
            heard = true
            heardTimer?.invoke(); heardTimer = null
        }

        override fun onAnswered(sdp: String) {
            if (!current || phase != Phase.DIALING) return
            heard = true
            heardTimer?.invoke(); heardTimer = null
            answerTimer?.invoke(); answerTimer = null
            phase = Phase.ANSWERED
            answeredAt = now()
            step("answered", "${answeredAt - inviteAt} ms after the INVITE${call?.serverCallId?.let { " (call $it)" } ?: ""}")
            ua?.keepConnected = true
            iceTimer = scheduler.schedule(RtcDial.ICE_CONNECT_DEADLINE_MS) {
                if (seq == dialSeq && phase == Phase.ANSWERED) fallback(RtcFallback.ICE_TIMEOUT, "ICE still $iceState ${RtcDial.ICE_CONNECT_DEADLINE_MS} ms after the answer")
            }
            val gen = peerGen
            peer?.setRemoteAnswer(sdp) { error ->
                scheduler.post {
                    if (gen != peerGen || seq != dialSeq || error == null) return@post
                    // The library's own words: a description problem, never anything personal.
                    val why = error.take(200)
                    step("answer", "could not be applied: $why")
                    if (phase == Phase.ANSWERED) fallback(RtcFallback.ERROR, "the answer could not be applied ($why)")
                }
            }
        }

        override fun onFailed(failure: SipFailure) {
            if (current) dialFailed(failure.code, failure.cause)
        }

        override fun onRemoteBye() {
            if (!current) return
            step("bye", "from the far side")
            if (phase == Phase.LIVE) end(GraineCallOutcome.EndReason.REMOTE, null)
            // Hung up before any audio flowed: nothing was said, so the WebSocket may still carry the call.
            else fallback(RtcFallback.ERROR, "the far side hung up before any audio")
        }

        override fun onDialogEnded(failure: SipFailure) {
            if (!current) return
            step("dialog", "ended: $failure")
            if (phase == Phase.LIVE) end(GraineCallOutcome.EndReason.FAILED, LOST) else fallback(RtcFallback.ERROR, failure.toString())
        }

        override fun onOffer(sdp: String, answer: (String?) -> Unit) {
            val p = peer
            if (p == null || !current) { answer(null); return }
            // Answered with the media's own answer — or, if it has none within a moment, the SDP we last sent.
            var settled = false
            val timeout = scheduler.schedule(OFFER_ANSWER_WAIT_MS) { if (!settled) { settled = true; answer(null) } }
            p.answerOffer(sdp) { a, _ ->
                scheduler.post {
                    if (settled) return@post
                    settled = true
                    timeout()
                    answer(a?.let { RtcSdp.preferCodecs(it) })
                }
            }
        }
    }

    /**
     * A dial that was not answered: tried once more on a fresh connection when
     * it was the network's (rtc-dial.ts), else carried on the WebSocket.
     */
    private fun dialFailed(code: Int?, reason: String) {
        if (phase != Phase.DIALING && phase != Phase.ANSWERED) return
        val verdict = RtcDial.verdict(code, reason, dials)
        step("dial", "${RtcDial.describe(code, reason)}: ${verdict.name.lowercase()}")
        when (verdict) {
            RtcDial.Verdict.IGNORE -> Unit
            RtcDial.Verdict.REDIAL -> redial()
            RtcDial.Verdict.FALLBACK -> fallback(
                when (reason) {
                    "no_response" -> RtcFallback.NO_RESPONSE
                    "no_answer" -> RtcFallback.NO_ANSWER
                    else -> RtcFallback.ERROR
                },
                RtcDial.describe(code, reason),
            )
        }
    }

    /** The second dial: a fresh socket and registration (the same credential while it is good), a fresh peer. */
    private fun redial() {
        dialSeq++
        cancelDialTimers()
        phase = Phase.DIALING
        probed = true
        stopSignalling()
        closePeer()
        val c = creds
        if (c != null && credsUsable(c)) {
            startPeer(c.iceServers)
            startSignalling(c)
        } else {
            creds = null
            mint()
        }
    }

    private fun cancelDialTimers() {
        heardTimer?.invoke(); heardTimer = null
        answerTimer?.invoke(); answerTimer = null
        iceTimer?.invoke(); iceTimer = null
    }

    /** Twelve seconds and no media: whatever step it is on, the WebSocket takes the call. */
    private fun budgetSpent() {
        budgetTimer = null
        if (phase == Phase.LIVE || phase == Phase.DONE) return
        val reason = when {
            creds == null -> if (mintFailure != null || minting) RtcFallback.MINT_FAILED else RtcFallback.ERROR
            !registered && call == null -> RtcFallback.REGISTER_FAILED
            phase == Phase.ANSWERED -> RtcFallback.ICE_TIMEOUT
            call == null || !heard -> RtcFallback.NO_RESPONSE
            else -> RtcFallback.NO_ANSWER
        }
        fallback(reason, "no media ${RtcDial.ATTEMPT_BUDGET_MS / 1000} s after the call could start")
    }

    // ── the call is up ──────────────────────────────────────────────────────

    private fun iceChanged(state: String) {
        if (state == iceState) return
        iceState = state
        when (phase) {
            Phase.ANSWERED -> when (state) {
                "connected", "completed" -> live()
                "failed" -> fallback(RtcFallback.ICE_TIMEOUT, "ICE failed before it connected")
            }
            Phase.LIVE -> watch(state)
            else -> Unit
        }
    }

    private fun live() {
        phase = Phase.LIVE
        cancelDialTimers()
        budgetTimer?.invoke(); budgetTimer = null
        val t = now()
        step("media", "ICE connected ${t - answeredAt} ms after the answer")
        listener.onMedia()
        telemetry()
        statsTimer = scheduler.schedule(STATS_MS) { pollStats() }
    }

    /** The two rows the voice bar records on every WebRTC call: how the connect went, and the transport. */
    private fun telemetry() {
        val setup = inviteAt - dialAt
        val dialMs = answeredAt - inviteAt
        val total = answeredAt - dialAt
        val data = JSONObject().put("warm", preparedBeforeDial).put("setup_ms", setup).put("dial_ms", dialMs).put("total_ms", total)
        if (dials > 1) data.put("redials", dials - 1)
        fun s(ms: Long) = String.format(Locale.US, "%.1fs", ms / 1000.0)
        val detail = "${if (preparedBeforeDial) "warm" else "cold"} · ${s(total)} (setup ${s(setup)}, answer ${s(dialMs)})"
        sendEvent("connect", detail, data)
        sendEvent("transport", "WebRTC", JSONObject().put("transport", "rtc").put("requested", "rtc"))
    }

    private fun sendEvent(name: String, detail: String, data: JSONObject) {
        val event = JSONObject().put("name", name).put("detail", detail).put("quiet", true).put("telemetry", true).put("data", data)
        control?.send("app_event", JSONObject().put("type", "app_event").put("event", event).toString())
    }

    /**
     * THE MEDIA WATCH: `disconnected` past 3 s tries one ICE restart, and
     * the call has until 10 s after the drop began; `failed` restarts at once
     * if the restart is still unused. Without a restart left, `disconnected`
     * for 8 s or `failed` ends the call as dropped. Never the WebSocket: that
     * would be a second conversation.
     */
    private fun watch(state: String) {
        when (state) {
            "connected", "completed" -> {
                if (dropStartedAt != null) step("media", "back after ${now() - dropStartedAt!!} ms")
                dropStartedAt = null
                restarting = false
                restartTimer?.invoke(); restartTimer = null
                dropTimer?.invoke(); dropTimer = null
            }
            "disconnected" -> if (dropStartedAt == null) {
                dropStartedAt = now()
                step("media", "ICE disconnected")
                if (!restartTried) {
                    restartTimer = scheduler.schedule(RtcMediaWatch.RESTART_AFTER_MS) { restartTimer = null; restartIce() }
                    dropTimer = scheduler.schedule(RtcMediaWatch.RECOVERY_WINDOW_MS) { dropped("ICE did not reconnect") }
                } else {
                    dropTimer = scheduler.schedule(RtcMediaWatch.DISCONNECTED_GRACE_MS) { dropped("ICE stayed disconnected") }
                }
            }
            "failed" -> {
                val began = dropStartedAt ?: now().also { dropStartedAt = it }
                if (restarting) return
                if (!restartTried && now() - began < RtcMediaWatch.RECOVERY_WINDOW_MS) {
                    restartTimer?.invoke(); restartTimer = null
                    if (dropTimer == null) dropTimer = scheduler.schedule(RtcMediaWatch.RECOVERY_WINDOW_MS - (now() - began)) { dropped("ICE did not reconnect") }
                    restartIce()
                } else {
                    dropped("ICE failed")
                }
            }
        }
    }

    /** One ICE restart: a fresh offer, gathered by the same rule, in a re-INVITE; its answer applied. */
    private fun restartIce() {
        if (phase != Phase.LIVE || restartTried) return
        restartTried = true
        restarting = true
        step("media", "restarting ICE")
        val gen = peerGen
        val c = call
        if (peer == null || c == null) { dropped("nothing to restart"); return }
        gather(gen, creds?.let { RtcIce.hasTurn(it.iceServers) } ?: false, iceRestart = true) {
            val offer = peer?.localDescription()
            if (offer == null || gen != peerGen) { dropped("the ICE restart's offer failed"); return@gather }
            c.reoffer(RtcSdp.preferCodecs(offer)) { answer ->
                if (gen != peerGen || phase != Phase.LIVE) return@reoffer
                if (answer == null) { dropped("the ICE restart was refused"); return@reoffer }
                peer?.setRemoteAnswer(answer) { error ->
                    scheduler.post { if (error != null && gen == peerGen) dropped("the ICE restart's answer could not be applied") }
                }
            }
        }
    }

    private fun dropped(why: String) {
        if (phase != Phase.LIVE) return
        step("media", "dropped: $why")
        end(GraineCallOutcome.EndReason.FAILED, LOST)
    }

    private fun audioFailed(message: String) {
        step("audio", "failed")
        CallLog.error("the call's audio failed: $message")
        when (phase) {
            Phase.LIVE -> end(GraineCallOutcome.EndReason.FAILED, "The microphone stopped working.")
            Phase.DONE, Phase.IDLE -> Unit
            else -> if (dialWanted) fallback(RtcFallback.ERROR, "the audio could not start") else mediaFailure = "the audio could not start"
        }
    }

    private fun pollStats() {
        statsTimer = null
        if (phase != Phase.LIVE) return
        statsTimer = scheduler.schedule(STATS_MS) { pollStats() }
        val gen = peerGen
        peer?.stats { s -> if (s != null) scheduler.post { if (gen == peerGen && phase == Phase.LIVE) onStats(s) } }
    }

    private fun onStats(s: GraineRtcStats) {
        if (!firstAudioSeen && s.bytesReceived > 0) {
            firstAudioSeen = true
            step("audio", "first inbound audio ${now() - answeredAt} ms after the answer (${s.codec ?: "codec unknown"}, ${s.localCandidateType ?: "path unknown"})")
        }
        val level = speaking.level(s)
        if (!firstVoiceSeen && level != null && level > 0.01) {
            firstVoiceSeen = true
            step("audio", "the agent's voice ${now() - answeredAt} ms after the answer")
        }
        speaking.update(level, now())?.let { listener.onAgentSpeaking(it) }
        listener.onAudioStats(s)
    }

    // ── the control channel ─────────────────────────────────────────────────

    private fun openControl() {
        if (control != null) return
        val host = deps?.wsHost?.takeIf { it.isNotBlank() } ?: request.wsHost
        val c = RtcControl(RtcControl.url(host, sessionId), scheduler, env.openControl, object : RtcControl.Listener {
            override fun onJoined() {
                step("control", "joined${if (answeredAt > 0) " ${now() - answeredAt} ms after the answer" else ""}")
                listener.onControlJoined()
            }
            override fun onFrame(frame: ControlFrame) = onControl(frame)
        })
        control = c
        c.open()
    }

    /** The socket path's own frames, handled as it handles them: captions, the agent's actions. */
    private fun onControl(f: ControlFrame) {
        if (phase == Phase.DONE) return
        when (f) {
            is ControlFrame.CustomerCaption -> if (f.text.isNotEmpty()) listener.onCaption(Role.CUSTOMER, f.text, !f.final)
            is ControlFrame.AgentText -> {
                val chunk = CallText.readAgentChunk(f.data)
                var words = chunk.text
                if (words.isNotEmpty()) {
                    words = CallText.spokenJoinGap(spokenTurn, words) + words
                    spokenTurn += words
                    listener.onCaption(Role.AGENT, spokenTurn, true)
                }
                if (chunk.endsTurn) endCaptionTurn()
            }
            ControlFrame.EndOfLine -> endCaptionTurn()
            is ControlFrame.Action -> runAction(f)
            is ControlFrame.Widget -> CallLog.debug("a component arrived during a call; a call screen does not draw components")
            else -> Unit
        }
    }

    private fun endCaptionTurn() {
        if (spokenTurn.isNotEmpty()) listener.onCaption(Role.AGENT, spokenTurn, false)
        spokenTurn = ""
    }

    /** Run it through the SDK's runner and answer within the runtime's deadline on every path — the agent waits on it. */
    private fun runAction(a: ControlFrame.Action) {
        val d = deps ?: return
        var answered = false
        var deadline: (() -> Unit)? = null
        fun answer(r: ActionResult) {
            if (answered) return
            answered = true
            deadline?.invoke()
            if (phase != Phase.DONE) control?.send("app_action_result", CallFrames.actionResult(a.actionId, r))
        }
        deadline = scheduler.schedule(CallFrames.ACTION_ANSWER_BY_MS) { answer(ActionResult.error("\"${a.name}\" did not finish in time.")) }
        try {
            d.runAction(a.name, a.arguments) { r -> scheduler.post { answer(r) } }
        } catch (e: Exception) {
            answer(ActionResult.error("\"${a.name}\" failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    // ── what the call screen does ───────────────────────────────────────────

    /** Muted: the microphone's track sends silence; nothing is torn down, so unmuting is instant. */
    fun setMuted(value: Boolean) = scheduler.post {
        muted = value
        peer?.setMicrophoneEnabled(!value)
    }

    /** The phone took the call's audio (a phone call, another app): no recording and no playout until it is back. */
    fun setOnHold(value: Boolean) = scheduler.post {
        if (onHold == value) return@post
        onHold = value
        peer?.setAudioActive(recording = !value, playout = !value)
    }

    /** A newer screen: queued until the control channel joins, replacing any screen still waiting. */
    fun sendContext(frame: JSONObject) = scheduler.post {
        if (phase != Phase.DONE) control?.send("app_context", CallFrames.appContext(frame))
    }

    /** Hang up from this side — BYE, or CANCEL before the answer — and release everything. No event follows. */
    fun hangUp() {
        released = true
        scheduler.post {
            if (phase == Phase.DONE) return@post
            phase = Phase.DONE
            release()
        }
    }

    // ── endings ─────────────────────────────────────────────────────────────

    private fun fallback(reason: String, detail: String) {
        if (phase == Phase.DONE || phase == Phase.LIVE) return
        phase = Phase.DONE
        step("fallback", "$reason: $detail")
        release()
        listener.onFallback(reason, detail)
    }

    /** A gate refused the call: no fallback — the WebSocket would refuse it the same way a second later. */
    private fun refused(message: String) {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        step("refused", message)
        release()
        listener.onRefused(message)
    }

    private fun end(reason: GraineCallOutcome.EndReason, error: String?) {
        if (phase == Phase.DONE) return
        phase = Phase.DONE
        release()
        endCaptionTurn()
        if (speaking.reset()) listener.onAgentSpeaking(false)
        listener.onEnded(reason, error)
    }

    /** Timers, the call (BYE or CANCEL), the registration and its socket, the control channel right after the BYE, the media. */
    private fun release() {
        released = true
        cancelDialTimers()
        budgetTimer?.invoke(); budgetTimer = null
        restartTimer?.invoke(); restartTimer = null
        dropTimer?.invoke(); dropTimer = null
        statsTimer?.invoke(); statsTimer = null
        stopSignalling()
        control?.close()
        closePeer()
    }

    private fun step(step: String, detail: String) {
        CallLog.debug("webrtc $step: $detail")
        runCatching { env.steps(step, detail) }
        runCatching { listener.onStep(step, detail) }
    }

    companion object {
        /** How often the far side's audio is read: "agent speaking", and the first audio and voice for the log. */
        const val STATS_MS = 200L
        /** How long an in-dialog offer waits for the media's answer before the last SDP is sent instead. */
        const val OFFER_ANSWER_WAIT_MS = 3_000L
        /** What the call says when the connection is lost after the call was up. */
        const val LOST = "The call's connection was lost."
    }
}
