package ai.graine.inapp

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executor
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
    /**
     * Notice taps by itself: a tap on a labelled control (what TalkBack
     * reads, its view id, or a button's words) becomes `tap:<name>` in the
     * screen's recent events, at most twice a second and only while a screen
     * is reported — so the agent knows what the customer just pressed. Only
     * watches; every touch reaches the app as before. On by default.
     */
    val captureTaps: Boolean = true,
    /**
     * How big the chat window opens, over the dashboard's choice (Embed &
     * Widgets → Appearance → Chat window → Window size): COMPACT above the
     * bar, LARGE a sheet over most of a phone (a panel on a tablet), FULL the
     * whole screen. Null, the default, follows the dashboard; a size passed to
     * [GraineChat.open] wins for that open.
     */
    val chatSize: GraineChatSize? = null,
    /**
     * How long the chat stays connected once no window shows it, in seconds:
     * the conversation closes this long after the last window left it (or
     * after its last message, whichever is later), unless a window shows it
     * again first — long enough for a quick hop to the booking sheet or a
     * component. 15 by default; 0 closes it as soon as the last window leaves;
     * kept within 0–300 (a negative value is 0). [GraineChat.closeDelaySeconds]
     * changes it while the app runs. New in 0.14.3.
     */
    val chatCloseDelaySeconds: Int = 15,
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
    /** Which trait names the user (the agent's Memory settings: Who is the user?); null from a server that predates it. */
    val identity: SessionIdentity? = null,
    /** Whether the agent remembers its users; null when the server does not say. */
    val memoryEnabled: Boolean? = null,
    /** The rules for booking calls, and for the calls Graine rings ([GraineSchedulingRules]); the defaults from an older server. */
    val schedulingRules: GraineSchedulingRules = GraineSchedulingRules(),
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
    /** The agent drew a component. Answer it with [GraineInApp.answerWidget] — the SDK's component views do. */
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
     * The voice bar's levels moved ([GraineVoice.agentLevel],
     * [GraineVoice.micLevel]), each 0–1 — about five times a second while a
     * call is live, and once more to 0 when it ends. New in 0.14.1.
     */
    fun onLevels(agent: Float, mic: Float) {}
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
    /** The text conversation ([GraineChat]) connected, is (re)connecting, or is off. */
    fun onChatState(connected: Boolean, connecting: Boolean) {}
    /**
     * A line of the text conversation: the agent's turn as it streams in
     * (`live`, the words so far), then once more when it is complete; the
     * customer's line when they send it.
     */
    fun onChatMessage(role: Role, text: String, live: Boolean) {}
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
 * Several agents in one app — a support agent in Help, a sales agent on
 * Plans — each with its own publishable key: name the others.
 *
 *     GraineInApp.init(application, GraineConfig(baseUrl, "pk_live_sales…"), name = "sales")
 *     GraineInApp.get("sales").chat          // its own session, chat, voice, actions, scheduling and memory calls
 *
 * Shared by every agent: the install id, who the user is ([identify] on one
 * identifies them for all; null signs out everywhere), their memory choice,
 * the screen ([setScreen] on any, each agent hears it while it has a live
 * conversation) and the one native call engine ([calls]). One conversation at
 * a time across all of them. An app with one agent names none: init() makes
 * the default, and get() returns it.
 *
 * Everything that touches a view runs on the main thread; everything that
 * touches the network does not.
 */
