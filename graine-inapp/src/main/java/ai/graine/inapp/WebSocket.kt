package ai.graine.inapp

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** The socket a native call speaks over — the real client below, or a fake in tests. */
internal interface CallSocket {
    /** Queue one text frame. False once the socket is not open. Never call on the main thread. */
    fun send(text: String): Boolean
    /** Begin the close handshake (or abort a connect). Exactly one terminal callback follows. */
    fun close(code: Int = 1000, reason: String = "")
}

/** What a socket reports, on its own reader thread. Exactly one of onClosed / onFailure, once. */
internal interface CallSocketListener {
    fun onOpen(protocol: String?)
    fun onText(text: String)
    /** A close handshake: the server's code and reason, or this side's when it closed first. */
    fun onClosed(code: Int, reason: String)
    /** The connection broke, or never opened. */
    fun onFailure(error: Throwable)
}

/**
 * A WEBSOCKET CLIENT ON PLATFORM APIs ONLY — RFC 6455, the parts a call needs.
 *
 * The SDK takes no dependencies, so no OkHttp. This is the subset a voice
 * socket uses: the HTTP/1.1 upgrade (key, accept, subprotocols), masked client
 * frames, text messages fragmented or not, ping → pong, and the close
 * handshake, over `SSLSocketFactory.getDefault()` with the platform's hostname
 * verifier. One reader thread per socket; sends are serialised.
 *
 * TLS: the TCP connection is made first with a deadline, then layered with
 * `createSocket(socket, host, port, true)` — the host given there is what SNI
 * and certificate checks use, as with `createSocket(host, port)`, which has no
 * connect timeout. The hostname is then verified with
 * `HttpsURLConnection.getDefaultHostnameVerifier()` (and by the TLS stack too,
 * via the HTTPS endpoint-identification algorithm). Plain `ws` exists for the
 * JVM tests' local server.
 */
