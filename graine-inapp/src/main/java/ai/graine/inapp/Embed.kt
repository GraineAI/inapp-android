package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/*
 * THE SDK'S OWN REQUESTS TO GRAINE — device registration, sign-out, scheduled
 * calls, call outcomes, forgetting (contract §5.1). Each is a POST with the
 * publishable key in its body, like the session and the ticket, and each
 * answers `{ error, code }` when it refuses. Pure: the HTTP is handed in, so
 * every body and every reading of an answer is a JVM test.
 */

/**
 * Why Graine refused a request, as the wire says it (WIRE.md "Errors") — or
 * [NETWORK] when no answer came back. A word this build does not know is
 * [Unknown], carrying it.
 */
sealed class GraineErrorCode(val wire: String) {
    object INVALID_KEY : GraineErrorCode("invalid_key")
    /** This agent does not accept the app: add `app://` to its Allowed domains. */
    object ORIGIN_REFUSED : GraineErrorCode("origin_refused")
    /** Too many requests: wait [GraineError.retryAfterSeconds] when the server said how long. */
    object RATE_LIMITED : GraineErrorCode("rate_limited")
    object INVALID_REQUEST : GraineErrorCode("invalid_request")
    /** The request needs a signed-in user: identify() with the trait the agent's Identity setting names. */
    object IDENTITY_REQUIRED : GraineErrorCode("identity_required")
    /** This user is already using the agent on as many devices as it allows. */
    object DEVICE_LIMIT : GraineErrorCode("device_limit")
    /** The agent does not let users book calls (Calls & scheduling in the dashboard). */
    object SCHEDULING_OFF : GraineErrorCode("scheduling_off")
    object TOO_SOON : GraineErrorCode("too_soon")
    object TOO_FAR : GraineErrorCode("too_far")
    /** Outside the agent's calling hours. */
    object OUTSIDE_WINDOW : GraineErrorCode("outside_window")
    object TOO_MANY_PENDING : GraineErrorCode("too_many_pending")
    object NOT_FOUND : GraineErrorCode("not_found")
    /** The service could not answer just now. Worth trying again later. */
    object UNAVAILABLE : GraineErrorCode("unavailable")
    /** [GraineInApp.startWorkflow]: no such workflow, "Your app can start this" is off for it, or it isn't published. */
    object NOT_ALLOWED : GraineErrorCode("not_allowed")
    /** [GraineInApp.startWorkflow]: this device isn't one of the user's signed-in devices, or the agent requires a verified user. */
    object NOT_VERIFIED : GraineErrorCode("not_verified")
    /** No answer came back: no network, or Graine could not be reached. Worth trying again. */
    object NETWORK : GraineErrorCode("network")
    /**
     * This app is not one of the agent's apps ("Only my apps" in the agent's
     * Your apps settings), or it did not say which app it is. Final: never
     * retried. New in 0.14.4.
     */
    object APP_NOT_ALLOWED : GraineErrorCode("app_not_allowed")
    /**
     * The agent requires a verified app and this install holds no valid app
     * pass. The SDK proves the app and tries the request once more before it
     * says this. New in 0.14.4.
     */
    object APP_NOT_VERIFIED : GraineErrorCode("app_not_verified")
    /**
     * The agent requires verified users and the identity sent is not
     * verified: give identify() an identity token, or set
     * identityTokenRefresher. The SDK asks the refresher and tries once more
     * before it says this. New in 0.14.4.
     */
    object IDENTITY_UNVERIFIED : GraineErrorCode("identity_unverified")
    /**
     * The agent requires a server token and none came with the request: set
     * [GraineConfig.tokenProvider]. The SDK has already asked the provider
     * again and sent the request once more. New in 0.14.4.
     */
    object TOKEN_REQUIRED : GraineErrorCode("token_required")
    /**
     * The server token could not be checked — [GraineError.reason] says why:
     * `malformed`, `signature`, `unknown_key`, `issuer` or `not_yet_valid`.
     * Already asked for again and retried once. New in 0.14.4.
     */
    object TOKEN_INVALID : GraineErrorCode("token_invalid")
    /** The server token expired, and a fresh one did not come in time (already asked for again and retried once). New in 0.14.4. */
    object TOKEN_EXPIRED : GraineErrorCode("token_expired")
    /**
     * The server token does not allow this — [GraineError.reason]: `agent` (it
     * is another agent's), `user` (another user's) or `scope` (it lacks the
     * scope this needs). Never retried. New in 0.14.4.
     */
    object TOKEN_SCOPE : GraineErrorCode("token_scope")
    /** This publishable key was revoked: ship the agent's new key (Agent → Embed & Widgets → Keys). Never retried. New in 0.14.4. */
    object KEY_REVOKED : GraineErrorCode("key_revoked")
    /** A website's bot check did not pass. Apps never see it: it applies only to websites. New in 0.14.4. */
    object BOT_CHECK_FAILED : GraineErrorCode("bot_check_failed")
    /** A code this build does not know, as the server sent it — or `http_500`: a bare 500, an answer nobody expected. */
    class Unknown(raw: String) : GraineErrorCode(raw) {
        override fun equals(other: Any?): Boolean = other is Unknown && other.wire == wire
        override fun hashCode(): Int = wire.hashCode()
    }

