@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOW A CALL CONNECTED, ON ITS RECORD — the analytics rows (CallAnalytics), as
 * a call sends them: the WebRTC call on its control channel, the WebSocket on
 * its voice socket. The clock is turned by hand, so every number is exact.
 *
 * transport and connect once at media (counted from the tap; prepared or
 * cold), voice once when the agent is first heard, a recovery each time the
 * connection heals, quality once at the end — before the BYE, or at the far
 * side's — and media when an answered call had no audio path, which goes with
 * the fallback to the WebSocket, as does the transport row's reason. Rows wait
 * for their channel and go if it never joins; nothing waits for a row. No row
 * carries an id, an identity or a value of the call's, nor more than twelve
 * keys.
 */
class CallAnalyticsRowsTest {
    private val sched = ManualScheduler()
    private val sbc = FakeSbc(password = "pw")
    private val sipSockets = mutableListOf<FakeSipSocket>()
    private val controlSockets = mutableListOf<FakeSipSocket>()
    private val peers = mutableListOf<FakePeer>()
    private val links = HashMap<FakeSipSocket, SbcLink>()
    private val log = mutableListOf<String>()
    /** What a WebRTC attempt handed over with its fallback. */
    private val handedOver = mutableListOf<String>()
    private var mint: () -> HttpReply = { HttpReply(200, mintReply()) }

    private fun mintReply() = JSONObject()
        .put("server", Trace.SERVER).put("username", "web-XXXX").put("password", "pw").put("realm", Trace.REALM)
        .put("application_sid", Trace.APP_SID).put("agent_id", "agent-web-1").put("expires_at", "2099-01-01T00:00:00+00:00")
        .put("ice_servers", JSONArray().put(JSONObject().put("urls", "stun:stun.example.invalid:80"))).toString()

    private val tickets = mutableListOf<String>()
    /** The signalling socket's open, held until [openPending]: a call still registering at its tap. */
    private var openSipLater = false
    private val heldOpen = mutableListOf<FakeSipSocket>()
    private fun openPending() { for (s in heldOpen.toList()) s.open(); heldOpen.clear() }
    private val http = HttpPost { url, json ->
        if (url.endsWith("/api/embed/rtc-session")) mint()
        else { tickets += json; HttpReply(200, "{\"ticket\":\"tkt\",\"wsUrl\":\"wss://ws.graine.ai/v1/s\"}") }
    }

    private val env = RtcEnv(
        scheduler = sched,
        createPeer = { _, l -> FakePeer().also { it.listener = l; peers += it } },
        http = http,
        io = { it() },
        openSip = { url, protocols, l ->
            FakeSipSocket().also { s ->
                s.url = url; s.protocols = protocols; s.listener = l; sipSockets += s
                if (openSipLater) heldOpen += s else sched.post { s.open() }
            }
        },
        openControl = { url, l -> FakeSipSocket().also { it.url = url; it.listener = l; controlSockets += it } },
        sipRandom = FixedSipRandom(),
        newSessionId = { SESSION },
        wallClock = { 1_800_000_000_000L },
    )

    private val request = RtcCallRequest(
        baseUrl = "https://www.graine.ai",
        publishableKey = "pk_test_key",
        variables = RtcMint.variables(mapOf("who_called" to "customer", "call_reason" to "kyc_help"), mapOf("name" to "Asha")),
        identity = IdentityEnvelope(value = "u-42", installId = "inst-1", memory = true, timezone = "Asia/Kolkata"),
        callId = "call_1",
        wsHost = "wss://ws.graine.ai",
    )

    /** What the call's connection report heard: each row as it was made, and the readings of the audio so far. */
    private val reported = mutableListOf<Pair<String, Map<String, Any?>>>()

    private val listener = object : RtcCallSession.Listener {
        override fun onMedia() { log += "media" }
        override fun onFallback(reason: String, detail: String) { log += "fallback $reason" }
        override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { log += "ended ${reason.wire}" }
        override fun onRows(frames: List<String>) { handedOver += frames }
        override fun onRow(name: String, data: Map<String, Any?>) { reported += name to data }
    }

    private fun deps(tapAt: Long? = null) = RtcDialDeps({ _, _, answer -> answer(ActionResult.ok()) }, { null }, tapAt = tapAt, requested = "auto")

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

    @After fun noPlanLeft() { CallTransports.claim("call_t") }

