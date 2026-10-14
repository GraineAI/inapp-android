package ai.graine.inapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/*
 * The voice bar's pieces that are more than a TextView: the meter, the round
 * buttons, the brand's mark, a window's title bar, and the chat window. Each
 * draws exactly what [VoiceBar] or [WindowChrome] says; none decides anything
 * of its own.
 */

/**
 * FOUR BARS MOVING AT THE DESIGN'S PACE — the motion knob, visible, and the
 * Garage's `dg-eq`. Heights, widths and timing are [VoiceBar]'s; this view
 * only asks it where each bar is on every frame, and asks for the next frame
 * only while it is moving and on screen. Still at full height when the design
 * or the phone says no motion.
 */
internal class MeterView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private var legMs = VoiceBar.meterLegMs(0)
    private var moving = false
    private var startedAt = -1L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun configure(color: Int, motion: ThemeMotion) {
        paint.color = color
        legMs = VoiceBar.meterLegMs(motion.durationMs)
        val moves = VoiceBar.meterMoves(motion, Themed.animatorScale(context))
        if (moves != moving) { moving = moves; startedAt = -1 }
        invalidate()
    }

    private fun px(dp: Double) = (dp * resources.displayMetrics.density).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = VoiceBar.METER_BARS.size
        val w = px(n * VoiceBar.METER_BAR_WIDTH + (n - 1) * VoiceBar.METER_BAR_GAP)
        setMeasuredDimension(Math.ceil(w.toDouble()).toInt(), Math.ceil(px(VoiceBar.METER_HEIGHT).toDouble()).toInt())
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Shown again: it starts again, low and staggered, as a meter that has just appeared does.
        if (visibility == VISIBLE) { startedAt = -1; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (moving && startedAt < 0) startedAt = now
        val elapsed = (now - startedAt).toDouble()
        val bar = px(VoiceBar.METER_BAR_WIDTH)
        val step = px(VoiceBar.METER_BAR_WIDTH + VoiceBar.METER_BAR_GAP)
        for ((i, share) in VoiceBar.METER_BARS.withIndex()) {
            val scale = if (moving) VoiceBar.meterScale(i, elapsed, legMs) else 1.0
            val h = px(VoiceBar.METER_HEIGHT * share) * scale.toFloat()
            val left = i * step
            // Bottoms aligned: each bar grows up from the meter's floor.
            rect.set(left, height - h, left + bar, height.toFloat())
            val r = minOf(bar / 2, h / 2)
            canvas.drawRoundRect(rect, r, r, paint)
        }
        if (moving) postInvalidateOnAnimation()
    }
}

/** A 38 dp (or 40 dp) round button: a fill, one icon, a label for TalkBack, and 40% while it cannot act. */
internal class RoundButton(context: Context, sizeDp: Double = VoiceBar.BUTTON) : FrameLayout(context) {
    private val icon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val side = Ui.dp(context, sizeDp.toFloat())

    init {
        isClickable = true
        isFocusable = true
        val i = Ui.dp(context, VoiceBar.ICON.toFloat())
        addView(icon, LayoutParams(i, i, Gravity.CENTER))
    }

    private var drawnFill: Int? = null

    /** Redraws only what changed: every caption that streams in asks again. */
    fun draw(fill: Int, drawable: Drawable, label: String, enabled: Boolean = true) {
        if (fill != drawnFill) {
            drawnFill = fill
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        }
        if (icon.drawable !== drawable) icon.setImageDrawable(drawable)
        if (contentDescription != label) contentDescription = label
        isEnabled = enabled
        alpha = if (enabled) 1f else VoiceBar.DISABLED_ALPHA
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY))
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
}

/**
 * THE BRAND'S MARK IN A SHAPE: the design's launcher glyph in `onAccent`, else
 * the logo, else the heading's first letter — the avatar on the bar (a circle)
 * and the chat header's launcher mark (the launcher's shape, a pill drawn as a
 * circle).
 *
 * The logo is drawn WHOLE (contract/DESIGN.md "The brand's logo"): fitted,
 * proportions kept, centred in the largest square the shape holds
 * ([Logo.box]) — never cropped. It filled the avatar here, centre-cropped: a
 * wordmark lost both ends and a square logo its corners. Under it, its plate:
 * the accent, or white with a hairline in `border`. A glyph or a letter is
 * always on the accent.
 */
