package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/*
 * THE CALLS HOST, WITHOUT THE PHONE — every rule for driving the native call
 * engine from React Native and Flutter, in plain Kotlin, so the JVM proves it
 * (CallsHostModelTest).
 *
 * Those packages reach the engine through one object, GraineCallsHost, and
 * speak JSON-safe values only: strings, numbers, booleans, null, lists and
 * string-keyed maps. Their own code only marshals values and hops threads. An
 * `if` about calls inside a wrapper is a rule this SDK's tests never see, so
 * every one lives here instead: the options (checked, normalised and kept for
 * a cold start), the screen and an action's answer as JavaScript or Dart
 * describe them, the call's snapshot and events, where each event goes, and
 * what each method does (CallsHostCore). GraineCallsHost is that core with its
 * hands on Android; the iOS SDK holds the same rules in the same shape, so the
 * two platforms answer a wrapper alike.
 */

/**
 * Why the host refused a call from React Native or Flutter: [code] for the
 * platform's error (`invalid_options`) and a sentence for the developer that
 * names what is wrong.
 */
class GraineCallsHostException(val code: String, message: String) : IllegalArgumentException(message) {
    internal companion object {
        fun invalidOptions(message: String) = GraineCallsHostException("invalid_options", message)
    }
}

// ── the options ─────────────────────────────────────────────────────────────

/** Who draws the call while the app is open: the SDK's call screen, or the app's own. */
internal enum class CallsHostCallScreen(val wire: String) { SDK("sdk"), APP("app") }

/**
 * THE OPTIONS a wrapper's `configure` passes, checked and normalised:
 *
 *   publishableKey   string, required, non-empty
 *   displayName      string, required, non-empty once trimmed
 *   baseUrl          string; https://www.graine.ai by default, no trailing "/"
 *   ringtoneSound    string or null: a `res/raw` name
 *   ringTimeoutMs    number; 30000 by default, and for anything not above 0
 *   appVersion       string or null
 *   callScreen       "sdk" (the default) or "app"
 *   callTransport    "auto" (the default), "websocket" or "webrtc": the calls' audio (GraineCallsConfig.callTransport)
 *   debug            bool; false by default: the SDK's debug log (GraineConfig.debug)
 *   android          { smallIcon: string or null }
 *   ios              { iconTemplate: string or null, includesCallsInRecents: bool }
 *   agents           { <name>: { publishableKey: string, baseUrl?: string } }: the
 *                    app's other agents, each with its own key;
 *                    the top-level key is the default agent's. A call is placed
 *                    with one by `startCall({agent: name})`, and a ring Graine
 *                    sends rings with the agent it is for
 *
 * Unknown keys are ignored, so an older SDK reads a newer package's options,
 * and null is the same as absent. A known key of the wrong type is refused,
 * naming it ("ringTimeoutMs must be a number"), rather than quietly becoming
 * its default: a ring that gives up at 30 s when the app asked for 45 is a bug
 * nobody finds. `ios` is iOS's, but it is checked here too, so one set of
 * options is valid on both platforms or on neither.
 */
