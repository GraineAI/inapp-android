package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * ONE AGENT'S SERVER TOKENS (CLIENT-TOKENS.md §2.3), on the JVM with the clock,
 * the jitter and the timers in the test's hands: asked at start; one question
 * at a time, however many wait (two connections at once, a connection during a
 * background refresh); backoff after a failure and a throw; nothing asked in
 * the background, a foreground return inside the lead asks; a ring asks; a
 * refusal asks once — at most once in 5 s — and the request goes once more,
 * while token_scope, key_revoked and the bot check are final; and the tokens a
 * calls host pushes.
 */
class ClientTokensTest {
    /** The device's wall clock at the start of each test, in ms: 2026-10-26. */
    private val base = 1_793_000_000_000L
    private val sched = ManualScheduler()
    private val nowSec: Long get() = (base + sched.now()) / 1000

    /** A token minted now for [lifeSeconds]. */
    private fun fresh(lifeSeconds: Long = 3600, id: String = "ct_1"): String = TestTokens.mint(nowSec, nowSec + lifeSeconds, id)

    /** A provider whose answer the test decides, counting its questions; [manual] keeps each until the test answers it. */
    private class Provider(var answer: () -> String? = { null }) : GraineTokenProvider {
        val asked = AtomicInteger()
        @Volatile var manual = false
        val pending: MutableList<(String?) -> Unit> = Collections.synchronizedList(ArrayList())
        override fun fetch(done: (String?) -> Unit) {
            asked.incrementAndGet()
            if (manual) pending += done else done(answer())
        }
    }

    private fun tokens(
        p: GraineTokenProvider?,
        random: Double = 0.5,
        connectionWaitMs: Long = 2_000,
        pushWaitMs: Long = 2_000,
        providerTimeoutMs: Long = ClientTokenRules.PROVIDER_TIMEOUT_MS,
    ) = ClientTokens(
        p,
        clock = { base + sched.now() },
        random = { random },
        schedule = { d, task -> synchronized(sched) { sched.schedule(d, task) } },
        background = { it() },
        providerTimeoutMs = providerTimeoutMs,
        connectionWaitMs = connectionWaitMs,
        pushWaitMs = pushWaitMs,
    )

    private fun refused(code: String, status: Int = 401) = HttpReply(status, JSONObject().put("error", "Refused.").put("code", code).toString())

    private val ok = HttpReply(200, """{"ok":true}""")

    // ── another user ─────────────────────────────────────────────────────────

    @Test fun anotherUserLetsGoOfTheTokenAndAsksAgainAtOnce() {
        val alice = fresh(id = "ct_alice")
        val bob = fresh(id = "ct_bob")
        val p = Provider { alice }
        val t = tokens(p)
        t.start()
        assertEquals(alice, t.newest)
        val heard = mutableListOf<String?>()
        t.onChange { heard += it }
        p.answer = { bob }
        t.renew()
        assertEquals("asked again at once", 2, p.asked.get())
        assertEquals(bob, t.newest)
        assertEquals("let go of first, then the fresh one", listOf(null, bob), heard)
    }

    @Test fun anAnswerStillOnItsWayForTheUserBeforeIsNotKept() {
        val p = Provider().apply { manual = true }
        val t = tokens(p)
        t.start()
        assertEquals("the first user's question, under way", 1, p.pending.size)
        t.renew()
        assertEquals("asked anew for the next user", 2, p.pending.size)
        p.pending[0](fresh(id = "ct_alice"))
        assertNull("the answer for the user before speaks for them: not kept", t.newest)
        val bob = fresh(id = "ct_bob")
        p.pending[1](bob)
        assertEquals(bob, t.newest)
    }

    @Test fun whoeverWaitedForTheUserBeforesTokenGetsTheNextOne() {
        val p = Provider().apply { manual = true }
        val t = tokens(p, connectionWaitMs = 5_000)
        t.start()
        val got = arrayOfNulls<String>(1)
        val latch = CountDownLatch(1)
        Thread { got[0] = t.forConnection(); latch.countDown() }.start()
        // The connection is waiting on the first question when the user changes.
        while (p.pending.size < 1) Thread.sleep(5)
        Thread.sleep(50)
        t.renew()
        while (p.pending.size < 2) Thread.sleep(5)
        p.pending[0](fresh(id = "ct_alice"))
        val bob = fresh(id = "ct_bob")
        p.pending[1](bob)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("the connection goes with the next user's token", bob, got[0])
    }

    @Test fun aCallsHostRenewsItsOwnTokens() {
        val t = tokens(null)
        val pushed = fresh(id = "ct_host")
        t.push(pushed)
        t.renew()
        assertEquals("the host's manager decides: its push stands until it pushes again", pushed, t.newest)
    }

