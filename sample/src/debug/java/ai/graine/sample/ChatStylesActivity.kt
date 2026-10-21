package ai.graine.sample

import ai.graine.inapp.GraineChatPreview
import ai.graine.inapp.ResolvedDesign
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/**
 * DEBUG BUILDS ONLY: CHAT STYLES — each style the dashboard offers for the
 * chat window, drawn by the SDK's own chat window with a conversation made up
 * on the device: no agent, no key, no server. The designs are the contract's
 * (assets/chat-styles.json, from sdk/contract/design.json).
 *
 *   adb shell am start -n ai.graine.sample/.ChatStylesActivity --es style soft --es moment typing --ez dark true
 *
 * style   classic | soft | bold | glass | minimal | crisp | cozy | calm | compact | lively   (none: the list)
 * moment  conversation | typing | rating | reconnecting | offline | fresh | disconnected
 * dark    true for the dark scheme
 * frame   window: the compact window that rides above the voice bar (default: the whole screen)
 * rtl     true to lay the screen out right to left
 */
class ChatStylesActivity : Activity() {
    private fun dp(v: Int) = Math.round(v * resources.displayMetrics.density)

    private val styles by lazy {
        val all = JSONObject(assets.open("chat-styles.json").bufferedReader().use { it.readText() }).getJSONArray("styles")
        (0 until all.length()).map { all.getJSONObject(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val chosen = styles.firstOrNull { it.getString("id") == intent?.getStringExtra(EXTRA_STYLE) }
        if (chosen != null) showStyle(chosen) else showList()
    }

    /** One style, full screen: the system bars in the chat's own colours, the conversation between them. */
    private fun showStyle(style: JSONObject) {
        val design = ResolvedDesign.parse(style.getJSONObject("design")) ?: return finish()
        val dark = intent?.getBooleanExtra(EXTRA_DARK, false) == true
        val moment = intent?.getStringExtra(EXTRA_MOMENT) ?: GraineChatPreview.CONVERSATION
        val compact = intent?.getStringExtra(EXTRA_FRAME) == "window"
        val rtl = intent?.getBooleanExtra(EXTRA_RTL, false) == true
        val (top, bottom) = GraineChatPreview.barColors(design, dark).let { it[0] to it[1] }
        val statusBar = View(this).apply { setBackgroundColor(top) }
        val navBar = View(this).apply { setBackgroundColor(bottom) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusBar, LinearLayout.LayoutParams(-1, 0))
            addView(GraineChatPreview.view(this@ChatStylesActivity, design, dark, moment, compact), LinearLayout.LayoutParams(-1, 0, 1f))
            addView(navBar, LinearLayout.LayoutParams(-1, 0))
        }
        // Edge to edge (Android 15+): the bars are drawn by the screen itself, in the chat's colours.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (t, b) = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
                else @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            statusBar.layoutParams = LinearLayout.LayoutParams(-1, t)
            navBar.layoutParams = LinearLayout.LayoutParams(-1, b)
            insets
        }
        // Right to left, as a phone in Arabic or Hebrew lays the screen out.
        if (rtl) root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (luminance(top) > 0.4) mask else 0, mask)
        }
    }

    /** Every style, to open one: light or dark, at the conversation, the typing indicator or the rating. */
    private fun showList() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val ink = if (dark) Color.WHITE else Color.parseColor("#101318")
        val muted = if (dark) Color.parseColor("#A0A5AE") else Color.parseColor("#5F6672")
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        col.addView(TextView(this).apply {
            text = "Chat styles"
            setTextColor(ink)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            paint.isFakeBoldText = true
        })
        col.addView(TextView(this).apply {
            text = "Each style, drawn by the SDK's chat window with a sample conversation. No agent or network needed."
            setTextColor(muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(4), 0, dp(12))
        })
        val darkBox = CheckBox(this).apply { text = "Dark"; setTextColor(ink) }
        col.addView(darkBox)
        for (style in styles) {
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = GradientDrawable().apply {
                    setColor(if (dark) Color.parseColor("#17181B") else Color.WHITE)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), Color.parseColor(if (dark) "#2C2E33" else "#E5E4E0"))
                }
                addView(TextView(context).apply {
                    text = style.getString("name")
                    setTextColor(ink)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    paint.isFakeBoldText = true
                })
                addView(TextView(context).apply {
                    text = style.getString("description")
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                    for ((label, moment) in listOf("Conversation" to GraineChatPreview.CONVERSATION, "Typing" to GraineChatPreview.TYPING,
                        "Rating" to GraineChatPreview.RATING, "Offline" to GraineChatPreview.OFFLINE)) {
                        addView(TextView(context).apply {
                            text = label
                            setTextColor(Color.parseColor("#318CE7"))
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                            setPadding(0, dp(6), dp(18), dp(6))
                            setOnClickListener { open(style.getString("id"), moment, darkBox.isChecked) }
                        })
                    }
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor(if (dark) "#0E0F12" else "#F4F5F7"))
            addView(col)
        }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val (t, b) = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).let { it.top to it.bottom }
                else @Suppress("DEPRECATION") (insets.systemWindowInsetTop to insets.systemWindowInsetBottom)
            v.setPadding(0, t, 0, b)
            insets
        }
        setContentView(scroll)
    }

    private fun open(style: String, moment: String, dark: Boolean) {
        startActivity(Intent(this, ChatStylesActivity::class.java)
            .putExtra(EXTRA_STYLE, style).putExtra(EXTRA_MOMENT, moment).putExtra(EXTRA_DARK, dark))
    }

    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    companion object {
        const val EXTRA_STYLE = "style"
        const val EXTRA_MOMENT = "moment"
        const val EXTRA_DARK = "dark"
        const val EXTRA_FRAME = "frame"
        const val EXTRA_RTL = "rtl"
    }
}
