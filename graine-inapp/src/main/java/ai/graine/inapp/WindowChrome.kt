package ai.graine.inapp

/**
 * THE WINDOW CHROME'S RULES, AS NUMBERS — contract/DESIGN.md → Window chrome,
 * the playful-desktop look (Desktop Buddy's, and a knob on any design).
 *
 * With `chrome: "window"` the chat window, the caption card and every
 * component card open as desktop windows: a title bar across the top of the
 * surface — solid `surfaceRaised`, even under glass — three traffic lights at
 * its left, and the title centred on the whole bar in the platform's
 * monospace, regular weight, lower case. The chat window's title bar replaces
 * its header (offline, the heading alone); a component card's own title moves
 * into its bar, and its body is `surface` so the bar reads as one. The loader
 * card of a component still on its way and the answered row are not windows.
 * Everything else — surfaces, corners, shadows, the voice bar, the launcher —
 * is drawn as without it.
 *
 * Pure, as [VoiceBar] is: no view, no context, so every size, colour and word
 * is proven on the JVM; [WindowTitleBar] only draws what [titleBar] says.
 * Lengths are dp, text sizes sp.
 */
internal object WindowChrome {
    const val NONE = "none"
    const val WINDOW = "window"

    /** The title bar on the chat window and on every component card. */
    const val HEIGHT = 32.0
    /** The caption card's: a smaller window. */
    const val CAPTION_HEIGHT = 24.0
    /** Each traffic light, across. */
    const val LIGHT = 10.0
    const val CAPTION_LIGHT = 8.0
    /** Between two lights. */
    const val LIGHT_GAP = 6.0
    /** From the bar's left edge to the first light, and from its right edge to the chat's collapse chevron. */
    const val INSET = 12.0
    /**
     * Left to right. Always these three: they are the window's controls, not
     * the brand's colours, so no design recolours them.
     */
    val LIGHTS = listOf("#FF5F57", "#FEBC2E", "#28C840")
    /** The chat window's `success` dot before its heading, while online. */
    const val ONLINE_DOT = 6.0
    /** Between that dot and the heading. */
    const val ONLINE_GAP = 6.0
    /** The platform's monospace — Typeface.MONOSPACE. */
    const val MONOSPACE = "monospace"
    /** The caption card's title, whatever is being said. */
    const val CAPTION_TITLE = "captions"

    /** The surfaces that open as windows. */
    enum class Surface { CHAT, CAPTION, COMPONENT }

    /** One title bar, exactly as [WindowTitleBar] draws it. Colours are "#RRGGBB". */
    data class TitleBar(
        val height: Double,
        /** Each light, across. */
        val light: Double,
        val lightGap: Double,
        val inset: Double,
        /** The traffic lights, left to right. */
        val lights: List<String>,
        /** `surfaceRaised`. */
        val fill: String,
        /** The bottom outline: `border`, [outlineWidth] (`shape.borderWidth`) wide. */
        val outline: String,
        val outlineWidth: Double,
        /** One line, lower case. Empty: the lights alone. */
        val title: String,
        /** `textMuted`. */
        val titleColor: String,
        /** `font.scale.caption`. */
        val titleSp: Double,
        /** Always [MONOSPACE]. */
        val titleFamily: String,
        /** The `success` colour of the dot before the chat's heading while online; null draws no dot. */
        val onlineDot: String?,
    ) {
        /**
         * How far in from EACH end the title stays: past the lights and one
         * gap more. The same on the right, so the title is centred in the
         * whole bar rather than in what the lights leave — and clear of the
         * chat's collapse chevron there.
         */
        val titleInset: Double get() = inset + lights.size * light + (lights.size - 1) * lightGap + lightGap
    }

    /** Whether these tokens open surfaces as windows. No design at all draws plain surfaces. */
    fun windowed(t: ThemeTokens?): Boolean = t?.chrome == WINDOW

    /**
     * A surface's title bar, or null when the design draws plain surfaces —
     * "none", a word this build does not know, an older server, no design.
     * `title` is the chat's heading or a component's own title; the caption
     * card's is always [CAPTION_TITLE]. `online` puts the `success` dot before
     * the chat's heading; no other surface has one.
     */
    fun titleBar(t: ThemeTokens?, surface: Surface, title: String? = null, online: Boolean = false): TitleBar? {
        if (t == null || t.chrome != WINDOW) return null
        val caption = surface == Surface.CAPTION
        return TitleBar(
            height = if (caption) CAPTION_HEIGHT else HEIGHT,
            light = if (caption) CAPTION_LIGHT else LIGHT,
            lightGap = LIGHT_GAP,
            inset = INSET,
            lights = LIGHTS,
            fill = t.colors.surfaceRaised,
            outline = t.colors.border,
            outlineWidth = t.shape.borderWidth,
            title = title(if (caption) CAPTION_TITLE else title),
            titleColor = t.colors.textMuted,
            titleSp = t.font.scale.caption,
            titleFamily = MONOSPACE,
            onlineDot = if (surface == Surface.CHAT && online) t.colors.success else null,
        )
    }

    /**
     * The title a component card draws in its body: its own — unless the card
     * is a window, whose title has moved into the bar and is not drawn again.
     * The rows inside the card keep their own titles either way.
     */
    fun bodyTitle(bar: TitleBar?, title: String): String = if (bar != null) "" else title

    /**
     * What a component card's body is filled with: `surfaceRaised` on a plain
     * card; under window chrome `surface`, so its `surfaceRaised` title bar
     * reads as a title bar — the white window under a grey bar. The plain
     * colour, as every SDK draws it: glass does not thin it. A host's own
     * surface, where it set one, wins over both.
     */
    fun cardFill(t: ThemeTokens): String = if (windowed(t)) t.colors.surface else t.colors.surfaceRaised

    /**
     * A title as the bar sets it: on one line — every run of spaces and line
     * breaks one space — and in lower case whatever the phone's language
     * (Kotlin's lowercase() is locale-invariant: a Turkish phone still turns
     * "I" into "i").
     */
    fun title(raw: String?): String = (raw ?: "").trim().replace(WHITESPACE, " ").lowercase()

    private val WHITESPACE = Regex("\\s+")
}
