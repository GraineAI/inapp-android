package ai.graine.inapp

import java.security.SecureRandom
import java.util.UUID

/** The credential a call session was minted with. Never printed: [toString] leaves the password out. */
internal class SipAccount(val server: String, val username: String, val password: String, val realm: String) {
    val aor: String get() = "sip:$username@$realm"
    val registrar: String get() = "sip:$realm"
    override fun toString(): String = "SipAccount(server=$server, realm=$realm)"
}

/** Why a request or a dial did not succeed, in the browser stack's words ("Request Timeout", "Unavailable", …). */
internal class SipFailure(val code: Int?, val cause: String) {
    override fun toString(): String = if (code != null) "$cause $code" else cause

    companion object {
        const val CONNECTION_ERROR = "Connection Error"
        const val REQUEST_TIMEOUT = "Request Timeout"
        const val SIP_FAILURE_CODE = "SIP Failure Code"
        const val INTERNAL_ERROR = "Internal Error"
        const val AUTHENTICATION_ERROR = "Authentication Error"
        const val BAD_MEDIA_DESCRIPTION = "Bad Media Description"
        const val DIALOG_ERROR = "Dialog Error"

        /** A final status, as the browser's stack names its cause (JsSIP's SIP_ERROR_CAUSES). */
        fun forStatus(code: Int): SipFailure = SipFailure(code, when (code) {
            300, 301, 302, 305, 380 -> "Redirected"
            486, 600 -> "Busy"
            403, 603 -> "Rejected"
            404, 604 -> "Not Found"
            480, 410, 408, 430 -> "Unavailable"
            484, 424 -> "Address Incomplete"
            488, 606 -> "Incompatible SDP"
            401, 407 -> AUTHENTICATION_ERROR
            else -> SIP_FAILURE_CODE
        })

        fun of(result: SipResult): SipFailure = when (result) {
            is SipResult.Response -> forStatus(result.message.status)
            SipResult.Timeout -> SipFailure(null, REQUEST_TIMEOUT)
            SipResult.TransportError -> SipFailure(null, CONNECTION_ERROR)
        }
    }
}

/** The randomness a user agent's messages carry, in the browser stack's shapes. A fixed sequence in the golden tests. */
internal interface SipRandom {
    /** [length] characters of 0–9 and a–v: a tag, a Call-ID, a client nonce. */
    fun token(length: Int): String
    /** `z9hG4bK` and a number under ten million. */
    fun branch(): String
    /** An INVITE's first CSeq: 1 to 9999. */
    fun cseq(): Int
    fun uuid(): String

    companion object {
        val SECURE: SipRandom = object : SipRandom {
            private val r = SecureRandom()
            override fun token(length: Int): String {
                val sb = StringBuilder(length)
                repeat(length) { sb.append("0123456789abcdefghijklmnopqrstuv"[r.nextInt(32)]) }
                return sb.toString()
            }
            override fun branch(): String = "z9hG4bK" + r.nextInt(10_000_000)
            override fun cseq(): Int = 1 + r.nextInt(9_999)
            override fun uuid(): String = UUID.randomUUID().toString()
        }
    }
}

/** One request we send, as the browser's stack writes it. */
internal class SipRequest(
    val method: String,
    val ruri: String,
    val to: String,
    val from: String,
    val callId: String,
    val cseq: Int,
    /** `Name: value` lines after CSeq, in order. */
    val extra: List<String> = emptyList(),
    val body: String? = null,
    val routes: List<String> = emptyList(),
    /** The credentials line (`Authorization: Digest …`), straight after CSeq where the browser puts it. */
    val auth: String? = null,
) {
    /** The same request answering a challenge: CSeq + 1, the credentials added (RFC 3261 §22.2). */
    fun authenticated(header: String, value: String): SipRequest =
        SipRequest(method, ruri, to, from, callId, cseq + 1, extra, body, routes, "$header: $value")

    /** The message, with [via] on top. */
    fun encode(via: String, userAgent: String): String {
        val sb = StringBuilder(512 + (body?.length ?: 0))
        sb.append(method).append(' ').append(ruri).append(" SIP/2.0\r\n")
        for (r in routes) sb.append("Route: ").append(r).append("\r\n")
        sb.append("Via: ").append(via).append("\r\n")
        sb.append("Max-Forwards: ").append(SipUserAgent.MAX_FORWARDS).append("\r\n")
        sb.append("To: ").append(to).append("\r\n")
        sb.append("From: ").append(from).append("\r\n")
        sb.append("Call-ID: ").append(callId).append("\r\n")
        sb.append("CSeq: ").append(cseq).append(' ').append(method).append("\r\n")
        auth?.let { sb.append(it).append("\r\n") }
        for (e in extra) sb.append(e).append("\r\n")
        sb.append("Allow: ").append(SipUserAgent.ALLOW).append("\r\n")
        sb.append("Supported: ").append(SipUserAgent.supported(method)).append("\r\n")
        sb.append("User-Agent: ").append(userAgent).append("\r\n")
        val b = body.orEmpty()
        sb.append("Content-Length: ").append(b.toByteArray(Charsets.UTF_8).size).append("\r\n\r\n")
        sb.append(b)
        return sb.toString()
    }
}

/** What became of a request: its final response, no answer in time, or a socket that could not carry it. */
internal sealed class SipResult {
    class Response(val message: SipMessage) : SipResult()
    object Timeout : SipResult()
    object TransportError : SipResult()
}

