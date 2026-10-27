package ai.graine.inapp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.MutableContextWrapper
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.lang.ref.WeakReference

/**
 * THE CALL — a hosted page in a WebView this SDK owns, one invisible pixel big.
 *
 * Why a WebView and not a native WebRTC stack: every fix to the audio path —
 * the TURN relay, the dead-media watchdog, connection quality, the WebSocket
 * fallback, echo handling — lives in that page and reaches your installed app
 * the day it ships, with no release. A native media stack would make each of
 * those an app-store update. The trade is a page load, which this class
 * prewarms at attach time and caches per deployment, so a tap costs one SIP
 * registration and an INVITE.
 *
 * Laid out at 1x1 rather than GONE: a view with no box can have its media
 * suspended by the platform, which would kill the call the moment your own UI
 * hid it.
 *
 * Every public method is main-thread only.
 */
class GraineVoice internal constructor(private val graine: GraineInApp) {

    // ── state you can read, and hear about through GraineListener ───────────
    var connected = false; private set
    var connecting = false; private set
    var muted = false; private set
    var ready = false
        private set(value) { field = value; rearm() }
    var quality = ConnectionQuality.UNKNOWN; private set
    var online: Boolean? = null; private set
    var reconnecting = false; private set
    var connectivity = Connectivity.LOADING; private set

    /**
     * The call cannot work as configured until something is fixed — an agent
     * or key that was never set up for calls (the engine reports it as
     * `not_configured`). Null otherwise. Also sent to onError.
     * Cleared when the next call is placed.
     */
    var error: String? = null; private set

    /**
     * Where a call being placed is, as the engine reports it — registering,
     * dialling, retrying, switching ([ConnectStage]). Null outside a connect.
     * A bar that says what is happening across a retry is one the customer
     * waits for; "Connecting…" for eight seconds is one they close.
     */
    val connectStage: ConnectStage? get() = progress.stage

    /**
     * The customer has waited on this connect past [GraineConfig.Timing.slowConnectMs]
     * (4 s): say so — "Still connecting…" — honestly, not with a faster
     * spinner. Only while they ARE waiting: a call they placed, or a bar of
     * this SDK open on a page not yet ready.
     */
    val slowConnect: Boolean get() = progress.slow

    /**
     * The last start could not be connected at all — not on WebRTC, not on
     * the fallback. Distinct from [error] (a setting someone must change): the
     * network may well be better in a moment, so [start] simply tries again.
     * Cleared by the next start, by a call that connects, and when the last
     * of this SDK's bars leaves the screen.
     */
    val callFailed: Boolean get() = progress.failed

    /**
     * What has been said this call, oldest first — the agent's turns and the
     * customer's. A live turn is replaced as its words arrive, not appended;
     * the last 12 are kept, and a new call starts empty: a transcript outliving
     * its call is a privacy question, not a feature. [GraineListener.onCaption]
     * hears each line as it comes.
     */
    var captions: List<GraineCaption> = emptyList()
        private set

    /**
     * The captions switch — CC. The SDK's bar shows the line being said only
     * while it is on. On by default: a caption nobody asked to hide is an
     * accessibility default. What is said is kept ([captions]) either way.
     */
    var captionsOn = true
        private set

    /** Turn the captions on or off — for your own CC button. The call goes on. */
    fun setCaptionsOn(on: Boolean) {
        if (on == captionsOn) return
        captionsOn = on
        captionsChanged()
    }

    /** Forget what has been said ([captions]) without ending the call. */
    fun clearCaptions() {
        if (captions.isEmpty()) return
        captions = emptyList()
        captionsChanged()
    }

    private val captionWatchers = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()

    /** The SDK's bars, told when the switch moves or the captions are cleared. Returns what stops it. */
    internal fun onCaptionsChange(watch: () -> Unit): () -> Unit {
        captionWatchers.add(watch)
        return { captionWatchers.remove(watch) }
    }

    private fun captionsChanged() = captionWatchers.forEach { runCatching { it() } }

    /** Whether the engine is mounted: [attach] was called, and [detach] was not since. */
    val attached: Boolean get() = webView != null

    /**
     * A voice UI of your own came on screen ([viewOpened]) or left it
     * ([viewClosed]) — the SDK's bars say so themselves. While one is open, a
     * page still loading is the customer's wait, reported as [slowConnect];
     * when the last one goes, a failed connect and its stage are no longer
     * news. Call them in pairs. Main thread.
     */
    fun viewOpened() {
        appViews++
        if (appViews == 1) barShown(appViewsToken, true)
    }

