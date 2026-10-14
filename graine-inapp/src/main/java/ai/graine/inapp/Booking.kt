package ai.graine.inapp

import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/*
 * BOOKING A CALL FROM THE APP — the SDK's own booking sheet, as rules and
 * words (BOOKING-SHEET.md; contract §4.2, "(added by booking-native)"): when
 * an agent can be booked ([GraineInApp.bookingAvailable]), which days and
 * times the sheet offers, what it says, and what a refusal reads as. Pure —
 * the date and clock formats are handed in — so every rule is a JVM test
 * (BookingTest); [GraineBookingDialog] draws it.
 */
internal object Booking {
    /** The sheet's times sit on these steps, and the clock rounds to them. */
    const val MINUTE_STEP = 5
    private const val STEP_MS = MINUTE_STEP * 60_000L

    // ── words: every SDK's, exactly ───────────────────────────────────────

    const val TITLE = "Book a call"
    const val DAY_LABEL = "Day"
    const val TIME_LABEL = "Time"
    const val REASON_LABEL = "What's it about? (optional)"
    const val BOOK = "Book call"
    const val DONE = "Done"
    /** The chat window's row, and the card's button. */
    const val CHAT_ROW = "Book a call"

    /** Under the title: who will ring. */
    fun subtitle(callName: String): String = "$callName will call you"

    /** The name that rings: the agent's call name (the session's `calls.callName`), else its chat title, else its heading. */
    fun callName(named: String?, title: String?, heading: String?): String =
        listOf(named, title, heading).firstNotNullOfOrNull { it?.trim()?.takeIf { w -> w.isNotEmpty() } } ?: "Assistant"

    /** Once it is booked: "Booked — Acme will call you on Thu 16 Oct, 6:00 PM". */
    fun bookedLine(callName: String, atMs: Long, date: (Long) -> String, clock: (Long) -> String): String =
        "Booked — $callName will call you on ${date(atMs)}, ${clock(atMs)}"

    /** The device's zone, and the time picked in it: "Asia/Kolkata · Tue 14 Oct, 6:00 PM". */
    fun zoneLine(atMs: Long, zone: TimeZone, date: (Long) -> String, clock: (Long) -> String): String =
        "${zone.id} · ${date(atMs)}, ${clock(atMs)}"

    /** A refusal, as the one line under the button. The counts are the agent's own rules, so the line says what would work. */
    fun errorLine(code: GraineErrorCode, rules: GraineSchedulingRules): String = when (code) {
        GraineErrorCode.TOO_SOON -> "Pick a time at least ${count(rules.minLeadMinutes, "minute")} from now"
        GraineErrorCode.TOO_FAR -> "Pick a time within ${count(rules.maxDaysAhead, "day")}"
        GraineErrorCode.OUTSIDE_WINDOW -> "That time is outside the hours we can call — pick another"
        GraineErrorCode.TOO_MANY_PENDING -> "You already have ${count(rules.maxPendingPerUser, "call")} booked — cancel one first"
        GraineErrorCode.SCHEDULING_OFF -> "Booking a call isn't available right now"
        GraineErrorCode.IDENTITY_REQUIRED -> "Sign in to book a call"
        else -> "Couldn't book just now — try again"
    }

