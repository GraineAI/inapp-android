package ai.graine.inapp

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * WHAT WAS SAID ON A CALL, AND THE CAPTIONS SWITCH — the React Native
 * launcher's `captions`, `captionsOn`, `setCaptionsOn` and `clearCaptions`:
 * the fold every SDK shares, then a real agent's voice on the JVM, spoken to
 * as its engine page would. And the voice's other state an app of its own
 * reads: whether the engine is mounted, and its own views opening and closing.
 */
class VoiceCaptionsTest {

    // ── the fold ─────────────────────────────────────────────────────────────

    private fun agent(text: String, live: Boolean = false) = GraineCaption(Role.AGENT, text, live)
    private fun customer(text: String, live: Boolean = false) = GraineCaption(Role.CUSTOMER, text, live)

    @Test fun aLiveTurnIsReplacedAsItsWordsArriveNotAppended() {
        var lines = CaptionLog.fold(emptyList(), agent("It", live = true))
        lines = CaptionLog.fold(lines, agent("It costs", live = true))
        lines = CaptionLog.fold(lines, agent("It costs ₹19 lakh."))
        assertEquals(listOf(agent("It costs ₹19 lakh.")), lines)
        lines = CaptionLog.fold(lines, customer("And the Seltos?"))
        assertEquals("a settled turn closes; the next one is its own line", 2, lines.size)
    }

    @Test fun anEmptyLineSettlesTheOpenTurnInsteadOfBlankingIt() {
        val open = CaptionLog.fold(emptyList(), agent("Let me check", live = true))
        assertEquals(listOf(agent("Let me check")), CaptionLog.fold(open, agent("")))
        assertEquals("nothing open, nothing to settle", open, CaptionLog.fold(open, customer("")))
    }

    @Test fun theLastTwelveAreKept() {
        var lines = emptyList<GraineCaption>()
        for (i in 1..15) lines = CaptionLog.fold(lines, if (i % 2 == 0) agent("a$i") else customer("c$i"))
        assertEquals(CaptionLog.HISTORY, lines.size)
        assertEquals("c15", lines.last().text)
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
    private val dir = java.nio.file.Files.createTempDirectory("graine-captions").toFile()
    private val kept = HashMap<String, String>()

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
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
        val g = GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", "pk_captions"), GraineInApp.DEFAULT) { env }
        clock.advance(0)
        return g
    }

    @Test fun theSwitchIsOnByDefaultAndHidesNothingItKeeps() {
        val v = graine().voice
        assertTrue("an accessibility default", v.captionsOn)
        var heard = 0
        val stop = v.onCaptionsChange { heard++ }
        v.setCaptionsOn(false)
        v.setCaptionsOn(false)
        assertFalse(v.captionsOn)
        assertEquals("told once, when it moved", 1, heard)
        v.handle(PageMessage.Caption(Role.AGENT, "It costs ₹19 lakh.", live = false))
        assertEquals("off hides the caption; what is said is kept", listOf(agent("It costs ₹19 lakh.")), v.captions)
        v.setCaptionsOn(true)
        assertTrue(v.captionsOn)
        stop()
    }

    @Test fun theEngineIsNotMountedUntilAttachedAndAnAppsOwnViewIsTheCustomersWait() {
        val v = graine().voice
        assertFalse("nothing attached it", v.attached)
        v.viewOpened()
        // A view of the app's own on screen, the page not ready: the customer is waiting, and is told so in time.
        clock.advance(4_100)
        assertTrue(v.slowConnect)
        v.viewClosed()
        assertFalse("the last view closed: nobody is waiting", v.slowConnect)
        v.viewClosed()
        assertFalse("an unpaired close is ignored", v.slowConnect)
    }

    @Test fun aClearForgetsWhatWasSaidAndTheCallGoesOn() {
        val v = graine().voice
        v.handle(PageMessage.Call(connected = true, connecting = false))
        v.handle(PageMessage.Caption(Role.CUSTOMER, "What does it cost?", live = false))
        v.handle(PageMessage.Caption(Role.AGENT, "It costs", live = true))
        assertEquals(listOf(customer("What does it cost?"), agent("It costs", live = true)), v.captions)
        var heard = 0
        val stop = v.onCaptionsChange { heard++ }
        v.clearCaptions()
        assertTrue(v.captions.isEmpty())
        assertEquals(1, heard)
        assertTrue("the call goes on", v.connected)
        v.clearCaptions()
        assertEquals("nothing to forget: nobody is told", 1, heard)
        stop()
    }

    // ── the voice bar's levels (0.14.1) ──────────────────────────────────────

    @Test fun theLevelsAreReadWhenThePageSendsThemEachHeldToZeroToOne() {
        assertEquals(PageMessage.Levels(0.42f, 0.1f), Protocol.parse("""{"type":"graine:levels","agent":0.42,"mic":0.1}"""))
        assertEquals("held to 0–1", PageMessage.Levels(1f, 0f), Protocol.parse("""{"type":"graine:levels","agent":3,"mic":-1}"""))
        assertEquals("a level that is not a number is not one", PageMessage.Levels(null, null),
            Protocol.parse("""{"type":"graine:levels","agent":"loud","mic":null}"""))
    }

