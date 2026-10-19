@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import android.content.Context
import android.os.Build

/*
 * THE PLUG BETWEEN THE SDK AND ITS WEBRTC MODULE.
 *
 * The SDK has no dependencies, and WebRTC is a large native library, so the
 * library lives in an opt-in module (ai.graine:inapp-android-rtc). The SDK
 * finds it at run time by one class name and talks to it only through the
 * interfaces below — the peer connection, reduced to what a call needs. An app
 * that adds the module changes nothing else; an app without it has none of the
 * native code, and its calls go over the WebSocket as they always did.
 *
 * Public only because the module is another library; marked so that a Kotlin
 * app cannot call into it by accident. Nothing here is an API for apps.
 */

/** The SDK's plug for its WebRTC module. Not an API for apps: it may change in any release. */
@RequiresOptIn(message = "The plug between Graine's SDK and its WebRTC module. Apps never use it.", level = RequiresOptIn.Level.ERROR)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS)
annotation class GraineRtcInternal

/**
 * The module's entry point: `ai.graine.inapp.rtc.GraineRtc`, made with no
 * arguments. One per process.
 */
@GraineRtcInternal
interface GraineRtcMedia {
    /**
     * A peer connection for one call, with the microphone and the speaker
     * behind it, on [iceServers], its candidate pool gathering at once — the
     * microphone itself joins with the first offer ([GraineRtcPeer.createOffer]).
     * Throws when the device cannot run it (its native library did not load) —
     * the call then goes over the WebSocket. Never on the main thread.
     */
    fun createPeer(context: Context, iceServers: List<GraineRtcIceServer>, listener: GraineRtcPeer.Listener): GraineRtcPeer
}

/** One ICE server as the call's credentials name it. */
@GraineRtcInternal
class GraineRtcIceServer(val urls: List<String>, val username: String?, val credential: String?) {
    override fun toString(): String = "GraineRtcIceServer(urls=$urls)"
}

/**
 * ONE CALL'S PEER CONNECTION: one audio transceiver, sending and receiving.
 * Callbacks arrive on the library's own threads.
 */
@GraineRtcInternal
interface GraineRtcPeer {
    interface Listener {
        /** A local ICE candidate, as an SDP attribute value (`candidate:… typ srflx …`). */
        fun onCandidate(candidate: String)
        /** Gathering finished: no more candidates are coming. */
        fun onGatheringComplete()
        /** The ICE connection's state, in the standard words: new, checking, connected, completed, disconnected, failed, closed. */
        fun onIceState(state: String)
        /** The microphone or the speaker failed under the call. */
        fun onAudioError(message: String)
    }

    /**
     * Create an offer (an ICE restart when [iceRestart]) and set it as the
     * local description: gathering starts, the candidate pool's first. The
     * SDK makes the first one at the dial, never before: the microphone's
     * track joins the connection with it, and until then nothing is set up to
     * send and nothing records.
     */
    fun createOffer(iceRestart: Boolean, done: (error: String?) -> Unit)

    /** The local description as it stands — every candidate gathered so far inside it — or null before there is one. */
    fun localDescription(): String?

    /** Apply the far side's answer. */
    fun setRemoteAnswer(sdp: String, done: (error: String?) -> Unit)

    /** Answer the far side's offer (a re-INVITE): set it, answer, set the answer; [done] hears the answer's SDP. */
    fun answerOffer(sdp: String, done: (answer: String?, error: String?) -> Unit)

    /** Send the microphone, or silence in its place. */
    fun setMicrophoneEnabled(enabled: Boolean)

    /** Record and play, or stop both while the phone has the audio (a hold). */
    fun setAudioActive(recording: Boolean, playout: Boolean)

    /** The call's audio statistics now. */
    fun stats(done: (GraineRtcStats?) -> Unit)

    /** Hang up the media and release the microphone and the speaker. Safe to call twice. */
    fun close()
}

/** What the peer connection measured about the call's audio. */
@GraineRtcInternal
class GraineRtcStats @JvmOverloads constructor(
    /** Received from the far side. */
    val bytesReceived: Long,
    val packetsReceived: Long,
    val packetsLost: Long,
    /** The far side's audio level now, 0 to 1. */
    val audioLevel: Double?,
    /** Its energy so far, and over how long: two readings make the level between them. */
    val totalAudioEnergy: Double?,
    val totalSamplesDuration: Double?,
    val jitterSeconds: Double?,
    val bytesSent: Long,
    val roundTripSeconds: Double?,
    /** `audio/PCMU`, `audio/opus`. */
    val codec: String?,
    /** host, srflx, prflx or relay: how this side reaches the far side. */
    val localCandidateType: String?,
    /** The codec's clock rate — 8000, 48000 — when the report says. */
    val clockRate: Int? = null,
)

/** Finds the WebRTC module, once. */
internal object RtcModule {
    /** The module's stable entry class. Kept by the module's own R8 rules. */
    const val ENTRY = "ai.graine.inapp.rtc.GraineRtc"

    @Volatile private var looked = false
    @Volatile private var found: GraineRtcMedia? = null

    /** For the tests: a module that is not one. */
    @Volatile var override: GraineRtcMedia? = null

    /** For the tests, whose Build.VERSION reads 0: whether this phone runs the module. */
    @Volatile var runsHereOverride: Boolean? = null

    /** Whether this phone runs the module: the WebRTC library's floor is the SDK's own (Android 7), so every phone the SDK installs on. */
    fun runsHere(): Boolean = runsHereOverride ?: (Build.VERSION.SDK_INT >= CallTransportChoice.MIN_SDK)

    /** Whether the app added the module — for code that only needs to know. Never throws. */
    fun added(): Boolean = media() != null

    /** The module, or null when the app did not add it. Cheap after the first call; never throws. */
    fun media(): GraineRtcMedia? {
        override?.let { return it }
        if (looked) return found
        synchronized(this) {
            if (!looked) {
                found = try {
                    Class.forName(ENTRY).getDeclaredConstructor().newInstance() as? GraineRtcMedia
                } catch (_: Throwable) {
                    null
                }
                looked = true
            }
        }
        return found
    }
}
