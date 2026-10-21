package ai.graine.inapp

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * THE THREAD — every row the chat window scrolls, in order (contract/DESIGN.md
 * "Chat window v2"): the pinned card (as a card), the upcoming calls, the
 * welcome, day lines and earlier calls, where a new conversation began under
 * earlier lines, one bubble per message with its time and status, the agent's
 * quick replies under its turn, starters at the end of the welcome, the
 * typing indicator and the rating card. A lazy, recycled list — the
 * platform's ListView with stable ids: the SDK takes no dependencies, so no
 * RecyclerView — that builds only the rows on screen and rebinds a row only
 * when what it shows changed.
 */

/** Everything a render draws with, resolved once (equal scenes draw the same). */
internal data class ChatScene(
    val t: ThemeTokens,
    val s: ChatStyle,
    val chat: ChatTokens,
    val accent: Int,
    val onAccent: Int,
    val heading: String,
    val hostImage: String?,
    val logoUrl: String?,
    val logoPlate: LogoPlate,
    val loader: LoaderLook,
    val loaderColor: Int,
    /** Right to left: the customer's side is the left. */
    val rtl: Boolean,
    /** Things may move: the design has motion and the phone did not turn animations off. */
    val moves: Boolean,
    val frame: ChatFrame,
) {
    /** `chat.title`, or the Appearance heading when the design wrote none. */
    val title: String get() = chat.title.ifEmpty { heading }
}

/** One row of the thread, with the space above it (px). Equal items draw the same row. */
internal sealed class ThreadItem {
    abstract val key: String
    abstract val top: Int

    data class Pin(val pin: GraineChatPin, override val top: Int) : ThreadItem() { override val key get() = "pin" }
    data class Upcoming(val rows: List<ChatWindowView.UpcomingRow>, val title: String, val callName: String, val book: Boolean, override val top: Int) :
        ThreadItem() { override val key get() = "upcoming" }
    /** The welcome, and the starters inside it (empty when they are drawn elsewhere or not at all). */
    data class Welcome(val layout: String, val starters: List<String>, val list: Boolean, override val top: Int) : ThreadItem() {
        override val key get() = "welcome"
    }
    data class Starters(val starters: List<String>, val list: Boolean, override val top: Int) : ThreadItem() { override val key get() = "starters" }
    data class Day(val row: ChatRows.Row.Day, override val top: Int) : ThreadItem() { override val key get() = "day-" + row.date }
    /** "Disconnected · new conversation", before the turn [before] (its id, or "end" when no bubble follows yet). */
    data class Fresh(val before: String, override val top: Int) : ThreadItem() { override val key get() = "fresh-$before" }
    data class Voice(val row: ChatRows.Row.Voice, override val top: Int) : ThreadItem() { override val key get() = row.id }
    /** A message at its place in its group; [shown] the characters of a reply still being revealed, null for all of it. */
    data class Bubble(val turn: GraineChatTurn, val position: String, val shown: Int?, override val top: Int) : ThreadItem() {
        override val key get() = "turn-" + turn.id
    }
    data class Replies(val replies: List<GraineQuickReply>, override val top: Int) : ThreadItem() { override val key get() = "replies" }
    data class Typing(override val top: Int) : ThreadItem() { override val key get() = "typing" }
    data class Rating(val style: String, val chosen: GraineChatRating?, override val top: Int) : ThreadItem() { override val key get() = "rating" }
}

/** What the rows need from the window that holds them. */
internal interface ThreadHost {
    val scene: ChatScene
    val agent: GraineInApp
    /** Filling a space of its own rather than riding above the bar. */
    val fillsSpace: Boolean
    /** The thread's width inside its edges, px. */
    val threadWidth: Int
    fun retry()
    fun starter(text: String)
    fun quickReply(reply: GraineQuickReply)
    fun rate(style: String, value: Int)
    fun cancelCall(id: String)
    fun book()
    fun copy(text: String)
}

/** The thread's list: a platform ListView that, above the voice bar, stops growing at a height. */
internal class ThreadList(context: Context) : ListView(context) {
    /** 0: no cap (the list fills the space it is given). */
    var maxHeightPx = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (maxHeightPx <= 0) { super.onMeasure(widthMeasureSpec, heightMeasureSpec); return }
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

internal class ThreadAdapter(private val host: ThreadHost) : BaseAdapter() {
    var items: List<ThreadItem> = emptyList()
        private set
    private val ids = HashMap<String, Long>()
    private var nextId = 1L
    private val ctx get() = (host as View).context

    fun submit(next: List<ThreadItem>) {
        items = next
        notifyDataSetChanged()
    }

    override fun getCount() = items.size
    override fun getItem(position: Int): Any = items[position]
    override fun getItemId(position: Int): Long = ids.getOrPut(items[position].key) { nextId++ }
    override fun hasStableIds() = true
    override fun getViewTypeCount() = TYPES
    override fun getItemViewType(position: Int) = typeOf(items[position])
    override fun isEnabled(position: Int) = false
    override fun areAllItemsEnabled() = false

    private fun typeOf(i: ThreadItem): Int = when (i) {
        is ThreadItem.Pin -> 0
        is ThreadItem.Upcoming -> 1
        is ThreadItem.Welcome -> 2
        is ThreadItem.Starters -> 3
        is ThreadItem.Day -> 4
        is ThreadItem.Voice -> 5
        is ThreadItem.Bubble -> 6
        is ThreadItem.Replies -> 7
        is ThreadItem.Typing -> 8
        is ThreadItem.Rating -> 9
        is ThreadItem.Fresh -> 10
    }

