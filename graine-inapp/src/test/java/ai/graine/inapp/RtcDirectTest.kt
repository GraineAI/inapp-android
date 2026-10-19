@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * THE DIRECT MINT (RtcDirect.kt), every host faked: the web route, the
 * gateway and the SBC. A grant kept warm for the next call, and the call that
 * takes its id minting at the gateway; the web route when there is no grant
 * for the call, and the grant its answer brings kept for that call alone; a
 * refused, spent or unreachable gateway sending the call to the web route at
 * once; a gate's refusal at the gateway read exactly as the web route's; a
 * grant's life from its arrival, on the wall clock — through the phone's
 * sleep — renewed past half of it and never used in its last 30 s; a warm-up
 * that sends nothing while the grant in hand holds; another user's grant
 * forgotten; a ringing call's own grant; the probe asked before anything
 * relies on it; and the ten-minute rest — two requests in a row with no
 * answer, two refused grants, a gateway that says no — after which every
 * route is asked again and the grants in hand are still there.
 */
class RtcDirectTest {
    private val web = "https://www.graine.ai"
    private val gateway = "https://gw.example.invalid/grant-session"
    private val key = "pk_test_key"
    private val user = IdentityEnvelope(value = "u-42", installId = "inst-1", memory = true, timezone = "Asia/Kolkata")
    private val sid = "fedcba9876543210fedcba9876543210"

    /** This phone's wall clock, which a grant's life and a rest are counted on. */
    private var now = 1_800_000_000_000L
    /** The clock a rest's timer runs on: it stops while the phone sleeps. */
    private val restTimers = ManualScheduler()
    private var grants = 0
    private var ids = 0

    /** The web route's grant-only answers, the gateway's probe and its mint: what each was asked, and what each says. */
    private inner class Hosts : RtcDirectHttp {
        val grantAsks: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
        val directMints: MutableList<JSONObject> = Collections.synchronizedList(ArrayList())
        @Volatile var probes = 0
        var grantReply: (JSONObject) -> HttpReply = { HttpReply(200, JSONObject().put("direct", offer()).toString()) }
        var probeReply: () -> HttpReply = { HttpReply(200, "{\"direct\":true}") }
        var directReply: (JSONObject) -> HttpReply = { HttpReply(200, credential().toString()) }

        override fun get(url: String, timeoutMs: Int): HttpReply {
            assertEquals("the probe asks the offer's own address", gateway, url)
            probes++
            return probeReply()
        }

        override fun post(url: String, json: String, timeoutMs: Int): HttpReply {
            val body = JSONObject(json)
            return when (url) {
                "$web/api/embed/rtc-session" -> { grantAsks += body; grantReply(body) }
                gateway -> { directMints += body; directReply(body) }
                else -> throw AssertionError("no such host: $url")
            }
        }
    }

    private val hosts = Hosts()

    /** A grant as the web route writes it: each a new one, so a test can tell which was used. */
    private fun offer(ttl: Long? = 900_000, expiresAt: Long = now + 900_000) = JSONObject()
        .put("grant", "v1.grant${++grants}.sig").put("expires_at", expiresAt).put("url", gateway)
        .apply { if (ttl != null) put("ttl_ms", ttl) }

    private fun credential() = JSONObject()
        .put("server", Trace.SERVER).put("username", "web-XXXX").put("password", "pw").put("realm", Trace.REALM)
        .put("application_sid", Trace.APP_SID).put("agent_id", "agent-web-1").put("expires_at", "2099-01-01T00:00:00+00:00")
        .put("ice_servers", JSONArray().put(JSONObject().put("urls", "stun:stun.example.invalid:80")))

    private fun fresh(background: (() -> Unit) -> Unit = { it() }) =
        RtcDirectMint(http = hosts, clock = { now }, background = background, timers = restTimers, newCallId = { "call_next_${++ids}" })

    /** Ten minutes on both clocks: the phone awake through a rest. */
    private fun restAwake() {
        now += RtcDirectMint.REST_MS
        restTimers.advance(RtcDirectMint.REST_MS)
    }

    private var direct = fresh()

    // ── a WebRTC call over the fake SBC, as RtcCallSessionTest places one ──

    private val sched = ManualScheduler()
    private val sbc = FakeSbc(password = "pw")
    private val sipSockets = mutableListOf<FakeSipSocket>()
    private val peers = mutableListOf<FakePeer>()
    private val links = HashMap<FakeSipSocket, SbcLink>()
    private val webMints = mutableListOf<JSONObject>()
    private var webReply: () -> HttpReply = { HttpReply(200, credential().toString()) }
    private val log = mutableListOf<String>()
    private val steps = mutableListOf<String>()

