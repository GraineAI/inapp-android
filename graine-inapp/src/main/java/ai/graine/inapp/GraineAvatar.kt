@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.Collections
import java.util.WeakHashMap

/** What a bridge asked of the SDK's call screen before the calls existed (GraineCallsHost.setShowsAvatar). */
internal object AvatarRequests {
    @Volatile var showsAvatar = false
}

/** Hears the face's state, on the main thread. */
fun interface GraineAvatarListener {
    fun onAvatarState(state: GraineAvatarState)
}

/** Told when the avatar views on screen change (GraineCalls, GraineVoice). Main thread. */
internal interface AvatarSurfaceListener {
    fun avatarSurfaceChanged()
}

/**
 * THE AGENT'S FACE (avatar) — what the app sees, and where it is drawn.
 *
 * When the agent has a face (switched on in the dashboard) and the app shows
 * a [GraineAvatarView], the face plays there during a call and carries the
 * agent's voice in sync with its lips; the call's own copy of the voice is
 * silenced only while it does, and comes back the moment the face stops. With
 * no avatar view on screen nothing is watched: the call is voice-only, exactly
 * as before.
 *
 *  - the assistant bar's calls: the face is drawn by the call's own engine,
 *    moved inside the avatar view while the face shows;
 *  - the SDK's native calls ([GraineCalls]): the face needs the WebRTC module
 *    (`ai.graine:inapp-android-rtc`) and a call on WebRTC; otherwise
 *    [state]'s reason is `unsupported` and the call stays voice-only.
 *
 * One call at a time, so one face at a time. Main thread.
 */
object GraineAvatar {
    private val main = Handler(Looper.getMainLooper())

    /** The face of the call in progress: off, available (show your avatar view) or live (it carries the voice). */
    @JvmStatic
    var state: GraineAvatarState = GraineAvatarState.OFF
        private set

    /** Whether an avatar view is on screen now. */
    @JvmStatic
    var isShown: Boolean = false
        private set

    private val listeners = mutableListOf<GraineAvatarListener>()
    private val slots: MutableSet<GraineAvatarView> = Collections.newSetFromMap(WeakHashMap())
    private val surfaces: MutableSet<AvatarSurfaceListener> = Collections.newSetFromMap(WeakHashMap())
    internal var activeSlot: GraineAvatarView? = null
        private set

    private var native: GraineAvatarState = GraineAvatarState.OFF
    private var nativeViewer: GraineAvatarViewer? = null
    private val engines = WeakHashMap<Any, Pair<GraineAvatarState, Int>>()
    private var lastOff: GraineAvatarState = GraineAvatarState.OFF
    private var tick = 0

    /** Hear [state] now and on every change; the returned function stops it. */
    @JvmStatic
    fun addListener(listener: GraineAvatarListener): () -> Unit {
        onMain {
            listeners += listener
            listener.onAvatarState(state)
        }
        return { onMain { listeners.remove(listener) } }
    }

    @JvmStatic
    fun removeListener(listener: GraineAvatarListener) = onMain { listeners.remove(listener) }

    /** The face's state as JSON — `{"phase":"off"|"available"|"live","reason":…}` — for a bridge. */
    @JvmStatic
    fun stateJson(): String = AvatarWire.stateJson(state)

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    // ── Slots ──

    internal fun register(slot: GraineAvatarView) { slots += slot; slotsChanged() }
    internal fun unregister(slot: GraineAvatarView) { slots -= slot; if (activeSlot === slot) activeSlot = null; slotsChanged() }

    /** A slot moved, appeared, disappeared or resized. */
    internal fun slotsChanged() {
        val visible = slots.filter { it.isOnScreen() }
        val next = if (visible.any { it === activeSlot }) activeSlot else visible.lastOrNull()
        val moved = next !== activeSlot
        activeSlot = next
        if (moved) placeNativeVideo()
        val shown = next != null
        val changed = shown != isShown
        isShown = shown
        if (changed || moved || shown) surfaces.toList().forEach { it.avatarSurfaceChanged() }
    }

