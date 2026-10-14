package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject

/*
 * THE TEXT CONVERSATION — the React Native SDK's text path (sdk/inapp
 * client.ts openSocket / onFrame / say / submitWidget / close), ported onto
 * the native call engine's builders (CallProtocol.kt): the same ticket and
 * subprotocol, the same context token, the same init, ping, app_context,
 * app_action_result and stop frames — with `mode: "text"`, `?text_chat=1`
 * and no audio. Pure: the socket, the clock and the HTTP are handed in, so
 * every frame is a JVM test (ChatTest). GraineChat is the Android shell.
 */

/** A component the agent drew — WIRE.md's `GraineComponent`. */
typealias GraineComponent = GraineWidget

/** One message in the chat (WIRE.md "SDK types"). */
data class GraineChatTurn @JvmOverloads constructor(
    /** Stable for the life of the transcript: `t1`, `t2`, … */
    val id: String,
    /** [Role.CUSTOMER] (the user) or [Role.AGENT]. */
    val role: Role,
    val text: String,
    /** When the turn began. */
    val at: java.util.Date,
    /** Where a customer's message is; an agent's turn is always [Delivery.SENT]. */
    val delivery: Delivery = Delivery.SENT,
    /** The agent said this without being asked: its turn did not follow one of the customer's. */
    val proactive: Boolean = false,
    /** The component the agent drew with this turn, when it drew one. */
    val component: GraineComponent? = null,
    /** The agent's words still arriving: drawn as provisional until the turn ends. */
    val live: Boolean = false,
    /** From an earlier conversation, restored when the chat opened: delivered, with its own time and id, not said just now. */
    val restored: Boolean = false,
    /** Where it was said. A chat window draws a run of [Channel.VOICE] turns as one "Voice call" row, never their words. */
    val channel: Channel = Channel.CHAT,
) {
    enum class Delivery {
        /** Waiting for the connection: typed while the chat was not connected, sent when it is. */
        SENDING,
        /** On its way to the agent. */
        SENT,
        /** The chat could not reconnect to send it; the next message, or a retry, tries again. */
        FAILED,
    }

    enum class Channel {
        /** Typed, in the chat. */
        CHAT,
        /** Said on a call with the agent — only a restored turn is. */
        VOICE,
    }
}

/**
 * A TEXT CONVERSATION A CHAT WINDOW CAN DRAW — GraineAgentBarView's chat and
 * GraineChatView read one. The SDK's own is [GraineChat] (`GraineInApp.get().chat`),
 * the default everywhere; an app with a conversation of its own implements this.
 * Called on the main thread.
 */
interface ChatSource {
    /** The conversation, oldest first. */
    val messages: List<GraineChatTurn>
    /** Connected: "Online". Otherwise "Connecting…" (or [failed]). */
    val online: Boolean
    /** The agent is working on a reply: the window draws its typing indicator. */
    val typing: Boolean get() = false
    /** Nothing could connect the conversation: the window says so and a send tries again. */
    val failed: Boolean get() = false
    fun send(text: String)
    /** A chat window showing this conversation came on screen (`true`) or left it. A source with a connection opens it here. */
    fun shown(visible: Boolean) {}
    /** Hear about every change. */
    fun addChangeListener(listener: Runnable)
    fun removeChangeListener(listener: Runnable)
}

internal object ChatFrames {
    /** `{type:"text", data}` — the customer's turn (client.ts say()). */
    fun text(data: String): String = JSONObject().put("type", "text").put("data", data).toString()

    /** `{type:"app_event", event:{name, …}}` — something the agent may speak up about (client.ts reportEvent()). */
    fun appEvent(name: String, data: Map<String, Any?>): String {
        val event = JSONObject().put("name", name)
        for ((k, v) in data) if (k != "name") event.put(k, Protocol.toJson(v))
        return JSONObject().put("type", "app_event").put("event", event).toString()
    }

    /** `{type:"widget_result", widget_id, data}` — the structured half of a component's answer; the text turn follows. */
    fun widgetResult(widgetId: String, data: Map<String, Any?>): String =
        JSONObject().put("type", "widget_result").put("widget_id", widgetId).put("data", Protocol.toJson(data)).toString()
}

