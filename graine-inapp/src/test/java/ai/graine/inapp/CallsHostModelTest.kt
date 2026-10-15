package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/*
 * The calls host's rules — everything the React Native and Flutter packages
 * rely on when they drive the native call engine — proven on the JVM: the
 * options and their errors, the screen and an action's answer as JavaScript or
 * Dart send them, the snapshot, the events and where they go, setVariables as
 * a replacement, the kept options, and what each of the host's methods does,
 * against a fake platform standing in for Android. The iOS SDK proves the same
 * cases against the same rules.
 */

// ── helpers ─────────────────────────────────────────────────────────────────

/** Every event a sink received, in order. */
private class HostEventLog {
    val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    val names: List<String> get() = events.map { it.first }

    fun record(name: String, payload: Map<String, Any?>) {
        events += name to payload
    }

    fun payloads(name: String): List<Map<String, Any?>> = events.filter { it.first == name }.map { it.second }
}

/** A variables change as text: each value as the prompt will read it, or "<removed>". */
private fun describe(change: Map<String, Any?>): Map<String, String> =
    change.mapValues { (_, v) -> if (v == null) "<removed>" else VariableStore.flatten(mapOf("v" to v))["v"] ?: "<removed>" }

private fun refusal(raw: Any?): String? = try {
    CallsHostOptions.parse(raw)
    null
} catch (e: GraineCallsHostException) {
    assertEquals("invalid_options", e.code)
    e.message
}

/** Android, as the host sees it: every call recorded, the main loop's turns and timers run by hand. */
private class FakeCallsHostPlatform : CallsHostPlatform {
    var fields = CallsHostSnapshot()
    /** What makeInstance reports; the options' own key, base URL and debug setting when null. */
    var runningWith: CallsHostInstance? = null
    var nextCallId: String? = "call-1"
    var pushIsACall = true

    val made = mutableListOf<CallsHostOptions>()
    val configured = mutableListOf<CallsHostOptions>()
    val placed = mutableListOf<Triple<Any?, String?, Map<String, Any?>>>()
    var answers = 0
    var declines = 0
    var ends = 0
    val muted = mutableListOf<Boolean>()
    val speaker = mutableListOf<Boolean>()
    val identities = mutableListOf<Map<String, Any?>?>()
    val variableChanges = mutableListOf<Map<String, Any?>>()
    val screens = mutableListOf<GraineScreen?>()
    val handlers = LinkedHashMap<String, (JSONObject, (ActionResult) -> Unit) -> Unit>()
    val unregistered = mutableListOf<String>()
    val pushes = mutableListOf<Map<String, String>>()
    val timers = mutableListOf<Pair<Long, () -> Unit>>()
    val logs = mutableListOf<String>()
    private val turns = mutableListOf<() -> Unit>()

    val pendingTurns: Int get() = turns.size

    fun runTurns() {
        while (turns.isNotEmpty()) {
            val now = turns.toList()
            turns.clear()
            now.forEach { it() }
        }
    }

    fun fireTimers() {
        val now = timers.toList()
        timers.clear()
        now.forEach { it.second() }
    }

    override fun makeInstance(options: CallsHostOptions): CallsHostInstance {
        made += options
        return runningWith ?: CallsHostInstance(options.publishableKey, options.baseUrl, options.debug)
    }

    override fun configureCalls(options: CallsHostOptions) {
        configured += options
    }

    override fun startCall(activity: Any?, reason: String?, variables: Map<String, Any?>): String? {
        placed += Triple(activity, reason, variables)
        nextCallId?.let { fields = CallsHostSnapshot(GraineCallState.CONNECTING, it, GraineCall.Direction.OUTGOING, reason, null) }
        return nextCallId
    }

    /** The agent each call was placed with. */
    val placedWith = mutableListOf<String>()
    val agentsMade = mutableListOf<Map<String, CallsHostAgent>>()

    override fun startCall(activity: Any?, reason: String?, variables: Map<String, Any?>, agent: String): String? {
        placedWith += agent
        return startCall(activity, reason, variables)
    }

    override fun makeAgents(agents: Map<String, CallsHostAgent>, options: CallsHostOptions) { agentsMade += agents }

    /** The agent each call (by id) is with, as GraineCalls knows it. */
    val callAgents = HashMap<String, String>()
    override fun agentOf(callId: String): String? = callAgents[callId]

    override fun answer() { answers++ }
    override fun decline() { declines++ }
    override fun end() { ends++ }
    override fun setMuted(muted: Boolean) { this.muted += muted }
    override fun setSpeaker(on: Boolean) { speaker += on }
    override fun identify(traits: Map<String, Any?>?) { identities += traits }
    override fun setVariables(values: Map<String, Any?>) { variableChanges += values }
    override fun setScreen(screen: GraineScreen?) { screens += screen }

    override fun registerAction(name: String, handler: (JSONObject, (ActionResult) -> Unit) -> Unit): () -> Unit {
        handlers[name] = handler
        return {
            handlers.remove(name)
            unregistered += name
        }
    }

    override fun handlePush(data: Map<String, String>): Boolean {
        pushes += data
        return pushIsACall
    }

    override fun callFields(): CallsHostSnapshot = fields
    override fun nextTurn(block: () -> Unit) { turns += block }
    override fun after(ms: Long, block: () -> Unit) { timers += ms to block }
    override fun log(message: String) { logs += message }
}

/** A host core on the fake platform, with a store of its own and (by default) one sink listening. */
private class HostRig(listening: Boolean = true) {
    var kept: String? = null
    val platform = FakeCallsHostPlatform()
    val store = CallsHostStore(read = { kept }, write = { kept = it })
    val core = CallsHostCore(platform, store)
    val events = HostEventLog()
    val sink: Int? = if (listening) core.addSink(events::record) else null

    fun configure(extra: Map<String, Any?> = emptyMap()) =
        core.configure(mapOf<String, Any?>("publishableKey" to "pk_test_rig", "displayName" to "Acme") + extra)

    fun keep(options: Map<String, Any?> = mapOf("publishableKey" to "pk_test_rig", "displayName" to "Acme")) =
        store.saveOptions(CallsHostOptions.parse(options).json)

    fun handler(name: String) = platform.handlers[name] ?: error("no handler for $name")
}

// ── the options ─────────────────────────────────────────────────────────────

class CallsHostOptionsTest {
    private val minimal = mapOf<String, Any?>("publishableKey" to "pk_test_1", "displayName" to "Acme")

    private fun with(vararg extra: Pair<String, Any?>): Map<String, Any?> = minimal + extra

    @Test fun aKeyAndANameAreEnoughAndEverythingElseHasItsDefault() {
        val o = CallsHostOptions.parse(minimal)
        assertEquals("pk_test_1", o.publishableKey)
        assertEquals("Acme", o.displayName)
        assertEquals("https://www.graine.ai", o.baseUrl)
        assertNull(o.ringtoneSound)
        assertEquals(30_000L, o.ringTimeoutMs)
        assertNull(o.appVersion)
        assertEquals(CallsHostCallScreen.SDK, o.callScreen)
        assertEquals(GraineCallTransport.AUTO, o.callTransport)
        assertFalse(o.debug)
        assertNull(o.androidSmallIcon)
        assertNull(o.iosIconTemplate)
        assertFalse(o.iosIncludesCallsInRecents)
    }

    @Test fun everyOptionIsRead() {
        val o = CallsHostOptions.parse(with(
            "baseUrl" to "https://graine.example.com/",
            "ringtoneSound" to "ring",
            "ringTimeoutMs" to 45_000,
            "appVersion" to "2.4.0",
            "callScreen" to "app",
            "callTransport" to "webrtc",
            "debug" to true,
            "android" to mapOf("smallIcon" to "ic_call"),
            "ios" to mapOf("iconTemplate" to "CallIcon", "includesCallsInRecents" to true),
        ))
        assertEquals(GraineCallTransport.WEBRTC, o.callTransport)
        assertEquals("https://graine.example.com", o.baseUrl)
        assertEquals("ring", o.ringtoneSound)
        assertEquals(45_000L, o.ringTimeoutMs)
        assertEquals("2.4.0", o.appVersion)
        assertEquals(CallsHostCallScreen.APP, o.callScreen)
        assertTrue(o.debug)
        assertEquals("ic_call", o.androidSmallIcon)
        assertEquals("CallIcon", o.iosIconTemplate)
        assertTrue(o.iosIncludesCallsInRecents)
    }

    @Test fun theKeyAndTheNameAreRequired() {
        assertEquals("publishableKey is required", refusal(mapOf("displayName" to "Acme")))
        assertEquals("publishableKey is required", refusal(mapOf("publishableKey" to "", "displayName" to "Acme")))
        assertEquals("publishableKey is required", refusal(mapOf("publishableKey" to null, "displayName" to "Acme")))
        assertEquals("publishableKey is required", refusal(mapOf("publishableKey" to JSONObject.NULL, "displayName" to "Acme")))
        assertEquals("displayName is required", refusal(mapOf("publishableKey" to "pk_test_1")))
        assertEquals("displayName is required", refusal(mapOf("publishableKey" to "pk_test_1", "displayName" to "  \n")))
        assertEquals("publishableKey is required", refusal(null))
        assertEquals("publishableKey is required", refusal(JSONObject.NULL))
        assertEquals("options must be an object", refusal("pk_test_1"))
        assertEquals("options must be an object", refusal(listOf("pk_test_1")))
    }

