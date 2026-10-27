package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * YOUR APP AND YOUR USERS, AGAINST THE CONTRACT FILE (sdk/contract/
 * app-identity.json — read, never skipped): the error codes and their wire
 * words, every verdict's word, the shapes, the certificate digest and the JWT
 * expiry vectors, the session's `security` examples, development mode's modes
 * and its log line, the passes' lifetimes, and the identity as it goes on the
 * wire.
 */
class AppIdentityContractTest {
    private val contract get() = AppIdentityContract.json

    @Test fun theContractIsThere() {
        assertEquals("0.14.4", contract.getString("sdkVersion"))
    }

    @Test fun everyErrorCodeIsKnownByItsWireWordAndName() {
        val codes = contract.getJSONArray("errorCodes")
        assertEquals(3, codes.length())
        for (i in 0 until codes.length()) {
            val c = codes.getJSONObject(i)
            val code = GraineErrorCode.from(c.getString("wire"))
            assertFalse("${c.getString("wire")} is known", code is GraineErrorCode.Unknown)
            assertEquals(c.getString("wire"), code.wire)
            assertEquals("the Kotlin name", c.getString("kotlin"), code.javaClass.simpleName)
            assertTrue(code in GraineErrorCode.KNOWN)
            // A refusal with the code is read as that code, whatever the status says.
            assertEquals(code, EmbedApi.errorFor(c.getInt("status"), JSONObject().put("code", c.getString("wire")).put("error", "no")).code)
        }
    }

    @Test fun everyAppVerdictHasTheContractsWord() {
        assertEquals(AppIdentityContract.strings("appVerdicts"), GraineAppVerdict.values().map { it.word })
        val wire = contract.getJSONObject("wireAppVerdicts")
        for (k in wire.keys()) assertEquals(k, wire.getString(k), GraineAppVerdict.fromWire(k)?.word)
        assertNull(GraineAppVerdict.fromWire("checking"))
        assertNull(GraineAppVerdict.fromWire(null))
    }

    @Test fun everyUserVerdictHasTheContractsWord() {
        assertEquals(AppIdentityContract.strings("userVerdicts"), GraineUserVerdict.values().map { it.word })
        val wire = contract.getJSONObject("wireUserVerdicts")
        for (k in wire.keys()) assertEquals(k, wire.getString(k), GraineUserVerdict.fromWire(k)?.word)
        assertNull(GraineUserVerdict.fromWire("maybe"))
    }

    @Test fun theCertificateDigestVector() {
        val v = contract.getJSONObject("certificateDigest")
        val der = v.getString("derHex").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals(v.getString("sha256"), AppDeclarations.sha256Hex(der))
        val shape = Regex(contract.getJSONObject("shapes").getString("certSha256"))
        assertTrue(shape.matches(AppDeclarations.sha256Hex(der)))
        assertEquals(listOf(v.getString("sha256")), AppDeclarations.digests(listOf(der, der.copyOf())))
    }

    @Test fun theShapes() {
        val shapes = contract.getJSONObject("shapes")
        assertEquals(shapes.getInt("maxCerts"), AppDeclarations.MAX_CERTS)
        assertEquals(shapes.getInt("maxIdentityTokenLength"), UserProof.MAX_TOKEN_LENGTH)
        val debug = Regex(shapes.getString("debugToken"))
        repeat(50) { assertTrue(debug.matches(DebugTokens.fresh(SecureRandom()))) }
        assertTrue(DebugTokens.valid(DebugTokens.fresh()))
        assertFalse(DebugTokens.valid("gdt_short"))
        val challenge = Regex(shapes.getString("challenge"))
        assertTrue(challenge.matches(FakeEmbed.challenge(1)))
    }

