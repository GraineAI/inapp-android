package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.security.cert.X509Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HostnameVerifier

/**
 * THE NATIVE CALL AGAINST PRODUCTION, ON THE JVM — opt-in, never in the default run.
 *
 *     GRAINE_LIVE_E2E=1 GRAINE_E2E_KEY=<publishable key> GRAINE_E2E_WAV=<16 kHz mono WAV> \
 *       ./gradlew :graine-inapp:testDebugUnitTest --tests 'ai.graine.inapp.LiveCallE2ETest' --rerun
 *
 * The real VoiceCallSession over the real WebSocketClient, with injected audio:
 * a microphone that speaks the WAV in real time once the welcome has played,
 * and a speaker simulated in real time through the SDK's own player policy, so
 * marks are acked at playout exactly as on a phone. It asserts the socket
 * opens, the welcome arrives as agent audio and decodes, marks are acked, and
 * the agent answers the question. The key comes from the environment only.
 */
class LiveCallE2ETest {
    private val clock = { System.nanoTime() / 1_000_000 }

    /** The JVM's default verifier refuses everything; the TLS stack already checked the host (HTTPS endpoint identification). This checks it again from the certificate. */
    private val certificateNames = HostnameVerifier { host, session ->
        val cert = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@HostnameVerifier false
        val names = cert.subjectAlternativeNames?.filter { it[0] == 2 }?.map { it[1] as String }.orEmpty()
        names.any { n ->
            n.equals(host, ignoreCase = true) ||
                (n.startsWith("*.") && host.endsWith(n.substring(1), ignoreCase = true) && host.count { it == '.' } == n.count { it == '.' })
        }
    }

    /** A microphone that speaks [question] when told, and a speaker that plays in real time. */
    private inner class LiveAudio(private val question: ShortArray) : CallAudioIO {
        private val lock = Object()
        private val player = PlayoutBuffer(AgentAudio.PCM_RATE, clock)
        private val ledger = DeviceLedger()
        private val resampler = LinearResampler(AgentAudio.PCM_RATE)
        private var head = 0.0
        private var lastTick = clock()
        @Volatile private var running = false
        @Volatile private var speakAt = Long.MAX_VALUE
        @Volatile var questionEndedAt = Long.MAX_VALUE
        val frames = AtomicInteger()
        val samples = AtomicInteger()
        val rates = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val recorded = java.io.ByteArrayOutputStream()
        var clears = 0

        fun speakNow() { speakAt = clock() }

        private fun tick() {
            val now = clock()
            head = Math.min(ledger.written.toDouble(), head + (now - lastTick) * AgentAudio.PCM_RATE / 1000.0)
            lastTick = now
            while (true) ledger.wroteAgent((player.take(ledger.agentUnplayed(head.toLong())) ?: break).size)
        }

        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {
            running = true
            Thread({
                var pos = 0
                var next = System.nanoTime()
                while (running) {
                    val frame = ShortArray(AgentAudio.CAPTURE_FRAME_SAMPLES)
                    if (clock() >= speakAt && pos < question.size) {
                        val n = Math.min(frame.size, question.size - pos)
                        System.arraycopy(question, pos, frame, 0, n)
                        pos += n
                        if (pos >= question.size) questionEndedAt = clock()
                    }
                    onFrame(frame)
                    next += 20_000_000
                    val wait = next - System.nanoTime()
                    if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                }
            }, "e2e-microphone").apply { isDaemon = true }.start()
        }

        override fun play(pcm: ShortArray, sampleRate: Int) = synchronized(lock) {
            frames.incrementAndGet()
            samples.addAndGet(pcm.size)
            rates += sampleRate
            val out = resampler.process(pcm, sampleRate)
            recorded.write(AgentAudio.toLittleEndian(out))
            tick()
            player.push(out, ledger.agentUnplayed(head.toLong()))
            tick()
        }

        override fun clear() {
            synchronized(lock) { player.clear(); ledger.flush(); head = 0.0; lastTick = clock(); clears++ }
        }

        override fun bufferedSeconds(): Double = synchronized(lock) { tick(); player.bufferedSeconds(ledger.agentUnplayed(head.toLong())) }

        override fun stop() { running = false }
    }