internal data class CallsHostOptions(
    val publishableKey: String,
    val displayName: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val ringtoneSound: String? = null,
    val ringTimeoutMs: Long = DEFAULT_RING_TIMEOUT_MS,
    val appVersion: String? = null,
    val callScreen: CallsHostCallScreen = CallsHostCallScreen.SDK,
    val callTransport: GraineCallTransport = GraineCallTransport.AUTO,
    /** Read when the SDK instance is made, like the key and the base URL. */
    val debug: Boolean = false,
    val androidSmallIcon: String? = null,
    val iosIconTemplate: String? = null,
    val iosIncludesCallsInRecents: Boolean = false,
    /** The app's other agents by name, in the order given; never the default one. */
    val agents: Map<String, CallsHostAgent> = emptyMap(),
) {
    /** What is kept for a cold start: every key, normalised, so that `parse(json)` gives these options back. */
    val json: Map<String, Any?>
        get() {
            val out = LinkedHashMap<String, Any?>()
            out["publishableKey"] = publishableKey
            out["displayName"] = displayName
            out["baseUrl"] = baseUrl
            out["ringTimeoutMs"] = ringTimeoutMs
            out["callScreen"] = callScreen.wire
            if (callTransport != GraineCallTransport.AUTO) out["callTransport"] = callTransport.name.lowercase()
            out["debug"] = debug
            ringtoneSound?.let { out["ringtoneSound"] = it }
            appVersion?.let { out["appVersion"] = it }
            androidSmallIcon?.let { out["android"] = linkedMapOf<String, Any?>("smallIcon" to it) }
            val ios = linkedMapOf<String, Any?>("includesCallsInRecents" to iosIncludesCallsInRecents)
            iosIconTemplate?.let { ios["iconTemplate"] = it }
            out["ios"] = ios
            if (agents.isNotEmpty()) {
                out["agents"] = LinkedHashMap<String, Any?>().also { all ->
                    agents.forEach { (name, a) -> all[name] = linkedMapOf<String, Any?>("publishableKey" to a.publishableKey, "baseUrl" to a.baseUrl) }
                }
            }
            return out
        }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.graine.ai"
        const val DEFAULT_RING_TIMEOUT_MS = 30_000L

        /** Throws [GraineCallsHostException] (`invalid_options`), naming the option that is wrong. */
        fun parse(raw: Any?): CallsHostOptions {
            val map = if (CallsHostValues.isNull(raw)) emptyMap()
                else CallsHostValues.map(raw) ?: throw GraineCallsHostException.invalidOptions("options must be an object")
            val key = string(map, "publishableKey")
            if (key.isNullOrEmpty()) throw GraineCallsHostException.invalidOptions("publishableKey is required")
            val name = string(map, "displayName")?.let(::nonBlank)
                ?: throw GraineCallsHostException.invalidOptions("displayName is required")
            var baseUrl = DEFAULT_BASE_URL
            string(map, "baseUrl")?.let(::nonBlank)?.trimEnd('/')?.let { if (it.isNotEmpty()) baseUrl = it }
            var ringTimeoutMs = DEFAULT_RING_TIMEOUT_MS
            number(map, "ringTimeoutMs")?.let { ms ->
                // Bounded before it becomes a whole number, and a fraction of a millisecond rounds up.
                if (!ms.isNaN() && !ms.isInfinite() && ms > 0) ringTimeoutMs = ceil(minOf(ms, Int.MAX_VALUE.toDouble())).toLong()
            }
            var callScreen = CallsHostCallScreen.SDK
            string(map, "callScreen")?.let { word ->
                callScreen = CallsHostCallScreen.values().firstOrNull { it.wire == word }
                    ?: throw GraineCallsHostException.invalidOptions("callScreen must be \"sdk\" or \"app\"")
            }
            var callTransport = GraineCallTransport.AUTO
            string(map, "callTransport")?.let { word ->
                // Exact words, as iOS reads them: one set of options is valid on both platforms or on neither.
                callTransport = GraineCallTransport.values().firstOrNull { it.name.lowercase() == word }
                    ?: throw GraineCallsHostException.invalidOptions("callTransport must be \"auto\", \"websocket\" or \"webrtc\"")
            }
            val android = obj(map, "android")
            val ios = obj(map, "ios")
            val agents = LinkedHashMap<String, CallsHostAgent>()
            obj(map, "agents")?.forEach { (raw, value) ->
                val name = raw.trim()
                if (name.isEmpty()) throw GraineCallsHostException.invalidOptions("agents: an agent's name must not be empty")
                if (name == GraineInApp.DEFAULT) {
                    throw GraineCallsHostException.invalidOptions("agents.${GraineInApp.DEFAULT}: the default agent is the top-level publishableKey")
                }
                val agent = (if (CallsHostValues.isNull(value)) null else CallsHostValues.map(value))
                    ?: throw GraineCallsHostException.invalidOptions("agents.$name must be an object")
                val agentKey = string(agent, "publishableKey", "agents.$name.publishableKey")?.trim()
                if (agentKey.isNullOrEmpty()) throw GraineCallsHostException.invalidOptions("agents.$name.publishableKey is required")
                val agentUrl = string(agent, "baseUrl", "agents.$name.baseUrl")?.let(::nonBlank)?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: baseUrl
                agents[name] = CallsHostAgent(agentKey, agentUrl)
            }
            return CallsHostOptions(
                publishableKey = key,
                displayName = name,
                baseUrl = baseUrl,
                ringtoneSound = string(map, "ringtoneSound")?.let(::nonBlank),
                ringTimeoutMs = ringTimeoutMs,
                appVersion = string(map, "appVersion")?.let(::nonBlank),
                callScreen = callScreen,
                callTransport = callTransport,
                debug = bool(map, "debug", "debug") ?: false,
                androidSmallIcon = android?.let { string(it, "smallIcon", "android.smallIcon") }?.let(::nonBlank),
                iosIconTemplate = ios?.let { string(it, "iconTemplate", "ios.iconTemplate") }?.let(::nonBlank),
                iosIncludesCallsInRecents = ios?.let { bool(it, "includesCallsInRecents", "ios.includesCallsInRecents") } ?: false,
                agents = agents,
            )
        }

        /** The value under [key], or null when it is absent or null. */
        private fun value(map: Map<String, Any?>, key: String): Any? = map[key]?.takeUnless { CallsHostValues.isNull(it) }

        private fun string(map: Map<String, Any?>, key: String, named: String = key): String? {
            val raw = value(map, key) ?: return null
            return raw as? String ?: throw GraineCallsHostException.invalidOptions("$named must be a string")
        }

        private fun number(map: Map<String, Any?>, key: String): Double? {
            val raw = value(map, key) ?: return null
            return (raw as? Number)?.toDouble() ?: throw GraineCallsHostException.invalidOptions("$key must be a number")
        }

        private fun bool(map: Map<String, Any?>, key: String, named: String): Boolean? {
            val raw = value(map, key) ?: return null
            return raw as? Boolean ?: throw GraineCallsHostException.invalidOptions("$named must be a boolean")
        }

        private fun obj(map: Map<String, Any?>, key: String): Map<String, Any?>? {
            val raw = value(map, key) ?: return null
            return CallsHostValues.map(raw) ?: throw GraineCallsHostException.invalidOptions("$key must be an object")
        }

        private fun nonBlank(text: String): String? = text.trim().ifEmpty { null }
    }
}

/** One of the app's other agents, as the options name it: its own key, and where its Graine is. */
internal data class CallsHostAgent(val publishableKey: String, val baseUrl: String)

// ── values from a wrapper ───────────────────────────────────────────────────

/** JSON-safe values from JavaScript or Dart, read the lenient way the rest of the SDK reads JSON. */
@Suppress("UNCHECKED_CAST") // plain() and normalize() keep a map a map and a list a list.
internal object CallsHostValues {
    fun isNull(raw: Any?): Boolean = raw == null || raw == JSONObject.NULL

    /** A call permission by its word: `microphone`, `notifications` or `fullScreen` (`full_screen` and any case too); null for anything else. */
    fun permission(raw: Any?): GraineCallPermission? = GraineCallPermission.of(raw as? String)

