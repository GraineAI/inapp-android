package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject

/**
 * THE WIRE PROTOCOL — the same one the React Native SDK speaks, byte for byte.
 *
 * The call itself runs in a page Graine hosts, inside a WebView this SDK owns.
 * That is deliberate, and it is the whole reason a native app gets every
 * audio-path fix the day it ships: TURN relays, the dead-media watchdog,
 * connection quality, WebSocket fallback — all of it lives in the page, and
 * the page is updated without an app release.
 *
 * What crosses the boundary is small and fixed:
 *
 *   host → page   window.postMessage({type: "graine:…"}, "*")
 *   page → host   window.ReactNativeWebView.postMessage("<json>")
 *
 * The page was written for React Native's WebView, and this SDK installs an
 * object under that same name, so the page needs no idea which platform it is
 * running on. Every message type is listed here once; a type that is not in
 * [PageMessage] is ignored rather than guessed at.
 */
internal object Protocol {

    /** Messages this SDK sends into the page. */
    object Out {
        const val START_CALL = "graine:start-call"
        const val END_CALL = "graine:end-call"
        const val SET_MUTED = "graine:set-muted"
        const val WARM = "graine:warm"
        const val VARIABLES = "graine:variables"
        const val APP_CONTEXT = "graine:app-context"
        const val APP_ACTION_RESULT = "graine:app-action-result"
        const val APP_EVENT = "graine:app-event"
        const val WIDGET_RESULT = "graine:widget-result"
    }

    /** Messages the page sends out. */
    object In {
        const val READY = "graine:ready"
        const val CALL = "graine:call"
        const val MUTED = "graine:muted"
        const val CAPTION = "graine:caption"
        const val WIDGET = "graine:widget"
        const val WIDGET_PENDING = "graine:widget-pending"
        const val APP_ACTION = "graine:app-action"
        const val ENDED = "graine:ended"
        const val MIC_DENIED = "graine:mic-denied"
        const val TRANSPORT = "graine:transport"
        const val QUALITY = "graine:quality"
        const val NETWORK = "graine:network"
        const val RECONNECTING = "graine:reconnecting"
        /** Where a connect is: registering · dialling · retrying · switching · failed (sdk/contract/DESIGN.md "Connecting"). */
        const val PROGRESS = "graine:progress"
    }

    /**
     * The name the page looks for. It was written against React Native's
     * WebView bridge; installing the same name is what lets one page serve
     * every platform.
     */
    const val BRIDGE_NAME = "ReactNativeWebView"

    /** How long an action handler may take before the agent is told it was not done. */
    const val ACTION_DEADLINE_MS = 6_000L

    /**
     * The engine page's URL. Identical to the React Native launcher's, because
     * a difference here is a different agent, a different transport, or a
     * cache that never refreshes.
     *
     * @param voiceAgentId the WEB-CALL agent id. Calling the primary id works,
     *   sounds right, and files every transcript under an agent nobody watches.
     * @param cacheKey the deployment id from the session — so a new deploy is a
     *   new URL, and the same deploy is served from the nearest edge. A
     *   per-launch timestamp here is an origin round trip on every launch.
     * @param idle load the page without registering anything, so a later tap
     *   costs one registration and an INVITE instead of a whole page load.
     */
    fun engineUrl(
        baseUrl: String,
        voiceAgentId: String,
        transport: Transport,
        cacheKey: String,
        idle: Boolean,
        publishableKey: String,
        accent: String?,
    ): String {
        val base = baseUrl.trimEnd('/')
        val sb = StringBuilder()
            .append(base).append("/embed/").append(encode(voiceAgentId))
            .append("?mode=voice&branding=0&embed=1")
            .append("&transport=").append(transport.wire)
            .append("&b=").append(encode(cacheKey))
        if (idle) sb.append("&idle=1")
        sb.append("&pk=").append(encode(publishableKey))
        val hex = accent?.trim()?.removePrefix("#")
        if (!hex.isNullOrEmpty()) sb.append("&accent=").append(encode(hex))
        return sb.toString()
    }

    /**
     * The JavaScript that delivers one message into the page.
     *
     * `true;` at the end because Android's evaluateJavascript hands the
     * statement's value back, and a non-serialisable one is logged as an error
     * on every message.
     */
    fun deliver(message: JSONObject): String = "window.postMessage(${message}, \"*\"); true;"

    fun out(type: String, vararg fields: Pair<String, Any?>): JSONObject {
        val o = JSONObject().put("type", type)
        for ((k, v) in fields) o.put(k, toJson(v))
        return o
    }