    /** The events written on [socket] as app events, in order, as their JSON text. */
    private fun rowsOn(socket: FakeSipSocket): List<String> =
        socket.sent.filter { it.startsWith(APP_EVENT) }.map { it.removePrefix(APP_EVENT).dropLast(1) }
    private fun rows(): List<String> = rowsOn(control)
    private fun names(rows: List<String> = rows()): List<String> = rows.map { JSONObject(it).getString("name") }
    private fun row(name: String, rows: List<String> = rows()): String = rows.single { JSONObject(it).getString("name") == name }

    /** Prepared at 0; the tap at [tapAt]; dialled at [dialAt] and answered at once; ICE connected [iceMs] later. */
    private fun live(tapAt: Long = 5_000, dialAt: Long = 5_300, iceMs: Long = 40): RtcCallSession {
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        sched.advance(dialAt); pump()
        s.dial(deps(tapAt), listener); pump()
        peer.ice("checking"); pump()
        sched.advance(iceMs); pump()
        peer.ice("connected"); pump()
        assertEquals(listOf("media"), log)
        log.clear()
        return s
    }

    // ── at media: the transport, then the connect ───────────────────────────

    @Test fun atMediaTheTransportThenTheConnectOnceOnTheControlChannel() {
        live()
        assertTrue("nothing on the channel before it joins", control.sent.isEmpty())
        control.deliver(READY); pump()
        assertEquals(listOf("transport", "connect"), names())
        assertEquals(CallAnalytics.event("transport", mapOf("transport" to "rtc", "requested" to "auto")), rows()[0])
        assertEquals("{\"name\":\"transport\",\"detail\":\"WebRTC\",\"quiet\":true,\"telemetry\":true,\"data\":{\"transport\":\"rtc\",\"requested\":\"auto\"}}", rows()[0])
        // Prepared five seconds before the tap; the dial 0.3 s after it, the answer at once, ICE 40 ms later. All twelve keys.
        assertEquals(
            "{\"name\":\"connect\",\"detail\":\"prepared · 0.3s (setup 0.3s, answer 0.0s)\",\"quiet\":true,\"telemetry\":true,\"data\":" +
                "{\"mode\":\"prepared\",\"warm\":true,\"total_ms\":300,\"setup_ms\":300,\"dial_ms\":0,\"mint_ms\":0,\"mint_route\":\"web\"," +
                "\"gather_ms\":0,\"register_ms\":0,\"redials\":0,\"prepared_age_s\":5,\"ice_ms\":40}}",
            rows()[1],
        )
        // Once: readings and time later, neither again.
        peer.stats = rtcStats(bytes = 800)
        sched.advance(2_000); pump()
        assertEquals(1, names().count { it == "transport" })
        assertEquals(1, names().count { it == "connect" })
    }

    @Test fun aColdCallIsColdAndCountsFromTheTap() {
        val s = RtcCallSession(request, env)
        sched.advance(1_000)
        s.dial(deps(tapAt = 800), listener); pump()
        peer.candidate("srflx"); pump()
        peer.ice("connected"); pump()
        control.deliver(READY); pump()
        assertEquals(
            "{\"name\":\"connect\",\"detail\":\"cold · 0.2s (setup 0.2s, answer 0.0s)\",\"quiet\":true,\"telemetry\":true,\"data\":" +
                "{\"mode\":\"cold\",\"warm\":false,\"total_ms\":200,\"setup_ms\":200,\"dial_ms\":0,\"mint_ms\":0,\"mint_route\":\"web\"," +
                "\"gather_ms\":0,\"register_ms\":0,\"redials\":0,\"ice_ms\":0}}",
            row("connect"),
        )
    }

    /** Ready between the tap (500) and the dial (800): registered at 700, so it was not prepared when the customer tapped. */
    @Test fun aCallThatWasStillRegisteringAtTheTapIsCold() {
        val s = RtcCallSession(request, env)
        openSipLater = true
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        sched.advance(700); openPending(); pump()
        sched.advance(100)
        s.dial(deps(tapAt = 500), listener); pump()
        peer.ice("connected"); pump()
        control.deliver(READY); pump()
        val data = JSONObject(row("connect")).getJSONObject("data")
        assertEquals("cold", data.getString("mode"))
        assertEquals(false, data.getBoolean("warm"))
        assertFalse("no age: it was not prepared", data.has("prepared_age_s"))
        assertEquals("counted from the tap", 300, data.getInt("total_ms"))
    }

    // ── the agent's voice ───────────────────────────────────────────────────

