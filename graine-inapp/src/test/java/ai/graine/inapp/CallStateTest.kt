package ai.graine.inapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CALL'S STATES AND OUTCOMES (sdk/NATIVE-CALLS.md "The call"), on a
 * hand-turned clock: ringing times out to missed, decline, answer → connect →
 * active → ended by either side or by a broken line, a call that never
 * connects fails, and a push while a call is live is busy — reported missed,
 * unless it is the live call's own id again, which is ignored.
 */
class CallStateTest {
    private class Clock {
        var now = 0L
        private val tasks = mutableListOf<Triple<Long, () -> Unit, BooleanArray>>()
        val schedule: (Long, () -> Unit) -> (() -> Unit) = { delay, task ->
            val cancelled = booleanArrayOf(false)
            tasks += Triple(now + delay, task, cancelled)
            ({ cancelled[0] = true })
        }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = tasks.filter { !it.third[0] && it.first <= until }.minByOrNull { it.first } ?: break
                tasks.remove(next)
                now = next.first
                next.second()
            }
            now = until
        }
        val pending: Int get() = tasks.count { !it.third[0] }
    }

    private val clock = Clock()
    private val states = mutableListOf<GraineCallState>()
    private val outcomes = mutableListOf<Pair<String, GraineCallOutcome>>()
    private val machine = CallMachine(clock.schedule, { 30_000L }, { s, _ -> states += s }, { id, o -> outcomes += id to o })

    private fun push(id: String = "c1", reason: String? = "payment_due", vars: Map<String, String> = emptyMap()) =
        AgentCallPush.Call(id, reason, "Asha from Acme", vars)

    @Test fun anUnansweredCallIsMissedAfterTheRingTimeout() {
        val call = machine.incoming(push())!!
        assertEquals(GraineCallState.RINGING, machine.state)
        assertEquals(GraineCall.Direction.INCOMING, call.direction)
        clock.advance(29_999)
        assertEquals(GraineCallState.RINGING, machine.state)
        clock.advance(1)
        assertEquals(listOf("c1" to GraineCallOutcome.MISSED), outcomes)
        assertEquals(listOf(GraineCallState.RINGING, GraineCallState.ENDED, GraineCallState.IDLE), states)
        assertNull(machine.call)
    }

    @Test fun aDeclinedCallIsDeclinedAndStopsRinging() {
        machine.incoming(push())
        assertTrue(machine.decline())
        assertEquals(listOf("c1" to GraineCallOutcome.DECLINED), outcomes)
        assertEquals(GraineCallState.IDLE, machine.state)
        assertEquals("the ring timer is gone", 0, clock.pending)
        clock.advance(60_000)
        assertEquals(1, outcomes.size)
    }

    @Test fun answeredThenConnectedThenEndedByTheCustomer() {
        machine.incoming(push())
        assertTrue(machine.answer())
        assertEquals(GraineCallState.CONNECTING, machine.state)
        assertTrue(machine.connected())
        assertEquals(GraineCallState.ACTIVE, machine.state)
        clock.advance(60_000)
        assertEquals("answered calls never time out", GraineCallState.ACTIVE, machine.state)
        assertTrue(machine.end(GraineCallOutcome.EndReason.LOCAL))
        assertEquals(listOf("c1" to GraineCallOutcome.ANSWERED, "c1" to GraineCallOutcome.ended(GraineCallOutcome.EndReason.LOCAL)), outcomes)
        assertEquals(listOf(GraineCallState.RINGING, GraineCallState.CONNECTING, GraineCallState.ACTIVE, GraineCallState.ENDED, GraineCallState.IDLE), states)
        assertEquals("local", outcomes.last().second.endReason?.wire)
        assertEquals("ended", outcomes.last().second.wire)
    }

    @Test fun theAgentHangingUpIsARemoteEnd() {
        machine.incoming(push()); machine.answer(); machine.connected()
        machine.end(GraineCallOutcome.EndReason.REMOTE)
        assertEquals(GraineCallOutcome.ended(GraineCallOutcome.EndReason.REMOTE), outcomes.last().second)
    }

    @Test fun aLineThatBreaksMidCallEndsAsFailed() {
        machine.incoming(push()); machine.answer(); machine.connected()
        machine.fail("connection reset")
        assertEquals(GraineCallOutcome.ended(GraineCallOutcome.EndReason.FAILED, "connection reset"), outcomes.last().second)
    }

    @Test fun aCallThatNeverConnectsFails() {
        val c = machine.outgoing("out-1", "help", emptyMap())!!
        assertEquals(GraineCall.Direction.OUTGOING, c.direction)
        assertEquals(GraineCallState.CONNECTING, machine.state)
        machine.fail("The socket did not open within 15 s")
        assertEquals(listOf("out-1" to GraineCallOutcome.failed("The socket did not open within 15 s")), outcomes)
        assertEquals(GraineCallState.IDLE, machine.state)
    }

    /** A session whose microphone stopped ends as FAILED with why; whether the line had connected decides the outcome. */
    @Test fun aMicrophoneThatStopsFailsACallStillConnectingAndEndsAnActiveOneAsFailed() {
        val why = "The microphone stopped working."
        machine.outgoing("out-1", null, emptyMap())
        assertTrue(machine.end(GraineCallOutcome.EndReason.FAILED, why))
        assertEquals("out-1" to GraineCallOutcome.failed(why), outcomes.last())
        assertEquals("failed", outcomes.last().second.wire)
        machine.incoming(push("c1")); machine.answer(); machine.connected()
        assertTrue(machine.end(GraineCallOutcome.EndReason.FAILED, why))
        assertEquals("c1" to GraineCallOutcome.ended(GraineCallOutcome.EndReason.FAILED, why), outcomes.last())
        assertEquals("ended", outcomes.last().second.wire)
        assertEquals("failed", outcomes.last().second.endReason?.wire)
        assertEquals(GraineCallState.IDLE, machine.state)
    }

    @Test fun hangingUpWhileConnectingIsALocalEnd() {
        machine.outgoing("out-1", null, emptyMap())
        machine.end(GraineCallOutcome.EndReason.LOCAL)
        assertEquals(GraineCallOutcome.ended(GraineCallOutcome.EndReason.LOCAL), outcomes.last().second)
    }

    @Test fun endingARingingCallDeclinesIt() {
        machine.incoming(push())
        machine.end(GraineCallOutcome.EndReason.LOCAL)
        assertEquals(GraineCallOutcome.DECLINED, outcomes.last().second)
    }

    @Test fun aPushDuringALiveCallIsBusyAndReportedMissed() {
        machine.incoming(push("c1")); machine.answer(); machine.connected()
        assertNull(machine.incoming(push("c2")))
        assertEquals("c2" to GraineCallOutcome.MISSED, outcomes.last())
        assertEquals("the live call is untouched", "c1", machine.call?.callId)
        assertEquals(GraineCallState.ACTIVE, machine.state)
        assertNull("a second outgoing call is refused", machine.outgoing("out", null, emptyMap()))
        // And while one is ringing, too.
        machine.end(GraineCallOutcome.EndReason.LOCAL)
        machine.incoming(push("c3"))
        assertNull(machine.incoming(push("c4")))
        assertEquals("c4" to GraineCallOutcome.MISSED, outcomes.last())
        assertEquals(GraineCallState.RINGING, machine.state)
    }

    /** A push can be delivered twice. The second copy of the live call is not a second call, and not a missed one. */
    @Test fun theLiveCallPushedAgainIsIgnoredNotMissed() {
        // Ringing: no second ring, no outcome, and the first push's timeout stands.
        machine.incoming(push("c1"))
        clock.advance(20_000)
        assertNull(machine.incoming(push("c1")))
        assertEquals(emptyList<Pair<String, GraineCallOutcome>>(), outcomes)
        assertEquals("no second ring", listOf(GraineCallState.RINGING), states)
        assertEquals("the ring timer is not re-armed", 1, clock.pending)
        clock.advance(10_000)
        assertEquals("missed once, 30 s after the first push", listOf("c1" to GraineCallOutcome.MISSED), outcomes)

        // Connecting, then active: the call in progress is never reported missed.
        outcomes.clear(); states.clear()
        machine.incoming(push("c2")); machine.answer()
        assertNull(machine.incoming(push("c2")))
        machine.connected()
        assertNull(machine.incoming(push("c2")))
        assertEquals(listOf("c2" to GraineCallOutcome.ANSWERED), outcomes)
        assertEquals(listOf(GraineCallState.RINGING, GraineCallState.CONNECTING, GraineCallState.ACTIVE), states)
        assertEquals("c2", machine.call?.callId)

        // A different id while it is live is still busy: missed, for that id.
        assertNull(machine.incoming(push("c3")))
        assertEquals("c3" to GraineCallOutcome.MISSED, outcomes.last())
        assertEquals(listOf(GraineCallState.RINGING, GraineCallState.CONNECTING, GraineCallState.ACTIVE), states)
        assertEquals("c2", machine.call?.callId)

        // The same holds for a call the customer placed.
        machine.end(GraineCallOutcome.EndReason.LOCAL)
        outcomes.clear()
        machine.outgoing("out-1", null, emptyMap())
        assertNull(machine.incoming(push("out-1")))
        assertEquals(emptyList<Pair<String, GraineCallOutcome>>(), outcomes)
        assertEquals(GraineCallState.CONNECTING, machine.state)
    }

    @Test fun nothingAnswersACallThatIsNotRinging() {
        assertFalse(machine.answer())
        assertFalse(machine.decline())
        assertFalse(machine.connected())
        assertFalse(machine.end(GraineCallOutcome.EndReason.LOCAL))
        machine.incoming(push())
        clock.advance(30_000)
        assertFalse("too late: it was missed", machine.answer())
        assertTrue(outcomes.none { it.second == GraineCallOutcome.ANSWERED })
    }

    @Test fun theCallCarriesWhoCalledAndWhy() {
        val incoming = machine.incoming(push(vars = mapOf("order" to "A-17", "who_called" to "spoofed", "call_reason" to "ignored")))!!
        assertEquals(mapOf("who_called" to "agent", "call_reason" to "payment_due", "order" to "A-17"), incoming.variables)
        assertEquals("Asha from Acme", incoming.callerName)
        machine.decline()
        val outgoing = machine.outgoing("o", "kyc_help", mapOf("plan" to "Plus"))!!
        assertEquals(mapOf("who_called" to "customer", "call_reason" to "kyc_help", "plan" to "Plus"), outgoing.variables)
        machine.end(GraineCallOutcome.EndReason.LOCAL)
        val noReason = machine.outgoing("o2", null, mapOf("call_reason" to "from the app"))!!
        assertEquals("without a reason the app's variable stands", "from the app", noReason.variables["call_reason"])
    }

    // ── the push ────────────────────────────────────────────────────────────

    @Test fun aPushThatIsNotOursIsLeftToTheApp() {
        assertEquals(AgentCallPush.NotOurs, AgentCallPush.parse(mapOf("type" to "chat_message", "call_id" to "x")))
        assertEquals(AgentCallPush.NotOurs, AgentCallPush.parse(mapOf("call_id" to "x")))
        assertEquals(AgentCallPush.NotOurs, AgentCallPush.parse(null))
    }

    @Test fun anAgentCallWithoutAnIdIsMalformed() {
        assertTrue(AgentCallPush.parse(mapOf("type" to "agent_call")) is AgentCallPush.Malformed)
        assertTrue(AgentCallPush.parse(mapOf("type" to "agent_call", "call_id" to "  ")) is AgentCallPush.Malformed)
    }

    @Test fun aFullPushIsRead() {
        val p = AgentCallPush.parse(mapOf(
            "type" to "agent_call",
            "call_id" to "call_42",
            "reason" to "delivery_delayed",
            "caller_name" to "Acme Support",
            "variables" to """{"order":"A-17","amount":4999,"vip":true,"nested":{"a":1},"gone":null}""",
        )) as AgentCallPush.Call
        assertEquals("call_42", p.callId)
        assertEquals("delivery_delayed", p.reason)
        assertEquals("Acme Support", p.callerName)
        assertEquals(mapOf("order" to "A-17", "amount" to "4999", "vip" to "true"), p.variables)
    }

    @Test fun variablesThatAreNotAnObjectAreDroppedAndTheCallStillRings() {
        val p = AgentCallPush.parse(mapOf("type" to "agent_call", "call_id" to "c", "variables" to "not json", "reason" to " ")) as AgentCallPush.Call
        assertEquals(emptyMap<String, String>(), p.variables)
        assertNull("a blank reason is no reason", p.reason)
        assertNull(p.callerName)
    }

    // ── the variables the call carries ─────────────────────────────────────

    @Test fun theCallsOwnVariablesWinAndComeFirst() {
        val merged = CallVariables.merged(mapOf("who_called" to "agent", "call_reason" to "r"), mapOf("name" to "Asha", "who_called" to "x"))
        assertEquals(listOf("who_called", "call_reason", "name"), merged.keys.toList())
        assertEquals("agent", merged["who_called"])
    }

    @Test fun theTicketKeepsTheCallsVariablesEvenBehindALongIdentify() {
        val sdk = (0 until 20).associate { "trait$it" to "v$it" }
        val t = CallVariables.ticketVariables(mapOf("who_called" to "agent", "call_reason" to "payment_due"), sdk)!!
        assertEquals(12, t.size)
        assertEquals("agent", t["who_called"])
        assertEquals("payment_due", t["call_reason"])
        // And over a kilobyte, the SDK's are what gives way.
        val big = (0 until 12).associate { "key$it" to "x".repeat(150) }
        val t2 = CallVariables.ticketVariables(mapOf("who_called" to "agent"), big)!!
        assertEquals("agent", t2["who_called"])
        assertTrue(t2.size in 2..11)
        assertNull(CallVariables.ticketVariables(emptyMap(), emptyMap()))
    }
}
