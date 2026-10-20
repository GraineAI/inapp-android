package ai.graine.inapp

import org.json.JSONObject

/*
 * THE CHAT WINDOW, PIECE BY PIECE — contract/DESIGN.md "Chat window v2": the
 * header, the bubbles, the times, the wallpaper, the chips, the cards, the
 * composer, the typing indicator, the density, the welcome, the rating and the
 * call button, each set in the dashboard (a style preset sets them all at
 * once) and delivered resolved in the token set's `chat` group, beside the v1
 * words and colours. This SDK never resolves a style: it reads the groups.
 *
 * A server older than v2 sends none. The chat is then CLASSIC — the chat as it
 * has always looked — derived from the v1 tokens exactly as the contract's
 * table says, with theme.ts resolveChat's own arithmetic, so the values are
 * the very ones a v2 server would have sent for that design. A group present
 * with a field missing or of the wrong type takes that field's classic value;
 * a word this build does not know is drawn as the first one listed for it.
 * Pure, and JVM-tested against every design in design.json (ChatStyleTest).
 *
 * Strings rather than enums, as for the rest of the tokens: the wire's own
 * words, compared against the same words the dashboard and every SDK use.
 */

/** A fill — one colour, or a gradient — and the text that reads on all of it. Colours "#RRGGBB". */
data class ChatStyleFill(
    val color: String,
    /** The gradient's far end; null: one solid colour. */
    val colorTo: String?,
    val text: String,
    /** Quieter words on it: the status line, a time inside a bubble. */
    val textMuted: String,
)

/** The header: its fill, alignment, status line and the context card's place. */
data class ChatStyleHeader(
    /** "surface", "accent", "gradient" (leading → trailing) or "plain" (no fill: the wallpaper runs under it). */
    val style: String,
    /** "start" or "center". */
    val align: String,
    /** "online" (a dot, then the words), "text" (the words alone) or "none". */
    val status: String,
    /** While the agent types, the status line says so, even where it is otherwise off. */
    val typingStatus: Boolean,
    /** Where a pinned card goes: "bar" under the header, "card" atop the thread, or "none". */
    val contextCard: String,
    val fill: ChatStyleFill,
    /** The online dot. */
    val dot: String,
    /** A hairline in `colors.border` under it. */
    val divider: Boolean,
)

/** One side's bubbles. */
data class ChatStyleBubble(
    /** The fill; null: transparent. */
    val color: String?,
    /** A gradient's far end, top → bottom; null: solid. */
    val colorTo: String?,
    val text: String,
    /** A time or a mark inside the bubble. */
    val textMuted: String,
    /** An outline; null: none. */
    val border: String?,
    /** No bubble at all: the words on the wallpaper. */
    val bare: Boolean,
    /** Links in the words (always underlined). */
    val link: String,
)

data class ChatStyleBubbles(
    /** "rounded", "squircle", "tail" or "flat". */
    val shape: String,
    val radius: Double,
    /** The corner nearest the speaker (`rounded`), and a group's inner corners. */
    val tightRadius: Double,
    /** Messages in a row from one side drawn as one group. */
    val grouping: Boolean,
    /** The widest a bubble may be, as a share of the thread's width (0.6–0.92). */
    val maxWidth: Double,
    val shadow: ThemeShadow?,
    val user: ChatStyleBubble,
    val agent: ChatStyleBubble,
)

data class ChatStyleTimestamps(
    /** "never", "group" (the last of each group) or "always". */
    val show: String,
    /** "below" the bubble or "inside" it. */
    val style: String,
    /** A waiting / sent / not-sent mark beside the customer's times. */
    val ticks: Boolean,
)

data class ChatStyleWallpaper(
    /** "solid", "gradient" (top → bottom) or "pattern". */
    val style: String,
    /** The base, or the gradient's top. */
    val color: String,
    /** The gradient's bottom; null unless a gradient. */
    val colorTo: String?,
    /** "none", "dots", "grid" or "diagonal". */
    val pattern: String,
    val patternColor: String,
    /** How strong the gradient or the pattern is, 0–1 (already in the colours). */
    val intensity: Double,
    /** Words straight on the wallpaper: a bare bubble, the welcome's title. */
    val text: String,
    /** Day lines, times under bubbles, the typing words. */
    val textMuted: String,
)

