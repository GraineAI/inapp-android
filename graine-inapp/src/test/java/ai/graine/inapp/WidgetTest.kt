package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE = "https://www.graine.ai"

/** The dashboard's own car showcase, as the agent sends it. */
private fun cars(extra: String = "") = JSONObject("""
    {"widget_id":"car_showcase:run:3","kind":"product_list","fallback_text":"Three that fit",
     "data":{"title":"Three that fit your budget","prompt":"Tap one and I'll tell you more.","selectLabel":"Compare",
       "items":[
         {"id":"creta","title":"Creta","price":"₹19.2 lakh","imageUrl":"/samples/car-blue.png","facts":["6 airbags","Automatic","18 kmpl","extra"],"subtitle":"Best value"},
         {"id":"seltos","title":"Seltos","price":"₹17.8 lakh","imageUrl":"/samples/car-silver.png"},
         {"id":"vitara","title":"Grand Vitara","unavailable":true}
       ]}$extra}
""".trimIndent())

class JsSemanticsTest {
    @Test fun numbersPrintTheWayJavaScriptPrintsThem() {
        assertEquals("5", Js.str(5.0))
        assertEquals("19.5", Js.str(19.5))
        assertEquals("-3", Js.str(-3.0))
        assertEquals("0.000015", Js.str(0.000015))
        assertEquals("1e-7", Js.str(1e-7))
        assertEquals("100000000000000000000", Js.str(1e20))
        assertEquals("1e+21", Js.str(1e21))
        assertEquals("0", Js.str(-0.0))
        assertEquals("NaN", Js.str(Double.NaN))
    }

    @Test fun truthinessAndStringIsJavaScripts() {
        assertFalse(Js.truthy(""))
        assertFalse(Js.truthy(0))
        assertFalse(Js.truthy(JSONObject.NULL))
        assertTrue(Js.truthy("0"))
        assertTrue(Js.truthy(JSONObject()))
        assertEquals("null", Js.str(JSONObject.NULL))
        assertEquals("[object Object]", Js.str(JSONObject()))
        assertEquals("a,,2", Js.str(org.json.JSONArray("[\"a\",null,2]")))
    }
}

class WidgetStyleEdgeTest {
    @Test fun nothingGarbageAndArraysAreTheDefaultLook() {
        for (raw in listOf(null, "", 0, "gallery", org.json.JSONArray(), 42)) {
            assertEquals("$raw", WidgetStyle(), Widgets.normaliseStyle(raw))
        }
    }

    @Test fun aKotlinMapIsReadLikeJson() {
        assertEquals(WidgetStyle(preset = "list", select = "many", maxSelect = 4), Widgets.normaliseStyle(mapOf("preset" to "LIST", "select" to "many", "maxSelect" to 4)))
    }

    @Test fun aLookBuiltByHandWithJunkStillDraws() {
        // A host constructing WidgetStyle itself must not be able to crash the renderer.
        assertEquals("cards", Widgets.resolveLook(WidgetStyle(preset = "hologram"), "x").preset)
    }
}

class PlacementEdgeTest {
    private val b = Placement.Bounds(windowHeight = 844.0, baseBottom = 96.0, barHeight = 64.0)

    @Test fun garbageIsTheAnchorNotNaN() {
        assertEquals(0, Placement.clampOffset(Double.NaN, b))
        assertEquals(0, Placement.clampOffset(Double.POSITIVE_INFINITY, b))
    }

    @Test fun noKeyboardIsNoLiftWhateverTheWindowDid() {
        assertEquals(0, KeyboardLift.liftFor(Double.NaN, 0.0))
        assertEquals(0, KeyboardLift.liftFor(0.0, 200.0))
        assertEquals(336, KeyboardLift.liftFor(336.0, Double.NaN))
    }
}

