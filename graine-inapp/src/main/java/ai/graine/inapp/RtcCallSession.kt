@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONObject

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
    /** The direct mint (RtcDirect.kt): the credential from the telephony gateway, with a grant. Null: the web route only. */
    val direct: RtcDirectMint? = null,
)

/** What the call needs from the app once it dials: its actions and its screen. */
internal class RtcDialDeps(
    val runAction: (name: String, args: JSONObject, answer: (ActionResult) -> Unit) -> Unit,
    val contextFrame: () -> JSONObject?,
    /** Where the agent's sockets live, as the session now says (a ring may have been prepared before it arrived). */
    val wsHost: String? = null,
    /**
     * When the customer tapped — placed the call, or answered its ring — on
     * the scheduler's clock: the analytics rows count from it. Null: from the
     * dial.
     */
    val tapAt: Long? = null,
    /** What the app asked for — "auto", "rtc" or "ws" — for the transport row. */
    val requested: String? = null,
)

/**
 * ONE NATIVE CALL ON WEBRTC — the voice bar's own sequence, done natively
 * (WEBRTC-CALLS.md §1; the bar's lib/webcall-rtc.ts).
 *
 *   prepare  the mint (credentials, ICE servers — at the telephony gateway with
 *            the call's grant, else through the web route: RtcDirect.kt) → a peer
 *            connection whose candidate pool gathers — no microphone track, no
 *            offer, so nothing can record — and the signalling socket
 *            registering, together
 *   dial     the control channel; the microphone's track and the offer, the
 *            pooled candidates in it at once; the microphone and speaker on, and
 *            an INVITE carrying the offer → 100 → 200 → ACK → the answer applied
 *            → ICE connected: the call is up
 *
 * [prepare] runs at the tap, or when a ring arrives; [dial] runs once the
 * phone may record — at the tap when it was made in the app in front, else
 * once the call's foreground service is up (GraineCalls, CallStart) — so the
 * INVITE never leaves before then. A dial that is not answered in time is
 * tried once more on a fresh connection when the failure was the network's;
 * anything else before media — the mint refused, no registration, no reply,
 * no answer, no media path, any error — is [Listener.onFallback]: the call
 * goes on, on the WebSocket, inside the same ring and screen. The whole
 * attempt has 12 s.
 * After media nothing falls back: a lost path gets one ICE restart, then the
 * call ends as dropped.
 *
 * On its control channel the call says how it went, as analytics rows
 * (CallAnalytics): at media the transport and where the tap's wait went, then
 * the agent's voice first heard, each time the connection heals, and the
 * audio's quality at the end. What happened before a fallback — no audio path
 * after the answer, a socket replaced — goes with the call to the WebSocket.
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
        /**
         * Just before [onFallback]: the analytics rows (CallAnalytics frames)
         * of what happened on the way — the socket replaced, the answer with no
         * audio path — for the conversation that carries the call instead.
         */
        fun onRows(frames: List<String>) {}
    }

    /**
     * A CALL PREPARED AHEAD OF THE TAP (GraineCalls.prepareCall), watched
     * until its dial: told once it is ready to dial — minted, its peer made,
     * registered — and once if it can no longer be: the mint failed
     * or was refused, the media could not start, the registration failed or
     * lapsed, or the socket dropped. Its owner lets it go then, silently; the
     * tap prepares as it always did. Nothing is said after the dial.
     */
    interface Preparation {
        fun onReady(ms: Long, direct: Boolean) {}
        fun onLost(why: String) {}
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

    // Prepared ahead of the tap: who watches it until the dial, and since when.
    private var preparation: Preparation? = null
    private var aheadOfTap = false
    private var preparedAt = 0L
    private var readyTold = false
    private var mintedDirect = false

    // The mint.
    private var creds: RtcCredentials? = null
    private var minting = false
    private var mintFailure: String? = null
    /** The mint was refused by a gate: the call cannot happen on either transport. */
    private var refusal: String? = null
    @Volatile private var released = false

    // The peer.
    private var peer: GraineRtcPeer? = null
    /** The ICE servers the peer was made with: whether its offer waits for a relay. */
    private var peerServers: List<GraineRtcIceServer> = emptyList()
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
    /** The peer's microphone and speaker were turned on, at its INVITE: until then a hold has nothing to turn off. */
    private var audioOn = false
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

    // The analytics rows (CallAnalytics): when each step happened, on the scheduler's clock.
    private var tapAt = 0L
    /** When the mint, the peer and the registration were all in: before the tap, the call was prepared. */
    private var readyAt: Long? = null
    private var mintMs: Long? = null
    private var gatherMs: Long? = null
    private var uaRegisterMs: Long? = null
    private var mediaAt = 0L
    private var firstAudioAt: Long? = null
    /** The INVITE of a dial that was given up and dialled again: the redial's trouble began there. */
    private var redialFrom: Long? = null
    /** A silent socket found before the dial, being replaced; and the socket lost under the answered call. */
    private var replacingSince: Long? = null
    private var sipLostAt: Long? = null
    /** Rows from before media: the control channel's at media, or, on a fallback, the WebSocket's. */
    private val early = ArrayList<String>()
    private val quality = QualitySamples()
    private var qualitySent = false

    /** The credential is still good for a while (rtc-warm.ts): one about to expire is minted again. */
    private fun credsUsable(c: RtcCredentials): Boolean = c.expiresAtMs == null || c.expiresAtMs - env.wallClock() >= RtcMint.MIN_REMAINING_MS

    // ── prepare ─────────────────────────────────────────────────────────────

    /**
     * Mint, then make the peer and register — everything a call needs before
     * its offer and its INVITE, which are the dial's. Safe to call once; later
     * calls do nothing. [watch]: the call is prepared ahead of the tap, and
     * told how that goes until its dial.
     */
    fun prepare(watch: Preparation? = null) = scheduler.post {
        if (phase != Phase.IDLE) return@post
        phase = Phase.PREPARING
        preparation = watch
        aheadOfTap = watch != null
        preparedAt = now()
        mint()
    }

    private fun mint() {
        minting = true
        mintFailure = null
        val url = request.baseUrl.trimEnd('/') + RtcMint.PATH
        val body = RtcMint.body(request.publishableKey, sessionId, request.variables, request.identity, request.callId).toString()
        val started = now()
        env.io {
            if (released) return@io
            /*
             * THE GATEWAY FIRST, THE WEB ROUTE SECOND (RtcDirect.kt). With a grant
             * asked for exactly this call in hand, the credential — or a gate's
             * refusal — comes from the telephony gateway beside the SBC, in a
             * fraction of the web route's time. Anything else is null, and the
             * web route mints below exactly as it always has.
             */
            val direct = env.direct?.let { d -> runCatching { d.mint(request, sessionId) }.getOrNull() }
            if (direct != null) {
                scheduler.post { minted(direct, null, now() - started, DIRECT) }
                return@io
            }
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
            // The grant that may ride in with the answer is this call's own: a redial's mint — a ring's at its answer — takes it to the gateway.
            env.direct?.let { d -> runCatching { d.webMinted(request, reply) } }
            scheduler.post { minted(reply, error, now() - started) }
        }
    }

    /** The mint's answer — the web route's, or the gateway's ([via] [DIRECT]), read alike. */
    private fun minted(reply: HttpReply?, error: String?, ms: Long, via: String? = null) {
        minting = false
        if (phase == Phase.DONE) return
        val at = via?.let { " ($it)" }.orEmpty()
        if (reply != null && reply.code != 200) RtcMint.refusal(reply.code, reply.body)?.let { message ->
            refusal = message
            step("mint", "refused after $ms ms$at: HTTP ${reply.code}${errorCode(reply.body)?.let { " $it" } ?: ""}")
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
            step("mint", "failed after $ms ms$at: $mintFailure")
            maybeProceed()
            return
        }
        creds = c
        mintedDirect = via == DIRECT
        mintMs = ms
        step("mint", "$ms ms$at, ${c.iceServers.size} ICE server(s)")
        startPeer(c.iceServers)
        startSignalling(c)
    }

    /** The mint's own error code (`key_unknown`, `origin_not_allowed`, `rate_limited`) — never its message, which may name the app. */
    private fun errorCode(body: String): String? =
        runCatching { JSONObject(body).optString("code").takeIf { it.isNotEmpty() && it.length <= 40 } }.getOrNull()

    // ── the peer and its candidates ─────────────────────────────────────────

    /** One gathering — the first offer's, or an ICE restart's: when it began, how long it may run, and what to do after. */
    private inner class Gather(val gen: Int, val wantRelay: Boolean, val restart: Boolean, val after: () -> Unit) {
        val startedAt = now()
        /** Candidates that came during it: at the dial, the pool's, at once. */
        var candidates = 0
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
        /*
         * NOTHING RECORDED, NOTHING PLAYED, UNTIL THE DIAL. The library starts
         * the microphone as soon as the offer is set — and a call prepared
         * ahead of the tap (prepareCall's, a ring's) sets its offer long before
         * anyone tapped: the phone would show its microphone in use, for a call
         * nobody placed. Both stay off until the INVITE ([invite]); a fresh
         * peer, a redial's, starts off too.
         */
        audioOn = false
        p.setAudioActive(recording = false, playout = false)
        peerServers = servers
        // A redial's fresh peer, or a cold call's, is made after the dial: its offer now. A prepared one waits for the dial.
        if (dialWanted) offer()
    }

    /**
     * THE OFFER, AT THE DIAL AND NEVER BEFORE — the voice bar's pooled peer.
     * Setting an offer is what starts the phone's microphone in the WebRTC
     * library (found on a device, 19 Oct: turning recording off does not hold
     * it back), so a call prepared ahead of the tap — prepareCall's, or a
     * ring's until its answer — has only its peer, whose candidate pool
     * gathers without one. Here, after the tap, the microphone's track joins
     * and the offer is set; the pooled candidates arrive at once, and the
     * gathering ends by the bar's rule. What it cost at the dial is logged.
     */
    private fun offer() {
        if (peer == null || offerReady || gathering != null) return
        gather(peerGen, RtcIce.hasTurn(peerServers), iceRestart = false) {
            offerReady = true
            maybeProceed()
        }
    }

    private fun gather(gen: Int, wantRelay: Boolean, iceRestart: Boolean, after: () -> Unit) {
        val g = Gather(gen, wantRelay, iceRestart, after)
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
        if (kind != RtcIce.Kind.END) g.candidates++
        if (RtcIce.stopNow(kind, now() - g.startedAt, g.cap, g.wantRelay)) gathered(g, kind.name.lowercase())
    }

    private fun gathered(g: Gather, why: String) {
        if (g.done) return
        g.done = true
        g.timer?.invoke()
        if (gathering === g) gathering = null
        if (!g.restart) gatherMs = now() - g.startedAt
        step("gather", "${now() - g.startedAt} ms ($why): ${RtcSdp.candidateTypes(peer?.localDescription())}")
        if (!g.restart) step("offer", "${now() - g.startedAt} ms at the dial (pooled candidates: ${g.candidates})")
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
            if (gen != uaGen) return
            // The signalling socket lost under the answered call is back and registered again: healed.
            sipLostAt?.let { lost ->
                sipLostAt = null
                recovered(SOCKET_REPLACED, lost)
            }
            if (registered) return
            registered = true
            registerTimer?.invoke(); registerTimer = null
            if (registerMs == null) registerMs = elapsedMs
            uaRegisterMs = elapsedMs
            step("register", "$elapsedMs ms")
            // A silent socket, replaced before the INVITE, registered: healed.
            replacingSince?.let { since ->
                replacingSince = null
                recovered(SOCKET_REPLACED, since)
            }
            maybeProceed()
        }
        override fun onRegistrationFailed(failure: SipFailure) {
            if (gen != uaGen) return
            if (!registered) registrationFailed(failure)
            // A refresh refused while the call waits for its tap: its registration is gone.
            else lostPreparation("the registration lapsed")
        }
        override fun onConnectionLost(error: String) {
            if (gen != uaGen) return
            step("signalling", "connection lost${if (phase == Phase.LIVE) "; reconnecting" else ""}")
            // Answered, the user agent reopens it and registers again (keepConnected): the trouble starts now.
            if ((phase == Phase.ANSWERED || phase == Phase.LIVE) && sipLostAt == null) sipLostAt = now()
            lostPreparation("the signalling socket dropped")
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
     * The phone may record — the tap was made in the app in front, or the
     * call's foreground service is up: dial — at once when prepared, else as
     * soon as the mint, the candidates and the registration are in. The
     * 12-second budget starts now; the analytics rows count from the tap.
     */
    fun dial(deps: RtcDialDeps, listener: Listener) = scheduler.post {
        this.listener = listener
        this.deps = deps
        if (phase == Phase.DONE) return@post
        if (phase == Phase.IDLE) { phase = Phase.PREPARING; mint() }
        dialWanted = true
        dialAt = now()
        tapAt = deps.tapAt?.takeIf { it <= dialAt } ?: dialAt
        preparedBeforeDial = registered && peer != null
        preparation = null
        if (aheadOfTap) step("dial", "prepared ${CallAnalytics.seconds(dialAt - preparedAt)} ago")
        budgetTimer = scheduler.schedule(RtcDial.ATTEMPT_BUDGET_MS) { budgetSpent() }
        openControl()
        runCatching { deps.contextFrame() }.getOrNull()?.let { control?.send("app_context", CallFrames.appContext(it)) }
        offer()
        maybeProceed()
    }

    /**
     * Before the dial, a call prepared ahead of the tap says how it stands:
     * lost — the mint failed or was refused, the media or the registration
     * failed — or ready to dial, once.
     */
    private fun watchPreparation() {
        val w = preparation ?: return
        if (dialWanted || phase != Phase.PREPARING) return
        val lost = when {
            refusal != null -> "the mint was refused"
            mintFailure != null -> "the mint failed"
            mediaFailure != null -> mediaFailure
            registerFailure != null -> "the registration failed"
            else -> null
        }
        if (lost != null) {
            lostPreparation(lost)
            return
        }
        // Ready to dial: minted, registered, and its peer made — the offer is the dial's.
        if (readyTold || creds == null || !registered || peer == null) return
        readyTold = true
        val ms = now() - preparedAt
        step("prepared", "$ms ms (${if (mintedDirect) "direct" else "web"})")
        runCatching { w.onReady(ms, mintedDirect) }
    }

    /** A call prepared ahead of the tap can no longer be dialled: said once, before any dial. */
    private fun lostPreparation(why: String) {
        val w = preparation ?: return
        if (dialWanted || phase == Phase.DONE) return
        preparation = null
        step("prepared", "lost: $why")
        runCatching { w.onLost(why) }
    }

    /** Whatever the call is waiting for has come: fall back on a failure, else send the INVITE once everything is in. */
    private fun maybeProceed() {
        if (readyAt == null && creds != null && registered && peer != null) readyAt = now()
        watchPreparation()
        if (!dialWanted || phase != Phase.PREPARING && phase != Phase.DIALING || call != null || probing) return
        refusal?.let { refused(it); return }
        mintFailure?.let { fallback(RtcFallback.MINT_FAILED, it); return }
        val c = creds ?: return
        mediaFailure?.let { fallback(RtcFallback.ERROR, it); return }
        registerFailure?.let { fallback(RtcFallback.REGISTER_FAILED, it.toString()); return }
        if (!registered) return
        val u = ua ?: return
        // A registration made a while ago — a call prepared before the tap, a ring answered after twenty seconds —
        // is asked first, beside the offer the dial is making: the INVITE waits for the slower of the two, not both.
        if (!probed && now() - u.heardAt > RtcDial.PROBE_SKIP_MS) { probe(u, c); return }
        if (!offerReady) return
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
                replacingSince = began
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
        // The dial: the microphone and the speaker come on now — unless the phone has the audio (a hold keeps them off).
        audioOn = true
        p.setAudioActive(recording = !onHold, playout = !onHold)
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
            if (!heard) {
                step("provisional", "$code after ${now() - inviteAt} ms")
                // The SBC has it: still only "trying" after ANSWER_AFTER_HEARD_MS is a stuck dial, cancelled and
                // dialled once more on a fresh connection while the attempt's budget still has room.
                answerTimer?.invoke()
                answerTimer = scheduler.schedule(RtcDial.ANSWER_AFTER_HEARD_MS) {
                    if (seq == dialSeq && phase == Phase.DIALING) dialFailed(null, "no_answer")
                }
            }
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
        if (redialFrom == null) redialFrom = inviteAt
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
        mediaAt = now()
        step("media", "ICE connected ${mediaAt - answeredAt} ms after the answer")
        listener.onMedia()
        connectedRows()
        statsTimer = scheduler.schedule(STATS_MS) { pollStats() }
    }

    // ── the analytics rows ──────────────────────────────────────────────────

    /**
     * ONE ANALYTICS ROW (CallAnalytics) ON THE CALL'S OWN CHANNEL. Once the
     * call is up it goes to the control channel at once — written before this
     * task's next line, so a quality row precedes the BYE that follows it —
     * where it waits until the channel joins, and is dropped if it never does.
     * Before media it waits here: for the control channel at media, or, if the
     * call falls back, for the WebSocket conversation that carries it
     * ([Listener.onRows]). Nothing ever waits for a row.
     */
    private fun row(name: String, data: Map<String, Any?>) {
        val frame = CallAnalytics.frame(name, data) ?: return
        if (phase == Phase.LIVE) control?.sendHere("app_event", frame)
        else if (early.size < EARLY_ROWS) early += frame
    }

    /** The connection healed: [what] ended a trouble that began at [since]. */
    private fun recovered(what: String, since: Long) = row(CallAnalytics.RECOVERY, mapOf("what" to what, "after_ms" to now() - since))

    /**
     * AT MEDIA: the transport, then where the tap's wait went — tap → INVITE
     * (setup) → answer (dial) → ICE connected — then whatever healed on the
     * way. PREPARED is a call that was minted, registered and had its peer
     * before the tap (prepareCall's, or a ring's, ready by its answer); its
     * mint and registration are its preparation's own times, and its age at
     * the tap rides along. A cold call's are inside its setup. The gathering is
     * always the dial's: the offer is never made before it.
     */
    private fun connectedRows() {
        val prepared = preparedBeforeDial && readyAt.let { it != null && it <= tapAt }
        row(CallAnalytics.TRANSPORT, mapOf("transport" to "rtc", "requested" to deps?.requested))
        row(CallAnalytics.CONNECT, mapOf(
            "mode" to if (prepared) "prepared" else "cold",
            "warm" to prepared,
            "total_ms" to answeredAt - tapAt,
            "setup_ms" to inviteAt - tapAt,
            "dial_ms" to answeredAt - inviteAt,
            "mint_ms" to mintMs,
            "mint_route" to creds?.let { if (mintedDirect) "direct" else "web" },
            "gather_ms" to gatherMs,
            "register_ms" to uaRegisterMs,
            "redials" to Math.max(0, dials - 1),
            "prepared_age_s" to if (prepared) (tapAt - preparedAt) / 1000.0 else null,
            "ice_ms" to mediaAt - answeredAt,
        ))
        for (f in early) control?.sendHere("app_event", f)
        early.clear()
        // Dialled again on a fresh connection, and connected: the trouble lasted from the INVITE that went unanswered.
        redialFrom?.let { recovered(REDIAL, it) }
    }

    /** Once, at the end of a call that had media — before the BYE, or at the far side's: the audio, from the readings so far. */
    private fun qualityRow() {
        if (qualitySent || phase != Phase.LIVE) return
        qualitySent = true
        row(CallAnalytics.QUALITY, mapOf(
            "duration_s" to (now() - mediaAt) / 1000.0,
            "loss_pct" to quality.lossPct(),
            "jitter_avg_ms" to quality.jitterAvgMs(),
            "jitter_max_ms" to quality.jitterMaxMs(),
            "rtt_avg_ms" to quality.rttAvgMs(),
        ))
    }

    /**
     * The far side's audio across the call, from the readings taken five
     * times a second — for its quality row. The counters are the call's
     * running totals, so the latest reading is the whole call's; jitter and
     * the round trip are averaged over the readings that had one.
     */
    private class QualitySamples {
        private var lost = 0L
        private var received = 0L
        private var jitterSum = 0.0
        private var jitterMax = 0.0
        private var jitters = 0
        private var rttSum = 0.0
        private var rtts = 0

        fun add(s: GraineRtcStats) {
            lost = Math.max(lost, s.packetsLost)
            received = Math.max(received, s.packetsReceived)
            s.jitterSeconds?.takeIf { s.packetsReceived > 0 && it.isFinite() && it >= 0 }?.let {
                jitterSum += it
                jitterMax = Math.max(jitterMax, it)
                jitters++
            }
            s.roundTripSeconds?.takeIf { it.isFinite() && it >= 0 }?.let {
                rttSum += it
                rtts++
            }
        }

        /** Lost of all the far side sent, in percent; null before any packet. */
        fun lossPct(): Double? = (lost + received).takeIf { it > 0 }?.let { lost * 100.0 / it }
        fun jitterAvgMs(): Double? = if (jitters > 0) jitterSum / jitters * 1000 else null
        fun jitterMaxMs(): Double? = if (jitters > 0) jitterMax * 1000 else null
        fun rttAvgMs(): Double? = if (rtts > 0) rttSum / rtts * 1000 else null
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
                dropStartedAt?.let { began ->
                    step("media", "back after ${now() - began} ms")
                    // Healed by the restart it was given; back on its own, it is no recovery of ours.
                    if (restarting) recovered(ICE_RESTART, began)
                }
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
            else -> if (dialWanted) fallback(RtcFallback.ERROR, "the audio could not start") else {
                mediaFailure = "the audio could not start"
                watchPreparation()
            }
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
        quality.add(s)
        if (!firstAudioSeen && s.bytesReceived > 0) {
            firstAudioSeen = true
            firstAudioAt = now()
            step("audio", "first inbound audio ${now() - answeredAt} ms after the answer (${s.codec ?: "codec unknown"}, ${s.localCandidateType ?: "path unknown"})")
        }
        val level = speaking.level(s)
        if (!firstVoiceSeen && level != null && level > 0.01) {
            firstVoiceSeen = true
            step("audio", "the agent's voice ${now() - answeredAt} ms after the answer")
            row(CallAnalytics.VOICE, mapOf(
                "tap_to_voice_ms" to now() - tapAt,
                "answer_to_voice_ms" to now() - answeredAt,
                "first_audio_ms" to firstAudioAt?.let { it - answeredAt },
                // "audio/opus" and 48000 → "audio/opus/48000", which the row writes as "opus/48000".
                "codec" to s.codec?.let { c -> s.clockRate?.let { "$c/$it" } ?: c },
                "candidate" to s.localCandidateType,
            ))
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

    /**
     * The phone took the call's audio (a phone call, another app): no
     * recording and no playout until it is back. Before the dial there is
     * nothing to stop — the audio is off until the INVITE, which keeps it off
     * if the phone still has it.
     */
    fun setOnHold(value: Boolean) = scheduler.post {
        if (onHold == value) return@post
        onHold = value
        if (audioOn) peer?.setAudioActive(recording = !value, playout = !value)
    }

    /** A newer screen: queued until the control channel joins, replacing any screen still waiting. */
    fun sendContext(frame: JSONObject) = scheduler.post {
        if (phase != Phase.DONE) control?.send("app_context", CallFrames.appContext(frame))
    }

    /**
     * Hang up from this side — BYE, or CANCEL before the answer — and release
     * everything. A call that had media writes its quality row first, from the
     * readings it already has: nothing is waited for. No event follows.
     */
    fun hangUp() {
        released = true
        scheduler.post {
            if (phase == Phase.DONE) return@post
            qualityRow()
            phase = Phase.DONE
            release()
        }
    }

    // ── endings ─────────────────────────────────────────────────────────────

    private fun fallback(reason: String, detail: String) {
        if (phase == Phase.DONE || phase == Phase.LIVE) return
        // Answered, and no audio path opened: a media row, which the conversation carrying the call instead records.
        if (phase == Phase.ANSWERED && reason == RtcFallback.ICE_TIMEOUT) {
            row(CallAnalytics.MEDIA, mapOf("reason" to if (iceState == "failed") "ice_failed" else "ice_timeout", "transport" to "rtc"))
        }
        phase = Phase.DONE
        step("fallback", "$reason: $detail")
        release()
        if (early.isNotEmpty()) {
            val rows = early.toList()
            early.clear()
            listener.onRows(rows)
        }
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
        // The far side's BYE, or the path lost: the quality row at once, while the channel is still open.
        qualityRow()
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
        /** A mint answered by the telephony gateway itself, as the step log names it. */
        const val DIRECT = "direct"
        /** How the connection healed, in the analytics rows' words. */
        const val REDIAL = "redial"
        const val ICE_RESTART = "ice_restart"
        const val SOCKET_REPLACED = "socket_replaced"
        /** Rows kept from before media — far more than a call makes: a guard, not a budget. */
        const val EARLY_ROWS = 16
    }
}