    override fun toString(): String = wire

    companion object {
        /**
         * Every code this build knows. Lazy: built on first use, never while a
         * code's own class is still being set up (an eager list could hold null).
         */
        val KNOWN: List<GraineErrorCode> by lazy {
            listOf(
                INVALID_KEY, ORIGIN_REFUSED, RATE_LIMITED, INVALID_REQUEST, IDENTITY_REQUIRED, DEVICE_LIMIT, SCHEDULING_OFF,
                TOO_SOON, TOO_FAR, OUTSIDE_WINDOW, TOO_MANY_PENDING, NOT_FOUND, UNAVAILABLE, NOT_ALLOWED, NOT_VERIFIED, NETWORK,
                APP_NOT_ALLOWED, APP_NOT_VERIFIED, IDENTITY_UNVERIFIED,
                TOKEN_REQUIRED, TOKEN_INVALID, TOKEN_EXPIRED, TOKEN_SCOPE, KEY_REVOKED, BOT_CHECK_FAILED,
            )
        }

        /** The code for a wire word; [Unknown] for one this build does not know. */
        @JvmStatic
        fun from(wire: String): GraineErrorCode {
            val w = wire.trim().lowercase()
            return KNOWN.firstOrNull { it.wire == w } ?: Unknown(wire)
        }
    }
}

/**
 * A refusal from Graine, or a request that never got an answer (WIRE.md
 * "SDK types"): [code] is the stable part to branch on; [message] is the
 * server's sentence, fit to show; [retryAfterSeconds], for a rate limit, how
 * long the server asked to wait when it said; [reason], the server's one word
 * beside the code when it gave one.
 */
data class GraineError @JvmOverloads constructor(
    val code: GraineErrorCode,
    val message: String,
    val retryAfterSeconds: Int? = null,
    /**
     * The server's one-word reason beside the code, when it gave one — which
     * part of a server token failed (`scope`, `user`, `agent`, `signature`,
     * `unknown_key`, …); null otherwise. New in 0.14.4.
     */
    val reason: String? = null,
)

/** The answer to a request the SDK made to Graine: the value, or why there is none. On the main thread. */
fun interface GraineCallback<T> {
    fun onResult(value: T?, error: GraineError?)
}

/** What a request came to. */
internal sealed class EmbedReply {
    data class Ok(val body: JSONObject) : EmbedReply()
    /** [retryAfterMs]: the server's `Retry-After`, when it sent one (a rate limit). [status]: the HTTP status; 0 when no answer came back. */
    data class Failed(val error: GraineError, val retryAfterMs: Long? = null, val status: Int = 0) : EmbedReply()
}

/**
 * POST one of the routes below, with the publishable key — and, with server
 * tokens in play, the newest token ([ClientTokenWire]: a refusal a fresh token
 * can lift is sent once more with one). Blocking; never on the main thread.
 */
