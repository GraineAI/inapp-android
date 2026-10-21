package ai.graine.inapp

/**
 * CALL ANALYTICS ROWS v1 — HOW A CALL CONNECTED, ON ITS RECORD.
 *
 * Every call reports how it connected: which transport carried it and why,
 * where the tap's wait went, when the agent's voice was first heard, the
 * connection healing itself, and the audio at the end. Each report is one app
 * event on the call's own conversation channel — the WebRTC control channel,
 * or the voice socket — `quiet` and `telemetry`, so the runtime files it on
 * the call's record and never speaks about it. Execution Logs reads the rows
 * back as the call's Connection.
 *
 * THE ROWS ARE DEFINED ONCE, in sdk/inapp/protocol.ts (callAnalyticsEvent).
 * This is Android's builder of them, held to sdk/contract/call-analytics.json
 * — generated from that TypeScript — byte for byte (CallAnalyticsVectorsTest):
 * the same input makes the same bytes on the web, on iOS and here. A row name,
 * a key or an enum value changes there, or nowhere.
 *
 * The rules every row keeps: only its own keys, in its order, at most
 * [MAX_KEYS]; a key that is absent, null or does not fit its type is left out,
 * never sent as 0; integers are floor(x + 0.5), held at their ceiling; a
 * percentage keeps two decimals the same way; every string comes from a closed
 * list, so no value can carry an id, a name or a number. Seconds in a detail
 * are tenths = floor((ms + 50) / 100), written with a '.' whatever the phone's
 * locale — integer arithmetic, never String.format, so every platform writes
 * the same digits.
 */
internal object CallAnalytics {
    const val VERSION = 1
    /** The most keys one row may carry. `connect` uses all twelve. */
    const val MAX_KEYS = 12

    const val TRANSPORT = "transport"
    const val CONNECT = "connect"
    const val VOICE = "voice"
    const val RECOVERY = "recovery"
    const val QUALITY = "quality"
    const val MEDIA = "media"

    // What a value may be (protocol.ts CallAnalyticsType); `enum:<list>` takes a value of that list, exactly.
    private const val MS = "ms"
    private const val SECONDS = "seconds"
    private const val COUNT = "count"
    private const val PERCENT = "percent"
    private const val BOOL = "bool"
    private const val CODEC = "codec"

    /** The ceilings: a value above one is held AT it. Ten minutes; a day; twenty redials (a loop, not a recovery); 100%. */
    val LIMITS: Map<String, Long> = linkedMapOf(MS to 600_000L, SECONDS to 86_400L, COUNT to 20L, PERCENT to 100L)

    /** Every closed list a value may come from, by name. */
    val ENUMS: Map<String, List<String>> = linkedMapOf(
        "transport" to listOf("rtc", "ws"),
        "requested" to listOf("rtc", "ws", "auto"),
        "fallback_reason" to listOf(
            "mint_failed", "register_failed", "no_response", "no_answer",
            "ice_timeout", "ice_failed", "ice_disconnected", "media", "stalled", "screen_channel",
            "not_configured", "unreachable", "dashboard", "unavailable", "error",
        ),
        "mode" to listOf("prepared", "cold"),
        "mint_route" to listOf("direct", "web"),
        "candidate" to listOf("host", "srflx", "prflx", "relay"),
        "recovery" to listOf("redial", "ice_restart", "socket_replaced"),
        "media_reason" to listOf("ice_failed", "ice_disconnected", "ice_timeout"),
        // In their SDP spelling: what a `codec` value is written as, whatever case it arrived in.
        "codec" to listOf("opus", "PCMU", "PCMA", "G722", "G729", "iLBC", "ISAC", "L16"),
    )

    /**
     * HOW THE CALL ENDED, once, as it ends — sent ahead of the contract: [ROWS]
     * is protocol.ts's to the byte, and this row joins it there. Until then it
     * is filed like every telemetry row (a raw timeline shows its detail). Its
     * `cause` is [CallEnd]'s word; `after_ms` how long the call had its audio;
     * `voice_heard` whether the agent's voice had reached the phone.
     */
    const val ENDED = "ended"

    /** Rows sent ahead of the contract, kept apart from [ROWS]: built by the same rules. */
    val AHEAD: Map<String, List<Pair<String, String>>> = linkedMapOf(
        ENDED to listOf("cause" to "enum:end_cause", "transport" to "enum:transport", "after_ms" to MS, "voice_heard" to BOOL),
    )

    /** Their closed lists. */
    private val AHEAD_ENUMS: Map<String, List<String>> = linkedMapOf(
        "end_cause" to listOf(CallEnd.LOCAL_END, CallEnd.REMOTE_BYE, CallEnd.MEDIA_DROP, CallEnd.AUDIO_WATCH, CallEnd.FAILED),
    )

