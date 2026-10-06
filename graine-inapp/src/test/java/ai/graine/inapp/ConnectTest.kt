package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A clock the tests turn by hand: [ConnectProgress] is handed [schedule] in
 * place of the main thread's Handler, so "four seconds later" is exact and
 * instant — nothing here depends on how loaded the machine running it is.
 */
private class HandClock {
    private class Task(val due: Long, val run: () -> Unit, var cancelled: Boolean = false)

    var now = 0L
        private set
    private val tasks = mutableListOf<Task>()

    val schedule: (Long, () -> Unit) -> (() -> Unit) = { delayMs, run ->
        val task = Task(now + delayMs, run)
        tasks += task
        ({ task.cancelled = true })
    }

    /** Tasks still waiting to run. */
    val pending: Int get() = tasks.count { !it.cancelled }

    fun advance(ms: Long) {
        val until = now + ms
        while (true) {
            val next = tasks.filter { !it.cancelled && it.due <= until }.minByOrNull { it.due } ?: break
            tasks.remove(next)
            now = next.due
            next.run()
        }
        now = until
    }
}

/**
 * A CONNECT THAT SAYS WHERE IT IS — `graine:progress` (contract/DESIGN.md
 * "Connecting"), the React Native launcher's connectStage, slowConnect and
 * callFailed (sdk/inapp/test/render/connect-progress.test.mjs).
 */
class ConnectProgressTest {
    private val clock = HandClock()
    private var changes = 0
    private fun progress(slowAfterMs: Long = 4_000) = ConnectProgress(slowAfterMs, clock.schedule) { changes++ }

    private fun stage(raw: String) = (Protocol.parse("""{"type":"graine:progress","stage":"$raw"}""") as PageMessage.Progress).stage

    @Test fun theEnginesFrameIsParsedWithItsStageAsWritten() {
        assertEquals(PageMessage.Progress("dialling"), Protocol.parse("""{"type":"graine:progress","stage":"dialling"}"""))
        assertEquals("a stage this build does not know still arrives; it is ignored where it is handled",
            PageMessage.Progress("teleporting"), Protocol.parse("""{"type":"graine:progress","stage":"teleporting"}"""))
        assertEquals(PageMessage.Progress(""), Protocol.parse("""{"type":"graine:progress"}"""))
    }

    @Test fun theFourStagesAreTheEnginesWordsAndNothingElseIsOne() {
        assertEquals(listOf("registering", "dialling", "retrying", "switching"), ConnectStage.values().map { it.wire })
        for (s in ConnectStage.values()) assertEquals(s, ConnectStage.from(s.wire))
        for (junk in listOf(null, "", "failed", "DIALLING", " dialling", "connected")) assertNull("$junk", ConnectStage.from(junk))
    }

    @Test fun theEnginesStagesReachTheStateAndAnUnknownOneIsIgnored() {
        val p = progress()
        assertNull(p.stage)
        assertFalse(p.onStage(stage("registering")))
        assertEquals(ConnectStage.REGISTERING, p.stage)
        p.onStage(stage("dialling"))
        assertEquals(ConnectStage.DIALLING, p.stage)
        p.onStage(stage("retrying"))
        assertEquals(ConnectStage.RETRYING, p.stage)
        val before = changes
        assertFalse(p.onStage(stage("nonsense")))
        assertEquals("an unknown stage is ignored, not stored", ConnectStage.RETRYING, p.stage)
        assertEquals("and nobody hears about it", before, changes)
        p.onStage(stage("switching"))
        assertEquals(ConnectStage.SWITCHING, p.stage)
        assertFalse(p.failed)
        // A connected call clears it: GraineVoice calls clear() on graine:call {connected: true}.
        p.clear()
        assertNull("a connected call still claimed to be switching", p.stage)
    }

    @Test fun failedEndsTheConnectAndTheNextAttemptForgetsIt() {
        val p = progress()
        p.onStage("switching")
        assertTrue("failed is the caller's cue to stop connecting", p.onStage(stage("failed")))
        assertTrue(p.failed)
        assertNull("a failed connect has no stage", p.stage)
        // A stage arriving after the failure does not undo it: only a start, a connected call or the bar closing does.
        p.onStage("dialling")
        assertTrue(p.failed)
        // start(), graine:call {connected}, and the last bar leaving the screen all clear.
        p.clear()
        assertFalse("a new attempt still showed the last one's failure", p.failed)
        assertNull(p.stage)
    }