class GraineInApp private constructor(
    internal val application: Application,
    val config: GraineConfig,
    /** This agent's name: [DEFAULT] unless init() was given another. */
    val name: String,
    internal val env: AgentEnv,
) {
    internal val main: MainThread = env.main
    private val io: Executor = env.io ?: Executors.newSingleThreadExecutor { r -> Thread(r, "graine-io").apply { isDaemon = true } }
    internal val listeners = CopyOnWriteArraySet<GraineListener>()

    internal val variables = VariableStore(config.variables)
    private val variableWatchers = CopyOnWriteArraySet<(Map<String, String>) -> Unit>()

    /** This install's own store, shared by every agent: the install id, the push token, memory choices. In the no-backup files directory. */
    internal val device: DeviceStore by lazy { env.device ?: DeviceStore.of(application) }
    /** Who the user is, as this agent's session names them (each agent's dashboard picks its own identifying trait). */
    private val identity = IdentityState { device }
    private val identityWatchers = CopyOnWriteArraySet<(IdentityEnvelope) -> Unit>()
    internal val api by lazy { EmbedApi(config.baseUrl, config.publishableKey, env.http) }
    /** The last session, kept on the device: a push that wakes a closed app draws the call in the brand's design. */
    private val sessionCache by lazy { env.sessionCache ?: SessionCache(application) }
    /** This agent's key and base URL, fingerprinted: what its slots on the device are kept under. */
    internal val owner: String by lazy { SessionCache.owner(config.baseUrl, config.publishableKey) }

    private val actions = ActionRegistry(
        post = { task -> main.post { task() } },
        schedule = { delayMs, task -> val r = Runnable { task() }; main.postDelayed(r, delayMs); ({ main.removeCallbacks(r) }) },
        requested = { name -> listeners.forEach { it.onActionRequested(name) } },
        completed = { name, result -> listeners.forEach { it.onActionCompleted(name, result) } },
    )
    /** Read from the main thread and a native call's session thread alike. */
    private val contextSeq = java.util.concurrent.atomic.AtomicInteger()
    private val contextWatchers = CopyOnWriteArraySet<(JSONObject) -> Unit>()
    /** One frame at once, then at most one per 400 ms — the React Native client's pacing. Main thread. */
    private val contextThrottle = ContextThrottle(
        schedule = { delayMs, task -> val r = Runnable { task() }; main.postDelayed(r, delayMs); ({ main.removeCallbacks(r) }) },
        flush = { flushContext() },
    )
    @Volatile var session: SessionConfig? = null
        private set
    private val sessionWaiters = mutableListOf<(SessionConfig?) -> Unit>()
    private var sessionLoading = false

    // Declared before `voice`: a delegate is read when the property is initialised.
    private val voiceLazy = lazy { GraineVoice(this) }

    /** The voice call. Attach it once, from your first activity; it follows the customer from there. */
    val voice: GraineVoice by voiceLazy

    /**
     * Native calls, like WhatsApp: the agent rings the customer (a push → the
     * call notification), or the customer calls the agent — with the screen
     * off, from the lock screen, outcomes back to the app. See [GraineCalls].
     * The app's one call engine, whichever agent it is reached through: name
     * the agent a call is with in [GraineCalls.startCall].
     */
    val calls: GraineCalls get() = AppShared.calls(this)

    private val chatLazy = lazy { GraineChat(this) }

    /**
     * The text conversation with the agent — what the voice bar switches to
     * when the dashboard offers chat, and what [GraineChatView] draws. See [GraineChat].
     */
    val chat: GraineChat by chatLazy

    // ── one conversation at a time, across every agent ───────────────────────

    /** This agent's web view call, if one is live or connecting, ends. */
    private fun endWebViewCall() {
        if (voiceLazy.isInitialized() && (voice.connected || voice.connecting)) voice.end()
    }

    /** This agent's chat steps aside; its lines stay. */
    private fun endChat() {
        if (chatLazy.isInitialized()) chat.yieldToCall()
    }

    /** A voice call is starting on this agent: every chat, and every other agent's call, ends. */
    internal fun voiceStarting() {
        for (g in AppShared.all()) {
            if (g !== this) g.endWebViewCall()
            g.endChat()
        }
    }

    /** This agent's chat is opening: every web view call, and every other agent's chat, ends. */
    internal fun chatOpening() {
        for (g in AppShared.all()) {
            g.endWebViewCall()
            if (g !== this) g.endChat()
        }
    }

    /** True while a native call is ringing, connecting or live — the web view engine must not start one too. */
    internal fun nativeCallLive(): Boolean = AppShared.callsIfMade()?.let { it.state != GraineCallState.IDLE } ?: false

    /**
     * ANSWER A COMPONENT the agent drew, from a UI of your own — on whichever
     * conversation is live: the voice call's page, or the chat's socket (the
     * structured answer, then the sentence that is the customer's turn, as the
     * web chat sends them). [summary] is that sentence ("The Creta, please"):
     * without it the answer lands and the conversation does not move; [data]
     * goes to your webhook. The SDK's component views answer for themselves.
     * Main thread.
     */
    fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String) {
        clearPendingWidget()
        val onCall = voiceLazy.isInitialized() && voice.connected
        if (!onCall && chatLazy.isInitialized() && chat.connected) chat.submitWidget(widgetId, data, summary)
        else voice.answerWidget(widgetId, data, summary)
    }

    /**
     * Something happened that the agent may want to speak up about — on
     * whichever conversation is live (a voice call, or the chat). With none
     * live, nobody is listening and it is dropped. The current screen goes
     * first, so the agent reads the event against the right one.
     */
    @JvmOverloads
    fun reportEvent(name: String, detail: String? = null, data: Map<String, Any?> = emptyMap()) {
        if (voiceLazy.isInitialized() && voice.connected) { voice.reportEvent(name, detail, data); return }
        if (chatLazy.isInitialized() && chat.connected) {
            currentContextFrame()?.let { frame -> contextWatchers.forEach { it(frame) } }
            val payload = LinkedHashMap<String, Any?>()
            detail?.let { payload["detail"] = it }
            payload.putAll(data)
            chat.reportEvent(name, payload)
        }
    }

    /** The live conversation is the typed chat, not a call: a component that arrives now is the chat's. */
    internal fun inTextChat(): Boolean = !(voiceLazy.isInitialized() && voice.connected) && chatLazy.isInitialized() && chat.connected

    /** A conversation (the voice call or the chat) came up or went down: the friction clock runs only while one is live. */
    internal fun conversationChanged() {
        val live = (voiceLazy.isInitialized() && voice.connected) || (chatLazy.isInitialized() && chat.connected)
        friction.onCall(live, android.os.SystemClock.uptimeMillis())
        armStall()
    }

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
    fun clearPendingWidget() {
        pendingWidget = null
        loading = null
    }

    /**
     * A component the agent asked for whose content is still loading — its
     * hint as the agent sent it (`widget_id`, `kind`) — from
     * [GraineListener.onWidgetPending] until it arrives ([pendingWidget]), it
     * is answered or closed, or 12 seconds pass with nothing, as the SDK's
     * component view gives up. Null otherwise. Draw your loader while it is set.
     */
    val loadingWidget: JSONObject?
        get() = loading?.takeIf { widgetClock() - it.second < Widgets.PENDING_WIDGET_MS }?.first

    /** The hint of the component on its way, and when it came. */
    @Volatile private var loading: Pair<JSONObject, Long>? = null

    internal fun widgetOnItsWay(hint: JSONObject) { loading = hint to widgetClock() }

    /** The clock a loading component is timed on: the agent's scheduler's, else the uptime. */
    private fun widgetClock(): Long = env.scheduler?.now() ?: android.os.SystemClock.uptimeMillis()

    /**
     * CLOSE A COMPONENT the agent drew without answering it, from a UI of
     * your own — what the close of the SDK's component view does: the agent
     * is told quietly (`component_dismissed`, on whichever conversation is
     * live), so it does not go on about options nobody can see, and
     * [pendingWidget] is forgotten. [widgetId] is the component's
     * ([GraineWidget.id]). Main thread.
     */
    fun dismissWidget(widgetId: String) {
        if (widgetId.isBlank()) return
        val w = GraineWidget.adopt(pendingWidget, config.baseUrl)?.takeIf { it.id == widgetId }
        clearPendingWidget()
        val report = WidgetFlow.Dismissal(widgetId.split(":")[0], w?.kind.orEmpty(), w?.let { Js.strOrEmpty(it.data.opt("title")) }.orEmpty())
        reportEvent("component_dismissed", Widgets.DISMISSED_DETAIL, mapOf("quiet" to true, "data" to report.eventData()))
    }

    internal fun retainWidget(widget: JSONObject) {
        pendingWidget = widget
        loading = null
    }

    fun addListener(l: GraineListener) { listeners.add(l) }
    fun removeListener(l: GraineListener) { listeners.remove(l) }

    // ── who, and what they are called ───────────────────────────────────────

    /**
     * Who is signed in. Traits become prompt variables and land on the call
     * record, masked, as always. The trait the agent's Identity setting names
     * (`user_id` unless the dashboard chose `email`, `phone` or one of yours)
     * is also how Graine tells your users apart — what lets the agent
     * remember a user across conversations, and ring them — and it travels
     * only to Graine, never into the prompt's variables beyond the trait itself.
     *
     * `memory = false`: this user's conversations are neither remembered nor
     * recalled. The choice is kept on this install for this user, so it need
     * not be repeated; null leaves the last choice (on, by default).
     *
     * Null signs out: the identity is forgotten, this device is released for
     * that user (it stops being rung for them, and stops counting toward their
     * devices), and later conversations are anonymous.
     *
     * A chat belongs to whoever was identified when it connected. When the
     * identity changes while the chat is connected or connecting — signing in
     * after it opened, another user, or signing out — that conversation is
     * closed quietly, its messages are cleared (a message typed and not yet
     * sent stays, and is sent next), and the chat connects again at once as
     * the new identity: a user's conversation continues, or the agent greets
     * them. The same identity with other traits, or another memory choice,
     * changes nothing on the open chat. Identify before showing the chat where
     * you can: the reconnect costs the customer a moment. A voice call reads
     * who the user is when it starts.
     *
     * One user for the whole app: identifying on any agent identifies them for
     * every agent, and null signs them out of every one.
     */
    @JvmOverloads
    fun identify(traits: Map<String, Any?>?, memory: Boolean? = null) {
        val next = traits?.let { LinkedHashMap(it) }
        AppShared.traits = next
        var anotherUser = false
        for (g in agentsWith(this)) anotherUser = g.applyIdentity(next, memory) || anotherUser
        // An agent made later starts with this user and the choice made for them; another user's is not theirs.
        if (memory != null || anotherUser || next == null) AppShared.memory = memory
    }

    /** identify(), on this agent: true when it now names someone else (or nobody), whose conversations are not the last user's. */
    private fun applyIdentity(traits: Map<String, Any?>?, memory: Boolean?): Boolean {
        val before = identity.value()
        variables.setIdentity(traits)
        identity.set(traits, memory)
        val after = identity.value()
        if (before != after) {
            // Someone else, or nobody: the last user's device binding is theirs, not the next user's.
            if (before != null) signOut(before)
            // A chat belongs to whoever was identified when its ticket was made (contract/DESIGN.md) — nobody to a
            // user included: the conversation open now is closed, its turns go, a line not yet sent stays, and a
            // chat that is wanted connects again at once as the new identity.
            if (chatLazy.isInitialized()) chat.identityChanged()
        }
        announceVariables()
        announceIdentity()
        scheduleContext()
        if (after != null && (after != before || memory != null)) registerDevice()
        return before != after
    }

    /** Remember the current user's conversations, or stop — the same as identify(the same traits, memory = enabled). Every agent. */
    fun setMemory(enabled: Boolean) {
        AppShared.memory = enabled
        for (g in agentsWith(this)) {
            g.identity.setMemory(enabled)
            g.announceIdentity()
            g.registerDevice()
        }
    }

    /** Whether the current user's conversations are remembered: their last choice on this install, else on. */
    val memoryEnabled: Boolean get() = identity.memory()

    /**
     * Forget everything the agent remembers about the signed-in user —
     * allowed only from a device that user is using. [done] hears how many
     * memories were deleted, or why not (`identity_required` with nobody
     * signed in, `device_limit` from a device the user is not using).
     */
    @JvmOverloads
    fun forgetMemory(done: GraineCallback<Int>? = null) {
        val env = identityEnvelope()
        if (env.value == null) {
            main.post { done?.onResult(null, GraineError(GraineErrorCode.IDENTITY_REQUIRED, "Nobody is signed in: identify() the user first.")) }
            return
        }
        request(EmbedApi.FORGET, env.who(org.json.JSONObject())) { r ->
            when (r) {
                is EmbedReply.Ok -> {
                    // The conversation is part of what was forgotten: off the screen too — only once it is gone.
                    chat.memoryForgotten()
                    done?.onResult(Scheduling.deleted(r.body), null)
                }
                is EmbedReply.Failed -> done?.onResult(null, r.error)
            }
        }
    }

    /**
     * Graine lets go of the conversation it kept for this agent and the
     * signed-in user, so the next chat starts fresh ([GraineChat.newConversation]).
     * Nobody signed in: nothing was kept, nothing to ask. On the main thread.
     */
    internal fun letGoOfConversation(done: GraineCallback<Unit>) {
        val env = identityEnvelope()
        if (env.value == null) {
            main.post { done.onResult(Unit, null) }
            return
        }
        request(EmbedApi.NEW_CONVERSATION, env.who(org.json.JSONObject())) { r ->
            when (r) {
                is EmbedReply.Ok -> done.onResult(Unit, null)
                is EmbedReply.Failed -> done.onResult(null, r.error)
            }
        }
    }

    /**
     * What the agent remembers about the signed-in user, newest first —
     * for a privacy screen that shows it (and [forgetMemory] to clear it).
     * Only from a device that user is using: [done] hears `identity_required`
     * with nobody signed in, `device_limit` from a device the user is not
     * using the agent on.
     */
    fun memories(done: GraineCallback<List<GraineMemory>>) {
        val env = identityEnvelope()
        if (env.value == null) {
            main.post { done.onResult(null, GraineError(GraineErrorCode.IDENTITY_REQUIRED, "Nobody is signed in: identify() the user first.")) }
            return
        }
        request(EmbedApi.MEMORIES, env.who(org.json.JSONObject())) { r ->
            when (r) {
                is EmbedReply.Ok -> done.onResult(Memories.list(r.body), null)
                is EmbedReply.Failed -> done.onResult(null, r.error)
            }
        }
    }

    /**
     * This install's id: a random UUID made on first launch and kept for as
     * long as the app is installed (never backed up, so a restored phone is a
     * new install). Graine sends it with its own requests; the agent never sees it.
     */
    val installId: String get() = device.installId()

    /** What Graine's requests say about who is using the app. Any thread. */
    internal fun identityEnvelope(): IdentityEnvelope = identity.envelope()

    internal fun onIdentity(watch: (IdentityEnvelope) -> Unit): () -> Unit {
        identityWatchers.add(watch); return { identityWatchers.remove(watch) }
    }

    private fun announceIdentity() {
        val env = identity.envelope()
        main.post { identityWatchers.forEach { runCatching { it(env) } } }
        // A call prepared for anyone else — another user, another memory choice — is let go before anything else.
        AppShared.callsIfMade()?.whoChanged(this, env)
        warmCalls(env)
    }

    /**
     * THE FIRST TAP OF A WEBRTC CALL, FAST. With the WebRTC module in the app,
     * the grant this agent's next call needs to mint at the telephony gateway —
     * beside the SBC, not an ocean away (RtcDirect.kt) — is kept ready: checked
     * once the session has said who the user is, whenever that changes
     * (identify, sign-out, the memory choice) — it is what a grant carries —
     * and when the app comes back to the foreground. A check sends nothing while
     * the grant in hand holds. Here because every start and every identify
     * passes through: the session's arrival and identify() both announce the
     * identity, from an app's own code or a bridge's (Kotlin Multiplatform,
     * React Native, Flutter). A call ringing with this agent has its own grant
     * asked for again under the new identity. Never before the session — a
     * grant carries who the user is, as a ticket does, and neither is asked for
     * before it says — and never for calls that go over the WebSocket. Nothing
     * waits on it and nothing fails without it: a call with no grant mints
     * through the web route, as it always did. Never the microphone.
     */
    private fun warmCalls(who: IdentityEnvelope) {
        if (!callsMayMintDirect()) return
        runCatching { env.directMint.warm(config.baseUrl, config.publishableKey, who) }
        runCatching { AppShared.callsIfMade()?.ringingCallOf(this) }.getOrNull()?.let { callId ->
            runCatching { env.directMint.warmCall(config.baseUrl, config.publishableKey, who, callId) }
        }
    }

    /**
     * A ring for this agent has arrived: its own grant is wanted, for exactly
     * its call id, so the mint at the answer can go to the gateway
     * (RtcDirect.kt). The ring's call, prepared now, brings it with its own
     * mint's answer; only an answer without one has it asked for apart. Before
     * the session there is nobody to want it for — the session's arrival asks,
     * for the ring still ringing.
     */
    internal fun warmRing(callId: String) {
        if (!callsMayMintDirect()) return
        val who = runCatching { identityEnvelope() }.getOrNull() ?: return
        runCatching { env.directMint.expectCall(config.publishableKey, who, callId) }
    }

    /** The session has said who the user is, the WebRTC module is here, and the app and the dashboard let calls use WebRTC. */
    private fun callsMayMintDirect(): Boolean {
        if (session == null || !RtcModule.added()) return false
        val app = AppShared.callsIfMade()?.config?.callTransport ?: GraineCallTransport.AUTO
        return CallTransportChoice.decide(app, schedulingRules.audioTransport, moduleAvailable = true, osSupported = RtcModule.runsHere()).webrtc
    }

    /**
     * The id the call about to be placed with this agent carries: the one its
     * warm grant was asked for (RtcDirect.kt), so it mints at the gateway and
     * names itself exactly as a call minted through the web route would — else
     * null, and the call takes an id of its own. The next call's grant follows
     * this call's mint.
     */
    internal fun nextCallId(): String? {
        if (!RtcModule.added()) return null
        val who = runCatching { identityEnvelope() }.getOrNull() ?: return null
        return runCatching { env.directMint.takeCallId(config.publishableKey, who) }.getOrNull()
    }

    /** Release this device for a user who left it. Best effort: the server also releases a device unused for 30 days. */
    private fun signOut(value: String) {
        val env = IdentityEnvelope(value, runCatching { device.installId() }.getOrNull(), identity.memory(), IdentityRules.timezone())
        request(EmbedApi.SIGNOUT, env.who(org.json.JSONObject()), retries = HostedOutcomes.RETRY_DELAYS_MS) { }
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
     * unchanged screen is not resent, and bursts are coalesced. The screen is
     * the app's, not an agent's: reported on any agent, every agent has it,
     * and each sends it on while it has a live conversation.
     */
    fun setScreen(next: GraineScreen?) {
        if (next == AppShared.screen) return
        if (next?.screen != AppShared.screen?.screen) AppShared.screenSince = System.currentTimeMillis()
        AppShared.screen = next
        val now = android.os.SystemClock.uptimeMillis()
        for (g in agentsWith(this)) g.screenChanged(next, now)
    }

    private fun screenChanged(next: GraineScreen?, now: Long) {
        scheduleContext()
        main.post { friction.onScreen(next, now); armStall() }
    }

    /** The screen as last reported, on any agent, or null. */
    val currentScreen: GraineScreen? get() = AppShared.screen

    /**
     * One field changed — report just that. Merged by name into the current
     * screen (added when it is new) and sent like any screen change, so a
     * screen described once need not be described again on every keystroke.
     * Nothing happens before a screen is reported.
     */
    fun updateField(field: GraineField) {
        val s = AppShared.screen ?: return
        val i = s.fields.indexOfFirst { it.name == field.name }
        val fields = if (i >= 0) s.fields.toMutableList().apply { set(i, field) } else s.fields + field
        setScreen(s.copy(fields = fields))
    }

    /** A field left the screen. Nothing happens when it was never reported. */
    fun removeField(name: String) {
        val s = AppShared.screen ?: return
        if (s.fields.none { it.name == name }) return
        setScreen(s.copy(fields = s.fields.filter { it.name != name }))
    }

    /**
     * The customer left a screen. With [screenId], only if it is still the
     * one reported — a screen closing late must not erase the one that
     * replaced it. Null clears whatever is there.
     */
    @JvmOverloads
    fun clearScreen(screenId: String? = null) {
        val s = AppShared.screen ?: return
        if (screenId != null && s.screen != screenId) return
        setScreen(null)
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
        for (g in agentsWith(this)) g.main.post { g.friction.onInteraction(now); g.armStall() }
    }

    /** The voice call's state changed ([GraineVoice]): it is one of the conversations friction waits on. */
    internal fun callStateChanged(@Suppress("UNUSED_PARAMETER") live: Boolean) = conversationChanged()

    private fun armStall() {
        main.removeCallbacks(stallCheck)
        val due = friction.idleDueIn(android.os.SystemClock.uptimeMillis()) ?: return
        main.postDelayed(stallCheck, due)
    }

    private fun onStall() {
        val signal = friction.onIdle(android.os.SystemClock.uptimeMillis()) ?: run { armStall(); return }
        listeners.forEach { l -> runCatching { l.onFriction(signal) } }
        reportEvent(signal.name, signal.detail, mapOf(
            "cooldown_seconds" to signal.cooldownSeconds,
            "max_per_session" to signal.maxPerSession,
        ))
    }

    /**
     * A product event that informs without interrupting. Rides the next
     * context frame. `coalesce`: context, not news — it goes out with the
     * frame at the end of the 400 ms window rather than one of its own (what
     * a tap is).
     */
    @JvmOverloads
    fun track(name: String, data: Map<String, Any?> = emptyMap(), coalesce: Boolean = false) {
        val e = JSONObject().put("name", name).put("at", System.currentTimeMillis())
        if (data.isNotEmpty()) e.put("data", Protocol.toJson(data))
        // The app's events, like its screen: every agent's next frame carries them.
        AppShared.record(e)
        for (g in agentsWith(this)) {
            if (coalesce) g.main.post { if (AppShared.screen != null) g.contextThrottle.changedTrailing() } else g.scheduleContext()
        }
    }

    /**
     * A tap on a labelled control, noticed by [TapCapture] (or reported by an
     * app for a control it names itself): `tap:<name>`, coalesced, at most
     * two a second, and only while a screen is reported — a tap means
     * something only against the screen it was on.
     */
    fun tap(name: String) {
        val label = Taps.label(name) ?: return
        main.post {
            if (AppShared.screen == null) return@post
            val now = android.os.SystemClock.uptimeMillis()
            if (now - AppShared.lastTapAt < Taps.MIN_GAP_MS) return@post
            AppShared.lastTapAt = now
            track(Taps.event(label), coalesce = true)
        }
    }

    internal fun onContext(watch: (JSONObject) -> Unit): () -> Unit {
        contextWatchers.add(watch); return { contextWatchers.remove(watch) }
    }

    /** The current frame, for a transport that has just come up. */
    internal fun currentContextFrame(): JSONObject? {
        // The app's screen and events; this agent's view of the user, and the actions registered on it.
        // The screen's own list narrowed to what this build registered; with none stated, everything registered.
        val frame = ContextFrame.build(AppShared.screen, AppShared.screenSince, System.currentTimeMillis(),
            variables.identity(), config.appVersion, AppShared.events(), registered = actions.keys) ?: return null
        return frame.put("seq", contextSeq.incrementAndGet())
    }

    /**
     * Paced as the React Native client paces it ([ContextThrottle]): the first
     * change goes out at once, later ones at most once per 400 ms, the latest
     * always last — a screen updating on every keystroke is neither flooded
     * nor stalled.
     */
    private fun scheduleContext() {
        main.post { contextThrottle.changed() }
    }

    /** Build the frame and hand it to every live transport. False when there is nothing to say. Main thread. */
    private fun flushContext(): Boolean {
        val frame = currentContextFrame() ?: return false
        contextWatchers.forEach { runCatching { it(frame) } }
        return true
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
     * The design being drawn: a preview when one is set, else the session's —
     * and before this launch's session has arrived, the last session's, kept
     * on the device (so a call that rings a closed app is drawn in the brand's
     * design, not a default). Null when the server sent none — the views then
     * draw from the appearance, exactly as before designs existed.
     */
    val design: ResolvedDesign? get() = designPreview ?: session?.design ?: (if (session == null) cachedSession?.design else null)

    /** The last session's look and calls, as kept on the device for this agent; null before any session was ever fetched. */
    internal val cachedSession: SessionCache.Kept? by lazy { sessionCache.load(config.baseUrl, config.publishableKey) }

    /**
     * The ids this agent answers to — its agent and its web-call twin, from
     * this launch's session, else the last one kept on the device: how a ring
     * Graine sends (its `agent_id`) finds the agent it is for, even on a cold start.
     */
    internal fun knownAgentIds(): Set<String> {
        val s = session
        val kept = if (s == null) cachedSession else null
        return setOfNotNull(s?.agentId, s?.webcallAgentId, kept?.agentId, kept?.webcallAgentId).filter { it.isNotBlank() }.toSet()
    }

    /** The appearance to draw with: this launch's session's, else the last one's. */
    internal val appearance: JSONObject? get() = session?.appearance ?: cachedSession?.appearance

    /**
     * The rules for booking calls — and for the calls Graine rings — from
     * this launch's session, else the last one kept on the device (a push
     * that woke the app), else the defaults: no booking, no hosted ringing.
     */
    val schedulingRules: GraineSchedulingRules get() = session?.schedulingRules ?: cachedSession?.rules ?: GraineSchedulingRules()

    /**
     * Whether this agent can be booked from the app now — what the SDK's own
     * "Book a call" follows, and what an app's own button should: Graine
     * rings this phone (`calls.hosted`, and `calls.platforms` empty or naming
     * Android), the agent lets users book, and the user is identified with
     * the trait the agent's Identity setting names (otherwise a booking is
     * refused with `identity_required`). Each agent answers for itself. Read
     * it again after [identify] and once the session has loaded.
     */
    val bookingAvailable: Boolean get() = Booking.available(schedulingRules, identity.value() != null)

    /** Why the booking sheet cannot book now, as its line; null when it can. */
    internal val bookingBlocked: String? get() = Booking.blocked(schedulingRules, identity.value() != null)

    /**
     * Why this agent cannot be booked from the app now — the first part of
     * [bookingAvailable]'s rule that fails, as a word: `not_loaded` (its
     * settings have not loaded yet), `rings_not_set_up` (Graine does not ring
     * this phone: the workspace's In-app calling, for Android), `scheduling_off`
     * (the agent does not let users book), `not_identified` (nobody is
     * identified with the trait the agent's Identity setting names). Null
     * when it can be booked. For a button of your own that says why.
     */
    val bookingUnavailableReason: String?
        get() = Booking.unavailableReason(schedulingRules, identity.value() != null, session != null || cachedSession != null)

    /**
     * The times the SDK's booking sheet would offer on the day [day] falls on,
     * in the phone's time zone — every 5 minutes from the soonest the agent's
     * rules allow ([GraineSchedulingRules.earliest], rounded up) to the
     * latest ([GraineSchedulingRules.latest]), oldest first — for a picker of
     * your own — and, when the agent has a calling window
     * ([GraineSchedulingRules.callingWindow]), only the times inside it, on
     * the phone's clock. Empty for a day outside the rules. Graine checks the
     * time again when it is booked, with what only it knows (the calls
     * already booked).
     */
    fun bookingSlots(day: java.util.Date): List<java.util.Date> =
        Booking.slots(schedulingRules, System.currentTimeMillis(), java.util.TimeZone.getDefault(), day.time).map { java.util.Date(it) }

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
        if (!env.fonts) return
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
            val result = runCatching {
                val raw = env.fetchSession(config.baseUrl, config.publishableKey)
                // Kept for a cold start: the design a push-woken call screen draws, the ring's length.
                runCatching { sessionCache.save(config.baseUrl, config.publishableKey, raw) }
                raw to SessionFetch.parse(raw)
            }
            main.post {
                val s = result.getOrNull()?.second
                if (s != null) {
                    session = s
                    // Who the user is, as this agent decides: identify() may have come first, and resolves now.
                    identity.onSession(s.identity)
                    // The trait that names the user goes only to Graine: out of the variables and the context's `user`.
                    variables.setIdentityField(identity.fieldOrDefault())
                    // The typefaces first: they take a round trip, and everything below draws.
                    loadFaces()
                    variables.setDashboardDefaults(s.variables)
                    // The dashboard's friction rules, under the app's own.
                    friction.configure(Friction.resolve(FrictionConfig.fromJson(s.friction), config.friction))
                    armStall()
                    announceVariables()
                    announceIdentity()
                    scheduleContext()
                    listeners.forEach { it.onSessionReady(s) }
                    // Graine rings this app's users itself: say where this install can be rung.
                    registerDevice()
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

    // ── hosted ringing: where this install can be rung ──────────────────────

    /** A registration in flight, so two triggers in one moment send it once. */
    @Volatile private var registering: String? = null

    /**
     * Register this install's push token with Graine, when the workspace has
     * Graine ring its users (`calls.hosted`) and a token reached the SDK (from
     * Firebase by itself, or [GraineCalls.onNewToken]): on every new token ([force]), whenever what
     * it says changes (another user, a new zone, a new app version), and
     * otherwise at most once a day ([DeviceRegistration]). Best effort: the
     * next launch tries again. Every agent registers the one install with its
     * own key, so each agent's workspace can ring it.
     */
    internal fun registerDevice(force: Boolean = false) {
        val token = runCatching { device.pushToken }.getOrNull() ?: return
        loadSession { s ->
            if (s?.schedulingRules?.ringsAndroid != true) return@loadSession
            val who = identity.envelope()
            val version = appVersion()
            val fp = DeviceRegistration.fingerprint(token, application.packageName, version, GraineVersion.NAME, who)
            if (fp == registering) return@loadSession
            // This agent's own record; the default agent's is where one agent's was kept before agents had names.
            val last = runCatching { device.lastRegistration(owner) ?: if (name == DEFAULT) device.lastRegistration() else null }.getOrNull()
            if (!force && !DeviceRegistration.due(last, fp, System.currentTimeMillis())) return@loadSession
            registering = fp
            request(EmbedApi.DEVICES, DeviceRegistration.body(token, application.packageName, version, GraineVersion.NAME, who)) { r ->
                registering = null
                when (r) {
                    is EmbedReply.Ok -> {
                        runCatching { device.registered(fp, System.currentTimeMillis(), owner) }
                        if (DeviceRegistration.reason(r.body) == GraineErrorCode.DEVICE_LIMIT.wire) {
                            android.util.Log.w(TAG, "This user is already on as many devices as the agent allows: this one is registered, but not rung for them.")
                        }
                    }
                    is EmbedReply.Failed ->
                        android.util.Log.w(TAG, "This device could not be registered for calls (${r.error.code}); it is tried again later.")
                }
            }
        }
    }

    /** A new push token (the SDK's messaging service, the app's, or the one read at start): kept, and registered when Graine rings this app. */
    internal fun pushTokenChanged(token: String) {
        runCatching { device.pushToken = token }
        registerDevice(force = true)
    }

    /** The build being run, for the call record and the device registry: the app's own word, else its version name. */
    internal fun appVersion(): String? = config.appVersion ?: runCatching {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    }.getOrNull()

    // ── scheduled calls ─────────────────────────────────────────────────────

    /**
     * Book a call from the agent for the signed-in user (or, with nobody
     * signed in, this install) at [at] — "call me tomorrow at 6". The agent
     * must allow it (Calls & scheduling → users may book), and the time must
     * fall inside its calling hours and limits; [done] hears the booked call,
     * or why not (`scheduling_off`, `too_soon`, `too_far`, `outside_window`,
     * `too_many_pending`…). [reason] is kept with the call for the agent, never
     * put in the push that rings it.
     */
    @JvmOverloads
    fun scheduleCall(at: java.util.Date, reason: String? = null, done: GraineCallback<GraineScheduledCall>? = null) {
        val atMs = at.time
        loadSession { s ->
            // The agent's own rules first, before any booking request: scheduling_off, too_soon, too_far.
            val rules = s?.schedulingRules ?: schedulingRules
            Scheduling.check(atMs, System.currentTimeMillis(), rules)?.let { e -> done?.onResult(null, e); return@loadSession }
            request(EmbedApi.SCHEDULE, Scheduling.scheduleBody(atMs, reason, identity.envelope())) { r ->
                when (r) {
                    is EmbedReply.Ok -> {
                        // The chat's Upcoming calls card shows it at once.
                        if (chatLazy.isInitialized()) chat.refreshUpcomingCalls()
                        val call = Scheduling.scheduled(r.body)
                        if (call != null) done?.onResult(call, null)
                        else done?.onResult(null, GraineError(GraineErrorCode.UNAVAILABLE, "The booking was accepted but not described."))
                    }
                    is EmbedReply.Failed -> done?.onResult(null, r.error)
                }
            }
        }
    }

    /** The calls booked for the signed-in user (or this install) that are still to come, soonest first. */
    fun scheduledCalls(done: GraineCallback<List<GraineScheduledCall>>) {
        request(EmbedApi.LIST, identity.envelope().who(JSONObject())) { r ->
            when (r) {
                is EmbedReply.Ok -> done.onResult(Scheduling.list(r.body), null)
                is EmbedReply.Failed -> done.onResult(null, r.error)
            }
        }
    }

    /**
     * Show the SDK's booking sheet over [activity] — a sheet up from the
     * bottom on a phone, a centred panel on a tablet — in the agent's design:
     * the day, the time (the platform's time picker), the phone's time zone,
     * "What's it about? (optional)" (which [reason] fills) and Book call.
     * [done] hears the booked call once the sheet closes, or null when the
     * user closed it without booking. A refusal stays in the sheet as one line.
     *
     * Check [bookingAvailable] before offering it: an agent that cannot be
     * booked now opens a sheet that says why and books nothing. One sheet at a
     * time. Main thread, or any: it is shown on the main thread.
     */
    @JvmOverloads
    fun presentBooking(activity: Activity, reason: String? = null, done: GraineCallback<GraineScheduledCall?>) {
        main.post {
            if (activity.isFinishing || activity.isDestroyed || bookingSheet?.isShowing == true) {
                done.onResult(null, null)
                return@post
            }
            if (!bookingAvailable) {
                android.util.Log.w(TAG, "The booking sheet opened while this agent cannot be booked; it says why. Check bookingAvailable before offering it.")
            }
            val sheet = GraineBookingDialog(activity, this, reason, GraineCallback { call, error ->
                bookingSheet = null
                done.onResult(call, error)
            })
            bookingSheet = sheet
            sheet.show()
        }
    }

    /** The booking sheet on screen, so a second tap does not stack another. Main thread. */
    private var bookingSheet: GraineBookingDialog? = null

    /** Cancel a booked call — only one booked for this user or install. [done] hears the call's status afterwards (cancelled). */
    @JvmOverloads
    fun cancelScheduledCall(id: String, done: GraineCallback<GraineCallStatus>? = null) {
        if (id.isBlank()) { main.post { done?.onResult(null, GraineError(GraineErrorCode.INVALID_REQUEST, "No call id.")) }; return }
        request(EmbedApi.CANCEL, identity.envelope().who(JSONObject()).put("callId", id)) { r ->
            when (r) {
                is EmbedReply.Ok -> done?.onResult(Scheduling.cancelled(r.body), null)
                is EmbedReply.Failed -> done?.onResult(null, r.error)
            }
        }
    }

    /**
     * One request to Graine, off the main thread; [done] on the main thread.
     * With [retries], a request that got no answer is tried again after each
     * wait — never one the server refused.
     */
    internal fun request(path: String, body: JSONObject, retries: List<Long> = emptyList(), done: (EmbedReply) -> Unit) {
        io.execute {
            val reply = api.post(path, body)
            val failed = reply as? EmbedReply.Failed
            if (failed != null && retries.isNotEmpty() && HostedOutcomes.retryable(failed.error)) {
                // A rate limit's Retry-After is honoured: never sooner than the server asked.
                val wait = maxOf(retries.first(), failed.retryAfterMs ?: 0L)
                main.postDelayed({ request(path, body, retries.drop(1), done) }, wait)
                return@execute
            }
            main.post { done(reply) }
        }
    }

    // ── the app's lifecycle ─────────────────────────────────────────────────

    private var started = 0
    internal var foreground = true
        private set
    internal val foregroundWatchers = CopyOnWriteArraySet<(Boolean) -> Unit>()

    /** Bars to tell when the full-screen chat one of them opened ([GraineChatActivity]) was closed. */
    internal val chatScreenWatchers = CopyOnWriteArraySet<() -> Unit>()

    internal fun chatScreenClosed() {
        chatScreenWatchers.forEach { it() }
    }

    /** Bars to tell when that full-screen chat asked for a call (its microphone or call button): the bar places it as its own switch does. */
    internal val chatScreenCallWatchers = CopyOnWriteArraySet<() -> Unit>()

    internal fun chatScreenCall() {
        chatScreenCallWatchers.forEach { it() }
    }

    private var nightMode = runCatching { application.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK }.getOrDefault(0)

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
            override fun onActivityResumed(a: Activity) {
                if (voiceLazy.isInitialized()) voice.follow(a)
                // The app's own screens: the SDK's call and chat screens are not what the customer is doing in the app.
                if (config.captureTaps && a !is GraineCallActivity && a !is GraineChatActivity) runCatching { TapCapture.install(a) { name -> tap(name) } }
            }
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) { if (voiceLazy.isInitialized()) voice.release(a) }
        })
    }

    companion object {
        internal const val TAG = "Graine"

        /** The name of the agent init() makes when it is given none, and get() returns. */
        const val DEFAULT = "default"

        /**
         * Make the agent [name] — the default one unless named. Call once per
         * agent, in Application.onCreate; a second call with the same name
         * returns the agent already made, unchanged.
         */
        @JvmStatic
        @JvmOverloads
        fun init(application: Application, config: GraineConfig, name: String = DEFAULT): GraineInApp =
            make(application, config, name) { AgentEnv() }

        /** init(), on what [env] makes: Android's own in an app, fakes in a test. */
        internal fun make(application: Application, config: GraineConfig, name: String, env: () -> AgentEnv): GraineInApp {
            val (g, made) = AppShared.agent(name) { key -> GraineInApp(application, config, key, env()) }
            if (made) g.started()
            return g
        }

        /** The agent init() made under [name] — the default one unless named. Throws if there is none. */
        @JvmStatic
        @JvmOverloads
        fun get(name: String = DEFAULT): GraineInApp = AppShared.find(name) ?: error(
            if (AppShared.key(name) == DEFAULT) "GraineInApp.init(application, config) has not been called"
            else "No agent is named \"${name.trim()}\": GraineInApp.init(application, config, name = \"${name.trim()}\") first",
        )

        /** The agent init() made under [name], or null. */
        @JvmStatic
        fun find(name: String): GraineInApp? = AppShared.find(name)

        /** Every agent init() made, by name, in the order they were made. */
        @JvmStatic
        val all: Map<String, GraineInApp> get() = AppShared.named()

        /** The default agent, else the first one made, or null before init() — for the SDK's own service, receiver and screens. */
        internal fun peek(): GraineInApp? = AppShared.primary()

        /** The agent a view draws when the app names none: the default, else the first one made. Throws before init(). */
        internal fun primary(): GraineInApp = AppShared.primary() ?: error("GraineInApp.init(application, config) has not been called")

        /** Every agent, [g] among them: a change made on one reaches all of them. */
        private fun agentsWith(g: GraineInApp): List<GraineInApp> = AppShared.all().let { if (g in it) it else it + g }

        /** A native call takes the line: every agent's web view call ends first. */
        internal fun endWebViewCalls() = AppShared.all().forEach { it.endWebViewCall() }

        /** FCM's new token: kept, and registered by every agent with its own key, so each agent's workspace can ring this install. */
        internal fun tokenChanged(token: String) = AppShared.all().forEach { it.pushTokenChanged(token) }

        /** A native call went live (it holds every chat) or ended (each chat the customer is in comes back). */
        internal fun nativeCallChanged(live: Boolean) = AppShared.all().forEach { g -> if (g.chatLazy.isInitialized()) g.chat.nativeCall(live) }
    }

    /** Just made: who the user already is, as the other agents know it; the app's lifecycle; the session; the push token. */
    private fun started() {
        AppShared.traits?.let { t ->
            variables.setIdentity(t)
            identity.set(t, AppShared.memory)
        }
        // The call engine's log follows the agents: any one of them in debug turns it on.
        if (config.debug) CallLog.enabled = true
        watchLifecycle()
        // Back in the foreground: the next call's grant is checked — nothing is sent while it holds (RtcDirect.kt).
        foregroundWatchers.add { inFront -> if (inFront) warmCalls(identityEnvelope()) }
        loadSession { }
        // With Firebase in the app, this install's push token reaches the SDK by itself — once a process.
        FirebasePush.start(application, config.debug)
    }
}

