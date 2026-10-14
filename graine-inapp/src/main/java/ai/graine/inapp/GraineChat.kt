package ai.graine.inapp

import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet

/**
 * THE TEXT CONVERSATION WITH THE AGENT — the chat the voice bar switches to,
 * and what [GraineChatView] draws. `GraineInApp.get().chat`.
 *
 *     val chat = GraineInApp.get().chat
 *     chat.connect()                 // a chat window does this itself when it comes on screen
 *     chat.send("Where is my order?")
 *     chat.messages                  // one GraineChatTurn per turn, the agent's streaming in
 *
 * Each agent of an app has its own (`GraineInApp.get("support").chat`): its
 * turns, its socket, its key; one agent's lines never reach another's.
 *
 * The agent sees the same screen, runs the same actions and draws the same
 * components as on a call; its words stream into one bubble per turn. A line
 * typed while the chat is connecting — or while it reconnects after a drop,
 * which it does by itself while the customer is in it — is sent as soon as it
 * can be, never lost. One conversation at a time, across every agent:
 * opening the chat ends a voice call (and another agent's chat), starting a
 * call ends the chat, and a native call holds the chat until it is over.
 *
 * Main thread. Changes reach [GraineListener.onChatState] and
 * [GraineListener.onChatMessage], and every [ChatSource] change listener.
 */
