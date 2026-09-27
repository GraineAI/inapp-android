package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** The contract file every SDK reads (sdk/contract/design.json), from the monorepo or a standalone checkout. */
private val designJson: JSONObject by lazy {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "sdk/contract/design.json").exists() && !File(dir, "contract/design.json").exists()) dir = dir.parentFile
    val f = listOf(File(dir, "sdk/contract/design.json"), File(dir, "contract/design.json")).firstOrNull { it.exists() }
        ?: error("sdk/contract/design.json missing — run sdk/contract/generate-design.mjs")
    JSONObject(f.readText())
}

private fun cases(): List<JSONObject> = designJson.getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

private fun parsed(o: JSONObject): ResolvedDesign = ResolvedDesign.parse(o) ?: error("did not parse: $o")

/** Every token set in the file: both schemes of every case, and a legacy embed's. */
private fun allTokens(): List<ThemeTokens> =
    (cases().map { it.getJSONObject("design") } + designJson.getJSONObject("legacy").getJSONObject("design"))
        .map { parsed(it) }.flatMap { listOf(it.light, it.dark) }

/**
 * THE DESIGN'S TYPEFACES (contract/DESIGN.md → Fonts): for every design in
 * design.json, exactly the faces — family, weight, registered name, address —
 * the React Native SDK's own code chose, from the same weights table.
 */
class FontFaceTest {
    @Test fun theWeightsTableIsTheContracts() {
        val want = designJson.getJSONObject("fontWeights")
        val got = Fonts.WEIGHTS
        assertEquals(want.keys().asSequence().toSet(), got.keys)
        for (family in got.keys) {
            val a = want.getJSONArray(family)
            assertEquals(family, (0 until a.length()).map { a.getInt(it) }, got.getValue(family))
        }
    }

    @Test fun theAssetVersionAndOriginAreTheContracts() {
        assertEquals(designJson.getInt("fontAssetVersion"), Fonts.FONT_ASSET_VERSION)
        assertEquals(designJson.getString("fontOrigin"), Appearance.DEFAULT_ORIGIN)
    }

    @Test fun everyDesignLoadsExactlyTheFacesTheContractLists() {
        val origin = designJson.getString("fontOrigin")
        var faces = 0
        for (c in cases()) {
            val want = c.getJSONArray("fontFaces").let { a ->
                (0 until a.length()).map { i -> a.getJSONObject(i).let { f -> listOf(f.getString("family"), f.getInt("weight"), f.getString("name"), f.getString("url")) } }
            }
            val got = Fonts.facesForDesign(parsed(c.getJSONObject("design")), origin).map { listOf(it.family, it.weight, it.name, it.url) }
            assertEquals("config ${c.getJSONObject("config")}", want, got)
            faces += got.size
        }
        // Not a vacuous pass: the file names faces, and the system font names none.
        assertTrue(faces >= 20)
        assertTrue(Fonts.facesForDesign(parsed(cases().first().getJSONObject("design"))).isEmpty())
    }

    @Test fun theNearestWeightTheFamilyHasAndTiesGoHeavier() {
        assertEquals(400, Fonts.nearestWeight(listOf(400, 700), 400))
        assertEquals(700, Fonts.nearestWeight(listOf(400, 700), 600))
        assertEquals("a tie is the heavier", 600, Fonts.nearestWeight(listOf(400, 600), 500))
        assertEquals("whatever the order", 600, Fonts.nearestWeight(listOf(600, 400), 500))
        assertEquals(400, Fonts.nearestWeight(listOf(400), 900))
        assertEquals("Martel has no 500", 600, Fonts.nearestWeight(Fonts.WEIGHTS.getValue("Martel"), 500))
        assertNull(Fonts.nearestWeight(emptyList(), 400))
    }

    @Test fun aFamilyIsTheCuratedOneWhateverItsCaseAndSystemIsNone() {
        val f = Fonts.faceFor(" dm sans ", 650)!!
        assertEquals(FontFace("DM Sans", 700, "Graine-DMSans-700", "https://www.graine.ai/api/embed/font?f=DM%20Sans&w=700&v=1"), f)
        assertNull(Fonts.faceFor("system", 400))
        assertNull(Fonts.faceFor("System", 400))
        assertNull(Fonts.faceFor("", 400))
        assertNull(Fonts.faceFor(null, 400))
        assertNull("a family this build has no files for draws in the system font", Fonts.faceFor("Lora", 400))
    }

