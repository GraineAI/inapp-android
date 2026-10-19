package ai.graine.inapp

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager

/**
 * THE SDK'S CALL SCREEN — over the lock screen, for the ring and the call,
 * drawn in the brand's design: the session's `call` tokens, exactly as
 * contract/DESIGN.md → Call screen draws them, in its six moments:
 *
 *   a scheduled call ringing   Graine rang it at a booked time ("Scheduled call")
 *   any other call ringing     Decline and Answer, a halo growing from the avatar
 *   connecting
 *   on the call                the timer (or Listening / Speaking), the caption,
 *                              the design's controls in its order
 *   ended                      "Call ended" (or "Call failed", "Declined"); closes in 1 s
 *   missed                     a ring nobody answered: Close and Call back; closes in 30 s
 *
 * Everything it draws is the design's: the look (the backdrop and its motion,
 * the layout, the avatar's ring, the voice, the caption's style, the buttons'
 * style and shape, the type, a look per moment — CallScreenView draws it),
 * every colour, every word, the typeface and the icon style. A call placed by
 * the customer shows its own moment (outgoing) while it connects; the voice
 * follows the agent's audio level over WebRTC, and the speaking signal on the
 * WebSocket. The last session's design is kept on the device, so a push that
 * wakes a closed app draws the same screen; before any session, Graine
 * Classic's.
 *
 * It only draws GraineCalls' state and calls its methods; closing it never
 * ends the call (the ongoing notification brings it back). An app with its
 * own call screen names it in [GraineCallsConfig.callActivity] and draws the
 * same state.
 */
class GraineCallActivity : Activity(), GraineCallListener {
    private var calls: GraineCalls? = null
    private var graine: GraineInApp? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var t: ThemeTokens
    private lateinit var look: CallTokens
    private lateinit var view: CallScreenView
    private var shownMoment: CallScreen.Moment? = null

    /** A missed call this screen shows: one that rang out here, or one the missed-call notification opened. */
    private var missedCall: Missed? = null
    /** The call this screen last drew live — whose missed outcome turns it into the missed screen. */
    private var shownCallId: String? = null
    /** How the call this screen drew ended, for the ended moment's words. */
    private var lastOutcome: GraineCallOutcome? = null
    private var closing: Runnable? = null
    /** The agent's last audio level and when it came (uptimeMillis): the visualiser follows it. */
    private var level: Float? = null
    private var levelAt = 0L
    /** Whether the status bar's icons are light now. */
    private var lightBars: Boolean? = null

