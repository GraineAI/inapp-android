package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HOW BIG THE CHAT WINDOW OPENS — contract/DESIGN.md "Chat window" → Size:
 * the design's `chat.size` as the session serves it (compact when absent or a
 * word this build does not know), the open's size over the app's over the
 * design's, where each size goes on a phone and a tablet, and what closing a
 * bigger window does. The same answers as the React Native SDK's chatSizeOf
 * and chatPresentation (sdk/inapp/theme.ts).
 */
class ChatWindowSizeTest {
    private val contract: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/design.json").exists() && !File(dir, "contract/design.json").exists()) dir = dir.parentFile
        JSONObject(listOf(File(dir, "sdk/contract/design.json"), File(dir, "contract/design.json")).first { it.exists() }.readText())
    }

    @Test fun everyDesignSaysItsSizeTheSameInLightAndDark() {
        val cases = contract.getJSONArray("cases")
        val seen = HashSet<GraineChatSize>()
        for (i in 0 until cases.length()) {
            val raw = cases.getJSONObject(i).getJSONObject("design")
            val design = ResolvedDesign.parse(raw.toString())!!
            for ((scheme, tokens) in listOf("light" to design.light, "dark" to design.dark)) {
                val served = raw.getJSONObject(scheme).getJSONObject("chat").getString("size")
                assertEquals("case $i $scheme", served, tokens.chat!!.size.wire)
                seen.add(tokens.chat!!.size)
            }
            assertEquals("one size for both schemes", design.light.chat!!.size, design.dark.chat!!.size)
        }
        assertEquals("the contract has a design of each size", GraineChatSize.values().toSet(), seen)
    }

    @Test fun absentOrUnknownIsCompactAndTheWordIsWrittenBack() {
        val colors = Design.CLASSIC_LIGHT.colors
        assertEquals("an older server says nothing", GraineChatSize.COMPACT, Surfaces.chat(JSONObject(), colors)!!.size)
        for (word in listOf("cinema", "", "LARGEST")) {
            assertEquals(word, GraineChatSize.COMPACT, Surfaces.chat(JSONObject().put("size", word), colors)!!.size)
        }
        for (odd in listOf<Any>(3, true, JSONObject.NULL, org.json.JSONArray().put("full"))) {
            assertEquals("$odd", GraineChatSize.COMPACT, Surfaces.chat(JSONObject().put("size", odd), colors)!!.size)
        }
        assertEquals(GraineChatSize.LARGE, Surfaces.chat(JSONObject().put("size", " Large "), colors)!!.size)
        assertEquals(GraineChatSize.FULL, Surfaces.chat(JSONObject().put("size", "full"), colors)!!.size)
        assertNull(GraineChatSize.from("huge"))
        assertEquals(listOf("compact", "large", "full"), GraineChatSize.values().map { it.wire })
        // Kept with the cached design: a cold start draws the size the session last said.
        val full = Surfaces.chat(JSONObject().put("size", "full"), colors)!!
        assertEquals("full", Surfaces.json(full).getString("size"))
        assertEquals(GraineChatSize.FULL, Surfaces.chat(Surfaces.json(full), colors)!!.size)
    }

    @Test fun theOpensSizeThenTheAppsThenTheDesigns() {
        assertEquals(GraineChatSize.FULL, ChatWindowLayout.size(GraineChatSize.FULL, GraineChatSize.LARGE, GraineChatSize.COMPACT))
        assertEquals(GraineChatSize.LARGE, ChatWindowLayout.size(null, GraineChatSize.LARGE, GraineChatSize.FULL))
        assertEquals(GraineChatSize.FULL, ChatWindowLayout.size(null, null, GraineChatSize.FULL))
        assertEquals(GraineChatSize.COMPACT, ChatWindowLayout.size(null, null, null))
        assertNull("null follows the dashboard", GraineConfig("https://www.graine.ai", "pk").chatSize)
        assertEquals(GraineChatSize.LARGE, GraineConfig("https://www.graine.ai", "pk", chatSize = GraineChatSize.LARGE).chatSize)
    }

    @Test fun wherePhonesAndTabletsPutEachSize() {
        val screens = listOf(390.0 to 844.0, 844.0 to 390.0, 820.0 to 1180.0)
        for ((w, h) in screens) {
            assertEquals(ChatWindowLayout.Presentation.WINDOW, ChatWindowLayout.presentation(GraineChatSize.COMPACT, w, h))
            assertEquals("the whole screen, a tablet's too", ChatWindowLayout.Presentation.SCREEN, ChatWindowLayout.presentation(GraineChatSize.FULL, w, h))
        }
        assertEquals(ChatWindowLayout.Presentation.SHEET, ChatWindowLayout.presentation(GraineChatSize.LARGE, 390.0, 844.0))
        assertEquals("a phone on its side", ChatWindowLayout.Presentation.SHEET, ChatWindowLayout.presentation(GraineChatSize.LARGE, 844.0, 390.0))
        assertEquals(ChatWindowLayout.Presentation.PANEL, ChatWindowLayout.presentation(GraineChatSize.LARGE, 820.0, 1180.0))
        assertEquals("600 dp across is a tablet", ChatWindowLayout.Presentation.PANEL, ChatWindowLayout.presentation(GraineChatSize.LARGE, 600.0, 1000.0))
        assertEquals(ChatWindowLayout.Presentation.SHEET, ChatWindowLayout.presentation(GraineChatSize.LARGE, 599.0, 1000.0))
        assertEquals(420.0 to 760.0, ChatWindowLayout.panelSize(1180.0))
        assertEquals(420.0 to 630.0, ChatWindowLayout.panelSize(700.0))
        // A sheet's top: 8% of the screen below the status bar (2400 px tall, a 96 px bar: 2400 − 96 − 192).
        assertEquals(2112, ChatWindowLayout.sheetHeight(2400, 96))
        assertEquals(0.08, ChatWindowLayout.SHEET_TOP, 0.0)
    }

    @Test fun theBarDrawsTheChatWhereTheSizeSaysAndClosingDoesWhatTheChevronDoes() {
        assertNull("on the call there is no chat window", ChatWindowLayout.barPresentation(false, GraineChatSize.FULL, null, null, 390.0, 844.0))
        assertEquals(ChatWindowLayout.Presentation.WINDOW, ChatWindowLayout.barPresentation(true, null, null, null, 390.0, 844.0))
        assertEquals(ChatWindowLayout.Presentation.SHEET, ChatWindowLayout.barPresentation(true, null, null, GraineChatSize.LARGE, 390.0, 844.0))
        assertEquals(ChatWindowLayout.Presentation.SCREEN, ChatWindowLayout.barPresentation(true, null, GraineChatSize.FULL, GraineChatSize.LARGE, 390.0, 844.0))
        assertEquals(ChatWindowLayout.Presentation.WINDOW, ChatWindowLayout.barPresentation(true, GraineChatSize.COMPACT, GraineChatSize.FULL, GraineChatSize.LARGE, 390.0, 844.0))
        assertEquals(ChatWindowLayout.Presentation.PANEL, ChatWindowLayout.barPresentation(true, null, null, GraineChatSize.LARGE, 1024.0, 1366.0))
        assertEquals(ChatWindowLayout.Close.BACK_TO_CALL, ChatWindowLayout.close(canSwitch = true))
        assertEquals(ChatWindowLayout.Close.CLOSE_BAR, ChatWindowLayout.close(canSwitch = false))
        // A sheet let go: past 80 dp, or flung, it closes; a nudge settles back.
        assertTrue(ChatWindowLayout.dragCloses(120.0, 0.0))
        assertTrue(ChatWindowLayout.dragCloses(20.0, 1500.0))
        assertFalse(ChatWindowLayout.dragCloses(40.0, 200.0))
    }
}
