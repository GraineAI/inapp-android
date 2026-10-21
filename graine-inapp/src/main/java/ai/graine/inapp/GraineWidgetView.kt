package ai.graine.inapp

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * THE COMPONENT THE AGENT PUT ON SCREEN — drawn by the SDK, not by the host.
 *
 * A port of the React Native SDK's GraineWidget, drawing the same kinds the
 * same way: a product list with pictures in five looks (gallery, cards, list,
 * spotlight, compact), a plan comparison, an info card, chips for choices and
 * quick actions, calendar slots — and, for a kind this build cannot draw, the
 * agent's own fallback sentence rather than a guess. Where it goes follows the
 * one rule the web embed also follows ([Widgets.surface]):
 *
 *   screen  the component IS the screen — pictures, several things to compare
 *   sheet   slides over the app, dimmed behind it — a choice to finish
 *   card    sits inline, right here — a fact, or a kind we cannot draw
 *
 * Screens and sheets are presented in a transparent dialog window above the
 * activity. Never opaque over the call: the WebView carrying it keeps running
 * underneath, and the "Still listening" line says so.
 *
 * A tap answers the agent with the structured payload the runtime reads AND
 * the sentence that is the customer's turn ([GraineVoice.answerWidget]); if
 * the component names an in-app action (`on_select`), that runs first, through
 * the action the app registered, and a refusal is reported to the agent.
 * Closing is not answering: the agent is told quietly
 * (`component_dismissed`), and [reopen] puts it back without another fetch.
 *
 * Mount it anywhere; [GraineAgentBarView] mounts one above itself so a card
 * moves with the bar. Everything visual comes from the dashboard: the design
 * the brand chose (contract/DESIGN.md → Components), or from a server that
 * predates designs, the appearance (accent, radius, light/dark/auto) — and
 * the loader. A component's own style (its preset, radius, accent, a pinned
 * platform) still wins over the design; the design supplies every value the
 * component does not set. Under a design's window chrome a card opens as a
 * desktop window: its title in a title bar over a `surface` body
 * (contract/DESIGN.md → Window chrome). The properties below override one
 * thing each. Main thread only.
 */
class GraineWidgetView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs), GraineListener {

    /** Single-colour overrides, each #RRGGBB. Null keeps the dashboard's. */
    data class Theme(
        val surface: String? = null,
        val border: String? = null,
        val text: String? = null,
        val muted: String? = null,
        val onAccent: String? = null,
    )

    /** Buttons and badges. Default: the component's own accent, then the dashboard's. */
    var accent: String? = null
        set(v) { field = v; render() }
    /** Corner rounding in dp. Default: the component's own, then its platform grammar, then the dashboard's. */
    var radius: Int? = null
        set(v) { field = v; render() }
    /** "light", "dark" or "auto" (the device). Default: the dashboard's theme. */
    var scheme: String? = null
        set(v) { field = v; render() }
    var theme: Theme? = null
        set(v) { field = v; render() }
    /** Called after every change of what is drawn — to show your own "show that again", say. */
    var onChange: (() -> Unit)? = null

    /** The component on screen, if any. */
    val widget: GraineWidget? get() = flow.widget
    /** What the customer last chose, kept as a line until the next component or a dismiss. */
    val answered: GraineAnswer? get() = flow.answered
    /** The last component closed without an answer — [reopen] puts it back. */
    val closedWidget: GraineWidget? get() = flow.closed
    /** A component is on its way. */
    val isLoading: Boolean get() = flow.loading != null

    /**
     * The agent whose components this view draws and answers — the default
     * one unless set. A voice bar or a chat view sets its own on the
     * components view it holds; set it on a standalone one for another of the
     * app's agents (`GraineInApp.get("support")`).
     */
    var graine: GraineInApp? = if (isInEditMode) null else GraineInApp.primary()
        set(v) {
            if (field === v) return
            val attached = isAttachedToWindow
            if (attached) unwire()
            field = v
            // Another agent's components: nothing of this one's is left on screen.
            flow = WidgetFlow(v?.config?.baseUrl ?: "")
            ticked.clear()
            closeDialog()
            if (attached) wire()
            render()
        }
    private var flow = WidgetFlow(graine?.config?.baseUrl ?: "")
    private val main = Handler(Looper.getMainLooper())
    private val tick = Runnable { if (flow.tick(SystemClock.uptimeMillis())) render() else schedule() }

    /** Rows ticked while choosing several, for the component on screen. */
    private val ticked = mutableListOf<String>()
    private var tickedFor: String? = null
    private var page = 0
    private var wasLive = false
    private var wasChatLive = false
    /** The component on screen arrived in the chat, not on a call: nothing is listening, so nothing says so. */
    private var typed = false

    private var dialog: Dialog? = null
    private var dialogSurface: String? = null
    private var unwatch: (() -> Unit)? = null
    /** The component that last came in over the design's duration — a redraw of it does not come in again. */
    private var enteredFor: String? = null
    private var bodyScroll: ScrollView? = null
    private var pager: HorizontalScrollView? = null
    private var restore: Triple<String, Int, Int>? = null

    init {
        orientation = VERTICAL
        visibility = GONE
    }

    // ── what hosts may call ────────────────────────────────────────────────

    /** Draw a frame received some other way — a preview, a test — exactly as if the agent sent it. */
    fun present(frame: JSONObject) = onWidget(frame)

    /** Show the loader for a component on its way, as if the agent announced it. */
    fun presentPending(hint: JSONObject) = onWidgetPending(hint)

    /**
     * Close the component without choosing. The agent is told, quietly — it
     * must not go on describing options nobody can see, and it should not
     * speak up because someone closed a card.
     */
    fun dismiss() {
        val report = flow.dismiss()
        if (report != null) {
            // On whichever conversation is live — the call or the chat.
            graine?.reportEvent("component_dismissed", Widgets.DISMISSED_DETAIL, mapOf("quiet" to true, "data" to report.eventData()))
        }
        graine?.clearPendingWidget()
        render()
    }

    /** Put back the last component the customer closed, exactly as it was. */
    fun reopen() { if (flow.reopen()) render() }

    // ── what the agent says ────────────────────────────────────────────────

    override fun onWidget(widget: JSONObject) {
        // Decided as it arrives: the conversation it belongs to is the one live now.
        typed = graine?.inTextChat() == true
        flow.onWidget(widget, SystemClock.uptimeMillis())
        render()
    }

    override fun onWidgetPending(hint: JSONObject) {
        typed = graine?.inTextChat() == true
        flow.onPending(hint, SystemClock.uptimeMillis())
        render()
    }

    override fun onCaption(role: Role, text: String, live: Boolean) {
        if (role == Role.AGENT && flow.awaitingReply) { flow.onAgentStarted(); render() }
    }

