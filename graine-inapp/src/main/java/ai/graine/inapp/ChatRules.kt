package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/*
 * CHAT WINDOW v2, THE RULES THAT ARE NOT DRAWING — protocol.ts "Chat window
 * v2", ported line for line: the replies the agent suggests (`quick_replies`),
 * the customer's rating and the quiet frame it is sent as, which messages are
 * one group and whose time is drawn, the smooth reveal of a reply still
 * arriving, and the little safe Markdown an agent's words may carry. Pure, and
 * pinned by sdk/contract/chat-window.json (ChatWindowTest): the answers every
 * SDK gives. JavaScript's own string rules are kept where they decide an
 * answer — its whitespace, its trim, its code units — so a reply formats the
 * same on every phone.
 */
internal object ChatRules {
    // ── quick replies ───────────────────────────────────────────────────────

    const val QUICK_REPLIES_MAX = 6
    const val QUICK_REPLY_LABEL_MAX = 40
    const val QUICK_REPLY_TEXT_MAX = 200

    /** Control and direction-override characters: drawn as a space, never as what they do. */
    private val CONTROL = Regex("[\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069]")

    /** One line of words as it may be drawn: composed, controls out, spaces collapsed, at most `max` characters (code points). */
    fun line(v: Any?, max: Int): String {
        if (v !is String) return ""
        var s = runCatching { Normalizer.normalize(v, Normalizer.Form.NFC) }.getOrDefault(v)
        s = jsTrim(collapseSpaces(CONTROL.replace(s, " ")))
        val points = s.codePoints().toArray()
        return if (points.size > max) jsTrim(String(points, 0, max)) else s
    }

