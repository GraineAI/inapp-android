package ai.graine.inapp

import android.app.Application
import org.json.JSONObject
import java.util.concurrent.CountDownLatch

/*
 * THE ONE DOOR EVERY REQUEST TO GRAINE GOES THROUGH (APP-IDENTITY.md §1, §2).
 *
 * Every request the SDK sends to Graine's embed routes — the session, tickets for
 * the chat and for calls, rtc-session (a full mint and a grant renewal),
 * devices, sign-out, booked calls and their outcomes, memories, a new
 * conversation, past sessions, and the app's own proofs — is sent by
 * [EmbedRequest.send], right before it leaves:
 *
 *   · `app`: which app this is, the app pass held for the key, and the debug
 *     token in development mode;
 *   · inside `identity: {value}`: the identity token and the user pass —
 *     only those given for exactly that value;
 *   · before a conversation (ticket, rtc-session): a JWT about to lapse is
 *     refreshed, and under "enforce" a proof on its way is waited for;
 *   · before a conversation, and before a request that carries the user and
 *     needs them proven (memories, past sessions, booked calls, a workflow, a
 *     new conversation, this install's registration for calls): a user
 *     identified without an identity token has the app's refresher asked and
 *     waited for, at most 10 s, when the agent checks its users — never for a
 *     call Graine rang, which the person is answering, nor for the next
 *     call's grant the agent asks for, which it holds back for that token
 *     itself;
 *   · the answer's `verification` kept (passes, verdicts);
 *   · refused `app_not_verified`: the app proven, and the request sent once
 *     more; refused `identity_unverified`: the app's refresher asked, and the
 *     request sent once more with the fresh token. `app_not_allowed` is final.
 *
 * Blocking, never on the main thread — every caller is already off it.
 */
internal object EmbedRequest {
    /**
     * What a request is, for the door: the session, the start of a
     * conversation, a request that carries the signed-in user and needs them
     * proven ([USER_PATHS]), the app's own proofs, or anything else.
     */
    enum class Route { SESSION, CONVERSATION, USER, APP_VERIFY, OTHER }

    const val SESSION_PATH = "/api/embed/session"
    const val TICKET_PATH = "/api/embed/ticket"

    /**
     * The requests that carry the signed-in user and need them proven —
     * their memories and forgetting them, past sessions and a transcript,
     * booked calls (the list, a booking, a cancellation), a workflow the app
     * starts for them, a new conversation, and this install's registration for
     * the calls Graine rings (without the token, under Verified users, the
     * install is registered alone and not rung for them) — which, like a
     * conversation, wait for a token the user was identified without
     * (UserProof.askForMissingToken).
     */
    private val USER_PATHS = listOf(
        EmbedApi.MEMORIES, EmbedApi.FORGET, EmbedApi.PAST_SESSIONS, EmbedApi.SESSION_TRANSCRIPT,
        EmbedApi.LIST, EmbedApi.SCHEDULE, EmbedApi.CANCEL, EmbedApi.START_WORKFLOW,
        EmbedApi.NEW_CONVERSATION, EmbedApi.DEVICES,
    )

    fun route(url: String): Route {
        val path = url.substringBefore('?').trimEnd('/')
        return when {
            path.endsWith(SESSION_PATH) -> Route.SESSION
            path.endsWith(TICKET_PATH) || path.endsWith(RtcMint.PATH) -> Route.CONVERSATION
            USER_PATHS.any { path.endsWith(it) } -> Route.USER
            path.endsWith(AppProofs.VERIFY_PATH) || path.endsWith(AppProofs.CHALLENGE_PATH) -> Route.APP_VERIFY
            else -> Route.OTHER
        }
    }

    /**
     * A refusal that ends a conversation before it starts — this app is not
     * one of the agent's (`app_not_allowed`), or it is not proven even after
     * the proof and the second try (`app_not_verified`) — as its error; null
     * for any other answer. Such a conversation fails with it: it never falls
     * back to a socket of its own.
     */
    fun appRefusal(reply: HttpReply): GraineError? {
        if (reply.code in 200..299) return null
        val o = runCatching { JSONObject(reply.body) }.getOrNull() ?: return null
        val code = (o.opt("code") as? String)?.trim()
        if (code != GraineErrorCode.APP_NOT_ALLOWED.wire && code != GraineErrorCode.APP_NOT_VERIFIED.wire) return null
        return EmbedApi.errorFor(reply.code, o, reply.retryAfterSeconds)
    }

