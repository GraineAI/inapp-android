package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A COMPONENT BUILT FROM BLOCKS — "Start blank" in the dashboard (kind
 * `blank`): its blocks as [GraineWidget.blocks] reads them, what
 * [GraineWidgetView] decides to draw for one, and what a button's tap
 * answers. The contract's own answers are held in RnParityTest.
 */
class WidgetBlocksTest {
    private val base = "https://www.graine.ai"

    /** The dashboard's own canvas: one block of each type. */
    private val canvas = """[
        {"id":"b1","type":"heading","content":"Your plan"},
        {"id":"b2","type":"text","content":"  Three things to check.\nThen tap below.  "},
        {"id":"b3","type":"image","imageUrl":"https://cdn.example.com/a.png"},
        {"id":"b4","type":"divider"},
        {"id":"b5","type":"button","content":"Talk to sales"}
    ]"""

    private fun frame(blocks: String, kind: String = "blank", extra: String = "") = JSONObject(
        """{"widget_id":"canvas:run:1","kind":"$kind","fallback_text":"Here is your plan.","data":{"blocks":$blocks}$extra}""",
    )

    private fun widget(blocks: String, kind: String = "blank", extra: String = "") = GraineWidget.adopt(frame(blocks, kind, extra), base)!!

    private fun look(w: GraineWidget) = Widgets.resolveLook(w.style, w.kind)

    /** The rule alone, on a component's data holding these blocks. */
    private fun rule(blocks: String, baseUrl: String? = null) = Widgets.normaliseBlankBlocks(JSONObject("""{"blocks":$blocks}"""), baseUrl)

    // ── the blocks ─────────────────────────────────────────────────────────

    @Test fun aBlankComponentsBlocksComeInOrder() {
        assertEquals(
            listOf(
                GraineWidgetBlock("b1", GraineWidgetBlockType.HEADING, content = "Your plan"),
                GraineWidgetBlock("b2", GraineWidgetBlockType.TEXT, content = "Three things to check.\nThen tap below."),
                GraineWidgetBlock("b3", GraineWidgetBlockType.IMAGE, imageUrl = "https://cdn.example.com/a.png"),
                GraineWidgetBlock("b4", GraineWidgetBlockType.DIVIDER),
                GraineWidgetBlock("b5", GraineWidgetBlockType.BUTTON, content = "Talk to sales"),
            ),
            widget(canvas).blocks,
        )
        assertEquals(listOf("heading", "text", "image", "divider", "button"), GraineWidgetBlockType.entries.map { it.wire })
    }

    @Test fun onlyWhatCanBeDrawnIsKept() {
        val blocks = rule("""[
            {"id":"a","type":" Heading ","content":"Kept, in any case"},
            {"id":"b","type":"video","content":"a type this version does not know"},
            {"id":"c","type":"heading"},
            {"id":"d","type":"text","content":"   "},
            {"id":"e","type":"button","content":42},
            {"id":"f","type":"image"},
            {"id":"g","type":"image","imageUrl":" /samples/a.png ","content":"not its field"},
            {"id":"h","type":"divider","content":"not its field","imageUrl":"/x.png"},
            "not a block", null, ["heading"], {"type":3,"content":"x"}, 7,
            {"id":"i","type":"text","content":"x","imageUrl":"/y.png"}
        ]""")
        assertEquals(listOf("a", "g", "h", "i"), blocks.map { it.id })
        assertEquals(GraineWidgetBlock("a", GraineWidgetBlockType.HEADING, content = "Kept, in any case"), blocks[0])
        assertEquals("each block keeps only its own field", GraineWidgetBlock("g", GraineWidgetBlockType.IMAGE, imageUrl = "/samples/a.png"), blocks[1])
        assertEquals(GraineWidgetBlock("h", GraineWidgetBlockType.DIVIDER), blocks[2])
        assertEquals(GraineWidgetBlock("i", GraineWidgetBlockType.TEXT, content = "x"), blocks[3])
    }

    @Test fun everyBlockHasAKeyOfItsOwn() {
        val blocks = rule("""[
            {"type":"divider"}, {"id":"  ","type":"divider"}, {"id":"x","type":"divider"},
            {"id":"x","type":"divider"}, {"id":"block-5","type":"divider"}, {"id":7,"type":"divider"}, {"id":" kept as written ","type":"divider"}
        ]""")
        assertEquals(listOf("block-0", "block-1", "x", "block-3", "block-5", "block-5-5", " kept as written "), blocks.map { it.id })
        // Its place in the list as sent: a row left out still counts.
        assertEquals(listOf("block-1"), rule("""["junk",{"type":"divider"}]""").map { it.id })
    }

