package ai.graine.inapp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * THE BRAND'S RING — `call.ringtone` `graine` or `soft`: the tone patterns of
 * contract/design.json → `ringtones` (theme.ts `RINGTONES`), synthesised here
 * as DESIGN.md "Call screen" → Ringtone says: each note a `wave` (sine or
 * triangle) at `hz` (0 is a rest) for `ms`, at `gain` of full scale, rising
 * linearly over its first 10 ms and falling over its last 40 ms; the notes in
 * order, then `pauseMs` of silence, again until the ring ends. Mono, 16-bit.
 * Pure (JVM-tested); [TonePlayer] plays it.
 */
internal object Ringtones {
    data class Note(val hz: Double, val ms: Double)
    data class Pattern(val wave: String, val gain: Double, val notes: List<Note>, val pauseMs: Double)

    /** design.json `ringtones`, pinned by the contract test. */
    val PATTERNS: Map<String, Pattern> = mapOf(
        "graine" to Pattern("sine", 0.5, listOf(
            Note(659.25, 150.0), Note(987.77, 230.0), Note(0.0, 110.0), Note(659.25, 150.0), Note(987.77, 230.0),
        ), 1_500.0),
        "soft" to Pattern("triangle", 0.35, listOf(Note(523.25, 420.0), Note(783.99, 560.0)), 2_000.0),
    )

    const val SAMPLE_RATE = 22_050
    const val ATTACK_MS = 10.0
    const val RELEASE_MS = 40.0

    /** Rounded half away from zero, as the other platforms round. */
    private fun round(v: Double): Long = if (v >= 0) Math.floor(v + 0.5).toLong() else Math.ceil(v - 0.5).toLong()

    /** One cycle of the pattern — its notes, then its pause — as 16-bit samples. */
    fun samples(p: Pattern, rate: Int = SAMPLE_RATE): ShortArray {
        val out = ArrayList<Short>()
        val r = rate.toDouble()
        for (note in p.notes) {
            val count = round(note.ms / 1_000 * r).toInt()
            val attack = ATTACK_MS / 1_000 * r
            val release = RELEASE_MS / 1_000 * r
            for (i in 0 until count) {
                if (note.hz <= 0) { out.add(0); continue }
                val t = i / r
                val phase = (t * note.hz) % 1.0
                // Triangle: 1 at the cycle's start, -1 at its middle.
                val wave = if (p.wave == "triangle") 1 - 4 * Math.abs(phase - 0.5) else Math.sin(2 * Math.PI * phase)
                val envelope = minOf(1.0, i / maxOf(1.0, attack), (count - 1 - i) / maxOf(1.0, release))
                out.add(round(wave * p.gain * maxOf(0.0, envelope) * 32_767).toInt().toShort())
            }
        }
        repeat(round(p.pauseMs / 1_000 * r).toInt()) { out.add(0) }
        return out.toShortArray()
    }

    /** The pattern for a `call.ringtone` word; null for `system` and anything this build does not know. */
    fun pattern(name: String?): Pattern? = PATTERNS[name]
}

/**
 * Plays a ring pattern on the RINGTONE stream, looping until stopped — so the
 * ringer switch, silent mode and Do Not Disturb silence it exactly as they
 * silence the phone's own ringtone. Main thread.
 */
internal class TonePlayer {
    private var track: AudioTrack? = null

    /** True when the pattern is playing; false (nothing played) for `system` or a device that refused it. */
    fun start(name: String?): Boolean {
        stop()
        val p = Ringtones.pattern(name) ?: return false
        return try {
            val pcm = Ringtones.samples(p)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(Ringtones.SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            t.play()
            track = t
            true
        } catch (e: Exception) {
            CallLog.error("the ring could not be played; the phone's own ringtone rings instead", e)
            false
        }
    }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }
}

/**
 * THE UPCOMING-CALLS CARD's rules (DESIGN.md "Chat window"): which booked
 * calls it lists — still to ring, with a time, soonest first, at most three —
 * and how a time reads in the customer's own zone and words: "Today, 5:30 PM",
 * "Tomorrow, 9:00 AM", else the weekday and date ("Tue 14 Oct, 10:00 AM").
 */
internal object UpcomingCalls {
    const val LIMIT = 3
    const val CANCELLING = "Cancelling…"
    const val CANCEL_FAILED = "Couldn’t cancel · tap to retry"

    fun shown(calls: List<GraineScheduledCall>): List<GraineScheduledCall> =
        calls.filter { it.isUpcoming && it.at != null }.sortedBy { it.at!!.time }.take(LIMIT)

    fun title(count: Int): String = if (count == 1) "Upcoming call" else "Upcoming calls"

    /** Which day a time is on, from today's point of view, in [zone]: 0 today, 1 tomorrow, anything else otherwise. */
    fun daysFrom(now: Date, at: Date, zone: TimeZone): Int {
        fun dayStart(d: Date): Long = Calendar.getInstance(zone).apply {
            time = d; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val a = Calendar.getInstance(zone).apply { timeInMillis = dayStart(now) }
        val b = Calendar.getInstance(zone).apply { timeInMillis = dayStart(at) }
        var days = 0
        if (b.before(a)) return -1
        while (a.before(b) && days < 2) { a.add(Calendar.DAY_OF_MONTH, 1); days++ }
        return days
    }

    /** The row's time line, the clock and the date written by the device's own formats. */
    fun label(at: Date, now: Date, zone: TimeZone, clock: (Date) -> String, day: (Date) -> String): String = when (daysFrom(now, at, zone)) {
        0 -> "Today, " + clock(at)
        1 -> "Tomorrow, " + clock(at)
        else -> day(at) + ", " + clock(at)
    }
}
