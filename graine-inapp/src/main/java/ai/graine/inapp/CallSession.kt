package ai.graine.inapp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The call's microphone and speaker, behind an interface: AndroidCallAudio
 * on a phone, a fake in the tests and the live end-to-end run.
 */
internal interface CallAudioIO {
    /**
     * Start the microphone. [onFrame] gets every 20 ms frame — 320 samples of
     * 16 kHz mono PCM16 — on the capture thread. [onFailed] runs at most once,
     * on any thread, when the microphone stops mid-call and cannot be brought
     * back: from then on nothing the customer says is captured.
     */
    fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit)
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

/** [retryAfterSeconds]: the `Retry-After` header in seconds, when the server sent one. */
internal data class HttpReply(val code: Int, val body: String, val retryAfterSeconds: Long? = null)

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
            return HttpReply(code, body, conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
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
    /** Who is using the app, for the ticket (contract §1.3); null sends none. */
    val identity: IdentityEnvelope? = null,
    /** The call's id on the ticket, so its record links back to the ring and the user (contract §3.6, §3.7). */
    val callId: String? = null,
    /**
     * When the customer tapped — placed the call, or answered its ring — on
     * the session scheduler's clock: the analytics rows (CallAnalytics) count
     * from it, a WebRTC attempt before the WebSocket included.
     */
    val tapAt: Long? = null,
)

/** What a session reports, on its scheduler thread. */
internal interface CallSessionEvents {
    /** The socket is open and the microphone is live. */
    fun onOpen() {}
    /** It never opened. */
    fun onFailed(error: String) {}
    /** It was open and is over: the agent hung up (REMOTE), or the connection broke or the microphone stopped (FAILED). */
    fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) {}
    fun onCaption(role: Role, text: String, live: Boolean) {}
    fun onAgentSpeaking(speaking: Boolean) {}
    /**
     * The far side's audio as measured: its level now (0–1), and on WebRTC its
     * energy so far and over how long. The WebSocket reports the level of the
     * agent's audio it decoded, as it is heard.
     */
    fun onAgentAudio(level: Double?, energy: Double?, seconds: Double?) {}
    /**
     * The call's media path is being recovered after the call was up (true),
     * or is back (false). WebRTC only: the WebSocket holds one socket for the
     * whole call and never reconnects inside it.
     */
    fun onReconnecting(reconnecting: Boolean) {}
    /**
     * An analytics row (CallAnalytics) was made, with the input it was made
     * from — for the call's connection report — or, as a [CallAnalytics.QUALITY],
     * a reading of the audio so far while a WebRTC call is live.
     */
    fun onAnalytics(name: String, data: Map<String, Any?>) {}
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
    /** The call's agent's server tokens: the ticket carries one when they are in play (CLIENT-TOKENS.md); null sends none. */
    val tokens: ClientTokens? = null,
)

/**
 * The call's analytics rows on the voice socket (CallAnalytics), from the
 * conversation that opened it: what the app asked for ("auto", "rtc", "ws"),
 * why WebRTC is not carrying the call (null when it was never meant to), and
 * the rows a WebRTC attempt left before it fell back.
 */
internal class SocketRows(val requested: String?, val fallbackReason: String?, val carried: List<String> = emptyList())

/**
 * ONE NATIVE CALL ON THE VOICE SOCKET — sdk/NATIVE-CALLS.md "The wire protocol".
 *
 *   ticket (or the direct URL, with context_token) → socket → init →
 *   app_context → ping every 25 s, the microphone up through the echo guard,
 *   the agent's audio down through the decoder and the player, marks acked
 *   when heard, actions answered within 6 s, and stop + close to hang up.
 *   Nothing arriving for two ping periods ends the call as dropped.
 *
 * One socket carries the whole call: a close or a failure once it is open
 * ends the call, and nothing reconnects inside it — so the call screen's
 * reconnecting moment is WebRTC's alone. The agent's voice level is measured
 * here, from the audio decoded for the player (HeardLevels), and reported as
 * it is heard.
 *
 * With [rows], the call says how it connected (CallAnalytics): the transport
 * and where the tap's wait went once the conversation starts — with whatever a
 * WebRTC attempt before it left — and the agent's voice when it is first heard.
 * Nothing is sent before the socket opens, and nothing waits for a row.
 *
 * Every piece of state lives on [CallSessionDeps.scheduler]'s thread: socket
 * callbacks, microphone frames and the public methods all hop onto it — but
 * for the audio a hang-up releases at once, and sends, which never block it
 * (the socket's writer carries them).
 */
