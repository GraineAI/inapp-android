package ai.graine.inapp.rtc

import ai.graine.inapp.GraineAvatarViewer
import ai.graine.inapp.GraineRtcIceServer
import ai.graine.inapp.GraineRtcInternal
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.CandidatePairChangeEvent
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.MediaStreamTrack
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.SurfaceViewRenderer
import livekit.org.webrtc.VideoFrame
import livekit.org.webrtc.VideoSink
import livekit.org.webrtc.VideoTrack
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * THE AGENT'S FACE, PLAYED NATIVELY — a second peer connection beside the
 * call's, made from the call's own factory (its audio device), so the face's
 * sound plays through the call's audio: its route, its focus, its echo
 * canceller.
 *
 *   receive only   one audio and one video transceiver, nothing sent; one data
 *                  channel for the service's clock
 *   the offer      complete before it leaves: the service never trickles
 *   the picture    a SurfaceViewRenderer (aspect fill), and a counter of frames
 *                  for the stall rule
 *   the sound      the face's track volume: 0 until the SDK says it carries the
 *                  voice; its level is measured before that volume
 *
 * WebRTC only: there is no other road for the face. The SDK (AvatarFace.kt)
 * runs the requests, the clock and the hand-over; this only moves media.
 */
@OptIn(GraineRtcInternal::class)
internal class WebRtcAvatarViewer(
    private val factory: PeerConnectionFactory,
    private val listener: GraineAvatarViewer.Listener,
) : GraineAvatarViewer {
    private val lock = Any()
    private var pc: PeerConnection? = null
    private var channel: DataChannel? = null
    private var audio: AudioTrack? = null
    private var video: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    @Volatile private var audible = false
    @Volatile private var closed = false
    private val frames = AtomicLong(0)
    private val meter = LevelMeter()
    private val counter = VideoSink { frames.incrementAndGet() }
    private var gathered: ((String?, String?) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())

    override fun offer(iceServers: List<GraineRtcIceServer>, gatherMs: Long, done: (sdp: String?, error: String?) -> Unit) {
        val servers = iceServers.map { s ->
            PeerConnection.IceServer.builder(s.urls).apply {
                s.username?.let { setUsername(it) }
                s.credential?.let { setPassword(it) }
            }.createIceServer()
        }
        val config = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // As the browser offers it: balanced bundle, RTCP multiplexed.
            bundlePolicy = PeerConnection.BundlePolicy.BALANCED
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            keyType = PeerConnection.KeyType.ECDSA
        }
        val peer = factory.createPeerConnection(config, Events())
        if (peer == null) { done(null, "the peer connection could not be made"); return }
        synchronized(lock) { pc = peer }
        val receiveOnly = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        val a = peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, receiveOnly)
        val v = peer.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, receiveOnly)
        if (a == null || v == null) { done(null, "the transceivers could not be added"); return }
        attach(a.receiver.track() as? AudioTrack, v.receiver.track() as? VideoTrack)
        val ch = peer.createDataChannel("playback", DataChannel.Init().apply { ordered = true })
        ch?.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previous: Long) {}
            override fun onStateChange() { if (ch.state() == DataChannel.State.OPEN && !closed) listener.onChannelOpen() }
            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary || closed) return
                val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
                listener.onMessage(String(bytes, Charsets.UTF_8))
            }
        })
        synchronized(lock) { channel = ch }
        peer.createOffer(object : Sdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peer.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() = waitForCandidates(peer, gatherMs, done)
                    override fun onSetFailure(error: String?) = done(null, error ?: "the offer could not be set")
                }, sdp)
            }
            override fun onCreateFailure(error: String?) = done(null, error ?: "no offer")
        }, MediaConstraints())
    }

    override fun applyAnswer(sdp: String, done: (error: String?) -> Unit) {
        val peer = synchronized(lock) { if (closed) null else pc } ?: run { done("no peer connection"); return }
        peer.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() = done(null)
            override fun onSetFailure(error: String?) = done(error ?: "the answer was refused")
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    override fun send(text: String) {
        val ch = synchronized(lock) { if (closed) null else channel } ?: return
        if (ch.state() != DataChannel.State.OPEN) return
        ch.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
    }

    override fun setAudible(audible: Boolean) {
        this.audible = audible
        synchronized(lock) { audio }?.let { runCatching { it.setVolume(if (audible) 1.0 else 0.0) } }
    }

    override fun sample(done: (frames: Double?, face: Double?, jitterBufferDelay: Double?, emitted: Double?) -> Unit) {
        val peer = synchronized(lock) { if (closed) null else pc } ?: run { done(null, null, null, null); return }
        val count = frames.get()
        val face = meter.peak()
        peer.getStats { report ->
            var delay: Double? = null
            var emitted: Double? = null
            var decoded: Double? = null
            for (s in report.statsMap.values) {
                if (s.type != "inbound-rtp") continue
                val m = s.members
                when (m["kind"] ?: m["mediaType"]) {
                    "audio" -> {
                        delay = (m["jitterBufferDelay"] as? Number)?.toDouble()
                        emitted = (m["jitterBufferEmittedCount"] as? Number)?.toDouble()
                    }
                    "video" -> decoded = (m["framesDecoded"] as? Number)?.toDouble()
                }
            }
            // Frames handed to be drawn, else decoded: whichever this build reports.
            done(if (count > 0) count.toDouble() else decoded, face, delay, emitted)
        }
    }

    override fun videoView(context: Context): View? {
        renderer?.let { return it }
        val track = synchronized(lock) { if (closed) null else video } ?: return null
        val made = SurfaceViewRenderer(context).apply {
            init(Egl.context(), null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
        }
        track.addSink(made)
        renderer = made
        return made
    }

    override fun close() {
        val (peer, ch, a, v) = synchronized(lock) {
            if (closed) return
            closed = true
            val out = Quad(pc, channel, audio, video)
            pc = null; channel = null; audio = null; video = null
            out
        }
        main.removeCallbacksAndMessages(null)
        gathered = null
        runCatching { a?.setVolume(0.0); a?.removeSink(meter) }
        runCatching { v?.removeSink(counter) }
        val r = renderer
        renderer = null
        if (r != null) main.post {
            runCatching { v?.removeSink(r) }
            (r.parent as? android.view.ViewGroup)?.removeView(r)
            runCatching { r.release() }
        }
        runCatching { ch?.unregisterObserver(); ch?.close() }
        runCatching { peer?.dispose() }
    }

    private data class Quad(val pc: PeerConnection?, val ch: DataChannel?, val a: AudioTrack?, val v: VideoTrack?)

    private fun attach(a: AudioTrack?, v: VideoTrack?) {
        synchronized(lock) { audio = a; video = v }
        // Silent until the SDK hands it the voice; measured all the same.
        runCatching { a?.setVolume(if (audible) 1.0 else 0.0) }
        a?.addSink(meter)
        v?.addSink(counter)
    }

    /** The offer once gathering is complete — or what there is after [ms]. */
    private fun waitForCandidates(peer: PeerConnection, ms: Long, done: (String?, String?) -> Unit) {
        if (peer.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) {
            val sdp = peer.localDescription?.description
            done(sdp, if (sdp == null) "no offer" else null)
            return
        }
        synchronized(lock) { gathered = done }
        main.postDelayed({ finishGathering() }, ms)
    }

    private fun finishGathering() {
        val done = synchronized(lock) { gathered.also { gathered = null } } ?: return
        val sdp = synchronized(lock) { if (closed) null else pc?.localDescription?.description }
        done(sdp, if (sdp == null) "no offer" else null)
    }

    private inner class Events : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) finishGathering()
        }
        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            if (!closed) listener.onConnection(state.name.lowercase())
        }
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidate(candidate: IceCandidate) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    private abstract class Sdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    /** One EGL context for every face this process draws, made when the first is. */
    private object Egl {
        @Volatile private var base: EglBase? = null
        fun context(): EglBase.Context = (base ?: synchronized(this) { base ?: EglBase.create().also { base = it } }).eglBaseContext
    }
}

