package ai.graine.inapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * THE FACE STAGE'S NUMBERS — contract/DESIGN.md "Face stage", dp and sp.
 * Pure, so a test proves them and the view draws only what these say.
 */
internal object FaceStage {
    const val BACKDROP = "#111111"
    const val AVATAR = 96.0
    const val SIDE = 22.0
    const val HEADER_TOP = 8.0
    const val NAME_SP = 21.0
    const val ROLE_SP = 14.0
    const val ROLE_GAP = 2.0
    const val ROLE_ALPHA = 0.86
    const val LIVE_RED = "#DC2626"
    const val LIVE_SP = 13.0
    const val LIVE_TRACKING_EM = 0.06
    const val LIVE_DOT = 6.0
    const val LIVE_PAD_V = 4.0
    const val LIVE_PAD_H = 10.0
    const val CLOCK_GAP = 8.0
    const val STATUS_ALPHA = 0.86
    /** The top scrim: black at 60% at the top to clear, down to this far below the header. */
    const val TOP_SCRIM_BELOW = 70.0
    const val TOP_SCRIM_ALPHA = 0.6
    /** The bottom scrim: from this far above the caption, clear to 82% black a third of the way down. */
    const val BOTTOM_SCRIM_ABOVE = 120.0
    const val BOTTOM_SCRIM_ALPHA = 0.82
    const val BOTTOM_SCRIM_AT = 0.34
    const val STACK_SIDE = 18.0
    const val STACK_BOTTOM = 28.0
    const val STACK_MAX = 560.0
    const val BUTTON = 58.0
    const val BUTTON_BIG = 70.0
    const val ICON = 26.0
    const val ICON_BIG = 30.0
    const val BUTTON_ALPHA = 0.18
    const val BUTTON_ON_ALPHA = 0.92
    const val ON_ICON = "#111111"
    const val ACCEPT = "#16A34A"
    const val DISABLED_ALPHA = 0.4
    const val COMPONENT_GAP = 22.0
    const val CAPTION_GAP = 14.0
    const val CAPTION_SP = 18.0
    const val CAPTION_LINE = 1.38
    const val CAPTION_LINES = 3
    const val YOU_SP = 11.0
    const val YOU_ALPHA = 0.7
    const val ARRIVING_ALPHA = 0.75
    const val RISE = 8.0
    /** The close control before the name, when the app gives one: its icon, its touch target, its gap to the name. */
    const val CLOSE_ICON = 22.0
    const val CLOSE_TARGET = 44.0
    const val CLOSE_GAP = 12.0

    /**
     * The safe area the stage keeps clear, in px, as [left, top, right, bottom]: the window's system bars and cutout
     * ([bars], the same order) where the view ([viewLeft], [viewTop], [viewWidth] x [viewHeight], in the window)
     * reaches them in a [windowWidth] x [windowHeight] window. Worked out from where the view is, not from insets
     * handed down — a Compose AndroidView is never handed any — so a stage under an app bar, or in a window that
     * already fits the bars, pads nothing there.
     */
    fun safeArea(
        bars: IntArray,
        viewLeft: Int, viewTop: Int, viewWidth: Int, viewHeight: Int,
        windowWidth: Int, windowHeight: Int,
    ): IntArray = intArrayOf(
        (bars[0] - viewLeft).coerceAtLeast(0),
        (bars[1] - viewTop).coerceAtLeast(0),
        (viewLeft + viewWidth - (windowWidth - bars[2])).coerceAtLeast(0),
        (viewTop + viewHeight - (windowHeight - bars[3])).coerceAtLeast(0),
    )

    /** The caption's words: in curly quotes. */
    fun quoted(text: String): String = "“${text.trim()}”"

    /** What VoiceOver and TalkBack say for a button, in the stage's state. */
    fun label(control: GraineStageControl, state: GraineStageState): String = when (control) {
        GraineStageControl.CALL -> "Call"
        GraineStageControl.ANSWER -> "Answer"
        GraineStageControl.MUTE -> if (state.muted) "Unmute" else "Mute"
        GraineStageControl.CHAT -> "Chat"
        GraineStageControl.SPEAKER -> "Speaker, ${if (state.speaker) "on" else "off"}"
        GraineStageControl.END -> if (state.endLabel == AvatarStage.END) "End call" else state.endLabel
    }

