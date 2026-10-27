@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE RULES OF A WEBRTC CALL, ported from the voice bar (lib/rtc-ice.ts,
 * lib/rtc-dial.ts) and held to the same cases: when gathering stops, how long
 * a dial is waited for, which failures earn a second dial, the offer's codec
 * order, the mint's body and its answers, and which transport a call tries.
 */
class RtcRulesTest {
    // ── gathering (rtc-ice.ts) ──

    @Test fun readsACandidatesType() {
        assertEquals(RtcIce.Kind.SRFLX, RtcIce.kind("candidate:2 1 udp 1685987072 198.51.100.7 65463 typ srflx raddr 0.0.0.0 rport 0"))
        assertEquals(RtcIce.Kind.RELAY, RtcIce.kind("a=candidate:1 1 udp 5 203.0.113.9 51750 typ relay raddr 192.0.2.1 rport 1"))
        assertEquals(RtcIce.Kind.HOST, RtcIce.kind("candidate:1 1 udp 2122194688 192.0.2.10 65463 typ host generation 0"))
        assertEquals(RtcIce.Kind.END, RtcIce.kind(null))
        assertEquals(RtcIce.Kind.END, RtcIce.kind(""))
        assertEquals(RtcIce.Kind.UNKNOWN, RtcIce.kind("candidate:1 1 udp 1 192.0.2.1 9 typ hostile"))
    }

    @Test fun stopsAtTheFirstRelayAtSrflxOnlyWithoutTurnAtTheEndOrAtTheCap() {
        assertTrue(RtcIce.stopNow(RtcIce.Kind.RELAY, 10, wantRelay = true))
        assertTrue("a public path, and no relay to wait for", RtcIce.stopNow(RtcIce.Kind.SRFLX, 10, wantRelay = false))
        assertFalse("with TURN the srflx is a trap: wait for the relay", RtcIce.stopNow(RtcIce.Kind.SRFLX, 10, RtcIce.RELAY_CAP_MS, wantRelay = true))
        assertTrue(RtcIce.stopNow(RtcIce.Kind.END, 10))
        assertFalse(RtcIce.stopNow(RtcIce.Kind.HOST, 1_499))
        assertTrue(RtcIce.stopNow(RtcIce.Kind.HOST, 1_500))
        assertFalse(RtcIce.stopNow(RtcIce.Kind.HOST, 2_499, RtcIce.capFor(true), wantRelay = true))
        assertTrue(RtcIce.stopNow(RtcIce.Kind.HOST, 2_500, RtcIce.capFor(true), wantRelay = true))
        assertEquals(1_500, RtcIce.capFor(false))
        assertEquals(2_500, RtcIce.capFor(true))
    }

    @Test fun knowsATurnServerWhenItSeesOne() {
        val stun = GraineRtcIceServer(listOf("stun:stun.example.invalid:80"), null, null)
        val turn = GraineRtcIceServer(listOf("turn:relay.example.invalid:80", "turns:relay.example.invalid:443?transport=tcp"), "u", "c")
        assertFalse(RtcIce.hasTurn(listOf(stun)))
        assertTrue(RtcIce.hasTurn(listOf(stun, turn)))
        assertTrue(RtcIce.hasTurn(listOf(GraineRtcIceServer(listOf(" TURNS:x.invalid"), null, null))))
    }

    // ── the dial (rtc-dial.ts) ──

