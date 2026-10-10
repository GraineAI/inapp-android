package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A clock and a thread the test turns by hand: "400 ms later" is exact and instant. */
internal class ManualScheduler : CallScheduler {
    private class Timer(val due: Long, val seq: Long, val task: () -> Unit) { var cancelled = false }

    var t = 0L
        private set
    private var seq = 0L
    private val posted = ArrayDeque<() -> Unit>()
    private val timers = mutableListOf<Timer>()

    override fun now(): Long = t
    override fun post(task: () -> Unit) { posted.addLast(task) }
    override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
        val timer = Timer(t + Math.max(0, delayMs), seq++, task)
        timers += timer
        return { timer.cancelled = true }
    }

    fun drain() { while (posted.isNotEmpty()) posted.removeFirst()() }

    fun advance(ms: Long) {
        val until = t + ms
        drain()
        while (true) {
            val next = timers.filter { !it.cancelled && it.due <= until }.minWithOrNull(compareBy({ it.due }, { it.seq })) ?: break
            timers.remove(next)
            t = next.due
            next.task()
            drain()
        }
        t = until
    }
}

/**
 * ONE NATIVE CALL ON THE VOICE SOCKET (sdk/NATIVE-CALLS.md "The wire
 * protocol"), with the socket, the audio and the network faked: the ticket or
 * the direct URL, the frames on open, marks acked at playout, speech that
 * follows the audio (not the markers), captions joined as said, barge-in once
 * per turn, the echo guard, actions answered on every path, mute, and every
 * way a call ends.
 */
class CallSessionTest {
    private val sched = ManualScheduler()

    private class FakeAudio : CallAudioIO {
        var onFrame: ((ShortArray) -> Unit)? = null
        val played = mutableListOf<Pair<ShortArray, Int>>()
        var buffered = 0.0
        var clears = 0
        var stopped = 0
        var failCapture = false
        override fun startCapture(onFrame: (ShortArray) -> Unit) {
            if (failCapture) throw IllegalStateException("no microphone")
            this.onFrame = onFrame
        }
        override fun play(pcm: ShortArray, sampleRate: Int) { played += pcm to sampleRate }
        override fun clear() { clears++ }
        override fun bufferedSeconds(): Double = buffered
        override fun stop() { stopped++ }
    }

    private class FakeSocket : CallSocket {
        val sent = mutableListOf<String>()
        var closed: Int? = null
        override fun send(text: String): Boolean { if (closed != null) return false; sent += text; return true }
        override fun close(code: Int, reason: String) { if (closed == null) closed = code }
        fun types(): List<String> = sent.map { JSONObject(it).optString("type") }
    }

    private class Events : CallSessionEvents {
        val log = mutableListOf<String>()
        val captions = mutableListOf<Triple<Role, String, Boolean>>()
        val speaking = mutableListOf<Boolean>()
        override fun onOpen() { log += "open" }
        override fun onFailed(error: String) { log += "failed:$error" }
        override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { log += "ended:${reason.wire}:${error ?: ""}" }
        override fun onCaption(role: Role, text: String, live: Boolean) { captions += Triple(role, text, live) }
        override fun onAgentSpeaking(speaking: Boolean) { this.speaking += speaking }
    }

    private val audio = FakeAudio()
    private val events = Events()
    private val socket = FakeSocket()
    private var listener: CallSocketListener? = null
    private var openedUrl: String? = null
    private var openedProtocols: List<String>? = null
    private val requests = mutableListOf<Pair<String, String>>()
    private var ticketReply: () -> HttpReply = { HttpReply(200, """{"ticket":"tkt_1","wsUrl":"wss://ws.graine.ai/v1/s"}""") }
    private var runner: (String, JSONObject, (ActionResult) -> Unit) -> Unit = { _, _, answer -> answer(ActionResult.ok()) }
    private var screen: JSONObject? = JSONObject().put("context", JSONObject().put("screen", "kyc")).put("seq", 1)

