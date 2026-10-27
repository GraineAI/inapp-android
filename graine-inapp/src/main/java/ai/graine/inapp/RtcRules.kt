@file:OptIn(GraineRtcInternal::class)

package ai.graine.inapp

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Calendar
import java.util.TimeZone

/*
 * THE RULES OF A CALL ON WEBRTC — pure, so each is proven on the JVM.
 *
 * Ports of the voice bar's own rules (ui: lib/rtc-ice.ts, lib/rtc-dial.ts,
 * the mint in lib/webcall-rtc.ts and app/api/embed/rtc-session/route.ts), so
 * a phone places its call exactly as the bar does and the server cannot tell
 * them apart. Where the bar's numbers were measured, they are kept as they are.
 */

/**
 * WHEN TO STOP GATHERING AND SEND THE OFFER (rtc-ice.ts). The far side does
 * not trickle, so the offer must carry its candidates; waiting for every
 * interface to finish was measured at forty seconds behind one dead adapter.
 * One relay candidate is the surest path; one server-reflexive candidate is a
 * public path when there is no TURN to wait for; past the cap, go with what
 * there is.
 */
internal object RtcIce {
    /** How long to wait for a public candidate with no TURN configured. */
    const val GATHER_CAP_MS = 1_500L
    /** How long to wait for the relay when a TURN server is configured. */
    const val RELAY_CAP_MS = 2_500L

    enum class Kind { HOST, SRFLX, PRFLX, RELAY, END, UNKNOWN }

    private val TYP = Regex("\\styp\\s+(host|srflx|prflx|relay)\\b")

    /** The `typ` of a candidate line; END for the end of gathering (null or empty). */
    fun kind(line: String?): Kind {
        if (line.isNullOrEmpty()) return Kind.END
        return when (TYP.find(line)?.groupValues?.get(1)) {
            "host" -> Kind.HOST
            "srflx" -> Kind.SRFLX
            "prflx" -> Kind.PRFLX
            "relay" -> Kind.RELAY
            else -> Kind.UNKNOWN
        }
    }

    /** True when gathering should stop now, given the candidate just reported and how long it has run. */
    fun stopNow(kind: Kind, elapsedMs: Long, capMs: Long = GATHER_CAP_MS, wantRelay: Boolean = false): Boolean {
        if (kind == Kind.RELAY) return true
        if (kind == Kind.SRFLX && !wantRelay) return true
        if (kind == Kind.END) return true
        return elapsedMs >= capMs
    }

    /** Does this configuration include a TURN server at all? */
    fun hasTurn(servers: List<GraineRtcIceServer>): Boolean =
        servers.any { s -> s.urls.any { u -> u.trim().let { it.startsWith("turn:", ignoreCase = true) || it.startsWith("turns:", ignoreCase = true) } } }

    fun capFor(wantRelay: Boolean): Long = if (wantRelay) RELAY_CAP_MS else GATHER_CAP_MS

    /**
     * THE WAIT FOR THIS GATHERING, on the link it is on (the bar's gatherCapMs).
     * Without TURN: the public-candidate cap. With TURN and a server-reflexive
     * candidate in hand: the relay cap — UDP works, the relay is a spare. With
     * TURN and no UDP path yet, the relay may be the only path, and on a slow
     * link its allocation takes longer: the cap grows with the link's pace, to
     * twice at most, and drops back the moment a server-reflexive one arrives.
     */
    fun capFor(wantRelay: Boolean, pace: Double, sawSrflx: Boolean): Long {
        if (!wantRelay) return GATHER_CAP_MS
        if (sawSrflx) return RELAY_CAP_MS
        return Math.round(RELAY_CAP_MS * pace.coerceIn(1.0, 2.0))
    }

    /** How a candidate reaches the far side: by kind, a relay by the protocol it reaches its relay with. */
    enum class Via { HOST, SRFLX, PRFLX, RELAY_UDP, RELAY_TCP, RELAY_TLS, OTHER }

    private val PRIORITY = Regex("^(?:a=)?candidate:\\S+\\s+\\d+\\s+\\S+\\s+(\\d+)\\s")

