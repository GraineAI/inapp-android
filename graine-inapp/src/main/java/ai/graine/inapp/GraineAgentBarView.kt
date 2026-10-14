package ai.graine.inapp

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * THE VOICE BAR — what the customer sees during a call, drawn by the SDK
 * exactly as the dashboard's Garage draws it in its phone preview.
 *
 * A bar rather than a sheet: the agent can see the screen and tell the
 * customer what to tap, and a sheet would cover the very thing it is talking
 * about. Place it at the bottom of your layout, above your tab bar.
 *
 * The DASHBOARD decides how it looks — the design the brand chose, element by
 * element as contract/DESIGN.md → Voice bar specifies, and nothing the app
 * passes: a 60 dp bar in `surface` (translucent under glass) with its outline,
 * `radius.xl` corners and the floating shadow or the hard offset; the accent
 * avatar with the design's launcher glyph (or the brand's logo, whole, on its
 * plate — contract/DESIGN.md "The brand's logo" — or the first letter);
 * the heading and a status line whose four-bar meter moves at the design's
 * pace; the microphone in `accent` (`danger` when muted); the end button in
 * `danger` on a call and a quiet close otherwise. The SDK's own icons are
 * drawn in the design's icon style, the words in the design's typeface, which
 * the SDK fetches itself. Above the bar, the line being said sits on a card
 * of its own, and it rises in over the design's duration. Under a design's
 * window chrome that card, the chat and the components open as desktop
 * windows: traffic lights and a monospaced title (contract/DESIGN.md →
 * Window chrome).
 *
 * It says the network's name out loud — "Weak connection", "No connection",
 * "Reconnecting…" — because a customer whose agent goes quiet on a train
 * reads "Listening" and concludes the agent is broken. While the call is
 * coming up, and while the agent works out its answer, the status line IS the
 * dashboard's loader, in the brand's words — and a connect says where it is: a
 * retry or a switch to the other transport is the loader's "reconnecting"
 * moment, a wait past four seconds "Still connecting…" (only while the brand
 * kept the default words), and a call nothing could carry "Couldn’t connect ·
 * tap to retry", with the microphone starting a new one.
 *
 * One unit, bottom up: the bar, the live caption, and the components the
 * agent draws ([widgetView]) — so a card the agent shows sits on the bar and
 * moves with it.
 *
 *  - [accent], [name], [avatarUrl], [scheme] and [radius] override one thing
 *    each; the design is the source for everything else. From a server that
 *    predates designs, the bar is Graine Classic in the dashboard's accent,
 *    corners and light/dark — the look every surface had before designs.
 *  - The CUSTOMER decides where it sits: drag it up or down (a tap still
 *    taps). Restore a saved spot with [placementOffset]; hear about a new one
 *    in [onPlacementChange] and persist it — the SDK has no storage of its own.
 *  - The KEYBOARD never covers it: it rises by the keyboard's height minus
 *    whatever the window already shrank ([KeyboardLift.liftFor]), so it is
 *    right under adjustResize, adjustPan and edge-to-edge alike.
 *  - [onClose] hears the end button when there is no call to end (and after a
 *    hang-up): hide the bar there, or go back to your launcher.
 *
 * Built from plain platform widgets: no AndroidX, no Compose, no Material, so
 * it drops into any app without a dependency conflict. Every piece is public
 * state on [GraineVoice]; an app with its own design system can draw its own
 * from those and ignore this class.
 */
class GraineAgentBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), GraineListener {

    // ── overrides: the dashboard is the source, these are the exceptions ──

    /** #RRGGBB for the avatar, the microphone and the meter. Default: the design's accent. */
    var accent: String? = null
        set(v) { field = v; applyLook() }
    /** The name beside the status. Default: the dashboard's heading, else "Assistant". */
    var name: String? = null
        set(v) { field = v; applyLook() }
    /**
     * A picture for the avatar, filling its circle — the app's own, so it is
     * the app's to crop. Default: the design's glyph, else the dashboard's
     * logo (drawn whole on its plate, never cropped), else the first letter.
     */
    var avatarUrl: String? = null
        set(v) { field = v; applyLook() }
    /** "light", "dark" or "auto" (the device). Default: the design's scheme. */
    var scheme: String? = null
        set(v) { field = v; applyLook(); widgetView.scheme = v }
    /** Corner rounding in dp for the caption card (the bar takes it plus 8, at most 26). Default: the design's radius.lg and radius.xl. */
    var radius: Int? = null
        set(v) { field = v; applyLook() }

