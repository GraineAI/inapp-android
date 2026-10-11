package ai.graine.inapp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * THE PHONE'S MICROPHONE AND SPEAKER FOR A NATIVE CALL.
 *
 * Microphone: AudioRecord on VOICE_COMMUNICATION (the source the platform
 * runs its echo canceller and noise suppression on), 16 kHz mono PCM16, read
 * 20 ms at a time on its own thread, plus AcousticEchoCanceler where the phone
 * has one. The echo guard in the session runs as well, not instead. A
 * recorder that dies mid-call is rebuilt; one that cannot be, or that fails
 * again soon after ([MicrophoneRecovery]), is reported, and the call ends.
 *
 * Speaker: one AudioTrack for the call (voice communication, speech, stream
 * mode) at 24 kHz — the agent's rate in production; any other rate is
 * resampled first. A writer thread hands it what [PlayoutBuffer] releases
 * (150 ms cushion, 120 ms drain) and, between turns, silence: a stream track
 * that underruns long enough is disabled, and a restarted one waits for a full
 * buffer before it plays — which would strand a short reply and leave
 * [bufferedSeconds] above zero. [DeviceLedger] knows which frames on the track
 * are the agent's, so [bufferedSeconds] is exact and lands at zero.
 */
internal class AndroidCallAudio : CallAudioIO {
    // ── the microphone ─────────────────────────────────────────────────────
    /** A recording microphone: the recorder and the echo canceller on its audio session. */
    private class Mic(val record: AudioRecord, val echoCanceller: AcousticEchoCanceler?) {
        fun release() {
            runCatching { record.stop() }
            runCatching { echoCanceller?.release() }
            runCatching { record.release() }
        }
    }

    @Volatile private var capturing = false
    /** Guards [mic]: the capture thread swaps it when it rebuilds, [stop] takes it. */
    private val micLock = Any()
    private var mic: Mic? = null
    private var captureThread: Thread? = null
    private val recovery = MicrophoneRecovery()

    override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {
        check(!stopped) { "this call's audio was already stopped" }
        val first = openMicrophone()
        synchronized(micLock) { mic = first }
        capturing = true
        captureThread = Thread({ capture(first.record, onFrame, onFailed) }, "graine-call-mic").apply { start() }
        startSpeaker()
    }

