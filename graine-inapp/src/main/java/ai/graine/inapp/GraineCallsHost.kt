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
 *     GraineCallsHost.handlePush(context, message.data)   // from the messaging service, before any JavaScript or Dart
 *     GraineCallsHost.configure(context, mapOf("publishableKey" to "pk_…", "displayName" to "Acme"))
 *     val sink = GraineCallsHost.addSink { event, payload -> … }
 *
 * Events: `state` (the snapshot, at most once per turn of the main loop),
 * `outcome`, `incoming` and `action` (`voipToken` is iOS's). Main thread: a
 * call from another thread hops there first (`configure`, `restore`,
 * `snapshot`, `handlePush`, `startCall`, `addSink` and
 * `needsMicrophonePermission` wait for their answer), and events are sent on it.
 */
object GraineCallsHost {
    /** Receives every event: its name (`state`, `outcome`, `incoming`, `action`) and its payload. Main thread. */
    fun interface Sink {
        fun onEvent(event: String, payload: Map<String, Any?>)
    }

    private const val PREFS = "ai.graine.inapp.calls"
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

    /** iOS registers for VoIP pushes here. Android has none: the app hands its pushes to [handlePush]. */
    @JvmStatic
    fun enableVoIPPushes() {}

    /**
     * A push's data, from the app's messaging service: `{String: String}`.
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

    // ── the call ────────────────────────────────────────────────────────────

    /**
     * The customer calls the agent: `{reason?, variables?}`, from [activity],
     * which must be in the foreground. The call's id, or null when the calls
     * are not configured, a call is live, or there is no activity.
     */
    @JvmStatic
    fun startCall(activity: Activity?, options: Map<*, *>?): String? = onMainSync { core.startCall(options, activity) }

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

    // ── what the agent knows ────────────────────────────────────────────────

    /** Who is signed in (GraineInApp.identify); null on sign-out. */
    @JvmStatic
    fun setIdentity(traits: Map<*, *>?) = onMain { core.setIdentity(traits) }

    /** The runtime variables become exactly this map: a key the last map set and this one leaves out is removed. */
    @JvmStatic
    fun setVariables(values: Map<*, *>?) = onMain { core.setVariables(values) }

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
        if (action != GraineCalls.ACTION_ANSWER && action != GraineCalls.ACTION_SHOW) return false
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

    /** The host's hands on Android: the SDK instance and its calls, the app's resources, the main loop. Main thread. */
    private class Device : CallsHostPlatform, GraineCallListener {
        @Volatile private var graine: GraineInApp? = null
        private val connected = CallsHostConnectedAt()

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
            return CallsHostInstance(g.config.publishableKey, g.config.baseUrl, g.config.debug)
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
            ))
        }

        override fun startCall(activity: Any?, reason: String?, variables: Map<String, Any?>): String? {
            val from = activity as? Activity ?: return null
            return calls?.startCall(from, reason, variables)
        }

        override fun answer() { calls?.answer() }

        override fun decline() { calls?.decline() }

        override fun end() { calls?.end() }

        override fun setMuted(muted: Boolean) { calls?.setMuted(muted) }

        override fun setSpeaker(on: Boolean) { calls?.setSpeaker(on) }

        override fun identify(traits: Map<String, Any?>?) { graine?.identify(traits) }

        override fun setVariables(values: Map<String, Any?>) { graine?.setVariables(values) }

        override fun setScreen(screen: GraineScreen?) { graine?.setScreen(screen) }

        override fun registerAction(name: String, handler: (JSONObject, (ActionResult) -> Unit) -> Unit): () -> Unit =
            graine?.registerDeferredAction(name, handler) ?: {}

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
                caption = c.caption?.let { CallsHostSnapshot.Caption(it.first, it.second, it.third) },
                connectedAt = connected.epochMs(c.connectedAt, SystemClock.elapsedRealtime(), System.currentTimeMillis()),
            )
        }

        override fun nextTurn(block: () -> Unit) { main.post { block() } }

        override fun after(ms: Long, block: () -> Unit) { main.postDelayed({ block() }, ms) }

        override fun log(message: String) { Log.w(GraineInApp.TAG, message) }

        // GraineCallListener: what the engine reports, on the main thread.

        override fun onIncomingCall(call: GraineCall) = core.incoming(call)

        override fun onCallState(state: GraineCallState, call: GraineCall?) = core.callStateChanged(state)

        override fun onCallOutcome(callId: String, outcome: GraineCallOutcome) = core.outcome(outcome, callId)

        override fun onCallCaption(role: Role, text: String, live: Boolean) = core.engineChanged()

        override fun onAgentSpeaking(speaking: Boolean) = core.engineChanged()

        override fun onCallControls(muted: Boolean, speaker: Boolean) = core.engineChanged()
    }
}
