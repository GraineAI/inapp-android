package ai.graine.inapp

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors

/** Everything the SDK needs to know up front. */
data class GraineConfig @JvmOverloads constructor(
    /** "https://www.graine.ai" unless self-hosted. */
    val baseUrl: String,
    /**
     * From Agent → Embed & Widgets. Selects the agent. It is not a secret and
     * cannot place a call on its own — add `app://` to that agent's Allowed
     * domains, or every request is refused.
     */
    val publishableKey: String,
    /** Prompt {variables} known at build time. identify() and setVariables() win over these. */
    val variables: Map<String, Any?> = emptyMap(),
    /** Force a transport. Null (the default) lets the dashboard's Call audio setting decide. */
    val transport: Transport? = null,
    /** Lands on the call record, to tell builds apart. */
    val appVersion: String? = null,
    /** End a live call when the app goes to the background. On by default; you want it on. */
    val endOnBackground: Boolean = true,
    /** Load timing, for a field app on a very slow network. */
    val timing: Timing = Timing(),
    /**
     * Log every message across the bridge, both ways, and the engine page's
     * own console, to logcat under the tag "Graine". Off by default; turn it
     * on in debug builds — "the agent did not speak" is answered in one read
     * of this log, and unanswerable without it.
     */
    val debug: Boolean = false,
    /**
     * This app's friction rules, over the dashboard's (Embed & Widgets →
     * friction). Most apps set nothing here; pass it for the screens only the
     * app knows about — a terms page nobody is stuck on, an OTP step where
     * twenty seconds is already long. See [FrictionConfig].
     */
    val friction: FrictionConfig? = null,
    /**
     * Optional: fonts this app already bundles, by the family names the
     * dashboard offers — `mapOf("Inter" to Typeface.createFromAsset(assets,
     * "fonts/Inter.ttf"))`. A family mapped here is drawn from the app's file
     * and never downloaded. Every other family the design uses the SDK fetches
     * from Graine by itself, keeps on disk and draws once it arrives; until
     * then, and if it never does, the system font, silently.
     */
    val fonts: Map<String, Typeface> = emptyMap(),
) {
    data class Timing @JvmOverloads constructor(
        val stallMs: Long = 15_000,
        val bootMs: Long = 15_000,
        val hardCapMs: Long = 120_000,
        val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 15_000),
        val endWatchdogMs: Long = 4_000,
        /**
         * A connect the customer has waited on this long says "Still
         * connecting…" ([GraineVoice.slowConnect]). Past the point a healthy
         * connect finishes, well inside the ten seconds after which, without a
         * word, people decide it is broken.
         */
        val slowConnectMs: Long = 4_000,
    )
}

/** What the dashboard says about this agent, fetched once per launch. */
data class SessionConfig(
    val agentId: String,
    val webcallAgentId: String,
    val appearance: JSONObject,
    val variables: Map<String, Any?>,
    val pageVersion: String?,
    val declaredActions: Set<String>,
    /** The dashboard's friction rules for this agent, as served. Applied under [GraineConfig.friction]. */
    val friction: JSONObject? = null,
    /**
     * The design the brand chose, resolved by the server into light and dark
     * tokens. Null from a server that predates designs: [appearance] then
     * draws everything, as it always did.
     */
    val design: ResolvedDesign? = null,
    /**
     * Where this agent's voice socket lives (`wss://…`), when the server says;
     * null means Graine's default. A native call's direct socket uses it.
     */
    val wsHost: String? = null,
) {
    /** The dashboard's Call audio setting. */
    val preferredTransport: Transport
        get() = if (appearance.optString("audioTransport") == "rtc") Transport.WEBRTC else Transport.WEBSOCKET

    val accent: String? get() = appearance.optString("accent").ifEmpty { null }
}

/** Whether the page that carries the call is actually there. */
enum class Connectivity { LOADING, READY, RETRYING, FAILED }

