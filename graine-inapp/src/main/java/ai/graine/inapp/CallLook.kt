package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject

/*
 * THE CALL SCREEN'S LOOK (version 2) — contract/DESIGN.md "Call screen" →
 * "The look": the backdrop, the layout, the avatar's size and rings, the
 * buttons' style, shape, size, labels and colours, the agent's voice drawn,
 * the caption's style, the type, and a look per moment. Resolved by the
 * server (theme.ts withCallLook); this reads it, derives it for a server that
 * predates it exactly as the contract says, and holds the arithmetic every
 * renderer shares (callVectors in design.json). Pure, and JVM-tested
 * (CallLookTest).
 */

/** One colour of a call screen's backdrop, at a position 0–1 along it. */
data class CallStop(val color: String, val at: Double)

/** The call screen's fill. */
data class CallBackdrop(
    /** "solid", "linear", "radial", "mesh" or "blurred-avatar". */
    val type: String,
    /** One for solid, 2–5 otherwise, in order of [CallStop.at]. */
    val stops: List<CallStop>,
    /** Degrees as CSS reads them: 180 runs top to bottom. A linear gradient's, and a blurred avatar's scrim's. */
    val angle: Double,
    /** "none", "drift" or "breathe". */
    val motion: String,
    /** 0–1: how far it moves, how far a radial glow reaches, how much of a blurred avatar shows. */
    val intensity: Double,
    /** A blurred avatar's scrim — the stops' opacity over it; 1 for every other type. */
    val scrim: Double,
)

/** One call button's colours. */
data class CallAction(
    val fill: String,
    /** 1: filled. Between 0 and 1: glass — [fill] at this opacity over the backdrop, blurred where the platform can. 0: an outline. */
    val fillAlpha: Double,
    val icon: String,
    /** An outline 1.5 wide in this colour; null: none. */
    val stroke: String?,
    /** Mute and speaker while on. Always opaque. */
    val activeFill: String,
    val activeIcon: String,
)

/** What one moment of the call screen is drawn in: everything that has a colour. */
data class CallLook(
    val backdrop: CallBackdrop,
    /** "light" or "dark": the status bar's icons follow it. */
    val ink: String,
    val text: String,
    val textMuted: String,
    val avatarRingColor: String,
    /** Every button's colours, by [CallLooks.ACTIONS]. */
    val actions: Map<String, CallAction>,
    val visualizerColor: String,
    /** Behind a card, subtitle or bubble caption (and the card layout's card); it only ever adds contrast. */
    val captionFill: String,
    val captionFillAlpha: Double,
) {
    fun action(name: String): CallAction = actions[name] ?: actions.getValue("mute")
}

/** The call screen's look: how it is laid out and drawn, and the look of each moment that has one of its own. */
data class CallScreenTokens(
    /** The preset it was resolved from; "classic" is today's screen. */
    val preset: String,
    /** "classic", "full-bleed", "card", "orb" or "minimal". */
    val layout: String,
    val avatarSize: Double,
    /** The ring each moment wears: "none", "pulse", "ripple" or "glow", by [CallLooks.MOMENTS]. */
    val rings: Map<String, String>,
    /** "solid", "glass" or "outline". */
    val buttonStyle: String,
    /** "circle", "squircle" or "pill". */
    val buttonShape: String,
    val buttonSize: Double,
    /** "below" or "none". */
    val buttonLabels: String,
    /** "none", "bars", "wave" or "orb". */
    val visualizer: String,
    val visualizerSensitivity: Double,
    /** "plain", "card", "subtitle" or "bubble". */
    val captionStyle: String,
    /** "middle" or "bottom". */
    val captionPosition: String,
    val nameSize: Double,
    val nameWeight: Int,
    val statusSize: Double,
    val statusWeight: Int,
    /** The screen's own look. */
    val look: CallLook,
    /** The moments that have a look of their own; the rest draw [look]. */
    val states: Map<String, CallLook>,
) {
    /** The look a moment is drawn in. */
    fun lookFor(moment: String): CallLook = states[moment] ?: look

    /** The ring a moment wears. */
    fun ringFor(moment: String): String = rings[moment] ?: "none"
}