    /** THE ROWS, each with its keys in the order they are sent and the type of each value. */
    val ROWS: Map<String, List<Pair<String, String>>> = linkedMapOf(
        // Once, when the call is connected on whichever transport carries it. fallback_reason only on a fallback.
        TRANSPORT to listOf("transport" to "enum:transport", "requested" to "enum:requested", "fallback_reason" to "enum:fallback_reason"),
        // Once, at media. total = setup + dial: tap → INVITE, INVITE → answer. `warm` for readers older than `mode`.
        CONNECT to listOf(
            "mode" to "enum:mode", "warm" to BOOL, "total_ms" to MS, "setup_ms" to MS, "dial_ms" to MS, "mint_ms" to MS,
            "mint_route" to "enum:mint_route", "gather_ms" to MS, "register_ms" to MS, "redials" to COUNT,
            "prepared_age_s" to SECONDS, "ice_ms" to MS,
        ),
        // Once, when the agent's voice is first heard. first_audio_ms: answer → the first inbound audio, voice or not.
        VOICE to listOf("tap_to_voice_ms" to MS, "answer_to_voice_ms" to MS, "first_audio_ms" to MS, "codec" to CODEC, "candidate" to "enum:candidate"),
        // Each time the connection heals; after_ms is how long the trouble lasted before it did.
        RECOVERY to listOf("what" to "enum:recovery", "after_ms" to MS),
        // Once at the end of a call that had media, best effort.
        QUALITY to listOf("duration_s" to SECONDS, "loss_pct" to PERCENT, "jitter_avg_ms" to MS, "jitter_max_ms" to MS, "rtt_avg_ms" to MS),
        // The call was answered and no audio could flow.
        MEDIA to listOf("reason" to "enum:media_reason", "transport" to "enum:transport"),
    )

    /**
     * THE BUILDER: one row's event, as the JSON its channel carries —
     * `{name, detail, quiet: true, telemetry: true, data}`. Only that row's
     * keys, in its order; null and whatever does not fit left out; integers
     * rounded half up and held at their ceilings; enums only from their lists;
     * the detail written from what survived, so it never says what the data
     * does not. Null for a name that is not a row: a report nobody can read is
     * not sent. Pure — the same input, the same bytes.
     *
     * Text, not a JSONObject, because the bytes are the contract: a JSONObject
     * keeps neither the key order (on the JVM) nor "opus/48000" unescaped.
     */
    fun event(name: String, input: Map<String, *>?): String? {
        val data = cleaned(name, input) ?: return null
        val sb = StringBuilder(192)
        sb.append("{\"name\":")
        Json.quote(sb, name)
        sb.append(",\"detail\":")
        Json.quote(sb, detail(name, data))
        sb.append(",\"quiet\":true,\"telemetry\":true,\"data\":{")
        var first = true
        for ((key, value) in data) {
            if (!first) sb.append(',')
            first = false
            Json.quote(sb, key)
            sb.append(':')
            when (value) {
                is String -> Json.quote(sb, value)
                is Hundredths -> value.writeTo(sb)
                else -> sb.append(value)
            }
        }
        return sb.append("}}").toString()
    }

    /** A row's data as it is sent: only its keys, in its order, each cleaned to its type; null for a name that is not a row. */
    private fun cleaned(name: String, input: Map<String, *>?): LinkedHashMap<String, Any>? {
        val keys = ROWS[name] ?: AHEAD[name] ?: return null
        val data = LinkedHashMap<String, Any>()
        if (input != null) for ((key, type) in keys) {
            if (!input.containsKey(key)) continue
            clean(type, input[key])?.let { data[key] = it }
            if (data.size >= MAX_KEYS) break
        }
        return data
    }

    /**
     * A row's data as plain values, exactly as it is sent — integers as Long,
     * a percentage as a Double of its two decimals — for the call's own
     * connection report ([CallConnectionReport]). Null for a name that is not a row.
     */
    fun values(name: String, input: Map<String, *>?): Map<String, Any>? =
        cleaned(name, input)?.mapValues { (_, v) -> if (v is Hundredths) v.n / 100.0 else v }

    /** The frame a row travels in, on either channel: `{"type":"app_event","event":…}`. Null for a name that is not a row. */
    fun frame(name: String, input: Map<String, *>?): String? = event(name, input)?.let { "{\"type\":\"app_event\",\"event\":$it}" }

    /** Milliseconds as a detail writes them: "0.2s", "12.5s". */
    fun seconds(ms: Long): String {
        val tenths = (Math.max(0L, ms) + 50) / 100
        return "${tenths / 10}.${tenths % 10}s"
    }

    // ── one value ───────────────────────────────────────────────────────────

