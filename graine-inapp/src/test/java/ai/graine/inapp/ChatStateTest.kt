package ai.graine.inapp

import android.app.Application
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
 * THE CHAT'S STATE, AS AN APP READS IT — through a real agent on the JVM (the
 * main thread, the network and the sockets faked): connected, connecting,
 * reconnecting after a drop, and given up; closed 15 seconds (the app's
 * chatCloseDelaySeconds) after no window shows it; ended by Graine itself, and
 * not remade.
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

    private fun agent(config: GraineConfig = GraineConfig("https://www.graine.ai", "pk_state")): GraineInApp {
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
        val g = GraineInApp.make(TestApp(), config, GraineInApp.DEFAULT) { env }
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

    // ── how long a conversation lasts (contract/DESIGN.md) ────────────────────

    /** A chat window came on screen: the chat connects, and its socket opens. */
    private fun shownOnline(g: GraineInApp): FakeSocket {
        g.chat.shown(true); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        return s
    }

    private fun says(s: FakeSocket, words: String) {
        s.listener.onText(JSONObject().put("type", "text").put("data", "<beginning_of_stream>$words<end_of_stream>").toString()); settle()
    }

    private fun type(s: FakeSocket): String = JSONObject(s.sent.last()).optString("type")

    /** The conversation closed as close() closes it: the stop frame, then a normal close — never a drop, never "Disconnected". */
    private fun assertClosedNormally(g: GraineInApp, s: FakeSocket) {
        assertFalse(g.chat.connected)
        assertFalse(g.chat.connecting)
        assertEquals("the agent is told it is over", "stop", type(s))
        clock.advance(1_000)
        assertEquals(1000, s.closed)
        assertFalse("not Graine ending it", g.chat.disconnected)
        assertFalse(g.chat.failed)
        waitOutARetry()
        assertEquals("and nothing comes back", 1, sockets.size)
    }

    @Test fun aChatNoWindowShowsClosesFifteenSecondsAfterTheLastWindowLeft() {
        val g = agent()
        assertEquals("the default", 15, g.chat.closeDelaySeconds)
        assertEquals(15_000L, GraineChat.CLOSE_UNSEEN_MS)
        val s = shownOnline(g)
        says(s, "Hi, I'm Asha.")
        // On screen, the chat itself never closes it for being idle.
        clock.advance(10 * 60_000)
        assertTrue(g.chat.connected)
        g.chat.shown(false); settle()
        clock.advance(15_000 - 1)
        assertTrue("counted from when the last window left, not from the last message", g.chat.connected)
        assertNull(s.closed)
        clock.advance(1)
        assertClosedNormally(g, s)
    }

    @Test fun aMessageWhileNoWindowShowsTheChatStartsTheWaitAgain() {
        val g = agent()
        val s = shownOnline(g)
        g.chat.shown(false); settle()
        clock.advance(10_000)
        says(s, "Your order just shipped.")
        clock.advance(10_000)
        assertTrue("15 seconds from the window leaving have passed, not 15 from the message", g.chat.connected)
        clock.advance(5_000 - 1)
        assertTrue(g.chat.connected)
        clock.advance(1)
        assertClosedNormally(g, s)
    }

    @Test fun reopenedWithinTheDelayTheChatGoesOnOnTheSameConnection() {
        val g = agent()
        val s = shownOnline(g)
        says(s, "Hi, I'm Asha.")
        // A quick hop: the booking sheet over the chat, and back.
        g.chat.shown(false); settle()
        clock.advance(14_000)
        g.chat.shown(true); settle()
        assertEquals("no new connection", 1, sockets.size)
        assertNull(s.closed)
        assertTrue(g.chat.connected)
        assertEquals("nothing on screen changed, no new welcome", listOf("Hi, I'm Asha."), g.chat.messages.map { it.text })
        clock.advance(60_000)
        assertTrue("on screen it stays", g.chat.connected)
        // Off screen again: a fresh 15 seconds.
        g.chat.shown(false); settle()
        clock.advance(15_000 - 1)
        assertTrue(g.chat.connected)
        clock.advance(1)
        assertClosedNormally(g, s)
    }

    @Test fun twoWindowsCloseItOnlyOnceBothHaveLeft() {
        val g = agent()
        val s = shownOnline(g)
        g.chat.shown(true); settle()
        g.chat.shown(false); settle()
        clock.advance(60_000)
        assertTrue("one window still shows it", g.chat.connected)
        g.chat.shown(false); settle()
        clock.advance(15_000)
        assertClosedNormally(g, s)
    }

    @Test fun anAppsOwnChatThatNeverReportsAWindowIsNeverClosedForBeingIdle() {
        val g = agent()
        online(g)
        clock.advance(10 * 60_000)
        assertTrue(g.chat.connected)
    }

    @Test fun aCloseDelayOfZeroClosesAsSoonAsTheLastWindowLeaves() {
        val g = agent(GraineConfig("https://www.graine.ai", "pk_state", chatCloseDelaySeconds = 0))
        assertEquals(0, g.chat.closeDelaySeconds)
        val s = shownOnline(g)
        says(s, "Hi, I'm Asha.")
        g.chat.shown(false); settle()
        assertClosedNormally(g, s)
        // Opened again, it connects again — and closing the screen closes it again at once.
        val again = shownOnline(g)
        g.chat.shown(false); settle()
        assertFalse(g.chat.connected)
        assertEquals("stop", type(again))
    }

    @Test fun aCustomCloseDelayIsTheAppsOwn() {
        val g = agent(GraineConfig("https://www.graine.ai", "pk_state", chatCloseDelaySeconds = 40))
        assertEquals(40, g.chat.closeDelaySeconds)
        val s = shownOnline(g)
        g.chat.shown(false); settle()
        clock.advance(40_000 - 1)
        assertTrue("39.999 seconds: still connected", g.chat.connected)
        clock.advance(1)
        assertClosedNormally(g, s)
    }

    @Test fun theCloseDelayIsKeptWithinZeroToThreeHundredSeconds() {
        assertEquals("the config's default", 15, GraineConfig("https://www.graine.ai", "pk_state").chatCloseDelaySeconds)
        for ((asked, kept) in listOf(-5 to 0, 0 to 0, 15 to 15, 300 to 300, 301 to 300, 1000 to 300, Int.MIN_VALUE to 0, Int.MAX_VALUE to 300)) {
            AppShared.reset()
            val g = agent(GraineConfig("https://www.graine.ai", "pk_state", chatCloseDelaySeconds = asked))
            assertEquals("config $asked", kept, g.chat.closeDelaySeconds)
            g.chat.closeDelaySeconds = 30
            g.chat.closeDelaySeconds = asked
            assertEquals("set to $asked", kept, g.chat.closeDelaySeconds)
        }
    }

    @Test fun aNegativeCloseDelayClosesAtOnce() {
        val g = agent(GraineConfig("https://www.graine.ai", "pk_state", chatCloseDelaySeconds = -5))
        val s = shownOnline(g)
        g.chat.shown(false); settle()
        assertClosedNormally(g, s)
    }

    @Test fun settingTheCloseDelayWhileACloseWaitsTakesTheNewDelay() {
        val g = agent()
        val s = shownOnline(g)
        g.chat.shown(false); settle()
        clock.advance(5_000)
        g.chat.closeDelaySeconds = 60; settle()
        clock.advance(15_000)
        assertTrue("the old 15 seconds no longer close it", g.chat.connected)
        clock.advance(40_000 - 1)
        assertTrue("60 seconds from the window leaving, not yet", g.chat.connected)
        clock.advance(1)
        assertFalse(g.chat.connected)
        assertEquals("stop", type(s))

        // Shortened past what has already waited: closed at once.
        val again = shownOnline(g)
        g.chat.shown(false); settle()
        clock.advance(20_000)
        assertTrue(g.chat.connected)
        g.chat.closeDelaySeconds = 10; settle()
        assertFalse("waited 20 already: closed now", g.chat.connected)
        assertEquals("stop", type(again))
    }

    @Test fun theCloseDelayNeverEndsAChatOnScreen() {
        val g = agent(GraineConfig("https://www.graine.ai", "pk_state", chatCloseDelaySeconds = 0))
        shownOnline(g)
        g.chat.closeDelaySeconds = 0; settle()
        clock.advance(60_000)
        assertTrue("a window shows it", g.chat.connected)
    }

    /** Graine closed the conversation with [code] itself: nothing remakes it, the header says so, the customer's next step connects. */
    private fun endedByGraine(code: Int) {
        val g = agent()
        val s = shownOnline(g)
        says(s, "Hi, I'm Asha.")
        s.listener.onClosed(code, ""); settle()
        waitOutARetry()
        assertEquals("$code: nothing reconnects by itself", 1, sockets.size)
        assertFalse(g.chat.connecting)
        assertFalse(g.chat.reconnecting)
        assertFalse("ended is not failed", g.chat.failed)
        assertEquals("the lines stay", listOf("Hi, I'm Asha."), g.chat.messages.map { it.text })
        val x = ChatExtras.of(g.chat, canCall = false)
        assertTrue(x.disconnected)
        val header = Surfaces.styleOf(Design.CLASSIC_LIGHT).header
        assertEquals("$code: the header", ChatLook.Status(ChatLook.StatusKind.DISCONNECTED, ChatLook.DISCONNECTED, false),
            ChatLook.status(header, "", "Typing…", g.chat.online, x.connecting, x.reconnecting, g.chat.failed, false, x.ended, "Connecting…", x.disconnected))
        fun bar() = VoiceBar.status(VoiceBar.StatusInput(chat = true, chatOnline = g.chat.online, chatFailed = g.chat.failed, chatDisconnected = g.chat.disconnected))
        assertEquals("$code: the voice bar in the chat", VoiceBar.Status(ChatLook.DISCONNECTED), bar())
        // The app coming back to the foreground is not the customer asking: still nothing.
        g.foregroundWatchers.forEach { it(true) }; settle()
        assertEquals(1, sockets.size)
        // Opening the chat again connects again.
        g.chat.shown(false); g.chat.shown(true); settle()
        assertEquals("$code: opened again, connected again", 2, sockets.size)
        assertFalse(ChatExtras.of(g.chat, canCall = false).disconnected)
        assertTrue(g.chat.connecting)
        assertEquals("$code: the bar says it is connecting", VoiceBar.Status("Connecting…", "connecting"), bar())
    }

    @Test fun whenGraineEndsAnIdleConversationTheChatDoesNotReconnect() = endedByGraine(TextChatSession.CLOSE_IDLE)

    @Test fun whenGraineEndsAConversationAtItsTimeLimitTheChatDoesNotReconnect() = endedByGraine(TextChatSession.CLOSE_TIME_LIMIT)

    @Test fun afterGraineEndedItAMessageConnectsAgain() {
        val g = agent()
        val s = shownOnline(g)
        s.listener.onClosed(TextChatSession.CLOSE_IDLE, "idle_timeout"); settle()
        g.chat.send("Are you still there?"); settle()
        assertEquals(2, sockets.size)
        sockets.last().listener.onOpen("graine.embed.v1"); settle()
        assertTrue(g.chat.connected)
        assertEquals("the waiting message went out", "text", type(sockets.last()))
    }

    @Test fun anyOtherCloseIsADropThatReconnects() {
        val g = agent()
        val s = shownOnline(g)
        s.listener.onClosed(1006, ""); settle()
        assertTrue(g.chat.reconnecting)
        assertFalse("a drop is not Graine ending it", ChatExtras.of(g.chat, canCall = false).disconnected)
        nextSocket()
    }

    @Test fun theAppGoingToTheBackgroundStillEndsTheChatAndItComesBackWithTheApp() {
        val g = agent()
        val s = shownOnline(g)
        g.foregroundWatchers.forEach { it(false) }; settle()
        assertFalse(g.chat.connected)
        assertEquals("stop", type(s))
        g.foregroundWatchers.forEach { it(true) }; settle()
        assertEquals("back with the app", 2, sockets.size)
    }
}
