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
import java.util.TreeMap
import java.util.concurrent.Executor

/**
 * CHAT WINDOW v2, THE WIRE AND THE RULES (contract/DESIGN.md "Chat window
 * v2"; vectors sdk/contract/chat-window.json, generated from the React Native
 * SDK's own code): the agent's quick replies read and cleaned, the fold —
 * replies, the end and the rating — through a real agent on the JVM (the main
 * thread, the network and the socket faked), the rating and its quiet frame,
 * the rating card's layout, groups and whose time is drawn, the safe
 * Markdown, and the reveal. Every section, every case: the same answers
 * every SDK gives.
 */
class ChatWindowTest {
    private val contract: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/chat-window.json").exists() && !File(dir, "contract/chat-window.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/chat-window.json"), File(dir, "contract/chat-window.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/chat-window.json missing — this contract is never skipped")
        JSONObject(f.readText())
    }

    private fun section(name: String): List<JSONObject> {
        val list = contract.getJSONArray(name)
        assertTrue("section $name is empty", list.length() > 0)
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    private fun canon(v: Any?): Any? = when {
        v == null || v == JSONObject.NULL -> null
        v is JSONObject -> TreeMap<String, Any?>().also { m -> v.keys().forEach { k -> m[k] = canon(v.opt(k)) } }
        v is JSONArray -> (0 until v.length()).map { canon(v.opt(it)) }
        v is Number -> v.toDouble()
        else -> v
    }

    private fun replies(list: List<GraineQuickReply>?): JSONArray? = list?.let { l ->
        JSONArray().apply { l.forEach { put(JSONObject().put("label", it.label).put("text", it.text)) } }
    }

    private fun rating(r: GraineChatRating?): Any = r?.let { JSONObject().put("value", it.value).put("scale", it.scale).put("style", it.style) } ?: JSONObject.NULL

    // ── the limits and the words every SDK shares ─────────────────────────────

    @Test fun theLimitsAndTheWordsAreTheContracts() {
        val l = contract.getJSONObject("limits")
        assertEquals(l.getInt("quickReplies"), ChatRules.QUICK_REPLIES_MAX)
        assertEquals(l.getInt("quickReplyLabel"), ChatRules.QUICK_REPLY_LABEL_MAX)
        assertEquals(l.getInt("quickReplyText"), ChatRules.QUICK_REPLY_TEXT_MAX)
        assertEquals(l.getLong("groupGapMs"), ChatRules.GROUP_GAP_MS)
        assertEquals(l.getDouble("revealMinCps"), ChatRules.REVEAL_MIN_CPS, 0.0)
        assertEquals(l.getDouble("revealCatchUpMs"), ChatRules.REVEAL_CATCH_UP_MS, 0.0)
        assertEquals(contract.getString("ratingEvent"), ChatRules.RATING_EVENT)
        assertEquals(contract.getString("ratingThanks"), ChatRules.RATING_THANKS)
        val emoji = contract.getJSONArray("ratingEmoji")
        assertEquals((0 until emoji.length()).map { emoji.getString(it) }, ChatRules.RATING_EMOJI)
        val labels = contract.getJSONObject("ratingLabels")
        for (style in ChatRules.RATING_STYLES) {
            val want = labels.getJSONArray(style)
            assertEquals(style, (0 until want.length()).map { want.getString(it) }, ChatRules.RATING_LABELS.getValue(style))
        }
        assertEquals("Thumbs up", ChatRules.ratingLabel("thumbs", 1))
        assertEquals("4 stars", ChatRules.ratingLabel("stars", 4))
        assertEquals("Very happy", ChatRules.ratingLabel("emoji", 5))
    }

    // ── quick_replies frames ──────────────────────────────────────────────────

    @Test fun quickRepliesAreReadAsTheContractSays() {
        for (c in section("quickReplies")) {
            val name = c.getString("name")
            val got = ChatRules.readQuickReplies(c.opt("frame"))
            if (c.isNull("replies")) { assertNull(name, got); continue }
            assertEquals(name, canon(c.getJSONArray("replies")), canon(replies(got)))
            // On the socket: the same frame read as the chat reads it, ignored when it is not one.
            val inbound = ChatInbound.parse(c.get("frame").toString())
            if (c.opt("frame") is JSONObject) assertEquals(name, got, (inbound as ChatInbound.QuickReplies).replies)
        }
        assertEquals(ChatInbound.Ignored, ChatInbound.parse(JSONObject().put("type", "quick_replies").put("replies", "Yes").toString()))
        assertEquals(ChatInbound.ConversationEnd, ChatInbound.parse("""{"type":"conversation_end"}"""))
    }

    // ── the rating ────────────────────────────────────────────────────────────

    @Test fun aRatingAndTheFrameItIsSentAsAreTheContracts() {
        for (c in section("rating")) {
            val input = c.getJSONObject("input")
            val r = ChatRules.rating(input.opt("style"), input.opt("value"))
            assertEquals("$input", canon(c.getJSONObject("rating")), canon(rating(r)))
            assertEquals("$input", canon(c.getJSONObject("frame")), canon(ChatRules.ratingFrame(r)))
            assertEquals("$input", canon(c.getJSONObject("frame")), canon(JSONObject(ChatFrames.rating(r))))
        }
    }

    @Test fun theRatingCardsLayoutIsTheContracts() {
        val l = contract.getJSONObject("limits")
        assertEquals(l.getDouble("ratingMaxWidth"), ChatLook.RATING_MAX, 0.0)
        assertEquals(l.getDouble("ratingShare"), ChatLook.RATING_SHARE, 0.0)
        assertEquals(l.getDouble("ratingGap"), ChatLook.RATING_GAP, 0.0)
        val choices = contract.getJSONObject("ratingChoices")
        assertEquals(choices.keys().asSequence().toSet(), ChatLook.RATING_CHOICES.keys)
        for (style in ChatRules.RATING_STYLES) {
            val want = choices.getJSONObject(style)
            val got = ChatLook.RATING_CHOICES.getValue(style)
            assertEquals(style, want.getInt("count"), got.count)
            assertEquals(style, want.getDouble("size"), got.size, 0.0)
            assertEquals(style, want.getDouble("glyph"), got.glyph, 0.0)
            assertEquals("$style: one target per choice drawn", ChatLook.ratingValues(style).size, got.count)
        }
        for (c in section("ratingLayout")) {
            val got = ChatLook.ratingLayout(c.getString("style"), c.getDouble("thread"), c.getDouble("padding"))
            assertEquals("$c width", c.getDouble("width"), got.width, 1e-6)
            assertEquals("$c count", c.getInt("count"), got.count)
            assertEquals("$c size", c.getDouble("size"), got.size, 1e-6)
            assertEquals("$c glyph", c.getDouble("glyph"), got.glyph, 1e-6)
            assertEquals("$c gap", c.getDouble("gap"), got.gap, 1e-6)
        }
        // A style this build does not know is thumbs, at any width.
        for (thread in listOf(0.0, 100.0, 258.0, 327.0, 600.0)) {
            val thumbs = ChatLook.ratingLayout("thumbs", thread, 14.0)
            assertEquals("hearts at $thread", thumbs, ChatLook.ratingLayout("hearts", thread, 14.0))
            assertEquals("no style at $thread", thumbs, ChatLook.ratingLayout(null, thread, 14.0))
        }
        // A width or padding that is not a finite number is 0: never a negative or NaN size.
        val none = ChatLook.ratingLayout("emoji", Double.NaN, Double.NEGATIVE_INFINITY)
        assertEquals(0.0, none.width, 0.0)
        assertEquals(0.0, none.size, 0.0)
        assertEquals(0.0, none.glyph, 0.0)
        assertEquals(ChatLook.ratingLayout("stars", 327.0, 0.0), ChatLook.ratingLayout("stars", 327.0, Double.POSITIVE_INFINITY))
    }

    // ── groups ────────────────────────────────────────────────────────────────

    @Test fun groupsAndWhoseTimeIsDrawnAreTheContracts() {
        for (c in section("groups")) {
            val name = c.getString("name")
            val raw = c.getJSONArray("items")
            val items = (0 until raw.length()).map { i ->
                val o = raw.getJSONObject(i)
                ChatRules.GroupItem(
                    role = o.opt("role") as? String,
                    at = if (o.has("at") && !o.isNull("at")) o.getLong("at") else null,
                    breaks = o.optBoolean("breaks", false),
                )
            }
            val positions = ChatRules.groups(items)
            val want = c.getJSONArray("positions")
            assertEquals(name, (0 until want.length()).map { want.getString(it) }, positions)
            val times = c.getJSONObject("times")
            for (show in listOf("never", "group", "always")) {
                val w = times.getJSONArray(show)
                assertEquals("$name $show", (0 until w.length()).map { w.getBoolean(it) }, positions.map { ChatRules.showsTime(show, it) })
            }
        }
    }

    // ── the agent's words ─────────────────────────────────────────────────────

    private fun blocks(list: List<ChatRules.Block>): JSONArray = JSONArray().apply {
        for (b in list) put(JSONObject().put("kind", b.kind).apply { if (b.kind == ChatRules.Block.NUMBER) put("n", b.n) }
            .put("spans", JSONArray().apply {
                for (s in b.spans) put(JSONObject().put("text", s.text).apply {
                    if (s.bold) put("bold", true)
                    if (s.italic) put("italic", true)
                    if (s.code) put("code", true)
                    s.link?.let { put("link", it) }
                })
            }))
    }

    @Test fun safeFormattingIsTheContracts() {
        for (c in section("markdown")) {
            val text = c.getString("text")
            val live = c.optBoolean("live", false)
            val got = ChatRules.markdown(text, live)
            assertEquals("${if (live) "live " else ""}\"$text\"", canon(c.getJSONArray("blocks")), canon(blocks(got)))
            assertEquals("plain words of \"$text\"", c.getString("plain"), ChatRules.markdownText(got))
        }
        // Never throws, whatever arrives.
        for (odd in listOf(null, "\\", "[", "[](", "](x)", "``", "**__**", "_*_*", "# ", "1.", "•")) ChatRules.markdown(odd, live = true)
    }

    @Test fun theRevealIsTheContracts() {
        for (c in section("reveal")) {
            assertEquals("$c", c.getInt("length"), ChatRules.revealLength(c.getDouble("shown"), c.getDouble("target"), c.getDouble("elapsedMs")))
        }
        // Never half an emoji.
        assertEquals("a👍", ChatRules.revealSlice("a👍b", 2))
        assertEquals("", ChatRules.revealSlice("abc", -3))
        assertEquals("abc", ChatRules.revealSlice("abc", 99))
    }

    // ── the fold, through a real agent ────────────────────────────────────────

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
    private val dir = java.nio.file.Files.createTempDirectory("graine-chat-window").toFile()
    private val kept = HashMap<String, String>()
    private val sockets = mutableListOf<FakeSocket>()

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun settle() = clock.advance(0)

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
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_window"), GraineInApp.DEFAULT) { env }
        settle()
        return g
    }

    /** An agent's chat, open on its socket. */
    private fun open(): GraineInApp {
        val g = agent()
        g.chat.connect(); settle()
        sockets.last().listener.onOpen("graine.embed.v1"); settle()
        return g
    }

    private fun frames(): List<JSONObject> = sockets.flatMap { it.sent }.map { JSONObject(it) }

    @Test fun theFoldIsTheContracts() {
        for (c in section("fold")) {
            AppShared.reset()
            sockets.clear()
            val name = c.getString("name")
            val g = open()
            val rated = mutableListOf<GraineChatRating>()
            g.chat.onRated = { rated += it }
            val steps = c.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                when {
                    step.has("frame") -> sockets.last().listener.onText(step.get("frame").toString())
                    step.has("send") -> g.chat.send(step.getString("send"))
                    step.has("rate") -> step.getJSONObject("rate").let { g.chat.rate(it.getString("style"), it.getInt("value")) }
                    step.optBoolean("drop") -> sockets.last().listener.onClosed(1006, "")
                    step.optBoolean("clear") -> g.chat.clear()
                    else -> error("$name: unknown step $step")
                }
                settle()
            }
            val want = c.getJSONArray("turns")
            val got = g.chat.messages
            assertEquals("$name: ${got.map { "${it.role}:${it.text}" }}", want.length(), got.size)
            for (i in 0 until want.length()) {
                val w = want.getJSONObject(i)
                val t = got[i]
                assertEquals("$name #$i role", w.getString("role"), if (t.role == Role.AGENT) "agent" else "customer")
                assertEquals("$name #$i text", w.getString("text"), t.text)
                if (t.role == Role.AGENT) {
                    assertEquals("$name #$i live", w.getBoolean("live"), t.live)
                    assertEquals("$name #$i proactive", w.getBoolean("proactive"), t.proactive)
                }
                assertEquals("$name #$i restored", w.optBoolean("restored", false), t.restored)
            }
            assertEquals("$name typing", c.getBoolean("typing"), g.chat.typing)
            assertEquals("$name quick replies", canon(c.getJSONArray("quickReplies")), canon(replies(g.chat.quickReplies)))
            assertEquals("$name ended", c.getBoolean("ended"), g.chat.ended)
            assertEquals("$name rating", canon(c.opt("rating")), canon(rating(g.chat.rating)))
            val sent = frames().filter { it.optString("type") == "text" }.map { it.getString("data") }
            assertEquals("$name sent", (0 until c.getJSONArray("sent").length()).map { c.getJSONArray("sent").getString(it) }, sent)
            val events = frames().filter { it.optString("type") == "app_event" && it.optJSONObject("event")?.optString("name") == ChatRules.RATING_EVENT }
            assertEquals("$name events", canon(c.getJSONArray("events")), canon(JSONArray(events)))
            // The rating reached the app as it reached the conversation: once, and only when it was taken.
            assertEquals("$name onRated", events.size, rated.size)
        }
    }

    // ── the rest of the chat's v2 state ───────────────────────────────────────

    @Test fun aQuickReplyTappedIsTheCustomersOwnLine() {
        val g = open()
        sockets.last().listener.onText("""{"type":"text","data":"<beginning_of_stream>How can I help?<end_of_stream>"}""")
        sockets.last().listener.onText("""{"type":"quick_replies","replies":[{"label":"Talk to a person","text":"I'd like to talk to a person"}]}""")
        settle()
        val reply = g.chat.quickReplies.single()
        assertEquals("Talk to a person", reply.label)
        g.chat.sendQuickReply(reply); settle()
        assertEquals("I'd like to talk to a person", g.chat.messages.last().text)
        assertEquals(Role.CUSTOMER, g.chat.messages.last().role)
        assertTrue(g.chat.quickReplies.isEmpty())
    }

    @Test fun aClosedConversationAfterTheEndIsNotRemadeUntilTheCustomerWrites() {
        val g = open()
        sockets.last().listener.onText("""{"type":"text","data":"<beginning_of_stream>Bye!<end_of_stream>"}""")
        sockets.last().listener.onText("""{"type":"conversation_end"}""")
        settle()
        assertTrue(g.chat.ended)
        // The server lets the ended conversation go a moment later.
        sockets.last().listener.onClosed(1000, ""); settle()
        clock.advance(20_000)
        assertEquals("nothing reconnects an ended conversation", 1, sockets.size)
        assertFalse(g.chat.connecting)
        assertFalse("ended is not failed", g.chat.failed)
        // The customer writes: a new conversation.
        g.chat.send("One more thing"); settle()
        assertEquals(2, sockets.size)
        assertFalse(g.chat.ended)
    }

    @Test fun aPinIsCleanedAndCleared() {
        val g = agent()
        var changes = 0
        g.chat.addChangeListener { changes++ }
        g.chat.pin(GraineChatPin("  Account\nsettings  ", "Profile and preferences " + "x".repeat(100), " https://example.com/a.png ", "Active"))
        val p = g.chat.pinned!!
        assertEquals("Account settings", p.title)
        assertEquals(GraineChat.PIN_SUBTITLE_MAX, p.subtitle!!.codePointCount(0, p.subtitle!!.length))
        assertEquals("https://example.com/a.png", p.imageUrl)
        assertEquals("Active", p.badge)
        assertEquals(1, changes)
        g.chat.pin(GraineChatPin("   "))
        assertNull("a pin with no title is no pin", g.chat.pinned)
        g.chat.pin(null)
        assertNull(g.chat.pinned)
    }

    @Test fun aRatingTheWindowOrTheAppGivesIsTakenOnceAndOnlyAfterTheEnd() {
        val g = open()
        assertNull("not ended: not taken", g.chat.rate("stars", 4))
        sockets.last().listener.onText("""{"type":"conversation_end"}"""); settle()
        val r = g.chat.rate("stars", 4)
        assertEquals(GraineChatRating(4, 5, "stars"), r)
        assertNull("one per conversation", g.chat.rate("stars", 1))
        assertEquals(r, g.chat.rating)
    }
}
