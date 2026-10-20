package ai.graine.inapp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * THE SIP USER AGENT AGAINST THE PRODUCTION TRACE (rtc-shared/TRACE.md) AND A
 * FAKE SBC: each message it writes is the browser's, byte for byte, but for
 * its random parts — REGISTER and its challenge, the INVITE, the ACK of the
 * 401 on the INVITE's branch, the INVITE with credentials, the ACK of the 200
 * to the 200's Contact with no Route, the UPDATE every 45 s, the BYE, the
 * un-REGISTER, the socket closed 1000 — and every turn of a call: their BYE,
 * ours, CANCEL before and racing an answer, their re-INVITE, requests it does
 * not support, and input that is not SIP. Then the same flow over a real
 * WebSocket on the loopback interface.
 */
class SipUserAgentTest {
    private val sched = ManualScheduler()
    private val socket = FakeSipSocket()
    private val sbc = FakeSbc()
    private val link = SbcLink(socket, sbc, sched)
    private val events = mutableListOf<String>()
    private val account = SipAccount(Trace.SERVER, "web-XXXX", "secret-pass", Trace.REALM)

    private val uaListener = object : SipUserAgent.Listener {
        override fun onRegistered(elapsedMs: Long) { events += "registered" }
        override fun onRegistrationFailed(failure: SipFailure) { events += "registration failed: $failure" }
        override fun onConnectionLost(error: String) { events += "lost" }
    }

    private fun agent(): SipUserAgent = SipUserAgent(account, sched, { url, protocols, l ->
        socket.url = url; socket.protocols = protocols; socket.listener = l; socket
    }, uaListener, random = FixedSipRandom())

    private val callEvents = object : SipCallListener {
        override fun onProvisional(code: Int) { events += "provisional $code" }
        override fun onChallenged(code: Int) { events += "challenged $code" }
        override fun onAnswered(sdp: String) { events += "answered" }
        override fun onFailed(failure: SipFailure) { events += "failed: $failure" }
        override fun onRemoteBye() { events += "their bye" }
        override fun onDialogEnded(failure: SipFailure) { events += "dialog ended: $failure" }
        override fun onOffer(sdp: String, answer: (String?) -> Unit) { events += "offer"; answer("v=0\r\ns=our-answer\r\n") }
    }

    private val sessionId = "0123456789abcdef0123456789abcdef"
    private val target = "sip:app-${Trace.APP_SID}@${Trace.REALM}"
    private val headers = listOf("X-Graine-Session-Id" to sessionId, "X-Graine-Agent-Id" to "agent-web-1", "X-Application-Sid" to Trace.APP_SID)

    /** Registered, with the SBC answering everything. */
    private fun registered(): SipUserAgent {
        val ua = agent()
        ua.start(); sched.drain()
        socket.open(); link.pump()
        assertEquals(listOf("registered"), events)
        events.clear()
        return ua
    }

    /** Registered and answered: the dialog up. */
    private fun answered(): Pair<SipUserAgent, SipUserAgent.Call> {
        val ua = registered()
        val call = ua.invite(target, headers, Trace.OFFER, callEvents)
        link.pump()
        assertEquals(listOf("provisional 100", "challenged 401", "provisional 100", "answered"), events)
        events.clear()
        return ua to call
    }

    // ── the trace's own messages, with the random parts made comparable ──

    private fun lines(vararg l: String) = l.joinToString("\r\n") + "\r\n\r\n"

    /** Both texts with every branch number, digest value and Content-Length made a placeholder, the trace's tokens made ours. */
    private fun shape(text: String, subs: Map<String, String> = emptyMap()): String {
        var t = text
        for ((from, to) in subs) t = t.replace(from, to)
        return t
            .replace(Regex("branch=z9hG4bK[0-9]+"), "branch=z9hG4bK#")
            .replace(Regex("(nonce|cnonce|response)=\"[^\"]*\""), "$1=\"#\"")
            .replace("User-Agent: jambonz-webrtc-0.1.4\r\n", "User-Agent: ${SipUserAgent.USER_AGENT}\r\n")
    }

    private fun head(text: String) = text.substringBefore("\r\n\r\n") + "\r\n\r\n"