    @Test fun scalesTheDeadlinesWithTheLinksPace() {
        assertEquals(4_000, RtcDial.heardDeadlineMs(null))
        assertEquals(4_000, RtcDial.heardDeadlineMs(0))
        assertEquals("twice the registration, at least 3 s", 3_000, RtcDial.heardDeadlineMs(300))
        assertEquals(5_000, RtcDial.heardDeadlineMs(2_500))
        assertEquals("at most 8 s", 8_000, RtcDial.heardDeadlineMs(10_000))
        assertEquals(2_500, RtcDial.probeDeadlineMs(null))
        assertEquals(1_500, RtcDial.probeDeadlineMs(356))
        assertEquals(3_000, RtcDial.probeDeadlineMs(2_000))
        assertEquals(4_000, RtcDial.probeDeadlineMs(9_000))
        assertEquals(15_000, RtcDial.ANSWER_DEADLINE_MS)
        assertEquals("a seatbelt: ~13x the 37 ms median answer, never 0.10.2's 3 s", 500, RtcDial.ANSWER_AFTER_HEARD_MS)
        // Two stuck dials, each with a second of setup, inside the attempt's budget: the redial must be reachable.
        assertTrue(RtcDial.MAX_DIALS * (RtcDial.ANSWER_AFTER_HEARD_MS + 1_000) < RtcDial.ATTEMPT_BUDGET_MS)
        assertEquals(8_000, RtcDial.ICE_CONNECT_DEADLINE_MS)
        assertEquals(12_000, RtcDial.ATTEMPT_BUDGET_MS)
        // A dial given up on keeps its socket for its CANCEL's ending: the voice bar's grace, longer than the stack's usual close.
        assertEquals(4_000, RtcDial.ABANDONED_DIAL_GRACE_MS)
        assertTrue(RtcDial.ABANDONED_DIAL_GRACE_MS > SipUserAgent.CLOSE_AFTER_MS)
    }

    @Test fun redialsTheNetworksFailuresOnceAndFallsBackOnPolicy() {
        val v = RtcDial::verdict
        assertEquals(RtcDial.Verdict.IGNORE, v(null, "Canceled", 1))
        for (r in listOf("no_answer", "Unavailable", "WebRTC Error", "Authentication Error")) {
            assertEquals(r, RtcDial.Verdict.REDIAL, v(null, r, 1))
            assertEquals("$r, out of dials", RtcDial.Verdict.FALLBACK, v(null, r, 2))
        }
        // The link's own failures earn a third dial (RtcResilienceTest), and never a fourth.
        for (r in listOf("no_response", "Connection Error", "Request Timeout")) {
            assertEquals(r, RtcDial.Verdict.REDIAL, v(null, r, 2))
            assertEquals("$r, out of dials", RtcDial.Verdict.FALLBACK, v(null, r, 3))
        }
        assertEquals("a 5xx is the network's", RtcDial.Verdict.REDIAL, v(503, "SIP Failure Code", 1))
        assertEquals("a 4xx is a refusal", RtcDial.Verdict.FALLBACK, v(420, "SIP Failure Code", 1))
        assertEquals(RtcDial.Verdict.FALLBACK, v(486, "Busy", 1))
        assertEquals(RtcDial.Verdict.FALLBACK, v(603, "Rejected", 1))
        assertEquals("408/480 are Unavailable: the network's", RtcDial.Verdict.REDIAL, v(408, SipFailure.forStatus(408).cause, 1))
        assertEquals("no reply to the INVITE", RtcDial.describe(null, "no_response"))
        assertEquals("Busy 486", RtcDial.describe(486, "Busy"))
    }

    // ── the offer ──

    @Test fun putsOpusThenPcmuFirstAndMovesNothingElse() {
        // The trace's offer: Chrome's own order, and the order the bar sent it in.
        val sdp = Trace.OFFER
        val out = RtcSdp.preferCodecs(sdp)
        assertTrue(out.contains("m=audio 51750 UDP/TLS/RTP/SAVPF 111 0 63 9 8 13 110 126\r\n"))
        assertEquals("only the m= line changes", sdp.replace("111 63 9 0 8 13 110 126", "111 0 63 9 8 13 110 126"), out)
        val pcmuFirst = "m=audio 9 UDP/TLS/RTP/SAVPF 0 8 111\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:8 PCMA/8000\r\na=rtpmap:111 opus/48000/2\r\n"
        assertTrue(RtcSdp.preferCodecs(pcmuFirst).startsWith("m=audio 9 UDP/TLS/RTP/SAVPF 111 0 8\r\n"))
        assertEquals("host srflx", RtcSdp.candidateTypes(sdp))
    }

