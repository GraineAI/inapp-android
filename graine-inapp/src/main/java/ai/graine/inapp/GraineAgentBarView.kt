package ai.graine.inapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * THE DEFAULT AGENT BAR — a working UI, not a requirement.
 *
 * A bar rather than a sheet: the agent can see the screen and tell the
 * customer what to tap, and a sheet would cover the very thing it is talking
 * about. Place it at the bottom of your layout, above your tab bar.
 *
 * It says the network's name out loud — "weak connection", "no connection",
 * "reconnecting…" — because a customer whose agent goes quiet on a train
 * reads "listening" and concludes the agent is broken. While the call is
 * coming up the status line IS the dashboard's loader, in the brand's words.
 *
 * One unit, three layers, bottom up: the bar, the live caption, and the
 * components the agent draws ([widgetView]) — so a card the agent shows sits
 * on the bar and moves with it.
 *
 *  - The DASHBOARD decides how it looks (Embed & Widgets → Appearance: accent,
 *    heading, logo, light/dark/auto, corner radius); [accent], [name],
 *    [avatarUrl], [scheme] and [radius] override one thing each.
 *  - The CUSTOMER decides where it sits: drag it up or down (a tap still
 *    taps). Restore a saved spot with [placementOffset]; hear about a new one
 *    in [onPlacementChange] and persist it — the SDK has no storage of its own.
 *  - The KEYBOARD never covers it: it rises by the keyboard's height minus
 *    whatever the window already shrank ([KeyboardLift.liftFor]), so it is
 *    right under adjustResize, adjustPan and edge-to-edge alike.
 *
 * Built from plain platform widgets: no AndroidX, no Compose, no Material, so
 * it drops into any app without a dependency conflict. Every piece is public
 * state on [GraineVoice]; an app with its own design system should draw its
 * own from those and ignore this class.
 */
class GraineAgentBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), GraineListener {

    // ── overrides: the dashboard is the source, these are the exceptions ──

    /** #RRGGBB for the avatar, the call button and components. Default: the dashboard's accent. */
    var accent: String? = null
        set(v) { field = v; applyLook() }
    /** The name beside the status. Default: the dashboard's heading, else "Assistant". */
    var name: String? = null
        set(v) { field = v; applyLook() }
    /** A face. Default: the dashboard's logo, else a glyph on the accent. */
    var avatarUrl: String? = null
        set(v) { field = v; applyLook() }
    /** "light", "dark" or "auto" (the device). Default: the dashboard's theme. */
    var scheme: String? = null
        set(v) { field = v; applyLook(); widgetView.scheme = v }
    /** Corner rounding in dp for the caption (the bar stays a pill). Default: the dashboard's radius. */
    var radius: Int? = null
        set(v) { field = v; applyLook() }

    // ── where it sits ──────────────────────────────────────────────────────

    /** Let the customer drag it up and down. The offset is 0 while off. */
    var draggable: Boolean = true
        set(v) { field = v; if (!v) { offsetDp = 0; applyTranslation() } }

    /**
     * Where the customer left it: dp above its anchor. Set it to restore a
     * position you persisted; it is clamped to the screen once the bar has
     * been laid out.
     */
    var placementOffset: Int
        get() = offsetDp
        set(v) { pendingRestore = v; if (isLaidOut && rootView.height > 0) restorePlacement() }

    /** The customer let go somewhere new. Persist the value and hand it back to [placementOffset]. */
    var onPlacementChange: ((bottomOffset: Int) -> Unit)? = null

    /** Rise above the soft keyboard. On by default; turn off only if your layout already does. */
    var keyboardAware: Boolean = true
        set(v) { field = v; updateKeyboard() }

    /** Put it back on its anchor, and say so. */
    fun resetPlacement() {
        offsetDp = 0
        applyTranslation()
        onPlacementChange?.invoke(0)
    }

    /** The components the agent draws, mounted above the caption so they move with the bar. */
    val widgetView: GraineWidgetView = GraineWidgetView(context)