    /** What a row last drew: redrawn only when this changes. */
    private class Holder(val type: Int, var item: ThreadItem, var scene: ChatScene, var width: Int)

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val item = items[position]
        val type = typeOf(item)
        val holder = convertView?.tag as? Holder
        val scene = host.scene
        val width = host.threadWidth
        if (convertView != null && holder != null && holder.type == type && holder.item == item && holder.scene === scene && holder.width == width) return convertView
        val rows = ThreadRows(ctx, host, scene)
        val view = when (item) {
            is ThreadItem.Bubble -> (convertView as? BubbleRow)?.takeIf { holder?.type == type } ?: BubbleRow(ctx)
            // The dots keep moving through a redraw: the same view while the look is the same.
            is ThreadItem.Typing -> if (convertView != null && holder != null && holder.type == type && holder.scene === scene) {
                holder.item = item
                (convertView as ViewGroup).setPadding(0, item.top, 0, 0)
                return convertView
            } else null
            else -> null
        }
        val drawn: View = when (item) {
            is ThreadItem.Bubble -> (view as BubbleRow).also { it.bind(item, scene, host) }
            is ThreadItem.Typing -> rows.typing(item)
            is ThreadItem.Pin -> rows.pinCard(item)
            is ThreadItem.Upcoming -> rows.upcoming(item)
            is ThreadItem.Welcome -> rows.welcome(item)
            is ThreadItem.Starters -> rows.starters(item)
            is ThreadItem.Day -> rows.day(item)
            is ThreadItem.Fresh -> rows.fresh(item)
            is ThreadItem.Voice -> rows.voice(item)
            is ThreadItem.Replies -> rows.replies(item)
            is ThreadItem.Rating -> rows.rating(item)
        }
        drawn.tag = Holder(type, item, scene, width)
        if (drawn.layoutParams !is AbsListViewParams) drawn.layoutParams = AbsListViewParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return drawn
    }

    private companion object {
        const val TYPES = 11
    }
}

private typealias AbsListViewParams = android.widget.AbsListView.LayoutParams

/**
 * The rows' drawings, for one scene. Each returns a full-width row whose top
 * padding is the space above it; the row keeps the thread's edges itself, so
 * a hero welcome can span the window.
 */
internal class ThreadRows(private val ctx: Context, private val host: ThreadHost, private val scene: ChatScene) {
    private val t = scene.t
    private val s = scene.s
    private val c = t.colors
    private val graine = host.agent
    private val edge = px(s.density.edge)
    private val bw = Themed.px(ctx, t.shape.borderWidth)

    private fun px(dp: Double): Int = Ui.dp(ctx, dp.toFloat())
    private fun color(hex: String?) = Themed.color(hex)

    /** `color` frosted: at `glass.opacity` when the chat is glass. */
    private fun frosted(hex: String): Int = s.glass?.let { Themed.alpha(color(hex), it.opacity) } ?: color(hex)

    private fun text(words: CharSequence, sp: Double, colorHex: String, family: String, weight: Int, lines: Int = 0): TextView = TextView(ctx).apply {
        text = words
        setTextColor(color(colorHex))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        Themed.face(this, family, weight, graine)
        letterSpacing = (if (family == t.font.heading && weight == t.font.weights.title) t.font.letterSpacing.title else t.font.letterSpacing.body).toFloat()
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }

    /** A full-width row: the space above it, the thread's edges (unless [edges] is false). */
    private fun row(top: Int, edges: Boolean = true): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(if (edges) edge else 0, top, if (edges) edge else 0, 0)
        clipChildren = false
        clipToPadding = false
    }

    /** A card: `cards.radius`, `surfaceRaised`, an outline when `cards.border`, the card shadow — or the design's hard offset when `cards.hard`. */
    private fun card(v: View, fill: Int, pad: Int) {
        val cards = s.cards
        val hard = cards.hard && t.elevation.hardOffset != null
        val tk = if (hard) t else t.copy(elevation = t.elevation.copy(hardOffset = null))
        Themed.surface(v, tk, fill, Themed.px(ctx, cards.radius).toFloat(), if (cards.border) bw else 0, color(c.border),
            if (hard) null else cards.shadow, pad, pad, pad, pad)
    }

    private fun avatar(sizeDp: Double, glyphShare: Double = Design.LAUNCHER_GLYPH): FrameLayout {
        val box = FrameLayout(ctx)
        val markShape = if (t.shape.launcher == "pill") "circle" else t.shape.launcher
        val tokens = if (scene.chat.avatar == "logo" && !scene.logoUrl.isNullOrEmpty()) t.copy(icons = t.icons.copy(launcher = null)) else t
        Mark.fill(box, tokens, sizeDp, sizeDp * glyphShare, markShape,
            if (markShape == "circle") null else Design.launcherCorner(markShape, sizeDp, t.shape.radius),
            scene.accent, scene.onAccent, scene.hostImage, scene.logoUrl, scene.logoPlate, CallScreen.initial(scene.title).ifEmpty { "A" })
        return box
    }

    // ── day lines and earlier calls ─────────────────────────────────────────

    /** A day line: centred, caption size, strong, in the wallpaper's quiet colour. */
    fun day(item: ThreadItem.Day): View = row(item.top).apply {
        addView(text(ChatRows.dayLabel(item.row), t.font.scale.caption, s.wallpaper.textMuted, t.font.body, t.font.weights.strong).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, Ui.matchWidth())
    }

    /** Where the server began a new conversation under earlier lines: drawn as a day line is, said as a sentence. */
    fun fresh(item: ThreadItem.Fresh): View = row(item.top).apply {
        addView(text(ChatLook.FRESH, t.font.scale.caption, s.wallpaper.textMuted, t.font.body, t.font.weights.strong).apply {
            gravity = Gravity.CENTER
            isFocusable = true
            contentDescription = ChatLook.FRESH_SPOKEN
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, Ui.matchWidth())
    }

    /** An earlier call as one row: the call icon and "Voice call · 6:05 PM" — that it happened, never what was said. */
    fun voice(item: ThreadItem.Voice): View = row(item.top).apply {
        val muted = color(s.wallpaper.textMuted)
        val clock = android.text.format.DateFormat.getTimeFormat(ctx)?.format(item.row.at)
            ?: java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(item.row.at)
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            Ui.gap(this, px(6.0))
            contentDescription = ChatRows.callSpoken(clock)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(ImageView(ctx).apply {
                setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, muted, px(14.0)))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(px(14.0), px(14.0)))
            addView(text(ChatRows.callLabel(clock), t.font.scale.caption, s.wallpaper.textMuted, t.font.body, t.font.weights.body, 1).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            })
        }, Ui.matchWidth())
    }

    // ── chips ───────────────────────────────────────────────────────────────

    /** One chip — a starter or a quick reply: `chips.height` high (it grows with the text), the design's chip look, one line. */
    fun chip(words: String, label: String = words, onTap: () -> Unit): TextView {
        val ch = s.chips
        return text(words, t.font.scale.body - 1, ch.text, t.font.body, t.font.weights.strong, 1).apply {
            gravity = Gravity.CENTER_VERTICAL
            val h = px(ChatLook.CHIP_PAD)
            setPadding(h, 0, h, 0)
            minHeight = px(ch.height)
            minimumHeight = px(ch.height)
            val fill = if (ch.style == "elevated") frosted(ch.fill) else color(ch.fill)
            background = Ui.rounded(fill, Themed.px(ctx, ch.radius).toFloat(),
                if (ch.border != null) maxOf(if (ch.borderWidth > 0) 1 else 0, Themed.px(ctx, ch.borderWidth)) else 0, color(ch.border))
            Themed.shadow(this, ch.shadow)
            contentDescription = label
            isClickable = true
            isFocusable = true
            setOnClickListener { onTap() }
            accessibilityDelegate = ButtonRole
        }
    }

    /**
     * Chips in a row of their own: `wrap` — rows that wrap, 8 apart both ways,
     * against [trailing] side or the leading one; `row` — one row that scrolls
     * sideways, 8 apart, starting the thread's edge in.
     */
    fun chipRow(chips: List<View>, trailing: Boolean, centered: Boolean = false, edgeIn: Int = edge): View {
        val gap = px(ChatLook.CHIP_GAP)
        return if (s.chips.scroll == "row" && !centered) {
            HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = View.OVER_SCROLL_NEVER
                clipToPadding = false
                setPadding(edgeIn, px(4.0), edgeIn, px(4.0))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    Ui.gap(this, gap)
                    chips.forEach { addView(it, Ui.wrap()) }
                })
            }
        } else FlowRow(ctx, gap, gap).apply {
            // The customer's side is the trailing side: the right, or the left in a right-to-left language.
            alignEnd = if (trailing) !scene.rtl else scene.rtl
            rightToLeft = scene.rtl
            center = centered
            setPadding(edgeIn, px(4.0), edgeIn, px(4.0))
            chips.forEach { addView(it) }
        }
    }

    /** The starters as full-width rows: 44 high (they grow), raised, outlined, the chevron pointing to the trailing side. */
    fun starterList(starters: List<String>): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        Ui.gap(this, px(ChatLook.LIST_GAP))
        for (words in starters) addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(ChatLook.LIST_ROW)
            val p = px(ChatLook.LIST_ROW_PAD)
            setPadding(p, 0, p, 0)
            background = Ui.rounded(frosted(c.surfaceRaised), Themed.px(ctx, t.shape.radius.md).toFloat(), bw, color(c.border))
            isClickable = true
            isFocusable = true
            contentDescription = words
            accessibilityDelegate = ButtonRole
            setOnClickListener { host.starter(words) }
            addView(text(words, t.font.scale.body - 1, c.text, t.font.body, t.font.weights.strong, 2).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0, px(8.0), 0, px(8.0))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ImageView(ctx).apply {
                // The chevron is drawn pointing up: a quarter turn points it to the trailing side.
                setImageDrawable(IconDrawable(SdkIcons.CHEVRON, t.icons.style, t.icons.stroke, color(c.textMuted), px(ChatLook.LIST_CHEVRON),
                    rotation = if (scene.rtl) -90f else 90f))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(px(ChatLook.LIST_CHEVRON), px(ChatLook.LIST_CHEVRON)))
        }, Ui.matchWidth())
    }

    /** The starters in the thread, after the messages (no welcome holds them): chips on the customer's side, or the list. */
    fun starters(item: ThreadItem.Starters): View = row(item.top, edges = item.list).apply {
        if (item.list) addView(starterList(item.starters), Ui.matchWidth())
        else addView(chipRow(item.starters.map { w -> chip(w) { host.starter(w) } }, trailing = true), Ui.matchWidth())
    }

    /** The agent's quick replies, under the turn they follow, on its side, 8 below it. */
    fun replies(item: ThreadItem.Replies): View = row(item.top, edges = false).apply {
        addView(chipRow(item.replies.map { r -> chip(r.label) { host.quickReply(r) } }, trailing = false), Ui.matchWidth())
    }

    // ── the welcome ─────────────────────────────────────────────────────────

    fun welcome(item: ThreadItem.Welcome): View = when (item.layout) {
        "card" -> welcomeCard(item)
        "hero" -> welcomeHero(item)
        else -> welcomeCentered(item)
    }

    private fun startersIn(item: ThreadItem.Welcome, centered: Boolean, edgeIn: Int): View? = when {
        item.starters.isEmpty() -> null
        item.list -> starterList(item.starters)
        else -> chipRow(item.starters.map { w -> chip(w) { host.starter(w) } }, trailing = !centered, centered = centered, edgeIn = edgeIn)
    }

    /** The empty chat (v1): the avatar at 48, the title 12 below it, the subtitle 4 below that — only when the design wrote one. */
    private fun welcomeCentered(item: ThreadItem.Welcome): View = row(item.top).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val top = px(if (host.fillsSpace) 48.0 else 12.0)
        if (scene.chat.avatar != "none") {
            addView(avatar(ChatLook.WELCOME_CENTERED_AVATAR), LinearLayout.LayoutParams(px(ChatLook.WELCOME_CENTERED_AVATAR), px(ChatLook.WELCOME_CENTERED_AVATAR))
                .apply { topMargin = top; bottomMargin = px(12.0); gravity = Gravity.CENTER_HORIZONTAL })
        }
        addView(text(scene.title, minOf(t.font.scale.title, ChatLook.WELCOME_CENTERED_TITLE_MAX), s.wallpaper.text, t.font.heading, t.font.weights.title, 2).apply {
            gravity = Gravity.CENTER
        }, Ui.matchWidth().apply { if (scene.chat.avatar == "none") topMargin = top })
        if (scene.chat.subtitle.isNotEmpty()) addView(text(scene.chat.subtitle, t.font.scale.caption, s.wallpaper.textMuted, t.font.body, t.font.weights.body).apply {
            gravity = Gravity.CENTER
        }, Ui.matchWidth().apply { topMargin = px(4.0) })
        startersIn(item, centered = true, edgeIn = 0)?.let { addView(it, Ui.matchWidth().apply { topMargin = px(16.0) }) }
    }

    /** A card first in the thread: the avatar (40) beside the title over the subtitle; the starters inside it when they are a list or placed inline. */
    private fun welcomeCard(item: ThreadItem.Welcome): View = row(item.top).apply {
        val pad = Themed.px(ctx, t.spacing.cardPadding)
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card(card, color(c.surfaceRaised), pad)
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            Ui.gap(this, px(12.0))
            if (scene.chat.avatar != "none") addView(avatar(ChatLook.WELCOME_CARD_AVATAR), LinearLayout.LayoutParams(px(ChatLook.WELCOME_CARD_AVATAR), px(ChatLook.WELCOME_CARD_AVATAR)))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(scene.title, minOf(t.font.scale.title, ChatLook.WELCOME_CARD_TITLE_MAX), c.text, t.font.heading, t.font.weights.title, 2))
                if (scene.chat.subtitle.isNotEmpty()) addView(text(scene.chat.subtitle, t.font.scale.caption, c.textMuted, t.font.body, t.font.weights.body, 2))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }, Ui.matchWidth())
        startersIn(item, centered = false, edgeIn = 0)?.let { card.addView(it, Ui.matchWidth().apply { topMargin = Themed.px(ctx, s.density.gap) }) }
        addView(card, Ui.matchWidth())
    }

    /**
     * A hero, the window's full width, filled with the header's fill (a
     * gradient top → bottom), its bottom corners `radius.xl`: 24 padding, the
     * avatar 56 centred, the title large, the subtitle, then the starters.
     */
    private fun welcomeHero(item: ThreadItem.Welcome): View = row(item.top, edges = false).apply {
        val f = s.header.fill
        val r = Themed.px(ctx, t.shape.radius.xl).toFloat()
        val pad = px(ChatLook.HERO_PAD)
        val block = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(color(f.color), color(f.colorTo ?: f.color))).apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }
        }
        if (scene.chat.avatar != "none") block.addView(avatar(ChatLook.HERO_AVATAR), LinearLayout.LayoutParams(px(ChatLook.HERO_AVATAR), px(ChatLook.HERO_AVATAR))
            .apply { bottomMargin = px(12.0) })
        block.addView(text(scene.title, minOf(t.font.scale.display, ChatLook.HERO_TITLE_MAX), f.text, t.font.heading, t.font.weights.title, 2).apply {
            gravity = Gravity.CENTER
        }, Ui.matchWidth())
        if (scene.chat.subtitle.isNotEmpty()) block.addView(text(scene.chat.subtitle, t.font.scale.body, f.textMuted, t.font.body, t.font.weights.body, 2).apply {
            gravity = Gravity.CENTER
        }, Ui.matchWidth().apply { topMargin = px(4.0) })
        startersIn(item, centered = true, edgeIn = 0)?.let { block.addView(it, Ui.matchWidth().apply { topMargin = px(16.0) }) }
        addView(block, Ui.matchWidth())
    }

    // ── the pinned card, as a card ──────────────────────────────────────────

    fun pinCard(item: ThreadItem.Pin): View = row(item.top).apply {
        val p = item.pin
        val pad = Themed.px(ctx, t.spacing.cardPadding)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            Ui.gap(this, px(10.0))
            contentDescription = ChatLook.pinSpoken(p)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        card(card, color(c.surfaceRaised), pad)
        card.addView(PinViews.thumb(ctx, scene, graine, p, ChatLook.CONTEXT_CARD_THUMB, t.shape.radius.md),
            LinearLayout.LayoutParams(px(ChatLook.CONTEXT_CARD_THUMB), px(ChatLook.CONTEXT_CARD_THUMB)))
        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(text(p.title, minOf(t.font.scale.title, 16.0), c.text, t.font.heading, t.font.weights.title, 1))
            p.subtitle?.let { addView(text(it, t.font.scale.caption, c.textMuted, t.font.body, t.font.weights.body, 1)) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        p.badge?.let { card.addView(PinViews.badge(ctx, scene, graine, it)) }
        addView(card, Ui.matchWidth())
    }

    // ── the typing indicator ────────────────────────────────────────────────

    /**
     * The agent is writing, as its next row: the loader in an agent bubble
     * (`loader`), three dots or a wave of them in one (`dots`, `wave`), or the
     * words alone, breathing (`text`). Said to a screen reader, politely.
     */
    fun typing(item: ThreadItem.Typing): View {
        val outer = FrameLayout(ctx).apply {
            setPadding(edge, item.top, edge, 0)
            contentDescription = ChatLook.typingSpoken(scene.title)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val ty = s.typing
        val side = s.bubbles.agent
        if (ty.style == "text") {
            val words = text(ChatLook.typingWords(ty), t.font.scale.caption, ty.color, t.font.body, t.font.weights.body, 1).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            if (scene.moves) {
                // Breathing 0.55 ↔ 1 over 1400 ms; still under reduced motion.
                android.animation.ValueAnimator.ofFloat(0f, 1400f).apply {
                    duration = 1400
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                    addUpdateListener { words.alpha = ChatLook.breathe((it.animatedValue as Float).toDouble(), true) }
                    words.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) { start() }
                        override fun onViewDetachedFromWindow(v: View) { cancel() }
                    })
                }
            }
            outer.addView(words, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START))
            return outer
        }
        val bubble = FrameLayout(ctx).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        if (!side.bare) {
            val corners = ChatLook.corners(s.bubbles.copy(shape = if (s.bubbles.shape == "tail") "squircle" else s.bubbles.shape), ChatRules.SINGLE, false, scene.rtl)
            bubble.background = BubbleDrawable(side.color?.let { frostedFill(it) }, side.colorTo?.let { color(it) }, side.border?.let { color(it) },
                maxOf(1, bw).toFloat(), corners, false, null, ctx.resources.displayMetrics.density)
        }
        if (ty.style == "loader") {
            val padH = Themed.px(ctx, s.density.padX)
            val padV = Themed.px(ctx, Js.round(t.spacing.cardPadding * 0.5))
            bubble.setPadding(padH, padV, padH, padV)
            bubble.addView(GraineLoaderView(ctx).apply {
                // The dots whatever the dashboard's kind: a ring or the logo breathing in a bubble reads as an error, not typing.
                configure(scene.loader.copy(kind = "dots"), scene.loaderColor, color(c.border), ChatWindowView.TYPING_DOTS, scene.logoUrl)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        } else {
            val (padH, padV) = ChatLook.padding(s.density, side.bare).let { Themed.px(ctx, it.first) to Themed.px(ctx, it.second) }
            bubble.setPadding(padH, padV, padH, padV)
            bubble.addView(TypingDotsView(ctx).apply { configure(color(ty.color), ty.style == "wave", scene.moves) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        outer.addView(bubble, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START))
        return outer
    }

    /** An agent bubble's fill, frosted when the chat is glass. */
    private fun frostedFill(hex: String): Int = frosted(hex)

    // ── the rating ──────────────────────────────────────────────────────────

    /**
     * After the agent ends the conversation: a card at the end of the thread,
     * centred — the question, the choices, and once chosen "Thanks for your
     * feedback" under them. Nothing else can be chosen after that. The card's
     * width, the choices' size and the space between them are
     * [ChatLook.ratingLayout] of the rows' width, so a narrow phone or a large
     * display setting never pushes a choice out of the card.
     */
    fun rating(item: ThreadItem.Rating): View = row(item.top).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        val pad = Themed.px(ctx, t.spacing.cardPadding)
        val fit = ChatLook.ratingLayout(item.style, threadDp(), t.spacing.cardPadding)
        val width = px(fit.width)
        val gap = px(fit.gap)
        val values = ChatLook.ratingValues(item.style)
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        card(card, color(c.surfaceRaised), pad)
        // In whole pixels, the targets never add up to more than the card holds inside its padding.
        val target = minOf(px(fit.size), maxOf(0, (width - card.paddingLeft - card.paddingRight - (values.size - 1) * gap) / values.size))
        card.addView(text(ChatLook.ratingPrompt(s.rating), minOf(t.font.scale.title, 16.0), c.text, t.font.heading, t.font.weights.title, 2).apply {
            gravity = Gravity.CENTER
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, Ui.matchWidth())
        val choices = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            Ui.gap(this, gap)
        }
        val chosen = item.chosen
        for (value in values) choices.addView(ratingChoice(item.style, value, chosen, target, fit.glyph))
        card.addView(choices, Ui.matchWidth().apply { topMargin = px(10.0) })
        if (chosen != null) card.addView(text(ChatRules.RATING_THANKS, t.font.scale.caption, c.textMuted, t.font.body, t.font.weights.body).apply {
            gravity = Gravity.CENTER
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }, Ui.matchWidth().apply { topMargin = px(8.0) })
        addView(card, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /**
     * The rows' width in dp — the thread's, inside its edges. Before the
     * thread is first laid out it is 0: the card is then drawn at its widest
     * (a 360 thread), and drawn again once the width is known.
     */
    private fun threadDp(): Double {
        val w = host.threadWidth
        return if (w > 0) w / ctx.resources.displayMetrics.density.toDouble() else 360.0
    }

    /** One choice: a [targetPx] square target, its icon or face [glyphDp] across. */
    private fun ratingChoice(style: String, value: Int, chosen: GraineChatRating?, targetPx: Int, glyphDp: Double): View {
        val lit = ChatLook.ratingLit(style, value, chosen)
        val picked = chosen?.value == value && chosen.style == style
        val label = ChatRules.ratingLabel(style, value)
        val glyph = px(glyphDp)
        val v: View = when (style) {
            "stars" -> FrameLayout(ctx).apply {
                addView(ImageView(ctx).apply {
                    setImageDrawable(IconDrawable(SdkIcons.STAR, t.icons.style, t.icons.stroke, color(if (lit) c.accent else c.textMuted), glyph, filled = lit))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(glyph, glyph, Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(targetPx, targetPx)
            }
            "emoji" -> FrameLayout(ctx).apply {
                if (picked) background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(c.accentSoft)) }
                addView(TextView(ctx).apply {
                    text = ChatRules.RATING_EMOJI[value - 1]
                    // dp, not sp: the phone's text size never grows a face out of its target.
                    setTextSize(TypedValue.COMPLEX_UNIT_DIP, glyphDp.toFloat())
                    includeFontPadding = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    if (picked) { scaleX = ChatLook.FACE_CHOSEN_SCALE; scaleY = ChatLook.FACE_CHOSEN_SCALE }
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(targetPx, targetPx)
            }
            else -> FrameLayout(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color(if (lit) c.accent else c.accentSoft)) }
                addView(ImageView(ctx).apply {
                    setImageDrawable(IconDrawable(if (value == 1) SdkIcons.THUMB_UP else SdkIcons.THUMB_DOWN, t.icons.style, t.icons.stroke,
                        if (lit) scene.onAccent else color(c.text), glyph))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, FrameLayout.LayoutParams(glyph, glyph, Gravity.CENTER))
                layoutParams = LinearLayout.LayoutParams(targetPx, targetPx)
            }
        }
        return v.apply {
            contentDescription = label
            isSelected = picked
            isFocusable = true
            accessibilityDelegate = ButtonRole
            if (chosen == null) {
                isClickable = true
                setOnClickListener { host.rate(style, value) }
            } else {
                isClickable = false
                isEnabled = picked
            }
        }
    }

    // ── upcoming calls ──────────────────────────────────────────────────────

    /**
     * UPCOMING CALLS — the customer's booked calls, first in the thread, as a
     * card: "Upcoming call(s)", a row per call (at most three) with Cancel,
     * and "Book a call" where the agent can be booked; with none booked, that
     * alone in the card's place.
     */
    fun upcoming(item: ThreadItem.Upcoming): View = row(item.top).apply {
        if (item.rows.isEmpty()) {
            addView(bookButton(standalone = true), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(40.0)))
            return@apply
        }
        val pad = Themed.px(ctx, t.spacing.cardPadding)
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; Ui.gap(this, Themed.px(ctx, t.spacing.gap)) }
        val window = WindowChrome.titleBar(t, WindowChrome.Surface.COMPONENT, item.title)
        val fill = color(WindowChrome.cardFill(t))
        val radius = Themed.px(ctx, s.cards.radius).toFloat()
        if (window != null) {
            val bar = WindowTitleBar(ctx)
            card.addView(bar, Ui.matchWidth())
            card.addView(body, Ui.matchWidth())
            bar.frame(card, body, window, t, fill, radius, bw, color(c.border), s.cards.shadow, pad, pad, pad, pad)
        } else {
            card(card, fill, pad)
            card.addView(body, Ui.matchWidth())
            body.addView(text(item.title, t.font.scale.caption, c.textMuted, t.font.body, t.font.weights.strong))
        }
        val clock = android.text.format.DateFormat.getTimeFormat(ctx)
        val locale = Locale.getDefault()
        val dayPattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }.getOrNull() ?: "EEE d MMM"
        val dayFormat = java.text.SimpleDateFormat(dayPattern, locale)
        val now = Date()
        for (r in item.rows) {
            val whenText = when {
                r.cancelling -> UpcomingCalls.CANCELLING
                r.failed -> UpcomingCalls.CANCEL_FAILED
                else -> UpcomingCalls.label(r.at, now, TimeZone.getDefault(), { clock.format(it) }, { dayFormat.format(it) })
            }
            body.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(FrameLayout(ctx).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(scene.accent) }
                    addView(ImageView(ctx).apply {
                        setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, scene.onAccent, px(16.0)))
                    }, FrameLayout.LayoutParams(px(16.0), px(16.0), Gravity.CENTER))
                }, LinearLayout.LayoutParams(px(32.0), px(32.0)))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text(whenText, t.font.scale.body - 1, c.text, t.font.body, t.font.weights.strong, 1))
                    addView(text(item.callName, t.font.scale.caption, c.textMuted, t.font.body, t.font.weights.body, 1))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(10.0); marginEnd = px(8.0) })
                addView(text("Cancel", t.font.scale.caption, c.text, t.font.body, t.font.weights.strong).apply {
                    gravity = Gravity.CENTER
                    setPadding(px(12.0), 0, px(12.0), 0)
                    background = Ui.rounded(Color.TRANSPARENT, Themed.px(ctx, minOf(t.shape.radius.md, 16.0)).toFloat(), maxOf(1, bw), color(c.border))
                    isEnabled = !r.cancelling
                    isClickable = !r.cancelling
                    contentDescription = "Cancel the call $whenText".trim()
                    accessibilityDelegate = ButtonRole
                    setOnClickListener { host.cancelCall(r.id) }
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32.0)))
            }, Ui.matchWidth())
        }
        if (item.book) body.addView(bookButton(standalone = false), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(36.0)))
        addView(card, Ui.matchWidth())
    }

    /** "Book a call": outlined like Cancel in the card; with none booked, the same on `surfaceRaised` in the card's place. */
    private fun bookButton(standalone: Boolean): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        Ui.gap(this, px(8.0))
        background = Ui.rounded(if (standalone) color(c.surfaceRaised) else Color.TRANSPARENT,
            Themed.px(ctx, minOf(t.shape.radius.md, 16.0)).toFloat(), maxOf(1, bw), color(c.border))
        isClickable = true
        isFocusable = true
        contentDescription = Booking.CHAT_ROW
        accessibilityDelegate = ButtonRole
        setOnClickListener { host.book() }
        addView(ImageView(ctx).apply {
            setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, scene.accent, px(16.0)))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(px(16.0), px(16.0)))
        addView(text(Booking.CHAT_ROW, t.font.scale.body - 1, c.text, t.font.body, t.font.weights.strong, 1).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }
}

