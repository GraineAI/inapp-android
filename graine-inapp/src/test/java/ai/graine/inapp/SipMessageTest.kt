package ai.graine.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE SIP PARSER, ON PRODUCTION'S OWN MESSAGES AND ON EVERY SHAPE IT MUST
 * REFUSE: responses and requests from the reference trace, folded headers,
 * compact names, several Via and Record-Route values (one per line and
 * comma-joined), bodies cut to Content-Length — and anything malformed,
 * oversized or incomplete dropped as null, never thrown.
 */
class SipMessageTest {
    private fun msg(vararg lines: String, body: String = ""): String =
        lines.joinToString("\r\n") + "\r\n\r\n" + body

    /** The 401 the SBC sends a REGISTER, verbatim from the trace (redacted). */
    private val challenge401 = msg(
        "SIP/2.0 401 Unauthorized",
        "Via: SIP/2.0/WSS cdh6l13lka9h.invalid;branch=z9hG4bK7801448;received=192.0.2.10;rport=65005",
        "From: <sip:web-XXXX@${Trace.REALM}>;tag=4kj2mue2i7",
        "To: <sip:web-XXXX@${Trace.REALM}>;tag=7XZFBD4cK46Dr",
        "Call-ID: 5jl5ol93rs8bq7uj2ivsf1",
        "CSeq: 1 REGISTER",
        "WWW-Authenticate: Digest realm=\"${Trace.REALM}\", algorithm=MD5, qop=\"auth\", nonce=\"abc123\"",
        "Content-Length: 0",
    )

    @Test fun readsTheSbcsChallengeAsSent() {
        val m = SipParser.parse(challenge401)!!
        assertFalse(m.isRequest)
        assertEquals(401, m.status)
        assertEquals("Unauthorized", m.reason)
        assertEquals("z9hG4bK7801448", m.topViaBranch)
        assertEquals("4kj2mue2i7", m.fromTag)
        assertEquals("7XZFBD4cK46Dr", m.toTag)
        assertEquals("5jl5ol93rs8bq7uj2ivsf1", m.callId)
        assertEquals(1, m.cseq.number)
        assertEquals("REGISTER", m.cseq.method)
        val c = SipChallenge.parse(m.header("www-authenticate"))!!
        assertEquals(Trace.REALM, c.realm)
        assertEquals("abc123", c.nonce)
        assertEquals(listOf("auth"), c.qop)
        assertEquals("MD5", c.algorithm)
        assertNull(c.opaque)
        assertFalse(c.stale)
        assertEquals("", m.body)
    }

    @Test fun readsTheAnswerItsContactAndCutsTheBodyToContentLength() {
        val answer = Trace.ANSWER
        val text = msg(
            "SIP/2.0 200 OK",
            "Via: SIP/2.0/WSS cdh6l13lka9h.invalid;branch=z9hG4bK7810508;received=192.0.2.10;rport=65005",
            "From: <sip:web-XXXX@${Trace.REALM}>;tag=qdq9n426ik",
            "To: <sip:app-${Trace.APP_SID}@${Trace.REALM}>;tag=cByBmmry4gpBj",
            "Call-ID: 8bfb0ntka5aua320artv",
            "CSeq: 368 INVITE",
            "Contact: <sip:${Trace.SBC}:5060;transport=tcp>",
            "Content-Type: application/sdp",
            "Content-Length: ${answer.toByteArray().size}",
            "X-Trace-ID: 1c9f7360d77b48c102207393e121ea68",
            "X-Call-Sid: 208fd2fd-4b9c-4732-bc61-5ab3819b1c21",
            body = answer + "trailing bytes past the length are dropped",
        )
        val m = SipParser.parse(text)!!
        assertEquals(200, m.status)
        assertEquals(answer, m.body)
        assertEquals("application/sdp", m.contentType)
        assertEquals("sip:${Trace.SBC}:5060;transport=tcp", SipAddress.parse(m.header("contact")!!)!!.uri)
        assertEquals("208fd2fd-4b9c-4732-bc61-5ab3819b1c21", m.header("X-Call-Sid"))
        assertTrue("no Record-Route in production's 200", m.values("record-route").isEmpty())
        assertNull("no Session-Expires either", m.header("session-expires"))
    }