/** The brand's starters and the agent's quick replies: one look. */
data class ChatStyleChips(
    /** "outline", "filled", "soft" or "elevated". */
    val style: String,
    /** "both" (starters docked, replies in the thread), "inline" or "above-composer". */
    val placement: String,
    /** "wrap" (rows that wrap) or "row" (one row that scrolls sideways). */
    val scroll: String,
    val height: Double,
    val radius: Double,
    val fill: String,
    val text: String,
    /** null: no outline. */
    val border: String?,
    val borderWidth: Double,
    val shadow: ThemeShadow?,
)

/** The agent's components, the upcoming-calls card, a context card and the rating card. */
data class ChatStyleCards(
    val radius: Double,
    val shadow: ThemeShadow?,
    /** The design's hard offset instead of a shadow. */
    val hard: Boolean,
    /** An outline in `border`. */
    val border: Boolean,
    /** A component's large image: "auto" (its own height), "square", "landscape" or "wide". */
    val image: String,
    /** A component's main ("filled") button: "filled", "outline", "tonal" or "text". */
    val button: String,
    /** null: no fill. */
    val buttonFill: String?,
    val buttonText: String,
    val buttonBorder: String?,
)

data class ChatStyleComposer(
    /** "bar" (a strip), "pill" (the send button inside) or "floating" (a raised capsule over the thread). */
    val style: String,
    /** A microphone while the field is empty: it calls the agent. */
    val mic: Boolean,
    /** A paperclip, where the app takes attachments. */
    val attach: Boolean,
    /** The send button: "circle", "rounded" or "icon" (no fill). */
    val send: String,
    /** The field. */
    val fill: String,
    val text: String,
    val placeholder: String,
    val border: String,
    /** The strip behind the field; null: none, the thread shows through. */
    val strip: String?,
    val shadow: ThemeShadow?,
    /** The field's height and corners. */
    val height: Double,
    val radius: Double,
    /** null: no fill. */
    val sendFill: String?,
    val sendIcon: String,
    /** The paperclip and the call button. */
    val icon: String,
)

data class ChatStyleTyping(
    /** "loader", "dots", "wave" or "text". */
    val style: String,
    /** "" = "Typing…". */
    val text: String,
    val color: String,
)

data class ChatStyleDensity(
    /** "comfortable", "compact" or "spacious". */
    val name: String,
    /** Between rows. */
    val gap: Double,
    /** Between two messages of one group. */
    val groupGap: Double,
    /** Inside a bubble. */
    val padX: Double,
    val padY: Double,
    /** The thread's side margins. */
    val edge: Double,
)

data class ChatStyleWelcome(
    /** "centered", "card", "hero" or "minimal". */
    val layout: String,
    /** The starters as "chips" or as a "list". */
    val starters: String,
)

data class ChatStyleRating(
    /** Ask, once the agent ends the conversation. */
    val show: Boolean,
    /** "thumbs", "stars" or "emoji". */
    val style: String,
    /** "" = "How was this chat?". */
    val prompt: String,
)

data class ChatStyleEscalation(
    /** A button that calls the agent: "none", "header" or "composer". */
    val call: String,
)

/** Frosted pieces: their colour at [opacity], over the wallpaper blurred by [blur] where the platform can. */
data class ChatStyleGlass(val blur: Double, val opacity: Double)

/**
 * THE CHAT WINDOW v2 — every piece, resolved (contract/DESIGN.md "Chat window
 * v2"). [ChatTokens.style] as the server sent it; `Surfaces.styleOf(tokens)`
 * is what to draw, the classic chat for a server that sends none.
 */
