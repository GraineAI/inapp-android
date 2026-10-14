package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SCREEN CONTEXT PARITY (contract §8): frames paced exactly as the React
 * Native client paces them, a screen's stated actions narrowed to the ones
 * this build registered, and what a tap is called.
 */
class ScreenContextTest {
    private val sched = ManualScheduler()
    private val sent = mutableListOf<Long>()
    private var latest = 0
    private val carried = mutableListOf<Int>()
    private var nothingToSay = false

    private val throttle = ContextThrottle(
        schedule = { delayMs, task -> sched.schedule(delayMs, task) },
        flush = { if (nothingToSay) false else { sent += sched.now(); carried += latest; true } },
    )

    private fun change(v: Int) { latest = v; throttle.changed() }

    @Test fun theFirstChangeGoesOutAtOnce() {
        change(1)
        assertEquals(listOf(0L), sent)
    }

    @Test fun changesInsideTheWindowGoOutOnceAtItsEndWithTheLatest() {
        change(1)
        sched.advance(100); change(2)
        sched.advance(100); change(3)
        sched.advance(199)
        assertEquals(1, sent.size)
        sched.advance(1)
        assertEquals(listOf(0L, 400L), sent)
        assertEquals("the latest, never a stale one", listOf(1, 3), carried)
        sched.advance(400)
        assertEquals("nothing new, nothing sent", 2, sent.size)
    }

    @Test fun aScreenThatUpdatesFasterThanTheWindowIsNeverStalled() {
        // A field reported on every keystroke, every 100 ms for two seconds: a restarting debounce sent nothing.
        for (i in 1..20) { change(i); sched.advance(100) }
        sched.advance(400)
        assertTrue("one frame per window at most", sent.zipWithNext().all { (a, b) -> b - a >= 400 })
        assertTrue("and frames all along", sent.size >= 5)
        assertEquals("the last change always arrives", 20, carried.last())
    }

    @Test fun aFlushWithNothingToSayOpensNoWindow() {
        nothingToSay = true
        change(1)
        nothingToSay = false
        // An action registered before any screen must not push the real first screen 400 ms back.
        change(2)
        assertEquals(listOf(0L), sent)
        assertEquals(listOf(2), carried)
    }

    @Test fun aTapWaitsForTheEndOfTheWindow() {
        latest = 7
        throttle.changedTrailing()
        assertTrue("context, not news: never at once", sent.isEmpty())
        sched.advance(400)
        assertEquals(listOf(400L), sent)
        // A screen change inside a tap's window is carried by the same frame.
        throttle.changedTrailing()
        change(8)
        assertEquals(1, sent.size)
        sched.advance(400)
        assertEquals(2, sent.size)
        assertEquals(8, carried.last())
    }

    // ── which actions the agent is offered ─────────────────────────────────

    @Test fun aScreensStatedActionsAreNarrowedToTheRegisteredOnes() {
        assertEquals(listOf("set_tenure"), ContextFrame.availableActions(listOf("set_tenure", "not_built"), setOf("set_tenure", "open_screen")))
        assertEquals("stated as none: none", emptyList<String>(), ContextFrame.availableActions(emptyList(), setOf("open_screen")))
        assertEquals("nothing stated: everything registered", listOf("open_screen"), ContextFrame.availableActions(null, setOf("open_screen")))
        assertNull("nothing either way: the key is left out", ContextFrame.availableActions(null, emptySet()))
        assertEquals("a stated list with nothing registered offers nothing", emptyList<String>(), ContextFrame.availableActions(listOf("a"), emptySet()))
    }

    @Test fun theFrameCarriesTheNarrowedList() {
        val screen = GraineScreen("kyc", availableActions = listOf("fill_pan", "not_built"))
        val frame = ContextFrame.build(screen, 0, 1, emptyMap(), null, emptyList(), registered = setOf("fill_pan"))!!
        assertEquals(listOf("fill_pan"), (0 until frame.getJSONArray("availableActions").length()).map { frame.getJSONArray("availableActions").getString(it) })
        val open = ContextFrame.build(GraineScreen("home"), 0, 1, emptyMap(), null, emptyList(), registered = emptySet())!!
        assertFalse("nothing registered, nothing stated: absent", open.has("availableActions"))
        val identityOnly = ContextFrame.build(null, 0, 1, mapOf("name" to "Asha"), null, emptyList(), registered = setOf("open_screen"))!!
        assertEquals("open_screen", identityOnly.getJSONArray("availableActions").getString(0))
    }

    // ── taps ───────────────────────────────────────────────────────────────

    @Test fun aTapIsShortAndStill() {
        assertTrue(Taps.isTap(0.0, 0.0, 80))
        assertTrue(Taps.isTap(11.9, -11.9, 399))
        assertFalse("a drag", Taps.isTap(12.0, 0.0, 80))
        assertFalse("a press", Taps.isTap(0.0, 0.0, 400))
        assertFalse(Taps.isTap(0.0, 0.0, -1))
    }

    @Test fun aControlIsNamedAsATestWouldFindIt() {
        assertEquals("Continue", Taps.nameFor("Continue", "btn_next", "Next"))
        assertEquals("btn next", Taps.nameFor(null, "btn_next", "Next"))
        assertEquals("Next", Taps.nameFor("  ", null, "Next"))
        assertNull(Taps.nameFor(null, null, "   "))
        assertEquals("one line", "Pay now", Taps.label("Pay\n   now"))
        val long = Taps.label("x".repeat(80))!!
        assertEquals(Taps.LABEL_MAX, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("tap:Continue", Taps.event("Continue"))
    }

    @Test fun aTapEventIsMaskedOnTheWayOutLikeEveryOther() {
        // A label is a value inside recent_events, so the frame's masking reaches it.
        val e = JSONObject().put("name", Taps.event("Call 9876543210")).put("at", 1)
        val frame = ContextFrame.build(GraineScreen("home"), 0, 1, emptyMap(), null, listOf(e))!!
        assertEquals("tap:Call [PHONE]", frame.getJSONObject("context").getJSONArray("recent_events").getJSONObject(0).getString("name"))
    }
}