internal object Mark {
    /** `markShape` is the launcher shape the mark is drawn in — what [Logo.box] fits the logo to. */
    fun fill(
        box: FrameLayout, t: ThemeTokens, sizeDp: Double, glyphDp: Double, markShape: String, cornerDp: Double?,
        accent: Int, onAccent: Int, hostImage: String?, logoUrl: String?, logoPlate: LogoPlate, initial: String,
    ) {
        val ctx = box.context
        val side = Ui.dp(ctx, sizeDp.toFloat())
        val glyphPx = Ui.dp(ctx, glyphDp.toFloat())
        box.removeAllViews()
        // A host's own picture is its override and wins; then the design's order: glyph, logo, letter.
        val glyph = if (hostImage.isNullOrEmpty()) GlyphDrawable.of(t.icons.launcher, t.icons.stroke, onAccent, glyphPx) else null
        val showsLogo = hostImage.isNullOrEmpty() && glyph == null && !logoUrl.isNullOrEmpty()
        val plate = Logo.plate(if (showsLogo) logoPlate else LogoPlate.ACCENT, t.colors.accent, t.colors.border, t.shape.borderWidth)
        box.background = GradientDrawable().apply {
            if (cornerDp == null) shape = GradientDrawable.OVAL else cornerRadius = Ui.dp(ctx, cornerDp.toFloat()).toFloat()
            // The accent plate is the bar's accent, a host's override included; the white one is white.
            setColor(if (plate.outline == null) accent else Themed.color(plate.fill, android.graphics.Color.WHITE))
            plate.outline?.let { setStroke(maxOf(1, Themed.px(ctx, it.width)), Themed.color(it.color)) }
        }
        // Clipped to its shape: a host's picture takes the shape.
        box.clipToOutline = true
        box.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        if (!hostImage.isNullOrEmpty()) { box.addView(image(ctx, hostImage, crop = true), FrameLayout.LayoutParams(side, side)); return }
        if (glyph != null) {
            box.addView(ImageView(ctx).apply { setImageDrawable(glyph) }, FrameLayout.LayoutParams(glyphPx, glyphPx, Gravity.CENTER))
            return
        }
        if (showsLogo) {
            val logoPx = Ui.dp(ctx, Logo.box(markShape, sizeDp).toFloat())
            box.addView(image(ctx, logoUrl!!, crop = false), FrameLayout.LayoutParams(logoPx, logoPx, Gravity.CENTER))
            return
        }
        box.addView(TextView(ctx).apply {
            text = initial
            gravity = Gravity.CENTER
            setTextColor(onAccent)
            // The letter as the Garage draws it: 40% of the mark, bold, in the platform's own face.
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Math.round(sizeDp * 0.4).toFloat())
            includeFontPadding = false
            Ui.weight(this, VoiceBar.INITIAL_WEIGHT)
        }, FrameLayout.LayoutParams(side, side))
    }

    /**
     * The brand's logo is never cropped (FIT_CENTER). Only a host's own avatar
     * picture fills its circle — [GraineAgentBarView.avatarUrl] says it does.
     */
    private fun image(ctx: Context, url: String, crop: Boolean): ImageView = ImageView(ctx).apply {
        scaleType = if (crop) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ImageLoader.load(this, url)
    }
}

/**
 * A WINDOW'S TITLE BAR — contract/DESIGN.md → Window chrome, drawn exactly as
 * [WindowChrome.titleBar] says: across the top of the surface in
 * `surfaceRaised`, a bottom outline, the surface's own top corners; the three
 * traffic lights 12 in from the left; the title centred in the whole bar on
 * one line, in the platform's monospace. What sits at the bar's right end —
 * the chat window's collapse chevron — is [trailing].
 *
 * It sits inside the surface's outline. [frame] draws the surface and moves
 * the padding it would have had under the bar, so what the surface holds sits
 * exactly where it sits without chrome.
 */
