package ai.graine.inapp

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import org.json.JSONObject
import java.util.Date

/**
 * DEBUG BUILDS ONLY — the SDK's own chat window, drawn in a design with a
 * conversation made up on the device: no server, no agent, no key. For
 * looking at a chat style (and taking its screenshots) before anyone ships
 * it. Not part of the published library: this file lives in the debug source
 * set, so the release AAR never contains it.
 *
 *     setContentView(GraineChatPreview.view(activity, design, dark = false, moment = GraineChatPreview.CONVERSATION))
 *
 * The window is the real one ([ChatWindowView], as [GraineChatView] draws it):
 * the header, the thread, a component card, the quick replies, the composer.
 */
object GraineChatPreview {
    /** A greeting, a question, a reply with a component card and quick replies. */
    const val CONVERSATION = "conversation"
    /** The same, the customer's next message waiting for a reply: the typing indicator. */
    const val TYPING = "typing"
    /** The conversation ended by the agent: the rating card. */
    const val RATING = "rating"
    /** The connection dropped and is being made again: the reconnect banner. */
    const val RECONNECTING = "reconnecting"
    /** Offline: a message waiting to be sent, one that could not be, and the banner that says so. */
    const val OFFLINE = "offline"
    /** Yesterday's conversation, then today's welcome after "Disconnected · new conversation": the server could not continue it. */
    const val NEW_CONVERSATION = "fresh"
    /** Graine ended the conversation itself: the lines stay and the header says "Disconnected". */
    const val DISCONNECTED = "disconnected"

    private const val AGENT = "graine-chat-preview"

    /** The preview's agent: a real one on a made-up session — nothing reaches a network. */
    private fun agent(activity: Activity, design: ResolvedDesign): GraineInApp {
        val session = JSONObject()
            .put("agentId", "preview")
            .put("webcallAgentId", "preview")
            .put("appearance", JSONObject().put("title", "Assistant").put("mode", "both"))
            .put("design", design.toJson())
        val g = GraineInApp.make(activity.application, GraineConfig("https://preview.invalid", "pk_preview"), AGENT) {
            AgentEnv(
                http = HttpPost { _, _ -> HttpReply(503, "{}") },
                fetchSession = { _, _ -> session },
                openSocket = { _, _, _ -> throw java.io.IOException("The preview has no connection.") },
                fonts = false,
            )
        }
        g.previewDesign(design)
        return g
    }

    /**
     * The colours behind the status bar and the navigation bar for the window
     * in [design]: the header's fill (a plain header's is the wallpaper's top)
     * and the window's bottom (the composer's strip, else the wallpaper's).
     */
    @JvmStatic
    fun barColors(design: ResolvedDesign, dark: Boolean): IntArray {
        val s = Surfaces.styleOf(if (dark) design.dark else design.light)
        return intArrayOf(Themed.color(s.header.fill.color), Themed.color(s.composer.strip ?: s.wallpaper.colorTo ?: s.wallpaper.color))
    }

    /**
     * The chat window in [design] — light, or [dark] — at [moment]: a whole
     * screen, as an app's GraineChatView is; or [compact], the window as it
     * rides above the voice bar.
     */
    @JvmStatic
    @JvmOverloads
    fun view(activity: Activity, design: ResolvedDesign, dark: Boolean, moment: String = CONVERSATION, compact: Boolean = false): ViewGroup {
        val g = agent(activity, design)
        val t = if (dark) design.dark else design.light
        val scheme = if (dark) "dark" else "light"
        val root = FrameLayout(activity)
        val window = ChatWindowView(activity, g, if (compact) ChatFrame.WINDOW else ChatFrame.PAGE)
        val widgets = GraineWidgetView(activity).apply { graine = g; this.scheme = scheme }
        window.setAccessory(widgets)
        // Every button the design may draw is offered: the microphone and the call button call, the paperclip attaches.
        window.onCall = {}
        window.onAttach = {}
        window.onSend = {}
        if (compact) {
            // Above where the voice bar would sit: 12 in from the sides, on the app's own page.
            root.setBackgroundColor(Themed.color(t.colors.background))
            val side = Ui.dp(activity, 12)
            root.addView(window, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, android.view.Gravity.BOTTOM)
                .apply { setMargins(side, side, side, Ui.dp(activity, 84)) })
        } else {
            root.addView(window, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val now = System.currentTimeMillis()
        fun at(minutesAgo: Int) = Date(now - minutesAgo * 60_000L)
        val greeting = GraineChatTurn("p1", Role.AGENT, "Hi there! I'm here to help with your account. What would you like to do today?", at(6), proactive = true)
        val question = GraineChatTurn("p2", Role.CUSTOMER, "Can you help me update my contact details?", at(5))
        val reply = GraineChatTurn("p3", Role.AGENT,
            "Of course! I can update these for you:\n- your **email address**\n- your phone number\n\nWhich one would you like to change?", at(5))
        val follow = GraineChatTurn("p4", Role.CUSTOMER, "The email, please.", at(1))
        val done = GraineChatTurn("p5", Role.AGENT, "All done — your new email is saved. Is there anything else I can help with?", at(1))
        val notSent = GraineChatTurn("p6", Role.CUSTOMER, "Can I change my phone number too?", at(1), delivery = GraineChatTurn.Delivery.FAILED)
        val waiting = GraineChatTurn("p7", Role.CUSTOMER, "Thanks!", at(0), delivery = GraineChatTurn.Delivery.SENDING)
        val again = greeting.copy(id = "p8", at = at(0))
        val messages = when (moment) {
            TYPING -> listOf(greeting, question, reply, follow)
            RATING -> listOf(greeting, question, reply, follow, done)
            OFFLINE -> listOf(greeting, question, reply, follow, done, notSent, waiting)
            NEW_CONVERSATION -> listOf(greeting.copy(at = at(26 * 60)), question.copy(at = at(26 * 60 - 1)), reply.copy(at = at(26 * 60 - 1)), again)
            else -> listOf(greeting, question, reply)
        }
        val online = moment != RECONNECTING && moment != OFFLINE && moment != DISCONNECTED
        val replies = if (moment == CONVERSATION) listOf(GraineQuickReply("Email", "Email"), GraineQuickReply("Phone number", "Phone number"),
            GraineQuickReply("Talk to a person", "I'd like to talk to a person")) else emptyList()
        val pin = GraineChatPin("Account settings", "Profile, email and phone", badge = "Active")
        val heading = "Assistant"
        window.render(
            t, Themed.color(t.colors.accent), Themed.color(t.colors.onAccent), heading, null, null, LogoPlate.ACCENT,
            messages, isOnline = online, replying = moment == TYPING,
            extras = ChatExtras(
                quickReplies = replies, ended = moment == RATING, pin = pin, everConnected = true, canCall = true, canAttach = true,
                connecting = moment == RECONNECTING, reconnecting = moment == RECONNECTING,
                disconnected = moment == DISCONNECTED, marks = if (moment == NEW_CONVERSATION) setOf(again.id) else emptySet(),
            ),
        )
        if (moment == CONVERSATION) widgets.present(JSONObject("""
            {"widget_id":"preview:details:1","kind":"choices","fallback_text":"Email or phone?","style":{"surface":"card"},
             "data":{"title":"What would you like to update?","options":[{"id":"email","label":"Email address"},{"id":"phone","label":"Phone number"}]}}
        """.trimIndent()))
        return root
    }
}
