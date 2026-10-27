@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WEBRTC STAYS UP ON A WEAK NETWORK — the rules (RtcRules.kt: RtcPace,
 * RtcResilience, RtcDial's third dial, RtcIce's paced cap), the voice bar's
 * numbers (lib/rtc-resilience.ts).
 */
class RtcResilienceTest {
    // ── the link's pace ──

    @Test fun nothingMeasuredIsAGoodLinkAndNoDeadlineShrinks() {
        assertEquals(1.0, RtcPace.of(null, null, null), 0.0)
        assertEquals(1.0, RtcPace.of(400, 120, 60.0), 0.0)
        assertEquals(1.0, RtcPace.of(0, -5, Double.NaN), 0.0)
        assertEquals(8_000, RtcResilience.setupDeadlineMs(1.0))
        assertEquals(8_000, RtcResilience.setupDeadlineMs(0.2))
    }

    @Test fun theSlowestReadingSetsThePaceCapped() {
        assertEquals(2.0, RtcPace.of(4_000, null, null), 0.0)
        assertEquals(1.5, RtcPace.of(null, 1_200, null), 0.0)
        assertEquals(2.0, RtcPace.of(null, null, 500.0), 0.0)
        assertEquals(RtcPace.MAX, RtcPace.of(60_000, null, 9_000.0), 0.0)
        assertEquals(16_000, RtcResilience.setupDeadlineMs(2.0))
        assertEquals(20_000, RtcResilience.setupDeadlineMs(RtcPace.MAX))
        assertEquals("twice its deadline at most, never past 30 s", 30_000, RtcResilience.setupExtendedCapMs(RtcPace.MAX))
        assertEquals(6_000, RtcResilience.restartWindowMs(1.0))
        assertEquals(15_000, RtcResilience.restartWindowMs(RtcPace.MAX))
        assertEquals(15_000, RtcResilience.pathLostGiveUpMs(1.0))
        assertEquals(30_000, RtcResilience.pathLostGiveUpMs(RtcPace.MAX))
        assertEquals(2_500, RtcResilience.earlyRestartMs(1.0))
        assertTrue(RtcResilience.earlyRestartMs(RtcPace.MAX) < RtcResilience.setupDeadlineMs(RtcPace.MAX))
    }

    // ── dials ──

    @Test fun aLinkFailureEarnsAThirdDialAStuckServerKeepsTwo() {
        assertEquals(3, RtcDial.MAX_DIALS_ON_LINK_FAILURE)
        for (r in listOf("no_response", SipFailure.CONNECTION_ERROR, SipFailure.REQUEST_TIMEOUT)) {
            assertEquals(r, RtcDial.Verdict.REDIAL, RtcDial.verdict(null, r, 1))
            assertEquals("$r: the third dial", RtcDial.Verdict.REDIAL, RtcDial.verdict(null, r, 2))
            assertEquals("$r: never a fourth", RtcDial.Verdict.FALLBACK, RtcDial.verdict(null, r, 3))
        }
        assertEquals("heard and not answered keeps two", RtcDial.Verdict.FALLBACK, RtcDial.verdict(null, "no_answer", 2))
        assertEquals("a 5xx keeps two", RtcDial.Verdict.FALLBACK, RtcDial.verdict(503, SipFailure.SIP_FAILURE_CODE, 2))
        assertEquals(RtcDial.Verdict.REDIAL, RtcDial.verdict(503, SipFailure.SIP_FAILURE_CODE, 1))
    }

