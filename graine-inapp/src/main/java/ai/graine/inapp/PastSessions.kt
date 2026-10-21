package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject

/*
 * PAST SESSIONS (0.14.3; contract/PUBLIC-STATE.md "Past sessions", the wire
 * and its vectors in contract/past-sessions.json): the signed-in user's
 * earlier sessions with this agent, newest first — as the dashboard's
 * Execution History lists them — and each one's transcript. Only for the user
 * identify() names, from a device bound to them; never anyone else's.
 * [GraineChat.pastSessions] holds the list. The parsing here is pure, so every
 * vector is a JVM test.
 */

/** Where a past session happened ([GraineSessionSummary.channel]). */
enum class GraineSessionChannel(
    /** The word on the wire: `chat`, `voice` or `call`. */
    val wire: String,
) {
    /** Typed, in the chat. */
    CHAT("chat"),
    /** Talked with the agent in the app. */
    VOICE("voice"),
    /** A call with the agent — [GraineSessionSummary.direction] says who rang whom. */
    CALL("call"),
}

/** Who started a past call or talk ([GraineSessionSummary.direction]). */
enum class GraineSessionDirection(
    /** The word on the wire: `inbound` or `outbound`. */
    val wire: String,
) {
    /** The user reached the agent. */
    INBOUND("inbound"),
    /** The agent reached the user. */
    OUTBOUND("outbound"),
}

/**
 * ONE OF THE USER'S EARLIER SESSIONS with this agent, as
 * [GraineChat.pastSessions] lists them — newest first. [id] is the session's
 * id, the one Execution History shows; open its chat history with
 * [GraineChat.sessionTranscript].
 */
data class GraineSessionSummary @JvmOverloads constructor(
    /** The session's id — the id Execution History shows; pass it to [GraineChat.sessionTranscript]. */
    val id: String,
    /** When it started. */
    val startedAt: java.util.Date,
    /** When it ended; null when Graine did not say. */
    val endedAt: java.util.Date? = null,
    /** How long it lasted, in whole seconds; null when Graine did not say. */
    val durationSeconds: Int? = null,
    /** Typed ([GraineSessionChannel.CHAT]), talked in the app ([GraineSessionChannel.VOICE]) or a call ([GraineSessionChannel.CALL]). */
    val channel: GraineSessionChannel = GraineSessionChannel.CHAT,
    /** Who started a call or a talk; null for a chat, and when Graine did not say. */
    val direction: GraineSessionDirection? = null,
    /** How it ended, in plain words — `completed`, `missed`, `failed`, … — as Graine says it; "" when it did not. */
    val status: String = "",
    /** How many messages it holds. */
    val turns: Int = 0,
    /** The user's first line, at most 120 characters; "" when there is none. */
    val preview: String = "",
)

/** One message of a past session's transcript: who said it, what, and when. */
data class GraineSessionTurn(
    /** [Role.CUSTOMER] (the user) or [Role.AGENT]. */
    val role: Role,
    /** What was said. */
    val text: String,
    /** When it was said. */
    val at: java.util.Date,
)

/**
 * A PAST SESSION'S CHAT HISTORY, oldest first — what
 * [GraineChat.sessionTranscript] answers: the session as the list describes
 * it, and every message said in it.
 */
data class GraineSessionTranscript @JvmOverloads constructor(
    /** The session's id. */
    val id: String,
    /** When it started; null when Graine did not say. */
    val startedAt: java.util.Date? = null,
    /** When it ended; null when Graine did not say. */
    val endedAt: java.util.Date? = null,
    /** How long it lasted, in whole seconds; null when Graine did not say. */
    val durationSeconds: Int? = null,
    /** Typed, talked in the app or a call. */
    val channel: GraineSessionChannel = GraineSessionChannel.CHAT,
    /** Who started a call or a talk; null for a chat, and when Graine did not say. */
    val direction: GraineSessionDirection? = null,
    /** How it ended, in plain words, as Graine says it; "" when it did not. */
    val status: String = "",
    /** What was said, oldest first. */
    val turns: List<GraineSessionTurn> = emptyList(),
)

/**
 * THE USER'S PAST SESSIONS, AS STATE — [GraineChat.pastSessions]: the
 * sessions loaded so far (newest first), whether a page is loading, whether
 * there is another, and the last refusal's word.
 */
data class GrainePastSessions @JvmOverloads constructor(
    /** The sessions loaded so far, newest first; empty until [GraineChat.refreshPastSessions]. */
    val sessions: List<GraineSessionSummary> = emptyList(),
    /** A page is on its way. */
    val loading: Boolean = false,
    /** There is another page: [GraineChat.loadMorePastSessions] appends it. */
    val hasMore: Boolean = false,
    /**
     * Why the last page did not come, as a word — `identity_required`,
     * `device_limit`, `rate_limited`, `unavailable`, `network`, … ([GraineErrorCode.wire]);
     * null when it came.
     */
    val error: String? = null,
)

/**
 * A request to Graine that was refused, or got no answer, as the suspending
 * calls throw it ([GraineChat.sessionTranscript]): [error] is what the
 * callback form would have heard — branch on its [GraineError.code].
 */
class GraineRequestException(
    /** Why: the code to branch on, and the server's sentence. */
    val error: GraineError,
) : Exception(error.message) {
    /** The stable part to branch on: [GraineError.code]. */
    val code: GraineErrorCode get() = error.code

    override fun toString(): String = "GraineRequestException(${error.code.wire}): ${error.message}"
}

