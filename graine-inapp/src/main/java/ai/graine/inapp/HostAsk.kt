package ai.graine.inapp

import org.json.JSONObject

/**
 * THE ENGINE PAGE ASKS ITS APP — what Talk (the voice bar's web view) cannot
 * get by itself.
 *
 * The engine page's own requests carry Graine's Origin and no app
 * declaration, so an agent that admits only its apps ("app://", "Only my
 * apps", "Require a verified app") refuses them: the session, the ticket, the
 * WebRTC credential. The page asks its host instead, with the website
 * loader's own messages (public/embed/v1.js), and this SDK answers natively —
 * no Origin, the app's declaration, its proofs and the server token, through
 * the one door every request of its own goes through ([EmbedRequest]):
 *
 *   `graine:need-session`                    → `graine:session {session}`: the session this SDK fetched, as served
 *   `graine:need-ticket {purpose: "vouch"}`  → `graine:ticket {ticket, wsUrl, purpose: "vouch"}`: a ticket that
 *                                              vouches for this app — the page's WebRTC credential is then
 *                                              judged as the app's
 *   `graine:need-ticket {mode: "voice"}`     → `graine:ticket {ticket, wsUrl, mode: "voice"}`: the call's ticket,
 *                                              for a call on the WebSocket (through the gateway)
 *
 * A refusal answers `ticket: null` with the server's `error` (code, message,
 * status, reason): the page never goes around it. The bridge says what it
 * answers ([ANSWERS], `ReactNativeWebView.answers()`): a page whose bridge
 * has no `answers` asks nothing, as with an SDK from before this. Nothing here
 * reaches a log but whether an answer carried anything. Pure; HostAskTest.
 */
internal object HostAsk {
    const val NEED_SESSION = "graine:need-session"
    const val NEED_TICKET = "graine:need-ticket"
    const val SESSION = "graine:session"
    const val TICKET = "graine:ticket"

    /** What this SDK answers, as the bridge tells the page (`ReactNativeWebView.answers()`). */
    const val ANSWERS = "session,ticket"

    sealed class Ask {
        object Session : Ask()

        /** [mode] "voice" or null; [purpose] "vouch" or null — anything else the page could say is ignored. */
        data class Ticket(val mode: String?, val purpose: String?) : Ask()
    }

    /** The page's ask, or null when the message is something else. */
    fun read(json: String?): Ask? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        return when (o.optString("type")) {
            NEED_SESSION -> Ask.Session
            NEED_TICKET -> Ask.Ticket(
                mode = if (o.optString("mode") == "voice") "voice" else null,
                purpose = if (o.optString("purpose") == "vouch") "vouch" else null,
            )
            else -> null
        }
    }

    /**
     * The ticket's request, before the door adds the app's declaration, proofs
     * and server token: a vouch (it opens no conversation, so it carries no
     * mode) or the call's ticket with its variables — and who the user is, as
     * every ticket of this SDK's carries.
     */
    fun ticketBody(publishableKey: String, ask: Ask.Ticket, variables: Map<String, Any?>?, identity: IdentityEnvelope?): JSONObject {
        val body = JSONObject().put("publishableKey", publishableKey)
        if (ask.purpose == "vouch") {
            body.put("purpose", "vouch")
        } else {
            body.put("mode", if (ask.mode == "voice") "voice" else "text")
            CallVariables.forTicket(variables)?.let { body.put("metadata", JSONObject(it as Map<*, *>)) }
        }
        identity?.into(body)
        return body
    }

    /** `graine:session` with the session as served — `null` when there is none (the page then asks the server, as before). */
    fun sessionAnswer(session: JSONObject?): JSONObject =
        JSONObject().put("type", SESSION).put("session", session ?: JSONObject.NULL)

    /** The refusals the page must not go around: this app's, the user's, the server token's. */
    private fun refusal(reply: HttpReply): GraineError? {
        EmbedRequest.appRefusal(reply)?.let { return it }
        ClientTokenRules.refusal(reply.code, reply.body)?.let { return it }
        if (reply.code in 200..299) return null
        val o = runCatching { JSONObject(reply.body) }.getOrNull() ?: return null
        return if ((o.opt("code") as? String)?.trim()?.lowercase() == "identity_unverified") EmbedApi.errorFor(reply.code, o, reply.retryAfterSeconds) else null
    }

    /** `graine:ticket` for an ask, from the mint's answer (null: none came back), saying back its mode and purpose as the loader does. */
    fun ticketAnswer(ask: Ask.Ticket, reply: HttpReply?): JSONObject {
        val answer = JSONObject().put("type", TICKET)
        val o = reply?.let { runCatching { JSONObject(it.body) }.getOrNull() }
        val ticket = (o?.opt("ticket") as? String).orEmpty()
        val wsUrl = (o?.opt("wsUrl") as? String).orEmpty()
        if (reply != null && reply.code in 200..299 && ticket.isNotEmpty() && wsUrl.isNotEmpty()) {
            answer.put("ticket", ticket).put("wsUrl", wsUrl)
        } else {
            answer.put("ticket", JSONObject.NULL)
            reply?.let { refusal(it) }?.let { e ->
                val said = JSONObject().put("code", e.code.wire).put("message", e.message)
                if (reply.code > 0) said.put("status", reply.code)
                e.reason?.let { said.put("reason", it) }
                answer.put("error", said)
            }
        }
        if (ask.mode == "voice") answer.put("mode", "voice")
        if (ask.purpose == "vouch") answer.put("purpose", "vouch")
        return answer
    }

    /** What the debug log shows for an answer: whether it carried anything — never a ticket or a session. Null: not one. */
    fun redactForLog(m: JSONObject): String? = when (m.optString("type")) {
        SESSION -> "session=${if (m.isNull("session")) "none" else "set"}"
        TICKET -> buildString {
            append("ticket=${if (m.isNull("ticket")) "none" else "set"}")
            m.optString("mode").takeIf { it.isNotEmpty() }?.let { append(" mode=$it") }
            m.optString("purpose").takeIf { it.isNotEmpty() }?.let { append(" purpose=$it") }
            m.optJSONObject("error")?.optString("code")?.takeIf { it.isNotEmpty() }?.let { append(" refused=$it") }
        }
        else -> null
    }
}