/**
 * `POST {baseUrl}/api/embed/session` — the agent's configuration, as every
 * Graine SDK reads it. Plain JVM (HttpURLConnection, org.json), so the live
 * end-to-end test reads it exactly as the app does.
 */
internal object SessionFetch {
    fun fetch(baseUrl: String, publishableKey: String): SessionConfig = parse(fetchJson(baseUrl, publishableKey))

    /** The session's answer as served, for [parse] and for the copy kept on the device. */
    fun fetchJson(baseUrl: String, publishableKey: String): JSONObject {
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
            return data
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
            identity = IdentityRules.parse(data.opt("identity")),
            memoryEnabled = (data.opt("memory") as? JSONObject)?.opt("enabled") as? Boolean,
            schedulingRules = GraineSchedulingRules.parse(data.opt("calls")),
        )
    }
}

/**
 * THE LAST SESSION, KEPT ON THE DEVICE — what a push that wakes a closed app
 * needs before this launch's session has been fetched: the design (the call
 * screen is drawn in it), the appearance's accent, heading and logo, the calls
 * block (how long Graine's ring rings), and the agent's ids (which agent a
 * ring is for). Nothing secret: what the session serves anyone holding the
 * publishable key. Kept per key and base URL — each agent of an app in a slot
 * of its own — so a build pointed at another agent never draws the last one's look.
 */
