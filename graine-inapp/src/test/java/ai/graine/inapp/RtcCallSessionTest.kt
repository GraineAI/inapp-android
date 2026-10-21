@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ONE WEBRTC CALL, END TO END ON THE JVM: the mint faked, the SBC the fake
 * one (answering as production's does), the peer connection scripted, the
 * clock turned by hand. The happy path as the trace runs it; every way it
 * falls back before media (mint, registration, no reply, no answer, no media,
 * a refusal) and the gate refusals that end the call instead; the one redial;
 * the 12-second budget; the media watch's single ICE restart; the probe of a
 * stale registration; and the call's other half — captions, actions, mute,
 * hold and "agent speaking" — over the control channel and the stats.
 */
class RtcCallSessionTest {
    private val sched = ManualScheduler()
    private val sbc = FakeSbc(password = "pw")
    private val sipSockets = mutableListOf<FakeSipSocket>()
    private val controlSockets = mutableListOf<FakeSipSocket>()
    private val peers = mutableListOf<FakePeer>()
    private val links = HashMap<FakeSipSocket, SbcLink>()
    private val mints = mutableListOf<JSONObject>()
    private var openSip = true
    private val log = mutableListOf<String>()
    private val captions = mutableListOf<Triple<Role, String, Boolean>>()
    private val steps = mutableListOf<String>()
    private var turn = false

    private fun mintReply(agent: String? = "agent-web-1", expires: String = "2099-01-01T00:00:00+00:00") = JSONObject()
        .put("server", Trace.SERVER).put("username", "web-XXXX").put("password", "pw").put("realm", Trace.REALM)
        .put("application_sid", Trace.APP_SID).put("expires_at", expires)
        .put("ice_servers", JSONArray().put(JSONObject().put("urls", if (turn) "turn:relay.example.invalid:80" else "stun:stun.example.invalid:80")))
        .apply { if (agent != null) put("agent_id", agent) }

    private var mint: () -> HttpReply = { HttpReply(200, mintReply().toString()) }

    private val env = RtcEnv(
        scheduler = sched,
        createPeer = { _, l -> FakePeer().also { it.listener = l; peers += it } },
        http = HttpPost { url, json ->
            assertEquals("https://www.graine.ai/api/embed/rtc-session", url)
            mints += JSONObject(json)
            mint()
        },
        io = { it() },
        openSip = { url, protocols, l ->
            FakeSipSocket().also { s ->
                s.url = url; s.protocols = protocols; s.listener = l; sipSockets += s
                if (openSip) sched.post { s.open() }
            }
        },
        openControl = { url, l -> FakeSipSocket().also { it.url = url; it.listener = l; controlSockets += it } },
        sipRandom = FixedSipRandom(),
        newSessionId = { "0123456789abcdef0123456789abcdef" },
        wallClock = { 1_800_000_000_000L },
        steps = { step, detail -> steps += "$step: $detail" },
    )

    private val request = RtcCallRequest(
        baseUrl = "https://www.graine.ai",
        publishableKey = "pk_test_key",
        variables = RtcMint.variables(mapOf("who_called" to "customer", "call_reason" to "kyc_help"), mapOf("name" to "Asha")),
        identity = IdentityEnvelope(value = "u-42", installId = "inst-1", memory = true, timezone = "Asia/Kolkata"),
        callId = "call_1",
        wsHost = "wss://ws.graine.ai",
    )

    /** The reconnecting moments told, and how many log lines came before each: its place among the other events. */
    private val reconnects = mutableListOf<Boolean>()
    private val reconnectsAt = mutableListOf<Int>()

    private val listener = object : RtcCallSession.Listener {
        override fun onReconnecting(reconnecting: Boolean) { reconnects += reconnecting; reconnectsAt += log.size }
        override fun onMedia() { log += "media" }
        override fun onFallback(reason: String, detail: String) { log += "fallback $reason" }
        override fun onRefused(message: String) { log += "refused $message" }
        override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { log += "ended ${reason.wire}" }
        override fun onCaption(role: Role, text: String, live: Boolean) { captions += Triple(role, text, live) }
        override fun onAgentSpeaking(speaking: Boolean) { log += "speaking $speaking" }
        override fun onControlJoined() { log += "control" }
    }

    private var runner: (String, JSONObject, (ActionResult) -> Unit) -> Unit = { _, _, answer -> answer(ActionResult.ok("done")) }
    private val deps = RtcDialDeps({ n, a, answer -> runner(n, a, answer) }, { JSONObject().put("context", JSONObject().put("screen", "kyc")).put("seq", 1) })

    /** Drain the scheduler and let the SBC answer every socket until nothing moves. */
    private fun pump() {
        repeat(10) {
            sched.drain()
            for (s in sipSockets.toList()) links.getOrPut(s) { SbcLink(s, sbc, sched) }.pump()
        }
        sched.drain()
    }

    private val sip: FakeSipSocket get() = sipSockets.last()
    private val peer: FakePeer get() = peers.last()
    private val control: FakeSipSocket get() = controlSockets.single()

    /** The twelve seconds are the call's after its credential, never spent on a mint still on its way. */
    @Test fun theTwelveSecondsStartWhenTheMintAnswersNotBeforeIt() {
        val held = mutableListOf<() -> Unit>()
        val slow = RtcEnv(
            scheduler = sched,
            createPeer = { _, l -> FakePeer().also { it.listener = l; peers += it } },
            http = env.http,
            io = { work -> held += work },
            // The signalling socket never opens: only the budget can end the attempt.
            openSip = { url, protocols, l -> FakeSipSocket().also { s -> s.url = url; s.protocols = protocols; s.listener = l; sipSockets += s } },
            openControl = { url, l -> FakeSipSocket().also { it.url = url; it.listener = l; controlSockets += it } },
            sipRandom = FixedSipRandom(),
            newSessionId = { "0123456789abcdef0123456789abcdef" },
            wallClock = { 1_800_000_000_000L },
            steps = { step, detail -> steps += "$step: $detail" },
        )
        val s = RtcCallSession(request, slow)
        s.dial(deps, listener); pump()
        sched.advance(RtcDial.ATTEMPT_BUDGET_MS + 3_000); pump()
        assertTrue("a mint still on its way spends none of the budget", log.isEmpty())
        held.removeAt(0)(); pump()
        sched.advance(RtcDial.ATTEMPT_BUDGET_MS - 100); pump()
        assertTrue("twelve seconds from the mint's answer, not from the dial", log.isEmpty())
        sched.advance(100); pump()
        assertEquals(listOf("fallback register_failed"), log)
    }

    /** Minted, registered and gathered (a srflx candidate): ready to dial. */
    private fun prepared(): RtcCallSession {
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        return s
    }

    /** Dialled and answered, ICE connected: media. */
    private fun live(): RtcCallSession {
        val s = prepared()
        s.dial(deps, listener); pump()
        peer.ice("checking"); peer.ice("connected"); pump()
        assertEquals(listOf("media"), log)
        log.clear()
        return s
    }

    @Test fun placesTheCallAsTheBarDoes() {
        val s = prepared()
        // The mint: the key, the control channel's id, the call's variables, who the user is, which call.
        val m = mints.single()
        assertEquals("pk_test_key", m.getString("publishableKey"))
        assertEquals(s.sessionId, m.getString("session_id"))
        assertEquals("customer", m.getJSONObject("variables").getString("who_called"))
        assertEquals("kyc_help", m.getJSONObject("variables").getString("call_reason"))
        assertEquals("inst-1", m.getString("installId"))
        assertEquals("call_1", m.getString("callId"))
        // Registered on the mint's server, subprotocol sip; nothing dialled before the foreground service says so.
        assertEquals(Trace.SERVER, sip.url)
        assertEquals(listOf("sip"), sip.protocols)
        assertTrue(sip.requests("INVITE").isEmpty())
        assertTrue("no control socket before the dial", controlSockets.isEmpty())

        s.dial(deps, listener); pump()
        assertEquals("wss://ws.graine.ai/control/v1/${s.sessionId}", control.url)
        val invites = sip.requests("INVITE")
        assertEquals("the first, and the one with credentials", 2, invites.size)
        for (i in invites) {
            assertEquals("sip:app-${Trace.APP_SID}@${Trace.REALM}", i.requestUri)
            assertEquals(s.sessionId, i.header("X-Graine-Session-Id"))
            assertEquals("the mint's own agent id, exactly", "agent-web-1", i.header("X-Graine-Agent-Id"))
            assertEquals(Trace.APP_SID, i.header("X-Application-Sid"))
            assertEquals("90", i.header("Session-Expires"))
            assertTrue(i.header("Supported")!!.startsWith("timer,"))
            assertTrue("Opus, then PCMU", i.body.contains("m=audio 51750 UDP/TLS/RTP/SAVPF 111 0 63 9 8 13 110 126"))
        }
        assertEquals("the SBC's answer applied", Trace.ANSWER, peer.remoteAnswer)
        assertEquals("ACK", sip.messages().last().method)
        assertEquals("sip:${Trace.SBC}:5060;transport=tcp", sip.messages().last().requestUri)
        assertTrue(log.isEmpty())

        peer.ice("checking"); peer.ice("connected"); pump()
        assertEquals(listOf("media"), log)
        assertTrue("nothing on the control socket before control_ready", control.sent.isEmpty())
        control.deliver("{\"type\":\"control_ready\",\"session_id\":\"${s.sessionId}\"}"); pump()
        val sent = control.sent.map { JSONObject(it) }
        assertEquals(listOf("app_context", "app_event", "app_event"), sent.map { it.getString("type") })
        assertEquals("kyc", sent[0].getJSONObject("context").getString("screen"))
        // The analytics rows (CallAnalytics): the transport, then the connect.
        val transport = sent[1].getJSONObject("event")
        assertEquals("transport", transport.getString("name"))
        assertEquals("WebRTC", transport.getString("detail"))
        assertEquals("rtc", transport.getJSONObject("data").getString("transport"))
        val connect = sent[2].getJSONObject("event")
        assertEquals("connect", connect.getString("name"))
        assertTrue(connect.getBoolean("quiet") && connect.getBoolean("telemetry"))
        assertTrue("prepared before the dial", connect.getString("detail").startsWith("prepared · "))
        assertEquals("prepared", connect.getJSONObject("data").getString("mode"))
        assertEquals(true, connect.getJSONObject("data").getBoolean("warm"))
        assertEquals(listOf("media", "control"), log)
        assertTrue(sbc.badDigests.isEmpty())
    }

    @Test fun hangsUpWithByeThenUnregistersThenClosesTheControlAndTheMedia() {
        val s = live()
        s.hangUp(); pump()
        val heads = sip.sent.map { it.substringBefore(' ') }
        val bye = heads.indexOf("BYE")
        assertTrue(bye > 0)
        assertEquals("then the un-REGISTER (challenged, answered once)", listOf("BYE", "REGISTER", "REGISTER"), heads.drop(bye))
        assertEquals("0", sip.requests("REGISTER").last().header("expires"))
        assertEquals("the signalling socket closed 1000", 1000, sip.closed)
        assertEquals("the control channel closed", 1000, control.closed)
        assertTrue(peer.closed)
        assertTrue("no event after our own hang-up", log.isEmpty())
    }

    @Test fun aRefusedMintFallsBackAtTheDial() {
        mint = { HttpReply(403, "{\"error\":\"Add app:// to Allowed domains.\",\"code\":\"no_origin\"}") }
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        assertTrue("nothing to say until the call wants the line", log.isEmpty())
        s.dial(deps, listener); pump()
        assertEquals(listOf("fallback mint_failed"), log)
        assertTrue("no signalling, no media", sipSockets.isEmpty() && peers.isEmpty())
        assertTrue(steps.any { it.contains("no_origin") })
        assertFalse("never the server's sentence in a step", steps.any { it.contains("Allowed domains") })
    }

    @Test fun aRateLimitFallsBack() {
        mint = { HttpReply(429, "{\"error\":\"Too many requests.\",\"code\":\"rate_limited\"}", 30) }
        val s = RtcCallSession(request, env)
        s.dial(deps, listener); pump()
        assertEquals(listOf("fallback mint_failed"), log)
    }

    @Test fun aGateRefusalEndsTheCallWithTheWebSocketsOwnWordsAndNoFallback() {
        mint = { HttpReply(402, "{\"error\":\"Out of credits.\",\"code\":\"insufficient_credits\"}") }
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        s.dial(deps, listener); pump()
        assertEquals(listOf("refused close 4402 insufficient_credits: this account is out of credits - add credits to start a call"), log)
        mint = { HttpReply(409, "{\"error\":\"This agent is switched off.\",\"code\":\"agent_off\"}") }
        log.clear()
        val s2 = RtcCallSession(request, env)
        s2.dial(deps, listener); pump()
        assertEquals(listOf("refused close 4409 agent_off: This agent is switched off."), log)
        assertTrue(sipSockets.isEmpty())
    }

    @Test fun aMintWithoutTheAgentFallsBackRatherThanCallAnonymous() {
        mint = { HttpReply(200, mintReply(agent = null).toString()) }
        val s = RtcCallSession(request, env)
        s.dial(deps, listener); pump()
        assertEquals(listOf("fallback mint_failed"), log)
        assertTrue(sipSockets.isEmpty())
    }

    @Test fun aRefusedRegistrationFallsBack() {
        sbc.registerAnswer = 403
        val s = prepared()
        s.dial(deps, listener); pump()
        assertEquals(listOf("fallback register_failed"), log)
        assertTrue(peer.closed)
    }

    @Test fun aRegistrationThatNeverCompletesFallsBack() {
        openSip = false
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        sched.advance(15_000); pump()
        s.dial(deps, listener); pump()
        assertEquals(listOf("fallback register_failed"), log)
    }

    @Test fun noReplyIsDialledOnceMoreOnAFreshConnectionThenFallsBack() {
        sbc.inviteAnswer = FakeSbc.Answer.SILENT
        val s = prepared()
        s.dial(deps, listener); pump()
        assertEquals(1, sipSockets.size)
        sched.advance(3_999); pump()
        assertTrue(log.isEmpty())
        sched.advance(1); pump()
        assertEquals("a fresh socket and registration", 2, sipSockets.size)
        assertEquals("a fresh peer", 2, peers.size)
        assertTrue(peers[0].closed)
        peer.candidate("srflx"); pump()
        assertEquals(1, sip.requests("INVITE").size)
        sched.advance(4_000); pump()
        assertEquals(listOf("fallback no_response"), log)
        assertTrue(steps.any { it.startsWith("dial: no reply to the INVITE: redial") })
    }

    /** 16 Oct: the SBC said "trying" and the call never reached the agent, every other tap. */
    @Test fun aStuckDialIsCancelledAndDialledAgainAfterHalfASecondThenFallsBack() {
        sbc.inviteAnswer = FakeSbc.Answer.TRYING_ONLY
        val s = prepared()
        s.dial(deps, listener); pump()
        sched.advance(499); pump()
        assertTrue(log.isEmpty())
        assertEquals("heard: still waiting for the answer", 1, sipSockets.size)
        sched.advance(1); pump()
        assertEquals("the stuck INVITE was cancelled", 1, sipSockets[0].requests("CANCEL").size)
        assertEquals("a fresh socket and registration", 2, sipSockets.size)
        peer.candidate("srflx"); pump()
        assertEquals("dialled again: challenged, then with its credentials", 2, sip.requests("INVITE").size)
        sched.advance(500); pump()
        assertEquals("heard twice, never answered", listOf("fallback no_answer"), log)
        assertEquals("that INVITE was cancelled too", 1, sip.requests("CANCEL").size)
    }

    // ── the answer clock runs from the INVITE with credentials' own reply ──

    /** Dialled, with the SBC silent: the test writes each reply to each INVITE, at the moment it says. */
    private fun dialledByHand(): RtcCallSession {
        sbc.inviteAnswer = FakeSbc.Answer.SILENT
        val s = prepared()
        s.dial(deps, listener); pump()
        assertEquals(1, sip.requests("INVITE").size)
        return s
    }

    /** The INVITE with credentials the challenge to [first] brought. */
    private fun challenged(first: SipMessage, code: Int = 401): SipMessage {
        sip.deliver(sbc.challengeTo(first, code)); pump()
        val second = sip.requests("INVITE").last()
        assertTrue("sent again with credentials", (second.header("authorization") ?: second.header("proxy-authorization")) != null)
        return second
    }

    /** 20 Oct 2026, production: answered 181 ms after its own reply and dialled again anyway — timed from the first INVITE's 100 Trying. */
    @Test fun aChallengedDialAnsweredInsideHalfASecondOfItsOwnReplyIsNeverDialledAgain() {
        dialledByHand()
        val first = sip.requests("INVITE").single()
        sip.deliver(sbc.response(first, 100)); pump()       // 100 Trying to the first INVITE: a clock starts for it…
        sched.advance(30); pump()
        val second = challenged(first)                      // …and stops at the challenge
        sched.advance(470); pump()
        sip.deliver(sbc.response(second, 100)); pump()      // the INVITE with credentials' own reply, 500 ms in
        sched.advance(425); pump()                          // answered 425 ms after it: 925 ms after the first reply
        sip.deliver(sbc.answerTo(second)); pump()
        peer.ice("checking"); peer.ice("connected"); pump()
        assertEquals(listOf("media"), log)
        assertEquals("a healthy, challenged call was dialled a second time", 1, sipSockets.size)
        assertTrue("nothing was cancelled", sip.requests("CANCEL").isEmpty())
        assertTrue(steps.any { it.startsWith("challenged: 401 after 30 ms") })
    }

    @Test fun aChallengeIsNotHeardEvenWithNoReplyBeforeIt() {
        dialledByHand()
        val first = sip.requests("INVITE").single()
        sched.advance(10); pump()
        val second = challenged(first, 407)                 // a proxy challenge, with no 100 Trying before it
        sched.advance(600); pump()
        assertEquals("the challenge started an answer clock", 1, sipSockets.size)
        sip.deliver(sbc.response(second, 100)); pump()
        sched.advance(400); pump()
        sip.deliver(sbc.answerTo(second)); pump()
        peer.ice("connected"); pump()
        assertEquals(listOf("media"), log)
        assertEquals(1, sipSockets.size)
    }

    @Test fun anInviteWithCredentialsThatHearsNothingIsNoResponseNotAStuckDial() {
        dialledByHand()
        val first = sip.requests("INVITE").single()
        sip.deliver(sbc.response(first, 100)); pump()
        challenged(first)
        sched.advance(3_999); pump()
        assertEquals("an answer clock ran for an INVITE that has heard nothing", 1, sipSockets.size)
        sched.advance(1); pump()
        assertTrue(steps.any { it.startsWith("dial: no reply to the INVITE: redial") })
        assertEquals(2, sipSockets.size)
    }

    @Test fun anAnswerStillBeingAppliedWhenTheHalfSecondRunsOutIsNotAStuckDial() {
        dialledByHand()
        peer.holdAnswer = true
        val second = challenged(sip.requests("INVITE").single())
        sip.deliver(sbc.response(second, 100)); pump()
        sched.advance(RtcDial.ANSWER_AFTER_HEARD_MS - 1); pump()
        sip.deliver(sbc.answerTo(second)); pump()           // the 200 is in; its SDP is still being applied
        sched.advance(1); pump()
        sched.advance(1_000); pump()
        assertEquals("an answered dial was dialled again while its answer was applied", 1, sipSockets.size)
        assertTrue("an answered dial was cancelled", sip.requests("CANCEL").isEmpty())
        assertTrue("an answered dial was hung up", sip.requests("BYE").isEmpty())
        peer.completeAnswer()
        peer.ice("connected"); pump()
        assertEquals(listOf("media"), log)
    }

    // ── a dial given up on ends on the server, on its own socket ──

    @Test fun aStuckDialIsCancelledOnItsOwnSocketWhichStaysUntilItsInviteHasItsFinalAnswer() {
        dialledByHand()
        val first = sip.requests("INVITE").single()
        sip.deliver(sbc.response(first, 100)); pump()
        sched.advance(40); pump()
        val second = challenged(first)
        sched.advance(200); pump()
        sip.deliver(sbc.response(second, 100)); pump()      // its own reply — and then nothing
        sched.advance(RtcDial.ANSWER_AFTER_HEARD_MS - 1); pump()
        assertEquals(1, sipSockets.size)
        sched.advance(1); pump()
        assertEquals("dialled again on a fresh connection", 2, sipSockets.size)
        val old = sipSockets[0]
        assertEquals("the INVITE with credentials is the one cancelled", second.topViaBranch, old.requests("CANCEL").single().topViaBranch)
        assertEquals("its registration went at once", "0", old.requests("REGISTER").last().header("expires"))
        sched.advance(SipUserAgent.CLOSE_AFTER_MS + 500); pump()
        assertNull("the socket the CANCEL's ending travels on went first", old.closed)
        old.deliver(sbc.response(second, 487, tag = true)); pump()
        assertEquals("the 487 is ACKed on its own socket", "ACK", old.messages().last().method)
        assertEquals("then that socket goes", 1000, old.closed)
        assertTrue("the old dial told this call nothing", log.isEmpty())
    }

    @Test fun anAnswerThatCrossesAnAbandonedDialsCancelIsAckedAndEndedOnItsOwnSocket() {
        dialledByHand()
        val second = challenged(sip.requests("INVITE").single())
        sip.deliver(sbc.response(second, 100)); pump()
        sched.advance(RtcDial.ANSWER_AFTER_HEARD_MS); pump()
        val old = sipSockets[0]
        assertEquals(2, sipSockets.size)
        assertEquals(1, old.requests("CANCEL").size)
        // The server answered as the CANCEL left: that leg exists now, and only an ACK and a BYE end it.
        old.deliver(sbc.answerTo(second)); pump()
        assertEquals("sip:${Trace.SBC}:5060;transport=tcp", old.requests("ACK").last().requestUri)
        assertEquals("the crossing answer's leg was left up on the server", 1, old.requests("BYE").size)
        assertEquals("the BYE answered, that socket goes", 1000, old.closed)
        assertTrue("the abandoned dial's answer was taken for this call's", log.isEmpty())
        assertTrue(peers.none { it.remoteAnswer != null })
    }

    @Test fun anAbandonedDialWhoseInviteNeverSettlesLetsItsSocketGoAfterTheGrace() {
        dialledByHand()
        val second = challenged(sip.requests("INVITE").single())
        sip.deliver(sbc.response(second, 100)); pump()
        sched.advance(RtcDial.ANSWER_AFTER_HEARD_MS); pump()
        val old = sipSockets[0]
        sched.advance(RtcDial.ABANDONED_DIAL_GRACE_MS - 100); pump()
        assertNull(old.closed)
        sched.advance(100); pump()
        assertEquals("a dial with no final answer held its socket for ever", 1000, old.closed)
    }

    @Test fun aHangUpWhileItRingsCancelsTheDialOnTheServer() {
        val s = dialledByHand()
        val second = challenged(sip.requests("INVITE").single())
        sip.deliver(sbc.response(second, 100)); pump()
        s.hangUp(); pump()
        assertEquals(second.topViaBranch, sip.requests("CANCEL").single().topViaBranch)
        assertNull("the socket went before the CANCEL could finish", sip.closed)
        sip.deliver(sbc.response(second, 487, tag = true)); pump()
        assertEquals(1000, sip.closed)
        assertTrue("nothing is told after a hang-up", log.isEmpty())
    }

    @Test fun aHangUpBeforeTheInviteWithCredentialsWasHeardCancelsItAtItsFirstReply() {
        val s = dialledByHand()
        val first = sip.requests("INVITE").single()
        sip.deliver(sbc.response(first, 100)); pump()
        val second = challenged(first)
        s.hangUp(); pump()
        assertTrue("a CANCEL for an INVITE nothing has replied to", sip.requests("CANCEL").isEmpty())
        sched.advance(SipUserAgent.CLOSE_AFTER_MS + 500); pump()
        sip.deliver(sbc.response(second, 100)); pump()
        assertEquals("cancelled at its first reply", second.topViaBranch, sip.requests("CANCEL").single().topViaBranch)
        sip.deliver(sbc.answerTo(second)); pump()           // and an answer that crossed that CANCEL is ended
        assertEquals(1, sip.requests("BYE").size)
        assertEquals(1000, sip.closed)
        assertTrue(log.isEmpty())
    }

    @Test fun answeredWithNoMediaPathFallsBackAfterEightSeconds() {
        val s = prepared()
        s.dial(deps, listener); pump()
        peer.ice("checking"); pump()
        sched.advance(7_999); pump()
        assertTrue(log.isEmpty())
        sched.advance(1); pump()
        assertEquals(listOf("fallback ice_timeout"), log)
        assertEquals("the answered call is hung up", 1, sip.requests("BYE").size)
    }

    @Test fun aPolicyRefusalFallsBackWithoutASecondDial() {
        sbc.inviteAnswer = FakeSbc.Answer.BUSY
        val s = prepared()
        s.dial(deps, listener); pump()
        assertEquals(listOf("fallback error"), log)
        assertEquals(1, sipSockets.size)
    }

    @Test fun aServerFailureIsDialledAgain() {
        sbc.inviteAnswer = FakeSbc.Answer.UNAVAILABLE
        val s = prepared()
        s.dial(deps, listener); pump()
        assertEquals(2, sipSockets.size)
        sbc.inviteAnswer = FakeSbc.Answer.OK
        peer.candidate("srflx"); pump()
        peer.ice("connected"); pump()
        assertEquals(listOf("media"), log)
    }

    @Test fun theirByeEndsTheCallAsTheAgentsHangUp() {
        live()
        val invite = sip.requests("INVITE").last()
        val ack = sip.requests("ACK").last()
        sip.deliver(sbc.inDialog("BYE", invite, ack, cseq = 1)); pump()
        assertEquals(listOf("ended remote"), log)
        assertEquals(200, sip.messages().filter { !it.isRequest }.last().status)
    }

    @Test fun theirByeBeforeAnyAudioFallsBack() {
        val s = prepared()
        s.dial(deps, listener); pump()
        val invite = sip.requests("INVITE").last()
        val ack = sip.requests("ACK").last()
        sip.deliver(sbc.inDialog("BYE", invite, ack, cseq = 1)); pump()
        assertEquals(listOf("fallback error"), log)
    }

    @Test fun aLostPathGetsOneIceRestartThenTheNextLossIsFinal() {
        live()
        peer.ice("disconnected"); pump()
        sched.advance(2_999); pump()
        assertFalse(peer.calls.contains("createOffer(restart)"))
        sched.advance(1); pump()
        assertTrue(peer.calls.contains("createOffer(restart)"))
        peer.candidate("srflx"); pump()
        val reinvite = sip.requests("INVITE").last()
        assertTrue("inside the dialog", reinvite.toTag != null)
        assertEquals("sip:${Trace.SBC}:5060;transport=tcp", reinvite.requestUri)
        peer.ice("checking"); peer.ice("connected"); pump()
        assertTrue("back, the call goes on", log.isEmpty())
        peer.ice("disconnected"); pump()
        sched.advance(7_999); pump()
        assertTrue(log.isEmpty())
        sched.advance(1); pump()
        assertEquals(listOf("ended failed"), log)
    }

    @Test fun aRestartThatDoesNotReconnectWithinTenSecondsEndsTheCall() {
        live()
        peer.ice("disconnected"); pump()
        sched.advance(3_000); pump()
        peer.candidate("srflx"); pump()
        sched.advance(6_999); pump()
        assertTrue(log.isEmpty())
        sched.advance(1); pump()
        assertEquals(listOf("ended failed"), log)
    }

    @Test fun iceFailedRestartsAtOnceAndARefusedRestartEndsTheCall() {
        live()
        sbc.inviteAnswer = FakeSbc.Answer.BUSY
        peer.ice("failed"); pump()
        assertTrue(peer.calls.contains("createOffer(restart)"))
        peer.candidate("srflx"); pump()
        assertEquals(listOf("ended failed"), log)
    }

    @Test fun aStaleRegistrationIsProbedBeforeTheInvite() {
        val s = prepared()
        sched.advance(20_000); pump()
        val before = sip.requests("REGISTER").size
        s.dial(deps, listener); pump()
        assertTrue("asked again", sip.requests("REGISTER").size > before)
        assertEquals("then dialled on the same socket", 1, sipSockets.size)
        assertTrue(sip.requests("INVITE").isNotEmpty())
    }

    /** The offer is the dial's now: a stale registration is asked beside it, so the INVITE waits for the slower, not for both. */
    @Test fun aStaleRegistrationIsProbedBesideTheOfferNotAfterIt() {
        val s = prepared()
        sched.advance(20_000); pump()
        peer.offerImmediately = false
        val before = sip.requests("REGISTER").size
        s.dial(deps, listener); pump()
        assertTrue("asked again while the offer is still being made", sip.requests("REGISTER").size > before)
        assertTrue("no INVITE before the offer", sip.requests("INVITE").isEmpty())
        peer.completeOffer(); pump()
        assertTrue("both in: the INVITE", sip.requests("INVITE").isNotEmpty())
    }

    @Test fun aSilentStaleSocketIsReplacedBeforeTheInvite() {
        val s = prepared()
        sched.advance(20_000); sched.drain()
        val dead = sip
        // The NAT forgot it: nothing it sends is answered any more.
        links[dead] = SbcLink(FakeSipSocket(), sbc, sched)
        s.dial(deps, listener); sched.drain()
        sched.advance(RtcDial.probeDeadlineMs(0)); pump()
        assertEquals("a fresh socket", 2, sipSockets.size)
        assertTrue(sip.requests("INVITE").isNotEmpty())
        assertTrue(dead.requests("INVITE").isEmpty())
    }

    @Test fun captionsAndActionsRideTheControlChannel() {
        val s = live()
        control.deliver("{\"type\":\"control_ready\"}"); pump()
        control.sent.clear()
        control.deliver("{\"type\":\"interim_transcript_received\",\"content\":\"I need\"}")
        control.deliver("{\"type\":\"transcript\",\"content\":\"I need help\"}")
        control.deliver("{\"type\":\"text\",\"data\":\"Sure! I can\"}")
        control.deliver("{\"type\":\"text\",\"data\":\"help you\"}")
        control.deliver("{\"type\":\"end_of_stream\"}")
        pump()
        assertEquals(listOf(
            Triple(Role.CUSTOMER, "I need", true),
            Triple(Role.CUSTOMER, "I need help", false),
            Triple(Role.AGENT, "Sure! I can", true),
            Triple(Role.AGENT, "Sure! I can help you", true),
            Triple(Role.AGENT, "Sure! I can help you", false),
        ), captions)
        var ran: String? = null
        runner = { name, args, answer -> ran = "$name ${args.optString("screen")}"; answer(ActionResult.ok("opened")) }
        control.deliver("{\"type\":\"app_action\",\"action\":{\"action_id\":\"a1\",\"name\":\"open_screen\",\"arguments\":{\"screen\":\"kyc\"}}}"); pump()
        assertEquals("open_screen kyc", ran)
        val result = JSONObject(control.sent.last())
        assertEquals("app_action_result", result.getString("type"))
        assertEquals("a1", result.getString("action_id"))
        assertEquals("ok", result.getString("status"))
        s.sendContext(JSONObject().put("context", JSONObject().put("screen", "pay")).put("seq", 2)); pump()
        assertEquals("app_context", JSONObject(control.sent.last()).getString("type"))
    }

    @Test fun anActionWithNoAnswerIsAnsweredBeforeTheRuntimeGivesUp() {
        live()
        control.deliver("{\"type\":\"control_ready\"}"); pump()
        control.sent.clear()
        runner = { _, _, _ -> }
        control.deliver("{\"type\":\"app_action\",\"action\":{\"action_id\":\"a2\",\"name\":\"slow\"}}"); pump()
        sched.advance(CallFrames.ACTION_ANSWER_BY_MS); pump()
        assertEquals("error", JSONObject(control.sent.last()).getString("status"))
    }

    @Test fun muteAndHoldReachTheMedia() {
        val s = live()
        s.setMuted(true); pump()
        assertFalse(peer.micEnabled)
        s.setMuted(false); pump()
        assertTrue(peer.micEnabled)
        s.setOnHold(true); pump()
        assertFalse(peer.recording || peer.playout)
        s.setOnHold(false); pump()
        assertTrue(peer.recording && peer.playout)
        // Muted before the peer existed: applied to it when made.
        val early = RtcCallSession(request, env)
        early.setMuted(true); early.prepare(); pump()
        assertFalse(peer.micEnabled)
    }

    /**
     * A PREPARED CALL HOLDS NO MICROPHONE (found on a device, 19 Oct: the
     * library recorded from the moment the offer was set, so a call prepared
     * on the Home screen lit the privacy dot). Off as the peer is made; on
     * with the dial, before the INVITE leaves.
     */
    @Test fun nothingIsRecordedOrPlayedUntilTheDial() {
        val s = prepared()
        assertEquals("off as soon as the peer is made", listOf(false to false), peer.audioActive)
        assertFalse(peer.recording || peer.playout)
        var onAtInvite: Boolean? = null
        sip.onSend = { if (it.startsWith("INVITE ") && onAtInvite == null) onAtInvite = peer.recording && peer.playout }
        s.dial(deps, listener); pump()
        assertEquals("on with the dial, before the INVITE left", true, onAtInvite)
        assertEquals(listOf(false to false, true to true), peer.audioActive)
    }

    /**
     * NO OFFER BEFORE THE DIAL (the device again, 19 Oct: the library starts
     * the microphone when the offer is set, whatever setAudioActive says). A
     * prepared call has its peer — its candidate pool gathering — and nothing
     * else: no offer, so no track and no recording. The dial sets the offer,
     * the pooled candidates arrive at once, and the INVITE follows.
     */
    @Test fun aPreparedCallMakesNoOfferUntilTheDial() {
        val s = prepared()
        assertFalse("no offer while it waits for the tap", peer.calls.contains("createOffer"))
        assertEquals(null, peer.localDescription())
        assertTrue("ready all the same", steps.any { it.startsWith("register:") })
        s.dial(deps, listener); pump()
        assertEquals("the offer, at the dial", 1, peer.calls.count { it == "createOffer" })
        assertTrue("its cost at the dial, the pooled candidate in it", steps.any { it.matches(Regex("offer: \\d+ ms at the dial \\(pooled candidates: 1\\)")) })
        assertEquals("then the INVITE: the first, and the one with credentials", 2, sip.requests("INVITE").size)
    }

    @Test fun aColdCallsOfferFollowsItsPeerAtOnce() {
        val s = RtcCallSession(request, env)
        s.dial(deps, listener); pump()
        assertEquals("made after the dial: its offer at once", 1, peer.calls.count { it == "createOffer" })
        peer.candidate("srflx"); pump()
        assertEquals(2, sip.requests("INVITE").size)
    }

    @Test fun aPreparedCallLetGoNeverTurnedTheMicrophoneOn() {
        val s = prepared()
        sched.advance(60_000); pump()
        s.hangUp(); pump()
        assertTrue(peer.closed)
        assertTrue(sip.requests("INVITE").isEmpty())
        assertEquals("turned off as it was made, and never on", listOf(false to false), peer.audioActive)
        assertFalse("and it never made an offer", peer.calls.contains("createOffer"))
    }

    @Test fun aRedialsFreshPeerIsOffUntilItsOwnInvite() {
        sbc.inviteAnswer = FakeSbc.Answer.SILENT
        val s = prepared()
        s.dial(deps, listener); pump()
        sched.advance(4_000); pump()
        assertEquals("a fresh peer", 2, peers.size)
        assertEquals("off as it is made", listOf(false to false), peer.audioActive)
        var onAtInvite: Boolean? = null
        sip.onSend = { if (it.startsWith("INVITE ") && onAtInvite == null) onAtInvite = peer.recording && peer.playout }
        peer.candidate("srflx"); pump()
        assertEquals(true, onAtInvite)
    }

    @Test fun aHoldBeforeTheDialTurnsNothingOnAndTheInviteKeepsItOff() {
        val s = prepared()
        s.setOnHold(true); pump()
        s.setOnHold(false); pump()
        assertEquals("a hold and its end before the dial turn nothing on", listOf(false to false), peer.audioActive)
        s.setOnHold(true); pump()
        s.dial(deps, listener); pump()
        assertEquals("the phone still has the audio: the INVITE keeps it off", false to false, peer.audioActive.last())
        assertFalse(peer.recording || peer.playout)
        peer.ice("checking"); peer.ice("connected"); pump()
        s.setOnHold(false); pump()
        assertEquals("back: both on", true to true, peer.audioActive.last())
        s.setOnHold(true); pump()
        assertEquals("and off again, as a hold always did", false to false, peer.audioActive.last())
    }

    @Test fun theAgentSpeaksWhileItsAudioIsLoud() {
        live()
        peer.stats = rtcStats(bytes = 800, level = 0.3)
        sched.advance(200); pump()
        assertEquals(listOf("speaking true"), log)
        peer.stats = rtcStats(bytes = 1600, level = 0.0)
        sched.advance(800); pump()
        assertEquals(listOf("speaking true", "speaking false"), log)
        assertTrue(steps.any { it.startsWith("audio: first inbound audio") })
        assertTrue(steps.any { it.startsWith("audio: the agent's voice") })
    }

    @Test fun theirReofferIsAnsweredByTheMedia() {
        live()
        val invite = sip.requests("INVITE").last()
        val ack = sip.requests("ACK").last()
        sip.deliver(sbc.inDialog("INVITE", invite, ack, cseq = 5, body = Trace.ANSWER)); pump()
        assertTrue(peer.calls.contains("answerOffer"))
        val ok = sip.messages().last()
        assertEquals(200, ok.status)
        assertEquals("v=0\r\ns=answer\r\n", ok.body)
    }

    @Test fun aPreparedCallNeverDialledReleasesEverythingAndSendsNoInvite() {
        val s = prepared()
        s.hangUp(); pump()
        assertTrue(sip.requests("INVITE").isEmpty())
        assertEquals("unregistered", "0", sip.requests("REGISTER").last().header("expires"))
        assertEquals(1000, sip.closed)
        assertTrue(peer.closed)
        assertTrue(log.isEmpty())
        // And a dial after the hang-up does nothing.
        s.dial(deps, listener); pump()
        assertTrue(log.isEmpty() && controlSockets.isEmpty())
    }

    @Test fun waitsForTheRelayWhenTurnIsConfigured() {
        turn = true
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        s.dial(deps, listener); pump()
        peer.candidate("srflx"); pump()
        assertTrue("a srflx is no stop signal with TURN", sip.requests("INVITE").isEmpty())
        peer.candidate("relay"); pump()
        assertTrue(sip.requests("INVITE").isNotEmpty())
    }

    @Test fun goesWithWhatItHasAtTheCap() {
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        s.dial(deps, listener); pump()
        sched.advance(1_499); pump()
        assertTrue(sip.requests("INVITE").isEmpty())
        sched.advance(1); pump()
        assertTrue(sip.requests("INVITE").isNotEmpty())
    }

    @Test fun aCredentialAboutToExpireIsMintedAgainForTheRedial() {
        mint = { HttpReply(200, mintReply(expires = "2027-01-15T08:00:10+00:00").toString()) } // ten seconds past wallClock's 1.8e12
        sbc.inviteAnswer = FakeSbc.Answer.SILENT
        val s = prepared()
        assertNotEquals(0, mints.size)
        s.dial(deps, listener); pump()
        sched.advance(4_000); pump()
        assertEquals("minted again", 2, mints.size)
        assertEquals("with the same session id", mints[0].getString("session_id"), mints[1].getString("session_id"))
    }

    // ── reconnecting: the media path recovered after the call was up ────────

    @Test fun aDropAfterMediaIsReconnectingThroughItsRestartUntilTheMediaIsBack() {
        live()
        peer.ice("disconnected"); pump()
        assertEquals(listOf(true), reconnects)
        sched.advance(3_000); pump()
        assertTrue(peer.calls.contains("createOffer(restart)"))
        peer.candidate("srflx"); pump()
        assertEquals("one moment, the restart inside it", listOf(true), reconnects)
        peer.ice("checking"); peer.ice("connected"); pump()
        assertEquals(listOf(true, false), reconnects)
        assertTrue("the call goes on", log.isEmpty())
    }

    @Test fun aDropThatHealsOnItsOwnEndsTheMomentToo() {
        live()
        peer.ice("disconnected"); pump()
        peer.ice("connected"); pump()
        assertEquals(listOf(true, false), reconnects)
        peer.ice("disconnected"); pump()
        peer.ice("completed"); pump()
        assertEquals("every drop its own moment", listOf(true, false, true, false), reconnects)
    }

    @Test fun iceFailedIsReconnectingWhileItsRestartRuns() {
        live()
        peer.ice("failed"); pump()
        assertTrue(peer.calls.contains("createOffer(restart)"))
        assertEquals(listOf(true), reconnects)
        peer.candidate("srflx"); pump()
        peer.ice("checking"); peer.ice("connected"); pump()
        assertEquals(listOf(true, false), reconnects)
    }

    @Test fun aCallThatNeverHadMediaIsNeverReconnecting() {
        val s = prepared()
        s.dial(deps, listener); pump()
        peer.ice("checking"); peer.ice("disconnected"); pump()
        peer.ice("failed"); pump()
        sched.advance(15_000); pump()
        assertTrue("before media a drop is the dial's, never the call's", reconnects.isEmpty())
        assertTrue(log.single().startsWith("fallback"))
    }

    @Test fun aCallThatEndsWhileReconnectingSaysSoBeforeItsEnd() {
        live()
        peer.ice("disconnected"); pump()
        sched.advance(3_000); pump()
        peer.candidate("srflx"); pump()
        sched.advance(7_000); pump()
        assertEquals(listOf("ended failed"), log)
        assertEquals(listOf(true, false), reconnects)
        assertEquals("both before the end was told", listOf(0, 0), reconnectsAt)
    }

    @Test fun aHangUpWhileReconnectingTellsNothingMore() {
        val s = live()
        peer.ice("disconnected"); pump()
        s.hangUp(); pump()
        peer.ice("connected"); pump()
        assertEquals("no event follows a hang-up", listOf(true), reconnects)
    }
}