    override fun onCallState(connected: Boolean, connecting: Boolean) {
        val live = connected || connecting
        // A new call is a new conversation: the last one's card must not appear over it.
        if (live && !wasLive) { flow.reset(); render() }
        wasLive = live
    }

    override fun onChatState(connected: Boolean, connecting: Boolean) {
        // A chat that (re)connects is a new conversation on the server: a card from the last one cannot be answered.
        if (connected && !wasChatLive) { flow.reset(); render() }
        wasChatLive = connected
    }

    override fun onChatMessage(role: Role, text: String, live: Boolean) {
        if (role == Role.AGENT && flow.awaitingReply) { flow.onAgentStarted(); render() }
    }

    /** A component nobody can answer any more is not left on screen. */
    override fun onEnded() { flow.reset(); render() }

    override fun onSessionReady(session: SessionConfig) = render()

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        // Light and dark are picked again when the phone switches.
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        wire()
    }

    override fun onDetachedFromWindow() {
        unwire()
        main.removeCallbacks(tick)
        closeDialog()
        super.onDetachedFromWindow()
    }

    private fun wire() {
        val g = graine ?: return
        g.addListener(this)
        unwatch = g.onDesignChange { render() }
        wasLive = g.voice.connected || g.voice.connecting
        wasChatLive = g.chat.connected
        // Drawn while nothing was listening — a panel that was closed — and still unanswered.
        flow.seed(g.pendingWidget)
        render()
    }

    private fun unwire() {
        graine?.removeListener(this)
        unwatch?.invoke()
        unwatch = null
    }

    private fun schedule() {
        main.removeCallbacks(tick)
        val at = flow.nextDeadline() ?: return
        main.postDelayed(tick, (at - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    // ── answering ──────────────────────────────────────────────────────────

    private fun answer(a: WidgetAnswer) {
        val g = graine ?: return
        val sub = flow.submit(a.data, a.summary, SystemClock.uptimeMillis()) ?: return
        g.clearPendingWidget()
        // The app's own job first, not awaited: a slow handler must never delay the agent hearing the choice.
        sub.action?.let { call ->
            g.executeAction(call.name, JSONObject(call.args as Map<*, *>)) { r ->
                if (r.status != ActionResult.Status.OK) {
                    g.reportEvent(
                        "in_app_action_failed",
                        "\"${call.name}\" did not run: ${r.message.ifEmpty { r.status.wire }}. Do not describe a screen the customer cannot see.",
                    )
                }
            }
        }
        // To the conversation that is live: the call's page, or the chat's socket.
        g.answerWidget(sub.widgetId, sub.data, sub.summary)
        ticked.clear()
        render()
    }

    // ── drawing ────────────────────────────────────────────────────────────

    /**
     * Everything a render needs to know about colour, shape and type, resolved
     * once. The order for each value: the host's override, then the
     * component's own style, then the design, then — from a server with no
     * design — the appearance, which is exactly the look from before designs.
     */
    private inner class Skin(look: WidgetLook?) {
        private val host = this@GraineWidgetView
        val appearance: JSONObject? = graine?.session?.appearance
        val dash = Appearance.resolve(appearance, Appearance.DEFAULT_ACCENT, "", origin = graine?.config?.baseUrl)
        /** The design's tokens for this view's light or dark. Null: no design. */
        val tokens: ThemeTokens? = graine?.themeTokens(context, host.scheme)
        /** The chat window's cards (contract/DESIGN.md "Chat window v2" → Cards): the classic card from a design before v2. */
        val cards: ChatStyleCards? = tokens?.let { Surfaces.styleOf(it).cards }
        val grammar: PlatformGrammar = Widgets.GRAMMAR.getValue(Widgets.resolvePlatform(look?.platform ?: "auto", "android", Build.MANUFACTURER))
        /** A platform chosen on the component is the component's own style: its grammar wins over the design. */
        private val pinned = look != null && look.platform != "auto"
        /** Host override, then the component's own, then the design's, then the dashboard's. */
        val accentHex: String = host.accent ?: look?.accent ?: tokens?.colors?.accent ?: dash.accent
        /** The accent in force is the design's own, so its onAccent and accentSoft belong to it. */
        private val designAccent = tokens != null && host.accent == null && look?.accent == null
        /** dp. Host override, then the component's own, then a pinned platform's, then the design's, then the dashboard's. */
        val radius: Int = host.radius ?: look?.radius ?: (if (pinned) grammar.radius
            else cards?.let { Math.round(it.radius).toInt() } ?: Math.round(dash.radius).toInt())
        /** Nothing more specific than the design chose the corners. */
        private val designCorners = tokens != null && host.radius == null && look?.radius == null && !pinned
        private val palette = Appearance.palette(host.scheme?.let { JSONObject().put("theme", it) } ?: appearance, Ui.isDeviceDark(context), accentHex)
        private val overrides = host.theme
        val dark = tokens?.isDark ?: (palette.mode == "dark")
        val accent: Int = Appearance.argb(accentHex) ?: Color.parseColor(Appearance.DEFAULT_ACCENT)
        /** Sheets, and a screen's header and footer. Translucent under glass. */
        val surface: Int = Appearance.argb(overrides?.surface) ?: tokens?.let { Themed.surface(it) } ?: Appearance.argb(palette.surface)!!
        /** Cards: raised off what they sit on. */
        val raised: Int = Appearance.argb(overrides?.surface) ?: tokens?.let { Themed.color(it.colors.surfaceRaised) } ?: surface
        /** A full-screen component's page. */
        val page: Int = Appearance.argb(overrides?.surface) ?: tokens?.let { Themed.color(it.colors.background) } ?: surface
        val border: Int = Appearance.argb(overrides?.border) ?: tokens?.let { Themed.color(it.colors.border) } ?: Appearance.argb(palette.border)!!
        val borderHex: String = overrides?.border ?: tokens?.colors?.border ?: palette.border
        val text: Int = Appearance.argb(overrides?.text) ?: tokens?.let { Themed.color(it.colors.text) } ?: Appearance.argb(palette.text)!!
        val muted: Int = Appearance.argb(overrides?.muted) ?: tokens?.let { Themed.color(it.colors.textMuted) } ?: Appearance.argb(palette.muted)!!
        val onAccent: Int = Appearance.argb(overrides?.onAccent) ?: tokens?.takeIf { designAccent }?.let { Themed.color(it.colors.onAccent) }
            ?: Appearance.argb(palette.onAccent)!!
        val loader: LoaderLook = Loaders.resolve(appearance?.opt("loader"))
        /** The loader's colour: its own, else the DESIGN's accent, else the DASHBOARD's — as the RN GraineLoader draws it. */
        val loaderColor: Int = Appearance.argb(loader.color) ?: Appearance.argb(tokens?.colors?.accent) ?: Appearance.argb(dash.accent) ?: accent
        fun tint(alpha: Double) = Appearance.tint(accentHex, alpha)
        fun borderTint(alpha: Double) = Appearance.tint(borderHex, alpha)

        /** Chips and badges: the design's accentSoft when the accent is the design's, else the accent at `alpha`. */
        fun soft(alpha: Double): Int = tokens?.takeIf { designAccent }?.let { Themed.color(it.colors.accentSoft) } ?: tint(alpha)
        /** The width of every outline, px. */
        val line: Int = tokens?.let { Themed.px(context, it.shape.borderWidth) } ?: dp(1)
        /** A row, a tile or a skeleton inside a component, px. */
        val inner: Float = (if (designCorners) Themed.px(context, tokens!!.shape.radius.md) else dp(maxOf(10, radius - 6))).toFloat()
        /** A picture inside a row, px. */
        val picture: Float = (if (designCorners) Themed.px(context, tokens!!.shape.radius.sm) else dp(maxOf(8, radius - 8))).toFloat()
        /** A sheet's top corners, px. */
        val sheet: Float = (if (designCorners) Themed.px(context, tokens!!.shape.radius.xl) else dp(maxOf(20, radius))).toFloat()
        /** Primary buttons: the design's medium radius, unless a platform is pinned. `pill` asks for the grammar's pill where it has one. */
        fun button(pill: Boolean): Float =
            if (tokens != null && !pinned) Themed.px(context, tokens.shape.radius.md).toFloat()
            else dp(if (pill && grammar.pillButtons) 999 else grammar.buttonRadius).toFloat()
        /** Chips: the design's largest radius (a pill at Graine Classic's corners, square at none), unless a platform is pinned. */
        val chip: Float = (if (tokens != null && !pinned) Themed.px(context, tokens.shape.radius.xl) else dp(999)).toFloat()
        /** A fact's tag. */
        val fact: Float = (if (tokens != null && !pinned) Themed.px(context, tokens.shape.radius.sm) else dp(grammar.chipRadius)).toFloat()
        /** A slot's outline. */
        val slot: Float = (if (tokens != null && !pinned) Themed.px(context, tokens.shape.radius.md) else dp(11)).toFloat()
        val titleWeight: Int = if (tokens != null && !pinned) tokens.font.weights.title else grammar.titleWeight
        /** Behind a sheet: the design's scrim at 40%. */
        val scrim: Int = tokens?.let { Themed.alpha(Themed.color(it.colors.scrim), 0.4) } ?: 0x73000000
        /** Inside a card, and between its items, px. */
        val cardPad: Int = tokens?.let { Themed.px(context, it.spacing.cardPadding) } ?: dp(14)
        fun gap(compact: Boolean): Int = tokens?.let { Themed.px(context, it.spacing.gap * (if (compact) 0.6 else 1.0)) } ?: dp(if (compact) 6 else 10)

        /** A line of text: the element's own size and weight before designs; the design's role for it, or its scale, with one. */
        fun label(s: CharSequence, sp: Float, color: Int, weight: Int = 400, lines: Int = 0, role: Themed.Role? = null): TextView =
            Ui.text(context, s, sp, color, weight, lines).also { tv -> tokens?.let { Themed.type(tv, it, role, graine, sp, weight) } }

        /**
         * A card: raised, outlined when the chat's cards are, with their shadow — or the design's hard offset where
         * they draw it (classic: the design's own card, as before).
         */
        fun card(v: View, radiusPx: Float, l: Int, t: Int, r: Int, b: Int, legacyElevation: Int = 0) {
            val tk = tokens
            val c = cards
            if (tk == null || c == null) {
                v.background = Ui.rounded(raised, radiusPx, dp(1), border)
                v.setPadding(l, t, r, b)
                if (legacyElevation > 0) v.elevation = dp(legacyElevation).toFloat()
            } else {
                val hard = c.hard && tk.elevation.hardOffset != null
                val drawn = if (hard) tk else tk.copy(elevation = tk.elevation.copy(hardOffset = null))
                Themed.surface(v, drawn, raised, radiusPx, if (c.border) line else 0, border, if (hard) null else c.shadow, l, t, r, b)
            }
        }

        /**
         * A component's main button where its own look says filled, drawn as the chat's cards say (filled, outline,
         * tonal, text) — unless the component (or the host) chose its own accent or pinned a platform: its own style wins.
         */
        val mainButton: ChatLook.Button? = cards?.takeIf { !pinned && host.accent == null && look?.accent == null }?.let { ChatLook.mainButton(it) }

        /** A component's large image: its own height, or the shape the chat's cards give it (always 100–260). */
        val imageShape: String = cards?.image ?: "auto"
    }

    private fun dp(v: Int) = Ui.dp(context, v)
    private fun dp(v: Float) = Ui.dp(context, v)

    /** Rebuild what is drawn from the flow's state. Cheap: a component is at most eight rows. */
    private fun render() {
        if (graine == null) return
        val w = flow.widget
        // Keep the customer's place when the same component is redrawn (a tick), not a new one.
        restore = if (w != null && w.id == tickedFor) Triple(w.id, bodyScroll?.scrollY ?: 0, pager?.scrollX ?: 0) else null
        if (w?.id != tickedFor) { ticked.clear(); page = 0; tickedFor = w?.id }
        bodyScroll = null; pager = null
        removeAllViews()

        val look = w?.let { Widgets.resolveLook(it.style, it.kind) }
        val s = Skin(look)
        flow.answered?.let { answeredLine(it, s)?.let { v -> addView(v, Ui.matchWidth().apply { bottomMargin = dp(8) }) } }

        var overlay: Pair<String, View>? = null
        val loading = flow.loading
        if (w == null && loading != null) {
            when (Widgets.surface(loading.kind)) {
                "screen" -> overlay = "screen" to pendingScreen(s)
                "sheet" -> overlay = "sheet" to pendingSheet(s)
                else -> addView(pendingCard(s), Ui.matchWidth().apply { bottomMargin = dp(8) })
            }
        } else if (w != null && look != null) {
            val content = WidgetContent(w)
            val body = body(w, content, look, s)
            when (content.surface(look)) {
                "screen" -> overlay = "screen" to screenSurface(content, body, s)
                "sheet" -> overlay = "sheet" to sheetSurface(content, body, s)
                else -> {
                    val card = cardSurface(w, content, body, s)
                    addView(card, Ui.matchWidth().apply { bottomMargin = dp(8) })
                    // A new component comes in over the design's duration; the same one redrawn does not.
                    if (enteredFor != w.id) Themed.enter(card, s.tokens, dp(8).toFloat())
                }
            }
        }
        enteredFor = w?.id
        showOverlay(overlay, s)
        visibility = if (childCount > 0) VISIBLE else GONE
        restore?.let { (_, y, x) -> post { bodyScroll?.scrollTo(0, y); pager?.scrollTo(x, 0) } }
        schedule()
        onChange?.invoke()
    }

    // ── the answered line ──────────────────────────────────────────────────

    private fun answeredLine(a: GraineAnswer, s: Skin): View? {
        val d = a.data
        val title = listOf("name", "title", "label", "slot").map { d[it] }.firstOrNull { Js.truthy(it) }?.let { Js.str(it) }?.trim() ?: ""
        val detail = listOf("price", "day").map { d[it] }.filter { Js.truthy(it) }.joinToString(" · ") { Js.str(it) }
        if (title.isEmpty() && detail.isEmpty()) return null
        val awaiting = flow.awaitingReply
        val row = Ui.horizontal(context, dp(10)).apply {
            s.card(this, dp(s.radius).toFloat(), dp(12), dp(10), dp(12), dp(10))
            Ui.holdsTouches(this)
        }
        val badge = FrameLayout(context).apply { background = Ui.rounded(s.soft(0.18), dp(13).toFloat()) }
        if (awaiting) {
            badge.addView(GraineLoaderView(context).apply { configure(s.loader, s.loaderColor, s.border, 14, s.dash.logoUrl) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        } else {
            badge.addView(s.label("✓", 14f, s.accent, 800).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        row.addView(badge, LayoutParams(dp(26), dp(26)))
        val words = Ui.vertical(context)
        words.addView(s.label(title.ifEmpty { detail }, 13.5f, s.text, 700, lines = 1))
        val second = if (awaiting) s.loader.text.thinking else if (title.isNotEmpty() && detail.isNotEmpty()) detail else null
        second?.let { words.addView(s.label(it, 11.5f, s.muted, 600, lines = 1, role = Themed.Role.CAPTION)) }
        row.addView(words, Ui.fill())
        if (!awaiting) {
            row.addView(s.label("Clear", 12f, s.muted, 700).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                setPadding(dp(4), 0, dp(4), 0)
                contentDescription = "Clear what I chose"
                isClickable = true
                setOnClickListener { dismiss() }
            })
        }
        return row
    }

    // ── a component on its way ─────────────────────────────────────────────

    private fun componentLoader(s: Skin, sizeDp: Int?, stacked: Boolean): View =
        GraineLoaderView.withLabel(context, s.loader, s.loaderColor, s.border, s.muted, s.loader.text.component, sizeDp, stacked, s.dash.logoUrl)

    /** Dimmed, and never focusable: the shape of what is coming, not content. */
    private fun bones(s: Skin, rows: Int): View = Ui.vertical(context, dp(10)).apply {
        alpha = 0.55f
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        repeat(rows) {
            val r = Ui.horizontal(context, dp(10)).apply {
                background = Ui.rounded(Color.TRANSPARENT, s.inner, s.line, s.border)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }
            r.addView(View(context).apply { background = Ui.rounded(s.borderTint(0.6), dp(8).toFloat()) }, LayoutParams(dp(72), dp(72)))
            val lines = Ui.vertical(context, dp(8))
            for ((w, h) in listOf(0.7f to 12, 0.4f to 12, 0.55f to 10)) {
                val holder = LinearLayout(context).apply { weightSum = 1f }
                holder.addView(View(context).apply { background = Ui.rounded(s.borderTint(0.6), dp(8).toFloat()) }, LayoutParams(0, dp(h), w))
                lines.addView(holder, Ui.matchWidth())
            }
            r.addView(lines, Ui.fill())
            addView(r, Ui.matchWidth())
        }
    }

    private fun closeButton(s: Skin): View = s.label("×", 20f, s.text, 600).apply {
        gravity = Gravity.CENTER
        background = Ui.rounded(s.borderTint(0.4), dp(22).toFloat())
        contentDescription = Widgets.closeLabel(typed)
        isClickable = true
        setOnClickListener { dismiss() }
    }

    private fun pendingScreen(s: Skin): View {
        val root = Ui.vertical(context).apply { setBackgroundColor(s.page); setPadding(dp(18), dp(16), dp(18), 0) }
        // A way out, always: a component that never arrives must not trap anyone behind a full screen.
        root.addView(closeButton(s), LayoutParams(dp(44), dp(44)))
        val middle = FrameLayout(context).apply { setPadding(0, 0, 0, dp(if (s.loader.skeleton) 24 else 72)) }
        middle.addView(componentLoader(s, 34, stacked = true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(middle, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (s.loader.skeleton) root.addView(bones(s, 3), Ui.matchWidth().apply { bottomMargin = dp(24) })
        return insetScreen(root, s.page)
    }

    private fun pendingSheet(s: Skin): View {
        val panel = sheetPanel(s, dp(18))
        panel.addView(Ui.vertical(context).apply {
            setPadding(0, dp(if (s.loader.skeleton) 10 else 34), 0, dp(if (s.loader.skeleton) 10 else 34))
            gravity = Gravity.CENTER_HORIZONTAL
            addView(componentLoader(s, 26, stacked = true), Ui.wrap())
        }, Ui.matchWidth())
        if (s.loader.skeleton) panel.addView(bones(s, 2), Ui.matchWidth())
        return scrim(panel, s)
    }

    /** The card a component on its way sits in. Never a window: the window is the component's, and it has not arrived. */
    private fun pendingCard(s: Skin): View = FrameLayout(context).apply {
        s.card(this, dp(s.radius).toFloat(), s.cardPad, s.cardPad, s.cardPad, s.cardPad)
        Ui.holdsTouches(this)
        addView(componentLoader(s, null, stacked = false), FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /**
     * A component's card opened as a window (contract/DESIGN.md → Window
     * chrome): the card's surface — its body `surface`, not `surfaceRaised`,
     * so the bar reads as a title bar — a 32 dp title bar across its top
     * holding the component's own title, and — returned — the column under it
     * that holds the rest, padded as the card itself is without chrome.
     */
    private fun windowed(card: LinearLayout, spec: WindowChrome.TitleBar, s: Skin, left: Int, top: Int, right: Int, bottom: Int): LinearLayout {
        // A title bar exists only for a design's tokens.
        val tokens = s.tokens ?: return card
        val o = theme
        // The white window: the host's own card surface if it set one, else the design's `surface`.
        val fill = Appearance.argb(o?.surface) ?: Themed.color(WindowChrome.cardFill(tokens))
        // The bar stays the design's solid surfaceRaised; the host's outline and muted colours reach it as the rest of the card.
        fun own(hex: String?, design: String) = hex?.takeIf { Appearance.argb(it) != null } ?: design
        val drawn = if (o == null) spec else spec.copy(outline = own(o.border, spec.outline), titleColor = own(o.muted, spec.titleColor))
        val bar = WindowTitleBar(context)
        val body = Ui.vertical(context)
        card.addView(bar, Ui.matchWidth())
        card.addView(body, Ui.matchWidth())
        bar.frame(card, body, drawn, tokens, fill, dp(s.radius).toFloat(), s.line, s.border, tokens.elevation.card, left, top, right, bottom)
        return body
    }

    // ── the three surfaces ─────────────────────────────────────────────────

    private fun screenSurface(c: WidgetContent, body: View, s: Skin): View {
        val root = Ui.vertical(context).apply { setBackgroundColor(s.page) }
        val header = Ui.horizontal(context, dp(12)).apply {
            setPadding(dp(18), dp(16), dp(18), dp(12))
            if (s.tokens != null) setBackgroundColor(s.surface)
        }
        header.addView(closeButton(s), LayoutParams(dp(44), dp(44)))
        val titles = Ui.vertical(context)
        if (c.title.isNotEmpty()) titles.addView(s.label(c.title, 18f, s.text, 800, lines = 1, role = Themed.Role.TITLE))
        if (c.prompt.isNotEmpty()) titles.addView(s.label(c.prompt, 12.5f, s.muted, 600, lines = 1).apply { setPadding(0, dp(1), 0, 0) })
        header.addView(titles, Ui.fill())
        root.addView(header, Ui.matchWidth())

        val scroll = ScrollView(context).apply { isFillViewport = false; clipToPadding = false }
        scroll.addView(FrameLayout(context).apply { setPadding(dp(18), 0, dp(18), dp(24)); addView(body, Ui.matchWidth()) })
        bodyScroll = scroll
        root.addView(scroll, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // The agent is still on the call — the difference between a screen it opened and one that replaced it.
        // In the chat there is no call to be on: no footer (Widgets.screenFooter).
        Widgets.screenFooter(c.prompt, typed)?.let { (label, line) ->
            val footer = Ui.vertical(context).apply {
                setPadding(dp(18), dp(12), dp(18), dp(20))
                background = topHairline(s)
            }
            footer.addView(s.label(label, 10.5f, s.muted, 800).apply { letterSpacing = 0.4f / 10.5f })
            footer.addView(s.label(line, 13.5f, s.text, 400, lines = 1).apply { setPadding(0, dp(2), 0, 0) })
            root.addView(footer, Ui.matchWidth())
        }
        return insetScreen(root, s.page)
    }

    private fun sheetPanel(s: Skin, gapPx: Int): LinearLayout = Ui.vertical(context, gapPx).apply {
        val r = s.sheet
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(s.surface)
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }
        setPadding(dp(18), dp(10), dp(18), dp(28))
        // A tap on the panel is the panel's, never the dimmed area's "close" underneath it.
        Ui.holdsTouches(this)
        addView(FrameLayout(context).apply {
            addView(View(context).apply { background = Ui.rounded(s.border, dp(999).toFloat()) },
                FrameLayout.LayoutParams(dp(s.grammar.grabberWidth), dp(s.grammar.grabberHeight), Gravity.CENTER_HORIZONTAL))
        }, Ui.matchWidth())
    }

    private fun sheetSurface(c: WidgetContent, body: View, s: Skin): View {
        val panel = sheetPanel(s, dp(14))
        if (c.title.isNotEmpty()) panel.addView(s.label(c.title, 18f, s.text, 800, role = Themed.Role.TITLE))
        if (c.prompt.isNotEmpty()) panel.addView(s.label(c.prompt, 13f, s.muted, 400).apply { Ui.lineHeight(this, 18f * (s.tokens?.font?.scale?.body?.div(15)?.toFloat() ?: 1f)) })
        val scroll = CappedScrollView(context, dp(420))
        scroll.addView(FrameLayout(context).apply { setPadding(0, 0, 0, dp(4)); addView(body, Ui.matchWidth()) })
        bodyScroll = scroll
        panel.addView(scroll, Ui.matchWidth())
        // Not answering is an answer: without a way out that is not a choice, the exits are a wrong pick or hanging up.
        panel.addView(s.label("None of these", 13.5f, s.text, 700).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            setPadding(dp(16), 0, dp(16), 0)
            background = Ui.rounded(s.borderTint(0.4), dp(999).toFloat())
            contentDescription = "None of these"
            isClickable = true
            setOnClickListener { dismiss() }
        }, Ui.wrap())
        return scrim(panel, s)
    }

    private fun cardSurface(w: GraineWidget, c: WidgetContent, body: View, s: Skin): View {
        val top = if (s.tokens != null) s.cardPad else dp(12)
        val card = Ui.vertical(context).apply {
            // Touches on the card are the card's: a drag started here moves the bar it sits on.
            Ui.holdsTouches(this)
        }
        // With window chrome the card opens as a window: its own title moves into the title bar and is not
        // drawn again below; everything else — the prompt, the rows and their titles, Dismiss — is unchanged.
        val window = WindowChrome.titleBar(s.tokens, WindowChrome.Surface.COMPONENT, c.title)
        val column = if (window != null) windowed(card, window, s, s.cardPad, top, s.cardPad, dp(4))
        else card.also { s.card(it, dp(s.radius).toFloat(), s.cardPad, top, s.cardPad, dp(4), legacyElevation = 6) }
        val title = WindowChrome.bodyTitle(window, c.title)
        if (title.isNotEmpty()) column.addView(s.label(title, 14.5f, s.text, 800, role = Themed.Role.TITLE).apply { setPadding(0, 0, 0, dp(2)) })
        if (c.prompt.isNotEmpty()) column.addView(s.label(c.prompt, 12.5f, s.muted, 400).apply {
            if (s.tokens == null) Ui.lineHeight(this, 17f)
            setPadding(0, 0, 0, dp(8))
        })
        else column.addView(View(context), LayoutParams(0, dp(6)))
        column.addView(body, Ui.matchWidth())
        column.addView(s.label("Dismiss", 12.5f, s.muted, 600).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(44)
            contentDescription = "Dismiss"
            isClickable = true
            setOnClickListener { dismiss() }
        }, Ui.wrap().apply { gravity = Gravity.END; topMargin = dp(10) })
        return card
    }

    private fun topHairline(s: Skin) = android.graphics.drawable.LayerDrawable(arrayOf(
        ColorDrawable(s.border), ColorDrawable(s.surface),
    )).apply { setLayerInset(1, 0, s.line, 0, 0) }

    /** The dimmed app behind a sheet; tapping it closes the sheet, the way every sheet does. */
    private fun scrim(panel: View, s: Skin): View {
        val root = FrameLayout(context).apply { setBackgroundColor(s.scrim) }
        root.addView(View(context).apply {
            contentDescription = "Close"
            isClickable = true
            setOnClickListener { dismiss() }
        }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = barInsets(insets)
            panel.setPadding(panel.paddingLeft, panel.paddingTop, panel.paddingRight, dp(28) + bottom)
            root.setPadding(0, top, 0, 0)
            insets
        }
        return root
    }

    /** A full-screen surface padded clear of the status and navigation bars. */
    private fun insetScreen(content: View, color: Int): View {
        val root = FrameLayout(context).apply { setBackgroundColor(color) }
        root.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = barInsets(insets)
            root.setPadding(0, top, 0, bottom)
            insets
        }
        return root
    }

    @Suppress("DEPRECATION")
    private fun barInsets(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
        else insets.systemWindowInsetTop to insets.systemWindowInsetBottom

    // ── the body: rows, chips, days, or the sentence ───────────────────────

    private fun body(w: GraineWidget, c: WidgetContent, look: WidgetLook, s: Skin): View = when {
        c.isCalendar -> calendar(c, s)
        c.isProducts -> products(c, look, s)
        c.isChips -> FlowRow(context, dp(8), dp(8)).apply {
            // Chips: the accent before designs; the design's soft accent, in its text colour, with one.
            val designed = s.tokens != null
            for (chip in c.chips) addView(s.label(chip.label, 13.5f, if (designed) s.text else s.onAccent, 700).apply {
                gravity = Gravity.CENTER_VERTICAL
                minHeight = dp(44)
                setPadding(dp(16), 0, dp(16), 0)
                background = if (designed) Ui.rounded(s.soft(0.15), s.chip) else Ui.rounded(s.accent, dp(999).toFloat())
                contentDescription = chip.label
                isClickable = true
                setOnClickListener { answer(c.pickChip(chip)) }
            })
        }
        // A kind this build does not draw: the sentence the agent gave us for exactly this case.
        else -> s.label(w.fallbackText.ifEmpty { "The assistant has something to show you." }, 14f, s.text, role = Themed.Role.BODY).apply {
            if (s.tokens == null) Ui.lineHeight(this, 20f)
        }
    }

    private fun calendar(c: WidgetContent, s: Skin): View = Ui.vertical(context, dp(12)).apply {
        if (c.note.isNotEmpty()) addView(s.label(c.note, 12.5f, s.muted, role = Themed.Role.CAPTION).apply { if (s.tokens == null) Ui.lineHeight(this, 18f) })
        for (day in c.days) {
            val block = Ui.vertical(context, dp(7))
            block.addView(s.label(day.label, 13f, s.text, 700))
            block.addView(FlowRow(context, dp(8), dp(8)).apply {
                for (slot in day.slots) addView(s.label(slot, 13.5f, s.text, 600).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    minHeight = dp(44)
                    setPadding(dp(14), 0, dp(14), 0)
                    background = Ui.rounded(Color.TRANSPARENT, s.slot, s.line, s.border)
                    contentDescription = "${day.label} at $slot"
                    isClickable = true
                    setOnClickListener { answer(c.pickSlot(day, slot)) }
                })
            }, Ui.matchWidth())
            addView(block, Ui.matchWidth())
        }
    }

    private fun products(c: WidgetContent, look: WidgetLook, s: Skin): View {
        val many = c.many(look)
        val gap = s.gap(look.density == "compact")
        fun draw(i: Int, variant: String): View {
            val r = c.rows[i]
            return row(r, variant, look, s, if (many) r.id in ticked else null) {
                if (many) {
                    // Ticking twice unticks; past the cap the oldest gives way rather than a tap doing nothing.
                    if (r.id in ticked) ticked.remove(r.id)
                    else { if (ticked.size >= look.maxSelect) ticked.removeAt(0); ticked.add(r.id) }
                    render()
                } else answer(c.pick(i))
            }
        }

        val list: View = when {
            look.preset == "spotlight" -> spotlight(c, s, gap, ::draw)
            look.columns == 2 -> Ui.vertical(context, gap).apply {
                // Two columns of picture-on-top tiles, for Gallery and Cards only.
                for (start in c.rows.indices step 2) {
                    val line = Ui.horizontal(context, gap).apply { gravity = Gravity.TOP }
                    line.addView(draw(start, "hero"), Ui.fill())
                    if (start + 1 < c.rows.size) line.addView(draw(start + 1, "hero"), Ui.fill())
                    else line.addView(View(context), Ui.fill())
                    addView(line, Ui.matchWidth())
                }
            }
            look.preset == "gallery" -> Ui.vertical(context, gap).apply { c.rows.indices.forEach { addView(draw(it, "hero"), Ui.matchWidth()) } }
            look.preset == "list" -> Ui.vertical(context).apply { c.rows.indices.forEach { addView(draw(it, "list"), Ui.matchWidth()) } }
            look.preset == "compact" -> Ui.vertical(context, gap).apply { c.rows.indices.forEach { addView(draw(it, "compact"), Ui.matchWidth()) } }
            else -> Ui.vertical(context, gap).apply { c.rows.indices.forEach { addView(draw(it, "row"), Ui.matchWidth()) } }
        }
        if (!many) return list

        // ONE BUTTON, ONE TURN. Below two it says what it still needs, and does nothing.
        val chosen = ticked.filter { id -> c.rows.any { it.id == id } }
        val ready = chosen.size >= 2
        val label = Widgets.manyButtonLabel(chosen.size, c.selectLabel)
        return Ui.vertical(context).apply {
            addView(list, Ui.matchWidth())
            addView(s.label(label, 14.5f, if (ready) s.onAccent else s.muted, 700).apply {
                gravity = Gravity.CENTER
                minHeight = dp(48)
                background = Ui.rounded(if (ready) s.accent else s.borderTint(0.5), s.button(pill = true))
                contentDescription = label
                isEnabled = ready
                isClickable = ready
                if (ready) setOnClickListener { answer(c.pickMany(chosen)) }
            }, Ui.matchWidth().apply { topMargin = dp(12) })
        }
    }

    /** One card in view, swipe for the next, dots for where you are. 86% wide, so the next one's edge shows. */
    private fun spotlight(c: WidgetContent, s: Skin, gap: Int, draw: (Int, String) -> View): View {
        val holder = Ui.vertical(context)
        val strip = Ui.horizontal(context, gap).apply { gravity = Gravity.TOP; setPadding(0, 0, dp(24), 0) }
        val dots = Ui.horizontal(context, dp(5)).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }
        val count = c.rows.size
        val scroller = object : HorizontalScrollView(context) {
            private var sized = 0
            private var startPage = 0
            fun step() = (Math.round(width * 0.86f) + gap).coerceAtLeast(1)
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                if (w == 0 || w == sized) return
                sized = w
                post {
                    val cardW = Math.round(w * 0.86f)
                    // LinearLayout's, named in full: inside this subclass a bare LayoutParams is FrameLayout's.
                    for (i in 0 until strip.childCount) strip.getChildAt(i).layoutParams = LinearLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }
            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) startPage = Math.round(scrollX.toFloat() / step())
                val handled = super.dispatchTouchEvent(ev)
                val a = ev.actionMasked
                if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
                    // Like snapToInterval with a fast deceleration: a real swipe moves one card, a nudge settles back.
                    val moved = scrollX - startPage * step()
                    val target = (startPage + when { moved > step() * 0.15f -> 1; moved < -step() * 0.15f -> -1; else -> 0 }).coerceIn(0, count - 1)
                    post { smoothScrollTo(target * step(), 0) }
                }
                return handled
            }
        }.apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = OVER_SCROLL_NEVER
            addView(strip)
        }
        val guess = Math.round(resources.displayMetrics.widthPixels * 0.7f)
        c.rows.indices.forEach { strip.addView(draw(it, "hero"), LayoutParams(guess, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        fun paintDots() {
            dots.removeAllViews()
            for (i in 0 until count) dots.addView(View(context).apply {
                background = Ui.rounded(if (i == page) s.accent else s.border, dp(999).toFloat())
            }, LayoutParams(dp(if (i == page) 14 else 5), dp(5)))
            dots.contentDescription = "${minOf(page + 1, count)} of $count"
        }
        scroller.setOnScrollChangeListener { _, x, _, _, _ ->
            val p = Math.round(x.toFloat() / scroller.step()).coerceIn(0, count - 1)
            if (p != page) { page = p; paintDots() }
        }
        pager = scroller
        holder.addView(scroller, Ui.matchWidth())
        if (count > 1) { paintDots(); holder.addView(dots, Ui.matchWidth()) }
        return holder
    }

    /**
     * ONE ROW, FOUR SHAPES — widget.tsx's WidgetRow.
     *
     *   hero     picture on top, full-width button — Gallery, Spotlight, two columns
     *   row      thumbnail left, pill button — Cards
     *   list     dense, the row is the button — List
     *   compact  no picture, outline button — Compact
     *
     * `picked` is null when choosing one; true/false when choosing several,
     * and then the ROW toggles — a per-row button beside a tick is two
     * controls for one decision.
     */
    private fun row(r: WidgetContent.Row, variant: String, look: WidgetLook, s: Skin, picked: Boolean?, onPick: () -> Unit): View {
        val many = picked != null
        val corner = s.inner
        val tight = look.density == "compact"
        val pad = dp((if (variant == "list") (if (tight) 6 else 9) else (if (tight) 7 else 10)) + s.grammar.padBoost)
        val gap = s.gap(tight)
        val rowIsButton = many || look.button == "row"
        val outline = look.button == "outline"
        val a11y = "${r.selectLabel}: ${r.title}"

        fun badge(): View? = if (r.subtitle.isEmpty()) null else LinearLayout(context).apply {
            addView(s.label(r.subtitle, 10.5f, s.accent, 800).apply {
                isAllCaps = true
                letterSpacing = 0.4f / 10.5f
                background = Ui.rounded(s.soft(0.15), s.chip)
                setPadding(dp(8), dp(3), dp(8), dp(3))
            }, Ui.wrap())
        }

        fun titleLine(big: Boolean): View = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.TOP
            Ui.gap(this, dp(8))
            addView(s.label(r.title, if (big) 16.5f else if (variant == "list") 13.5f else 14.5f, s.text, s.titleWeight, lines = 2), Ui.fill())
            if (r.price.isNotEmpty()) addView(s.label(r.price, if (big) 17f else if (variant == "list") 13.5f else 14.5f, s.text, 800).apply {
                fontFeatureSettings = "tnum"
            }, Ui.wrap())
        }

        // Facts as chips: joined with dots they read as the sentence a component exists to replace.
        fun facts(): View? = if (!look.facts || r.facts.isEmpty()) null else FlowRow(context, dp(6), dp(6)).apply {
            setPadding(0, dp(2), 0, 0)
            val small = variant == "list"
            for (f in if (small) r.facts.take(2) else r.facts) addView(s.label(f, if (small) 10.5f else 11.5f, s.muted, 600).apply {
                background = if (s.grammar.chipOutlined) Ui.rounded(Color.TRANSPARENT, s.fact, s.line, s.border)
                else Ui.rounded(s.borderTint(0.35), s.fact)
                setPadding(dp(if (small) 6 else 8), dp(if (small) 2 else 4), dp(if (small) 6 else 8), dp(if (small) 2 else 4))
            })
        }

        fun tick(): View = FrameLayout(context).apply {
            background = if (picked == true) Ui.rounded(s.accent, dp(11).toFloat()) else Ui.rounded(Color.TRANSPARENT, dp(11).toFloat(), if (s.tokens != null) s.line else dp(1.5f), s.border)
            if (picked == true) addView(s.label("✓", 12f, s.onAccent, 800).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        fun button(wide: Boolean): View = s.label(if (r.unavailable) "Unavailable" else r.selectLabel, if (wide) 14f else 12.5f,
            if (r.unavailable) s.muted else if (outline) s.accent else s.onAccent, 700).apply {
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(if (wide) 12 else 14), 0, dp(if (wide) 12 else 14), 0)
            val radius = s.button(pill = !wide)
            val main = s.mainButton
            background = if (r.unavailable || outline) Ui.rounded(Color.TRANSPARENT, radius, if (s.tokens != null) maxOf(s.line, dp(1)) else dp(1.5f), if (r.unavailable) s.border else s.accent)
            else if (main != null) {
                // The chat's main-button treatment: filled, outline, tonal or the words alone.
                setTextColor(Themed.color(main.text))
                if (main.strong) s.tokens?.let { Themed.face(this, it.font.body, it.font.weights.strong, graine) }
                when {
                    main.border != null -> Ui.rounded(Color.TRANSPARENT, radius, maxOf(s.line, dp(1)), Themed.color(main.border))
                    main.fill != null -> Ui.rounded(Themed.color(main.fill), radius)
                    else -> null
                }
            }
            else Ui.rounded(s.accent, radius)
            contentDescription = a11y
            isEnabled = !r.unavailable
            isClickable = !r.unavailable
            setOnClickListener { onPick() }
        }

        fun image(w: Int, h: Int): View? = if (r.image.isEmpty()) null
        else roundedImage(context, r.image, s.picture, s.border).also { it.layoutParams = LayoutParams(w, h) }

        /** The large picture on top: its own height, or the chat's card image shape — as tall as wide, 3:4 or 9:16 — always 100–260. */
        fun largeImage(ownDp: Int): View? = if (r.image.isEmpty()) null
        else if (s.imageShape == "auto") image(ViewGroup.LayoutParams.MATCH_PARENT, dp(ownDp))
        else ShapedImage(context, s.imageShape, ownDp.toDouble()).apply {
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            background = android.graphics.drawable.GradientDrawable().apply { setColor(s.border); cornerRadius = s.picture }
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            ImageLoader.load(this, r.image)
            layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        fun frame(v: LinearLayout, bordered: Boolean): View {
            if (bordered) v.background = Ui.rounded(Color.TRANSPARENT, corner, if (s.tokens == null) dp(if (picked == true) 2 else 1) else if (picked == true) s.line * 2 else s.line, if (picked == true) s.accent else s.border)
            if (r.unavailable) v.alpha = 0.55f
            if (rowIsButton) {
                v.contentDescription = a11y
                v.isEnabled = !r.unavailable
                v.isClickable = !r.unavailable
                v.setOnClickListener { onPick() }
            }
            return v
        }

        when (variant) {
            "hero" -> {
                val col = Ui.vertical(context, gap).apply { setPadding(pad, pad, pad, pad) }
                largeImage(mapOf("small" to 100, "medium" to 140, "large" to 180).getValue(look.imageSize))?.let { col.addView(it) }
                val words = Ui.vertical(context, dp(3))
                badge()?.let { words.addView(it) }
                words.addView(titleLine(true), Ui.matchWidth())
                facts()?.let { words.addView(it, Ui.matchWidth()) }
                if (!many && !rowIsButton) words.addView(button(true), Ui.matchWidth().apply { topMargin = dp(8) })
                col.addView(words, Ui.matchWidth())
                if (!many) return frame(col, true)
                val stack = FrameLayout(context)
                stack.addView(frame(col, true), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                stack.addView(tick(), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, pad + dp(6), pad + dp(6), 0) })
                return stack
            }
            "list" -> {
                val line = Ui.horizontal(context, gap).apply {
                    setPadding(dp(2), pad, dp(2), pad)
                    minimumHeight = dp(44)
                    background = Hairline(s.border, s.line)
                }
                val t = dp(mapOf("small" to 36, "medium" to 44, "large" to 56).getValue(look.imageSize))
                image(t, t)?.let { line.addView(it) }
                val words = Ui.vertical(context, dp(2))
                words.addView(titleLine(false), Ui.matchWidth())
                facts()?.let { words.addView(it, Ui.matchWidth()) }
                line.addView(words, Ui.fill())
                when {
                    many -> line.addView(tick(), LayoutParams(dp(22), dp(22)))
                    rowIsButton -> line.addView(s.label("›", 22f, s.muted, 600).apply { setPadding(dp(4), 0, 0, 0) })
                    else -> line.addView(button(false))
                }
                val framed = frame(line, false)
                // Where the platform insets its separators (Apple's), the whole row moves in with them.
                return if (s.grammar.separatorInset <= 0) framed else FrameLayout(context).apply {
                    setPadding(dp(s.grammar.separatorInset), 0, 0, 0)
                    addView(framed)
                }
            }
            "compact" -> {
                val line = Ui.horizontal(context, gap).apply { setPadding(pad + dp(2), pad, pad + dp(2), pad); minimumHeight = dp(44) }
                val words = Ui.vertical(context, dp(2))
                badge()?.let { words.addView(it) }
                words.addView(titleLine(false), Ui.matchWidth())
                facts()?.let { words.addView(it, Ui.matchWidth()) }
                line.addView(words, Ui.fill())
                if (many) line.addView(tick(), LayoutParams(dp(22), dp(22))) else if (!rowIsButton) line.addView(button(false))
                return frame(line, true)
            }
            else -> {
                val line = Ui.horizontal(context, gap).apply { setPadding(pad, pad, pad, pad) }
                val t = dp(mapOf("small" to 56, "medium" to 72, "large" to 96).getValue(look.imageSize))
                image(t, t)?.let { line.addView(it) }
                val words = Ui.vertical(context, dp(3))
                badge()?.let { words.addView(it) }
                words.addView(titleLine(false), Ui.matchWidth())
                facts()?.let { words.addView(it, Ui.matchWidth()) }
                line.addView(words, Ui.fill())
                if (many) line.addView(tick(), LayoutParams(dp(22), dp(22))) else if (!rowIsButton) line.addView(button(false))
                return frame(line, true)
            }
        }
    }

    // ── the dialog that carries a screen or a sheet ────────────────────────

    private fun showOverlay(next: Pair<String, View>?, s: Skin) {
        if (next == null) { closeDialog(); return }
        val (surface, view) = next
        val activity = Ui.activity(context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        var d = dialog
        if (d == null || dialogSurface != surface) {
            closeDialog()
            d = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar).apply {
                setCancelable(true)
                // Back is "close": the same as the × and "None of these".
                setOnCancelListener { dismiss() }
                window?.let { configureWindow(it) }
            }
            dialog = d
            dialogSurface = surface
        }
        d.window?.let { w ->
            // No motion in the design, or animations off on the phone: it is simply there.
            if (s.tokens != null) w.setWindowAnimations(if (Themed.motionMs(context, s.tokens) > 0) android.R.style.Animation_InputMethod else 0)
            // Glass: what is behind the sheet or screen blurred, where the phone can.
            Themed.blurBehind(w, s.tokens?.glass)
        }
        d.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        if (!d.isShowing) runCatching { d.show() }
        d.window?.let { systemBarIcons(it, lightIcons = surface == "sheet" || s.dark) }
        view.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun configureWindow(w: Window) {
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setWindowAnimations(android.R.style.Animation_InputMethod)
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.statusBarColor = Color.TRANSPARENT
        w.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) w.setDecorFitsSystemWindows(false)
        else w.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    @Suppress("DEPRECATION")
    private fun systemBarIcons(w: Window, lightIcons: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            val dark = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            w.insetsController?.setSystemBarsAppearance(if (lightIcons) 0 else dark, dark)
        } else if (Build.VERSION.SDK_INT >= 23) {
            val v = w.decorView
            v.systemUiVisibility = if (lightIcons) v.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            else v.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun closeDialog() {
        dialog?.let { it.setOnCancelListener(null); runCatching { it.dismiss() } }
        dialog = null
        dialogSurface = null
    }
}

/** A 1px line along the bottom edge — a list row's separator. */
/** A picture as tall as its width says ([ChatLook.imageHeight]): square, 3:4 or 9:16, never under 100 or over 260. */
private class ShapedImage(context: Context, private val shape: String, private val ownDp: Double) : android.widget.ImageView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val d = resources.displayMetrics.density
        val h = Math.round(ChatLook.imageHeight(shape, w / d.toDouble(), ownDp) * d).toInt()
        setMeasuredDimension(w, h)
    }
}

private class Hairline(color: Int, private val thickness: Int) : android.graphics.drawable.Drawable() {
    private val paint = android.graphics.Paint().apply { this.color = color }
    override fun draw(canvas: android.graphics.Canvas) {
        val b = bounds
        canvas.drawRect(b.left.toFloat(), (b.bottom - thickness).toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
}