    @Test fun theJwtExpiryIsReadWithoutVerifying() {
        val v = contract.getJSONObject("jwtExpiry")
        val ex = v.getJSONObject("example")
        assertEquals(ex.getLong("exp"), Jwt.expiresAt(ex.getString("token")))
        assertEquals(v.getLong("refreshWhenSecondsLeftBelow") * 1000, UserProof.JWT_REFRESH_BELOW_MS)
        // An HMAC token is no JWT, and neither is anything of another shape.
        assertNull(Jwt.expiresAt(contract.getJSONObject("identityWire").getJSONObject("identity").getString("token")))
        assertNull(Jwt.expiresAt("a.b"))
        assertNull(Jwt.expiresAt("a.b.c.d"))
        assertNull(Jwt.expiresAt("eyJhbGciOiJub25lIn0.bm90IGpzb24.c2ln"))
    }

    @Test fun thePassesLifetimes() {
        val p = contract.getJSONObject("passes")
        assertEquals(p.getLong("appRefreshWhenSecondsLeftBelow") * 1000, AppProofs.REFRESH_BELOW_MS)
        assertEquals(p.getLong("ringGraceSeconds") * 1000, AppProofs.RING_GRACE_MS)
        assertEquals(p.getLong("outageGraceSeconds") * 1000, AppProofs.OUTAGE_GRACE_MS)
    }

    @Test fun theSessionsSecurityExamples() {
        val examples = contract.getJSONArray("sessionSecurityExamples")
        for (i in 0 until examples.length()) {
            val e = examples.getJSONObject(i)
            val s = SecuritySettings.parse(e.getJSONObject("security"))
            assertEquals("example $i proves", e.getBoolean("prove"), s.provesApp)
            assertEquals("example $i waits", e.optBoolean("waitForProofBeforeTicket", false), s.enforcesApp)
            val app = e.getJSONObject("security").getJSONObject("app")
            assertEquals(app.optString("cloudProjectNumber").ifEmpty { null }?.toLong(), s.cloudProjectNumber)
            assertEquals(e.getJSONObject("security").getJSONObject("user").optString("method").ifEmpty { null }, s.userMethod)
        }
        // No security at all — an older server — is nothing configured.
        assertFalse(SecuritySettings.parse(null).provesApp)
        assertFalse(SecuritySettings.parse(null).checksUsers)
    }

    @Test fun developmentModesAndTheirLogLine() {
        val d = contract.getJSONObject("debugTokens")
        assertEquals(AppIdentityContract.json.getJSONObject("debugTokens").getJSONArray("modes").let { a -> (0 until a.length()).map { a.getString(it) } },
            GraineDebugTokenMode.values().map { it.word })
        assertEquals(d.getString("defaultMode"), AppProofsTest.proofs().app.debugTokenMode.word)
        val token = DebugTokens.fresh()
        assertEquals(d.getString("logLine").replace("{token}", token), DebugTokens.logLine(token))
        // AUTOMATIC: debuggable builds only. ALWAYS: every build. NEVER: none.
        assertTrue(DebugTokens.inUse(GraineDebugTokenMode.AUTOMATIC, debuggable = true))
        assertFalse(DebugTokens.inUse(GraineDebugTokenMode.AUTOMATIC, debuggable = false))
        assertTrue(DebugTokens.inUse(GraineDebugTokenMode.ALWAYS, debuggable = false))
        assertFalse(DebugTokens.inUse(GraineDebugTokenMode.NEVER, debuggable = true))
    }

    @Test fun theIdentityGoesOnTheWireAsTheContractShowsIt() {
        val wire = contract.getJSONObject("identityWire").getJSONObject("identity")
        val user = UserProof()
        val traits = mapOf("user_id" to wire.getString("value"), "name" to "Asha")
        user.identify(traits, wire.getString("token"), sameUser = false)
        user.heard("pk_x", wire.getString("value"), user.generation,
            JSONObject().put("user", "verified").put("userPass", wire.getString("pass")).put("userPassExpiresIn", 3600))
        val proofs = Proofs(AppProofsTest.appProofs(), user, fieldFor = { "user_id" })
        val body = IdentityEnvelope(wire.getString("value"), "inst-1", true, "UTC").who(JSONObject().put("publishableKey", "pk_x"))
        proofs.stamp(body, "pk_x")
        val sent = body.getJSONObject("identity")
        assertTrue("$sent is $wire", sameJson(wire, sent))
    }
}
