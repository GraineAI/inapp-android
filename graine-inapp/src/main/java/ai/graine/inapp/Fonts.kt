package ai.graine.inapp

import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/*
 * THE DESIGN'S TYPEFACES, FETCHED BY THE SDK (contract/DESIGN.md → Fonts).
 *
 * A brand picks a typeface in the dashboard and expects the phone to draw it.
 * Until 0.3.0 that happened only if the app bundled the exact family and
 * mapped it in GraineConfig.fonts — which in practice was never, so the phone
 * drew the system font and the brand saw its typeface change nowhere. Now the
 * SDK decides which faces the design draws with, fetches them from Graine (the
 * origin the app already talks to, never a third party), keeps them on disk and
 * registers them itself. The app names no font.
 *
 * Two halves: [Fonts] is the arithmetic — which faces, which weight, which
 * address — pure, so the contract's answers (design.json → fontFaces) are
 * proven on the JVM. [FontStore] is the phone's half: disk, network, Typeface.
 */

/** One face of one family, as the SDK registers it. */
internal data class FontFace(
    val family: String,
    val weight: Int,
    /** `Graine-<family, letters and digits only>-<weight>`: the name every SDK registers it under. */
    val name: String,
    val url: String,
)

internal object Fonts {
    /**
     * Bump when a file behind /api/embed/font changes, so no cache keeps the
     * old one — the React Native SDK's FONT_ASSET_VERSION (design.json → fontAssetVersion).
     */
    const val FONT_ASSET_VERSION = 1

    /**
     * Every curated family and the weights it actually has — design.json →
     * fontWeights, which the server's /api/embed/font serves exactly. A family
     * missing here is one this build has no files for: the system font.
     */
    val WEIGHTS: Map<String, List<Int>> = linkedMapOf(
        "Inter" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Roboto" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Geist" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "IBM Plex Sans" to listOf(100, 200, 300, 400, 500, 600, 700),
        "IBM Plex Sans Devanagari" to listOf(100, 200, 300, 400, 500, 600, 700),
        "DM Sans" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Noto Sans" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Noto Sans Devanagari" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Noto Sans Tamil" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Mukta" to listOf(200, 300, 400, 500, 600, 700, 800),
        "Hind" to listOf(300, 400, 500, 600, 700),
        "Anek Devanagari" to listOf(100, 200, 300, 400, 500, 600, 700, 800),
        "Poppins" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Baloo 2" to listOf(400, 500, 600, 700, 800),
        "Nunito" to listOf(200, 300, 400, 500, 600, 700, 800, 900),
        "Atkinson Hyperlegible Next" to listOf(200, 300, 400, 500, 600, 700, 800),
        "Source Serif 4" to listOf(200, 300, 400, 500, 600, 700, 800, 900),
        "Newsreader" to listOf(200, 300, 400, 500, 600, 700, 800),
        "Fraunces" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Playfair Display" to listOf(400, 500, 600, 700, 800, 900),
        "Tiro Devanagari Hindi" to listOf(400),
        "Noto Serif Devanagari" to listOf(100, 200, 300, 400, 500, 600, 700, 800, 900),
        "Martel" to listOf(200, 300, 400, 600, 700, 800, 900),
        "JetBrains Mono" to listOf(100, 200, 300, 400, 500, 600, 700, 800),
    )

    /** The registry's key for a face: `family|weight`. */
    fun faceKey(family: String, weight: Int): String = "$family|$weight"

    /** The weight a family actually has nearest the one asked for; on a tie, the heavier. Null for none. */
    fun nearestWeight(available: List<Int>, want: Int): Int? {
        var best: Int? = null
        for (w in available) {
            val b = best
            if (b == null || Math.abs(w - want) < Math.abs(b - want) || (Math.abs(w - want) == Math.abs(b - want) && w > b)) best = w
        }
        return best
    }

    /** `<origin>/api/embed/font?f=<family>&w=<weight>&v=<version>` — the family encoded as encodeURIComponent does. */
    fun fontUrl(origin: String?, family: String, weight: Int): String {
        val base = (origin?.takeIf { it.isNotEmpty() } ?: Appearance.DEFAULT_ORIGIN).trimEnd('/')
        return "$base/api/embed/font?f=${Appearance.encodeURIComponent(family)}&w=$weight&v=$FONT_ASSET_VERSION"
    }

