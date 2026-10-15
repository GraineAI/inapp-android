@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SESSION GraineCalls DRIVES, OVER EITHER TRANSPORT: WebRTC carries the
 * call when it connects; a failure before any audio continues the SAME call on
 * the WebSocket — one ring, one screen, one `open` — with the reason recorded;
 * a refusal by a gate ends the call exactly as the WebSocket's refusal does;
 * and a call with no plan goes over the WebSocket as it always did.
 */
class CallTransportTest {
    private val sched = ManualScheduler()
    private val sbc = FakeSbc(password = "pw")
    private val sipSockets = mutableListOf<FakeSipSocket>()
    private val links = HashMap<FakeSipSocket, SbcLink>()
    private val peers = mutableListOf<FakePeer>()
    private val voiceSockets = mutableListOf<FakeSipSocket>()
    private val tickets = mutableListOf<String>()
    private val events = mutableListOf<String>()
    private val owner = FakeOwner()
    private var mint: () -> HttpReply = { HttpReply(200, mintReply()) }

    private fun mintReply() = JSONObject()
        .put("server", Trace.SERVER).put("username", "web-XXXX").put("password", "pw").put("realm", Trace.REALM)
        .put("application_sid", Trace.APP_SID).put("agent_id", "agent-web-1")
        .put("ice_servers", JSONArray().put(JSONObject().put("urls", "stun:stun.example.invalid:80"))).toString()

    private val http = HttpPost { url, json ->
        if (url.endsWith("/api/embed/rtc-session")) mint()
        else { tickets += json; HttpReply(200, "{\"ticket\":\"tkt\",\"wsUrl\":\"wss://ws.graine.ai/v1/s\"}") }
    }

    private val rtcEnv = RtcEnv(
        scheduler = sched,
        createPeer = { _, l -> FakePeer().also { it.listener = l; peers += it } },
        http = http,
        io = { it() },
        openSip = { url, protocols, l -> FakeSipSocket().also { s -> s.url = url; s.protocols = protocols; s.listener = l; sipSockets += s; sched.post { s.open() } } },
        openControl = { url, l -> FakeSipSocket().also { it.url = url; it.listener = l } },
        sipRandom = FixedSipRandom(),
        newSessionId = { "0123456789abcdef0123456789abcdef" },
    )