    @Test fun theAddressIsTheSessionsOriginAndTheFamilyIsEncodedAsTheWebEncodesIt() {
        assertEquals("https://x.test/api/embed/font?f=Baloo%202&w=700&v=1", Fonts.fontUrl("https://x.test//", "Baloo 2", 700))
        assertEquals("https://www.graine.ai/api/embed/font?f=Source%20Serif%204&w=400&v=1", Fonts.fontUrl(null, "Source Serif 4", 400))
        assertEquals("https://www.graine.ai/api/embed/font?f=Inter&w=400&v=1", Fonts.fontUrl("", "Inter", 400))
        assertEquals("Graine-AtkinsonHyperlegibleNext-400", Fonts.faceName("Atkinson Hyperlegible Next", 400))
        assertEquals("Inter|600", Fonts.faceKey("Inter", 600))
    }

    @Test fun aWeightTheDesignNeverLoadsIsSetInItsNearestLoadedFace() {
        // DM Sans at 500 and 700 (Neo-Brutalist's body and strong).
        val faces = listOf(Fonts.faceFor("DM Sans", 500)!!, Fonts.faceFor("DM Sans", 700)!!)
        assertEquals(500, Fonts.faceToDraw("DM Sans", 500, faces)!!.weight)
        assertEquals("a component's own 800 is the design's 700, not the system font", 700, Fonts.faceToDraw("DM Sans", 800, faces)!!.weight)
        assertEquals(500, Fonts.faceToDraw("dm sans", 400, faces)!!.weight)
        assertEquals("600 sits between them; the heavier wins", 700, Fonts.faceToDraw("DM Sans", 600, faces)!!.weight)
        assertNull("a family the design does not load", Fonts.faceToDraw("Inter", 500, faces))
        assertNull(Fonts.faceToDraw("system", 500, faces))
        assertNull(Fonts.faceToDraw("DM Sans", 500, emptyList()))
    }

    @Test fun onlyAFontFileIsKept() {
        assertTrue(Fonts.looksLikeFont(byteArrayOf(0, 1, 0, 0, 9)))
        assertTrue(Fonts.looksLikeFont("OTTO...".toByteArray()))
        assertTrue(Fonts.looksLikeFont("true....".toByteArray()))
        assertTrue(Fonts.looksLikeFont("ttcf....".toByteArray()))
        assertFalse("a captive portal's page", Fonts.looksLikeFont("<!doctype html>".toByteArray()))
        assertFalse(Fonts.looksLikeFont(byteArrayOf(0, 1)))
        assertFalse(Fonts.looksLikeFont(null))
    }

    @Test fun oneAddressIsOneFileAndANewVersionIsANewFile() {
        val a = Fonts.cacheName("https://www.graine.ai/api/embed/font?f=Inter&w=400&v=1")
        assertEquals(a, Fonts.cacheName("https://www.graine.ai/api/embed/font?f=Inter&w=400&v=1"))
        assertNotEquals(a, Fonts.cacheName("https://www.graine.ai/api/embed/font?f=Inter&w=400&v=2"))
        assertTrue(a, Regex("^[0-9a-f]{64}\\.ttf$").matches(a))
    }
}

/** THE SDK'S OWN ICONS (contract/DESIGN.md → Icons), and the path data they and the launcher glyphs are drawn from. */
class IconTest {
    private fun opt(o: JSONObject, k: String): String? = if (o.has(k) && !o.isNull(k)) o.getString(k) else null

    @Test fun theIconSetIsTheContracts() {
        val want = designJson.getJSONObject("icons")
        assertEquals(want.keys().asSequence().toSet(), SdkIcons.ALL.keys)
        for ((name, icon) in SdkIcons.ALL) {
            val w = want.getJSONObject(name)
            assertEquals("$name body", opt(w, "body"), icon.body)
            assertEquals("$name lines", opt(w, "lines"), icon.lines)
        }
        assertEquals(listOf("mic", "micOff", "end", "send", "chat", "call", "chevron", "close"),
            listOf(SdkIcons.MIC, SdkIcons.MIC_OFF, SdkIcons.END, SdkIcons.SEND, SdkIcons.CHAT, SdkIcons.CALL, SdkIcons.CHEVRON, SdkIcons.CLOSE))
    }

