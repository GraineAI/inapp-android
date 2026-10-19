package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject

/*
 * The parts of the SDK that are pure logic — no Android, no threads, no I/O —
 * so every rule here is proven by a plain JVM test, not by hoping a device
 * behaves. The Android half (GraineInApp, GraineVoice) is a thin shell around
 * these.
 */

/**
 * WHAT NEVER LEAVES THE DEVICE UNMASKED.
 *
 * The same five rules, in the same order, as the React Native SDK. Order is
 * load-bearing: a card number must be caught as a card before a twelve-digit
 * run inside it can be caught as an Aadhaar. A native SDK that leaked what the
 * React Native one redacts would fail the security review this product is
 * sold through, so the two are pinned to the same test cases.
 */
object Masking {
    /*
     * The React Native SDK's rules, with every class spelled out as JavaScript
     * defines it. On a phone, java.util.regex is ICU, where `\\d` also matches
     * Devanagari digits, `\\w` every letter in every script, and `\\b` follows
     * them — copied verbatim, the rules pass every JVM test and still leak on
     * the device: "ABCDE1234Fहै" keeps its PAN (ह is a word character to ICU,
     * so there is no boundary), "०9876543210" keeps its phone number. Written
     * out, they mean the same thing on the JVM, on ICU and in JavaScript.
     */
    private const val JS_WORD = "A-Za-z0-9_"
    private const val JS_SPACE = "\\t\\n\\u000B\\f\\r\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

    private val rules: List<Pair<Regex, String>> = listOf(
        // /\b[A-Z]{5}[0-9]{4}[A-Z]\b/g
        Regex("(?<![$JS_WORD])[A-Z]{5}[0-9]{4}[A-Z](?![$JS_WORD])") to "[PAN]",
        // /[\w.+-]+@[\w-]+\.[\w.]{2,}/g
        Regex("[$JS_WORD.+\\-]+@[$JS_WORD\\-]+\\.[$JS_WORD.]{2,}") to "[EMAIL]",
        // /(^|[^\d-])((?:\d[ -]?){13,19})(?![\d-])/g
        Regex("(^|[^0-9\\-])((?:[0-9][\\u0020\\-]?){13,19})(?![0-9\\-])") to "$1[CARD]",
        // /(^|[^\d+])((?:\+91[-\s]?)?[6-9]\d{9})(?!\d)/g
        Regex("(^|[^0-9+])((?:\\+91[\\-$JS_SPACE]?)?[6-9][0-9]{9})(?![0-9])") to "$1[PHONE]",
        // /(^|[^\d+])(\d{4}\s?\d{4}\s?\d{4})(?!\d)/g
        Regex("(^|[^0-9+])([0-9]{4}[$JS_SPACE]?[0-9]{4}[$JS_SPACE]?[0-9]{4})(?![0-9])") to "$1[AADHAAR]",
    )
    private val custom = mutableListOf<Pair<Regex, String>>()

    fun mask(input: String): String {
        var out = input
        for ((re, token) in rules) out = re.replace(out, token)
        synchronized(custom) { for ((re, token) in custom) out = re.replace(out, token) }
        return out
    }

    /** Add a rule for a field shape only your app knows — an account number, a policy id. */
    fun addRule(pattern: Regex, token: String = "[REDACTED]") {
        synchronized(custom) { custom.add(pattern to token) }
    }

    fun clearCustomRules() = synchronized(custom) { custom.clear() }

    /** Masks every string anywhere inside a JSON value. Keys are not masked; values are. */
    fun deep(value: Any?): Any? = when (value) {
        is String -> mask(value)
        is JSONObject -> JSONObject().also { o -> value.keys().forEach { k -> o.put(k, deep(value.opt(k))) } }
        is JSONArray -> JSONArray().also { a -> for (i in 0 until value.length()) a.put(deep(value.opt(i))) }
        else -> value
    }
}

/**
 * THE PROMPT'S {variables}, from every place they can come from, in one view.
 *
 *   1. the agent's defaults from the dashboard (Embed & Widgets → Variables)
 *   2. what the app knows at build time (GraineConfig.variables)
 *   3. what the app learns while it runs (setVariables)
 *   4. who is signed in (identify) — last, so a person beats a default
 *
 * Every value is a string, because it is substituted into a sentence: numbers
 * and booleans are flattened, nested objects dropped (they would render as a
 * repr inside the agent's first line). Runtime values and identity are masked
 * on the way in, like everything else the app hands over.
 */
internal class VariableStore(buildTime: Map<String, Any?> = emptyMap()) {
    private val buildTime: Map<String, String> = flatten(buildTime)
    @Volatile private var dashboard: Map<String, String> = emptyMap()
    @Volatile private var runtime: Map<String, String> = emptyMap()
    @Volatile private var identity: Map<String, String> = emptyMap()
    /**
     * The trait that names the user (the session's `identity.field`): it goes,
     * raw, only to Graine's own requests, so it is left out of the variables
     * and the context's `user` — the identifier never reaches a prompt.
     * `user_id` until the session says otherwise.
     */
    @Volatile private var identityField: String = IdentityRules.DEFAULT_FIELD

    fun setDashboardDefaults(values: Map<String, Any?>) { dashboard = flatten(values) }

    fun setIdentityField(field: String) { identityField = field }

    /** Merged over what is there. `null` under a key removes it; `null` for the whole map clears. */
    @Synchronized
    fun setRuntime(values: Map<String, Any?>?) {
        if (values == null) { runtime = emptyMap(); return }
        val next = runtime.toMutableMap()
        for ((k, v) in values) {
            if (k.isEmpty()) continue
            if (v == null) { next.remove(k); continue }
            flattenOne(v)?.let { next[k] = Masking.mask(it) }
        }
        runtime = next
    }

    /** Who is signed in. `null` on sign-out clears it. */
    fun setIdentity(traits: Map<String, Any?>?) {
        identity = if (traits == null) emptyMap() else flatten(traits).mapValues { Masking.mask(it.value) }
    }

    /** Who is signed in, less the identifier itself. */
    fun identity(): Map<String, String> = identity.filterKeys { it.trim().lowercase() != identityField }

    fun merged(): Map<String, String> = LinkedHashMap<String, String>().apply {
        putAll(dashboard); putAll(buildTime); putAll(runtime); putAll(identity())
    }

    companion object {
        fun flatten(values: Map<String, Any?>): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in values) {
                if (k.isEmpty()) continue
                flattenOne(v)?.let { out[k] = it }
            }
            return out
        }

        private fun flattenOne(v: Any?): String? = when (v) {
            null -> null
            is String -> v
            is Boolean -> v.toString()
            is Int, is Long -> v.toString()
            is Number -> {
                val d = v.toDouble()
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()
            }
            is Map<*, *>, is Iterable<*>, is Array<*>, is JSONObject, is JSONArray -> null
            else -> v.toString()
        }
    }
}

/**
 * WAITING IS NOT THE SAME AS BEING STUCK.
 *
 * The engine page is downloaded over the customer's worst network. One flat
 * timer — "ready in 15 seconds or reload" — cancelled honest slow downloads,
 * threw away everything that had arrived, and started again, six times; on a
 * slow enough link it could never finish. So the question is "is it still
 * moving?", with three limits for three different failures:
 *
 *   stall   nothing has arrived for a while — a dead socket, a captive portal
 *   boot    everything arrived and the page still has not said ready — broken
 *   cap     an overall ceiling, so a link that dribbles forever still ends
 */
internal class LoadWatchdog(
    val stallMs: Long = 15_000,
    val bootMs: Long = 15_000,
    val hardCapMs: Long = 120_000,
) {
    enum class Verdict { WAIT, STALLED, BOOT_TIMEOUT, LOAD_CAP }

    private var startedAt = 0L
    private var movedAt = 0L
    private var progress = 0
    private var downloaded = false

    fun start(now: Long) { startedAt = now; movedAt = now; progress = 0; downloaded = false }

    /** Progress 0–100 from the WebView. Monotonic: a lower report is not the page going backwards. */
    fun progress(percent: Int, now: Long) {
        if (percent <= progress) return
        progress = percent
        movedAt = now
        if (percent >= 100) downloaded = true
    }

    fun loaded(now: Long) { downloaded = true; movedAt = now }

    fun check(now: Long): Verdict {
        val limit = if (downloaded) bootMs else stallMs
        if (now - movedAt >= limit) return if (downloaded) Verdict.BOOT_TIMEOUT else Verdict.STALLED
        if (now - startedAt >= hardCapMs) return Verdict.LOAD_CAP
        return Verdict.WAIT
    }

    /**
     * When to look again: the earlier of the two limits. Never busier than
     * every 250ms, and never slower than a quarter of the window — a fixed
     * floor swamps a short window the host asked for.
     */
    fun nextCheckInMs(now: Long): Long {
        val limit = if (downloaded) bootMs else stallMs
        val untilLimit = limit - (now - movedAt)
        val untilCap = hardCapMs - (now - startedAt)
        val floor = minOf(250L, limit / 4)
        return maxOf(floor, minOf(untilLimit, untilCap))
    }
}

/**
 * How often to reload a page that failed, and when to stop.
 *
 * Jittered ±25%: a thousand phones coming back from the same outage must not
 * reload the same page in the same second. Bounded: a phone with no network
 * stops and says so, rather than burning the battery in the background.
 */
internal class RetryLadder(
    private val delaysMs: List<Long> = listOf(1_000, 2_000, 4_000, 8_000, 15_000, 15_000),
    private val random: () -> Double = { Math.random() },
) {
    var attempt = 0
        private set

    val exhausted: Boolean get() = attempt >= delaysMs.size

    /** The delay before the next attempt, or null when every attempt is spent. */
    fun next(): Long? {
        if (exhausted) return null
        val base = delaysMs[attempt]
        attempt++
        return Math.round(base * (0.75 + random() * 0.5))
    }

    fun reset() { attempt = 0 }
}

/**
 * When the app comes back to the foreground, should the page be told to warm?
 *
 * The page keeps a SIP registration warm so a tap costs only the INVITE
 * (~0.2 s). A phone drops that idle socket while the app sleeps, and the page
 * hears of it only when something wakes it — its own visibilitychange is not
 * reliably that in a WebView a pixel wide. The app knows it is back, so it
 * says so: only when the page is ready to hear it, and never during a call,
 * whose registration a warm would replace. The same rule as the React Native
 * launcher's AppState listener.
 */
internal object Warm {
    fun onForeground(ready: Boolean, startedCall: Boolean, connected: Boolean, connecting: Boolean): Boolean =
        ready && !startedCall && !connected && !connecting
}

/**
 * WHERE A CONNECT IS, AND HOW LONG THE CUSTOMER HAS WAITED ON IT — the React
 * Native launcher's connectStage, slowConnect and callFailed
 * (voice-launcher.tsx), with nothing of Android in it. [GraineVoice] feeds it
 * the engine's `graine:progress` and its own state; the slow clock runs on
 * whatever [schedule] it is handed — the main thread in the SDK, a fake clock
 * in the tests.
 *
 * Measured, the commonest slow connect on mobile data is a connection the
 * phone dropped while idle being replaced: a few seconds a customer will wait
 * if the bar says what is happening, and will not if it says "Connecting…"
 * for all of them. A clock on the WAIT, not on the network: the network is
 * the engine's to report.
 */