    @Test fun registersExactlyAsTheTrace() {
        val ua = agent()
        ua.start(); sched.drain()
        assertEquals("the mint's server, subprotocol sip", Trace.SERVER, socket.url)
        assertEquals(listOf("sip"), socket.protocols)
        socket.open(); link.pump()
        val (first, authed) = socket.requests("REGISTER")
        val via = SipVia.parse(first.values("via")[0])!!.sentBy
        val contactUser = SipText.userOf(SipAddress.parse(first.header("contact")!!)!!.uri)!!
        assertTrue("Via host: 12 random characters .invalid", Regex("[0-9a-v]{12}\\.invalid").matches(via))
        assertTrue("Contact user: 8 random characters", Regex("[0-9a-v]{8}").matches(contactUser))
        val subs = mapOf("cdh6l13lka9h.invalid" to via, "e7q9tikf" to contactUser, "4kj2mue2i7" to first.fromTag!!, "5jl5ol93rs8bq7uj2ivsf1" to first.callId)
        val trace = lines(
            "REGISTER sip:grainewhatsup.sip.graine.ai SIP/2.0",
            "Via: SIP/2.0/WSS cdh6l13lka9h.invalid;branch=z9hG4bK7801448",
            "Max-Forwards: 69",
            "To: <sip:web-XXXX@grainewhatsup.sip.graine.ai>",
            "From: <sip:web-XXXX@grainewhatsup.sip.graine.ai>;tag=4kj2mue2i7",
            "Call-ID: 5jl5ol93rs8bq7uj2ivsf1",
            "CSeq: 1 REGISTER",
            "Expires: 300",
            "Contact: <sip:e7q9tikf@cdh6l13lka9h.invalid;transport=ws>;+sip.ice;reg-id=1;+sip.instance=\"<urn:uuid:c30c2118-f5c4-4dd6-898c-3d8485be8150>\";expires=300",
            "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,NOTIFY,SUBSCRIBE",
            "Supported: path,gruu,outbound",
            "User-Agent: jambonz-webrtc-0.1.4",
            "Content-Length: 0",
        )
        assertEquals(shape(trace, subs), shape(socket.sent[0]))
        val traceAuthed = lines(
            "REGISTER sip:grainewhatsup.sip.graine.ai SIP/2.0",
            "Via: SIP/2.0/WSS cdh6l13lka9h.invalid;branch=z9hG4bK7956117",
            "Max-Forwards: 69",
            "To: <sip:web-XXXX@grainewhatsup.sip.graine.ai>",
            "From: <sip:web-XXXX@grainewhatsup.sip.graine.ai>;tag=4kj2mue2i7",
            "Call-ID: 5jl5ol93rs8bq7uj2ivsf1",
            "CSeq: 2 REGISTER",
            "Authorization: Digest algorithm=MD5, username=\"web-XXXX\", realm=\"grainewhatsup.sip.graine.ai\", nonce=\"<redacted>\", uri=\"sip:grainewhatsup.sip.graine.ai\", response=\"<redacted>\", qop=auth, cnonce=\"<redacted>\", nc=00000001",
            "Expires: 300",
            "Contact: <sip:e7q9tikf@cdh6l13lka9h.invalid;transport=ws>;+sip.ice;reg-id=1;+sip.instance=\"<urn:uuid:c30c2118-f5c4-4dd6-898c-3d8485be8150>\";expires=300",
            "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,NOTIFY,SUBSCRIBE",
            "Supported: path,gruu,outbound",
            "User-Agent: jambonz-webrtc-0.1.4",
            "Content-Length: 0",
        )
        assertEquals(shape(traceAuthed, subs), shape(socket.sent.first { it.contains("CSeq: 2 REGISTER") }))
        assertNotEquals("a new branch for the retry", first.topViaBranch, authed.topViaBranch)
        assertEquals("the same From tag", first.fromTag, authed.fromTag)
        assertTrue("the digest was right", sbc.badDigests.isEmpty())
        assertEquals(listOf("registered"), events)
        assertTrue(ua.registered)
    }

