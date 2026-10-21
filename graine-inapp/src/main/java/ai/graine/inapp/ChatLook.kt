package ai.graine.inapp

/**
 * THE CHAT WINDOW v2'S RULES, AS NUMBERS AND WORDS — contract/DESIGN.md "Chat
 * window v2", element by element: which corners of a bubble are tight and
 * where its tail goes, whose time is drawn, where a new conversation's marker
 * row goes, what the header's status line says, when the reconnect banner
 * shows, which composer is drawn and which buttons it carries, where the
 * chips go, which welcome stands at the top, when the rating is asked, what
 * the scroll-to-latest pill says, how the typing dots move. Pure, as
 * [VoiceBar] is: no view, no context — every decision is proven on the JVM
 * (ChatLookTest), and [ChatWindowView] only draws what these say. Lengths are
 * dp, text sizes sp.
 */
internal object ChatLook {
    // ── sizes ──────────────────────────────────────────────────────────────
    const val HEADER = 50.0
    const val AVATAR_START = 30.0
    /** The avatar in a centred header. */
    const val AVATAR_CENTER = 24.0
    /** A centred title group keeps this clear of the header's controls. */
    const val CENTER_CLEAR = 8.0
    /** Every icon button's tap target. */
    const val TARGET = 44.0
    const val CONTEXT_BAR = 52.0
    const val CONTEXT_THUMB = 32.0
    const val CONTEXT_CARD_THUMB = 44.0
    const val BADGE_HEIGHT = 22.0
    const val BADGE_PAD = 8.0
    const val BANNER = 28.0
    const val BANNER_LOADER = 12
    const val BANNER_DOT = 6.0
    /** The tail lies outside the bubble, in the margin, this wide. */
    const val TAIL = 8.0
    const val TIME_BELOW_GAP = 4.0
    const val TIME_INSIDE_GAP = 2.0
    const val TICK = 12.0
    const val TICK_GAP = 4.0
    const val STATUS_ICON = 14.0
    const val TYPING_DOT = 7.0
    const val TYPING_DOT_GAP = 5.0
    const val CHIP_PAD = 14.0
    const val CHIP_GAP = 8.0
    /** Docked chips: from the edge, and above the composer. */
    const val CHIP_EDGE = 12.0
    const val CHIP_ABOVE = 8.0
    /** Quick replies in the thread sit this far under the turn they follow. */
    const val REPLIES_BELOW = 8.0
    const val LIST_ROW = 44.0
    const val LIST_ROW_PAD = 14.0
    const val LIST_GAP = 8.0
    const val LIST_CHEVRON = 16.0
    const val STRIP_BAR = 58.0
    const val STRIP_PILL = 60.0
    const val SEND_BAR = 40.0
    const val SEND_INSIDE = 36.0
    const val SEND_ICON_ONLY = 22.0
    /** The send button inside a pill sits this far in; inside a floating capsule, [SEND_INSET_FLOATING]. */
    const val SEND_INSET_PILL = 4.0
    const val SEND_INSET_FLOATING = 6.0
    const val SIDE_ICON = 20.0
    const val SIDE_TARGET = 40.0
    /** A floating composer: this far above the window's bottom and in from its sides. */
    const val FLOATING_INSET = 10.0
    const val WELCOME_CARD_AVATAR = 40.0
    const val WELCOME_CARD_TITLE_MAX = 17.0
    const val WELCOME_CENTERED_AVATAR = 48.0
    const val WELCOME_CENTERED_TITLE_MAX = 18.0
    const val HERO_AVATAR = 56.0
    const val HERO_PAD = 24.0
    const val HERO_TITLE_MAX = 26.0
    const val HERO_AFTER = 16.0
    /** The rating card is [RATING_SHARE] of the thread, at most this wide — more of the thread where its choices need it ([ratingLayout]). */
    const val RATING_MAX = 300.0
    const val RATING_SHARE = 0.84
    /** Between two of the rating's choices. */
    const val RATING_GAP = 8.0
    /** A chosen face, a little larger than the others. */
    const val FACE_CHOSEN_SCALE = 1.15f
    const val PILL = 36.0
    const val PILL_ICON = 16.0
    const val PILL_ABOVE = 8.0
    /** The thread follows new rows while it is within this of the bottom. */
    const val FOLLOW_WITHIN = 120.0
    const val CODE_CORNERS = 4.0
    const val PARAGRAPH_GAP = 6.0
    const val MARKER_GAP = 8.0
    /** A message still waiting for a connection. */
    const val WAITING_ALPHA = 0.6f

