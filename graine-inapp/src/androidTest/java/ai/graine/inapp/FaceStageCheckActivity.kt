package ai.graine.inapp

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * THE FACE STAGE ON A DEVICE — the test APK only. Puts a [GraineAvatarStageView]
 * on screen, speaks to the voice engine as its page would (the face available,
 * live, off), moves the stage away and back, hides and shows it, and checks
 * after each step where the engine's view is — inside the stage's avatar view,
 * or home in the window — and that it was never left out of a window. Each
 * result is a `FACECHECK` line in logcat; the last screen is a live call drawn
 * over the face (a caption, a component, the buttons), for a screenshot.
 *
 *   adb shell am start -S -n ai.graine.inapp.test/ai.graine.inapp.FaceStageCheckActivity [--es key <publishable key>] [--es until off]
 *   adb logcat -d -s Graine:* FaceStageCheck:*
 *
 * No call is placed: the engine page need not load at all for this.
 */
class FaceStageCheckActivity : Activity() {
    private lateinit var graine: GraineInApp
    private lateinit var root: FrameLayout
    private lateinit var stage: GraineAvatarStageView
    private var failures = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra("key") ?: "pk_face_check"
        graine = runCatching { GraineInApp.primary() }.getOrNull() ?: GraineInApp.init(application, GraineConfig("https://www.graine.ai", key))
        root = FrameLayout(this)
        stage = GraineAvatarStageView(this).apply {
            graine = this@FaceStageCheckActivity.graine
            name = "Asha"
            role = "AI care assistant"
            onChat = { line("chat button tapped") }
        }
        root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // Something of the app's own over the face, to prove it draws over it: a label in the stage's top corner.
        val own = TextView(this).apply { text = "app view over the face"; setTextColor(0xFFFFD54F.toInt()); textSize = 12f }
        root.addView(own, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 400; leftMargin = 60 })
        setContentView(root)
        // The steps, each after the layout before it has run.
        // `--es until off` stops before the live call: the stage idle, its backdrop and Call button, for a screenshot.
        val all = listOf(::available, ::removed, ::added, ::hidden, ::shown, ::off, ::liveCall)
        steps = if (intent.getStringExtra("until") == "off") all.dropLast(1) else all
        root.postDelayed({ next() }, 1500)
    }

    private var steps: List<() -> Unit> = emptyList()
    private var at = 0

    private fun next() {
        if (at >= steps.size) {
            line(if (failures == 0) "ALL PASSED" else "$failures FAILED")
            return
        }
        steps[at++]()
        root.postDelayed({ next() }, 700)
    }

    private val voice get() = graine.voice

    private fun check(what: String, ok: Boolean) {
        if (!ok) failures++
        line("${if (ok) "PASS" else "FAIL"} $what (placement=${voice.facePlacement}, inWindow=${voice.engineInWindow}, parent=${voice.engineParent?.javaClass?.simpleName})")
    }

    private fun available() {
        check("the engine was mounted by the stage", voice.attached)
        voice.faceFromPage("available" to null)
        check("a face available: the engine is inside the stage's avatar view", voice.facePlacement == FacePlacement.SLOT && voice.engineParent === stage.face)
        check("…and in a window", voice.engineInWindow)
        check("…at the bottom of the avatar view", findEngineBox() != null && stage.face.indexOfChild(findEngineBox()) == 0)
    }

    private fun findEngineBox(): View? = (0 until stage.face.childCount).map { stage.face.getChildAt(it) }.firstOrNull { it is EngineFrame }

    private fun removed() {
        root.removeView(stage)
        // In the same turn: the avatar view's detach handed the engine back.
        check("the stage taken away: the engine went home at once", voice.facePlacement == FacePlacement.HOME)
        check("…and never left out of a window", voice.engineInWindow)
    }

    private fun added() {
        root.addView(stage, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.post {
            check("the stage back: the engine inside it again", voice.facePlacement == FacePlacement.SLOT && voice.engineParent === stage.face)
            check("…in a window", voice.engineInWindow)
        }
    }

    private fun hidden() {
        stage.visibility = View.GONE
        check("the stage hidden: the engine home (never GONE with it)", voice.facePlacement == FacePlacement.HOME && voice.engineInWindow)
    }

    private fun shown() {
        stage.visibility = View.VISIBLE
        root.post { check("shown again: inside it", voice.facePlacement == FacePlacement.SLOT && voice.engineInWindow) }
    }

    private fun off() {
        voice.faceFromPage("off" to "ended")
        check("the face off: the engine home", voice.facePlacement == FacePlacement.HOME && voice.engineInWindow)
    }

    private fun liveCall() {
        voice.faceFromPage("live" to null)
        voice.handle(PageMessage.Call(connected = true, connecting = false))
        voice.handle(PageMessage.Caption(Role.AGENT, "Hi Riya, I can book your follow-up visit. Does tomorrow at 10 work for you?", live = false))
        voice.handle(
            PageMessage.Widget(
                JSONObject(
                    """{"type":"widget","widget_id":"visit-1","kind":"info_card","data":{"title":"Follow-up visit","summary":"Tomorrow, 10:00 AM"},"fallback_text":"Tomorrow at 10"}""",
                ),
            ),
        )
        root.postDelayed({
            val s = graine.stage.state
            check("a live call over the face: LIVE, the clock, the line, the component, the buttons",
                s.live && s.startedAt != null && s.caption != null && s.component != null &&
                    s.controls == listOf(GraineStageControl.MUTE, GraineStageControl.CHAT, GraineStageControl.END, GraineStageControl.SPEAKER))
            check("…with the face inside the stage", voice.facePlacement == FacePlacement.SLOT)
            line("stage ${graine.stage.stateJson()} voice=${voice.status.wire} connectivity=${voice.connectivity}")
        }, 300)
    }

    private fun line(s: String) {
        Log.i(TAG, "FACECHECK $s")
    }

    private companion object {
        const val TAG = "FaceStageCheck"
    }
}