    @Test fun listenersHearOfAChangeOnceAndOfNoChangeNever() {
        val p = progress()
        p.onStage("dialling"); p.onStage("dialling")
        assertEquals(1, changes)
        p.clear(); p.clear()
        assertEquals(2, changes)
        p.onStage("failed"); p.onStage("failed")
        assertEquals(3, changes)
    }

    @Test fun aWaitPastFourSecondsIsSlowAndOneThatEndsIsNot() {
        val p = progress()
        p.waiting(true)
        clock.advance(3_999)
        assertFalse("slow before the mark", p.slow)
        clock.advance(1)
        assertTrue("past the slow mark, the bar still said Connecting…", p.slow)
        assertEquals(1, changes)
        p.waiting(false)
        assertFalse("a connect that landed is not slow", p.slow)
        assertEquals(2, changes)
        assertEquals(0, clock.pending)
    }

    @Test fun aWaitThatEndsBeforeTheMarkIsNeverSlowAndLeavesNoClockBehind() {
        val p = progress()
        p.waiting(true)
        clock.advance(2_000)
        p.waiting(false)
        assertEquals("the clock was not cancelled", 0, clock.pending)
        clock.advance(60_000)
        assertFalse(p.slow)
        assertEquals(0, changes)
    }

    @Test fun theClockRunsFromWhenTheWaitBeganNotFromTheLastLook() {
        val p = progress()
        p.waiting(true)
        clock.advance(3_000)
        // Re-evaluated with nothing changed (every state change re-checks): the wait goes on, so does the clock.
        p.waiting(true)
        clock.advance(1_000)
        assertTrue(p.slow)
    }

    @Test fun aNewWaitStartsANewClock() {
        val p = progress()
        p.waiting(true); clock.advance(3_000); p.waiting(false)
        p.waiting(true); clock.advance(3_000)
        assertFalse("the first wait's three seconds were counted toward the second", p.slow)
        clock.advance(1_000)
        assertTrue(p.slow)
    }

    @Test fun theMarkIsTheAppsToMove() {
        val p = progress(slowAfterMs = 150)
        p.waiting(true)
        clock.advance(149)
        assertFalse(p.slow)
        clock.advance(1)
        assertTrue(p.slow)
        assertEquals("the default is four seconds", 4_000L, GraineConfig.Timing().slowConnectMs)
        assertEquals(150L, GraineConfig.Timing(slowConnectMs = 150).slowConnectMs)
    }

    @Test fun theCustomerIsWaitingOnlyWhileTheyAre() {
        fun waiting(connected: Boolean = false, failed: Boolean = false, connecting: Boolean = false, ready: Boolean = true, barOpen: Boolean = false) =
            ConnectProgress.waitingOnConnect(connected, failed, connecting, ready, barOpen)
        assertTrue("a call they placed", waiting(connecting = true))
        assertTrue("a start held for the page (start() says connecting at once)", waiting(connecting = true, ready = false))
        assertTrue("their bar open on a page still loading", waiting(ready = false, barOpen = true))
        assertFalse("an idle page loading behind a closed bar is nobody's slow connect", waiting(ready = false))
        assertFalse("an open bar on a ready page, nothing placed", waiting(barOpen = true))
        assertFalse("a call that landed", waiting(connected = true, connecting = true, barOpen = true))
        assertFalse("a connect that failed", waiting(failed = true, connecting = true, ready = false, barOpen = true))
    }

    @Test fun aConnectThatFailsAfterItWentSlowStopsBeingSlow() {
        // GraineVoice re-checks the wait after every frame: failed takes it out of waitingOnConnect.
        val p = progress()
        p.waiting(ConnectProgress.waitingOnConnect(false, p.failed, connecting = true, ready = true, barOpen = true))
        clock.advance(4_000)
        assertTrue(p.slow)
        p.onStage("failed")
        p.waiting(ConnectProgress.waitingOnConnect(false, p.failed, connecting = false, ready = true, barOpen = true))
        assertFalse(p.slow)
        assertTrue(p.failed)
    }
}

/**
 * WHAT THE BAR SAYS WHILE A CONNECT RUNS — the React Native bar's
 * connectingLook and statusOf (sdk/inapp/test/render/voice-bar.test.mjs, "a
 * slow connect says what is happening…").
 */
class ConnectingWordsTest {
    private val placing = VoiceBar.StatusInput(connected = false, connecting = true)
    private val classic = Loaders.resolve(null).text

