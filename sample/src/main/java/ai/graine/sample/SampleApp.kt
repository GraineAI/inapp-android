package ai.graine.sample

import ai.graine.inapp.ActionResult
import ai.graine.inapp.GraineConfig
import ai.graine.inapp.GraineInApp
import ai.graine.inapp.Transport
import android.app.Application

/**
 * THE WHOLE INTEGRATION, step 1: one line in Application.onCreate.
 *
 * The publishable key selects the agent. It is public by design — it cannot
 * read data or place a phone call — but its agent must list `app://` under
 * Allowed domains, or every request is refused.
 */
class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // No key yet: the main screen says what to add, and the Chat styles screen (debug builds) runs without an agent.
        if (BuildConfig.GRAINE_PUBLISHABLE_KEY.isEmpty()) {
            android.util.Log.w("GraineSample", "No publishable key: set graine.publishableKey=pk_live_… in local.properties, then rebuild.")
            return
        }
        val graine = GraineInApp.init(this, GraineConfig(
            baseUrl = "https://www.graine.ai",
            publishableKey = BuildKeys.PUBLISHABLE_KEY,
            variables = mapOf("brand" to "Graine sample"),
            appVersion = "0.5.0",
            // Every bridge message and the engine page's console, in logcat. Debug builds only.
            debug = BuildConfig.DEBUG,
            // Debug builds can force a transport for testing:
            //   adb shell setprop debug.graine.transport rtc
            transport = if (BuildConfig.DEBUG) debugTransport() else null,
        ))
        // Step 2b, for an agent that requires a verified app: prove this app now, so the first conversation never
        // waits for it (the SDK also does it by itself). Debug builds use a debug token instead: it is logged once.
        graine.prepareAppVerification { verdict -> android.util.Log.i("GraineSample", "app verification: ${verdict.word}") }
        // Step 3: something the agent may do. Declare it on the agent too.
        // It is also the one handler a component's `on_select.path` runs through ("compare?ids={id}").
        graine.registerAction("open_screen") { args ->
            android.util.Log.i("GraineSample", "open_screen $args")
            ActionResult.ok("Opened ${args.optString("path").ifEmpty { args.optString("screen", "the screen") }}.")
        }
    }
}

/** `adb shell setprop debug.graine.transport rtc|ws`; unset means the dashboard decides. */
private fun debugTransport(): Transport? = runCatching {
    val v = Runtime.getRuntime().exec(arrayOf("getprop", "debug.graine.transport"))
        .inputStream.bufferedReader().readText().trim()
    when (v) { "rtc" -> Transport.WEBRTC; "ws" -> Transport.WEBSOCKET; else -> null }
}.getOrNull()