    /** A string-keyed map, or null. A key that is not a string is dropped: the values are JSON. */
    fun map(raw: Any?): Map<String, Any?>? = when (raw) {
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> raw.forEach { (k, v) -> if (k is String) out[k] = v } }
        is JSONObject -> plain(raw) as Map<String, Any?>
        else -> null
    }

    /** A list, or null — from Kotlin, from a bridge, or from org.json. */
    private fun list(raw: Any?): List<Any?>? = when (raw) {
        is List<*> -> raw
        is Array<*> -> raw.toList()
        is JSONArray -> plain(raw) as List<Any?>
        else -> null
    }

    /** A list of names: strings only, never empty, each once, in the order given. */
    fun names(raw: Any?): List<String> {
        val out = LinkedHashSet<String>()
        list(raw)?.forEach { if (it is String && it.isNotEmpty()) out.add(it) }
        return out.toList()
    }

    /**
     * THE SCREEN, as the app's JavaScript or Dart describes it: `{screen,
     * title?, journey?: {name, step?, of?, next?}, fields?: [{name, label?,
     * value?, status?, error?, optional?}], error?, data?, availableActions?}`.
     *
     * No screen name, no screen. A field's value is text whatever it was sent
     * as, a number written the way JavaScript writes it (1299 is "1299", never
     * "1299.0": the agent reads it out). A status this SDK does not know is no
     * status, never a guess. `availableActions` keeps its two meanings apart:
     * absent allows every action, an empty list allows none.
     */
    fun screen(raw: Any?): GraineScreen? {
        val o = map(raw) ?: return null
        val name = text(o["screen"])?.trim() ?: return null
        val journey = map(o["journey"])?.let { j ->
            text(j["name"])?.let { GraineScreen.Journey(it, integer(j["step"]), integer(j["of"]), text(j["next"])) }
        }
        return GraineScreen(
            screen = name,
            title = text(o["title"]),
            journey = journey,
            fields = list(o["fields"])?.mapNotNull { field(it) } ?: emptyList(),
            error = text(o["error"]),
            data = map(o["data"])?.let { normalize(it) as Map<String, Any?> } ?: emptyMap(),
            availableActions = list(o["availableActions"])?.let { names(it) },
        )
    }

    private fun field(raw: Any?): GraineField? {
        val o = map(raw) ?: return null
        val name = text(o["name"]) ?: return null
        return GraineField(
            name = name,
            label = text(o["label"]),
            value = jsonText(o["value"]),
            status = jsonText(o["status"])?.let { s -> GraineField.Status.values().firstOrNull { it.wire == s } },
            error = text(o["error"]),
            optional = flag(o["optional"]),
        )
    }

    /**
     * AN ACTION'S ANSWER: `{status, message?, data?}`. A status other than
     * `ok`, `refused` or `error` is an error: the agent must never be told that
     * something happened when nobody said it did.
     */
    fun actionResult(raw: Any?): ActionResult {
        val o = map(raw) ?: return ActionResult.error("The app answered without a result.")
        val message = jsonText(o["message"]) ?: ""
        val data = map(o["data"])?.let { normalize(it) as Map<String, Any?> } ?: emptyMap()
        val word = jsonText(o["status"])
        val status = ActionResult.Status.values().firstOrNull { it.wire == word }
        if (status == null) {
            val why = if (word != null) "The app answered with an unknown status \"$word\"." else "The app answered without a status."
            return ActionResult(ActionResult.Status.ERROR, message.ifEmpty { why }, data)
        }
        return ActionResult(status, message, data)
    }

    /** `startCall`'s options: `{reason?, variables?, agent?}`. */
    fun callRequest(raw: Any?): Pair<String?, Map<String, Any?>> {
        val o = map(raw) ?: emptyMap()
        return text(o["reason"])?.trim() to (map(o["variables"]) ?: emptyMap())
    }

    /** The agent `startCall`'s options name (`agent`, GraineInApp.init's name): the default one unless named. */
    fun callAgent(raw: Any?): String =
        (map(raw)?.get("agent") as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: GraineInApp.DEFAULT

    /**
     * A push's data, as the messaging service hands it over: every value a
     * string. A wrapper stringifies what is not one; whatever still arrives
     * otherwise is written as JSON would write it, and null is left out.
     */
    fun pushData(raw: Any?): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        map(raw)?.forEach { (k, v) ->
            when {
                isNull(v) -> Unit
                v is String -> out[k] = v
                else -> out[k] = jsonText(v) ?: Protocol.toJson(normalize(v)).toString()
            }
        }
        return out
    }

    /**
     * A `res/raw` name from `ringtoneSound`. The option is shared with iOS,
     * where it is a file in the app's bundle, so a folder and an extension are
     * dropped: "sounds/ring.caf" is the raw resource "ring". A resource name
     * can never hold either.
     */
    fun rawResourceName(sound: String?): String? {
        val file = sound?.trim()?.substringAfterLast('/') ?: return null
        val name = if (file.contains('.')) file.substringBeforeLast('.') else file
        return name.ifEmpty { null }
    }

    /** A scalar as text; null when it is absent, null, blank or not a scalar. */
    private fun text(raw: Any?): String? = jsonText(raw)?.takeIf { it.isNotBlank() }

    /** A scalar as JavaScript's String() writes it; null for null, objects and arrays. */
    fun jsonText(raw: Any?): String? = when (raw) {
        is String -> raw
        is Boolean -> raw.toString()
        is Number -> numberText(raw)
        else -> null
    }

    /**
     * A number as text, the way JavaScript writes it: a whole number has no
     * ".0" (it is substituted into a sentence — "your cart of 1299.0" is a bug
     * a customer hears), and a fraction is its digits.
     */
    fun numberText(n: Number): String = when (n) {
        is Int, is Long, is Short, is Byte -> n.toString()
        else -> {
            val d = n.toDouble()
            if (!d.isNaN() && !d.isInfinite() && d == floor(d) && abs(d) < 1e15) d.toLong().toString() else d.toString()
        }
    }

    /** true for `true`, a non-zero number or the string "true". Everything else is false. */
    private fun flag(raw: Any?): Boolean = when (raw) {
        is Boolean -> raw
        is Number -> raw.toDouble() != 0.0
        is String -> raw.lowercase() == "true"
        else -> false
    }

    /** A whole number from a number or a numeric string; null for anything else, or anything out of range. */
    private fun integer(raw: Any?): Int? = when (raw) {
        is Number -> raw.toDouble().takeIf { !it.isNaN() && !it.isInfinite() && abs(it) <= Int.MAX_VALUE }?.let {
            // Half away from zero, as Swift rounds: the iOS SDK reads the same step from the same number.
            (if (it >= 0) floor(it + 0.5) else -floor(-it + 0.5)).toInt()
        }
        is String -> raw.trim().toIntOrNull()
        else -> null
    }

    /**
     * Values → JSON-safe values, recursively: what crosses a bridge. A number
     * that is not finite is null (JSON has no NaN); a map's keys become text;
     * anything unknown becomes its text.
     */
    fun normalize(value: Any?): Any? = when (value) {
        null -> null
        is String, is Boolean -> value
        is Number -> {
            val d = value.toDouble()
            if (d.isNaN() || d.isInfinite()) null else value
        }
        is JSONObject, is JSONArray -> normalize(plain(value))
        is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> value.forEach { (k, v) -> out[k.toString()] = normalize(v) } }
        is Iterable<*> -> value.map { normalize(it) }
        is Array<*> -> value.map { normalize(it) }
        else -> if (value == JSONObject.NULL) null else value.toString()
    }

    /** org.json values → maps, lists and scalars; JSONObject.NULL → null. */
    fun plain(value: Any?): Any? = when (value) {
        is JSONObject -> LinkedHashMap<String, Any?>().also { out -> value.keys().forEach { k -> out[k] = plain(value.opt(k)) } }
        is JSONArray -> ArrayList<Any?>().also { out -> for (i in 0 until value.length()) out.add(plain(value.opt(i))) }
        else -> if (isNull(value)) null else value
    }
}