    /**
     * A candidate line read for [Via]. The relay protocol is not written in the
     * line, but the library puts it in the priority's type preference (its top
     * byte: 2 a relay reached over UDP, 1 over TCP, 0 over TLS — libwebrtc's
     * ICE_TYPE_PREFERENCE_RELAY_*). A relay whose priority cannot be read
     * counts as UDP: never evidence that UDP is blocked.
     */
    fun via(line: String?): Via = when (kind(line)) {
        Kind.HOST -> Via.HOST
        Kind.SRFLX -> Via.SRFLX
        Kind.PRFLX -> Via.PRFLX
        Kind.RELAY -> when (PRIORITY.find(line.orEmpty().trim())?.groupValues?.get(1)?.toLongOrNull()?.let { it ushr 24 }) {
            1L -> Via.RELAY_TCP
            0L -> Via.RELAY_TLS
            else -> Via.RELAY_UDP
        }
        else -> Via.OTHER
    }
}

/**
 * THE LINK'S PACE (the bar's networkPace): 1 on a good link, up to [MAX] —
 * from what the call measured: its registration (three handshakes and two
 * round trips; 2 s is a good mobile link's, server time included), the warm
 * socket's probe (about a round trip; 0.8 s), the call's own round trip
 * (250 ms). The largest wins. Nothing measured is a good link: every
 * deadline only ever grows from its base, never shrinks below it. No
 * platform hint is read — none comes without a new permission.
 */
internal object RtcPace {
    const val MAX = 2.5

    fun of(registerMs: Long?, probeMs: Long?, rttMs: Double?): Double {
        var p = 1.0
        if (registerMs != null && registerMs > 0) p = Math.max(p, registerMs / 2_000.0)
        if (probeMs != null && probeMs > 0) p = Math.max(p, probeMs / 800.0)
        if (rttMs != null && rttMs.isFinite() && rttMs > 0) p = Math.max(p, rttMs / 250.0)
        return Math.round(Math.min(MAX, p) * 10) / 10.0
    }

    /** [baseMs] on this link: never shorter, at most [capMs]. */
    fun scaled(baseMs: Long, pace: Double, capMs: Long = Long.MAX_VALUE): Long =
        Math.min(Math.round(baseMs * pace.coerceIn(1.0, MAX)), capMs)
}

/**
 * THE MEDIA PATH, KEPT INSIDE WEBRTC (the bar's lib/rtc-resilience.ts — the
 * same numbers). The WebSocket is the last resort: a path that will not come
 * up, or was lost, is restarted inside the call first — a new gathering in a
 * re-INVITE, never a new call — and nothing is restarted or dialled into no
 * network.
 */
internal object RtcResilience {
    /** Answered, and nothing in flight at all this long after (× pace): restarted early. */
    const val EARLY_RESTART_MS = 2_500L
    /** After a restart is sent, how long the new path has to come up (× pace, at most 15 s); twice that while it moves. */
    const val RESTART_WINDOW_MS = 6_000L
    const val RESTART_WINDOW_CAP_MS = 15_000L
    const val RESTART_HARD_CAP_MS = 24_000L
    /** A setup still making progress is given twice its deadline, at most this. */
    const val SETUP_EXTENDED_CAP_MS = 30_000L
    /** Answered, and no media this long after, whatever the recovery is doing: the WebSocket takes the call. */
    const val SETUP_HARD_CAP_MS = 30_000L
    /** A lost path's first restart, this long after it went (at once on `failed`): ICE heals most blips by itself. */
    const val RESTART_AFTER_MS = 3_000L
    /** How long a lost path may stay lost with a network, restarts included (× pace, at most 30 s), before the call has dropped. */
    const val PATH_LOST_GIVE_UP_MS = 15_000L
    const val PATH_LOST_GIVE_UP_CAP_MS = 30_000L
    /** Lost and offline this long: dropped. Time offline is never counted against the path. */
    const val OFFLINE_HOLD_MS = 30_000L
    /** The network came back: a moment for ICE to heal by itself before a restart. */
    const val ONLINE_SETTLE_MS = 2_000L
    /** A restart that cannot go yet (no network, the socket reconnecting) is tried again this often. */
    const val RESTART_RETRY_MS = 1_000L
    /** How far apart the readings that judge progress are, and how often a moving setup is looked at again. */
    const val PROGRESS_LOOK_MS = 1_000L
    /** Restarts per trouble: the first, and a second only when something changed. */
    const val MAX_RESTARTS = 2
    /** A dial that failed offline waits this long for the network before its redial. */
    const val REDIAL_OFFLINE_WAIT_MS = 15_000L
    /** The whole attempt, however many dials and waits it extends to, from its budget's start. */
    const val ATTEMPT_HARD_CAP_MS = 30_000L

