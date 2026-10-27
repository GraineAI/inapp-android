package ai.graine.inapp

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.concurrent.Executor

/**
 * WHAT STOPPED A CALL THAT NEEDS THE USER — three things an app shows
 * differently:
 *   UNAVAILABLE, NETWORK, INVALID_KEY…  the agent's settings (or Graine) could
 *                       not answer: try again — the session's own reason;
 *   IDENTITY_REQUIRED   nobody is identified by the trait this agent identifies
 *                       users by, and the message names it — the usual mistake
 *                       is identifying them by another one;
 *   OFF                 the agent remembers nobody: `session?.memoryEnabled == false`.
 * memories() and forgetMemory() used to answer IDENTITY_REQUIRED at once
 * whenever the session had not arrived — the trait that names the user comes
 * from it — so a memory screen opened right after launch, or after a failed
 * session, said "Nobody is signed in" to a signed-in user. Every call that
 * needs the session now waits for it and only then decides, and the booking
 * reads say "not loaded", never a wrong final reason, while it is on its way.
 * Real agents on the JVM; the main thread, the network and the session are faked.
 */
class MemoryReportingTest {
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

    private companion object {
        const val BASE = "https://www.graine.ai"
        const val KEY = "pk_memory"
        /** An agent that identifies its users by `patient_id`. */
        const val PATIENT_AGENT = """{"agentId":"agent-1","webcallAgentId":"web-1","identity":{"field":"patient_id"}}"""
        /** The same agent, booked from the app and rung on Android. */
        const val BOOKABLE_AGENT = """{"agentId":"agent-1","webcallAgentId":"web-1","identity":{"field":"patient_id"},
            "calls":{"hosted":true,"platforms":["android"],"scheduling":{"usersCanBook":true,"minLeadMinutes":5,"maxDaysAhead":14}}}"""
    }