    /** The SDK icon a button shows. */
    fun icon(control: GraineStageControl, state: GraineStageState): String = when (control) {
        GraineStageControl.CALL, GraineStageControl.ANSWER -> SdkIcons.CALL
        GraineStageControl.MUTE -> if (state.muted) SdkIcons.MIC_OFF else SdkIcons.MIC
        GraineStageControl.CHAT -> SdkIcons.CHAT
        GraineStageControl.SPEAKER -> SdkIcons.SPEAKER
        GraineStageControl.END -> SdkIcons.END
    }

    /** The big ones: End (and Decline), Call and Answer. */
    fun big(control: GraineStageControl): Boolean =
        control == GraineStageControl.END || control == GraineStageControl.CALL || control == GraineStageControl.ANSWER

    /** A toggle drawn on: muted, the loudspeaker. */
    fun on(control: GraineStageControl, state: GraineStageState): Boolean = when (control) {
        GraineStageControl.MUTE -> state.muted
        GraineStageControl.SPEAKER -> state.speaker
        else -> false
    }

    /** The LIVE pill's fill: the design's danger, else the stage's own red. `t`: the design's LIGHT look. */
    fun liveFill(t: ThemeTokens?): String = t?.colors?.danger ?: LIVE_RED

    /**
     * End's fill (Decline's while ringing), Call's and Answer's: the design's, else its danger or success, else
     * the stage's own. `t` is the design's LIGHT look whatever the phone's appearance (DESIGN.md "Face stage").
     */
    fun fill(control: GraineStageControl, phase: GraineStagePhase, t: ThemeTokens?): String? = when (control) {
        GraineStageControl.END -> if (phase == GraineStagePhase.RINGING) t?.call?.declineFill ?: t?.colors?.danger ?: LIVE_RED
            else t?.call?.endFill ?: t?.colors?.danger ?: LIVE_RED
        GraineStageControl.CALL, GraineStageControl.ANSWER -> t?.call?.acceptFill ?: t?.colors?.success ?: ACCEPT
        else -> null
    }
}

/**
 * THE AGENT'S FACE, EDGE TO EDGE, WITH THE CALL DRAWN OVER IT — the SDK's own
 * face screen (contract/DESIGN.md "Face stage"): the face filling the view,
 * the agent's name and role at the top with LIVE and the call's clock, the
 * line being said, the component the agent drew, and the call's buttons —
 * Call (or Answer and Decline while a native call rings), then Mute, Chat,
 * End and Speaker while it is on. Everything it draws is [GraineStage]
 * (`graine.stage`), public: an app can draw its own over a [GraineAvatarView]
 * instead. New in 0.15.4. Main thread.
 *
 *     <ai.graine.inapp.GraineAvatarStageView
 *         android:id="@+id/stage"
 *         android:layout_width="match_parent"
 *         android:layout_height="match_parent" />
 *
 *     stage.role = "AI care assistant"
 *     stage.onChat = { startActivity(Intent(this, ChatActivity::class.java)) }
 *     stage.onClose = { finish() }                  // a close control before the name; the call is yours to end
 *
 * It places the voice bar's call (mounting the voice engine in its activity
 * if nothing has), or a native call with [calls] = NATIVE. Taking it away
 * never ends a call: the call goes on, voice-only.
 */
class GraineAvatarStageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    /** The agent it draws. Default: the one [GraineInApp.init] made. */
    var graine: GraineInApp? = if (isInEditMode) null else runCatching { GraineInApp.primary() }.getOrNull()
        set(v) {
            if (field === v) return
            unbind()
            field = v
            widget.graine = v
            if (isAttachedToWindow) bind()
        }

    /** The header's name — the stage's own ([GraineStage.name]). Null: the dashboard chat header's title, else the dashboard's heading. */
    var name: String? = null
        set(v) { field = v; graine?.stage?.name = v }

    /** The header's second line ("AI care assistant") — [GraineStage.role]. Null: the dashboard chat header's subtitle, else none. */
    var role: String? = null
        set(v) { field = v; graine?.stage?.role = v }

    /** Which call the Call button places — [GraineStage.calls]: the voice bar's, or a native call. */
    var calls: GraineStageCalls = GraineStageCalls.VOICE
        set(v) { field = v; graine?.stage?.calls = v }

    /** What the chat button does — your app opens its own chat ([GraineStage.onChat]). No button without it. */
    var onChat: (() -> Unit)? = null
        set(v) { field = v; graine?.stage?.onChat = v }

    /**
     * A close control before the name — the SDK's close icon, 22 dp in a 44 dp
     * target — that calls this: your app decides what closing means. It never
     * ends a call by itself. Null (the default): no close control.
     */
    var onClose: (() -> Unit)? = null
        set(v) { field = v; closeButton.visibility = if (v != null) VISIBLE else GONE; requestLayout() }

    /** Mount the voice engine in this view's activity when it appears, if nothing has. */
    var autoAttach: Boolean = true

    /** The name, the role, LIVE and the clock (or the call's words) at the top. */
    var showHeader: Boolean = true
        set(v) { field = v; render() }
    /** The line being said. */
    var showCaption: Boolean = true
        set(v) { field = v; render() }
    /** The component the agent drew. */
    var showComponent: Boolean = true
        set(v) { field = v; render() }
    /** The call's buttons. */
    var showControls: Boolean = true
        set(v) { field = v; render() }

    /** Where the face plays — the stage's own [GraineAvatarView]: lay your own views over this stage, not inside it. */
    val face: GraineAvatarView = GraineAvatarView(context)

    // ── the pieces, bottom to top ──

    private val backdrop = Backdrop(context)
    private val topScrim = Scrim(context, intArrayOf(Themed.alpha(Color.BLACK, FaceStage.TOP_SCRIM_ALPHA), Color.TRANSPARENT), floatArrayOf(0f, 1f))
    private val bottomScrim = Scrim(
        context,
        intArrayOf(Color.TRANSPARENT, Themed.alpha(Color.BLACK, FaceStage.BOTTOM_SCRIM_ALPHA), Themed.alpha(Color.BLACK, FaceStage.BOTTOM_SCRIM_ALPHA)),
        floatArrayOf(0f, FaceStage.BOTTOM_SCRIM_AT.toFloat(), 1f),
    )
    private val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
    private val closeButton = IconButton(context, Math.round(FaceStage.CLOSE_ICON * context.resources.displayMetrics.density).toInt())
    private val nameText = TextView(context)
    private val roleText = TextView(context)
    private val livePill = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val clockText = TextView(context)
    private val statusText = TextView(context)
    private val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val captionBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
    private val youText = TextView(context)
    private val captionText = TextView(context)
    private val widget = GraineWidgetView(context)
    private val controls = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private var state: GraineStageState = GraineStageState()
    private var stopStage: (() -> Unit)? = null
    private var stopDesign: (() -> Unit)? = null
    private var shownControls: List<GraineStageControl> = emptyList()
    private val buttons = mutableMapOf<GraineStageControl, IconButton>()
    private var insetTop = 0
    private var insetBottom = 0
    private var insetLeft = 0
    private var insetRight = 0
    private var hadCaption = false
    private var hadComponent = false

    private fun dp(v: Double): Int = Math.round(v * resources.displayMetrics.density).toInt()

    init {
        setBackgroundColor(Color.parseColor(FaceStage.BACKDROP))
        clipChildren = true
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(face, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(topScrim, LayoutParams(LayoutParams.MATCH_PARENT, 0))
        addView(bottomScrim, LayoutParams(LayoutParams.MATCH_PARENT, 0, Gravity.BOTTOM))

        // The header: the close control (when the app gives one), the name and the role leading, LIVE and the
        // clock (or the call's words) trailing.
        closeButton.contentDescription = "Close"
        closeButton.visibility = GONE
        closeButton.setOnClickListener { onClose?.invoke() }
        header.addView(closeButton, LinearLayout.LayoutParams(dp(FaceStage.CLOSE_ICON), dp(FaceStage.CLOSE_ICON)).apply { marginEnd = dp(FaceStage.CLOSE_GAP) })
        val names = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        nameText.setTextColor(Color.WHITE)
        nameText.setTextSize(TypedValue.COMPLEX_UNIT_SP, FaceStage.NAME_SP.toFloat())
        nameText.maxLines = 1
        nameText.ellipsize = TextUtils.TruncateAt.END
        Ui.weight(nameText, 700)
        roleText.setTextColor(Themed.alpha(Color.WHITE, FaceStage.ROLE_ALPHA))
        roleText.setTextSize(TypedValue.COMPLEX_UNIT_SP, FaceStage.ROLE_SP.toFloat())
        roleText.maxLines = 1
        roleText.ellipsize = TextUtils.TruncateAt.END
        names.addView(nameText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        names.addView(roleText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(FaceStage.ROLE_GAP) })
        header.addView(names, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        livePill.background = Ui.rounded(Color.parseColor(FaceStage.LIVE_RED), dp(20.0).toFloat())
        livePill.setPadding(dp(FaceStage.LIVE_PAD_H), dp(FaceStage.LIVE_PAD_V), dp(FaceStage.LIVE_PAD_H), dp(FaceStage.LIVE_PAD_V))
        val dot = View(context).apply { background = Ui.rounded(Color.WHITE, dp(FaceStage.LIVE_DOT).toFloat()) }
        livePill.addView(dot, LinearLayout.LayoutParams(dp(FaceStage.LIVE_DOT), dp(FaceStage.LIVE_DOT)).apply { marginEnd = dp(6.0) })
        val liveWord = TextView(context).apply {
            text = "LIVE"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FaceStage.LIVE_SP.toFloat())
            letterSpacing = FaceStage.LIVE_TRACKING_EM.toFloat()
            Ui.weight(this, 700)
        }
        livePill.addView(liveWord)
        liveWord.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        livePill.contentDescription = "Live"
        livePill.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        clockText.setTextColor(Color.WHITE)
        clockText.setTextSize(TypedValue.COMPLEX_UNIT_SP, FaceStage.LIVE_SP.toFloat())
        clockText.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        clockText.fontFeatureSettings = "tnum"
        statusText.setTextColor(Themed.alpha(Color.WHITE, FaceStage.STATUS_ALPHA))
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, FaceStage.LIVE_SP.toFloat())
        Ui.weight(statusText, 600)
        val trailing = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        trailing.addView(livePill, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        trailing.addView(clockText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(FaceStage.CLOCK_GAP) })
        trailing.addView(statusText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        header.addView(trailing, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4.0); marginStart = dp(12.0) })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))

        // The bottom stack: the caption, the component, the buttons.
        youText.text = "YOU"
        youText.setTextColor(Themed.alpha(Color.WHITE, FaceStage.YOU_ALPHA))
        youText.setTextSize(TypedValue.COMPLEX_UNIT_SP, FaceStage.YOU_SP.toFloat())
        youText.letterSpacing = 0.06f
        Ui.weight(youText, 600)
        youText.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        captionText.setTextColor(Color.WHITE)
        captionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, FaceStage.CAPTION_SP.toFloat())
        captionText.gravity = Gravity.CENTER_HORIZONTAL
        captionText.setLineSpacing(0f, FaceStage.CAPTION_LINE.toFloat())
        Ui.weight(captionText, 500)
        captionBox.addView(youText, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4.0) })
        captionBox.addView(captionText, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stack.addView(captionBox, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        widget.graine = graine
        stack.addView(widget, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        stack.addView(controls, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        setOnApplyWindowInsetsListener { v, insets ->
            if (readSafeArea()) place()
            v.onApplyWindowInsets(insets)
        }
        // The design's margins from the first layout, before (or without) any safe area.
        place()
        render()
    }

    @Suppress("DEPRECATION")
    /**
     * The safe area this view meets ([FaceStage.safeArea]): the window's system bars and cutout where the view reaches
     * them. Read from the window, not from insets handed down — a Compose AndroidView is never handed any — on every
     * layout and whenever insets change. True when it moved (the margins are placed again).
     */
    private fun readSafeArea(): Boolean {
        val insets = rootWindowInsets ?: return false
        val window = rootView ?: return false
        val bars = if (Build.VERSION.SDK_INT >= 30) {
            val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            intArrayOf(i.left, i.top, i.right, i.bottom)
        } else {
            intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
        }
        val at = IntArray(2).also { getLocationInWindow(it) }
        val safe = FaceStage.safeArea(bars, at[0], at[1], width, height, window.width, window.height)
        if (safe[0] == insetLeft && safe[1] == insetTop && safe[2] == insetRight && safe[3] == insetBottom) return false
        insetLeft = safe[0]; insetTop = safe[1]; insetRight = safe[2]; insetBottom = safe[3]
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
        bind()
    }

    override fun onDetachedFromWindow() {
        unbind()
        lightStatusIcons(false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Hidden (it, or a screen it is on), it gives the status bar back; shown again, it asks again.
        if (isAttachedToWindow) lightStatusIcons(isShown && insetTop > 0)
    }

    // ── the status bar over the stage ──

    /** The window's status-bar icons were dark (true) or light before the stage asked for light ones; null: it has not asked. */
    private var statusIconsWereDark: Boolean? = null

    /**
     * The stage is dark in every design: while it lies under the status bar it asks for light status-bar icons, as the
     * iOS stage's are, and gives back exactly what it found when it leaves, hides or moves off the bar.
     */
    private fun lightStatusIcons(want: Boolean) {
        val window = Ui.activity(context)?.window ?: return
        if (want) {
            if (statusIconsWereDark != null) return
            val dark = statusIconsDark(window)
            statusIconsWereDark = dark
            if (dark) setStatusIconsDark(window, false)
        } else {
            val was = statusIconsWereDark ?: return
            statusIconsWereDark = null
            if (was) setStatusIconsDark(window, true)
        }
    }

    /** Dark icons (a light status bar) — the legacy flag is read too: Android 11 leaves the theme's choice out of the controller. */
    @Suppress("DEPRECATION")
    private fun statusIconsDark(window: android.view.Window): Boolean {
        val legacy = (window.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0
        if (Build.VERSION.SDK_INT < 30) return legacy
        val controller = window.decorView.windowInsetsController ?: return legacy
        return legacy || (controller.systemBarsAppearance and android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS) != 0
    }

    @Suppress("DEPRECATION")
    private fun setStatusIconsDark(window: android.view.Window, dark: Boolean) {
        runCatching {
            val decor = window.decorView
            val flags = decor.systemUiVisibility
            decor.systemUiVisibility = if (dark) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 30) {
                val bit = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                window.decorView.windowInsetsController?.setSystemBarsAppearance(if (dark) bit else 0, bit)
            }
        }
    }

    private fun bind() {
        val g = graine ?: return
        val stage = g.stage
        // The view's words are the stage's own, so a screen of the app's reads the same.
        name?.let { stage.name = it }
        role?.let { stage.role = it }
        stage.calls = calls
        onChat?.let { stage.onChat = it }
        if (autoAttach && !g.voice.attached) Ui.activity(context)?.let { g.voice.attach(it) }
        // A connect is the customer's wait while the stage is on screen (GraineVoice.slowConnect), as the bar's is.
        g.voice.barShown(this, true)
        stopDesign = g.onDesignChange { applyDesign() }
        stopStage = stage.addListener { s -> state = s; render() }
        applyDesign()
    }

    private fun unbind() {
        removeCallbacks(tick)
        stopStage?.invoke(); stopStage = null
        stopDesign?.invoke(); stopDesign = null
        graine?.voice?.barShown(this, false)
    }

    // ── where things go ──

    private fun place() {
        if (isAttachedToWindow) lightStatusIcons(isShown && insetTop > 0)
        val side = dp(FaceStage.SIDE)
        (header.layoutParams as LayoutParams).apply {
            leftMargin = side + insetLeft; rightMargin = side + insetRight; topMargin = insetTop + dp(FaceStage.HEADER_TOP)
        }
        (stack.layoutParams as LayoutParams).apply {
            leftMargin = dp(FaceStage.STACK_SIDE) + insetLeft; rightMargin = dp(FaceStage.STACK_SIDE) + insetRight
            bottomMargin = insetBottom + dp(FaceStage.STACK_BOTTOM)
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The bottom stack at most 560 wide, centred.
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val lp = stack.layoutParams as LayoutParams
        val room = width - lp.leftMargin - lp.rightMargin
        lp.width = if (room > dp(FaceStage.STACK_MAX)) dp(FaceStage.STACK_MAX) else LayoutParams.MATCH_PARENT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Where the view is decides what it must keep clear: placed again on the next turn, outside this layout pass.
        if (readSafeArea()) post { place() }
        // The scrims follow what they sit under: 70 below the header; from 120 above the caption (or what tops the stack).
        val topH = if (header.visibility == VISIBLE) header.bottom + dp(FaceStage.TOP_SCRIM_BELOW) else 0
        val stackTop = firstShownTop()
        val bottomH = if (stackTop == null) 0 else (height - stackTop + dp(FaceStage.BOTTOM_SCRIM_ABOVE)).coerceAtMost(height)
        resize(topScrim, topH)
        resize(bottomScrim, bottomH)
        placeClose()
    }

    /** The close icon centred on the name's first line, and touchable over 44 dp around it. */
    private fun placeClose() {
        if (closeButton.visibility != VISIBLE || header.visibility != VISIBLE) { touchDelegate = null; return }
        val names = nameText.parent as? View ?: return
        val lineCentre = names.top + nameText.top + nameText.paddingTop + nameText.lineHeight / 2f
        closeButton.translationY = lineCentre - (closeButton.top + closeButton.height / 2f)
        val r = android.graphics.Rect()
        closeButton.getHitRect(r)
        r.offset(header.left, header.top)
        val grow = (dp(FaceStage.CLOSE_TARGET) - closeButton.width) / 2
        r.inset(-grow, -grow)
        touchDelegate = android.view.TouchDelegate(r, closeButton)
    }

    private fun firstShownTop(): Int? {
        for (i in 0 until stack.childCount) {
            val c = stack.getChildAt(i)
            if (c.visibility == VISIBLE && c.height > 0) return stack.top + c.top
        }
        return null
    }

    private fun resize(v: View, h: Int) {
        val lp = v.layoutParams
        if (lp.height == h) return
        lp.height = h
        // Outside the layout pass that measured it.
        post { v.layoutParams = lp }
    }

    // ── what it shows ──

    private fun tokens(): ThemeTokens? = runCatching { graine?.themeTokens(context) }.getOrNull()

    /**
     * The design's LIGHT look, whatever the phone's appearance: the stage's fills (LIVE, End and Decline, Call
     * and Answer) come from it. A dark look's danger is often a pale tint meant for text on dark surfaces, and
     * on the stage it would read washed out; only the white ink and the scrims are the stage's own.
     */
    private fun fills(): ThemeTokens? = runCatching { graine?.design?.light }.getOrNull()

    private fun applyDesign() {
        val g = graine ?: return
        val t = tokens()
        if (t != null) {
            Themed.face(nameText, t.font.heading, t.font.weights.title, g)
            Themed.face(roleText, t.font.body, t.font.weights.body, g)
            Themed.face(captionText, t.font.body, 500, g)
            Themed.face(statusText, t.font.body, 600, g)
        }
        livePill.background = Ui.rounded(Themed.color(FaceStage.liveFill(fills()), Color.parseColor(FaceStage.LIVE_RED)), dp(20.0).toFloat())
        backdrop.update(g, t)
        closeButton.paint(Color.TRANSPARENT, SdkIcons.CLOSE, Color.WHITE, t?.icons?.style, t?.icons?.stroke ?: 2.0)
        shownControls = emptyList()
        render()
    }

    private fun render() {
        val s = state
        // The header.
        header.visibility = if (showHeader) VISIBLE else GONE
        nameText.text = s.name
        roleText.text = s.role.orEmpty()
        roleText.visibility = if (s.role.isNullOrEmpty()) GONE else VISIBLE
        val clockOn = s.live && s.startedAt != null
        livePill.visibility = if (s.live) VISIBLE else GONE
        clockText.visibility = if (clockOn) VISIBLE else GONE
        statusText.text = s.status
        statusText.visibility = if (!s.live && s.status.isNotEmpty()) VISIBLE else GONE
        removeCallbacks(tick)
        if (clockOn) tick.run()

        // The caption.
        val cap = s.caption?.takeIf { showCaption }
        captionBox.visibility = if (cap != null) VISIBLE else GONE
        if (cap != null) {
            youText.visibility = if (cap.who == Role.CUSTOMER) VISIBLE else GONE
            captionText.text = fitted(FaceStage.quoted(cap.text))
            captionText.alpha = if (cap.final) 1f else FaceStage.ARRIVING_ALPHA.toFloat()
            captionText.contentDescription = (if (cap.who == Role.CUSTOMER) "You: " else "${s.name}: ") + cap.text
            if (!hadCaption) rise(captionBox)
        }
        hadCaption = cap != null

        // The component: the SDK's own view, which draws whatever the agent put on screen.
        val component = showComponent && (s.component != null || s.componentPending)
        widget.visibility = if (showComponent) VISIBLE else GONE
        (captionBox.layoutParams as LinearLayout.LayoutParams).bottomMargin =
            dp(if (component) FaceStage.CAPTION_GAP else FaceStage.COMPONENT_GAP)
        (widget.layoutParams as LinearLayout.LayoutParams).bottomMargin = if (component) dp(FaceStage.COMPONENT_GAP) else 0
        if (component && !hadComponent) rise(widget)
        hadComponent = component

        // The buttons.
        controls.visibility = if (showControls) VISIBLE else GONE
        if (s.controls != shownControls) buildControls(s.controls)
        val t = tokens()
        val light = fills()
        for ((control, b) in buttons) {
            val on = FaceStage.on(control, s)
            val fill = FaceStage.fill(control, s.phase, light)
            b.paint(
                fill = if (fill != null) Themed.color(fill) else if (on) Themed.alpha(Color.WHITE, FaceStage.BUTTON_ON_ALPHA) else Themed.alpha(Color.WHITE, FaceStage.BUTTON_ALPHA),
                icon = FaceStage.icon(control, s),
                ink = if (fill == null && on) Color.parseColor(FaceStage.ON_ICON) else Color.WHITE,
                style = t?.icons?.style,
                stroke = t?.icons?.stroke ?: 2.0,
            )
            val enabled = s.canUse(control)
            b.isEnabled = enabled
            b.alpha = if (enabled) 1f else FaceStage.DISABLED_ALPHA.toFloat()
            b.contentDescription = FaceStage.label(control, s)
        }
        requestLayout()
    }

    private fun buildControls(list: List<GraineStageControl>) {
        controls.removeAllViews()
        buttons.clear()
        shownControls = list
        for (control in list) {
            val big = FaceStage.big(control)
            val size = dp(if (big) FaceStage.BUTTON_BIG else FaceStage.BUTTON)
            val b = IconButton(context, dp(if (big) FaceStage.ICON_BIG else FaceStage.ICON))
            b.setOnClickListener { act(control) }
            buttons[control] = b
            // Spread evenly across the row: each in a cell of its own.
            val cell = FrameLayout(context)
            cell.addView(b, LayoutParams(size, size, Gravity.CENTER))
            controls.addView(cell, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun act(control: GraineStageControl) {
        val stage = graine?.stage ?: return
        if (!state.canUse(control)) return
        when (control) {
            GraineStageControl.CALL -> stage.call(Ui.activity(context))
            GraineStageControl.ANSWER -> stage.answer()
            GraineStageControl.MUTE -> stage.setMuted(!state.muted)
            GraineStageControl.CHAT -> stage.chat()
            GraineStageControl.END -> stage.end()
            GraineStageControl.SPEAKER -> stage.setSpeaker(!state.speaker)
        }
    }

    /** The clock, every second on the second of the call. */
    private val tick: Runnable = object : Runnable {
        override fun run() {
            val at = state.startedAt ?: return
            if (!state.live) return
            val elapsed = System.currentTimeMillis() - at
            clockText.text = GraineStage.clock(elapsed)
            clockText.contentDescription = clockText.text
            postDelayed(this, 1000 - (maxOf(0L, elapsed) % 1000))
        }
    }

    /** At most three lines; a longer line keeps its end — the words being said now — and drops its start. */
    private fun fitted(text: String): CharSequence {
        val width = (stack.width.takeIf { it > 0 } ?: (resources.displayMetrics.widthPixels - 2 * dp(FaceStage.STACK_SIDE)))
            .coerceAtLeast(1)
        val paint = TextPaint(captionText.paint)
        fun lines(s: CharSequence): Int = StaticLayout.Builder.obtain(s, 0, s.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(0f, FaceStage.CAPTION_LINE.toFloat())
            .build().lineCount
        if (lines(text) <= FaceStage.CAPTION_LINES) return text
        val words = text.removePrefix("“").split(' ')
        var from = 1
        while (from < words.size) {
            val tail = "“…" + words.drop(from).joinToString(" ")
            if (lines(tail) <= FaceStage.CAPTION_LINES) return tail
            from++
        }
        return "“…" + words.last()
    }

    /** An arrival rises into place in the design's motion; nothing moves when the phone turned animations off. */
    private fun rise(v: View) {
        val ms = tokens()?.motion?.durationMs ?: 220
        if (ms <= 0 || (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) return
        v.translationY = dp(FaceStage.RISE).toFloat()
        v.alpha = 0f
        v.animate().translationY(0f).alpha(1f).setDuration(ms.toLong()).start()
    }

    /** #111 and the brand's picture of the agent in its middle — seen until the face's first frame, and on a call with none. */
    private class Backdrop(context: Context) : View(context) {
        private var picture: Bitmap? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private var initial = "A"
        private var accent = Color.parseColor(Appearance.DEFAULT_ACCENT)
        private var asked: String? = null

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun update(g: GraineInApp, t: ThemeTokens?) {
            val dash = Appearance.resolve(g.appearance, Appearance.DEFAULT_ACCENT, "Assistant", origin = g.config.baseUrl)
            initial = dash.heading.trim().take(1).uppercase().ifEmpty { "A" }
            accent = Appearance.argb(t?.colors?.accent ?: dash.accent) ?: accent
            val call = t?.call
            val key = "${t?.preset}|${call?.avatar}|${dash.logoUrl}|$initial"
            if (key == asked) return
            asked = key
            picture = null
            if (t != null && call != null) {
                val side = Math.round(FaceStage.AVATAR * resources.displayMetrics.density).toInt()
                picture = CallAvatar.bitmap(context, t, call, dash.logoUrl, dash.logoPlate, initial, side) { ready -> post { picture = ready; invalidate() } }
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val side = Math.round(FaceStage.AVATAR * resources.displayMetrics.density).toFloat()
            val l = (width - side) / 2f
            val t = (height - side) / 2f
            val box = RectF(l, t, l + side, t + side)
            val bmp = picture
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, box, paint)
                return
            }
            paint.color = accent
            canvas.drawOval(box, paint)
            paint.color = Color.WHITE
            paint.textSize = side * 0.42f
            paint.textAlign = Paint.Align.CENTER
            paint.isFakeBoldText = true
            canvas.drawText(initial, box.centerX(), box.centerY() - (paint.descent() + paint.ascent()) / 2, paint)
        }
    }

    /** A gradient band: the scrims under the header and the bottom stack, so white ink reads on any face. */
    private class Scrim(context: Context, private val colors: IntArray, private val stops: FloatArray) : View(context) {
        private val paint = Paint()

        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            paint.shader = if (h > 0) LinearGradient(0f, 0f, 0f, h.toFloat(), colors, stops, Shader.TileMode.CLAMP) else null
        }

        override fun onDraw(canvas: Canvas) {
            if (height > 0) canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    /** One round button — or the bare close icon: its fill (transparent for none) and the SDK's icon in the middle. */
    private class IconButton(context: Context, private val iconPx: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var fill = Color.TRANSPARENT
        private var icon = SdkIcons.CALL
        private var ink = Color.WHITE
        private var style: String? = null
        private var stroke = 2.0
        private var drawable: IconDrawable? = null

        init {
            isClickable = true
            isFocusable = true
        }

        fun paint(fill: Int, icon: String, ink: Int, style: String?, stroke: Double) {
            if (fill == this.fill && icon == this.icon && ink == this.ink && style == this.style && stroke == this.stroke && drawable != null) return
            this.fill = fill; this.icon = icon; this.ink = ink; this.style = style; this.stroke = stroke
            drawable = IconDrawable(icon, style, stroke, ink, iconPx)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (Color.alpha(fill) > 0) {
                paint.color = fill
                canvas.drawOval(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            val d = drawable ?: return
            val l = (width - iconPx) / 2
            val t = (height - iconPx) / 2
            d.setBounds(l, t, l + iconPx, t + iconPx)
            d.draw(canvas)
        }

        override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
    }
}