    private fun env() = RtcEnv(
        scheduler = sched,
        createPeer = { _, l -> FakePeer().also { it.listener = l; peers += it } },
        http = HttpPost { url, json ->
            assertEquals("$web/api/embed/rtc-session", url)
            webMints += JSONObject(json)
            webReply()
        },
        io = { it() },
        openSip = { url, protocols, l ->
            FakeSipSocket().also { s -> s.url = url; s.protocols = protocols; s.listener = l; sipSockets += s; sched.post { s.open() } }
        },
        openControl = { url, l -> FakeSipSocket().also { it.url = url; it.listener = l } },
        sipRandom = FixedSipRandom(),
        newSessionId = { "0123456789abcdef0123456789abcdef" },
        steps = { step, detail -> steps += "$step: $detail" },
        direct = direct,
    )

    private fun request(callId: String?, who: IdentityEnvelope? = user) = RtcCallRequest(
        baseUrl = web,
        publishableKey = key,
        variables = RtcMint.variables(mapOf("who_called" to "customer", "call_reason" to "kyc_help"), mapOf("name" to "Asha")),
        identity = who,
        callId = callId,
        wsHost = "wss://ws.graine.ai",
    )

    private val listener = object : RtcCallSession.Listener {
        override fun onMedia() { log += "media" }
        override fun onFallback(reason: String, detail: String) { log += "fallback $reason" }
        override fun onRefused(message: String) { log += "refused $message" }
        override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { log += "ended ${reason.wire}" }
    }
    private val deps = RtcDialDeps({ _, _, answer -> answer(ActionResult.ok("done")) }, { null })

    private fun pump() {
        repeat(10) {
            sched.drain()
            for (s in sipSockets.toList()) links.getOrPut(s) { SbcLink(s, sbc, sched) }.pump()
        }
        sched.drain()
    }

    /** A call for [callId], minted and prepared. */
    private fun prepared(callId: String?, who: IdentityEnvelope? = user): RtcCallSession =
        RtcCallSession(request(callId, who), env()).also { it.prepare(); pump() }

    // ── the grant for the next call, and the call that takes it ─────────────

    @Test fun theCallThatTakesTheWarmGrantsIdMintsAtTheGateway() {
        direct.warm(web, key, user)
        // The grant-only request: the key, and exactly the identity fields a mint carries — the next call's id among them.
        val ask = hosts.grantAsks.single()
        assertEquals(key, ask.getString("publishableKey"))
        assertTrue(ask.getBoolean("grant_only"))
        assertEquals("u-42", ask.getJSONObject("identity").getString("value"))
        assertEquals("inst-1", ask.getString("installId"))
        assertTrue(ask.getBoolean("memory"))
        assertEquals("Asia/Kolkata", ask.getString("timezone"))
        assertEquals("call_next_1", ask.getString("callId"))
        assertFalse("a grant is no call: no session id", ask.has("session_id"))
        assertFalse("and no variables", ask.has("variables"))
        assertEquals("the gateway asked once, before anything relies on it", 1, hosts.probes)

        val id = direct.takeCallId(key, user)
        assertEquals("the call carries the id its grant was asked for", "call_next_1", id)
        val s = prepared(id)
        val m = hosts.directMints.single()
        assertEquals("v1.grant1.sig", m.getString("grant"))
        assertEquals(s.sessionId, m.getString("session_id"))
        assertEquals("kyc_help", m.getJSONObject("variables").getString("call_reason"))
        assertEquals("Asha", m.getJSONObject("variables").getString("name"))
        assertTrue("the web route was never asked", webMints.isEmpty())
        assertTrue(steps.any { it.startsWith("mint: ") && it.contains("(direct)") })
        // The next call's grant was asked for behind this one, with an id of its own.
        assertEquals(2, hosts.grantAsks.size)
        assertEquals("call_next_2", hosts.grantAsks.last().getString("callId"))

        // And the call goes on as any other: registered on the gateway's credential, dialled, answered, media.
        assertEquals(Trace.SERVER, sipSockets.single().url)
        peers.single().candidate("srflx"); pump()
        s.dial(deps, listener); pump()
        assertEquals("agent-web-1", sipSockets.single().requests("INVITE").last().header("X-Graine-Agent-Id"))
        peers.single().ice("connected"); pump()
        assertEquals(listOf("media"), log)
        assertEquals("a yes is kept: the probe is not asked again", 1, hosts.probes)
    }

    @Test fun withNoGrantForTheCallTheWebRouteMintsAndTheGrantItBringsIsThatCallsAlone() {
        val answer = credential().put("direct", offer()).toString()
        val brought = JSONObject(answer).getJSONObject("direct").getString("grant")
        webReply = { HttpReply(200, answer) }
        prepared("sc_rung_1")
        assertEquals("no grant for this call: the web route, as before", 1, webMints.size)
        assertTrue(hosts.directMints.isEmpty())
        assertEquals("sc_rung_1", webMints.single().getString("callId"))

        // That call's own next mint (a redial on a lapsed credential) takes the grant its answer brought to the gateway…
        assertNotNull(direct.mint(request("sc_rung_1"), "fedcba9876543210fedcba9876543210"))
        assertEquals(brought, hosts.directMints.single().getString("grant"))
        // …and no other call ever does: it names that call.
        assertNull(direct.mint(request("call_someone_else"), "fedcba9876543210fedcba9876543210"))
        assertEquals(1, hosts.directMints.size)
    }

