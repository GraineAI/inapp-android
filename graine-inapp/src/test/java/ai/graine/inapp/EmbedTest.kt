package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * THE SDK'S OWN REQUESTS (contract §3.6, §4, §5.1): what each body says, how
 * every answer — and every refusal — is read, the scheduling rules checked
 * before asking, and the outcomes of a call Graine rang.
 */
class EmbedTest {
    private val env = IdentityEnvelope("P-1234", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", true, "Asia/Kolkata")

    // ── requests and refusals ──────────────────────────────────────────────

    @Test fun everyRequestCarriesTheKeyAndReadsTheServersRefusal() {
        val sent = mutableListOf<Pair<String, JSONObject>>()
        var reply = HttpReply(200, """{"ok":true}""")
        val api = EmbedApi("https://www.graine.ai/", "pk_test", HttpPost { url, json -> sent += url to JSONObject(json); reply })
        val ok = api.post(EmbedApi.SCHEDULE, JSONObject().put("at", "x"))
        assertTrue(ok is EmbedReply.Ok)
        assertEquals("https://www.graine.ai/api/embed/calls/schedule", sent[0].first)
        assertEquals("pk_test", sent[0].second.getString("publishableKey"))

        reply = HttpReply(400, """{"error":"Pick a later time.","code":"too_soon"}""")
        assertEquals(GraineError(GraineErrorCode.TOO_SOON, "Pick a later time."), (api.post(EmbedApi.SCHEDULE, JSONObject()) as EmbedReply.Failed).error)
        reply = HttpReply(400, """{"error":"Something new.","code":"brand_new_code"}""")
        assertEquals("a code this build does not know is carried", GraineErrorCode.Unknown("brand_new_code"),
            (api.post(EmbedApi.SCHEDULE, JSONObject()) as EmbedReply.Failed).error.code)
        // No code in the body: the status says what it is — a bare 500 is an answer nobody expected, as on every SDK.
        for ((status, code) in listOf(400 to "invalid_request", 401 to "invalid_key", 403 to "origin_refused", 404 to "not_found",
                429 to "rate_limited", 500 to "http_500", 502 to "unavailable", 503 to "unavailable", 504 to "unavailable")) {
            reply = HttpReply(status, "not json")
            assertEquals("$status", code, (api.post(EmbedApi.LIST, JSONObject()) as EmbedReply.Failed).error.code.wire)
        }
        reply = HttpReply(429, """{"error":"Slow down.","code":"rate_limited"}""", retryAfterSeconds = 30)
        val limited = api.post(EmbedApi.LIST, JSONObject()) as EmbedReply.Failed
        assertEquals("Retry-After is honoured", 30_000L, limited.retryAfterMs)
        assertEquals(GraineError(GraineErrorCode.RATE_LIMITED, "Slow down.", 30), limited.error)
        val offline = EmbedApi("https://www.graine.ai", "pk", HttpPost { _, _ -> throw java.io.IOException("down") })
        assertEquals(GraineErrorCode.NETWORK, (offline.post(EmbedApi.OUTCOME, JSONObject()) as EmbedReply.Failed).error.code)
    }

    @Test fun onlyAnUnansweredRequestIsTriedAgain() {
        assertTrue(HostedOutcomes.retryable(GraineError(GraineErrorCode.NETWORK, "")))
        assertTrue(HostedOutcomes.retryable(GraineError(GraineErrorCode.RATE_LIMITED, "")))
        assertTrue(HostedOutcomes.retryable(GraineError(GraineErrorCode.UNAVAILABLE, "")))
        assertFalse(HostedOutcomes.retryable(GraineError(GraineErrorCode.INVALID_REQUEST, "")))
        assertFalse("a refusal is an answer", HostedOutcomes.retryable(GraineError(GraineErrorCode.NOT_FOUND, "")))
        assertFalse(HostedOutcomes.retryable(GraineError(GraineErrorCode.INVALID_KEY, "")))
        for (c in GraineErrorCode.KNOWN) assertEquals(c, GraineErrorCode.from(c.wire))
    }

    // ── the session's calls block ──────────────────────────────────────────

    @Test fun theSessionSaysWhetherGraineRingsAndWhoMayBook() {
        val r = GraineSchedulingRules.parse(JSONObject("""{"hosted":true,"platforms":["ios","Android","web"],"ringTimeoutSeconds":40,"callName":" Asha · Graine Care ",
            "scheduling":{"usersCanBook":true,"maxDaysAhead":21,"minLeadMinutes":10,"maxPendingPerUser":2}}"""))
        assertEquals(GraineSchedulingRules(true, 10, 21, 2, 40, "Asha · Graine Care", true, listOf("ios", "android")), r)
        assertTrue(r.ringsAndroid)
        val now = java.util.Date(1_000_000_000_000L)
        assertEquals(java.util.Date(now.time + 10 * 60_000L), r.earliest(now))
        assertEquals(java.util.Date(now.time + 21 * 86_400_000L), r.latest(now))
        assertFalse("iOS only does not ring Android", GraineSchedulingRules.parse(JSONObject("""{"hosted":true,"platforms":["ios"]}""")).ringsAndroid)
        assertTrue("no list: every platform", GraineSchedulingRules.parse(JSONObject("""{"hosted":true}""")).ringsAndroid)
        assertFalse("not hosted rings nobody", GraineSchedulingRules.parse(JSONObject("""{"hosted":false}""")).ringsAndroid)
        assertFalse("only true is true", GraineSchedulingRules.parse(JSONObject("""{"hosted":"yes"}""")).hostedRinging)
        // Nothing in the session: nobody books, Graine rings nobody, the defaults hold.
        assertEquals(GraineSchedulingRules(usersCanBook = false, minLeadMinutes = 5, maxDaysAhead = 14, maxPendingPerUser = 3,
            ringTimeoutSeconds = 45, callName = null, hostedRinging = false, platforms = emptyList()), GraineSchedulingRules.parse(null))
        assertEquals(GraineSchedulingRules(), GraineSchedulingRules.parse(JSONObject("""{"hosted":true,"callName":"  "}""")).copy(hostedRinging = false))
        // The dashboard's range, as every SDK reads it: 20–60 seconds.
        assertEquals(60, GraineSchedulingRules.parse(JSONObject("""{"ringTimeoutSeconds":600}""")).ringTimeoutSeconds)
        assertEquals(20, GraineSchedulingRules.parse(JSONObject("""{"ringTimeoutSeconds":5}""")).ringTimeoutSeconds)
        assertEquals(45, GraineSchedulingRules.parse(JSONObject("""{"ringTimeoutSeconds":-3}""")).ringTimeoutSeconds)
    }

    // ── scheduling ─────────────────────────────────────────────────────────

    @Test fun aTimeIsWrittenWithTheUsersOwnOffset() {
        val at = Scheduling.parseIso("2026-10-14T12:30:00Z")!!
        assertEquals("2026-10-14T18:00:00+05:30", Scheduling.iso(at, TimeZone.getTimeZone("Asia/Kolkata")))
        assertEquals("2026-10-14T05:30:00-07:00", Scheduling.iso(at, TimeZone.getTimeZone("America/Los_Angeles")))
        assertEquals("2026-10-14T12:30:00+00:00", Scheduling.iso(at, TimeZone.getTimeZone("UTC")))
        // Whatever language the phone is in, the digits are ASCII.
        val before = Locale.getDefault()
        try {
            Locale.setDefault(Locale("ar", "EG"))
            assertEquals("2026-10-14T18:00:00+05:30", Scheduling.iso(at, TimeZone.getTimeZone("Asia/Kolkata")))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun theServersTimesAreReadInEveryShapeItWrites() {
        val utc = Scheduling.parseIso("2026-10-14T12:30:00Z")!!
        assertEquals(utc, Scheduling.parseIso("2026-10-14T18:00:00+05:30"))
        assertEquals(utc, Scheduling.parseIso("2026-10-14T18:00:00+0530"))
        assertEquals(utc, Scheduling.parseIso("2026-10-14T12:30:00"))
        assertEquals(utc + 250, Scheduling.parseIso("2026-10-14T12:30:00.25Z"))
        assertEquals(utc + 123, Scheduling.parseIso("2026-10-14T12:30:00.123456Z"))
        assertEquals(utc, Scheduling.parseIso("2026-10-14T05:30:00-07:00"))
        assertNull(Scheduling.parseIso("tomorrow at six"))
        assertNull(Scheduling.parseIso(null))
        val zone = TimeZone.getTimeZone("Asia/Kolkata")
        assertEquals("written and read back", utc, Scheduling.parseIso(Scheduling.iso(utc, zone)))
    }

    @Test fun theAgentsOwnRulesAreCheckedBeforeAnyRequest() {
        val now = 1_000_000_000_000L
        val rules = GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 5, maxDaysAhead = 14)
        assertNull(Scheduling.check(now + 10 * 60_000, now, rules))
        assertNull("exactly the earliest is allowed", Scheduling.check(rules.earliest(java.util.Date(now)).time, now, rules))
        assertEquals(GraineErrorCode.SCHEDULING_OFF, Scheduling.check(now + 60 * 60_000, now, rules.copy(usersCanBook = false))?.code)
        assertEquals("a session that says nothing books nothing", GraineErrorCode.SCHEDULING_OFF, Scheduling.check(now + 60 * 60_000, now, GraineSchedulingRules())?.code)
        assertEquals(GraineErrorCode.TOO_SOON, Scheduling.check(now + 4 * 60_000, now, rules)?.code)
        assertEquals("the past is too soon", GraineErrorCode.TOO_SOON, Scheduling.check(now - 1, now, rules.copy(minLeadMinutes = 0))?.code)
        assertEquals(GraineErrorCode.TOO_FAR, Scheduling.check(now + 15 * 86_400_000L, now, rules)?.code)
        assertNull("exactly the latest is allowed", Scheduling.check(rules.latest(java.util.Date(now)).time, now, rules))
    }

    @Test fun aLocalRefusalSaysItInTheBookingSheetsWords() {
        val now = 1_000_000_000_000L
        val one = GraineSchedulingRules(usersCanBook = true, minLeadMinutes = 1, maxDaysAhead = 1, maxPendingPerUser = 1)
        assertEquals("1 minute, not 1 minutes", "Pick a time at least 1 minute from now", Scheduling.check(now - 60_000, now, one)?.message)
        assertEquals("Pick a time within 1 day", Scheduling.check(now + 3 * 86_400_000L, now, one)?.message)
        val many = one.copy(minLeadMinutes = 5, maxDaysAhead = 14)
        assertEquals("Pick a time at least 5 minutes from now", Scheduling.check(now + 60_000, now, many)?.message)
        assertEquals("Pick a time within 14 days", Scheduling.check(now + 15 * 86_400_000L, now, many)?.message)
        assertEquals("Booking a call isn't available right now", Scheduling.check(now + 3_600_000, now, many.copy(usersCanBook = false))?.message)
    }

    @Test fun aBookingSaysWhenWhyAndWho() {
        val at = Scheduling.parseIso("2026-10-14T12:30:00Z")!!
        val o = Scheduling.scheduleBody(at, "  Follow up on the KYC documents " + "x".repeat(200), env, TimeZone.getTimeZone("Asia/Kolkata"))
        assertEquals("2026-10-14T18:00:00+05:30", o.getString("at"))
        assertEquals(Scheduling.REASON_MAX, o.getString("reason").length)
        assertTrue(o.getString("reason").startsWith("Follow up"))
        assertEquals("P-1234", o.getJSONObject("identity").getString("value"))
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", o.getString("installId"))
        assertEquals("Asia/Kolkata", o.getString("timezone"))
        assertEquals("exactly the route's fields", setOf("installId", "identity", "at", "reason", "timezone"), o.keys().asSequence().toSet())
        assertFalse("no reason, no key", Scheduling.scheduleBody(at, " ", env).has("reason"))
    }

    @Test fun bookedCallsAreReadAsTheWireDescribesThem() {
        val one = Scheduling.scheduled(JSONObject("""{"call":{"id":"sc_1","at":"2026-10-14T12:30:00Z","status":"SCHEDULED","reason":"kyc","source":"sdk"}}"""))!!
        assertEquals("sc_1", one.id)
        assertEquals(java.util.Date(Scheduling.parseIso("2026-10-14T12:30:00Z")!!), one.at)
        assertEquals(GraineCallStatus.SCHEDULED, one.status)
        assertTrue(one.isUpcoming)
        assertTrue(one.canCancel)
        assertEquals("kyc", one.reason)
        assertEquals("the app's own booking", GraineCallSource.APP, one.source)
        val unknown = Scheduling.scheduled(JSONObject("""{"call":{"id":"sc_9","at":null,"status":"rescheduled","source":"partner","reason":null}}"""))!!
        assertEquals("an unknown status passes through", GraineCallStatus.Unknown("rescheduled"), unknown.status)
        assertEquals("rescheduled", unknown.status.wire)
        assertEquals(GraineCallSource.Unknown("partner"), unknown.source)
        assertFalse(unknown.isUpcoming)
        assertNull(unknown.at)
        assertNull(unknown.reason)
        assertNull("no id, no call", Scheduling.scheduled(JSONObject("""{"status":"scheduled"}""")))
        val list = Scheduling.list(JSONObject("""{"calls":[
            {"id":"ringing","at":"2026-10-13T10:00:00Z","status":"ringing"},
            {"id":"soon","at":"2026-10-15T10:00:00Z","status":"scheduled","source":"agent"},
            {"id":"gone","at":"2026-10-16T10:00:00Z","status":"no_device","source":"dashboard"},
            {"id":"millis","at":1760000000000,"status":"cancelled","source":"api"},
            {"nope":true}]}"""))
        assertEquals("in the server's order", listOf("ringing", "soon", "gone", "millis"), list.map { it.id })
        assertTrue("ringing is still upcoming", list[0].isUpcoming)
        assertFalse("but can no longer be cancelled", list[0].canCancel)
        assertEquals(GraineCallStatus.NO_DEVICE, list[2].status)
        assertEquals(GraineCallSource.DASHBOARD, list[2].source)
        assertEquals(java.util.Date(1760000000000L), list[3].at)
        assertEquals(GraineCallStatus.CANCELLED, list[3].status)
        assertTrue(Scheduling.list(JSONObject()).isEmpty())
        for (st in GraineCallStatus.KNOWN) assertEquals(st, GraineCallStatus.from(st.wire))
        assertEquals("no_device", GraineCallStatus.NO_DEVICE.wire)
        assertEquals("sdk", GraineCallSource.APP.wire)
    }

    @Test fun cancelAndForgetAnswerWithTheirResults() {
        assertEquals(GraineCallStatus.CANCELLED, Scheduling.cancelled(JSONObject("""{"ok":true}""")))
        assertEquals(GraineCallStatus.CANCELLED, Scheduling.cancelled(JSONObject("""{"ok":true,"status":"cancelled"}""")))
        assertEquals(GraineCallStatus.COMPLETED, Scheduling.cancelled(JSONObject("""{"ok":true,"status":"completed"}""")))
        assertEquals(7, Scheduling.deleted(JSONObject("""{"deleted":7}""")))
        assertEquals(0, Scheduling.deleted(JSONObject("""{}""")))
    }

    // ── what the agent remembers ───────────────────────────────────────────

    @Test fun whatTheAgentRemembersIsReadNewestFirst() {
        val list = Memories.list(JSONObject("""{"memories":[
            {"id":"m2","text":"Prefers calls after 6 pm","createdAt":"2026-10-12T09:00:00Z","updatedAt":"2026-10-13T09:00:00Z"},
            {"id":"m1","text":"Has a gold plan","createdAt":null,"updatedAt":null},
            {"id":"","text":"no id"},
            {"text":"no id either"},
            {"id":"m0"}]}"""))
        assertEquals(listOf("m2", "m1"), list.map { it.id })
        assertEquals("Prefers calls after 6 pm", list[0].text)
        assertEquals(java.util.Date(Scheduling.parseIso("2026-10-12T09:00:00Z")!!), list[0].createdAt)
        assertEquals(java.util.Date(Scheduling.parseIso("2026-10-13T09:00:00Z")!!), list[0].updatedAt)
        assertNull(list[1].createdAt)
        assertTrue(Memories.list(JSONObject()).isEmpty())
        val many = JSONObject().put("memories", org.json.JSONArray((1..150).map { JSONObject().put("id", "m$it").put("text", "t") }))
        assertEquals(Memories.MAX, Memories.list(many).size)
    }

    @Test fun theMemoryRoutesRefuseAsTheWireSays() {
        var reply = HttpReply(200, "{}")
        val sent = mutableListOf<Pair<String, JSONObject>>()
        val api = EmbedApi("https://www.graine.ai", "pk_test", HttpPost { url, json -> sent += url to JSONObject(json); reply })
        val body = env.who(JSONObject())
        assertTrue(api.post(EmbedApi.MEMORIES, body) is EmbedReply.Ok)
        assertEquals("https://www.graine.ai/api/embed/memory/list", sent[0].first)
        assertEquals(setOf("installId", "identity", "publishableKey"), sent[0].second.keys().asSequence().toSet())
        reply = HttpReply(403, """{"error":"This device is not one this user uses.","code":"device_limit"}""")
        assertEquals(GraineErrorCode.DEVICE_LIMIT, (api.post(EmbedApi.MEMORIES, env.who(JSONObject())) as EmbedReply.Failed).error.code)
        reply = HttpReply(400, """{"error":"Sign the user in first.","code":"identity_required"}""")
        assertEquals(GraineErrorCode.IDENTITY_REQUIRED, (api.post(EmbedApi.MEMORIES, JSONObject()) as EmbedReply.Failed).error.code)
    }

    // ── calls Graine rang ──────────────────────────────────────────────────

    @Test fun everyOutcomeIsGrainesWord() {
        fun of(o: GraineCallOutcome, busy: Boolean = false, ms: Long? = null) = HostedOutcomes.of("c1", o, busy, ms)
        assertEquals(HostedOutcomes.Report("c1", "answered"), of(GraineCallOutcome.ANSWERED))
        assertEquals(HostedOutcomes.Report("c1", "declined"), of(GraineCallOutcome.DECLINED))
        assertEquals("a ring that ran out says nothing more", HostedOutcomes.Report("c1", "missed"), of(GraineCallOutcome.MISSED))
        assertEquals(HostedOutcomes.Report("c1", "missed", "busy"), of(GraineCallOutcome.MISSED, busy = true))
        assertEquals(HostedOutcomes.Report("c1", "failed", "The microphone permission was denied."), of(GraineCallOutcome.failed("The microphone permission was denied.")))
        assertEquals(HostedOutcomes.Report("c1", "ended", "remote", 63), of(GraineCallOutcome.ended(GraineCallOutcome.EndReason.REMOTE), ms = 62_600))
        assertEquals(HostedOutcomes.Report("c1", "ended", "local", null), of(GraineCallOutcome.ended(GraineCallOutcome.EndReason.LOCAL)))
    }

    @Test fun anOutcomeReportSaysWhichCallAndWhichInstall() {
        val o = HostedOutcomes.Report("c1", "ended", "remote" + "x".repeat(300), 12).body("3f2504e0-4f89-11d3-9a0c-0305e82c3301")
        assertEquals(setOf("installId", "callId", "outcome", "reason", "durationSeconds"), o.keys().asSequence().toSet())
        assertEquals("c1", o.getString("callId"))
        assertEquals("ended", o.getString("outcome"))
        assertEquals(HostedOutcomes.REASON_MAX, o.getString("reason").length)
        assertEquals(12, o.getInt("durationSeconds"))
        val ringing = HostedOutcomes.Report("c1", "ringing").body("3f2504e0-4f89-11d3-9a0c-0305e82c3301")
        assertEquals(setOf("installId", "callId", "outcome"), ringing.keys().asSequence().toSet())
        assertEquals("two retries", listOf(2_000L, 6_000L), HostedOutcomes.RETRY_DELAYS_MS)
    }

    @Test fun aDeviceRegistersItsTokenAndWhoUsesIt() {
        val o = DeviceRegistration.body("fcm-token", "com.acme.app", "2.4.0", "0.6.2", env)
        assertEquals(setOf("installId", "identity", "platform", "appId", "token", "tokenKind", "timezone", "appVersion", "sdkVersion"),
            o.keys().asSequence().toSet())
        assertEquals("android", o.getString("platform"))
        assertEquals("fcm", o.getString("tokenKind"))
        assertEquals("P-1234", o.getJSONObject("identity").getString("value"))
        assertEquals(DeviceRegistration.reason(JSONObject("""{"ok":true,"trusted":false,"reason":"device_limit"}""")), "device_limit")
        assertNull(DeviceRegistration.reason(JSONObject("""{"ok":true,"trusted":true}""")))
    }

    @Test fun aDeviceRegistersOnChangeAndOtherwiseOnceADay() {
        fun fp(e: IdentityEnvelope = env, token: String = "t", version: String? = "2.4.0") = DeviceRegistration.fingerprint(token, "com.acme.app", version, "0.6.2", e)
        val base = fp()
        assertEquals(base, fp(env.copy()))
        assertEquals("the memory choice is not part of a registration", base, fp(env.copy(memory = false)))
        for (other in listOf(fp(token = "t2"), fp(env.copy(value = "P-9")), fp(env.copy(value = null)), fp(env.copy(timezone = "UTC")), fp(version = "2.5.0"))) {
            assertTrue("a change registers again", other != base)
        }
        assertFalse("the fingerprint never holds the token or the user", base.contains("P-1234") || base.contains("t"))
        val now = 1_000_000_000_000L
        assertTrue("never sent", DeviceRegistration.due(null, base, now))
        assertFalse("sent an hour ago, unchanged", DeviceRegistration.due(base to now - 3_600_000, base, now))
        assertTrue("changed", DeviceRegistration.due(fp(token = "t2") to now - 1_000, base, now))
        assertTrue("a day later", DeviceRegistration.due(base to now - DeviceRegistration.REFRESH_MS, base, now))
        assertTrue("the clock went back", DeviceRegistration.due(base to now + 60_000, base, now))
    }

    @Test fun theStoreKeepsTheLastRegistration() {
        val f = java.io.File(java.nio.file.Files.createTempDirectory("graine-reg").toFile(), "device.json")
        val store = DeviceStore(f)
        assertNull(store.lastRegistration())
        store.registered("abc", 42L)
        assertEquals("abc" to 42L, DeviceStore(f).lastRegistration())
        store.registered(null, 0L)
        assertNull(store.lastRegistration())
    }

    @Test fun eachAgentKeepsItsOwnRegistrationRecord() {
        val f = java.io.File(java.nio.file.Files.createTempDirectory("graine-reg-agents").toFile(), "device.json")
        val store = DeviceStore(f)
        store.registered("legacy", 1L)
        store.registered("support", 2L, "owner-support")
        store.registered("sales", 3L, "owner-sales")
        val again = DeviceStore(f)
        assertEquals("legacy" to 1L, again.lastRegistration())
        assertEquals("support" to 2L, again.lastRegistration("owner-support"))
        assertEquals("sales" to 3L, again.lastRegistration("owner-sales"))
        assertNull(again.lastRegistration("owner-billing"))
        store.registered(null, 0L, "owner-sales")
        assertNull(store.lastRegistration("owner-sales"))
        assertEquals("support" to 2L, store.lastRegistration("owner-support"))
    }

    @Test fun eachAgentsSessionIsKeptInASlotOfItsOwnWithItsIds() {
        val slots = HashMap<String, String>()
        val cache = SessionCache(slotRead = { slots[it] }, slotWrite = { slot, text -> slots[slot] = text })
        val support = JSONObject().put("agentId", "agent-support").put("webcallAgentId", "web-support")
            .put("design", JSONObject().put("light", JSONObject().put("colors", JSONObject().put("accent", "#7C3AED"))))
        val sales = JSONObject().put("agentId", "agent-sales")
            .put("design", JSONObject().put("light", JSONObject().put("colors", JSONObject().put("accent", "#0EA5E9"))))
        cache.save("https://www.graine.ai", "pk_support", support)
        cache.save("https://www.graine.ai", "pk_sales", sales)
        assertEquals("one slot per agent", 2, slots.size)
        val a = cache.load("https://www.graine.ai", "pk_support")!!
        val b = cache.load("https://www.graine.ai", "pk_sales")!!
        assertEquals("agent-support" to "web-support", a.agentId to a.webcallAgentId)
        assertEquals("agent-sales", b.agentId)
        assertNull("no twin, none kept", b.webcallAgentId)
        assertEquals("#7C3AED", a.design!!.light.colors.accent)
        assertEquals("#0EA5E9", b.design!!.light.colors.accent)
        // A session kept by a build from before agents had slots is still found, for its own agent only.
        val legacy = HashMap<String, String>()
        SessionCache(read = { legacy["last"] }, write = { legacy["last"] = it }).save("https://www.graine.ai", "pk_support", support)
        val upgraded = SessionCache(slotRead = { legacy[it] }, slotWrite = { slot, text -> legacy[slot] = text })
        assertEquals("agent-support", upgraded.load("https://www.graine.ai", "pk_support")?.agentId)
        assertNull(upgraded.load("https://www.graine.ai", "pk_sales"))
    }

    @Test fun theLastSessionIsKeptForItsOwnAgentOnly() {
        var kept: String? = null
        val cache = SessionCache(read = { kept }, write = { kept = it })
        val session = JSONObject()
            .put("design", JSONObject().put("light", JSONObject().put("colors", JSONObject().put("accent", "#7C3AED"))))
            .put("appearance", JSONObject().put("accent", "#7C3AED").put("title", "Acme").put("variables", JSONObject().put("x", "y")))
            .put("calls", JSONObject().put("hosted", true).put("ringTimeoutSeconds", 40))
            .put("variables", JSONObject().put("secretish", "not kept"))
        cache.save("https://www.graine.ai/", "pk_a", session)
        assertFalse("only what a cold start draws is kept", kept!!.contains("not kept"))
        val k = cache.load("https://www.graine.ai", "pk_a")!!
        assertEquals("#7C3AED", k.design!!.light.colors.accent)
        assertEquals("Acme", k.appearance!!.getString("title"))
        assertFalse(k.appearance!!.has("variables"))
        assertEquals(40, k.rules.ringTimeoutSeconds)
        assertTrue(k.rules.hostedRinging)
        assertNull("another agent's build never draws this one's look", cache.load("https://www.graine.ai", "pk_b"))
        assertNull(SessionCache(read = { "garbage" }, write = {}).load("https://www.graine.ai", "pk_a"))
    }

    @Test fun theSessionsNewBlocksAreRead() {
        val s = SessionFetch.parse(JSONObject("""{"agentId":"a","identity":{"field":"patient_id","devicesPerUser":1},
            "memory":{"enabled":true},"calls":{"hosted":true,"scheduling":{"usersCanBook":true}}}"""))
        assertEquals(SessionIdentity("patient_id", 1), s.identity)
        assertEquals(true, s.memoryEnabled)
        assertTrue(s.schedulingRules.hostedRinging)
        assertTrue(s.schedulingRules.usersCanBook)
        val old = SessionFetch.parse(JSONObject("""{"agentId":"a"}"""))
        assertNull(old.identity)
        assertNull(old.memoryEnabled)
        assertEquals(GraineSchedulingRules(), old.schedulingRules)
    }
}
