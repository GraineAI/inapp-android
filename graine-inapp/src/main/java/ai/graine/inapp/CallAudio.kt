package ai.graine.inapp

/*
 * THE NATIVE CALL'S AUDIO RULES — pure, no Android, no threads.
 *
 * A native call speaks the voice socket itself: the customer's microphone up,
 * the agent's audio down. Every rule the React Native client learned the hard
 * way (headerless agent audio, the latch, the echo guard) has to hold here
 * exactly, so each one below is a port of sdk/inapp/audio.ts and is pinned by
 * NativeCallVectorsTest against vectors that TypeScript produced
 * (sdk/contract/native-calls.json). Where the port had to choose, the comment
 * says which way and why.
 */

/** What an agent audio stream turned out to be. Held for the call once it is sure. */
internal data class AgentAudioFormat(val kind: Kind, val sampleRate: Int) {
    enum class Kind(val wire: String) { MULAW("mulaw"), PCM("pcm") }
}

/** One decoded agent frame. [latch] is non-null once the stream's format is certain; keep it and pass it back. */
internal class DecodedAudio(
    val pcm16: ShortArray,
    val sampleRate: Int,
    val kind: AgentAudioFormat.Kind,
    val latch: AgentAudioFormat?,
)

internal object AgentAudio {
    /**
     * What headerless PCM from the agent is on a voice session: 24 kHz.
     * Measured off the production socket — 9600-byte frames with no
     * `meta_info`, 200 ms each.
     */
    const val PCM_RATE = 24_000

    /** What the microphone sends: 16 kHz mono PCM16, which the transcriber is configured for. */
    const val CAPTURE_RATE = 16_000

    /** 20 ms at [CAPTURE_RATE]: 320 samples, 640 bytes. */
    const val CAPTURE_FRAME_SAMPLES = 320

    /**
     * Below this the zero-crossing test is measuring dither, not speech, and a
     * frame must not decide the whole call's format — sdk/inapp/audio.ts
     * CONFIDENT_RMS, the code that generated the vectors.
     */
    const val CONFIDENT_RMS = 500.0

    /** Mu-law → PCM16, ITU-T G.711. Written out: a thirty-line function beats a dependency. */
    fun decodeMuLaw(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size)
        val bias = 0x84
        for (i in bytes.indices) {
            val mu = bytes[i].toInt().inv() and 0xff
            val sign = mu and 0x80
            val exponent = (mu shr 4) and 0x07
            val mantissa = mu and 0x0f
            val t = ((mantissa shl 3) + bias) shl exponent
            out[i] = (if (sign != 0) bias - t else t - bias).toShort()
        }
        return out
    }

    /** Little-endian PCM16. An odd trailing byte is a truncated sample: dropped, because playing it is a click. */
    fun decodePcm16(bytes: ByteArray): ShortArray {
        val out = ShortArray(bytes.size shr 1)
        for (i in out.indices) {
            out[i] = ((bytes[i * 2].toInt() and 0xff) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
        return out
    }

    /** Fraction of adjacent sample pairs that change sign. Speech is low; bytes read as the wrong format approach 0.5. */
    fun zeroCrossingRate(pcm: ShortArray): Double {
        if (pcm.size < 2) return 1.0
        var crossings = 0
        for (i in 1 until pcm.size) if ((pcm[i] < 0) != (pcm[i - 1] < 0)) crossings++
        return crossings.toDouble() / (pcm.size - 1)
    }

    fun rms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sum = 0.0
        for (s in pcm) sum += s.toDouble() * s.toDouble()
        return Math.sqrt(sum / pcm.size)
    }

    /**
     * Decode one agent audio frame — sdk/inapp/audio.ts `decodeAgentAudio`.
     *
     *  1. A declared format is believed: a server that bothers to declare one
     *     knows better than a measurement of a few milliseconds of audio.
     *  2. A stream that already latched is not re-measured: a format decided
     *     per frame flips on quiet frames, and one flipped frame is loud garbage.
     *  3. Otherwise both readings are measured and the lower zero-crossing rate
     *     wins (a tie goes to mu-law). The result latches only on a frame that
     *     carried evidence: loud as PCM, and the two readings told apart.
     *
     * @param declaredFormat `meta_info.format`, or null — usually null in production
     * @param declaredRate `meta_info.sample_rate`, or null
     * @param latched what this call's stream was already decided to be
     */
    fun decode(
        bytes: ByteArray,
        declaredFormat: String?,
        declaredRate: Int?,
        latched: AgentAudioFormat?,
        fallbackPcmRate: Int = PCM_RATE,
    ): DecodedAudio? {
        if (bytes.isEmpty()) return null
        // JavaScript's `declared?.sampleRate || x`: 0 (and absent) fall through.
        val rate = declaredRate?.takeIf { it > 0 }
        when (declaredFormat?.lowercase()) {
            "mulaw", "ulaw", "g711_ulaw", "pcmu" -> {
                val r = rate ?: 8000
                return DecodedAudio(decodeMuLaw(bytes), r, AgentAudioFormat.Kind.MULAW, AgentAudioFormat(AgentAudioFormat.Kind.MULAW, r))
            }
            "pcm", "linear16", "raw" -> {
                val r = rate ?: fallbackPcmRate
                return DecodedAudio(decodePcm16(bytes), r, AgentAudioFormat.Kind.PCM, AgentAudioFormat(AgentAudioFormat.Kind.PCM, r))
            }
        }
        if (latched != null) {
            val pcm = if (latched.kind == AgentAudioFormat.Kind.MULAW) decodeMuLaw(bytes) else decodePcm16(bytes)
            return DecodedAudio(pcm, latched.sampleRate, latched.kind, latched)
        }
        val asMuLaw = decodeMuLaw(bytes)
        val asPcm = decodePcm16(bytes)
        val zMu = zeroCrossingRate(asMuLaw)
        val zPcm = zeroCrossingRate(asPcm)
        val muLawWins = zMu <= zPcm
        val kind = if (muLawWins) AgentAudioFormat.Kind.MULAW else AgentAudioFormat.Kind.PCM
        val sampleRate = if (muLawWins) 8000 else rate ?: fallbackPcmRate
        // Measured on the PCM reading — the raw byte energy — never on the chosen
        // decode: digital silence decodes as full-scale mu-law and would "prove" it.
        val confident = rms(asPcm) >= CONFIDENT_RMS && zMu != zPcm
        return DecodedAudio(
            if (muLawWins) asMuLaw else asPcm, sampleRate, kind,
            if (confident) AgentAudioFormat(kind, sampleRate) else null,
        )
    }

    /** PCM16 samples → little-endian bytes, the microphone's wire form. */
    fun toLittleEndian(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val s = pcm[i].toInt()
            out[i * 2] = s.toByte()
            out[i * 2 + 1] = (s shr 8).toByte()
        }
        return out
    }

    /** True when every byte is zero — PCM silence, and nothing else it could sensibly be. */
    fun isAllZero(bytes: ByteArray): Boolean {
        for (b in bytes) if (b.toInt() != 0) return false
        return true
    }
}

