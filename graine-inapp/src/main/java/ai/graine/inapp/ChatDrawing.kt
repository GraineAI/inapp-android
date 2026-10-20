package ai.graine.inapp

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ClickableSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView

/*
 * THE CHAT WINDOW v2'S DRAWINGS — what [ChatWindowView] paints with, each
 * exactly as [ChatLook] and the design's tokens say and deciding nothing of
 * its own: the wallpaper (one colour, a gradient, or a pattern standing still
 * behind the thread), a bubble's shape with its tight corners and its tail,
 * the frost behind a glass piece, the typing dots, the agent's formatted
 * words, the send button and the header's layout. Plain platform drawing — no
 * AndroidX, no Markdown or image library.
 */

/**
 * THE WALLPAPER — contract/DESIGN.md "Chat window v2" → Wallpaper: one surface
 * behind the thread (and a plain header, and a floating composer): `solid`,
 * a `gradient` top → bottom of that surface, or a `pattern` over it in
 * `patternColor` — dots 2 across on an 18-point grid, the first 9 in from the
 * top-left; a grid of 1-wide lines at 9 + 18k; or diagonal 1-wide lines where
 * x + y is a multiple of 14. Patterns never mirror for right to left.
 */
internal class WallpaperDrawable(private val w: ChatStyleWallpaper, private val density: Float) : Drawable() {
    /** The surface the gradient spans and the pattern starts from, as px from the bounds' top; a negative bottom is the bounds'. */
    var surfaceTop = 0f
        set(v) { if (field != v) { field = v; invalidateSelf() } }
    var surfaceBottom = -1f
        set(v) { if (field != v) { field = v; invalidateSelf() } }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pattern = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Themed.color(w.patternColor) }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val top = b.top + surfaceTop
        val bottom = if (surfaceBottom < 0) b.bottom.toFloat() else b.top + surfaceBottom
        val to = w.colorTo
        paint.shader = if (w.style == "gradient" && to != null && bottom > top) {
            LinearGradient(0f, top, 0f, bottom, Themed.color(w.color), Themed.color(to), Shader.TileMode.CLAMP)
        } else null
        paint.color = Themed.color(w.color)
        canvas.drawRect(b, paint)
        if (w.style != "pattern" || w.pattern == "none") return
        canvas.save()
        canvas.clipRect(b.left.toFloat(), top, b.right.toFloat(), bottom)
        val left = b.left.toFloat()
        val step = 18f * density
        val first = 9f * density
        when (w.pattern) {
            "grid" -> {
                pattern.strokeWidth = density
                var x = left + first
                while (x < b.right) { canvas.drawLine(x, top, x, bottom, pattern); x += step }
                var y = top + first
                while (y < bottom) { canvas.drawLine(left, y, b.right.toFloat(), y, pattern); y += step }
            }
            "diagonal" -> {
                // Lines where x + y is a multiple of 14, rising left to right.
                pattern.strokeWidth = density
                val gap = 14f * density
                val span = (b.right - left) + (bottom - top)
                var k = gap
                while (k < span + gap) { canvas.drawLine(left + k, top, left, top + k, pattern); k += gap }
            }
            else -> {
                var y = top + first
                while (y < bottom) {
                    var x = left + first
                    while (x < b.right) { canvas.drawCircle(x, y, density, pattern); x += step }
                    y += step
                }
            }
        }
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; pattern.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; pattern.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/**
 * ONE BUBBLE'S SHAPE — its four corners ([ChatLook.corners]), its fill (one
 * colour or a gradient top → bottom), its outline, and the tail where the
 * design draws one ([ChatLook.tailPath]), filled and outlined with it. The
 * tail lies outside the bubble's box, so a tail-shaped side keeps [TAIL]
 * room on the speaker's side of the bounds ([reserveRight]); the box is the
 * rest.
 */