// ── the snapshot ────────────────────────────────────────────────────────────

/**
 * THE CALL AS A CALL SCREEN DRAWS IT: the `state` event and `snapshot()`.
 *
 * Every key is always there (null, never missing), so JavaScript and Dart
 * never read `undefined`. At idle nothing of a call shows except `error`: why
 * the call that just ended could not connect stays until the next one rings or
 * is placed, so a screen can still say so once the call screen is gone.
 */
internal data class CallsHostSnapshot(
    val state: GraineCallState = GraineCallState.IDLE,
    val callId: String? = null,
    val direction: GraineCall.Direction? = null,
    /** The push's reason, or startCall's. */
    val reason: String? = null,
    val callerName: String? = null,
    val muted: Boolean = false,
    val speakerOn: Boolean = false,
    val agentSpeaking: Boolean = false,
    val caption: Caption? = null,
    /** When the conversation opened, in ms since the Unix epoch, as JavaScript's Date and Dart's DateTime take it. */
    val connectedAt: Long? = null,
    /** Why the last call failed; cleared when the next call starts. */
    val error: String? = null,
    /**
     * The name of the agent the call is with (GraineInApp.init's name;
     * "default" for the default agent): the one it was placed with, or the one
     * its ring was routed to by the push's `agent_id`.
     */
    val agent: String? = null,
    /** The road the call's audio runs on: "webrtc" or "websocket", once decided. */
    val transport: String? = null,
    /** Why a call that tried WebRTC runs on the WebSocket (`no_answer`, `ice_timeout`, …). */
    val transportFallbackReason: String? = null,
) {
    data class Caption(val role: Role, val text: String, val live: Boolean)

    /** The idle rule applied and blanks made null: what is shown, and what is compared. */
    val settled: CallsHostSnapshot
        get() = if (state == GraineCallState.IDLE) CallsHostSnapshot(error = present(error))
            else copy(callId = present(callId), reason = present(reason), callerName = present(callerName), error = present(error),
                agent = present(agent), transport = present(transport), transportFallbackReason = present(transportFallbackReason))

    val json: Map<String, Any?>
        get() {
            val s = settled
            return linkedMapOf(
                "state" to word(s.state),
                "callId" to s.callId,
                "direction" to s.direction?.name?.lowercase(),
                "reason" to s.reason,
                "callerName" to s.callerName,
                "muted" to s.muted,
                "speakerOn" to s.speakerOn,
                "agentSpeaking" to s.agentSpeaking,
                "caption" to s.caption?.let { linkedMapOf<String, Any?>("role" to it.role.name.lowercase(), "text" to it.text, "live" to it.live) },
                "connectedAt" to s.connectedAt,
                "error" to s.error,
                "agent" to s.agent,
                "transport" to s.transport,
                "transportFallbackReason" to s.transportFallbackReason,
            )
        }

    companion object {
        fun word(state: GraineCallState): String = state.name.lowercase()

        private fun present(text: String?): String? = text?.ifEmpty { null }
    }
}

/**
 * WHEN THE CALL CONNECTED, ON THE WALL CLOCK. GraineCalls keeps the moment on
 * the phone's elapsed-time clock, which no JavaScript or Dart can read, so it
 * is turned into ms since the epoch — once per call: worked out again on every
 * snapshot, it would move by a millisecond now and then, and every move would
 * be a `state` event for nothing.
 */
internal class CallsHostConnectedAt {
    private var clockMs = 0L
    private var epochMs: Long? = null

