package ai.graine.inapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.LruCache
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.WeakHashMap
import java.util.concurrent.Executors

/*
 * The drawing kit the SDK's views share. Plain platform widgets only — no
 * AndroidX, no Material, no image library — so nothing here can conflict with
 * whatever the host app already ships.
 */

internal object Ui {
    fun dp(ctx: Context, v: Float): Int = Math.round(v * ctx.resources.displayMetrics.density)
    fun dp(ctx: Context, v: Int): Int = dp(ctx, v.toFloat())

    fun isDeviceDark(ctx: Context): Boolean =
        (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** A CSS weight (400–800) on a TextView: exact from Android 9, bold-or-not before. */
    fun weight(tv: TextView, w: Int) {
        tv.typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, w.coerceIn(100, 1000), false)
        else if (w >= 600) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    fun text(ctx: Context, s: CharSequence, sizeSp: Float, color: Int, weight: Int = 400, lines: Int = 0): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            weight(this, weight)
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
        }

    /** Line height in sp, where the platform can set one (Android 9+). */
    fun lineHeight(tv: TextView, sp: Float) {
        if (Build.VERSION.SDK_INT >= 28) tv.lineHeight = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, tv.resources.displayMetrics))
    }

    fun rounded(fill: Int, radiusPx: Float, strokePx: Int = 0, strokeColor: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    /** Space between a LinearLayout's children, the way a CSS `gap` does it. */
    fun gap(layout: LinearLayout, px: Int) {
        layout.dividerDrawable = GradientDrawable().apply {
            if (layout.orientation == LinearLayout.VERTICAL) setSize(0, px) else setSize(px, 0)
        }
        layout.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    }

    fun vertical(ctx: Context, gapPx: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (gapPx > 0) gap(this, gapPx)
    }

    fun horizontal(ctx: Context, gapPx: Int = 0): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        if (gapPx > 0) gap(this, gapPx)
    }

    fun matchWidth() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    fun fill() = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)

    /**
     * Take a gesture on DOWN without becoming "clickable" to TalkBack: a drag
     * that starts on a surface (the bar, a card on it) can then move the bar,
     * and a tap on it does not fall through to whatever is underneath.
     */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun holdsTouches(v: View) {
        v.setOnTouchListener { _, e -> e.actionMasked == android.view.MotionEvent.ACTION_DOWN }
    }

    fun activity(ctx: Context?): Activity? {
        var c = ctx
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}

/** Children laid out in rows that wrap — facts, chips, time slots. */
internal class FlowRow(context: Context, private val gapH: Int, private val gapV: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val mode = MeasureSpec.getMode(widthMeasureSpec)
        val maxW = if (mode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0; var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(maxW.coerceAtLeast(0), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > maxW) { y += rowH + gapV; x = 0; rowH = 0 }
            x += c.measuredWidth + gapH
            rowH = maxOf(rowH, c.measuredHeight)
            widest = maxOf(widest, x - gapH)
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(y + rowH + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { y += rowH + gapV; x = 0; rowH = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gapH
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}

/** A ScrollView that stops growing at a height — a sheet's body. */
internal class CappedScrollView(context: Context, private val maxHeightPx: Int) : ScrollView(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val cap = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) maxHeightPx else minOf(size, maxHeightPx)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}

/**
 * PICTURES, WITHOUT AN IMAGE LIBRARY.
 *
 * A component's pictures are a handful of product photos; a dependency on
 * Glide for that is a dependency a bank's security review has to clear. So:
 * HttpURLConnection on a small background pool, a decode that never makes a
 * bitmap larger than the screen needs, an in-memory LRU bounded in bytes, and
 * one request per URL however many views ask for it. Main-thread API only.
 */
internal object ImageLoader {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_SIDE = 1280

    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "graine-img").apply { isDaemon = true } }
    private val cache = object : LruCache<String, Bitmap>(
        minOf(Runtime.getRuntime().maxMemory() / 16, 24L * 1024 * 1024).toInt().coerceAtLeast(4 * 1024 * 1024),
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val waiting = HashMap<String, MutableList<(Bitmap?) -> Unit>>()
    private val bound = WeakHashMap<ImageView, String>()

    /** Show `url` in `view`. A view reused for another picture never shows the old one late. */
    fun load(view: ImageView, url: String?) {
        if (url.isNullOrEmpty()) { bound.remove(view); view.setImageDrawable(null); return }
        bound[view] = url
        cache.get(url)?.let { view.setImageBitmap(it); return }
        view.setImageDrawable(null)
        fetch(url) { bmp -> if (bmp != null && bound[view] == url) view.setImageBitmap(bmp) }
    }

    fun fetch(url: String, done: (Bitmap?) -> Unit) {
        cache.get(url)?.let { done(it); return }
        waiting[url]?.let { it.add(done); return }
        waiting[url] = mutableListOf(done)
        pool.execute {
            val bmp = try { download(url) } catch (_: Throwable) { null }
            main.post {
                if (bmp != null) cache.put(url, bmp)
                waiting.remove(url)?.forEach { runCatching { it(bmp) } }
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val bytes = if (url.startsWith("data:")) dataUri(url) else httpGet(url)
        return bytes?.let { decode(it) }
    }

    private fun dataUri(url: String): ByteArray? {
        val comma = url.indexOf(',')
        if (comma < 0) return null
        val meta = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        return if (meta.endsWith(";base64")) android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        else java.net.URLDecoder.decode(payload, "UTF-8").toByteArray(Charsets.ISO_8859_1)
    }

    private fun httpGet(start: String): ByteArray? {
        var target = URL(start)
        repeat(4) {
            if (target.protocol != "https" && target.protocol != "http") return null
            val conn = target.openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code in 300..399) {
                    // Same-protocol redirects are followed for us; http↔https ones are not.
                    target = URL(target, conn.getHeaderField("Location") ?: return null)
                    return@repeat
                }
                if (code !in 200..299) return null
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) return null
                    }
                    return out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** An ImageView with rounded corners and a placeholder fill, the way the RN component draws a picture. */
internal fun roundedImage(ctx: Context, url: String, radiusPx: Float, placeholder: Int, oval: Boolean = false): ImageView =
    ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = GradientDrawable().apply {
            setColor(placeholder)
            if (oval) shape = GradientDrawable.OVAL else cornerRadius = radiusPx
        }
        clipToOutline = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ImageLoader.load(this, url)
    }