/** Everything the SDK reports. Every method is optional; override what you draw. */
interface GraineListener {
    fun onSessionReady(session: SessionConfig) {}
    fun onCallState(connected: Boolean, connecting: Boolean) {}
    fun onCaption(role: Role, text: String, live: Boolean) {}
    /** The agent drew a component. Answer it with GraineVoice.answerWidget. */
    fun onWidget(widget: JSONObject) {}
    /** A component is on its way; its content is loading. Draw your loader. */
    fun onWidgetPending(hint: JSONObject) {}
    fun onEnded() {}
    fun onMicDenied(reason: String) {}
    /** One word for the connection. Fires only when the word changes. */
    fun onQuality(quality: ConnectionQuality) {}
    fun onNetwork(online: Boolean) {}
    /** The network interrupted the call and it is being re-established. Not an end. */
    fun onReconnecting(active: Boolean) {}
    /**
     * Where a call being placed is ([GraineVoice.connectStage]), whether the
     * customer has waited on it long enough to say so ([GraineVoice.slowConnect]),
     * and whether it could not be connected at all ([GraineVoice.callFailed]).
     * Fires when any of the three changes.
     */
    fun onConnectProgress(stage: ConnectStage?, slow: Boolean, failed: Boolean) {}
    fun onTransport(transport: Transport, requested: Transport, reason: String?, message: String?) {}
    fun onConnectivity(state: Connectivity, attempt: Int, reason: String?) {}
    fun onActionRequested(name: String) {}
    fun onActionCompleted(name: String, result: ActionResult) {}
    /**
     * The customer looks stuck (stalled_on_step): on one screen, with a field
     * outstanding, through a stretch of a live call. The agent has already
     * been told; this is for the app — badge a control, count it.
     */
    fun onFriction(signal: FrictionSignal) {}
    /** Loud for you, invisible to the customer: a misconfigured agent is never the customer's problem. */
    fun onError(message: String) {}
}

/**
 * THE GRAINE IN-APP AGENT, FOR NATIVE ANDROID.
 *
 *     val graine = GraineInApp.init(application, GraineConfig(
 *         baseUrl = "https://www.graine.ai",
 *         publishableKey = "pk_live_…",
 *     ))
 *     graine.identify(mapOf("name" to user.name))
 *     graine.setScreen(GraineScreen("kyc_documents", fields = listOf(…)))
 *     graine.registerAction("set_tenure") { args -> ActionResult.ok("Tenure set") }
 *     graine.voice.attach(activity)          // loads the engine, idle
 *     graine.voice.start()                   // places the call
 *
 * One instance per process. Everything that touches a view runs on the main
 * thread; everything that touches the network does not.
 */
