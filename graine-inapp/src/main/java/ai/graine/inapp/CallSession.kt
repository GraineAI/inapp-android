package ai.graine.inapp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit

/**
 * The call's microphone and speaker, behind an interface: AndroidCallAudio
 * on a phone, a fake in the tests and the live end-to-end run.
 */
internal interface CallAudioIO {
    /** Start the microphone. [onFrame] gets every 20 ms frame — 320 samples of 16 kHz mono PCM16 — on the capture thread. */
    fun startCapture(onFrame: (ShortArray) -> Unit)
    /** Queue the agent's speech; the player rules decide when it plays. */
    fun play(pcm: ShortArray, sampleRate: Int)
    /** Drop everything queued and unplayed, now. */
    fun clear()
    /** Seconds of agent audio still to be heard: exact, and zero once drained. */
    fun bufferedSeconds(): Double
    /** Stop the microphone and the speaker and release both. Safe to call twice. */
    fun stop()
}

/** Where the session's work runs: one thread, a clock, timers. A hand-turned clock in the tests. */
internal interface CallScheduler {
    fun now(): Long
    fun post(task: () -> Unit)
    /** Run [task] after [delayMs]; the returned function cancels it. */
    fun schedule(delayMs: Long, task: () -> Unit): () -> Unit
}

/** One daemon thread for every call's session work; it lets itself go when there is nothing to do. */
internal class ExecutorScheduler(name: String) : CallScheduler {
    private val executor = ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }).apply {
        setKeepAliveTime(30, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }

    override fun now(): Long = System.nanoTime() / 1_000_000

    override fun post(task: () -> Unit) {
        executor.execute { runCatching(task).onFailure { CallLog.error("call task failed", it) } }
    }

    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val f = executor.schedule({ runCatching(task).onFailure { CallLog.error("call timer failed", it) } }, Math.max(0, delayMs), TimeUnit.MILLISECONDS)
        return { f.cancel(false) }
    }
}

/** An HTTP POST of JSON — HttpURLConnection on a phone, a fake in the tests. Throws on a network error. */
internal fun interface HttpPost {
    fun post(url: String, json: String): HttpReply
}

internal data class HttpReply(val code: Int, val body: String)

/** The platform's HTTP, as the session fetch does it: no Origin header, short timeouts. */
internal object PlatformHttp : HttpPost {
    override fun post(url: String, json: String): HttpReply {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpReply(code, body)
        } finally {
            conn.disconnect()
        }
    }
}

/** Debug logging for the call path — logcat on a phone (only when GraineConfig.debug), nothing in JVM tests. */
internal object CallLog {
    @Volatile var enabled = false
    @Volatile var sink: ((String, Throwable?) -> Unit)? = null

    fun debug(message: String) { if (enabled) sink?.invoke(message, null) }
    fun error(message: String, t: Throwable? = null) { sink?.invoke(message, t) }
}

/** What a session needs to know about this call. */
internal data class CallSessionConfig(
    val baseUrl: String,
    val publishableKey: String,
    val agentId: String,
    val webcallAgentId: String?,
    val wsHost: String?,
    /** The call's own variables (who_called, call_reason, the push's) — they win. */
    val callVariables: Map<String, String>,
    /** GraineInApp.currentVariables() — the dashboard's, the build's, the app's, the signed-in customer. */
    val sdkVariables: Map<String, String>,
)

/** What a session reports, on its scheduler thread. */
internal interface CallSessionEvents {
    /** The socket is open and the microphone is live. */
    fun onOpen() {}
    /** It never opened. */
    fun onFailed(error: String) {}
    /** It was open and is over: the agent hung up (REMOTE) or the connection broke (FAILED). */
    fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) {}
    fun onCaption(role: Role, text: String, live: Boolean) {}
    fun onAgentSpeaking(speaking: Boolean) {}
}