    /** A microphone that is recording, or an exception that says why there is none. */
    @SuppressLint("MissingPermission")   // the call never starts without RECORD_AUDIO; GraineCalls checks first
    private fun openMicrophone(): Mic {
        val rate = AgentAudio.CAPTURE_RATE
        val frame = AgentAudio.CAPTURE_FRAME_SAMPLES
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) throw IllegalStateException("This phone cannot record 16 kHz mono audio")
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, Math.max(min, frame * 2 * 8))
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            throw IllegalStateException("The microphone could not be opened (is RECORD_AUDIO granted?)")
        }
        val echoCanceller = if (!AcousticEchoCanceler.isAvailable()) null
            else runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            echoCanceller?.release()
            r.release()
            throw IllegalStateException("The microphone is in use by another app")
        }
        return Mic(r, echoCanceller)
    }

    /** The capture thread: a 20 ms frame at a time until the call stops it. */
    private fun capture(first: AudioRecord, onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val frame = AgentAudio.CAPTURE_FRAME_SAMPLES
        val buf = ShortArray(frame)
        var r = first
        while (capturing) {
            var n = 0
            var error = 0
            while (n < frame && capturing) {
                val got = r.read(buf, n, frame - n)
                if (got < 0) { error = got; break }
                n += got
            }
            if (n == frame) { onFrame(buf.copyOf()); continue }
            if (!capturing) return
            // The recorder died under the call — the audio server restarted, or the route changed
            // under it. Left alone, the call goes on with the customer silent; rebuilt, it usually
            // records again. One that cannot be rebuilt, or fails again soon after, is not coming back.
            CallLog.error("microphone read failed ($error)")
            r = (if (recovery.failed(clock())) rebuildMicrophone() else null) ?: run {
                if (capturing) { capturing = false; onFailed() }
                return
            }
        }
    }

    /** The dead recorder released and a new one recording — or null: it could not be, or the call ended meanwhile. */
    private fun rebuildMicrophone(): AudioRecord? {
        // A moment for whatever took it down (an audio server restarting, a route still moving) to settle.
        val until = clock() + MIC_REBUILD_AFTER_MS
        while (capturing && clock() < until) {
            try { Thread.sleep(10) } catch (e: InterruptedException) { return null }
        }
        synchronized(micLock) {
            // Under the lock: stop() either took the old one already, or will find the new one.
            if (!capturing) return null
            mic?.release()
            mic = null
            val fresh = try {
                openMicrophone()
            } catch (e: Exception) {
                CallLog.error("the microphone could not be rebuilt", e)
                return null
            }
            mic = fresh
            CallLog.debug("microphone rebuilt")
            return fresh.record
        }
    }

    // ── the speaker ────────────────────────────────────────────────────────
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private val player = PlayoutBuffer(OUT_RATE, clock)
    private val ledger = DeviceLedger()
    private val resampler = LinearResampler(OUT_RATE)
    private var track: AudioTrack? = null
    private var trackFrames = 0
    private var writer: Thread? = null
    @Volatile private var speaking = false
    private val silence = ShortArray(OUT_RATE / 50)   // 20 ms

    private fun startSpeaker() = lock.withLock { if (track == null) startSpeakerLocked() }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(OUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bytes = Math.max(min, OUT_RATE * 2 / 10)   // at least 100 ms
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(OUT_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bytes)
            .build()
        trackFrames = t.bufferSizeInFrames.takeIf { it > 0 } ?: (bytes / 2)
        ledger.flush()
        t.play()
        prime(t)
        return t
    }

    /** A started track plays only once its buffer has filled: fill it with silence, so the agent's first word is not held back. */
    private fun prime(t: AudioTrack) {
        var left = trackFrames
        while (left > 0) {
            val n = t.write(silence, 0, Math.min(silence.size, left), AudioTrack.WRITE_NON_BLOCKING)
            if (n <= 0) break
            ledger.wroteSilence(n)
            left -= n
        }
    }

    private fun head(t: AudioTrack): Long = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL

    private fun writeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val lowWater = OUT_RATE / 25   // keep at least 40 ms on the device
        while (speaking) {
            lock.withLock {
                val t = track ?: return
                val h = head(t)
                var wrote = false
                val chunk = player.take(ledger.agentUnplayed(h))
                if (chunk != null) {
                    val n = t.write(chunk, 0, chunk.size, AudioTrack.WRITE_NON_BLOCKING)
                    when {
                        n == AudioTrack.ERROR_DEAD_OBJECT -> { player.putBack(chunk); recover() }
                        n < 0 -> CallLog.error("speaker write failed ($n)")
                        else -> {
                            ledger.wroteAgent(n)
                            if (n < chunk.size) player.putBack(chunk.copyOfRange(n, chunk.size))
                            wrote = n > 0
                        }
                    }
                } else if (ledger.depth(h) < lowWater) {
                    val n = t.write(silence, 0, silence.size, AudioTrack.WRITE_NON_BLOCKING)
                    if (n > 0) ledger.wroteSilence(n)
                }
                if (!wrote && speaking) {
                    val due = player.dueInMs()
                    wake.await(if (due != null) Math.max(1L, Math.min(due, 10L)) else 10L, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    /** The audio server restarted under the track (a route change, a crash): build a new one; what was queued plays on it. */
    private fun recover() {
        CallLog.debug("speaker track died; rebuilding")
        runCatching { track?.release() }
        track = runCatching { newTrack() }.getOrNull()
        if (track == null) speaking = false
    }

    override fun play(pcm: ShortArray, sampleRate: Int) {
        lock.withLock {
            if (stopped) return
            if (track == null) startSpeakerLocked()
            val out = resampler.process(pcm, sampleRate)
            player.push(out, track?.let { ledger.agentUnplayed(head(it)) } ?: 0)
            wake.signalAll()
        }
    }

    private fun startSpeakerLocked() {
        if (stopped) return
        track = runCatching { newTrack() }.getOrElse { CallLog.error("speaker could not start", it); null } ?: return
        speaking = true
        writer = Thread({ writeLoop() }, "graine-call-speaker").apply { start() }
    }

    override fun clear() {
        lock.withLock {
            player.clear()
            resampler.reset()
            val t = track ?: return
            // Paused, flushed (the play head starts again at zero), restarted, re-primed.
            runCatching { t.pause(); t.flush(); ledger.flush(); t.play(); prime(t) }
            wake.signalAll()
        }
    }

    override fun bufferedSeconds(): Double = lock.withLock {
        val t = track
        player.bufferedSeconds(if (t != null) ledger.agentUnplayed(head(t)) else 0)
    }

    /** Once stopped, nothing restarts it: a frame decoded after the hang-up must not open a new speaker. */
    @Volatile private var stopped = false

    override fun stop() {
        stopped = true
        capturing = false
        // Under the lock, so a rebuild in progress finishes first and the recorder it made is the one released here.
        val m = synchronized(micLock) { mic.also { mic = null } }
        // Stopped before the join: a blocking read returns, and the capture thread sees the call is over.
        m?.let { runCatching { it.record.stop() } }
        runCatching { captureThread?.join(500) }
        captureThread = null
        m?.release()
        val w: Thread?
        lock.withLock {
            speaking = false
            w = writer
            writer = null
            wake.signalAll()
        }
        runCatching { w?.join(500) }
        lock.withLock {
            track?.let { t -> runCatching { t.pause(); t.flush(); t.stop() }; runCatching { t.release() } }
            track = null
            player.clear()
            ledger.flush()
        }
    }

    companion object {
        /** The speaker's rate for the whole call: what the agent sends in production. */
        const val OUT_RATE = AgentAudio.PCM_RATE
        /** How long a dead microphone is left before it is rebuilt. */
        const val MIC_REBUILD_AFTER_MS = 200L
    }
}

/**
 * WHEN A MICROPHONE THAT DIED IS REBUILT, AND WHEN THE CALL ENDS INSTEAD.
 * Pure (the clock is handed in), so the rule is proven on the JVM.
 *
 * A recorder can die under a call and a new one usually records again, so
 * every failure is answered with one rebuild. A failure within [windowMs] of
 * the one before means the rebuilt microphone did not hold: it is not coming
 * back, and the call ends rather than go on with the customer silent. Two
 * failures far apart are two separate events, each worth its own rebuild.
 */
internal class MicrophoneRecovery(private val windowMs: Long = WINDOW_MS) {
    private var lastFailureAt: Long? = null

    /** The microphone failed at [now] (ms): true to rebuild it, false to give up on it. */
    fun failed(now: Long): Boolean {
        val last = lastFailureAt
        lastFailureAt = now
        return last == null || now - last >= windowMs
    }

    companion object {
        /** "Soon after": a microphone that fails again within this of its last failure is gone. */
        const val WINDOW_MS = 5_000L
    }
}

/**
 * WHERE A CALL IS HEARD — the choice a phone call makes, as a rule. Pure (the
 * devices are handed in), so every case is proven on the JVM; [AudioRoute]
 * applies it.
 *
 *   loudspeaker chosen  the built-in speaker
 *   otherwise           a Bluetooth headset (LE audio, then hands-free, then a
 *                       hearing aid), else a wired or USB headset (or wired
 *                       headphones, or a USB audio device), else the earpiece
 *
 * So turning the loudspeaker off brings the call back to the headset the
 * customer is wearing, not to the earpiece. The choice is made again whenever
 * a device comes or goes: a headset switched on mid-call takes the call, and
 * one switched off hands it to the next in line.
 */
internal object CallRouting {
    /** Bluetooth headsets a call can use, in the order it prefers them. */
    private val BLUETOOTH = intArrayOf(
        AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
    )
    /**
     * Then what is plugged in. A USB audio device that does not call itself a
     * headset (many USB-C earphone adapters) comes last: before Android 12 the
     * platform routes a call to it over the earpiece by itself, and naming the
     * earpiece over it from 12 on would split the two.
     */
    private val WIRED = intArrayOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )
    /** Everything for the ear, best first: the earpiece last. */
    private val FOR_THE_EAR = BLUETOOTH + WIRED + AudioDeviceInfo.TYPE_BUILTIN_EARPIECE

    /**
     * The AudioDeviceInfo type the call should be heard on, out of
     * [available] (Android 12+: the phone's communication devices; before
     * that, its outputs), with the loudspeaker [speaker] chosen or not. Null
     * when none of them is there: the phone's own default.
     */
    fun choose(available: Collection<Int>, speaker: Boolean): Int? =
        if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER.takeIf { it in available }
        else FOR_THE_EAR.firstOrNull { it in available }
}

/** What the phone did to a call's audio, for the call to act on. */
internal enum class CallInterruption {
    /** A phone call or another app took the audio: nothing is sent or played until it comes back. */
    HELD,
    /** The audio came back. */
    RESUMED,
    /** The customer answered a phone call during the hold: the agent's call ends, as iOS's End & Accept ends it. */
    PHONE_CALL,
}

/**
 * WHAT A CHANGE OF AUDIO FOCUS MEANS FOR A CALL — the rule, pure, so every
 * case is proven on the JVM; [AudioRoute] applies it.
 *
 *   lost, for good or for now   hold: a phone call ringing, another app's call or player
 *   lost, but may duck          nothing: a notification sound — a voice does not duck
 *   gained                      resume
 *
 * An agent cannot be put on hold the way a person on a phone call can, so a
 * phone call answered during the hold ends the agent's call:
 * [phoneCallActive] is the phone's own call in progress.
 */
internal object CallFocus {
    enum class Act { HOLD, RESUME, NONE }

    fun act(change: Int): Act = when (change) {
        AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> Act.HOLD
        AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
        AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK -> Act.RESUME
        else -> Act.NONE
    }

    /** The audio mode a phone call in progress puts the phone in. */
    fun phoneCallActive(mode: Int): Boolean = mode == AudioManager.MODE_IN_CALL
}

/**
 * THE CALL'S PLACE IN THE PHONE'S AUDIO: communication mode for the call's
 * length, transient focus (other apps duck or pause), and the device it is
 * heard on — [CallRouting]'s choice, made again whenever a device comes or
 * goes. Released exactly once, with the phone's routing given back.
 *
 * Android 12+ names the device (setCommunicationDevice). Before that there
 * are two switches: the loudspeaker, and a Bluetooth headset's voice link
 * (SCO), which the call opens when its choice is a hands-free headset; with
 * both off, the platform itself prefers a wired headset or a hearing aid to
 * the earpiece. Main thread only, the device callback included.
 */
internal class AudioRoute(context: Context, private val onInterruption: (CallInterruption) -> Unit = {}) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val main = Handler(Looper.getMainLooper())
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        CallLog.debug("audio focus $change")
        main.post { focusChanged(change) }
    }
    /** The phone took the call's audio and has not given it back. */
    private var onHold = false
    /** The phone call that ends this call was reported: once. */
    private var phoneCallReported = false
    private var held = false
    /** The customer chose the loudspeaker. */
    private var speaker = false
    /** Below Android 12: this call opened the Bluetooth voice link, so it closes it. */
    private var scoStarted = false
    /** A device came or went — a headset switched on or off, plugged or pulled: the choice is made again. */
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = reroute()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = reroute()
    }

    fun acquire(speaker: Boolean) {
        if (held) return
        held = true
        this.speaker = speaker
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }
        reroute()
        // For the call's length. A null handler is the main thread, where every other call into this class is made.
        runCatching { am.registerAudioDeviceCallback(devices, null) }
    }

    /** The loudspeaker on, or off: back to the device the call prefers — a headset if one is connected, else the earpiece. */
    fun setSpeaker(on: Boolean) {
        if (!held) return
        speaker = on
        reroute()
    }

    /**
     * [CallRouting]'s choice, applied — again on every device that comes or
     * goes, so it changes nothing that is already right. Never throws: it
     * runs from the platform's callback, where a throw would take the app down.
     */
    private fun reroute() {
        if (!held) return
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val available = am.availableCommunicationDevices
                val type = CallRouting.choose(available.map { it.type }, speaker)
                val device = type?.let { t -> available.firstOrNull { it.type == t } }
                if (device == null) am.clearCommunicationDevice()
                else if (am.communicationDevice?.id != device.id) am.setCommunicationDevice(device)
            } else {
                setSwitches()
            }
        } catch (e: Exception) {
            CallLog.error("the call's audio route could not be set", e)
        }
    }

    /** Below Android 12: the loudspeaker switch and the Bluetooth voice link, set to [CallRouting]'s choice. */
    @Suppress("DEPRECATION")
    private fun setSwitches() {
        val type = CallRouting.choose(am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }, speaker)
        val sco = type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && am.isBluetoothScoAvailableOffCall
        // One switch off before the other goes on: both claim the call's route, and the last one set wins.
        if (!sco) stopSco()
        am.isSpeakerphoneOn = type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        if (sco) startSco()
    }

    /** Below Android 12: open the hands-free headset's voice link — once; the platform routes the call to it. */
    @Suppress("DEPRECATION")
    private fun startSco() {
        if (scoStarted) return
        scoStarted = true
        runCatching { am.startBluetoothSco() }
        runCatching { am.isBluetoothScoOn = true }
    }

    @Suppress("DEPRECATION")
    private fun stopSco() {
        if (!scoStarted) return
        scoStarted = false
        runCatching { am.stopBluetoothSco() }
        runCatching { am.isBluetoothScoOn = false }
    }

    /** [CallFocus]'s rule, applied on the main thread. */
    private fun focusChanged(change: Int) {
        if (!held) return
        when (CallFocus.act(change)) {
            CallFocus.Act.HOLD -> if (!onHold) {
                onHold = true
                onInterruption(CallInterruption.HELD)
                main.postDelayed(watchForPhoneCall, PHONE_CALL_CHECK_MS)
            }
            CallFocus.Act.RESUME -> if (onHold) {
                onHold = false
                main.removeCallbacks(watchForPhoneCall)
                // A phone call in between put the phone in its own mode, and may have moved the route.
                runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
                reroute()
                onInterruption(CallInterruption.RESUMED)
            }
            CallFocus.Act.NONE -> Unit
        }
    }

    /** During a hold, and only then: has the customer answered a phone call? Looked at once a second. */
    private val watchForPhoneCall = object : Runnable {
        override fun run() {
            if (!held || !onHold || phoneCallReported) return
            if (CallFocus.phoneCallActive(am.mode)) {
                phoneCallReported = true
                onInterruption(CallInterruption.PHONE_CALL)
            } else {
                main.postDelayed(this, PHONE_CALL_CHECK_MS)
            }
        }
    }

    fun release() {
        if (!held) return
        held = false
        main.removeCallbacks(watchForPhoneCall)
        onHold = false
        runCatching { am.unregisterAudioDeviceCallback(devices) }
        // The phone's routing given back: no device named, no voice link open, no loudspeaker.
        if (Build.VERSION.SDK_INT >= 31) {
            runCatching { am.clearCommunicationDevice() }
        } else {
            stopSco()
            @Suppress("DEPRECATION")
            runCatching { am.isSpeakerphoneOn = false }
        }
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
        focusRequest = null
        am.mode = AudioManager.MODE_NORMAL
    }

    private companion object {
        const val PHONE_CALL_CHECK_MS = 1_000L
    }
}
