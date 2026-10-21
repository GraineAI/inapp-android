package ai.graine.inapp

import org.json.JSONObject

/**
 * What the call is doing, in one word — the React Native launcher's `phase`.
 *
 *   IDLE          no call; ready to start
 *   CONNECTING    the page loading, or a call being placed
 *   RECONNECTING  the page failed and is being reloaded, or the network dropped the call
 *   THINKING      the customer finished a sentence; the agent has not answered yet
 *   SPEAKING      the agent is talking
 *   LISTENING     the agent is waiting for the customer
 */
enum class CallPhase {
    IDLE, CONNECTING, RECONNECTING, THINKING, SPEAKING, LISTENING;

    companion object {
        /**
         * Thinking is the gap between the customer's final line and the agent's
         * first: kept as a flag rather than a timer, because the transcript is
         * the truth about who last spoke.
         */
        @JvmStatic
        fun of(
            reconnecting: Boolean, connectivity: Connectivity, connecting: Boolean, ready: Boolean,
            connected: Boolean, agentSpeaking: Boolean, awaitingAgent: Boolean,
        ): CallPhase = when {
            reconnecting || connectivity == Connectivity.FAILED || connectivity == Connectivity.RETRYING -> RECONNECTING
            connecting || !ready -> CONNECTING
            !connected -> IDLE
            agentSpeaking -> SPEAKING
            awaitingAgent -> THINKING
            else -> LISTENING
        }
    }
}

/**
 * Where the voice bar's call is, in one word — what the SDK's bar says, first
 * match wins: FAILED (a setting someone must fix, [GraineVoice.error], or a
 * start nothing could carry, [GraineVoice.callFailed]), RECONNECTING,
 * CONNECTING, MUTED, THINKING, SPEAKING, LISTENING; with no call, ENDED once
 * a call has ended (until the next starts), else IDLE. [wire] is the word
 * every Graine SDK uses.
 */
enum class GraineVoiceStatus(val wire: String) {
    IDLE("idle"), CONNECTING("connecting"), LISTENING("listening"), THINKING("thinking"), SPEAKING("speaking"),
    RECONNECTING("reconnecting"), MUTED("muted"), FAILED("failed"), ENDED("ended");

    companion object {
        /** The rule, from the call's [phase] and flags. An engine reloading with no call is no call recovering. */
        internal fun of(phase: CallPhase, connected: Boolean, connecting: Boolean, muted: Boolean, failed: Boolean, ended: Boolean): GraineVoiceStatus = when {
            failed -> FAILED
            phase == CallPhase.RECONNECTING && (connected || connecting) -> RECONNECTING
            connecting -> CONNECTING
            !connected -> if (ended) ENDED else IDLE
            muted -> MUTED
            phase == CallPhase.THINKING -> THINKING
            phase == CallPhase.SPEAKING -> SPEAKING
            else -> LISTENING
        }
    }
}

/**
 * THE VOICE BAR'S RULES, AS NUMBERS — contract/DESIGN.md → Voice bar and Chat
 * window, the Garage's phone preview. Pure: no view, no context, so every size,
 * word and timing the bar draws is proven on the JVM; GraineAgentBarView only
 * draws what these say. Lengths are dp, text sizes sp.
 */
internal object VoiceBar {
    // ── the frame ──────────────────────────────────────────────────────────
    const val HEIGHT = 60.0
    const val PADDING = 9.0
    const val GAP = 10.0
    /** The avatar and every round button. */
    const val BUTTON = 38.0
    /** Every SDK icon on the bar and in the chat window. */
    const val ICON = 18.0
    /** The launcher glyph inside the avatar. */
    const val AVATAR_GLYPH = 18.0
    /** The heading's first letter, when there is neither glyph nor logo. */
    const val INITIAL_SP = 15.0
    const val INITIAL_WEIGHT = 700
    const val NAME_SP = 14.0
    const val NAME_LINE_SP = 17.5
    /** Between the meter and the status words. */
    const val STATUS_GAP = 6.0
    const val STATUS_LINE = 1.3
    /** The loader in the status line. */
    const val LOADER = 14
    /** A button that cannot act. */
    const val DISABLED_ALPHA = 0.4f
    /** After a hang-up, how long before [GraineAgentBarView.onClose]: the goodbye leaves the speaker first. */
    const val CLOSE_AFTER_END_MS = 400L

