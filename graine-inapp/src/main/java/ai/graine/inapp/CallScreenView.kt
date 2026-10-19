package ai.graine.inapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * THE CALL SCREEN, DRAWN — contract/DESIGN.md "Call screen" and its look
 * (version 2), in every moment: the backdrop (a fill, a gradient, a mesh of
 * glows, the avatar blurred under a scrim) moving as the design says; the
 * layout; the avatar in its shape with its ring; the name and the status in
 * the design's type; the agent's voice as bars, a wave or an orb; the caption
 * in its style; and the buttons in their style, shape and size, each in the
 * moment's colours.
 *
 * It draws a [State] and reports taps to its [Listener]; it knows nothing of
 * calls. GraineCallActivity maps the call onto it, and a preview can drive it
 * with any design and any moment.
 *
 * Nothing moves while it is not running ([setRunning]: the activity's
 * resume and pause), while it is off screen, when the design has no motion or
 * the phone has animations off. On a connected call nobody has touched for
 * 10 s the backdrop comes to rest. Every frame is asked for by something that
 * still moves; a still screen asks for none.
 */
internal class CallScreenView(context: Context, private val graine: GraineInApp?) : FrameLayout(context) {
    /** What the screen shows. */
    data class State(
        val moment: CallScreen.Moment,
        /** The call name: the caller name the call carries, else the agent's. */
        val name: String,
        val muted: Boolean = false,
        val speaker: Boolean = false,
        val agentSpeaking: Boolean = false,
        /** The agent's inbound audio level, 0–1, and when it was read (uptimeMillis); null where the call reports none. */
        val level: Float? = null,
        val levelAt: Long = 0L,
        /** The latest line: who, the words, whether still arriving. */
        val caption: Triple<Role, String, Boolean>? = null,
        /** How long the call has been connected, for the timer. */
        val connectedForMs: Long? = null,
        val ending: CallScreen.Ending = CallScreen.Ending.NORMAL,
        /** The chat can be opened during this call (never on a native call: the line is held). */
        val chatAvailable: Boolean = false,
    )

    /** The screen's buttons, pressed. */
    interface Listener {
        fun onAnswer() {}
        fun onDecline() {}
        fun onEnd() {}
        fun onMute() {}
        fun onSpeaker() {}
        fun onClose() {}
        fun onCallBack() {}
    }

    var listener: Listener? = null

    private lateinit var t: ThemeTokens
    private lateinit var call: CallTokens
    private lateinit var screen: CallScreenTokens
    private lateinit var layoutSpec: CallLooks.Layout
    private var logoUrl: String? = null
    private var logoPlate = LogoPlate.ACCENT
    private var state: State? = null
    private var shownLook: CallLook? = null

