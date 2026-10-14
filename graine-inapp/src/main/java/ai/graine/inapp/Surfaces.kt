package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject

/*
 * THE CHAT WINDOW AND THE CALL SCREEN, AS THE DESIGN DRAWS THEM — contract
 * §7: the session's tokens gain `chat` and `call` per scheme, resolved by the
 * server into flat values. A server that predates them sends neither; the SDK
 * then derives the same shapes from the scheme's colours, so every screen is
 * drawn from one set of values either way. Pure, and JVM-tested (SurfacesTest).
 */

/** The chat window's resolved tokens (one scheme). Colours "#RRGGBB". */
data class ChatTokens(
    /** The header's title; empty: the agent's heading. */
    val title: String,
    /** The header's second line while online; empty: "Online". */
    val subtitle: String,
    /** The header's mark: "logo", "glyph" or "none". */
    val avatar: String,
    val placeholder: String,
    val showTimestamps: Boolean,
    /** At most four openers, shown before the first message; a tap sends one. */
    val suggestions: List<String>,
    val background: String,
    /** "none" or "dots": dots over the thread, in [patternColor]. */
    val pattern: String,
    val patternColor: String,
    val headerBackground: String,
    val headerText: String,
    val headerSubtext: String,
    /** The message field's fill, text, placeholder and outline. */
    val composerBackground: String,
    val composerText: String,
    val composerPlaceholder: String,
    val composerBorder: String,
    /** How big the window opens (Window size in the dashboard): [GraineChatSize.COMPACT] unless the design says otherwise. */
    val size: GraineChatSize = GraineChatSize.COMPACT,
    /** The card of the user's booked calls at the top of the chat (Booked calls in the dashboard). */
    val showUpcomingCalls: Boolean = true,
    /** That card's title; empty: "Upcoming call" / "Upcoming calls". */
    val upcomingTitle: String = "",
    /** The conversation kept from before, on screen (Earlier messages). Off, the agent still has it. */
    val showHistory: Boolean = true,
    /** The header's compose control (New conversation button). Off, [GraineChat.newConversation] still works. */
    val newConversation: Boolean = true,
)

/**
 * What the call screen says while it rings, connects, and once it is over —
 * and, when the server sends them, for a call Graine rang at a booked time
 * ([scheduled]) and for a ring nobody answered ([missed]). Absent, those two
 * read "Scheduled call" and "Missed call".
 */
data class CallStatusText @JvmOverloads constructor(
    val ringing: String,
    val connecting: String,
    val ended: String,
    val scheduled: String? = null,
    val missed: String? = null,
) {
    /** The ring of a call Graine rang (booked by the agent, the user or the dashboard). */
    val scheduledText: String get() = scheduled?.takeIf { it.isNotBlank() } ?: DEFAULT_SCHEDULED
    /** A ring nobody answered — on the screen, and in the missed-call notification. */
    val missedText: String get() = missed?.takeIf { it.isNotBlank() } ?: DEFAULT_MISSED

    companion object {
        const val DEFAULT_SCHEDULED = "Scheduled call"
        const val DEFAULT_MISSED = "Missed call"
    }
}

/** The call screen's resolved tokens (one scheme): Android's full-screen ring and in-call screen. Colours "#RRGGBB". */
data class CallTokens(
    val background: String,
    /** The gradient's other end, top to bottom; null: a solid background. */
    val backgroundTo: String?,
    val text: String,
    val textMuted: String,
    /** "logo", "glyph" or "initials". */
    val avatar: String,
    /** "circle", "squircle" or "rounded-square". */
    val avatarShape: String,
    val avatarFill: String,
    val avatarText: String,
    val buttonFill: String,
    val buttonIcon: String,
    /** A control that is on (muted, the loudspeaker). */
    val buttonActiveFill: String,
    val buttonActiveIcon: String,
    val endFill: String,
    val endIcon: String,
    /** "round" or "pill". */
    val endShape: String,
    val acceptFill: String,
    val acceptIcon: String,
    val declineFill: String,
    val declineIcon: String,
    /** Call back, on a missed call. */
    val callbackFill: String,
    val callbackIcon: String,
    /** Show what is being said. */
    val captions: Boolean,
    /** Show how long the call has lasted. */
    val timer: Boolean,
    /** The in-call controls, in order: "mute", "speaker", "chat", "end". */
    val controls: List<String>,
    /** "system", "graine" or "soft". */
    val ringtone: String,
    val statusText: CallStatusText,
)