    @Test fun aCallGraineRangMintsThroughTheWebRouteAndLeavesTheNextCallsGrantAlone() {
        direct.warm(web, key, user)
        prepared("sc_hosted_7")
        assertEquals("its own id, from the push: the web route", 1, webMints.size)
        assertTrue(hosts.directMints.isEmpty())
        assertEquals("the next call's grant is still the next call's", "call_next_1", direct.takeCallId(key, user))
    }

    // ── when the gateway does not mint ──────────────────────────────────────

    @Test fun aRefusedGrantIsForgottenAndTheWebRouteMintsAtOnce() {
        direct.warm(web, key, user)
        hosts.directReply = { HttpReply(401, "{\"detail\":\"This call grant is not valid or has expired.\",\"code\":\"grant_invalid\"}") }
        val id = direct.takeCallId(key, user)
        prepared(id)
        assertEquals(1, hosts.directMints.size)
        assertEquals("the web route minted in the same attempt", 1, webMints.size)
        assertEquals(id, webMints.single().getString("callId"))
        assertEquals("and the call registered on its credential", 1, sipSockets.size)
        assertNull("the refused grant is gone", direct.mint(request(id), "fedcba9876543210fedcba9876543210"))
        assertEquals(1, hosts.directMints.size)
        assertFalse("one refusal is not two", direct.resting)
    }

    @Test fun twoRefusedGrantsInARowRestTheDirectMintTenMinutes() {
        hosts.directReply = { HttpReply(401, "{\"code\":\"grant_invalid\"}") }
        direct.warm(web, key, user)
        prepared(direct.takeCallId(key, user))
        assertFalse(direct.resting)
        prepared(direct.takeCallId(key, user))
        assertEquals(2, hosts.directMints.size)
        assertTrue("the web route and the gateway disagree on the secret: resting", direct.resting)
        assertEquals(2, webMints.size)
        // Nothing direct while it rests: no grant asked for, no probe.
        val asked = hosts.grantAsks.size
        now += 9 * 60_000L
        direct.warm(web, key, user)
        assertEquals(asked, hosts.grantAsks.size)
        assertEquals(1, hosts.probes)
        // Then the gateway is asked again — and the grants, once the next is fetched, go to it.
        now += 60_000L
        restTimers.advance(RtcDirectMint.REST_MS)
        assertFalse(direct.resting)
        assertEquals(2, hosts.probes)
        hosts.directReply = { HttpReply(200, credential().toString()) }
        direct.warm(web, key, user)
        prepared(direct.takeCallId(key, user))
        assertEquals(3, hosts.directMints.size)
        assertEquals(2, webMints.size)
    }

    @Test fun anyRefusalOfTheGrantItselfCountsAsARefusedGrant() {
        // A 403 with no code is the grant refused as surely as grant_invalid (the gateway's own word for it).
        hosts.directReply = { HttpReply(403, "{\"detail\":\"Forbidden\"}") }
        direct.warm(web, key, user)
        prepared(direct.takeCallId(key, user))
        hosts.directReply = { HttpReply(401, "{\"detail\":\"Missing authentication header\"}") }
        prepared(direct.takeCallId(key, user))
        assertTrue(direct.resting)
        assertEquals("each time the web route took the call", 2, webMints.size)
    }

    @Test fun aSpentGrantMintsThroughTheWebRoute() {
        direct.warm(web, key, user)
        hosts.directReply = { HttpReply(429, "{\"detail\":\"This page has asked for too many calls on one grant.\",\"code\":\"grant_budget\"}") }
        val id = direct.takeCallId(key, user)
        prepared(id)
        assertEquals(1, hosts.directMints.size)
        assertEquals(1, webMints.size)
        assertNull("spent: not taken to the gateway again", direct.mint(request(id), "fedcba9876543210fedcba9876543210"))
        assertEquals(1, hosts.directMints.size)
        assertFalse(direct.resting)
    }

