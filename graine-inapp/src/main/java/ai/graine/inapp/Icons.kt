package ai.graine.inapp

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable

/*
 * THE SDK'S OWN ICONS, AND THE LAUNCHER GLYPH, DRAWN FROM PATH DATA.
 *
 * The bar used emoji — 🎙 📞 ✕ — which every phone draws in its own colours
 * and none draws in the design's icon style. These are the dashboard's own
 * drawings (contract/design.json → icons, theme.ts SDK_ICONS), drawn stroke
 * for stroke as the Garage draws them, in the design's style and stroke.
 *
 * Android can draw SVG path data, but not through public API: the platform's
 * PathParser is hidden, and AndroidX's is a dependency this SDK does not take.
 * So [SvgPath] reads path data itself — every command SVG has, arcs included —
 * into plain segments, pure and proven on the JVM, and [path] turns those into
 * an android.graphics.Path.
 */

/** One SDK icon: a closed `body` a fill may sit in, and `lines` that are only ever stroked. */
internal data class SdkIcon(val body: String?, val lines: String?)

internal object SdkIcons {
    const val MIC = "mic"
    const val MIC_OFF = "micOff"
    const val END = "end"
    const val SEND = "send"
    const val CHAT = "chat"
    const val CALL = "call"
    /** Points up as drawn; the chat header turns it 180°. */
    const val CHEVRON = "chevron"
    const val CLOSE = "close"

    /** design.json → icons, verbatim. */
    val ALL: Map<String, SdkIcon> = linkedMapOf(
        MIC to SdkIcon(
            body = "M12 2.5a3.5 3.5 0 0 0-3.5 3.5v6a3.5 3.5 0 0 0 7 0V6A3.5 3.5 0 0 0 12 2.5z",
            lines = "M18.5 11.5a6.5 6.5 0 0 1-13 0M12 18v3.5",
        ),
        MIC_OFF to SdkIcon(
            body = "M12 2.5a3.5 3.5 0 0 0-3.5 3.5v6a3.5 3.5 0 0 0 7 0V6A3.5 3.5 0 0 0 12 2.5z",
            lines = "M18.5 11.5a6.5 6.5 0 0 1-13 0M12 18v3.5M4 4l16 16",
        ),
        END to SdkIcon(
            body = "M4.2 14.6c4.6-4.1 11-4.1 15.6 0 .6.5.6 1.4.1 2l-1.3 1.4c-.4.5-1.1.6-1.7.3l-2.2-1.2c-.5-.3-.8-.8-.7-1.4l.2-1.3a11 11 0 0 0-4.4 0l.2 1.3c.1.6-.2 1.1-.7 1.4l-2.2 1.2c-.6.3-1.3.2-1.7-.3L4.1 16.6c-.5-.6-.5-1.5.1-2z",
            lines = null,
        ),
        SEND to SdkIcon(body = "M4 12 20 4l-4 16-4.5-6.5L4 12z", lines = "M11.5 13.5 20 4"),
        CHAT to SdkIcon(body = "M20 12a8 8 0 0 1-11.6 7.1L4 20l1-4.2A8 8 0 1 1 20 12z", lines = null),
        CALL to SdkIcon(
            body = null,
            lines = "M13.832 16.568a1 1 0 0 0 1.213 -0.303l0.355 -0.465A2 2 0 0 1 17 15h3a2 2 0 0 1 2 2v3a2 2 0 0 1 -2 2A18 18 0 0 1 2 4a2 2 0 0 1 2 -2h3a2 2 0 0 1 2 2v3a2 2 0 0 1 -0.8 1.6l-0.468 0.351a1 1 0 0 0 -0.292 1.233a14 14 0 0 0 6.392 6.384",
        ),
        CHEVRON to SdkIcon(body = null, lines = "M6 15l6-6 6 6"),
        CLOSE to SdkIcon(body = null, lines = "M6 6l12 12M18 6 6 18"),
    )

    /**
     * How one icon style draws: stroke caps and joins, whether the body is
     * filled and how strongly, and the body's stroke — contract/DESIGN.md → Icons.
     *   outline  stroked, round caps and joins
     *   sharp    stroked, square caps, mitred joins
     *   filled   body filled, stroked at 0.75 × the stroke (at least 1)
     *   duotone  body filled at 28%, then stroked
     * A style this build does not know is outline.
     */
    data class Treatment(val squareCaps: Boolean, val bodyFillAlpha: Double, val bodyStroke: Double, val lineStroke: Double)

