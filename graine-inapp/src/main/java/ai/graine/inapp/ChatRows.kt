package ai.graine.inapp

import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * WHAT A CHAT WINDOW DRAWS, in order (contract/DESIGN.md → Chat window;
 * contract §2.6, the `rows` of sdk/contract/history.json): a bubble per chat
 * turn; ONE "Voice call · 6:05 PM" row for a run of voice turns on one day —
 * that a call happened and when, never its transcript; and a day separator —
 * Today, Yesterday, a date — before the first row when it is not from today,
 * and wherever the device's day changes between rows. A conversation all from
 * today draws exactly what it always did. The React Native SDK's chatRows
 * (protocol.ts), ported. Pure: every row is a JVM test (HistoryTest).
 */
internal object ChatRows {
    sealed class Row {
        /** A day separator: `today`, `yesterday` or `date`; the local date (YYYY-MM-DD); the first message's time on it. */
        data class Day(val day: String, val date: String, val at: Date) : Row() {
            /** The contract's word: "today", "yesterday" or the local date. */
            val key: String get() = if (day == DATE) date else day
        }

        /** An earlier call: its first turn's time, and which turns (indices into the conversation) it stands for. */
        data class Voice(val id: String, val at: Date, val first: Int, val last: Int) : Row()

        data class Turn(val turn: GraineChatTurn) : Row()
    }

    const val TODAY = "today"
    const val YESTERDAY = "yesterday"
    const val DATE = "date"

    /** The rows for [turns] (oldest first), on the device's days in [zone]. */
    fun build(turns: List<GraineChatTurn>, now: Date = Date(), zone: TimeZone = TimeZone.getDefault()): List<Row> {
        val today = localDate(now, zone)
        val yesterday = Calendar.getInstance(zone).run { time = now; add(Calendar.DAY_OF_MONTH, -1); localDate(time, zone) }
        val rows = ArrayList<Row>()
        var lastDay: String? = null
        var openCall = -1
        var callDay: String? = null
        turns.forEachIndexed { index, turn ->
            val day = localDate(turn.at, zone)
            val voice = turn.channel == GraineChatTurn.Channel.VOICE
            // One call, one row — until the day turns: a separator never sits inside a row.
            val call = if (openCall >= 0) rows[openCall] as? Row.Voice else null
            if (voice && call != null && callDay == day) {
                rows[openCall] = call.copy(last = index)
                return@forEachIndexed
            }
            if (if (lastDay == null) day != today else day != lastDay) {
                val kind = when (day) { today -> TODAY; yesterday -> YESTERDAY; else -> DATE }
                rows.add(Row.Day(kind, day, turn.at))
            }
            lastDay = day
            if (voice) {
                rows.add(Row.Voice("voice-${turn.id}", turn.at, index, index))
                openCall = rows.size - 1
                callDay = day
            } else {
                rows.add(Row.Turn(turn))
                openCall = -1
                callDay = null
            }
        }
        return rows
    }

    /** "Today", "Yesterday", else the date in the device's words ("Tue 14 Oct"), with the year when it is not this one. */
    fun dayLabel(day: Row.Day, now: Date = Date(), zone: TimeZone = TimeZone.getDefault(), locale: Locale = Locale.getDefault()): String =
        when (day.day) {
            TODAY -> "Today"
            YESTERDAY -> "Yesterday"
            else -> {
                val sameYear = year(day.at, zone) == year(now, zone)
                // The device's own order and words where the platform has them (JVM tests: the fallback).
                val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, if (sameYear) "EEEdMMM" else "EEEdMMMy") }
                    .getOrNull() ?: if (sameYear) "EEE d MMM" else "EEE d MMM yyyy"
                java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }.format(day.at)
            }
        }

    /** "Voice call · 6:05 PM" — the call's first turn, in the device's short time ([clock]). */
    fun callLabel(clock: String): String = "Voice call · $clock"

    /** What a screen reader says for the row: "Voice call at 6:05 PM". */
    fun callSpoken(clock: String): String = "Voice call at $clock"

    /** The header's "New conversation" belongs to a conversation that continues: offered while restored turns are on screen. */
    fun offersNewConversation(turns: List<GraineChatTurn>): Boolean = turns.any { it.restored }

    /** The brand's starters belong to a new chat: not once the user has said something, nor on a conversation that continues. */
    fun offersStarters(turns: List<GraineChatTurn>): Boolean = turns.none { it.role == Role.CUSTOMER || it.restored }

    /** The local date of an instant, as the contract writes it: YYYY-MM-DD. */
    fun localDate(at: Date, zone: TimeZone): String {
        val c = Calendar.getInstance(zone).apply { time = at }
        return String.format(Locale.ROOT, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun year(at: Date, zone: TimeZone): Int = Calendar.getInstance(zone).apply { time = at }.get(Calendar.YEAR)
}

/**
 * THE PIECES A CHAT WINDOW DRAWS of those the design can leave out
 * (contract/DESIGN.md → Chat window: Booked calls, Earlier messages, New
 * conversation) — the React Native SDK's chatPieces, ported, and pinned for
 * every SDK by sdk/contract/history.json → `display`. With earlier messages
 * hidden the window opens like a new chat: the restored turns are not drawn
 * (the agent still has them), the starters may show, and the compose control,
 * which belongs to earlier messages on screen, does not. Pure: HistoryTest.
 */
internal data class ChatPieces(
    /** The turns to draw, oldest first. */
    val turns: List<GraineChatTurn>,
    /** The brand's starters may show: nothing restored on screen, nothing said by the user. */
    val starters: Boolean,
    /** The header's compose control: kept by the design, and earlier messages on screen. */
    val newConversation: Boolean,
    /** The booked-calls card: kept by the design, and at least one booked call. */
    val upcoming: Boolean,
    /** The card's title: the brand's words, else "Upcoming call" / "Upcoming calls". */
    val upcomingTitle: String,
) {
    companion object {
        /** For [turns] and [upcomingCount] booked calls, under the design's chat tokens (null, an older server: everything on). */
        fun of(turns: List<GraineChatTurn>, chat: ChatTokens?, upcomingCount: Int = 0): ChatPieces {
            val shown = if (chat?.showHistory != false) turns else turns.filter { !it.restored }
            val words = chat?.upcomingTitle?.trim().orEmpty()
            return ChatPieces(
                turns = shown,
                starters = ChatRows.offersStarters(shown),
                newConversation = chat?.newConversation != false && ChatRows.offersNewConversation(shown),
                upcoming = chat?.showUpcomingCalls != false && upcomingCount > 0,
                upcomingTitle = words.ifEmpty { UpcomingCalls.title(upcomingCount) },
            )
        }
    }
}