internal class ConnectProgress(
    /** A wait this long is slow — [GraineConfig.Timing.slowConnectMs]. */
    private val slowAfterMs: Long,
    /** Runs `task` after `delayMs`, and returns what cancels it. */
    private val schedule: (delayMs: Long, task: () -> Unit) -> (() -> Unit),
    /** [stage], [slow] or [failed] changed. */
    private val onChange: () -> Unit,
) {
    /** Null outside a connect. A stage this build does not know is ignored, never stored. */
    var stage: ConnectStage? = null
        private set

    /** The customer has waited on this connect past [slowAfterMs]. */
    var slow = false
        private set

    /** Neither transport could carry the last start. */
    var failed = false
        private set

    private var waiting = false
    private var cancelClock: (() -> Unit)? = null

    /**
     * One `graine:progress`. True for `failed`: nothing could carry the call,
     * and the caller must stop saying "connecting". An unknown stage changes nothing.
     */
    fun onStage(raw: String?): Boolean {
        if (raw == FAILED) { set(null, true); return true }
        ConnectStage.from(raw)?.let { set(it, failed) }
        return false
    }

    /** A new attempt, a call that connected, or the customer's UI closing: the last stage and failure are not news. */
    fun clear() = set(null, false)

    /**
     * Whether the customer is waiting on a connect now ([waitingOnConnect]).
     * The clock starts when the wait does; a wait that ends — the call landed
     * or failed, the bar closed — is no longer slow.
     */
    fun waiting(now: Boolean) {
        if (now == waiting) return
        waiting = now
        cancelClock?.invoke()
        cancelClock = null
        if (now) {
            cancelClock = schedule(slowAfterMs) {
                cancelClock = null
                if (waiting && !slow) { slow = true; onChange() }
            }
        } else if (slow) {
            slow = false
            onChange()
        }
    }

    private fun set(stage: ConnectStage?, failed: Boolean) {
        if (stage == this.stage && failed == this.failed) return
        this.stage = stage
        this.failed = failed
        onChange()
    }

    companion object {
        /** The engine's word that ends a connect rather than describing one. */
        const val FAILED = "failed"

        /**
         * The React Native launcher's waitingOnConnect, for a platform with no
         * `active` prop: nothing has landed or failed, and the customer IS
         * waiting — a call they placed is connecting (a start held for the
         * page counts: start() says connecting at once), or their bar is open
         * on a page that is not ready yet. A page loading behind no open bar
         * is nobody's slow connect.
         */
        @JvmStatic
        fun waitingOnConnect(connected: Boolean, failed: Boolean, connecting: Boolean, ready: Boolean, barOpen: Boolean): Boolean =
            !connected && !failed && (connecting || (!ready && barOpen))
    }
}

/** What an action handler answers. The agent speaks about exactly this, so tell the truth. */
data class ActionResult(
    val status: Status,
    val message: String = "",
    val data: Map<String, Any?> = emptyMap(),
) {
    enum class Status(val wire: String) { OK("ok"), REFUSED("refused"), ERROR("error") }

    companion object {
        @JvmStatic @JvmOverloads
        fun ok(message: String = "", data: Map<String, Any?> = emptyMap()) = ActionResult(Status.OK, message, data)
        @JvmStatic fun refused(message: String) = ActionResult(Status.REFUSED, message)
        @JvmStatic fun error(message: String) = ActionResult(Status.ERROR, message)
    }
}

/** One input on a screen, as the agent should understand it. */
data class GraineField @JvmOverloads constructor(
    val name: String,
    val label: String? = null,
    val value: String? = null,
    val status: Status? = null,
    val error: String? = null,
    val optional: Boolean = false,
) {
    enum class Status(val wire: String) { EMPTY("empty"), FILLED("filled"), INVALID("invalid"), PENDING("pending"), LOCKED("locked") }
}

/**
 * What the customer is looking at. `status` on each field is what makes the
 * agent proactive: after a pause it names the field that is `invalid` or
 * `empty` — "the PAN isn't being accepted" — instead of asking "need a hand?".
 *
 * Report real values. Masking runs on the way out; a screen that pre-redacts
 * only blinds the agent.
 */
data class GraineScreen @JvmOverloads constructor(
    val screen: String,
    val title: String? = null,
    val journey: Journey? = null,
    val fields: List<GraineField> = emptyList(),
    val error: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val availableActions: List<String>? = null,
) {
    data class Journey @JvmOverloads constructor(val name: String, val step: Int? = null, val of: Int? = null, val next: String? = null)
}

/**
 * The context frame the runtime reads — same keys as the React Native SDK's
 * buildContextFrame(), masked on the way out.
 */
internal object ContextFrame {
    /**
     * `availableActions` for the frame — present, or deliberately absent
     * (the React Native and iOS SDKs' rule). The runtime reads an ARRAY as
     * the complete set of what may be called here, so [] forbids everything
     * while an ABSENT key allows everything the dashboard declared:
     *
     *  - a screen that STATES its actions is honoured exactly, [] included —
     *    narrowed to the ones this build registered, since an action the
     *    screen names but nothing implements would be offered and then
     *    refused mid-sentence;
     *  - otherwise every registered action is offered;
     *  - nothing registered and nothing stated leaves the key out, so a
     *    momentarily empty registry never disarms the agent.
     */
    fun availableActions(stated: List<String>?, registered: Collection<String>): List<String>? {
        if (stated != null) return stated.filter { it in registered }
        return if (registered.isEmpty()) null else registered.toList()
    }

