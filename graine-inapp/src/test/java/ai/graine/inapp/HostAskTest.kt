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
 * TALK AUTHENTICATES AS THIS APP — the engine page's asks (HostAsk.kt): the
 * website loader's messages, read here; the ticket minted natively through the
 * SDK's own door (EmbedRequest: no Origin, the app's declaration and proofs,
 * the server token); the answers said back as the loader says them; and a real
 * agent on the JVM answering them.
 */
class HostAskTest {
    @Test fun readsTheLoadersQuestionsAndNothingElse() {
        assertEquals(HostAsk.Ask.Session, HostAsk.read("""{"type":"graine:need-session"}"""))
        assertEquals(HostAsk.Ask.Ticket(null, "vouch"), HostAsk.read("""{"type":"graine:need-ticket","purpose":"vouch"}"""))
        assertEquals(HostAsk.Ask.Ticket("voice", null), HostAsk.read("""{"type":"graine:need-ticket","mode":"voice"}"""))
        assertEquals("anything else the page could say is ignored", HostAsk.Ask.Ticket(null, null),
            HostAsk.read("""{"type":"graine:need-ticket","mode":"fax","purpose":"anything"}"""))
        assertNull(HostAsk.read("""{"type":"graine:ready"}"""))
        assertNull(HostAsk.read("not json"))
        assertNull(HostAsk.read(null))
        assertEquals("session,ticket", HostAsk.ANSWERS)
    }