    @Test fun theLevelsFollowThePageWhileACallIsLiveAndAreZeroOtherwise() {
        val g = graine()
        val v = g.voice
        val heard = mutableListOf<Pair<Float, Float>>()
        g.addListener(object : GraineListener {
            override fun onLevels(agent: Float, mic: Float) { heard += agent to mic }
        })
        v.handle(PageMessage.Levels(0.5f, 0.5f))
        assertEquals("no call, no levels", 0f to 0f, v.agentLevel to v.micLevel)
        assertTrue("nothing moved: nobody is told", heard.isEmpty())
        v.handle(PageMessage.Call(connected = true, connecting = false))
        v.handle(PageMessage.Levels(0.42f, 0.1f))
        assertEquals(0.42f to 0.1f, v.agentLevel to v.micLevel)
        v.handle(PageMessage.Levels(null, 0.3f))
        assertEquals("one the page did not say stays as it was", 0.42f to 0.3f, v.agentLevel to v.micLevel)
        v.handle(PageMessage.Levels(0.42f, 0.3f))
        assertEquals("the same levels are no news", 2, heard.size)
        v.handle(PageMessage.Muted(true))
        assertEquals("muted: the microphone is 0", 0f, v.micLevel)
        v.handle(PageMessage.Levels(0.2f, 0.6f))
        assertEquals(0.2f to 0f, v.agentLevel to v.micLevel)
        v.handle(PageMessage.Muted(false))
        v.handle(PageMessage.Ended)
        assertEquals("the call is over: 0, and the listeners hear it", 0f to 0f, v.agentLevel to v.micLevel)
        assertEquals(0f to 0f, heard.last())
    }

    // ── the call in one word, and what it runs on (0.14.0) ───────────────────

    @Test fun theCallInOneWordAndWhatItRunsOnAreTheVoicesOwn() {
        val v = graine().voice
        assertEquals(GraineVoiceStatus.IDLE, v.status)
        assertEquals(null, v.transport)
        v.handle(PageMessage.Ready)
        v.handle(PageMessage.Call(connected = false, connecting = true))
        assertEquals(GraineVoiceStatus.CONNECTING, v.status)
        v.handle(PageMessage.TransportChanged(Transport.WEBSOCKET, Transport.WEBRTC, "no_answer", null))
        assertEquals(Transport.WEBSOCKET, v.transport)
        assertEquals("why it fell back", "no_answer", v.transportFallbackReason)
        v.handle(PageMessage.Call(connected = true, connecting = false))
        assertEquals(GraineVoiceStatus.LISTENING, v.status)
        v.handle(PageMessage.Caption(Role.AGENT, "It costs", live = true))
        assertEquals(GraineVoiceStatus.SPEAKING, v.status)
        v.handle(PageMessage.Caption(Role.CUSTOMER, "And the Seltos?", live = false))
        assertEquals(GraineVoiceStatus.THINKING, v.status)
        v.handle(PageMessage.Muted(true))
        assertEquals("muted outranks what the agent is doing", GraineVoiceStatus.MUTED, v.status)
        v.handle(PageMessage.Muted(false))
        v.handle(PageMessage.Reconnecting(true))
        assertEquals(GraineVoiceStatus.RECONNECTING, v.status)
        v.handle(PageMessage.Reconnecting(false))
        v.handle(PageMessage.Ended)
        assertEquals(GraineVoiceStatus.ENDED, v.status)
        assertEquals("kept after the call, until the next one", Transport.WEBSOCKET, v.transport)
        v.handle(PageMessage.TransportChanged(Transport.WEBRTC, Transport.WEBRTC, null, null))
        assertEquals("what it asked for: no fallback", null, v.transportFallbackReason)
        // Neither transport could carry the next call.
        v.handle(PageMessage.Progress("failed"))
        assertEquals(GraineVoiceStatus.FAILED, v.status)
        assertEquals(listOf("idle", "connecting", "listening", "thinking", "speaking", "reconnecting", "muted", "failed", "ended"),
            GraineVoiceStatus.entries.map { it.wire })
    }

    /** A Talk call runs on what the voice engine was loaded with — the dashboard's call audio here — from the moment it is placed. */
    @Test fun aTalkCallsTransportIsKnownFromTheMomentItIsPlacedAndAFallbackSticks() {
        val v = graine("""{"agentId":"agent-1","webcallAgentId":"web-1","appearance":{"audioTransport":"rtc"}}""").voice
        v.handle(PageMessage.Ready)
        v.handle(PageMessage.Call(connected = false, connecting = false))
        assertEquals("the engine loading is no call", null, v.transport)
        v.handle(PageMessage.Call(connected = false, connecting = true))
        assertEquals("placed: what the engine runs calls on", Transport.WEBRTC, v.transport)
        assertEquals(null, v.transportFallbackReason)
        v.handle(PageMessage.Call(connected = true, connecting = false))
        assertEquals(Transport.WEBRTC, v.transport)
        // WebRTC could not carry it: the WebSocket does, and says why.
        v.handle(PageMessage.TransportChanged(Transport.WEBSOCKET, Transport.WEBRTC, "unreachable", null))
        assertEquals(Transport.WEBSOCKET, v.transport)
        assertEquals("unreachable", v.transportFallbackReason)
        v.handle(PageMessage.Ended)
        // The next call on the same engine: it stays on the WebSocket, with the same reason.
        v.handle(PageMessage.Call(connected = false, connecting = true))
        assertEquals(Transport.WEBSOCKET, v.transport)
        assertEquals("unreachable", v.transportFallbackReason)
    }

    @Test fun anAgentOnTheWebSocketTalksOverItWithNoFallbackReason() {
        val v = graine().voice
        v.handle(PageMessage.Ready)
        v.handle(PageMessage.Call(connected = true, connecting = false))
        assertEquals(Transport.WEBSOCKET, v.transport)
        assertEquals(null, v.transportFallbackReason)
    }
}
