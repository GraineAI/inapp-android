package ai.graine.inapp

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/*
 * PROVING THE APP — "Require a verified app" (APP-IDENTITY.md §2, §3).
 *
 * When an agent's settings ask for it (`security.app.verify` not "off"), the
 * SDK proves this app in the background once the session arrives: a challenge
 * from Graine, a Play Integrity token with that challenge as its nonce, and
 * the token back to Graine, which answers with an APP PASS — kept for 24
 * hours per publishable key, sent with every request until it lapses, and
 * renewed in the background once less than 6 hours remain. Under "enforce" a
 * conversation waits up to 10 seconds for a proof on its way when no pass is
 * held; a request refused `app_not_verified` is proven and sent once more.
 *
 * When Google cannot be reached, a pass already held (even a lapsed one) asks
 * for a short grace pass instead (`proof: {kind: "unavailable"}`), and the
 * proof is tried again after 30 s, 2 min and 10 min, then at the next
 * foreground. A debug build never asks Play Integrity at all: it sends its
 * debug token instead (development mode, §3.6).
 *
 * Never on the main thread: proofs run on a thread of their own, and the
 * waits above on the thread of the request that waits.
 */

/** The session's `security` (§2.1): what the agent asks of this app and of its users. */
internal class SecuritySettings(
    /** `off`, `report` or `enforce`. */
    val appVerify: String,
    /** The Google Cloud project whose Play Integrity the agent reads; null when the dashboard set none. */
    val cloudProjectNumber: Long?,
    /** `off`, `report` or `enforce`. */
    val userVerify: String,
    /** `hmac` or `jwt`; null when none is set. */
    val userMethod: String?,
) {
    /** The agent wants this app proven. */
    val provesApp: Boolean get() = appVerify != OFF
    /** …and refuses an app that is not. */
    val enforcesApp: Boolean get() = appVerify == ENFORCE
    /** The agent checks who its users are. */
    val checksUsers: Boolean get() = userVerify != OFF

    companion object {
        const val OFF = "off"
        const val REPORT = "report"
        const val ENFORCE = "enforce"

        /** Nothing configured: what an older server (no `security`) means. */
        val NONE = SecuritySettings(OFF, null, OFF, null)

        /**
         * The session's `security`, or [NONE]. A mode this build does not know
         * is read as `report` — proven, never waited for — so a stricter mode
         * added later is not mistaken for none.
         */
        fun parse(raw: Any?): SecuritySettings {
            val o = raw as? JSONObject ?: return NONE
            val app = o.opt("app") as? JSONObject
            val user = o.opt("user") as? JSONObject
            fun mode(v: Any?): String {
                val w = (v as? String)?.trim()?.lowercase()
                return when {
                    w.isNullOrEmpty() || w == OFF -> OFF
                    w == ENFORCE -> ENFORCE
                    else -> REPORT
                }
            }
            val cloud = when (val c = app?.opt("cloudProjectNumber")) {
                is String -> c.trim().toLongOrNull()
                is Number -> Js.finite(c)?.takeIf { it % 1.0 == 0.0 }?.toLong()
                else -> null
            }?.takeIf { it > 0 }
            val method = (user?.opt("method") as? String)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            return SecuritySettings(mode(app?.opt("verify")), cloud, mode(user?.opt("verify")), method)
        }
    }
}

/** Play Integrity, behind an interface: PlayIntegrity.kt's prover in an app, a fake in the tests. */
internal interface IntegrityProver {
    /** A token for [nonce] — blocking, never on the main thread, for at most [timeoutMs]. */
    fun token(nonce: String, cloudProjectNumber: Long?, timeoutMs: Long): IntegrityAnswer
}

/** What Play Integrity came to. */
internal sealed class IntegrityAnswer {
    class Token(val token: String) : IntegrityAnswer()

    /**
     * Google could not be reached or asked to wait (§3.5 3): back off — and,
     * when [outage] (the four codes §3.2 names), ask for the outage grace.
     */
    class Unreachable(val why: String, val outage: Boolean = true) : IntegrityAnswer()