    /** [atClockMs] is GraineCalls.connectedAt (0 before the call connects); the clocks are read now. */
    @Synchronized
    fun epochMs(atClockMs: Long, nowClockMs: Long, nowEpochMs: Long): Long? {
        if (atClockMs <= 0L) {
            clockMs = 0L
            epochMs = null
            return null
        }
        if (atClockMs != clockMs) {
            clockMs = atClockMs
            epochMs = nowEpochMs - (nowClockMs - atClockMs)
        }
        return epochMs
    }
}

/**
 * ONE `state` EVENT PER TURN OF THE MAIN LOOP, and only when something a call
 * screen draws has changed. The engine reports one thing at a time — a call
 * that ends moves through ended to idle and drops its controls in a row — and
 * a JavaScript screen re-rendered four times for one change is three renders
 * of nothing. Starts at idle, so an idle engine says nothing at all.
 */
internal class CallsHostStateGate {
    var last = CallsHostSnapshot()
        private set
    var scheduled = false
        private set

    /** Something may have changed. True when a turn must be scheduled to look (none is pending). */
    fun changed(): Boolean {
        if (scheduled) return false
        scheduled = true
        return true
    }

    /** The scheduled turn: the snapshot to send, or null when nothing a screen draws changed. */
    fun flush(current: CallsHostSnapshot): CallsHostSnapshot? {
        scheduled = false
        val next = current.settled
        if (next == last) return null
        last = next
        return next
    }
}

// ── events ──────────────────────────────────────────────────────────────────

/**
 * THE EVENTS, by name, with their payloads — the same names and shapes on
 * iOS. React Native renames them (`GraineCallState`, `GraineCallOutcome`,
 * `GraineIncomingCall`, `GraineVoIPToken`, `GraineActionRequest`); Flutter
 * streams `{"event": name, "data": payload}`.
 */
internal object CallsHostEvent {
    const val STATE = "state"
    const val OUTCOME = "outcome"
    const val INCOMING = "incoming"
    const val VOIP_TOKEN = "voipToken"
    const val ACTION = "action"

    /**
     * `{callId, outcome, reason?, error?, durationSeconds?, hosted?, agent?}`: `reason` (local, remote, failed)
     * only with `ended`, `error` only with `failed`, `durationSeconds` with an `ended` call whose length the
     * engine measured, `hosted: true` for a call Graine rang (its outcomes reach Graine too), and `agent` — the
     * name of the agent the call was with — when it is known. The keys React Native's and Flutter's
     * GraineCallOutcome read.
     */
    fun outcomePayload(outcome: GraineCallOutcome, callId: String, agent: String? = null): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>("callId" to callId, "outcome" to outcome.wire)
        if (outcome.kind == GraineCallOutcome.Kind.ENDED) outcome.endReason?.let { out["reason"] = it.wire }
        if (outcome.kind == GraineCallOutcome.Kind.FAILED) outcome.error?.let { out["error"] = it }
        if (outcome.kind == GraineCallOutcome.Kind.ENDED) outcome.durationSeconds?.let { out["durationSeconds"] = it.coerceAtLeast(0) }
        if (outcome.hosted) out["hosted"] = true
        agent?.let { out["agent"] = it }
        return out
    }

    /**
     * `{callId, outcome: "ringing", hosted: true, agent?}`: a call Graine rang is ringing on this device — what
     * the engine tells Graine as it starts to ring, told to the app as well.
     */
    fun ringingPayload(callId: String, agent: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("callId" to callId, "outcome" to "ringing", "hosted" to true)
            .also { out -> agent?.let { out["agent"] = it } }

    /** `{callId, reason, callerName, hosted?, agent?}`: as the push said them, `hosted: true` when Graine rang it, and the agent the ring was routed to. */
    fun incomingPayload(call: GraineCall, agent: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("callId" to call.callId, "reason" to call.reason, "callerName" to call.callerName)
            .also { out ->
                if (call.hosted) out["hosted"] = true
                agent?.let { out["agent"] = it }
            }

    /** `{requestId, name, arguments, agent?}`: `agent` is the agent whose call asked. */
    fun actionPayload(requestId: String, name: String, arguments: Map<String, Any?>, agent: String? = null): Map<String, Any?> =
        linkedMapOf<String, Any?>("requestId" to requestId, "name" to name, "arguments" to CallsHostValues.normalize(arguments))
            .also { out -> agent?.let { out["agent"] = it } }
}

/** Receives one event: its name and its payload. Main thread. */
internal typealias CallsHostSink = (event: String, payload: Map<String, Any?>) -> Unit

/**
 * WHERE EVENTS GO. React Native adds one sink; Flutter adds one per engine (its
 * main isolate, and a background messaging isolate). Every event reaches every
 * sink, except an action request, which goes only to the sink that last listed
 * a non-empty set of actions: one handler runs an action once, never once per
 * engine. Outcomes and rings raised while no sink is attached — a call that
 * rang, was answered and ended before any JavaScript ran — wait, the last 20,
 * for the first sink to arrive. The state never waits: a new sink reads the
 * snapshot, which is newer than anything that could have waited.
 */
internal class CallsHostSinks {
    private val sinks = ArrayList<Pair<Int, CallsHostSink>>()
    private var nextToken = 1
    private val waiting = ArrayList<Pair<String, Map<String, Any?>>>()

    /** The sink that last listed a non-empty set of actions, attached or not. */
    var actionSink: Int? = null
        private set

    val count: Int get() = sinks.size

    /** What waits for the first sink, oldest first. */
    val queued: List<Pair<String, Map<String, Any?>>> get() = waiting.toList()

    /** Add a sink. The first one also receives everything that waited, in order. */
    fun add(sink: CallsHostSink): Int {
        val token = nextToken++
        sinks.add(token to sink)
        if (waiting.isNotEmpty()) {
            val held = waiting.toList()
            waiting.clear()
            held.forEach { (event, payload) -> sink(event, payload) }
        }
        return token
    }

    fun remove(token: Int) {
        sinks.removeAll { it.first == token }
    }

    fun contains(token: Int?): Boolean = token != null && sinks.any { it.first == token }

    /** A list of actions arrived from [by]. Only a non-empty one makes its sender the one that answers. */
    fun listedActions(names: List<String>, by: Int?) {
        if (names.isNotEmpty()) actionSink = by
    }

    /** Everything but an action request: to every sink, or (an outcome or a ring) to the queue. */
    fun emit(event: String, payload: Map<String, Any?>) {
        if (sinks.isEmpty()) {
            if (event != CallsHostEvent.OUTCOME && event != CallsHostEvent.INCOMING) return
            waiting.add(event to payload)
            while (waiting.size > QUEUE_LIMIT) waiting.removeAt(0)
            return
        }
        sinks.toList().forEach { (_, sink) -> sink(event, payload) }
    }

    /** An action request, to the action sink only. False when it is gone: the request must be refused at once. */
    fun emitAction(payload: Map<String, Any?>): Boolean {
        val token = actionSink ?: return false
        val sink = sinks.firstOrNull { it.first == token }?.second ?: return false
        sink(CallsHostEvent.ACTION, payload)
        return true
    }

    companion object {
        const val QUEUE_LIMIT = 20
    }
}

// ── variables ───────────────────────────────────────────────────────────────

/**
 * A WRAPPER'S setVariables REPLACES. The app's JavaScript or Dart holds the
 * whole map and sends all of it every time, while the SDK's setVariables
 * merges. So a key the last map set and this one leaves out is sent as a
 * removal, or a variable the app dropped would reach every call after it. A
 * value that cannot be a variable (null, a list, an object) is a removal too:
 * the variables become exactly what this map can say.
 */
internal class CallsHostVariables {
    var keys: Set<String> = emptySet()
        private set

    /** The change for GraineInApp.setVariables: this map's values, and null under every key to remove. */
    fun replace(values: Map<String, Any?>?): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        val kept = LinkedHashSet<String>()
        values?.forEach { (key, value) ->
            if (key.isEmpty()) return@forEach
            // The variables' own rule decides what can be one, so the two never disagree.
            if (VariableStore.flatten(mapOf(key to value)).isNotEmpty()) {
                out[key] = value
                kept.add(key)
            } else {
                out[key] = null
            }
        }
        for (key in keys) if (key !in kept) out[key] = null
        keys = kept
        return out
    }
}