internal object Surfaces {
    val CHAT_AVATARS = listOf("logo", "glyph", "none")
    val PATTERNS = listOf("none", "dots")
    val CALL_AVATARS = listOf("logo", "glyph", "initials")
    val AVATAR_SHAPES = listOf("circle", "squircle", "rounded-square")
    val END_SHAPES = listOf("round", "pill")
    val CONTROLS = listOf("mute", "speaker", "chat", "end")
    val RINGTONES = listOf("system", "graine", "soft")
    const val SUGGESTIONS_MAX = 4
    const val SUGGESTION_LENGTH = 40
    const val WHITE = "#FFFFFF"

    val DEFAULT_STATUS = CallStatusText("Incoming call", "Connecting…", "Call ended")

    // ── the server's, read ─────────────────────────────────────────────────

    private fun hex(o: JSONObject, k: String, fallback: String): String = Design.normaliseHex(o.opt(k) as? String) ?: fallback
    /** A word from its list; absent, the fallback; one this build does not know (a newer server's), the list's first. */
    private fun word(o: JSONObject, k: String, allowed: List<String>, fallback: String): String {
        if (!o.has(k)) return fallback
        return (o.opt(k) as? String)?.trim()?.lowercase()?.takeIf { it in allowed } ?: allowed[0]
    }
    private fun text(o: JSONObject, k: String, fallback: String): String = (o.opt(k) as? String) ?: fallback
    private fun flag(o: JSONObject, k: String, fallback: Boolean): Boolean = o.opt(k) as? Boolean ?: fallback

    /** `tokens.chat` → its values over the derived ones; null when the server sent none (an older one). */
    fun chat(raw: Any?, colors: ThemeColors): ChatTokens? {
        val o = raw as? JSONObject ?: return null
        val d = defaultChat(colors)
        return ChatTokens(
            title = text(o, "title", d.title),
            subtitle = text(o, "subtitle", d.subtitle),
            avatar = word(o, "avatar", CHAT_AVATARS, d.avatar),
            placeholder = text(o, "placeholder", d.placeholder),
            showTimestamps = flag(o, "showTimestamps", d.showTimestamps),
            suggestions = (o.opt("suggestions") as? JSONArray)?.let { a ->
                (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.takeIf { s -> s.isNotEmpty() }?.take(SUGGESTION_LENGTH) }
                    .take(SUGGESTIONS_MAX)
            } ?: d.suggestions,
            background = hex(o, "background", d.background),
            pattern = word(o, "pattern", PATTERNS, d.pattern),
            patternColor = hex(o, "patternColor", d.patternColor),
            headerBackground = hex(o, "headerBackground", d.headerBackground),
            headerText = hex(o, "headerText", d.headerText),
            headerSubtext = hex(o, "headerSubtext", d.headerSubtext),
            composerBackground = hex(o, "composerBackground", d.composerBackground),
            composerText = hex(o, "composerText", d.composerText),
            composerPlaceholder = hex(o, "composerPlaceholder", d.composerPlaceholder),
            composerBorder = hex(o, "composerBorder", d.composerBorder),
            // Absent (an older server) or a word this build does not know: the compact window.
            size = GraineChatSize.from(o.opt("size")) ?: d.size,
            // The pieces the brand can leave out: absent, or anything but a boolean, is on.
            showUpcomingCalls = flag(o, "showUpcomingCalls", d.showUpcomingCalls),
            upcomingTitle = text(o, "upcomingTitle", d.upcomingTitle).trim(),
            showHistory = flag(o, "showHistory", d.showHistory),
            newConversation = flag(o, "newConversation", d.newConversation),
        )
    }