internal class SessionCache(private val slotRead: (slot: String) -> String?, private val slotWrite: (slot: String, text: String) -> Unit) {
    constructor(context: Context) : this(
        slotRead = { slot -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(slot, null) },
        slotWrite = { slot, text -> context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(slot, text).apply() },
    )

    /** One place for every agent: the owner check alone keeps them apart. */
    constructor(read: () -> String?, write: (String) -> Unit) : this(slotRead = { _ -> read() }, slotWrite = { _, text -> write(text) })

    /** What was kept: the design, the appearance's look, the calls block, the agent's ids. */
    class Kept(
        val design: ResolvedDesign?,
        val appearance: JSONObject?,
        val rules: GraineSchedulingRules,
        val agentId: String? = null,
        val webcallAgentId: String? = null,
        /** Where the agent's socket lives — what lets a call start on the kept session (GraineCalls). */
        val wsHost: String? = null,
    )

    fun save(baseUrl: String, publishableKey: String, session: JSONObject) {
        val who = owner(baseUrl, publishableKey)
        val o = JSONObject().put("owner", who)
        session.opt("design")?.takeIf { it is JSONObject }?.let { o.put("design", it) }
        (session.opt("appearance") as? JSONObject)?.let { a ->
            val look = JSONObject()
            for (k in APPEARANCE_KEYS) if (a.has(k)) look.put(k, a.opt(k))
            o.put("appearance", look)
        }
        (session.opt("calls") as? JSONObject)?.let { o.put("calls", it) }
        for (k in listOf("agentId", "webcallAgentId", "wsHost")) (session.opt(k) as? String)?.takeIf { it.isNotBlank() }?.let { o.put(k, it) }
        slotWrite(SLOT + "." + who, o.toString())
    }

