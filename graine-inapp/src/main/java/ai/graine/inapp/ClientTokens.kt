package ai.graine.inapp

import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/*
 * SERVER TOKENS — a short-lived token your server mints for the signed-in user
 * of one agent, which the SDK sends with every request to Graine (contract:
 * sdk/contract/CLIENT-TOKENS.md, vectors in client-tokens.json). Once the
 * agent's "Require server tokens" is enforced, the publishable key alone opens
 * nothing: every request without a good token is refused.
 *
 * The SDK never reads a token beyond its `iat` and `exp` — when it was minted
 * and when it lapses, unverified — and only to know when to ask for the next
 * one. It never logs one, never keeps one on the device, and never ends a
 * live chat or call because one expired: tokens are checked when a
 * conversation starts, never while it runs.
 */

/**
 * WHERE THE APP'S SERVER TOKENS COME FROM — [GraineConfig.tokenProvider].
 *
 *     GraineConfig(
 *         baseUrl = "https://www.graine.ai",
 *         publishableKey = "pk_live_…",
 *         tokenProvider = GraineTokenProvider { done ->
 *             // Your server mints it with POST /v2/client-tokens and your secret API key.
 *             backend.graineToken(onResult = { token -> done(token) }, onError = { done(null) })
 *         },
 *     )
 *
 * The SDK asks at start, again shortly before the token it holds expires (in
 * the background, with a little jitter, and with backoff after a failure),
 * when a call rings, and when Graine refuses a token — one question at a
 * time, however many requests are waiting for the answer. [fetch] is called
 * on a background thread of the SDK's and is never given anything: your
 * server knows the user from its own session.
 *
 * Call `done` once, from any thread, with the token — or with null when none
 * can be had right now. Throwing, or not calling `done` within 15 seconds,
 * counts as null; a later answer is ignored. With no token, requests go
 * without one and Graine decides. New in 0.14.4.
 */
fun interface GraineTokenProvider {
    /** Fetch a fresh token from your server, and hand it to [done] (null: none right now). */
    fun fetch(done: (String?) -> Unit)

    companion object {
        /**
         * A provider from a suspending function — its answer, or null when it
         * throws: `GraineTokenProvider.suspending { backend.graineToken() }`.
         * Started on the SDK's background thread; it carries on wherever the
         * function itself resumes. No coroutine library is needed.
         */
        @JvmStatic
        fun suspending(fetch: suspend () -> String?): GraineTokenProvider = GraineTokenProvider { done ->
            fetch.startCoroutine(Continuation(EmptyCoroutineContext) { result -> done(result.getOrNull()) })
        }
    }
}

/**
 * THE RULES, pure — every number and word the contract fixes, so the vectors
 * in client-tokens.json hold them (ClientTokensContractTest) and every SDK
 * refreshes on the same schedule.
 */
internal object ClientTokenRules {
    /** The top-level field every request to Graine carries the token in. */
    const val FIELD = "clientToken"

    /** A new connection uses the token it holds while it has more than this left. */
    const val USABLE_FOR_CONNECTION_MS = 15_000L
    /** Otherwise it waits at most this long for a refresh, then goes with the newest token it has. */
    const val CONNECTION_WAIT_MS = 10_000L
    /** A refusal asks for a new token at most once in this long. */
    const val FORCED_MIN_INTERVAL_MS = 5_000L
    /** A refresh in the background is never sooner than this after the last token arrived. */
    const val BACKGROUND_MIN_INTERVAL_MS = 30_000L
    /** How long the calls host waits for JavaScript or Dart to push a token it asked for, and the engine page for the SDK. */
    const val PUSH_WAIT_MS = 5_000L
    /** A provider that has not answered in this long has answered null. */
    const val PROVIDER_TIMEOUT_MS = 15_000L
    /** Each backoff after a failure is varied by up to this share, either way. */
    const val BACKOFF_JITTER = 0.2

    /** The engine page's messages: the token, host → page; a request for one, page → host. */
    const val PAGE_TOKEN = "graine:client-token"
    const val PAGE_NEEDS_TOKEN = "graine:need-client-token"

