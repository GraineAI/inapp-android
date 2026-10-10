package ai.graine.inapp

/*
 * THE PLAYER'S RULES — sdk/NATIVE-CALLS.md "The player", pure and clock-injected
 * so every one of them is proven on the JVM (CallPlayerTest). AndroidCallAudio
 * is the thin shell that hands these chunks to an AudioTrack.
 */

/**
 * The agent's audio, queued until it may play.
 *
 *  - A run starts once 150 ms is queued — or once 120 ms passed with no new
 *    audio: the end of a turn never reaches 150 ms, and a reply shorter than
 *    that ("Yes.") must still play. That is the drain path, and without it
 *    the tail of every turn is stranded and [bufferedSeconds] never returns
 *    to zero — which defers every mark ack until the agent stops taking turns.
 *  - A run that played out completely (nothing queued, nothing left on the
 *    device) is over; the next one refills to 150 ms the same way. One
 *    network hiccup must not become a turn of stutter.
 *  - [clear] drops everything queued, immediately.
 *
 * Every count is in frames at [rate]. Not thread-safe: the owner locks.
 */
internal class PlayoutBuffer(val rate: Int, private val now: () -> Long) {
    private val chunks = ArrayDeque<ShortArray>()
    var queuedFrames = 0
        private set
    var running = false
        private set
    private var lastAudioAt = Long.MIN_VALUE / 4

    /** 150 ms at [rate]. */
    val startFrames: Int = Math.ceil(rate * MIN_START_SECONDS).toInt()

    /**
     * Queue agent audio. [deviceUnplayed] is the agent audio the device still
     * holds: a run whose queue and device are both empty has ended, and this
     * chunk starts a new one.
     */
    fun push(pcm: ShortArray, deviceUnplayed: Int) {
        if (pcm.isEmpty()) return
        if (running && chunks.isEmpty() && deviceUnplayed <= 0) running = false
        chunks.addLast(pcm)
        queuedFrames += pcm.size
        lastAudioAt = now()
        if (!running && queuedFrames >= startFrames) running = true
    }

    /** The next chunk the device may have, or null to wait. */
    fun take(deviceUnplayed: Int): ShortArray? {
        if (!running) {
            if (chunks.isEmpty()) return null
            if (queuedFrames < startFrames && now() - lastAudioAt < DRAIN_AFTER_MS) return null
            running = true
        }
        val c = chunks.removeFirstOrNull()
        if (c == null) {
            // Ran dry. Played out on the device too: the next audio refills first.
            if (deviceUnplayed <= 0) running = false
            return null
        }
        queuedFrames -= c.size
        return c
    }

    /** Hand a chunk back (the device took only part of it): it plays next. */
    fun putBack(rest: ShortArray) {
        if (rest.isEmpty()) return
        chunks.addFirst(rest)
        queuedFrames += rest.size
    }

    fun clear() {
        chunks.clear()
        queuedFrames = 0
        running = false
    }

    /** How long until [take] may say yes without another push — the drain path's wait — or null. */
    fun dueInMs(): Long? =
        if (!running && chunks.isNotEmpty()) Math.max(0L, DRAIN_AFTER_MS - (now() - lastAudioAt)) else null

    /** Seconds still to be heard: queued here plus what the device holds. Zero, exactly, when drained. */
    fun bufferedSeconds(deviceUnplayed: Int): Double = (queuedFrames + Math.max(0, deviceUnplayed)).toDouble() / rate

    companion object {
        const val MIN_START_SECONDS = 0.15
        const val DRAIN_AFTER_MS = 120L
    }
}

/**
 * WHICH FRAMES ON THE DEVICE ARE THE AGENT'S.
 *
 * The speaker is fed silence between turns, so it never underruns (an
 * AudioTrack that underruns long enough is disabled, and a restarted one waits
 * for a full buffer before it plays — which strands a short reply). So "frames
 * written minus frames played" counts silence too; this ledger knows which
 * written frames were the agent's, and [agentUnplayed] is exactly the agent
 * audio not yet past the play head — zero once its last frame has played.
 */
internal class DeviceLedger {
    /** Frames written since the last flush — the play head's scale. */
    var written = 0L
        private set
    private val agent = ArrayDeque<LongArray>()

    fun wroteAgent(frames: Int) {
        if (frames <= 0) return
        val last = agent.lastOrNull()
        if (last != null && last[1] == written) last[1] += frames.toLong()
        else agent.addLast(longArrayOf(written, written + frames))
        written += frames
    }

    fun wroteSilence(frames: Int) {
        if (frames > 0) written += frames
    }

    /** The agent's frames beyond [head] (frames played since the last flush). */
    fun agentUnplayed(head: Long): Int {
        while (agent.isNotEmpty() && agent.first()[1] <= head) agent.removeFirst()
        var n = 0L
        for (r in agent) n += r[1] - Math.max(r[0], head)
        return n.toInt()
    }

    /** Everything written and not yet played, silence included. */
    fun depth(head: Long): Long = Math.max(0L, written - head)

    /** The device was flushed: its play head starts again at zero. */
    fun flush() {
        written = 0
        agent.clear()
    }
}
