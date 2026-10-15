package ai.graine.inapp

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * THE CALL PERMISSIONS ON A PHONE: what they are now, asking for them, and
 * the Settings page for each. The rules are CallPermissionRules'.
 *
 * Asking happens on a screen of the SDK's own (GraineCallPermissionActivity,
 * transparent), so no activity of the app has to forward a permission result,
 * and a React Native, Flutter or Kotlin Multiplatform app asks the same way.
 * Android does not say whether a refused permission can still be asked for,
 * so what each ask came to is kept on the device: asked, and refused for good
 * (the system's dialog will not show again). Works before anything is
 * configured: a permission is the phone's, not an agent's. Main thread.
 */
internal object CallPermissions {
    private const val PREFS = "ai.graine.inapp.permissions"
    /** The permissions the SDK's screen asks for, as an extra. */
    const val EXTRA_PERMISSIONS = "ai.graine.inapp.extra.PERMISSIONS"
    const val REQUEST = 0x6E7C

    private val main = Handler(Looper.getMainLooper())
    /** Who waits for the ask in progress. Main thread. */
    private val waiting = ArrayList<GraineCallPermissionsCallback>()
    private var asking = false

    /** The call permissions as the phone holds them now. Any thread. */
    fun read(context: Context): GraineCallPermissions {
        val c = context.applicationContext ?: context
        val prefs = prefs(c)
        val sdk = Build.VERSION.SDK_INT
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val mic = CallPermissionRules.runtime(granted(c, Manifest.permission.RECORD_AUDIO), asked(prefs, MIC), refused(prefs, MIC))
        val enabled = runCatching { nm?.areNotificationsEnabled() }.getOrNull() ?: true
        val notifications = CallPermissionRules.notifications(
            sdk, sdk >= 33 && granted(c, NOTIFICATIONS), enabled, asked(prefs, NOTIFICATIONS), refused(prefs, NOTIFICATIONS),
        )
        val canUse = if (sdk >= 34) runCatching { nm?.canUseFullScreenIntent() }.getOrNull() ?: false else true
        val fullScreen = CallPermissionRules.fullScreen(sdk, declared(c, FULL_SCREEN), canUse)
        return GraineCallPermissions(mic, notifications, fullScreen)
    }

    /**
     * Ask, from [from], for what the system's dialog can still ask for: the
     * microphone, and on Android 13+ notifications. [done] gets the
     * permissions as they stand after, on the main thread — at once when
     * there is nothing to ask, or no screen in front to ask from. The
     * full-screen ring is Settings' alone (`openSettings`). One ask at a
     * time: a second request waits for the first one's answer.
     */
    fun request(context: Context, from: Activity?, done: GraineCallPermissionsCallback) = onMain {
        val c = context.applicationContext ?: context
        val wanted = buildList {
            if (!granted(c, MIC)) add(MIC)
            if (Build.VERSION.SDK_INT >= 33 && !granted(c, NOTIFICATIONS)) add(NOTIFICATIONS)
        }
        if (wanted.isEmpty() || from == null || from.isFinishing) {
            if (wanted.isNotEmpty()) CallLog.debug("call permissions: no screen in front to ask from")
            deliver(listOf(done), read(c))
            return@onMain
        }
        waiting += done
        if (asking) return@onMain
        asking = true
        try {
            from.startActivity(Intent(from, GraineCallPermissionActivity::class.java).putExtra(EXTRA_PERMISSIONS, wanted.toTypedArray()))
            @Suppress("DEPRECATION")
            from.overridePendingTransition(0, 0)
        } catch (e: Exception) {
            CallLog.error("could not ask for the call permissions", e)
            finish(c)
        }
    }

    /** The SDK's screen heard the system's answer: kept, then everyone waiting is told. */
    fun answered(context: Context, permissions: Array<String>, results: IntArray, refusedForGood: (String) -> Boolean) {
        val c = context.applicationContext ?: context
        permissions.forEachIndexed { i, p -> record(c, p, results.getOrNull(i) == PackageManager.PERMISSION_GRANTED, refusedForGood) }
        finish(c)
    }

    /** The SDK asked for [permission] itself (the call screen's microphone) and heard [granted]. */
    fun record(context: Context, permission: String, granted: Boolean, refusedForGood: (String) -> Boolean) {
        val c = context.applicationContext ?: context
        runCatching {
            prefs(c)?.edit()
                ?.putBoolean(ASKED + permission, true)
                ?.putBoolean(REFUSED + permission, !granted && refusedForGood(permission))
                ?.apply()
        }
    }

    /** The ask is over, answered or not: everyone waiting gets the permissions as they stand. */
    fun finish(context: Context) = onMain {
        asking = false
        val callbacks = waiting.toList()
        waiting.clear()
        deliver(callbacks, read(context))
    }

    /**
     * The Settings page that changes [item]: the full-screen ring's own page
     * on Android 14+, the app's notification settings for notifications, the
     * app's page (Permissions → Microphone) for the microphone — and the
     * app's page whenever the first cannot open. True when one opened.
     */
    fun openSettings(context: Context, from: Activity?, item: GraineCallPermission): Boolean {
        val c = context.applicationContext ?: context
        val pkg = c.packageName
        val appPage = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        val first = when {
            item == GraineCallPermission.FULL_SCREEN && Build.VERSION.SDK_INT >= 34 ->
                Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$pkg"))
            item != GraineCallPermission.MICROPHONE && Build.VERSION.SDK_INT >= 26 ->
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            else -> appPage
        }
        for (intent in listOf(first, appPage).distinct()) {
            val starter: Context = from?.takeUnless { it.isFinishing } ?: c
            if (starter !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { starter.startActivity(intent) }.isSuccess) return true
        }
        return false
    }

    /**
     * What calls need that the merged manifest lacks, by name: the
     * permissions, the call screen ([callActivity] when the app draws its
     * own), the SDK's permission screen, the microphone service and the
     * Answer / Decline receiver. Empty when the manifest cannot be read.
     */
    fun missingManifest(context: Context, callActivity: Class<*>?): List<String> {
        val c = context.applicationContext ?: context
        val pm = c.packageManager ?: return emptyList()
        val pkg = c.packageName
        val requested = runCatching {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions?.toSet()
        }.getOrNull() ?: return emptyList()
        val out = CallSetup.REQUIRED_PERMISSIONS.filter { it !in requested }.toMutableList()
        fun component(cls: Class<*>, read: (ComponentName) -> Unit) {
            if (runCatching { read(ComponentName(pkg, cls.name)) }.isFailure) out += cls.simpleName
        }
        @Suppress("DEPRECATION")
        component(callActivity ?: GraineCallActivity::class.java) { pm.getActivityInfo(it, 0) }
        @Suppress("DEPRECATION")
        component(GraineCallPermissionActivity::class.java) { pm.getActivityInfo(it, 0) }
        @Suppress("DEPRECATION")
        component(GraineCallReceiver::class.java) { pm.getReceiverInfo(it, 0) }
        @Suppress("DEPRECATION")
        val service = runCatching { pm.getServiceInfo(ComponentName(pkg, GraineCallService::class.java.name), 0) }.getOrNull()
        when {
            service == null -> out += GraineCallService::class.java.simpleName
            Build.VERSION.SDK_INT >= 29 && service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE == 0 ->
                out += "${GraineCallService::class.java.simpleName} (foregroundServiceType microphone)"
        }
        return out
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private const val MIC = Manifest.permission.RECORD_AUDIO
    private const val NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    private const val FULL_SCREEN = "android.permission.USE_FULL_SCREEN_INTENT"
    private const val ASKED = "asked."
    private const val REFUSED = "refused."

    private fun prefs(c: Context): SharedPreferences? = runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull()
    private fun asked(p: SharedPreferences?, permission: String) = p?.getBoolean(ASKED + permission, false) ?: false
    private fun refused(p: SharedPreferences?, permission: String) = p?.getBoolean(REFUSED + permission, false) ?: false

    private fun granted(c: Context, permission: String): Boolean =
        c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun declared(c: Context, permission: String): Boolean = runCatching {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions?.contains(permission)
    }.getOrNull() ?: true

    private fun deliver(callbacks: List<GraineCallPermissionsCallback>, now: GraineCallPermissions) {
        callbacks.forEach { cb -> runCatching { cb.onResult(now) }.onFailure { CallLog.error("a call permissions callback threw", it) } }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}

/**
 * The SDK's own transparent screen that asks for the call permissions and
 * reports the answer, so no activity of the app forwards a result. Opened by
 * `requestCallPermissions`; never shown on its own.
 */
internal class GraineCallPermissionActivity : Activity() {
    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wanted = intent?.getStringArrayExtra(CallPermissions.EXTRA_PERMISSIONS)
        if (wanted.isNullOrEmpty()) {
            close()
            return
        }
        // Recreated with the system's dialog already up: its answer comes to this instance.
        if (savedInstanceState == null) requestPermissions(wanted, CallPermissions.REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != CallPermissions.REQUEST) return
        reported = true
        // Refused and the dialog will not show again: only Settings can change it now.
        CallPermissions.answered(this, arrayOf(*permissions), grantResults) { !shouldShowRequestPermissionRationale(it) }
        close()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Gone without an answer: whoever waits gets the permissions as they stand.
        if (!reported && !isChangingConfigurations) CallPermissions.finish(this)
    }

    private fun close() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