    // ── words ──────────────────────────────────────────────────────────────
    const val TYPING = "Typing…"
    const val WAITING = "Waiting for connection…"
    const val NOT_SENT = "Not sent · Tap to retry"
    const val RECONNECTING = "Reconnecting…"
    const val OFFLINE = "Offline · messages send when you're back"
    /** Graine ended the conversation itself (idle, or its time limit): the header's status line until it connects again. */
    const val DISCONNECTED = "Disconnected"
    /** The row where a new conversation began under earlier lines, and what a screen reader says for it. */
    const val FRESH = "Disconnected · new conversation"
    const val FRESH_SPOKEN = "Disconnected. A new conversation started."
    const val SCROLL_TO_LATEST = "Scroll to the latest message"
    const val COPIED = "Copied"
    const val COPY = "Copy"
    const val RETRY = "Retry"
    const val ATTACH = "Attach"
    const val SEND = "Send"

    fun typingWords(t: ChatStyleTyping): String = t.text.ifEmpty { TYPING }
    fun ratingPrompt(r: ChatStyleRating): String = r.prompt.ifEmpty { ChatRules.RATING_PROMPT }
    fun callLabel(name: String): String = "Call $name"
    fun typingSpoken(name: String): String = "$name is typing"

    // ── bubbles ────────────────────────────────────────────────────────────

    /** A bubble's four corners, physical (dp). */
    data class Corners(val topLeft: Double, val topRight: Double, val bottomRight: Double, val bottomLeft: Double)

    /**
     * The speaker's side is the trailing side for the customer and the leading
     * side for the agent — so in a right-to-left language both flip. True when
     * that side is the right.
     */
    fun speakerOnRight(customer: Boolean, rtl: Boolean): Boolean = customer != rtl

    /**
     * A bubble's corners at its place in a group (always "single" without
     * grouping):
     *   rounded   every corner `radius`; the bottom speaker-side corner always
     *             `tightRadius` (v1), the top one too for middle and last
     *   squircle  every corner `radius`; in a group the top speaker-side corner
     *             tight for middle and last, the bottom one for first and middle
     *   tail      as squircle; on single and last the bottom speaker-side corner
     *             is square and carries the tail
     *   flat      every corner `radius`
     */
    fun corners(b: ChatStyleBubbles, position: String, customer: Boolean, rtl: Boolean): Corners {
        val p = if (b.grouping) position else ChatRules.SINGLE
        val r = b.radius
        val tight = b.tightRadius
        val joinedAbove = p == ChatRules.MIDDLE || p == ChatRules.LAST
        val joinedBelow = p == ChatRules.FIRST || p == ChatRules.MIDDLE
        var topSpeaker = r
        var bottomSpeaker = r
        when (b.shape) {
            "flat" -> Unit
            "squircle", "tail" -> {
                if (joinedAbove) topSpeaker = tight
                if (joinedBelow) bottomSpeaker = tight
                if (b.shape == "tail" && tail(b, position)) bottomSpeaker = 0.0
            }
            else -> {
                if (joinedAbove) topSpeaker = tight
                bottomSpeaker = tight
            }
        }
        return if (speakerOnRight(customer, rtl)) Corners(r, topSpeaker, bottomSpeaker, r) else Corners(topSpeaker, r, r, bottomSpeaker)
    }