internal class BubbleDrawable(
    private val fill: Int?,
    private val fillTo: Int?,
    private val border: Int?,
    private val borderPx: Float,
    private val corners: ChatLook.Corners,
    /** Draw the tail. */
    private val tail: Boolean,
    /** Where the tail's room is kept: true right, false left, null none. */
    private val reserveRight: Boolean?,
    private val density: Float,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var path = Path()
    private var shaped = android.graphics.Rect()

    /** The box the words sit in, inside the bounds. */
    fun box(): RectF {
        val b = bounds
        val room = ChatLook.TAIL.toFloat() * density
        return RectF(
            b.left + (if (reserveRight == false) room else 0f), b.top.toFloat(),
            b.right - (if (reserveRight == true) room else 0f), b.bottom.toFloat(),
        )
    }

    /** The whole shape — the box with its corners, and the tail. */
    fun shape(): Path {
        if (shaped == bounds) return path
        shaped = android.graphics.Rect(bounds)
        val box = box()
        val d = density
        fun px(v: Double) = (v * d).toFloat()
        val radii = floatArrayOf(
            px(corners.topLeft), px(corners.topLeft), px(corners.topRight), px(corners.topRight),
            px(corners.bottomRight), px(corners.bottomRight), px(corners.bottomLeft), px(corners.bottomLeft),
        )
        val p = Path().apply { addRoundRect(box, radii, Path.Direction.CW) }
        if (tail && reserveRight != null) {
            val w = (box.width() / d).toDouble()
            val h = (box.height() / d).toDouble()
            val t = Path()
            for (s in ChatLook.tailPath(w, h, reserveRight)) {
                val v = s.values.map { (it * d).toFloat() }
                when (s.op) {
                    'M' -> t.moveTo(box.left + v[0], box.top + v[1])
                    'Q' -> t.quadTo(box.left + v[0], box.top + v[1], box.left + v[2], box.top + v[3])
                    'L' -> t.lineTo(box.left + v[0], box.top + v[1])
                    'Z' -> t.close()
                }
            }
            p.op(t, Path.Op.UNION)
        }
        path = p
        return p
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val p = shape()
        val box = box()
        if (fill != null) {
            fillPaint.shader = if (fillTo != null) LinearGradient(0f, box.top, 0f, box.bottom, fill, fillTo, Shader.TileMode.CLAMP) else null
            fillPaint.color = fill
            canvas.drawPath(p, fillPaint)
        }
        if (border != null && borderPx > 0) {
            strokePaint.color = border
            strokePaint.strokeWidth = borderPx
            canvas.save()
            canvas.clipPath(p)
            // Twice the width, clipped to the shape: the outline sits inside it, as a CSS border does.
            strokePaint.strokeWidth = borderPx * 2
            canvas.drawPath(p, strokePaint)
            canvas.restore()
        }
    }

    /** The outline a shadow is cast in: the whole shape where the platform can (Android 11+), else the box. */
    fun outline(outline: Outline) {
        val box = box()
        if (Build.VERSION.SDK_INT >= 30) { outline.setPath(shape()); return }
        val r = (maxOf(corners.topLeft, corners.topRight, corners.bottomRight, corners.bottomLeft) * density).toFloat()
        outline.setRoundRect(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt(), r)
    }

    override fun setAlpha(alpha: Int) { fillPaint.alpha = alpha; strokePaint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { fillPaint.colorFilter = colorFilter; strokePaint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** The outline provider for a view drawn with one: its shadow follows the bubble, tail and all. */
        val OUTLINE = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                (view.background as? BubbleDrawable)?.outline(outline) ?: outline.setEmpty()
                outline.alpha = if ((view.background as? BubbleDrawable)?.opaque == true) 1f else 0f
            }
        }
    }

    /** A translucent (glass) bubble casts no shadow, as the platform rules for any translucent fill. */
    val opaque: Boolean get() = fill != null && (fill ushr 24) == 0xFF && (fillTo == null || (fillTo ushr 24) == 0xFF)
}

/**
 * THE FROST BEHIND A GLASS PIECE — `chat.glass`: the wallpaper beneath it,
 * blurred by `glass.blur` on Android 12+ (RenderEffect), under the piece's own
 * colour at `glass.opacity`. The contract frosts over the wallpaper, so this
 * draws the window's wallpaper at its own place in the window; before Android
 * 12 it draws nothing and the piece is translucency alone.
 */