    /** `tokens.call` → its values over the derived ones; null when the server sent none (an older one). */
    fun call(raw: Any?, colors: ThemeColors): CallTokens? {
        val o = raw as? JSONObject ?: return null
        val d = defaultCall(colors)
        val status = o.opt("statusText") as? JSONObject
        return CallTokens(
            background = hex(o, "background", d.background),
            backgroundTo = if (o.has("backgroundTo")) Design.normaliseHex(o.opt("backgroundTo") as? String) else d.backgroundTo,
            text = hex(o, "text", d.text),
            textMuted = hex(o, "textMuted", d.textMuted),
            avatar = word(o, "avatar", CALL_AVATARS, d.avatar),
            avatarShape = word(o, "avatarShape", AVATAR_SHAPES, d.avatarShape),
            avatarFill = hex(o, "avatarFill", d.avatarFill),
            avatarText = hex(o, "avatarText", d.avatarText),
            buttonFill = hex(o, "buttonFill", d.buttonFill),
            buttonIcon = hex(o, "buttonIcon", d.buttonIcon),
            buttonActiveFill = hex(o, "buttonActiveFill", d.buttonActiveFill),
            buttonActiveIcon = hex(o, "buttonActiveIcon", d.buttonActiveIcon),
            endFill = hex(o, "endFill", d.endFill),
            endIcon = hex(o, "endIcon", d.endIcon),
            endShape = word(o, "endShape", END_SHAPES, d.endShape),
            acceptFill = hex(o, "acceptFill", d.acceptFill),
            acceptIcon = hex(o, "acceptIcon", d.acceptIcon),
            declineFill = hex(o, "declineFill", d.declineFill),
            declineIcon = hex(o, "declineIcon", d.declineIcon),
            callbackFill = hex(o, "callbackFill", d.callbackFill),
            callbackIcon = hex(o, "callbackIcon", d.callbackIcon),
            captions = flag(o, "captions", d.captions),
            timer = flag(o, "timer", d.timer),
            // An unknown control is skipped; `end` is always there.
            controls = (o.opt("controls") as? JSONArray)?.let { a ->
                (0 until a.length()).mapNotNull { (a.opt(it) as? String)?.trim()?.lowercase()?.takeIf { c -> c in CONTROLS } }.distinct()
                    .let { if ("end" in it) it else it + "end" }
            } ?: d.controls,
            ringtone = word(o, "ringtone", RINGTONES, d.ringtone),
            statusText = CallStatusText(
                ringing = status?.let { text(it, "ringing", d.statusText.ringing) } ?: d.statusText.ringing,
                connecting = status?.let { text(it, "connecting", d.statusText.connecting) } ?: d.statusText.connecting,
                ended = status?.let { text(it, "ended", d.statusText.ended) } ?: d.statusText.ended,
                // Optional: a server that does not send them reads the defaults (scheduledText, missedText).
                scheduled = status?.opt("scheduled") as? String,
                missed = status?.opt("missed") as? String,
            ),
        )
    }

    // ── derived, for a server that sends none ──────────────────────────────

    /** The chat window as it was drawn before these tokens: the page, a surface header, a raised field, no pattern. */
    fun defaultChat(c: ThemeColors): ChatTokens = ChatTokens(
        title = "", subtitle = "", avatar = "logo", placeholder = "Message…", showTimestamps = false, suggestions = emptyList(),
        background = c.background, pattern = "none", patternColor = c.border,
        headerBackground = c.surface, headerText = c.text, headerSubtext = c.textMuted,
        composerBackground = c.surfaceRaised, composerText = c.text, composerPlaceholder = c.textMuted, composerBorder = c.border,
    )