class GraineChat internal constructor(
    /** The agent this conversation is with. */
    internal val graine: GraineInApp,
) : ChatSource {

    /** The conversation is up: lines go out as they are typed. */
    var connected = false
        private set

    /** It is being opened, or re-opened after a drop. */
    var connecting = false
        private set

    /** Nothing could connect it, after every retry. A [send] or [connect] tries again. */
    override var failed = false
        private set

    override val messages: List<GraineChatTurn> get() = transcript.lines

    /**
     * This user's calls still to come ([GraineInApp.scheduledCalls]), soonest
     * first, at most three — the chat window's Upcoming calls card. Fetched
     * when the chat opens and after the agent finishes a reply (it may have
     * just booked one), only where Graine rings this app.
     */
    var upcomingCalls: List<GraineScheduledCall> = emptyList()
        private set
    /** Calls being cancelled from the card, and those whose cancel failed (a tap retries). */
    internal val cancelling = HashSet<String>()
    internal val cancelFailed = HashSet<String>()
    private var lastUpcomingFetch = 0L
    override val online: Boolean get() = connected
    override val typing: Boolean get() = transcript.typing

    private val main get() = graine.main
    private val transcript = ChatTranscript()
    private val reconnect = ChatReconnect()
    private val scheduler by lazy { graine.env.scheduler ?: ExecutorScheduler("graine-chat") }
    private val changeListeners = CopyOnWriteArraySet<Runnable>()

    private var session: TextChatSession? = null
    /** Waiting for the agent's configuration before the first session. */
    private var opening = false
    /** Lines not yet handed to a session: typed before one existed, or left unsent by one that dropped. */
    private val outbox = ArrayList<String>()
    private var unwatchContext: (() -> Unit)? = null
    /** The customer is in the chat: a window shows it, or the app opened it. A drop reconnects only then. */
    private var wanted = false
    /** Chat windows on screen now. */
    private var surfaces = 0
    private var retry: Runnable? = null
    private val closeUnseen = Runnable { if (surfaces == 0) close() }
    private val tick = Runnable { if (transcript.tick(SystemClock.uptimeMillis())) changed(); scheduleTick() }

    init {
        graine.foregroundWatchers.add { fg -> onMain { onForeground(fg) } }
        // Who is signed in decides whether the chat window offers "Book a call": windows on screen redraw.
        graine.onIdentity { changed() }
    }

    // ── what an app calls ─────────────────────────────────────────────────

    /**
     * The size the last [open] asked for, until [close]; null follows
     * [GraineConfig.chatSize], then the dashboard's. A [GraineAgentBarView]
     * draws the chat at it — and moving to the call and back keeps it.
     */
    var openedSize: GraineChatSize? = null
        private set
    /** Counts the app's opens, so a bar on screen moves to the chat when the app opens it. */
    internal var opens = 0
        private set

    /**
     * Open the chat — at [size] for this open, else [GraineConfig.chatSize],
     * else the dashboard's (Window size). A [GraineAgentBarView] on screen
     * moves to the chat, where the agent offers one, and draws it at that
     * size; the size lasts until [close].
     */
    @JvmOverloads
    fun open(size: GraineChatSize? = null): Unit = onMain {
        openedSize = size
        opens++
        connect()
    }

    /** Open the conversation, if it is not open or opening. A chat window on screen does this by itself. */
    fun connect(): Unit = onMain {
        wanted = true
        failed = false
        reconnect.reset()
        transcript.deliver(GraineChatTurn.Delivery.FAILED, GraineChatTurn.Delivery.SENDING)
        openSession()
        fetchUpcoming(force = true)
        changed()
    }

    /** Fetch the upcoming calls again (the card refreshes itself; call this after booking one elsewhere). */
    fun refreshUpcomingCalls(): Unit = onMain { fetchUpcoming(force = true) }

    /** Cancel one of the card's calls: its row says "Cancelling…" until it is done, then goes; a failure says so. */
    fun cancelUpcomingCall(id: String): Unit = onMain {
        if (id in cancelling) return@onMain
        cancelling.add(id)
        cancelFailed.remove(id)
        changed()
        graine.cancelScheduledCall(id) { _, error ->
            cancelling.remove(id)
            if (error != null) cancelFailed.add(id) else upcomingCalls = upcomingCalls.filter { it.id != id }
            changed()
        }
    }

    /** At most every ten seconds unless asked, and only where Graine rings this app. */
    private fun fetchUpcoming(force: Boolean) {
        if (!graine.schedulingRules.ringsAndroid) return
        val now = SystemClock.uptimeMillis()
        if (!force && lastUpcomingFetch != 0L && now - lastUpcomingFetch < UPCOMING_EVERY_MS) return
        lastUpcomingFetch = now
        graine.scheduledCalls { calls, _ ->
            val shown = UpcomingCalls.shown(calls ?: return@scheduledCalls)
            if (shown != upcomingCalls) { upcomingCalls = shown; changed() }
        }
    }

    /** The card's rows, as the chat window draws them. */
    internal fun upcomingRows(): List<ChatWindowView.UpcomingRow> = upcomingCalls.mapNotNull { c ->
        c.at?.let { ChatWindowView.UpcomingRow(c.id, it, c.id in cancelling, c.id in cancelFailed) }
    }

    /**
     * End the conversation: the agent is told it is over (the stop frame),
     * and no reconnect follows. The lines stay on screen; the next [connect]
     * or [send] starts a new conversation under them.
     */
    fun close(): Unit = onMain {
        openedSize = null
        hangUp()
    }

    /** Ends the conversation as [close] does, keeping the open's size: the bar moving to the call comes back to it. */
    internal fun hangUp(): Unit = onMain {
        wanted = false
        main.removeCallbacks(closeUnseen)
        cancelRetry()
        shut()
        setState(connected = false, connecting = false)
        changed()
    }

    /** The customer's line: on screen at once, sent when it can be. Blank lines are ignored. */
    override fun send(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        onMain {
            // On screen at once: sent now when the chat is up, else waiting for it.
            transcript.customer(line, SystemClock.uptimeMillis(), System.currentTimeMillis(),
                if (connected) GraineChatTurn.Delivery.SENT else GraineChatTurn.Delivery.SENDING)
            transcript.deliver(GraineChatTurn.Delivery.FAILED, GraineChatTurn.Delivery.SENDING)
            scheduleTick()
            graine.noteInteraction()
            report { it.onChatMessage(Role.CUSTOMER, line, false) }
            val s = session
            if (s != null) s.say(line) else outbox.add(line)
            if (session == null && !opening) {
                // Offline, gave up, or never opened: the customer typing is the customer asking again.
                wanted = true
                failed = false
                reconnect.reset()
                cancelRetry()
                openSession()
            }
            changed()
        }
    }

    /** Forget the lines on screen — a sign-out, a new topic. The conversation itself goes on. */
    fun clear(): Unit = onMain {
        transcript.clear()
        changed()
    }

    /**
     * START A NEW CONVERSATION — a fresh thread, as a support chat offers one.
     *
     * The chat hangs up, Graine lets go of the conversation it kept for this
     * agent and user (so the next chat does not continue it), the lines on
     * screen and any waiting to go are cleared, and — when the user was in the
     * chat — a fresh chat opens, with the welcome and the starters. What the
     * agent remembers about the user (memories) is untouched. Nobody signed
     * in: nothing was kept, so only the screen clears. The chat window's
     * header offers it while it shows an earlier conversation.
     *
     * [done] hears `null` when it is done, or the [GraineError] Graine
     * answered (`DEVICE_LIMIT`, `NETWORK`, …) — the chat is then as it was,
     * connected again. On the main thread.
     */
    @JvmOverloads
    fun newConversation(done: GraineCallback<Unit>? = null): Unit = onMain {
        val wasWanted = wanted
        // Hung up first: the conversation being let go of must not write another turn after it.
        if (wasWanted) { cancelRetry(); shut(); setState(connected = false, connecting = false); changed() }
        graine.letGoOfConversation { _, error ->
            if (error != null) {
                // Nothing was let go of: back to the conversation as it was.
                if (wasWanted && wanted) { open(); changed() }
                done?.onResult(null, error)
                return@letGoOfConversation
            }
            transcript.clear()
            outbox.clear()
            if (wasWanted && wanted) open()
            changed()
            done?.onResult(Unit, null)
        }
    }

    /** The user's memory was forgotten: the conversation is part of it — off the screen, and nothing waiting goes after. */
    internal fun memoryForgotten(): Unit = onMain {
        transcript.clear()
        outbox.clear()
        changed()
    }

    override fun shown(visible: Boolean): Unit = onMain {
        surfaces = (surfaces + if (visible) 1 else -1).coerceAtLeast(0)
        if (visible) {
            main.removeCallbacks(closeUnseen)
            if (!wanted || (session == null && !opening && retry == null)) connect()
        } else if (surfaces == 0 && wanted) {
            // Gone from every screen: the conversation is kept a moment for the next screen to take, then closed.
            main.removeCallbacks(closeUnseen)
            main.postDelayed(closeUnseen, CLOSE_UNSEEN_MS)
        }
    }

    override fun addChangeListener(listener: Runnable) { changeListeners.add(listener) }
    override fun removeChangeListener(listener: Runnable) { changeListeners.remove(listener) }

    // ── what the rest of the SDK calls ────────────────────────────────────

    /** A component answered in the chat: the structured payload and the sentence that is the customer's turn. */
    internal fun submitWidget(widgetId: String, data: Map<String, Any?>, summary: String) {
        session?.submitWidget(widgetId, data, summary)
    }

    /** Something the agent may speak up about, on the chat's socket. */
    internal fun reportEvent(name: String, data: Map<String, Any?>) {
        session?.reportEvent(name, data)
    }

    /** A voice call is starting: one conversation at a time. The lines stay. */
    internal fun yieldToCall() {
        if (session == null && !opening && retry == null) return
        hangUp()
    }

    /** A native call went live (it holds the chat) or ended (the chat comes back if the customer is in it). */
    internal fun nativeCall(live: Boolean): Unit = onMain {
        if (live) {
            cancelRetry()
            if (session != null || opening) { shut(); setState(connected = false, connecting = false); changed() }
        } else if (wanted && surfaces > 0 && session == null && !opening) {
            openSession()
            changed()
        }
    }

    /** Signed out, or another user: their conversation is not the next user's. */
    internal fun forgetUser(): Unit = onMain {
        val was = wanted
        shut()
        transcript.clear()
        outbox.clear()
        setState(connected = false, connecting = false)
        if (was && surfaces > 0) openSession()
        changed()
    }

    // ── connecting ────────────────────────────────────────────────────────

    private fun openSession() {
        if (session != null || opening) return
        // One call at a time: a native call holds the line; the chat comes back when it ends.
        if (graine.nativeCallLive()) return
        // Into the chat, the call ends — and another agent's chat: two conversations at once is two agents answering.
        graine.chatOpening()
        cancelRetry()
        opening = true
        setState(connected = false, connecting = true)
        graine.loadSession { s ->
            opening = false
            if (!wanted || session != null) { if (!wanted) setState(connected = false, connecting = false); return@loadSession }
            if (s == null) { dropped(); return@loadSession }
            start(s)
        }
    }

    private fun start(s: SessionConfig) {
        val events = Events()
        val sess = TextChatSession(
            ChatSessionConfig(
                baseUrl = graine.config.baseUrl,
                publishableKey = graine.config.publishableKey,
                agentId = s.agentId,
                webcallAgentId = s.webcallAgentId,
                wsHost = s.wsHost,
                variables = graine.currentVariables(),
                identity = graine.identityEnvelope(),
            ),
            ChatSessionDeps(
                scheduler = scheduler,
                http = graine.env.http,
                openSocket = graine.env.openSocket ?: { url, protocols, listener -> WebSocketClient(url, protocols, listener).connect() },
                runAction = { name, args, answer -> main.post { graine.executeAction(name, args, answer) } },
                contextFrame = { graine.currentContextFrame() },
                io = graine.env.ticketIo ?: { work -> Thread({ work() }, "graine-chat-ticket").apply { isDaemon = true }.start() },
            ),
            events,
            ArrayList(outbox),
        )
        events.of = sess
        outbox.clear()
        session = sess
        unwatchContext = graine.onContext { frame -> session?.sendContext(frame) }
        sess.start()
    }

    /** The socket and what watches it, let go; the stop frame goes out if it was open. */
    private fun shut() {
        opening = false
        unwatchContext?.invoke(); unwatchContext = null
        session?.close()
        session = null
        transcript.disconnected()
    }

    /** The session ended without this side asking: come back if the customer is still in the chat. */
    private fun dropped() {
        unwatchContext?.invoke(); unwatchContext = null
        session = null
        transcript.disconnected()
        if (!wanted || !graine.foreground || graine.nativeCallLive()) {
            setState(connected = false, connecting = false)
            changed()
            return
        }
        val delay = reconnect.next()
        if (delay == null) {
            failed = true
            // Every retry spent: what was waiting to go is marked as not sent.
            transcript.deliver(GraineChatTurn.Delivery.SENDING, GraineChatTurn.Delivery.FAILED)
            setState(connected = false, connecting = false)
            changed()
            return
        }
        setState(connected = false, connecting = true)
        val r = Runnable { retry = null; if (wanted && session == null) openSession() }
        retry = r
        main.postDelayed(r, delay)
        changed()
    }

    private fun cancelRetry() {
        retry?.let { main.removeCallbacks(it) }
        retry = null
    }

    private fun onForeground(fg: Boolean) {
        if (!fg) {
            // As a call does: a conversation does not go on in the pocket. It comes back with the app.
            if (graine.config.endOnBackground && (session != null || opening || retry != null)) {
                cancelRetry()
                shut()
                setState(connected = false, connecting = false)
                changed()
            }
        } else if (wanted && surfaces > 0 && session == null && !opening && retry == null && !failed) {
            openSession()
            changed()
        }
    }

    /** What the socket reports, on its thread: each hops to the main thread and is dropped if its session is gone. */
    private inner class Events : ChatSessionEvents {
        /** The session these events are about; set before it starts, so before anything is reported. */
        @Volatile var of: TextChatSession? = null

        private fun mine(block: () -> Unit) {
            main.post { if (of != null && session === of) block() }
        }

        override fun onOpen() = mine {
            failed = false
            // What a history on this connection replaces: what is settled now, before the waiting lines go.
            transcript.opened()
            // The session sends what was waiting right after it opens.
            transcript.deliver(GraineChatTurn.Delivery.SENDING, GraineChatTurn.Delivery.SENT)
            setState(connected = true, connecting = false)
            changed()
        }

        override fun onFailed(error: String) = mine {
            CallLog.debug("chat: $error")
            dropped()
        }

        override fun onClosed(code: Int, error: String?, unsent: List<String>) = mine {
            outbox.addAll(0, unsent)
            CallLog.debug("chat closed ($code${error?.let { ": $it" } ?: ""})")
            dropped()
        }

        override fun onAgentTurnStart() = mine {
            transcript.agentTurnStart(System.currentTimeMillis())
            changed()
        }

        override fun onAgentWords(text: String) = mine {
            transcript.agentWords(text, System.currentTimeMillis())
            // A reply came: this connection works, and a later drop starts the ladder again.
            reconnect.worked()
            val line = transcript.lines.lastOrNull()
            if (line != null) report { it.onChatMessage(Role.AGENT, line.text, true) }
            changed()
        }

        override fun onAgentTurnEnd() = mine {
            val line = transcript.lines.lastOrNull { it.role == Role.AGENT && it.live }
            transcript.agentTurnEnd()
            if (line != null && line.text.isNotBlank()) report { it.onChatMessage(Role.AGENT, line.text, false) }
            // The agent may have just booked a call for the customer.
            fetchUpcoming(force = false)
            changed()
        }

        override fun onWidget(widget: JSONObject) = mine {
            // Retained before it is dispatched, so a view mounted later still finds it — as on a call.
            graine.retainWidget(widget)
            GraineWidget.adopt(widget, graine.config.baseUrl)?.let { transcript.component(it, System.currentTimeMillis()); changed() }
            report { it.onWidget(widget) }
        }

        override fun onWidgetPending(hint: JSONObject) = mine { report { it.onWidgetPending(hint) } }

        override fun onHistory(turns: List<GraineChatTurn>) = mine {
            // The conversation continues: the earlier lines, before any agent message. Never reported as new ones.
            if (transcript.restore(turns)) changed()
        }
    }

    // ── plumbing ──────────────────────────────────────────────────────────

    private fun setState(connected: Boolean, connecting: Boolean) {
        val changed = connected != this.connected || connecting != this.connecting
        val liveChanged = connected != this.connected
        this.connected = connected
        this.connecting = connecting
        if (liveChanged) graine.conversationChanged()
        if (changed) report { it.onChatState(connected, connecting) }
    }

    private fun changed() {
        changeListeners.forEach { runCatching { it.run() } }
    }

    private fun scheduleTick() {
        main.removeCallbacks(tick)
        val at = transcript.nextDeadline() ?: return
        main.postDelayed(tick, (at - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private inline fun report(crossinline f: (GraineListener) -> Unit) {
        graine.listeners.forEach { l -> runCatching { f(l) } }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        /** How long a conversation no window shows is kept for the next screen to take. */
        const val CLOSE_UNSEEN_MS = 15_000L
        /** The upcoming calls are fetched again at most this often, unless asked. */
        const val UPCOMING_EVERY_MS = 10_000L
    }
}