    // ── the caption, and anything else above the bar ───────────────────────
    /** Between the bar and what sits above it. */
    const val STACK_GAP = 8.0
    const val CAPTION_PAD_H = 12.0
    const val CAPTION_PAD_V = 8.0
    const val CAPTION_SP = 13.0
    const val CAPTION_LINE_SP = 17.5
    const val CAPTION_LINES = 2
    /** A line still arriving. */
    const val LIVE_ALPHA = 0.75f
    const val YOU_SP = 10.5
    /** "YOU" is spaced 0.6 — as em, what a TextView takes. */
    const val YOU_TRACKING_EM = 0.6 / 10.5
    /** Arrivals rise this far while they fade in. */
    const val RISE = 8.0

    // ── the meter ──────────────────────────────────────────────────────────
    /** Each bar's height as a share of [METER_HEIGHT], left to right. */
    val METER_BARS = listOf(0.55, 1.0, 0.7, 0.4)
    const val METER_HEIGHT = 12.0
    const val METER_BAR_WIDTH = 2.5
    const val METER_BAR_GAP = 2.0
    const val METER_LOW = 0.35
    const val METER_STAGGER_MS = 90.0

    // ── the chat window ────────────────────────────────────────────────────
    const val CHAT_HEADER = 50.0
    const val CHAT_HEADER_PAD = 12.0
    const val CHAT_MARK = 30.0
    const val CHAT_MARK_GLYPH = 14.0
    const val CHAT_TITLE_MAX_SP = 16.0
    const val ONLINE_DOT = 6.0
    const val BUBBLE_MAX_SHARE = 0.84
    const val CHAT_LIST_PAD = 12.0
    const val CHAT_LIST_MAX = 260
    /** The list while the keyboard is up. */
    const val CHAT_LIST_MAX_TYPING = 150
    const val COMPOSER = 58.0
    const val COMPOSER_PAD = 10.0
    const val COMPOSER_GAP = 8.0
    const val FIELD = 40.0
    const val FIELD_PAD = 14.0
    const val SEND = 40.0

    // ── what the status line says ──────────────────────────────────────────

    /** Everything the status words depend on. */
    data class StatusInput(
        /** A misconfiguration the call cannot fix by itself ([GraineVoice.error]). */
        val error: Boolean = false,
        /** The customer is in the chat, not on the call. */
        val chat: Boolean = false,
        val chatOnline: Boolean = false,
        /** Nothing could connect the chat, after every retry ([ChatSource.failed]). */
        val chatFailed: Boolean = false,
        /** Graine ended the chat's conversation itself — idle, or its time limit — and nothing reconnects until the customer opens it or writes ([GraineChat.disconnected]). */
        val chatDisconnected: Boolean = false,
        /** The engine page's view of the network; null until it says. */
        val online: Boolean? = null,
        val reconnecting: Boolean = false,
        val connectivity: Connectivity = Connectivity.READY,
        val micDenied: Boolean = false,
        val ready: Boolean = true,
        val connecting: Boolean = false,
        val muted: Boolean = false,
        val connected: Boolean = false,
        /** The call's phase is THINKING. */
        val thinking: Boolean = false,
        /** The agent's line is still arriving. */
        val speaking: Boolean = false,
        val quality: ConnectionQuality = ConnectionQuality.UNKNOWN,
        /** Neither transport could carry the last start ([GraineVoice.callFailed]). */
        val callFailed: Boolean = false,
        /** Where the connect is ([GraineVoice.connectStage]). */
        val connectStage: ConnectStage? = null,
        /** The customer has waited on it long enough to say so ([GraineVoice.slowConnect]). */
        val slowConnect: Boolean = false,
        /** The brand's words for the connecting moment (Appearance → Loading): "Connecting…" unless it chose its own. */
        val connectingText: String = DEFAULT_CONNECTING,
    )

    /**
     * The words, and whether they are drawn as the loader (with that moment's
     * dashboard words — or [label], when the bar has words of its own for it)
     * or after the meter instead of standing alone.
     */
    data class Status(val text: String, val loader: String? = null, val meter: Boolean = false, val label: String? = null)

    /** The loader's own words for connecting, which a slow connect may replace — and no others. */
    val DEFAULT_CONNECTING: String = Loaders.DEFAULT_TEXT.connecting ?: "Connecting…"

    /** What a connect the customer has waited on says, while the brand's words are the default. */
    const val STILL_CONNECTING = "Still connecting…"

