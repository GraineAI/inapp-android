package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject

/*
 * THE VOICE SOCKET, AS A NATIVE CALL SPEAKS IT — pure, no Android, no I/O.
 *
 * sdk/NATIVE-CALLS.md "The wire protocol": the variables on the URL and the
 * ticket, every frame this SDK puts on the socket, and how every frame the
 * agent sends is read. Ports of sdk/inapp/protocol.ts and client.ts, pinned by
 * NativeCallVectorsTest and CallSessionTest.
 */

internal object CallVariables {
    /** The ticket's metadata caps — sdk/inapp/protocol.ts TICKET_VARIABLE_LIMITS, enforced server-side too. */
    const val TICKET_KEYS = 12
    const val TICKET_KEY_LENGTH = 40
    const val TICKET_VALUE_LENGTH = 200
    const val TICKET_TOTAL_BYTES = 1024

    /**
     * THE VARIABLES THE AGENT'S PROMPT READS, on the socket URL —
     * protocol.ts `contextToken`: base64 of the UTF-8 JSON
     * `{...vals, call_type, recipient_data: {...vals, call_type}}`, where
     * `vals` drop nulls and objects and stringify the rest. Null when there is
     * nothing to say. The JSON is written as JavaScript's JSON.stringify
     * writes it (key order kept, `/` and non-ASCII unescaped), so the token is
     * the TypeScript's byte for byte.
     */
    fun contextToken(variables: Map<String, Any?>?, callType: String = "webcall"): String? {
        val vals = LinkedHashMap<String, String>()
        for ((k, v) in variables ?: emptyMap()) {
            val s = jsString(v) ?: continue
            val key = k.trim()
            if (key.isNotEmpty()) vals[key] = s
        }
        if (vals.isEmpty()) return null
        val sb = StringBuilder("{")
        fun pair(k: String, v: String, first: Boolean) {
            if (!first) sb.append(',')
            Json.quote(sb, k); sb.append(':'); Json.quote(sb, v)
        }
        var first = true
        for ((k, v) in vals) { pair(k, v, first); first = false }
        pair("call_type", callType, first)
        sb.append(",\"recipient_data\":{")
        first = true
        for ((k, v) in vals) { pair(k, v, first); first = false }
        pair("call_type", callType, first)
        sb.append("}}")
        return B64.encode(sb.toString().toByteArray(Charsets.UTF_8))
    }

    /**
     * The variables as the ticket carries them — protocol.ts
     * `variablesForTicket`: at most 12 keys of at most 40 characters, values
     * of at most 200, and nothing at all over 1024 bytes. The gateway builds
     * the agent's context from the ticket's signed claims, so this is what the
     * agent knows on the gateway path.
     */
    fun forTicket(vars: Map<String, Any?>?): Map<String, String>? {
        if (vars == null) return null
        val out = LinkedHashMap<String, String>()
        var n = 0
        for ((k, v) in vars) {
            if (n >= TICKET_KEYS) break
            val s = jsString(v) ?: continue
            val key = k.take(TICKET_KEY_LENGTH)
            val value = s.take(TICKET_VALUE_LENGTH)
            if (key.isEmpty() || value.isEmpty()) continue
            out[key] = value
            n++
        }
        if (n == 0) return null
        // Bytes, not characters: a Devanagari name is three bytes a letter.
        var bytes = 2
        for ((k, v) in out) bytes += utf8Length(k) + utf8Length(v) + 6
        return if (bytes > TICKET_TOTAL_BYTES) null else out
    }

    /**
     * The call's variables, the SDK's after them, as many as the ticket holds.
     *
     * [forTicket] keeps the first twelve keys and drops everything over a
     * kilobyte — so the merged map, handed over whole, could lose exactly the
     * keys this call exists to carry (`who_called`, `call_reason`) behind an
     * app's long identify(). The call's own go first and always win; the
     * SDK's are added while the ticket still accepts them.
     */
    fun ticketVariables(call: Map<String, String>, sdk: Map<String, String>): Map<String, String>? {
        var best = forTicket(call)
        if (best == null && call.isNotEmpty()) return null
        val candidate = LinkedHashMap<String, String>(call)
        for ((k, v) in sdk) {
            if (k in candidate) continue
            candidate[k] = v
            val next = forTicket(candidate)
            if (next != null && next.size > (best?.size ?: 0)) best = next else candidate.remove(k)
        }
        return best
    }

