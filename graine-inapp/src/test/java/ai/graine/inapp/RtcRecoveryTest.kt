package ai.graine.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE MEDIA PATH, KEPT — the recovery's clock (RtcRecovery.kt), driven with a
 * stand-in for the call: what it restarts, when, and what it never does
 * (restart into no network, restart after the dial is over, end a call itself).
 */
class RtcRecoveryTest {
    private val sched = ManualScheduler()

    private class Sent(val n: Int, val relayOnly: Boolean, val on: RtcRecovery.Handlers)

    private inner class Host : RtcRecovery.Host {
        var pace = 1.0
        var online = true
        var canReoffer = true
        var progressing: Boolean? = null
        var nothingInFlight = false
        var udpBlocked = false
        val restarts = mutableListOf<Sent>()
        val failed = mutableListOf<String>()
        val reconnecting = mutableListOf<Boolean>()
        val recovered = mutableListOf<Pair<Int, Long>>()
        override fun pace() = pace
        override fun online() = online
        override fun canReoffer() = canReoffer
        override fun restart(n: Int, relayOnly: Boolean, on: RtcRecovery.Handlers): Boolean { restarts += Sent(n, relayOnly, on); return true }
        override fun progressing() = progressing
        override fun nothingInFlight() = nothingInFlight
        override fun udpBlocked() = udpBlocked
        override fun mediaFailed(lost: Boolean, detail: String) { failed += if (lost) "lost" else "setup" }
        override fun reconnecting(active: Boolean) { reconnecting += active }
        override fun recovered(restarts: Int, afterMs: Long) { recovered += restarts to afterMs }
        override fun log(line: String) {}
    }

    private val host = Host()
    private val r = RtcRecovery(sched, host)

    // ── answered, the path never up ──

    @Test fun aSetupWithNoPathIsRestartedAtItsDeadlineNotBeforeAndNotHandedOver() {
        r.answered()
        sched.advance(7_999)
        assertTrue(host.restarts.isEmpty())
        sched.advance(1)
        assertEquals(1, host.restarts.size)
        host.restarts[0].on.offer(3)
        host.restarts[0].on.answered()
        sched.advance(1_200)
        r.pathUp()
        assertEquals(listOf(1 to 9_200L), host.recovered)
        assertTrue("a call that never connected is never reconnecting", host.reconnecting.isEmpty())
        sched.advance(60_000)
        assertTrue(host.failed.isEmpty())
    }

    @Test fun aSetupStillMakingProgressIsNeverCutUntilItsCap() {
        host.progressing = true
        r.answered()
        sched.advance(15_999)
        assertTrue("a moving setup was restarted", host.restarts.isEmpty())
        sched.advance(1)
        assertEquals(1, host.restarts.size)
    }

    @Test fun aRefusedRestartHandsTheSetupOverAndNoMoreAreAsked() {
        r.answered()
        sched.advance(8_000)
        host.restarts[0].on.refused("486")
        assertEquals(listOf("setup"), host.failed)
        sched.advance(60_000)
        assertEquals(1, host.restarts.size)
        assertEquals(listOf("setup"), host.failed)
    }

    @Test fun aRestartThatGatheredNothingMovesOnNow() {
        r.answered()
        sched.advance(8_000)
        host.restarts[0].on.offer(0)
        assertEquals("an offer with no candidate waited out its window", listOf("setup"), host.failed)
    }

    @Test fun theWindowAfterARestartIsItsOwnAndASecondNeedsAChange() {
        r.answered()
        sched.advance(8_000)
        host.restarts[0].on.answered()
        sched.advance(RtcResilience.RESTART_WINDOW_MS - 1)
        assertTrue(host.failed.isEmpty())
        sched.advance(1)
        assertEquals("the same restart repeated with nothing changed", 1, host.restarts.size)
        assertEquals(listOf("setup"), host.failed)
    }

    @Test fun aSecondRestartWhenTheNetworkChanged() {
        r.answered()
        sched.advance(8_000)
        host.restarts[0].on.answered()
        r.networkChanged(true)
        sched.advance(RtcResilience.RESTART_WINDOW_MS)
        assertEquals(2, host.restarts.size)
        assertTrue(host.failed.isEmpty())
    }

    @Test fun noRestartIntoNoNetworkOrOnASocketThatIsDownTriedAgainInsideTheCap() {
        host.online = false
        r.answered()
        sched.advance(8_000 + 2 * RtcResilience.RESTART_RETRY_MS)
        assertTrue("a re-INVITE went into no network", host.restarts.isEmpty())
        host.online = true
        host.canReoffer = false
        sched.advance(RtcResilience.RESTART_RETRY_MS)
        assertTrue(host.restarts.isEmpty())
        host.canReoffer = true
        sched.advance(RtcResilience.RESTART_RETRY_MS)
        assertEquals(1, host.restarts.size)
    }