    @Test fun aSlowConnectSaysWhatIsHappeningInsteadOfSpinningOnOneWord() {
        for ((input, words) in listOf(
            placing to "Connecting…",
            placing.copy(slowConnect = true) to "Still connecting…",
            placing.copy(connectStage = ConnectStage.RETRYING) to "Reconnecting…",
            placing.copy(connectStage = ConnectStage.RETRYING, slowConnect = true) to "Reconnecting…",
            placing.copy(connectStage = ConnectStage.SWITCHING) to "Reconnecting…",
            placing.copy(connectStage = ConnectStage.DIALLING, slowConnect = true) to "Still connecting…",
            placing.copy(connectStage = ConnectStage.REGISTERING) to "Connecting…",
            VoiceBar.StatusInput(connected = false, connecting = false, callFailed = true) to "Couldn’t connect · tap to retry",
        )) {
            val st = VoiceBar.status(input)
            assertEquals(input.toString(), words, VoiceBar.loaderWords(st, classic) ?: st.text)
        }
    }

    @Test fun aRetryOrASwitchIsTheReconnectingMomentInTheBrandsOwnWords() {
        val brand = Loaders.resolve(JSONObject("""{"text":{"connecting":"जुड़ रहा है…","reconnecting":"फिर से जोड़ रहे हैं…"}}""")).text
        for (stage in listOf(ConnectStage.RETRYING, ConnectStage.SWITCHING)) {
            val st = VoiceBar.status(placing.copy(connectStage = stage, connectingText = brand.connecting!!))
            assertEquals("reconnecting", st.loader)
            assertNull("the bar has no words of its own for a retry: the brand's are drawn", st.label)
            assertEquals("फिर से जोड़ रहे हैं…", VoiceBar.loaderWords(st, brand))
        }
    }

    @Test fun aBrandThatChoseItsOwnConnectingWordsKeepsThemHoweverSlowTheConnect() {
        for (own in listOf("जुड़ रहा है…", "Connecting", "Calling Asha…", "connecting…")) {
            val brand = Loaders.resolve(JSONObject().put("text", JSONObject().put("connecting", own))).text
            val st = VoiceBar.status(placing.copy(slowConnect = true, connectingText = brand.connecting!!))
            assertEquals(own, st.text)
            assertEquals("connecting", st.loader)
            assertNull("the brand's wording was replaced", st.label)
            assertEquals(own, VoiceBar.loaderWords(st, brand))
        }
    }

    @Test fun stillConnectingIsTheLoaderWithTheBarsOwnWords() {
        val st = VoiceBar.status(placing.copy(slowConnect = true))
        assertEquals(VoiceBar.Status("Still connecting…", "connecting", label = "Still connecting…"), st)
        assertEquals("only the default is replaced", Loaders.DEFAULT_TEXT.connecting, VoiceBar.DEFAULT_CONNECTING)
        // A page still loading behind an open bar is the same wait, and says the same.
        assertEquals(st, VoiceBar.status(VoiceBar.StatusInput(ready = false, slowConnect = true)))
    }

    @Test fun anOrdinaryConnectIsTheLoaderInTheBrandsWords() {
        val st = VoiceBar.status(placing)
        assertEquals(VoiceBar.Status("Connecting…", "connecting"), st)
        assertEquals("Connecting…", VoiceBar.loaderWords(st, classic))
        // Words, not the loader: a status with no moment has no loader words.
        assertNull(VoiceBar.loaderWords(VoiceBar.status(VoiceBar.StatusInput()), classic))
    }

    @Test fun aCallNothingCouldCarrySaysSoWithTheTypographersApostropheAndTheMicrophoneTriesAgain() {
        val st = VoiceBar.status(VoiceBar.StatusInput(callFailed = true))
        assertEquals("Couldn’t connect · tap to retry", st.text)
        assertEquals("U+2019, as React Native writes it", '’', st.text[6])
        assertNull("a failed connect is words, not the loader", st.loader)
        assertFalse(st.meter)
        // Not connecting any more, the page ready: the microphone can act, and off a call it starts one.
        assertFalse(VoiceBar.micBlocked(error = false, micDenied = false, connecting = false, ready = true, connectivity = Connectivity.READY))
    }