/** Everything a text socket can deliver, typed. */
internal sealed class ChatInbound {
    data class Text(val data: Any?) : ChatInbound()
    /** The earlier conversation, restored (contract §2.6): the turns, oldest first — possibly none. */
    data class History(val turns: List<GraineChatTurn>) : ChatInbound()
    data class Widget(val widget: JSONObject) : ChatInbound()
    data class WidgetPending(val hint: JSONObject) : ChatInbound()
    data class Action(val actionId: String, val name: String, val arguments: JSONObject) : ChatInbound()
    object TurnStart : ChatInbound()
    object TurnEnd : ChatInbound()
    object Ignored : ChatInbound()

    companion object {
        fun parse(raw: String): ChatInbound? {
            val o = try { JSONObject(raw) } catch (_: Exception) { return null }
            return when (o.optString("type")) {
                "text" -> Text(o.opt("data"))
                "widget" -> (o.opt("widget") as? JSONObject)?.let { Widget(it) } ?: Ignored
                "widget_pending" -> WidgetPending(o.opt("widget") as? JSONObject ?: JSONObject())
                "app_action" -> o.optJSONObject("action")?.let { a ->
                    Action(a.optString("action_id"), a.optString("name"), a.optJSONObject("arguments") ?: JSONObject())
                } ?: Ignored
                "beginning_of_stream" -> TurnStart
                "end_of_stream" -> TurnEnd
                // A history whose turns are not a list is not one: ignored, never an error.
                "history" -> ChatHistory.read(o)?.let { History(it) } ?: Ignored
                else -> Ignored
            }
        }
    }
}

/**
 * THE CONVERSATION CONTINUES (contract §2.6, vectors sdk/contract/history.json).
 *
 * An identified customer who comes back finds the chat where they left it: a
 * chat that passes the memory gate opens with ONE frame, in place of the
 * welcome (the customer types next) —
 *
 *     {"type":"history","turns":[{id, role: "user"|"assistant", text,
 *       at: ISO-8601 UTC, channel: "chat"|"voice"}, …oldest first],"continued":true}
 *
 * read here as the React Native SDK reads it (protocol.ts readHistoryFrame).
 */
internal object ChatHistory {
    private val ISO = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})$", RegexOption.IGNORE_CASE)

    /**
     * The turns a history frame restores, oldest first, or null when its
     * `turns` is not a list. A turn that cannot be read is skipped: not an
     * object, a role other than user/assistant, text that is not a string or is
     * blank, no ISO-8601 `at` string. A channel other than "voice" is the chat;
     * a missing or repeated id becomes "h<index>".
     */
    fun read(frame: JSONObject): List<GraineChatTurn>? {
        val list = frame.opt("turns") as? JSONArray ?: return null
        val out = ArrayList<GraineChatTurn>()
        val used = HashSet<String>()
        for (index in 0 until list.length()) {
            val o = list.opt(index) as? JSONObject ?: continue
            val role = when (o.opt("role")) {
                "user" -> Role.CUSTOMER
                "assistant" -> Role.AGENT
                else -> continue
            }
            // optString would turn 42 into "42": a number is neither a message nor a time.
            val text = o.opt("text") as? String ?: continue
            if (text.isBlank()) continue
            val at = (o.opt("at") as? String)?.let { time(it) } ?: continue
            val wire = o.opt("id") as? String
            val id = if (!wire.isNullOrEmpty() && wire !in used) wire else "h$index"
            used.add(id)
            out.add(GraineChatTurn(
                id = id, role = role, text = text, at = java.util.Date(at), delivery = GraineChatTurn.Delivery.SENT,
                restored = true, channel = if (o.opt("channel") == "voice") GraineChatTurn.Channel.VOICE else GraineChatTurn.Channel.CHAT,
            ))
        }
        return out
    }

    /** ISO-8601 with a zone, fractions or not, as ms since the epoch — the server writes UTC with a Z. A time with no zone is not one. */
    fun time(raw: String): Long? {
        val s = raw.trim()
        if (!ISO.matches(s)) return null
        return Scheduling.parseIso(s)
    }
}

