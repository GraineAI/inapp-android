package ai.graine.inapp

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * THE FACE STAGE — its rule against contract/avatar-stage.json (generated from
 * the React Native SDK's avatarStage), every case and every clock reading; the
 * native call's phase and the header's words as the stage reads them; and a
 * real agent's voice, spoken to as its engine page would, reaching
 * [GraineStage]: the call's start time, its loudspeaker, its controls.
 */
class AvatarStageTest {
    private fun contract(name: String): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/$name").exists() && !File(dir, "contract/$name").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/$name"), File(dir, "contract/$name")).firstOrNull { it.exists() }
            ?: error("sdk/contract/$name missing — run node sdk/contract/generate-avatar-stage.mjs")
        return JSONObject(f.readText())
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    private fun who(word: String) = if (word == "agent") Role.AGENT else Role.CUSTOMER

    private fun lines(a: JSONArray): List<AvatarStage.Line> =
        a.objects().map { AvatarStage.Line(who(it.getString("who")), it.getString("text"), it.getBoolean("final")) }

    private fun started(o: JSONObject): Long? = if (o.isNull("startedAt")) null else o.getLong("startedAt")

    private fun input(o: JSONObject): AvatarStage.Input {
        val n = o.getJSONObject("native")
        val v = o.getJSONObject("voice")
        return AvatarStage.Input(
            native = AvatarStage.Native(n.getString("phase"), n.getBoolean("muted"), n.getBoolean("speaker"), started(n), lines(n.getJSONArray("captions"))),
            voice = AvatarStage.Voice(
                v.getString("status"), v.getBoolean("muted"), v.getBoolean("speaker"), started(v), lines(v.getJSONArray("captions")), v.getBoolean("captionsOn"),
            ),
            chat = o.getBoolean("chat"),
        )
    }

    @Test fun everyCaseIsTheReferenceAnswer() {
        val cases = contract("avatar-stage.json").getJSONArray("cases").objects()
        assertTrue("the cases are there", cases.size >= 20)
        for (c in cases) {
            val name = c.getString("name")
            val want = c.getJSONObject("stage")
            val got = AvatarStage.of(input(c.getJSONObject("input")))
            assertEquals(name, want.getString("call"), got.call.wire)
            assertEquals(name, want.getString("phase"), got.phase.wire)
            assertEquals(name, want.getString("status"), got.status)
            assertEquals(name, want.getBoolean("live"), got.live)
            assertEquals(name, started(want), got.startedAt)
            val cap = want.optJSONObject("caption")
            if (cap == null) assertNull(name, got.caption)
            else assertEquals(name, GraineStageCaption(who(cap.getString("who")), cap.getString("text"), cap.getBoolean("final")), got.caption)
            assertEquals(name, want.getBoolean("muted"), got.muted)
            assertEquals(name, want.getBoolean("speaker"), got.speaker)
            assertEquals(name, want.getJSONArray("controls").strings(), got.controls.map { it.wire })
            assertEquals(name, want.getJSONArray("enabled").strings(), got.enabled.map { it.wire })
            assertEquals(name, want.getString("endLabel"), got.endLabel)
        }
    }

    @Test fun theClockReadsAsTheReferenceClock() {
        val readings = contract("avatar-stage.json").getJSONArray("clock").objects()
        assertTrue(readings.size >= 10)
        for (r in readings) assertEquals("${r.getLong("ms")} ms", r.getString("text"), GraineStage.clock(r.getLong("ms")))
    }

    @Test fun theWordsAreTheContracts() {
        val c = contract("avatar-stage.json")
        assertEquals(c.getJSONArray("phases").strings(), GraineStagePhase.values().map { it.wire })
        assertEquals(c.getJSONArray("controls").strings(), GraineStageControl.values().map { it.wire })
        val status = c.getJSONObject("status")
        for (p in GraineStagePhase.values()) assertEquals(p.wire, status.getString(p.wire), AvatarStage.STATUS.getValue(p))
        assertEquals(listOf("none", "voice", "native"), GraineStageCall.values().map { it.wire })
    }

    @Test fun theNativeCallsPhaseIsItsPublicOne() {
        val out = GraineCall.Direction.OUTGOING
        val inc = GraineCall.Direction.INCOMING
        assertEquals("idle", AvatarStage.nativePhase(GraineCallState.IDLE, null, false))
        assertEquals("ringing", AvatarStage.nativePhase(GraineCallState.RINGING, inc, false))
        assertEquals("the customer's own call, before the agent answers", "calling", AvatarStage.nativePhase(GraineCallState.CONNECTING, out, false))
        assertEquals("an answered ring connecting", "connecting", AvatarStage.nativePhase(GraineCallState.CONNECTING, inc, false))
        assertEquals("active", AvatarStage.nativePhase(GraineCallState.ACTIVE, out, false))
        assertEquals("reconnecting", AvatarStage.nativePhase(GraineCallState.ACTIVE, out, true))
        assertEquals("ended", AvatarStage.nativePhase(GraineCallState.ENDED, out, false))
    }

    @Test fun theHeadersWordsAreTheAppsElseTheChatHeadersElseTheBarsName() {
        assertEquals("Asha", AvatarStage.name("Asha", "Sia", "Assistant"))
        assertEquals("a blank one is no name", "Sia", AvatarStage.name("  ", "Sia", "Assistant"))
        assertEquals("Assistant", AvatarStage.name(null, "", "Assistant"))
        assertEquals("AI care assistant", AvatarStage.role("AI care assistant", "Online now"))
        assertEquals("Online now", AvatarStage.role(null, " Online now "))
        assertNull("no role: no line", AvatarStage.role(null, ""))
    }

    @Test fun theStagesButtonsSayWhatTheyDoAndDrawTheirMoment() {
        val live = GraineStageState(phase = GraineStagePhase.LIVE, muted = true, speaker = false)
        assertEquals("Unmute", FaceStage.label(GraineStageControl.MUTE, live))
        assertEquals("Mute", FaceStage.label(GraineStageControl.MUTE, live.copy(muted = false)))
        assertEquals("Speaker, off", FaceStage.label(GraineStageControl.SPEAKER, live))
        assertEquals("End call", FaceStage.label(GraineStageControl.END, live))
        assertEquals("Cancel", FaceStage.label(GraineStageControl.END, live.copy(endLabel = "Cancel")))
        assertEquals("Decline", FaceStage.label(GraineStageControl.END, live.copy(endLabel = "Decline")))
        assertEquals(SdkIcons.MIC_OFF, FaceStage.icon(GraineStageControl.MUTE, live))
        assertEquals(SdkIcons.CALL, FaceStage.icon(GraineStageControl.ANSWER, live))
        assertTrue(FaceStage.on(GraineStageControl.MUTE, live))
        assertFalse(FaceStage.on(GraineStageControl.SPEAKER, live))
        assertTrue(FaceStage.big(GraineStageControl.END))
        assertFalse(FaceStage.big(GraineStageControl.CHAT))
        assertEquals("with no design: the stage's own red and green", "#DC2626", FaceStage.fill(GraineStageControl.END, GraineStagePhase.LIVE, null))
        assertEquals("#16A34A", FaceStage.fill(GraineStageControl.CALL, GraineStagePhase.IDLE, null))
        assertNull("a round button is white at 18%, not a colour", FaceStage.fill(GraineStageControl.MUTE, GraineStagePhase.LIVE, null))
        assertEquals("“Does 10 work for you?”", FaceStage.quoted(" Does 10 work for you? "))
    }

    @Test fun theStagesFillsAreTheDesignsLightLookWhateverThePhonesAppearance() {
        val design = ResolvedDesign.parse(contract("design.json").getJSONArray("cases").objects().first().getJSONObject("design"))!!
        val light = design.light
        val dark = design.dark
        assertEquals(light.call?.endFill ?: light.colors.danger, FaceStage.fill(GraineStageControl.END, GraineStagePhase.LIVE, light))
        assertEquals(light.call?.declineFill ?: light.colors.danger, FaceStage.fill(GraineStageControl.END, GraineStagePhase.RINGING, light))
        assertEquals(light.call?.acceptFill ?: light.colors.success, FaceStage.fill(GraineStageControl.ANSWER, GraineStagePhase.RINGING, light))
        assertEquals(light.colors.danger, FaceStage.liveFill(light))
        assertEquals("with no design: the stage's own red", "#DC2626", FaceStage.liveFill(null))
        // The view hands the light look in, never the phone's: the dark look's danger is a tint for text on dark.
        if (dark.colors.danger != light.colors.danger) assertTrue(FaceStage.liveFill(light) != dark.colors.danger)
    }

    /**
     * The stage keeps clear only the bars it reaches, from where it is in the window — a Compose AndroidView is never
     * handed insets, so the view cannot wait for them ([left, top, right, bottom], px).
     */
    @Test fun theStageKeepsClearOnlyTheBarsItReaches() {
        val bars = intArrayOf(0, 63, 0, 126) // a 63 px status bar and a 126 px gesture bar, 1080 x 2400
        assertArrayEquals("full screen, edge to edge: both bars", intArrayOf(0, 63, 0, 126), FaceStage.safeArea(bars, 0, 0, 1080, 2400, 1080, 2400))
        assertArrayEquals("under an app bar: nothing at the top", intArrayOf(0, 0, 0, 126), FaceStage.safeArea(bars, 0, 210, 1080, 2190, 1080, 2400))
        assertArrayEquals("a window that fits the bars: nothing", intArrayOf(0, 0, 0, 0), FaceStage.safeArea(bars, 0, 63, 1080, 2211, 1080, 2400))
        assertArrayEquals("a card mid-screen: nothing", intArrayOf(0, 0, 0, 0), FaceStage.safeArea(bars, 40, 600, 1000, 900, 1080, 2400))
        assertArrayEquals("partly under the status bar: the part it reaches", intArrayOf(0, 23, 0, 0), FaceStage.safeArea(bars, 0, 40, 1080, 600, 1080, 2400))
        val landscape = intArrayOf(126, 0, 63, 0) // a cutout on the left, the navigation bar on the right
        assertArrayEquals("landscape: the sides", intArrayOf(126, 0, 63, 0), FaceStage.safeArea(landscape, 0, 0, 2400, 1080, 2400, 1080))
    }

    @Test fun theStateOnTheWireIsTheIosSdksKeysSorted() {
        val s = GraineStageState(
            call = GraineStageCall.VOICE, phase = GraineStagePhase.LIVE, status = "", live = true, startedAt = 1762430400000,
            caption = GraineStageCaption(Role.CUSTOMER, "Tomorrow \"works\"", true), muted = false, speaker = true,
            controls = listOf(GraineStageControl.MUTE, GraineStageControl.END, GraineStageControl.SPEAKER),
            enabled = listOf(GraineStageControl.MUTE, GraineStageControl.END, GraineStageControl.SPEAKER),
            endLabel = "End", face = GraineAvatarState(GraineAvatarState.Phase.LIVE), name = "Asha", role = null,
            component = null, componentPending = true,
        )
        assertEquals(
            """{"call":"voice","caption":{"final":true,"text":"Tomorrow \"works\"","who":"customer"},"componentId":null,""" +
                """"componentPending":true,"controls":["mute","end","speaker"],"enabled":["mute","end","speaker"],"endLabel":"End",""" +
                """"face":{"phase":"live","reason":null},"live":true,"muted":false,"name":"Asha","phase":"live","role":null,""" +
                """"speaker":true,"startedAt":1762430400000,"status":""}""",
            StageWire.json(s),
        )
        val idle = JSONObject(StageWire.json(GraineStageState(face = GraineAvatarState(GraineAvatarState.Phase.OFF, "busy"), role = "AI care assistant")))
        assertTrue(idle.isNull("caption"))
        assertTrue(idle.isNull("startedAt"))
        assertEquals("busy", idle.getJSONObject("face").getString("reason"))
        assertEquals("AI care assistant", idle.getString("role"))
        assertEquals(listOf("call"), idle.getJSONArray("controls").let { a -> (0 until a.length()).map { a.getString(it) } })
    }

    // ── a real agent's voice ─────────────────────────────────────────────────

    private class TestApp : Application() {
        override fun getPackageName(): String = "com.acme.app"
    }

    private class TestMain(private val clock: ManualScheduler) : MainThread {
        override fun post(r: Runnable): Boolean = postDelayed(r, 0)
        override fun postDelayed(r: Runnable, delayMs: Long): Boolean { clock.schedule(delayMs) { r.run() }; return true }
        override fun removeCallbacks(r: Runnable) {}
    }

    private val clock = ManualScheduler()
    private val dir = java.nio.file.Files.createTempDirectory("graine-stage").toFile()
    private val kept = HashMap<String, String>()

    @Before fun fresh() { AppShared.reset(); GraineAvatar.reset() }

    @After fun done() {
        AppShared.reset()
        GraineAvatar.reset()
        dir.deleteRecursively()
    }

    private fun graine(session: String = """{"agentId":"agent-1","webcallAgentId":"web-1"}"""): GraineInApp {
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { it.run() },
            http = HttpPost { _, _ -> HttpReply(200, """{"ok":true}""") },
            fetchSession = { _, _ -> JSONObject(session) },
            openSocket = { _, _, _ -> error("no sockets here") },
            scheduler = clock,
            ticketIo = { it() },
            device = DeviceStore(File(dir, "device.json")),
            sessionCache = SessionCache(slotRead = { kept[it] }, slotWrite = { slot, text -> kept[slot] = text }),
            fonts = false,
        )
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_stage"), GraineInApp.DEFAULT) { env }
        clock.advance(0)
        return g
    }

    @Test fun theVoiceBarsCallHasAStartTimeFromGoingLiveUntilItIsOver() {
        val v = graine().voice
        assertNull("no call, no start", v.startedAt)
        v.handle(PageMessage.Call(connected = false, connecting = true))
        assertNull("being placed is not live", v.startedAt)
        val before = System.currentTimeMillis()
        v.handle(PageMessage.Call(connected = true, connecting = false))
        val at = v.startedAt
        assertNotNull(at)
        assertTrue(at!! >= before && at <= System.currentTimeMillis())
        v.handle(PageMessage.Reconnecting(true))
        v.handle(PageMessage.Call(connected = false, connecting = true))
        v.handle(PageMessage.Call(connected = true, connecting = false))
        assertEquals("a recovery or a fallback inside the call keeps its clock", at, v.startedAt)
        v.handle(PageMessage.Ended)
        assertNull("over", v.startedAt)
    }

    @Test fun theLoudspeakerIsTheCustomersChoiceForTheCallAndTheListenersHearIt() {
        val g = graine()
        val v = g.voice
        val heard = mutableListOf<Pair<Boolean, Boolean>>()
        g.addListener(object : GraineListener {
            override fun onVoiceControls(muted: Boolean, speaker: Boolean) { heard += muted to speaker }
        })
        assertTrue("a call starts on the loudspeaker", v.speaker)
        v.setSpeaker(false)
        assertFalse("chosen before the call: it waits for it", v.speaker)
        v.handle(PageMessage.Call(connected = false, connecting = true))
        v.handle(PageMessage.Call(connected = true, connecting = false))
        v.setMuted(true)
        assertEquals(listOf(false to false, true to false), heard)
        v.handle(PageMessage.Muted(true))
        assertEquals("the same is no news", 2, heard.size)
        v.handle(PageMessage.Muted(false))
        assertEquals(false to false, heard.last())
        v.handle(PageMessage.Ended)
        assertTrue("the call is over: the next one starts on the loudspeaker", v.speaker)
        assertEquals(false to true, heard.last())
    }

    @Test fun theStageFollowsTheVoiceBarsCall() {
        val g = graine()
        val seen = mutableListOf<GraineStageState>()
        val stop = g.stage.addListener { seen += it }
        val idle = g.stage.state
        assertEquals(GraineStageCall.NONE, idle.call)
        assertEquals(GraineStagePhase.IDLE, idle.phase)
        assertEquals(listOf(GraineStageControl.CALL), idle.controls)
        assertEquals("the dashboard's heading, else Assistant", "Assistant", idle.name)
        assertEquals("heard at once", idle, seen.first())

        g.stage.name = "Asha"
        g.stage.role = "AI care assistant"
        g.stage.onChat = {}
        assertEquals("Asha", g.stage.state.name)
        assertEquals("AI care assistant", g.stage.state.role)
        assertEquals(listOf(GraineStageControl.CHAT, GraineStageControl.CALL), g.stage.state.controls)

        val v = g.voice
        v.handle(PageMessage.Call(connected = false, connecting = true))
        assertEquals(GraineStagePhase.CONNECTING, g.stage.state.phase)
        assertEquals("Cancel", g.stage.state.endLabel)
        assertFalse("the microphone waits for the call", g.stage.state.canUse(GraineStageControl.MUTE))
        v.handle(PageMessage.Call(connected = true, connecting = false))
        v.handle(PageMessage.Caption(Role.AGENT, "Hi Riya, I can book your follow-up visit.", live = true))
        val live = g.stage.state
        assertEquals(GraineStageCall.VOICE, live.call)
        assertEquals(GraineStagePhase.LIVE, live.phase)
        assertTrue(live.live)
        assertEquals(v.startedAt, live.startedAt)
        assertEquals(GraineStageCaption(Role.AGENT, "Hi Riya, I can book your follow-up visit.", false), live.caption)
        assertEquals(listOf(GraineStageControl.MUTE, GraineStageControl.CHAT, GraineStageControl.END, GraineStageControl.SPEAKER), live.controls)

        g.stage.setMuted(true)
        assertTrue("the stage's microphone is the voice bar's", g.stage.state.muted)
        v.setCaptionsOn(false)
        assertNull("the captions switched off: no line", g.stage.state.caption)
        v.setCaptionsOn(true)

        v.handle(PageMessage.Ended)
        assertEquals(GraineStagePhase.ENDED, g.stage.state.phase)
        assertEquals("Call ended", g.stage.state.status)
        assertNull(g.stage.state.startedAt)
        assertEquals(listOf(GraineStageControl.CHAT, GraineStageControl.CALL), g.stage.state.controls)
        stop()
        val count = seen.size
        g.stage.name = "Sia"
        assertEquals("stopped: nobody is told", count, seen.size)
    }

    /**
     * A misconfiguration the call survived (`not_configured`: the call carries
     * on over the other transport) is [GraineVoice.error] for the developer —
     * and the public status says failed, as before — but the stage keeps the
     * call the customer is on, and its End button.
     */
    @Test fun aMisconfigurationTheCallSurvivedNeverTakesItsEndButtonAway() {
        val g = graine()
        val v = g.voice
        v.handle(PageMessage.Call(connected = true, connecting = false))
        v.handle(PageMessage.TransportChanged(Transport.WEBSOCKET, Transport.WEBRTC, "not_configured", "WebRTC is not set up for this agent"))
        assertEquals("the developer still hears it", "WebRTC is not set up for this agent", v.error)
        assertEquals(GraineVoiceStatus.FAILED, v.status)
        val on = g.stage.state
        assertEquals(GraineStageCall.VOICE, on.call)
        assertEquals(GraineStagePhase.LIVE, on.phase)
        assertEquals("", on.status)
        assertTrue("a call the customer is on can always be hung up", on.shows(GraineStageControl.END) && on.canUse(GraineStageControl.END))
        assertFalse(on.shows(GraineStageControl.CALL))
        assertEquals("0:42", on.clock(on.startedAt!! + 42_000))

        v.handle(PageMessage.Reconnecting(true))
        assertEquals(GraineStagePhase.RECONNECTING, g.stage.state.phase)
        assertTrue(g.stage.state.canUse(GraineStageControl.END))
        v.handle(PageMessage.Call(connected = true, connecting = false))
        v.handle(PageMessage.Ended)
        assertEquals("an end, never Couldn't connect", GraineStagePhase.ENDED, g.stage.state.phase)
        assertEquals("Call ended", g.stage.state.status)
        assertEquals("no clock off a call", "", g.stage.state.clock())
        assertTrue(g.stage.state.shows(GraineStageControl.CALL))
    }

    @Test fun theStageCarriesTheFaceAndTheComponent() {
        val g = graine()
        assertEquals(GraineAvatarState.OFF, g.stage.state.face)
        GraineAvatar.engineState(this, GraineAvatarState.of("live" to null))
        assertEquals(GraineAvatarState.Phase.LIVE, g.stage.state.face.phase)
        val frame = JSONObject("""{"type":"widget","widget_id":"w1","kind":"info_card","data":{"title":"Follow-up visit"},"fallback_text":"Tomorrow at 10"}""")
        g.voice.handle(PageMessage.Widget(frame))
        val shown = g.stage.state.component
        assertNotNull("the component on show", shown)
        val before = g.stage.state
        g.stage.refresh()
        assertTrue("read again, the same component is the same: the state is kept, nobody is told", before === g.stage.state)
        g.clearPendingWidget()
        assertNull("answered or closed: gone", g.stage.state.component)
        GraineAvatar.engineGone(this)
    }
}
