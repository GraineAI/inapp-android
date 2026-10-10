package ai.graine.inapp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE PLAYER'S RULES (sdk/NATIVE-CALLS.md "The player") on a hand-turned
 * clock: a run starts at 150 ms queued or after 120 ms without audio, a run
 * that played out refills before resuming, bufferedSeconds lands at exactly
 * zero, and clear drops everything at once.
 */
class CallPlayerTest {
    private var now = 0L
    private val rate = 24_000
    private fun ms(n: Int) = ShortArray(rate * n / 1000) { 1000 }

    /** A device that plays what it is given in real time on [now] — agent audio and silence alike. */
    private inner class Device {
        val ledger = DeviceLedger()
        private var head = 0L
        private var at = 0L
        fun advance() {
            head = Math.min(ledger.written, head + (now - at) * rate / 1000)
            at = now
        }
        fun agentUnplayed(): Int { advance(); return ledger.agentUnplayed(head) }
        fun write(c: ShortArray) { advance(); ledger.wroteAgent(c.size) }
    }

    @Test fun aRunStartsOnce150MsIsQueued() {
        val p = PlayoutBuffer(rate) { now }
        assertEquals(3600, p.startFrames)
        p.push(ms(100), 0)
        assertNull("100 ms is not yet a cushion", p.take(0))
        p.push(ms(60), 0)
        assertEquals(2400, p.take(0)?.size)
        assertEquals(1440, p.take(0)?.size)
        assertNull(p.take(0))
    }

    @Test fun aReplyShorterThanTheCushionStillPlaysAfter120MsOfQuiet() {
        val p = PlayoutBuffer(rate) { now }
        now = 1_000
        p.push(ms(80), 0)   // "Yes." — the turn is over and will never reach 150 ms
        assertEquals(120L, p.dueInMs())
        now = 1_119
        assertNull("waits for the drain path", p.take(0))
        assertEquals(1L, p.dueInMs())
        now = 1_120
        assertEquals(1920, p.take(0)?.size)
        assertNull(p.dueInMs())
    }

    @Test fun everyNewChunkRestartsTheDrainClock() {
        val p = PlayoutBuffer(rate) { now }
        p.push(ms(40), 0)
        now = 100
        p.push(ms(40), 0)
        now = 200
        assertNull("only 100 ms since the last chunk", p.take(0))
        now = 220
        assertNotNull(p.take(0))
    }

    @Test fun bufferedSecondsLandsAtExactlyZeroWhenDrained() {
        val p = PlayoutBuffer(rate) { now }
        val d = Device()
        p.push(ms(200), 0)
        p.push(ms(70), 0)
        assertEquals(0.27, p.bufferedSeconds(d.agentUnplayed()), 1e-9)
        while (true) d.write(p.take(d.agentUnplayed()) ?: break)
        assertEquals("all of it is on the device now", 0.27, p.bufferedSeconds(d.agentUnplayed()), 1e-9)
        now += 100
        assertEquals(0.17, p.bufferedSeconds(d.agentUnplayed()), 1e-9)
        now += 170
        assertEquals(0.0, p.bufferedSeconds(d.agentUnplayed()), 0.0)
        now += 5_000
        assertEquals("and stays there", 0.0, p.bufferedSeconds(d.agentUnplayed()), 0.0)
    }

    @Test fun silenceOnTheDeviceIsNotTheAgentStillTalking() {
        val l = DeviceLedger()
        l.wroteSilence(480)
        l.wroteAgent(2400)
        l.wroteAgent(2400)   // contiguous: one range
        l.wroteSilence(480)
        l.wroteAgent(1000)
        assertEquals(5800, l.agentUnplayed(0))
        assertEquals(5800 - 20, l.agentUnplayed(500))
        assertEquals(1000, l.agentUnplayed(5280))
        assertEquals(1000, l.agentUnplayed(5760))
        assertEquals(0, l.agentUnplayed(6760))
        assertEquals(6760L, l.written)
        assertEquals(0L, l.depth(6760))
        l.flush()
        assertEquals(0, l.agentUnplayed(0))
        assertEquals(0L, l.written)
    }

    @Test fun clearDropsEverythingAtOnce() {
        val p = PlayoutBuffer(rate) { now }
        p.push(ms(500), 0)
        assertTrue(p.running)
        p.clear()
        assertEquals(0.0, p.bufferedSeconds(0), 0.0)
        assertFalse(p.running)
        assertNull(p.take(0))
        p.push(ms(100), 0)
        assertNull("after a clear the next run refills too", p.take(0))
    }

