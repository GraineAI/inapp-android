package ai.graine.inapp

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * THE DIRECT MINT — a WebRTC call's credential from the telephony gateway
 * itself, carrying a grant the web route signed: the voice bar's own (its
 * lib/rtc-direct.ts, and how lib/webcall-rtc.ts uses it), done natively.
 *
 * The mint at `{baseUrl}/api/embed/rtc-session` runs far from the telephony
 * gateway, and the gateway sits beside the SBC every call dials. Measured on
 * production on 19 Oct 2026, minting the SDK's way: a median of 1326 ms
 * through the web route, 193 ms at the gateway with a grant — the whole way
 * from the tap to media 1.8 s, and 0.67 s. The checks do not move: the web
 * route still makes them (the key, the agent's allowed domains, its limits)
 * and signs its verdict into the grant, which the gateway verifies before it
 * mints exactly as the web route's mint does, credit and agent gates included.
 *
 * EVERYTHING HERE IS OPTIONAL. [RtcDirectMint.mint] answers with the
 * gateway's reply — a credential, or a gate's refusal, read exactly as the web
 * route's would be — or null, and null means "mint through the web route, as
 * before". A gateway without the route, a grant refused or spent, a network
 * that cannot reach it: each costs one short request at most. Two direct
 * requests in a row that got no answer, two grants refused in a row, or a
 * gateway that answers anything but yes, and the direct mint RESTS: ten
 * minutes on the web route, asking nobody anything, then the gateway is asked
 * again. A process lives for days on a phone; a gateway out of reach this
 * morning is not one for the rest of the week.
 *
 * A GRANT IS FOR ITS REQUEST, EXACTLY. The web route signs who the user is
 * into it — the identity, the install, the memory choice, the time zone — and
 * the call's id, which the conversation carries as its `graine_call_id`. So a
 * grant is used only for the publishable key, the identity and the call id it
 * was asked for: another user's is forgotten, another call's is never used.
 * The grant kept warm is therefore asked for the NEXT call, its id chosen when
 * the grant is fetched; the call the customer places next takes that id
 * ([RtcDirectMint.takeCallId]), and its mint at the gateway carries exactly
 * what a mint through the web route would have. A call Graine rings comes with
 * its own id: the ring's call, prepared when it arrives, mints through the web
 * route, and the grant that answer carries — for the same user and that call —
 * is kept for the mint at the answer ([RtcDirectMint.expectCall]).
 *
 * TIME IS THE DEVICE'S WALL CLOCK, as in the voice bar. A grant's life runs
 * from its arrival — `ttl_ms`, else `expires_at` less now — so a phone set
 * minutes wrong neither uses a lapsed grant nor refuses a good one; past half
 * its life it is renewed, and in its last 30 s it is not used. A rest is
 * counted the same way. Android's uptime clocks (System.nanoTime,
 * SystemClock.uptimeMillis) stop while the phone sleeps, so a grant slept
 * through would look fresh on them; the wall clock keeps counting.
 */

/** What the web route hands out as `direct`: a grant, when it lapses, and where to take it. */
internal class RtcGrantOffer(
    val grant: String,
    /** Epoch ms, by the server's clock. */
    val expiresAt: Long,
    /** The grant's life, so a phone whose clock is wrong still knows when it lapses. */
    val ttlMs: Long?,
    /** The gateway's direct route: the probe asks it with a GET, the mint POSTs to it. */
    val url: String,
) {
    /** For a log line: never the grant. */
    override fun toString(): String = "RtcGrantOffer(expiresAt=$expiresAt, ttlMs=$ttlMs)"

    companion object {
        /** `direct` as the web route writes it, or null when it is missing or malformed (the bar's isDirectOffer). */
        fun parse(raw: Any?): RtcGrantOffer? {
            val o = raw as? JSONObject ?: return null
            val grant = (o.opt("grant") as? String)?.takeIf { it.startsWith("v1.") } ?: return null
            val expires = (o.opt("expires_at") as? Number)?.toDouble()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: return null
            val url = (o.opt("url") as? String)?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) } ?: return null
            val ttl = when (val t = o.opt("ttl_ms")) {
                null, JSONObject.NULL -> null
                is Number -> t.toDouble().takeIf { it > 0 && !it.isInfinite() }?.toLong() ?: return null
                else -> return null
            }
            return RtcGrantOffer(grant, expires.toLong(), ttl, url)
        }

        /** The offer an answer of the web route carries — `{"direct": …}` beside a credential, or alone — or null. */
        fun of(body: String): RtcGrantOffer? = runCatching { parse(JSONObject(body).opt("direct")) }.getOrNull()
    }
}