    @Test fun aPictureIsResolvedAsSentThenTrimmed() {
        val blocks = rule("""[
            {"id":"a","type":"image","imageUrl":"/a.png"}, {"id":"b","type":"image","imageUrl":"  https://cdn.example.com/b.jpg  "},
            {"id":"c","type":"image","imageUrl":" /c.png"}, {"id":"d","type":"image","imageUrl":"//cdn.example.com/d.png"}
        ]""", baseUrl = "$base/")
        assertEquals(listOf("$base/a.png", "https://cdn.example.com/b.jpg", "/c.png", "//cdn.example.com/d.png"), blocks.map { it.imageUrl })
        assertEquals("no base, no resolving", "/a.png", rule("""[{"type":"image","imageUrl":"/a.png"}]""", baseUrl = "").single().imageUrl)
    }

    @Test fun onlyAComponentBuiltFromBlocksHasBlocks() {
        assertTrue(widget(canvas, kind = "product_list").blocks.isEmpty())
        assertEquals("the kind as the surface rule reads it", 5, widget(canvas, kind = " Blank ").blocks.size)
        assertEquals(5, widget(canvas, kind = "BLANK").blocks.size)
        for (junk in listOf("\"nope\"", """{"0":{"type":"divider"}}""", "null", "[]", "42")) {
            assertTrue(junk, widget(junk).blocks.isEmpty())
        }
        assertTrue("no data at all", GraineWidget.adopt(JSONObject("""{"widget_id":"w","kind":"blank"}"""), base)!!.blocks.isEmpty())
        // A component an app builds itself reads its blocks the same way.
        val own = GraineWidget("w", "blank", JSONObject().put("blocks", JSONArray(canvas)), "", WidgetStyle(), WidgetSelectAction())
        assertEquals(widget(canvas).blocks, own.blocks)
        assertEquals("a copy reads them again", own.blocks, own.copy(id = "w2").blocks)
        assertTrue(own.copy(kind = "info_card").blocks.isEmpty())
    }

    @Test fun aKotlinListOfMapsIsReadLikeJson() {
        val blocks = Widgets.normaliseBlankBlocks(mapOf("blocks" to listOf(
            mapOf("type" to "button", "content" to " Go "),
            mapOf("id" to "p", "type" to "image", "imageUrl" to "https://cdn.example.com/p.png"),
            "junk",
        )))
        assertEquals(
            listOf(
                GraineWidgetBlock("block-0", GraineWidgetBlockType.BUTTON, content = "Go"),
                GraineWidgetBlock("p", GraineWidgetBlockType.IMAGE, imageUrl = "https://cdn.example.com/p.png"),
            ),
            blocks,
        )
    }

    @Test fun neverThrowsWhateverItIsGiven() {
        val cyclic = ArrayList<Any?>().also { it.add(it) }
        // Data that is not an object (the blocks themselves, a list, a word) is no component's data.
        val data = listOf(
            null, "blocks", 42, true, JSONObject.NULL, JSONObject(), JSONArray(canvas), listOf(mapOf("blocks" to JSONArray(canvas))),
            JSONObject("""{"blocks":[1,true,null,"s",[],{}]}"""), mapOf("blocks" to listOf(null, 1, emptyList<Any>())), mapOf("blocks" to cyclic), mapOf(1 to 2),
        )
        for (raw in data) assertTrue("$raw".take(40), Widgets.normaliseBlankBlocks(raw, base).isEmpty())
        assertTrue(Widgets.blankBlocks("blank", null).isEmpty())
        assertTrue(Widgets.blankBlocks(null, JSONObject().put("blocks", JSONArray(canvas))).isEmpty())
    }

    @Test fun picturesGivenAsAPathAreOnTheAgentsOrigin() {
        val w = GraineWidget.adopt(JSONObject("""{"widget_id":"w1","kind":"blank","data":{"logo":"/logo.png","path":"/checkout","blocks":[
            {"id":"b1","type":"image","imageUrl":"/samples/car-blue.png"},
            {"id":"b2","type":"image","imageUrl":"https://cdn.example.com/a.png"},
            {"id":"b3","type":"image","imageUrl":"//cdn.example.com/b.png"},
            {"id":"b4","type":"image","imageUrl":"data:image/png;base64,AA=="}
        ]}}"""), base)!!
        assertEquals(
            listOf("$base/samples/car-blue.png", "https://cdn.example.com/a.png", "//cdn.example.com/b.png", "data:image/png;base64,AA=="),
            w.blocks.map { it.imageUrl },
        )
        assertEquals("$base/logo.png", w.data.getString("logo"))
        assertEquals("a path that is not a picture is left alone", "/checkout", w.data.getString("path"))
    }