internal object CallLooks {
    val BACKDROP_TYPES = listOf("solid", "linear", "radial", "mesh", "blurred-avatar")
    val MOTIONS = listOf("none", "drift", "breathe")
    val INKS = listOf("light", "dark")
    val RINGS = listOf("none", "pulse", "ripple", "glow")
    val BUTTON_STYLES = listOf("solid", "glass", "outline")
    val BUTTON_SHAPES = listOf("circle", "squircle", "pill")
    val BUTTON_LABELS = listOf("below", "none")
    val VISUALIZERS = listOf("none", "bars", "wave", "orb")
    val CAPTION_STYLES = listOf("plain", "card", "subtitle", "bubble")
    val CAPTION_POSITIONS = listOf("middle", "bottom")
    val LAYOUTS = listOf("classic", "full-bleed", "card", "orb", "minimal")
    val MOMENTS = listOf("incoming", "scheduled", "outgoing", "connecting", "connected", "reconnecting", "ended", "missed")
    val ACTIONS = listOf("answer", "decline", "end", "callback", "mute", "speaker", "chat", "close")
    /** The buttons that are the call's decisions: always filled. */
    val DECISIONS = setOf("answer", "decline", "end", "callback")

    /** The keys only a server that resolves the look sends. */
    private val LOOK_KEYS = listOf(
        "preset", "layout", "backdrop", "ink", "avatarSize", "rings", "avatarRingColor", "buttonStyle", "buttonShape", "buttonSize",
        "buttonLabels", "actions", "visualizer", "visualizerColor", "visualizerSensitivity", "captionStyle", "captionPosition",
        "captionFill", "captionFillAlpha", "nameSize", "nameWeight", "statusSize", "statusWeight", "states",
    )

    // ── the numbers (design.json → callScreen) ─────────────────────────────

    /** How each layout places the screen: CALL_LAYOUTS in theme.ts. */
    data class Layout(
        /** "center" or "start". */
        val align: String,
        val top: Double,
        /** The avatar at this share of avatarSize; 0: none. */
        val avatar: Double,
        val nameGap: Double,
        val nameFirst: Boolean,
        val nameSize: Double,
        val nameLines: Int,
        val card: Boolean,
    )

    val LAYOUT_SPECS: Map<String, Layout> = linkedMapOf(
        "classic" to Layout("center", 72.0, 1.0, 20.0, false, 28.0, 1, false),
        "full-bleed" to Layout("start", 32.0, 0.5, 24.0, false, 40.0, 2, false),
        "card" to Layout("center", 48.0, 0.8, 16.0, false, 24.0, 1, true),
        "orb" to Layout("center", 40.0, 0.75, 0.0, true, 24.0, 1, false),
        "minimal" to Layout("center", 120.0, 0.0, 0.0, false, 34.0, 2, false),
    )

    fun layout(id: String): Layout = LAYOUT_SPECS[id] ?: LAYOUT_SPECS.getValue("classic")

    // CALL_SCREEN
    const val SIDE = 24.0
    const val STATUS_GAP = 6.0
    const val VISUALIZER_GAP = 16.0
    const val VISUALIZER_HEIGHT = 28.0
    const val CAPTION_GAP = 36.0
    const val CAPTION_ABOVE_BUTTONS = 24.0
    const val BUTTONS_BOTTOM = 56.0
    const val LABEL_GAP = 8.0
    const val LABEL_SIZE = 13.0
    const val CARD_PADDING = 24.0
    const val CARD_RADIUS = 28.0
    const val CARD_MIN_ALPHA = 0.16
    const val ORB_SCALE = 2.0
    const val SPEAKING_SCALE = 1.06
    const val ENDED_ALPHA = 0.4
    const val BIG_BUTTON = 9.0 / 8.0
    const val ICON = 30.0 / 64.0