    @Test fun theAgentsVoiceOnceWhenItIsFirstHeard() {
        live()
        control.deliver(READY); pump()
        // Media at 5340: the first reading (5540) has audio but no voice; the second (5740) is the voice.
        peer.stats = rtcStats(bytes = 800, level = 0.0)
        sched.advance(200); pump()
        assertFalse(names().contains("voice"))
        peer.stats = rtcStats(bytes = 1600, level = 0.3)
        sched.advance(200); pump()
        assertEquals(
            "{\"name\":\"voice\",\"detail\":\"agent's voice 0.7s after the tap (0.4s after the answer)\",\"quiet\":true,\"telemetry\":true,\"data\":" +
                "{\"tap_to_voice_ms\":740,\"answer_to_voice_ms\":440,\"first_audio_ms\":240,\"codec\":\"PCMU\",\"candidate\":\"srflx\"}}",
            row("voice"),
        )
        peer.stats = rtcStats(bytes = 2400, level = 0.5)
        sched.advance(1_000); pump()
        assertEquals("once", 1, names().count { it == "voice" })
    }

    @Test fun theVoiceRowNamesTheCodecWithItsClockRate() {
        live()
        control.deliver(READY); pump()
        peer.stats = GraineRtcStats(1600, 10, 0, 0.3, null, null, 0.01, 1000, 0.05, "audio/opus", "relay", 48000)
        sched.advance(200); pump()
        val data = JSONObject(row("voice")).getJSONObject("data")
        assertEquals("opus/48000", data.getString("codec"))
        assertEquals("relay", data.getString("candidate"))
    }

    // ── recoveries ──────────────────────────────────────────────────────────

    @Test fun aRedialThatConnectsSaysSoAfterTheConnect() {
        sbc.inviteAnswer = FakeSbc.Answer.SILENT
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        s.dial(deps(tapAt = 0), listener); pump()
        sched.advance(4_000); pump()
        assertEquals("a fresh connection", 2, sipSockets.size)
        sbc.inviteAnswer = FakeSbc.Answer.OK
        peer.candidate("srflx"); pump()
        peer.ice("connected"); pump()
        control.deliver(READY); pump()
        assertEquals(listOf("transport", "connect", "recovery"), names())
        assertEquals(1, JSONObject(row("connect")).getJSONObject("data").getInt("redials"))
        // The trouble lasted from the INVITE nobody answered (0) until the redial's media (4000).
        assertEquals(CallAnalytics.event("recovery", mapOf("what" to "redial", "after_ms" to 4_000)), row("recovery"))
        assertEquals("redial after 4.0s", JSONObject(row("recovery")).getString("detail"))
    }

    @Test fun anIceRestartThatHealsSaysHowLongTheTroubleLasted() {
        live()
        control.deliver(READY); pump()
        peer.ice("disconnected"); pump()
        sched.advance(3_000); pump()
        assertTrue(peer.calls.contains("createOffer(restart)"))
        peer.candidate("srflx"); pump()
        sched.advance(500); pump()
        peer.ice("connected"); pump()
        assertEquals(CallAnalytics.event("recovery", mapOf("what" to "ice_restart", "after_ms" to 3_500)), row("recovery"))
        // Back on its own another time: no recovery of ours, no row.
        peer.ice("disconnected"); pump()
        sched.advance(1_000); pump()
        peer.ice("connected"); pump()
        assertEquals(1, names().count { it == "recovery" })
    }

    @Test fun aSilentSocketReplacedBeforeTheInviteSaysSoAtMedia() {
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        sched.advance(20_000); sched.drain()
        val dead = sip
        links[dead] = SbcLink(FakeSipSocket(), sbc, sched)
        s.dial(deps(tapAt = 20_000), listener); sched.drain()
        sched.advance(RtcDial.probeDeadlineMs(0)); pump()
        assertEquals("a fresh socket", 2, sipSockets.size)
        peer.ice("connected"); pump()
        control.deliver(READY); pump()
        assertEquals(listOf("transport", "connect", "recovery"), names())
        assertEquals(CallAnalytics.event("recovery", mapOf("what" to "socket_replaced", "after_ms" to RtcDial.probeDeadlineMs(0))), row("recovery"))
    }

    // ── the end ─────────────────────────────────────────────────────────────

    private fun stats(received: Long, lost: Long, jitter: Double, rtt: Double) =
        GraineRtcStats(received * 160, received, lost, 0.2, null, null, jitter, 1000, rtt, "audio/opus", "relay")

