package ai.graine.sample

import ai.graine.inapp.ConnectionQuality
import ai.graine.inapp.Connectivity
import ai.graine.inapp.FrictionSignal
import ai.graine.inapp.GraineAgentBarView
import ai.graine.inapp.GraineField
import ai.graine.inapp.GraineInApp
import ai.graine.inapp.GraineListener
import ai.graine.inapp.GraineLoaderView
import ai.graine.inapp.Loaders
import ai.graine.inapp.GraineScreen
import ai.graine.inapp.Role
import ai.graine.inapp.SessionConfig
import ai.graine.inapp.Transport
import android.app.Activity
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

class MainActivity : Activity() {
    private val graine get() = GraineInApp.get()
    private lateinit var bar: GraineAgentBarView
    private lateinit var reportScreen: () -> Unit

    /** Every event, to logcat — what the device test reads, and a template for your own analytics. */
    private val logger = object : GraineListener {
        override fun onSessionReady(session: SessionConfig) = log("session agent=${session.webcallAgentId} transport=${session.preferredTransport.wire} page=${session.pageVersion}")
        override fun onConnectivity(state: Connectivity, attempt: Int, reason: String?) = log("connectivity $state attempt=$attempt reason=$reason")
        override fun onCallState(connected: Boolean, connecting: Boolean) = log("call connected=$connected connecting=$connecting")
        override fun onCaption(role: Role, text: String, live: Boolean) = log("caption $role live=$live: $text")
        override fun onQuality(quality: ConnectionQuality) = log("quality $quality")
        override fun onNetwork(online: Boolean) = log("network online=$online")
        override fun onTransport(transport: Transport, requested: Transport, reason: String?, message: String?) = log("transport ${transport.wire} (asked ${requested.wire}) reason=$reason")
        override fun onWidget(widget: JSONObject) = log("widget ${widget.optString("kind")}")
        override fun onFriction(signal: FrictionSignal) = log("friction ${signal.name} on ${signal.screen}: ${signal.detail}")
        override fun onEnded() = log("ended")
        override fun onMicDenied(reason: String) = log("mic denied: $reason")
        override fun onError(message: String) = log("ERROR $message")
        private fun log(s: String) { Log.i("GraineSample", s) }
    }