internal class EmbedApi(
    private val baseUrl: String,
    private val publishableKey: String,
    private val http: HttpPost = PlatformHttp,
    /** This agent's server tokens; null, or none in play, sends none. */
    private val tokens: ClientTokens? = null,
) {
    fun post(path: String, body: JSONObject): EmbedReply = send(path, body, tokens)

    /**
     * [post], carrying exactly [token] — what a request made for a user who has
     * left carries (their sign-out, CLIENT-TOKENS.md §2.3): never the newest
     * token, and never sent again with another on a refusal. Null: none.
     */
    fun postPinned(path: String, body: JSONObject, token: String?): EmbedReply {
        if (token != null) body.put(ClientTokenRules.FIELD, token) else body.remove(ClientTokenRules.FIELD)
        return send(path, body, tokens = null)
    }

    private fun send(path: String, body: JSONObject, tokens: ClientTokens?): EmbedReply {
        body.put("publishableKey", publishableKey)
        val url = "${baseUrl.trimEnd('/')}$path"
        val reply = try {
            // Through the one door: the app's declaration, the user's token and pass, the proofs' answers — and inside it
            // the agent's server token (EmbedRequest.kt, ClientTokenWire).
            EmbedRequest.send(url, body, tokens = tokens) { json -> http.post(url, json) }
        } catch (e: Exception) {
            return EmbedReply.Failed(GraineError(GraineErrorCode.NETWORK, "Graine could not be reached. Check the connection and try again."))
        }
        val json = runCatching { JSONObject(reply.body) }.getOrNull() ?: JSONObject()
        return if (reply.code in 200..299) EmbedReply.Ok(json)
        else EmbedReply.Failed(errorFor(reply.code, json, reply.retryAfterSeconds), reply.retryAfterSeconds?.takeIf { it > 0 }?.let { it * 1000 }, reply.code)
    }

    companion object {
        const val DEVICES = "/api/embed/devices"
        const val SIGNOUT = "/api/embed/identity/signout"
        const val SCHEDULE = "/api/embed/calls/schedule"
        const val LIST = "/api/embed/calls/list"
        const val CANCEL = "/api/embed/calls/cancel"
        const val OUTCOME = "/api/embed/calls/outcome"
        const val FORGET = "/api/embed/memory/forget"
        const val MEMORIES = "/api/embed/memory/list"
        const val NEW_CONVERSATION = "/api/embed/conversation/new"
        /** The signed-in user's past sessions, a page at a time (contract/past-sessions.json). */
        const val PAST_SESSIONS = "/api/embed/sessions/list"
        /** One past session's transcript. */
        const val SESSION_TRANSCRIPT = "/api/embed/sessions/transcript"
        /** A workflow the app starts for the signed-in user ([GraineInApp.startWorkflow], 0.14.4). */
        const val START_WORKFLOW = "/api/embed/workflows/start"

        /**
         * The server's `{error, code}`; a code it did not send is read from
         * the status by the one rule every Graine SDK follows
         * (sdk/contract/http-status.json): 400/422 invalid_request, 401,
         * 403, 404, 429 by name; 502, 503, 504 and the CDN's 520–524 — what
         * stands in front of the service, the service itself, a timeout — are
         * [GraineErrorCode.UNAVAILABLE], worth trying again later; a bare 500
         * and every other status are [GraineErrorCode.Unknown] `http_<status>`.
         */
        @JvmOverloads
        fun errorFor(status: Int, body: JSONObject?, retryAfterSeconds: Long? = null): GraineError {
            val sent = (body?.opt("code") as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val code = sent?.let { GraineErrorCode.from(it) } ?: codeFor(status)

            val message = (body?.opt("error") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "Graine refused the request ($status)."
            return GraineError(code, message, retryAfterSeconds?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt(), reasonOf(body))
        }

        /** A refusal's code when its body names none: the contract's one rule, by status. */
        fun codeFor(status: Int): GraineErrorCode = when (status) {
            400, 422 -> GraineErrorCode.INVALID_REQUEST
            401 -> GraineErrorCode.INVALID_KEY
            403 -> GraineErrorCode.ORIGIN_REFUSED
            404 -> GraineErrorCode.NOT_FOUND
            429 -> GraineErrorCode.RATE_LIMITED
            502, 503, 504, in 520..524 -> GraineErrorCode.UNAVAILABLE
            else -> GraineErrorCode.Unknown("http_$status")
        }
        /** The answer's `reason` — one word beside the code (`scope`, `signature`, …) — or null. */
        fun reasonOf(body: JSONObject?): String? = (body?.opt("reason") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }
}

// ── workflows the app starts (0.14.4) ─────────────────────────────────────

/**
 * A run [GraineInApp.startWorkflow] started — or the one the user already had
 * under way ([alreadyRunning]: that run, never a second).
 */
data class GraineWorkflowRun(
    /** The run's id (`oex_…`). */
    val runId: String,
    /** The workflow's id (`owf_…`); null when the answer did not say. */
    val workflowId: String?,
    /** True when the user already had a run of this workflow under way. */
    val alreadyRunning: Boolean,
)

/**
 * [GraineInApp.startWorkflow]'s rules, every SDK's (PUBLIC-STATE.md §7): what
 * is checked on the device, what of the app's data is sent, how the answer is
 * read. Pure, so the JVM tests hold it.
 *
 * The run is for the identified user, never for anyone the app names: who the
 * run is for never leaves the device in `data`. What could never be kept is
 * left out before anything is sent — a list, an object, null, a text over 200
 * characters, a name no workflow can allow, `user_id` / `user_field` /
 * `reference_id`, a `graine_` name — and the first 12 of the rest go, in the
 * map's order. Left out, never refused.
 */
internal object Workflows {
    const val NAME_MAX = 120
    const val FIELDS_MAX = 12
    const val VALUE_MAX = 200
    /** After a request that got no answer, the one more ask (the same key) waits this long. */
    const val RETRY_MS = 1_000L
    const val KEY_MESSAGE = "idempotencyKey is 8 to 128 letters, digits, _ - . or :."
    const val NAME_MESSAGE = "Pass the workflow's exact name or its id (at most $NAME_MAX characters)."

    /** Never sent: who the run is for, the engine's `fields`, and where a step sends by default. */
    private val RESERVED = setOf("user_id", "user_field", "reference_id", "fields", "mobile_number", "email")
    private val FIELD_NAME = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val KEY = Regex("^[A-Za-z0-9_.:-]{8,128}$")

    /** The workflow's name or id as sent — trimmed — or null when empty or over 120 characters. */
    fun name(raw: String?): String? = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= NAME_MAX }

    fun isIdempotencyKey(value: String?): Boolean = value != null && KEY.matches(value)

    /** A fresh key for one call: a UUID. */
    fun newKey(): String = java.util.UUID.randomUUID().toString()

    /** The fields sent: what could be kept, the first 12 in the map's order. */
    fun data(data: Map<String, Any?>?): JSONObject {
        val out = JSONObject()
        var kept = 0
        for ((name, value) in data.orEmpty()) {
            if (kept >= FIELDS_MAX) break
            if (!FIELD_NAME.matches(name) || name in RESERVED || name.lowercase().startsWith("graine_")) continue
            val keep: Any = when (value) {
                // Code points, as Graine counts them: an emoji is one.
                is String -> value.takeIf { it.codePointCount(0, it.length) <= VALUE_MAX }
                is Boolean -> value
                is Int, is Long, is Short, is Byte -> (value as Number).toLong()
                is Float -> value.toDouble().takeIf { it.isFinite() }
                is Double -> value.takeIf { it.isFinite() }
                else -> null
            } ?: continue
            out.put(name, keep)
            kept++
        }
        return out
    }

    /** The run in the answer — `{runId, workflowId, alreadyRunning}` — or null without a run id. */
    fun run(body: JSONObject): GraineWorkflowRun? {
        val runId = text(body, "runId") ?: text(body, "run_id") ?: return null
        val already = body.opt("alreadyRunning") ?: body.opt("already_running")
        return GraineWorkflowRun(runId, text(body, "workflowId") ?: text(body, "workflow_id"), already == true)
    }

    private fun text(o: JSONObject, key: String): String? = (o.opt(key) as? String)?.takeIf { it.isNotEmpty() }
}

// ── what the session says about calls ─────────────────────────────────────

/**
 * THE RULES FOR BOOKING, AND FOR THE CALLS GRAINE RINGS — from the session's
 * `calls` (WIRE.md "SDK types"; `GraineInApp.schedulingRules`). With nothing
 * in the session (an older server, or none fetched yet), users cannot book
 * ([usersCanBook] false), Graine rings nobody, and the defaults hold.
 * [scheduleCall][GraineInApp.scheduleCall] checks a time against [earliest]
 * and [latest] before it asks; a booking screen can offer only times between them.
 */
data class GraineSchedulingRules @JvmOverloads constructor(
    val usersCanBook: Boolean = false,
    val minLeadMinutes: Int = 5,
    val maxDaysAhead: Int = 14,
    val maxPendingPerUser: Int = 3,
    /** How long a ring Graine sends lasts before it is missed. */
    val ringTimeoutSeconds: Int = 45,
    /** The name the agent's calls ring with, when the dashboard set one. */
    val callName: String? = null,
    /** Graine rings this app's users (the workspace uploaded its push credentials). */
    val hostedRinging: Boolean = false,
    /** The platforms Graine rings: "ios", "android". Empty: every one. */
    val platforms: List<String> = emptyList(),
    /**
     * The agent's calling window, as booking applies it: the booking sheet and
     * [GraineInApp.bookingSlots] offer only the times inside it. Null when the
     * agent has none — every time between [earliest] and [latest] is offered.
     */
    val callingWindow: GraineCallingWindow? = null,
) {
    /**
     * The dashboard's word on how this agent's native calls carry their audio
     * (`calls.audioTransport`): "webrtc", "websocket" — the switch that turns
     * WebRTC off — or null, no opinion. Read by the call engine only.
     */
    internal var audioTransport: String? = null
        private set

    /** The earliest a call can be booked for: [now] plus [minLeadMinutes]. */
    @JvmOverloads
    fun earliest(now: java.util.Date = java.util.Date()): java.util.Date = java.util.Date(now.time + minLeadMinutes * 60_000L)

    /** The latest: [now] plus [maxDaysAhead] days. */
    @JvmOverloads
    fun latest(now: java.util.Date = java.util.Date()): java.util.Date = java.util.Date(now.time + maxDaysAhead * 86_400_000L)

    /** Graine rings this app on Android. */
    val ringsAndroid: Boolean get() = hostedRinging && (platforms.isEmpty() || "android" in platforms)

    companion object {
        /** How long a call Graine rings may ring, in seconds: the dashboard's range. */
        const val RING_SECONDS_MIN = 20
        const val RING_SECONDS_MAX = 60

        /** From the session's `calls`; the defaults (and no booking, no hosted ringing) when there are none. */
        @JvmStatic
        fun parse(raw: Any?): GraineSchedulingRules {
            val o = raw as? JSONObject ?: return GraineSchedulingRules()
            fun int(v: Any?): Int? = Js.finite(v)?.let { Math.round(it).toInt() }
            val platforms = (o.opt("platforms") as? JSONArray)?.let { a ->
                (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.lowercase()?.takeIf { p -> p == "ios" || p == "android" } }
            } ?: emptyList()
            val s = o.opt("scheduling") as? JSONObject
            return GraineSchedulingRules(
                usersCanBook = s?.opt("usersCanBook") == true,
                minLeadMinutes = int(s?.opt("minLeadMinutes"))?.takeIf { it >= 0 } ?: 5,
                maxDaysAhead = int(s?.opt("maxDaysAhead"))?.takeIf { it > 0 } ?: 14,
                maxPendingPerUser = int(s?.opt("maxPendingPerUser"))?.takeIf { it > 0 } ?: 3,
                // The dashboard's range (Calls & scheduling → Ring for): 20–60 seconds, as every SDK reads it.
                ringTimeoutSeconds = int(o.opt("ringTimeoutSeconds"))?.takeIf { it > 0 }?.coerceIn(RING_SECONDS_MIN, RING_SECONDS_MAX) ?: 45,
                callName = (o.opt("callName") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                hostedRinging = o.opt("hosted") == true,
                platforms = platforms,
                callingWindow = GraineCallingWindow.read(s?.opt("callingWindow")),
            ).also { r ->
                r.audioTransport = (o.opt("audioTransport") as? String)?.trim()?.lowercase()?.takeIf { it == "webrtc" || it == "websocket" }
            }
        }
    }
}

/**
 * THE AGENT'S CALLING WINDOW, as booking applies it (the session's
 * `calls.scheduling.callingWindow`): the hours Graine may ring a booked call.
 * [start] is offered, [end] is not ("HH:MM", 24-hour; a window never crosses
 * midnight), on the [days] listed — 0 Monday … 6 Sunday — all read on the
 * device's clock, in its own time zone, as Graine checks a booking.
 */
data class GraineCallingWindow(
    /** The first time of day offered, "HH:MM". */
    val start: String,
    /** The time of day the window closes, "HH:MM" — never offered itself. */
    val end: String,
    /** The days it is open: 0 Monday … 6 Sunday, each once, in order. */
    val days: List<Int>,
) {
    private val startMinute: Int get() = minuteOf(start)
    private val endMinute: Int get() = minuteOf(end)

    /** [atMs] on [calendar]'s clock (its zone) is inside the window: its weekday listed, start ≤ its time < end. */
    internal fun allows(atMs: Long, calendar: Calendar): Boolean {
        calendar.timeInMillis = atMs
        // Calendar counts Sunday 1 … Saturday 7; the window counts Monday 0 … Sunday 6.
        val weekday = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
        if (weekday !in days) return false
        val minute = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
        return minute in startMinute until endMinute
    }

    internal companion object {
        private val HHMM = Regex("([01]\\d|2[0-3]):([0-5]\\d)")

        private fun minuteOf(hhmm: String): Int = hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(3, 5).toInt()

        /**
         * The window the session sent, or null — no window — when there is none
         * or it cannot be read: times "HH:MM" with start before end; days a list
         * whose whole numbers 0–6 are kept (each once, in order), every day when
         * it is missing, and no window when none is left.
         */
        fun read(raw: Any?): GraineCallingWindow? {
            val o = raw as? JSONObject ?: return null
            fun clock(v: Any?): String? = (v as? String)?.trim()?.takeIf { HHMM.matches(it) }
            val start = clock(o.opt("start")) ?: return null
            val end = clock(o.opt("end")) ?: return null
            if (start >= end) return null
            val days = when (val d = o.opt("days")) {
                null, JSONObject.NULL -> (0..6).toList()
                is JSONArray -> (0 until d.length())
                    .mapNotNull { i -> Js.finite(d.opt(i))?.takeIf { it % 1.0 == 0.0 && it in 0.0..6.0 }?.toInt() }
                    .distinct().sorted()
                else -> return null
            }
            return if (days.isEmpty()) null else GraineCallingWindow(start, end, days)
        }
    }
}

// ── scheduled calls ───────────────────────────────────────────────────────

/** Where a booked call is (WIRE.md "SDK types"). A status this build does not know is [Unknown], with its word. */
sealed class GraineCallStatus(val wire: String) {
    object SCHEDULED : GraineCallStatus("scheduled")
    object RINGING : GraineCallStatus("ringing")
    object ANSWERED : GraineCallStatus("answered")
    object COMPLETED : GraineCallStatus("completed")
    object DECLINED : GraineCallStatus("declined")
    object MISSED : GraineCallStatus("missed")
    object FAILED : GraineCallStatus("failed")
    object CANCELLED : GraineCallStatus("cancelled")
    object EXPIRED : GraineCallStatus("expired")
    /** Graine had no device of this user's to ring (wire: `no_device`). */
    object NO_DEVICE : GraineCallStatus("no_device")
    class Unknown(raw: String) : GraineCallStatus(raw) {
        override fun equals(other: Any?): Boolean = other is Unknown && other.wire == wire
        override fun hashCode(): Int = wire.hashCode()
    }

    override fun toString(): String = wire

    companion object {
        /** Every status this build knows. Lazy, for the same reason as [GraineErrorCode.KNOWN]. */
        val KNOWN: List<GraineCallStatus> by lazy {
            listOf(SCHEDULED, RINGING, ANSWERED, COMPLETED, DECLINED, MISSED, FAILED, CANCELLED, EXPIRED, NO_DEVICE)
        }

        @JvmStatic
        fun from(wire: String): GraineCallStatus {
            val w = wire.trim().lowercase()
            return KNOWN.firstOrNull { it.wire == w } ?: Unknown(wire)
        }
    }
}

/** Who booked a call. The app's own bookings ([GraineInApp.scheduleCall]) are [APP] (wire: `sdk`). */
sealed class GraineCallSource(val wire: String) {
    object AGENT : GraineCallSource("agent")
    object APP : GraineCallSource("sdk")
    object DASHBOARD : GraineCallSource("dashboard")
    object API : GraineCallSource("api")
    class Unknown(raw: String) : GraineCallSource(raw) {
        override fun equals(other: Any?): Boolean = other is Unknown && other.wire == wire
        override fun hashCode(): Int = wire.hashCode()
    }

    override fun toString(): String = wire

    companion object {
        @JvmStatic
        fun from(wire: String): GraineCallSource = when (wire.trim().lowercase()) {
            "agent" -> AGENT
            "sdk", "app" -> APP
            "dashboard" -> DASHBOARD
            "api" -> API
            else -> Unknown(wire)
        }
    }
}

/**
 * A CALL THE AGENT WILL MAKE TO THIS USER, booked for later (WIRE.md "SDK
 * types"): Graine rings it at [at], through the app's call screen.
 */
data class GraineScheduledCall @JvmOverloads constructor(
    val id: String,
    /** When it rings; null only when the server's time could not be read. */
    val at: java.util.Date?,
    val status: GraineCallStatus = GraineCallStatus.SCHEDULED,
    /** Who booked it; null when the server does not say. */
    val source: GraineCallSource? = null,
    /** Why, as it was booked; null when none was given (or the server keeps it to itself). */
    val reason: String? = null,
) {
    /** Still to come: scheduled, or ringing now. */
    val isUpcoming: Boolean get() = status == GraineCallStatus.SCHEDULED || status == GraineCallStatus.RINGING

    /** It can still be called off: it has not rung yet. */
    val canCancel: Boolean get() = status == GraineCallStatus.SCHEDULED
}

internal object Scheduling {
    const val REASON_MAX = 120

    /**
     * `at` as ISO-8601 with the device's offset — "2026-10-14T18:00:00+05:30" —
     * so the server reads the moment and the user's own wall clock from one string.
     */
    fun iso(atMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = atMs }
        val offsetMin = zone.getOffset(atMs) / 60_000
        val sign = if (offsetMin < 0) "-" else "+"
        val abs = Math.abs(offsetMin)
        // Locale.US: a phone set to Arabic or Marathi would otherwise write the digits in its own script.
        return String.format(
            Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d%s%02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
            sign, abs / 60, abs % 60,
        )
    }

    private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:[.,](\\d{1,9}))?)?(Z|[+-]\\d{2}(?::?\\d{2})?)?$", RegexOption.IGNORE_CASE)

    /** ISO-8601 → ms since the epoch; null for anything else. No zone reads as UTC, as the server writes it. */
    fun parseIso(raw: String?): Long? {
        val m = ISO.matchEntire(raw?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        }
        var ms = c.timeInMillis + (g[7].takeIf { it.isNotEmpty() }?.padEnd(3, '0')?.take(3)?.toLong() ?: 0L)
        val zone = g[8]
        if (zone.isNotEmpty() && !zone.equals("Z", ignoreCase = true)) {
            val digits = zone.substring(1).replace(":", "")
            val minutes = digits.take(2).toInt() * 60 + (digits.drop(2).takeIf { it.isNotEmpty() }?.toInt() ?: 0)
            ms -= (if (zone[0] == '-') -1 else 1) * minutes * 60_000L
        }
        return ms
    }

    /**
     * What the agent's own rules already say about a booking, before any
     * request (WIRE.md): booking switched off, a time before
     * [GraineSchedulingRules.earliest], a time after [GraineSchedulingRules.latest].
     * The server checks again — and checks what only it knows (calling hours,
     * pending calls) — so null here is not a promise. Refused in the booking
     * sheet's words ([Booking.errorLine]), the same on every SDK.
     */
    fun check(atMs: Long, nowMs: Long, rules: GraineSchedulingRules): GraineError? {
        fun refused(code: GraineErrorCode) = GraineError(code, Booking.errorLine(code, rules))
        if (!rules.usersCanBook) return refused(GraineErrorCode.SCHEDULING_OFF)
        val now = java.util.Date(nowMs)
        if (atMs < rules.earliest(now).time || atMs <= nowMs) return refused(GraineErrorCode.TOO_SOON)
        if (atMs > rules.latest(now).time) return refused(GraineErrorCode.TOO_FAR)
        return null
    }

    /** The body of `/api/embed/calls/schedule`: `installId, identity?, at, reason?, timezone`. */
    fun scheduleBody(atMs: Long, reason: String?, identity: IdentityEnvelope, zone: TimeZone = TimeZone.getDefault()): JSONObject {
        val o = identity.who(JSONObject())
        o.put("at", iso(atMs, zone))
        // The user's own zone: the calling window is checked in it.
        o.put("timezone", identity.timezone ?: zone.id)
        reason?.trim()?.takeIf { it.isNotEmpty() }?.let { o.put("reason", it.take(REASON_MAX)) }
        return o
    }

    /** One call as the server describes it; null without an id. */
    fun call(raw: Any?): GraineScheduledCall? {
        val o = raw as? JSONObject ?: return null
        val id = listOf("id", "callId", "call_id").firstNotNullOfOrNull { k -> (o.opt(k) as? String)?.takeIf { it.isNotBlank() } } ?: return null
        val at = listOf("at", "scheduledAt", "scheduled_at").firstNotNullOfOrNull { k -> (o.opt(k) as? String)?.takeIf { it.isNotBlank() } } ?: ""
        return GraineScheduledCall(
            id = id,
            // ISO-8601, or epoch milliseconds from a server that writes them so.
            at = parseIso(at)?.let { java.util.Date(it) } ?: Js.finite(o.opt("at"))?.let { java.util.Date(it.toLong()) },
            status = (o.opt("status") as? String)?.takeIf { it.isNotBlank() }?.let { GraineCallStatus.from(it) } ?: GraineCallStatus.SCHEDULED,
            source = (o.opt("source") as? String)?.takeIf { it.isNotBlank() }?.let { GraineCallSource.from(it) },
            reason = (o.opt("reason") as? String)?.takeIf { it.isNotBlank() },
        )
    }

    /** `/calls/schedule`'s answer: `{call: {...}}`, or the call itself. */
    fun scheduled(body: JSONObject): GraineScheduledCall? = call(body.opt("call")) ?: call(body)

    /** `/calls/cancel`'s answer: `{ok, status?}` — the call's status afterwards, cancelled when the server does not say. */
    fun cancelled(body: JSONObject): GraineCallStatus =
        (body.opt("status") as? String)?.takeIf { it.isNotBlank() }?.let { GraineCallStatus.from(it) } ?: GraineCallStatus.CANCELLED

    /** `/memory/forget`'s answer: `{deleted}` — how many memories were deleted. */
    fun deleted(body: JSONObject): Int = Js.finite(body.opt("deleted"))?.toInt()?.coerceAtLeast(0) ?: 0

    /** `/calls/list`'s answer: `{calls: [...]}`, in the server's order (upcoming first). */
    fun list(body: JSONObject): List<GraineScheduledCall> {
        val a = body.opt("calls") as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { call(a.opt(it)) }
    }
}