data class ChatStyle(
    /** The style preset it came from — informational: the groups are resolved. */
    val preset: String,
    val header: ChatStyleHeader,
    val bubbles: ChatStyleBubbles,
    val timestamps: ChatStyleTimestamps,
    val wallpaper: ChatStyleWallpaper,
    val chips: ChatStyleChips,
    val cards: ChatStyleCards,
    val composer: ChatStyleComposer,
    val typing: ChatStyleTyping,
    val density: ChatStyleDensity,
    val welcome: ChatStyleWelcome,
    val rating: ChatStyleRating,
    val escalation: ChatStyleEscalation,
    /** null: solid pieces. */
    val glass: ChatStyleGlass?,
)

internal object ChatStyles {
    // The words each piece allows. The first of each is what a word this build does not know is drawn as.
    val HEADER_STYLES = listOf("surface", "accent", "gradient", "plain")
    val HEADER_ALIGNS = listOf("start", "center")
    val STATUS_LINES = listOf("online", "text", "none")
    val CONTEXT_CARDS = listOf("bar", "card", "none")
    val BUBBLE_SHAPES = listOf("rounded", "squircle", "tail", "flat")
    val TIMESTAMP_SHOWS = listOf("never", "group", "always")
    val TIMESTAMP_STYLES = listOf("below", "inside")
    val WALLPAPER_STYLES = listOf("solid", "gradient", "pattern")
    /** A pattern this build does not know is its dots ("none" is a pattern word too, never the fallback). */
    val PATTERNS = listOf("dots", "grid", "diagonal", "none")
    val CHIP_STYLES = listOf("outline", "filled", "soft", "elevated")
    val CHIP_PLACEMENTS = listOf("both", "inline", "above-composer")
    val CHIP_SCROLLS = listOf("wrap", "row")
    val CARD_IMAGES = listOf("auto", "square", "landscape", "wide")
    val CARD_BUTTONS = listOf("filled", "outline", "tonal", "text")
    val COMPOSER_STYLES = listOf("bar", "floating", "pill")
    val SEND_STYLES = listOf("circle", "rounded", "icon")
    val TYPING_STYLES = listOf("loader", "dots", "wave", "text")
    val DENSITIES = listOf("comfortable", "compact", "spacious")
    val WELCOME_LAYOUTS = listOf("centered", "card", "hero", "minimal")
    val STARTER_LOOKS = listOf("chips", "list")
    val RATING_STYLES = listOf("thumbs", "stars", "emoji")
    val CALL_BUTTONS = listOf("none", "header", "composer")

    /** The keys only a v2 server puts in `chat`. Any one of them present: the server sent the groups. */
    val KEYS = listOf("preset", "header", "bubbles", "timestamps", "wallpaper", "chips", "cards", "composer", "typing",
        "density", "welcome", "rating", "escalation", "glass")

    /** Words on the accent (and its shades) read at this, as the v1 accent header and customer bubble always have. */
    const val ON_ACCENT_MIN = 3.0

    // ── classic, from the v1 tokens ─────────────────────────────────────────

