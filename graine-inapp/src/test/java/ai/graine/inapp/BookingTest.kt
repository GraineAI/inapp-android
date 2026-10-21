package ai.graine.inapp

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

/**
 * BOOKING A CALL FROM THE APP (BOOKING-SHEET.md §5): `bookingAvailable` for
 * each of its rules and per agent, on real agents with the main thread, the
 * network and the device's storage faked; what the sheet offers (today to the
 * furthest day, 5-minute steps between earliest and latest); each refusal as
 * its line; a booking that succeeds handing back the call; and the chat
 * window's "Book a call" only where booking is available and the design keeps
 * the booked-calls card. The dialog itself is platform views; its state is here.
 */
class BookingTest {
    private class TestApp : Application() {
        override fun getPackageName(): String = "com.acme.app"
    }

    /** The main thread on the test's clock: posts run when the test lets time pass. */
    private class TestMain(private val clock: ManualScheduler) : MainThread {
        private val pending = HashMap<Runnable, MutableList<() -> Unit>>()
        override fun post(r: Runnable): Boolean = postDelayed(r, 0)
        override fun postDelayed(r: Runnable, delayMs: Long): Boolean {
            val mine = pending.getOrPut(r) { ArrayList() }
            lateinit var cancel: () -> Unit
            cancel = clock.schedule(delayMs) { mine.remove(cancel); r.run() }
            mine += cancel
            return true
        }
        override fun removeCallbacks(r: Runnable) { pending.remove(r)?.forEach { it() } }
    }