// ── actions ─────────────────────────────────────────────────────────────────

/**
 * THE ACTIONS A WRAPPER ANSWERS, and the requests waiting on it.
 *
 * Each request has an id; the first answer to it wins, and a late, repeated or
 * unknown one is ignored. By then the SDK has already answered the agent (its
 * own six-second deadline), and a second answer must never reach it.
 */
internal class CallsHostActions {
    private class Waiting(val sink: Int?, val answer: (ActionResult) -> Unit)

    private val waiting = LinkedHashMap<String, Waiting>()

    /** The names listed last, in order. */
    var names: List<String> = emptyList()
        private set

    val pendingCount: Int get() = waiting.size

    /** A new list: what to register, and what to unregister. */
    fun list(next: List<String>): Pair<List<String>, List<String>> {
        val added = next.filter { it !in names }
        val removed = names.filter { it !in next }
        names = next
        return added to removed
    }

    /** A request sent to [sink]. Returns its id. */
    fun begin(sink: Int?, answer: (ActionResult) -> Unit): String {
        val id = UUID.randomUUID().toString()
        waiting[id] = Waiting(sink, answer)
        return id
    }

    /** The answer to one request. False when it is unknown or already answered. */
    fun resolve(id: String, result: ActionResult): Boolean {
        val entry = waiting.remove(id) ?: return false
        entry.answer(result)
        return true
    }

    /** The sink a request went to is gone: nobody is left to answer it. */
    fun refuse(sink: Int, message: String) {
        waiting.filterValues { it.sink == sink }.keys.toList().forEach { resolve(it, ActionResult.refused(message)) }
    }
}

// ── kept for a cold start ───────────────────────────────────────────────────

/**
 * THE OPTIONS, KEPT FOR A COLD START. A push can wake an app that is not
 * running, and the call must ring before any JavaScript or Dart has run. So
 * `configure` keeps the options under one key (SharedPreferences
 * `ai.graine.inapp.calls`, key `host` → `{"options": {…}, "voip": bool}`, the
 * shape iOS keeps), and `restore()` reads them. `voip` is iOS's and stays as
 * iOS left it.
 */
internal class CallsHostStore(private val read: () -> String?, private val write: (String) -> Unit) {
    fun load(): Pair<Map<String, Any?>?, Boolean> {
        val saved = read()?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null to false
        return CallsHostValues.map(saved.opt("options")) to (saved.opt("voip") == true)
    }

    fun saveOptions(options: Map<String, Any?>) {
        val out = JSONObject().put("options", Protocol.toJson(options)).put("voip", load().second)
        write(out.toString())
    }
}

// ── the host's rules ────────────────────────────────────────────────────────

/** What the SDK instance really runs with, fixed for the life of the process: the app may have made it first. */
internal data class CallsHostInstance(val publishableKey: String, val baseUrl: String, val debug: Boolean)