    /** The call's own variables, then the SDK's — the call's winning on a shared key. */
    fun merged(call: Map<String, String>, sdk: Map<String, String>): Map<String, String> =
        LinkedHashMap<String, String>(call).apply { for ((k, v) in sdk) if (k !in this) put(k, v) }

    /** JavaScript's `String(v)` for what may be a variable; null for what is dropped (null, objects). */
    fun jsString(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is String -> v
        is Boolean -> v.toString()
        is Int, is Long, is Short, is Byte -> v.toString()
        is Number -> {
            val d = v.toDouble()
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e21) {
                if (Math.abs(d) < 9.0e15) d.toLong().toString() else java.math.BigDecimal(d).toPlainString()
            } else if (d.isNaN()) "NaN" else if (d.isInfinite()) (if (d > 0) "Infinity" else "-Infinity") else d.toString()
        }
        is Map<*, *>, is Iterable<*>, is Array<*>, is JSONObject, is JSONArray -> null
        else -> v.toString()
    }

    /** UTF-8 length as protocol.ts `utf8Length` counts it, surrogate quirks included. */
    fun utf8Length(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            n += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xd800..0xdbff -> { i++; 4 }
                else -> 3
            }
            i++
        }
        return n
    }
}

/** JSON text as JavaScript writes it — for the bytes that must match the TypeScript exactly. */
internal object Json {
    fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u").append(hex4(c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1]); i++
                }
                // A lone surrogate is escaped, as JSON.stringify has done since ES2019.
                Character.isSurrogate(c) -> sb.append("\\u").append(hex4(c.code))
                else -> sb.append(c)
            }
            i++
        }
        sb.append('"')
    }

    fun quote(s: String): String = StringBuilder(s.length + 2).also { quote(it, s) }.toString()

    private fun hex4(code: Int): String = Integer.toHexString(code).padStart(4, '0')
}

/** One `text` frame, read: what to show, and what it says about the turn. */
internal data class AgentChunk(val text: String, val startsTurn: Boolean, val endsTurn: Boolean)

internal object CallText {
    const val STREAM_START = "<beginning_of_stream>"
    const val STREAM_END = "<end_of_stream>"
    private val START_RE = Regex(Regex.escape(STREAM_START), RegexOption.IGNORE_CASE)
    private val END_RE = Regex(Regex.escape(STREAM_END), RegexOption.IGNORE_CASE)

    /** protocol.ts `readAgentChunk`: the turn markers are not words anybody said. */
    fun readAgentChunk(raw: Any?): AgentChunk {
        val text = if (raw == null || raw == JSONObject.NULL) "" else raw.toString()
        if (text.isEmpty()) return AgentChunk("", startsTurn = false, endsTurn = false)
        if (text.indexOf('<') < 0) return AgentChunk(text, startsTurn = false, endsTurn = false)
        val lower = text.lowercase()
        val starts = lower.contains(STREAM_START)
        val ends = lower.contains(STREAM_END)
        if (!starts && !ends) return AgentChunk(text, startsTurn = false, endsTurn = false)
        return AgentChunk(text.replace(START_RE, "").replace(END_RE, ""), starts, ends)
    }

    /**
     * protocol.ts `spokenJoinGap`. A spoken turn's words arrive as the
     * phrases they were synthesised in, with no spaces between them ("Sure! I
     * can", "help you", "settings,"), so joined raw a caption reads "I
     * canhelp you". One space goes before [next] unless either side already
     * has one, [next] opens with closing punctuation, or [soFar] ends with an
     * opening bracket or a joiner. Whitespace is JavaScript's `\s`.
     */
    fun spokenJoinGap(soFar: String, next: String): String {
        if (soFar.isEmpty() || next.isEmpty()) return ""
        if (isJsSpace(soFar[soFar.length - 1]) || isJsSpace(next[0])) return ""
        if (next[0] in CLOSERS) return ""
        if (soFar[soFar.length - 1] in OPENERS) return ""
        return " "
    }

