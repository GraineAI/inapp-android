package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CALLS GRAINE RINGS ITSELF (contract §3.5–3.7): the push that says so, the
 * ring that lasts as long as Graine waits, the variables Graine fills in
 * itself, and the ticket that carries which call and which user it is.
 */
class HostedCallTest {
    @Test fun aHostedPushIsReadAsGraines() {
        val p = AgentCallPush.parse(mapOf("type" to "agent_call", "call_id" to "ic_1", "caller_name" to "Acme Support", "hosted" to "1")) as AgentCallPush.Call
        assertTrue(p.hosted)
        assertEquals("Acme Support", p.callerName)
        assertNull("no reason in Graine's push", p.reason)
        assertTrue(p.variables.isEmpty())
        val own = AgentCallPush.parse(mapOf("type" to "agent_call", "call_id" to "c_1", "hosted" to "0")) as AgentCallPush.Call
        assertFalse("only \"1\" is hosted", own.hosted)
        assertFalse((AgentCallPush.parse(mapOf("type" to "agent_call", "call_id" to "c_2")) as AgentCallPush.Call).hosted)
    }

    @Test fun aPushNamesTheAgentItIsFor() {
        // Several agents in one app: the scheduled call's agent (or its web-call twin), which picks the app's agent that rings it.
        val p = AgentCallPush.parse(mapOf("type" to "agent_call", "call_id" to "ic_2", "hosted" to "1", "agent_id" to " web-sales ")) as AgentCallPush.Call
        assertEquals("web-sales", p.agentId)
        assertNull((AgentCallPush.parse(mapOf("type" to "agent_call", "call_id" to "ic_3", "agent_id" to "  ")) as AgentCallPush.Call).agentId)
        assertNull("an app server's own push names none", (AgentCallPush.parse(mapOf("type" to "agent_call", "call_id" to "c_3")) as AgentCallPush.Call).agentId)
    }

    private class Recorder {
        val states = mutableListOf<GraineCallState>()
        val outcomes = mutableListOf<Pair<String, String>>()
        val timers = mutableListOf<Long>()
    }

    private fun machine(r: Recorder, ringMs: Long = 30_000) = CallMachine(
        schedule = { delay, _ -> r.timers += delay; ({}) },
        ringTimeoutMs = { ringMs },
        onState = { s, _ -> r.states += s },
        onOutcome = { id, o -> r.outcomes += id to o.wire },
    )

    @Test fun aHostedRingRingsForAsLongAsGraineWaits() {
        val r = Recorder()
        val m = machine(r)
        val c = m.incoming(AgentCallPush.Call("ic_1", null, "Acme", emptyMap(), hosted = true, ringTimeoutMs = 45_000))!!
        assertEquals(listOf(45_000L), r.timers)
        assertTrue(c.hosted)
        // The contract's word for a call Graine rang at a booked time (§3.4): the agent hears "scheduled".
        assertEquals("scheduled", c.variables["who_called"])
        // A push's own who_called never wins, hosted or not.
        val spoofed = machine(Recorder()).incoming(AgentCallPush.Call("ic_2", null, "Acme", mapOf("who_called" to "customer"), hosted = true))!!
        assertEquals("scheduled", spoofed.variables["who_called"])
        assertEquals("scheduled", whoCalled(hosted = true))
        assertEquals("agent", whoCalled(hosted = false))
        val r2 = Recorder()
        val own = machine(r2).incoming(AgentCallPush.Call("c_1", "payment_due", null, emptyMap()))!!
        assertEquals(listOf(30_000L), r2.timers)
        assertFalse(own.hosted)
        assertEquals("agent", own.variables["who_called"])
        assertEquals("payment_due", own.variables["call_reason"])
        assertEquals("a nonsense timeout is the configured one", listOf(30_000L),
            Recorder().also { machine(it).incoming(AgentCallPush.Call("c_3", null, null, emptyMap(), ringTimeoutMs = 0)) }.timers)
    }

    @Test fun aRingRefusedWhileBusyIsMissedForItsOwnId() {
        val r = Recorder()
        val m = machine(r)
        m.incoming(AgentCallPush.Call("live", null, null, emptyMap(), hosted = true))
        m.incoming(AgentCallPush.Call("second", null, null, emptyMap(), hosted = true))
        assertEquals(listOf("second" to "missed"), r.outcomes)
        assertEquals("live", m.call?.callId)
    }

    @Test fun theTicketCarriesWhichCallAndWhichUser() {
        val sched = ManualScheduler()
        val requests = mutableListOf<JSONObject>()
        val silent = object : CallAudioIO {
            override fun startCapture(onFrame: (ShortArray) -> Unit, onFailed: () -> Unit) {}
            override fun play(pcm: ShortArray, sampleRate: Int) {}
            override fun clear() {}
            override fun bufferedSeconds(): Double = 0.0
            override fun stop() {}
        }
        val socket = object : CallSocket {
            override fun send(text: String) = true
            override fun close(code: Int, reason: String) {}
        }
        val session = VoiceCallSession(
            CallSessionConfig(
                baseUrl = "https://www.graine.ai", publishableKey = "pk", agentId = "a", webcallAgentId = "w", wsHost = null,
                callVariables = mapOf("call_reason" to "kyc"), sdkVariables = mapOf("name" to "Asha"),
                identity = IdentityEnvelope("P-1234", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", true, "Asia/Kolkata"),
                callId = "ic_1",
            ),
            CallSessionDeps(
                scheduler = sched, audio = silent,
                http = HttpPost { _, json -> requests += JSONObject(json); HttpReply(200, """{"ticket":"t","wsUrl":"wss://ws.graine.ai/v1/s"}""") },
                openSocket = { _, _, _ -> socket },
                io = { it() },
            ),
            object : CallSessionEvents {},
        )
        session.start(); sched.drain()
        val body = requests.single()
        assertEquals("voice", body.getString("mode"))
        assertEquals("ic_1", body.getString("callId"))
        assertEquals("P-1234", body.getJSONObject("identity").getString("value"))
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", body.getString("installId"))
        assertEquals("Asia/Kolkata", body.getString("timezone"))
        assertTrue(body.getBoolean("memory"))
        // Beside the metadata, never in it: the cap must never drop who or which.
        assertFalse(body.getJSONObject("metadata").has("callId"))
        assertEquals("kyc", body.getJSONObject("metadata").getString("call_reason"))
    }
}