    fun treatment(style: String?, stroke: Double): Treatment = when (style) {
        "sharp" -> Treatment(true, 0.0, stroke, stroke)
        "filled" -> Treatment(false, 1.0, maxOf(1.0, stroke * 0.75), stroke)
        "duotone" -> Treatment(false, 0.28, stroke, stroke)
        else -> Treatment(false, 0.0, stroke, stroke)
    }
}

/**
 * SVG PATH DATA → ABSOLUTE SEGMENTS: moves, lines, cubic and quadratic curves,
 * closes. Relative commands are resolved, implicit repeats followed, smooth
 * curves reflected, H and V made lines, and arcs turned into cubic Béziers
 * (SVG 1.1 implementation notes, F.6), so a renderer needs only the five
 * primitives every canvas has. Malformed data throws.
 */
internal object SvgPath {
    sealed class Seg {
        data class Move(val x: Double, val y: Double) : Seg()
        data class Line(val x: Double, val y: Double) : Seg()
        data class Cubic(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val x: Double, val y: Double) : Seg()
        data class Quad(val x1: Double, val y1: Double, val x: Double, val y: Double) : Seg()
        object Close : Seg() { override fun toString() = "Close" }
    }

    private class Reader(val s: String) {
        var i = 0
        fun skip() { while (i < s.length && (s[i].isWhitespace() || s[i] == ',')) i++ }
        fun done(): Boolean { skip(); return i >= s.length }
        fun peekCommand(): Char? { skip(); return s.getOrNull(i)?.takeIf { it.isLetter() && it != 'e' && it != 'E' } }

        fun number(): Double {
            skip()
            val start = i
            if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
            var digits = 0
            while (i < s.length && s[i].isDigit()) { i++; digits++ }
            if (i < s.length && s[i] == '.') { i++; while (i < s.length && s[i].isDigit()) { i++; digits++ } }
            require(digits > 0) { "a number was expected at $start in \"$s\"" }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                val mark = i
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (i < s.length && s[i].isDigit()) { while (i < s.length && s[i].isDigit()) i++ } else i = mark
            }
            return s.substring(start, i).toDouble()
        }

