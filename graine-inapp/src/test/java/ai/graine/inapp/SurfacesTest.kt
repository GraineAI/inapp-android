package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * THE CHAT WINDOW'S AND THE CALL SCREEN'S TOKENS (contract §7): read
 * exactly, written back exactly, derived from the scheme's colours when an
 * older server sends none — and the call screen's own rules: the status in
 * every state (ringing, a scheduled ring, connecting, in a call, ended,
 * missed), the controls, the timer, the pulse.
 */
class SurfacesTest {
    private val colors = Design.CLASSIC_DARK.colors

    private val fullCall = JSONObject("""{
        "background":"#101820","backgroundTo":"#203040","text":"#FFFFFF","textMuted":"#A0A8B0",
        "avatar":"logo","avatarShape":"squircle","avatarFill":"#7C3AED","avatarText":"#FFFFFF",
        "buttonFill":"#2A2D35","buttonIcon":"#FFFFFF","buttonActiveFill":"#FFFFFF","buttonActiveIcon":"#101820",
        "endFill":"#E5484D","endIcon":"#FFFFFF","endShape":"pill",
        "acceptFill":"#1FAD5B","acceptIcon":"#FFFFFF","declineFill":"#E5484D","declineIcon":"#FFFFFF",
        "callbackFill":"#7C3AED","callbackIcon":"#FFFFFF",
        "captions":false,"timer":true,"controls":["speaker","mute","chat","end"],"ringtone":"soft",
        "statusText":{"ringing":"Acme is calling","connecting":"Joining…","ended":"Bye"}}""")

    private val fullChat = JSONObject("""{
        "title":"Acme help","subtitle":"Replies in seconds","avatar":"logo","placeholder":"Ask Acme…","showTimestamps":true,
        "suggestions":["Track my order","Change my plan","Talk to a person","Refund","A fifth one"],
        "background":"#F7F7F5","pattern":"dots","patternColor":"#E0E0DF","headerBackground":"#FFFFFF","headerText":"#101318","headerSubtext":"#5F6672",
        "composerBackground":"#FFFFFF","composerText":"#101318","composerPlaceholder":"#5F6672","composerBorder":"#E5E4E0",
        "size":"large","showUpcomingCalls":false,"upcomingTitle":"Your calls","showHistory":false,"newConversation":false}""")

    @Test fun theCallScreenIsReadExactly() {
        val c = Surfaces.call(fullCall, colors)!!
        assertEquals("#101820", c.background)
        assertEquals("#203040", c.backgroundTo)
        assertEquals("logo", c.avatar)
        assertEquals("squircle", c.avatarShape)
        assertEquals("pill", c.endShape)
        assertFalse(c.captions)
        assertEquals(listOf("speaker", "mute", "chat", "end"), c.controls)
        assertEquals("soft", c.ringtone)
        assertEquals(CallStatusText("Acme is calling", "Joining…", "Bye"), c.statusText)
        // Written back, the tokens are the tokens: nothing lost, nothing invented.
        assertEquals(canon(fullCall), canon(Surfaces.json(c)))
    }

    @Test fun theChatWindowIsReadExactlyAndItsSuggestionsCapped() {
        val c = Surfaces.chat(fullChat, Design.CLASSIC_LIGHT.colors)!!
        assertEquals("Acme help", c.title)
        assertTrue(c.showTimestamps)
        assertFalse(c.showUpcomingCalls || c.showHistory || c.newConversation)
        assertEquals("Your calls", c.upcomingTitle)
        assertEquals("at most four", listOf("Track my order", "Change my plan", "Talk to a person", "Refund"), c.suggestions)
        val back = Surfaces.json(c)
        assertEquals(4, back.getJSONArray("suggestions").length())
        assertEquals(canon(JSONObject(fullChat.toString()).put("suggestions", JSONArray(c.suggestions))), canon(back))
    }

    @Test fun anOlderServerGetsGraineClassicsCallScreenAndTheChatAsBefore() {
        assertNull("absent stays absent on the tokens", Surfaces.call(null, colors))
        // Graine Classic's call screen — the dark one, in both schemes — in the scheme's accent.
        val d = Surfaces.defaultCall(Design.CLASSIC_LIGHT.colors)
        assertEquals(Surfaces.CLASSIC_CALL, d)
        assertEquals("#0E0F11", d.background)
        val brand = Surfaces.defaultCall(Design.CLASSIC_LIGHT.colors.copy(accent = "#7C3AED", onAccent = "#FFFFFF"))
        assertEquals("#7C3AED", brand.avatarFill)
        assertEquals("#7C3AED", brand.callbackFill)
        val chat = Surfaces.defaultChat(Design.CLASSIC_LIGHT.colors)
        assertEquals("Message…", chat.placeholder)
        assertEquals("none", chat.pattern)
        assertEquals(Design.CLASSIC_LIGHT.colors.surface, chat.headerBackground)
        assertTrue(chat.suggestions.isEmpty())
        assertEquals(d, Surfaces.callOf(Design.CLASSIC_LIGHT.copy(call = null)))
    }