    private val backdrop = CallBackdropLayer(context)
    private val safe = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private val stage = FrameLayout(context).apply { clipChildren = false; clipToPadding = false; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    private val orbSlot = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
    private val orb = OrbView(context)
    private val ring = RingView(context)
    private val avatar = FrameLayout(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
    private val nameView = TextView(context)
    private val statusView = TextView(context)
    private val strip = VoiceStripView(context)
    private val captionMiddle = CaptionView(context)
    private val captionBottom = CaptionView(context)
    private val ringingRow = row()
    private val missedRow = row()
    private val callRow = row()
    private val controlButtons = LinkedHashMap<String, CallButtonView>()
    private val decisionButtons = ArrayList<Pair<String, CallButtonView>>()

    // ── motion ─────────────────────────────────────────────────────────────

    private var running = false
    private var ticking = false
    private var moves = false
    private var lastFrame = 0L
    private var lastTouch = SystemClock.uptimeMillis()
    private var speakingSince = 0L
    /** The voice as drawn, 0–1, following its target up quickly and down slowly. */
    private var shownLevel = 0.0

    init {
        clipChildren = false
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(safe, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // The layout is measured inside the safe area; the backdrop runs under the bars.
        setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                safe.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                safe.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    private fun dp(v: Double): Int = Math.round(v * resources.displayMetrics.density).toInt()
    private fun c(hex: String?): Int = Themed.color(hex, Color.BLACK)

    // ── the design ─────────────────────────────────────────────────────────

    /** Draw in this design (one scheme's tokens) with this logo. Builds the screen anew. */
    fun setDesign(tokens: ThemeTokens, logo: String?, plate: LogoPlate) {
        t = tokens
        call = Surfaces.callOf(tokens)
        screen = Surfaces.screenOf(tokens)
        layoutSpec = CallLooks.layout(screen.layout)
        logoUrl = logo
        logoPlate = plate
        moves = Design.motionMs(tokens.motion, Themed.animatorScale(context)) > 0
        shownLook = null
        build()
        state?.let { s -> state = null; render(s) }
    }

    /** The look the moment on screen is drawn in (its ink sets the status bar's icons). */
    val look: CallLook? get() = shownLook

    private fun build() {
        safe.removeAllViews()
        column.removeAllViews()
        card.removeAllViews()
        stage.removeAllViews()
        orbSlot.removeAllViews()
        val spec = layoutSpec
        val start = spec.align == "start"
        val side = dp(CallLooks.SIDE)
        val gravity = if (start) Gravity.START else Gravity.CENTER_HORIZONTAL

        // The avatar, in its shape, with its ring and its orb behind it.
        val avatarDp = screen.avatarSize * spec.avatar
        val avatarPx = dp(avatarDp)
        val corner = CallScreen.avatarCorner(call.avatarShape, avatarDp)
        ring.setUp(avatarPx, corner?.let { it / avatarDp }, moves)
        val orbBase = if (screen.layout == "orb") screen.avatarSize * CallLooks.ORB_LAYOUT_REST else avatarDp * CallLooks.ORB_REST
        val orbMax = if (screen.layout == "orb") screen.avatarSize * (CallLooks.ORB_LAYOUT_REST + CallLooks.ORB_LAYOUT_GAIN) else avatarDp * (CallLooks.ORB_REST + CallLooks.ORB_GAIN)
        orb.setUp(dp(orbBase), dp(orbMax))
        val overflow = dp(maxOf(orbMax, avatarDp * CallLooks.RIPPLE.scale) + 8)
        stage.addView(orb, LayoutParams(overflow, overflow, Gravity.CENTER))
        stage.addView(ring, LayoutParams(overflow, overflow, Gravity.CENTER))
        stage.addView(avatar, LayoutParams(avatarPx, avatarPx, Gravity.CENTER))
        renderAvatar()

        // The name, the status, the voice, a middle caption.
        nameView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, screen.nameSize.toFloat())
            Themed.face(this, t.font.heading, screen.nameWeight, graine)
            letterSpacing = t.font.letterSpacing.title.toFloat()
            maxLines = spec.nameLines
            ellipsize = TextUtils.TruncateAt.END
            this.gravity = if (start) Gravity.START else Gravity.CENTER_HORIZONTAL
            includeFontPadding = true
        }
        statusView.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, screen.statusSize.toFloat())
            Themed.face(this, t.font.body, screen.statusWeight, graine)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            this.gravity = if (start) Gravity.START else Gravity.CENTER_HORIZONTAL
            // Tabular digits: the timer does not jitter as it counts.
            fontFeatureSettings = "tnum"
            accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        }
        strip.setUp(screen.visualizer, start)
        for (cap in listOf(captionMiddle, captionBottom)) cap.setUp(t, screen.captionStyle, start, graine)

        fun wide() = LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        val holder = if (spec.card) card else column
        if (spec.nameFirst) {
            // The orb: the name and status first, the orb centred in the space left above the buttons.
            column.addView(nameView, wide())
            column.addView(statusView, wide().apply { topMargin = dp(CallLooks.STATUS_GAP) })
            column.addView(strip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(CallLooks.VISUALIZER_HEIGHT)).apply { topMargin = dp(CallLooks.VISUALIZER_GAP) })
            orbSlot.addView(stage, LayoutParams(avatarPx, avatarPx, Gravity.CENTER))
            column.addView(orbSlot, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
            column.addView(captionMiddle.wrap, wide().apply { topMargin = dp(CallLooks.CAPTION_ABOVE_BUTTONS) })
        } else {
            if (avatarPx > 0) holder.addView(stage, LinearLayout.LayoutParams(avatarPx, avatarPx).apply { this.gravity = gravity })
            holder.addView(nameView, wide().apply { topMargin = if (avatarPx > 0) dp(spec.nameGap) else 0 })
            holder.addView(statusView, wide().apply { topMargin = dp(CallLooks.STATUS_GAP) })
            holder.addView(strip, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dp(CallLooks.VISUALIZER_HEIGHT)).apply { topMargin = dp(CallLooks.VISUALIZER_GAP) })
            holder.addView(captionMiddle.wrap, wide().apply { topMargin = dp(CallLooks.CAPTION_GAP) })
            if (spec.card) {
                card.setPadding(dp(CallLooks.CARD_PADDING), dp(CallLooks.CARD_PADDING), dp(CallLooks.CARD_PADDING), dp(CallLooks.CARD_PADDING))
                column.addView(card, wide())
            }
        }
        column.gravity = gravity
        card.gravity = gravity
        val big = CallLooks.buttonSizes(screen.buttonSize).big
        val rowDp = big + labelBlockDp()
        safe.addView(column, LayoutParams(LayoutParams.MATCH_PARENT, if (spec.nameFirst) LayoutParams.MATCH_PARENT else LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = side; rightMargin = side
            topMargin = dp(spec.top)
            if (spec.nameFirst) bottomMargin = dp(CallLooks.BUTTONS_BOTTOM + rowDp + CallLooks.CAPTION_ABOVE_BUTTONS)
        })
        // A bottom caption: just above the call's buttons.
        val callRowDp = screen.buttonSize + labelBlockDp()
        safe.addView(captionBottom.wrap, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            leftMargin = side; rightMargin = side
            bottomMargin = dp(CallLooks.BUTTONS_BOTTOM + callRowDp + CallLooks.CAPTION_ABOVE_BUTTONS)
        })
        buildButtons()
        for (r in listOf(ringingRow, missedRow, callRow)) {
            safe.addView(r, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(CallLooks.BUTTONS_BOTTOM) })
        }
    }

    /** The label under a button and its gap: none without labels. */
    private fun labelBlockDp(): Double = if (screen.buttonLabels == "below") CallLooks.LABEL_GAP + Math.ceil(CallLooks.LABEL_SIZE * 1.3) else 0.0

    private fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL or Gravity.TOP
        clipChildren = false
        clipToPadding = false
    }

    private fun buildButtons() {
        for (r in listOf(ringingRow, missedRow, callRow)) { r.removeAllViews(); r.setPadding(dp(CallLooks.SIDE), 0, dp(CallLooks.SIDE), 0) }
        controlButtons.clear()
        decisionButtons.clear()
        val sizes = CallLooks.buttonSizes(screen.buttonSize)
        val widthDp = resources.displayMetrics.widthPixels / resources.displayMetrics.density
        val inner = widthDp - 2 * CallLooks.SIDE
        fun add(row: LinearLayout, b: CallButtonView) = row.addView(b, LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        fun button(action: String, icon: String, label: String, size: Double, share: Double, end: Boolean = false, tap: () -> Unit) =
            CallButtonView(context, this, action, icon, label, size, share, end, sizes.icon, tap)
        // Ringing (either kind): Decline and Answer, each centred in half the width.
        val decline = button("decline", SdkIcons.END, "Decline", sizes.big, inner / 2) { listener?.onDecline() }
        val answer = button("answer", SdkIcons.CALL, "Answer", sizes.big, inner / 2) { listener?.onAnswer() }
        add(ringingRow, decline); add(ringingRow, answer)
        // Missed: Close and Call back.
        val close = button("close", SdkIcons.CLOSE, "Close", sizes.big, inner / 2) { listener?.onClose() }
        val back = button("callback", SdkIcons.CALL, "Call back", sizes.big, inner / 2) { listener?.onCallBack() }
        add(missedRow, close); add(missedRow, back)
        decisionButtons += listOf("decline" to decline, "answer" to answer, "close" to close, "callback" to back)
        // Connecting, on the call, ended: the design's controls, in its order, each in an equal share.
        val controls = CallScreen.controls(call.controls, state?.chatAvailable == true)
        val share = inner / controls.size
        for (name in controls) {
            val b = when (name) {
                "mute" -> button("mute", SdkIcons.MIC, "Mute", sizes.button, share) { listener?.onMute() }
                "speaker" -> button("speaker", SdkIcons.SPEAKER, "Speaker", sizes.button, share) { listener?.onSpeaker() }
                "chat" -> button("chat", SdkIcons.CHAT, "Chat", sizes.button, share) {}
                else -> button("end", SdkIcons.END, "End", sizes.button, share, end = true) { listener?.onEnd() }
            }
            controlButtons[name] = b
            add(callRow, b)
        }
    }

    /** The avatar: the brand's logo, its glyph or the call name's letter, in its shape and colours. */
    private fun renderAvatar() {
        val spec = layoutSpec
        val avatarDp = screen.avatarSize * spec.avatar
        if (avatarDp <= 0) return
        val name = state?.name ?: ""
        val corner = CallScreen.avatarCorner(call.avatarShape, avatarDp)
        val logo = CallAvatar.logo(call, logoUrl)
        Mark.fill(avatar, CallAvatar.markTokens(t, call, logoUrl), avatarDp, avatarDp * CallScreen.AVATAR_GLYPH, call.avatarShape, corner,
            c(call.avatarFill), c(call.avatarText), null, logo, logoPlate, CallScreen.initial(name))
        (avatar.getChildAt(0) as? TextView)?.let { tv ->
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, (avatarDp * CallScreen.AVATAR_LETTER).toFloat())
            Themed.face(tv, t.font.heading, t.font.weights.title, graine)
        }
        // The blurred avatar of the backdrop is this avatar, drawn small, then 4 × avatarSize across.
        val side = dp(screen.avatarSize * 4).toFloat()
        backdrop.setAvatar(CallAvatar.bitmap(context, t, call, logoUrl, logoPlate, CallScreen.initial(name)) { bmp -> backdrop.setAvatar(bmp, avatarCentre(), side) }, avatarCentre(), side)
    }

    /**
     * Where the blurred avatar is centred, px: on the avatar in its slot; in the
     * orb and minimal layouts (an avatar mid-screen, or none), 42% down the
     * screen, centred across — as every renderer places it (DESIGN.md "Backdrop").
     */
    private fun avatarCentre(): Pair<Float, Float> {
        if (layoutSpec.nameFirst || layoutSpec.avatar == 0.0) return (width / 2f) to (height * 0.42f)
        val loc = IntArray(2); val mine = IntArray(2)
        stage.getLocationInWindow(loc); getLocationInWindow(mine)
        return if (stage.width > 0) (loc[0] - mine[0] + stage.width / 2f) to (loc[1] - mine[1] + stage.height / 2f)
        else (width / 2f) to (dp(CallLooks.SIDE + layoutSpec.top) + screen.avatarSize.toFloat() / 2)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (changed && this::screen.isInitialized) backdrop.moveAvatar(avatarCentre())
    }

    // ── the state ──────────────────────────────────────────────────────────

    fun render(s: State) {
        val was = state
        state = s
        if (!this::screen.isInitialized) return
        val rebuilt = was?.chatAvailable != s.chatAvailable
        if (rebuilt) buildButtons()
        if (was?.name != s.name) { nameView.text = s.name; renderAvatar() }
        val m = s.moment
        val look = screen.lookFor(m.wire)
        // New buttons take the look too.
        if (rebuilt || look != shownLook) applyLook(look, animate = shownLook != null && look != shownLook && moves)
        if (s.agentSpeaking && was?.agentSpeaking != true) speakingSince = SystemClock.uptimeMillis()

        statusView.text = CallScreen.status(m, call, s.connectedForMs, s.muted, s.agentSpeaking, s.ending)
        val ringing = m.ringing
        show(ringingRow, ringing, was != null)
        show(missedRow, m == CallScreen.Moment.MISSED, was != null)
        show(callRow, !ringing && m != CallScreen.Moment.MISSED, was != null)
        // Once ended, the call's buttons sit at 40% and do nothing.
        val ended = m == CallScreen.Moment.ENDED
        callRow.alpha = if (ended) CallLooks.ENDED_ALPHA.toFloat() else 1f
        for (b in controlButtons.values) b.setActive(!ended)
        renderControls(look)
        renderCaption(look)
        val connected = m == CallScreen.Moment.ON_CALL || m == CallScreen.Moment.RECONNECTING
        strip.visibility = if (connected && (screen.visualizer == "bars" || screen.visualizer == "wave")) VISIBLE else GONE
        orb.visibility = if (screen.layout == "orb" || (connected && screen.visualizer == "orb")) VISIBLE else GONE
        orb.live = connected
        ring.kind = screen.ringFor(m.wire)
        ring.connected = connected
        renderSpeaking(s)
        if (was?.moment != m && m != CallScreen.Moment.ON_CALL) wake()
        if (was?.moment != m && m == CallScreen.Moment.ON_CALL) lastTouch = SystemClock.uptimeMillis()
        schedule()
    }

    private fun applyLook(look: CallLook, animate: Boolean) {
        shownLook = look
        nameView.setTextColor(c(look.text))
        statusView.setTextColor(c(look.textMuted))
        val cardAlpha = maxOf(look.captionFillAlpha, CallLooks.CARD_MIN_ALPHA)
        card.background = if (layoutSpec.card) GradientDrawable().apply {
            cornerRadius = dp(CallLooks.CARD_RADIUS).toFloat()
            setColor(Themed.alpha(c(look.captionFill), cardAlpha))
        } else null
        ring.color = c(look.avatarRingColor)
        orb.color = c(look.visualizerColor)
        strip.color = c(look.visualizerColor)
        for (cap in listOf(captionMiddle, captionBottom)) cap.look = look
        for (b in decisionButtons) b.second.paint(look.action(b.first), on = false, frameOn = false)
        for (b in listOf(ringingRow, missedRow, callRow).flatMap { r -> (0 until r.childCount).map { r.getChildAt(it) } }) (b as? CallButtonView)?.labelColor(c(look.textMuted))
        backdrop.show(look.backdrop, if (animate) Design.motionMs(t.motion, Themed.animatorScale(context)) else 0L, moves)
    }

    private fun renderControls(look: CallLook) {
        val s = state ?: return
        controlButtons["mute"]?.let { b ->
            b.paint(look.action("mute"), on = s.muted, frameOn = s.muted)
            b.set(if (s.muted) SdkIcons.MIC_OFF else SdkIcons.MIC, if (s.muted) "Unmute" else "Mute", s.muted)
        }
        controlButtons["speaker"]?.let { b ->
            b.paint(look.action("speaker"), on = s.speaker, frameOn = s.speaker)
            b.set(SdkIcons.SPEAKER, "Speaker", s.speaker)
        }
        controlButtons["chat"]?.paint(look.action("chat"), on = false, frameOn = false)
        controlButtons["end"]?.paint(look.action("end"), on = false, frameOn = false)
    }

    /** The latest line, in the design's caption style and place: the agent's in `text`, the customer's in `textMuted`, at 75% while arriving. */
    private fun renderCaption(look: CallLook) {
        val s = state ?: return
        val cap = s.caption
        val on = call.captions && cap != null && cap.second.isNotBlank() && s.moment == CallScreen.Moment.ON_CALL
        val bottom = screen.captionPosition == "bottom"
        val (shown, hidden) = if (bottom) captionBottom to captionMiddle else captionMiddle to captionBottom
        hidden.show(null, look)
        val before = shown.text
        shown.show(if (on) cap else null, look)
        // A new line fades in over the design's duration.
        if (on && before.isNullOrEmpty() && cap!!.first == Role.AGENT) Themed.enter(shown.wrap, t, toAlpha = 1f)
    }

    /** On the call, the avatar scales to 1.06 while the agent's line is arriving, back when it stops — at the design's pace. */
    private fun renderSpeaking(s: State) {
        val speaking = s.agentSpeaking && s.moment == CallScreen.Moment.ON_CALL
        val target = if (speaking) CallLooks.SPEAKING_SCALE.toFloat() else 1f
        if (avatar.scaleX == target) return
        val ms = CallScreen.motionMs(t.motion, Themed.animatorScale(context))
        if (ms <= 0) { avatar.scaleX = target; avatar.scaleY = target; return }
        avatar.animate().scaleX(target).scaleY(target).setDuration(ms).setInterpolator(Themed.interpolator(t.motion)).start()
    }

    /** The change from one set of buttons to another fades in over the design's duration. */
    private fun show(v: View, on: Boolean, fade: Boolean) {
        val was = v.visibility == VISIBLE
        v.visibility = if (on) VISIBLE else GONE
        if (on && !was && fade) Themed.enter(v, t)
    }

    // ── motion ─────────────────────────────────────────────────────────────

    /** The activity's resume (true) and pause (false). Nothing moves while it is false. */
    fun setRunning(on: Boolean) {
        running = on
        if (on) { lastFrame = SystemClock.uptimeMillis(); schedule() } else backdrop.pause(SystemClock.uptimeMillis())
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) schedule() else backdrop.pause(SystemClock.uptimeMillis())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(frame)
        ticking = false
    }

    /** A touch anywhere wakes a backdrop that came to rest. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) { lastTouch = SystemClock.uptimeMillis(); wake() }
        return super.dispatchTouchEvent(ev)
    }

    private fun wake() {
        if (backdrop.resting) { backdrop.wake(SystemClock.uptimeMillis()); schedule() }
    }

    private fun schedule() {
        if (ticking || !running || !isAttachedToWindow || windowVisibility != VISIBLE) return
        ticking = true
        Choreographer.getInstance().postFrameCallback(frame)
    }

    private val frame = Choreographer.FrameCallback {
        ticking = false
        tick()
    }

    private fun tick() {
        val s = state ?: return
        if (!running || !this::screen.isInitialized) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrame).coerceIn(0L, 200L).toDouble()
        lastFrame = now
        val connected = s.moment == CallScreen.Moment.ON_CALL || s.moment == CallScreen.Moment.RECONNECTING
        // The voice: the call's level where it reports one, else the speaking signal; nothing off the call.
        val fresh = s.level != null && now - s.levelAt < LEVEL_FRESH_MS
        val target = when {
            !connected -> 0.0
            fresh -> CallLooks.voiceLevel(s.level!!.toDouble(), screen.visualizerSensitivity)
            s.agentSpeaking -> CallLooks.speakingLevel((now - speakingSince).toDouble())
            else -> 0.0
        }
        shownLevel = if (moves) CallLooks.follow(shownLevel, target, dt) else 0.0
        if (shownLevel < 0.002 && target == 0.0) shownLevel = 0.0
        val heard = target > 0.05 || s.agentSpeaking
        // The backdrop rests on a connected call nobody has touched for a while.
        if (s.moment == CallScreen.Moment.ON_CALL && !backdrop.resting && now - lastTouch >= CallLooks.IDLE_MS) backdrop.rest(now)
        val drew = backdrop.tick(now, moves)
        // Glass buttons show the backdrop behind them: they move with it.
        if (drew) for (r in listOf(ringingRow, missedRow, callRow)) if (r.visibility == VISIBLE) for (i in 0 until r.childCount) (r.getChildAt(i) as? CallButtonView)?.refreshFrost()
        var more = drew
        more = ring.tick(now, if (connected) shownLevel else 0.0, heard) || more
        more = orb.tick(shownLevel, moves) || more
        if (strip.visibility == VISIBLE) more = strip.tick(now, shownLevel, moves) || more
        // The voice keeps frames coming only where something draws it: a strip, a live orb, a ring on the call.
        val voiceDrawn = strip.visibility == VISIBLE || (orb.visibility == VISIBLE && orb.live) || (connected && ring.kind != "none")
        if (moves && voiceDrawn && (target > 0 || shownLevel > 0)) more = true
        if (more) schedule()
    }

    // What the buttons draw with.
    internal fun tokensForButtons(): Pair<CallTokens, CallScreenTokens> = call to screen
    internal fun iconStyle(): String = t.icons.style
    internal fun iconStroke(): Double = t.icons.stroke
    internal fun motionMsForButtons(): Long = Design.motionMs(t.motion, Themed.animatorScale(context))

    /** A button's label: 13, the body family at its body weight. */
    internal fun labelFace(tv: TextView) = Themed.face(tv, t.font.body, t.font.weights.body, graine)

    /** The backdrop and its moving parts — CallBackdropLayer — for a glass button to draw beneath itself. */
    internal fun paintBackdropInto(canvas: Canvas, view: View) = backdrop.paintInto(canvas, view)

    companion object {
        /** A level reading this old is no longer the voice: the call stopped reporting. */
        const val LEVEL_FRESH_MS = 700L
    }
}

