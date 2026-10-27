package ai.graine.inapp

import android.annotation.TargetApi
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom

/*
 * WHICH APP THIS IS — the declaration every request to Graine carries
 * (APP-IDENTITY.md §1.1, §1.2): the package name, and the SHA-256 of each
 * certificate the app is signed with. Neither is a secret, and neither proves
 * anything on its own (any client can claim any app): it lets an agent that
 * lists its apps turn away casual reuse of its key. Proof is Play Integrity's
 * (AppProofs.kt).
 *
 * And development mode (§3.6): a debug build carries a debug token instead
 * of a proof.
 */

/** This app as it declares itself: its package name, and its signing certificates' SHA-256 (null when unreadable). */
internal class AppDeclaration(val id: String, val certs: List<String>?) {
    /** `{platform: "android", id, certs?}` — the request's `app` before the pass and the debug token. */
    fun json(): JSONObject {
        val o = JSONObject().put("platform", AppDeclarations.PLATFORM).put("id", id)
        certs?.takeIf { it.isNotEmpty() }?.let { o.put("certs", JSONArray(it)) }
        return o
    }
}

/** The certificates an installed package is signed with, as the platform reports them. A fake in the tests. */
internal interface PackageSigning {
    /** Android 9 (API 28) and later: `getPackageInfo(GET_SIGNING_CERTIFICATES).signingInfo`; null when there is none. */
    fun signingInfo(packageName: String): SigningCertificates?

    /** Android 7–8.1 (API 24–27): `getPackageInfo(GET_SIGNATURES).signatures`, each as DER bytes; null when there are none. */
    fun signatures(packageName: String): List<ByteArray>?
}

/** API 28's `SigningInfo`, as DER bytes: the current signers when there are several, else the rotation history. */
internal class SigningCertificates(
    val multipleSigners: Boolean,
    val apkContentsSigners: List<ByteArray>?,
    val signingCertificateHistory: List<ByteArray>?,
)

/** [PackageSigning] from the platform's PackageManager. */
internal class AndroidPackageSigning(private val pm: PackageManager) : PackageSigning {
    @TargetApi(28)
    override fun signingInfo(packageName: String): SigningCertificates? {
        val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
        return SigningCertificates(
            info.hasMultipleSigners(),
            info.apkContentsSigners?.map { it.toByteArray() },
            info.signingCertificateHistory?.map { it.toByteArray() },
        )
    }

    @Suppress("DEPRECATION")
    override fun signatures(packageName: String): List<ByteArray>? =
        pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.map { it.toByteArray() }
}

internal object AppDeclarations {
    const val PLATFORM = "android"
    /** The most certificates a declaration carries. */
    const val MAX_CERTS = 5

    /**
     * The app's declaration, read once per process: `context.packageName`
     * (none — no declaration at all), and its certificates' digests (none
     * readable — the declaration goes without them).
     */
    fun read(context: Context): AppDeclaration? {
        val id = runCatching { context.packageName }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val pm = runCatching { context.packageManager }.getOrNull()
        val certs = pm?.let { certificates(id, Build.VERSION.SDK_INT, AndroidPackageSigning(it)) }
        return AppDeclaration(id, certs)
    }

    /**
     * The SHA-256 of each certificate [packageName] is signed with (§1.2):
     * on API 28 and later the current signers when there are several, else
     * every certificate in the signing history; before, the signatures. Each
     * digest lowercase hex with no colons, de-duplicated, at most five. Null
     * when none can be read — any exception included.
     */
    fun certificates(packageName: String, sdk: Int, signing: PackageSigning): List<String>? = try {
        val ders = if (sdk >= 28) {
            val info = signing.signingInfo(packageName)
            if (info == null) null else if (info.multipleSigners) info.apkContentsSigners else info.signingCertificateHistory
        } else {
            signing.signatures(packageName)
        }
        ders?.let { digests(it) }?.takeIf { it.isNotEmpty() }
    } catch (t: Throwable) {
        null
    }

    /** Each certificate's SHA-256, lowercase hex, in order, each once, at most [MAX_CERTS]. */
    fun digests(ders: List<ByteArray>): List<String> = ders.map { sha256Hex(it) }.distinct().take(MAX_CERTS)

    fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        val sb = StringBuilder(d.size * 2)
        for (b in d) {
            val v = b.toInt() and 0xff
            sb.append(hex[v shr 4]).append(hex[v and 15])
        }
        return sb.toString()
    }

    /** The app was built debuggable (`ApplicationInfo.FLAG_DEBUGGABLE`): every build Android Studio runs. False when it cannot be read. */
    fun debuggable(context: Context): Boolean =
        runCatching { (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 }.getOrDefault(false)
}

/**
 * DEBUG TOKENS (§3.6): `gdt_` and 24 random bytes in base64url — made once
 * per install, kept with the install id, and sent instead of a proof by a
 * build in development mode. Graine accepts one only once an Admin has
 * registered it on the agent, and only until it expires.
 */
internal object DebugTokens {
    private val SHAPE = Regex("^gdt_[A-Za-z0-9_-]{32}$")

    fun valid(token: String?): Boolean = token != null && SHAPE.matches(token)

    fun fresh(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(24).also { random.nextBytes(it) }
        return "gdt_" + base64Url(bytes)
    }

    /** Whether this build uses a debug token: [GraineDebugTokenMode.ALWAYS], or a debuggable build left [GraineDebugTokenMode.AUTOMATIC]. */
    fun inUse(mode: GraineDebugTokenMode, debuggable: Boolean): Boolean =
        mode == GraineDebugTokenMode.ALWAYS || (mode == GraineDebugTokenMode.AUTOMATIC && debuggable)

    /** The line logged once per install, exactly as every Graine SDK logs it. */
    fun logLine(token: String): String =
        "Graine debug token for this install: $token — register it under Agent → Embed & Widgets → Install → " +
            "Development → Debug tokens to use this build while \"Require a verified app\" is on."

    /** Base64url without padding. */
    fun base64Url(bytes: ByteArray): String = B64.encode(bytes).trimEnd('=').replace('+', '-').replace('/', '_')
}
