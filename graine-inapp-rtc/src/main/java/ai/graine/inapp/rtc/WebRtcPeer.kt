package ai.graine.inapp.rtc

import ai.graine.inapp.GraineRtcIceServer
import ai.graine.inapp.GraineRtcInternal
import ai.graine.inapp.GraineRtcPeer
import ai.graine.inapp.GraineRtcStats
import android.content.Context
import android.media.AudioAttributes
import android.media.MediaRecorder
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.CandidatePairChangeEvent
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.RtpTransceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.audio.JavaAudioDeviceModule

/**
 * ONE CALL'S PEER CONNECTION, on Google's WebRTC — audio only.
 *
 *   the microphone   JavaAudioDeviceModule on VOICE_COMMUNICATION (the source the
 *                    platform runs its echo canceller on), mono, the phone's
 *                    hardware echo canceller and noise suppressor when it has them
 *                    (WebRTC's own otherwise), automatic gain off as the voice bar
 *                    has it; recording starts only once the call is connected
 *   the speaker      voice-communication audio, routed by the SDK as every call is
 *   the connection   unified plan, one audio transceiver sending and receiving,
 *                    bundle balanced (as the browser), RTCP multiplexed, candidates
 *                    gathered continually with a pool of one, so the offer can carry them
 *
 * Each call has its own factory and audio device, released with it: nothing
 * of one call outlives it. Every method is called from the call's own thread,
 * never from a WebRTC callback.
 */
