package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE AGENT'S ACTIONS, BOTH KINDS, DOWN ONE PATH (GraineInApp.ActionRegistry):
 * a handler that answers when it returns, and a deferred one that answers
 * later — from another thread, or another runtime — held to the same rules on
 * a hand-turned main loop: the handler runs on the main thread, the agent
 * hears exactly one answer (the handler's, the six-second deadline's, or a
 * refusal), whatever comes second is dropped, and the listeners hear the
 * request and that one answer.
 */
class DeferredActionTest {
    /** The main thread as the registry sees it: posted tasks and timers, run by hand. */
    private class MainLoop {
        var now = 0L
            private set
        private val posted = ArrayDeque<() -> Unit>()
        private val timers = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()

        val post: (() -> Unit) -> Unit = { task -> synchronized(posted) { posted.addLast(task) } }
        val schedule: (Long, () -> Unit) -> (() -> Unit) = { delay, task ->
            val cancelled = booleanArrayOf(false)
            timers += Triple(now + delay, task, cancelled)
            ({ cancelled[0] = true })
        }

        /** Run everything posted, including what those tasks post. */
        fun drain() {
            while (true) {
                val task = synchronized(posted) { posted.removeFirstOrNull() } ?: return
                task()
            }
        }

        fun advance(ms: Long) {
            val until = now + ms
            drain()
            while (true) {
                val next = timers.filter { !it.third[0] && it.first <= until }.minByOrNull { it.first } ?: break
                timers.remove(next)
                now = next.first
                next.second()
                drain()
            }
            now = until
        }

        val pendingTimers: Int get() = timers.count { !it.third[0] }
    }

    private val loop = MainLoop()
    private val requested = mutableListOf<String>()
    private val completed = mutableListOf<Pair<String, ActionResult>>()
    private val actions = GraineInApp.ActionRegistry(
        post = loop.post,
        schedule = loop.schedule,
        requested = { requested += it },
        completed = { name, result -> completed += name to result },
    )
    private val answers = mutableListOf<ActionResult>()

    private fun ask(name: String, args: JSONObject = JSONObject()) = actions.execute(name, args) { answers += it }

    private fun deferred(run: (JSONObject, (ActionResult) -> Unit) -> Unit) = GraineInApp.ActionHandler.Deferred(run)

    @Test fun aDeferredHandlerAnswersLaterAndTheAgentHearsItOnce() {
        var answer: ((ActionResult) -> Unit)? = null
        var seen: JSONObject? = null
        actions.register("open_screen", deferred { args, a -> seen = args; answer = a })
        ask("open_screen", JSONObject().put("page", "billing"))
        assertTrue("the handler runs on the main loop, not inside execute", answer == null)
        loop.drain()
        assertEquals("billing", seen?.getString("page"))
        assertTrue("nothing is answered until the handler answers", answers.isEmpty())
        loop.advance(2_000)
        answer!!(ActionResult.ok("Opened the bill."))
        loop.drain()
        assertEquals(listOf(ActionResult.ok("Opened the bill.")), answers)
        assertEquals("the deadline is cancelled once answered", 0, loop.pendingTimers)
    }

    @Test fun anAnswerFromAnotherThreadLandsOnTheMainLoop() {
        var answer: ((ActionResult) -> Unit)? = null
        actions.register("lookup", deferred { _, a -> answer = a })
        ask("lookup")
        loop.drain()
        val worker = Thread { answer!!(ActionResult.ok("Found it.")) }
        worker.start()
        worker.join()
        assertTrue("the answer waits for the main loop", answers.isEmpty() && completed.isEmpty())
        loop.drain()
        assertEquals(listOf(ActionResult.ok("Found it.")), answers)
        assertEquals(listOf("lookup" to ActionResult.ok("Found it.")), completed)
    }

    @Test fun noAnswerWithinSixSecondsIsAnErrorAndALateAnswerIsDropped() {
        var answer: ((ActionResult) -> Unit)? = null
        actions.register("slow", deferred { _, a -> answer = a })
        ask("slow")
        loop.advance(5_999)
        assertTrue(answers.isEmpty())
        loop.advance(1)
        assertEquals(listOf(ActionResult.error("The app did not answer within 6 seconds.")), answers)
        answer!!(ActionResult.ok("Done, eventually."))
        loop.drain()
        assertEquals("the agent was already told; the late answer never reaches it", 1, answers.size)
        assertEquals(1, completed.size)
    }

