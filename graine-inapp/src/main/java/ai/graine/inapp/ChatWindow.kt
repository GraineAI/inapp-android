package ai.graine.inapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.inputmethod.EditorInfo
import android.widget.AbsListView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Date

/**
 * The chat's v2 state a window draws beside its messages — [GraineChat]'s
 * quick replies, end, rating, pinned card, reconnect and the buttons it may
 * offer — read from any [ChatSource] (an app's own carries what it carries).
 */
internal data class ChatExtras(
    val quickReplies: List<GraineQuickReply> = emptyList(),
    val ended: Boolean = false,
    val rating: GraineChatRating? = null,
    val pin: GraineChatPin? = null,
    val connecting: Boolean = false,
    val reconnecting: Boolean = false,
    /** Connected at least once: a reconnect after that is announced. */
    val everConnected: Boolean = false,
    /** The agent takes calls and this SDK can start one from this chat: the microphone and the call button may show. */
    val canCall: Boolean = false,
    /** The app handles attachments ([GraineChat.onAttach]): the paperclip may show. */
    val canAttach: Boolean = false,
) {
    companion object {
        fun of(source: ChatSource, canCall: Boolean): ChatExtras {
            val g = source as? GraineChat
            return ChatExtras(
                quickReplies = source.quickReplies,
                ended = source.ended,
                rating = source.rating,
                pin = g?.pinned,
                connecting = g?.connecting ?: !source.online,
                reconnecting = source.reconnecting,
                everConnected = g?.everConnected ?: false,
                canCall = canCall,
                canAttach = g?.onAttach != null,
            )
        }
    }
}

