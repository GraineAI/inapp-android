package ai.graine.inapp

import android.app.Application
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.Executor

/**
 * THE CALLS HOST'S SERVER TOKENS (CLIENT-TOKENS.md §2.4) — React Native's and
 * Flutter's own token managers push their tokens through the host
 * (`setClientToken`), and hear `needClientToken` when a request of the SDK's
 * was refused for its token: the host's rules (CallsHostCore) over a real
 * agent, with the platform as Android's runs it (GraineInApp.pushClientToken,
 * askHostForTokens) and everything else faked.
 */
class ClientTokensHostTest {
    private class TestApp : Application() {
        override fun getPackageName(): String = "com.acme.app"
    }

    /** The main thread, run at once: a turn of the main loop is now. */
    private class NowMain : MainThread {
        override fun post(r: Runnable): Boolean { r.run(); return true }
        override fun postDelayed(r: Runnable, delayMs: Long): Boolean = true
        override fun removeCallbacks(r: Runnable) {}
    }

    private val dir = java.nio.file.Files.createTempDirectory("graine-host-tokens").toFile()
    private val requests = mutableListOf<Pair<String, JSONObject>>()
    private var reply: (String, JSONObject) -> HttpReply = { _, _ -> HttpReply(200, """{"calls":[]}""") }
    private val session = JSONObject("""{"agentId":"agent-1","webcallAgentId":"web-1","identity":{"field":"user_id"}}""")

    @Before fun fresh() = AppShared.reset()

    @After fun done() {
        AppShared.reset()
        dir.deleteRecursively()
    }

    private fun agent(key: String, provider: GraineTokenProvider? = null, name: String = GraineInApp.DEFAULT): GraineInApp {
        val env = AgentEnv(
            main = NowMain(),
            io = Executor { it.run() },
            http = HttpPost { url, json -> JSONObject(json).let { b -> requests += url to b; reply(url, b) } },
            fetchSession = { _, _ -> session },
            device = DeviceStore(File(dir, "device.json")),
            sessionCache = SessionCache(slotRead = { null }, slotWrite = { _, _ -> }),
            fonts = false,
            clientTokens = { p ->
                ClientTokens(p, schedule = { _, _ -> {} }, background = { it() }, connectionWaitMs = 500, pushWaitMs = 500)
            },
        )
        return GraineInApp.make(TestApp(), GraineConfig("https://www.graine.ai", key, tokenProvider = provider), name) { env }
    }

    /** Android's platform, as far as tokens go: every agent the app runs hears the push and asks through the host. */
    private inner class Platform(private val made: () -> GraineInApp) : CallsHostPlatform {
        override fun makeInstance(options: CallsHostOptions): CallsHostInstance {
            val g = made()
            return CallsHostInstance(g.config.publishableKey, g.config.baseUrl, g.config.debug)
        }
        override fun setClientToken(token: String?) = GraineInApp.pushClientToken(token)
        override fun askForClientTokens(ask: () -> Unit) = GraineInApp.askHostForTokens(ask)
        override fun nextTurn(block: () -> Unit) = block()
        override fun configureCalls(options: CallsHostOptions) {}
        override fun startCall(activity: Any?, reason: String?, variables: Map<String, Any?>): String? = null
        override fun answer() {}
        override fun decline() {}
        override fun end() {}
        override fun setMuted(muted: Boolean) {}
        override fun setSpeaker(on: Boolean) {}
        override fun identify(traits: Map<String, Any?>?) {}
        override fun setVariables(values: Map<String, Any?>) {}
        override fun setScreen(screen: GraineScreen?) {}
        override fun registerAction(name: String, handler: (JSONObject, (ActionResult) -> Unit) -> Unit): () -> Unit = {}
        override fun handlePush(data: Map<String, String>): Boolean = false
        override fun callFields(): CallsHostSnapshot = CallsHostSnapshot()
        override fun after(ms: Long, block: () -> Unit) {}
        override fun log(message: String) {}
    }

