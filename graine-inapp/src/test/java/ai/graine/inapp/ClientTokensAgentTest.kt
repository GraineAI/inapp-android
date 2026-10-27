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
import java.util.Date
import java.util.concurrent.Executor

/**
 * SERVER TOKENS ON A REAL AGENT (CLIENT-TOKENS.md §2.2–2.5) — GraineInApp on the
 * JVM, the main thread, the network, the sockets and the token manager's clock
 * faked: with a provider, every request the SDK makes to Graine carries the
 * token (the session, the chat's and a call's tickets, the call's
 * credential and its grant, devices, sign-out, booking, outcomes, memory, a new
 * conversation, past sessions); without one, none does. A token that expires
 * while a chat or a call is live ends, pauses and reconnects nothing. A refusal
 * a fresh token lifts asks once and goes once more; token_scope and key_revoked
 * are final — the chat fails with them, a call fails with them, and nothing
 * tries another way in.
 */
class ClientTokensAgentTest {
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
        var closed: Int? = null
        override fun send(text: String): Boolean { if (closed != null) return false; sent += text; return true }
        override fun close(code: Int, reason: String) { if (closed == null) closed = code }
    }

    /** The device's wall clock at the start of each test: 2026-10-26. */
    private val base = 1_793_000_000_000L
    private val clock = ManualScheduler()
    private val nowSec: Long get() = (base + clock.now()) / 1000
    private val dir = java.nio.file.Files.createTempDirectory("graine-tokens").toFile()
    private val device = DeviceStore(File(dir, "device.json"))
    private val kept = HashMap<String, String>()

    /** Every request an agent sent: where, and what it said. */
    private val requests = mutableListOf<Pair<String, JSONObject>>()
    /** The token each ask of the session route carried. */
    private val sessionTokens = mutableListOf<String?>()
    private val sockets = mutableListOf<FakeSocket>()
    private val errors = mutableListOf<String>()

    private val session = JSONObject(
        """{"agentId":"agent-1","webcallAgentId":"web-1","appearance":{"title":"Acme"},"identity":{"field":"user_id"},
            "calls":{"hosted":true,"platforms":["android"],"ringTimeoutSeconds":30,"scheduling":{"usersCanBook":true}}}""",
    )
    private var sessionAnswer: (String?) -> JSONObject = { session }
    private var reply: (String, JSONObject) -> HttpReply = { url, body -> answer(url, body) }

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun answer(url: String, body: JSONObject): HttpReply = when {
        url.endsWith("/api/embed/ticket") -> HttpReply(200, """{"ticket":"tkt_${body.optString("mode")}","wsUrl":"wss://ws.graine.ai/v1/s"}""")
        url.endsWith(EmbedApi.SCHEDULE) ->
            HttpReply(200, JSONObject().put("call", JSONObject().put("id", "sc_1").put("at", body.getString("at")).put("status", "scheduled")).toString())
        url.endsWith(EmbedApi.LIST) -> HttpReply(200, """{"calls":[]}""")
        url.endsWith(EmbedApi.PAST_SESSIONS) -> HttpReply(200, """{"sessions":[],"next":null}""")
        else -> HttpReply(200, """{"ok":true}""")
    }

    /** The agent's token manager, on the test's clock: the jitter in the middle, the provider asked where it is called. */
    private fun managerFor(p: GraineTokenProvider?) = ClientTokens(
        p,
        clock = { base + clock.now() },
        random = { 0.5 },
        schedule = { d, task -> clock.schedule(d, task) },
        background = { it() },
        connectionWaitMs = 1_000,
        pushWaitMs = 1_000,
    )

    private fun agent(provider: GraineTokenProvider?, key: String = "pk_tokens"): GraineInApp {
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { it.run() },
            http = HttpPost { url, json -> JSONObject(json).let { b -> requests += url to b; reply(url, b) } },
            fetchSessionWithToken = { _, _, token -> sessionTokens += token; sessionAnswer(token) },
            openSocket = { url, protocols, listener -> FakeSocket(url, protocols, listener).also { sockets += it } },
            scheduler = clock,
            ticketIo = { it() },
            device = device,
            sessionCache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text }),
            fonts = false,
            clientTokens = ::managerFor,
        )
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", key, tokenProvider = provider), GraineInApp.DEFAULT) { env }
        g.addListener(object : GraineListener { override fun onError(message: String) { errors += message } })
        settle()
        return g
    }

    private fun settle() = clock.advance(0)

    private fun sent(path: String): List<JSONObject> = requests.filter { it.first.endsWith(path) }.map { it.second }

    private fun refusal(code: String, reason: String? = null) =
        JSONObject().put("error", "Refused for its $code.").put("code", code).apply { reason?.let { put("reason", it) } }.toString()

    /** Every route the SDK asks on its own, once each. */
    private fun everyRoute(g: GraineInApp) {
        g.identify(mapOf("user_id" to "u-1")); settle()
        g.scheduleCall(Date(System.currentTimeMillis() + 3_600_000), "billing") { _, _ -> }; settle()
        g.scheduledCalls { _, _ -> }; settle()
        g.cancelScheduledCall("sc_1"); settle()
        g.memories { _, _ -> }; settle()
        g.forgetMemory(); settle()
        g.chat.newConversation(); settle()
        g.chat.refreshPastSessions(); settle()
        g.chat.sessionTranscript("s_1") { _, _ -> }; settle()
        g.chat.send("Hello"); settle()
        g.calls.handlePush(mapOf("type" to "agent_call", "call_id" to "ic_1", "hosted" to "1")); settle()
        g.calls.decline(); settle()
        g.identify(null); settle()
    }

    private val routes = listOf(
        "/api/embed/ticket", EmbedApi.DEVICES, EmbedApi.SIGNOUT, EmbedApi.SCHEDULE, EmbedApi.LIST, EmbedApi.CANCEL, EmbedApi.OUTCOME,
        EmbedApi.MEMORIES, EmbedApi.FORGET, EmbedApi.NEW_CONVERSATION, EmbedApi.PAST_SESSIONS, EmbedApi.SESSION_TRANSCRIPT,
    )

    // ── the wire ─────────────────────────────────────────────────────────────

    @Test fun withAProviderEveryRequestCarriesTheToken() {
        device.pushToken = "fcm-1"
        val token = TestTokens.mint(nowSec, nowSec + 3600, "ct_app")
        var asked = 0
        val g = agent(GraineTokenProvider { done -> asked++; done(token) })
        assertEquals("asked at start, before the session", 1, asked)
        assertEquals("the session carried it", listOf<String?>(token), sessionTokens)
        everyRoute(g)
        for (path in routes) assertTrue("$path was asked", sent(path).isNotEmpty())
        // Every request reaches Graine's own routes: each carried it.
        assertTrue(requests.size >= routes.size)
        for ((url, body) in requests) assertEquals(url, token, body.optString(ClientTokenRules.FIELD))
        // Asked at start, and again whenever identify() names someone else — here a user, then nobody: a token
        // speaks for one user (CLIENT-TOKENS.md §2.3). The provider answered the same token each time.
        assertEquals("asked at start and on each change of user", 3, asked)
    }

    @Test fun withoutAProviderNoRequestCarriesOne() {
        device.pushToken = "fcm-1"
        val g = agent(null)
        assertEquals(listOf<String?>(null), sessionTokens)
        everyRoute(g)
        for (path in routes) assertTrue("$path was asked", sent(path).isNotEmpty())
        for ((url, body) in requests) assertFalse(url, body.has(ClientTokenRules.FIELD))
    }

    @Test fun aRingAsksForAFreshTokenBeforeAnyoneAnswers() {
        var asked = 0
        val g = agent(GraineTokenProvider { done -> asked++; done(TestTokens.mint(nowSec, nowSec + 3600, "ct_$asked")) })
        assertEquals(1, asked)
        clock.advance(60_000)
        g.calls.handlePush(mapOf("type" to "agent_call", "call_id" to "ic_9", "hosted" to "1")); settle()
        assertEquals(2, asked)
        assertEquals("the ring's report carries the fresh one", g.clientTokens.newest, sent(EmbedApi.OUTCOME).last().getString(ClientTokenRules.FIELD))
    }

    @Test fun theEnginePageAskingForATokenAsksTheProvider() {
        var asked = 0
        val g = agent(GraineTokenProvider { done -> asked++; done(TestTokens.mint(nowSec, nowSec + 3600, "ct_$asked")) })
        clock.advance(10_000)
        g.voice.handle(PageMessage.NeedClientToken); settle()
        assertEquals(2, asked)
        // At most one asked anew in 5 s: a second request at once is answered with the newest, asking nobody.
        g.voice.handle(PageMessage.NeedClientToken); settle()
        assertEquals(2, asked)
    }

    // ── refusals ─────────────────────────────────────────────────────────────

    @Test fun aSessionRefusedForAnExpiredTokenAsksOnceAndLoads() {
        val first = TestTokens.mint(nowSec, nowSec + 3600, "ct_1")
        val second = TestTokens.mint(nowSec, nowSec + 3600, "ct_2")
        var asked = 0
        sessionAnswer = { token ->
            if (token == first) throw SessionRefusedException(401, JSONObject(refusal("token_expired")), "Refused for its token_expired.")
            session
        }
        val g = agent(GraineTokenProvider { done -> asked++; done(if (asked == 1) first else second) })
        assertEquals(listOf<String?>(first, second), sessionTokens)
        assertEquals(2, asked)
        assertNotNull(g.session)
    }

    @Test fun aSessionRefusedForARevokedKeyIsTheRefusalEveryRequestHears() {
        sessionAnswer = { throw SessionRefusedException(401, JSONObject(refusal("key_revoked")), "Refused for its key_revoked.") }
        val g = agent(GraineTokenProvider { done -> done(TestTokens.mint(nowSec, nowSec + 3600)) })
        assertEquals("final: never asked again", 1, sessionTokens.size)
        assertNull(g.session)
        assertTrue(errors.contains("Refused for its key_revoked."))
        var heard: GraineError? = null
        g.memories { _, e -> heard = e }; settle()
        assertEquals(GraineErrorCode.KEY_REVOKED, heard?.code)
        sessionAnswer = { throw SessionRefusedException(403, JSONObject(refusal("token_scope", "user")), "Refused for its token_scope.") }
        g.scheduledCalls { _, e -> heard = e }; settle()
        assertEquals(GraineErrorCode.TOKEN_SCOPE, heard?.code)
        assertEquals("user", heard?.reason)
    }

    @Test fun aChatTicketRefusedForAnExpiredTokenAsksOnceAndConnects() {
        var asked = 0
        val g = agent(GraineTokenProvider { done -> asked++; done(TestTokens.mint(nowSec, nowSec + 3600, "ct_$asked")) })
        var tickets = 0
        reply = { url, body ->
            if (url.endsWith("/api/embed/ticket") && ++tickets == 1) HttpReply(401, refusal("token_expired")) else answer(url, body)
        }
        g.chat.shown(true); settle()
        assertEquals("exactly one more question", 2, asked)
        val asks = sent("/api/embed/ticket")
        assertEquals("exactly one retry", 2, asks.size)
        assertEquals("ct_1", jti(asks[0]))
        assertEquals("ct_2", jti(asks[1]))
        val s = sockets.single()
        assertEquals(listOf(CallFrames.SUBPROTOCOL, "tkt_text"), s.protocols)
        s.listener.onOpen("graine.embed.v1"); settle()
        assertTrue(g.chat.connected)
        assertNull(g.chat.error)
    }

    @Test fun aChatRefusedForARevokedKeyFailsWithItAndNeverTriesAnotherWay() {
        val g = agent(GraineTokenProvider { done -> done(TestTokens.mint(nowSec, nowSec + 3600)) })
        reply = { url, body -> if (url.endsWith("/api/embed/ticket")) HttpReply(401, refusal("key_revoked")) else answer(url, body) }
        g.chat.shown(true); settle()
        g.chat.send("Hello"); settle()
        assertTrue(g.chat.failed)
        assertFalse(g.chat.connecting)
        assertEquals(GraineErrorCode.KEY_REVOKED, g.chat.error?.code)
        assertTrue("no direct socket past the refusal", sockets.isEmpty())
        assertTrue(errors.contains("Refused for its key_revoked."))
        assertEquals(GraineChatTurn.Delivery.FAILED, g.chat.messages.single().delivery)
        val asked = sent("/api/embed/ticket").size
        clock.advance(120_000)
        assertEquals("no reconnect: the next would meet the same answer", asked, sent("/api/embed/ticket").size)
        // The app asks again: a new try — and the line the customer typed goes on it.
        reply = { url, body -> answer(url, body) }
        g.chat.connect(); settle()
        assertNull(g.chat.error)
        assertEquals(1, sockets.size)
        sockets.single().listener.onOpen("graine.embed.v1"); settle()
        assertTrue(g.chat.connected)
        assertTrue(sockets.single().sent.any { it.contains("Hello") })
        assertEquals(GraineChatTurn.Delivery.SENT, g.chat.messages.single().delivery)
    }

    @Test fun aChatRefusedForTheTokensScopeIsNeverRetried() {
        var asked = 0
        val g = agent(GraineTokenProvider { done -> asked++; done(TestTokens.mint(nowSec, nowSec + 3600)) })
        reply = { url, body -> if (url.endsWith("/api/embed/ticket")) HttpReply(403, refusal("token_scope", "scope")) else answer(url, body) }
        g.chat.shown(true); settle()
        assertEquals(1, sent("/api/embed/ticket").size)
        assertEquals(1, asked)
        assertEquals(GraineErrorCode.TOKEN_SCOPE, g.chat.error?.code)
        assertEquals("scope", g.chat.error?.reason)
        assertTrue(sockets.isEmpty())
    }

    // ── nothing live is touched ──────────────────────────────────────────────

    @Test fun aTokenThatExpiresDuringALiveChatEndsPausesAndReconnectsNothing() {
        var answer: String? = TestTokens.mint(nowSec, nowSec + 60, "ct_minute")
        var asked = 0
        val g = agent(GraineTokenProvider { done -> asked++; done(answer) })
        g.chat.shown(true); settle()
        val s = sockets.single()
        s.listener.onOpen("graine.embed.v1"); settle()
        assertTrue(g.chat.connected)
        // The app's server goes down: the minute's token lapses, and every refresh fails, for ten minutes.
        answer = null
        val tickets = sent("/api/embed/ticket").size
        repeat(20) {
            clock.advance(30_000)
            s.listener.onText("""{"type":"text","data":"<beginning_of_stream>Still here.<end_of_stream>"}"""); settle()
        }
        assertTrue("the refreshes were tried", asked > 2)
        g.chat.send("And now?"); settle()
        assertTrue(g.chat.connected)
        assertFalse(g.chat.connecting)
        assertFalse(g.chat.reconnecting)
        assertFalse(g.chat.failed)
        assertNull(s.closed)
        assertEquals("no stop frame", 0, s.sent.count { runCatching { JSONObject(it).optString("type") }.getOrNull() == "stop" })
        assertTrue("the line went on the live socket", s.sent.any { it.contains("And now?") })
        assertEquals("no new ticket", tickets, sent("/api/embed/ticket").size)
        assertEquals(1, sockets.size)
    }

    @Test fun aTokenThatExpiresDuringALiveCallEndsPausesAndReconnectsNothing() {
        var answer: String? = TestTokens.mint(nowSec, nowSec + 60, "ct_minute")
        var asked = 0
        val tokens = managerFor(GraineTokenProvider { done -> asked++; done(answer) })
        tokens.start()
        val tickets = mutableListOf<JSONObject>()
        val socket = FakeSocket("wss://ws.graine.ai/v1/s", emptyList(), object : CallSocketListener {
            override fun onOpen(protocol: String?) {}
            override fun onText(text: String) {}
            override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(error: Throwable) {}
        })
        var listener: CallSocketListener? = null
        val events = mutableListOf<String>()
        val call = VoiceCallSession(
            CallSessionConfig("https://www.graine.ai", "pk_tokens", "agent-1", "web-1", null, emptyMap(), emptyMap(), callId = "call_1"),
            CallSessionDeps(
                scheduler = clock,
                audio = SilentAudio(),
                http = HttpPost { _, json -> tickets += JSONObject(json); HttpReply(200, """{"ticket":"t","wsUrl":"wss://ws.graine.ai/v1/s"}""") },
                openSocket = { _, _, l -> listener = l; socket },
                io = { it() },
                tokens = tokens,
            ),
            object : CallSessionEvents {
                override fun onOpen() { events += "open" }
                override fun onFailed(error: String) { events += "failed $error" }
                override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { events += "ended" }
                override fun onReconnecting(reconnecting: Boolean) { events += "reconnecting $reconnecting" }
            },
        )
        call.start(); clock.drain()
        assertEquals("ct_minute", jti(tickets.single()))
        listener!!.onOpen(null); clock.drain()
        assertEquals(listOf("open"), events)
        answer = null
        repeat(20) {
            clock.advance(30_000)
            listener!!.onText("""{"type":"pong"}"""); clock.drain()
        }
        assertTrue("the refreshes were tried", asked > 2)
        assertEquals("nothing ended, paused or reconnected", listOf("open"), events)
        assertNull(socket.closed)
        assertEquals("no new ticket", 1, tickets.size)
        call.hangUp(); clock.advance(1_000)
    }

    @Test fun aCallWhoseTicketIsRefusedForItsScopeFailsWithTheCodeAndNoDirectSocket() {
        val tokens = managerFor(GraineTokenProvider { done -> done(TestTokens.mint(nowSec, nowSec + 3600)) })
        tokens.start()
        var opened = 0
        val events = mutableListOf<String>()
        val call = VoiceCallSession(
            CallSessionConfig("https://www.graine.ai", "pk_tokens", "agent-1", "web-1", null, emptyMap(), emptyMap(), callId = "call_2"),
            CallSessionDeps(
                scheduler = clock,
                audio = SilentAudio(),
                http = HttpPost { _, _ -> HttpReply(403, refusal("token_scope", "scope")) },
                openSocket = { _, _, _ -> opened++; throw AssertionError("no socket") },
                io = { it() },
                tokens = tokens,
            ),
            object : CallSessionEvents { override fun onFailed(error: String) { events += error } },
        )
        call.start(); clock.drain()
        assertEquals(listOf("token_scope: Refused for its token_scope."), events)
        assertEquals(0, opened)
    }

    // ── a call over WebRTC: its credential and its grant ─────────────────────

    @Test fun theWebRtcCredentialCarriesTheTokenAndARefusalForItsScopeEndsTheCallWithoutAFallback() {
        val token = TestTokens.mint(nowSec, nowSec + 3600, "ct_rtc")
        val tokens = managerFor(GraineTokenProvider { done -> done(token) })
        tokens.start()
        val mints = mutableListOf<JSONObject>()
        var mintReply = HttpReply(503, """{"error":"busy","code":"unavailable"}""")
        val env = RtcEnv(
            scheduler = clock,
            createPeer = { _, l -> FakePeer().also { it.listener = l } },
            http = HttpPost { _, json -> mints += JSONObject(json); mintReply },
            io = { it() },
            openSip = { url, protocols, l -> FakeSipSocket().also { s -> s.url = url; s.protocols = protocols; s.listener = l } },
            openControl = { url, l -> FakeSipSocket().also { it.url = url; it.listener = l } },
            sipRandom = FixedSipRandom(),
            newSessionId = { "0123456789abcdef0123456789abcdef" },
            tokens = tokens,
        )
        val request = RtcCallRequest("https://www.graine.ai", "pk_tokens", emptyMap(), null, "call_3", null)
        val heard = mutableListOf<String>()
        val listener = object : RtcCallSession.Listener {
            override fun onFallback(reason: String, detail: String) { heard += "fallback $reason" }
            override fun onDenied(error: String) { heard += "denied $error" }
            override fun onRefused(message: String) { heard += "refused $message" }
        }
        val deps = RtcDialDeps({ _, _, answer -> answer(ActionResult.ok()) }, { null })
        // Any other answer falls back to the WebSocket as always — and the mint carried the token.
        RtcCallSession(request, env).dial(deps, listener); clock.drain()
        assertEquals(token, mints.single().getString(ClientTokenRules.FIELD))
        assertEquals(listOf("fallback ${RtcFallback.MINT_FAILED}"), heard)
        // Refused for its scope: the call fails with it, and no WebSocket is tried.
        heard.clear()
        mintReply = HttpReply(403, refusal("token_scope", "scope"))
        RtcCallSession(request, env).dial(deps, listener); clock.drain()
        assertEquals(listOf("denied token_scope: Refused for its token_scope."), heard)
    }

    @Test fun aGrantRequestCarriesTheTokenOfTheAgentItsKeySelects() {
        val token = TestTokens.mint(nowSec, nowSec + 3600, "ct_grant")
        val tokens = managerFor(GraineTokenProvider { done -> done(token) })
        tokens.start()
        val asks = mutableListOf<JSONObject>()
        val direct = RtcDirectMint(
            http = object : RtcDirectHttp {
                override fun get(url: String, timeoutMs: Int): HttpReply = HttpReply(404, "")
                override fun post(url: String, json: String, timeoutMs: Int): HttpReply {
                    asks += JSONObject(json)
                    return HttpReply(200, """{"direct":null}""")
                }
            },
            clock = { base + clock.now() },
            background = { it() },
            timers = clock,
            newCallId = { "call_next" },
            tokens = { key -> if (key == "pk_tokens") tokens else null },
        )
        direct.warm("https://www.graine.ai", "pk_tokens", null)
        val ask = asks.single()
        assertTrue(ask.getBoolean("grant_only"))
        assertEquals(token, ask.getString(ClientTokenRules.FIELD))
        direct.warm("https://www.graine.ai", "pk_other", null)
        assertFalse("another key's agent has no tokens: none is sent", asks.last().has(ClientTokenRules.FIELD))
    }

    // ── another user (CLIENT-TOKENS.md §2.3) ────────────────────────────────

    @Test fun anotherUserLetsGoOfTheTokenAndTheSignOutCarriesTheLeavingUsersOwn() {
        // The provider mints for whoever is signed in now, as a real app's server does.
        var signedIn = "alice"
        var asked = 0
        val g = agent(GraineTokenProvider { done -> asked++; done(TestTokens.mint(nowSec, nowSec + 3600, "ct_${signedIn}_$asked")) })
        g.identify(mapOf("user_id" to "alice")); settle()
        val alices = g.clientTokens.newest
        assertEquals("at start, and again for Alice once she is identified", 2, asked)
        clock.advance(60_000)
        requests.clear()
        signedIn = "bob"
        g.identify(mapOf("user_id" to "bob")); settle()
        // Alice's device is released with her own token — never Bob's, never none.
        assertEquals(alices, sent(EmbedApi.SIGNOUT).single().getString(ClientTokenRules.FIELD))
        // The token held spoke for Alice: let go of, and Bob's asked for at once.
        assertEquals("asked again at once, for Bob", 3, asked)
        assertEquals("ct_bob_3", jti(JSONObject().put(ClientTokenRules.FIELD, g.clientTokens.newest)))
        // Bob's chat goes with Bob's token.
        g.chat.shown(true); settle()
        assertEquals("ct_bob_3", jti(sent("/api/embed/ticket").last()))
    }

    @Test fun signingOutLetsGoOfTheToken() {
        var signedIn: String? = "alice"
        var asked = 0
        val g = agent(GraineTokenProvider { done -> asked++; done(signedIn?.let { TestTokens.mint(nowSec, nowSec + 3600, "ct_${it}_$asked") }) })
        g.identify(mapOf("user_id" to "alice")); settle()
        val alices = g.clientTokens.newest
        signedIn = null
        g.identify(null); settle()
        assertEquals("the sign-out carries the leaving user's token", alices, sent(EmbedApi.SIGNOUT).single().getString(ClientTokenRules.FIELD))
        assertNull("nobody holds Alice's token", g.clientTokens.newest)
        assertEquals("asked again for whoever is signed in now", 3, asked)
    }

    /** The `jti` a test token was minted with. */
    private fun jti(body: JSONObject): String {
        val payload = body.getString(ClientTokenRules.FIELD).split('.')[1]
        return JSONObject(String(B64.decode(payload.replace('-', '+').replace('_', '/')))).getString("jti")
    }

    private class SilentAudio : CallAudioIO {
        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {}
        override fun play(pcm: ShortArray, sampleRate: Int) {}
        override fun clear() {}
        override fun bufferedSeconds(): Double = 0.0
        override fun stop() {}
    }
}