    @Test fun invitesWith100Then401ThenTheSameOfferWithCredentialsThenAcksThe200AtItsContact() {
        val ua = registered()
        val sentBefore = socket.sent.size
        ua.invite(target, headers, Trace.OFFER, callEvents)
        link.pump()
        val sent = socket.sent.drop(sentBefore)
        assertEquals(listOf("INVITE", "ACK", "INVITE", "ACK"), sent.map { it.substringBefore(' ') })
        val invite1 = SipParser.parse(sent[0])!!
        val ack401 = SipParser.parse(sent[1])!!
        val invite2 = SipParser.parse(sent[2])!!
        val ack200 = SipParser.parse(sent[3])!!
        val via = SipVia.parse(invite1.values("via")[0])!!.sentBy
        val contactUser = SipText.userOf(SipAddress.parse(invite1.header("contact")!!)!!.uri)!!
        val subs = mapOf(
            "cdh6l13lka9h.invalid" to via, "e7q9tikf" to contactUser, "qdq9n426ik" to invite1.fromTag!!, "8bfb0ntka5aua320artv" to invite1.callId,
            "<session id: 32 hex>" to sessionId, "<webcall agent id>" to "agent-web-1", "Content-Length: 1864" to "Content-Length: ${Trace.OFFER.toByteArray().size}",
            "367" to invite1.cseq.number.toString(), "368" to (invite1.cseq.number + 1).toString(),
            "aSBtgypQaZ95a" to ack401.toTag!!, "cByBmmry4gpBj" to ack200.toTag!!,
        )
        val app = "sip:app-817361cd-2a13-4937-b465-686d3f58eddd@grainewhatsup.sip.graine.ai"
        assertEquals(shape(lines(
            "INVITE $app SIP/2.0",
            "Via: SIP/2.0/WSS cdh6l13lka9h.invalid;branch=z9hG4bK8311767",
            "Max-Forwards: 69",
            "To: <$app>",
            "From: <sip:web-XXXX@grainewhatsup.sip.graine.ai>;tag=qdq9n426ik",
            "Call-ID: 8bfb0ntka5aua320artv",
            "CSeq: 367 INVITE",
            "X-Graine-Session-Id: <session id: 32 hex>",
            "X-Graine-Agent-Id: <webcall agent id>",
            "X-Application-Sid: 817361cd-2a13-4937-b465-686d3f58eddd",
            "Contact: <sip:e7q9tikf@cdh6l13lka9h.invalid;transport=ws;ob>",
            "Content-Type: application/sdp",
            "Session-Expires: 90",
            "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,NOTIFY,SUBSCRIBE",
            "Supported: timer,ice,replaces,outbound",
            "User-Agent: jambonz-webrtc-0.1.4",
            "Content-Length: 1864",
        ), subs), shape(head(sent[0])))
        assertEquals("the offer as given", Trace.OFFER, invite1.body)
        assertEquals("the 401 is ACKed on the INVITE's branch", invite1.topViaBranch, ack401.topViaBranch)
        assertEquals(shape(lines(
            "ACK $app SIP/2.0",
            "Via: SIP/2.0/WSS cdh6l13lka9h.invalid;branch=z9hG4bK8311767",
            "Max-Forwards: 69",
            "To: <$app>;tag=aSBtgypQaZ95a",
            "From: <sip:web-XXXX@grainewhatsup.sip.graine.ai>;tag=qdq9n426ik",
            "Call-ID: 8bfb0ntka5aua320artv",
            "CSeq: 367 ACK",
            "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,NOTIFY,SUBSCRIBE",
            "Supported: outbound",
            "User-Agent: jambonz-webrtc-0.1.4",
            "Content-Length: 0",
        ), subs), shape(sent[1]))
        assertEquals(shape(lines(
            "INVITE $app SIP/2.0",
            "Via: SIP/2.0/WSS cdh6l13lka9h.invalid;branch=z9hG4bK7810508",
            "Max-Forwards: 69",
            "To: <$app>",
            "From: <sip:web-XXXX@grainewhatsup.sip.graine.ai>;tag=qdq9n426ik",
            "Call-ID: 8bfb0ntka5aua320artv",
            "CSeq: 368 INVITE",
            "Authorization: Digest algorithm=MD5, username=\"web-XXXX\", realm=\"grainewhatsup.sip.graine.ai\", nonce=\"<redacted>\", uri=\"$app\", response=\"<redacted>\", qop=auth, cnonce=\"<redacted>\", nc=00000001",
            "X-Graine-Session-Id: <session id: 32 hex>",
            "X-Graine-Agent-Id: <webcall agent id>",
            "X-Application-Sid: 817361cd-2a13-4937-b465-686d3f58eddd",
            "Contact: <sip:e7q9tikf@cdh6l13lka9h.invalid;transport=ws;ob>",
            "Content-Type: application/sdp",
            "Session-Expires: 90",
            "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,NOTIFY,SUBSCRIBE",
            "Supported: timer,ice,replaces,outbound",
            "User-Agent: jambonz-webrtc-0.1.4",
            "Content-Length: 1864",
        ), subs), shape(head(sent[2])))
        assertEquals("the SAME offer, byte for byte", invite1.body, invite2.body)
        assertNotEquals("a new branch", invite1.topViaBranch, invite2.topViaBranch)
        assertEquals("CSeq + 1", invite1.cseq.number + 1, invite2.cseq.number)
        assertEquals(shape(lines(
            "ACK sip:13.205.250.72:5060;transport=tcp SIP/2.0",
            "Via: SIP/2.0/WSS cdh6l13lka9h.invalid;branch=z9hG4bK9229009",
            "Max-Forwards: 69",
            "To: <$app>;tag=cByBmmry4gpBj",
            "From: <sip:web-XXXX@grainewhatsup.sip.graine.ai>;tag=qdq9n426ik",
            "Call-ID: 8bfb0ntka5aua320artv",
            "CSeq: 368 ACK",
            "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,NOTIFY,SUBSCRIBE",
            "Supported: outbound",
            "User-Agent: jambonz-webrtc-0.1.4",
            "Content-Length: 0",
        ), subs), shape(sent[3]))
        assertTrue("no Route anywhere: the 200 had no Record-Route", sent.none { it.contains("\r\nRoute:") })
        assertNotEquals("the 200's ACK is its own transaction", invite2.topViaBranch, ack200.topViaBranch)
        // The challenge is said, and the INVITE with credentials' own 100 Trying is reported: it is the one the answer follows.
        assertEquals(listOf("provisional 100", "challenged 401", "provisional 100", "answered"), events)
        assertTrue(sbc.badDigests.isEmpty())
    }