internal class VoiceCallSession(
    private val config: CallSessionConfig,
    private val deps: CallSessionDeps,
    private val events: CallSessionEvents,
    private val rows: SocketRows? = null,
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
    /** When anything last arrived on the socket, and the timer that notices when nothing has for too long. */
    private var lastInboundAt = 0L
    private var livenessTimer: (() -> Unit)? = null
    /** The microphone and the speaker were let go — once, from whichever thread got there first (a hang-up's own). */
    private val audioReleased = AtomicBoolean(false)

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
    /** The agent's voice level as it will be heard — each window of decoded audio, due when it plays. */
    private val heard = HeardLevels()
    private var levelTimer: (() -> Unit)? = null
    /** The level last reported; 0 is reported once when the voice has played out. */
    private var levelShown = 0.0
    /** One barge_in per agent turn. */
    private var bargedIn = false
    private var muted = false
    private var onHold = false
    /** The agent's caption turn so far, its phrases joined (spokenJoinGap). */
    private var spokenTurn = ""

    // The analytics rows: when the ticket was asked for and how long it took, when the socket was asked for and opened.
    private var ticketAskedAt = 0L
    private var ticketMs: Long? = null
    private var socketAt = 0L
    private var openedAt = 0L
    private var firstAudioAt: Long? = null
    private var voiceTold = false
    /** How the call ended has been told ([endedRow]). */
    private var endedTold = false

    /** The agent's voice is being heard (playout-based). Read on the scheduler thread. */
    val agentSpeaking: Boolean get() = speaking

    fun start() = scheduler.post {
        if (phase != Phase.NEW) return@post
        phase = Phase.CONNECTING
        ticketAskedAt = scheduler.now()
        deps.io {
            val ticket = fetchTicket()
            scheduler.post { connect(ticket) }
        }
    }

    /**
     * Hang up: the microphone and the speaker are released NOW, on the
     * caller's thread — a hang-up never waits behind whatever the session's
     * thread is doing — then the stop frame, and the close ~200 ms later (the
     * socket forces it if the line cannot carry it within a second). How it
     * ended goes before the stop frame ([cause]: the app or the user ended it,
     * unless the app says why else). No event follows.
     */
    fun hangUp(cause: String = CallEnd.LOCAL_END) {
        releaseAudio()
        scheduler.post {
            if (phase == Phase.CLOSED) return@post
            val wasOpen = phase == Phase.OPEN
            if (wasOpen) endedRow(cause)
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
    }

    /** Release the microphone and the speaker, once. Any thread. */
    private fun releaseAudio() {
        if (audioReleased.compareAndSet(false, true)) runCatching { audio.stop() }
    }

    /** Muted: frames are dropped before they are sent; the microphone keeps running. */
    fun setMuted(value: Boolean) = scheduler.post { muted = value }

    /**
     * On hold: the phone took the call's audio — a phone call ringing, another
     * app's call or player. Nothing the customer says is sent and nothing the
     * agent says is played, and what was queued is dropped, until it comes back.
     */
    fun setOnHold(value: Boolean) = scheduler.post {
        if (onHold == value) return@post
        onHold = value
        if (value) {
            runCatching { audio.clear() }
            levelsCleared()
        }
    }

    /** A newer screen while the call is up. */
    fun sendContext(frame: JSONObject) = scheduler.post { if (phase == Phase.OPEN) send(CallFrames.appContext(frame)) }

    // ── connecting ──────────────────────────────────────────────────────────

    private class Ticket(val ticket: String, val wsUrl: String)

    /**
     * Graine refused the ticket — for this app (EmbedRequest.appRefusal), or for
     * the server token or the key (ClientTokenRules.refusal): the call fails with
     * it, and never tries the direct socket instead. Written on the ticket's
     * thread before the scheduler hears of it.
     */
    @Volatile private var refused: GraineError? = null

    /**
     * A fresh ticket — each is single-use — through the one door (the app's
     * declaration and proofs, the user's token and pass), carrying the server
     * token when tokens are in play (a new connection: [ClientTokenWire]). Null
     * on any failure: the direct URL is used — except after a refusal
     * ([refused]), which ends the call before it starts.
     */
    private fun fetchTicket(): Ticket? = try {
        val body = JSONObject().put("publishableKey", config.publishableKey).put("mode", "voice")
        CallVariables.ticketVariables(config.callVariables, config.sdkVariables)?.let { body.put("metadata", JSONObject(it as Map<*, *>)) }
        // Beside the metadata, never in it: the 12-key cap must never drop who the user is or which call this is.
        config.identity?.into(body)
        config.callId?.let { body.put("callId", it) }
        val url = "${config.baseUrl.trimEnd('/')}/api/embed/ticket"
        val reply = EmbedRequest.send(url, body, tokens = deps.tokens, newConnection = true) { json -> deps.http.post(url, json) }
        if (reply.code !in 200..299) {
            refused = EmbedRequest.appRefusal(reply) ?: ClientTokenRules.refusal(reply.code, reply.body)
            CallLog.debug(refused?.let { "the ticket was refused (${it.code.wire})" } ?: "no ticket (HTTP ${reply.code}); using the direct socket")
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
        // Refused for this app, the token or the key: the call cannot start, and the direct socket is not another way past it.
        refused?.let { e ->
            failBeforeOpen(ClientTokenRules.callError(e))
            return
        }
        if (ticket != null) ticketMs = scheduler.now() - ticketAskedAt
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
        // The variables on the direct socket's URL only: the gateway builds the call's context from the
        // ticket's signed claims and reads nothing on its URL — a token there would only reach access logs.
        val full = if (ticket != null) url else CallUrls.withToken(url, CallVariables.contextToken(variables, "webcall"))
        openTimer = scheduler.schedule(CallFrames.OPEN_TIMEOUT_MS) {
            if (phase == Phase.CONNECTING) failBeforeOpen("The call could not connect within ${CallFrames.OPEN_TIMEOUT_MS / 1000} seconds.")
        }
        socketAt = scheduler.now()
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
        override fun onText(text: String) = scheduler.post {
            if (phase != Phase.OPEN) return@post
            lastInboundAt = scheduler.now()
            onFrame(text)
        }
        override fun onClosed(code: Int, reason: String) = scheduler.post {
            when (phase) {
                Phase.CONNECTING -> failBeforeOpen("The call was refused (close $code${if (reason.isNotEmpty()) ": $reason" else ""}).")
                Phase.OPEN -> endRemotely(GraineCallOutcome.EndReason.REMOTE, if (code == 1000 || code == 1005) null else "close $code $reason".trim(), CallEnd.REMOTE_BYE)
                else -> Unit
            }
        }
        override fun onFailure(error: Throwable) = scheduler.post {
            val message = error.message ?: error.javaClass.simpleName
            when (phase) {
                Phase.CONNECTING -> failBeforeOpen("The call could not connect: $message")
                Phase.OPEN -> endRemotely(GraineCallOutcome.EndReason.FAILED, message, CallEnd.FAILED)
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
        openedAt = scheduler.now()
        openRows()
        schedulePing()
        lastInboundAt = scheduler.now()
        armLiveness(CallFrames.LIVENESS_MS)
        format = null
        echo.reset()
        try {
            audio.startCapture(
                onFrame = { pcm -> scheduler.post { onMicrophone(pcm) } },
                onFailed = { scheduler.post { microphoneStopped() } },
            )
        } catch (e: Exception) {
            // Open, but deaf: nothing the customer says would arrive. End it honestly.
            hangUpFromHere()
            events.onFailed("The microphone could not start: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        // A hang-up on another thread released the audio while it was starting: release it again.
        if (audioReleased.get()) runCatching { audio.stop() }
        events.onOpen()
    }

    /** When the customer tapped, on this clock: as the call says, else when this session was asked to start. */
    private val tapAt: Long get() = config.tapAt?.takeIf { it <= ticketAskedAt } ?: ticketAskedAt

    /**
     * THE CONVERSATION STARTED: the transport row — the WebSocket, what the app
     * asked for, and why WebRTC is not carrying it — then where the tap's wait
     * went (a socket's call is never prepared: setup is tap → the socket asked
     * for, its ticket inside; dial is → open), then what a WebRTC attempt
     * before it left.
     */
    private fun openRows() {
        val r = rows ?: return
        row(CallAnalytics.TRANSPORT, mapOf("transport" to "ws", "requested" to r.requested, "fallback_reason" to r.fallbackReason))
        row(CallAnalytics.CONNECT, mapOf(
            "mode" to "cold",
            "warm" to false,
            "total_ms" to openedAt - tapAt,
            "setup_ms" to socketAt - tapAt,
            "dial_ms" to openedAt - socketAt,
            "mint_ms" to ticketMs,
            "mint_route" to ticketMs?.let { "web" },
        ))
        for (f in r.carried) send(f)
    }

    /**
     * HOW THE CALL ENDED ([CallAnalytics.ENDED]), once, while the conversation
     * is open: before the stop frame when this side ends it.
     */
    private fun endedRow(cause: String) {
        if (rows == null || endedTold) return
        endedTold = true
        row(CallAnalytics.ENDED, mapOf(
            "cause" to cause,
            "transport" to "ws",
            "after_ms" to scheduler.now() - openedAt,
            "voice_heard" to voiceTold,
        ))
    }

    /** One analytics row on the socket, and its input to the call's connection report. */
    private fun row(name: String, data: Map<String, Any?>) {
        CallAnalytics.frame(name, data)?.let { send(it) }
        events.onAnalytics(name, data)
    }

    /** The agent's first audio, voice or not; then its voice — the first audio that is not digital silence — said once. */
    private fun voiceRow(silence: Boolean, kind: AgentAudioFormat.Kind, sampleRate: Int) {
        if (rows == null || voiceTold) return
        val now = scheduler.now()
        val first = firstAudioAt ?: now.also { firstAudioAt = it }
        if (silence) return
        voiceTold = true
        row(CallAnalytics.VOICE, mapOf(
            "tap_to_voice_ms" to now - tapAt,
            "answer_to_voice_ms" to now - openedAt,
            "first_audio_ms" to first - openedAt,
            "codec" to "${if (kind == AgentAudioFormat.Kind.MULAW) "PCMU" else "L16"}/$sampleRate",
        ))
    }

    /**
     * NOTHING HAS ARRIVED FOR TWO PING PERIODS. A live line answers every
     * ping with a pong, so this is a line that is gone — a network that died
     * without a reset, where TCP would go on believing for many minutes, the
     * customer hearing silence and the microphone still open. Checked once a
     * period, against the last frame that arrived.
     */
    private fun armLiveness(delayMs: Long) {
        livenessTimer = scheduler.schedule(delayMs) {
            livenessTimer = null
            if (phase != Phase.OPEN) return@schedule
            val quietMs = scheduler.now() - lastInboundAt
            if (quietMs < CallFrames.LIVENESS_MS) {
                armLiveness(CallFrames.LIVENESS_MS - quietMs)
                return@schedule
            }
            val s = socket
            endRemotely(GraineCallOutcome.EndReason.FAILED, "Nothing arrived for ${CallFrames.LIVENESS_MS / 1000} seconds: the connection was lost.", CallEnd.AUDIO_WATCH)
            // Closed for good: the socket forces it if the dead line cannot carry the close.
            s?.close(1000, "")
        }
    }

    /**
     * The microphone stopped mid-call and could not be brought back. The agent
     * would go on talking to someone it can no longer hear, and the customer
     * would think it was ignoring them: hang up, and say why.
     */
    private fun microphoneStopped() {
        if (phase != Phase.OPEN) return
        endedRow(CallEnd.FAILED)
        hangUpFromHere()
        events.onEnded(GraineCallOutcome.EndReason.FAILED, "The microphone stopped working.")
    }

    /** This side cannot go on: everything released, then the stop frame and, ~200 ms later, the close. */
    private fun hangUpFromHere() {
        val s = socket
        phase = Phase.CLOSED
        teardown()
        s?.send(CallFrames.STOP)
        s?.let { scheduler.schedule(CallFrames.CLOSE_AFTER_STOP_MS) { it.close(1000, "") } }
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

    private fun endRemotely(reason: GraineCallOutcome.EndReason, error: String?, cause: String) {
        if (phase != Phase.OPEN) return
        // Told on a line that may be gone: the call's report hears it either way.
        endedRow(cause)
        phase = Phase.CLOSED
        teardown()
        events.onEnded(reason, error)
    }

    /** Timers, microphone and speaker — everything but the socket. */
    private fun teardown() {
        openTimer?.invoke(); openTimer = null
        pingTimer?.invoke(); pingTimer = null
        livenessTimer?.invoke(); livenessTimer = null
        speakingTimer?.invoke(); speakingTimer = null
        levelTimer?.invoke(); levelTimer = null
        heard.clear()
        levelShown = 0.0
        runCatching { audio.clear() }
        releaseAudio()
        if (speaking) { speaking = false; events.onAgentSpeaking(false) }
        spokenTurn = ""
    }

    private fun send(text: String): Boolean = socket?.send(text) ?: false

    /** The microphone's audio: the one frame the socket may drop when the line falls behind. */
    private fun sendAudio(text: String): Boolean = socket?.sendDroppable(text) ?: false

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
                levelsCleared()
                speakingTimer?.invoke(); speakingTimer = null
                setSpeaking(false)
                endCaptionTurn()
            }
            is Inbound.Ignored -> Unit
        }
    }

    private fun onAgentAudio(f: Inbound.Audio) {
        if (onHold) return
        agentAudioArrived()
        val bytes = B64.decode(f.data)
        val decoded = AgentAudio.decode(bytes, f.format, f.sampleRate, format) ?: return
        decoded.latch?.let { format = it }
        val zero = AgentAudio.isAllZero(bytes)
        // It starts to be heard when what the player already holds has played.
        val startsAt = scheduler.now() + Math.round(audio.bufferedSeconds() * 1000)
        if (decoded.latch == null && zero) {
            // Digital silence decides nothing, and its mu-law reading is a full-scale DC step
            // lasting six times the frame's real length. In PCM it is what it is: silence.
            val rate = f.sampleRate?.takeIf { it > 0 } ?: AgentAudio.PCM_RATE
            val silence = ShortArray(bytes.size / 2)
            audio.play(silence, rate)
            heard.add(silence, rate, startsAt)
        } else {
            audio.play(decoded.pcm16, decoded.sampleRate)
            heard.add(decoded.pcm16, decoded.sampleRate, startsAt)
        }
        levelsDue()
        // Its voice is audio that is not digital silence, whatever the frame declared itself to be.
        voiceRow(zero, decoded.kind, decoded.sampleRate)
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

    /**
     * THE VOICE LEVEL, AS IT IS HEARD: at most every HeardLevels.WINDOW_MS
     * while any is queued, the level of the window playing now — its silences
     * too, so a pause inside the voice reads 0 rather than a level gone stale;
     * once the voice has played out, 0, and the ticks stop — a quiet call
     * schedules nothing.
     */
    private fun levelsDue() {
        if (levelTimer == null) levelTimer = scheduler.schedule(0) { levelTick() }
    }

    private fun levelTick() {
        levelTimer = null
        if (phase != Phase.OPEN) return
        val due = heard.due(scheduler.now())
        if (due != null) reportLevel(due)
        else if (heard.isEmpty && levelShown > 0) reportLevel(0.0)
        if (!heard.isEmpty || levelShown > 0) levelTimer = scheduler.schedule(HeardLevels.WINDOW_MS) { levelTick() }
    }

    /** What was queued will not play (a barge-in, a hold): its levels go, and the voice is silent now. */
    private fun levelsCleared() {
        heard.clear()
        if (levelShown > 0) reportLevel(0.0)
    }

    private fun reportLevel(level: Double) {
        levelShown = level
        events.onAgentAudio(level, null, null)
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
        if (muted || onHold) return
        // Once per agent turn, immediately before the first frame released over the agent's voice.
        if (speaking && !bargedIn) {
            bargedIn = true
            send(CallFrames.BARGE_IN)
        } else if (!speaking) {
            bargedIn = false
        }
        sendAudio(CallFrames.audio(B64.encode(AgentAudio.toLittleEndian(frame))))
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

/**
 * WHICH AGENT A CALL DIALS, AND WHERE — from this launch's session, or the one
 * kept on the device. A call that has either starts at once; the network
 * refreshes the session behind it (GraineCalls.connect).
 */
internal data class CallAgentIds(val agentId: String, val webcallAgentId: String?, val wsHost: String?) {
    companion object {
        fun of(s: SessionConfig) = CallAgentIds(s.agentId, s.webcallAgentId, s.wsHost)

        /**
         * What a call can start on NOW: this launch's session, else the kept
         * one — but a kept one only when it says where the agent's socket
         * lives. One kept by an older SDK does not, and a guess would dial
         * Graine's own host for an install that runs its own. Null: the call
         * waits for the network.
         */
        fun now(live: SessionConfig?, kept: SessionCache.Kept?): CallAgentIds? {
            if (live != null) return of(live)
            val k = kept ?: return null
            val host = k.wsHost?.takeIf { it.isNotBlank() } ?: return null
            val agent = k.agentId?.takeIf { it.isNotBlank() } ?: k.webcallAgentId?.takeIf { it.isNotBlank() } ?: return null
            return CallAgentIds(agent, k.webcallAgentId, host)
        }
    }
}

/**
 * THE AGENT'S VOICE LEVEL AS IT IS HEARD, on the WebSocket: the RMS (0–1 of
 * full scale) of each [WINDOW_MS] of the decoded audio, due when the player
 * reaches it. The server sends a reply far faster than real time, so a level
 * read on arrival would move seconds ahead of the voice and stop before it
 * does. Pure: the session gives it the audio and the clock.
 */
internal class HeardLevels {
    /** (due at, level), in the order they play. */
    private val queue = ArrayDeque<Pair<Long, Double>>()

    /** [pcm] at [sampleRate], heard from [startsAt] on: one level per window, each due when it starts to play. */
    fun add(pcm: ShortArray, sampleRate: Int, startsAt: Long) {
        if (pcm.isEmpty() || sampleRate <= 0) return
        val window = Math.max(1, (sampleRate * WINDOW_MS / 1000).toInt())
        var from = 0
        var at = startsAt.toDouble()
        while (from < pcm.size) {
            val to = Math.min(pcm.size, from + window)
            queue.addLast(Math.round(at) to rms(pcm, from, to))
            at += (to - from) * 1000.0 / sampleRate
            from = to
        }
    }

    /** Takes every level due by [now] and returns the latest of them; null when none is due yet. */
    fun due(now: Long): Double? {
        var latest: Double? = null
        while (queue.isNotEmpty() && queue.first().first <= now) latest = queue.removeFirst().second
        return latest
    }

    val isEmpty: Boolean get() = queue.isEmpty()

    fun clear() = queue.clear()

    companion object {
        /** One level per this much audio, and at most one report per this long: about ten a second. */
        const val WINDOW_MS = 100L

        /** The root mean square of [pcm] from [from] until [to], as a share of full scale. */
        @JvmStatic
        fun rms(pcm: ShortArray, from: Int = 0, to: Int = pcm.size): Double {
            if (to <= from) return 0.0
            var sum = 0.0
            for (k in from until to) {
                val v = pcm[k].toDouble()
                sum += v * v
            }
            return Math.min(1.0, Math.sqrt(sum / (to - from)) / 32768.0)
        }
    }
}