    /**
     * The frame. With [registered] (the SDK passes its handlers), the actions
     * follow [availableActions]; without it, a screen's own list is carried as stated.
     */
    fun build(
        screen: GraineScreen?,
        screenSinceMs: Long,
        nowMs: Long,
        identity: Map<String, String>,
        appVersion: String?,
        recentEvents: List<JSONObject>,
        registered: Collection<String>? = null,
    ): JSONObject? {
        if (screen == null && identity.isEmpty() && recentEvents.isEmpty()) return null
        val ctx = JSONObject()
        if (screen != null) {
            ctx.put("screen", screen.screen)
            screen.title?.let { ctx.put("title", it) }
            screen.journey?.let { j ->
                ctx.put("journey", JSONObject().apply {
                    put("name", j.name)
                    j.step?.let { put("step", it) }
                    j.of?.let { put("of", it) }
                    j.next?.let { put("next", it) }
                })
            }
            if (screen.fields.isNotEmpty()) {
                ctx.put("fields", JSONArray().apply {
                    screen.fields.forEach { f ->
                        put(JSONObject().apply {
                            put("name", f.name)
                            f.label?.let { put("label", it) }
                            f.value?.let { put("value", it) }
                            f.status?.let { put("status", it.wire) }
                            f.error?.let { put("error", it) }
                            if (f.optional) put("optional", true)
                        })
                    }
                })
            }
            screen.error?.let { ctx.put("error", it) }
            if (screen.data.isNotEmpty()) ctx.put("data", Protocol.toJson(screen.data))
            ctx.put("idle_ms", (nowMs - screenSinceMs).coerceAtLeast(0))
        }
        if (identity.isNotEmpty()) ctx.put("user", JSONObject(identity as Map<*, *>))
        appVersion?.let { ctx.put("app_version", it) }
        if (recentEvents.isNotEmpty()) ctx.put("recent_events", JSONArray(recentEvents))
        val actions = if (registered != null) availableActions(screen?.availableActions, registered) else screen?.availableActions
        return JSONObject()
            .put("context", Masking.deep(ctx))
            .apply { actions?.let { put("availableActions", JSONArray(it)) } }
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// PARITY WITH THE REACT NATIVE SDK — components, loader, placement, keyboard,
// friction, appearance. Each section below is a port of one pure function or
// table from sdk/inapp (protocol.ts, friction.ts, react-native/*-math.ts,
// react-native/appearance.ts), pinned by RnParityTest against answers the RN
// SDK's own built code produced. Where a port had to choose, the comment says
// which way and why.
// ═════════════════════════════════════════════════════════════════════════════

/**
 * JAVASCRIPT'S OWN COERCIONS, for values read out of org.json.
 *
 * Several of the RN rules lean on `String(x)`, truthiness and `x ?? y`. A port
 * that used Kotlin's equivalents would agree on every ordinary input and
 * differ on the odd one — a numeric price, a `null` title — which is exactly
 * where nobody looks. So the port is literal: Kotlin `null` stands for JS
 * `undefined` (absent), [JSONObject.NULL] for JS `null`.
 */
internal object Js {
    fun nullish(v: Any?): Boolean = v == null || v == JSONObject.NULL

    fun truthy(v: Any?): Boolean = when {
        nullish(v) -> false
        v is Boolean -> v
        v is String -> v.isNotEmpty()
        v is Number -> v.toDouble().let { !(it == 0.0 || it.isNaN()) }
        else -> true
    }

    /** `String(v)`. */
    fun str(v: Any?): String = when {
        v == null -> "undefined"
        v == JSONObject.NULL -> "null"
        v is String -> v
        v is Boolean -> v.toString()
        v is Int || v is Long || v is Short || v is Byte -> v.toString()
        v is Number -> number(v.toDouble())
        v is JSONArray -> (0 until v.length()).joinToString(",") { i -> v.opt(i).let { if (nullish(it)) "" else str(it) } }
        v is Iterable<*> -> v.joinToString(",") { if (nullish(it)) "" else str(it) }
        v is Array<*> -> v.joinToString(",") { if (nullish(it)) "" else str(it) }
        v is JSONObject || v is Map<*, *> -> "[object Object]"
        else -> v.toString()
    }

    /** `String(v ?? "")` — the idiom every allowlist in the RN SDK reads a value through. */
    fun strOrEmpty(v: Any?): String = if (nullish(v)) "" else str(v)

    /** `typeof v === "number" && Number.isFinite(v)`, as the number. */
    fun finite(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() }

    /** `Math.round`: half rounds up, toward +∞, as JavaScript does. */
    fun round(d: Double): Double = Math.floor(d + 0.5)

    /** Number.prototype.toString(): no ".0" on an integer, exponent form outside 1e-6..1e21. */
    fun number(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return "0"
        val abs = Math.abs(d)
        if (d == Math.rint(d) && abs < 1e21) return java.math.BigDecimal(d).toPlainString()
        val bd = java.math.BigDecimal(d.toString()).stripTrailingZeros()
        if (abs >= 1e-6 && abs < 1e21) return bd.toPlainString()
        val digits = bd.unscaledValue().abs().toString()
        val exp = digits.length - 1 - bd.scale()
        val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
        return (if (d < 0) "-" else "") + mantissa + "e" + (if (exp >= 0) "+" else "-") + Math.abs(exp)
    }

    /** A JSON object's entries in its own order (insertion order on Android's org.json). */
    fun entries(o: JSONObject): List<Pair<String, Any?>> = o.keys().asSequence().map { it to o.opt(it) }.toList()

    /** A JS value as a Kotlin one, recursively: objects to maps, arrays to lists, `null` to null. */
    fun plain(v: Any?): Any? = when {
        nullish(v) -> null
        v is JSONObject -> LinkedHashMap<String, Any?>().also { m -> entries(v).forEach { (k, x) -> m[k] = plain(x) } }
        v is JSONArray -> (0 until v.length()).map { plain(v.opt(it)) }
        else -> v
    }
}

// ── components: how one looks ──────────────────────────────────────────────

/**
 * HOW A COMPONENT LOOKS — chosen on the component in the dashboard, carried
 * with it to the agent, drawn by the SDK. Only allowlisted keys and values
 * survive [Widgets.normaliseStyle]: an unknown preset draws Cards, never
 * nothing, and a phone on an older SDK does not guess.
 *
 * Strings rather than enums on purpose: these are the wire's own words, and a
 * host drawing its own UI compares against the same words the dashboard and
 * the React Native SDK use.
 */
data class WidgetStyle(
    val preset: String? = null,
    val imageSize: String? = null,
    val density: String? = null,
    val button: String? = null,
    val facts: Boolean? = null,
    val columns: Int? = null,
    val surface: String? = null,
    val radius: Int? = null,
    val accent: String? = null,
    val platform: String? = null,
    val select: String? = null,
    val maxSelect: Int? = null,
)

/** The effective look: the preset's defaults, then the knobs on top. */
data class WidgetLook(
    val preset: String,
    val imageSize: String,
    val density: String,
    val button: String,
    val facts: Boolean,
    val columns: Int,
    /** The surface to draw on, "auto" already resolved for the kind. */
    val surface: String,
    val radius: Int? = null,
    val accent: String? = null,
    /** As chosen; "auto" is resolved by the renderer, which knows the phone. */
    val platform: String,
    val select: String,
    val maxSelect: Int,
)

/**
 * WHAT A PLATFORM'S DESIGN GRAMMAR CHANGES — the shapes people on that phone
 * already know. The colours stay the brand's. `radius` is used only for an
 * explicit platform; under "auto" the Appearance radius wins.
 */
data class PlatformGrammar(
    val radius: Int,
    val chipRadius: Int,
    val buttonRadius: Int,
    val pillButtons: Boolean,
    val separatorInset: Int,
    val grabberWidth: Int,
    val grabberHeight: Int,
    /** CSS weight: 500, 600 or 700. */
    val titleWeight: Int,
    val padBoost: Int,
    val chipOutlined: Boolean,
)

/**
 * WHAT THE APP DOES WHEN THE CUSTOMER TAPS, beyond telling the agent: an
 * in-app action (or a path for the one navigate handler), with arguments
 * filled from the tapped item. Empty when the component names neither.
 */
data class WidgetSelectAction(
    val path: String? = null,
    val action: String? = null,
    val args: Map<String, String>? = null,
    val say: String? = null,
) {
    val isEmpty: Boolean get() = path == null && action == null
}

/** One tap's in-app call: the action to run and what to run it with. */
data class SelectCall(val name: String, val args: Map<String, String>)

internal object Widgets {
    val PRESETS = listOf("gallery", "cards", "list", "spotlight", "compact")
    val IMAGE_SIZES = listOf("small", "medium", "large")
    val DENSITIES = listOf("comfortable", "compact")
    val BUTTONS = listOf("filled", "outline", "row")
    val SURFACE_CHOICES = listOf("auto", "screen", "sheet", "card")
    val PLATFORMS = listOf("auto", "ios", "material", "oneui")
    val SELECTS = listOf("one", "many")
    const val MAX_SELECT_DEFAULT = 3
    const val DEFAULT_PRESET = "cards"

    /** The one handler an app registers so the dashboard can open any screen. */
    const val NAVIGATE_ACTION = "open_screen"
    const val SELECT_ARG_MAX = 10
    const val SELECT_VALUE_MAX = 200

    /** Once the loader is on screen it stays this long, so it reads as loading rather than a glitch. */
    const val MIN_LOADER_MS = 450L
    /** How long a "component coming" loader may stand with nothing arriving. */
    const val PENDING_WIDGET_MS = 12_000L
    /** How long the answered line says "thinking" while the agent has not started. */
    const val AWAITING_REPLY_MS = 12_000L

    private val SURFACE = mapOf(
        "product_list" to "screen", "products" to "screen",
        "plan_comparison" to "sheet", "choices" to "sheet", "quick_actions" to "sheet", "calendar_slots" to "sheet",
        "info_card" to "card",
    )

    /**
     * Where a component goes: "screen", "sheet" or "card". An unknown kind is
     * a card, because what is drawn for it is one fallback sentence.
     */
    fun surface(kind: String?): String = SURFACE[(kind ?: "").trim().lowercase()] ?: "card"

    private fun pick(v: Any?, allowed: List<String>): String? =
        Js.strOrEmpty(v).trim().lowercase().takeIf { it in allowed }

    /** Only the keys and values the renderers read. Anything else is dropped. */
    fun normaliseStyle(raw: Any?): WidgetStyle {
        val r: (String) -> Any? = when (raw) {
            is JSONObject -> { k -> raw.opt(k) }
            is Map<*, *> -> { k -> raw[k] }
            else -> return WidgetStyle()
        }
        val columns = r("columns").let { c ->
            when {
                Js.finite(c) == 2.0 || c == "2" -> 2
                Js.finite(c) == 1.0 || c == "1" -> 1
                else -> null
            }
        }
        val accent = (r("accent") as? String)?.trim()?.takeIf { Regex("^#[0-9a-fA-F]{6}$").matches(it) }?.uppercase()
        return WidgetStyle(
            preset = pick(r("preset"), PRESETS),
            imageSize = pick(r("imageSize"), IMAGE_SIZES),
            density = pick(r("density"), DENSITIES),
            button = pick(r("button"), BUTTONS),
            facts = r("facts") as? Boolean,
            columns = columns,
            surface = pick(r("surface"), SURFACE_CHOICES),
            radius = Js.finite(r("radius"))?.let { Js.round(it).coerceIn(0.0, 24.0).toInt() },
            accent = accent,
            platform = pick(r("platform"), PLATFORMS),
            select = pick(r("select"), SELECTS),
            maxSelect = Js.finite(r("maxSelect"))?.let { Js.round(it).coerceIn(2.0, 8.0).toInt() },
        )
    }

    private val PRESET_DEFAULTS = mapOf(
        "gallery" to ("large" to "filled"),
        "cards" to ("medium" to "filled"),
        "list" to ("small" to "row"),
        "spotlight" to ("large" to "filled"),
        "compact" to ("small" to "outline"),
    )

    fun resolveLook(style: WidgetStyle?, kind: String?): WidgetLook {
        val s = style ?: WidgetStyle()
        val preset = s.preset?.takeIf { it in PRESETS } ?: DEFAULT_PRESET
        val (imageSize, button) = PRESET_DEFAULTS.getValue(preset)
        return WidgetLook(
            preset = preset,
            imageSize = s.imageSize ?: imageSize,
            density = s.density ?: "comfortable",
            button = s.button ?: button,
            facts = s.facts ?: true,
            columns = if ((preset == "gallery" || preset == "cards") && s.columns == 2) 2 else 1,
            surface = if (s.surface != null && s.surface != "auto") s.surface else surface(kind),
            radius = s.radius,
            accent = s.accent,
            platform = s.platform ?: "auto",
            select = s.select ?: "one",
            maxSelect = s.maxSelect ?: MAX_SELECT_DEFAULT,
        )
    }

    val GRAMMAR: Map<String, PlatformGrammar> = mapOf(
        "ios" to PlatformGrammar(12, 8, 12, false, 16, 36, 5, 600, 0, false),
        "material" to PlatformGrammar(16, 8, 20, true, 0, 32, 4, 500, 0, true),
        "oneui" to PlatformGrammar(26, 999, 999, true, 0, 40, 4, 700, 2, false),
    )

    /** "auto" → the phone's own grammar: Material on Android, One UI on a Samsung, Apple's on iOS. */
    fun resolvePlatform(platform: String?, os: String?, manufacturer: String? = null): String {
        if (platform != null && platform != "auto") return platform
        if (os == "android") return if (Regex("samsung", RegexOption.IGNORE_CASE).containsMatchIn(manufacturer ?: "")) "oneui" else "material"
        return "ios"
    }

    /**
     * The one button under a multi-select list. Below two it says how many
     * more — and not "to <label>": the author's label is a button's word
     * ("Compare", "Taken"), not always a verb, and "Pick 2 more to taken" is
     * what reading it as one said.
     */
    fun manyButtonLabel(chosen: Int, selectLabel: String): String =
        if (chosen < 2) "Pick ${2 - chosen} more" else "$selectLabel $chosen"

    /**
     * A full-screen component's footer: on a call, "STILL LISTENING" and how to
     * answer — the agent is still there, listening. A component that arrived in
     * the chat is on no call: no footer at all.
     */
    fun screenFooter(prompt: String, typed: Boolean): Pair<String, String>? =
        if (typed) null else "STILL LISTENING" to prompt.ifEmpty { "Tap one, or just say it." }

    /** The component's close control, for a screen reader: in the chat there is no talking to keep on with. */
    fun closeLabel(typed: Boolean): String = if (typed) "Close" else "Close and keep talking"

    /**
     * The sentence the agent hears when several were chosen — and the user's
     * own bubble in a text chat: the button's label as written, then the names
     * ("Taken: Morning and Evening"). Never the label read as a verb. No
     * label: the names alone.
     */
    fun manySummary(titles: List<String>, label: String = "Compare"): String {
        val names = titles.map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return ""
        val list = if (names.size == 1) names[0] else "${names.dropLast(1).joinToString(", ")} and ${names.last()}"
        val lead = label.trim()
        return if (lead.isEmpty()) list else "$lead: $list"
    }

    private val ACTION_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")

    fun normaliseSelectAction(raw: Any?): WidgetSelectAction {
        val r: (String) -> Any? = when (raw) {
            is JSONObject -> { k -> raw.opt(k) }
            is Map<*, *> -> { k -> raw[k] }
            else -> return WidgetSelectAction()
        }
        val name = Js.strOrEmpty(r("action")).trim().lowercase().takeIf { ACTION_NAME.matches(it) }
        val path = (r("path") as? String)?.trim()?.take(SELECT_VALUE_MAX)?.takeIf { it.isNotEmpty() }
        val rawArgs: List<Pair<String, Any?>>? = when (val a = r("args")) {
            is JSONObject -> Js.entries(a)
            is Map<*, *> -> a.entries.map { (k, v) -> k.toString() to v }
            else -> null
        }
        var args: LinkedHashMap<String, String>? = null
        if (rawArgs != null) {
            val out = LinkedHashMap<String, String>()
            for ((k, v) in rawArgs) {
                val key = k.trim()
                if (key.isEmpty() || out.size >= SELECT_ARG_MAX) continue
                // `typeof v === "object"` — null, arrays and objects are not arguments.
                if (Js.nullish(v) || v is JSONObject || v is JSONArray || v is Map<*, *> || v is Iterable<*>) continue
                out[key] = Js.str(v).take(SELECT_VALUE_MAX)
            }
            if (out.isNotEmpty()) args = out
        }
        val say = (r("say") as? String)?.trim()?.take(SELECT_VALUE_MAX)?.takeIf { it.isNotEmpty() }
        // Arguments alone are nothing: a half-configured component must not look configured.
        return if (name != null || path != null) WidgetSelectAction(path, name, args, say) else WidgetSelectAction()
    }

    private val PLACEHOLDER = Regex("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}")

    /** Fill {id}/{title}/… from the tapped item. An unknown placeholder becomes empty, never literal. */
    fun fillSelectTemplate(template: String, item: Map<String, Any?>): String =
        PLACEHOLDER.replace(template) { m -> Js.strOrEmpty(item[m.groupValues[1]]) }

    fun fillSelectArgs(args: Map<String, String>?, item: Map<String, Any?>): Map<String, String> =
        LinkedHashMap<String, String>().also { out -> args?.forEach { (k, v) -> out[k] = fillSelectTemplate(v, item) } }

    /**
     * The action to run for one tap: an explicit action wins, a path goes to
     * [NAVIGATE_ACTION], and the path travels as an argument either way.
     */
    fun selectCall(onSelect: WidgetSelectAction?, item: Map<String, Any?>): SelectCall? {
        val s = onSelect ?: return null
        if (s.action == null && s.path == null) return null
        val args = LinkedHashMap(fillSelectArgs(s.args, item))
        if (s.path != null) args["path"] = fillSelectTemplate(s.path, item)
        return SelectCall(s.action ?: NAVIGATE_ACTION, args)
    }

    private val IMAGE_KEY = Regex(
        "^(image|imageurl|image_url|icon|iconurl|icon_url|logo|logourl|logo_url|photo|photourl|photo_url|thumbnail|thumbnailurl|thumbnail_url)$",
        RegexOption.IGNORE_CASE,
    )

    private fun resolveOne(value: Any?, base: String): Any? =
        if (value !is String || !value.startsWith("/") || value.startsWith("//")) value else base.trimEnd('/') + value

    /**
     * A root-relative picture ("/samples/car.png") is resolved against the
     * agent's origin — the one place it could have come from. Only keys that
     * name an image, top level and one level into a list; a copy, not an edit.
     */
    fun resolveImages(data: JSONObject, baseUrl: String): JSONObject {
        if (baseUrl.isEmpty()) return data
        val out = JSONObject()
        for ((k, v) in Js.entries(data)) {
            when {
                IMAGE_KEY.matches(k) -> out.put(k, resolveOne(v, baseUrl))
                v is JSONArray -> out.put(k, JSONArray().also { rows ->
                    for (i in 0 until v.length()) {
                        val row = v.opt(i)
                        rows.put(if (row is JSONObject) JSONObject().also { o ->
                            for ((rk, rv) in Js.entries(row)) o.put(rk, if (IMAGE_KEY.matches(rk)) resolveOne(rv, baseUrl) else rv)
                        } else row)
                    }
                })
                else -> out.put(k, v)
            }
        }
        return out
    }
}

// ── the loader ─────────────────────────────────────────────────────────────

/** Words for each moment a customer waits. Null means "use the default". */
data class LoaderText(
    val connecting: String? = null,
    val reconnecting: String? = null,
    val thinking: String? = null,
    val component: String? = null,
) {
    fun forMoment(moment: String): String? = when (moment) {
        "connecting" -> connecting; "reconnecting" -> reconnecting; "thinking" -> thinking; "component" -> component; else -> null
    }
}

/** HOW WAITING LOOKS, as chosen in Embed & Widgets → Appearance. Allowlisted. */
data class LoaderStyle(
    val kind: String? = null,
    /** "accent" or #RRGGBB. */
    val color: String? = null,
    val size: String? = null,
    val speed: String? = null,
    val text: LoaderText? = null,
    val skeleton: Boolean? = null,
)

/** The effective loader: numbers a renderer can use directly. */
data class LoaderLook(
    val kind: String,
    /** Null means "use the accent". */
    val color: String?,
    /** The mark's size, dp. */
    val px: Int,
    /** One animation cycle. */
    val periodMs: Long,
    /** Every moment filled in. */
    val text: LoaderText,
    val skeleton: Boolean,
)

object Loaders {
    val KINDS = listOf("dots", "pulse", "ring", "bars", "mark")
    val SIZES = listOf("small", "medium", "large")
    val SPEEDS = listOf("calm", "normal", "quick")
    val MOMENTS = listOf("connecting", "reconnecting", "thinking", "component")
    const val DEFAULT_KIND = "dots"
    const val TEXT_MAX = 40
    val DEFAULT_TEXT = LoaderText("Connecting…", "Reconnecting…", "Thinking…", "Loading…")
    private val PX = mapOf("small" to 14, "medium" to 20, "large" to 28)
    private val PERIOD = mapOf("calm" to 1600L, "normal" to 1100L, "quick" to 700L)

    private fun pick(v: Any?, allowed: List<String>): String? =
        Js.strOrEmpty(v).trim().lowercase().takeIf { it in allowed }

    fun normalise(raw: Any?): LoaderStyle {
        if (raw !is JSONObject) return LoaderStyle()
        val color = (raw.opt("color") as? String)?.trim()?.let { c ->
            when {
                c.lowercase() == "accent" -> "accent"
                Regex("^#[0-9a-fA-F]{6}$").matches(c) -> c.uppercase()
                else -> null
            }
        }
        val text = (raw.opt("text") as? JSONObject)?.let { t ->
            fun word(m: String) = (t.opt(m) as? String)?.trim()?.take(TEXT_MAX)?.takeIf { it.isNotEmpty() }
            LoaderText(word("connecting"), word("reconnecting"), word("thinking"), word("component"))
                .takeIf { it != LoaderText() }
        }
        return LoaderStyle(
            kind = pick(raw.opt("kind"), KINDS),
            color = color,
            size = pick(raw.opt("size"), SIZES),
            speed = pick(raw.opt("speed"), SPEEDS),
            text = text,
            skeleton = raw.opt("skeleton") as? Boolean,
        )
    }

    /** The look for a raw `appearance.loader` value (anything; junk resolves to the default). */
    fun resolve(raw: Any?): LoaderLook = resolve(normalise(raw))

    fun resolve(s: LoaderStyle): LoaderLook = LoaderLook(
        kind = s.kind ?: DEFAULT_KIND,
        color = s.color?.takeIf { it != "accent" },
        px = PX.getValue(s.size ?: "medium"),
        periodMs = PERIOD.getValue(s.speed ?: "normal"),
        text = LoaderText(
            connecting = s.text?.connecting ?: DEFAULT_TEXT.connecting,
            reconnecting = s.text?.reconnecting ?: DEFAULT_TEXT.reconnecting,
            thinking = s.text?.thinking ?: DEFAULT_TEXT.thinking,
            component = s.text?.component ?: DEFAULT_TEXT.component,
        ),
        skeleton = s.skeleton ?: false,
    )
}

// ── where the bar may sit, and how far it rises for the keyboard ───────────

/**
 * WHERE THE BAR MAY SIT — arithmetic only. The bar is anchored to the bottom;
 * the customer drags it and the result is an OFFSET above that anchor,
 * clamped so the bar can never leave the screen. Units are the caller's; the
 * SDK passes dp, so an offset persisted on one phone means the same on another
 * and the same as the React Native SDK's.
 */
object Placement {
    /** A drag that moved less than this (in both axes) is a tap, and the tap must still work. */
    const val DRAG_SLOP = 8.0

    data class Bounds(
        /** The screen's height. */
        val windowHeight: Double,
        /** Where the bar sits with no offset. */
        val baseBottom: Double,
        /** The bar's rendered height, as last measured. 0 before the first layout. */
        val barHeight: Double,
        /** Space to keep clear at the top — status bar, notch. */
        val topMargin: Double = 48.0,
    )

    fun clampOffset(offset: Double, b: Bounds): Int {
        if (offset.isNaN() || offset.isInfinite()) return 0
        val height = if (b.barHeight > 0) b.barHeight else 64.0
        val maxOffset = maxOf(0.0, b.windowHeight - b.topMargin - height - b.baseBottom)
        return Js.round(minOf(maxOf(offset, 0.0), maxOffset)).toInt()
    }

    /** The offset during a drag: where it started plus how far the finger moved UP (dy grows downward). */
    fun offsetDuringDrag(startOffset: Double, dy: Double, b: Bounds): Int = clampOffset(startOffset - dy, b)

    fun isTap(dx: Double, dy: Double): Boolean = Math.abs(dx) < DRAG_SLOP && Math.abs(dy) < DRAG_SLOP
}

/**
 * HOW FAR A BOTTOM-ANCHORED BAR MUST RISE TO STAY ABOVE THE KEYBOARD: the
 * keyboard's height minus whatever the window already shrank. With
 * adjustResize the window moved it already; edge-to-edge (every app targeting
 * Android 15+) and adjustPan do not, and the bar must rise by the keyboard.
 */
object KeyboardLift {
    fun liftFor(keyboardHeight: Double, windowShrink: Double): Int {
        if (!(keyboardHeight > 0)) return 0
        val shrink = if (windowShrink > 0) windowShrink else 0.0
        return maxOf(0.0, Js.round(keyboardHeight - shrink)).toInt()
    }
}

// ── friction: noticing a customer is stuck before they say so ──────────────

/**
 * The app's layer of friction rules, over the dashboard's (Embed & Widgets →
 * friction JSON, served with the session) over the built-in defaults. Every
 * field is optional; null means "inherit the layer below".
 *
 * Only the idle rule is ported (stalled_on_step). `idleAfterMs` of 0 or less
 * switches it off.
 */
data class FrictionConfig @JvmOverloads constructor(
    val enabled: Boolean? = null,
    /** On the same screen this long → stalled_on_step. Default 45s; 0 or less is off. */
    val idleAfterMs: Long? = null,
    /** Minimum gap between two signals on the same screen. Default 60s. */
    val cooldownMs: Long? = null,
    /** Runtime pacing for the agent's unsolicited interventions. Defaults 20s / 3. */
    val cooldownSeconds: Int? = null,
    val maxPerSession: Int? = null,
    /** Carried to [GraineListener.onFriction] for a host that opens its own launcher. */
    val openLauncher: Boolean? = null,
    /** What the agent is told instead of the built-in sentence: {title} {screen} {seconds} {field} {error}. */
    val message: String? = null,
    /**
     * Fire only while a field is outstanding (invalid, or empty and not
     * optional), with the clock running from when it became so. Default true.
     * False is the React Native SDK's rule: idle on the screen at all.
     */
    val requireOutstandingField: Boolean? = null,
    /** Per-screen overrides, keyed by the screen id the app reports. */
    val screens: Map<String, Screen> = emptyMap(),
) {
    data class Screen(
        /** No friction detection at all on this screen — someone reading terms is not stuck. */
        val ignore: Boolean? = null,
        val idleAfterMs: Long? = null,
        val message: String? = null,
    )

    companion object {
        /** A positive number, or a string holding one — a hand-edited JSON document says both. */
        private fun num(v: Any?): Double? {
            val d = when (v) { is Number -> v.toDouble(); is String -> v.trim().takeIf { Regex("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$").matches(it) }?.toDouble(); else -> null }
            return d?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0 }
        }

        /** `false` is off (0); an object's `afterMs` is the time; anything else inherits. */
        private fun idle(v: Any?): Long? = when {
            v == false -> 0L
            v is JSONObject -> num(v.opt("afterMs"))?.toLong()
            else -> null
        }

        /** The dashboard's layer, from the session's `friction` object. Unknown keys are dropped. */
        fun fromJson(o: JSONObject?): FrictionConfig? {
            if (o == null) return null
            val nudge = o.opt("nudge") as? JSONObject
            val screens = LinkedHashMap<String, Screen>()
            (o.opt("screens") as? JSONObject)?.let { s ->
                for ((id, raw) in Js.entries(s)) {
                    if (id.isEmpty() || raw !is JSONObject) continue
                    screens[id] = Screen(
                        ignore = if (raw.opt("ignore") == true) true else null,
                        idleAfterMs = idle(raw.opt("idle")),
                        message = (raw.opt("message") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                    )
                }
            }
            return FrictionConfig(
                enabled = o.opt("enabled") as? Boolean,
                idleAfterMs = idle(o.opt("idle")),
                cooldownMs = num(o.opt("cooldownMs"))?.toLong(),
                cooldownSeconds = num(nudge?.opt("cooldownSeconds"))?.toInt(),
                maxPerSession = num(nudge?.opt("maxPerSession"))?.toInt(),
                openLauncher = nudge?.opt("openLauncher") as? Boolean,
                message = (o.opt("message") as? String)?.trim()?.takeIf { it.isNotEmpty() },
                requireOutstandingField = o.opt("requireOutstandingField") as? Boolean,
                screens = screens,
            )
        }
    }
}

/** Every layer applied: what is actually in force. */
data class FrictionRules(
    val enabled: Boolean = true,
    /** Null: the idle rule is off. */
    val idleAfterMs: Long? = 45_000,
    val cooldownMs: Long = 60_000,
    val cooldownSeconds: Int = 20,
    val maxPerSession: Int = 3,
    val openLauncher: Boolean = false,
    val message: String = "",
    val requireOutstandingField: Boolean = true,
    val screens: Map<String, FrictionConfig.Screen> = emptyMap(),
) {
    data class ForScreen(val ignore: Boolean, val idleAfterMs: Long?, val message: String)

    /** The rules on one screen: the top level with that screen's overrides on it. */
    fun forScreen(id: String?): ForScreen {
        val over = id?.let { screens[it] }
        val idle = when (val o = over?.idleAfterMs) { null -> idleAfterMs; else -> o.takeIf { it > 0 } }
        return ForScreen(over?.ignore ?: false, idle, over?.message ?: message)
    }
}

internal object Friction {
    /** Defaults ← each layer in order, each changing only what it names. */
    fun resolve(vararg layers: FrictionConfig?): FrictionRules {
        var r = FrictionRules()
        for (l in layers) {
            if (l == null) continue
            val screens = LinkedHashMap(r.screens)
            for ((id, s) in l.screens) {
                val cur = screens[id]
                screens[id] = FrictionConfig.Screen(
                    ignore = s.ignore ?: cur?.ignore,
                    idleAfterMs = s.idleAfterMs ?: cur?.idleAfterMs,
                    message = s.message ?: cur?.message,
                )
            }
            r = r.copy(
                enabled = l.enabled ?: r.enabled,
                idleAfterMs = when (val i = l.idleAfterMs) { null -> r.idleAfterMs; else -> i.takeIf { it > 0 } },
                cooldownMs = l.cooldownMs?.takeIf { it > 0 } ?: r.cooldownMs,
                cooldownSeconds = l.cooldownSeconds?.takeIf { it > 0 } ?: r.cooldownSeconds,
                maxPerSession = l.maxPerSession?.takeIf { it > 0 } ?: r.maxPerSession,
                openLauncher = l.openLauncher ?: r.openLauncher,
                message = l.message ?: r.message,
                requireOutstandingField = l.requireOutstandingField ?: r.requireOutstandingField,
                screens = screens,
            )
        }
        return r
    }
}

/** The customer looks stuck. `detail` is plain English, given to the agent verbatim. */
data class FrictionSignal(
    val name: String,
    val screen: String,
    val detail: String,
    val data: Map<String, Any?>,
    val cooldownSeconds: Int,
    val maxPerSession: Int,
    val openLauncher: Boolean,
)

/**
 * STALLED ON A STEP — the idle half of the React Native SDK's friction
 * detector, with time as an argument so every rule is a JVM test.
 *
 * The clock is per screen VISIT: a field edit on the same screen does not
 * restart it (a customer typing one character every forty seconds is exactly
 * who is stuck); a different screen, or [onInteraction], does. It fires at
 * most once per visit, and never twice on one screen inside the cooldown.
 *
 * Two gates the React Native rule does not have, each on by default in the
 * SDK and off here so the RN rule itself stays provable:
 *  - [FrictionRules.requireOutstandingField]: the clock runs only while a
 *    field is outstanding, from the moment it became so.
 *  - `gateOnCall`: the clock runs only while a call is live, from when it
 *    connected — so the one signal per visit is spent on a conversation that
 *    can hear it, and a new call on the same screen may hear it again.
 */
internal class FrictionDetector(rules: FrictionRules = FrictionRules(), private val gateOnCall: Boolean = false) {
    var rules: FrictionRules = rules
        private set
    private var screen: GraineScreen? = null
    private var screenSince = 0L
    private var stuckSince: Long? = null
    private var callSince: Long? = null
    private var idleFiredFor: String? = null
    private val lastSignalAt = HashMap<String, Long>()

    fun configure(next: FrictionRules) { rules = next }

    /** The app reported a screen. A different id is a new visit; the same id is an update. */
    fun onScreen(next: GraineScreen?, now: Long) {
        val prev = screen
        screen = next
        if (next == null) { stuckSince = null; return }
        if (prev == null || prev.screen != next.screen) {
            screenSince = now
            idleFiredFor = null
            stuckSince = null
        }
        stuckSince = if (outstanding(next.fields).isEmpty()) null else (stuckSince ?: now)
    }

    /** The customer did something — the idle clock restarts. */
    fun onInteraction(now: Long) { screenSince = now }

    /** A call connected or ended. Only read when the detector is gated on a call. */
    fun onCall(live: Boolean, now: Long) {
        if (live && callSince == null) { callSince = now; idleFiredFor = null }
        if (!live) callSince = null
    }

    private fun active(): FrictionRules.ForScreen? {
        val s = screen ?: return null
        if (!rules.enabled) return null
        return rules.forScreen(s.screen).takeIf { !it.ignore }
    }

    private fun startedAt(): Long? {
        var start = screenSince
        if (rules.requireOutstandingField) start = maxOf(start, stuckSince ?: return null)
        if (gateOnCall) start = maxOf(start, callSince ?: return null)
        return start
    }

    /** How long until the idle rule should fire, or null if it should not. */
    fun idleDueIn(now: Long): Long? {
        val r = active() ?: return null
        val after = r.idleAfterMs ?: return null
        if (idleFiredFor == screen?.screen) return null
        val start = startedAt() ?: return null
        return maxOf(0L, after - (now - start))
    }

    /** The idle timer went off. At most once per visit. */
    fun onIdle(now: Long): FrictionSignal? {
        val r = active() ?: return null
        val after = r.idleAfterMs ?: return null
        val s = screen ?: return null
        if (idleFiredFor == s.screen) return null
        val start = startedAt() ?: return null
        if (now - start < after) return null
        idleFiredFor = s.screen

        val last = lastSignalAt[s.screen]
        if (last != null && now - last < rules.cooldownMs) return null
        lastSignalAt[s.screen] = now

        val pool = if (rules.requireOutstandingField) outstanding(s.fields) else s.fields
        val stuck = pool.firstOrNull { it.status == GraineField.Status.INVALID || Js.truthy(it.error) }
            ?: pool.firstOrNull { it.status == GraineField.Status.EMPTY }
        val seconds = Js.round(after / 1000.0).toLong()
        val vars = linkedMapOf<String, Any?>(
            "seconds" to seconds,
            "field" to (stuck?.let { it.label ?: it.name } ?: ""),
            "error" to (stuck?.let { it.error ?: it.status?.wire } ?: ""),
        )
        val all = linkedMapOf<String, Any?>("title" to (s.title ?: s.screen), "screen" to s.screen).apply { putAll(vars) }
        val fallback = if (stuck != null) {
            "The customer has not touched the \"{title}\" screen for {seconds}s. The \"{field}\" field is " +
                (if (Js.truthy(stuck.error)) "showing: {error}" else "{error}") + "."
        } else "The customer has not touched the \"{title}\" screen for {seconds}s."
        return FrictionSignal(
            name = "stalled_on_step",
            screen = s.screen,
            detail = fill(if (r.message.isNotEmpty()) r.message else fallback, all),
            data = vars,
            cooldownSeconds = rules.cooldownSeconds,
            maxPerSession = rules.maxPerSession,
            openLauncher = rules.openLauncher,
        )
    }

    companion object {
        /**
         * What the customer still has to deal with. `locked` is not theirs to
         * fix and `optional` not theirs to have to; an explicit error outranks
         * `optional`.
         */
        fun outstanding(fields: List<GraineField>): List<GraineField> = fields.filter {
            Js.truthy(it.error) || (!it.optional && (it.status == GraineField.Status.INVALID || it.status == GraineField.Status.EMPTY))
        }

        private val VAR = Regex("\\{(\\w+)\\}")
        fun fill(template: String, vars: Map<String, Any?>): String =
            VAR.replace(template) { m -> vars[m.groupValues[1]]?.let { Js.str(it) } ?: "" }
    }
}

// ── appearance: what the dashboard says, translated for a phone ─────────────

/** The dashboard's appearance, translated: see [Appearance.resolve]. */
data class NativeAppearance @JvmOverloads constructor(
    /** Always a valid "#rgb" or "#rrggbb". */
    val accent: String,
    /** The panel heading ("Talk to our AI assistant") — NOT the assistant's name. */
    val heading: String,
    /** Absolute, or null when no logo is set. The dashboard stores an S3 key, not a URL. */
    val logoUrl: String?,
    /** Corner rounding, clamped to what the host draws. */
    val radius: Double,
    /**
     * What the logo sits on where it stands in for the launcher glyph — the
     * accent, or a white plate (contract/DESIGN.md "The brand's logo"). Last,
     * with a default, so 0.4.0's four-argument constructor still builds.
     */
    val logoPlate: LogoPlate = LogoPlate.ACCENT,
)

/**
 * WHAT THE BRAND'S LOGO SITS ON — `appearance.logoPlate`, contract/DESIGN.md
 * "The brand's logo".
 *
 *   ACCENT  the shape's own fill: for a white or light logo made for a
 *           coloured background. How logos were always drawn, and the default.
 *   WHITE   #FFFFFF with a hairline in `border`: for a coloured or dark logo,
 *           or an opaque image — which is what most logo files are. A logo in
 *           the brand's own colour on the brand's own fill is no logo at all.
 *
 * The dashboard reads it from the logo's pixels when it is uploaded, and the
 * brand can change it under Appearance → Logo.
 */
enum class LogoPlate(val wire: String) {
    ACCENT("accent"), WHITE("white");

    companion object {
        /** theme.ts's normaliseLogoPlate: exactly "white" is white; anything else — "WHITE", 1, absent — is the accent. */
        @JvmStatic
        fun from(value: Any?): LogoPlate = if (value == WHITE.wire) WHITE else ACCENT
    }
}

/**
 * THE BRAND'S LOGO, WHOLE AND ON ITS PLATE — theme.ts's logoBox and
 * logoPlateLook, for every place the logo stands in for the launcher glyph:
 * the launcher, the voice bar's avatar, the chat header's mark.
 *
 * It used to be drawn FILLING its shape (centre-crop): a wordmark lost both
 * ends, a square logo lost its corners to the circle. It is fitted instead —
 * whole, proportions kept, centred — into the largest square the shape holds.
 */
object Logo {
    /** A white plate's fill. */
    const val PLATE_WHITE = "#FFFFFF"

    /** A pill's label on the white plate — theme.ts's readableOn("#FFFFFF"). */
    const val ON_WHITE = "#101318"

    /** The hairline under a classic (design-less) launcher's white plate, as the React Native classic launcher draws it. */
    const val CLASSIC_HAIRLINE = "#E5E7EB"

    /**
     * The side of the square a logo is fitted into, in the shape's own units,
     * inside a shape `size` across (a pill: its height, drawn as a circle).
     * 80% for `rounded-square` and `square`; 70% for the rest — the largest
     * square a circle holds is 70.7% of it, and a squircle is treated as one.
     * Rounded down, never up: a box rounded up pokes its corners past the circle.
     */
    @JvmStatic
    fun box(shape: String?, size: Double): Int {
        val k = if (shape == "rounded-square" || shape == "square") 0.8 else 0.7
        return maxOf(1, Math.floor(size * k).toInt())
    }

    /** An outline: a width in the shape's units and a #RRGGBB colour. */
    data class Stroke(val width: Double, val color: String)

    /** The fill under a logo, and its outline — null for none. */
    data class Plate(val fill: String, val outline: Stroke?)

    /**
     * What a plate looks like: `WHITE` is #FFFFFF outlined `max(1, borderWidth)`
     * in `border`; `ACCENT` is the accent, no outline. Callers pass `ACCENT`
     * whenever the mark is not the logo — a glyph or a letter is always on the accent.
     */
    @JvmStatic
    @JvmOverloads
    fun plate(plate: LogoPlate, accent: String, border: String, borderWidth: Double = 1.0): Plate =
        if (plate == LogoPlate.WHITE) Plate(PLATE_WHITE, Stroke(maxOf(1.0, borderWidth), border))
        else Plate(accent, null)
}

/** The colours a component is drawn in, as #RRGGBB. */
data class WidgetPalette(
    val surface: String,
    val border: String,
    val text: String,
    val muted: String,
    /** Readable ON the accent — chosen by luminance, so a yellow accent still has a label. */
    val onAccent: String,
    /** "light" or "dark", after resolving "auto" against the device. */
    val mode: String,
)

object Appearance {
    const val DEFAULT_ACCENT = "#318CE7"
    const val DEFAULT_ORIGIN = "https://www.graine.ai"
    private const val RADIUS_CEILING = 48.0
    private val HEX = Regex("^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6})$")

    private fun numeric(v: Any?): Double? = when (v) {
        is Number -> v.toDouble().takeIf { !it.isNaN() && !it.isInfinite() }
        is String -> v.trim().takeIf { it.isNotEmpty() && Regex("^[-+]?(\\d+\\.?\\d*|\\.\\d+)([eE][-+]?\\d+)?$").matches(it) }
            ?.toDouble()?.takeIf { !it.isInfinite() }
        else -> null
    }

    private fun clamp(v: Double, max: Double) = if (v < 0) 0.0 else if (v > max) max else v

    /**
     * The dashboard decides, the host's values are the fallback — one rule in
     * one direction. `accent` may arrive without its "#"; `title` is a heading;
     * `logo` is an S3 key that becomes `<origin>/api/embed/logo?k=…`;
     * `logoPlate` is [LogoPlate.from]'s — only "white" is white.
     */
    fun resolve(
        appearance: JSONObject?,
        accent: String,
        heading: String,
        radius: Double? = null,
        maxRadius: Double? = null,
        origin: String? = null,
    ): NativeAppearance {
        val a = appearance ?: JSONObject()
        val max = clamp(maxRadius ?: RADIUS_CEILING, RADIUS_CEILING)
        val rawAccent = Js.strOrEmpty(a.opt("accent")).trim()
        val logoKey = Js.strOrEmpty(a.opt("logo")).trim()
        val base = (origin?.takeIf { it.isNotEmpty() } ?: DEFAULT_ORIGIN).trimEnd('/')
        return NativeAppearance(
            accent = if (HEX.matches(rawAccent)) "#" + rawAccent.removePrefix("#") else accent,
            heading = Js.strOrEmpty(a.opt("title")).trim().ifEmpty { heading },
            logoUrl = if (logoKey.isEmpty()) null else "$base/api/embed/logo?k=${encodeURIComponent(logoKey)}",
            radius = clamp(numeric(a.opt("radius")) ?: radius ?: 18.0, max),
            logoPlate = LogoPlate.from(a.opt("logoPlate")),
        )
    }

    /** Light, dark, or "auto" — which follows the DEVICE, not the dashboard's own default. */
    fun isDark(theme: String?, deviceDark: Boolean?): Boolean = when ((theme ?: "auto").trim().lowercase()) {
        "dark" -> true
        "light" -> false
        else -> deviceDark == true
    }

    fun palette(appearance: JSONObject?, deviceDark: Boolean?, accent: String?): WidgetPalette {
        val dark = isDark(Js.strOrEmpty(appearance?.opt("theme")).ifEmpty { "auto" }, deviceDark)
        val onAccent = if (luminance(accent ?: "") > 0.45) "#101318" else "#FFFFFF"
        return if (dark) WidgetPalette("#17181B", "#2C2E33", "#F5F5F4", "#A0A5AE", onAccent, "dark")
        else WidgetPalette("#FFFFFF", "#E5E4E0", "#101318", "#5F6672", onAccent, "light")
    }

    /** WCAG relative luminance of a #rrggbb colour; 0 for anything else. */
    fun luminance(hex: String): Double {
        val m = Regex("^#?([0-9a-fA-F]{6})$").find(hex.trim()) ?: return 0.0
        val n = m.groupValues[1].toInt(16)
        fun ch(c: Int): Double { val s = c / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((n shr 16) and 255) + 0.7152 * ch((n shr 8) and 255) + 0.0722 * ch(n and 255)
    }

    /** A colour as ARGB: #rgb, #rrggbb or #aarrggbb, with or without the "#". Null for anything else. */
    fun argb(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (!Regex("^([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(h)) return null
        val full = if (h.length == 3) h.map { "$it$it" }.joinToString("") else h
        val v = full.toLong(16)
        return (if (full.length == 6) (0xFF000000L or v) else v).toInt()
    }

    /** A #rrggbb colour at partial strength — badge and selection fills. Slate for anything else. */
    fun tint(hex: String?, alpha: Double): Int {
        val m = Regex("^#?([0-9a-fA-F]{6})$").find((hex ?: "").trim())
        val rgb = m?.groupValues?.get(1)?.toInt(16) ?: ((120 shl 16) or (140 shl 8) or 170)
        val a = Js.round(alpha.coerceIn(0.0, 1.0) * 255).toInt()
        return (a shl 24) or (rgb and 0xFFFFFF)
    }

    /** JavaScript's encodeURIComponent: everything but A–Z a–z 0–9 - _ . ! ~ * ' ( ) is escaped, as UTF-8. */
    fun encodeURIComponent(s: String): String {
        val keep = "-_.!~*'()"
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            if (c < 0x80 && (ch.isLetterOrDigit() || keep.indexOf(ch) >= 0)) sb.append(ch)
            else sb.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 15])
        }
        return sb.toString()
    }
}

// ── design: the look the brand chose, as the session carries it ────────────

/*
 * THE SERVER RESOLVES, THIS SDK DRAWS.
 *
 * The brand chooses a design in the dashboard — a philosophy and a few knobs.
 * The session carries it already resolved, as two complete sets of tokens,
 * light and dark (contract/DESIGN.md). Nothing here knows what a philosophy
 * is: it parses the tokens, picks the scheme, builds the glyph's address, and
 * the views draw what they are told. A philosophy added next month reaches
 * this build without a release.
 *
 * Nothing from the wire is trusted. Unknown keys are ignored; a value that is
 * missing or the wrong shape takes Graine Classic's (the look every Graine
 * surface had before designs, and the default for everything); a `design`
 * that is not one at all is null, and the appearance then draws everything,
 * exactly as it did before designs existed. Parsing never throws.
 *
 * Strings rather than enums, as for WidgetStyle: these are the wire's own
 * words, and a host drawing its own UI compares against the same words the
 * dashboard and every other SDK use.
 */

/** Every colour a surface is drawn in, as "#RRGGBB". What each one paints is in contract/DESIGN.md. */
data class ThemeColors(
    val background: String,
    val surface: String,
    val surfaceRaised: String,
    val text: String,
    val textMuted: String,
    val border: String,
    val accent: String,
    val onAccent: String,
    /** The accent at low strength: chips, selected rows, the focus halo. */
    val accentSoft: String,
    val success: String,
    val warning: String,
    val danger: String,
    val userBubble: String,
    val userBubbleText: String,
    val agentBubble: String,
    val agentBubbleText: String,
    val scrim: String,
)

/** Type sizes, in sp. */
data class FontScale(val caption: Double, val body: Double, val title: Double, val display: Double)

/** CSS weights, 100–900. */
data class FontWeights(val body: Int, val strong: Int, val title: Int)

/** One number for body text and one for titles: letter spacing in em, or line height as a multiplier. */
data class FontPair(val body: Double, val title: Double)

data class ThemeFont(
    /** "system" means the platform's own UI font; anything else is a family the app may bundle. */
    val body: String,
    val heading: String,
    val scale: FontScale,
    val weights: FontWeights,
    val letterSpacing: FontPair,
    val lineHeight: FontPair,
)

/** Corner radii, dp. */
data class ThemeRadius(val sm: Double, val md: Double, val lg: Double, val xl: Double)

data class ThemeShape(
    val radius: ThemeRadius,
    /** The width of every outline, dp. */
    val borderWidth: Double,
    /** "circle", "squircle", "rounded-square", "square" or "pill". */
    val launcher: String,
)

/** dp. */
data class ThemeSpacing(val unit: Double, val cardPadding: Double, val gap: Double)

/** A soft shadow. `opacity` (0–1) applies to `color`. */
data class ThemeShadow(val y: Double, val blur: Double, val spread: Double, val opacity: Double, val color: String)

/** A solid offset shadow, drawn in `colors.text`. dp. */
data class ThemeOffset(val x: Double, val y: Double)

data class ThemeElevation(
    /** Null: no shadow. */
    val card: ThemeShadow?,
    val floating: ThemeShadow?,
    /** Null unless the design draws depth as a hard offset instead of a shadow. */
    val hardOffset: ThemeOffset?,
)

data class ThemeMotion(
    /** 0 means no transition at all. The phone's own "remove animations" wins over any number here. */
    val durationMs: Int,
    /** "standard", "emphasized", "spring", "linear" or "none". */
    val easing: String,
    /** 0–1, for "spring". */
    val bounce: Double,
)

/**
 * A launcher glyph: 24×24 SVG path data, stroked or filled. The voice bar draws
 * it from its path; the launcher shows it as an image ([Design.glyphUrl]).
 */
data class LauncherGlyph(val id: String, val path: String, val stroke: Boolean)

data class ThemeIcons(
    /** "outline", "filled", "duotone" or "sharp". */
    val style: String,
    val stroke: Double,
    /** Null: the launcher shows the brand's logo. */
    val launcher: LauncherGlyph?,
)

/** Translucent surfaces: `surface` at `surfaceOpacity`, what is behind blurred by `blur` where the platform can. */
data class ThemeGlass(val blur: Double, val surfaceOpacity: Double)

/** Everything a renderer needs for one scheme, as flat numbers and "#RRGGBB" strings. */
data class ThemeTokens @JvmOverloads constructor(
    val v: Int,
    val preset: String,
    /** "light" or "dark". */
    val scheme: String,
    val colors: ThemeColors,
    val font: ThemeFont,
    val shape: ThemeShape,
    val spacing: ThemeSpacing,
    val elevation: ThemeElevation,
    val motion: ThemeMotion,
    val icons: ThemeIcons,
    /** Null: opaque surfaces. */
    val glass: ThemeGlass?,
    /** "filled", "outline" or "minimal". */
    val bubbles: String,
    /**
     * "none" or "window": with "window" the chat window, the caption card and
     * every component card open as desktop windows — a title bar with traffic
     * lights and a monospaced title (contract/DESIGN.md → Window chrome).
     * "none", a plain surface, when the server says nothing (an older one).
     */
    val chrome: String = "none",
    /**
     * The chat window's resolved values (contract §7), as the server sent
     * them; null from a server that predates them — [Surfaces.chatOf] then
     * derives them from [colors].
     */
    val chat: ChatTokens? = null,
    /** The call screen's resolved values (contract §7); null from an older server — [Surfaces.callOf] derives them. */
    val call: CallTokens? = null,
) {
    val isDark: Boolean get() = scheme == "dark"

    /** The tokens as the session spelled them — for a log line, or a host that forwards them. */
    fun toJson(): JSONObject = JSONObject()
        .put("v", v).put("preset", preset).put("scheme", scheme)
        .put("colors", JSONObject().apply {
            val c = colors
            listOf(
                "background" to c.background, "surface" to c.surface, "surfaceRaised" to c.surfaceRaised,
                "text" to c.text, "textMuted" to c.textMuted, "border" to c.border, "accent" to c.accent,
                "onAccent" to c.onAccent, "accentSoft" to c.accentSoft, "success" to c.success,
                "warning" to c.warning, "danger" to c.danger, "userBubble" to c.userBubble,
                "userBubbleText" to c.userBubbleText, "agentBubble" to c.agentBubble,
                "agentBubbleText" to c.agentBubbleText, "scrim" to c.scrim,
            ).forEach { (k, x) -> put(k, x) }
        })
        .put("font", JSONObject()
            .put("body", font.body).put("heading", font.heading)
            .put("scale", JSONObject().put("caption", Design.n(font.scale.caption)).put("body", Design.n(font.scale.body))
                .put("title", Design.n(font.scale.title)).put("display", Design.n(font.scale.display)))
            .put("weights", JSONObject().put("body", font.weights.body).put("strong", font.weights.strong).put("title", font.weights.title))
            .put("letterSpacing", JSONObject().put("body", Design.n(font.letterSpacing.body)).put("title", Design.n(font.letterSpacing.title)))
            .put("lineHeight", JSONObject().put("body", Design.n(font.lineHeight.body)).put("title", Design.n(font.lineHeight.title))))
        .put("shape", JSONObject()
            .put("radius", JSONObject().put("sm", Design.n(shape.radius.sm)).put("md", Design.n(shape.radius.md))
                .put("lg", Design.n(shape.radius.lg)).put("xl", Design.n(shape.radius.xl)))
            .put("borderWidth", Design.n(shape.borderWidth)).put("launcher", shape.launcher))
        .put("spacing", JSONObject().put("unit", Design.n(spacing.unit)).put("cardPadding", Design.n(spacing.cardPadding)).put("gap", Design.n(spacing.gap)))
        .put("elevation", JSONObject()
            .put("card", Design.shadowJson(elevation.card))
            .put("floating", Design.shadowJson(elevation.floating))
            .put("hardOffset", elevation.hardOffset?.let { JSONObject().put("x", Design.n(it.x)).put("y", Design.n(it.y)) } ?: JSONObject.NULL))
        .put("motion", JSONObject().put("durationMs", motion.durationMs).put("easing", motion.easing).put("bounce", Design.n(motion.bounce)))
        .put("icons", JSONObject().put("style", icons.style).put("stroke", Design.n(icons.stroke))
            .put("launcher", icons.launcher?.let { JSONObject().put("id", it.id).put("path", it.path).put("stroke", it.stroke) } ?: JSONObject.NULL))
        .put("glass", glass?.let { JSONObject().put("blur", Design.n(it.blur)).put("surfaceOpacity", Design.n(it.surfaceOpacity)) } ?: JSONObject.NULL)
        .put("bubbles", bubbles)
        .put("chrome", chrome)
        // Only what the server sent: a design from before these tokens writes back without them.
        .apply { chat?.let { put("chat", Surfaces.json(it)) } }
        .apply { call?.let { put("call", Surfaces.json(it)) } }
}

/**
 * The knobs the brand turned, as the session carries them beside the tokens.
 * Only [scheme] changes what this SDK draws (which token set); the rest are
 * already inside the tokens and are kept for a host that wants to show them.
 */
data class DesignConfig @JvmOverloads constructor(
    val preset: String = Design.DEFAULT_PRESET,
    val accent: String? = null,
    /** "light", "dark" or "auto". Absent means "auto". */
    val scheme: String? = null,
    val roundness: Double? = null,
    val font: String? = null,
    val headingFont: String? = null,
    val density: String? = null,
    val shadow: String? = null,
    val motion: String? = null,
    val neutral: String? = null,
    val glass: Boolean? = null,
    val bubbles: String? = null,
    val launcherIcon: String? = null,
    val launcherShape: String? = null,
    val iconStyle: String? = null,
    /** "none" or "window" when the brand turned the knob; the tokens' [ThemeTokens.chrome] is what is drawn. */
    val chrome: String? = null,
    /** The chat window's knobs as the brand set them (Garage → Chat window); the tokens' [ThemeTokens.chat] is what is drawn. */
    val chat: Map<String, Any?>? = null,
    /** The call screen's knobs as the brand set them (Garage → Call screen); the tokens' [ThemeTokens.call] is what is drawn. */
    val call: Map<String, Any?>? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("preset", preset)
        accent?.let { put("accent", it) }
        scheme?.let { put("scheme", it) }
        roundness?.let { put("roundness", Design.n(it)) }
        font?.let { put("font", it) }
        headingFont?.let { put("headingFont", it) }
        density?.let { put("density", it) }
        shadow?.let { put("shadow", it) }
        motion?.let { put("motion", it) }
        neutral?.let { put("neutral", it) }
        glass?.let { put("glass", it) }
        bubbles?.let { put("bubbles", it) }
        launcherIcon?.let { put("launcherIcon", it) }
        launcherShape?.let { put("launcherShape", it) }
        iconStyle?.let { put("iconStyle", it) }
        chrome?.let { put("chrome", it) }
        chat?.let { put("chat", Protocol.toJson(it)) }
        call?.let { put("call", Protocol.toJson(it)) }
    }
}

/** The session's `design`: the config and both token sets. */
data class ResolvedDesign(val config: DesignConfig, val light: ThemeTokens, val dark: ThemeTokens) {
    /**
     * Which tokens to draw. The brand's fixed scheme when it chose one; under
     * "auto" (or none) the phone's — light when the phone's is unknown.
     * `override` ("light", "dark" or "auto") is a host's own choice, and wins.
     * Pick again whenever the phone switches between light and dark.
     */
    @JvmOverloads
    fun pick(deviceDark: Boolean?, override: String? = null): ThemeTokens {
        val o = override?.trim()?.lowercase()?.takeIf { it == "light" || it == "dark" || it == "auto" }
        return when (o ?: config.scheme?.trim()?.lowercase() ?: "auto") {
            "light" -> light
            "dark" -> dark
            else -> if (deviceDark == true) dark else light
        }
    }