class WidgetAdoptTest {
    @Test fun aFrameIsAdoptedAsTheReactNativeSdkAdoptsIt() {
        val w = GraineWidget.adopt(cars(""",
            "style":{"preset":"Gallery","onclick":"x"},"on_select":{"path":"compare?ids={id}"}"""), BASE)!!
        assertEquals("car_showcase:run:3", w.id)
        assertEquals("product_list", w.kind)
        assertEquals("gallery", w.style.preset)
        assertEquals("compare?ids={id}", w.onSelect.path)
        // Root-relative pictures are resolved against the agent's origin.
        assertEquals("$BASE/samples/car-blue.png", w.data.getJSONArray("items").getJSONObject(0).getString("imageUrl"))
    }

    @Test fun noWidgetIdIsNotAComponent() {
        assertNull(GraineWidget.adopt(JSONObject("""{"kind":"product_list"}"""), BASE))
        assertNull(GraineWidget.adopt(JSONObject("""{"widget_id":"","kind":"x"}"""), BASE))
        assertNull(GraineWidget.adopt(null, BASE))
    }

    @Test fun onSelectIsAlsoReadUnderItsCamelCaseName() {
        val w = GraineWidget.adopt(JSONObject("""{"widget_id":"w","kind":"x","onSelect":{"action":"go"}}"""), BASE)!!
        assertEquals("go", w.onSelect.action)
    }
}

class WidgetContentTest {
    private fun content(raw: JSONObject) = WidgetContent(GraineWidget.adopt(raw, BASE)!!)

    @Test fun aProductListIsRowsWithPicturesPricesAndAtMostThreeFacts() {
        val c = content(cars())
        assertTrue(c.isProducts); assertTrue(c.drawable)
        assertEquals(3, c.rows.size)
        val creta = c.rows[0]
        assertEquals(listOf("6 airbags", "Automatic", "18 kmpl"), creta.facts)
        assertEquals("$BASE/samples/car-blue.png", creta.image)
        assertEquals("Best value", creta.subtitle)
        assertEquals("Compare", creta.selectLabel)
        assertTrue(c.rows[2].unavailable)
        assertEquals("Three that fit your budget", c.title)
        assertEquals("Tap one and I'll tell you more.", c.prompt)
    }

    @Test fun tappingOneSendsThePayloadAndSentenceTheRuntimeReads() {
        val a = content(cars()).pick(0)
        assertEquals(mapOf("id" to "creta", "title" to "Creta", "price" to "₹19.2 lakh"), a.data)
        assertEquals("I'll take a look at Creta at ₹19.2 lakh.", a.summary)
        val noPrice = content(cars()).pick(2)
        assertEquals("I'll take a look at Grand Vitara.", noPrice.summary)
        assertEquals("", noPrice.data["price"])
    }

    @Test fun severalChosenAreOneAnswerNamingThemAll() {
        val a = content(cars()).pickMany(listOf("creta", "seltos"))
        assertEquals(2, a.data["count"])
        assertEquals("creta,seltos", a.data["ids"])
        assertEquals("Creta, Seltos", a.data["titles"])
        assertEquals(2, (a.data["items"] as List<*>).size)
        // The button's label, then the names — the label never read as a verb.
        assertEquals("Compare: Creta and Seltos", a.summary)
        assertEquals("Taken: Morning and Evening", Widgets.manySummary(listOf("Morning", "Evening"), "Taken"))
        assertEquals("Morning", Widgets.manySummary(listOf("Morning"), "  "))
    }

    @Test fun aPlanComparisonIsRowsTooWithItsOwnPayload() {
        val c = content(JSONObject("""{"widget_id":"plans:1","kind":"plan_comparison","data":{"plans":[
            {"id":"basic","name":"Basic","price":"₹299","priceSuffix":"/mo","badge":"Popular","facts":[{"label":"Cover","value":"₹5L"},{"value":"No wait"}]},
            {"id":"family","name":"Family Shield","price":499}]}}"""))
        assertTrue(c.isProducts)
        assertEquals("₹299/mo", c.rows[0].price)
        assertEquals(listOf("Cover ₹5L", "No wait"), c.rows[0].facts)
        assertEquals("Popular", c.rows[0].subtitle)
        assertEquals("499", c.rows[1].price)
        assertEquals("Select", c.rows[1].selectLabel)
        val a = c.pick(1)
        assertEquals(mapOf("id" to "family", "name" to "Family Shield", "price" to "499"), a.data)
        assertEquals("I'll take Family Shield at 499.", a.summary)
    }