    fun setupDeadlineMs(pace: Double): Long = RtcPace.scaled(RtcDial.ICE_CONNECT_DEADLINE_MS, pace)
    fun setupExtendedCapMs(pace: Double): Long = Math.min(2 * setupDeadlineMs(pace), SETUP_EXTENDED_CAP_MS)
    fun earlyRestartMs(pace: Double): Long = RtcPace.scaled(EARLY_RESTART_MS, pace)
    fun restartWindowMs(pace: Double): Long = RtcPace.scaled(RESTART_WINDOW_MS, pace, RESTART_WINDOW_CAP_MS)
    fun restartExtendedCapMs(pace: Double): Long = Math.min(2 * restartWindowMs(pace), RESTART_HARD_CAP_MS)
    fun pathLostGiveUpMs(pace: Double): Long = RtcPace.scaled(PATH_LOST_GIVE_UP_MS, pace, PATH_LOST_GIVE_UP_CAP_MS)

    /**
     * MAY THIS DIAL RESTART ICE (again)? The first restart always may. A
     * second only when something changed a new gathering can use: the network
     * itself, or UDP now looks blocked and the relays over TCP and TLS were not
     * tried alone yet. A refusal ends restarts on the dial.
     */
    fun restartPermitted(restarts: Int, networkChanged: Boolean, udpBlocked: Boolean, relayOnlyTried: Boolean, refused: Boolean): Boolean {
        if (refused) return false
        if (restarts <= 0) return true
        if (restarts >= MAX_RESTARTS) return false
        return networkChanged || (udpBlocked && !relayOnlyTried)
    }

    /** Nothing in flight: the offer or the answer carried no candidate at all, so no pair can form. Unknown (-1) says nothing. */
    fun nothingInFlight(offerCandidates: Int, answerCandidates: Int): Boolean = offerCandidates == 0 || answerCandidates == 0

    /** A TURN URL that reaches its relay over TCP or TLS: `turns:`, or `turn:` with `transport=tcp`. */
    fun isTcpTlsRelayUrl(url: String): Boolean {
        val u = url.trim().lowercase()
        return u.startsWith("turns:") || (u.startsWith("turn:") && Regex("[?&]transport=tcp\\b").containsMatchIn(u))
    }

    /** The servers cut down to the relays over TCP and TLS, credentials kept; empty when there are none. */
    fun tcpTlsRelays(servers: List<GraineRtcIceServer>): List<GraineRtcIceServer> =
        servers.mapNotNull { s -> s.urls.filter { isTcpTlsRelayUrl(it) }.takeIf { it.isNotEmpty() }?.let { GraineRtcIceServer(it, s.username, s.credential) } }

    /**
     * DOES UDP LOOK BLOCKED? Nothing public came back over UDP — no
     * server-reflexive candidate, no relay reached over UDP — while a relay
     * over TCP or TLS did, or one is configured to try. Not judged before
     * anything was gathered.
     */
    fun udpLooksBlocked(gathered: Gathered, servers: List<GraineRtcIceServer>): Boolean {
        if (gathered.total == 0) return false
        if (gathered.srflx > 0 || gathered.relayUdp > 0) return false
        return gathered.relayTcp + gathered.relayTls > 0 || tcpTlsRelays(servers).isNotEmpty()
    }

    /** What one dial gathered, by [RtcIce.Via]. */
    class Gathered {
        var host = 0; var srflx = 0; var relayUdp = 0; var relayTcp = 0; var relayTls = 0
        val total: Int get() = host + srflx + relayUdp + relayTcp + relayTls