    /**
     * The chat as it has always looked, in this design: theme.ts resolveChat
     * with every v2 knob at its classic value, from the tokens a pre-v2 server
     * sends — the v1 chat words and colours, `colors`, the radii, the spacing,
     * the card depth and the `bubbles` word.
     */
    fun classic(t: ThemeTokens): ChatStyle {
        val c = t.colors
        val v1 = Surfaces.chatOf(t)
        val r = t.shape.radius
        val wall = v1.background
        val surfaceHeader = Design.normaliseHex(v1.headerBackground) == Design.normaliseHex(c.surface)
        /** The side as the design's `bubbles` draws it — v1, exactly. */
        fun side(user: Boolean): ChatStyleBubble {
            val color: String?
            val text: String
            var border: String? = null
            var bare = false
            when (t.bubbles) {
                "outline" -> { color = null; text = c.text; border = if (user) c.accent else c.border }
                "minimal" -> if (user) { color = c.accentSoft; text = c.text } else { color = null; text = c.text; bare = true }
                else -> { color = if (user) c.userBubble else c.agentBubble; text = if (user) c.userBubbleText else c.agentBubbleText }
            }
            val under = color ?: wall
            val floor = if (user && color == c.userBubble) ON_ACCENT_MIN else 4.5
            return ChatStyleBubble(color, null, text, quietOn(text, under, null, floor), border, bare, linkOn(text, c.accent, under, null))
        }
        val gap = Js.round(t.spacing.gap)
        return ChatStyle(
            preset = "classic",
            header = ChatStyleHeader(
                style = if (surfaceHeader) "surface" else "accent",
                align = "start", status = "online", typingStatus = false, contextCard = "bar",
                fill = ChatStyleFill(v1.headerBackground, null, v1.headerText, v1.headerSubtext),
                dot = if (surfaceHeader) c.success else v1.headerText,
                divider = true,
            ),
            bubbles = ChatStyleBubbles("rounded", r.lg, minOf(r.sm, r.lg), false, 0.84, null, side(true), side(false)),
            timestamps = ChatStyleTimestamps(if (v1.showTimestamps) "always" else "never", "below", false),
            wallpaper = ChatStyleWallpaper(if (v1.pattern == "dots") "pattern" else "solid", wall, null, v1.pattern, v1.patternColor, 0.5, c.text, c.textMuted),
            chips = ChatStyleChips("outline", "both", "wrap", 34.0, minOf(r.xl, 17.0), wall, ensureContrast(c.text, wall), c.accent,
                maxOf(1.0, t.shape.borderWidth), null),
            cards = ChatStyleCards(r.lg, t.elevation.card, t.elevation.hardOffset != null, true, "auto", "filled", c.accent, c.onAccent, null),
            composer = ChatStyleComposer(
                style = "bar", mic = false, attach = false, send = "circle",
                fill = v1.composerBackground, text = v1.composerText, placeholder = v1.composerPlaceholder, border = v1.composerBorder,
                strip = wall, shadow = null, height = 40.0, radius = r.xl, sendFill = c.accent, sendIcon = c.onAccent,
                icon = ensureOnBoth(c.accent, v1.composerBackground, wall, ON_ACCENT_MIN),
            ),
            typing = ChatStyleTyping("loader", "", c.accent),
            density = ChatStyleDensity("comfortable", gap, maxOf(2.0, Js.round(gap * 0.25)), Js.round(t.spacing.cardPadding),
                Js.round(t.spacing.cardPadding * 0.75), 12.0),
            welcome = ChatStyleWelcome("centered", "chips"),
            rating = ChatStyleRating(false, "thumbs", ""),
            escalation = ChatStyleEscalation("none"),
            glass = null,
        )
    }

    // ── the server's, read over classic ─────────────────────────────────────