    @Test fun theQualityRowGoesBeforeTheByeWhenTheCustomerHangsUp() {
        val s = live()
        control.deliver(READY); pump()
        peer.stats = stats(received = 997, lost = 3, jitter = 0.012, rtt = 0.048)
        sched.advance(200); pump()
        peer.stats = stats(received = 1990, lost = 10, jitter = 0.020, rtt = 0.052)
        sched.advance(200); pump()
        sched.advance(100)
        var qualityAtBye: Boolean? = null
        sip.onSend = { if (it.startsWith("BYE ") && qualityAtBye == null) qualityAtBye = names().contains("quality") }
        s.hangUp(); pump()
        assertEquals("written before the BYE", true, qualityAtBye)
        // 0.5 s of media; 10 lost of 2000; jitter 12 and 20 ms; round trips 48 and 52 ms.
        assertEquals(
            "{\"name\":\"quality\",\"detail\":\"1s · loss 0.5% · jitter 16 ms\",\"quiet\":true,\"telemetry\":true,\"data\":" +
                "{\"duration_s\":1,\"loss_pct\":0.5,\"jitter_avg_ms\":16,\"jitter_max_ms\":20,\"rtt_avg_ms\":50}}",
            row("quality"),
        )
        assertEquals(1000, control.closed)
    }

    @Test fun theQualityRowGoesAtOnceOnTheirBye() {
        live()
        control.deliver(READY); pump()
        peer.stats = stats(received = 500, lost = 0, jitter = 0.01, rtt = 0.04)
        sched.advance(64_000); pump()
        val invite = sip.requests("INVITE").last()
        val ack = sip.requests("ACK").last()
        sip.deliver(sbc.inDialog("BYE", invite, ack, cseq = 1)); pump()
        assertEquals(listOf("ended remote"), log)
        val q = JSONObject(row("quality"))
        assertEquals("64s · loss 0.0% · jitter 10 ms", q.getString("detail"))
        assertEquals("written while the channel was open, then it closed", 1000, control.closed)
    }

    @Test fun aCallThatNeverHadMediaHasNoQualityRow() {
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        s.dial(deps(), listener); pump()
        s.hangUp(); pump()
        assertTrue(rowsOn(control).isEmpty())
    }

    // ── no audio path: the media row goes with the fallback ─────────────────

    @Test fun answeredWithNoAudioPathTheMediaRowGoesWithTheFallback() {
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        s.dial(deps(), listener); pump()
        peer.ice("checking"); pump()
        // An ICE restart inside the call at the deadline first (RtcRecovery); the WebSocket when its window ends.
        sched.advance(RtcDial.ICE_CONNECT_DEADLINE_MS + RtcResilience.RESTART_WINDOW_MS); pump()
        assertEquals(listOf("fallback ice_timeout"), log)
        assertEquals(listOf(CallAnalytics.frame("media", mapOf("reason" to "ice_timeout", "transport" to "rtc"))), handedOver)
        assertTrue("never on the WebRTC attempt's own channel", rowsOn(control).isEmpty())
    }

    @Test fun anAudioPathThatFailedSaysSo() {
        val s = RtcCallSession(request, env)
        s.prepare(); pump()
        peer.candidate("srflx"); pump()
        s.dial(deps(), listener); pump()
        peer.ice("checking"); peer.ice("failed"); pump()
        // Restarted at once; handed over at the deadline, never before it.
        sched.advance(RtcDial.ICE_CONNECT_DEADLINE_MS); pump()
        assertEquals(listOf("fallback ice_timeout"), log)
        assertEquals("{\"name\":\"media\",\"detail\":\"no audio path (ice_failed)\",\"quiet\":true,\"telemetry\":true,\"data\":{\"reason\":\"ice_failed\",\"transport\":\"rtc\"}}",
            handedOver.single().removePrefix(APP_EVENT).dropLast(1))
    }

    @Test fun aFallbackBeforeTheAnswerHandsOverNothing() {
        mint = { HttpReply(503, "{\"error\":\"down\"}") }
        val s = RtcCallSession(request, env)
        s.dial(deps(), listener); pump()
        assertEquals(listOf("fallback mint_failed"), log)
        assertTrue(handedOver.isEmpty())
    }

    // ── the channel ─────────────────────────────────────────────────────────

    @Test fun rowsWaitForTheControlChannelThenGoInOrder() {
        live()
        peer.stats = rtcStats(bytes = 800, level = 0.3)
        sched.advance(200); pump()
        assertTrue("queued: nothing sent before control_ready", control.sent.isEmpty())
        control.deliver(READY); pump()
        assertEquals(listOf("transport", "connect", "voice"), names())
    }

