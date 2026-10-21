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
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * PAST SESSIONS (contract/PUBLIC-STATE.md "Past sessions"; vectors
 * sdk/contract/past-sessions.json): every case of every section — a page as
 * the SDK keeps it, refresh and load more on a real agent, a transcript in the
 * SDK's words, and each refusal as the state's error word — then who the list
 * belongs to: cleared when the identity changes, never another user's answer.
 * The main thread, the network and the clock are faked; nothing is skipped.
 */
class PastSessionsTest {

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

    private val contract: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/past-sessions.json").exists() && !File(dir, "contract/past-sessions.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/past-sessions.json"), File(dir, "contract/past-sessions.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/past-sessions.json missing — this contract is never skipped")
        JSONObject(f.readText())
    }

    private fun section(name: String): List<JSONObject> {
        val list = contract.getJSONArray(name)
        assertTrue("section $name is empty", list.length() > 0)
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    private val clock = ManualScheduler()
    private val dir = java.nio.file.Files.createTempDirectory("graine-past-sessions").toFile()
    private val kept = HashMap<String, String>()

    /** Every request to the past-sessions routes, as (path, body). */
    private val asked = mutableListOf<Pair<String, JSONObject>>()
    /** What the list route answers next, in order; the last one again when they run out. */
    private val listAnswers = ArrayDeque<HttpReply>()
    private var transcriptAnswer = HttpReply(200, """{"id":"ex-1","turns":[]}""")
    /** No answer comes back from the past-sessions routes. */
    private var offline = false
    /** Requests held back (a slow network) until [release]. */
    private var holding = false
    private val held = ArrayList<Runnable>()

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun agent(name: String = GraineInApp.DEFAULT): GraineInApp {
        val session = JSONObject("""{"agentId":"agent-1","webcallAgentId":"web-1","appearance":{"title":"Asha"},"identity":{"field":"user_id"}}""")
        var lastList: HttpReply? = null
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { if (holding) held += it else it.run() },
            http = HttpPost { url, json ->
                val path = url.removePrefix("https://www.graine.ai")
                if (offline && (path == EmbedApi.PAST_SESSIONS || path == EmbedApi.SESSION_TRANSCRIPT)) throw java.io.IOException("offline")
                when (path) {
                    EmbedApi.PAST_SESSIONS -> {
                        asked += path to JSONObject(json)
                        (listAnswers.removeFirstOrNull() ?: lastList ?: HttpReply(200, """{"sessions":[],"next":null}""")).also { lastList = it }
                    }
                    EmbedApi.SESSION_TRANSCRIPT -> { asked += path to JSONObject(json); transcriptAnswer }
                    else -> HttpReply(200, """{"ok":true}""")
                }
            },
            fetchSession = { _, _ -> session },
            scheduler = clock,
            ticketIo = { it() },
            device = DeviceStore(File(dir, "device.json")),
            sessionCache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text }),
            fonts = false,
        )
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_sessions"), name) { env }
        settle()
        return g
    }

    private fun settle() = clock.advance(0)

    private fun release() {
        holding = false
        while (held.isNotEmpty()) held.removeAt(0).run()
        settle()
    }

    private fun signedIn(user: String = "u-meera"): GraineInApp = agent().also { it.identify(mapOf("user_id" to user, "name" to "Meera")); settle() }

    private fun ok(body: Any): HttpReply = HttpReply(200, body.toString())

    private fun date(raw: Any?): java.util.Date? = (raw as? String)?.let { java.util.Date(Instant.parse(it).toEpochMilli()) }

    /** A session as the contract writes what the SDK keeps. */
    private fun expectedSession(o: JSONObject) = GraineSessionSummary(
        id = o.getString("id"),
        startedAt = date(o.get("startedAt"))!!,
        endedAt = date(o.opt("endedAt")),
        durationSeconds = (o.opt("durationSeconds") as? Number)?.toInt(),
        channel = GraineSessionChannel.values().first { it.wire == o.getString("channel") },
        direction = (o.opt("direction") as? String)?.let { w -> GraineSessionDirection.values().first { it.wire == w } },
        status = o.getString("status"),
        turns = o.getInt("turns"),
        preview = o.getString("preview"),
    )

    private fun sessions(a: JSONArray): List<GraineSessionSummary> = (0 until a.length()).map { expectedSession(a.getJSONObject(it)) }

    // ── the contract's vectors ──────────────────────────────────────────────

    @Test fun everyPageIsKeptAsTheContractSays() {
        for (c in section("pages")) {
            val name = c.getString("name")
            val page = PastSessionsWire.page(c.getJSONObject("response"))
            assertEquals(name, sessions(c.getJSONArray("sessions")), page.sessions)
            assertEquals(name, c.opt("next") as? String, page.next)
        }
    }

    @Test fun everyAppendCaseRunsOnARealChat() {
        for (c in section("append")) {
            AppShared.reset()
            asked.clear()
            val name = c.getString("name")
            val g = signedIn()
            val steps = c.getJSONArray("steps")
            val states = c.getJSONArray("states")
            assertEquals(name, steps.length(), states.length())
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val refresh = step.has("refresh")
                listAnswers.addLast(ok(step.getJSONObject(if (refresh) "refresh" else "more")))
                if (refresh) g.chat.refreshPastSessions() else g.chat.loadMorePastSessions()
                settle()
                val want = states.getJSONObject(i)
                val ids = want.getJSONArray("ids").let { a -> (0 until a.length()).map { a.getString(it) } }
                val state = g.chat.pastSessions
                assertEquals("$name, step ${i + 1}: ids", ids, state.sessions.map { it.id })
                assertEquals("$name, step ${i + 1}: hasMore", want.getBoolean("hasMore"), state.hasMore)
                assertFalse("$name, step ${i + 1}: not loading", state.loading)
                assertNull("$name, step ${i + 1}: no error", state.error)
            }
            assertEquals("$name: one request a step", steps.length(), asked.size)
        }
    }

    @Test fun everyTranscriptIsReadInTheSdksWords() {
        for (c in section("transcripts")) {
            val name = c.getString("name")
            val response = c.getJSONObject("response")
            val t = PastSessionsWire.transcript(response, "asked")
            assertEquals(name, response.getString("id"), t.id)
            val want = c.getJSONArray("turns").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                    GraineSessionTurn(if (o.getString("role") == "agent") Role.AGENT else Role.CUSTOMER, o.getString("text"), date(o.getString("at"))!!)
                }
            }
            assertEquals(name, want, t.turns)
            assertEquals(name, date(response.get("startedAt")), t.startedAt)
            assertEquals(name, date(response.get("endedAt")), t.endedAt)
            assertEquals(name, response.getInt("durationSeconds"), t.durationSeconds)
            assertEquals(name, GraineSessionChannel.CHAT, t.channel)
            assertNull(name, t.direction)
            assertEquals(name, "completed", t.status)
        }
    }

    @Test fun everyRefusalIsTheStatesErrorWordAndTheTranscriptsCode() {
        for (c in section("refusals")) {
            AppShared.reset()
            val name = c.getString("name")
            val reply = HttpReply(c.getInt("status"), c.getJSONObject("body").toString())
            val g = signedIn()
            listAnswers.clear()
            listAnswers.addLast(reply)
            g.chat.refreshPastSessions(); settle()
            assertEquals(name, c.getString("error"), g.chat.pastSessions.error)
            assertFalse(name, g.chat.pastSessions.loading)

            transcriptAnswer = reply
            var heard: GraineError? = null
            g.chat.sessionTranscript("ex-7a1c0001") { _, e -> heard = e }; settle()
            assertEquals(name, c.getString("error"), heard?.code?.wire)
        }
    }

    // ── the wire ────────────────────────────────────────────────────────────

    @Test fun aRefreshAsksForTheFirstPageAsTheMemoryListIsAsked() {
        val g = signedIn()
        g.chat.refreshPastSessions(); settle()
        val (path, body) = asked.single()
        assertEquals("/api/embed/sessions/list", path)
        assertEquals("pk_sessions", body.getString("publishableKey"))
        assertEquals(g.installId, body.getString("installId"))
        assertEquals("u-meera", body.getJSONObject("identity").getString("value"))
        assertFalse("the first page names no cursor", body.has("cursor"))
    }

    @Test fun loadMoreAsksFromTheCursorAndAppends() {
        val g = signedIn()
        listAnswers.addLast(ok("""{"sessions":[{"id":"a1","startedAt":"2026-10-21T10:00:00Z"}],"next":"c2"}"""))
        listAnswers.addLast(ok("""{"sessions":[{"id":"a2","startedAt":"2026-10-20T10:00:00Z"}],"next":null}"""))
        g.chat.refreshPastSessions(); settle()
        g.chat.loadMorePastSessions(); settle()
        assertEquals("c2", asked.last().second.getString("cursor"))
        assertEquals(listOf("a1", "a2"), g.chat.pastSessions.sessions.map { it.id })
        assertFalse(g.chat.pastSessions.hasMore)
        // The end: nothing more to ask for.
        g.chat.loadMorePastSessions(); settle()
        assertEquals(2, asked.size)
    }

    @Test fun loadMoreBeforeAnyRefreshAndWhileLoadingAsksNothing() {
        val g = signedIn()
        g.chat.loadMorePastSessions(); settle()
        assertTrue("no cursor yet", asked.isEmpty())
        assertEquals(GrainePastSessions(), g.chat.pastSessions)

        listAnswers.addLast(ok("""{"sessions":[{"id":"a1","startedAt":"2026-10-21T10:00:00Z"}],"next":"c2"}"""))
        g.chat.refreshPastSessions(); settle()
        holding = true
        g.chat.loadMorePastSessions(); settle()
        assertTrue("loading", g.chat.pastSessions.loading)
        g.chat.loadMorePastSessions(); settle()
        release()
        assertEquals("one load-more while one was on its way", 2, asked.size)
        assertFalse(g.chat.pastSessions.loading)
    }

    @Test fun whileARefreshLoadsWhatWasLoadedStays() {
        val g = signedIn()
        listAnswers.addLast(ok("""{"sessions":[{"id":"a1","startedAt":"2026-10-21T10:00:00Z"}],"next":null}"""))
        g.chat.refreshPastSessions(); settle()
        holding = true
        listAnswers.addLast(ok("""{"sessions":[{"id":"a9","startedAt":"2026-10-22T10:00:00Z"}],"next":null}"""))
        g.chat.refreshPastSessions(); settle()
        assertEquals(GrainePastSessions(g.chat.pastSessions.sessions, loading = true), g.chat.pastSessions)
        assertEquals(listOf("a1"), g.chat.pastSessions.sessions.map { it.id })
        release()
        assertEquals(listOf("a9"), g.chat.pastSessions.sessions.map { it.id })
    }

    @Test fun aRefusalKeepsWhatWasLoadedAndLoadMoreAsksForTheSamePageAgain() {
        val g = signedIn()
        listAnswers.addLast(ok("""{"sessions":[{"id":"a1","startedAt":"2026-10-21T10:00:00Z"}],"next":"c2"}"""))
        listAnswers.addLast(HttpReply(429, """{"error":"Slow down.","code":"rate_limited"}"""))
        listAnswers.addLast(ok("""{"sessions":[{"id":"a2","startedAt":"2026-10-20T10:00:00Z"}],"next":null}"""))
        g.chat.refreshPastSessions(); settle()
        g.chat.loadMorePastSessions(); settle()
        assertEquals(GrainePastSessions(g.chat.pastSessions.sessions, loading = false, hasMore = true, error = "rate_limited"), g.chat.pastSessions)
        assertEquals(listOf("a1"), g.chat.pastSessions.sessions.map { it.id })
        g.chat.loadMorePastSessions(); settle()
        assertEquals("the same page again", "c2", asked.last().second.getString("cursor"))
        assertEquals(listOf("a1", "a2"), g.chat.pastSessions.sessions.map { it.id })
        assertNull(g.chat.pastSessions.error)
    }

    @Test fun noAnswerIsNetwork() {
        val g = signedIn()
        offline = true
        g.chat.refreshPastSessions(); settle()
        assertEquals(GrainePastSessions(error = "network"), g.chat.pastSessions)
        var heard: GraineError? = null
        g.chat.sessionTranscript("ex-1") { _, e -> heard = e }; settle()
        assertEquals(GraineErrorCode.NETWORK, heard?.code)
    }

    @Test fun nobodySignedInIsIdentityRequiredAndNothingIsAsked() {
        val g = agent()
        g.chat.refreshPastSessions(); settle()
        assertEquals(GrainePastSessions(error = "identity_required"), g.chat.pastSessions)
        var heard: GraineError? = null
        g.chat.sessionTranscript("ex-1") { _, e -> heard = e }; settle()
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, heard?.code)
        assertTrue("nothing was sent", asked.isEmpty())
    }

    // ── the transcript ──────────────────────────────────────────────────────

    @Test fun aTranscriptIsAskedForByIdAndAnswersOnce() {
        val g = signedIn()
        transcriptAnswer = ok(section("transcripts").first().getJSONObject("response"))
        var answers = 0
        var got: GraineSessionTranscript? = null
        g.chat.sessionTranscript(" ex-7a1c0001 ") { t, e -> answers++; got = t; assertNull(e) }; settle()
        val (path, body) = asked.single()
        assertEquals("/api/embed/sessions/transcript", path)
        assertEquals("ex-7a1c0001", body.getString("id"))
        assertEquals("u-meera", body.getJSONObject("identity").getString("value"))
        assertEquals(g.installId, body.getString("installId"))
        assertEquals("pk_sessions", body.getString("publishableKey"))
        assertEquals(1, answers)
        assertEquals(3, got?.turns?.size)
        assertEquals(Role.AGENT, got?.turns?.first()?.role)
    }

    @Test fun aBlankIdIsRefusedWithoutAsking() {
        val g = signedIn()
        var heard: GraineError? = null
        g.chat.sessionTranscript("  ") { _, e -> heard = e }; settle()
        assertEquals(GraineErrorCode.INVALID_REQUEST, heard?.code)
        assertTrue(asked.isEmpty())
    }

    @Test fun aTranscriptThatArrivesAfterTheIdentityChangedIsNotThisUsers() {
        val g = signedIn()
        transcriptAnswer = ok(section("transcripts").first().getJSONObject("response"))
        holding = true
        var heard: GraineError? = null
        var got: GraineSessionTranscript? = null
        g.chat.sessionTranscript("ex-7a1c0001") { t, e -> got = t; heard = e }; settle()
        g.identify(mapOf("user_id" to "u-arjun")); settle()
        release()
        assertNull("another user's chat history is never handed over", got)
        assertEquals(GraineErrorCode.NOT_FOUND, heard?.code)
    }

    @Test fun theSuspendingTranscriptAnswersOrThrows() {
        val g = signedIn()
        transcriptAnswer = ok(section("transcripts").first().getJSONObject("response"))
        var result: Result<GraineSessionTranscript>? = null
        val ask: suspend () -> GraineSessionTranscript = { g.chat.sessionTranscript("ex-7a1c0001") }
        ask.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        settle()
        assertEquals("ex-7a1c0001", result?.getOrNull()?.id)

        transcriptAnswer = HttpReply(404, """{"error":"Session not found.","code":"not_found"}""")
        result = null
        ask.startCoroutine(Continuation(EmptyCoroutineContext) { result = it })
        settle()
        val thrown = result?.exceptionOrNull() as GraineRequestException
        assertEquals(GraineErrorCode.NOT_FOUND, thrown.code)
        assertEquals("Session not found.", thrown.message)
    }

    // ── who the list belongs to ─────────────────────────────────────────────

    private fun loaded(g: GraineInApp) {
        listAnswers.addLast(ok("""{"sessions":[{"id":"a1","startedAt":"2026-10-21T10:00:00Z","preview":"Hi"}],"next":"c2"}"""))
        g.chat.refreshPastSessions(); settle()
        assertEquals(listOf("a1"), g.chat.pastSessions.sessions.map { it.id })
    }

    @Test fun anotherUserClearsThePastSessions() {
        val g = signedIn()
        loaded(g)
        var heard = 0
        g.chat.addChangeListener { heard++ }
        g.identify(mapOf("user_id" to "u-arjun")); settle()
        assertEquals("cleared", GrainePastSessions(), g.chat.pastSessions)
        assertTrue("the change listeners heard it", heard > 0)
        // And the cursor went with them: nothing more of the last user's to load.
        g.chat.loadMorePastSessions(); settle()
        assertEquals(1, asked.size)
    }

    @Test fun signingOutClearsThePastSessions() {
        val g = signedIn()
        loaded(g)
        g.identify(null); settle()
        assertEquals(GrainePastSessions(), g.chat.pastSessions)
    }

    @Test fun theSameUserWithOtherTraitsOrAnotherMemoryChoiceKeepsThem() {
        val g = signedIn()
        loaded(g)
        val before = g.chat.pastSessions
        g.identify(mapOf("user_id" to "u-meera", "name" to "Meera K", "plan" to "gold")); settle()
        g.setMemory(false); settle()
        assertEquals(before, g.chat.pastSessions)
    }

    @Test fun aPageOnItsWayForTheLastUserIsNeverShown() {
        val g = signedIn()
        holding = true
        listAnswers.addLast(ok("""{"sessions":[{"id":"meeras","startedAt":"2026-10-21T10:00:00Z"}],"next":"c2"}"""))
        g.chat.refreshPastSessions(); settle()
        assertTrue(g.chat.pastSessions.loading)
        g.identify(mapOf("user_id" to "u-arjun")); settle()
        assertEquals(GrainePastSessions(), g.chat.pastSessions)
        release()
        assertEquals("the last user's page was dropped", GrainePastSessions(), g.chat.pastSessions)
    }

    @Test fun aLoadMoreOnItsWayForTheLastUserIsNeverShown() {
        val g = signedIn()
        loaded(g)
        holding = true
        listAnswers.addLast(ok("""{"sessions":[{"id":"meeras-older","startedAt":"2026-10-20T10:00:00Z"}],"next":null}"""))
        g.chat.loadMorePastSessions(); settle()
        g.identify(null); settle()
        release()
        assertEquals(GrainePastSessions(), g.chat.pastSessions)
    }

    @Test fun aNewerRefreshWinsOverAnOlderOne() {
        val g = signedIn()
        holding = true
        listAnswers.addLast(ok("""{"sessions":[{"id":"old","startedAt":"2026-10-21T10:00:00Z"}],"next":null}"""))
        listAnswers.addLast(ok("""{"sessions":[{"id":"new","startedAt":"2026-10-22T10:00:00Z"}],"next":null}"""))
        g.chat.refreshPastSessions(); settle()
        g.chat.refreshPastSessions(); settle()
        release()
        assertEquals(listOf("new"), g.chat.pastSessions.sessions.map { it.id })
    }

    @Test fun eachAgentKeepsItsOwnAndOneIdentifyClearsEveryOne() {
        val support = signedIn()
        val sales = agent("sales")
        loaded(support)
        assertTrue("another agent's list is its own", sales.chat.pastSessions.sessions.isEmpty())
        loaded(sales)
        assertEquals("pk_sessions", asked.last().second.getString("publishableKey"))
        // identify() on any agent is the app's user changing: every agent's list goes.
        sales.identify(mapOf("user_id" to "u-arjun")); settle()
        assertEquals(GrainePastSessions(), support.chat.pastSessions)
        assertEquals(GrainePastSessions(), sales.chat.pastSessions)
    }
}