    @Test fun aSecondAnswerIsDropped() {
        actions.register("twice", deferred { _, a -> a(ActionResult.ok("First.")); a(ActionResult.error("Second.")) })
        ask("twice")
        loop.drain()
        assertEquals(listOf(ActionResult.ok("First.")), answers)
    }

    @Test fun aHandlerThatThrowsBeforeAnsweringIsAnError() {
        actions.register("broken", deferred { _, _ -> throw IllegalStateException("No session.") })
        ask("broken")
        loop.drain()
        assertEquals(listOf(ActionResult.error("No session.")), answers)
    }

    @Test fun anAnswerGivenBeforeTheHandlerThrowsIsTheOneHeard() {
        actions.register("messy", deferred { _, a -> a(ActionResult.ok("Saved.")); throw IllegalStateException("cleanup failed") })
        ask("messy")
        loop.drain()
        assertEquals(listOf(ActionResult.ok("Saved.")), answers)
    }

    @Test fun anUnknownNameIsRefusedAtOnce() {
        ask("set_theme")
        assertEquals("refused in the same turn: no handler will ever answer",
            listOf(ActionResult.refused("This app build does not implement \"set_theme\".")), answers)
        assertEquals(0, loop.pendingTimers)
    }

    @Test fun aSyncHandlerTakesTheSamePath() {
        actions.register("set_tenure", GraineInApp.ActionHandler.Sync { args -> ActionResult.ok("Tenure set to ${args.getInt("months")}.") })
        ask("set_tenure", JSONObject().put("months", 12))
        assertTrue("it runs on the main loop too", answers.isEmpty())
        loop.drain()
        assertEquals(listOf(ActionResult.ok("Tenure set to 12.")), answers)
        assertEquals(0, loop.pendingTimers)
        actions.register("crash", GraineInApp.ActionHandler.Sync { throw IllegalArgumentException("bad months") })
        ask("crash")
        loop.drain()
        assertEquals(ActionResult.error("bad months"), answers.last())
    }

    @Test fun theListenersHearTheRequestAndItsOneAnswer() {
        actions.register("open_screen", deferred { _, a -> a(ActionResult.ok()) })
        ask("open_screen")
        ask("missing")
        loop.advance(10_000)
        assertEquals(listOf("open_screen", "missing"), requested)
        assertEquals(listOf("missing" to ActionResult.refused("This app build does not implement \"missing\"."), "open_screen" to ActionResult.ok()), completed)
    }

    @Test fun theUnregisterFunctionRemovesOnlyItsOwnHandler() {
        val first = actions.register("open_screen", deferred { _, a -> a(ActionResult.ok("first")) })
        actions.register("open_screen", deferred { _, a -> a(ActionResult.ok("second")) })
        first()
        assertEquals("a stale unregister leaves the newer handler alone", setOf("open_screen"), actions.keys)
        ask("open_screen")
        loop.drain()
        assertEquals(ActionResult.ok("second"), answers.last())
    }

    @Test fun eitherKindReplacesTheOtherUnderOneName() {
        val sync = actions.register("open_screen", GraineInApp.ActionHandler.Sync { ActionResult.ok("sync") })
        val later = actions.register("open_screen", deferred { _, a -> a(ActionResult.ok("deferred")) })
        ask("open_screen")
        loop.drain()
        assertEquals(ActionResult.ok("deferred"), answers.last())
        sync()
        assertEquals(setOf("open_screen"), actions.keys)
        later()
        assertTrue(actions.keys.isEmpty())
        ask("open_screen")
        assertEquals(ActionResult.Status.REFUSED, answers.last().status)
    }

    @Test fun keysListWhatIsRegisteredInOrder() {
        actions.register("b", deferred { _, _ -> })
        actions.register("a", GraineInApp.ActionHandler.Sync { ActionResult.ok() })
        assertEquals(listOf("b", "a"), actions.keys.toList())
    }
}