    @Test fun rowsOfAChannelThatNeverJoinedGoAndTheHangUpWaitsForNothing() {
        val s = live()
        s.hangUp(); pump()
        assertTrue("dropped with the channel", control.sent.isEmpty())
        assertEquals("the BYE went at once", 1, sip.requests("BYE").size)
        assertEquals(1000, control.closed)
    }

    /** Never an id, an identity or a value of the call's — every string is a word from a closed list. */
    @Test fun noRowCarriesAnIdOrAnIdentityAndNoneHasMoreThanTwelveKeys() {
        val s = live()
        control.deliver(READY); pump()
        peer.stats = rtcStats(bytes = 800, level = 0.3)
        sched.advance(200); pump()
        peer.ice("disconnected"); pump()
        sched.advance(3_000); pump()
        peer.candidate("srflx"); pump()
        peer.ice("connected"); pump()
        s.hangUp(); pump()
        val all = rows()
        assertEquals(listOf("transport", "connect", "voice", "recovery", "quality", "ended"), names(all))
        val secrets = listOf(SESSION, "call_1", "u-42", "inst-1", "agent-web-1", "web-XXXX", "Asha", "kyc_help", Trace.APP_SID, "pk_test_key", "Asia/Kolkata", "customer")
        for (r in all) {
            for (x in secrets) assertFalse("$x in $r", r.contains(x))
            @Suppress("UNCHECKED_CAST")
            val data = (OrderedJson.parse(r) as Map<String, Any?>)["data"] as Map<String, Any?>
            assertTrue(r, data.size <= CallAnalytics.MAX_KEYS)
            val name = JSONObject(r).getString("name")
            val keys = CallAnalytics.ROWS[name] ?: CallAnalytics.AHEAD.getValue(name)
            assertEquals("only the row's keys, in its order", keys.map { it.first }.filter { it in data }, data.keys.toList())
            for ((k, v) in data) if (v is String) {
                val type = keys.first { it.first == k }.second
                val list = when (type) {
                    "codec" -> CallAnalytics.ENUMS.getValue("codec")
                    "enum:end_cause" -> listOf(CallEnd.LOCAL_END, CallEnd.REMOTE_BYE, CallEnd.MEDIA_DROP, CallEnd.AUDIO_WATCH, CallEnd.FAILED)
                    else -> CallAnalytics.ENUMS.getValue(type.removePrefix("enum:"))
                }
                assertTrue("$k=$v is a word of its list", list.any { v == it || v.startsWith("$it/") })
            }
        }
    }

    // ── the WebSocket: the call's voice socket carries them ─────────────────

    private class FakeAudio : CallAudioIO {
        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {}
        override fun play(pcm: ShortArray, sampleRate: Int) {}
        override fun clear() {}
        override fun bufferedSeconds(): Double = 0.0
        override fun stop() {}
    }

    private val voiceSockets = mutableListOf<FakeSipSocket>()
    private val wsDeps = CallSessionDeps(
        scheduler = sched, audio = FakeAudio(), http = http,
        openSocket = { url, _, l -> FakeSipSocket().also { it.url = url; it.listener = l; voiceSockets += it } },
        io = { it() },
    )
    private val config = CallSessionConfig(
        baseUrl = "https://www.graine.ai", publishableKey = "pk_test_key", agentId = "agent_1", webcallAgentId = "web_1",
        wsHost = "wss://ws.graine.ai", callVariables = mapOf("who_called" to "customer"), sdkVariables = emptyMap(), callId = "call_t",
    )
    private val owner = object : CallTransportOwner {
        override fun rtcFor(plan: CallTransportPlan, config: CallSessionConfig): RtcCallSession? =
            if (plan.decision.webrtc) RtcCallSession(RtcCallRequest.of(config), env) else null
        override fun carried(plan: CallTransportPlan) {}
        override fun fellBack(plan: CallTransportPlan, reason: String, detail: String) { plan.fallbackReason = reason }
    }

    private fun plan(webrtc: Boolean, reason: String? = null, requested: String = "auto") =
        CallTransports.put(CallTransportPlan("call_t", CallTransportChoice.Decision(webrtc, reason), owner, requested))

    /** The tap at 0, the session at 300, the socket open 200 ms after it was asked for. */
    private fun onTheSocket(tapAt: Long? = 0): FakeSipSocket {
        sched.advance(300)
        val s = CallTransportSession(config.copy(tapAt = tapAt), wsDeps, object : CallSessionEvents {})
        s.start(); pump()
        return voiceSockets.single()
    }

