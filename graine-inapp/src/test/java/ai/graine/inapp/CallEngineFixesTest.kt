package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the live run against production showed the call needed: the
 * variables on the direct socket's URL only, the microphone's audio sent as
 * droppable, a hang-up that lets go of the audio at once, a line that is
 * dropped when nothing has arrived for two ping periods, and a call that
 * starts on the session it already has.
 */
class CallEngineFixesTest {
    private val sched = ManualScheduler()

    private class Audio : CallAudioIO {
        val log = mutableListOf<String>()
        var onFrame: ((ShortArray) -> Unit)? = null
        val stopped get() = log.count { it == "stop" }
        override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) { log += "start"; this.onFrame = onFrame }
        override fun play(pcm: ShortArray, sampleRate: Int) {}
        override fun clear() {}
        override fun bufferedSeconds(): Double = 0.0
        override fun stop() { log += "stop" }
    }

    /** Records which frames were sent as droppable. */
    private class Socket : CallSocket {
        val sent = mutableListOf<Pair<String, Boolean>>()
        var closed: Int? = null
        override fun send(text: String): Boolean { if (closed != null) return false; sent += text to false; return true }
        override fun sendDroppable(text: String): Boolean { if (closed != null) return false; sent += text to true; return true }
        override fun close(code: Int, reason: String) { if (closed == null) closed = code }
        fun types(droppable: Boolean? = null) = sent.filter { droppable == null || it.second == droppable }.map { JSONObject(it.first).optString("type") }
    }

    private val audio = Audio()
    private val socket = Socket()
    private val log = mutableListOf<String>()
    private var listener: CallSocketListener? = null
    private var openedUrl: String? = null
    private var ticket: () -> HttpReply = { HttpReply(200, """{"ticket":"tkt_1","wsUrl":"wss://ws.graine.ai/embed/v1/web_1?region=in"}""") }

    private fun session(): VoiceCallSession = VoiceCallSession(
        CallSessionConfig(
            baseUrl = "https://www.graine.ai", publishableKey = "pk_test_key", agentId = "agent_1", webcallAgentId = "web_1",
            wsHost = "wss://ws.graine.ai", callVariables = mapOf("who_called" to "customer"), sdkVariables = mapOf("name" to "Asha"),
        ),
        CallSessionDeps(
            scheduler = sched, audio = audio,
            http = HttpPost { _, _ -> ticket() },
            openSocket = { url, _, l -> openedUrl = url; listener = l; socket },
            io = { it() },
        ),
        object : CallSessionEvents {
            override fun onOpen() { log += "open" }
            override fun onFailed(error: String) { log += "failed:$error" }
            override fun onEnded(reason: GraineCallOutcome.EndReason, error: String?) { log += "ended:${reason.wire}:${error ?: ""}" }
        },
    )

    private fun open(): VoiceCallSession {
        val s = session()
        s.start(); sched.drain()
        listener!!.onOpen("graine.embed.v1"); sched.drain()
        return s
    }

    private fun inbound(type: String) { listener!!.onText(JSONObject().put("type", type).toString()); sched.drain() }

    // ── the variables: the direct URL only ───────────────────────────────────

    @Test fun theGatewayUrlIsUsedAsGivenWithNoVariablesOnIt() {
        session().start(); sched.drain()
        assertEquals("wss://ws.graine.ai/embed/v1/web_1?region=in", openedUrl)
    }

    @Test fun theDirectUrlCarriesTheVariables() {
        ticket = { HttpReply(503, "") }
        session().start(); sched.drain()
        val token = CallVariables.contextToken(mapOf("who_called" to "customer", "name" to "Asha"))!!
        assertEquals("wss://ws.graine.ai/chat/v1/web_1?webcall=1&context_token=" + CallUrls.encodeURIComponent(token), openedUrl)
    }

    // ── what may be dropped ──────────────────────────────────────────────────

    @Test fun onlyTheMicrophonesAudioIsSentDroppable() {
        val s = open()
        audio.onFrame!!(ShortArray(320) { (3000 * Math.sin(it / 5.0)).toInt().toShort() }); sched.drain()
        inbound("text")
        listener!!.onText(JSONObject().put("type", "mark").put("name", "m1").toString()); sched.drain()
        s.hangUp(); sched.drain()
        assertEquals(listOf("audio"), socket.types(droppable = true))
        assertEquals(listOf("init", "mark", "stop"), socket.types(droppable = false))
    }

    // ── hanging up ───────────────────────────────────────────────────────────

    @Test fun aHangUpReleasesTheAudioAtOnceEvenWithTheSessionThreadBusy() {
        val s = open()
        s.hangUp()
        // Nothing drained: the session's thread has not run the hang-up yet, and the audio is already let go.
        assertEquals(1, audio.stopped)
        sched.drain()
        assertEquals("released once", 1, audio.stopped)
        assertEquals("stop", socket.types().last())
        sched.advance(200)
        assertEquals(1000, socket.closed)
    }

    @Test fun aHangUpThatRacesTheOpeningStillLeavesTheMicrophoneOff() {
        val s = session()
        s.start(); sched.drain()
        // The socket opened (its task is queued) and the customer hung up before the session's thread ran it.
        listener!!.onOpen("graine.embed.v1")
        s.hangUp()
        sched.drain()
        assertEquals("the last word is stop", "stop", audio.log.last())
        assertTrue(audio.log.count { it == "stop" } >= 1)
    }

    // ── a line that says nothing ─────────────────────────────────────────────

    @Test fun nothingArrivingForTwoPingPeriodsDropsTheCall() {
        open()
        sched.advance(49_999)
        assertEquals(listOf("open"), log)
        assertEquals("one ping went, at 25 s, and nothing answered it", 1, socket.types().count { it == "ping" })
        sched.advance(1)
        assertEquals(listOf("open", "ended:failed:Nothing arrived for 50 seconds: the connection was lost."), log)
        assertEquals("closed for good", 1000, socket.closed)
        assertEquals(1, audio.stopped)
        sched.advance(120_000)
        assertEquals("decided once", 2, log.size)
    }

    @Test fun aPongKeepsTheLineAliveAndTheClockRunsFromTheLastFrame() {
        open()
        sched.advance(25_000)
        inbound("pong")                 // t = 25 s
        sched.advance(49_999)           // t = 74.999 s: 49.999 s of quiet
        assertEquals(listOf("open"), log)
        sched.advance(1)                // t = 75 s
        assertEquals(listOf("open", "ended:failed:Nothing arrived for 50 seconds: the connection was lost."), log)
    }

    @Test fun anyFrameKeepsTheLineAlive() {
        open()
        for (i in 1..10) {
            sched.advance(20_000)
            inbound(if (i % 2 == 0) "audio" else "text")
        }
        assertEquals("200 s of a talking line", listOf("open"), log)
    }

    @Test fun anEndedCallNeverTripsTheLivenessCheck() {
        val s = open()
        s.hangUp(); sched.drain()
        sched.advance(200_000)
        assertFalse(log.any { it.startsWith("ended:failed") })
    }

    // ── the session a call starts with ──────────────────────────────────────

    private fun sessionConfig(o: JSONObject) = SessionFetch.parse(o)

    @Test fun thisLaunchsSessionStartsTheCall() {
        val live = sessionConfig(JSONObject().put("agentId", "a_live").put("webcallAgentId", "w_live").put("wsHost", "wss://ws.graine.ai"))
        val kept = SessionCache.Kept(null, null, GraineSchedulingRules(), "a_kept", "w_kept", "wss://ws.graine.ai")
        assertEquals(CallAgentIds("a_live", "w_live", "wss://ws.graine.ai"), CallAgentIds.now(live, kept))
    }

    @Test fun theKeptSessionStartsTheCallWhenItSaysWhereTheSocketLives() {
        val kept = SessionCache.Kept(null, null, GraineSchedulingRules(), "a_kept", "w_kept", "wss://ws.graine.ai")
        assertEquals(CallAgentIds("a_kept", "w_kept", "wss://ws.graine.ai"), CallAgentIds.now(null, kept))
    }

    @Test fun aKeptSessionWithoutItsHostOrAgentWaitsForTheNetwork() {
        assertNull("kept by an older SDK: no host", CallAgentIds.now(null, SessionCache.Kept(null, null, GraineSchedulingRules(), "a", "w")))
        assertNull("no agent", CallAgentIds.now(null, SessionCache.Kept(null, null, GraineSchedulingRules(), null, null, "wss://ws.graine.ai")))
        assertNull("nothing at all", CallAgentIds.now(null, null))
        val webOnly = CallAgentIds.now(null, SessionCache.Kept(null, null, GraineSchedulingRules(), null, "w_only", "wss://ws.graine.ai"))
        assertEquals(CallAgentIds("w_only", "w_only", "wss://ws.graine.ai"), webOnly)
    }

    @Test fun theDeviceKeepsTheSocketsHostWithTheAgentsIds() {
        var stored: String? = null
        val cache = SessionCache(read = { stored }, write = { stored = it })
        cache.save("https://www.graine.ai", "pk_test_key", JSONObject().put("agentId", "a1").put("webcallAgentId", "w1").put("wsHost", "wss://ws.graine.ai"))
        val kept = cache.load("https://www.graine.ai", "pk_test_key")
        assertNotNull(kept)
        assertEquals("a1", kept!!.agentId)
        assertEquals("w1", kept.webcallAgentId)
        assertEquals("wss://ws.graine.ai", kept.wsHost)
        assertEquals(CallAgentIds("a1", "w1", "wss://ws.graine.ai"), CallAgentIds.now(null, kept))
    }
}