    /**
     * The replies a `quick_replies` frame suggests, cleaned, in order — or
     * null when it is not one (`replies` is not a list), which is ignored. A
     * reply is a string or `{label, text}` (no text: the label); blanks and
     * repeats (the same label, ignoring case) are left out; at most six.
     */
    fun readQuickReplies(frame: Any?): List<GraineQuickReply>? {
        val list = (frame as? JSONObject)?.opt("replies") as? JSONArray ?: return null
        val out = ArrayList<GraineQuickReply>()
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val raw = list.opt(i)
            val label = line(if (raw is String) raw else (raw as? JSONObject)?.opt("label"), QUICK_REPLY_LABEL_MAX)
            if (label.isEmpty() || label.lowercase() in seen) continue
            val given = if (raw is JSONObject) line(raw.opt("text"), QUICK_REPLY_TEXT_MAX) else ""
            seen.add(label.lowercase())
            out.add(GraineQuickReply(label, given.ifEmpty { label }))
            if (out.size == QUICK_REPLIES_MAX) break
        }
        return out
    }

    // ── the rating ──────────────────────────────────────────────────────────

    /** The app event a rating is recorded as. */
    const val RATING_EVENT = "chat_rating"
    val RATING_STYLES = listOf("thumbs", "stars", "emoji")
    /** What each choice says to a screen reader: thumbs down, up; stars and faces 1–5. */
    val RATING_LABELS: Map<String, List<String>> = mapOf(
        "thumbs" to listOf("Thumbs down", "Thumbs up"),
        "stars" to listOf("1 star", "2 stars", "3 stars", "4 stars", "5 stars"),
        "emoji" to listOf("Very unhappy", "Unhappy", "Neutral", "Happy", "Very happy"),
    )
    /** The faces of the emoji rating, 1–5. Drawn as text: every phone has them. */
    val RATING_EMOJI = listOf("😞", "🙁", "😐", "🙂", "😄")
    /** The line under a rating once it is given. */
    const val RATING_THANKS = "Thanks for your feedback"
    /** The rating card's question when the brand wrote none. */
    const val RATING_PROMPT = "How was this chat?"

    /** A rating as it may be sent: thumbs 0 or 1 out of 1, stars and emoji 1–5 out of 5. A style this build does not know is thumbs. */
    fun rating(style: Any?, value: Any?): GraineChatRating {
        val s = if (style == "stars" || style == "emoji") style as String else "thumbs"
        val n = (value as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: 0.0
        if (s == "thumbs") return GraineChatRating(if (n >= 1) 1 else 0, 1, s)
        return GraineChatRating(Js.round(n).coerceIn(1.0, 5.0).toInt(), 5, s)
    }

    /** The words the conversation's record carries — plain, so a person reading it understands. */
    fun ratingDetail(r: GraineChatRating): String {
        val x = rating(r.style, r.value)
        if (x.style == "thumbs") return if (x.value != 0) "The customer gave this chat a thumbs up." else "The customer gave this chat a thumbs down."
        return "The customer rated this chat ${x.value} out of ${x.scale}."
    }

    /** The frame a rating is sent as: a quiet app event — recorded on the conversation, never a nudge to speak. */
    fun ratingFrame(r: GraineChatRating): JSONObject {
        val x = rating(r.style, r.value)
        return JSONObject().put("type", "app_event").put("event", JSONObject()
            .put("name", RATING_EVENT)
            .put("quiet", true)
            .put("detail", ratingDetail(x))
            .put("data", JSONObject().put("value", x.value).put("scale", x.scale).put("style", x.style)))
    }

    /** What the choice `value` of a style says to a screen reader. */
    fun ratingLabel(style: String, value: Int): String {
        val labels = RATING_LABELS[style] ?: RATING_LABELS.getValue("thumbs")
        return if (style == "thumbs") labels[if (value >= 1) 1 else 0] else labels[(value - 1).coerceIn(0, 4)]
    }

    // ── groups ──────────────────────────────────────────────────────────────

    /** Two messages further apart than this are two groups. */
    const val GROUP_GAP_MS = 120_000L

    const val SINGLE = "single"
    const val FIRST = "first"
    const val MIDDLE = "middle"
    const val LAST = "last"

    /** One row as grouping sees it: who said it and when — or a row that ends any group (a day line, a call row, a component). */
    data class GroupItem(val role: String? = null, val at: Long? = null, val breaks: Boolean = false)

    /**
     * Where each row sits in its group, in the same order. Messages in a row
     * from one side, each within two minutes of the one before, are ONE group;
     * a turn with no time joins the group before it; anything that breaks is
     * "single".
     */
    fun groups(items: List<GroupItem>): List<String> {
        val out = MutableList(items.size) { SINGLE }
        var start = -1
        fun close(end: Int) {
            if (start < 0) return
            if (end > start) {
                out[start] = FIRST
                for (i in start + 1 until end) out[i] = MIDDLE
                out[end] = LAST
            }
            start = -1
        }
        var prevAt: Long? = null
        items.forEachIndexed { i, item ->
            val groupable = !item.breaks && (item.role == "agent" || item.role == "customer")
            if (!groupable) {
                close(i - 1)
                prevAt = null
                return@forEachIndexed
            }
            val at = item.at
            val prev = if (i > 0) items[i - 1] else null
            val p = prevAt
            val joins = start >= 0 && prev != null && !prev.breaks && prev.role == item.role &&
                (at == null || p == null || Math.abs(at - p) <= GROUP_GAP_MS)
            if (!joins) {
                close(i - 1)
                start = i
            }
            if (at != null) prevAt = at
        }
        close(items.size - 1)
        return out
    }

    /** Whether a row's time is drawn: every message for "always", the last of each group for "group", none for "never". */
    fun showsTime(show: String, position: String): Boolean = when (show) {
        "always" -> true
        "group" -> position == SINGLE || position == LAST
        else -> false
    }

    // ── the reveal ──────────────────────────────────────────────────────────

    const val REVEAL_MIN_CPS = 90.0
    const val REVEAL_CATCH_UP_MS = 600.0

    /**
     * How much of a reply still arriving to show after `elapsedMs` more, from
     * `shown` toward `target` characters: at least 90 a second, faster when
     * behind, never more than about 0.6 s behind what has arrived.
     */
    fun revealLength(shown: Double, target: Double, elapsedMs: Double): Int {
        val goal = Math.max(0.0, Math.floor(target))
        val now = Math.max(0.0, Math.min(goal, Math.floor(shown)))
        if (now >= goal || !(elapsedMs > 0)) return now.toInt()
        val cps = Math.max(REVEAL_MIN_CPS, ((goal - now) * 1000) / REVEAL_CATCH_UP_MS)
        return Math.min(goal, now + Math.max(1.0, Math.floor((cps * elapsedMs) / 1000))).toInt()
    }

    /** The first `length` UTF-16 units of `text`, never splitting a surrogate pair (an emoji drawn half). */
    fun revealSlice(text: String, length: Int): String {
        var n = Math.max(0, Math.min(text.length, length))
        if (n > 0 && n < text.length && Character.isHighSurrogate(text[n - 1])) n += 1
        return text.substring(0, n)
    }

    // ── safe formatting ─────────────────────────────────────────────────────
    //
    // The little Markdown the agent's words may carry, and nothing else:
    // **bold** (or __bold__), *italic* (or _italic_), `code`, [links](https://…)
    // and bare https:// links, "- ", "* " or "• " bullets, "1. " / "1) " items,
    // a "# heading" (a bold line), and line breaks. Never HTML: text stays
    // text. A link is only ever http(s), mailto or tel — anything else is its
    // label alone. While a reply is still arriving (`live`), a style whose
    // closing mark has not come yet already applies to the words after it,
    // and a lone mark at the very end is not drawn.

    /** One run of words with one look. `link` is the address a tap opens: http(s), mailto or tel only. */
    data class Span(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val code: Boolean = false,
        val link: String? = null,
    )

    /** A paragraph, a bullet, or a numbered item ([n]). */
    data class Block(val kind: String, val spans: List<Span>, val n: Int = 0) {
        companion object {
            const val PARAGRAPH = "paragraph"
            const val BULLET = "bullet"
            const val NUMBER = "number"
        }
    }

    private const val ESCAPABLE = "\\`*_[]()#+-.!>~|"
    private const val URL_TAIL = ".,!?:;'\""
    private val LINK_SCHEME = Regex("^(https?://|mailto:|tel:)", RegexOption.IGNORE_CASE)
    private val BARE_URL_START = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val BARE_URL = Regex("^https?://[^/\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]+", RegexOption.IGNORE_CASE)

    private data class Style(val bold: Boolean = false, val italic: Boolean = false)

    /** JavaScript's `\s`: its whitespace and line terminators. Null (past either end) counts as a space, as an undefined character does there. */
    internal fun isJsSpace(c: Char?): Boolean = c == null || c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' ||
        c == ' ' || c == ' ' || c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' ||
        c == ' ' || c == '　' || c == '﻿'

    /** `/[\p{L}\p{N}_]/u` on one UTF-16 unit: a letter, a number or an underscore. */
    private fun isWord(c: Char?): Boolean {
        if (c == null) return false
        if (c == '_') return true
        return when (Character.getType(c).toByte()) {
            Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
            Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER, Character.OTHER_NUMBER -> true
            else -> false
        }
    }

    /** JavaScript's trim(): its whitespace, from both ends. */
    internal fun jsTrim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isJsSpace(s[a])) a++
        while (b > a && isJsSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    private fun jsTrimEnd(s: String): String {
        var b = s.length
        while (b > 0 && isJsSpace(s[b - 1])) b--
        return s.substring(0, b)
    }

    /** `.replace(/\s+/g, " ")`. */
    private fun collapseSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        var inSpace = false
        for (c in s) {
            if (isJsSpace(c)) { if (!inSpace) sb.append(' '); inSpace = true } else { sb.append(c); inSpace = false }
        }
        return sb.toString()
    }

    private fun at(s: String, i: Int): Char? = if (i in s.indices) s[i] else null

    /** The inline spans of one paragraph's text. */
    private fun inline(text: String, style: Style, live: Boolean): List<Span> {
        val spans = ArrayList<Span>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) spans.add(Span(buf.toString(), style.bold, style.italic))
            buf.setLength(0)
        }
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            // An escaped mark is the mark itself.
            if (c == '\\' && i + 1 < n && ESCAPABLE.indexOf(text[i + 1]) >= 0) {
                buf.append(text[i + 1])
                i += 2
                continue
            }
            // `code`, on one line, nothing styled inside it.
            if (c == '`') {
                val j = text.indexOf('`', i + 1)
                val end = if (j > i + 1 && text.substring(i + 1, j).indexOf('\n') < 0) j
                else if (live && j < 0 && i + 1 < n && text.substring(i + 1).indexOf('\n') < 0) n else -1
                if (end > 0) {
                    flush()
                    spans.add(Span(text.substring(i + 1, end), style.bold, style.italic, code = true))
                    i = if (end == n) n else end + 1
                    continue
                }
            }
            // [label](address)
            if (c == '[') {
                val close = text.indexOf(']', i + 1)
                if (close > i + 1 && at(text, close + 1) == '(' && text.substring(i + 1, close).indexOf('\n') < 0) {
                    // The address runs to the bracket that closes it — (balanced) brackets inside it are its own.
                    var end = -1
                    var j = close + 2
                    var depth = 0
                    while (j < n && !isJsSpace(text[j])) {
                        if (text[j] == '(') depth++
                        else if (text[j] == ')') { if (depth == 0) { end = j; break }; depth-- }
                        j++
                    }
                    val url = if (end > close + 2) text.substring(close + 2, end) else ""
                    if (url.isNotEmpty() && url.none { isJsSpace(it) }) {
                        flush()
                        val label = text.substring(i + 1, close)
                        spans.add(Span(label, style.bold, style.italic, link = if (LINK_SCHEME.containsMatchIn(url)) url else null))
                        i = end + 1
                        continue
                    }
                }
            }
            // A bare http(s) address.
            if ((c == 'h' || c == 'H') && !isWord(at(text, i - 1)) && BARE_URL_START.containsMatchIn(text.substring(i, Math.min(n, i + 8)))) {
                var j = i
                while (j < n && !isJsSpace(text[j])) j++
                var url = text.substring(i, j)
                while (url.isNotEmpty() && (URL_TAIL.indexOf(url[url.length - 1]) >= 0 || (url.endsWith(")") && !url.contains("(")))) url = url.substring(0, url.length - 1)
                if (BARE_URL.containsMatchIn(url)) {
                    flush()
                    spans.add(Span(url, style.bold, style.italic, link = url))
                    i += url.length
                    continue
                }
            }
            // **bold** / __bold__
            if ((c == '*' || c == '_') && at(text, i + 1) == c) {
                val mark = "$c$c"
                // Never inside a word (2**3, snake__case): a mark opens after a non-word and closes before one.
                val opens = !isJsSpace(at(text, i + 2)) && at(text, i + 2) != c && !isWord(at(text, i - 1))
                if (opens) {
                    var j = i + 2
                    var found = -1
                    while (j < n - 1) {
                        if (text[j] == '\n') break
                        if (text[j] == c && text[j + 1] == c && !isJsSpace(at(text, j - 1)) && !isWord(at(text, j + 2))) { found = j; break }
                        j++
                    }
                    val end = if (found >= 0) found else if (live && text.substring(i + 2).indexOf('\n') < 0) n else -1
                    if (end > i + 2) {
                        flush()
                        spans.addAll(inline(text.substring(i + 2, end), style.copy(bold = true), live && found < 0))
                        i = if (end == n) n else end + 2
                        continue
                    }
                }
                buf.append(mark)
                i += 2
                continue
            }
            // *italic* / _italic_
            if (c == '*' || c == '_') {
                val opens = !isJsSpace(at(text, i + 1)) && !isWord(at(text, i - 1))
                if (opens) {
                    var j = i + 1
                    var found = -1
                    while (j < n) {
                        if (text[j] == '\n') break
                        if (text[j] == c) {
                            if (at(text, j + 1) == c) { j += 2; continue }
                            if (!isJsSpace(at(text, j - 1)) && !isWord(at(text, j + 1))) { found = j; break }
                        }
                        j++
                    }
                    val end = if (found >= 0) found else if (live && text.substring(i + 1).indexOf('\n') < 0) n else -1
                    if (end > i + 1) {
                        flush()
                        spans.addAll(inline(text.substring(i + 1, end), style.copy(italic = true), live && found < 0))
                        i = if (end == n) n else end + 1
                        continue
                    }
                }
            }
            buf.append(c)
            i++
        }
        flush()
        return spans
    }

    /** Spans side by side with the same look are one span; empty ones go. */
    private fun merge(spans: List<Span>): List<Span> {
        val out = ArrayList<Span>()
        for (s in spans) {
            if (s.text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && last.bold == s.bold && last.italic == s.italic && last.code == s.code && last.link == s.link) {
                out[out.size - 1] = last.copy(text = last.text + s.text)
            } else out.add(s)
        }
        return out
    }

    /** `^ {0,3}` then `rest` when the line has at most three spaces before it: the index past them, else -1. */
    private fun indent(line: String): Int {
        var k = 0
        while (k < line.length && line[k] == ' ') k++
        return if (k <= 3) k else -1
    }

    /** `[ \t]+(\S.*)$` from `from`: the words after the gap, or null. `.` never crosses a line terminator. */
    private fun afterGap(line: String, from: Int): String? {
        var k = from
        if (k >= line.length || (line[k] != ' ' && line[k] != '\t')) return null
        while (k < line.length && (line[k] == ' ' || line[k] == '\t')) k++
        if (k >= line.length || isJsSpace(line[k])) return null
        val rest = line.substring(k)
        return if (rest.indexOf(' ') >= 0 || rest.indexOf(' ') >= 0) null else rest
    }

    /** `^ {0,3}[-*•][ \t]+(\S.*)$`. */
    private fun bullet(line: String): String? {
        val k = indent(line)
        if (k < 0 || k >= line.length || line[k] !in "-*•") return null
        return afterGap(line, k + 1)
    }

    /** `^ {0,3}(\d{1,3})[.)][ \t]+(\S.*)$`: the number and the words. */
    private fun numbered(line: String): Pair<Int, String>? {
        val k = indent(line)
        if (k < 0) return null
        var d = k
        while (d < line.length && line[d] in '0'..'9' && d - k < 4) d++
        val digits = d - k
        if (digits < 1 || digits > 3 || d >= line.length || (line[d] != '.' && line[d] != ')')) return null
        val words = afterGap(line, d + 1) ?: return null
        return line.substring(k, d).toInt() to words
    }

    /** `^ {0,3}#{1,6}[ \t]+(\S.*)$`. */
    private fun heading(line: String): String? {
        val k = indent(line)
        if (k < 0) return null
        var h = k
        while (h < line.length && line[h] == '#') h++
        if (h - k < 1 || h - k > 6) return null
        return afterGap(line, h)
    }

    /** `.replace(/[ \t]+#+[ \t]*$/, "")` — a heading's closing hashes. */
    private fun dropClosingHashes(s: String): String {
        var e = s.length
        while (e > 0 && (s[e - 1] == ' ' || s[e - 1] == '\t')) e--
        var h = e
        while (h > 0 && s[h - 1] == '#') h--
        if (h == e) return s
        var g = h
        while (g > 0 && (s[g - 1] == ' ' || s[g - 1] == '\t')) g--
        return if (g == h) s else s.substring(0, g)
    }

    /** The blocks of an agent's words, formatted. Never throws; plain words are one paragraph. */
    fun markdown(text: String?, live: Boolean = false): List<Block> {
        var source = (text ?: "").replace("\r\n", "\n").replace('\r', '\n')
        // A lone mark at the very end of a reply still arriving is the start of something not yet here.
        if (live) {
            var e = source.length
            while (e > 0 && source[e - 1] in "*_`[") e--
            source = source.substring(0, e)
        }
        val blocks = ArrayList<Block>()
        val para = ArrayList<String>()
        fun endPara() {
            if (para.isNotEmpty()) {
                val spans = merge(inline(para.joinToString("\n"), Style(), live))
                if (spans.isNotEmpty()) blocks.add(Block(Block.PARAGRAPH, spans))
            }
            para.clear()
        }
        for (line in source.split('\n')) {
            if (jsTrim(line).isEmpty()) { endPara(); continue }
            val bulletWords = bullet(line)
            if (bulletWords != null) {
                endPara()
                val spans = merge(inline(jsTrimEnd(bulletWords), Style(), live))
                if (spans.isNotEmpty()) blocks.add(Block(Block.BULLET, spans))
                continue
            }
            val item = numbered(line)
            if (item != null) {
                endPara()
                val spans = merge(inline(jsTrimEnd(item.second), Style(), live))
                if (spans.isNotEmpty()) blocks.add(Block(Block.NUMBER, spans, item.first))
                continue
            }
            val title = heading(line)
            if (title != null) {
                endPara()
                val spans = merge(inline(jsTrimEnd(dropClosingHashes(title)), Style(bold = true), live))
                if (spans.isNotEmpty()) blocks.add(Block(Block.PARAGRAPH, spans))
                continue
            }
            para.add(jsTrim(line))
        }
        endPara()
        return blocks
    }

    /** The plain words of formatted blocks — what a long-press copies and a screen reader reads: one line per block, "• " and "1. " kept. */
    fun markdownText(blocks: List<Block>): String = blocks.joinToString("\n") { b ->
        val words = b.spans.joinToString("") { it.text }
        when (b.kind) {
            Block.BULLET -> "• $words"
            Block.NUMBER -> "${b.n}. $words"
            else -> words
        }
    }
}
