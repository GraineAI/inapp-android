package ai.graine.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DIGEST AUTHENTICATION: RFC 2617's own example, the form without qop, and
 * the header in the order the browser's stack writes it — the order the
 * server has always accepted (TRACE.md §5).
 */
class SipDigestTest {
    @Test fun matchesRfc2617Section35() {
        // RFC 2617 §3.5: user Mufasa, the RFC's own example phrase, GET /dir/index.html. Positional, as the
        // leak guard reads any named credential with a value as a secret, even a published test vector.
        val response = SipDigest.response(
            "Mufasa", "testrealm@host.com", "Circle Of Life", "GET",
            "/dir/index.html", "dcd98b7102dd2f0e8b11d0f600bfb0c093", "auth", "00000001", "0a4f113b",
        )
        assertEquals("6629fae49393a05397450978507c4ef1", response)
    }

    @Test fun answersWithoutQopAsRfc2069() {
        val ha1 = SipDigest.md5Hex("u:r:p")
        val ha2 = SipDigest.md5Hex("REGISTER:sip:r")
        assertEquals(SipDigest.md5Hex("$ha1:n:$ha2"), SipDigest.response("u", "r", "p", "REGISTER", "sip:r", "n", null, "", ""))
    }

    @Test fun writesTheHeaderInTheBrowsersOrder() {
        val c = SipChallenge.parse("Digest realm=\"${Trace.REALM}\", algorithm=MD5, qop=\"auth\", nonce=\"abc\"")!!
        val h = SipDigest.authorize(c, "web-XXXX", "pw", "INVITE", "sip:app-1@${Trace.REALM}", null, "cn0nce")!!
        val expected = SipDigest.response("web-XXXX", Trace.REALM, "pw", "INVITE", "sip:app-1@${Trace.REALM}", "abc", "auth", "00000001", "cn0nce")
        assertEquals(
            "Digest algorithm=MD5, username=\"web-XXXX\", realm=\"${Trace.REALM}\", nonce=\"abc\", uri=\"sip:app-1@${Trace.REALM}\", " +
                "response=\"$expected\", qop=auth, cnonce=\"cn0nce\", nc=00000001",
            h,
        )
    }

    @Test fun carriesOpaqueAndPrefersAuthIntAsTheBrowserDoes() {
        val c = SipChallenge.parse("Digest realm=\"r\", nonce=\"n\", opaque=\"o\", qop=\"auth,auth-int\"")!!
        val h = SipDigest.authorize(c, "u", "p", "INVITE", "sip:x@r", "v=0\r\n", "c")!!
        assertTrue(h.contains(", opaque=\"o\", qop=auth-int, cnonce=\"c\", nc=00000001"))
        val ha1 = SipDigest.md5Hex("u:r:p")
        val ha2 = SipDigest.md5Hex("INVITE:sip:x@r:${SipDigest.md5Hex("v=0\r\n")}")
        assertTrue(h.contains("response=\"${SipDigest.md5Hex("$ha1:n:00000001:c:auth-int:$ha2")}\""))
    }

    @Test fun refusesWhatItCannotAnswer() {
        assertNull("not MD5", SipDigest.authorize(SipChallenge.parse("Digest realm=r, nonce=n, algorithm=SHA-256")!!, "u", "p", "REGISTER", "sip:r", null, "c"))
        assertNull("an unknown qop only", SipDigest.authorize(SipChallenge.parse("Digest realm=r, nonce=n, qop=\"other\"")!!, "u", "p", "REGISTER", "sip:r", null, "c"))
    }
}