    private fun session(call: Map<String, String> = mapOf("who_called" to "agent", "call_reason" to "payment_due")): VoiceCallSession {
        val deps = CallSessionDeps(
            scheduler = sched,
            audio = audio,
            http = HttpPost { url, json -> requests += url to json; ticketReply() },
            openSocket = { url, protocols, l -> openedUrl = url; openedProtocols = protocols; listener = l; socket },
            runAction = { name, args, answer -> runner(name, args, answer) },
            contextFrame = { screen },
            io = { it() },
        )
        val config = CallSessionConfig(
            baseUrl = "https://www.graine.ai",
            publishableKey = "pk_test_key",
            agentId = "agent_1",
            webcallAgentId = "web_1",
            wsHost = "wss://ws.graine.ai",
            callVariables = call,
            sdkVariables = mapOf("name" to "Asha", "who_called" to "should-lose"),
        )
        return VoiceCallSession(config, deps, events)
    }

    private fun open(s: VoiceCallSession = session()): VoiceCallSession {
        s.start(); sched.drain()
        listener!!.onOpen("graine.embed.v1"); sched.drain()
        return s
    }

    private fun frame(o: JSONObject) { listener!!.onText(o.toString()); sched.drain() }
    private fun text(data: String) = frame(JSONObject().put("type", "text").put("data", data))
    private fun mark(name: String) = frame(JSONObject().put("type", "mark").put("name", name))

    /** 200 ms of 24 kHz PCM speech, headerless — what production sends. */
    private val speech: String = B64.encode(AgentAudio.toLittleEndian(ShortArray(4800) { i -> (9000 * Math.sin(2 * Math.PI * 140 * i / 24000.0)).toInt().toShort() }))
    private fun agentAudio(data: String = speech) = frame(JSONObject().put("type", "audio").put("data", data))

    private fun mic(amp: Double) {
        audio.onFrame!!(ShortArray(320) { n -> Math.round(amp * Math.sin((2 * Math.PI * 220 * n) / 16000)).toInt().toShort() })
        sched.drain()
    }

    // ── connecting ──────────────────────────────────────────────────────────

    @Test fun theTicketRidesTheSubprotocolAndTheVariablesRideTheUrl() {
        session().start(); sched.drain()
        val (url, body) = requests.single()
        assertEquals("https://www.graine.ai/api/embed/ticket", url)
        val b = JSONObject(body)
        assertEquals("pk_test_key", b.getString("publishableKey"))
        assertEquals("voice", b.getString("mode"))
        val meta = b.getJSONObject("metadata")
        assertEquals("agent", meta.getString("who_called"))
        assertEquals("payment_due", meta.getString("call_reason"))
        assertEquals("Asha", meta.getString("name"))
        assertEquals(listOf("graine.embed.v1", "tkt_1"), openedProtocols)
        val token = CallVariables.contextToken(mapOf("who_called" to "agent", "call_reason" to "payment_due", "name" to "Asha"))!!
        assertEquals("wss://ws.graine.ai/v1/s?context_token=" + CallUrls.encodeURIComponent(token), openedUrl)
        assertFalse("the ticket never rides the query", openedUrl!!.contains("tkt_1"))
    }

    @Test fun withoutATicketTheDirectSocketIsUsed() {
        ticketReply = { HttpReply(503, "") }
        session().start(); sched.drain()
        assertTrue(openedUrl!!.startsWith("wss://ws.graine.ai/chat/v1/web_1?webcall=1&context_token="))
        assertEquals(emptyList<String>(), openedProtocols)
    }

    @Test fun aTicketRequestThatFailsIsTheDirectSocketToo() {
        ticketReply = { throw java.io.IOException("offline") }
        session().start(); sched.drain()
        assertTrue(openedUrl!!.startsWith("wss://ws.graine.ai/chat/v1/web_1?webcall=1"))
        ticketReply = { HttpReply(200, """{"ticket":""}""") }
        openedUrl = null
        session().start(); sched.drain()
        assertTrue("a ticket without a URL is no ticket", openedUrl!!.startsWith("wss://ws.graine.ai/chat/v1/web_1?webcall=1"))
    }

    @Test fun onOpenTheInitThenTheScreenThenAPingEvery25Seconds() {
        open()
        assertEquals(listOf("open"), events.log)
        assertEquals(listOf("init", "app_context"), socket.types())
        val init = JSONObject(socket.sent[0])
        assertEquals("start", init.getString("event"))
        val meta = init.getJSONObject("meta_data")
        assertEquals("graine-android-sdk", meta.getString("client"))
        assertEquals("agent", meta.getJSONObject("context_data").getString("who_called"))
        assertEquals("Asha", meta.getJSONObject("context_data").getString("name"))
        assertEquals("kyc", JSONObject(socket.sent[1]).getJSONObject("context").getString("screen"))
        assertNotNull("the microphone is live", audio.onFrame)
        sched.advance(24_999)
        assertEquals(2, socket.sent.size)
        sched.advance(1)
        assertEquals("ping", socket.types().last())
        sched.advance(25_000)
        assertEquals(2, socket.types().count { it == "ping" })
    }

