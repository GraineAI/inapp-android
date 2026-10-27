package ai.graine.inapp

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * WHAT EVERY AGENT IN THE APP SHARES — one app, one user, one device. An app
 * may run several agents, each with its own publishable key (a support agent
 * in Help, a sales agent on Plans): each owns its session, chat, voice,
 * actions and requests. What is the same whichever agent asks lives here: the
 * agents themselves, who the user is and their memory choice, the screen they
 * are on and what they just did there, and the one native call engine.
 * Main-thread writes; any-thread reads.
 */
internal object AppShared {
    private val agents = LinkedHashMap<String, GraineInApp>()

    /** An agent's name as it is kept: trimmed, and the default one when there is none. */
    fun key(name: String): String = name.trim().ifEmpty { GraineInApp.DEFAULT }

    /** The agent [name], made by [make] when there is none yet; true when it was made now. */
    fun agent(name: String, make: (key: String) -> GraineInApp): Pair<GraineInApp, Boolean> = synchronized(agents) {
        val k = key(name)
        agents[k]?.let { return it to false }
        val g = make(k)
        agents[k] = g
        g to true
    }

    fun find(name: String): GraineInApp? = synchronized(agents) { agents[key(name)] }

    /** The server tokens of the agent whose publishable key this is: what a request made for that key carries (CLIENT-TOKENS.md §2.2). */
    fun tokensFor(publishableKey: String): ClientTokens? =
        synchronized(agents) { agents.values.firstOrNull { it.config.publishableKey == publishableKey } }?.clientTokens

    /** Every configured agent, in the order they were made. */
    fun all(): List<GraineInApp> = synchronized(agents) { agents.values.toList() }

    /** Every configured agent by name, in the order they were made. */
    fun named(): Map<String, GraineInApp> = synchronized(agents) { LinkedHashMap(agents) }

    /** The default agent, else the first one made: for an app that only named its agents. */
    fun primary(): GraineInApp? = synchronized(agents) { agents[GraineInApp.DEFAULT] ?: agents.values.firstOrNull() }

    // ── who the user is ────────────────────────────────────────────────────

    /** The traits the last identify() handed over, raw; null signed out (or never identified). An agent made later starts with them. */
    @Volatile var traits: Map<String, Any?>? = null
    /** The memory choice made for the current user this launch; null leaves the choice kept on the device. */
    @Volatile var memory: Boolean? = null
    /** The traits [memory] was chosen for: an agent made later takes it on only for the user they name. */
    @Volatile var memoryFor: Map<String, Any?>? = null

    // ── the screen ─────────────────────────────────────────────────────────

    @Volatile var screen: GraineScreen? = null
    @Volatile var screenSince: Long = System.currentTimeMillis()
    /** When the last tap was recorded, uptime ms: at most two a second reach the context. */
    @Volatile var lastTapAt: Long = Long.MIN_VALUE / 2
    /** The app's recent events, oldest first, at most ten. */
    private val recentEvents = ArrayDeque<JSONObject>()

    fun events(): List<JSONObject> = synchronized(recentEvents) { recentEvents.toList() }

    fun record(event: JSONObject) {
        synchronized(recentEvents) {
            recentEvents.addLast(event)
            while (recentEvents.size > 10) recentEvents.removeFirst()
        }
    }

    // ── a calls host's server tokens (CLIENT-TOKENS.md §2.4) ───────────────

    /** A token a calls host pushed: [token] null is "none" — still a push, which puts tokens in play. */
    class Pushed(val token: String?)

    /** The last token the calls host pushed; null before it pushed any. An agent made later starts with it. */
    @Volatile var pushedToken: Pushed? = null
    /** How the calls host asks its JavaScript or Dart manager for a token; null with no host. An agent made later is given it. */
    @Volatile var tokenAsk: (() -> Unit)? = null

    // ── the native call engine ─────────────────────────────────────────────

    @Volatile private var calls: GraineCalls? = null

    /** The one call engine of the process, made by the first agent that asks for it. */
    fun calls(home: GraineInApp): GraineCalls = calls ?: synchronized(this) { calls ?: GraineCalls(home).also { calls = it } }

    /** The call engine if anything made it: nothing makes it just to ask whether a call is live. */
    fun callsIfMade(): GraineCalls? = calls

    /**
     * Which configured agent an agent id belongs to: the one whose session
     * (this launch's, or the last one kept on the device) has it as its agent
     * or its web-call twin. Null when none does.
     */
    fun agentFor(agentId: String?): GraineInApp? {
        val id = agentId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return all().firstOrNull { id in it.knownAgentIds() }
    }

    // ── what Graine has proven: this app and this user ─────────────────────

    @Volatile private var proofs: Proofs? = null