    @Test fun nameTheAgentTheMintSignedNothingElse() {
        val ua = registered()
        ua.invite(target, headers, Trace.OFFER, callEvents)
        link.pump()
        for (invite in socket.requests("INVITE")) {
            assertEquals("agent-web-1", invite.header("X-Graine-Agent-Id"))
            assertEquals(1, invite.headers.count { it.name == "x-graine-agent-id" })
            assertEquals(sessionId, invite.header("X-Graine-Session-Id"))
            assertEquals(Trace.APP_SID, invite.header("X-Application-Sid"))
            assertEquals(target, invite.requestUri)
        }
    }

    @Test fun refreshesTheSessionWithAnUpdateEvery45SecondsAsTheBrowserDoes() {
        val (_, _) = answered()
        val invite = socket.requests("INVITE").last()
        val before = socket.requests("UPDATE").size
        sched.advance(44_999); link.pump()
        assertEquals("not before 45 s", before, socket.requests("UPDATE").size)
        sched.advance(1); link.pump()
        val update = socket.requests("UPDATE").single()
        val via = SipVia.parse(update.values("via")[0])!!.sentBy
        val contactUser = SipText.userOf(SipAddress.parse(update.header("contact")!!)!!.uri)!!
        val app = "sip:app-${Trace.APP_SID}@${Trace.REALM}"
        // TRACE.md §2, call 2's refresh, in call 1's terms.
        assertEquals(shape(lines(
            "UPDATE sip:13.205.250.72:5060;transport=tcp SIP/2.0",
            "Via: SIP/2.0/WSS $via;branch=z9hG4bK2457755",
            "Max-Forwards: 69",
            "To: <$app>;tag=${socket.requests("ACK").last().toTag}",
            "From: <sip:web-XXXX@${Trace.REALM}>;tag=${invite.fromTag}",
            "Call-ID: ${invite.callId}",
            "CSeq: ${invite.cseq.number + 1} UPDATE",
            "Contact: <sip:$contactUser@$via;transport=ws;ob>",
            "Session-Expires: 90;refresher=uac",
            "Allow: INVITE,ACK,CANCEL,BYE,UPDATE,MESSAGE,OPTIONS,REFER,INFO,NOTIFY,SUBSCRIBE",
            "Supported: timer,ice,outbound",
            "User-Agent: jambonz-webrtc-0.1.4",
            "Content-Length: 0",
        )), shape(socket.sent.first { it.startsWith("UPDATE") }))
        // Its 200 arms the next, 45 s later, with the next CSeq.
        sched.advance(45_000); link.pump()
        val updates = socket.requests("UPDATE")
        assertEquals(2, updates.size)
        assertEquals(invite.cseq.number + 2, updates[1].cseq.number)
        assertTrue(events.isEmpty())
    }

    @Test fun aRefreshThatFindsNoDialogEndsTheCall() {
        val (_, _) = answered()
        sbc.challenge = false
        sched.advance(45_000); sched.drain()
        val update = socket.requests("UPDATE").single()
        socket.deliver(sbc.response(update, 481))
        sched.drain()
        assertEquals(listOf("dialog ended: Dialog Error 481"), events)
        assertEquals("ended with a BYE", "BYE", socket.messages().last().method)
    }

    @Test fun hangsUpWithByeThenUnregistersThenClosesTheSocket() {
        val (ua, _) = answered()
        val invite = socket.requests("INVITE").last()
        val mark = socket.sent.size
        ua.stop(); link.pump()
        val after = socket.sent.drop(mark).map { it.substringBefore("\r\n") }
        assertEquals(
            listOf("BYE sip:13.205.250.72:5060;transport=tcp SIP/2.0", "REGISTER sip:${Trace.REALM} SIP/2.0", "REGISTER sip:${Trace.REALM} SIP/2.0"),
            after,
        )
        val bye = socket.requests("BYE").single()
        assertEquals("CSeq: the INVITE's + 1", invite.cseq.number + 1, bye.cseq.number)
        assertNull("no Route", bye.header("route"))
        val unregister = socket.requests("REGISTER").filter { it.header("expires") == "0" }
        assertEquals("challenged, and answered once", 2, unregister.size)
        assertTrue(unregister[0].header("contact")!!.endsWith(";expires=0"))
        assertEquals("the registration's Call-ID", socket.requests("REGISTER").first().callId, unregister[0].callId)
        assertNotEquals("a new From tag", socket.requests("REGISTER").first().fromTag, unregister[0].fromTag)
        assertEquals(1000, socket.closed)
        assertTrue("no event after our own hang-up", events.isEmpty())
    }

