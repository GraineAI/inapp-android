package ai.graine.inapp

import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/*
 * PROVING THE USER — "Verified users" (APP-IDENTITY.md §1.3, §2.2).
 *
 * identify(traits, memory, identityToken): the token proves the identity
 * value — the trait the agent's Identity setting names — and rides INSIDE
 * `identity: {value, token, pass}` on every request that carries that value,
 * beside the USER PASS Graine gave for exactly that value. Never anywhere
 * else: not in prompt variables, metadata, the context, a URL or a log line.
 * Held in memory only.
 *
 * A token and a pass belong to the user they were given with: another user,
 * or nobody, drops both in the same step that changes the identity, and each
 * is only ever sent beside the value it was given with (proofFor). The app's
 * refresher is asked for a fresh token when Graine says the token expired,
 * when a route refuses `identity_unverified` (the request is then sent once
 * more), before a conversation whose JWT has less than a minute left, and
 * when a call rings — at most once every 30 seconds, never twice at once.
 */

/** A user pass as kept: opaque, and when it lapses on the device's wall clock. */
internal class UserPass(val pass: String, val expiresAt: Long)

/** What to send inside `identity` beside a value: the token and the pass, when they belong with it; and who the user was then. */
internal class UserProofs(val token: String?, val pass: String?, val generation: Long)

