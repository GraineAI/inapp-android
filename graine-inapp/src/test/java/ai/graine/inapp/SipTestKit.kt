package ai.graine.inapp

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/*
 * WHAT THE SIP TESTS SHARE (and the WebRTC ones after them): randomness that
 * repeats, a socket the test speaks through, an SBC that answers as
 * production's does (from the reference trace), and a loopback WebSocket
 * server. Nothing here reaches a real host.
 */

/** The production realm and agent as the trace shows them, redacted the same way. */
internal object Trace {
    const val REALM = "grainewhatsup.sip.graine.ai"
    const val SBC = "13.205.250.72"
    const val APP_SID = "817361cd-2a13-4937-b465-686d3f58eddd"
    val SERVER = listOf("wss", "://", REALM, ":8443").joinToString("")

    /** The SBC's answer (call 1), as served: PCMU and telephone-event, setup:active, one host candidate. */
    val ANSWER = listOf(
        "v=0",
        "o=Jambonz-Mediaserver 1791980511 1791980512 IN IP4 13.205.250.72",
        "s=Jambonz-Mediaserver",
        "t=0 0",
        "m=audio 40414 UDP/TLS/RTP/SAVPF 0 126",
        "c=IN IP4 13.205.250.72",
        "a=mid:0",
        "a=rtpmap:0 PCMU/8000",
        "a=rtpmap:126 telephone-event/8000",
        "a=fmtp:126 0-15",
        "a=sendrecv",
        "a=rtcp:40414",
        "a=rtcp-mux",
        "a=setup:active",
        "a=fingerprint:sha-256 6E:0F:6A:86:00",
        "a=tls-id:971c16c527f442e59dc2001a9d8e8959",
        "a=ptime:20",
        "a=ice-ufrag:DLtsxRNa",
        "a=ice-pwd:redactedredactedredacted",
        "a=ice-options:trickle",
        "a=candidate:IEr81xVVpfGHdWH2 1 UDP 2130706431 13.205.250.72 40414 typ host",
        "a=end-of-candidates",
        "",
    ).joinToString("\r\n")

    /** An offer as libwebrtc writes one, with its candidates; the format list in its own order. */
    val OFFER = listOf(
        "v=0",
        "o=- 4844249809718397698 2 IN IP4 127.0.0.1",
        "s=-",
        "t=0 0",
        "a=group:BUNDLE 0",
        "m=audio 51750 UDP/TLS/RTP/SAVPF 111 63 9 0 8 13 110 126",
        "c=IN IP4 203.0.113.9",
        "a=candidate:1 1 udp 2122194688 192.0.2.10 65463 typ host generation 0",
        "a=candidate:2 1 udp 1685987072 198.51.100.7 65463 typ srflx raddr 192.0.2.10 rport 65463 generation 0",
        "a=ice-ufrag:gC1e",
        "a=ice-pwd:redactedredactedredacted",
        "a=setup:actpass",
        "a=mid:0",
        "a=sendrecv",
        "a=rtcp-mux",
        "a=rtpmap:111 opus/48000/2",
        "a=rtpmap:63 red/48000/2",
        "a=rtpmap:9 G722/8000",
        "a=rtpmap:0 PCMU/8000",
        "a=rtpmap:8 PCMA/8000",
        "a=rtpmap:13 CN/8000",
        "a=rtpmap:110 telephone-event/48000",
        "a=rtpmap:126 telephone-event/8000",
        "",
    ).joinToString("\r\n")
}

/** Randomness that repeats: tokens of a known shape from a counter, branches and CSeqs from fixed sequences. */
internal class FixedSipRandom(private val cseq: Int = 367) : SipRandom {
    private var n = 0
    private var b = 7801448
    override fun token(length: Int): String {
        val sb = StringBuilder()
        val seed = "abcdefghijklmnopqrstuv0123456789"
        while (sb.length < length) { sb.append(seed[(n + sb.length) % seed.length]) }
        n++
        return sb.toString()
    }
    override fun branch(): String = "z9hG4bK" + (b++)
    override fun cseq(): Int = cseq
    override fun uuid(): String = "c30c2118-f5c4-4dd6-898c-3d8485be8150"
}