/**
 * BASE64, WRITTEN OUT.
 *
 * `java.util.Base64` arrived in Android 8 (API 26) and this SDK runs from 7;
 * `android.util.Base64` is a stub in JVM tests. Twenty lines remove both
 * problems from the audio path. Decoding is lenient exactly as
 * sdk/inapp/audio.ts `base64ToBytes` is: anything outside the alphabet
 * (padding, whitespace) is skipped.
 */
internal object B64 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val INDEX = IntArray(128) { -1 }.also { t -> ALPHABET.forEachIndexed { i, c -> t[c.code] = i } }

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xff
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else -1
            out.append(ALPHABET[b0 shr 2])
            out.append(ALPHABET[((b0 and 3) shl 4) or ((if (b1 < 0) 0 else b1) shr 4)])
            out.append(if (b1 < 0) '=' else ALPHABET[((b1 and 15) shl 2) or ((if (b2 < 0) 0 else b2) shr 6)])
            out.append(if (b2 < 0) '=' else ALPHABET[b2 and 63])
            i += 3
        }
        return out.toString()
    }

    fun decode(text: String): ByteArray {
        val out = ByteArray(text.length * 3 / 4 + 3)
        var n = 0
        var acc = 0
        var bits = 0
        for (c in text) {
            val v = if (c.code < 128) INDEX[c.code] else -1
            if (v < 0) continue
            acc = (acc shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[n++] = (acc shr bits).toByte()
            }
            acc = acc and 0xffff
        }
        return out.copyOf(n)
    }
}

/**
 * STOPS THE AGENT FROM HEARING ITSELF — sdk/inapp/audio.ts `EchoGuard`, line for line.
 *
 * On a phone the agent's voice comes out of a speaker two inches from the
 * microphone, and some of it comes straight back in. Sent upstream, that
 * residue is transcribed as the customer, read as a barge-in, and the agent
 * interrupts itself. The platform echo canceller runs too; nothing removes
 * echo completely, so this measures it.
 *
 * While the agent is audible a frame must stand clearly above the measured
 * echo (and the room) for long enough to be an interruption; frames held
 * meanwhile are flushed ahead of the first one that passes, so the
 * transcriber gets the whole first word. With the agent silent every frame
 * passes. [T] is the frame as the caller will send it (a tag in the vectors,
 * the PCM in a call); [filter] returns the frames to send, oldest first.
 */