        /** An arc flag is one character, so "a1 1 0 01 2 2" reads as flags 0 and 1. */
        fun flag(): Boolean {
            skip()
            val c = s.getOrNull(i)
            require(c == '0' || c == '1') { "an arc flag was expected at $i in \"$s\"" }
            i++
            return c == '1'
        }
    }

    fun parse(d: String): List<Seg> {
        val out = ArrayList<Seg>()
        val r = Reader(d)
        var cmd: Char? = null
        var x = 0.0; var y = 0.0          // the current point
        var sx = 0.0; var sy = 0.0        // where the subpath began
        var cx2 = 0.0; var cy2 = 0.0      // the last cubic's second control, for S
        var qx = 0.0; var qy = 0.0        // the last quadratic's control, for T
        var last: Char = ' '              // the last command drawn, upper-cased

        while (!r.done()) {
            r.peekCommand()?.let { cmd = it; r.i++ }
            val c = cmd ?: throw IllegalArgumentException("path data must start with a command: \"$d\"")
            val rel = c.isLowerCase()
            val ox = if (rel) x else 0.0
            val oy = if (rel) y else 0.0
            when (c.uppercaseChar()) {
                'M' -> {
                    x = ox + r.number(); y = oy + r.number()
                    out.add(Seg.Move(x, y)); sx = x; sy = y
                    // Pairs after a move are lines, relative if the move was.
                    cmd = if (rel) 'l' else 'L'
                }
                'L' -> { x = ox + r.number(); y = oy + r.number(); out.add(Seg.Line(x, y)) }
                'H' -> { x = ox + r.number(); out.add(Seg.Line(x, y)) }
                'V' -> { y = oy + r.number(); out.add(Seg.Line(x, y)) }
                'C' -> {
                    val x1 = ox + r.number(); val y1 = oy + r.number()
                    cx2 = ox + r.number(); cy2 = oy + r.number()
                    x = ox + r.number(); y = oy + r.number()
                    out.add(Seg.Cubic(x1, y1, cx2, cy2, x, y))
                }
                'S' -> {
                    val x1 = if (last == 'C' || last == 'S') 2 * x - cx2 else x
                    val y1 = if (last == 'C' || last == 'S') 2 * y - cy2 else y
                    cx2 = ox + r.number(); cy2 = oy + r.number()
                    x = ox + r.number(); y = oy + r.number()
                    out.add(Seg.Cubic(x1, y1, cx2, cy2, x, y))
                }
                'Q' -> {
                    qx = ox + r.number(); qy = oy + r.number()
                    x = ox + r.number(); y = oy + r.number()
                    out.add(Seg.Quad(qx, qy, x, y))
                }
                'T' -> {
                    qx = if (last == 'Q' || last == 'T') 2 * x - qx else x
                    qy = if (last == 'Q' || last == 'T') 2 * y - qy else y
                    x = ox + r.number(); y = oy + r.number()
                    out.add(Seg.Quad(qx, qy, x, y))
                }
                'A' -> {
                    val rx = r.number(); val ry = r.number(); val rotation = r.number()
                    val large = r.flag(); val sweep = r.flag()
                    val ex = ox + r.number(); val ey = oy + r.number()
                    arc(out, x, y, rx, ry, rotation, large, sweep, ex, ey)
                    x = ex; y = ey
                }
                'Z' -> {
                    out.add(Seg.Close)
                    x = sx; y = sy
                    // Numbers straight after a close are malformed, not a repeat.
                    cmd = null
                }
                else -> throw IllegalArgumentException("unknown path command '$c' in \"$d\"")
            }
            last = c.uppercaseChar()
        }
        return out
    }

    /** One elliptical arc as cubic Béziers of at most 90° each, ending exactly on the end point. */
    private fun arc(
        out: MutableList<Seg>, x0: Double, y0: Double, rxIn: Double, ryIn: Double, rotationDeg: Double,
        large: Boolean, sweep: Boolean, x: Double, y: Double,
    ) {
        if (x0 == x && y0 == y) return
        var rx = Math.abs(rxIn); var ry = Math.abs(ryIn)
        if (rx == 0.0 || ry == 0.0) { out.add(Seg.Line(x, y)); return }
        val phi = Math.toRadians(rotationDeg % 360.0)
        val cos = Math.cos(phi); val sin = Math.sin(phi)
        val dx = (x0 - x) / 2; val dy = (y0 - y) / 2
        val x1p = cos * dx + sin * dy
        val y1p = -sin * dx + cos * dy
        // Radii too small to reach the end point grow until they just do.
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val k = Math.sqrt(lambda); rx *= k; ry *= k }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        val coef = (if (large == sweep) -1 else 1) * Math.sqrt(maxOf(0.0, num / den))
        val cxp = coef * rx * y1p / ry
        val cyp = -coef * ry * x1p / rx
        val cx = cos * cxp - sin * cyp + (x0 + x) / 2
        val cy = sin * cxp + cos * cyp + (y0 + y) / 2
        val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
        val theta1 = angle(1.0, 0.0, ux, uy)
        var delta = angle(ux, uy, vx, vy)
        if (!sweep && delta > 0) delta -= 2 * Math.PI
        if (sweep && delta < 0) delta += 2 * Math.PI
        val n = maxOf(1, Math.ceil(Math.abs(delta) / (Math.PI / 2) - 1e-9).toInt())
        val step = delta / n
        val t = 4.0 / 3.0 * Math.tan(step / 4)
        fun px(ex: Double, ey: Double) = cx + rx * ex * cos - ry * ey * sin
        fun py(ex: Double, ey: Double) = cy + rx * ex * sin + ry * ey * cos
        for (i in 0 until n) {
            val a1 = theta1 + i * step
            val a2 = a1 + step
            val c1 = Math.cos(a1); val s1 = Math.sin(a1)
            val c2 = Math.cos(a2); val s2 = Math.sin(a2)
            val lastOne = i == n - 1
            out.add(Seg.Cubic(
                px(c1 - t * s1, s1 + t * c1), py(c1 - t * s1, s1 + t * c1),
                px(c2 + t * s2, s2 - t * c2), py(c2 + t * s2, s2 - t * c2),
                if (lastOne) x else px(c2, s2), if (lastOne) y else py(c2, s2),
            ))
        }
    }

    /** The signed angle from u to v. */
    private fun angle(ux: Double, uy: Double, vx: Double, vy: Double): Double {
        val dot = ux * vx + uy * vy
        val len = Math.sqrt((ux * ux + uy * uy) * (vx * vx + vy * vy))
        val a = Math.acos((dot / len).coerceIn(-1.0, 1.0))
        return if (ux * vy - uy * vx < 0) -a else a
    }

    private val cache = HashMap<String, Path?>()

    /** Path data as an android.graphics.Path in its own 24 × 24 units; null when it does not parse. Cached per string. */
    fun path(d: String): Path? = synchronized(cache) {
        cache.getOrPut(d) {
            runCatching {
                Path().apply {
                    for (s in parse(d)) when (s) {
                        is Seg.Move -> moveTo(s.x.toFloat(), s.y.toFloat())
                        is Seg.Line -> lineTo(s.x.toFloat(), s.y.toFloat())
                        is Seg.Cubic -> cubicTo(s.x1.toFloat(), s.y1.toFloat(), s.x2.toFloat(), s.y2.toFloat(), s.x.toFloat(), s.y.toFloat())
                        is Seg.Quad -> quadTo(s.x1.toFloat(), s.y1.toFloat(), s.x.toFloat(), s.y.toFloat())
                        Seg.Close -> close()
                    }
                }
            }.getOrNull()
        }
    }
}

