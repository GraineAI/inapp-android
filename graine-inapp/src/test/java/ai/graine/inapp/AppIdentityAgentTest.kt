@file:OptIn(GraineRtcInternal::class)

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
 * YOUR APP AND YOUR USERS, THROUGH REAL AGENTS (APP-IDENTITY.md §1, §2, §6):
 * every request the SDK makes — the session, both tickets, rtc-session (a
 * full mint and a grant renewal), devices, sign-out, booked calls and their
 * outcomes, memories, a new conversation, past sessions, the app's proofs —
 * says which app it is; the identity token rides inside the identity and
 * nowhere else; an account change takes the old token and pass with it; a
 * ring refreshes the app proof and the token before anyone answers; an app
 * the agent does not take ends the chat and the call, for good; and what
 * Graine has proven is heard on the main thread. With the main thread, the
 * network, the sockets and the device faked: nothing reaches a host.
 */
class AppIdentityAgentTest {
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

    private class FakeSocket(val url: String, val protocols: List<String>, val listener: CallSocketListener) : CallSocket {
        val sent = mutableListOf<String>()
        override fun send(text: String): Boolean { sent += text; return true }
        override fun close(code: Int, reason: String) {}
    }

    private val clock = ManualScheduler()
    private var wall = 1_800_000_000_000L
    private val main = TestMain(clock)
    private val app = TestApp()
    private val dir = java.nio.file.Files.createTempDirectory("graine-app-identity").toFile()
    private val device = DeviceStore(File(dir, "device.json"))
    private val kept = HashMap<String, String>()
    private val cache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text })

    private val embed = FakeEmbed()
    private val sockets = mutableListOf<FakeSocket>()
    private val logs = mutableListOf<String>()
    private var prover: IntegrityProver? = null
    private var session = session()

    private fun session(app: String = "off", user: String = "off"): JSONObject = JSONObject(
        """{"agentId":"agent-a","webcallAgentId":"web-a","appearance":{"title":"Help"},
            "identity":{"field":"user_id"},
            "security":{"app":{"verify":"$app"},"user":{"verify":"$user","method":"hmac"}},
            "calls":{"hosted":true,"platforms":["android"],"ringTimeoutSeconds":30,
                     "scheduling":{"usersCanBook":true}}}""",
    )

    private val proofs by lazy {
        Proofs(
            AppProofs(
                declaration = { AppDeclaration("com.acme.app", listOf(AppProofsTest.CERT)) },
                store = AppPassStore.memory(),
                device = { device },
                debuggable = { false },
                prover = { prover },
                worker = clock,
                clock = { wall + clock.now() },
                changed = { key -> AppShared.verificationChanged(key) },
                log = { logs += it },
                enforceWaitMs = 50,
            ),
            UserProof(
                clock = { wall + clock.now() },
                callOn = { r -> main.post(r) },
                changed = { AppShared.verificationChanged(null) },
                tokenChanged = { AppShared.identityTokenChanged() },
            ),
            fieldFor = { key -> AppShared.identityFieldFor(key) },
            refreshWaitMs = 50,
        )
    }

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        CallLog.sink = null
        CallLog.enabled = false
        dir.deleteRecursively()
    }

    private fun env() = AgentEnv(
        main = main,
        io = Executor { it.run() },
        http = embed,
        fetchSession = { _, _ -> session },
        openSocket = { url, protocols, listener -> FakeSocket(url, protocols, listener).also { sockets += it } },
        scheduler = clock,
        ticketIo = { it() },
        device = device,
        sessionCache = cache,
        fonts = false,
        proofs = { proofs },
    )

    private fun agent(): GraineInApp = GraineInApp.make(app, GraineConfig("https://www.graine.ai", KEY), GraineInApp.DEFAULT) { env() }.also { settle() }

    private fun settle() = clock.advance(0)

    private val asha = mapOf("user_id" to "user-123", "name" to "Asha", "plan" to "gold")

    // ── the declaration on every request ────────────────────────────────────

    @Test fun everyRequestAnAgentMakesSaysWhichAppItIs() {
        device.pushToken = "fcm-token-1"
        val g = agent()
        g.identify(asha, null, "tok-asha-1"); settle()
        g.memories { _, _ -> }; settle()
        g.forgetMemory(); settle()
        g.scheduleCall(java.util.Date(System.currentTimeMillis() + 3_600_000)); settle()
        g.scheduledCalls { _, _ -> }; settle()
        g.cancelScheduledCall("sc_1"); settle()
        g.chat.refreshPastSessions(); settle()
        g.chat.sessionTranscript("ex-1") { _, _ -> }; settle()
        g.chat.newConversation(); settle()
        g.chat.send("Where is my order?"); settle()
        g.calls.handlePush(mapOf("type" to "agent_call", "call_id" to "c-1", "hosted" to "1", "agent_id" to "agent-a")); settle()
        g.calls.decline(); settle()
        g.identify(null); settle()
        val paths = embed.sent.map { it.path }.toSet()
        for (p in listOf("devices", "memory/list", "memory/forget", "calls/schedule", "calls/list", "calls/cancel", "sessions/list",
            "sessions/transcript", "conversation/new", "ticket", "calls/outcome", "identity/signout")) {
            assertTrue("$p was asked (asked: $paths)", p in paths)
        }
        for (s in embed.sent) {
            val a = s.body.optJSONObject("app")
            assertNotNull("${s.path} says which app it is", a)
            assertEquals("android", a!!.getString("platform"))
            assertEquals("com.acme.app", a.getString("id"))
            assertEquals(listOf(AppProofsTest.CERT), (0 until a.getJSONArray("certs").length()).map { a.getJSONArray("certs").getString(it) })
        }
    }

    @Test fun theSessionTheCallTicketAndBothMintsSayItToo() {
        agent()
        // The session.
        val asked = mutableListOf<JSONObject>()
        SessionFetch.fetchJson("https://www.graine.ai", KEY) { _, json -> asked += JSONObject(json); HttpReply(200, session.toString()) }
        assertEquals("com.acme.app", asked.single().getJSONObject("app").getString("id"))
        // A native call's ticket.
        val call = VoiceCallSession(
            CallSessionConfig("https://www.graine.ai", KEY, "agent-a", "web-a", null, emptyMap(), emptyMap(), callId = "call_1"),
            CallSessionDeps(clock, SilentAudio(), http = embed, openSocket = { u, p, l -> FakeSocket(u, p, l).also { sockets += it } }, io = { it() }),
            object : CallSessionEvents {},
        )
        call.start(); settle()
        assertEquals("com.acme.app", embed.to("ticket").last().getJSONObject("app").getString("id"))
        call.hangUp(); settle()
        // rtc-session: a full mint…
        val rtc = RtcCallSession(
            RtcCallRequest("https://www.graine.ai", KEY, emptyMap(), null, "call_2", null),
            RtcEnv(scheduler = clock, createPeer = { _, l -> FakePeer().also { it.listener = l } }, http = embed, io = { it() },
                openSip = { _, _, l -> FakeSipSocket().also { it.listener = l } }, openControl = { _, l -> FakeSipSocket().also { it.listener = l } }),
        )
        rtc.prepare(); settle()
        rtc.hangUp(); settle()
        val mint = embed.to("rtc-session").single()
        assertEquals("com.acme.app", mint.getJSONObject("app").getString("id"))
        // …and a grant renewal.
        val grants = mutableListOf<JSONObject>()
        val direct = RtcDirectMint(
            http = object : RtcDirectHttp {
                override fun get(url: String, timeoutMs: Int) = HttpReply(200, "{\"direct\":true}")
                override fun post(url: String, json: String, timeoutMs: Int): HttpReply { grants += JSONObject(json); return HttpReply(200, "{}") }
            },
            clock = { wall }, background = { it() }, timers = ManualScheduler(), newCallId = { "call_next_1" },
        )
        direct.warm("https://www.graine.ai", KEY, IdentityEnvelope("user-123", "inst-1", true, "UTC"))
        val grant = grants.single()
        assertTrue(grant.getBoolean("grant_only"))
        assertEquals("com.acme.app", grant.getJSONObject("app").getString("id"))
    }

    // ── the identity token ──────────────────────────────────────────────────

    @Test fun theTokenRidesInsideTheIdentityAndNowhereElse() {
        val lines = mutableListOf<String>()
        CallLog.enabled = true
        CallLog.sink = { m, t -> lines += m + (t?.message ?: "") }
        val g = agent()
        val token = "tok-6c249fec755515370f7f8bbc"
        g.identify(asha, null, token); settle()
        g.setScreen(GraineScreen("checkout", fields = listOf(GraineField("card", label = "Card"))))
        g.chat.send("Hello"); settle()
        val ticket = embed.to("ticket").single()
        assertEquals(token, ticket.getJSONObject("identity").getString("token"))
        assertEquals("user-123", ticket.getJSONObject("identity").getString("value"))
        val outside = JSONObject(ticket.toString()).apply { remove("identity") }.toString()
        assertFalse("not in the metadata or anywhere else in the body", outside.contains(token))
        assertTrue(g.currentVariables().values.none { it.contains(token) })
        assertFalse("not in the screen's context", g.currentContextFrame().toString().contains(token))
        for (s in sockets) {
            assertFalse("not in a socket's URL", s.url.contains(token))
            assertTrue(s.protocols.none { it.contains(token) })
            assertTrue(s.sent.none { it.contains(token) })
        }
        assertTrue("not in a log line", lines.none { it.contains(token) })
        assertNull("identify() is not what the token is read back from", g.identityEnvelope().toString().takeIf { it.contains(token) })
    }

    @Test fun anAccountChangeTakesTheTokenAndThePassWithIt() {
        val g = agent()
        embed.answer("memory/list", FakeEmbed.ok(JSONObject().put("memories", org.json.JSONArray())
            .put("verification", JSONObject().put("user", "verified").put("userPass", "up1.asha").put("userPassExpiresIn", 3600))))
        g.identify(asha, null, "tok-asha"); settle()
        g.memories { _, _ -> }; settle()
        g.memories { _, _ -> }; settle()
        val ashas = embed.to("memory/list").last().getJSONObject("identity")
        assertEquals("tok-asha", ashas.getString("token"))
        assertEquals("up1.asha", ashas.getString("pass"))
        // Signed out: the device is released for Asha, with nothing of her proof.
        g.identify(null); settle()
        val signout = embed.to("identity/signout").single().getJSONObject("identity")
        assertEquals("user-123", signout.getString("value"))
        assertFalse(signout.has("token"))
        assertFalse(signout.has("pass"))
        // Another user without a token: none of hers.
        g.identify(mapOf("user_id" to "user-456", "name" to "Ravi")); settle()
        g.memories { _, _ -> }; settle()
        val ravis = embed.to("memory/list").last().getJSONObject("identity")
        assertEquals("user-456", ravis.getString("value"))
        assertFalse(ravis.has("token"))
        assertFalse(ravis.has("pass"))
        // Asha again, without her token: it went with her.
        g.identify(asha); settle()
        g.memories { _, _ -> }; settle()
        assertFalse(embed.to("memory/list").last().getJSONObject("identity").has("token"))
    }

    @Test fun setIdentityTokenKeepsTheIdentityAndTheSameTraitsKeepTheToken() {
        val g = agent()
        g.identify(asha); settle()
        g.setIdentityToken("tok-later"); settle()
        assertEquals("user-123", g.identityEnvelope().value)
        g.identify(asha); settle()
        g.memories { _, _ -> }; settle()
        assertEquals("tok-later", embed.to("memory/list").last().getJSONObject("identity").getString("token"))
        g.setIdentityToken(null); settle()
        g.memories { _, _ -> }; settle()
        assertFalse(embed.to("memory/list").last().getJSONObject("identity").has("token"))
    }

    @Test fun theSuspendRefresherIsAskedLikeTheCallbackOne() {
        val g = agent()
        var asked = 0
        g.setIdentityTokenRefresher { asked++; "tok-suspended" }
        assertNotNull(g.identityTokenRefresher)
        g.identify(asha, null, "tok-0"); settle()
        proofs.user.refreshInBackground(); settle()
        assertEquals(1, asked)
        g.memories { _, _ -> }; settle()
        assertEquals("tok-suspended", embed.to("memory/list").last().getJSONObject("identity").getString("token"))
        g.setIdentityTokenRefresher(null)
        assertNull(g.identityTokenRefresher)
    }

    // ── rings ───────────────────────────────────────────────────────────────

    @Test fun aRingRefreshesTheAppProofAndTheTokenBeforeAnyoneAnswers() {
        session = session(app = "report", user = "report")
        var n = 0
        embed.answer("app-verify/challenge", { FakeEmbed.ok(JSONObject().put("challenge", FakeEmbed.challenge(++n)))(it) })
        // A pass with three hours left: due for renewal, so the next trigger renews it.
        embed.answer("app-verify", FakeEmbed.pass("ap1.short", ttl = 3 * 3600L))
        val fake = FakeProver(IntegrityAnswer.Token("play-1"))
        prover = fake
        val g = agent()
        assertEquals("proven once its session said so", 1, fake.nonces.size)
        var refreshed = 0
        g.identityTokenRefresher = GraineIdentityTokenRefresher { done -> refreshed++; done.onToken("tok-ring") }
        g.identify(asha, null, "tok-0"); settle()
        assertTrue(g.calls.handlePush(mapOf("type" to "agent_call", "call_id" to "c-ring", "hosted" to "1", "agent_id" to "agent-a")))
        settle()
        assertEquals(GraineCallState.RINGING, g.calls.state)
        assertEquals("the app proof is refreshed at the ring", 2, fake.nonces.size)
        assertEquals("and the refresher asked", 1, refreshed)
        g.calls.decline(); settle()
        g.memories { _, _ -> }; settle()
        assertEquals("tok-ring", embed.to("memory/list").last().getJSONObject("identity").getString("token"))
    }

    // ── an app the agent does not take ──────────────────────────────────────

    @Test fun aChatThisAppMayNotUseFailsForGood() {
        embed.answer("ticket", FakeEmbed.refused(403, "app_not_allowed"))
        val g = agent()
        val errors = mutableListOf<String>()
        g.addListener(object : GraineListener { override fun onError(message: String) { errors += message } })
        g.chat.send("Hello"); settle()
        assertTrue(g.chat.failed)
        assertFalse(g.chat.connecting)
        assertTrue("no socket of its own instead", sockets.isEmpty())
        assertEquals(1, errors.size)
        clock.advance(120_000)
        assertEquals("never tried again by itself", 1, embed.to("ticket").size)
    }

    @Test fun aCallThisAppMayNotUseFailsBeforeItOpens() {
        agent()
        embed.answer("ticket", FakeEmbed.refused(403, "app_not_allowed"))
        var failed: String? = null
        val call = VoiceCallSession(
            CallSessionConfig("https://www.graine.ai", KEY, "agent-a", "web-a", null, emptyMap(), emptyMap()),
            CallSessionDeps(clock, SilentAudio(), http = embed, openSocket = { u, p, l -> FakeSocket(u, p, l).also { sockets += it } }, io = { it() }),
            object : CallSessionEvents { override fun onFailed(error: String) { failed = error } },
        )
        call.start(); settle()
        assertNotNull(failed)
        assertTrue(failed!!.contains("app_not_allowed"))
        assertTrue("no direct socket", sockets.isEmpty())
    }

    // ── what Graine has proven ──────────────────────────────────────────────

    @Test fun theVerificationIsHeardOnTheMainThread() {
        session = session(app = "report", user = "report")
        var n = 0
        embed.answer("app-verify/challenge", { FakeEmbed.ok(JSONObject().put("challenge", FakeEmbed.challenge(++n)))(it) })
        embed.answer("app-verify", FakeEmbed.pass("ap1.v"))
        prover = FakeProver(IntegrityAnswer.Token("play-1"))
        val seen = mutableListOf<GraineVerification>()
        val g = GraineInApp.make(app, GraineConfig("https://www.graine.ai", KEY), GraineInApp.DEFAULT) { env() }
        assertEquals(GraineVerification(), g.verification)
        g.addVerificationListener { seen += it }
        settle()
        assertEquals(GraineAppVerdict.VERIFIED, g.verification.app)
        assertEquals(GraineUserVerdict.UNVERIFIED, g.verification.user)
        assertEquals(g.verification, seen.last())
        embed.answer("ticket", FakeEmbed.ok(JSONObject().put("ticket", "t").put("wsUrl", "wss://ws.graine.ai/v1/s")
            .put("verification", JSONObject().put("app", "verified").put("user", "verified").put("userPass", "up1").put("userPassExpiresIn", 3600))))
        g.identify(asha, null, "tok-1"); settle()
        g.chat.send("Hi"); settle()
        assertEquals(GraineVerification(GraineAppVerdict.VERIFIED, null, GraineUserVerdict.VERIFIED, null), seen.last())
        val before = seen.size
        g.removeVerificationListener { }
        g.chat.send("Again"); settle()
        assertEquals("an unchanged verification is not told again", before, seen.size)
    }

    @Test fun developmentModeThroughTheAgent() {
        val g = agent()
        assertEquals(GraineDebugTokenMode.AUTOMATIC, g.debugTokenMode)
        assertNull("a release build has none", g.debugToken)
        g.debugTokenMode = GraineDebugTokenMode.ALWAYS
        val token = g.debugToken
        assertTrue(DebugTokens.valid(token))
        g.memories { _, _ -> }; settle()
        g.identify(asha); settle()
        g.memories { _, _ -> }; settle()
        assertEquals(token, embed.to("memory/list").last().getJSONObject("app").getString("debugToken"))
        g.debugTokenMode = GraineDebugTokenMode.NEVER
        assertNull(g.debugToken)
    }

    @Test fun prepareAppVerificationAnswersOnTheMainThread() {
        session = session(app = "report")
        var n = 0
        embed.answer("app-verify/challenge", { FakeEmbed.ok(JSONObject().put("challenge", FakeEmbed.challenge(++n)))(it) })
        embed.answer("app-verify", FakeEmbed.pass("ap1.p"))
        prover = FakeProver(IntegrityAnswer.Token("play-1"))
        val g = agent()
        var verdict: GraineAppVerdict? = null
        g.prepareAppVerification { verdict = it }
        settle()
        assertEquals(GraineAppVerdict.VERIFIED, verdict)
        assertEquals("a fresh pass is not proven again", 1, embed.to("app-verify").size)
    }

    // ── the SDK's own sources ───────────────────────────────────────────────

    private fun mainSources(): List<File> {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "graine-inapp/src/main").isDirectory && !File(d, "src/main/java/ai/graine/inapp").isDirectory) d = d.parentFile
        val root = listOf(File(d, "graine-inapp/src/main"), File(d, "src/main")).first { it.isDirectory }
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    @Test fun onlyOneFileNamesPlayIntegrityAndOneCoroutines() {
        val files = mainSources()
        assertTrue(files.size > 50)
        assertEquals(listOf("PlayIntegrity.kt"), files.filter { it.readText().contains("com.google.android.play.core.integrity") }.map { it.name })
        val flows = files.filter { it.readText().contains("kotlinx.coroutines") }
        assertEquals(listOf("VerificationFlow.kt"), flows.map { it.name })
        // In a folder of its own: the engine copied into the React Native and Flutter packages leaves it out.
        assertEquals("coroutines", flows.single().parentFile.name)
    }

    @Test fun everyRequestToGraineGoesThroughTheOneDoor() {
        val post = Regex("""\b(?:http|env\.http|deps\.http)\.post\(""")
        val outside = mutableListOf<String>()
        for (f in mainSources()) {
            f.readLines().forEachIndexed { i, line ->
                if (!post.containsMatchIn(line)) return@forEachIndexed
                val throughTheDoor = line.contains("EmbedRequest.send(") || line.contains("door.send(")
                // The one request that is not Graine's web routes: the grant taken to the telephony gateway (RtcDirect.kt).
                val gateway = line.contains("http.post(h.offer.url")
                if (!throughTheDoor && !gateway) outside += "${f.name}:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue("requests that skip EmbedRequest.send: $outside", outside.isEmpty())
    }

    /** A call's audio that hears and plays nothing. */
    private class SilentAudio : CallAudioIO {
        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {}
        override fun play(pcm: ShortArray, sampleRate: Int) {}
        override fun clear() {}
        override fun bufferedSeconds(): Double = 0.0
        override fun stop() {}
    }

    companion object {
        const val KEY = "pk_test_app"
    }
}
