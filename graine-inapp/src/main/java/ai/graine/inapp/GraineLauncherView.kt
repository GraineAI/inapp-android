package ai.graine.inapp

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.TextView

/**
 * THE LAUNCHER — the floating button, drawn the way the brand designed it.
 *
 * Everything about it comes from the session's design (contract/DESIGN.md →
 * Launcher): 56 dp, filled with the accent; the design's glyph in the colour
 * that reads on the accent at 46% of the height, or the brand's logo — whole,
 * fitted into the largest square the shape holds, on its plate (the accent,
 * or white with a hairline; contract/DESIGN.md "The brand's logo") — or the
 * first letter of the name; the corners of its shape — a circle, a squircle
 * (30% of the size), a rounded or plain square, or a pill as wide as its
 * label; and its depth — the floating shadow, or a hard offset in the text
 * colour with an outline to match. It appears over the design's duration,
 * and not at all when the design or the phone says no motion.
 *
 * A tap starts the call, and ends one that is live. setOnClickListener
 * replaces that with whatever the app wants the button to open — the bar,
 * say. Place it yourself, as any view (bottom-end over the content is the
 * usual spot); it measures its own size, shadow room included.
 *
 * Without a design (a server that predates designs) it is a circle in the
 * dashboard's accent with its logo, on its plate — no glyph, since there is no
 * design to choose one. Main thread only.
 */
class GraineLauncherView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs), GraineListener {

    /** The pill's words, and what TalkBack reads. Default: the dashboard's launcher label, else "Talk to us". */
    var label: String? = null
        set(v) { field = v; applyLook() }
    /** #RRGGBB fill. Default: the design's accent, else the dashboard's. */
    var accent: String? = null
        set(v) { field = v; applyLook() }
    /** "light", "dark" or "auto" (the device). Default: the design's scheme. */
    var scheme: String? = null
        set(v) { field = v; applyLook() }

    /** The agent this launcher calls, and whose design it wears — the default one unless set (`GraineInApp.get("sales")`). */
    var graine: GraineInApp? = if (isInEditMode) null else GraineInApp.primary()
        set(v) {
            if (field === v) return
            val attached = isAttachedToWindow
            if (attached) { field?.removeListener(this); unwatch?.invoke(); unwatch = null }
            field = v
            if (attached && v != null) { v.addListener(this); unwatch = v.onDesignChange { applyLook() } }
            applyLook()
        }
    private var unwatch: (() -> Unit)? = null
    private var ownClick = true
    private var entered = false

    private var tokens: ThemeTokens? = null
    private var fill = Color.parseColor(Appearance.DEFAULT_ACCENT)
    private var ink = Color.WHITE
    private var outlineInk = 0
    private var shape = "circle"
    private var cornerDp = 28.0
    private var borderPx = 0f
    private var offsetX = 0
    private var offsetY = 0
    private var words = ""
    private var letter = "✦"
    /** The brand's logo and what it sits on; the hairline a white plate draws (#RRGGBB, px). */
    private var logoUrl: String? = null
    private var logoPlate = LogoPlate.ACCENT
    private var hairline = Color.TRANSPARENT
    private var hairlinePx = 0f

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val face = RectF()
    private val glyphBox = RectF()
    /** Measures and draws the pill's words and the fallback letter, in the design's type. */
    private val type = TextView(context)
    private val mark = MarkLoader { invalidate(); requestLayout() }