    // CALL_RINGS
    data class Ring(val periodMs: Long, val scale: Double, val alpha: Double, val width: Double, val count: Int)
    val PULSE = Ring(1600, 1.35, 0.6, 2.0, 1)
    val RIPPLE = Ring(2400, 1.6, 0.5, 1.5, 3)
    const val GLOW_PERIOD_MS = 2000L
    const val GLOW_REACH = 1.45
    const val GLOW_ALPHA_LOW = 0.35
    const val GLOW_ALPHA_HIGH = 0.7
    const val GLOW_BREATHE = 1.08

    // CALL_VISUALIZER
    const val BARS = 5
    const val BAR_WIDTH = 4.0
    const val BAR_GAP = 4.0
    const val BAR_REST = 4.0
    const val BAR_MAX = 28.0
    val BAR_WEIGHTS = listOf(0.5, 0.8, 1.0, 0.8, 0.5)
    const val BAR_WOBBLE_MS = 600L
    const val WAVE_WIDTH = 160.0
    const val WAVE_STROKE = 2.0
    const val WAVE_AMPLITUDE = 12.0
    const val WAVE_CYCLES = 1.5
    const val WAVE_PERIOD_MS = 900L
    const val WAVE_REST_ALPHA = 0.4
    const val ORB_REST = 1.6
    const val ORB_GAIN = 0.5
    const val ORB_LAYOUT_REST = 2.0
    const val ORB_LAYOUT_GAIN = 0.4
    const val ORB_INNER = 0.72
    const val ORB_OUTER_ALPHA = 0.35
    const val ORB_INNER_ALPHA = 0.5
    const val ATTACK_MS = 60.0
    const val RELEASE_MS = 240.0

    // CALL_BACKDROP_MOTION
    const val DRIFT_LINEAR_MS = 14000.0
    const val DRIFT_RADIAL_MS = 16000.0
    const val DRIFT_AVATAR_MS = 18000.0
    val DRIFT_MESH_MS = listOf(17000.0, 19000.0, 23000.0, 29000.0, 31000.0)
    const val BREATHE_MS = 7000.0
    const val IDLE_MS = 10000L
    const val SETTLE_MS = 600L
    const val BLUR = 48.0
    const val MESH_COLS = 32
    const val MESH_ROWS = 64

    /** Where a mesh's glows sit, in shares of the screen: TL, TR, BR, BL, and the centre's for a fifth stop. */
    private val MESH_ANCHORS = listOf(0.15 to 0.12, 0.85 to 0.2, 0.8 to 0.85, 0.18 to 0.78, 0.5 to 0.48)

    // ── reading what the server sent ───────────────────────────────────────

    private fun hex(o: JSONObject?, k: String): String? = Design.normaliseHex(o?.opt(k) as? String)
    private fun num(o: JSONObject?, k: String): Double? = Js.finite(o?.opt(k))
    /** A word from its list; absent: null (the caller's own); one this build does not know: the list's first. */
    private fun word(o: JSONObject?, k: String, allowed: List<String>): String? {
        if (o == null || !o.has(k) || o.isNull(k)) return null
        return (o.opt(k) as? String)?.trim()?.lowercase()?.takeIf { it in allowed } ?: allowed[0]
    }

    /** Whether the server sent the look at all (one that predates it sends none of its keys). */
    fun sent(o: JSONObject): Boolean = LOOK_KEYS.any { o.has(it) }