    @Test fun anInfoCardIsOneRowWhoseButtonIsItsActionLabel() {
        val c = content(JSONObject("""{"widget_id":"i","kind":"info_card","data":{"title":"Loan status","summary":"Approved","category":"Loans","actionLabel":"Show EMI","facts":[{"label":"Rate","value":"9.5%"}]}}"""))
        assertEquals(1, c.rows.size)
        assertEquals(listOf("Approved", "Rate 9.5%"), c.rows[0].facts)
        assertEquals("Show EMI", c.rows[0].selectLabel)
        assertEquals(WidgetAnswer(mapOf("action" to "Show EMI"), "Show EMI"), c.pick(0))
        assertEquals("card", c.surface(Widgets.resolveLook(null, "info_card")))
    }

    @Test fun choicesAndQuickActionsAreChipsWithEachKindsOwnKeys() {
        val choices = content(JSONObject("""{"widget_id":"c","kind":"choices","data":{"options":[{"id":"y","label":"Yes"},"No",{"title":"Maybe"}]}}"""))
        assertTrue(choices.isChips); assertFalse(choices.isProducts)
        assertEquals(listOf("Yes", "No", "Maybe"), choices.chips.map { it.label })
        assertEquals(WidgetAnswer(mapOf("chosen" to "y", "label" to "Yes"), "I'll go with Yes."), choices.pickChip(choices.chips[0]))
        val quick = content(JSONObject("""{"widget_id":"q","kind":"quick_actions","data":{"actions":[{"id":"call","label":"Call me"}]}}"""))
        assertTrue(quick.isQuickActions)
        assertEquals(mapOf("id" to "call", "label" to "Call me"), quick.pickChip(quick.chips[0]).data)
        assertEquals("sheet", quick.surface(Widgets.resolveLook(null, "quick_actions")))
    }

    @Test fun calendarSlotsAreDaysAndTheirTimes() {
        val c = content(JSONObject("""{"widget_id":"s","kind":"calendar_slots","data":{"note":"IST","days":[
            {"label":"Mon","slots":["10:00","11:00"]},{"id":"tue","slots":[]},{"slots":["9:00"]}]}}"""))
        assertTrue(c.isCalendar)
        assertEquals(listOf("Mon", "Day 3"), c.days.map { it.label })
        assertEquals(WidgetAnswer(mapOf("day" to "Mon", "slot" to "11:00"), "Mon at 11:00, please."), c.pickSlot(c.days[0], "11:00"))
        assertEquals("IST", c.note)
    }

    @Test fun aKindThisBuildCannotDrawIsTheFallbackSentenceOnACard() {
        val c = content(JSONObject("""{"widget_id":"f","kind":"form","fallback_text":"Fill in your address","data":{"fields":[1]}}"""))
        assertFalse(c.drawable)
        assertEquals("card", c.surface(Widgets.resolveLook(WidgetStyle(surface = "screen"), "form")))
    }

    @Test fun anExplicitSurfaceWinsForADrawableKind() {
        val w = GraineWidget.adopt(cars(""","style":{"surface":"card"}"""), BASE)!!
        assertEquals("card", WidgetContent(w).surface(Widgets.resolveLook(w.style, w.kind)))
        assertEquals("screen", WidgetContent(GraineWidget.adopt(cars(), BASE)!!).surface(Widgets.resolveLook(null, "product_list")))
    }
}

class WidgetFlowTest {
    private fun flow() = WidgetFlow(BASE)
    private val pending = JSONObject("""{"widget_id":"car_showcase:run:3","kind":"product_list"}""")

    @Test fun aComponentWithNoAnnouncedWaitDrawsAtOnce() {
        val f = flow()
        assertTrue(f.onWidget(cars(), 1_000))
        assertEquals("car_showcase:run:3", f.widget?.id)
        assertNull(f.nextDeadline())
    }