    /** The tail: `tail` bubbles, on a single bubble or the last of a group. */
    fun tail(b: ChatStyleBubbles, position: String): Boolean {
        if (b.shape != "tail") return false
        val p = if (b.grouping) position else ChatRules.SINGLE
        return p == ChatRules.SINGLE || p == ChatRules.LAST
    }

    /** One step of the tail's closed path, in the bubble's own box (dp, the box W × H, the tail outside it). */
    data class PathStep(val op: Char, val values: List<Double>)

    /**
     * The tail on the right (the customer's, in a left-to-right language),
     * with the box's bottom-right corner at (W, H):
     * M W,H−12 · Q W+1,H−2 W+8,H · L W−12,H · Z — and on the left the same
     * mirrored at the bottom-left corner: M 0,H−12 · Q −1,H−2 −8,H · L 12,H · Z.
     */
    fun tailPath(w: Double, h: Double, right: Boolean): List<PathStep> = if (right) listOf(
        PathStep('M', listOf(w, h - 12)), PathStep('Q', listOf(w + 1, h - 2, w + 8, h)), PathStep('L', listOf(w - 12, h)), PathStep('Z', emptyList()),
    ) else listOf(
        PathStep('M', listOf(0.0, h - 12)), PathStep('Q', listOf(-1.0, h - 2, -8.0, h)), PathStep('L', listOf(12.0, h)), PathStep('Z', emptyList()),
    )

    /**
     * Each row's place in its group, as [ChatRules.groups] reckons it: a day
     * line, a call row or a marker row before a row ([fresh], [freshAt]) breaks
     * a group; a message is its speaker and its time.
     */
    fun positions(rows: List<ChatRows.Row>, fresh: Set<Int> = emptySet()): List<String> {
        val items = ArrayList<ChatRules.GroupItem>()
        val at = IntArray(rows.size)
        rows.forEachIndexed { i, row ->
            if (i in fresh) items.add(ChatRules.GroupItem(breaks = true))
            at[i] = items.size
            items.add(when (row) {
                is ChatRows.Row.Turn -> ChatRules.GroupItem(if (row.turn.role == Role.AGENT) "agent" else "customer", row.turn.at.time)
                else -> ChatRules.GroupItem(breaks = true)
            })
        }
        val groups = ChatRules.groups(items)
        return rows.indices.map { groups[at[it]] }
    }

    /**
     * Where "Disconnected · new conversation" rows go among [rows] (built from
     * the [turns] that draw a bubble) — contract/DESIGN.md → A new
     * conversation under earlier lines: before the bubble of each [marked]
     * turn, so after any day line of its own; for a marked turn that draws no
     * bubble (a component alone), before the next turn that does. Indices into
     * [rows]; [rows].size for one still waiting for a bubble, at the end.
     */
    fun freshAt(rows: List<ChatRows.Row>, turns: List<GraineChatTurn>, marked: Set<String>): Set<Int> {
        if (marked.isEmpty()) return emptySet()
        val before = HashSet<String>()
        var due = false
        for (turn in turns) {
            if (turn.id in marked) due = true
            if (due && turn.text.isNotEmpty()) { before.add(turn.id); due = false }
        }
        val out = HashSet<Int>()
        rows.forEachIndexed { i, row -> if (row is ChatRows.Row.Turn && row.turn.id in before) out.add(i) }
        if (due) out.add(rows.size)
        return out
    }

    /** The space above a row: [ChatStyleDensity.groupGap] inside a group, [ChatStyleDensity.gap] between anything else. */
    fun gapAbove(d: ChatStyleDensity, position: String, grouping: Boolean): Double =
        if (grouping && (position == ChatRules.MIDDLE || position == ChatRules.LAST)) d.groupGap else d.gap

    /** A bubble's padding: `density.padY` × `padX`, or 2 × 2 for no bubble at all. */
    fun padding(d: ChatStyleDensity, bare: Boolean): Pair<Double, Double> = if (bare) 2.0 to 2.0 else d.padX to d.padY