/** What a text session needs to know. */
internal data class ChatSessionConfig(
    val baseUrl: String,
    val publishableKey: String,
    val agentId: String,
    val webcallAgentId: String?,
    val wsHost: String?,
    /** GraineInApp.currentVariables(): the dashboard's, the build's, the app's, the signed-in customer. */
    val variables: Map<String, String>,
    /** Who is using the app, for the ticket; null sends none. */
    val identity: IdentityEnvelope? = null,
)

/** What a text session reports, on its scheduler thread. */
internal interface ChatSessionEvents {
    fun onOpen() {}
    /** It never opened. */
    fun onFailed(error: String) {}
    /** It was open and is over, not by this side's hand. `unsent` are the lines that never reached the socket. */
    fun onClosed(code: Int, error: String?, unsent: List<String>) {}
    /** The agent's words, markers removed; nothing between turns. */
    fun onAgentWords(text: String) {}
    /** A turn began: its first words or its start marker. */
    fun onAgentTurnStart() {}
    /** The turn's words are complete (`<end_of_stream>`). */
    fun onAgentTurnEnd() {}
    fun onWidget(widget: JSONObject) {}
    fun onWidgetPending(hint: JSONObject) {}
    /** The earlier conversation, restored, before any agent message: never the agent speaking now. */
    fun onHistory(turns: List<GraineChatTurn>) {}
}

internal class ChatSessionDeps(
    val scheduler: CallScheduler,
    val http: HttpPost = PlatformHttp,
    val openSocket: (url: String, protocols: List<String>, listener: CallSocketListener) -> CallSocket =
        { url, protocols, listener -> WebSocketClient(url, protocols, listener).connect() },
    /** The SDK's action runner (GraineInApp.executeAction): always answers, from any thread. */
    val runAction: (name: String, args: JSONObject, answer: (ActionResult) -> Unit) -> Unit =
        { _, _, answer -> answer(ActionResult.refused("This app build does not implement actions.")) },
    /** The SDK's context frame (GraineInApp.currentContextFrame), or null. */
    val contextFrame: () -> JSONObject? = { null },
    /** Where blocking work (the ticket) runs. */
    val io: (() -> Unit) -> Unit = { work -> Thread({ work() }, "graine-chat-ticket").apply { isDaemon = true }.start() },
)

/**
 * ONE TEXT CONVERSATION ON ONE SOCKET.
 *
 *   ticket (mode "text") or the direct `?text_chat=1` URL → socket with
 *   context_token (call_type "text_chat") → init → app_context → the lines
 *   typed while it was connecting → ping every 25 s; the agent's words in,
 *   with their turn markers read and removed; components and actions in;
 *   lines, context, events, component answers and action results out; stop
 *   and close to finish.
 *
 * Lines sent before the socket opens are held and go out right after init,
 * in order; a session that closes hands back whatever never reached the
 * socket ([ChatSessionEvents.onClosed]'s `unsent`), so the next one can send it.
 * All state lives on the scheduler's thread.
 */