    @Test fun readsARequestWithCompactNamesAndFoldedHeaders() {
        val m = SipParser.parse(msg(
            "BYE sip:e7q9tikf@cdh6l13lka9h.invalid;transport=ws SIP/2.0",
            "v: SIP/2.0/WSS ${Trace.SBC}:8443;branch=z9hG4bKsbc1",
            "f: <sip:app-x@${Trace.REALM}>;tag=sbc",
            "t: <sip:web-XXXX@${Trace.REALM}>",
            "  ;tag=ours",
            "i: call-1",
            "CSeq: 2 BYE",
            "X-Folded: one",
            "\ttwo",
            "l: 0",
        ))!!
        assertTrue(m.isRequest)
        assertEquals("BYE", m.method)
        assertEquals("sip:e7q9tikf@cdh6l13lka9h.invalid;transport=ws", m.requestUri)
        assertEquals("z9hG4bKsbc1", m.topViaBranch)
        assertEquals("sbc", m.fromTag)
        assertEquals("ours", m.toTag)
        assertEquals("call-1", m.callId)
        assertEquals("one two", m.header("x-folded"))
        assertEquals(m.header("content-length"), m.header("l"))
    }

    @Test fun readsSeveralViaAndRecordRouteValuesJoinedOrApart() {
        val m = SipParser.parse(msg(
            "SIP/2.0 200 OK",
            "Via: SIP/2.0/WSS a.invalid;branch=z9hG4bK1, SIP/2.0/TCP proxy.example.invalid;branch=z9hG4bK2",
            "Via: SIP/2.0/UDP edge.example.invalid;branch=z9hG4bK3",
            "Record-Route: <sip:p1.example.invalid;lr>, <sip:p2.example.invalid;lr;x=\"a,b\">",
            "Record-Route: <sip:p3.example.invalid;lr>",
            "From: <sip:a@b.invalid>;tag=1",
            "To: <sip:c@d.invalid>;tag=2",
            "Call-ID: x",
            "CSeq: 9 INVITE",
            "Content-Length: 0",
        ))!!
        assertEquals(3, m.values("via").size)
        assertEquals("z9hG4bK1", m.topViaBranch)
        assertEquals(
            listOf("<sip:p1.example.invalid;lr>", "<sip:p2.example.invalid;lr;x=\"a,b\">", "<sip:p3.example.invalid;lr>"),
            m.values("record-route"),
        )
    }

    @Test fun acceptsAViaWithSpacesAroundItsSlashes() {
        assertEquals("SIP/2.0/UDP", SipVia.parse("SIP / 2.0 / UDP host.invalid;branch=z9hG4bKa")!!.protocol)
    }

    @Test fun takesTheRestAsTheBodyWithoutAContentLength() {
        val m = SipParser.parse(msg(
            "INFO sip:u@h.invalid SIP/2.0", "Via: SIP/2.0/WSS h.invalid;branch=z9hG4bKx", "From: <sip:a@b.invalid>;tag=1",
            "To: <sip:u@h.invalid>;tag=2", "Call-ID: c", "CSeq: 3 INFO", body = "hello",
        ))!!
        assertEquals("hello", m.body)
    }

