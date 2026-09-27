package ai.graine.inapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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

    /**
     * A CSS weight (400–800) on a TextView: exact from Android 9, bold-or-not
     * before. `family` is a font the app handed over for a design; without
     * one it is the system font, and a family that cannot be weighted falls
     * back to it rather than failing.
     */
    fun weight(tv: TextView, w: Int, family: Typeface? = null) {
        if (family != null) {
            runCatching {
                tv.typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(family, w.coerceIn(100, 1000), false)
                else Typeface.create(family, if (w >= 600) Typeface.BOLD else Typeface.NORMAL)
                return
            }
        }
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

/**
 * THE DESIGN'S TOKENS, AS A VIEW TAKES THEM.
 *
 * The bar, the launcher and the components all draw through here, so a
 * shadow, a font role or a transition means the same thing on each. Only
 * called with tokens in hand: without a design the launcher and components
 * keep drawing from the appearance, exactly as before, and the bar draws
 * Graine Classic in the appearance's accent and light or dark.
 */
internal object Themed {
    /** The text roles contract/DESIGN.md names. */
    enum class Role { TITLE, BODY, STRONG, CAPTION }

    fun color(hex: String?, fallback: Int = Color.BLACK): Int = Appearance.argb(hex) ?: fallback

    /** `color` at `alpha` (0–1). */
    fun alpha(color: Int, alpha: Double): Int = (Math.round(alpha.coerceIn(0.0, 1.0) * 255).toInt() shl 24) or (color and 0xFFFFFF)

    /** `surface` — translucent under glass. */
    fun surface(t: ThemeTokens): Int {
        val c = color(t.colors.surface, Color.WHITE)
        return t.glass?.let { alpha(c, it.surfaceOpacity) } ?: c
    }

    /** What reads on an accent the design did not choose (a component's own, a host's): by luminance, as before designs. */
    fun readableOnHex(accentHex: String): String = if (Appearance.luminance(accentHex) > 0.45) "#101318" else "#FFFFFF"

    fun readableOn(accentHex: String): Int = color(readableOnHex(accentHex), Color.WHITE)

    fun px(ctx: Context, dp: Double): Int = Ui.dp(ctx, dp.toFloat())

    /**
     * Text in one of the design's roles: family, size, weight, letter spacing,
     * line height. No role keeps the element's own size (`sp`) and weight,
     * scaled with the design's body size — Graine Classic's 15 leaves it
     * untouched — in the body family.
     */
    fun type(tv: TextView, t: ThemeTokens, role: Role?, graine: GraineInApp?, sp: Float = 15f, weight: Int = 400) {
        val f = t.font
        val heading = role == Role.TITLE
        val size = when (role) {
            Role.TITLE -> f.scale.title
            Role.BODY, Role.STRONG -> f.scale.body
            Role.CAPTION -> f.scale.caption
            null -> sp * (f.scale.body / 15.0)
        }
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
        val w = when (role) {
            Role.TITLE -> f.weights.title
            Role.STRONG -> f.weights.strong
            Role.BODY, Role.CAPTION -> f.weights.body
            null -> weight
        }
        face(tv, if (heading) f.heading else f.body, w, graine)
        tv.letterSpacing = (if (heading) f.letterSpacing.title else f.letterSpacing.body).toFloat()
        if (role == Role.TITLE || role == Role.BODY) Ui.lineHeight(tv, (size * (if (heading) f.lineHeight.title else f.lineHeight.body)).toFloat())
    }

    /**
     * A design family at a weight, on a TextView — contract/DESIGN.md → Fonts:
     *
     *  - the app mapped the family itself ([GraineConfig.fonts]) → that
     *    typeface at the weight, as before 0.3.0: the app's file wins;
     *  - the SDK has loaded the face → the face, and nothing on top: a loaded
     *    face IS its weight, and asking for bold as well would embolden a bold
     *    face twice;
     *  - neither, yet or ever → the system font at the weight, silently.
     */
    fun face(tv: TextView, family: String, weight: Int, graine: GraineInApp?) {
        val own = graine?.typeface(family)
        if (own != null) { Ui.weight(tv, weight, own); return }
        val loaded = graine?.loadedFace(family, weight)
        if (loaded != null) {
            tv.paint.isFakeBoldText = false
            tv.typeface = loaded
            return
        }
        Ui.weight(tv, weight)
    }

    /**
     * A soft shadow as Android draws one — elevation, and the shadow colour
     * where the platform lets it be set (Android 9+). Null: none. A
     * translucent fill casts none either; that is the platform's rule and the
     * right one for glass.
     */
    fun shadow(v: View, s: ThemeShadow?) {
        val (elevation, alpha) = Design.androidShadow(s)
        v.elevation = px(v.context, elevation).toFloat()
        if (Build.VERSION.SDK_INT >= 28 && s != null) {
            val c = alpha(color(s.color), alpha)
            v.outlineSpotShadowColor = c
            v.outlineAmbientShadowColor = c
        }
    }

    /**
     * A surface — fill, outline, corners — and its depth: the soft shadow, or
     * the design's hard offset, a solid copy of the shape in `colors.text`
     * behind it. The offset needs room inside the view, so the padding is set
     * here, grown on the side the copy shows.
     */
    fun surface(
        v: View, t: ThemeTokens, fill: Int, radiusPx: Float, strokePx: Int, strokeColor: Int, shadow: ThemeShadow?,
        left: Int, top: Int, right: Int, bottom: Int,
    ) {
        val front = Ui.rounded(fill, radiusPx, strokePx, strokeColor)
        val o = t.elevation.hardOffset
        if (o == null) {
            v.background = front
            shadow(v, shadow)
            v.setPadding(left, top, right, bottom)
            return
        }
        val x = px(v.context, Math.abs(o.x)); val y = px(v.context, Math.abs(o.y))
        val east = o.x >= 0; val south = o.y >= 0
        v.background = android.graphics.drawable.LayerDrawable(arrayOf(Ui.rounded(color(t.colors.text), radiusPx), front)).apply {
            setLayerInset(0, if (east) x else 0, if (south) y else 0, if (east) 0 else x, if (south) 0 else y)
            setLayerInset(1, if (east) 0 else x, if (south) 0 else y, if (east) x else 0, if (south) y else 0)
        }
        v.elevation = 0f
        v.setPadding(left + (if (east) 0 else x), top + (if (south) 0 else y), right + (if (east) x else 0), bottom + (if (south) y else 0))
    }

    /**
     * Glass on a window the SDK owns: what is behind it blurred, on Android
     * 12+ where the compositor can (and the phone has not turned blur off).
     * Before that, and inside a window the app owns (the bar), glass is
     * translucency alone — a view cannot blur what lies under it, and
     * RenderEffect would blur the view's own content instead.
     */
    fun blurBehind(w: android.view.Window, glass: ThemeGlass?) {
        if (Build.VERSION.SDK_INT < 31) return
        val radius = glass?.blur?.takeIf { it > 0 }?.let { px(w.context, it) } ?: 0
        if (radius > 0) w.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        else w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        w.attributes = w.attributes.apply { blurBehindRadius = radius }
    }

    /** The phone's animator duration scale: 0 when the customer turned animations off. */
    fun animatorScale(ctx: Context): Float =
        runCatching { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }
            .getOrDefault(1f)

    /** How long a transition runs here, ms; 0 is none (see [Design.motionMs]). */
    fun motionMs(ctx: Context, t: ThemeTokens?): Long = Design.motionMs(t?.motion, animatorScale(ctx))

    fun interpolator(m: ThemeMotion): android.animation.TimeInterpolator = when (m.easing) {
        "emphasized" -> android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
        "spring" -> android.view.animation.OvershootInterpolator((0.5 + m.bounce * 4).toFloat())
        "linear" -> android.view.animation.LinearInterpolator()
        else -> android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)
    }

    /** Fade a view in to `toAlpha`, rising `risePx`, over the design's duration. Nothing at all when that is none. */
    fun enter(v: View, t: ThemeTokens?, risePx: Float = 0f, toAlpha: Float = 1f) {
        val ms = motionMs(v.context, t)
        if (ms <= 0 || t == null) return
        v.alpha = 0f
        v.translationY = risePx
        v.animate().alpha(toAlpha).translationY(0f).setDuration(ms).setInterpolator(interpolator(t.motion)).start()
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

/** A ScrollView that stops growing at a height — a sheet's body, the chat's messages. */
internal class CappedScrollView(context: Context, maxHeightPx: Int) : ScrollView(context) {
    var maxHeightPx: Int = maxHeightPx
        set(v) { if (field != v) { field = v; requestLayout() } }

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

    /** GET a URL's body, following redirects (http↔https too); null for anything but a 2xx under `maxBytes`. Off the main thread. */
    fun httpGet(start: String, maxBytes: Int = MAX_BYTES): ByteArray? {
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
                        if (out.size() > maxBytes) return null
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
