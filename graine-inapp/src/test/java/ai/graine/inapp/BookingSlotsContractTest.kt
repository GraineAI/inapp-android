package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.Calendar
import java.util.TimeZone

/**
 * BOOKING SLOTS AND THE AGENT'S CALLING WINDOW (contract/PUBLIC-STATE.md §4;
 * vectors sdk/contract/booking-slots.json, written from the rule): the window
 * read from the session as every SDK reads it, and the times offered on a day
 * — by bookingSlots and by the sheet's days — in the case's own time zone, the
 * nights the clocks change included. Every case of both sections; never skipped.
 */
class BookingSlotsContractTest {
    private val contract: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/booking-slots.json").exists() && !File(dir, "contract/booking-slots.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/booking-slots.json"), File(dir, "contract/booking-slots.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/booking-slots.json missing — this contract is never skipped")
        JSONObject(f.readText())
    }

    private fun section(name: String): List<JSONObject> {
        val list = contract.getJSONArray(name)
        assertTrue("section $name is empty", list.length() > 0)
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    /** The session's `calls`, as it would arrive, with this window under `scheduling`. */
    private fun calls(window: Any?, lead: Int = 5, ahead: Int = 14): JSONObject = JSONObject().put(
        "scheduling",
        JSONObject().put("usersCanBook", true).put("minLeadMinutes", lead).put("maxDaysAhead", ahead)
            .put("callingWindow", window ?: JSONObject.NULL),
    )

    private fun expected(raw: Any?): GraineCallingWindow? = (raw as? JSONObject)?.let { w ->
        val days = w.getJSONArray("days")
        GraineCallingWindow(w.getString("start"), w.getString("end"), (0 until days.length()).map { days.getInt(it) })
    }

    private fun millis(list: JSONArray): List<Long> = (0 until list.length()).map { Instant.parse(list.getString(it)).toEpochMilli() }

    @Test fun everyWindowIsReadAsTheContractSays() {
        for (c in section("window")) {
            val got = GraineSchedulingRules.parse(calls(c.opt("wire"))).callingWindow
            assertEquals(c.getString("name"), expected(c.opt("window")), got)
        }
    }

    @Test fun noWindowInTheSessionIsNoWindow() {
        assertNull(GraineSchedulingRules.parse(JSONObject().put("scheduling", JSONObject().put("usersCanBook", true))).callingWindow)
        assertNull(GraineSchedulingRules.parse(null).callingWindow)
        assertNull(GraineSchedulingRules().callingWindow)
    }

    @Test fun everyCaseOffersTheContractsTimesOnItsDay() {
        assertEquals(5, contract.getInt("stepMinutes"))
        for (c in section("slots")) {
            val name = c.getString("name")
            val zone = TimeZone.getTimeZone(c.getString("timeZone"))
            assertEquals("$name: a zone this JVM knows", c.getString("timeZone"), zone.id)
            val rules = GraineSchedulingRules.parse(calls(c.opt("callingWindow"), c.getInt("minLeadMinutes"), c.getInt("maxDaysAhead")))
            assertEquals(name, c.getInt("minLeadMinutes"), rules.minLeadMinutes)
            assertEquals(name, c.getInt("maxDaysAhead"), rules.maxDaysAhead)
            val now = Instant.parse(c.getString("now")).toEpochMilli()
            val (y, m, d) = c.getString("day").split("-").map { it.toInt() }
            val noon = Calendar.getInstance(zone).apply { clear(); set(y, m - 1, d, 12, 0, 0) }.timeInMillis
            val want = millis(c.getJSONArray("slots"))
            assertEquals(name, want, Booking.slots(rules, now, zone, noon))
            // The sheet offers the same day, from the same first time to the same last — or not at all.
            val day = Booking.days(rules, now, zone) { "" }.firstOrNull { it.startMs == Booking.startOfDay(noon, zone) }
            if (want.isEmpty()) {
                assertNull("$name: the sheet offers no such day", day)
            } else {
                assertNotNull("$name: the sheet offers the day", day)
                assertEquals("$name: the sheet's first time", want.first(), day!!.firstMs)
                assertEquals("$name: the sheet's last time", want.last(), day.lastMs)
            }
        }
    }
}