/** What a call's dialog reports, on the user agent's scheduler. */
internal interface SipCallListener {
    /** A provisional reply (100 Trying, 18x): the INVITE arrived. */
    fun onProvisional(code: Int) {}
    /** Answered: the 200 OK's SDP. The ACK has gone out. */
    fun onAnswered(sdp: String) {}
    /** Never answered. Not called for a call this side hung up. */
    fun onFailed(failure: SipFailure) {}
    /** The far side hung up (their BYE, answered 200). */
    fun onRemoteBye() {}
    /** The dialog ended for another reason: the session timer ran out, a refresh found no dialog. */
    fun onDialogEnded(failure: SipFailure) {}
    /** Their re-INVITE or UPDATE carried an offer: [answer] it with our SDP, or null to answer with the SDP we last sent. */
    fun onOffer(sdp: String, answer: (String?) -> Unit) { answer(null) }
}

/**
 * A SIP USER AGENT OVER ONE WEBSOCKET — RFC 3261 as far as one outbound call
 * needs it, over RFC 7118, shaped exactly as the voice bar's browser stack
 * (JsSIP 3.13 under the web SDK) shapes it, so the server cannot tell the two
 * apart. The reference trace of a production call (REGISTER to BYE) is what
 * the golden tests hold it to.
 *
 *   REGISTER sip:{realm} (expires 300) → 401 → digest → 200, refreshed before it lapses
 *   INVITE sip:app-{sid}@{realm} → 100 → 401 → ACK → INVITE with credentials → 100 → 200 → ACK
 *   an UPDATE every 45 s while the call lasts (the session refresh the browser sends)
 *   BYE, or CANCEL before the answer; then un-REGISTER and the socket closed
 *
 * Every request of the dialog — the ACK, the BYE and the UPDATE whose
 * Request-URI is the server's own address — goes on this one socket, and the
 * server's in-dialog requests are answered on it: BYE ends the call, a
 * re-INVITE or UPDATE gets our SDP, OPTIONS, INFO and NOTIFY get 200, anything
 * else 501 (405 outside a dialog). Nothing it receives can crash it: a message
 * that does not parse is dropped.
 *
 * All state lives on [scheduler]'s thread; the socket's callbacks hop onto it.
 * A socket that drops while a call is up is reopened and registered again
 * ([keepConnected]), as the browser's stack recovers its transport.
 */