    // ── asking ───────────────────────────────────────────────────────────────

    @Test fun startAsksAtOnceAndAgainAtTheLeadBeforeExpiry() {
        val t1 = fresh(id = "ct_1")
        val p = Provider { t1 }
        val t = tokens(p, random = 0.0)
        assertFalse("nothing asked before start", p.asked.get() > 0)
        t.start()
        assertEquals(1, p.asked.get())
        assertEquals(t1, t.newest)
        assertTrue(t.inPlay)
        // An hour's token: asked again 600 s (the lead) before it lapses.
        sched.advance(3_000_000 - 1)
        assertEquals(1, p.asked.get())
        p.answer = { fresh(id = "ct_2") }
        sched.advance(1)
        assertEquals(2, p.asked.get())
        assertTrue(t.newest!!.isNotEmpty() && t.newest != t1)
    }

    @Test fun aProviderThatThrowsBacksOffThenSucceeds() {
        var calls = 0
        val good = fresh()
        val p = GraineTokenProvider { done ->
            calls++
            if (calls <= 2) throw IllegalStateException("the app's server is down")
            done(good)
        }
        val t = tokens(p, random = 0.5)
        t.start()
        assertEquals(1, calls)
        assertNull(t.newest)
        sched.advance(1_999)
        assertEquals("2 s after the first failure, not before", 1, calls)
        sched.advance(1)
        assertEquals(2, calls)
        sched.advance(3_999)
        assertEquals("then 4 s", 2, calls)
        sched.advance(1)
        assertEquals(3, calls)
        assertEquals(good, t.newest)
        // A token resets the backoff: the next question is the token's own refresh, not 8 s on.
        sched.advance(8_000)
        assertEquals(3, calls)
    }

    @Test fun nullAndSilenceAreFailuresAndTheBackoffIsJitteredAndCapped() {
        val p = Provider { null }
        val t = tokens(p, random = 1.0)
        t.start()
        val at = mutableListOf<Long>()
        repeat(8) {
            val before = p.asked.get()
            val from = sched.now()
            while (p.asked.get() == before) sched.advance(100)
            at += sched.now() - from
        }
        // 2, 4, 8, 16, 32, 60, 60, 60 s — each 20 % later at the jitter's top (rounded up to the 100 ms steps taken).
        assertEquals(listOf(2_400L, 4_800L, 9_600L, 19_200L, 38_400L, 72_000L, 72_000L, 72_000L), at)

        // A provider that never answers has answered null after its timeout.
        val silent = Provider().also { it.manual = true }
        val s = ClientTokens(silent, clock = { base + sched.now() }, random = { 0.5 }, schedule = { d, task -> sched.schedule(d, task) }, background = { it() })
        s.start()
        assertEquals(1, silent.asked.get())
        sched.advance(ClientTokenRules.PROVIDER_TIMEOUT_MS)
        sched.advance(2_000)
        assertEquals("timed out, then asked again 2 s on", 2, silent.asked.get())
        // Its late answer is ignored.
        silent.pending.first()("late")
        assertNull(s.newest)
    }

