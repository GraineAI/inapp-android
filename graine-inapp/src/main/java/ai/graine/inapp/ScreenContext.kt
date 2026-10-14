package ai.graine.inapp

import android.app.Activity
import android.graphics.Rect
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import java.util.WeakHashMap

/*
 * WHAT THE AGENT KNOWS ABOUT THE SCREEN, AND WHEN IT HEARS — the pacing of
 * context frames and the taps the SDK notices by itself (contract §8 "Screen
 * context"). The rules are pure and JVM-tested; TapCapture is the Android half.
 */

/**
 * ONE FRAME NOW, THEN AT MOST ONE PER WINDOW — the React Native client's
 * scheduleScreen (client.ts), exactly:
 *
 *  - a change with no window open goes out AT ONCE, and opens a 400 ms window
 *    — but only if a frame actually went out (registering an action before
 *    any screen exists must not push the first real screen 400 ms back);
 *  - changes inside the window are remembered, and ONE frame carrying the
 *    latest goes out when it closes (which opens the next window);
 *  - [changedTrailing] — a tap, which is context and not news — never goes
 *    out at once: one frame at the end of the window.
 *
 * It replaces a debounce that restarted on every change: the first frame was
 * always 400 ms late, and a screen updating faster than every 400 ms (a
 * field reported on each keystroke) was never sent at all.
 */
internal class ContextThrottle(
    private val windowMs: Long = WINDOW_MS,
    /** Runs a task after a delay; returns what cancels it. */
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    /** Builds the frame and hands it on; false when there was nothing to send. */
    private val flush: () -> Boolean,
) {
    private var window: (() -> Unit)? = null
    private var pending = false

    /** Something the frame carries changed. */
    fun changed() {
        if (window != null) { pending = true; return }
        if (!flush()) return
        open()
    }

    /** Something worth carrying, not worth sending now: one frame at the end of the window. */
    fun changedTrailing() {
        pending = true
        if (window != null) return
        open()
    }

    fun cancel() {
        window?.invoke()
        window = null
        pending = false
    }

    private fun open() {
        window = schedule(windowMs) {
            window = null
            if (pending) { pending = false; changed() }
        }
    }

    companion object {
        /** protocol.ts CONTEXT_THROTTLE_MS. */
        const val WINDOW_MS = 400L
    }
}

/** What counts as a tap, and what a tapped element is called. */
internal object Taps {
    /** A touch that moved less than this (dp) in both axes is a tap — the React Native capture's 12. */
    const val SLOP_DP = 12.0
    /** And lasted less than this. */
    const val MAX_MS = 400L
    /** At most two taps a second reach the context; the rest are the same gesture, or a rage the app can measure itself. */
    const val MIN_GAP_MS = 500L
    /** A label longer than this is a paragraph, not a control's name. */
    const val LABEL_MAX = 40

    fun isTap(dxDp: Double, dyDp: Double, durationMs: Long): Boolean =
        Math.abs(dxDp) < SLOP_DP && Math.abs(dyDp) < SLOP_DP && durationMs in 0 until MAX_MS

    /** One line, trimmed, at most [LABEL_MAX] characters; null when nothing is left. */
    fun label(raw: CharSequence?): String? {
        val s = raw?.toString()?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (s.isEmpty()) return null
        return if (s.length <= LABEL_MAX) s else s.take(LABEL_MAX - 1).trimEnd() + "…"
    }

    /**
     * The name a tapped element goes by, in the order a test would find it:
     * what TalkBack reads (its content description), then its view id, then a
     * button's own words. A plain text field's contents are never a name.
     */
    fun nameFor(contentDescription: CharSequence?, idName: String?, buttonText: CharSequence?): String? =
        label(contentDescription) ?: label(idName?.replace('_', ' ')) ?: label(buttonText)

    /** The event a tap becomes: `tap:<name>`, as `useGraineTap(name)` records it on React Native. */
    fun event(name: String): String = "tap:$name"
}

/**
 * TAPS, NOTICED WITHOUT THE APP WIRING ANYTHING — a pass-through around each
 * window's callback. It only watches: every event goes on to the window
 * exactly as before, and nothing is consumed. A tap on a labelled control
 * (its content description, view id or button text) becomes a coalesced
 * `tap:<name>` in the context's recent events — the agent knows what the
 * customer just pressed — at most twice a second, only while a screen is
 * reported, and never for the SDK's own views.
 */
internal class TapCapture private constructor(
    private val base: Window.Callback,
    private val window: Window,
    private val onTap: (String) -> Unit,
) : Window.Callback by base {
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    override fun dispatchTouchEvent(event: MotionEvent?): Boolean {
        if (event != null) runCatching { watch(event) }
        return base.dispatchTouchEvent(event)
    }

    // Default methods of the platform interface, passed on explicitly so the activity's own still run.
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        base.onProvideKeyboardShortcuts(data, menu, deviceId)

    @android.annotation.TargetApi(26)
    override fun onPointerCaptureChanged(hasCapture: Boolean) = base.onPointerCaptureChanged(hasCapture)

    /** Window coordinates throughout: what the window's callback receives, and what a view's visible rect is in. */
    private fun watch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downAt = e.eventTime }
            MotionEvent.ACTION_UP -> {
                val density = window.decorView.resources.displayMetrics.density
                val dx = ((e.x - downX) / density).toDouble()
                val dy = ((e.y - downY) / density).toDouble()
                if (!Taps.isTap(dx, dy, e.eventTime - downAt)) return
                val name = nameAt(window.decorView, e.x.toInt(), e.y.toInt()) ?: return
                onTap(name)
            }
        }
    }

    companion object {
        private val installed = WeakHashMap<Window, TapCapture>()

        /** Watch [activity]'s window, once. */
        fun install(activity: Activity, onTap: (String) -> Unit) {
            val w = activity.window ?: return
            if (installed.containsKey(w)) return
            val base = w.callback ?: return
            val capture = TapCapture(base, w, onTap)
            w.callback = capture
            installed[w] = capture
        }

        /**
         * The name of the control under a point (window coordinates), or
         * null: nothing pressable there, nothing that names it, or it is one
         * of the SDK's own views.
         */
        internal fun nameAt(root: View, x: Int, y: Int): String? {
            val hit = deepest(root, x, y) ?: return null
            var control: View? = hit
            while (control != null && !(control.isClickable || control.isLongClickable)) control = control.parent as? View
            if (control == null) return null
            var p: View? = control
            while (p != null) { if (isSdkView(p)) return null; p = p.parent as? View }
            // The control's own name, else the words on it (a label inside a pressable row).
            return name(control) ?: if (hit !== control) name(hit) else null
        }

        private fun deepest(v: View, x: Int, y: Int): View? {
            if (v.visibility != View.VISIBLE) return null
            val r = Rect()
            if (!v.getGlobalVisibleRect(r) || !r.contains(x, y)) return null
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) deepest(v.getChildAt(i), x, y)?.let { return it }
            }
            return v
        }

        private fun name(v: View): String? {
            val idName = if (v.id != View.NO_ID && v.id ushr 24 != 0) runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull() else null
            // A button's words name it; a text field is named by its hint, never by what the customer typed.
            val words = when (v) {
                is android.widget.EditText -> v.hint
                is TextView -> v.text
                else -> null
            }
            return Taps.nameFor(v.contentDescription, idName, words)
        }

        private fun isSdkView(v: View): Boolean =
            v is GraineAgentBarView || v is GraineWidgetView || v is GraineLauncherView || v is GraineChatView
    }
}