    private fun open(voice: FakeSipSocket) {
        sched.advance(200)
        voice.listener!!.onOpen("graine.embed.v1"); pump()
    }

    @Test fun aWebSocketCallSaysItsTransportAndConnectWhenItsConversationStarts() {
        plan(webrtc = false, requested = "ws")
        val voice = onTheSocket()
        assertTrue("nothing before the socket opens", voice.sent.isEmpty())
        open(voice)
        assertEquals("the init first, then the rows", "init", JSONObject(voice.sent.first()).getString("type"))
        val rows = rowsOn(voice)
        assertEquals(listOf("transport", "connect"), names(rows))
        assertEquals("{\"name\":\"transport\",\"detail\":\"WebSocket\",\"quiet\":true,\"telemetry\":true,\"data\":{\"transport\":\"ws\",\"requested\":\"ws\"}}", rows[0])
        // Tapped at 0; the socket asked for at 300 (its ticket inside), open at 500. Never prepared.
        assertEquals(
            "{\"name\":\"connect\",\"detail\":\"cold · 0.5s (setup 0.3s, answer 0.2s)\",\"quiet\":true,\"telemetry\":true,\"data\":" +
                "{\"mode\":\"cold\",\"warm\":false,\"total_ms\":500,\"setup_ms\":300,\"dial_ms\":200,\"mint_ms\":0,\"mint_route\":\"web\"}}",
            rows[1],
        )
    }

    @Test fun aFallbackSaysWhyOnTheWebSocketConversationItContinuesOn() {
        mint = { HttpReply(503, "{\"error\":\"down\"}") }
        plan(webrtc = true)
        val voice = onTheSocket()
        open(voice)
        val rows = rowsOn(voice)
        assertEquals(listOf("transport", "connect"), names(rows))
        assertEquals(CallAnalytics.event("transport", mapOf("transport" to "ws", "requested" to "auto", "fallback_reason" to "mint_failed")), rows[0])
        assertEquals("WebSocket (fallback: mint_failed)", JSONObject(rows[0]).getString("detail"))
        assertEquals("counted from the tap, the WebRTC attempt inside", 500, JSONObject(rows[1]).getJSONObject("data").getInt("total_ms"))
    }

    @Test fun aCallTheDashboardSendsOverTheWebSocketSaysSo() {
        plan(webrtc = false, reason = RtcFallback.DASHBOARD)
        val voice = onTheSocket()
        open(voice)
        assertEquals("WebSocket (fallback: dashboard)", JSONObject(rowsOn(voice)[0]).getString("detail"))
    }

    @Test fun theMediaRowRidesTheFallbackToTheWebSocket() {
        plan(webrtc = true)
        sched.advance(300)
        val s = CallTransportSession(config.copy(tapAt = 0), wsDeps, object : CallSessionEvents {})
        s.start(); pump()
        peers.single().candidate("srflx"); pump()
        assertTrue("answered", peers.single().remoteAnswer != null)
        peers.single().ice("checking"); pump()
        sched.advance(RtcDial.ICE_CONNECT_DEADLINE_MS + RtcResilience.RESTART_WINDOW_MS); pump()
        val voice = voiceSockets.single()
        assertTrue(voice.sent.isEmpty())
        open(voice)
        val rows = rowsOn(voice)
        assertEquals(listOf("transport", "connect", "media"), names(rows))
        assertEquals("WebSocket (fallback: ice_timeout)", JSONObject(rows[0]).getString("detail"))
        assertEquals("{\"name\":\"media\",\"detail\":\"no audio path (ice_timeout)\",\"quiet\":true,\"telemetry\":true,\"data\":{\"reason\":\"ice_timeout\",\"transport\":\"rtc\"}}", rows[2])
    }