/** What the host acts on: the SDK instance, its calls and the app around them. GraineCallsHost is the Android one; the tests drive a fake. */
internal interface CallsHostPlatform {
    /** Make the SDK instance (one per process) and say which key, base URL and debug setting it really runs with. */
    fun makeInstance(options: CallsHostOptions): CallsHostInstance
    fun configureCalls(options: CallsHostOptions)
    /** The customer calls the agent from [activity] — null when there is none in the foreground. The call's id, or null. */
    fun startCall(activity: Any?, reason: String?, variables: Map<String, Any?>): String?
    /** startCall(), with the app's agent [agent] (GraineInApp.init's name). */
    fun startCall(activity: Any?, reason: String?, variables: Map<String, Any?>, agent: String): String? =
        if (agent == GraineInApp.DEFAULT) startCall(activity, reason, variables) else null
    /** Make the app's other agents the options name — those not made already. */
    fun makeAgents(agents: Map<String, CallsHostAgent>, options: CallsHostOptions) {}
    fun answer()
    fun decline()
    fun end()
    fun setMuted(muted: Boolean)
    fun setSpeaker(on: Boolean)
    fun identify(traits: Map<String, Any?>?)
    fun setVariables(values: Map<String, Any?>)
    fun setScreen(screen: GraineScreen?)
    fun registerAction(name: String, handler: (JSONObject, (ActionResult) -> Unit) -> Unit): () -> Unit
    /** A push's data, to the calls; false when it is not a call. */
    fun handlePush(data: Map<String, String>): Boolean
    /**
     * The name of the agent the call [callId] is with — the one it was placed
     * with, or the one its ring was routed to — or null when nobody knows.
     */
    fun agentOf(callId: String): String? = null
    /** The engine's half of the snapshot: everything but `error`, which is the host's own. */
    fun callFields(): CallsHostSnapshot
    /** Run [block] on the next turn of the main loop. */
    fun nextTurn(block: () -> Unit)
    /** Run [block] on the main loop after [ms]. */
    fun after(ms: Long, block: () -> Unit)
    fun log(message: String)
}

/**
 * WHAT EACH OF THE HOST'S METHODS DOES. Main thread only: GraineCallsHost hops
 * there first.
 *
 * The first `configure` makes the SDK instance from the key, base URL, app
 * version and debug setting; later ones update the calls' configuration, and a
 * different key, base URL or debug setting is kept for the next launch (the
 * instance is one per process).
 * `restore()` applies the kept options, without keeping them again; a push
 * handed over before anything was configured restores them first. Identity,
 * variables, the screen and actions sent before there is an instance are held
 * and applied when it is made. The snapshot's `error` (why the last call could
 * not connect) is tracked here; the rest is the engine's.
 */
internal class CallsHostCore(private val platform: CallsHostPlatform, private val store: CallsHostStore) {
    val sinks = CallsHostSinks()
    val actions = CallsHostActions()
    private val variables = CallsHostVariables()
    private val gate = CallsHostStateGate()

    var options: CallsHostOptions? = null
        private set
    private var instance: CallsHostInstance? = null
    var error: String? = null
        private set
    private var lastState = GraineCallState.IDLE
    private val registered = HashMap<String, () -> Unit>()

    /** Something a wrapper set before there was an instance to set it on; the value may itself be null (cleared). */
    private class Held<T>(val value: T)
    private var heldIdentity: Held<Map<String, Any?>?>? = null
    private var heldVariables: Held<Map<String, Any?>?>? = null
    private var heldScreen: Held<GraineScreen?>? = null

    /** The app draws the call itself (`callScreen: "app"`). */
    val appDrawsTheCall: Boolean get() = options?.callScreen == CallsHostCallScreen.APP

    // ── setting up ──

    /** Throws [GraineCallsHostException] for options that are not valid. */
    fun configure(raw: Any?) {
        val next = CallsHostOptions.parse(raw)
        store.saveOptions(next.json)
        apply(next)
    }

    /** The kept options, applied. False when there were none, or when this launch is already configured. */
    fun restore(): Boolean {
        if (options != null) return false
        val raw = store.load().first ?: return false
        val kept = try {
            CallsHostOptions.parse(raw)
        } catch (e: GraineCallsHostException) {
            platform.log("The kept call options could not be read (${e.message}); calls wait for configure().")
            return false
        }
        apply(kept)
        return true
    }

    private fun apply(next: CallsHostOptions) {
        if (instance == null) {
            val made = platform.makeInstance(next)
            instance = made.copy(baseUrl = made.baseUrl.trimEnd('/'))
            releaseHeld()
        }
        val running = instance
        if (running != null && (running.publishableKey != next.publishableKey || running.baseUrl != next.baseUrl)) {
            platform.log("The calls run with the publishableKey and baseUrl this launch started with; the new ones are kept and take effect at the next launch.")
        }
        if (running != null && running.debug != next.debug) {
            platform.log("The calls run with the debug setting this launch started with; the new one is kept and takes effect at the next launch.")
        }
        options = next
        // The app's other agents: made once, each with its own key (a changed key takes effect at the next launch).
        if (next.agents.isNotEmpty()) platform.makeAgents(next.agents, next)
        platform.configureCalls(next)
        changed()
    }

    // ── calls ──

    /** A push's data. The kept options first, so a push that woke the app rings before any JavaScript or Dart. */
    fun handlePush(raw: Any?): Boolean {
        if (options == null) restore()
        return platform.handlePush(CallsHostValues.pushData(raw))
    }

    /** The customer calls the agent. Null when the calls are not configured, a call is live, or none was placed. */
    fun startCall(raw: Any?, activity: Any?): String? {
        if (options == null || platform.callFields().state != GraineCallState.IDLE) return null
        val request = CallsHostValues.callRequest(raw)
        val before = error
        error = null
        val id = platform.startCall(activity, request.first, request.second, CallsHostValues.callAgent(raw))
        if (id == null) {
            error = before
            return null
        }
        changed()
        return id
    }