    /**
     * `tokens.chat`'s v2 groups over [classic], field by field; null when the
     * server sent none of them (an older one). Never throws.
     */
    fun parse(raw: JSONObject?, classic: ChatStyle): ChatStyle? {
        if (raw == null || KEYS.none { raw.has(it) }) return null
        val k = classic
        val h = group(raw, "header")
        val hf = group(h, "fill")
        val b = group(raw, "bubbles")
        val ts = group(raw, "timestamps")
        val w = group(raw, "wallpaper")
        val ch = group(raw, "chips")
        val cd = group(raw, "cards")
        val cp = group(raw, "composer")
        val ty = group(raw, "typing")
        val dn = group(raw, "density")
        val wl = group(raw, "welcome")
        val rt = group(raw, "rating")
        val es = group(raw, "escalation")
        fun side(o: JSONObject, d: ChatStyleBubble) = ChatStyleBubble(
            color = hexOrNull(o, "color", d.color),
            colorTo = hexOrNull(o, "colorTo", d.colorTo),
            text = hex(o, "text", d.text),
            textMuted = hex(o, "textMuted", d.textMuted),
            border = hexOrNull(o, "border", d.border),
            bare = flag(o, "bare", d.bare),
            link = hex(o, "link", d.link),
        )
        return ChatStyle(
            preset = (raw.opt("preset") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: k.preset,
            header = ChatStyleHeader(
                style = word(h, "style", HEADER_STYLES, k.header.style),
                align = word(h, "align", HEADER_ALIGNS, k.header.align),
                status = word(h, "status", STATUS_LINES, k.header.status),
                typingStatus = flag(h, "typingStatus", k.header.typingStatus),
                contextCard = word(h, "contextCard", CONTEXT_CARDS, k.header.contextCard),
                fill = ChatStyleFill(
                    color = hex(hf, "color", k.header.fill.color),
                    colorTo = hexOrNull(hf, "colorTo", k.header.fill.colorTo),
                    text = hex(hf, "text", k.header.fill.text),
                    textMuted = hex(hf, "textMuted", k.header.fill.textMuted),
                ),
                dot = hex(h, "dot", k.header.dot),
                divider = flag(h, "divider", k.header.divider),
            ),
            bubbles = ChatStyleBubbles(
                shape = word(b, "shape", BUBBLE_SHAPES, k.bubbles.shape),
                radius = num(b, "radius", k.bubbles.radius, 0.0, 999.0),
                tightRadius = num(b, "tightRadius", k.bubbles.tightRadius, 0.0, 999.0),
                grouping = flag(b, "grouping", k.bubbles.grouping),
                maxWidth = num(b, "maxWidth", k.bubbles.maxWidth, 0.6, 0.92),
                shadow = shadow(b, "shadow", k.bubbles.shadow),
                user = side(group(b, "user"), k.bubbles.user),
                agent = side(group(b, "agent"), k.bubbles.agent),
            ),
            timestamps = ChatStyleTimestamps(
                show = word(ts, "show", TIMESTAMP_SHOWS, k.timestamps.show),
                style = word(ts, "style", TIMESTAMP_STYLES, k.timestamps.style),
                ticks = flag(ts, "ticks", k.timestamps.ticks),
            ),
            wallpaper = ChatStyleWallpaper(
                style = word(w, "style", WALLPAPER_STYLES, k.wallpaper.style),
                color = hex(w, "color", k.wallpaper.color),
                colorTo = hexOrNull(w, "colorTo", k.wallpaper.colorTo),
                pattern = word(w, "pattern", PATTERNS, k.wallpaper.pattern),
                patternColor = hex(w, "patternColor", k.wallpaper.patternColor),
                intensity = num(w, "intensity", k.wallpaper.intensity, 0.0, 1.0),
                text = hex(w, "text", k.wallpaper.text),
                textMuted = hex(w, "textMuted", k.wallpaper.textMuted),
            ),
            chips = ChatStyleChips(
                style = word(ch, "style", CHIP_STYLES, k.chips.style),
                placement = word(ch, "placement", CHIP_PLACEMENTS, k.chips.placement),
                scroll = word(ch, "scroll", CHIP_SCROLLS, k.chips.scroll),
                height = num(ch, "height", k.chips.height, 0.0, 200.0),
                radius = num(ch, "radius", k.chips.radius, 0.0, 999.0),
                fill = hex(ch, "fill", k.chips.fill),
                text = hex(ch, "text", k.chips.text),
                border = hexOrNull(ch, "border", k.chips.border),
                borderWidth = num(ch, "borderWidth", k.chips.borderWidth, 0.0, 16.0),
                shadow = shadow(ch, "shadow", k.chips.shadow),
            ),
            cards = ChatStyleCards(
                radius = num(cd, "radius", k.cards.radius, 0.0, 999.0),
                shadow = shadow(cd, "shadow", k.cards.shadow),
                hard = flag(cd, "hard", k.cards.hard),
                border = flag(cd, "border", k.cards.border),
                image = word(cd, "image", CARD_IMAGES, k.cards.image),
                button = word(cd, "button", CARD_BUTTONS, k.cards.button),
                buttonFill = hexOrNull(cd, "buttonFill", k.cards.buttonFill),
                buttonText = hex(cd, "buttonText", k.cards.buttonText),
                buttonBorder = hexOrNull(cd, "buttonBorder", k.cards.buttonBorder),
            ),
            composer = ChatStyleComposer(
                style = word(cp, "style", COMPOSER_STYLES, k.composer.style),
                mic = flag(cp, "mic", k.composer.mic),
                attach = flag(cp, "attach", k.composer.attach),
                send = word(cp, "send", SEND_STYLES, k.composer.send),
                fill = hex(cp, "fill", k.composer.fill),
                text = hex(cp, "text", k.composer.text),
                placeholder = hex(cp, "placeholder", k.composer.placeholder),
                border = hex(cp, "border", k.composer.border),
                strip = hexOrNull(cp, "strip", k.composer.strip),
                shadow = shadow(cp, "shadow", k.composer.shadow),
                height = num(cp, "height", k.composer.height, 0.0, 200.0),
                radius = num(cp, "radius", k.composer.radius, 0.0, 999.0),
                sendFill = hexOrNull(cp, "sendFill", k.composer.sendFill),
                sendIcon = hex(cp, "sendIcon", k.composer.sendIcon),
                icon = hex(cp, "icon", k.composer.icon),
            ),
            typing = ChatStyleTyping(
                style = word(ty, "style", TYPING_STYLES, k.typing.style),
                text = (ty.opt("text") as? String) ?: k.typing.text,
                color = hex(ty, "color", k.typing.color),
            ),
            density = ChatStyleDensity(
                name = word(dn, "name", DENSITIES, k.density.name),
                gap = num(dn, "gap", k.density.gap, 0.0, 200.0),
                groupGap = num(dn, "groupGap", k.density.groupGap, 0.0, 200.0),
                padX = num(dn, "padX", k.density.padX, 0.0, 200.0),
                padY = num(dn, "padY", k.density.padY, 0.0, 200.0),
                edge = num(dn, "edge", k.density.edge, 0.0, 200.0),
            ),
            welcome = ChatStyleWelcome(
                layout = word(wl, "layout", WELCOME_LAYOUTS, k.welcome.layout),
                starters = word(wl, "starters", STARTER_LOOKS, k.welcome.starters),
            ),
            rating = ChatStyleRating(
                show = flag(rt, "show", k.rating.show),
                style = word(rt, "style", RATING_STYLES, k.rating.style),
                prompt = (rt.opt("prompt") as? String) ?: k.rating.prompt,
            ),
            escalation = ChatStyleEscalation(call = word(es, "call", CALL_BUTTONS, k.escalation.call)),
            glass = when (val g = raw.opt("glass")) {
                null -> k.glass
                JSONObject.NULL -> null
                is JSONObject -> ChatStyleGlass(num(g, "blur", k.glass?.blur ?: 20.0, 0.0, 200.0), num(g, "opacity", k.glass?.opacity ?: 0.72, 0.0, 1.0))
                else -> k.glass
            },
        )
    }

    private val EMPTY = JSONObject()

    /** A group, or nothing (absent or not an object: every field classic). */
    private fun group(o: JSONObject, key: String): JSONObject = o.opt(key) as? JSONObject ?: EMPTY

    /** Absent or not a word: classic; a word this build does not know: the first listed. */
    private fun word(o: JSONObject, key: String, allowed: List<String>, classic: String): String {
        val v = o.opt(key) as? String ?: return classic
        return v.trim().lowercase().takeIf { it in allowed } ?: allowed[0]
    }

    private fun hex(o: JSONObject, key: String, classic: String): String = Design.normaliseHex(o.opt(key) as? String) ?: classic

    /** JSON null is "none"; absent or not a colour keeps classic. */
    private fun hexOrNull(o: JSONObject, key: String, classic: String?): String? {
        if (!o.has(key)) return classic
        val v = o.opt(key)
        if (v == JSONObject.NULL) return null
        return Design.normaliseHex(v as? String) ?: classic
    }

    private fun num(o: JSONObject, key: String, classic: Double, min: Double, max: Double): Double =
        Js.finite(o.opt(key))?.coerceIn(min, max) ?: classic

    private fun flag(o: JSONObject, key: String, classic: Boolean): Boolean = o.opt(key) as? Boolean ?: classic

    private val NO_SHADOW = ThemeShadow(0.0, 0.0, 0.0, 0.0, "#000000")

    /** JSON null is no shadow; an object is read over classic's (or nothing); anything else keeps classic. */
    private fun shadow(o: JSONObject, key: String, classic: ThemeShadow?): ThemeShadow? {
        if (!o.has(key)) return classic
        return when (val v = o.opt(key)) {
            JSONObject.NULL -> null
            is JSONObject -> {
                val d = classic ?: NO_SHADOW
                ThemeShadow(
                    y = num(v, "y", d.y, -200.0, 200.0),
                    blur = num(v, "blur", d.blur, 0.0, 400.0),
                    spread = num(v, "spread", d.spread, -200.0, 200.0),
                    opacity = num(v, "opacity", d.opacity, 0.0, 1.0),
                    color = hex(v, "color", d.color),
                )
            }
            else -> classic
        }
    }

    // ── written back, as the session spelled them ───────────────────────────

    private fun nullable(v: String?): Any = v ?: JSONObject.NULL

    private fun fill(f: ChatStyleFill): JSONObject = JSONObject()
        .put("color", f.color).put("colorTo", nullable(f.colorTo)).put("text", f.text).put("textMuted", f.textMuted)

    private fun side(s: ChatStyleBubble): JSONObject = JSONObject()
        .put("color", nullable(s.color)).put("colorTo", nullable(s.colorTo)).put("text", s.text).put("textMuted", s.textMuted)
        .put("border", nullable(s.border)).put("bare", s.bare).put("link", s.link)

    /** The v2 groups into `chat`, as a v2 server writes them. */
    fun json(s: ChatStyle, into: JSONObject): JSONObject = into
        .put("preset", s.preset)
        .put("header", JSONObject()
            .put("style", s.header.style).put("align", s.header.align).put("status", s.header.status)
            .put("typingStatus", s.header.typingStatus).put("contextCard", s.header.contextCard)
            .put("fill", fill(s.header.fill)).put("dot", s.header.dot).put("divider", s.header.divider))
        .put("bubbles", JSONObject()
            .put("shape", s.bubbles.shape).put("radius", Design.n(s.bubbles.radius)).put("tightRadius", Design.n(s.bubbles.tightRadius))
            .put("grouping", s.bubbles.grouping).put("maxWidth", Design.n(s.bubbles.maxWidth))
            .put("shadow", Design.shadowJson(s.bubbles.shadow)).put("user", side(s.bubbles.user)).put("agent", side(s.bubbles.agent)))
        .put("timestamps", JSONObject().put("show", s.timestamps.show).put("style", s.timestamps.style).put("ticks", s.timestamps.ticks))
        .put("wallpaper", JSONObject()
            .put("style", s.wallpaper.style).put("color", s.wallpaper.color).put("colorTo", nullable(s.wallpaper.colorTo))
            .put("pattern", s.wallpaper.pattern).put("patternColor", s.wallpaper.patternColor)
            .put("intensity", Design.n(s.wallpaper.intensity)).put("text", s.wallpaper.text).put("textMuted", s.wallpaper.textMuted))
        .put("chips", JSONObject()
            .put("style", s.chips.style).put("placement", s.chips.placement).put("scroll", s.chips.scroll)
            .put("height", Design.n(s.chips.height)).put("radius", Design.n(s.chips.radius)).put("fill", s.chips.fill)
            .put("text", s.chips.text).put("border", nullable(s.chips.border)).put("borderWidth", Design.n(s.chips.borderWidth))
            .put("shadow", Design.shadowJson(s.chips.shadow)))
        .put("cards", JSONObject()
            .put("radius", Design.n(s.cards.radius)).put("shadow", Design.shadowJson(s.cards.shadow)).put("hard", s.cards.hard)
            .put("border", s.cards.border).put("image", s.cards.image).put("button", s.cards.button)
            .put("buttonFill", nullable(s.cards.buttonFill)).put("buttonText", s.cards.buttonText).put("buttonBorder", nullable(s.cards.buttonBorder)))
        .put("composer", JSONObject()
            .put("style", s.composer.style).put("mic", s.composer.mic).put("attach", s.composer.attach).put("send", s.composer.send)
            .put("fill", s.composer.fill).put("text", s.composer.text).put("placeholder", s.composer.placeholder)
            .put("border", s.composer.border).put("strip", nullable(s.composer.strip)).put("shadow", Design.shadowJson(s.composer.shadow))
            .put("height", Design.n(s.composer.height)).put("radius", Design.n(s.composer.radius))
            .put("sendFill", nullable(s.composer.sendFill)).put("sendIcon", s.composer.sendIcon).put("icon", s.composer.icon))
        .put("typing", JSONObject().put("style", s.typing.style).put("text", s.typing.text).put("color", s.typing.color))
        .put("density", JSONObject()
            .put("name", s.density.name).put("gap", Design.n(s.density.gap)).put("groupGap", Design.n(s.density.groupGap))
            .put("padX", Design.n(s.density.padX)).put("padY", Design.n(s.density.padY)).put("edge", Design.n(s.density.edge)))
        .put("welcome", JSONObject().put("layout", s.welcome.layout).put("starters", s.welcome.starters))
        .put("rating", JSONObject().put("show", s.rating.show).put("style", s.rating.style).put("prompt", s.rating.prompt))
        .put("escalation", JSONObject().put("call", s.escalation.call))
        .put("glass", s.glass?.let { JSONObject().put("blur", Design.n(it.blur)).put("opacity", Design.n(it.opacity)) } ?: JSONObject.NULL)

    // ── colour, as theme.ts reckons it ──────────────────────────────────────
    //
    // The classic bubbles' quiet words and links, the chips' words and the
    // composer's icon are checked against what they sit on, as the server
    // checks them; the same arithmetic gives the same colours. StrictMath for
    // the power: the same fdlibm the server's JavaScript engine uses.

    private fun rgb(hex: String): IntArray {
        val n = (Design.normaliseHex(hex) ?: "#000000").substring(1).toInt(16)
        return intArrayOf((n shr 16) and 255, (n shr 8) and 255, n and 255)
    }

    /** WCAG relative luminance. */
    fun luminance(hex: String): Double {
        fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else StrictMath.pow((s + 0.055) / 1.055, 2.4) }
        val (r, g, b) = rgb(hex).let { Triple(it[0], it[1], it[2]) }
        return 0.2126 * ch(r) + 0.7152 * ch(g) + 0.0722 * ch(b)
    }

