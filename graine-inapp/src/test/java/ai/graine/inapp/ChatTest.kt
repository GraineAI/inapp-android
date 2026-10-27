package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE TEXT CONVERSATION — the React Native SDK's text path on the native
 * builders, with the socket, the clock and the network faked: the text ticket
 * (or `?text_chat=1`), the frames on open, lines held while connecting and
 * sent in order, the turn markers read, components and actions, every frame
 * the app sends, and every way a conversation ends.
 */
class ChatTest {
    private val sched = ManualScheduler()

    private class FakeSocket : CallSocket {
        val sent = mutableListOf<String>()
        var closed: Int? = null
        override fun send(text: String): Boolean { if (closed != null) return false; sent += text; return true }
        override fun close(code: Int, reason: String) { if (closed == null) closed = code }
        fun frames(): List<JSONObject> = sent.map { JSONObject(it) }
        fun types(): List<String> = frames().map { it.optString("type") }
    }

    private class Events : ChatSessionEvents {
        val log = mutableListOf<String>()
        val widgets = mutableListOf<JSONObject>()
        var unsent: List<String> = emptyList()
        override fun onOpen() { log += "open" }
        override fun onFailed(error: String) { log += "failed" }
        override fun onClosed(code: Int, error: String?, unsent: List<String>) { log += "closed:$code"; this.unsent = unsent }
        override fun onAgentWords(text: String) { log += "words:$text" }
        override fun onAgentTurnStart() { log += "start" }
        override fun onAgentTurnEnd() { log += "end" }
        override fun onWidget(widget: JSONObject) { widgets += widget; log += "widget" }
        override fun onWidgetPending(hint: JSONObject) { log += "pending" }
    }

    private val socket = FakeSocket()
    private val events = Events()
    private var listener: CallSocketListener? = null
    private var openedUrl: String? = null
    private var openedProtocols: List<String>? = null
    private val requests = mutableListOf<Pair<String, JSONObject>>()
    private var ticketReply: () -> HttpReply = { HttpReply(200, """{"ticket":"tkt_text","wsUrl":"wss://ws.graine.ai/v1/s"}""") }
    private var runner: (String, JSONObject, (ActionResult) -> Unit) -> Unit = { _, _, answer -> answer(ActionResult.ok("Done.")) }
    private var screen: JSONObject? = JSONObject().put("context", JSONObject().put("screen", "orders")).put("seq", 4)

    private fun session(outbox: List<String> = emptyList(), identity: IdentityEnvelope? = null): TextChatSession {
        val deps = ChatSessionDeps(
            scheduler = sched,
            http = HttpPost { url, json -> requests += url to JSONObject(json); ticketReply() },
            openSocket = { url, protocols, l -> openedUrl = url; openedProtocols = protocols; listener = l; socket },
            runAction = { name, args, answer -> runner(name, args, answer) },
            contextFrame = { screen },
            io = { it() },
        )
        val config = ChatSessionConfig(
            baseUrl = "https://www.graine.ai",
            publishableKey = "pk_test_key",
            agentId = "agent_1",
            webcallAgentId = "web_1",
            wsHost = "wss://ws.graine.ai",
            variables = mapOf("name" to "Asha", "plan" to "gold"),
            identity = identity,
        )
        return TextChatSession(config, deps, events, outbox)
    }

    private fun open(s: TextChatSession = session()): TextChatSession {
        s.start(); sched.drain()
        listener!!.onOpen("graine.embed.v1"); sched.drain()
        return s
    }

    private fun frame(o: JSONObject) { listener!!.onText(o.toString()); sched.drain() }
    private fun text(data: String) = frame(JSONObject().put("type", "text").put("data", data))

    private fun token(url: String): JSONObject {
        val t = java.net.URLDecoder.decode(url.substringAfter("context_token="), "UTF-8")
        return JSONObject(String(java.util.Base64.getDecoder().decode(t), Charsets.UTF_8))
    }

