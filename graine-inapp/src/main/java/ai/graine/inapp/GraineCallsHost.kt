package ai.graine.inapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * CALLS FROM REACT NATIVE AND FLUTTER: the one object their call packages
 * drive this SDK's calls ([GraineCalls]) through, in JSON-safe values —
 * strings, numbers, booleans, null, lists and string-keyed maps.
 *
 * A native app does not use it: `GraineInApp.get().calls` is the Kotlin API.
 * The packages for React Native (`@graineai/inapp-react-native-calls`) and
 * Flutter (`graine_inapp`) call it and only marshal values; every rule — the
 * options, the snapshot, where events go, how actions are answered — is here,
 * inside the SDK, where its tests cover it (CallsHostModel.kt).
 *
 *     GraineCallsHost.configure(context, mapOf("publishableKey" to "pk_…", "displayName" to "Acme"))
 *     val sink = GraineCallsHost.addSink { event, payload -> … }
 *     GraineCallsHost.handlePush(context, message.data)   // only from a messaging service of the app's own
 *
 * Pushes: with Firebase in the app, the SDK's own messaging service receives
 * the token and Graine's call pushes, natively and before any JavaScript or
 * Dart runs, and the SDK reads the token when it starts. Only a messaging
 * service of the app's own, which outranks the SDK's, hands them over
 * ([handlePush], [onNewToken]).
 *
 * Several agents in one app: `configure` names the others under
 * `agents` (`{"sales": {"publishableKey": "pk_…"}}`), `startCall` takes
 * `{agent: "sales"}`, and a ring Graine sends rings with the agent it is for.
 * The snapshot, `incoming`, `outcome` and `action` say which: `agent` is the
 * call's agent by name ("default" for the default one).
 *
 * Permissions and setup: [callPermissions], [requestCallPermissions],
 * [openCallSettings] and [callSetupProblems], as JSON-safe maps.
 *
 * Server tokens: a wrapper whose own token manager (JavaScript, Dart) holds
 * the app's server tokens pushes each new one with [setClientToken], and
 * hears `needClientToken` when a request of the SDK's was refused for its
 * token.
 *
 * Events: `state` (the snapshot, at most once per turn of the main loop),
 * `outcome`, `incoming`, `action` and `needClientToken` (`voipToken` is
 * iOS's). Main thread: a
 * call from another thread hops there first (`configure`, `restore`,
 * `snapshot`, `handlePush`, `startCall`, `addSink` and
 * `needsMicrophonePermission` wait for their answer), and events are sent on it.
 */
object GraineCallsHost {
    /** Receives every event: its name (`state`, `outcome`, `incoming`, `action`, `needClientToken`) and its payload. Main thread. */
    fun interface Sink {
        fun onEvent(event: String, payload: Map<String, Any?>)
    }

    private const val PREFS = "ai.graine.inapp.calls"
    /** What opens the call screen: answer, show, the missed call, call back. */
    private val CALL_SCREEN_ACTIONS = setOf(GraineCalls.ACTION_ANSWER, GraineCalls.ACTION_SHOW, GraineCalls.ACTION_MISSED, GraineCalls.ACTION_CALL_BACK)
    private const val KEY = "host"

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var application: Application? = null
    private val device = Device()
    private val core = CallsHostCore(device, CallsHostStore(
        read = { prefs()?.getString(KEY, null) },
        write = { text -> prefs()?.edit()?.putString(KEY, text)?.apply() },
    ))

    // ── setting up ──────────────────────────────────────────────────────────

    /**
     * Apply the options the last `configure` kept, so a launch can ring
     * before any JavaScript or Dart runs. True when something was restored;
     * false when nothing was kept, or this launch is already configured.
     * [handlePush] does this by itself.
     */
    @JvmStatic
    fun restore(context: Context): Boolean {
        remember(context)
        return onMainSync { core.restore() }
    }

    /**
     * Configure the calls and keep the options for the next cold start. The
     * first call makes the SDK instance (GraineInApp.init); later ones update
     * the calls. Throws [GraineCallsHostException] (`invalid_options`), naming
     * the option that is wrong.
     */
    @JvmStatic
    fun configure(context: Context, options: Map<*, *>?) {
        remember(context)
        onMainSync { core.configure(options) }
    }

    /** The call as a call screen draws it: every key always present. */
    @JvmStatic
    fun snapshot(): Map<String, Any?> = onMainSync { core.snapshot() }

    /** iOS registers for VoIP pushes here. Android has none: Firebase's messages reach the SDK's messaging service, or an app's own hands them to [handlePush]. */
    @JvmStatic
    fun enableVoIPPushes() {}

    /**
     * A push's data, from a messaging service of the app's own (the SDK's
     * own service needs no call): `{String: String}`.
     * If nothing configured the calls this launch, the kept options are
     * applied first, so a push that woke the app rings before any JavaScript
     * or Dart has run. True when it was a call (ringing, refused as busy, or
     * malformed) and the app should do nothing more with it.
     */
    @JvmStatic
    fun handlePush(context: Context, data: Map<*, *>?): Boolean {
        remember(context)
        return onMainSync { core.handlePush(data) }
    }

    /**
     * FCM's new token for this install, from a messaging service of the app's
     * own (the SDK's own service, and the SDK at start, need no call): kept,
     * and registered with Graine when the workspace has Graine ring its users
     * ([GraineCalls.onNewToken]). Safe before anything is configured.
     */
    @JvmStatic
    fun onNewToken(context: Context, token: String) {
        remember(context)
        GraineCalls.onNewToken(context, token)
    }

    // ── the call ────────────────────────────────────────────────────────────

    /**
     * The customer calls the agent: `{reason?, variables?, agent?}`, from
     * [activity], which must be in the foreground; `agent` names one of the
     * agents `configure` set up (the default one unless named). The call's
     * id, or null when the calls are not configured, a call is live, there is
     * no activity, or no agent has that name.
     */
    @JvmStatic
    fun startCall(activity: Activity?, options: Map<*, *>?): String? = onMainSync { core.startCall(options, activity) }

    /**
     * Get the customer's next call ready before they tap — `startCall`'s
     * options, `{reason?, variables?, agent?}` — so a `startCall` with the
     * same reason and variables connects almost at once. Nothing is dialled
     * and nothing is billed until then, and the microphone is not touched.
     * Cheap to call whenever the call button appears (GraineCalls.prepareCall).
     */
    @JvmStatic
    fun prepareCall(options: Map<*, *>?) = onMain { core.prepareCall(options) }

    @JvmStatic
    fun answer() = onMain { core.answer() }

    @JvmStatic
    fun decline() = onMain { core.decline() }

    /** Hang up — or, while it rings, decline. */
    @JvmStatic
    fun end() = onMain { core.end() }

    @JvmStatic
    fun setMuted(muted: Boolean) = onMain { core.setMuted(muted) }

    @JvmStatic
    fun setSpeaker(on: Boolean) = onMain { core.setSpeaker(on) }

    /**
     * Calls start on the loudspeaker (true, the default) unless a headset is
     * connected, or on the earpiece (false) — [GraineCalls.speakerByDefault].
     * Applied now and kept with the options for the next cold start, as
     * `configure`'s `speakerByDefault` is; before `configure`, held until it
     * runs. For the call in progress, [setSpeaker].
     */
    @JvmStatic
    fun setSpeakerByDefault(on: Boolean) = onMain { core.setSpeakerByDefault(on) }

    // ── permissions and setup ───────────────────────────────────────────────

    /**
     * The call permissions: `{microphone, notifications, fullScreen}`, each
     * `granted`, `denied`, `blocked` or `notNeeded`. Safe before anything is
     * configured: a permission is the phone's.
     */
    @JvmStatic
    fun callPermissions(context: Context): Map<String, Any?> {
        remember(context)
        return CallPermissions.read(context).toMap()
    }

    /**
     * Ask for the call permissions the system's dialog can still ask for, from
     * [activity] (a transparent screen of the SDK's over it, so no result has
     * to be forwarded); [done] gets them as they stand after, on the main
     * thread. Never on its own: the app chooses when.
     */
    @JvmStatic
    fun requestCallPermissions(context: Context, activity: Activity?, done: (Map<String, Any?>) -> Unit) {
        remember(context)
        CallPermissions.request(context, activity) { done(it.toMap()) }
    }

    /** Open the Settings page for `microphone`, `notifications` or `fullScreen`: true when one opened; false for any other word. */
    @JvmStatic
    fun openCallSettings(context: Context, activity: Activity?, item: Any?): Boolean {
        remember(context)
        val permission = CallsHostValues.permission(item) ?: return false
        return onMainSync { CallPermissions.openSettings(context, activity, permission) }
    }

    /** What the app's setup still lacks for calls: `[{code, message}]`, empty when nothing is missing (and before anything is configured). */
    @JvmStatic
    fun callSetupProblems(): List<Map<String, Any?>> = device.calls?.callSetupProblems?.map { it.toMap() } ?: emptyList()

    // ── what the agent knows ────────────────────────────────────────────────

    /** Who is signed in (GraineInApp.identify), the RAW traits: the SDK masks them and reads the identity itself. Null on sign-out. */
    @JvmStatic
    fun setIdentity(traits: Map<*, *>?) = onMain { core.setIdentity(traits) }

    /**
     * Remember the signed-in user's conversations, or stop (GraineInApp.setMemory).
     * Before there is an instance the choice is held and applied when it is made.
     */
    @JvmStatic
    fun setMemory(enabled: Boolean) = onMain {
        val g = GraineInApp.peek()
        if (g != null) g.setMemory(enabled) else heldMemory = enabled
    }

    /**
     * This install's id (GraineInApp.installId) — the one id the native SDK,
     * React Native and Flutter share on a device. Safe before anything is configured.
     */
    @JvmStatic
    fun installId(context: Context): String {
        remember(context)
        return DeviceStore.of(context).installId()
    }

    @Volatile private var heldMemory: Boolean? = null

    /** The runtime variables become exactly this map: a key the last map set and this one leaves out is removed. */
    @JvmStatic
    fun setVariables(values: Map<*, *>?) = onMain { core.setVariables(values) }

    /**
     * The server token the wrapper's own token manager holds — on every change;
     * null when it has none. Every request the SDK makes to Graine carries it
     * from then on (calls' credentials and tickets, device registration, call
     * outcomes, …). When one is refused for its token, the sinks hear
     * `needClientToken` (an empty payload): fetch a fresh token (one question at
     * a time) and call this again; the request waits at most 5 seconds for it,
     * then goes once more. Before `configure` it is held and applied then. An
     * agent the app configured natively with its own
     * [GraineConfig.tokenProvider] keeps its own tokens and ignores this.
     * New in 0.14.4.
     */
    @JvmStatic
    fun setClientToken(token: String?) = onMain { core.setClientToken(token) }

    /** The screen, as `{screen, title?, journey?, fields?, error?, data?, availableActions?}`; null, or no screen name, for none. */
    @JvmStatic
    fun setScreen(screen: Map<*, *>?) = onMain { core.setScreen(screen) }

    /**
     * The actions the caller answers, and its sink ([addSink]'s token): each
     * request for one of them is sent as `action` to the sink that last listed
     * a non-empty set, and waits for [resolveAction] — within the SDK's own
     * six seconds. Names no longer listed are unregistered.
     */
    @JvmStatic
    fun setActions(names: List<*>?, sink: Int?) = onMain { core.setActions(names, sink) }

    /** The answer to an `action` request: `{status: "ok" | "refused" | "error", message?, data?}`. The first answer wins. */
    @JvmStatic
    fun resolveAction(requestId: String, result: Map<*, *>?) = onMain { core.resolveAction(requestId, result) }

    // ── events ──────────────────────────────────────────────────────────────

    /** Start receiving events. Outcomes and rings that came while nothing listened are delivered to the first sink, at once. */
    @JvmStatic
    fun addSink(sink: Sink): Int = onMainSync {
        core.addSink { event, payload ->
            // One wrapper's broken bridge must not keep the others from hearing the call.
            try {
                sink.onEvent(event, payload)
            } catch (t: Throwable) {
                Log.e(GraineInApp.TAG, "A calls sink failed on \"$event\".", t)
            }
        }
    }

    /** Stop. Action requests this sink had not answered are refused. */
    @JvmStatic
    fun removeSink(token: Int) = onMain { core.removeSink(token) }

    // ── what only the wrapper has ───────────────────────────────────────────

    /**
     * Forward every permission result from the activity that draws the call
     * when the app draws it (`callScreen: "app"`): the SDK asks for the
     * microphone from that screen. True when the request was the SDK's.
     */
    @JvmStatic
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean =
        device.calls?.onRequestPermissionsResult(requestCode, grantResults) ?: false

    /**
     * Forward the new intents of the activity that draws the call when the app
     * draws it (`callScreen: "app"`). The ring's Answer opens that activity
     * with the call in its intent, which GraineCalls reads when it resumes; an
     * activity that is already running — React Native's and Flutter's are
     * single-top — receives it as a new intent instead, which its own intent
     * then becomes. True when it was a call's.
     */
    @JvmStatic
    fun onNewIntent(activity: Activity, intent: Intent?): Boolean {
        val action = intent?.action
        if (action !in CALL_SCREEN_ACTIONS) return false
        activity.intent = intent
        return true
    }

    /**
     * True when the app draws the call (`callScreen: "app"`) and has no
     * microphone permission yet. A wrapper that cannot hear the answer to a
     * request made in its own activity (React Native's) asks before placing
     * or answering a call, and calls [microphoneDenied] when the customer says no.
     */
    @JvmStatic
    fun needsMicrophonePermission(context: Context): Boolean =
        onMainSync { core.appDrawsTheCall } &&
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED

    /** The customer refused the microphone a call needed: none was placed or answered, and the snapshot's `error` says why. */
    @JvmStatic
    fun microphoneDenied() = onMain { core.microphoneDenied() }

    // ── plumbing ────────────────────────────────────────────────────────────

    private fun remember(context: Context) {
        if (application == null) application = context.applicationContext as? Application
    }

    private fun prefs(): SharedPreferences? = application?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun <T> onMainSync(work: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return work()
        val task = FutureTask(Callable { work() })
        main.post(task)
        try {
            return task.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post { block() }
    }

    /** A resource of this app by its name, or null — logged, with what is used instead. */
    @SuppressLint("DiscouragedApi") // The options name their resources: a name is all JavaScript or Dart can send.
    private fun resource(context: Context, name: String?, type: String, option: String, instead: String): Int? {
        if (name == null) return null
        val id = context.resources.getIdentifier(name, type, context.packageName)
        if (id == 0) Log.w(GraineInApp.TAG, "$option \"$name\" is not a $type resource of this app: $instead.")
        return id.takeIf { it != 0 }
    }

    private fun ringtone(context: Context, sound: String?): Uri? {
        val name = CallsHostValues.rawResourceName(sound) ?: return null
        resource(context, name, "raw", "ringtoneSound", "the phone's ringtone rings") ?: return null
        return Uri.parse("android.resource://${context.packageName}/raw/$name")
    }

    /** The app's launch activity: it draws the call when the app chose its own screen (`callScreen: "app"`). */
    private fun launchActivity(context: Context): Class<out Activity>? {
        val name = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component?.className
        if (name == null) {
            Log.w(GraineInApp.TAG, "callScreen \"app\": this app has no launch activity, so the SDK's call screen is used.")
            return null
        }
        return try {
            Class.forName(name, false, context.classLoader).asSubclass(Activity::class.java)
        } catch (e: Exception) {
            Log.w(GraineInApp.TAG, "callScreen \"app\": the launch activity $name could not be loaded, so the SDK's call screen is used.", e)
            null
        }
    }

    /** The host's hands on Android: the SDK's agents and their calls, the app's resources, the main loop. Main thread. */
    private class Device : CallsHostPlatform, GraineCallListener {
        @Volatile private var graine: GraineInApp? = null
        /** The other agents the options named, by name: they run the wrapper's actions and variables too. */
        private val others = LinkedHashMap<String, GraineInApp>()
        /** The wrapper's actions, so an agent made later answers them too. */
        private val actionHandlers = LinkedHashMap<String, (JSONObject, (ActionResult) -> Unit) -> Unit>()
        /** The wrapper's runtime variables as they stand, so an agent made later has them too. */
        private val runtime = LinkedHashMap<String, Any?>()
        private val connected = CallsHostConnectedAt()

        /** The default agent and every other one the options named. */
        private fun agents(): List<GraineInApp> = (listOfNotNull(graine) + others.values).distinct()

        val calls: GraineCalls? get() = graine?.calls

        override fun makeInstance(options: CallsHostOptions): CallsHostInstance {
            val app = application ?: error("GraineCallsHost was handed no context")
            // The app may have made the instance itself; then the calls run on that one.
            val g = GraineInApp.peek() ?: GraineInApp.init(app, GraineConfig(
                baseUrl = options.baseUrl,
                publishableKey = options.publishableKey,
                appVersion = options.appVersion,
                debug = options.debug,
            ))
            graine = g
            g.calls.addListener(this)
            heldMemory?.let { g.setMemory(it); heldMemory = null }
            return CallsHostInstance(g.config.publishableKey, g.config.baseUrl, g.config.debug)
        }

        override fun makeAgents(agents: Map<String, CallsHostAgent>, options: CallsHostOptions) {
            val app = application ?: return
            for ((name, a) in agents) {
                val existing = others[name]
                if (existing != null) {
                    if (existing.config.publishableKey != a.publishableKey || existing.config.baseUrl.trimEnd('/') != a.baseUrl) {
                        Log.w(GraineInApp.TAG, "The \"$name\" agent runs with the key and base URL this launch started with; the new ones take effect at the next launch.")
                    }
                    continue
                }
                // The app may have made it itself; then the calls use that one.
                val g = GraineInApp.find(name) ?: GraineInApp.init(app, GraineConfig(
                    baseUrl = a.baseUrl,
                    publishableKey = a.publishableKey,
                    appVersion = options.appVersion,
                    debug = options.debug,
                ), name)
                others[name] = g
                actionHandlers.forEach { (action, handler) -> unregisters.getOrPut(action) { ArrayList() } += g.registerDeferredAction(action, handler) }
                if (runtime.isNotEmpty()) g.setVariables(LinkedHashMap(runtime))
            }
        }

        override fun configureCalls(options: CallsHostOptions) {
            val g = graine ?: return
            val app = application ?: return
            g.calls.configure(GraineCallsConfig(
                displayName = options.displayName,
                smallIcon = resource(app, options.androidSmallIcon, "drawable", "android.smallIcon", "the platform's call icon is used") ?: 0,
                ringtone = ringtone(app, options.ringtoneSound),
                callActivity = if (options.callScreen == CallsHostCallScreen.APP) launchActivity(app) else null,
                ringTimeoutMs = options.ringTimeoutMs,
                callTransport = options.callTransport,
            ))
            g.calls.speakerByDefault = options.speakerByDefault
        }

        override fun startCall(activity: Any?, reason: String?, variables: Map<String, Any?>): String? =
            startCall(activity, reason, variables, GraineInApp.DEFAULT)

        override fun startCall(activity: Any?, reason: String?, variables: Map<String, Any?>, agent: String): String? {
            val from = activity as? Activity ?: return null
            return calls?.startCall(from, reason, variables, agent)
        }

        override fun prepareCall(reason: String?, variables: Map<String, Any?>, agent: String) {
            calls?.prepareCall(reason, variables, agent)
        }

        override fun answer() { calls?.answer() }

        override fun decline() { calls?.decline() }

        override fun end() { calls?.end() }

        override fun setMuted(muted: Boolean) { calls?.setMuted(muted) }

        override fun setSpeaker(on: Boolean) { calls?.setSpeaker(on) }

        override fun setSpeakerByDefault(on: Boolean) { calls?.speakerByDefault = on }

        // Who the user is and the screen are the app's: on one agent, every agent has them.
        override fun identify(traits: Map<String, Any?>?) { graine?.identify(traits) }

        override fun setVariables(values: Map<String, Any?>) {
            values.forEach { (k, v) -> if (v == null) runtime.remove(k) else runtime[k] = v }
            agents().forEach { it.setVariables(values) }
        }

        override fun setScreen(screen: GraineScreen?) { graine?.setScreen(screen) }

        // Server tokens are the app's: every agent sends the one pushed, and asks the wrapper for another when refused.
        override fun setClientToken(token: String?) = GraineInApp.pushClientToken(token)

        override fun askForClientTokens(ask: () -> Unit) = GraineInApp.askHostForTokens(ask)

        /** Unregistering each action from every agent it was registered on. */
        private val unregisters = HashMap<String, MutableList<() -> Unit>>()

        /** On every agent: a call with any of them may ask for it. */
        override fun registerAction(name: String, handler: (JSONObject, (ActionResult) -> Unit) -> Unit): () -> Unit {
            actionHandlers[name] = handler
            val mine = agents().map { it.registerDeferredAction(name, handler) }
            unregisters[name] = ArrayList(mine)
            return {
                if (actionHandlers[name] === handler) actionHandlers.remove(name)
                unregisters.remove(name)?.forEach { it() }
            }
        }

        override fun handlePush(data: Map<String, String>): Boolean {
            val g = graine ?: GraineInApp.peek()
            if (g != null) return g.calls.handlePush(data)
            if (AgentCallPush.parse(data) == AgentCallPush.NotOurs) return false
            Log.w(GraineInApp.TAG, "A call push arrived before the calls were ever configured, so it cannot ring. Configure them at launch.")
            return true
        }

        override fun callFields(): CallsHostSnapshot {
            val c = calls ?: return CallsHostSnapshot()
            val call = c.call
            return CallsHostSnapshot(
                state = c.state,
                callId = call?.callId,
                direction = call?.direction,
                reason = call?.reason,
                callerName = call?.callerName,
                muted = c.muted,
                speakerOn = c.speaker,
                agentSpeaking = c.agentSpeaking,
                reconnecting = c.reconnecting,
                caption = c.caption?.let { CallsHostSnapshot.Caption(it.first, it.second, it.third) },
                connectedAt = connected.epochMs(c.connectedAt, SystemClock.elapsedRealtime(), System.currentTimeMillis()),
                agent = call?.let { c.agentFor(it.callId).name },
                transport = c.transport,
                transportFallbackReason = c.transportFallbackReason,
                connection = c.connection,
                agentLevel = CallsHostSnapshot.level(c.agentLevel),
            )
        }

        /** The agent a call (by id) is with: placed with, or rung for — else the default. */
        override fun agentOf(callId: String): String? = calls?.agentFor(callId)?.name

        override fun nextTurn(block: () -> Unit) { main.post { block() } }

        override fun after(ms: Long, block: () -> Unit) { main.postDelayed({ block() }, ms) }

        override fun log(message: String) { Log.w(GraineInApp.TAG, message) }

        // GraineCallListener: what the engine reports, on the main thread.

        override fun onIncomingCall(call: GraineCall) = core.incoming(call)

        override fun onCallState(state: GraineCallState, call: GraineCall?) = core.callStateChanged(state)

        override fun onCallOutcome(callId: String, outcome: GraineCallOutcome) = core.outcome(outcome, callId)

        override fun onCallCaption(role: Role, text: String, live: Boolean) = core.engineChanged()

        override fun onAgentSpeaking(speaking: Boolean) = core.engineChanged()

        override fun onCallReconnecting(reconnecting: Boolean) = core.engineChanged()

        override fun onCallControls(muted: Boolean, speaker: Boolean) = core.engineChanged()

        override fun onCallConnection(connection: GraineCallConnection) = core.engineChanged()

        override fun onAgentLevel(level: Float) = core.engineChanged()
    }
}