    @Test fun theOfferCarriesWhatTheSbcAnswers() {
        // TRACE.md §5: PCMU at PT 0 and telephone-event/8000 at PT 126 are what the SBC takes.
        val out = RtcSdp.preferCodecs(Trace.OFFER)
        assertTrue(out.contains("a=rtpmap:0 PCMU/8000"))
        assertTrue(out.contains("a=rtpmap:126 telephone-event/8000"))
    }

    // ── the mint ──

    @Test fun mintsWithTheTicketsVariablesAndIdentityBesideThem() {
        val call = linkedMapOf("who_called" to "scheduled", "call_reason" to "payment_due", "graine_tz" to "never sent")
        val sdk = linkedMapOf<String, String>().apply { for (i in 1..20) put("k$i", "v$i"); put("who_called", "loses") }
        val vars = RtcMint.variables(call, sdk)
        assertEquals("the ticket's cap", 12, vars.size)
        assertEquals(listOf("who_called", "call_reason"), vars.keys.take(2))
        assertEquals("scheduled", vars["who_called"])
        assertFalse("no reserved key from the device", vars.keys.any { it.startsWith("graine_") })
        assertEquals(CallVariables.ticketVariables(linkedMapOf("who_called" to "scheduled", "call_reason" to "payment_due"), sdk.filterKeys { it != "graine_tz" }), vars)
        val identity = IdentityEnvelope(value = "u-42", installId = "inst-1", memory = true, timezone = "Asia/Kolkata")
        val body = RtcMint.body("pk_test_key", "0123456789abcdef0123456789abcdef", vars, identity, "call_9")
        assertEquals("pk_test_key", body.getString("publishableKey"))
        assertEquals("0123456789abcdef0123456789abcdef", body.getString("session_id"))
        assertEquals("u-42", body.getJSONObject("identity").getString("value"))
        assertEquals("installId: without it memory stays off", "inst-1", body.getString("installId"))
        assertEquals(true, body.getBoolean("memory"))
        assertEquals("Asia/Kolkata", body.getString("timezone"))
        assertEquals("call_9", body.getString("callId"))
        assertFalse("never the direct grant", body.has("grant_only"))
        assertTrue("this phone says when the call's audio plays", body.getBoolean("audio_ready_signal"))
        assertEquals("payment_due", body.getJSONObject("variables").getString("call_reason"))
        assertFalse("the identity rides beside the variables", body.getJSONObject("variables").has("identity"))
        assertFalse(RtcMint.body("pk", "s", emptyMap(), null, null).has("variables"))
    }

    private val reply = JSONObject()
        .put("server", Trace.SERVER).put("username", "web-1").put("password", "pw").put("realm", Trace.REALM)
        .put("application_sid", Trace.APP_SID).put("client_sid", "c1").put("agent_id", "agent-web-1")
        .put("expires_at", "2026-10-14T18:23:45.123456+00:00")
        .put("ice_servers", org.json.JSONArray()
            .put(JSONObject().put("urls", "stun:stun.example.invalid:80"))
            .put(JSONObject().put("urls", org.json.JSONArray().put("turn:relay.example.invalid:80").put("https://not-ice.example.invalid")).put("username", "u").put("credential", "c"))
            .put(JSONObject().put("urls", "ftp://nope.example.invalid")))
        .put("direct", JSONObject().put("grant", "v1.never-used"))

    @Test fun readsTheMintsCredentials() {
        val now = 1_760_000_000_000L
        val c = RtcMint.parse(reply.toString(), now)!!
        assertEquals(Trace.SERVER, c.server)
        assertEquals("sip:app-${Trace.APP_SID}@${Trace.REALM}", c.target)
        assertEquals("agent-web-1", c.agentId)
        assertEquals(2, c.iceServers.size)
        assertEquals(listOf("turn:relay.example.invalid:80"), c.iceServers[1].urls)
        assertEquals("u", c.iceServers[1].username)
        assertEquals(1_792_002_225_123L, c.expiresAtMs)
        assertFalse("the password is never printed", c.toString().contains("pw"))
    }

