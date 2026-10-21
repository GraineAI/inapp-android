package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Date
import java.util.TimeZone

/**
 * THE PIECES THE GARAGE SETS (contract/DESIGN.md → Chat window: Booked calls,
 * Earlier messages, New conversation): the four chat tokens as the session
 * serves them — on, and the SDK's words, when absent or of the wrong type —
 * and what the chat window draws of them, run against
 * sdk/contract/history.json → `display`, the same cases every SDK runs.
 */
class ChatPiecesTest {
    private fun contract(name: String): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "sdk/contract/$name").exists() && !File(dir, "contract/$name").exists()) dir = dir.parentFile
        val f = listOf(File(dir, "sdk/contract/$name"), File(dir, "contract/$name")).firstOrNull { it.exists() }
            ?: error("sdk/contract/$name missing — this contract is never skipped")
        return JSONObject(f.readText())
    }

    // Any scheme's colours do: the pieces are not colours.
    private val colors = Design.CLASSIC_LIGHT.colors

    private fun ms(iso: String): Long = ChatHistory.time(iso) ?: error("not a time: $iso")

    @Test fun theSessionsTokensAreReadAsServedTheSameInLightAndDark() {
        val cases = contract("design.json").getJSONArray("cases")
        val off = BooleanArray(4)
        for (i in 0 until cases.length()) {
            val raw = cases.getJSONObject(i).getJSONObject("design")
            val design = ResolvedDesign.parse(raw.toString())!!
            for ((scheme, tokens) in listOf("light" to design.light, "dark" to design.dark)) {
                val served = raw.getJSONObject(scheme).getJSONObject("chat")
                val chat = tokens.chat!!
                assertEquals("$scheme showUpcomingCalls", served.getBoolean("showUpcomingCalls"), chat.showUpcomingCalls)
                assertEquals("$scheme upcomingTitle", served.getString("upcomingTitle"), chat.upcomingTitle)
                assertEquals("$scheme showHistory", served.getBoolean("showHistory"), chat.showHistory)
                assertEquals("$scheme newConversation", served.getBoolean("newConversation"), chat.newConversation)
                if (!chat.showUpcomingCalls) off[0] = true
                if (!chat.showHistory) off[1] = true
                if (!chat.newConversation) off[2] = true
                if (chat.upcomingTitle.isNotEmpty()) off[3] = true
            }
            assertEquals("not a colour: one value for both schemes", design.light.chat!!.showHistory, design.dark.chat!!.showHistory)
        }
        assertTrue("the contract turns each away from its default", off.all { it })
    }

    @Test fun absentOrTheWrongTypeIsOnAndTheSDKsWords() {
        val older = Surfaces.chat(JSONObject(), colors)!!
        assertTrue(older.showUpcomingCalls && older.showHistory && older.newConversation)
        assertEquals("", older.upcomingTitle)
        for (odd in listOf<Any>("false", 0, JSONObject.NULL, JSONArray().put("off"), JSONObject().put("showHistory", false))) {
            val t = Surfaces.chat(JSONObject().put("showUpcomingCalls", odd).put("showHistory", odd).put("newConversation", odd), colors)!!
            assertTrue("$odd: never read as off", t.showUpcomingCalls && t.showHistory && t.newConversation)
        }
        for (odd in listOf<Any>(7, false, JSONObject.NULL, JSONArray().put("Calls"), "   ")) {
            assertEquals("$odd: the SDK's words", "", Surfaces.chat(JSONObject().put("upcomingTitle", odd), colors)!!.upcomingTitle)
        }
        val off = Surfaces.chat(JSONObject().put("showUpcomingCalls", false).put("showHistory", false).put("newConversation", false)
            .put("upcomingTitle", "  Your calls  "), colors)!!
        assertFalse(off.showUpcomingCalls || off.showHistory || off.newConversation)
        assertEquals("Your calls", off.upcomingTitle)
        // Written back as the session spelled them, and read again the same.
        assertEquals(off, Surfaces.chat(Surfaces.json(off), colors))
    }

    @Test fun whatTheWindowDrawsIsTheContractsDisplay() {
        val cases = contract("history.json").getJSONArray("display")
        assertTrue(cases.length() >= 8)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val name = c.getString("name")
            val zone = TimeZone.getTimeZone(c.getString("timeZone"))
            val chat = Surfaces.chat(c.getJSONObject("chat"), colors)!!
            val raw = c.getJSONArray("turns")
            val turns = (0 until raw.length()).map { k ->
                val t = raw.getJSONObject(k)
                GraineChatTurn(t.getString("id"), if (t.getString("role") == "agent") Role.AGENT else Role.CUSTOMER, t.getString("text"), Date(ms(t.getString("at"))),
                    restored = t.optBoolean("restored", false),
                    channel = if (t.optString("channel") == "voice") GraineChatTurn.Channel.VOICE else GraineChatTurn.Channel.CHAT)
            }
            val p = ChatPieces.of(turns, chat, c.getInt("upcoming"))
            val shown = c.getJSONArray("shown").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals("$name: the turns drawn", shown, p.turns.map { it.id })
            val rows = ChatRows.build(p.turns, Date(ms(c.getString("now"))), zone).map { row ->
                when (row) {
                    is ChatRows.Row.Day -> "day:${row.key}"
                    is ChatRows.Row.Voice -> "voice:${row.first}-${row.last}"
                    is ChatRows.Row.Turn -> "turn:${p.turns.indexOfFirst { it.id == row.turn.id }}"
                    is ChatRows.Row.NewConversation -> "fresh:${row.before}"
                }
            }
            val wantRows = c.getJSONArray("rows")
            val want = (0 until wantRows.length()).map { k ->
                val r = wantRows.getJSONObject(k)
                when {
                    r.has("day") -> "day:${r.getString("day")}"
                    r.has("voice") -> "voice:${r.getJSONArray("voice").getInt(0)}-${r.getJSONArray("voice").getInt(1)}"
                    else -> "turn:${r.getInt("turn")}"
                }
            }
            assertEquals("$name: rows", want, rows)
            assertEquals("$name: starters", c.getBoolean("starters"), p.starters)
            assertEquals("$name: New conversation", c.getBoolean("newConversation"), p.newConversation)
            assertEquals("$name: the booked-calls card", c.getBoolean("card"), p.upcoming)
            assertEquals("$name: its title", c.getString("cardTitle"), p.upcomingTitle)
        }
    }

    @Test fun anOlderServerWithNoChatTokensDrawsEverything() {
        val restored = GraineChatTurn("h1", Role.CUSTOMER, "Hi", Date(), restored = true)
        val p = ChatPieces.of(listOf(restored), null, 2)
        assertTrue(p.turns.size == 1 && p.newConversation && !p.starters && p.upcoming)
        assertEquals("Upcoming calls", p.upcomingTitle)
        assertEquals("Upcoming call", ChatPieces.of(emptyList(), null, 1).upcomingTitle)
    }
}