// ── what the agent remembers ──────────────────────────────────────────────

/** One thing the agent remembers about the signed-in user, as Graine keeps it (already redacted). */
data class GraineMemory @JvmOverloads constructor(
    val id: String,
    val text: String,
    val createdAt: java.util.Date? = null,
    val updatedAt: java.util.Date? = null,
)

internal object Memories {
    /** The most the server sends. */
    const val MAX = 100

    /** `/memory/list`'s answer: `{memories: [...]}`, newest first, as the server orders them. */
    fun list(body: JSONObject): List<GraineMemory> {
        val a = body.opt("memories") as? JSONArray ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.opt(i) as? JSONObject ?: return@mapNotNull null
            val id = (o.opt("id") as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val text = (o.opt("text") as? String) ?: return@mapNotNull null
            GraineMemory(
                id = id,
                text = text,
                createdAt = Scheduling.parseIso(o.opt("createdAt") as? String)?.let { java.util.Date(it) },
                updatedAt = Scheduling.parseIso(o.opt("updatedAt") as? String)?.let { java.util.Date(it) },
            )
        }.take(MAX)
    }
}

// ── calls Graine rang: what became of them ────────────────────────────────

/**
 * WHAT A RING CAME TO, FOR GRAINE (contract §3.6) — only for a call Graine
 * itself rang (a push carrying `hosted: "1"`). The server follows its own
 * state machine and ignores a late or repeated report, so these may be sent
 * again safely.
 */
