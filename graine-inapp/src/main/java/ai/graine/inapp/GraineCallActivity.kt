package ai.graine.inapp

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

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
 * Background, avatar (the brand's logo, its glyph or initials, in the design's
 * shape), every colour and every word are the design's; the type is its
 * typeface, the icons its icon style; the avatar grows a little while the
 * agent speaks, at the design's pace (still when the design or the phone asks
 * for no motion). The last session's design is kept on the device, so a push
 * that wakes a closed app draws the same screen; before any session, Graine
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
    private lateinit var root: FrameLayout
    private lateinit var halo: View
    private lateinit var avatar: FrameLayout
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var captionView: TextView
    private lateinit var ringingRow: LinearLayout
    private lateinit var callRow: LinearLayout
    private lateinit var missedRow: LinearLayout
    private val controlButtons = HashMap<String, CallButton>()
    private var haloAnimator: ValueAnimator? = null
    private var speakingShown = false
    private var shownMoment: CallScreen.Moment? = null
    private var shownCaption: String? = null

    /** A missed call this screen shows: one that rang out here, or one the missed-call notification opened. */
    private var missedCall: Missed? = null
    /** The call this screen last drew live — whose missed outcome turns it into the missed screen. */
    private var shownCallId: String? = null
    /** How the call this screen drew ended, for the ended moment's words. */
    private var lastOutcome: GraineCallOutcome? = null
    private var closing: Runnable? = null

    private val tick = object : Runnable {
        override fun run() { renderStatus(); handler.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = agentOf(intent)
        if (g == null) { finish(); return }
        graine = g
        calls = g.calls
        showOverLockScreen()
        resolveLook(g)
        setContentView(build())
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
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        calls?.removeListener(this)
        handler.removeCallbacksAndMessages(null)
        haloAnimator?.cancel()
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
    override fun onCallCaption(role: Role, text: String, live: Boolean) = renderCaption()
    override fun onAgentSpeaking(speaking: Boolean) { renderStatus(); renderSpeaking() }
    override fun onCallControls(muted: Boolean, speaker: Boolean) { renderControls(); renderStatus() }

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

    private fun c(hex: String): Int = Themed.color(hex, Color.BLACK)

    private fun dp(v: Double): Int = Math.round(v * resources.displayMetrics.density).toInt()

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

    /** Edge to edge, under the status bar and the home indicator; the bars' icons light when the screen's text is light. */
    private fun paintBars() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
            if (Build.VERSION.SDK_INT < 30) {
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val darkIcons = !CallScreen.lightBarIcons(look)
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (darkIcons) mask else 0, mask)
        }
    }

    /** Text in the design: a family at a weight and size, in a colour. */
    private fun text(sizeSp: Double, color: String, family: String, weight: Int): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.toFloat())
        setTextColor(c(color))
        gravity = Gravity.CENTER
        Themed.face(this, family, weight, graine)
    }

    private fun icon(name: String, color: String): Drawable = IconDrawable(name, t.icons.style, t.icons.stroke, c(color), dp(CallScreen.ICON))

    private fun build(): View {
        val (top, bottom) = CallScreen.background(look)
        root = FrameLayout(this).apply {
            background = if (top == bottom) GradientDrawable().apply { setColor(c(top)) }
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(c(top), c(bottom)))
            clipChildren = false
            // The layout is measured inside the safe area; the background runs under the bars.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else {
                    @Suppress("DEPRECATION")
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        }
        val side = dp(CallScreen.SIDE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(side, dp(CallScreen.AVATAR_TOP), side, 0)
        }

        // The avatar, and the halo that grows from its edge while the phone rings.
        val size = dp(CallScreen.AVATAR)
        val corner = CallScreen.avatarCorner(look.avatarShape, CallScreen.AVATAR)
        val avatarWrap = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        halo = View(this).apply {
            background = GradientDrawable().apply {
                if (corner == null) shape = GradientDrawable.OVAL else cornerRadius = dp(corner).toFloat()
                setColor(Color.TRANSPARENT)
                setStroke(dp(CallScreen.HALO_WIDTH), c(look.textMuted))
            }
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        avatar = FrameLayout(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        avatarWrap.addView(halo, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        avatarWrap.addView(avatar, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        column.addView(avatarWrap, LinearLayout.LayoutParams(size, size))

        nameView = text(CallScreen.NAME_SP, look.text, t.font.heading, t.font.weights.title).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            letterSpacing = t.font.letterSpacing.title.toFloat()
        }
        column.addView(nameView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(CallScreen.NAME_GAP) })
        statusView = text(CallScreen.STATUS_SP, look.textMuted, t.font.body, t.font.weights.body).apply {
            maxLines = 1
            // Tabular digits: the timer does not jitter as it counts.
            fontFeatureSettings = "tnum"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        column.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(CallScreen.STATUS_GAP) })
        captionView = text(CallScreen.CAPTION_SP, look.text, t.font.body, t.font.weights.strong).apply {
            maxLines = CallScreen.CAPTION_LINES
            ellipsize = TextUtils.TruncateAt.START
            Ui.lineHeight(this, (CallScreen.CAPTION_SP * CallScreen.CAPTION_LINE).toFloat())
            visibility = if (look.captions) View.VISIBLE else View.GONE
        }
        column.addView(captionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(CallScreen.CAPTION_GAP) })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val big = dp(CallScreen.BIG_BUTTON)
        // Ringing (either kind): Decline and Answer, each centred in half the width.
        ringingRow = row().apply {
            addView(CallButton(context, big, big, null, c(look.declineFill), icon(SdkIcons.END, look.declineIcon), "Decline", labelStyle()) { calls?.decline() }, share())
            addView(CallButton(context, big, big, null, c(look.acceptFill), icon(SdkIcons.CALL, look.acceptIcon), "Answer", labelStyle()) { calls?.answer() }, share())
        }
        // Missed: Close and Call back.
        missedRow = row().apply {
            addView(CallButton(context, big, big, null, c(look.buttonFill), icon(SdkIcons.CLOSE, look.buttonIcon), "Close", labelStyle()) { finishAndRemoveTask() }, share())
            addView(CallButton(context, big, big, null, c(look.callbackFill), icon(SdkIcons.CALL, look.callbackIcon), "Call back", labelStyle()) { callBack() }, share())
        }
        // Connecting, on the call, ended: the design's controls, in its order, each in an equal share.
        callRow = row()
        val controls = CallScreen.controls(look.controls)
        val shareDp = (resources.displayMetrics.widthPixels / resources.displayMetrics.density - 2 * CallScreen.SIDE) / controls.size
        val small = dp(CallScreen.BUTTON)
        for (control in controls) {
            val b = when (control) {
                "mute" -> CallButton(this, small, small, null, c(look.buttonFill), icon(SdkIcons.MIC, look.buttonIcon), "Mute", labelStyle()) {
                    calls?.let { it.setMuted(!it.muted) }
                }
                "speaker" -> CallButton(this, small, small, null, c(look.buttonFill), icon(SdkIcons.SPEAKER, look.buttonIcon), "Speaker", labelStyle()) {
                    calls?.let { it.setSpeaker(!it.speaker) }
                }
                else -> if (look.endShape == "pill")
                    CallButton(this, dp(CallScreen.pillWidth(shareDp)), small, small / 2f, c(look.endFill), icon(SdkIcons.END, look.endIcon), "End", labelStyle()) { calls?.end() }
                else CallButton(this, small, small, null, c(look.endFill), icon(SdkIcons.END, look.endIcon), "End", labelStyle()) { calls?.end() }
            }
            controlButtons[control] = b
            callRow.addView(b, share())
        }
        val bottomParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            .apply { bottomMargin = dp(CallScreen.BUTTONS_BOTTOM) }
        root.addView(ringingRow, bottomParams)
        root.addView(callRow, FrameLayout.LayoutParams(bottomParams))
        root.addView(missedRow, FrameLayout.LayoutParams(bottomParams))
        return root
    }

    /** A button's label: 13, the body family at its body weight, `textMuted`. */
    private fun labelStyle(): (TextView) -> Unit = { tv ->
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, CallScreen.LABEL_SP.toFloat())
        tv.setTextColor(c(look.textMuted))
        Themed.face(tv, t.font.body, t.font.weights.body, graine)
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val side = dp(CallScreen.SIDE)
        setPadding(side, 0, side, 0)
    }

    private fun share() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

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
        return CallScreen.moment(calls.state, mine(c), c?.hosted == true, missed = !live && missedCall != null)
    }

    private fun render() {
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
        nameView.text = name
        renderAvatar(name)
        val ringing = m == CallScreen.Moment.RING || m == CallScreen.Moment.SCHEDULED_RING
        show(ringingRow, ringing)
        show(missedRow, m == CallScreen.Moment.MISSED)
        show(callRow, !ringing && m != CallScreen.Moment.MISSED)
        // Once ended, the call's buttons sit at 40% and do nothing.
        val ended = m == CallScreen.Moment.ENDED
        callRow.alpha = if (ended) CallScreen.ENDED_ALPHA else 1f
        for (b in controlButtons.values) b.setActive(!ended)
        renderStatus()
        renderCaption()
        renderControls()
        renderHalo(ringing)
        renderSpeaking()
        shownMoment = m
        scheduleClose(m)
    }

    /** The change from one set of buttons to another fades in over the design's duration. */
    private fun show(v: View, on: Boolean) {
        val was = v.visibility == View.VISIBLE
        v.visibility = if (on) View.VISIBLE else View.GONE
        if (on && !was && shownMoment != null) Themed.enter(v, t)
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

    /** The design's avatar: the brand's logo, its glyph, or initials — in its shape and colours. */
    private fun renderAvatar(name: String) {
        val g = graine ?: return
        val dash = Appearance.resolve(g.appearance, look.avatarFill, "", origin = g.config.baseUrl)
        val corner = CallScreen.avatarCorner(look.avatarShape, CallScreen.AVATAR)
        // "logo" puts the logo first (no logo: the glyph, else the initial); "glyph" the glyph; "initials" neither.
        val markTokens = when (look.avatar) {
            "logo" -> if (dash.logoUrl != null) t.copy(icons = t.icons.copy(launcher = null)) else t
            "initials" -> t.copy(icons = t.icons.copy(launcher = null))
            else -> t
        }
        val logo = if (look.avatar == "initials") null else dash.logoUrl
        Mark.fill(avatar, markTokens, CallScreen.AVATAR, CallScreen.AVATAR * CallScreen.AVATAR_GLYPH, look.avatarShape, corner,
            c(look.avatarFill), c(look.avatarText), null, logo, dash.logoPlate, CallScreen.initial(name))
        // The initial in the heading family at the title weight, 40% of the avatar.
        (avatar.getChildAt(0) as? TextView)?.let { tv ->
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (CallScreen.AVATAR * CallScreen.AVATAR_LETTER).toFloat())
            Themed.face(tv, t.font.heading, t.font.weights.title, graine)
        }
    }

    private fun renderStatus() {
        val calls = calls ?: return
        val since = calls.connectedAt.takeIf { it > 0 }?.let { SystemClock.elapsedRealtime() - it }
        val m = moment()
        statusView.text = CallScreen.status(m, look, since, calls.muted, calls.agentSpeaking, CallScreen.ending(lastOutcome))
    }

    /** The latest line: the agent's in `text`, the customer's in `textMuted`, at 75% while it is still arriving. */
    private fun renderCaption() {
        val calls = calls ?: return
        if (!look.captions) return
        val cap = calls.caption
        val on = cap != null && moment() == CallScreen.Moment.ON_CALL
        val text = if (on) cap!!.second else ""
        captionView.text = text
        if (on) {
            captionView.setTextColor(c(if (cap!!.first == Role.CUSTOMER) look.textMuted else look.text))
            captionView.alpha = if (cap.third) VoiceBar.LIVE_ALPHA else 1f
        }
        // A new line fades in over the design's duration.
        if (on && cap!!.first == Role.AGENT && shownCaption.isNullOrEmpty()) Themed.enter(captionView, t, toAlpha = captionView.alpha)
        shownCaption = text
    }

    private fun renderControls() {
        val calls = calls ?: return
        controlButtons["mute"]?.let { b ->
            val on = calls.muted
            b.set(c(if (on) look.buttonActiveFill else look.buttonFill),
                icon(if (on) SdkIcons.MIC_OFF else SdkIcons.MIC, if (on) look.buttonActiveIcon else look.buttonIcon),
                if (on) "Unmute" else "Mute", on)
        }
        controlButtons["speaker"]?.let { b ->
            val on = calls.speaker
            b.set(c(if (on) look.buttonActiveFill else look.buttonFill), icon(SdkIcons.SPEAKER, if (on) look.buttonActiveIcon else look.buttonIcon), "Speaker", on)
        }
    }

    /** Ringing: a ring grows from the avatar's edge to 1.35 × its size, fading from 60%, every 1.6 s. None once missed, or with no motion. */
    private fun renderHalo(ringing: Boolean) {
        val moves = Design.motionMs(t.motion, Themed.animatorScale(this)) > 0
        if (!ringing || !moves) {
            haloAnimator?.cancel()
            haloAnimator = null
            halo.alpha = 0f
            halo.scaleX = 1f
            halo.scaleY = 1f
            return
        }
        if (haloAnimator != null) return
        haloAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CallScreen.HALO_MS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val s = 1f + (CallScreen.HALO_SCALE - 1f) * f
                halo.scaleX = s
                halo.scaleY = s
                halo.alpha = CallScreen.HALO_ALPHA * (1f - f)
            }
            start()
        }
    }

    /** On the call, the avatar scales to 1.06 while the agent's line is arriving, back when it stops — at the design's pace. */
    private fun renderSpeaking() {
        val speaking = calls?.agentSpeaking == true && moment() == CallScreen.Moment.ON_CALL
        if (speaking == speakingShown) return
        speakingShown = speaking
        val target = if (speaking) CallScreen.SPEAKING_SCALE else 1f
        val ms = CallScreen.motionMs(t.motion, Themed.animatorScale(this))
        if (ms <= 0) { avatar.scaleX = target; avatar.scaleY = target; return }
        avatar.animate().scaleX(target).scaleY(target).setDuration(ms).setInterpolator(Themed.interpolator(t.motion)).start()
    }

    /** A disc with an icon, and its label 8 under it: round, or a pill when the design draws the end button as one. */
    private class CallButton(
        context: Context, width: Int, height: Int, private val cornerPx: Float?, fill: Int, icon: Drawable, label: String,
        style: (TextView) -> Unit, onTap: () -> Unit,
    ) : LinearLayout(context) {
        private val face = FrameLayout(context)
        private val image = ImageView(context)
        private val caption = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            face.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            face.isClickable = true
            face.isFocusable = true
            face.setOnClickListener { onTap() }
            addView(face, LayoutParams(width, height))
            caption.gravity = Gravity.CENTER
            caption.maxLines = 1
            caption.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            style(caption)
            addView(caption, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Math.round(CallScreen.LABEL_GAP * resources.displayMetrics.density).toInt()
            })
            set(fill, icon, label, null)
        }

        fun set(fill: Int, icon: Drawable, label: String, checked: Boolean?) {
            face.background = GradientDrawable().apply {
                if (cornerPx == null) shape = GradientDrawable.OVAL else cornerRadius = cornerPx
                setColor(fill)
            }
            image.setImageDrawable(icon)
            caption.text = label
            face.contentDescription = if (checked == null) label else "$label, ${if (checked) "on" else "off"}"
        }

        /** An ended call's buttons do nothing. */
        fun setActive(on: Boolean) {
            face.isEnabled = on
            face.isClickable = on
        }
    }
}