    /** Refusals a fresh token can fix: refresh, then the same request once more. */
    val REFRESHABLE: Set<String> = setOf("token_required", "token_invalid", "token_expired")
    /**
     * Every refusal for the token, the publishable key or the website bot
     * check: final for the request that met it — a conversation refused with
     * one is never tried another way.
     */
    val REFUSALS: Set<String> = REFRESHABLE + setOf("token_scope", "key_revoked", "bot_check_failed")

    /** What a token says about its own life, unverified: `iat` and `exp` in seconds since the epoch; null when not readable. */
    data class Claims(val iat: Double?, val exp: Double?)

    /** `iat` and `exp` from the token's payload — the middle of its three parts, base64url JSON — or nulls. Nothing else is read. */
    fun decode(token: String?): Claims {
        val parts = token?.trim()?.split('.') ?: return Claims(null, null)
        if (parts.size < 2 || parts[1].isEmpty()) return Claims(null, null)
        val json = runCatching {
            val bytes = B64.decode(parts[1].replace('-', '+').replace('_', '/'))
            JSONObject(String(bytes, Charsets.UTF_8))
        }.getOrNull() ?: return Claims(null, null)
        fun seconds(key: String): Double? = (json.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }
        return Claims(seconds("iat"), seconds("exp"))
    }

    /**
     * WHEN A TOKEN LAPSES, BY THIS DEVICE'S CLOCK (ms): `min(exp, receivedAt + lifetime)`
     * — a token the app's server cached keeps its real expiry, and a clock that
     * runs behind only refreshes early — or, when that is no more than 15 s
     * after it arrived (a clock far ahead, a stale token), `receivedAt +
     * lifetime`: the token is used and Graine judges it. Null when the token
     * has no readable `iat` and `exp`, or no life between them: then nothing
     * is refreshed in the background, and only a refusal asks for another.
     */
    fun localExpiry(claims: Claims, receivedAtMs: Long): Long? {
        val life = lifetimeMs(claims) ?: return null
        val exp = claims.exp ?: return null
        val local = minOf(Math.round(exp * 1000), receivedAtMs + life)
        return if (local <= receivedAtMs + USABLE_FOR_CONNECTION_MS) receivedAtMs + life else local
    }

    /** `exp − iat`, in ms; null without both, or with no life between them. */
    fun lifetimeMs(claims: Claims): Long? {
        val iat = claims.iat ?: return null
        val exp = claims.exp ?: return null
        return Math.round((exp - iat) * 1000).takeIf { it > 0 }
    }

    /** How long before it lapses a token is refreshed: `clamp(lifetime / 5, min(60 s, lifetime / 2), 600 s)`. */
    fun leadMs(lifetimeMs: Long): Long {
        val low = minOf(60_000L, lifetimeMs / 2)
        return maxOf(low, minOf(lifetimeMs / 5, 600_000L))
    }

    /** The most a refresh is moved earlier, so a fleet does not refresh in step: `min(30 s, lifetime / 10)`. */
    fun maxJitterMs(lifetimeMs: Long): Long = minOf(30_000L, lifetimeMs / 10)

    /** A new connection may go with a token that has more than 15 s left; one with no readable expiry always may. */
    fun usable(localExpMs: Long?, nowMs: Long): Boolean = localExpMs == null || localExpMs - nowMs > USABLE_FOR_CONNECTION_MS

    /** The wait after the [failures]-th failure in a row, before its jitter: `min(60, 2^n)` seconds. */
    fun backoffMs(failures: Int): Long = minOf(60_000L, (1L shl failures.coerceIn(1, 6)) * 1000L)

    /** The error code a refusal's body names, or null. */
    fun code(body: String?): String? =
        runCatching { (JSONObject(body ?: return null).opt("code") as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } }.getOrNull()

    /** A refusal Graine can lift with a fresh token: the request is sent once more with one. */
    fun refreshable(reply: HttpReply): Boolean = reply.code !in 200..299 && code(reply.body) in REFRESHABLE