    @Test fun aFailedConnectOutranksConnectingAndYieldsToEverythingTheContractPutsFirst() {
        val failed = VoiceBar.StatusInput(callFailed = true)
        assertEquals("Couldn’t connect · tap to retry", VoiceBar.status(failed.copy(connecting = true, ready = false, slowConnect = true)).text)
        assertEquals("Couldn’t connect · tap to retry", VoiceBar.status(failed.copy(muted = true)).text)
        assertEquals("Couldn’t load · tap to retry", VoiceBar.status(failed.copy(connectivity = Connectivity.FAILED)).text)
        assertEquals("Needs microphone", VoiceBar.status(failed.copy(micDenied = true)).text)
        assertEquals("Reconnecting…", VoiceBar.status(failed.copy(reconnecting = true)).text)
        assertEquals("No connection", VoiceBar.status(failed.copy(online = false)).text)
        assertEquals("Unavailable", VoiceBar.status(failed.copy(error = true)).text)
        assertEquals("the chat has no call to fail", "Online", VoiceBar.status(failed.copy(chat = true, chatOnline = true)).text)
    }

    @Test fun theStagesSayNothingOnceTheConnectIsOver() {
        // A stage the engine left behind is not read off a call: only while connecting or not ready.
        assertEquals(VoiceBar.Status("Listening", meter = true), VoiceBar.status(VoiceBar.StatusInput(connected = true, connectStage = ConnectStage.RETRYING, slowConnect = true)))
        assertEquals(VoiceBar.Status("Tap to talk"), VoiceBar.status(VoiceBar.StatusInput(connectStage = ConnectStage.SWITCHING)))
    }

    @Test fun theWholeStoryOfAConnectThatFails() {
        // RN: "when nothing could carry the call, the connect ends and offers another try".
        val clock = HandClock()
        val p = ConnectProgress(4_000, clock.schedule) {}
        var s = placing
        fun now() = VoiceBar.status(s.copy(connectStage = p.stage, slowConnect = p.slow, callFailed = p.failed))
        p.waiting(true)
        p.onStage("dialling")
        assertEquals("Connecting…", now().text)
        clock.advance(4_000)
        assertEquals("Still connecting…", now().text)
        p.onStage("switching")
        assertEquals("Reconnecting…", now().text)
        assertTrue(p.onStage("failed"))
        s = s.copy(connecting = false)
        p.waiting(false)
        assertEquals("Couldn’t connect · tap to retry", now().text)
        // The microphone: start() forgets the failure and says connecting again.
        p.clear()
        s = s.copy(connecting = true)
        assertEquals("Connecting…", now().text)
    }
}

/** The contract file every SDK reads (sdk/contract/design.json). */
private val designFile: JSONObject by lazy {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "sdk/contract/design.json").exists() && !File(dir, "contract/design.json").exists()) dir = dir.parentFile
    JSONObject(listOf(File(dir, "sdk/contract/design.json"), File(dir, "contract/design.json")).first { it.exists() }.readText())
}

/**
 * THE BRAND'S LOGO, WHOLE AND ON ITS PLATE — contract/DESIGN.md "The brand's
 * logo", theme.ts's logoBox / logoPlateLook / normaliseLogoPlate, with the
 * React Native SDK's own expected values (sdk/inapp/test/theme.test.mjs).
 */
class LogoTest {
    @Test fun theLogoIsFittedIntoTheLargestSquareItsShapeHolds() {
        assertEquals(26, Logo.box("circle", 38.0))
        assertEquals(39, Logo.box("squircle", 56.0))
        assertEquals(44, Logo.box("rounded-square", 56.0))
        assertEquals(44, Logo.box("square", 56.0))
        assertEquals("a pill's mark is a circle its height", 39, Logo.box("pill", 56.0))
        assertEquals(21, Logo.box("circle", 30.0))
        assertEquals(24, Logo.box("rounded-square", 30.0))
        assertEquals("a shape this build does not know is a circle", 26, Logo.box("blob", 38.0))
        assertEquals(26, Logo.box(null, 38.0))
        // The largest square inside a circle is 70.7% of it: a fitted logo never touches the edge.
        for (size in listOf(30.0, 38.0, 52.0, 56.0, 64.0)) assertTrue("$size", Logo.box("circle", size) <= size / Math.sqrt(2.0))
        // Rounded down, and never to nothing.
        assertEquals(1, Logo.box("circle", 1.0))
    }

