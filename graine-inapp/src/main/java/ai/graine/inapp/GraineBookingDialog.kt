package ai.graine.inapp

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.content.res.Resources
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.TimePicker
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * THE BOOKING SHEET — "Call me at…" with no screen of the app's own
 * (BOOKING-SHEET.md §2–3). The user picks a day (chips, today to the
 * agent's furthest), a time (the platform's time picker, in spinner mode,
 * held to the times the agent's rules allow), and may say what it is about;
 * Book call books it with [GraineInApp.scheduleCall], and the sheet says when
 * the agent will ring. A refusal stays in the sheet as one line under the
 * button. Drawn from the design's tokens, as the chat window is.
 *
 *     GraineInApp.get().presentBooking(activity) { call, _ -> if (call != null) … }
 *     GraineBookingDialog(activity, GraineInApp.get("sales")) { call, _ -> … }.show()
 *
 * A plain platform [Dialog] — the SDK has no dependencies, so no Material:
 * a sheet up from the bottom on a phone, a centred panel on a tablet, the app
 * dimmed behind it; Back, a tap outside, or its close button closes it (not
 * while a booking is on its way). [done] hears the booked call once it
 * closes, or null when nothing was booked. Main thread.
 */
class GraineBookingDialog @JvmOverloads constructor(
    activity: Activity,
    private val graine: GraineInApp = GraineInApp.get(),
    reason: String? = null,
    private val done: GraineCallback<GraineScheduledCall?>? = null,
) : Dialog(activity) {
    private val zone: TimeZone = TimeZone.getDefault()
    private val locale: Locale = Locale.getDefault()
    /** The device's own clock (12 or 24 hours, as the user set it) and its words for a date. */
    private val clockFormat: java.text.DateFormat = android.text.format.DateFormat.getTimeFormat(activity)
        ?: java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT, locale)
    private val dateFormat = java.text.SimpleDateFormat(
        runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEdMMM") }.getOrNull() ?: "EEE d MMM", locale,
    )

    private fun clock(ms: Long): String = clockFormat.format(Date(ms))
    private fun date(ms: Long): String = dateFormat.format(Date(ms))

    private val flow = BookingFlow(graine.schedulingRules, System.currentTimeMillis(), reason, zone) { date(it) }

    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val title = TextView(context).apply { text = Booking.TITLE; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val subtitle = TextView(context).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val close = ImageView(context).apply {
        contentDescription = "Close"
        isClickable = true
        isFocusable = true
        scaleType = ImageView.ScaleType.CENTER
    }
    private val divider = View(context)
    private val scroll = CappedScrollView(context, Int.MAX_VALUE)
    private val form = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val dayLabel = TextView(context).apply { text = Booking.DAY_LABEL }
    private val chipsScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false }
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val timeLabel = TextView(context).apply { text = Booking.TIME_LABEL }
    private val pickerSlot = FrameLayout(context)
    private var picker: TimePicker? = null
    /**
     * The picker's minute wheel, in 5-minute steps (the spec's): the platform
     * picker has no interval, so its minute spinner shows 00, 05 … 55 and its
     * value is the step. Null where the platform's layout has no such spinner;
     * the picked minute is then rounded to 5 instead.
     */
    private var minuteSteps: NumberPicker? = null
    /** The scheme the picker was built in: a new one is built when it changes. */
    private var pickerDark: Boolean? = null
    private val zoneLine = TextView(context).apply { gravity = Gravity.CENTER_HORIZONTAL }
    private val reasonLabel = TextView(context).apply { text = Booking.REASON_LABEL }
    private val reasonField = EditText(context).apply {
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        filters = arrayOf(InputFilter.LengthFilter(Scheduling.REASON_MAX))
        contentDescription = Booking.REASON_LABEL
    }
    private val bookButton = FrameLayout(context).apply { isClickable = true; isFocusable = true; contentDescription = Booking.BOOK }
    private val bookText = TextView(context).apply { text = Booking.BOOK; gravity = Gravity.CENTER }
    private val spinner = ProgressBar(context, null, android.R.attr.progressBarStyleSmall).apply { isIndeterminate = true; visibility = View.GONE }
    private val errorView = TextView(context).apply { visibility = View.GONE }
    private val confirmation = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; visibility = View.GONE }
    private val disc = FrameLayout(context)
    private val discIcon = ImageView(context)
    private val bookedText = TextView(context).apply { gravity = Gravity.CENTER }
    private val doneButton = TextView(context).apply { text = Booking.DONE; gravity = Gravity.CENTER; isClickable = true; isFocusable = true }

    /** The time picker is being set by the sheet itself, not the user. */
    private var syncing = false
    private var answered = false
    /** Closed while a booking was on its way: [done] waits for its answer. */
    private var closedWhileBooking = false
    private var tokens: ThemeTokens? = null
    private var unwatch: List<() -> Unit> = emptyList()
    private val sessionListener = object : GraineListener {
        // The session arrived after the sheet opened: its rules are the ones to offer.
        override fun onSessionReady(session: SessionConfig) {
            flow.refresh(System.currentTimeMillis(), graine.schedulingRules)
            buildChips()
            syncPicker()
            paint()
        }
    }

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        header.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(subtitle)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(dp(44.0), dp(44.0)))
        chipsScroll.addView(chips)
        bookButton.addView(bookText, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bookButton.addView(spinner, FrameLayout.LayoutParams(dp(22.0), dp(22.0), Gravity.CENTER))
        val gap = dp(10.0)
        fun add(v: View, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, top: Int = gap) =
            form.addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply { topMargin = top })
        add(dayLabel, top = 0)
        add(chipsScroll)
        add(timeLabel, top = dp(18.0))
        add(pickerSlot)
        add(zoneLine)
        add(reasonLabel, top = dp(18.0))
        add(reasonField, dp(44.0))
        add(bookButton, dp(48.0), dp(18.0))
        add(errorView)
        disc.addView(discIcon, FrameLayout.LayoutParams(dp(24.0), dp(24.0), Gravity.CENTER))
        confirmation.addView(disc, LinearLayout.LayoutParams(dp(52.0), dp(52.0)))
        confirmation.addView(bookedText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18.0) })
        confirmation.addView(doneButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48.0)).apply { topMargin = dp(18.0) })
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(form, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(confirmation, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(body)

        reasonField.setText(flow.reason)
        reasonField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { flow.setReason(s?.toString().orEmpty()) }
        })
        close.setOnClickListener { if (!flow.isBooking) dismiss() }
        bookButton.setOnClickListener { book() }
        doneButton.setOnClickListener { dismiss() }
        setOnDismissListener { closed() }
        buildChips()
    }

    override fun onStart() {
        super.onStart()
        graine.addListener(sessionListener)
        // Opened before this launch's session (or after it failed to load): it is asked for, and its arrival redraws the sheet.
        graine.loadSession { }
        unwatch = listOf(
            graine.onDesignChange { paint() },
            // Who is signed in decides whether the sheet can book: its line and its button follow.
            graine.onIdentity { refreshState() },
        )
        paint()
        syncPicker()
        place()
    }

    override fun onStop() {
        graine.removeListener(sessionListener)
        unwatch.forEach { it() }
        unwatch = emptyList()
        super.onStop()
    }

    // ── what the user does ─────────────────────────────────────────────────

    private fun book() {
        val (atMs, why) = flow.begin() ?: return
        refreshState()
        graine.scheduleCall(Date(atMs), why) { call, error ->
            flow.finish(call, error, System.currentTimeMillis())
            // Too soon moved the offer on: the chips and the time follow it.
            if (error?.code == GraineErrorCode.TOO_SOON) { buildChips(); syncPicker() }
            if (closedWhileBooking) answer() else refreshState()
        }
    }

    private fun closed() {
        // A booking on its way is reported once its answer arrives.
        if (flow.isBooking) closedWhileBooking = true else answer()
    }

    private fun answer() {
        if (answered) return
        answered = true
        done?.onResult(flow.booked, null)
    }

    // ── where it sits ──────────────────────────────────────────────────────

    /** A tablet (the shorter side 600 dp or more) gets a centred panel; a phone a sheet up from the bottom. */
    private val panel: Boolean
        get() {
            val m = context.resources.displayMetrics
            return minOf(m.widthPixels, m.heightPixels) / m.density >= ChatWindowLayout.TABLET_MIN_SIDE
        }

    private fun place() {
        val w = window ?: return
        val m = context.resources.displayMetrics
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        @Suppress("DEPRECATION")
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(ChatWindowLayout.SCRIM_ALPHA)
        val moves = Themed.motionMs(context, tokens) > 0
        if (panel) {
            w.setLayout(dp(ChatWindowLayout.PANEL_WIDTH), ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.CENTER)
            w.setWindowAnimations(if (moves) android.R.style.Animation_Dialog else 0)
            scroll.maxHeightPx = (m.heightPixels * ChatWindowLayout.PANEL_HEIGHT).toInt() - dp(HEADER_ROOM)
        } else {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setWindowAnimations(if (moves) android.R.style.Animation_InputMethod else 0)
            scroll.maxHeightPx = (m.heightPixels * (1 - ChatWindowLayout.SHEET_TOP)).toInt() - dp(HEADER_ROOM)
        }
    }

    // ── drawing ────────────────────────────────────────────────────────────

    /** The design's tokens for now: the brand's, else Graine Classic around the dashboard's accent. */
    private fun currentTokens(): ThemeTokens = graine.themeTokens(context) ?: if (Ui.isDeviceDark(context)) Design.CLASSIC_DARK else Design.CLASSIC_LIGHT

    private fun callName(t: ThemeTokens): String {
        val heading = Appearance.resolve(graine.appearance, Appearance.DEFAULT_ACCENT, "Assistant", origin = graine.config.baseUrl).heading
        return Booking.callName(graine.schedulingRules.callName, Surfaces.chatOf(t).title, heading)
    }

    /** Everything in the design: colours, faces, corners — then the state. */
    private fun paint() {
        val t = currentTokens()
        tokens = t
        val c = t.colors
        val chat = Surfaces.chatOf(t)
        val bw = Themed.px(context, t.shape.borderWidth)
        val text = Themed.color(c.text)
        val muted = Themed.color(c.textMuted)
        val accent = Themed.color(c.accent)
        val onAccent = Themed.color(c.onAccent, Color.WHITE)
        val r = Themed.px(context, t.shape.radius.xl).toFloat()
        body.background = GradientDrawable().apply {
            setColor(Themed.color(c.surface))
            cornerRadii = if (panel) floatArrayOf(r, r, r, r, r, r, r, r) else floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            if (panel) setStroke(maxOf(1, bw), Themed.color(c.border))
        }
        body.outlineProvider = ViewOutlineProvider.BACKGROUND
        body.clipToOutline = true

        header.setPadding(dp(20.0), dp(12.0), dp(8.0), dp(12.0))
        val titleSp = minOf(t.font.scale.title, 20.0)
        title.setTextColor(text)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, titleSp.toFloat())
        Themed.face(title, t.font.heading, t.font.weights.title, graine)
        title.letterSpacing = t.font.letterSpacing.title.toFloat()
        if (Build.VERSION.SDK_INT >= 28) title.isAccessibilityHeading = true
        subtitle.text = Booking.subtitle(callName(t))
        caption(subtitle, t, muted, t.font.weights.body)
        close.setImageDrawable(IconDrawable(SdkIcons.CLOSE, t.icons.style, t.icons.stroke, muted, dp(20.0)))
        divider.setBackgroundColor(Themed.color(c.border))
        divider.layoutParams = divider.layoutParams.apply { height = maxOf(1, bw) }

        scroll.setPadding(dp(20.0), dp(16.0), dp(20.0), dp(20.0))
        scroll.clipToPadding = false
        for (label in listOf(dayLabel, timeLabel, reasonLabel)) caption(label, t, muted, t.font.weights.strong)
        caption(zoneLine, t, muted, t.font.weights.body)
        reasonField.setTextColor(Themed.color(chat.composerText))
        reasonField.setHintTextColor(Themed.color(chat.composerPlaceholder))
        reasonField.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.body.toFloat())
        Themed.face(reasonField, t.font.body, t.font.weights.body, graine)
        reasonField.background = Ui.rounded(Themed.color(chat.composerBackground), Themed.px(context, t.shape.radius.md).toFloat(), maxOf(1, bw), Themed.color(chat.composerBorder))
        reasonField.setPadding(dp(14.0), 0, dp(14.0), 0)
        bookButton.background = Ui.rounded(accent, Themed.px(context, t.shape.radius.md).toFloat())
        bookText.setTextColor(onAccent)
        bookText.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.body.toFloat())
        Themed.face(bookText, t.font.body, t.font.weights.strong, graine)
        spinner.indeterminateTintList = android.content.res.ColorStateList.valueOf(onAccent)
        caption(errorView, t, Themed.color(c.danger), t.font.weights.strong)

        disc.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
        discIcon.setImageDrawable(IconDrawable(SdkIcons.CALL, t.icons.style, t.icons.stroke, onAccent, dp(24.0)))
        bookedText.setTextColor(text)
        bookedText.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.body.toFloat())
        Themed.face(bookedText, t.font.body, t.font.weights.strong, graine)
        confirmation.setPadding(0, dp(12.0), 0, dp(8.0))
        doneButton.background = Ui.rounded(accent, Themed.px(context, t.shape.radius.md).toFloat())
        doneButton.setTextColor(onAccent)
        doneButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.body.toFloat())
        Themed.face(doneButton, t.font.body, t.font.weights.strong, graine)

        buildPicker(t)
        refreshState()
    }

    private fun caption(tv: TextView, t: ThemeTokens, color: Int, weight: Int) {
        tv.setTextColor(color)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, t.font.scale.caption.toFloat())
        Themed.face(tv, t.font.body, weight, graine)
        tv.letterSpacing = t.font.letterSpacing.body.toFloat()
    }

    /** The chips, one per day on offer; drawn again when the days change. */
    private fun buildChips() {
        chips.removeAllViews()
        val gap = dp(8.0)
        flow.days.forEachIndexed { i, day ->
            chips.addView(TextView(context).apply {
                text = day.label
                gravity = Gravity.CENTER
                maxLines = 1
                isClickable = true
                isFocusable = true
                setPadding(dp(14.0), 0, dp(14.0), 0)
                setOnClickListener {
                    flow.pickDay(i)
                    syncPicker()
                    refreshState()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34.0)).apply { if (i > 0) leftMargin = gap })
        }
    }

    /**
     * The platform's time picker in spinner mode, in the design's light or
     * dark: built without the theme's picker style, whose own default is the
     * spinner — and again only when the scheme changes. The wheels' numbers in
     * the design's text colour where the platform lets them be set (Android 10+).
     */
    private fun buildPicker(t: ThemeTokens) {
        val dark = t.scheme == "dark"
        if (picker != null && pickerDark == dark) { tintPicker(t); return }
        pickerDark = dark
        val themed = ContextThemeWrapper(context, if (dark) android.R.style.Theme_DeviceDefault_Dialog else android.R.style.Theme_DeviceDefault_Light_Dialog)
        val p = TimePicker(themed, null, 0, 0)
        p.setIs24HourView(android.text.format.DateFormat.is24HourFormat(context))
        p.contentDescription = Booking.TIME_LABEL
        minuteSteps = fiveMinuteWheel(p)
        p.setOnTimeChangedListener { _, hour, minute ->
            if (syncing) return@setOnTimeChangedListener
            flow.pickTime(hour, if (minuteSteps != null) minute * 5 else minOf(55, (minute + 2) / 5 * 5))
            // A time the day does not have goes back to the nearest it has.
            syncPicker()
            refreshState()
        }
        pickerSlot.removeAllViews()
        pickerSlot.addView(p, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        picker = p
        tintPicker(t)
        syncPicker()
    }

    /** The minute spinner as 00, 05 … 55, or null when the platform's picker has none to find. */
    private fun fiveMinuteWheel(p: TimePicker): NumberPicker? = try {
        val id = Resources.getSystem().getIdentifier("minute", "id", "android")
        (if (id != 0) p.findViewById<NumberPicker>(id) else null)?.apply {
            displayedValues = null      // before the range shrinks, or the old labels outnumber it
            minValue = 0
            maxValue = 11
            displayedValues = Array(12) { String.format(Locale.ROOT, "%02d", it * 5) }
            wrapSelectorWheel = true
        }
    } catch (_: RuntimeException) {
        null
    }

    private fun tintPicker(t: ThemeTokens) {
        val p = picker ?: return
        val text = Themed.color(t.colors.text)
        fun walk(v: View) {
            if (Build.VERSION.SDK_INT >= 29 && v is NumberPicker) v.textColor = text
            if (v is TextView) v.setTextColor(text)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(p)
        p.isEnabled = !flow.isBooking
    }

    /** The picker shows the flow's time. */
    private fun syncPicker() {
        val p = picker ?: return
        val (hour, minute) = Booking.clockOf(flow.atMs, zone)
        syncing = true
        try {
            if (p.hour != hour) p.hour = hour
            val shown = if (minuteSteps != null) minute / 5 else minute
            if (p.minute != shown) p.minute = shown
        } finally {
            syncing = false
        }
    }

    /** What changes as the user picks: the chips, the zone line, the button, the line under it, the confirmation. */
    private fun refreshState() {
        val t = tokens ?: return
        val c = t.colors
        val bw = Themed.px(context, t.shape.borderWidth)
        val chipCorner = Themed.px(context, minOf(t.shape.radius.xl, 17.0)).toFloat()
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as? TextView ?: continue
            val selected = i == flow.dayIndex
            chip.background = Ui.rounded(
                Themed.color(if (selected) c.accent else c.surfaceRaised), chipCorner, maxOf(1, bw), Themed.color(if (selected) c.accent else c.border),
            )
            chip.setTextColor(Themed.color(if (selected) c.onAccent else c.text, Color.WHITE))
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, (t.font.scale.body - 1).toFloat())
            Themed.face(chip, t.font.body, t.font.weights.strong, graine)
            chip.isSelected = selected
            chip.isEnabled = !flow.isBooking
        }
        val booked = flow.booked
        form.visibility = if (booked == null) View.VISIBLE else View.GONE
        confirmation.visibility = if (booked == null) View.GONE else View.VISIBLE
        if (booked != null) {
            bookedText.text = Booking.bookedLine(callName(t), booked.at?.time ?: flow.atMs, ::date, ::clock)
            return
        }
        zoneLine.text = Booking.zoneLine(flow.atMs, zone, ::date, ::clock)
        val blocked = graine.bookingBlocked
        val enabled = blocked == null && flow.day != null && !flow.isBooking
        bookButton.isEnabled = enabled
        bookButton.alpha = if (blocked == null && flow.day != null) 1f else VoiceBar.DISABLED_ALPHA
        bookText.visibility = if (flow.isBooking) View.INVISIBLE else View.VISIBLE
        spinner.visibility = if (flow.isBooking) View.VISIBLE else View.GONE
        bookButton.contentDescription = if (flow.isBooking) "Booking" else Booking.BOOK
        val line = flow.errorLine ?: blocked
        errorView.text = line
        errorView.visibility = if (line == null) View.GONE else View.VISIBLE
        reasonField.isEnabled = !flow.isBooking
        picker?.isEnabled = !flow.isBooking
        setCancelable(!flow.isBooking)
        setCanceledOnTouchOutside(!flow.isBooking)
    }

    private fun dp(v: Double): Int = Ui.dp(context, v.toFloat())

    private companion object {
        /** The header and the divider, kept out of the body's height cap (dp). */
        const val HEADER_ROOM = 72.0
    }
}