    /** A percentage as whole hundredths, so it is written as JavaScript writes the number: 0.28, 0.3, 100. */
    private class Hundredths(val n: Long) {
        fun writeTo(sb: StringBuilder) {
            sb.append(n / 100)
            val cents = (n % 100).toInt()
            if (cents == 0) return
            sb.append('.')
            if (cents < 10) sb.append('0')
            sb.append(if (cents % 10 == 0) cents / 10 else cents)
        }

        /** As a detail writes it, to one decimal: "0.3%". */
        fun text(): String {
            val tenths = (n + 5) / 10
            return "${tenths / 10}.${tenths % 10}%"
        }
    }

    /** One value as its type allows it, or null when it does not fit. */
    private fun clean(type: String, v: Any?): Any? = when (type) {
        MS, SECONDS, COUNT -> number(v)?.let { Math.min(Math.floor(it + 0.5), LIMITS.getValue(type).toDouble()).toLong() }
        PERCENT -> number(v)?.let { Hundredths(Math.min(Math.floor(it * 100 + 0.5), LIMITS.getValue(PERCENT) * 100.0).toLong()) }
        BOOL -> v as? Boolean
        CODEC -> codec(v)
        else -> (v as? String)?.takeIf { type.removePrefix("enum:").let { list -> (ENUMS[list] ?: AHEAD_ENUMS[list])?.contains(it) == true } }
    }

    /** A number, finite and not negative. A numeric string or a boolean is not one. */
    private fun number(v: Any?): Double? {
        if (v !is Number) return null
        val x = v.toDouble()
        return if (x.isNaN() || x.isInfinite() || x < 0) null else x
    }

    /** "audio/OPUS" → "opus", "opus/48000/2" → "opus/48000", "pcmu/8000" → "PCMU/8000"; anything else null. */
    private val CODEC_SHAPE = Regex("(?:[Aa][Uu][Dd][Ii][Oo]/)?([A-Za-z0-9]{2,8})(?:/([0-9]{4,6}))?(?:/[0-9]{1,2})?")

    private fun codec(v: Any?): String? {
        val m = CODEC_SHAPE.matchEntire(jsTrim(v as? String ?: return null)) ?: return null
        val name = ENUMS.getValue("codec").firstOrNull { it.lowercase() == m.groupValues[1].lowercase() } ?: return null
        val rate = m.groupValues[2]
        return if (rate.isEmpty()) name else "$name/$rate"
    }

    /** JavaScript's String.prototype.trim, whose idea of white space is not Kotlin's at the edges. */
    private fun jsTrim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && jsSpace(s[a])) a++
        while (b > a && jsSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun jsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    // ── the detail ──────────────────────────────────────────────────────────

    /**
     * The one line a row reads as wherever a raw timeline is shown:
     *   transport  "WebRTC" · "WebSocket" · "WebSocket (fallback: no_answer)"
     *   connect    "prepared · 0.2s (setup 0.0s, answer 0.2s)"
     *   voice      "agent's voice 0.9s after the tap (0.7s after the answer)"
     *   recovery   "redial after 1.2s"
     *   quality    "64s · loss 0.3% · jitter 12 ms"
     *   media      "no audio path (ice_timeout)"
     *   ended      "hung up on this side after 3.2s, the agent heard"
     * Each part only when its value survived.
     */
    private fun detail(name: String, d: Map<String, Any>): String {
        fun ms(key: String): Long? = d[key] as? Long
        return when (name) {
            TRANSPORT -> when (d["transport"]) {
                "rtc" -> "WebRTC"
                "ws" -> d["fallback_reason"]?.let { "WebSocket (fallback: $it)" } ?: "WebSocket"
                else -> d["fallback_reason"]?.let { "fallback: $it" } ?: "transport"
            }
            CONNECT -> {
                val mode = d["mode"] as? String ?: when (d["warm"]) {
                    true -> "prepared"
                    false -> "cold"
                    else -> "connected"
                }
                val parts = listOfNotNull(ms("setup_ms")?.let { "setup ${seconds(it)}" }, ms("dial_ms")?.let { "answer ${seconds(it)}" })
                mode + (ms("total_ms")?.let { " · ${seconds(it)}" } ?: "") + (if (parts.isEmpty()) "" else parts.joinToString(", ", " (", ")"))
            }
            VOICE -> {
                val tap = ms("tap_to_voice_ms")
                val answer = ms("answer_to_voice_ms")
                when {
                    tap != null -> "agent's voice ${seconds(tap)} after the tap" + (answer?.let { " (${seconds(it)} after the answer)" } ?: "")
                    answer != null -> "agent's voice ${seconds(answer)} after the answer"
                    else -> "agent's voice heard"
                }
            }
            RECOVERY -> {
                val what = when (d["what"]) {
                    "redial" -> "redial"
                    "ice_restart" -> "ice restart"
                    "socket_replaced" -> "socket replaced"
                    else -> "recovered"
                }
                what + (ms("after_ms")?.let { " after ${seconds(it)}" } ?: "")
            }
            QUALITY -> listOfNotNull(
                ms("duration_s")?.let { "${it}s" },
                (d["loss_pct"] as? Hundredths)?.let { "loss ${it.text()}" },
                ms("jitter_avg_ms")?.let { "jitter $it ms" },
            ).joinToString(" · ").ifEmpty { "call quality" }
            MEDIA -> (d["reason"] as? String)?.let { "no audio path ($it)" } ?: "no audio path"
            ENDED -> when (d["cause"]) {
                CallEnd.LOCAL_END -> "hung up on this side"
                CallEnd.REMOTE_BYE -> "the far side hung up"
                CallEnd.MEDIA_DROP -> "the audio path dropped"
                CallEnd.AUDIO_WATCH -> "nothing arrived: ended on this side"
                CallEnd.FAILED -> "failed"
                else -> "ended"
            } + (ms("after_ms")?.let { " after ${seconds(it)}" } ?: "") + when (d["voice_heard"]) {
                true -> ", the agent heard"
                false -> ", the agent not heard"
                else -> ""
            }
            else -> name
        }
    }
}