    /** The process's proofs (EmbedRequest.kt), made by the first agent with [make]. */
    fun proofs(make: () -> Proofs): Proofs = proofs ?: synchronized(this) { proofs ?: make().also { proofs = it } }

    /** The proofs if an agent made them: before any agent, requests carry nothing of them. */
    fun proofsIfMade(): Proofs? = proofs

    /** What the agents with [publishableKey] (null: every agent) show of their verification may have changed. */
    fun verificationChanged(publishableKey: String?) {
        for (g in all()) if (publishableKey == null || g.config.publishableKey == publishableKey) g.verificationChanged()
    }

    /** The identity token changed: what each agent prepared or kept with the old one goes. */
    fun identityTokenChanged() {
        for (g in all()) g.identityTokenChanged()
    }

    /** The trait the agent behind [publishableKey] identifies users by; null before its session (or with no such agent). */
    fun identityFieldFor(publishableKey: String): String? =
        all().firstOrNull { it.config.publishableKey == publishableKey }?.identityField()

    /** Forget every agent and everything they shared: between tests, which make agents of their own. */
    fun reset() {
        synchronized(agents) { agents.clear() }
        traits = null
        memory = null
        memoryFor = null
        screen = null
        screenSince = System.currentTimeMillis()
        lastTapAt = Long.MIN_VALUE / 2
        synchronized(recentEvents) { recentEvents.clear() }
        synchronized(this) { calls = null; proofs = null }
        pushedToken = null
        tokenAsk = null
    }
}

/** The main thread, as the SDK posts to it: Android's main looper in an app, a test's own clock on the JVM. */
internal interface MainThread {
    fun post(r: Runnable): Boolean
    fun postDelayed(r: Runnable, delayMs: Long): Boolean
    fun removeCallbacks(r: Runnable)

    companion object {
        fun android(): MainThread = object : MainThread {
            private val handler = Handler(Looper.getMainLooper())
            override fun post(r: Runnable): Boolean = handler.post(r)
            override fun postDelayed(r: Runnable, delayMs: Long): Boolean = handler.postDelayed(r, delayMs)
            override fun removeCallbacks(r: Runnable) = handler.removeCallbacks(r)
        }
    }
}

/**
 * WHAT AN AGENT RUNS ON: the main thread, the network, the device's storage.
 * An app's agents run on Android's own; a test hands in fakes, so several real
 * agents run side by side on the JVM, offline (MultiAgentTest).
 */
internal class AgentEnv(
    val main: MainThread = MainThread.android(),
    /** Blocking work: requests and the session. Null: the agent's own background thread. */
    val io: Executor? = null,
    val http: HttpPost = PlatformHttp,
    /** The session route. The platform's own carries the server token; a test's fake here reads none. */
    val fetchSession: (baseUrl: String, publishableKey: String) -> JSONObject = PlatformSessionFetch,
    /** The chat's socket, its clock and where its ticket is minted. Null: the platform's. */
    val openSocket: ((String, List<String>, CallSocketListener) -> CallSocket)? = null,
    val scheduler: CallScheduler? = null,
    val ticketIo: ((() -> Unit) -> Unit)? = null,
    val device: DeviceStore? = null,
    val sessionCache: SessionCache? = null,
    /** Fetch the design's typefaces. Off in tests: nothing reaches a font host. */
    val fonts: Boolean = true,
    /** WebRTC calls' grants to mint at the telephony gateway (RtcDirect.kt): the process's one in an app, a fake in a test. */
    val directMint: RtcDirectMint = RtcDirectMint.shared,
    /** A WebRTC call's world — its thread, sockets, peer and HTTP — for this agent's calls. Null: the platform's; fakes in a test. */
    val rtcEnv: ((GraineInApp) -> RtcEnv)? = null,
    /** The phone under a native call — its lock screen, audio route, microphone and speaker. Null: the phone's own; a fake in a test. */
    val callPhone: CallPhone? = null,
    /** What proves this app and its user (EmbedRequest.kt), made by the process's first agent. Null: the app's own; fakes in a test. */
    val proofs: (() -> Proofs)? = null,
    /**
     * The session route with the server token it carries (CLIENT-TOKENS.md
     * §2.2), asked in place of [fetchSession]: a test that watches the token.
     * Null: the platform's own route, or [fetchSession] when a test faked it.
     */
    val fetchSessionWithToken: ((baseUrl: String, publishableKey: String, clientToken: String?) -> JSONObject)? = null,
    /** This agent's server tokens, from the app's provider. Null: the platform's clocks and threads; a test's own here. */
    val clientTokens: ((GraineTokenProvider?) -> ClientTokens)? = null,
)

/** The platform's session route, as [AgentEnv.fetchSession] defaults to it: the agent then asks it with the server token. */
internal val PlatformSessionFetch: (baseUrl: String, publishableKey: String) -> JSONObject = { b, k -> SessionFetch.fetchJson(b, k) }
