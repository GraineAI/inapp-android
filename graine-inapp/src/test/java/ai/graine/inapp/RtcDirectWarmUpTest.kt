@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import android.app.Activity
import android.app.Application
import android.content.Context
import org.json.JSONArray
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
import java.util.Collections
import java.util.concurrent.Executor

/**
 * THE GRANT KEPT WARM, THROUGH THE SDK'S OWN ENTRY POINTS — the ones every
 * bridge drives. Kotlin Multiplatform's Android side calls exactly these:
 * `Graine.configure` makes the agent with GraineInApp.init (what
 * GraineInApp.make runs here, on fakes); `identify` and `setMemory` are
 * GraineInApp's own; a call is GraineCalls.startCall with the port's agent,
 * and "Call back" is GraineCalls.callBack. Real agents, the main thread on the
 * test's clock, every host faked: the session's arrival fetches the next
 * call's grant, a new user (or memory choice) fetches theirs, and a call
 * placed that way carries the grant's id and mints at the gateway. Without the
 * WebRTC module, or for calls that go over the WebSocket, nothing is fetched.
 */
class RtcDirectWarmUpTest {
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

    private val web = "https://www.graine.ai"
    private val gateway = "https://gw.example.invalid/grant-session"
    private val clock = ManualScheduler()
    private val main = TestMain(clock)
    private val app = TestApp()
    private val dir = java.nio.file.Files.createTempDirectory("graine-direct").toFile()
    private val device = DeviceStore(File(dir, "device.json"))
    private val kept = HashMap<String, String>()
    private val cache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text })
    private val sessions = HashMap<String, JSONObject>()

    /** The web route's mints, through the agent's own HTTP: none, when the gateway mints. */
    private val webMints: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
    /** The direct mint's hosts: the web route's grant-only answers, the gateway's probe and its mint. */
    private val grantAsks: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
    private val directMints: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
    @Volatile private var probes = 0
    private var grants = 0
    private var now = 1_800_000_000_000L

    private val hosts = object : RtcDirectHttp {
        override fun get(url: String, timeoutMs: Int): HttpReply {
            assertEquals(gateway, url)
            probes++
            return HttpReply(200, "{\"direct\":true}")
        }

        override fun post(url: String, json: String, timeoutMs: Int): HttpReply {
            val body = JSONObject(json)
            return when (url) {
                "$web/api/embed/rtc-session" -> {
                    grantAsks += body
                    val offer = JSONObject().put("grant", "v1.grant${++grants}.sig").put("expires_at", now + 900_000).put("ttl_ms", 900_000).put("url", gateway)
                    HttpReply(200, JSONObject().put("direct", offer).toString())
                }
                gateway -> {
                    directMints += body
                    // A gate's refusal: the call stops at its mint, before any socket — nothing here reaches a host.
                    HttpReply(402, "{\"detail\":\"Insufficient credits.\",\"code\":\"insufficient_credits\"}")
                }
                else -> throw AssertionError("no such host: $url")
            }
        }
    }

    /** The web route's answer to a call's mint: down, unless a test says otherwise. */
    private var webMint: () -> HttpReply = { HttpReply(503, "{\"error\":\"down\"}") }
    private val webHttp = HttpPost { url, json ->
        if (url.endsWith("/api/embed/rtc-session")) { webMints += JSONObject(json); webMint() }
        else HttpReply(200, "{\"ok\":true}")
    }

    private fun credential() = JSONObject()
        .put("server", Trace.SERVER).put("username", "web-XXXX").put("password", "pw").put("realm", Trace.REALM)
        .put("application_sid", Trace.APP_SID).put("agent_id", "agent-web-1").put("expires_at", "2099-01-01T00:00:00+00:00")
        .put("ice_servers", JSONArray().put(JSONObject().put("urls", "stun:stun.example.invalid:80")))

    /** A call's world on the test's clock: the web route above, a peer, sockets that never open — nothing reaches a host. */
    private var sessionIds = 0
    private fun rtcEnv(agent: GraineInApp) = RtcEnv(
        scheduler = clock,
        createPeer = { _, l -> FakePeer().also { it.listener = l } },
        http = webHttp,
        io = { it() },
        openSip = { url, _, l -> FakeSipSocket().also { it.url = url; it.listener = l } },
        openControl = { url, l -> FakeSipSocket().also { it.url = url; it.listener = l } },
        sipRandom = FixedSipRandom(),
        newSessionId = { String.format("%032x", ++sessionIds) },
        direct = agent.env.directMint,
    )

    private val media = object : GraineRtcMedia {
        override fun createPeer(context: Context, iceServers: List<GraineRtcIceServer>, listener: GraineRtcPeer.Listener): GraineRtcPeer =
            FakePeer().also { it.listener = listener }
    }

    /** The direct mint's background work — its grant requests and the probe — run when the test settles, from whichever thread asked. */
    private val queued: MutableList<() -> Unit> = Collections.synchronizedList(ArrayList())

    private fun runQueued() {
        while (true) {
            val work = synchronized(queued) { if (queued.isEmpty()) null else queued.removeAt(0) } ?: return
            work()
        }
    }

    @Before fun fresh() {
        AppShared.reset()
        RtcModule.override = media
        RtcModule.runsHereOverride = true
    }

    @After fun done() {
        AppShared.reset()
        RtcModule.override = null
        RtcModule.runsHereOverride = null
        dir.deleteRecursively()
    }

    /** What proves the app and the user, when a test makes its own ([ownProofs]); null: the app's. */
    private var proofs: Proofs? = null
    /** What the agent's session asks of its users (`security.user.verify`); null: nothing. */
    private var userVerify: String? = null

    private fun env() = AgentEnv(
        main = main,
        io = Executor { it.run() },
        http = webHttp,
        fetchSession = { _, key -> sessions[key] ?: throw IllegalStateException("offline") },
        scheduler = clock,
        ticketIo = { it() },
        device = device,
        sessionCache = cache,
        fonts = false,
        directMint = RtcDirectMint(http = hosts, clock = { now }, background = { work -> queued += work }, timers = ManualScheduler()),
        rtcEnv = { agent -> rtcEnv(agent) },
        proofs = proofs?.let { p -> { p } },
    )

    private fun session(audioTransport: String? = null) = JSONObject(
        """{"agentId":"agent-kmp","webcallAgentId":"web-kmp","appearance":{"title":"KMP"},"identity":{"field":"user_id"},
            "calls":{"hosted":true,"platforms":["android"]${audioTransport?.let { ",\"audioTransport\":\"$it\"" } ?: ""}}
            ${userVerify?.let { ",\"security\":{\"user\":{\"verify\":\"$it\"}}" } ?: ""}}""",
    )

    /**
     * Proofs of the test's own: the app's refresher answered on the asking
     * thread; [waitMs] the most a request waits for it when the user was
     * identified without a token (and the window what nobody waits on holds
     * back for it), on the test's clock for the hold, real for a request.
     */
    private fun ownProofs(waitMs: Long = 300) {
        proofs = Proofs(
            AppProofs({ AppProofsTest.DECLARED }, AppPassStore.memory(), { device }, { false }, { null }, clock, clock = { now }),
            UserProof(clock = { now }, tokenChanged = { AppShared.identityTokenChanged() }),
            fieldFor = { key -> AppShared.identityFieldFor(key) },
            refreshWaitMs = 50,
            noTokenWaitMs = waitMs,
        )
    }

    /** The app's refresher: every ask counted, answered at once with [next] — or held for the test to answer. */
    private class Refresher(private val next: () -> String? = { null }) : GraineIdentityTokenRefresher {
        var calls = 0
        var hold = false
        var held: GraineIdentityTokenCallback? = null
        override fun refresh(done: GraineIdentityTokenCallback) {
            calls++
            if (hold) held = done else done.onToken(next())
        }
    }

    /** The grant requests that named user [value]: the next call's (its id the app's own, `call_…`), or the call [callId]'s. */
    private fun grantsFor(value: String, callId: String? = null): List<JSONObject> = grantAsks.filter {
        it.optJSONObject("identity")?.optString("value") == value &&
            (if (callId == null) it.optString("callId").startsWith("call_") else it.optString("callId") == callId)
    }

    private fun tokenOf(body: JSONObject): String? = body.optJSONObject("identity")?.optString("token")?.takeIf { it.isNotEmpty() }

    private fun settle() {
        clock.advance(0)
        runQueued()
    }

    /** Graine.configure on Android: GraineInApp.init — make, with Android's own environment — and the session arriving. */
    private fun started(audioTransport: String? = null): GraineInApp {
        sessions["pk_kmp"] = session(audioTransport)
        val g = GraineInApp.make(app, GraineConfig(web, "pk_kmp"), GraineInApp.DEFAULT) { env() }
        settle()
        return g
    }

    @Test fun startedAndIdentifiedTheWayKotlinMultiplatformDoesKeepsTheNextCallsGrantWarm() {
        val g = started()
        val first = grantAsks.single()
        assertEquals("pk_kmp", first.getString("publishableKey"))
        assertTrue(first.getBoolean("grant_only"))
        assertFalse("nobody identified yet", first.has("identity"))
        assertEquals(device.installId(), first.getString("installId"))
        assertTrue("the next call's id", first.getString("callId").startsWith("call_"))
        assertEquals("and the gateway asked, once", 1, probes)

        // Graine.identify → AndroidEngine.identify → GraineInApp.identify: another user, their own grant.
        g.identify(mapOf("user_id" to "u-42", "name" to "Asha")); settle()
        assertEquals(2, grantAsks.size)
        assertEquals("u-42", grantAsks[1].getJSONObject("identity").getString("value"))
        assertNotEquals(first.getString("callId"), grantAsks[1].getString("callId"))

        // Graine.setMemory → GraineInApp.setMemory: the grant carries the choice.
        g.setMemory(false); settle()
        assertEquals(3, grantAsks.size)
        assertFalse(grantAsks[2].getBoolean("memory"))

        // The same user again: the grant in hand is theirs, and nothing is asked.
        g.identify(mapOf("user_id" to "u-42", "name" to "Asha")); settle()
        assertEquals(3, grantAsks.size)

        // Signed out: the next call is anonymous, and so is its grant.
        g.identify(null); settle()
        assertEquals(4, grantAsks.size)
        assertFalse(grantAsks[3].has("identity"))
        assertEquals(1, probes)
        assertTrue("nothing minted: no call yet", directMints.isEmpty() && webMints.isEmpty())
    }

    @Test fun aCallPlacedTheWayKotlinMultiplatformPlacesItMintsAtTheGateway() {
        val g = started()
        g.identify(mapOf("user_id" to "u-42")); settle()
        val warm = grantAsks.last()
        val activity = object : Activity() {}

        // Graine.calls.start → AndroidEngine.callsStart → RealAndroidPort.startCall → GraineCalls.startCall(…, agent = its name).
        val id = g.calls.startCall(activity, "card_blocked", mapOf("order" to "A-1043"), agent = g.name)
        assertEquals("the call carries the id its grant was asked for", warm.getString("callId"), id)
        // Its plan prepares it on the call's thread: the mint, at the gateway, with that grant.
        clock.advance(0)
        val m = directMints.single()
        assertEquals("that grant", "v1.grant2.sig", m.getString("grant"))
        assertTrue(m.getString("session_id").matches(Regex("[0-9a-f]{32}")))
        assertEquals("card_blocked", m.getJSONObject("variables").getString("call_reason"))
        assertEquals("A-1043", m.getJSONObject("variables").getString("order"))
        assertTrue("the web route minted nothing", webMints.isEmpty())
        assertEquals("nothing travelled beside the call's mint", 2, grantAsks.size)
        // Once it is done, the next call's grant.
        runQueued()
        assertEquals(3, grantAsks.size)
        val following = grantAsks.last()
        assertNotEquals("for the next call, under a new id", id, following.getString("callId"))
        assertEquals("u-42", following.getJSONObject("identity").getString("value"))
        g.calls.end(); settle()

        // "Call back" (AndroidEngine.callsCallBack → GraineCalls.callBack) places the next call the same way.
        val back = g.calls.callBack(activity, id)
        assertEquals(following.getString("callId"), back)
        settle()
        assertEquals("v1.grant3.sig", directMints.last().getString("grant"))
        assertTrue(webMints.isEmpty())
        g.calls.end(); settle()
    }

    @Test fun nothingIsAskedBeforeTheSessionSaysWhoTheUserIs() {
        sessions["pk_kmp"] = session()
        val g = GraineInApp.make(app, GraineConfig(web, "pk_kmp"), GraineInApp.DEFAULT) { env() }
        // identify() before the session has arrived: no grant — a ticket is never minted before it either…
        g.identify(mapOf("user_id" to "u-42"))
        assertTrue(grantAsks.isEmpty())
        // …and one, for this user, once it has.
        settle()
        assertEquals(1, grantAsks.size)
        assertEquals("u-42", grantAsks.single().getJSONObject("identity").getString("value"))
    }

    @Test fun withoutTheWebRtcModuleNothingIsAskedAndACallTakesAnIdOfItsOwn() {
        RtcModule.override = null
        val g = started()
        g.identify(mapOf("user_id" to "u-42")); settle()
        val id = g.calls.startCall(object : Activity() {}, "help", agent = g.name)
        assertTrue(id!!.startsWith("call_"))
        assertTrue(grantAsks.isEmpty())
        assertEquals(0, probes)
        assertTrue(directMints.isEmpty())
        g.calls.end(); settle()
    }

    @Test fun comingBackToTheForegroundAsksOnlyWhenTheGrantNeedsIt() {
        val g = started()
        assertEquals(1, grantAsks.size)
        // Back in the foreground within the grant's first half: nothing is sent.
        now += 5 * 60_000L
        g.foregroundWatchers.forEach { it(true) }; settle()
        assertEquals(1, grantAsks.size)
        // Past half its life: renewed, for the same next call.
        now += 3 * 60_000L
        g.foregroundWatchers.forEach { it(true) }; settle()
        assertEquals(2, grantAsks.size)
        assertEquals(grantAsks[0].getString("callId"), grantAsks[1].getString("callId"))
    }

    private fun ring(callId: String) = mapOf("type" to "agent_call", "call_id" to callId, "hosted" to "1", "agent_id" to "agent-kmp")

    @Test fun aRingsGrantRidesInOnItsOwnMintsAnswerSoTheRingCostsOneRequest() {
        val g = started()
        g.identify(mapOf("user_id" to "u-42")); settle()
        val asked = grantAsks.size
        // The ring's call, prepared as it arrives, mints through the web route — and that answer carries a grant for the ring.
        val ringGrant = JSONObject().put("grant", "v1.ring.sig").put("expires_at", now + 900_000).put("ttl_ms", 900_000).put("url", gateway)
        webMint = { HttpReply(200, credential().put("direct", ringGrant).toString()) }
        // A ring Graine sends (GraineCalls.handlePush — Kotlin Multiplatform's PlatformPush.handlePush reaches the same).
        assertTrue(g.calls.handlePush(ring("sc_ring_kmp_1")))
        settle()
        assertEquals("sc_ring_kmp_1", webMints.single().getString("callId"))
        assertEquals("no grant asked for apart", asked, grantAsks.size)
        // The mint at its answer takes that grant to the gateway.
        val answer = RtcCallRequest(web, "pk_kmp", emptyMap(), g.identityEnvelope(), "sc_ring_kmp_1", null)
        assertEquals(402, g.env.directMint.mint(answer, "0123456789abcdef0123456789abcdef")?.code)
        assertEquals("v1.ring.sig", directMints.last().getString("grant"))
        g.calls.decline(); settle()
    }

    @Test fun aRingWhoseMintAnswerCarriedNoGrantHasOneAskedForApart() {
        val g = started()
        g.identify(mapOf("user_id" to "u-42")); settle()
        webMint = { HttpReply(200, credential().toString()) }
        assertTrue(g.calls.handlePush(ring("sc_ring_kmp_2")))
        settle()
        val ask = grantAsks.single { it.optString("callId") == "sc_ring_kmp_2" }
        assertEquals("u-42", ask.getJSONObject("identity").getString("value"))
        g.calls.decline(); settle()
    }

    @Test fun aRingBeforeTheSessionHasItsGrantAskedForOnceTheSessionSaysWhoTheUserIs() {
        sessions["pk_kmp"] = session()
        val g = GraineInApp.make(app, GraineConfig(web, "pk_kmp"), GraineInApp.DEFAULT) { env() }
        g.identify(mapOf("user_id" to "u-42"))
        // A push woke the app: the ring arrives before the session.
        assertTrue(g.calls.handlePush(ring("sc_ring_cold")))
        runQueued()
        assertTrue("nobody to ask under yet", grantAsks.none { it.optString("callId") == "sc_ring_cold" })
        settle()
        val ask = grantAsks.single { it.optString("callId") == "sc_ring_cold" }
        assertEquals("under who the user is, now known", "u-42", ask.getJSONObject("identity").getString("value"))
        g.calls.decline(); settle()
    }

    @Test fun callsTheDashboardSendsOverTheWebSocketWarmNothing() {
        val g = started(audioTransport = "websocket")
        g.identify(mapOf("user_id" to "u-42")); settle()
        assertTrue(grantAsks.isEmpty())
        assertEquals(0, probes)
    }

    // ── the user's identity token, while the app is still fetching it (0.14.7) ──

    /**
     * Identified without a token, for an agent that checks its users, with a
     * refresher: no grant for the user until the token comes — whatever asks
     * meanwhile, one refresh — then exactly one, with it. A grant without it
     * is a request the agent's Security log notes as anonymous.
     */
    @Test fun theNextCallsGrantWaitsForTheUsersTokenThenGoesOnceWithIt() {
        ownProofs()
        userVerify = "report"
        val g = started()
        val r = Refresher().apply { hold = true }
        g.identityTokenRefresher = r
        g.identify(mapOf("user_id" to "u-42")); settle()
        // More reasons to warm while it is on its way: back in the foreground, another memory choice.
        g.foregroundWatchers.forEach { it(true) }; settle()
        g.setMemory(false); settle()
        assertTrue("nothing goes for the user without the token", grantsFor("u-42").isEmpty())
        assertEquals("one refresh, joined by every warm-up", 1, r.calls)
        r.held!!.onToken("tok-fresh"); settle()
        val sent = grantsFor("u-42")
        assertEquals("exactly one", 1, sent.size)
        assertEquals("with the token", "tok-fresh", tokenOf(sent.single()))
        assertFalse("for who the user is now", sent.single().getBoolean("memory"))
        assertEquals(1, r.calls)
    }

    /** The refresher answers null: one grant, as it is, at once — no ten seconds — and coming back asks the refresher nothing more. */
    @Test fun aRefresherWithNoTokenLetsTheGrantGoOnceAsItIs() {
        ownProofs()
        userVerify = "report"
        val g = started()
        val r = Refresher { null }
        g.identityTokenRefresher = r
        g.identify(mapOf("user_id" to "u-42")); settle()
        val sent = grantsFor("u-42")
        assertEquals("one, once the refresh ended", 1, sent.size)
        assertNull("nothing to carry: as it is", tokenOf(sent.single()))
        assertEquals(1, r.calls)
        g.foregroundWatchers.forEach { it(true) }; settle()
        assertEquals("the grant in hand holds: nothing sent", 1, grantsFor("u-42").size)
        assertEquals("at most once every 30 seconds", 1, r.calls)
    }

    /**
     * The refresher hangs: one grant, as it is, once the 10 seconds go by.
     * Nobody waited, so a conversation starting then would still wait for the
     * refresh; its late token sends the grant again, with it.
     */
    @Test fun aSlowRefresherLetsTheGrantGoAfterTenSeconds() {
        ownProofs(waitMs = 10_000)
        userVerify = "enforce"
        val g = started()
        val r = Refresher().apply { hold = true }
        g.identityTokenRefresher = r
        g.identify(mapOf("user_id" to "u-42")); settle()
        clock.advance(9_999); settle()
        assertTrue(grantsFor("u-42").isEmpty())
        clock.advance(1); settle()
        assertEquals("ten seconds: it goes as it is", 1, grantsFor("u-42").size)
        assertNull(tokenOf(grantsFor("u-42").single()))
        assertEquals(1, r.calls)
        assertTrue("not a wait that ran out: a conversation would still wait for it", proofs!!.missingTokenDue("pk_kmp", "u-42"))
        r.held!!.onToken("tok-late"); settle()
        assertEquals("the grant again, with the token", 2, grantsFor("u-42").size)
        assertEquals("tok-late", tokenOf(grantsFor("u-42").last()))
        assertEquals("never two refreshes", 1, r.calls)
    }

    /** Nothing to wait for — an agent that does not check its users: the grant goes at once, as before, and nobody's refresher is asked. */
    @Test fun withNothingToWaitForTheGrantGoesAsBefore() {
        ownProofs()
        userVerify = "off"
        val r = Refresher().apply { hold = true }
        proofs!!.user.refresher = r
        val g = started()
        g.identify(mapOf("user_id" to "u-42")); settle()
        assertEquals("an agent that does not check its users: at once", 1, grantsFor("u-42").size)
        assertNull(tokenOf(grantsFor("u-42").single()))
        assertEquals(0, r.calls)
    }

    /** Nobody identified, a token held, no refresher: the grant goes at once, as before. */
    @Test fun nobodyIdentifiedATokenHeldOrNoRefresherWarmsAsBefore() {
        ownProofs()
        userVerify = "enforce"
        val r = Refresher().apply { hold = true }
        proofs!!.user.refresher = r
        val g = started()
        assertEquals("nobody identified: at once", 1, grantAsks.size)
        assertFalse(grantAsks.single().has("identity"))
        // A token held.
        g.identify(mapOf("user_id" to "u-7"), identityToken = "tok-held"); settle()
        assertEquals(1, grantsFor("u-7").size)
        assertEquals("tok-held", tokenOf(grantsFor("u-7").single()))
        // No refresher: nothing to ask, so nothing to wait for.
        g.identityTokenRefresher = null
        g.identify(mapOf("user_id" to "u-42")); settle()
        assertEquals(1, grantsFor("u-42").size)
        assertNull(tokenOf(grantsFor("u-42").single()))
        assertEquals(0, r.calls)
    }

    /** A ring's own grant never waits for the token: asked for apart while the next call's grant still waits, and the ring asks the refresher nothing. */
    @Test fun aRingsGrantNeverWaitsForTheToken() {
        ownProofs()
        userVerify = "report"
        val g = started()
        val r = Refresher().apply { hold = true }
        g.identityTokenRefresher = r
        g.identify(mapOf("user_id" to "u-42")); settle()
        assertEquals(1, r.calls)
        webMint = { HttpReply(200, credential().toString()) }
        assertTrue(g.calls.handlePush(ring("sc_ring_tok")))
        settle()
        val ask = grantsFor("u-42", callId = "sc_ring_tok")
        assertEquals("the ring's own grant, asked for apart", 1, ask.size)
        assertNull(tokenOf(ask.single()))
        assertTrue("the next call's grant still waits", grantsFor("u-42").isEmpty())
        assertEquals("the ring asked nothing more", 1, r.calls)
        g.calls.decline(); settle()
    }
}
