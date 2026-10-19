package ai.graine.inapp

import org.json.JSONObject

/** A frame on the control channel, read as the voice bar reads it (lib/webcall-rtc.ts openControl). */
internal sealed class ControlFrame {
    /** Joined: the call exists and the channel reaches it. */
    object Ready : ControlFrame()
    object Pong : ControlFrame()
    /** The customer's words: live while `final` is false. */
    data class CustomerCaption(val text: String, val final: Boolean) : ControlFrame()
    /** A chunk of the agent's line (its `data`, as a text frame on the voice socket carries it). */
    data class AgentText(val data: Any?) : ControlFrame()
    /** The agent's line is over: end of stream, cleared, or interrupted. */
    object EndOfLine : ControlFrame()
    data class Action(val actionId: String, val name: String, val arguments: JSONObject) : ControlFrame()
    data class Widget(val pending: Boolean, val widget: JSONObject) : ControlFrame()
    data class Other(val type: String) : ControlFrame()

    companion object {
        fun parse(raw: String): ControlFrame? {
            val o = try { JSONObject(raw) } catch (_: Exception) { return null }
            return when (val type = o.optString("type")) {
                "control_ready" -> Ready
                "pong" -> Pong
                "interim_transcript_received", "transcript" -> {
                    // `content`, else `data.content`, else `data` — the three shapes the runtime has used.
                    val data = o.opt("data")
                    val text = CallVariables.jsString(o.opt("content"))
                        ?: (data as? JSONObject)?.let { CallVariables.jsString(it.opt("content")) }
                        ?: CallVariables.jsString(data)
                        ?: ""
                    CustomerCaption(text, final = type == "transcript")
                }
                "text" -> AgentText(o.opt("data"))
                "end_of_stream", "clear", "interruption" -> EndOfLine
                "app_action" -> {
                    val a = o.optJSONObject("action") ?: return Other(type)
                    Action(a.optString("action_id"), a.optString("name"), a.optJSONObject("arguments") ?: JSONObject())
                }
                "widget", "widget_pending" -> Widget(type == "widget_pending", o.optJSONObject("widget") ?: JSONObject())
                else -> Other(type)
            }
        }
    }
}

/**
 * THE CALL'S CONTROL CHANNEL — the half of a WebRTC call that is not audio
 * (lib/webcall-rtc.ts openControl, sdk/NATIVE-CALLS.md).
 *
 * The media goes to the server's media edge, which knows nothing of the app;
 * the screen, the agent's actions and the captions travel here, joined to the
 * call by its session id. The server holds the socket's handshake until the
 * call exists, then says `control_ready`: frames written before that are
 * queued (32 at most, the oldest dropped; a newer `app_context` replaces the
 * one waiting), and flushed in order. Then a `ping` every 25 s, because a
 * load balancer counts a quiet socket as dead. A socket that closes before the
 * call ends is opened again two seconds later; hanging up never closes it from
 * the server's side, so this side closes it.
 *
 * Its absence is a degraded call, never a failed one: the audio does not wait
 * for it. All state on [scheduler]'s thread.
 */
internal class RtcControl(
    private val url: String,
    private val scheduler: CallScheduler,
    private val openSocket: (url: String, listener: CallSocketListener) -> CallSocket,
    private val listener: Listener,
) {
    interface Listener {
        fun onJoined() {}
        fun onFrame(frame: ControlFrame) {}
    }

    private class Queued(val type: String, val text: String)

    private var socket: CallSocket? = null
    private var generation = 0
    private var opened = false
    private var closed = false
    private val queue = ArrayDeque<Queued>()
    private var pingTimer: (() -> Unit)? = null
    private var reopenTimer: (() -> Unit)? = null

    /** Joined: `control_ready` arrived on the socket that is open now. */
    var joined = false
        private set

    fun open() = scheduler.post {
        if (closed || opened) return@post
        opened = true
        connect()
    }

    private fun connect() {
        val gen = ++generation
        socket = try {
            openSocket(url, SocketListener(gen))
        } catch (e: Exception) {
            scheduler.post { dropped(gen) }
            null
        }
    }

    private inner class SocketListener(private val gen: Int) : CallSocketListener {
        override fun onOpen(protocol: String?) = Unit
        override fun onText(text: String) = scheduler.post { if (gen == generation && !closed) receive(text) }
        override fun onClosed(code: Int, reason: String) = scheduler.post { dropped(gen) }
        override fun onFailure(error: Throwable) = scheduler.post { dropped(gen) }
    }

    private fun receive(text: String) {
        val f = ControlFrame.parse(text) ?: return
        when (f) {
            ControlFrame.Ready -> {
                joined = true
                schedulePing()
                // Flushed in order: the first app_context first, so the agent's opening line sees the screen.
                while (queue.isNotEmpty()) socket?.send(queue.removeFirst().text)
                listener.onJoined()
            }
            ControlFrame.Pong -> Unit
            else -> listener.onFrame(f)
        }
    }

    private fun schedulePing() {
        pingTimer?.invoke()
        pingTimer = scheduler.schedule(PING_MS) {
            pingTimer = null
            if (!joined || closed) return@schedule
            socket?.send(PING)
            schedulePing()
        }
    }

    private fun dropped(gen: Int) {
        if (gen != generation) return
        generation++
        socket = null
        joined = false
        pingTimer?.invoke(); pingTimer = null
        if (closed) return
        reopenTimer?.invoke()
        reopenTimer = scheduler.schedule(REOPEN_AFTER_MS) {
            reopenTimer = null
            if (!closed && socket == null) connect()
        }
    }

    /** Send [text] (a frame of [type]) now if joined, else queue it. */
    fun send(type: String, text: String) = scheduler.post { sendHere(type, text) }

    /**
     * [send], from [scheduler]'s own thread, done before the caller's next
     * line: a call's quality row is written before the BYE that follows it in
     * the same task, and before the close that follows the BYE.
     */
    fun sendHere(type: String, text: String) {
        if (closed) return
        if (joined && socket?.send(text) == true) return
        if (type == "app_context") queue.removeAll { it.type == "app_context" }
        queue.addLast(Queued(type, text))
        while (queue.size > QUEUE_LIMIT) queue.removeFirst()
    }

    /** Close it for good — after the BYE. */
    fun close() = scheduler.post {
        if (closed) return@post
        closed = true
        joined = false
        pingTimer?.invoke(); pingTimer = null
        reopenTimer?.invoke(); reopenTimer = null
        queue.clear()
        generation++
        socket?.close(1000, "")
        socket = null
    }

    companion object {
        const val PATH = "/control/v1/"
        const val PING_MS = 25_000L
        const val REOPEN_AFTER_MS = 2_000L
        const val QUEUE_LIMIT = 32
        /**
         * How long the handshake may wait. The server holds it until the call
         * exists — a few hundred milliseconds after the answer — and closes a
         * session that never comes after 20 s, so this outlasts both.
         */
        const val OPEN_TIMEOUT_MS = 25_000L
        const val PING = "{\"type\":\"ping\"}"

        /** `{wsHost}/control/v1/{sessionId}`. */
        fun url(wsHost: String?, sessionId: String): String =
            (wsHost?.takeIf { it.isNotBlank() } ?: CallUrls.DEFAULT_WS_HOST).trimEnd('/') + PATH + CallUrls.encodeURIComponent(sessionId)
    }
}