    /** The UI from [viewOpened] left the screen. */
    fun viewClosed() {
        if (appViews == 0) return
        appViews--
        if (appViews == 0) barShown(appViewsToken, false)
    }

    /** Voice UIs of the app's own on screen, counted as one bar while any is. */
    private var appViews = 0
    private val appViewsToken = Any()

    /**
     * What the call runs on — [Transport.WEBRTC] or [Transport.WEBSOCKET] —
     * from the moment the call is placed: what the voice engine was loaded
     * to use, or the WebSocket once it reports a fallback
     * ([GraineListener.onTransport] hears that). Null before any call; kept
     * after a call ends, until the next one starts.
     */
    var transport: Transport? = null
        private set

    /**
     * What the voice engine carries its calls on: what it was loaded with
     * ([load]), until it reports a fallback — after which it stays on the
     * WebSocket, with that reason, until it is loaded again.
     */
    private var engineTransport: Transport? = null
    private var engineFallbackReason: String? = null
    /** The call in progress has been told what it runs on: a fallback during it is the only change after. */
    private var callTransportTold = false

    /**
     * Why a call that asked for WebRTC runs on the WebSocket instead
     * (`mint_failed`, `no_answer`, `ice_timeout`, …, as the engine said it).
     * Null when it did not fall back.
     */
    var transportFallbackReason: String? = null
        private set

    /**
     * How loud the agent's voice is as it plays, 0–1, as the call page
     * measures it — about five times a second while a call is live, and 0
     * otherwise. For a meter or a visualiser of your own; each change reaches
     * [GraineListener.onLevels]. New in 0.14.1.
     */
    var agentLevel = 0f
        private set

    /** How loud the customer's microphone is, 0–1 — as [agentLevel], and 0 while muted. New in 0.14.1. */
    var micLevel = 0f
        private set

    /** What the call is doing, in one word — see [CallPhase]. A bar says "Thinking…" from this. */
    val phase: CallPhase
        get() = CallPhase.of(reconnecting, connectivity, connecting, ready, connected, agentSpeaking, awaitingAgent)

    /**
     * Where the call is, in one word — what the SDK's bar says
     * ([GraineVoiceStatus]): connecting, listening, thinking, speaking,
     * muted, reconnecting, failed ([error] says why, unless nothing could
     * carry the call: [callFailed]) — and, with no call, ended (a call ended
     * and none has started since) or idle. For a voice UI of your own; the
     * listener's callbacks say when to read it again.
     */
    val status: GraineVoiceStatus
        get() = GraineVoiceStatus.of(phase, connected, connecting, muted, error != null || callFailed, ended)

    /** A call ended and no other has started since. */
    private var ended = false

    /** The last caption is the agent's, still arriving. */
    private var agentSpeaking = false
    /** The customer finished a line and the agent has not begun its answer. */
    private var awaitingAgent = false

    private var webView: WebView? = null
    private var host: FrameLayout? = null
    /**
     * The engine's context. A view's context is fixed when it is built, so the
     * WebView and its box are built on a wrapper whose base is swapped for
     * whichever activity carries them — the one WebView moves between
     * activities, and none of them is kept alive by it after it leaves.
     */
    private var engineContext: MutableContextWrapper? = null
    private var activityRef = WeakReference<Activity>(null)
    private var pendingStart = false
    private var startedCall = false
    private var endNotified = true
    private var cacheKey: String = System.currentTimeMillis().toString()
    private var mountedIdle = true
    private var unwatch: List<() -> Unit> = emptyList()

    private val progress = ConnectProgress(
        graine.config.timing.slowConnectMs,
        schedule = { delayMs, task ->
            val r = Runnable { task() }
            graine.main.postDelayed(r, delayMs)
            ({ graine.main.removeCallbacks(r) })
        },
        onChange = { report { it.onConnectProgress(connectStage, slowConnect, callFailed) } },
    )

    /**
     * This SDK's bars on screen — the React Native launcher's `active`: the
     * customer's UI is open. Weak, so a bar can never hold its activity here.
     */
    private val openBars: MutableSet<Any> = java.util.Collections.newSetFromMap(java.util.WeakHashMap<Any, Boolean>())