        fun add(line: String) {
            when (RtcIce.via(line)) {
                RtcIce.Via.HOST -> host++
                RtcIce.Via.SRFLX -> srflx++
                RtcIce.Via.RELAY_UDP -> relayUdp++
                RtcIce.Via.RELAY_TCP -> relayTcp++
                RtcIce.Via.RELAY_TLS -> relayTls++
                else -> Unit
            }
        }

        /** A new network: what the last one said about UDP no longer holds. */
        fun forget() { host = 0; srflx = 0; relayUdp = 0; relayTcp = 0; relayTls = 0 }
    }
}

/**
 * THE RULES FOR A DIAL THAT DOES NOT COME BACK (rtc-dial.ts). Measured on
 * production: an INVITE into a socket a carrier NAT had silently dropped sat
 * "connecting" for 32 s and then simply ended. Every deadline scales with the
 * link's own pace — how long its registration took — so a slow network that
 * works is waited for, and a dead one is not.
 */
internal object RtcDial {
    /** The server answered this recently: trust the socket without asking again. */
    const val PROBE_SKIP_MS = 10_000L

    /** How long a re-REGISTER on a warm socket may take before the socket is called dead. */
    fun probeDeadlineMs(registerMs: Long?): Long {
        if (registerMs == null || registerMs <= 0) return 2_500
        return Math.round(registerMs * 1.5).coerceIn(1_500, 4_000)
    }

    /**
     * From the INVITE leaving to any reply to the INVITE in flight — after a challenge, the one with
     * credentials. The server sends 100 Trying the moment it has a request, so silence past this is a
     * dead socket, not an agent slow to pick up.
     */
    fun heardDeadlineMs(registerMs: Long?): Long {
        if (registerMs == null || registerMs <= 0) return 4_000
        return Math.round(registerMs * 2.0).coerceIn(3_000, 8_000)
    }

    /** From the INVITE to the answer. Production answers in 0.2–0.9 s. */
    const val ANSWER_DEADLINE_MS = 15_000L
    /**
     * From the SBC's first provisional reply to the AUTHENTICATED INVITE, to the answer. The SBC
     * replies the moment it has the INVITE, and a healthy call is answered right after: 37 ms median
     * on production, measured at the SBC on 18 Oct 2026. One that has said "trying" and then nothing
     * for half a second is stuck — a feature server that took the call and never answered — so it is
     * cancelled and dialled once more on a fresh connection. A seatbelt, and a short one: 0.10.2's 3 s
     * was 80× a normal answer and only delayed the fallback; this is still ~13× the median, so a
     * healthy call never trips it.
     *
     * The SBC challenges every INVITE (401 or 407) and the INVITE goes again with credentials, so the
     * first reply a dial hears belongs to an INVITE that is about to be refused. Timed from there,
     * this half second also had to hold the challenge, the second INVITE's whole round trip and the
     * server's answer time, and a healthy call on a slow mobile link was dialled twice (20 Oct 2026).
     * So a challenge is not "heard": a clock started before it stops there, and starts again at the
     * first provisional reply to the INVITE with credentials (RtcCallSession, onChallenged).
     */
    const val ANSWER_AFTER_HEARD_MS = 500L