    @Test fun theKeyIsTakenAsGivenAndOnlyTheNameIsTrimmed() {
        assertEquals(" pk_test_1 ", CallsHostOptions.parse(mapOf("publishableKey" to " pk_test_1 ", "displayName" to "Acme")).publishableKey)
        assertEquals("a key of spaces is not empty", "  ", CallsHostOptions.parse(mapOf("publishableKey" to "  ", "displayName" to "Acme")).publishableKey)
    }

    @Test fun aKnownKeyOfTheWrongTypeIsRefusedByName() {
        val cases = listOf(
            mapOf("publishableKey" to 42, "displayName" to "Acme") to "publishableKey must be a string",
            mapOf("publishableKey" to "pk_test_1", "displayName" to true) to "displayName must be a string",
            with("baseUrl" to 1) to "baseUrl must be a string",
            with("ringtoneSound" to listOf("ring")) to "ringtoneSound must be a string",
            with("ringTimeoutMs" to "30000") to "ringTimeoutMs must be a number",
            with("ringTimeoutMs" to true) to "ringTimeoutMs must be a number",
            with("appVersion" to 2.4) to "appVersion must be a string",
            with("callScreen" to 1) to "callScreen must be a string",
            with("callScreen" to "web") to "callScreen must be \"sdk\" or \"app\"",
            with("callScreen" to "SDK") to "callScreen must be \"sdk\" or \"app\"",
            with("callTransport" to true) to "callTransport must be a string",
            with("callTransport" to "rtc") to "callTransport must be \"auto\", \"websocket\" or \"webrtc\"",
            with("callTransport" to "WebRTC") to "callTransport must be \"auto\", \"websocket\" or \"webrtc\"",
            with("debug" to "yes") to "debug must be a boolean",
            with("debug" to 1) to "debug must be a boolean",
            with("android" to "ic_call") to "android must be an object",
            with("android" to mapOf("smallIcon" to 7)) to "android.smallIcon must be a string",
            with("ios" to listOf(true)) to "ios must be an object",
            with("ios" to mapOf("iconTemplate" to false)) to "ios.iconTemplate must be a string",
            with("ios" to mapOf("includesCallsInRecents" to "yes")) to "ios.includesCallsInRecents must be a boolean",
            with("ios" to mapOf("includesCallsInRecents" to 1)) to "ios.includesCallsInRecents must be a boolean",
        )
        for ((raw, message) in cases) assertEquals(message, message, refusal(raw))
    }

    @Test fun valuesAsTheBridgesDeliverThemAreRead() {
        // React Native hands every number over as a Double; Flutter as an Int, a Long or a Double.
        assertEquals(12_000L, CallsHostOptions.parse(with("ringTimeoutMs" to 12_000.0)).ringTimeoutMs)
        assertEquals(12_000L, CallsHostOptions.parse(with("ringTimeoutMs" to 12_000L)).ringTimeoutMs)
        assertEquals(1_501L, CallsHostOptions.parse(with("ringTimeoutMs" to 1_500.2)).ringTimeoutMs)
        assertEquals(1_501L, CallsHostOptions.parse(with("ringTimeoutMs" to 1_500.2f)).ringTimeoutMs)
        assertFalse(CallsHostOptions.parse(with("ios" to mapOf("includesCallsInRecents" to false))).iosIncludesCallsInRecents)
        assertTrue(CallsHostOptions.parse(with("debug" to true)).debug)
        // And the kept options come back through org.json.
        val fromJson = JSONObject().put("publishableKey", "pk_test_1").put("displayName", "Acme").put("debug", true)
            .put("ios", JSONObject().put("includesCallsInRecents", true))
        assertTrue(CallsHostOptions.parse(fromJson).iosIncludesCallsInRecents)
        assertTrue(CallsHostOptions.parse(fromJson).debug)
    }

    @Test fun aKeyThatIsNotAStringIsDropped() {
        val raw = hashMapOf<Any?, Any?>("publishableKey" to "pk_test_1", "displayName" to "Acme", 7 to "ignored", null to "too")
        assertEquals(CallsHostOptions.parse(minimal), CallsHostOptions.parse(raw))
    }

    @Test fun aRingTimeoutThatIsNotAboveZeroIsThirtySeconds() {
        for (value in listOf<Any?>(0, -5, -0.1, null, Double.NaN)) {
            assertEquals("$value", 30_000L, CallsHostOptions.parse(with("ringTimeoutMs" to value)).ringTimeoutMs)
        }
        assertEquals("above zero is never rounded to zero", 1L, CallsHostOptions.parse(with("ringTimeoutMs" to 0.2)).ringTimeoutMs)
        assertEquals("bounded, never an overflow", Int.MAX_VALUE.toLong(), CallsHostOptions.parse(with("ringTimeoutMs" to 1e300)).ringTimeoutMs)
        assertEquals(30_000L, CallsHostOptions.parse(with("ringTimeoutMs" to Double.POSITIVE_INFINITY)).ringTimeoutMs)
    }

    @Test fun theBaseUrlLosesItsTrailingSlashesAndABlankOneIsTheDefault() {
        assertEquals("https://graine.example.com", CallsHostOptions.parse(with("baseUrl" to "https://graine.example.com///")).baseUrl)
        assertEquals("https://graine.example.com", CallsHostOptions.parse(with("baseUrl" to " https://graine.example.com ")).baseUrl)
        assertEquals("https://www.graine.ai", CallsHostOptions.parse(with("baseUrl" to "  ")).baseUrl)
        assertEquals("https://www.graine.ai", CallsHostOptions.parse(with("baseUrl" to "/")).baseUrl)
        assertEquals("https://www.graine.ai", CallsHostOptions.parse(with("baseUrl" to null)).baseUrl)
    }

    @Test fun unknownKeysAreIgnoredAndNullIsAbsent() {
        val o = CallsHostOptions.parse(with(
            "future" to mapOf("anything" to 1),
            "ringtoneSound" to null,
            "ios" to null,
            "android" to JSONObject.NULL,
            "callScreen" to null,
            "callTransport" to null,
            "ringTimeoutMs" to null,
            "debug" to JSONObject.NULL,
        ))
        assertEquals(CallsHostOptions.parse(minimal), o)
    }

    @Test fun blankOptionalStringsAreNoneAndTheNameIsTrimmed() {
        val o = CallsHostOptions.parse(mapOf(
            "publishableKey" to "pk_test_1",
            "displayName" to "  Acme Assistant ",
            "ringtoneSound" to " ",
            "appVersion" to "",
            "ios" to mapOf("iconTemplate" to "  "),
            "android" to mapOf("smallIcon" to ""),
        ))
        assertEquals("Acme Assistant", o.displayName)
        assertNull(o.ringtoneSound)
        assertNull(o.appVersion)
        assertNull(o.iosIconTemplate)
        assertNull(o.androidSmallIcon)
    }

    @Test fun whatIsKeptReadsBackAsTheSameOptions() {
        val full = CallsHostOptions.parse(with(
            "baseUrl" to "https://graine.example.com",
            "ringtoneSound" to "ring",
            "ringTimeoutMs" to 45_000,
            "appVersion" to "2.4.0",
            "callScreen" to "app",
            "callTransport" to "websocket",
            "debug" to true,
            "android" to mapOf("smallIcon" to "ic_call"),
            "ios" to mapOf("iconTemplate" to "CallIcon", "includesCallsInRecents" to true),
        ))
        assertEquals(full, CallsHostOptions.parse(full.json))
        assertEquals("websocket", full.json["callTransport"])
        val plain = CallsHostOptions.parse(minimal)
        assertEquals(plain, CallsHostOptions.parse(plain.json))
        // Through the JSON text SharedPreferences keeps.
        assertEquals(full, CallsHostOptions.parse(JSONObject(Protocol.toJson(full.json).toString())))
        assertFalse("android is only kept when it says something", plain.json.containsKey("android"))
        assertFalse("the default transport is not written down", plain.json.containsKey("callTransport"))
    }

    @Test fun eachTransportIsReadByItsOwnWord() {
        assertEquals(GraineCallTransport.AUTO, CallsHostOptions.parse(with("callTransport" to "auto")).callTransport)
        assertEquals(GraineCallTransport.WEBSOCKET, CallsHostOptions.parse(with("callTransport" to "websocket")).callTransport)
        assertEquals(GraineCallTransport.WEBRTC, CallsHostOptions.parse(with("callTransport" to "webrtc")).callTransport)
    }

    @Test fun theAppsOtherAgentsAreNamedWithTheirOwnKeys() {
        val o = CallsHostOptions.parse(with(
            "baseUrl" to "https://graine.example.com/",
            "agents" to mapOf(
                " sales " to mapOf("publishableKey" to " pk_test_sales "),
                "care" to mapOf("publishableKey" to "pk_test_care", "baseUrl" to "https://care.example.com/"),
            ),
        ))
        assertEquals(listOf("sales", "care"), o.agents.keys.toList())
        assertEquals(CallsHostAgent("pk_test_sales", "https://graine.example.com"), o.agents["sales"])
        assertEquals(CallsHostAgent("pk_test_care", "https://care.example.com"), o.agents["care"])
        // Kept for a cold start, and read back the same.
        assertEquals(o, CallsHostOptions.parse(o.json))
        assertEquals(o, CallsHostOptions.parse(JSONObject(Protocol.toJson(o.json).toString())))
        assertFalse("agents are only kept when there are some", CallsHostOptions.parse(minimal).json.containsKey("agents"))
        assertTrue(CallsHostOptions.parse(with("agents" to null)).agents.isEmpty())
    }