internal object HostedOutcomes {
    const val RINGING = "ringing"
    const val ANSWERED = "answered"
    const val DECLINED = "declined"
    const val MISSED = "missed"
    const val FAILED = "failed"
    const val ENDED = "ended"
    /** A reason the server never shows anyone, but keeps short. */
    const val REASON_MAX = 200

    /** One report: the outcome, why (when there is a why), and how long a call that connected lasted. */
    data class Report(val callId: String, val outcome: String, val reason: String? = null, val durationSeconds: Long? = null) {
        /** `installId, callId, outcome, reason?, durationSeconds?` (WIRE.md). */
        fun body(installId: String?): JSONObject {
            val o = JSONObject()
            installId?.let { o.put("installId", it) }
            o.put("callId", callId)
            o.put("outcome", outcome)
            reason?.takeIf { it.isNotEmpty() }?.let { o.put("reason", it.take(REASON_MAX)) }
            durationSeconds?.let { o.put("durationSeconds", it) }
            return o
        }
    }

    /**
     * The SDK's outcome as Graine's. `busy` is a ring refused because another
     * call was live (a ring that simply ran out says nothing more); an ended
     * call says who ended it and how long it was connected.
     */
    fun of(callId: String, outcome: GraineCallOutcome, busy: Boolean, connectedMs: Long?): Report = when (outcome.kind) {
        GraineCallOutcome.Kind.ANSWERED -> Report(callId, ANSWERED)
        GraineCallOutcome.Kind.DECLINED -> Report(callId, DECLINED)
        GraineCallOutcome.Kind.MISSED -> Report(callId, MISSED, if (busy) "busy" else null)
        GraineCallOutcome.Kind.FAILED -> Report(callId, FAILED, outcome.error ?: "failed")
        GraineCallOutcome.Kind.ENDED -> Report(callId, ENDED, outcome.endReason?.wire, connectedMs?.let { Math.max(0L, (it + 500) / 1000) })
    }