    // ── times and status ───────────────────────────────────────────────────

    /** Where a message's time is drawn: "below", "inside" — or null for none. A bare bubble's inside time goes below. */
    fun timePlace(ts: ChatStyleTimestamps, position: String, grouping: Boolean, bare: Boolean): String? {
        val p = if (grouping) position else ChatRules.SINGLE
        if (!ChatRules.showsTime(ts.show, p)) return null
        return if (ts.style == "inside" && !bare) "inside" else "below"
    }

    /** The mark beside a customer's time, with ticks on: waiting, sent, not sent. Null: none. */
    fun tick(ts: ChatStyleTimestamps, customer: Boolean, delivery: GraineChatTurn.Delivery): String? {
        if (!ts.ticks || !customer) return null
        return when (delivery) {
            GraineChatTurn.Delivery.SENDING -> SdkIcons.CLOCK
            GraineChatTurn.Delivery.FAILED -> SdkIcons.ALERT
            GraineChatTurn.Delivery.SENT -> SdkIcons.CHECK
        }
    }

    /** What a screen reader says for a bubble: "{name}: {words}" or "You: {words}", then its time and its status. */
    fun spoken(name: String, customer: Boolean, words: String, time: String?, delivery: GraineChatTurn.Delivery): String {
        val sb = StringBuilder(if (customer) "You" else name).append(": ").append(words)
        if (!time.isNullOrEmpty()) sb.append(", ").append(time)
        if (customer && delivery == GraineChatTurn.Delivery.SENDING) sb.append(", waiting to send")
        if (customer && delivery == GraineChatTurn.Delivery.FAILED) sb.append(", not sent. Double-tap to retry.")
        return sb.toString()
    }

    // ── the header ─────────────────────────────────────────────────────────

    enum class StatusKind { CONNECTING, RECONNECTING, FAILED, DISCONNECTED, TYPING, ONLINE, TEXT, NONE }

    /** The status line: what it says, and whether the dot leads it. */
    data class Status(val kind: StatusKind, val words: String?, val dot: Boolean)

    /**
     * Under the title, first match wins: nothing could connect (tap to retry);
     * reconnecting; Graine ended the conversation itself and nothing is
     * connecting ([disconnected]) — "Disconnected", until opening the chat or
     * a message connects again; connecting (the loader's connecting words);
     * the agent typing, where the design says so; `none` — no line; `online` —
     * the dot, then the subtitle or "Online"; `text` — the words alone. An
     * ended conversation the server has let go of says its words without the
     * dot: the next message opens a new one.
     */
    fun status(
        h: ChatStyleHeader, subtitle: String, typingWords: String, online: Boolean, connecting: Boolean, reconnecting: Boolean,
        failed: Boolean, typing: Boolean, ended: Boolean, connectingWords: String, disconnected: Boolean = false,
    ): Status {
        val words = subtitle.ifEmpty { "Online" }
        return when {
            !online && failed -> Status(StatusKind.FAILED, ChatWindowView.FAILED_TEXT, false)
            reconnecting -> Status(StatusKind.RECONNECTING, RECONNECTING, false)
            !online && !connecting && disconnected -> Status(StatusKind.DISCONNECTED, DISCONNECTED, false)
            !online && (connecting || !ended) -> Status(StatusKind.CONNECTING, connectingWords, false)
            typing && h.typingStatus -> Status(StatusKind.TYPING, typingWords, false)
            h.status == "none" -> Status(StatusKind.NONE, null, false)
            h.status == "online" && online -> Status(StatusKind.ONLINE, words, true)
            else -> Status(StatusKind.TEXT, words, false)
        }
    }

    /** The header's call button: the design asks for it there, and the agent takes calls this SDK can start from the chat. */
    fun headerCall(s: ChatStyle, canCall: Boolean): Boolean = canCall && s.escalation.call == "header"