    // ── what the view draws for one ────────────────────────────────────────

    @Test fun aBlankComponentIsACardOfItsBlocks() {
        val w = widget(canvas)
        val c = WidgetContent(w)
        assertTrue(c.isBlocks); assertTrue(c.drawable)
        assertFalse(c.isProducts); assertFalse(c.isChips); assertFalse(c.isCalendar)
        assertEquals(w.blocks, c.blocks)
        assertEquals("card", c.surface(look(w)))
        // The dashboard's own choice of surface wins, as for every kind the SDK draws.
        val sheet = widget(canvas, extra = ""","style":{"surface":"sheet"}""")
        assertEquals("sheet", WidgetContent(sheet).surface(look(sheet)))
        // A button answers at once: there is nothing to tick.
        val many = widget(canvas, extra = ""","style":{"select":"many"}""")
        assertFalse(WidgetContent(many).many(look(many)))
    }

    @Test fun withNoBlockToDrawItIsTheFallbackSentenceOnACard() {
        val w = widget("""[{"type":"heading"},{"type":"video","content":"x"}]""", extra = ""","style":{"surface":"screen"}""")
        val c = WidgetContent(w)
        assertFalse(c.isBlocks); assertFalse(c.drawable)
        assertEquals("card", c.surface(look(w)))
        assertEquals("Here is your plan.", w.fallbackText)
    }

    @Test fun itsLookIsReadLikeAnyComponents() {
        val plain = look(widget(canvas))
        assertEquals("filled", plain.button)
        assertEquals("medium", plain.imageSize)
        assertEquals("comfortable", plain.density)
        assertEquals("auto", plain.platform)
        val own = look(widget(canvas, extra = ""","style":{"button":"Row","imageSize":"large","density":"compact","radius":4,"accent":"#0a7d4f","platform":"oneui"}"""))
        assertEquals("row", own.button)
        assertEquals("large", own.imageSize)
        assertEquals("compact", own.density)
        assertEquals(4, own.radius)
        assertEquals("#0A7D4F", own.accent)
        assertEquals("oneui", own.platform)
        assertEquals("outline", look(widget(canvas, extra = ""","style":{"button":"outline"}""")).button)
    }

    @Test fun aPictureIsAsTallAsItsLookSays() {
        assertEquals(100, Widgets.pictureDp("small"))
        assertEquals(140, Widgets.pictureDp("medium"))
        assertEquals(180, Widgets.pictureDp("large"))
        assertEquals("anything else is the medium one", 140, Widgets.pictureDp("huge"))
        assertEquals(140, Widgets.pictureDp(null))
    }

    // ── what a button answers ──────────────────────────────────────────────

    @Test fun aButtonAnswersWithItsWords() {
        val c = WidgetContent(widget(canvas))
        assertEquals(WidgetAnswer(mapOf("action" to "Talk to sales"), "Talk to sales"), c.pickBlock(c.blocks.last()))
    }

    @Test fun aButtonsAnswerGoesTheWayEveryAnswerGoes() {
        val f = WidgetFlow(base)
        assertTrue(f.onWidget(frame(canvas), 0))
        val w = f.widget!!
        val a = WidgetContent(w).pickBlock(w.blocks.last())
        val s = f.submit(a.data, a.summary, 1_000)!!
        assertEquals("canvas:run:1", s.widgetId)
        assertEquals(mapOf("action" to "Talk to sales"), s.data)
        assertEquals("Talk to sales", s.summary)
        assertNull("no on_select: no in-app action", s.action)
        assertTrue(f.awaitingReply)
        assertNull("answered: off the screen", f.widget)
    }

    @Test fun aButtonRunsTheComponentsTapWithItsWords() {
        val f = WidgetFlow(base)
        f.onWidget(frame(canvas, extra = ""","on_select":{"action":"open_sales","args":{"topic":"{action}"},"say":"{summary}, please."}"""), 0)
        val w = f.widget!!
        val a = WidgetContent(w).pickBlock(w.blocks.last())
        val s = f.submit(a.data, a.summary, 0)!!
        assertEquals(SelectCall("open_sales", mapOf("topic" to "Talk to sales")), s.action)
        assertEquals("Talk to sales, please.", s.summary)
        assertEquals("Talk to sales, please.", f.answered?.summary)
    }
}