/**
 * One SDK icon in the design's icon style, at any size: the Garage's SdkIcon,
 * on a Canvas. The 24-unit drawing is scaled to the bounds, strokes with it,
 * as an SVG viewBox does.
 */
internal class IconDrawable(
    private val icon: SdkIcon?,
    style: String?,
    stroke: Double,
    private val color: Int,
    private val sizePx: Int,
    /** Turned 180° — the chat header's chevron, pointing down. */
    private val flipped: Boolean = false,
) : Drawable() {
    /** One of [SdkIcons.ALL], by name. */
    constructor(name: String, style: String?, stroke: Double, color: Int, sizePx: Int, flipped: Boolean = false) :
        this(SdkIcons.ALL[name], style, stroke, color, sizePx, flipped)

    private val body = icon?.body?.let { SvgPath.path(it) }
    private val lines = icon?.lines?.let { SvgPath.path(it) }
    private val treatment = SdkIcons.treatment(style, stroke)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeCap = if (treatment.squareCaps) Paint.Cap.SQUARE else Paint.Cap.ROUND
        strokeJoin = if (treatment.squareCaps) Paint.Join.MITER else Paint.Join.ROUND
        color = this@IconDrawable.color
    }
    private var alphaMul = 255

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        if (flipped) canvas.rotate(180f, 12f, 12f)
        line.alpha = scaled(color ushr 24, 1.0)
        body?.let { p ->
            if (treatment.bodyFillAlpha > 0) {
                fill.color = color
                fill.alpha = scaled(color ushr 24, treatment.bodyFillAlpha)
                canvas.drawPath(p, fill)
            }
            line.strokeWidth = treatment.bodyStroke.toFloat()
            canvas.drawPath(p, line)
        }
        lines?.let { p ->
            line.strokeWidth = treatment.lineStroke.toFloat()
            canvas.drawPath(p, line)
        }
        canvas.restore()
    }

    private fun scaled(base: Int, share: Double): Int = Math.round(base * share * alphaMul / 255.0).toInt().coerceIn(0, 255)

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { alphaMul = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { fill.colorFilter = colorFilter; line.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * The design's launcher glyph (`icons.launcher`), drawn from its path as the
 * Garage draws it: a stroke glyph stroked at `icons.stroke` with round caps and
 * joins, any other filled (nonzero, Path's own rule). [of] is null when the
 * path does not parse — the caller then shows the logo or the first letter.
 */
internal class GlyphDrawable private constructor(
    private val path: Path,
    stroked: Boolean,
    strokeWidth: Double,
    private val color: Int,
    private val sizePx: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = this@GlyphDrawable.color
        if (stroked) {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth.toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        } else {
            style = Paint.Style.FILL
        }
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) { paint.alpha = Math.round((color ushr 24) * alpha / 255f); invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        fun of(glyph: LauncherGlyph?, strokeWidth: Double, color: Int, sizePx: Int): GlyphDrawable? {
            val g = glyph ?: return null
            val p = g.path.takeIf { it.isNotBlank() }?.let { SvgPath.path(it) } ?: return null
            return GlyphDrawable(p, g.stroke, strokeWidth, color, sizePx)
        }
    }
}
