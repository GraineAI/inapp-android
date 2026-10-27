package ai.graine.inapp

import android.app.Activity
import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * SEVERAL AGENTS IN ONE APP — real GraineInApp agents, side
 * by side on the JVM, with the main thread, the network, the sockets and the
 * device's storage faked: nothing reaches a host. Each agent fetches its own
 * session, mints its own tickets and asks its own routes with its own key;
 * one agent's chat never reaches another's; the user, their memory choice and
 * the screen are the app's; a ring Graine sends rings with the agent it is
 * for, in that agent's design; every agent registers the install. An app
 * with one agent behaves as it always did.
 */
class MultiAgentTest {
    private class TestApp : Application() {
        override fun getPackageName(): String = "com.acme.app"
    }

    /** The main thread on the test's clock: posts run when the test lets time pass. */
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

    private class FakeSocket(val url: String, val protocols: List<String>, val listener: CallSocketListener) : CallSocket {
        val sent = mutableListOf<String>()
        var closed: Int? = null
        override fun send(text: String): Boolean { if (closed != null) return false; sent += text; return true }
        override fun close(code: Int, reason: String) { if (closed == null) closed = code }
    }

    private val clock = ManualScheduler()
    private val main = TestMain(clock)
    private val app = TestApp()
    private val dir = java.nio.file.Files.createTempDirectory("graine-agents").toFile()
    private val device = DeviceStore(File(dir, "device.json"))
    private val kept = HashMap<String, String>()
    private val cache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text })

    /** Every request an agent sent: where, and what it said. */
    private val requests = mutableListOf<Pair<String, JSONObject>>()
    private val fetched = mutableListOf<String>()
    private val sessions = HashMap<String, JSONObject>()
    private val sockets = mutableListOf<FakeSocket>()

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun env() = AgentEnv(
        main = main,
        io = Executor { it.run() },
        http = HttpPost { url, json -> JSONObject(json).let { body -> requests += url to body; reply(url, body) } },
        fetchSession = { _, key -> fetched += key; sessions[key] ?: throw IllegalStateException("offline") },
        openSocket = { url, protocols, listener -> FakeSocket(url, protocols, listener).also { sockets += it } },
        scheduler = clock,
        ticketIo = { it() },
        device = device,
        sessionCache = cache,
        fonts = false,
    )

    private fun reply(url: String, body: JSONObject): HttpReply = when {
        url.endsWith("/api/embed/ticket") ->
            HttpReply(200, JSONObject().put("ticket", "tkt_" + body.getString("publishableKey")).put("wsUrl", "wss://ws.graine.ai/v1/s").toString())
        url.endsWith(EmbedApi.SCHEDULE) ->
            HttpReply(200, JSONObject().put("ok", true).put("call", JSONObject().put("id", "sc_1").put("at", body.getString("at")).put("status", "scheduled").put("source", "sdk")).toString())
        url.endsWith(EmbedApi.LIST) -> HttpReply(200, """{"calls":[]}""")
        else -> HttpReply(200, """{"ok":true}""")
    }

    private fun session(agentId: String, webId: String, accent: String, ringSeconds: Int, canBook: Boolean) = JSONObject(
        """{"agentId":"$agentId","webcallAgentId":"$webId","appearance":{"title":"$agentId"},
            "design":{"light":{"colors":{"accent":"$accent"}}},
            "identity":{"field":"user_id"},
            "calls":{"hosted":true,"platforms":["android"],"ringTimeoutSeconds":$ringSeconds,"callName":"$agentId desk",
                     "scheduling":{"usersCanBook":$canBook}}}""",
    )

    private val supportSession = session("agent-support", "web-support", "#7C3AED", 20, canBook = false)
    private val salesSession = session("agent-sales", "web-sales", "#0EA5E9", 40, canBook = true)

    private fun agent(name: String, key: String): GraineInApp =
        GraineInApp.make(app, GraineConfig("https://www.graine.ai", key), name) { env() }

    /** The default agent (support, pk_support) and a named one (sales, pk_sales), their sessions arrived. */
    private fun twoAgents(): Pair<GraineInApp, GraineInApp> {
        sessions["pk_support"] = supportSession
        sessions["pk_sales"] = salesSession
        val support = agent(GraineInApp.DEFAULT, "pk_support")
        val sales = agent("sales", "pk_sales")
        settle()
        return support to sales
    }

    private fun settle() = clock.advance(0)

    private fun sent(path: String): List<JSONObject> = requests.filter { it.first.endsWith(path) }.map { it.second }

    private fun ring(callId: String, agentId: String?) = mapOf("type" to "agent_call", "call_id" to callId, "hosted" to "1") +
        (if (agentId != null) mapOf("agent_id" to agentId) else emptyMap())

    // ── the agents ───────────────────────────────────────────────────────────

    @Test fun eachAgentHasANameAndFetchesItsOwnSession() {
        val (support, sales) = twoAgents()
        assertEquals(listOf("pk_support", "pk_sales"), fetched)
        assertSame(support, GraineInApp.get())
        assertSame(support, GraineInApp.get(GraineInApp.DEFAULT))
        assertSame(sales, GraineInApp.get("sales"))
        assertSame(sales, GraineInApp.find("sales"))
        assertNull(GraineInApp.find("billing"))
        assertEquals(listOf(GraineInApp.DEFAULT, "sales"), GraineInApp.all.keys.toList())
        assertEquals("sales", sales.name)
        assertEquals("agent-support", support.session!!.agentId)
        assertEquals("agent-sales", sales.session!!.agentId)
        // A second init with a name already made returns that agent, as one init() always did.
        assertSame(sales, agent("sales", "pk_other"))
        assertEquals("pk_sales", GraineInApp.get("sales").config.publishableKey)
        val missing = runCatching { GraineInApp.get("billing") }.exceptionOrNull()
        assertTrue(missing?.message.orEmpty().contains("name = \"billing\""))
        // Their sessions are kept in slots of their own: neither draws the other's look on a cold start.
        assertEquals(2, kept.keys.count { it.startsWith("last.") })
    }

    @Test fun chatOnOneAgentMintsWithItsKeyAndNeverReachesTheOther() {
        val (support, sales) = twoAgents()
        support.chat.send("Where is my order?")
        settle()
        val ticket = sent("/api/embed/ticket").single()
        assertEquals("pk_support", ticket.getString("publishableKey"))
        val first = sockets.single()
        assertEquals(listOf(CallFrames.SUBPROTOCOL, "tkt_pk_support"), first.protocols)
        first.listener.onOpen("graine.embed.v1"); settle()
        assertTrue(support.chat.connected)
        first.listener.onText("""{"type":"text","data":"<beginning_of_stream>"}"""); settle()
        first.listener.onText("""{"type":"text","data":"It ships today.<end_of_stream>"}"""); settle()
        assertEquals(listOf("Where is my order?", "It ships today."), support.chat.messages.map { it.text })
        assertTrue("the other agent's chat never hears it", sales.chat.messages.isEmpty())

        // One conversation at a time: the sales chat opening ends the support chat, whose lines stay.
        sales.chat.send("What does Pro cost?")
        settle()
        assertEquals("pk_sales", sent("/api/embed/ticket").last().getString("publishableKey"))
        assertFalse(support.chat.connected)
        assertEquals("the support chat said goodbye", "stop", JSONObject(first.sent.last()).optString("type"))
        clock.advance(CallFrames.CLOSE_AFTER_STOP_MS)
        assertNotNull("and hung up", first.closed)
        assertEquals(2, support.chat.messages.size)
        assertEquals(listOf("What does Pro cost?"), sales.chat.messages.map { it.text })
        val second = sockets.last()
        assertEquals(listOf(CallFrames.SUBPROTOCOL, "tkt_pk_sales"), second.protocols)
        second.listener.onOpen("graine.embed.v1"); settle()
        second.listener.onText("""{"type":"text","data":"<beginning_of_stream>Pro is 20 a month.<end_of_stream>"}"""); settle()
        assertEquals(listOf("What does Pro cost?", "Pro is 20 a month."), sales.chat.messages.map { it.text })
        assertEquals(listOf("Where is my order?", "It ships today."), support.chat.messages.map { it.text })
    }

    @Test fun anAgentMadeLaterNeverGivesTheNextUserTheLastOnesChoice() {
        // B said "don't remember me" on an earlier launch, on this install.
        device.rememberMemory("user_id", "B", false)
        // Offline at launch: no agent's session has named the trait when A chooses, and B replaces A.
        val support = agent(GraineInApp.DEFAULT, "pk_support")
        settle()
        support.identify(mapOf("user_id" to "A"), memory = true)
        support.identify(mapOf("user_id" to "B"))
        settle()
        sessions["pk_support"] = supportSession
        sessions["pk_billing"] = session("agent-billing", "web-billing", "#16A34A", 30, canBook = false)
        val billing = agent("billing", "pk_billing")
        settle()
        assertEquals("B", billing.identityEnvelope().value)
        assertFalse("A's choice is not B's on an agent made later either", billing.memoryEnabled)
        assertEquals("B's kept choice was not overwritten", false, device.memoryFor("user_id", "B"))
        support.loadSession { }
        settle()
        assertFalse(support.memoryEnabled)
        // The choice made for B, then an agent made later: B's.
        support.setMemory(true)
        sessions["pk_care"] = session("agent-care", "web-care", "#DB2777", 30, canBook = false)
        val care = agent("care", "pk_care")
        settle()
        assertTrue(care.memoryEnabled)
    }

    @Test fun identifyingOnOneAgentIdentifiesTheUserForAllAndNullSignsOutEverywhere() {
        val (support, sales) = twoAgents()
        sales.identify(mapOf("user_id" to "U-1001", "name" to "Asha"))
        settle()
        assertEquals("U-1001", support.identityEnvelope().value)
        assertEquals("U-1001", sales.identityEnvelope().value)
        assertEquals(support.installId, sales.installId)
        assertEquals("Asha", support.currentVariables()["name"])
        assertNull("the identifying trait is only for Graine", support.currentVariables()["user_id"])

        // The memory choice is the user's, whichever agent is told.
        support.setMemory(false)
        assertFalse(sales.memoryEnabled)
        assertFalse(support.memoryEnabled)

        // An agent made later starts with the user the app already identified.
        sessions["pk_billing"] = session("agent-billing", "web-billing", "#16A34A", 30, canBook = false)
        val billing = agent("billing", "pk_billing")
        settle()
        assertEquals("U-1001", billing.identityEnvelope().value)
        assertFalse(billing.memoryEnabled)

        // Signed out on one: every agent releases the device for that user, each with its own key.
        support.identify(null)
        settle()
        val signouts = sent(EmbedApi.SIGNOUT)
        assertEquals(setOf("pk_support", "pk_sales", "pk_billing"), signouts.map { it.getString("publishableKey") }.toSet())
        assertTrue(signouts.all { it.getJSONObject("identity").getString("value") == "U-1001" })
        for (g in listOf(support, sales, billing)) assertNull(g.identityEnvelope().value)
    }

    @Test fun schedulingGoesToTheAgentAskedWithThatAgentsRules() {
        val (support, sales) = twoAgents()
        val at = java.util.Date(System.currentTimeMillis() + 3_600_000)
        var booked: GraineScheduledCall? = null
        sales.scheduleCall(at, "pricing") { call, _ -> booked = call }
        settle()
        val booking = sent(EmbedApi.SCHEDULE).single()
        assertEquals("pk_sales", booking.getString("publishableKey"))
        assertEquals("pricing", booking.getString("reason"))
        assertEquals("sc_1", booked?.id)

        // The support agent does not let users book: refused by its own rules, before any request.
        var refused: GraineError? = null
        support.scheduleCall(at) { _, e -> refused = e }
        settle()
        assertEquals(GraineErrorCode.SCHEDULING_OFF, refused?.code)
        assertEquals(1, sent(EmbedApi.SCHEDULE).size)

        support.scheduledCalls { _, _ -> }
        sales.cancelScheduledCall("sc_1")
        settle()
        assertEquals("pk_support", sent(EmbedApi.LIST).single().getString("publishableKey"))
        assertEquals("pk_sales", sent(EmbedApi.CANCEL).single().getString("publishableKey"))
    }

    // ── calls ────────────────────────────────────────────────────────────────

    @Test fun aRingGraineSendsRingsWithTheAgentItIsForInThatAgentsDesign() {
        val (support, sales) = twoAgents()
        val calls = support.calls
        assertSame("one call engine, whichever agent it is reached through", calls, sales.calls)
        assertTrue(calls.handlePush(ring("c-sales-1", "web-sales")))
        settle()
        assertEquals(GraineCallState.RINGING, calls.state)
        assertSame(sales, calls.callAgent)
        val salesLook = Surfaces.callOf(sales.design!!.light)
        assertEquals(salesLook.avatarFill, calls.callLook().avatarFill)
        assertNotEquals(Surfaces.callOf(support.design!!.light).avatarFill, calls.callLook().avatarFill)
        // Its rules ring it, and its key reports what became of it.
        assertEquals("agent-sales desk", calls.call?.callerName)
        assertEquals(listOf("pk_sales" to "ringing"), sent(EmbedApi.OUTCOME).map { it.getString("publishableKey") to it.getString("outcome") })
        clock.advance(39_999)
        assertEquals("the sales agent rings for 40 s, not support's 20", GraineCallState.RINGING, calls.state)
        clock.advance(1)
        assertEquals(GraineCallState.IDLE, calls.state)
        assertEquals("pk_sales" to "missed", sent(EmbedApi.OUTCOME).last().let { it.getString("publishableKey") to it.getString("outcome") })
    }

    @Test fun aRingMatchesTheAgentIdOrTheWebCallTwinAndOtherwiseTheDefault() {
        val (support, sales) = twoAgents()
        val calls = sales.calls
        calls.handlePush(ring("c-1", "agent-sales")); settle()
        assertSame(sales, calls.callAgent)
        calls.decline(); settle()
        calls.handlePush(ring("c-2", "agent-nobody")); settle()
        assertSame("an agent no configured one is rings with the default", support, calls.callAgent)
        calls.decline(); settle()
        calls.handlePush(ring("c-3", null)); settle()
        assertSame(support, calls.callAgent)
        calls.decline(); settle()
        val declined = sent(EmbedApi.OUTCOME).filter { it.getString("outcome") == "declined" }
        assertEquals(listOf("pk_sales", "pk_support", "pk_support"), declined.map { it.getString("publishableKey") })
    }

    @Test fun aBusyRingIsReportedWithItsOwnAgentsKey() {
        val (_, sales) = twoAgents()
        val calls = sales.calls
        calls.handlePush(ring("c-support", "web-support")); settle()
        calls.handlePush(ring("c-sales", "web-sales")); settle()
        assertEquals("c-support", calls.call?.callId)
        val busy = sent(EmbedApi.OUTCOME).single { it.getString("callId") == "c-sales" }
        assertEquals("pk_sales", busy.getString("publishableKey"))
        assertEquals("missed", busy.getString("outcome"))
    }

    @Test fun aColdStartRingFindsItsAgentInTheSessionKeptOnTheDevice() {
        // The last launch kept both sessions; this one is offline, so only the kept ones are known.
        cache.save("https://www.graine.ai", "pk_support", supportSession)
        cache.save("https://www.graine.ai", "pk_sales", salesSession)
        val support = agent(GraineInApp.DEFAULT, "pk_support")
        val sales = agent("sales", "pk_sales")
        settle()
        assertNull(sales.session)
        val calls = support.calls
        calls.handlePush(ring("c-cold", "web-sales"))
        assertSame(sales, calls.callAgent)
        assertEquals(Surfaces.callOf(ResolvedDesign.parse(salesSession.getJSONObject("design"))!!.light).avatarFill, calls.callLook().avatarFill)
        assertEquals("agent-sales desk", calls.call?.callerName)
    }

    @Test fun aCallIsPlacedWithTheAgentItNames() {
        val (support, sales) = twoAgents()
        val activity = object : Activity() {}
        val calls = support.calls
        assertNull("no agent has that name: no call", calls.startCall(activity, "upgrade", agent = "billing"))
        assertEquals(GraineCallState.IDLE, calls.state)
        val id = calls.startCall(activity, "upgrade", agent = "sales")
        assertNotNull(id)
        assertSame(sales, calls.callAgent)
        assertEquals(GraineCallState.CONNECTING, calls.state)
        assertNull("one call at a time", calls.startCall(activity, "help"))
        calls.end(); settle()
        val again = calls.startCall(activity, "help")
        assertNotNull(again)
        assertSame("the default agent unless named", support, calls.callAgent)
    }

    @Test fun everyAgentRegistersTheInstallWithItsOwnKey() {
        device.pushToken = "fcm-token-1"
        val (support, sales) = twoAgents()
        val first = sent(EmbedApi.DEVICES)
        assertEquals(listOf("pk_support", "pk_sales"), first.map { it.getString("publishableKey") })
        assertTrue(first.all { it.getString("token") == "fcm-token-1" })
        assertNotNull(device.lastRegistration(support.owner))
        assertNotNull(device.lastRegistration(sales.owner))
        // A new token: every agent registers it at once.
        GraineInApp.tokenChanged("fcm-token-2")
        settle()
        val next = sent(EmbedApi.DEVICES).drop(2)
        assertEquals(setOf("pk_support", "pk_sales"), next.map { it.getString("publishableKey") }.toSet())
        assertTrue(next.all { it.getString("token") == "fcm-token-2" })
    }

    // ── the screen ───────────────────────────────────────────────────────────

    @Test fun theScreenIsTheAppsAndEachAgentOffersItsOwnActions() {
        val (support, sales) = twoAgents()
        support.registerAction("open_order") { ActionResult.ok() }
        sales.registerAction("start_trial") { ActionResult.ok() }
        sales.setScreen(GraineScreen("plans"))
        settle()
        assertEquals("plans", support.currentScreen?.screen)
        support.track("compared_plans")
        val supportFrame = support.currentContextFrame()!!
        val salesFrame = sales.currentContextFrame()!!
        assertEquals("plans", supportFrame.getJSONObject("context").getString("screen"))
        assertEquals("plans", salesFrame.getJSONObject("context").getString("screen"))
        assertTrue(supportFrame.toString().contains("compared_plans"))
        assertTrue(salesFrame.toString().contains("compared_plans"))
        assertTrue(supportFrame.toString().contains("open_order") && !supportFrame.toString().contains("start_trial"))
        assertTrue(salesFrame.toString().contains("start_trial") && !salesFrame.toString().contains("open_order"))
        support.clearScreen("plans")
        assertNull(sales.currentScreen)
    }

    // ── one agent, as before ─────────────────────────────────────────────────

    @Test fun anAppWithOneAgentBehavesAsItAlwaysDid() {
        sessions["pk_only"] = supportSession
        device.pushToken = "fcm-token-1"
        // Registered by the build before agents had names, minutes ago, saying exactly this.
        val who = IdentityEnvelope(null, device.installId(), true, IdentityRules.timezone())
        device.registered(DeviceRegistration.fingerprint("fcm-token-1", "com.acme.app", null, GraineVersion.NAME, who), System.currentTimeMillis() - 60_000)
        val only = agent(GraineInApp.DEFAULT, "pk_only")
        settle()
        assertSame(only, GraineInApp.get())
        assertSame(only, GraineInApp.peek())
        assertEquals(GraineInApp.DEFAULT, only.name)
        assertTrue("the record it left is honoured: no new registration", sent(EmbedApi.DEVICES).isEmpty())
        // With one agent there is nothing to choose: any ring rings with it.
        only.calls.handlePush(ring("c-1", "web-elsewhere"))
        assertSame(only, only.calls.callAgent)
        assertEquals("pk_only", sent(EmbedApi.OUTCOME).single().getString("publishableKey"))
    }
}