    /** Every point a path visits, control points included. */
    private fun points(segs: List<SvgPath.Seg>): List<Pair<Double, Double>> = segs.flatMap { s ->
        when (s) {
            is SvgPath.Seg.Move -> listOf(s.x to s.y)
            is SvgPath.Seg.Line -> listOf(s.x to s.y)
            is SvgPath.Seg.Cubic -> listOf(s.x1 to s.y1, s.x2 to s.y2, s.x to s.y)
            is SvgPath.Seg.Quad -> listOf(s.x1 to s.y1, s.x to s.y)
            SvgPath.Seg.Close -> emptyList()
        }
    }

    @Test fun everyIconAndEveryLauncherGlyphInTheFileParsesInsideItsBox() {
        val paths = SdkIcons.ALL.values.flatMap { listOfNotNull(it.body, it.lines) } +
            allTokens().mapNotNull { it.icons.launcher?.path }.distinct()
        assertTrue(paths.size > 15)
        for (d in paths) {
            val segs = SvgPath.parse(d)
            assertTrue(d, segs.first() is SvgPath.Seg.Move)
            for ((x, y) in points(segs)) assertTrue("$d visits ($x, $y)", x in -1.0..25.0 && y in -1.0..25.0)
        }
    }

    @Test fun relativeCommandsAndImplicitRepeatsResolveToAbsolutePoints() {
        assertEquals(
            listOf(SvgPath.Seg.Move(6.0, 15.0), SvgPath.Seg.Line(12.0, 9.0), SvgPath.Seg.Line(18.0, 15.0)),
            SvgPath.parse("M6 15l6-6 6 6"),
        )
        // Pairs after a move are lines; a relative move after a close starts from where the subpath began.
        assertEquals(
            listOf(SvgPath.Seg.Move(4.0, 12.0), SvgPath.Seg.Line(20.0, 4.0), SvgPath.Seg.Close, SvgPath.Seg.Move(5.0, 13.0), SvgPath.Seg.Line(5.0, 3.0), SvgPath.Seg.Line(2.0, 3.0)),
            SvgPath.parse("M4 12 20 4z m1 1V3H2"),
        )
    }

    @Test fun compactNumbersSplitWhereSvgSplitsThem() {
        // From `end`: ".6.5.6 1.4.1 2" is six numbers.
        val segs = SvgPath.parse("M4.2 14.6c4.6-4.1 11-4.1 15.6 0 .6.5.6 1.4.1 2")
        assertEquals(3, segs.size)
        val second = segs[2] as SvgPath.Seg.Cubic
        assertEquals(19.8 + 0.6, second.x1, 1e-9)
        assertEquals(14.6 + 0.5, second.y1, 1e-9)
        assertEquals(19.8 + 0.1, second.x, 1e-9)
        assertEquals(14.6 + 2.0, second.y, 1e-9)
        assertEquals(listOf(SvgPath.Seg.Move(1e-1, -2.5e1)), SvgPath.parse("M1e-1-2.5E1"))
    }

    @Test fun smoothCurvesReflectTheLastControlPoint() {
        val s = SvgPath.parse("M0 0C1 2 3 4 5 5S9 6 10 10")
        assertEquals(SvgPath.Seg.Cubic(7.0, 6.0, 9.0, 6.0, 10.0, 10.0), s[2])
        // A smooth curve after anything else starts on the current point.
        assertEquals(SvgPath.Seg.Cubic(0.0, 0.0, 2.0, 3.0, 4.0, 4.0), SvgPath.parse("M0 0S2 3 4 4")[1])
        // graphic-eq's q…T: the control reflects through the end of the last quadratic.
        val q = SvgPath.parse("M7 17q0 -0.425 0.288 -0.713T8 6")
        val first = q[1] as SvgPath.Seg.Quad
        val second = q[2] as SvgPath.Seg.Quad
        assertEquals(7.0, first.x1, 1e-9); assertEquals(16.575, first.y1, 1e-9)
        assertEquals(2 * 7.288 - 7.0, second.x1, 1e-9)
        assertEquals(2 * 16.287 - 16.575, second.y1, 1e-9)
        assertEquals(8.0, second.x, 1e-9); assertEquals(6.0, second.y, 1e-9)
    }