internal class WebSocketClient(
    private val url: String,
    private val protocols: List<String>,
    private val listener: CallSocketListener,
    private val openTimeoutMs: Long = CallFrames.OPEN_TIMEOUT_MS,
    private val headers: Map<String, String> = emptyMap(),
    private val sslSocketFactory: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
    private val hostnameVerifier: () -> HostnameVerifier = { HttpsURLConnection.getDefaultHostnameVerifier() },
) : CallSocket {

    private enum class State { CONNECTING, OPEN, CLOSING, CLOSED }

    @Volatile private var state = State.CONNECTING
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    private val writeLock = Any()
    private val terminal = AtomicBoolean(false)
    private val random = SecureRandom()
    @Volatile private var closeCode = 1000
    @Volatile private var closeReason = ""

    /** Connect on the socket's own thread; [CallSocketListener.onOpen] when the upgrade succeeds. */
    fun connect(): WebSocketClient {
        Thread({ run() }, "graine-ws").apply { isDaemon = true }.start()
        return this
    }

    override fun send(text: String): Boolean {
        if (state != State.OPEN) return false
        return try {
            writeFrame(OP_TEXT, text.toByteArray(Charsets.UTF_8))
            true
        } catch (e: Exception) {
            // The reader sees the dead socket and reports it; this send just did not happen.
            closeSocket()
            false
        }
    }

    override fun close(code: Int, reason: String) {
        when (state) {
            State.CONNECTING -> {
                state = State.CLOSED
                closeCode = code; closeReason = reason
                closeSocket()
                // A connect still in flight sees the closed socket and fails quietly; the caller hears this.
                deliverClosed(code, reason)
            }
            State.OPEN -> {
                state = State.CLOSING
                closeCode = code; closeReason = reason
                try { writeFrame(OP_CLOSE, closePayload(code, reason)) } catch (_: Exception) { closeSocket(); return }
                // The server should echo the close; if it never does, stop waiting.
                Thread({
                    try { Thread.sleep(CLOSE_WAIT_MS) } catch (_: InterruptedException) {}
                    closeSocket()
                }, "graine-ws-close").apply { isDaemon = true }.start()
            }
            else -> Unit
        }
    }

    // ── the reader thread ───────────────────────────────────────────────────

    private fun run() {
        try {
            val target = Target.parse(url)
            val deadline = System.nanoTime() + openTimeoutMs * 1_000_000
            val s = openSocket(target, deadline)
            if (state != State.CONNECTING) { closeQuietly(s); return }
            val input = BufferedInputStream(s.getInputStream(), 16 * 1024)
            val out = s.getOutputStream()
            val key = B64.encode(ByteArray(16).also { random.nextBytes(it) })
            out.write(upgradeRequest(target, key, protocols, headers).toByteArray(Charsets.ISO_8859_1))
            out.flush()
            val response = readResponseHead(input)
            val protocol = verifyUpgrade(response, key, protocols)
            s.soTimeout = 0
            synchronized(writeLock) { output = out }
            if (state != State.CONNECTING) { closeQuietly(s); return }
            state = State.OPEN
            listener.onOpen(protocol)
            readLoop(DataInputStream(input))
        } catch (e: Throwable) {
            if (state == State.CLOSING || state == State.CLOSED) deliverClosed(closeCode, closeReason)
            else { state = State.CLOSED; closeSocket(); deliverFailure(e) }
        } finally {
            closeSocket()
        }
    }

    private fun remaining(deadline: Long): Int {
        val ms = (deadline - System.nanoTime()) / 1_000_000
        if (ms <= 0) throw java.net.SocketTimeoutException("The socket did not open within ${openTimeoutMs / 1000} s")
        return ms.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun openSocket(t: Target, deadline: Long): Socket {
        val plain = Socket()
        socket = plain
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(t.host, t.port), remaining(deadline))
        if (!t.secure) {
            plain.soTimeout = remaining(deadline)
            return plain
        }
        val ssl = sslSocketFactory().createSocket(plain, t.host, t.port, true) as SSLSocket
        socket = ssl
        runCatching {
            val p = ssl.sslParameters
            p.endpointIdentificationAlgorithm = "HTTPS"
            ssl.sslParameters = p
        }
        ssl.soTimeout = remaining(deadline)
        ssl.startHandshake()
        if (!hostnameVerifier().verify(t.host, ssl.session)) {
            throw SSLPeerUnverifiedException("The certificate does not match ${t.host}")
        }
        return ssl
    }

    private fun readLoop(input: DataInputStream) {
        var message: ByteArrayOutputStream? = null
        var messageOp = 0
        while (true) {
            val b0 = input.read()
            if (b0 < 0) throw EOFException("The connection closed without a close frame")
            val b1 = input.readUnsignedByte()
            val fin = b0 and 0x80 != 0
            val opcode = b0 and 0x0f
            if (b0 and 0x70 != 0) protocolError("reserved bits set")
            var length = (b1 and 0x7f).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong()
            else if (length == 127L) length = input.readLong()
            if (length < 0 || length > MAX_MESSAGE_BYTES) protocolError("a frame of $length bytes")
            // A server must not mask; one that does is unmasked rather than refused.
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
            val payload = ByteArray(length.toInt())
            input.readFully(payload)
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()

            when (opcode) {
                OP_CONTINUATION -> {
                    val m = message ?: protocolError("a continuation with no message")
                    if (m.size() + payload.size > MAX_MESSAGE_BYTES) protocolError("a message over ${MAX_MESSAGE_BYTES} bytes")
                    m.write(payload)
                    if (fin) { message = null; deliver(messageOp, m.toByteArray()) }
                }
                OP_TEXT, OP_BINARY -> {
                    if (message != null) protocolError("a new message inside a fragmented one")
                    if (fin) deliver(opcode, payload)
                    else { message = ByteArrayOutputStream().also { it.write(payload) }; messageOp = opcode }
                }
                OP_CLOSE -> {
                    val code = if (payload.size >= 2) ((payload[0].toInt() and 0xff) shl 8) or (payload[1].toInt() and 0xff) else 1005
                    val reason = if (payload.size > 2) String(payload, 2, payload.size - 2, Charsets.UTF_8) else ""
                    if (state == State.OPEN) {
                        // The server closed first: echo it, as the handshake asks.
                        state = State.CLOSING
                        runCatching { writeFrame(OP_CLOSE, closePayload(if (code == 1005) 1000 else code, "")) }
                    }
                    state = State.CLOSED
                    closeSocket()
                    deliverClosed(code, reason)
                    return
                }
                OP_PING -> {
                    if (!fin || payload.size > 125) protocolError("a malformed ping")
                    runCatching { writeFrame(OP_PONG, payload) }
                }
                OP_PONG -> Unit
                else -> protocolError("opcode $opcode")
            }
        }
    }

    private fun deliver(opcode: Int, payload: ByteArray) {
        if (opcode == OP_TEXT && state == State.OPEN) listener.onText(String(payload, Charsets.UTF_8))
    }

    private fun protocolError(what: String): Nothing {
        runCatching { writeFrame(OP_CLOSE, closePayload(1002, "")) }
        throw IOException("WebSocket protocol error: $what")
    }

    private fun writeFrame(opcode: Int, payload: ByteArray) {
        val frame = encodeFrame(opcode, payload, ByteArray(4).also { random.nextBytes(it) })
        synchronized(writeLock) {
            val out = output ?: throw IOException("not open")
            out.write(frame)
            out.flush()
        }
    }

    private fun deliverClosed(code: Int, reason: String) {
        if (terminal.compareAndSet(false, true)) listener.onClosed(code, reason)
    }

    private fun deliverFailure(e: Throwable) {
        if (terminal.compareAndSet(false, true)) listener.onFailure(e)
    }

    private fun closeSocket() {
        socket?.let { closeQuietly(it) }
    }

    private fun closeQuietly(s: Socket) {
        try { s.close() } catch (_: IOException) {}
    }

    /** Where to connect, from a ws/wss URL. */
    internal data class Target(val secure: Boolean, val host: String, val port: Int, val resource: String, val hostHeader: String) {
        companion object {
            fun parse(url: String): Target {
                val u = URI(url)
                val secure = when (u.scheme?.lowercase()) {
                    "wss" -> true
                    "ws" -> false
                    else -> throw IllegalArgumentException("Not a WebSocket URL: ${u.scheme}")
                }
                val rawHost = u.host ?: throw IllegalArgumentException("A WebSocket URL needs a host")
                val host = rawHost.removePrefix("[").removeSuffix("]")
                val defaultPort = if (secure) 443 else 80
                val port = if (u.port > 0) u.port else defaultPort
                val path = u.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
                val resource = if (u.rawQuery != null) "$path?${u.rawQuery}" else path
                val hostHeader = if (port == defaultPort) rawHost else "$rawHost:$port"
                return Target(secure, host, port, resource, hostHeader)
            }
        }
    }

    companion object {
        const val OP_CONTINUATION = 0x0
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
        /** A voice frame is ~13 KB; nothing the agent sends comes near this. */
        const val MAX_MESSAGE_BYTES = 8L * 1024 * 1024
        const val CLOSE_WAIT_MS = 1_000L
        private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

        /** One client frame: FIN set, masked as RFC 6455 requires of a client. */
        fun encodeFrame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
            val n = payload.size
            val header = when {
                n <= 125 -> 2
                n <= 0xffff -> 4
                else -> 10
            }
            val out = ByteArray(header + 4 + n)
            out[0] = (0x80 or (opcode and 0x0f)).toByte()
            when (header) {
                2 -> out[1] = (0x80 or n).toByte()
                4 -> { out[1] = (0x80 or 126).toByte(); out[2] = (n shr 8).toByte(); out[3] = n.toByte() }
                else -> {
                    out[1] = (0x80 or 127).toByte()
                    val len = n.toLong()
                    for (i in 0 until 8) out[2 + i] = (len shr (56 - 8 * i)).toByte()
                }
            }
            System.arraycopy(mask, 0, out, header, 4)
            for (i in 0 until n) out[header + 4 + i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
            return out
        }

        fun closePayload(code: Int, reason: String): ByteArray {
            val r = reason.toByteArray(Charsets.UTF_8).let { if (it.size > 123) it.copyOf(123) else it }
            return byteArrayOf((code shr 8).toByte(), code.toByte()) + r
        }

        /** Sec-WebSocket-Accept for a key: base64(SHA-1(key + the RFC's GUID)). */
        fun acceptFor(key: String): String =
            B64.encode(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.ISO_8859_1)))

        fun upgradeRequest(t: Target, key: String, protocols: List<String>, headers: Map<String, String>): String {
            val sb = StringBuilder()
            sb.append("GET ").append(t.resource).append(" HTTP/1.1\r\n")
            sb.append("Host: ").append(t.hostHeader).append("\r\n")
            sb.append("Upgrade: websocket\r\n")
            sb.append("Connection: Upgrade\r\n")
            sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n")
            sb.append("Sec-WebSocket-Version: 13\r\n")
            if (protocols.isNotEmpty()) sb.append("Sec-WebSocket-Protocol: ").append(protocols.joinToString(", ")).append("\r\n")
            for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
            sb.append("\r\n")
            return sb.toString()
        }

        /** The status line and headers, up to the blank line — and not one byte of the first frame. */
        fun readResponseHead(input: InputStream): Response {
            val head = ByteArrayOutputStream()
            var matched = 0
            while (matched < 4) {
                val b = input.read()
                if (b < 0) throw EOFException("The server closed the connection during the upgrade")
                head.write(b)
                if (head.size() > 16 * 1024) throw IOException("The upgrade response is too large")
                matched = when {
                    b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                    b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                    b == '\r'.code -> 1
                    else -> 0
                }
            }
            val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n")
            val status = lines.firstOrNull().orEmpty()
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Not an HTTP response: $status")
            val fields = LinkedHashMap<String, String>()
            for (line in lines.drop(1)) {
                val i = line.indexOf(':')
                if (i <= 0) continue
                val name = line.substring(0, i).trim().lowercase()
                val value = line.substring(i + 1).trim()
                fields[name] = fields[name]?.let { "$it, $value" } ?: value
            }
            return Response(code, status, fields)
        }

        /** Checks the server agreed to this upgrade; returns the subprotocol it chose, if any. */
        fun verifyUpgrade(r: Response, key: String, protocols: List<String>): String? {
            if (r.code != 101) throw IOException("The server refused the WebSocket upgrade: HTTP ${r.code}")
            if (!r.header("upgrade").equals("websocket", ignoreCase = true)) throw IOException("The server did not upgrade to websocket")
            if (r.header("connection").split(',').none { it.trim().equals("upgrade", ignoreCase = true) }) {
                throw IOException("The server did not upgrade the connection")
            }
            if (r.header("sec-websocket-accept") != acceptFor(key)) throw IOException("The server's Sec-WebSocket-Accept does not match")
            val chosen = r.headers["sec-websocket-protocol"]?.takeIf { it.isNotEmpty() } ?: return null
            if (chosen !in protocols) throw IOException("The server chose a subprotocol that was not offered")
            return chosen
        }
    }

    internal data class Response(val code: Int, val status: String, val headers: Map<String, String>) {
        fun header(name: String): String = headers[name].orEmpty()
    }
}
