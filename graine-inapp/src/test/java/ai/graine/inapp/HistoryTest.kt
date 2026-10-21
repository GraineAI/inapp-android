package ai.graine.inapp

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor

/**
 * CONVERSATIONS CONTINUE (contract §2.6; vectors sdk/contract/history.json,
 * hand-written from CONTINUITY.md and run by every SDK): the history frame
 * read, folded into the turns on screen, and laid out as the chat window draws
 * it; a reconnect the server could not continue, marked where the new
 * conversation began — then the same through a real agent on the JVM (the
 * main thread, the network and the sockets faked), and a new conversation
 * started over.
 */
class HistoryTest {
    private val contract: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/history.json").exists() && !File(dir, "contract/history.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/history.json"), File(dir, "contract/history.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/history.json missing — this contract is never skipped")
        JSONObject(f.readText())
    }

    private fun section(name: String): List<JSONObject> {
        val list = contract.getJSONArray(name)
        assertTrue("section $name is empty", list.length() > 0)
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    private fun ms(iso: String): Long = ChatHistory.time(iso) ?: error("not a time: $iso")

    // ── the contract ─────────────────────────────────────────────────────────

    @Test fun framesAreReadAsTheContractSays() {
        for (c in section("frames")) {
            val name = c.getString("name")
            val got = ChatHistory.read(c.getJSONObject("frame"))
            if (c.isNull("turns")) {
                assertNull("$name: the frame must be ignored", got)
                continue
            }
            val want = c.getJSONArray("turns")
            assertNotNull("$name: a readable frame", got)
            assertEquals("$name: ${got!!.map { it.text }}", want.length(), got.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONObject(i)
                val t = got[i]
                assertEquals("$name #$i id", w.getString("id"), t.id)
                assertEquals("$name #$i role", w.getString("role"), if (t.role == Role.AGENT) "agent" else "customer")
                assertEquals("$name #$i text", w.getString("text"), t.text)
                assertEquals("$name #$i at", ms(w.getString("at")), t.at.time)
                assertEquals("$name #$i channel", w.optString("channel", "chat"), t.channel.name.lowercase())
                // The session it was said in, wherever the expected turn names it (null when it names none).
                if (w.has("session")) assertEquals("$name #$i session", if (w.isNull("session")) null else w.getString("session"), t.session)
                assertTrue("$name #$i: delivered and restored", t.restored && !t.live && !t.proactive && t.delivery == GraineChatTurn.Delivery.SENT)
            }
        }
    }

    @Test fun theSessionFrameNamesTheChatsSessionAsTheContractSays() {
        for (c in section("sessionFrame")) {
            val name = c.getString("name")
            val f = ChatInbound.parse(c.getJSONObject("frame").toString())
            assertTrue("$name: a session frame", f is ChatInbound.Session)
            assertEquals(name, if (c.isNull("sessionId")) null else c.getString("sessionId"), (f as ChatInbound.Session).id)
        }
    }

    @Test fun theSessionsAreDerivedAsTheContractSays() {
        for (c in section("sessions")) {
            val name = c.getString("name")
            val raw = c.getJSONArray("turns")
            val turns = (0 until raw.length()).map { i ->
                val t = raw.getJSONObject(i)
                GraineChatTurn("s$i", if (t.getString("role") == "agent") Role.AGENT else Role.CUSTOMER, t.getString("text"), Date(ms(t.getString("at"))),
                    channel = if (t.optString("channel") == "voice") GraineChatTurn.Channel.VOICE else GraineChatTurn.Channel.CHAT,
                    session = if (t.isNull("session")) null else t.getString("session"))
            }
            val sessionId = if (c.isNull("sessionId")) null else c.getString("sessionId")
            val want = c.getJSONArray("sessions").let { a -> (0 until a.length()).map { i ->
                val e = a.getJSONObject(i)
                GraineChatSession(
                    id = if (e.isNull("id")) null else e.getString("id"),
                    startedAt = Date(ms(e.getString("startedAt"))),
                    endedAt = Date(ms(e.getString("endedAt"))),
                    channel = if (e.getString("channel") == "voice") GraineChatTurn.Channel.VOICE else GraineChatTurn.Channel.CHAT,
                    turns = e.getInt("turns"),
                    current = e.getBoolean("current"),
                )
            } }
            assertEquals(name, want, ChatSessions.of(turns, sessionId))
        }
    }

    /** A case's steps, on a transcript of its own: {agent}, {customer, delivery?}, {opened}, {history}. */
    private fun play(c: JSONObject): ChatTranscript {
        val name = c.getString("name")
        val t = ChatTranscript()
        var clock = 0L
        val steps = c.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            clock += 1_000
            when {
                step.has("agent") -> { t.agentWords(step.getString("agent"), clock); t.agentTurnEnd() }
                step.has("customer") -> {
                    val delivery = when (step.optString("delivery", "sent")) {
                        "sending" -> GraineChatTurn.Delivery.SENDING
                        "failed" -> GraineChatTurn.Delivery.FAILED
                        else -> GraineChatTurn.Delivery.SENT
                    }
                    t.customer(step.getString("customer"), clock, clock, delivery)
                }
                step.optBoolean("opened") -> { t.opened(); t.deliver(GraineChatTurn.Delivery.SENDING, GraineChatTurn.Delivery.SENT) }
                // As the socket hands it on: a frame that restores nothing still arrives.
                step.has("history") -> t.restore((ChatInbound.parse(step.getJSONObject("history").toString()) as ChatInbound.History).turns)
                else -> error("$name: unknown step $step")
            }
        }
        return t
    }

    /** The turns on screen are the case's `turns`. */
    private fun assertTurns(c: JSONObject, got: List<GraineChatTurn>) {
        val name = c.getString("name")
        val want = c.getJSONArray("turns")
        assertEquals("$name: ${got.map { "${it.role}:${it.text}" }}", want.length(), got.size)
        for (i in 0 until want.length()) {
            val w = want.getJSONObject(i)
            val turn = got[i]
            assertEquals("$name #$i role", w.getString("role"), if (turn.role == Role.AGENT) "agent" else "customer")
            assertEquals("$name #$i text", w.getString("text"), turn.text)
            assertEquals("$name #$i restored", w.optBoolean("restored", false), turn.restored)
            assertEquals("$name #$i channel", w.optString("channel", "chat"), turn.channel.name.lowercase())
            if (turn.role == Role.CUSTOMER) assertEquals("$name #$i delivery", w.optString("delivery", "sent"), turn.delivery.name.lowercase())
            if (w.has("id")) assertEquals("$name #$i id", w.getString("id"), turn.id)
            if (w.has("at")) assertEquals("$name #$i at", ms(w.getString("at")), turn.at.time)
            if (turn.restored) assertFalse("$name #$i: a restored turn is not live, not volunteered", turn.live || turn.proactive)
        }
    }

    @Test fun restoreFollowsTheContractsSteps() {
        for (c in section("restore")) assertTurns(c, play(c).lines)
    }

    @Test fun aNewConversationUnderEarlierLinesFollowsTheContractsSteps() {
        for (c in section("fresh")) {
            val name = c.getString("name")
            val t = play(c)
            val got = t.lines
            assertTurns(c, got)
            val want = c.getJSONArray("marks").let { m -> (0 until m.length()).map { m.getInt(it) } }
            assertEquals("$name: the turns a marker row goes before", want, got.indices.filter { got[it].id in t.marks })
            assertTrue("$name: a marker row stands before a turn on screen, never alone", got.map { it.id }.containsAll(t.marks))
        }
    }

    /** For a chat of your own: the rows ChatRows builds from the chat's markers stand right before the turns the contract marks. */
    @Test fun theRowsOfAChatOfYourOwnCarryTheContractsMarkers() {
        for (c in section("fresh")) {
            val name = c.getString("name")
            val t = play(c)
            val lines = t.lines
            val want = c.getJSONArray("marks").let { m -> (0 until m.length()).map { lines[m.getInt(it)].id } }
            val rows = ChatRows.build(lines, t.marks.toList(), Date(ms("2026-10-21T09:00:00Z")), TimeZone.getTimeZone("UTC"))
            val markers = rows.withIndex().filter { it.value is ChatRows.Row.NewConversation }
            assertEquals("$name: one row per marker", want, markers.map { (it.value as ChatRows.Row.NewConversation).before })
            for ((i, row) in markers) assertEquals("$name: right before its turn", (row as ChatRows.Row.NewConversation).before, (rows[i + 1] as ChatRows.Row.Turn).turn.id)
        }
    }

    @Test fun aNewConversationsRowGoesAfterItsDaySeparatorAndOnlyUnderEarlierRows() {
        val utc = TimeZone.getTimeZone("UTC")
        val now = Date(ms("2026-10-21T09:00:00Z"))
        fun turn(id: String, at: String, voice: Boolean = false) = GraineChatTurn(id, Role.AGENT, "…", Date(ms(at)),
            channel = if (voice) GraineChatTurn.Channel.VOICE else GraineChatTurn.Channel.CHAT)
        val turns = listOf(turn("a", "2026-10-20T09:00:00Z"), turn("b", "2026-10-21T08:00:00Z"))
        val rows = ChatRows.build(turns, listOf("b"), now, utc)
        assertEquals(listOf("day", "turn", "day", "fresh", "turn"), rows.map {
            when (it) {
                is ChatRows.Row.Day -> "day"
                is ChatRows.Row.Voice -> "voice"
                is ChatRows.Row.Turn -> "turn"
                is ChatRows.Row.NewConversation -> "fresh"
            }
        })
        val row = rows[3] as ChatRows.Row.NewConversation
        assertEquals("Disconnected · new conversation", row.label)
        assertEquals("Disconnected. A new conversation started.", row.spoken)
        assertEquals("no markers: the rows as before", ChatRows.build(turns, now, utc), ChatRows.build(turns, emptyList(), now, utc))
        assertTrue("nothing earlier to set apart", ChatRows.build(turns, listOf("a"), now, utc).none { it is ChatRows.Row.NewConversation })
        val call = listOf(turn("v1", "2026-10-21T08:00:00Z", voice = true), turn("v2", "2026-10-21T08:00:05Z", voice = true))
        val split = ChatRows.build(call, setOf("v2"), now, utc)
        assertEquals("a marked voice turn starts a call row of its own", 3, split.size)
        assertTrue(split[1] is ChatRows.Row.NewConversation && split[2] is ChatRows.Row.Voice)
    }

    @Test fun rowsAreLaidOutAsTheContractSays() {
        for (c in section("rows")) {
            val name = c.getString("name")
            val zone = TimeZone.getTimeZone(c.getString("timeZone"))
            val raw = c.getJSONArray("turns")
            val turns = (0 until raw.length()).map { i ->
                val t = raw.getJSONObject(i)
                GraineChatTurn("t$i", if (t.getString("role") == "agent") Role.AGENT else Role.CUSTOMER, t.getString("text"), Date(ms(t.getString("at"))),
                    restored = true, channel = if (t.optString("channel") == "voice") GraineChatTurn.Channel.VOICE else GraineChatTurn.Channel.CHAT)
            }
            val shape = ChatRows.build(turns, Date(ms(c.getString("now"))), zone).map { row ->
                when (row) {
                    is ChatRows.Row.Day -> "day:${row.key}"
                    is ChatRows.Row.Voice -> "voice:${row.first}-${row.last}"
                    is ChatRows.Row.Turn -> "turn:${row.turn.id.drop(1)}"
                    is ChatRows.Row.NewConversation -> "fresh:${row.before}"
                }
            }
            val wantRows = c.getJSONArray("rows")
            val want = (0 until wantRows.length()).map { i ->
                val r = wantRows.getJSONObject(i)
                when {
                    r.has("day") -> "day:${r.getString("day")}"
                    r.has("voice") -> "voice:${r.getJSONArray("voice").getInt(0)}-${r.getJSONArray("voice").getInt(1)}"
                    else -> "turn:${r.getInt("turn")}"
                }
            }
            assertEquals(name, want, shape)
        }
    }

    // ── a new conversation under earlier lines, past the contract's cases ─────

    /** A welcome, a question, its answer — then the connection opens again, with no history. */
    private fun startedOver(t: ChatTranscript = ChatTranscript()): ChatTranscript {
        t.opened()
        t.agentWords("Hi, I'm Asha.", 1_000); t.agentTurnEnd()
        t.customer("Is my order shipped?", 2_000)
        t.agentWords("Yes, it left this morning.", 3_000); t.agentTurnEnd()
        t.opened()
        return t
    }

    @Test fun aComponentFirstInANewConversationIsATurnOfItsOwnAfterTheMarkerRow() {
        val t = startedOver()
        val card = GraineWidget.adopt(JSONObject().put("widget_id", "plans:1").put("kind", "choices"), "https://www.graine.ai")!!
        t.component(card, 4_000)
        val lines = t.lines
        assertNull("the answer above is the earlier conversation's: the card is not added to it", lines[2].component)
        assertEquals(card, lines[3].component)
        assertEquals(setOf(lines[3].id), t.marks)
    }

    @Test fun aTurnThatSaidNothingHandsItsMarkerRowToWhatComesNext() {
        val t = startedOver()
        t.agentTurnStart(4_000)
        t.agentTurnEnd()
        assertTrue("no marker row stands alone", t.marks.isEmpty())
        t.customer("Hello?", 5_000)
        assertEquals(listOf("Hi, I'm Asha.", "Is my order shipped?", "Yes, it left this morning.", "Hello?"), t.lines.map { it.text })
        assertEquals(setOf(t.lines[3].id), t.marks)
    }

    @Test fun clearTakesTheMarkerRowsWithTheLines() {
        val t = startedOver()
        t.agentWords("Hi, I'm Asha.", 4_000); t.agentTurnEnd()
        assertEquals(1, t.marks.size)
        t.clear()
        assertTrue(t.marks.isEmpty())
        t.opened()
        t.agentWords("Hi, I'm Asha.", 5_000); t.agentTurnEnd()
        assertTrue("a first conversation: nothing to mark", t.marks.isEmpty())
    }

    @Test fun aMarkerRowScrollsAwayWithTheTurnItStoodBefore() {
        val t = startedOver(ChatTranscript(cap = 5))
        t.agentWords("Hi, I'm Asha.", 4_000); t.agentTurnEnd()
        val marked = t.lines[3].id
        t.customer("Where is it now?", 5_000)
        t.agentWords("In Pune.", 6_000); t.agentTurnEnd()
        assertEquals("the oldest went, the marker row stayed", setOf(marked), t.marks)
        repeat(3) { t.customer("And now?", 7_000L + it) }
        assertTrue(t.marks.isEmpty())
    }

    // ── the words ────────────────────────────────────────────────────────────

    private val india: TimeZone = TimeZone.getTimeZone("Asia/Kolkata")
    private val now = Date(ms("2026-10-14T06:30:00Z"))

    @Test fun todayYesterdayAndADateWithTheYearWhenItIsNotThisOne() {
        fun line(day: String, iso: String) = ChatRows.Row.Day(day, "", Date(ms(iso)))
        assertEquals("Today", ChatRows.dayLabel(line(ChatRows.TODAY, "2026-10-14T05:00:00Z"), now, india, Locale.UK))
        assertEquals("Yesterday", ChatRows.dayLabel(line(ChatRows.YESTERDAY, "2026-10-13T05:00:00Z"), now, india, Locale.UK))
        assertEquals("Sat 10 Oct", ChatRows.dayLabel(line(ChatRows.DATE, "2026-10-10T08:00:00Z"), now, india, Locale.UK))
        assertEquals("Wed 31 Dec 2025", ChatRows.dayLabel(line(ChatRows.DATE, "2025-12-31T08:00:00Z"), now, india, Locale.UK))
        assertEquals("Voice call · 6:05 PM", ChatRows.callLabel("6:05 PM"))
        assertEquals("Voice call at 6:05 PM", ChatRows.callSpoken("6:05 PM"))
    }

    @Test fun rowsHaveStableKeysAndTheHeaderAndStartersFollowTheHistory() {
        val earlier = Date(ms("2026-10-13T10:00:00Z"))
        val a1 = GraineChatTurn("a1", Role.AGENT, "Hi", earlier, restored = true, channel = GraineChatTurn.Channel.VOICE)
        val a2 = GraineChatTurn("a2", Role.CUSTOMER, "Yo", earlier, restored = true, channel = GraineChatTurn.Channel.VOICE)
        val t1 = GraineChatTurn("t1", Role.CUSTOMER, "Now", now)
        val rows = ChatRows.build(listOf(a1, a2, t1), now, india)
        assertEquals(listOf("voice-a1"), rows.filterIsInstance<ChatRows.Row.Voice>().map { it.id })
        assertEquals(listOf("2026-10-13", "2026-10-14"), rows.filterIsInstance<ChatRows.Row.Day>().map { it.date })

        val welcome = GraineChatTurn("t1", Role.AGENT, "Hi!", now, proactive = true)
        val restored = GraineChatTurn("h1", Role.AGENT, "It ships today.", earlier, restored = true)
        assertTrue("a new chat offers its starters", ChatRows.offersStarters(emptyList()) && ChatRows.offersStarters(listOf(welcome)))
        assertFalse("never on a conversation that continues", ChatRows.offersStarters(listOf(restored)))
        assertFalse("a live conversation is not an earlier one", ChatRows.offersNewConversation(listOf(welcome)))
        assertTrue(ChatRows.offersNewConversation(listOf(restored, welcome)))
    }

    @Test fun theMultiSelectButtonAndTheCallOnlyFooter() {
        assertEquals("Pick 2 more", Widgets.manyButtonLabel(0, "Taken"))
        assertEquals("Pick 1 more", Widgets.manyButtonLabel(1, "Compare"))
        assertEquals("Taken 2", Widgets.manyButtonLabel(2, "Taken"))
        assertEquals("STILL LISTENING" to "Tap one, or just say it.", Widgets.screenFooter("", typed = false))
        assertEquals("STILL LISTENING" to "Pick a car", Widgets.screenFooter("Pick a car", typed = false))
        assertNull("a typed chat has no call to be on", Widgets.screenFooter("Pick a car", typed = true))
        assertEquals("Close and keep talking", Widgets.closeLabel(typed = false))
        assertEquals("Close", Widgets.closeLabel(typed = true))
    }

    @Test fun javaCanStillMakeATurnAndReadTheNewFields() {
        val t = GraineChatTurn("t1", Role.AGENT, "Hi", Date(0))
        assertFalse(t.restored)
        assertEquals(GraineChatTurn.Channel.CHAT, t.channel)
    }

    // ── a real agent ─────────────────────────────────────────────────────────

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
        fun types(): List<String> = sent.map { JSONObject(it).optString("type") }
    }

    private val clock = ManualScheduler()
    private val dir = java.nio.file.Files.createTempDirectory("graine-history").toFile()
    private val kept = HashMap<String, String>()
    private val requests = mutableListOf<Pair<String, JSONObject>>()
    private val sockets = mutableListOf<FakeSocket>()
    private val replies = HashMap<String, HttpReply>()

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
            http = HttpPost { url, json ->
                val body = JSONObject(json)
                requests += url to body
                when {
                    url.endsWith("/api/embed/ticket") -> HttpReply(200, """{"ticket":"tkt_1","wsUrl":"wss://ws.graine.ai/v1/s"}""")
                    else -> replies.entries.firstOrNull { url.endsWith(it.key) }?.value ?: HttpReply(200, """{"ok":true}""")
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
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_history"), GraineInApp.DEFAULT) { env }
        settle()
        return g
    }

    private fun settle() = clock.advance(0)
    private fun posted(path: String) = requests.filter { it.first.endsWith(path) }.map { it.second }
    private fun frame(s: FakeSocket, o: JSONObject) { s.listener.onText(o.toString()); settle() }
    private fun says(s: FakeSocket, words: String) = frame(s, JSONObject().put("type", "text").put("data", "<beginning_of_stream>$words<end_of_stream>"))

    /** The clock moved until the chat opens its next socket (a reconnect's waits are jittered). */
    private fun nextSocket(): FakeSocket {
        val before = sockets.size
        var waited = 0L
        while (sockets.size == before && waited < 20_000) { clock.advance(100); waited += 100 }
        assertEquals("a reconnect opened the next socket", before + 1, sockets.size)
        return sockets.last()
    }

    private val history = JSONObject().put("type", "history").put("continued", true).put("turns", JSONArray()
        .put(JSONObject().put("id", "f1").put("role", "user").put("text", "Is my order shipped?").put("at", "2026-10-13T09:30:00Z").put("channel", "chat"))
        .put(JSONObject().put("id", "f2").put("role", "assistant").put("text", "Yes, it left this morning.").put("at", "2026-10-13T09:30:04Z").put("channel", "chat"))
        .put(JSONObject().put("id", "f3").put("role", "assistant").put("text", "Hi, this is Asha.").put("at", "2026-10-13T11:00:00Z").put("channel", "fax")))

    /** An identified user's chat, open, with the earlier conversation restored. */
    private fun continuing(): Pair<GraineInApp, FakeSocket> {
        val g = agent()
        g.identify(mapOf("user_id" to "U-1"))
        settle()
        g.chat.connect()
        settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        frame(s, history)
        return g to s
    }

    @Test fun theChatRestoresTheHistoryBeforeAnyAgentMessageAndNeverReportsItAsNew() {
        val g = agent()
        val heard = mutableListOf<String>()
        g.addListener(object : GraineListener { override fun onChatMessage(role: Role, text: String, live: Boolean) { heard += text } })
        g.chat.connect(); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        frame(s, history)
        assertEquals(listOf("Is my order shipped?", "Yes, it left this morning.", "Hi, this is Asha."), g.chat.messages.map { it.text })
        assertTrue(g.chat.messages.all { it.restored })
        assertEquals("a channel this build does not know is the chat", GraineChatTurn.Channel.CHAT, g.chat.messages.last().channel)
        assertTrue("restored turns are not the agent speaking now", heard.isEmpty())
        // The conversation continues: the next message and the reply come after it.
        g.chat.send("Where is it now?"); settle()
        frame(s, JSONObject().put("type", "text").put("data", "<beginning_of_stream>In Pune.<end_of_stream>"))
        assertEquals(listOf("Where is it now?", "In Pune."), g.chat.messages.takeLast(2).map { it.text })
        assertFalse(g.chat.messages.last().proactive)
    }

    @Test fun whatWentOutAsTheChatOpenedStaysAfterTheHistory() {
        val g = agent()
        g.chat.send("Typed in the tunnel"); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        assertTrue("the waiting line went out on open", s.types().contains("text"))
        frame(s, JSONObject().put("type", "history").put("turns", JSONArray()
            .put(JSONObject().put("id", "g1").put("role", "assistant").put("text", "Hello!").put("at", "2026-10-14T05:00:00Z"))))
        assertEquals(listOf("true:Hello!", "false:Typed in the tunnel"), g.chat.messages.map { "${it.restored}:${it.text}" })
    }

    @Test fun aMalformedHistoryIsNoNews() {
        val g = agent()
        g.chat.connect(); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        frame(s, JSONObject().put("type", "text").put("data", "<beginning_of_stream>Hi!<end_of_stream>"))
        frame(s, JSONObject().put("type", "history").put("turns", "none"))
        frame(s, JSONObject().put("type", "history").put("turns", JSONArray().put(JSONObject().put("role", "robot").put("text", "x").put("at", "2026-10-13T09:30:00Z"))))
        assertEquals(listOf("Hi!"), g.chat.messages.map { it.text })
    }

    @Test fun aForgetClearsTheChatOnScreenAndARefusalLeavesIt() {
        val (g, _) = continuing()
        replies[EmbedApi.FORGET] = HttpReply(503, """{"error":"Try again soon.","code":"unavailable"}""")
        var refused: GraineError? = null
        g.forgetMemory { _, e -> refused = e }
        settle()
        assertEquals(GraineErrorCode.UNAVAILABLE, refused?.code)
        assertEquals("a forget that did not complete took the conversation off the screen", 3, g.chat.messages.size)
        replies[EmbedApi.FORGET] = HttpReply(200, """{"deleted":2}""")
        var deleted: Int? = null
        g.forgetMemory { n, _ -> deleted = n }
        settle()
        assertEquals(2, deleted)
        assertTrue("the forgotten conversation is still on screen", g.chat.messages.isEmpty())
    }

    @Test fun aNewConversationHangsUpLetsGoClearsAndOpensFresh() {
        val (g, first) = continuing()
        var finished = false
        g.chat.newConversation { _, e -> assertNull(e); finished = true }
        settle()
        assertTrue(finished)
        assertEquals("the conversation let go of was not hung up", "stop", JSONObject(first.sent.last()).optString("type"))
        val asked = posted(EmbedApi.NEW_CONVERSATION).single()
        assertEquals("U-1", asked.getJSONObject("identity").getString("value"))
        assertTrue(asked.getString("installId").isNotEmpty())
        assertTrue("memories are untouched", posted(EmbedApi.FORGET).isEmpty())
        assertTrue(g.chat.messages.isEmpty())
        val fresh = sockets.last()
        assertNotSame("no fresh chat was opened", first, fresh)
        fresh.listener.onOpen("graine.embed.v1"); settle()
        frame(fresh, JSONObject().put("type", "text").put("data", "<beginning_of_stream>Hi! How can I help?<end_of_stream>"))
        assertEquals(listOf("Hi! How can I help?"), g.chat.messages.map { it.text })
    }

    @Test fun aRefusedNewConversationLeavesTheChatAsItWasConnectedAgain() {
        val (g, first) = continuing()
        replies[EmbedApi.NEW_CONVERSATION] = HttpReply(403, """{"error":"Not one of your devices.","code":"device_limit"}""")
        var refused: GraineError? = null
        g.chat.newConversation { _, e -> refused = e }
        settle()
        assertEquals(GraineErrorCode.DEVICE_LIMIT, refused?.code)
        assertEquals("a refused new conversation took the old one off the screen", 3, g.chat.messages.size)
        assertNotSame("the chat was left hung up", first, sockets.last())
    }

    @Test fun aReconnectThatStartsOverShowsWhereTheNewConversationBegan() {
        val g = agent()
        g.chat.connect(); settle()
        val first = sockets.last()
        first.listener.onOpen("graine.embed.v1"); settle()
        says(first, "Hi, I'm Asha.")
        g.chat.send("Is my order shipped?"); settle()
        says(first, "Yes, it left this morning.")
        // The connection drops, and the next one cannot continue the conversation: no history, the welcome again.
        first.listener.onClosed(1006, ""); settle()
        val second = nextSocket()
        second.listener.onOpen("graine.embed.v1"); settle()
        says(second, "Hi, I'm Asha.")
        val lines = g.chat.messages
        assertEquals(listOf("Hi, I'm Asha.", "Is my order shipped?", "Yes, it left this morning.", "Hi, I'm Asha."), lines.map { it.text })
        assertEquals("the window draws the marker row before the new welcome", setOf(lines[3].id), ChatExtras.of(g.chat, canCall = false).marks)
        // Again, and nothing new from the customer: the welcome nobody answered is replaced, the marker row before the newest.
        second.listener.onClosed(1006, ""); settle()
        val third = nextSocket()
        third.listener.onOpen("graine.embed.v1"); settle()
        says(third, "Hi, I'm Asha.")
        assertEquals(listOf("Hi, I'm Asha.", "Is my order shipped?", "Yes, it left this morning.", "Hi, I'm Asha."), g.chat.messages.map { it.text })
        assertEquals(setOf(g.chat.messages[3].id), ChatExtras.of(g.chat, canCall = false).marks)
        // Clear (or New conversation) takes it away with the lines.
        g.chat.clear()
        assertTrue(ChatExtras.of(g.chat, canCall = false).marks.isEmpty())
    }

    @Test fun aReconnectThatContinuesDrawsNoMarkerRow() {
        val (g, s) = continuing()
        s.listener.onClosed(1006, ""); settle()
        val next = nextSocket()
        next.listener.onOpen("graine.embed.v1"); settle()
        frame(next, history)
        g.chat.send("Where is it now?"); settle()
        says(next, "In Pune.")
        assertEquals(listOf("Is my order shipped?", "Yes, it left this morning.", "Hi, this is Asha.", "Where is it now?", "In Pune."),
            g.chat.messages.map { it.text })
        assertTrue(ChatExtras.of(g.chat, canCall = false).marks.isEmpty())
    }

    @Test fun aHistoryFrameThatRestoresNothingStillMeansTheConversationContinued() {
        val g = agent()
        g.chat.connect(); settle()
        val first = sockets.last()
        first.listener.onOpen("graine.embed.v1"); settle()
        says(first, "Hi, I'm Asha.")
        g.chat.send("Is my order shipped?"); settle()
        says(first, "Yes, it left this morning.")
        first.listener.onClosed(1006, ""); settle()
        val next = nextSocket()
        next.listener.onOpen("graine.embed.v1"); settle()
        // In place of a welcome: the server continued, though nothing in the frame could be read.
        frame(next, JSONObject().put("type", "history").put("turns", "none").put("continued", true))
        g.chat.send("And now?"); settle()
        says(next, "In Pune.")
        assertEquals(listOf("Hi, I'm Asha.", "Is my order shipped?", "Yes, it left this morning.", "And now?", "In Pune."),
            g.chat.messages.map { it.text })
        assertTrue("continued: no marker row", ChatExtras.of(g.chat, canCall = false).marks.isEmpty())
    }

    @Test fun anonymousStartsOverWithoutAskingGraine() {
        val g = agent()
        g.chat.connect(); settle()
        val s = sockets.last()
        s.listener.onOpen("graine.embed.v1"); settle()
        frame(s, JSONObject().put("type", "text").put("data", "<beginning_of_stream>Hello!<end_of_stream>"))
        g.chat.newConversation(); settle()
        assertTrue("nothing was kept for an anonymous user", posted(EmbedApi.NEW_CONVERSATION).isEmpty())
        assertTrue(g.chat.messages.isEmpty())
        assertNotSame(s, sockets.last())
    }
}