    @Test fun anArcBecomesCurvesOnItsCircleEndingExactlyOnItsEndPoint() {
        // Half a circle of radius 2 around (2, 0), clockwise from (0, 0) to (4, 0): through (2, -2).
        val segs = SvgPath.parse("M0 0A2 2 0 0 1 4 0")
        val curves = segs.drop(1).map { it as SvgPath.Seg.Cubic }
        assertEquals(2, curves.size)
        assertEquals(2.0, curves[0].x, 1e-9)
        assertEquals(-2.0, curves[0].y, 1e-9)
        assertEquals(4.0, curves[1].x, 0.0)
        assertEquals(0.0, curves[1].y, 0.0)
        // The other sweep goes underneath.
        assertEquals(2.0, (SvgPath.parse("M0 0A2 2 0 0 0 4 0")[1] as SvgPath.Seg.Cubic).y, 1e-9)
        // Radii too small to reach grow until they just do: the same half circle.
        assertEquals(-2.0, (SvgPath.parse("M0 0A1 1 0 0 1 4 0")[1] as SvgPath.Seg.Cubic).y, 1e-9)
        // A quarter arc, relative, as the icons write them: the mic's cap starts at (12, 2.5).
        val cap = SvgPath.parse("M12 2.5a3.5 3.5 0 0 0-3.5 3.5")[1] as SvgPath.Seg.Cubic
        assertEquals(8.5, cap.x, 0.0); assertEquals(6.0, cap.y, 0.0)
        // A zero radius is a straight line; an arc to where it already is draws nothing.
        assertEquals(listOf(SvgPath.Seg.Move(0.0, 0.0), SvgPath.Seg.Line(3.0, 4.0)), SvgPath.parse("M0 0A0 5 0 0 1 3 4"))
        assertEquals(listOf(SvgPath.Seg.Move(1.0, 1.0)), SvgPath.parse("M1 1A5 5 0 0 1 1 1"))
    }

    @Test fun arcFlagsMayBeWrittenWithoutSpaces() {
        assertEquals(SvgPath.parse("M0 0A2 2 0 0 1 4 0"), SvgPath.parse("M0 0A2 2 0 014 0"))
    }