    @Test fun anAgentThatCannotBeMadeIsRefusedByName() {
        val cases = listOf(
            "agents" to "sales" to "agents must be an object",
            "agents" to mapOf("sales" to "pk_test_sales") to "agents.sales must be an object",
            "agents" to mapOf("sales" to emptyMap<String, Any?>()) to "agents.sales.publishableKey is required",
            "agents" to mapOf("sales" to mapOf("publishableKey" to " ")) to "agents.sales.publishableKey is required",
            "agents" to mapOf("sales" to mapOf("publishableKey" to 7)) to "agents.sales.publishableKey must be a string",
            "agents" to mapOf("sales" to mapOf("publishableKey" to "pk", "baseUrl" to 7)) to "agents.sales.baseUrl must be a string",
            "agents" to mapOf("default" to mapOf("publishableKey" to "pk")) to "agents.default: the default agent is the top-level publishableKey",
            "agents" to mapOf(" " to mapOf("publishableKey" to "pk")) to "agents: an agent's name must not be empty",
        )
        for ((option, message) in cases) {
            try {
                CallsHostOptions.parse(with(option))
                fail("accepted $option")
            } catch (e: GraineCallsHostException) {
                assertEquals("invalid_options", e.code)
                assertEquals(message, e.message)
            }
        }
    }
}

// ── the screen, an action's answer, a call's request, a push ────────────────

class CallsHostValuesTest {
    @Test fun aWholeScreenBecomesAGraineScreen() {
        val raw = mapOf(
            "screen" to "kyc_documents",
            "title" to "Upload your documents",
            "journey" to mapOf("name" to "KYC", "step" to 2, "of" to 4, "next" to "review"),
            "fields" to listOf(
                mapOf("name" to "pan", "label" to "PAN", "value" to "ABCDE1234F", "status" to "invalid", "error" to "Not recognised"),
                mapOf("name" to "nickname", "optional" to true),
            ),
            "error" to "Something went wrong",
            "data" to mapOf("plan" to "Plus", "limit" to 5_000),
            "availableActions" to listOf("upload_document", "set_tenure"),
        )
        assertEquals(GraineScreen(
            screen = "kyc_documents",
            title = "Upload your documents",
            journey = GraineScreen.Journey("KYC", 2, 4, "review"),
            fields = listOf(
                GraineField("pan", "PAN", "ABCDE1234F", GraineField.Status.INVALID, "Not recognised"),
                GraineField("nickname", optional = true),
            ),
            error = "Something went wrong",
            data = mapOf("plan" to "Plus", "limit" to 5_000),
            availableActions = listOf("upload_document", "set_tenure"),
        ), CallsHostValues.screen(raw))
    }

    @Test fun noScreenNameIsNoScreen() {
        assertNull(CallsHostValues.screen(null))
        assertNull(CallsHostValues.screen(JSONObject.NULL))
        assertNull(CallsHostValues.screen("home"))
        assertNull(CallsHostValues.screen(emptyMap<String, Any?>()))
        assertNull(CallsHostValues.screen(mapOf("screen" to "")))
        assertNull(CallsHostValues.screen(mapOf("screen" to "   ")))
        assertNull(CallsHostValues.screen(mapOf("title" to "Home")))
        assertEquals("home", CallsHostValues.screen(mapOf("screen" to " home "))?.screen)
    }

    @Test fun aFieldValueIsTextWhateverItWasSentAs() {
        val raw = mapOf("screen" to "cart", "fields" to listOf(
            mapOf("name" to "total", "value" to 1299),
            mapOf("name" to "total_from_js", "value" to 1299.0),
            mapOf("name" to "rate", "value" to 12.5),
            mapOf("name" to "agreed", "value" to true),
            mapOf("name" to "note", "value" to ""),
            mapOf("name" to "missing", "value" to null),
            mapOf("name" to "list", "value" to listOf(1, 2)),
        ))
        assertEquals(listOf("1299", "1299", "12.5", "true", "", null, null), CallsHostValues.screen(raw)!!.fields.map { it.value })
    }

    @Test fun anUnknownStatusIsNoStatusAndAFieldWithoutANameIsLeftOut() {
        val raw = mapOf("screen" to "kyc", "fields" to listOf(
            mapOf("name" to "pan", "status" to "ok-ish"),
            mapOf("name" to "pin", "status" to "Filled"),
            mapOf("label" to "No name"),
            "not a field",
            mapOf("name" to "dob", "status" to "empty"),
        ))
        val screen = CallsHostValues.screen(raw)!!
        assertEquals(listOf("pan", "pin", "dob"), screen.fields.map { it.name })
        assertEquals(listOf(null, null, GraineField.Status.EMPTY), screen.fields.map { it.status })
    }

    @Test fun availableActionsKeepAbsentAndEmptyApart() {
        assertNull("absent allows every action", CallsHostValues.screen(mapOf("screen" to "home"))!!.availableActions)
        assertEquals("an empty list allows none", emptyList<String>(),
            CallsHostValues.screen(mapOf("screen" to "home", "availableActions" to emptyList<String>()))!!.availableActions)
        assertEquals(listOf("a", "b"),
            CallsHostValues.screen(mapOf("screen" to "home", "availableActions" to listOf("a", 7, "", "a", "b")))!!.availableActions)
        assertNull(CallsHostValues.screen(mapOf("screen" to "home", "availableActions" to "a"))!!.availableActions)
    }

    @Test fun aJourneyNeedsANameAndTakesNumbersOrNumericText() {
        val journey = CallsHostValues.screen(mapOf("screen" to "kyc", "journey" to mapOf("name" to "KYC", "step" to "2", "of" to 4.0)))!!.journey
        assertEquals(GraineScreen.Journey("KYC", 2, 4), journey)
        assertNull(CallsHostValues.screen(mapOf("screen" to "kyc", "journey" to mapOf("step" to 1)))!!.journey)
        assertEquals("half away from zero, as iOS rounds", 3, CallsHostValues.screen(mapOf("screen" to "kyc", "journey" to mapOf("name" to "K", "step" to 2.5)))!!.journey!!.step)
    }

    @Test fun screenDataIsKeptAsJSON() {
        val screen = CallsHostValues.screen(mapOf("screen" to "cart", "data" to mapOf("items" to listOf(1, 2), "total" to 12.5, "gift" to null, "bad" to Double.NaN)))!!
        assertEquals(mapOf("items" to listOf(1, 2), "total" to 12.5, "gift" to null, "bad" to null), screen.data)
    }

    @Test fun okRefusedAndErrorAreReadWithTheirMessageAndData() {
        val ok = CallsHostValues.actionResult(mapOf("status" to "ok", "message" to "Opened the bill.", "data" to mapOf("page" to "billing")))
        assertEquals(ActionResult(ActionResult.Status.OK, "Opened the bill.", mapOf("page" to "billing")), ok)
        assertEquals(ActionResult.Status.REFUSED, CallsHostValues.actionResult(mapOf("status" to "refused", "message" to "Locked")).status)
        assertEquals(ActionResult(ActionResult.Status.ERROR, ""), CallsHostValues.actionResult(mapOf("status" to "error")))
    }

    @Test fun anythingElseIsAnError() {
        val unknown = CallsHostValues.actionResult(mapOf("status" to "done", "message" to "Done!"))
        assertEquals(ActionResult.Status.ERROR, unknown.status)
        assertEquals("the app's own words are kept", "Done!", unknown.message)
        assertEquals("the words are exact", ActionResult.Status.ERROR, CallsHostValues.actionResult(mapOf("status" to "OK")).status)
        assertEquals("The app answered with an unknown status \"maybe\".", CallsHostValues.actionResult(mapOf("status" to "maybe")).message)
        val statusless = CallsHostValues.actionResult(mapOf("message" to "hi"))
        assertEquals(ActionResult.Status.ERROR, statusless.status)
        assertEquals("hi", statusless.message)
        assertEquals("The app answered without a status.", CallsHostValues.actionResult(emptyMap<String, Any?>()).message)
        assertEquals(ActionResult.error("The app answered without a result."), CallsHostValues.actionResult(null))
        assertEquals(ActionResult.Status.ERROR, CallsHostValues.actionResult("ok").status)
    }

    @Test fun aReasonAndVariables() {
        val (reason, variables) = CallsHostValues.callRequest(mapOf("reason" to " card_blocked ", "variables" to mapOf("order" to "A-1043")))
        assertEquals("card_blocked", reason)
        assertEquals("A-1043", variables["order"])
        val (none, noVariables) = CallsHostValues.callRequest(null)
        assertNull(none)
        assertTrue(noVariables.isEmpty())
        assertNull(CallsHostValues.callRequest(mapOf("reason" to " ")).first)
    }

    @Test fun aPushIsStringsWhateverArrives() {
        val data = CallsHostValues.pushData(mapOf(
            "type" to "agent_call", "call_id" to "c1", "attempt" to 2.0, "urgent" to true,
            "variables" to mapOf("order" to "A-1043"), "dropped" to null,
        ))
        assertEquals(mapOf(
            "type" to "agent_call", "call_id" to "c1", "attempt" to "2", "urgent" to "true",
            "variables" to "{\"order\":\"A-1043\"}",
        ), data)
        assertTrue(CallsHostValues.pushData(null).isEmpty())
        assertTrue(CallsHostValues.pushData(listOf("agent_call")).isEmpty())
    }

