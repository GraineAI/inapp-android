@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

/** A peer connection the test drives: the offer it makes, the candidates it reports, the ICE states. */
internal class FakePeer(var offer: String = Trace.OFFER) : GraineRtcPeer {
    var listener: GraineRtcPeer.Listener? = null
    val calls = mutableListOf<String>()
    var remoteAnswer: String? = null
    var answeredOffer: String? = null
    var micEnabled = true
    var recording = true
    var playout = true
    var closed = false
    var failOffer = false
    var stats: GraineRtcStats? = null
    /** createOffer's callbacks waiting for the test, or answered at once. */
    var offerImmediately = true
    private var pendingOffer: ((String?) -> Unit)? = null
    private var hasLocal = false
    /**
     * Candidates found before the first offer — the pool's, as a connection
     * made with iceCandidatePoolSize gathers them — and handed to that offer
     * once it is set, as the library hands them over.
     */
    private val pool = mutableListOf<String>()

    override fun createOffer(iceRestart: Boolean, done: (error: String?) -> Unit) {
        calls += if (iceRestart) "createOffer(restart)" else "createOffer"
        if (failOffer) { done("no"); return }
        hasLocal = true
        if (offerImmediately) { done(null); flushPool() } else pendingOffer = done
    }
    fun completeOffer() { pendingOffer?.invoke(null); pendingOffer = null; flushPool() }
    private fun flushPool() { val found = pool.toList(); pool.clear(); for (c in found) listener!!.onCandidate(c) }
    override fun localDescription(): String? = if (hasLocal) offer else null
    override fun setRemoteAnswer(sdp: String, done: (error: String?) -> Unit) { calls += "setRemoteAnswer"; remoteAnswer = sdp; done(null) }
    override fun answerOffer(sdp: String, done: (answer: String?, error: String?) -> Unit) { calls += "answerOffer"; answeredOffer = sdp; done("v=0\r\ns=answer\r\n", null) }
    override fun setMicrophoneEnabled(enabled: Boolean) { micEnabled = enabled }
    /** Every setAudioActive, in order: (recording, playout). A peer starts recording and playing, as the library's does. */
    val audioActive = mutableListOf<Pair<Boolean, Boolean>>()
    override fun setAudioActive(recording: Boolean, playout: Boolean) { audioActive += recording to playout; this.recording = recording; this.playout = playout }
    override fun stats(done: (GraineRtcStats?) -> Unit) = done(stats)
    override fun close() { closed = true; calls += "close" }

    /** A candidate found: signalled now once there is an offer, else kept in the pool for the first one. */
    fun candidate(typ: String) {
        val c = "candidate:9 1 udp 1 192.0.2.1 9 typ $typ"
        if (hasLocal) listener!!.onCandidate(c) else pool += c
    }
    fun ice(state: String) = listener!!.onIceState(state)
}

internal fun rtcStats(bytes: Long = 0, level: Double? = null, energy: Double? = null, duration: Double? = null) =
    GraineRtcStats(bytes, bytes / 160, 0, level, energy, duration, 0.01, 1000, 0.05, "audio/PCMU", "srflx")