    // ── the reconnect banner ───────────────────────────────────────────────

    enum class Banner { NONE, RECONNECTING, OFFLINE }

    /**
     * A calm strip under the header: while a conversation that was connected
     * reconnects, or while it is offline with a message waiting — never during
     * a first connect, never for a conversation that simply is not open.
     */
    fun banner(online: Boolean, connecting: Boolean, reconnecting: Boolean, everConnected: Boolean, waiting: Boolean): Banner = when {
        online -> Banner.NONE
        reconnecting && everConnected -> Banner.RECONNECTING
        waiting && (reconnecting || !connecting) -> Banner.OFFLINE
        else -> Banner.NONE
    }

    // ── the context card ───────────────────────────────────────────────────

    /** Where the app's pinned card goes: "bar", "card" — null when nothing is pinned or the design hides it. */
    fun pin(pin: GraineChatPin?, s: ChatStyle): String? = if (pin == null || s.header.contextCard == "none") null else s.header.contextCard

    /** What a screen reader says for it: "Pinned: {title}, {subtitle}, {badge}". */
    fun pinSpoken(p: GraineChatPin): String = listOfNotNull(p.title, p.subtitle, p.badge).joinToString(", ", prefix = "Pinned: ")

    // ── the composer ───────────────────────────────────────────────────────

    /** The composer drawn: its style and the buttons it carries. */
    data class Composer(val style: String, val attach: Boolean, val call: Boolean, val mic: Boolean, val send: String)

    /**
     * `composer.mic` only where the agent takes calls this SDK can start; the
     * paperclip only where the app takes attachments; the composer's call
     * button only where it may call and the microphone is not already there.
     */
    fun composer(s: ChatStyle, canCall: Boolean, canAttach: Boolean): Composer {
        val mic = s.composer.mic && canCall
        return Composer(
            style = s.composer.style,
            attach = s.composer.attach && canAttach,
            call = canCall && s.escalation.call == "composer" && !mic,
            mic = mic,
            send = s.composer.send,
        )
    }

    /** What the send button is now: the microphone while the field is empty (it calls), else send — dimmed while there is nothing to send. */
    data class Send(val icon: String, val label: String, val calls: Boolean, val enabled: Boolean)

    fun send(c: Composer, empty: Boolean, name: String): Send = when {
        c.mic && empty -> Send(SdkIcons.MIC, callLabel(name), calls = true, enabled = true)
        else -> Send(SdkIcons.SEND, SEND, calls = false, enabled = !empty)
    }

    /** The composer's strip height: a bar's 58, a pill's 60, none for a floating capsule. */
    fun strip(style: String): Double = when (style) { "pill" -> STRIP_PILL; "floating" -> 0.0; else -> STRIP_BAR }

    /** The send button's side: 40 on a bar, 36 inside a pill or a capsule; the icon-only button is a 44 target. */
    fun sendSide(c: Composer): Double = if (c.send == "icon") TARGET else if (c.style == "bar") SEND_BAR else SEND_INSIDE

    /** The send button's corners: round, `min(radius.md, 12)`, or none. Null: round. */
    fun sendCorners(send: String, md: Double): Double? = if (send == "rounded") minOf(md, 12.0) else null

    // ── chips ──────────────────────────────────────────────────────────────

    /** Where the starters and the quick replies go. */
    data class Chips(
        val starters: List<String>,
        /** Docked above the composer, on the customer's side. */
        val startersDocked: Boolean,
        /** In the thread, at the end of the welcome. */
        val startersInThread: Boolean,
        /** As full-width rows, not chips. */
        val startersAsList: Boolean,
        val replies: List<GraineQuickReply>,
        val repliesDocked: Boolean,
        /** In the thread, under the agent's turn they follow, on its side. */
        val repliesInThread: Boolean,
    )

