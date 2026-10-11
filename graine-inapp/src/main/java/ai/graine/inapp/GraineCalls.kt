package ai.graine.inapp

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * CALLS LIKE WHATSAPP — the agent rings the customer, or the customer calls the agent.
 *
 *     // Application.onCreate, after GraineInApp.init:
 *     GraineInApp.get().calls.configure(GraineCallsConfig(displayName = "Acme", smallIcon = R.drawable.ic_call))
 *
 *     // FirebaseMessagingService.onMessageReceived:
 *     if (GraineInApp.get().calls.handlePush(message.data)) return
 *
 *     // A "Call support" button:
 *     GraineInApp.get().calls.startCall(activity, reason = "kyc_help")
 *
 * The call's audio runs natively, in the app's own process, over the voice
 * socket — the platform's microphone and echo canceller, not a web view — so
 * it rings (a call notification with a full-screen intent), it is answered
 * or declined, it keeps going with the screen off (a microphone foreground
 * service), and its outcome comes back through [GraineCallListener] with the
 * call's id for the app's server. One call at a time, and never alongside a
 * web view call: starting one ends the other.
 *
 * Public methods are main-thread, except [handlePush], which is safe from the
 * messaging service's thread. Listeners are called on the main thread.
 */
class GraineCalls internal constructor(private val graine: GraineInApp) {

    /** How calls look and ring. */
    @Volatile var config: GraineCallsConfig = GraineCallsConfig()
        private set

    private val app: Application get() = graine.application
    private val main get() = graine.main
    private val listeners = CopyOnWriteArraySet<GraineCallListener>()
    private val machine = CallMachine(
        schedule = { delayMs, task -> val r = Runnable { task() }; main.postDelayed(r, delayMs); ({ main.removeCallbacks(r) }) },
        ringTimeoutMs = { config.ringTimeoutMs },
        onState = { s, c -> onState(s, c) },
        onOutcome = { id, o -> report { it.onCallOutcome(id, o) } },
    )

    /** Where the call is. Changes reach [GraineCallListener.onCallState]. */
    @Volatile var state: GraineCallState = GraineCallState.IDLE
        private set

    /** The call in progress, or null. */
    @Volatile var call: GraineCall? = null
        private set

    /** The customer's microphone is muted (the call goes on; nothing they say is sent). */
    @Volatile var muted = false
        private set

    /** The customer chose the loudspeaker (else the call is on a headset, if one is connected, or the earpiece). */
    @Volatile var speaker = false
        private set

    /** The agent's voice is being heard. */
    @Volatile var agentSpeaking = false
        private set

    /** The last line on the call, for a screen that opens mid-call: role, words, still arriving. */
    @Volatile var caption: Triple<Role, String, Boolean>? = null
        private set

    /** When the call connected (SystemClock.elapsedRealtime millis, which keep counting in deep sleep), for a duration display; 0 before. */
    @Volatile var connectedAt = 0L
        private set

    private val scheduler by lazy { ExecutorScheduler("graine-call") }
    private var session: VoiceCallSession? = null
    private var route: AudioRoute? = null
    private var unwatchContext: (() -> Unit)? = null
    private var service = WeakReference<GraineCallService>(null)
    private var resumed = WeakReference<Activity>(null)
    private var micPending = false
    private var micAsked = false
    private val micGiveUp = Runnable {
        if (micPending) { micPending = false; machine.fail("The microphone permission was not granted.") }
    }

