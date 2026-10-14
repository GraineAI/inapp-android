package ai.graine.inapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * THE CHAT WINDOW — contract/DESIGN.md → Chat window, from the design's
 * `chat` tokens: the frame in `chat.background` (outlined, `radius.xl`, the
 * floating shadow — none of it when the chat fills the screen); a 50 dp header
 * on `chat.headerBackground` with the avatar the design asks for, the title
 * (`chat.title`, else the heading) and its status ("Online" or the subtitle,
 * after a dot — `success` on a surface header, the title's colour on any
 * other); the thread, dotted when the design says so; one bubble per turn in
 * the design's bubble style, times under them when it shows them, the agent's
 * typing indicator while a reply is on its way; a conversation that continues
 * with a day line where the day changes and an earlier call as one "Voice call
 * · time" row (never its transcript), and "New conversation" in the header;
 * the empty chat; the design's starters above the composer until the customer
 * has said something (never while earlier messages are on screen); the
 * composer; and, first in the thread, the customer's Upcoming calls. The
 * pieces the design can leave out — the Upcoming calls card (and its title),
 * the earlier messages, the compose control — follow [ChatPieces].
 *
 * With window chrome a 32 dp title bar replaces the header: no avatar, the
 * title centred in monospace after the `success` dot while online, the
 * chevron 12 in from the bar's right end.
 *
 * [frame]: how it is framed (contract/DESIGN.md "Chat window" → Size) — a
 * card above the voice bar ([ChatFrame.WINDOW]), or filling a space of its
 * own, the thread taking all the height: a whole screen of the app's
 * ([ChatFrame.PAGE], [GraineChatView]), a sheet ([ChatFrame.SHEET]), a
 * tablet's panel, which keeps the whole frame ([ChatFrame.PANEL]), or the
 * bar's full screen, the chevron leading the header ([ChatFrame.SCREEN]).
 */