    /**
     * Graine Classic's call screen (design.json → the first case's `call`),
     * what a server that sends no `call` — and a cold start before any
     * session — draws: the dark screen in both schemes. The avatar and Call
     * back take the scheme's accent, so a brand on an older server keeps its colour.
     */
    val CLASSIC_CALL = CallTokens(
        background = "#0E0F11", backgroundTo = null, text = "#F5F5F4", textMuted = "#A0A5AE",
        avatar = "logo", avatarShape = "circle", avatarFill = "#318CE7", avatarText = "#FFFFFF",
        buttonFill = "#333435", buttonIcon = "#F5F5F4", buttonActiveFill = "#F5F5F4", buttonActiveIcon = "#0E0F11",
        endFill = "#DC2626", endIcon = WHITE, endShape = "round",
        acceptFill = "#16A34A", acceptIcon = WHITE, declineFill = "#DC2626", declineIcon = WHITE,
        callbackFill = "#318CE7", callbackIcon = WHITE,
        captions = true, timer = true, controls = listOf("mute", "speaker", "chat", "end"), ringtone = "system",
        statusText = CallStatusText("Incoming call", "Connecting…", "Call ended", "Scheduled call", "Missed call"),
    )

    /** Graine Classic's chat window, light (design.json → the first case's `light.chat`). */
    val CLASSIC_CHAT_LIGHT = ChatTokens(
        title = "", subtitle = "", avatar = "logo", placeholder = "Message…", showTimestamps = false, suggestions = emptyList(),
        background = "#F7F7F5", pattern = "none", patternColor = "#E0E0DF",
        headerBackground = "#FFFFFF", headerText = "#101318", headerSubtext = "#5F6672",
        composerBackground = "#FFFFFF", composerText = "#101318", composerPlaceholder = "#5F6672", composerBorder = "#E5E4E0",
    )

    /** Graine Classic's chat window, dark. */
    val CLASSIC_CHAT_DARK = ChatTokens(
        title = "", subtitle = "", avatar = "logo", placeholder = "Message…", showTimestamps = false, suggestions = emptyList(),
        background = "#0E0F11", pattern = "none", patternColor = "#2E2F31",
        headerBackground = "#17181B", headerText = "#F5F5F4", headerSubtext = "#A0A5AE",
        composerBackground = "#1F2024", composerText = "#F5F5F4", composerPlaceholder = "#A0A5AE", composerBorder = "#2C2E33",
    )

    /** Classic's call screen in the scheme's accent. */
    fun defaultCall(c: ThemeColors): CallTokens =
        CLASSIC_CALL.copy(avatarFill = c.accent, avatarText = c.onAccent, callbackFill = c.accent, callbackIcon = c.onAccent)

    /** The chat window's tokens to draw: the server's, else derived. */
    fun chatOf(t: ThemeTokens): ChatTokens = t.chat ?: defaultChat(t.colors)

    /** The call screen's tokens to draw: the server's, else derived. */
    fun callOf(t: ThemeTokens): CallTokens = t.call ?: defaultCall(t.colors)

    // ── written back, as the session spelled them ──────────────────────────

    fun json(c: ChatTokens): JSONObject = JSONObject()
        .put("title", c.title).put("subtitle", c.subtitle).put("avatar", c.avatar).put("placeholder", c.placeholder)
        .put("showTimestamps", c.showTimestamps).put("suggestions", JSONArray(c.suggestions))
        .put("background", c.background).put("pattern", c.pattern).put("patternColor", c.patternColor)
        .put("headerBackground", c.headerBackground).put("headerText", c.headerText)
        .put("headerSubtext", c.headerSubtext).put("composerBackground", c.composerBackground).put("composerText", c.composerText)
        .put("composerPlaceholder", c.composerPlaceholder).put("composerBorder", c.composerBorder)
        .put("size", c.size.wire)
        .put("showUpcomingCalls", c.showUpcomingCalls).put("upcomingTitle", c.upcomingTitle)
        .put("showHistory", c.showHistory).put("newConversation", c.newConversation)