    init {
        isClickable = true
        isFocusable = true
        super.setOnClickListener { toggleCall() }
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(face.left.toInt(), face.top.toInt(), face.right.toInt(), face.bottom.toInt(), dp(cornerDp))
            }
        }
        applyLook()
    }

    /** Replaces the default tap (start or end the call) with the app's own. */
    override fun setOnClickListener(l: OnClickListener?) {
        ownClick = l == null
        super.setOnClickListener(l ?: OnClickListener { toggleCall() })
        updateDescription()
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    private fun toggleCall() {
        val v = graine?.voice ?: return
        if (v.connected || v.connecting) v.end() else v.start()
    }

    // ── the design is the source ───────────────────────────────────────────

    private fun applyLook() {
        val g = graine ?: return
        val appearance = g.session?.appearance
        val dash = Appearance.resolve(appearance, Appearance.DEFAULT_ACCENT, "Assistant", origin = g.config.baseUrl)
        val t = g.themeTokens(context, scheme)
        tokens = t
        val accentHex = accent?.takeIf { Appearance.argb(it) != null } ?: t?.colors?.accent ?: dash.accent
        fill = Appearance.argb(accentHex) ?: Color.parseColor(Appearance.DEFAULT_ACCENT)
        val inkHex = if (t != null && accent == null) t.colors.onAccent else Themed.readableOnHex(accentHex)
        ink = Themed.color(inkHex, Color.WHITE)
        shape = t?.shape?.launcher ?: "circle"
        cornerDp = if (t != null) Design.launcherCorner(shape, Design.LAUNCHER_SIZE, t.shape.radius) else Design.LAUNCHER_SIZE / 2
        // The hard offset: a copy in the text colour, and an outline to match.
        val hard = t?.elevation?.hardOffset
        offsetX = if (hard != null) dp(hard.x).toInt() else 0
        offsetY = if (hard != null) dp(hard.y).toInt() else 0
        outlineInk = if (hard != null) Themed.color(t.colors.text) else 0
        borderPx = if (hard != null) dp(t.shape.borderWidth) else 0f
        // A white plate's hairline: the design's border at max(1, borderWidth) — the classic look's
        // light grey at 1 without a design, as the React Native classic launcher draws it.
        logoUrl = dash.logoUrl
        logoPlate = dash.logoPlate
        val white = Logo.plate(LogoPlate.WHITE, accentHex, t?.colors?.border ?: Logo.CLASSIC_HAIRLINE, t?.shape?.borderWidth ?: 1.0)
        hairline = Themed.color(white.outline?.color, Color.TRANSPARENT)
        hairlinePx = dp(white.outline?.width ?: 1.0)
        words = label?.trim()?.takeIf { it.isNotEmpty() }
            ?: Js.strOrEmpty(appearance?.opt("launcherLabel")).trim().ifEmpty { "Talk to us" }
        letter = dash.heading.trim().firstOrNull()?.uppercase() ?: "✦"

        type.setTextColor(ink)
        type.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        Ui.weight(type, 600)
        if (t != null) Themed.type(type, t, Themed.Role.STRONG, g)

        // The glyph, else the logo, else the letter.
        val glyph = t?.icons?.launcher?.let { gl ->
            Design.glyphUrl(g.config.baseUrl, gl.id, inkHex, dp(Design.LAUNCHER_SIZE * Design.LAUNCHER_GLYPH).toDouble(), t.icons.stroke)
        }
        mark.load(listOfNotNull(glyph, dash.logoUrl))

        if (t != null) Themed.shadow(this, if (hard != null) null else t.elevation.floating)
        else elevation = dp(6.0)
        updateDescription()
        requestLayout()
        invalidate()
    }

    private fun updateDescription() {
        val v = graine?.voice
        val live = v != null && (v.connected || v.connecting)
        contentDescription = if (ownClick && live) "End call" else words
    }

    private fun dp(v: Double): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics)

    // ── size and drawing ───────────────────────────────────────────────────

    private val isPill get() = shape == "pill"

    private fun glyphPx() = dp(Design.LAUNCHER_SIZE * Design.LAUNCHER_GLYPH)

    /** The mark on the button is the brand's logo (not the glyph, not the letter): it sits on its plate. */
    private fun showsLogo() = mark.bitmap != null && logoUrl != null && mark.source == logoUrl

    /** A white plate under the logo, rather than the accent. */
    private fun onWhite() = showsLogo() && logoPlate == LogoPlate.WHITE

    /**
     * The side of the square the mark is drawn in: a glyph at 46% of the
     * height; the logo in the largest square the shape holds (a pill's as a
     * circle's) — whole, never cropped.
     */
    private fun markPx() =
        if (showsLogo()) dp(Logo.box(if (isPill) "circle" else shape, Design.LAUNCHER_SIZE).toDouble()) else glyphPx()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = dp(Design.LAUNCHER_SIZE)
        val w = if (!isPill) h else {
            val icon = if (mark.bitmap != null) markPx() + dp(10.0) else 0f
            dp(20.0) + icon + type.paint.measureText(words) + dp(22.0)
        }
        // Room for the hard offset, so the copy is drawn inside the view rather than clipped.
        setMeasuredDimension(
            resolveSize(Math.ceil((w + Math.abs(offsetX)).toDouble()).toInt(), widthMeasureSpec),
            resolveSize(Math.ceil((h + Math.abs(offsetY)).toDouble()).toInt(), heightMeasureSpec),
        )
    }

    /** The button itself, inside the view: everything but the room kept for the hard offset. */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        face.set(
            if (offsetX < 0) -offsetX.toFloat() else 0f,
            if (offsetY < 0) -offsetY.toFloat() else 0f,
            width - (if (offsetX > 0) offsetX.toFloat() else 0f),
            height - (if (offsetY > 0) offsetY.toFloat() else 0f),
        )
        invalidateOutline()
    }

    override fun onDraw(canvas: Canvas) {
        val r = minOf(dp(cornerDp), face.height() / 2)
        if (offsetX != 0 || offsetY != 0) {
            fillPaint.color = outlineInk
            canvas.save()
            canvas.translate(offsetX.toFloat(), offsetY.toFloat())
            canvas.drawRoundRect(face, r, r, fillPaint)
            canvas.restore()
        }
        // The logo's plate: white when the brand's logo asks for it; the accent for everything else.
        val onWhite = onWhite()
        fillPaint.color = if (onWhite) Color.WHITE else fill
        canvas.drawRoundRect(face, r, r, fillPaint)
        // A hard offset keeps its text-coloured outline; otherwise a white plate draws its hairline.
        val line = if (borderPx > 0) borderPx else if (onWhite) hairlinePx else 0f
        if (line > 0) {
            strokePaint.color = if (borderPx > 0) outlineInk else hairline
            strokePaint.strokeWidth = line
            val half = line / 2
            canvas.drawRoundRect(face.left + half, face.top + half, face.right - half, face.bottom - half, r - half, r - half, strokePaint)
        }

        val g = markPx()
        val cy = face.centerY()
        val bmp = mark.bitmap
        if (isPill) {
            var x = face.left + dp(20.0)
            if (bmp != null) {
                drawFitted(canvas, bmp, x, cy - g / 2, g)
                x += g + dp(10.0)
            }
            val p = type.paint
            // On the white plate the label is dark, whatever reads on the accent.
            p.color = if (onWhite) Color.parseColor(Logo.ON_WHITE) else ink
            canvas.drawText(words, x, cy - (p.descent() + p.ascent()) / 2, p)
        } else if (bmp != null) {
            drawFitted(canvas, bmp, face.centerX() - g / 2, cy - g / 2, g)
        } else {
            val p = type.paint
            p.color = ink
            val size = p.textSize
            p.textSize = glyphPx()
            canvas.drawText(letter, face.centerX() - p.measureText(letter) / 2, cy - (p.descent() + p.ascent()) / 2, p)
            p.textSize = size
        }
    }

    /** A glyph or a logo, fitted into a square, its proportions kept: whole, never cropped. */
    private fun drawFitted(canvas: Canvas, bmp: Bitmap, x: Float, y: Float, side: Float) {
        val scale = minOf(side / bmp.width, side / bmp.height)
        val w = bmp.width * scale; val h = bmp.height * scale
        glyphBox.set(x + (side - w) / 2, y + (side - h) / 2, x + (side - w) / 2 + w, y + (side - h) / 2 + h)
        canvas.drawBitmap(bmp, null, glyphBox, bitmapPaint)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        alpha = if (isPressed) 0.85f else 1f
    }

    // ── following the session ──────────────────────────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val g = graine ?: return
        g.addListener(this)
        unwatch = g.onDesignChange { applyLook() }
        applyLook()
        if (!entered) {
            entered = true
            Themed.enter(this, tokens, dp(8.0))
        }
    }

    override fun onDetachedFromWindow() {
        graine?.removeListener(this)
        unwatch?.invoke()
        unwatch = null
        super.onDetachedFromWindow()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        applyLook()
    }

    override fun onSessionReady(session: SessionConfig) = applyLook()
    override fun onCallState(connected: Boolean, connecting: Boolean) = updateDescription()
    override fun onEnded() = updateDescription()
}

/**
 * THE BRAND'S MARK, IN ORDER: the design's glyph as an image, else the
 * brand's logo, else nothing — and the caller draws the first letter. Each
 * step is tried only when the one before is absent or fails to load, the
 * order contract/DESIGN.md gives. Main thread.
 */
internal class MarkLoader(private val onChange: () -> Unit) {
    var bitmap: Bitmap? = null
        private set
    /** The address [bitmap] came from — which of the marks it is. */
    var source: String? = null
        private set
    private var want: List<String> = emptyList()

    fun load(urls: List<String>) {
        if (urls == want) return
        want = urls
        bitmap = null
        source = null
        next(urls, 0)
    }

    private fun next(urls: List<String>, i: Int) {
        if (i >= urls.size) { onChange(); return }
        ImageLoader.fetch(urls[i]) { bmp ->
            if (want !== urls) return@fetch
            if (bmp != null) { bitmap = bmp; source = urls[i]; onChange() } else next(urls, i + 1)
        }
    }
}