    @Test fun nothingInFlightIsRestartedEarlyAndStillNeverHandedOverBeforeTheDeadline() {
        host.nothingInFlight = true
        r.answered()
        sched.advance(2_499)
        assertTrue(host.restarts.isEmpty())
        sched.advance(1)
        assertEquals("a setup with nothing in flight waited for the deadline", 1, host.restarts.size)
        host.restarts[0].on.offer(0)
        sched.advance(1_000)
        assertTrue("handed over sooner than the deadline it had before", host.failed.isEmpty())
        sched.advance(8_000 - 3_500 - 1)
        assertTrue(host.failed.isEmpty())
        sched.advance(1)
        assertEquals(listOf("setup"), host.failed)
    }

    @Test fun iceFailedBeforeThePathCameUpIsActedOnAtOnce() {
        r.answered()
        sched.advance(1_000)
        r.pathLost(true)
        assertEquals(1, host.restarts.size)
    }

    // ── lost mid-call ──

    @Test fun aLostPathSaysReconnectingIsRestartedThreeSecondsInAndSaysWhenItIsBack() {
        r.answered(); r.pathUp()
        r.pathLost(false)
        assertEquals(listOf(true), host.reconnecting)
        sched.advance(2_999)
        assertTrue(host.restarts.isEmpty())
        sched.advance(1)
        assertEquals(1, host.restarts.size)
        host.restarts[0].on.answered()
        sched.advance(500)
        r.pathUp()
        assertEquals(listOf(true, false), host.reconnecting)
        assertEquals(1, host.recovered.size)
        assertTrue(host.failed.isEmpty())
    }

    @Test fun iceFailedMidCallRestartsAtOnce() {
        r.answered(); r.pathUp()
        r.pathLost(true)
        assertEquals("restarted at once, not on the next turn of the clock", 1, host.restarts.size)
    }

    @Test fun aRefusedRestartNeverEndsTheCallTheGiveUpDecides() {
        r.answered(); r.pathUp()
        r.pathLost(true)
        host.restarts[0].on.refused("486")
        assertTrue("a refused restart ended the call", host.failed.isEmpty())
        r.pathUp()
        r.pathLost(true)
        assertEquals("a far side that refused is asked no more", 1, host.restarts.size)
        sched.advance(15_000)
        assertEquals(listOf("lost"), host.failed)
    }

    @Test fun offlineTimeIsWaitedOutNotCountedAndTheReturnGetsAMomentToHeal() {
        r.answered(); r.pathUp()
        host.online = false
        r.networkChanged(false)
        r.pathLost(false)
        sched.advance(20_000)
        assertTrue("a restart went into no network", host.restarts.isEmpty())
        assertTrue("time offline was held against the path", host.failed.isEmpty())
        host.online = true
        r.networkChanged(true)
        sched.advance(RtcResilience.ONLINE_SETTLE_MS - 1)
        assertTrue(host.restarts.isEmpty())
        sched.advance(1)
        assertEquals(1, host.restarts.size)
    }

    @Test fun offlineForTooLongIsGivenUpOn() {
        r.answered(); r.pathUp()
        host.online = false
        r.pathLost(false)
        sched.advance(RtcResilience.OFFLINE_HOLD_MS)
        assertEquals(listOf("lost"), host.failed)
        assertEquals(listOf(true, false), host.reconnecting)
    }

    @Test fun aLostPathIsGivenUpOnFifteenSecondsInNeverInsideAnOpenRestartWindow() {
        host.canReoffer = false      // its socket reconnecting
        r.answered(); r.pathUp()
        r.pathLost(false)
        sched.advance(12_000)
        assertTrue(host.restarts.isEmpty())
        host.canReoffer = true
        sched.advance(RtcResilience.RESTART_RETRY_MS)
        assertEquals("sent at 13 s", 1, host.restarts.size)
        sched.advance(15_000 - 13_000 + 1_000)
        assertTrue("the give-up cut a restart whose window was open", host.failed.isEmpty())
        sched.advance(13_000 + RtcResilience.RESTART_WINDOW_MS - 16_000)
        assertEquals(listOf("lost"), host.failed)
    }

    @Test fun aSecondLossGetsItsOwnRestart() {
        r.answered(); r.pathUp()
        r.pathLost(false)
        sched.advance(3_000)
        host.restarts[0].on.answered()
        r.pathUp()
        r.pathLost(false)
        sched.advance(3_000)
        assertEquals(2, host.restarts.size)
    }

    // ── never after the dial is over ──

    @Test fun aRestartRacingAHangUpDoesNothingAfterDispose() {
        r.answered(); r.pathUp()
        r.pathLost(false)
        sched.advance(3_000)
        r.dispose()
        assertEquals("the reconnect is told over, once", listOf(true, false), host.reconnecting)
        host.restarts[0].on.refused("408")
        host.restarts[0].on.answered()
        sched.advance(60_000)
        assertTrue(host.failed.isEmpty())
        assertEquals(1, host.restarts.size)
    }

    @Test fun aSlowLinksDeadlineIsLonger() {
        host.pace = 2.0
        r.answered()
        sched.advance(8_000)
        assertTrue(host.restarts.isEmpty())
        sched.advance(8_000)
        assertEquals(1, host.restarts.size)
    }
}