    @Test fun everyPlaceTheLogoStandsInForTheGlyphFitsItRatherThanFillingIt() {
        // The avatar used to be the logo at the whole 38: a wordmark lost both ends.
        assertEquals(26, Logo.box("circle", VoiceBar.BUTTON))
        assertTrue(Logo.box("circle", VoiceBar.BUTTON) < VoiceBar.BUTTON)
        // The chat header's 30 mark in each launcher shape (a pill drawn as a circle).
        assertEquals(listOf(21, 21, 24, 24), listOf("circle", "squircle", "rounded-square", "square").map { Logo.box(it, VoiceBar.CHAT_MARK) })
        // The launcher: the logo gets the shape's square, larger than the glyph's 46%.
        assertEquals(39, Logo.box("circle", Design.LAUNCHER_SIZE))
        assertTrue(Logo.box("circle", Design.LAUNCHER_SIZE) > Design.LAUNCHER_SIZE * Design.LAUNCHER_GLYPH)
    }

    @Test fun onlyWhiteIsTheWhitePlate() {
        assertEquals(listOf("accent", "white"), LogoPlate.values().map { it.wire })
        assertEquals(LogoPlate.WHITE, LogoPlate.from("white"))
        assertEquals(LogoPlate.ACCENT, LogoPlate.from("accent"))
        for (v in listOf(null, "", "black", 3, 1, "WHITE", " white", true, JSONObject.NULL)) assertEquals("$v is not a plate", LogoPlate.ACCENT, LogoPlate.from(v))
    }

    @Test fun theWhitePlateIsWhiteWithAHairlineAndTheAccentPlateIsTheAccent() {
        assertEquals(Logo.Plate("#318CE7", null), Logo.plate(LogoPlate.ACCENT, "#318CE7", "#E5E5E5"))
        assertEquals(Logo.Plate("#FFFFFF", Logo.Stroke(1.0, "#E5E5E5")), Logo.plate(LogoPlate.WHITE, "#318CE7", "#E5E5E5", 0.0))
        assertEquals(Logo.Stroke(3.0, "#E5E5E5"), Logo.plate(LogoPlate.WHITE, "#318CE7", "#E5E5E5", 3.0).outline)
        assertEquals("a hairline is never thinner than 1", 1.0, Logo.plate(LogoPlate.WHITE, "#318CE7", "#E5E5E5", 0.5).outline!!.width, 0.0)
    }

    @Test fun aPillsLabelOnTheWhitePlateIsTheDarkThatReadsOnWhite() {
        assertEquals("#101318", Logo.ON_WHITE)
        assertEquals(Themed.readableOnHex(Logo.PLATE_WHITE), Logo.ON_WHITE)
    }

    @Test fun theDashboardsPlateReachesTheAppearance() {
        fun plate(json: String) = Appearance.resolve(JSONObject(json), Appearance.DEFAULT_ACCENT, "Assistant").logoPlate
        assertEquals(LogoPlate.WHITE, plate("""{"logo":"org/a.png","logoPlate":"white"}"""))
        assertEquals(LogoPlate.ACCENT, plate("""{"logo":"org/a.png","logoPlate":"accent"}"""))
        assertEquals("absent: as logos were always drawn", LogoPlate.ACCENT, plate("""{"logo":"org/a.png"}"""))
        assertEquals(LogoPlate.ACCENT, Appearance.resolve(null, Appearance.DEFAULT_ACCENT, "Assistant").logoPlate)
        // 0.4.0's constructor still builds, on the accent.
        assertEquals(LogoPlate.ACCENT, NativeAppearance("#318CE7", "Ring", null, 18.0).logoPlate)
    }

    @Test fun everyDesignCanCarryALogoOnEitherPlate() {
        val cases = designFile.getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertTrue("design.json has no design whose launcher is the brand's logo",
            cases.any { ResolvedDesign.parse(it.getJSONObject("design"))!!.light.icons.launcher == null })
        for (c in cases) {
            val d = ResolvedDesign.parse(c.getJSONObject("design"))!!
            for (t in listOf(d.light, d.dark)) {
                val white = Logo.plate(LogoPlate.WHITE, t.colors.accent, t.colors.border, t.shape.borderWidth)
                assertEquals(t.colors.border, white.outline!!.color)
                assertTrue(white.outline!!.width >= 1.0 && white.outline!!.width >= t.shape.borderWidth)
                assertEquals(Logo.Plate(t.colors.accent, null), Logo.plate(LogoPlate.ACCENT, t.colors.accent, t.colors.border, t.shape.borderWidth))
                assertTrue(Logo.box(t.shape.launcher, Design.LAUNCHER_SIZE) in 39..44)
            }
        }
    }
}
