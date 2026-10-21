@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import android.app.Activity
import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.TimeZone
import java.util.concurrent.Executor

/**
 * THE NEXT CALL, PREPARED BEFORE THE TAP (GraineCalls.prepareCall) — real
 * agents, with the main thread and every call's thread on the test's clock,
 * and the web route, the SBC and the peer faked: nothing reaches a host.
 *
 * A prepared call is minted, gathered and registered, and nothing is dialled.
 * Asked again for the same agent, user, reason and variables it sends nothing;
 * anything else prepares afresh. The tap that matches dials it at once (the
 * INVITE, under its id), its twelve seconds counted from the dial; any other
 * tap prepares as it always did. It is let go — never dialled for anyone else
 * — when the user changes in any field (identify, sign-out, the memory choice,
 * the time zone), when a ring arrives, when the app goes to the background,
 * when calls leave WebRTC, after four minutes, or when its socket drops. And it
 * is nothing at all without the WebRTC module, over the WebSocket, before the
 * session, or while a call rings.
 */
class PrepareCallTest {
    private class TestApp : Application() {
        /** The SDK's activity watcher, kept so the test can say which screen is in front. */
        var lifecycle: ActivityLifecycleCallbacks? = null
        override fun getPackageName(): String = "com.acme.app"
        override fun registerActivityLifecycleCallbacks(callback: ActivityLifecycleCallbacks) { lifecycle = callback }
    }

    /** The phone under the call: its lock screen as the test says, audio that does nothing, and a route that says where it went. */
    private class FakePhone : CallPhone {
        var locked = false
        /** A headset is connected: the loudspeaker by default gives way to it. */
        var headset = false
        /** Each acquire: the loudspeaker wanted, and whether by default. */
        val acquired = mutableListOf<Pair<Boolean, Boolean>>()
        val choices = mutableListOf<Boolean>()
        var refreshes = 0
        /** What the phone answers a focus request. */
        var focusGranted = true
        /** Run at each acquire: what the test wants to see at that moment. */
        var onAcquire: () -> Unit = {}
        var reapplies = 0
        /** The interruptions the route can tell, for the test to raise. */
        var interrupt: (CallInterruption) -> Unit = {}
        override fun locked(): Boolean = locked
        override fun route(onInterruption: (CallInterruption) -> Unit, onSpeaker: (Boolean) -> Unit): CallRoute = object : CallRoute {
            override fun acquire(speaker: Boolean, byDefault: Boolean): Boolean {
                acquired += speaker to byDefault
                interrupt = onInterruption
                onAcquire()
                onSpeaker(speaker && !(byDefault && headset))
                return focusGranted
            }
            override fun setSpeaker(on: Boolean) { choices += on; onSpeaker(on) }
            override fun refresh() { refreshes++ }
            override fun reapply(): Boolean { reapplies++; return focusGranted }
            override fun release() {}
        }
        override fun audio(): CallAudioIO = FakeAudio()
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

    private class FakeAudio : CallAudioIO {
        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {}
        override fun play(pcm: ShortArray, sampleRate: Int) {}
        override fun clear() {}
        override fun bufferedSeconds(): Double = 0.0
        override fun stop() {}
    }