    @Test fun theWebSocketsVoiceRowOnceWhenTheAgentIsFirstHeard() {
        plan(webrtc = false, requested = "ws")
        val voice = onTheSocket()
        open(voice)
        fun audio(bytes: ByteArray) = "{\"type\":\"audio\",\"data\":\"${java.util.Base64.getEncoder().encodeToString(bytes)}\",\"meta_info\":{\"format\":\"pcm\",\"sample_rate\":16000}}"
        // At 600 the first audio — digital silence; at 900 the agent's voice.
        sched.advance(100); voice.deliver(audio(ByteArray(640))); pump()
        assertFalse(names(rowsOn(voice)).contains("voice"))
        sched.advance(300); voice.deliver(audio(ByteArray(640) { if (it % 2 == 0) 0x40 else 0x10 })); pump()
        assertEquals(
            "{\"name\":\"voice\",\"detail\":\"agent's voice 0.9s after the tap (0.4s after the answer)\",\"quiet\":true,\"telemetry\":true,\"data\":" +
                "{\"tap_to_voice_ms\":900,\"answer_to_voice_ms\":400,\"first_audio_ms\":100,\"codec\":\"L16/16000\"}}",
            row("voice", rowsOn(voice)),
        )
        sched.advance(100); voice.deliver(audio(ByteArray(640) { 0x20 })); pump()
        assertEquals("once", 1, names(rowsOn(voice)).count { it == "voice" })
    }

    @Test fun aSocketThatNeverOpensSendsNoRow() {
        mint = { HttpReply(503, "{\"error\":\"down\"}") }
        plan(webrtc = true)
        val voice = onTheSocket()
        voice.listener!!.onClosed(1006, "")
        pump()
        assertTrue(voice.sent.isEmpty())
    }

    @Test fun aSessionWithoutRowsSendsNone() {
        val voice = run {
            val s = VoiceCallSession(config, wsDeps, object : CallSessionEvents {})
            s.start(); pump()
            voiceSockets.single()
        }
        voice.listener!!.onOpen("graine.embed.v1"); pump()
        assertTrue(rowsOn(voice).isEmpty())
        assertNull(CallTransports.claim("call_t"))
    }

    // ── how the call ended (0.14.0): the `ended` row, ahead of the contract ──

    private fun ended(rows: List<String> = rows()) = JSONObject(row("ended", rows))

    @Test fun aCallThisSideEndsSaysSoBeforeTheBye() {
        val s = live()
        control.deliver(READY); pump()
        peer.stats = stats(received = 500, lost = 0, jitter = 0.01, rtt = 0.04)
        sched.advance(3_000); pump()
        var endedAtBye: Boolean? = null
        sip.onSend = { if (it.startsWith("BYE ") && endedAtBye == null) endedAtBye = names().contains("ended") }
        s.hangUp(); pump()
        assertEquals("written before the BYE", true, endedAtBye)
        assertEquals(listOf("quality", "ended"), names().takeLast(2))
        val e = ended()
        assertEquals(
            "{\"name\":\"ended\",\"detail\":\"hung up on this side after 3.0s, the agent heard\",\"quiet\":true,\"telemetry\":true,\"data\":" +
                "{\"cause\":\"local_end\",\"transport\":\"rtc\",\"after_ms\":3000,\"voice_heard\":true}}",
            row("ended"),
        )
        assertEquals("the call's report heard it too", "ended", reported.last().first)
        assertEquals("local_end", e.getJSONObject("data").getString("cause"))
    }

    @Test fun theFarSidesByeSaysRemoteBye() {
        live()
        control.deliver(READY); pump()
        sched.advance(1_000); pump()
        val invite = sip.requests("INVITE").last()
        val ack = sip.requests("ACK").last()
        sip.deliver(sbc.inDialog("BYE", invite, ack, cseq = 1)); pump()
        assertEquals(listOf("ended remote"), log)
        val data = ended().getJSONObject("data")
        assertEquals("remote_bye", data.getString("cause"))
        assertFalse("nothing heard yet", data.getBoolean("voice_heard"))
    }

    @Test fun anAudioPathThatDroppedSaysMediaDrop() {
        live()
        control.deliver(READY); pump()
        peer.ice("disconnected"); pump()
        sched.advance(RtcResilience.PATH_LOST_GIVE_UP_MS); pump()
        assertEquals(listOf("ended failed"), log)
        assertEquals("media_drop", ended().getJSONObject("data").getString("cause"))
    }

    @Test fun theAppSaysWhyItEndedTheCallWhenItWasNotTheUser() {
        val s = live()
        control.deliver(READY); pump()
        s.hangUp(CallEnd.FAILED); pump()
        assertEquals("failed", ended().getJSONObject("data").getString("cause"))
        s.hangUp(); pump()
        assertEquals("once", 1, names().count { it == "ended" })
    }

