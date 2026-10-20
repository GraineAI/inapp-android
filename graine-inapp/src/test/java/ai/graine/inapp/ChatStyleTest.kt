package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.TreeMap

/**
 * THE CHAT WINDOW v2 TOKENS (contract/DESIGN.md "Chat window v2"): every
 * design in sdk/contract/design.json — each chat style on Graine Classic
 * and on other designs, among every other case — read to exactly the server's values, light
 * and dark; and a server that sends no v2 groups drawn as the classic chat,
 * derived from the v1 tokens to the very values a v2 server sends.
 */
class ChatStyleTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/design.json").exists() && !File(dir, "contract/design.json").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/design.json"), File(dir, "contract/design.json")).firstOrNull { it.exists() }
            ?: error("sdk/contract/design.json missing — this contract is never skipped")
        JSONObject(f.readText())
    }

    private fun cases(): List<JSONObject> = fx.getJSONArray("cases").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun canon(v: Any?): Any? = when {
        v == null || v == JSONObject.NULL -> null
        v is JSONObject -> TreeMap<String, Any?>().also { m -> v.keys().forEach { k -> m[k] = canon(v.opt(k)) } }
        v is JSONArray -> (0 until v.length()).map { canon(v.opt(it)) }
        v is Number -> v.toDouble()
        else -> v
    }

    /** Just the v2 groups of a chat object. */
    private fun v2(chat: JSONObject): JSONObject = JSONObject().also { out -> ChatStyles.KEYS.forEach { k -> if (chat.has(k)) out.put(k, chat.get(k)) } }

    /** A token set with its chat's v2 groups taken out: what a server older than v2 sends. */
    private fun stripped(tokens: JSONObject): JSONObject = JSONObject(tokens.toString()).also { t -> ChatStyles.KEYS.forEach { t.getJSONObject("chat").remove(it) } }

    private fun parse(o: JSONObject): ResolvedDesign = ResolvedDesign.parse(o) ?: error("did not parse: $o")

    /** Whether the brand turned any v2 knob (a style other than classic counts). */
    private fun hasV2Knobs(config: JSONObject): Boolean {
        val chat = config.optJSONObject("chat") ?: return false
        return chat.keys().asSequence().any { k ->
            when (k) {
                "preset" -> chat.optString("preset") != "classic"
                "header" -> chat.opt("header") is JSONObject
                in ChatStyles.KEYS -> true
                else -> false
            }
        }
    }

    /** The ten styles as they come on Graine Classic: found by what each case says, not where it sits in the file. */
    private fun styleCases(): List<JSONObject> = cases().filter { c ->
        val config = c.getJSONObject("config")
        val chat = config.optJSONObject("chat")
        config.optString("preset") == "graine-classic" && !config.has("accent") && chat != null && chat.length() == 1 && chat.opt("preset") is String
    }

    private fun styleCase(id: String): JSONObject = styleCases().first { it.getJSONObject("config").getJSONObject("chat").getString("preset") == id }

    @Test fun everyCaseCarriesTheGroupsAndTheTenStylesAreThere() {
        assertTrue(cases().isNotEmpty())
        cases().forEach { assertNotNull("${it.getJSONObject("config")}", parse(it.getJSONObject("design")).light.chat!!.style) }
        val styles = styleCases().map { parse(it.getJSONObject("design")).light.chat!!.style!!.preset }
        assertEquals(listOf("classic", "soft", "bold", "glass", "minimal", "crisp", "cozy", "calm", "compact", "lively"), styles)
        assertEquals(10, fx.getJSONArray("chatStyles").length())
    }

    @Test fun everyDesignReadsToExactlyTheServersValues() {
        for (c in cases()) {
            val d = c.getJSONObject("design")
            val parsed = parse(d)
            for ((scheme, t) in listOf("light" to parsed.light, "dark" to parsed.dark)) {
                val want = v2(d.getJSONObject(scheme).getJSONObject("chat"))
                val got = ChatStyles.json(t.chat!!.style!!, JSONObject())
                assertEquals("${c.getJSONObject("config")} $scheme", canon(want), canon(got))
                assertEquals("drawn as sent", t.chat!!.style, Surfaces.styleOf(t))
            }
        }
    }

    @Test fun aServerWithNoGroupsDrawsTheClassicChatItsV1TokensImply() {
        var compared = 0
        for (c in cases()) {
            val d = c.getJSONObject("design")
            for (scheme in listOf("light", "dark")) {
                val old = Design.tokens(stripped(d.getJSONObject(scheme)), scheme)!!
                assertNull("an older server sends no groups", old.chat!!.style)
                val derived = Surfaces.styleOf(old)
                // The classic chat, from the v1 tokens, exactly as the contract's table says.
                val v1 = old.chat!!
                val col = old.colors
                assertEquals("classic", derived.preset)
                assertEquals(ChatStyleFill(v1.headerBackground, null, v1.headerText, v1.headerSubtext), derived.header.fill)
                assertEquals(if (Design.normaliseHex(v1.headerBackground) == col.surface) "surface" else "accent", derived.header.style)
                assertEquals(if (derived.header.style == "surface") col.success else v1.headerText, derived.header.dot)
                assertEquals(v1.background, derived.wallpaper.color)
                assertEquals(v1.pattern, derived.wallpaper.pattern)
                assertEquals(v1.patternColor, derived.wallpaper.patternColor)
                assertEquals(if (v1.showTimestamps) "always" else "never", derived.timestamps.show)
                assertEquals(old.shape.radius.lg, derived.bubbles.radius, 0.0)
                assertEquals(minOf(old.shape.radius.sm, old.shape.radius.lg), derived.bubbles.tightRadius, 0.0)
                assertEquals(v1.background, derived.chips.fill)
                assertEquals(v1.composerBackground, derived.composer.fill)
                assertEquals(v1.background, derived.composer.strip)
                assertEquals(old.elevation.card, derived.cards.shadow)
                assertEquals(old.elevation.hardOffset != null, derived.cards.hard)
                assertEquals("loader", derived.typing.style)
                assertEquals(12.0, derived.density.edge, 0.0)
                // A design with no v2 knobs: exactly the groups the server sends for it.
                if (!hasV2Knobs(c.getJSONObject("config"))) {
                    compared++
                    assertEquals("${c.getJSONObject("config")} $scheme", canon(v2(d.getJSONObject(scheme).getJSONObject("chat"))),
                        canon(ChatStyles.json(derived, JSONObject())))
                }
            }
        }
        // Every case from before v2, both schemes, and classic itself.
        assertTrue("compared $compared", compared >= 2 * 32)
    }

    @Test fun graineClassicsBuiltInTokensCarryTheClassicChat() {
        val first = cases().first().getJSONObject("design")
        assertEquals(canon(v2(first.getJSONObject("light").getJSONObject("chat"))), canon(ChatStyles.json(Design.CLASSIC_LIGHT.chat!!.style!!, JSONObject())))
        assertEquals(canon(v2(first.getJSONObject("dark").getJSONObject("chat"))), canon(ChatStyles.json(Design.CLASSIC_DARK.chat!!.style!!, JSONObject())))
    }

    @Test fun aFieldMissingOrOfTheWrongTypeIsClassicsAndAnUnknownWordIsTheFirstListed() {
        val d = JSONObject(styleCase("bold").getJSONObject("design").toString())   // Bold on Graine Classic
        val chat = d.getJSONObject("light").getJSONObject("chat")
        chat.getJSONObject("header").put("style", "sparkly").remove("align")
        chat.getJSONObject("header").put("typingStatus", "yes")
        chat.getJSONObject("bubbles").put("shape", "blob").put("radius", "big").put("maxWidth", 3)
        chat.getJSONObject("bubbles").getJSONObject("user").put("color", "blue")
        chat.getJSONObject("timestamps").put("show", 1)
        chat.getJSONObject("wallpaper").put("pattern", "stripes")
        chat.getJSONObject("chips").put("placement", "floating")
        chat.getJSONObject("composer").put("style", "orb").put("strip", JSONObject.NULL).put("sendFill", "#abc")
        chat.put("typing", "dots")
        chat.put("glass", "frosted")
        val t = parse(d).light
        val s = t.chat!!.style!!
        val classic = ChatStyles.classic(t)
        assertEquals("a word this build does not know: the first listed", "surface", s.header.style)
        assertEquals("absent: classic's", classic.header.align, s.header.align)
        assertEquals("the wrong type: classic's", classic.header.typingStatus, s.header.typingStatus)
        assertEquals("rounded", s.bubbles.shape)
        assertEquals(classic.bubbles.radius, s.bubbles.radius, 0.0)
        assertEquals("a share past its range is clamped", 0.92, s.bubbles.maxWidth, 0.0)
        assertEquals("a colour that is not one: classic's", classic.bubbles.user.color, s.bubbles.user.color)
        assertEquals(classic.timestamps.show, s.timestamps.show)
        assertEquals("a pattern this build does not know is its dots", "dots", s.wallpaper.pattern)
        assertEquals("both", s.chips.placement)
        assertEquals("bar", s.composer.style)
        assertNull("null is none", s.composer.strip)
        assertEquals("#AABBCC", s.composer.sendFill)
        assertEquals("a group that is not one: all classic", classic.typing, s.typing)
        assertEquals(classic.glass, s.glass)
        // What was fine is untouched.
        assertEquals("bold", s.preset)
        assertEquals("tail", parse(styleCase("bold").getJSONObject("design")).light.chat!!.style!!.bubbles.shape)
    }

    @Test fun oneGroupAloneIsAV2ServerAndTheRestIsClassic() {
        val d = JSONObject(cases().first().getJSONObject("design").toString())
        val chat = d.getJSONObject("light").getJSONObject("chat")
        ChatStyles.KEYS.forEach { chat.remove(it) }
        chat.put("rating", JSONObject().put("show", true).put("style", "stars"))
        val t = parse(d).light
        val s = t.chat!!.style
        assertNotNull(s)
        assertEquals(ChatStyleRating(true, "stars", ""), s!!.rating)
        assertEquals(ChatStyles.classic(t).copy(rating = s.rating), s)
    }

    @Test fun aChatThatIsNotThereIsNoStyleAndStillDrawsClassic() {
        val d = JSONObject(cases().first().getJSONObject("design").toString())
        d.getJSONObject("light").remove("chat")
        val t = parse(d).light
        assertNull(t.chat)
        assertEquals("classic", Surfaces.styleOf(t).preset)
        assertEquals(Surfaces.defaultChat(t.colors).background, Surfaces.styleOf(t).wallpaper.color)
    }

    @Test fun everyWordAPieceAllowsStartsWithTheOneDrawnForAWordItDoesNotKnow() {
        assertEquals("surface", ChatStyles.HEADER_STYLES[0])
        assertEquals("rounded", ChatStyles.BUBBLE_SHAPES[0])
        assertEquals("never", ChatStyles.TIMESTAMP_SHOWS[0])
        assertEquals("solid", ChatStyles.WALLPAPER_STYLES[0])
        assertEquals("dots", ChatStyles.PATTERNS[0])
        assertEquals("outline", ChatStyles.CHIP_STYLES[0])
        assertEquals("both", ChatStyles.CHIP_PLACEMENTS[0])
        assertEquals("wrap", ChatStyles.CHIP_SCROLLS[0])
        assertEquals("auto", ChatStyles.CARD_IMAGES[0])
        assertEquals("filled", ChatStyles.CARD_BUTTONS[0])
        assertEquals("bar", ChatStyles.COMPOSER_STYLES[0])
        assertEquals("circle", ChatStyles.SEND_STYLES[0])
        assertEquals("loader", ChatStyles.TYPING_STYLES[0])
        assertEquals("comfortable", ChatStyles.DENSITIES[0])
        assertEquals("centered", ChatStyles.WELCOME_LAYOUTS[0])
        assertEquals("chips", ChatStyles.STARTER_LOOKS[0])
        assertEquals("thumbs", ChatStyles.RATING_STYLES[0])
        assertEquals("none", ChatStyles.CALL_BUTTONS[0])
        assertEquals("bar", ChatStyles.CONTEXT_CARDS[0])
    }

    @Test fun theStylesAsDataNameEveryKnobAWordThisBuildKnows() {
        // design.json → chatStyles: the presets as data (theme.ts CHAT_STYLES). The words they use are all words this SDK draws.
        val styles = fx.getJSONArray("chatStyles")
        val words = mapOf(
            "header.style" to ChatStyles.HEADER_STYLES, "header.align" to ChatStyles.HEADER_ALIGNS, "header.status" to ChatStyles.STATUS_LINES,
            "header.contextCard" to ChatStyles.CONTEXT_CARDS, "bubbles.shape" to ChatStyles.BUBBLE_SHAPES, "timestamps.show" to ChatStyles.TIMESTAMP_SHOWS,
            "timestamps.style" to ChatStyles.TIMESTAMP_STYLES, "wallpaper.style" to ChatStyles.WALLPAPER_STYLES, "wallpaper.pattern" to ChatStyles.PATTERNS,
            "chips.style" to ChatStyles.CHIP_STYLES, "chips.placement" to ChatStyles.CHIP_PLACEMENTS, "chips.scroll" to ChatStyles.CHIP_SCROLLS,
            "cards.image" to ChatStyles.CARD_IMAGES, "cards.button" to ChatStyles.CARD_BUTTONS, "composer.style" to ChatStyles.COMPOSER_STYLES,
            "composer.send" to ChatStyles.SEND_STYLES, "typing.style" to ChatStyles.TYPING_STYLES, "welcome.layout" to ChatStyles.WELCOME_LAYOUTS,
            "welcome.starters" to ChatStyles.STARTER_LOOKS, "rating.style" to ChatStyles.RATING_STYLES, "escalation.call" to ChatStyles.CALL_BUTTONS,
        )
        for (i in 0 until styles.length()) {
            val knobs = styles.getJSONObject(i).getJSONObject("knobs")
            for ((path, allowed) in words) {
                val (g, k) = path.split(".")
                val v = knobs.optJSONObject(g)?.opt(k) as? String ?: continue
                assertTrue("${styles.getJSONObject(i).getString("id")} $path=$v", v in allowed)
            }
            (knobs.opt("density") as? String)?.let { assertTrue(it in ChatStyles.DENSITIES) }
        }
    }
}