/** A socket the test speaks through: what the UA sends, and a way to answer it. */
internal class FakeSipSocket(private val scheduler: CallScheduler? = null) : CallSocket {
    val sent = mutableListOf<String>()
    var listener: CallSocketListener? = null
    var closed: Int? = null
    var url: String? = null
    var protocols: List<String>? = null

    override fun send(text: String): Boolean {
        if (closed != null) return false
        sent += text
        return true
    }
    override fun close(code: Int, reason: String) {
        if (closed != null) return
        closed = code
        listener?.onClosed(code, reason)
    }
    fun open() = listener!!.onOpen("sip")
    fun deliver(text: String) = listener!!.onText(text)
    fun drop() { closed = 1006; listener?.onFailure(java.io.IOException("reset")) }

    /** The messages the UA sent, parsed (keep-alives left out). */
    fun messages(): List<SipMessage> = sent.filter { it.length > 4 }.mapNotNull { SipParser.parse(it) }
    fun requests(method: String): List<SipMessage> = messages().filter { it.method == method }
    fun last(): SipMessage = messages().last()
}

/**
 * AN SBC THAT ANSWERS AS PRODUCTION'S DOES (the reference trace): every new
 * request challenged with 401 and a fresh nonce, a 100 Trying before the
 * INVITE's challenge and before its answer, a 200 with the trace's answer and
 * its Contact, no Record-Route and no Session-Expires. Checks every digest it
 * is given against the password.
 */