    @Test fun aWebSocketCallSaysHowItEndedBeforeItsStop() {
        plan(webrtc = false, requested = "ws")
        val heard = mutableListOf<Pair<String, Map<String, Any?>>>()
        sched.advance(300)
        val s = CallTransportSession(config.copy(tapAt = 0), wsDeps, object : CallSessionEvents {
            override fun onAnalytics(name: String, data: Map<String, Any?>) { heard += name to data }
        })
        s.start(); pump()
        val voice = voiceSockets.single()
        open(voice)
        sched.advance(2_000)
        s.hangUp(); pump()
        val types = voice.sent.map { runCatching { JSONObject(it).optString("type") }.getOrDefault("") }
        val endedAt = voice.sent.indexOfFirst { it.startsWith(APP_EVENT) && JSONObject(it).getJSONObject("event").getString("name") == "ended" }
        assertTrue("sent", endedAt >= 0)
        assertEquals("then the stop", "stop", types[endedAt + 1])
        val data = JSONObject(voice.sent[endedAt]).getJSONObject("event").getJSONObject("data")
        assertEquals("local_end", data.getString("cause"))
        assertEquals("ws", data.getString("transport"))
        assertEquals(2_000, data.getInt("after_ms"))
        assertEquals("ended", heard.last().first)
    }

    @Test fun aWebSocketTheFarSideClosedSaysRemoteByeToTheCallsReport() {
        plan(webrtc = false, requested = "ws")
        val causes = mutableListOf<String>()
        sched.advance(300)
        val s = CallTransportSession(config.copy(tapAt = 0), wsDeps, object : CallSessionEvents {
            override fun onAnalytics(name: String, data: Map<String, Any?>) { if (name == "ended") causes += data["cause"] as String }
        })
        s.start(); pump()
        val voice = voiceSockets.single()
        open(voice)
        voice.listener!!.onClosed(1000, ""); pump()
        assertEquals(listOf("remote_bye"), causes)
    }

    private companion object {
        const val SESSION = "0123456789abcdef0123456789abcdef"
        const val READY = "{\"type\":\"control_ready\"}"
        const val APP_EVENT = "{\"type\":\"app_event\",\"event\":"
    }

    // ── the connection report (GraineCalls.connection) ──────────────────────

    @Test fun everyRowIsToldToTheConnectionReportAsItIsMadeWithoutWaitingForTheChannel() {
        val s = live()
        assertEquals("at media, the channel not joined yet", listOf("transport", "connect"), reported.map { it.first })
        control.deliver(READY); pump()
        // Five readings: the agent heard at the first, the audio so far at the fifth.
        peer.stats = stats(received = 997, lost = 3, jitter = 0.012, rtt = 0.048)
        sched.advance(1_000); pump()
        assertEquals(listOf("transport", "connect", "voice", "quality"), reported.map { it.first })
        val live = CallConnectionReport().apply { reported.forEach { (n, d) -> fold(n, d) } }.value!!
        assertEquals("webrtc", live.transport)
        assertEquals(true, live.prepared)
        assertEquals(5L, live.preparedAgeSeconds)
        assertEquals(300L, live.setupMs)
        assertNull("prepared: its login was before the tap", live.loginMs)
        assertEquals(0L, live.answerMs)
        assertEquals(540L, live.tapToVoiceMs)
        assertEquals(240L, live.firstWordsMs)
        assertEquals(GraineCallConnection.Audio(0.3, 12, 12, 48, GraineCallConnection.GOOD), live.audio)
        assertEquals("a reading is never a row", 0, names().count { it == "quality" })
        s.hangUp(); pump()
        assertEquals("and the call's own at its end, then how it ended", listOf("quality", "ended"), reported.takeLast(2).map { it.first })
        assertEquals(1, names().count { it == "quality" })
    }

    @Test fun aWebSocketCallTellsItsConnectionReportToo() {
        plan(webrtc = false, requested = "ws")
        val heard = mutableListOf<String>()
        val report = CallConnectionReport()
        sched.advance(300)
        val s = CallTransportSession(config.copy(tapAt = 0), wsDeps, object : CallSessionEvents {
            override fun onAnalytics(name: String, data: Map<String, Any?>) { heard += name; report.fold(name, data) }
        })
        s.start(); pump()
        open(voiceSockets.single())
        assertEquals(listOf("transport", "connect"), heard)
        val c = report.value!!
        assertEquals("websocket", c.transport)
        assertEquals(false, c.prepared)
        assertEquals("tapped at 0, the socket asked for at 300", 300L, c.setupMs)
        assertEquals("open 200 ms later", 200L, c.answerMs)
        assertNull("no audio figures on the WebSocket", c.audio)
    }
}