/**
 * ONE CALL'S CONNECTION REPORT, folded from its analytics rows as they are
 * made ([GraineCalls.connection], [GraineCallOutcome.connection]): the facts
 * Execution Logs' Connection card shows, in its words. The tap's wait is the
 * card's segments — setup, then login (the device signing in, inside the
 * setup of a call set up after the tap; a prepared call logged in before the
 * tap, so it has none), the answer, the agent's first words — the audio is
 * the quality row's (or the reading so far, while the call is live), its
 * verdict on the thresholds the SDK's connection indicator uses, and every
 * recovery row counts once. Pure; main thread.
 */
internal class CallConnectionReport {
    /** The report so far; null before any row. */
    var value: GraineCallConnection? = null
        private set

    /** One row (or, for [CallAnalytics.QUALITY], a reading of the audio so far), as [CallAnalytics.values] cleans it. A row it has no fact from changes nothing. */
    fun fold(name: String, input: Map<String, *>?) {
        val d = CallAnalytics.values(name, input) ?: return
        val r = value ?: GraineCallConnection()
        fun ms(key: String): Long? = d[key] as? Long
        value = when (name) {
            CallAnalytics.TRANSPORT -> r.copy(transport = when (d["transport"]) {
                "rtc" -> CallTransportChoice.WEBRTC
                "ws" -> CallTransportChoice.WEBSOCKET
                else -> r.transport
            })
            CallAnalytics.CONNECT -> {
                val prepared = (d["mode"] as? String)?.let { it == "prepared" } ?: d["warm"] as? Boolean
                val setup = ms("setup_ms")
                val login = if (prepared == true) null else ms("register_ms")
                val inSetup = if (setup != null && login != null) minOf(login, setup) else null
                r.copy(
                    prepared = prepared,
                    preparedAgeSeconds = if (prepared == true) ms("prepared_age_s") else null,
                    credential = d["mint_route"] as? String,
                    setupMs = setup?.let { it - (inSetup ?: 0L) },
                    loginMs = if (setup != null) inSetup else login,
                    answerMs = ms("dial_ms"),
                )
            }
            CallAnalytics.VOICE -> r.copy(tapToVoiceMs = ms("tap_to_voice_ms"), firstWordsMs = ms("answer_to_voice_ms"))
            CallAnalytics.RECOVERY -> r.copy(recoveries = r.recoveries + 1)
            CallAnalytics.QUALITY -> {
                val loss = d["loss_pct"] as? Double
                val jitter = ms("jitter_avg_ms")
                val rtt = ms("rtt_avg_ms")
                if (loss == null && jitter == null && rtt == null && ms("jitter_max_ms") == null) return
                r.copy(audio = GraineCallConnection.Audio(loss, jitter, ms("jitter_max_ms"), rtt, verdict(loss, jitter, rtt)))
            }
            else -> return
        }
    }

    companion object {
        /** "good", "fair" or "poor" — Execution Logs' verdict, on the connection indicator's thresholds; null with nothing measured. */
        fun verdict(lossPct: Double?, jitterMs: Long?, rttMs: Long?): String? = when {
            lossPct == null && jitterMs == null && rttMs == null -> null
            (lossPct ?: 0.0) >= 8 || (jitterMs ?: 0L) >= 80 || (rttMs ?: 0L) >= 400 -> GraineCallConnection.POOR
            (lossPct ?: 0.0) >= 2 || (jitterMs ?: 0L) >= 30 || (rttMs ?: 0L) >= 150 -> GraineCallConnection.FAIR
            else -> GraineCallConnection.GOOD
        }
    }
}