internal class ChatWindowView(
    context: Context,
    /** The agent whose typefaces the window draws in; the next render follows a change. */
    var graine: GraineInApp,
    private val frame: ChatFrame = ChatFrame.WINDOW,
) : LinearLayout(context) {
    /** Filling a space of its own rather than riding above the bar: the thread takes the height. */
    private val page = frame != ChatFrame.WINDOW
    /** No outline, corners or shadow of its own: a whole screen, or a sheet (which rounds its own top). */
    private val frameless = frame == ChatFrame.PAGE || frame == ChatFrame.SHEET || frame == ChatFrame.SCREEN
    var onCollapse: (() -> Unit)? = null
    var onSend: ((String) -> Unit)? = null
    /** The customer asked to try again, after nothing could connect. */
    var onRetry: (() -> Unit)? = null
    /** Cancel one of the Upcoming calls. */
    var onCancelCall: ((String) -> Unit)? = null
    /** "New conversation" — offered in the header only while earlier messages (restored turns) are on screen. */
    var onNewConversation: (() -> Unit)? = null

    private val inner = LinearLayout(context).apply { orientation = VERTICAL }
    /** The header's place when the design opens the chat as a window. */
    private val titleBar = WindowTitleBar(context).apply { visibility = GONE }
    private val barChevron = ImageView(context).apply { contentDescription = if (frame == ChatFrame.PAGE) "Close" else "Collapse"; isClickable = true; isFocusable = true }
    private val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val mark = FrameLayout(context)
    private val title = TextView(context).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val dot = View(context)
    private val status = TextView(context).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val chevron = ImageView(context).apply { contentDescription = if (frame == ChatFrame.PAGE) "Close" else "Collapse"; isClickable = true; isFocusable = true }
    private val compose = ImageView(context).apply { contentDescription = "New conversation"; isClickable = true; isFocusable = true; visibility = GONE }
    private val barCompose = ImageView(context).apply { contentDescription = "New conversation"; isClickable = true; isFocusable = true; visibility = GONE }

    /** What the chevron is called: "Collapse" back to a call, "Close" where the chat is all there is. */
    var collapseLabel: String
        get() = chevron.contentDescription?.toString().orEmpty()
        set(v) { chevron.contentDescription = v; barChevron.contentDescription = v }
    private val divider = View(context)
    /** The thread's frame: the chat's background and its pattern, standing still while the messages scroll. */
    private val threadFrame = FrameLayout(context)
    private val thread = LinearLayout(context).apply { orientation = VERTICAL }
    private val upcomingSlot = FrameLayout(context).apply { visibility = GONE }
    private val emptySlot = FrameLayout(context).apply { visibility = GONE }
    private val list = object : LinearLayout(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            bubbleMax = Math.round((w - paddingLeft - paddingRight) * VoiceBar.BUBBLE_MAX_SHARE).toInt()
            post { applyBubbleMax() }
        }
    }.apply { orientation = VERTICAL }
    private val typingSlot = FrameLayout(context).apply { visibility = GONE }
    private val scroll: ScrollView =
        if (page) ScrollView(context).apply { isFillViewport = true } else CappedScrollView(context, Ui.dp(context, VoiceBar.CHAT_LIST_MAX))
    private val starters = FlowRow(context, Ui.dp(context, STARTER_GAP), Ui.dp(context, STARTER_GAP)).apply { alignEnd = true; visibility = GONE }
    private val composer = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val field = EditText(context).apply {
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEND
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    }
    private val sendButton = RoundButton(context, VoiceBar.SEND)
    private var bubbleMax = Int.MAX_VALUE
    private var accessory: View? = null

    /** What is drawn now, so a streamed word redraws one bubble rather than the whole conversation. */
    private var drawnLines: List<ChatRows.Row> = emptyList()
    private var drawnLook: Any? = null
    private val lineViews = ArrayList<View>()

    init {
        orientation = VERTICAL
        chevron.setOnClickListener { onCollapse?.invoke() }
        barChevron.setOnClickListener { onCollapse?.invoke() }
        compose.setOnClickListener { onNewConversation?.invoke() }
        barCompose.setOnClickListener { onNewConversation?.invoke() }
        titleBar.trailing(barChevron, Ui.dp(context, VoiceBar.ICON.toFloat()))
        // Beside the chevron, one icon's width further in.
        titleBar.addView(barCompose, FrameLayout.LayoutParams(Ui.dp(context, VoiceBar.ICON.toFloat()), Ui.dp(context, VoiceBar.ICON.toFloat()),
            Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = Ui.dp(context, (WindowChrome.INSET + VoiceBar.ICON + VoiceBar.GAP).toFloat()) })
        sendButton.setOnClickListener { submit() }
        field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        status.setOnClickListener { onRetry?.invoke() }
        val statusRow = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            Ui.gap(this, Ui.dp(context, DOT_GAP))
            addView(dot, LayoutParams(Ui.dp(context, VoiceBar.ONLINE_DOT.toFloat()), Ui.dp(context, VoiceBar.ONLINE_DOT.toFloat())))
            addView(status)
        }
        val words = LinearLayout(context).apply { orientation = VERTICAL; addView(title); addView(statusRow) }
        // A full screen has no corner to close it by: there the chevron leads the header.
        if (frame == ChatFrame.SCREEN) header.addView(chevron, LayoutParams(Ui.dp(context, VoiceBar.ICON.toFloat()), Ui.dp(context, VoiceBar.ICON.toFloat())))
        header.addView(mark, LayoutParams(Ui.dp(context, VoiceBar.CHAT_MARK.toFloat()), Ui.dp(context, VoiceBar.CHAT_MARK.toFloat())))
        header.addView(words, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(compose, LayoutParams(Ui.dp(context, VoiceBar.ICON.toFloat()), Ui.dp(context, VoiceBar.ICON.toFloat())))
        if (frame != ChatFrame.SCREEN) header.addView(chevron, LayoutParams(Ui.dp(context, VoiceBar.ICON.toFloat()), Ui.dp(context, VoiceBar.ICON.toFloat())))
        thread.addView(upcomingSlot, Ui.matchWidth())
        thread.addView(emptySlot, Ui.matchWidth())
        thread.addView(list, Ui.matchWidth())
        thread.addView(typingSlot, Ui.matchWidth())
        scroll.addView(thread, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        threadFrame.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (page) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
        composer.addView(field, LayoutParams(0, Ui.dp(context, VoiceBar.FIELD.toFloat()), 1f))
        composer.addView(sendButton)
        inner.addView(titleBar, Ui.matchWidth())
        inner.addView(header, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, VoiceBar.CHAT_HEADER.toFloat())))
        inner.addView(divider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        inner.addView(threadFrame, if (page) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) else Ui.matchWidth())
        inner.addView(starters, Ui.matchWidth())
        inner.addView(composer, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, VoiceBar.COMPOSER.toFloat())))
        addView(inner, if (page) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) else Ui.matchWidth())
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { sendEnabled(!s.isNullOrBlank()) }
        })
    }

    /**
     * A view between the thread and the composer — GraineChatView puts the
     * agent's components there, so a card sits right under the line that
     * asked for it. Null removes it.
     */
    fun setAccessory(v: View?) {
        accessory?.let { inner.removeView(it) }
        accessory = v
        if (v != null) inner.addView(v, inner.indexOfChild(starters), Ui.matchWidth())
    }

    private var sendFill = 0
    private var sendIcon: Drawable? = null

    private fun sendEnabled(on: Boolean) {
        val icon = sendIcon ?: return
        sendButton.draw(sendFill, icon, "Send", on)
    }

    private fun submit() {
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        field.setText("")
        onSend?.invoke(text)
    }

    private fun px(dp: Double) = Ui.dp(context, dp.toFloat())

    /** Everything a render draws with, resolved once. */
    private data class Look(
        val t: ThemeTokens, val chat: ChatTokens, val accent: Int, val onAccent: Int, val heading: String,
        val hostImage: String?, val logoUrl: String?, val logoPlate: LogoPlate, val loader: LoaderLook, val loaderColor: Int,
    ) {
        /** `chat.title`, or the Appearance heading when the design wrote none. */
        val title: String get() = chat.title.ifEmpty { heading }
    }

    /** One upcoming call as the card draws it. */
    data class UpcomingRow(val id: String, val at: Date, val cancelling: Boolean, val failed: Boolean)

    /**
     * Draw the conversation in the design. Cheap enough to call on every
     * change: only what changed is redrawn.
     *
     * [replying]: the agent is working on a reply — the typing indicator.
     * [failed]: nothing could connect; the status says so and a tap retries.
     * [keyboardUp]: the thread gives up height while the keyboard is up (on the bar).
     * [upcoming]: the customer's booked calls, soonest first; [callName] names them.
     */
    fun render(
        t: ThemeTokens, accent: Int, onAccent: Int, heading: String, hostImage: String?, logoUrl: String?, logoPlate: LogoPlate,
        messages: List<GraineChatTurn>, isOnline: Boolean, replying: Boolean = false, failed: Boolean = false, keyboardUp: Boolean = false,
        loader: LoaderLook = Loaders.resolve(null), loaderColor: Int = accent,
        upcoming: List<UpcomingRow> = emptyList(), callName: String = heading,
    ) {
        val chat = Surfaces.chatOf(t)
        val look = Look(t, chat, accent, onAccent, heading, hostImage, logoUrl, logoPlate, loader, loaderColor)
        // What the window draws of the pieces the design can leave out: earlier messages, New conversation, booked calls.
        val pieces = ChatPieces.of(messages, chat, upcoming.size)
        val shown = pieces.turns
        val c = t.colors
        val r = t.shape.radius
        val bw = Themed.px(context, t.shape.borderWidth)
        val border = Themed.color(c.border)
        val background = Themed.color(chat.background)

        if (frameless) {
            // A whole screen: no outline, corners or shadow. (A sheet's container rounds its top.)
            if ((this.background as? android.graphics.drawable.ColorDrawable)?.color != background) setBackgroundColor(background)
            inner.setPadding(0, 0, 0, 0)
            inner.clipToOutline = false
        } else {
            // The window: the chat's background, outlined, at radius.xl, with its depth; the inside clipped to the corners.
            Themed.surface(this, t, background, Themed.px(context, r.xl).toFloat(), bw, border, t.elevation.floating, 0, 0, 0, 0)
            val innerRadius = Themed.px(context, maxOf(0.0, r.xl - t.shape.borderWidth)).toFloat()
            inner.setPadding(bw, bw, bw, bw)
            inner.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(bw, bw, view.width - bw, view.height - bw, innerRadius)
                }
            }
            inner.clipToOutline = true
        }

        renderHeader(look, isOnline, failed)
        renderCompose(look, pieces)

        // The thread: the chat's background, dotted when the design says so; its frame stands still while the messages scroll.
        threadFrame.background = if (chat.pattern == "dots") DotsDrawable(background, Themed.color(chat.patternColor), resources.displayMetrics.density)
        else android.graphics.drawable.ColorDrawable(background)
        if (!page) (scroll as? CappedScrollView)?.maxHeightPx = Ui.dp(context, if (keyboardUp) VoiceBar.CHAT_LIST_MAX_TYPING else VoiceBar.CHAT_LIST_MAX)
        val pad = px(VoiceBar.CHAT_LIST_PAD)
        thread.setPadding(pad, pad, pad, pad)
        Ui.gap(thread, Themed.px(context, t.spacing.gap))
        Ui.gap(list, Themed.px(context, t.spacing.gap))
        renderUpcoming(look, if (pieces.upcoming) upcoming else emptyList(), callName, pieces.upcomingTitle)
        val grew = drawMessages(look, shown)
        renderEmpty(look, shown.isEmpty())
        renderTyping(look, replying)
        if (grew) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        renderStarters(look, pieces)

        // Composer: on the chat's background, the design's field, the accent send button.
        composer.setBackgroundColor(background)
        composer.setPadding(px(VoiceBar.COMPOSER_PAD), 0, px(VoiceBar.COMPOSER_PAD), 0)
        Ui.gap(composer, px(VoiceBar.COMPOSER_GAP))
        field.hint = chat.placeholder
        field.background = Ui.rounded(Themed.color(chat.composerBackground), Themed.px(context, r.xl).toFloat(), bw, Themed.color(chat.composerBorder))
        field.setPadding(px(VoiceBar.FIELD_PAD), 0, px(VoiceBar.FIELD_PAD), 0)
        field.setTextColor(Themed.color(chat.composerText))
        field.setHintTextColor(Themed.color(chat.composerPlaceholder))
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, (t.font.scale.body - 1).toFloat())
        Themed.face(field, t.font.body, t.font.weights.body, graine)
        sendFill = accent
        sendIcon = IconDrawable(SdkIcons.SEND, t.icons.style, t.icons.stroke, onAccent, px(VoiceBar.ICON))
        sendEnabled(!field.text.isNullOrBlank())
    }

    // ── the header ─────────────────────────────────────────────────────────

    private fun renderHeader(look: Look, isOnline: Boolean, failed: Boolean) {
        val t = look.t
        val chat = look.chat
        val r = t.shape.radius
        val bw = Themed.px(context, t.shape.borderWidth)
        val subtext = Themed.color(chat.headerSubtext)
        val chevronIcon = if (frame == ChatFrame.PAGE) IconDrawable(SdkIcons.CLOSE, t.icons.style, t.icons.stroke, subtext, px(VoiceBar.ICON))
        else IconDrawable(SdkIcons.CHEVRON, t.icons.style, t.icons.stroke, subtext, px(VoiceBar.ICON), flipped = true)
        val window = WindowChrome.titleBar(t, WindowChrome.Surface.CHAT, look.title, isOnline)
        titleBar.visibility = if (window != null) VISIBLE else GONE
        header.visibility = if (window != null) GONE else VISIBLE
        // The title bar draws its own bottom outline.
        divider.visibility = header.visibility
        if (window != null) {
            titleBar.render(window, if (frameless) 0f else Themed.px(context, maxOf(0.0, r.xl - t.shape.borderWidth)).toFloat())
            val closes = frame == ChatFrame.PAGE
            barChevron.setImageDrawable(IconDrawable(if (closes) SdkIcons.CLOSE else SdkIcons.CHEVRON, t.icons.style, t.icons.stroke,
                Themed.color(t.colors.textMuted), px(VoiceBar.ICON), flipped = !closes))
            return
        }
        header.setBackgroundColor(Themed.color(chat.headerBackground))
        header.setPadding(px(VoiceBar.CHAT_HEADER_PAD), 0, px(VoiceBar.CHAT_HEADER_PAD), 0)
        Ui.gap(header, px(VoiceBar.GAP))
        divider.setBackgroundColor(Themed.color(t.colors.border))
        divider.layoutParams = (divider.layoutParams as LayoutParams).apply { height = maxOf(1, bw) }
        mark.visibility = if (chat.avatar == "none") GONE else VISIBLE
        if (chat.avatar != "none") fillAvatar(mark, look, VoiceBar.CHAT_MARK, VoiceBar.CHAT_MARK_GLYPH)
        val titleSp = minOf(t.font.scale.title, VoiceBar.CHAT_TITLE_MAX_SP)
        title.text = look.title
        title.setTextColor(Themed.color(chat.headerText))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp.toFloat())
        Themed.face(title, t.font.heading, t.font.weights.title, graine)
        title.letterSpacing = t.font.letterSpacing.title.toFloat()
        Ui.lineHeight(title, (titleSp * t.font.lineHeight.title).toFloat())
        // The dot: `success` on a surface header, the title's own colour on any other (an accent header).
        val surfaceHeader = Design.normaliseHex(chat.headerBackground) == Design.normaliseHex(t.colors.surface)
        dot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Themed.color(if (surfaceHeader) t.colors.success else chat.headerText))
        }
        dot.visibility = if (isOnline) VISIBLE else GONE
        status.text = when {
            isOnline -> chat.subtitle.ifEmpty { "Online" }
            failed -> FAILED_TEXT
            else -> look.loader.text.connecting ?: "Connecting…"
        }
        status.isClickable = failed && !isOnline
        status.setTextColor(subtext)
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
        Themed.face(status, t.font.body, t.font.weights.body, graine)
        status.letterSpacing = t.font.letterSpacing.body.toFloat()
        chevron.setImageDrawable(chevronIcon)
    }

    /** "New conversation": the compose icon beside the chevron, only while earlier messages are on screen and the design keeps it. */
    private fun renderCompose(look: Look, pieces: ChatPieces) {
        val show = onNewConversation != null && pieces.newConversation
        val t = look.t
        val windowed = titleBar.visibility == VISIBLE
        compose.visibility = if (show && !windowed) VISIBLE else GONE
        barCompose.visibility = if (show && windowed) VISIBLE else GONE
        if (!show) return
        val color = Themed.color(if (windowed) t.colors.textMuted else look.chat.headerSubtext)
        val icon = IconDrawable(SdkIcons.COMPOSE, t.icons.style, t.icons.stroke, color, px(VoiceBar.ICON))
        (if (windowed) barCompose else compose).setImageDrawable(icon)
    }

    /**
     * The chat's avatar, by `chat.avatar`: `logo` — the brand's logo on its
     * plate (no logo: the glyph when there is one, else the title's letter);
     * `glyph` — the launcher mark in the accent. In the launcher's shape, a pill drawn as a circle.
     */
    private fun fillAvatar(box: FrameLayout, look: Look, sizeDp: Double, glyphDp: Double) {
        val t = look.t
        val markShape = if (t.shape.launcher == "pill") "circle" else t.shape.launcher
        val tokens = if (look.chat.avatar == "logo" && !look.logoUrl.isNullOrEmpty()) t.copy(icons = t.icons.copy(launcher = null)) else t
        Mark.fill(box, tokens, sizeDp, glyphDp, markShape,
            if (markShape == "circle") null else Design.launcherCorner(markShape, sizeDp, t.shape.radius),
            look.accent, look.onAccent, look.hostImage, look.logoUrl, look.logoPlate, CallScreen.initial(look.title).ifEmpty { "A" })
    }

    // ── the thread ─────────────────────────────────────────────────────────

    /** True when something was added or grew — the thread then scrolls to its end. */
    private fun drawMessages(look: Look, turns: List<GraineChatTurn>): Boolean {
        // A turn that is only a component draws no bubble: the component is drawn where components are.
        // A conversation that continues: day lines where the day changes, and an earlier call as one row.
        val rows = ChatRows.build(turns.filter { it.text.isNotEmpty() })
        val sameLook = drawnLook == look
        val old = drawnLines
        // The common change: the same conversation, the last line grown or new lines after it.
        val keep = if (!sameLook || rows.size < old.size) 0 else {
            var k = 0
            while (k < old.size && k < rows.size && old[k] == rows[k]) k++
            if (k < old.size - 1) 0 else k
        }
        if (keep == 0) {
            list.removeAllViews()
            lineViews.clear()
        } else {
            while (lineViews.size > keep) list.removeView(lineViews.removeAt(lineViews.size - 1))
        }
        for (i in keep until rows.size) {
            val v = when (val row = rows[i]) {
                is ChatRows.Row.Day -> dayView(look, row)
                is ChatRows.Row.Voice -> callView(look, row)
                is ChatRows.Row.Turn -> lineView(look, row.turn)
            }
            lineViews.add(v)
            list.addView(v)
        }
        val grew = rows.size != old.size || rows.lastOrNull() != old.lastOrNull()
        drawnLines = rows
        drawnLook = look
        applyBubbleMax()
        return grew
    }

    /** A day separator: centred, caption size, strong, muted (DESIGN.md "Day separators"). */
    private fun dayView(look: Look, row: ChatRows.Row.Day): View {
        val t = look.t
        return TextView(context).apply {
            text = ChatRows.dayLabel(row)
            gravity = Gravity.CENTER
            setTextColor(Themed.color(t.colors.textMuted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
            Themed.face(this, t.font.body, t.font.weights.strong, graine)
            letterSpacing = t.font.letterSpacing.body.toFloat()
            isFocusable = true
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** An earlier call, as one row: the call icon and "Voice call · 6:05 PM" — that it happened, never what was said. */
    private fun callView(look: Look, row: ChatRows.Row.Voice): View {
        val t = look.t
        val muted = Themed.color(t.colors.textMuted)
        val clock = android.text.format.DateFormat.getTimeFormat(context)?.format(row.at)
            ?: java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(row.at)
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            Ui.gap(this, px(6.0))
            contentDescription = ChatRows.callSpoken(clock)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(ImageView(context).apply {
                setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, muted, px(14.0)))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LayoutParams(px(14.0), px(14.0)))
            addView(TextView(context).apply {
                text = ChatRows.callLabel(clock)
                maxLines = 1
                setTextColor(muted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
                Themed.face(this, t.font.body, t.font.weights.body, graine)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun applyBubbleMax() {
        for (v in lineViews) bubbleOf(v)?.maxWidth = bubbleMax
    }

    private fun bubbleOf(v: View): TextView? = (v as? TextView) ?: ((v as? LinearLayout)?.getChildAt(0) as? TextView)

    private fun bodyText(t: ThemeTokens, s: String, color: Int) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.body.toFloat())
        Themed.face(this, t.font.body, t.font.weights.body, graine)
        letterSpacing = t.font.letterSpacing.body.toFloat()
        Ui.lineHeight(this, (t.font.scale.body * t.font.lineHeight.body).toFloat())
    }

    /**
     * A bubble, with the line under it when there is one — never inside it:
     * its time, when the design shows times; "Not sent · tap to retry" for a
     * message the chat could not deliver.
     */
    private fun lineView(look: Look, m: GraineChatTurn): View {
        val customer = m.role != Role.AGENT
        val bubble = bubbleView(look.t, m)
        val gravity = if (customer) Gravity.END else Gravity.START
        val failed = customer && m.delivery == GraineChatTurn.Delivery.FAILED
        val under = when {
            failed -> NOT_SENT
            look.chat.showTimestamps -> android.text.format.DateFormat.getTimeFormat(context).format(m.at)
            else -> null
        }
        if (under == null) {
            bubble.layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.gravity = gravity }
            return bubble
        }
        val t = look.t
        return LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(bubble, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { this.gravity = gravity })
            addView(TextView(context).apply {
                text = under
                setTextColor(Themed.color(if (failed) t.colors.danger else t.colors.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, maxOf(10.0, t.font.scale.caption - 1).toFloat())
                Themed.face(this, t.font.body, t.font.weights.body, graine)
                if (failed) { isClickable = true; setOnClickListener { onRetry?.invoke() } }
            }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                this.gravity = gravity
                topMargin = px(TIME_GAP)
            })
        }
    }

    /** One message, per [VoiceBar.bubble]: `radius.lg`, the corner nearest the speaker `radius.sm`. */
    private fun bubbleView(t: ThemeTokens, m: GraineChatTurn): TextView {
        val customer = m.role != Role.AGENT
        val b = VoiceBar.bubble(t, customer)
        return bodyText(t, m.text, Themed.color(b.text)).apply {
            maxWidth = bubbleMax
            // Provisional: the agent's words still arriving, or the customer's waiting for the connection.
            alpha = if (m.live || (customer && m.delivery != GraineChatTurn.Delivery.SENT)) VoiceBar.LIVE_ALPHA else 1f
            if (b.plain) {
                val p = px(2.0)
                setPadding(p, p, p, p)
                return@apply
            }
            val padH = Themed.px(context, t.spacing.cardPadding)
            val padV = Themed.px(context, Math.round(t.spacing.cardPadding * 0.75).toDouble())
            setPadding(padH, padV, padH, padV)
            background = bubbleShape(t, b, customer)
        }
    }

    private fun bubbleShape(t: ThemeTokens, b: VoiceBar.Bubble, customer: Boolean): GradientDrawable {
        val lg = Themed.px(context, t.shape.radius.lg).toFloat()
        val sm = Themed.px(context, t.shape.radius.sm).toFloat()
        return GradientDrawable().apply {
            // Top-left, top-right, bottom-right, bottom-left, each as x and y.
            cornerRadii = if (customer) floatArrayOf(lg, lg, lg, lg, sm, sm, lg, lg) else floatArrayOf(lg, lg, lg, lg, lg, lg, sm, sm)
            setColor(b.fill?.let { Themed.color(it) } ?: android.graphics.Color.TRANSPARENT)
            b.outline?.let { setStroke(Themed.px(context, t.shape.borderWidth), Themed.color(it)) }
        }
    }

    /** The agent is writing: the dashboard's loader (as dots) in an agent bubble — the shape of the reply about to stand there. */
    private fun renderTyping(look: Look, replying: Boolean) {
        typingSlot.removeAllViews()
        typingSlot.visibility = if (replying) VISIBLE else GONE
        if (!replying) return
        val t = look.t
        val b = VoiceBar.bubble(t, customer = false)
        typingSlot.addView(FrameLayout(context).apply {
            contentDescription = "${look.title} is typing"
            val padH = Themed.px(context, t.spacing.cardPadding)
            val padV = Themed.px(context, Math.round(t.spacing.cardPadding * 0.75).toDouble())
            setPadding(padH, padV, padH, padV)
            if (!b.plain) background = bubbleShape(t, b, customer = false)
            addView(GraineLoaderView(context).apply {
                // The dots whatever the dashboard's kind: a ring or the logo breathing in a bubble reads as an error, not typing.
                configure(look.loader.copy(kind = "dots"), look.loaderColor, Themed.color(t.colors.border), TYPING_DOTS, look.logoUrl)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.START))
    }

    /**
     * Before any message, centred in the thread: the avatar at 48 (nothing for
     * `none`), the title 12 below it, and the subtitle 4 below that — only
     * when the design wrote one. No dot here.
     */
    private fun renderEmpty(look: Look, empty: Boolean) {
        emptySlot.removeAllViews()
        emptySlot.visibility = if (empty) VISIBLE else GONE
        if (!empty) return
        val t = look.t
        val col = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        if (look.chat.avatar != "none") {
            val box = FrameLayout(context)
            col.addView(box, LayoutParams(px(EMPTY_AVATAR), px(EMPTY_AVATAR)).apply { bottomMargin = px(12.0) })
            fillAvatar(box, look, EMPTY_AVATAR, EMPTY_AVATAR * Design.LAUNCHER_GLYPH)
        }
        col.addView(TextView(context).apply {
            text = look.title
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Themed.color(t.colors.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, minOf(t.font.scale.title, EMPTY_TITLE_MAX_SP).toFloat())
            Themed.face(this, t.font.heading, t.font.weights.title, graine)
            letterSpacing = t.font.letterSpacing.title.toFloat()
        }, Ui.matchWidth())
        if (look.chat.subtitle.isNotEmpty()) {
            col.addView(TextView(context).apply {
                text = look.chat.subtitle
                gravity = Gravity.CENTER
                setTextColor(Themed.color(t.colors.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
                Themed.face(this, t.font.body, t.font.weights.body, graine)
            }, Ui.matchWidth().apply { topMargin = px(4.0) })
        }
        emptySlot.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            .apply { topMargin = px(if (page) 48.0 else 12.0) })
    }

    /**
     * The starters: the design's suggestions, while the customer has sent
     * nothing in this conversation — right-aligned above the composer, chips
     * 34 high outlined in the accent; a tap sends its words as typed.
     */
    private fun renderStarters(look: Look, pieces: ChatPieces) {
        val t = look.t
        // Never while earlier messages are on screen: there is history to read.
        val show = look.chat.suggestions.isNotEmpty() && pieces.starters
        starters.visibility = if (show) VISIBLE else GONE
        starters.removeAllViews()
        if (!show) return
        val edge = px(12.0)
        starters.setPadding(edge, 0, edge, px(8.0))
        starters.setBackgroundColor(Themed.color(look.chat.background))
        val corner = Themed.px(context, minOf(t.shape.radius.xl, 17.0)).toFloat()
        for (s in look.chat.suggestions) starters.addView(TextView(context).apply {
            text = s
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(Themed.color(t.colors.text))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, (t.font.scale.body - 1).toFloat())
            Themed.face(this, t.font.body, t.font.weights.strong, graine)
            val h = px(14.0)
            setPadding(h, 0, h, 0)
            setMinHeight(px(STARTER_HEIGHT))
            setHeight(px(STARTER_HEIGHT))
            background = Ui.rounded(Themed.color(look.chat.background), corner, maxOf(1, Themed.px(context, t.shape.borderWidth)), look.accent)
            isClickable = true
            isFocusable = true
            setOnClickListener { onSend?.invoke(s) }
        })
    }

    /**
     * UPCOMING CALLS — the customer's booked calls, first in the thread, as a
     * component card: "Upcoming call(s)", then a row per call (at most three):
     * the time in the device's own zone and words, the call name under it,
     * Cancel at the right ("Cancelling…" while it goes; a failure says so and
     * a tap retries).
     */
    private fun renderUpcoming(look: Look, rows: List<UpcomingRow>, callName: String, heading: String) {
        upcomingSlot.removeAllViews()
        upcomingSlot.visibility = if (rows.isEmpty()) GONE else VISIBLE
        if (rows.isEmpty()) return
        val t = look.t
        val c = t.colors
        val bw = Themed.px(context, t.shape.borderWidth)
        val pad = Themed.px(context, t.spacing.cardPadding)
        val card = LinearLayout(context).apply { orientation = VERTICAL }
        val body = LinearLayout(context).apply { orientation = VERTICAL; Ui.gap(this, Themed.px(context, t.spacing.gap)) }
        val window = WindowChrome.titleBar(t, WindowChrome.Surface.COMPONENT, heading)
        val fill = Themed.color(WindowChrome.cardFill(t))
        val radius = Themed.px(context, t.shape.radius.lg).toFloat()
        if (window != null) {
            val bar = WindowTitleBar(context)
            card.addView(bar, Ui.matchWidth())
            card.addView(body, Ui.matchWidth())
            bar.frame(card, body, window, t, fill, radius, bw, Themed.color(c.border), t.elevation.card, pad, pad, pad, pad)
        } else {
            Themed.surface(card, t, fill, radius, bw, Themed.color(c.border), t.elevation.card, pad, pad, pad, pad)
            card.addView(body, Ui.matchWidth())
            body.addView(TextView(context).apply {
                text = heading
                setTextColor(Themed.color(c.textMuted))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
                Themed.face(this, t.font.body, t.font.weights.strong, graine)
            })
        }
        val clock = android.text.format.DateFormat.getTimeFormat(context)
        val locale = Locale.getDefault()
        val dayPattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }.getOrNull() ?: "EEE d MMM"
        val day = java.text.SimpleDateFormat(dayPattern, locale)
        val now = Date()
        for (row in rows) {
            val when_ = when {
                row.cancelling -> UpcomingCalls.CANCELLING
                row.failed -> UpcomingCalls.CANCEL_FAILED
                else -> UpcomingCalls.label(row.at, now, TimeZone.getDefault(), { clock.format(it) }, { day.format(it) })
            }
            body.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // The disc: the accent, the call icon in onAccent.
                addView(FrameLayout(context).apply {
                    background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(look.accent) }
                    addView(ImageView(context).apply {
                        setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, look.onAccent, px(16.0)))
                    }, FrameLayout.LayoutParams(px(16.0), px(16.0), Gravity.CENTER))
                }, LayoutParams(px(32.0), px(32.0)))
                addView(LinearLayout(context).apply {
                    orientation = VERTICAL
                    addView(TextView(context).apply {
                        text = when_
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(Themed.color(c.text))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, (t.font.scale.body - 1).toFloat())
                        Themed.face(this, t.font.body, t.font.weights.strong, graine)
                    })
                    addView(TextView(context).apply {
                        text = callName
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(Themed.color(c.textMuted))
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
                        Themed.face(this, t.font.body, t.font.weights.body, graine)
                    })
                }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = px(10.0); rightMargin = px(8.0) })
                addView(TextView(context).apply {
                    text = "Cancel"
                    gravity = Gravity.CENTER
                    setTextColor(Themed.color(c.text))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
                    Themed.face(this, t.font.body, t.font.weights.strong, graine)
                    setPadding(px(12.0), 0, px(12.0), 0)
                    background = Ui.rounded(android.graphics.Color.TRANSPARENT, Themed.px(context, minOf(t.shape.radius.md, 16.0)).toFloat(),
                        maxOf(1, bw), Themed.color(c.border))
                    isEnabled = !row.cancelling
                    isClickable = !row.cancelling
                    contentDescription = "Cancel the call ${if (when_.isNotEmpty()) when_ else ""}".trim()
                    setOnClickListener { onCancelCall?.invoke(row.id) }
                }, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, px(32.0)))
            }, Ui.matchWidth())
        }
        upcomingSlot.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    companion object {
        /** What the status says when nothing could connect, and a tap retries. */
        const val FAILED_TEXT = "Couldn’t connect · tap to retry"
        /** Under a message the chat could not deliver; a tap retries. */
        const val NOT_SENT = "Not sent · tap to retry"
        /** The typing indicator's dots, dp. */
        const val TYPING_DOTS = 14
        const val DOT_GAP = 6
        const val TIME_GAP = 4.0
        const val EMPTY_AVATAR = 48.0
        const val EMPTY_TITLE_MAX_SP = 18.0
        const val STARTER_GAP = 8
        const val STARTER_HEIGHT = 34.0
    }
}

/**
 * The thread's dots — DESIGN.md "Chat window" → Thread: dots 2 across in
 * `chat.patternColor` on an 18-point grid, the first centred 9 in from the
 * top-left, over the chat's background.
 */
internal class DotsDrawable(private val background: Int, color: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawColor(background)
        val step = 18f * density
        val radius = 1f * density
        var y = b.top + 9f * density
        while (y < b.bottom) {
            var x = b.left + 9f * density
            while (x < b.right) { canvas.drawCircle(x, y, radius, paint); x += step }
            y += step
        }
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}
