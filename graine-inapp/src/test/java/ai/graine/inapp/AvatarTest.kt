@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE AGENT'S FACE — the frame's reader and the host messages against
 * contract/avatar.json, the hand-over rules against contract/avatar-voice.json
 * (both generated from the TypeScript the web engine runs), and one call's face
 * (AvatarFace) with a stand-in viewer, a stand-in network and the clock turned
 * by hand.
 */
class AvatarTest {
    private fun contract(name: String): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/$name").exists() && !File(dir, "contract/$name").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/$name"), File(dir, "contract/$name")).firstOrNull { it.exists() }
            ?: error("sdk/contract/$name missing — run sdk/contract/generate-avatar*.mjs")
        return JSONObject(f.readText())
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    @Test fun everyFrameCaseReadsAsTheWebReadsIt() {
        val cases = contract("avatar.json").getJSONArray("cases").objects()
        assertTrue(cases.size >= 20)
        for (v in cases) {
            val name = v.getString("name")
            val got = AvatarWire.read(v.opt("frame"))
            val want = v.opt("read") as? JSONObject
            if (want == null) { assertNull(name, got); continue }
            when (got) {
                is AvatarFrame.Ready -> {
                    val ws = want.getJSONObject("session")
                    assertEquals(name, "ready", want.getString("state"))
                    assertEquals(name, ws.getString("id"), got.session.id)
                    assertEquals(name, ws.getString("url"), got.session.url)
                    assertEquals(name, ws.getString("token"), got.session.token)
                    assertEquals(name, (ws.opt("expiresAt") as? Number)?.toDouble(), got.session.expiresAt)
                }
                is AvatarFrame.Unavailable -> {
                    assertEquals(name, "unavailable", want.getString("state"))
                    assertEquals(name, want.getString("reason"), got.reason)
                }
                AvatarFrame.Ended -> assertEquals(name, "ended", want.getString("state"))
                null -> throw AssertionError("$name: read nothing, the web reads $want")
            }
        }
    }

    @Test fun hostMessagesAndSurfaces() {
        val host = contract("avatar.json").getJSONObject("host")
        assertEquals(AvatarWire.HOST_STATE, host.getString("to_host"))
        assertEquals(AvatarWire.SURFACE, host.getString("to_page"))
        for (v in host.getJSONArray("cases").objects()) {
            val got = AvatarWire.hostMessage(v.getString("state"), v.opt("reason") as? String)
            val want = v.opt("message") as? JSONObject
            assertEquals(v.toString(), want?.toString(), got?.toString())
            if (want != null) {
                val read = AvatarWire.readHostMessage(want)!!
                assertEquals(want.getString("state"), read.first)
                assertEquals(want.opt("reason") as? String, read.second)
            }
        }
        for (v in host.getJSONArray("surfaces").objects()) {
            assertEquals(v.toString(), v.opt("shown") as? Boolean, AvatarWire.readSurface(v.opt("message")))
        }
        assertEquals(true, AvatarWire.readSurface(AvatarWire.surfaceMessage(true)))
    }

    @Test fun stateForABridge() {
        assertEquals("""{"phase":"off","reason":null}""", AvatarWire.stateJson(GraineAvatarState.OFF))
        assertEquals("""{"phase":"off","reason":"busy"}""", AvatarWire.stateJson(GraineAvatarState(GraineAvatarState.Phase.OFF, "busy")))
        assertEquals("""{"phase":"live","reason":null}""", AvatarWire.stateJson(GraineAvatarState(GraineAvatarState.Phase.LIVE, "busy")))
    }

    private fun event(e: JSONObject): AvatarFoldEvent {
        val at = e.optDouble("at", 0.0)
        return when (e.getString("type")) {
            "frame" -> AvatarFoldEvent.Frame(AvatarWire.read(e.get("frame"))!!, at)
            "surface" -> AvatarFoldEvent.Surface(e.getBoolean("shown"), at)
            "playing" -> AvatarFoldEvent.Playing(at)
            "blocked" -> AvatarFoldEvent.Blocked(at)
            "failed" -> AvatarFoldEvent.Failed(at)
            "call_ended" -> AvatarFoldEvent.CallEnded(at)
            "sample" -> AvatarFoldEvent.Sample(at, (e.opt("frames") as? Number)?.toDouble(), (e.opt("face") as? Number)?.toDouble(),
                (e.opt("agent") as? Number)?.toDouble() ?: 0.0)
            else -> error("event $e")
        }
    }

    @Test fun constantsAreTheWebEngines() {
        val c = contract("avatar-voice.json").getJSONObject("constants")
        assertEquals(c.getDouble("sample_ms"), AvatarFold.SAMPLE_MS, 0.0)
        assertEquals(c.getDouble("stall_ms"), AvatarFold.STALL_MS, 0.0)
        assertEquals(c.getDouble("first_frame_ms"), AvatarFold.FIRST_FRAME_MS, 0.0)
        assertEquals(c.getDouble("resume_ms"), AvatarFold.RESUME_MS, 0.0)
        assertEquals(c.getDouble("stalled_max_ms"), AvatarFold.STALLED_MAX_MS, 0.0)
        assertEquals(c.getInt("max_stalls"), AvatarFold.MAX_STALLS)
        assertEquals(c.getInt("quiet_samples"), AvatarFold.QUIET_SAMPLES)
        assertEquals(c.getDouble("quiet_level"), AvatarFold.QUIET_LEVEL, 0.0)
        assertEquals(c.getDouble("speech_level"), AvatarFold.SPEECH_LEVEL, 0.0)
        assertEquals(c.getDouble("face_quiet"), AvatarFold.FACE_QUIET, 0.0)
        assertEquals(c.getDouble("face_heard"), AvatarFold.FACE_HEARD, 0.0)
        assertEquals(c.getInt("voice_window"), AvatarFold.VOICE_WINDOW)
        assertEquals(c.getInt("voice_speech_min"), AvatarFold.VOICE_SPEECH_MIN)
    }

    @Test fun everyScenarioStepByStep() {
        val scenarios = contract("avatar-voice.json").getJSONArray("scenarios").objects()
        assertTrue(scenarios.size >= 15)
        for (sc in scenarios) {
            val name = sc.getString("name")
            var s = AvatarFoldState(shown = sc.getBoolean("shown"))
            sc.getJSONArray("steps").objects().forEachIndexed { i, step ->
                s = AvatarFold.fold(s, event(step.getJSONObject("event")))
                val want = step.getJSONObject("expect")
                val at = "$name step $i"
                assertEquals(at, want.getString("phase"), s.phase.word)
                assertEquals(at, want.opt("reason") as? String, s.reason)
                assertEquals(at, want.opt("session") as? String, s.session?.id)
                assertEquals(at, want.getBoolean("agent_audible"), AvatarFold.agentAudible(s))
                assertEquals(at, want.getBoolean("face_audible"), AvatarFold.faceAudible(s))
                assertEquals(at, want.getBoolean("mounted"), AvatarFold.mounted(s))
                assertEquals(at, want.getBoolean("visible"), AvatarFold.visible(s))
                val host = want.getJSONObject("host")
                val got = AvatarFold.hostState(s)
                assertEquals(at, host.getString("state"), got.first)
                assertEquals(at, host.opt("reason") as? String, got.second)
                assertNotEquals("$at: one voice, exactly", AvatarFold.agentAudible(s), AvatarFold.faceAudible(s))
            }
        }
    }

    @Test fun meterScale() {
        assertEquals(0.0, AvatarFold.meterLevel(0.0), 0.0)
        assertEquals(1.0, AvatarFold.meterLevel(0.25), 0.0)
        assertEquals(0.2, AvatarFold.meterLevel(0.01), 1e-9)
        assertEquals(0.0, AvatarFold.meterLevel(Double.NaN), 0.0)
    }

    @Test fun theViewerWire() {
        val s = GraineAvatarSession("f:1", "https://face.example.com/v", "tok", 2_000.0)
        assertEquals("https://face.example.com/v/v1/sessions/f%3A1/ice", AvatarViewerWire.iceUrl(s))
        assertEquals("https://face.example.com/v/v1/sessions/f%3A1/offer", AvatarViewerWire.offerUrl(s))
        assertEquals("Bearer tok", AvatarViewerWire.authorization(s))
        assertTrue(AvatarViewerWire.expired(s, 2_001.0))
        assertFalse(AvatarViewerWire.expired(s, 1_999.0))
        val body = JSONObject(AvatarViewerWire.offerBody("v=0\r\n"))
        assertEquals("offer", body.getString("type"))
        assertEquals("v=0\r\n", body.getString("sdp"))
        assertEquals("v=0", AvatarViewerWire.answer("""{"type":"answer","sdp":"v=0"}"""))
        assertNull(AvatarViewerWire.answer("""{"type":"offer","sdp":"v=0"}"""))
        val servers = AvatarViewerWire.iceServers("""{"iceServers":[{"urls":"stun:stun.example.com:3478"},
            {"urls":["turn:t.example.com:3478?transport=udp","turns:t.example.com:443?transport=tcp"],"username":"u","credential":"c"},
            {"urls":"face:not-ice"},{"urls":[]},{"nothing":1}]}""")
        assertEquals(2, servers.size)
        assertEquals(2, servers[1].urls.size)
        assertEquals("u", servers[1].username)
        assertTrue(AvatarViewerWire.iceServers("{}").isEmpty())
        assertTrue(AvatarViewerWire.isFinal(401) && AvatarViewerWire.isFinal(410) && !AvatarViewerWire.isFinal(409))

        val clock = AvatarClock()
        assertEquals(AvatarClockStep.Ack(4800.0, 3.0, 150.0), clock.receive("""{"type":"clock","epoch":3,"samples":4800}"""))
        assertNull("the same sample once", clock.receive("""{"type":"clock","epoch":3,"samples":4800}"""))
        clock.playoutDelayMs = 90.0
        assertEquals(AvatarClockStep.Interrupted(150.0), clock.receive("""{"type":"interrupted","epoch":4}"""))
        assertTrue(clock.current(4.0) && !clock.current(3.0))
        assertEquals(AvatarClockStep.Failed, clock.receive("""{"type":"error","message":"x"}"""))
        assertEquals("""{"type":"played","samples":4800,"epoch":3}""", AvatarViewerWire.played(4800.0, 3.0))
        assertEquals(100.0, AvatarViewerWire.playoutDelayMs(0.6, 10.0)!!, 1e-9)
        assertEquals(500.0, AvatarViewerWire.playoutDelayMs(60.0, 10.0)!!, 0.0)
        assertNull(AvatarViewerWire.playoutDelayMs(1.0, 0.0))
    }

    // ── One call's face, with stand-ins ──

    private class FakeViewer(val listener: GraineAvatarViewer.Listener) : GraineAvatarViewer {
        var offeredWith: List<GraineRtcIceServer>? = null
        var answer: String? = null
        val sent = mutableListOf<String>()
        val audible = mutableListOf<Boolean>()
        var closed = false
        var frames = 0.0
        var face: Double? = 0.0
        override fun offer(iceServers: List<GraineRtcIceServer>, gatherMs: Long, done: (String?, String?) -> Unit) { offeredWith = iceServers; done("v=0 offer", null) }
        override fun applyAnswer(sdp: String, done: (String?) -> Unit) { answer = sdp; done(null) }
        override fun send(text: String) { sent += text }
        override fun setAudible(audible: Boolean) { this.audible += audible }
        override fun sample(done: (Double?, Double?, Double?, Double?) -> Unit) = done(frames, face, 0.6, 10.0)
        override fun videoView(context: android.content.Context): android.view.View? = null
        override fun close() { closed = true }
    }

    private class Rig {
        val scheduler = ManualScheduler()
        val requests = mutableListOf<Triple<String, String, String?>>()
        var iceStatus = 200
        val offerStatuses = ArrayDeque<Int>()
        val viewers = mutableListOf<FakeViewer>()
        var canView = true
        var agentLevel = 0.0
        val agentAudible = mutableListOf<Boolean>()
        val states = mutableListOf<GraineAvatarState>()
        var shown: GraineAvatarViewer? = null
        val face = AvatarFace(
            scheduler,
            AvatarFace.Hooks(
                makeViewer = { l -> if (canView) FakeViewer(l).also { viewers += it } else null },
                agentLevel = { agentLevel },
                setAgentAudible = { agentAudible += it },
                onState = { states += it },
                onViewer = { shown = it },
            ),
            http = { method, url, headers, _ ->
                requests += Triple(method, url, headers["Authorization"])
                if (url.endsWith("/ice")) HttpReply(iceStatus, """{"iceServers":[{"urls":"turns:turn.example.com:443?transport=tcp","username":"u","credential":"c"}]}""")
                else {
                    val status = offerStatuses.removeFirstOrNull() ?: 200
                    HttpReply(status, if (status == 200) """{"type":"answer","sdp":"v=0 answer"}""" else "")
                }
            },
            io = { it() },
            wallSeconds = { 1_000.0 },
        )
        val viewer get() = viewers.last()

        fun ready(expires: Double = 2_000.0) = JSONObject("""{"type":"avatar","state":"ready","session":{"id":"face_1","url":"https://face.example.com","token":"tok","expires_at":$expires,"publisher_token":"never"}}""")
        fun run(block: () -> Unit) { block(); scheduler.drain() }
        fun flow(ms: Long, face: Double = 0.0, agent: Double = 0.0) {
            repeat((ms / 250).toInt()) {
                viewers.lastOrNull()?.let { it.frames += 6; it.face = face }
                agentLevel = agent
                scheduler.advance(250)
            }
        }
        fun connected() = run { viewer.listener.onConnection("connected") }
    }

    @Test fun aFaceNobodyCanSeeIsNeverWatched() {
        val r = Rig()
        r.run { r.face.surface(false); r.face.frame(r.ready()) }
        assertTrue(r.viewers.isEmpty() && r.requests.isEmpty())
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.AVAILABLE), r.states.last())
        r.run { r.face.surface(true) }
        assertEquals(1, r.viewers.size)
    }

    @Test fun connectsOverWebRtcAndTakesTheVoiceAtAGap() {
        val r = Rig()
        r.run { r.face.surface(true); r.face.frame(r.ready()) }
        assertEquals(listOf("GET", "POST"), r.requests.map { it.first })
        assertEquals("https://face.example.com/v1/sessions/face_1/ice", r.requests[0].second)
        assertTrue(r.requests.all { it.third == "Bearer tok" })
        assertEquals(listOf("turns:turn.example.com:443?transport=tcp"), r.viewer.offeredWith!!.first().urls)
        assertEquals("v=0 answer", r.viewer.answer)
        assertEquals(listOf(false), r.viewer.audible)
        r.run { r.viewer.listener.onChannelOpen() }
        assertEquals(listOf(AvatarViewerWire.READY), r.viewer.sent)
        r.connected()
        assertTrue(r.shown === r.viewer)
        r.flow(1000, face = 0.3, agent = 0.5)
        assertTrue("no hand-over while the agent speaks", r.agentAudible.isEmpty())
        r.flow(500)
        assertEquals(listOf(false), r.agentAudible)
        assertEquals(true, r.viewer.audible.last())
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.LIVE), r.states.last())
        r.scheduler.advance(2250)
        assertEquals("frames stopped: the voice is the call's again", true, r.agentAudible.last())
        assertEquals(false, r.viewer.audible.last())
        r.run { r.face.callEnded() }
        assertTrue(r.viewer.closed)
        assertEquals(GraineAvatarState.Phase.OFF, r.states.last().phase)
    }

    @Test fun aCallWithNoViewerIsUnsupportedAndStaysVoiceOnly() {
        val r = Rig()
        r.canView = false
        r.run { r.face.surface(true); r.face.frame(r.ready()) }
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "unsupported"), r.states.last())
        assertTrue(r.requests.isEmpty() && r.agentAudible.isEmpty())
        r.scheduler.advance(30_000)
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "unsupported"), r.states.last())
    }

    @Test fun theClockIsAnsweredOnceHeardAndAnInterruptionSilencesTheFace() {
        val r = Rig()
        r.run { r.face.surface(true); r.face.frame(r.ready()) }
        r.run { r.viewer.listener.onChannelOpen() }
        r.connected()
        r.flow(500)
        assertEquals(GraineAvatarState.Phase.LIVE, r.states.last().phase)
        r.run { r.viewer.listener.onMessage("""{"type":"clock","epoch":0,"samples":2400}""") }
        assertFalse("never acked on arrival", r.viewer.sent.any { "played" in it })
        r.viewer.frames += 6
        r.scheduler.advance(200)
        assertEquals(AvatarViewerWire.played(2400.0, 0.0), r.viewer.sent.last())
        r.run {
            r.viewer.listener.onMessage("""{"type":"clock","epoch":0,"samples":4800}""")
            r.viewer.listener.onMessage("""{"type":"interrupted","epoch":1}""")
        }
        assertEquals("stale audio drains silently", false, r.viewer.audible.last())
        r.viewer.frames += 6
        r.scheduler.advance(200)
        assertFalse("an interrupted epoch is never acked", r.viewer.sent.any { "4800" in it })
        r.flow(250)
        assertEquals("heard again once drained", true, r.viewer.audible.last())
        r.flow(15_000)
        assertTrue(r.viewer.sent.contains(AvatarViewerWire.PING))
    }

    @Test fun aLostConnectionIsMadeAgainWithAFreshOfferPatiently() {
        val r = Rig()
        r.run { r.face.surface(true); r.face.frame(r.ready()) }
        r.connected()
        r.flow(500)
        assertEquals(GraineAvatarState.Phase.LIVE, r.states.last().phase)
        val first = r.viewer
        r.offerStatuses.addAll(listOf(409, 200))
        r.run { first.listener.onConnection("failed") }
        assertTrue(first.closed)
        assertEquals("a fresh peer and offer at once", 2, r.viewers.size)
        assertTrue("its 409: let go, tried again in 2 s", r.viewers[1].closed)
        r.scheduler.advance(2000)
        assertEquals(3, r.viewers.size)
        assertEquals("v=0 answer", r.viewer.answer)
        assertEquals("frames stopped: the voice went back to the call meanwhile", listOf(false, true), r.agentAudible)
        r.connected()
        r.flow(1250)
        r.flow(500)
        assertEquals(GraineAvatarState.Phase.LIVE, r.states.last().phase)
        assertEquals(false, r.agentAudible.last())
        r.run { r.viewer.listener.onConnection("disconnected") }
        r.scheduler.advance(3900)
        assertEquals(3, r.viewers.size)
        r.scheduler.advance(200)
        assertEquals(4, r.viewers.size)
        repeat(30) {
            r.run { r.viewer.listener.onConnection("failed") }
            r.scheduler.advance(2000)
        }
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "failed"), r.states.last())
        assertEquals(true, r.agentAudible.last())
        assertTrue(r.viewers.all { it.closed })
    }

    @Test fun aFinalRefusalAndNoFirstFrameWithin25sAndExpiredTokensFail() {
        val r = Rig()
        r.iceStatus = 401
        r.run { r.face.surface(true); r.face.frame(r.ready()) }
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "failed"), r.states.last())
        assertTrue(r.viewer.closed)

        val busy = Rig()
        repeat(20) { busy.offerStatuses.addLast(503) }
        busy.run { busy.face.surface(true); busy.face.frame(busy.ready()) }
        busy.scheduler.advance(16_000)
        assertEquals("still trying within 20 s", GraineAvatarState(GraineAvatarState.Phase.AVAILABLE), busy.states.last())
        busy.scheduler.advance(4_000)
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "failed"), busy.states.last())

        val late = Rig()
        late.run { late.face.surface(true); late.face.frame(late.ready()) }
        late.scheduler.advance(24_750)
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.AVAILABLE), late.states.last())
        late.scheduler.advance(500)
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "failed"), late.states.last())

        val expired = Rig()
        expired.run { expired.face.surface(true); expired.face.frame(expired.ready(expires = 999.0)) }
        assertTrue(expired.requests.isEmpty())
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "failed"), expired.states.last())
    }

    @Test fun unavailableAndHiddenGiveTheVoiceBackAtOnce() {
        val r = Rig()
        r.run { r.face.surface(true); r.face.frame(r.ready()) }
        r.connected()
        r.flow(500)
        assertEquals(listOf(false), r.agentAudible)
        r.run { r.face.surface(false) }
        assertEquals(listOf(false, true), r.agentAudible)
        assertTrue("a face nobody can see is not watched", r.viewer.closed)
        r.run { r.face.surface(true) }
        assertEquals(2, r.viewers.size)
        r.connected()
        r.flow(500)
        assertEquals(false, r.agentAudible.last())
        r.run { r.face.frame(JSONObject("""{"type":"avatar","state":"unavailable","reason":"busy"}""")) }
        assertEquals(true, r.agentAudible.last())
        assertEquals(GraineAvatarState(GraineAvatarState.Phase.OFF, "busy"), r.states.last())
    }
}