/** What stands in the call screen's avatar — the same rule for the avatar and for the backdrop's blurred copy of it. */
internal object CallAvatar {
    /** "logo" puts the logo first (no logo: the glyph, else the letter); "glyph" the glyph (else the logo); "initials" neither. */
    fun markTokens(t: ThemeTokens, call: CallTokens, logoUrl: String?): ThemeTokens = when (call.avatar) {
        "logo" -> if (logoUrl != null) t.copy(icons = t.icons.copy(launcher = null)) else t
        "initials" -> t.copy(icons = t.icons.copy(launcher = null))
        else -> t
    }

    fun logo(call: CallTokens, logoUrl: String?): String? = if (call.avatar == "initials") null else logoUrl

    /**
     * The avatar drawn into a small bitmap (it is blurred to a glow): its
     * plate or fill in its shape, and its mark. A logo arrives later: the
     * bitmap without it now, and [ready] with it when it lands.
     */
    fun bitmap(ctx: Context, t: ThemeTokens, call: CallTokens, logoUrl: String?, plate: LogoPlate, initial: String, ready: (Bitmap) -> Unit): Bitmap? {
        val marks = markTokens(t, call, logoUrl)
        val logo = logo(call, logoUrl)
        val glyph = marks.icons.launcher
        if (glyph == null && logo != null) {
            ImageLoader.fetch(logo) { img -> if (img != null) ready(draw(t, call, glyph, img, plate, initial)) }
            return null
        }
        return draw(t, call, glyph, null, plate, initial)
    }