    private val clock = ManualScheduler()
    private val dir = java.nio.file.Files.createTempDirectory("graine-memory-reporting").toFile()
    private val kept = HashMap<String, String>()
    private val cache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text })
    /** Every request to Graine, as (path, body). */
    private val requests = mutableListOf<Pair<String, JSONObject>>()
    /** What a route answers next, in order; the last one again when they run out. */
    private val replies = HashMap<String, MutableList<HttpReply>>()

    /** What the session route does when asked: answer this JSON, or throw. */
    private var session: () -> JSONObject = { JSONObject(PATIENT_AGENT) }
    /** How many times the session was asked for. */
    private var sessionAsks = 0
    /** Background work — the session, every request — held back (a slow network) until [release]. */
    private var holding = false
    private val held = ArrayList<Runnable>()

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    /** A real agent, its session asked for and not yet arrived: [settle] or [release] lets it land. */
    private fun agent(): GraineInApp {
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { if (holding) held += it else it.run() },
            http = HttpPost { url, json ->
                val path = url.removePrefix(BASE)
                requests += path to JSONObject(json)
                val queue = replies[path]
                when {
                    queue.isNullOrEmpty() -> HttpReply(200, """{"ok":true}""")
                    queue.size > 1 -> queue.removeAt(0)
                    else -> queue[0]
                }
            },
            fetchSession = { _, _ -> sessionAsks++; session() },
            scheduler = clock,
            ticketIo = { it() },
            device = DeviceStore(File(dir, "device.json")),
            sessionCache = cache,
            fonts = false,
        )
        return GraineInApp.make(TestApp(), GraineConfig(BASE, KEY), GraineInApp.DEFAULT) { env }
    }

    private fun settle() = clock.advance(0)

    /** The slow network catches up: everything held runs, and the main thread with it. */
    private fun release() {
        holding = false
        while (held.isNotEmpty()) held.removeAt(0).run()
        settle()
    }

    private fun reply(path: String, vararg answers: HttpReply) { replies[path] = answers.toMutableList() }

    private fun posted(path: String) = requests.filter { it.first == path }.map { it.second }

    private fun listed(g: GraineInApp): Pair<List<GraineMemory>?, GraineError?> {
        var out: Pair<List<GraineMemory>?, GraineError?>? = null
        g.memories { list, e -> out = list to e }
        settle()
        return out ?: error("memories() never answered")
    }

    // ── waiting for the session, then deciding ──────────────────────────────

    @Test fun aMemoryScreenOpenedBeforeTheSessionWaitsForItInsteadOfSayingNobodyIsIdentified() {
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        reply(EmbedApi.MEMORIES, HttpReply(200, """{"memories":[{"id":"m1","text":"Walks every evening","createdAt":"2026-10-13T09:00:00Z"}]}"""))
        var heard: Pair<List<GraineMemory>?, GraineError?>? = null
        g.memories { list, e -> heard = list to e }
        settle()
        assertNull("it waits for the session — the trait that names the user comes with it", heard)
        release()
        assertNull(heard?.second)
        assertEquals(listOf("m1"), heard?.first?.map { it.id })
        assertEquals("MRN-000981", posted(EmbedApi.MEMORIES).single().getJSONObject("identity").getString("value"))
    }

    @Test fun forgettingBeforeTheSessionWaitsForItToo() {
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        reply(EmbedApi.FORGET, HttpReply(200, """{"deleted":3}"""))
        var answered = false
        var deleted: Int? = null
        var refused: GraineError? = null
        g.forgetMemory { n, e -> answered = true; deleted = n; refused = e }
        settle()
        assertFalse(answered)
        release()
        assertNull(refused)
        assertEquals(3, deleted)
        assertEquals("MRN-000981", posted(EmbedApi.FORGET).single().getJSONObject("identity").getString("value"))
    }

    // ── a request belongs to whoever was identified when it was made ───────

    @Test fun aForgetAskedForOneUserNeverForgetsTheNextOne() {
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "A-1"))
        reply(EmbedApi.FORGET, HttpReply(200, """{"deleted":3}"""))
        var heard: Pair<Int?, GraineError?>? = null
        g.forgetMemory { n, e -> heard = n to e }
        // A signs out and B signs in on the same phone while the session is still on its way.
        g.identify(null)
        g.identify(mapOf("patient_id" to "B-2"))
        release()
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, heard?.second?.code)
        assertTrue(heard?.second?.message.orEmpty().startsWith("Someone else was identified while the agent's settings were loading"))
        assertTrue("B's memories are never forgotten for A", posted(EmbedApi.FORGET).isEmpty())
    }

    @Test fun aBookingAskedForOneUserIsNeverMadeForTheNextOne() {
        session = { JSONObject(BOOKABLE_AGENT) }
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "A-1"))
        var refused: GraineError? = null
        g.scheduleCall(Date(System.currentTimeMillis() + 3_600_000L), "A's blood test results") { _, e -> refused = e }
        g.identify(mapOf("patient_id" to "B-2"))
        release()
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, refused?.code)
        assertTrue("B is never booked a call, with A's reason, that B did not ask for", posted(EmbedApi.SCHEDULE).isEmpty())
    }

    @Test fun signedOutWhileTheSessionLoadsIsIdentityRequiredNamingTheTrait() {
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "A-1"))
        var heard: Pair<List<GraineMemory>?, GraineError?>? = null
        g.memories { list, e -> heard = list to e }
        g.identify(null)
        release()
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, heard?.second?.code)
        assertEquals("Nobody is identified: call identify() with the customer's \"patient_id\" first.", heard?.second?.message)
        assertTrue(posted(EmbedApi.MEMORIES).isEmpty())
    }

    @Test fun aTranscriptAskedForOneUserIsNeverFetchedForTheNextOne() {
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "A-1"))
        var refused: GraineError? = null
        g.chat.sessionTranscript("s-1") { _, e -> refused = e }
        g.identify(mapOf("patient_id" to "B-2"))
        release()
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, refused?.code)
        assertTrue("B's session is never fetched for A's screen", posted(EmbedApi.SESSION_TRANSCRIPT).isEmpty())
    }

    @Test fun theSameUserWrittenAnotherWayWhileTheSessionLoadsStillGetsTheirAnswer() {
        session = { JSONObject("""{"agentId":"agent-1","webcallAgentId":"web-1","identity":{"field":"email"}}""") }
        holding = true
        val g = agent()
        g.identify(mapOf("email" to "Asha@Example.com"))
        reply(EmbedApi.FORGET, HttpReply(200, """{"deleted":2}"""))
        var heard: Pair<Int?, GraineError?>? = null
        g.forgetMemory { n, e -> heard = n to e }
        g.identify(mapOf("email" to "asha@example.com"))
        release()
        assertNull("one user as Graine keys them: not someone else", heard?.second)
        assertEquals(2, heard?.first)
    }

    @Test fun theSameUserIdentifiedAgainWithOtherTraitsStillGetsTheirAnswer() {
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "A-1"))
        reply(EmbedApi.MEMORIES, HttpReply(200, """{"memories":[{"id":"m1","text":"Walks every evening","createdAt":"2026-10-13T09:00:00Z"}]}"""))
        var heard: Pair<List<GraineMemory>?, GraineError?>? = null
        g.memories { list, e -> heard = list to e }
        g.identify(mapOf("patient_id" to "A-1", "name" to "Asha"))
        release()
        assertNull(heard?.second)
        assertEquals(listOf("m1"), heard?.first?.map { it.id })
        assertEquals("A-1", posted(EmbedApi.MEMORIES).single().getJSONObject("identity").getString("value"))
    }

    // ── a session that could not be loaded is its own reason ────────────────

    @Test fun aSessionThatCouldNotLoadIsHandedOnNotReadAsNobodyIdentified() {
        session = { throw IllegalStateException("Graine is busy. Try again in a minute.") }
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        settle()
        val (_, e) = listed(g)
        assertEquals(GraineErrorCode.UNAVAILABLE, e?.code)
        assertEquals("the session's own sentence", "Graine is busy. Try again in a minute.", e?.message)
        var forgot: GraineError? = null
        g.forgetMemory { _, err -> forgot = err }
        settle()
        assertEquals(GraineErrorCode.UNAVAILABLE, forgot?.code)
        assertTrue("nothing was asked of the memory routes", posted(EmbedApi.MEMORIES).isEmpty() && posted(EmbedApi.FORGET).isEmpty())
    }

    @Test fun noAnswerAtAllIsANetworkFailureAndTheNextCallLoadsTheSessionAgain() {
        var up = false
        session = { if (!up) throw java.io.IOException("offline") else JSONObject(PATIENT_AGENT) }
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        settle()
        val asked = sessionAsks
        assertEquals(GraineErrorCode.NETWORK, listed(g).second?.code)
        assertEquals("the call asked for the session again", asked + 1, sessionAsks)
        up = true
        reply(EmbedApi.MEMORIES, HttpReply(200, """{"memories":[]}"""))
        val (list, e) = listed(g)
        assertNull(e)
        assertEquals(emptyList<GraineMemory>(), list)
        assertEquals(asked + 2, sessionAsks)
    }

    @Test fun pastSessionsAndTranscriptsHandOnAFailedSessionToo() {
        session = { throw IllegalStateException("Graine is busy.") }
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        settle()
        g.chat.refreshPastSessions(); settle()
        assertEquals(GrainePastSessions(error = "unavailable"), g.chat.pastSessions)
        var heard: GraineError? = null
        g.chat.sessionTranscript("ex-1") { _, e -> heard = e }; settle()
        assertEquals(GraineErrorCode.UNAVAILABLE, heard?.code)
        assertTrue("nothing was sent", posted(EmbedApi.PAST_SESSIONS).isEmpty() && posted(EmbedApi.SESSION_TRANSCRIPT).isEmpty())
    }

    /** The session route on a loopback port, answering every request with [answer]: the real fetch, over real HTTP. */
    private class SessionRoute(private val answer: () -> Pair<Int, String>) : AutoCloseable {
        private val socket = java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        val base: String get() = "http://127.0.0.1:${socket.localPort}"

        init {
            Thread {
                while (!socket.isClosed) {
                    val conn = runCatching { socket.accept() }.getOrNull() ?: break
                    conn.use { c ->
                        val input = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        var length = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
                        }
                        repeat(length) { input.read() }
                        val (status, body) = answer()
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val head = "HTTP/1.1 $status Answer\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        c.getOutputStream().apply { write(head.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        override fun close() = socket.close()
    }

    @Test fun aRefusedSessionKeepsItsCodeAndItsSentence() {
        val answer = java.util.concurrent.atomic.AtomicReference(401 to """{"error":"Invalid key.","code":"invalid_key"}""")
        SessionRoute { answer.get() }.use { route ->
            fun refused(): GraineError = SessionFetch.refusal(runCatching { SessionFetch.fetchJson(route.base, KEY) }.exceptionOrNull())
            assertEquals(GraineError(GraineErrorCode.INVALID_KEY, "Publishable key was rejected. Check it against Embed & Widgets in the dashboard."), refused())
            answer.set(403 to """{"error":"This agent does not accept this origin.","code":"origin_refused"}""")
            assertEquals(GraineErrorCode.ORIGIN_REFUSED, refused().code)
            answer.set(503 to """{"error":"Graine is busy.","code":"unavailable"}""")
            assertEquals(GraineError(GraineErrorCode.UNAVAILABLE, "Graine is busy."), refused())
            for (gateway in listOf(502, 504)) {
                answer.set(gateway to "")
                assertEquals("$gateway with no code", GraineErrorCode.UNAVAILABLE, refused().code)
            }
            answer.set(500 to "")
            assertEquals("a bare 500 is an answer nobody expected", GraineErrorCode.Unknown("http_500"), refused().code)
        }
        val nobody = java.net.ServerSocket(0).use { it.localPort }
        assertEquals("nothing listening: no answer came back", GraineErrorCode.NETWORK,
            SessionFetch.refusal(runCatching { SessionFetch.fetchJson("http://127.0.0.1:$nobody", KEY) }.exceptionOrNull()).code)
    }

    // ── identity_required names the trait ───────────────────────────────────

    @Test fun identifyingByAnotherTraitIsNamedInTheRefusal() {
        val g = agent()
        g.identify(mapOf("user_id" to "P-1"))
        settle()
        val (_, e) = listed(g)
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, e?.code)
        assertEquals("identify() was given no usable \"patient_id\" — the trait this agent identifies customers by " +
            "(Who is the user? in the agent's memory settings). Until it is, conversations are anonymous and nothing is remembered.", e?.message)
        assertTrue("nothing was asked", posted(EmbedApi.MEMORIES).isEmpty())
    }

    @Test fun nobodyIdentifiedNamesTheTraitToIdentifyThemBy() {
        val g = agent()
        settle()
        var forgot: GraineError? = null
        g.forgetMemory { _, err -> forgot = err }
        settle()
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, forgot?.code)
        assertEquals("Nobody is identified: call identify() with the customer's \"patient_id\" first.", forgot?.message)
        var heard: GraineError? = null
        g.chat.sessionTranscript("ex-1") { _, err -> heard = err }
        settle()
        assertEquals("a transcript says the same", forgot, heard)
    }

    @Test fun theSentenceBeforeAnySessionNamedTheTrait() {
        assertEquals("Nobody is identified: call identify() with the customer's identifier first.",
            IdentityRules.missingIdentityMessage(null, identified = true))
        assertEquals("Nobody is identified: call identify() with the customer's \"user_id\" first.",
            IdentityRules.missingIdentityMessage("user_id", identified = false))
    }

    @Test fun theAgentsOwnSettingSaysWhetherAnyoneIsRemembered() {
        session = { JSONObject("""{"agentId":"agent-1","memory":{"enabled":false}}""") }
        val g = agent()
        settle()
        assertEquals(false, g.session?.memoryEnabled)
    }

    // ── 502 / 504 are unavailable; a bare 500 is not ────────────────────────

    @Test fun everyRefusalReadsTheContractsOneRule() {
        // sdk/contract/http-status.json: the one rule every SDK runs (inapp-platform-contract.md, "A refusal's code").
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/http-status.json").exists() && !File(dir, "contract/http-status.json").exists()) dir = dir.parentFile
        val file = listOfNotNull(dir?.let { File(it, "sdk/contract/http-status.json") }, dir?.let { File(it, "contract/http-status.json") }).first { it.exists() }
        val vectors = JSONObject(file.readText()).getJSONArray("vectors")
        assertTrue(vectors.length() >= 20)
        for (i in 0 until vectors.length()) {
            val v = vectors.getJSONObject(i)
            val heard = EmbedApi.errorFor(v.getInt("status"), v.opt("body") as? JSONObject).code
            val want = if (v.getString("code") == "unknown") GraineErrorCode.Unknown(v.getString("raw")) else GraineErrorCode.from(v.getString("code"))
            assertEquals(v.getString("name"), want, heard)
        }
    }

    @Test fun aGatewayOrATimeoutWithNoCodeIsUnavailableAndABare500IsUnknown() {
        var status = 502
        val api = EmbedApi(BASE, KEY, HttpPost { _, _ -> HttpReply(status, "<html>Bad gateway</html>") })
        for (s in listOf(502, 503, 504)) {
            status = s
            assertEquals("$s", GraineErrorCode.UNAVAILABLE, (api.post(EmbedApi.MEMORIES, JSONObject()) as EmbedReply.Failed).error.code)
        }
        status = 500
        val bare = (api.post(EmbedApi.MEMORIES, JSONObject()) as EmbedReply.Failed).error
        assertEquals("an answer nobody expected, as on every SDK", GraineErrorCode.Unknown("http_500"), bare.code)
        assertEquals("http_500", bare.code.wire)
        assertEquals("a code in the body wins", GraineErrorCode.UNAVAILABLE,
            EmbedApi.errorFor(500, JSONObject("""{"error":"Busy.","code":"unavailable"}""")).code)

        // As an app hears it, from memories().
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        settle()
        for (gateway in listOf(502, 504)) {
            reply(EmbedApi.MEMORIES, HttpReply(gateway, ""))
            assertEquals("$gateway", GraineErrorCode.UNAVAILABLE, listed(g).second?.code)
        }
    }

    @Test fun aBare500IsStillTriedAgainWhereRequestsAreRetried() {
        val g = agent()
        settle()
        reply(EmbedApi.OUTCOME, HttpReply(500, ""), HttpReply(500, ""), HttpReply(200, """{"ok":true}"""))
        var result: EmbedReply? = null
        g.request(EmbedApi.OUTCOME, JSONObject(), retries = HostedOutcomes.RETRY_DELAYS_MS) { r -> result = r }
        clock.advance(10_000)
        assertTrue("the service's own trouble, as before: tried again", result is EmbedReply.Ok)
        assertEquals(3, posted(EmbedApi.OUTCOME).size)
        assertTrue(HostedOutcomes.retryable(EmbedReply.Failed(GraineError(GraineErrorCode.Unknown("http_500"), ""), status = 500)))
        assertFalse("a refusal is an answer", HostedOutcomes.retryable(EmbedReply.Failed(GraineError(GraineErrorCode.INVALID_REQUEST, ""), status = 400)))
        assertFalse("a code the server sent, refused", HostedOutcomes.retryable(EmbedReply.Failed(GraineError(GraineErrorCode.Unknown("brand_new"), ""), status = 409)))
    }

    // ── booking, read before the session ────────────────────────────────────

    @Test fun bookingReadBeforeTheSessionSaysNotLoadedNeverNotIdentified() {
        // The last launch's session, kept on the device: booking on, rung here. This launch's has not arrived.
        cache.save(BASE, KEY, JSONObject(BOOKABLE_AGENT))
        session = { JSONObject(BOOKABLE_AGENT) }
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        settle()
        assertFalse(g.bookingAvailable)
        assertEquals("not yet — never a final answer", "not_loaded", g.bookingUnavailableReason)
        assertNotEquals("never \"sign in\" to someone who may well be signed in", "Sign in to book a call", g.bookingBlocked)
        var onArrival: Pair<Boolean, String?>? = null
        g.addListener(object : GraineListener {
            override fun onSessionReady(session: SessionConfig) { onArrival = g.bookingAvailable to g.bookingUnavailableReason }
        })
        release()
        assertEquals("the listener hears the session arrive, and reads again", true to null, onArrival)
        assertNull(g.bookingBlocked)
    }

    @Test fun nobodyIdentifiedIsAFinalAnswerEvenBeforeTheSession() {
        cache.save(BASE, KEY, JSONObject(BOOKABLE_AGENT))
        holding = true
        val g = agent()
        assertEquals("identify() was given nobody: no session changes that", "not_identified", g.bookingUnavailableReason)
        assertEquals("Sign in to book a call", g.bookingBlocked)
    }

    @Test fun withNothingKeptBookingIsNotLoadedUntilTheSessionArrives() {
        session = { JSONObject(BOOKABLE_AGENT) }
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        assertEquals("not_loaded", g.bookingUnavailableReason)
        assertEquals("Booking a call isn't available right now", g.bookingBlocked)
        release()
        assertTrue(g.bookingAvailable)
        assertNull(g.bookingUnavailableReason)
    }

    @Test fun bookingWhenTheSessionCouldNotLoadSaysWhyNotThatBookingIsOff() {
        session = { throw java.io.IOException("offline") }
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        settle()
        var refused: GraineError? = null
        g.scheduleCall(Date(System.currentTimeMillis() + 3_600_000L)) { _, e -> refused = e }
        settle()
        assertEquals("not scheduling_off: the agent's rules were never read", GraineErrorCode.NETWORK, refused?.code)
        assertTrue(posted(EmbedApi.SCHEDULE).isEmpty())
    }

    // ── the rest of what needs the session ──────────────────────────────────

    @Test fun theBookedCallsListedBeforeTheSessionAreTheUsersNotOnlyThisInstalls() {
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        var listed: List<GraineScheduledCall>? = null
        g.scheduledCalls { calls, _ -> listed = calls }
        release()
        assertEquals(emptyList<GraineScheduledCall>(), listed)
        assertEquals("MRN-000981", posted(EmbedApi.LIST).single().getJSONObject("identity").getString("value"))
    }

    @Test fun aNewConversationBeforeTheSessionLetsGoOfTheUsersConversation() {
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        var finished = false
        var refused: GraineError? = null
        g.chat.newConversation { _, e -> finished = true; refused = e }
        release()
        assertTrue(finished)
        assertNull(refused)
        assertEquals("MRN-000981", posted(EmbedApi.NEW_CONVERSATION).single().getJSONObject("identity").getString("value"))
    }

    @Test fun theUpcomingCallsCardAskedForBeforeTheSessionIsFetchedWhenItArrives() {
        session = { JSONObject(BOOKABLE_AGENT) }
        holding = true
        val g = agent()
        g.identify(mapOf("patient_id" to "MRN-000981"))
        g.chat.refreshUpcomingCalls()
        release()
        assertEquals("MRN-000981", posted(EmbedApi.LIST).single().getJSONObject("identity").getString("value"))
    }
}