    fun json(c: CallTokens): JSONObject = JSONObject()
        .put("background", c.background).put("backgroundTo", c.backgroundTo ?: JSONObject.NULL)
        .put("text", c.text).put("textMuted", c.textMuted)
        .put("avatar", c.avatar).put("avatarShape", c.avatarShape).put("avatarFill", c.avatarFill).put("avatarText", c.avatarText)
        .put("buttonFill", c.buttonFill).put("buttonIcon", c.buttonIcon)
        .put("buttonActiveFill", c.buttonActiveFill).put("buttonActiveIcon", c.buttonActiveIcon)
        .put("endFill", c.endFill).put("endIcon", c.endIcon).put("endShape", c.endShape)
        .put("acceptFill", c.acceptFill).put("acceptIcon", c.acceptIcon)
        .put("declineFill", c.declineFill).put("declineIcon", c.declineIcon)
        .put("callbackFill", c.callbackFill).put("callbackIcon", c.callbackIcon)
        .put("captions", c.captions).put("timer", c.timer).put("controls", JSONArray(c.controls)).put("ringtone", c.ringtone)
        .put("statusText", JSONObject().put("ringing", c.statusText.ringing).put("connecting", c.statusText.connecting).put("ended", c.statusText.ended)
            .apply { c.statusText.scheduled?.let { put("scheduled", it) } }
            .apply { c.statusText.missed?.let { put("missed", it) } })
}

/**
 * THE CALL SCREEN'S RULES, AS NUMBERS AND WORDS — contract/DESIGN.md → Call
 * screen, its six moments: a scheduled call ringing, any other call ringing,
 * connecting, on the call, ended, missed. GraineCallActivity draws what these
 * say. Lengths dp, sizes sp.
 */
internal object CallScreen {
    const val AVATAR = 112.0
    const val AVATAR_TOP = 72.0
    const val AVATAR_GLYPH = 0.46
    const val AVATAR_LETTER = 0.40
    const val NAME_GAP = 20.0
    const val NAME_SP = 28.0
    const val STATUS_GAP = 6.0
    const val STATUS_SP = 16.0
    const val CAPTION_GAP = 36.0
    const val CAPTION_SP = 17.0
    const val CAPTION_LINE = 1.3
    const val CAPTION_LINES = 4
    const val BIG_BUTTON = 72.0
    const val BUTTON = 64.0
    const val ICON = 30.0
    const val LABEL_GAP = 8.0
    const val LABEL_SP = 13.0
    const val BUTTONS_BOTTOM = 56.0
    const val SIDE = 24.0
    /** The ring's halo: from the avatar's edge to this × its size, fading from [HALO_ALPHA], over [HALO_MS], again and again. */
    const val HALO_SCALE = 1.35f
    const val HALO_ALPHA = 0.6f
    const val HALO_MS = 1_600L
    const val HALO_WIDTH = 2.0
    /** The avatar while the agent's line is arriving. */
    const val SPEAKING_SCALE = 1.06f
    /** An ended call's buttons, and how long its screen stays. */
    const val ENDED_ALPHA = 0.4f
    const val ENDED_CLOSE_MS = 1_000L
    /** A missed call's screen closes by itself after this, untouched. */
    const val MISSED_CLOSE_MS = 30_000L

    /** The six moments, and the screen with nothing on it. */
    enum class Moment { SCHEDULED_RING, RING, CONNECTING, ON_CALL, ENDED, MISSED }

    /** How a call that is over ended, for the status line's words. */
    enum class Ending { NORMAL, FAILED, DECLINED }

    /**
     * The moment the screen shows. [missed] wins (a ring nobody answered);
     * then a call that is not this screen's, or is over, is ended; then the
     * call's own state.
     */
    fun moment(state: GraineCallState, mine: Boolean, hosted: Boolean, missed: Boolean): Moment = when {
        missed -> Moment.MISSED
        !mine || state == GraineCallState.IDLE || state == GraineCallState.ENDED -> Moment.ENDED
        state == GraineCallState.RINGING -> if (hosted) Moment.SCHEDULED_RING else Moment.RING
        state == GraineCallState.CONNECTING -> Moment.CONNECTING
        else -> Moment.ON_CALL
    }