internal class WindowTitleBar(context: Context) : FrameLayout(context) {
    private val lights = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // What makes it a window, not controls anyone can press.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val dot = View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val title = TextView(context).apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }
    private val titleRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(dot)
        addView(title, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    /** The surface's top corners inside its outline, px. */
    private var cornerPx = 0f

    init {
        addView(lights, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT or Gravity.CENTER_VERTICAL))
        addView(titleRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
        // Clipped to the surface's top corners, fill and outline and all, so the bar never shows past the surface's curve.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                // Taller than the bar by both corners: its top corners are the surface's exactly, however short the bar.
                outline.setRoundRect(0, 0, view.width, view.height + Math.ceil(2.0 * cornerPx).toInt(), cornerPx)
            }
        }
        clipToOutline = true
    }

    private fun px(dp: Double) = Themed.px(context, dp)

    /** What sits at the bar's right end, [WindowChrome.INSET] in: the chat window's collapse chevron. */
    fun trailing(v: View, sidePx: Int) {
        addView(v, LayoutParams(sidePx, sidePx, Gravity.RIGHT or Gravity.CENTER_VERTICAL).apply { rightMargin = px(WindowChrome.INSET) })
    }

    /** Draw the bar `spec` describes, its top corners `topCornerPx` (the surface's, inside its outline). Cheap to call again. */
    fun render(spec: WindowChrome.TitleBar, topCornerPx: Float) {
        // The fill, and the bottom outline under it: as the surface's own outline is drawn, borderWidth in border.
        val line = Themed.px(context, spec.outlineWidth)
        val fill = Themed.color(spec.fill)
        background = if (line <= 0) ColorDrawable(fill)
        else LayerDrawable(arrayOf(ColorDrawable(Themed.color(spec.outline)), ColorDrawable(fill))).apply { setLayerInset(1, 0, 0, 0, line) }
        // What the bar holds is centred between its top and its outline.
        setPadding(0, 0, 0, line)
        // Its height; a phone set to very large text grows it rather than cutting the title in half.
        minimumHeight = px(spec.height)

        // The traffic lights: `light` across, `lightGap` apart, `inset` in from the left.
        val side = px(spec.light)
        if (lights.childCount != spec.lights.size) {
            lights.removeAllViews()
            repeat(spec.lights.size) { lights.addView(View(context)) }
        }
        spec.lights.forEachIndexed { i, hex ->
            val light = lights.getChildAt(i)
            light.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Themed.color(hex)) }
            light.layoutParams = LinearLayout.LayoutParams(side, side)
        }
        Ui.gap(lights, px(spec.lightGap))
        lights.layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.LEFT or Gravity.CENTER_VERTICAL)
            .apply { leftMargin = px(spec.inset) }

        // The title, kept as far from the right end as the lights reach from the left: centred in the whole bar.
        val clear = px(spec.titleInset)
        titleRow.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL)
            .apply { leftMargin = clear; rightMargin = clear }
        val dotSide = px(WindowChrome.ONLINE_DOT)
        dot.layoutParams = LinearLayout.LayoutParams(dotSide, dotSide).apply { rightMargin = px(WindowChrome.ONLINE_GAP) }
        dot.visibility = if (spec.onlineDot != null) VISIBLE else GONE
        spec.onlineDot?.let { c -> dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Themed.color(c)) } }
        title.text = spec.title
        title.visibility = if (spec.title.isEmpty()) GONE else VISIBLE
        title.setTextColor(Themed.color(spec.titleColor))
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.titleSp.toFloat())
        // The platform's monospace, at its regular weight: not the design's family, and never its bold.
        title.typeface = if (spec.titleFamily == WindowChrome.MONOSPACE) Typeface.MONOSPACE else Typeface.create(spec.titleFamily, Typeface.NORMAL)
        title.paint.isFakeBoldText = false

        if (cornerPx != topCornerPx) { cornerPx = topCornerPx; invalidateOutline() }
    }

    /**
     * Frame `surface` as this window: its fill, outline, corners and depth as
     * [Themed.surface] draws them; this bar across its top, inside the
     * outline; and the padding the surface would have had (`left`, `top`,
     * `right`, `bottom`, px) moved onto `body` under the bar.
     */
    fun frame(
        surface: View, body: View, spec: WindowChrome.TitleBar, t: ThemeTokens, fill: Int, radiusPx: Float,
        linePx: Int, stroke: Int, shadow: ThemeShadow?, left: Int, top: Int, right: Int, bottom: Int,
    ) {
        Themed.surface(surface, t, fill, radiusPx, linePx, stroke, shadow, linePx, linePx, linePx, 0)
        render(spec, maxOf(0f, radiusPx - linePx))
        visibility = VISIBLE
        // Beside the bar and under it, what the surface holds starts where it would without one.
        body.setPadding(maxOf(0, left - linePx), top, maxOf(0, right - linePx), bottom)
    }
}