internal class FrostView(context: Context) : View(context) {
    var wallpaper: WallpaperDrawable? = null
        set(v) { field = v; invalidate() }
    /** Where this view sits in the wallpaper's window, px. */
    var dx = 0f
    var dy = 0f
    /** The window's size, px: the wallpaper's bounds. */
    var hostW = 0
    var hostH = 0

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Never sizes the piece it sits in: it takes the piece's size, given exactly, and nothing otherwise. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun exact(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(exact(widthMeasureSpec), exact(heightMeasureSpec))
    }

    fun blur(radiusDp: Double) {
        if (Build.VERSION.SDK_INT < 31) return
        val r = (radiusDp * resources.displayMetrics.density).toFloat()
        setRenderEffect(if (r > 0f) RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP) else null)
    }

    override fun onDraw(canvas: Canvas) {
        if (Build.VERSION.SDK_INT < 31) return
        val w = wallpaper ?: return
        if (hostW <= 0 || hostH <= 0) return
        canvas.save()
        canvas.translate(-dx, -dy)
        val old = android.graphics.Rect(w.bounds)
        w.setBounds(0, 0, hostW, hostH)
        w.draw(canvas)
        w.bounds = old
        canvas.restore()
    }
}

/**
 * THE TYPING DOTS — `dots`: three 7-point dots 5 apart, each fading 0.35 → 1
 * → 0.35 over 1200 ms, i × 160 ms late; `wave`: the same dots each rising 4
 * and back over 900 ms, i × 120 ms late. Still (0.6, or level) when the design
 * or the phone says no motion. Asks for frames only while moving on screen.
 */
internal class TypingDotsView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var wave = false
    private var moves = false
    private var startedAt = -1L

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun configure(color: Int, wave: Boolean, moves: Boolean) {
        paint.color = color
        this.wave = wave
        if (moves != this.moves) startedAt = -1
        this.moves = moves
        invalidate()
    }

    private fun px(dp: Double) = (dp * resources.displayMetrics.density).toFloat()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = px(3 * ChatLook.TYPING_DOT + 2 * ChatLook.TYPING_DOT_GAP)
        val h = px(ChatLook.TYPING_DOT + if (wave) 4.0 else 0.0)
        setMeasuredDimension(Math.ceil(w.toDouble()).toInt(), Math.ceil(h.toDouble()).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        if (moves && startedAt < 0) startedAt = now
        val elapsed = (now - startedAt).toDouble()
        val r = px(ChatLook.TYPING_DOT / 2)
        val step = px(ChatLook.TYPING_DOT + ChatLook.TYPING_DOT_GAP)
        val base = height - r
        val alpha = paint.color ushr 24
        for (i in 0..2) {
            val a = if (wave) 1f else ChatLook.dotAlpha(i, elapsed, moves)
            val rise = if (wave) px(ChatLook.waveRise(i, elapsed, moves).toDouble()) else 0f
            paint.alpha = Math.round(alpha * a)
            canvas.drawCircle(r + i * step, base - rise, r, paint)
        }
        paint.alpha = alpha
        if (moves && isShown) postInvalidateOnAnimation()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }
}

/**
 * THE AGENT'S WORDS, FORMATTED — [ChatRules.markdown]'s blocks as one styled
 * text: bold at the design's strong weight, the platform's italic, code in the
 * monospace on a quiet rounded patch, links in the side's link colour and
 * always underlined (a tap opens http(s) in the browser, mailto and tel with
 * the system), "•" and "1." with their wrapped lines hanging under the words,
 * paragraphs 6 apart. Never HTML.
 */
internal object ChatFormat {
    /** What the words are drawn with. Typefaces already at their weights; sizes px. */
    data class Look(
        val strong: Typeface,
        val italic: Typeface?,
        val strongItalic: Typeface?,
        val mono: Typeface,
        val codeSizePx: Float,
        val codeFill: Int,
        val codeCornerPx: Float,
        val link: Int,
        val paragraphGapPx: Int,
        val markerGapPx: Int,
    )