    @Test fun theLoaderStaysAtLeastItsMinimumThenTheComponentReplacesIt() {
        val f = flow()
        f.onPending(pending, 0)
        assertEquals(WidgetFlow.Pending("car_showcase:run:3", "product_list"), f.loading)
        assertFalse("held: the loader has only been up 100ms", f.onWidget(cars(), 100))
        assertNull(f.widget)
        assertNotNull("still loading while held", f.loading)
        assertEquals(Widgets.MIN_LOADER_MS, f.nextDeadline())
        assertFalse(f.tick(449))
        assertTrue(f.tick(450))
        assertEquals("car_showcase:run:3", f.widget?.id)
        assertNull(f.loading)
    }

    @Test fun aLoaderNothingArrivesForGivesUp() {
        val f = flow()
        f.onPending(pending, 0)
        assertEquals(Widgets.PENDING_WIDGET_MS, f.nextDeadline())
        f.tick(Widgets.PENDING_WIDGET_MS - 1)
        assertNotNull(f.loading)
        f.tick(Widgets.PENDING_WIDGET_MS)
        assertNull(f.loading)
    }

    @Test fun closingWhileItWaitsCancelsTheComponentThatWasLoading() {
        val f = flow()
        f.onPending(pending, 0)
        f.onWidget(cars(), 100)
        assertNull("nothing was on screen, so there is nothing to report", f.dismiss())
        f.tick(10_000)
        assertNull("a component must not appear after the customer closed the wait", f.widget)
        assertNull(f.loading)
    }

    @Test fun answeringKeepsTheChoiceAndWaitsForTheAgent() {
        val f = flow()
        f.onWidget(cars(), 0)
        val s = f.submit(mapOf("id" to "creta", "title" to "Creta", "price" to "₹19.2 lakh"), "I'll take a look at Creta.", 1_000)!!
        assertEquals("car_showcase:run:3", s.widgetId)
        assertNull("no on_select: no in-app action", s.action)
        assertNull(f.widget)
        assertEquals("I'll take a look at Creta.", f.answered?.summary)
        assertTrue(f.awaitingReply)
        f.onAgentStarted()
        assertFalse(f.awaitingReply)
        assertNotNull("the line stays after the agent answers", f.answered)
    }

    @Test fun theWaitForTheAgentIsBounded() {
        val f = flow()
        f.onWidget(cars(), 0)
        f.submit(mapOf("id" to "creta"), "x", 1_000)
        f.tick(1_000 + Widgets.AWAITING_REPLY_MS)
        assertFalse(f.awaitingReply)
    }

    @Test fun aComponentsActionRunsWithTheTappedItemAndItsSayReplacesTheSentence() {
        val f = flow()
        f.onWidget(cars(""","on_select":{"action":"open_comparison","args":{"id":"{id}","label":"{title} at {price}"},"say":"Comparing {title}."}"""), 0)
        val s = f.submit(mapOf("id" to "creta", "title" to "Creta", "price" to "₹19.2 lakh"), "I'll take a look at Creta.", 0)!!
        assertEquals(SelectCall("open_comparison", mapOf("id" to "creta", "label" to "Creta at ₹19.2 lakh")), s.action)
        assertEquals("Comparing Creta.", s.summary)
        assertEquals("Comparing Creta.", f.answered?.summary)
    }

    @Test fun aPathGoesThroughTheOneNavigateHandlerWithEveryChosenId() {
        val f = flow()
        f.onWidget(cars(""","on_select":{"path":"compare?ids={ids}"}"""), 0)
        val w = f.widget!!
        val many = WidgetContent(w).pickMany(listOf("creta", "seltos"))
        val s = f.submit(many.data, many.summary, 0)!!
        assertEquals(SelectCall(Widgets.NAVIGATE_ACTION, mapOf("path" to "compare?ids=creta,seltos")), s.action)
    }

    @Test fun closingTellsTheAgentQuietlyWhatWasClosedAndItCanBePutBack() {
        val f = flow()
        f.onWidget(cars(), 0)
        val d = f.dismiss()!!
        assertEquals(mapOf("tool_name" to "car_showcase", "kind" to "product_list", "title" to "Three that fit your budget", "answered" to false), d.eventData())
        assertNull(f.widget)
        assertEquals("car_showcase:run:3", f.closed?.id)
        assertTrue(f.reopen())
        assertEquals("car_showcase:run:3", f.widget?.id)
        assertNull(f.closed)
    }