    @Test fun aTextTicketIsMintedWithWhoTheUserIs() {
        val id = IdentityEnvelope("P-1234", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", false, "Asia/Kolkata")
        open(session(identity = id))
        val (url, body) = requests.single()
        assertEquals("https://www.graine.ai/api/embed/ticket", url)
        assertEquals("text", body.getString("mode"))
        assertEquals("pk_test_key", body.getString("publishableKey"))
        assertEquals("Asha", body.getJSONObject("metadata").getString("name"))
        // Beside the metadata, never inside it.
        assertEquals("P-1234", body.getJSONObject("identity").getString("value"))
        assertFalse(body.getJSONObject("metadata").has("identity"))
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", body.getString("installId"))
        assertFalse(body.getBoolean("memory"))
        assertEquals("Asia/Kolkata", body.getString("timezone"))
        // The ticket rides the subprotocol, and the variables ride the ticket: the gateway reads nothing
        // off its URL, so a query there would only reach the access logs.
        assertEquals(listOf(CallFrames.SUBPROTOCOL, "tkt_text"), openedProtocols)
        assertEquals("wss://ws.graine.ai/v1/s", openedUrl)
    }

    @Test fun withNoTicketTheDirectSocketIsATextChat() {
        ticketReply = { HttpReply(503, """{"error":"Gateway not configured on this deployment."}""") }
        open(session(identity = IdentityEnvelope("P-1234", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", false, "Asia/Kolkata")))
        assertTrue(openedUrl!!.startsWith("wss://ws.graine.ai/chat/v1/web_1?text_chat=1&context_token="))
        // The direct socket reads the variables off its URL, as a text chat.
        val t = token(openedUrl!!)
        assertEquals("text_chat", t.getString("call_type"))
        assertEquals("gold", t.getJSONObject("recipient_data").getString("plan"))
        assertFalse("the identity never becomes a variable", t.toString().contains("P-1234"))
        assertEquals(emptyList<String>(), openedProtocols)
        ticketReply = { throw java.io.IOException("offline") }
        val other = ChatTest()
        other.ticketReply = { throw java.io.IOException("offline") }
        other.open()
        assertTrue(other.openedUrl!!.contains("?text_chat=1"))
    }

    @Test fun onOpenTheInitTheScreenThenTheHeldLinesInOrder() {
        val s = session(outbox = listOf("left over from the last drop"))
        s.start(); sched.drain()
        s.say("Where is my order?")
        s.say("It was due Monday.")
        sched.drain()
        assertTrue("nothing goes out before the socket opens", socket.sent.isEmpty())
        listener!!.onOpen(null); sched.drain()
        assertEquals(listOf("init", "app_context", "text", "text", "text"), socket.types())
        val init = socket.frames()[0]
        assertEquals("start", init.getString("event"))
        assertEquals("graine-android-sdk", init.getJSONObject("meta_data").getString("client"))
        assertEquals("orders", socket.frames()[1].getJSONObject("context").getString("screen"))
        assertEquals(listOf("left over from the last drop", "Where is my order?", "It was due Monday."), socket.frames().drop(2).map { it.getString("data") })
        assertEquals(listOf("open"), events.log)
    }

    @Test fun aPingEveryTwentyFiveSeconds() {
        open()
        val before = socket.sent.size
        sched.advance(24_999)
        assertEquals(before, socket.sent.size)
        sched.advance(1)
        assertEquals("ping", socket.types().last())
        sched.advance(25_000)
        assertEquals(2, socket.types().count { it == "ping" })
    }

    @Test fun theTurnMarkersAreReadAndRemoved() {
        open()
        text("<beginning_of_stream>")
        text("Your order ")
        text("ships today.<end_of_stream>")
        text("Anything else?")
        text("<end_of_stream>")
        assertEquals(listOf("open", "start", "words:Your order ", "words:ships today.", "end", "start", "words:Anything else?", "end"), events.log)
    }

    @Test fun explicitStreamFramesBoundATurnToo() {
        open()
        frame(JSONObject().put("type", "beginning_of_stream"))
        text("Hello")
        frame(JSONObject().put("type", "end_of_stream"))
        frame(JSONObject().put("type", "end_of_stream"))
        assertEquals(listOf("open", "start", "words:Hello", "end"), events.log)
    }

    @Test fun componentsReachTheApp() {
        open()
        frame(JSONObject().put("type", "widget_pending").put("widget", JSONObject().put("widget_id", "w1")))
        frame(JSONObject().put("type", "widget").put("widget", JSONObject().put("widget_id", "w1").put("kind", "choices")))
        frame(JSONObject().put("type", "widget"))
        assertEquals(listOf("open", "pending", "widget"), events.log)
        assertEquals("choices", events.widgets.single().getString("kind"))
    }

    @Test fun actionsAreAnsweredOnEveryPath() {
        open()
        frame(JSONObject().put("type", "app_action").put("action", JSONObject().put("action_id", "a1").put("name", "track_order").put("arguments", JSONObject())))
        var result = socket.frames().last()
        assertEquals("app_action_result", result.getString("type"))
        assertEquals("a1", result.getString("action_id"))
        assertEquals("ok", result.getString("status"))
        assertEquals("Done.", result.getString("message"))
        // A handler that never answers is answered for, before the runtime's six seconds.
        runner = { _, _, _ -> }
        frame(JSONObject().put("type", "app_action").put("action", JSONObject().put("action_id", "a2").put("name", "slow")))
        sched.advance(CallFrames.ACTION_ANSWER_BY_MS)
        result = socket.frames().last()
        assertEquals("a2", result.getString("action_id"))
        assertEquals("error", result.getString("status"))
        runner = { _, _, _ -> throw IllegalStateException("boom") }
        frame(JSONObject().put("type", "app_action").put("action", JSONObject().put("action_id", "a3").put("name", "broken")))
        assertEquals("error", socket.frames().last().getString("status"))
    }

    @Test fun whatTheAppSendsIsTheRuntimesShape() {
        val s = open()
        s.reportEvent("component_dismissed", mapOf("detail" to "closed", "quiet" to true))
        sched.drain()
        val ev = socket.frames().last()
        assertEquals("app_event", ev.getString("type"))
        assertEquals("component_dismissed", ev.getJSONObject("event").getString("name"))
        assertEquals("closed", ev.getJSONObject("event").getString("detail"))
        assertTrue(ev.getJSONObject("event").getBoolean("quiet"))
        s.submitWidget("w1", mapOf("chosen" to "gold", "label" to "Gold"), "I'll go with Gold.")
        sched.drain()
        val two = socket.frames().takeLast(2)
        assertEquals("widget_result", two[0].getString("type"))
        assertEquals("w1", two[0].getString("widget_id"))
        assertEquals("gold", two[0].getJSONObject("data").getString("chosen"))
        assertEquals("the sentence that moves the conversation", "I'll go with Gold.", two[1].getString("data"))
        s.sendContext(JSONObject().put("context", JSONObject().put("screen", "checkout")).put("seq", 9))
        sched.drain()
        assertEquals("checkout", socket.frames().last().getJSONObject("context").getString("screen"))
    }

    @Test fun anEventCarryingANumberJsonCannotHoldStillReachesTheAgent() {
        val s = open()
        s.reportEvent("weighed", mapOf("detail" to "Weighed in", "bmi" to Double.NaN, "cooldown_seconds" to 0))
        sched.drain()
        val ev = socket.frames().last()
        assertEquals("app_event", ev.getString("type"))
        assertEquals("weighed", ev.getJSONObject("event").getString("name"))
        assertTrue("null, as JSON has no NaN — not an event lost on the session's thread", ev.getJSONObject("event").isNull("bmi"))
        assertEquals(0, ev.getJSONObject("event").getInt("cooldown_seconds"))
    }

    @Test fun closingSaysStopThenCloses() {
        val s = open()
        s.close(); sched.drain()
        assertEquals("stop", socket.types().last())
        assertNull(socket.closed)
        sched.advance(CallFrames.CLOSE_AFTER_STOP_MS)
        assertEquals(1000, socket.closed)
        listener!!.onClosed(1000, ""); sched.drain()
        assertEquals("closing is not reported as a drop", listOf("open"), events.log)
    }

    @Test fun aDropHandsBackWhatNeverWentOut() {
        val s = open()
        text("Let me check")
        listener!!.onFailure(java.io.IOException("reset")); sched.drain()
        // The live turn ends with the connection.
        assertEquals(listOf("open", "start", "words:Let me check", "end", "closed:1006"), events.log)
        s.say("are you there?"); sched.drain()
        assertTrue("a closed session sends nothing", socket.types().none { it == "text" })
        val fresh = ChatTest()
        val held = fresh.session(outbox = listOf("are you there?"))
        held.start(); fresh.sched.drain()
        fresh.listener!!.onClosed(1001, "going away"); fresh.sched.drain()
        assertEquals("refused before it opened", listOf("failed"), fresh.events.log)
    }

    @Test fun theServerClosingCleanlyIsReportedWithItsCode() {
        val s = session()
        s.start(); sched.drain()
        s.say("held")
        listener!!.onOpen(null); sched.drain()
        socket.closed = 0 // the socket is gone: the next line cannot be written
        s.say("never written"); sched.drain()
        listener!!.onClosed(1000, ""); sched.drain()
        assertEquals("closed:1000", events.log.last())
        assertEquals(listOf("never written"), events.unsent)
    }

    @Test fun aSocketThatNeverOpensFailsInFifteenSeconds() {
        val s = session()
        s.start(); sched.drain()
        sched.advance(CallFrames.OPEN_TIMEOUT_MS)
        assertEquals(listOf("failed"), events.log)
        assertEquals(1000, socket.closed)
    }

    // ── one bubble per turn ────────────────────────────────────────────────

    @Test fun eachAgentTurnIsItsOwnBubble() {
        val t = ChatTranscript()
        t.customer("Where is my order?", 0)
        assertTrue("the typing indicator, from the moment they send", t.awaitingReply)
        assertFalse("an answer is not volunteered", t.agentTurnStart(1))
        assertTrue("a turn with no words yet is still the typing indicator", t.typing)
        assertTrue("and is not a line", t.lines.none { it.role == Role.AGENT })
        t.agentWords("It ships ", 1)
        assertFalse("words stop the typing indicator", t.awaitingReply)
        assertFalse(t.typing)
        t.agentWords("today.", 1)
        val turn = t.lines.last()
        assertEquals(Role.AGENT, turn.role)
        assertEquals("It ships today.", turn.text)
        assertTrue(turn.live)
        assertEquals(java.util.Date(1), turn.at)
        assertFalse("an answer is not proactive", turn.proactive)
        assertEquals("t2", turn.id)
        t.agentTurnEnd()
        assertEquals("solid once its turn ends", false, t.lines.last().live)
        // A second reply, with nothing typed between: a second bubble, never merged into the first.
        assertTrue("the agent speaking first is volunteering", t.agentTurnStart(2))
        t.agentWords("Anything else?", 2)
        t.agentTurnEnd()
        assertEquals(listOf("Where is my order?", "It ships today.", "Anything else?"), t.lines.map { it.text })
        assertTrue(t.lines.none { it.live })
        assertTrue("the second reply was volunteered", t.lines.last().proactive)
        assertEquals("ids are stable and distinct", t.lines.map { it.id }.toSet().size, t.lines.size)
    }

    @Test fun aCustomersMessageSaysWhereItIs() {
        val t = ChatTranscript()
        t.customer("typed offline", 0, delivery = GraineChatTurn.Delivery.SENDING)
        t.customer("typed offline too", 1, delivery = GraineChatTurn.Delivery.SENDING)
        assertTrue(t.deliver(GraineChatTurn.Delivery.SENDING, GraineChatTurn.Delivery.FAILED))
        assertEquals(listOf(GraineChatTurn.Delivery.FAILED, GraineChatTurn.Delivery.FAILED), t.lines.map { it.delivery })
        t.deliver(GraineChatTurn.Delivery.FAILED, GraineChatTurn.Delivery.SENDING)
        t.deliver(GraineChatTurn.Delivery.SENDING, GraineChatTurn.Delivery.SENT)
        assertTrue(t.lines.all { it.delivery == GraineChatTurn.Delivery.SENT })
        assertFalse("nothing left to move", t.deliver(GraineChatTurn.Delivery.SENDING, GraineChatTurn.Delivery.SENT))
    }

    @Test fun aComponentBelongsToTheTurnThatDrewIt() {
        val widget = GraineWidget.adopt(JSONObject().put("widget_id", "plans:1").put("kind", "choices"), "https://www.graine.ai")!!
        val t = ChatTranscript()
        t.customer("Which plan?", 0)
        t.agentTurnStart(1)
        t.agentWords("Here are your options.", 1)
        t.component(widget, 2)
        assertEquals(widget, t.lines.last().component)
        assertEquals(2, t.lines.size)
        // The agent drew one with nothing said since the customer spoke: a turn of its own, words or not.
        t.customer("And the cheapest?", 3)
        t.component(widget, 4)
        assertEquals(4, t.lines.size)
        assertEquals("", t.lines.last().text)
        assertEquals(widget, t.lines.last().component)
        assertFalse("a component is not the typing indicator", t.typing)
    }

    @Test fun aTurnOfMarkersAloneLeavesNoEmptyBubble() {
        val t = ChatTranscript()
        t.agentTurnStart(0)
        t.agentTurnEnd()
        assertTrue(t.lines.isEmpty())
        t.agentWords("no start marker came", 0)
        assertEquals(1, t.lines.size)
        assertTrue(t.lines[0].live)
        // The customer typing closes whatever the agent was writing.
        t.customer("ok", 5)
        assertEquals(listOf(false, false), t.lines.map { it.live })
    }

    @Test fun theTypingIndicatorGivesUpAfterTwelveSeconds() {
        val t = ChatTranscript()
        t.customer("hello?", 1_000)
        assertEquals(1_000 + Widgets.AWAITING_REPLY_MS, t.nextDeadline())
        assertFalse(t.tick(1_000 + Widgets.AWAITING_REPLY_MS - 1))
        assertTrue(t.tick(1_000 + Widgets.AWAITING_REPLY_MS))
        assertFalse(t.awaitingReply)
        assertNull(t.nextDeadline())
        t.customer("hello again", 20_000)
        t.disconnected()
        assertFalse("a dropped chat is not typing", t.awaitingReply)
    }

    @Test fun theTranscriptIsBounded() {
        val t = ChatTranscript(cap = 3)
        for (i in 1..5) t.customer("line $i", i.toLong())
        assertEquals(listOf("line 3", "line 4", "line 5"), t.lines.map { it.text })
        t.clear()
        assertTrue(t.lines.isEmpty())
    }

    @Test fun aDroppedChatComesBackOnALadderAndStops() {
        val r = ChatReconnect(random = { 0.5 })
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 15_000L), List(6) { r.next()!! })
        assertNull(r.next())
        assertTrue(r.exhausted)
        r.reset()
        assertEquals(1_000L, r.next())
        r.next()
        r.worked()
        assertEquals("a reply came: a later drop starts over", 1_000L, r.next())
        // Jittered: a thousand phones back from one outage do not reconnect in the same second.
        assertEquals(750L, ChatReconnect(random = { 0.0 }).next())
        assertEquals(1_250L, ChatReconnect(random = { 1.0 }).next())
    }

    @Test fun inboundFramesAreTyped() {
        assertTrue(ChatInbound.parse("not json") == null)
        assertEquals(ChatInbound.Ignored, ChatInbound.parse("""{"type":"pong"}"""))
        assertEquals(ChatInbound.Ignored, ChatInbound.parse("""{"type":"app_action"}"""))
        assertEquals(ChatInbound.TurnStart, ChatInbound.parse("""{"type":"beginning_of_stream"}"""))
        assertTrue(ChatInbound.parse("""{"type":"widget_pending"}""") is ChatInbound.WidgetPending)
        assertEquals("wss://ws.graine.ai/chat/v1/a%20b?text_chat=1", ChatUrls.direct(null, "a b"))
    }
}