    /** Production, 26 Oct 2026, a +300 ms link: the INVITE with credentials answered 491, and the call went to the WebSocket at once. */
    @Test fun requestPendingIsDialledAgainNeverTheWebSocketAtOnce() {
        assertEquals(RtcDial.Verdict.REDIAL, RtcDial.verdict(491, SipFailure.SIP_FAILURE_CODE, 1))
        assertEquals(RtcDial.Verdict.REDIAL, RtcDial.verdict(491, SipFailure.SIP_FAILURE_CODE, 2))
        assertEquals(RtcDial.Verdict.FALLBACK, RtcDial.verdict(491, SipFailure.SIP_FAILURE_CODE, 3))
        assertEquals("a 4xx that is an answer is still one", RtcDial.Verdict.FALLBACK, RtcDial.verdict(486, "Busy", 1))
        assertEquals("491 Request Pending", RtcDial.describe(491, SipFailure.SIP_FAILURE_CODE))
    }

    @Test fun theAnswerSeatbeltIsExactlyHalfASecondOnAGoodLink() {
        assertEquals(500, RtcDial.answerAfterHeardMs(1.0))
        assertEquals(500, RtcDial.answerAfterHeardMs(0.4))
        assertEquals(1_000, RtcDial.answerAfterHeardMs(2.0))
        assertEquals("still far short of the 3 s that was withdrawn", 1_250, RtcDial.answerAfterHeardMs(9.0))
    }

    // ── gathering ──

    @Test fun theRelayWaitGrowsOnASlowLinkOnlyWhileUdpShowedNothing() {
        assertEquals(RtcIce.GATHER_CAP_MS, RtcIce.capFor(false, 2.5, false))
        assertEquals(RtcIce.RELAY_CAP_MS, RtcIce.capFor(true, 1.0, false))
        assertEquals(4_000, RtcIce.capFor(true, 1.6, false))
        assertEquals("twice at most", 5_000, RtcIce.capFor(true, 2.5, false))
        assertEquals("UDP works: the relay is a spare", RtcIce.RELAY_CAP_MS, RtcIce.capFor(true, 2.5, true))
    }

    @Test fun aRelayIsReadByTheProtocolItReachesItsRelayWith() {
        // libwebrtc's type preference, the priority's top byte: 2 UDP, 1 TCP, 0 TLS.
        assertEquals(RtcIce.Via.RELAY_UDP, RtcIce.via("candidate:3 1 udp ${(2L shl 24) or 0xFFFF00L or 255L} 203.0.113.9 50000 typ relay raddr 0.0.0.0 rport 0"))
        assertEquals(RtcIce.Via.RELAY_TCP, RtcIce.via("candidate:4 1 udp ${(1L shl 24) or 0xFFFF00L or 255L} 203.0.113.9 50001 typ relay raddr 0.0.0.0 rport 0"))
        assertEquals(RtcIce.Via.RELAY_TLS, RtcIce.via("a=candidate:5 1 udp ${0xFFFF00L or 255L} 203.0.113.9 50002 typ relay raddr 0.0.0.0 rport 0"))
        assertEquals("unreadable: UDP, never evidence of a block", RtcIce.Via.RELAY_UDP, RtcIce.via("candidate:6 typ relay"))
        assertEquals(RtcIce.Via.SRFLX, RtcIce.via("candidate:2 1 udp 1685987072 198.51.100.7 65463 typ srflx raddr 192.0.2.10 rport 65463"))
        assertEquals(RtcIce.Via.HOST, RtcIce.via("candidate:1 1 udp 2122194688 192.0.2.10 65463 typ host"))
    }

    // ── UDP that looks blocked ──

    private val servers = listOf(
        GraineRtcIceServer(listOf("stun:stun.relay.example:80"), null, null),
        GraineRtcIceServer(listOf("turn:relay.example:80"), "u", "c"),
        GraineRtcIceServer(listOf("turn:relay.example:80?transport=tcp"), "u", "c"),
        GraineRtcIceServer(listOf("turn:relay.example:443", "turns:relay.example:443?transport=tcp"), "u2", "c2"),
    )