    @Test fun answersTheirByeAndSaysSo() {
        val (_, _) = answered()
        val invite = socket.requests("INVITE").last()
        val ack = socket.requests("ACK").last()
        socket.deliver(sbc.inDialog("BYE", invite, ack, cseq = 1))
        sched.drain()
        val ok = socket.messages().last()
        assertEquals(200, ok.status)
        assertEquals("BYE", ok.cseq.method)
        assertEquals("z9hG4bKsbc1", ok.topViaBranch)
        assertEquals("their Via, as sent", "SIP/2.0/WSS ${Trace.SBC}:8443;branch=z9hG4bKsbc1", ok.values("via")[0])
        assertEquals(listOf("their bye"), events)
        // The same BYE again gets the same 200, and no second event.
        socket.deliver(sbc.inDialog("BYE", invite, ack, cseq = 1)); sched.drain()
        assertEquals(200, socket.messages().last().status)
        assertEquals(listOf("their bye"), events)
    }

    @Test fun cancelsOnceTheInviteWasHeardAndAcksThe487() {
        val ua = registered()
        sbc.inviteAnswer = FakeSbc.Answer.TRYING_ONLY
        val call = ua.invite(target, headers, Trace.OFFER, callEvents)
        link.pump()
        val invite = socket.requests("INVITE").last()
        call.hangUp(); link.pump()
        val cancel = socket.requests("CANCEL").single()
        assertEquals("the INVITE's branch", invite.topViaBranch, cancel.topViaBranch)
        assertEquals(invite.cseq.number, cancel.cseq.number)
        assertEquals(invite.requestUri, cancel.requestUri)
        assertNull("the INVITE's To, no tag", cancel.toTag)
        socket.deliver(sbc.response(invite, 487, tag = true)); sched.drain()
        val ack = socket.messages().last()
        assertEquals("ACK", ack.method)
        assertEquals(invite.topViaBranch, ack.topViaBranch)
        assertEquals(listOf("provisional 100", "challenged 401", "provisional 100"), events)
    }

    @Test fun aCancelledCallAnsweredAnywayIsAckedAndHungUp() {
        val ua = registered()
        sbc.inviteAnswer = FakeSbc.Answer.TRYING_ONLY
        val call = ua.invite(target, headers, Trace.OFFER, callEvents)
        link.pump()
        val invite = socket.requests("INVITE").last()
        call.hangUp(); sched.drain()
        // The 200 crosses the CANCEL.
        sbc.inviteAnswer = FakeSbc.Answer.OK
        socket.deliver(sbc.response(invite, 200, tag = true, body = Trace.ANSWER, extra = listOf("Contact: <sip:${Trace.SBC}:5060;transport=tcp>", "Content-Type: application/sdp")))
        sched.drain()
        val tail = socket.messages().takeLast(2)
        assertEquals(listOf("ACK", "BYE"), tail.map { it.method })
        assertEquals("sip:${Trace.SBC}:5060;transport=tcp", tail[1].requestUri)
        assertFalse("never reported answered", "answered" in events)
    }

    @Test fun aCallHungUpBeforeAnyReplyCancelsAtTheFirstOne() {
        val ua = registered()
        sbc.challenge = false
        sbc.inviteAnswer = FakeSbc.Answer.SILENT
        val call = ua.invite(target, headers, Trace.OFFER, callEvents)
        sched.drain()
        call.hangUp(); sched.drain()
        assertTrue("no CANCEL before a provisional", socket.requests("CANCEL").isEmpty())
        val invite = socket.requests("INVITE").last()
        socket.deliver(sbc.response(invite, 100)); sched.drain()
        assertEquals(1, socket.requests("CANCEL").size)
        assertTrue(events.isEmpty())
    }

    @Test fun aChallengeAfterOurCancelIsNotAnswered() {
        val ua = registered()
        sbc.inviteAnswer = FakeSbc.Answer.SILENT
        val call = ua.invite(target, headers, Trace.OFFER, callEvents)
        sched.drain()
        val invite = socket.requests("INVITE").single()
        socket.deliver(sbc.response(invite, 100)); sched.drain()
        call.hangUp(); sched.drain()
        socket.deliver(sbc.response(invite, 401, tag = true, extra = listOf("WWW-Authenticate: Digest realm=\"${Trace.REALM}\", qop=\"auth\", nonce=\"late\"")))
        sched.drain()
        assertEquals("no INVITE with credentials after the hang-up", 1, socket.requests("INVITE").size)
    }