    fun load(baseUrl: String, publishableKey: String): Kept? {
        val who = owner(baseUrl, publishableKey)
        // This agent's slot; before agents had slots, the one agent's session was kept under the plain key.
        val o = listOf(SLOT + "." + who, SLOT).firstNotNullOfOrNull { slot ->
            runCatching { slotRead(slot)?.let { JSONObject(it) } }.getOrNull()?.takeIf { it.optString("owner") == who }
        } ?: return null
        return Kept(
            ResolvedDesign.parse(o.opt("design")),
            o.opt("appearance") as? JSONObject,
            GraineSchedulingRules.parse(o.opt("calls")),
            o.optString("agentId").ifBlank { null },
            o.optString("webcallAgentId").ifBlank { null },
            o.optString("wsHost").ifBlank { null },
        )
    }

    companion object {
        private const val PREFS = "ai.graine.inapp.session"
        private const val SLOT = "last"
        /** The appearance a call screen draws from; the rest of it is not needed before the session arrives. */
        private val APPEARANCE_KEYS = listOf("accent", "title", "logo", "logoPlate", "theme", "radius")

        /** A key and base URL as one fingerprint: what an agent's slots on the device are kept under. */
        fun owner(baseUrl: String, publishableKey: String): String = IdentityRules.fingerprint(baseUrl.trimEnd('/') + "\n" + publishableKey)
    }
}
