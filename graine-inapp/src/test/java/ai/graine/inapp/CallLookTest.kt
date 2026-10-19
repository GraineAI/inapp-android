package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * THE CALL SCREEN'S LOOK ON ANDROID (contract/DESIGN.md "Call screen" → "The
 * look"): every design in design.json carries a look this SDK reads whole; a
 * design with no look knob — and a server that sends none — draws today's
 * screen; and the arithmetic the renderer shares with every other SDK agrees
 * with design.json → callVectors, answer for answer.
 */
class CallLookTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/design.json").exists() && !File(dir, "contract/design.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/design.json"), File(dir, "contract/design.json")).first { it.exists() }
        JSONObject(f.readText())
    }
    private val vectors: JSONObject by lazy { fx.getJSONObject("callVectors") }

    private fun cases(): List<JSONObject> = fx.getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun list(a: JSONArray): List<JSONObject> = (0 until a.length()).map { a.getJSONObject(it) }

    /** The config keys of the look: a case with none of them is a design saved before the look. */
    private val lookKnobs = setOf(
        "preset", "layout", "backdrop", "ink", "avatarSize", "avatarRing", "avatarRingColor", "buttonStyle", "buttonShape", "buttonSize",
        "buttonLabels", "buttonColors", "visualizer", "visualizerColor", "visualizerSensitivity", "captionStyle", "captionPosition",
        "nameSize", "nameWeight", "statusSize", "statusWeight", "states",
    )

    private fun backdropOf(o: JSONObject) = CallBackdrop(
        type = o.getString("type"),
        stops = list(o.getJSONArray("stops")).map { CallStop(it.getString("color"), it.getDouble("at")) },
        angle = o.getDouble("angle"),
        motion = o.getString("motion"),
        intensity = o.getDouble("intensity"),
        scrim = o.optDouble("scrim", 1.0),
    )

    private fun near(msg: String, want: Double, got: Double, tol: Double) =
        assertTrue("$msg: want $want, got $got", Math.abs(want - got) <= tol)

    /** Two colours that may differ by one in a channel (a float rounding at .5). */
    private fun sameColour(msg: String, want: String, got: String) {
        val a = CallLooks.rgb(want); val b = CallLooks.rgb(got)
        assertTrue("$msg: want $want, got $got", (0..2).all { Math.abs(a[it] - b[it]) <= 1 })
    }

    // ── every design ───────────────────────────────────────────────────────

    @Test fun everyDesignCarriesALookThisBuildReadsWhole() {
        var looks = 0
        for (c in cases()) for (scheme in listOf("light", "dark")) {
            val t = ResolvedDesign.parse(c.getJSONObject("design"))!!.let { if (scheme == "light") it.light else it.dark }
            val screen = t.call!!.screen
            assertNotNull("${c.getJSONObject("config")} $scheme: the server sends the look", screen)
            screen!!
            assertTrue(screen.layout in CallLooks.LAYOUTS && screen.buttonStyle in CallLooks.BUTTON_STYLES && screen.buttonShape in CallLooks.BUTTON_SHAPES)
            assertTrue(screen.visualizer in CallLooks.VISUALIZERS && screen.captionStyle in CallLooks.CAPTION_STYLES)
            assertEquals(CallLooks.MOMENTS.toSet(), screen.rings.keys)
            for (look in listOf(screen.look) + screen.states.values) {
                assertTrue(look.backdrop.type in CallLooks.BACKDROP_TYPES && look.backdrop.stops.isNotEmpty())
                assertEquals(CallLooks.ACTIONS.toSet(), look.actions.keys)
                looks++
            }
        }
        assertTrue("$looks looks read", looks > 100)
    }

    @Test fun aDesignWithNoLookKnobDrawsTodaysScreenAndSoDoesAnOlderServer() {
        var checked = 0
        for (c in cases()) {
            val call = c.getJSONObject("config").optJSONObject("call")
            if (call != null && call.keys().asSequence().any { it in lookKnobs }) continue
            for (scheme in listOf("light", "dark")) {
                val json = c.getJSONObject("design").getJSONObject(scheme)
                val t = ResolvedDesign.parse(c.getJSONObject("design"))!!.let { if (scheme == "light") it.light else it.dark }
                val derived = CallLooks.derive(t.call!!.copy(screen = null), t.font.weights)
                assertEquals("${c.getJSONObject("config")} $scheme: the server's look is today's screen", derived, t.call!!.screen)
                // An older server's tokens — the same call without the look — draw the same.
                val older = JSONObject(json.getJSONObject("call").toString())
                for (k in listOf("preset", "layout", "backdrop", "ink", "avatarSize", "rings", "avatarRingColor", "buttonStyle", "buttonShape", "buttonSize",
                    "buttonLabels", "actions", "visualizer", "visualizerColor", "visualizerSensitivity", "captionStyle", "captionPosition", "captionFill",
                    "captionFillAlpha", "nameSize", "nameWeight", "statusSize", "statusWeight", "states")) older.remove(k)
                val old = Surfaces.call(older, t.colors, t.font.weights)!!
                assertNull("an older server sends no look", old.screen)
                assertEquals(t.call!!.screen, Surfaces.screenOf(t.copy(call = old)))
                checked++
            }
        }
        assertTrue("$checked designs saved before the look", checked >= 20)
    }

    @Test fun aLookThatIsDamagedFallsBackOneTokenAtATime() {
        val c = cases().first { it.getJSONObject("config").optJSONObject("call")?.optString("layout") == "full-bleed" }
        val json = JSONObject(c.getJSONObject("design").getJSONObject("light").getJSONObject("call").toString())
        json.put("layout", "spiral").put("buttonSize", 999).put("rings", JSONObject().put("incoming", "sparkle"))
        json.getJSONObject("backdrop").put("stops", JSONArray().put(JSONObject().put("color", "nope")))
        json.getJSONObject("actions").getJSONObject("mute").put("stroke", JSONObject.NULL)
        json.getJSONObject("states").put("lunch", JSONObject().put("ink", "light"))
        val s = Surfaces.call(json, Design.CLASSIC_LIGHT.colors)!!.screen!!
        assertEquals("a newer server's word: the first listed", "classic", s.layout)
        assertEquals(80.0, s.buttonSize, 0.0)
        assertEquals("none", s.ringFor("incoming"))
        assertEquals("a moment that was not sent keeps today's", "pulse", s.ringFor("scheduled"))
        assertEquals("no colour in the stops: the flat screen's", listOf(json.getString("background"), json.getString("backgroundTo")), s.look.backdrop.stops.map { it.color })
        assertNull(s.look.action("mute").stroke)
        assertTrue("a moment nobody knows is ignored", "lunch" !in s.states)
    }

    // ── the vectors ────────────────────────────────────────────────────────

    @Test fun gradientLinesAreTheContracts() {
        for (v in list(vectors.getJSONArray("lines"))) {
            val want = v.getJSONObject("line")
            val got = CallLooks.gradientLine(v.getDouble("angle"), v.getDouble("w"), v.getDouble("h"))
            val msg = "angle ${v.getDouble("angle")} ${v.getDouble("w")}x${v.getDouble("h")}"
            near("$msg x0", want.getDouble("x0"), got.x0, 0.011); near("$msg y0", want.getDouble("y0"), got.y0, 0.011)
            near("$msg x1", want.getDouble("x1"), got.x1, 0.011); near("$msg y1", want.getDouble("y1"), got.y1, 0.011)
        }
    }

    @Test fun backdropFramesAreTheContractsAtEveryMoment() {
        for (v in list(vectors.getJSONArray("frames"))) {
            val b = backdropOf(v.getJSONObject("backdrop"))
            val want = v.getJSONObject("frame")
            val got = CallLooks.frame(b, v.getDouble("w"), v.getDouble("h"), v.getDouble("t"))
            val msg = "${b.type} ${b.motion} t=${v.getDouble("t")}"
            val line = want.getJSONObject("line")
            near("$msg line x0", line.getDouble("x0"), got.line.x0, 0.011); near("$msg line y0", line.getDouble("y0"), got.line.y0, 0.011)
            near("$msg line x1", line.getDouble("x1"), got.line.x1, 0.011); near("$msg line y1", line.getDouble("y1"), got.line.y1, 0.011)
            val r = want.getJSONObject("radial")
            near("$msg cx", r.getDouble("cx"), got.radial.cx, 0.011); near("$msg cy", r.getDouble("cy"), got.radial.cy, 0.011)
            near("$msg r", r.getDouble("r"), got.radial.r, 0.011)
            val mesh = list(want.getJSONArray("mesh"))
            assertEquals("$msg glows", mesh.size, got.mesh.size)
            mesh.forEachIndexed { i, g ->
                near("$msg glow $i u", g.getDouble("u"), got.mesh[i].u, 0.0011); near("$msg glow $i v", g.getDouble("v"), got.mesh[i].v, 0.0011)
                assertEquals(g.getString("color"), got.mesh[i].color)
            }
            val a = want.getJSONObject("avatar")
            near("$msg dx", a.getDouble("dx"), got.avatar.dx, 0.011); near("$msg dy", a.getDouble("dy"), got.avatar.dy, 0.011)
            near("$msg scale", a.getDouble("scale"), got.avatar.scale, 0.0011)
        }
    }

    @Test fun meshColoursAreTheContracts() {
        for (v in list(vectors.getJSONArray("mesh"))) {
            val glows = list(v.getJSONArray("glows")).map { CallLooks.Glow(it.getDouble("u"), it.getDouble("v"), it.getString("color")) }
            sameColour("(${v.getDouble("u")}, ${v.getDouble("v")})", v.getString("color"), CallLooks.meshColor(glows, v.getDouble("u"), v.getDouble("v")))
        }
    }

    @Test fun buttonBoxesAndSizesAreTheContracts() {
        for (v in list(vectors.getJSONArray("buttons"))) {
            val a = v.getJSONObject("args")
            val want = v.getJSONObject("frame")
            val got = CallLooks.buttonFrame(
                a.getString("shape"), a.getDouble("size"), a.getDouble("share"), a.optBoolean("end", false),
                if (a.has("endShape")) a.getString("endShape") else null, a.optBoolean("on", false),
            )
            assertEquals("$a", CallLooks.ButtonFrame(want.getDouble("width"), want.getDouble("height"), want.getDouble("radius"), want.getBoolean("continuous")), got)
        }
        for (v in list(vectors.getJSONArray("buttonSizes"))) {
            val s = v.getJSONObject("sizes")
            assertEquals(CallLooks.Sizes(s.getDouble("button"), s.getDouble("big"), s.getDouble("icon")), CallLooks.buttonSizes(v.getDouble("buttonSize")))
        }
    }

    @Test fun theVoiceIsTheContracts() {
        for (v in list(vectors.getJSONArray("levels"))) {
            near("raw ${v.getDouble("raw")} at ${v.getDouble("sensitivity")}", v.getDouble("level"), CallLooks.voiceLevel(v.getDouble("raw"), v.getDouble("sensitivity")), 0.0011)
        }
        for (v in list(vectors.getJSONArray("speaking"))) {
            near("speaking at ${v.getDouble("t")}", v.getDouble("level"), CallLooks.speakingLevel(v.getDouble("t")), 0.0011)
        }
        // Followed up quickly and down slowly.
        assertTrue(CallLooks.follow(0.0, 1.0, 60.0) > 0.6)
        assertTrue(CallLooks.follow(1.0, 0.0, 60.0) > 0.7)
    }

    @Test fun theMomentsAreTheContracts() {
        val states = mapOf("ringing" to GraineCallState.RINGING, "connecting" to GraineCallState.CONNECTING, "active" to GraineCallState.ACTIVE,
            "ended" to GraineCallState.ENDED, "idle" to GraineCallState.IDLE)
        for (v in list(vectors.getJSONArray("moments"))) {
            val i = v.getJSONObject("input")
            val m = CallScreen.moment(
                states.getValue(i.getString("state")), mine = true, hosted = i.optBoolean("hosted", false), missed = i.optBoolean("missed", false),
                outgoing = i.optString("direction") == "outgoing", reconnecting = i.optBoolean("reconnecting", false),
            )
            assertEquals("$i", v.getString("moment"), m.wire)
        }
    }

    @Test fun theStatusWordsAreTheContracts() {
        val turned = cases().first { it.getJSONObject("config").optJSONObject("call")?.optString("layout") == "full-bleed" }
            .let { ResolvedDesign.parse(it.getJSONObject("design"))!!.light.call!! }
        val classic = ResolvedDesign.parse(cases()[0].getJSONObject("design"))!!.light.call!!.copy(timer = false)
        val byWire = CallScreen.Moment.values().associateBy { it.wire }
        for (v in list(vectors.getJSONArray("status"))) {
            val call = if (v.getString("call") == "turned") turned else classic
            val o = v.getJSONObject("options")
            val ending = when (o.optString("ending")) { "failed" -> CallScreen.Ending.FAILED; "declined" -> CallScreen.Ending.DECLINED; else -> CallScreen.Ending.NORMAL }
            val words = CallScreen.status(
                byWire.getValue(v.getString("moment")), call, if (o.has("elapsedMs")) o.getLong("elapsedMs") else null,
                muted = o.optBoolean("muted", false), agentSpeaking = o.optBoolean("speaking", false), ending = ending,
            )
            assertEquals("$v", v.getString("words"), words)
        }
    }

    @Test fun eachMomentDrawsItsOwnLookOrTheScreens() {
        val screen = cases().first { it.getJSONObject("config").optJSONObject("call")?.optString("layout") == "full-bleed" }
            .let { ResolvedDesign.parse(it.getJSONObject("design"))!!.light.call!!.screen!! }
        for (v in list(vectors.getJSONArray("looks"))) {
            val m = v.getString("moment")
            assertEquals(m, v.getBoolean("own"), m in screen.states)
            assertEquals(m, backdropOf(v.getJSONObject("backdrop")), screen.lookFor(m).backdrop)
            assertEquals(m, v.getString("ring"), screen.ringFor(m))
        }
    }

    @Test fun theNumbersAreTheContracts() {
        val cs = fx.getJSONObject("callScreen")
        val layouts = cs.getJSONObject("layouts")
        assertEquals(layouts.keys().asSequence().toSet(), CallLooks.LAYOUT_SPECS.keys)
        for ((id, spec) in CallLooks.LAYOUT_SPECS) {
            val l = layouts.getJSONObject(id)
            assertEquals(id, CallLooks.Layout(l.getString("align"), l.getDouble("top"), l.getDouble("avatar"), l.getDouble("nameGap"),
                l.getBoolean("nameFirst"), l.getDouble("nameSize"), l.getInt("nameLines"), l.getBoolean("card")), spec)
        }
        val s = cs.getJSONObject("screen")
        for ((k, v) in mapOf("side" to CallLooks.SIDE, "statusGap" to CallLooks.STATUS_GAP, "visualizerGap" to CallLooks.VISUALIZER_GAP,
            "visualizerHeight" to CallLooks.VISUALIZER_HEIGHT, "captionGap" to CallLooks.CAPTION_GAP, "captionAboveButtons" to CallLooks.CAPTION_ABOVE_BUTTONS,
            "buttonsBottom" to CallLooks.BUTTONS_BOTTOM, "labelGap" to CallLooks.LABEL_GAP, "labelSize" to CallLooks.LABEL_SIZE,
            "cardPadding" to CallLooks.CARD_PADDING, "cardRadius" to CallLooks.CARD_RADIUS, "cardMinAlpha" to CallLooks.CARD_MIN_ALPHA,
            "orbScale" to CallLooks.ORB_SCALE, "speakingScale" to CallLooks.SPEAKING_SCALE, "endedAlpha" to CallLooks.ENDED_ALPHA,
            "bigButton" to CallLooks.BIG_BUTTON, "icon" to CallLooks.ICON)) assertEquals(k, s.getDouble(k), v, 1e-12)
        val rings = cs.getJSONObject("rings")
        for ((k, r) in mapOf("pulse" to CallLooks.PULSE, "ripple" to CallLooks.RIPPLE)) {
            val o = rings.getJSONObject(k)
            assertEquals(k, CallLooks.Ring(o.getLong("periodMs"), o.getDouble("scale"), o.getDouble("alpha"), o.getDouble("width"), o.getInt("count")), r)
        }
        val glow = rings.getJSONObject("glow")
        assertEquals(glow.getLong("periodMs"), CallLooks.GLOW_PERIOD_MS)
        assertEquals(glow.getDouble("reach"), CallLooks.GLOW_REACH, 0.0)
        val viz = cs.getJSONObject("visualizer")
        val bars = viz.getJSONObject("bars")
        assertEquals(bars.getInt("count"), CallLooks.BARS)
        assertEquals(bars.getDouble("max"), CallLooks.BAR_MAX, 0.0)
        assertEquals(list2(bars.getJSONArray("weights")), CallLooks.BAR_WEIGHTS)
        assertEquals(viz.getJSONObject("wave").getDouble("width"), CallLooks.WAVE_WIDTH, 0.0)
        assertEquals(viz.getJSONObject("orb").getDouble("rest"), CallLooks.ORB_REST, 0.0)
        assertEquals(viz.getDouble("attackMs"), CallLooks.ATTACK_MS, 0.0)
        assertEquals(viz.getDouble("releaseMs"), CallLooks.RELEASE_MS, 0.0)
        val motion = cs.getJSONObject("motion")
        assertEquals(motion.getDouble("breatheMs"), CallLooks.BREATHE_MS, 0.0)
        assertEquals(motion.getLong("idleMs"), CallLooks.IDLE_MS)
        assertEquals(motion.getDouble("blur"), CallLooks.BLUR, 0.0)
        assertEquals(list2(motion.getJSONObject("driftMs").getJSONArray("mesh")), CallLooks.DRIFT_MESH_MS)
        assertEquals(CallLooks.MOMENTS, (0 until cs.getJSONArray("moments").length()).map { cs.getJSONArray("moments").getString(it) })
    }

    private fun list2(a: JSONArray): List<Double> = (0 until a.length()).map { a.getDouble(it) }

    @Test fun aPlacedCallAndARecoveringOneHaveMomentsAndWordsOfTheirOwn() {
        val m = CallScreen
        assertEquals(CallScreen.Moment.OUTGOING, m.moment(GraineCallState.CONNECTING, true, hosted = false, missed = false, outgoing = true))
        assertEquals("an answered ring connects", CallScreen.Moment.CONNECTING, m.moment(GraineCallState.CONNECTING, true, false, false))
        assertEquals(CallScreen.Moment.RECONNECTING, m.moment(GraineCallState.ACTIVE, true, false, false, reconnecting = true))
        val call = Surfaces.CLASSIC_CALL
        assertEquals("Connecting…", m.status(CallScreen.Moment.OUTGOING, call))
        assertEquals("Reconnecting…", m.status(CallScreen.Moment.RECONNECTING, call))
        // An older server's words: a placed call says what connecting says, the brand's own included.
        val older = call.copy(statusText = CallStatusText("Ring", "Putting you through…", "Bye"))
        assertEquals("Putting you through…", m.status(CallScreen.Moment.OUTGOING, older))
        assertEquals("Reconnecting…", m.status(CallScreen.Moment.RECONNECTING, older))
        assertEquals(listOf("scheduled", "incoming", "outgoing", "connecting", "connected", "reconnecting", "ended", "missed"),
            CallScreen.Moment.values().map { it.wire })
        assertTrue(CallScreen.Moment.RING.ringing && CallScreen.Moment.SCHEDULED_RING.ringing && !CallScreen.Moment.OUTGOING.ringing)
    }

    @Test fun onlyALiveCallOfThisScreenShowsTheReconnectingMoment() {
        val m = CallScreen
        assertEquals(CallScreen.Moment.ON_CALL, m.moment(GraineCallState.ACTIVE, true, false, false, reconnecting = false))
        assertEquals("still dialling", CallScreen.Moment.OUTGOING, m.moment(GraineCallState.CONNECTING, true, false, false, outgoing = true, reconnecting = true))
        assertEquals(CallScreen.Moment.CONNECTING, m.moment(GraineCallState.CONNECTING, true, false, false, reconnecting = true))
        assertEquals(CallScreen.Moment.RING, m.moment(GraineCallState.RINGING, true, false, false, reconnecting = true))
        assertEquals("ended is ended", CallScreen.Moment.ENDED, m.moment(GraineCallState.ENDED, true, false, false, reconnecting = true))
        assertEquals("another call's screen", CallScreen.Moment.ENDED, m.moment(GraineCallState.ACTIVE, false, false, false, reconnecting = true))
        assertEquals(CallScreen.Moment.MISSED, m.moment(GraineCallState.IDLE, false, false, true, reconnecting = true))
    }

    @Test fun theAgentsLevelIsTheEnergyBetweenTwoReadingsElseTheReadingItself() {
        val meter = AgentLevelMeter()
        assertEquals("the first reading has no other to measure against", 0.2f, meter.read(0.2, 1.0, 10.0)!!, 1e-6f)
        // 0.09 more energy over 1 s more: an RMS of 0.3.
        assertEquals(0.3f, meter.read(0.25, 1.09, 11.0)!!, 1e-4f)
        assertEquals("no time between them: the reading", 0.5f, meter.read(0.5, 1.2, 11.0)!!, 1e-6f)
        assertNull("nothing to read", AgentLevelMeter().read(null, null, null))
        assertEquals("clamped", 1f, AgentLevelMeter().read(4.0, null, null)!!, 0f)
    }

    @Test fun theLookIsWrittenBackOnlyWhenTheServerSentIt() {
        val first = cases()[0].getJSONObject("design").getJSONObject("light").getJSONObject("call")
        val back = Surfaces.json(Surfaces.call(first, Design.CLASSIC_LIGHT.colors, Design.CLASSIC_LIGHT.font.weights)!!)
        assertTrue(back.has("backdrop") && back.has("states") && back.has("actions"))
        assertEquals(first.getJSONObject("statusText").getString("outgoing"), back.getJSONObject("statusText").getString("outgoing"))
        val older = Surfaces.json(Surfaces.CLASSIC_CALL.copy(screen = null))
        assertTrue("an older server's tokens write back without the look", !older.has("backdrop") && !older.has("rings"))
    }
}
