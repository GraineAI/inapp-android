package ai.graine.inapp

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import org.json.JSONObject

/**
 * THE CALL SCREEN IN ANY DESIGN AND ANY MOMENT — the test APK only, for
 * screenshots and for looking at a design on a device. Draws the call screen
 * view exactly as the call activity does, from a design in design.json (the
 * contract, served to this APK as its assets), with a sample call: "Asha", a
 * caption, the speaker on and the agent speaking while connected.
 *
 *   adb shell am start -S -n ai.graine.inapp.test/ai.graine.inapp.CallScreenGalleryActivity \
 *       --es preset aurora --es moment connected [--es philosophy signature] [--es scheme light]
 *
 * `preset` is a call preset's id (design.json → cases whose config has
 * call.preset); `moment` one of incoming, scheduled, outgoing, connecting,
 * connected, reconnecting, ended, missed.
 */
class CallScreenGalleryActivity : Activity() {
    private var screen: CallScreenView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val preset = intent.getStringExtra("preset") ?: "classic"
        val philosophy = intent.getStringExtra("philosophy") ?: "signature"
        val scheme = intent.getStringExtra("scheme") ?: "light"
        val moment = CallScreen.Moment.values().firstOrNull { it.wire == intent.getStringExtra("moment") } ?: CallScreen.Moment.ON_CALL
        val contract = JSONObject(assets.open("design.json").bufferedReader().use { it.readText() })
        val cases = contract.getJSONArray("cases")
        val design = (0 until cases.length()).map { cases.getJSONObject(it) }.firstOrNull { c ->
            val config = c.getJSONObject("config")
            config.optString("preset") == philosophy && config.optJSONObject("call")?.optString("preset") == preset && config.optString("accent").isEmpty()
        }?.getJSONObject("design") ?: error("no design for $philosophy / $preset in design.json")
        val resolved = ResolvedDesign.parse(design)!!
        val t = if (scheme == "dark") resolved.dark else resolved.light

        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        val view = CallScreenView(this, null)
        view.setDesign(t, null, LogoPlate.ACCENT)
        setContentView(view)
        val connected = moment == CallScreen.Moment.ON_CALL || moment == CallScreen.Moment.RECONNECTING
        view.render(CallScreenView.State(
            moment = moment,
            name = "Asha",
            speaker = connected,
            agentSpeaking = connected,
            caption = if (connected) Triple(Role.AGENT, "Done. I have moved it to Saturday at ten. Anything else?", false) else null,
            connectedForMs = 42_000,
        ))
        view.look?.let { look ->
            if (Build.VERSION.SDK_INT >= 30) {
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                window.insetsController?.setSystemBarsAppearance(if (Appearance.luminance(look.text) > 0.5) 0 else mask, mask)
            }
        }
        window.decorView.systemUiVisibility = window.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        screen = view
    }

    override fun onResume() { super.onResume(); screen?.setRunning(true) }
    override fun onPause() { super.onPause(); screen?.setRunning(false) }
}