    /** Parse one message from the page. Null for anything that is not ours. */
    fun parse(raw: String?): PageMessage? {
        if (raw.isNullOrEmpty()) return null
        val o = try { JSONObject(raw) } catch (_: Exception) { return null }
        return when (o.optString("type")) {
            In.READY -> PageMessage.Ready
            In.CALL -> PageMessage.Call(o.optBoolean("connected"), o.optBoolean("connecting"))
            In.MUTED -> PageMessage.Muted(o.optBoolean("muted"))
            In.CAPTION -> PageMessage.Caption(
                role = if (o.optString("role") == "agent") Role.AGENT else Role.CUSTOMER,
                text = o.optString("text"),
                live = o.optBoolean("live"),
            )
            In.WIDGET -> PageMessage.Widget(o.optJSONObject("widget") ?: JSONObject())
            In.WIDGET_PENDING -> PageMessage.WidgetPending(o.optJSONObject("widget") ?: JSONObject())
            In.APP_ACTION -> {
                val a = o.optJSONObject("action") ?: return null
                PageMessage.AppAction(
                    actionId = a.optString("action_id"),
                    name = a.optString("name"),
                    arguments = a.optJSONObject("arguments") ?: JSONObject(),
                )
            }
            In.ENDED -> PageMessage.Ended
            In.MIC_DENIED -> PageMessage.MicDenied(o.optString("reason", "NotAllowedError"))
            In.TRANSPORT -> PageMessage.TransportChanged(
                transport = Transport.from(o.optString("transport")),
                requested = Transport.from(o.optString("requested")),
                reason = o.optString("reason").ifEmpty { null },
                message = o.optString("message").ifEmpty { null },
            )
            In.QUALITY -> {
                val s = o.optJSONObject("sample") ?: JSONObject()
                PageMessage.QualitySample(
                    rttMs = s.optNumber("rtt"),
                    jitterMs = s.optNumber("jitter"),
                    loss = s.optNumber("loss"),
                )
            }
            In.NETWORK -> PageMessage.Network(o.optBoolean("online"))
            In.RECONNECTING -> PageMessage.Reconnecting(o.optBoolean("active"))
            // Passed on as the engine wrote it: a stage this build does not know is ignored where it is handled.
            In.PROGRESS -> PageMessage.Progress(o.optString("stage"))
            else -> null
        }
    }

    private fun JSONObject.optNumber(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { !it.isNaN() && it >= 0 }

    /** Kotlin values → JSON values, recursively. Anything else becomes its string form. */
    fun toJson(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray, is String, is Boolean, is Int, is Long, is Double, is Float -> v
        is Number -> v.toDouble()
        is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJson(x)) } }
        is Iterable<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        is Array<*> -> JSONArray().also { a -> v.forEach { a.put(toJson(it)) } }
        else -> v.toString()
    }

    private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

/** How the call's audio travels. */
enum class Transport(val wire: String) {
    /** Raw PCM on the conversation socket. Works everywhere; a lossy network sounds lossy. */
    WEBSOCKET("ws"),
    /** Opus over WebRTC with a jitter buffer and the device's echo canceller. Falls back to [WEBSOCKET]. */
    WEBRTC("rtc");

    companion object {
        fun from(s: String?): Transport = if (s == "rtc") WEBRTC else WEBSOCKET
    }
}

enum class Role { AGENT, CUSTOMER }

/**
 * WHERE A CALL BEING PLACED IS, as the engine reports it (`graine:progress`,
 * contract/DESIGN.md "Connecting") — the React Native launcher's `connectStage`.
 *
 *   REGISTERING  a credential and a line are being set up (a cold start)
 *   DIALLING     the call is on its way to the agent
 *   RETRYING     the first attempt went unanswered and a fresh connection is
 *                being made — on mobile data, usually a connection the phone
 *                dropped while it sat idle
 *   SWITCHING    the call is moving to the other transport
 *
 * The engine's fifth word, `failed`, is not a stage but the end of a connect:
 * it is [GraineVoice.callFailed].
 */
enum class ConnectStage(val wire: String) {
    REGISTERING("registering"), DIALLING("dialling"), RETRYING("retrying"), SWITCHING("switching");

    companion object {
        /** Null for anything else — `failed`, and any stage this build does not know. */
        @JvmStatic
        fun from(s: String?): ConnectStage? = values().firstOrNull { it.wire == s }
    }
}

/** Everything the page can say, typed. */
internal sealed class PageMessage {
    object Ready : PageMessage()
    data class Call(val connected: Boolean, val connecting: Boolean) : PageMessage()
    data class Muted(val muted: Boolean) : PageMessage()
    data class Caption(val role: Role, val text: String, val live: Boolean) : PageMessage()
    data class Widget(val widget: JSONObject) : PageMessage()
    data class WidgetPending(val widget: JSONObject) : PageMessage()
    data class AppAction(val actionId: String, val name: String, val arguments: JSONObject) : PageMessage()
    object Ended : PageMessage()
    data class MicDenied(val reason: String) : PageMessage()
    data class TransportChanged(
        val transport: Transport,
        val requested: Transport,
        val reason: String?,
        val message: String?,
    ) : PageMessage()
    data class QualitySample(val rttMs: Double?, val jitterMs: Double?, val loss: Double?) : PageMessage()
    data class Network(val online: Boolean) : PageMessage()
    data class Reconnecting(val active: Boolean) : PageMessage()
    /** `graine:progress` — the stage as the engine wrote it, known or not. */
    data class Progress(val stage: String) : PageMessage()
}

/**
 * The connection, in one word — the way LiveKit reports it.
 *
 * Same thresholds as the React Native SDK and the website (the classic VoIP
 * edges: 150ms / 30ms / 2% leaves "excellent", 400ms / 80ms / 8% is "poor"),
 * so every surface means the same thing by "weak connection".
 */
enum class ConnectionQuality {
    EXCELLENT, GOOD, POOR, LOST, UNKNOWN;

    companion object {
        fun classify(rttMs: Double?, jitterMs: Double?, loss: Double?): ConnectionQuality {
            if (rttMs == null && jitterMs == null && loss == null) return UNKNOWN
            val r = rttMs ?: 0.0
            val j = jitterMs ?: 0.0
            val l = loss ?: 0.0
            // Any one of these alone is enough to ruin a call, so the worst wins.
            if (r >= 400 || j >= 80 || l >= 0.08) return POOR
            if (r >= 150 || j >= 30 || l >= 0.02) return GOOD
            return EXCELLENT
        }
    }
}