    @Test fun noScreenNoAppContext() {
        screen = null
        open()
        assertEquals(listOf("init"), socket.types())
    }

    @Test fun aNewerScreenDuringTheCallIsSent() {
        val s = session()
        s.sendContext(JSONObject().put("context", JSONObject().put("screen", "early"))); sched.drain()
        open(s)
        s.sendContext(JSONObject().put("context", JSONObject().put("screen", "checkout")).put("seq", 2)); sched.drain()
        assertEquals("checkout", JSONObject(socket.sent.last()).getJSONObject("context").getString("screen"))
        assertTrue("nothing went out before the socket was open", socket.sent.none { it.contains("early") })
        assertEquals(1, socket.sent.count { it.contains("checkout") })
    }

    // ── the agent's audio, its marks, its speech ───────────────────────────

    @Test fun agentAudioIsDecodedLatchedAndPlayedAt24kHz() {
        open()
        agentAudio()
        val (pcm, rate) = audio.played.single()
        assertEquals(24_000, rate)
        assertEquals(4800, pcm.size)
        // Latched: a near-silent frame afterwards is still PCM at 24 kHz, not re-guessed.
        agentAudio(B64.encode(ByteArray(800) { if (it % 2 == 0) 3 else 0 }))
        assertEquals(24_000, audio.played.last().second)
        assertEquals(400, audio.played.last().first.size)
    }

    @Test fun aDigitalSilenceLeadInPlaysAsSilenceNotAsAFullScaleStep() {
        open()
        agentAudio(B64.encode(ByteArray(9600)))
        val (pcm, rate) = audio.played.single()
        assertEquals("200 ms at 24 kHz, as PCM reads it", 4800, pcm.size)
        assertEquals(24_000, rate)
        assertTrue(pcm.all { it.toInt() == 0 })
        agentAudio()
        assertEquals("the speech after it still latches PCM", 24_000, audio.played.last().second)
    }

    @Test fun aMarkIsAckedWhenTheAudioBeforeItHasBeenHeard() {
        open()
        agentAudio()
        audio.buffered = 0.4
        mark("m1")
        assertFalse("never on arrival", socket.sent.any { it.contains("m1") })
        sched.advance(399)
        assertFalse(socket.sent.any { it.contains("m1") })
        sched.advance(1)
        val ack = JSONObject(socket.sent.last())
        assertEquals("mark", ack.getString("type"))
        assertEquals("m1", ack.getString("name"))
        audio.buffered = 0.0
        mark("m2")
        assertEquals("nothing queued: acked now", "m2", JSONObject(socket.sent.last()).getString("name"))
        audio.buffered = 45.0
        mark("m3")
        sched.advance(9_999)
        assertFalse(socket.sent.any { it.contains("m3") })
        sched.advance(1)
        assertEquals("capped at 10 s", "m3", JSONObject(socket.sent.last()).getString("name"))
    }

    /**
     * The production stream, as measured: phrases, then "<end_of_stream>" when
     * the TEXT is done, while the audio keeps coming. Speaking follows the
     * audio; the caption line closes at the marker; the phrases are spaced.
     */
    @Test fun speechFollowsTheAudioNotTheEndOfStreamMarker() {
        open()
        text("Welcome to Tring ")
        assertEquals("words alone are not the agent speaking", emptyList<Boolean>(), events.speaking)
        assertEquals(Triple(Role.AGENT, "Welcome to Tring ", true), events.captions.last())
        agentAudio()
        assertEquals(listOf(true), events.speaking)
        text("today")
        assertEquals(Triple(Role.AGENT, "Welcome to Tring today", true), events.captions.last())
        text("<end_of_stream>")
        assertEquals("the caption line closes at the marker", Triple(Role.AGENT, "Welcome to Tring today", false), events.captions.last())
        assertEquals("the marker did not end speech", listOf(true), events.speaking)

        audio.buffered = 0.6
        agentAudio()                    // audio keeps coming after the marker
        sched.advance(400)
        assertEquals("still being heard", listOf(true), events.speaking)
        audio.buffered = 0.3
        sched.advance(250)
        assertEquals(listOf(true), events.speaking)
        audio.buffered = 0.0
        sched.advance(1_000)
        assertEquals("drained and quiet for 300 ms: done", listOf(true, false), events.speaking)

        // The next turn starts its caption fresh, joined from its own first phrase.
        text("Sure! I can"); text("help you"); text("settings,"); text("like"); text("<end_of_stream>")
        assertEquals(Triple(Role.AGENT, "Sure! I can help you settings, like", false), events.captions.last())
    }

