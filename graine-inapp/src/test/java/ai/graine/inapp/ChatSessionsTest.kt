package ai.graine.inapp

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * THE CHAT'S SESSIONS (contract/PUBLIC-STATE.md; history.json `sessionFrame`
 * and `sessions`), on a real agent on the JVM — the main thread, the network
 * and the sockets faked. The server's session frame names each connection's
 * session; a turn carries the session it was said in (a line typed before the
 * chat connected, the session of the connection that sends it); a new
 * connection starts without one until its frame comes; and `sessions` lists
 * the runs, the current one marked.
 */
class ChatSessionsTest {

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
    private val dir = java.nio.file.Files.createTempDirectory("graine-chat-sessions").toFile()
    private val kept = HashMap<String, String>()
    private val sockets = mutableListOf<FakeSocket>()

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun agent(): GraineInApp {
        val session = JSONObject("""{"agentId":"agent-1","webcallAgentId":"web-1","appearance":{"title":"Asha"},"identity":{"field":"user_id"}}""")
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { it.run() },
            http = HttpPost { url, _ ->
                if (url.endsWith("/api/embed/ticket")) HttpReply(200, """{"ticket":"tkt","wsUrl":"wss://ws.graine.ai/v1/s"}""")
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
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_sessions"), GraineInApp.DEFAULT) { env }
        settle()
        return g
    }

    private fun settle() = clock.advance(0)

    private fun frame(s: FakeSocket, o: JSONObject) { s.listener.onText(o.toString()); settle() }

    private fun session(s: FakeSocket, id: Any?) = frame(s, JSONObject().put("type", "session").put("id", id ?: JSONObject.NULL).put("at", "2026-10-21T12:00:00Z"))

    private fun says(s: FakeSocket, words: String) = frame(s, JSONObject().put("type", "text").put("data", "<beginning_of_stream>$words<end_of_stream>"))

    private fun nextSocket(): FakeSocket {
        val before = sockets.size
        var waited = 0L
        while (sockets.size == before && waited < 20_000) { clock.advance(100); waited += 100 }
        assertEquals("a retry opened the next socket", before + 1, sockets.size)
        return sockets.last()
    }

    @Test fun eachTurnCarriesItsConnectionsSessionEvenALineTypedBeforeTheChatConnected() {
        val g = agent()
        g.chat.shown(true); settle()
        g.chat.send("Where is my order?"); settle()
        assertNull("nothing connected yet: no session", g.chat.sessionId)
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        assertNull("open, but its frame has not come: none yet", g.chat.sessionId)
        session(s, "s-9f2c")
        assertEquals("s-9f2c", g.chat.sessionId)
        says(s, "It ships tomorrow.")
        assertEquals("the line typed before goes in the session that sent it", listOf("s-9f2c", "s-9f2c"), g.chat.messages.map { it.session })
        val only = g.chat.sessions.single()
        assertEquals("s-9f2c", only.id)
        assertTrue(only.current)
        assertEquals(2, only.turns)
        assertEquals(GraineChatTurn.Channel.CHAT, only.channel)
    }

    @Test fun aNewConnectionIsANewSessionAndTheEarlierOneIsKeptInTheList() {
        val g = agent()
        g.chat.shown(true); settle()
        val first = sockets.last()
        first.listener.onOpen("graine.embed.v1"); settle()
        session(first, "s-1")
        says(first, "Hi! How can I help?")
        g.chat.send("Where is my order?"); settle()
        first.listener.onClosed(1006, ""); settle()
        val second = nextSocket()
        second.listener.onOpen("graine.embed.v1"); settle()
        assertNull("a new connection starts without one", g.chat.sessionId)
        session(second, "s-2")
        says(second, "Welcome back.")
        assertEquals(listOf("s-1", "s-2"), g.chat.sessions.map { it.id })
        assertEquals(listOf(2, 1), g.chat.sessions.map { it.turns })
        assertEquals(listOf(false, true), g.chat.sessions.map { it.current })
    }

    @Test fun aServerThatSendsNoSessionsIsOneSessionWithNoIdNeverCurrent() {
        val g = agent()
        g.chat.shown(true); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        says(s, "Hello!")
        g.chat.send("Hi"); settle()
        assertNull(g.chat.sessionId)
        val only = g.chat.sessions.single()
        assertNull(only.id)
        assertFalse(only.current)
        assertEquals(2, only.turns)
        // An id that is not a non-empty string is none.
        session(s, "")
        assertNull(g.chat.sessionId)
        session(s, 42)
        assertNull(g.chat.sessionId)
    }

    @Test fun restoredTurnsKeepTheirSessionsAndTheListShowsEach() {
        val g = agent()
        g.chat.shown(true); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        session(s, "s-9f2c")
        val turns = JSONArray()
            .put(JSONObject().put("id", "b1").put("role", "user").put("text", "Hi").put("at", "2026-10-20T10:00:00Z").put("channel", "chat").put("session", "s-7a1c"))
            .put(JSONObject().put("id", "b2").put("role", "assistant").put("text", "Hello!").put("at", "2026-10-20T10:00:03Z").put("channel", "chat").put("session", "s-7a1c"))
            .put(JSONObject().put("id", "b3").put("role", "user").put("text", "Call me").put("at", "2026-10-21T08:00:00Z").put("channel", "voice").put("session", "c-31f0"))
        frame(s, JSONObject().put("type", "history").put("continued", true).put("turns", turns))
        g.chat.send("Back again"); settle()
        assertEquals(listOf("s-7a1c", "c-31f0", "s-9f2c"), g.chat.sessions.map { it.id })
        assertEquals(listOf(GraineChatTurn.Channel.CHAT, GraineChatTurn.Channel.VOICE, GraineChatTurn.Channel.CHAT), g.chat.sessions.map { it.channel })
        assertEquals(listOf(false, false, true), g.chat.sessions.map { it.current })
    }

    @Test fun anIdentityChangeLeavesTheSessionBehindAndTheLineKeptGoesInTheNextOne() {
        val g = agent()
        g.chat.shown(true); settle()
        val first = sockets.last()
        first.listener.onOpen("graine.embed.v1"); settle()
        session(first, "s-anon")
        says(first, "Hi! How can I help?")
        first.listener.onClosed(1006, ""); settle()
        g.chat.send("I need help"); settle()
        g.identify(mapOf("user_id" to "u-1")); settle()
        assertNull("the session was the identity before's", g.chat.sessionId)
        val next = sockets.last()
        next.listener.onOpen("graine.embed.v1"); settle()
        session(next, "s-u1")
        assertEquals("the kept line went in the user's session", listOf("s-u1"), g.chat.messages.map { it.session })
        assertEquals(listOf("s-u1"), g.chat.sessions.map { it.id })
    }
}
