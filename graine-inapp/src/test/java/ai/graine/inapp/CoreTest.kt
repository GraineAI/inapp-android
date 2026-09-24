package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared contract, generated from the React Native SDK (sdk/contract).
 * Every answer here was produced by the RN SDK's own code; this SDK must
 * reproduce each one exactly.
 */
class ContractTest {
    private val fixtures: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/fixtures.json").exists() && !File(dir, "contract/fixtures.json").exists()) {
            dir = dir.parentFile
        }
        val f = listOf(File(dir, "sdk/contract/fixtures.json"), File(dir, "contract/fixtures.json")).first { it.exists() }
        JSONObject(f.readText())
    }

    @Test fun masksExactlyWhatTheReactNativeSdkMasks() {
        val cases = fixtures.getJSONArray("mask")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertEquals("input: ${c.getString("input")}", c.getString("output"), Masking.mask(c.getString("input")))
        }
    }

    @Test fun meansTheSameByWeakConnection() {
        val cases = fixtures.getJSONArray("quality")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val s = c.getJSONObject("sample")
            fun n(k: String) = if (!s.has(k) || s.isNull(k)) null else s.getDouble(k)
            val got = ConnectionQuality.classify(n("rtt"), n("jitter"), n("loss"))
            assertEquals("sample: $s", c.getString("quality").uppercase(), got.name)
        }
    }

    @Test fun buildsTheSameEngineUrl() {
        val cases = fixtures.getJSONArray("engineUrl")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val input = c.getJSONObject("input")
            val got = Protocol.engineUrl(
                baseUrl = input.getString("baseUrl"),
                voiceAgentId = input.getString("voiceAgentId"),
                transport = Transport.from(input.getString("transport")),
                cacheKey = input.getString("cacheKey"),
                idle = input.getBoolean("idle"),
                publishableKey = input.getString("publishableKey"),
                accent = if (input.isNull("accent")) null else input.getString("accent"),
            )
            assertEquals(c.getString("url"), got)
        }
    }
}

class ProtocolTest {
    @Test fun aMessageIntoThePageIsAPostMessageThatEvaluatesToTrue() {
        val js = Protocol.deliver(Protocol.out(Protocol.Out.SET_MUTED, "muted" to true))
        assertTrue(js.startsWith("window.postMessage({"))
        assertTrue(js.contains("\"type\":\"graine:set-muted\""))
        assertTrue(js.contains("\"muted\":true"))
        // Android logs a non-serialisable statement value as an error on every message.
        assertTrue(js.endsWith("\"*\"); true;"))
    }

    @Test fun everyMessageThePageSendsIsUnderstood() {
        assertEquals(PageMessage.Ready, Protocol.parse("""{"type":"graine:ready"}"""))
        assertEquals(PageMessage.Call(connected = true, connecting = false), Protocol.parse("""{"type":"graine:call","connected":true,"connecting":false}"""))
        assertEquals(PageMessage.Muted(true), Protocol.parse("""{"type":"graine:muted","muted":true}"""))
        assertEquals(PageMessage.Caption(Role.AGENT, "Hi Asha", live = true), Protocol.parse("""{"type":"graine:caption","role":"agent","text":"Hi Asha","live":true}"""))
        assertEquals(PageMessage.Ended, Protocol.parse("""{"type":"graine:ended"}"""))
        assertEquals(PageMessage.MicDenied("NotAllowedError"), Protocol.parse("""{"type":"graine:mic-denied","reason":"NotAllowedError"}"""))
        assertEquals(PageMessage.Network(false), Protocol.parse("""{"type":"graine:network","online":false}"""))
        assertEquals(PageMessage.Reconnecting(true), Protocol.parse("""{"type":"graine:reconnecting","active":true}"""))

        val q = Protocol.parse("""{"type":"graine:quality","sample":{"rtt":480,"jitter":40,"loss":0.1}}""") as PageMessage.QualitySample
        assertEquals(ConnectionQuality.POOR, ConnectionQuality.classify(q.rttMs, q.jitterMs, q.loss))

        val t = Protocol.parse("""{"type":"graine:transport","transport":"ws","requested":"rtc","reason":"media","message":"no audio path"}""") as PageMessage.TransportChanged
        assertEquals(Transport.WEBSOCKET, t.transport)
        assertEquals(Transport.WEBRTC, t.requested)
        assertEquals("media", t.reason)

        val a = Protocol.parse("""{"type":"graine:app-action","action":{"action_id":"a1","name":"set_tenure","arguments":{"months":24}}}""") as PageMessage.AppAction
        assertEquals("a1", a.actionId)
        assertEquals("set_tenure", a.name)
        assertEquals(24, a.arguments.getInt("months"))

        val w = Protocol.parse("""{"type":"graine:widget","widget":{"kind":"product_list","widget_id":"w1"}}""") as PageMessage.Widget
        assertEquals("w1", w.widget.getString("widget_id"))
    }