internal class UserProof(
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Where the app's refresher is called: the main thread in an app. */
    private val callOn: (Runnable) -> Unit = { it.run() },
    /** The user part of what agents show may have changed. */
    private val changed: () -> Unit = {},
    /** The token itself changed (identify, setIdentityToken, the refresher): conversations prepared with the old one are let go. */
    private val tokenChanged: () -> Unit = {},
) {
    private val lock = Any()
    private var token: String? = null
    /** The traits the token was given with: it goes only beside the value they name. */
    private var traits: Map<String, Any?>? = null
    /** Bumps whenever the user changes: what a request carried is compared with it when the answer comes back. */
    @Volatile var generation = 0L
        private set
    private val passes = HashMap<String, UserPass>()
    /** What Graine last said about the current user, per agent key. */
    private val heard = HashMap<String, Pair<GraineUserVerdict, String?>>()

    /** The app's refresher; null when it set none. */
    @Volatile var refresher: GraineIdentityTokenRefresher? = null
    private var refreshStartedAt = Long.MIN_VALUE / 2
    private var refreshing: CountDownLatch? = null

    /** A token the app gave, as the SDK keeps it: blank is none, longer than the limit is refused (and never sent). */
    fun accept(token: String?): String? {
        val t = token?.takeIf { it.isNotBlank() } ?: return null
        if (t.length > MAX_TOKEN_LENGTH) {
            CallLog.error("an identity token longer than $MAX_TOKEN_LENGTH characters was ignored")
            return null
        }
        return t
    }

    /**
     * identify(): [next] the traits now, [token] the token given with them,
     * [sameUser] whether they name the user the SDK had. Another user, or
     * nobody, drops the token and every user pass at once; the same user keeps
     * the token unless a new one came. True when the user or the token changed.
     */
    fun identify(next: Map<String, Any?>?, token: String?, sameUser: Boolean): Boolean {
        val t = accept(token)
        val tokenMoved: Boolean
        val userMoved = next == null || !sameUser
        var dropped: CountDownLatch? = null
        synchronized(lock) {
            if (userMoved) {
                generation++
                passes.clear()
                heard.clear()
                traits = next?.let { LinkedHashMap(it) }
                val now = if (next == null) null else t
                tokenMoved = this.token != now
                this.token = now
                // The last user's refresh is theirs: its answer is dropped, and the new user's is not held back by it.
                dropped = refreshing
                refreshing = null
                refreshStartedAt = Long.MIN_VALUE / 2
            } else {
                traits = LinkedHashMap(next!!)
                tokenMoved = t != null && t != this.token
                if (t != null) this.token = t
            }
        }
        dropped?.countDown()
        // Another user is the identity's own change, which lets go of what the last one had by itself: only the same user's new token is news here.
        if (tokenMoved && !userMoved) tokenChanged()
        if (userMoved) changed()
        return userMoved || tokenMoved
    }

    /** setIdentityToken(): the token for the current user, the user unchanged; null clears it. True when it changed. */
    fun setToken(token: String?): Boolean {
        val t = if (token == null) null else accept(token) ?: return false
        val moved = synchronized(lock) {
            if (t == this.token) false else { this.token = t; true }
        }
        if (moved) tokenChanged()
        return moved
    }

    /** The token and pass to send beside [value] on [key]'s requests — only when they belong to it ([field] names the trait that is the value). */
    fun proofFor(key: String, value: String, field: String?, aboutACall: Boolean): UserProofs = synchronized(lock) {
        val t = token?.takeIf { field != null && IdentityRules.pick(traits, field) == value }
        val now = clock()
        val p = passes[slot(key, value)]?.takeIf { now < it.expiresAt || (aboutACall && now < it.expiresAt + AppProofs.RING_GRACE_MS) }
        UserProofs(t, p?.pass, generation)
    }

    /** The token held for [value], as [proofFor] would send it. */
    private fun tokenFor(value: String?, field: String?): String? = synchronized(lock) {
        token?.takeIf { value != null && field != null && IdentityRules.pick(traits, field) == value }
    }

    // ── answers ─────────────────────────────────────────────────────────────

    /**
     * An answer's `verification` for a request that carried [value] for the
     * user of [sentGeneration]: Graine's verdict kept for [key], a new user
     * pass kept for exactly that value — unless the user changed since — and,
     * when the token expired, the refresher asked for a fresh one.
     */
    fun heard(key: String, value: String?, sentGeneration: Long, verification: JSONObject) {
        val verdict = GraineUserVerdict.fromWire(verification.opt("user")) ?: return
        val reason = (verification.opt("userReason") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val expired = verdict == GraineUserVerdict.INVALID && reason == "expired"
        synchronized(lock) {
            if (sentGeneration != generation) return
            heard[key] = verdict to reason
            if (value != null) {
                val pass = (verification.opt("userPass") as? String)?.takeIf { it.isNotBlank() }
                val ttl = Js.finite(verification.opt("userPassExpiresIn"))?.takeIf { it > 0 }
                if (pass != null && ttl != null) passes[slot(key, value)] = UserPass(pass, clock() + (ttl * 1000).toLong())
                else if (expired) passes.remove(slot(key, value))
            }
        }
        changed()
        if (expired) refreshInBackground()
    }

    /** A route refused [key]'s request `identity_unverified`, for the user of [sentGeneration]. */
    fun refused(key: String, sentGeneration: Long, reason: String?) {
        synchronized(lock) {
            if (sentGeneration != generation) return
            heard[key] = GraineUserVerdict.UNVERIFIED to reason?.takeIf { it.isNotBlank() }
        }
        changed()
    }

    /** [key]'s user verdict: off unless its agent checks users ([checksUsers]); then what Graine last said, unverified before it said. */
    fun verdict(key: String, checksUsers: Boolean): Pair<GraineUserVerdict, String?> {
        if (!checksUsers) return GraineUserVerdict.OFF to null
        return synchronized(lock) { heard[key] } ?: (GraineUserVerdict.UNVERIFIED to null)
    }

    // ── the refresher ───────────────────────────────────────────────────────

    /**
     * Before a ticket or rtc-session carrying [value]: a refresh on its way is
     * waited for, and a JWT with less than a minute left is refreshed first —
     * each at most [waitMs]. Never on the main thread.
     */
    fun beforeConversation(value: String?, field: String?, waitMs: Long) {
        val (inFlight, t) = synchronized(lock) { refreshing to tokenFor(value, field) }
        if (inFlight != null) {
            // A refresher that did not answer in time is not waited for again by the next conversation; its late answer still counts.
            if (!inFlight.await(waitMs, TimeUnit.MILLISECONDS)) synchronized(lock) { if (refreshing === inFlight) refreshing = null }
            return
        }
        val exp = t?.let { Jwt.expiresAt(it) } ?: return
        if (exp * 1000L - clock() < JWT_REFRESH_BELOW_MS) refreshNow(waitMs)
    }

    /** Ask the refresher now and wait up to [waitMs] for it (one on its way is joined): true when the token changed. Never on the main thread. */
    fun refreshNow(waitMs: Long): Boolean {
        val before = synchronized(lock) { token }
        val wait = synchronized(lock) { refreshing } ?: start() ?: return false
        wait.await(waitMs, TimeUnit.MILLISECONDS)
        return synchronized(lock) { token != null && token != before }
    }

    /** Ask the refresher, and wait for nothing: a ring, an expired token. */
    fun refreshInBackground() {
        start()
    }

    /**
     * Call the refresher — unless there is none, it was called less than 30
     * seconds ago, or a call is on its way — on [callOn]; its answer replaces
     * the token when it is one, for the user it was asked for. The latch to
     * wait on, or null when it was not called.
     */
    private fun start(): CountDownLatch? {
        val r = refresher ?: return null
        val latch: CountDownLatch
        val asked: Long
        synchronized(lock) {
            val now = clock()
            refreshing?.let { inFlight ->
                // A refresher that never answered is given up on, so the next can be asked.
                if (now - refreshStartedAt < REFRESH_GIVE_UP_MS) return null
                inFlight.countDown()
            }
            if (now - refreshStartedAt < REFRESH_MIN_INTERVAL_MS) return null
            refreshStartedAt = now
            latch = CountDownLatch(1)
            refreshing = latch
            asked = generation
        }
        val answered = AtomicBoolean(false)
        callOn(Runnable {
            try {
                r.refresh { token -> if (answered.compareAndSet(false, true)) refreshed(latch, asked, token) }
            } catch (t: Throwable) {
                // A refresher that throws leaves the token as it was.
                if (answered.compareAndSet(false, true)) refreshed(latch, asked, null)
            }
        })
        return latch
    }

    private fun refreshed(latch: CountDownLatch, asked: Long, token: String?) {
        val t = accept(token)
        val moved = synchronized(lock) {
            if (refreshing === latch) refreshing = null
            // Null keeps the token; another user since the ask keeps theirs.
            if (t == null || asked != generation || traits == null || t == this.token) false else { this.token = t; true }
        }
        latch.countDown()
        if (moved) tokenChanged()
    }

    private fun slot(key: String, value: String): String = key + "\n" + value

    companion object {
        /** The longest identity token the SDK sends (§1.3). */
        const val MAX_TOKEN_LENGTH = 4096
        /** The refresher is asked at most this often (§1.3). */
        const val REFRESH_MIN_INTERVAL_MS = 30_000L
        /** A JWT with less than this left is refreshed before a conversation (§1.3). */
        const val JWT_REFRESH_BELOW_MS = 60_000L
        /** A refresher that has not answered in this long is given up on. */
        const val REFRESH_GIVE_UP_MS = 60_000L
        /** How long a request waits for the refresher's answer. */
        const val REFRESH_WAIT_MS = 10_000L
    }
}

/** A JWT read WITHOUT verifying it, only to know when it lapses (§1.3): Graine verifies it. */
internal object Jwt {
    private val SEGMENT = Regex("^[A-Za-z0-9_-]*$")

    /** `exp`, in seconds since the epoch, of a token of three base64url segments; null for anything else. */
    fun expiresAt(token: String): Long? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts.any { !SEGMENT.matches(it) }) return null
        val json = runCatching { String(B64.decode(parts[1].replace('-', '+').replace('_', '/')), Charsets.UTF_8) }.getOrNull() ?: return null
        val exp = runCatching { JSONObject(json).opt("exp") }.getOrNull()
        return Js.finite(exp)?.toLong()
    }
}