@OptIn(GraineRtcInternal::class)
internal class WebRtcPeer private constructor(
    private val factory: PeerConnectionFactory,
    private val adm: JavaAudioDeviceModule,
    private val source: AudioSource,
    private val track: AudioTrack,
) : GraineRtcPeer {
    private lateinit var pc: PeerConnection
    @Volatile private var closed = false

    override fun createOffer(iceRestart: Boolean, done: (error: String?) -> Unit) {
        val constraints = MediaConstraints()
        if (iceRestart) constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                pc.setLocalDescription(object : Sdp() {
                    override fun onSetSuccess() = done(null)
                    override fun onSetFailure(error: String?) = done(error ?: "the offer could not be set")
                }, sdp)
            }
            override fun onCreateFailure(error: String?) = done(error ?: "no offer")
        }, constraints)
    }

    override fun localDescription(): String? = if (closed) null else pc.localDescription?.description

    override fun setRemoteAnswer(sdp: String, done: (error: String?) -> Unit) {
        val answer = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        if (pc.signalingState() == PeerConnection.SignalingState.STABLE) {
            // An answer already applied (an early one): a fresh offer first, as the browser's stack does.
            createOffer(iceRestart = false) { error -> if (error != null) done(error) else setRemote(answer, done) }
        } else {
            setRemote(answer, done)
        }
    }

    private fun setRemote(desc: SessionDescription, done: (error: String?) -> Unit) {
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() = done(null)
            override fun onSetFailure(error: String?) = done(error ?: "the answer could not be set")
        }, desc)
    }

    override fun answerOffer(sdp: String, done: (answer: String?, error: String?) -> Unit) {
        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                pc.createAnswer(object : Sdp() {
                    override fun onCreateSuccess(answer: SessionDescription) {
                        pc.setLocalDescription(object : Sdp() {
                            override fun onSetSuccess() = done(pc.localDescription?.description ?: answer.description, null)
                            override fun onSetFailure(error: String?) = done(null, error ?: "the answer could not be set")
                        }, answer)
                    }
                    override fun onCreateFailure(error: String?) = done(null, error ?: "no answer")
                }, MediaConstraints())
            }
            override fun onSetFailure(error: String?) = done(null, error ?: "the offer could not be set")
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    override fun setMicrophoneEnabled(enabled: Boolean) {
        if (!closed) track.setEnabled(enabled)
    }

    override fun setAudioActive(recording: Boolean, playout: Boolean) {
        if (closed) return
        pc.setAudioRecording(recording)
        pc.setAudioPlayout(playout)
    }

    override fun stats(done: (GraineRtcStats?) -> Unit) {
        if (closed) { done(null); return }
        pc.getStats { report ->
            done(runCatching { WebRtcStats.read(report.statsMap.values.map { WebRtcStats.Entry(it.id, it.type, it.members) }) }.getOrNull())
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        // In the library's order: the connection, then what fed it, then the factory and the audio device.
        runCatching { pc.dispose() }
        runCatching { track.dispose() }
        runCatching { source.dispose() }
        runCatching { factory.dispose() }
        runCatching { adm.release() }
    }

    /** An SDP observer whose callbacks a caller overrides as it needs. */
    private abstract class Sdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    /** The connection's events, as the SDK hears them. */
    private class Events(private val listener: GraineRtcPeer.Listener) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) = listener.onCandidate(candidate.sdp)
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) listener.onGatheringComplete()
        }
        // The standard state machine, as the browser reports it — what the voice bar's rules were measured on.
        override fun onStandardizedIceConnectionChange(state: PeerConnection.IceConnectionState) = listener.onIceState(state.name.lowercase())
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
    }

    /** The microphone or the speaker failing under the call: the SDK ends it, or carries it elsewhere before media. */
    private class AudioErrors(private val listener: GraineRtcPeer.Listener) :
        JavaAudioDeviceModule.AudioRecordErrorCallback, JavaAudioDeviceModule.AudioTrackErrorCallback {
        override fun onWebRtcAudioRecordInitError(message: String) = listener.onAudioError("microphone: $message")
        override fun onWebRtcAudioRecordStartError(code: JavaAudioDeviceModule.AudioRecordStartErrorCode, message: String) =
            listener.onAudioError("microphone ($code): $message")
        override fun onWebRtcAudioRecordError(message: String) = listener.onAudioError("microphone: $message")
        override fun onWebRtcAudioTrackInitError(message: String) = listener.onAudioError("speaker: $message")
        override fun onWebRtcAudioTrackStartError(code: JavaAudioDeviceModule.AudioTrackStartErrorCode, message: String) =
            listener.onAudioError("speaker ($code): $message")
        override fun onWebRtcAudioTrackError(message: String) = listener.onAudioError("speaker: $message")
    }

    companion object {
        private val initLock = Any()
        @Volatile private var initialized = false

        /** Load the native library and set WebRTC up for this process: once. Throws when the library cannot load. */
        private fun initialize(context: Context) {
            if (initialized) return
            synchronized(initLock) {
                if (initialized) return
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions())
                initialized = true
            }
        }

        fun create(context: Context, servers: List<GraineRtcIceServer>, listener: GraineRtcPeer.Listener): WebRtcPeer {
            val app = context.applicationContext ?: context
            initialize(app)
            val errors = AudioErrors(listener)
            val adm = JavaAudioDeviceModule.builder(app)
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setUseHardwareAcousticEchoCanceler(JavaAudioDeviceModule.isBuiltInAcousticEchoCancelerSupported())
                .setUseHardwareNoiseSuppressor(JavaAudioDeviceModule.isBuiltInNoiseSuppressorSupported())
                .setUseStereoInput(false)
                .setUseStereoOutput(false)
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build())
                .setAudioRecordErrorCallback(errors)
                .setAudioTrackErrorCallback(errors)
                .createAudioDeviceModule()
            val factory = try {
                PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
            } catch (e: Throwable) {
                adm.release()
                throw e
            }
            // The microphone as the voice bar asks for it: echo cancellation and noise suppression on, gain control off.
            val constraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
                mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            }
            val source = factory.createAudioSource(constraints)
            val track = factory.createAudioTrack("graine-microphone", source)
            val peer = WebRtcPeer(factory, adm, source, track)
            val iceServers = servers.map { s ->
                PeerConnection.IceServer.builder(s.urls).apply {
                    s.username?.let { setUsername(it) }
                    s.credential?.let { setPassword(it) }
                }.createIceServer()
            }
            val config = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                // Balanced, as the voice bar's browser has it. The server answers with no BUNDLE group, and
                // under max-bundle WebRTC refuses that answer ("Answer cannot remove m= section with mid='0'
                // from already-established BUNDLE group"); with one m= line the offer is the same either way.
                bundlePolicy = PeerConnection.BundlePolicy.BALANCED
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                iceCandidatePoolSize = 1
                keyType = PeerConnection.KeyType.ECDSA
            }
            val pc = factory.createPeerConnection(config, Events(listener))
            if (pc == null) {
                peer.closed = true
                runCatching { track.dispose() }
                runCatching { source.dispose() }
                runCatching { factory.dispose() }
                runCatching { adm.release() }
                throw IllegalStateException("WebRTC made no peer connection")
            }
            peer.pc = pc
            pc.addTransceiver(track, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf("graine")))
            return peer
        }
    }
}
