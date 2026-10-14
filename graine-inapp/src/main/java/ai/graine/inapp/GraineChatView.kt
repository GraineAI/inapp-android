package ai.graine.inapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import org.json.JSONObject

/**
 * THE CHAT, AS A WHOLE SCREEN — the text conversation with the agent, drawn
 * by the SDK in the brand's design (the chat window's tokens: header, bubbles,
 * composer, suggestions), with the agent's components right under the line
 * that asked for them. Drop it into any layout, or open [GraineChatActivity].
 *
 *     setContentView(GraineChatView(this).apply { onClose = { finish() } })
 *     // Another of the app's agents (GraineInApp.init(…, name = "support")):
 *     GraineChatView(this).apply { source = GraineInApp.get("support").chat }
 *
 * It opens the conversation when it comes on screen and lets it go a moment
 * after it leaves ([GraineChat]); lines typed before it connects are sent
 * when it does. Plain platform views: no AndroidX, no Compose. Main thread.
 */
class GraineChatView internal constructor(
    context: Context,
    attrs: AttributeSet?,
    /** The bar's full screen: the chevron, pointing down, leads the header instead of a close at its end. */
    private val leadingChevron: Boolean,
) : FrameLayout(context, attrs), GraineListener {

    @JvmOverloads
    constructor(context: Context, attrs: AttributeSet? = null) : this(context, attrs, false)

    /** The agent drawn when the source is not one agent's chat: the app's default. */
    private val home = GraineInApp.primary()

    /** The agent drawn — in its design, with its components: the source's own, else the default. */
    private val graine: GraineInApp get() = (source as? GraineChat)?.graine ?: home

    /**
     * The conversation drawn: the default agent's chat unless set — another
     * agent's (`GraineInApp.get("support").chat`), drawn in that agent's
     * design, or one the app runs itself.
     */
    var source: ChatSource = home.chat
        set(v) {
            if (field === v) return
            val before = graine
            field.removeChangeListener(watcher)
            if (onScreen) field.shown(false)
            onScreen = false
            field = v
            if (graine !== before) follow(before)
            if (isAttachedToWindow) v.addChangeListener(watcher)
            render()
        }

    /** "light", "dark" or "auto" (the device). Default: the design's scheme. */
    var scheme: String? = null
        set(v) { field = v; widgets.scheme = v; render() }

    /** The close button was pressed. Unset, it does nothing: the screen is the app's to leave. */
    var onClose: (() -> Unit)? = null

    /** The components the agent draws in the chat, between the messages and the composer. */
    val widgetView: GraineWidgetView = GraineWidgetView(context)

    private val widgets get() = widgetView
    private val window = ChatWindowView(context, graine, if (leadingChevron) ChatFrame.SCREEN else ChatFrame.PAGE)
    private val watcher = Runnable { if (isAttachedToWindow) render() }
    private var unwatch: (() -> Unit)? = null
    private var onScreen = false

    init {
        addView(window, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        window.setAccessory(widgetView)
        window.onSend = { text -> source.send(text) }
        window.onCollapse = { onClose?.invoke() }
        window.onRetry = { (source as? GraineChat)?.connect() ?: run { source.shown(false); source.shown(true) } }
        window.onCancelCall = { id -> (source as? GraineChat)?.cancelUpcomingCall(id) }
        // A refusal leaves the conversation as it was; nothing to say over it.
        window.onNewConversation = { (source as? GraineChat)?.newConversation() }
        render()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        graine.addListener(this)
        source.addChangeListener(watcher)
        unwatch = graine.onDesignChange { render() }
        render()
    }

    /** The source is another agent's chat: its listener, design and components, not [before]'s. */
    private fun follow(before: GraineInApp) {
        val now = graine
        window.graine = now
        widgetView.graine = now
        if (!isAttachedToWindow) return
        before.removeListener(this)
        unwatch?.invoke()
        now.addListener(this)
        unwatch = now.onDesignChange { render() }
    }

    override fun onDetachedFromWindow() {
        graine.removeListener(this)
        source.removeChangeListener(watcher)
        unwatch?.invoke()
        unwatch = null
        show(false)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) render()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        render()
    }

    override fun onSessionReady(session: SessionConfig) = render()
    override fun onChatState(connected: Boolean, connecting: Boolean) = render()

    /** On screen it connects; off every screen the conversation is let go a moment later. */
    private fun show(on: Boolean) {
        if (on == onScreen) return
        onScreen = on
        source.shown(on)
    }

    /** The design's tokens for now: the brand's, else Graine Classic in the dashboard's light or dark. */
    private fun tokens(): ThemeTokens {
        val appearance: JSONObject? = graine.appearance
        return graine.themeTokens(context, scheme)
            ?: if (Appearance.isDark(scheme ?: Js.strOrEmpty(appearance?.opt("theme")).ifEmpty { "auto" }, Ui.isDeviceDark(context)))
                Design.CLASSIC_DARK else Design.CLASSIC_LIGHT
    }

    private fun render() {
        val t = tokens()
        val appearance = graine.appearance
        val dash = Appearance.resolve(appearance, Appearance.DEFAULT_ACCENT, "Assistant", origin = graine.config.baseUrl)
        val designed = graine.themeTokens(context, scheme)
        val accentHex = designed?.colors?.accent ?: dash.accent
        val accent = Appearance.argb(accentHex) ?: Color.parseColor(Appearance.DEFAULT_ACCENT)
        val onAccent = Themed.color(if (designed != null) t.colors.onAccent else Themed.readableOnHex(accentHex), Color.WHITE)
        val loader = Loaders.resolve(appearance?.opt("loader"))
        val loaderColor = Appearance.argb(loader.color) ?: accent
        val s = source
        window.render(t, accent, onAccent, dash.heading, null, dash.logoUrl, dash.logoPlate, s.messages, s.online,
            replying = s.typing, failed = s.failed, loader = loader, loaderColor = loaderColor,
            upcoming = (s as? GraineChat)?.upcomingRows() ?: emptyList(), callName = graine.schedulingRules.callName ?: dash.heading)
        show(isAttachedToWindow && isShown)
    }
}