    @Test fun aRingtoneIsARawResourceNameWhateverIOSWouldCallIt() {
        assertEquals("ring", CallsHostValues.rawResourceName("ring"))
        assertEquals("ring", CallsHostValues.rawResourceName("ring.caf"))
        assertEquals("ring", CallsHostValues.rawResourceName("sounds/ring.mp3"))
        assertEquals("agent_ring", CallsHostValues.rawResourceName(" agent_ring "))
        assertNull(CallsHostValues.rawResourceName(".mp3"))
        assertNull(CallsHostValues.rawResourceName(" "))
        assertNull(CallsHostValues.rawResourceName(null))
    }

    @Test fun listedNamesAreStringsEachOnce() {
        assertEquals(listOf("open_screen", "set_theme"), CallsHostValues.names(listOf("open_screen", "", 7, "set_theme", "open_screen")))
        assertTrue(CallsHostValues.names(null).isEmpty())
        assertTrue(CallsHostValues.names("open_screen").isEmpty())
    }
}

// ── the snapshot and the events ─────────────────────────────────────────────

class CallsHostSnapshotTest {
    private val keys = setOf("state", "callId", "direction", "reason", "callerName", "muted", "speakerOn", "agentSpeaking",
        "caption", "connectedAt", "error", "agent", "transport", "transportFallbackReason")

    @Test fun everyKeyIsAlwaysThereAndIdleIsNullsAndFalses() {
        val json = CallsHostSnapshot().json
        assertEquals(keys, json.keys)
        assertEquals("idle", json["state"])
        for (key in listOf("callId", "direction", "reason", "callerName", "caption", "connectedAt", "error", "agent", "transport", "transportFallbackReason")) {
            assertTrue(key, json.containsKey(key) && json[key] == null)
        }
        for (key in listOf("muted", "speakerOn", "agentSpeaking")) assertEquals(key, false, json[key])
    }

    @Test fun aLiveCallShowsEverything() {
        val json = CallsHostSnapshot(
            state = GraineCallState.ACTIVE, callId = "c1", direction = GraineCall.Direction.INCOMING, reason = "payment_due",
            callerName = "Acme", muted = true, speakerOn = true, agentSpeaking = true,
            caption = CallsHostSnapshot.Caption(Role.AGENT, "Hi Asha", true), connectedAt = 1_760_000_000_123L, agent = "sales",
            transport = "websocket", transportFallbackReason = "no_answer",
        ).json
        assertEquals(keys, json.keys)
        assertEquals("the call's agent, by name", "sales", json["agent"])
        assertEquals("what the call's audio runs on", "websocket", json["transport"])
        assertEquals("why it is not WebRTC", "no_answer", json["transportFallbackReason"])
        assertEquals("active", json["state"])
        assertEquals("c1", json["callId"])
        assertEquals("incoming", json["direction"])
        assertEquals("payment_due", json["reason"])
        assertEquals("Acme", json["callerName"])
        assertEquals(true, json["muted"])
        assertEquals(true, json["speakerOn"])
        assertEquals(true, json["agentSpeaking"])
        assertEquals("milliseconds since the epoch", 1_760_000_000_123L, json["connectedAt"])
        assertEquals(mapOf("role" to "agent", "text" to "Hi Asha", "live" to true), json["caption"])
        assertNull(json["error"])
    }

    @Test fun atIdleOnlyTheErrorSurvives() {
        val stale = CallsHostSnapshot(
            state = GraineCallState.IDLE, callId = "c1", direction = GraineCall.Direction.OUTGOING, reason = "support",
            callerName = "Acme", muted = true, speakerOn = true, agentSpeaking = true,
            caption = CallsHostSnapshot.Caption(Role.CUSTOMER, "hello", false), connectedAt = 5L,
            error = "The microphone permission was not granted.", agent = "sales", transport = "webrtc",
            transportFallbackReason = "ice_timeout",
        )
        assertEquals(CallsHostSnapshot(error = "The microphone permission was not granted."), stale.settled)
        assertNull("no call, no agent", stale.json["agent"])
        assertNull("no call, no transport", stale.json["transport"])
        assertNull(stale.json["transportFallbackReason"])
        val json = stale.json
        assertNull(json["callId"])
        assertNull(json["caption"])
        assertEquals(false, json["muted"])
        assertEquals("The microphone permission was not granted.", json["error"])
    }

    @Test fun theStateIsOneWord() {
        assertEquals(listOf("idle", "ringing", "connecting", "active", "ended"), GraineCallState.values().map { CallsHostSnapshot.word(it) })
    }

    @Test fun aBlankIsNull() {
        val json = CallsHostSnapshot(state = GraineCallState.RINGING, callId = "c1", reason = "", callerName = "", error = "").json
        assertNull(json["callerName"])
        assertNull(json["reason"])
        assertNull(json["error"])
    }

    @Test fun whenTheCallConnectedIsWorkedOutOnceOnTheWallClock() {
        val connectedAt = CallsHostConnectedAt()
        assertNull("not connected yet", connectedAt.epochMs(0L, 9_000L, 1_000_000L))
        assertEquals(996_000L, connectedAt.epochMs(5_000L, 9_000L, 1_000_000L))
        assertEquals("the clocks drifting apart a millisecond later is not a change", 996_000L, connectedAt.epochMs(5_000L, 19_000L, 1_010_001L))
        assertEquals("the next call works it out again", 1_100_000L, connectedAt.epochMs(40_000L, 40_000L, 1_100_000L))
        assertNull(connectedAt.epochMs(0L, 50_000L, 1_200_000L))
    }
}

class CallsHostStateGateTest {
    @Test fun oneLookPerTurnAndOnlyWhenSomethingChanged() {
        val gate = CallsHostStateGate()
        assertTrue(gate.changed())
        assertFalse("a turn is already coming", gate.changed())
        assertNull("idle to idle is nothing", gate.flush(CallsHostSnapshot()))
        assertTrue("the turn was taken: the next change asks for another", gate.changed())
        val ringing = CallsHostSnapshot(state = GraineCallState.RINGING, callId = "c1")
        assertEquals(ringing, gate.flush(ringing))
        gate.changed()
        assertNull(gate.flush(ringing))
    }

    @Test fun whatIdleHidesIsNotAChange() {
        val gate = CallsHostStateGate()
        gate.changed()
        assertNull(gate.flush(CallsHostSnapshot(state = GraineCallState.IDLE, callId = "stale", muted = true)))
        gate.changed()
        assertEquals(CallsHostSnapshot(error = "No network."), gate.flush(CallsHostSnapshot(state = GraineCallState.IDLE, error = "No network.")))
    }
}

class CallsHostEventTest {
    @Test fun everyOutcomeHasItsShape() {
        fun payload(o: GraineCallOutcome) = CallsHostEvent.outcomePayload(o, "c1")
        assertEquals(mapOf("callId" to "c1", "outcome" to "answered"), payload(GraineCallOutcome.ANSWERED))
        assertEquals(mapOf("callId" to "c1", "outcome" to "declined"), payload(GraineCallOutcome.DECLINED))
        assertEquals(mapOf("callId" to "c1", "outcome" to "missed"), payload(GraineCallOutcome.MISSED))
        assertEquals(mapOf("callId" to "c1", "outcome" to "ended", "reason" to "local"), payload(GraineCallOutcome.ended(GraineCallOutcome.EndReason.LOCAL)))
        assertEquals(mapOf("callId" to "c1", "outcome" to "ended", "reason" to "remote"), payload(GraineCallOutcome.ended(GraineCallOutcome.EndReason.REMOTE)))
        assertEquals("an ended call carries its reason, never an error",
            mapOf("callId" to "c1", "outcome" to "ended", "reason" to "failed"),
            payload(GraineCallOutcome.ended(GraineCallOutcome.EndReason.FAILED, "connection reset")))
        assertEquals(mapOf("callId" to "c1", "outcome" to "failed", "error" to "No network."), payload(GraineCallOutcome.failed("No network.")))
    }

    @Test fun aRingSaysNullOutLoud() {
        val ring = CallsHostEvent.incomingPayload(GraineCall("c9", GraineCall.Direction.INCOMING, null, "delivery_delayed", emptyMap()))
        assertEquals(mapOf("callId" to "c9", "reason" to "delivery_delayed", "callerName" to null), ring)
        assertTrue(ring.containsKey("callerName"))
        val named = CallsHostEvent.incomingPayload(GraineCall("c9", GraineCall.Direction.INCOMING, "Acme", null, emptyMap()))
        assertEquals(mapOf("callId" to "c9", "reason" to null, "callerName" to "Acme"), named)
    }