    fun toJson(): JSONObject = JSONObject().put("config", config.toJson()).put("light", light.toJson()).put("dark", dark.toJson())

    companion object {
        /**
         * The session's `design` (a JSONObject, or its JSON text) → a design,
         * or null when it is absent or is not one. A scheme that is missing
         * borrows the other; a value that is missing or malformed takes
         * Graine Classic's. Never throws.
         */
        @JvmStatic
        fun parse(raw: Any?): ResolvedDesign? = runCatching {
            val o = when (raw) {
                is JSONObject -> raw
                is String -> raw.trim().takeIf { it.startsWith("{") }?.let { JSONObject(it) }
                else -> null
            } ?: return null
            val light = Design.tokens(o.opt("light") as? JSONObject, "light")
            val dark = Design.tokens(o.opt("dark") as? JSONObject, "dark")
            if (light == null && dark == null) return null
            ResolvedDesign(
                config = Design.config(o.opt("config") as? JSONObject),
                light = light ?: dark!!.copy(scheme = "light"),
                dark = dark ?: light!!.copy(scheme = "dark"),
            )
        }.getOrNull()
    }
}

/** The design's rules that are arithmetic: parsing, the launcher's shape, the glyph's address, motion. */
object Design {
    const val DEFAULT_PRESET = "graine-classic"

