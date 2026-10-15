package ai.graine.inapp

/*
 * SIP AS IT ARRIVES ON A NATIVE CALL'S SIGNALLING SOCKET — one message per
 * WebSocket message (RFC 7118), parsed by rules that never trust it.
 *
 * Pure: no Android, no I/O. The user agent (SipUserAgent) builds on it; the
 * JVM tests feed it production's own messages and every malformed shape we
 * could think of. Whatever cannot be read is dropped — null, never a throw —
 * because a SIP parser inside a bank's app is attack surface: sizes are capped,
 * nothing recurses, and a header that cannot be read makes the message unread.
 */

/** One header as it arrived, under its full lower-case name (compact forms expanded). */
internal class SipHeader(val name: String, val value: String)

/** A request or a response. [method] is null for a response. */
internal class SipMessage(
    val method: String?,
    val requestUri: String?,
    val status: Int,
    val reason: String,
    val headers: List<SipHeader>,
    val body: String,
) {
    val isRequest: Boolean get() = method != null

    /** The first value of [name] (any spelling, compact forms included), or null. */
    fun header(name: String): String? {
        val key = SipParser.canonical(name)
        return headers.firstOrNull { it.name == key }?.value
    }

    /** Every value of [name], a comma-separated list split into its items for the headers that are lists. */
    fun values(name: String): List<String> {
        val key = SipParser.canonical(name)
        val out = ArrayList<String>()
        for (h in headers) {
            if (h.name != key) continue
            if (key in SipParser.LIST_HEADERS) out += SipText.splitList(h.value) else out += h.value
        }
        return out
    }

    val callId: String get() = header("call-id").orEmpty()
    val cseq: SipCSeq get() = SipCSeq.parse(header("cseq"))!!
    val fromTag: String? get() = header("from")?.let { SipAddress.parse(it)?.tag }
    val toTag: String? get() = header("to")?.let { SipAddress.parse(it)?.tag }
    /** The branch of the topmost Via: which of our transactions a response answers. */
    val topViaBranch: String? get() = values("via").firstOrNull()?.let { SipVia.parse(it)?.params?.get("branch") }
    val contentType: String? get() = header("content-type")?.substringBefore(';')?.trim()?.lowercase()
}

internal object SipParser {
    /** A whole message: an SBC's answer with candidates is under 2 KB, so 64 KB is generous and still bounded. */
    const val MAX_MESSAGE_CHARS = 64 * 1024
    /** Headers per message: production sends about a dozen. */
    const val MAX_HEADERS = 100
    /** One header, folded lines joined. */
    const val MAX_HEADER_CHARS = 8 * 1024

    /** RFC 3261 §7.3.3 and its successors: the one-letter names. */
    private val COMPACT = mapOf(
        "v" to "via", "f" to "from", "t" to "to", "i" to "call-id", "m" to "contact", "l" to "content-length",
        "c" to "content-type", "k" to "supported", "s" to "subject", "e" to "content-encoding", "o" to "event",
        "r" to "refer-to", "x" to "session-expires", "u" to "allow-events", "b" to "referred-by",
    )

    /** Headers whose value is a comma-separated list: split before reading an item. */
    val LIST_HEADERS = setOf(
        "via", "route", "record-route", "contact", "allow", "supported", "require", "proxy-require", "unsupported",
        "accept", "allow-events",
    )

    private val TOKEN = Regex("^[A-Za-z0-9.!%*_+`'~-]+$")
    private val METHOD = Regex("^[A-Z][A-Z-]{0,31}$")
    private val DIGITS = Regex("^[0-9]{1,9}$")

    fun canonical(name: String): String {
        val n = name.trim().lowercase()
        return COMPACT[n] ?: n
    }