/** The direct mint's network: a GET (the probe) and a JSON POST, each with its own deadline. Throws on a network error. */
internal interface RtcDirectHttp {
    fun get(url: String, timeoutMs: Int): HttpReply
    fun post(url: String, json: String, timeoutMs: Int): HttpReply
}

/**
 * The platform's HTTP for it, as [PlatformHttp] sends a mint — no Origin
 * header, a JSON body — with the caller's deadline. Each answer is read to its
 * end, which hands the connection back to the platform's pool: the probe's
 * connection is the one the first direct mint rides.
 */
internal object PlatformDirectHttp : RtcDirectHttp {
    override fun get(url: String, timeoutMs: Int): HttpReply = send("GET", url, null, timeoutMs)

    override fun post(url: String, json: String, timeoutMs: Int): HttpReply = send("POST", url, json, timeoutMs)

    private fun send(method: String, url: String, json: String?, timeoutMs: Int): HttpReply {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            useCaches = false
            if (json != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (json != null) conn.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            return HttpReply(code, body, conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull())
        } finally {
            conn.disconnect()
        }
    }
}


/**
 * THE DIRECT MINT'S STATE — one per process ([shared]), which every agent's
 * calls share: the grants in hand, the gateway's answer on each of its routes,
 * and whether the direct mint is resting.
 *
 *   warm        keep a grant ready for an agent's next call — the session
 *               arrived, the user changed, the app came back — and send nothing
 *               while the one in hand holds
 *   expectCall  a ring's grant is wanted: its own mint's answer brings it, else warmCall
 *   warmCall    a ringing call's own grant, asked for apart, for its mint at the answer
 *   takeCallId  the call being placed takes the id the next call's grant was asked for
 *   mint        a call's credential from the gateway, or null for the web route
 *   webMinted   the grant a web route's answer brought, kept for its own call
 *
 * Thread-safe. Only [mint] blocks — on the call's own mint thread, for one
 * short request (and a probe already on its way); every other request runs in
 * the background. Nothing here throws, and nothing here is ever needed: a
 * call that finds no grant mints through the web route, exactly as before.
 */