internal class SipUserAgent(
    private val account: SipAccount,
    private val scheduler: CallScheduler,
    private val openSocket: (url: String, protocols: List<String>, listener: CallSocketListener) -> CallSocket,
    private val listener: Listener,
    private val userAgent: String = USER_AGENT,
    private val random: SipRandom = SipRandom.SECURE,
    private val registerExpires: Int = REGISTER_EXPIRES,
) {
    interface Listener {
        /** Registered: the first time with how long it took from [start] (socket, challenge and all); 0 afterwards. */
        fun onRegistered(elapsedMs: Long) {}
        /** The registration failed: refused, unanswered, or the socket never opened or died first. */
        fun onRegistrationFailed(failure: SipFailure) {}
        /** The socket dropped after the user agent had registered. With [keepConnected] it is reopened. */
        fun onConnectionLost(error: String) {}
        /** Every message, both ways (`>` sent, `<` received) — for the live test's trace. */
        fun onMessage(direction: Char, text: String) {}
    }

    /** This UA's own names, fixed for its life as the browser's are: Via host, Contact user, instance. */
    private val viaHost = random.token(12) + ".invalid"
    private val contactUser = random.token(8)
    private val instanceId = random.uuid()
    private val callIdPrefix = random.token(5)
    private val viaTransport = if (account.server.startsWith("ws:", ignoreCase = true)) "WS" else "WSS"

    private val contactRegister = "<sip:$contactUser@$viaHost;transport=ws>;+sip.ice;reg-id=1;+sip.instance=\"<urn:uuid:$instanceId>\""
    private val contactCall = "<sip:$contactUser@$viaHost;transport=ws;ob>"

    private val regCallId = random.token(22)
    private var regCseq = 0
    private var registering = false
    private var expires = registerExpires

    /** Registered with the server on the current socket. */
    var registered = false
        private set
    /** When the server last sent anything ([CallScheduler.now]). */
    var heardAt = Long.MIN_VALUE / 4
        private set
    /** Reopen a socket that drops (a call is up). */
    var keepConnected = false

    private var socket: CallSocket? = null
    private var generation = 0
    private var connected = false
    private var everRegistered = false
    private var stopped = false
    private var startedAt = 0L
    private var reconnects = 0
    private var reconnectTimer: (() -> Unit)? = null
    private var refreshTimer: (() -> Unit)? = null
    private var closeTimer: (() -> Unit)? = null
    private var probe: ((Boolean) -> Unit)? = null

    private val pending = LinkedHashMap<String, ClientTx>()
    /** Our last answer to each request we received, by branch and method: a retransmission gets it again. */
    private val answered = object : LinkedHashMap<String, String>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 32
    }
    private var call: Call? = null

    // ── the socket ──────────────────────────────────────────────────────────

    /** Open the socket and REGISTER. */
    fun start() = scheduler.post {
        if (stopped || socket != null) return@post
        startedAt = scheduler.now()
        connect()
    }

    private fun connect() {
        val gen = ++generation
        connected = false
        socket = try {
            openSocket(account.server, listOf(SUBPROTOCOL), SocketListener(gen))
        } catch (e: Exception) {
            scheduler.post { socketDown(gen, e.message ?: e.javaClass.simpleName) }
            null
        }
    }

    private inner class SocketListener(private val gen: Int) : CallSocketListener {
        override fun onOpen(protocol: String?) = scheduler.post {
            if (gen != generation || stopped) return@post
            connected = true
            heardAt = scheduler.now()
            register()
        }
        override fun onText(text: String) = scheduler.post { if (gen == generation) receive(text) }
        override fun onClosed(code: Int, reason: String) = scheduler.post { socketDown(gen, "closed $code${if (reason.isNotEmpty()) " $reason" else ""}") }
        override fun onFailure(error: Throwable) = scheduler.post { socketDown(gen, error.message ?: error.javaClass.simpleName) }
    }

    private fun socketDown(gen: Int, error: String) {
        if (gen != generation) return
        generation++
        connected = false
        socket = null
        registered = false
        refreshTimer?.invoke(); refreshTimer = null
        val registerInFlight = registering
        // Every transaction on it is lost: each one hears so, once — a REGISTER among them reports its own failure.
        for (tx in pending.values.toList()) tx.fail(SipResult.TransportError)
        registering = false
        answerProbe(false)
        if (stopped) return
        if (!everRegistered) {
            if (!registerInFlight) listener.onRegistrationFailed(SipFailure(null, SipFailure.CONNECTION_ERROR))
            return
        }
        listener.onConnectionLost(error)
        if (keepConnected) scheduleReconnect()
    }

    /** The browser's transport recovery: 2 s, then longer with each attempt, at most 30 s. */
    private fun scheduleReconnect() {
        reconnects++
        val k = Math.floor(Math.random() * Math.pow(2.0, reconnects.toDouble()) + 1).toLong().coerceIn(2, 30)
        reconnectTimer?.invoke()
        reconnectTimer = scheduler.schedule(k * 1000) {
            reconnectTimer = null
            if (!stopped && socket == null) connect()
        }
    }

    private fun write(text: String): Boolean {
        val s = socket ?: return false
        if (!connected) return false
        listener.onMessage('>', text)
        return s.send(text)
    }

    private fun receive(text: String) {
        // RFC 5626 keep-alive: a double CRLF is answered with one; a single one is that answer.
        if (text == "\r\n\r\n") { socket?.send("\r\n"); return }
        if (text == "\r\n") return
        listener.onMessage('<', text)
        val m = SipParser.parse(text) ?: return
        heardAt = scheduler.now()
        answerProbe(true)
        if (m.isRequest) onRequest(m) else onResponse(m)
    }

    // ── client transactions ─────────────────────────────────────────────────

    /**
     * One request and its answer (RFC 3261 §17.1, on a reliable transport:
     * nothing is retransmitted). An INVITE's final failure is ACKed here, on
     * its own branch, as the transaction layer does; its 2xx stays matched
     * (Timer M) so a repeated 200 is ACKed again.
     */
    private inner class ClientTx(
        val request: SipRequest,
        val branch: String,
        private val onProvisional: (SipMessage) -> Unit,
        private val onFinal: (SipResult) -> Unit,
        private val on2xxAgain: (SipMessage) -> Unit,
    ) {
        val key = "$branch:${request.method}"
        val via = "SIP/2.0/$viaTransport $viaHost;branch=$branch"
        /** A provisional reply came: an INVITE may now be cancelled. */
        var proceeding = false
            private set
        var finished = false
            private set
        private var timer: (() -> Unit)? = null

        fun send() {
            pending[key] = this
            timer = scheduler.schedule(TIMER_B_MS) { if (!finished) { remove(); finish(SipResult.Timeout) } }
            if (!write(request.encode(via, userAgent))) { remove(); scheduler.post { finish(SipResult.TransportError) } }
        }

        fun receive(m: SipMessage) {
            val code = m.status
            when {
                code < 200 -> if (!finished) { proceeding = true; onProvisional(m) }
                request.method == "INVITE" && code < 300 -> {
                    if (finished) { on2xxAgain(m); return }
                    timer?.invoke()
                    timer = scheduler.schedule(TIMER_B_MS) { remove() }
                    finish(SipResult.Response(m))
                }
                request.method == "INVITE" -> {
                    ackFailure(m)
                    if (!finished) { remove(); finish(SipResult.Response(m)) }
                }
                else -> { remove(); finish(SipResult.Response(m)) }
            }
        }

        fun fail(result: SipResult) { remove(); finish(result) }

        private fun finish(result: SipResult) {
            if (finished) return
            finished = true
            onFinal(result)
        }

        fun remove() {
            timer?.invoke(); timer = null
            if (pending[key] === this) pending.remove(key)
            if (stopped && pending.isEmpty()) scheduler.post { if (pending.isEmpty()) closeSocket() }
        }

        /** The ACK for a final failure: this branch, the INVITE's Request-URI and CSeq, the response's To. */
        private fun ackFailure(response: SipMessage) {
            val ack = SipRequest("ACK", request.ruri, response.header("to") ?: request.to, request.from, request.callId, request.cseq,
                routes = request.routes)
            write(ack.encode(via, userAgent))
        }
    }

    /** One request, answered once if challenged: [current] is the transaction in flight now (the retry, once there is one). */
    private inner class Exchange {
        var current: ClientTx? = null
    }

    /**
     * Send [request], answering one digest challenge (and one more when the
     * server says the nonce went stale), as the browser's request sender does.
     * [authenticated] hears the retried request, whose CSeq is one higher.
     */
    private fun request(
        request: SipRequest,
        branch: String? = null,
        onProvisional: (SipMessage) -> Unit = {},
        on2xxAgain: (SipMessage) -> Unit = {},
        authenticated: (SipRequest) -> Unit = {},
        /** Whether a challenge may still be answered: not for an INVITE hung up meanwhile. */
        retry: () -> Boolean = { true },
        onFinal: (SipResult) -> Unit,
    ): Exchange {
        val exchange = Exchange()
        var challenged = false
        var staled = false
        fun attempt(r: SipRequest, b: String?) {
            val tx = ClientTx(r, b ?: random.branch(), onProvisional, { result ->
                val m = (result as? SipResult.Response)?.message
                val code = m?.status ?: 0
                // Answered even while stopping: the un-REGISTER and the BYE are challenged too (TRACE.md §2).
                if (m != null && (code == 401 || code == 407) && retry()) {
                    val challenge = SipChallenge.parse(m.header(if (code == 401) "www-authenticate" else "proxy-authenticate"))
                    if (challenge != null && (!challenged || (!staled && challenge.stale))) {
                        val value = SipDigest.authorize(challenge, account.username, account.password, r.method, r.ruri, r.body, random.token(12))
                        if (value != null) {
                            challenged = true
                            if (challenge.stale) staled = true
                            val next = r.authenticated(if (code == 401) "Authorization" else "Proxy-Authorization", value)
                            authenticated(next)
                            attempt(next, null)
                            return@ClientTx
                        }
                    }
                }
                onFinal(result)
            }, on2xxAgain)
            exchange.current = tx
            tx.send()
        }
        attempt(request, branch)
        return exchange
    }

    // ── registration ────────────────────────────────────────────────────────

    /** REGISTER (or refresh it): `Expires`, the instance's Contact, a fresh From tag, the registration's Call-ID. */
    private fun register() {
        if (registering || stopped || !connected) return
        registering = true
        val req = SipRequest(
            "REGISTER", account.registrar, "<${account.aor}>", "<${account.aor}>;tag=${random.token(10)}", regCallId, ++regCseq,
            extra = listOf("Expires: $expires", "Contact: $contactRegister;expires=$expires"),
        )
        request(req, authenticated = { regCseq += 1 }) { result ->
            registering = false
            val m = (result as? SipResult.Response)?.message
            when {
                m != null && m.status in 200..299 -> {
                    registered = true
                    val first = !everRegistered
                    everRegistered = true
                    reconnects = 0
                    scheduleRefresh(expiresFrom(m))
                    listener.onRegistered(if (first) scheduler.now() - startedAt else 0L)
                }
                m != null && m.status == 423 && m.header("min-expires")?.trim()?.toIntOrNull() != null -> {
                    // Interval too brief: again, at the server's minimum (RFC 3261 §10.2.8).
                    expires = m.header("min-expires")!!.trim().toInt().coerceAtLeast(MIN_REGISTER_EXPIRES)
                    register()
                }
                else -> if (!stopped) listener.onRegistrationFailed(SipFailure.of(result))
            }
        }
    }

    /** The lifetime the server granted: our Contact's `expires`, else the Expires header, else what we asked. */
    private fun expiresFrom(m: SipMessage): Int {
        val ours = m.values("contact").mapNotNull { SipAddress.parse(it) }.firstOrNull {
            it.params["+sip.instance"] == "<urn:uuid:$instanceId>" || SipText.userOf(it.uri) == contactUser
        }
        val e = ours?.params?.get("expires")?.trim()?.toIntOrNull() ?: m.header("expires")?.trim()?.toIntOrNull() ?: expires
        return e.coerceAtLeast(MIN_REGISTER_EXPIRES)
    }

    /** Re-REGISTER before it lapses: halfway and a random part of the rest, as the browser's stack does. */
    private fun scheduleRefresh(seconds: Int) {
        refreshTimer?.invoke()
        val ms = if (seconds > 64) seconds * 500L + Math.floor((seconds / 2.0 - 32) * 1000 * Math.random()).toLong()
            else seconds * 1000L - 5000
        refreshTimer = scheduler.schedule(Math.max(1000L, ms)) { refreshTimer = null; register() }
    }

    /**
     * IS THE SOCKET STILL THERE? A re-REGISTER: anything the server sends
     * within [deadlineMs] says yes. A registration made a while before the
     * call (a ring answered after twenty seconds) can sit on a socket a carrier
     * NAT forgot without a word; asking costs one round trip, and an INVITE
     * into a dead socket costs the whole dial deadline.
     */
    fun probe(deadlineMs: Long, done: (alive: Boolean) -> Unit) = scheduler.post {
        if (stopped || !connected) { done(false); return@post }
        answerProbe(false)
        val timer = scheduler.schedule(deadlineMs) { answerProbe(false) }
        probe = { alive -> timer(); done(alive) }
        register()
    }

    private fun answerProbe(alive: Boolean) {
        val p = probe ?: return
        probe = null
        p(alive)
    }

    // ── the call ────────────────────────────────────────────────────────────

    /**
     * Place the call: an INVITE to [target] with [headers] (in order) and
     * [sdp]. Events reach [callListener] on the scheduler. One call per user
     * agent.
     */
    fun invite(target: String, headers: List<Pair<String, String>>, sdp: String, callListener: SipCallListener): Call {
        val c = Call(target, headers, sdp, callListener)
        scheduler.post {
            if (stopped) return@post
            call = c
            c.send()
        }
        return c
    }

    /**
     * Let go: the call hung up (BYE or CANCEL) if it is still up, the
     * registration removed, and the socket closed once those are answered —
     * at most two seconds later, as the browser's stack closes it. No event follows.
     */
    fun stop() = scheduler.post {
        if (stopped) return@post
        stopped = true
        reconnectTimer?.invoke(); reconnectTimer = null
        refreshTimer?.invoke(); refreshTimer = null
        answerProbe(false)
        call?.terminate(null)
        if (registered) unregister()
        // An answered INVITE stays matched only for repeated 200s; it holds nothing open.
        for (tx in pending.values.toList()) if (tx.finished) tx.remove()
        if (pending.isEmpty()) closeSocket()
        else closeTimer = scheduler.schedule(CLOSE_AFTER_MS) { closeSocket() }
    }

    private fun unregister() {
        registered = false
        val req = SipRequest(
            "REGISTER", account.registrar, "<${account.aor}>", "<${account.aor}>;tag=${random.token(10)}", regCallId, ++regCseq,
            extra = listOf("Contact: $contactRegister;expires=0", "Expires: 0"),
        )
        request(req, authenticated = { regCseq += 1 }) { }
    }

    private fun closeSocket() {
        closeTimer?.invoke(); closeTimer = null
        val s = socket ?: return
        generation++
        socket = null
        connected = false
        s.close(1000, "")
    }

    // ── what the server sends ───────────────────────────────────────────────

    private fun onResponse(m: SipMessage) {
        val branch = m.topViaBranch ?: return
        pending["$branch:${m.cseq.method}"]?.receive(m)
    }

    private fun onRequest(m: SipMessage) {
        val method = m.method!!
        val ruri = m.requestUri!!
        // Addressed to someone else: 404, statelessly — but an ACK is never answered.
        val user = SipText.userOf(ruri)
        if (user != account.username && user != contactUser) { if (method != "ACK") replyStateless(m, 404); return }
        if (ruri.startsWith("sips:", ignoreCase = true)) { if (method != "ACK") replyStateless(m, 416); return }
        // A retransmission: the same answer again.
        if (method != "ACK") answered["${m.topViaBranch}:$method"]?.let { write(it); return }
        val c = call
        if (m.toTag != null) {
            when {
                c != null && c.owns(m) -> c.receive(m)
                method == "NOTIFY" -> reply(m, 481, reason = "Subscription does not exist")
                method != "ACK" -> reply(m, 481)
            }
            return
        }
        when (method) {
            "OPTIONS", "NOTIFY" -> reply(m, 200)
            "ACK" -> Unit
            // A CANCEL for nothing we have, a BYE outside a dialog: neither exists here.
            "CANCEL", "BYE" -> reply(m, 481)
            // Calls to this app are not taken on this path; nor are messages or subscriptions.
            else -> reply(m, 405)
        }
    }

    /** A response to [req], as the browser's stack writes one (its Supported, Allow/Accept where due, our To tag). */
    private fun reply(req: SipMessage, code: Int, extra: List<String> = emptyList(), body: String? = null, reason: String? = null) {
        val method = req.method!!
        val sb = StringBuilder(256 + (body?.length ?: 0))
        sb.append("SIP/2.0 ").append(code).append(' ').append(reason ?: REASONS[code].orEmpty()).append("\r\n")
        if (method == "INVITE" && code in 101..200) for (rr in req.values("record-route")) sb.append("Record-Route: ").append(rr).append("\r\n")
        for (v in req.values("via")) sb.append("Via: ").append(v).append("\r\n")
        var to = req.header("to")!!
        if (req.toTag == null && code > 100) to += ";tag=" + random.token(10)
        sb.append("To: ").append(to).append("\r\n")
        sb.append("From: ").append(req.header("from")).append("\r\n")
        sb.append("Call-ID: ").append(req.callId).append("\r\n")
        sb.append("CSeq: ").append(req.cseq.number).append(' ').append(method).append("\r\n")
        for (e in extra) sb.append(e).append("\r\n")
        val supported = ArrayList<String>()
        when (method) {
            "INVITE" -> { supported += "timer"; supported += "ice"; supported += "replaces" }
            "UPDATE" -> { supported += "timer"; if (body != null) supported += "ice"; supported += "replaces" }
        }
        supported += "outbound"
        when {
            method == "OPTIONS" -> sb.append("Allow: ").append(ALLOW).append("\r\n").append("Accept: ").append(ACCEPT).append("\r\n")
            code == 405 -> sb.append("Allow: ").append(ALLOW).append("\r\n")
            code == 415 -> sb.append("Accept: ").append(ACCEPT).append("\r\n")
        }
        sb.append("Supported: ").append(supported.joinToString(",")).append("\r\n")
        if (body != null) {
            sb.append("Content-Type: application/sdp\r\n")
            sb.append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n\r\n").append(body)
        } else {
            sb.append("Content-Length: 0\r\n\r\n")
        }
        val text = sb.toString()
        answered["${req.topViaBranch}:$method"] = text
        write(text)
    }

    private fun replyStateless(req: SipMessage, code: Int) {
        val sb = StringBuilder(256)
        sb.append("SIP/2.0 ").append(code).append(' ').append(REASONS[code].orEmpty()).append("\r\n")
        for (v in req.values("via")) sb.append("Via: ").append(v).append("\r\n")
        var to = req.header("to")!!
        if (req.toTag == null && code > 100) to += ";tag=" + random.token(10)
        sb.append("To: ").append(to).append("\r\n")
        sb.append("From: ").append(req.header("from")).append("\r\n")
        sb.append("Call-ID: ").append(req.callId).append("\r\n")
        sb.append("CSeq: ").append(req.cseq.number).append(' ').append(req.method).append("\r\n")
        sb.append("Content-Length: 0\r\n\r\n")
        write(sb.toString())
    }

    /**
     * ONE CALL: the INVITE, its dialog, and everything inside it. Made by
     * [invite]; all of it on the scheduler's thread.
     */
    inner class Call internal constructor(
        private val target: String,
        private val headers: List<Pair<String, String>>,
        sdp: String,
        private val listener: SipCallListener,
    ) {
        private val callId = callIdPrefix + random.token(15)
        private val fromTag = random.token(10)
        private val from = "<${account.aor}>;tag=$fromTag"
        /** The INVITE (the retry with credentials once challenged): what a CANCEL names. */
        private var invite: Exchange? = null
        private var cancelled: ClientTx? = null
        private var state = State.CALLING
        /** Hung up before the answer: CANCEL once the INVITE was heard; a 200 that comes anyway is ACKed and BYEd. */
        private var canceling = false
        /** No more events reach [listener]: the call was hung up here, or ended. */
        private var silenced = false

        private var remoteTag: String? = null
        private var remoteTarget: String? = null
        private var remoteUri: String? = null
        private var localUri: String? = null
        private var routeSet: List<String> = emptyList()
        private var localSeq = 0
        private var remoteSeq: Int? = null
        private var reofferInFlight = false

        /** The SDP we last sent — what an in-dialog offer is answered with when there is nothing newer. */
        var localSdp: String = sdp
            private set

        /** The server's id for the call (its 200's X-Call-Sid), for a log line. */
        var serverCallId: String? = null
            private set

        private var timerExpires = SESSION_EXPIRES
        private var timerRefresher = false
        private var timerRunning = false
        private var sessionTimer: (() -> Unit)? = null

        internal fun send() {
            // Hung up before it went: it never goes.
            if (silenced) { state = State.TERMINATED; return }
            val extra = ArrayList<String>()
            for ((k, v) in headers) extra += "$k: $v"
            extra += "Contact: $contactCall"
            extra += "Content-Type: application/sdp"
            extra += "Session-Expires: $SESSION_EXPIRES"
            val req = SipRequest("INVITE", target, "<$target>", from, callId, random.cseq(), extra, localSdp)
            invite = request(req, onProvisional = { m -> onProvisional(m) }, on2xxAgain = { m -> on2xx(m) }, retry = { !silenced }) { result -> onFinal(result) }
        }

        private fun onProvisional(m: SipMessage) {
            if (state == State.CALLING) state = State.PROCEEDING
            if (canceling) { sendCancel(); return }
            // Once per status: the server says 100 Trying to the INVITE and again to its retry with credentials.
            if (!silenced && provisionals.add(m.status)) listener.onProvisional(m.status)
        }
        private val provisionals = HashSet<Int>()

        private fun onFinal(result: SipResult) {
            val m = (result as? SipResult.Response)?.message
            if (m != null && m.status in 200..299) { on2xx(m); return }
            if (state == State.TERMINATED || state == State.CONFIRMED) return
            state = State.TERMINATED
            stopTimers()
            if (silenced) return
            silenced = true
            listener.onFailed(SipFailure.of(result))
        }

        private fun on2xx(m: SipMessage) {
            if (remoteTag != null) {
                // The same 200 again: ACK it again. A 200 from another fork: ACK it and hang that one up.
                if (m.toTag == remoteTag) ack(m.cseq.number) else forkHangUp(m)
                return
            }
            if (!makeDialog(m)) {
                state = State.TERMINATED
                if (!silenced) { silenced = true; listener.onFailed(SipFailure(m.status, SipFailure.INTERNAL_ERROR)) }
                return
            }
            serverCallId = m.header("x-call-sid")
            // At once: the server waits for it (7 ms after the 200 in the browser).
            ack(m.cseq.number)
            if (silenced) {
                // Answered after we hung up — the CANCEL race: the call exists now, so it is ended.
                bye(null)
                state = State.TERMINATED
                return
            }
            if (m.body.isBlank()) {
                bye("Reason: SIP ;cause=400; text=\"Missing SDP\"")
                state = State.TERMINATED
                silenced = true
                listener.onFailed(SipFailure(m.status, SipFailure.BAD_MEDIA_DESCRIPTION))
                return
            }
            state = State.CONFIRMED
            timersFromResponse(m)
            listener.onAnswered(m.body)
        }

        /** The dialog, from the 2xx (RFC 3261 §12.1.2): its tags, the far side's Contact, the Record-Route reversed. */
        private fun makeDialog(m: SipMessage): Boolean {
            val contact = m.values("contact").firstOrNull()?.let { SipAddress.parse(it) } ?: return false
            remoteTag = m.toTag ?: return false
            remoteTarget = contact.uri
            remoteUri = SipAddress.parse(m.header("to")!!)?.uri ?: target
            localUri = SipAddress.parse(m.header("from")!!)?.uri ?: account.aor
            routeSet = m.values("record-route").reversed()
            localSeq = m.cseq.number
            return true
        }

        private fun dialogRequest(method: String, cseq: Int, extra: List<String> = emptyList(), body: String? = null): SipRequest =
            SipRequest(method, remoteTarget!!, "<$remoteUri>;tag=$remoteTag", "<$localUri>;tag=$fromTag", callId, cseq, extra, body, routeSet)

        /** The ACK for a 2xx: its own branch, the INVITE's CSeq, to the far side's Contact. */
        private fun ack(cseq: Int) {
            write(dialogRequest("ACK", cseq).encode("SIP/2.0/$viaTransport $viaHost;branch=${random.branch()}", userAgent))
        }

        private fun forkHangUp(m: SipMessage) {
            val contact = m.values("contact").firstOrNull()?.let { SipAddress.parse(it) } ?: return
            val toUri = SipAddress.parse(m.header("to")!!)?.uri ?: target
            val routes = m.values("record-route").reversed()
            val to = "<$toUri>;tag=${m.toTag}"
            write(SipRequest("ACK", contact.uri, to, from, callId, m.cseq.number, routes = routes)
                .encode("SIP/2.0/$viaTransport $viaHost;branch=${random.branch()}", userAgent))
            request(SipRequest("BYE", contact.uri, to, from, callId, m.cseq.number + 1, routes = routes)) { }
        }

        /** CANCEL the INVITE in flight, once it has been heard (RFC 3261 §9.1): its branch, Request-URI, To, From and CSeq. */
        private fun sendCancel() {
            val tx = invite?.current ?: return
            if (!tx.proceeding || tx.finished || cancelled === tx) return
            cancelled = tx
            val r = tx.request
            request(SipRequest("CANCEL", r.ruri, r.to, r.from, r.callId, r.cseq, routes = r.routes), branch = tx.branch) { }
        }

        private fun bye(reasonHeader: String?) {
            if (remoteTarget == null) return
            request(dialogRequest("BYE", ++localSeq, listOfNotNull(reasonHeader)), authenticated = { localSeq += 1 }) { }
        }

        /**
         * Hang up from this side: BYE once answered; before that a CANCEL,
         * which may only follow a provisional reply — so one sent before any
         * waits for it. No event follows.
         */
        fun terminate(reasonHeader: String?) {
            silenced = true
            when (state) {
                State.CALLING, State.PROCEEDING -> { canceling = true; sendCancel() }
                State.CONFIRMED -> { stopTimers(); bye(reasonHeader); state = State.TERMINATED }
                State.TERMINATED -> Unit
            }
        }

        /** Hang up: [terminate], on the scheduler. */
        fun hangUp() = scheduler.post { terminate(null) }

        /** A new offer inside the dialog — the ICE restart: a re-INVITE with [sdp]; [done] hears the answer, or null. */
        fun reoffer(sdp: String, done: (String?) -> Unit) = scheduler.post {
            if (state != State.CONFIRMED || silenced || reofferInFlight) { done(null); return@post }
            reofferInFlight = true
            sendReoffer(sdp, retried = false, done)
        }

        private fun sendReoffer(sdp: String, retried: Boolean, done: (String?) -> Unit) {
            val extra = arrayListOf("Contact: $contactCall", "Content-Type: application/sdp")
            if (timerRunning) extra += "Session-Expires: $timerExpires;refresher=${if (timerRefresher) "uac" else "uas"}"
            request(dialogRequest("INVITE", ++localSeq, extra, sdp), on2xxAgain = { m -> ack(m.cseq.number) }, authenticated = { localSeq += 1 }) { result ->
                val m = (result as? SipResult.Response)?.message
                fun finish(answer: String?) { reofferInFlight = false; done(answer) }
                when {
                    state != State.CONFIRMED || silenced -> finish(null)
                    m != null && m.status in 200..299 -> {
                        ack(m.cseq.number)
                        timersFromResponse(m)
                        localSdp = sdp
                        finish(m.body.takeIf { it.isNotBlank() })
                    }
                    // Both sides offered at once (RFC 3261 §14.1): once more, after a moment.
                    m != null && m.status == 491 && !retried -> scheduler.schedule(1_000) {
                        if (state == State.CONFIRMED && !silenced) sendReoffer(sdp, true, done) else finish(null)
                    }
                    m != null && (m.status == 408 || m.status == 481) -> { finish(null); dialogError(m.status) }
                    else -> finish(null)
                }
            }
        }

        // ── what the far side sends inside the dialog ──

        fun owns(m: SipMessage): Boolean = m.callId == callId && m.toTag == fromTag && (remoteTag == null || m.fromTag == remoteTag)

        fun receive(m: SipMessage) {
            val method = m.method!!
            val n = m.cseq.number
            val last = remoteSeq
            if (last == null || n > last) remoteSeq = n
            else if (n < last && method != "ACK") { reply(m, 500); return }
            when (method) {
                "BYE" -> {
                    if (state != State.CONFIRMED) { reply(m, 403, reason = "Wrong Status"); return }
                    reply(m, 200)
                    state = State.TERMINATED
                    stopTimers()
                    if (!silenced) { silenced = true; listener.onRemoteBye() }
                }
                "INVITE" -> when {
                    state != State.CONFIRMED -> reply(m, 403, reason = "Wrong Status")
                    reofferInFlight -> reply(m, 491)
                    else -> answerOffer(m)
                }
                "UPDATE" -> if (state != State.CONFIRMED) reply(m, 403, reason = "Wrong Status") else answerOffer(m)
                "ACK" -> Unit
                "OPTIONS", "INFO", "NOTIFY" -> reply(m, 200)
                else -> reply(m, 501)
            }
        }

        /**
         * Their re-INVITE or UPDATE: an offer gets our answer (the media's, else
         * the SDP we last sent); an UPDATE without one is a session refresh,
         * answered 200 with no body; a re-INVITE without one gets the SDP we
         * last sent as our offer.
         */
        private fun answerOffer(m: SipMessage) {
            val method = m.method!!
            if (m.body.isBlank()) {
                val extra = arrayListOf("Contact: $contactCall")
                timersFromRequest(m, extra)
                reply(m, 200, extra, if (method == "INVITE") localSdp else null)
                return
            }
            if (m.contentType != "application/sdp") { reply(m, 415); return }
            listener.onOffer(m.body) { answer ->
                scheduler.post {
                    if (state != State.CONFIRMED) return@post
                    val body = answer ?: localSdp
                    localSdp = body
                    val extra = arrayListOf("Contact: $contactCall")
                    timersFromRequest(m, extra)
                    reply(m, 200, extra, body)
                }
            }
        }

        // ── session timers (RFC 4028, as the browser's stack runs them) ──

        /**
         * After our INVITE's (or a refresh's) 2xx: the server's interval when it
         * gives one of at least 60 s, else ours (90 s) — and unless the server
         * says otherwise, we refresh. Production's 200 carries none, so a call
         * sends an UPDATE every 45 s, exactly as the browser does.
         */
        private fun timersFromResponse(m: SipMessage) {
            val se = SipSessionExpires.parse(m.header("session-expires"))
            if (se != null && se.seconds >= MIN_SESSION_EXPIRES) {
                timerExpires = se.seconds
                timerRefresher = (se.refresher ?: "uac") == "uac"
            } else {
                timerExpires = SESSION_EXPIRES
                timerRefresher = true
            }
            runSessionTimer()
        }

        /** Their refresh: answered with the interval and who refreshes — they do, unless they say otherwise. */
        private fun timersFromRequest(m: SipMessage, extra: MutableList<String>) {
            val se = SipSessionExpires.parse(m.header("session-expires"))
            val refresher: String
            if (se != null && se.seconds >= MIN_SESSION_EXPIRES) {
                timerExpires = se.seconds
                refresher = se.refresher ?: "uas"
            } else {
                timerExpires = SESSION_EXPIRES
                refresher = "uas"
            }
            extra += "Session-Expires: $timerExpires;refresher=$refresher"
            timerRefresher = refresher == "uas"
            runSessionTimer()
        }

        private fun runSessionTimer() {
            timerRunning = true
            sessionTimer?.invoke()
            sessionTimer = if (timerRefresher) scheduler.schedule(timerExpires * 500L) { sessionTimer = null; refresh() }
                else scheduler.schedule(timerExpires * 1100L) { sessionTimer = null; sessionExpired() }
        }

        /** The refresh: an UPDATE with no body, our Contact and the interval. Its 2xx arms the next one. */
        private fun refresh() {
            if (state != State.CONFIRMED || silenced) return
            if (reofferInFlight) { runSessionTimer(); return }
            val extra = listOf("Contact: $contactCall", "Session-Expires: $timerExpires;refresher=${if (timerRefresher) "uac" else "uas"}")
            request(dialogRequest("UPDATE", ++localSeq, extra), authenticated = { localSeq += 1 }) { result ->
                val m = (result as? SipResult.Response)?.message ?: return@request
                if (state != State.CONFIRMED) return@request
                when {
                    m.status in 200..299 -> timersFromResponse(m)
                    m.status == 408 || m.status == 481 -> dialogError(m.status)
                }
            }
        }

        private fun sessionExpired() {
            if (state != State.CONFIRMED || silenced) return
            stopTimers()
            bye("Reason: SIP ;cause=408; text=\"Session Timer Expired\"")
            state = State.TERMINATED
            silenced = true
            listener.onDialogEnded(SipFailure(408, SipFailure.REQUEST_TIMEOUT))
        }

        /** A request inside the dialog found no dialog (408/481): the call is over (RFC 3261 §12.2.1.2). */
        private fun dialogError(code: Int) {
            if (state != State.CONFIRMED || silenced) return
            stopTimers()
            bye("Reason: SIP ;cause=500; text=\"Dialog Error\"")
            state = State.TERMINATED
            silenced = true
            listener.onDialogEnded(SipFailure(code, SipFailure.DIALOG_ERROR))
        }

        private fun stopTimers() {
            sessionTimer?.invoke(); sessionTimer = null
            timerRunning = false
        }
    }

    private enum class State { CALLING, PROCEEDING, CONFIRMED, TERMINATED }

    companion object {
        const val SUBPROTOCOL = "sip"
        const val MAX_FORWARDS = 69
        const val ALLOW = "INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,NOTIFY,SUBSCRIBE"
        const val ACCEPT = "application/sdp, application/dtmf-relay"
        const val REGISTER_EXPIRES = 300
        const val MIN_REGISTER_EXPIRES = 10
        const val SESSION_EXPIRES = 90
        const val MIN_SESSION_EXPIRES = 60
        /** Timer B and Timer F: 64 × T1. */
        const val TIMER_B_MS = 32_000L
        /** How long the socket stays after the un-REGISTER and the BYE went, at most. */
        const val CLOSE_AFTER_MS = 2_000L
        /**
         * What the browser's SIP stack announces, so the server treats a phone
         * exactly as it treats the voice bar, and then the SDK's own name.
         */
        const val USER_AGENT = "jambonz-webrtc-0.1.4 graine-inapp-android/" + GraineVersion.NAME

        /** Supported, as the browser's stack lists it per method (session timers on). */
        fun supported(method: String): String = when (method) {
            "REGISTER" -> "path,gruu,outbound"
            "INVITE" -> "timer,ice,replaces,outbound"
            "UPDATE" -> "timer,ice,outbound"
            else -> "outbound"
        }

        val REASONS = mapOf(
            100 to "Trying", 180 to "Ringing", 200 to "OK", 400 to "Bad Request", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 415 to "Unsupported Media Type", 416 to "Unsupported URI Scheme",
            481 to "Call/Transaction Does Not Exist", 488 to "Not Acceptable Here", 491 to "Request Pending",
            500 to "Server Internal Error", 501 to "Not Implemented",
        )
    }
}