    @Test fun twoRequestsInARowWithNoAnswerRestTheDirectMintThenEveryRouteIsAskedAgainAndTheGrantsAreStillThere() {
        direct.warm(web, key, user)
        direct.warmCall(web, key, user, "sc_ring_1")
        hosts.directReply = { throw IOException("timed out") }
        assertNull("no answer: the web route", direct.mint(request("sc_ring_1"), sid))
        assertFalse("once is the network's", direct.resting)
        assertNull(direct.mint(request("sc_ring_1"), sid))
        assertTrue("twice in a row: resting", direct.resting)
        assertEquals(2, hosts.directMints.size)

        // Ten minutes of nothing direct: no mint, no probe, no grant request — and a call takes an id of its own.
        val asked = hosts.grantAsks.size
        val probed = hosts.probes
        now += 9 * 60_000L
        direct.warm(web, key, user)
        direct.warmCall(web, key, user, "sc_ring_2")
        assertNull("the next call's grant is kept for after", direct.takeCallId(key, user))
        prepared("call_while_resting")
        assertEquals(1, webMints.size)
        assertEquals(asked, hosts.grantAsks.size)
        assertEquals(probed, hosts.probes)
        assertEquals(2, hosts.directMints.size)

        // Then every route seen is asked again, the count starts again, and the grant still in hand is the next call's.
        now += 60_000L
        restTimers.advance(RtcDirectMint.REST_MS)
        assertFalse(direct.resting)
        assertEquals(probed + 1, hosts.probes)
        hosts.directReply = { HttpReply(200, credential().toString()) }
        val id = direct.takeCallId(key, user)
        assertEquals("call_next_1", id)
        prepared(id)
        assertEquals("v1.grant1.sig", hosts.directMints.last().getString("grant"))
        assertEquals(1, webMints.size)
    }

    @Test fun anAnswerIsNotTheNetworksFailureAndOnlyACredentialStartsTheCountAgain() {
        // The gateway answered, however badly: never one of the two with no answer.
        hosts.directReply = { HttpReply(502, "{\"detail\":\"Could not mint a call session.\"}") }
        direct.warm(web, key, user)
        prepared(direct.takeCallId(key, user))
        prepared(direct.takeCallId(key, user))
        assertFalse("two answers are not two silences", direct.resting)
        hosts.directReply = { HttpReply(400, "{\"detail\":\"agent_id is required\"}") }
        prepared(direct.takeCallId(key, user))
        assertFalse(direct.resting)
        assertEquals(3, webMints.size)
        // No answer, then a credential, then no answer: not two in a row.
        hosts.directReply = { throw IOException("timed out") }
        prepared(direct.takeCallId(key, user))
        hosts.directReply = { HttpReply(200, credential().toString()) }
        prepared(direct.takeCallId(key, user))
        hosts.directReply = { throw IOException("timed out") }
        prepared(direct.takeCallId(key, user))
        assertFalse(direct.resting)
        assertEquals(6, hosts.directMints.size)
    }

    @Test fun aGateRefusalAtTheGatewayEndsTheCallInTheWebSocketsWordsAndNothingIsAskedTwice() {
        direct.warm(web, key, user)
        hosts.directReply = { HttpReply(402, "{\"detail\":\"Insufficient credits.\",\"code\":\"insufficient_credits\"}") }
        val s = prepared(direct.takeCallId(key, user))
        s.dial(deps, listener); pump()
        assertEquals(listOf("refused close 4402 insufficient_credits: this account is out of credits - add credits to start a call"), log)
        assertTrue("never the web route: it would refuse the same, a second later", webMints.isEmpty())
        assertTrue(sipSockets.isEmpty())
        assertTrue(steps.any { it.startsWith("refused") })

        log.clear()
        hosts.directReply = { HttpReply(409, "{\"detail\":\"This agent is switched off.\",\"code\":\"agent_off\"}") }
        val s2 = prepared(direct.takeCallId(key, user))
        s2.dial(deps, listener); pump()
        assertEquals(listOf("refused close 4409 agent_off: This agent is switched off."), log)
        assertTrue(webMints.isEmpty())
        assertFalse("a refusal is an answer, not a failure", direct.resting)
    }

    @Test fun aGateRefusalWithNoCodeIsReadAsTheWebRoutesOwnWouldBe() {
        // The web route's 402 carries no code either: today that call goes on, on the WebSocket, which refuses it there.
        direct.warm(web, key, user)
        hosts.directReply = { HttpReply(402, "{\"detail\":\"Insufficient credits.\"}") }
        val s = prepared(direct.takeCallId(key, user))
        s.dial(deps, listener); pump()
        assertEquals(listOf("fallback mint_failed"), log)
        assertTrue(webMints.isEmpty())
    }

    // ── a grant's life ──────────────────────────────────────────────────────

    @Test fun aGrantIsRenewedPastHalfItsLifeForTheSameNextCall() {
        direct.warm(web, key, user)
        assertEquals(1, hosts.grantAsks.size)
        now += 7 * 60_000L
        direct.warm(web, key, user)
        assertEquals("under half its life: nothing asked", 1, hosts.grantAsks.size)
        now += 60_000L
        direct.warm(web, key, user)
        assertEquals("past half: renewed in the background", 2, hosts.grantAsks.size)
        assertEquals("for the same next call", "call_next_1", hosts.grantAsks[1].getString("callId"))
        val id = direct.takeCallId(key, user)
        assertEquals("call_next_1", id)
        prepared(id)
        assertEquals("the renewed grant is the one used", "v1.grant2.sig", hosts.directMints.single().getString("grant"))
    }