    /** This device cannot give a token at all (no Play Store, no Play services…): no proof. */
    class Unavailable(val why: String) : IntegrityAnswer()
}

/**
 * Play Integrity's error codes (`IntegrityErrorCode`), by value — read off its
 * exception in PlayIntegrity.kt, kept here so the rule is a JVM test.
 */
internal object IntegrityErrors {
    const val API_NOT_AVAILABLE = -1
    const val PLAY_STORE_NOT_FOUND = -2
    const val NETWORK_ERROR = -3
    const val PLAY_SERVICES_NOT_FOUND = -6
    const val TOO_MANY_REQUESTS = -8
    const val CANNOT_BIND_TO_SERVICE = -9
    const val GOOGLE_SERVER_UNAVAILABLE = -12
    const val CLIENT_TRANSIENT_ERROR = -17
    const val INTERNAL_ERROR = -100

    /** The codes that mean "Google, or the way to it, is not there right now" (§3.5 3): back off as for a 503, and ask for the outage grace. */
    val OUTAGE = setOf(TOO_MANY_REQUESTS, GOOGLE_SERVER_UNAVAILABLE, NETWORK_ERROR, INTERNAL_ERROR)

    /** [code] (null: not Play Integrity's own error) as what it means for the proof. A transient error of the device's own backs off, without the grace. */
    fun classify(code: Int?, name: String): IntegrityAnswer = when {
        code != null && code in OUTAGE -> IntegrityAnswer.Unreachable("integrity error $code")
        code == CLIENT_TRANSIENT_ERROR -> IntegrityAnswer.Unreachable("integrity error $code", outage = false)
        else -> IntegrityAnswer.Unavailable(if (code != null) "integrity error $code" else name)
    }
}

/** An app pass as kept: opaque, when it lapses (the device's wall clock), the verdict it came with, and the install it is for. */
internal class AppPass(val pass: String, val expiresAt: Long, val verdict: GraineAppVerdict, val installId: String?)

/**
 * The app passes, one per agent's key and base URL — SharedPreferences
 * private to the app in an app, a map in a test. A pass kept for another
 * install (a backup restored onto a new phone) is never read back.
 */
internal class AppPassStore(private val read: (String) -> String?, private val write: (String, String?) -> Unit) {
    fun load(slot: String, installId: String?): AppPass? = runCatching {
        val o = JSONObject(read(SLOT + slot) ?: return null)
        val pass = o.optString("p").takeIf { it.isNotEmpty() } ?: return null
        val verdict = GraineAppVerdict.values().firstOrNull { it.word == o.optString("v") } ?: return null
        val install = o.optString("i").ifEmpty { null }
        if (install != installId) return null
        AppPass(pass, o.optLong("e"), verdict, install)
    }.getOrNull()

    fun save(slot: String, pass: AppPass?) {
        runCatching {
            write(SLOT + slot, pass?.let {
                JSONObject().put("p", it.pass).put("e", it.expiresAt).put("v", it.verdict.word).apply { it.installId?.let { i -> put("i", i) } }.toString()
            })
        }
    }

    companion object {
        private const val PREFS = "ai.graine.inapp.app-proof"
        private const val SLOT = "pass."

        /** The app's private SharedPreferences; a map for this process when they cannot be had. */
        fun android(context: Context): AppPassStore {
            val prefs = runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }.getOrNull() ?: return memory()
            return AppPassStore(
                read = { k -> prefs.getString(k, null) },
                write = { k, v -> prefs.edit().apply { if (v == null) remove(k) else putString(k, v) }.apply() },
            )
        }

        fun memory(): AppPassStore {
            val kept = java.util.concurrent.ConcurrentHashMap<String, String>()
            return AppPassStore({ kept[it] }, { k, v -> if (v == null) kept.remove(k) else kept[k] = v })
        }
    }
}

/**
 * THE APP'S PROOFS, FOR EVERY AGENT OF THE PROCESS — one per process
 * (AppShared), each agent's key with its own settings, pass and proof.
 * Thread-safe; nothing here throws.
 */