    @Test fun anythingThatIsNotOursIsIgnoredRatherThanGuessedAt() {
        for (raw in listOf(null, "", "not json", "{}", """{"type":"graine:unheard-of"}""", """{"type":"graine:app-action"}""", "[1,2]")) {
            assertNull("raw: $raw", Protocol.parse(raw))
        }
    }

    @Test fun aMissingQualityNumberIsUnknownNotZero() {
        // Zero would read as a perfect line. Absent is not perfect.
        val q = Protocol.parse("""{"type":"graine:quality","sample":{}}""") as PageMessage.QualitySample
        assertEquals(ConnectionQuality.UNKNOWN, ConnectionQuality.classify(q.rttMs, q.jitterMs, q.loss))
    }
}

class VariableStoreTest {
    @Test fun precedenceIsDashboardThenBuildThenRuntimeThenIdentity() {
        val v = VariableStore(mapOf("plan" to "Free", "city" to "Pune"))
        v.setDashboardDefaults(mapOf("plan" to "Default", "greeting" to "Hi"))
        v.setRuntime(mapOf("plan" to "Plus", "cart_total" to 1299))
        v.setIdentity(mapOf("name" to "Asha", "plan" to "Pro"))
        assertEquals(
            mapOf("plan" to "Pro", "greeting" to "Hi", "city" to "Pune", "cart_total" to "1299", "name" to "Asha"),
            v.merged(),
        )
    }

    @Test fun runtimeMergesRemovesOnNullAndDropsObjects() {
        val v = VariableStore()
        v.setRuntime(mapOf("a" to "1", "nested" to mapOf("x" to 1), "list" to listOf(1)))
        v.setRuntime(mapOf("b" to true, "ratio" to 0.5))
        v.setRuntime(mapOf("a" to null))
        assertEquals(mapOf("b" to "true", "ratio" to "0.5"), v.merged())
        v.setRuntime(null)
        assertEquals(emptyMap<String, String>(), v.merged())
    }

    @Test fun whatTheAppHandsOverIsMaskedLikeEverythingElse() {
        val v = VariableStore()
        v.setIdentity(mapOf("name" to "Asha", "phone" to "9876543210"))
        v.setRuntime(mapOf("note" to "PAN ABCDE1234F"))
        assertEquals("[PHONE]", v.merged()["phone"])
        assertEquals("PAN [PAN]", v.merged()["note"])
        assertEquals("Asha", v.merged()["name"])
    }

    @Test fun signOutClearsWhoItWas() {
        val v = VariableStore()
        v.setIdentity(mapOf("name" to "Asha"))
        v.setIdentity(null)
        assertTrue(v.merged().isEmpty())
    }
}

class LoadWatchdogTest {
    @Test fun aLoadStillArrivingIsNeverCalledStuckHoweverSlow() {
        val w = LoadWatchdog(stallMs = 40, bootMs = 40, hardCapMs = 10_000)
        w.start(0)
        var t = 0L
        for (p in 1..20) { t += 30; w.progress(p * 4, t); assertEquals(LoadWatchdog.Verdict.WAIT, w.check(t)) }
    }

    @Test fun silenceIsAStallAndAMutePageIsABootTimeout() {
        val w = LoadWatchdog(stallMs = 40, bootMs = 40)
        w.start(0); w.progress(30, 10)
        assertEquals(LoadWatchdog.Verdict.STALLED, w.check(60))
        w.start(0); w.progress(100, 10)
        assertEquals(LoadWatchdog.Verdict.BOOT_TIMEOUT, w.check(60))
    }