/** Everything outside the session, injected. */
internal class CallSessionDeps(
    val scheduler: CallScheduler,
    val audio: CallAudioIO,
    val http: HttpPost = PlatformHttp,
    val openSocket: (url: String, protocols: List<String>, listener: CallSocketListener) -> CallSocket =
        { url, protocols, listener -> WebSocketClient(url, protocols, listener).connect() },
    /** The SDK's action runner (GraineInApp.executeAction): always answers, on any thread. */
    val runAction: (name: String, args: JSONObject, answer: (ActionResult) -> Unit) -> Unit =
        { _, _, answer -> answer(ActionResult.refused("This app build does not implement actions.")) },
    /** The SDK's context frame (GraineInApp.currentContextFrame), or null. */
    val contextFrame: () -> JSONObject? = { null },
    /** Where blocking work (the ticket) runs. */
    val io: (() -> Unit) -> Unit = { work -> Thread({ work() }, "graine-call-ticket").apply { isDaemon = true }.start() },
)

/**
 * ONE NATIVE CALL ON THE VOICE SOCKET — sdk/NATIVE-CALLS.md "The wire protocol".
 *
 *   ticket (or the direct URL) → socket with context_token → init →
 *   app_context → ping every 25 s, the microphone up through the echo guard,
 *   the agent's audio down through the decoder and the player, marks acked
 *   when heard, actions answered within 6 s, and stop + close to hang up.
 *
 * Every piece of state lives on [CallSessionDeps.scheduler]'s thread: socket
 * callbacks, microphone frames and the public methods all hop onto it.
 */