    @Test fun malformedPathDataThrows() {
        for (d in listOf("6 15l6-6", "M6", "M0 0A2 2 0 2 1 4 0", "M0 0z 5 5", "M0 0X1 1")) {
            try { SvgPath.parse(d); fail("parsed \"$d\"") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun eachIconStyleDrawsAsTheContractSays() {
        assertEquals(SdkIcons.Treatment(squareCaps = false, bodyFillAlpha = 0.0, bodyStroke = 2.0, lineStroke = 2.0), SdkIcons.treatment("outline", 2.0))
        assertEquals(SdkIcons.Treatment(squareCaps = true, bodyFillAlpha = 0.0, bodyStroke = 1.5, lineStroke = 1.5), SdkIcons.treatment("sharp", 1.5))
        assertEquals(SdkIcons.Treatment(squareCaps = false, bodyFillAlpha = 1.0, bodyStroke = 1.5, lineStroke = 2.0), SdkIcons.treatment("filled", 2.0))
        assertEquals("0.75 × the stroke, at least 1", 1.0, SdkIcons.treatment("filled", 1.25).bodyStroke, 0.0)
        assertEquals(SdkIcons.Treatment(squareCaps = false, bodyFillAlpha = 0.28, bodyStroke = 1.5, lineStroke = 1.5), SdkIcons.treatment("duotone", 1.5))
        assertEquals("a style this build does not know is outline", SdkIcons.treatment("outline", 2.0), SdkIcons.treatment("glow", 2.0))
        // Every style the file's designs use is one of the four.
        assertTrue(allTokens().map { it.icons.style }.toSet().containsAll(listOf("outline", "sharp", "filled", "duotone")))
    }
}

/** THE VOICE BAR (contract/DESIGN.md → Voice bar, Chat window): its numbers, its words, its meter. */
class VoiceBarTest {
    private val base = VoiceBar.StatusInput()

    @Test fun theFrameIsTheContractsSizes() {
        assertEquals(60.0, VoiceBar.HEIGHT, 0.0)
        assertEquals(9.0, VoiceBar.PADDING, 0.0)
        assertEquals(10.0, VoiceBar.GAP, 0.0)
        assertEquals(38.0, VoiceBar.BUTTON, 0.0)
        assertEquals(18.0, VoiceBar.ICON, 0.0)
        assertEquals(18.0, VoiceBar.AVATAR_GLYPH, 0.0)
        assertEquals(15.0, VoiceBar.INITIAL_SP, 0.0)
        assertEquals(700, VoiceBar.INITIAL_WEIGHT)
        assertEquals(14.0, VoiceBar.NAME_SP, 0.0)
        assertEquals(0.4f, VoiceBar.DISABLED_ALPHA, 0f)
        assertEquals(8.0, VoiceBar.STACK_GAP, 0.0)
        assertEquals(12.0, VoiceBar.CAPTION_PAD_H, 0.0)
        assertEquals(8.0, VoiceBar.CAPTION_PAD_V, 0.0)
        assertEquals(13.0, VoiceBar.CAPTION_SP, 0.0)
        assertEquals(2, VoiceBar.CAPTION_LINES)
        assertEquals(0.75f, VoiceBar.LIVE_ALPHA, 0f)
        assertEquals(10.5, VoiceBar.YOU_SP, 0.0)
        assertEquals("spaced 0.6", 0.6, VoiceBar.YOU_TRACKING_EM * VoiceBar.YOU_SP, 1e-12)
        assertEquals(8.0, VoiceBar.RISE, 0.0)
    }

    @Test fun theChatWindowIsTheContractsSizes() {
        assertEquals(50.0, VoiceBar.CHAT_HEADER, 0.0)
        assertEquals(30.0, VoiceBar.CHAT_MARK, 0.0)
        assertEquals(16.0, VoiceBar.CHAT_TITLE_MAX_SP, 0.0)
        assertEquals(6.0, VoiceBar.ONLINE_DOT, 0.0)
        assertEquals(0.84, VoiceBar.BUBBLE_MAX_SHARE, 0.0)
        assertEquals(58.0, VoiceBar.COMPOSER, 0.0)
        assertEquals(40.0, VoiceBar.FIELD, 0.0)
        assertEquals(40.0, VoiceBar.SEND, 0.0)
    }

    // ── the status words, first match wins ───────────────────────────────────

    @Test fun everyStatusWinsOverEverythingAfterItAndLosesToEverythingBefore() {
        // Everything at once, then taken away one condition at a time, in the contract's order.
        var s = VoiceBar.StatusInput(
            error = true, chat = true, chatOnline = true, online = false, reconnecting = true,
            connectivity = Connectivity.FAILED, micDenied = true, ready = false, connecting = true,
            muted = true, connected = true, thinking = true, speaking = true, quality = ConnectionQuality.POOR,
        )
        val seen = mutableListOf<VoiceBar.Status>()
        fun see() { seen += VoiceBar.status(s) }
        see(); s = s.copy(error = false)
        see(); s = s.copy(chat = false)
        see(); s = s.copy(online = true)
        see(); s = s.copy(reconnecting = false)
        see(); s = s.copy(micDenied = false)
        see(); s = s.copy(connectivity = Connectivity.READY)
        see(); s = s.copy(ready = true)
        see(); s = s.copy(connecting = false)
        see(); s = s.copy(muted = false)
        see(); s = s.copy(thinking = false)
        see(); s = s.copy(speaking = false)
        see(); s = s.copy(quality = ConnectionQuality.GOOD)
        see(); s = s.copy(connected = false)
        see()
        assertEquals(listOf(
            VoiceBar.Status("Unavailable"),
            VoiceBar.Status("Online"),
            VoiceBar.Status("No connection"),
            VoiceBar.Status("Reconnecting…", "reconnecting"),
            VoiceBar.Status("Needs microphone"),
            VoiceBar.Status("Couldn’t load · tap to retry"),
            VoiceBar.Status("Connecting…", "connecting"),
            VoiceBar.Status("Connecting…", "connecting"),
            VoiceBar.Status("Muted"),
            VoiceBar.Status("Thinking…", "thinking"),
            VoiceBar.Status("Speaking", meter = true),
            VoiceBar.Status("Weak connection"),
            VoiceBar.Status("Listening", meter = true),
            VoiceBar.Status("Tap to talk"),
        ), seen)
    }

    @Test fun aFailedLoadSaysSoWithTheTypographersApostrophe() {
        val failed = VoiceBar.status(base.copy(connectivity = Connectivity.FAILED, ready = false))
        assertEquals("Couldn\u2019t load · tap to retry", failed.text)
        assertEquals("U+2019, as React Native writes it", '\u2019', failed.text[6])
        assertNull("a failed load is words, not the loader", failed.loader)
    }

    @Test fun theChatSaysOnlineOrWaitsWithTheConnectingLoader() {
        assertEquals(VoiceBar.Status("Online"), VoiceBar.status(base.copy(chat = true, chatOnline = true, connected = true)))
        assertEquals(VoiceBar.Status("Connecting…", "connecting"), VoiceBar.status(base.copy(chat = true, online = false)))
    }

    @Test fun thinkingIsTheLoaderAndOnlyOnACall() {
        val thinking = VoiceBar.status(base.copy(connected = true, thinking = true))
        assertEquals("Thinking…", thinking.text)
        assertEquals("the dashboard's words for this moment, not a meter over silence", "thinking", thinking.loader)
        assertFalse(thinking.meter)
        // It outranks the agent's line arriving and a weak network, and yields to muted.
        assertEquals("thinking", VoiceBar.status(base.copy(connected = true, thinking = true, speaking = true, quality = ConnectionQuality.POOR)).loader)
        assertEquals(VoiceBar.Status("Muted"), VoiceBar.status(base.copy(connected = true, thinking = true, muted = true)))
        assertEquals(VoiceBar.Status("Tap to talk"), VoiceBar.status(base.copy(connected = false, thinking = true)))
    }

    @Test fun theRetryingPageIsReconnectingAndAnUnknownNetworkIsNotOffline() {
        assertEquals(VoiceBar.Status("Reconnecting…", "reconnecting"), VoiceBar.status(base.copy(connectivity = Connectivity.RETRYING)))
        assertEquals(VoiceBar.Status("Tap to talk"), VoiceBar.status(base.copy(online = null)))
    }

    @Test fun onlyListeningAndSpeakingCarryTheMeterAndOnlyWaitingIsTheLoader() {
        for (s in listOf(
            base, base.copy(connected = true), base.copy(connected = true, speaking = true), base.copy(connected = true, thinking = true),
            base.copy(connecting = true), base.copy(reconnecting = true), base.copy(muted = true), base.copy(error = true),
        )) {
            val st = VoiceBar.status(s)
            assertEquals(st.toString(), st.text == "Listening" || st.text == "Speaking", st.meter)
            assertEquals(st.toString(), st.text.endsWith("…"), st.loader != null)
            // Sentence case, as the contract writes them.
            assertTrue(st.text, st.text.first().isUpperCase())
        }
    }

    @Test fun theMicrophoneCannotActWhileConnectingNotReadyRefusedOrMisconfigured() {
        assertFalse(VoiceBar.micBlocked(error = false, micDenied = false, connecting = false, ready = true, connectivity = Connectivity.READY))
        assertTrue(VoiceBar.micBlocked(error = true, micDenied = false, connecting = false, ready = true, connectivity = Connectivity.READY))
        assertTrue(VoiceBar.micBlocked(error = false, micDenied = true, connecting = false, ready = true, connectivity = Connectivity.READY))
        assertTrue(VoiceBar.micBlocked(error = false, micDenied = false, connecting = true, ready = true, connectivity = Connectivity.READY))
        assertTrue(VoiceBar.micBlocked(error = false, micDenied = false, connecting = false, ready = false, connectivity = Connectivity.LOADING))
        assertFalse("after a failed load it retries", VoiceBar.micBlocked(error = false, micDenied = false, connecting = false, ready = false, connectivity = Connectivity.FAILED))
    }

    // ── the call's phase ─────────────────────────────────────────────────────

    @Test fun thePhaseIsTheReactNativeLaunchersInTheSameOrder() {
        fun of(reconnecting: Boolean = false, c: Connectivity = Connectivity.READY, connecting: Boolean = false, ready: Boolean = true,
               connected: Boolean = true, speaking: Boolean = false, awaiting: Boolean = false) =
            CallPhase.of(reconnecting, c, connecting, ready, connected, speaking, awaiting)
        assertEquals(CallPhase.RECONNECTING, of(reconnecting = true, connecting = true, speaking = true))
        assertEquals(CallPhase.RECONNECTING, of(c = Connectivity.FAILED))
        assertEquals(CallPhase.RECONNECTING, of(c = Connectivity.RETRYING))
        assertEquals(CallPhase.CONNECTING, of(connecting = true, awaiting = true))
        assertEquals(CallPhase.CONNECTING, of(ready = false, connected = false))
        assertEquals(CallPhase.IDLE, of(connected = false, awaiting = true))
        assertEquals(CallPhase.SPEAKING, of(speaking = true, awaiting = true))
        assertEquals(CallPhase.THINKING, of(awaiting = true))
        assertEquals(CallPhase.LISTENING, of())
    }

    // ── the meter ────────────────────────────────────────────────────────────

    @Test fun theMeterIsFourRoundBarsOfTheContractsHeights() {
        assertEquals(listOf(0.55, 1.0, 0.7, 0.4), VoiceBar.METER_BARS)
        assertEquals(12.0, VoiceBar.METER_HEIGHT, 0.0)
        assertEquals(2.5, VoiceBar.METER_BAR_WIDTH, 0.0)
        assertEquals(2.0, VoiceBar.METER_BAR_GAP, 0.0)
        assertEquals(0.35, VoiceBar.METER_LOW, 0.0)
        assertEquals(90.0, VoiceBar.METER_STAGGER_MS, 0.0)
    }

    @Test fun eachLegIsTheDesignsDurationTimesThreePointTwoAndNeverUnder420() {
        assertEquals(420.0, VoiceBar.meterLegMs(0), 0.0)
        assertEquals(420.0, VoiceBar.meterLegMs(120), 0.0)
        assertEquals(480.0, VoiceBar.meterLegMs(150), 1e-9)
        assertEquals(640.0, VoiceBar.meterLegMs(200), 1e-9)
        assertEquals(1600.0, VoiceBar.meterLegMs(500), 1e-9)
        // Every design in the file moves at its own pace, or not at all.
        for (t in allTokens()) {
            val leg = VoiceBar.meterLegMs(t.motion.durationMs)
            assertTrue(leg >= 420.0)
            assertEquals(maxOf(420.0, t.motion.durationMs * 3.2), leg, 1e-9)
        }
    }

    @Test fun theMeterIsStillWhenTheDesignOrThePhoneSaysNoMotion() {
        assertTrue(VoiceBar.meterMoves(ThemeMotion(200, "standard", 0.0), 1f))
        assertTrue("a faster animator scale still moves", VoiceBar.meterMoves(ThemeMotion(200, "standard", 0.0), 0.5f))
        assertFalse(VoiceBar.meterMoves(ThemeMotion(0, "none", 0.0), 1f))
        assertFalse(VoiceBar.meterMoves(ThemeMotion(0, "standard", 0.0), 1f))
        assertFalse(VoiceBar.meterMoves(ThemeMotion(200, "none", 0.0), 1f))
        assertFalse("Remove animations", VoiceBar.meterMoves(ThemeMotion(200, "standard", 0.0), 0f))
        // High Contrast and Retro Terminal have no motion: still.
        val still = cases().map { parsed(it.getJSONObject("design")).light }.filter { it.motion.durationMs == 0 }
        assertTrue(still.isNotEmpty())
        for (t in still) assertFalse(t.preset, VoiceBar.meterMoves(t.motion, 1f))
    }

    @Test fun eachBarRisesFromLowToFullAndBackEachOneNinetyMillisecondsBehind() {
        val leg = 640.0
        assertEquals(0.35, VoiceBar.meterScale(0, 0.0, leg), 1e-12)
        assertEquals(1.0, VoiceBar.meterScale(0, leg, leg), 1e-9)
        assertEquals(0.35, VoiceBar.meterScale(0, 2 * leg, leg), 1e-9)
        assertEquals(1.0, VoiceBar.meterScale(0, 3 * leg, leg), 1e-9)
        assertEquals("halfway up", 0.675, VoiceBar.meterScale(0, leg / 2, leg), 1e-9)
        assertEquals("halfway down", 0.675, VoiceBar.meterScale(0, leg * 1.5, leg), 1e-9)
        for (i in 1..3) {
            val start = i * 90.0
            assertEquals("bar $i waits low", 0.35, VoiceBar.meterScale(i, start - 1, leg), 1e-12)
            assertEquals(0.35, VoiceBar.meterScale(i, start, leg), 1e-12)
            assertEquals("bar $i is full one leg after its start", 1.0, VoiceBar.meterScale(i, start + leg, leg), 1e-9)
        }
        // Up is monotonic; down mirrors it.
        var last = 0.0
        for (k in 0..64) {
            val v = VoiceBar.meterScale(0, leg * k / 64.0, leg)
            assertTrue(v >= last - 1e-12); last = v
            assertEquals(v, VoiceBar.meterScale(0, 2 * leg - leg * k / 64.0, leg), 1e-9)
        }
    }

    @Test fun theCurveIsCssEaseInOut() {
        // Sampled from cubic-bezier(0.42, 0, 0.58, 1) by bisection, independently of the solver.
        for ((x, y) in listOf(0.0 to 0.0, 0.1 to 0.019722, 0.25 to 0.129162, 0.5 to 0.5, 0.75 to 0.870838, 0.9 to 0.980278, 1.0 to 1.0)) {
            assertEquals("x=$x", y, VoiceBar.easeInOut(x), 1e-6)
        }
    }

    // ── the chat's bubbles, the call-or-chat policy, the accent's chip strength ──

    @Test fun eachBubbleStyleDrawsAsTheContractSays() {
        for (t in allTokens()) {
            val c = t.colors
            val you = VoiceBar.bubble(t, customer = true)
            val agent = VoiceBar.bubble(t, customer = false)
            when (t.bubbles) {
                "filled" -> {
                    assertEquals(VoiceBar.Bubble(c.userBubble, c.userBubbleText, null), you)
                    assertEquals(VoiceBar.Bubble(c.agentBubble, c.agentBubbleText, null), agent)
                }
                "outline" -> {
                    assertEquals(VoiceBar.Bubble(null, c.text, c.accent), you)
                    assertEquals(VoiceBar.Bubble(null, c.text, c.border), agent)
                }
                "minimal" -> {
                    assertEquals(VoiceBar.Bubble(c.accentSoft, c.text, null), you)
                    assertEquals(VoiceBar.Bubble(null, c.text, null, plain = true), agent)
                }
                else -> fail("unknown bubble style ${t.bubbles}")
            }
        }
        assertEquals(setOf("filled", "outline", "minimal"), allTokens().map { it.bubbles }.toSet())
    }

    @Test fun theDashboardSaysWhichSurfacesExistAndWhichOpens() {
        assertEquals(VoiceBar.Surfaces(listOf("voice", "chat"), "voice", true), VoiceBar.surfaces(null))
        assertEquals(VoiceBar.Surfaces(listOf("voice", "chat"), "voice", true), VoiceBar.surfaces(JSONObject().put("mode", JSONObject.NULL)))
        assertEquals(VoiceBar.Surfaces(listOf("voice"), "voice", false), VoiceBar.surfaces(JSONObject().put("mode", "Voice").put("start", "chat")))
        assertEquals(VoiceBar.Surfaces(listOf("chat"), "chat", false), VoiceBar.surfaces(JSONObject().put("mode", "chat")))
        assertEquals(VoiceBar.Surfaces(listOf("voice", "chat"), "chat", true), VoiceBar.surfaces(JSONObject().put("start", "CHAT")))
        assertEquals("the customer's last switch wins", "chat", VoiceBar.surfaces(JSONObject().put("start", "voice"), prefer = "chat").start)
        assertEquals("a preference the dashboard does not allow is ignored", "voice", VoiceBar.surfaces(JSONObject().put("mode", "voice"), prefer = "chat").start)
    }

    @Test fun anAccentsChipStrengthIsTheMixTheServerMakes() {
        // Every design's accentSoft is its surface mixed toward its accent — the same mix a host's accent gets.
        for (t in allTokens()) {
            assertEquals("${t.preset} ${t.scheme}", t.colors.accentSoft, VoiceBar.mixHex(t.colors.surface, t.colors.accent, if (t.scheme == "light") 0.12 else 0.22))
            assertEquals("the design's own accent keeps its own chip", t.colors.accentSoft, VoiceBar.softFor(t.colors.accent.lowercase(), t))
        }
        val classic = Design.CLASSIC_LIGHT
        assertEquals(VoiceBar.mixHex(classic.colors.surface, "#7C3AED", 0.12), VoiceBar.softFor("#7c3aed", classic))
        assertEquals("#EFE7FD", VoiceBar.softFor("#7C3AED", classic))
        assertEquals("#2D1F49", VoiceBar.softFor("#7C3AED", Design.CLASSIC_DARK))
        assertEquals("not a colour: the design's own", classic.colors.accentSoft, VoiceBar.softFor("purple", classic))
    }
}