    /** First match wins, in the contract's order. */
    fun status(s: StatusInput): Status = when {
        s.error -> Status("Unavailable")
        // The chat has no microphone and no call to place: none of the words below are true there.
        s.chat -> when {
            s.chatOnline -> Status("Online")
            // Said, not spun: a loader over a chat that has stopped trying is the lie the call's words avoid too.
            s.chatFailed -> Status(ChatWindowView.FAILED_TEXT)
            // Graine ended it and nothing is trying: as the chat window's header says it, until the customer opens it or writes.
            s.chatDisconnected -> Status(ChatLook.DISCONNECTED)
            else -> Status("Connecting…", "connecting")
        }
        // The network gets named, or the agent takes the blame for it.
        s.online == false -> Status("No connection")
        s.reconnecting || s.connectivity == Connectivity.RETRYING -> Status("Reconnecting…", "reconnecting")
        s.micDenied -> Status("Needs microphone")
        s.connectivity == Connectivity.FAILED -> Status("Couldn’t load · tap to retry")
        // Neither transport could carry the call. The microphone starts a new one.
        s.callFailed -> Status("Couldn’t connect · tap to retry")
        !s.ready || s.connecting -> connecting(s)
        s.muted -> Status("Muted")
        // The gap between the customer finishing and the agent answering: the loader, not a meter over silence.
        s.connected && s.thinking -> Status("Thinking…", "thinking")
        s.connected && s.speaking -> Status("Speaking", meter = true)
        s.connected && s.quality == ConnectionQuality.POOR -> Status("Weak connection")
        s.connected -> Status("Listening", meter = true)
        else -> Status("Tap to talk")
    }

    /**
     * WHAT A CONNECT SAYS WHILE IT RUNS — the React Native bar's connectingLook.
     *
     * The words stay the BRAND's. The loader draws each moment in the wording
     * set under Appearance → Loading — often not English — so a retry, or a
     * switch to the other transport, is the "reconnecting" moment in their
     * words; and a connect the customer has waited on says "Still connecting…"
     * only while the brand left "Connecting…" as it was. A wording someone
     * chose is never overwritten.
     */
    fun connecting(s: StatusInput): Status = when {
        s.connectStage == ConnectStage.RETRYING || s.connectStage == ConnectStage.SWITCHING -> Status("Reconnecting…", "reconnecting")
        s.slowConnect && s.connectingText == DEFAULT_CONNECTING -> Status(STILL_CONNECTING, "connecting", label = STILL_CONNECTING)
        else -> Status(s.connectingText, "connecting")
    }

    /**
     * The words the loader draws for a status that is the loader: the bar's
     * own ([Status.label], "Still connecting…") when it has some, else the
     * brand's for that moment (Appearance → Loading). Null for words alone.
     */
    fun loaderWords(st: Status, text: LoaderText): String? = st.loader?.let { st.label ?: text.forMoment(it) }

    /**
     * The microphone cannot act while connecting, before the page is ready,
     * after a refusal, or with a misconfiguration — drawn at 40%. After a
     * failed load it CAN act: it retries.
     */
    fun micBlocked(error: Boolean, micDenied: Boolean, connecting: Boolean, ready: Boolean, connectivity: Connectivity): Boolean =
        error || micDenied || connecting || (!ready && connectivity != Connectivity.FAILED)

    // ── the meter's motion ─────────────────────────────────────────────────

    /** One leg, low to high or back, ms: `max(420, motion.durationMs × 3.2)`. */
    fun meterLegMs(durationMs: Int): Double = maxOf(420.0, durationMs * 3.2)

    /**
     * Whether the bars move at all: not when the design has no motion (a
     * duration of 0, or "none"), and not when the phone asked for none (an
     * animator scale of 0 is Android's "Remove animations").
     */
    fun meterMoves(motion: ThemeMotion, animatorScale: Float): Boolean =
        motion.durationMs > 0 && motion.easing != "none" && animatorScale > 0f

    /**
     * Bar `bar`'s height as a share of its own, `elapsedMs` after the meter
     * appeared: from the bottom, 35% ↔ 100% with CSS ease-in-out, each leg
     * [meterLegMs], bar i starting i × 90 ms late (low until then), forever.
     */
    fun meterScale(bar: Int, elapsedMs: Double, legMs: Double): Double {
        val t = elapsedMs - bar * METER_STAGGER_MS
        if (t <= 0 || legMs <= 0) return METER_LOW
        val legs = t / legMs
        val leg = Math.floor(legs).toLong()
        val eased = easeInOut(legs - leg)
        return if (leg % 2 == 0L) METER_LOW + (1 - METER_LOW) * eased else 1 - (1 - METER_LOW) * eased
    }

    /** CSS `ease-in-out`, cubic-bezier(0.42, 0, 0.58, 1): the Garage's meter curve. */
    fun easeInOut(x: Double): Double = cubicBezier(0.42, 0.0, 0.58, 1.0, x)