    /**
     * [ANSWER_AFTER_HEARD_MS] on the link the dial is on: exactly 500 ms on a
     * good one, longer only on a link measured to be slow ([RtcPace]) — where
     * one delayed packet between the reply and the answer would otherwise
     * redial a healthy call. 1.25 s at the slowest.
     */
    fun answerAfterHeardMs(pace: Double): Long = RtcPace.scaled(ANSWER_AFTER_HEARD_MS, pace)
    /**
     * How long a dial given up on keeps its socket for its own ending: its CANCEL and the 487, or the
     * ACK and BYE of an answer that crossed the CANCEL, all travel on the socket the dial was placed
     * on. Closed sooner (the stack's usual two seconds), a late reply found it gone and the server's
     * leg stayed up — its agent running beside the redial's — until the server gave up on its ACK.
     * A few round trips on the slowest link a call still connects on. The registration goes at once.
     */
    const val ABANDONED_DIAL_GRACE_MS = 4_000L
    /** Answered, and still no media path after this: the customer's patience, not the library's ~30 s. */
    const val ICE_CONNECT_DEADLINE_MS = 8_000L
    /** Dials per call: the first, and one on a fresh connection. Then the WebSocket. */
    const val MAX_DIALS = 2
    /**
     * …and a third when the failure was the LINK's — nothing back to the
     * INVITE, the socket closed under it, a request timed out on the way — or
     * the server refused it with 491 for its own race (a +300 ms link,
     * measured on production 26 Oct 2026: the INVITE with credentials answered
     * 491 and the call went to the WebSocket at once). None of these dials was
     * answered as far as this side can tell, and a late answer to an abandoned
     * dial is ACKed and ended with a BYE, so the third INVITE never stands
     * beside a second greeting. A dial heard and not answered keeps its two.
     */
    const val MAX_DIALS_ON_LINK_FAILURE = 3
    /** Failures that are the link's, before any answer ([MAX_DIALS_ON_LINK_FAILURE]). */
    private val LINK_CAUSES = setOf("no_response", SipFailure.CONNECTION_ERROR, SipFailure.REQUEST_TIMEOUT)
    /** The whole attempt — mint, registration, INVITE, answer, ICE — before the WebSocket takes the call. */
    const val ATTEMPT_BUDGET_MS = 12_000L
    /** A registration that does not complete within this is refused. */
    const val REGISTER_DEADLINE_MS = 15_000L

    enum class Verdict { REDIAL, FALLBACK, IGNORE }

    /** Causes that are the network's or the socket's: a fresh connection can fix them. */
    private val NETWORK_CAUSES = setOf(
        "no_response", "no_answer", "Connection Error", "Request Timeout", "Unavailable", "SIP Failure Code",
        "WebRTC Error", "Internal Error", "No Answer", "No ACK", "Dialog Error", "RTP Timeout",
        // A credential the server no longer accepts: a fresh connection asks again.
        "Authentication Error",
    )

    /**
     * What to do about a dial that failed before it was answered: IGNORE what
     * this side cancelled, REDIAL the network's failure while a dial is left,
     * else FALLBACK — a policy refusal is refused on the WebSocket too and ends
     * there with its usual outcome; one that was this transport's is not.
     */
    fun verdict(code: Int?, reason: String?, dialsSoFar: Int): Verdict {
        val r = reason.orEmpty()
        if (r == "Canceled" || r == "User Denied Media Access") return Verdict.IGNORE
        // 491 Request Pending: the server's own race (RFC 3261 §14.1), never an answer about this call.
        if ((r in LINK_CAUSES || code == 491) && dialsSoFar < MAX_DIALS_ON_LINK_FAILURE) return Verdict.REDIAL
        val networkish = r in NETWORK_CAUSES && (r != "SIP Failure Code" || code == null || code >= 500)
        if (networkish && dialsSoFar < MAX_DIALS) return Verdict.REDIAL
        return Verdict.FALLBACK
    }

    /** A cause as a short phrase for a log line. */
    fun describe(code: Int?, reason: String?): String {
        val r = reason ?: "failed"
        if (r == "no_response") return "no reply to the INVITE"
        if (r == "no_answer") return "not answered"
        if (code == 491) return "491 Request Pending"
        return if (code != null) "$r $code" else r
    }
}

/**
 * THE MEDIA WATCH, after the call connected: the recovery's rules (RtcRecovery,
 * [RtcResilience]) — a restart [RESTART_AFTER_MS] into a drop (at once on
 * `failed`), never into no network, and the call dropped only after
 * [RtcResilience.PATH_LOST_GIVE_UP_MS] × pace with a network.
 */
internal object RtcMediaWatch {
    const val RESTART_AFTER_MS = RtcResilience.RESTART_AFTER_MS
}

/** THE OFFER AS SENT: the codecs reordered as the bar's `preferredCodecs: ["opus", "PCMU"]` does. */
internal object RtcSdp {
    val PREFERRED = listOf("opus", "PCMU")
    private val RTPMAP = Regex("^a=rtpmap:(\\d+)\\s+([^/]+)")