    @Test fun twoConnectionsAtOnceAskTheProviderOnce() {
        val p = Provider().also { it.manual = true }
        val t = tokens(p, connectionWaitMs = 10_000)
        val got = Collections.synchronizedList(ArrayList<String?>())
        val done = CountDownLatch(2)
        repeat(2) { Thread { got += t.forConnection(); done.countDown() }.start() }
        waitFor { p.asked.get() == 1 && p.pending.size == 1 }
        Thread.sleep(150)
        assertEquals("one question, however many connections wait", 1, p.asked.get())
        val token = fresh()
        p.pending.single()(token)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(token, token), got.toList())
        assertEquals(1, p.asked.get())
    }

    @Test fun aConnectionDuringABackgroundRefreshWaitsForItAndNeverAsksAgain() {
        val short = fresh(lifeSeconds = 60, id = "ct_short")
        val p = Provider { short }
        // A slow provider: still answering once the token has 15 s or less left.
        val t = tokens(p, random = 0.0, connectionWaitMs = 10_000, providerTimeoutMs = 60_000)
        t.start()
        // The background refresh is under way (the provider has not answered yet)…
        p.manual = true
        sched.advance(30_000)
        assertEquals(2, p.asked.get())
        // …and the token has 15 s or less left: a connection waits for that refresh, never asks one of its own.
        sched.advance(16_000)
        val got = arrayOfNulls<String>(1)
        val done = CountDownLatch(1)
        Thread { got[0] = t.forConnection(); done.countDown() }.start()
        Thread.sleep(150)
        assertEquals(2, p.asked.get())
        val next = fresh(id = "ct_next")
        p.pending.single()(next)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(next, got[0])
        assertEquals(2, p.asked.get())
    }

    @Test fun aUsableTokenIsUsedAtOnceAndAConnectionWaitsAtMostItsLimit() {
        val p = Provider { fresh() }
        val t = tokens(p)
        t.start()
        val held = t.newest
        assertEquals("plenty left: no question", held, t.forConnection())
        assertEquals(1, p.asked.get())
        // A provider that does not answer: the connection goes with the newest token after its wait (2 s here).
        p.manual = true
        sched.advance(3_600_000)
        val started = System.nanoTime()
        assertEquals(held, t.forConnection())
        assertTrue((System.nanoTime() - started) / 1_000_000 in 1_500..6_000)
    }

    // ── the app away, and a ring ─────────────────────────────────────────────

    @Test fun nothingIsAskedInTheBackgroundAndAForegroundReturnInsideTheLeadAsks() {
        val p = Provider { null }
        val t = tokens(p, random = 0.5)
        t.start()
        assertEquals(1, p.asked.get())
        t.setForeground(false)
        sched.advance(300_000)
        assertEquals("no retries while the app is in the background", 1, p.asked.get())
        p.answer = { fresh() }
        t.setForeground(true)
        assertEquals("back in front after a failure: asked at once", 2, p.asked.get())
        // An hour's token, its refresh due at 3000 s: the app away past it, then back.
        t.setForeground(false)
        sched.advance(3_100_000)
        assertEquals(2, p.asked.get())
        t.setForeground(true)
        assertEquals("inside the lead: asked", 3, p.asked.get())
        // Back in front well before the lead: nothing now, the refresh at its moment.
        t.setForeground(false)
        sched.advance(60_000)
        t.setForeground(true)
        assertEquals(3, p.asked.get())
        sched.advance(3_000_000 - 60_000)
        assertEquals(4, p.asked.get())
    }

    @Test fun aRingAsksForAFreshTokenUnlessOneJustArrived() {
        val p = Provider { fresh() }
        val t = tokens(p)
        t.start()
        t.ring()
        assertEquals("one arrived just now: enough", 1, p.asked.get())
        sched.advance(31_000)
        t.setForeground(false)
        t.ring()
        assertEquals("asked, even with the app in the background", 2, p.asked.get())
        tokens(null).ring()
    }

    // ── a refusal ────────────────────────────────────────────────────────────

    @Test fun aTokenExpiredRefusalAsksOnceAndTheRequestGoesOnceMoreWithTheNewToken() {
        val first = fresh(id = "ct_first")
        val second = fresh(id = "ct_second")
        val p = Provider { first }
        val t = tokens(p)
        t.start()
        p.answer = { second }
        val sent = mutableListOf<JSONObject>()
        val reply = ClientTokenWire.post(t, JSONObject().put("publishableKey", "pk"), newConnection = false) { json ->
            sent += JSONObject(json)
            if (sent.size == 1) refused("token_expired") else ok
        }
        assertEquals(200, reply.code)
        assertEquals("exactly one more question", 2, p.asked.get())
        assertEquals("exactly one retry", 2, sent.size)
        assertEquals(first, sent[0].getString(ClientTokenRules.FIELD))
        assertEquals(second, sent[1].getString(ClientTokenRules.FIELD))
    }

    @Test fun tokenScopeKeyRevokedAndTheBotCheckAreFinalAndNeverRetried() {
        val p = Provider { fresh() }
        val t = tokens(p)
        t.start()
        for ((code, status) in listOf("token_scope" to 403, "key_revoked" to 401, "bot_check_failed" to 403)) {
            var sends = 0
            val reply = ClientTokenWire.post(t, JSONObject(), newConnection = true) { sends++; refused(code, status) }
            assertEquals(code, 1, sends)
            assertEquals(code, ClientTokenRules.code(reply.body))
            assertEquals("$code: the provider is not asked", 1, p.asked.get())
        }
        // Through every request's own path, the refusal arrives as the code with its reason.
        val api = EmbedApi("https://www.graine.ai", "pk", { _, _ -> HttpReply(403, """{"error":"Not for this agent.","code":"token_scope","reason":"agent"}""") }, t)
        val failed = api.post(EmbedApi.LIST, JSONObject()) as EmbedReply.Failed
        assertEquals(GraineErrorCode.TOKEN_SCOPE, failed.error.code)
        assertEquals("agent", failed.error.reason)
        assertEquals("Not for this agent.", failed.error.message)
        assertEquals(1, p.asked.get())
    }

    @Test fun aRefusalAsksAtMostOnceInFiveSecondsAndNeverRetriesWithTheSameToken() {
        val p = Provider { fresh(id = "ct_a") }
        val t = tokens(p)
        t.start()
        // The app's server hands out the same token again: the refusal stands, with no second request.
        val same = t.newest
        p.answer = { same }
        var sends = 0
        ClientTokenWire.post(t, JSONObject(), newConnection = false) { sends++; refused("token_invalid") }
        assertEquals(2, p.asked.get())
        assertEquals(1, sends)
        // A second refusal a second later asks nobody: the refusal stands.
        sched.advance(1_000)
        sends = 0
        ClientTokenWire.post(t, JSONObject(), newConnection = false) { sends++; refused("token_required") }
        assertEquals(2, p.asked.get())
        assertEquals(1, sends)
        // 5 s on, a refusal may ask again — and goes once more with what it brought.
        val older = same
        p.answer = { fresh(id = "ct_b") }
        sched.advance(5_000)
        val sent = mutableListOf<String?>()
        ClientTokenWire.post(t, JSONObject(), newConnection = false) { json ->
            sent += JSONObject(json).optString(ClientTokenRules.FIELD)
            if (sent.size == 1) refused("token_expired") else ok
        }
        assertEquals(3, p.asked.get())
        val newest = t.newest
        assertEquals(listOf(older, newest), sent)
        // A refusal for a token older than the newest — a refresh arrived while it was on its way — goes once more
        // with the newest, asking nobody.
        sched.advance(1_000)
        assertEquals(newest, t.afterRefusal(older))
        assertEquals(3, p.asked.get())
        // One refused with the newest itself, within the 5 s: the refusal stands.
        assertNull(t.afterRefusal(newest))
        assertEquals(3, p.asked.get())
    }

    @Test fun withoutAProviderNoRequestCarriesATokenAndNothingIsRetried() {
        val t = tokens(null)
        t.start()
        assertFalse(t.inPlay)
        assertNull(t.forConnection())
        val sent = mutableListOf<JSONObject>()
        val reply = ClientTokenWire.post(t, JSONObject().put("publishableKey", "pk"), newConnection = true) { json -> sent += JSONObject(json); refused("token_required") }
        assertEquals(401, reply.code)
        assertEquals(1, sent.size)
        assertFalse(sent.single().has(ClientTokenRules.FIELD))
        // And with no manager at all, the body goes as it is.
        ClientTokenWire.post(null, JSONObject().put("a", 1), newConnection = true) { json -> assertEquals("""{"a":1}""", json); ok }
    }

    // ── a calls host's tokens ────────────────────────────────────────────────

    @Test fun aPushedTokenIsSentAndARefusalAsksTheHostAndWaitsForItsPush() {
        val t = tokens(null)
        val heard = mutableListOf<String?>()
        t.onChange { heard += it }
        assertFalse(t.inPlay)
        t.push("host_1")
        assertTrue(t.inPlay)
        assertEquals("host_1", t.newest)
        assertEquals("a connection goes with what the host pushed", "host_1", t.forConnection())
        assertEquals(listOf<String?>("host_1"), heard)
        var asked = 0
        t.onNeed = { asked++; t.push("host_2") }
        val sent = mutableListOf<String>()
        val reply = ClientTokenWire.post(t, JSONObject(), newConnection = false) { json ->
            sent += JSONObject(json).getString(ClientTokenRules.FIELD)
            if (sent.size == 1) refused("token_expired") else ok
        }
        assertEquals(200, reply.code)
        assertEquals(1, asked)
        assertEquals(listOf("host_1", "host_2"), sent)
        // A host that pushes nothing in time: the refusal stands after the wait (2 s here).
        sched.advance(10_000)
        t.onNeed = { asked++ }
        val started = System.nanoTime()
        val stood = ClientTokenWire.post(t, JSONObject(), newConnection = false) { refused("token_expired") }
        assertEquals(401, stood.code)
        assertEquals(2, asked)
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 1_500)
        t.push(null)
        assertNull(t.newest)
        assertTrue("null is a push too: still the host's tokens", t.inPlay)
    }

    @Test fun anAppsOwnProviderIgnoresPushedTokens() {
        val p = Provider { fresh(id = "ct_app") }
        val t = tokens(p)
        t.start()
        val own = t.newest
        t.push("host_token")
        assertEquals(own, t.newest)
    }

    @Test fun theSuspendingAdapterHandsOnItsAnswerAndAThrowIsNull() {
        var got: String? = "unset"
        GraineTokenProvider.suspending { "tok" }.fetch { got = it }
        assertEquals("tok", got)
        GraineTokenProvider.suspending { throw IllegalStateException("down") }.fetch { got = it }
        assertNull(got)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.nanoTime() + 5_000_000_000L
        while (!condition()) {
            if (System.nanoTime() > until) throw AssertionError("timed out")
            Thread.sleep(10)
        }
    }
}