internal class TextChatSession(
    private val config: ChatSessionConfig,
    private val deps: ChatSessionDeps,
    private val events: ChatSessionEvents,
    initialOutbox: List<String> = emptyList(),
) {
    private enum class Phase { NEW, CONNECTING, OPEN, CLOSED }

    private val scheduler get() = deps.scheduler
    private var phase = Phase.NEW
    private var socket: CallSocket? = null
    private var openTimer: (() -> Unit)? = null
    private var pingTimer: (() -> Unit)? = null
    private val outbox = ArrayDeque<String>(initialOutbox)
    /** Inside an agent turn: its first words or its start marker came, its end marker has not. */
    private var inTurn = false

    fun start() = scheduler.post {
        if (phase != Phase.NEW) return@post
        phase = Phase.CONNECTING
        deps.io {
            val ticket = fetchTicket()
            scheduler.post { connect(ticket) }
        }
    }

    /** The customer's line. Held until the socket is open; lost only if every later session fails too. */
    fun say(text: String) = scheduler.post {
        if (phase == Phase.OPEN && outbox.isEmpty() && send(ChatFrames.text(text))) return@post
        outbox.addLast(text)
        if (outbox.size > OUTBOX_MAX) outbox.removeFirst()
    }

    /** A newer screen while the conversation is up. */
    fun sendContext(frame: JSONObject) = scheduler.post { if (phase == Phase.OPEN) send(CallFrames.appContext(frame)) }

    /** Something the agent may speak up about (friction, a component closed, an in-app action that failed). */
    fun reportEvent(name: String, data: Map<String, Any?>) = scheduler.post {
        if (phase == Phase.OPEN) send(ChatFrames.appEvent(name, data))
    }

    /**
     * A component answered: BOTH frames, as the web chat sends them — the
     * structured payload, and the text turn that moves the conversation.
     */
    fun submitWidget(widgetId: String, data: Map<String, Any?>, summary: String) = scheduler.post {
        if (phase == Phase.OPEN) send(ChatFrames.widgetResult(widgetId, data))
        if (summary.isNotEmpty()) {
            if (phase == Phase.OPEN && outbox.isEmpty() && send(ChatFrames.text(summary))) return@post
            outbox.addLast(summary)
        }
    }

    /** This side ends it: the stop frame, the close ~200 ms later. No event follows. */
    fun close() = scheduler.post {
        if (phase == Phase.CLOSED) return@post
        val wasOpen = phase == Phase.OPEN
        phase = Phase.CLOSED
        stopTimers()
        val s = socket ?: return@post
        if (wasOpen) {
            s.send(CallFrames.STOP)
            scheduler.schedule(CallFrames.CLOSE_AFTER_STOP_MS) { s.close(1000, "") }
        } else {
            s.close(1000, "")
        }
    }

    // ── connecting ──────────────────────────────────────────────────────────

    private class Ticket(val ticket: String, val wsUrl: String)

    /** A fresh text ticket — each is single-use, and a text one opens a typed conversation, not a call. Null: the direct URL. */
    private fun fetchTicket(): Ticket? = try {
        val body = JSONObject().put("publishableKey", config.publishableKey).put("mode", "text")
        CallVariables.forTicket(config.variables)?.let { body.put("metadata", JSONObject(it as Map<*, *>)) }
        config.identity?.into(body)
        val reply = deps.http.post("${config.baseUrl.trimEnd('/')}/api/embed/ticket", body.toString())
        if (reply.code !in 200..299) null else {
            val o = JSONObject(reply.body)
            val t = o.optString("ticket")
            val u = o.optString("wsUrl")
            if (t.isNotEmpty() && u.isNotEmpty()) Ticket(t, u) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun connect(ticket: Ticket?) {
        if (phase != Phase.CONNECTING) return
        val url: String
        val protocols: List<String>
        if (ticket != null) {
            url = ticket.wsUrl
            // In the subprotocol, never the query: queries land in access logs.
            protocols = listOf(CallFrames.SUBPROTOCOL, ticket.ticket)
        } else {
            url = ChatUrls.direct(config.wsHost, config.webcallAgentId?.takeIf { it.isNotEmpty() } ?: config.agentId)
            protocols = emptyList()
        }
        // The direct socket reads the variables off its URL. The gateway builds the conversation's context
        // from the ticket's signed claims and reads nothing else, so a token there would only reach the access logs.
        val full = if (ticket != null) url else CallUrls.withToken(url, CallVariables.contextToken(config.variables, CALL_TYPE))
        openTimer = scheduler.schedule(CallFrames.OPEN_TIMEOUT_MS) {
            if (phase == Phase.CONNECTING) failBeforeOpen("The chat could not connect within ${CallFrames.OPEN_TIMEOUT_MS / 1000} seconds.")
        }
        val s = try {
            deps.openSocket(full, protocols, Listener())
        } catch (e: Exception) {
            failBeforeOpen("The chat could not connect: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        socket = s
        if (phase != Phase.CONNECTING) s.close(1000, "")
    }

    private inner class Listener : CallSocketListener {
        override fun onOpen(protocol: String?) = scheduler.post { opened() }
        override fun onText(text: String) = scheduler.post { if (phase == Phase.OPEN) onFrame(text) }
        override fun onClosed(code: Int, reason: String) = scheduler.post {
            when (phase) {
                Phase.CONNECTING -> failBeforeOpen("The chat was refused (close $code${if (reason.isNotEmpty()) ": $reason" else ""}).")
                Phase.OPEN -> closedRemotely(code, if (code == 1000 || code == 1005) null else "close $code $reason".trim())
                else -> Unit
            }
        }
        override fun onFailure(error: Throwable) = scheduler.post {
            val message = error.message ?: error.javaClass.simpleName
            when (phase) {
                Phase.CONNECTING -> failBeforeOpen("The chat could not connect: $message")
                Phase.OPEN -> closedRemotely(1006, message)
                else -> Unit
            }
        }
    }

    private fun opened() {
        if (phase != Phase.CONNECTING) { socket?.close(1000, ""); return }
        phase = Phase.OPEN
        openTimer?.invoke(); openTimer = null
        send(CallFrames.init(config.variables))
        // The screen set before the chat opened is the whole context for the agent's first line.
        runCatching { deps.contextFrame() }.getOrNull()?.let { send(CallFrames.appContext(it)) }
        schedulePing()
        events.onOpen()
        // What the customer typed while it connected, in order.
        while (outbox.isNotEmpty() && phase == Phase.OPEN) {
            if (!send(ChatFrames.text(outbox.first()))) break
            outbox.removeFirst()
        }
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
        stopTimers()
        socket?.close(1000, "")
        events.onFailed(error)
    }

    private fun closedRemotely(code: Int, error: String?) {
        if (phase != Phase.OPEN) return
        phase = Phase.CLOSED
        stopTimers()
        if (inTurn) { inTurn = false; events.onAgentTurnEnd() }
        events.onClosed(code, error, outbox.toList())
        outbox.clear()
    }

    private fun stopTimers() {
        openTimer?.invoke(); openTimer = null
        pingTimer?.invoke(); pingTimer = null
    }

    private fun send(text: String): Boolean = socket?.send(text) ?: false

    // ── the agent → the customer ────────────────────────────────────────────

    private fun onFrame(raw: String) {
        when (val f = ChatInbound.parse(raw) ?: return) {
            is ChatInbound.Text -> {
                // A turn marker is not a word anybody said; a text turn starts on its first words when it has no start marker.
                val chunk = CallText.readAgentChunk(f.data)
                if ((chunk.startsTurn || chunk.text.isNotEmpty()) && !inTurn) { inTurn = true; events.onAgentTurnStart() }
                if (chunk.text.isNotEmpty()) events.onAgentWords(chunk.text)
                if (chunk.endsTurn && inTurn) { inTurn = false; events.onAgentTurnEnd() }
            }
            ChatInbound.TurnStart -> if (!inTurn) { inTurn = true; events.onAgentTurnStart() }
            ChatInbound.TurnEnd -> if (inTurn) { inTurn = false; events.onAgentTurnEnd() }
            is ChatInbound.Widget -> events.onWidget(f.widget)
            is ChatInbound.WidgetPending -> events.onWidgetPending(f.hint)
            is ChatInbound.History -> events.onHistory(f.turns)
            is ChatInbound.Action -> runAction(f)
            ChatInbound.Ignored -> Unit
        }
    }

    /** Through the SDK's runner, answered within 6 s on every path: the runtime is blocked on it. */
    private fun runAction(a: ChatInbound.Action) {
        var answered = false
        var deadline: (() -> Unit)? = null
        fun answer(r: ActionResult) {
            if (answered) return
            answered = true
            deadline?.invoke()
            if (phase == Phase.OPEN) send(CallFrames.actionResult(a.actionId, r))
        }
        deadline = scheduler.schedule(CallFrames.ACTION_ANSWER_BY_MS) { answer(ActionResult.error("\"${a.name}\" did not finish in time.")) }
        try {
            deps.runAction(a.name, a.arguments) { r -> scheduler.post { answer(r) } }
        } catch (e: Exception) {
            answer(ActionResult.error("\"${a.name}\" failed: ${e.message ?: e.javaClass.simpleName}"))
        }
    }

    companion object {
        /** The runtime's word for a typed conversation: turn-based, no synthesizer. */
        const val CALL_TYPE = "text_chat"
        /** Lines held while offline; a customer does not type more than this into a chat that cannot answer. */
        const val OUTBOX_MAX = 20
    }
}

internal object ChatUrls {
    /** The direct socket, when no ticket could be minted: `{wsHost}/chat/v1/{agent}?text_chat=1`. */
    fun direct(wsHost: String?, agentId: String): String {
        val host = (wsHost?.takeIf { it.isNotBlank() } ?: CallUrls.DEFAULT_WS_HOST).trimEnd('/')
        return "$host/chat/v1/${CallUrls.encodeURIComponent(agentId)}?text_chat=1"
    }
}

/**
 * THE CONVERSATION AS A WINDOW DRAWS IT — one bubble per agent turn.
 *
 * Streamed words go into the agent's live line; the line ends on its turn's
 * end marker — not when the customer next types, the bug in the React Native
 * provider that merged separate replies into one bubble and left it at 75%
 * until the customer spoke. While the customer waits for a reply (they sent a
 * line, no word has come back yet, for at most [Widgets.AWAITING_REPLY_MS])
 * the window draws its typing indicator. Pure; the owner passes the time.
 */
internal class ChatTranscript(private val cap: Int = MAX_LINES) {
    private val list = ArrayList<GraineChatTurn>()
    private var awaitingUntil: Long? = null
    private var nextId = 1
    /** The ids that were settled when the connection last opened — what a history replaces. Null before any open. */
    private var settledAtOpen: Set<String>? = null

    /** The conversation as drawn: a turn that has begun with neither words nor a component yet is not a turn, it is [typing]. */
    val lines: List<GraineChatTurn> get() = list.filter { it.text.isNotEmpty() || it.component != null }

    /** The customer is waiting on the agent's reply. */
    val awaitingReply: Boolean get() = awaitingUntil != null

    /** The agent is working on a reply: the customer is waiting on one, or a turn has begun with no words yet. */
    val typing: Boolean get() = awaitingUntil != null ||
        list.lastOrNull()?.let { it.role == Role.AGENT && it.live && it.text.isEmpty() && it.component == null } == true

    /** The customer's line. Whatever the agent was writing is finished: they have moved on. Returns the turn's id. */
    fun customer(text: String, now: Long, wallMs: Long = now, delivery: GraineChatTurn.Delivery = GraineChatTurn.Delivery.SENT): String {
        closeLive()
        val turn = GraineChatTurn(take(), Role.CUSTOMER, text, java.util.Date(wallMs), delivery)
        add(turn)
        awaitingUntil = now + Widgets.AWAITING_REPLY_MS
        return turn.id
    }

    /** A turn began. True when the agent spoke first — not in answer to the customer's last line. */
    fun agentTurnStart(wallMs: Long): Boolean {
        closeLive()
        val volunteered = list.lastOrNull()?.role != Role.CUSTOMER
        add(GraineChatTurn(take(), Role.AGENT, "", java.util.Date(wallMs), proactive = volunteered, live = true))
        return volunteered
    }

    /** Words for the live turn — a turn that somehow had no start begins here. */
    fun agentWords(text: String, wallMs: Long) {
        if (text.isEmpty()) return
        val last = list.lastOrNull()
        if (last == null || last.role != Role.AGENT || !last.live) agentTurnStart(wallMs)
        val i = list.size - 1
        list[i] = list[i].copy(text = list[i].text + text)
        awaitingUntil = null
    }

    /** The turn's words are complete: the bubble is solid; an empty one (markers alone) is dropped. */
    fun agentTurnEnd() = closeLive()

    /**
     * A component the agent drew: it belongs to the agent's turn in answer to
     * the customer's last line — or, when the agent has not said anything
     * since, to a turn of its own.
     */
    fun component(c: GraineComponent, wallMs: Long) {
        val i = list.indexOfLast { it.role == Role.AGENT }
        val lastCustomer = list.indexOfLast { it.role == Role.CUSTOMER }
        if (i >= 0 && i > lastCustomer) {
            list[i] = list[i].copy(component = c)
        } else {
            val proactive = list.lastOrNull()?.role != Role.CUSTOMER
            add(GraineChatTurn(take(), Role.AGENT, "", java.util.Date(wallMs), proactive = proactive, component = c))
        }
        awaitingUntil = null
    }

    /** Every customer message at [from] moves to [to]: sent once the chat connects, failed when it gives up, sending on a retry. */
    fun deliver(from: GraineChatTurn.Delivery, to: GraineChatTurn.Delivery): Boolean {
        var changed = false
        for (i in list.indices) if (list[i].role == Role.CUSTOMER && list[i].delivery == from) { list[i] = list[i].copy(delivery = to); changed = true }
        return changed
    }

    /** The conversation dropped: no line is still being written. */
    fun disconnected() {
        closeLive()
        awaitingUntil = null
    }

    fun clear() { list.clear(); awaitingUntil = null; settledAtOpen = null }

    /**
     * The connection opened, before what was waiting goes out: what is settled
     * now is what the next history replaces. A message still waiting goes out
     * right after this, and stays.
     */
    fun opened() {
        settledAtOpen = list.filter { it.delivery == GraineChatTurn.Delivery.SENT && !it.live }.map { it.id }.toSet()
    }

    /**
     * An earlier conversation, restored: its turns, then every turn the history
     * cannot hold yet — still sending or failed, or said since the connection
     * opened — in order. A reconnect's history holds what the last one did and
     * what was said since, so it replaces both, and nothing shows twice. With
     * no open yet, only the turns still to go are kept. False when there was
     * nothing to restore.
     */
    fun restore(restored: List<GraineChatTurn>): Boolean {
        if (restored.isEmpty()) return false
        val settled = settledAtOpen
        val kept = list.filter { turn ->
            when {
                turn.restored -> false
                settled != null -> turn.id !in settled
                else -> turn.delivery != GraineChatTurn.Delivery.SENT
            }
        }
        list.clear()
        list.addAll(restored)
        list.addAll(kept)
        while (list.size > cap) list.removeAt(0)
        return true
    }

    /** Apply the waiting deadline. True when the typing indicator went away. */
    fun tick(now: Long): Boolean {
        val until = awaitingUntil ?: return false
        if (now < until) return false
        awaitingUntil = null
        return true
    }

    fun nextDeadline(): Long? = awaitingUntil

    private fun take(): String = "t" + nextId++

    private fun closeLive() {
        val i = list.indexOfLast { it.live }
        if (i < 0) return
        if (list[i].text.isBlank() && list[i].component == null) list.removeAt(i) else list[i] = list[i].copy(live = false)
    }

    private fun add(turn: GraineChatTurn) {
        list.add(turn)
        while (list.size > cap) list.removeAt(0)
    }

    companion object {
        /** Turns kept on screen for one launch; older ones scroll away for good. */
        const val MAX_LINES = 200
    }
}

/**
 * WHEN A DROPPED CHAT COMES BACK — after an unexpected close while the
 * customer is in the chat: 1, 2, 4, 8, 15, 15 s, jittered (a thousand phones
 * back from one outage must not reconnect in the same second), then it stops
 * and says so; the next line the customer sends tries again from the start.
 *
 * The ladder is reset only once a connection has carried a reply: a server
 * that accepts and immediately closes must not keep a phone reconnecting
 * forever.
 */
internal class ChatReconnect(delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 15_000), random: () -> Double = { Math.random() }) {
    private val ladder = RetryLadder(delaysMs, random)

    /** Every attempt has been spent. */
    val exhausted: Boolean get() = ladder.exhausted

    /** The wait before the next attempt; null when there are none left. */
    fun next(): Long? = ladder.next()

    /** A reply arrived: the connection works, and a later drop starts the ladder again. */
    fun worked() = ladder.reset()

    /** The customer asked again (a send, a retry): start over. */
    fun reset() = ladder.reset()
}