    @Test fun aRunThatPlayedOutRefillsBeforeResuming() {
        val p = PlayoutBuffer(rate) { now }
        val d = Device()
        p.push(ms(200), 0)
        while (true) d.write(p.take(d.agentUnplayed()) ?: break)
        now += 300   // the device plays it all and runs dry
        assertEquals(0, d.agentUnplayed())
        p.push(ms(50), d.agentUnplayed())
        assertNull("one late fragment must not play alone mid-turn", p.take(d.agentUnplayed()))
        p.push(ms(50), d.agentUnplayed())
        p.push(ms(50), d.agentUnplayed())
        assertNotNull("150 ms again: resume", p.take(d.agentUnplayed()))
    }

    @Test fun audioArrivingWhileTheDeviceStillPlaysContinuesTheRun() {
        val p = PlayoutBuffer(rate) { now }
        val d = Device()
        p.push(ms(200), 0)
        while (true) d.write(p.take(d.agentUnplayed()) ?: break)
        now += 100   // half played; the queue is empty but the speaker is not
        assertNull(p.take(d.agentUnplayed()))
        assertTrue(p.running)
        p.push(ms(20), d.agentUnplayed())
        assertEquals("no gap, no refill", 480, p.take(d.agentUnplayed())?.size)
    }

    @Test fun aChunkTheDeviceTookOnlyPartOfPlaysNext() {
        val p = PlayoutBuffer(rate) { now }
        p.push(ms(200), 0)
        val c = p.take(0)!!
        p.putBack(c.copyOfRange(1000, c.size))
        assertEquals(c.size - 1000, p.queuedFrames)
        assertEquals(c.size - 1000, p.take(0)?.size)
    }

    @Test fun marksAreAckedWhenTheAudioBeforeThemHasBeenHeard() {
        assertEquals(0L, CallFrames.markAckDelayMs(0.0))
        assertEquals(0L, CallFrames.markAckDelayMs(-1.0))
        assertEquals(0L, CallFrames.markAckDelayMs(Double.NaN))
        assertEquals(400L, CallFrames.markAckDelayMs(0.4))
        assertEquals("never early: rounded up", 1L, CallFrames.markAckDelayMs(0.0001))
        assertEquals("capped, so a runaway buffer cannot hold an ack for the whole call", 10_000L, CallFrames.markAckDelayMs(42.0))
    }

    @Test fun resamplingIsContinuousAcrossChunks() {
        val r = LinearResampler(24_000)
        val ramp = ShortArray(160) { (it * 100).toShort() }   // 20 ms at 8 kHz
        val a = r.process(ramp.copyOfRange(0, 80), 8_000)
        val b = r.process(ramp.copyOfRange(80, 160), 8_000)
        val out = a + b
        // Three output samples per input sample, on the same straight line, through the chunk boundary.
        for (i in 1 until out.size) assertEquals("sample $i", 100.0 / 3, (out[i] - out[i - 1]).toDouble(), 1.0)
        assertEquals("160 samples at 8 kHz are 478 at 24 kHz", 478.0, out.size.toDouble(), 1.0)
        assertArrayEquals("the call's own rate passes through untouched", ramp, r.process(ramp, 24_000))
        val down = LinearResampler(8_000).process(ShortArray(480) { 7 }, 24_000)
        assertEquals(160, down.size)
    }

    @Test fun base64IsStandardAndForgiving() {
        assertEquals("", B64.encode(ByteArray(0)))
        assertEquals("Zg==", B64.encode("f".toByteArray()))
        assertEquals("Zm8=", B64.encode("fo".toByteArray()))
        assertEquals("Zm9v", B64.encode("foo".toByteArray()))
        assertEquals("+/8=", B64.encode(byteArrayOf(0xfb.toByte(), 0xff.toByte())))
        assertArrayEquals("foobar".toByteArray(), B64.decode("Zm9v\nYmFy"))
        assertArrayEquals("fo".toByteArray(), B64.decode("Zm8="))
        val all = ByteArray(1000) { (it * 31).toByte() }
        assertArrayEquals(all, B64.decode(B64.encode(all)))
    }

    @Test fun pcmGoesOnTheWireLittleEndian() {
        val bytes = AgentAudio.toLittleEndian(shortArrayOf(1, -2, 0x1234))
        assertArrayEquals(byteArrayOf(1, 0, 0xfe.toByte(), 0xff.toByte(), 0x34, 0x12), bytes)
        assertArrayEquals(shortArrayOf(1, -2, 0x1234), AgentAudio.decodePcm16(bytes))
        assertTrue(AgentAudio.isAllZero(ByteArray(10)))
        assertFalse(AgentAudio.isAllZero(byteArrayOf(0, 0, 1)))
    }
}