    /**
     * The glyph asset version — theme.ts's GLYPH_ASSET_VERSION. The image is
     * cached for a year under its URL, so a changed drawing gets a new number.
     */
    const val GLYPH_ASSET_VERSION = 2

    /** The launcher is this many dp high (and wide, unless it is a pill). */
    const val LAUNCHER_SIZE = 56.0

    /** Its glyph or logo, as a share of its height. */
    const val LAUNCHER_GLYPH = 0.46

    val LAUNCHER_SHAPES = listOf("circle", "squircle", "rounded-square", "square", "pill")
    val BUBBLES = listOf("filled", "outline", "minimal")
    val EASINGS = listOf("standard", "emphasized", "spring", "linear", "none")
    val ICON_STYLES = listOf("outline", "filled", "duotone", "sharp")
    /** How a surface that holds content is framed: plain, or as a desktop window. */
    val CHROMES = listOf("none", "window")

    private val HEX6 = Regex("^#?([0-9a-fA-F]{6})$")
    private val HEX3 = Regex("^#?([0-9a-fA-F]{3})$")

    /** "#RRGGBB" for anything that is a hex colour ("#abc", "AABBCC", …), else null — theme.ts's normaliseHex. */
    @JvmStatic
    fun normaliseHex(value: Any?): String? {
        val s = Js.strOrEmpty(value).trim()
        HEX6.find(s)?.let { return "#" + it.groupValues[1].uppercase() }
        HEX3.find(s)?.let { m -> return "#" + m.groupValues[1].map { "$it$it" }.joinToString("").uppercase() }
        return null
    }