    /** One message, or null when anything about it cannot be read. */
    fun parse(raw: String): SipMessage? {
        if (raw.isEmpty() || raw.length > MAX_MESSAGE_CHARS) return null
        val headEnd = raw.indexOf("\r\n\r\n")
        if (headEnd <= 0) return null
        val lines = raw.substring(0, headEnd).split("\r\n")
        val start = lines[0]

        var method: String? = null
        var uri: String? = null
        var status = 0
        var reason = ""
        if (start.regionMatches(0, "SIP/2.0 ", 0, 8, ignoreCase = true)) {
            // SIP/2.0 200 OK — three digits, then the phrase (which may be empty).
            val rest = start.substring(8)
            val code = rest.substringBefore(' ')
            if (code.length != 3 || !code.all { it in '0'..'9' }) return null
            status = code.toInt()
            if (status !in 100..699) return null
            reason = if (rest.length > 3) rest.substring(4) else ""
        } else {
            // INVITE sip:… SIP/2.0 — exactly three parts, single spaces.
            val parts = start.split(' ')
            if (parts.size != 3) return null
            if (!METHOD.matches(parts[0])) return null
            if (!parts[2].equals("SIP/2.0", ignoreCase = true)) return null
            if (!SipText.isSipUri(parts[1])) return null
            method = parts[0]
            uri = parts[1]
        }

        val headers = ArrayList<SipHeader>()
        var lastName: String? = null
        val lastValue = StringBuilder()
        fun flush(): Boolean {
            val name = lastName ?: return true
            if (headers.size >= MAX_HEADERS) return false
            headers += SipHeader(name, lastValue.toString().trim())
            lastName = null
            lastValue.setLength(0)
            return true
        }
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) return null
            if (line[0] == ' ' || line[0] == '\t') {
                // A folded line continues the header above it (RFC 3261 §7.3.1).
                if (lastName == null) return null
                lastValue.append(' ').append(line.trim())
            } else {
                if (!flush()) return null
                val colon = line.indexOf(':')
                if (colon <= 0) return null
                val name = line.substring(0, colon).trim()
                if (!TOKEN.matches(name)) return null
                lastName = canonical(name)
                lastValue.append(line, colon + 1, line.length)
            }
            if (lastValue.length > MAX_HEADER_CHARS) return null
        }
        if (!flush()) return null

        // The body: Content-Length bytes of what follows the blank line, when there is one.
        val rest = raw.substring(headEnd + 4)
        val lengthHeader = headers.firstOrNull { it.name == "content-length" }?.value
        val body = if (lengthHeader == null) rest else {
            if (!DIGITS.matches(lengthHeader)) return null
            val length = lengthHeader.toInt()
            val bytes = rest.toByteArray(Charsets.UTF_8)
            if (length > bytes.size) return null
            if (length == bytes.size) rest else String(bytes, 0, length, Charsets.UTF_8)
        }

        val message = SipMessage(method, uri, status, reason, headers, body)
        // What every SIP message must carry (RFC 3261 §8.1.1); without them nothing can be matched or answered.
        for (required in listOf("via", "from", "to", "call-id", "cseq")) {
            if (headers.none { it.name == required }) return null
        }
        val cseq = SipCSeq.parse(message.header("cseq")) ?: return null
        if (method != null && cseq.method != method) return null
        if (message.callId.isEmpty() || message.callId.length > 256) return null
        if (SipAddress.parse(message.header("from")!!) == null || SipAddress.parse(message.header("to")!!) == null) return null
        if (message.values("via").firstOrNull()?.let { SipVia.parse(it) } == null) return null
        return message
    }
}

/** `CSeq: 368 INVITE`. */
internal class SipCSeq(val number: Int, val method: String) {
    companion object {
        fun parse(value: String?): SipCSeq? {
            val v = value?.trim() ?: return null
            val space = v.indexOf(' ')
            if (space <= 0) return null
            val n = v.substring(0, space)
            val m = v.substring(space + 1).trim()
            if (n.length > 10 || !n.all { it in '0'..'9' }) return null
            val number = n.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE } ?: return null
            if (m.isEmpty() || m.length > 32 || !m.all { it in 'A'..'Z' || it == '-' }) return null
            return SipCSeq(number.toInt(), m)
        }
    }
}