    @Test fun aCallGraineRangSaysSoAndAnEndedCallSaysHowLongItLasted() {
        // The engine's outcome carries both; the event carries them on, in the keys React Native and Flutter read.
        val ended = GraineCallOutcome.ended(GraineCallOutcome.EndReason.REMOTE).copy(durationSeconds = 95, hosted = true)
        assertEquals(mapOf("callId" to "h1", "outcome" to "ended", "reason" to "remote", "durationSeconds" to 95, "hosted" to true),
            CallsHostEvent.outcomePayload(ended, "h1"))
        assertEquals("a length only on an ended call", mapOf("callId" to "h1", "outcome" to "answered", "hosted" to true),
            CallsHostEvent.outcomePayload(GraineCallOutcome.ANSWERED.copy(durationSeconds = 3, hosted = true), "h1"))
        assertEquals("not hosted: no key", mapOf("callId" to "c1", "outcome" to "ended", "reason" to "local", "durationSeconds" to 12),
            CallsHostEvent.outcomePayload(GraineCallOutcome.ended(GraineCallOutcome.EndReason.LOCAL).copy(durationSeconds = 12), "c1"))
        assertEquals(mapOf("callId" to "h1", "outcome" to "ringing", "hosted" to true, "agent" to "sales"), CallsHostEvent.ringingPayload("h1", "sales"))
        assertEquals(mapOf("callId" to "h1", "reason" to null, "callerName" to "Acme", "hosted" to true),
            CallsHostEvent.incomingPayload(GraineCall("h1", GraineCall.Direction.INCOMING, "Acme", null, emptyMap(), hosted = true)))
    }

    @Test fun anActionRequestCarriesJSONArguments() {
        val payload = CallsHostEvent.actionPayload("r1", "open_screen", mapOf("page" to "billing", "count" to 2, "when" to null))
        assertEquals(mapOf("requestId" to "r1", "name" to "open_screen", "arguments" to mapOf("page" to "billing", "count" to 2, "when" to null)), payload)
    }

    @Test fun anOutcomeARingAndAnActionNameTheirAgentWhenItIsKnown() {
        assertEquals(mapOf("callId" to "c1", "outcome" to "missed", "agent" to "sales"),
            CallsHostEvent.outcomePayload(GraineCallOutcome.MISSED, "c1", "sales"))
        assertEquals(mapOf("callId" to "c9", "reason" to null, "callerName" to null, "agent" to "default"),
            CallsHostEvent.incomingPayload(GraineCall("c9", GraineCall.Direction.INCOMING, null, null, emptyMap()), "default"))
        assertEquals(mapOf("requestId" to "r1", "name" to "raise_ticket", "arguments" to emptyMap<String, Any?>(), "agent" to "sales"),
            CallsHostEvent.actionPayload("r1", "raise_ticket", emptyMap(), "sales"))
        assertFalse("unknown: no key at all", CallsHostEvent.outcomePayload(GraineCallOutcome.MISSED, "c1").containsKey("agent"))
    }
}

// ── sinks ───────────────────────────────────────────────────────────────────

class CallsHostSinksTest {
    @Test fun everyEventReachesEverySink() {
        val sinks = CallsHostSinks()
        val a = HostEventLog()
        val b = HostEventLog()
        sinks.add(a::record)
        sinks.add(b::record)
        sinks.emit(CallsHostEvent.STATE, mapOf("state" to "ringing"))
        sinks.emit(CallsHostEvent.OUTCOME, mapOf("callId" to "c1"))
        assertEquals(listOf("state", "outcome"), a.names)
        assertEquals(listOf("state", "outcome"), b.names)
        assertEquals(2, sinks.count)
    }

    @Test fun outcomesAndRingsWithNobodyListeningWaitForTheFirstSink() {
        val sinks = CallsHostSinks()
        sinks.emit(CallsHostEvent.INCOMING, mapOf("callId" to "c1"))
        sinks.emit(CallsHostEvent.STATE, mapOf("state" to "ringing"))
        sinks.emit(CallsHostEvent.OUTCOME, mapOf("callId" to "c1", "outcome" to "answered"))
        assertEquals("the state never waits", 2, sinks.queued.size)
        val first = HostEventLog()
        val second = HostEventLog()
        sinks.add(first::record)
        sinks.add(second::record)
        assertEquals(listOf("incoming", "outcome"), first.names)
        assertTrue("delivered once, to the first sink", second.names.isEmpty())
        assertTrue(sinks.queued.isEmpty())
    }

    @Test fun theQueueKeepsTheLastTwenty() {
        val sinks = CallsHostSinks()
        for (i in 0 until 25) sinks.emit(CallsHostEvent.OUTCOME, mapOf("callId" to "c$i"))
        val log = HostEventLog()
        sinks.add(log::record)
        assertEquals(20, log.events.size)
        assertEquals("c5", log.events.first().second["callId"])
        assertEquals("c24", log.events.last().second["callId"])
    }

    @Test fun onceTheLastSinkLeavesEventsWaitAgain() {
        val sinks = CallsHostSinks()
        val log = HostEventLog()
        val token = sinks.add(log::record)
        sinks.remove(token)
        sinks.emit(CallsHostEvent.OUTCOME, mapOf("callId" to "c1"))
        assertTrue(log.events.isEmpty())
        assertEquals(1, sinks.queued.size)
        assertFalse(sinks.contains(token))
    }

    @Test fun anActionGoesOnlyToTheSinkThatLastListedActions() {
        val sinks = CallsHostSinks()
        val main = HostEventLog()
        val background = HostEventLog()
        val a = sinks.add(main::record)
        val b = sinks.add(background::record)
        assertFalse("nobody listed actions", sinks.emitAction(mapOf("requestId" to "r0")))
        sinks.listedActions(listOf("open_screen"), a)
        sinks.listedActions(emptyList(), b)
        assertEquals("an empty list takes nothing over", a, sinks.actionSink)
        assertTrue(sinks.emitAction(mapOf("requestId" to "r1")))
        assertEquals(listOf("action"), main.names)
        assertTrue(background.names.isEmpty())
        sinks.listedActions(listOf("open_screen"), b)
        assertTrue(sinks.emitAction(mapOf("requestId" to "r2")))
        assertEquals(listOf("action"), background.names)
        sinks.remove(b)
        assertFalse("the sink that answers is gone: refused at once", sinks.emitAction(mapOf("requestId" to "r3")))
        assertEquals("never handed to another sink", listOf("action"), main.names)
    }

    @Test fun actionsListedByACallerWithNoSinkAreRefused() {
        val sinks = CallsHostSinks()
        val log = HostEventLog()
        val token = sinks.add(log::record)
        sinks.listedActions(listOf("open_screen"), token)
        sinks.listedActions(listOf("open_screen"), null)
        assertFalse(sinks.emitAction(mapOf("requestId" to "r1")))
        assertTrue(log.events.isEmpty())
    }

    @Test fun anActionRequestNeverWaitsInTheQueue() {
        val sinks = CallsHostSinks()
        sinks.listedActions(listOf("open_screen"), 1)
        assertFalse(sinks.emitAction(mapOf("requestId" to "r1")))
        assertTrue(sinks.queued.isEmpty())
    }
}

// ── variables ───────────────────────────────────────────────────────────────

class CallsHostVariablesTest {
    @Test fun theFirstMapIsSetAsIs() {
        val variables = CallsHostVariables()
        assertEquals(mapOf("plan" to "Plus", "cart" to "1299"), describe(variables.replace(mapOf("plan" to "Plus", "cart" to 1299))))
        assertEquals(setOf("plan", "cart"), variables.keys)
    }

    @Test fun aKeyTheLastMapSetAndThisOneLeavesOutIsRemoved() {
        val variables = CallsHostVariables()
        variables.replace(mapOf("plan" to "Plus", "cart" to 1299))
        assertEquals(mapOf("plan" to "Gold", "cart" to "<removed>"), describe(variables.replace(mapOf("plan" to "Gold"))))
        assertEquals("removed once, not forever", mapOf("plan" to "Gold"), describe(variables.replace(mapOf("plan" to "Gold"))))
    }

    @Test fun nullClearsEveryKeyTheHostSet() {
        val variables = CallsHostVariables()
        variables.replace(mapOf("plan" to "Plus", "cart" to 1299))
        assertEquals(mapOf("plan" to "<removed>", "cart" to "<removed>"), describe(variables.replace(null)))
        assertTrue(variables.keys.isEmpty())
        assertTrue(variables.replace(null).isEmpty())
    }

    @Test fun aValueThatCannotBeAVariableIsARemoval() {
        val variables = CallsHostVariables()
        variables.replace(mapOf("plan" to "Plus", "address" to "Pune"))
        val change = variables.replace(mapOf("plan" to null, "address" to mapOf("city" to "Pune"), "tags" to listOf("a"), "" to "x", "vip" to true))
        assertEquals(mapOf("plan" to "<removed>", "address" to "<removed>", "tags" to "<removed>", "vip" to "true"), describe(change))
        assertEquals(setOf("vip"), variables.keys)
    }

    @Test fun theSDKsVariablesBecomeExactlyTheMap() {
        // The SDK's own store merges; the replacement is what makes it exact.
        val store = VariableStore()
        val variables = CallsHostVariables()
        store.setRuntime(variables.replace(mapOf("plan" to "Plus", "cart" to 1299)))
        store.setRuntime(variables.replace(mapOf("plan" to "Gold", "city" to "Pune")))
        assertEquals(mapOf("plan" to "Gold", "city" to "Pune"), store.merged())
        store.setRuntime(variables.replace(mapOf("plan" to "Gold", "city" to listOf("Pune"))))
        assertEquals("a value that cannot be one is gone, not left as it was", mapOf("plan" to "Gold"), store.merged())
        store.setRuntime(variables.replace(null))
        assertTrue(store.merged().isEmpty())
    }
}

// ── actions ─────────────────────────────────────────────────────────────────