    @Test fun anotherCallsMintPastHalfTheNextCallsGrantRenewsIt() {
        direct.warm(web, key, user)
        now += 8 * 60_000L
        prepared("sc_hosted_8")
        assertEquals("the ring minted through the web route", 1, webMints.size)
        assertEquals("and the next call's grant was renewed behind it", 2, hosts.grantAsks.size)
        assertEquals("call_next_1", hosts.grantAsks[1].getString("callId"))
    }

    @Test fun aGrantIsNeverUsedInItsLastThirtySeconds() {
        direct.warm(web, key, user)
        now += 900_000L - 31_000L
        assertEquals("31 s left: used", "call_next_1", direct.takeCallId(key, user))

        val late = fresh()
        late.warm(web, key, user)
        now += 900_000L - 30_000L
        assertNull("30 s left: not used — a mint in flight must not arrive with it lapsed", late.takeCallId(key, user))
    }

    @Test fun aGrantsLifeRunsFromItsArrivalByThisPhonesClock() {
        // This phone is ten minutes ahead of the server: by expires_at it would look a third spent. Its ttl says fifteen minutes.
        hosts.grantReply = { HttpReply(200, JSONObject().put("direct", offer(ttl = 900_000, expiresAt = now - 600_000 + 900_000)).toString()) }
        direct.warm(web, key, user)
        now += 14 * 60_000L
        assertEquals("fourteen minutes after it arrived, still good", "call_next_1", direct.takeCallId(key, user))

        // Without a ttl, expires_at less now — by this phone's clock, at arrival.
        hosts.grantReply = { HttpReply(200, JSONObject().put("direct", offer(ttl = null, expiresAt = now + 120_000)).toString()) }
        val noTtl = fresh()
        noTtl.warm(web, key, user)
        now += 89_000L
        assertNotNull("31 s left of its two minutes", noTtl.takeCallId(key, user))
        val noTtl2 = fresh()
        noTtl2.warm(web, key, user)
        now += 90_000L
        assertNull("30 s left", noTtl2.takeCallId(key, user))
    }

    // ── who the user is ─────────────────────────────────────────────────────

    @Test fun anotherUsersGrantIsForgotten() {
        direct.warm(web, key, user)
        val other = user.copy(value = "u-77")
        assertNull("another user's grant is never this one's", direct.takeCallId(key, other))
        assertNull("and it is gone: the first user's request forgot it", direct.takeCallId(key, user))
        direct.warm(web, key, other)
        val ask = hosts.grantAsks.last()
        assertEquals("u-77", ask.getJSONObject("identity").getString("value"))
        assertNotEquals("a new call id: the last one's grant named another user", "call_next_1", ask.getString("callId"))
        val id = direct.takeCallId(key, other)
        prepared(id, other)
        assertEquals("v1.grant2.sig", hosts.directMints.single().getString("grant"))
    }

    @Test fun theMemoryChoiceAndSigningOutAreAnotherIdentityToo() {
        direct.warm(web, key, user)
        direct.warm(web, key, user.copy(memory = false))
        assertFalse("the grant carries the choice", hosts.grantAsks.last().getBoolean("memory"))
        val signedOut = user.copy(value = null)
        direct.warm(web, key, signedOut)
        assertFalse("signed out: no identity in it", hosts.grantAsks.last().has("identity"))
        assertEquals(3, hosts.grantAsks.size)
        assertEquals("the signed-out user's own", hosts.grantAsks.last().getString("callId"), direct.takeCallId(key, signedOut))
        assertNull("the first user's went with them", direct.mint(request("call_next_1"), "fedcba9876543210fedcba9876543210"))
        assertTrue(hosts.directMints.isEmpty())
    }

    @Test fun aGrantArrivingForAUserWhoHasSinceLeftIsNotKept() {
        val queued = mutableListOf<() -> Unit>()
        direct = fresh(background = { queued += it })
        direct.warm(web, key, user)
        direct.warm(web, key, user.copy(value = "u-77"))
        // The first user's request answers after the second user's began: only the second's is kept.
        queued.toList().forEach { it() }
        assertEquals(2, hosts.grantAsks.size)
        assertEquals(hosts.grantAsks[1].getString("callId"), direct.takeCallId(key, user.copy(value = "u-77")))
        assertNull(direct.mint(request(hosts.grantAsks[0].getString("callId")), "fedcba9876543210fedcba9876543210"))
        assertTrue(hosts.directMints.isEmpty())
    }

    // ── the probe ───────────────────────────────────────────────────────────

