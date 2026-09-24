package ai.graine.sample

import ai.graine.inapp.GraineAgentBarView
import ai.graine.inapp.GraineInApp
import ai.graine.inapp.GraineScreen
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The next KYC step, as its own activity — the shape of most native onboarding
 * flows. It does NOT attach the voice engine: MainActivity did that once, and
 * the engine follows the customer here, so a call placed on the PAN step keeps
 * talking on this one. This activity only reports its screen and places a bar.
 */
class SelfieActivity : Activity() {
    private val graine get() = GraineInApp.get()

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val ink = if (dark) Color.WHITE else Color.parseColor("#101318")
        val muted = if (dark) Color.parseColor("#A0A5AE") else Color.parseColor("#5F6672")
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor(if (dark) "#0E0F12" else "#F4F5F7")) }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), 0)
            addView(TextView(context).apply { text = "KYC · STEP 3 OF 4"; setTextColor(muted); textSize = 12f; letterSpacing = 0.08f })
            addView(TextView(context).apply { text = "Take a selfie"; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); paint.isFakeBoldText = true },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(TextView(context).apply { text = "A call started on the last step carries on here."; setTextColor(muted); textSize = 15f },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Each activity that should show the agent places a bar; the call behind it is shared.
        val bar = GraineAgentBarView(this)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(16)) })
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = systemBars(insets)
            v.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(root)
    }

    @Suppress("DEPRECATION")
    private fun systemBars(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
        else insets.systemWindowInsetTop to insets.systemWindowInsetBottom

    override fun onResume() {
        super.onResume()
        graine.setScreen(GraineScreen(
            screen = "kyc_selfie",
            title = "Take a selfie",
            journey = GraineScreen.Journey("KYC", step = 3, of = 4, next = "Review"),
        ))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!graine.voice.onRequestPermissionsResult(requestCode, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }
}