    // ── a dial let go before its answer: CANCELled on a socket kept until the dial has ended ──

    @Test fun aStoppedDialKeepsItsSocketUntilItsInviteHasItsFinalAnswer() {
        val ua = registered()
        sbc.inviteAnswer = FakeSbc.Answer.TRYING_ONLY
        ua.invite(target, headers, Trace.OFFER, callEvents)
        link.pump()
        val invite = socket.requests("INVITE").last()
        ua.stop(); link.pump()
        assertEquals("the INVITE with credentials is the one cancelled", invite.topViaBranch, socket.requests("CANCEL").single().topViaBranch)
        assertEquals("the registration went at once", "0", socket.requests("REGISTER").last().header("expires"))
        sched.advance(SipUserAgent.CLOSE_AFTER_MS + 500); link.pump()
        assertNull("the socket the dial's ending travels on went before it", socket.closed)
        socket.deliver(sbc.response(invite, 487, tag = true)); sched.drain()
        assertEquals("the 487 is ACKed", "ACK", socket.messages().last().method)
        assertEquals("then the socket goes", 1000, socket.closed)
    }

    @Test fun aStoppedDialAnsweredAcrossItsCancelIsAckedAndEndedBeforeTheSocketGoes() {
        val ua = registered()
        sbc.inviteAnswer = FakeSbc.Answer.TRYING_ONLY
        ua.invite(target, headers, Trace.OFFER, callEvents)
        link.pump()
        val invite = socket.requests("INVITE").last()
        ua.stop(); link.pump()
        assertEquals(1, socket.requests("CANCEL").size)
        // The answer crosses the CANCEL: the server's leg exists now, and only an ACK and a BYE on this socket end it.
        socket.deliver(sbc.answerTo(invite)); sched.drain()
        val tail = socket.messages().filter { it.isRequest }.takeLast(2)
        assertEquals(listOf("ACK", "BYE"), tail.map { it.method })
        assertEquals("to the answer's Contact", "sip:${Trace.SBC}:5060;transport=tcp", tail[1].requestUri)
        assertNull("the socket went before the BYE was answered", socket.closed)
        link.pump()
        assertEquals("the BYE answered, the socket goes", 1000, socket.closed)
        assertFalse("never reported answered", "answered" in events)
    }

    @Test fun aStoppedDialThatNeverHearsBackLetsItsSocketGoAfterTheGrace() {
        val ua = registered()
        sbc.inviteAnswer = FakeSbc.Answer.TRYING_ONLY
        ua.invite(target, headers, Trace.OFFER, callEvents)
        link.pump()
        ua.stop(); link.pump()
        sched.advance(RtcDial.ABANDONED_DIAL_GRACE_MS - 100); link.pump()
        assertNull(socket.closed)
        sched.advance(100); link.pump()
        assertEquals("a dial with no final answer held its socket for ever", 1000, socket.closed)
    }

    @Test fun aStoppedDialHeardOnlyAfterTheStopIsCancelledAtItsFirstReply() {
        val ua = registered()
        sbc.challenge = false
        sbc.inviteAnswer = FakeSbc.Answer.SILENT
        ua.invite(target, headers, Trace.OFFER, callEvents)
        sched.drain()
        val invite = socket.requests("INVITE").single()
        ua.stop(); link.pump()
        assertTrue("no CANCEL before a provisional", socket.requests("CANCEL").isEmpty())
        sched.advance(SipUserAgent.CLOSE_AFTER_MS + 500); link.pump()
        assertNull("the socket went before the INVITE could be cancelled", socket.closed)
        socket.deliver(sbc.response(invite, 100)); sched.drain()
        assertEquals("cancelled at its first reply", invite.topViaBranch, socket.requests("CANCEL").single().topViaBranch)
        assertTrue("no event after a stop", events.isEmpty())
    }

    @Test fun answersAReinviteWithTheMediasAnswerAndAnUpdateWithoutOneWith200() {
        val (_, _) = answered()
        val invite = socket.requests("INVITE").last()
        val ack = socket.requests("ACK").last()
        socket.deliver(sbc.inDialog("INVITE", invite, ack, cseq = 2, body = Trace.ANSWER, extra = listOf("Session-Expires: 120;refresher=uas")))
        sched.drain()
        val ok = socket.messages().last()
        assertEquals(200, ok.status)
        assertEquals("INVITE", ok.cseq.method)
        assertEquals("v=0\r\ns=our-answer\r\n", ok.body)
        assertEquals("application/sdp", ok.contentType)
        assertEquals("Session-Expires: 120;refresher=uas".substringAfter(": "), ok.header("session-expires"))
        assertTrue(ok.header("contact")!!.endsWith(";transport=ws;ob>"))
        assertEquals(listOf("offer"), events)
        socket.deliver(sbc.inDialog("UPDATE", invite, ack, cseq = 3)); sched.drain()
        val ok2 = socket.messages().last()
        assertEquals(200, ok2.status)
        assertEquals("", ok2.body)
        assertEquals("90;refresher=uas", ok2.header("session-expires"))
    }