    @Test fun aGatewayThatSaysNoRestsTheDirectMintAndIsAskedAgainAfter() {
        hosts.probeReply = { HttpReply(200, "{\"direct\":false}") }
        direct.warm(web, key, user)
        assertEquals(1, hosts.probes)
        assertTrue("not here yet: resting", direct.resting)
        assertNull(direct.takeCallId(key, user))
        prepared("call_1")
        assertTrue(hosts.directMints.isEmpty())
        assertEquals(1, webMints.size)

        // Deployed in the meantime: asked again after the rest, and the grant kept through it is the next call's.
        hosts.probeReply = { HttpReply(200, "{\"direct\":true}") }
        restAwake()
        assertEquals(2, hosts.probes)
        assertFalse(direct.resting)
        val id = direct.takeCallId(key, user)
        assertEquals("call_next_1", id)
        prepared(id)
        assertEquals(1, hosts.directMints.size)

        // A gateway that has not been deployed with the route answers 404: the same rest.
        hosts.probeReply = { HttpReply(404, "{\"detail\":\"Not Found\"}") }
        val older = fresh()
        older.warm(web, key, user)
        assertTrue(older.resting)
    }

    @Test fun aProbeTheNetworkLostIsAskedAgainAndCounts() {
        val queued = mutableListOf<() -> Unit>()
        direct = fresh(background = { queued += it })
        fun runQueued() { while (queued.isNotEmpty()) queued.removeAt(0)() }
        hosts.probeReply = { throw IOException("unreachable") }
        direct.warm(web, key, user); runQueued()
        assertEquals(1, hosts.probes)
        assertFalse("the network's, not the gateway's answer", direct.resting)
        hosts.probeReply = { HttpReply(200, "{\"direct\":true}") }
        prepared(direct.takeCallId(key, user))
        assertEquals("not known yet: this call minted through the web route…", 1, webMints.size)
        assertTrue(hosts.directMints.isEmpty())
        runQueued()
        assertEquals("…and the probe was asked again, for the next", 2, hosts.probes)
        prepared(direct.takeCallId(key, user))
        assertEquals("ready now: the next call mints at the gateway", 1, hosts.directMints.size)
        assertEquals(1, webMints.size)
    }

    @Test fun aProbeLostToTheNetworkTwiceRestsTheDirectMint() {
        hosts.probeReply = { throw IOException("unreachable") }
        direct.warm(web, key, user)
        assertFalse(direct.resting)
        direct.warm(web, key, user)
        assertTrue("the probe's silences count as the mint's do: two in a row", direct.resting)
        assertEquals(2, hosts.probes)
        direct.warm(web, key, user)
        assertEquals("nothing asked while resting", 2, hosts.probes)
    }

    @Test fun aMintWaitsForAProbeAlreadyAsked() {
        val asked = CountDownLatch(1)
        val answer = CountDownLatch(1)
        hosts.probeReply = { asked.countDown(); answer.await(5, TimeUnit.SECONDS); HttpReply(200, "{\"direct\":true}") }
        direct = fresh(background = { work -> Thread(work).apply { isDaemon = true }.start() })
        direct.warm(web, key, user)
        assertTrue(asked.await(5, TimeUnit.SECONDS))
        val id = direct.takeCallId(key, user)
        assertNotNull(id)
        var got: HttpReply? = null
        val minting = Thread { got = direct.mint(request(id), "fedcba9876543210fedcba9876543210") }.apply { start() }
        Thread.sleep(100)
        assertTrue("nothing reaches the gateway before the probe answers", hosts.directMints.isEmpty())
        answer.countDown()
        minting.join(5_000)
        assertEquals(200, got?.code)
        assertEquals(1, hosts.directMints.size)
    }

    // ── a warm-up is never load ─────────────────────────────────────────────

    @Test fun aRefusedWarmUpWaitsBeforeAskingAgain() {
        hosts.grantReply = { HttpReply(429, "{\"error\":\"Too many requests.\",\"code\":\"rate_limited\"}", retryAfterSeconds = 30) }
        direct.warm(web, key, user)
        direct.warm(web, key, user)
        assertEquals("the route's limits are everyone's: not asked again at once", 1, hosts.grantAsks.size)
        now += 30_001L
        hosts.grantReply = { HttpReply(403, "{\"error\":\"…\",\"code\":\"no_origin\"}") }
        direct.warm(web, key, user)
        assertEquals(2, hosts.grantAsks.size)
        now += 59_000L
        direct.warm(web, key, user)
        assertEquals("a setting's refusal pauses for a minute", 2, hosts.grantAsks.size)
        now += 1_001L
        direct.warm(web, key, user)
        assertEquals(3, hosts.grantAsks.size)
        assertEquals("no grant, no probe", 0, hosts.probes)
    }