    internal fun listen(l: AvatarSurfaceListener) { surfaces += l }
    internal fun stopListening(l: AvatarSurfaceListener) { surfaces -= l }

    // ── Sources ──

    /** The native call's face (GraineCalls). */
    internal fun nativeState(s: GraineAvatarState) = onMain {
        native = s
        if (!s.showsFace) lastOff = s
        recompute()
    }

    /** The viewer drawing the native call's face, while it is on screen; null otherwise. */
    internal fun nativeViewer(v: GraineAvatarViewer?) = onMain {
        nativeViewer = v
        placeNativeVideo()
    }

    /** An assistant-bar engine's face, as its page reported it. */
    internal fun engineState(engine: Any, s: GraineAvatarState) {
        engines[engine] = s to ++tick
        if (!s.showsFace) lastOff = s
        recompute()
    }

    internal fun engineGone(engine: Any) {
        engines.remove(engine)
        recompute()
    }

    private fun recompute() {
        val next = when {
            native.showsFace -> native
            else -> engines.values.filter { it.first.showsFace }.maxByOrNull { it.second }?.first ?: lastOff
        }
        if (next == state) return
        state = next
        listeners.toList().forEach { it.onAvatarState(next) }
        slots.toList().forEach { it.stateChanged(next) }
    }

    private fun placeNativeVideo() {
        val slot = activeSlot
        slots.filter { it !== slot }.forEach { it.host(null) }
        val v = nativeViewer
        slot?.host(if (v == null) null else runCatching { v.videoView(slot.context) }.getOrNull())
    }

    /** For the tests: everything back to the start. */
    internal fun reset() {
        state = GraineAvatarState.OFF
        isShown = false
        listeners.clear(); slots.clear(); surfaces.clear(); engines.clear()
        activeSlot = null; native = GraineAvatarState.OFF; nativeViewer = null; lastOff = GraineAvatarState.OFF
    }
}

/**
 * WHERE THE AGENT'S FACE IS DRAWN — place it in your layout (a square or
 * portrait area reads best) and the face plays here during calls whose agent
 * has one. Transparent when there is none: put your own picture of the agent
 * behind it. Hiding it, or taking it off screen, gives the voice back to the
 * call at once. One on screen at a time is used: the last one shown.
 *
 * The face is drawn INSIDE this view, beneath everything else (0.15.4): views
 * you add to it, or lay over it, draw over the face — your own captions,
 * buttons, the SDK's bar. [GraineAvatarStageView] is the SDK's own screen
 * drawn that way.
 */
class GraineAvatarView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    private var hosted: View? = null

    init {
        contentDescription = "Agent"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    /** On screen: attached, shown with every ancestor, visible, with an area. */
    internal fun isOnScreen(): Boolean = isAttachedToWindow && isShown && width > 0 && height > 0 && alpha > 0.01f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        GraineAvatar.register(this)
    }

    override fun onDetachedFromWindow() {
        // Before anything else: the voice engine inside this view (the face) goes straight back to its window,
        // so the call it carries is never left out of one. Its children are already detached here, which is
        // what lets it be moved without detaching it twice.
        GraineAvatar.unregister(this)
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        GraineAvatar.slotsChanged()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) GraineAvatar.slotsChanged()
    }

    override fun setAlpha(alpha: Float) {
        val was = this.alpha > 0.01f
        super.setAlpha(alpha)
        if (was != alpha > 0.01f && isAttachedToWindow) GraineAvatar.slotsChanged()
    }

    /** The native face's video view, or null — at the bottom, so whatever the app put in this view draws over the face. */
    internal fun host(view: View?) {
        if (hosted === view) return
        hosted?.let { if (it.parent === this) removeView(it) }
        hosted = view
        if (view == null) return
        (view.parent as? ViewGroup)?.removeView(view)
        addView(view, 0, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    internal fun stateChanged(state: GraineAvatarState) {
        if (android.os.Build.VERSION.SDK_INT >= 30) stateDescription = if (state.phase == GraineAvatarState.Phase.LIVE) "Speaking" else null
    }
}