    private val graine = GraineInApp.get()
    private val voice get() = graine.voice

    private var agentSpeaking = false
    private var lastCaption: String? = null
    private var captionLive = false
    private var micDenied = false
    private var look = Look.resolve(this)
    private var avatarUrlShown = ""

    private val caption = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        Ui.weight(this, 600)
        setPadding(dp(14), dp(11), dp(14), dp(11))
        visibility = GONE
        Ui.holdsTouches(this)
    }
    private val avatar = FrameLayout(context)
    private val nameView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        Ui.weight(this, 800)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val status = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        contentDescription = "Agent status"
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    /** The status line: words, or the loader while the call is coming up. */
    private val statusSlot = FrameLayout(context)
    private var shownLoader: String? = null
    private val mute = roundButton("🎙", "Mute") { voice.setMuted(!voice.muted); refresh() }
    private val call = roundButton("📞", "Start a call") { if (voice.connected || voice.connecting) voice.end() else voice.start() }
    private val bar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        elevation = dp(8).toFloat()
        Ui.holdsTouches(this)
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        bar.addView(avatar, LayoutParams(dp(38), dp(38)))
        val text = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(11), 0, dp(11), 0)
            addView(nameView)
            addView(statusSlot, Ui.matchWidth().apply { topMargin = dp(1) })
        }
        bar.addView(text, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(mute, LayoutParams(dp(38), dp(38)).apply { marginEnd = dp(8) })
        bar.addView(call, LayoutParams(dp(38), dp(38)))
        addView(widgetView, Ui.matchWidth())
        addView(caption, Ui.matchWidth().apply { bottomMargin = dp(8) })
        addView(bar, Ui.matchWidth())
        applyLook()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        graine.addListener(this)
        viewTreeObserver.addOnGlobalLayoutListener(layoutWatcher)
        setOnApplyWindowInsetsListener { v, insets -> post { updateKeyboard() }; v.onApplyWindowInsets(insets) }
        if (Build.VERSION.SDK_INT >= 30) setWindowInsetsAnimationCallback(ImeAnimation())
        applyLook()
    }

    override fun onDetachedFromWindow() {
        graine.removeListener(this)
        viewTreeObserver.removeOnGlobalLayoutListener(layoutWatcher)
        setOnApplyWindowInsetsListener(null)
        if (Build.VERSION.SDK_INT >= 30) setWindowInsetsAnimationCallback(null)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        applyLook()
    }

    // ── the dashboard is the source; the view follows it ─────────────────────

    /** Everything visual, resolved once from the dashboard and the overrides. */
    private class Look(
        val accent: Int, val accentHex: String, val name: String, val avatarUrl: String?, val dark: Boolean,
        val radiusDp: Int, val loader: LoaderLook, val loaderColor: Int, val logoUrl: String?,
    ) {
        // ui.tsx's two palettes, with the dashboard choosing between them.
        val surface = if (dark) 0xFF17161A.toInt() else Color.WHITE
        val border = if (dark) 0x17FFFFFF else 0x1A000000
        val text = if (dark) 0xFFF2F3F5.toInt() else 0xFF16181C.toInt()
        val sub = if (dark) 0xFF9AA0A6.toInt() else 0xFF61666D.toInt()
        val chip = if (dark) 0x14FFFFFF else 0x0D000000
        val danger = if (dark) 0xFFE5484D.toInt() else 0xFFD32F2F.toInt()

        companion object {
            fun resolve(v: GraineAgentBarView): Look {
                val g = GraineInApp.get()
                val appearance: JSONObject? = g.session?.appearance
                val dash = Appearance.resolve(appearance, Appearance.DEFAULT_ACCENT, "Assistant", origin = g.config.baseUrl)
                val accentHex = v.accent?.takeIf { Appearance.argb(it) != null } ?: dash.accent
                val theme = v.scheme ?: Js.strOrEmpty(appearance?.opt("theme")).ifEmpty { "auto" }
                val loader = Loaders.resolve(appearance?.opt("loader"))
                val accent = Appearance.argb(accentHex) ?: Color.parseColor(Appearance.DEFAULT_ACCENT)
                return Look(
                    accent = accent,
                    accentHex = accentHex,
                    name = v.name ?: dash.heading,
                    avatarUrl = v.avatarUrl ?: dash.logoUrl,
                    dark = Appearance.isDark(theme, Ui.isDeviceDark(v.context)),
                    radiusDp = v.radius ?: Math.round(dash.radius).toInt(),
                    loader = loader,
                    loaderColor = Appearance.argb(loader.color) ?: Appearance.argb(dash.accent) ?: accent,
                    logoUrl = dash.logoUrl,
                )
            }
        }
    }

    private fun applyLook() {
        look = Look.resolve(this)
        val l = look
        bar.background = Ui.rounded(l.surface, dp(minOf(26, l.radiusDp + 8)).toFloat(), dp(1), l.border)
        caption.background = Ui.rounded(l.surface, dp(l.radiusDp).toFloat(), dp(1), l.border)
        caption.setTextColor(l.text)
        nameView.text = l.name
        nameView.setTextColor(l.text)
        status.setTextColor(l.sub)
        drawAvatar(l)
        shownLoader = null
        refresh()
    }

    private fun drawAvatar(l: Look) {
        val url = l.avatarUrl ?: ""
        if (avatar.childCount > 0 && url == avatarUrlShown && url.isNotEmpty()) return
        avatarUrlShown = url
        avatar.removeAllViews()
        if (url.isNotEmpty()) {
            avatar.addView(roundedImage(context, url, 0f, l.chip, oval = true), FrameLayout.LayoutParams(dp(38), dp(38)))
        } else {
            avatar.addView(TextView(context).apply {
                text = "✦"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = Ui.rounded(l.accent, dp(19).toFloat())
            }, FrameLayout.LayoutParams(dp(38), dp(38)))
        }
    }

    override fun onSessionReady(session: SessionConfig) = applyLook()
    override fun onCallState(connected: Boolean, connecting: Boolean) {
        if (connected || connecting) micDenied = false
        refresh()
    }
    override fun onQuality(quality: ConnectionQuality) = refresh()
    override fun onNetwork(online: Boolean) = refresh()
    override fun onReconnecting(active: Boolean) = refresh()
    override fun onConnectivity(state: Connectivity, attempt: Int, reason: String?) = refresh()
    override fun onEnded() { lastCaption = null; agentSpeaking = false; refresh() }
    override fun onMicDenied(reason: String) { micDenied = true; refresh() }

    override fun onCaption(role: Role, text: String, live: Boolean) {
        agentSpeaking = role == Role.AGENT && live
        if (text.isNotBlank()) { lastCaption = text; captionLive = live }
        refresh()
    }

    private fun refresh() {
        val l = look
        val waiting: String? = when {
            voice.connectivity == Connectivity.FAILED || voice.online == false -> null
            voice.reconnecting || voice.connectivity == Connectivity.RETRYING -> "reconnecting"
            voice.connecting || !voice.ready -> "connecting"
            else -> null
        }
        if (waiting != null) {
            // While it is coming up the status line IS the loader the brand chose, in its words.
            if (shownLoader != waiting) {
                shownLoader = waiting
                statusSlot.removeAllViews()
                statusSlot.addView(GraineLoaderView.withLabel(context, l.loader, l.loaderColor, l.border, l.sub,
                    l.loader.text.forMoment(waiting), sizeDp = 15, logoUrl = l.logoUrl).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START })
            }
        } else {
            if (shownLoader != null || statusSlot.childCount == 0) {
                shownLoader = null
                statusSlot.removeAllViews()
                statusSlot.addView(status)
            }
            status.text = when {
                voice.connectivity == Connectivity.FAILED -> "couldn't load · tap to retry"
                voice.online == false -> "no connection"
                micDenied && !voice.connected -> "needs microphone"
                voice.muted -> "muted"
                voice.connected && voice.quality == ConnectionQuality.POOR -> "weak connection"
                voice.connected && agentSpeaking -> "speaking"
                voice.connected -> "listening"
                else -> "tap to talk"
            }
        }
        mute.visibility = if (voice.connected) VISIBLE else GONE
        (mute.getChildAt(0) as? TextView)?.text = if (voice.muted) "🔇" else "🎙"
        mute.contentDescription = if (voice.muted) "Unmute" else "Mute"
        mute.background = Ui.rounded(if (voice.muted) l.danger else l.chip, dp(19).toFloat())
        val live = voice.connected || voice.connecting
        (call.getChildAt(0) as? TextView)?.text = if (live) "✕" else "📞"
        call.contentDescription = if (live) "End call" else "Start a call"
        call.background = Ui.rounded(if (live) l.danger else l.accent, dp(19).toFloat())
        if (voice.connectivity == Connectivity.FAILED) call.setOnClickListener { voice.retry() }
        else call.setOnClickListener { if (voice.connected || voice.connecting) voice.end() else voice.start() }
        caption.text = lastCaption
        caption.alpha = if (captionLive) 0.8f else 1f
        caption.visibility = if (voice.connected && !lastCaption.isNullOrBlank()) VISIBLE else GONE
    }

    private fun roundButton(glyph: String, label: String, onClick: () -> Unit) = LinearLayout(context).apply {
        gravity = Gravity.CENTER
        contentDescription = label
        isClickable = true
        isFocusable = true
        addView(TextView(context).apply { text = glyph; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); gravity = Gravity.CENTER })
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = Ui.dp(context, v)

    // ── dragging: the customer decides where it sits ───────────────────────

    private var offsetDp = 0
    private var pendingRestore: Int? = null
    private var imeLiftPx = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragStart = 0

    private val density get() = resources.displayMetrics.density

    /** Space to keep clear at the top: RN's 48, or the status bar if that is taller. */
    @Suppress("DEPRECATION")
    private fun topMarginDp(): Double {
        val insets = rootWindowInsets ?: return 48.0
        val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.statusBars()).top else insets.systemWindowInsetTop
        return maxOf(48.0, top / density + 8.0)
    }

    /** Where it may sit, in dp, measured from where the host laid it out (translation excluded). */
    private fun bounds(): Placement.Bounds {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val layoutBottom = loc[1] - translationY + height
        val windowHeight = rootView.height.toFloat()
        return Placement.Bounds(
            windowHeight = (windowHeight / density).toDouble(),
            baseBottom = ((windowHeight - layoutBottom) / density).toDouble(),
            barHeight = (height / density).toDouble(),
            topMargin = topMarginDp(),
        )
    }

    /** Drag offset plus keyboard lift, never past the top margin however they add up. */
    private fun applyTranslation() {
        if (!isLaidOut) return
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val layoutTop = loc[1] - translationY
        val maxUp = maxOf(0f, layoutTop - (topMarginDp() * density).toFloat())
        val want = offsetDp * density + imeLiftPx
        translationY = -minOf(want, maxUp)
    }

    private fun restorePlacement() {
        val want = pendingRestore ?: return
        if (!isLaidOut || rootView.height <= 0) return
        pendingRestore = null
        offsetDp = if (draggable) Placement.clampOffset(want.toDouble(), bounds()) else 0
        applyTranslation()
    }

    private fun isDrag(ev: MotionEvent): Boolean {
        val dx = ((ev.rawX - downX) / density).toDouble()
        val dy = ((ev.rawY - downY) / density).toDouble()
        // Vertical only: a sideways swipe belongs to whatever is under the finger (a carousel in a card).
        return !Placement.isTap(dx, dy) && Math.abs(dy) > Math.abs(dx)
    }

    private fun beginDrag() {
        dragging = true
        dragStart = offsetDp
        parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun dragTo(ev: MotionEvent): Int =
        Placement.offsetDuringDrag(dragStart.toDouble(), ((ev.rawY - downY) / density).toDouble(), bounds())

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!draggable) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false }
            // Claimed only once it is clearly a drag, so a tap on the bar and its buttons still taps.
            MotionEvent.ACTION_MOVE -> if (!dragging && isDrag(ev)) { beginDrag(); return true }
        }
        return dragging
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        // Empty space passes through to the app underneath, as a box-none view does.
        if (!draggable || ev.actionMasked == MotionEvent.ACTION_DOWN) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && isDrag(ev)) beginDrag()
                if (dragging) { offsetDp = dragTo(ev); applyTranslation() }
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                val next = dragTo(ev)
                offsetDp = next
                dragging = false
                applyTranslation()
                if (next != dragStart) onPlacementChange?.invoke(next)
            }
            MotionEvent.ACTION_CANCEL -> if (dragging) { offsetDp = dragStart; dragging = false; applyTranslation() }
        }
        return true
    }

    // ── the keyboard: never behind it ──────────────────────────────────────

    private var tallestContent = 0
    private var measuredWidth0 = 0
    private var baselineCovered = Int.MAX_VALUE

    private val layoutWatcher = ViewTreeObserver.OnGlobalLayoutListener {
        updateKeyboard()
        if (pendingRestore != null) restorePlacement()
        else if (draggable && offsetDp > 0 && !dragging) {
            // The screen or the bar changed shape (a card appeared, a rotation): stay on screen.
            val c = Placement.clampOffset(offsetDp.toDouble(), bounds())
            if (c != offsetDp) offsetDp = c
        }
        applyTranslation()
    }

    /** The keyboard's height in px as the platform reports it; 0 when hidden. */
    @Suppress("DEPRECATION")
    private fun keyboardHeightPx(from: WindowInsets? = null): Int {
        val root = rootView ?: return 0
        if (Build.VERSION.SDK_INT >= 30) {
            val wi = from ?: root.rootWindowInsets ?: return 0
            val ime = wi.getInsets(WindowInsets.Type.ime()).bottom
            val bars = wi.getInsets(WindowInsets.Type.systemBars()).bottom
            return maxOf(0, ime - bars)
        }
        // Before Android 11: what the visible frame lost, over what it loses with no keyboard (the nav bar).
        val r = Rect()
        root.getWindowVisibleDisplayFrame(r)
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        val covered = loc[1] + root.height - r.bottom
        baselineCovered = minOf(baselineCovered, covered)
        val kb = covered - baselineCovered
        return if (kb > root.height * 0.15f) kb else 0
    }

    /** How much shorter the content window is than with no keyboard — what adjustResize already did. */
    private fun windowShrinkPx(): Int {
        val content = rootView?.findViewById<View>(android.R.id.content) ?: return 0
        if (content.width != measuredWidth0) {
            // A rotation, not a keyboard: start measuring again.
            measuredWidth0 = content.width
            tallestContent = 0
            baselineCovered = Int.MAX_VALUE
        }
        tallestContent = maxOf(tallestContent, content.height)
        return tallestContent - content.height
    }

    private fun updateKeyboard(from: WindowInsets? = null) {
        val lift = if (!keyboardAware || !isAttachedToWindow) 0
        else KeyboardLift.liftFor(keyboardHeightPx(from).toDouble(), windowShrinkPx().toDouble())
        if (lift != imeLiftPx) {
            imeLiftPx = lift
            applyTranslation()
        }
    }

    /** Android 11+: follow the keyboard frame by frame, instead of jumping after it lands. */
    private inner class ImeAnimation : WindowInsetsAnimation.Callback(WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
        override fun onProgress(insets: WindowInsets, running: MutableList<WindowInsetsAnimation>): WindowInsets {
            if (running.any { it.typeMask and WindowInsets.Type.ime() != 0 }) updateKeyboard(insets)
            return insets
        }
        override fun onEnd(animation: WindowInsetsAnimation) { updateKeyboard() }
    }

}
