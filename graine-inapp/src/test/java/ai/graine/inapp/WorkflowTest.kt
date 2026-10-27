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
 * WORKFLOWS THE APP STARTS — [GraineInApp.startWorkflow] (PUBLIC-STATE.md §7),
 * on real agents with the main thread, the network and the device's storage
 * faked: the run is the identified user's (nobody identified, nothing is
 * asked); who the run is for never leaves the device in `data`; what could
 * never be kept is left out; every call carries an idempotency key, and a
 * request that got no answer is asked once more with the SAME key; Graine's
 * refusals arrive as their codes.
 */
class WorkflowTest {
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

    private val clock = ManualScheduler()
    private val main = TestMain(clock)
    private val app = TestApp()
    private val dir = java.nio.file.Files.createTempDirectory("graine-workflows").toFile()
    private val device = DeviceStore(File(dir, "device.json"))
    private val kept = HashMap<String, String>()
    private val cache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text })
    private val sessions = HashMap<String, JSONObject>()
    private val asked = mutableListOf<JSONObject>()
    /** Background work — the session, every request — held back (a slow network) until [release]. */
    private var holding = false
    private val held = ArrayList<Runnable>()

    /** What the route answers, in order; null throws, as a dropped connection does. Empty: the run. */
    private val replies = ArrayDeque<HttpReply?>()
    private val run = """{"runId":"oex_2c9e8f1a7b3d4c5e6f7a8b9c","workflowId":"owf_8a1d4e7c2b90f1a2b3c4d5e6","alreadyRunning":false}"""

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun env() = AgentEnv(
        main = main,
        io = Executor { if (holding) held += it else it.run() },
        http = HttpPost { url, json ->
            if (!url.endsWith(EmbedApi.START_WORKFLOW)) return@HttpPost HttpReply(200, """{"ok":true}""")
            asked += JSONObject(json)
            val reply = if (replies.isEmpty()) HttpReply(201, run) else replies.removeFirst()
            reply ?: throw java.io.IOException("connection reset")
        },
        fetchSession = { _, key -> sessions[key] ?: throw IllegalStateException("offline") },
        scheduler = clock,
        ticketIo = { it() },
        device = device,
        sessionCache = cache,
        fonts = false,
    )

    private fun settle() = clock.advance(0)

    /** The slow network catches up: everything held runs, and the main thread with it. */
    private fun release() {
        holding = false
        while (held.isNotEmpty()) held.removeAt(0).run()
        settle()
    }

    private fun agent(identityField: String = "user_id"): GraineInApp {
        sessions["pk_test"] = JSONObject("""{"agentId":"agent-1","webcallAgentId":"web-1","identity":{"field":"$identityField"}}""")
        val g = GraineInApp.make(app, GraineConfig("https://www.graine.ai", "pk_test"), GraineInApp.DEFAULT) { env() }
        settle()
        return g
    }

    private fun signedIn(): GraineInApp = agent().also { it.identify(mapOf("user_id" to "P-1234", "name" to "Asha")); settle() }

    /** startWorkflow, waited for: the run, or why not. */
    private fun start(g: GraineInApp, workflow: String, data: Map<String, Any?> = emptyMap(), key: String? = null): Pair<GraineWorkflowRun?, GraineError?> {
        var answer: Pair<GraineWorkflowRun?, GraineError?>? = null
        g.startWorkflow(workflow, data, key) { r, e -> answer = r to e }
        settle()
        clock.advance(Workflows.RETRY_MS)
        settle()
        return answer ?: throw AssertionError("startWorkflow never answered")
    }

    @Test fun theRunIsStartedForTheIdentifiedUserAndAnswered() {
        val g = signedIn()
        val (r, e) = start(g, "  Welcome call ", linkedMapOf("plan" to "gold", "seats" to 3, "trial" to true))
        assertNull(e)
        assertEquals(GraineWorkflowRun("oex_2c9e8f1a7b3d4c5e6f7a8b9c", "owf_8a1d4e7c2b90f1a2b3c4d5e6", false), r)
        val body = asked.single()
        assertEquals("pk_test", body.getString("publishableKey"))
        assertEquals("P-1234", body.getJSONObject("identity").getString("value"))
        assertNotNull(body.optString("installId").takeIf { it.isNotEmpty() })
        assertEquals("trimmed", "Welcome call", body.getString("workflow"))
        val data = body.getJSONObject("data")
        assertEquals(3, data.length())
        assertEquals("gold", data.getString("plan"))
        assertEquals(3, data.getInt("seats"))
        assertEquals(true, data.getBoolean("trial"))
        assertTrue("every call carries a key", Workflows.isIdempotencyKey(body.getString("idempotencyKey")))
    }

    @Test fun aRunAskedForOneUserIsNeverStartedForTheNextOne() {
        holding = true
        val g = agent()
        g.identify(mapOf("user_id" to "A-1"))
        var heard: Pair<GraineWorkflowRun?, GraineError?>? = null
        g.startWorkflow("Welcome call", mapOf("plan" to "gold")) { r, e -> heard = r to e }
        // A signs out and B signs in on the same phone while the agent's settings are still on their way.
        g.identify(null)
        g.identify(mapOf("user_id" to "B-2"))
        release()
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, heard?.second?.code)
        assertTrue(heard?.second?.message.orEmpty().startsWith("Someone else was identified while the agent's settings were loading"))
        assertTrue("no run is started for B, who never asked", asked.isEmpty())
        // The same user identified again (other traits, another spelling) is still the one who asked.
        holding = true
        val again = agent()
        again.identify(mapOf("user_id" to "C-3"))
        var ran: Pair<GraineWorkflowRun?, GraineError?>? = null
        again.startWorkflow("Welcome call") { r, e -> ran = r to e }
        again.identify(mapOf("user_id" to " C-3 ", "name" to "Chitra"))
        release()
        assertNull(ran?.second)
        assertEquals("C-3", asked.single().getJSONObject("identity").getString("value"))
    }

    @Test fun nobodyIdentifiedIsRefusedOnTheDeviceNamingTheTrait() {
        val g = agent(identityField = "patient_id")
        val (_, nobody) = start(g, "Welcome call")
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, nobody?.code)
        assertTrue(nobody!!.message.contains("patient_id"))
        g.identify(mapOf("user_id" to "P-1"))
        settle()
        assertEquals("another trait is nobody for this agent", GraineErrorCode.IDENTITY_REQUIRED, start(g, "Welcome call").second?.code)
        assertTrue("nothing was asked", asked.isEmpty())
    }

    @Test fun aForgedIdNeverLeavesTheDeviceAndWhatCouldNeverBeKeptIsLeftOut() {
        val g = signedIn()
        start(g, "Welcome call", linkedMapOf(
            "user_id" to "someone-else", "user_field" to "email", "reference_id" to "ref-forged", "graine_user_key" to "u1_x",
            "GRAINE_CALL_ID" to "c", "mobile_number" to "+15550000002", "email" to "x@evil.example", "fields" to "x",
            "plan" to "gold", "long" to "x".repeat(201), "emoji" to "🙂".repeat(200),
            "list" to listOf(1, 2), "nested" to mapOf("a" to 1), "nothing" to null, "nan" to Double.NaN, "bad name" to "x",
        ))
        val body = asked.single()
        assertEquals("the identity, untouched by data", "P-1234", body.getJSONObject("identity").getString("value"))
        val data = body.getJSONObject("data")
        assertEquals("an emoji is one character, as Graine counts", setOf("plan", "emoji"), data.keys().asSequence().toSet())
    }

    @Test fun atMostTwelveFieldsGoTheFirstTwelveInTheMapsOrder() {
        val many = LinkedHashMap<String, Any?>().apply { put("list", listOf(1)); for (i in 0 until 15) put("f$i", i) }
        // Which twelve: the map's first. (The JVM's org.json keeps no key order; Android's does — the wire needs none.)
        assertEquals((0 until 12).map { "f$it" }.toSet(), Workflows.data(many).keys().asSequence().toSet())
        assertEquals(0, Workflows.data(null).length())
    }

    @Test fun aWorkflowNameIsNeededAndAKeyMustBeOneBeforeAnythingIsAsked() {
        val g = signedIn()
        for (bad in listOf("", "   ", "w".repeat(121))) {
            assertEquals(bad, GraineErrorCode.INVALID_REQUEST, start(g, bad).second?.code)
        }
        for (bad in listOf("short", "has a space", "k".repeat(129))) {
            assertEquals(bad, GraineErrorCode.INVALID_REQUEST, start(g, "Welcome call", key = bad).second?.code)
        }
        assertTrue(asked.isEmpty())
        assertNotNull(Workflows.name("w".repeat(120)))
    }

    @Test fun noAnswerIsAskedOnceMoreWithTheSameKeyAndARefusalNever() {
        val g = signedIn()
        replies += listOf(null, HttpReply(200, run))
        val (r, e) = start(g, "Welcome call")
        assertNull(e)
        assertEquals("oex_2c9e8f1a7b3d4c5e6f7a8b9c", r?.runId)
        assertEquals(2, asked.size)
        assertEquals("the same key: Graine answers the start it made", asked[0].getString("idempotencyKey"), asked[1].getString("idempotencyKey"))

        asked.clear()
        replies += listOf(null, null)
        assertEquals(GraineErrorCode.NETWORK, start(g, "Welcome call", key = "order-1042:welcome").second?.code)
        assertEquals("once more, and no more", listOf("order-1042:welcome", "order-1042:welcome"), asked.map { it.getString("idempotencyKey") })

        asked.clear()
        replies += HttpReply(503, """{"error":"This workspace can't start workflows right now.","code":"unavailable"}""")
        assertEquals(GraineErrorCode.UNAVAILABLE, start(g, "Welcome call").second?.code)
        assertEquals("Graine answered: not asked again", 1, asked.size)
    }

    @Test fun grainesRefusalsArriveAsTheirCodes() {
        val g = signedIn()
        val cases = listOf(
            Triple(403, "not_allowed", GraineErrorCode.NOT_ALLOWED), Triple(403, "not_verified", GraineErrorCode.NOT_VERIFIED),
            Triple(400, "identity_required", GraineErrorCode.IDENTITY_REQUIRED), Triple(400, "invalid_request", GraineErrorCode.INVALID_REQUEST),
            Triple(503, "unavailable", GraineErrorCode.UNAVAILABLE),
        )
        for ((status, word, code) in cases) {
            replies += HttpReply(status, """{"error":"said $word","code":"$word"}""")
            val (r, e) = start(g, "Welcome call")
            assertNull(r)
            assertEquals(word, code, e?.code)
            assertEquals("said $word", e?.message)
        }
        replies += HttpReply(429, """{"error":"Too many workflows started from this device in the last hour.","code":"rate_limited"}""", 600)
        val limited = start(g, "Welcome call").second
        assertEquals(GraineErrorCode.RATE_LIMITED, limited?.code)
        assertEquals(600, limited?.retryAfterSeconds)
        assertEquals(GraineErrorCode.NOT_ALLOWED, GraineErrorCode.from("not_allowed"))
        assertEquals(GraineErrorCode.NOT_VERIFIED, GraineErrorCode.from(" NOT_VERIFIED "))
    }

    @Test fun theRunIsReadFromTheAnswer() {
        assertEquals(GraineWorkflowRun("oex_1", "owf_1", true), Workflows.run(JSONObject("""{"run_id":"oex_1","workflow_id":"owf_1","already_running":true}""")))
        assertEquals(GraineWorkflowRun("oex_1", null, false), Workflows.run(JSONObject("""{"runId":"oex_1"}""")))
        assertNull(Workflows.run(JSONObject("""{"workflowId":"owf_1"}""")))
        val g = signedIn()
        replies += HttpReply(201, """{"workflowId":"owf_1"}""")
        val (r, e) = start(g, "Welcome call")
        assertNull(r)
        assertFalse(e?.code == GraineErrorCode.NETWORK)
        assertEquals("unreadable_answer", e?.code?.wire)
    }
}