    /** WCAG contrast ratio, 1–21. */
    fun contrast(a: String, b: String): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
    }

    /** `a` mixed toward `b` by `t` — theme.ts's mixHex. */
    fun mix(a: String, b: String, t: Double): String = VoiceBar.mixHex(a, b, t)

    /** `fg` if it reaches `min` on `bg`; else stepped toward black or white until it does. */
    fun ensureContrast(fg: String, bg: String, min: Double = 4.5): String {
        if (contrast(fg, bg) >= min) return fg
        val toward = if (luminance(bg) > 0.5) "#000000" else "#FFFFFF"
        // The same steps JavaScript takes, floating-point drift and all.
        var t = 0.1
        while (t <= 1.0001) {
            val c = mix(fg, toward, t)
            if (contrast(c, bg) >= min) return c
            t += 0.1
        }
        return toward
    }

    /** `fg` made to read on both ends of a gradient (or one fill twice). */
    fun ensureOnBoth(fg: String, a: String, b: String, min: Double): String = ensureContrast(ensureContrast(fg, a, min), b, min)

    private fun worstOn(fg: String, a: String, b: String?): Double = if (b != null) Math.min(contrast(fg, a), contrast(fg, b)) else contrast(fg, a)

    /** Words quieter than `text` on a fill, as far toward it as they still read — 4.5:1, or on the accent its own floor. */
    private fun quietOn(text: String, a: String, b: String?, floor: Double): String {
        val min = if (floor >= 4.5) 4.5 else Math.max(ON_ACCENT_MIN, Math.min(4.5, worstOn(text, a, b)))
        for (t in doubleArrayOf(0.3, 0.25, 0.2, 0.15, 0.1, 0.05)) {
            val c = mix(text, a, t)
            if (worstOn(c, a, b) >= min) return c
        }
        return text
    }

    /** Links: the accent where it reads at 4.5:1 on all of the fill, else the words' own colour (links are always underlined). */
    private fun linkOn(text: String, accent: String, a: String, b: String?): String = if (worstOn(accent, a, b) >= 4.5) accent else text
}