    /**
     * Send [body] to [url] with [post], through the door above. [anyAppPass]:
     * the app pass goes even if it lapsed (the outage grace's request).
     * [tokens]: the agent's server tokens, carried INSIDE the door
     * ([ClientTokenWire], CLIENT-TOKENS.md §2.2) — [newConnection] for the
     * session, a ticket or a call's credential, which wait for a usable token.
     * Graine checks the token before the app and the user, so a refusal a fresh
     * token lifts is answered in there, before the door reads the answer.
     * [forARing]: the request is for a call Graine rang — its credential, its
     * grant, its ticket — which the person is answering, so it never waits for
     * a token the user was identified without.
     * [tokenAsked]: whoever sends it has already asked for that token and is
     * done holding back for it — the next call's grant, which the agent sends
     * once the token came or will not (GraineInApp.warmNextCall) — so it waits
     * for no refresh here.
     * Throws what [post] throws.
     */
    fun send(
        url: String,
        body: JSONObject,
        anyAppPass: Boolean = false,
        tokens: ClientTokens? = null,
        newConnection: Boolean = false,
        forARing: Boolean = false,
        tokenAsked: Boolean = false,
        post: (String) -> HttpReply,
    ): HttpReply {
        val carried = withToken(tokens, newConnection, post)
        val proofs = AppShared.proofsIfMade() ?: return carried(body.toString())
        return proofs.send(url, body, anyAppPass, forARing, tokenAsked, carried)
    }

    /** [post], carrying [tokens]' newest token on every body it sends; null tokens: [post] as it is. */
    fun withToken(tokens: ClientTokens?, newConnection: Boolean, post: (String) -> HttpReply): (String) -> HttpReply =
        if (tokens == null) post else { json -> ClientTokenWire.post(tokens, JSONObject(json), newConnection, post) }
}

/**
 * THE APP'S AND THE USER'S PROOFS, TOGETHER — one per process (AppShared):
 * what [EmbedRequest] puts on a request, and reads off its answer.
 */