/** `SIP/2.0/WSS host;branch=…;received=…;rport=…`. */
internal class SipVia(val protocol: String, val sentBy: String, val params: Map<String, String>) {
    companion object {
        private val SLASH = Regex("\\s*/\\s*")

        fun parse(value: String): SipVia? {
            // `SIP / 2.0 / WSS` is legal (RFC 3261 §25.1: LWS around the slashes).
            val v = value.trim().replace(SLASH, "/")
            val space = v.indexOfFirst { it == ' ' || it == '\t' }
            if (space <= 0) return null
            val protocol = v.substring(0, space)
            if (!protocol.startsWith("SIP/2.0/", ignoreCase = true)) return null
            val rest = v.substring(space).trim()
            val semi = rest.indexOf(';')
            val sentBy = (if (semi < 0) rest else rest.substring(0, semi)).trim()
            if (sentBy.isEmpty()) return null
            val params = if (semi < 0) emptyMap() else SipText.params(rest.substring(semi)) ?: return null
            return SipVia(protocol, sentBy, params)
        }
    }
}

/**
 * A name-addr or addr-spec — From, To, Contact, Route, Record-Route:
 * `"Display" <sip:user@host;uri-params>;tag=…` or `sip:user@host;tag=…`
 * (without the angle brackets every parameter is the header's, RFC 3261 §20.10).
 */
internal class SipAddress(val display: String?, val uri: String, val params: Map<String, String>) {
    val tag: String? get() = params["tag"]

    companion object {
        fun parse(value: String): SipAddress? {
            val v = value.trim()
            if (v.isEmpty()) return null
            val lt = SipText.indexOutsideQuotes(v, '<')
            if (lt >= 0) {
                val gt = v.indexOf('>', lt + 1)
                if (gt < 0) return null
                val uri = v.substring(lt + 1, gt).trim()
                if (!SipText.isSipUri(uri)) return null
                val display = v.substring(0, lt).trim().removeSurrounding("\"").ifEmpty { null }
                val params = SipText.params(v.substring(gt + 1)) ?: return null
                return SipAddress(display, uri, params)
            }
            val semi = v.indexOf(';')
            val uri = (if (semi < 0) v else v.substring(0, semi)).trim()
            if (!SipText.isSipUri(uri)) return null
            val params = if (semi < 0) emptyMap() else SipText.params(v.substring(semi)) ?: return null
            return SipAddress(null, uri, params)
        }
    }
}

/** `Session-Expires: 90;refresher=uac` (RFC 4028). */
internal class SipSessionExpires(val seconds: Int, val refresher: String?) {
    companion object {
        fun parse(value: String?): SipSessionExpires? {
            val v = value?.trim() ?: return null
            val semi = v.indexOf(';')
            val n = (if (semi < 0) v else v.substring(0, semi)).trim()
            if (n.isEmpty() || n.length > 9 || !n.all { it in '0'..'9' }) return null
            val params = if (semi < 0) emptyMap() else SipText.params(v.substring(semi)) ?: return null
            return SipSessionExpires(n.toInt(), params["refresher"]?.lowercase())
        }
    }
}

/**
 * A digest challenge — `WWW-Authenticate` / `Proxy-Authenticate`:
 * `Digest realm="…", algorithm=MD5, qop="auth", nonce="…"`. The parameters
 * are a comma list whose quoted values may hold commas, so it is read by hand.
 */
internal class SipChallenge(
    val realm: String,
    val nonce: String,
    val opaque: String?,
    val algorithm: String?,
    /** The qop options offered, lower-case: `auth`, `auth-int`. Empty when none were. */
    val qop: List<String>,
    val stale: Boolean,
) {
    companion object {
        fun parse(value: String?): SipChallenge? {
            val v = value?.trim() ?: return null
            if (!v.regionMatches(0, "Digest", 0, 6, ignoreCase = true)) return null
            if (v.length > 6 && v[6] != ' ' && v[6] != '\t') return null
            val params = SipText.authParams(v.substring(6)) ?: return null
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val qop = params["qop"]?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() } ?: emptyList()
            return SipChallenge(realm, nonce, params["opaque"], params["algorithm"], qop, params["stale"].equals("true", ignoreCase = true))
        }
    }
}