class CallsHostActionsTest {
    @Test fun aNewListSaysWhatToRegisterAndWhatToUnregister() {
        val actions = CallsHostActions()
        assertEquals(listOf("open_screen", "set_theme") to emptyList<String>(), actions.list(listOf("open_screen", "set_theme")))
        assertEquals(listOf("set_ring") to listOf("open_screen"), actions.list(listOf("set_theme", "set_ring")))
        assertEquals(listOf("set_theme", "set_ring"), actions.names)
    }

    @Test fun theFirstAnswerWinsAndALateOrUnknownOneIsIgnored() {
        val actions = CallsHostActions()
        val answers = mutableListOf<ActionResult.Status>()
        val id = actions.begin(1) { answers += it.status }
        assertEquals(1, actions.pendingCount)
        assertTrue(actions.resolve(id, ActionResult.ok("Done")))
        assertFalse(actions.resolve(id, ActionResult.error("again")))
        assertFalse(actions.resolve("no-such-request", ActionResult.ok()))
        assertEquals(listOf(ActionResult.Status.OK), answers)
        assertEquals(0, actions.pendingCount)
    }

    @Test fun everyRequestHasItsOwnId() {
        val actions = CallsHostActions()
        val a = actions.begin(1) {}
        val b = actions.begin(1) {}
        assertNotEquals(a, b)
        assertTrue(a.isNotEmpty())
    }

    @Test fun aSinkThatLeavesHasItsRequestsRefused() {
        val actions = CallsHostActions()
        val answers = mutableMapOf<String, ActionResult>()
        actions.begin(1) { answers["one"] = it }
        actions.begin(2) { answers["two"] = it }
        actions.refuse(1, "gone")
        assertEquals(ActionResult.refused("gone"), answers["one"])
        assertNull(answers["two"])
        assertEquals(1, actions.pendingCount)
    }
}

// ── kept for a cold start ───────────────────────────────────────────────────

class CallsHostStoreTest {
    @Test fun nothingKeptIsNothing() {
        assertEquals(null to false, CallsHostStore({ null }, {}).load())
        assertEquals("unreadable is nothing, never a crash", null to false, CallsHostStore({ "{not json" }, {}).load())
    }

    @Test fun theOptionsAreKeptInTheDocumentedShapeAndVoIPIsLeftAlone() {
        var kept: String? = JSONObject().put("voip", true).toString() // as an iOS-shaped writer left it
        val store = CallsHostStore({ kept }, { kept = it })
        val options = CallsHostOptions.parse(mapOf("publishableKey" to "pk_test_1", "displayName" to "Acme"))
        store.saveOptions(options.json)
        val (saved, voip) = store.load()
        assertTrue("the options did not overwrite the flag", voip)
        assertEquals(options, CallsHostOptions.parse(saved))
        val raw = JSONObject(kept!!)
        assertEquals(setOf("options", "voip"), raw.keys().asSequence().toSet())
        assertEquals("pk_test_1", raw.getJSONObject("options").getString("publishableKey"))
        assertEquals(30_000L, raw.getJSONObject("options").getLong("ringTimeoutMs"))
        var fresh: String? = null
        CallsHostStore({ fresh }, { fresh = it }).saveOptions(options.json)
        assertEquals(false, JSONObject(fresh!!).get("voip"))
    }
}

// ── what each method does ───────────────────────────────────────────────────

class CallsHostCoreTest {
    // configure and restore

    @Test fun theFirstConfigureMakesTheInstanceAndLaterOnesUpdateTheCalls() {
        val rig = HostRig()
        rig.configure(mapOf("appVersion" to "2.4.0", "ringTimeoutMs" to 45_000))
        assertEquals(1, rig.platform.made.size)
        assertEquals("2.4.0", rig.platform.made.first().appVersion)
        assertEquals(45_000L, rig.platform.configured.last().ringTimeoutMs)
        assertEquals("pk_test_rig", rig.core.options?.publishableKey)
        rig.configure(mapOf("displayName" to "Acme Assistant"))
        assertEquals("one instance per process", 1, rig.platform.made.size)
        assertEquals(2, rig.platform.configured.size)
        assertEquals("Acme Assistant", rig.platform.configured.last().displayName)
        assertTrue(rig.platform.logs.isEmpty())
        assertEquals("kept for the next cold start", "Acme Assistant", rig.store.load().first?.get("displayName"))
    }

    @Test fun theTransportReachesTheCallsAndIsKeptForAColdStart() {
        val rig = HostRig()
        rig.configure(mapOf("callTransport" to "webrtc"))
        assertEquals(GraineCallTransport.WEBRTC, rig.platform.configured.last().callTransport)
        assertEquals("webrtc", rig.store.load().first?.get("callTransport"))
        rig.configure(mapOf("callTransport" to "websocket"))
        assertEquals(GraineCallTransport.WEBSOCKET, rig.platform.configured.last().callTransport)
    }

    @Test fun invalidOptionsAreRefusedAndNothingIsKept() {
        val rig = HostRig()
        try {
            rig.core.configure(mapOf("publishableKey" to "pk_test_rig"))
            fail("configure accepted options with no displayName")
        } catch (e: GraineCallsHostException) {
            assertEquals("invalid_options", e.code)
            assertEquals("displayName is required", e.message)
        }
        assertTrue(rig.platform.made.isEmpty())
        assertNull(rig.store.load().first)
    }

    @Test fun aDifferentKeyIsKeptForTheNextLaunchAndSaidSo() {
        val rig = HostRig()
        rig.configure()
        rig.configure(mapOf("publishableKey" to "pk_test_other"))
        assertEquals(1, rig.platform.made.size)
        assertEquals(1, rig.platform.logs.size)
        assertEquals("pk_test_other", rig.store.load().first?.get("publishableKey"))
        rig.configure(mapOf("publishableKey" to "pk_test_rig", "baseUrl" to "https://graine.example.com"))
        assertEquals("a different base URL too", 2, rig.platform.logs.size)
    }

    @Test fun anInstanceTheAppMadeWithAnotherKeyIsSaidToo() {
        val rig = HostRig()
        rig.platform.runningWith = CallsHostInstance("pk_test_native", "https://www.graine.ai/", false)
        rig.configure()
        assertEquals(1, rig.platform.logs.size)
        val same = HostRig()
        same.platform.runningWith = CallsHostInstance("pk_test_rig", "https://www.graine.ai/", false)
        same.configure()
        assertTrue("a trailing slash is the same base URL", same.platform.logs.isEmpty())
    }

    @Test fun debugIsReadWhenTheInstanceIsMadeAndAChangeWaitsForTheNextLaunch() {
        val rig = HostRig()
        rig.configure(mapOf("debug" to true))
        assertEquals("the SDK instance is made with the debug log on", true, rig.platform.made.first().debug)
        assertTrue(rig.platform.logs.isEmpty())
        rig.configure(mapOf("debug" to true, "displayName" to "Acme Assistant"))
        assertTrue("the same setting again is nothing to say", rig.platform.logs.isEmpty())
        rig.configure(mapOf("debug" to false))
        assertEquals("one instance per process", 1, rig.platform.made.size)
        assertEquals(1, rig.platform.logs.size)
        assertEquals("kept for the next launch", false, rig.store.load().first?.get("debug"))
        // The next launch: its cold start makes the instance from what was kept.
        val next = HostRig()
        next.kept = rig.kept
        assertTrue(next.core.restore())
        assertEquals(false, next.platform.made.first().debug)
    }

    @Test fun anInstanceTheAppMadeWithAnotherDebugSettingIsSaidToo() {
        val rig = HostRig()
        rig.platform.runningWith = CallsHostInstance("pk_test_rig", "https://www.graine.ai", true)
        rig.configure()
        assertEquals(1, rig.platform.logs.size)
        val same = HostRig()
        same.platform.runningWith = CallsHostInstance("pk_test_rig", "https://www.graine.ai", true)
        same.configure(mapOf("debug" to true))
        assertTrue(same.platform.logs.isEmpty())
    }

    @Test fun restoreAppliesTheKeptOptionsWithoutKeepingThemAgain() {
        val rig = HostRig()
        // Kept by an earlier launch, by a newer SDK with a key this one does not know.
        rig.store.saveOptions(CallsHostOptions.parse(mapOf("publishableKey" to "pk_test_rig", "displayName" to "Acme")).json + ("fromANewerSDK" to true))
        assertTrue(rig.core.restore())
        assertEquals(1, rig.platform.made.size)
        assertEquals(1, rig.platform.configured.size)
        assertEquals("restore never rewrites what was kept", true, rig.store.load().first?.get("fromANewerSDK"))
        assertFalse("once per launch", rig.core.restore())
        assertEquals(1, rig.platform.configured.size)
    }

    @Test fun restoreWithNothingKeptOrAfterConfigureDoesNothing() {
        val rig = HostRig()
        assertFalse(rig.core.restore())
        assertTrue(rig.platform.made.isEmpty())
        rig.configure()
        assertFalse(rig.core.restore())
        assertEquals(1, rig.platform.configured.size)
    }

    @Test fun unreadableKeptOptionsWaitForConfigure() {
        val rig = HostRig()
        rig.store.saveOptions(mapOf("displayName" to "Acme"))
        assertFalse(rig.core.restore())
        assertTrue(rig.platform.made.isEmpty())
        assertEquals(1, rig.platform.logs.size)
    }

    // a push

