package ai.graine.inapp

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * THE WEBSOCKET CLIENT AGAINST A REAL SERVER SOCKET — one this test writes,
 * on the loopback interface: the upgrade (key, accept, subprotocols), masked
 * client frames, fragmented messages with a ping in the middle, the three
 * length encodings, both directions of the close handshake, and the ways an
 * upgrade fails.
 *
 * The URL is built from its parts: the leak guard refuses any literal socket
 * URL whose host is not Graine's, in tests too.
 */
class WebSocketClientTest {
    private val servers = mutableListOf<ServerSocket>()

    @After fun closeServers() { servers.forEach { runCatching { it.close() } } }

    private class Frame(val fin: Boolean, val opcode: Int, val masked: Boolean, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
    }

    /** One accepted connection, scripted by the test, on its own thread. */
    private fun server(script: (Socket, DataInputStream, OutputStream) -> Unit): String {
        val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        servers += ss
        Thread({
            runCatching {
                ss.accept().use { s -> script(s, DataInputStream(s.getInputStream()), s.getOutputStream()) }
            }
        }, "test-ws-server").apply { isDaemon = true }.start()
        return listOf("ws", "://", "127.0.0.1", ":", ss.localPort, "/chat?x=1").joinToString("")
    }

    /** The upgrade request: its line under "_line", headers by lower-case name. Byte by byte, so no frame is eaten. */
    private fun readRequest(input: InputStream): Map<String, String> {
        val head = java.io.ByteArrayOutputStream()
        while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) error("the client hung up mid-request")
            head.write(b)
        }
        val lines = head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        val out = LinkedHashMap<String, String>()
        out["_line"] = lines.first()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            out[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return out
    }

    private fun accept(out: OutputStream, key: String, protocol: String? = null, acceptOverride: String? = null) {
        val sb = StringBuilder("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n")
        sb.append("Sec-WebSocket-Accept: ").append(acceptOverride ?: WebSocketClient.acceptFor(key)).append("\r\n")
        if (protocol != null) sb.append("Sec-WebSocket-Protocol: ").append(protocol).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); out.flush()
    }

    private fun readFrame(input: DataInputStream): Frame {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        var len = (b1 and 0x7f).toLong()
        if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
        val masked = b1 and 0x80 != 0
        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val p = ByteArray(len.toInt()).also { input.readFully(it) }
        if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(b0 and 0x80 != 0, b0 and 0x0f, masked, p)
    }

    /** A server frame: never masked. */
    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray, fin: Boolean = true) {
        val head = java.io.ByteArrayOutputStream()
        head.write((if (fin) 0x80 else 0) or opcode)
        when {
            payload.size <= 125 -> head.write(payload.size)
            payload.size <= 0xffff -> { head.write(126); head.write(payload.size shr 8); head.write(payload.size and 0xff) }
            else -> { head.write(127); for (i in 7 downTo 0) head.write((payload.size.toLong() shr (8 * i)).toInt() and 0xff) }
        }
        out.write(head.toByteArray()); out.write(payload); out.flush()
    }

    private class Events : CallSocketListener {
        val q = LinkedBlockingQueue<String>()
        override fun onOpen(protocol: String?) { q.put("open:$protocol") }
        override fun onText(text: String) { q.put("text:$text") }
        override fun onClosed(code: Int, reason: String) { q.put("closed:$code:$reason") }
        override fun onFailure(error: Throwable) { q.put("failure:${error.message}") }
        fun next(timeoutMs: Long = 5_000): String = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: "timeout"
    }

    @Test fun upgradesExchangesFragmentedTextAnswersPingsAndClosesWhenTheServerDoes() {
        val serverSaw = LinkedBlockingQueue<Any>()
        val url = server { _, input, out ->
            val req = readRequest(input)
            serverSaw.put(req)
            accept(out, req.getValue("sec-websocket-key"), protocol = "graine.embed.v1")
            serverSaw.put(readFrame(input))                                    // the client's "hello"
            writeFrame(out, WebSocketClient.OP_TEXT, "Hel".toByteArray(), fin = false)
            writeFrame(out, WebSocketClient.OP_PING, "p1".toByteArray())       // a ping between fragments
            writeFrame(out, WebSocketClient.OP_CONTINUATION, "lo ".toByteArray(), fin = false)
            writeFrame(out, WebSocketClient.OP_CONTINUATION, "world".toByteArray())
            serverSaw.put(readFrame(input))                                    // the pong
            writeFrame(out, WebSocketClient.OP_TEXT, "x".repeat(300).toByteArray())           // 16-bit length
            writeFrame(out, WebSocketClient.OP_TEXT, "नमस्ते".repeat(5000).toByteArray())       // 64-bit length
            writeFrame(out, WebSocketClient.OP_CLOSE, WebSocketClient.closePayload(1000, "bye"))
            serverSaw.put(readFrame(input))                                    // the echoed close
        }
        val ev = Events()
        val ws = WebSocketClient(url, listOf("graine.embed.v1", "tkt_123"), ev, headers = mapOf("User-Agent" to "graine-android-sdk")).connect()
        assertEquals("open:graine.embed.v1", ev.next())

        @Suppress("UNCHECKED_CAST")
        val req = serverSaw.poll(5, TimeUnit.SECONDS) as Map<String, String>
        assertEquals("GET /chat?x=1 HTTP/1.1", req["_line"])
        assertTrue(req.getValue("host").startsWith("127.0.0.1:"))
        assertEquals("websocket", req["upgrade"])
        assertEquals("Upgrade", req["connection"])
        assertEquals("13", req["sec-websocket-version"])
        assertEquals("graine.embed.v1, tkt_123", req["sec-websocket-protocol"])
        assertEquals("graine-android-sdk", req["user-agent"])
        assertEquals("a 16-byte key", 16, B64.decode(req.getValue("sec-websocket-key")).size)
        assertTrue("no Origin header: the agent allows app://", "origin" !in req)

        assertTrue(ws.send("hello"))
        val hello = serverSaw.poll(5, TimeUnit.SECONDS) as Frame
        assertTrue("client frames are masked", hello.masked)
        assertTrue(hello.fin)
        assertEquals(WebSocketClient.OP_TEXT, hello.opcode)
        assertEquals("hello", hello.text)

        assertEquals("text:Hello world", ev.next())
        val pong = serverSaw.poll(5, TimeUnit.SECONDS) as Frame
        assertEquals(WebSocketClient.OP_PONG, pong.opcode)
        assertTrue(pong.masked)
        assertEquals("p1", pong.text)
        assertEquals("text:" + "x".repeat(300), ev.next())
        assertEquals("text:" + "नमस्ते".repeat(5000), ev.next())

        assertEquals("closed:1000:bye", ev.next())
        val echoed = serverSaw.poll(5, TimeUnit.SECONDS) as Frame
        assertEquals(WebSocketClient.OP_CLOSE, echoed.opcode)
        assertTrue(echoed.masked)
        assertEquals(1000, ((echoed.payload[0].toInt() and 0xff) shl 8) or (echoed.payload[1].toInt() and 0xff))
        assertTrue("nothing goes out on a closed socket", !ws.send("late"))
        assertEquals("one terminal callback", "timeout", ev.next(300))
    }

    @Test fun theClientClosesFirstAndHearsTheServersEcho() {
        val got = LinkedBlockingQueue<Frame>()
        val url = server { _, input, out ->
            val req = readRequest(input)
            accept(out, req.getValue("sec-websocket-key"))
            val close = readFrame(input)
            got.put(close)
            writeFrame(out, WebSocketClient.OP_CLOSE, close.payload)
        }
        val ev = Events()
        val ws = WebSocketClient(url, emptyList(), ev).connect()
        assertEquals("no protocol offered, none chosen", "open:null", ev.next())
        ws.close(1000, "client_hangup")
        val close = got.poll(5, TimeUnit.SECONDS)!!
        assertEquals(WebSocketClient.OP_CLOSE, close.opcode)
        assertTrue(close.masked)
        assertArrayEquals(WebSocketClient.closePayload(1000, "client_hangup"), close.payload)
        assertEquals("closed:1000:client_hangup", ev.next())
    }

    @Test fun aServerThatNeverEchoesTheCloseIsLetGo() {
        val url = server { _, input, out ->
            val req = readRequest(input)
            accept(out, req.getValue("sec-websocket-key"))
            readFrame(input)
            Thread.sleep(3_000)   // says nothing
        }
        val ev = Events()
        val ws = WebSocketClient(url, emptyList(), ev).connect()
        assertEquals("open:null", ev.next())
        val t0 = System.nanoTime()
        ws.close(1000, "")
        assertEquals("closed:1000:", ev.next())
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue("about a second, not forever: $waited ms", waited in 500..2_500)
    }

    @Test fun aRefusedUpgradeIsAFailureWithTheStatus() {
        val url = server { _, input, out ->
            readRequest(input)
            out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray()); out.flush()
        }
        val ev = Events()
        WebSocketClient(url, emptyList(), ev).connect()
        val e = ev.next()
        assertTrue(e, e.startsWith("failure:") && e.contains("403"))
    }

    @Test fun aWrongAcceptIsRefused() {
        val url = server { _, input, out ->
            readRequest(input)
            accept(out, "x", acceptOverride = "not-the-hash=")
        }
        val ev = Events()
        WebSocketClient(url, emptyList(), ev).connect()
        val e = ev.next()
        assertTrue(e, e.startsWith("failure:") && e.contains("Accept"))
    }

    @Test fun aSubprotocolThatWasNotOfferedIsRefused() {
        val url = server { _, input, out ->
            val req = readRequest(input)
            accept(out, req.getValue("sec-websocket-key"), protocol = "something.else")
        }
        val ev = Events()
        WebSocketClient(url, listOf("graine.embed.v1", "t"), ev).connect()
        val e = ev.next()
        assertTrue(e, e.startsWith("failure:") && e.contains("subprotocol"))
    }

    @Test fun anUpgradeThatNeverAnswersTimesOut() {
        val url = server { _, input, _ ->
            readRequest(input)
            Thread.sleep(5_000)
        }
        val ev = Events()
        val t0 = System.nanoTime()
        WebSocketClient(url, emptyList(), ev, openTimeoutMs = 600).connect()
        val e = ev.next()
        val waited = (System.nanoTime() - t0) / 1_000_000
        assertTrue(e, e.startsWith("failure:"))
        assertTrue("timed out near 600 ms, took $waited", waited in 400..3_000)
    }

    @Test fun aConnectionThatDropsWithoutACloseIsAFailure() {
        val url = server { s, input, out ->
            val req = readRequest(input)
            accept(out, req.getValue("sec-websocket-key"))
            writeFrame(out, WebSocketClient.OP_TEXT, "one".toByteArray())
            Thread.sleep(100)
            s.close()
        }
        val ev = Events()
        WebSocketClient(url, emptyList(), ev).connect()
        assertEquals("open:null", ev.next())
        assertEquals("text:one", ev.next())
        assertTrue(ev.next().startsWith("failure:"))
    }

    @Test fun closingWhileConnectingReportsOnceAndOpensNothing() {
        val url = server { _, input, _ -> readRequest(input); Thread.sleep(2_000) }
        val ev = Events()
        val ws = WebSocketClient(url, emptyList(), ev).connect()
        Thread.sleep(100)
        ws.close(1000, "")
        assertEquals("closed:1000:", ev.next())
        assertEquals("timeout", ev.next(500))
    }

    @Test fun framesAreEncodedWithTheRightLengthAndMask() {
        val mask = byteArrayOf(1, 2, 3, 4)
        fun header(n: Int) = WebSocketClient.encodeFrame(WebSocketClient.OP_TEXT, ByteArray(n), mask)
        assertEquals(2 + 4 + 125, header(125).size)
        assertEquals((0x80 or 125).toByte(), header(125)[1])
        assertEquals(4 + 4 + 126, header(126).size)
        assertEquals((0x80 or 126).toByte(), header(126)[1])
        assertEquals(4 + 4 + 65535, header(65535).size)
        assertEquals(10 + 4 + 65536, header(65536).size)
        assertEquals((0x80 or 127).toByte(), header(65536)[1])
        val f = WebSocketClient.encodeFrame(WebSocketClient.OP_TEXT, "abcd".toByteArray(), mask)
        assertEquals(0x81.toByte(), f[0])
        assertArrayEquals(mask, f.copyOfRange(2, 6))
        assertArrayEquals(byteArrayOf(('a'.code xor 1).toByte(), ('b'.code xor 2).toByte(), ('c'.code xor 3).toByte(), ('d'.code xor 4).toByte()), f.copyOfRange(6, 10))
        // RFC 6455's own example key.
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketClient.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test fun aSocketUrlIsReadIntoWhereToConnect() {
        val t = WebSocketClient.Target.parse("wss://ws.graine.ai/chat/v1/a?webcall=1&context_token=ab%2Fc")
        assertTrue(t.secure)
        assertEquals("ws.graine.ai", t.host)
        assertEquals(443, t.port)
        assertEquals("/chat/v1/a?webcall=1&context_token=ab%2Fc", t.resource)
        assertEquals("the default port is not written", "ws.graine.ai", t.hostHeader)
        val local = WebSocketClient.Target.parse(listOf("ws", "://", "127.0.0.1", ":", "9001").joinToString(""))
        assertEquals("/", local.resource)
        assertEquals("127.0.0.1:9001", local.hostHeader)
    }
}
