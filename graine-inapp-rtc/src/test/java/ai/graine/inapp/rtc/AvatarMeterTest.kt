package ai.graine.inapp.rtc

import ai.graine.inapp.GraineRtcInternal
import ai.graine.inapp.GraineRtcStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The agent's voice, measured before the volume that silences it while its face carries the voice. */
@OptIn(GraineRtcInternal::class)
class AvatarMeterTest {
    @Test fun theMeterReadsWhatArrivesOverAQuarterSecond() {
        val meter = LevelMeter()
        assertEquals(0.0, meter.meterLevel(), 0.0)
        assertEquals(0.0, meter.peak(), 0.0)
        val pcm = ByteBuffer.allocate(480 * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 480) pcm.putShort(if (i % 2 == 0) 8192 else -8192)
        pcm.flip()
        meter.onData(pcm, 16, 48_000, 1, 480, 0)
        assertEquals(0.25, meter.peak(), 1e-9)
        assertEquals("mean |x| 0.25 is the top of the meter scale", 1.0, meter.meterLevel(), 0.0)
        val timed = LevelMeter()
        timed.add(0.01, 0.1, now = 1_000)
        assertEquals(0.1, timed.peak(now = 1_200), 0.0)
        assertEquals("a quarter second later it is gone", 0.0, timed.peak(now = 1_300), 0.0)
        assertEquals(0.2, LevelMeter.meterScale(0.01), 1e-9)
    }

    @Test fun aSilencedTrackReportsItsLevelBeforeTheVolume() {
        val s = GraineRtcStats(1, 2, 3, 0.0, 4.0, 5.0, 0.01, 6, 0.05, "audio/PCMU", "relay", 8000)
        val w = WebRtcStats.withLevel(s, 0.4)
        assertEquals(0.4, w.audioLevel!!, 0.0)
        assertNull("the energy reads the silence: left out", w.totalAudioEnergy)
        assertNull(w.totalSamplesDuration)
        assertEquals(s.packetsReceived, w.packetsReceived)
        assertEquals(s.codec, w.codec)
        assertEquals(s.clockRate, w.clockRate)
    }
}