    @Test fun aPushRestoresTheKeptOptionsFirstSoACallRingsBeforeAnyJavaScript() {
        val rig = HostRig(listening = false)
        rig.keep()
        assertTrue(rig.core.handlePush(mapOf("type" to "agent_call", "call_id" to "c1", "attempt" to 2.0)))
        assertEquals("configured from what was kept, before the push", 1, rig.platform.configured.size)
        assertEquals(listOf(mapOf("type" to "agent_call", "call_id" to "c1", "attempt" to "2")), rig.platform.pushes)
        rig.core.handlePush(mapOf("type" to "agent_call", "call_id" to "c2"))
        assertEquals("restored once", 1, rig.platform.configured.size)
    }

    @Test fun aPushWithNothingKeptStillGoesToTheCallsToSayWhetherItWasOne() {
        val rig = HostRig()
        rig.platform.pushIsACall = false
        assertFalse(rig.core.handlePush(mapOf("type" to "chat_message")))
        assertTrue(rig.platform.made.isEmpty())
        assertEquals(1, rig.platform.pushes.size)
    }

    // calls

    @Test fun startCallPlacesTheCallWithItsReasonAndClearsTheLastError() {
        val rig = HostRig()
        assertNull("not configured", rig.core.startCall(mapOf("reason" to "support"), "activity"))
        rig.configure()
        rig.core.outcome(GraineCallOutcome.failed("No network."), "old")
        assertEquals("No network.", rig.core.error)
        val id = rig.core.startCall(mapOf("reason" to " card_blocked ", "variables" to mapOf("order" to "A-1043")), "activity")
        assertEquals("call-1", id)
        assertEquals("activity", rig.platform.placed.first().first)
        assertEquals("card_blocked", rig.platform.placed.first().second)
        assertEquals("A-1043", rig.platform.placed.first().third["order"])
        assertNull(rig.core.error)
        rig.platform.runTurns()
        val state = rig.events.payloads("state").last()
        assertEquals("connecting", state["state"])
        assertEquals("card_blocked", state["reason"])
        assertNull(state["error"])
    }

    @Test fun theSnapshotTheRingTheOutcomeAndTheActionNameTheCallsAgent() {
        val rig = HostRig()
        rig.configure(mapOf("agents" to mapOf("sales" to mapOf("publishableKey" to "pk_test_sales"))))
        rig.core.setActions(listOf("raise_ticket"), rig.sink)
        // A ring Graine routed to the sales agent by the push's agent_id.
        rig.platform.callAgents["h1"] = "sales"
        rig.platform.fields = CallsHostSnapshot(GraineCallState.RINGING, "h1", GraineCall.Direction.INCOMING, agent = "sales")
        rig.core.incoming(GraineCall("h1", GraineCall.Direction.INCOMING, "Sales", "renewal", emptyMap(), hosted = true))
        rig.platform.runTurns()
        assertEquals("sales", rig.events.payloads("incoming").single()["agent"])
        assertEquals("sales", rig.events.payloads("state").last()["agent"])
        assertEquals("sales", rig.core.snapshot()["agent"])
        rig.handler("raise_ticket")(JSONObject()) { }
        assertEquals("the agent on the call asked", "sales", rig.events.payloads("action").single()["agent"])
        rig.core.outcome(GraineCallOutcome.ANSWERED, "h1")
        // A ring refused while that call is live is its own agent's: the default one here.
        rig.platform.callAgents["h2"] = "default"
        rig.core.outcome(GraineCallOutcome.MISSED, "h2")
        // An outcome nobody can place says nothing about an agent.
        rig.core.outcome(GraineCallOutcome.MISSED, "unknown")
        // First the ring itself: Graine rang it, so the app hears it ringing — then what became of it, and of the others.
        assertEquals(listOf("ringing", "answered", "missed", "missed"), rig.events.payloads("outcome").map { it["outcome"] })
        assertEquals(listOf("sales", "sales", "default", null), rig.events.payloads("outcome").map { it["agent"] })
        assertFalse(rig.events.payloads("outcome").last().containsKey("agent"))
        rig.platform.fields = CallsHostSnapshot()
        rig.core.callStateChanged(GraineCallState.IDLE)
        rig.platform.runTurns()
        assertNull("idle: no agent", rig.events.payloads("state").last()["agent"])
    }

    @Test fun aCallIsPlacedWithTheAgentItsOptionsName() {
        val rig = HostRig()
        rig.configure(mapOf("agents" to mapOf("sales" to mapOf("publishableKey" to "pk_test_sales"))))
        assertEquals(listOf(mapOf("sales" to CallsHostAgent("pk_test_sales", "https://www.graine.ai"))), rig.platform.agentsMade)
        rig.core.startCall(mapOf("reason" to "upgrade", "agent" to " sales "), "activity")
        rig.platform.fields = CallsHostSnapshot()
        rig.core.startCall(mapOf("reason" to "help"), "activity")
        rig.platform.fields = CallsHostSnapshot()
        rig.core.startCall(mapOf("reason" to "help", "agent" to 7), "activity")
        assertEquals(listOf("sales", "default", "default"), rig.platform.placedWith)
        // A cold start makes them again from what was kept, so a ring for any of them rings with its own.
        val next = HostRig()
        next.kept = rig.kept
        assertTrue(next.core.restore())
        assertEquals(rig.platform.agentsMade, next.platform.agentsMade)
    }

    @Test fun noSecondCallWhileOneIsLive() {
        val rig = HostRig()
        rig.configure()
        rig.core.startCall(mapOf("reason" to "first"), "activity")
        assertNull(rig.core.startCall(mapOf("reason" to "second"), "activity"))
        assertEquals(1, rig.platform.placed.size)
    }

    @Test fun aCallTheEngineRefusesLeavesEverythingAsItWas() {
        val rig = HostRig()
        rig.configure()
        rig.core.outcome(GraineCallOutcome.failed("No network."), "old")
        rig.platform.nextCallId = null
        assertNull("no activity in the foreground, or the engine refused", rig.core.startCall(mapOf("reason" to "support"), null))
        assertEquals("No network.", rig.core.error)
    }

    @Test fun theControlsReachTheCalls() {
        val rig = HostRig()
        rig.core.answer()
        rig.core.decline()
        rig.core.end()
        rig.core.setMuted(true)
        rig.core.setSpeaker(false)
        assertEquals(1, rig.platform.answers)
        assertEquals(1, rig.platform.declines)
        assertEquals(1, rig.platform.ends)
        assertEquals(listOf(true), rig.platform.muted)
        assertEquals(listOf(false), rig.platform.speaker)
    }

    @Test fun aMicrophoneTheCustomerRefusedIsTheSnapshotsError() {
        val rig = HostRig()
        rig.configure(mapOf("callScreen" to "app"))
        assertTrue(rig.core.appDrawsTheCall)
        rig.core.microphoneDenied()
        rig.platform.runTurns()
        assertEquals(CallsHostCore.MICROPHONE_DENIED, rig.events.payloads("state").last()["error"])
        rig.core.startCall(null, "activity")
        assertNull("cleared when a call is placed", rig.core.error)
        assertFalse(HostRig().core.appDrawsTheCall)
    }

    // what the engine reports

    @Test fun aRingIsAnEventAndItsReasonIsTheSnapshots() {
        val rig = HostRig()
        rig.configure()
        rig.core.outcome(GraineCallOutcome.failed("No network."), "old")
        val call = GraineCall("c9", GraineCall.Direction.INCOMING, "Acme", "payment_due", mapOf("who_called" to "agent"))
        rig.platform.fields = CallsHostSnapshot(GraineCallState.RINGING, "c9", GraineCall.Direction.INCOMING, "payment_due", "Acme")
        rig.core.callStateChanged(GraineCallState.RINGING)
        assertNull("a new call rings: the last one's error is old news", rig.core.error)
        rig.core.incoming(call)
        assertEquals(listOf(mapOf("callId" to "c9", "reason" to "payment_due", "callerName" to "Acme")), rig.events.payloads("incoming"))
        rig.platform.runTurns()
        val state = rig.events.payloads("state").last()
        assertEquals("ringing", state["state"])
        assertEquals("incoming", state["direction"])
        assertEquals("payment_due", state["reason"])
        assertEquals("Acme", state["callerName"])
    }

    @Test fun aCallPlacedOutsideTheHostClearsTheErrorToo() {
        val rig = HostRig()
        rig.configure()
        rig.core.outcome(GraineCallOutcome.failed("No network."), "old")
        rig.platform.fields = CallsHostSnapshot(GraineCallState.CONNECTING, "native-1", GraineCall.Direction.OUTGOING)
        rig.core.callStateChanged(GraineCallState.CONNECTING)
        assertNull(rig.core.error)
    }

    @Test fun whyACallFailedOutlivesItUntilTheNextOne() {
        val rig = HostRig()
        rig.configure()
        rig.core.startCall(mapOf("reason" to "support"), "activity")
        rig.core.callStateChanged(GraineCallState.CONNECTING)
        // GraineCalls reports the outcome while the call is still connecting, then ended, then idle.
        rig.core.outcome(GraineCallOutcome.failed("The microphone permission was not granted."), "call-1")
        rig.platform.fields = CallsHostSnapshot(GraineCallState.ENDED, "call-1", GraineCall.Direction.OUTGOING, "support")
        rig.core.callStateChanged(GraineCallState.ENDED)
        rig.platform.fields = CallsHostSnapshot()
        rig.core.callStateChanged(GraineCallState.IDLE)
        rig.platform.runTurns()
        val idle = rig.events.payloads("state").last()
        assertEquals("idle", idle["state"])
        assertEquals("The microphone permission was not granted.", idle["error"])
        assertNull(idle["reason"])
        assertEquals(mapOf("callId" to "call-1", "outcome" to "failed", "error" to "The microphone permission was not granted."),
            rig.events.payloads("outcome").last())
        rig.core.startCall(null, "activity")
        assertNull("cleared when the next call is placed", rig.core.error)
    }