    fun format(blocks: List<ChatRules.Block>, look: Look): CharSequence {
        val sb = SpannableStringBuilder()
        blocks.forEachIndexed { index, block ->
            val start = sb.length
            for (s in block.spans) {
                val at = sb.length
                sb.append(s.text)
                val end = sb.length
                if (end == at) continue
                when {
                    s.bold && s.italic -> sb.setSpan(FaceSpan(look.strongItalic ?: look.strong, synthItalic = look.strongItalic == null), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.bold -> sb.setSpan(FaceSpan(look.strong), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    s.italic -> if (look.italic != null) sb.setSpan(FaceSpan(look.italic), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        else sb.setSpan(StyleSpan(Typeface.ITALIC), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (s.code) sb.setSpan(CodeSpan(look.mono, look.codeSizePx, look.codeFill, look.codeCornerPx), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                s.link?.let { sb.setSpan(LinkSpan(it, look.link), at, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
            }
            val marker = when (block.kind) {
                ChatRules.Block.BULLET -> "•"
                ChatRules.Block.NUMBER -> "${block.n}."
                else -> null
            }
            val last = index == blocks.size - 1
            if (!last) sb.append('\n')
            val end = sb.length
            if (marker != null) sb.setSpan(MarkerSpan(marker, look.markerGapPx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (!last) sb.setSpan(GapSpan(look.paragraphGapPx), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    fun hasLinks(text: CharSequence): Boolean = text is Spanned && text.getSpans(0, text.length, LinkSpan::class.java).isNotEmpty()

    /** A typeface over the run (bold at the strong weight, or a loaded italic). */
    class FaceSpan(private val face: Typeface, private val synthItalic: Boolean = false) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(tp: TextPaint) {
            tp.typeface = face
            tp.isFakeBoldText = false
            if (synthItalic) tp.textSkewX = -0.25f
        }
    }

    /** Code: the platform's monospace, one step smaller; its patch is drawn by [ChatTextView]. */
    class CodeSpan(private val face: Typeface, private val sizePx: Float, val fill: Int, val cornerPx: Float) : MetricAffectingSpan() {
        override fun updateDrawState(tp: TextPaint) = apply(tp)
        override fun updateMeasureState(tp: TextPaint) = apply(tp)
        private fun apply(tp: TextPaint) {
            tp.typeface = face
            tp.textSize = sizePx
        }
    }

    /** A link: the side's link colour, always underlined; http(s), mailto and tel only (the formatter allows nothing else). */
    class LinkSpan(val url: String, private val color: Int) : ClickableSpan() {
        override fun updateDrawState(ds: TextPaint) {
            ds.color = color
            ds.isUnderlineText = true
        }

        override fun onClick(widget: View) {
            // A long press that copied the words is not a tap on the link under the finger.
            if ((widget as? ChatTextView)?.longPressed == true) return
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { widget.context.startActivity(i) } catch (_: ActivityNotFoundException) { } catch (_: SecurityException) { }
        }
    }

    /** "•" or "1." in the margin, every line of the item hanging under its words. */
    class MarkerSpan(private val marker: String, private val gapPx: Int) : LeadingMarginSpan {
        private var width = 0

        override fun getLeadingMargin(first: Boolean): Int = width + gapPx

        override fun drawLeadingMargin(
            c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout?,
        ) {
            val spanned = text as? Spanned ?: return
            if (spanned.getSpanStart(this) != start) return
            val style = p.style
            p.style = Paint.Style.FILL
            val w = p.measureText(marker)
            c.drawText(marker, if (dir < 0) x - w else x.toFloat(), baseline.toFloat(), p)
            p.style = style
        }

        /** Measured once with the words' paint, so the margin fits the widest number. */
        fun measure(p: Paint) { width = Math.ceil(p.measureText(marker).toDouble()).toInt() }
    }

    /** The space after a paragraph: added under its last line. */
    class GapSpan(private val gapPx: Int) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val spanEnd = (text as? Spanned)?.getSpanEnd(this) ?: return
            if (end == spanEnd) {
                fm.descent += gapPx
                fm.bottom += gapPx
            }
        }
    }
}

/**
 * A bubble's words: the formatted text, the rounded patch under each piece of
 * code, and a note of a long press — so the link under the finger is not
 * opened by the press that copied the words.
 */
internal class ChatTextView(context: Context) : TextView(context) {
    var longPressed = false
        private set
    private val patch = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) longPressed = false
        return super.onTouchEvent(event)
    }

    override fun performLongClick(): Boolean {
        longPressed = true
        return super.performLongClick()
    }

    /** Measure the list markers with the words' own paint before the text is laid out. */
    fun setFormatted(text: CharSequence) {
        if (text is Spanned) text.getSpans(0, text.length, ChatFormat.MarkerSpan::class.java).forEach { it.measure(paint) }
        setText(text)
    }

    override fun onDraw(canvas: Canvas) {
        val t = text as? Spanned
        val l = layout
        if (t != null && l != null) {
            val spans = t.getSpans(0, t.length, ChatFormat.CodeSpan::class.java)
            if (spans.isNotEmpty()) {
                canvas.save()
                canvas.translate(totalPaddingLeft.toFloat(), totalPaddingTop.toFloat())
                val fm = paint.fontMetrics
                val pad = resources.displayMetrics.density * 2
                for (s in spans) {
                    patch.color = s.fill
                    val start = t.getSpanStart(s)
                    val end = t.getSpanEnd(s)
                    val first = l.getLineForOffset(start)
                    val last = l.getLineForOffset(end)
                    for (line in first..last) {
                        val a = if (line == first) l.getPrimaryHorizontal(start) else l.getLineLeft(line)
                        val b = if (line == last) l.getPrimaryHorizontal(end) else l.getLineRight(line)
                        val base = l.getLineBaseline(line).toFloat()
                        rect.set(minOf(a, b) - pad, base + fm.ascent, maxOf(a, b) + pad, base + fm.descent)
                        canvas.drawRoundRect(rect, s.cornerPx, s.cornerPx, patch)
                    }
                }
                canvas.restore()
            }
        }
        super.onDraw(canvas)
    }
}

/**
 * THE SEND BUTTON — `circle` (round, `sendFill`), `rounded` (corners
 * `min(radius.md, 12)`), or `icon` (no fill, the icon at 22 in a 44 target) —
 * and the microphone in its place while the field is empty, where the
 * composer calls. Dimmed to 40% while there is nothing to send.
 */
internal class SendButton(context: Context) : FrameLayout(context) {
    private val icon = ImageView(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private var sidePx = 0

    init {
        isClickable = true
        isFocusable = true
        addView(icon, LayoutParams(0, 0, Gravity.CENTER))
    }

    fun draw(sideDp: Double, fill: Int?, cornersDp: Double?, drawable: Drawable, iconDp: Double, label: String, enabled: Boolean) {
        val d = resources.displayMetrics.density
        sidePx = Math.round(sideDp * d).toInt()
        background = if (fill == null) null else GradientDrawable().apply {
            setColor(fill)
            if (cornersDp == null) shape = GradientDrawable.OVAL else cornerRadius = (cornersDp * d).toFloat()
        }
        val i = Math.round(iconDp * d).toInt()
        icon.layoutParams = LayoutParams(i, i, Gravity.CENTER)
        icon.setImageDrawable(drawable)
        contentDescription = label
        isEnabled = enabled
        alpha = if (enabled) 1f else VoiceBar.DISABLED_ALPHA
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(sidePx, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(sidePx, MeasureSpec.EXACTLY))
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name
}

/**
 * THE HEADER'S LAYOUT — the controls at their edges (a full screen's chevron
 * leading; the call button, compose and chevron trailing) and the avatar with
 * the title and status as one group: after the leading controls and taking
 * the rest (`start`), or centred on the header's whole width, kept
 * [ChatLook.CENTER_CLEAR] clear of the controls (`center`).
 */
internal class HeaderFrame(context: Context) : FrameLayout(context) {
    val leading = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val trailing = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    val group = android.widget.LinearLayout(context).apply { orientation = android.widget.LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    var centered = false
    /** Between the group and the controls, px. */
    var gapPx = 0

    init {
        addView(leading, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.START or Gravity.CENTER_VERTICAL))
        addView(trailing, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT, Gravity.END or Gravity.CENTER_VERTICAL))
        addView(group, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER_VERTICAL))
    }

    private fun shown(v: android.widget.LinearLayout) = (0 until v.childCount).any { v.getChildAt(it).visibility != GONE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        leading.measure(unspecified, unspecified)
        trailing.measure(unspecified, unspecified)
        val leadW = if (shown(leading)) leading.measuredWidth else 0
        val trailW = if (shown(trailing)) trailing.measuredWidth else 0
        val lp = group.layoutParams as LayoutParams
        if (centered) {
            // Centred on the whole width: the same room on both sides, the wider controls' and 8 more.
            val clear = Math.round(ChatLook.CENTER_CLEAR * resources.displayMetrics.density).toInt()
            val widest = maxOf(leadW, trailW)
            val side = if (widest > 0) widest + clear else 0
            lp.marginStart = side
            lp.marginEnd = side
            group.gravity = Gravity.CENTER
        } else {
            lp.marginStart = if (leadW > 0) leadW + gapPx else 0
            lp.marginEnd = if (trailW > 0) trailW + gapPx else 0
            group.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        }
        lp.width = LayoutParams.MATCH_PARENT
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
