@file:OptIn(ai.graine.inapp.GraineRtcInternal::class)

package ai.graine.inapp.rtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

/** The stats report, read as a call needs it: the far side's audio, the path, the codec. */
class WebRtcStatsTest {
    private fun e(id: String, type: String, vararg members: Pair<String, Any?>) = WebRtcStats.Entry(id, type, mapOf(*members))

    @Test fun readsTheInboundAudioThePathAndTheCodec() {
        val s = WebRtcStats.read(listOf(
            e("IT01A", "inbound-rtp", "kind" to "audio", "bytesReceived" to BigInteger.valueOf(48_000), "packetsReceived" to 300L,
                "packetsLost" to 2, "audioLevel" to 0.21, "totalAudioEnergy" to 0.5, "totalSamplesDuration" to 6.0, "jitter" to 0.012, "codecId" to "CIT01_0"),
            e("OT01A", "outbound-rtp", "kind" to "audio", "bytesSent" to BigInteger.valueOf(51_000)),
            e("CIT01_0", "codec", "mimeType" to "audio/PCMU", "clockRate" to 8000L),
            e("T01", "transport", "selectedCandidatePairId" to "CP1"),
            e("CP1", "candidate-pair", "localCandidateId" to "IL1", "currentRoundTripTime" to 0.038, "state" to "succeeded"),
            e("IL1", "local-candidate", "candidateType" to "relay"),
        ))!!
        assertEquals(48_000, s.bytesReceived)
        assertEquals(300, s.packetsReceived)
        assertEquals(2, s.packetsLost)
        assertEquals(0.21, s.audioLevel!!, 1e-9)
        assertEquals(6.0, s.totalSamplesDuration!!, 1e-9)
        assertEquals(51_000, s.bytesSent)
        assertEquals(0.038, s.roundTripSeconds!!, 1e-9)
        assertEquals("audio/PCMU", s.codec)
        assertEquals(8000, s.clockRate)
        assertEquals("relay", s.localCandidateType)
    }

    @Test fun findsTheNominatedPairWithoutATransportAndToleratesWhatIsMissing() {
        val s = WebRtcStats.read(listOf(
            e("I", "inbound-rtp", "mediaType" to "audio"),
            e("P", "candidate-pair", "nominated" to true, "state" to "succeeded", "localCandidateId" to "L"),
            e("L", "local-candidate", "candidateType" to "srflx"),
        ))!!
        assertEquals(0, s.bytesReceived)
        assertNull(s.audioLevel)
        assertNull(s.codec)
        assertNull(s.clockRate)
        assertEquals("srflx", s.localCandidateType)
    }

    @Test fun noInboundAudioIsNoReading() {
        assertNull(WebRtcStats.read(listOf(e("V", "inbound-rtp", "kind" to "video"))))
        assertNull(WebRtcStats.read(emptyList()))
    }
}