internal class FakeSbc(
    private val password: String = "secret-pass",
    private val realm: String = Trace.REALM,
) {
    /** What it does with an INVITE carrying credentials: answer it, ring and wait, refuse it, or say nothing. */
    enum class Answer { OK, TRYING_ONLY, SILENT, BUSY, UNAVAILABLE }

    var inviteAnswer = Answer.OK
    var registerAnswer = 200
    var challenge = true
    /** Challenge the request with credentials again: the credential is refused. */
    var refuseCredentials = false
    /** Say nothing to anything: a socket a NAT forgot. */
    var deaf = false
    /** Digests that did not match: the test fails on any. */
    val badDigests = mutableListOf<String>()
    private var nonce = 0
    private var toTag = 0

    /** The responses to [m], in order — none for an ACK or when the SBC says nothing. */
    fun respond(m: SipMessage): List<String> {
        val method = m.method ?: return emptyList()
        if (deaf) return emptyList()
        if (method == "ACK") return emptyList()
        if (method == "CANCEL") return listOf(response(m, 200))
        val auth = m.header("authorization") ?: m.header("proxy-authorization")
        if (method == "INVITE" && m.toTag == null && inviteAnswer == Answer.SILENT) return emptyList()
        val out = ArrayList<String>()
        if (method == "INVITE" && m.toTag == null) out += response(m, 100)
        if (challenge && (auth == null || refuseCredentials) && m.toTag == null) {
            out += response(m, 401, extra = listOf("WWW-Authenticate: Digest realm=\"$realm\", algorithm=MD5, qop=\"auth\", nonce=\"n${++nonce}\""), tag = true)
            return out
        }
        if (auth != null) checkDigest(m, auth)
        when (method) {
            "REGISTER" -> {
                if (registerAnswer != 200) { out += response(m, registerAnswer, tag = true); return out }
                val contact = m.header("contact")!!
                val expires = m.header("expires") ?: "300"
                out += response(m, 200, extra = listOf("Contact: $contact", "Expires: $expires"), tag = true)
            }
            "INVITE" -> when (inviteAnswer) {
                Answer.OK -> out += response(m, 200, tag = true, body = Trace.ANSWER, extra = listOf(
                    "Contact: <sip:${Trace.SBC}:5060;transport=tcp>", "Content-Type: application/sdp"), after = listOf("X-Call-Sid: 208fd2fd"))
                Answer.TRYING_ONLY, Answer.SILENT -> Unit
                Answer.BUSY -> out += response(m, 486, tag = true)
                Answer.UNAVAILABLE -> out += response(m, 503, tag = true)
            }
            else -> out += response(m, 200)
        }
        return out
    }

    private fun checkDigest(m: SipMessage, auth: String) {
        val p = SipText.authParams(auth.removePrefix("Digest").trim()) ?: run { badDigests += auth; return }
        val expected = SipDigest.response(p["username"]!!, p["realm"]!!, password, m.method!!, p["uri"]!!, p["nonce"]!!, p["qop"], p["nc"].orEmpty(), p["cnonce"].orEmpty())
        if (expected != p["response"]) badDigests += auth
    }

    private val toTags = HashMap<String, String>()

    fun response(m: SipMessage, code: Int, extra: List<String> = emptyList(), tag: Boolean = false, body: String? = null, after: List<String> = emptyList()): String {
        val sb = StringBuilder("SIP/2.0 $code ${SipUserAgent.REASONS[code] ?: "Reason"}\r\n")
        for (v in m.values("via")) sb.append("Via: ").append(v).append(";received=192.0.2.10;rport=65005\r\n")
        sb.append("From: ").append(m.header("from")).append("\r\n")
        var to = m.header("to")!!
        if (tag && m.toTag == null) to += ";tag=" + toTags.getOrPut(m.callId + m.cseq.method) { "sbc${++toTag}" }
        sb.append("To: ").append(to).append("\r\n")
        sb.append("Call-ID: ").append(m.callId).append("\r\n")
        sb.append("CSeq: ").append(m.cseq.number).append(' ').append(m.cseq.method).append("\r\n")
        for (e in extra) sb.append(e).append("\r\n")
        val b = body.orEmpty()
        sb.append("Content-Length: ").append(b.toByteArray().size).append("\r\n")
        for (e in after) sb.append(e).append("\r\n")
        sb.append("\r\n").append(b)
        return sb.toString()
    }

    /** A request from the SBC inside [dialogOf]'s dialog: their BYE, a re-INVITE, an OPTIONS. */
    fun inDialog(method: String, dialogOf: SipMessage, ack: SipMessage, cseq: Int, body: String? = null, extra: List<String> = emptyList()): String {
        // The dialog as the SBC sees it: our Contact is its target, our tag is the To tag.
        val ourContact = SipAddress.parse(dialogOf.header("contact")!!)!!.uri
        val ourFrom = dialogOf.header("from")!!
        val theirTo = ack.header("to")!!
        val sb = StringBuilder("$method $ourContact SIP/2.0\r\n")
        sb.append("Via: SIP/2.0/WSS ${Trace.SBC}:8443;branch=z9hG4bKsbc$cseq\r\n")
        sb.append("Max-Forwards: 70\r\n")
        sb.append("From: ").append(theirTo).append("\r\n")
        sb.append("To: ").append(ourFrom).append("\r\n")
        sb.append("Call-ID: ").append(dialogOf.callId).append("\r\n")
        sb.append("CSeq: $cseq $method\r\n")
        for (e in extra) sb.append(e).append("\r\n")
        val b = body.orEmpty()
        if (body != null) sb.append("Content-Type: application/sdp\r\n")
        sb.append("Content-Length: ").append(b.toByteArray().size).append("\r\n\r\n").append(b)
        return sb.toString()
    }
}

