package ai.graine.inapp

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout

/**
 * HOW WAITING LOOKS, drawn from the dashboard's Appearance → loader.
 *
 * The same five kinds as the React Native SDK's GraineLoader — dots, pulse,
 * ring, bars, and the brand's own logo breathing ("mark", which is the pulse
 * when no logo is uploaded) — at the configured size and speed, in the accent
 * unless a colour was chosen. Drawn on a Canvas: no drawable resources, no
 * Lottie, nothing to add to a host's dependency tree.
 *
 * Use [configure] with a [LoaderLook] from [Loaders.resolve]; the bar and the
 * component renderer do exactly that.
 */
class GraineLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var kind = Loaders.DEFAULT_KIND
    private var color = Color.parseColor(Appearance.DEFAULT_ACCENT)
    private var track = 0x332C2E33
    private var sizeDp = 20
    private var periodMs = 1100L
    private var logo: Bitmap? = null
    private var t = 0.25f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /**
     * Draw this look. `color` is the resolved colour (the look's own, else the
     * accent); `track` is the ring's unlit part. `sizeDp` overrides the look's
     * size — the bar's status line wants a small one whatever the dashboard says.
     */
    fun configure(look: LoaderLook, color: Int, track: Int, sizeDp: Int? = null, logoUrl: String? = null) {
        this.kind = look.kind
        this.color = color
        this.track = track
        this.sizeDp = sizeDp ?: look.px
        this.periodMs = look.periodMs
        animator.duration = periodMs
        logo = null
        if (kind == "mark" && !logoUrl.isNullOrEmpty()) ImageLoader.fetch(logoUrl) { bmp -> logo = bmp; invalidate() }
        requestLayout()
        invalidate()
    }

    private fun px(v: Float) = v * resources.displayMetrics.density
    private fun rnd(v: Float) = Math.round(v).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = sizeDp.toFloat()
        val w = when (kind) {
            "dots" -> { val d = maxOf(4f, rnd(s * 0.32f)); d * 3 + rnd(d * 0.7f) * 2 }
            "bars" -> { val b = maxOf(3f, rnd(s * 0.18f)); b * 3 + rnd(b * 0.8f) * 2 }
            else -> s
        }
        setMeasuredDimension(resolveSize(Math.ceil(px(w).toDouble()).toInt(), widthMeasureSpec), resolveSize(Math.ceil(px(s).toDouble()).toInt(), heightMeasureSpec))
    }

    /** Linear interpolation between keyframes, as Animated.interpolate does it. */
    private fun at(input: FloatArray, output: FloatArray): Float {
        for (i in 1 until input.size) {
            if (t <= input[i]) {
                val f = (t - input[i - 1]) / (input[i] - input[i - 1])
                return output[i - 1] + (output[i] - output[i - 1]) * f
            }
        }
        return output.last()
    }

    override fun onDraw(canvas: Canvas) {
        val s = px(sizeDp.toFloat())
        val cy = height / 2f
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.alpha = 255
        when (kind) {
            "dots" -> {
                val d = px(maxOf(4f, rnd(sizeDp * 0.32f)))
                val gap = px(rnd(maxOf(4f, rnd(sizeDp * 0.32f)) * 0.7f))
                val lit = floatArrayOf(0f, 0.33f, 0.66f, 1f)
                val curves = arrayOf(floatArrayOf(1f, .3f, .3f, 1f), floatArrayOf(.3f, 1f, .3f, .3f), floatArrayOf(.3f, .3f, 1f, .3f))
                for (i in 0..2) {
                    paint.alpha = (at(lit, curves[i]) * 255).toInt()
                    canvas.drawCircle(i * (d + gap) + d / 2, cy, d / 2, paint)
                }
            }
            "ring" -> {
                val w = px(maxOf(2f, rnd(sizeDp * 0.14f)))
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = w
                rect.set(w / 2, cy - s / 2 + w / 2, s - w / 2, cy + s / 2 - w / 2)
                paint.color = track
                canvas.drawOval(rect, paint)
                paint.color = color
                // The top quarter of the border, turning — the RN ring is a border with a coloured top.
                canvas.drawArc(rect, -135f + 360f * t, 90f, false, paint)
            }
            "bars" -> {
                val bw = px(maxOf(3f, rnd(sizeDp * 0.18f)))
                val gap = px(rnd(maxOf(3f, rnd(sizeDp * 0.18f)) * 0.8f))
                val keys = floatArrayOf(0f, 0.5f, 1f)
                for (i in 0..2) {
                    val scale = at(keys, if (i == 1) floatArrayOf(.4f, 1f, .4f) else floatArrayOf(1f, .4f, 1f))
                    val h = s * scale
                    val x = i * (bw + gap)
                    rect.set(x, cy - h / 2, x + bw, cy + h / 2)
                    canvas.drawRoundRect(rect, bw / 2, bw / 2, paint)
                }
            }
            "mark" -> {
                val bmp = logo
                if (bmp == null) drawPulse(canvas, s, cy) else {
                    paint.alpha = (at(floatArrayOf(0f, 0.5f, 1f), floatArrayOf(1f, 0.45f, 1f)) * 255).toInt()
                    val scale = minOf(s / bmp.width, s / bmp.height)
                    val w = bmp.width * scale; val h = bmp.height * scale
                    rect.set((s - w) / 2, cy - h / 2, (s - w) / 2 + w, cy + h / 2)
                    canvas.drawBitmap(bmp, null, rect, paint)
                }
            }
            else -> drawPulse(canvas, s, cy)
        }
    }

    /**
     * A dot with a ring breathing out of it. The RN ring grows to 1.4× past
     * its box; a View cannot draw outside its bounds, so here it grows to the
     * box's edge.
     */
    private fun drawPulse(canvas: Canvas, s: Float, cy: Float) {
        val r = s / 2
        paint.alpha = (at(floatArrayOf(0f, 1f), floatArrayOf(0.35f, 0f)) * 255).toInt()
        canvas.drawCircle(s / 2, cy, r * at(floatArrayOf(0f, 1f), floatArrayOf(0.43f, 1f)), paint)
        paint.alpha = 255
        canvas.drawCircle(s / 2, cy, px(Math.round(sizeDp * 0.5f).toFloat()) / 2, paint)
    }

    private fun animate(on: Boolean) {
        val enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (on && enabled && isAttachedToWindow && isShown) { if (!animator.isStarted) animator.start() }
        else animator.cancel()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); animate(true) }
    override fun onDetachedFromWindow() { animate(false); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        animate(visibility == VISIBLE)
    }

    internal companion object {
        /**
         * The loader and its words, in a row (the bar's status line) or stacked
         * (a component on its way). `styleLabel` sets the words in a design's
         * type — the React Native loader's caption size, body face, strong weight.
         */
        fun withLabel(
            ctx: Context, look: LoaderLook, color: Int, track: Int, labelColor: Int,
            label: String?, sizeDp: Int? = null, stacked: Boolean = false, logoUrl: String? = null,
            styleLabel: ((android.widget.TextView) -> Unit)? = null,
        ): LinearLayout {
            val size = sizeDp ?: look.px
            val row = LinearLayout(ctx).apply {
                orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                Ui.gap(this, Ui.dp(ctx, if (stacked) 8 else 10))
                contentDescription = label?.takeIf { it.isNotEmpty() } ?: "Loading"
            }
            row.addView(GraineLoaderView(ctx).apply { configure(look, color, track, size, logoUrl) }, Ui.wrap())
            if (!label.isNullOrEmpty()) {
                row.addView(Ui.text(ctx, label, maxOf(12f, Math.round(size * 0.65f).toFloat()), labelColor, 600).apply {
                    importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                    styleLabel?.invoke(this)
                }, Ui.wrap())
            }
            return row
        }
    }
}