internal class EchoGuard<T>(
    /** Level must beat the measured echo by this much. */
    private val overEcho: Double = 2.2,
    /** ...and the ambient room by this much. */
    private val overNoise: Double = 4.0,
    /** Absolute floor, so a silent room cannot make the threshold meaningless. */
    private val minLevel: Double = 0.012,
    /** ...and be sustained this long. One transient is not an interruption. */
    private val sustainMs: Double = 240.0,
    /** How much held audio to flush ahead of a confirmed interruption. */
    private val preRollFrames: Int = 12,
    /** Frames at the start of each agent utterance spent measuring its echo (~200 ms). */
    private val warmupFrames: Int = 10,
) {
    private var echoFloor = 0.0
    private var noiseFloor = 0.0
    private var heldMs = 0.0
    private val preRoll = ArrayDeque<T>()
    /** Frames since the agent started being audible; the first ones are taken on faith as echo. */
    private var audibleFrames = 0

    /** The level a frame must reach right now to count as the customer. */
    val threshold: Double get() = maxOf(minLevel, echoFloor * overEcho, noiseFloor * overNoise)

    /** True once the level has stood above the threshold long enough to be real. */
    val interrupting: Boolean get() = heldMs >= sustainMs

    /** A new call starts with no idea what its echo sounds like. */
    fun reset() {
        echoFloor = 0.0
        noiseFloor = 0.0
        heldMs = 0.0
        preRoll.clear()
        audibleFrames = 0
    }

    fun filter(frame: T, pcm: ShortArray, agentAudible: Boolean, frameMs: Double): List<T> {
        var sum = 0.0
        for (s in pcm) sum += Math.abs(s.toInt())
        // Two divisions, in this order, as the TypeScript does: the same double, bit for bit.
        val level = if (pcm.isNotEmpty()) sum / pcm.size / 32768 else 0.0

        if (agentAudible) {
            audibleFrames++
            if (audibleFrames <= warmupFrames) {
                // Warm-up: this IS the echo. Learn its PEAK unconditionally, declare nothing.
                echoFloor = maxOf(echoFloor, level)
                heldMs = 0.0
                preRoll.addLast(frame)
                if (preRoll.size > preRollFrames) preRoll.removeFirst()
                return emptyList()
            }
            // Past warm-up only levels under the bar may move the floor, so a talking
            // customer cannot raise it against themselves; it decays slowly otherwise.
            if (level > echoFloor && level <= threshold) echoFloor = level
            else echoFloor *= 0.999
            heldMs = if (level >= threshold) heldMs + frameMs else 0.0
        } else {
            noiseFloor = if (noiseFloor == 0.0) level else noiseFloor * 0.95 + level * 0.05
            heldMs = 0.0
            audibleFrames = 0
            preRoll.clear()
            return listOf(frame)
        }

        if (level < threshold || !interrupting) {
            preRoll.addLast(frame)
            if (preRoll.size > preRollFrames) preRoll.removeFirst()
            return emptyList()
        }
        val out = ArrayList<T>(preRoll.size + 1)
        out.addAll(preRoll)
        out.add(frame)
        preRoll.clear()
        return out
    }
}

/**
 * Linear resampling, continuous across chunks.
 *
 * The call's speaker runs at one rate for the whole call (see
 * AndroidCallAudio). Production audio already arrives at that rate and never
 * comes through here; this exists for a stream that declares another rate,
 * or the odd frame that decodes as 8 kHz before the stream has latched.
 * Linear is enough for speech that was never wider than its source rate.
 */
internal class LinearResampler(val outRate: Int) {
    private var inRate = 0
    /** Where the next output sample falls, in input samples from the start of the next chunk (-1 < t). */
    private var t = 0.0
    /** The previous chunk's last sample: input position -1 of the next chunk. */
    private var last = 0.0

    fun reset() { inRate = 0; t = 0.0; last = 0.0 }

    fun process(pcm: ShortArray, rate: Int): ShortArray {
        if (pcm.isEmpty() || rate <= 0 || rate == outRate) return pcm
        if (rate != inRate) { inRate = rate; t = 0.0; last = pcm[0].toDouble() }
        val step = rate.toDouble() / outRate
        val n = pcm.size
        val out = ShortArray(((n - t) / step).toInt() + 2)
        var k = 0
        var p = t
        while (p <= n - 1) {
            val i = Math.floor(p).toInt()
            val frac = p - i
            val a = if (i < 0) last else pcm[i].toDouble()
            // p <= n - 1 with a fraction means i + 1 <= n - 1: the next sample is in this chunk.
            val b = if (frac == 0.0) a else pcm[i + 1].toDouble()
            out[k++] = Math.round(a + (b - a) * frac).toInt().coerceIn(-32768, 32767).toShort()
            p += step
        }
        t = p - n
        last = pcm[n - 1].toDouble()
        return out.copyOf(k)
    }
}
