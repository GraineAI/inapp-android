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
    /**
     * The id of the session it was said in — a chat connection's run, or a
     * call's id for a voice turn, the id Execution Logs shows: a restored
     * turn's as Graine kept it, a turn said now the chat's
     * [GraineChat.sessionId] when it was said. Null for turns kept before
     * sessions were recorded, and with a server that sends none. New in 0.14.0.
     */
    val session: String? = null,
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
 * One reply the agent suggests for the turn it just sent: the words on the
 * chip ([label]) and what a tap sends as the customer's own message ([text]).
 */
data class GraineQuickReply(val label: String, val text: String)

/**
 * The customer's answer to "How was this chat?", asked once the agent ended
 * the conversation. [style] is "thumbs", "stars" or "emoji"; thumbs are
 * [value] 1 (up) or 0 (down) of [scale] 1, stars and faces 1–5 of 5.
 */
data class GraineChatRating(val value: Int, val scale: Int, val style: String)

/**
 * WHAT THE CONVERSATION IS ABOUT — a card the app pins to the chat window
 * ([GraineChat.pin]): an item, an appointment, a ticket. Drawn as a bar under
 * the header or as a card atop the thread, as the design says; never sent to
 * the agent (tell the agent about the screen with the screen context). Each
 * line is one line: [title] at most 60 characters, [subtitle] 80, [badge] 24.
 */
data class GraineChatPin @JvmOverloads constructor(
    val title: String,
    val subtitle: String? = null,
    /** A picture for the thumbnail, filled and cropped; none draws the title's first letter. */
    val imageUrl: String? = null,
    /** A short word at the trailing end ("Active", "2 items"). */
    val badge: String? = null,
)

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
    /** A dropped conversation is being made again: the window says "Reconnecting…". */
    val reconnecting: Boolean get() = false
    /** The replies the agent suggests for its latest turn, drawn as chips; a tap sends one as the customer's message. */
    val quickReplies: List<GraineQuickReply> get() = emptyList()
    /** The agent ended the conversation: the window asks for a rating where the design does. A message from the customer starts over. */
    val ended: Boolean get() = false
    /** The customer's answer to the rating, once given. */
    val rating: GraineChatRating? get() = null
    /** The customer answered the rating: thumbs 1/0, stars and emoji 1–5. Taken once, and only after the end; null when not taken. */
    fun rate(style: String, value: Int): GraineChatRating? = null
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
    /**
     * An app's event as the runtime reads it (sdk/ios ChatWire.appEventFrame,
     * React Native's chat): the call's own [detail], the pacing keys
     * (`cooldown_seconds`, `max_per_session`, `quiet`) at the event's level,
     * and everything else the app sent under `data` — a `name` or `detail`
     * key included, so the app's data never renames the event or replaces
     * what the call said happened. The fields [appEvent] writes beside `name`.
     */
    fun eventFields(detail: String?, data: Map<String, Any?>): Map<String, Any?> {
        val fields = LinkedHashMap<String, Any?>()
        val inner = LinkedHashMap<String, Any?>()
        when (val nested = data["data"]) {
            null -> Unit
            is Map<*, *> -> nested.forEach { (k, v) -> inner[k.toString()] = v }
            else -> inner["data"] = nested
        }
        for ((k, v) in data) {
            when (k) {
                "cooldown_seconds", "max_per_session", "quiet" -> fields[k] = v
                "data" -> Unit
                else -> if (!inner.containsKey(k)) inner[k] = v
            }
        }
        detail?.let { fields["detail"] = it }
        if (inner.isNotEmpty()) fields["data"] = inner
        return fields
    }

    fun appEvent(name: String, data: Map<String, Any?>): String {
        val event = JSONObject().put("name", name)
        for ((k, v) in data) if (k != "name") event.put(k, Protocol.toJson(v))
        return JSONObject().put("type", "app_event").put("event", event).toString()
    }

    /** `{type:"widget_result", widget_id, data}` — the structured half of a component's answer; the text turn follows. */
    fun widgetResult(widgetId: String, data: Map<String, Any?>): String =
        JSONObject().put("type", "widget_result").put("widget_id", widgetId).put("data", Protocol.toJson(data)).toString()

    /** The customer's rating, as the quiet `chat_rating` app event (protocol.ts chatRatingFrame). */
    fun rating(r: GraineChatRating): String = ChatRules.ratingFrame(r).toString()
}