    @Test fun speakingNeedsBothTheTailAndADrainedPlayer() {
        open()
        audio.buffered = 0.0
        agentAudio()
        sched.advance(299)
        assertEquals(listOf(true), events.speaking)
        sched.advance(1)
        assertEquals("300 ms after the last audio with nothing queued", listOf(true, false), events.speaking)
    }

    @Test fun bargeInIsSentOncePerTurnImmediatelyBeforeTheFirstReleasedFrame() {
        open()
        audio.buffered = 0.5
        agentAudio()
        socket.sent.clear()
        repeat(12) { mic(1500.0) }      // the agent's echo: held
        assertEquals(emptyList<String>(), socket.types())
        repeat(12) { mic(9000.0) }      // the customer talks over the agent
        val types = socket.types()
        assertEquals("barge_in", types.first())
        assertEquals("the held pre-roll and the frame that crossed", 13, types.count { it == "audio" })
        assertEquals(1, types.count { it == "barge_in" })
        mic(9000.0); mic(9000.0)
        assertEquals("once per turn", 1, socket.types().count { it == "barge_in" })

        // The agent stops; the next frames go up plainly; the next turn can be barged into again.
        audio.buffered = 0.0
        sched.advance(1_600)
        assertEquals(listOf(true, false), events.speaking)
        socket.sent.clear()
        // The room while nobody speaks: heard plainly, and what the guard learns the room sounds like.
        // (A loud frame here would be learned as the room — the TypeScript guard's own rule.)
        mic(120.0)
        assertEquals(listOf("audio"), socket.types())
        audio.buffered = 0.5
        agentAudio()
        socket.sent.clear()
        repeat(12) { mic(1500.0) }
        repeat(12) { mic(9000.0) }
        assertEquals("barge_in", socket.types().first())
    }

    @Test fun theCustomerIsHeardWhenTheAgentIsSilent() {
        open()
        socket.sent.clear()
        mic(120.0); mic(9000.0)
        assertEquals(listOf("audio", "audio"), socket.types())
        val sent = JSONObject(socket.sent[1])
        val meta = sent.getJSONObject("meta_info")
        assertEquals("pcm", meta.getString("format"))
        assertEquals(16_000, meta.getInt("sample_rate"))
        assertEquals("base64", meta.getString("encoding"))
        assertEquals("20 ms of 16 kHz PCM16", 640, B64.decode(sent.getString("data")).size)
    }

    @Test fun theEchoGuardFailsOpen1_5SecondsPastTheProjectedEndWhateverAStalledPlayerClaims() {
        open()
        audio.buffered = 5.0            // a player stuck reporting audio, forever
        agentAudio()                    // t = 0: due to have been heard by 5.0 s
        socket.sent.clear()
        repeat(5) { mic(1500.0) }
        assertEquals("echo while the agent is audible: held", emptyList<String>(), socket.types())
        sched.advance(6_500)
        mic(1500.0)
        assertEquals("1.5 s past the projected end: still held", emptyList<String>(), socket.types())
        sched.advance(1)
        mic(1500.0)
        assertTrue("past it, the customer is heard whatever the stalled player says", socket.types().contains("audio"))
    }