    private val web = "https://www.graine.ai"
    private val gateway = "https://gw.example.invalid/grant-session"
    private val clock = ManualScheduler()
    private val main = TestMain(clock)
    private val app = TestApp()
    private val dir = java.nio.file.Files.createTempDirectory("graine-prepare").toFile()
    private val device = DeviceStore(File(dir, "device.json"))
    private val kept = HashMap<String, String>()
    private val cache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text })
    private val sessions = HashMap<String, JSONObject>()
    private val activity = object : Activity() {}
    private val phone = FakePhone()
    private val zone: TimeZone = TimeZone.getDefault()
    private var now = 1_800_000_000_000L

    /** The web route's mints, the SBC and its sockets, the peers, and every step the calls took. */
    private val mints = mutableListOf<JSONObject>()
    private val sbc = FakeSbc(password = "pw")
    private val sipSockets = mutableListOf<FakeSipSocket>()
    private val links = HashMap<FakeSipSocket, SbcLink>()
    private val peers = mutableListOf<FakePeer>()
    private val controls = mutableListOf<FakeSipSocket>()
    private val steps = mutableListOf<String>()
    private var sessionIds = 0

    /** The direct mint's hosts: grant-only answers (none, unless [offerGrants]) and the gateway. */
    private var offerGrants = false
    private var grants = 0
    private val grantAsks = mutableListOf<JSONObject>()
    private val directMints = mutableListOf<JSONObject>()

    private fun credential() = JSONObject()
        .put("server", Trace.SERVER).put("username", "web-XXXX").put("password", "pw").put("realm", Trace.REALM)
        .put("application_sid", Trace.APP_SID).put("agent_id", "agent-web-1").put("expires_at", "2099-01-01T00:00:00+00:00")
        .put("ice_servers", JSONArray().put(JSONObject().put("urls", "stun:stun.example.invalid:80")))

    private fun offer() = JSONObject().put("grant", "v1.grant${++grants}.sig").put("expires_at", now + 900_000).put("ttl_ms", 900_000).put("url", gateway)

    private val webHttp = HttpPost { url, json ->
        if (url.endsWith("/api/embed/rtc-session")) {
            mints += JSONObject(json)
            HttpReply(200, credential().toString())
        } else {
            HttpReply(200, "{\"ok\":true}")
        }
    }

    private val direct = RtcDirectMint(
        http = object : RtcDirectHttp {
            override fun get(url: String, timeoutMs: Int): HttpReply = HttpReply(200, "{\"direct\":true}")
            override fun post(url: String, json: String, timeoutMs: Int): HttpReply {
                val body = JSONObject(json)
                return when (url) {
                    "$web/api/embed/rtc-session" -> {
                        grantAsks += body
                        HttpReply(200, if (offerGrants) JSONObject().put("direct", offer()).toString() else "{\"direct\":null}")
                    }
                    gateway -> { directMints += body; HttpReply(200, credential().toString()) }
                    else -> throw AssertionError("no such host: $url")
                }
            }
        },
        clock = { now },
        background = { it() },
        timers = ManualScheduler(),
    )

    /** A WebRTC call's world, on the test's clock: the fake SBC's sockets, the fake peer, the web route above. */
    private fun rtcEnv(agent: GraineInApp) = RtcEnv(
        scheduler = clock,
        createPeer = { _, l -> FakePeer().also { it.listener = l; peers += it } },
        http = webHttp,
        io = { it() },
        openSip = { url, protocols, l ->
            FakeSipSocket().also { s -> s.url = url; s.protocols = protocols; s.listener = l; sipSockets += s; clock.post { s.open() } }
        },
        openControl = { url, l -> FakeSipSocket().also { it.url = url; it.listener = l; controls += it } },
        sipRandom = FixedSipRandom(),
        newSessionId = { String.format("%032x", ++sessionIds) },
        wallClock = { now },
        steps = { step, detail -> steps += "$step: $detail" },
        direct = agent.env.directMint,
    )

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
        directMint = direct,
        rtcEnv = { agent -> rtcEnv(agent) },
        callPhone = phone,
    )

    private fun session(audioTransport: String? = null) = JSONObject(
        """{"agentId":"agent-a","webcallAgentId":"web-a","appearance":{"title":"A"},"identity":{"field":"user_id"},
            "calls":{"hosted":true,"platforms":["android"]${audioTransport?.let { ",\"audioTransport\":\"$it\"" } ?: ""}}}""",
    )

    @Before fun fresh() {
        AppShared.reset()
        RtcModule.override = object : GraineRtcMedia {
            override fun createPeer(context: android.content.Context, iceServers: List<GraineRtcIceServer>, listener: GraineRtcPeer.Listener): GraineRtcPeer =
                error("the calls' world is the test's own (AgentEnv.rtcEnv)")
        }
        RtcModule.runsHereOverride = true
    }

    @After fun done() {
        AppShared.reset()
        RtcModule.override = null
        RtcModule.runsHereOverride = null
        TimeZone.setDefault(zone)
        dir.deleteRecursively()
    }

    /** Let the main thread and the calls' threads run, and the SBC answer, until nothing moves. */
    private fun pump() {
        repeat(10) {
            clock.advance(0)
            for (s in sipSockets.toList()) links.getOrPut(s) { SbcLink(s, sbc, clock) }.pump()
        }
        clock.advance(0)
    }

    /** [ms] going by a second at a time, the SBC answering as it goes (a refreshed REGISTER among it). */
    private fun idle(ms: Long) {
        var left = ms
        while (left > 0) {
            val step = Math.min(1_000L, left)
            clock.advance(step)
            pump()
            left -= step
        }
    }

    private fun agent(name: String = GraineInApp.DEFAULT, key: String = "pk_a", audioTransport: String? = null): GraineInApp {
        sessions[key] = session(audioTransport)
        return GraineInApp.make(app, GraineConfig(web, key), name) { env() }
    }

    /** Made, its session arrived. */
    private fun started(audioTransport: String? = null): GraineInApp = agent(audioTransport = audioTransport).also { pump() }

    private val vars = mapOf("order" to "A-1")

    /** prepareCall, and a public candidate in the peer's pool — signalled with the offer, at the dial. */
    private fun prepare(g: GraineInApp, reason: String? = "help", variables: Map<String, Any?> = vars, agent: String = g.name) {
        val before = peers.size
        g.calls.prepareCall(reason, variables, agent)
        pump()
        if (peers.size > before) peers.last().candidate("srflx")
        pump()
    }

    private fun released(socket: FakeSipSocket) = socket.closed != null && socket.requests("REGISTER").last().header("expires") == "0"

    /** The call's session as GraineCalls opens it once its service is up — here on the test's clock — and dialled. */
    private val fallbacks = mutableListOf<String>()
    private fun dial(g: GraineInApp, callId: String, reason: String?, variables: Map<String, String>): CallTransportSession {
        val config = CallSessionConfig(
            baseUrl = web, publishableKey = g.config.publishableKey, agentId = "agent-a", webcallAgentId = "web-a", wsHost = null,
            callVariables = CallMachine.outgoingVariables(reason, variables), sdkVariables = g.currentVariables(),
            identity = g.identityEnvelope(), callId = callId,
        )
        val deps = CallSessionDeps(
            scheduler = clock, audio = FakeAudio(), http = webHttp,
            openSocket = { url, _, l -> FakeSipSocket().also { it.url = url; it.listener = l } },
            io = { it() },
        )
        val s = CallTransportSession(config, deps, object : CallSessionEvents {
            override fun onFailed(error: String) { fallbacks += "failed: $error" }
        })
        s.start()
        pump()
        return s
    }

    // ── preparing ───────────────────────────────────────────────────────────

    @Test fun aPreparedCallIsMintedGatheredAndRegisteredAndNothingIsDialled() {
        val g = started()
        prepare(g)
        val p = g.calls.preparedFor(g)
        assertNotNull(p)
        val m = mints.single()
        assertEquals("under the id the call will carry", p!!.callId, m.getString("callId"))
        assertEquals("help", m.getJSONObject("variables").getString("call_reason"))
        assertEquals("customer", m.getJSONObject("variables").getString("who_called"))
        assertEquals("A-1", m.getJSONObject("variables").getString("order"))
        val sip = sipSockets.single()
        assertTrue("registered", sip.requests("REGISTER").size >= 2)
        assertTrue("nothing dialled", sip.requests("INVITE").isEmpty())
        assertEquals(GraineCallState.IDLE, g.calls.state)
        assertTrue("its debug line, without an id or a user", steps.any { it.matches(Regex("prepared: \\d+ ms \\(web\\)")) })
    }

    @Test fun preparingAgainForTheSameCallSendsNothing() {
        val g = started()
        prepare(g)
        idle(60_000)
        prepare(g)
        prepare(g, variables = mapOf("order" to "A-1"))
        assertEquals("one mint", 1, mints.size)
        assertEquals("one socket", 1, sipSockets.size)
        assertEquals(1, peers.size)
    }

    @Test fun aDifferentReasonOrVariablesPreparesAfresh() {
        val g = started()
        prepare(g)
        val first = g.calls.preparedFor(g)!!
        prepare(g, reason = "billing")
        assertTrue("the first one let go", released(sipSockets[0]))
        assertEquals(2, mints.size)
        assertEquals("billing", mints[1].getJSONObject("variables").getString("call_reason"))
        assertNotEquals(first.callId, g.calls.preparedFor(g)!!.callId)
        prepare(g, reason = "billing", variables = mapOf("order" to "B-2"))
        assertEquals(3, mints.size)
        assertTrue(released(sipSockets[1]))
    }

    @Test fun aPreparedCallIsKeptFourMinutesThenLetGo() {
        val g = started()
        prepare(g)
        idle(4 * 60_000L - 1_000)
        assertNotNull("still ready, still registered", g.calls.preparedFor(g))
        assertFalse(released(sipSockets.single()))
        idle(1_000)
        assertNull("four minutes: let go", g.calls.preparedFor(g))
        assertTrue(released(sipSockets.single()))
        assertTrue("never dialled", sipSockets.single().requests("INVITE").isEmpty())
        prepare(g)
        assertEquals("prepared afresh", 2, mints.size)
    }

    @Test fun aPreparedCallMintsAtTheGatewayWithTheWarmGrant() {
        offerGrants = true
        val g = started()
        val grantFor = grantAsks.single().getString("callId")
        prepare(g)
        assertTrue("never the web route", mints.isEmpty())
        assertEquals(grantFor, g.calls.preparedFor(g)!!.callId)
        assertEquals("v1.grant1.sig", directMints.single().getString("grant"))
        assertTrue(steps.any { it.matches(Regex("prepared: \\d+ ms \\(direct\\)")) })
    }

    // ── the tap ─────────────────────────────────────────────────────────────

    @Test fun theTapThatMatchesDialsThePreparedCallAtOnce() {
        val g = started()
        prepare(g)
        val p = g.calls.preparedFor(g)!!
        idle(30_000)
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)
        assertEquals("the prepared call's id", p.callId, id)
        assertNull(g.calls.preparedFor(g))
        dial(g, id!!, "help", vars)
        assertEquals("no second mint", 1, mints.size)
        assertEquals("no second socket", 1, sipSockets.size)
        val invites = sipSockets.single().requests("INVITE")
        assertTrue("the INVITE", invites.isNotEmpty())
        assertEquals(p.callId, mints.single().getString("callId"))
        assertTrue(steps.any { it.startsWith("dial: prepared 30.") && it.endsWith("s ago") })
        peers.single().ice("connected"); pump()
        assertTrue(steps.any { it.startsWith("media:") })
        assertTrue(fallbacks.isEmpty())
    }

    /** The device finding of 19 Oct: a call prepared on the Home screen must not hold the microphone — the privacy dot. */
    @Test fun aPreparedCallHoldsNoMicrophoneUntilItsTapDialsIt() {
        val g = started()
        prepare(g)
        val peer = peers.single()
        assertEquals("prepared: recording and playout off", listOf(false to false), peer.audioActive)
        idle(30_000)
        assertFalse(peer.recording || peer.playout)
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)!!
        dial(g, id, "help", vars)
        assertEquals("on with its dial", true to true, peer.audioActive.last())
    }

    @Test fun aPreparedCallMakesNoOfferUntilItsTap() {
        val g = started()
        prepare(g)
        val peer = peers.single()
        idle(30_000)
        assertFalse("prepared, and no offer: nothing set up to record", peer.calls.contains("createOffer"))
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)!!
        dial(g, id, "help", vars)
        assertEquals("the offer, at the dial", 1, peer.calls.count { it == "createOffer" })
        assertTrue(steps.any { it.matches(Regex("offer: \\d+ ms at the dial \\(pooled candidates: 1\\)")) })
        assertTrue(sipSockets.single().requests("INVITE").isNotEmpty())
    }

    @Test fun aRingsPreparedCallMakesNoOfferUntilItsAnswer() {
        val g = started()
        inFront(g)
        ring(g, "sc_ring_offer")
        val peer = peers.single()
        assertFalse("ringing: no offer", peer.calls.contains("createOffer"))
        assertTrue(peer.audioActive.none { it.first || it.second })
        g.calls.answer()
        pump()
        assertEquals("the answer makes it", 1, peer.calls.count { it == "createOffer" })
        assertTrue(invites() > 0)
        g.calls.end(); pump()
    }

    @Test fun aPreparedCallLetGoNeverRecorded() {
        val g = started()
        prepare(g)
        idle(4 * 60_000L)
        assertNull("let go after four minutes", g.calls.preparedFor(g))
        val peer = peers.single()
        assertTrue(peer.closed)
        assertEquals("turned off as it was made, and never on", listOf(false to false), peer.audioActive)
        assertFalse("and it never made an offer", peer.calls.contains("createOffer"))
    }

    @Test fun aPreparedCallsTwelveSecondsStartAtItsDial() {
        val g = started()
        prepare(g)
        idle(60_000)
        assertFalse("waiting is not dialling: nothing fell back", steps.any { it.startsWith("fallback") })
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)!!
        // From the dial on, the SBC says nothing: only the attempt's budget ends it — twelve seconds after the dial.
        sbc.deaf = true
        dial(g, id, "help", vars)
        idle(11_000)
        assertFalse(steps.any { it.startsWith("fallback") })
        idle(1_000)
        assertTrue(steps.any { it.startsWith("fallback") && it.contains("12 s after the call could start") })
    }

    @Test fun aTapThatDoesNotMatchLetsThePreparedCallGoAndPreparesAsAlways() {
        val g = started()
        prepare(g)
        val p = g.calls.preparedFor(g)!!
        val id = g.calls.startCall(activity, "billing", vars, agent = g.name)
        pump()
        assertNotEquals(p.callId, id)
        assertTrue("let go", released(sipSockets[0]))
        assertEquals("prepared at the tap, as always", 2, mints.size)
        assertEquals(id, mints[1].getString("callId"))
        assertEquals("billing", mints[1].getJSONObject("variables").getString("call_reason"))
    }

    @Test fun aPreparedCallWhoseSocketDropsIsLetGoSilently() {
        val g = started()
        prepare(g)
        val p = g.calls.preparedFor(g)!!
        sipSockets.single().drop()
        pump()
        assertNull(g.calls.preparedFor(g))
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)
        pump()
        assertNotEquals(p.callId, id)
        assertEquals("prepared at the tap, as always", 2, mints.size)
    }

    // ── who the call is for ─────────────────────────────────────────────────

    @Test fun anotherUsersCallNeverDialsThePreparedOne() {
        val g = started()
        g.identify(mapOf("user_id" to "user-a")); pump()
        prepare(g)
        val forA = g.calls.preparedFor(g)!!
        assertEquals("user-a", mints.single().getJSONObject("identity").getString("value"))
        // A signs out; B signs in and calls.
        g.identify(null); pump()
        assertNull("let go the moment A left", g.calls.preparedFor(g))
        assertTrue(released(sipSockets.single()))
        g.identify(mapOf("user_id" to "user-b")); pump()
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)
        pump()
        assertNotEquals(forA.callId, id)
        assertEquals("B's call is minted for B", "user-b", mints.last().getJSONObject("identity").getString("value"))
        assertEquals(id, mints.last().getString("callId"))
        assertTrue("and A's was never dialled", sipSockets.first().requests("INVITE").isEmpty())
    }

    @Test fun aNewMemoryChoiceMintsTheCallAfresh() {
        val g = started()
        g.identify(mapOf("user_id" to "user-a")); pump()
        prepare(g)
        val p = g.calls.preparedFor(g)!!
        assertTrue(mints.single().getBoolean("memory"))
        g.setMemory(false); pump()
        assertNull(g.calls.preparedFor(g))
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)
        pump()
        assertNotEquals(p.callId, id)
        assertFalse("the call carries memory off", mints.last().getBoolean("memory"))
    }

    @Test fun aNewTimeZoneLetsThePreparedCallGoAndATapNeverDialsIt() {
        val g = started()
        val other = if (zone.id == "Asia/Tokyo") "Europe/Paris" else "Asia/Tokyo"
        prepare(g)
        TimeZone.setDefault(TimeZone.getTimeZone(other))
        g.calls.timeZoneChanged(); pump()
        assertNull("let go when the zone changed", g.calls.preparedFor(g))
        // And without the system's word: a tap under a new zone never dials one prepared under the old.
        TimeZone.setDefault(zone)
        prepare(g)
        val p = g.calls.preparedFor(g)!!
        TimeZone.setDefault(TimeZone.getTimeZone(other))
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)
        pump()
        assertNotEquals(p.callId, id)
        assertEquals(other, mints.last().getString("timezone"))
    }

    @Test fun eachAgentsPreparedCallIsOnlyThatAgents() {
        val support = agent(GraineInApp.DEFAULT, "pk_support")
        val sales = agent("sales", "pk_sales")
        pump()
        prepare(support)
        prepare(sales)
        val forSupport = support.calls.preparedFor(support)!!
        val forSales = support.calls.preparedFor(sales)!!
        assertNotEquals(forSupport.callId, forSales.callId)
        assertEquals("pk_sales", mints[1].getString("publishableKey"))
        val id = support.calls.startCall(activity, "help", vars, agent = "sales")
        pump()
        assertEquals("sales' call, sales' prepared call", forSales.callId, id)
        assertNull("support's let go: one call at a time", support.calls.preparedFor(support))
        assertTrue(released(sipSockets[0]))
        assertTrue("never dialled", sipSockets[0].requests("INVITE").isEmpty())
        assertEquals(2, mints.size)
    }

    // ── let go ──────────────────────────────────────────────────────────────

    @Test fun aRingWinsOverThePreparedCall() {
        val g = started()
        prepare(g)
        assertTrue(g.calls.handlePush(mapOf("type" to "agent_call", "call_id" to "sc_ring_1", "hosted" to "1")))
        pump()
        assertNull(g.calls.preparedFor(g))
        assertTrue(released(sipSockets[0]))
        assertEquals(GraineCallState.RINGING, g.calls.state)
        assertEquals("the ring prepares its own", "sc_ring_1", mints.last().getString("callId"))
        g.calls.decline(); pump()
    }

    @Test fun theBackgroundLetsThePreparedCallGo() {
        val g = started()
        prepare(g)
        g.foregroundWatchers.forEach { it(false) }
        pump()
        assertNull(g.calls.preparedFor(g))
        assertTrue(released(sipSockets.single()))
    }

    @Test fun callsLeavingWebRtcLetThePreparedCallGo() {
        val g = started()
        prepare(g)
        g.calls.configure(GraineCallsConfig(callTransport = GraineCallTransport.WEBSOCKET))
        pump()
        assertNull(g.calls.preparedFor(g))
        assertTrue(released(sipSockets.single()))
        prepare(g)
        assertEquals("nothing prepared over the WebSocket", 1, mints.size)
    }

    // ── nothing at all ──────────────────────────────────────────────────────

    @Test fun withoutTheWebRtcModuleNothingIsPrepared() {
        RtcModule.override = null
        val g = started()
        prepare(g)
        assertNull(g.calls.preparedFor(g))
        assertTrue(mints.isEmpty() && sipSockets.isEmpty())
    }

    @Test fun callsTheDashboardSendsOverTheWebSocketPrepareNothing() {
        val g = started(audioTransport = "websocket")
        prepare(g)
        assertNull(g.calls.preparedFor(g))
        assertTrue(mints.isEmpty())
    }

    @Test fun nothingIsPreparedBeforeTheSession() {
        val g = agent()
        g.calls.prepareCall("help", vars, g.name)
        assertNull(g.calls.preparedFor(g))
        assertTrue(mints.isEmpty())
        pump()
        assertTrue("the session's arrival prepares nothing by itself", mints.isEmpty())
    }

    @Test fun nothingIsPreparedWhileACallRingsOrIsLive() {
        val g = started()
        assertTrue(g.calls.handlePush(mapOf("type" to "agent_call", "call_id" to "sc_ring_2", "hosted" to "1")))
        pump()
        prepare(g)
        assertNull(g.calls.preparedFor(g))
        assertEquals("only the ring's own", listOf("sc_ring_2"), mints.map { it.getString("callId") })
        g.calls.decline(); pump()
        val id = g.calls.startCall(activity, "help", vars, agent = g.name)
        pump()
        prepare(g)
        assertNull("nor beside a call", g.calls.preparedFor(g))
        assertEquals(listOf("sc_ring_2", id), mints.map { it.getString("callId") })
    }

    // ── the tap's wait: at once in the app in front, else with the service ──

    /** [a] comes to the front, as the activities tell the call engine (made now, if it was not yet). */
    private fun inFront(g: GraineInApp, a: Activity = activity) {
        assertNotNull(g.calls)
        app.lifecycle!!.onActivityResumed(a)
    }

    /** The INVITEs on the calls' signalling sockets so far. */
    private fun invites() = sipSockets.sumOf { it.requests("INVITE").size }

    /** A ring from Graine, its call prepared as it rings: minted, gathered, registered. */
    private fun ring(g: GraineInApp, id: String) {
        assertTrue(g.calls.handlePush(mapOf("type" to "agent_call", "call_id" to id, "hosted" to "1")))
        pump()
        peers.last().candidate("srflx")
        pump()
        assertEquals(GraineCallState.RINGING, g.calls.state)
    }

    /**
     * Measured on a device, 19 Oct: 0.44 s from the tap to the dial, the INVITE
     * waiting for the foreground service on a busy main thread. A tap in the app
     * in front may record already: the prepared call dials at the tap, and the
     * service, when it comes, changes nothing.
     */
    @Test fun aTapInTheAppInFrontDialsAtOnceWithoutWaitingForTheService() {
        val g = started()
        inFront(g)
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        val dialled = invites()
        assertTrue("the INVITE left at the tap, the service not up yet", dialled > 0)
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertEquals("the service changes nothing: no second session, no second dial", dialled, invites())
        assertEquals(1, sipSockets.size)
        assertEquals(1, peers.size)
        g.calls.end(); pump()
    }

    @Test fun withNothingOfTheAppInFrontTheCallWaitsForItsService() {
        val g = started()
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        assertEquals("no screen of the app in front: nothing dialled before the service", 0, invites())
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertTrue("the service is up: the dial", invites() > 0)
        g.calls.end(); pump()
    }

    @Test fun aScreenThatLeftTheFrontNoLongerCounts() {
        val g = started()
        inFront(g)
        app.lifecycle!!.onActivityPaused(activity)
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        assertEquals(0, invites())
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertTrue(invites() > 0)
        g.calls.end(); pump()
    }

    @Test fun aRingAnsweredInTheAppInFrontDialsAtOnce() {
        val g = started()
        inFront(g)
        ring(g, "sc_ring_front")
        g.calls.answer()
        pump()
        assertTrue("the ring's prepared call dialled at the answer", invites() > 0)
        g.calls.end(); pump()
    }

    @Test fun aRingAnsweredOverTheLockScreenWaitsForItsService() {
        val g = started()
        inFront(g)
        phone.locked = true
        ring(g, "sc_ring_locked")
        g.calls.answer()
        pump()
        assertEquals("locked: nothing dialled before the service", 0, invites())
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertTrue(invites() > 0)
        g.calls.end(); pump()
    }

    @Test fun aRingAnsweredFromItsNotificationWaitsForItsService() {
        val g = started()
        inFront(g)
        ring(g, "sc_ring_notification")
        // The notification's Answer opens the call screen with ACTION_ANSWER, which answers this way.
        g.calls.answer(fromNotification = true)
        pump()
        assertEquals("the notification's button: nothing dialled before the service", 0, invites())
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertTrue(invites() > 0)
        g.calls.end(); pump()
    }

    @Test fun aCallBackFromTheMissedCallNotificationWaitsForItsService() {
        val g = started()
        inFront(g)
        // The missed call's Call back opens the call screen with ACTION_CALL_BACK, which places the call this way.
        g.calls.callBack(activity, null, null, null, fromNotification = true)
        pump()
        peers.last().candidate("srflx")
        pump()
        assertEquals("the notification's button: nothing dialled before the service", 0, invites())
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertTrue(invites() > 0)
        g.calls.end(); pump()
    }

    // ── the call's audio always starts, and the SDK never ends a call that works ──

    /** A call placed at the tap in the app in front, connected: its audio is flowing. */
    private fun connectedAtTheTap(g: GraineInApp): GraineCallService {
        inFront(g)
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        assertTrue("dialled at the tap, the service not up yet", invites() > 0)
        peers.single().ice("connected"); pump()
        assertEquals(GraineCallState.ACTIVE, g.calls.state)
        return GraineCallService()
    }

    private fun outcomesOf(g: GraineInApp) = mutableListOf<GraineCallOutcome>().also { list ->
        g.calls.addListener(object : GraineCallListener {
            override fun onCallOutcome(callId: String, outcome: GraineCallOutcome) { list += outcome }
        })
    }

    @Test fun aServiceThatCannotStartNeverEndsACallWhoseAudioIsFlowing() {
        val g = started()
        val outcomes = outcomesOf(g)
        connectedAtTheTap(g)
        val byes = sipSockets.sumOf { it.requests("BYE").size }
        g.calls.serviceFailed(IllegalStateException("startForeground not allowed"))
        pump()
        assertEquals("the call goes on", GraineCallState.ACTIVE, g.calls.state)
        assertTrue("nothing reported: it did not end", outcomes.isEmpty())
        assertEquals("no BYE", byes, sipSockets.sumOf { it.requests("BYE").size })
        g.calls.end(); pump()
        assertEquals("local_end", outcomes.single().endCause)
    }

    @Test fun aServiceTheSystemStopsNeverEndsACallWhoseAudioIsFlowing() {
        val g = started()
        val outcomes = outcomesOf(g)
        val service = connectedAtTheTap(g)
        g.calls.serviceStarted(service); pump()
        g.calls.serviceStopped(service); pump()
        assertEquals(GraineCallState.ACTIVE, g.calls.state)
        assertTrue(outcomes.isEmpty())
        g.calls.end(); pump()
        assertEquals(GraineCallOutcome.Kind.ENDED, outcomes.single().kind)
    }

    @Test fun aCallWaitingForItsServiceStartsWithoutItWhenTheAppIsInFrontAndFailsWhenItCannotRecord() {
        val g = started()
        val outcomes = outcomesOf(g)
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        assertEquals("nothing of the app in front: waiting for the service", 0, invites())
        inFront(g)
        g.calls.serviceFailed(IllegalStateException("startForeground not allowed")); pump()
        assertTrue("the app is in front now: it may record, so the call starts", invites() > 0)
        assertEquals(GraineCallState.CONNECTING, g.calls.state)
        g.calls.end(); pump()
        outcomes.clear()
        // Nothing of the app in front, nothing dialled: it cannot record, so it fails — and says so.
        app.lifecycle!!.onActivityPaused(activity)
        val before = invites()
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        g.calls.serviceFailed(IllegalStateException("startForeground not allowed")); pump()
        assertEquals("nothing dialled", before, invites())
        assertEquals(GraineCallOutcome.Kind.FAILED, outcomes.single().kind)
        assertEquals("failed", outcomes.single().endCause)
    }

    @Test fun theRouteIsSetBeforeAnyOfTheAgentsAudioCanPlay() {
        val g = started()
        var invitesAtAcquire = -1
        var playingAtAcquire: Boolean? = null
        phone.onAcquire = {
            invitesAtAcquire = invites()
            playingAtAcquire = peers.lastOrNull()?.audioActive?.any { it.second } == true
        }
        connectedAtTheTap(g)
        assertEquals("the mode, the focus and the device before the INVITE", 0, invitesAtAcquire)
        assertEquals("and before any playout", false, playingAtAcquire)
        assertTrue("then the agent's audio plays", peers.single().audioActive.last().second)
        g.calls.end(); pump()
    }

    @Test fun aFocusThePhoneDeniesIsSaidAndTheCallPlaysAnyway() {
        val g = started()
        phone.focusGranted = false
        connectedAtTheTap(g)
        assertTrue("never silent: the playout runs", peers.single().audioActive.last().second)
        assertEquals(GraineCallState.ACTIVE, g.calls.state)
        g.calls.end(); pump()
    }

    @Test fun noAgentAudioThreeSecondsAfterTheAnswerAppliesTheRouteAgainOnceAndNeverEndsTheCall() {
        val g = started()
        val outcomes = outcomesOf(g)
        connectedAtTheTap(g)
        clock.advance(GraineCalls.AUDIO_CHECK_MS - 1)
        assertEquals(0, phone.reapplies)
        clock.advance(1)
        assertEquals("once, at three seconds", 1, phone.reapplies)
        idle(60_000)
        assertEquals("never again", 1, phone.reapplies)
        assertEquals("and the call goes on: there is no watchdog that hangs up", GraineCallState.ACTIVE, g.calls.state)
        assertTrue(outcomes.isEmpty())
        g.calls.end(); pump()
    }

    @Test fun theAgentsAudioHeardInTimeLeavesTheRouteAloneAndIsKeptOnTheEndedRow() {
        val g = started()
        connectedAtTheTap(g)
        controls.single().deliver("{\"type\":\"control_ready\"}"); pump()
        peers.single().stats = rtcStats(bytes = 1600, level = 0.3)
        clock.advance(400); pump()
        clock.advance(GraineCalls.AUDIO_CHECK_MS); pump()
        assertEquals("heard: nothing to apply again", 0, phone.reapplies)
        g.calls.end(); pump()
        val ended = controls.single().sent.map { JSONObject(it) }
            .single { it.optString("type") == "app_event" && it.getJSONObject("event").getString("name") == "ended" }
        assertTrue("voice_heard on the ended row", ended.getJSONObject("event").getJSONObject("data").getBoolean("voice_heard"))
    }

    /** Calls start on the loudspeaker (the earpiece was the "silent call"), a headset keeps the call, and the user still decides. */
    @Test fun aCallStartsOnTheLoudspeakerUnlessAHeadsetIsThereAndTheUserStillDecides() {
        val g = started()
        val heardSpeaker = mutableListOf<Boolean>()
        g.calls.addListener(object : GraineCallListener {
            override fun onCallControls(muted: Boolean, speaker: Boolean) { heardSpeaker += speaker }
        })
        assertTrue("the default", g.calls.speakerByDefault)
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertEquals("the loudspeaker, by default", listOf(true to true), phone.acquired)
        assertTrue("the state is the route", g.calls.speaker)
        peers.single().ice("connected"); pump()
        assertEquals("chosen again as the call's audio starts", 1, phone.refreshes)
        g.calls.setSpeaker(false); pump()
        assertFalse("the user's choice: the earpiece", g.calls.speaker)
        assertEquals(listOf(true, false), heardSpeaker)
        g.calls.end(); pump()
        assertFalse("no call, no loudspeaker", g.calls.speaker)
        // The next call: a headset keeps it.
        phone.headset = true
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertEquals(true to true, phone.acquired.last())
        assertFalse("the headset has the call", g.calls.speaker)
        g.calls.end(); pump()
        // Set off before a call: the earpiece first, as before.
        g.calls.speakerByDefault = false
        phone.headset = false
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertEquals(false to true, phone.acquired.last())
        assertFalse(g.calls.speaker)
        g.calls.end(); pump()
    }

    @Test fun anEndedCallSaysWhoEndedIt() {
        val g = started()
        val outcomes = mutableListOf<GraineCallOutcome>()
        g.calls.addListener(object : GraineCallListener {
            override fun onCallOutcome(callId: String, outcome: GraineCallOutcome) { outcomes += outcome }
        })
        prepare(g)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        g.calls.serviceStarted(GraineCallService())
        pump()
        peers.single().ice("connected"); pump()
        controls.single().deliver("{\"type\":\"control_ready\"}"); pump()
        g.calls.end(); pump()
        assertEquals("the user ended it", "local_end", outcomes.last().endCause)
        assertEquals(GraineCallOutcome.EndReason.LOCAL, outcomes.last().endReason)
        // The hang-up told the agent's side why, on the call's own channel.
        val ended = controls.single().sent.map { JSONObject(it) }
            .single { it.optString("type") == "app_event" && it.getJSONObject("event").getString("name") == "ended" }
        assertEquals("local_end", ended.getJSONObject("event").getJSONObject("data").getString("cause"))
    }

    /** The same facts as the rows, on GraineCalls: growing as the call goes, heard by its listeners, and on its outcome. */
    @Test fun theConnectionReportGrowsAsTheCallGoesAndRidesItsOutcome() {
        val g = started()
        val heard = mutableListOf<GraineCallConnection>()
        val outcomes = mutableListOf<GraineCallOutcome>()
        g.calls.addListener(object : GraineCallListener {
            override fun onCallConnection(connection: GraineCallConnection) { heard += connection }
            override fun onCallOutcome(callId: String, outcome: GraineCallOutcome) { outcomes += outcome }
        })
        prepare(g)
        idle(30_000)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        clock.advance(400)
        g.calls.serviceStarted(GraineCallService())
        pump()
        assertNull("nothing known before it connects", g.calls.connection)
        clock.advance(60)
        peers.single().ice("connected"); pump()
        val c = g.calls.connection!!
        assertEquals("webrtc", c.transport)
        assertEquals(true, c.prepared)
        assertEquals(30L, c.preparedAgeSeconds)
        assertEquals("from the tap, the service's 400 ms inside", 400L, c.setupMs)
        assertNull("logged in before the tap", c.loginMs)
        assertEquals("the listeners heard it", c, heard.last())
        g.calls.end(); pump()
        val ended = outcomes.last()
        assertEquals(GraineCallOutcome.Kind.ENDED, ended.kind)
        assertEquals("the outcome carries the report as it stood", c, ended.connection)
        assertNull("no call, no report", g.calls.connection)
    }

    /**
     * The analytics rows count from the tap GraineCalls saw, on the calls' own
     * clock — here a tap with nothing of the app in front, whose dial waits
     * 400 ms for the service — and say the prepared call's age at the tap.
     */
    @Test fun theConnectRowCountsFromTheTapAndSaysThePreparedCallsAge() {
        val g = started()
        prepare(g)
        idle(30_000)
        g.calls.startCall(activity, "help", vars, agent = g.name)!!
        pump()
        clock.advance(400)
        g.calls.serviceStarted(GraineCallService())
        pump()
        clock.advance(60)
        peers.single().ice("connected"); pump()
        controls.single().deliver("{\"type\":\"control_ready\"}"); pump()
        val connect = controls.single().sent.map { JSONObject(it) }.single { it.optString("type") == "app_event" && it.getJSONObject("event").getString("name") == "connect" }
        val data = connect.getJSONObject("event").getJSONObject("data")
        assertEquals("prepared", data.getString("mode"))
        assertEquals("prepared thirty seconds before the tap", 30, data.getInt("prepared_age_s"))
        assertEquals("from the tap, the service's 400 ms inside", 400, data.getInt("total_ms"))
        assertEquals(400, data.getInt("setup_ms"))
        assertEquals(60, data.getInt("ice_ms"))
        g.calls.end(); pump()
    }

    @Test fun theRuleIsInFrontUnlockedAndNotFromANotification() {
        assertTrue(CallStart.atOnce(appInFront = true, locked = false, fromNotification = false))
        assertFalse(CallStart.atOnce(appInFront = false, locked = false, fromNotification = false))
        assertFalse(CallStart.atOnce(appInFront = true, locked = true, fromNotification = false))
        assertFalse(CallStart.atOnce(appInFront = true, locked = false, fromNotification = true))
    }
}