    @Test fun theRelaysOverTcpAndTlsArePickedOutCredentialsKept() {
        assertTrue(RtcResilience.isTcpTlsRelayUrl("turns:relay.example:443?transport=tcp"))
        assertTrue(RtcResilience.isTcpTlsRelayUrl("turn:relay.example:80?transport=tcp"))
        assertFalse(RtcResilience.isTcpTlsRelayUrl("turn:relay.example:80"))
        assertFalse(RtcResilience.isTcpTlsRelayUrl("stun:stun.example:3478"))
        val relays = RtcResilience.tcpTlsRelays(servers)
        assertEquals(listOf(listOf("turn:relay.example:80?transport=tcp"), listOf("turns:relay.example:443?transport=tcp")), relays.map { it.urls })
        assertEquals(listOf("u", "u2"), relays.map { it.username })
        assertTrue(RtcResilience.tcpTlsRelays(listOf(GraineRtcIceServer(listOf("turn:x:3478"), "u", "c"))).isEmpty())
    }

    private fun gathered(vararg kinds: String) = RtcResilience.Gathered().apply {
        for (k in kinds) add(when (k) {
            "host" -> "candidate:1 1 udp 2122194688 192.0.2.10 1 typ host"
            "srflx" -> "candidate:2 1 udp 1685987072 198.51.100.7 1 typ srflx"
            "relay/udp" -> "candidate:3 1 udp ${2L shl 24} 203.0.113.9 1 typ relay"
            "relay/tcp" -> "candidate:4 1 udp ${1L shl 24} 203.0.113.9 1 typ relay"
            else -> "candidate:5 1 udp 255 203.0.113.9 1 typ relay"
        })
    }

    @Test fun udpLooksBlockedOnlyWhenNothingCameBackOverItAndATcpOrTlsRelayExists() {
        assertTrue(RtcResilience.udpLooksBlocked(gathered("host", "relay/tcp", "relay/tls"), servers))
        assertTrue("nothing public over UDP, a TCP relay configured", RtcResilience.udpLooksBlocked(gathered("host"), servers))
        assertFalse(RtcResilience.udpLooksBlocked(gathered("host", "srflx"), servers))
        assertFalse(RtcResilience.udpLooksBlocked(gathered("host", "relay/udp", "relay/tcp"), servers))
        assertFalse("no TCP or TLS relay to move to", RtcResilience.udpLooksBlocked(gathered("host"), listOf(GraineRtcIceServer(listOf("turn:x:3478"), "u", "c"))))
        assertFalse("nothing gathered is a different problem", RtcResilience.udpLooksBlocked(gathered(), servers))
        val g = gathered("host", "relay/tcp")
        g.forget()
        assertFalse("a new network forgets", RtcResilience.udpLooksBlocked(g, servers))
    }

    // ── restarts ──

    @Test fun theFirstRestartAlwaysMayASecondOnlyWhenSomethingChangedARefusalEndsThem() {
        assertTrue(RtcResilience.restartPermitted(0, false, false, false, false))
        assertFalse(RtcResilience.restartPermitted(1, false, false, false, false))
        assertTrue(RtcResilience.restartPermitted(1, true, false, false, false))
        assertTrue(RtcResilience.restartPermitted(1, false, true, false, false))
        assertFalse(RtcResilience.restartPermitted(1, false, true, true, false))
        assertFalse("never a third", RtcResilience.restartPermitted(2, true, false, false, false))
        assertFalse(RtcResilience.restartPermitted(0, false, false, false, true))
    }

    @Test fun nothingInFlightIsAnOfferOrAnAnswerWithNoCandidate() {
        assertTrue(RtcResilience.nothingInFlight(0, 1))
        assertTrue(RtcResilience.nothingInFlight(2, 0))
        assertFalse(RtcResilience.nothingInFlight(2, 1))
        assertFalse("not known yet says nothing", RtcResilience.nothingInFlight(-1, -1))
        assertEquals(2, RtcSdp.candidateCount(Trace.OFFER))
        assertEquals(1, RtcSdp.candidateCount(Trace.ANSWER))
    }
}