    /**
     * Production sends a reply far faster than real time: all of it can
     * arrive in under a second and play for several more. The agent is
     * audible until what was queued has been heard — measured from the
     * projected end of playout, not from the last arrival — so its echo is
     * held and no barge_in goes out over its own voice.
     */
    @Test fun aReplyThatArrivesInABurstStaysAudibleUntilItHasBeenHeard() {
        open()
        audio.buffered = 4.0
        agentAudio()                    // 4 s of reply, queued at once at t = 0
        socket.sent.clear()
        sched.advance(1_600)
        audio.buffered = 2.4            // 1.6 s after it arrived, 2.4 s still to play
        repeat(12) { mic(1500.0) }      // its echo
        assertEquals("held, and no barge_in over the agent's own voice", emptyList<String>(), socket.types())
        assertEquals(listOf(true), events.speaking)

        // Heard: nothing buffered, 1.5 s past when it was due to end (4.0 s).
        audio.buffered = 0.0
        sched.advance(5_501 - 1_600)
        assertEquals(listOf(true, false), events.speaking)
        mic(1500.0)
        assertEquals("the customer is heard again, plainly", listOf("audio"), socket.types())
    }

    @Test fun clearDropsTheQueueEndsSpeechAndClosesTheCaption() {
        open()
        audio.buffered = 2.0
        agentAudio()
        text("Let me tell you about")
        frame(JSONObject().put("type", "interruption"))
        assertEquals(1, audio.clears)
        assertEquals(listOf(true, false), events.speaking)
        assertEquals(Triple(Role.AGENT, "Let me tell you about", false), events.captions.last())
        text("Sure")
        assertEquals("a fresh line, no leading space", Triple(Role.AGENT, "Sure", true), events.captions.last())
        frame(JSONObject().put("type", "clear"))
        assertEquals(2, audio.clears)
    }

    @Test fun speechStartAndEndFramesAreHandledButNeverDecideSpeaking() {
        open()
        frame(JSONObject().put("type", "beginning_of_stream"))
        assertEquals(emptyList<Boolean>(), events.speaking)
        text("Hello")
        frame(JSONObject().put("type", "end_of_stream"))
        assertEquals(Triple(Role.AGENT, "Hello", false), events.captions.last())
        assertEquals(emptyList<Boolean>(), events.speaking)
    }

    @Test fun theCustomersWordsAreCaptionedLiveThenFinal() {
        open()
        frame(JSONObject().put("type", "interim_transcript_received").put("data", "what can"))
        frame(JSONObject().put("type", "transcript").put("data", "what can you do"))
        assertEquals(listOf(Triple(Role.CUSTOMER, "what can", true), Triple(Role.CUSTOMER, "what can you do", false)), events.captions)
    }

    @Test fun widgetsPongsAcksAndStrangersAreIgnored() {
        open()
        val before = socket.sent.size
        for (t in listOf("widget", "widget_pending", "pong", "ack", "something_new")) frame(JSONObject().put("type", t))
        listener!!.onText("not json"); sched.drain()
        assertEquals(before, socket.sent.size)
        assertEquals(listOf("open"), events.log)
    }

    // ── actions ─────────────────────────────────────────────────────────────

    private fun action(id: String, name: String) =
        frame(JSONObject().put("type", "app_action").put("action", JSONObject().put("action_id", id).put("name", name).put("arguments", JSONObject().put("path", "/kyc"))))

    private fun results(): List<JSONObject> = socket.sent.map { JSONObject(it) }.filter { it.getString("type") == "app_action_result" }

    @Test fun anActionIsAnsweredOnEveryPathAndOnlyOnce() {
        val late = mutableListOf<(ActionResult) -> Unit>()
        var argsSeen: JSONObject? = null
        runner = { name, args, answer ->
            when (name) {
                "open_screen" -> { argsSeen = args; answer(ActionResult.ok("Opened.", mapOf("ref" to "R1"))) }
                "unregistered" -> answer(ActionResult.refused("This app build does not implement \"unregistered\"."))
                "throws" -> throw IllegalStateException("boom")
                "slow" -> late += answer
                else -> error("unexpected")
            }
        }
        open()
        action("a1", "open_screen")
        action("a2", "unregistered")
        action("a3", "throws")
        action("a4", "slow")
        assertEquals("/kyc", argsSeen?.getString("path"))
        var r = results()
        assertEquals(listOf("a1", "a2", "a3"), r.map { it.getString("action_id") })
        assertEquals(listOf("ok", "refused", "error"), r.map { it.getString("status") })
        assertEquals("Opened.", r[0].getString("message"))
        assertEquals("R1", r[0].getJSONObject("data").getString("ref"))
        assertTrue(r[2].getString("message").contains("boom"))

        sched.advance(5_499)
        assertEquals(3, results().size)
        sched.advance(1)
        r = results()
        assertEquals("answered before the runtime's 6 s", "a4", r.last().getString("action_id"))
        assertEquals("error", r.last().getString("status"))
        assertEquals("\"slow\" did not finish in time.", r.last().getString("message"))
        late.single()(ActionResult.ok("too late")); sched.drain()
        assertEquals("one answer per action", 4, results().size)
    }