    init {
        CallLog.enabled = graine.config.debug
        CallLog.sink = { message, t -> if (t != null) Log.e(GraineInApp.TAG, message, t) else Log.d(GraineInApp.TAG, message) }
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { onResumed(a) }
            override fun onActivityPaused(a: Activity) { if (resumed.get() === a) resumed = WeakReference(null) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    fun addListener(l: GraineCallListener) { listeners.add(l) }
    fun removeListener(l: GraineCallListener) { listeners.remove(l) }

    /** Set how calls look and ring. Call once, in Application.onCreate. Creates the notification channels. */
    fun configure(config: GraineCallsConfig) {
        this.config = config
        runCatching { ensureChannels() }
    }

    // ── an agent calls the customer ─────────────────────────────────────────

    /**
     * Hand every FCM data message here first. True when it was a Graine call
     * (`type: "agent_call"`) — ringing, refused as busy, a repeat of the call
     * in progress, or malformed — and the app should do nothing more with it;
     * false when it is the app's own.
     * Safe on the messaging service's thread.
     */
    fun handlePush(data: Map<String, String>): Boolean {
        return when (val push = AgentCallPush.parse(data)) {
            AgentCallPush.NotOurs -> false
            is AgentCallPush.Malformed -> {
                Log.w(GraineInApp.TAG, "An agent_call push was ignored: ${push.reason}.")
                true
            }
            is AgentCallPush.Call -> {
                onMain { incoming(push) }
                true
            }
        }
    }

    private fun incoming(push: AgentCallPush.Call) {
        // Masked like every variable the app hands over: nothing leaves the device unmasked.
        val masked = push.copy(variables = push.variables.mapValues { Masking.mask(it.value) })
        // Busy: reported missed (or ignored, when it is the live call pushed again), the live call untouched.
        val c = machine.incoming(masked) ?: return
        report { it.onIncomingCall(c) }
    }

    /** Pick up the ringing call. The SDK's call screen does this; an app's own screen calls it. */
    fun answer() = onMain {
        if (!machine.answer()) return@onMain
        graine.endWebViewCall()
        begin()
    }

    /** Decline the ringing call. */
    fun decline() = onMain { machine.decline() }

    internal fun decline(callId: String?) = onMain {
        if (callId == null || callId == machine.call?.callId) machine.decline()
        if (state != GraineCallState.RINGING) cancelRing()
    }

    // ── the customer calls the agent ────────────────────────────────────────

    /**
     * Call the agent: the call screen opens and the call connects. `reason`
     * becomes the prompt's `call_reason` (and `who_called` is "customer");
     * `variables` are this call's own prompt variables, over the SDK's.
     * Returns the call's id — every outcome carries it — or null when a call
     * is already live. Main thread; [activity] must be in the foreground (the
     * microphone service may only start from there).
     */
    @JvmOverloads
    fun startCall(activity: Activity, reason: String? = null, variables: Map<String, Any?> = emptyMap()): String? {
        check(Looper.myLooper() == Looper.getMainLooper()) { "GraineCalls.startCall must be called on the main thread" }
        if (machine.live) return null
        graine.endWebViewCall()
        val id = "call_" + UUID.randomUUID().toString()
        val vars = VariableStore.flatten(variables).mapValues { Masking.mask(it.value) }
        val c = machine.outgoing(id, reason?.takeIf { it.isNotBlank() }, vars) ?: return null
        resumed = WeakReference(activity)
        openCallScreen(activity, c, ACTION_SHOW)
        begin()
        return id
    }

    /** Hang up — or, while ringing, decline. */
    /** The phone took the call's audio, gave it back, or the customer answered a phone call during the hold. */
    private fun interrupted(callId: String, i: CallInterruption) {
        if (machine.call?.callId != callId) return
        when (i) {
            CallInterruption.HELD -> { CallLog.debug("on hold: the phone took the call's audio"); session?.setOnHold(true) }
            CallInterruption.RESUMED -> { CallLog.debug("the call's audio is back"); session?.setOnHold(false) }
            CallInterruption.PHONE_CALL -> { CallLog.error("a phone call was answered during the hold; the agent's call ends"); end() }
        }
    }

    fun end() = onMain {
        when (machine.state) {
            GraineCallState.RINGING -> machine.decline()
            GraineCallState.CONNECTING, GraineCallState.ACTIVE -> machine.end(GraineCallOutcome.EndReason.LOCAL)
            else -> Unit
        }
    }

    /** Stop sending the customer's voice without ending the call. */
    fun setMuted(value: Boolean) = onMain {
        if (muted == value) return@onMain
        muted = value
        session?.setMuted(value)
        report { it.onCallControls(muted, speaker) }
    }

    /** The loudspeaker on or off. Off, the call goes back to a headset if one is connected (Bluetooth first), else the earpiece. */
    fun setSpeaker(on: Boolean) = onMain {
        if (speaker == on) return@onMain
        speaker = on
        route?.setSpeaker(on)
        report { it.onCallControls(muted, speaker) }
    }

    /**
     * Forward from an activity of your own that shows the call: the SDK asks
     * for the microphone from the call screen when it does not have it. The
     * SDK's GraineCallActivity forwards by itself. True when the request was
     * the SDK's.
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != MIC_REQUEST) return false
        onMain {
            if (!micPending) return@onMain
            micPending = false
            main.removeCallbacks(micGiveUp)
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (!granted) machine.fail("The microphone permission was denied.")
            else if (machine.state == GraineCallState.CONNECTING) startService()
        }
        return true
    }

    // ── connecting ──────────────────────────────────────────────────────────

    /** The call is CONNECTING: microphone permission, then the foreground service, then the session. */
    private fun begin() {
        if (hasMic()) { startService(); return }
        micPending = true
        micAsked = false
        main.removeCallbacks(micGiveUp)
        main.postDelayed(micGiveUp, MIC_WAIT_MS)
        resumed.get()?.takeIf { isCallScreen(it) }?.let { askForMic(it) }
    }

    private fun askForMic(a: Activity) {
        if (!micPending || micAsked || a.isFinishing) return
        micAsked = true
        a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
    }

    private fun hasMic(): Boolean =
        app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun startService() {
        val intent = Intent(app, GraineCallService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent) else app.startService(intent)
        } catch (e: Exception) {
            // Android forbids a microphone service started from the background; the call screen starts it.
            machine.fail("The call could not start: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** GraineCallService is in the foreground: the call may record now. */
    internal fun serviceStarted(s: GraineCallService) {
        service = WeakReference(s)
        if (!machine.live || machine.state == GraineCallState.RINGING) { s.stopNow(); return }
        if (session == null) connect()
    }

    internal fun serviceFailed(e: Exception) = onMain {
        machine.fail("The call's foreground service could not start: ${e.message ?: e.javaClass.simpleName}")
    }

    /** The system stopped the service under a live call: the call cannot go on. */
    internal fun serviceStopped(s: GraineCallService) = onMain {
        if (service.get() === s) service = WeakReference(null)
        if (machine.state == GraineCallState.CONNECTING || machine.state == GraineCallState.ACTIVE) {
            machine.fail("The call's foreground service was stopped.")
        }
    }

    private fun connect() {
        val c = machine.call ?: return
        graine.loadSession { s ->
            if (machine.call?.callId != c.callId || machine.state != GraineCallState.CONNECTING || session != null) return@loadSession
            if (s == null) { machine.fail("The agent could not be loaded."); return@loadSession }
            route = AudioRoute(app) { i -> onMain { interrupted(c.callId, i) } }.also { it.acquire(speaker) }
            val events = object : CallSessionEvents {
                override fun onOpen() = onMain {
                    if (machine.call?.callId == c.callId && machine.connected()) {
                        connectedAt = android.os.SystemClock.elapsedRealtime()
                        updateOngoing()
                    }
                }
                override fun onFailed(error: String) = onMain { if (machine.call?.callId == c.callId) machine.fail(error) }
                override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) = onMain {
                    if (machine.call?.callId == c.callId) machine.end(reason, error)
                }
                override fun onCaption(role: Role, text: String, live: Boolean) = onMain {
                    if (machine.call?.callId != c.callId) return@onMain
                    caption = Triple(role, text, live)
                    report { it.onCallCaption(role, text, live) }
                }
                override fun onAgentSpeaking(speaking: Boolean) = onMain {
                    if (machine.call?.callId != c.callId) return@onMain
                    agentSpeaking = speaking
                    report { it.onAgentSpeaking(speaking) }
                }
            }
            val sess = VoiceCallSession(
                CallSessionConfig(
                    baseUrl = graine.config.baseUrl,
                    publishableKey = graine.config.publishableKey,
                    agentId = s.agentId,
                    webcallAgentId = s.webcallAgentId,
                    wsHost = s.wsHost,
                    callVariables = c.variables,
                    sdkVariables = graine.currentVariables(),
                ),
                CallSessionDeps(
                    scheduler = scheduler,
                    audio = AndroidCallAudio(),
                    // The SDK's own runner, on the main thread as every handler expects; it always answers.
                    runAction = { name, args, answer -> main.post { graine.executeAction(name, args, answer) } },
                    contextFrame = { graine.currentContextFrame() },
                ),
                events,
            )
            session = sess
            sess.setMuted(muted)
            unwatchContext = graine.onContext { frame -> session?.sendContext(frame) }
            sess.start()
        }
    }

    // ── state ───────────────────────────────────────────────────────────────

    private fun onState(s: GraineCallState, c: GraineCall?) {
        state = s
        call = c
        when (s) {
            GraineCallState.RINGING -> c?.let { ring(it) }
            GraineCallState.CONNECTING -> cancelRing()
            GraineCallState.ACTIVE -> Unit
            GraineCallState.ENDED -> teardown()
            GraineCallState.IDLE -> Unit
        }
        report { it.onCallState(s, c) }
    }

    /** Everything the call held, released; the hang-up frames go out if the line was up. */
    private fun teardown() {
        cancelRing()
        main.removeCallbacks(micGiveUp)
        micPending = false
        session?.hangUp()
        session = null
        unwatchContext?.invoke(); unwatchContext = null
        route?.release(); route = null
        // Never stopService() here: a service started with startForegroundService() that is stopped
        // before it calls startForeground() crashes the app. One still starting stops itself in
        // serviceStarted(), finding no live call.
        service.get()?.stopNow()
        service = WeakReference(null)
        notifications()?.cancel(ONGOING_ID)
        muted = false
        speaker = false
        agentSpeaking = false
        caption = null
        connectedAt = 0L
    }

    private fun onResumed(a: Activity) {
        resumed = WeakReference(a)
        if (!isCallScreen(a)) return
        val intent = a.intent
        val c = machine.call
        // Answer from the notification: the call screen opened with ACTION_ANSWER for this call.
        if (intent?.action == ACTION_ANSWER && c != null && intent.getStringExtra(EXTRA_CALL_ID) == c.callId) {
            a.intent = Intent(intent).setAction(ACTION_SHOW)
            if (machine.state == GraineCallState.RINGING) answer()
        }
        if (micPending) askForMic(a)
    }

    private fun isCallScreen(a: Activity): Boolean = a.javaClass == callScreenClass()

    private fun callScreenClass(): Class<out Activity> = config.callActivity ?: GraineCallActivity::class.java

    private fun openCallScreen(from: Context, c: GraineCall, action: String) {
        val i = Intent(from, callScreenClass()).setAction(action).putExtra(EXTRA_CALL_ID, c.callId)
        if (from !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { from.startActivity(i) }.onFailure { CallLog.error("could not open the call screen", it) }
    }

    // ── notifications ───────────────────────────────────────────────────────

    private fun notifications(): NotificationManager? = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun ringtone(): Uri =
        config.ringtone ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE) ?: Settings.System.DEFAULT_RINGTONE_URI

    /** A channel's sound is fixed once created, so a different ringtone is a different channel. */
    private fun ringChannelId(): String = RING_CHANNEL + "." + Integer.toHexString(ringtone().toString().hashCode())

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notifications() ?: return
        val id = ringChannelId()
        if (nm.getNotificationChannel(id) == null) {
            nm.notificationChannels.filter { it.id.startsWith("$RING_CHANNEL.") && it.id != id }.forEach { nm.deleteNotificationChannel(it.id) }
            nm.createNotificationChannel(NotificationChannel(id, "Incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Calls from the assistant"
                setSound(ringtone(), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                enableVibration(true)
                vibrationPattern = VIBRATION
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
        if (nm.getNotificationChannel(ONGOING_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "Ongoing calls", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A call with the assistant in progress"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            })
        }
    }

    private fun builder(channel: String): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, channel) else @Suppress("DEPRECATION") Notification.Builder(app)

    private fun smallIcon(): Int = config.smallIcon.takeIf { it != 0 } ?: android.R.drawable.sym_call_incoming

    private fun flags(): Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun screenIntent(c: GraineCall, action: String, requestCode: Int): PendingIntent {
        val i = Intent(app, callScreenClass()).setAction(action).putExtra(EXTRA_CALL_ID, c.callId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(app, requestCode, i, flags())
    }

    private fun receiverIntent(c: GraineCall?, action: String, requestCode: Int): PendingIntent {
        val i = Intent(app, GraineCallReceiver::class.java).setAction(action)
        c?.let { i.putExtra(EXTRA_CALL_ID, it.callId) }
        return PendingIntent.getBroadcast(app, requestCode, i, flags())
    }

    private fun callerName(c: GraineCall): String = c.callerName ?: config.displayName

    /** The ring: a call notification that rings until answered, declined or missed, over the lock screen. */
    private fun ring(c: GraineCall) {
        val nm = notifications() ?: return
        runCatching { ensureChannels() }
        val name = callerName(c)
        val answer = screenIntent(c, ACTION_ANSWER, REQUEST_ANSWER)
        val decline = receiverIntent(c, ACTION_DECLINE, REQUEST_DECLINE)
        val b = builder(ringChannelId())
            .setSmallIcon(smallIcon())
            .setContentTitle(name)
            .setContentText("Incoming call")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(screenIntent(c, ACTION_SHOW, REQUEST_SHOW))
            .setFullScreenIntent(screenIntent(c, ACTION_SHOW, REQUEST_FULL_SCREEN), true)
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(config.ringTimeoutMs)
        if (Build.VERSION.SDK_INT >= 31) {
            val person = Person.Builder().setName(name).setImportant(true).build()
            b.setStyle(Notification.CallStyle.forIncomingCall(person, decline, answer))
        } else {
            @Suppress("DEPRECATION")
            b.setPriority(Notification.PRIORITY_MAX)
            if (Build.VERSION.SDK_INT < 26) {
                @Suppress("DEPRECATION")
                b.setSound(ringtone(), AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                @Suppress("DEPRECATION")
                b.setVibrate(VIBRATION)
            }
            b.addAction(Notification.Action.Builder(Icon.createWithResource(app, android.R.drawable.ic_menu_close_clear_cancel), "Decline", decline).build())
            b.addAction(Notification.Action.Builder(Icon.createWithResource(app, android.R.drawable.ic_menu_call), "Answer", answer).build())
        }
        val n = b.build()
        n.flags = n.flags or Notification.FLAG_INSISTENT
        runCatching { nm.notify(RING_ID, n) }.onFailure { CallLog.error("could not post the ring", it) }
        // In front already: show the call screen itself, as a call app does.
        resumed.get()?.let { openCallScreen(it, c, ACTION_SHOW) }
    }

    private fun cancelRing() { notifications()?.cancel(RING_ID) }

    /** The foreground service's notification: who, how long, and Hang up. */
    internal fun ongoingNotification(context: Context): Notification {
        runCatching { ensureChannels() }
        val c = machine.call
        val name = c?.let { callerName(it) } ?: config.displayName
        val hangUp = receiverIntent(c, ACTION_HANGUP, REQUEST_HANGUP)
        val b = builder(ONGOING_CHANNEL)
            .setSmallIcon(smallIcon())
            .setContentTitle(name)
            .setContentText(if (state == GraineCallState.ACTIVE) "On a call" else "Connecting…")
            .setCategory(Notification.CATEGORY_CALL)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (c != null) b.setContentIntent(screenIntent(c, ACTION_SHOW, REQUEST_SHOW))
        if (connectedAt > 0) {
            b.setUsesChronometer(true)
            b.setWhen(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - connectedAt))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            b.setStyle(Notification.CallStyle.forOngoingCall(Person.Builder().setName(name).setImportant(true).build(), hangUp))
        } else {
            b.addAction(Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel), "Hang up", hangUp).build())
        }
        return b.build()
    }

    private fun updateOngoing() {
        if (service.get() == null) return
        runCatching { notifications()?.notify(ONGOING_ID, ongoingNotification(app)) }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private inline fun report(crossinline f: (GraineCallListener) -> Unit) {
        listeners.forEach { l -> runCatching { f(l) }.onFailure { CallLog.error("a call listener threw", it) } }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        /** The call screen was opened to answer (from the notification's Answer). */
        const val ACTION_ANSWER = "ai.graine.inapp.action.ANSWER_CALL"
        /** The call screen was opened to show the call. */
        const val ACTION_SHOW = "ai.graine.inapp.action.SHOW_CALL"
        const val ACTION_DECLINE = "ai.graine.inapp.action.DECLINE_CALL"
        const val ACTION_HANGUP = "ai.graine.inapp.action.HANG_UP"
        /** Which call an intent is about. */
        const val EXTRA_CALL_ID = "ai.graine.inapp.extra.CALL_ID"
        /** The request code the SDK uses for the call's microphone prompt. */
        const val MIC_REQUEST = 0x6E7B

        internal const val RING_ID = 0x67726E67
        internal const val ONGOING_ID = 0x67726E68
        internal const val RING_CHANNEL = "graine.calls.ring"
        internal const val ONGOING_CHANNEL = "graine.calls.ongoing"
        private const val REQUEST_SHOW = 0x6E70
        private const val REQUEST_FULL_SCREEN = 0x6E71
        private const val REQUEST_ANSWER = 0x6E72
        private const val REQUEST_DECLINE = 0x6E73
        private const val REQUEST_HANGUP = 0x6E74
        private const val MIC_WAIT_MS = 60_000L
        private val VIBRATION = longArrayOf(0, 900, 700)
    }
}