    private val clock = ManualScheduler()
    private val main = TestMain(clock)
    private val app = TestApp()
    private val dir = java.nio.file.Files.createTempDirectory("graine-booking").toFile()
    private val device = DeviceStore(File(dir, "device.json"))
    private val kept = HashMap<String, String>()
    private val cache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text })
    private val sessions = HashMap<String, JSONObject>()
    private val requests = mutableListOf<Pair<String, JSONObject>>()

    /** What the schedule route answers; by default it books the time asked for. */
    private var scheduleReply: (JSONObject) -> HttpReply = { body ->
        HttpReply(201, JSONObject().put("call", JSONObject().put("id", "sc_1").put("at", body.getString("at"))
            .put("status", "scheduled").put("source", "sdk").put("reason", body.opt("reason") ?: JSONObject.NULL)).toString())
    }

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun env() = AgentEnv(
        main = main,
        io = Executor { it.run() },
        http = HttpPost { url, json ->
            val body = JSONObject(json)
            requests += url to body
            when {
                url.endsWith(EmbedApi.SCHEDULE) -> scheduleReply(body)
                url.endsWith(EmbedApi.LIST) -> HttpReply(200, """{"calls":[]}""")
                else -> HttpReply(200, """{"ok":true}""")
            }
        },
        fetchSession = { _, key -> sessions[key] ?: throw IllegalStateException("offline") },
        scheduler = clock,
        ticketIo = { it() },
        device = device,
        sessionCache = cache,
        fonts = false,
    )

    private fun settle() = clock.advance(0)

    private fun session(calls: String?, identityField: String = "user_id") = JSONObject(
        """{"agentId":"agent-1","webcallAgentId":"web-1","identity":{"field":"$identityField"}""" +
            (calls?.let { ""","calls":$it""" } ?: "") + "}",
    )

    private val bookable = """{"hosted":true,"platforms":["android","ios"],"callName":"Acme Care",
        "scheduling":{"usersCanBook":true,"minLeadMinutes":5,"maxDaysAhead":14,"maxPendingPerUser":3}}"""

    /** A real agent whose session has arrived. */
    private fun agent(session: JSONObject, name: String = GraineInApp.DEFAULT, key: String = "pk_test"): GraineInApp {
        sessions[key] = session
        val g = GraineInApp.make(app, GraineConfig("https://www.graine.ai", key), name) { env() }
        settle()
        return g
    }

    // ── bookingAvailable ─────────────────────────────────────────────────────

    @Test fun bookingIsAvailableOnlyWhenEveryRuleHolds() {
        val cases = listOf(
            Triple("hosted, Android rung, users may book, identified", bookable, true),
            Triple("no platforms named: every platform", """{"hosted":true,"scheduling":{"usersCanBook":true}}""", true),
            Triple("Graine does not ring this app", """{"hosted":false,"scheduling":{"usersCanBook":true}}""", false),
            Triple("Graine rings iOS only", """{"hosted":true,"platforms":["ios"],"scheduling":{"usersCanBook":true}}""", false),
            Triple("users may not book", """{"hosted":true,"scheduling":{"usersCanBook":false}}""", false),
            Triple("calls without scheduling", """{"hosted":true}""", false),
            Triple("an older server: no calls at all", null, false),
        )
        for ((name, calls, expected) in cases) {
            AppShared.reset()
            val g = agent(session(calls))
            g.identify(mapOf("user_id" to "P-1", "name" to "Riya"))
            settle()
            assertEquals(name, expected, g.bookingAvailable)
        }
    }

    @Test fun theUserMustBeIdentifiedWithTheAgentsOwnTrait() {
        val g = agent(session(bookable, identityField = "patient_id"))
        assertFalse("nobody identified", g.bookingAvailable)
        assertEquals("Sign in to book a call", g.bookingBlocked)
        g.identify(mapOf("user_id" to "P-1"))
        assertFalse("identified, but not with the trait the agent names", g.bookingAvailable)
        g.identify(mapOf("patient_id" to "MRN-000981"))
        assertTrue(g.bookingAvailable)
        assertNull(g.bookingBlocked)
        g.identify(mapOf("patient_id" to "guest"))
        assertFalse("a placeholder is nobody", g.bookingAvailable)
        g.identify(null)
        assertFalse("signed out", g.bookingAvailable)
    }

    @Test fun beforeTheSessionNothingCanBeBooked() {
        val g = GraineInApp.make(app, GraineConfig("https://www.graine.ai", "pk_offline"), GraineInApp.DEFAULT) { env() }
        g.identify(mapOf("user_id" to "P-1"))
        settle()
        assertFalse(g.bookingAvailable)
        assertEquals("Booking a call isn't available right now", g.bookingBlocked)
    }

    @Test fun eachAgentAnswersForItself() {
        val support = agent(session("""{"hosted":true}"""), GraineInApp.DEFAULT, "pk_support")
        val sales = agent(session(bookable), "sales", "pk_sales")
        support.identify(mapOf("user_id" to "P-1"))
        settle()
        assertTrue("the user is the app's: identified on one agent, identified on both", sales.bookingAvailable)
        assertFalse("the support agent does not let users book", support.bookingAvailable)
    }

    // ── why not, as a word (0.14.0) ──────────────────────────────────────────

    @Test fun whyBookingIsNotAvailableIsTheFirstRuleThatFails() {
        val offline = GraineInApp.make(app, GraineConfig("https://www.graine.ai", "pk_offline"), GraineInApp.DEFAULT) { env() }
        settle()
        assertEquals("the settings never loaded", "not_loaded", offline.bookingUnavailableReason)
        val cases = listOf(
            "rings_not_set_up" to """{"hosted":false,"scheduling":{"usersCanBook":true}}""",
            "rings_not_set_up" to """{"hosted":true,"platforms":["ios"],"scheduling":{"usersCanBook":true}}""",
            "rings_not_set_up" to null,
            "scheduling_off" to """{"hosted":true,"scheduling":{"usersCanBook":false}}""",
            "scheduling_off" to """{"hosted":true}""",
        )
        for ((want, calls) in cases) {
            AppShared.reset()
            val g = agent(session(calls))
            g.identify(mapOf("user_id" to "P-1"))
            settle()
            assertEquals(calls ?: "an older server: no calls at all", want, g.bookingUnavailableReason)
            assertFalse(g.bookingAvailable)
        }
        AppShared.reset()
        val g = agent(session(bookable))
        assertEquals("not_identified", g.bookingUnavailableReason)
        g.identify(mapOf("user_id" to "P-1"))
        settle()
        assertNull(g.bookingUnavailableReason)
        assertTrue("the same rule", g.bookingAvailable)
    }

    // ── what the sheet offers ────────────────────────────────────────────────

    private val kolkata: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val rules = GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 5, maxDaysAhead = 14, hostedRinging = true)

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long = Calendar.getInstance(kolkata).apply {
        clear()
        set(y, mo - 1, d, h, mi, s)
    }.timeInMillis

    private val dayFormat = SimpleDateFormat("EEE d MMM", Locale.UK).apply { timeZone = kolkata }
    private val clockFormat = SimpleDateFormat("HH:mm", Locale.UK).apply { timeZone = kolkata }
    private fun date(ms: Long): String = dayFormat.format(Date(ms))
    private fun clockText(ms: Long): String = clockFormat.format(Date(ms))

    @Test fun theFirstTimeIsTheLeadRoundedUpToFiveMinutes() {
        assertEquals(at(2026, 10, 14, 18, 10), Booking.first(rules, at(2026, 10, 14, 18, 3, 20)))
        assertEquals("already on a mark: kept", at(2026, 10, 14, 18, 5), Booking.first(rules, at(2026, 10, 14, 18, 0)))
        assertEquals(at(2026, 10, 14, 18, 10), Booking.first(rules, at(2026, 10, 14, 18, 0, 1)))
        assertEquals(at(2026, 10, 14, 19, 35), Booking.first(GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 90), at(2026, 10, 14, 18, 3)))
        val now = at(2026, 10, 14, 18, 3, 20)
        assertTrue("never before earliest", Booking.first(rules, now) >= rules.earliest(Date(now)).time)
    }

    @Test fun theLastTimeIsLatestOnAFiveMinuteMark() {
        val now = at(2026, 10, 14, 18, 3, 20)
        assertEquals(at(2026, 10, 28, 18, 0), Booking.last(rules, now))
        assertTrue("never after latest", Booking.last(rules, now) <= rules.latest(Date(now)).time)
    }

    @Test fun theDaysRunFromTodayToTheFurthestWithOnlyTheTimesAllowed() {
        val days = Booking.days(rules, at(2026, 10, 14, 18, 3), kolkata, ::date)
        assertEquals("today and 14 days ahead", 15, days.size)
        assertEquals(listOf("Today", "Tomorrow", "Fri 16 Oct"), days.take(3).map { it.label })
        assertEquals(at(2026, 10, 14, 18, 10), days[0].firstMs)
        assertEquals(at(2026, 10, 14, 23, 55), days[0].lastMs)
        assertEquals(at(2026, 10, 15, 0, 0), days[1].firstMs)
        assertEquals(at(2026, 10, 15, 23, 55), days[1].lastMs)
        assertEquals(at(2026, 10, 28, 0, 0), days[14].firstMs)
        assertEquals(at(2026, 10, 28, 18, 0), days[14].lastMs)
        for (d in days) {
            assertTrue(d.firstMs <= d.lastMs)
            assertEquals("on a 5-minute mark", 0L, d.firstMs % 300_000L)
        }
    }

    @Test fun aDaysSlotsAreTheSheetsTimesThatDayEveryFiveMinutes() {
        val now = at(2026, 10, 14, 18, 3)
        val days = Booking.days(rules, now, kolkata, ::date)
        val today = Booking.slots(rules, now, kolkata, at(2026, 10, 14, 12, 0))
        assertEquals(days[0].firstMs, today.first())
        assertEquals(days[0].lastMs, today.last())
        assertTrue("every five minutes", today.zipWithNext().all { (a, b) -> b - a == 300_000L })
        assertEquals((days[0].lastMs - days[0].firstMs) / 300_000L + 1, today.size.toLong())
        val furthest = Booking.slots(rules, now, kolkata, at(2026, 10, 28, 23, 59))
        assertEquals(at(2026, 10, 28, 0, 0), furthest.first())
        assertEquals(at(2026, 10, 28, 18, 0), furthest.last())
        assertTrue("past the furthest day", Booking.slots(rules, now, kolkata, at(2026, 10, 29, 9, 0)).isEmpty())
        assertTrue("before today", Booking.slots(rules, now, kolkata, at(2026, 10, 13, 9, 0)).isEmpty())
        assertEquals("any time of the day asks for that day", Booking.slots(rules, now, kolkata, at(2026, 10, 15, 0, 0)),
            Booking.slots(rules, now, kolkata, at(2026, 10, 15, 23, 59)))
        assertTrue("no booking, no days: nothing", Booking.slots(GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 2, maxDaysAhead = 0), now, kolkata, now).isEmpty())
    }

    @Test fun anAgentsSlotsAreForTheDayAskedInThePhonesZone() {
        val g = agent(session(bookable))
        val tomorrow = g.bookingSlots(Date(System.currentTimeMillis() + 86_400_000L))
        assertTrue("a whole day of times", tomorrow.size > 200)
        assertTrue("on five-minute marks", tomorrow.all { it.time % 300_000L == 0L })
        assertTrue("never sooner than the lead", tomorrow.first().time >= g.schedulingRules.earliest().time)
        assertTrue("past the furthest day: none", g.bookingSlots(Date(System.currentTimeMillis() + 20 * 86_400_000L)).isEmpty())
    }

    @Test fun aDayWithNoTimeLeftIsNotOffered() {
        val days = Booking.days(rules, at(2026, 10, 14, 23, 58), kolkata, ::date)
        assertEquals("the lead runs past midnight: the first day offered is tomorrow", "Tomorrow", days.first().label)
        assertEquals(at(2026, 10, 15, 0, 5), days.first().firstMs)
        val none = Booking.days(GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 2, maxDaysAhead = 0), at(2026, 10, 14, 18, 3), kolkata, ::date)
        assertTrue("a window narrower than five minutes offers nothing", none.isEmpty())
    }

    @Test fun pickingADayKeepsTheTimeWhereItCanAndClampsWhereItCannot() {
        val flow = BookingFlow(rules, at(2026, 10, 14, 18, 3), null, kolkata, ::date)
        assertEquals("opens on the first time offered", at(2026, 10, 14, 18, 10), flow.atMs)
        flow.pickDay(1)
        assertEquals("tomorrow, the same time", at(2026, 10, 15, 18, 10), flow.atMs)
        flow.pickTime(7, 30)
        assertEquals(at(2026, 10, 15, 7, 30), flow.atMs)
        flow.pickDay(0)
        assertEquals("7:30 has passed today: the first time today has", at(2026, 10, 14, 18, 10), flow.atMs)
        flow.pickTime(6, 0)
        assertEquals("the picker cannot go before the first time", at(2026, 10, 14, 18, 10), flow.atMs)
        flow.pickDay(14)
        flow.pickTime(21, 0)
        assertEquals("after latest: the last time offered", at(2026, 10, 28, 18, 0), flow.atMs)
        flow.pickDay(99)
        assertEquals("no such day: nothing changes", 14, flow.dayIndex)
        assertEquals(18 to 0, Booking.clockOf(flow.atMs, kolkata))
    }

    @Test fun theSheetsWordsAreTheSpecs() {
        val six = at(2026, 10, 14, 18, 0)
        assertEquals("Book a call", Booking.TITLE)
        assertEquals("Acme Care will call you", Booking.subtitle("Acme Care"))
        assertEquals("What's it about? (optional)", Booking.REASON_LABEL)
        assertEquals("Book call", Booking.BOOK)
        assertEquals("Book a call", Booking.CHAT_ROW)
        assertEquals("Asia/Kolkata · Wed 14 Oct, 18:00", Booking.zoneLine(six, kolkata, ::date, ::clockText))
        assertEquals("Booked — Acme Care will call you on Wed 14 Oct, 18:00", Booking.bookedLine("Acme Care", six, ::date, ::clockText))
        assertEquals("Acme Care", Booking.callName("Acme Care", "Aria", "Help"))
        assertEquals("no call name: the chat's title", "Aria", Booking.callName("  ", "Aria", "Help"))
        assertEquals("Help", Booking.callName(null, "", "Help"))
        assertEquals("Assistant", Booking.callName(null, null, null))
    }

    @Test fun theReasonIsOneLineOfAtMost120Characters() {
        val flow = BookingFlow(rules, System.currentTimeMillis(), "Finish my\nKYC", kolkata, ::date)
        assertEquals("Finish my KYC", flow.reason)
        flow.setReason("a".repeat(300))
        assertEquals(120, flow.reason.length)
        // Never half a character: an emoji across the limit is left out whole.
        assertEquals(119, Booking.clipReason("a".repeat(119) + "😀").length)
        flow.setReason("   ")
        val blank = flow.begin()
        assertNotNull(blank)
        assertNull("a blank reason is no reason", blank!!.second)
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test fun eachRefusalIsItsLine() {
        val r = GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 15, maxDaysAhead = 7, maxPendingPerUser = 3)
        val lines = listOf(
            GraineErrorCode.TOO_SOON to "Pick a time at least 15 minutes from now",
            GraineErrorCode.TOO_FAR to "Pick a time within 7 days",
            GraineErrorCode.OUTSIDE_WINDOW to "That time is outside the hours we can call — pick another",
            GraineErrorCode.TOO_MANY_PENDING to "You already have 3 calls booked — cancel one first",
            GraineErrorCode.SCHEDULING_OFF to "Booking a call isn't available right now",
            GraineErrorCode.IDENTITY_REQUIRED to "Sign in to book a call",
            GraineErrorCode.RATE_LIMITED to "Couldn't book just now — try again",
            GraineErrorCode.UNAVAILABLE to "Couldn't book just now — try again",
            GraineErrorCode.NETWORK to "Couldn't book just now — try again",
            GraineErrorCode.Unknown("server_error") to "Couldn't book just now — try again",
        )
        for ((code, line) in lines) assertEquals(code.wire, line, Booking.errorLine(code, r))
        val one = GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 1, maxDaysAhead = 1, maxPendingPerUser = 1)
        assertEquals("Pick a time at least 1 minute from now", Booking.errorLine(GraineErrorCode.TOO_SOON, one))
        assertEquals("Pick a time within 1 day", Booking.errorLine(GraineErrorCode.TOO_FAR, one))
        assertEquals("You already have 1 call booked — cancel one first", Booking.errorLine(GraineErrorCode.TOO_MANY_PENDING, one))
    }

    @Test fun aRefusalStaysInTheSheetAndTooSoonMovesTheOfferOn() {
        val flow = BookingFlow(rules, at(2026, 10, 14, 18, 3), null, kolkata, ::date)
        assertNotNull(flow.begin())
        assertTrue(flow.isBooking)
        assertNull("one booking at a time", flow.begin())
        flow.pickDay(1)
        assertEquals("nothing moves while it books", 0, flow.dayIndex)
        flow.finish(null, GraineError(GraineErrorCode.OUTSIDE_WINDOW, "x"), at(2026, 10, 14, 18, 4))
        assertEquals(BookingFlow.Phase.PICKING, flow.phase)
        assertEquals("That time is outside the hours we can call — pick another", flow.errorLine)
        flow.pickTime(19, 0)
        assertNull("a new pick clears the line", flow.errorLine)
        flow.pickTime(18, 10)
        assertNotNull(flow.begin())
        // Twenty minutes later, 18:10 is too soon: the line says so and the offer starts at what now allows.
        flow.finish(null, GraineError(GraineErrorCode.TOO_SOON, "x"), at(2026, 10, 14, 18, 23))
        assertEquals("Pick a time at least 5 minutes from now", flow.errorLine)
        assertEquals(at(2026, 10, 14, 18, 30), flow.atMs)
        assertEquals(at(2026, 10, 14, 18, 30), flow.days.first().firstMs)
    }

    // ── a booking, end to end on a real agent ────────────────────────────────

    private fun bookOn(g: GraineInApp, flow: BookingFlow) {
        val (atMs, why) = flow.begin() ?: return
        var answered = false
        g.scheduleCall(Date(atMs), why) { call, error -> flow.finish(call, error, System.currentTimeMillis()); answered = true }
        settle()
        assertTrue("the booking was answered", answered)
    }

    private fun readyAgent(): GraineInApp = agent(session(bookable)).also { it.identify(mapOf("user_id" to "P-1")); settle() }

    @Test fun aBookingThatSucceedsHandsBackTheCall() {
        val g = readyAgent()
        val flow = BookingFlow(g.schedulingRules, System.currentTimeMillis(), "Help with my KYC", TimeZone.getDefault()) { date(it) }
        flow.pickDay(1)
        val wanted = flow.atMs
        bookOn(g, flow)
        val call = flow.booked
        assertNotNull(call)
        assertEquals("sc_1", call!!.id)
        assertEquals(wanted, call.at?.time)
        assertEquals("Help with my KYC", call.reason)
        assertEquals(BookingFlow.Phase.BOOKED, flow.phase)
        assertNull(flow.errorLine)
        val body = requests.last { it.first.endsWith(EmbedApi.SCHEDULE) }.second
        assertEquals(Scheduling.iso(wanted), body.getString("at"))
        assertEquals("Help with my KYC", body.getString("reason"))
        assertEquals("P-1", body.getJSONObject("identity").getString("value"))
        assertNull("booked: nothing more to book in this sheet", flow.begin())
    }

    @Test fun eachRefusalFromGraineIsItsLineInTheSheet() {
        val refusals = listOf(
            Triple(422, "outside_window", "That time is outside the hours we can call — pick another"),
            Triple(409, "too_many_pending", "You already have 3 calls booked — cancel one first"),
            Triple(400, "identity_required", "Sign in to book a call"),
            Triple(403, "scheduling_off", "Booking a call isn't available right now"),
            Triple(429, "rate_limited", "Couldn't book just now — try again"),
            Triple(503, "unavailable", "Couldn't book just now — try again"),
        )
        val g = readyAgent()
        for ((status, code, line) in refusals) {
            scheduleReply = { HttpReply(status, """{"error":"Refused.","code":"$code"}""") }
            val flow = BookingFlow(g.schedulingRules, System.currentTimeMillis(), null, TimeZone.getDefault()) { date(it) }
            flow.pickDay(1)
            bookOn(g, flow)
            assertNull(code, flow.booked)
            assertEquals("$code: the user can pick again", BookingFlow.Phase.PICKING, flow.phase)
            assertEquals(code, line, flow.errorLine)
        }
        // No answer at all.
        scheduleReply = { throw java.io.IOException("offline") }
        val flow = BookingFlow(g.schedulingRules, System.currentTimeMillis(), null, TimeZone.getDefault()) { date(it) }
        flow.pickDay(1)
        bookOn(g, flow)
        assertEquals("Couldn't book just now — try again", flow.errorLine)
    }

    @Test fun aTimeThatHasPassedIsRefusedOnTheDeviceAndTheOfferMovesOn() {
        val g = readyAgent()
        val opened = System.currentTimeMillis() - 60 * 60_000L
        // The sheet was opened an hour ago; its first time has since passed.
        val flow = BookingFlow(g.schedulingRules, opened, null, TimeZone.getDefault()) { date(it) }
        val before = requests.size
        bookOn(g, flow)
        assertEquals("refused before anything was sent", before, requests.size)
        assertEquals("Pick a time at least 5 minutes from now", flow.errorLine)
        assertTrue("the offer starts at what now allows", flow.atMs >= Booking.first(g.schedulingRules, System.currentTimeMillis() - 1_000))
    }

    // ── the chat window's row ────────────────────────────────────────────────

    @Test fun theRowShowsOnlyWhereBookingIsAvailableAndTheCardIsKept() {
        val colors = Design.CLASSIC_LIGHT.colors
        val kept = Surfaces.chat(JSONObject(), colors)!!
        val off = Surfaces.chat(JSONObject().put("showUpcomingCalls", false), colors)!!
        val alone = ChatPieces.of(emptyList(), kept, 0, bookable = true)
        assertTrue("no calls booked: the compact row stands where the card would be", alone.book && !alone.upcoming)
        val inCard = ChatPieces.of(emptyList(), kept, 2, bookable = true)
        assertTrue("calls booked: in the card, under them", inCard.book && inCard.upcoming)
        assertFalse("booking not available: no row", ChatPieces.of(emptyList(), kept, 2, bookable = false).book)
        assertFalse("the default is no row", ChatPieces.of(emptyList(), kept, 0).book)
        assertFalse("the design hides the booked-calls card: no row either", ChatPieces.of(emptyList(), off, 2, bookable = true).book)
        assertTrue("an older server: the card is on, so the row is", ChatPieces.of(emptyList(), null, 0, bookable = true).book)
    }
}