    private class FakeAudio : CallAudioIO {
        var onFrame: ((ShortArray) -> Unit)? = null
        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) { this.onFrame = onFrame }
        override fun play(pcm: ShortArray, sampleRate: Int) {}
        override fun clear() {}
        override fun bufferedSeconds(): Double = 0.0
        override fun stop() {}
    }
    private val audio = FakeAudio()

    private val config = CallSessionConfig(
        baseUrl = "https://www.graine.ai", publishableKey = "pk_test_key", agentId = "agent_1", webcallAgentId = "web_1",
        wsHost = "wss://ws.graine.ai", callVariables = mapOf("who_called" to "customer"), sdkVariables = emptyMap(), callId = "call_t",
    )
    private val deps = CallSessionDeps(
        scheduler = sched, audio = audio, http = http,
        openSocket = { url, _, l -> FakeSipSocket().also { it.url = url; it.listener = l; voiceSockets += it } },
        io = { it() },
    )
    private val sessionEvents = object : CallSessionEvents {
        override fun onOpen() { events += "open" }
        override fun onFailed(error: String) { events += "failed: $error" }
        override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { events += "ended ${reason.wire}${error?.let { ": $it" } ?: ""}" }
    }

    /** Plans a WebRTC call and dials a fresh RtcCallSession for it. */
    private inner class FakeOwner : CallTransportOwner {
        val said = mutableListOf<String>()
        override fun rtcFor(plan: CallTransportPlan, config: CallSessionConfig): RtcCallSession? =
            if (plan.decision.webrtc) RtcCallSession(RtcCallRequest.of(config), rtcEnv) else null
        override fun carried(plan: CallTransportPlan) { said += "webrtc" }
        override fun fellBack(plan: CallTransportPlan, reason: String, detail: String) { said += "websocket: $reason" }
    }

    private fun plan(webrtc: Boolean = true) = CallTransports.put(CallTransportPlan("call_t", CallTransportChoice.Decision(webrtc, null), owner))

    private fun pump() {
        repeat(10) {
            sched.drain()
            for (s in sipSockets.toList()) links.getOrPut(s) { SbcLink(s, sbc, sched) }.pump()
        }
        sched.drain()
    }

    @After fun noPlanLeft() { CallTransports.claim("call_t") }

    @Test fun aCallWithNoPlanGoesOverTheWebSocket() {
        val s = CallTransportSession(config, deps, sessionEvents)
        s.start(); pump()
        assertEquals(1, tickets.size)
        assertTrue(sipSockets.isEmpty())
        voiceSockets.single().listener!!.onOpen("graine.embed.v1"); pump()
        assertEquals(listOf("open"), events)
    }

    @Test fun webRtcCarriesTheCallWhenItConnects() {
        plan()
        val s = CallTransportSession(config, deps, sessionEvents)
        s.start(); pump()
        peers.single().candidate("srflx"); pump()
        assertTrue("answered", peers.single().remoteAnswer != null)
        assertTrue("not open before media", events.isEmpty())
        peers.single().ice("connected"); pump()
        assertEquals(listOf("open"), events)
        assertEquals(listOf("webrtc"), owner.said)
        assertTrue("no ticket: the WebSocket never ran", tickets.isEmpty())
        s.hangUp(); pump()
        assertEquals(1, sipSockets.single().requests("BYE").size)
    }

    @Test fun aFailureBeforeMediaContinuesOnTheWebSocketInsideTheSameCall() {
        mint = { HttpReply(503, "{\"error\":\"down\"}") }
        plan()
        val s = CallTransportSession(config, deps, sessionEvents)
        s.setMuted(true)
        s.start(); pump()
        assertEquals(listOf("websocket: mint_failed"), owner.said)
        assertEquals("the WebSocket took the call", 1, tickets.size)
        assertTrue("and nothing has ended", events.isEmpty())
        val voice = voiceSockets.single()
        voice.listener!!.onOpen("graine.embed.v1"); pump()
        assertEquals(listOf("open"), events)
        // Muted before the switch: the WebSocket session is muted too.
        audio.onFrame!!.invoke(ShortArray(320) { 1000 }); pump()
        assertFalse(voice.sent.any { JSONObject(it).optString("type") == "audio" })
    }

    @Test fun answeredWithNoMediaContinuesOnTheWebSocket() {
        plan()
        val s = CallTransportSession(config, deps, sessionEvents)
        s.start(); pump()
        peers.single().candidate("srflx"); pump()
        sched.advance(RtcDial.ICE_CONNECT_DEADLINE_MS); pump()
        assertEquals(listOf("websocket: ice_timeout"), owner.said)
        assertEquals(1, sipSockets.single().requests("BYE").size)
        assertEquals(1, tickets.size)
    }

    @Test fun aGateRefusalEndsTheCallAsTheWebSocketsRefusalDoes() {
        mint = { HttpReply(409, "{\"error\":\"This agent is switched off.\",\"code\":\"agent_off\"}") }
        plan()
        val s = CallTransportSession(config, deps, sessionEvents)
        s.start(); pump()
        // The WebSocket shows this refusal as a socket that opened and was closed 4409 with this reason.
        assertEquals(listOf("open", "ended remote: close 4409 agent_off: This agent is switched off."), events)
        assertTrue("no second attempt", tickets.isEmpty())
        assertTrue(owner.said.isEmpty())
    }

    @Test fun aWebSocketPlanNeverTouchesWebRtc() {
        plan(webrtc = false)
        val s = CallTransportSession(config, deps, sessionEvents)
        s.start(); pump()
        assertEquals(1, tickets.size)
        assertTrue(peers.isEmpty() && sipSockets.isEmpty())
    }

    @Test fun hangingUpBeforeTheStartRunsStartsNothing() {
        plan()
        val s = CallTransportSession(config, deps, sessionEvents)
        s.start()
        s.hangUp(); pump()
        assertTrue(tickets.isEmpty() && sipSockets.isEmpty() && events.isEmpty())
    }

    @Test fun theRtcCallsAgentIdIsTheMints() {
        plan()
        val s = CallTransportSession(config, deps, sessionEvents)
        s.start(); pump()
        peers.single().candidate("srflx"); pump()
        for (invite in sipSockets.single().requests("INVITE")) assertEquals("agent-web-1", invite.header("X-Graine-Agent-Id"))
        s.hangUp(); pump()
    }

    @Test fun choosesTheTransport() {
        fun d(app: GraineCallTransport, dash: String?, module: Boolean, os: Boolean = true) = CallTransportChoice.decide(app, dash, module, os)
        d(GraineCallTransport.AUTO, null, true).let { assertTrue(it.webrtc); assertNull(it.reason) }
        d(GraineCallTransport.AUTO, null, false).let { assertFalse(it.webrtc); assertNull("not wanted without the module", it.reason) }
        d(GraineCallTransport.AUTO, "websocket", true).let { assertFalse(it.webrtc); assertEquals(RtcFallback.DASHBOARD, it.reason) }
        d(GraineCallTransport.AUTO, "webrtc", true).let { assertTrue(it.webrtc) }
        d(GraineCallTransport.WEBSOCKET, "webrtc", true).let { assertFalse("the app's word", it.webrtc); assertNull(it.reason) }
        d(GraineCallTransport.WEBRTC, null, false).let { assertFalse(it.webrtc); assertEquals(RtcFallback.UNAVAILABLE, it.reason) }
        d(GraineCallTransport.WEBRTC, "WebSocket", true).let { assertFalse("the dashboard's switch wins", it.webrtc); assertEquals(RtcFallback.DASHBOARD, it.reason) }
        d(GraineCallTransport.WEBRTC, null, true, os = false).let { assertFalse(it.webrtc); assertEquals(RtcFallback.UNAVAILABLE, it.reason) }
    }

    @Test fun readsTheDashboardsSwitchFromTheSessionsCalls() {
        assertEquals("websocket", GraineSchedulingRules.parse(JSONObject().put("audioTransport", "WebSocket")).audioTransport)
        assertEquals("webrtc", GraineSchedulingRules.parse(JSONObject().put("audioTransport", "webrtc")).audioTransport)
        assertNull(GraineSchedulingRules.parse(JSONObject().put("audioTransport", "carrier-pigeon")).audioTransport)
        assertNull(GraineSchedulingRules.parse(null).audioTransport)
        assertNotNull(GraineCallsConfig().callTransport)
        assertEquals(GraineCallTransport.AUTO, GraineCallsConfig().callTransport)
    }
}
