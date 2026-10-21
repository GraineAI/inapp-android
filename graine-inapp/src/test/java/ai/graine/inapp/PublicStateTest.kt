package ai.graine.inapp

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * PUBLIC STATE FOR SCREENS OF YOUR OWN (sdk/contract/PUBLIC-STATE.md) — what
 * the SDK's own chat window and component view read, read by an app through
 * the public API, on a real agent on the JVM (the main thread, the network
 * and the sockets faked): a conversation Graine ended, where a new
 * conversation began under earlier lines, the component on its way, and a
 * component closed from the app's own UI.
 */
class PublicStateTest {

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
    private val dir = java.nio.file.Files.createTempDirectory("graine-public-state").toFile()
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
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_public"), GraineInApp.DEFAULT) { env }
        settle()
        return g
    }

    private fun settle() = clock.advance(0)

    private fun shownOnline(g: GraineInApp): FakeSocket {
        g.chat.shown(true); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        return s
    }

    /** The clock moved until the chat opens its next socket (the ladder's waits are jittered). */
    private fun nextSocket(): FakeSocket {
        val before = sockets.size
        var waited = 0L
        while (sockets.size == before && waited < 20_000) { clock.advance(100); waited += 100 }
        assertEquals("a retry opened the next socket", before + 1, sockets.size)
        return sockets.last()
    }

    private fun says(s: FakeSocket, words: String) {
        s.listener.onText(JSONObject().put("type", "text").put("data", "<beginning_of_stream>$words<end_of_stream>").toString()); settle()
    }

    private fun frame(s: FakeSocket, o: JSONObject) { s.listener.onText(o.toString()); settle() }

    @Test fun aConversationGraineEndedIsDisconnectedForAChatOfYourOwnToo() {
        val g = agent()
        val s = shownOnline(g)
        assertFalse(g.chat.disconnected)
        s.listener.onClosed(TextChatSession.CLOSE_IDLE, "idle_timeout"); settle()
        assertTrue("Graine ended it: nothing reconnects by itself", g.chat.disconnected)
        assertFalse(g.chat.connecting)
        g.chat.connect(); settle()
        assertFalse("connecting again", g.chat.disconnected)
    }

    /**
     * The agent said goodbye and the server let the conversation go: not a
     * failure, not Graine's own end — every flag down, which reads as idle
     * (React Native and Flutter say `idle`; KMP maps these flags to IDLE).
     */
    @Test fun aConversationTheAgentEndedIsIdleNotFailedWhateverTheCloseSaid() {
        for (code in listOf(1000, 1006)) {
            AppShared.reset()
            sockets.clear()
            val g = agent()
            val s = shownOnline(g)
            says(s, "Glad I could help. Goodbye!")
            frame(s, JSONObject().put("type", "conversation_end"))
            assertTrue("$code: held open for a rating, still connected", g.chat.connected)
            s.listener.onClosed(code, ""); settle()
            clock.advance(20_000)
            assertTrue(g.chat.ended)
            assertFalse("$code: not connected", g.chat.connected)
            assertFalse("$code: nothing reconnects", g.chat.connecting)
            assertFalse("$code: the agent's goodbye is not a failure", g.chat.failed)
            assertFalse("$code: nor Graine's own end", g.chat.disconnected)
            assertEquals("$code: one socket", 1, sockets.size)
        }
    }

    @Test fun whereANewConversationBeganIsTheChatsAndTheRowsCarryIt() {
        val g = agent()
        val first = shownOnline(g)
        says(first, "Hi Meera, I'm Asha, your care coach.")
        g.chat.send("I take metformin twice a day."); settle()
        says(first, "Thanks, I'll keep that in mind.")
        assertTrue(g.chat.newConversationBefore.isEmpty())
        // A drop, and the server could not continue: the agent opens again with no history.
        first.listener.onClosed(1006, ""); settle()
        val second = nextSocket()
        second.listener.onOpen("graine.embed.v1"); settle()
        says(second, "Hi Meera, I'm Asha, your care coach.")
        val lines = g.chat.messages
        assertEquals(4, lines.size)
        assertEquals("the marker goes before the new conversation's first words", listOf(lines.last().id), g.chat.newConversationBefore)
        val rows = ChatRows.build(lines, g.chat.newConversationBefore)
        val at = rows.indexOfFirst { it is ChatRows.Row.NewConversation }
        assertEquals(lines.last().id, (rows[at + 1] as ChatRows.Row.Turn).turn.id)
        assertEquals("Disconnected · new conversation", (rows[at] as ChatRows.Row.NewConversation).label)
        g.chat.clear()
        assertTrue("clear takes it away with the lines", g.chat.newConversationBefore.isEmpty())
    }

    @Test fun aComponentOnItsWayIsLoadingUntilItArrivesOrTwelveSecondsPass() {
        val g = agent()
        val s = shownOnline(g)
        assertNull(g.loadingWidget)
        frame(s, JSONObject().put("type", "widget_pending").put("widget", JSONObject().put("widget_id", "slots:1").put("kind", "calendar_slots")))
        assertEquals("calendar_slots", g.loadingWidget!!.optString("kind"))
        clock.advance(Widgets.PENDING_WIDGET_MS + 1)
        assertNull("the SDK's view gives up too", g.loadingWidget)
        frame(s, JSONObject().put("type", "widget_pending").put("widget", JSONObject().put("widget_id", "slots:2").put("kind", "calendar_slots")))
        assertNotNull(g.loadingWidget)
        frame(s, JSONObject().put("type", "widget").put("widget", JSONObject().put("widget_id", "slots:2").put("kind", "calendar_slots")
            .put("data", JSONObject().put("title", "Pick a time"))))
        assertNull("it arrived", g.loadingWidget)
        assertEquals("slots:2", GraineWidget.adopt(g.pendingWidget, "https://www.graine.ai")!!.id)
    }

    @Test fun aComponentClosedFromTheAppsOwnUiTellsTheAgentQuietly() {
        val g = agent()
        val s = shownOnline(g)
        frame(s, JSONObject().put("type", "widget").put("widget", JSONObject().put("widget_id", "plans:7").put("kind", "choices")
            .put("data", JSONObject().put("title", "Pick a plan"))))
        assertNotNull(g.pendingWidget)
        g.dismissWidget("plans:7"); settle()
        assertNull(g.pendingWidget)
        val event = s.sent.map { JSONObject(it) }.last { it.optString("type") == "app_event" }.getJSONObject("event")
        assertEquals("component_dismissed", event.getString("name"))
        assertTrue("quiet: the agent does not speak up for it", event.getBoolean("quiet"))
        assertEquals(Widgets.DISMISSED_DETAIL, event.getString("detail"))
        val data = event.getJSONObject("data")
        assertEquals("plans", data.getString("tool_name"))
        assertEquals("choices", data.getString("kind"))
        assertEquals("Pick a plan", data.getString("title"))
        assertFalse(data.getBoolean("answered"))
        val before = s.sent.size
        g.dismissWidget(" ")
        assertEquals("a blank id closes nothing", before, s.sent.size)
    }
}