    /**
     * Only the `m=audio` format list moves — the `a=rtpmap` lines stay where
     * they are — and a stable sort keeps everything not named in its order.
     */
    fun preferCodecs(sdp: String, preferred: List<String> = PREFERRED): String {
        val lines = sdp.split("\r\n")
        val names = HashMap<String, String>()
        for (line in lines) RTPMAP.find(line)?.let { names[it.groupValues[1]] = it.groupValues[2].lowercase() }
        val rank = preferred.map { it.lowercase() }
        return lines.joinToString("\r\n") { line ->
            if (!line.startsWith("m=audio")) return@joinToString line
            val parts = line.split(" ")
            if (parts.size < 4) return@joinToString line
            val payloads = parts.drop(3).sortedBy { pt -> rank.indexOf(names[pt].orEmpty()).let { if (it < 0) 999 else it } }
            (parts.take(3) + payloads).joinToString(" ")
        }
    }

    /** The candidates' types in an SDP, for a log line: "host srflx relay". */
    fun candidateTypes(sdp: String?): String =
        sdp.orEmpty().split("\r\n").filter { it.startsWith("a=candidate:") }.map { RtcIce.kind(it).name.lowercase() }.joinToString(" ")

    /** How many candidates an SDP carries: an offer, or an answer, with none cannot make a path. */
    fun candidateCount(sdp: String?): Int = sdp.orEmpty().split("\r\n").count { it.startsWith("a=candidate:") }
}

/** The call's credentials, as the mint returns them. */
internal class RtcCredentials(
    val server: String,
    val username: String,
    val password: String,
    val realm: String,
    val applicationSid: String,
    /** The agent the mint signed the call for: what the INVITE's X-Graine-Agent-Id says, exactly. */
    val agentId: String,
    val expiresAtMs: Long?,
    val iceServers: List<GraineRtcIceServer>,
) {
    /** The INVITE's Request-URI and To. */
    val target: String get() = "sip:app-$applicationSid@$realm"

    override fun toString(): String = "RtcCredentials(server=$server, realm=$realm, ice=${iceServers.size})"
}

/**
 * THE MINT — `POST {baseUrl}/api/embed/rtc-session` (app/api/embed/rtc-session/route.ts).
 * The same trust as the ticket: the publishable key, no Origin header (an app
 * is allowed by the agent's `app://` entry), the identity fields beside the
 * variables and never inside them.
 */
internal object RtcMint {
    const val PATH = "/api/embed/rtc-session"
    /** A credential this close to expiring is minted again rather than used (rtc-warm.ts). */
    const val MIN_REMAINING_MS = 20_000L
    /** Retries for a mint the network lost (not for an answer): after 1 s, then 2 s — the bar's fetchWithRetry. */
    val RETRY_DELAYS_MS = listOf(1_000L, 2_000L)

    /**
     * The variables as the mint carries them: exactly the ticket's merged set
     * (CallVariables.ticketVariables — the call's own first and winning, then
     * the SDK's, twelve keys of 200 characters, a kilobyte in all), less the
     * reserved `graine_*` keys only the server writes. Empty when none fit.
     */
    fun variables(call: Map<String, String>, sdk: Map<String, String>): Map<String, String> {
        fun open(m: Map<String, String>) = m.filterKeys { k -> !k.lowercase().startsWith("graine_") }
        return CallVariables.ticketVariables(open(call), open(sdk)) ?: emptyMap()
    }

    /**
     * The body: the key, the control channel's session id, the variables, the
     * ticket's identity fields — and `audio_ready_signal`: this phone says when
     * the call's audio plays ([AUDIO_READY]), so the agent's greeting waits for it.
     */
    fun body(publishableKey: String, sessionId: String, variables: Map<String, String>, identity: IdentityEnvelope?, callId: String?): JSONObject {
        val o = JSONObject().put("publishableKey", publishableKey).put("session_id", sessionId)
        if (variables.isNotEmpty()) o.put("variables", JSONObject(variables as Map<*, *>))
        identity?.into(o)
        callId?.let { o.put("callId", it) }
        o.put(AUDIO_READY_SIGNAL, true)
        return o
    }