    /**
     * The end button was pressed with no call to end, or a call was just hung
     * up (once its goodbye has left the speaker): close the bar — hide it, or
     * show your launcher again. Unset, a close does nothing: the bar stays.
     */
    var onClose: (() -> Unit)? = null

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

    /**
     * The agent this bar talks to — the default one unless set:
     * `bar.graine = GraineInApp.get("sales")` for another of the app's agents
     * (GraineInApp.init's name). Its voice (attach it once, as the default
     * agent's: `GraineInApp.get("sales").voice.attach(activity)`), its design
     * and, unless [chat] was set to a conversation of the app's own, its chat.
     * Set it on a screen's way in; a call already live stays with its own agent.
     */
    var graine: GraineInApp = GraineInApp.primary()
        set(v) {
            if (field === v) return
            val old = field
            val attached = isAttachedToWindow
            if (attached) {
                old.voice.barShown(this, false)
                old.removeListener(this)
                unwatch?.invoke()
                unwatch = null
            }
            field = v
            widgetView.graine = v
            // The chat window is drawn for one agent: the next one is made for this one.
            chatWindow?.let { chatSlot.removeView(it) }
            chatWindow = null
            dismissBigChat()
            if (attached) {
                old.chatScreenWatchers.remove(screenClosed)
                v.chatScreenWatchers.add(screenClosed)
            }
            // The chat follows the agent, unless the app draws a conversation of its own.
            if (chat === old.chat) chat = v.chat
            if (attached) {
                v.addListener(this)
                v.voice.barShown(this, isShown)
                unwatch = v.onDesignChange { applyLook() }
            }
            chosenSurface = null
            applyLook()
        }
    private val voice get() = graine.voice

    /** Redraw when the chat changes — a line arrived or grew, it came online, it dropped. */
    private val chatWatcher = Runnable { if (isAttachedToWindow) refresh() }

    /**
     * The text conversation the bar opens beside the call, with the switch
     * between them, as the dashboard decides (Appearance → mode and start:
     * voice, chat, or both — contract/DESIGN.md → Chat window). The SDK's own
     * chat ([GraineChat], `GraineInApp.get().chat`) by default; set your own
     * [ChatSource] to draw a conversation the app runs itself, or null for the
     * call alone whatever the dashboard offers.
     */
    var chat: ChatSource? = graine.chat
        set(v) {
            if (field === v) return
            field?.let { old -> old.removeChangeListener(chatWatcher); if (chatOnScreen) old.shown(false) }
            chatOnScreen = false
            field = v
            if (isAttachedToWindow) v?.addChangeListener(chatWatcher)
            chosenSurface = null
            refresh()
        }

    /** The chat is the surface on screen now — what [ChatSource.shown] was last told. */
    private var chatOnScreen = false

    private var lastCaption: String? = null
    private var captionRole = Role.AGENT
    private var captionLive = false
    private var micDenied = false
    private var look = Look.resolve(this)
    private var unwatch: (() -> Unit)? = null
    private var captionShown = false
    private var chatShown = false
    /** The loader moment and words the status line shows; null while it shows words alone. */
    private var shownLoader: String? = null
    /** The customer's last switch between the call and the chat; null follows the dashboard. */
    private var chosenSurface: String? = null
    private var chatWindow: ChatWindowView? = null
    /** A bigger chat window — a phone's sheet, a tablet's panel — in its own window over the app. */
    private var bigChat: ChatWindowDialog? = null
    /** The full-screen chat this bar opened ([GraineChatActivity]) is up. */
    private var screenOpen = false
    /** The app's opens of the chat ([GraineChat.open]) this bar has seen: a new one moves it to the chat. */
    private var seenOpens = 0
    /** That full-screen chat was closed: do what the chevron does. */
    private val screenClosed: () -> Unit = { post { if (screenOpen) { screenOpen = false; closeChat() } } }
    /** Icons drawn for this look, by name and colour: a caption arriving redraws the buttons, not the drawings. */
    private val icons = HashMap<String, Drawable>()
    /** The bar's corners and whether its face is opaque, for the outline this view casts its shadow in. */
    private var barCornerPx = 0f
    private var barOpaque = true