    private val tick = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = agentOf(intent)
        if (g == null) { finish(); return }
        graine = g
        calls = g.calls
        showOverLockScreen()
        resolveLook(g)
        view = CallScreenView(this, g)
        val dash = Appearance.resolve(g.appearance, look.avatarFill, "", origin = g.config.baseUrl)
        view.setDesign(t, dash.logoUrl, dash.logoPlate)
        view.listener = object : CallScreenView.Listener {
            override fun onAnswer() { calls?.answer() }
            override fun onDecline() { calls?.decline() }
            override fun onEnd() { calls?.end() }
            override fun onMute() { calls?.let { it.setMuted(!it.muted) } }
            override fun onSpeaker() { calls?.let { it.setSpeaker(!it.speaker) } }
            override fun onClose() { finishAndRemoveTask() }
            override fun onCallBack() { callBack() }
        }
        setContentView(view)
        paintBars()
        g.calls.addListener(this)
        readMissed(intent)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Another agent's call: this screen is drawn again, in that agent's design.
        val g = agentOf(intent)
        if (g != null && g !== graine) { recreate(); return }
        readMissed(intent)
        render()
    }

    /**
     * The agent whose design this screen draws: the one the intent's call is
     * with (each intent the engine builds names it), else the call in
     * progress's, else the app's default agent. Null before any init().
     */
    private fun agentOf(i: Intent?): GraineInApp? {
        val primary = GraineInApp.peek() ?: return null
        i?.getStringExtra(GraineCalls.EXTRA_AGENT)?.let { GraineInApp.find(it) }?.let { return it }
        return AppShared.callsIfMade()?.callAgent ?: primary
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (this::view.isInitialized) view.setRunning(true)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        if (this::view.isInitialized) view.setRunning(false)
    }

    override fun onDestroy() {
        calls?.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Back leaves the call screen, never the call: the notification brings it back.
        if (!moveTaskToBack(true)) finish()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (calls?.onRequestPermissionsResult(requestCode, grantResults) != true) super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    // ── GraineCallListener ─────────────────────────────────────────────────

    override fun onCallState(state: GraineCallState, call: GraineCall?) = render()
    override fun onCallCaption(role: Role, text: String, live: Boolean) = render()
    override fun onAgentSpeaking(speaking: Boolean) = render()
    override fun onCallControls(muted: Boolean, speaker: Boolean) = render()
    override fun onCallReconnecting(reconnecting: Boolean) = render()

    override fun onAgentLevel(level: Float) {
        this.level = level
        levelAt = SystemClock.uptimeMillis()
        render()
    }

    override fun onCallOutcome(callId: String, outcome: GraineCallOutcome) {
        if (callId != shownCallId) return
        lastOutcome = outcome
        // The ring on this screen ran out: it stays, as the missed call, with Call back.
        if (outcome.kind == GraineCallOutcome.Kind.MISSED) {
            val c = calls?.call?.takeIf { it.callId == callId }
            missedCall = Missed(callId, c?.callerName ?: calls?.config?.displayName ?: "", c?.reason)
            calls?.missedSeen()
        }
        render()
    }

    /** Opened from the missed-call notification: show that call, and let the notification go. */
    private fun readMissed(i: Intent?) {
        if (i?.action != GraineCalls.ACTION_MISSED) return
        val name = i.getStringExtra(GraineCalls.EXTRA_CALLER_NAME) ?: calls?.config?.displayName ?: ""
        missedCall = Missed(i.getStringExtra(GraineCalls.EXTRA_CALL_ID), name, i.getStringExtra(GraineCalls.EXTRA_REASON))
        calls?.missedSeen()
    }

    /** A missed call: who, and why it was placed (carried into the call back). */
    private data class Missed(val callId: String?, val name: String, val reason: String?)

    // ── the design ─────────────────────────────────────────────────────────

    /**
     * The tokens this screen draws: the design's (this launch's session, else
     * the last one kept on the device) for the brand's scheme or the phone's;
     * with no design at all, Graine Classic's call screen in the dashboard's accent.
     */
    private fun resolveLook(g: GraineInApp) {
        val designed = g.themeTokens(this)
        t = designed ?: run {
            val dash = Appearance.resolve(g.appearance, Design.CLASSIC_DARK.colors.accent, "")
            val accent = Design.normaliseHex(dash.accent) ?: Design.CLASSIC_DARK.colors.accent
            Design.CLASSIC_DARK.copy(colors = Design.CLASSIC_DARK.colors.copy(accent = accent, onAccent = Themed.readableOnHex(accent)))
        }
        look = Surfaces.callOf(t)
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** Edge to edge, under the status bar and the home indicator. */
    private fun paintBars() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (Build.VERSION.SDK_INT < 30) {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        }
    }

    /** The bars' icons light while the moment's ink is light (contract: call.text's luminance above 0.5). */
    private fun barIcons(lightText: Boolean) {
        if (lightBars == lightText) return
        lightBars = lightText
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (lightText) 0 else mask, mask)
        }
    }

    /** Call the agent back: this screen becomes the outgoing call's. */
    private fun callBack() {
        val m = missedCall ?: return
        // With the agent the missed call was from — the one this screen is drawn for.
        val id = calls?.callBack(this, m.callId, m.reason, graine?.name) ?: return
        missedCall = null
        lastOutcome = null
        intent = Intent(intent).setAction(GraineCalls.ACTION_SHOW).putExtra(GraineCalls.EXTRA_CALL_ID, id)
        render()
    }

    /** This screen is for the call in [getIntent]: a stale one (a ring from before) shows the end and closes. */
    private fun mine(c: GraineCall?): Boolean {
        val id = intent?.getStringExtra(GraineCalls.EXTRA_CALL_ID)
        return c != null && (id == null || id == c.callId)
    }

    private fun moment(): CallScreen.Moment {
        val calls = calls ?: return CallScreen.Moment.ENDED
        val c = calls.call
        val live = mine(c) && calls.state != GraineCallState.IDLE && calls.state != GraineCallState.ENDED
        // A call recovering its connection (WebRTC's media watch, GraineCalls.reconnecting) shows its reconnecting moment.
        return CallScreen.moment(calls.state, mine(c), c?.hosted == true, missed = !live && missedCall != null,
            outgoing = c?.direction == GraineCall.Direction.OUTGOING, reconnecting = calls.reconnecting)
    }

    private fun render() {
        if (!this::view.isInitialized) return
        val calls = calls ?: return
        val c = calls.call
        val m = moment()
        val live = m != CallScreen.Moment.ENDED && m != CallScreen.Moment.MISSED
        if (live && c != null) {
            if (shownCallId != c.callId) lastOutcome = null
            shownCallId = c.callId
            // A call on this screen again — the call back, a new ring — is not the missed one.
            if (c.callId != missedCall?.callId) missedCall = null
        }
        val name = if (m == CallScreen.Moment.MISSED) missedCall!!.name.ifEmpty { calls.config.displayName }
        else c?.callerName ?: calls.config.displayName
        val since = calls.connectedAt.takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() - it }
        val onCall = m == CallScreen.Moment.ON_CALL
        view.render(CallScreenView.State(
            moment = m,
            name = name,
            muted = calls.muted,
            speaker = calls.speaker,
            agentSpeaking = calls.agentSpeaking,
            level = if (onCall) level else null,
            levelAt = levelAt,
            caption = calls.caption,
            connectedForMs = since,
            ending = CallScreen.ending(lastOutcome),
        ))
        view.look?.let { barIcons(Appearance.luminance(it.text) > 0.5) }
        if (shownMoment != m) scheduleClose(m)
        shownMoment = m
    }

    /** Ended closes in a second; missed waits for Close or Call back, or closes after 30 s. */
    private fun scheduleClose(m: CallScreen.Moment) {
        val wait = when (m) {
            CallScreen.Moment.ENDED -> CallScreen.ENDED_CLOSE_MS
            CallScreen.Moment.MISSED -> CallScreen.MISSED_CLOSE_MS
            else -> null
        }
        closing?.let { handler.removeCallbacks(it) }
        closing = null
        if (wait == null) return
        val r = Runnable { if (moment() == m) finishAndRemoveTask() }
        closing = r
        handler.postDelayed(r, wait)
    }
}