    private const val CLOSERS = ",.;:!?)]}…%'’”»।॥"
    private const val OPENERS = "([{“‘«/-"

    /** JavaScript's `\s`: ASCII whitespace plus the Unicode spaces and line separators. */
    fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ', ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }
}

/** Every frame a native call puts on the socket. Strings, ready to send. */
internal object CallFrames {
    const val SUBPROTOCOL = "graine.embed.v1"
    const val CLIENT = "graine-android-sdk"
    const val PING_MS = 25_000L
    const val OPEN_TIMEOUT_MS = 15_000L
    const val ACTION_DEADLINE_MS = 6_000L
    /** Answered this long before the runtime gives up, so the agent hears a real answer rather than silence. */
    const val ACTION_ANSWER_BY_MS = ACTION_DEADLINE_MS - 500
    const val MARK_ACK_CAP_MS = 10_000L
    /** "Agent audible" needs agent audio this recent — the echo guard fails open after it. */
    const val AGENT_AUDIBLE_RECENCY_MS = 1_500L
    /** The agent stops speaking once its audio has played and none arrived for this long. */
    const val SPEAKING_TAIL_MS = 300L
    /** Below this much queued audio the agent's voice has, for every purpose here, finished. */
    const val AUDIBLE_BUFFER_SECONDS = 0.02
    const val CLOSE_AFTER_STOP_MS = 200L

    fun init(variables: Map<String, String>): String = JSONObject()
        .put("type", "init")
        .put("event", "start")
        .put("meta_data", JSONObject().put("client", CLIENT).put("context_data", JSONObject(variables as Map<*, *>)))
        .toString()

    /**
     * One 20 ms microphone frame. Written by hand: fifty a second, and base64
     * holds nothing JSON must escape.
     */
    fun audio(base64: String, sampleRate: Int = AgentAudio.CAPTURE_RATE): String =
        "{\"type\":\"audio\",\"data\":\"$base64\",\"meta_info\":{\"format\":\"pcm\",\"sample_rate\":$sampleRate,\"encoding\":\"base64\"}}"

    const val BARGE_IN = "{\"type\":\"barge_in\"}"
    const val PING = "{\"type\":\"ping\"}"
    const val STOP = "{\"type\":\"stop\",\"event\":\"stop\",\"data\":\"client_hangup\"}"

    fun markAck(name: String): String = JSONObject().put("type", "mark").put("name", name).toString()

    fun actionResult(actionId: String, result: ActionResult): String {
        val o = JSONObject().put("type", "app_action_result").put("action_id", actionId).put("status", result.status.wire)
        if (result.message.isNotEmpty()) o.put("message", result.message)
        if (result.data.isNotEmpty()) o.put("data", Protocol.toJson(result.data))
        return o.toString()
    }

    /**
     * The SDK's context frame (GraineInApp.currentContextFrame, the one the
     * web view path posts to its page) as the socket takes it: `app_context`,
     * with `available_actions` in the runtime's spelling. Absent stays absent
     * — an empty list would tell the agent nothing may be done here.
     */
    fun appContext(frame: JSONObject): String {
        val o = JSONObject().put("type", "app_context")
        o.put("context", frame.opt("context") ?: JSONObject())
        frame.optJSONArray("availableActions")?.let { o.put("available_actions", it) }
        if (frame.has("seq")) o.put("seq", frame.opt("seq"))
        return o.toString()
    }

    /** How long to hold a mark's ack: until the audio queued before it has been heard, at most 10 s. */
    fun markAckDelayMs(bufferedSeconds: Double): Long {
        if (bufferedSeconds.isNaN() || bufferedSeconds <= 0) return 0
        return Math.min(Math.ceil(bufferedSeconds * 1000).toLong(), MARK_ACK_CAP_MS)
    }
}

/** Everything the agent can send, typed. Anything unknown is [Ignored]. */
internal sealed class Inbound {
    data class Audio(val data: String, val format: String?, val sampleRate: Int?) : Inbound()
    data class Mark(val name: String) : Inbound()
    data class Text(val data: Any?) : Inbound()
    data class Transcript(val text: String, val final: Boolean) : Inbound()
    data class Action(val actionId: String, val name: String, val arguments: JSONObject) : Inbound()
    object SpeechStart : Inbound()
    object SpeechEnd : Inbound()
    object Clear : Inbound()
    data class Ignored(val type: String) : Inbound()