    private fun backdrop(o: JSONObject?, d: CallBackdrop): CallBackdrop {
        if (o == null) return d
        val stops = (o.opt("stops") as? JSONArray)?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val s = a.opt(i) as? JSONObject ?: return@mapNotNull null
                val c = hex(s, "color") ?: return@mapNotNull null
                CallStop(c, (num(s, "at") ?: 0.0).coerceIn(0.0, 1.0))
            }.take(5).sortedBy { it.at }
        }?.takeIf { it.isNotEmpty() } ?: d.stops
        return CallBackdrop(
            type = word(o, "type", BACKDROP_TYPES) ?: d.type,
            stops = stops,
            angle = num(o, "angle") ?: d.angle,
            motion = word(o, "motion", MOTIONS) ?: d.motion,
            intensity = (num(o, "intensity") ?: d.intensity).coerceIn(0.0, 1.0),
            scrim = (num(o, "scrim") ?: d.scrim).coerceIn(0.0, 1.0),
        )
    }

    private fun action(o: JSONObject?, d: CallAction): CallAction {
        if (o == null) return d
        return CallAction(
            fill = hex(o, "fill") ?: d.fill,
            fillAlpha = (num(o, "fillAlpha") ?: d.fillAlpha).coerceIn(0.0, 1.0),
            icon = hex(o, "icon") ?: d.icon,
            // JSON null is "no outline"; absent keeps the default.
            stroke = if (o.has("stroke")) (if (o.isNull("stroke")) null else hex(o, "stroke") ?: d.stroke) else d.stroke,
            activeFill = hex(o, "activeFill") ?: d.activeFill,
            activeIcon = hex(o, "activeIcon") ?: d.activeIcon,
        )
    }

    private fun actions(o: JSONObject?, d: Map<String, CallAction>): Map<String, CallAction> =
        ACTIONS.associateWith { a -> action(o?.opt(a) as? JSONObject, d.getValue(a)) }

    /** A look from its keys on `o`, each over `d`'s. */
    private fun look(o: JSONObject?, d: CallLook): CallLook {
        if (o == null) return d
        return CallLook(
            backdrop = backdrop(o.opt("backdrop") as? JSONObject, d.backdrop),
            ink = word(o, "ink", INKS) ?: d.ink,
            text = hex(o, "text") ?: d.text,
            textMuted = hex(o, "textMuted") ?: d.textMuted,
            avatarRingColor = hex(o, "avatarRingColor") ?: d.avatarRingColor,
            actions = actions(o.opt("actions") as? JSONObject, d.actions),
            visualizerColor = hex(o, "visualizerColor") ?: d.visualizerColor,
            captionFill = hex(o, "captionFill") ?: d.captionFill,
            captionFillAlpha = (num(o, "captionFillAlpha") ?: d.captionFillAlpha).coerceIn(0.0, 1.0),
        )
    }

    /**
     * `tokens.call`'s look, over [derived]'s — the screen as an older server's
     * tokens describe it. Null when the server sent no look key at all.
     */
    fun parse(o: JSONObject, derived: CallScreenTokens): CallScreenTokens? {
        if (!sent(o)) return null
        val d = derived
        val base = look(o, d.look)
        val rings = (o.opt("rings") as? JSONObject).let { r -> MOMENTS.associateWith { m -> word(r, m, RINGS) ?: d.rings.getValue(m) } }
        val states = (o.opt("states") as? JSONObject)?.let { s ->
            MOMENTS.mapNotNull { m -> (s.opt(m) as? JSONObject)?.let { m to look(it, base) } }.toMap()
        } ?: emptyMap()
        return CallScreenTokens(
            preset = (o.opt("preset") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: d.preset,
            layout = word(o, "layout", LAYOUTS) ?: d.layout,
            avatarSize = (num(o, "avatarSize") ?: d.avatarSize).coerceIn(72.0, 160.0),
            rings = rings,
            buttonStyle = word(o, "buttonStyle", BUTTON_STYLES) ?: d.buttonStyle,
            buttonShape = word(o, "buttonShape", BUTTON_SHAPES) ?: d.buttonShape,
            buttonSize = (num(o, "buttonSize") ?: d.buttonSize).coerceIn(52.0, 80.0),
            buttonLabels = word(o, "buttonLabels", BUTTON_LABELS) ?: d.buttonLabels,
            visualizer = word(o, "visualizer", VISUALIZERS) ?: d.visualizer,
            visualizerSensitivity = (num(o, "visualizerSensitivity") ?: d.visualizerSensitivity).coerceIn(0.0, 1.0),
            captionStyle = word(o, "captionStyle", CAPTION_STYLES) ?: d.captionStyle,
            captionPosition = word(o, "captionPosition", CAPTION_POSITIONS) ?: d.captionPosition,
            nameSize = (num(o, "nameSize") ?: d.nameSize).coerceIn(12.0, 64.0),
            nameWeight = num(o, "nameWeight")?.let { Js.round(it).coerceIn(100.0, 900.0).toInt() } ?: d.nameWeight,
            statusSize = (num(o, "statusSize") ?: d.statusSize).coerceIn(10.0, 32.0),
            statusWeight = num(o, "statusWeight")?.let { Js.round(it).coerceIn(100.0, 900.0).toInt() } ?: d.statusWeight,
            look = base,
            states = states,
        )
    }

    /**
     * The look a server that predates it means — contract/DESIGN.md "An older
     * server": today's screen, written out in the look's terms, so the screen
     * draws exactly as it did.
     */
    fun derive(c: CallTokens, weights: FontWeights?): CallScreenTokens {
        val backdrop = CallBackdrop(
            type = if (c.backgroundTo != null) "linear" else "solid",
            stops = if (c.backgroundTo != null) listOf(CallStop(c.background, 0.0), CallStop(c.backgroundTo, 1.0)) else listOf(CallStop(c.background, 0.0)),
            angle = 180.0, motion = "none", intensity = 0.5, scrim = 1.0,
        )
        val ink = if (Appearance.luminance(c.text) > 0.5) "light" else "dark"
        val neutral = CallAction(c.buttonFill, 1.0, c.buttonIcon, null, c.buttonActiveFill, c.buttonActiveIcon)
        fun decision(fill: String, icon: String) = CallAction(fill, 1.0, icon, null, fill, icon)
        val actions = linkedMapOf(
            "answer" to decision(c.acceptFill, c.acceptIcon),
            "decline" to decision(c.declineFill, c.declineIcon),
            "end" to decision(c.endFill, c.endIcon),
            "callback" to decision(c.callbackFill, c.callbackIcon),
            "mute" to neutral, "speaker" to neutral, "chat" to neutral, "close" to neutral,
        )
        val look = CallLook(
            backdrop = backdrop, ink = ink, text = c.text, textMuted = c.textMuted, avatarRingColor = c.textMuted,
            actions = actions, visualizerColor = c.text,
            captionFill = if (ink == "light") "#000000" else "#FFFFFF", captionFillAlpha = 0.0,
        )
        return CallScreenTokens(
            preset = "classic", layout = "classic", avatarSize = 112.0,
            rings = MOMENTS.associateWith { m -> if (m == "incoming" || m == "scheduled") "pulse" else "none" },
            buttonStyle = "solid", buttonShape = "circle", buttonSize = 64.0, buttonLabels = "below",
            visualizer = "none", visualizerSensitivity = 0.5, captionStyle = "plain", captionPosition = "middle",
            nameSize = 28.0, nameWeight = weights?.title ?: 600, statusSize = 16.0, statusWeight = weights?.body ?: 400,
            look = look, states = emptyMap(),
        )
    }

    // ── written back, as the session spelled them ──────────────────────────

    private fun json(b: CallBackdrop): JSONObject = JSONObject()
        .put("type", b.type)
        .put("stops", JSONArray(b.stops.map { JSONObject().put("color", it.color).put("at", Design.n(it.at)) }))
        .put("angle", Design.n(b.angle)).put("motion", b.motion).put("intensity", Design.n(b.intensity)).put("scrim", Design.n(b.scrim))

    private fun json(a: CallAction): JSONObject = JSONObject()
        .put("fill", a.fill).put("fillAlpha", Design.n(a.fillAlpha)).put("icon", a.icon).put("stroke", a.stroke ?: JSONObject.NULL)
        .put("activeFill", a.activeFill).put("activeIcon", a.activeIcon)

    private fun lookJson(l: CallLook, into: JSONObject, withText: Boolean): JSONObject = into
        .put("backdrop", json(l.backdrop)).put("ink", l.ink)
        .apply { if (withText) put("text", l.text).put("textMuted", l.textMuted) }
        .put("avatarRingColor", l.avatarRingColor)
        .put("actions", JSONObject().apply { for (a in ACTIONS) l.actions[a]?.let { put(a, json(it)) } })
        .put("visualizerColor", l.visualizerColor).put("captionFill", l.captionFill).put("captionFillAlpha", Design.n(l.captionFillAlpha))

    /** The look's keys, added to the call screen's JSON (`text` and `textMuted` are the call's own). */
    fun write(s: CallScreenTokens, into: JSONObject) {
        into.put("preset", s.preset).put("layout", s.layout).put("avatarSize", Design.n(s.avatarSize))
            .put("rings", JSONObject().apply { for (m in MOMENTS) put(m, s.ringFor(m)) })
            .put("buttonStyle", s.buttonStyle).put("buttonShape", s.buttonShape).put("buttonSize", Design.n(s.buttonSize))
            .put("buttonLabels", s.buttonLabels).put("visualizer", s.visualizer).put("visualizerSensitivity", Design.n(s.visualizerSensitivity))
            .put("captionStyle", s.captionStyle).put("captionPosition", s.captionPosition)
            .put("nameSize", Design.n(s.nameSize)).put("nameWeight", s.nameWeight)
            .put("statusSize", Design.n(s.statusSize)).put("statusWeight", s.statusWeight)
            .put("states", JSONObject().apply { for ((m, l) in s.states) put(m, lookJson(l, JSONObject(), withText = true)) })
        lookJson(s.look, into, withText = false)
    }

    // ── the arithmetic every renderer shares (theme.ts, design.json → callVectors) ──

    /** Rounded as theme.ts rounds (JavaScript's Math.round, half up), never -0. */
    fun round2(v: Double): Double = Math.floor(v * 100 + 0.5) / 100 + 0.0
    fun round3(v: Double): Double = Math.floor(v * 1000 + 0.5) / 1000 + 0.0

    data class Line(val x0: Double, val y0: Double, val x1: Double, val y1: Double)
    data class Radial(val cx: Double, val cy: Double, val r: Double)
    data class Glow(val u: Double, val v: Double, val color: String)
    data class AvatarDrift(val dx: Double, val dy: Double, val scale: Double)
    data class Frame(val line: Line, val radial: Radial, val mesh: List<Glow>, val avatar: AvatarDrift)

    /** A linear gradient's line across `w` × `h` for an angle read as CSS reads it: through the centre, the end stops reaching the corners. */
    fun gradientLine(angle: Double, w: Double, h: Double): Line {
        val a = angle * Math.PI / 180
        val dx = StrictMath.sin(a)
        val dy = -StrictMath.cos(a)
        val len = Math.abs(w * StrictMath.sin(a)) + Math.abs(h * StrictMath.cos(a))
        return Line(round2(w / 2 - dx * len / 2), round2(h / 2 - dy * len / 2), round2(w / 2 + dx * len / 2), round2(h / 2 + dy * len / 2))
    }

    /** Where a backdrop's moving parts are `t` ms after it began moving — theme.ts callBackdropFrame. At t = 0, or with no motion, at rest. */
    fun frame(b: CallBackdrop, w: Double, h: Double, t: Double): Frame {
        val k = b.intensity.coerceIn(0.0, 1.0)
        val drift = b.motion == "drift"
        val breathe = if (b.motion == "breathe") 1 + (0.05 + 0.12 * k) * StrictMath.sin(2 * Math.PI * t / BREATHE_MS) else 1.0
        val sway = if (drift) (6 + 18 * k) * StrictMath.sin(2 * Math.PI * t / DRIFT_LINEAR_MS) else 0.0
        val base = gradientLine(b.angle + sway, w, h)
        val cx = w / 2
        val cy = h / 2
        val line = Line(
            round2(cx + (base.x0 - cx) * breathe), round2(cy + (base.y0 - cy) * breathe),
            round2(cx + (base.x1 - cx) * breathe), round2(cy + (base.y1 - cy) * breathe),
        )
        fun orbit(period: Double, phase: Double = 0.0) = 2 * Math.PI * t / period + phase
        val ra = if (drift) (0.03 + 0.06 * k) * w else 0.0
        val radial = Radial(
            round2(0.5 * w + ra * StrictMath.cos(orbit(DRIFT_RADIAL_MS))),
            round2(0.28 * h + ra * StrictMath.sin(orbit(DRIFT_RADIAL_MS))),
            round2((0.35 + 0.4 * k) * StrictMath.hypot(w, h) * breathe),
        )
        val colors = b.stops.map { it.color }
        val n = colors.size
        val anchors = if (n >= 5) MESH_ANCHORS else MESH_ANCHORS.take(4)
        val ma = if (drift) 0.05 + 0.1 * k else 0.0
        val mesh = anchors.mapIndexed { i, (u, v) ->
            val a = orbit(DRIFT_MESH_MS[i], i * Math.PI / 2.5)
            Glow(
                round3(0.5 + (u + ma * StrictMath.cos(a) - 0.5) * breathe),
                round3(0.5 + (v + ma * StrictMath.sin(a) - 0.5) * breathe),
                colors.getOrNull(i % maxOf(1, n)) ?: "#000000",
            )
        }
        val aa = if (drift) (0.02 + 0.05 * k) * w else 0.0
        val avatar = AvatarDrift(
            round2(aa * StrictMath.cos(orbit(DRIFT_AVATAR_MS))),
            round2(aa * StrictMath.sin(orbit(DRIFT_AVATAR_MS))),
            round3(if (b.motion == "breathe") 1 + (0.04 + 0.08 * k) * StrictMath.sin(2 * Math.PI * t / BREATHE_MS) else 1.0),
        )
        return Frame(line, radial, mesh, avatar)
    }

    /** "#RRGGBB" → its three channels. */
    fun rgb(hex: String): IntArray {
        val n = (Design.normaliseHex(hex) ?: "#000000").substring(1).toInt(16)
        return intArrayOf((n shr 16) and 255, (n shr 8) and 255, n and 255)
    }

    /** theme.ts toHex: each channel rounded half up, clamped, upper case. */
    fun hex(r: Double, g: Double, b: Double): String {
        fun c(v: Double) = Math.floor(v + 0.5).coerceIn(0.0, 255.0).toInt()
        return String.format(java.util.Locale.US, "#%02X%02X%02X", c(r), c(g), c(b))
    }

    /** The packed colour of [meshColor], for a bitmap. */
    fun meshArgb(glows: List<Glow>, u: Double, v: Double): Int {
        val c = rgb(meshColor(glows, u, v))
        return (0xFF shl 24) or (c[0] shl 16) or (c[1] shl 8) or c[2]
    }

    /** A mesh's colour at (u, v), shares of the screen: every glow weighted by max(0, 0.9 − distance)⁴, averaged. */
    fun meshColor(glows: List<Glow>, u: Double, v: Double): String {
        var r = 0.0; var g = 0.0; var bl = 0.0; var sum = 0.0
        for (glow in glows) {
            val w = StrictMath.pow(maxOf(0.0, 0.9 - StrictMath.hypot(u - glow.u, v - glow.v)), 4.0)
            val c = rgb(glow.color)
            r += c[0] * w; g += c[1] * w; bl += c[2] * w; sum += w
        }
        if (sum < 1e-9) {
            val all = glows.map { rgb(it.color) }
            val n = maxOf(1, all.size).toDouble()
            return hex(all.sumOf { it[0] } / n, all.sumOf { it[1] } / n, all.sumOf { it[2] } / n)
        }
        return hex(r / sum, g / sum, bl / sum)
    }

    /** A stop list's colour at `t` (0–1), as CSS draws a gradient between its stops. */
    fun stopColorAt(stops: List<CallStop>, t: Double): String {
        if (stops.size == 1 || t <= stops[0].at) return stops[0].color
        for (i in 1 until stops.size) {
            val a = stops[i - 1]; val b = stops[i]
            if (t <= b.at) {
                if (b.at == a.at) return b.color
                val f = (t - a.at) / (b.at - a.at)
                val ca = rgb(a.color); val cb = rgb(b.color)
                return hex(ca[0] + (cb[0] - ca[0]) * f, ca[1] + (cb[1] - ca[1]) * f, ca[2] + (cb[2] - ca[2]) * f)
            }
        }
        return stops.last().color
    }

    data class Sizes(val button: Double, val big: Double, val icon: Double)

    /** The call's buttons, the ring's and the missed call's (9/8 of it), and the icon in them. */
    fun buttonSizes(buttonSize: Double): Sizes =
        Sizes(buttonSize, Math.floor(buttonSize * BIG_BUTTON + 0.5), Math.floor(buttonSize * ICON + 0.5))

    data class ButtonFrame(val width: Double, val height: Double, val radius: Double, val continuous: Boolean)

    /**
     * A button's box `size` high in a share `share` wide: a circle round; a
     * squircle with continuous corners at 30%; a pill up to 1.5 × its height
     * wide, its corners at 30% while a toggle is on; End with endShape "pill"
     * its share less 8 wide in every shape — theme.ts callButtonFrame.
     */
    fun buttonFrame(shape: String, size: Double, share: Double, end: Boolean = false, endShape: String? = null, on: Boolean = false): ButtonFrame {
        if (end && endShape == "pill") return ButtonFrame(maxOf(size, Math.floor(share - 8 + 0.5)), size, size / 2, false)
        if (shape == "squircle") return ButtonFrame(size, size, round2(size * 0.3), true)
        if (shape == "pill") {
            val width = maxOf(size, minOf(Math.floor(share - 8 + 0.5), Math.floor(size * 1.5 + 0.5)))
            return ButtonFrame(width, size, if (on) round2(size * 0.3) else size / 2, on)
        }
        return ButtonFrame(size, size, size / 2, false)
    }

    /** The agent's voice, 0–1, from the call's inbound level (0–1): its loudness in dB between a floor (−30 dB at sensitivity 0, −60 at 1) and −6 dB. */
    fun voiceLevel(raw: Double, sensitivity: Double = 0.5): Double {
        if (!(raw > 0)) return 0.0
        val db = 20 * StrictMath.log10(minOf(1.0, raw))
        val floor = -(30 + 30 * sensitivity.coerceIn(0.0, 1.0))
        return round3(((db - floor) / (-6 - floor)).coerceIn(0.0, 1.0))
    }

    /** The voice where the call says only that the agent is speaking: a speech-like movement `t` ms in, between 0.15 and 0.95. */
    fun speakingLevel(t: Double): Double {
        val s = t / 1000
        return round3(maxOf(0.15, minOf(0.95, 0.55 + 0.3 * StrictMath.sin(2 * Math.PI * s / 0.53) * StrictMath.sin(2 * Math.PI * s / 1.7))))
    }

    /** The drawn level after `dtMs`, following `target` up over [ATTACK_MS] and down over [RELEASE_MS]. */
    fun follow(shown: Double, target: Double, dtMs: Double): Double {
        val tau = if (target > shown) ATTACK_MS else RELEASE_MS
        return shown + (target - shown) * (1 - Math.exp(-maxOf(0.0, dtMs) / tau))
    }
}

/**
 * The agent's voice level between two readings of the call's inbound audio:
 * the RMS of the energy over the time between them when both are known (as
 * RtcRules' SpeakingDetector measures it), else the level the reading gives.
 */
internal class AgentLevelMeter {
    private var lastEnergy: Double? = null
    private var lastSeconds: Double? = null

    /** The level, 0–1, or null when the reading says nothing. */
    fun read(level: Double?, energy: Double?, seconds: Double?): Float? {
        val pe = lastEnergy
        val ps = lastSeconds
        lastEnergy = energy
        lastSeconds = seconds
        val v = if (energy != null && seconds != null && pe != null && ps != null && seconds > ps && energy >= pe) Math.sqrt((energy - pe) / (seconds - ps)) else level
        return v?.takeIf { !it.isNaN() }?.coerceIn(0.0, 1.0)?.toFloat()
    }
}