    @Test fun clearingTheAnsweredLineReportsNothingAndForgetsIt() {
        val f = flow()
        f.onWidget(cars(), 0)
        f.submit(mapOf("id" to "creta"), "x", 0)
        assertNull(f.dismiss())
        assertNull(f.answered)
        assertFalse(f.awaitingReply)
    }

    @Test fun theNextComponentReplacesTheLastAnswer() {
        val f = flow()
        f.onWidget(cars(), 0)
        f.submit(mapOf("id" to "creta"), "x", 0)
        f.onWidget(JSONObject("""{"widget_id":"slots:9","kind":"calendar_slots","data":{}}"""), 5_000)
        assertNull(f.answered)
        assertFalse("a component is the agent answering", f.awaitingReply)
        assertEquals("slots:9", f.widget?.id)
    }

    @Test fun aRetainedComponentIsPickedUpButNotReplayedOverItself() {
        val f = flow()
        f.seed(cars())
        assertEquals("car_showcase:run:3", f.widget?.id)
        val same = f.widget
        f.seed(cars())
        assertTrue(same === f.widget)
        f.seed(null)
        assertTrue(same === f.widget)
    }

    @Test fun aNewCallStartsClean() {
        val f = flow()
        f.onPending(pending, 0)
        f.onWidget(cars(), 1_000)
        f.dismiss()
        f.reset()
        assertNull(f.widget); assertNull(f.closed); assertNull(f.loading); assertNull(f.nextDeadline())
    }
}

class FrictionGateTest {
    private val pan = { status: GraineField.Status?, error: String? ->
        GraineScreen("kyc_pan", title = "PAN", fields = listOf(GraineField("pan", "PAN", status = status, error = error), GraineField("middle", "Middle name", status = GraineField.Status.EMPTY, optional = true)))
    }

    @Test fun theSdkDefaultWaitsForAnOutstandingFieldAndNamesIt() {
        val d = FrictionDetector(Friction.resolve(FrictionConfig(idleAfterMs = 10_000)))
        d.onScreen(pan(GraineField.Status.FILLED, null), 0)
        assertNull("nothing outstanding, nothing to be stuck on", d.idleDueIn(0))
        d.onScreen(pan(GraineField.Status.INVALID, "Not recognised"), 5_000)
        assertEquals("the clock starts when the field went wrong", 10_000L, d.idleDueIn(5_000))
        val s = d.onIdle(15_000)!!
        assertEquals("stalled_on_step", s.name)
        assertEquals("The customer has not touched the \"PAN\" screen for 10s. The \"PAN\" field is showing: Not recognised.", s.detail)
        assertNull("once per visit", d.onIdle(40_000))
    }

    @Test fun anOptionalBlankIsNotWhatTheyAreStuckOn() {
        val d = FrictionDetector(Friction.resolve(FrictionConfig(idleAfterMs = 1_000)))
        d.onScreen(GraineScreen("details", fields = listOf(GraineField("middle", "Middle name", status = GraineField.Status.EMPTY, optional = true))), 0)
        assertNull(d.idleDueIn(0))
        d.onScreen(GraineScreen("details", fields = listOf(
            GraineField("middle", "Middle name", status = GraineField.Status.EMPTY, optional = true),
            GraineField("pan", "PAN", status = GraineField.Status.EMPTY),
        )), 10)
        assertEquals("The customer has not touched the \"details\" screen for 1s. The \"PAN\" field is empty.", d.onIdle(1_010)!!.detail)
    }

    @Test fun aFieldEditOnTheSameScreenDoesNotRestartTheClock() {
        val d = FrictionDetector(Friction.resolve(FrictionConfig(idleAfterMs = 10_000)))
        d.onScreen(pan(GraineField.Status.EMPTY, null), 0)
        d.onScreen(pan(GraineField.Status.INVALID, "Too short"), 6_000)
        assertEquals(4_000L, d.idleDueIn(6_000))
    }

