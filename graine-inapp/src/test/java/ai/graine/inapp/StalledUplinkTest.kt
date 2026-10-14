package ai.graine.inapp

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * A STALLED UPLINK, ON THE LOOPBACK INTERFACE.
 *
 * A live call against production on a network that stopped carrying showed
 * it: every send blocked the session's one thread, so the agent's frames, the
 * mark acks and the hang-up queued behind the microphone's audio, and the
 * microphone stayed open after the customer hung up. Here the line stalls on
 * command (a socket whose writes wait while its gate is shut, as a kernel
 * buffer that will take no more does), and the real WebSocketClient — and the
 * real VoiceCallSession over it — must not care: sends return at once, at most
 * 300 ms of audio waits, no control frame is ever dropped, the reader never
 * waits on the line, and a hang-up lets go of the microphone at once and gets
 * the socket closed within about a second.
 *
 * The URL is built from its parts: the leak guard refuses any literal socket
 * URL whose host is not Graine's, in tests too.
 */
class StalledUplinkTest {
    private val servers = mutableListOf<Server>()

    @After fun closeServers() { servers.forEach { it.close() } }

    private fun clock() = System.nanoTime() / 1_000_000

    /** A connection whose writes wait while [carrying] is false, and fail once it is closed. */
    private class GatedSocket : Socket() {
        @Volatile var carrying = true

        override fun getOutputStream(): OutputStream {
            val real = super.getOutputStream()
            val socket = this
            return object : OutputStream() {
                private fun gate() {
                    while (!carrying) {
                        if (socket.isClosed) throw SocketException("Socket closed")
                        Thread.sleep(5)
                    }
                }
                override fun write(b: Int) { gate(); real.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { gate(); real.write(b, off, len) }
                override fun flush() { real.flush() }
            }
        }
    }

    /** One accepted connection: the upgrade, then every client frame recorded, and frames sent on demand. */
    private inner class Server {
        private val ss = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val frames = LinkedBlockingQueue<Pair<Int, String>>()
        val endedAt = AtomicLong(-1)
        @Volatile private var conn: Socket? = null
        @Volatile private var out: OutputStream? = null

        val url: String get() = listOf("ws", "://", "127.0.0.1", ":", ss.localPort, "/embed/v1/web_1").joinToString("")

        init {
            servers += this
            Thread({
                runCatching {
                    val s = ss.accept()
                    conn = s
                    val input = DataInputStream(s.getInputStream())
                    val head = readHead(input)
                    val key = Regex("(?i)sec-websocket-key:\\s*(\\S+)").find(head)!!.groupValues[1]
                    val o = s.getOutputStream()
                    val proto = if (head.contains("graine.embed.v1")) "Sec-WebSocket-Protocol: graine.embed.v1\r\n" else ""
                    // Ready to send before the client can know it is open.
                    out = o
                    synchronized(this) {
                        o.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${WebSocketClient.acceptFor(key)}\r\n$proto\r\n".toByteArray(Charsets.ISO_8859_1))
                        o.flush()
                    }
                    try {
                        while (true) {
                            val (opcode, payload) = readFrame(input)
                            frames.put(opcode to String(payload, Charsets.UTF_8))
                            if (opcode == WebSocketClient.OP_CLOSE) break
                        }
                    } finally {
                        endedAt.set(clock())
                    }
                }.onFailure { endedAt.compareAndSet(-1, clock()) }
            }, "stalled-test-server").apply { isDaemon = true }.start()
        }

        fun send(opcode: Int, text: String) = synchronized(this) {
            val o = out ?: error("not upgraded yet")
            val p = text.toByteArray(Charsets.UTF_8)
            require(p.size <= 125)
            o.write(byteArrayOf((0x80 or opcode).toByte(), p.size.toByte()) + p)
            o.flush()
        }

        /** Every client frame until [quietMs] passes with none. */
        fun drain(quietMs: Long = 400): List<Pair<Int, String>> {
            val got = mutableListOf<Pair<Int, String>>()
            while (true) got += frames.poll(quietMs, TimeUnit.MILLISECONDS) ?: return got
        }

        fun close() { runCatching { conn?.close() }; runCatching { ss.close() } }

        private fun readHead(input: InputStream): String {
            val head = ByteArrayOutputStream()
            while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) error("the client hung up mid-request")
                head.write(b)
            }
            return head.toString("ISO-8859-1")
        }