    /**
     * Where an app fetches a launcher glyph as a PNG — built exactly as
     * theme.ts's glyphUrl(): the id lower-cased, the colour upper-case hex
     * without "#", the size clamped to 16–512 PHYSICAL pixels (dp × density,
     * so it is sharp on every screen), the stroke clamped to 0.5–4 in quarter
     * steps, and the asset version.
     */
    @JvmStatic
    @JvmOverloads
    fun glyphUrl(origin: String?, id: String?, color: String?, sizePx: Double, stroke: Double = 2.0): String {
        val gid = (id ?: "").trim().lowercase()
        val hex = normaliseHex(color) ?: "#FFFFFF"
        // `Number(x) || fallback`: zero and NaN are "not given".
        val size = Js.round(maxOf(16.0, minOf(512.0, if (sizePx == 0.0 || sizePx.isNaN()) 96.0 else sizePx))).toInt()
        val w = maxOf(0.5, minOf(4.0, if (stroke == 0.0 || stroke.isNaN()) 2.0 else stroke))
        val base = (origin?.takeIf { it.isNotEmpty() } ?: Appearance.DEFAULT_ORIGIN).trimEnd('/')
        return "$base/api/embed/glyph?id=${Appearance.encodeURIComponent(gid)}&c=${hex.substring(1)}&s=$size" +
            "&w=${Js.number(Js.round(w * 4) / 4)}&v=$GLYPH_ASSET_VERSION"
    }