    @Test fun aKeyTheWebRouteSignsNoGrantsForIsNotAskedAgainUntilOneOfItsAnswersBringsOne() {
        hosts.grantReply = { HttpReply(200, "{\"direct\":null}") }
        direct.warm(web, key, user)
        assertFalse("not the gateway's failure: no rest", direct.resting)
        direct.warm(web, key, user)
        direct.warmCall(web, key, user, "sc_ring_1")
        prepared("call_1")
        assertEquals("asked once", 1, hosts.grantAsks.size)
        assertEquals(0, hosts.probes)
        assertEquals(1, webMints.size)

        // A web-route mint that brings a grant after all: this key's next call's grant is asked for again.
        hosts.grantReply = { HttpReply(200, JSONObject().put("direct", offer()).toString()) }
        webReply = { HttpReply(200, credential().put("direct", offer()).toString()) }
        prepared("call_2")
        assertEquals(2, hosts.grantAsks.size)
        assertEquals("call_next_2", direct.takeCallId(key, user))
    }

    // ── a warm-up sends nothing while the grant in hand holds ───────────────

    @Test fun aWarmUpWithAUsableGrantSendsNothing() {
        direct.warm(web, key, user)
        for (minute in 1..7) {
            now += 60_000L
            direct.warm(web, key, user)
        }
        assertEquals("one grant request, in the grant's first half", 1, hosts.grantAsks.size)
        assertEquals("one probe: its yes is kept", 1, hosts.probes)
    }

    @Test fun aGrantWithinThirtySecondsOfLapsingIsRenewedEvenBeforeHalfItsLife() {
        // A short grant: half its life is 25 s, and from 20 s it is within 30 s of lapsing.
        hosts.grantReply = { HttpReply(200, JSONObject().put("direct", offer(ttl = 50_000)).toString()) }
        direct.warm(web, key, user)
        now += 19_000L
        direct.warm(web, key, user)
        assertEquals("31 s left: nothing asked", 1, hosts.grantAsks.size)
        now += 2_000L
        direct.warm(web, key, user)
        assertEquals("29 s left: renewed", 2, hosts.grantAsks.size)
        assertEquals("for the same next call", "call_next_1", hosts.grantAsks[1].getString("callId"))
    }

    // ── the phone's sleep ───────────────────────────────────────────────────

    @Test fun aGrantThePhoneSleptThroughIsLapsed() {
        direct.warm(web, key, user)
        // Twenty minutes asleep: the wall clock moved on; the clock timers run on did not.
        now += 20 * 60_000L
        assertNull("lapsed while the phone slept", direct.takeCallId(key, user))
        assertNull(direct.mint(request("call_next_1"), sid))
        assertTrue(hosts.directMints.isEmpty())
        assertEquals("and a new one asked for, for the same next call", 2, hosts.grantAsks.size)
        assertEquals("call_next_1", hosts.grantAsks[1].getString("callId"))
        assertEquals("call_next_1", direct.takeCallId(key, user))
    }

    @Test fun aRestThePhoneSleptThroughIsOverAtTheNextWarmUp() {
        hosts.probeReply = { HttpReply(200, "{\"direct\":false}") }
        direct.warm(web, key, user)
        assertTrue(direct.resting)
        hosts.probeReply = { HttpReply(200, "{\"direct\":true}") }
        // Eleven minutes asleep: the rest's timer has not fired, but the wall clock says it is over.
        now += 11 * 60_000L
        direct.warm(web, key, user)
        assertFalse(direct.resting)
        assertEquals("the gateway asked again", 2, hosts.probes)
        val id = direct.takeCallId(key, user)
        prepared(id)
        assertEquals(1, hosts.directMints.size)
    }

    // ── a ringing call's own grant ──────────────────────────────────────────

    @Test fun aRingingCallsGrantAskedForApartIsTakenToTheGatewayAtItsAnswer() {
        direct.warm(web, key, user)
        direct.warmCall(web, key, user, "sc_ring_9")
        val ask = hosts.grantAsks.last()
        assertEquals("exactly that call's", "sc_ring_9", ask.getString("callId"))
        assertEquals("u-42", ask.getJSONObject("identity").getString("value"))
        direct.warmCall(web, key, user, "sc_ring_9")
        assertEquals("asked once: the grant in hand holds", 2, hosts.grantAsks.size)
        prepared("sc_ring_9")
        assertEquals("v1.grant2.sig", hosts.directMints.single().getString("grant"))
        assertTrue(webMints.isEmpty())
        assertEquals("the next call's grant is still the next call's", "call_next_1", direct.takeCallId(key, user))
    }

    @Test fun aRingsOwnMintAnswerBringsItsGrantAndNothingIsAskedApart() {
        direct.warm(web, key, user)
        direct.expectCall(key, user, "sc_ring_11")
        webReply = { HttpReply(200, credential().put("direct", offer()).toString()) }
        prepared("sc_ring_11")
        assertEquals("one request for the ring: its mint", 1, webMints.size)
        assertTrue("no grant asked for apart", hosts.grantAsks.none { it.optString("callId") == "sc_ring_11" })
        assertEquals(200, direct.mint(request("sc_ring_11"), sid)?.code)
        assertEquals("the grant its answer brought", "v1.grant2.sig", hosts.directMints.single().getString("grant"))
    }