    /** The mint's promise that the phone will send [AUDIO_READY]: on every mint, grant and direct-mint body. */
    const val AUDIO_READY_SIGNAL = "audio_ready_signal"

    /**
     * THE PHONE'S WORD THAT THE CALL'S AUDIO IS PLAYING, once per call on the
     * control channel — the speaker's playout running. The agent's greeting
     * waits for it (a few seconds at most) instead of playing to a phone whose
     * audio has not started. The same frame the iOS SDK sends.
     */
    const val AUDIO_READY = "{\"type\":\"audio\",\"state\":\"ready\"}"

    /**
     * The credentials in a 200's body, or null when any part a call needs is
     * missing — `agent_id` among them: the INVITE must name exactly the agent
     * the mint signed, or the call reaches the agent anonymous (no identity,
     * no memory). Without it the WebSocket, which carries both, takes the call.
     */
    fun parse(body: String, now: Long): RtcCredentials? {
        val o = try { JSONObject(body) } catch (_: Exception) { return null }
        fun str(k: String): String? = (o.opt(k) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val server = str("server")?.takeIf { it.startsWith("wss://", ignoreCase = true) || it.startsWith("ws://", ignoreCase = true) } ?: return null
        val username = str("username") ?: return null
        val password = str("password") ?: return null
        val realm = str("realm") ?: return null
        val sid = str("application_sid") ?: return null
        val agent = str("agent_id") ?: return null
        return RtcCredentials(server, username, password, realm, sid, agent, expiry(str("expires_at"), now), iceServers(o.opt("ice_servers")))
    }

    /**
     * A mint refused by a gate the WebSocket would refuse too — out of
     * credit (402: insufficient_credits, parent_out_of_credits, monthly_cap)
     * or an agent switched off or deleted (409: agent_off, agent_deleted) —
     * as the message the WebSocket's refusal ends the call with: its close
     * code and reason, word for word. Null for any other answer, which falls
     * back instead.
     */
    fun refusal(status: Int, body: String): String? {
        val code = runCatching { JSONObject(body).optString("code") }.getOrNull() ?: return null
        val reason = when {
            status == 402 && code in CREDIT_CODES -> "4402 $CREDIT_REASON"
            status == 409 && code in AGENT_REASONS -> "4409 ${AGENT_REASONS[code]}"
            else -> return null
        }
        return "close $reason"
    }

    /** The credit gate's codes; the WebSocket names them all as one. */
    val CREDIT_CODES = setOf("insufficient_credits", "parent_out_of_credits", "monthly_cap")
    /** The WebSocket's own reason for a session refused for credit. */
    const val CREDIT_REASON = "insufficient_credits: this account is out of credits - add credits to start a call"
    /** …and for an agent that takes no calls. */
    val AGENT_REASONS = mapOf(
        "agent_off" to "agent_off: This agent is switched off.",
        "agent_deleted" to "agent_deleted: This agent is no longer available.",
    )

    /** `ice_servers`: `{urls, username?, credential?}`, `urls` one string or several; anything that is not STUN or TURN is left out. */
    fun iceServers(raw: Any?): List<GraineRtcIceServer> {
        val a = raw as? JSONArray ?: return emptyList()
        val out = ArrayList<GraineRtcIceServer>()
        for (i in 0 until Math.min(a.length(), 16)) {
            val s = a.opt(i) as? JSONObject ?: continue
            val urls = when (val u = s.opt("urls") ?: s.opt("url")) {
                is String -> listOf(u)
                is JSONArray -> (0 until u.length()).mapNotNull { u.opt(it) as? String }
                else -> emptyList()
            }.map { it.trim() }.filter { url -> listOf("stun:", "stuns:", "turn:", "turns:").any { url.startsWith(it, ignoreCase = true) } }
            if (urls.isEmpty()) continue
            out += GraineRtcIceServer(urls, (s.opt("username") as? String), (s.opt("credential") as? String))
        }
        return out
    }

    private val ISO = Regex("^(\\d{4})-(\\d{2})-(\\d{2})[Tt ](\\d{2}):(\\d{2}):(\\d{2})(\\.\\d+)?([Zz]|[+-]\\d{2}:?\\d{2})?$")

    /**
     * `expires_at` as epoch ms: ISO-8601 (the server writes
     * `2026-10-14T18:23:45.123456+00:00`), or epoch seconds. Null when it
     * cannot be read, or is already past (a clock ahead of the server's would
     * otherwise make a fresh credential look expired).
     */
    fun expiry(raw: String?, now: Long): Long? {
        val s = raw?.trim() ?: return null
        val ms = s.toDoubleOrNull()?.let { if (it > 1e12) it.toLong() else (it * 1000).toLong() } ?: run {
            val m = ISO.matchEntire(s) ?: return null
            val g = m.groupValues
            val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            cal.clear()
            cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
            var t = cal.timeInMillis + (g[7].takeIf { it.isNotEmpty() }?.let { ("0" + it).toDouble() * 1000 }?.toLong() ?: 0L)
            val zone = g[8]
            if (zone.isNotEmpty() && zone != "Z" && zone != "z") {
                val sign = if (zone[0] == '-') -1 else 1
                val digits = zone.substring(1).replace(":", "")
                val offset = (digits.substring(0, 2).toInt() * 60 + digits.substring(2, 4).toInt()) * 60_000L
                t -= sign * offset
            }
            t
        }
        return ms.takeIf { it > now }
    }
}

/** Why a call that asked for WebRTC is on the WebSocket — the voice bar's words, and two of the SDK's own. */
internal object RtcFallback {
    const val MINT_FAILED = "mint_failed"
    const val REGISTER_FAILED = "register_failed"
    const val NO_RESPONSE = "no_response"
    const val NO_ANSWER = "no_answer"
    const val ICE_TIMEOUT = "ice_timeout"
    const val ERROR = "error"
    /** The dashboard's Call audio setting asks for the WebSocket. */
    const val DASHBOARD = "dashboard"
    /** The app asked for WebRTC and this build cannot run it (no WebRTC module, or its native library). */
    const val UNAVAILABLE = "unavailable"
}

/** The control channel's credential and the join between it and the media call: 32 lower-case hex characters, new per call. */
internal object RtcSessionId {
    private val random = SecureRandom()