    @Test fun dropsEverythingMalformedWithoutThrowing() {
        val good = listOf("Via: SIP/2.0/WSS h.invalid;branch=z9hG4bKx", "From: <sip:a@b.invalid>;tag=1", "To: <sip:u@h.invalid>", "Call-ID: c", "CSeq: 1 OPTIONS")
        val cases = mapOf(
            "empty" to "",
            "no blank line" to "OPTIONS sip:u@h.invalid SIP/2.0\r\nVia: x\r\n",
            "a line of junk" to "\u0000\u0001garbage\r\n\r\n",
            "a status that is not three digits" to msg("SIP/2.0 2000 OK", *good.toTypedArray()),
            "a status out of range" to msg("SIP/2.0 099 Odd", *good.toTypedArray()),
            "the wrong version" to msg("OPTIONS sip:u@h.invalid SIP/3.0", *good.toTypedArray()),
            "a non-SIP Request-URI" to msg("OPTIONS http://h.invalid SIP/2.0", *good.toTypedArray()),
            "a lower-case method" to msg("options sip:u@h.invalid SIP/2.0", *good.toTypedArray()),
            "four parts on the request line" to msg("OPTIONS sip:u@h.invalid SIP/2.0 extra", *good.toTypedArray()),
            "a header with no colon" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.toTypedArray(), "NoColonHere"),
            "a header name with a space" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.toTypedArray(), "Bad Name: x"),
            "a fold before any header" to msg("OPTIONS sip:u@h.invalid SIP/2.0", " folded", *good.toTypedArray()),
            "no Call-ID" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.filter { !it.startsWith("Call-ID") }.toTypedArray()),
            "no Via" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.filter { !it.startsWith("Via") }.toTypedArray()),
            "a CSeq for another method" to msg("BYE sip:u@h.invalid SIP/2.0", *good.toTypedArray()),
            "a CSeq that is not a number" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.filter { !it.startsWith("CSeq") }.toTypedArray(), "CSeq: x OPTIONS"),
            "a CSeq past 2^31" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.filter { !it.startsWith("CSeq") }.toTypedArray(), "CSeq: 99999999999 OPTIONS"),
            "a From that is not an address" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.filter { !it.startsWith("From") }.toTypedArray(), "From: nobody"),
            "an unclosed angle bracket" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.filter { !it.startsWith("To") }.toTypedArray(), "To: <sip:u@h.invalid"),
            "a Via that is not one" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.filter { !it.startsWith("Via") }.toTypedArray(), "Via: banana"),
            "an unterminated quote in a parameter" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.filter { !it.startsWith("From") }.toTypedArray(), "From: <sip:a@b.invalid>;tag=\"1"),
            "a Content-Length longer than the body" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.toTypedArray(), "Content-Length: 50", body = "short"),
            "a negative Content-Length" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.toTypedArray(), "Content-Length: -1"),
            "a Content-Length that is not a number" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.toTypedArray(), "Content-Length: lots"),
            "a message over 64 KB" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.toTypedArray(), body = "x".repeat(70_000)),
            "a header over 8 KB" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.toTypedArray(), "X-Big: " + "y".repeat(9_000)),
            "more than 100 headers" to msg("OPTIONS sip:u@h.invalid SIP/2.0", *good.toTypedArray(), *Array(120) { "X-H$it: v" }),
        )
        for ((name, text) in cases) assertNull("$name must be dropped", SipParser.parse(text))
    }

    @Test fun readsAddressesWithAndWithoutAngleBrackets() {
        val a = SipAddress.parse("\"Agent, the \\\"one\\\"\" <sip:app-1@r.invalid;transport=ws>;tag=abc;x")!!
        assertEquals("sip:app-1@r.invalid;transport=ws", a.uri)
        assertEquals("abc", a.tag)
        assertEquals("", a.params["x"])
        val b = SipAddress.parse("sip:web-1@r.invalid;tag=t9")!!
        assertEquals("sip:web-1@r.invalid", b.uri)
        assertEquals("t9", b.tag)
        assertNull(SipAddress.parse("<mailto:x@y.invalid>"))
        assertEquals("web-1", SipText.userOf("sip:web-1@r.invalid;transport=ws"))
        assertNull(SipText.userOf("sip:13.205.250.72:5060"))
    }

    @Test fun readsSessionExpiresAndCSeq() {
        val se = SipSessionExpires.parse("90;refresher=uac")!!
        assertEquals(90, se.seconds)
        assertEquals("uac", se.refresher)
        assertNull(SipSessionExpires.parse("soon"))
        assertNull(SipCSeq.parse("12"))
        assertEquals("UPDATE", SipCSeq.parse("9691 UPDATE")!!.method)
    }

    @Test fun readsAChallengeWithQuotedCommasEscapesAndStale() {
        val c = SipChallenge.parse("Digest realm=\"a, b\", nonce=\"n\\\"1\", opaque=\"op\", qop=\"auth,auth-int\", stale=TRUE, algorithm=MD5")!!
        assertEquals("a, b", c.realm)
        assertEquals("n\"1", c.nonce)
        assertEquals("op", c.opaque)
        assertEquals(listOf("auth", "auth-int"), c.qop)
        assertTrue(c.stale)
        assertNull("not a digest", SipChallenge.parse("Basic realm=\"x\""))
        assertNull("no nonce", SipChallenge.parse("Digest realm=\"x\""))
        assertNotNull(SipChallenge.parse("digest realm=x, nonce=y"))
    }
}