    @Test fun answersWhatItDoesNotUseWithoutEndingTheCall() {
        val (_, _) = answered()
        val invite = socket.requests("INVITE").last()
        val ack = socket.requests("ACK").last()
        var cseq = 10
        for ((method, code) in listOf("OPTIONS" to 200, "INFO" to 200, "NOTIFY" to 200, "MESSAGE" to 501, "REFER" to 501)) {
            socket.deliver(sbc.inDialog(method, invite, ack, cseq = cseq++)); sched.drain()
            assertEquals(method, code, socket.messages().last().status)
        }
        // Outside the dialog: an INVITE is not taken (405, with Allow); a BYE for nothing is 481; another user's is 404.
        val stray = sbc.inDialog("INVITE", invite, ack, cseq = 1).replace(";tag=${invite.fromTag}", "").replace(invite.callId, "other-call")
        socket.deliver(stray); sched.drain()
        assertEquals(405, socket.messages().last().status)
        assertEquals(SipUserAgent.ALLOW, socket.messages().last().header("allow"))
        socket.deliver(sbc.inDialog("BYE", invite, ack, cseq = 1).replace(invite.callId, "nobody")); sched.drain()
        assertEquals(481, socket.messages().last().status)
        val elsewhere = sbc.inDialog("OPTIONS", invite, ack, cseq = 30).replaceFirst(Regex("OPTIONS sip:[^@]+@"), "OPTIONS sip:someone-else@")
        socket.deliver(elsewhere); sched.drain()
        assertEquals(404, socket.messages().last().status)
        assertTrue("the call goes on", events.isEmpty())
    }

    @Test fun keepAlivesAndInputThatIsNotSipChangeNothing() {
        val ua = agent()
        ua.start(); sched.drain()
        socket.open(); sched.drain()
        val sent = socket.sent.size
        socket.deliver("\r\n\r\n"); sched.drain()
        assertEquals("a double CRLF is answered with one", "\r\n", socket.sent[sent])
        socket.deliver("\r\n"); sched.drain()
        for (junk in listOf("garbage", "SIP/2.0 200 OK\r\n\r\n", "\u0000".repeat(70_000), "INVITE sip:x SIP/2.0\r\nVia: nope\r\n\r\n")) {
            socket.deliver(junk); sched.drain()
        }
        // A response for a transaction we never had.
        socket.deliver(sbc.response(SipParser.parse(socket.sent[0])!!, 200).replace("z9hG4bK", "z9hG4bKother")); sched.drain()
        assertTrue(events.isEmpty())
        link.pump()
        assertEquals("and it still registers", listOf("registered"), events)
    }

    @Test fun aRefusedRegistrationAndARefusedCredentialAreFailures() {
        val ua = agent()
        sbc.registerAnswer = 403
        ua.start(); sched.drain()
        socket.open(); link.pump()
        assertEquals(listOf("registration failed: Rejected 403"), events)
        // A second 401, to the request with credentials: the credential was refused.
        val socket2 = FakeSipSocket()
        val sbc2 = FakeSbc().apply { refuseCredentials = true }
        val link2 = SbcLink(socket2, sbc2, sched)
        val ua2 = SipUserAgent(account, sched, { _, _, l -> socket2.listener = l; socket2 }, uaListener, random = FixedSipRandom())
        events.clear()
        ua2.start(); sched.drain(); socket2.open(); link2.pump()
        assertEquals(listOf("registration failed: Authentication Error 401"), events)
        assertEquals("answered once, not twice", 2, socket2.requests("REGISTER").size)
    }

    @Test fun registersAgainAtTheServersMinimumInterval() {
        val ua = agent()
        ua.start(); sched.drain(); socket.open(); sched.drain()
        val first = socket.requests("REGISTER").single()
        socket.deliver(sbc.response(first, 423, extra = listOf("Min-Expires: 600"), tag = true)); link.pump()
        val again = socket.requests("REGISTER")[1]
        assertEquals("600", again.header("expires"))
        assertTrue(again.header("contact")!!.endsWith(";expires=600"))
        assertEquals(listOf("registered"), events)
    }