    /**
     * A refusal for the token, the key or the bot check — [REFUSALS] — as the
     * SDK's error, with Graine's sentence and its reason; null for any other
     * answer, which goes where it always went.
     */
    fun refusal(status: Int, body: String?): GraineError? {
        if (status in 200..299) return null
        val json = runCatching { JSONObject(body ?: return null) }.getOrNull() ?: return null
        val c = (json.opt("code") as? String)?.trim()?.lowercase()?.takeIf { it in REFUSALS } ?: return null
        val said = (json.opt("error") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return GraineError(GraineErrorCode.from(c), said ?: sentence(c), reason = EmbedApi.reasonOf(json))
    }

    /** A call that could not start for one of [REFUSALS]: its outcome's error — the code first, then the sentence. */
    fun callError(e: GraineError): String = "${e.code.wire}: ${e.message}"

    /** The SDK's own sentence for a refusal Graine sent without one. */
    fun sentence(code: String): String = when (code) {
        "token_required" -> "This agent requires a server token: set tokenProvider in GraineConfig."
        "token_invalid" -> "The server token could not be checked."
        "token_expired" -> "The server token expired, and a fresh one did not come in time."
        "token_scope" -> "The server token does not allow this."
        "key_revoked" -> "This publishable key was revoked. Update the app with the agent's new key."
        "bot_check_failed" -> "The bot check did not pass."
        else -> "Graine refused the request."
    }
}

/**
 * ONE AGENT'S SERVER TOKENS (CLIENT-TOKENS.md §2.3): the newest token, when to
 * ask for the next, and everyone waiting for it.
 *
 *   at start        asked at once, in the background
 *   in the          at the token's local expiry, less the lead, less a jitter —
 *   background      never sooner than 30 s after the last token arrived; none
 *                   for a token with no readable expiry
 *   one at a time   one question to the provider at a time; every caller
 *                   waits on the one under way
 *   a failure       (null, a throw, no answer) asked again after 2, 4, 8, 16,
 *                   32, 60, 60… s, ±20 %; reset by a token
 *   the app away    nothing asked while the app is in the background; back in
 *                   front, asked when inside the lead
 *   a connection    (the session, a ticket, a call's credential) goes with the
 *                   token while it has more than 15 s left, else waits at most
 *                   10 s for a refresh, then goes with the newest it has
 *   a refusal       (token_required, token_expired, token_invalid) asks again —
 *                   at most once in 5 s — and the request goes once more
 *   a ring          asked in the background before anyone answers
 *
 * Nothing here ever touches a live chat or call.
 *
 * WITHOUT A PROVIDER, a calls host (React Native, Flutter) may push the tokens
 * its own manager fetched ([push]); a refusal then asks it ([onNeed], the
 * host's `needClientToken`) and waits at most 5 s for the next push. With
 * neither, tokens are not in play ([inPlay]) and no request carries one.
 *
 * Thread-safe. Only [forConnection] and [afterRefusal] block — on the request's
 * own background thread, never the main thread's.
 */
internal class ClientTokens(
    /** The app's provider; null: a calls host pushes the tokens, or none are in play. */
    private val provider: GraineTokenProvider?,
    /** The device's wall clock: a token's life is read against it. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Uniform in [0, 1): the jitter of a refresh and of a backoff. */
    private val random: () -> Double = { Math.random() },
    /** Runs a task after a delay; returns what cancels it. */
    private val schedule: (delayMs: Long, task: () -> Unit) -> (() -> Unit) = { delayMs, task -> TokenThreads.timers.schedule(delayMs, task) },
    /** Where the provider is asked: a thread of its own, so a provider that blocks holds up nothing else. */
    private val background: (() -> Unit) -> Unit = { work -> Thread({ work() }, "graine-token").apply { isDaemon = true }.start() },
    private val providerTimeoutMs: Long = ClientTokenRules.PROVIDER_TIMEOUT_MS,
    private val connectionWaitMs: Long = ClientTokenRules.CONNECTION_WAIT_MS,
    private val pushWaitMs: Long = ClientTokenRules.PUSH_WAIT_MS,
) {
    /** One question under way — to the provider, or to a calls host — and who waits for its answer. */
    private class Flight(val toHost: Boolean) {
        val waiters = ArrayList<(String?) -> Unit>()
        @Volatile var cancelTimeout: (() -> Unit)? = null
        var done = false
        /** Asked for the user before ([renew]): it finishes, but its answer is not kept. */
        var superseded = false
    }

    private val lock = Any()
    @Volatile private var held: String? = null
    private var localExp: Long? = null
    private var lifetime: Long? = null
    /** This token's jitter, drawn when it arrived: its refresh moves earlier by it. */
    private var jitterMs = 0L
    private var lastTokenAt: Long? = null
    private var lastForcedAt: Long? = null
    private var failures = 0
    private var flight: Flight? = null
    private var timer: (() -> Unit)? = null
    private var foreground = true
    @Volatile private var pushedOnce = false
    /** [start] was called: [renew] asks at once. */
    @Volatile private var started = false
    private val watchers = CopyOnWriteArraySet<(String?) -> Unit>()

    /**
     * A calls host's way to ask its JavaScript or Dart manager for a token (the
     * `needClientToken` event): called on any thread. Null outside a host.
     */
    @Volatile var onNeed: (() -> Unit)? = null

    /** Tokens are in play: the app gave a provider, or a calls host pushed one. Otherwise no request carries one — nothing changes. */
    val inPlay: Boolean get() = provider != null || pushedOnce

    /** The newest token held — even one past its expiry: Graine may still take it, answering a ring within its grace. */
    val newest: String? get() = held

    /** The token's expiry by this device's clock, for the tests; null without a token or a readable expiry. */
    internal val localExpiry: Long? get() = synchronized(lock) { localExp }

    /** The app's provider is asked at once, in the background. */
    fun start() {
        started = true
        if (provider != null) ask(forced = false, then = null)
    }

    /**
     * ANOTHER USER: identify() named someone else, or nobody. The token held
     * was minted for the user before — sent now it would speak for them — so
     * it is let go of, an answer still on its way for them is not kept (whoever
     * waited for it waits for the fresh one instead), and a fresh one is asked
     * for at once. Until it comes, a new connection waits for it (at most 10 s)
     * and other requests go without a token. What signs the user before out
     * carries their token, taken before this ([EmbedApi.postPinned]). Only
     * with the app's own provider: a calls host renews its own and pushes it.
     */
    fun renew() {
        if (provider == null) return
        val had: Boolean
        val moved: List<(String?) -> Unit>
        synchronized(lock) {
            val stale = flight
            moved = stale?.waiters?.toList().orEmpty()
            stale?.waiters?.clear()
            stale?.superseded = true
            flight = null
            had = held != null
            forget()
            failures = 0
            lastForcedAt = null
            cancelTimer()
        }
        if (had) announce(null)
        if (started || moved.isNotEmpty()) ask(forced = false, then = if (moved.isEmpty()) null else { t -> moved.forEach { runCatching { it(t) } } })
    }

    /** Hear every new token (null: none) — the engine page is posted each. Returns what stops it. */
    fun onChange(watch: (String?) -> Unit): () -> Unit {
        watchers.add(watch)
        return { watchers.remove(watch) }
    }

    /**
     * THE TOKEN A NEW CONNECTION GOES WITH — the session, a ticket, a call's
     * credential: the one held while it has more than 15 s left; otherwise a
     * refresh, waited for at most 10 s, then the newest token there is (perhaps
     * none). Blocks: never on the main thread.
     */
    fun forConnection(): String? {
        if (!inPlay) return null
        synchronized(lock) {
            val t = held
            if (t != null && ClientTokenRules.usable(localExp, clock())) return t
        }
        // A host refreshes its own tokens: a connection goes with what it pushed, and a refusal asks for more.
        if (provider == null) return held
        return await(connectionWaitMs) { done -> ask(forced = false, then = done) }
    }

    /**
     * A REQUEST CARRYING [sent] WAS REFUSED for its token (token_required,
     * token_expired, token_invalid): a fresh token is asked for — joining the
     * question under way, or asking anew at most once in 5 s — and waited for.
     * The token to send the request again with, or null when there is none
     * other than [sent] (nothing new could be had: the refusal stands). Blocks.
     */
    fun afterRefusal(sent: String?): String? {
        val wait = when {
            provider != null -> connectionWaitMs
            onNeed != null -> pushWaitMs
            else -> return null
        }
        if (mayAskAgain()) await(wait) { done -> ask(forced = true, then = done) }
        return held?.takeIf { it != sent }
    }

    /**
     * The engine page asked for a token (`graine:need-client-token`): a fresh
     * one by the refusal rule, then [done] hears the newest — at once when none
     * may be asked for now. Never blocks.
     */
    fun refreshForPage(done: (String?) -> Unit) {
        val canAsk = provider != null || onNeed != null
        if (canAsk && mayAskAgain()) ask(forced = true, then = done) else done(held)
    }

    /**
     * A call is ringing: a fresh token is asked for in the background, so the
     * answer finds one — unless one arrived in the last 30 s or a question is
     * already under way. Only with the app's own provider.
     */
    fun ring() {
        if (provider == null) return
        synchronized(lock) {
            if (flight != null) return
            val last = lastTokenAt
            if (last != null && clock() - last < ClientTokenRules.BACKGROUND_MIN_INTERVAL_MS) return
        }
        ask(forced = false, then = null)
    }

    /**
     * The app came to the front (true) or went to the background: nothing is
     * asked in the background; back in front, a token inside its lead — or
     * none, or one whose last question failed — is asked for (never sooner than
     * 30 s after the last token arrived), and any other is scheduled again.
     */
    fun setForeground(inFront: Boolean) {
        var askNow = false
        synchronized(lock) {
            if (foreground == inFront) return
            foreground = inFront
            if (!inFront) {
                cancelTimer()
                return
            }
            if (provider == null || flight != null) return
            val now = clock()
            val exp = localExp
            val life = lifetime
            when {
                held == null || failures > 0 -> askNow = true
                exp == null || life == null -> Unit
                now >= exp - ClientTokenRules.leadMs(life) -> {
                    val earliest = (lastTokenAt ?: now) + ClientTokenRules.BACKGROUND_MIN_INTERVAL_MS
                    if (now >= earliest) askNow = true else arm(earliest - now)
                }
                else -> armRefresh(now)
            }
        }
        if (askNow) ask(forced = false, then = null)
    }

    /**
     * A TOKEN A CALLS HOST PUSHED (CLIENT-TOKENS.md §2.4) — the newest its own
     * manager has; null: none. It answers a question the host was asked, and is
     * sent from now on. Ignored when the app configured a provider of its own.
     */
    fun push(token: String?) {
        if (provider != null) return
        val t = token?.trim()?.takeIf { it.isNotEmpty() }
        val answering: Flight?
        val changed: Boolean
        synchronized(lock) {
            pushedOnce = true
            changed = t != held
            if (t != null) accept(t, clock()) else forget()
            answering = flight?.takeIf { it.toHost }
        }
        if (changed) announce(t)
        answering?.let { finish(it, t) }
    }

    // ── asking ──────────────────────────────────────────────────────────────

    /** A refusal may ask for a token now: one is being asked for already (it is joined), or none was forced in the last 5 s. */
    private fun mayAskAgain(): Boolean = synchronized(lock) {
        flight != null || lastForcedAt?.let { clock() - it >= ClientTokenRules.FORCED_MIN_INTERVAL_MS } ?: true
    }

    /**
     * One question at a time: [then] hears the newest token once the question
     * under way — or this one — has its answer. Asks the provider; without one,
     * the calls host (and waits for its push); with neither, answers at once.
     */
    private fun ask(forced: Boolean, then: ((String?) -> Unit)?) {
        val p = provider
        val host = if (p == null) onNeed else null
        if (p == null && host == null) {
            then?.invoke(held)
            return
        }
        val f = Flight(toHost = p == null)
        synchronized(lock) {
            flight?.let { under -> then?.let { under.waiters += it }; return }
            then?.let { f.waiters += it }
            flight = f
            if (forced) lastForcedAt = clock()
            cancelTimer()
        }
        if (p != null) {
            f.cancelTimeout = schedule(providerTimeoutMs) { finish(f, null) }
            background {
                try {
                    p.fetch { token -> finish(f, token) }
                } catch (e: Throwable) {
                    finish(f, null)
                }
            }
        } else {
            // The host's push answers it; a host that pushes nothing in time leaves the request to go with what there is.
            f.cancelTimeout = schedule(pushWaitMs) { finish(f, held) }
            runCatching { host?.invoke() }
        }
    }

    /** The question [f] has its answer — the first one counts, a late one is ignored. */
    private fun finish(f: Flight, answer: String?) {
        val waiting: List<(String?) -> Unit>
        var changed = false
        val newest: String?
        synchronized(lock) {
            if (f.done) return
            f.done = true
            if (flight === f) flight = null
            f.cancelTimeout?.invoke()
            if (!f.toHost && !f.superseded) {
                val now = clock()
                val t = answer?.trim()?.takeIf { it.isNotEmpty() }
                if (t != null) {
                    changed = t != held
                    accept(t, now)
                    armRefresh(now)
                } else {
                    failures++
                    armBackoff()
                }
            }
            newest = held
            waiting = f.waiters.toList()
        }
        if (changed) announce(newest)
        waiting.forEach { runCatching { it(newest) } }
    }

    /** Waits at most [maxMs] for the question [start] joins or asks, and returns the newest token then. */
    private fun await(maxMs: Long, start: ((String?) -> Unit) -> Unit): String? {
        val latch = CountDownLatch(1)
        start { latch.countDown() }
        try {
            latch.await(maxMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        return held
    }

    // ── the token held and its schedule (under the lock) ───────────────────

    private fun accept(token: String, now: Long) {
        val claims = ClientTokenRules.decode(token)
        held = token
        lifetime = ClientTokenRules.lifetimeMs(claims)
        localExp = ClientTokenRules.localExpiry(claims, now)
        jitterMs = lifetime?.let { Math.round(random() * ClientTokenRules.maxJitterMs(it)) } ?: 0L
        lastTokenAt = now
        failures = 0
    }

    private fun forget() {
        held = null
        localExp = null
        lifetime = null
        jitterMs = 0L
    }

    /** The next refresh, in the background: the local expiry less the lead and the jitter, and never within 30 s of the last token. */
    private fun armRefresh(now: Long) {
        cancelTimer()
        if (provider == null || !foreground) return
        val exp = localExp ?: return
        val life = lifetime ?: return
        val due = exp - ClientTokenRules.leadMs(life) - jitterMs
        val earliest = (lastTokenAt ?: now) + ClientTokenRules.BACKGROUND_MIN_INTERVAL_MS
        arm(maxOf(due, earliest) - now)
    }

    /** After a failure: 2, 4, 8, 16, 32, 60, 60… s, each ±20 % — in front only. */
    private fun armBackoff() {
        cancelTimer()
        if (provider == null || !foreground) return
        val base = ClientTokenRules.backoffMs(failures)
        arm(Math.round(base * (1 + ClientTokenRules.BACKOFF_JITTER * (2 * random() - 1))))
    }

    /** The background question, [delayMs] from now — replacing any other. Under the lock, so its task finds itself armed. */
    private fun arm(delayMs: Long) {
        cancelTimer()
        var mine: (() -> Unit)? = null
        mine = schedule(maxOf(0L, delayMs)) {
            val go = synchronized(lock) {
                // Replaced or cancelled while it was due: the newer one decides.
                if (timer !== mine) return@synchronized false
                timer = null
                foreground && flight == null
            }
            if (go) ask(forced = false, then = null)
        }
        timer = mine
    }

    private fun cancelTimer() {
        timer?.invoke()
        timer = null
    }

    private fun announce(token: String?) = watchers.forEach { runCatching { it(token) } }
}

/** The timers every agent's tokens share: one daemon thread, which lets itself go when nothing is due. */
internal object TokenThreads {
    val timers: CallScheduler by lazy { ExecutorScheduler("graine-token-timer") }
}

/**
 * ONE REQUEST TO GRAINE WITH THE SERVER TOKEN (CLIENT-TOKENS.md §2.2):
 * the body carries the newest token as `clientToken` — a new connection's
 * (the session, a ticket, a call's credential) waits for a usable one first —
 * and a refusal a fresh token can lift is answered once: a refresh, and the
 * same request again with the token it brought. Tokens not in play: the body
 * goes as it is, and nothing about the request changes.
 */
internal object ClientTokenWire {
    /** [send] posts the JSON and throws on a network error, which goes up as it is. */
    fun post(tokens: ClientTokens?, body: JSONObject, newConnection: Boolean, send: (String) -> HttpReply): HttpReply {
        if (tokens == null) return send(body.toString())
        val first = when {
            !tokens.inPlay -> null
            newConnection -> tokens.forConnection()
            else -> tokens.newest
        }
        carry(body, first)
        val reply = send(body.toString())
        if (!ClientTokenRules.refreshable(reply)) return reply
        val next = tokens.afterRefusal(first) ?: return reply
        carry(body, next)
        return send(body.toString())
    }

    private fun carry(body: JSONObject, token: String?) {
        if (token != null) body.put(ClientTokenRules.FIELD, token) else body.remove(ClientTokenRules.FIELD)
    }
}