    /**
     * The launcher's corner radius at a height (dp): `circle` and `pill` fully
     * round, `squircle` 30% of the size (the continuous corner iOS draws, as a
     * plain radius), `rounded-square` the design's large radius, `square` its
     * small one — never more than half the height.
     */
    @JvmStatic
    fun launcherCorner(shape: String?, height: Double, radius: ThemeRadius): Double = when (shape) {
        "squircle" -> height * 0.30
        "rounded-square" -> minOf(radius.lg, height / 2)
        "square" -> minOf(radius.sm, height / 2)
        else -> height / 2
    }

    /**
     * How long a transition runs, ms: the design's duration, or 0 — none —
     * when the design says none, when there is no design (the look before
     * designs had no transitions), or when the phone has animations off
     * (`animatorScale` is the system's animator duration scale; 0 is "Remove
     * animations"). ViewPropertyAnimator applies the scale itself otherwise.
     */
    @JvmStatic
    fun motionMs(motion: ThemeMotion?, animatorScale: Float): Long {
        if (motion == null || !(animatorScale > 0f) || motion.durationMs <= 0 || motion.easing == "none") return 0
        return motion.durationMs.toLong()
    }

    /**
     * A shadow token as Android can draw it: an elevation in dp (the offset,
     * Material's own reading of depth) and the alpha of the shadow colour.
     * Android multiplies that alpha by the theme's spot-shadow alpha (0.19 by
     * default), so the token's opacity is divided by it to land where the
     * design meant. Null draws no shadow.
     */
    @JvmStatic
    fun androidShadow(s: ThemeShadow?): Pair<Double, Double> {
        if (s == null || s.opacity <= 0.0) return 0.0 to 0.0
        return maxOf(0.0, s.y) to minOf(1.0, s.opacity / 0.19)
    }

    /** The family to ask the app for, or null for the platform's own font. */
    @JvmStatic
    fun fontFamily(name: String?): String? = name?.trim()?.takeIf { it.isNotEmpty() && !it.equals("system", ignoreCase = true) }

    /** The app's font for a design family, matched without regard to case; null when the app has none. */
    @JvmStatic
    fun <T> fontFor(fonts: Map<String, T>, family: String?): T? {
        val want = fontFamily(family) ?: return null
        return fonts[want] ?: fonts.entries.firstOrNull { it.key.trim().equals(want, ignoreCase = true) }?.value
    }

    // ── parsing ────────────────────────────────────────────────────────────

    /** A number as JSON writes it: no ".0" on an integer. */
    internal fun n(d: Double): Any = if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong() else d

    internal fun shadowJson(s: ThemeShadow?): Any = s?.let {
        JSONObject().put("y", n(it.y)).put("blur", n(it.blur)).put("spread", n(it.spread)).put("opacity", n(it.opacity)).put("color", it.color)
    } ?: JSONObject.NULL

    private fun num(o: JSONObject?, k: String, fallback: Double, min: Double, max: Double): Double =
        Js.finite(o?.opt(k))?.coerceIn(min, max) ?: fallback

    private fun hex(o: JSONObject?, k: String, fallback: String): String = normaliseHex((o?.opt(k) as? String)) ?: fallback

    private fun word(o: JSONObject?, k: String, allowed: List<String>, fallback: String): String =
        (o?.opt(k) as? String)?.trim()?.lowercase()?.takeIf { it in allowed } ?: fallback