    @Test fun answersAProxyChallengeWithProxyAuthorization() {
        val ua = registered()
        sbc.challenge = false
        val call = ua.invite(target, headers, Trace.OFFER, callEvents)
        sched.drain()
        val invite = socket.requests("INVITE").single()
        socket.deliver(sbc.response(invite, 407, tag = true, extra = listOf("Proxy-Authenticate: Digest realm=\"${Trace.REALM}\", qop=\"auth\", nonce=\"p1\"")))
        sched.drain()
        val retry = socket.requests("INVITE")[1]
        assertTrue(retry.header("proxy-authorization")!!.startsWith("Digest algorithm=MD5, username=\"web-XXXX\""))
        assertNull(retry.header("authorization"))
        assertEquals("ACK of the 407 first", "ACK", socket.messages()[socket.messages().size - 2].method)
        call.hangUp()
    }

    @Test fun aDroppedSocketMidCallIsReopenedAndRegisteredAgain() {
        val (ua, _) = answered()
        ua.keepConnected = true
        val registers = socket.requests("REGISTER").size
        socket.drop(); sched.drain()
        assertEquals(listOf("lost"), events)
        socket.closed = null
        sched.advance(30_000)
        socket.open(); link.pump()
        assertTrue("registered again on the new socket", socket.requests("REGISTER").size > registers)
    }

    @Test fun probeSaysAliveOnAnyAnswerAndDeadOnSilence() {
        val ua = registered()
        val results = mutableListOf<Boolean>()
        ua.probe(2_000) { results += it }; link.pump()
        assertEquals(listOf(true), results)
        sbc.challenge = false
        sbc.registerAnswer = 200
        val silent = SbcLink(socket, FakeSbc().apply { deaf = true }, sched)
        ua.probe(2_000) { results += it }; silent.pump()
        sched.advance(2_000)
        assertEquals(listOf(true, false), results)
    }

    /** Over a real WebSocket on the loopback interface: the whole call, the SBC played by the test. */
    @Test fun placesACallOverARealWebSocket() {
        LocalWsServer().use { server ->
            val sched = ExecutorScheduler("test-sip")
            val got = LinkedBlockingQueue<String>()
            val acct = SipAccount(server.url, "web-XXXX", "secret-pass", Trace.REALM)
            val sbcReal = FakeSbc()
            val ua = SipUserAgent(acct, sched, { url, protocols, l -> WebSocketClient(url, protocols, l).connect() }, object : SipUserAgent.Listener {
                override fun onRegistered(elapsedMs: Long) { got.put("registered") }
                override fun onRegistrationFailed(failure: SipFailure) { got.put("failed $failure") }
            }, random = FixedSipRandom())
            ua.start()
            server.accepted.poll(5, TimeUnit.SECONDS)
            val reg = server.next("REGISTER")
            assertTrue("Via over plain ws says WS", reg.values("via")[0].startsWith("SIP/2.0/WS "))
            sbcReal.respond(reg).forEach { server.send(it) }
            val reg2 = server.next("REGISTER")
            sbcReal.respond(reg2).forEach { server.send(it) }
            assertEquals("registered", got.poll(5, TimeUnit.SECONDS))

            val callGot = LinkedBlockingQueue<String>()
            val call = ua.invite(target, headers, Trace.OFFER, object : SipCallListener {
                override fun onProvisional(code: Int) { callGot.put("provisional $code") }
                override fun onChallenged(code: Int) { callGot.put("challenged $code") }
                override fun onAnswered(sdp: String) { callGot.put("answered") }
                override fun onRemoteBye() { callGot.put("their bye") }
            })
            val inv = server.next("INVITE")
            sbcReal.respond(inv).forEach { server.send(it) }          // 100, then 401
            val ack401 = server.next("ACK")
            assertEquals(inv.topViaBranch, ack401.topViaBranch)
            val inv2 = server.next("INVITE")
            assertTrue(inv2.header("authorization") != null)
            sbcReal.respond(inv2).forEach { server.send(it) }         // 100, then 200
            val ack = server.next("ACK")
            assertEquals("sip:${Trace.SBC}:5060;transport=tcp", ack.requestUri)
            assertEquals("provisional 100", callGot.poll(5, TimeUnit.SECONDS))
            assertEquals("challenged 401", callGot.poll(5, TimeUnit.SECONDS))
            assertEquals("provisional 100", callGot.poll(5, TimeUnit.SECONDS))
            assertEquals("answered", callGot.poll(5, TimeUnit.SECONDS))
            server.send(sbcReal.inDialog("BYE", inv2, ack, cseq = 1))
            val ok = server.next("SIP/2.0 200")
            assertEquals("BYE", ok.cseq.method)
            assertEquals("their bye", callGot.poll(5, TimeUnit.SECONDS))
            assertTrue(sbcReal.badDigests.isEmpty())
            ua.stop()
            server.next("REGISTER")                                    // the un-REGISTER
            call.hangUp()
        }
    }

    @After fun noBadDigests() { assertTrue(sbc.badDigests.isEmpty()) }
}