    @Test fun aLowerProgressReportIsNotThePageGoingBackwards() {
        val w = LoadWatchdog(stallMs = 40)
        w.start(0); w.progress(90, 10)
        w.progress(10, 30); w.progress(10, 45)
        assertEquals(LoadWatchdog.Verdict.STALLED, w.check(55))
    }

    @Test fun aLinkThatDribblesForeverStillEnds() {
        val w = LoadWatchdog(stallMs = 1_000, hardCapMs = 100)
        w.start(0); w.progress(50, 90)
        assertEquals(LoadWatchdog.Verdict.LOAD_CAP, w.check(101))
    }

    @Test fun itNeverWakesMoreThanFourTimesASecondAtTheDefaultWindow() {
        val w = LoadWatchdog()           // 15s windows
        w.start(0)
        assertEquals(250L, w.nextCheckInMs(14_999))
    }

    @Test fun aShortWindowIsCheckedWithinAQuarterOfItself() {
        // A fixed 250ms floor gave a 40ms window six times the slop it asked for.
        val w = LoadWatchdog(stallMs = 40)
        w.start(0)
        assertEquals(10L, w.nextCheckInMs(39))
    }
}

class RetryLadderTest {
    @Test fun jitteredWithinAQuarterAndThenExhausted() {
        val lo = RetryLadder(listOf(1_000, 2_000), random = { 0.0 })
        assertEquals(750L, lo.next())
        assertEquals(1_500L, lo.next())
        assertNull(lo.next())
        assertTrue(lo.exhausted)
        val hi = RetryLadder(listOf(1_000), random = { 1.0 })
        assertEquals(1_250L, hi.next())
        hi.reset()
        assertFalse(hi.exhausted)
    }
}

class ContextFrameTest {
    @Test fun theFrameIsTheShapeTheRuntimeReadsAndIsMasked() {
        val frame = ContextFrame.build(
            screen = GraineScreen(
                screen = "kyc_documents",
                title = "Upload your documents",
                journey = GraineScreen.Journey("KYC", step = 2, of = 4),
                fields = listOf(GraineField("pan", "PAN", "ABCDE1234F", GraineField.Status.INVALID, "Not recognised")),
                availableActions = listOf("fill_pan"),
            ),
            screenSinceMs = 1_000, nowMs = 46_000,
            identity = mapOf("name" to "Asha"), appVersion = "2.4.0", recentEvents = emptyList(),
        )!!
        val ctx = frame.getJSONObject("context")
        assertEquals("kyc_documents", ctx.getString("screen"))
        assertEquals(2, ctx.getJSONObject("journey").getInt("step"))
        val field = ctx.getJSONArray("fields").getJSONObject(0)
        assertEquals("[PAN]", field.getString("value"))
        assertEquals("invalid", field.getString("status"))
        assertEquals(45_000, ctx.getLong("idle_ms"))
        assertEquals("Asha", ctx.getJSONObject("user").getString("name"))
        assertEquals("2.4.0", ctx.getString("app_version"))
        assertEquals("fill_pan", frame.getJSONArray("availableActions").getString(0))
    }

    @Test fun nothingToSayIsNoFrame() {
        assertNull(ContextFrame.build(null, 0, 0, emptyMap(), null, emptyList()))
    }
}

class VersionTest {
    @Test fun theConstantIsTheVersionBeingPublished() {
        // Gradle passes GRAINE_VERSION in; the call record reads GraineVersion.NAME.
        assertEquals(System.getProperty("graine.version"), GraineVersion.NAME)
    }
}

class SocketTraceContractTest {
    @Test fun theTracerIsTheSharedOneVerbatim() {
        // sdk/contract/socket-trace.js is extracted from this file's JS; iOS and Flutter test against it too.
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/socket-trace.js").exists() && !File(dir, "contract/socket-trace.js").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/socket-trace.js"), File(dir, "contract/socket-trace.js")).first { it.exists() }
        assertEquals(f.readText(), SocketTrace.JS)
    }
}
