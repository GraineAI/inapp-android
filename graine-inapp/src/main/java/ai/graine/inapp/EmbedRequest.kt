package ai.graine.inapp

import android.app.Application
import org.json.JSONObject

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
 *   · the answer's `verification` kept (passes, verdicts);
 *   · refused `app_not_verified`: the app proven, and the request sent once
 *     more; refused `identity_unverified`: the app's refresher asked, and the
 *     request sent once more with the fresh token. `app_not_allowed` is final.
 *
 * Blocking, never on the main thread — every caller is already off it.
 */
internal object EmbedRequest {
    /** What a request is, for the door: the session, the start of a conversation, the app's own proofs, or anything else. */
    enum class Route { SESSION, CONVERSATION, APP_VERIFY, OTHER }

    const val SESSION_PATH = "/api/embed/session"
    const val TICKET_PATH = "/api/embed/ticket"

    fun route(url: String): Route {
        val path = url.substringBefore('?').trimEnd('/')
        return when {
            path.endsWith(SESSION_PATH) -> Route.SESSION
            path.endsWith(TICKET_PATH) || path.endsWith(RtcMint.PATH) -> Route.CONVERSATION
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
     * Throws what [post] throws.
     */
    fun send(
        url: String,
        body: JSONObject,
        anyAppPass: Boolean = false,
        tokens: ClientTokens? = null,
        newConnection: Boolean = false,
        post: (String) -> HttpReply,
    ): HttpReply {
        val carried = withToken(tokens, newConnection, post)
        val proofs = AppShared.proofsIfMade() ?: return carried(body.toString())
        return proofs.send(url, body, anyAppPass, carried)
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
) {
    /** What a request carried: the identity value, the user it was for, and the app pass. */
    class Sent(val value: String?, val generation: Long, val appPass: String?)

    init {
        app.owner = this
    }

    fun send(url: String, body: JSONObject, anyAppPass: Boolean, post: (String) -> HttpReply): HttpReply {
        val route = EmbedRequest.route(url)
        val key = (body.opt("publishableKey") as? String).orEmpty()
        if (route == EmbedRequest.Route.CONVERSATION) {
            user.beforeConversation(identityValue(body), fieldFor(key), refreshWaitMs)
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