/** The lexical pieces every header above needs. */
internal object SipText {
    /** A SIP or SIPS URI with something after the scheme, and nothing a header could hide in it. */
    fun isSipUri(s: String): Boolean {
        if (s.length > 1024) return false
        val scheme = when {
            s.regionMatches(0, "sip:", 0, 4, ignoreCase = true) -> 4
            s.regionMatches(0, "sips:", 0, 5, ignoreCase = true) -> 5
            else -> return false
        }
        if (s.length <= scheme) return false
        return s.none { it == ' ' || it == '\t' || it == '\r' || it == '\n' || it == '<' || it == '>' || it == '"' }
    }

    /** The user part of a SIP URI (`sip:user@host` → `user`), or null. */
    fun userOf(uri: String): String? {
        val colon = uri.indexOf(':')
        if (colon < 0) return null
        val at = uri.indexOf('@', colon + 1)
        if (at < 0) return null
        return uri.substring(colon + 1, at).substringBefore(';')
    }

    /** Where [c] first appears outside a quoted string, or -1. */
    fun indexOutsideQuotes(s: String, c: Char): Int {
        var quoted = false
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (quoted && ch == '\\') { i += 2; continue }
            if (ch == '"') quoted = !quoted
            else if (!quoted && ch == c) return i
            i++
        }
        return -1
    }

    /** A comma list's items, the commas inside quotes and angle brackets left alone. Empty items dropped. */
    fun splitList(value: String): List<String> {
        val out = ArrayList<String>()
        var quoted = false
        var angle = false
        var start = 0
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            when {
                quoted && ch == '\\' -> { i += 2; continue }
                ch == '"' -> quoted = !quoted
                !quoted && ch == '<' -> angle = true
                !quoted && ch == '>' -> angle = false
                !quoted && !angle && ch == ',' -> {
                    value.substring(start, i).trim().takeIf { it.isNotEmpty() }?.let { out += it }
                    start = i + 1
                }
            }
            i++
        }
        value.substring(Math.min(start, value.length)).trim().takeIf { it.isNotEmpty() }?.let { out += it }
        return out
    }

    /** `;a=b;c;d="e f"` → {a: b, c: "", d: "e f"}, names lower-case. Null when it is not parameters. */
    fun params(s: String): Map<String, String>? {
        val t = s.trim()
        if (t.isEmpty()) return emptyMap()
        if (t[0] != ';') return null
        val out = LinkedHashMap<String, String>()
        var i = 1
        while (i <= t.length) {
            // One parameter runs to the next semicolon outside quotes.
            var j = i
            var quoted = false
            while (j < t.length) {
                val ch = t[j]
                if (quoted && ch == '\\') { j += 2; continue }
                if (ch == '"') quoted = !quoted
                else if (!quoted && ch == ';') break
                j++
            }
            if (quoted) return null
            val item = t.substring(i, Math.min(j, t.length)).trim()
            if (item.isNotEmpty()) {
                val eq = item.indexOf('=')
                val name = (if (eq < 0) item else item.substring(0, eq)).trim().lowercase()
                if (name.isEmpty() || out.size >= 32) return null
                out[name] = if (eq < 0) "" else unquote(item.substring(eq + 1).trim())
            }
            i = j + 1
        }
        return out
    }

    /** A digest header's `name=value` list (values quoted or not, commas inside quotes kept). */
    fun authParams(s: String): Map<String, String>? {
        val out = LinkedHashMap<String, String>()
        for (item in splitList(s)) {
            val eq = item.indexOf('=')
            if (eq <= 0) return null
            val name = item.substring(0, eq).trim().lowercase()
            if (name.isEmpty() || out.size >= 32) return null
            out[name] = unquote(item.substring(eq + 1).trim())
        }
        return out
    }

    /** A quoted-string's content, its escapes undone; anything else as it is. */
    fun unquote(v: String): String {
        if (v.length < 2 || v[0] != '"' || v[v.length - 1] != '"') return v
        val sb = StringBuilder(v.length)
        var i = 1
        while (i < v.length - 1) {
            val ch = v[i]
            if (ch == '\\' && i + 1 < v.length - 1) { sb.append(v[i + 1]); i += 2; continue }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }
}