    private fun host(made: () -> GraineInApp) = CallsHostCore(Platform(made), CallsHostStore(read = { null }, write = {}))

    private val options = mapOf("publishableKey" to "pk_host", "displayName" to "Acme")

    private fun lastToken(path: String): String? = requests.last { it.first.endsWith(path) }.second.optString(ClientTokenRules.FIELD).ifEmpty { null }

    @Test fun aPushedTokenReachesTheRequestsAndARefusalAsksTheWrapperForAnother() {
        var g: GraineInApp? = null
        val core = host { agent("pk_host").also { g = it } }
        // Pushed before configure: held, and applied when the instance is made.
        core.setClientToken("host_1")
        core.configure(options)
        val events = mutableListOf<Pair<String, Map<String, Any?>>>()
        var pushes = 0
        core.addSink { event, payload ->
            events += event to payload
            // The wrapper's manager answers the event with a fresh token, as React Native's and Flutter's do.
            if (event == CallsHostEvent.NEED_CLIENT_TOKEN) core.setClientToken("host_${2 + pushes++}")
        }
        g!!.scheduledCalls { _, _ -> }
        assertEquals("host_1", lastToken(EmbedApi.LIST))
        assertTrue(events.none { it.first == CallsHostEvent.NEED_CLIENT_TOKEN })

        // Refused for its token: the wrapper is asked once, and the request goes once more with what it pushed.
        var lists = 0
        reply = { url, _ -> if (url.endsWith(EmbedApi.LIST) && ++lists == 1) HttpReply(401, """{"code":"token_expired"}""") else HttpReply(200, """{"calls":[]}""") }
        var heard: List<GraineScheduledCall>? = null
        g!!.scheduledCalls { calls, _ -> heard = calls }
        assertEquals(listOf(CallsHostEvent.NEED_CLIENT_TOKEN to emptyMap<String, Any?>()), events.filter { it.first == CallsHostEvent.NEED_CLIENT_TOKEN })
        val asked = requests.filter { it.first.endsWith(EmbedApi.LIST) }.takeLast(2).map { it.second.getString(ClientTokenRules.FIELD) }
        assertEquals(listOf("host_1", "host_2"), asked)
        assertEquals(emptyList<GraineScheduledCall>(), heard)

        // A value that is not a token is none.
        core.setClientToken(42)
        g!!.memories { _, _ -> }
        assertNull(g!!.clientTokens.newest)
    }

    @Test fun anAgentMadeLaterStartsWithThePushedTokenAndOneWithItsOwnProviderIgnoresIt() {
        val core = host { agent("pk_host") }
        core.configure(options)
        core.setClientToken("host_1")
        val later = agent("pk_later", name = "later")
        assertEquals("host_1", later.clientTokens.newest)
        val own = agent("pk_own", provider = GraineTokenProvider { done -> done(TestTokens.mint(1_793_000_000, 1_793_003_600, "ct_own")) }, name = "own")
        own.clientTokens.start()
        val mine = own.clientTokens.newest
        core.setClientToken("host_2")
        assertEquals("host_2", later.clientTokens.newest)
        assertEquals(mine, own.clientTokens.newest)
        assertTrue(mine!!.contains('.'))
    }

    @Test fun withNoPushNothingCarriesATokenButARefusalStillAsksTheWrapper() {
        var g: GraineInApp? = null
        val core = host { agent("pk_host").also { g = it } }
        core.configure(options)
        val asked = mutableListOf<String>()
        core.addSink { event, _ -> asked += event }
        g!!.scheduledCalls { _, _ -> }
        assertFalse(requests.last().second.has(ClientTokenRules.FIELD))
        reply = { _, _ -> HttpReply(401, """{"code":"token_required"}""") }
        var error: GraineError? = null
        g!!.scheduledCalls { _, e -> error = e }
        assertTrue(asked.contains(CallsHostEvent.NEED_CLIENT_TOKEN))
        assertEquals("nothing pushed in time: the refusal stands", GraineErrorCode.TOKEN_REQUIRED, error?.code)
    }
}