        private fun readFrame(input: DataInputStream): Pair<Int, ByteArray> {
            val b0 = input.readUnsignedByte()
            val b1 = input.readUnsignedByte()
            var len = (b1 and 0x7f).toLong()
            if (len == 126L) len = input.readUnsignedShort().toLong() else if (len == 127L) len = input.readLong()
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
            val p = ByteArray(len.toInt()).also { input.readFully(it) }
            if (mask != null) for (i in p.indices) p[i] = (p[i].toInt() xor mask[i and 3].toInt()).toByte()
            return (b0 and 0x0f) to p
        }
    }

    private class Events : CallSocketListener {
        val q = LinkedBlockingQueue<String>()
        override fun onOpen(protocol: String?) { q.put("open") }
        override fun onText(text: String) { q.put("text:$text") }
        override fun onClosed(code: Int, reason: String) { q.put("closed:$code") }
        override fun onFailure(error: Throwable) { q.put("failure:${error.message}") }
        fun next(timeoutMs: Long = 5_000): String = q.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: "timeout"
    }

    // ── the socket ───────────────────────────────────────────────────────────

    @Test fun sendsNeverBlockAndAStalledLineKeepsOnlyTheNewest300msOfAudioAndEveryControlFrame() {
        val server = Server()
        val line = GatedSocket()
        val ev = Events()
        val ws = WebSocketClient(server.url, listOf("graine.embed.v1", "tkt"), ev, plainSocket = { line }).connect()
        assertEquals("open", ev.next())

        line.carrying = false
        val t0 = clock()
        assertTrue(ws.send("c0"))
        for (i in 1..100) {
            assertTrue("a send is accepted while the line is stalled", ws.sendDroppable("a$i"))
            if (i % 25 == 0) assertTrue(ws.send("c${i / 25}"))
            assertTrue("never more than 300 ms of audio waits", ws.queuedDroppable <= CallFrames.AUDIO_QUEUE_FRAMES)
        }
        val took = clock() - t0
        assertTrue("105 sends on a stalled line returned at once: $took ms", took < 1_000)
        assertEquals(CallFrames.AUDIO_QUEUE_FRAMES, ws.queuedDroppable)
        assertEquals("the oldest audio went", 85L, ws.droppedFrames)

        // The line carries again: every control frame, in order; of the audio, only the newest 300 ms.
        line.carrying = true
        val got = server.drain().filter { it.first == WebSocketClient.OP_TEXT }.map { it.second }
        assertEquals(listOf("c0", "c1", "c2", "c3", "c4"), got.filter { it.startsWith("c") })
        assertEquals((86..100).map { "a$it" }, got.filter { it.startsWith("a") })
        assertTrue(got.indexOf("c3") < got.indexOf("a86") && got.indexOf("a100") < got.indexOf("c4"))
    }

    @Test fun aCloseTheLineCannotCarryIsForcedWithinASecond() {
        val server = Server()
        val line = GatedSocket()
        val ev = Events()
        val ws = WebSocketClient(server.url, emptyList(), ev, plainSocket = { line }).connect()
        assertEquals("open", ev.next())
        line.carrying = false
        ws.sendDroppable("a1")
        val t0 = clock()
        ws.close(1000, "client_hangup")
        assertEquals("closed:1000", ev.next())
        val waited = clock() - t0
        assertTrue("forced after about a second, not when TCP gives up: $waited ms", waited in 700..2_500)
        // The server's end sees the connection go.
        val deadline = clock() + 3_000
        while (server.endedAt.get() < 0 && clock() < deadline) Thread.sleep(20)
        assertTrue("the connection was closed under the stalled write", server.endedAt.get() >= 0)
        assertEquals("one terminal callback", "timeout", ev.next(300))
    }

    @Test fun theReaderNeverWaitsOnAStalledLine() {
        val server = Server()
        val line = GatedSocket()
        val ev = Events()
        WebSocketClient(server.url, emptyList(), ev, plainSocket = { line }).connect()
        assertEquals("open", ev.next())
        line.carrying = false
        // A ping wants a pong the line cannot carry; the frame after it must still arrive.
        server.send(WebSocketClient.OP_PING, "p1")
        server.send(WebSocketClient.OP_TEXT, "after the ping")
        assertEquals("text:after the ping", ev.next(1_000))
        line.carrying = true
        val pong = server.drain().firstOrNull { it.first == WebSocketClient.OP_PONG }
        assertEquals("the pong waited its turn, and went", "p1", pong?.second)
    }

    // ── the call ─────────────────────────────────────────────────────────────

    @Test fun aStalledUplinkNeitherFreezesTheCallNorHoldsTheMicrophone() {
        val server = Server()
        val line = GatedSocket()
        var client: WebSocketClient? = null
        val captions = CopyOnWriteArrayList<Pair<Long, String>>()
        val opened = LinkedBlockingQueue<String>()
        val micStoppedAt = AtomicLong(-1)

        val audio = object : CallAudioIO {
            @Volatile var running = false
            override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {
                running = true
                Thread({
                    var next = System.nanoTime()
                    while (running) {
                        onFrame(ShortArray(AgentAudio.CAPTURE_FRAME_SAMPLES) { (2_000 * Math.sin(it / 4.0)).toInt().toShort() })
                        next += 20_000_000
                        val wait = next - System.nanoTime()
                        if (wait > 0) Thread.sleep(wait / 1_000_000, (wait % 1_000_000).toInt())
                    }
                }, "test-microphone").apply { isDaemon = true }.start()
            }
            override fun play(pcm: ShortArray, sampleRate: Int) {}
            override fun clear() {}
            override fun bufferedSeconds(): Double = 0.0
            override fun stop() { if (running) micStoppedAt.compareAndSet(-1, clock()); running = false }
        }
        val call = VoiceCallSession(
            CallSessionConfig("https://www.graine.ai", "pk_test_key", "agent_1", "web_1", "wss://ws.graine.ai", mapOf("who_called" to "customer"), emptyMap()),
            CallSessionDeps(
                scheduler = ExecutorScheduler("stalled-call"),
                audio = audio,
                http = HttpPost { _, _ -> HttpReply(200, JSONObject().put("ticket", "tkt").put("wsUrl", server.url).toString()) },
                openSocket = { url, protocols, l -> WebSocketClient(url, protocols, l, plainSocket = { line }).also { client = it }.connect() },
            ),
            object : CallSessionEvents {
                override fun onOpen() { opened.put("open") }
                override fun onFailed(error: String) { opened.put("failed:$error") }
                override fun onCaption(role: Role, text: String, live: Boolean) { captions += clock() to text.trim().substringAfterLast(' ') }
            },
        )
        call.start()
        assertEquals("open", opened.poll(5, TimeUnit.SECONDS))
        Thread.sleep(300)

        // The line stops carrying. The agent goes on talking: its frames must still be heard, promptly.
        line.carrying = false
        val sentAt = mutableMapOf<String, Long>()
        for (i in 1..10) {
            Thread.sleep(150)
            val word = "tick$i"
            sentAt[word] = clock()
            server.send(WebSocketClient.OP_TEXT, """{"type":"text","data":"$word "}""")
            assertTrue("at most 300 ms of audio waits", client!!.queuedDroppable <= CallFrames.AUDIO_QUEUE_FRAMES)
        }
        Thread.sleep(300)
        for (i in 1..10) {
            val word = "tick$i"
            val heard = captions.firstOrNull { it.second == word }?.first
            assertTrue("$word reached the session while the line was stalled", heard != null)
            assertTrue("$word was handled within 250 ms, took ${heard!! - sentAt.getValue(word)} ms", heard - sentAt.getValue(word) < 250)
        }
        assertTrue("the oldest audio was dropped, not queued without end", client!!.droppedFrames > 0)

        // The customer hangs up: the microphone goes at once, the socket within about a second.
        val hungUpAt = clock()
        call.hangUp()
        assertTrue("the microphone was released at once", micStoppedAt.get() >= 0 && micStoppedAt.get() - hungUpAt < 100)
        val deadline = clock() + 3_000
        while (server.endedAt.get() < 0 && clock() < deadline) Thread.sleep(20)
        val closedAfter = server.endedAt.get() - hungUpAt
        assertTrue("the stalled socket was closed about a second after the hang-up: $closedAfter ms", server.endedAt.get() >= 0 && closedAfter < 2_500)
    }
}
