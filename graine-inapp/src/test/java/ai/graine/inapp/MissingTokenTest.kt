@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger

/**
 * THE TOKEN NOT YET HELD (0.14.6, APP-IDENTITY.md §1.3). A user identified
 * without an identity token, an agent that checks its users (Verified users on
 * report or enforce) and an app that set a refresher: the refresher is asked —
 * and waited for, at most 10 seconds — before a conversation starts (the chat's
 * and a call's ticket, a WebRTC call's credential, the grant for the next call,
 * the voice bar's start) and before the requests that carry the user and need
 * them proven (memories, past sessions, booked calls, a workflow, a new
 * conversation, this install's registration for calls). The request goes with
 * the token it answers; null, a throw or the cap lets it go without, as before,
 * with no wait added — and a registration that went without it is made again,
 * once, when a token for the same user arrives. One refresh for every request
 * waiting at once; the 30 seconds between asks are per user, and the last
 * user's answer is never the next one's. Never for nobody identified, an agent
 * that does not check its users, a token already held — and never for a call
 * Graine rang, which the person is answering. The refresh of a token about to
 * lapse is as it was. Nothing reaches a host.
 */
class MissingTokenTest {
    private val sched = ManualScheduler()
    private var wall = 1_800_000_000_000L
    private fun now(): Long = wall + sched.now()

    private val embed = FakeEmbed()

    private val alice = mapOf("user_id" to "user-123", "name" to "Alice")
    private val bob = mapOf("user_id" to "user-456", "name" to "Bob")
    private fun who(value: String?) = IdentityEnvelope(value, "inst-1", true, "Asia/Kolkata")

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    /** The app's refresher, scripted: every ask counted; answered at once with [next] (or a throw), or held for the test to answer. */
    private class Refresher(@Volatile var next: () -> String? = { null }) : GraineIdentityTokenRefresher {
        private val asked = AtomicInteger()
        val calls: Int get() = asked.get()
        @Volatile var hold = false
        @Volatile var throws = false
        @Volatile var held: GraineIdentityTokenCallback? = null
        override fun refresh(done: GraineIdentityTokenCallback) {
            asked.incrementAndGet()
            if (throws) throw IllegalStateException("The token service is down.")
            if (hold) held = done else done.onToken(next())
        }
    }

    /** The user's proof, its refresher answered on the asking thread (as the main thread is, in an app). */
    private fun user(tokenChanged: () -> Unit = {}) = UserProof(clock = { now() }, tokenChanged = tokenChanged)

    private fun security(user: String) = SecuritySettings.parse(
        JSONObject().put("app", JSONObject().put("verify", "off")).put("user", JSONObject().put("verify", user)),
    )

    /** The door for an agent whose session says [userVerify] of its users — null: its session has not arrived. */
    private fun proofs(u: UserProof, userVerify: String? = "report", waitMs: Long = UserProof.NO_TOKEN_WAIT_MS): Proofs {
        val app = AppProofs({ AppProofsTest.DECLARED }, AppPassStore.memory(), { DeviceStore(null) }, { false }, { null }, sched, clock = { now() })
        val p = Proofs(app, u, fieldFor = { "user_id" }, noTokenWaitMs = waitMs)
        app.register(KEY, BASE, embed)
        if (userVerify != null) app.onSession(KEY, security(userVerify))
        return p
    }

    /** A request to [path] through the door, carrying [value] as the user — [forARing]: for a call Graine rang. */
    private fun send(p: Proofs, path: String, value: String? = "user-123", forARing: Boolean = false, extra: JSONObject.() -> Unit = {}): HttpReply {
        val body = JSONObject().put("publishableKey", KEY)
        who(value).into(body)
        body.extra()
        val url = AppProofsTest.EMBED + path
        return p.send(url, body, false, forARing) { json -> embed.post(url, json) }
    }

    /** The identity token the last request to [path] carried, or null. */
    private fun tokenOn(path: String): String? =
        embed.to(path).last().optJSONObject("identity")?.optString("token")?.takeIf { it.isNotEmpty() }