    @Test fun aPartialOrMalformedObjectFillsFromTheDefaultsAndUnknownWordsAreTheFirstListed() {
        val c = Surfaces.call(JSONObject("""{"avatar":"hologram","endShape":"triangle","ringtone":"opera","controls":["mute","dance","mute"],"backgroundTo":null,
            "background":"not a colour","statusText":{"ringing":"Ring ring"}}"""), colors)!!
        assertEquals("a newer server's word: the first listed", "logo", c.avatar)
        assertEquals("round", c.endShape)
        assertEquals("system", c.ringtone)
        assertEquals("an unknown control is skipped, and end is always there", listOf("mute", "end"), c.controls)
        assertNull("JSON null: a solid background", c.backgroundTo)
        assertEquals(Surfaces.CLASSIC_CALL.background, c.background)
        assertEquals("Ring ring", c.statusText.ringing)
        assertEquals("Connecting…", c.statusText.connecting)
        val chat = Surfaces.chat(JSONObject("""{"avatar":"hologram","pattern":"stripes"}"""), colors)!!
        assertEquals("logo", chat.avatar)
        assertEquals("none", chat.pattern)
    }

    @Test fun theScheduledAndMissedWordsAreOptionalWithDefaults() {
        val plain = Surfaces.call(fullCall, colors)!!.statusText
        assertNull(plain.scheduled)
        assertEquals("Scheduled call", plain.scheduledText)
        assertEquals("Missed call", plain.missedText)
        val withThem = JSONObject(fullCall.toString())
        withThem.getJSONObject("statusText").put("scheduled", "Your booked call").put("missed", "You missed Acme")
        val c = Surfaces.call(withThem, colors)!!
        assertEquals("Your booked call", c.statusText.scheduledText)
        assertEquals("You missed Acme", c.statusText.missedText)
        assertEquals("written back when sent", canon(withThem), canon(Surfaces.json(c)))
        assertFalse("never invented when not", Surfaces.json(Surfaces.call(fullCall, colors)!!).getJSONObject("statusText").has("missed"))
    }

    @Test fun aDesignCarriesItsSurfacesThroughParsingAndBack() {
        val design = JSONObject()
            .put("config", JSONObject().put("preset", "graine-classic"))
            .put("light", JSONObject().put("colors", JSONObject().put("accent", "#7C3AED")).put("call", fullCall).put("chat", fullChat))
            .put("dark", JSONObject().put("colors", JSONObject().put("accent", "#7C3AED")))
        val d = ResolvedDesign.parse(design)!!
        assertNotNull(d.light.call)
        assertNotNull(d.light.chat)
        assertNull("the dark scheme sent none", d.dark.call)
        val light = d.toJson().getJSONObject("light")
        assertEquals(canon(fullCall), canon(light.getJSONObject("call")))
        assertFalse(d.toJson().getJSONObject("dark").has("call"))
        // A design from before these tokens writes back exactly as it was.
        val older = Design.CLASSIC_LIGHT.copy(chat = null, call = null)
        assertFalse(older.toJson().has("call"))
        assertFalse(older.toJson().has("chat"))
        // Graine Classic itself carries both, as the server resolves it.
        assertEquals(Surfaces.CLASSIC_CALL, Design.CLASSIC_LIGHT.call)
        assertEquals(Surfaces.CLASSIC_CHAT_DARK, Design.CLASSIC_DARK.chat)
    }

    // ── the call screen's rules ────────────────────────────────────────────

    private val look = Surfaces.CLASSIC_CALL

    @Test fun theSixMomentsAreTold() {
        val m = CallScreen
        assertEquals(CallScreen.Moment.SCHEDULED_RING, m.moment(GraineCallState.RINGING, true, hosted = true, missed = false))
        assertEquals(CallScreen.Moment.RING, m.moment(GraineCallState.RINGING, true, hosted = false, missed = false))
        assertEquals(CallScreen.Moment.CONNECTING, m.moment(GraineCallState.CONNECTING, true, false, false))
        assertEquals(CallScreen.Moment.ON_CALL, m.moment(GraineCallState.ACTIVE, true, false, false))
        assertEquals(CallScreen.Moment.ENDED, m.moment(GraineCallState.IDLE, true, false, false))
        assertEquals("a stale screen is an ended one", CallScreen.Moment.ENDED, m.moment(GraineCallState.ACTIVE, false, false, false))
        assertEquals(CallScreen.Moment.MISSED, m.moment(GraineCallState.IDLE, false, true, missed = true))
    }

