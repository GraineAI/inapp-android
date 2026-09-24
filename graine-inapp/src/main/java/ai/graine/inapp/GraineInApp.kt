package ai.graine.inapp

import android.app.Activity
import android.app.Application
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
) {
    data class Timing @JvmOverloads constructor(
        val stallMs: Long = 15_000,
        val bootMs: Long = 15_000,
        val hardCapMs: Long = 120_000,
        val retryDelaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 15_000),
        val endWatchdogMs: Long = 4_000,
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

    private val actions = LinkedHashMap<String, (JSONObject) -> ActionResult>()
    @Volatile private var screen: GraineScreen? = null
    @Volatile private var screenSince = System.currentTimeMillis()
    private val recentEvents = ArrayDeque<JSONObject>()
    private var contextSeq = 0
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
        return frame.put("seq", ++contextSeq)
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
    fun registerAction(name: String, handler: (JSONObject) -> ActionResult): () -> Unit {
        synchronized(actions) { actions[name] = handler }
        scheduleContext()
        return { synchronized(actions) { if (actions[name] === handler) actions.remove(name) }; scheduleContext() }
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
    internal fun executeAction(name: String, args: JSONObject, answer: (ActionResult) -> Unit) {
        listeners.forEach { it.onActionRequested(name) }
        val handler = synchronized(actions) { actions[name] }
        var answered = false
        fun once(r: ActionResult) {
            if (answered) return
            answered = true
            listeners.forEach { it.onActionCompleted(name, r) }
            answer(r)
        }
        if (handler == null) {
            once(ActionResult.refused("This app build does not implement \"$name\"."))
            return
        }
        main.postDelayed({ once(ActionResult.error("The app did not answer within ${Protocol.ACTION_DEADLINE_MS / 1000} seconds.")) },
            Protocol.ACTION_DEADLINE_MS)
        main.post {
            val r = try { handler(args) } catch (e: Throwable) { ActionResult.error(e.message ?: e.javaClass.simpleName) }
            once(r)
        }
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

    private fun fetchSession(): SessionConfig {
        val url = URL("${config.baseUrl.trimEnd('/')}/api/embed/session")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(JSONObject().put("publishableKey", config.publishableKey).toString().toByteArray()) }
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
            )
        } finally {
            conn.disconnect()
        }
    }

    // ── the app's lifecycle ─────────────────────────────────────────────────

    private var started = 0
    internal var foreground = true
        private set
    internal val foregroundWatchers = CopyOnWriteArraySet<(Boolean) -> Unit>()

    private fun watchLifecycle() {
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
    }
}
