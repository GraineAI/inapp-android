package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date

/**
 * THE CHAT WINDOW v2'S DECISIONS (contract/DESIGN.md "Chat window v2"), as
 * [ChatLook] makes them: corners and tails, times and marks, the header's
 * status line, the banner, which composer and which buttons, where the chips
 * go, which welcome, when the rating is asked, the pill, the typing motion —
 * for the styles as the contract resolves them (design.json cases 31–40, each
 * style on Graine Classic), and classic exactly as the chat always was.
 */
class ChatLookTest {
    private val fx: JSONObject by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/design.json").exists() && !File(dir, "contract/design.json").exists()) dir = dir.parentFile
        JSONObject(listOf(File(dir, "sdk/contract/design.json"), File(dir, "contract/design.json")).first { it.exists() }.readText())
    }

    /** A style on Graine Classic, light, as the server resolved it. */
    private fun style(id: String, dark: Boolean = false): ChatStyle {
        val cases = fx.getJSONArray("cases")
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val config = c.getJSONObject("config")
            val preset = config.optJSONObject("chat")?.optString("preset") ?: "classic"
            if (config.optString("preset") == "graine-classic" && config.length() == 2 && preset == id || (id == "classic" && config.length() == 1 && config.optString("preset") == "graine-classic")) {
                val d = ResolvedDesign.parse(c.getJSONObject("design"))!!
                return Surfaces.styleOf(if (dark) d.dark else d.light)
            }
        }
        error("no case for $id")
    }

    private val classic by lazy { style("classic") }

    private fun bubbles(shape: String, grouping: Boolean = true) = classic.bubbles.copy(shape = shape, radius = 18.0, tightRadius = 6.0, grouping = grouping)

    // ── classic is the chat as it always was ──────────────────────────────────

    @Test fun classicDrawsTheChatAsItAlwaysWas() {
        val s = classic
        assertEquals("classic", s.preset)
        // v1's bubbles: radius.lg, the corner nearest the speaker radius.sm, every message on its own.
        val lg = Design.CLASSIC_LIGHT.shape.radius.lg
        val sm = Design.CLASSIC_LIGHT.shape.radius.sm
        assertEquals(ChatLook.Corners(lg, lg, sm, lg), ChatLook.corners(s.bubbles, ChatRules.MIDDLE, customer = true, rtl = false))
        assertEquals(ChatLook.Corners(lg, lg, lg, sm), ChatLook.corners(s.bubbles, ChatRules.LAST, customer = false, rtl = false))
        assertFalse(ChatLook.tail(s.bubbles, ChatRules.SINGLE))
        // The bar composer, the round send button, nothing else in it — whatever the app offers.
        assertEquals(ChatLook.Composer("bar", attach = false, call = false, mic = false, send = "circle"), ChatLook.composer(s, canCall = true, canAttach = true))
        assertEquals(58.0, ChatLook.strip("bar"), 0.0)
        assertEquals(ChatLook.Send(SdkIcons.SEND, "Send", calls = false, enabled = false), ChatLook.send(ChatLook.composer(s, true, true), empty = true, name = "Asha"))
        assertFalse("no call button in the header", ChatLook.headerCall(s, canCall = true))
        assertEquals("the empty chat, before any message", "centered", ChatLook.welcome(s, anyRow = false, restored = false))
        assertNull(ChatLook.welcome(s, anyRow = true, restored = false))
        assertFalse("no rating", ChatLook.rating(s, ended = true))
        assertEquals("loader", s.typing.style)
        assertEquals(12.0, s.density.edge, 0.0)
        // Starters docked above the composer, wrapping, on the customer's side.
        val chips = ChatLook.chips(s, listOf("Hi", "Help"), offersStarters = true, replies = emptyList())
        assertTrue(chips.startersDocked)
        assertFalse(chips.startersInThread)
        assertEquals("wrap", s.chips.scroll)
    }

    // ── corners and tails ─────────────────────────────────────────────────────

    @Test fun roundedTightensTheSpeakersBottomAlwaysAndItsTopInsideAGroup() {
        val b = bubbles("rounded")
        assertEquals(ChatLook.Corners(18.0, 18.0, 6.0, 18.0), ChatLook.corners(b, ChatRules.SINGLE, customer = true, rtl = false))
        assertEquals(ChatLook.Corners(18.0, 18.0, 6.0, 18.0), ChatLook.corners(b, ChatRules.FIRST, customer = true, rtl = false))
        assertEquals(ChatLook.Corners(18.0, 6.0, 6.0, 18.0), ChatLook.corners(b, ChatRules.MIDDLE, customer = true, rtl = false))
        assertEquals(ChatLook.Corners(18.0, 6.0, 6.0, 18.0), ChatLook.corners(b, ChatRules.LAST, customer = true, rtl = false))
        // The agent speaks from the leading side.
        assertEquals(ChatLook.Corners(6.0, 18.0, 18.0, 6.0), ChatLook.corners(b, ChatRules.LAST, customer = false, rtl = false))
    }

    @Test fun squircleAndTailTightenOnlyTheInnerCornersOfAGroup() {
        val sq = bubbles("squircle")
        assertEquals(ChatLook.Corners(18.0, 18.0, 18.0, 18.0), ChatLook.corners(sq, ChatRules.SINGLE, customer = true, rtl = false))
        assertEquals(ChatLook.Corners(18.0, 18.0, 6.0, 18.0), ChatLook.corners(sq, ChatRules.FIRST, customer = true, rtl = false))
        assertEquals(ChatLook.Corners(18.0, 6.0, 6.0, 18.0), ChatLook.corners(sq, ChatRules.MIDDLE, customer = true, rtl = false))
        assertEquals(ChatLook.Corners(18.0, 6.0, 18.0, 18.0), ChatLook.corners(sq, ChatRules.LAST, customer = true, rtl = false))
        val tail = bubbles("tail")
        assertTrue(ChatLook.tail(tail, ChatRules.SINGLE))
        assertTrue(ChatLook.tail(tail, ChatRules.LAST))
        assertFalse(ChatLook.tail(tail, ChatRules.FIRST))
        assertFalse(ChatLook.tail(tail, ChatRules.MIDDLE))
        assertEquals("the tail's corner is square", ChatLook.Corners(18.0, 6.0, 0.0, 18.0), ChatLook.corners(tail, ChatRules.LAST, customer = true, rtl = false))
        assertEquals(ChatLook.Corners(18.0, 18.0, 6.0, 18.0), ChatLook.corners(tail, ChatRules.FIRST, customer = true, rtl = false))
        val flat = bubbles("flat")
        for (p in listOf(ChatRules.SINGLE, ChatRules.FIRST, ChatRules.MIDDLE, ChatRules.LAST)) {
            assertEquals(ChatLook.Corners(18.0, 18.0, 18.0, 18.0), ChatLook.corners(flat, p, customer = true, rtl = false))
        }
    }

    @Test fun withoutGroupingEveryBubbleIsSingle() {
        val b = bubbles("tail", grouping = false)
        assertTrue("a tail on every bubble", ChatLook.tail(b, ChatRules.FIRST))
        assertEquals(ChatLook.corners(b, ChatRules.SINGLE, true, false), ChatLook.corners(b, ChatRules.MIDDLE, true, false))
    }

    @Test fun rightToLeftMirrorsTheSpeakersSideAndItsTail() {
        val b = bubbles("tail")
        assertTrue(ChatLook.speakerOnRight(customer = true, rtl = false))
        assertFalse(ChatLook.speakerOnRight(customer = true, rtl = true))
        assertTrue(ChatLook.speakerOnRight(customer = false, rtl = true))
        // The customer's last bubble in a right-to-left language: its tail and tight corners on the left.
        assertEquals(ChatLook.Corners(6.0, 18.0, 18.0, 0.0), ChatLook.corners(b, ChatRules.LAST, customer = true, rtl = true))
    }

    @Test fun theTailIsTheContractsPath() {
        val right = ChatLook.tailPath(100.0, 40.0, right = true)
        assertEquals(listOf(
            ChatLook.PathStep('M', listOf(100.0, 28.0)), ChatLook.PathStep('Q', listOf(101.0, 38.0, 108.0, 40.0)),
            ChatLook.PathStep('L', listOf(88.0, 40.0)), ChatLook.PathStep('Z', emptyList()),
        ), right)
        val left = ChatLook.tailPath(100.0, 40.0, right = false)
        assertEquals(listOf(
            ChatLook.PathStep('M', listOf(0.0, 28.0)), ChatLook.PathStep('Q', listOf(-1.0, 38.0, -8.0, 40.0)),
            ChatLook.PathStep('L', listOf(12.0, 40.0)), ChatLook.PathStep('Z', emptyList()),
        ), left)
    }

    @Test fun aGroupsMessagesSitCloserThanEverythingElse() {
        val d = ChatStyleDensity("comfortable", 10.0, 3.0, 14.0, 11.0, 12.0)
        assertEquals(10.0, ChatLook.gapAbove(d, ChatRules.FIRST, grouping = true), 0.0)
        assertEquals(3.0, ChatLook.gapAbove(d, ChatRules.MIDDLE, grouping = true), 0.0)
        assertEquals(3.0, ChatLook.gapAbove(d, ChatRules.LAST, grouping = true), 0.0)
        assertEquals(10.0, ChatLook.gapAbove(d, ChatRules.LAST, grouping = false), 0.0)
        assertEquals(2.0 to 2.0, ChatLook.padding(d, bare = true))
        assertEquals(14.0 to 11.0, ChatLook.padding(d, bare = false))
    }

    @Test fun rowsArePlacedInGroupsByTheirSpeakerAndTime() {
        val t0 = 1_792_400_400_000L
        fun turn(id: String, role: Role, at: Long) = ChatRows.Row.Turn(GraineChatTurn(id, role, "x", Date(at)))
        val rows = listOf(turn("a", Role.AGENT, t0), turn("b", Role.AGENT, t0 + 30_000), ChatRows.Row.Day("today", "2026-10-19", Date(t0)),
            turn("c", Role.CUSTOMER, t0 + 40_000), turn("d", Role.CUSTOMER, t0 + 400_000))
        assertEquals(listOf("first", "last", "single", "single", "single"), ChatLook.positions(rows))
    }

    // ── times, marks, what a screen reader says ───────────────────────────────

    @Test fun timesBelowOrInsideAndOnlyWhereTheyShow() {
        val always = ChatStyleTimestamps("always", "inside", true)
        assertEquals("inside", ChatLook.timePlace(always, ChatRules.MIDDLE, grouping = true, bare = false))
        assertEquals("a bare bubble's time is below it", "below", ChatLook.timePlace(always, ChatRules.MIDDLE, grouping = true, bare = true))
        val group = ChatStyleTimestamps("group", "below", false)
        assertNull(ChatLook.timePlace(group, ChatRules.FIRST, grouping = true, bare = false))
        assertEquals("below", ChatLook.timePlace(group, ChatRules.LAST, grouping = true, bare = false))
        assertEquals("ungrouped: every message is the last of its group", "below", ChatLook.timePlace(group, ChatRules.FIRST, grouping = false, bare = false))
        assertNull(ChatLook.timePlace(ChatStyleTimestamps("never", "below", true), ChatRules.SINGLE, grouping = true, bare = false))
    }

    @Test fun theCustomersMarksSayWhereTheMessageIs() {
        val ticks = ChatStyleTimestamps("always", "below", true)
        assertEquals(SdkIcons.CLOCK, ChatLook.tick(ticks, customer = true, delivery = GraineChatTurn.Delivery.SENDING))
        assertEquals(SdkIcons.CHECK, ChatLook.tick(ticks, customer = true, delivery = GraineChatTurn.Delivery.SENT))
        assertEquals(SdkIcons.ALERT, ChatLook.tick(ticks, customer = true, delivery = GraineChatTurn.Delivery.FAILED))
        assertNull("the agent's have none", ChatLook.tick(ticks, customer = false, delivery = GraineChatTurn.Delivery.SENT))
        assertNull(ChatLook.tick(ticks.copy(ticks = false), customer = true, delivery = GraineChatTurn.Delivery.SENT))
    }

    @Test fun eachBubbleIsOneElementToAScreenReader() {
        assertEquals("Asha: Hello there, 9:41", ChatLook.spoken("Asha", false, "Hello there", "9:41", GraineChatTurn.Delivery.SENT))
        assertEquals("You: Hi, waiting to send", ChatLook.spoken("Asha", true, "Hi", null, GraineChatTurn.Delivery.SENDING))
        assertEquals("You: Hi, 9:42, not sent. Double-tap to retry.", ChatLook.spoken("Asha", true, "Hi", "9:42", GraineChatTurn.Delivery.FAILED))
        assertEquals("Asha is typing", ChatLook.typingSpoken("Asha"))
        assertEquals("Call Asha", ChatLook.callLabel("Asha"))
        assertEquals("Pinned: Account settings, Profile, Active", ChatLook.pinSpoken(GraineChatPin("Account settings", "Profile", badge = "Active")))
        assertEquals("Pinned: Account settings", ChatLook.pinSpoken(GraineChatPin("Account settings")))
    }

    // ── the header and the banner ─────────────────────────────────────────────

    @Test fun theStatusLineFirstMatchWins() {
        val h = classic.header
        fun status(head: ChatStyleHeader = h, online: Boolean = true, connecting: Boolean = false, reconnecting: Boolean = false,
            failed: Boolean = false, typing: Boolean = false, ended: Boolean = false) =
            ChatLook.status(head, "", "Typing…", online, connecting, reconnecting, failed, typing, ended, "Connecting…")
        assertEquals(ChatLook.Status(ChatLook.StatusKind.ONLINE, "Online", true), status())
        assertEquals(ChatLook.StatusKind.CONNECTING, status(online = false, connecting = true).kind)
        assertEquals("Reconnecting…", status(online = false, connecting = true, reconnecting = true).words)
        assertEquals(ChatWindowView.FAILED_TEXT, status(online = false, failed = true).words)
        assertEquals("classic never says typing", ChatLook.StatusKind.ONLINE, status(typing = true).kind)
        val typingHeader = h.copy(typingStatus = true)
        assertEquals(ChatLook.Status(ChatLook.StatusKind.TYPING, "Typing…", false), status(typingHeader, typing = true))
        assertEquals(ChatLook.StatusKind.NONE, status(h.copy(status = "none")).kind)
        assertEquals("connecting outranks none", ChatLook.StatusKind.CONNECTING, status(h.copy(status = "none"), online = false, connecting = true).kind)
        assertEquals(ChatLook.Status(ChatLook.StatusKind.TEXT, "Online", false), status(h.copy(status = "text")))
        assertEquals("an ended conversation let go of: the words, no dot", ChatLook.Status(ChatLook.StatusKind.TEXT, "Online", false),
            status(online = false, ended = true))
        assertEquals("Replies in seconds", ChatLook.status(h, "Replies in seconds", "Typing…", true, false, false, false, false, false, "Connecting…").words)
    }

    @Test fun theBannerIsCalmAndOnlyWhenItMeansSomething() {
        assertEquals(ChatLook.Banner.NONE, ChatLook.banner(online = true, connecting = false, reconnecting = false, everConnected = true, waiting = true))
        assertEquals(ChatLook.Banner.RECONNECTING, ChatLook.banner(online = false, connecting = true, reconnecting = true, everConnected = true, waiting = false))
        assertEquals("a first connect retried is not a reconnect", ChatLook.Banner.NONE,
            ChatLook.banner(online = false, connecting = true, reconnecting = true, everConnected = false, waiting = false))
        assertEquals(ChatLook.Banner.OFFLINE, ChatLook.banner(online = false, connecting = false, reconnecting = false, everConnected = true, waiting = true))
        assertEquals("a first connect with a message waiting is just connecting", ChatLook.Banner.NONE,
            ChatLook.banner(online = false, connecting = true, reconnecting = false, everConnected = false, waiting = true))
        assertEquals(ChatLook.Banner.NONE, ChatLook.banner(online = false, connecting = false, reconnecting = false, everConnected = true, waiting = false))
    }

    @Test fun aPinnedCardIsDrawnOnlyWhenPinnedAndTheDesignShowsIt() {
        val pin = GraineChatPin("Account settings")
        assertNull("nothing pinned, nothing drawn", ChatLook.pin(null, style("crisp")))
        assertEquals("bar", ChatLook.pin(pin, style("crisp")))
        assertEquals("bar", ChatLook.pin(pin, classic))
        assertNull(ChatLook.pin(pin, classic.copy(header = classic.header.copy(contextCard = "none"))))
        assertEquals("card", ChatLook.pin(pin, classic.copy(header = classic.header.copy(contextCard = "card"))))
    }

    // ── the composer and its buttons ──────────────────────────────────────────

    @Test fun theMicrophoneAndTheCallButtonOnlyWhereTheAgentTakesCallsAndThePaperclipOnlyWithAHandler() {
        val soft = style("soft")
        assertEquals("floating", soft.composer.style)
        assertTrue(soft.composer.mic)
        assertEquals(ChatLook.Composer("floating", attach = false, call = false, mic = true, send = "circle"), ChatLook.composer(soft, canCall = true, canAttach = true))
        assertFalse("no calls: no microphone", ChatLook.composer(soft, canCall = false, canAttach = false).mic)
        val empty = ChatLook.send(ChatLook.composer(soft, true, false), empty = true, name = "Asha")
        assertEquals(ChatLook.Send(SdkIcons.MIC, "Call Asha", calls = true, enabled = true), empty)
        assertEquals(ChatLook.Send(SdkIcons.SEND, "Send", calls = false, enabled = true), ChatLook.send(ChatLook.composer(soft, true, false), empty = false, name = "Asha"))
        assertEquals("no calls: the send button, dimmed", ChatLook.Send(SdkIcons.SEND, "Send", calls = false, enabled = false),
            ChatLook.send(ChatLook.composer(soft, false, false), empty = true, name = "Asha"))

        val crisp = style("crisp")
        assertEquals(ChatLook.Composer("bar", attach = true, call = false, mic = false, send = "rounded"), ChatLook.composer(crisp, canCall = true, canAttach = true))
        assertFalse("the paperclip needs the app's handler", ChatLook.composer(crisp, canCall = true, canAttach = false).attach)
        assertTrue(ChatLook.headerCall(crisp, canCall = true))
        assertFalse(ChatLook.headerCall(crisp, canCall = false))

        val calm = style("calm")
        assertEquals("composer", calm.escalation.call)
        assertFalse("the microphone already calls", ChatLook.composer(calm, canCall = true, canAttach = false).call)
        assertTrue(ChatLook.composer(calm.copy(composer = calm.composer.copy(mic = false)), canCall = true, canAttach = false).call)

        val minimal = style("minimal")
        assertEquals(ChatLook.Composer("pill", attach = false, call = false, mic = false, send = "icon"), ChatLook.composer(minimal, true, true))
        assertEquals(ChatLook.TARGET, ChatLook.sendSide(ChatLook.composer(minimal, true, true)), 0.0)
        assertEquals(ChatLook.SEND_INSIDE, ChatLook.sendSide(ChatLook.composer(style("bold"), true, true)), 0.0)
        assertEquals(ChatLook.SEND_BAR, ChatLook.sendSide(ChatLook.composer(classic, true, true)), 0.0)
        assertEquals(12.0, ChatLook.sendCorners("rounded", 16.0)!!, 0.0)
        assertEquals(10.0, ChatLook.sendCorners("rounded", 10.0)!!, 0.0)
        assertNull(ChatLook.sendCorners("circle", 16.0))
        assertEquals(60.0, ChatLook.strip("pill"), 0.0)
        assertEquals(0.0, ChatLook.strip("floating"), 0.0)
    }

    @Test fun everyStyleDrawsTheComposerItsDataSays() {
        val want = mapOf("classic" to "bar", "soft" to "floating", "bold" to "pill", "glass" to "floating", "minimal" to "pill",
            "crisp" to "bar", "cozy" to "pill", "calm" to "floating", "compact" to "bar", "lively" to "floating")
        for ((id, composer) in want) {
            assertEquals(id, composer, style(id).composer.style)
            assertEquals("$id dark", composer, style(id, dark = true).composer.style)
        }
        assertEquals("a floating composer has no strip", null, style("soft").composer.strip)
    }

    // ── chips, welcome, rating, pill ──────────────────────────────────────────

    @Test fun theChipsGoWhereTheirPlacementSays() {
        val replies = listOf(GraineQuickReply("Yes", "Yes"))
        val both = ChatLook.chips(classic, listOf("Hi"), offersStarters = true, replies = replies)
        assertTrue(both.startersDocked); assertTrue(both.repliesInThread); assertFalse(both.repliesDocked)
        val inline = ChatLook.chips(classic.copy(chips = classic.chips.copy(placement = "inline")), listOf("Hi"), true, replies)
        assertTrue(inline.startersInThread); assertFalse(inline.startersDocked); assertTrue(inline.repliesInThread)
        val docked = classic.copy(chips = classic.chips.copy(placement = "above-composer"))
        val a = ChatLook.chips(docked, listOf("Hi"), true, replies)
        assertTrue("quick replies take the starters' place", a.repliesDocked)
        assertFalse(a.startersDocked)
        assertFalse(a.repliesInThread)
        assertTrue(ChatLook.chips(docked, listOf("Hi"), true, emptyList()).startersDocked)
        val list = ChatLook.chips(classic.copy(welcome = classic.welcome.copy(starters = "list")), listOf("Hi"), true, emptyList())
        assertTrue("a list is always in the thread", list.startersInThread && list.startersAsList && !list.startersDocked)
        val spoken = ChatLook.chips(classic, listOf("Hi"), offersStarters = false, replies = emptyList())
        assertTrue("the customer said something: no starters", spoken.starters.isEmpty() && !spoken.startersDocked)
    }

    @Test fun theWelcomeIsANewConversationsAndStaysAtTheTop() {
        val card = style("soft")
        assertEquals("card", ChatLook.welcome(card, anyRow = true, restored = false))
        assertNull("a conversation that continues opens on its history", ChatLook.welcome(card, anyRow = true, restored = true))
        assertEquals("hero", ChatLook.welcome(style("bold"), anyRow = true, restored = false))
        assertNull(ChatLook.welcome(style("minimal"), anyRow = false, restored = false))
    }

    @Test fun theRatingIsAskedOnlyAfterTheEndWhereTheDesignAsks() {
        val soft = style("soft")
        assertFalse(ChatLook.rating(soft, ended = false))
        assertTrue(ChatLook.rating(soft, ended = true))
        assertEquals("emoji", soft.rating.style)
        assertEquals("How was this chat?", ChatLook.ratingPrompt(soft.rating))
        assertEquals("Did this help?", ChatLook.ratingPrompt(soft.rating.copy(prompt = "Did this help?")))
        assertEquals(listOf(1, 0), ChatLook.ratingValues("thumbs"))
        assertEquals(listOf(1, 2, 3, 4, 5), ChatLook.ratingValues("stars"))
        val four = GraineChatRating(4, 5, "stars")
        assertEquals(listOf(true, true, true, true, false), (1..5).map { ChatLook.ratingLit("stars", it, four) })
        assertEquals(listOf(false, false, false, true, false), (1..5).map { ChatLook.ratingLit("emoji", it, GraineChatRating(4, 5, "emoji")) })
        assertFalse(ChatLook.ratingLit("thumbs", 1, null))
        assertTrue(ChatLook.ratingLit("thumbs", 0, GraineChatRating(0, 1, "thumbs")))
    }

    @Test fun thePillCountsWhatArrivedAndTheThreadFollowsWithin120() {
        assertNull(ChatLook.pillCount(0))
        assertEquals("1 new", ChatLook.pillCount(1))
        assertEquals("9 new", ChatLook.pillCount(9))
        assertEquals("9+ new", ChatLook.pillCount(10))
        assertTrue(ChatLook.follows(0.0))
        assertTrue(ChatLook.follows(120.0))
        assertFalse(ChatLook.follows(120.5))
    }

    // ── motion ────────────────────────────────────────────────────────────────

    @Test fun theTypingDotsMoveAsTheContractSaysAndStandStillUnderReducedMotion() {
        assertEquals(0.6f, ChatLook.dotAlpha(1, 500.0, moves = false), 0f)
        assertEquals("dot 2 has not started at 300 ms", 0.35f, ChatLook.dotAlpha(2, 300.0, moves = true), 1e-6f)
        assertEquals("full at the middle of its leg", 1f, ChatLook.dotAlpha(0, 600.0, moves = true), 1e-6f)
        assertEquals(0.35f, ChatLook.dotAlpha(0, 1200.0, moves = true), 1e-6f)
        assertEquals(0f, ChatLook.waveRise(0, 450.0, moves = false), 0f)
        assertEquals(4f, ChatLook.waveRise(0, 450.0, moves = true), 1e-6f)
        assertEquals(0f, ChatLook.waveRise(1, 100.0, moves = true), 0f)
        assertEquals(1f, ChatLook.breathe(700.0, moves = false), 0f)
        assertEquals(1f, ChatLook.breathe(0.0, moves = true), 1e-6f)
        assertEquals(0.55f, ChatLook.breathe(700.0, moves = true), 1e-6f)
        assertEquals("Typing…", ChatLook.typingWords(ChatStyleTyping("text", "", "#000000")))
        assertEquals("Writing…", ChatLook.typingWords(ChatStyleTyping("text", "Writing…", "#000000")))
    }

    // ── cards ─────────────────────────────────────────────────────────────────

    @Test fun aComponentsLargeImageAndMainButtonFollowTheCards() {
        assertEquals(140.0, ChatLook.imageHeight("auto", 300.0, 140.0), 0.0)
        assertEquals(240.0, ChatLook.imageHeight("square", 240.0, 140.0), 0.0)
        assertEquals("never past 260", 260.0, ChatLook.imageHeight("square", 400.0, 140.0), 0.0)
        assertEquals(180.0, ChatLook.imageHeight("landscape", 240.0, 140.0), 0.0)
        assertEquals("never under 100", 100.0, ChatLook.imageHeight("wide", 160.0, 140.0), 0.0)
        val cards = classic.cards
        assertEquals(ChatLook.Button(cards.buttonFill, cards.buttonText, null, false), ChatLook.mainButton(cards))
        assertEquals(ChatLook.Button(null, "#101318", "#318CE7", false), ChatLook.mainButton(cards.copy(button = "outline", buttonFill = null, buttonText = "#101318", buttonBorder = "#318CE7")))
        assertEquals(ChatLook.Button(null, "#101318", null, true), ChatLook.mainButton(cards.copy(button = "text", buttonFill = null, buttonText = "#101318")))
        assertEquals("tonal fills with its own colour", "#E6F1FC", ChatLook.mainButton(cards.copy(button = "tonal", buttonFill = "#E6F1FC")).fill)
    }

    // ── what a window draws from a chat ───────────────────────────────────────

    @Test fun aWindowReadsTheV2StateOffAnySource() {
        val source = object : ChatSource {
            override val messages: List<GraineChatTurn> = emptyList()
            override val online = true
            override val quickReplies = listOf(GraineQuickReply("Yes", "Yes"))
            override val ended = true
            override val rating = GraineChatRating(1, 1, "thumbs")
            override fun send(text: String) {}
            override fun addChangeListener(listener: Runnable) {}
            override fun removeChangeListener(listener: Runnable) {}
        }
        val x = ChatExtras.of(source, canCall = true)
        assertEquals(source.quickReplies, x.quickReplies)
        assertTrue(x.ended)
        assertEquals(source.rating, x.rating)
        assertNull("only the SDK's own chat carries a pin", x.pin)
        assertFalse("nor an attach handler", x.canAttach)
        assertTrue(x.canCall)
        assertFalse(x.connecting)
    }
}