    @Test fun refusesCredentialsThatNameNoAgent() {
        // X-Graine-Agent-Id must be the mint's own: without it the call would reach the agent anonymous.
        assertNull(RtcMint.parse(JSONObject(reply.toString()).apply { remove("agent_id") }.toString(), 0))
        for (k in listOf("server", "username", "password", "realm", "application_sid")) {
            assertNull(k, RtcMint.parse(JSONObject(reply.toString()).apply { remove(k) }.toString(), 0))
        }
        assertNull("not a socket URL", RtcMint.parse(JSONObject(reply.toString()).put("server", "https://x.example.invalid").toString(), 0))
        assertNull("not JSON", RtcMint.parse("<html>", 0))
    }

    @Test fun readsTheExpiryInEveryShapeTheServerWrites() {
        val now = 1_000L
        assertEquals(1_792_002_225_123L, RtcMint.expiry("2026-10-14T18:23:45.123456+00:00", now))
        assertEquals(1_792_002_225_000L, RtcMint.expiry("2026-10-14T18:23:45Z", now))
        assertEquals("+05:30 is earlier in UTC", 1_792_002_225_000L - 19_800_000L, RtcMint.expiry("2026-10-14T18:23:45+05:30", now))
        assertEquals(1_792_002_225_000L, RtcMint.expiry("1792002225", now))
        assertNull("in the past", RtcMint.expiry("1970-01-01T00:00:00Z", now))
        assertNull(RtcMint.expiry("tomorrow", now))
        assertNull(RtcMint.expiry(null, now))
    }

    @Test fun aGateRefusalEndsWithTheWebSocketsOwnWords() {
        fun body(code: String) = JSONObject().put("error", "Out of credits.").put("code", code).toString()
        val credit = "close 4402 insufficient_credits: this account is out of credits - add credits to start a call"
        assertEquals(credit, RtcMint.refusal(402, body("insufficient_credits")))
        assertEquals(credit, RtcMint.refusal(402, body("parent_out_of_credits")))
        assertEquals(credit, RtcMint.refusal(402, body("monthly_cap")))
        assertEquals("close 4409 agent_off: This agent is switched off.", RtcMint.refusal(409, body("agent_off")))
        assertEquals("close 4409 agent_deleted: This agent is no longer available.", RtcMint.refusal(409, body("agent_deleted")))
        assertNull("any other refusal falls back", RtcMint.refusal(403, body("no_origin")))
        assertNull(RtcMint.refusal(429, body("rate_limited")))
        assertNull("a 402 with no code", RtcMint.refusal(402, "{}"))
        assertNull(RtcMint.refusal(500, "oops"))
    }

    // ── speaking ──

    @Test fun theAgentSpeaksWhileItsLevelIsUpAndStopsAfterAQuietMoment() {
        val d = SpeakingDetector()
        assertNull(d.update(0.001, 0))
        assertEquals(true, d.update(0.2, 200))
        assertNull(d.update(0.3, 400))
        assertNull("quiet, but not for long", d.update(0.0, 600))
        assertNull(d.update(0.0, 800))
        assertEquals(false, d.update(0.0, 1_000))
        assertNull(d.update(null, 1_200))
    }

    @Test fun measuresTheLevelFromEnergyBetweenReadings() {
        val d = SpeakingDetector()
        assertEquals("the first reading has nothing to compare with", 0.5, d.level(rtcStats(level = 0.5, energy = 1.0, duration = 10.0))!!, 1e-9)
        // 0.2 s more at RMS 0.1: energy grows by 0.1² × 0.2.
        assertEquals(0.1, d.level(rtcStats(level = 0.9, energy = 1.0 + 0.002, duration = 10.2))!!, 1e-9)
    }

    @Test fun aSessionIdIs32LowerHexAndNewEachTime() {
        val a = RtcSessionId.next()
        assertTrue(Regex("[0-9a-f]{32}").matches(a))
        assertFalse(a == RtcSessionId.next())
    }
}