    /** "5 minutes", "1 day": the spec's words, singular for one. */
    private fun count(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

    // ── when booking is available ─────────────────────────────────────────

    /**
     * The one rule every SDK follows (BOOKING-SHEET.md §1): Graine can ring
     * this phone (`calls.hosted`, and `calls.platforms` empty or naming
     * Android), the agent lets users book (`calls.scheduling.usersCanBook`),
     * and the user is identified with the trait the agent's Identity setting
     * names — else the booking is refused with `identity_required`.
     */
    fun available(rules: GraineSchedulingRules, identified: Boolean): Boolean = blocked(rules, identified) == null

    /** Why the sheet cannot book now, as its line; null when it can. */
    fun blocked(rules: GraineSchedulingRules, identified: Boolean): String? = when {
        !rules.ringsAndroid || !rules.usersCanBook -> errorLine(GraineErrorCode.SCHEDULING_OFF, rules)
        !identified -> errorLine(GraineErrorCode.IDENTITY_REQUIRED, rules)
        else -> null
    }

    // ── what the sheet offers ─────────────────────────────────────────────

    /** The first time offered: `earliest(now)` rounded up to the next 5 minutes. */
    fun first(rules: GraineSchedulingRules, nowMs: Long): Long = roundUp(rules.earliest(Date(nowMs)).time)

    /** The last: `latest(now)`, on a 5-minute mark at or before it. */
    fun last(rules: GraineSchedulingRules, nowMs: Long): Long = roundDown(rules.latest(Date(nowMs)).time)

    fun roundUp(ms: Long): Long = Math.floorDiv(ms + STEP_MS - 1, STEP_MS) * STEP_MS

    fun roundDown(ms: Long): Long = Math.floorDiv(ms, STEP_MS) * STEP_MS

    /** One day the sheet offers: its chip's words, and the first and last time it has. */
    data class Day(
        /** Midnight, in the device's zone. */
        val startMs: Long,
        val firstMs: Long,
        val lastMs: Long,
        /** "Today", "Tomorrow", then the weekday and date ("Thu 16 Oct"). */
        val label: String,
    )

    /**
     * Every day from the one `first(now)` falls on to the one `last(now)` falls
     * on, each holding only the times between the two — the chips. A day with
     * no time left in it is not offered. [date] writes a day's chip after
     * Today and Tomorrow.
     */
    fun days(rules: GraineSchedulingRules, nowMs: Long, zone: TimeZone, date: (Long) -> String): List<Day> {
        val from = first(rules, nowMs)
        val to = last(rules, nowMs)
        if (from > to) return emptyList()
        val out = ArrayList<Day>()
        var start = startOfDay(from, zone)
        val lastStart = startOfDay(to, zone)
        while (start <= lastStart && out.size < 400) {
            val next = nextDay(start, zone)
            if (next <= start) break
            val dayFirst = maxOf(from, start)
            val dayLast = minOf(to, roundDown(next - 1))
            if (dayFirst <= dayLast) out += Day(start, dayFirst, dayLast, dayLabel(start, nowMs, zone, date))
            start = next
        }
        return out
    }

    /** "Today", "Tomorrow", else the weekday and date, in the device's words. */
    fun dayLabel(dayMs: Long, nowMs: Long, zone: TimeZone, date: (Long) -> String): String =
        when (UpcomingCalls.daysFrom(Date(nowMs), Date(dayMs), zone)) {
            0 -> "Today"
            1 -> "Tomorrow"
            else -> date(dayMs)
        }

    /** The time [hour]:[minute] on [day] — what the time picker says — kept within the day's times. */
    fun at(day: Day, hour: Int, minute: Int, zone: TimeZone): Long = clamp(onDay(day.startMs, hour, minute, zone), day)

    /** A time kept on the clock, moved onto [day]: the day's nearest when that day does not have it. */
    fun move(atMs: Long, day: Day, zone: TimeZone): Long {
        val clock = Calendar.getInstance(zone).apply { timeInMillis = atMs }
        return at(day, clock.get(Calendar.HOUR_OF_DAY), clock.get(Calendar.MINUTE), zone)
    }

    /** Within the day's first and last time. */
    fun clamp(atMs: Long, day: Day): Long = atMs.coerceIn(day.firstMs, day.lastMs)

    /** The hour and minute of a time, in [zone]: what the time picker shows. */
    fun clockOf(atMs: Long, zone: TimeZone): Pair<Int, Int> {
        val c = Calendar.getInstance(zone).apply { timeInMillis = atMs }
        return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
    }

    /** What the field keeps: one line, at most the server's 120 characters (never half a character). */
    fun clipReason(text: String): String {
        val line = text.replace('\n', ' ').replace('\r', ' ')
        if (line.length <= Scheduling.REASON_MAX) return line
        val cut = line.take(Scheduling.REASON_MAX)
        return if (Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut
    }

    /** Midnight of the day [ms] falls on, in [zone]. */
    fun startOfDay(ms: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = ms
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextDay(startMs: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = startMs
        add(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun onDay(startMs: Long, hour: Int, minute: Int, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = startMs
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

/**
 * THE BOOKING SHEET'S STATE, apart from how it is drawn: the days and the
 * time on offer, what is picked, the reason, and what came of the booking —
 * one line under the button for a refusal, the booked call for a success.
 * The sheet hands [booked] back when it closes. Main thread.
 */
internal class BookingFlow(
    rules: GraineSchedulingRules,
    nowMs: Long,
    reason: String? = null,
    val zone: TimeZone = TimeZone.getDefault(),
    /** How a day's chip is written after Today and Tomorrow. */
    private val date: (Long) -> String,
) {
    enum class Phase { PICKING, BOOKING, BOOKED }

    var rules: GraineSchedulingRules = rules
        private set
    var days: List<Booking.Day> = Booking.days(rules, nowMs, zone, date)
        private set
    var dayIndex: Int = 0
        private set
    /** The time picked: always one the picked day offers. */
    var atMs: Long = days.firstOrNull()?.firstMs ?: Booking.first(rules, nowMs)
        private set
    var reason: String = Booking.clipReason(reason ?: "")
        private set
    var phase: Phase = Phase.PICKING
        private set
    /** The booked call, once there is one: what the sheet hands back when it closes. */
    var booked: GraineScheduledCall? = null
        private set
    /** A refusal, in the sheet's words; null when there is none to show. */
    var errorLine: String? = null
        private set

    /** The day picked; null only when the rules offer none. */
    val day: Booking.Day? get() = days.getOrNull(dayIndex)
    val isBooking: Boolean get() = phase == Phase.BOOKING

    /** A day's chip: the same time that day, or its nearest. */
    fun pickDay(index: Int) {
        if (phase != Phase.PICKING || index !in days.indices) return
        dayIndex = index
        atMs = Booking.move(atMs, days[index], zone)
        errorLine = null
    }

    /** The time picker moved to [hour]:[minute]: kept within the picked day's times. */
    fun pickTime(hour: Int, minute: Int) {
        val d = day ?: return
        if (phase != Phase.PICKING) return
        atMs = Booking.at(d, hour, minute, zone)
        errorLine = null
    }

    fun setReason(text: String) {
        reason = Booking.clipReason(text)
    }

    /** The booking to send — the time and the reason (null when blank) — or null while one is on its way, or done. */
    fun begin(): Pair<Long, String?>? {
        if (phase != Phase.PICKING || day == null) return null
        phase = Phase.BOOKING
        errorLine = null
        return atMs to reason.trim().ifEmpty { null }
    }

    /** What came of it. A refusal stays in the sheet as one line; "too soon" moves the offer on to what [nowMs] allows. */
    fun finish(call: GraineScheduledCall?, error: GraineError?, nowMs: Long) {
        if (error == null && call != null) {
            phase = Phase.BOOKED
            booked = call
            errorLine = null
            return
        }
        phase = Phase.PICKING
        val code = error?.code ?: GraineErrorCode.UNAVAILABLE
        if (code == GraineErrorCode.TOO_SOON) refresh(nowMs)
        errorLine = Booking.errorLine(code, rules)
    }

    /** The offer again from [nowMs] (time has passed, or the rules arrived): the same day and time where they still are. */
    fun refresh(nowMs: Long, next: GraineSchedulingRules? = null) {
        if (phase == Phase.BOOKING) return
        if (next != null) rules = next
        val wanted = atMs
        days = Booking.days(rules, nowMs, zone, date)
        if (days.isEmpty()) return
        val start = Booking.startOfDay(wanted, zone)
        dayIndex = days.indexOfFirst { it.startMs == start }.coerceAtLeast(0)
        atMs = Booking.move(wanted, days[dayIndex], zone)
    }
}