/**
 * THE PAST-SESSIONS WIRE, read (contract/past-sessions.json):
 * `/api/embed/sessions/list` → `{sessions, next}`,
 * `/api/embed/sessions/transcript` → `{id, startedAt, endedAt,
 * durationSeconds, channel, direction, status, turns}`. Every rule of the
 * contract's comment, and nothing else: an item without an id or a readable
 * `startedAt` is skipped; `endedAt` / `durationSeconds` unreadable → null; a
 * channel other than voice/call is chat; a direction other than
 * inbound/outbound is null; turns not a whole number ≥ 0 → 0; preview (and
 * status) not a string → ""; a turn without a user/assistant role, a string
 * text or a readable `at` is skipped.
 */
internal object PastSessionsWire {
    /** One page: its sessions as the SDK keeps them, and where the next page starts (null at the end). */
    data class Page(val sessions: List<GraineSessionSummary>, val next: String?)

    /** A page of the list; an id repeated within it is kept once, the first. */
    fun page(body: JSONObject): Page {
        val raw = body.opt("sessions") as? JSONArray
        val seen = HashSet<String>()
        val sessions = ArrayList<GraineSessionSummary>()
        if (raw != null) for (i in 0 until raw.length()) {
            val s = summary(raw.opt(i)) ?: continue
            if (seen.add(s.id)) sessions += s
        }
        return Page(sessions, cursor(body.opt("next")))
    }

    /** [kept], then the [page]'s sessions whose id is not kept already, in order. */
    fun appending(page: List<GraineSessionSummary>, kept: List<GraineSessionSummary>): List<GraineSessionSummary> {
        val seen = kept.mapTo(HashSet()) { it.id }
        return kept + page.filter { seen.add(it.id) }
    }

    /** One session of a page; null without an id or a readable `startedAt`. */
    fun summary(raw: Any?): GraineSessionSummary? {
        val o = raw as? JSONObject ?: return null
        val id = text(o.opt("id")) ?: return null
        val startedAt = date(o.opt("startedAt")) ?: return null
        return GraineSessionSummary(
            id = id,
            startedAt = startedAt,
            endedAt = date(o.opt("endedAt")),
            durationSeconds = seconds(o.opt("durationSeconds")),
            channel = channel(o.opt("channel")),
            direction = direction(o.opt("direction")),
            status = words(o.opt("status")),
            turns = count(o.opt("turns")),
            preview = words(o.opt("preview")),
        )
    }

    /** A transcript's answer; [asked] is its id when the answer does not say. */
    fun transcript(body: JSONObject, asked: String): GraineSessionTranscript {
        val raw = body.opt("turns") as? JSONArray
        val turns = ArrayList<GraineSessionTurn>()
        if (raw != null) for (i in 0 until raw.length()) turn(raw.opt(i))?.let { turns += it }
        return GraineSessionTranscript(
            id = text(body.opt("id")) ?: asked,
            startedAt = date(body.opt("startedAt")),
            endedAt = date(body.opt("endedAt")),
            durationSeconds = seconds(body.opt("durationSeconds")),
            channel = channel(body.opt("channel")),
            direction = direction(body.opt("direction")),
            status = words(body.opt("status")),
            turns = turns,
        )
    }

    /** One message: a user/assistant role, a string text and a readable `at`; null otherwise. */
    fun turn(raw: Any?): GraineSessionTurn? {
        val o = raw as? JSONObject ?: return null
        val role = role(o.opt("role")) ?: return null
        val text = o.opt("text") as? String ?: return null
        val at = date(o.opt("at")) ?: return null
        return GraineSessionTurn(role, text, at)
    }

    /** The wire's `user` is the user (the customer), its `assistant` the agent; any other role is nobody's. */
    fun role(raw: Any?): Role? = when (word(raw)) {
        "user" -> Role.CUSTOMER
        "assistant" -> Role.AGENT
        else -> null
    }

    fun channel(raw: Any?): GraineSessionChannel = when (word(raw)) {
        "voice" -> GraineSessionChannel.VOICE
        "call" -> GraineSessionChannel.CALL
        else -> GraineSessionChannel.CHAT
    }

    fun direction(raw: Any?): GraineSessionDirection? = word(raw)?.let { w -> GraineSessionDirection.values().firstOrNull { it.wire == w } }

    /** The next page's cursor: a string with something in it, as sent; null at the end. */
    fun cursor(raw: Any?): String? = text(raw)

    /** Whole seconds: a finite number ≥ 0, rounded; null for anything else (a string, a boolean). */
    fun seconds(raw: Any?): Int? {
        val d = Js.finite(raw)?.takeIf { it >= 0 && it < Int.MAX_VALUE } ?: return null
        return Math.round(d).toInt()
    }

    /** A count: a whole number ≥ 0; 0 for anything else. */
    fun count(raw: Any?): Int {
        val d = Js.finite(raw)?.takeIf { it >= 0 && it % 1.0 == 0.0 && it <= Int.MAX_VALUE } ?: return 0
        return d.toInt()
    }

    /** A string as sent; "" for anything else. */
    fun words(raw: Any?): String = raw as? String ?: ""

    /** An ISO-8601 time, as the wire writes it; null for anything else — a number, "yesterday". */
    fun date(raw: Any?): java.util.Date? = Scheduling.parseIso(raw as? String)?.let { java.util.Date(it) }

    /** A string with something in it, as sent. */
    private fun text(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotBlank() }

    private fun word(raw: Any?): String? = (raw as? String)?.trim()?.lowercase()
}