    fun answer() = platform.answer()

    fun decline() = platform.decline()

    fun end() = platform.end()

    fun setMuted(muted: Boolean) = platform.setMuted(muted)

    fun setSpeaker(on: Boolean) = platform.setSpeaker(on)

    /**
     * The customer would not give the microphone to a call the app's own
     * screen was about to place or answer, so none was: the snapshot says why.
     */
    fun microphoneDenied() {
        error = MICROPHONE_DENIED
        changed()
    }

    // ── who, what and where ──

    fun setIdentity(raw: Any?) {
        val traits = CallsHostValues.map(raw)
        if (instance == null) {
            heldIdentity = Held(traits)
            return
        }
        platform.identify(traits)
    }

    fun setVariables(raw: Any?) {
        val values = CallsHostValues.map(raw)
        if (instance == null) {
            heldVariables = Held(values)
            return
        }
        platform.setVariables(variables.replace(values))
    }

    fun setScreen(raw: Any?) {
        val screen = CallsHostValues.screen(raw)
        if (instance == null) {
            heldScreen = Held(screen)
            return
        }
        platform.setScreen(screen)
    }

    private fun releaseHeld() {
        heldIdentity?.let { platform.identify(it.value) }
        heldVariables?.let { platform.setVariables(variables.replace(it.value)) }
        heldScreen?.let { platform.setScreen(it.value) }
        heldIdentity = null
        heldVariables = null
        heldScreen = null
        actions.names.forEach { register(it) }
    }

    // ── actions ──

    /** The names the wrapper (its sink, [sink]) answers. New names get a deferred handler; names no longer listed lose theirs. */
    fun setActions(raw: Any?, sink: Int?) {
        val (added, removed) = actions.list(CallsHostValues.names(raw))
        sinks.listedActions(actions.names, sink)
        if (instance == null) return
        removed.forEach { registered.remove(it)?.invoke() }
        added.forEach { register(it) }
    }

    private fun register(name: String) {
        registered.remove(name)?.invoke()
        registered[name] = platform.registerAction(name) { arguments, answer -> ask(name, arguments, answer) }
    }

    /** The agent asked for an action: to the sink that answers actions, or refused at once when it is gone. */
    private fun ask(name: String, arguments: JSONObject, answer: (ActionResult) -> Unit) {
        val id = actions.begin(sinks.actionSink, answer)
        // The agent on the call asked: the app runs it in that agent's own handlers.
        val live = platform.callFields()
        val agent = live.agent ?: live.callId?.let { platform.agentOf(it) }
        val payload = CallsHostEvent.actionPayload(id, name, CallsHostValues.map(arguments) ?: emptyMap(), agent)
        if (!sinks.emitAction(payload)) {
            actions.resolve(id, ActionResult.refused("The app is not listening for \"$name\" right now."))
            return
        }
        // Answered here too, harmlessly, so an app that never answers holds nothing.
        platform.after(FORGET_ACTION_AFTER_MS) { actions.resolve(id, ActionResult.error("The app did not answer in time.")) }
    }

    fun resolveAction(requestId: String, result: Any?) {
        actions.resolve(requestId, CallsHostValues.actionResult(result))
    }

    // ── sinks ──

    fun addSink(sink: CallsHostSink): Int = sinks.add(sink)

    fun removeSink(token: Int) {
        sinks.remove(token)
        actions.refuse(token, "The app stopped listening before it answered.")
    }

    // ── what the engine reports ──

    /** A caption, the agent's voice or a control changed; it is read once the turn is over. */
    fun engineChanged() = changed()

    fun callStateChanged(state: GraineCallState) {
        val previous = lastState
        lastState = state
        // A new call — it rings, or it is placed: the last call's error is old news.
        if (state == GraineCallState.RINGING || (previous == GraineCallState.IDLE && state != GraineCallState.IDLE)) error = null
        changed()
    }

    fun incoming(call: GraineCall) {
        error = null
        val agent = platform.agentOf(call.callId)
        sinks.emit(CallsHostEvent.INCOMING, CallsHostEvent.incomingPayload(call, agent))
        // Graine rang it: the engine has told Graine it is ringing, and the app hears the same.
        if (call.hosted) sinks.emit(CallsHostEvent.OUTCOME, CallsHostEvent.ringingPayload(call.callId, agent))
        changed()
    }

    fun outcome(outcome: GraineCallOutcome, callId: String) {
        val why = outcome.error
        // Only a call that never connected leaves an error; one that connected and then ended, however it ended, does not.
        if (outcome.kind == GraineCallOutcome.Kind.FAILED && why != null) {
            // A push refused during a live call is another call's failure, not this one's.
            val live = platform.callFields()
            if (live.state == GraineCallState.IDLE || live.state == GraineCallState.ENDED || live.callId == callId) error = why
        }
        sinks.emit(CallsHostEvent.OUTCOME, CallsHostEvent.outcomePayload(outcome, callId, platform.agentOf(callId)))
        changed()
    }

    // ── the snapshot ──

    fun snapshot(): Map<String, Any?> = current().json

    private fun current(): CallsHostSnapshot = platform.callFields().copy(error = error)

    private fun changed() {
        if (gate.changed()) platform.nextTurn { flush() }
    }

    private fun flush() {
        gate.flush(current())?.let { sinks.emit(CallsHostEvent.STATE, it.json) }
    }

    companion object {
        /** A request the app never answered is forgotten after this: the SDK's own deadline (six seconds) has long since answered the agent. */
        const val FORGET_ACTION_AFTER_MS = 10_000L
        const val MICROPHONE_DENIED = "The microphone permission was denied."
    }
}