    /**
     * `both` — starters docked, quick replies under their turn; `inline` —
     * starters at the end of the welcome, quick replies under their turn;
     * `above-composer` — both docked, the quick replies taking the starters'
     * place. Starters as a list are always in the thread. The starters show
     * while the customer has said nothing ([offersStarters]).
     */
    fun chips(s: ChatStyle, suggestions: List<String>, offersStarters: Boolean, replies: List<GraineQuickReply>): Chips {
        val starters = if (offersStarters) suggestions else emptyList()
        val list = s.welcome.starters == "list"
        val placement = s.chips.placement
        val repliesDocked = placement == "above-composer" && replies.isNotEmpty()
        return Chips(
            starters = starters,
            startersDocked = starters.isNotEmpty() && !list && placement != "inline" && !repliesDocked,
            startersInThread = starters.isNotEmpty() && (list || placement == "inline"),
            startersAsList = list,
            replies = replies,
            repliesDocked = repliesDocked,
            repliesInThread = replies.isNotEmpty() && placement != "above-composer",
        )
    }

    // ── the welcome ────────────────────────────────────────────────────────

    /**
     * The welcome a new conversation opens on (none on a conversation that
     * continues — its history is there to read): `centered` — the empty chat,
     * before any message (v1); `card` and `hero` — first in the thread, staying
     * at the top as the conversation grows; `minimal` — none.
     */
    fun welcome(s: ChatStyle, anyRow: Boolean, restored: Boolean): String? = when {
        restored -> null
        s.welcome.layout == "centered" -> if (anyRow) null else "centered"
        s.welcome.layout == "card" || s.welcome.layout == "hero" -> s.welcome.layout
        else -> null
    }

    // ── the rating ─────────────────────────────────────────────────────────

    /** The rating card: the agent ended the conversation and the design asks. */
    fun rating(s: ChatStyle, ended: Boolean): Boolean = ended && s.rating.show

    /** The choices a style offers, in order: thumbs down then up (0, 1); stars and faces 1–5. */
    fun ratingValues(style: String): List<Int> = if (style == "thumbs") listOf(1, 0) else listOf(1, 2, 3, 4, 5)

    /** Whether a choice is drawn chosen: thumbs exactly; stars up to the chosen one; a face exactly. */
    fun ratingLit(style: String, value: Int, chosen: GraineChatRating?): Boolean {
        val r = chosen ?: return false
        if (r.style != style) return false
        return if (style == "stars") value <= r.value else value == r.value
    }

    /** One style's choices at full size: how many, each one's square target, the icon or face inside it. */
    data class RatingChoices(val count: Int, val size: Double, val glyph: Double)

    /** Two 44 thumbs with a 22 icon, five 36 stars with a 28 star, five 44 faces at 24. */
    val RATING_CHOICES: Map<String, RatingChoices> = mapOf(
        "thumbs" to RatingChoices(2, 44.0, 22.0),
        "stars" to RatingChoices(5, 36.0, 28.0),
        "emoji" to RatingChoices(5, 44.0, 24.0),
    )

    /**
     * The rating card laid out: its width, how many choices, each one's square
     * target, the icon or face inside it (a face's size is dp too, never sp:
     * the phone's text size never pushes it out of its target), and the space
     * between two.
     */
    data class RatingLayout(val width: Double, val count: Int, val size: Double, val glyph: Double, val gap: Double)