class GraineInApp private constructor(
    internal val application: Application,
    val config: GraineConfig,
) {
    internal val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "graine-io").apply { isDaemon = true } }
    internal val listeners = CopyOnWriteArraySet<GraineListener>()

    internal val variables = VariableStore(config.variables)
    private val variableWatchers = CopyOnWriteArraySet<(Map<String, String>) -> Unit>()

    private val actions = ActionRegistry(
        post = { task -> main.post { task() } },
        schedule = { delayMs, task -> val r = Runnable { task() }; main.postDelayed(r, delayMs); ({ main.removeCallbacks(r) }) },
        requested = { name -> listeners.forEach { it.onActionRequested(name) } },
        completed = { name, result -> listeners.forEach { it.onActionCompleted(name, result) } },
    )
    @Volatile private var screen: GraineScreen? = null
    @Volatile private var screenSince = System.currentTimeMillis()
    private val recentEvents = ArrayDeque<JSONObject>()
    /** Read from the main thread and a native call's session thread alike. */
    private val contextSeq = java.util.concurrent.atomic.AtomicInteger()
    private val contextWatchers = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    private var contextPending: Runnable? = null

    @Volatile var session: SessionConfig? = null
        private set
    private val sessionWaiters = mutableListOf<(SessionConfig?) -> Unit>()
    private var sessionLoading = false

    // Declared before `voice`: a delegate is read when the property is initialised.
    private val voiceLazy = lazy { GraineVoice(this) }

    /** The voice call. Attach it once, from your first activity; it follows the customer from there. */
    val voice: GraineVoice by voiceLazy

    private val callsLazy = lazy { GraineCalls(this) }

    /**
     * Native calls, like WhatsApp: the agent rings the customer (a push → the
     * call notification), or the customer calls the agent — with the screen
     * off, from the lock screen, outcomes back to the app. See [GraineCalls].
     */
    val calls: GraineCalls by callsLazy

    /** A native call takes the line: any web view call ends first. */
    internal fun endWebViewCall() {
        if (voiceLazy.isInitialized() && (voice.connected || voice.connecting)) voice.end()
    }

    /** True while a native call is ringing, connecting or live — the web view engine must not start one too. */
    internal fun nativeCallLive(): Boolean = callsLazy.isInitialized() && calls.state != GraineCallState.IDLE

    /**
     * The component the agent drew that is still waiting to be answered, as
     * the page sent it — retained because the view that draws it may not be
     * mounted when it arrives (a panel that is closed), and an event has no
     * replay. GraineWidgetView picks it up on attach. Cleared when it is
     * answered or dismissed, and when a new call starts.
     */
    @Volatile var pendingWidget: JSONObject? = null
        private set

    /** Forget the retained component — answered, dismissed, or from a call that is over. */
    fun clearPendingWidget() { pendingWidget = null }

    internal fun retainWidget(widget: JSONObject) { pendingWidget = widget }

    fun addListener(l: GraineListener) { listeners.add(l) }
    fun removeListener(l: GraineListener) { listeners.remove(l) }

    // ── who, and what they are called ───────────────────────────────────────

    /** Who is signed in. Traits become prompt variables and land on the call record. Null on sign-out. */
    fun identify(traits: Map<String, Any?>?) {
        variables.setIdentity(traits)
        announceVariables()
        scheduleContext()
    }

    /**
     * Prompt variables from the app's own data, while it runs. Merged, not
     * replaced; null under a key removes it. Reaches the next call, and a
     * live engine page re-mints with it.
     */
    fun setVariables(values: Map<String, Any?>?) {
        variables.setRuntime(values)
        announceVariables()
    }

    /** Every variable the agent will receive, in precedence order. */
    fun currentVariables(): Map<String, String> = variables.merged()

    internal fun onVariables(watch: (Map<String, String>) -> Unit): () -> Unit {
        variableWatchers.add(watch); return { variableWatchers.remove(watch) }
    }

    private fun announceVariables() {
        val merged = variables.merged()
        main.post { variableWatchers.forEach { it(merged) } }
    }

    // ── what is on the screen ───────────────────────────────────────────────

    /**
     * What the customer is looking at. Call on every meaningful change; an
     * unchanged screen is not resent, and bursts are coalesced.
     */
    fun setScreen(next: GraineScreen?) {
        if (next == screen) return
        if (next?.screen != screen?.screen) screenSince = System.currentTimeMillis()
        screen = next
        scheduleContext()
        val now = android.os.SystemClock.uptimeMillis()
        main.post { friction.onScreen(next, now); armStall() }
    }

    // ── friction: noticing the customer is stuck ────────────────────────────

    /**
     * Idle friction, the React Native SDK's `stalled_on_step`: the customer
     * has been on one screen, with a field outstanding, for the configured
     * time (45s by default) during a live call — once per screen visit. The
     * agent hears it with a sentence naming the field; the app hears
     * [GraineListener.onFriction]. Main-thread state; the clock is uptime.
     */
    private val friction = FrictionDetector(Friction.resolve(config.friction), gateOnCall = true)
    private val stallCheck = Runnable { onStall() }

    /** Replace the app's layer of friction rules. Safe at any time. */
    fun configureFriction(override: FrictionConfig?) {
        main.post {
            friction.configure(Friction.resolve(FrictionConfig.fromJson(session?.friction), override))
            armStall()
        }
    }

    /** The rules in force right now — defaults, the dashboard's, the app's. */
    val frictionRules: FrictionRules get() = friction.rules

    /**
     * The customer did something that is not a field edit — scrolled a list,
     * opened a picker, sent a message. The idle clock restarts. Field edits do
     * NOT count: a customer typing one character every forty seconds is
     * exactly who is stuck.
     */
    fun noteInteraction() {
        val now = android.os.SystemClock.uptimeMillis()
        main.post { friction.onInteraction(now); armStall() }
    }

    internal fun callStateChanged(live: Boolean) {
        friction.onCall(live, android.os.SystemClock.uptimeMillis())
        armStall()
    }

    private fun armStall() {
        main.removeCallbacks(stallCheck)
        val due = friction.idleDueIn(android.os.SystemClock.uptimeMillis()) ?: return
        main.postDelayed(stallCheck, due)
    }

    private fun onStall() {
        val signal = friction.onIdle(android.os.SystemClock.uptimeMillis()) ?: run { armStall(); return }
        listeners.forEach { l -> runCatching { l.onFriction(signal) } }
        voice.reportEvent(signal.name, signal.detail, mapOf(
            "cooldown_seconds" to signal.cooldownSeconds,
            "max_per_session" to signal.maxPerSession,
        ))
    }

    /** A product event that informs without interrupting. Rides the next context frame. */
    @JvmOverloads
    fun track(name: String, data: Map<String, Any?> = emptyMap()) {
        val e = JSONObject().put("name", name).put("at", System.currentTimeMillis())
        if (data.isNotEmpty()) e.put("data", Protocol.toJson(data))
        synchronized(recentEvents) {
            recentEvents.addLast(e)
            while (recentEvents.size > 10) recentEvents.removeFirst()
        }
        scheduleContext()
    }

    internal fun onContext(watch: (JSONObject) -> Unit): () -> Unit {
        contextWatchers.add(watch); return { contextWatchers.remove(watch) }
    }

    /** The current frame, for a transport that has just come up. */
    internal fun currentContextFrame(): JSONObject? {
        val events = synchronized(recentEvents) { recentEvents.toList() }
        val frame = ContextFrame.build(screen, screenSince, System.currentTimeMillis(),
            variables.identity(), config.appVersion, events) ?: return null
        val available = screen?.availableActions ?: synchronized(actions) { actions.keys.toList() }
        if (!frame.has("availableActions") && available.isNotEmpty()) frame.put("availableActions", org.json.JSONArray(available))
        return frame.put("seq", contextSeq.incrementAndGet())
    }

    /** Coalesced: a screen that updates on every keystroke is sent at most every 400ms. */
    private fun scheduleContext() {
        main.post {
            contextPending?.let { main.removeCallbacks(it) }
            val r = Runnable {
                contextPending = null
                val frame = currentContextFrame() ?: return@Runnable
                contextWatchers.forEach { it(frame) }
            }
            contextPending = r
            main.postDelayed(r, 400)
        }
    }

    // ── what the agent may do ───────────────────────────────────────────────

    /**
     * Something the agent can do in your app. Declare it on the agent in the
     * dashboard too — a handler with no declaration is never offered, and a
     * declaration with no handler is refused mid-sentence.
     *
     * The handler runs on the main thread (it will usually touch UI) and must
     * answer within six seconds, or the agent is told it was not done.
     * Returns a function that unregisters it.
     */
    fun registerAction(name: String, handler: (JSONObject) -> ActionResult): () -> Unit =
        register(name, ActionHandler.Sync(handler))

    /**
     * [registerAction] for an answer that is not ready when the handler
     * returns: a network call, a confirmation the customer taps, code in
     * another runtime (the React Native and Flutter call packages answer from
     * JavaScript and Dart this way). The handler runs on the main thread and
     * calls `answer` once, from any thread. The same six seconds apply: after
     * them the agent has already been told it was not done, and a late answer
     * is dropped — as is every answer after the first.
     * Returns a function that unregisters it.
     */
    fun registerDeferredAction(name: String, handler: (args: JSONObject, answer: (ActionResult) -> Unit) -> Unit): () -> Unit =
        register(name, ActionHandler.Deferred(handler))

    private fun register(name: String, handler: ActionHandler): () -> Unit {
        val unregister = actions.register(name, handler)
        scheduleContext()
        return { unregister(); scheduleContext() }
    }

    /** Which registered actions the agent can call, and which it cannot. Log this once at startup. */
    fun checkActions(): Map<String, List<String>> {
        val registered = synchronized(actions) { actions.keys.toSet() }
        val declared = session?.declaredActions ?: emptySet()
        return mapOf(
            "usable" to registered.filter { it in declared },
            "undeclared" to registered.filter { it !in declared },
            "unhandled" to declared.filter { it !in registered },
        )
    }

    /**
     * Run one action for the agent, on the main thread, against the deadline.
     * Always answers — a handler that throws is `error`, a slow one is `error`
     * with a timeout message, an unknown one is `refused`. The agent is
     * blocked waiting, so silence is the one answer never given.
     */
    internal fun executeAction(name: String, args: JSONObject, answer: (ActionResult) -> Unit) =
        actions.execute(name, args, answer)

    /** A handler as registered: one that answers when it returns, or one that answers later. */
    internal sealed class ActionHandler {
        class Sync(val run: (JSONObject) -> ActionResult) : ActionHandler()
        class Deferred(val run: (JSONObject, (ActionResult) -> Unit) -> Unit) : ActionHandler()
    }

    /**
     * EVERY ACTION, AND THE ONE PATH THAT RUNS THEM. Both kinds of handler are
     * held to the same rules, and the rules are pure — the main thread and its
     * clock are handed in — so DeferredActionTest proves them on the JVM: the
     * handler runs on the main thread; the agent hears exactly one answer (the
     * handler's, the deadline's, or a refusal for a name nobody registered)
     * and whatever comes second is dropped, wherever it comes from; the
     * listeners hear the request and that one answer.
     */
    internal class ActionRegistry(
        /** Runs a task on the main thread, after whatever is running now. */
        private val post: (() -> Unit) -> Unit,
        /** Runs a task on the main thread after a delay; returns what cancels it. */
        private val schedule: (Long, () -> Unit) -> (() -> Unit),
        private val requested: (String) -> Unit,
        private val completed: (String, ActionResult) -> Unit,
        private val deadlineMs: Long = Protocol.ACTION_DEADLINE_MS,
    ) {
        private val handlers = LinkedHashMap<String, ActionHandler>()

        /** The names registered now. */
        val keys: Set<String> get() = synchronized(handlers) { LinkedHashSet(handlers.keys) }

        /** Registers [handler] under [name], replacing any other. What it returns unregisters this one only. */
        fun register(name: String, handler: ActionHandler): () -> Unit {
            synchronized(handlers) { handlers[name] = handler }
            return { synchronized(handlers) { if (handlers[name] === handler) handlers.remove(name) } }
        }

        /** Called on the main thread. Always answers, exactly once. */
        fun execute(name: String, args: JSONObject, answer: (ActionResult) -> Unit) {
            requested(name)
            val handler = synchronized(handlers) { handlers[name] }
            var answered = false
            var cancelDeadline: () -> Unit = {}
            // Every answer arrives here on the main thread, so the flag needs no lock.
            fun once(r: ActionResult) {
                if (answered) return
                answered = true
                cancelDeadline()
                completed(name, r)
                answer(r)
            }
            if (handler == null) {
                once(ActionResult.refused("This app build does not implement \"$name\"."))
                return
            }
            cancelDeadline = schedule(deadlineMs) { once(ActionResult.error("The app did not answer within ${deadlineMs / 1000} seconds.")) }
            post {
                when (handler) {
                    is ActionHandler.Sync -> once(try { handler.run(args) } catch (e: Throwable) { failure(e) })
                    // A deferred answer is always posted, even from the main thread, and so is the
                    // handler's own failure: whichever came first is the one the agent hears.
                    is ActionHandler.Deferred -> try {
                        handler.run(args) { r -> post { once(r) } }
                    } catch (e: Throwable) {
                        post { once(failure(e)) }
                    }
                }
            }
        }

        private fun failure(e: Throwable) = ActionResult.error(e.message ?: e.javaClass.simpleName)
    }

    // ── the design: how the agent looks ─────────────────────────────────────

    @Volatile private var designPreview: ResolvedDesign? = null
    private val designWatchers = CopyOnWriteArraySet<() -> Unit>()

    /**
     * The design being drawn: a preview when one is set, else the session's.
     * Null when the server sent none — the views then draw from the
     * appearance, exactly as before designs existed.
     */
    val design: ResolvedDesign? get() = designPreview ?: session?.design

    /**
     * The tokens to draw in `context` right now: the brand's fixed light or
     * dark, else whichever the phone is in (the context's own, so an activity
     * that forces night mode gets dark). `scheme` — "light", "dark" or "auto"
     * — is the host's own choice and wins, as the views' `scheme` does. Null
     * without a design. For an app drawing its own UI in the brand's design.
     */
    @JvmOverloads
    fun themeTokens(context: Context? = null, scheme: String? = null): ThemeTokens? =
        design?.pick(Ui.isDeviceDark(context ?: application), scheme)

    /**
     * Draw this design instead of the session's — a preview, a screenshot, a
     * test of how a design looks before the brand saves it. Null goes back to
     * the session's. Every SDK view redraws.
     */
    fun previewDesign(design: ResolvedDesign?) {
        designPreview = design
        main.post { loadFaces() }
        announceDesign()
    }

    /** A design family's font, if the app mapped one in [GraineConfig.fonts]; null means the system font. */
    internal fun typeface(family: String?): Typeface? = Design.fontFor(config.fonts, family)

    /**
     * The design's typefaces, fetched by the SDK itself (contract/DESIGN.md →
     * Fonts). Each face that arrives redraws every SDK view, exactly as a
     * design change does. Kept in the app's cache directory, by URL.
     */
    private val fontStore by lazy {
        FontStore(java.io.File(application.cacheDir, "graine-fonts"), config.debug) { announceDesign() }
    }

    /** The face the SDK loaded for a design family at a weight; null means the system font (for now, or for good). */
    internal fun loadedFace(family: String?, weight: Int): Typeface? = fontStore.face(family, weight)

    /** Fetch the faces the design being drawn uses — in light and in dark — except families the app bundles. Main thread. */
    private fun loadFaces() {
        val d = design ?: return
        fontStore.want(Fonts.facesForDesign(d, config.baseUrl).filter { typeface(it.family) == null })
    }

    /** Called on the main thread whenever what the views draw may have changed: a preview, the phone going dark. */
    internal fun onDesignChange(watch: () -> Unit): () -> Unit {
        designWatchers.add(watch); return { designWatchers.remove(watch) }
    }

    private fun announceDesign() {
        main.post { designWatchers.forEach { runCatching { it() } } }
    }

    // ── the session ─────────────────────────────────────────────────────────

    /** Fetch the agent's configuration once; later callers share the answer. */
    internal fun loadSession(done: (SessionConfig?) -> Unit) {
        session?.let { done(it); return }
        synchronized(sessionWaiters) {
            sessionWaiters.add(done)
            if (sessionLoading) return
            sessionLoading = true
        }
        io.execute {
            val result = runCatching { fetchSession() }
            main.post {
                val s = result.getOrNull()
                if (s != null) {
                    session = s
                    // The typefaces first: they take a round trip, and everything below draws.
                    loadFaces()
                    variables.setDashboardDefaults(s.variables)
                    // The dashboard's friction rules, under the app's own.
                    friction.configure(Friction.resolve(FrictionConfig.fromJson(s.friction), config.friction))
                    armStall()
                    listeners.forEach { it.onSessionReady(s) }
                    val c = checkActions()
                    if (c.getValue("unhandled").isNotEmpty()) {
                        android.util.Log.w(TAG, "The agent declares actions this build does not implement: ${c["unhandled"]}. They will be refused mid-sentence.")
                    }
                } else {
                    val msg = result.exceptionOrNull()?.message ?: "Could not load the agent."
                    android.util.Log.e(TAG, msg)
                    listeners.forEach { it.onError(msg) }
                }
                val waiting = synchronized(sessionWaiters) { sessionLoading = false; sessionWaiters.toList().also { sessionWaiters.clear() } }
                waiting.forEach { it(s) }
            }
        }
    }

    private fun fetchSession(): SessionConfig = SessionFetch.fetch(config.baseUrl, config.publishableKey)

    // ── the app's lifecycle ─────────────────────────────────────────────────

    private var started = 0
    internal var foreground = true
        private set
    internal val foregroundWatchers = CopyOnWriteArraySet<(Boolean) -> Unit>()

    private var nightMode = application.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    private fun watchLifecycle() {
        // Light and dark are picked again when the phone switches, for views whose activity is not recreated.
        application.registerComponentCallbacks(object : ComponentCallbacks {
            override fun onConfigurationChanged(c: Configuration) {
                val night = c.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (night != nightMode) { nightMode = night; announceDesign() }
            }
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {}
        })
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                if (!foreground) { foreground = true; foregroundWatchers.forEach { it(true) } }
            }
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && foreground) { foreground = false; foregroundWatchers.forEach { it(false) } }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            // The engine goes where the customer goes; a call carries on across activities.
            override fun onActivityResumed(a: Activity) { if (voiceLazy.isInitialized()) voice.follow(a) }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (voiceLazy.isInitialized()) voice.release(a) }
        })
    }

    companion object {
        internal const val TAG = "Graine"
        @Volatile private var instance: GraineInApp? = null

        /** Create the one instance. Call once, in Application.onCreate. */
        @JvmStatic
        fun init(application: Application, config: GraineConfig): GraineInApp =
            instance ?: synchronized(this) {
                instance ?: GraineInApp(application, config).also {
                    instance = it
                    it.watchLifecycle()
                    it.loadSession { }
                }
            }

        /** The instance init() created. Throws if init() was never called. */
        @JvmStatic
        fun get(): GraineInApp = instance ?: error("GraineInApp.init(application, config) has not been called")

        /** The instance, or null before init() — for the SDK's own service, receiver and screen. */
        internal fun peek(): GraineInApp? = instance
    }
}