    private fun draw(t: ThemeTokens, call: CallTokens, glyph: LauncherGlyph?, logo: Bitmap?, plate: LogoPlate, initial: String): Bitmap {
        val side = 96
        val bmp = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val showsLogo = glyph == null && logo != null
        val plateLook = Logo.plate(if (showsLogo) plate else LogoPlate.ACCENT, t.colors.accent, t.colors.border, t.shape.borderWidth)
        p.color = if (showsLogo) Themed.color(if (plateLook.outline == null) call.avatarFill else plateLook.fill) else Themed.color(call.avatarFill)
        val r = CallScreen.avatarCorner(call.avatarShape, side.toDouble())
        if (r == null) canvas.drawOval(0f, 0f, side.toFloat(), side.toFloat(), p) else canvas.drawRoundRect(0f, 0f, side.toFloat(), side.toFloat(), r.toFloat(), r.toFloat(), p)
        val ink = Themed.color(call.avatarText)
        when {
            glyph != null -> GlyphDrawable.of(glyph, t.icons.stroke, ink, (side * CallScreen.AVATAR_GLYPH).toInt())?.let { g ->
                val gs = (side * CallScreen.AVATAR_GLYPH).toInt()
                g.setBounds((side - gs) / 2, (side - gs) / 2, (side + gs) / 2, (side + gs) / 2)
                g.draw(canvas)
            }
            logo != null -> {
                val box = Logo.box(call.avatarShape, side.toDouble()).toFloat()
                val k = minOf(box / logo.width, box / logo.height)
                val w = logo.width * k; val h = logo.height * k
                canvas.drawBitmap(logo, null, RectF((side - w) / 2, (side - h) / 2, (side + w) / 2, (side + h) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            else -> {
                p.color = ink
                p.textSize = (side * CallScreen.AVATAR_LETTER).toFloat()
                p.textAlign = Paint.Align.CENTER
                p.isFakeBoldText = true
                val y = side / 2f - (p.descent() + p.ascent()) / 2
                canvas.drawText(initial, side / 2f, y, p)
            }
        }
        return bmp
    }
}

/**
 * THE BACKDROP — contract/DESIGN.md "Call screen" → Backdrop: a fill, a
 * linear or radial gradient, a mesh of glows (a small bitmap scaled up
 * smoothly), or the avatar blurred under the stops' scrim; moving as the
 * design says, coming to rest, and fading from one moment's look to the next.
 */
internal class CallBackdropLayer(context: Context) : FrameLayout(context) {
    private var current: CallBackdrop? = null
    private var previous: CallBackdrop? = null
    private var fadeStart = 0L
    private var fadeMs = 0L
    /** When the current motion began (uptimeMillis): the frame is `now − motionStart` ms in. */
    private var motionStart = SystemClock.uptimeMillis()
    /** The motion's time where it stopped (paused or at rest), so it resumes where it was. */
    private var heldAt: Double? = null
    var resting = false
        private set
    private var settleStart = 0L
    private var settleFrom: Double = 0.0
    private var lastDraw = 0L

    private val base = PaintView(context, Part.FILL)
    private val blur = AvatarBlurView(context)
    private val scrim = PaintView(context, Part.SCRIM)

    init {
        addView(base, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(blur, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    enum class Part { FILL, SCRIM }

    /** Show `b`, fading from what was shown over `fadeMs` (0: at once). */
    fun show(b: CallBackdrop, fadeMs: Long, moves: Boolean) {
        if (b == current) return
        previous = if (fadeMs > 0) current else null
        current = b
        this.fadeMs = fadeMs
        fadeStart = SystemClock.uptimeMillis()
        if (!moves || b.motion == "none") heldAt = 0.0
        blur.visibility = if (b.type == "blurred-avatar") VISIBLE else GONE
        scrim.visibility = if (b.type == "blurred-avatar") VISIBLE else GONE
        redraw()
    }

    fun setAvatar(bmp: Bitmap?, centre: Pair<Float, Float>, sidePx: Float) { blur.set(bmp, centre, sidePx); redraw() }
    fun moveAvatar(centre: Pair<Float, Float>) { blur.move(centre); redraw() }

    /** The time into the motion the screen is drawn at: frozen while held, eased to rest while settling. */
    private fun timeAt(now: Long): Double = heldAt ?: (now - motionStart).toDouble()

    /** Whether there is motion to show at all. */
    fun moving(moves: Boolean): Boolean = moves && current?.let { it.motion != "none" && it.type != "solid" } == true

    /** Stop moving, holding the frame (the activity paused, the screen went off). */
    fun pause(now: Long) { if (heldAt == null) heldAt = (now - motionStart).toDouble() }

    /** Come to rest over CallLooks.SETTLE_MS. */
    fun rest(now: Long) {
        if (resting) return
        resting = true
        settleStart = now
        settleFrom = timeAt(now)
        heldAt = null
    }

    /** Move again, from rest. */
    fun wake(now: Long) {
        resting = false
        motionStart = now
        heldAt = null
    }

    /** One frame: true while there is more to show. Draws at most every 33 ms (motion this slow needs no more). */
    fun tick(now: Long, moves: Boolean): Boolean {
        val fading = previous != null && now - fadeStart < fadeMs
        if (previous != null && !fading) { previous = null; redraw() }
        val motion = moving(moves) && !(resting && now - settleStart >= CallLooks.SETTLE_MS)
        if (motion && heldAt != null && !resting) { motionStart = now - heldAt!!.toLong(); heldAt = null }
        if (!motion && !fading) return false
        if (fading || now - lastDraw >= 33) { lastDraw = now; redraw() }
        return true
    }

    private fun redraw() { base.invalidate(); blur.invalidate(); scrim.invalidate() }

    /** The last frame worked out, and what for: every layer and every glass button of one tick draws the same one. */
    private var cached: Pair<List<Any>, CallLooks.Frame>? = null

    /** The frame to draw at `now`, in dp: settling blends the moving frame into rest. */
    private fun frameFor(b: CallBackdrop, wDp: Double, hDp: Double, now: Long): CallLooks.Frame {
        val key = listOf(b, wDp, hDp, now / 16, resting)
        cached?.let { (k, f) -> if (k == key) return f }
        return computeFrame(b, wDp, hDp, now).also { cached = key to it }
    }

    private fun computeFrame(b: CallBackdrop, wDp: Double, hDp: Double, now: Long): CallLooks.Frame {
        if (resting) {
            val e = ((now - settleStart).toDouble() / CallLooks.SETTLE_MS).coerceIn(0.0, 1.0)
            val k = 1 - (1 - e) * (1 - e)
            val from = CallLooks.frame(b, wDp, hDp, settleFrom + (now - settleStart))
            val to = CallLooks.frame(b, wDp, hDp, 0.0)
            return blend(from, to, k)
        }
        return CallLooks.frame(b, wDp, hDp, timeAt(now))
    }

    private fun blend(a: CallLooks.Frame, b: CallLooks.Frame, k: Double): CallLooks.Frame {
        fun l(x: Double, y: Double) = x + (y - x) * k
        return CallLooks.Frame(
            CallLooks.Line(l(a.line.x0, b.line.x0), l(a.line.y0, b.line.y0), l(a.line.x1, b.line.x1), l(a.line.y1, b.line.y1)),
            CallLooks.Radial(l(a.radial.cx, b.radial.cx), l(a.radial.cy, b.radial.cy), l(a.radial.r, b.radial.r)),
            a.mesh.mapIndexed { i, g -> CallLooks.Glow(l(g.u, b.mesh[i].u), l(g.v, b.mesh[i].v), g.color) },
            CallLooks.AvatarDrift(l(a.avatar.dx, b.avatar.dx), l(a.avatar.dy, b.avatar.dy), l(a.avatar.scale, b.avatar.scale)),
        )
    }

    private val painter = BackdropPainter()

    /** Draw the backdrop — what shows behind `view` — into `canvas`, translated to where `view` sits: a glass button's frost. */
    fun paintInto(canvas: Canvas, view: View) {
        val b = current ?: return
        val mine = IntArray(2); val theirs = IntArray(2)
        getLocationInWindow(mine); view.getLocationInWindow(theirs)
        canvas.save()
        canvas.translate((mine[0] - theirs[0]).toFloat(), (mine[1] - theirs[1]).toFloat())
        val d = resources.displayMetrics.density
        val f = frameFor(b, width / d.toDouble(), height / d.toDouble(), SystemClock.uptimeMillis())
        painter.paint(canvas, b, f, width, height, d, Part.FILL, 255)
        if (b.type == "blurred-avatar") painter.paint(canvas, b, f, width, height, d, Part.SCRIM, 255)
        canvas.restore()
    }

    /** One layer of the backdrop: the fill (and, for a blurred avatar, its colour field), or the scrim over the avatar. */
    private inner class PaintView(context: Context, private val part: Part) : View(context) {
        override fun onDraw(canvas: Canvas) {
            val b = current ?: return
            val d = resources.displayMetrics.density
            val now = SystemClock.uptimeMillis()
            val wDp = width / d.toDouble(); val hDp = height / d.toDouble()
            val fade = previous?.let { ((now - fadeStart).toDouble() / maxOf(1L, fadeMs)).coerceIn(0.0, 1.0) }
            previous?.let { p ->
                if (part == Part.FILL || p.type == "blurred-avatar") painter.paint(canvas, p, frameFor(p, wDp, hDp, now), width, height, d, part, 255)
            }
            if (part == Part.SCRIM && b.type != "blurred-avatar") return
            painter.paint(canvas, b, frameFor(b, wDp, hDp, now), width, height, d, part, ((fade ?: 1.0) * 255).toInt())
        }
    }

    /** The avatar at 4 × its size, centred on it, blurred by 48 (RenderEffect from API 31; before it, a tiny bitmap scaled up smoothly). */
    private inner class AvatarBlurView(context: Context) : View(context) {
        private var full: Bitmap? = null
        private var small: Bitmap? = null
        private var centre = 0f to 0f
        private var sidePx = 0f
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        fun set(bmp: Bitmap?, at: Pair<Float, Float>, side: Float) {
            full = bmp
            sidePx = side
            small = bmp?.let { Bitmap.createScaledBitmap(it, 16, 16, true) }
            centre = at
            if (Build.VERSION.SDK_INT >= 31) {
                val r = CallLooks.BLUR.toFloat() * resources.displayMetrics.density
                setRenderEffect(if (bmp != null) RenderEffect.createBlurEffect(r, r, Shader.TileMode.DECAL) else null)
            }
            invalidate()
        }

        fun move(at: Pair<Float, Float>) { centre = at; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val b = current ?: return
            if (b.type != "blurred-avatar") return
            val img = (if (Build.VERSION.SDK_INT >= 31) full else small) ?: return
            val d = resources.displayMetrics.density
            val f = frameFor(b, width / d.toDouble(), height / d.toDouble(), SystemClock.uptimeMillis())
            val side = sidePx * f.avatar.scale.toFloat()
            val cx = centre.first + (f.avatar.dx * d).toFloat()
            val cy = centre.second + (f.avatar.dy * d).toFloat()
            canvas.drawBitmap(img, null, RectF(cx - side / 2, cy - side / 2, cx + side / 2, cy + side / 2), paint)
        }
    }
}

/** Draws one CallBackdrop at one frame — the screen's, or a glass button's frost. */
internal class BackdropPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var mesh: Bitmap? = null
    private var meshKey: List<CallLooks.Glow>? = null
    private val dst = RectF()

    fun paint(canvas: Canvas, b: CallBackdrop, f: CallLooks.Frame, w: Int, h: Int, density: Float, part: CallBackdropLayer.Part, alpha: Int) {
        paint.alpha = 255
        paint.shader = null
        val colors = b.stops.map { Themed.color(it.color) }.toIntArray()
        val stops = b.stops.map { it.at.toFloat() }.toFloatArray()
        fun linear(): Shader = if (colors.size == 1) LinearGradient(0f, 0f, 0f, 1f, colors[0], colors[0], Shader.TileMode.CLAMP)
        else LinearGradient((f.line.x0 * density).toFloat(), (f.line.y0 * density).toFloat(), (f.line.x1 * density).toFloat(), (f.line.y1 * density).toFloat(), colors, stops, Shader.TileMode.CLAMP)
        when {
            part == CallBackdropLayer.Part.SCRIM -> {
                paint.shader = linear()
                paint.alpha = (alpha * b.scrim).toInt().coerceIn(0, 255)
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            b.type == "solid" || colors.size == 1 -> {
                paint.color = colors[0]
                paint.alpha = alpha
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            b.type == "radial" -> {
                paint.shader = RadialGradient((f.radial.cx * density).toFloat(), (f.radial.cy * density).toFloat(), maxOf(1f, (f.radial.r * density).toFloat()), colors, stops, Shader.TileMode.CLAMP)
                paint.alpha = alpha
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
            b.type == "mesh" -> {
                val bmp = meshBitmap(f.mesh)
                paint.alpha = alpha
                dst.set(0f, 0f, w.toFloat(), h.toFloat())
                canvas.drawBitmap(bmp, null, dst, paint)
            }
            else -> {
                // linear, and the blurred avatar's colour field
                paint.shader = linear()
                paint.alpha = alpha
                canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            }
        }
        paint.shader = null
    }

    /** The mesh at these glows, as a small bitmap (CallLooks.MESH_COLS × MESH_ROWS, each pixel at its centre). */
    private fun meshBitmap(glows: List<CallLooks.Glow>): Bitmap {
        val cached = mesh
        if (cached != null && meshKey == glows) return cached
        val cols = CallLooks.MESH_COLS; val rows = CallLooks.MESH_ROWS
        val px = IntArray(cols * rows)
        for (y in 0 until rows) for (x in 0 until cols) px[y * cols + x] = CallLooks.meshArgb(glows, (x + 0.5) / cols, (y + 0.5) / rows)
        val bmp = cached ?: Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
        bmp.setPixels(px, 0, cols, 0, 0, cols, rows)
        mesh = bmp
        meshKey = glows
        return bmp
    }
}

/**
 * THE AVATAR'S RING — pulse, ripple or glow, in the moment's ring colour,
 * behind the avatar in its shape (contract/DESIGN.md → Avatar → Rings). With
 * no motion, none.
 */
internal class RingView(context: Context) : View(context) {
    var kind = "none"
        set(v) { if (field != v) { field = v; began = SystemClock.uptimeMillis(); invalidate() } }
    var color = Color.WHITE
        set(v) { field = v; invalidate() }
    var connected = false
    private var avatarPx = 0
    /** The corner as a share of the side; null: round. */
    private var cornerShare: Double? = null
    private var moves = false
    private var began = SystemClock.uptimeMillis()
    private var level = 0.0
    private var heard = false
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setUp(avatarPx: Int, cornerShare: Double?, moves: Boolean) {
        this.avatarPx = avatarPx
        this.cornerShare = cornerShare
        this.moves = moves
        invalidate()
    }

    /** One frame; true while it moves. */
    fun tick(now: Long, level: Double, heard: Boolean): Boolean {
        this.level = level
        this.heard = heard
        val runs = runs()
        if (runs || kind == "glow") invalidate()
        return runs
    }

    /** A connected call's pulse or ripple runs only while the agent is heard; every other moment wearing one runs it throughout. */
    private fun runs(): Boolean = moves && avatarPx > 0 && when (kind) {
        "pulse", "ripple" -> !connected || heard
        "glow" -> !connected || level > 0.0
        else -> false
    }

    override fun onDraw(canvas: Canvas) {
        if (!moves || avatarPx <= 0 || kind == "none") return
        val now = SystemClock.uptimeMillis()
        val cx = width / 2f; val cy = height / 2f
        val d = resources.displayMetrics.density
        when (kind) {
            "glow" -> {
                val b = if (connected) level else 0.5 - 0.5 * Math.cos(2 * Math.PI * (now - began) / (2.0 * CallLooks.GLOW_PERIOD_MS))
                val strength = 0.5 + 0.5 * b
                val scale = 1 + (CallLooks.GLOW_BREATHE - 1) * b
                val r = (avatarPx * CallLooks.GLOW_REACH * scale / 2).toFloat()
                val a = (CallLooks.GLOW_ALPHA_HIGH * strength * 255).toInt().coerceIn(0, 255)
                glow.shader = RadialGradient(cx, cy, maxOf(1f, r), intArrayOf(Themed.alpha(color, a / 255.0), Themed.alpha(color, a / 255.0), Themed.alpha(color, 0.0)),
                    floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
                canvas.drawCircle(cx, cy, r, glow)
            }
            "pulse", "ripple" -> {
                if (connected && !heard) return
                val spec = if (kind == "pulse") CallLooks.PULSE else CallLooks.RIPPLE
                line.strokeWidth = (spec.width * d).toFloat()
                for (i in 0 until spec.count) {
                    val p = ((((now - began).toDouble() / spec.periodMs) + i.toDouble() / spec.count) % 1.0)
                    val s = 1 + (spec.scale - 1) * p
                    line.color = Themed.alpha(color, spec.alpha * (1 - p))
                    val half = (avatarPx * s / 2).toFloat()
                    val corner = cornerShare
                    if (corner == null) canvas.drawCircle(cx, cy, half, line)
                    else canvas.drawRoundRect(cx - half, cy - half, cx + half, cy + half, (half * 2 * corner).toFloat(), (half * 2 * corner).toFloat(), line)
                }
            }
        }
    }
}

/** THE ORB — two soft discs behind the avatar that swell with the agent's voice (contract/DESIGN.md → The voice). */
internal class OrbView(context: Context) : View(context) {
    var color = Color.WHITE
        set(v) { field = v; invalidate() }
    /** On a connected call it follows the voice; otherwise it stands still at rest. */
    var live = false
    private var restPx = 0
    private var maxPx = 0
    private var level = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setUp(restPx: Int, maxPx: Int) { this.restPx = restPx; this.maxPx = maxPx; invalidate() }

    fun tick(level: Double, moves: Boolean): Boolean {
        val l = if (live && moves) level else 0.0
        if (l == this.level) return false
        this.level = l
        invalidate()
        return l > 0
    }

    override fun onDraw(canvas: Canvas) {
        if (restPx <= 0) return
        val cx = width / 2f; val cy = height / 2f
        val outer = (restPx + (maxPx - restPx) * level).toFloat() / 2
        for (r in listOf(outer, outer * CallLooks.ORB_INNER.toFloat())) {
            paint.shader = RadialGradient(cx, cy, maxOf(1f, r),
                intArrayOf(Themed.alpha(color, CallLooks.ORB_INNER_ALPHA), Themed.alpha(color, CallLooks.ORB_OUTER_ALPHA), Themed.alpha(color, 0.0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, r, paint)
        }
    }
}

/** THE VOICE AS BARS OR A WAVE — in the strip under the status (contract/DESIGN.md → The voice). */
internal class VoiceStripView(context: Context) : View(context) {
    var color = Color.WHITE
        set(v) { field = v; invalidate() }
    private var style = "none"
    private var start = false
    private var level = 0.0
    private var now = SystemClock.uptimeMillis()
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wave = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun setUp(style: String, start: Boolean) { this.style = style; this.start = start; invalidate() }

    fun tick(now: Long, level: Double, moves: Boolean): Boolean {
        this.now = now
        val l = if (moves) level else 0.0
        val changed = l != this.level || l > 0
        this.level = l
        if (changed) invalidate()
        return l > 0
    }

    override fun onDraw(canvas: Canvas) {
        val d = resources.displayMetrics.density
        val cy = height / 2f
        when (style) {
            "bars" -> {
                val bw = (CallLooks.BAR_WIDTH * d).toFloat()
                val gap = (CallLooks.BAR_GAP * d).toFloat()
                val total = CallLooks.BARS * bw + (CallLooks.BARS - 1) * gap
                var x = if (start) 0f else (width - total) / 2
                bar.color = color
                for (i in 0 until CallLooks.BARS) {
                    // Each wobbles between 35% and 100% of its height over 600 ms each way, bar i starting i × 110 ms late.
                    val wobble = if (level > 0) 0.675 - 0.325 * Math.cos(2 * Math.PI * (now - i * 110L) / (2.0 * CallLooks.BAR_WOBBLE_MS)) else 1.0
                    val hDp = CallLooks.BAR_REST + (CallLooks.BAR_MAX - CallLooks.BAR_REST) * level * CallLooks.BAR_WEIGHTS[i] * wobble
                    val h = (hDp * d).toFloat()
                    canvas.drawRoundRect(x, cy - h / 2, x + bw, cy + h / 2, bw / 2, bw / 2, bar)
                    x += bw + gap
                }
            }
            "wave" -> {
                val w = (CallLooks.WAVE_WIDTH * d).toFloat()
                val x0 = if (start) 0f else (width - w) / 2
                wave.strokeWidth = (CallLooks.WAVE_STROKE * d).toFloat()
                wave.color = if (level > 0) color else Themed.alpha(color, CallLooks.WAVE_REST_ALPHA * (Color.alpha(color) / 255.0))
                path.reset()
                val steps = 48
                val amp = CallLooks.WAVE_AMPLITUDE * level * d
                val phase = (now % CallLooks.WAVE_PERIOD_MS).toDouble() / CallLooks.WAVE_PERIOD_MS
                for (s in 0..steps) {
                    val u = s.toDouble() / steps
                    val y = cy + (amp * Math.sin(Math.PI * u) * Math.sin(2 * Math.PI * (CallLooks.WAVE_CYCLES * u + phase))).toFloat()
                    val x = x0 + (u * w).toFloat()
                    if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, wave)
            }
        }
    }
}

/**
 * THE CAPTION — plain, on a card, as a subtitle band or a chat bubble
 * (contract/DESIGN.md → Caption). [wrap] is what the screen places; the
 * caption itself sits in it where its style says.
 */
internal class CaptionView(context: Context) : TextView(context) {
    val wrap = FrameLayout(context)
    private var style = "plain"
    private var start = false
    var look: CallLook? = null

    init {
        wrap.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        wrap.visibility = View.GONE
        ellipsize = TextUtils.TruncateAt.START
    }

    private fun dp(v: Double) = Math.round(v * resources.displayMetrics.density).toInt()

    fun setUp(t: ThemeTokens, style: String, start: Boolean, graine: GraineInApp?) {
        this.style = style
        this.start = start
        val size = when (style) { "card" -> 16.0; "subtitle", "bubble" -> 15.0; else -> CallScreen.CAPTION_SP }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        Themed.face(this, t.font.body, t.font.weights.strong, graine)
        Ui.lineHeight(this, (size * CallScreen.CAPTION_LINE).toFloat())
        maxLines = if (style == "subtitle") 3 else CallScreen.CAPTION_LINES
        when (style) {
            "card" -> setPadding(dp(16.0), dp(12.0), dp(16.0), dp(12.0))
            "subtitle" -> setPadding(dp(10.0), dp(6.0), dp(10.0), dp(6.0))
            "bubble" -> setPadding(dp(14.0), dp(10.0), dp(14.0), dp(10.0))
            else -> setPadding(0, 0, 0, 0)
        }
    }

    /** Show this line (null: none), in this look. */
    fun show(cap: Triple<Role, String, Boolean>?, look: CallLook) {
        this.look = look
        if (cap == null) { text = ""; wrap.visibility = View.GONE; return }
        wrap.visibility = View.VISIBLE
        text = cap.second.trim()
        val agent = cap.first != Role.CUSTOMER
        setTextColor(Themed.color(if (agent) look.text else look.textMuted))
        alpha = if (cap.third) VoiceBar.LIVE_ALPHA else 1f
        val fill = Themed.alpha(Themed.color(look.captionFill), look.captionFillAlpha)
        val lp = layoutParams as FrameLayout.LayoutParams
        when (style) {
            "card" -> {
                background = GradientDrawable().apply { cornerRadius = dp(20.0).toFloat(); setColor(fill) }
                gravity = if (start) Gravity.START else Gravity.CENTER_HORIZONTAL
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.gravity = Gravity.TOP
            }
            "subtitle" -> {
                background = GradientDrawable().apply { cornerRadius = dp(10.0).toFloat(); setColor(fill) }
                gravity = Gravity.CENTER_HORIZONTAL
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT; lp.gravity = Gravity.CENTER_HORIZONTAL
            }
            "bubble" -> {
                // The agent's at the leading edge, the customer's at the trailing; the speaker's lower corner tighter.
                val big = dp(18.0).toFloat(); val small = dp(6.0).toFloat()
                val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
                val leadingLow = if (agent) small else big
                val trailingLow = if (agent) big else small
                val (bl, br) = if (rtl) trailingLow to leadingLow else leadingLow to trailingLow
                background = GradientDrawable().apply { cornerRadii = floatArrayOf(big, big, big, big, br, br, bl, bl); setColor(fill) }
                gravity = Gravity.START
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                lp.gravity = if (agent) Gravity.START else Gravity.END
                maxWidth = (wrap.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels) * 85 / 100
            }
            else -> {
                background = null
                gravity = if (start) Gravity.START else Gravity.CENTER_HORIZONTAL
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT; lp.gravity = Gravity.TOP
            }
        }
        layoutParams = lp
    }
}

/**
 * ONE CALL BUTTON — its face (filled, glass or an outline; round, a squircle
 * or a pill) with the SDK's icon, and its label under it (contract/DESIGN.md →
 * Buttons). The screen reader always hears the label.
 */
internal class CallButtonView(
    context: Context,
    private val screen: CallScreenView,
    private val action: String,
    private var icon: String,
    private var label: String,
    private val sizeDp: Double,
    private val shareDp: Double,
    private val end: Boolean,
    private val iconDp: Double,
    onTap: () -> Unit,
) : LinearLayout(context) {
    private val face = Face(context)
    private val caption = TextView(context)
    private var fill = CallAction("#333333", 1.0, "#FFFFFF", null, "#FFFFFF", "#000000")
    private var on = false
    private var radiusPx = 0f
    private var radiusAnim: ValueAnimator? = null

    private fun dp(v: Double) = Math.round(v * resources.displayMetrics.density).toInt()

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        clipChildren = false
        val owner = screenTokens()
        val frame = CallLooks.buttonFrame(owner.second.buttonShape, sizeDp, shareDp, end, owner.first.endShape, false)
        radiusPx = dp(frame.radius).toFloat()
        face.isClickable = true
        face.isFocusable = true
        face.setOnClickListener { onTap() }
        addView(face, LayoutParams(dp(frame.width), dp(frame.height)))
        caption.gravity = Gravity.CENTER
        caption.maxLines = 1
        caption.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, CallLooks.LABEL_SIZE.toFloat())
        caption.text = label
        caption.visibility = if (owner.second.buttonLabels == "below") View.VISIBLE else View.GONE
        addView(caption, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(CallLooks.LABEL_GAP) })
        describe(null)
    }

    /** The screen's call tokens and look, for this button's shape and labels. */
    private fun screenTokens(): Pair<CallTokens, CallScreenTokens> = screen.tokensForButtons()

    fun labelColor(c: Int) {
        caption.setTextColor(c)
        screen.labelFace(caption)
    }

    /** The icon and the label (mute's change as it toggles); `checked` for a toggle. */
    fun set(icon: String, label: String, checked: Boolean?) {
        this.icon = icon
        this.label = label
        caption.text = label
        describe(checked)
        face.invalidate()
    }

    private fun describe(checked: Boolean?) {
        face.contentDescription = if (checked == null) label else "$label, ${if (checked) "on" else "off"}"
    }

    /** This button's colours in the moment's look, and whether it is on; `frameOn` squares a pill's corners. */
    fun paint(a: CallAction, on: Boolean, frameOn: Boolean) {
        fill = a
        val changed = this.on != on
        this.on = on
        val (call, look) = screenTokens()
        val frame = CallLooks.buttonFrame(look.buttonShape, sizeDp, shareDp, end, call.endShape, frameOn)
        val target = dp(frame.radius).toFloat()
        if (target != radiusPx) {
            radiusAnim?.cancel()
            val ms = if (changed) screen.motionMsForButtons() else 0L
            if (ms <= 0) { radiusPx = target; face.invalidateOutline() }
            else radiusAnim = ValueAnimator.ofFloat(radiusPx, target).apply {
                duration = ms
                addUpdateListener { radiusPx = it.animatedValue as Float; face.invalidateOutline(); face.invalidate() }
                start()
            }
        }
        face.frost = a.fillAlpha > 0 && a.fillAlpha < 1 && !on && Build.VERSION.SDK_INT >= 31
        face.invalidate()
    }

    /** An ended call's buttons do nothing. */
    fun setActive(active: Boolean) {
        face.isEnabled = active
        face.isClickable = active
    }

    /** The face: its fill or glass or outline, and the icon. Clipped to its shape. */
    private inner class Face(context: Context) : FrameLayout(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val frostView = FrostView(context)
        var frost = false
            set(v) { field = v; frostView.visibility = if (v) View.VISIBLE else View.GONE }

        fun refreshFrost() { if (frost) frostView.invalidate() }

        init {
            setWillNotDraw(false)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, radiusPx) }
            }
            clipToOutline = true
            addView(frostView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            frostView.visibility = View.GONE
            if (Build.VERSION.SDK_INT >= 31) {
                val r = 20f * resources.displayMetrics.density
                frostView.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
            }
        }

        override fun onDraw(canvas: Canvas) {
            val a = fill
            val w = width.toFloat(); val h = height.toFloat()
            val rect = RectF(0f, 0f, w, h)
            if (on) {
                paint.style = Paint.Style.FILL
                paint.color = Themed.color(a.activeFill)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            } else if (a.fillAlpha > 0) {
                paint.style = Paint.Style.FILL
                paint.color = Themed.alpha(Themed.color(a.fill), a.fillAlpha)
                canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)
            }
            if (!on && a.stroke != null) {
                val sw = 1.5f * resources.displayMetrics.density
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = sw
                paint.color = Themed.color(a.stroke)
                val inset = sw / 2
                canvas.drawRoundRect(RectF(inset, inset, w - inset, h - inset), maxOf(0f, radiusPx - inset), maxOf(0f, radiusPx - inset), paint)
            }
        }

        override fun dispatchDraw(canvas: Canvas) {
            super.dispatchDraw(canvas)
            val px = dp(iconDp)
            val d = IconDrawable(icon, screen.iconStyle(), screen.iconStroke(), Themed.color(if (on) fill.activeIcon else fill.icon), px)
            val l = (width - px) / 2; val t = (height - px) / 2
            d.setBounds(l, t, l + px, t + px)
            d.draw(canvas)
        }
    }

    fun refreshFrost() { face.refreshFrost() }

    /**
     * Glass on API 31+: the backdrop behind the button, blurred (this view's
     * RenderEffect), with the glass's translucent fill over it. It is a child
     * of the face, so it covers the face's own fill, which is what shows
     * where there is no blur.
     */
    private inner class FrostView(context: Context) : View(context) {
        private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) {
            screen.paintBackdropInto(canvas, this)
            tint.color = Themed.alpha(Themed.color(fill.fill), fill.fillAlpha)
            canvas.drawRect(Rect(0, 0, width, height), tint)
        }
    }
}