    private fun noTokenSent(): Boolean = embed.sent.none { it.body.optJSONObject("identity")?.has("token") == true }

    /** How long [block] took, in milliseconds. */
    private fun timed(block: () -> Unit): Long {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1_000_000
    }

    private fun waitUntil(what: String, condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    // ── the wait ────────────────────────────────────────────────────────────

    @Test fun theCapIsTenSeconds() {
        assertEquals(10_000L, UserProof.NO_TOKEN_WAIT_MS)
    }

    @Test fun theFirstConversationWaitsForTheTokenAndGoesWithIt() {
        val r = Refresher { "tok-fresh" }
        val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) })
        send(p, "ticket")
        assertEquals("the refresher asked once", 1, r.calls)
        assertEquals("the ticket carries what it answered", "tok-fresh", tokenOn("ticket"))
        // Held now: the next conversation asks nobody, and carries it.
        send(p, "rtc-session")
        assertEquals(1, r.calls)
        assertEquals("tok-fresh", tokenOn("rtc-session"))
    }

    @Test fun everyRequestThatCarriesTheUserWaitsLikeAConversation() {
        val routes = listOf(
            "memory/list", "memory/forget", "sessions/list", "sessions/transcript",
            "calls/list", "calls/schedule", "calls/cancel", "workflows/start",
            // A new conversation needs a proven user too; and this install's registration for calls — without the
            // token, under Verified users, the install is registered alone and not rung for them.
            "conversation/new", "devices",
        )
        for (path in routes) {
            val r = Refresher { "tok-$path" }
            val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) })
            send(p, path)
            assertEquals(path, 1, r.calls)
            assertEquals(path, "tok-$path", tokenOn(path))
        }
        // The grant the next call mints with is asked like the call itself: it waits too.
        val r = Refresher { "tok-grant" }
        val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) })
        send(p, "rtc-session") { put("grant_only", true) }
        assertEquals(1, r.calls)
        assertEquals("tok-grant", tokenOn("rtc-session"))
    }

    @Test fun anythingElseNeverWaits() {
        val r = Refresher { "tok-fresh" }
        val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) })
        for (path in listOf("identity/signout", "calls/outcome", "session", "app-verify/challenge")) send(p, path)
        assertEquals("nobody asked", 0, r.calls)
        assertTrue(noTokenSent())
    }

    @Test fun aRefresherThatBringsNoTokenLetsTheRequestGoWithNoWaitAdded() {
        val r = Refresher { null }
        val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) })
        val took = timed { send(p, "ticket") }
        assertEquals(1, r.calls)
        assertNull("no token to carry", tokenOn("ticket"))
        assertTrue("no wait added: $took ms", took < 2_000)
        // Asked at most every 30 seconds: the next request neither asks nor waits…
        assertTrue(timed { send(p, "memory/list") } < 2_000)
        assertEquals(1, r.calls)
        // …and 30 seconds on, it asks again.
        sched.advance(30_000)
        r.next = { "tok-later" }
        send(p, "ticket")
        assertEquals(2, r.calls)
        assertEquals("tok-later", tokenOn("ticket"))
    }

    @Test fun aRefresherThatThrowsLetsTheRequestGoWithNoWaitAdded() {
        val r = Refresher().apply { throws = true }
        val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) })
        val took = timed { send(p, "calls/schedule") }
        assertEquals(1, r.calls)
        assertNull(tokenOn("calls/schedule"))
        assertTrue("no wait added: $took ms", took < 2_000)
    }

    @Test fun aRefresherSlowerThanTheCapIsWaitedForAtMostTheCap() {
        val r = Refresher().apply { hold = true }
        val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) }, waitMs = 300)
        val took = timed { send(p, "ticket") }
        assertTrue("waited the cap: $took ms", took >= 250)
        assertTrue("and no longer: $took ms", took < 5_000)
        assertNull("it went without", tokenOn("ticket"))
        // Not waited for again, and never asked a second time while it has not answered.
        assertTrue(timed { send(p, "calls/list") } < 250)
        assertEquals(1, r.calls)
        // Its late answer still counts.
        r.held!!.onToken("tok-late")
        send(p, "ticket")
        assertEquals("tok-late", tokenOn("ticket"))
    }

    @Test fun anAgentThatDoesNotCheckItsUsersNeverAsks() {
        val r = Refresher { "tok-fresh" }
        val u = user().apply { refresher = r; identify(alice, null, sameUser = false) }
        val off = proofs(u, userVerify = "off")
        send(off, "ticket")
        send(off, "memory/list")
        // Nor before its session has said.
        val before = proofs(u, userVerify = null)
        send(before, "ticket")
        send(before, "calls/schedule")
        assertEquals(0, r.calls)
        assertTrue(noTokenSent())
        // Enforce asks, as report does.
        send(proofs(u, userVerify = "enforce"), "ticket")
        assertEquals(1, r.calls)
        assertEquals("tok-fresh", tokenOn("ticket"))
    }

    @Test fun nobodyIdentifiedNeverAsks() {
        val r = Refresher { "tok-fresh" }
        val u = user().apply { refresher = r }
        val p = proofs(u)
        send(p, "ticket", value = null)
        send(p, "memory/list", value = null)
        // Signed in, then out again.
        u.identify(alice, null, sameUser = false)
        u.identify(null, null, sameUser = false)
        send(p, "ticket", value = null)
        // A body made for Alice, sent once Bob is signed in: asked for neither.
        u.identify(bob, null, sameUser = false)
        send(p, "ticket", value = "user-123")
        // Traits without the one that identifies the user: no identity to prove.
        u.identify(mapOf("name" to "Nameless"), null, sameUser = false)
        send(p, "ticket", value = null)
        assertEquals(0, r.calls)
        assertTrue(noTokenSent())
    }

    @Test fun aTokenHeldIsNeverAskedForAhead() {
        val r = Refresher { "tok-fresh" }
        val p = proofs(user().apply { refresher = r; identify(alice, "tok-held", sameUser = false) })
        send(p, "ticket")
        send(p, "memory/list")
        send(p, "rtc-session")
        assertEquals(0, r.calls)
        assertEquals("tok-held", tokenOn("ticket"))
    }

    @Test fun requestsWaitingAtOnceShareOneRefresh() {
        val r = Refresher().apply { hold = true }
        val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) })
        val paths = listOf("ticket", "memory/list", "rtc-session")
        val go = CountDownLatch(1)
        val threads = paths.map { path -> Thread { go.await(); send(p, path) }.apply { start() } }
        go.countDown()
        waitUntil("the refresher to be asked") { r.held != null }
        Thread.sleep(150)
        assertEquals("one refresh for every request waiting", 1, r.calls)
        assertTrue("none has gone before it answers", embed.sent.isEmpty())
        r.held!!.onToken("tok-shared")
        threads.forEach { it.join(5_000) }
        assertTrue(threads.none { it.isAlive })
        assertEquals(1, r.calls)
        for (path in paths) assertEquals(path, "tok-shared", tokenOn(path))
    }

    @Test fun theTokenIsHeldBeforeTheRequestThatWaitedGoesOn() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val r = Refresher().apply { hold = true }
        val u = user(tokenChanged = { events += "token changed" }).apply { refresher = r; identify(alice, null, sameUser = false) }
        val p = proofs(u)
        val body = who("user-123").into(JSONObject().put("publishableKey", KEY))
        val url = AppProofsTest.EMBED + "rtc-session"
        val t = Thread { p.send(url, body, false) { json -> events += "sent"; embed.post(url, json) } }.apply { start() }
        waitUntil("the refresher to be asked") { r.held != null }
        r.held!!.onToken("tok-fresh")
        t.join(5_000)
        // What was prepared under the old verdict is let go before the request that waited is made (GraineInApp.identityTokenChanged).
        assertEquals(listOf("token changed", "sent"), events.toList())
        assertEquals("tok-fresh", tokenOn("rtc-session"))
    }

    @Test fun aRequestForACallGraineRangNeverWaits() {
        val r = Refresher().apply { hold = true }
        val p = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) }, waitMs = 300)
        val took = timed {
            send(p, "rtc-session", forARing = true) { put("callId", "sc_ring_1") }
            send(p, "rtc-session", forARing = true) { put("callId", "sc_ring_1"); put("grant_only", true) }
            send(p, "ticket", forARing = true) { put("callId", "sc_ring_1") }
        }
        assertEquals("answering asks nobody", 0, r.calls)
        assertTrue("and waits for nothing: $took ms", took < 250)
        // A call the app places, by contrast, waits.
        val placed = timed { send(p, "rtc-session") { put("callId", "call_placed") } }
        assertEquals(1, r.calls)
        assertTrue("$placed ms", placed >= 250)
    }

    @Test fun theRefreshOfATokenAboutToLapseIsAsItWas() {
        val r = Refresher { jwt(now() / 1000 + 3600) }
        val u = user().apply { refresher = r; identify(alice, jwt(now() / 1000 + 30), sameUser = false) }
        val p = proofs(u)
        send(p, "ticket")
        assertEquals("refreshed before the conversation", 1, r.calls)
        assertEquals(now() / 1000 + 3600, Jwt.expiresAt(tokenOn("ticket")!!))
        // Plenty of time left: nobody is asked.
        sched.advance(60_000)
        send(p, "rtc-session")
        assertEquals(1, r.calls)
        // Not a conversation: a token about to lapse is never refreshed ahead — the request goes with it.
        val lapsing = jwt(now() / 1000 + 10)
        u.setToken(lapsing)
        sched.advance(60_000)
        send(p, "memory/list")
        assertEquals(1, r.calls)
        assertEquals(lapsing, tokenOn("memory/list"))
    }

    // ── the 30 seconds are per user ─────────────────────────────────────────

    @Test fun anotherUsersFirstConversationAsksAndWaitsWhateverTheLastUserWasAsked() {
        val r = Refresher().apply { hold = true }
        val u = user().apply { refresher = r; identify(alice, null, sameUser = false) }
        val p = proofs(u, waitMs = 300)
        // Alice's first conversation asks at t = 0, and her refresher does not answer in time.
        send(p, "ticket", value = "user-123")
        assertEquals(1, r.calls)
        val forAlice = r.held!!
        r.held = null
        // Bob signs in five seconds later: his first conversation asks for him, and waits — Alice's ask is not his.
        sched.advance(5_000)
        u.identify(bob, null, sameUser = false)
        val took = timed { send(p, "ticket", value = "user-456") }
        assertEquals("asked for Bob", 2, r.calls)
        assertTrue("and waited for: $took ms", took >= 250)
        // Alice's token, arriving late, is never Bob's — nor kept for her, who has left.
        forAlice.onToken("tok-alice")
        assertNull(u.proofFor(KEY, "user-456", "user_id", false).token)
        assertNull(u.proofFor(KEY, "user-123", "user_id", false).token)
        send(p, "memory/list", value = "user-456")
        assertNull(tokenOn("memory/list"))
        // Bob's own answer is his.
        r.held!!.onToken("tok-bob")
        send(p, "memory/list", value = "user-456")
        assertEquals("tok-bob", tokenOn("memory/list"))
    }

    @Test fun aRefreshOnItsWayForTheLastUserIsLetGoAndNeverLandsOnTheNext() {
        val r = Refresher().apply { hold = true }
        val u = user().apply { refresher = r; identify(alice, null, sameUser = false) }
        val forAlice = u.askForMissingToken("user-123", "user_id", checksUsers = true)!!
        val aliceAnswers = r.held!!
        r.held = null
        sched.advance(5_000)
        // Signed out, then Bob: whoever waited for Alice's refresh goes on at once, and Bob's is a refresh of his own.
        u.identify(null, null, sameUser = false)
        assertEquals("Alice's waiters let go", 0L, forAlice.count)
        u.identify(bob, null, sameUser = false)
        val forBob = u.askForMissingToken("user-456", "user_id", checksUsers = true)!!
        assertEquals("asked for Bob within the 30 seconds", 2, r.calls)
        assertTrue(forBob !== forAlice)
        aliceAnswers.onToken("tok-alice")
        assertEquals("Bob's refresh still on its way", 1L, forBob.count)
        assertNull("Alice's token never lands on Bob", u.proofFor(KEY, "user-456", "user_id", false).token)
        r.held!!.onToken("tok-bob")
        assertEquals(0L, forBob.count)
        assertEquals("tok-bob", u.proofFor(KEY, "user-456", "user_id", false).token)
    }

    @Test fun theSameUserIdentifiedAgainKeepsTheirThirtySeconds() {
        val r = Refresher { null }
        val u = user().apply { refresher = r; identify(alice, null, sameUser = false) }
        val p = proofs(u)
        send(p, "ticket")
        assertEquals(1, r.calls)
        // The same user with other traits is no new user: asked less than 30 seconds ago, not asked again.
        sched.advance(5_000)
        u.identify(alice + ("plan" to "gold"), null, sameUser = true)
        send(p, "ticket")
        assertEquals(1, r.calls)
    }

    @Test fun whetherACallWouldWaitIsKnownWithoutAsking() {
        val r = Refresher { null }
        val u = user().apply { refresher = r; identify(alice, null, sameUser = false) }
        val p = proofs(u)
        assertTrue(p.missingTokenDue(KEY, "user-123"))
        assertEquals("knowing asks nobody", 0, r.calls)
        assertFalse("another user's value", p.missingTokenDue(KEY, "user-456"))
        send(p, "ticket")
        assertFalse("asked less than 30 seconds ago, and no token came", p.missingTokenDue(KEY, "user-123"))
        sched.advance(30_000)
        assertTrue(p.missingTokenDue(KEY, "user-123"))
        // While a refresh is on its way, a call would wait for it.
        r.hold = true
        assertTrue(p.askForMissingToken(KEY, "user-123") != null)
        sched.advance(1_000)
        assertTrue(p.missingTokenDue(KEY, "user-123"))
        r.held!!.onToken("tok-fresh")
        assertFalse("a token held", p.missingTokenDue(KEY, "user-123"))
        val off = proofs(user().apply { refresher = r; identify(alice, null, sameUser = false) }, userVerify = "off")
        assertFalse("an agent that does not check its users", off.missingTokenDue(KEY, "user-123"))
    }

    private fun jwt(exp: Long): String {
        fun b64(s: String) = DebugTokens.base64Url(s.toByteArray())
        return b64("""{"alg":"RS256","kid":"k1"}""") + "." + b64("""{"sub":"user-123","exp":$exp}""") + ".c2ln"
    }

    // ── each conversation's own request ─────────────────────────────────────

    /** Alice, identified without a token, a refresher that holds its answer, and an agent that checks its users: the door every request takes. */
    private fun heldRefresher(waitMs: Long = 300): Refresher {
        val r = Refresher().apply { hold = true }
        val u = user().apply { refresher = r; identify(alice, null, sameUser = false) }
        AppShared.proofs { proofs(u, waitMs = waitMs) }
        return r
    }

    private class SilentAudio : CallAudioIO {
        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {}
        override fun play(pcm: ShortArray, sampleRate: Int) {}
        override fun clear() {}
        override fun bufferedSeconds(): Double = 0.0
        override fun stop() {}
    }

    private class FakeSocket(val listener: CallSocketListener) : CallSocket {
        override fun send(text: String): Boolean = true
        override fun close(code: Int, reason: String) {}
    }

    @Test fun aCallsTicketWaitsUnlessGraineRangIt() {
        val r = heldRefresher()
        fun call(rang: Boolean) = VoiceCallSession(
            CallSessionConfig(BASE, KEY, "agent-a", "web-a", null, emptyMap(), emptyMap(), identity = who("user-123"), callId = "call_1", rang = rang),
            CallSessionDeps(sched, SilentAudio(), http = embed, openSocket = { _, _, l -> FakeSocket(l) }, io = { it() }),
            object : CallSessionEvents {},
        )
        val answered = call(rang = true)
        val took = timed { answered.start(); sched.advance(0) }
        answered.hangUp(); sched.advance(0)
        assertEquals("answering a ring asks nobody", 0, r.calls)
        assertTrue("and waits for nothing: $took ms", took < 250)
        assertEquals(1, embed.to("ticket").size)
        val placed = call(rang = false)
        val waited = timed { placed.start(); sched.advance(0) }
        placed.hangUp(); sched.advance(0)
        assertEquals("a call the app places asks", 1, r.calls)
        assertTrue("and waits for it: $waited ms", waited >= 250)
    }

    @Test fun aWebRtcCallsCredentialWaitsUnlessGraineRangIt() {
        val r = heldRefresher()
        fun call(rang: Boolean, id: String) = RtcCallSession(
            RtcCallRequest(BASE, KEY, emptyMap(), who("user-123"), id, null, rang = rang),
            RtcEnv(
                scheduler = sched, createPeer = { _, l -> FakePeer().also { it.listener = l } }, http = embed, io = { it() },
                openSip = { _, _, l -> FakeSipSocket().also { it.listener = l } }, openControl = { _, l -> FakeSipSocket().also { it.listener = l } },
            ),
        )
        val ring = call(rang = true, "sc_ring_1")
        val took = timed { ring.prepare(); sched.advance(0) }
        ring.hangUp(); sched.advance(0)
        assertEquals("a ring's credential asks nobody", 0, r.calls)
        assertTrue("and waits for nothing: $took ms", took < 250)
        assertEquals("sc_ring_1", embed.to("rtc-session").single().getString("callId"))
        val placed = call(rang = false, "call_1")
        val waited = timed { placed.prepare(); sched.advance(0) }
        placed.hangUp(); sched.advance(0)
        assertEquals("a call the app places asks", 1, r.calls)
        assertTrue("and waits for it: $waited ms", waited >= 250)
        assertEquals(2, embed.to("rtc-session").size)
    }

    @Test fun theNextCallsGrantWaitsAndARingingCallsOwnNever() {
        val r = Refresher { "tok-fresh" }
        val u = user().apply { refresher = r; identify(alice, null, sameUser = false) }
        AppShared.proofs { proofs(u) }
        val asks = Collections.synchronizedList(mutableListOf<JSONObject>())
        val direct = RtcDirectMint(
            http = object : RtcDirectHttp {
                override fun get(url: String, timeoutMs: Int) = HttpReply(200, "{\"direct\":true}")
                override fun post(url: String, json: String, timeoutMs: Int): HttpReply { asks += JSONObject(json); return HttpReply(200, "{}") }
            },
            clock = { now() }, background = { it() }, timers = ManualScheduler(), newCallId = { "call_next_1" },
        )
        direct.warmCall(BASE, KEY, who("user-123"), "sc_ring_1")
        assertEquals("a ringing call's own grant asks nobody", 0, r.calls)
        assertFalse(asks.single().getJSONObject("identity").has("token"))
        direct.warm(BASE, KEY, who("user-123"))
        assertEquals("the next call's grant waits for the token", 1, r.calls)
        assertEquals("tok-fresh", asks.last().getJSONObject("identity").getString("token"))
    }

    // ── through a real agent ────────────────────────────────────────────────

    private class TestApp : Application() {
        override fun getPackageName(): String = "com.acme.app"
    }

    /** The main thread on the test's clock: posts run, in order, when the test lets time pass. */
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

    private val main = TestMain(sched)
    private val dir = java.nio.file.Files.createTempDirectory("graine-missing-token").toFile()
    private val device = DeviceStore(File(dir, "device.json"))
    private val kept = HashMap<String, String>()
    private val cache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text })
    private val asha = mapOf("user_id" to "user-123", "name" to "Asha")

    /**
     * A real agent whose session checks its users, with every request through
     * the door above — its refresher answered on the asking thread. [hosted]:
     * the workspace has Graine ring its users, so this install registers for
     * calls (it has a push token).
     */
    private fun agent(waitMs: Long = 300, hosted: Boolean = false): GraineInApp {
        if (hosted) device.pushToken = "fcm-token-1"
        val calls = if (hosted) ""","calls":{"hosted":true,"platforms":["android"],"ringTimeoutSeconds":30}""" else ""
        val session = JSONObject(
            """{"agentId":"agent-a","webcallAgentId":"web-a","appearance":{"title":"Help"},"identity":{"field":"user_id"},
                "security":{"app":{"verify":"off"},"user":{"verify":"report","method":"hmac"}}$calls}""",
        )
        val p = Proofs(
            AppProofs({ AppProofsTest.DECLARED }, AppPassStore.memory(), { device }, { false }, { null }, sched, clock = { now() }),
            user(tokenChanged = { AppShared.identityTokenChanged() }),
            fieldFor = { key -> AppShared.identityFieldFor(key) },
            noTokenWaitMs = waitMs,
        )
        val env = AgentEnv(
            main = main, io = Executor { it.run() }, http = embed, fetchSession = { _, _ -> session },
            openSocket = { _, _, l -> FakeSocket(l) }, scheduler = sched, ticketIo = { it() }, device = device,
            sessionCache = cache, fonts = false, proofs = { p },
        )
        return GraineInApp.make(TestApp(), GraineConfig(BASE, KEY), GraineInApp.DEFAULT) { env }.also { sched.advance(0) }
    }

    @Test fun anAgentsFirstChatAndMemoriesGoWithTheTokenItsRefresherBrings() {
        val g = agent()
        val r = Refresher { "tok-fresh" }
        g.identityTokenRefresher = r
        g.identify(asha); sched.advance(0)
        g.chat.send("Hello"); sched.advance(0)
        assertEquals(1, r.calls)
        assertEquals("the first chat is not anonymous", "tok-fresh", embed.to("ticket").single().getJSONObject("identity").getString("token"))
        g.memories { _, _ -> }; sched.advance(0)
        assertEquals("held now: asked once", 1, r.calls)
        assertEquals("tok-fresh", tokenOn("memory/list"))
    }

    @Test fun theVoiceBarStartsOnceItsPageHasTheToken() {
        val g = agent()
        g.identify(asha); sched.advance(0)
        val heard = mutableListOf<String>()
        // What the voice bar's page hears: each identity with the token beside it (GraineVoice.postIdentity), then the start.
        g.onIdentity { env -> heard += "identity:" + (g.identityProofFor(env.value)?.token ?: "none") }
        // No refresher: the start goes at once, as before.
        g.afterMissingToken { heard += "start" }
        assertEquals(listOf("start"), heard)
        heard.clear()
        val r = Refresher { "tok-fresh" }
        g.identityTokenRefresher = r
        g.afterMissingToken { heard += "start" }
        sched.advance(0)
        assertEquals(1, r.calls)
        assertEquals("the page has the token before the call starts", listOf("identity:tok-fresh", "start"), heard)
        // Held now: the next start goes at once.
        heard.clear()
        g.afterMissingToken { heard += "start" }
        assertEquals(listOf("start"), heard)
    }

    @Test fun aSlowRefresherHoldsTheVoiceBarsStartAtMostTheCap() {
        val g = agent(waitMs = 300)
        g.identify(asha); sched.advance(0)
        val r = Refresher().apply { hold = true }
        g.identityTokenRefresher = r
        var started = false
        val took = timed { g.afterMissingToken { started = true }; sched.advance(0) }
        assertTrue("it started", started)
        assertEquals(1, r.calls)
        assertTrue("after the cap: $took ms", took >= 250)
        assertTrue("and no later: $took ms", took < 5_000)
    }

    // ── this install's registration for the calls Graine rings ──────────────

    /** The registrations this install sent for user [value]. */
    private fun registrationsFor(value: String): List<JSONObject> =
        embed.to("devices").filter { it.optJSONObject("identity")?.optString("value") == value }

    private fun tokenOf(registration: JSONObject): String? =
        registration.optJSONObject("identity")?.optString("token")?.takeIf { it.isNotEmpty() }

    @Test fun theRegistrationWaitsForTheTokenAndCarriesIt() {
        val g = agent(hosted = true)
        val r = Refresher { "tok-fresh" }
        g.identityTokenRefresher = r
        g.identify(asha); sched.advance(0)
        assertEquals(1, r.calls)
        val ashas = registrationsFor("user-123")
        assertEquals(1, ashas.size)
        assertEquals("registered with the token, so Asha is rung here", "tok-fresh", tokenOf(ashas.single()))
        // It went with the token: another one later registers nothing again.
        g.setIdentityToken("tok-newer"); sched.advance(0)
        assertEquals(1, registrationsFor("user-123").size)
    }

    @Test fun aRegistrationThatWentWithoutTheTokenIsMadeAgainOnceWhenItArrives() {
        val g = agent(waitMs = 300, hosted = true)
        val r = Refresher().apply { hold = true }
        g.identityTokenRefresher = r
        g.identify(asha); sched.advance(0)
        val first = registrationsFor("user-123")
        assertEquals(1, first.size)
        assertNull("the refresher did not answer in time: registered without it", tokenOf(first.single()))
        // The token comes late: registered again — once — with it.
        r.held!!.onToken("tok-late"); sched.advance(0)
        val ashas = registrationsFor("user-123")
        assertEquals(2, ashas.size)
        assertEquals("tok-late", tokenOf(ashas.last()))
        // Another token later, or the same user identified again, registers nothing more.
        g.setIdentityToken("tok-later"); sched.advance(0)
        g.identify(asha); sched.advance(0)
        assertEquals(2, registrationsFor("user-123").size)
    }

    @Test fun aTokenTheAppSetsLaterRestoresTheRegistrationToo() {
        val g = agent(hosted = true)
        // No refresher: nothing to wait for, so the registration goes without the token.
        g.identify(asha); sched.advance(0)
        assertNull(tokenOf(registrationsFor("user-123").single()))
        g.setIdentityToken("tok-from-the-app"); sched.advance(0)
        val ashas = registrationsFor("user-123")
        assertEquals("registered again, once", 2, ashas.size)
        assertEquals("tok-from-the-app", tokenOf(ashas.last()))
    }

    @Test fun aLateTokenNeverRegistersAgainForAUserWhoLeft() {
        val g = agent(waitMs = 300, hosted = true)
        val r = Refresher().apply { hold = true }
        g.identityTokenRefresher = r
        g.identify(asha); sched.advance(0)
        assertNull(tokenOf(registrationsFor("user-123").single()))
        val forAsha = r.held!!
        r.held = null
        // Ravi signs in before Asha's token comes: his own registration, asked for him (no answer in time either).
        g.identify(mapOf("user_id" to "user-456", "name" to "Ravi")); sched.advance(0)
        assertEquals(2, r.calls)
        assertEquals(1, registrationsFor("user-456").size)
        // Asha's token arrives: never registered again for her, nor taken as Ravi's.
        forAsha.onToken("tok-asha"); sched.advance(0)
        assertEquals(1, registrationsFor("user-123").size)
        assertEquals(1, registrationsFor("user-456").size)
        // Ravi's own token restores his registration — his, once.
        r.held!!.onToken("tok-ravi"); sched.advance(0)
        assertEquals(1, registrationsFor("user-123").size)
        val ravis = registrationsFor("user-456")
        assertEquals(2, ravis.size)
        assertEquals("tok-ravi", tokenOf(ravis.last()))
    }

    @Test fun aRegistrationAnsweredAfterTheUserLeftIsNeverMadeAgainForThem() {
        val g = agent(hosted = true)
        // Asha's registration goes without a token, and Ravi signs in before its answer is read.
        g.identify(asha)
        g.identify(mapOf("user_id" to "user-456", "name" to "Ravi"))
        sched.advance(0)
        assertEquals(1, registrationsFor("user-123").size)
        // A token for Ravi restores his registration only.
        g.setIdentityToken("tok-ravi"); sched.advance(0)
        assertEquals(1, registrationsFor("user-123").size)
        assertEquals("tok-ravi", tokenOf(registrationsFor("user-456").last()))
    }

    companion object {
        const val KEY = AppProofsTest.KEY
        const val BASE = AppProofsTest.BASE
    }
}