internal class VoiceCallSession(
    private val config: CallSessionConfig,
    private val deps: CallSessionDeps,
    private val events: CallSessionEvents,
) {
    private enum class Phase { NEW, CONNECTING, OPEN, CLOSED }

    private val scheduler get() = deps.scheduler
    private val audio get() = deps.audio

    /** Every variable the agent receives — the call's own first, winning. */
    val variables: Map<String, String> = CallVariables.merged(config.callVariables, config.sdkVariables)

    private var phase = Phase.NEW
    private var socket: CallSocket? = null
    private var openTimer: (() -> Unit)? = null
    private var pingTimer: (() -> Unit)? = null

    /** What this call's agent audio turned out to be — decided once, never per frame. */
    private var format: AgentAudioFormat? = null
    private val echo = EchoGuard<ShortArray>()
    private var lastAudioAt = Long.MIN_VALUE / 4
    /**
     * When the agent audio received so far is due to have been HEARD: its
     * arrival plus what the player held then. Production sends a reply far
     * faster than real time (6.3 s of speech within 0.8 s), so recency from the
     * arrival alone declared the agent silent with seconds still playing.
     */
    private var audibleUntil = Long.MIN_VALUE / 4
    private var speaking = false
    private var speakingTimer: (() -> Unit)? = null
    /** One barge_in per agent turn. */
    private var bargedIn = false
    private var muted = false
    /** The agent's caption turn so far, its phrases joined (spokenJoinGap). */
    private var spokenTurn = ""

    /** The agent's voice is being heard (playout-based). Read on the scheduler thread. */
    val agentSpeaking: Boolean get() = speaking

    fun start() = scheduler.post {
        if (phase != Phase.NEW) return@post
        phase = Phase.CONNECTING
        deps.io {
            val ticket = fetchTicket()
            scheduler.post { connect(ticket) }
        }
    }

    /** Hang up: the stop frame, then the close ~200 ms later. Audio is released now. No event follows. */
    fun hangUp() = scheduler.post {
        if (phase == Phase.CLOSED) return@post
        val wasOpen = phase == Phase.OPEN
        phase = Phase.CLOSED
        teardown()
        val s = socket ?: return@post
        if (wasOpen) {
            s.send(CallFrames.STOP)
            scheduler.schedule(CallFrames.CLOSE_AFTER_STOP_MS) { s.close(1000, "") }
        } else {
            s.close(1000, "")
        }
    }

    /** Muted: frames are dropped before they are sent; the microphone keeps running. */
    fun setMuted(value: Boolean) = scheduler.post { muted = value }

    /** A newer screen while the call is up. */
    fun sendContext(frame: JSONObject) = scheduler.post { if (phase == Phase.OPEN) send(CallFrames.appContext(frame)) }

    // ── connecting ──────────────────────────────────────────────────────────

    private class Ticket(val ticket: String, val wsUrl: String)

    /** A fresh ticket — the gateway burns them. Null on any failure: the direct URL is used. */
    private fun fetchTicket(): Ticket? = try {
        val body = JSONObject().put("publishableKey", config.publishableKey).put("mode", "voice")
        CallVariables.ticketVariables(config.callVariables, config.sdkVariables)?.let { body.put("metadata", JSONObject(it as Map<*, *>)) }
        val reply = deps.http.post("${config.baseUrl.trimEnd('/')}/api/embed/ticket", body.toString())
        if (reply.code !in 200..299) {
            CallLog.debug("no ticket (HTTP ${reply.code}); using the direct socket")
            null
        } else {
            val o = JSONObject(reply.body)
            val t = o.optString("ticket")
            val u = o.optString("wsUrl")
            if (t.isNotEmpty() && u.isNotEmpty()) Ticket(t, u) else null
        }
    } catch (e: Exception) {
        CallLog.debug("no ticket (${e.javaClass.simpleName}); using the direct socket")
        null
    }

    private fun connect(ticket: Ticket?) {
        if (phase != Phase.CONNECTING) return
        val url: String
        val protocols: List<String>
        if (ticket != null) {
            url = ticket.wsUrl
            // The ticket rides the subprotocol header, never the query: queries land in access logs.
            protocols = listOf(CallFrames.SUBPROTOCOL, ticket.ticket)
        } else {
            url = CallUrls.direct(config.wsHost, config.webcallAgentId?.takeIf { it.isNotEmpty() } ?: config.agentId)
            protocols = emptyList()
        }
        val full = CallUrls.withToken(url, CallVariables.contextToken(variables, "webcall"))
        openTimer = scheduler.schedule(CallFrames.OPEN_TIMEOUT_MS) {
            if (phase == Phase.CONNECTING) failBeforeOpen("The call could not connect within ${CallFrames.OPEN_TIMEOUT_MS / 1000} seconds.")
        }
        val s = try {
            deps.openSocket(full, protocols, Listener())
        } catch (e: Exception) {
            failBeforeOpen("The call could not connect: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        socket = s
        if (phase != Phase.CONNECTING) s.close(1000, "")
    }

    /** One socket per session, so one listener; anything it says after the session let go is ignored by phase. */
    private inner class Listener : CallSocketListener {
        override fun onOpen(protocol: String?) = scheduler.post { opened() }
        override fun onText(text: String) = scheduler.post { if (phase == Phase.OPEN) onFrame(text) }
        override fun onClosed(code: Int, reason: String) = scheduler.post {
            when (phase) {
                Phase.CONNECTING -> failBeforeOpen("The call was refused (close $code${if (reason.isNotEmpty()) ": $reason" else ""}).")
                Phase.OPEN -> endRemotely(GraineCallOutcome.EndReason.REMOTE, if (code == 1000 || code == 1005) null else "close $code $reason".trim())
                else -> Unit
            }
        }
        override fun onFailure(error: Throwable) = scheduler.post {
            val message = error.message ?: error.javaClass.simpleName
            when (phase) {
                Phase.CONNECTING -> failBeforeOpen("The call could not connect: $message")
                Phase.OPEN -> endRemotely(GraineCallOutcome.EndReason.FAILED, message)
                else -> Unit
            }
        }
    }

    private fun opened() {
        if (phase != Phase.CONNECTING) { socket?.close(1000, ""); return }
        phase = Phase.OPEN
        openTimer?.invoke(); openTimer = null
        send(CallFrames.init(variables))
        runCatching { deps.contextFrame() }.getOrNull()?.let { send(CallFrames.appContext(it)) }
        schedulePing()
        format = null
        echo.reset()
        try {
            audio.startCapture { pcm -> scheduler.post { onMicrophone(pcm) } }
        } catch (e: Exception) {
            // Open, but deaf: nothing the customer says would arrive. End it honestly.
            val s = socket
            phase = Phase.CLOSED
            teardown()
            s?.send(CallFrames.STOP)
            s?.let { scheduler.schedule(CallFrames.CLOSE_AFTER_STOP_MS) { it.close(1000, "") } }
            events.onFailed("The microphone could not start: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        events.onOpen()
    }

    private fun schedulePing() {
        pingTimer = scheduler.schedule(CallFrames.PING_MS) {
            if (phase != Phase.OPEN) return@schedule
            send(CallFrames.PING)
            schedulePing()
        }
    }

    private fun failBeforeOpen(error: String) {
        if (phase != Phase.CONNECTING) return
        phase = Phase.CLOSED
        teardown()
        socket?.close(1000, "")
        events.onFailed(error)
    }

    private fun endRemotely(reason: GraineCallOutcome.EndReason, error: String?) {
        if (phase != Phase.OPEN) return
        phase = Phase.CLOSED
        teardown()
        events.onEnded(reason, error)
    }

    /** Timers, microphone and speaker — everything but the socket. */
    private fun teardown() {
        openTimer?.invoke(); openTimer = null
        pingTimer?.invoke(); pingTimer = null
        speakingTimer?.invoke(); speakingTimer = null
        runCatching { audio.clear() }
        runCatching { audio.stop() }
        if (speaking) { speaking = false; events.onAgentSpeaking(false) }
        spokenTurn = ""
    }

    private fun send(text: String): Boolean = socket?.send(text) ?: false

    // ── the agent → the customer ────────────────────────────────────────────

    private fun onFrame(raw: String) {
        when (val f = Inbound.parse(raw) ?: return) {
            is Inbound.Audio -> onAgentAudio(f)
            is Inbound.Mark -> {
                // Acked when the audio queued before it has been HEARD: the runtime reads
                // the ack as "playback ended" — it clears speaking and records the welcome.
                val wait = CallFrames.markAckDelayMs(audio.bufferedSeconds())
                val ack = CallFrames.markAck(f.name)
                if (wait <= 0) send(ack) else scheduler.schedule(wait) { if (phase == Phase.OPEN) send(ack) }
            }
            is Inbound.Text -> onAgentText(f.data)
            is Inbound.Transcript -> if (f.text.isNotEmpty()) events.onCaption(Role.CUSTOMER, f.text, !f.final)
            is Inbound.Action -> runAction(f)
            // Never sent on this path today; if they come, they bound a caption turn. Speech follows the audio.
            Inbound.SpeechStart, Inbound.SpeechEnd -> endCaptionTurn()
            Inbound.Clear -> {
                // The customer talked over the agent: what is queued must not play out behind them.
                audio.clear()
                speakingTimer?.invoke(); speakingTimer = null
                setSpeaking(false)
                endCaptionTurn()
            }
            is Inbound.Ignored -> Unit
        }
    }

    private fun onAgentAudio(f: Inbound.Audio) {
        agentAudioArrived()
        val bytes = B64.decode(f.data)
        val decoded = AgentAudio.decode(bytes, f.format, f.sampleRate, format) ?: return
        decoded.latch?.let { format = it }
        if (decoded.latch == null && AgentAudio.isAllZero(bytes)) {
            // Digital silence decides nothing, and its mu-law reading is a full-scale DC step
            // lasting six times the frame's real length. In PCM it is what it is: silence.
            audio.play(ShortArray(bytes.size / 2), f.sampleRate?.takeIf { it > 0 } ?: AgentAudio.PCM_RATE)
        } else {
            audio.play(decoded.pcm16, decoded.sampleRate)
        }
        // Queued: the agent stays audible until what the player now holds has been heard.
        audibleUntil = scheduler.now() + Math.round(audio.bufferedSeconds() * 1000)
    }

    /**
     * THE AGENT IS SPEAKING WHILE ITS VOICE PLAYS (client.ts agentAudioArrived).
     * True on the first audio; false once what was queued has been heard and
     * no new audio came for 300 ms. The server sends no speech start or end
     * on this path — `<end_of_stream>` ends the TEXT while the audio runs on.
     */
    private fun agentAudioArrived() {
        lastAudioAt = scheduler.now()
        setSpeaking(true)
        scheduleSpeakingEnd(CallFrames.SPEAKING_TAIL_MS)
    }

    private fun scheduleSpeakingEnd(delayMs: Long) {
        speakingTimer?.invoke()
        speakingTimer = scheduler.schedule(delayMs) {
            speakingTimer = null
            if (!speaking || phase != Phase.OPEN) return@schedule
            val quietMs = scheduler.now() - lastAudioAt
            val buffered = audio.bufferedSeconds()
            if (quietMs >= CallFrames.SPEAKING_TAIL_MS && buffered <= CallFrames.AUDIBLE_BUFFER_SECONDS) {
                setSpeaking(false)
                return@schedule
            }
            // Still audible: look again when what is queued should have played.
            scheduleSpeakingEnd(Math.min(1_000L, maxOf(50L, Math.ceil(buffered * 1000).toLong(), CallFrames.SPEAKING_TAIL_MS - quietMs)))
        }
    }

    private fun setSpeaking(value: Boolean) {
        if (speaking == value) return
        speaking = value
        events.onAgentSpeaking(value)
    }

    /** Phrases joined with their spaces; `<end_of_stream>` closes the line. */
    private fun onAgentText(data: Any?) {
        val chunk = CallText.readAgentChunk(data)
        var words = chunk.text
        if (words.isNotEmpty()) {
            words = CallText.spokenJoinGap(spokenTurn, words) + words
            spokenTurn += words
            events.onCaption(Role.AGENT, spokenTurn, true)
        }
        if (chunk.endsTurn) endCaptionTurn()
    }

    private fun endCaptionTurn() {
        if (spokenTurn.isNotEmpty()) events.onCaption(Role.AGENT, spokenTurn, false)
        spokenTurn = ""
    }

    // ── the customer → the agent ────────────────────────────────────────────

    /**
     * Is the agent's voice reaching the speaker right now? The echo guard's
     * input: within 1.5 s of when the agent audio received so far is due to
     * have been heard (`audibleUntil`, never before its arrival) AND the
     * player still holds some or the agent is speaking. It gates the
     * microphone, so it fails OPEN: a stalled player cannot move
     * `audibleUntil` — it is set only when audio arrives — so 1.5 s past the
     * projected end the customer is always heard, whatever any latch says.
     */
    private fun agentAudible(now: Long): Boolean {
        if (now - maxOf(lastAudioAt, audibleUntil) > CallFrames.AGENT_AUDIBLE_RECENCY_MS) return false
        return audio.bufferedSeconds() > CallFrames.AUDIBLE_BUFFER_SECONDS || speaking
    }

    private fun onMicrophone(pcm: ShortArray) {
        if (phase != Phase.OPEN) return
        val frameMs = pcm.size * 1000.0 / AgentAudio.CAPTURE_RATE
        for (frame in echo.filter(pcm, pcm, agentAudible(scheduler.now()), frameMs)) sendMicrophone(frame)
    }

    private fun sendMicrophone(frame: ShortArray) {
        if (muted) return
        // Once per agent turn, immediately before the first frame released over the agent's voice.
        if (speaking && !bargedIn) {
            bargedIn = true
            send(CallFrames.BARGE_IN)
        } else if (!speaking) {
            bargedIn = false
        }
        send(CallFrames.audio(B64.encode(AgentAudio.toLittleEndian(frame))))
    }

    // ── the agent's actions ─────────────────────────────────────────────────

    /** Run it through the SDK's runner and answer within 6 s on every path — the runtime is blocked on it. */
    private fun runAction(a: Inbound.Action) {
        var answered = false
        var deadline: (() -> Unit)? = null
        fun answer(r: ActionResult) {
            if (answered) return
            answered = true
            deadline?.invoke()
            if (phase == Phase.OPEN) send(CallFrames.actionResult(a.actionId, r))
        }
        deadline = scheduler.schedule(CallFrames.ACTION_ANSWER_BY_MS) {
            answer(ActionResult.error("\"${a.name}\" did not finish in time."))
        }
        try {
            deps.runAction(a.name, a.arguments) { r -> scheduler.post { answer(r) } }
        } catch (e: Exception) {
            answer(ActionResult.error("\"${a.name}\" failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }
}