    companion object {
        fun parse(raw: String): Inbound? {
            val o = try { JSONObject(raw) } catch (_: Exception) { return null }
            return when (val type = o.optString("type")) {
                "audio" -> {
                    val data = o.opt("data") as? String ?: return Ignored(type)
                    val meta = o.optJSONObject("meta_info")
                    val fmt = meta?.opt("format")?.takeIf { it is String } as String?
                    val rate = meta?.opt("sample_rate")?.let { r -> (r as? Number)?.toInt() ?: (r as? String)?.toIntOrNull() }
                    Audio(data, fmt, rate)
                }
                "mark" -> o.optString("name").takeIf { it.isNotEmpty() && !o.isNull("name") }?.let { Mark(it) } ?: Ignored(type)
                "text" -> Text(o.opt("data"))
                "transcript" -> Transcript(o.optString("data"), final = true)
                "interim_transcript_received" -> Transcript(o.optString("data"), final = false)
                "app_action" -> {
                    val a = o.optJSONObject("action") ?: return Ignored(type)
                    Action(a.optString("action_id"), a.optString("name"), a.optJSONObject("arguments") ?: JSONObject())
                }
                "beginning_of_stream" -> SpeechStart
                "end_of_stream" -> SpeechEnd
                "clear", "interruption" -> Clear
                else -> Ignored(type)
            }
        }
    }
}

internal object CallUrls {
    const val DEFAULT_WS_HOST = "wss://ws.graine.ai"

    /** The direct socket, when no ticket could be minted: `{wsHost}/chat/v1/{agent}?webcall=1`. */
    fun direct(wsHost: String?, agentId: String): String {
        val host = (wsHost?.takeIf { it.isNotBlank() } ?: DEFAULT_WS_HOST).trimEnd('/')
        return "$host/chat/v1/${encodeURIComponent(agentId)}?webcall=1"
    }

    /** `context_token` on whichever URL was chosen — `&` if it already has a query. */
    fun withToken(url: String, token: String?): String =
        if (token.isNullOrEmpty()) url else url + (if ('?' in url) "&" else "?") + "context_token=" + encodeURIComponent(token)

    /** JavaScript's encodeURIComponent: everything but A–Z a–z 0–9 - _ . ! ~ * ' ( ) is escaped, as UTF-8. */
    fun encodeURIComponent(s: String): String {
        val sb = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }
}

/**
 * THE PUSH THAT RINGS THE PHONE — what a client's server sends through FCM.
 *
 *   type        "agent_call" (anything else is not ours)
 *   call_id     required; every outcome carries it back
 *   reason      optional → the prompt's `call_reason`
 *   caller_name optional; what the ring shows
 *   variables   optional JSON object, as a string → more prompt variables
 */
internal sealed class AgentCallPush {
    object NotOurs : AgentCallPush()
    data class Malformed(val reason: String) : AgentCallPush()
    data class Call(
        val callId: String,
        val reason: String?,
        val callerName: String?,
        val variables: Map<String, String>,
    ) : AgentCallPush()

    companion object {
        const val TYPE = "agent_call"

        fun parse(data: Map<String, String?>?): AgentCallPush {
            if (data == null || data["type"] != TYPE) return NotOurs
            val id = data["call_id"]?.trim().orEmpty()
            if (id.isEmpty()) return Malformed("call_id is missing")
            val vars = LinkedHashMap<String, String>()
            data["variables"]?.takeIf { it.isNotBlank() }?.let { raw ->
                // A variables string that is not a JSON object is dropped, not fatal:
                // the call still rings, the agent just knows less.
                val o = runCatching { JSONObject(raw) }.getOrNull()
                o?.keys()?.forEach { k -> if (k.isNotEmpty()) CallVariables.jsString(o.opt(k))?.let { vars[k] = it } }
            }
            return Call(
                callId = id,
                reason = data["reason"]?.trim()?.takeIf { it.isNotEmpty() },
                callerName = data["caller_name"]?.trim()?.takeIf { it.isNotEmpty() },
                variables = vars,
            )
        }
    }
}