    fun next(): String {
        val b = ByteArray(16).also { random.nextBytes(it) }
        val sb = StringBuilder(32)
        for (x in b) {
            val v = x.toInt() and 0xff
            sb.append("0123456789abcdef"[v shr 4]).append("0123456789abcdef"[v and 15])
        }
        return sb.toString()
    }
}

/**
 * THE AGENT IS SPEAKING, from the far side's audio level (read about five
 * times a second): above [threshold] it speaks; it stops once the level has
 * stayed under it for [holdMs]. The level between two readings is their
 * energy over their duration, when the library reports both — a steadier
 * measure than one packet's level.
 */
internal class SpeakingDetector(private val threshold: Double = 0.01, private val holdMs: Long = 400) {
    var speaking = false
        private set
    private var quietSince: Long? = null
    private var lastEnergy: Double? = null
    private var lastDuration: Double? = null

    /** The level between this reading and the last: RMS from energy over time when both are known, else the reported level. */
    fun level(stats: GraineRtcStats): Double? {
        val e = stats.totalAudioEnergy
        val d = stats.totalSamplesDuration
        val pe = lastEnergy
        val pd = lastDuration
        lastEnergy = e
        lastDuration = d
        if (e != null && d != null && pe != null && pd != null && d > pd && e >= pe) return Math.sqrt((e - pe) / (d - pd))
        return stats.audioLevel
    }

    /** A new reading at [now]: the new state when it changed, else null. */
    fun update(level: Double?, now: Long): Boolean? {
        val loud = level != null && level > threshold
        if (loud) {
            quietSince = null
            if (!speaking) { speaking = true; return true }
            return null
        }
        if (!speaking) return null
        val since = quietSince ?: now.also { quietSince = it }
        if (now - since >= holdMs) { speaking = false; quietSince = null; return false }
        return null
    }

    fun reset(): Boolean {
        val was = speaking
        speaking = false
        quietSince = null
        lastEnergy = null
        lastDuration = null
        return was
    }
}
