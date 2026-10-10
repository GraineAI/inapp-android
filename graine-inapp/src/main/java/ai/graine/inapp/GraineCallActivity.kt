package ai.graine.inapp

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
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * THE SDK'S CALL SCREEN — over the lock screen, for the ring and the call.
 *
 * Ringing: the agent's name, Decline and Answer. In a call: who, how long,
 * what the agent is saying, Mute, Speaker and End. It only draws GraineCalls'
 * state and calls its methods; closing it never ends the call (the ongoing
 * notification brings it back). An app with its own call screen names it in
 * [GraineCallsConfig.callActivity] and draws the same state.
 */
class GraineCallActivity : Activity(), GraineCallListener {
    private var calls: GraineCalls? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var avatar: TextView
    private lateinit var nameView: TextView
    private lateinit var statusView: TextView
    private lateinit var captionView: TextView
    private lateinit var ringingRow: LinearLayout
    private lateinit var callRow: LinearLayout
    private lateinit var muteButton: CallButton
    private lateinit var speakerButton: CallButton
    private var finishing = false
    private val tick = object : Runnable {
        override fun run() { renderStatus(); handler.postDelayed(this, 1_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graine = GraineInApp.peek()
        if (graine == null) { finish(); return }
        calls = graine.calls
        showOverLockScreen()
        setContentView(build(accentOf(graine)))
        graine.calls.addListener(this)
        render()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render()
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
    override fun onAgentSpeaking(speaking: Boolean) = renderStatus()
    override fun onCallControls(muted: Boolean, speaker: Boolean) = renderControls()

    // ── drawing ────────────────────────────────────────────────────────────

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

    private fun accentOf(graine: GraineInApp): Int =
        runCatching { Color.parseColor(graine.themeTokens(this)?.colors?.accent ?: graine.session?.accent ?: "") }.getOrDefault(ACCENT)

    private fun dp(v: Float): Int = Math.round(v * resources.displayMetrics.density)

    private fun text(sizeSp: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private fun build(accent: Int): View {
        val root = FrameLayout(this).apply {
            setBackgroundColor(BACKGROUND)
            fitsSystemWindows = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24f), dp(72f), dp(24f), dp(24f))
        }
        avatar = text(36f, Color.WHITE, bold = true).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        column.addView(avatar, LinearLayout.LayoutParams(dp(104f), dp(104f)))
        nameView = text(26f, Color.WHITE, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        column.addView(nameView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20f) })
        statusView = text(16f, MUTED).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        column.addView(statusView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6f) })
        captionView = text(17f, CAPTION).apply { maxLines = 5; ellipsize = TextUtils.TruncateAt.START; setLineSpacing(0f, 1.15f) }
        column.addView(captionView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(36f) })
        root.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))

        val icon = dp(30f)
        ringingRow = row().apply {
            addView(CallButton(context, dp(72f), DANGER, IconDrawable(SdkIcons.END, "filled", 2.0, Color.WHITE, icon), "Decline") { calls?.decline() }, weight())
            addView(CallButton(context, dp(72f), ANSWER, IconDrawable(SdkIcons.CALL, "outline", 2.0, Color.WHITE, icon), "Answer") { calls?.answer() }, weight())
        }
        muteButton = CallButton(this, dp(64f), CONTROL, IconDrawable(SdkIcons.MIC, "outline", 2.0, Color.WHITE, icon), "Mute") { calls?.let { it.setMuted(!it.muted) } }
        speakerButton = CallButton(this, dp(64f), CONTROL, IconDrawable(SPEAKER, "outline", 2.0, Color.WHITE, icon), "Speaker") { calls?.let { it.setSpeaker(!it.speaker) } }
        callRow = row().apply {
            addView(muteButton, weight())
            addView(speakerButton, weight())
            addView(CallButton(context, dp(64f), DANGER, IconDrawable(SdkIcons.END, "filled", 2.0, Color.WHITE, icon), "End") { calls?.end() }, weight())
        }
        val bottom = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = dp(56f) }
        root.addView(ringingRow, bottom)
        root.addView(callRow, FrameLayout.LayoutParams(bottom))
        return root
    }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(24f), 0, dp(24f), 0)
    }

    private fun weight() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /** This screen is for the call in [getIntent]: a stale one (a ring from before) shows the end and closes. */
    private fun mine(c: GraineCall?): Boolean {
        val id = intent?.getStringExtra(GraineCalls.EXTRA_CALL_ID)
        return c != null && (id == null || id == c.callId)
    }

    private fun render() {
        val calls = calls ?: return
        val c = calls.call
        val live = mine(c) && calls.state != GraineCallState.IDLE && calls.state != GraineCallState.ENDED
        val name = c?.callerName ?: calls.config.displayName
        nameView.text = name
        avatar.text = name.trim().firstOrNull()?.uppercase() ?: ""
        ringingRow.visibility = if (live && calls.state == GraineCallState.RINGING) View.VISIBLE else View.GONE
        callRow.visibility = if (live && calls.state != GraineCallState.RINGING) View.VISIBLE else View.GONE
        renderStatus()
        renderCaption()
        renderControls()
        if (!live && !finishing) {
            finishing = true
            handler.postDelayed({ finishAndRemoveTask() }, 1_000)
        }
    }

    private fun renderStatus() {
        val calls = calls ?: return
        val c = calls.call
        statusView.text = when {
            !mine(c) || calls.state == GraineCallState.IDLE || calls.state == GraineCallState.ENDED -> "Call ended"
            calls.state == GraineCallState.RINGING -> "Incoming call"
            calls.state == GraineCallState.CONNECTING -> if (c?.direction == GraineCall.Direction.OUTGOING) "Calling…" else "Connecting…"
            else -> duration(calls.connectedAt) + if (calls.muted) " · Muted" else ""
        }
    }

    private fun duration(since: Long): String {
        if (since <= 0) return "00:00"
        val s = (SystemClock.elapsedRealtime() - since) / 1000
        return if (s >= 3600) String.format("%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60) else String.format("%02d:%02d", s / 60, s % 60)
    }

    private fun renderCaption() {
        val calls = calls ?: return
        val cap = calls.caption
        captionView.text = if (cap != null && mine(calls.call)) (if (cap.first == Role.CUSTOMER) "You: " else "") + cap.second else ""
    }

    private fun renderControls() {
        val calls = calls ?: return
        muteButton.set(if (calls.muted) Color.WHITE else CONTROL,
            IconDrawable(if (calls.muted) SdkIcons.MIC_OFF else SdkIcons.MIC, "outline", 2.0, if (calls.muted) BACKGROUND else Color.WHITE, dp(30f)),
            if (calls.muted) "Unmute" else "Mute", calls.muted)
        speakerButton.set(if (calls.speaker) Color.WHITE else CONTROL,
            IconDrawable(SPEAKER, "outline", 2.0, if (calls.speaker) BACKGROUND else Color.WHITE, dp(30f)),
            "Speaker", calls.speaker)
    }

    /** A round button with an icon, and its label under it. */
    private class CallButton(context: Context, size: Int, fill: Int, icon: Drawable, label: String, onTap: () -> Unit) : LinearLayout(context) {
        private val circle = FrameLayout(context)
        private val image = ImageView(context)
        private val caption = TextView(context)

        init {
            orientation = VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            circle.addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            circle.isClickable = true
            circle.isFocusable = true
            circle.setOnClickListener { onTap() }
            addView(circle, LayoutParams(size, size))
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            caption.setTextColor(MUTED)
            caption.gravity = Gravity.CENTER
            caption.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(caption, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Math.round(8 * resources.displayMetrics.density)
            })
            set(fill, icon, label, null)
        }

        fun set(fill: Int, icon: Drawable, label: String, checked: Boolean?) {
            circle.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
            image.setImageDrawable(icon)
            caption.text = label
            circle.contentDescription = if (checked == null) label else "$label, ${if (checked) "on" else "off"}"
        }
    }

    companion object {
        private val BACKGROUND = Color.parseColor("#0F1115")
        private val CONTROL = Color.parseColor("#2A2D35")
        private val DANGER = Color.parseColor("#E5484D")
        private val ANSWER = Color.parseColor("#1FAD5B")
        private val ACCENT = Color.parseColor("#4F46E5")
        private val MUTED = Color.parseColor("#A3A8B3")
        private val CAPTION = Color.parseColor("#E6E8EC")
        /** A loudspeaker, in the SDK icons' 24-unit drawing. */
        private val SPEAKER = SdkIcon(body = "M4 9.5h3.5L12 5.5v13l-4.5-4H4z", lines = "M15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11")
    }
}