/**
 * A RECEIVED VOICE'S LEVEL, BEFORE ITS VOLUME — the decoded audio as the
 * track's sinks hear it, ahead of the gain a silenced track applies. Two
 * readings over the last quarter second: the meter scale (sqrt(meanAbs / 0.25),
 * the web engine's `meterLevel`) and the peak (0–1, as WebRTC's `audioLevel`).
 */
internal class LevelMeter : AudioTrackSink {
    private class Window(val at: Long, val meanAbs: Double, val peak: Double)
    private val windows = ArrayDeque<Window>()

    override fun onData(data: ByteBuffer, bitsPerSample: Int, sampleRate: Int, channels: Int, frames: Int, captureMs: Long) {
        if (bitsPerSample != 16 || frames <= 0 || channels <= 0) return
        val shorts = data.duplicate().order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sum = 0.0
        var peak = 0.0
        val n = Math.min(frames, shorts.remaining() / channels)
        for (i in 0 until n) {
            val v = Math.abs(shorts.get(i * channels).toInt()) / 32768.0
            sum += v
            if (v > peak) peak = v
        }
        if (n == 0) return
        add(sum / n, Math.min(1.0, peak))
    }

    /** One window's reading (the tests feed it directly). */
    fun add(meanAbs: Double, peak: Double, now: Long = System.nanoTime() / 1_000_000) = synchronized(windows) {
        windows.addLast(Window(now, meanAbs, peak))
        while (windows.isNotEmpty() && now - windows.first().at > WINDOW_MS) windows.removeFirst()
    }

    private fun recent(now: Long): List<Window> = synchronized(windows) { windows.filter { now - it.at <= WINDOW_MS } }

    /** The meter-scale level (0–1) over the last quarter second; 0 when nothing arrived. */
    fun meterLevel(now: Long = System.nanoTime() / 1_000_000): Double {
        val w = recent(now)
        if (w.isEmpty()) return 0.0
        return meterScale(w.sumOf { it.meanAbs } / w.size)
    }

    /** The peak (0–1) over the last quarter second; 0 when nothing arrived. */
    fun peak(now: Long = System.nanoTime() / 1_000_000): Double = recent(now).maxOfOrNull { it.peak } ?: 0.0

    companion object {
        const val WINDOW_MS = 250L

        fun meterScale(meanAbs: Double): Double {
            val x = if (meanAbs.isNaN() || meanAbs.isInfinite()) 0.0 else Math.max(0.0, meanAbs)
            return Math.round(Math.min(1.0, Math.sqrt(x / 0.25)) * 100) / 100.0
        }
    }
}
