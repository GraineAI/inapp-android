package ai.graine.inapp

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * THE CHAT'S STATE, AS AN APP READS IT — through a real agent on the JVM (the
 * main thread, the network and the sockets faked): connected, connecting,
 * reconnecting after a drop, and given up.
 */
class ChatStateTest {

    private class TestApp : Application() {
        override fun getPackageName(): String = "com.acme.app"
    }

    private class TestMain(private val clock: ManualScheduler) : MainThread {
        private val pending = HashMap<Runnable, MutableList<() -> Unit>>()
        override fun post(r: Runnable): Boolean = postDelayed(r, 0)
        override fun postDelayed(r: Runnable, delayMs: Long): Boolean {
            val mine = pending.getOrPut(r) { ArrayList() }
            lateinit var cancel: () -> Unit
            cancel = clock.schedule(delayMs) { mine.remove(cancel); r.run() }
            mine += cancel
            return true
        }
        override fun removeCallbacks(r: Runnable) { pending.remove(r)?.forEach { it() } }
    }

    private class FakeSocket(val listener: CallSocketListener) : CallSocket {
        val sent = mutableListOf<String>()
        var closed: Int? = null
        override fun send(text: String): Boolean { if (closed != null) return false; sent += text; return true }
        override fun close(code: Int, reason: String) { if (closed == null) closed = code }
    }

    private val clock = ManualScheduler()
    private val dir = java.nio.file.Files.createTempDirectory("graine-chat-state").toFile()
    private val kept = HashMap<String, String>()
    private val sockets = mutableListOf<FakeSocket>()

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun agent(): GraineInApp {
        val session = JSONObject("""{"agentId":"agent-1","webcallAgentId":"web-1","appearance":{"title":"Asha"}}""")
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { it.run() },
            http = HttpPost { url, _ ->
                if (url.endsWith("/api/embed/ticket")) HttpReply(200, """{"ticket":"tkt_1","wsUrl":"wss://ws.graine.ai/v1/s"}""")
                else HttpReply(200, """{"ok":true}""")
            },
            fetchSession = { _, _ -> session },
            openSocket = { _, _, listener -> FakeSocket(listener).also { sockets += it } },
            scheduler = clock,
            ticketIo = { it() },
            device = DeviceStore(File(dir, "device.json")),
            sessionCache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text }),
            fonts = false,
        )
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_state"), GraineInApp.DEFAULT) { env }
        settle()
        return g
    }

    private fun settle() = clock.advance(0)

    /** Past the longest wait the ladder can draw: 15 s, jittered up by a quarter. */
    private fun waitOutARetry() = clock.advance(20_000)

    /** The clock moved until the chat opens its next socket (the ladder's waits are jittered). */
    private fun nextSocket(): FakeSocket {
        val before = sockets.size
        var waited = 0L
        while (sockets.size == before && waited < 20_000) { clock.advance(100); waited += 100 }
        assertEquals("a retry opened the next socket", before + 1, sockets.size)
        return sockets.last()
    }

    private fun online(g: GraineInApp): FakeSocket {
        g.chat.connect(); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        return s
    }

    @Test fun aDropIsReconnectingUntilTheChatIsBack() {
        val g = agent()
        val changes = mutableListOf<Boolean>()
        g.chat.addChangeListener { changes += g.chat.reconnecting }
        online(g)
        assertTrue(g.chat.connected)
        assertFalse(g.chat.reconnecting)

        sockets.last().listener.onClosed(1006, ""); settle()
        assertTrue("a drop is being made again", g.chat.reconnecting)
        assertTrue("reconnecting is connecting too", g.chat.connecting)
        assertFalse(g.chat.connected)
        assertFalse(g.chat.failed)
        assertTrue("the change listeners heard it", changes.contains(true))

        val second = nextSocket()
        assertTrue("the retry itself is still the reconnect", g.chat.reconnecting)

        second.listener.onOpen("graine.embed.v1"); settle()
        assertTrue(g.chat.connected)
        assertFalse("back: no longer reconnecting", g.chat.reconnecting)
    }

    @Test fun aChatThatGivesUpIsFailedNotReconnectingAndARetryIsAFreshConnect() {
        val g = agent()
        online(g)
        sockets.last().listener.onClosed(1006, ""); settle()
        // Every attempt on the ladder fails.
        repeat(6) {
            val attempt = nextSocket()
            assertTrue("attempt ${it + 1} is a reconnect", g.chat.reconnecting)
            attempt.listener.onFailure(RuntimeException("offline")); settle()
        }
        assertTrue("every retry spent", g.chat.failed)
        assertFalse(g.chat.reconnecting)
        assertFalse(g.chat.connecting)

        g.chat.connect(); settle()
        assertTrue(g.chat.connecting)
        assertFalse("asked again after giving up: connecting, not reconnecting", g.chat.reconnecting)
        assertFalse(g.chat.failed)
    }

    @Test fun aFirstConnectThatFailsIsRetriedAsAReconnect() {
        val g = agent()
        g.chat.connect(); settle()
        assertTrue(g.chat.connecting)
        assertFalse("the first try is connecting", g.chat.reconnecting)
        sockets.last().listener.onFailure(RuntimeException("refused")); settle()
        assertTrue("the next try remakes it", g.chat.reconnecting)
    }

    @Test fun closingDuringAReconnectEndsIt() {
        val g = agent()
        online(g)
        sockets.last().listener.onClosed(1006, ""); settle()
        assertTrue(g.chat.reconnecting)
        g.chat.close()
        assertFalse(g.chat.reconnecting)
        assertFalse(g.chat.connecting)
        waitOutARetry()
        assertEquals("closed on purpose: nothing comes back", 1, sockets.size)
    }
}
