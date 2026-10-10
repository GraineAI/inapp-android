package ai.graine.inapp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
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
 * has one. The echo guard in the session runs as well, not instead.
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
    @Volatile private var capturing = false
    private var record: AudioRecord? = null
    private var echoCanceller: AcousticEchoCanceler? = null
    private var captureThread: Thread? = null

    @SuppressLint("MissingPermission")   // the call never starts without RECORD_AUDIO; GraineCalls checks first
    override fun startCapture(onFrame: (ShortArray) -> Unit) {
        check(!stopped) { "this call's audio was already stopped" }
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
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceller = runCatching { AcousticEchoCanceler.create(r.audioSessionId)?.apply { enabled = true } }.getOrNull()
        }
        r.startRecording()
        if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            echoCanceller?.release(); echoCanceller = null
            r.release()
            throw IllegalStateException("The microphone is in use by another app")
        }
        record = r
        capturing = true
        captureThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(frame)
            while (capturing) {
                var n = 0
                while (n < frame && capturing) {
                    val got = r.read(buf, n, frame - n)
                    if (got < 0) { CallLog.error("microphone read failed ($got)"); capturing = false; break }
                    n += got
                }
                if (n == frame) onFrame(buf.copyOf())
            }
        }, "graine-call-mic").apply { start() }
        startSpeaker()
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
        val r = record
        record = null
        if (r != null) {
            runCatching { r.stop() }
            runCatching { captureThread?.join(500) }
            runCatching { echoCanceller?.release() }
            echoCanceller = null
            runCatching { r.release() }
        }
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
    }
}

/**
 * THE CALL'S PLACE IN THE PHONE'S AUDIO: communication mode for the call's
 * length, transient focus (other apps duck or pause), and the earpiece or the
 * loudspeaker. Released exactly once.
 */
internal class AudioRoute(context: Context) {
    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change -> CallLog.debug("audio focus $change") }
    private var held = false

    fun acquire(speaker: Boolean) {
        if (held) return
        held = true
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
        setSpeaker(speaker)
    }

    /** The loudspeaker, or back to the earpiece (or a headset, if one is connected). */
    fun setSpeaker(on: Boolean) {
        if (!held) return
        if (Build.VERSION.SDK_INT >= 31) {
            if (on) {
                am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    ?.let { runCatching { am.setCommunicationDevice(it) } }
            } else {
                runCatching { am.clearCommunicationDevice() }
            }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = on
        }
    }

    fun release() {
        if (!held) return
        setSpeaker(false)
        held = false
        if (Build.VERSION.SDK_INT >= 26) focusRequest?.let { am.abandonAudioFocusRequest(it) }
        else @Suppress("DEPRECATION") am.abandonAudioFocus(focusListener)
        focusRequest = null
        am.mode = AudioManager.MODE_NORMAL
    }
}