    @Test fun theStatusSaysWhereTheCallIsInTheDesignsWords() {
        val s = CallScreen
        assertEquals("Incoming call", s.status(CallScreen.Moment.RING, look))
        assertEquals("a call Graine rang at a booked time", "Scheduled call", s.status(CallScreen.Moment.SCHEDULED_RING, look))
        assertEquals("Connecting…", s.status(CallScreen.Moment.CONNECTING, look))
        assertEquals("4:07", s.status(CallScreen.Moment.ON_CALL, look, 247_000))
        assertEquals("4:07 · Muted", s.status(CallScreen.Moment.ON_CALL, look, 247_000, muted = true))
        assertEquals("Call ended", s.status(CallScreen.Moment.ENDED, look))
        assertEquals("Call failed", s.status(CallScreen.Moment.ENDED, look, ending = CallScreen.Ending.FAILED))
        assertEquals("Declined", s.status(CallScreen.Moment.ENDED, look, ending = CallScreen.Ending.DECLINED))
        assertEquals("Missed call", s.status(CallScreen.Moment.MISSED, look))
        val noTimer = look.copy(timer = false)
        assertEquals("Listening", s.status(CallScreen.Moment.ON_CALL, noTimer, 5_000))
        assertEquals("Speaking", s.status(CallScreen.Moment.ON_CALL, noTimer, 5_000, agentSpeaking = true))
        assertEquals("Muted", s.status(CallScreen.Moment.ON_CALL, noTimer, 5_000, muted = true, agentSpeaking = true))
        val words = look.copy(statusText = CallStatusText("Acme is calling", "Joining…", "Bye", "Booked call", "Missed Acme"))
        assertEquals("Booked call", s.status(CallScreen.Moment.SCHEDULED_RING, words))
        assertEquals("Missed Acme", s.status(CallScreen.Moment.MISSED, words))
        assertEquals(CallScreen.Ending.FAILED, s.ending(GraineCallOutcome.failed("no network")))
        assertEquals(CallScreen.Ending.FAILED, s.ending(GraineCallOutcome.ended(GraineCallOutcome.EndReason.FAILED)))
        assertEquals(CallScreen.Ending.DECLINED, s.ending(GraineCallOutcome.DECLINED))
        assertEquals(CallScreen.Ending.NORMAL, s.ending(GraineCallOutcome.ended(GraineCallOutcome.EndReason.REMOTE)))
    }

    @Test fun theControlsAreTheDesignsAndAlwaysEndTheCall() {
        assertEquals("a native call holds the line: no chat", listOf("speaker", "mute", "end"), CallScreen.controls(listOf("speaker", "mute", "chat", "end")))
        assertEquals(listOf("mute", "chat", "end"), CallScreen.controls(listOf("mute", "chat", "end"), chatAvailable = true))
        assertEquals("nobody is left without a way to hang up", listOf("mute", "end"), CallScreen.controls(listOf("mute", "mute")))
        assertEquals(listOf("end"), CallScreen.controls(emptyList()))
        assertEquals(64.0, CallScreen.pillWidth(40.0), 0.0)
        assertEquals(112.0, CallScreen.pillWidth(120.0), 0.0)
    }

    @Test fun theTimerShapeAndMotionFollowTheContract() {
        assertEquals("0:00", CallScreen.duration(-5))
        assertEquals("0:59", CallScreen.duration(59_999))
        assertEquals("1:02:09", CallScreen.duration(3_729_000))
        assertEquals(200L, CallScreen.motionMs(ThemeMotion(200, "standard", 0.0), 1f))
        assertEquals("no motion: still", 0L, CallScreen.motionMs(ThemeMotion(0, "none", 0.0), 1f))
        assertEquals("the phone's own setting wins", 0L, CallScreen.motionMs(ThemeMotion(200, "standard", 0.0), 0f))
        assertNull(CallScreen.avatarCorner("circle", 112.0))
        assertEquals(112.0 * 0.30, CallScreen.avatarCorner("squircle", 112.0)!!, 1e-9)
        assertEquals(112.0 * 0.25, CallScreen.avatarCorner("rounded-square", 112.0)!!, 1e-9)
        assertTrue("light text, light icons", CallScreen.lightBarIcons(look))
        assertFalse(CallScreen.lightBarIcons(look.copy(text = "#101318")))
        assertEquals("#101820" to "#203040", CallScreen.background(Surfaces.call(fullCall, colors)!!))
        assertEquals(look.background to look.background, CallScreen.background(look))
        assertEquals("A", CallScreen.initial("  asha"))
        assertEquals("स", CallScreen.initial("सहायता").take(1))
        assertEquals("", CallScreen.initial(" "))
    }

    /** JSON in one comparable shape. */
    private fun canon(v: Any?): Any? = when {
        v == null || v == JSONObject.NULL -> null
        v is JSONObject -> java.util.TreeMap<String, Any?>().also { m -> v.keys().forEach { k -> m[k] = canon(v.opt(k)) } }
        v is JSONArray -> (0 until v.length()).map { canon(v.opt(it)) }
        v is Number -> v.toDouble()
        else -> v
    }
}
