package ai.graine.inapp

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * A BIGGER CHAT WINDOW the bar opens over the app — contract/DESIGN.md "Chat
 * window" → Size, where ChatWindowLayout puts it:
 *
 *  - a SHEET, on a phone: up from the bottom, the full width, its top edge 8%
 *    of the screen below the status bar; corners at the top only; the app
 *    dimmed behind it (a tap there, or Back, closes it); a grab handle — the
 *    handle or the header dragged down far enough, or flung, closes it too;
 *  - a PANEL, on a tablet: 420 dp wide, min(760 dp, 90% of the screen) tall,
 *    in the bottom corner 24 dp in, the whole frame, no dimming — the app stays
 *    usable beside it.
 *
 * A plain [Dialog]: the SDK has no dependencies, so no Material bottom sheet.
 * Its own window, so it sits above the app's views wherever the bar was put,
 * and resizes for the keyboard so the composer stays above it.
 */
internal class ChatWindowDialog(
    context: Context,
    graine: GraineInApp,
    val presentation: ChatWindowLayout.Presentation,
    /** Closed by the customer — Back, a tap outside, a drag: what the chevron does. */
    private val onClosed: () -> Unit,
) : Dialog(context) {
    val chat = ChatWindowView(context, graine, if (presentation == ChatWindowLayout.Presentation.SHEET) ChatFrame.SHEET else ChatFrame.PANEL)
    private val sheet = presentation == ChatWindowLayout.Presentation.SHEET
    private val handleBar = View(context)
    private val handle = FrameLayout(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(handleBar, FrameLayout.LayoutParams(dp(ChatWindowLayout.HANDLE_WIDTH), dp(ChatWindowLayout.HANDLE_HEIGHT), Gravity.CENTER))
    }
    private val body = SheetBody(context)
    private var closedOnce = false
    private var tokens: ThemeTokens? = null

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        body.orientation = LinearLayout.VERTICAL
        if (sheet) body.addView(handle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ChatWindowLayout.HANDLE_STRIP)))
        body.addView(chat, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        // A panel leaves room around itself for its shadow, which its window would otherwise cut off.
        val room = if (sheet) 0 else dp(PANEL_SHADOW_ROOM)
        val holder = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
            setPadding(room, room, room, room)
            addView(body, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        setContentView(holder)
        setCancelable(true)
        setCanceledOnTouchOutside(sheet)
        setOnCancelListener { closed() }
    }

    override fun onStart() {
        super.onStart()
        place()
    }

    /** Put the window where the presentation says, at its size; again on a rotation. */
    fun place() {
        val w = window ?: return
        val metrics = context.resources.displayMetrics
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        @Suppress("DEPRECATION")
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val moves = Themed.motionMs(context, tokens) > 0
        if (sheet) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ChatWindowLayout.sheetHeight(metrics.heightPixels, statusBarPx()))
            w.setGravity(Gravity.BOTTOM)
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setDimAmount(ChatWindowLayout.SCRIM_ALPHA)
            // Up from the bottom and back, unless the design (or the phone) moves nothing.
            w.setWindowAnimations(if (moves) android.R.style.Animation_InputMethod else 0)
        } else {
            val (widthDp, heightDp) = ChatWindowLayout.panelSize(metrics.heightPixels / metrics.density.toDouble())
            val room = dp(PANEL_SHADOW_ROOM)
            w.setLayout(dp(widthDp) + 2 * room, dp(heightDp) + 2 * room)
            w.setGravity(Gravity.BOTTOM or Gravity.END)
            w.attributes = w.attributes.apply {
                x = dp(ChatWindowLayout.PANEL_INSET) - room
                y = dp(ChatWindowLayout.PANEL_INSET) - room
            }
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            // Touches beside the panel reach the app.
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
            w.setWindowAnimations(if (moves) android.R.style.Animation_Toast else 0)
        }
    }

    /**
     * Draw it in the design: the chat window as the bar draws it, a sheet's
     * fill and top corners, its handle in the header's fill.
     */
    fun paint(t: ThemeTokens) {
        val first = tokens == null
        tokens = t
        val style = Surfaces.styleOf(t)
        if (sheet) {
            val r = Themed.px(context, t.shape.radius.xl).toFloat()
            body.background = GradientDrawable().apply {
                setColor(Themed.color(style.wallpaper.color))
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            body.outlineProvider = ViewOutlineProvider.BACKGROUND
            body.clipToOutline = true
            // The floating shadow, unless the design draws a hard offset — which has nowhere to show at the screen's edges.
            if (t.elevation.hardOffset == null) Themed.shadow(body, t.elevation.floating) else body.elevation = 0f
            // The handle's strip in the header's fill (a plain header's is the wallpaper's top; the title bar's with window chrome).
            val windowed = WindowChrome.titleBar(t, WindowChrome.Surface.CHAT, "", false) != null
            handle.setBackgroundColor(Themed.color(if (windowed) t.colors.surfaceRaised else style.header.fill.color))
            handleBar.background = Ui.rounded(
                Themed.alpha(Themed.color(if (windowed) t.colors.textMuted else style.header.fill.textMuted), 0.5),
                dp(ChatWindowLayout.HANDLE_HEIGHT / 2).toFloat(),
            )
        }
        if (first && isShowing) place()
    }

    private fun closed() {
        if (closedOnce) return
        closedOnce = true
        onClosed()
    }

    @Suppress("DEPRECATION")
    private fun statusBarPx(): Int {
        val insets = Ui.activity(context)?.window?.decorView?.rootWindowInsets ?: return 0
        return if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
    }

    private fun dp(v: Double): Int = Ui.dp(context, v.toFloat())

    /**
     * The sheet's body: a drag that starts on the handle or the header and
     * goes down moves the sheet with the finger; let go far enough (or flung),
     * the sheet closes, else it settles back. The thread under it scrolls as
     * ever, and a tap is still a tap.
     */
    private inner class SheetBody(context: Context) : LinearLayout(context) {
        private var downY = 0f
        private var downX = 0f
        /** Where the finger went down, in this view: a drag pulls the sheet only from the handle or the header. */
        private var downLocalY = 0f
        private var pulling = false
        private var velocity: VelocityTracker? = null
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private fun inDragZone(y: Float) = y <= dp(ChatWindowLayout.HANDLE_STRIP + VoiceBar.CHAT_HEADER)

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!sheet) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; downX = ev.rawX; downLocalY = ev.y; pulling = false; track(ev, reset = true) }
                MotionEvent.ACTION_MOVE -> {
                    val dy = ev.rawY - downY
                    if (!pulling && inDragZone(downLocalY) && dy > slop && Math.abs(dy) > Math.abs(ev.rawX - downX)) {
                        pulling = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
            }
            return pulling
        }

        @android.annotation.SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (!sheet) return super.onTouchEvent(ev)
            track(ev)
            val dy = (ev.rawY - downY).coerceAtLeast(0f)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; downX = ev.rawX; downLocalY = ev.y; track(ev, reset = true); return inDragZone(ev.y) }
                MotionEvent.ACTION_MOVE -> { if (pulling || dy > slop) { pulling = true; translationY = dy } }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val density = resources.displayMetrics.density
                    velocity?.computeCurrentVelocity(1000)
                    val vy = (velocity?.yVelocity ?: 0f) / density
                    val closes = ev.actionMasked == MotionEvent.ACTION_UP && pulling && ChatWindowLayout.dragCloses(dy / density.toDouble(), vy.toDouble())
                    pulling = false
                    velocity?.recycle(); velocity = null
                    if (closes) {
                        closed()
                        dismiss()
                    } else {
                        val ms = Themed.motionMs(context, tokens)
                        if (ms > 0) animate().translationY(0f).setDuration(ms).start() else translationY = 0f
                    }
                }
            }
            return true
        }

        private fun track(ev: MotionEvent, reset: Boolean = false) {
            if (reset || velocity == null) { velocity?.recycle(); velocity = VelocityTracker.obtain() }
            // Raw coordinates: the body moves under the finger as it is dragged.
            val copy = MotionEvent.obtain(ev).apply { setLocation(ev.rawX, ev.rawY) }
            velocity?.addMovement(copy)
            copy.recycle()
        }
    }

    private companion object {
        /** Room around a panel, inside its window, for its shadow (dp). */
        const val PANEL_SHADOW_ROOM = 12.0
    }
}