/** Everything a text socket can deliver, typed. */
internal sealed class ChatInbound {
    data class Text(val data: Any?) : ChatInbound()
    /** The earlier conversation, restored (contract §2.6): the turns, oldest first — none when the frame's could not be read. */
    data class History(val turns: List<GraineChatTurn>) : ChatInbound()
    data class Widget(val widget: JSONObject) : ChatInbound()
    data class WidgetPending(val hint: JSONObject) : ChatInbound()
    data class Action(val actionId: String, val name: String, val arguments: JSONObject) : ChatInbound()
    /** The replies the agent suggests for its turn, cleaned (ChatRules.readQuickReplies); an empty list takes them away. */
    data class QuickReplies(val replies: List<GraineQuickReply>) : ChatInbound()
    /** The agent ended the conversation: the window may ask for a rating. */
    object ConversationEnd : ChatInbound()
    /** `{"type":"session","id","at"}`: this connection's session — [id] null for one that is not a non-empty string. */
    data class Session(val id: String?) : ChatInbound()
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
                // A history whose turns are not a list restores nothing, never an error — but it is still the
                // server continuing the conversation, in place of a welcome.
                "history" -> History(ChatHistory.read(o) ?: emptyList())
                // Chat window v2 (contract/DESIGN.md "Wire"): replies whose `replies` is not a list are no news.
                "quick_replies" -> ChatRules.readQuickReplies(o)?.let { QuickReplies(it) } ?: Ignored
                "conversation_end" -> ConversationEnd
                // Sent once when a chat connection starts (history.json `sessionFrame`).
                "session" -> Session((o.opt("id") as? String)?.takeIf { it.isNotEmpty() })
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
                // The session it was said in, as Graine kept it: a string that is not empty, else none.
                session = (o.opt("session") as? String)?.takeIf { it.isNotEmpty() },
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
    /**
     * It never opened, and trying again will not open it: Graine refused its
     * ticket — for this app (`app_not_allowed`, or `app_not_verified` after the
     * proof and a second try), or for the server token or the publishable key
     * ([ClientTokenRules.REFUSALS]). Final: never followed by a reconnect of its
     * own, and no other way in is tried. `unsent` are the lines that were
     * waiting for it. Unless implemented, [onFailed] hears its sentence.
     */
    fun onRefused(error: GraineError, unsent: List<String>) = onFailed(error.message)
    /** A turn began: its first words or its start marker. */
    fun onAgentTurnStart() {}
    /** The turn's words are complete (`<end_of_stream>`). */
    fun onAgentTurnEnd() {}
    fun onWidget(widget: JSONObject) {}
    fun onWidgetPending(hint: JSONObject) {}
    /** The earlier conversation, restored, before any agent message: never the agent speaking now. Every history frame, even one that restores nothing. */
    fun onHistory(turns: List<GraineChatTurn>) {}
    /** The agent suggested replies for its turn; an empty list takes them away. */
    fun onQuickReplies(replies: List<GraineQuickReply>) {}
    /** The agent ended the conversation (after closing whatever turn was open). */
    fun onConversationEnd() {}
    /** This connection's session, once, as it starts: its id, or null for one that is not a non-empty string. */
    fun onSession(id: String?) {}
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
    /** The agent's server tokens: the ticket carries one when they are in play (CLIENT-TOKENS.md); null sends none. */
    val tokens: ClientTokens? = null,
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