    private val dark get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Follow the phone's light/dark setting, like the bar does (the dashboard's theme is "auto").
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        val ink = if (dark) Color.WHITE else Color.parseColor("#101318")
        val muted = if (dark) Color.parseColor("#A0A5AE") else Color.parseColor("#5F6672")
        val root = FrameLayout(this).apply { setBackgroundColor(Color.parseColor(if (dark) "#0E0F12" else "#F4F5F7")) }

        // The screen the agent is helping with: one field, reported as it changes.
        val error = TextView(this).apply { setTextColor(Color.parseColor("#D32F2F")); textSize = 13f; visibility = TextView.GONE }
        val pan = EditText(this).apply {
            hint = "ABCDE1234F"
            contentDescription = "PAN"
            textSize = 20f
            setTextColor(ink)
            setHintTextColor(muted)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.AllCaps(), InputFilter.LengthFilter(10))
            background = GradientDrawable().apply {
                setColor(if (dark) Color.parseColor("#17181B") else Color.WHITE)
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), Color.parseColor(if (dark) "#2C2E33" else "#E5E4E0"))
            }
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), 0)
            addView(TextView(context).apply { text = "KYC · STEP 2 OF 4"; setTextColor(muted); textSize = 12f; letterSpacing = 0.08f })
            addView(TextView(context).apply { text = "Upload your PAN"; setTextColor(ink); setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f); paint.isFakeBoldText = true }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(TextView(context).apply { text = "Enter it exactly as printed on the card."; setTextColor(muted); textSize = 15f }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4); bottomMargin = dp(18) })
            addView(TextView(context).apply { text = "PAN"; setTextColor(ink); textSize = 13f; paint.isFakeBoldText = true }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })
            addView(pan, LinearLayout.LayoutParams(-1, -2))
            addView(error, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(TextView(context).apply {
                text = "The assistant sees this screen. Tap the call button to talk; drag the bar to move it — it stays where you leave it."
                setTextColor(muted); textSize = 13f
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
            // The next step is another activity: a call in progress follows the customer there.
            addView(TextView(context).apply {
                text = "Continue"
                contentDescription = "Continue"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE); textSize = 16f; paint.isFakeBoldText = true
                background = GradientDrawable().apply { setColor(Color.parseColor("#318CE7")); cornerRadius = dp(12).toFloat() }
                setPadding(dp(14), dp(14), dp(14), dp(14))
                setOnClickListener { startActivity(android.content.Intent(this@MainActivity, SelfieActivity::class.java)) }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // Step 4 (optional): the default bar — components the agent draws appear on it.
        bar = GraineAgentBarView(this)
        root.addView(bar, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(16)) })

        // Where the customer left the bar, kept across launches. The SDK has no storage; the app does.
        val prefs = getSharedPreferences("graine_sample", MODE_PRIVATE)
        bar.placementOffset = prefs.getInt("bar_offset_dp", 0)
        bar.onPlacementChange = { offset ->
            prefs.edit().putInt("bar_offset_dp", offset).apply()
            Log.i("GraineSample", "placement bottomOffset=$offset")
        }

        // Edge to edge (every app targeting Android 15+): keep clear of the system bars ourselves.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = systemBars(insets)
            v.setPadding(0, top, 0, bottom)
            insets
        }
        setContentView(root)

        graine.addListener(logger)
        graine.identify(mapOf("name" to "Sample Tester"))
        // Step 2: what the customer is looking at — reported again whenever the field changes.
        fun report(value: String) {
            val valid = Regex("^[A-Z]{5}[0-9]{4}[A-Z]$").matches(value)
            val wrong = value.length == 10 && !valid
            error.text = if (wrong) "That doesn't look like a PAN — five letters, four digits, one letter." else ""
            error.visibility = if (wrong) TextView.VISIBLE else TextView.GONE
            graine.setScreen(GraineScreen(
                screen = "kyc_documents",
                title = "Upload your documents",
                journey = GraineScreen.Journey("KYC", step = 2, of = 4, next = "Selfie"),
                fields = listOf(GraineField(
                    name = "pan", label = "PAN", value = value,
                    status = when { value.isEmpty() -> GraineField.Status.EMPTY; wrong -> GraineField.Status.INVALID; else -> GraineField.Status.FILLED },
                    error = if (wrong) error.text.toString() else null,
                )),
            ))
        }
        reportScreen = { report(pan.text?.toString().orEmpty()) }
        pan.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = report(s?.toString().orEmpty())
        })
        report("")
        graine.voice.attach(this)
        if (intent?.getBooleanExtra("autostart", false) == true) graine.voice.start()
        if (BuildConfig.DEBUG) {
            if (intent?.getBooleanExtra("maskCheck", false) == true) runMaskCheck()
            showDemoWidget()
            if (intent?.hasExtra("demoLoaders") == true) content.addView(demoLoaders(muted), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        }
    }

    @Suppress("DEPRECATION")
    private fun systemBars(insets: WindowInsets): Pair<Int, Int> =
        if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
        else insets.systemWindowInsetTop to insets.systemWindowInsetBottom

    /**
     * Debug builds only: every loader kind the dashboard offers, side by side —
     * the agent's own dashboard picks one ("mark"), so the others are otherwise
     * never on screen. `--ez demoLoaders true`.
     */
    private fun demoLoaders(labelColor: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        for (kind in Loaders.KINDS) {
            val look = Loaders.resolve(JSONObject().put("kind", kind).put("size", "large"))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(GraineLoaderView(context).apply {
                    configure(look, Color.parseColor("#318CE7"), Color.parseColor("#E5E4E0"), logoUrl = null)
                    contentDescription = "loader $kind"
                }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) })
                addView(TextView(context).apply { text = kind; setTextColor(labelColor); textSize = 14f })
            }, LinearLayout.LayoutParams(-1, dp(44)))
        }
    }

    /**
     * Debug builds only: the contract's mask cases through THIS device's regex
     * engine — ICU on a phone, not the desktop JVM the unit tests run on, and
     * the two disagree about what a word boundary is next to Hindi text.
     *   adb push sdk/contract/fixtures.json /sdcard/Android/data/ai.graine.sample/files/
     *   adb shell am start -n ai.graine.sample/.MainActivity --ez maskCheck true
     */
    private fun runMaskCheck() {
        val cases = JSONObject(java.io.File(getExternalFilesDir(null), "fixtures.json").readText()).getJSONArray("mask")
        var failed = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val got = ai.graine.inapp.Masking.mask(c.getString("input"))
            if (got != c.getString("output")) { failed++; Log.e("GraineSample", "maskCheck FAIL ${c.getString("input")} -> $got (want ${c.getString("output")})") }
        }
        Log.i("GraineSample", "maskCheck ${cases.length() - failed}/${cases.length()} pass on this device's regex engine")
        // The rule as it was, verbatim from JavaScript: what this engine does with it.
        val verbatim = Regex("\\b[A-Z]{5}[0-9]{4}[A-Z]\\b").replace("PAN ABCDE1234Fहै", "[PAN]")
        Log.i("GraineSample", "maskCheck verbatim JS rule on this engine: $verbatim")
    }

    /** Debug builds only: draw a hard-coded component, so the renderer can be seen without a call. */
    private fun showDemoWidget() {
        val kind = intent?.getStringExtra("demoWidget") ?: return
        val style = DemoWidgets.style(intent.getStringExtra("demoPreset"), intent.getStringExtra("demoSelect"),
            intent.getStringExtra("demoSurface"), intent.getStringExtra("demoColumns"))
        val view = bar.widgetView
        when (kind) {
            "pending" -> view.presentPending(DemoWidgets.pending(intent.getStringExtra("demoPendingKind") ?: "product_list"))
            "pending_then_list" -> {
                view.presentPending(DemoWidgets.pending("product_list"))
                bar.postDelayed({ view.present(DemoWidgets.frame("product_list", style)) }, 2_500)
            }
            else -> view.present(DemoWidgets.frame(kind, style))
        }
        Log.i("GraineSample", "demo widget $kind style=$style")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!graine.voice.onRequestPermissionsResult(requestCode, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the next step: this screen is what the customer sees again.
        if (::reportScreen.isInitialized) reportScreen()
    }

    override fun onDestroy() {
        // No detach here: the engine follows the customer to whichever activity
        // is in front, and a call started on this step carries on in the next.
        graine.removeListener(logger)
        super.onDestroy()
    }
}
