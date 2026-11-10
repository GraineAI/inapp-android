package ai.graine.inapp

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import java.util.concurrent.Executor

/**
 * THE VOICE BAR'S CALL ON THE LOUDSPEAKER, OR NOT ([GraineVoice.speaker],
 * [GraineVoice.setSpeaker]). The web view carries the call and sets the
 * phone's audio itself — communication mode, the loudspeaker unless a headset
 * is connected — and puts it back as the call closes. This only watches where
 * the call is heard while it is on, and when the customer chooses, routes it
 * as a native call's route does ([CallRouting]): on, the loudspeaker; off, a
 * headset if one is connected (Bluetooth first), else the earpiece. What it
 * set, it gives back when the call ends; what it did not set, it never
 * touches. Never throws: a phone that refuses leaves the route where it was,
 * and [onSpeaker] says where that is. Main thread.
 */
internal class VoiceRoute(
    context: Context,
    private val main: MainThread,
    private val onSpeaker: (Boolean) -> Unit,
) {
    private val am = runCatching { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }.getOrNull()
    private var watching = false
    /** The customer's choice for this call; null: the web view's own route. */
    private var choice: Boolean? = null
    /** This call named a device (or switched the loudspeaker), so it gives that back. */
    private var routed = false

    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { main.post(Runnable { changed() }) }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { main.post(Runnable { changed() }) }
    }
    private var communication: Any? = null
    /** The web view sets its route a moment after the call connects: read again then. */
    private val settle = Runnable { read() }

    /** The call is on: watch where it is heard, and route it as the customer chose — before it was on, too. */
    fun start(chosen: Boolean?) {
        val am = am ?: return
        choice = chosen
        if (!watching) {
            watching = true
            runCatching { am.registerAudioDeviceCallback(devices, null) }
            if (Build.VERSION.SDK_INT >= 31) {
                runCatching {
                    val l = AudioManager.OnCommunicationDeviceChangedListener { main.post(Runnable { read() }) }
                    am.addOnCommunicationDeviceChangedListener(Executor { it.run() }, l)
                    communication = l
                }
            }
        }
        if (chosen != null) route(chosen) else read()
        main.removeCallbacks(settle)
        main.postDelayed(settle, SETTLE_MS)
    }

    /** The customer chose: the loudspeaker on, or off. */
    fun choose(on: Boolean) {
        choice = on
        if (watching) route(on)
    }

    /** The call is over: stop watching, and give back only what this routed. */
    fun stop() {
        val am = am ?: return
        main.removeCallbacks(settle)
        if (watching) {
            watching = false
            runCatching { am.unregisterAudioDeviceCallback(devices) }
            if (Build.VERSION.SDK_INT >= 31) {
                (communication as? AudioManager.OnCommunicationDeviceChangedListener)?.let { l ->
                    runCatching { am.removeOnCommunicationDeviceChangedListener(l) }
                }
                communication = null
            }
        }
        if (routed) {
            routed = false
            if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
            else @Suppress("DEPRECATION") runCatching { am.isSpeakerphoneOn = false }
        }
        choice = null
    }

    /** A device came or went: the customer's choice is made again on what is there now; with none, only read. */
    private fun changed() {
        if (!watching) return
        val c = choice
        if (c != null) route(c) else read()
    }

    private fun route(on: Boolean) {
        val am = am ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val available = am.availableCommunicationDevices
                val type = CallRouting.choose(available.map { it.type }, on)
                val device = type?.let { t -> available.firstOrNull { it.type == t } }
                if (device != null && am.communicationDevice?.id != device.id) {
                    am.setCommunicationDevice(device)
                    routed = true
                }
            } else {
                @Suppress("DEPRECATION")
                if (am.isSpeakerphoneOn != on) {
                    am.isSpeakerphoneOn = on
                    routed = true
                }
            }
        } catch (e: Exception) {
            CallLog.error("the voice call's route could not be set", e)
        }
        read()
    }

    /** Where the call is heard now. */
    private fun read() {
        val am = am ?: return
        val on = runCatching {
            if (Build.VERSION.SDK_INT >= 31) am.communicationDevice?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            else @Suppress("DEPRECATION") am.isSpeakerphoneOn
        }.getOrNull() ?: return
        onSpeaker(on)
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