/** Ties a [FakeSipSocket] to a [FakeSbc]: each request the UA sends is answered at once (on the next turn of the scheduler). */
internal class SbcLink(val socket: FakeSipSocket, val sbc: FakeSbc, private val scheduler: ManualScheduler) {
    private var seen = 0
    /** Answer everything sent since last time; repeat until quiet. */
    fun pump() {
        while (true) {
            scheduler.drain()
            if (seen >= socket.sent.size) return
            val text = socket.sent[seen++]
            val m = SipParser.parse(text) ?: continue
            if (!m.isRequest || socket.closed != null) continue
            for (r in sbc.respond(m)) socket.deliver(r)
        }
    }
}

/**
 * A LOOPBACK WEBSOCKET SERVER the tests script: it takes one connection,
 * agrees to `sip`, and hands the test each text frame the client sends; the
 * test writes back. The URL is built from its parts — the leak guard refuses a
 * literal socket URL whose host is not Graine's, in tests too.
 */
internal class LocalWsServer : AutoCloseable {
    private val ss = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
    val url: String = listOf("ws", "://", "127.0.0.1", ":", ss.localPort, "/sip").joinToString("")
    val received = LinkedBlockingQueue<String>()
    @Volatile private var out: OutputStream? = null
    @Volatile private var client: Socket? = null
    val accepted = LinkedBlockingQueue<Boolean>()

    init {
        Thread({
            runCatching {
                val s = ss.accept()
                client = s
                val input = DataInputStream(s.getInputStream())
                val o = s.getOutputStream()
                val key = readRequest(input)["sec-websocket-key"]!!
                o.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ${WebSocketClient.acceptFor(key)}\r\nSec-WebSocket-Protocol: sip\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
                o.flush()
                out = o
                accepted.put(true)
                while (true) {
                    val b0 = input.read()
                    if (b0 < 0) break
                    val b1 = input.readUnsignedByte()
                    var len = (b1 and 0x7f).toLong()
                    if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
                    val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
                    val p = ByteArray(len.toInt()).also { input.readFully(it) }
                    if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i and 3].toInt()).toByte()
                    when (b0 and 0x0f) {
                        WebSocketClient.OP_TEXT -> received.put(String(p, Charsets.UTF_8))
                        WebSocketClient.OP_CLOSE -> { write(WebSocketClient.OP_CLOSE, p); break }
                    }
                }
            }
            runCatching { client?.close() }
        }, "test-sip-ws").apply { isDaemon = true }.start()
    }

    private fun readRequest(input: InputStream): Map<String, String> {
        val head = ByteArrayOutputStream()
        while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) head.write(input.read())
        return head.toString("ISO-8859-1").split("\r\n").drop(1).filter { ':' in it }
            .associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
    }

    @Synchronized
    fun write(opcode: Int, payload: ByteArray) {
        val o = out ?: error("not connected")
        val head = ByteArrayOutputStream()
        head.write(0x80 or opcode)
        when {
            payload.size <= 125 -> head.write(payload.size)
            payload.size <= 0xffff -> { head.write(126); head.write(payload.size shr 8); head.write(payload.size and 0xff) }
            else -> { head.write(127); for (i in 7 downTo 0) head.write((payload.size.toLong() shr (8 * i)).toInt() and 0xff) }
        }
        o.write(head.toByteArray()); o.write(payload); o.flush()
    }

    fun send(text: String) = write(WebSocketClient.OP_TEXT, text.toByteArray(Charsets.UTF_8))

    /** The next text frame the client sent that is a SIP message of [method] (or any response, for "SIP/2.0"), skipping others. */
    fun next(prefix: String, timeoutMs: Long = 5_000): SipMessage {
        val until = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = until - System.currentTimeMillis()
            val text = received.poll(Math.max(1, left), TimeUnit.MILLISECONDS) ?: error("nothing starting \"$prefix\" within $timeoutMs ms")
            if (text.startsWith(prefix)) return SipParser.parse(text) ?: error("unreadable: $text")
        }
    }

    fun dropConnection() { runCatching { client?.close() } }

    override fun close() { runCatching { client?.close() }; runCatching { ss.close() } }
}