    /**
     * The rating card in a thread `thread` wide (its rows' width, inside the
     * thread's own edges), `padding` inside the card — protocol.ts
     * chatRatingLayout, pinned by chat-window.json's `ratingLayout`
     * (ChatWindowTest): min(300, 84% of the thread) wide, wider — up to the
     * thread's own width — where the choices would not fit inside the padding;
     * the choices at full size, 8 apart, made smaller together (icon or face
     * with them) only where even the whole thread is too narrow — a small
     * phone, a large display setting. A style this build does not know is
     * thumbs; a width or padding that is not a finite number is 0.
     */
    fun ratingLayout(style: String?, thread: Double, padding: Double): RatingLayout {
        val full = RATING_CHOICES[style] ?: RATING_CHOICES.getValue("thumbs")
        val room = if (thread.isFinite()) maxOf(0.0, thread) else 0.0
        val pad = if (padding.isFinite()) maxOf(0.0, padding) else 0.0
        val row = full.count * full.size + (full.count - 1) * RATING_GAP
        val width = minOf(RATING_MAX, maxOf(room * RATING_SHARE, minOf(room, row + 2 * pad)))
        val inner = maxOf(0.0, width - 2 * pad)
        val size = maxOf(0.0, minOf(full.size, (inner - (full.count - 1) * RATING_GAP) / full.count))
        return RatingLayout(width, full.count, size, full.glyph * size / full.size, RATING_GAP)
    }

    // ── the scroll-to-latest pill ──────────────────────────────────────────

    /** "N new" beside the arrow once something arrived since — "9+" past nine; null before. */
    fun pillCount(unseen: Int): String? = when {
        unseen <= 0 -> null
        unseen > 9 -> "9+ new"
        else -> "$unseen new"
    }

    /** Whether the thread follows new rows: within [FOLLOW_WITHIN] of the bottom. */
    fun follows(distanceFromBottomDp: Double): Boolean = distanceFromBottomDp <= FOLLOW_WITHIN

    // ── the typing indicator ───────────────────────────────────────────────

    /**
     * Dot `i`'s opacity `elapsedMs` after the dots appeared: 0.35 → 1 → 0.35
     * over 1200 ms, dot i starting i × 160 ms late, forever; all at 0.6 when
     * nothing may move.
     */
    fun dotAlpha(i: Int, elapsedMs: Double, moves: Boolean): Float {
        if (!moves) return 0.6f
        val t = elapsedMs - i * 160.0
        if (t <= 0) return 0.35f
        val phase = (t % 1200.0) / 1200.0
        return (0.35 + 0.65 * (0.5 - 0.5 * Math.cos(2 * Math.PI * phase))).toFloat()
    }

    /** Dot `i`'s rise (dp, upward) in the wave: 4 and back over 900 ms, i × 120 ms late; still when nothing may move. */
    fun waveRise(i: Int, elapsedMs: Double, moves: Boolean): Float {
        if (!moves) return 0f
        val t = elapsedMs - i * 120.0
        if (t <= 0) return 0f
        val phase = (t % 900.0) / 900.0
        return (4.0 * (0.5 - 0.5 * Math.cos(2 * Math.PI * phase))).toFloat()
    }

    /** The typing words' opacity: breathing 0.55 ↔ 1 over 1400 ms; still at 1 when nothing may move. */
    fun breathe(elapsedMs: Double, moves: Boolean): Float {
        if (!moves) return 1f
        val phase = (elapsedMs % 1400.0) / 1400.0
        return (0.55 + 0.45 * (0.5 + 0.5 * Math.cos(2 * Math.PI * phase))).toFloat()
    }

    // ── cards ──────────────────────────────────────────────────────────────

    /** A component's large image: its own height (`auto`), or as tall as wide, 3:4, 9:16 — always 100–260. */
    fun imageHeight(image: String, widthDp: Double, ownDp: Double): Double = when (image) {
        "square" -> widthDp
        "landscape" -> widthDp * 3 / 4
        "wide" -> widthDp * 9 / 16
        else -> ownDp
    }.coerceIn(100.0, 260.0)

    /** A component's main button, where its own look says filled: its fill, words, outline and whether the words are strong. */
    data class Button(val fill: String?, val text: String, val border: String?, val strong: Boolean)

    fun mainButton(c: ChatStyleCards): Button = when (c.button) {
        "outline" -> Button(null, c.buttonText, c.buttonBorder, false)
        "text" -> Button(null, c.buttonText, null, true)
        else -> Button(c.buttonFill, c.buttonText, null, false)
    }
}