    @Test fun aCallThatConnectedAndEndedBadlyLeavesNoError() {
        val rig = HostRig()
        rig.configure()
        rig.platform.fields = CallsHostSnapshot(GraineCallState.ACTIVE, "c1", GraineCall.Direction.INCOMING)
        rig.core.outcome(GraineCallOutcome.ended(GraineCallOutcome.EndReason.FAILED, "connection reset"), "c1")
        assertNull(rig.core.error)
        assertEquals(mapOf("callId" to "c1", "outcome" to "ended", "reason" to "failed"), rig.events.payloads("outcome").last())
    }

    @Test fun aPushRefusedDuringACallIsNotThatCallsError() {
        val rig = HostRig()
        rig.configure()
        rig.platform.fields = CallsHostSnapshot(GraineCallState.ACTIVE, "live")
        rig.core.outcome(GraineCallOutcome.failed("The push was not an agent call this SDK can ring."), "other")
        assertNull(rig.core.error)
        rig.core.outcome(GraineCallOutcome.MISSED, "other")
        assertEquals("the app still hears about both", 2, rig.events.payloads("outcome").size)
    }

    @Test fun changesInOneTurnAreOneStateEventAndNoChangeIsNone() {
        val rig = HostRig()
        rig.configure()
        rig.platform.runTurns()
        assertTrue("idle is not news", rig.events.payloads("state").isEmpty())
        rig.platform.fields = CallsHostSnapshot(GraineCallState.CONNECTING, "c1", GraineCall.Direction.OUTGOING)
        rig.core.engineChanged()
        rig.core.engineChanged()
        rig.core.callStateChanged(GraineCallState.CONNECTING)
        rig.core.engineChanged()
        assertEquals(1, rig.platform.pendingTurns)
        rig.platform.runTurns()
        assertEquals(1, rig.events.payloads("state").size)
        rig.core.engineChanged()
        rig.platform.runTurns()
        assertEquals("nothing a screen draws changed", 1, rig.events.payloads("state").size)
        rig.platform.fields = rig.platform.fields.copy(agentSpeaking = true)
        rig.core.engineChanged()
        rig.platform.runTurns()
        assertEquals(true, rig.events.payloads("state").last()["agentSpeaking"])
    }

    @Test fun aNewSinkReadsTheSnapshot() {
        val rig = HostRig()
        assertEquals("before configure too", "idle", rig.core.snapshot()["state"])
        rig.configure()
        rig.platform.fields = CallsHostSnapshot(GraineCallState.ACTIVE, "c1", GraineCall.Direction.OUTGOING, connectedAt = 1_000_000L)
        val snapshot = rig.core.snapshot()
        assertEquals("active", snapshot["state"])
        assertEquals(1_000_000L, snapshot["connectedAt"])
        assertNull(snapshot["reason"])
    }

    @Test fun outcomesFromBeforeAnythingListenedReachTheFirstSink() {
        val rig = HostRig(listening = false)
        rig.configure()
        rig.core.incoming(GraineCall("c1", GraineCall.Direction.INCOMING, null, "payment_due", emptyMap()))
        rig.core.outcome(GraineCallOutcome.ANSWERED, "c1")
        rig.core.outcome(GraineCallOutcome.ended(GraineCallOutcome.EndReason.REMOTE), "c1")
        rig.platform.runTurns()
        val log = HostEventLog()
        rig.core.addSink(log::record)
        assertEquals(listOf("incoming", "outcome", "outcome"), log.names)
    }

    // who, what and where

    @Test fun identityVariablesAndTheScreenReachTheSDK() {
        val rig = HostRig()
        rig.configure()
        rig.core.setIdentity(mapOf("name" to "Asha"))
        rig.core.setIdentity(null)
        rig.core.setVariables(mapOf("plan" to "Plus", "cart" to 1299))
        rig.core.setVariables(mapOf("plan" to "Gold"))
        rig.core.setScreen(mapOf("screen" to "billing", "title" to "Billing"))
        rig.core.setScreen(mapOf("title" to "no screen name"))
        assertEquals(listOf(mapOf("name" to "Asha"), null), rig.platform.identities)
        assertEquals(listOf(mapOf("plan" to "Plus", "cart" to "1299"), mapOf("plan" to "Gold", "cart" to "<removed>")),
            rig.platform.variableChanges.map { describe(it) })
        assertEquals(listOf("billing", null), rig.platform.screens.map { it?.screen })
        assertEquals("Billing", rig.platform.screens.first()?.title)
    }

    @Test fun whatArrivesBeforeConfigureIsHeldUntilThereIsAnInstance() {
        val rig = HostRig()
        rig.core.setIdentity(mapOf("name" to "Asha"))
        rig.core.setVariables(mapOf("plan" to "Plus"))
        rig.core.setVariables(mapOf("plan" to "Gold", "city" to "Pune"))
        rig.core.setScreen(mapOf("screen" to "home"))
        rig.core.setActions(listOf("open_screen"), rig.sink)
        assertTrue(rig.platform.identities.isEmpty())
        assertTrue(rig.platform.variableChanges.isEmpty())
        assertTrue(rig.platform.handlers.isEmpty())
        rig.configure()
        assertEquals(1, rig.platform.identities.size)
        assertEquals("only the latest map", listOf(mapOf("plan" to "Gold", "city" to "Pune")), rig.platform.variableChanges.map { describe(it) })
        assertEquals(listOf("home"), rig.platform.screens.map { it?.screen })
        assertEquals(listOf("open_screen"), rig.platform.handlers.keys.toList())
        rig.configure()
        assertEquals("applied once", 1, rig.platform.identities.size)
    }

    // actions

    @Test fun theAgentsActionIsAskedOfTheSinkAndAnsweredOnce() {
        val rig = HostRig()
        rig.configure()
        rig.core.setActions(listOf("open_screen", "open_screen", ""), rig.sink)
        assertEquals(listOf("open_screen"), rig.platform.handlers.keys.toList())
        val answers = mutableListOf<ActionResult>()
        rig.handler("open_screen")(JSONObject().put("page", "billing")) { answers += it }
        val request = rig.events.payloads("action").first()
        assertEquals("open_screen", request["name"])
        assertEquals(mapOf("page" to "billing"), request["arguments"])
        val id = request["requestId"] as String
        rig.core.resolveAction(id, mapOf("status" to "ok", "message" to "Opened the bill."))
        rig.core.resolveAction(id, mapOf("status" to "error", "message" to "late"))
        assertEquals(listOf(ActionResult.ok("Opened the bill.")), answers)
        rig.platform.fireTimers()
        assertEquals("forgotten later, harmlessly: it was answered", 1, answers.size)
    }

    @Test fun namesNoLongerListedAreUnregistered() {
        val rig = HostRig()
        rig.configure()
        rig.core.setActions(listOf("open_screen", "set_theme"), rig.sink)
        rig.core.setActions(listOf("set_theme"), rig.sink)
        assertEquals(listOf("open_screen"), rig.platform.unregistered)
        assertEquals(listOf("set_theme"), rig.platform.handlers.keys.toList())
        rig.core.setActions(emptyList<String>(), rig.sink)
        assertTrue(rig.platform.handlers.isEmpty())
        assertEquals(listOf("open_screen", "set_theme"), rig.platform.unregistered)
    }

    @Test fun anActionNobodyCanAnswerIsRefusedAtOnce() {
        val rig = HostRig()
        rig.configure()
        rig.core.setActions(listOf("open_screen"), null)
        val answers = mutableListOf<ActionResult>()
        rig.handler("open_screen")(JSONObject()) { answers += it }
        assertEquals(listOf(ActionResult.refused("The app is not listening for \"open_screen\" right now.")), answers)
        assertTrue(rig.events.payloads("action").isEmpty())
        assertEquals(0, rig.core.actions.pendingCount)
        assertTrue(rig.platform.timers.isEmpty())
    }

    @Test fun aSinkThatLeavesHasItsUnansweredRequestsRefused() {
        val rig = HostRig()
        rig.configure()
        rig.core.setActions(listOf("open_screen"), rig.sink)
        val answers = mutableListOf<ActionResult>()
        val handler = rig.handler("open_screen")
        handler(JSONObject()) { answers += it }
        rig.core.removeSink(rig.sink!!)
        assertEquals(listOf(ActionResult.refused("The app stopped listening before it answered.")), answers)
        handler(JSONObject()) { answers += it }
        assertEquals("and the next request is refused at once",
            ActionResult.refused("The app is not listening for \"open_screen\" right now."), answers.last())
    }

    @Test fun aRequestTheAppNeverAnswersIsForgotten() {
        val rig = HostRig()
        rig.configure()
        rig.core.setActions(listOf("open_screen"), rig.sink)
        val answers = mutableListOf<ActionResult>()
        rig.handler("open_screen")(JSONObject()) { answers += it }
        assertEquals(listOf(10_000L), rig.platform.timers.map { it.first })
        rig.platform.fireTimers()
        assertEquals(listOf(ActionResult.error("The app did not answer in time.")), answers)
        assertEquals(0, rig.core.actions.pendingCount)
    }
}