    @Test fun aVouchCarriesNoModeAndTheCallsTicketIsAVoiceTicket() {
        val who = IdentityEnvelope("u-1", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", true, "Asia/Kolkata")
        val vouch = HostAsk.ticketBody("pk_live_x", HostAsk.Ask.Ticket(null, "vouch"), mapOf("first_name" to "Asha"), who)
        assertEquals("vouch", vouch.getString("purpose"))
        assertFalse("a vouch opens no conversation", vouch.has("mode"))
        assertFalse(vouch.has("metadata"))
        assertEquals("who the user is, as every ticket of this SDK's", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", vouch.getString("installId"))
        val voice = HostAsk.ticketBody("pk_live_x", HostAsk.Ask.Ticket("voice", null), mapOf("first_name" to "Asha"), null)
        assertEquals("voice", voice.getString("mode"))
        assertEquals("Asha", voice.getJSONObject("metadata").getString("first_name"))
    }

    @Test fun answersAsTheLoaderDoes() {
        val ok = HostAsk.ticketAnswer(HostAsk.Ask.Ticket("voice", null), HttpReply(200, """{"ticket":"v1.a.b","wsUrl":"wss://ws.graine.ai/embed/v1/web-1"}"""))
        assertEquals("graine:ticket", ok.getString("type"))
        assertEquals("v1.a.b", ok.getString("ticket"))
        assertEquals("wss://ws.graine.ai/embed/v1/web-1", ok.getString("wsUrl"))
        assertEquals("voice", ok.getString("mode"))
        assertFalse(ok.has("purpose"))

        val none = HostAsk.ticketAnswer(HostAsk.Ask.Ticket(null, "vouch"), HttpReply(503, """{"error":"No gateway here."}"""))
        assertTrue("no ticket: the page carries on as it would have", none.isNull("ticket"))
        assertFalse(none.has("error"))
        assertEquals("vouch", none.getString("purpose"))
        assertTrue("no answer at all", HostAsk.ticketAnswer(HostAsk.Ask.Ticket(null, "vouch"), null).isNull("ticket"))

        val refused = HostAsk.ticketAnswer(
            HostAsk.Ask.Ticket(null, "vouch"),
            HttpReply(403, """{"error":"This app is not one of the agent's apps.","code":"app_not_allowed"}"""),
        )
        assertTrue(refused.isNull("ticket"))
        assertEquals("app_not_allowed", refused.getJSONObject("error").getString("code"))
        assertEquals(403, refused.getJSONObject("error").getInt("status"))
        val unverified = HostAsk.ticketAnswer(
            HostAsk.Ask.Ticket(null, "vouch"),
            HttpReply(403, """{"error":"Not verified.","code":"app_not_verified","reason":"missing"}"""),
        )
        assertEquals("missing", unverified.getJSONObject("error").getString("reason"))

        val session = HostAsk.sessionAnswer(JSONObject().put("agentId", "main").put("webcallAgentId", "web"))
        assertEquals("graine:session", session.getString("type"))
        assertEquals("web", session.getJSONObject("session").getString("webcallAgentId"))
        assertTrue(HostAsk.sessionAnswer(null).isNull("session"))
    }

    @Test fun theLogNeverShowsATicketOrASession() {
        val line = HostAsk.redactForLog(HostAsk.ticketAnswer(HostAsk.Ask.Ticket("voice", null), HttpReply(200, """{"ticket":"v1.secret.sig","wsUrl":"wss://ws.graine.ai"}""")))!!
        assertFalse(line.contains("v1.secret.sig"))
        assertTrue(line.startsWith("ticket=set"))
        assertEquals("session=set", HostAsk.redactForLog(HostAsk.sessionAnswer(JSONObject().put("variables", JSONObject().put("first_name", "Asha")))))
        assertNull(HostAsk.redactForLog(JSONObject().put("type", "graine:variables")))
    }

    // ── A real agent answering them ─────────────────────────────────────────────

    private class TestApp : Application() {
        override fun getPackageName(): String = "com.graine.callassistant.kmpdemo"
    }

    private class TestMain(private val clock: ManualScheduler) : MainThread {
        override fun post(r: Runnable): Boolean = postDelayed(r, 0)
        override fun postDelayed(r: Runnable, delayMs: Long): Boolean { clock.schedule(delayMs) { r.run() }; return true }
        override fun removeCallbacks(r: Runnable) {}
    }

    private val clock = ManualScheduler()
    private val dir = java.nio.file.Files.createTempDirectory("graine-host-ask").toFile()
    private val kept = HashMap<String, String>()
    private val requests = mutableListOf<Pair<String, JSONObject>>()
    private var ticketReply: HttpReply = HttpReply(200, """{"ticket":"v1.vouch.sig","wsUrl":"wss://ws.graine.ai/embed/v1/web-1"}""")
    private val served = JSONObject(
        """{"agentId":"agent-1","webcallAgentId":"web-1","appearance":{"title":"Acme","audioTransport":"rtc"},
            "design":{"config":{"preset":"signature"}},"identity":{"field":"user_id"}}""",
    )

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun agent(): GraineInApp {
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { it.run() },
            http = HttpPost { url, json -> requests += url to JSONObject(json); if (url.endsWith("/api/embed/ticket")) ticketReply else HttpReply(200, "{}") },
            fetchSession = { _, _ -> served },
            scheduler = clock,
            ticketIo = { it() },
            device = DeviceStore(File(dir, "device.json")),
            sessionCache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text }),
            fonts = false,
        )
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_live_demo"), GraineInApp.DEFAULT) { env }
        clock.advance(0)
        return g
    }

    @Test fun theAgentHandsThePageTheSessionAsServed() {
        val g = agent()
        var answer: JSONObject? = null
        g.pageSession { answer = HostAsk.sessionAnswer(it) }
        clock.advance(0)
        val s = answer!!.getJSONObject("session")
        assertEquals("web-1", s.getString("webcallAgentId"))
        assertEquals("signature", s.getJSONObject("design").getJSONObject("config").getString("preset"))
    }

    @Test fun theAgentMintsThePagesTicketNativelyThroughItsOwnDoor() {
        val g = agent()
        g.identify(mapOf("user_id" to "u-1")); clock.advance(0)
        var answer: JSONObject? = null
        g.pageTicket(HostAsk.Ask.Ticket(null, "vouch")) { answer = it }
        clock.advance(0)
        assertNotNull(answer)
        assertEquals("v1.vouch.sig", answer!!.getString("ticket"))
        assertEquals("vouch", answer!!.getString("purpose"))
        val (url, body) = requests.last { it.first.endsWith("/api/embed/ticket") }
        assertEquals("https://www.graine.ai/api/embed/ticket", url)
        assertEquals("pk_live_demo", body.getString("publishableKey"))
        assertEquals("vouch", body.getString("purpose"))
        assertEquals("u-1", body.getJSONObject("identity").getString("value"))

        // Refused for this app: said back, never gone around.
        ticketReply = HttpReply(403, """{"error":"This app is not one of the agent's apps.","code":"app_not_allowed"}""")
        g.pageTicket(HostAsk.Ask.Ticket("voice", null)) { answer = it }
        clock.advance(0)
        assertTrue(answer!!.isNull("ticket"))
        assertEquals("app_not_allowed", answer!!.getJSONObject("error").getString("code"))
        assertEquals("voice", answer!!.getString("mode"))
        assertEquals("voice", requests.last { it.first.endsWith("/api/embed/ticket") }.second.getString("mode"))
    }
}