    private fun text(o: JSONObject?, k: String, fallback: String): String =
        (o?.opt(k) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback

    private fun obj(o: JSONObject?, k: String): JSONObject? = o?.opt(k) as? JSONObject

    /** Absent keeps the default; JSON null is "none"; an object is read over the default; anything else keeps it. */
    private fun <T> nullable(o: JSONObject?, k: String, fallback: T?, read: (JSONObject) -> T?): T? {
        if (o == null || !o.has(k)) return fallback
        return when (val v = o.opt(k)) {
            JSONObject.NULL -> null
            is JSONObject -> read(v)
            else -> fallback
        }
    }

    private val NO_SHADOW = ThemeShadow(0.0, 0.0, 0.0, 0.0, "#000000")

    private fun shadow(o: JSONObject, d: ThemeShadow?): ThemeShadow {
        val b = d ?: NO_SHADOW
        return ThemeShadow(
            y = num(o, "y", b.y, -200.0, 200.0),
            blur = num(o, "blur", b.blur, 0.0, 400.0),
            spread = num(o, "spread", b.spread, -200.0, 200.0),
            opacity = num(o, "opacity", b.opacity, 0.0, 1.0),
            color = hex(o, "color", b.color),
        )
    }

    internal fun config(o: JSONObject?): DesignConfig {
        fun s(k: String) = (o?.opt(k) as? String)
        return DesignConfig(
            preset = text(o, "preset", DEFAULT_PRESET),
            accent = s("accent"),
            scheme = s("scheme"),
            roundness = Js.finite(o?.opt("roundness")),
            font = s("font"),
            headingFont = s("headingFont"),
            density = s("density"),
            shadow = s("shadow"),
            motion = s("motion"),
            neutral = s("neutral"),
            glass = o?.opt("glass") as? Boolean,
            bubbles = s("bubbles"),
            launcherIcon = s("launcherIcon"),
            launcherShape = s("launcherShape"),
            iconStyle = s("iconStyle"),
            chrome = s("chrome"),
            chat = knobs(o?.opt("chat")),
            call = knobs(o?.opt("call")),
        )
    }

    /** A group of knobs, as plain values (strings, numbers, lists, maps). */
    @Suppress("UNCHECKED_CAST")
    private fun knobs(raw: Any?): Map<String, Any?>? = (raw as? JSONObject)?.let { Js.plain(it) as Map<String, Any?> }

    /** One scheme's tokens, or null when there is nothing there to read (no `colors` at all). */
    internal fun tokens(o: JSONObject?, scheme: String): ThemeTokens? {
        val c = obj(o, "colors") ?: return null
        val d = if (scheme == "dark") CLASSIC_DARK else CLASSIC_LIGHT
        val dc = d.colors
        val font = obj(o, "font")
        val scale = obj(font, "scale")
        val weights = obj(font, "weights")
        val spacingO = obj(font, "letterSpacing")
        val lineO = obj(font, "lineHeight")
        val shape = obj(o, "shape")
        val radius = obj(shape, "radius")
        val spacing = obj(o, "spacing")
        val elevation = obj(o, "elevation")
        val motion = obj(o, "motion")
        val icons = obj(o, "icons")
        fun weight(k: String, fallback: Int) = Js.finite(weights?.opt(k))?.let { Js.round(it).coerceIn(100.0, 900.0).toInt() } ?: fallback
        val colors = ThemeColors(
            background = hex(c, "background", dc.background),
            surface = hex(c, "surface", dc.surface),
            surfaceRaised = hex(c, "surfaceRaised", dc.surfaceRaised),
            text = hex(c, "text", dc.text),
            textMuted = hex(c, "textMuted", dc.textMuted),
            border = hex(c, "border", dc.border),
            accent = hex(c, "accent", dc.accent),
            onAccent = hex(c, "onAccent", dc.onAccent),
            accentSoft = hex(c, "accentSoft", dc.accentSoft),
            success = hex(c, "success", dc.success),
            warning = hex(c, "warning", dc.warning),
            danger = hex(c, "danger", dc.danger),
            userBubble = hex(c, "userBubble", dc.userBubble),
            userBubbleText = hex(c, "userBubbleText", dc.userBubbleText),
            agentBubble = hex(c, "agentBubble", dc.agentBubble),
            agentBubbleText = hex(c, "agentBubbleText", dc.agentBubbleText),
            scrim = hex(c, "scrim", dc.scrim),
        )
        val fontWeights = FontWeights(weight("body", d.font.weights.body), weight("strong", d.font.weights.strong), weight("title", d.font.weights.title))
        return ThemeTokens(
            v = Js.finite(o?.opt("v"))?.toInt() ?: 1,
            preset = text(o, "preset", d.preset),
            scheme = word(o, "scheme", listOf("light", "dark"), scheme),
            colors = colors,
            font = ThemeFont(
                body = text(font, "body", d.font.body),
                heading = text(font, "heading", d.font.heading),
                scale = FontScale(
                    caption = num(scale, "caption", d.font.scale.caption, 6.0, 96.0),
                    body = num(scale, "body", d.font.scale.body, 6.0, 96.0),
                    title = num(scale, "title", d.font.scale.title, 6.0, 96.0),
                    display = num(scale, "display", d.font.scale.display, 6.0, 128.0),
                ),
                weights = fontWeights,
                letterSpacing = FontPair(
                    num(spacingO, "body", d.font.letterSpacing.body, -0.5, 1.0),
                    num(spacingO, "title", d.font.letterSpacing.title, -0.5, 1.0),
                ),
                lineHeight = FontPair(
                    num(lineO, "body", d.font.lineHeight.body, 0.5, 4.0),
                    num(lineO, "title", d.font.lineHeight.title, 0.5, 4.0),
                ),
            ),
            shape = ThemeShape(
                radius = ThemeRadius(
                    sm = num(radius, "sm", d.shape.radius.sm, 0.0, 999.0),
                    md = num(radius, "md", d.shape.radius.md, 0.0, 999.0),
                    lg = num(radius, "lg", d.shape.radius.lg, 0.0, 999.0),
                    xl = num(radius, "xl", d.shape.radius.xl, 0.0, 999.0),
                ),
                borderWidth = num(shape, "borderWidth", d.shape.borderWidth, 0.0, 16.0),
                launcher = word(shape, "launcher", LAUNCHER_SHAPES, d.shape.launcher),
            ),
            spacing = ThemeSpacing(
                unit = num(spacing, "unit", d.spacing.unit, 0.0, 64.0),
                cardPadding = num(spacing, "cardPadding", d.spacing.cardPadding, 0.0, 200.0),
                gap = num(spacing, "gap", d.spacing.gap, 0.0, 200.0),
            ),
            elevation = ThemeElevation(
                card = nullable(elevation, "card", d.elevation.card) { shadow(it, d.elevation.card) },
                floating = nullable(elevation, "floating", d.elevation.floating) { shadow(it, d.elevation.floating) },
                hardOffset = nullable(elevation, "hardOffset", d.elevation.hardOffset) {
                    ThemeOffset(num(it, "x", 4.0, -64.0, 64.0), num(it, "y", 4.0, -64.0, 64.0))
                },
            ),
            motion = ThemeMotion(
                durationMs = Js.finite(motion?.opt("durationMs"))?.let { Js.round(it).coerceIn(0.0, 5000.0).toInt() } ?: d.motion.durationMs,
                easing = word(motion, "easing", EASINGS, d.motion.easing),
                bounce = num(motion, "bounce", d.motion.bounce, 0.0, 1.0),
            ),
            icons = ThemeIcons(
                style = word(icons, "style", ICON_STYLES, d.icons.style),
                stroke = num(icons, "stroke", d.icons.stroke, 0.5, 4.0),
                launcher = nullable(icons, "launcher", d.icons.launcher) { g ->
                    val id = (g.opt("id") as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    if (id == null) d.icons.launcher
                    else LauncherGlyph(id, (g.opt("path") as? String) ?: "", (g.opt("stroke") as? Boolean) ?: true)
                },
            ),
            glass = nullable(o, "glass", null) { g ->
                ThemeGlass(num(g, "blur", 20.0, 0.0, 200.0), num(g, "surfaceOpacity", 0.72, 0.0, 1.0))
            },
            bubbles = word(o, "bubbles", BUBBLES, d.bubbles),
            // Absent (an older server) or a word this build does not know: a plain surface.
            chrome = word(o, "chrome", CHROMES, d.chrome),
            chat = Surfaces.chat(o?.opt("chat"), colors),
            call = Surfaces.call(o?.opt("call"), colors, fontWeights),
        )
    }

    // ── Graine Classic: the default for every value the wire leaves out ─────

    private val CLASSIC_FONT = ThemeFont(
        body = "system", heading = "system",
        scale = FontScale(12.0, 15.0, 17.0, 22.0),
        weights = FontWeights(400, 600, 600),
        letterSpacing = FontPair(0.0, -0.01),
        lineHeight = FontPair(1.45, 1.25),
    )
    private val CLASSIC_SHAPE = ThemeShape(ThemeRadius(8.0, 12.0, 18.0, 24.0), 1.0, "circle")
    private val CLASSIC_SPACING = ThemeSpacing(4.0, 14.0, 10.0)
    private val CLASSIC_FLOATING = ThemeShadow(8.0, 24.0, 0.0, 0.16, "#000000")
    private val CLASSIC_MOTION = ThemeMotion(200, "standard", 0.0)
    private val CLASSIC_ICONS = ThemeIcons("outline", 2.0, LauncherGlyph("waveform", "M2 13a2 2 0 0 0 2 -2V7a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0V4a2 2 0 0 1 4 0v13a2 2 0 0 0 4 0v-4a2 2 0 0 1 2 -2", true))

    /** Graine Classic in light, as the server resolves it. */
    @JvmField
    val CLASSIC_LIGHT = ThemeTokens(
        v = 1, preset = DEFAULT_PRESET, scheme = "light",
        colors = ThemeColors(
            background = "#F7F7F5", surface = "#FFFFFF", surfaceRaised = "#FFFFFF", text = "#101318", textMuted = "#5F6672",
            border = "#E5E4E0", accent = "#318CE7", onAccent = "#FFFFFF", accentSoft = "#E6F1FC", success = "#16A34A",
            warning = "#B45309", danger = "#DC2626", userBubble = "#318CE7", userBubbleText = "#FFFFFF",
            agentBubble = "#F1F1EF", agentBubbleText = "#101318", scrim = "#000000",
        ),
        font = CLASSIC_FONT, shape = CLASSIC_SHAPE, spacing = CLASSIC_SPACING,
        elevation = ThemeElevation(ThemeShadow(2.0, 8.0, 0.0, 0.08, "#000000"), CLASSIC_FLOATING, null),
        motion = CLASSIC_MOTION, icons = CLASSIC_ICONS, glass = null, bubbles = "filled", chrome = "none",
        chat = Surfaces.CLASSIC_CHAT_LIGHT, call = Surfaces.CLASSIC_CALL,
    )

    /** Graine Classic in dark, as the server resolves it. */
    @JvmField
    val CLASSIC_DARK = ThemeTokens(
        v = 1, preset = DEFAULT_PRESET, scheme = "dark",
        colors = ThemeColors(
            background = "#0E0F11", surface = "#17181B", surfaceRaised = "#1F2024", text = "#F5F5F4", textMuted = "#A0A5AE",
            border = "#2C2E33", accent = "#318CE7", onAccent = "#FFFFFF", accentSoft = "#1D3248", success = "#22C55E",
            warning = "#F59E0B", danger = "#F87171", userBubble = "#318CE7", userBubbleText = "#FFFFFF",
            agentBubble = "#23252A", agentBubbleText = "#F5F5F4", scrim = "#000000",
        ),
        font = CLASSIC_FONT, shape = CLASSIC_SHAPE, spacing = CLASSIC_SPACING,
        elevation = ThemeElevation(ThemeShadow(2.0, 8.0, 0.0, 0.16, "#000000"), CLASSIC_FLOATING, null),
        motion = CLASSIC_MOTION, icons = CLASSIC_ICONS, glass = null, bubbles = "filled", chrome = "none",
        chat = Surfaces.CLASSIC_CHAT_DARK, call = Surfaces.CLASSIC_CALL,
    )
}
