package ai.graine.inapp

import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

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
 * Closing the chat window ends the conversation shortly after: once no window
 * shows it, it closes [closeDelaySeconds] later — 15 seconds unless
 * [GraineConfig.chatCloseDelaySeconds] says otherwise — unless a window shows
 * it again or a message goes either way in that time (a quick hop to the
 * booking sheet or a component keeps it). Opening the chat again afterwards
 * connects again, and a signed-in user's conversation continues from Graine's
 * history, with no new welcome. When Graine ends the conversation itself —
 * idle, or at its time limit — nothing reconnects by itself: the lines stay,
 * the window says "Disconnected", and opening the chat or a [send] connects again.
 *
 * The user's earlier sessions with the agent, and each one's chat history,
 * are [pastSessions] and [sessionTranscript].
 *
 * The agent may suggest replies for its turn ([quickReplies], sent with
 * [sendQuickReply]) and end the conversation ([ended]), after which the
 * chat window asks how it went where the design does ([rating], [onRated],
 * [rate]). The app can pin what the conversation is about ([pin]) and take
 * attachments ([onAttach]).
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

    /**
     * The conversation dropped — or a try to open it failed — while the
     * customer is in the chat, and it is being made again: from then until it
     * is connected, gives up ([failed]) or is closed. [connecting] is true
     * too. Say "Reconnecting…", not "Offline", while it is.
     */
    override val reconnecting: Boolean get() = remaking && connecting
    /** A drop's retries are under way: set when one is scheduled, cleared when connected, given up or hung up. */
    private var remaking = false
    /** This conversation has been connected at least once: a reconnect after that is announced (the reconnect banner). */
    internal var everConnected = false
        private set

    /**
     * Graine ended the conversation itself — idle, or at the session's time
     * limit — and nothing reconnects by itself: the window says
     * "Disconnected" until the customer opens the chat or sends again. A chat
     * of your own says it too; [connect] or [send] connects again.
     */
    var disconnected = false
        private set

    override val messages: List<GraineChatTurn> get() = transcript.lines

    /**
     * Where the server began a new conversation under earlier lines: the ids of
     * the turns the chat window draws "Disconnected · new conversation" before.
     * The window's own rows, never turns: [messages] does not change for them.
     */
    internal val marks: Set<String> get() = transcript.marks

    /**
     * The ids of the turns a "Disconnected · new conversation" row goes before
     * — where the server began a new conversation under earlier lines — oldest
     * first; empty when there are none. For a chat of your own:
     * [ChatRows.build] places the rows from it. Never turns: [messages] does
     * not change for them, and [newConversation] or [clear] takes them away
     * with the lines.
     */
    val newConversationBefore: List<String> get() = transcript.marks.toList()

    /**
     * The id of the chat's current session — this connection's run, the id
     * Execution Logs shows for it — from the server's session frame. Null
     * until it arrives (each new connection starts without one), and with a
     * server that sends none. A turn said now carries it ([GraineChatTurn.session]).
     * Changes reach every change listener. New in 0.14.0.
     */
    val sessionId: String? get() = transcript.session

    /**
     * The sessions the conversation on screen holds, oldest first: each run of
     * consecutive turns said in the same session ([GraineChatSession]) — a
     * chat connection's, or a call's — with the current one marked. Turns kept
     * before sessions were recorded make one with no id; with a server that
     * sends none, the whole conversation is one session with no id. For a
     * "past conversations" list, or session dividers of your own. Derived
     * from [messages] and [sessionId], so it changes when they do. New in 0.14.0.
     */
    val sessions: List<GraineChatSession> get() = ChatSessions.of(messages, sessionId)

    /**
     * THE USER'S PAST SESSIONS with this agent — their earlier chats, talks
     * and calls, newest first, as Execution History lists them
     * ([GrainePastSessions]: `sessions`, `loading`, `hasMore`, `error`). Empty
     * until [refreshPastSessions]; [loadMorePastSessions] appends the next
     * page, and [sessionTranscript] answers one session's chat history. Only
     * for the user identify() names, from a device bound to them — `error`
     * says why not (`identity_required`, `device_limit`, `rate_limited`,
     * `unavailable`, `network`) — and cleared when the identity changes
     * (another user, or nobody). The session open now is never in it. Changes
     * reach every change listener. New in 0.14.3.
     */
    var pastSessions: GrainePastSessions = GrainePastSessions()
        private set

    /**
     * How long the conversation stays connected once no window shows it, in
     * seconds: it closes this long after the last window left it — or after
     * the last message either way, whichever is later — unless a window shows
     * it again first. Starts as [GraineConfig.chatCloseDelaySeconds] (15 by
     * default); 0 closes it as soon as the last window leaves. Kept within
     * 0–300 (a negative value is 0). Set it any time: a close already waiting
     * takes the new delay. New in 0.14.3.
     */
    var closeDelaySeconds: Int
        get() = (closeDelayMs / 1000L).toInt()
        set(value) {
            closeDelayMs = closeDelayMsOf(value)
            onMain { if (unseen) closeWhenIdle() }
        }

    @Volatile private var closeDelayMs: Long = closeDelayMsOf(graine.config.chatCloseDelaySeconds)

    /**
     * The replies the agent suggests for its latest turn — drawn as chips by
     * the SDK's chat window; read them to draw your own, and send one with
     * [sendQuickReply]. Gone once the customer sends anything, the agent starts
     * another turn, an earlier conversation is restored, or the conversation
     * ends.
     */
    override var quickReplies: List<GraineQuickReply> = emptyList()
        private set

    /**
     * The agent ended the conversation: the chat window asks for a rating where
     * the design does. The customer sending anything starts over.
     */
    override var ended: Boolean = false
        private set

    /** The customer's answer to "How was this chat?", once given — one per conversation. */
    override var rating: GraineChatRating? = null
        private set

    /**
     * The customer answered the rating ([rate], or the chat window's rating
     * card). On the main thread. Also sent to the conversation as a quiet event.
     */
    var onRated: ((GraineChatRating) -> Unit)? = null

    /**
     * The app handles attachments: the chat window draws its paperclip (where
     * the design asks for one) and a tap calls this. The SDK sends nothing
     * itself — pick and upload the file, then tell the agent about it as you
     * would any screen. Null: no paperclip.
     */
    var onAttach: (() -> Unit)? = null
        set(v) { field = v; onMain { changed() } }

    /** The card the app pinned ([pin]), or null. */
    var pinned: GraineChatPin? = null
        private set

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
    /** Every window that showed the conversation has left it: it closes [closeDelaySeconds] after that, or after its last message. */
    private var unseen = false
    /** When the last window left it, on the scheduler's clock. */
    private var unseenSince = 0L
    /** When the conversation last opened or carried a message either way, on the scheduler's clock: idle since then. */
    private var lastActivity = 0L
    private val closeUnseen = Runnable { if (unseen) closeWhenIdle() }
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

    // ── past sessions ─────────────────────────────────────────────────────

    /** Moves on with every refresh and every change of identity: an answer to a request made before is not shown. */
    private var pastGeneration = 0
    /** Where the next page starts — the last page's `next`; null before the first page and after the last. */
    private var pastCursor: String? = null

    /**
     * Load the first page of [pastSessions] — the user's most recent
     * sessions with this agent — replacing what was loaded. While it loads,
     * `loading` is true and what was loaded stays; a refusal keeps it too,
     * with `error` saying why. With nobody signed in nothing is asked: `error`
     * is `identity_required` at once. New in 0.14.3.
     */
    fun refreshPastSessions(): Unit = onMain { fetchPastSessions(more = false) }

    /**
     * Load the next page of [pastSessions] and append it — a session already
     * listed is not listed twice. Nothing happens while a page is loading, and
     * when there is no next page (`hasMore` false, or nothing loaded yet:
     * [refreshPastSessions] loads the first). After a refusal, calling it
     * again asks for the same page. New in 0.14.3.
     */
    fun loadMorePastSessions(): Unit = onMain { fetchPastSessions(more = true) }

    /**
     * ONE PAST SESSION'S CHAT HISTORY, oldest first — the session as
     * [pastSessions] describes it, and every message said in it. [done] hears
     * it once, on the main thread, or why not: `INVALID_REQUEST` for a blank
     * id and `IDENTITY_REQUIRED` with nobody signed in (nothing is sent);
     * `NOT_FOUND` for a session Graine does not know or that is another
     * user's — and for an answer that arrives after the identity changed;
     * `DEVICE_LIMIT`, `RATE_LIMITED`, `UNAVAILABLE`, `NETWORK`. New in 0.14.3.
     */
    fun sessionTranscript(id: String, done: GraineCallback<GraineSessionTranscript>): Unit = onMain {
        val wanted = id.trim()
        if (wanted.isEmpty()) {
            main.post { done.onResult(null, GraineError(GraineErrorCode.INVALID_REQUEST, "No session id.")) }
            return@onMain
        }
        // Who the user is waits for the agent's settings: they name the trait that identifies them.
        graine.loadSession {
            val who = graine.identityEnvelope()
            val value = who.value
            if (value == null) {
                done.onResult(null, GraineError(GraineErrorCode.IDENTITY_REQUIRED, NOBODY))
                return@loadSession
            }
            graine.request(EmbedApi.SESSION_TRANSCRIPT, who.who(JSONObject()).put("id", wanted)) { r ->
                when {
                    // Asked for someone who is no longer the one signed in: not this user's session.
                    graine.identityEnvelope().value != value ->
                        done.onResult(null, GraineError(GraineErrorCode.NOT_FOUND, "That session is not this user's."))
                    r is EmbedReply.Ok -> done.onResult(PastSessionsWire.transcript(r.body, wanted), null)
                    r is EmbedReply.Failed -> done.onResult(null, r.error)
                }
            }
        }
    }

    /**
     * [sessionTranscript], suspending: the transcript, or a
     * [GraineRequestException] carrying what the callback would have heard.
     * New in 0.14.3.
     */
    suspend fun sessionTranscript(id: String): GraineSessionTranscript = suspendCoroutine { c ->
        sessionTranscript(id) { value, error ->
            if (value != null) c.resume(value)
            else c.resumeWithException(GraineRequestException(error ?: GraineError(GraineErrorCode.UNAVAILABLE, "Graine did not answer.")))
        }
    }

    /** Main thread. */
    private fun fetchPastSessions(more: Boolean) {
        val cursor = pastCursor
        if (more && (pastSessions.loading || cursor == null)) return
        if (!more) pastGeneration++
        val generation = pastGeneration
        setPastSessions(pastSessions.copy(loading = true, error = null))
        // Who the user is waits for the agent's settings: they name the trait that identifies them.
        graine.loadSession {
            if (pastGeneration != generation) return@loadSession
            val who = graine.identityEnvelope()
            if (who.value == null) {
                pastCursor = null
                setPastSessions(GrainePastSessions(error = GraineErrorCode.IDENTITY_REQUIRED.wire))
                return@loadSession
            }
            val body = who.who(JSONObject())
            if (more && cursor != null) body.put("cursor", cursor)
            graine.request(EmbedApi.PAST_SESSIONS, body) { r -> pastSessionsAnswered(r, more, generation) }
        }
    }

    /** Main thread. An answer asked for before a refresh or another identity is not shown. */
    private fun pastSessionsAnswered(r: EmbedReply, more: Boolean, generation: Int) {
        if (pastGeneration != generation) return
        when (r) {
            is EmbedReply.Ok -> {
                val page = PastSessionsWire.page(r.body)
                pastCursor = page.next
                val sessions = if (more) PastSessionsWire.appending(page.sessions, pastSessions.sessions) else page.sessions
                setPastSessions(GrainePastSessions(sessions, loading = false, hasMore = page.next != null, error = null))
            }
            // What was loaded stays, and so does the page asked for: asking again asks for it again.
            is EmbedReply.Failed -> setPastSessions(pastSessions.copy(loading = false, error = r.error.code.wire))
        }
    }

    private fun setPastSessions(next: GrainePastSessions) {
        if (next == pastSessions) return
        pastSessions = next
        changed()
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
        unseen = false
        main.removeCallbacks(closeUnseen)
        cancelRetry()
        shut()
        setState(connected = false, connecting = false)
        changed()
    }

    /**
     * PIN WHAT THE CONVERSATION IS ABOUT — a card the chat window draws under
     * its header or atop the thread, as the design says (nothing for a design
     * that hides it). `pin(null)` takes it away. Never sent to the agent: tell
     * the agent about the screen with the screen context, as always. Each line
     * is cleaned to one line and cut to its length.
     */
    fun pin(pin: GraineChatPin?): Unit = onMain {
        val title = pin?.let { ChatRules.line(it.title, PIN_TITLE_MAX) }.orEmpty()
        val next = if (pin == null || title.isEmpty()) null else GraineChatPin(
            title = title,
            subtitle = ChatRules.line(pin.subtitle, PIN_SUBTITLE_MAX).ifEmpty { null },
            imageUrl = pin.imageUrl?.trim()?.ifEmpty { null },
            badge = ChatRules.line(pin.badge, PIN_BADGE_MAX).ifEmpty { null },
        )
        if (next == pinned) return@onMain
        pinned = next
        changed()
    }

    /**
     * The customer's answer to "How was this chat?" — for an app drawing its
     * own rating: `style` "thumbs" (value 1 up, 0 down), "stars" or "emoji"
     * (1–5). Taken once per conversation and only after the agent ended it
     * ([ended]); sent to the conversation as a quiet event, kept in [rating]
     * and handed to [onRated]. Returns the rating taken, or null.
     */
    override fun rate(style: String, value: Int): GraineChatRating? {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { rate(style, value) }; return null }
        // One answer per conversation, and only to a conversation that ended.
        if (!ended || rating != null) return null
        val r = ChatRules.rating(style, value)
        rating = r
        session?.rate(r)
        changed()
        runCatching { onRated?.invoke(r) }
        return r
    }

    /** One of [quickReplies], sent as the customer's own message — a real turn, exactly as if typed. */
    fun sendQuickReply(reply: GraineQuickReply) = send(reply.text)

    /** The customer's line: on screen at once, sent when it can be. Blank lines are ignored. */
    override fun send(text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        onMain {
            // Whatever the agent suggested, the customer has moved on — past a conversation that had ended, too.
            goingOn()
            // On screen at once: sent now when the chat is up, else waiting for it.
            transcript.customer(line, SystemClock.uptimeMillis(), System.currentTimeMillis(),
                if (connected) GraineChatTurn.Delivery.SENT else GraineChatTurn.Delivery.SENDING)
            transcript.deliver(GraineChatTurn.Delivery.FAILED, GraineChatTurn.Delivery.SENDING)
            scheduleTick()
            active()
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
        fresh()
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
            fresh()
            if (wasWanted && wanted) open()
            changed()
            done?.onResult(Unit, null)
        }
    }

    /** The user's memory was forgotten: the conversation is part of it — off the screen, and nothing waiting goes after. */
    internal fun memoryForgotten(): Unit = onMain {
        transcript.clear()
        outbox.clear()
        fresh()
        changed()
    }

    override fun shown(visible: Boolean): Unit = onMain {
        surfaces = (surfaces + if (visible) 1 else -1).coerceAtLeast(0)
        if (visible) {
            unseen = false
            main.removeCallbacks(closeUnseen)
            if (!wanted || (session == null && !opening && retry == null)) connect()
        } else if (surfaces == 0 && wanted) {
            // Gone from every screen: kept for a quick hop (the booking sheet, a component), then closed.
            unseen = true
            unseenSince = scheduler.now()
            closeWhenIdle()
        }
    }

    override fun addChangeListener(listener: Runnable) { changeListeners.add(listener) }
    override fun removeChangeListener(listener: Runnable) { changeListeners.remove(listener) }

    // ── what the rest of the SDK calls ────────────────────────────────────

    /** A component answered in the chat: the structured payload and the sentence that is the customer's turn. */
    internal fun submitWidget(widgetId: String, data: Map<String, Any?>, summary: String) {
        session?.submitWidget(widgetId, data, summary)
        onMain { active() }
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
        } else if (wanted && surfaces > 0 && session == null && !opening && !disconnected) {
            openSession()
            changed()
        }
    }

    /**
     * WHO A CHAT BELONGS TO (contract/DESIGN.md): the identity changed —
     * nobody to a user (the app identified after the chat opened), a user to
     * another, or a user to nobody. A connection is whoever was identified
     * when its ticket was made, so:
     *  1. the one connected or connecting is closed normally — no
     *     "Disconnected", no reconnect banner;
     *  2. the turns it carried go, its history and marker rows with them, and
     *     the booked calls shown for the identity before;
     *  3. a line the customer typed that has not gone out stays, and goes on
     *     the next connection;
     *  4. a chat that was connected or connecting and is wanted connects again
     *     at once, with a ticket for the new identity; otherwise the next open
     *     connects;
     *  5. the past sessions are the identity's too: cleared, and a page still
     *     on its way for the identity before is never shown.
     * Called only when the identity's value changed: other traits, or the
     * memory choice, change nothing on an open chat.
     */
    internal fun identityChanged(): Unit = onMain {
        val live = connected || connecting
        shut()
        cancelRetry()
        val unsent = transcript.forgetAllButUnsent()
        outbox.clear()
        outbox.addAll(unsent)
        fresh()
        upcomingCalls = emptyList()
        cancelling.clear()
        cancelFailed.clear()
        lastUpcomingFetch = 0L
        pastGeneration++
        pastCursor = null
        pastSessions = GrainePastSessions()
        disconnected = false
        remaking = false
        setState(connected = false, connecting = false)
        if (live && wanted) {
            failed = false
            reconnect.reset()
            transcript.deliver(GraineChatTurn.Delivery.FAILED, GraineChatTurn.Delivery.SENDING)
            openSession()
            fetchUpcoming(force = true)
        }
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
        // Opening it again is what a conversation Graine ended waits for; one being opened is not idle.
        disconnected = false
        active()
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
        // The agent ended this conversation and the server let it go, or Graine ended it itself (idle, or its time
        // limit): nothing comes back by itself. The customer's next message, or opening the chat, connects again
        // (send, shown), as after any close.
        if (!wanted || !graine.foreground || graine.nativeCallLive() || ended || disconnected) {
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
        remaking = true
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
        } else if (wanted && surfaces > 0 && session == null && !opening && retry == null && !failed && !disconnected) {
            openSession()
            changed()
        }
    }

    /**
     * No window shows the conversation: it closes [closeDelaySeconds] after
     * the last window left it or after its last message, whichever is later —
     * now, when that time has passed, else when it comes. A message before
     * then starts the wait again ([active]).
     */
    private fun closeWhenIdle() {
        main.removeCallbacks(closeUnseen)
        val left = maxOf(lastActivity, unseenSince) + closeDelayMs - scheduler.now()
        if (left > 0) main.postDelayed(closeUnseen, left) else close()
    }

    /** The conversation opened or carried a message, either way: it is not idle — and, with no window showing it, its wait starts again. */
    private fun active() {
        lastActivity = scheduler.now()
        if (unseen) closeWhenIdle()
    }

    /** What the socket reports, on its thread: each hops to the main thread and is dropped if its session is gone. */
    private inner class Events : ChatSessionEvents {
        /** The session these events are about; set before it starts, so before anything is reported. */
        @Volatile var of: TextChatSession? = null

        private fun mine(block: () -> Unit) {
            main.post { if (of != null && session === of) block() }
        }

        /** As [mine], for a message from the agent: the conversation is not idle. */
        private fun heard(block: () -> Unit) = mine { active(); block() }

        override fun onOpen() = mine {
            failed = false
            everConnected = true
            active()
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
            // Graine ended it itself — idle, or the session's time limit: it does not come back by itself.
            if (code == TextChatSession.CLOSE_IDLE || code == TextChatSession.CLOSE_TIME_LIMIT) disconnected = true
            dropped()
        }

        override fun onAgentTurnStart() = heard {
            transcript.agentTurnStart(System.currentTimeMillis())
            // A new turn: the replies suggested for the one before it are spent.
            quickReplies = emptyList()
            changed()
        }

        override fun onAgentWords(text: String) = heard {
            // Words with no open turn begin one, and that too spends the replies offered before it.
            if (!transcript.agentLive) quickReplies = emptyList()
            transcript.agentWords(text, System.currentTimeMillis())
            // A reply came: this connection works, and a later drop starts the ladder again.
            reconnect.worked()
            val line = transcript.lines.lastOrNull()
            if (line != null) report { it.onChatMessage(Role.AGENT, line.text, true) }
            changed()
        }

        override fun onAgentTurnEnd() = heard {
            val line = transcript.lines.lastOrNull { it.role == Role.AGENT && it.live }
            transcript.agentTurnEnd()
            if (line != null && line.text.isNotBlank()) report { it.onChatMessage(Role.AGENT, line.text, false) }
            // The agent may have just booked a call for the customer.
            fetchUpcoming(force = false)
            changed()
        }

        override fun onWidget(widget: JSONObject) = heard {
            // Retained before it is dispatched, so a view mounted later still finds it — as on a call.
            graine.retainWidget(widget)
            GraineWidget.adopt(widget, graine.config.baseUrl)?.let { transcript.component(it, System.currentTimeMillis()); changed() }
            report { it.onWidget(widget) }
        }

        override fun onWidgetPending(hint: JSONObject) = heard {
            graine.widgetOnItsWay(hint)
            report { it.onWidgetPending(hint) }
        }

        override fun onHistory(turns: List<GraineChatTurn>) = heard {
            // The conversation continues: the earlier lines, before any agent message. Never reported as new ones.
            // Replies suggested before it belong to a moment that has been replaced. A frame that restores nothing
            // still says the conversation continued: the agent's next turn opens no new one.
            if (transcript.restore(turns)) { quickReplies = emptyList(); changed() }
        }

        override fun onQuickReplies(replies: List<GraineQuickReply>) = heard {
            when {
                replies.isEmpty() -> quickReplies = emptyList()
                // Offered after the conversation ended, they are nobody's to tap.
                ended -> return@heard
                else -> quickReplies = replies.toList()
            }
            changed()
        }

        override fun onSession(id: String?) = mine {
            transcript.sessionStarted(id)
            changed()
        }

        override fun onConversationEnd() = heard {
            // The open turn was closed by the session; typing stops, the replies go, and the rating may be asked.
            transcript.disconnected()
            quickReplies = emptyList()
            ended = true
            changed()
        }
    }

    /** A conversation that goes on: no end, no rating, no replies left over from before. */
    private fun goingOn() {
        quickReplies = emptyList()
        ended = false
        rating = null
    }

    /** The chat on screen went: nothing of the last conversation's end, rating or replies is left. */
    private fun fresh() = goingOn()

    // ── plumbing ──────────────────────────────────────────────────────────

    private fun setState(connected: Boolean, connecting: Boolean) {
        // Connected, given up or hung up: whatever drop was being remade is over.
        if (!connecting) remaking = false
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
        /**
         * How long a conversation no window shows stays connected by default,
         * in milliseconds — 15 seconds after the last window left it, or after
         * its last message, whichever is later — before it closes
         * ([GraineConfig.chatCloseDelaySeconds] and [closeDelaySeconds] change
         * it). A window showing it again within that time keeps it, so a quick
         * hop to the booking sheet or a component goes on with the same
         * conversation. It was 5 minutes in 0.14.0–0.14.1.
         */
        const val CLOSE_UNSEEN_MS = 15_000L
        /** The longest [closeDelaySeconds] may be: 5 minutes, Graine's own idle limit. */
        internal const val CLOSE_DELAY_MAX_SECONDS = 300
        /** The message an identity is asked for in: nobody is signed in. */
        private const val NOBODY = "Nobody is signed in: identify() the user first."

        /** A close delay in seconds, kept within 0–[CLOSE_DELAY_MAX_SECONDS], as milliseconds. */
        internal fun closeDelayMsOf(seconds: Int): Long = seconds.coerceIn(0, CLOSE_DELAY_MAX_SECONDS) * 1000L
        /** The upcoming calls are fetched again at most this often, unless asked. */
        const val UPCOMING_EVERY_MS = 10_000L
        /** A pinned card's lines, in characters. */
        const val PIN_TITLE_MAX = 60
        const val PIN_SUBTITLE_MAX = 80
        const val PIN_BADGE_MAX = 24
    }
}