    /** The customer's rating, quiet. Best effort, as the other SDKs: with no connection it is announced to the app, not kept. */
    fun rate(rating: GraineChatRating) = scheduler.post {
        if (phase == Phase.OPEN) send(ChatFrames.rating(rating))
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

    /**
     * Graine refused the ticket — for this app (`app_not_allowed`, or
     * `app_not_verified` after the proof and a second try: EmbedRequest.appRefusal),
     * or for the server token or the key (ClientTokenRules.refusal): the
     * conversation's answer, set before [connect] runs. The chat fails with it,
     * and never tries the direct socket instead.
     */
    @Volatile private var refused: GraineError? = null

    /**
     * A fresh text ticket — each is single-use, and a text one opens a typed
     * conversation, not a call — through the one door (the app's declaration and
     * proofs, the user's token and pass) carrying the server token when tokens
     * are in play (a new connection: [ClientTokenWire]). Null: the direct URL —
     * except after a refusal ([refused]), which is final.
     */
    private fun fetchTicket(): Ticket? = try {
        val body = JSONObject().put("publishableKey", config.publishableKey).put("mode", "text")
        CallVariables.forTicket(config.variables)?.let { body.put("metadata", JSONObject(it as Map<*, *>)) }
        config.identity?.into(body)
        val url = "${config.baseUrl.trimEnd('/')}/api/embed/ticket"
        val reply = EmbedRequest.send(url, body, tokens = deps.tokens, newConnection = true) { json -> deps.http.post(url, json) }
        if (reply.code !in 200..299) {
            refused = EmbedRequest.appRefusal(reply) ?: ClientTokenRules.refusal(reply.code, reply.body)
            null
        } else {
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
        // Refused for this app, the token or the key: the direct socket would only be another way past that answer.
        refused?.let { e ->
            phase = Phase.CLOSED
            stopTimers()
            events.onRefused(e, outbox.toList())
            outbox.clear()
            return
        }
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
            is ChatInbound.QuickReplies -> events.onQuickReplies(f.replies)
            ChatInbound.ConversationEnd -> {
                // The open turn is over with the conversation: its words are complete.
                if (inTurn) { inTurn = false; events.onAgentTurnEnd() }
                events.onConversationEnd()
            }
            is ChatInbound.Session -> events.onSession(f.id)
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
        /** Graine ended the conversation itself, idle (`idle_timeout`): no reconnect follows (contract/DESIGN.md "How long a conversation lasts"). */
        const val CLOSE_IDLE = 4408
        /** Graine ended the conversation itself, at the session's time limit: no reconnect follows. */
        const val CLOSE_TIME_LIMIT = 4410
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
 *
 * A connection whose agent speaks first with no history before it is a NEW
 * conversation (contract/DESIGN.md "A new conversation under earlier lines",
 * vectors sdk/contract/history.json → `fresh`): an opening nobody answered is
 * replaced by the new one, anything more gets a marker row before what the new
 * conversation said — never a second welcome stacked under the first.
 */
internal class ChatTranscript(private val cap: Int = MAX_LINES) {
    private val list = ArrayList<GraineChatTurn>()
    private var awaitingUntil: Long? = null
    private var nextId = 1
    /** The ids that were settled when the connection last opened — what a history replaces. Null before any open. */
    private var settledAtOpen: Set<String>? = null
    /** The connection opened and the agent has not begun a turn on it, with no history frame since: its first turn tells. */
    private var firstTurnDue = false
    /** The turns a marker row is drawn before. */
    private val marked = LinkedHashSet<String>()
    /** A marker row whose turn has not come yet: the next turn added takes it. */
    private var markNext = false

    /**
     * The chat's current session: this connection's, from the server's
     * session frame ([sessionStarted]); null from a connection's open until
     * the frame arrives, and with a server that sends none.
     */
    var session: String? = null
        private set
    /** Between a connection's open and its session frame: the turns it carries meanwhile are stamped when the frame comes. */
    private var stamping = false
    /** Those turns, by id: the lines waiting at the open, and every turn said before the frame. */
    private val unstamped = LinkedHashSet<String>()

    /** The conversation as drawn: a turn that has begun with neither words nor a component yet is not a turn, it is [typing]. */
    val lines: List<GraineChatTurn> get() = list.filter { it.text.isNotEmpty() || it.component != null }

    /**
     * The ids of the turns a "Disconnected · new conversation" row is drawn
     * before: where the server began a new conversation under earlier lines.
     * The window's own rows — never turns, never in [lines].
     */
    val marks: Set<String> get() = marked.toSet()

    /** The customer is waiting on the agent's reply. */
    val awaitingReply: Boolean get() = awaitingUntil != null

    /** The agent is working on a reply: the customer is waiting on one, or a turn has begun with no words yet. */
    val typing: Boolean get() = awaitingUntil != null ||
        list.lastOrNull()?.let { it.role == Role.AGENT && it.live && it.text.isEmpty() && it.component == null } == true

    /** The agent's turn is open: words arriving now go into it rather than starting a new one. */
    val agentLive: Boolean get() = list.lastOrNull()?.let { it.role == Role.AGENT && it.live } == true

    /** The customer's line. Whatever the agent was writing is finished: they have moved on. Returns the turn's id. */
    fun customer(text: String, now: Long, wallMs: Long = now, delivery: GraineChatTurn.Delivery = GraineChatTurn.Delivery.SENT): String {
        closeLive()
        // Said on the connection: its session. Waiting for one: the session of the connection that sends it.
        val turn = GraineChatTurn(take(), Role.CUSTOMER, text, java.util.Date(wallMs), delivery,
            session = if (delivery == GraineChatTurn.Delivery.SENT) session else null)
        add(turn)
        awaitingUntil = now + Widgets.AWAITING_REPLY_MS
        return turn.id
    }

    /** A turn began. True when the agent spoke first — not in answer to the customer's last line. */
    fun agentTurnStart(wallMs: Long): Boolean {
        closeLive()
        firstTurn()
        val volunteered = list.lastOrNull()?.role != Role.CUSTOMER
        add(GraineChatTurn(take(), Role.AGENT, "", java.util.Date(wallMs), proactive = volunteered, live = true, session = session))
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
     * since, to a turn of its own. The first thing the agent sends in a new
     * conversation is a turn of its own: the turns above it are another's.
     */
    fun component(c: GraineComponent, wallMs: Long) {
        val fresh = firstTurn()
        val i = list.indexOfLast { it.role == Role.AGENT }
        val lastCustomer = list.indexOfLast { it.role == Role.CUSTOMER }
        if (!fresh && i >= 0 && i > lastCustomer) {
            list[i] = list[i].copy(component = c)
        } else {
            val proactive = list.lastOrNull()?.role != Role.CUSTOMER
            add(GraineChatTurn(take(), Role.AGENT, "", java.util.Date(wallMs), proactive = proactive, component = c, session = session))
        }
        awaitingUntil = null
    }

    /** Every customer message at [from] moves to [to]: sent once the chat connects, failed when it gives up, sending on a retry. */
    fun deliver(from: GraineChatTurn.Delivery, to: GraineChatTurn.Delivery): Boolean {
        var changed = false
        for (i in list.indices) if (list[i].role == Role.CUSTOMER && list[i].delivery == from) { list[i] = list[i].copy(delivery = to); changed = true }
        return changed
    }

    /**
     * WHO A CHAT BELONGS TO (contract/DESIGN.md): the identity changed, so the
     * turns this transcript carried are another identity's. Every one goes,
     * with what a history or a new conversation had settled and the marker
     * rows, except the customer's lines that have not gone out — waiting or
     * failed — which stay, in order, still waiting. Returns their words, in
     * order, for the next connection to send.
     */
    fun forgetAllButUnsent(): List<String> {
        list.retainAll { it.role == Role.CUSTOMER && (it.delivery == GraineChatTurn.Delivery.SENDING || it.delivery == GraineChatTurn.Delivery.FAILED) }
        // The lines kept are said in the session of the connection that sends them, not the identity before's.
        for (i in list.indices) if (list[i].session != null) list[i] = list[i].copy(session = null)
        awaitingUntil = null; settledAtOpen = null
        firstTurnDue = false; marked.clear(); markNext = false
        session = null; stamping = false; unstamped.clear()
        return list.map { it.text }
    }

    /** The conversation dropped: no line is still being written. */
    fun disconnected() {
        closeLive()
        awaitingUntil = null
    }

    /** Every line goes, and the marker rows between them. */
    fun clear() {
        list.clear(); awaitingUntil = null; settledAtOpen = null
        firstTurnDue = false; marked.clear(); markNext = false
        unstamped.clear()
    }

    /**
     * The connection opened, before what was waiting goes out: what is settled
     * now is what the next history replaces. A message still waiting goes out
     * right after this, and stays. The agent's first turn on it, with no
     * history before, tells that the server began a new conversation.
     */
    fun opened() {
        settledAtOpen = list.filter { it.delivery == GraineChatTurn.Delivery.SENT && !it.live }.map { it.id }.toSet()
        firstTurnDue = true
        // A new connection: its session is its own, unknown until its frame comes. What waited goes on it.
        session = null
        stamping = true
        unstamped.clear()
        for (turn in list) if (turn.role == Role.CUSTOMER && turn.delivery != GraineChatTurn.Delivery.SENT && turn.session == null) unstamped.add(turn.id)
    }

    /**
     * THE SESSION FRAME (history.json `sessionFrame`): this connection's
     * session — [id] null for one that is not a non-empty string. Every turn
     * the connection carried before it — the lines waiting at its open, and
     * whatever was said meanwhile — takes it.
     */
    fun sessionStarted(id: String?) {
        session = id
        stamping = false
        if (id != null) for (i in list.indices) if (list[i].id in unstamped && list[i].session == null) list[i] = list[i].copy(session = id)
        unstamped.clear()
    }

    /**
     * An earlier conversation, restored: its turns, then every turn the history
     * cannot hold yet — still sending or failed, or said since the connection
     * opened — in order. A reconnect's history holds what the last one did and
     * what was said since, so it replaces both, and nothing shows twice. With
     * no open yet, only the turns still to go are kept. A marker row before a
     * turn it replaced goes with it. False when there was nothing to restore.
     * Any history — one that restores nothing too — is the server continuing
     * the conversation: the agent's next turn is not a new one's.
     */
    fun restore(restored: List<GraineChatTurn>): Boolean {
        firstTurnDue = false
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
        marked.retainAll(kept.mapTo(HashSet()) { it.id })
        markNext = false
        while (list.size > cap) marked.remove(list.removeAt(0).id)
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

    /**
     * The agent's first turn on a connection that brought no history frame:
     * the server began a new conversation. The current one — what was settled at
     * that open, from the last marker row on — decides: none → nothing; no
     * line of the customer's from the last marker row on, settled or still to
     * go (an opening nobody answered) → it goes, the new opening in its place
     * (a marker row before it now goes before what follows); else a marker row before
     * the first turn not settled at that open — what went out once it opened
     * is the new conversation's. True when this turn begins a conversation of
     * its own.
     */
    private fun firstTurn(): Boolean {
        if (!firstTurnDue) return false
        firstTurnDue = false
        val settled = settledAtOpen ?: return true
        val from = maxOf(0, list.indexOfLast { it.id in marked })
        val current = (from until list.size).filter { list[it].id in settled }
        when {
            current.isEmpty() -> Unit
            (from until list.size).none { list[it].role == Role.CUSTOMER } -> {
                var moved = false
                for (i in current.asReversed()) if (marked.remove(list.removeAt(i).id)) moved = true
                if (moved) markAt(from)
            }
            else -> markAt((from until list.size).firstOrNull { list[it].id !in settled } ?: list.size)
        }
        return true
    }

    /** A marker row before the turn at [index] — before the next turn added when there is none there yet. */
    private fun markAt(index: Int) {
        if (index < list.size) marked.add(list[index].id) else markNext = true
    }

    private fun closeLive() {
        val i = list.indexOfLast { it.live }
        if (i < 0) return
        if (list[i].text.isBlank() && list[i].component == null) {
            // A turn that said nothing is dropped: a marker row before it goes before whatever comes next.
            if (marked.remove(list.removeAt(i).id)) markAt(i)
        } else list[i] = list[i].copy(live = false)
    }

    private fun add(turn: GraineChatTurn) {
        list.add(turn)
        if (markNext) { marked.add(turn.id); markNext = false }
        // Said on this connection before its session frame: stamped when the frame comes.
        if (stamping && turn.session == null && !turn.restored) unstamped.add(turn.id)
        while (list.size > cap) list.removeAt(0).id.let { marked.remove(it); unstamped.remove(it) }
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
