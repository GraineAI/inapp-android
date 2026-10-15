package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CONTROL CHANNEL (lib/webcall-rtc.ts openControl): its URL, the queue
 * before `control_ready` (32 at most, the oldest dropped, a newer screen
 * replacing the one waiting), the flush in order, a ping every 25 s, a
 * reopen two seconds after a drop, a close that ends all of it — and its
 * frames read as the bar reads them.
 */
class RtcControlTest {
    private val sched = ManualScheduler()
    private val sockets = mutableListOf<FakeSipSocket>()
    private val frames = mutableListOf<ControlFrame>()
    private var joins = 0

    private fun control(): RtcControl {
        val c = RtcControl(RtcControl.url("wss://ws.graine.ai", "abc123"), sched, { url, l ->
            FakeSipSocket().also { it.url = url; it.listener = l; sockets += it }
        }, object : RtcControl.Listener {
            override fun onJoined() { joins++ }
            override fun onFrame(frame: ControlFrame) { frames += frame }
        })
        c.open(); sched.drain()
        return c
    }

    private fun ready(s: FakeSipSocket) { s.deliver("{\"type\":\"control_ready\",\"session_id\":\"abc123\"}"); sched.drain() }
    private fun types(s: FakeSipSocket) = s.sent.map { JSONObject(it).optString("type") }

    @Test fun opensTheSessionsControlUrl() {
        control()
        assertEquals("wss://ws.graine.ai/control/v1/abc123", sockets.single().url)
        assertEquals("the SDK's default host", "wss://ws.graine.ai/control/v1/x", RtcControl.url(null, "x"))
        assertEquals("wss://ws.graine.ai/control/v1/x", RtcControl.url("wss://ws.graine.ai/", "x"))
    }

    @Test fun queuesUntilReadyThenFlushesInOrderWithTheNewestScreenOnly() {
        val c = control()
        val s = sockets.single()
        c.send("app_context", "{\"type\":\"app_context\",\"seq\":1}")
        c.send("app_event", "{\"type\":\"app_event\",\"n\":1}")
        c.send("app_context", "{\"type\":\"app_context\",\"seq\":2}")
        sched.drain()
        assertTrue("nothing before the join", s.sent.isEmpty())
        ready(s)
        assertEquals(listOf("app_event", "app_context"), types(s))
        assertEquals(2, JSONObject(s.sent[1]).getInt("seq"))
        assertEquals(1, joins)
        // Joined: straight through.
        c.send("app_action_result", "{\"type\":\"app_action_result\"}"); sched.drain()
        assertEquals("app_action_result", types(s).last())
    }

    @Test fun keepsAtMost32WaitingTheOldestDropped() {
        val c = control()
        for (i in 1..40) c.send("app_event", "{\"type\":\"app_event\",\"n\":$i}")
        sched.drain()
        ready(sockets.single())
        val sent = sockets.single().sent
        assertEquals(32, sent.size)
        assertEquals(9, JSONObject(sent.first()).getInt("n"))
        assertEquals(40, JSONObject(sent.last()).getInt("n"))
    }

    @Test fun pingsEvery25SecondsOnceJoinedAndIgnoresPongs() {
        control()
        val s = sockets.single()
        sched.advance(60_000)
        assertTrue("no ping before the join", s.sent.isEmpty())
        ready(s)
        sched.advance(24_999)
        assertTrue(s.sent.isEmpty())
        sched.advance(1)
        assertEquals(listOf("ping"), types(s))
        s.deliver("{\"type\":\"pong\"}"); sched.drain()
        assertTrue(frames.isEmpty())
        sched.advance(25_000)
        assertEquals(listOf("ping", "ping"), types(s))
    }

    @Test fun reopensTwoSecondsAfterADropAndQueuesMeanwhile() {
        val c = control()
        val first = sockets.single()
        ready(first)
        first.drop(); sched.drain()
        c.send("app_context", "{\"type\":\"app_context\",\"seq\":7}"); sched.drain()
        sched.advance(1_999)
        assertEquals(1, sockets.size)
        sched.advance(1)
        assertEquals("opened again", 2, sockets.size)
        val second = sockets[1]
        assertTrue(second.sent.isEmpty())
        ready(second)
        assertEquals(listOf("app_context"), types(second))
        assertEquals(2, joins)
        // The server's 4404 for an unknown session is a close like any other: again in two seconds.
        second.close(4404, "unknown session"); sched.drain()
        sched.advance(2_000)
        assertEquals(3, sockets.size)
    }

    @Test fun closeEndsEverything() {
        val c = control()
        val s = sockets.single()
        ready(s)
        c.close(); sched.drain()
        assertEquals(1000, s.closed)
        sched.advance(120_000)
        assertEquals("no ping and no reopen after close", 1, sockets.size)
        assertTrue(types(s).isEmpty())
        c.send("app_context", "{}"); sched.drain()
        assertTrue(s.sent.isEmpty())
    }

    @Test fun readsTheFramesAsTheBarDoes() {
        fun p(json: String) = ControlFrame.parse(json)
        assertEquals(ControlFrame.CustomerCaption("hello", final = false), p("{\"type\":\"interim_transcript_received\",\"content\":\"hello\"}"))
        assertEquals(ControlFrame.CustomerCaption("from data", final = true), p("{\"type\":\"transcript\",\"data\":{\"content\":\"from data\"}}"))
        assertEquals(ControlFrame.CustomerCaption("plain", final = true), p("{\"type\":\"transcript\",\"data\":\"plain\"}"))
        assertEquals(ControlFrame.AgentText("Welcome to Tring "), p("{\"type\":\"text\",\"data\":\"Welcome to Tring \"}"))
        for (t in listOf("end_of_stream", "clear", "interruption")) assertEquals(ControlFrame.EndOfLine, p("{\"type\":\"$t\"}"))
        val a = p("{\"type\":\"app_action\",\"action\":{\"action_id\":\"a1\",\"name\":\"open_screen\",\"arguments\":{\"screen\":\"kyc\"}}}") as ControlFrame.Action
        assertEquals("a1", a.actionId)
        assertEquals("open_screen", a.name)
        assertEquals("kyc", a.arguments.getString("screen"))
        assertTrue((p("{\"type\":\"widget_pending\",\"widget\":{\"kind\":\"card\"}}") as ControlFrame.Widget).pending)
        assertEquals(ControlFrame.Ready, p("{\"type\":\"control_ready\"}"))
        assertEquals(ControlFrame.Other("mystery"), p("{\"type\":\"mystery\"}"))
        assertNull(p("not json"))
    }
}
