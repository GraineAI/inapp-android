package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.TimeZone

/**
 * THE GARAGE'S DESIGNS ON ANDROID (contract/DESIGN.md "Chat window", "Call
 * screen"): every design in design.json — resolved by theme.ts itself — has
 * chat and call tokens this SDK reads whole, draws in every one of the six
 * call moments, and rings with; and the ring patterns are the file's.
 */
class GarageDesignsTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/design.json").exists() && !File(dir, "contract/design.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/design.json"), File(dir, "contract/design.json")).first { it.exists() }
        JSONObject(f.readText())
    }

    private fun designs(): List<ResolvedDesign> {
        val cases = fx.getJSONArray("cases")
        return (0 until cases.length()).map { ResolvedDesign.parse(cases.getJSONObject(it).getJSONObject("design"))!! } +
            ResolvedDesign.parse(fx.getJSONObject("legacy").getJSONObject("design"))!!
    }

    private fun tokens(): List<ThemeTokens> = designs().flatMap { listOf(it.light, it.dark) }

    @Test fun everyDesignCarriesBothSurfaces() {
        assertTrue("the file has the Garage's designs", fx.getJSONArray("cases").length() >= 30)
        for (t in tokens()) {
            assertNotNull("${t.preset} ${t.scheme} chat", t.chat)
            val call = assertNotNullCall(t)
            assertTrue("${t.preset}: every call screen can be hung up", "end" in call.controls)
            assertTrue(call.statusText.scheduled != null && call.statusText.missed != null)
            // Every word the Garage resolves is one this build draws.
            assertTrue(call.avatar in Surfaces.CALL_AVATARS && call.avatarShape in Surfaces.AVATAR_SHAPES)
            assertTrue(call.endShape in Surfaces.END_SHAPES && call.ringtone in Surfaces.RINGTONES)
            assertTrue(t.chat!!.avatar in Surfaces.CHAT_AVATARS && t.chat!!.pattern in Surfaces.PATTERNS)
        }
    }

    private fun assertNotNullCall(t: ThemeTokens): CallTokens {
        assertNotNull("${t.preset} ${t.scheme} call", t.call)
        return t.call!!
    }

    @Test fun theFileMeetsEveryShapeTheScreensMustDraw() {
        val calls = tokens().map { it.call!! }
        val chats = tokens().map { it.chat!! }
        assertTrue(calls.map { it.avatar }.toSet().containsAll(Surfaces.CALL_AVATARS))
        assertTrue(calls.map { it.avatarShape }.toSet().containsAll(Surfaces.AVATAR_SHAPES))
        assertTrue(calls.map { it.endShape }.toSet().containsAll(Surfaces.END_SHAPES))
        assertTrue(calls.map { it.ringtone }.toSet().containsAll(Surfaces.RINGTONES))
        assertTrue("a gradient", calls.any { it.backgroundTo != null })
        assertTrue(chats.map { it.avatar }.toSet().containsAll(Surfaces.CHAT_AVATARS))
        assertTrue("dots", chats.any { it.pattern == "dots" })
        assertTrue("starters", chats.any { it.suggestions.isNotEmpty() })
        assertTrue("an accent header", tokens().any { Design.normaliseHex(it.chat!!.headerBackground) != Design.normaliseHex(it.colors.surface) })
    }

    @Test fun graineClassicIsTheColdStartCallScreen() {
        val first = ResolvedDesign.parse(fx.getJSONArray("cases").getJSONObject(0).getJSONObject("design"))!!
        assertEquals("before any session, the first case's light.call", Surfaces.CLASSIC_CALL, first.light.call)
        assertEquals("and with no `call` from an older server", first.light.call, Surfaces.callOf(first.light.copy(call = null)))
    }

    @Test fun everyMomentHasWordsInEveryDesign() {
        for (t in tokens()) {
            val call = t.call!!
            for (m in CallScreen.Moment.values()) {
                val words = CallScreen.status(m, call, 65_000, muted = false)
                assertTrue("${t.preset} ${t.scheme} $m", m == CallScreen.Moment.ON_CALL && !call.timer || words.isNotBlank())
            }
            // The missed screen always has its Call back colours; the ring always has both buttons'.
            for (hex in listOf(call.callbackFill, call.callbackIcon, call.acceptFill, call.declineFill, call.endFill)) {
                assertNotNull(Appearance.argb(hex))
            }
        }
    }

    @Test fun theRingPatternsAreTheFiles() {
        val want = fx.getJSONObject("ringtones")
        assertEquals(want.keys().asSequence().toSet(), Ringtones.PATTERNS.keys)
        for ((name, p) in Ringtones.PATTERNS) {
            val w = want.getJSONObject(name)
            assertEquals(w.getString("wave"), p.wave)
            assertEquals(w.getDouble("gain"), p.gain, 0.0)
            assertEquals(w.getDouble("pauseMs"), p.pauseMs, 0.0)
            val notes: JSONArray = w.getJSONArray("notes")
            assertEquals(notes.length(), p.notes.size)
            for (i in 0 until notes.length()) {
                assertEquals(notes.getJSONObject(i).getDouble("hz"), p.notes[i].hz, 0.0)
                assertEquals(notes.getJSONObject(i).getDouble("ms"), p.notes[i].ms, 0.0)
            }
        }
    }

    @Test fun aRingIsSynthesisedAsTheContractSays() {
        val p = Ringtones.PATTERNS.getValue("graine")
        val samples = Ringtones.samples(p)
        val rate = Ringtones.SAMPLE_RATE
        fun count(ms: Double) = Math.floor(ms / 1000 * rate + 0.5).toInt()
        assertEquals(p.notes.sumOf { count(it.ms) } + count(p.pauseMs), samples.size)
        // Each note rises from nothing and falls back to nothing: no click at either end.
        assertEquals(0, samples[0].toInt())
        val first = count(p.notes[0].ms)
        assertEquals(0, samples[first - 1].toInt())
        // Never louder than its gain.
        val peak = samples.maxOf { Math.abs(it.toInt()) }
        assertTrue(peak <= Math.round(p.gain * 32_767))
        assertTrue("it is not silence", peak > p.gain * 32_767 * 0.9)
        // The rest and the pause are silent.
        val restStart = count(p.notes[0].ms) + count(p.notes[1].ms)
        assertTrue((restStart until restStart + count(p.notes[2].ms)).all { samples[it].toInt() == 0 })
        assertTrue((samples.size - count(p.pauseMs) until samples.size).all { samples[it].toInt() == 0 })
        val soft = Ringtones.samples(Ringtones.PATTERNS.getValue("soft"))
        assertTrue(soft.maxOf { Math.abs(it.toInt()) } <= Math.round(0.35 * 32_767))
        assertEquals(null, Ringtones.pattern("system"))
        assertEquals(null, Ringtones.pattern("opera"))
    }

    // ── the Upcoming calls card ────────────────────────────────────────────

    private fun call(id: String, atMs: Long?, status: String = "scheduled") = GraineScheduledCall(id, atMs?.let { Date(it) }, GraineCallStatus.from(status))

    @Test fun theCardListsTheNextThreeStillToRing() {
        val shown = UpcomingCalls.shown(listOf(
            call("late", 5_000), call("done", 1_000, "completed"), call("soon", 2_000), call("untimed", null),
            call("third", 3_000), call("fourth", 4_000), call("now", 1_500, "ringing"),
        ))
        assertEquals("upcoming (scheduled or ringing), timed, soonest first, three", listOf("now", "soon", "third"), shown.map { it.id })
        assertEquals("Upcoming call", UpcomingCalls.title(1))
        assertEquals("Upcoming calls", UpcomingCalls.title(3))
    }

    @Test fun aTimeReadsTodayTomorrowOrItsDayInTheUsersZone() {
        val zone = TimeZone.getTimeZone("Asia/Kolkata")
        val now = Date(Scheduling.parseIso("2026-10-13T20:00:00+05:30")!!)
        fun at(iso: String) = Date(Scheduling.parseIso(iso)!!)
        val clock = { d: Date -> java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).apply { timeZone = zone }.format(d) }
        val day = { d: Date -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.US).apply { timeZone = zone }.format(d) }
        assertEquals("Today, 11:30 PM", UpcomingCalls.label(at("2026-10-13T23:30:00+05:30"), now, zone, clock, day))
        assertEquals("Tomorrow, 9:00 AM", UpcomingCalls.label(at("2026-10-14T09:00:00+05:30"), now, zone, clock, day))
        assertEquals("Thu 15 Oct, 10:00 AM", UpcomingCalls.label(at("2026-10-15T10:00:00+05:30"), now, zone, clock, day))
        // The same instant is "today" in one zone and "tomorrow" in another: the user's own decides.
        val utcNow = Date(Scheduling.parseIso("2026-10-13T20:00:00Z")!!)
        assertEquals(1, UpcomingCalls.daysFrom(utcNow, at("2026-10-14T01:00:00Z"), TimeZone.getTimeZone("UTC")))
        assertEquals(0, UpcomingCalls.daysFrom(utcNow, at("2026-10-14T01:00:00Z"), TimeZone.getTimeZone("America/Los_Angeles")))
        assertFalse(UpcomingCalls.CANCELLING.isEmpty() || UpcomingCalls.CANCEL_FAILED.isEmpty())
    }
}
