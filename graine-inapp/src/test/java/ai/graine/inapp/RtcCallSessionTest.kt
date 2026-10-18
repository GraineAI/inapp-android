@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    private val listener = object : RtcCallSession.Listener {
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
        val connect = sent[1].getJSONObject("event")
        assertEquals("connect", connect.getString("name"))
        assertTrue(connect.getBoolean("quiet") && connect.getBoolean("telemetry"))
        assertTrue("prepared before the dial", connect.getString("detail").startsWith("warm · "))
        assertEquals(true, connect.getJSONObject("data").getBoolean("warm"))
        val transport = sent[2].getJSONObject("event")
        assertEquals("transport", transport.getString("name"))
        assertEquals("WebRTC", transport.getString("detail"))
        assertEquals("rtc", transport.getJSONObject("data").getString("transport"))
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
    @Test fun aStuckDialIsCancelledAndDialledAgainAfterThreeSecondsThenFallsBack() {
        sbc.inviteAnswer = FakeSbc.Answer.TRYING_ONLY
        val s = prepared()
        s.dial(deps, listener); pump()
        sched.advance(2_999); pump()
        assertTrue(log.isEmpty())
        assertEquals("heard: still waiting for the answer", 1, sipSockets.size)
        sched.advance(1); pump()
        assertEquals("the stuck INVITE was cancelled", 1, sipSockets[0].requests("CANCEL").size)
        assertEquals("a fresh socket and registration", 2, sipSockets.size)
        peer.candidate("srflx"); pump()
        assertEquals("dialled again: challenged, then with its credentials", 2, sip.requests("INVITE").size)
        sched.advance(3_000); pump()
        assertEquals("heard twice, never answered", listOf("fallback no_answer"), log)
        assertEquals("that INVITE was cancelled too", 1, sip.requests("CANCEL").size)
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
}