    private val watchdog = LoadWatchdog(graine.config.timing.stallMs, graine.config.timing.bootMs, graine.config.timing.hardCapMs)
    private val ladder = RetryLadder(graine.config.timing.retryDelaysMs)
    private val checkLoad = Runnable { onWatchdogTick() }
    private val retryLoad = Runnable { webView?.reload() }
    private val endWatchdog = Runnable { forceEnd() }
    private val backgroundConfirm = Runnable { if (!graine.foreground) end() }

    /**
     * Mount the engine. Loads the page idle — nothing is registered, nothing
     * is dialled, no microphone — so a start() later is an INVITE away rather
     * than a page load away.
     *
     * Once is enough. From then on the engine follows the customer: whichever
     * of your activities comes to the front, the one WebView moves into its
     * window and a call in progress carries on. A KYC flow built as one
     * activity per step does not hang up on "Next". Calling attach again from
     * another activity is the same move, never a reload.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(activity: Activity) {
        if (webView != null) { moveTo(activity); return }
        activityRef = WeakReference(activity)
        // In debug, the engine is inspectable at chrome://inspect like any page.
        if (graine.config.debug) WebView.setWebContentsDebuggingEnabled(true)

        val ctx = MutableContextWrapper(activity)
        engineContext = ctx
        val wv = WebView(ctx).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // The agent speaks first, and nothing on a 1x1 view can be tapped
            // to satisfy a gesture requirement.
            settings.mediaPlaybackRequiresUserGesture = false
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            addJavascriptInterface(Bridge(), Protocol.BRIDGE_NAME)
            webChromeClient = Chrome()
            webViewClient = Client()
        }
        val frame = FrameLayout(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1)
            alpha = 0f
            isClickable = false
            isFocusable = false
            addView(wv, FrameLayout.LayoutParams(1, 1))
        }
        (activity.window.decorView as ViewGroup).addView(frame)
        webView = wv
        host = frame

        unwatch = listOf(
            graine.onVariables { vars -> if (ready) post(Protocol.out(Protocol.Out.VARIABLES, "variables" to vars)) },
            graine.onContext { frame -> if (connected) postContext(frame) },
            // A sign-in, a sign-out or a memory choice reaches the page for its next call.
            graine.onIdentity { env -> if (ready) postIdentity(env) },
            // Every new server token reaches the page for its own requests; a call in progress is never touched by it.
            graine.clientTokens.onChange { token -> graine.main.post { if (ready) postClientToken(token) } },
        )
        graine.foregroundWatchers.add(foregroundWatcher)
        load(idle = true)
    }

    /** Move the running engine into [activity]'s window. The page, its socket and any call carry on. */
    private fun moveTo(activity: Activity) {
        val frame = host ?: return
        if (activityRef.get() === activity && frame.parent != null) return
        (frame.parent as? ViewGroup)?.removeView(frame)
        engineContext?.baseContext = activity
        activityRef = WeakReference(activity)
        (activity.window.decorView as ViewGroup).addView(frame)
        if (graine.config.debug) Log.d(GraineInApp.TAG, "engine moved to ${activity.javaClass.simpleName}")
    }

    /** An activity came to the front. The engine goes with the customer — once the app has attached it at all. */
    internal fun follow(activity: Activity) {
        if (webView != null) moveTo(activity)
    }

    /**
     * An activity is being destroyed. If the engine is still in its window —
     * the last screen closing, not a step forward, which has already taken it
     * — lift it out and point it at the application so the activity can be
     * collected. The page keeps running; the next activity to come to the
     * front takes it, and backgrounding still ends a call as it always did.
     */
    internal fun release(activity: Activity) {
        if (activityRef.get() !== activity) return
        (host?.parent as? ViewGroup)?.removeView(host)
        engineContext?.baseContext = graine.application
        activityRef = WeakReference(null)
    }

    /** Remove the engine. Hangs up first — a call must never outlive its UI. */
    fun detach() {
        webView?.let { post(Protocol.out(Protocol.Out.END_CALL)) }
        unwatch.forEach { it() }; unwatch = emptyList()
        graine.foregroundWatchers.remove(foregroundWatcher)
        graine.main.removeCallbacks(checkLoad); graine.main.removeCallbacks(retryLoad)
        graine.main.removeCallbacks(endWatchdog); graine.main.removeCallbacks(backgroundConfirm)
        webView?.let { wv ->
            (host?.parent as? ViewGroup)?.removeView(host)
            wv.removeJavascriptInterface(Protocol.BRIDGE_NAME)
            wv.stopLoading()
            wv.destroy()
        }
        webView = null; host = null; engineContext = null
        activityRef = WeakReference(null)
        progress.clear()
        ready = false; startedCall = false; pendingStart = false
        // The engine is gone, and its call: what was said goes with it.
        if (captions.isNotEmpty()) { captions = emptyList(); captionsChanged() }
        setCall(connected = false, connecting = false)
    }