internal class RtcDirectMint(
    private val http: RtcDirectHttp = PlatformDirectHttp,
    /** The device's wall clock: a grant's life and a rest are counted on it. */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Where the probes and the grant requests run. */
    private val background: (() -> Unit) -> Unit = { work -> Thread({ work() }, "graine-direct-mint").apply { isDaemon = true }.start() },
    /**
     * What ends a rest on time while the phone stays awake. Its clock stops
     * while the phone sleeps, so a rest the wall clock says is over ends at the
     * next warm-up or call instead.
     */
    private val timers: CallScheduler = ExecutorScheduler("graine-direct-mint"),
    /** A new id for a call this app places, as it names them. */
    private val newCallId: () -> String = { CallIds.next() },
) {
    /** What a grant was asked for — the key, who the user is, which call. A grant is used for exactly this and nothing else. */
    private data class GrantKey(val publishableKey: String, val identity: IdentityEnvelope?, val callId: String?)

    /** A grant in hand: when it lapses and when it is due for renewal, on the wall clock. */
    private class Held(val offer: RtcGrantOffer, val lapsesAt: Long, val renewAt: Long)

    /** One probe of one route: on its way until [done] counts down; [ready] once the gateway said yes. */
    private class Probe {
        val done = CountDownLatch(1)
        @Volatile var ready = false
    }

    private val lock = Any()
    private val held = object : LinkedHashMap<GrantKey, Held>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<GrantKey, Held>?): Boolean = size > MAX_HELD
    }
    /** Each publishable key's next call: what its grant was asked for, and so the id the next call placed with that key takes. */
    private val next = HashMap<String, GrantKey>()
    /** The next call's grant on its way, per publishable key: one request at a time. */
    private val fetching = HashMap<String, GrantKey>()
    /** Ringing calls' grants on their way. */
    private val fetchingCalls = HashSet<GrantKey>()
    /** Ringing calls whose grant is wanted, until their own mint's answer says whether it carried one. */
    private val wantedCalls = HashSet<GrantKey>()
    /** No grant request before this, per publishable key: the web route said to wait. */
    private val quietUntil = HashMap<String, Long>()
    /** Keys the web route signs no grants for (`direct: null`): not asked again until one of its answers brings a grant. */
    private val unoffered = HashSet<String>()
    /** The gateway's answer per direct route: on its way, or yes. No entry: never asked, or no answer — ask again. */
    private val probes = HashMap<String, Probe>()
    /** Every direct route an offer has named: what is asked again when a rest is over. */
    private val routes = LinkedHashSet<String>()
    /** Direct requests in a row (the probe's or a mint's) that got no answer. */
    private var unanswered = 0
    /** Grants in a row the gateway refused. */
    private var refused = 0
    /** While resting: until when, on the wall clock — and the timer that ends it on time. */
    private var restUntil: Long? = null
    private var restTimer: (() -> Unit)? = null

    /** Resting now: every call mints through the web route, and nothing direct is sent. */
    val resting: Boolean get() = synchronized(lock) { restUntil?.let { clock() < it } ?: false }

    // ── a grant for the next call ───────────────────────────────────────────

    /**
     * Keep a grant ready for [publishableKey]'s next call, for [identity]. The
     * web route is asked only when none is in hand for this user, or the one in
     * hand is past half its life or within 30 s of lapsing: otherwise nothing is
     * sent, however often the app comes back — but the probe of a route whose
     * answer never came. Grants another user's requests brought are forgotten
     * first. Resting: nothing is sent. Never blocks.
     */
    fun warm(baseUrl: String, publishableKey: String, identity: IdentityEnvelope?) = warmNext(baseUrl, publishableKey, identity, fromApp = true)

    /**
     * [warm], from the app ([fromApp]: [identity] is who the user is now, and
     * another user's grants go) or from behind a call's mint — whose request
     * may be older than the user: a call a push prepared before the session
     * said who the user is. That one renews the next call's grant only for the
     * user it was asked for, and forgets nobody's.
     */
    private fun warmNext(baseUrl: String, publishableKey: String, identity: IdentityEnvelope?, fromApp: Boolean) {
        val now = clock()
        val probeNow = ArrayList<Pair<String, Probe>>()
        val ask = synchronized(lock) {
            if (fromApp) forgetOthers(publishableKey, identity)
            if (resting(now, probeNow)) return@synchronized null
            val kept = next[publishableKey]
            if (!fromApp && kept != null && kept.identity != identity) return@synchronized null
            val h = kept?.let { held[it] }
            when {
                h != null && !needsGrant(h, now) -> {
                    claimProbe(h.offer.url)?.let { probeNow += it }
                    null
                }
                publishableKey in fetching || publishableKey in unoffered -> null
                (quietUntil[publishableKey] ?: 0L) > now -> null
                // Renewed, a grant keeps its call id: it is for the same next call.
                else -> GrantKey(publishableKey, identity, kept?.callId ?: newCallId()).also { fetching[publishableKey] = it }
            }
        }
        launch(probeNow)
        if (ask != null) background { fetch(baseUrl, ask, forNext = true) }
    }

    /**
     * A ringing call's own grant, asked for apart, for exactly [callId]: when
     * the ring's own mint's answer carried none ([webMinted]), and when who the
     * user is changes while it rings — the call prepared at the ring is minted
     * again at the answer when what it carries has changed. That mint takes the
     * grant to the gateway; not here by then, the web route mints, as before.
     * Resting: nothing. Never blocks.
     */
    fun warmCall(baseUrl: String, publishableKey: String, identity: IdentityEnvelope?, callId: String) {
        val now = clock()
        val probeNow = ArrayList<Pair<String, Probe>>()
        val ask = synchronized(lock) {
            if (resting(now, probeNow)) return@synchronized null
            val key = GrantKey(publishableKey, identity, callId)
            val h = held[key]
            when {
                h != null && !needsGrant(h, now) -> null
                key in fetchingCalls || publishableKey in unoffered -> null
                (quietUntil[publishableKey] ?: 0L) > now -> null
                else -> key.also { fetchingCalls += it }
            }
        }
        launch(probeNow)
        if (ask != null) background { fetch(baseUrl, ask, forNext = false) }
    }

    /**
     * The web route's grant-only answer: the same checks as a mint, in the same
     * order, and no credential. A grant for the next call becomes that key's
     * next call's; a ringing call's is kept for that call; the first grant on a
     * route has the gateway probed. A refusal (a 429, or a setting such as an
     * agent without `app://`) pauses this key's grant requests for a while; an
     * answer saying the web route signs none for the key stops them, until one
     * of its answers brings a grant after all.
     */
    private fun fetch(baseUrl: String, ask: GrantKey, forNext: Boolean) {
        var offer: RtcGrantOffer? = null
        var waitMs: Long? = null
        var signsNone = false
        try {
            val reply = http.post(baseUrl.trimEnd('/') + RtcMint.PATH, grantBody(ask).toString(), WEB_TIMEOUT_MS)
            if (reply.code == 200) {
                offer = RtcGrantOffer.of(reply.body)
                if (offer == null) {
                    signsNone = runCatching { JSONObject(reply.body).let { it.has("direct") && it.isNull("direct") } }.getOrDefault(false)
                    note(if (signsNone) "grant: the web route signs none for this key" else "grant: the answer carried none")
                }
            } else {
                waitMs = (if (reply.code == 429) reply.retryAfterSeconds?.times(1000) else null) ?: QUIET_MS
                note("grant: HTTP ${reply.code}${code(reply.body)?.let { " $it" } ?: ""}")
            }
        } catch (e: Exception) {
            note("grant: the request failed (${e.javaClass.simpleName})")
        }
        val now = clock()
        val got = offer
        val probeNow = ArrayList<Pair<String, Probe>>()
        synchronized(lock) {
            // A request made for another user while it was on its way is no longer this key's to answer.
            val current = if (forNext) fetching[ask.publishableKey] == ask else ask in fetchingCalls
            if (forNext) {
                if (current) fetching.remove(ask.publishableKey)
            } else {
                fetchingCalls.remove(ask)
            }
            waitMs?.let { quietUntil[ask.publishableKey] = now + it }
            if (signsNone) unoffered += ask.publishableKey
            if (current && got != null) {
                keep(ask, got, now, probeNow)
                if (forNext) next[ask.publishableKey] = ask
            }
        }
        launch(probeNow)
    }

    /**
     * `{publishableKey, grant_only: true, …}` with exactly the identity fields a
     * mint for [ask] carries: the mint's own body, less its session id — and
     * without variables, which a grant does not carry.
     */
    private fun grantBody(ask: GrantKey): JSONObject =
        RtcMint.body(ask.publishableKey, "", emptyMap(), ask.identity, ask.callId).apply {
            remove("session_id")
            put("grant_only", true)
        }

    /**
     * The id the call about to be placed takes: the one [publishableKey]'s
     * next-call grant was asked for, when that grant is [identity]'s and has
     * life enough left. The grant is that call's from now on; the next call's
     * is fetched once this call's mint is done ([mint]), so nothing travels
     * beside it. Null: the call takes an id of its own and mints through the
     * web route, as before — as every call does while resting, the grant kept
     * for after.
     */
    fun takeCallId(publishableKey: String, identity: IdentityEnvelope?): String? {
        val now = clock()
        val probeNow = ArrayList<Pair<String, Probe>>()
        val id = synchronized(lock) {
            forgetOthers(publishableKey, identity)
            if (resting(now, probeNow)) return@synchronized null
            val kept = next[publishableKey] ?: return@synchronized null
            val h = held[kept]
            if (h == null || !usable(h, now)) return@synchronized null
            next.remove(publishableKey)
            kept.callId
        }
        launch(probeNow)
        return id
    }

    // ── the mint ────────────────────────────────────────────────────────────

    /**
     * [request]'s credential from the gateway, with the grant asked for exactly
     * that request — or a gate's refusal (402, 409), which the web route would
     * make in the same words and is read exactly as its answer. Null for
     * everything else, and the web route mints: resting, no grant for this call,
     * one about to lapse, a route the gateway has not said yes on, a grant
     * refused or spent, no answer. Blocks for one short request at most (and a
     * probe already on its way — never once [dialling] says the call is being
     * dialled: the user is waiting then, and the web route does not keep them).
     */
    fun mint(request: RtcCallRequest, sessionId: String, dialling: () -> Boolean = { false }): HttpReply? {
        val key = GrantKey(request.publishableKey, request.identity, request.callId)
        val now = clock()
        val probeNow = ArrayList<Pair<String, Probe>>()
        val (isResting, h) = synchronized(lock) { resting(now, probeNow) to held[key] }
        launch(probeNow)
        if (isResting) {
            note("mint: resting; via the web route")
            return null
        }
        // Then the next call's grant — fetched if none came, renewed when due — so no call waits on one.
        try {
            return mintNow(request, sessionId, key, h, now, dialling)
        } finally {
            warmNext(request.baseUrl, request.publishableKey, request.identity, fromApp = false)
        }
    }

    private fun mintNow(request: RtcCallRequest, sessionId: String, key: GrantKey, h: Held?, now: Long, dialling: () -> Boolean): HttpReply? {
        if (h == null) {
            note("mint: no grant for this call; via the web route")
            return null
        }
        if (!usable(h, now)) {
            synchronized(lock) { held.remove(key) }
            note("mint: the grant is about to lapse; via the web route")
            return null
        }
        if (!probeReady(h.offer.url, dialling)) return null
        val body = JSONObject().put("grant", h.offer.grant).put("session_id", sessionId)
        // The variables travel with the credential, as they do through the web route: the gateway cleans them by the same rule.
        if (request.variables.isNotEmpty()) body.put("variables", JSONObject(request.variables as Map<*, *>))
        val started = clock()
        val reply = try {
            http.post(h.offer.url, body.toString(), MINT_TIMEOUT_MS)
        } catch (e: Exception) {
            noAnswer("mint: the gateway did not answer (${e.javaClass.simpleName})")
            return null
        }
        return judge(key, reply, clock() - started)
    }

    /** What the gateway's answer means for the call. */
    private fun judge(key: GrantKey, reply: HttpReply, ms: Long): HttpReply? {
        val code = code(reply.body)
        val named = code?.let { " $it" } ?: ""
        return when {
            reply.code == 200 && RtcMint.parse(reply.body, clock()) != null -> {
                synchronized(lock) { unanswered = 0; refused = 0 }
                note("mint: direct, $ms ms")
                reply
            }
            reply.code == 200 -> {
                note("mint: the gateway's answer was not a credential; via the web route")
                null
            }
            // No credit, an agent switched off or deleted: the same gate stands behind both routes, so the web route
            // would answer the same, a second later. The answer is the call's as it came — never asked twice.
            reply.code == 402 || reply.code == 409 -> {
                note("mint: refused at the gateway (HTTP ${reply.code}$named)")
                reply
            }
            code == "grant_invalid" || reply.code == 401 || reply.code == 403 -> {
                // The web route's mint signs a new one. Two refused in a row: the two disagree on the secret, for now.
                val twice = synchronized(lock) { held.remove(key); ++refused >= MAX_REFUSED }
                if (twice) rest("the gateway refused $MAX_REFUSED grants in a row") else note("mint: the grant was refused (HTTP ${reply.code}$named); via the web route")
                null
            }
            code == "grant_budget" || reply.code == 429 -> {
                synchronized(lock) { held.remove(key) }
                note("mint: the grant is spent; via the web route")
                null
            }
            reply.code == 404 -> {
                // The grant names an agent the gateway does not have (a call twin that moved): the web route mints for the new one.
                synchronized(lock) { held.remove(key) }
                note("mint: the gateway does not know the grant's agent; via the web route")
                null
            }
            else -> {
                note("mint: the gateway answered HTTP ${reply.code}$named; via the web route")
                null
            }
        }
    }

    /**
     * A ring has arrived: its grant is wanted for exactly [callId], for the
     * mint at its answer. The ring's own call, prepared now, mints through the
     * web route, and that answer carries a grant for the same user and call
     * ([webMinted]): only when it carries none is one asked for apart — a ring
     * costs the web route one request, not two. Sends nothing itself.
     */
    fun expectCall(publishableKey: String, identity: IdentityEnvelope?, callId: String) {
        synchronized(lock) { wantedCalls += GrantKey(publishableKey, identity, callId) }
    }

    /**
     * The web route's answer to [request]'s mint (null: no answer). The grant
     * that rode in on it is kept for exactly that request — that call's own
     * later mints, a ring's at its answer, a redial — its route probed if it is
     * new, and the next call's grant asked for if none is in hand (the web route
     * signs grants for this key after all). An answer without one leaves a
     * ringing call's grant to be asked for apart, unless a gate or a setting
     * refused the call: a call refused at its mint gains nothing from a grant.
     */
    fun webMinted(request: RtcCallRequest, reply: HttpReply?) {
        val key = GrantKey(request.publishableKey, request.identity, request.callId)
        val offer = reply?.takeIf { it.code == 200 }?.let { RtcGrantOffer.of(it.body) }
        val now = clock()
        val probeNow = ArrayList<Pair<String, Probe>>()
        val askApart = synchronized(lock) {
            val wanted = wantedCalls.remove(key)
            if (offer != null) {
                unoffered -= request.publishableKey
                keep(key, offer, now, probeNow)
                false
            } else {
                wanted && (reply == null || reply.code == 200 || reply.code >= 500)
            }
        }
        launch(probeNow)
        val callId = request.callId
        if (askApart && callId != null) warmCall(request.baseUrl, request.publishableKey, request.identity, callId)
        if (offer != null) warmNext(request.baseUrl, request.publishableKey, request.identity, fromApp = false)
    }

    // ── the probe ───────────────────────────────────────────────────────────

    /**
     * Is the gateway's direct route there? Asked before any grant is taken to it
     * — the web route and the gateway deploy separately, in either order — and
     * the request also opens the connection the first direct mint rides. A
     * probe on its way is waited for — at most [PROBE_WAIT_MS], and not at all
     * once the call is dialling ([dialling]): this one then mints through the
     * web route. A route not asked yet (or whose answer never came) is asked
     * for the next call, and this one mints through the web route.
     */
    private fun probeReady(url: String, dialling: () -> Boolean): Boolean {
        val probeNow = ArrayList<Pair<String, Probe>>()
        val p = synchronized(lock) {
            probes[url] ?: run {
                if (!resting(clock(), probeNow)) claimProbe(url)?.let { probeNow += it }
                null
            }
        }
        launch(probeNow)
        if (p == null) {
            note("mint: the gateway has not said whether it takes grants; via the web route")
            return false
        }
        var waited = 0L
        while (!p.ready && waited < PROBE_WAIT_MS && !dialling()) {
            if (p.done.await(PROBE_SLICE_MS, TimeUnit.MILLISECONDS)) break
            waited += PROBE_SLICE_MS
        }
        if (!p.ready && dialling()) note("mint: dialling, and the gateway's answer is still on its way; via the web route")
        return p.ready
    }

    /** The probe of [url], for the caller to run out of the lock, when the route has no answer and none on its way. Under [lock]. */
    private fun claimProbe(url: String): Pair<String, Probe>? {
        if (probes.containsKey(url)) return null
        val p = Probe()
        probes[url] = p
        return url to p
    }

    private fun launch(probeNow: List<Pair<String, Probe>>) {
        for ((url, p) in probeNow) background { runProbe(url, p) }
    }

    /**
     * `GET` a direct route: `{"direct": true}` is yes, kept until a rest; any
     * other answer rests the direct mint; no answer is asked again by the next
     * warm-up or call — and is one of the two in a row that rest it.
     */
    private fun runProbe(url: String, p: Probe) {
        val yes: Boolean? = try {
            val r = http.get(url, DIRECT_TIMEOUT_MS)
            r.code == 200 && runCatching { JSONObject(r.body).opt("direct") == true }.getOrDefault(false)
        } catch (_: Exception) {
            null
        }
        val probeNow = ArrayList<Pair<String, Probe>>()
        val ready = synchronized(lock) {
            // The route keeps its probe until the rest is looked at, so a rest ending now does not ask it twice.
            val taken = yes == true && !resting(clock(), probeNow)
            if (taken) {
                p.ready = true
                unanswered = 0
            } else if (probes[url] === p) {
                probes.remove(url)
            }
            taken
        }
        p.done.countDown()
        launch(probeNow)
        when (yes) {
            true -> if (ready) note("mint: the gateway takes grants")
            false -> rest("the gateway takes no grants")
            null -> noAnswer("probe: the gateway did not answer")
        }
    }

    // ── resting ─────────────────────────────────────────────────────────────

    /** A direct request — the probe's or a mint's — that got no answer: the second in a row rests the direct mint. */
    private fun noAnswer(why: String) {
        val twice = synchronized(lock) { ++unanswered >= MAX_UNANSWERED }
        if (twice) rest("$why, twice in a row") else note("$why; via the web route")
    }

    /**
     * Ten minutes on the web route with nothing direct sent: no mint, no probe,
     * no grant request. The counts start again, every route's answer is asked
     * for again after it (a probe on its way finishes on its own), and the
     * grants in hand are kept — still good after the rest if their life allows.
     */
    private fun rest(why: String) {
        val now = clock()
        synchronized(lock) {
            if (restUntil?.let { now < it } == true) return
            val until = now + REST_MS
            restUntil = until
            unanswered = 0
            refused = 0
            for ((url, p) in probes.filterValues { it.ready }) {
                p.ready = false
                probes.remove(url)
            }
            restTimer?.invoke()
            restTimer = timers.schedule(REST_MS) { endRest(until) }
        }
        note("direct mint resting ${REST_MS / 60_000} minutes: $why. Calls mint through the web route")
    }

    /** The rest's timer, the phone awake through it. */
    private fun endRest(until: Long) {
        val probeNow = ArrayList<Pair<String, Probe>>()
        synchronized(lock) { if (restUntil == until) resume(probeNow) }
        launch(probeNow)
    }

    /** Resting at [now]? A rest the wall clock says is over — one the phone slept through, its timer late — ends here. Under [lock]. */
    private fun resting(now: Long, probeNow: MutableList<Pair<String, Probe>>): Boolean {
        val until = restUntil ?: return false
        if (now < until) return true
        resume(probeNow)
        return false
    }

    /** The rest is over: the counts start again, and every route seen is asked again — by the caller, out of the lock. Under [lock]. */
    private fun resume(probeNow: MutableList<Pair<String, Probe>>) {
        restUntil = null
        restTimer?.invoke()
        restTimer = null
        unanswered = 0
        refused = 0
        for (url in routes) claimProbe(url)?.let { probeNow += it }
        note("direct mint: the rest is over; asking the gateway again")
    }

    // ── bookkeeping ─────────────────────────────────────────────────────────

    /** Another user's grants for [publishableKey] go, with any being fetched for them. Under [lock]. */
    private fun forgetOthers(publishableKey: String, identity: IdentityEnvelope?) {
        held.keys.removeAll { it.publishableKey == publishableKey && it.identity != identity }
        next[publishableKey]?.let { if (it.identity != identity) next.remove(publishableKey) }
        fetching[publishableKey]?.let { if (it.identity != identity) fetching.remove(publishableKey) }
        fetchingCalls.removeAll { it.publishableKey == publishableKey && it.identity != identity }
        wantedCalls.removeAll { it.publishableKey == publishableKey && it.identity != identity }
    }

    /** A grant kept for [key], and its route probed if it has no answer (unless resting). Under [lock]. */
    private fun keep(key: GrantKey, offer: RtcGrantOffer, now: Long, probeNow: MutableList<Pair<String, Probe>>) {
        held[key] = hold(offer, now)
        routes += offer.url
        if (!resting(now, probeNow)) claimProbe(offer.url)?.let { probeNow += it }
    }

    /** Timed from arrival, on the wall clock. */
    private fun hold(offer: RtcGrantOffer, now: Long): Held {
        val life = offer.ttlMs ?: Math.max(0L, offer.expiresAt - now)
        return Held(offer, now + life, now + life / 2)
    }

    private fun usable(h: Held, now: Long): Boolean = now < h.lapsesAt - MIN_LIFE_MS

    /** Time to ask for a grant in place of the one in hand: past half its life, or within 30 s of lapsing. */
    private fun needsGrant(h: Held, now: Long): Boolean = now >= h.renewAt || !usable(h, now)

    /** An answer's own error code (`grant_invalid`, `agent_off`) — never its message. */
    private fun code(body: String): String? =
        runCatching { JSONObject(body).optString("code").takeIf { it.isNotEmpty() && it.length <= 40 } }.getOrNull()

    /** The debug log: timings and codes only — never a grant, an identity or a call's id. */
    private fun note(line: String) = CallLog.debug("webrtc $line")

    companion object {
        /** The one of this process: the gateway's answers and the rests hold for every agent's calls. */
        val shared: RtcDirectMint by lazy { RtcDirectMint() }

        /** A probe not answered in this got no answer — the gateway answers in about 0.2 s. */
        const val DIRECT_TIMEOUT_MS = 4_000
        /**
         * A direct mint not answered in this got no answer, and the web route
         * mints: its user is waiting, and a gateway that answers at all answers
         * in about 0.2 s — a dead route must cost the call a moment, not seconds.
         */
        const val MINT_TIMEOUT_MS = 1_500
        /** A grant request through the web route, in the background: at the web route's own pace, as a mint is. */
        const val WEB_TIMEOUT_MS = 8_000
        /** How long a mint made ahead of the dial waits for a probe already on its way; a dialling call's waits for none. */
        const val PROBE_WAIT_MS = 5_000L
        /** A dial that starts during that wait is looked for this often. */
        const val PROBE_SLICE_MS = 50L
        /** A grant with less life left than this is not used, and is renewed: a mint in flight must not arrive with it lapsed. */
        const val MIN_LIFE_MS = 30_000L
        /** Direct requests in a row (the probe's or a mint's) with no answer that rest the direct mint. */
        const val MAX_UNANSWERED = 2
        /** Grants refused in a row that rest it: the web route and the gateway disagree on the secret. */
        const val MAX_REFUSED = 2
        /** A rest: the web route alone, nothing direct sent, then every route asked again. */
        const val REST_MS = 600_000L
        /** Grants held at once: each agent's next call's, and the calls in progress's own. */
        const val MAX_HELD = 8
        /** How long grant requests pause after the web route's refusal says nothing of when to come back. */
        const val QUIET_MS = 60_000L
    }
}