    /** The avatar's corner radius at `size`: a circle (null), the squircle's 30%, or a rounded square's 25%. */
    fun avatarCorner(shape: String, size: Double): Double? = when (shape) {
        "squircle" -> size * 0.30
        "rounded-square" -> size * 0.25
        else -> null
    }

    /**
     * The in-call controls the screen draws, in the design's order: `chat`
     * only when a chat can be opened during this call — a native call holds
     * the line, so on Android it cannot, and the shares close up — each once,
     * and always an end button, whatever the design says.
     */
    @JvmOverloads
    fun controls(wanted: List<String>, chatAvailable: Boolean = false): List<String> {
        val out = wanted.filter { it == "mute" || it == "speaker" || it == "end" || (it == "chat" && chatAvailable) }.distinct().toMutableList()
        if ("end" !in out) out.add("end")
        return out
    }

    /** A pill end button's width in its share of the row: the share less 8, at least the button's height. */
    fun pillWidth(shareDp: Double): Double = maxOf(BUTTON, shareDp - 8)

    /** `ms` as the timer shows it: 4:07, or 1:02:09 past an hour. */
    fun duration(ms: Long): String {
        val s = Math.max(0L, ms) / 1000
        return if (s >= 3600) String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60)
    }

    /**
     * The status line, first match winning: missed; ended (the design's words
     * for a call that ended normally, "Call failed", "Declined"); a scheduled
     * ring; any other ring; connecting; on the call — the timer (" · Muted"
     * while muted) when the design shows it, else "Muted", "Speaking" while
     * the agent's line is arriving, "Listening".
     */
    @JvmOverloads
    fun status(
        moment: Moment, look: CallTokens, connectedForMs: Long? = null, muted: Boolean = false,
        agentSpeaking: Boolean = false, ending: Ending = Ending.NORMAL,
    ): String = when (moment) {
        Moment.MISSED -> look.statusText.missedText
        Moment.ENDED -> when (ending) {
            Ending.FAILED -> "Call failed"
            Ending.DECLINED -> "Declined"
            Ending.NORMAL -> look.statusText.ended
        }
        Moment.SCHEDULED_RING -> look.statusText.scheduledText
        Moment.RING -> look.statusText.ringing
        Moment.CONNECTING -> look.statusText.connecting
        Moment.ON_CALL -> when {
            look.timer -> duration(connectedForMs ?: 0) + if (muted) " · Muted" else ""
            muted -> "Muted"
            agentSpeaking -> "Speaking"
            else -> "Listening"
        }
    }

    /** How a call that ended, ended — from its outcome. */
    fun ending(outcome: GraineCallOutcome?): Ending = when (outcome?.kind) {
        GraineCallOutcome.Kind.FAILED -> Ending.FAILED
        GraineCallOutcome.Kind.DECLINED -> Ending.DECLINED
        else -> if (outcome?.endReason == GraineCallOutcome.EndReason.FAILED) Ending.FAILED else Ending.NORMAL
    }

    /** How long the avatar takes to scale while the agent speaks: the design's duration; 0 is none (no motion, or the phone said so). */
    fun motionMs(motion: ThemeMotion, animatorScale: Float): Long = Design.motionMs(motion, animatorScale)

    /** The two ends of the background, top to bottom (the same twice when it is solid). */
    fun background(look: CallTokens): Pair<String, String> = look.background to (look.backgroundTo ?: look.background)

    /** Light status-bar icons when the screen's text is light (WCAG luminance above 0.5), dark ones otherwise. */
    fun lightBarIcons(look: CallTokens): Boolean = Appearance.luminance(look.text) > 0.5

    /** The call name's first letter — its first grapheme, upper-cased where the script has case. */
    fun initial(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return ""
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(t)
        val end = it.next().takeIf { e -> e != java.text.BreakIterator.DONE } ?: t.length
        return t.substring(0, end).uppercase()
    }
}