    private val captionYou = TextView(context).apply {
        text = "YOU"
        maxLines = 1
        visibility = GONE
    }
    private val captionText = TextView(context).apply {
        maxLines = VoiceBar.CAPTION_LINES
        ellipsize = TextUtils.TruncateAt.END
    }
    /** The caption card's title bar, when the design opens it as a window. */
    private val captionBar = WindowTitleBar(context).apply {
        visibility = GONE
        // "captions" names the card; what TalkBack reads is the line on it.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val captionBody = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(captionYou, Ui.matchWidth().apply { bottomMargin = Ui.dp(context, 2) })
        addView(captionText, Ui.matchWidth())
    }
    private val caption = LinearLayout(context).apply {
        orientation = VERTICAL
        visibility = GONE
        Ui.holdsTouches(this)
        addView(captionBar, Ui.matchWidth())
        addView(captionBody, Ui.matchWidth())
    }
    private val chatSlot = FrameLayout(context).apply { visibility = GONE }
    private val avatar = FrameLayout(context)
    private val nameView = TextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val statusText = TextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val meter = MeterView(context)
    private val statusRow = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(meter, Ui.wrap())
        addView(statusText, Ui.wrap())
    }
    /** The status line: the meter and words, or the loader while waiting. */
    private val statusSlot = FrameLayout(context)
    private val words = LinearLayout(context).apply {
        orientation = VERTICAL
        addView(nameView, Ui.matchWidth())
        addView(statusSlot, Ui.matchWidth().apply { topMargin = Ui.dp(context, 1) })
    }
    private val switchButton = RoundButton(context).apply { visibility = GONE; setOnClickListener { switchSurface() } }
    private val micButton = RoundButton(context).apply { setOnClickListener { pressMic() } }
    private val endButton = RoundButton(context).apply { setOnClickListener { pressEnd() } }
    private val bar = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        Ui.holdsTouches(this)
    }

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        bar.addView(avatar, LayoutParams(px(VoiceBar.BUTTON), px(VoiceBar.BUTTON)))
        bar.addView(words, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(switchButton, Ui.wrap())
        bar.addView(micButton, Ui.wrap())
        bar.addView(endButton, Ui.wrap())
        statusSlot.addView(statusRow)
        addView(widgetView, Ui.matchWidth())
        addView(chatSlot, Ui.matchWidth().apply { bottomMargin = px(VoiceBar.STACK_GAP) })
        addView(caption, Ui.matchWidth().apply { bottomMargin = px(VoiceBar.STACK_GAP) })
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, px(VoiceBar.HEIGHT)))
        // The floating shadow is cast by THIS view, in the bar's outline. Cast by the bar, it was drawn
        // inside this view and cut off at its bottom edge — the bar's own — as a grey box behind the corners.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                if (bar.width <= 0 || bar.height <= 0) { outline.setEmpty(); return }
                outline.setRoundRect(bar.left, bar.top, bar.right, bar.bottom, barCornerPx)
                // A translucent (glass) face casts no shadow, as the platform rules for any translucent fill.
                outline.alpha = if (barOpaque) 1f else 0f
            }
        }
        applyLook()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // The bar moves down this view when a caption or a component appears above it: the shadow follows.
        invalidateOutline()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        graine.addListener(this)
        graine.chatScreenWatchers.add(screenClosed)
        seenOpens = (chat as? GraineChat)?.opens ?: 0
        chat?.addChangeListener(chatWatcher)
        voice.barShown(this, isShown)
        unwatch = graine.onDesignChange { applyLook() }
        viewTreeObserver.addOnGlobalLayoutListener(layoutWatcher)
        setOnApplyWindowInsetsListener { v, insets -> post { updateKeyboard() }; v.onApplyWindowInsets(insets) }
        if (Build.VERSION.SDK_INT >= 30) setWindowInsetsAnimationCallback(ImeAnimation())
        applyLook()
    }

    override fun onDetachedFromWindow() {
        voice.barShown(this, false)
        graine.removeListener(this)
        graine.chatScreenWatchers.remove(screenClosed)
        chat?.removeChangeListener(chatWatcher)
        dismissBigChat()
        showChat(false)
        unwatch?.invoke()
        unwatch = null
        viewTreeObserver.removeOnGlobalLayoutListener(layoutWatcher)
        setOnApplyWindowInsetsListener(null)
        if (Build.VERSION.SDK_INT >= 30) setWindowInsetsAnimationCallback(null)
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        applyLook()
        // Turned: a sheet or a panel takes its size again.
        bigChat?.takeIf { it.isShowing }?.place()
    }

    /**
     * Shown or hidden — this view, or anything holding it (onClose hiding it
     * is the usual way). While it is on screen the customer's UI is open: a
     * page still loading behind it is a connect they wait on, and the last
     * bar leaving forgets a failed connect, as the React Native launcher does
     * when it is deactivated. Only ever dispatched to an attached view.
     */
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        voice.barShown(this, isShown)
        if (isAttachedToWindow) refresh()
    }

    /** Tell the chat whether this bar shows it: on screen it connects; off every screen it closes after a moment. */
    private fun showChat(on: Boolean) {
        if (on == chatOnScreen) return
        chatOnScreen = on
        chat?.shown(on)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        // Back from Settings with the microphone granted: "Needs microphone" is no longer true, and the button may act again.
        if (hasWindowFocus && micDenied && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            micDenied = false
            refresh()
        }
    }

    // ── the dashboard is the source; the view follows it ─────────────────────

    /** Everything visual, resolved once from the design and the overrides. */
    private class Look(
        /** The design's tokens for now — Graine Classic when the server sent none. */
        val t: ThemeTokens,
        val accent: Int,
        /** What reads on [accent]: the design's own, unless a host override replaced the accent. */
        val onAccent: Int,
        /** The accent at chip strength: the switch and close buttons. */
        val soft: Int,
        val name: String,
        /** A host's own avatar picture; it wins over the glyph. */
        val hostImage: String?,
        val logoUrl: String?,
        /** What the logo sits on: the accent, or white with a hairline. */
        val logoPlate: LogoPlate,
        val loader: LoaderLook,
        val loaderColor: Int,
        val barRadiusDp: Double,
        val captionRadiusDp: Double,
        val appearance: JSONObject?,
    ) {
        val surface = Themed.surface(t)
        val border = Themed.color(t.colors.border)
        val text = Themed.color(t.colors.text)
        val muted = Themed.color(t.colors.textMuted)
        val danger = Themed.color(t.colors.danger)

        companion object {
            fun resolve(v: GraineAgentBarView): Look {
                val g = v.graine
                val appearance: JSONObject? = g.session?.appearance
                val dash = Appearance.resolve(appearance, Appearance.DEFAULT_ACCENT, "Assistant", origin = g.config.baseUrl)
                val designed = g.themeTokens(v.context, v.scheme)
                // No design — a server that predates designs, or the moment before the session arrives: Graine
                // Classic in the dashboard's light or dark, accent and corners, the look before designs.
                val t = designed ?: if (Appearance.isDark(v.scheme ?: Js.strOrEmpty(appearance?.opt("theme")).ifEmpty { "auto" }, Ui.isDeviceDark(v.context)))
                    Design.CLASSIC_DARK else Design.CLASSIC_LIGHT
                val accentHex = v.accent?.takeIf { Appearance.argb(it) != null } ?: designed?.colors?.accent ?: dash.accent
                val own = Design.normaliseHex(accentHex) == Design.normaliseHex(t.colors.accent)
                val loader = Loaders.resolve(appearance?.opt("loader"))
                // The dashboard's corner rounding scales Classic's radii, as the server scales them for such an embed.
                val corners = if (designed == null) dash.radius / 18.0 else 1.0
                val accent = Appearance.argb(accentHex) ?: Color.parseColor(Appearance.DEFAULT_ACCENT)
                return Look(
                    t = t,
                    accent = accent,
                    onAccent = Themed.color(if (own) t.colors.onAccent else Themed.readableOnHex(accentHex), Color.WHITE),
                    soft = Themed.color(VoiceBar.softFor(accentHex, t)),
                    name = v.name ?: dash.heading,
                    hostImage = v.avatarUrl?.takeIf { it.isNotBlank() },
                    logoUrl = dash.logoUrl,
                    logoPlate = dash.logoPlate,
                    loader = loader,
                    // The loader's own colour, else the design's accent — as the React Native loader draws it.
                    loaderColor = Appearance.argb(loader.color) ?: Appearance.argb(designed?.colors?.accent ?: dash.accent) ?: accent,
                    barRadiusDp = v.radius?.let { minOf(26, it + 8).toDouble() } ?: (t.shape.radius.xl * corners),
                    captionRadiusDp = v.radius?.toDouble() ?: (t.shape.radius.lg * corners),
                    appearance = appearance,
                )
            }
        }
    }

    private fun applyLook() {
        look = Look.resolve(this)
        icons.clear()
        val l = look
        val t = l.t
        val line = Themed.px(context, t.shape.borderWidth)

        // The bar: 60 high, 9 either side, items 10 apart; surface, outline, radius.xl, and its depth.
        val pad = px(VoiceBar.PADDING)
        val corner = Themed.px(context, l.barRadiusDp).toFloat()
        Themed.surface(bar, t, l.surface, corner, line, l.border, t.elevation.floating, pad, 0, pad, 0)
        // Its soft shadow is cast by this view instead (see the outline provider): same depth, same colour.
        barCornerPx = corner
        barOpaque = Color.alpha(l.surface) == 255
        elevation = bar.elevation
        if (Build.VERSION.SDK_INT >= 28) {
            outlineSpotShadowColor = bar.outlineSpotShadowColor
            outlineAmbientShadowColor = bar.outlineAmbientShadowColor
        }
        bar.elevation = 0f
        invalidateOutline()
        // A hard offset is drawn inside the view, below the face: the view grows by it, the face stays 60.
        val room = t.elevation.hardOffset?.let { Themed.px(context, Math.abs(it.y)) } ?: 0
        bar.layoutParams = (bar.layoutParams as LayoutParams).apply { height = px(VoiceBar.HEIGHT) + room }
        Ui.gap(bar, px(VoiceBar.GAP))

        // The name: 14, the body family at weights.strong. The status: caption size, weights.body, textMuted.
        nameView.text = l.name
        nameView.setTextColor(l.text)
        type(nameView, t.font.weights.strong, VoiceBar.NAME_SP, VoiceBar.NAME_LINE_SP)
        statusText.setTextColor(l.muted)
        type(statusText, t.font.weights.body, t.font.scale.caption, t.font.scale.caption * VoiceBar.STATUS_LINE)
        Ui.gap(statusRow, px(VoiceBar.STATUS_GAP))
        meter.configure(l.accent, t.motion)

        // The caption: surface, outline, radius.lg, the card shadow or the hard offset, 12 by 8 inside —
        // and with window chrome, a 24 dp "captions" title bar across its top, the 12 by 8 under it.
        val ph = px(VoiceBar.CAPTION_PAD_H); val pv = px(VoiceBar.CAPTION_PAD_V)
        val captionCorner = Themed.px(context, l.captionRadiusDp).toFloat()
        val window = WindowChrome.titleBar(t, WindowChrome.Surface.CAPTION)
        if (window == null) {
            Themed.surface(caption, t, l.surface, captionCorner, line, l.border, t.elevation.card, ph, pv, ph, pv)
            captionBar.visibility = GONE
            captionBody.setPadding(0, 0, 0, 0)
        } else {
            captionBar.frame(caption, captionBody, window, t, l.surface, captionCorner, line, l.border, t.elevation.card, ph, pv, ph, pv)
        }
        captionYou.setTextColor(l.accent)
        type(captionYou, t.font.weights.strong, VoiceBar.YOU_SP, null)
        captionYou.letterSpacing = VoiceBar.YOU_TRACKING_EM.toFloat()
        captionText.setTextColor(l.text)
        type(captionText, t.font.weights.strong, VoiceBar.CAPTION_SP, VoiceBar.CAPTION_LINE_SP)

        // A circle: the logo fitted into 70% of it, whole, on its plate.
        Mark.fill(avatar, t, VoiceBar.BUTTON, VoiceBar.AVATAR_GLYPH, "circle", null, l.accent, l.onAccent, l.hostImage, l.logoUrl,
            l.logoPlate, l.name.trim().firstOrNull()?.uppercase() ?: "A")
        shownLoader = null
        refresh()
    }

    /** Text in the design's body family at a weight and size, spaced as the design spaces body text. */
    private fun type(tv: TextView, weight: Int, sp: Double, lineSp: Double?) {
        val f = look.t.font
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp.toFloat())
        Themed.face(tv, f.body, weight, graine)
        tv.letterSpacing = f.letterSpacing.body.toFloat()
        lineSp?.let { Ui.lineHeight(tv, it.toFloat()) }
    }

    /** An SDK icon in the design's style, at the bar's icon size. */
    private fun icon(name: String, color: Int): Drawable = icons.getOrPut("$name|$color") {
        IconDrawable(name, look.t.icons.style, look.t.icons.stroke, color, px(VoiceBar.ICON))
    }

    override fun onSessionReady(session: SessionConfig) = applyLook()
    override fun onCallState(connected: Boolean, connecting: Boolean) {
        if (connected || connecting) micDenied = false
        refresh()
    }
    override fun onQuality(quality: ConnectionQuality) = refresh()
    override fun onNetwork(online: Boolean) = refresh()
    override fun onReconnecting(active: Boolean) = refresh()
    override fun onConnectProgress(stage: ConnectStage?, slow: Boolean, failed: Boolean) = refresh()
    override fun onConnectivity(state: Connectivity, attempt: Int, reason: String?) = refresh()
    override fun onTransport(transport: Transport, requested: Transport, reason: String?, message: String?) = refresh()
    override fun onError(message: String) = refresh()
    override fun onChatState(connected: Boolean, connecting: Boolean) = refresh()
    override fun onEnded() { lastCaption = null; captionLive = false; refresh() }
    override fun onMicDenied(reason: String) { micDenied = true; refresh() }

    override fun onCaption(role: Role, text: String, live: Boolean) {
        if (text.isNotBlank()) { lastCaption = text; captionRole = role; captionLive = live }
        refresh()
    }

    /** The surfaces as they stand: the dashboard's, a chat attached or not, the customer's last switch. */
    private fun surfaces(): VoiceBar.Surfaces? = chat?.let { VoiceBar.surfaces(look.appearance, chosenSurface) }

    private fun refresh() {
        val l = look
        val t = l.t
        val source = chat
        // The app opened the chat (GraineChat.open): the bar moves to it, where the agent offers one.
        (source as? GraineChat)?.let { g ->
            if (g.opens != seenOpens) {
                seenOpens = g.opens
                if (surfaces()?.allowed?.contains("chat") == true) chosenSurface = "chat"
            }
        }
        val s = surfaces()
        val inChat = source != null && s?.start == "chat"
        val canSwitch = source != null && s?.canSwitch == true
        val failed = voice.connectivity == Connectivity.FAILED
        val callFailed = voice.callFailed

        val status = VoiceBar.status(VoiceBar.StatusInput(
            error = voice.error != null,
            chat = inChat,
            chatOnline = source?.online == true,
            chatFailed = source?.failed == true,
            online = voice.online,
            reconnecting = voice.reconnecting,
            connectivity = voice.connectivity,
            micDenied = micDenied,
            ready = voice.ready,
            connecting = voice.connecting,
            muted = voice.muted,
            connected = voice.connected,
            thinking = voice.phase == CallPhase.THINKING,
            speaking = voice.phase == CallPhase.SPEAKING,
            quality = voice.quality,
            callFailed = callFailed,
            connectStage = voice.connectStage,
            slowConnect = voice.slowConnect,
            connectingText = l.loader.text.connecting ?: VoiceBar.DEFAULT_CONNECTING,
        ))
        val moment = status.loader
        if (moment != null) {
            // Waiting: the status line IS the loader the brand chose, in its words for this moment —
            // or the bar's own, when it has some ("Still connecting…").
            val loaderWords = VoiceBar.loaderWords(status, l.loader.text)
            val key = "$moment|$loaderWords"
            if (shownLoader != key) {
                shownLoader = key
                statusSlot.removeAllViews()
                statusSlot.addView(GraineLoaderView.withLabel(context, l.loader, l.loaderColor, l.border, l.muted,
                    loaderWords, sizeDp = VoiceBar.LOADER, logoUrl = l.logoUrl) { label ->
                    type(label, t.font.weights.strong, maxOf(t.font.scale.caption, Math.round(VoiceBar.LOADER * 0.65).toDouble()), null)
                    label.maxLines = 1
                    label.ellipsize = TextUtils.TruncateAt.END
                }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START })
            }
        } else {
            if (shownLoader != null || statusRow.parent == null) {
                shownLoader = null
                statusSlot.removeAllViews()
                statusSlot.addView(statusRow)
            }
            statusText.text = status.text
            meter.visibility = if (status.meter) VISIBLE else GONE
        }
        // "Couldn’t load · tap to retry" and "Couldn’t connect · tap to retry" mean the words too, not only
        // the microphone: a failed load reloads the page, a call nothing could carry is placed again.
        val chatRetries = inChat && source?.failed == true && source.online.not()
        val retries = ((failed || callFailed) && !inChat) || chatRetries
        words.setOnClickListener(if (retries) OnClickListener {
            when {
                chatRetries -> retryChat()
                voice.connectivity == Connectivity.FAILED -> voice.retry()
                else -> voice.start()
            }
        } else null)
        words.isClickable = retries

        // The way to the other surface, only when both are offered.
        switchButton.visibility = if (canSwitch) VISIBLE else GONE
        if (canSwitch) {
            switchButton.draw(l.soft, icon(if (inChat) SdkIcons.CALL else SdkIcons.CHAT, l.text), if (inChat) "Switch to a call" else "Switch to chat")
        }

        // Off a call the microphone STARTS one; on a call it is the mute; after a failed load it retries.
        micButton.visibility = if (inChat) GONE else VISIBLE
        val muted = voice.muted
        micButton.draw(
            if (muted) l.danger else l.accent,
            icon(if (muted) SdkIcons.MIC_OFF else SdkIcons.MIC, if (muted) Color.WHITE else l.onAccent),
            when { failed || callFailed -> "Try again"; !voice.connected -> "Start talking to ${l.name}"; muted -> "Unmute"; else -> "Mute" },
            enabled = !VoiceBar.micBlocked(voice.error != null, micDenied, voice.connecting, voice.ready, voice.connectivity),
        )

        // Loud only while there is a call to end; otherwise it closes.
        val live = voice.connected
        endButton.draw(
            if (live) l.danger else l.soft,
            icon(if (live) SdkIcons.END else SdkIcons.CLOSE, if (live) Color.WHITE else l.muted),
            if (live) "End call" else "Close",
        )

        // How big the chat is (contract/DESIGN.md "Chat window" → Size): above the bar, a phone's sheet or a
        // tablet's panel, or the whole screen — the open's size, then the app's, then the design's.
        val metrics = resources.displayMetrics
        val decided = ChatWindowLayout.barPresentation(
            source != null && inChat, (source as? GraineChat)?.openedSize, graine.config.chatSize, t.chat?.size,
            metrics.widthPixels / metrics.density.toDouble(), metrics.heightPixels / metrics.density.toDouble(),
        )
        // A sheet or a panel needs an activity to open its window from; a bar made without one keeps the compact window.
        val presentation = if ((decided == ChatWindowLayout.Presentation.SHEET || decided == ChatWindowLayout.Presentation.PANEL) &&
            Ui.activity(context) == null) ChatWindowLayout.Presentation.WINDOW else decided
        showBigChat(source, presentation)

        // Above the bar: the compact chat window in the chat, else the line being said on a call.
        if (source != null && presentation == ChatWindowLayout.Presentation.WINDOW) {
            val w = chatWindow ?: ChatWindowView(context, graine).also { cw ->
                chatWindow = cw
                cw.onSend = { text -> chat?.send(text) }
                cw.onRetry = { retryChat() }
                cw.onCancelCall = { id -> (chat as? GraineChat)?.cancelUpcomingCall(id) }
                cw.onNewConversation = { (chat as? GraineChat)?.newConversation() }
                cw.onCollapse = { closeChat() }
                chatSlot.addView(cw, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
            }
            w.render(t, l.accent, l.onAccent, l.name, l.hostImage, l.logoUrl, l.logoPlate, source.messages, source.online,
                replying = source.typing, failed = source.failed, keyboardUp = imeLiftPx > 0, loader = l.loader, loaderColor = l.loaderColor,
                upcoming = (source as? GraineChat)?.upcomingRows() ?: emptyList(), callName = graine.schedulingRules.callName ?: l.name)
            chatSlot.visibility = VISIBLE
            if (!chatShown) Themed.enter(chatSlot, t, px(VoiceBar.RISE).toFloat())
        } else {
            chatSlot.visibility = GONE
        }
        chatShown = presentation == ChatWindowLayout.Presentation.WINDOW
        // The chat connects while this bar shows it, and only then.
        showChat(inChat && isAttachedToWindow && isShown)

        val showCaption = !inChat && voice.connected && !lastCaption.isNullOrBlank()
        captionText.text = lastCaption
        // Still arriving: the words at 75%, the card itself solid.
        captionText.alpha = if (captionLive) VoiceBar.LIVE_ALPHA else 1f
        // WHO is saying it: captions carry both sides, and an unlabelled line reads as the agent talking to itself.
        captionYou.visibility = if (captionRole == Role.CUSTOMER) VISIBLE else GONE
        caption.visibility = if (showCaption) VISIBLE else GONE
        // It rises in over the design's duration; with no motion, it is simply there.
        if (showCaption && !captionShown) Themed.enter(caption, t, px(VoiceBar.RISE).toFloat())
        captionShown = showCaption
    }

    private fun pressMic() {
        if (voice.connectivity == Connectivity.FAILED) { voice.retry(); return }
        if (voice.connected) voice.setMuted(!voice.muted) else voice.start()
        refresh()
    }

    /** Hang up, THEN close: the goodbye leaves the speaker first, and a page torn down sooner files the call as dropped. */
    private fun pressEnd() {
        val live = voice.connected || voice.connecting
        if (live) voice.end()
        // Closing a chat-only bar ends the chat, as hanging up ends a call.
        if (surfaces()?.start == "chat") (chat as? GraineChat)?.close()
        val close = onClose ?: return
        if (live) postDelayed({ close() }, VoiceBar.CLOSE_AFTER_END_MS) else close()
    }

    private fun setSurface(next: String) {
        chosenSurface = next
        refresh()
    }

    private fun switchSurface() {
        val next = if (surfaces()?.start == "chat") "voice" else "chat"
        // Into the chat, the call ends: two conversations with one agent is the agent answering twice.
        if (next == "chat" && (voice.connected || voice.connecting)) voice.end()
        // Back to the call, the chat ends — at once, not when it is next unseen. The size an open asked for stays,
        // for the way back.
        if (next == "voice") { showChat(false); (chat as? GraineChat)?.hangUp() }
        setSurface(next)
    }

    /** The chevron, and closing a sheet, a panel or the full screen: back to the call where there is one, else close. */
    private fun closeChat() {
        when (ChatWindowLayout.close(surfaces()?.canSwitch == true)) {
            ChatWindowLayout.Close.BACK_TO_CALL -> switchSurface()
            ChatWindowLayout.Close.CLOSE_BAR -> pressEnd()
        }
    }

    /**
     * A bigger chat window: a phone's sheet or a tablet's panel in a window of
     * its own ([ChatWindowDialog]), the agent's components above its composer;
     * the whole screen in the chat's own activity, opened once. Anything else
     * takes them away.
     */
    private fun showBigChat(source: ChatSource?, presentation: ChatWindowLayout.Presentation?) {
        val l = look
        val inDialog = presentation == ChatWindowLayout.Presentation.SHEET || presentation == ChatWindowLayout.Presentation.PANEL
        if (source != null && presentation != null && inDialog) {
            val d = bigChat?.takeIf { it.presentation == presentation } ?: run {
                dismissBigChat()
                ChatWindowDialog(context, graine, presentation) { post { closeChat() } }.also { dialog ->
                    dialog.chat.onSend = { text -> chat?.send(text) }
                    dialog.chat.onRetry = { retryChat() }
                    dialog.chat.onCancelCall = { id -> (chat as? GraineChat)?.cancelUpcomingCall(id) }
                    // A sheet and a panel offer New conversation like the window above the bar.
                    dialog.chat.onNewConversation = { (chat as? GraineChat)?.newConversation() }
                    dialog.chat.onCollapse = { closeChat() }
                    dialog.chat.setAccessory(GraineWidgetView(context).also { it.graine = graine; it.scheme = scheme })
                    bigChat = dialog
                }
            }
            d.chat.collapseLabel = if (surfaces()?.canSwitch == true) "Collapse" else "Close"
            d.paint(l.t)
            d.chat.render(l.t, l.accent, l.onAccent, l.name, l.hostImage, l.logoUrl, l.logoPlate, source.messages, source.online,
                replying = source.typing, failed = source.failed, loader = l.loader, loaderColor = l.loaderColor,
                upcoming = (source as? GraineChat)?.upcomingRows() ?: emptyList(), callName = graine.schedulingRules.callName ?: l.name)
            val host = Ui.activity(context)
            if (!d.isShowing && isAttachedToWindow && host != null && !host.isFinishing) d.show()
        } else {
            dismissBigChat()
        }
        // The bar's own components step aside while a bigger window carries them.
        widgetView.visibility = if (inDialog) GONE else VISIBLE
        if (source != null && presentation == ChatWindowLayout.Presentation.SCREEN && !screenOpen && isAttachedToWindow) {
            screenOpen = true
            GraineChatActivity.openFromBar(context, graine.name)
        }
    }

    private fun dismissBigChat() {
        val d = bigChat ?: return
        bigChat = null
        if (d.isShowing) d.dismiss()
    }

    /** Nothing could connect the chat: the customer asked to try again. */
    private fun retryChat() {
        val c = chat ?: return
        if (c is GraineChat) c.connect() else { c.shown(false); c.shown(true) }
    }

    private fun px(dp: Double): Int = Ui.dp(context, dp.toFloat())

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
            // The chat's messages give up height while the keyboard is up.
            if (chatShown) refresh()
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