/**
 * THE CHAT WINDOW — contract/DESIGN.md → Chat window, and every piece of
 * "Chat window v2" from the design's `chat` tokens ([Surfaces.styleOf]): the
 * frame (outlined, `radius.xl`, the floating shadow — none of it when the chat
 * fills the screen); the wallpaper, one surface from the header (when it has
 * no fill of its own) to the composer (when it floats); the header in its
 * style and alignment, its status line and call button; the pinned card as a
 * bar; the calm reconnect banner; the thread — a lazy list of the pinned card,
 * the upcoming calls, the welcome, day lines and earlier calls, the bubbles in
 * their shape and groups with times, marks and status, the agent's quick
 * replies, the starters, the typing indicator and the rating; the
 * scroll-to-latest pill; the docked chips; and the composer — a bar, a pill or
 * a floating capsule, with its microphone, paperclip and call button where the
 * design and the app allow. A design from before v2 is the classic chat,
 * drawn exactly as it always was.
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
) : LinearLayout(context), ThreadHost {
    /** Filling a space of its own rather than riding above the bar: the thread takes the height. */
    private val page = frame != ChatFrame.WINDOW
    /** No outline, corners or shadow of its own: a whole screen, or a sheet (which rounds its own top). */
    private val frameless = frame == ChatFrame.PAGE || frame == ChatFrame.SHEET || frame == ChatFrame.SCREEN
    var onCollapse: (() -> Unit)? = null
    var onSend: ((String) -> Unit)? = null
    /** The customer asked to try again, after nothing could connect — or tapped a message that was not sent. */
    var onRetry: (() -> Unit)? = null
    /** Cancel one of the Upcoming calls. */
    var onCancelCall: ((String) -> Unit)? = null
    /** "Book a call": open the booking sheet. */
    var onBook: (() -> Unit)? = null
    /** "New conversation" — offered in the header only while earlier messages (restored turns) are on screen. */
    var onNewConversation: (() -> Unit)? = null
    /** Call the agent — the microphone or the call button; drawn only where [ChatExtras.canCall]. */
    var onCall: (() -> Unit)? = null
    /** The paperclip; drawn only where [ChatExtras.canAttach]. */
    var onAttach: (() -> Unit)? = null
    /** The customer answered the rating. */
    var onRate: ((String, Int) -> Unit)? = null
    /** A quick reply tapped: the customer's own line. Unset, it is sent with [onSend]. */
    var onQuickReply: ((GraineQuickReply) -> Unit)? = null

    private fun px(dp: Double) = Ui.dp(context, dp.toFloat())
    private val density get() = resources.displayMetrics.density

    // ── the frame and the wallpaper ───────────────────────────────────────

    private val inner = FrameLayout(context)
    private val column = LinearLayout(context).apply { orientation = VERTICAL }
    private var wallpaper: WallpaperDrawable? = null

    // ── the header ────────────────────────────────────────────────────────

    /** The header's place when the design opens the chat as a window. */
    private val titleBar = WindowTitleBar(context).apply { visibility = GONE }
    private val barChevron = icon(if (frame == ChatFrame.PAGE) "Close" else "Collapse")
    private val barCompose = icon("New conversation").apply { visibility = GONE }
    private val barCall = icon("Call").apply { visibility = GONE }
    private val headerBox = FrameLayout(context)
    private val headerFrost = FrostView(context).apply { visibility = GONE }
    private val header = HeaderFrame(context)
    private val leadChevron = icon("Close")
    private val mark = FrameLayout(context)
    private val title = TextView(context).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val dot = View(context)
    private val status = TextView(context).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val statusRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val words = LinearLayout(context).apply { orientation = VERTICAL }
    private val callButton = FrameLayout(context).apply { isClickable = true; isFocusable = true; visibility = GONE; accessibilityDelegate = ButtonRole }
    private val callIcon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val compose = icon("New conversation").apply { visibility = GONE }
    private val chevron = icon(if (frame == ChatFrame.PAGE) "Close" else "Collapse")
    private val divider = View(context)

    /** What the chevron is called: "Collapse" back to a call, "Close" where the chat is all there is. */
    var collapseLabel: String
        get() = chevron.contentDescription?.toString().orEmpty()
        set(v) { chevron.contentDescription = v; barChevron.contentDescription = v; leadChevron.contentDescription = v }

    // ── the pinned bar and the banner ─────────────────────────────────────

    private val contextBar = FrameLayout(context).apply { visibility = GONE; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    private val contextFrost = FrostView(context).apply { visibility = GONE }
    private val contextRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    private val banner = FrameLayout(context).apply { visibility = GONE; accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE }
    private val bannerFrost = FrostView(context).apply { visibility = GONE }
    private val bannerRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
    private var shownBanner = ChatLook.Banner.NONE

    // ── the thread ────────────────────────────────────────────────────────

    private val body = FrameLayout(context)
    private val list = ThreadList(context)
    private val adapter = ThreadAdapter(this)
    private val pill = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        visibility = GONE
        isClickable = true
        isFocusable = true
        contentDescription = ChatLook.SCROLL_TO_LATEST
        accessibilityDelegate = ButtonRole
    }
    private val pillIcon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val pillCount = TextView(context).apply { maxLines = 1; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    /** The thread follows new rows (within 120 of the bottom). */
    private var following = true
    /** The list's scroll state: only the customer's own scrolling (and where it comes to rest) decides [following]. */
    private var scrollState = AbsListView.OnScrollListener.SCROLL_STATE_IDLE
    /** The pill's scroll to the newest row is under way: it is following, whatever passes on the way. */
    private var pinning = false
    /** How tall the thread may grow above the voice bar, dp (the keyboard takes some while it is up). */
    private var capDp = VoiceBar.CHAT_LIST_MAX.toDouble()
    /** Rows that arrived while the customer was reading further up. */
    private var unseen = 0

    // ── the bottom: components, docked chips, the composer ────────────────

    private val floatSlot = FrameLayout(context)
    private val bottomSlot = FrameLayout(context)
    private val bottomStack = LinearLayout(context).apply { orientation = VERTICAL }
    private val accessorySlot = FrameLayout(context)
    private val docked = FrameLayout(context).apply { visibility = GONE }
    private val composerBox = FrameLayout(context)
    /** The capsule's shadow falls into the row's padding (the room under and beside it): never clipped square at its edges. */
    private val composerRow = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipToPadding = false; clipChildren = false }
    /** The floating composer's capsule: its frost and its field, clipped to its corners. */
    private val capsule = FrameLayout(context)
    private var capsuleRadiusPx = 0f
    private val composerFrost = FrostView(context).apply { visibility = GONE }
    private val fieldBox = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val field = EditText(context).apply {
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_SEND
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        background = null
        // The theme's 48 dp would set the field's height; the design's does.
        minHeight = 0
        minimumHeight = 0
    }
    private val send = SendButton(context)
    private val attach = FrameLayout(context).apply { isClickable = true; isFocusable = true; contentDescription = ChatLook.ATTACH; accessibilityDelegate = ButtonRole }
    private val attachIcon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val composerCall = FrameLayout(context).apply { isClickable = true; isFocusable = true; accessibilityDelegate = ButtonRole }
    private val composerCallIcon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var accessory: View? = null

    // ── what is drawn now ─────────────────────────────────────────────────

    override var scene: ChatScene = ChatScene(
        Design.CLASSIC_LIGHT, Surfaces.styleOf(Design.CLASSIC_LIGHT), Surfaces.chatOf(Design.CLASSIC_LIGHT), 0, 0, "", null, null,
        LogoPlate.ACCENT, Loaders.resolve(null), 0, false, false, frame,
    )
        private set
    private var styled: ChatScene? = null
    private var composer = ChatLook.composer(scene.s, canCall = false, canAttach = false)
    private var extras = ChatExtras()
    private var lastMessages: List<GraineChatTurn> = emptyList()
    /** The last render, to draw again when the window learns its layout direction (it is only resolved once attached). */
    private var redraw: (() -> Unit)? = null

    override val agent: GraineInApp get() = graine
    override val fillsSpace: Boolean get() = page
    override val threadWidth: Int get() = maxOf(0, list.width - 2 * px(scene.s.density.edge))

    init {
        orientation = VERTICAL
        chevron.setOnClickListener { onCollapse?.invoke() }
        barChevron.setOnClickListener { onCollapse?.invoke() }
        leadChevron.setOnClickListener { onCollapse?.invoke() }
        compose.setOnClickListener { onNewConversation?.invoke() }
        barCompose.setOnClickListener { onNewConversation?.invoke() }
        callButton.setOnClickListener { onCall?.invoke() }
        barCall.setOnClickListener { onCall?.invoke() }
        composerCall.setOnClickListener { onCall?.invoke() }
        attach.setOnClickListener { onAttach?.invoke() }
        status.setOnClickListener { onRetry?.invoke() }
        pill.setOnClickListener { toLatest() }

        // The title bar: the chevron at its right end, then compose and the call button further in.
        titleBar.trailing(barChevron, px(VoiceBar.ICON))
        titleBar.addView(barCompose, FrameLayout.LayoutParams(px(VoiceBar.ICON), px(VoiceBar.ICON), Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            .apply { rightMargin = px(WindowChrome.INSET + VoiceBar.ICON + VoiceBar.GAP) })
        titleBar.addView(barCall, FrameLayout.LayoutParams(px(VoiceBar.ICON), px(VoiceBar.ICON), Gravity.RIGHT or Gravity.CENTER_VERTICAL)
            .apply { rightMargin = px(WindowChrome.INSET + 2 * (VoiceBar.ICON + VoiceBar.GAP)) })

        // The header: [chevron, full screen only] [avatar] [title and status] [call] [compose] [chevron].
        statusRow.addView(dot, LayoutParams(px(VoiceBar.ONLINE_DOT), px(VoiceBar.ONLINE_DOT)))
        statusRow.addView(status)
        Ui.gap(statusRow, px(ChatWindowView.DOT_GAP.toDouble()))
        words.addView(title)
        words.addView(statusRow)
        header.group.addView(mark, LayoutParams(px(ChatLook.AVATAR_START), px(ChatLook.AVATAR_START)))
        header.group.addView(words, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (frame == ChatFrame.SCREEN) header.leading.addView(leadChevron, LayoutParams(px(VoiceBar.ICON), px(VoiceBar.ICON)))
        callButton.addView(callIcon, FrameLayout.LayoutParams(px(VoiceBar.ICON), px(VoiceBar.ICON), Gravity.CENTER))
        // A 44 target that takes no more room than the icons beside it.
        val overhang = -px((ChatLook.TARGET - VoiceBar.ICON) / 2)
        header.trailing.addView(callButton, LayoutParams(px(ChatLook.TARGET), px(ChatLook.TARGET)).apply { marginStart = overhang; marginEnd = overhang })
        header.trailing.addView(compose, LayoutParams(px(VoiceBar.ICON), px(VoiceBar.ICON)))
        if (frame != ChatFrame.SCREEN) header.trailing.addView(chevron, LayoutParams(px(VoiceBar.ICON), px(VoiceBar.ICON)))
        headerBox.addView(headerFrost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        headerBox.addView(header, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The frost first, the piece's own (translucent) fill over it: a child draws above its parent's background.
        contextBar.addView(contextFrost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contextBar.addView(contextRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        banner.addView(bannerFrost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        banner.addView(bannerRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // The thread: the platform's recycled list, no dividers, no selector, rows that keep their own edges.
        list.adapter = adapter
        list.divider = null
        list.dividerHeight = 0
        list.selector = android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
        list.cacheColorHint = android.graphics.Color.TRANSPARENT
        list.itemsCanFocus = true
        list.clipToPadding = false
        list.clipChildren = false
        list.isVerticalFadingEdgeEnabled = false
        list.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        list.transcriptMode = AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {
                scrollState = state
                // Where a scroll comes to rest decides whether the thread follows new rows.
                if (state == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) { pinning = false; updateFollowing() }
            }
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                // The customer's own scrolling only: a layout that moves the rows (the keyboard, a new row) is not them leaving.
                if (scrollState != AbsListView.OnScrollListener.SCROLL_STATE_IDLE && !pinning) updateFollowing()
            }
        })
        // A new size: a new widest bubble, so the rows on screen are bound again — and a thread that follows stays at its newest row.
        list.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) post { adapter.notifyDataSetChanged() }
        }
        pill.addView(pillIcon, LayoutParams(px(ChatLook.PILL_ICON), px(ChatLook.PILL_ICON)))
        pill.addView(pillCount)
        Ui.gap(pill, px(6.0))
        body.addView(list, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (page) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(floatSlot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        body.addView(pill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        floatSlot.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) post { clearFloating() } }

        // The bottom: a component, the docked chips, the composer — under the thread, or floating over it.
        attach.addView(attachIcon, FrameLayout.LayoutParams(px(ChatLook.SIDE_ICON), px(ChatLook.SIDE_ICON), Gravity.CENTER))
        composerCall.addView(composerCallIcon, FrameLayout.LayoutParams(px(ChatLook.SIDE_ICON), px(ChatLook.SIDE_ICON), Gravity.CENTER))
        composerBox.addView(composerRow, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        capsule.addView(composerFrost, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bottomStack.addView(accessorySlot, Ui.matchWidth())
        bottomStack.addView(docked, Ui.matchWidth())
        bottomStack.addView(composerBox, Ui.matchWidth())
        bottomSlot.addView(bottomStack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        column.addView(titleBar, Ui.matchWidth())
        column.addView(headerBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        column.addView(divider, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        column.addView(contextBar, Ui.matchWidth())
        column.addView(banner, Ui.matchWidth())
        column.addView(body, if (page) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f) else Ui.matchWidth())
        column.addView(bottomSlot, Ui.matchWidth())
        inner.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, if (page) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(inner, if (page) LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) else Ui.matchWidth())

        send.setOnClickListener { if (ChatLook.send(composer, field.text.isNullOrBlank(), scene.title).calls) onCall?.invoke() else submit() }
        field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { submit(); true } else false }
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { drawSend() }
        })
    }

    private fun icon(label: String) = ImageView(context).apply {
        contentDescription = label
        isClickable = true
        isFocusable = true
        accessibilityDelegate = ButtonRole
    }

    /**
     * A view between the thread and the composer — GraineChatView puts the
     * agent's components there, so a card sits right under the line that
     * asked for it. Null removes it.
     */
    fun setAccessory(v: View?) {
        accessory?.let { accessorySlot.removeView(it) }
        accessory = v
        if (v != null) accessorySlot.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun submit() {
        val text = field.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        field.setText("")
        onSend?.invoke(text)
    }

    /** One upcoming call as the card draws it. */
    data class UpcomingRow(val id: String, val at: Date, val cancelling: Boolean, val failed: Boolean)

    // ── ThreadHost ────────────────────────────────────────────────────────

    override fun retry() { onRetry?.invoke() }
    override fun starter(text: String) { onSend?.invoke(text) }
    override fun quickReply(reply: GraineQuickReply) { onQuickReply?.invoke(reply) ?: onSend?.invoke(reply.text) }
    override fun rate(style: String, value: Int) { onRate?.invoke(style, value) }
    override fun cancelCall(id: String) { onCancelCall?.invoke(id) }
    override fun book() { onBook?.invoke() }

    /** Long-press: the plain words to the clipboard, and "Copied" — Android 13+ confirms by itself. */
    override fun copy(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        runCatching { cm.setPrimaryClip(ClipData.newPlainText(ChatLook.COPY, text)) }
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, ChatLook.COPIED, Toast.LENGTH_SHORT).show()
        announceForAccessibility(ChatLook.COPIED)
    }

    // ── render ────────────────────────────────────────────────────────────

    /**
     * Draw the conversation in the design. Cheap enough to call on every
     * change: only what changed is redrawn, and only the rows on screen.
     *
     * [replying]: the agent is working on a reply — the typing indicator.
     * [failed]: nothing could connect; the status says so and a tap retries.
     * [keyboardUp]: the thread gives up height while the keyboard is up (on the bar).
     * [upcoming]: the customer's booked calls, soonest first; [callName] names them.
     * [bookable]: the agent can be booked from the app — "Book a call" in the card, or in its place.
     * [extras]: the v2 state — quick replies, the end, the rating, the pinned card, reconnecting, the call and attach buttons.
     */
    fun render(
        t: ThemeTokens, accent: Int, onAccent: Int, heading: String, hostImage: String?, logoUrl: String?, logoPlate: LogoPlate,
        messages: List<GraineChatTurn>, isOnline: Boolean, replying: Boolean = false, failed: Boolean = false, keyboardUp: Boolean = false,
        loader: LoaderLook = Loaders.resolve(null), loaderColor: Int = accent,
        upcoming: List<UpcomingRow> = emptyList(), callName: String = heading, bookable: Boolean = false,
        extras: ChatExtras = ChatExtras(connecting = !isOnline && !failed),
    ) {
        redraw = { render(t, accent, onAccent, heading, hostImage, logoUrl, logoPlate, messages, isOnline, replying, failed, keyboardUp, loader, loaderColor,
            upcoming, callName, bookable, extras) }
        val chat = Surfaces.chatOf(t)
        val next = ChatScene(t, Surfaces.styleOf(t), chat, accent, onAccent, heading, hostImage, logoUrl, logoPlate, loader, loaderColor,
            rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL, moves = Themed.motionMs(context, t) > 0, frame = frame)
        if (next != scene) scene = next
        this.extras = extras
        val sc = scene
        val s = sc.s
        if (styled != sc) { style(sc); styled = sc }
        composer = ChatLook.composer(s, extras.canCall && onCall != null, extras.canAttach && onAttach != null)

        // What the window draws of the pieces the design can leave out: earlier messages, New conversation, booked calls.
        val pieces = ChatPieces.of(messages, chat, upcoming.size, bookable)
        renderHeader(sc, isOnline, failed, replying, extras)
        renderCompose(sc, pieces)
        renderPinBar(sc, extras.pin)
        renderBanner(sc, isOnline, extras, messages)
        capDp = (if (keyboardUp) VoiceBar.CHAT_LIST_MAX_TYPING else VoiceBar.CHAT_LIST_MAX).toDouble()
        if (!page) list.maxHeightPx = px(capDp) + floatingUnder()
        val chips = ChatLook.chips(s, chat.suggestions, pieces.starters, extras.quickReplies)
        renderThread(sc, pieces, upcoming, callName, replying, chips, extras)
        renderDocked(sc, chips)
        renderComposer(sc)
    }

    // ── the look: what changes only with the design ───────────────────────

    private fun style(sc: ChatScene) {
        val t = sc.t
        val s = sc.s
        val r = t.shape.radius
        val bw = Themed.px(context, t.shape.borderWidth)
        val border = Themed.color(t.colors.border)

        // The frame: outlined, radius.xl, its depth — none of it for a chat that fills the screen.
        val wall = WallpaperDrawable(s.wallpaper, density)
        wallpaper = wall
        if (frameless) {
            background = null
            setBackgroundColor(Themed.color(s.wallpaper.color))
            inner.setPadding(0, 0, 0, 0)
            inner.clipToOutline = false
        } else {
            Themed.surface(this, t, Themed.color(s.wallpaper.color), Themed.px(context, r.xl).toFloat(), bw, border, t.elevation.floating, 0, 0, 0, 0)
            val innerRadius = Themed.px(context, maxOf(0.0, r.xl - t.shape.borderWidth)).toFloat()
            inner.setPadding(bw, bw, bw, bw)
            inner.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(bw, bw, view.width - bw, view.height - bw, innerRadius)
                }
            }
            inner.clipToOutline = true
        }
        inner.background = wall
        for (f in listOf(headerFrost, contextFrost, bannerFrost, composerFrost)) {
            f.wallpaper = wall
            f.blur(s.glass?.blur ?: 0.0)
        }

        // The header's fill: surface, accent, a gradient leading → trailing, or none (the wallpaper runs under it).
        val h = s.header
        val glassy = s.glass != null && (h.style == "surface" || h.style == "plain")
        headerFrost.visibility = if (glassy) VISIBLE else GONE
        header.background = when {
            glassy -> android.graphics.drawable.ColorDrawable(Themed.alpha(Themed.color(h.fill.color), s.glass!!.opacity))
            h.style == "plain" -> null
            h.style == "gradient" -> GradientDrawable(
                if (sc.rtl) GradientDrawable.Orientation.RIGHT_LEFT else GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Themed.color(h.fill.color), Themed.color(h.fill.colorTo ?: h.fill.color)),
            )
            else -> android.graphics.drawable.ColorDrawable(Themed.color(h.fill.color))
        }
        header.setPadding(px(VoiceBar.CHAT_HEADER_PAD), 0, px(VoiceBar.CHAT_HEADER_PAD), 0)
        header.minimumHeight = px(ChatLook.HEADER)
        headerBox.minimumHeight = px(ChatLook.HEADER)
        header.gapPx = px(VoiceBar.GAP)
        header.centered = h.align == "center"
        Ui.gap(header.trailing, px(VoiceBar.GAP))
        Ui.gap(header.group, px(VoiceBar.GAP))
        val markSide = px(if (header.centered) ChatLook.AVATAR_CENTER else ChatLook.AVATAR_START)
        mark.layoutParams = LayoutParams(markSide, markSide)
        words.gravity = if (header.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        title.gravity = if (header.centered) Gravity.CENTER_HORIZONTAL else Gravity.START
        statusRow.gravity = if (header.centered) Gravity.CENTER else Gravity.CENTER_VERTICAL or Gravity.START
        divider.setBackgroundColor(border)
        divider.layoutParams = (divider.layoutParams as LayoutParams).apply { height = maxOf(1, bw) }

        val titleSp = minOf(t.font.scale.title, VoiceBar.CHAT_TITLE_MAX_SP)
        title.setTextColor(Themed.color(h.fill.text))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp.toFloat())
        Themed.face(title, t.font.heading, t.font.weights.title, graine)
        title.letterSpacing = t.font.letterSpacing.title.toFloat()
        Ui.lineHeight(title, (titleSp * t.font.lineHeight.title).toFloat())
        status.setTextColor(Themed.color(h.fill.textMuted))
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
        Themed.face(status, t.font.body, t.font.weights.body, graine)
        status.letterSpacing = t.font.letterSpacing.body.toFloat()
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Themed.color(h.dot)) }
        val muted = Themed.color(h.fill.textMuted)
        chevron.setImageDrawable(if (frame == ChatFrame.PAGE) IconDrawable(SdkIcons.CLOSE, t.icons.style, t.icons.stroke, muted, px(VoiceBar.ICON))
            else IconDrawable(SdkIcons.CHEVRON, t.icons.style, t.icons.stroke, muted, px(VoiceBar.ICON), flipped = true))
        leadChevron.setImageDrawable(IconDrawable(SdkIcons.CHEVRON, t.icons.style, t.icons.stroke, muted, px(VoiceBar.ICON), flipped = true))
        compose.setImageDrawable(IconDrawable(SdkIcons.COMPOSE, t.icons.style, t.icons.stroke, muted, px(VoiceBar.ICON)))
        callIcon.setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, Themed.color(h.fill.text), px(VoiceBar.ICON)))
        val barMuted = Themed.color(t.colors.textMuted)
        barCompose.setImageDrawable(IconDrawable(SdkIcons.COMPOSE, t.icons.style, t.icons.stroke, barMuted, px(VoiceBar.ICON)))
        barCall.setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, barMuted, px(VoiceBar.ICON)))
        val closes = frame == ChatFrame.PAGE
        barChevron.setImageDrawable(IconDrawable(if (closes) SdkIcons.CLOSE else SdkIcons.CHEVRON, t.icons.style, t.icons.stroke, barMuted, px(VoiceBar.ICON), flipped = !closes))

        // The avatar (none for `none`): in the launcher shape, a pill drawn as a circle.
        mark.visibility = if (sc.chat.avatar == "none") GONE else VISIBLE
        if (sc.chat.avatar != "none") {
            val sizeDp = if (header.centered) ChatLook.AVATAR_CENTER else ChatLook.AVATAR_START
            val markShape = if (t.shape.launcher == "pill") "circle" else t.shape.launcher
            val tokens = if (sc.chat.avatar == "logo" && !sc.logoUrl.isNullOrEmpty()) t.copy(icons = t.icons.copy(launcher = null)) else t
            Mark.fill(mark, tokens, sizeDp, sizeDp * VoiceBar.CHAT_MARK_GLYPH / VoiceBar.CHAT_MARK, markShape,
                if (markShape == "circle") null else Design.launcherCorner(markShape, sizeDp, t.shape.radius),
                sc.accent, sc.onAccent, sc.hostImage, sc.logoUrl, sc.logoPlate, CallScreen.initial(sc.title).ifEmpty { "A" })
        }

        // The pinned bar and the banner: the surface (frosted as the header is) and raised surface, a hairline under each.
        val frostOn = s.glass != null
        contextFrost.visibility = if (frostOn) VISIBLE else GONE
        contextRow.background = hairlined(if (frostOn) Themed.alpha(Themed.color(t.colors.surface), s.glass!!.opacity) else Themed.color(t.colors.surface), border, bw)
        contextRow.minimumHeight = px(ChatLook.CONTEXT_BAR)
        contextRow.setPadding(px(12.0), 0, px(12.0), maxOf(1, bw))
        Ui.gap(contextRow, px(10.0))
        bannerFrost.visibility = if (frostOn) VISIBLE else GONE
        bannerRow.background = hairlined(if (frostOn) Themed.alpha(Themed.color(t.colors.surfaceRaised), s.glass!!.opacity) else Themed.color(t.colors.surfaceRaised), border, bw)
        bannerRow.minimumHeight = px(ChatLook.BANNER)
        bannerRow.setPadding(0, 0, 0, maxOf(1, bw))

        // The thread's edges: the rows keep the sides themselves; the list keeps the top and bottom.
        val edge = px(s.density.edge)
        list.setPadding(0, edge, 0, edge)
        list.maxHeightPx = if (page) 0 else px(capDp)

        // The pill: raised, outlined, the card's shadow, the arrow in `text`.
        pill.background = Ui.rounded(Themed.color(t.colors.surfaceRaised), px(ChatLook.PILL / 2).toFloat(), maxOf(1, bw), border)
        Themed.shadow(pill, t.elevation.card)
        pill.minimumHeight = px(ChatLook.PILL)
        pill.setPadding(px(12.0), px(4.0), px(12.0), px(4.0))
        pillIcon.setImageDrawable(IconDrawable(SdkIcons.ARROW_DOWN, t.icons.style, t.icons.stroke, Themed.color(t.colors.text), px(ChatLook.PILL_ICON)))
        pillCount.setTextColor(Themed.color(t.colors.text))
        pillCount.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
        Themed.face(pillCount, t.font.body, t.font.weights.strong, graine)

        styleComposer(sc)
        adapter.notifyDataSetChanged()
    }

    /** A fill with a hairline under it. */
    private fun hairlined(fill: Int, line: Int, width: Int): android.graphics.drawable.Drawable =
        android.graphics.drawable.LayerDrawable(arrayOf(android.graphics.drawable.ColorDrawable(line), android.graphics.drawable.ColorDrawable(fill)))
            .apply { setLayerInset(1, 0, 0, 0, maxOf(1, width)) }

    // ── the header ────────────────────────────────────────────────────────

    private fun renderHeader(sc: ChatScene, isOnline: Boolean, failed: Boolean, typing: Boolean, x: ChatExtras) {
        val t = sc.t
        val s = sc.s
        val window = WindowChrome.titleBar(t, WindowChrome.Surface.CHAT, sc.title, isOnline)
        titleBar.visibility = if (window != null) VISIBLE else GONE
        headerBox.visibility = if (window != null) GONE else VISIBLE
        // The title bar draws its own bottom outline; a header draws one only where the design says.
        divider.visibility = if (window == null && s.header.divider) VISIBLE else GONE
        val calls = ChatLook.headerCall(s, x.canCall && onCall != null)
        if (window != null) {
            titleBar.render(window, if (frameless) 0f else Themed.px(context, maxOf(0.0, t.shape.radius.xl - t.shape.borderWidth)).toFloat())
            barCall.visibility = if (calls) VISIBLE else GONE
            barCall.contentDescription = ChatLook.callLabel(sc.title)
            callButton.visibility = GONE
            return
        }
        barCall.visibility = GONE
        callButton.visibility = if (calls) VISIBLE else GONE
        callButton.contentDescription = ChatLook.callLabel(sc.title)
        title.text = sc.title
        val st = ChatLook.status(s.header, sc.chat.subtitle, ChatLook.typingWords(s.typing), isOnline, x.connecting, x.reconnecting, failed, typing,
            x.ended, sc.loader.text.connecting ?: "Connecting…")
        statusRow.visibility = if (st.kind == ChatLook.StatusKind.NONE) GONE else VISIBLE
        dot.visibility = if (st.dot) VISIBLE else GONE
        status.text = st.words ?: ""
        status.isClickable = st.kind == ChatLook.StatusKind.FAILED
    }

    /** "New conversation": the compose icon beside the chevron, only while earlier messages are on screen and the design keeps it. */
    private fun renderCompose(sc: ChatScene, pieces: ChatPieces) {
        val show = onNewConversation != null && pieces.newConversation
        val windowed = titleBar.visibility == VISIBLE
        compose.visibility = if (show && !windowed) VISIBLE else GONE
        barCompose.visibility = if (show && windowed) VISIBLE else GONE
    }

    // ── the pinned bar ────────────────────────────────────────────────────

    private var drawnPin: Pair<GraineChatPin?, ChatScene>? = null

    /** The card the app pinned, as a bar under the header: thumbnail, title over subtitle, the badge at the trailing end. */
    private fun renderPinBar(sc: ChatScene, pin: GraineChatPin?) {
        val place = ChatLook.pin(pin, sc.s)
        contextBar.visibility = if (place == "bar") VISIBLE else GONE
        if (place != "bar" || pin == null) return
        if (drawnPin == pin to sc) return
        drawnPin = pin to sc
        val t = sc.t
        contextRow.removeAllViews()
        contextBar.contentDescription = ChatLook.pinSpoken(pin)
        contextRow.addView(PinViews.thumb(context, sc, graine, pin, ChatLook.CONTEXT_THUMB, t.shape.radius.sm),
            LayoutParams(px(ChatLook.CONTEXT_THUMB), px(ChatLook.CONTEXT_THUMB)))
        contextRow.addView(LinearLayout(context).apply {
            orientation = VERTICAL
            addView(TextView(context).apply {
                text = pin.title
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(Themed.color(t.colors.text))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, (t.font.scale.body - 1).toFloat())
                Themed.face(this, t.font.body, t.font.weights.strong, graine)
            })
            pin.subtitle?.let { sub ->
                addView(TextView(context).apply {
                    text = sub
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(Themed.color(t.colors.textMuted))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
                    Themed.face(this, t.font.body, t.font.weights.body, graine)
                })
            }
        }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = px(8.0); bottomMargin = px(8.0) })
        pin.badge?.let { contextRow.addView(PinViews.badge(context, sc, graine, it)) }
    }

    // ── the reconnect banner ──────────────────────────────────────────────

    private fun renderBanner(sc: ChatScene, isOnline: Boolean, x: ChatExtras, messages: List<GraineChatTurn>) {
        val waiting = messages.any { it.role == Role.CUSTOMER && it.delivery == GraineChatTurn.Delivery.SENDING }
        val next = ChatLook.banner(isOnline, x.connecting, x.reconnecting, x.everConnected, waiting)
        if (next == shownBanner) return
        val before = shownBanner
        shownBanner = next
        if (next == ChatLook.Banner.NONE) {
            slide(banner, show = false)
            return
        }
        val t = sc.t
        bannerRow.removeAllViews()
        Ui.gap(bannerRow, px(6.0))
        val words = if (next == ChatLook.Banner.RECONNECTING) ChatLook.RECONNECTING else ChatLook.OFFLINE
        if (next == ChatLook.Banner.RECONNECTING) {
            bannerRow.addView(GraineLoaderView(context).apply {
                configure(sc.loader, sc.loaderColor, Themed.color(t.colors.border), ChatLook.BANNER_LOADER, sc.logoUrl)
            }, Ui.wrap())
        } else {
            bannerRow.addView(View(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Themed.color(t.colors.warning)) }
            }, LayoutParams(px(ChatLook.BANNER_DOT), px(ChatLook.BANNER_DOT)))
        }
        bannerRow.addView(TextView(context).apply {
            text = words
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Themed.color(t.colors.textMuted))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
            Themed.face(this, t.font.body, t.font.weights.body, graine)
        })
        banner.contentDescription = words
        if (before == ChatLook.Banner.NONE) slide(banner, show = true)
        // Said once, politely: calm, never a modal.
        announceForAccessibility(words)
    }

    /** In and out over the design's duration, sliding from under the header; none under reduced motion. */
    private fun slide(v: View, show: Boolean) {
        val ms = Themed.motionMs(context, scene.t)
        v.animate().cancel()
        if (ms <= 0) { v.visibility = if (show) VISIBLE else GONE; v.translationY = 0f; v.alpha = 1f; return }
        if (show) {
            v.visibility = VISIBLE
            v.alpha = 0f
            v.translationY = -px(ChatLook.BANNER).toFloat()
            v.animate().alpha(1f).translationY(0f).setDuration(ms).setInterpolator(Themed.interpolator(scene.t.motion)).start()
        } else {
            v.animate().alpha(0f).translationY(-v.height.toFloat()).setDuration(ms).withEndAction { v.visibility = GONE; v.translationY = 0f; v.alpha = 1f }.start()
        }
    }

    // ── the thread ────────────────────────────────────────────────────────

    /** The live turn being revealed, how much of it is shown, and the last frame's time. */
    private var revealId: String? = null
    private var revealShown = 0
    private var revealAt = 0L
    private val revealFrame = Choreographer.FrameCallback { now -> revealStep(now) }
    private var revealPosted = false

    private fun renderThread(
        sc: ChatScene, pieces: ChatPieces, upcoming: List<UpcomingRow>, callName: String, typing: Boolean, chips: ChatLook.Chips, x: ChatExtras,
    ) {
        val s = sc.s
        val d = s.density
        // A turn that is only a component draws no bubble: the component is drawn where components are.
        val turns = pieces.turns.filter { it.text.isNotEmpty() }
        // A reply just begun starts its reveal before its row is drawn, so it never shows whole first.
        lastMessages = pieces.turns
        trackReveal(turns)
        val rows = ChatRows.build(turns)
        val positions = ChatLook.positions(rows)
        val restored = pieces.turns.any { it.restored }
        val items = ArrayList<ThreadItem>()
        var after: String? = null
        fun top(next: String, position: String = ChatRules.SINGLE): Int = if (items.isEmpty()) 0 else px(when {
            after == "hero" -> ChatLook.HERO_AFTER
            next == "replies" -> ChatLook.REPLIES_BELOW
            else -> ChatLook.gapAbove(d, position, s.bubbles.grouping)
        })
        fun add(item: ThreadItem, kind: String) { items.add(item); after = kind }

        if (ChatLook.pin(x.pin, s) == "card") add(ThreadItem.Pin(x.pin!!, top("pin")), "pin")
        if (pieces.upcoming || pieces.book) add(ThreadItem.Upcoming(if (pieces.upcoming) upcoming else emptyList(), pieces.upcomingTitle, callName, pieces.book, top("upcoming")), "upcoming")
        val welcome = ChatLook.welcome(s, rows.isNotEmpty(), restored)
        val startersInWelcome = welcome != null && chips.startersInThread
        if (welcome != null) {
            add(ThreadItem.Welcome(welcome, if (startersInWelcome) chips.starters else emptyList(), chips.startersAsList, top("welcome")), welcome)
        }
        rows.forEachIndexed { i, row ->
            when (row) {
                is ChatRows.Row.Day -> add(ThreadItem.Day(row, top("day")), "day")
                is ChatRows.Row.Voice -> add(ThreadItem.Voice(row, top("voice")), "voice")
                is ChatRows.Row.Turn -> add(ThreadItem.Bubble(row.turn, positions[i], revealShownFor(row.turn), top("bubble", positions[i])), "bubble")
            }
        }
        if (chips.startersInThread && !startersInWelcome) add(ThreadItem.Starters(chips.starters, chips.startersAsList, top("starters")), "starters")
        if (chips.repliesInThread) add(ThreadItem.Replies(chips.replies, top("replies")), "replies")
        if (typing) add(ThreadItem.Typing(top("typing")), "typing")
        if (ChatLook.rating(s, x.ended)) add(ThreadItem.Rating(s.rating.style, x.rating, top("rating")), "rating")

        // Scrolled up: nothing moves the thread, and what arrives is counted on the pill.
        val before = adapter.items.mapTo(HashSet()) { it.key }
        val arrived = items.count { it is ThreadItem.Bubble && it.key !in before }
        if (!following && arrived > 0 && adapter.items.isNotEmpty()) unseen += arrived
        val typingStarted = typing && adapter.items.none { it is ThreadItem.Typing }
        adapter.submit(items)
        if (typingStarted) announceForAccessibility(ChatLook.typingSpoken(sc.title))
        drawPill()
        postReveal()
    }

    // ── following the newest row, and the pill ────────────────────────────

    /** How far the newest row's bottom is below the list's, dp; far when it is not on screen at all. */
    private fun distanceFromBottomDp(): Double {
        val n = adapter.count
        if (n == 0 || list.childCount == 0) return 0.0
        if (list.lastVisiblePosition < n - 1) return Double.MAX_VALUE
        val last = list.getChildAt(list.childCount - 1) ?: return 0.0
        return maxOf(0, last.bottom - (list.height - list.paddingBottom)) / density.toDouble()
    }

    private fun updateFollowing() {
        val now = ChatLook.follows(distanceFromBottomDp())
        if (now == following) return
        following = now
        if (now) unseen = 0
        // Following: every change lands at the bottom. Reading further up: nothing moves the thread.
        list.transcriptMode = if (now) AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL else AbsListView.TRANSCRIPT_MODE_DISABLED
        drawPill()
    }

    private fun drawPill() {
        val show = !following && adapter.count > 0
        pill.visibility = if (show) VISIBLE else GONE
        val count = ChatLook.pillCount(unseen)
        pillCount.visibility = if (count == null) GONE else VISIBLE
        pillCount.text = count ?: ""
        pill.contentDescription = if (count == null) ChatLook.SCROLL_TO_LATEST else "${ChatLook.SCROLL_TO_LATEST}, $count"
    }

    /** The pill tapped: to the newest row — animated, except under reduced motion — and the pill goes. */
    private fun toLatest() {
        val last = adapter.count - 1
        if (last < 0) return
        following = true
        unseen = 0
        list.transcriptMode = AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL
        if (scene.moves) { pinning = true; list.smoothScrollToPosition(last) } else adapter.notifyDataSetChanged()
        drawPill()
    }

    // ── the bottom stack, docked chips and the composer ───────────────────

    /** The bottom stack's place: under the thread for a bar or a pill; floating over its bottom for a capsule. */
    private fun placeBottom(floating: Boolean) {
        val want = if (floating) floatSlot else bottomSlot
        if (bottomStack.parent === want) return
        (bottomStack.parent as? ViewGroup)?.removeView(bottomStack)
        want.addView(bottomStack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        clearFloating()
    }

    /** What floats over the thread's bottom, px: the floating composer and what docks with it. */
    private fun floatingUnder(): Int = if (bottomStack.parent === floatSlot) floatSlot.height else 0

    /**
     * A floating composer's thread scrolls under it, and its last row clears
     * it; the pill sits 8 above it. Above the voice bar the thread's height
     * cap is for what shows, so it grows by what floats over it.
     */
    private fun clearFloating() {
        val edge = px(scene.s.density.edge)
        val under = floatingUnder()
        list.setPadding(0, edge, 0, edge + under)
        if (!page) list.maxHeightPx = px(capDp) + under
        (pill.layoutParams as FrameLayout.LayoutParams).bottomMargin = px(ChatLook.PILL_ABOVE) + under
        pill.requestLayout()
        // Still at the newest row when it followed.
        if (following) adapter.notifyDataSetChanged()
    }

    private var drawnChips: Pair<ChatLook.Chips, ChatScene>? = null

    /** Starters (and, `above-composer`, the quick replies) docked above the composer, on the customer's side. */
    private fun renderDocked(sc: ChatScene, chips: ChatLook.Chips) {
        val show = chips.startersDocked || chips.repliesDocked
        docked.visibility = if (show) VISIBLE else GONE
        if (!show) { drawnChips = null; return }
        if (drawnChips == chips to sc) return
        drawnChips = chips to sc
        docked.removeAllViews()
        val rows = ThreadRows(context, this, sc)
        val views = if (chips.repliesDocked) chips.replies.map { r -> rows.chip(r.label) { quickReply(r) } }
            else chips.starters.map { w -> rows.chip(w) { starter(w) } }
        val edge = px(ChatLook.CHIP_EDGE)
        val row = rows.chipRow(views, trailing = true, edgeIn = edge)
        docked.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        docked.setPadding(0, 0, 0, px(ChatLook.CHIP_ABOVE) - px(4.0))
        // Over a strip, the strip's colour (v1: the chat's background, solid over any pattern); floating, nothing.
        val strip = sc.s.composer.strip
        if (strip != null) docked.setBackgroundColor(Themed.color(strip)) else docked.background = null
    }

    private fun styleComposer(sc: ChatScene) {
        val t = sc.t
        val s = sc.s
        val cp = s.composer
        val bw = Themed.px(context, t.shape.borderWidth)
        val floating = cp.style == "floating"
        placeBottom(floating)
        (field.parent as? ViewGroup)?.removeView(field)
        (send.parent as? ViewGroup)?.removeView(send)
        (attach.parent as? ViewGroup)?.removeView(attach)
        (composerCall.parent as? ViewGroup)?.removeView(composerCall)
        (fieldBox.parent as? ViewGroup)?.removeView(fieldBox)
        (capsule.parent as? ViewGroup)?.removeView(capsule)
        composerRow.removeAllViews()
        fieldBox.removeAllViews()
        capsule.background = null
        capsule.clipToOutline = false
        capsule.elevation = 0f

        // The field: its fill, outline and corners; the words, the placeholder.
        field.hint = sc.chat.placeholder
        field.setTextColor(Themed.color(cp.text))
        field.setHintTextColor(Themed.color(cp.placeholder))
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, (t.font.scale.body - 1).toFloat())
        Themed.face(field, t.font.body, t.font.weights.body, graine)
        val fieldFill = if (s.glass != null && floating) Themed.alpha(Themed.color(cp.fill), s.glass.opacity) else Themed.color(cp.fill)
        val fieldRadius = Themed.px(context, cp.radius).toFloat()
        fieldBox.background = Ui.rounded(fieldFill, fieldRadius, bw, Themed.color(cp.border))
        fieldBox.minimumHeight = px(cp.height)
        attachIcon.setImageDrawable(IconDrawable(SdkIcons.ATTACH, t.icons.style, t.icons.stroke, Themed.color(cp.icon), px(ChatLook.SIDE_ICON)))
        composerCallIcon.setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, Themed.color(cp.icon), px(ChatLook.SIDE_ICON)))
        val side = px(ChatLook.SIDE_TARGET)
        composerFrost.visibility = if (floating && s.glass != null) VISIBLE else GONE
        composerBox.elevation = 0f
        when (cp.style) {
            "pill", "floating" -> {
                // The send button inside the field at its trailing end; the paperclip and the call button inside at its leading end.
                val inset = px(if (floating) ChatLook.SEND_INSET_FLOATING else ChatLook.SEND_INSET_PILL)
                fieldBox.setPadding(inset, 0, inset, 0)
                fieldBox.addView(attach, LayoutParams(side, side))
                fieldBox.addView(composerCall, LayoutParams(side, side))
                fieldBox.addView(field, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                fieldBox.addView(send, Ui.wrap())
                field.setPadding(px(10.0), 0, px(6.0), 0)
                if (floating) {
                    // One raised capsule, 10 above the window's bottom and 10 in from its sides; no strip. Its fill (frosted
                    // with glass), outline and shadow are the capsule's; the field inside it is bare.
                    // The room under and beside it is the row's padding: a margin would shift the centred capsule off its row.
                    val m = px(ChatLook.FLOATING_INSET)
                    composerRow.background = null
                    composerRow.setPadding(m, 0, m, m)
                    // The capsule clips its frost and its field to its corners; the field's own fill (frosted) and outline draw over
                    // the frost. The capsule's outline, from its own size, casts the shadow.
                    capsule.background = null
                    capsuleRadiusPx = fieldRadius
                    capsule.outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, minOf(capsuleRadiusPx, view.height / 2f))
                        }
                    }
                    capsule.clipToOutline = true
                    capsule.addView(fieldBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    composerRow.addView(capsule, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    composerBox.background = null
                    // A translucent (glass) capsule casts no shadow, as the platform rules for any translucent fill.
                    if (s.glass == null) Themed.shadow(capsule, cp.shadow)
                    composerRow.minimumHeight = 0
                } else {
                    composerRow.setBackgroundColor(Themed.color(cp.strip ?: s.wallpaper.color))
                    composerRow.setPadding(px(VoiceBar.COMPOSER_PAD), 0, px(VoiceBar.COMPOSER_PAD), 0)
                    composerRow.minimumHeight = px(ChatLook.STRIP_PILL)
                    composerRow.addView(fieldBox, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    fieldBox.elevation = 0f
                }
            }
            else -> {
                // The bar (v1): a strip 58 high; the paperclip and the call button before the field; the send button 40, 10 from the sides, 8 apart.
                composerRow.setBackgroundColor(Themed.color(cp.strip ?: s.wallpaper.color))
                composerRow.setPadding(px(VoiceBar.COMPOSER_PAD), 0, px(VoiceBar.COMPOSER_PAD), 0)
                composerRow.minimumHeight = px(ChatLook.STRIP_BAR)
                Ui.gap(composerRow, px(VoiceBar.COMPOSER_GAP))
                composerRow.addView(attach, LayoutParams(side, side))
                composerRow.addView(composerCall, LayoutParams(side, side))
                fieldBox.setPadding(0, 0, 0, 0)
                fieldBox.addView(field, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                field.setPadding(px(VoiceBar.FIELD_PAD), 0, px(VoiceBar.FIELD_PAD), 0)
                composerRow.addView(fieldBox, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                composerRow.addView(send, Ui.wrap())
                fieldBox.elevation = 0f
            }
        }
        if (cp.style != "bar") composerRow.showDividers = SHOW_DIVIDER_NONE
    }

    private fun renderComposer(sc: ChatScene) {
        attach.visibility = if (composer.attach) VISIBLE else GONE
        composerCall.visibility = if (composer.call) VISIBLE else GONE
        composerCall.contentDescription = ChatLook.callLabel(sc.title)
        drawSend()
    }

    /** The send button as it stands: the microphone while the field is empty (where the composer calls), else send. */
    private fun drawSend() {
        val sc = scene
        val t = sc.t
        val cp = sc.s.composer
        val st = ChatLook.send(composer, field.text.isNullOrBlank(), sc.title)
        // A host's accent (the bar's own) still paints the classic send button.
        val fill = cp.sendFill?.let { if (Design.normaliseHex(it) == Design.normaliseHex(t.colors.accent)) sc.accent else Themed.color(it) }
        val ink = if (Design.normaliseHex(cp.sendIcon) == Design.normaliseHex(t.colors.onAccent) && fill == sc.accent) sc.onAccent else Themed.color(cp.sendIcon)
        val iconDp = if (cp.send == "icon") ChatLook.SEND_ICON_ONLY else VoiceBar.ICON
        val drawable = IconDrawable(st.icon, t.icons.style, t.icons.stroke, ink, px(iconDp), mirrored = sc.rtl && st.icon == SdkIcons.SEND)
        send.draw(ChatLook.sendSide(composer), if (cp.send == "icon") null else fill, ChatLook.sendCorners(cp.send, t.shape.radius.md), drawable, iconDp,
            st.label, st.enabled)
    }

    // ── the reveal ────────────────────────────────────────────────────────

    /** How much of a turn to draw: its revealed part while the reveal runs, else all of it (null). */
    private fun revealShownFor(turn: GraineChatTurn): Int? = if (turn.id == revealId && revealShown < turn.text.length) revealShown else null

    /**
     * A reply still arriving is revealed smoothly, at least 90 characters a
     * second and never far behind ([ChatRules.revealLength]); when the turn
     * ends the rest is revealed at the same pace, then drawn whole. Only the
     * live turn animates; nothing does when the design or the phone says no motion.
     */
    private fun trackReveal(turns: List<GraineChatTurn>) {
        val last = turns.lastOrNull { it.role == Role.AGENT }
        if (!scene.moves || last == null) { revealId = null; return }
        if (last.live && last.id != revealId) {
            revealId = last.id
            revealShown = 0
            revealAt = System.nanoTime()
        }
    }

    /** The next frame of the reveal, while the turn being revealed has more to show. */
    private fun postReveal() {
        val id = revealId ?: return
        val turn = lastMessages.lastOrNull { it.id == id } ?: return
        if (revealShown < turn.text.length && !revealPosted) {
            revealPosted = true
            Choreographer.getInstance().postFrameCallback(revealFrame)
        }
    }

    private fun revealStep(nowNanos: Long) {
        revealPosted = false
        val id = revealId ?: return
        val turn = lastMessages.lastOrNull { it.id == id } ?: run { revealId = null; return }
        val elapsed = (nowNanos - revealAt) / 1_000_000.0
        revealAt = nowNanos
        revealShown = ChatRules.revealLength(revealShown.toDouble(), turn.text.length.toDouble(), elapsed)
        val i = adapter.items.indexOfFirst { it.key == "turn-$id" }
        if (i >= 0) {
            val item = adapter.items[i] as ThreadItem.Bubble
            val next = adapter.items.toMutableList()
            next[i] = item.copy(turn = turn, shown = if (revealShown < turn.text.length) revealShown else null)
            adapter.submit(next)
        }
        if (revealShown < turn.text.length) { revealPosted = true; Choreographer.getInstance().postFrameCallback(revealFrame) }
        else if (!turn.live) revealId = null
    }

    override fun onDetachedFromWindow() {
        if (revealPosted) { Choreographer.getInstance().removeFrameCallback(revealFrame); revealPosted = false }
        super.onDetachedFromWindow()
    }

    /** Right to left now (or no longer): the speaker's sides, tails, the header's gradient and the send icon mirror. */
    override fun onRtlPropertiesChanged(layoutDirection: Int) {
        super.onRtlPropertiesChanged(layoutDirection)
        if (redraw == null) return  // nothing drawn yet (the first render reads the direction itself)
        if ((layoutDirection == View.LAYOUT_DIRECTION_RTL) != scene.rtl) post { redraw?.invoke() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        postReveal()
    }

    // ── the wallpaper's surface and the frost's places ────────────────────

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        val wall = wallpaper ?: return
        val s = scene.s
        // One surface: from the header when it has no fill of its own, else the thread; to the window's bottom when the composer floats.
        val top = if (s.header.style == "plain" && headerBox.visibility == VISIBLE) headerBox.top else body.top
        wall.surfaceTop = (column.top + top).toFloat()
        wall.surfaceBottom = if (s.composer.style == "floating") inner.height.toFloat() else (column.top + body.bottom).toFloat()
        for (f in listOf(headerFrost, contextFrost, bannerFrost, composerFrost)) {
            if (f.visibility != VISIBLE) continue
            var x = 0f
            var y = 0f
            var v: View? = f
            while (v != null && v !== inner) { x += v.left + v.translationX; y += v.top + v.translationY; v = v.parent as? View }
            f.dx = x
            f.dy = y
            f.hostW = inner.width
            f.hostH = inner.height
            f.invalidate()
        }
    }

    companion object {
        /** What the status says when nothing could connect, and a tap retries. */
        const val FAILED_TEXT = "Couldn’t connect · tap to retry"
        /** Under a message the chat could not deliver; a tap retries. */
        const val NOT_SENT = ChatLook.NOT_SENT
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
