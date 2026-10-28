package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * SERVER TOKENS, KEYS AND THE BOT CHECK — the contract's vectors
 * (sdk/contract/CLIENT-TOKENS.md, client-tokens.json), every case of each, on
 * the JVM: the six codes as this SDK's error type with their retry rule and
 * reasons, what a token says about its own life (`sdk.decode`), when each is
 * refreshed (`sdk.schedule`, by the rule and by the token manager itself), when
 * a connection may use one (`sdk.usable`), the backoff (`sdk.backoffSeconds`),
 * and the words and numbers every SDK shares. A missing contract file fails.
 */
class ClientTokensContractTest {
    private val contract: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/client-tokens.json").exists() && !File(dir, "contract/client-tokens.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/client-tokens.json"), File(dir, "contract/client-tokens.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/client-tokens.json missing — this contract is never skipped")
        JSONObject(f.readText())
    }

    private val sdk: JSONObject get() = contract.getJSONObject("sdk")

    private fun cases(o: JSONObject, name: String): List<JSONObject> {
        val list = o.getJSONArray(name)
        assertTrue("section $name is empty", list.length() > 0)
        return (0 until list.length()).map { list.getJSONObject(it) }
    }

    private fun token(name: String): String = contract.getJSONObject("token").getJSONObject("tokens").getString(name)

    private fun seconds(o: JSONObject, key: String): Double? = if (o.isNull(key)) null else o.getDouble(key)

    // ── the codes ────────────────────────────────────────────────────────────

    @Test fun everyCodeIsThisSdksWithItsWireWordAndItsRetryRule() {
        val codes = cases(contract, "errorCodes")
        for (c in codes) {
            val wire = c.getString("wire")
            val code = GraineErrorCode.from(wire)
            assertEquals(wire, code.wire)
            assertTrue("$wire is a code this build knows", code in GraineErrorCode.KNOWN)
            assertEquals("the Kotlin name of $wire", c.getString("kotlin"), code.javaClass.simpleName)
            assertEquals("case and spaces never matter", code, GraineErrorCode.from("  ${wire.uppercase()} "))
            when (val retry = c.getString("retry")) {
                // A fresh token can lift it: asked for again, and the request goes once more.
                "refresh_then_retry_once" -> assertTrue(wire, wire in ClientTokenRules.REFRESHABLE)
                // Final for the request: surfaced, never looped.
                "never" -> assertFalse(wire, wire in ClientTokenRules.REFRESHABLE)
                // The website's alone: an app never answers a challenge, so never retries it.
                "website_challenge_then_retry_once" -> assertFalse(wire, wire in ClientTokenRules.REFRESHABLE)
                else -> throw AssertionError("a retry rule this SDK does not know: $retry")
            }
        }
        assertEquals("every refusal of the contract, and only those, is final for a conversation",
            codes.map { it.getString("wire") }.toSet(), ClientTokenRules.REFUSALS)
    }

    @Test fun aRefusalIsReadWithItsStatusSentenceAndReason() {
        val reasons = contract.getJSONObject("reasons")
        for (c in cases(contract, "errorCodes")) {
            val wire = c.getString("wire")
            val status = c.getInt("status")
            val known = reasons.optJSONArray(wire)
            val said = (0 until (known?.length() ?: 0)).map { known!!.getString(it) }.ifEmpty { listOf<String?>(null) }
            for (reason in said) {
                val body = JSONObject().put("error", "Refused.").put("code", wire).apply { reason?.let { put("reason", it) } }
                // Every request's reading of a refusal: the code, Graine's sentence, its reason.
                val e = EmbedApi.errorFor(status, body)
                assertEquals(wire, e.code.wire)
                assertEquals("Refused.", e.message)
                assertEquals(reason, e.reason)
                // A conversation's reading: the same, final.
                val r = ClientTokenRules.refusal(status, body.toString())!!
                assertEquals(e, r)
                // Graine without a sentence: the SDK's own, never the allowed-domains one.
                val bare = ClientTokenRules.refusal(status, JSONObject().put("code", wire).toString())!!
                assertEquals(ClientTokenRules.sentence(wire), bare.message)
                assertFalse(bare.message.contains("app://"))
            }
        }
        assertNull("any other refusal goes where it always went", ClientTokenRules.refusal(403, """{"code":"origin_refused"}"""))
        assertNull(ClientTokenRules.refusal(200, """{"code":"token_scope"}"""))
        assertNull(ClientTokenRules.refusal(503, "not json"))
        assertEquals("a call that could not start says the code first",
            "key_revoked: Revoked.", ClientTokenRules.callError(GraineError(GraineErrorCode.KEY_REVOKED, "Revoked.")))
    }

    // ── what a token says about its own life ─────────────────────────────────

    @Test fun everyTokenDecodesAsTheVectorsSay() {
        for (c in cases(sdk, "decode")) {
            val name = c.getString("token")
            val claims = ClientTokenRules.decode(token(name))
            assertEquals("$name iat", seconds(c, "iat"), claims.iat)
            assertEquals("$name exp", seconds(c, "exp"), claims.exp)
        }
        assertEquals(ClientTokenRules.Claims(null, null), ClientTokenRules.decode(null))
        assertEquals(ClientTokenRules.Claims(null, null), ClientTokenRules.decode(""))
        assertEquals(ClientTokenRules.Claims(null, null), ClientTokenRules.decode("a..c"))
        assertNull("no readable expiry: nothing in the background", ClientTokenRules.localExpiry(ClientTokenRules.decode("not-a-token"), 1_000))
    }

    @Test fun everyScheduleIsTheVectorsOwn() {
        for (c in cases(sdk, "schedule")) {
            val name = c.getString("name")
            val claims = ClientTokenRules.Claims(c.getDouble("iat"), c.getDouble("exp"))
            val receivedAt = c.getLong("receivedAt") * 1000
            val life = ClientTokenRules.lifetimeMs(claims)!!
            val localExp = ClientTokenRules.localExpiry(claims, receivedAt)!!
            assertEquals("$name localExp", c.getLong("localExp") * 1000, localExp)
            assertEquals("$name lead", c.getLong("lead") * 1000, ClientTokenRules.leadMs(life))
            assertEquals("$name refreshAt", c.getLong("refreshAt") * 1000, localExp - ClientTokenRules.leadMs(life))
            assertEquals("$name maxJitter", c.getLong("maxJitter") * 1000, ClientTokenRules.maxJitterMs(life))
        }
    }

    /**
     * The same vectors through the token manager itself: a token minted with
     * each case's `iat` and `exp`, arriving at `receivedAt`, is asked for again
     * at `refreshAt` less its jitter (none, then the most) — and never sooner
     * than 30 s after it arrived.
     */
    @Test fun theTokenManagerRefreshesAtEachVectorsMoment() {
        for (c in cases(sdk, "schedule")) {
            for (draw in listOf(0.0, 1.0)) {
                val name = c.getString("name")
                val sched = ManualScheduler()
                val receivedAt = c.getLong("receivedAt") * 1000
                val minted = TestTokens.mint(c.getLong("iat"), c.getLong("exp"))
                var asked = 0
                val tokens = ClientTokens(
                    GraineTokenProvider { done -> asked++; done(minted) },
                    clock = { receivedAt + sched.now() },
                    random = { draw },
                    schedule = { d, task -> sched.schedule(d, task) },
                    background = { it() },
                )
                tokens.start()
                assertEquals(name, 1, asked)
                val jitter = Math.round(draw * c.getLong("maxJitter") * 1000)
                val due = maxOf(c.getLong("refreshAt") * 1000 - jitter, receivedAt + ClientTokenRules.BACKGROUND_MIN_INTERVAL_MS) - receivedAt
                sched.advance(due - 1)
                assertEquals("$name (jitter $jitter): not before its moment", 1, asked)
                sched.advance(1)
                assertEquals("$name (jitter $jitter): at its moment", 2, asked)
            }
        }
    }

    @Test fun aConnectionUsesATokenAsTheVectorsSay() {
        for (c in cases(sdk, "usable")) {
            assertEquals(c.toString(), c.getBoolean("usable"), ClientTokenRules.usable(c.getLong("localExp") * 1000, c.getLong("now") * 1000))
        }
        assertTrue("no readable expiry: the token is used, and Graine judges it", ClientTokenRules.usable(null, 0))
    }

    @Test fun theBackoffIsTheVectorsOwn() {
        val list = sdk.getJSONArray("backoffSeconds")
        assertTrue(list.length() >= 6)
        for (i in 0 until list.length()) assertEquals("failure ${i + 1}", list.getLong(i) * 1000, ClientTokenRules.backoffMs(i + 1))
        assertEquals(sdk.getDouble("backoffJitter"), ClientTokenRules.BACKOFF_JITTER, 0.0)
    }

    @Test fun theWordsAndNumbersAreTheContracts() {
        assertEquals(sdk.getString("wireField"), ClientTokenRules.FIELD)
        val page = sdk.getJSONObject("pageMessages")
        assertEquals(page.getString("toPage"), Protocol.Out.CLIENT_TOKEN)
        assertEquals(page.getString("toHost"), Protocol.In.NEED_CLIENT_TOKEN)
        assertEquals(page.getLong("pageWaitMs"), ClientTokenRules.PUSH_WAIT_MS)
        assertEquals(PageMessage.NeedClientToken, Protocol.parse(JSONObject().put("type", page.getString("toHost")).toString()))
        assertEquals(sdk.getLong("usableForNewConnectionSeconds") * 1000, ClientTokenRules.USABLE_FOR_CONNECTION_MS)
        assertEquals(sdk.getLong("newConnectionWaitSeconds") * 1000, ClientTokenRules.CONNECTION_WAIT_MS)
        assertEquals(sdk.getLong("forcedRefreshMinIntervalSeconds") * 1000, ClientTokenRules.FORCED_MIN_INTERVAL_MS)
        assertEquals(sdk.getLong("minSecondsBetweenBackgroundRefreshes") * 1000, ClientTokenRules.BACKGROUND_MIN_INTERVAL_MS)
        // The version server tokens arrived in (CLIENT-TOKENS.md), as iOS's and the multiplatform library's tests pin it:
        // this SDK implements the contract from then on (0.14.6 moved on, the contract did not).
        assertEquals("0.14.4", contract.getString("sdkVersion"))
        val parts = { v: String -> v.split(".").map(String::toInt) }
        val newer = parts(GraineVersion.NAME).zip(parts("0.14.4")).map { (a, b) -> a.compareTo(b) }.firstOrNull { it != 0 } ?: 0
        assertTrue("this SDK is at least the contract's version", newer >= 0)
        // The calls host's event, and the page's message, as the contract names them (CLIENT-TOKENS.md §2.2, §2.4).
        assertEquals("needClientToken", CallsHostEvent.NEED_CLIENT_TOKEN)
        assertNotEquals(Protocol.Out.CLIENT_TOKEN, Protocol.In.NEED_CLIENT_TOKEN)
    }
}

/** Tokens for the tests: a header, a payload with `iat` and `exp`, and a signature nobody checks — the SDK reads only the two. */
internal object TestTokens {
    private fun part(json: String): String = B64.encode(json.toByteArray()).trimEnd('=').replace('+', '-').replace('/', '_')

    fun mint(iat: Long, exp: Long, id: String = "ct_test"): String =
        part("""{"alg":"EdDSA","typ":"JWT","kid":"test-key-000000"}""") + "." +
            part("""{"iss":"graine","aud":"graine-embed","sub":"user-123","scp":["chat","call"],"iat":$iat,"exp":$exp,"jti":"$id"}""") + ".c2ln"
}
