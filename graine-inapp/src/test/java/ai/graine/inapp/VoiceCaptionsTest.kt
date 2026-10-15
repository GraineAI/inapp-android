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

    private fun graine(): GraineInApp {
        val env = AgentEnv(
            main = TestMain(clock),
            io = Executor { it.run() },
            http = HttpPost { _, _ -> HttpReply(200, """{"ok":true}""") },
            fetchSession = { _, _ -> JSONObject("""{"agentId":"agent-1","webcallAgentId":"web-1"}""") },
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
}