    // ── mute ────────────────────────────────────────────────────────────────

    @Test fun mutedDropsFramesAndKeepsTheMicrophoneRunning() {
        val s = open()
        socket.sent.clear()
        s.setMuted(true); sched.drain()
        repeat(3) { mic(9000.0) }
        assertEquals(emptyList<String>(), socket.types())
        assertEquals(0, audio.stopped)
        s.setMuted(false); sched.drain()
        mic(9000.0)
        assertEquals(listOf("audio"), socket.types())
    }

    // ── endings ─────────────────────────────────────────────────────────────

    @Test fun hangingUpSendsStopThenClosesAbout200MsLater() {
        val s = open()
        s.hangUp(); sched.drain()
        assertEquals("""{"type":"stop","event":"stop","data":"client_hangup"}""", socket.sent.last())
        assertEquals(1, audio.stopped)
        assertNull("not yet: the stop must leave first", socket.closed)
        sched.advance(199)
        assertNull(socket.closed)
        sched.advance(1)
        assertEquals(1000, socket.closed)
        assertEquals("the caller ended it; no event", listOf("open"), events.log)
        listener!!.onClosed(1000, ""); sched.drain()
        assertEquals(listOf("open"), events.log)
        s.hangUp(); sched.drain()
        assertEquals("twice is once", 1, socket.sent.count { it.contains("client_hangup") })
    }

    @Test fun theAgentHangingUpIsARemoteEnd() {
        open()
        audio.buffered = 1.0
        agentAudio()
        listener!!.onClosed(1000, "bye"); sched.drain()
        assertEquals(listOf("open", "ended:remote:"), events.log)
        assertEquals(1, audio.stopped)
        assertEquals(listOf(true, false), events.speaking)
        sched.advance(60_000)
        assertFalse("no pings after the end", socket.types().contains("ping"))
    }

    @Test fun aBrokenSocketMidCallEndsAsFailed() {
        open()
        listener!!.onFailure(java.io.EOFException("reset")); sched.drain()
        assertEquals(listOf("open", "ended:failed:reset"), events.log)
        assertEquals(1, audio.stopped)
    }

    @Test fun aSocketThatNeverOpensFailsAfter15Seconds() {
        session().start(); sched.drain()
        sched.advance(14_999)
        assertEquals(emptyList<String>(), events.log)
        sched.advance(1)
        assertEquals(listOf("failed:The call could not connect within 15 seconds."), events.log)
        assertEquals(1000, socket.closed)
        listener!!.onOpen(null); sched.drain()
        assertEquals("a late open is not a call", 1, events.log.size)
    }

    @Test fun aFailureBeforeOpenIsAFailedCall() {
        session().start(); sched.drain()
        listener!!.onFailure(java.io.IOException("The server refused the WebSocket upgrade: HTTP 403")); sched.drain()
        assertEquals(listOf("failed:The call could not connect: The server refused the WebSocket upgrade: HTTP 403"), events.log)
    }

    @Test fun hangingUpBeforeTheSocketOpensOpensNothing() {
        var deferred: (() -> Unit)? = null
        val deps = CallSessionDeps(
            scheduler = sched, audio = audio,
            http = HttpPost { _, _ -> HttpReply(503, "") },
            openSocket = { _, _, l -> listener = l; socket },
            io = { work -> deferred = work },
        )
        val s = VoiceCallSession(CallSessionConfig("https://www.graine.ai", "pk", "a", null, null, emptyMap(), emptyMap()), deps, events)
        s.start(); sched.drain()
        s.hangUp(); sched.drain()
        deferred!!(); sched.drain()
        assertNull("the ticket came back after the hang-up: no socket", listener)
        assertEquals(emptyList<String>(), events.log)
    }

    @Test fun aMicrophoneThatCannotStartEndsTheCallHonestly() {
        audio.failCapture = true
        open()
        assertEquals(listOf("failed:The microphone could not start: no microphone"), events.log)
        assertTrue(socket.sent.last().contains("client_hangup"))
    }
}