internal class AppProofs(
    /** This app's declaration (§1.2): read once, when first needed. Null when the package name cannot be read. */
    declaration: () -> AppDeclaration?,
    private val store: AppPassStore,
    /** The install's own store: the install id, and the debug token kept with it. */
    private val device: () -> DeviceStore?,
    /** The app was built debuggable. */
    debuggable: () -> Boolean,
    /** Play Integrity, when it is in the app; asked once, when first needed. */
    prover: () -> IntegrityProver?,
    /** Where background proofs run, and the backoff's timers. */
    private val worker: CallScheduler,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** What an agent with this key shows may have changed. */
    private val changed: (publishableKey: String) -> Unit = {},
    /** The log, for the lines the developer must see: logcat in an app. */
    private val log: (String) -> Unit = { line -> Log.i(GraineInApp.TAG, line) },
    /** How long a conversation under "enforce" waits for a proof on its way (§2.1: 10 s). */
    private val enforceWaitMs: Long = ENFORCE_WAIT_MS,
    /** How long a refused request waits for a proof another thread is making. */
    private val proofWaitMs: Long = PROOF_WAIT_MS,
) {
    private val declared by lazy { runCatching(declaration).getOrNull() }
    private val isDebuggable by lazy { runCatching(debuggable).getOrDefault(false) }
    private val integrity by lazy { runCatching(prover).getOrNull() }

    /** One agent key's settings, pass, proof and backoff. Under [lock]. */
    private class KeyState(val key: String) {
        var baseUrl: String? = null
        var http: HttpPost? = null
        var slot: String? = null
        var security: SecuritySettings? = null
        var pass: AppPass? = null
        /** The verdict Graine last said (an answer's `verification.app`, a refusal), with its reason. */
        var heard: GraineAppVerdict? = null
        var heardReason: String? = null
        var flight: Flight? = null
        /** Proofs in a row that failed, and when the next may start; past the ladder, the next foreground. */
        var failures = 0
        var resumeAt = 0L
        var waitForeground = false
        var retryTimer: (() -> Unit)? = null
        /** Graine said this app is not one of the agent's (`app_not_allowed`): nothing is proven until the next foreground. */
        var notAllowed = false
        /** When the last proof gave a pass: a refusal right after it is not proven again. */
        var provenAt = Long.MIN_VALUE / 2
    }

    /** A proof on its way: waited for, or told to run something when it is done. */
    private class Flight {
        val done = CountDownLatch(1)
        val then = ArrayList<() -> Unit>()
    }

    private val lock = Any()
    private val states = HashMap<String, KeyState>()

    /** The proofs this belongs to: its own requests go through their door too (EmbedRequest.kt). */
    @Volatile internal var owner: Proofs? = null
    @Volatile private var libraryMissingTold = false
    @Volatile private var unavailableTold = false

    /** Development mode (§3.6): the app's choice; [GraineDebugTokenMode.AUTOMATIC] unless it made one. */
    @Volatile var debugTokenMode: GraineDebugTokenMode = GraineDebugTokenMode.AUTOMATIC
        set(value) {
            field = value
            synchronized(lock) { states.keys.toList() }.forEach { changed(it) }
        }

    /** This build sends a debug token instead of proving itself. */
    fun debugTokenInUse(): Boolean = DebugTokens.inUse(debugTokenMode, isDebuggable)

    /**
     * The install's debug token while this build uses one — made once per
     * install and kept with the install id, and logged the first time it is
     * used, once per install — else null: a release build never makes one.
     */
    fun debugToken(): String? {
        if (!debugTokenInUse()) return null
        val d = runCatching(device).getOrNull() ?: return null
        var tell: String? = null
        val token = synchronized(lock) {
            val t = d.debugToken?.takeIf { DebugTokens.valid(it) } ?: DebugTokens.fresh().also { d.debugToken = it }
            if (!d.debugTokenLogged) {
                d.debugTokenLogged = true
                tell = t
            }
            t
        }
        tell?.let { log(DebugTokens.logLine(it)) }
        return token
    }

    private fun state(key: String): KeyState = states.getOrPut(key) { KeyState(key) }

    private fun installId(): String? = runCatching { device()?.installId() }.getOrNull()

    // ── what an agent tells it ──────────────────────────────────────────────

    /** An agent with [key] at [baseUrl] exists: its proofs go there, and the pass kept for it is read back. */
    fun register(key: String, baseUrl: String, http: HttpPost) {
        val slot = SessionCache.owner(baseUrl, key)
        val install = installId()
        val kept = store.load(slot, install)
        synchronized(lock) {
            val s = state(key)
            s.baseUrl = baseUrl
            s.http = http
            s.slot = slot
            if (s.pass == null) s.pass = kept
        }
    }

    /** [key]'s session arrived with [security]: proven in the background when it asks for that. */
    fun onSession(key: String, security: SecuritySettings) {
        synchronized(lock) { state(key).security = security }
        changed(key)
        ensure(key)
    }

    /** What [key]'s agent asks of its users, as its session said; null before the session. */
    fun security(key: String): SecuritySettings? = synchronized(lock) { states[key]?.security }

    /** The app is in front again: a pass due for renewal is renewed, and a proof that was waiting for this tries again. */
    fun foreground() {
        val keys = synchronized(lock) {
            for (s in states.values) {
                s.notAllowed = false
                if (s.waitForeground) {
                    s.waitForeground = false
                    s.failures = 0
                }
            }
            states.keys.toList()
        }
        keys.forEach { ensure(it) }
    }

    /** A call Graine rings has arrived for [key]: its app proof is refreshed now, before the person answers (§6). */
    fun ring(key: String) = ensure(key)

    // ── the request's `app` ─────────────────────────────────────────────────

    /**
     * The request's `app` (§1.1): the declaration, the pass held for [key]
     * when it has not lapsed — or lapsed less than 15 minutes ago, for a
     * request about a call that rang ([aboutACall]); any pass at all with
     * [anyPass] — and the debug token in development mode. Null when the
     * package name cannot be read: then no `app` is sent.
     */
    fun appJson(key: String?, aboutACall: Boolean, anyPass: Boolean = false): JSONObject? {
        val o = declared?.json() ?: return null
        if (key != null) passFor(key, aboutACall, anyPass)?.let { o.put("pass", it.pass) }
        debugToken()?.let { o.put("debugToken", it) }
        return o
    }

    private fun passFor(key: String, aboutACall: Boolean, anyPass: Boolean): AppPass? = synchronized(lock) {
        val p = states[key]?.pass ?: return@synchronized null
        val now = clock()
        when {
            now < p.expiresAt -> p
            aboutACall && now < p.expiresAt + RING_GRACE_MS -> p
            anyPass -> p
            else -> null
        }
    }

    /** The pass held for [key] that has not lapsed. Under [lock]. */
    private fun validPass(s: KeyState): AppPass? = s.pass?.takeIf { clock() < it.expiresAt }

    // ── answers ─────────────────────────────────────────────────────────────

    /**
     * An answer's `verification` (§2.2) for a request that carried [sentPass]:
     * a new pass is kept; Graine saying the app is unverified although that
     * pass went with the request means the pass is no good, and it goes.
     */
    fun heard(key: String, verification: JSONObject, sentPass: String?) {
        val verdict = GraineAppVerdict.fromWire(verification.opt("app")) ?: return
        val unverified = synchronized(lock) {
            val s = state(key)
            if (!keepPass(s, verification, verdict)) {
                if (verdict == GraineAppVerdict.UNVERIFIED && sentPass != null && s.pass?.pass == sentPass) dropPass(s)
                s.heard = verdict
                s.heardReason = null
            }
            verdict == GraineAppVerdict.UNVERIFIED
        }
        changed(key)
        if (unverified) ensure(key)
    }

    /**
     * A route refused [key]'s request (§2.3): `app_not_allowed` (no proof
     * until the next foreground — Graine does not take this app at all), or
     * `app_not_verified` with its [reason] (a pass that went with it and was
     * refused as lapsed or invalid goes).
     */
    fun refused(key: String, code: String, reason: String?, sentPass: String?) {
        synchronized(lock) {
            val s = state(key)
            when (code) {
                GraineErrorCode.APP_NOT_ALLOWED.wire -> s.notAllowed = true
                GraineErrorCode.APP_NOT_VERIFIED.wire -> {
                    s.heard = GraineAppVerdict.UNVERIFIED
                    s.heardReason = reason?.takeIf { it.isNotBlank() }
                    if (sentPass != null && s.pass?.pass == sentPass) dropPass(s)
                }
                else -> return
            }
        }
        changed(key)
    }

    /** `verification` with a pass: kept with its expiry and verdict. False when it carries none. Under [lock]. */
    private fun keepPass(s: KeyState, v: JSONObject, verdict: GraineAppVerdict): Boolean {
        if (verdict != GraineAppVerdict.VERIFIED && verdict != GraineAppVerdict.TEST_BUILD) return false
        val pass = (v.opt("appPass") as? String)?.takeIf { it.isNotBlank() } ?: return false
        val ttl = Js.finite(v.opt("appPassExpiresIn"))?.takeIf { it > 0 } ?: return false
        val now = clock()
        val p = AppPass(pass, now + (ttl * 1000).toLong(), verdict, installId())
        s.pass = p
        s.heard = verdict
        s.heardReason = null
        s.provenAt = now
        s.slot?.let { store.save(it, p) }
        return true
    }

    private fun dropPass(s: KeyState) {
        s.pass = null
        s.slot?.let { store.save(it, null) }
    }

    // ── the verdict ─────────────────────────────────────────────────────────

    /**
     * [key]'s app verdict and Graine's reason: off until its session asks for
     * a verified app; then the pass held, the proof on its way, or what Graine
     * last said.
     */
    fun verdict(key: String): Pair<GraineAppVerdict, String?> = synchronized(lock) {
        val s = states[key] ?: return@synchronized GraineAppVerdict.OFF to null
        if (s.security?.provesApp != true || s.heard == GraineAppVerdict.OFF) return@synchronized GraineAppVerdict.OFF to null
        validPass(s)?.let { return@synchronized it.verdict to null }
        if (s.flight != null) return@synchronized GraineAppVerdict.CHECKING to null
        s.heard?.let { return@synchronized it to s.heardReason }
        GraineAppVerdict.UNVERIFIED to null
    }

    // ── proving ─────────────────────────────────────────────────────────────

    /** A proof may start for [s] now. Under [lock]. */
    private fun mayProve(s: KeyState): Boolean =
        s.baseUrl != null && s.http != null && !debugTokenInUse() && !s.notAllowed && !backingOff(s)

    private fun backingOff(s: KeyState): Boolean = s.waitForeground || clock() < s.resumeAt

    /** No pass, or one with less than 6 hours left. Under [lock]. */
    private fun due(s: KeyState): Boolean = s.pass?.let { it.expiresAt - clock() < REFRESH_BELOW_MS } ?: true

    /** In the background, when [key]'s agent wants the app proven and no proof is on its way: none held, or one due for renewal. */
    fun ensure(key: String) {
        val (s, f) = synchronized(lock) {
            val s = states[key] ?: return
            if (s.security?.provesApp != true || !mayProve(s) || !due(s) || s.flight != null) return
            val f = Flight()
            s.flight = f
            s to f
        }
        changed(key)
        worker.post { runProof(s, f) }
    }

    /**
     * Before a ticket or rtc-session for [key]: under "enforce", with no
     * pass held, the proof on its way — started now if there is none — is
     * waited for, at most [ENFORCE_WAIT_MS]. The request goes either way.
     */
    fun beforeConversation(key: String) {
        val wait = synchronized(lock) {
            val s = states[key] ?: return
            if (s.security?.enforcesApp != true || debugTokenInUse() || validPass(s) != null) return
            s.flight
        } ?: run {
            ensure(key)
            synchronized(lock) { states[key]?.flight }
        } ?: return
        wait.done.await(enforceWaitMs, TimeUnit.MILLISECONDS)
    }

    /**
     * A request for [key] was refused `app_not_verified`: prove now — on this
     * thread, or by waiting for the proof already on its way — and say whether
     * a pass is held after it, so the request is sent once more. Not in
     * development mode, not while backing off, and not again right after a
     * proof that gave a pass Graine then refused.
     */
    fun proveForRetry(key: String): Boolean {
        val (s, f, mine) = synchronized(lock) {
            val s = states[key] ?: return false
            if (!mayProve(s) || clock() - s.provenAt < REPROVE_AFTER_MS) return false
            val existing = s.flight
            if (existing != null) Triple(s, existing, false) else Triple(s, Flight().also { s.flight = it }, true)
        }
        if (mine) {
            changed(key)
            runProof(s, f)
        } else {
            f.done.await(proofWaitMs, TimeUnit.MILLISECONDS)
        }
        return synchronized(lock) { validPass(s) != null }
    }

    /**
     * prepareAppVerification(): prove now (or join the proof on its way)
     * unless a pass with more than 6 hours left is held, the agent does not
     * ask for a verified app, this is a debug build, or proofs are backing
     * off — and hand [done] the verdict, on the proof's thread.
     */
    fun prepare(key: String, done: (GraineAppVerdict) -> Unit) {
        var start: Pair<KeyState, Flight>? = null
        val answerNow = synchronized(lock) {
            val s = states[key] ?: return@synchronized true
            if (s.security?.provesApp != true || !mayProve(s) || !due(s)) return@synchronized true
            val f = s.flight ?: Flight().also { s.flight = it; start = s to it }
            f.then += { done(verdict(key).first) }
            false
        }
        if (answerNow) {
            worker.post { done(verdict(key).first) }
            return
        }
        start?.let { (s, f) ->
            changed(key)
            worker.post { runProof(s, f) }
        }
    }

    /** Run the proof [f] for [s], then let everyone waiting on it go. */
    private fun runProof(s: KeyState, f: Flight) {
        try {
            prove(s)
        } catch (t: Throwable) {
            synchronized(lock) { failed(s, null) }
        }
        val then = synchronized(lock) {
            if (s.flight === f) s.flight = null
            ArrayList(f.then).also { f.then.clear() }
        }
        f.done.countDown()
        changed(s.key)
        then.forEach { runCatching { it() } }
    }

    /** The outcome of one request of a proof. */
    private sealed class Step {
        class Challenge(val value: String) : Step()
        object Proven : Step()
        object NewChallenge : Step()
        class Refused(val reason: String?) : Step()
        object NotAllowed : Step()
        class BackOff(val retryAfterMs: Long?) : Step()
    }

    /** One proof, on this thread: challenge → Play Integrity → app-verify (§3.1, §3.2, §3.5). */
    private fun prove(s: KeyState) {
        val prover = integrity
        if (prover == null) {
            if (!libraryMissingTold) {
                libraryMissingTold = true
                log("This agent asks for a verified app, and Play Integrity is not in this app: add " +
                    "implementation(\"com.google.android.play:integrity:1.4.0\") to prove it. Until then it is unverified.")
            }
            // Nothing at run time brings the library: no proof is tried again before the next foreground.
            synchronized(lock) { settle(s, GraineAppVerdict.UNVERIFIED, null); s.waitForeground = true }
            return
        }
        val cloud = synchronized(lock) { s.security?.cloudProjectNumber }
        // A second challenge only when Graine says the first was spent.
        for (attempt in 0..1) {
            val challenge = when (val c = challenge(s)) {
                is Step.Challenge -> c.value
                else -> return conclude(s, c)
            }
            when (val a = prover.token(challenge, cloud, INTEGRITY_TIMEOUT_MS)) {
                is IntegrityAnswer.Token -> {
                    val step = verify(s, JSONObject().put("kind", "play").put("token", a.token), challenge)
                    if (step is Step.NewChallenge && attempt == 0) continue
                    return conclude(s, step)
                }
                is IntegrityAnswer.Unreachable -> {
                    // The device cannot reach Google: a pass held — even a lapsed one — may carry it through (outage grace).
                    val graced = a.outage && grace(s)
                    synchronized(lock) { if (!graced) settle(s, GraineAppVerdict.UNVERIFIED, null); failed(s, null) }
                    return
                }
                is IntegrityAnswer.Unavailable -> {
                    if (!unavailableTold) {
                        unavailableTold = true
                        log("Play Integrity cannot give this device a token (${a.why}): the app is unverified on it.")
                    }
                    // The device will not change in a moment: tried again at the next foreground.
                    synchronized(lock) { settle(s, GraineAppVerdict.UNVERIFIED, null); s.waitForeground = true }
                    return
                }
            }
        }
    }

    /** What a step that ends the proof means for [s]. */
    private fun conclude(s: KeyState, step: Step) = synchronized(lock) {
        when (step) {
            Step.Proven -> succeeded(s)
            is Step.Refused -> { settle(s, GraineAppVerdict.UNVERIFIED, step.reason); failed(s, null) }
            Step.NotAllowed -> { s.notAllowed = true; settle(s, GraineAppVerdict.UNVERIFIED, null) }
            is Step.BackOff -> failed(s, step.retryAfterMs)
            Step.NewChallenge -> { settle(s, GraineAppVerdict.UNVERIFIED, "challenge"); failed(s, null) }
            is Step.Challenge -> Unit
        }
    }

    /** Graine's verdict for [s] when no pass came. Under [lock]. */
    private fun settle(s: KeyState, verdict: GraineAppVerdict, reason: String?) {
        s.heard = verdict
        s.heardReason = reason
    }

    private fun succeeded(s: KeyState) {
        s.failures = 0
        s.resumeAt = 0L
        s.waitForeground = false
        s.retryTimer?.invoke()
        s.retryTimer = null
    }

    /** A proof failed: the next waits 30 s, 2 min, 10 min (or the server's Retry-After, if longer), then for the next foreground. Under [lock]. */
    private fun failed(s: KeyState, retryAfterMs: Long?) {
        s.failures++
        s.retryTimer?.invoke()
        s.retryTimer = null
        val step = BACKOFF_MS.getOrNull(s.failures - 1)
        if (step == null) {
            s.waitForeground = true
            s.resumeAt = 0L
            return
        }
        val wait = maxOf(step, retryAfterMs ?: 0L)
        s.resumeAt = clock() + wait
        s.retryTimer = worker.schedule(wait) {
            synchronized(lock) { s.retryTimer = null }
            ensure(s.key)
        }
    }

    /** `POST app-verify/challenge` (§3.1). */
    private fun challenge(s: KeyState): Step {
        val body = JSONObject()
        installId()?.let { body.put("installId", it) }
        val reply = call(s, CHALLENGE_PATH, body) ?: return Step.BackOff(null)
        val o = runCatching { JSONObject(reply.body) }.getOrNull() ?: JSONObject()
        if (reply.code in 200..299) {
            val c = (o.opt("challenge") as? String)?.takeIf { CHALLENGE.matches(it) } ?: return Step.BackOff(null)
            return Step.Challenge(c)
        }
        return refusal(reply, o)
    }

    /** `POST app-verify` with [proof] (§3.2): a pass, or why not. */
    private fun verify(s: KeyState, proof: JSONObject, challenge: String?, anyPass: Boolean = false): Step {
        val body = JSONObject()
        installId()?.let { body.put("installId", it) }
        challenge?.let { body.put("challenge", it) }
        body.put("proof", proof)
        val reply = call(s, VERIFY_PATH, body, anyPass) ?: return Step.BackOff(null)
        val o = runCatching { JSONObject(reply.body) }.getOrNull() ?: JSONObject()
        if (reply.code in 200..299) {
            val v = o.opt("verification") as? JSONObject ?: return Step.Refused(null)
            val verdict = GraineAppVerdict.fromWire(v.opt("app")) ?: return Step.Refused(null)
            return synchronized(lock) { if (keepPass(s, v, verdict)) Step.Proven else Step.Refused(null) }
        }
        return refusal(reply, o)
    }

    /**
     * The outage grace (§3.2): with a pass held — a lapsed one too, unless
     * it lapsed so long ago that its proof is past the grace's 7 days —
     * `proof: {kind: "unavailable"}` with that pass in `app.pass`. True when
     * Graine gave a grace pass.
     */
    private fun grace(s: KeyState): Boolean {
        val held = synchronized(lock) { s.pass } ?: return false
        if (clock() - held.expiresAt > OUTAGE_GRACE_MS) return false
        return verify(s, JSONObject().put("kind", "unavailable"), challenge = null, anyPass = true) is Step.Proven
    }

    /** A refused app-verify request, as a step. */
    private fun refusal(reply: HttpReply, o: JSONObject): Step {
        val code = (o.opt("code") as? String)?.trim()
        val reason = (o.opt("reason") as? String)?.trim()?.takeIf { it.isNotEmpty() }
        return when {
            code == GraineErrorCode.APP_NOT_ALLOWED.wire -> Step.NotAllowed
            code == GraineErrorCode.APP_NOT_VERIFIED.wire && reason == "challenge" -> Step.NewChallenge
            code == GraineErrorCode.APP_NOT_VERIFIED.wire -> Step.Refused(reason)
            reply.code == 429 -> Step.BackOff(reply.retryAfterSeconds?.takeIf { it > 0 }?.times(1000))
            reply.code >= 500 -> Step.BackOff(null)
            else -> Step.Refused(reason ?: code)
        }
    }

    /** One app-verify request for [s]'s key, through the one door every request takes. Null when no answer came back. */
    private fun call(s: KeyState, path: String, body: JSONObject, anyPass: Boolean = false): HttpReply? {
        val (base, http) = synchronized(lock) { (s.baseUrl ?: return null) to (s.http ?: return null) }
        val url = base.trimEnd('/') + path
        body.put("publishableKey", s.key)
        // The agent's server token rides along: under "Require server tokens" app-verify needs one too (CLIENT-TOKENS.md §2.2).
        val tokens = AppShared.tokensFor(s.key)
        return try {
            val door = owner
            if (door != null) door.send(url, body, anyPass, EmbedRequest.withToken(tokens, newConnection = false) { json -> http.post(url, json) })
            else EmbedRequest.send(url, body, anyAppPass = anyPass, tokens = tokens) { json -> http.post(url, json) }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        const val CHALLENGE_PATH = "/api/embed/app-verify/challenge"
        const val VERIFY_PATH = "/api/embed/app-verify"
        private val CHALLENGE = Regex("^[A-Za-z0-9_-]{48}$")

        /** Play Integrity's own deadline (§3.5 2). */
        const val INTEGRITY_TIMEOUT_MS = 20_000L
        /** How long a conversation under "enforce" waits for a proof on its way (§2.1). */
        const val ENFORCE_WAIT_MS = 10_000L
        /** How long a refused request waits for a proof another thread is making. */
        const val PROOF_WAIT_MS = 30_000L
        /** A pass renewed in the background once less than this is left (§3.3: 6 h). */
        const val REFRESH_BELOW_MS = 6 * 3_600_000L
        /** A pass still sent this long after it lapsed with a request about a call that rang (§6: 15 min). */
        const val RING_GRACE_MS = 15 * 60_000L
        /** No grace is asked for with a pass that lapsed longer ago than this (§3.2: 7 days). */
        const val OUTAGE_GRACE_MS = 7 * 86_400_000L
        /** A refusal within this of a pass being given is not proven again: the pass was just made. */
        const val REPROVE_AFTER_MS = 60_000L
        /** Waits after proofs that failed in a row; after the last, the next foreground (§3.2). */
        val BACKOFF_MS = listOf(30_000L, 120_000L, 600_000L)
    }
}