    /**
     * Place the call. Asks for the microphone first if the app does not hold
     * it — forward your activity's onRequestPermissionsResult to
     * [onRequestPermissionsResult] so the call can go ahead when it is granted.
     * Before the page is ready the start is held and fired on ready, which is
     * what the customer meant.
     */
    fun start() {
        val activity = activityRef.get() ?: run {
            report { it.onError("GraineVoice.attach(activity) must be called before start().") }; return
        }
        // One call at a time: a native call (GraineCalls) holds the microphone and the line.
        if (graine.nativeCallLive()) {
            report { it.onError("A call is already in progress.") }; return
        }
        // One conversation at a time, across every agent: a call starting ends the chat (its lines stay on
        // screen), and another agent's call.
        graine.voiceStarting()
        // A new attempt: the last call's fault, its failure to connect, its road and its last words are not news.
        error = null
        ended = false
        transport = null
        transportFallbackReason = null
        callTransportTold = false
        progress.clear()
        agentSpeaking = false
        awaitingAgent = false
        // A new call, not a continuation: the last one's words do not open this one.
        if (captions.isNotEmpty()) { captions = emptyList(); captionsChanged() }
        setCall(connected = connected, connecting = true)
        if (!hasMic()) {
            pendingStart = true
            if (Build.VERSION.SDK_INT >= 23) activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), MIC_REQUEST)
            return
        }
        if (!ready) { pendingStart = true; return }
        beginCall()
    }

    /** Hang up. Guaranteed: if the page never confirms, the call is torn down after a bounded wait. */
    fun end() {
        pendingStart = false
        post(Protocol.out(Protocol.Out.END_CALL))
        graine.main.removeCallbacks(endWatchdog)
        graine.main.postDelayed(endWatchdog, graine.config.timing.endWatchdogMs)
    }

    /** Stop sending the customer's voice without ending the call. */
    fun setMuted(value: Boolean) {
        muted = value
        if (value) heard(null, 0f)
        post(Protocol.out(Protocol.Out.SET_MUTED, "muted" to value))
    }

    /**
     * The customer answered a component the agent drew. `summary` is the
     * sentence the agent hears ("Chose the Creta and the Seltos") — without it
     * the tap lands and the conversation does not move.
     */
    fun answerWidget(widgetId: String, data: Map<String, Any?>, summary: String) {
        // Answered: nothing may replay it on the next mount.
        graine.clearPendingWidget()
        post(Protocol.out(Protocol.Out.WIDGET_RESULT, "widgetId" to widgetId, "data" to data, "summary" to summary))
    }

    /** Something happened that the agent may want to speak up about. Only during a call. */
    @JvmOverloads
    fun reportEvent(name: String, detail: String? = null, data: Map<String, Any?> = emptyMap()) {
        if (!connected) return
        graine.currentContextFrame()?.let { postContext(it) }
        post(Protocol.appEvent(name, detail, data))
    }

    /** Reload the page and start the retry ladder over. For a "Try again" button. */
    fun retry() {
        ladder.reset()
        setConnectivity(Connectivity.LOADING, null)
        webView?.reload()
    }

    /** Forward from your activity. Returns true when the request was the SDK's. */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray): Boolean {
        if (requestCode != MIC_REQUEST) return false
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            pendingStart = false
            setCall(connected = false, connecting = false)
            report { it.onMicDenied("NotAllowedError") }
            return true
        }
        if (pendingStart && ready) { pendingStart = false; beginCall() }
        return true
    }

    // ── internals ──────────────────────────────────────────────────────────

    private fun load(idle: Boolean) {
        graine.loadSession { session ->
            val wv = webView ?: return@loadSession
            if (session == null) { setConnectivity(Connectivity.FAILED, "session"); return@loadSession }
            mountedIdle = idle
            session.pageVersion?.let { cacheKey = it }
            // What the engine carries calls on from now: what it is loaded with, until it reports a fallback.
            val chosen = graine.config.transport ?: session.preferredTransport
            engineTransport = chosen
            engineFallbackReason = null
            val url = Protocol.engineUrl(
                baseUrl = graine.config.baseUrl,
                voiceAgentId = session.webcallAgentId,
                transport = chosen,
                cacheKey = cacheKey,
                idle = idle,
                publishableKey = graine.config.publishableKey,
                accent = session.accent,
            )
            wv.loadUrl(url)
        }
    }

    private fun beginCall() {
        startedCall = true
        endNotified = true
        // A new call: the last one's unanswered component must not come back over it.
        graine.clearPendingWidget()
        post(Protocol.out(Protocol.Out.START_CALL))
    }

    /** Deliver one message into the page. WebView calls are main-thread only; hop there if needed. */
    private fun post(message: JSONObject) {
        val wv = webView ?: return
        if (graine.config.debug) Log.d(GraineInApp.TAG, "→ page ${message.optString("type")} ${redactForLog(message)}")
        val js = Protocol.deliver(message)
        if (onMainThread()) wv.evaluateJavascript(js, null) else graine.main.post { webView?.evaluateJavascript(js, null) }
    }

    /** Debug logs name what was sent without dumping a customer's variables, fields or identity into logcat. */
    private fun redactForLog(m: JSONObject): String = when (m.optString("type")) {
        Protocol.Out.VARIABLES -> "{${m.optJSONObject("variables")?.keys()?.asSequence()?.joinToString() ?: ""}}"
        Protocol.Out.APP_CONTEXT -> "screen=${m.optJSONObject("context")?.optString("screen")}"
        Protocol.Out.IDENTITY -> "identity=${if (m.isNull("value")) "none" else "set"} memory=${m.optBoolean("memory")}"
        Protocol.Out.CLIENT_TOKEN -> "token=${if (m.isNull("token")) "none" else "set"}"
        else -> m.toString().take(300)
    }

    /**
     * Who is using the app, for the page's ticket and rtc-session (contract
     * §1.3) — with the identity token and the user pass that belong to that
     * value (APP-IDENTITY.md §1.3), which the page sends inside the identity
     * as every request of the SDK does. Never in the page's URL, and never in
     * the debug log ([redactForLog] names an identity, nothing of it).
     */
    private fun postIdentity(env: IdentityEnvelope) {
        val proof = graine.identityProofFor(env.value)
        val message = Protocol.out(Protocol.Out.IDENTITY,
            "value" to env.value, "installId" to env.installId, "memory" to env.memory, "timezone" to env.timezone)
        proof?.token?.let { message.put("token", it) }
        proof?.pass?.let { message.put("pass", it) }
        post(message)
    }

    /** The server token, for the page's own requests (CLIENT-TOKENS.md §2.2) — only while tokens are in play. */
    private fun postClientToken(token: String?) {
        if (!graine.clientTokens.inPlay) return
        postClientTokenAnswer(token)
    }

    /** The answer to the page's `graine:need-client-token`: the newest token — null when there is none, so it stops waiting. */
    private fun postClientTokenAnswer(token: String?) = post(Protocol.out(Protocol.Out.CLIENT_TOKEN, "token" to token))

    private fun onMainThread(): Boolean = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    private fun postContext(frame: JSONObject) {
        post(JSONObject(frame.toString()).put("type", Protocol.Out.APP_CONTEXT))
    }

    /** Whether the APP holds the microphone. Any context answers that, including between activities. */
    private fun hasMic(): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            graine.application.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /**
     * This engine's call still holds the phone's audio — its microphone, and
     * the audio mode the web view set and puts back as it closes: live, or
     * placed and not yet over. A start still waiting for the page holds nothing.
     */
    internal val holdsAudio: Boolean get() = connected || (connecting && startedCall)

    private fun setCall(connected: Boolean, connecting: Boolean) {
        val changed = connected != this.connected || connecting != this.connecting
        val liveChanged = connected != this.connected
        this.connected = connected
        this.connecting = connecting
        rearm()
        if (liveChanged) graine.callStateChanged(connected)
        if (changed) report { it.onCallState(connected, connecting) }
        // No call, no levels: the last ones heard never outlive it.
        if (!connected) heard(0f, 0f)
        // Over: a native call waiting for this one to let go of the audio (talk, then call) goes on.
        if (changed && !connected && !connecting) AppShared.callsIfMade()?.webCallReleased()
    }

    /** The page's levels: followed while a call is live (the microphone's while it is not muted), 0 otherwise. */
    private fun heard(agent: Float?, mic: Float?) {
        val nextAgent = if (connected) agent ?: agentLevel else 0f
        val nextMic = if (connected && !muted) mic ?: micLevel else 0f
        if (nextAgent == agentLevel && nextMic == micLevel) return
        agentLevel = nextAgent
        micLevel = nextMic
        report { it.onLevels(nextAgent, nextMic) }
    }

    private fun setConnectivity(state: Connectivity, reason: String?) {
        connectivity = state
        report { it.onConnectivity(state, ladder.attempt, reason) }
    }

    private inline fun report(crossinline f: (GraineListener) -> Unit) {
        graine.listeners.forEach { l -> runCatching { f(l) } }
    }

    /** One message from the engine page, on the main thread. Internal for the JVM tests, which speak as the page. */
    internal fun handle(msg: PageMessage) {
        when (msg) {
            PageMessage.Ready -> {
                ready = true
                graine.main.removeCallbacks(checkLoad)
                ladder.reset()
                setConnectivity(Connectivity.READY, null)
                // Who the customer is, before anything else: the greeting is
                // composed from these and nothing later can repair it.
                post(Protocol.out(Protocol.Out.VARIABLES, "variables" to graine.currentVariables()))
                postIdentity(graine.identityEnvelope())
                // The token the page's own ticket and rtc-session carry, before anything asks for one.
                postClientToken(graine.clientTokens.newest)
                graine.currentContextFrame()?.let { postContext(it) }
                if (pendingStart && hasMic()) { pendingStart = false; beginCall() }
                else if (!startedCall) post(Protocol.out(Protocol.Out.WARM))
            }
            is PageMessage.Call -> {
                if (msg.connected) { endNotified = false; reconnecting = false; graine.main.removeCallbacks(endWatchdog); progress.clear() }
                // A call is being placed or is live: what it runs on is what the engine carries calls on now.
                if ((msg.connected || msg.connecting) && !callTransportTold) {
                    callTransportTold = true
                    transport = engineTransport ?: graine.config.transport ?: graine.session?.preferredTransport
                    transportFallbackReason = engineFallbackReason
                }
                // The page announces "not connected" as it loads. With a start
                // queued behind it, that is not idle — the customer pressed
                // the button, and the bar must keep saying "connecting".
                val held = pendingStart && !msg.connected
                setCall(msg.connected, msg.connecting || held)
                if (msg.connected) graine.currentContextFrame()?.let { postContext(it) }
                if (!msg.connected && !msg.connecting) startedCall = false
            }
            is PageMessage.Muted -> { muted = msg.muted; if (msg.muted) heard(null, 0f) }
            is PageMessage.Levels -> heard(msg.agent, msg.mic)
            is PageMessage.Caption -> {
                // Before the listeners hear it, so a bar reading `phase` in onCaption sees this line.
                agentSpeaking = msg.role == Role.AGENT && msg.live
                if (msg.role == Role.CUSTOMER && !msg.live) awaitingAgent = true
                if (msg.role == Role.AGENT) awaitingAgent = false
                captions = CaptionLog.fold(captions, GraineCaption(msg.role, msg.text, msg.live))
                report { it.onCaption(msg.role, msg.text, msg.live) }
            }
            is PageMessage.Widget -> {
                // Retained before it is dispatched, so a view mounted later still finds it.
                graine.retainWidget(msg.widget)
                report { it.onWidget(msg.widget) }
            }
            is PageMessage.WidgetPending -> {
                graine.widgetOnItsWay(msg.widget)
                report { it.onWidgetPending(msg.widget) }
            }
            is PageMessage.AppAction -> graine.executeAction(msg.name, msg.arguments) { r ->
                post(Protocol.out(Protocol.Out.APP_ACTION_RESULT,
                    "actionId" to msg.actionId, "status" to r.status.wire, "message" to r.message, "data" to r.data))
            }
            PageMessage.Ended -> {
                graine.main.removeCallbacks(endWatchdog)
                reconnecting = false
                agentSpeaking = false
                awaitingAgent = false
                ended = true
                // Kept until the next call, which is told its own.
                callTransportTold = false
                setCall(connected = false, connecting = false)
                startedCall = false
                setQuality(ConnectionQuality.UNKNOWN)
                if (!endNotified) { endNotified = true; report { it.onEnded() } }
            }
            is PageMessage.MicDenied -> {
                pendingStart = false
                setCall(connected = false, connecting = false)
                report { it.onMicDenied(msg.reason) }
            }
            is PageMessage.TransportChanged -> {
                transport = msg.transport
                transportFallbackReason = msg.reason?.takeIf { msg.transport != msg.requested && it.isNotBlank() }
                callTransportTold = true
                // The engine stays on what it fell back to, with that reason, until it is loaded again.
                engineTransport = msg.transport
                engineFallbackReason = transportFallbackReason
                report { it.onTransport(msg.transport, msg.requested, msg.reason, msg.message) }
                // A misconfiguration is an error even though the call survived it on the other
                // transport: nothing recovers from it on its own, as the React Native launcher says too.
                val fault = msg.message
                if (msg.reason == "not_configured" && !fault.isNullOrEmpty()) {
                    error = fault
                    report { it.onError(fault) }
                }
            }
            is PageMessage.QualitySample -> setQuality(ConnectionQuality.classify(msg.rttMs, msg.jitterMs, msg.loss))
            is PageMessage.Network -> {
                online = msg.online
                report { it.onNetwork(msg.online) }
                if (!msg.online) setQuality(ConnectionQuality.LOST)
            }
            is PageMessage.Reconnecting -> {
                reconnecting = msg.active
                if (msg.active) setCall(connected = connected, connecting = true)
                report { it.onReconnecting(msg.active) }
            }
            // Refused for its token: one question at a time, at most one asked anew in 5 s; the page hears the newest
            // token either way (it waits at most 5 s, then sends that request once more).
            PageMessage.NeedClientToken -> graine.clientTokens.refreshForPage { token -> graine.main.post { postClientTokenAnswer(token) } }
            is PageMessage.Progress -> {
                if (progress.onStage(msg.stage)) {
                    // Neither transport could carry the call: stop saying "connecting", and
                    // let the next press be a new start rather than a wait on this one.
                    startedCall = false
                    pendingStart = false
                    setCall(connected = false, connecting = false)
                }
                rearm()
            }
        }
    }

    /**
     * A bar of this SDK came on screen or left it — [GraineAgentBarView] says
     * so itself. The last one leaving is the customer closing their UI: the
     * React Native launcher deactivating, which forgets a failed connect and
     * its stage, as this does.
     */
    internal fun barShown(bar: Any, shown: Boolean) {
        val changed = if (shown) openBars.add(bar) else openBars.remove(bar)
        if (!changed) return
        if (!shown && openBars.isEmpty()) progress.clear()
        rearm()
    }

    /** Start, stop or keep the slow-connect clock, from the state as it now stands. */
    private fun rearm() {
        progress.waiting(ConnectProgress.waitingOnConnect(connected, progress.failed, connecting, ready, openBars.isNotEmpty()))
    }

    private fun setQuality(q: ConnectionQuality) {
        if (q == quality) return
        quality = q
        report { it.onQuality(q) }
    }

    /** The page never confirmed the hang-up. Force it: state, the callback, and a reload that tears the media down. */
    private fun forceEnd() {
        setCall(connected = false, connecting = false)
        startedCall = false
        if (!endNotified) { endNotified = true; report { it.onEnded() } }
        webView?.reload()
    }

    private fun onWatchdogTick() {
        if (ready) return
        val now = System.currentTimeMillis()
        when (val v = watchdog.check(now)) {
            LoadWatchdog.Verdict.WAIT -> graine.main.postDelayed(checkLoad, watchdog.nextCheckInMs(now))
            else -> scheduleRetry(v.name.lowercase())
        }
    }

    private fun scheduleRetry(reason: String) {
        graine.main.removeCallbacks(checkLoad)
        if (connected) return   // never reload a page that is carrying a call
        val delay = ladder.next()
        if (delay == null) {
            setConnectivity(Connectivity.FAILED, reason)
            pendingStart = false
            setCall(connected = false, connecting = false)
            report { it.onError("The assistant could not load. Check your connection and try again.") }
            return
        }
        ready = false
        setConnectivity(Connectivity.RETRYING, reason)
        graine.main.removeCallbacks(retryLoad)
        graine.main.postDelayed(retryLoad, delay)
    }

    private val foregroundWatcher: (Boolean) -> Unit = { fg ->
        graine.main.removeCallbacks(backgroundConfirm)
        if (fg) {
            // Back, and idle: the warm registration very likely died while the app
            // slept. Warm now rather than at the tap (see Warm).
            if (Warm.onForeground(ready, startedCall, connected, connecting)) post(Protocol.out(Protocol.Out.WARM))
        } else if (graine.config.endOnBackground && (connected || connecting)) {
            // Confirmed after two seconds: a permission dialog or the app switcher
            // briefly stops the activity, and ending on that would hang up the
            // call that asked for the microphone.
            graine.main.postDelayed(backgroundConfirm, 2_000)
        }
    }

    /** The page calls `window.ReactNativeWebView.postMessage(json)`. Runs on a binder thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String?) {
            val msg = Protocol.parse(json)
            // The levels come five times a second while live: kept out of the debug log, which they would drown.
            if (graine.config.debug && msg !is PageMessage.Levels) Log.d(GraineInApp.TAG, "← page ${json?.take(600)}")
            if (msg == null) return
            graine.main.post { handle(msg) }
        }
    }

    private inner class Chrome : WebChromeClient() {
        /** The engine page's console, in logcat — only with debug on; a release build stays quiet. */
        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
            if (!graine.config.debug) return false
            val line = "page console ${m.messageLevel()}: ${m.message()}"
            when (m.messageLevel()) {
                ConsoleMessage.MessageLevel.ERROR -> Log.e(GraineInApp.TAG, line)
                ConsoleMessage.MessageLevel.WARNING -> Log.w(GraineInApp.TAG, line)
                else -> Log.d(GraineInApp.TAG, line)
            }
            return true
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            watchdog.progress(newProgress, System.currentTimeMillis())
        }

        /**
         * The page asks for the microphone. Granted only when the APP holds
         * RECORD_AUDIO — a WebView cannot hold a permission the app does not —
         * and only for audio capture, never the camera.
         */
        override fun onPermissionRequest(request: PermissionRequest) {
            graine.main.post {
                val wantsAudio = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                if (wantsAudio && hasMic()) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                else { request.deny(); if (wantsAudio) report { it.onMicDenied("NotAllowedError") } }
            }
        }
    }

    private inner class Client : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            ready = false
            watchdog.start(System.currentTimeMillis())
            graine.main.removeCallbacks(checkLoad)
            graine.main.postDelayed(checkLoad, 1_000)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            watchdog.loaded(System.currentTimeMillis())
            if (graine.config.debug) view?.evaluateJavascript(SocketTrace.JS, null)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            if (request?.isForMainFrame == true) scheduleRetry("load-error:${if (Build.VERSION.SDK_INT >= 23) error?.errorCode else ""}")
        }

        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: WebResourceResponse?) {
            if (request?.isForMainFrame == true) scheduleRetry("http-${response?.statusCode}")
        }

        /** The renderer died under us. Rebuild rather than crash the host app, which is the default. */
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            val activity = activityRef.get()
            webView = null
            (host?.parent as? ViewGroup)?.removeView(host)
            host = null
            setCall(connected = false, connecting = false)
            if (!endNotified) { endNotified = true; report { it.onEnded() } }
            if (activity != null) graine.main.post { attach(activity) }
            return true
        }

        /** The engine never leaves Graine's origin; anything else opens outside the app's call. */
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val target = request?.url ?: return true
            val base = Uri.parse(graine.config.baseUrl)
            return !(target.scheme == base.scheme && target.host == base.host)
        }
    }

    companion object {
        /** The request code the SDK uses for the microphone prompt. */
        const val MIC_REQUEST = 0x6E7A
    }
}

/** One line of what was said on a call: who said it, the words, and whether they are still arriving. */
data class GraineCaption(val role: Role, val text: String, val live: Boolean)

/**
 * WHAT HAS BEEN SAID, folded as every SDK folds it (the React Native
 * launcher's foldCaption): a live turn replaces the tail it belongs to — same
 * role, still live — rather than appending the same sentence growing a word at
 * a time; an empty line settles the open turn instead of blanking it; the last
 * [HISTORY] are kept.
 */
internal object CaptionLog {
    /** How many turns are kept. A caption box shows two or three. */
    const val HISTORY = 12

    fun fold(lines: List<GraineCaption>, next: GraineCaption): List<GraineCaption> {
        val tail = lines.lastOrNull()
        val continues = tail != null && tail.live && tail.role == next.role
        if (next.text.isEmpty()) return if (continues) lines.dropLast(1) + tail!!.copy(live = false) else lines
        val merged = if (continues) lines.dropLast(1) + next else lines + next
        return if (merged.size > HISTORY) merged.takeLast(HISTORY) else merged
    }
}