    /** A CSS cubic-bezier timing function at `x` (0–1): solve x(t) = x, return y(t). */
    fun cubicBezier(x1: Double, y1: Double, x2: Double, y2: Double, x: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        fun bx(t: Double) = ((1 - 3 * x2 + 3 * x1) * t + (3 * x2 - 6 * x1)) * t * t + 3 * x1 * t
        fun by(t: Double) = ((1 - 3 * y2 + 3 * y1) * t + (3 * y2 - 6 * y1)) * t * t + 3 * y1 * t
        fun dx(t: Double) = 3 * (1 - 3 * x2 + 3 * x1) * t * t + 2 * (3 * x2 - 6 * x1) * t + 3 * x1
        var t = x
        repeat(12) {
            val err = bx(t) - x
            if (Math.abs(err) < 1e-12) return by(t)
            val d = dx(t)
            if (Math.abs(d) < 1e-6) return@repeat
            t -= err / d
        }
        // Newton could not settle: bisect, which always does.
        var lo = 0.0; var hi = 1.0; t = x
        repeat(60) {
            val v = bx(t)
            if (Math.abs(v - x) < 1e-12) return by(t)
            if (v < x) lo = t else hi = t
            t = (lo + hi) / 2
        }
        return by(t)
    }

    // ── the chat window's bubbles ──────────────────────────────────────────

    /**
     * One message's colours, per bubble style. A null fill is no fill; a null
     * outline no outline; `plain` is no bubble at all — the agent's words set
     * straight on the page (minimal).
     */
    data class Bubble(val fill: String?, val text: String, val outline: String?, val plain: Boolean = false)

    fun bubble(t: ThemeTokens, customer: Boolean): Bubble {
        val c = t.colors
        return when (t.bubbles) {
            "outline" -> Bubble(null, c.text, if (customer) c.accent else c.border)
            "minimal" -> if (customer) Bubble(c.accentSoft, c.text, null) else Bubble(null, c.text, null, plain = true)
            else -> if (customer) Bubble(c.userBubble, c.userBubbleText, null) else Bubble(c.agentBubble, c.agentBubbleText, null)
        }
    }

    // ── the call or the chat ───────────────────────────────────────────────

    /** Which surfaces the dashboard allows, which one opens, and whether the switch is offered. */
    data class Surfaces(val allowed: List<String>, val start: String, val canSwitch: Boolean)

    /** The React Native SDK's resolveSurface: `appearance.mode` allows, `appearance.start` (or a preference) opens. */
    fun surfaces(appearance: JSONObject?, prefer: String? = null): Surfaces {
        val modeRaw = appearance?.opt("mode")
        val mode = (if (Js.nullish(modeRaw)) "both" else Js.str(modeRaw)).lowercase()
        val allowed = when (mode) { "voice" -> listOf("voice"); "chat" -> listOf("chat"); else -> listOf("voice", "chat") }
        val configured = Js.strOrEmpty(appearance?.opt("start")).lowercase()
        val start = when {
            prefer != null && prefer in allowed -> prefer
            configured == "chat" || configured == "voice" -> if (configured in allowed) configured else allowed[0]
            else -> allowed[0]
        }
        return Surfaces(allowed, start, allowed.size > 1)
    }

    // ── colour ─────────────────────────────────────────────────────────────

    /** `a` mixed toward `b` by `t` (0 = a, 1 = b) — theme.ts's mixHex. */
    fun mixHex(a: String, b: String, t: Double): String {
        fun rgb(hex: String): IntArray {
            val n = (Design.normaliseHex(hex) ?: "#000000").substring(1).toInt(16)
            return intArrayOf((n shr 16) and 255, (n shr 8) and 255, n and 255)
        }
        val x = rgb(a); val y = rgb(b)
        return "#" + (0..2).joinToString("") { i ->
            Js.round(x[i] + (y[i] - x[i]) * t).coerceIn(0.0, 255.0).toInt().toString(16).padStart(2, '0')
        }.uppercase()
    }

    /**
     * The accent at chip strength: the design's accentSoft when the accent is
     * the design's own; for an accent chosen elsewhere (a host's), the same
     * mix the React Native SDK uses — so the switch and close buttons tint
     * with the accent they sit beside.
     */
    fun softFor(accentHex: String, t: ThemeTokens): String {
        val a = Design.normaliseHex(accentHex)
        if (a == null || a == Design.normaliseHex(t.colors.accent)) return t.colors.accentSoft
        return mixHex(t.colors.surface, a, if (t.scheme == "light") 0.12 else 0.22)
    }
}
