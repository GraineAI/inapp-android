package ai.graine.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HOW A CALL CONNECTED, AS AN APP READS IT (GraineCalls.connection) — the
 * analytics rows folded into one report, in Execution Logs' words: the tap's
 * wait as the Connection card splits it (setup, login, answer, first words),
 * the audio with its verdict, every recovery counted. The values are the rows'
 * own, cleaned and rounded as they are sent. Pure.
 */
class CallConnectionTest {

    private fun report(vararg rows: Pair<String, Map<String, Any?>>) =
        CallConnectionReport().apply { for ((name, data) in rows) fold(name, data) }.value

    @Test fun aColdCallsLoginIsSplitOutOfItsSetup() {
        val c = report(
            CallAnalytics.TRANSPORT to mapOf("transport" to "rtc", "requested" to "auto"),
            CallAnalytics.CONNECT to mapOf("mode" to "cold", "warm" to false, "total_ms" to 900, "setup_ms" to 500.4, "dial_ms" to 400, "register_ms" to 120, "mint_route" to "direct"),
        )!!
        assertEquals("webrtc", c.transport)
        assertEquals(false, c.prepared)
        assertNull("only a prepared call has an age", c.preparedAgeSeconds)
        assertEquals("direct", c.credential)
        assertEquals("the setup without the login inside it", 380L, c.setupMs)
        assertEquals(120L, c.loginMs)
        assertEquals(400L, c.answerMs)
        // A login longer than the setup it sits in is held to it.
        val held = report(CallAnalytics.CONNECT to mapOf("mode" to "cold", "setup_ms" to 100, "register_ms" to 250))!!
        assertEquals(0L, held.setupMs)
        assertEquals(100L, held.loginMs)
        // No setup measured: the login alone.
        assertEquals(250L, report(CallAnalytics.CONNECT to mapOf("mode" to "cold", "register_ms" to 250))!!.loginMs)
    }

    @Test fun aPreparedCallLoggedInBeforeTheTapAndSaysItsAge() {
        val c = report(CallAnalytics.CONNECT to mapOf("mode" to "prepared", "warm" to true, "setup_ms" to 300, "dial_ms" to 0, "register_ms" to 90, "prepared_age_s" to 4.6))!!
        assertEquals(true, c.prepared)
        assertEquals("rounded as the row rounds it", 5L, c.preparedAgeSeconds)
        assertEquals(300L, c.setupMs)
        assertNull("its login was not part of the wait", c.loginMs)
        assertEquals(0L, c.answerMs)
        assertEquals("a row older than `mode` says it with `warm`", true, report(CallAnalytics.CONNECT to mapOf("warm" to true))!!.prepared)
    }

    @Test fun theFirstWordsTheRecoveriesAndTheTransportOfAFallback() {
        val c = report(
            CallAnalytics.TRANSPORT to mapOf("transport" to "ws", "requested" to "auto", "fallback_reason" to "no_answer"),
            CallAnalytics.VOICE to mapOf("tap_to_voice_ms" to 1302, "answer_to_voice_ms" to 880, "first_audio_ms" to 700),
            CallAnalytics.RECOVERY to mapOf("what" to "redial", "after_ms" to 1200),
            CallAnalytics.RECOVERY to mapOf("what" to "ice_restart", "after_ms" to 900),
        )!!
        assertEquals("websocket", c.transport)
        assertEquals(1302L, c.tapToVoiceMs)
        assertEquals(880L, c.firstWordsMs)
        assertEquals(2, c.recoveries)
        assertNull(c.audio)
    }

    @Test fun theAudioCarriesExecutionLogsVerdict() {
        fun audio(loss: Double?, jitter: Double?, rtt: Double?) =
            report(CallAnalytics.QUALITY to mapOf("duration_s" to 64, "loss_pct" to loss, "jitter_avg_ms" to jitter, "jitter_max_ms" to jitter?.times(2), "rtt_avg_ms" to rtt))?.audio
        assertEquals(GraineCallConnection.Audio(0.28, 12, 24, 95, GraineCallConnection.GOOD), audio(0.284, 12.0, 95.0))
        assertEquals(GraineCallConnection.FAIR, audio(2.0, 10.0, 50.0)!!.quality)
        assertEquals(GraineCallConnection.FAIR, audio(0.0, 30.0, 50.0)!!.quality)
        assertEquals(GraineCallConnection.FAIR, audio(0.0, 10.0, 150.0)!!.quality)
        assertEquals(GraineCallConnection.POOR, audio(8.0, 10.0, 50.0)!!.quality)
        assertEquals(GraineCallConnection.POOR, audio(0.0, 80.0, 50.0)!!.quality)
        assertEquals(GraineCallConnection.POOR, audio(0.0, 10.0, 400.0)!!.quality)
        assertEquals("one figure is enough", GraineCallConnection.GOOD, audio(null, null, 40.0)!!.quality)
        assertNull("nothing measured: no audio, and no report", audio(null, null, null))
        // A reading of the audio so far is replaced by the next, and by the call's own at its end.
        val r = CallConnectionReport()
        r.fold(CallAnalytics.QUALITY, mapOf("loss_pct" to 0.0, "rtt_avg_ms" to 40))
        r.fold(CallAnalytics.QUALITY, mapOf("loss_pct" to 9.0, "rtt_avg_ms" to 40))
        assertEquals(GraineCallConnection.POOR, r.value!!.audio!!.quality)
    }

    @Test fun aRowWithNoFactForTheReportChangesNothing() {
        assertNull(report(CallAnalytics.MEDIA to mapOf("reason" to "ice_timeout", "transport" to "rtc")))
        assertNull(report("not_a_row" to mapOf("transport" to "rtc")))
        val c = report(CallAnalytics.TRANSPORT to mapOf("transport" to "rtc"), CallAnalytics.MEDIA to mapOf("reason" to "ice_failed"))
        assertEquals(GraineCallConnection(transport = "webrtc"), c)
    }

    @Test fun theValuesAreTheRowsOwnCleanedAsTheyAreSent() {
        assertEquals(
            mapOf("tap_to_voice_ms" to 1303L, "codec" to "opus/48000"),
            CallAnalytics.values(CallAnalytics.VOICE, mapOf("tap_to_voice_ms" to 1302.5, "codec" to "audio/OPUS/48000/2", "answer_to_voice_ms" to -1, "who" to "x")),
        )
        assertEquals(mapOf("loss_pct" to 0.28, "jitter_avg_ms" to 600_000L), CallAnalytics.values(CallAnalytics.QUALITY, mapOf("loss_pct" to 0.284, "jitter_avg_ms" to 9e9)))
        assertNull(CallAnalytics.values("not_a_row", emptyMap<String, Any?>()))
        assertEquals(listOf("good", "fair", "poor"), listOf(GraineCallConnection.GOOD, GraineCallConnection.FAIR, GraineCallConnection.POOR))
    }
}