/** A tap target TalkBack calls a button. */
internal object ButtonRole : View.AccessibilityDelegate() {
    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(host, info)
        info.className = android.widget.Button::class.java.name
    }
}

/** The pinned card's thumbnail and badge, shared by the bar under the header and the card in the thread. */
internal object PinViews {
    /** The picture, filled and cropped — or a disc of `accentSoft` with the title's first letter. */
    fun thumb(ctx: Context, scene: ChatScene, graine: GraineInApp, p: GraineChatPin, sizeDp: Double, cornerDp: Double): View {
        val t = scene.t
        val corner = Ui.dp(ctx, cornerDp.toFloat()).toFloat()
        val url = p.imageUrl
        if (!url.isNullOrEmpty()) return roundedImage(ctx, url, corner, Themed.color(t.colors.accentSoft))
        return TextView(ctx).apply {
            text = CallScreen.initial(p.title)
            gravity = Gravity.CENTER
            setTextColor(Themed.color(t.colors.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (14.0 * sizeDp / ChatLook.CONTEXT_THUMB).toFloat())
            Themed.face(this, t.font.body, t.font.weights.strong, graine)
            includeFontPadding = false
            background = GradientDrawable().apply {
                setColor(Themed.color(t.colors.accentSoft))
                if (sizeDp <= ChatLook.CONTEXT_THUMB) shape = GradientDrawable.OVAL else cornerRadius = corner
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    /** A pill 22 high, 8 padding, `accentSoft`, the words at caption size, strong, in `text`. */
    fun badge(ctx: Context, scene: ChatScene, graine: GraineInApp, words: String): View {
        val t = scene.t
        return TextView(ctx).apply {
            text = words
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setTextColor(Themed.color(t.colors.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
            Themed.face(this, t.font.body, t.font.weights.strong, graine)
            val h = Ui.dp(ctx, ChatLook.BADGE_PAD.toFloat())
            setPadding(h, 0, h, 0)
            minHeight = Ui.dp(ctx, ChatLook.BADGE_HEIGHT.toFloat())
            background = Ui.rounded(Themed.color(t.colors.accentSoft), Ui.dp(ctx, 11f).toFloat())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }
}

/**
 * ONE MESSAGE — its bubble at its place in its group (corners, tail, fill,
 * outline, shadow; or no bubble at all), the words (the agent's formatted and,
 * while arriving, revealed), its time below or inside, the customer's marks,
 * and the line under it when it waits or could not be sent (a tap retries).
 * Long-press copies the plain words. One element to a screen reader.
 */
internal class BubbleRow(context: Context) : LinearLayout(context) {
    private val box = MaxWidthLayout(context).apply { orientation = VERTICAL }
    private val words = ChatTextView(context)
    private val inside = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END }
    private val insideTime = TextView(context).apply { maxLines = 1 }
    private val insideTick = ImageView(context)
    private val under = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val underIcon = ImageView(context)
    private val underText = TextView(context).apply { maxLines = 2 }
    private val underTick = ImageView(context)
    private var plain = ""
    private var spoken = ""
    private var failed = false
    private var host: ThreadHost? = null

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        inside.addView(insideTime)
        inside.addView(insideTick)
        box.addView(words, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(inside, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.END })
        under.addView(underIcon)
        under.addView(underText)
        under.addView(underTick)
        addView(box, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(under, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (v in listOf(inside, insideTime, insideTick, under, underIcon, underText, underTick)) v.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        box.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        words.accessibilityDelegate = object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(v: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(v, info)
                // One element: who said it, the words (links kept reachable), the time and the status.
                val text = words.text
                info.text = if (text is android.text.Spanned) {
                    android.text.SpannableStringBuilder(spoken.substringBefore(plain)).append(text).append(spoken.substringAfter(plain, ""))
                } else spoken
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(ACTION_COPY, ChatLook.COPY))
                if (failed) info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, ChatLook.RETRY))
            }

            override fun performAccessibilityAction(v: View, action: Int, args: Bundle?): Boolean {
                if (action == ACTION_COPY) { host?.copy(plain); return true }
                return super.performAccessibilityAction(v, action, args)
            }
        }
    }

    fun bind(item: ThreadItem.Bubble, scene: ChatScene, host: ThreadHost) {
        this.host = host
        val ctx = context
        val d = ctx.resources.displayMetrics.density
        fun px(dp: Double) = Math.round(dp * d).toInt()
        val t = scene.t
        val s = scene.s
        val m = item.turn
        val customer = m.role != Role.AGENT
        val side = if (customer) s.bubbles.user else s.bubbles.agent
        val b = s.bubbles
        val edge = px(s.density.edge)
        val tailShape = b.shape == "tail" && !side.bare
        val right = ChatLook.speakerOnRight(customer, scene.rtl)
        val room = if (tailShape) px(ChatLook.TAIL) else 0
        // The tail lies in the margin: the speaker's side keeps the edge less the tail's room.
        setPadding(if (!right) edge - room else edge, item.top, if (right) edge - room else edge, 0)
        gravity = if (customer) Gravity.END else Gravity.START
        (box.layoutParams as LayoutParams).gravity = gravity
        (under.layoutParams as LayoutParams).gravity = gravity
        box.maxWidthPx = (host.threadWidth * b.maxWidth).toInt().takeIf { it > 0 }?.plus(room) ?: Int.MAX_VALUE

        // The words: the customer's as typed; the agent's formatted — revealed so far while still arriving.
        val full = m.text
        val live = item.shown != null
        val shownText = item.shown?.let { ChatRules.revealSlice(full, it) } ?: full
        val textColor = Themed.color(side.text)
        words.setTextColor(textColor)
        words.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.body.toFloat())
        Themed.face(words, t.font.body, t.font.weights.body, host.agent)
        words.letterSpacing = t.font.letterSpacing.body.toFloat()
        Ui.lineHeight(words, (t.font.scale.body * t.font.lineHeight.body).toFloat())
        if (customer) {
            words.movementMethod = null
            words.text = shownText
            plain = full
        } else {
            val blocks = ChatRules.markdown(shownText, live || m.live)
            val formatted = ChatFormat.format(blocks, formatLook(scene, side, host.agent))
            words.setFormatted(formatted)
            words.movementMethod = if (ChatFormat.hasLinks(formatted)) LinkMovementMethod.getInstance() else null
            plain = ChatRules.markdownText(ChatRules.markdown(full, false))
        }
        words.isFocusable = true
        words.isClickable = true
        words.isLongClickable = true
        // Provisional: the agent's words still arriving; a message waiting for the connection.
        val waiting = customer && m.delivery == GraineChatTurn.Delivery.SENDING
        failed = customer && m.delivery == GraineChatTurn.Delivery.FAILED
        box.alpha = when {
            waiting -> ChatLook.WAITING_ALPHA
            m.live -> VoiceBar.LIVE_ALPHA
            else -> 1f
        }

        // The bubble.
        val (padX, padY) = ChatLook.padding(s.density, side.bare)
        if (side.bare) {
            box.background = null
            box.outlineProvider = ViewOutlineProvider.BACKGROUND
            box.elevation = 0f
            box.setPadding(px(padX), px(padY), px(padX), px(padY))
        } else {
            val corners = ChatLook.corners(b, item.position, customer, scene.rtl)
            val fill = side.color?.let { hex -> if (!customer && s.glass != null) Themed.alpha(Themed.color(hex), s.glass.opacity) else Themed.color(hex) }
            box.background = BubbleDrawable(fill, side.colorTo?.let { Themed.color(it) }, side.border?.let { Themed.color(it) },
                maxOf(1f, (t.shape.borderWidth * d).toFloat()), corners, ChatLook.tail(b, item.position), if (tailShape) right else null, d)
            box.outlineProvider = BubbleDrawable.OUTLINE
            Themed.shadow(box, b.shadow)
            box.setPadding(px(padX) + (if (!right) room else 0), px(padY), px(padX) + (if (right) room else 0), px(padY))
        }

        // The time — below, or inside on a line of its own — and the customer's marks.
        val clock = android.text.format.DateFormat.getTimeFormat(ctx).format(m.at)
        val place = ChatLook.timePlace(s.timestamps, item.position, b.grouping, side.bare)
        val tick = ChatLook.tick(s.timestamps, customer, m.delivery)
        val timeSp = maxOf(10.0, t.font.scale.caption - 1)
        fun styleTime(tv: TextView, hex: String) {
            tv.setTextColor(Themed.color(hex))
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, timeSp.toFloat())
            Themed.face(tv, t.font.body, t.font.weights.body, host.agent)
        }
        fun tickOn(iv: ImageView, hex: String) {
            if (tick == null) { iv.visibility = GONE; return }
            iv.visibility = VISIBLE
            val tickColor = Themed.color(if (tick == SdkIcons.ALERT) t.colors.danger else hex)
            iv.setImageDrawable(IconDrawable(tick, t.icons.style, t.icons.stroke, tickColor, px(ChatLook.TICK)))
            iv.layoutParams = LayoutParams(px(ChatLook.TICK), px(ChatLook.TICK)).apply { marginStart = px(ChatLook.TICK_GAP) }
        }
        if (place == "inside") {
            inside.visibility = VISIBLE
            insideTime.text = clock
            styleTime(insideTime, side.textMuted)
            tickOn(insideTick, side.textMuted)
            (inside.layoutParams as LayoutParams).topMargin = px(ChatLook.TIME_INSIDE_GAP)
        } else inside.visibility = GONE

        // Under the bubble: not sent (a tap retries), waiting, or the time below.
        val belowTime = place == "below"
        when {
            failed -> {
                under.visibility = VISIBLE
                underIcon.visibility = VISIBLE
                underIcon.setImageDrawable(IconDrawable(SdkIcons.ALERT, t.icons.style, t.icons.stroke, Themed.color(t.colors.danger), px(ChatLook.STATUS_ICON)))
                underIcon.layoutParams = LayoutParams(px(ChatLook.STATUS_ICON), px(ChatLook.STATUS_ICON)).apply { marginEnd = px(4.0) }
                underText.text = ChatLook.NOT_SENT
                underText.setTextColor(Themed.color(t.colors.danger))
                underText.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
                Themed.face(underText, t.font.body, t.font.weights.strong, host.agent)
                underTick.visibility = GONE
            }
            waiting -> {
                under.visibility = VISIBLE
                underIcon.visibility = GONE
                underText.text = if (belowTime) "$clock · ${ChatLook.WAITING}" else ChatLook.WAITING
                styleTime(underText, s.wallpaper.textMuted)
                if (belowTime) tickOn(underTick, s.wallpaper.textMuted) else underTick.visibility = GONE
            }
            belowTime -> {
                under.visibility = VISIBLE
                underIcon.visibility = GONE
                underText.text = clock
                styleTime(underText, s.wallpaper.textMuted)
                tickOn(underTick, s.wallpaper.textMuted)
            }
            else -> under.visibility = GONE
        }
        (under.layoutParams as LayoutParams).apply {
            topMargin = px(ChatLook.TIME_BELOW_GAP)
            // Aligned with the bubble, not with its tail's room in the margin.
            leftMargin = if (!right) room else 0
            rightMargin = if (right) room else 0
        }
        val retry = OnClickListener { host.retry() }
        under.setOnClickListener(if (failed) retry else null)
        under.isClickable = failed
        words.setOnClickListener(if (failed) retry else null)
        if (!failed) words.isClickable = words.movementMethod != null
        words.setOnLongClickListener { host.copy(plain); true }
        val time = if (place != null) clock else null
        spoken = ChatLook.spoken(scene.title, customer, plain, time, m.delivery)
    }

    /** What the agent's words are drawn with: the design's strong weight, its italic, the monospace, the side's link colour. */
    private fun formatLook(scene: ChatScene, side: ChatStyleBubble, graine: GraineInApp): ChatFormat.Look {
        val t = scene.t
        val d = resources.displayMetrics.density
        val body = t.font.body
        val under = side.color ?: scene.s.wallpaper.color
        return ChatFormat.Look(
            strong = ChatFonts.face(body, t.font.weights.strong, false, graine),
            italic = ChatFonts.italic(body, t.font.weights.body, graine),
            strongItalic = ChatFonts.italic(body, t.font.weights.strong, graine),
            mono = Typeface.MONOSPACE,
            codeSizePx = android.util.TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, (t.font.scale.body - 1).toFloat(), resources.displayMetrics),
            codeFill = Themed.color(ChatStyles.mix(under, side.text, 0.1)),
            codeCornerPx = (ChatLook.CODE_CORNERS * d).toFloat(),
            link = Themed.color(side.link),
            paragraphGapPx = Math.round(ChatLook.PARAGRAPH_GAP * d).toInt(),
            markerGapPx = Math.round(ChatLook.MARKER_GAP * d).toInt(),
        )
    }