    /** Two retries of a report that got no answer — the outcome matters, the phone may be offline for a moment. */
    val RETRY_DELAYS_MS = listOf(2_000L, 6_000L)

    /** Worth retrying: no answer, a rate limit, or the service's own trouble — never a refusal. */
    fun retryable(e: GraineError): Boolean =
        e.code == GraineErrorCode.NETWORK || e.code == GraineErrorCode.RATE_LIMITED || e.code == GraineErrorCode.UNAVAILABLE

    /** [retryable], or any 5xx — a bare 500 too, though its code is `http_500`: the service's own trouble, not a refusal. */
    fun retryable(reply: EmbedReply.Failed): Boolean = retryable(reply.error) || reply.status >= 500
}

/**
 * THIS DEVICE, FOR THE CALLS GRAINE RINGS: the body of `/api/embed/devices`
 * (WIRE.md) — and when to send it: on every new token, whenever what it says
 * changes (another user signed in, a new time zone, a new app version), and
 * otherwise at most once a day, so a device that rings is never forgotten
 * (Graine lets go of one unseen for 90 days). What was sent is kept as a
 * fingerprint: never the token, never the user.
 */
internal object DeviceRegistration {
    const val REFRESH_MS = 24L * 60 * 60 * 1000

    /** `installId, platform, appId, token, tokenKind, timezone, appVersion, sdkVersion, identity?`. */
    fun body(token: String, appId: String, appVersion: String?, sdkVersion: String, identity: IdentityEnvelope): JSONObject {
        val o = identity.who(JSONObject())
        o.put("platform", "android")
        o.put("appId", appId)
        o.put("token", token)
        o.put("tokenKind", "fcm")
        identity.timezone?.let { o.put("timezone", it) }
        appVersion?.let { o.put("appVersion", it) }
        o.put("sdkVersion", sdkVersion)
        return o
    }

    /** A one-way fingerprint of everything a registration says. */
    fun fingerprint(token: String, appId: String, appVersion: String?, sdkVersion: String, identity: IdentityEnvelope): String {
        val fields = listOf(
            "appId" to appId, "appVersion" to (appVersion ?: ""), "sdkVersion" to sdkVersion,
            "timezone" to (identity.timezone ?: ""), "identity" to (identity.value ?: ""), "installId" to (identity.installId ?: ""),
        )
        return IdentityRules.sha16("graine:device:v1\n" + token + fields.joinToString("") { (k, v) -> "\n$k=$v" })
    }

    /** Whether to register now: never sent, something changed, a day has passed — or the clock went backwards. */
    fun due(last: Pair<String, Long>?, fingerprint: String, nowMs: Long): Boolean {
        if (last == null || last.first != fingerprint) return true
        return nowMs - last.second >= REFRESH_MS || nowMs < last.second
    }

    /** `{ok, trusted?, reason?}`: what Graine made of this device for the user — `device_limit` when they are on too many. */
    fun reason(answer: JSONObject): String? = (answer.opt("reason") as? String)?.takeIf { it.isNotBlank() }
}
