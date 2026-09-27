package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * WINDOW CHROME (contract/DESIGN.md → Window chrome): every design in
 * design.json says "none" or "window", and this SDK reads exactly what it
 * says; a "window" design gets the title bar the contract measures on each
 * surface; a "none" design, an older server's tokens and no design at all get
 * none.
 */
class WindowChromeTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/design.json").exists() && !File(dir, "contract/design.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/design.json"), File(dir, "contract/design.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/design.json missing — run sdk/contract/generate-design.mjs")
        JSONObject(f.readText())
    }

    private fun cases(): List<JSONObject> = fx.getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun parse(o: JSONObject): ResolvedDesign = ResolvedDesign.parse(o) ?: error("did not parse: $o")

    /** Both token sets of every case, each with the chrome its JSON spells. `getString`: a set without one fails here. */
    private fun tokenSets(): List<Pair<String, ThemeTokens>> = cases().flatMap { c ->
        val d = c.getJSONObject("design")
        val parsed = parse(d)
        listOf(d.getJSONObject("light").getString("chrome") to parsed.light, d.getJSONObject("dark").getString("chrome") to parsed.dark)
    }

    private val desktopBuddy by lazy { cases().first { it.getJSONObject("config").let { c -> c.length() == 1 && c.getString("preset") == "desktop-buddy" } }.getJSONObject("design") }

    private val windowTokens by lazy { tokenSets().filter { it.first == WindowChrome.WINDOW }.map { it.second } }

    @Test fun theNumbersAreTheContracts() {
        assertEquals(listOf("none", "window"), Design.CHROMES)
        assertEquals(32.0, WindowChrome.HEIGHT, 0.0)
        assertEquals(24.0, WindowChrome.CAPTION_HEIGHT, 0.0)
        assertEquals(10.0, WindowChrome.LIGHT, 0.0)
        assertEquals(8.0, WindowChrome.CAPTION_LIGHT, 0.0)
        assertEquals(6.0, WindowChrome.LIGHT_GAP, 0.0)
        assertEquals(12.0, WindowChrome.INSET, 0.0)
        assertEquals(listOf("#FF5F57", "#FEBC2E", "#28C840"), WindowChrome.LIGHTS)
        assertEquals("the chat's online dot, as the header's", VoiceBar.ONLINE_DOT, WindowChrome.ONLINE_DOT, 0.0)
        assertEquals("between the online dot and the heading", 6.0, WindowChrome.ONLINE_GAP, 0.0)
        assertEquals("monospace", WindowChrome.MONOSPACE)
        assertEquals("captions", WindowChrome.CAPTION_TITLE)
    }

    @Test fun everyCaseSaysItsChromeAndIsReadExactly() {
        for (c in cases()) {
            val d = c.getJSONObject("design")
            val parsed = parse(d)
            for ((scheme, t) in listOf("light" to parsed.light, "dark" to parsed.dark)) {
                val want = d.getJSONObject(scheme).getString("chrome")
                assertTrue("${c.getJSONObject("config")} $scheme: $want", want in Design.CHROMES)
                assertEquals("${c.getJSONObject("config")} $scheme", want, t.chrome)
                assertEquals(want == "window", WindowChrome.windowed(t))
            }
            // The knob, when the brand turned it, is kept as the session spelled it.
            val config = d.getJSONObject("config")
            assertEquals(if (config.has("chrome")) config.getString("chrome") else null, parsed.config.chrome)
        }
        // Not a vacuous pass: the file has both.
        assertEquals(setOf("none", "window"), tokenSets().map { it.first }.toSet())
    }

    @Test fun desktopBuddyOpensInWindowsAndAnyDesignCanTurnItOn() {
        val buddy = parse(desktopBuddy)
        assertEquals("window", buddy.light.chrome)
        assertEquals("window", buddy.dark.chrome)
        // Graine Classic with every knob turned, chrome among them.
        val allKnobs = cases().map { it.getJSONObject("design") }.first { it.getJSONObject("config").optString("chrome") == "window" }
        assertEquals("graine-classic", allKnobs.getJSONObject("config").getString("preset"))
        assertEquals("window", parse(allKnobs).light.chrome)
        assertEquals("window", parse(allKnobs).dark.chrome)
        assertEquals("window", parse(allKnobs).config.chrome)
    }

    @Test fun aWindowDesignGetsTheContractsTitleBarOnEverySurface() {
        assertTrue("both schemes of both window cases", windowTokens.size >= 4)
        for (t in windowTokens) {
            val name = "${t.preset} ${t.scheme}"
            fun common(b: WindowChrome.TitleBar) {
                assertEquals(name, 6.0, b.lightGap, 0.0)
                assertEquals(name, 12.0, b.inset, 0.0)
                assertEquals("always these three, whatever the brand", listOf("#FF5F57", "#FEBC2E", "#28C840"), b.lights)
                assertEquals(name, t.colors.surfaceRaised, b.fill)
                assertEquals(name, t.colors.border, b.outline)
                assertEquals(name, t.shape.borderWidth, b.outlineWidth, 0.0)
                assertEquals(name, t.colors.textMuted, b.titleColor)
                assertEquals(name, t.font.scale.caption, b.titleSp, 0.0)
                assertEquals(name, "monospace", b.titleFamily)
            }

            val chat = WindowChrome.titleBar(t, WindowChrome.Surface.CHAT, "Desktop Buddy", online = true)!!
            common(chat)
            assertEquals(32.0, chat.height, 0.0)
            assertEquals(10.0, chat.light, 0.0)
            assertEquals("the heading, lower case", "desktop buddy", chat.title)
            assertEquals("after the success dot while online", t.colors.success, chat.onlineDot)
            val offline = WindowChrome.titleBar(t, WindowChrome.Surface.CHAT, "Desktop Buddy", online = false)!!
            assertNull("offline: no dot", offline.onlineDot)
            assertEquals("offline: the heading alone, no \"Connecting…\"", "desktop buddy", offline.title)

            val caption = WindowChrome.titleBar(t, WindowChrome.Surface.CAPTION, "Whatever is being said", online = true)!!
            common(caption)
            assertEquals(24.0, caption.height, 0.0)
            assertEquals(8.0, caption.light, 0.0)
            assertEquals("captions", caption.title)
            assertNull(caption.onlineDot)
            assertEquals("captions", WindowChrome.titleBar(t, WindowChrome.Surface.CAPTION)!!.title)

            val component = WindowChrome.titleBar(t, WindowChrome.Surface.COMPONENT, "Three That Fit Your Budget", online = true)!!
            common(component)
            assertEquals(32.0, component.height, 0.0)
            assertEquals(10.0, component.light, 0.0)
            assertEquals("its own title, lower case", "three that fit your budget", component.title)
            assertNull(component.onlineDot)
            assertEquals("a component with no title: the lights alone", "", WindowChrome.titleBar(t, WindowChrome.Surface.COMPONENT)!!.title)
        }
    }

    @Test fun aComponentsOwnTitleMovesIntoTheBarAndIsNotDrawnAgain() {
        for (t in windowTokens) {
            val bar = WindowChrome.titleBar(t, WindowChrome.Surface.COMPONENT, "Pick a plan")
            assertEquals("pick a plan", bar!!.title)
            assertEquals("not drawn again in the body", "", WindowChrome.bodyTitle(bar, "Pick a plan"))
        }
        // A plain card keeps its title in its body.
        for (t in listOf(Design.CLASSIC_LIGHT, Design.CLASSIC_DARK, null)) {
            val bar = WindowChrome.titleBar(t, WindowChrome.Surface.COMPONENT, "Pick a plan")
            assertEquals("Pick a plan", WindowChrome.bodyTitle(bar, "Pick a plan"))
        }
    }

    @Test fun aWindowedCardsBodyIsSurfaceAndAPlainCardsIsSurfaceRaised() {
        var windows = 0
        for ((chrome, t) in tokenSets()) {
            val name = "${t.preset} ${t.scheme} ($chrome)"
            if (chrome == "window") { windows++; assertEquals(name, t.colors.surface, WindowChrome.cardFill(t)) }
            else assertEquals(name, t.colors.surfaceRaised, WindowChrome.cardFill(t))
        }
        assertTrue(windows >= 4)
        for (t in listOf(Design.CLASSIC_LIGHT, Design.CLASSIC_DARK)) assertEquals(t.colors.surfaceRaised, WindowChrome.cardFill(t))
        // Desktop Buddy: a white window under a grey bar; in the dark, #161618 under #1F1F22.
        val buddy = parse(desktopBuddy)
        assertEquals("#FFFFFF", WindowChrome.cardFill(buddy.light))
        assertEquals("#F5F5F5", WindowChrome.titleBar(buddy.light, WindowChrome.Surface.COMPONENT, "x")!!.fill)
        assertEquals("#161618", WindowChrome.cardFill(buddy.dark))
        assertEquals("#1F1F22", WindowChrome.titleBar(buddy.dark, WindowChrome.Surface.COMPONENT, "x")!!.fill)
        // The plain colour, as every SDK draws it: glass does not thin a window's body.
        for (t in windowTokens.filter { it.glass != null }) assertEquals(0xFF, Appearance.argb(WindowChrome.cardFill(t))!! ushr 24)
    }

    @Test fun theBarIsSolidSurfaceRaisedEvenUnderGlass() {
        val glassy = windowTokens.filter { it.glass != null }
        assertTrue("the all-knobs case is glass and a window", glassy.isNotEmpty())
        for (t in glassy) for (s in WindowChrome.Surface.values()) {
            val bar = WindowChrome.titleBar(t, s)!!
            assertEquals(t.colors.surfaceRaised, bar.fill)
            assertEquals("opaque: the glass's surfaceOpacity is not applied", 0xFF, Appearance.argb(bar.fill)!! ushr 24)
        }
    }

    @Test fun theTitleIsCentredInTheWholeBarClearOfTheLightsAndTheChevron() {
        val t = windowTokens.first()
        // 12 in, three lights, two gaps between them, one gap after: 12 + 30 + 12 + 6.
        val chat = WindowChrome.titleBar(t, WindowChrome.Surface.CHAT, "x")!!
        assertEquals(60.0, chat.titleInset, 0.0)
        // The caption's lights are 8: 12 + 24 + 12 + 6.
        assertEquals(54.0, WindowChrome.titleBar(t, WindowChrome.Surface.CAPTION)!!.titleInset, 0.0)
        // The same inset on the right keeps it off the chevron at the chat's right end: 12 in, an icon across.
        assertTrue(chat.titleInset >= WindowChrome.INSET + VoiceBar.ICON)
    }

    @Test fun aPlainDesignAnOlderServerAndNoDesignGetNoTitleBar() {
        val plain = tokenSets().filter { it.first == WindowChrome.NONE }.map { it.second }
        assertTrue(plain.size >= 4)
        for (t in plain + Design.CLASSIC_LIGHT + Design.CLASSIC_DARK) {
            assertFalse(t.preset, WindowChrome.windowed(t))
            for (s in WindowChrome.Surface.values()) assertNull("${t.preset} $s", WindowChrome.titleBar(t, s, "Title", online = true))
        }
        // No design at all — a server that predates designs: plain surfaces.
        assertFalse(WindowChrome.windowed(null))
        for (s in WindowChrome.Surface.values()) assertNull(WindowChrome.titleBar(null, s, "Title", online = true))
    }

    @Test fun tokensWithoutChromeReadAsNone() {
        // An older server: its token sets have no `chrome` at all.
        val older = JSONObject(desktopBuddy.toString())
        older.getJSONObject("light").remove("chrome")
        older.getJSONObject("dark").remove("chrome")
        val parsed = parse(older)
        assertEquals("none", parsed.light.chrome)
        assertEquals("none", parsed.dark.chrome)
        assertNull(WindowChrome.titleBar(parsed.light, WindowChrome.Surface.CHAT, "Buddy", online = true))
        // Only the chrome changed: every other token is read as before.
        assertEquals(parse(desktopBuddy).light.copy(chrome = "none"), parsed.light)
        // A design as small as a design can be.
        assertEquals("none", ResolvedDesign.parse("""{"light":{"colors":{"accent":"#7C3AED"}}}""")!!.light.chrome)
        assertEquals("none", ResolvedDesign.parse("""{"light":{"colors":{"accent":"#7C3AED"}}}""")!!.dark.chrome)
    }

    @Test fun aChromeThisBuildDoesNotKnowIsAPlainSurface() {
        for (v in listOf<Any>("skeuomorphic", "", 1, true, JSONObject(), JSONObject.NULL)) {
            val d = JSONObject(desktopBuddy.toString())
            d.getJSONObject("light").put("chrome", v)
            val t = parse(d)
            assertEquals("chrome $v", "none", t.light.chrome)
            assertEquals("the other scheme keeps its own", "window", t.dark.chrome)
        }
        // Spaces and case are forgiven, as for every word token.
        val d = JSONObject(desktopBuddy.toString())
        d.getJSONObject("light").put("chrome", " Window ")
        assertEquals("window", parse(d).light.chrome)
    }

    @Test fun theChromeIsWrittenBackOut() {
        assertEquals("window", parse(desktopBuddy).light.toJson().getString("chrome"))
        assertEquals("none", Design.CLASSIC_LIGHT.toJson().getString("chrome"))
        assertEquals("window", DesignConfig(chrome = "window").toJson().getString("chrome"))
        assertFalse("a knob not turned is not written", DesignConfig().toJson().has("chrome"))
    }

    @Test fun aTitleIsOneLineInLowerCaseWhateverThePhonesLanguage() {
        assertEquals("aria", WindowChrome.title("Aria"))
        assertEquals("desktop buddy", WindowChrome.title("  Desktop\n BUDDY\t"))
        assertEquals("", WindowChrome.title(null))
        assertEquals("", WindowChrome.title(" \n "))
        val saved = Locale.getDefault()
        try {
            // Lower case in Turkish would make this "tıtle".
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertEquals("title", WindowChrome.title("TITLE"))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