    private fun readWav(path: String): ShortArray {
        RandomAccessFile(path, "r").use { f ->
            val bytes = ByteArray(f.length().toInt()).also { f.readFully(it) }
            fun le32(i: Int) = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8) or ((bytes[i + 2].toInt() and 0xff) shl 16) or ((bytes[i + 3].toInt() and 0xff) shl 24)
            fun le16(i: Int) = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)
            require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "$path is not a WAV file" }
            var i = 12
            var rate = 0; var channels = 0; var bits = 0
            while (i + 8 <= bytes.size) {
                val id = String(bytes, i, 4)
                val size = le32(i + 4)
                if (id == "fmt ") { channels = le16(i + 10); rate = le32(i + 12); bits = le16(i + 22) }
                if (id == "data") {
                    require(rate == 16_000 && channels == 1 && bits == 16) { "the WAV must be 16 kHz mono 16-bit (is $rate Hz, $channels ch, $bits bit)" }
                    return AgentAudio.decodePcm16(bytes.copyOfRange(i + 8, Math.min(bytes.size, i + 8 + size)))
                }
                i += 8 + size + (size and 1)
            }
            error("$path has no data chunk")
        }
    }

    private fun waitFor(timeoutMs: Long, what: () -> Boolean): Boolean {
        val until = clock() + timeoutMs
        while (clock() < until) { if (what()) return true; Thread.sleep(50) }
        return what()
    }

    @Test(timeout = 180_000)
    fun aRealCallWithTheAgent() {
        assumeTrue("set GRAINE_LIVE_E2E=1 to run against production", System.getenv("GRAINE_LIVE_E2E") == "1")
        val key = System.getenv("GRAINE_E2E_KEY").orEmpty()
        val wav = System.getenv("GRAINE_E2E_WAV").orEmpty()
        assumeTrue("GRAINE_E2E_KEY and GRAINE_E2E_WAV are required", key.isNotEmpty() && wav.isNotEmpty())
        val base = "https://www.graine.ai"
        val question = readWav(wav)
        val t0 = clock()
        fun at() = "${clock() - t0} ms"
        val log = CopyOnWriteArrayList<String>()
        fun note(s: String) { log += "[${at()}] $s"; println("[${at()}] $s") }

        val session = SessionFetch.fetch(base, key)
        note("session: agent ${session.webcallAgentId.take(6)}…, wsHost ${session.wsHost ?: "(default)"}")

        // The wire, as it happened.
        val received = CopyOnWriteArrayList<Pair<Long, JSONObject>>()
        val sent = CopyOnWriteArrayList<Pair<Long, JSONObject>>()
        var transport = "?"
        var firstAudio: ByteArray? = null
        val audio = LiveAudio(question)
        val opened = java.util.concurrent.CountDownLatch(1)
        val ended = CopyOnWriteArrayList<String>()
        val agentTurns = CopyOnWriteArrayList<String>()
        val customer = CopyOnWriteArrayList<String>()
        val speakingLog = CopyOnWriteArrayList<Pair<Long, Boolean>>()

        val deps = CallSessionDeps(
            scheduler = ExecutorScheduler("e2e-call"),
            audio = audio,
            http = PlatformHttp,
            openSocket = { url, protocols, listener ->
                transport = if (protocols.size == 2) "gateway (ticket in the subprotocol)" else "direct"
                val recorder = object : CallSocketListener {
                    override fun onOpen(protocol: String?) { note("socket open, subprotocol=$protocol"); listener.onOpen(protocol) }
                    override fun onText(text: String) {
                        val o = runCatching { JSONObject(text) }.getOrNull()
                        if (o != null) {
                            if (o.optString("type") == "audio") {
                                if (firstAudio == null) firstAudio = B64.decode(o.optString("data"))
                                o.put("data", "<${o.optString("data").length} b64>")
                            }
                            received += (clock() - t0) to o
                        }
                        listener.onText(text)
                    }
                    override fun onClosed(code: Int, reason: String) { note("socket closed $code $reason"); listener.onClosed(code, reason) }
                    override fun onFailure(error: Throwable) { note("socket failure: $error"); listener.onFailure(error) }
                }
                val ws = WebSocketClient(url, protocols, recorder, hostnameVerifier = { certificateNames }).connect()
                object : CallSocket {
                    override fun send(text: String): Boolean {
                        runCatching { JSONObject(text) }.getOrNull()?.let { o ->
                            if (o.optString("type") == "audio") o.put("data", "<pcm>")
                            sent += (clock() - t0) to o
                        }
                        return ws.send(text)
                    }
                    override fun close(code: Int, reason: String) = ws.close(code, reason)
                }
            },
            runAction = { name, _, answer -> note("app_action $name -> refused (the test registers none)"); answer(ActionResult.refused("This test registers no actions.")) },
            contextFrame = { JSONObject().put("context", JSONObject().put("screen", "settings").put("title", "Settings")).put("seq", 1) },
        )
        val events = object : CallSessionEvents {
            override fun onOpen() { note("call open"); opened.countDown() }
            override fun onFailed(error: String) { note("FAILED: $error"); ended += "failed: $error"; opened.countDown() }
            override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { note("ended: ${reason.wire} ${error ?: ""}"); ended += reason.wire }
            override fun onCaption(role: Role, text: String, live: Boolean) {
                if (live) return
                if (role == Role.AGENT) { agentTurns += text; note("agent said: \"$text\"") } else { customer += text; note("customer said: \"$text\"") }
            }
            override fun onAgentSpeaking(speaking: Boolean) { speakingLog += (clock() - t0) to speaking; note("agent speaking: $speaking") }
        }
        val call = VoiceCallSession(
            CallSessionConfig(base, key, session.agentId, session.webcallAgentId, session.wsHost,
                callVariables = mapOf("who_called" to "customer", "call_reason" to "sdk_e2e_test"), sdkVariables = emptyMap()),
            deps, events,
        )
        call.start()

        assertTrue("the socket opened", opened.await(25, java.util.concurrent.TimeUnit.SECONDS) && ended.isEmpty())
        note("transport: $transport")

        // The welcome: agent audio, decoded; played out; its marks acked.
        assertTrue("the welcome arrived as agent audio", waitFor(20_000) { audio.frames.get() > 0 })
        waitFor(30_000) { speakingLog.any { it.second } && speakingLog.lastOrNull()?.second == false }
        val welcomeFrames = audio.frames.get()
        val decoded = AgentAudio.decode(firstAudio!!, null, null, null)!!
        note("first agent frame: ${firstAudio!!.size} bytes, decoded ${decoded.kind.wire} at ${decoded.sampleRate} Hz, latched=${decoded.latch != null}")
        val marksIn = received.filter { it.second.optString("type") == "mark" }.map { it.second.optString("name") }
        val acks = sent.filter { it.second.optString("type") == "mark" }.map { it.second.optString("name") }
        note("welcome: $welcomeFrames audio frames, marks received ${marksIn.size}, acked ${acks.size}")

        // The question.
        val askedAt = clock()
        audio.speakNow()
        note("speaking the question (${question.size / 16} ms of audio)")
        val turnsBefore = agentTurns.size
        val framesBefore = audio.frames.get()
        val replied = waitFor(45_000) {
            audio.questionEndedAt != Long.MAX_VALUE &&
                received.any { it.first > audio.questionEndedAt - t0 && it.second.optString("type") == "text" } &&
                agentTurns.size > turnsBefore
        }
        val replyTextAt = clock() - t0
        note("reply text complete; waiting for its voice")
        // Its voice follows its text: wait for that answer's audio to arrive, be heard, and for speech to end.
        val replyAudio = waitFor(20_000) { received.any { it.second.optString("type") == "audio" && it.first >= replyTextAt - 50 } }
        waitFor(60_000) { speakingLog.lastOrNull()?.let { !it.second && it.first > replyTextAt } == true }
        waitFor(3_000) { received.count { it.second.optString("type") == "mark" } == sent.count { it.second.optString("type") == "mark" } }
        val replyFrames = audio.frames.get() - framesBefore
        call.hangUp()
        Thread.sleep(1_500)

        val allMarks = received.filter { it.second.optString("type") == "mark" }
        val allAcks = sent.filter { it.second.optString("type") == "mark" }
        val delays = allAcks.mapNotNull { ack -> allMarks.firstOrNull { it.second.optString("name") == ack.second.optString("name") }?.let { ack.first - it.first } }
        val types = received.groupingBy { it.second.optString("type") }.eachCount()
        val sentTypes = sent.groupingBy { it.second.optString("type") }.eachCount()

        File("build/e2e").mkdirs()
        writeWav(File("build/e2e/agent-24k.wav"), audio.recorded.toByteArray(), AgentAudio.PCM_RATE)
        val summary = buildString {
            appendLine("── LIVE NATIVE CALL (JVM) ─────────────────────────────")
            appendLine("transport        $transport")
            appendLine("agent audio      ${decoded.kind.wire} @ ${decoded.sampleRate} Hz (rates played: ${audio.rates}), ${audio.frames.get()} frames, ${"%.1f".format(audio.samples.get() / 24_000.0)} s")
            appendLine("welcome          $welcomeFrames frames before the question")
            appendLine("reply            $replyFrames frames after the question; replied=$replied")
            appendLine("marks            received ${allMarks.size}, acked ${allAcks.size}, every ack names a received mark: ${allAcks.all { a -> allMarks.any { it.second.optString("name") == a.second.optString("name") } }}")
            appendLine("ack delays (ms)  ${delays.take(12)}${if (delays.size > 12) " …" else ""}")
            appendLine("frames in        $types")
            appendLine("frames out       $sentTypes")
            appendLine("agent turns      ${agentTurns.mapIndexed { i, t -> "${i + 1}. \"$t\"" }}")
            appendLine("customer         $customer")
            appendLine("speaking         ${speakingLog.map { "${it.first}ms=${it.second}" }}")
            appendLine("ended            $ended (asked at ${askedAt - t0} ms)")
            appendLine("recording        build/e2e/agent-24k.wav")
            appendLine("timeline (frames in, without audio and marks):")
            for ((t, o) in received) {
                val type = o.optString("type")
                if (type == "audio" || type == "mark") continue
                appendLine("  ${t} ms  $type ${o.opt("data")?.toString()?.take(100)?.let { "\"$it\"" } ?: ""}")
            }
            val audioTimes = received.filter { it.second.optString("type") == "audio" }.map { it.first }
            appendLine("  agent audio frames at (ms): $audioTimes")
            for ((t, _) in sent.filter { it.second.optString("type") == "barge_in" }) {
                val lastAudio = audioTimes.lastOrNull { it <= t }
                val speakingThen = speakingLog.lastOrNull { it.first <= t }?.second
                appendLine("  barge_in sent at $t ms: last agent audio arrived at $lastAudio ms (${lastAudio?.let { t - it }} ms before), agent speaking (playout)=$speakingThen")
            }
        }
        println(summary)
        File("build/e2e/summary.txt").writeText(summary + "\n" + log.joinToString("\n"))

        assertTrue("a welcome arrived before the question", welcomeFrames > 0)
        assertTrue("marks were received", allMarks.isNotEmpty())
        assertTrue("marks were acked", allAcks.isNotEmpty())
        assertTrue("every ack names a mark the agent sent", allAcks.all { a -> allMarks.any { it.second.optString("name") == a.second.optString("name") } })
        assertTrue("the init frame went first", sent.firstOrNull()?.second?.optString("type") == "init")
        assertEquals("graine-android-sdk", sent.first().second.getJSONObject("meta_data").getString("client"))
        assertTrue("the agent replied to the question (text or audio)", replied || replyFrames > 0)
        note("reply: text=$replied, audio=$replyAudio ($replyFrames frames)")
        assertTrue("hung up with stop", sent.any { it.second.optString("type") == "stop" })
    }

    private fun writeWav(f: File, pcm: ByteArray, rate: Int) {
        val out = java.io.ByteArrayOutputStream()
        fun le32(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff); out.write((v shr 16) and 0xff); out.write((v shr 24) and 0xff) }
        fun le16(v: Int) { out.write(v and 0xff); out.write((v shr 8) and 0xff) }
        out.write("RIFF".toByteArray()); le32(36 + pcm.size); out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray()); le32(16); le16(1); le16(1); le32(rate); le32(rate * 2); le16(2); le16(16)
        out.write("data".toByteArray()); le32(pcm.size); out.write(pcm)
        f.writeBytes(out.toByteArray())
    }
}