    /** `Graine-<family with only ASCII letters and digits>-<weight>`. */
    fun faceName(family: String, weight: Int): String =
        "Graine-" + family.filter { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' } + "-" + weight

    /** The curated family's own spelling, matched as written and then ignoring case; null for "system" or an unknown family. */
    fun canonical(family: String?): String? {
        val want = Design.fontFamily(family) ?: return null
        if (WEIGHTS.containsKey(want)) return want
        return WEIGHTS.keys.firstOrNull { it.equals(want, ignoreCase = true) }
    }

    /** The face to load for `family` drawn at `weight`, or null for the system font or a family this build has no files for. */
    fun faceFor(family: String?, weight: Int, origin: String? = null): FontFace? {
        val f = canonical(family) ?: return null
        val w = nearestWeight(WEIGHTS.getValue(f), weight) ?: return null
        return FontFace(f, w, faceName(f, w), fontUrl(origin, f, w))
    }

    /** The faces one token set draws with: the body family at body and strong, the heading family at title and strong. */
    fun facesFor(t: ThemeTokens, origin: String? = null): List<FontFace> {
        val out = LinkedHashMap<String, FontFace>()
        fun add(family: String, weight: Int) { faceFor(family, weight, origin)?.let { out[faceKey(it.family, it.weight)] = it } }
        add(t.font.body, t.font.weights.body)
        add(t.font.body, t.font.weights.strong)
        add(t.font.heading, t.font.weights.title)
        add(t.font.heading, t.font.weights.strong)
        return out.values.toList()
    }

    /** Every face a design can draw with, in either scheme — by family, then weight (design.json → fontFaces). */
    fun facesForDesign(design: ResolvedDesign, origin: String? = null): List<FontFace> {
        val out = LinkedHashMap<String, FontFace>()
        for (t in listOf(design.light, design.dark)) for (f in facesFor(t, origin)) out[faceKey(f.family, f.weight)] = f
        return out.values.sortedWith(compareBy<FontFace> { it.family }.thenBy { it.weight })
    }

    /**
     * Which of the design's faces sets text in `family` at `weight`: the face
     * for that weight when the design loads it; for a weight the design never
     * asks for (a component's own 800, say) the nearest face the design does
     * load, so a card is not half the brand's typeface and half the system's.
     * Null — the system font — for a family the design does not load at all.
     */
    fun faceToDraw(family: String?, weight: Int, designFaces: List<FontFace>): FontFace? {
        val f = canonical(family) ?: return null
        val own = designFaces.filter { it.family == f }
        if (own.isEmpty()) return null
        val exact = nearestWeight(WEIGHTS.getValue(f), weight)
        own.firstOrNull { it.weight == exact }?.let { return it }
        val w = nearestWeight(own.map { it.weight }, weight)
        return own.firstOrNull { it.weight == w }
    }

    /**
     * Whether bytes start the way a font file does: TrueType (00 01 00 00 or
     * "true"), OpenType CFF ("OTTO") or a collection ("ttcf"). A captive portal's
     * HTML saved as Inter.ttf would otherwise be kept on disk for a year.
     */
    fun looksLikeFont(bytes: ByteArray?): Boolean {
        if (bytes == null || bytes.size < 4) return false
        val head = bytes.copyOfRange(0, 4)
        return head.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
            listOf("true", "OTTO", "ttcf").any { head.contentEquals(it.toByteArray(Charsets.US_ASCII)) }
    }

    /** The file a face's URL is kept in: the URL's SHA-256, so a new version is a new file and one URL is one file. */
    fun cacheName(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } + ".ttf"
}

/**
 * THE PHONE'S HALF: each face fetched once, kept on disk by its URL, registered
 * with Typeface.createFromFile, and announced so every SDK view redraws in it.
 *
 * Silent by contract: a face that fails to arrive is the system font, never an
 * error the customer sees (a debug build logs why). A failure is forgotten, so
 * the next session can try again; a success is never fetched again.
 *
 * `want` and `face` are main-thread calls; disk and network are on one
 * background thread of their own, so a slow font never delays a session.
 */
internal class FontStore(
    private val dir: File,
    private val debug: Boolean,
    /** Main thread, once per face that arrives. */
    private val onLoaded: () -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "graine-fonts").apply { isDaemon = true } }

    /** `family|weight` → the registered face. */
    private val loaded = HashMap<String, Typeface>()
    /** Keys on their way or arrived: never fetched twice. */
    private val asked = HashSet<String>()
    /** The faces the design being drawn uses — what [face] chooses among. */
    private var designFaces: List<FontFace> = emptyList()

    /** The design's faces: remember them, and fetch any not yet here. */
    fun want(faces: List<FontFace>) {
        designFaces = faces
        for (f in faces) {
            val key = Fonts.faceKey(f.family, f.weight)
            if (!asked.add(key)) continue
            worker.execute {
                val face = try { obtain(f) } catch (e: Throwable) {
                    if (debug) android.util.Log.d(GraineInApp.TAG, "font ${f.name} did not load: $e")
                    null
                }
                main.post {
                    if (face != null) { loaded[key] = face; onLoaded() } else asked.remove(key)
                }
            }
        }
    }

    /** The face to set text in `family` at `weight`, or null for the system font (see [Fonts.faceToDraw]). */
    fun face(family: String?, weight: Int): Typeface? =
        Fonts.faceToDraw(family, weight, designFaces)?.let { loaded[Fonts.faceKey(it.family, it.weight)] }

    /** From disk when it is there and is a font; from Graine otherwise, written whole before it is used. */
    private fun obtain(f: FontFace): Typeface? {
        dir.mkdirs()
        val file = File(dir, Fonts.cacheName(f.url))
        if (file.length() > 0) {
            val head = ByteArray(4).also { b -> file.inputStream().use { it.read(b) } }
            if (Fonts.looksLikeFont(head)) return Typeface.createFromFile(file)
            file.delete()
        }
        val bytes = ImageLoader.httpGet(f.url, MAX_FONT_BYTES) ?: return null
        if (!Fonts.looksLikeFont(bytes)) return null
        // Written to the side and renamed: a process killed mid-write leaves no half font under the real name.
        val part = File(dir, file.name + ".part")
        part.writeBytes(bytes)
        if (!part.renameTo(file)) { part.delete(); return null }
        return Typeface.createFromFile(file)
    }

    private companion object {
        /** The largest curated face is well under a megabyte; anything near this is not a font. */
        const val MAX_FONT_BYTES = 6 * 1024 * 1024
    }
}
