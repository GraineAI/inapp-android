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
 * WHO A CHAT BELONGS TO (contract/DESIGN.md) — on a real agent on the JVM,
 * the main thread, the network and the sockets faked. A chat connection is
 * whoever was identified when its ticket was made. When the identity changes
 * while the chat is connected or connecting — nobody to a user, a user to
 * another, a user to nobody — that connection is closed normally, its turns
 * go, a line typed and not yet sent stays and goes next, and the chat connects
 * again at once with a ticket for the new identity. The same identity changes
 * nothing.
 */
class ChatIdentityTest {

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
    private val dir = java.nio.file.Files.createTempDirectory("graine-chat-identity").toFile()
    private val kept = HashMap<String, String>()
    private val sockets = mutableListOf<FakeSocket>()
    /** Every chat ticket asked for, as its body. */
    private val tickets = mutableListOf<JSONObject>()
    private val signOuts = mutableListOf<JSONObject>()

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun agent(field: String = "user_id"): GraineInApp {
        val session = JSONObject("""{"agentId":"agent-1","webcallAgentId":"web-1","appearance":{"title":"Asha"},"identity":{"field":"$field"}}""")
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { it.run() },
            http = HttpPost { url, json ->
                when {
                    url.endsWith("/api/embed/ticket") -> { tickets += JSONObject(json); HttpReply(200, """{"ticket":"tkt_${tickets.size}","wsUrl":"wss://ws.graine.ai/v1/s"}""") }
                    url.endsWith("/api/embed/identity/signout") -> { signOuts += JSONObject(json); HttpReply(200, """{"ok":true}""") }
                    else -> HttpReply(200, """{"ok":true}""")
                }
            },
            fetchSession = { _, _ -> session },
            openSocket = { _, _, listener -> FakeSocket(listener).also { sockets += it } },
            scheduler = clock,
            ticketIo = { it() },
            device = DeviceStore(File(dir, "device.json")),
            sessionCache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text }),
            fonts = false,
        )
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_identity"), GraineInApp.DEFAULT) { env }
        settle()
        return g
    }

    private fun settle() = clock.advance(0)

    private fun shownOnline(g: GraineInApp): FakeSocket {
        g.chat.shown(true); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        assertTrue(g.chat.connected)
        return s
    }

    private fun says(s: FakeSocket, words: String) {
        s.listener.onText(JSONObject().put("type", "text").put("data", "<beginning_of_stream>$words<end_of_stream>").toString()); settle()
    }

    private fun types(s: FakeSocket): List<String> = s.sent.map { runCatching { JSONObject(it).optString("type") }.getOrDefault("") }

    /** Who the last ticket was for: the identity's value, or null for nobody. */
    private fun lastTicketIdentity(): String? = tickets.last().optJSONObject("identity")?.optString("value")

    /** The connection the identity change closed: normally, with the stop frame — never as a drop. */
    private fun assertClosedNormally(old: FakeSocket, g: GraineInApp) {
        assertEquals("the stop frame: this side ended it", "stop", types(old).last())
        clock.advance(CallFrames.CLOSE_AFTER_STOP_MS)
        assertEquals("then closed normally", 1000, old.closed)
        assertFalse("no Disconnected", g.chat.disconnected)
        assertFalse("not a failure", g.chat.failed)
        assertFalse("no reconnect banner", g.chat.reconnecting)
    }

    @Test fun signingInAfterTheChatOpenedClosesTheAnonymousConversationAndConnectsAgainAsTheUser() {
        val g = agent()
        val anonymous = shownOnline(g)
        assertNull("opened before identify(): nobody's", lastTicketIdentity())
        says(anonymous, "Hi! How can I help?")
        g.identify(mapOf("user_id" to "u-1")); settle()
        assertClosedNormally(anonymous, g)
        assertTrue("the anonymous turns are not the user's", g.chat.messages.isEmpty())
        assertEquals("connecting again at once", 2, sockets.size)
        assertTrue(g.chat.connecting)
        assertEquals("with a ticket for the user", "u-1", lastTicketIdentity())
        sockets.last().listener.onOpen("graine.embed.v1"); settle()
        assertTrue(g.chat.connected)
        assertTrue("signing in signs nobody out", signOuts.isEmpty())
    }

    @Test fun anotherUserAndSigningOutCloseTheConversationAndConnectAgainAsTheNewIdentity() {
        val g = agent()
        g.identify(mapOf("user_id" to "u-1")); settle()
        val first = shownOnline(g)
        assertEquals("u-1", lastTicketIdentity())
        says(first, "Welcome back, Meera.")
        g.chat.send("Where is my order?"); settle()

        g.identify(mapOf("user_id" to "u-2")); settle()
        assertClosedNormally(first, g)
        assertTrue("u-1's conversation is not u-2's", g.chat.messages.isEmpty())
        assertEquals("u-2", lastTicketIdentity())
        assertEquals(1, signOuts.size)
        val second = sockets.last()
        second.listener.onOpen("graine.embed.v1"); settle()
        says(second, "Hi there!")

        g.identify(null); settle()
        assertClosedNormally(second, g)
        assertTrue(g.chat.messages.isEmpty())
        assertEquals("connecting again as nobody", 3, sockets.size)
        assertNull("an anonymous ticket", lastTicketIdentity())
    }

    @Test fun theSameUserWrittenAnotherWayChangesNothingAndIsNeverSignedOut() {
        // Graine keys a user by the normalised value (identity-vectors.json): these are one user.
        val g = agent(field = "email")
        g.identify(mapOf("email" to "Asha@Example.com")); settle()
        val s = shownOnline(g)
        says(s, "Hello Asha.")
        g.identify(mapOf("email" to "asha@example.com", "name" to "Asha")); settle()
        assertEquals("still the one connection", 1, sockets.size)
        assertFalse("never stopped", types(s).contains("stop"))
        assertTrue(g.chat.connected)
        assertEquals(listOf("Hello Asha."), g.chat.messages.map { it.text })
        assertTrue("the user still on the phone is never signed out", signOuts.isEmpty())
    }

    @Test fun anEventsNameAndDetailAreTheCallsOwnAndTheAppsDataStaysUnderData() {
        val g = agent()
        g.identify(mapOf("user_id" to "u-1")); settle()
        val s = shownOnline(g)
        g.reportEvent("reminder_opened", "The user came from the breakfast reminder",
            mapOf("name" to "spoofed", "detail" to "not this", "quiet" to true, "cooldown_seconds" to 0, "meal" to "breakfast"))
        settle()
        val ev = s.sent.map { JSONObject(it) }.last { it.optString("type") == "app_event" }.getJSONObject("event")
        assertEquals("the call's own name", "reminder_opened", ev.getString("name"))
        assertEquals("the call's own detail", "The user came from the breakfast reminder", ev.getString("detail"))
        assertTrue("the runtime's keys at the event's level", ev.getBoolean("quiet"))
        assertEquals(0, ev.getInt("cooldown_seconds"))
        val data = ev.getJSONObject("data")
        assertEquals("spoofed", data.getString("name"))
        assertEquals("not this", data.getString("detail"))
        assertEquals("breakfast", data.getString("meal"))
        assertFalse("never loose beside the runtime's keys", ev.has("meal"))
    }

    @Test fun theSameIdentityChangesNothingOnAnOpenChat() {
        val g = agent()
        g.identify(mapOf("user_id" to "u-1")); settle()
        val s = shownOnline(g)
        says(s, "Hello Meera.")
        g.identify(mapOf("user_id" to "u-1", "name" to "Meera", "plan" to "Gold"), memory = false); settle()
        g.setMemory(true); settle()
        assertEquals("still the one connection", 1, sockets.size)
        assertFalse("never stopped", types(s).contains("stop"))
        assertTrue(g.chat.connected)
        assertEquals(listOf("Hello Meera."), g.chat.messages.map { it.text })
    }

    @Test fun aLineTypedAndNotYetSentStaysAndGoesOnTheNextConnection() {
        val g = agent()
        g.chat.shown(true); settle()
        val connecting = sockets.last()
        assertTrue("connecting: the socket is not open yet", g.chat.connecting)
        g.chat.send("I need help with my order"); settle()
        assertEquals(GraineChatTurn.Delivery.SENDING, g.chat.messages.single().delivery)

        g.identify(mapOf("user_id" to "u-1")); settle()
        assertEquals("the line typed stays, still waiting", listOf("I need help with my order"), g.chat.messages.map { it.text })
        assertEquals(GraineChatTurn.Delivery.SENDING, g.chat.messages.single().delivery)
        assertFalse("the closed connection never carried it", connecting.sent.any { it.contains("I need help with my order") })
        assertEquals("u-1", lastTicketIdentity())

        val next = sockets.last()
        next.listener.onOpen("graine.embed.v1"); settle()
        assertTrue("sent on the user's connection", next.sent.any { it.contains("I need help with my order") })
        assertEquals(GraineChatTurn.Delivery.SENT, g.chat.messages.single().delivery)
    }

    @Test fun aChatThatIsNotConnectedForgetsItsTurnsAndConnectsAtTheNextOpen() {
        val g = agent()
        val s = shownOnline(g)
        says(s, "Hi! How can I help?")
        g.chat.close(); settle()
        g.identify(mapOf("user_id" to "u-1")); settle()
        assertEquals("nothing reconnects a chat nobody wants", 1, sockets.size)
        assertTrue("its turns are nobody's, not the user's", g.chat.messages.isEmpty())
        assertFalse(g.chat.connecting)
        g.chat.open(); settle()
        assertEquals(2, sockets.size)
        assertEquals("the next open is the user's", "u-1", lastTicketIdentity())
    }

    @Test fun aConversationGraineEndedIsNotDisconnectedForTheNewIdentity() {
        val g = agent()
        val s = shownOnline(g)
        says(s, "Hi! How can I help?")
        s.listener.onClosed(TextChatSession.CLOSE_IDLE, "idle_timeout"); settle()
        assertTrue(g.chat.disconnected)
        g.identify(mapOf("user_id" to "u-1")); settle()
        assertFalse("the Disconnected line was the conversation before", g.chat.disconnected)
        assertTrue(g.chat.messages.isEmpty())
        assertEquals("it was not connected: nothing reconnects by itself", 1, sockets.size)
        g.chat.send("Hello?"); settle()
        assertEquals("the customer's next message connects, as the user", "u-1", lastTicketIdentity())
    }

    @Test fun whereANewConversationBeganIsForgottenWithTheTurns() {
        val g = agent()
        g.identify(mapOf("user_id" to "u-1")); settle()
        val first = shownOnline(g)
        says(first, "Hi Meera.")
        g.chat.send("Thanks"); settle()
        says(first, "Anything else?")
        first.listener.onClosed(1006, ""); settle()
        var waited = 0L
        while (sockets.size == 1 && waited < 20_000) { clock.advance(100); waited += 100 }
        val second = sockets.last()
        second.listener.onOpen("graine.embed.v1"); settle()
        says(second, "Hi Meera.")
        assertEquals(1, g.chat.newConversationBefore.size)
        g.identify(mapOf("user_id" to "u-2")); settle()
        assertTrue("its marker rows go with the turns", g.chat.newConversationBefore.isEmpty())
        assertTrue(g.chat.messages.isEmpty())
    }
}