internal class Proofs(
    val app: AppProofs,
    val user: UserProof,
    /** The trait the agent behind a publishable key identifies users by; null before its session. */
    private val fieldFor: (publishableKey: String) -> String?,
    /** How long a request waits for the app's refresher. */
    private val refreshWaitMs: Long = UserProof.REFRESH_WAIT_MS,
    /**
     * How long a request waits for the app's refresher when the user was
     * identified without a token ([UserProof.NO_TOKEN_WAIT_MS]) — and how long
     * what nobody waits on holds back for it (GraineInApp.holdForMissingToken).
     */
    val noTokenWaitMs: Long = UserProof.NO_TOKEN_WAIT_MS,
) {
    /** What a request carried: the identity value, the user it was for, and the app pass. */
    class Sent(val value: String?, val generation: Long, val appPass: String?)

    init {
        app.owner = this
    }

    /**
     * Send [body] to [url] with [post], with what the proofs put on it and
     * read off its answer — [forARing]: a request for a call Graine rang,
     * which never waits for a token the user was identified without;
     * [tokenAsked]: one whose sender already held back for that token, which
     * waits for no refresh (EmbedRequest.send).
     */
    fun send(
        url: String,
        body: JSONObject,
        anyAppPass: Boolean,
        forARing: Boolean = false,
        tokenAsked: Boolean = false,
        post: (String) -> HttpReply,
    ): HttpReply {
        val route = EmbedRequest.route(url)
        val key = (body.opt("publishableKey") as? String).orEmpty()
        // A user identified without a token, an agent that checks its users, an app with a refresher: the refresher asked
        // and waited for before a conversation — or a request that carries the user and needs them proven — goes, so it
        // carries the token (UserProof.askForMissingToken). Never for a call Graine rang: the person is answering it.
        if (!forARing && !tokenAsked && (route == EmbedRequest.Route.CONVERSATION || route == EmbedRequest.Route.USER)) {
            awaitMissingToken(key, identityValue(body))
        }
        if (route == EmbedRequest.Route.CONVERSATION) {
            user.beforeConversation(identityValue(body), fieldFor(key), refreshWaitMs, waitForInFlight = !tokenAsked)
            app.beforeConversation(key)
        }
        var sent = stamp(body, key, anyAppPass)
        var reply = post(body.toString())
        read(route, key, sent, reply)
        if (route == EmbedRequest.Route.APP_VERIFY || route == EmbedRequest.Route.SESSION) return reply
        val again = when (refusalCode(reply)) {
            GraineErrorCode.APP_NOT_VERIFIED.wire -> app.proveForRetry(key)
            GraineErrorCode.IDENTITY_UNVERIFIED.wire -> sent.value != null && user.refreshNow(refreshWaitMs)
            else -> false
        }
        if (!again) return reply
        sent = stamp(body, key, anyAppPass)
        reply = post(body.toString())
        read(route, key, sent, reply)
        return reply
    }

    /**
     * Put on [body] what it carries for [key]: `app`, and inside `identity`
     * the token and the pass that belong with its value — and nothing left
     * from an earlier stamp of the same body.
     */
    fun stamp(body: JSONObject, key: String, anyAppPass: Boolean = false): Sent {
        val aboutACall = (body.opt("callId") as? String)?.isNotBlank() == true
        val app = app.appJson(key.ifEmpty { null }, aboutACall, anyAppPass)
        if (app != null) body.put("app", app) else body.remove("app")
        val identity = body.opt("identity") as? JSONObject
        val value = identityValue(body)
        var generation = user.generation
        if (identity != null) {
            identity.remove("token")
            identity.remove("pass")
            if (value != null) {
                val p = user.proofFor(key, value, fieldFor(key), aboutACall)
                p.token?.let { identity.put("token", it) }
                p.pass?.let { identity.put("pass", it) }
                generation = p.generation
            }
        }
        return Sent(value, generation, (app?.opt("pass") as? String)?.takeIf { it.isNotEmpty() })
    }

    /** The answer, as it bears on the proofs: `verification` on a success, the refusals of §2.3. */
    private fun read(route: EmbedRequest.Route, key: String, sent: Sent, reply: HttpReply) {
        // The session's `security` is read where the session is (GraineInApp); the proofs read their own answers.
        if (route == EmbedRequest.Route.SESSION || route == EmbedRequest.Route.APP_VERIFY) return
        val o = runCatching { JSONObject(reply.body) }.getOrNull() ?: return
        if (reply.code in 200..299) {
            val v = o.opt("verification") as? JSONObject ?: return
            app.heard(key, v, sent.appPass)
            user.heard(key, sent.value, sent.generation, v)
            return
        }
        val reason = (o.opt("reason") as? String)?.trim()
        when ((o.opt("code") as? String)?.trim()) {
            GraineErrorCode.APP_NOT_ALLOWED.wire -> app.refused(key, GraineErrorCode.APP_NOT_ALLOWED.wire, null, sent.appPass)
            GraineErrorCode.APP_NOT_VERIFIED.wire -> app.refused(key, GraineErrorCode.APP_NOT_VERIFIED.wire, reason, sent.appPass)
            GraineErrorCode.IDENTITY_UNVERIFIED.wire -> user.refused(key, sent.generation, reason)
        }
    }

    private fun refusalCode(reply: HttpReply): String? {
        if (reply.code in 200..299) return null
        return runCatching { (JSONObject(reply.body).opt("code") as? String)?.trim() }.getOrNull()
    }

    private fun identityValue(body: JSONObject): String? =
        ((body.opt("identity") as? JSONObject)?.opt("value") as? String)?.takeIf { it.isNotEmpty() }

    // ── the token not yet held, for what does not start at this door ────────

    /** [key]'s agent checks its users — Verified users on report or enforce — as its session said; false before the session. */
    private fun checksUsers(key: String): Boolean = app.security(key)?.checksUsers == true

    /**
     * [UserProof.awaitMissingToken] for user [value] of [key]'s agent, at
     * most [noTokenWaitMs]: what [send] does before a conversation, for one
     * that may begin without a request through this door — a WebRTC call
     * minted at the telephony gateway with a grant fetched earlier. A token
     * that comes back lets go of every grant kept under the old verdict before
     * this returns (GraineInApp.identityTokenChanged), so the call mints with
     * it. Never on the main thread.
     */
    fun awaitMissingToken(key: String, value: String?) = user.awaitMissingToken(value, fieldFor(key), checksUsers(key), noTokenWaitMs)

    /** [UserProof.askForMissingToken] for user [value] of [key]'s agent: the latch to wait on (with [awaitAsked]), or null. Never blocks. */
    fun askForMissingToken(key: String, value: String?): CountDownLatch? =
        user.askForMissingToken(value, fieldFor(key), checksUsers(key))

    /** The same, for what nobody waits on: [settled] told when that refresh ends; false when nothing was asked (UserProof). Never blocks. */
    fun askForMissingToken(key: String, value: String?, settled: (brought: Boolean) -> Unit): Boolean =
        user.askForMissingToken(value, fieldFor(key), checksUsers(key), settled)

    /** Wait for what [askForMissingToken] handed back, at most [noTokenWaitMs]. Never on the main thread. */
    fun awaitAsked(wait: CountDownLatch) = user.await(wait, noTokenWaitMs)

    /** Whether a conversation with [key]'s agent for user [value] would wait for a token now ([UserProof.missingTokenDue]). */
    fun missingTokenDue(key: String, value: String?): Boolean = user.missingTokenDue(value, fieldFor(key), checksUsers(key))

    companion object {
        /**
         * The proofs of an app's process, made by its first agent: the app's
         * declaration and debug-build flag, its private store, Play Integrity
         * when it is there, and [env]'s main thread for the app's refresher.
         */
        fun forApp(application: Application, env: AgentEnv): Proofs = Proofs(
            app = AppProofs(
                declaration = { AppDeclarations.read(application) },
                store = AppPassStore.android(application),
                device = { env.device ?: DeviceStore.of(application) },
                debuggable = { AppDeclarations.debuggable(application) },
                prover = { PlayIntegrity.prover(application) },
                worker = ExecutorScheduler("graine-app-proof"),
                changed = { key -> AppShared.verificationChanged(key) },
            ),
            user = UserProof(
                callOn = { r -> env.main.post(r) },
                changed = { AppShared.verificationChanged(null) },
                tokenChanged = { AppShared.identityTokenChanged() },
            ),
            fieldFor = { key -> AppShared.identityFieldFor(key) },
        )
    }
}