/**
 * THE CHAT ON A SCREEN OF ITS OWN — [GraineChatView] full screen, in the
 * brand's design, above the keyboard, closed by its close button or Back.
 * Declared in the SDK's manifest; nothing for the app to add.
 *
 *     GraineChatActivity.open(context)
 *     GraineChatActivity.open(context, agent = "support")   // another of the app's agents
 */
class GraineChatActivity : Activity() {
    private var view: GraineChatView? = null
    private var graine: GraineInApp? = null
    /** Opened by the bar for a full-screen chat: the chevron leads, and closing it is the bar's chevron. */
    private var fromBar = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val named = intent?.getStringExtra(GraineCalls.EXTRA_AGENT)?.let { AppShared.key(it) }
        val g = named?.let { GraineInApp.find(it) } ?: GraineInApp.peek()
        if (g == null) { finish(); return }
        if (named != null && named != GraineInApp.DEFAULT && named != g.name) {
            android.util.Log.w(GraineInApp.TAG, "No agent is named \"$named\": the chat opens with the \"${g.name}\" agent. GraineInApp.init(application, config, name = \"$named\") in Application.onCreate.")
        }
        graine = g
        fromBar = intent?.getBooleanExtra(EXTRA_FROM_BAR, false) == true
        val chat = GraineChatView(this, null, leadingChevron = fromBar).apply { source = g.chat; onClose = { finish() } }
        view = chat
        // Edge to edge or not, the conversation sits between the system bars and above the keyboard.
        chat.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        setContentView(chat)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        paintBars()
    }

    override fun onResume() {
        super.onResume()
        paintBars()
    }

    override fun onDestroy() {
        // Closed for good (not turned): the bar that opened it does what its chevron does.
        if (fromBar && isFinishing) graine?.chatScreenClosed()
        super.onDestroy()
    }

    /** The system bars in the chat's own colours, their icons readable on them. */
    private fun paintBars() {
        val g = graine ?: return
        val t = g.themeTokens(this) ?: if (Ui.isDeviceDark(this)) Design.CLASSIC_DARK else Design.CLASSIC_LIGHT
        val chat = Surfaces.chatOf(t)
        window.decorView.setBackgroundColor(Themed.color(chat.background))
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Themed.color(chat.headerBackground)
            window.navigationBarColor = Themed.color(chat.background)
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val light = Appearance.luminance(chat.headerBackground) >= 0.4
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        }
    }

    companion object {
        /**
         * Open the chat screen — with [agent], for an app that runs several
         * (GraineInApp.init's name); the default one unless named. From
         * anything with a context; a non-activity one starts it in a new task.
         */
        @JvmStatic
        @JvmOverloads
        fun open(context: Context, agent: String = GraineInApp.DEFAULT) {
            val i = Intent(context, GraineChatActivity::class.java).putExtra(GraineCalls.EXTRA_AGENT, agent)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }

        /** The bar's full-screen chat (Window size: Full screen): the chevron leads the header. */
        internal const val EXTRA_FROM_BAR = "ai.graine.inapp.CHAT_FROM_BAR"

        internal fun openFromBar(context: Context, agent: String) {
            val i = Intent(context, GraineChatActivity::class.java)
                .putExtra(GraineCalls.EXTRA_AGENT, agent)
                .putExtra(EXTRA_FROM_BAR, true)
            if (context !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
    }
}