/**
 * `POST {baseUrl}/api/embed/session` — the agent's configuration, as every
 * Graine SDK reads it. Plain JVM (HttpURLConnection, org.json), so the live
 * end-to-end test reads it exactly as the app does.
 */
internal object SessionFetch {
    fun fetch(baseUrl: String, publishableKey: String): SessionConfig {
        val url = URL("${baseUrl.trimEnd('/')}/api/embed/session")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("publishableKey", publishableKey).toString().toByteArray()) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            val data = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
            when (code) {
                in 200..299 -> Unit
                403 -> throw IllegalStateException(
                    "This agent does not accept connections from a mobile app yet. Add \"app://\" to its " +
                        "Allowed domains in the dashboard, under Embed & Widgets. (server said: ${data.optString("error", "forbidden")})")
                401 -> throw IllegalStateException("Publishable key was rejected. Check it against Embed & Widgets in the dashboard.")
                else -> throw IllegalStateException(data.optString("error").ifEmpty { "Embed rejected ($code)" })
            }
            return parse(data)
        } finally {
            conn.disconnect()
        }
    }

    fun parse(data: JSONObject): SessionConfig {
        val agentId = data.optString("agentId")
        val declared = mutableSetOf<String>()
        data.optJSONArray("appActions")?.let { arr ->
            for (i in 0 until arr.length()) {
                val a = arr.optJSONObject(i) ?: continue
                val n = a.optString("tool_name").ifEmpty { a.optString("toolName") }.ifEmpty { a.optString("name") }
                if (n.isNotEmpty()) declared.add(n)
            }
        }
        val vars = mutableMapOf<String, Any?>()
        data.optJSONObject("variables")?.let { v -> v.keys().forEach { k -> vars[k] = v.opt(k) } }
        return SessionConfig(
            agentId = agentId,
            webcallAgentId = data.optString("webcallAgentId").ifEmpty { agentId },
            appearance = data.optJSONObject("appearance") ?: JSONObject(),
            variables = vars,
            pageVersion = data.optString("pageVersion").ifEmpty { null },
            declaredActions = declared,
            friction = data.opt("friction") as? JSONObject,
            design = ResolvedDesign.parse(data.opt("design")),
            wsHost = data.optString("wsHost").takeIf { it.startsWith("wss://") || it.startsWith("ws://") },
        )
    }
}