    @Test fun gatedOnACallTheClockStartsWhenTheCallDoesAndANewCallMayHearItAgain() {
        val d = FrictionDetector(Friction.resolve(FrictionConfig(idleAfterMs = 10_000, cooldownMs = 1)), gateOnCall = true)
        d.onScreen(pan(GraineField.Status.EMPTY, null), 0)
        assertNull("no call, no one to tell", d.idleDueIn(60_000))
        d.onCall(true, 60_000)
        assertEquals(10_000L, d.idleDueIn(60_000))
        assertNotNull(d.onIdle(70_000))
        assertNull(d.idleDueIn(70_001))
        d.onCall(false, 80_000)
        d.onCall(true, 90_000)
        assertEquals("a new conversation has not heard it", 10_000L, d.idleDueIn(90_000))
    }

    @Test fun theDashboardsPerScreenRulesApplyUnderTheApps() {
        val dashboard = FrictionConfig.fromJson(JSONObject("""{"idle":{"afterMs":45000},"nudge":{"openLauncher":true,"maxPerSession":2,"cooldownSeconds":30},
            "screens":{"home":{"idle":false},"agent_demo":{"ignore":true},"otp":{"idle":{"afterMs":"20000"},"message":"OTP for {seconds}s: {field} {error}"}}}"""))
        val rules = Friction.resolve(dashboard, FrictionConfig(screens = mapOf("otp" to FrictionConfig.Screen(idleAfterMs = 15_000))))
        assertNull(rules.forScreen("home").idleAfterMs)
        assertTrue(rules.forScreen("agent_demo").ignore)
        assertEquals(15_000L, rules.forScreen("otp").idleAfterMs)
        assertEquals(45_000L, rules.forScreen("anything").idleAfterMs)
        val d = FrictionDetector(rules)
        d.onScreen(GraineScreen("otp", fields = listOf(GraineField("code", "OTP", status = GraineField.Status.EMPTY))), 0)
        val s = d.onIdle(15_000)!!
        assertEquals("OTP for 15s: OTP empty", s.detail)
        assertEquals(30, s.cooldownSeconds); assertEquals(2, s.maxPerSession); assertTrue(s.openLauncher)
    }

    @Test fun zeroSwitchesItOffAndEnabledFalseSilencesEverything() {
        assertNull(Friction.resolve(FrictionConfig(idleAfterMs = 0)).idleAfterMs)
        val d = FrictionDetector(Friction.resolve(FrictionConfig(enabled = false, idleAfterMs = 10)))
        d.onScreen(pan(GraineField.Status.EMPTY, null), 0)
        assertNull(d.idleDueIn(0)); assertNull(d.onIdle(100))
    }
}

class AppearanceEdgeTest {
    @Test fun coloursParseInEveryShapeTheDashboardSends() {
        assertEquals(0xFF318CE7.toInt(), Appearance.argb("#318CE7"))
        assertEquals(0xFF318CE7.toInt(), Appearance.argb("318ce7"))
        assertEquals(0xFFFF00AA.toInt(), Appearance.argb("#f0a"))
        assertEquals(0x80112233.toInt(), Appearance.argb("#80112233"))
        assertNull(Appearance.argb("red"))
        assertNull(Appearance.argb(null))
    }

    @Test fun aTintIsTheAccentAtPartialStrength() {
        assertEquals(0x26318CE7, Appearance.tint("#318CE7", 0.15))
        // Not a colour: the neutral slate the RN SDK falls back to.
        assertEquals((0x26 shl 24) or 0x788CAA, Appearance.tint("rebeccapurple", 0.15))
    }

    @Test fun encodeUriComponentIsJavaScripts() {
        assertEquals("a%20b%2Bc%2F%26%3D", Appearance.encodeURIComponent("a b+c/&="))
        assertEquals("it's(ok)!~*-_.", Appearance.encodeURIComponent("it's(ok)!~*-_."))
        assertEquals("%E0%A4%86", Appearance.encodeURIComponent("आ"))
    }
}