    private companion object {
        /** The screen reader's "Copy" action on a bubble. */
        val ACTION_COPY = View.generateViewId()
    }
}

/** A column that never grows past [maxWidthPx] — a bubble at most `maxWidth` of the thread. */
internal class MaxWidthLayout(context: Context) : LinearLayout(context) {
    var maxWidthPx = Int.MAX_VALUE
        set(v) { if (field != v) { field = v; requestLayout() } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val spec = when {
            maxWidthPx == Int.MAX_VALUE -> widthMeasureSpec
            mode == MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(minOf(size, maxWidthPx), if (mode == MeasureSpec.EXACTLY) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST)
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}

/** The design's family as a Typeface at a weight — for the formatted words' bold and italic runs (contract/DESIGN.md → Fonts). */
internal object ChatFonts {
    fun face(family: String, weight: Int, italic: Boolean, graine: GraineInApp?): Typeface {
        val own = graine?.typeface(family)
        if (own != null) return weighted(own, weight, italic)
        val loaded = graine?.loadedFace(family, weight)
        if (loaded != null) return if (italic) Typeface.create(loaded, Typeface.ITALIC) else loaded
        return weighted(Typeface.DEFAULT, weight, italic)
    }

    /** The italic at a weight; null when only a slanted copy would do (the run is then skewed). */
    fun italic(family: String, weight: Int, graine: GraineInApp?): Typeface? = face(family, weight, true, graine)

    private fun weighted(base: Typeface, weight: Int, italic: Boolean): Typeface =
        if (Build.VERSION.SDK_INT >= 28) Typeface.create(base, weight.coerceIn(100, 1000), italic)
        else Typeface.create(base, (if (weight >= 600) Typeface.BOLD else Typeface.NORMAL) or (if (italic) Typeface.ITALIC else 0))
}