    @Test fun aRingsMintAnswerWithoutAGrantHasOneAskedForApart() {
        direct.warm(web, key, user)
        direct.expectCall(key, user, "sc_ring_12")
        prepared("sc_ring_12")
        assertEquals(1, hosts.grantAsks.count { it.optString("callId") == "sc_ring_12" })
        assertEquals(200, direct.mint(request("sc_ring_12"), sid)?.code)
        // A ring nobody expected (no session yet, say) asks nothing apart.
        prepared("sc_ring_13")
        assertTrue(hosts.grantAsks.none { it.optString("callId") == "sc_ring_13" })
    }

    @Test fun aRingRefusedAtItsMintAsksNothingApartButAServerErrorDoes() {
        direct.warm(web, key, user)
        direct.expectCall(key, user, "sc_ring_14")
        webReply = { HttpReply(402, "{\"error\":\"Out of credits.\",\"code\":\"insufficient_credits\"}") }
        prepared("sc_ring_14")
        assertTrue("a gate refused the call: a grant would change nothing", hosts.grantAsks.none { it.optString("callId") == "sc_ring_14" })
        direct.expectCall(key, user, "sc_ring_15")
        webReply = { HttpReply(502, "{\"error\":\"Could not start a call.\"}") }
        prepared("sc_ring_15")
        assertEquals("the server's own failure: asked for apart", 1, hosts.grantAsks.count { it.optString("callId") == "sc_ring_15" })
    }

    @Test fun aCallPreparedBeforeTheUserWasKnownForgetsNobodysGrants() {
        val anonymous = user.copy(value = null)
        direct.warm(web, key, user)
        direct.warmCall(web, key, user, "sc_ring_cold")
        // A ring a push prepared before the session said who the user is: its mint, and the grant its answer brings.
        webReply = { HttpReply(200, credential().put("direct", offer()).toString()) }
        prepared("sc_ring_cold", anonymous)
        assertEquals("through the web route, as before", 1, webMints.size)
        assertEquals("the next call's grant is still the user's", "call_next_1", direct.takeCallId(key, user))
        assertEquals("and so is the ring's own, for the mint at its answer", 200, direct.mint(request("sc_ring_cold"), sid)?.code)
        assertEquals("v1.grant2.sig", hosts.directMints.single().getString("grant"))
    }

    @Test fun aRingsGrantNotHereByTheAnswerLeavesTheMintToTheWebRoute() {
        val queued = mutableListOf<() -> Unit>()
        direct = fresh(background = { queued += it })
        direct.warmCall(web, key, user, "sc_ring_10")
        prepared("sc_ring_10")
        assertEquals("as today", 1, webMints.size)
        assertTrue(hosts.directMints.isEmpty())
    }

    @Test fun oneGrantRequestAtATime() {
        val queued = mutableListOf<() -> Unit>()
        direct = fresh(background = { queued += it })
        direct.warm(web, key, user)
        direct.warm(web, key, user)
        direct.warm(web, key, user)
        assertEquals(1, queued.size)
    }

    // ── what the web route writes ───────────────────────────────────────────

    @Test fun anOfferIsReadOnlyWhenItIsWhole() {
        val good = JSONObject().put("grant", "v1.abc.def").put("expires_at", 1_800_000_900_000L).put("ttl_ms", 900_000).put("url", gateway)
        RtcGrantOffer.parse(good)!!.let {
            assertEquals("v1.abc.def", it.grant)
            assertEquals(1_800_000_900_000L, it.expiresAt)
            assertEquals(900_000L, it.ttlMs)
            assertEquals(gateway, it.url)
            assertFalse("a log line never carries the grant", it.toString().contains("v1.abc"))
        }
        RtcGrantOffer.parse(JSONObject(good.toString()).apply { remove("ttl_ms") }).let {
            assertNotNull("no ttl is fine", it)
            assertNull(it!!.ttlMs)
        }
        assertNull("another version", RtcGrantOffer.parse(JSONObject(good.toString()).put("grant", "v2.abc.def")))
        assertNull("not https", RtcGrantOffer.parse(JSONObject(good.toString()).put("url", "http://gw.example.invalid/grant-session")))
        assertNull("no expiry", RtcGrantOffer.parse(JSONObject(good.toString()).apply { remove("expires_at") }))
        assertNull("an expiry that is not a number", RtcGrantOffer.parse(JSONObject(good.toString()).put("expires_at", "soon")))
        assertNull("a life that is no life", RtcGrantOffer.parse(JSONObject(good.toString()).put("ttl_ms", 0)))
        assertNull(RtcGrantOffer.parse(null))
        assertNull(RtcGrantOffer.of("{\"direct\":null}"))
        assertNull(RtcGrantOffer.of("<html>"))
        assertEquals("v1.abc.def", RtcGrantOffer.of(credential().put("direct", good).toString())?.grant)
    }
}
