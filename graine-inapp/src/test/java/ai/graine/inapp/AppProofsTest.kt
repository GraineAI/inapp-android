package ai.graine.inapp

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * PROVING THE APP AND THE USER (APP-IDENTITY.md §1–§3), with Play Integrity,
 * the platform's certificates and Graine's routes faked: the declaration on
 * every request; the app pass kept, sent until it lapses (15 minutes longer
 * about a call that rang), read back per key and install, renewed under six
 * hours; "enforce" waiting for a proof on its way; `app_not_verified` proven
 * and sent once more; a spent challenge replaced once; Google out of reach
 * backing off 30 s, 2 min, 10 min, then the next foreground, with the outage
 * grace when a pass is held; no Play Integrity in the app — unverified, and
 * nothing breaks; development mode's debug token — debug builds only unless
 * the app says otherwise; and the user's token: inside the identity only,
 * never across an account change, refreshed when it lapses or is refused, at
 * most every 30 seconds and never twice at once.
 */
class AppProofsTest {
    private val sched = ManualScheduler()
    private var wall = 1_800_000_000_000L
    private fun now(): Long = wall + sched.now()

    private val embed = FakeEmbed()
    private val logs = mutableListOf<String>()
    private var prover: IntegrityProver? = FakeProver(IntegrityAnswer.Token("play-token-1"))
    private var debuggable = false
    private val device = DeviceStore(null)
    private var store = AppPassStore.memory()

    @Before fun routes() {
        var n = 0
        embed.answer("app-verify/challenge", { FakeEmbed.ok(JSONObject().put("challenge", FakeEmbed.challenge(++n)).put("expiresIn", 300))(it) })
        embed.answer("app-verify", FakeEmbed.pass("ap1.first"))
    }

    private fun app(worker: CallScheduler = sched, enforceWaitMs: Long = 10_000, declaration: AppDeclaration? = DECLARED) = AppProofs(
        declaration = { declaration },
        store = store,
        device = { device },
        debuggable = { debuggable },
        prover = { prover },
        worker = worker,
        clock = { now() },
        log = { logs += it },
        enforceWaitMs = enforceWaitMs,
    )

    private fun user() = UserProof(clock = { now() })

    private fun proofs(app: AppProofs = app(), user: UserProof = user()): Proofs =
        Proofs(app, user, fieldFor = { "user_id" }).also { app.register(KEY, BASE, embed) }

    private fun security(app: String, user: String = "off", cloud: String? = null) = SecuritySettings.parse(
        JSONObject().put("app", JSONObject().put("verify", app).apply { cloud?.let { put("cloudProjectNumber", it) } })
            .put("user", JSONObject().put("verify", user)),
    )

    /** A request to [path] through the door, as the SDK sends one. */
    private fun send(p: Proofs, path: String, identity: IdentityEnvelope? = null, callId: String? = null, extra: JSONObject.() -> Unit = {}): HttpReply {
        val body = JSONObject().put("publishableKey", KEY)
        identity?.into(body)
        callId?.let { body.put("callId", it) }
        body.extra()
        val url = EMBED + path
        return p.send(url, body, false) { json -> embed.post(url, json) }
    }

    private fun paths(): List<String> = embed.sent.map { it.path }

    // ── the declaration ─────────────────────────────────────────────────────

    @Test fun everyRequestSaysWhichAppItIs() {
        val p = proofs()
        send(p, "ticket")
        val app = embed.to("ticket").single().getJSONObject("app")
        assertEquals("android", app.getString("platform"))
        assertEquals("com.acme.app", app.getString("id"))
        assertEquals(CERT, app.getJSONArray("certs").getString(0))
        assertFalse("no pass held, none sent", app.has("pass"))
        assertFalse("a release build sends no debug token", app.has("debugToken"))
    }

    @Test fun withNoPackageNameThereIsNoDeclarationAtAll() {
        val p = proofs(app(declaration = null))
        send(p, "memory/list")
        assertFalse(embed.to("memory/list").single().has("app"))
    }

    @Test fun certificatesAsTheContractReadsThem() {
        val a = "a".toByteArray()
        val b = "b".toByteArray()
        val c = "c".toByteArray()
        fun hex(x: ByteArray) = AppDeclarations.sha256Hex(x)
        // API 28+: several signers — the current ones; one — every certificate in the rotation history.
        val several = FakeSigning(info = SigningCertificates(true, listOf(a, b), listOf(c)))
        assertEquals(listOf(hex(a), hex(b)), AppDeclarations.certificates("com.acme.app", 28, several))
        val rotated = FakeSigning(info = SigningCertificates(false, listOf(c), listOf(a, b, a)))
        assertEquals("de-duplicated", listOf(hex(a), hex(b)), AppDeclarations.certificates("com.acme.app", 33, rotated))
        assertEquals(listOf("signingInfo:com.acme.app"), rotated.asked)
        // API 24–27: the signatures.
        val legacy = FakeSigning(legacy = listOf(b))
        assertEquals(listOf(hex(b)), AppDeclarations.certificates("com.acme.app", 27, legacy))
        assertEquals(listOf("signatures:com.acme.app"), legacy.asked)
        // At most five.
        val many = (1..8).map { "cert$it".toByteArray() }
        assertEquals(5, AppDeclarations.certificates("com.acme.app", 30, FakeSigning(info = SigningCertificates(false, null, many)))!!.size)
        // Anything unreadable: no certs (the declaration goes without them).
        assertNull(AppDeclarations.certificates("com.acme.app", 30, FakeSigning(fail = true)))
        assertNull(AppDeclarations.certificates("com.acme.app", 30, FakeSigning(info = null)))
        assertNull(AppDeclarations.certificates("com.acme.app", 25, FakeSigning(legacy = emptyList())))
        val noCerts = AppDeclaration("com.acme.app", null).json()
        assertFalse(noCerts.has("certs"))
    }

    // ── passes ──────────────────────────────────────────────────────────────

    @Test fun aProofGivesAPassThatIsKeptAndSent() {
        val fake = FakeProver(IntegrityAnswer.Token("play-token-1"))
        prover = fake
        val a = app()
        val p = proofs(a)
        a.onSession(KEY, security("report", cloud = "123456789012"))
        assertEquals(GraineAppVerdict.CHECKING, a.verdict(KEY).first)
        sched.drain()
        assertEquals(listOf("app-verify/challenge", "app-verify"), paths())
        val challenge = embed.to("app-verify/challenge").single()
        assertTrue("the install id goes with it", challenge.getString("installId").isNotEmpty())
        assertEquals("com.acme.app", challenge.getJSONObject("app").getString("id"))
        val verify = embed.to("app-verify").single()
        assertEquals(FakeEmbed.challenge(1), verify.getString("challenge"))
        assertEquals("the challenge is Play Integrity's nonce", listOf(FakeEmbed.challenge(1)), fake.nonces)
        assertEquals(listOf<Long?>(123456789012L), fake.cloudNumbers)
        assertTrue(sameJson(JSONObject().put("kind", "play").put("token", "play-token-1"), verify.getJSONObject("proof")))
        assertEquals(GraineAppVerdict.VERIFIED, a.verdict(KEY).first)
        send(p, "ticket")
        assertEquals("ap1.first", embed.to("ticket").single().getJSONObject("app").getString("pass"))
    }

    @Test fun thePassIsKeptPerKeyAndInstallAndReadBack() {
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        // Another launch: the same store, the same install.
        val again = app()
        val p = proofs(again)
        send(p, "devices")
        assertEquals("ap1.first", embed.to("devices").single().getJSONObject("app").getString("pass"))
        // Another agent's key never sees it.
        send(p, "devices") { put("publishableKey", "pk_other") }
        assertFalse(embed.to("devices").last().getJSONObject("app").has("pass"))
        // A pass kept for another install (a backup restored on a new phone) is never read back.
        val otherInstall = AppProofs({ DECLARED }, store, { DeviceStore(null) }, { false }, { prover }, sched, { now() })
        val p2 = Proofs(otherInstall, user(), { "user_id" }).also { otherInstall.register(KEY, BASE, embed) }
        send(p2, "devices")
        assertFalse(embed.to("devices").last().getJSONObject("app").has("pass"))
    }

    @Test fun aLapsedPassIsNotSentExceptAboutACallThatRangForFifteenMinutes() {
        embed.answer("app-verify", FakeEmbed.pass("ap1.short", ttl = 600))
        val a = app()
        val p = proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        wall += 10 * 60_000L + 5 * 60_000L // lapsed five minutes ago
        send(p, "calls/list")
        assertFalse(embed.to("calls/list").last().getJSONObject("app").has("pass"))
        send(p, "rtc-session", callId = "call_ring_1")
        assertEquals("a ring's answer carries the verdict it rang under", "ap1.short", embed.to("rtc-session").last().getJSONObject("app").getString("pass"))
        wall += 11 * 60_000L // sixteen minutes
        send(p, "rtc-session", callId = "call_ring_1")
        assertFalse(embed.to("rtc-session").last().getJSONObject("app").has("pass"))
    }

    @Test fun aPassIsRenewedInTheBackgroundOnceLessThanSixHoursRemain() {
        embed.answer("app-verify", FakeEmbed.pass("ap1.a", ttl = 7 * 3600L), FakeEmbed.pass("ap1.b"))
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        assertEquals(1, embed.to("app-verify").size)
        a.foreground()
        sched.drain()
        assertEquals("seven hours left: nothing to renew", 1, embed.to("app-verify").size)
        wall += 61 * 60_000L
        a.foreground()
        sched.drain()
        assertEquals("under six hours: renewed", 2, embed.to("app-verify").size)
        assertEquals(GraineAppVerdict.VERIFIED, a.verdict(KEY).first)
    }

    // ── enforce ─────────────────────────────────────────────────────────────

    @Test fun underEnforceAConversationWaitsForTheProofOnItsWay() {
        val release = CountDownLatch(1)
        prover = FakeProver(IntegrityAnswer.Token("t")).apply { before = { release.await(5, TimeUnit.SECONDS) } }
        val worker = ExecutorScheduler("app-proof-test")
        val a = app(worker = worker)
        val p = proofs(a)
        a.onSession(KEY, security("enforce"))
        Thread { Thread.sleep(150); release.countDown() }.start()
        val started = System.nanoTime()
        send(p, "ticket")
        val waited = (System.nanoTime() - started) / 1_000_000
        assertTrue("it waited for the proof ($waited ms)", waited >= 100)
        assertEquals("ap1.first", embed.to("ticket").single().getJSONObject("app").getString("pass"))
    }

    @Test fun underEnforceTheWaitIsBounded() {
        val release = CountDownLatch(1)
        prover = FakeProver(IntegrityAnswer.Token("t")).apply { before = { release.await(5, TimeUnit.SECONDS) } }
        val a = app(worker = ExecutorScheduler("app-proof-test"), enforceWaitMs = 200)
        val p = Proofs(a, user(), { "user_id" }).also { a.register(KEY, BASE, embed) }
        a.onSession(KEY, security("enforce"))
        val started = System.nanoTime()
        send(p, "rtc-session")
        val waited = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertTrue("it went after its wait ($waited ms)", waited in 150..3_000)
        assertFalse(embed.to("rtc-session").single().getJSONObject("app").has("pass"))
    }

    @Test fun reportModeNeverWaits() {
        val release = CountDownLatch(1)
        prover = FakeProver(IntegrityAnswer.Token("t")).apply { before = { release.await(5, TimeUnit.SECONDS) } }
        val a = app(worker = ExecutorScheduler("app-proof-test"))
        val p = proofs(a)
        a.onSession(KEY, security("report"))
        val started = System.nanoTime()
        send(p, "ticket")
        val waited = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertTrue("no wait in report mode ($waited ms)", waited < 1_000)
    }

    // ── refusals ────────────────────────────────────────────────────────────

    @Test fun appNotVerifiedIsProvenThenSentOnceMore() {
        embed.answer("ticket", FakeEmbed.refused(403, "app_not_verified", "missing"), FakeEmbed.ok(JSONObject().put("ticket", "t1")))
        val p = proofs()
        val reply = send(p, "ticket")
        assertEquals(200, reply.code)
        assertEquals(listOf("ticket", "app-verify/challenge", "app-verify", "ticket"), paths())
        val (first, second) = embed.to("ticket")
        assertFalse(first.getJSONObject("app").has("pass"))
        assertEquals("ap1.first", second.getJSONObject("app").getString("pass"))
    }

    @Test fun refusedAgainItIsNotSentAThirdTime() {
        embed.answer("memory/list", FakeEmbed.refused(403, "app_not_verified", "invalid"))
        val p = proofs()
        val reply = send(p, "memory/list")
        assertEquals(403, reply.code)
        assertEquals(listOf("memory/list", "app-verify/challenge", "app-verify", "memory/list"), paths())
        assertEquals(GraineErrorCode.APP_NOT_VERIFIED, EmbedApi.errorFor(reply.code, JSONObject(reply.body)).code)
        // Right after a proof Graine refused, the next refusal is not proven again.
        send(p, "memory/list")
        assertEquals(1, embed.to("app-verify").size)
    }

    @Test fun appNotAllowedIsFinal() {
        embed.answer("ticket", FakeEmbed.refused(403, "app_not_allowed"))
        val a = app()
        val p = proofs(a)
        val reply = send(p, "ticket")
        assertEquals(listOf("ticket"), paths())
        assertEquals(GraineErrorCode.APP_NOT_ALLOWED, EmbedRequest.appRefusal(reply)?.code)
        // Nothing is proven for an app the agent does not take — until the app comes back to the front.
        a.onSession(KEY, security("report"))
        sched.drain()
        assertEquals(listOf("ticket"), paths())
        a.foreground()
        sched.drain()
        assertEquals(listOf("ticket", "app-verify/challenge", "app-verify"), paths())
    }

    @Test fun aSpentChallengeIsReplacedOnce() {
        embed.answer("app-verify", FakeEmbed.refused(403, "app_not_verified", "challenge"), FakeEmbed.pass("ap1.second"))
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        assertEquals(listOf("app-verify/challenge", "app-verify", "app-verify/challenge", "app-verify"), paths())
        assertEquals(FakeEmbed.challenge(2), embed.to("app-verify").last().getString("challenge"))
        assertEquals(GraineAppVerdict.VERIFIED, a.verdict(KEY).first)
    }

    @Test fun aSecondSpentChallengeEndsTheProof() {
        embed.answer("app-verify", FakeEmbed.refused(403, "app_not_verified", "challenge"))
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        assertEquals(2, embed.to("app-verify/challenge").size)
        assertEquals(2, embed.to("app-verify").size)
        assertEquals(GraineAppVerdict.UNVERIFIED, a.verdict(KEY).first)
    }

    @Test fun aRefusedProofSaysWhyAndBacksOff() {
        embed.answer("app-verify", FakeEmbed.refused(403, "app_not_verified", "not_configured"))
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        assertEquals(GraineAppVerdict.UNVERIFIED to "not_configured", a.verdict(KEY))
        a.foreground()
        sched.drain()
        assertEquals("not again before its wait", 1, embed.to("app-verify").size)
        sched.advance(30_000)
        assertEquals(2, embed.to("app-verify").size)
    }

    // ── Google out of reach ─────────────────────────────────────────────────

    @Test fun googleOutOfReachBacksOff30Seconds2Minutes10MinutesThenTheNextForeground() {
        val fake = FakeProver(IntegrityAnswer.Unreachable("integrity error -12"))
        prover = fake
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        assertEquals(1, fake.nonces.size)
        a.ensure(KEY)
        sched.drain()
        assertEquals("not before 30 s", 1, fake.nonces.size)
        sched.advance(29_999)
        assertEquals(1, fake.nonces.size)
        sched.advance(1)
        assertEquals(2, fake.nonces.size)
        sched.advance(119_999)
        assertEquals(2, fake.nonces.size)
        sched.advance(1)
        assertEquals(3, fake.nonces.size)
        sched.advance(600_000)
        assertEquals(4, fake.nonces.size)
        sched.advance(3_600_000)
        assertEquals("then nothing until the app comes back", 4, fake.nonces.size)
        a.foreground()
        sched.drain()
        assertEquals(5, fake.nonces.size)
        // No pass was ever held: no grace was asked for.
        assertTrue(embed.to("app-verify").isEmpty())
    }

    @Test fun graineUnavailableBacksOffAndRetryAfterIsHonoured() {
        embed.answer("app-verify/challenge", FakeEmbed.status(429, retryAfter = 300), FakeEmbed.ok(JSONObject().put("challenge", FakeEmbed.challenge(9))))
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        sched.advance(30_000)
        assertEquals("Retry-After, not 30 s", 1, embed.to("app-verify/challenge").size)
        sched.advance(270_000)
        assertEquals(2, embed.to("app-verify/challenge").size)
        assertEquals(GraineAppVerdict.VERIFIED, a.verdict(KEY).first)
    }

    @Test fun aPassHeldCarriesTheInstallThroughAnOutage() {
        embed.answer("app-verify", FakeEmbed.pass("ap1.old", ttl = 600), FakeEmbed.pass("ap1.grace", ttl = 3600))
        val a = app()
        val p = proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        wall += 2 * 86_400_000L // the pass lapsed two days ago
        prover = null
        val fake = FakeProver(IntegrityAnswer.Unreachable("integrity error -3"))
        val b = AppProofs({ DECLARED }, store, { device }, { false }, { fake }, sched, { now() })
        val p2 = Proofs(b, user(), { "user_id" }).also { b.register(KEY, BASE, embed) }
        b.onSession(KEY, security("enforce"))
        sched.drain()
        val grace = embed.to("app-verify").last()
        assertTrue(sameJson(JSONObject().put("kind", "unavailable"), grace.getJSONObject("proof")))
        assertFalse("no challenge for the grace", grace.has("challenge"))
        assertEquals("the lapsed pass goes in app.pass", "ap1.old", grace.getJSONObject("app").getString("pass"))
        send(p2, "ticket")
        assertEquals("ap1.grace", embed.to("ticket").single().getJSONObject("app").getString("pass"))
        assertEquals(GraineAppVerdict.VERIFIED, b.verdict(KEY).first)
        assertNotNull(p)
    }

    @Test fun noGraceWithoutAPassOrWithOneSevenDaysGone() {
        prover = FakeProver(IntegrityAnswer.Unreachable("integrity error -8"))
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        assertTrue(embed.to("app-verify").isEmpty())
        // A pass that lapsed eight days ago: its proof is past the grace.
        store.save(SessionCache.owner(BASE, KEY), AppPass("ap1.ancient", now() - 8 * 86_400_000L, GraineAppVerdict.VERIFIED, device.installId()))
        val b = app()
        proofs(b)
        b.onSession(KEY, security("report"))
        sched.drain()
        assertTrue(embed.to("app-verify").isEmpty())
    }

    @Test fun aDeviceThatCannotGiveATokenIsUnverifiedAndTriedAgainAtTheNextForeground() {
        prover = FakeProver(IntegrityAnswer.Unavailable("integrity error -2"))
        val a = app()
        proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        assertEquals(GraineAppVerdict.UNVERIFIED, a.verdict(KEY).first)
        sched.advance(3_600_000)
        assertEquals(1, embed.to("app-verify/challenge").size)
        a.foreground()
        sched.drain()
        assertEquals(2, embed.to("app-verify/challenge").size)
    }

    @Test fun playIntegrityErrorsAsTheContractSorts() {
        for (code in listOf(-8, -12, -3, -100)) {
            val a = IntegrityErrors.classify(code, "x")
            assertTrue("$code backs off", a is IntegrityAnswer.Unreachable && a.outage)
        }
        assertTrue((IntegrityErrors.classify(-17, "x") as IntegrityAnswer.Unreachable).outage.not())
        for (code in listOf(-1, -2, -6, -9)) assertTrue("$code: no proof", IntegrityErrors.classify(code, "x") is IntegrityAnswer.Unavailable)
        assertTrue(IntegrityErrors.classify(null, "RuntimeException") is IntegrityAnswer.Unavailable)
    }

    // ── without Play Integrity ──────────────────────────────────────────────

    @Test fun withoutPlayIntegrityTheAppIsUnverifiedAndNothingBreaks() {
        prover = null
        val a = app()
        val p = proofs(a)
        a.onSession(KEY, security("enforce"))
        sched.drain()
        assertEquals(GraineAppVerdict.UNVERIFIED, a.verdict(KEY).first)
        assertTrue("nothing is asked of Graine", embed.sent.isEmpty())
        assertEquals("one line names the dependency", 1, logs.count { it.contains("com.google.android.play:integrity:1.4.0") })
        send(p, "calls/list")
        sched.advance(3_600_000)
        assertEquals(listOf("calls/list"), paths())
        assertEquals(1, logs.count { it.contains("com.google.android.play:integrity") })
    }

    @Test fun thePresenceCheckLoadsNothing() {
        assertFalse(PlayIntegrity.present(className = "com.google.android.play.core.integrity.NoSuchFactory"))
        assertTrue(PlayIntegrity.present(className = "ai.graine.inapp.GraineInApp"))
        assertEquals("com.google.android.play.core.integrity.IntegrityManagerFactory", PlayIntegrity.FACTORY_CLASS)
    }

    // ── development mode ────────────────────────────────────────────────────

    @Test fun aDebugBuildSendsItsDebugTokenInsteadOfAProof() {
        debuggable = true
        val fake = FakeProver(IntegrityAnswer.Token("never"))
        prover = fake
        val a = app()
        val p = proofs(a)
        a.onSession(KEY, security("enforce"))
        sched.drain()
        send(p, "ticket")
        send(p, "session")
        val tokens = embed.sent.map { it.body.getJSONObject("app").getString("debugToken") }
        assertTrue(tokens.all { it == tokens[0] && DebugTokens.valid(it) })
        assertTrue("Play Integrity is never asked", fake.nonces.isEmpty())
        assertTrue(embed.to("app-verify/challenge").isEmpty())
        assertEquals("kept with the install id", tokens[0], device.debugToken)
        assertEquals(tokens[0], a.debugToken())
        assertEquals(listOf(DebugTokens.logLine(tokens[0])), logs)
        // Another launch on the same install: the same token, not logged again.
        val b = app()
        assertEquals(tokens[0], b.debugToken())
        assertEquals(1, logs.size)
    }

    @Test fun aReleaseBuildNeverMakesLogsOrSendsADebugToken() {
        debuggable = false
        val a = app()
        val p = proofs(a)
        a.onSession(KEY, security("enforce"))
        sched.drain()
        send(p, "ticket")
        assertTrue(embed.sent.none { it.body.optJSONObject("app")?.has("debugToken") == true })
        assertNull(a.debugToken())
        assertNull("never made", device.debugToken)
        assertTrue(logs.none { it.contains("debug token") })
    }

    @Test fun alwaysSendsOneFromAReleaseBuild() {
        debuggable = false
        val fake = FakeProver(IntegrityAnswer.Token("never"))
        prover = fake
        val a = app()
        a.debugTokenMode = GraineDebugTokenMode.ALWAYS
        val p = proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        send(p, "ticket")
        assertTrue(DebugTokens.valid(embed.to("ticket").single().getJSONObject("app").getString("debugToken")))
        assertTrue(fake.nonces.isEmpty())
    }

    @Test fun neverAsksPlayIntegrityInADebugBuild() {
        debuggable = true
        val fake = FakeProver(IntegrityAnswer.Token("play-token-1"))
        prover = fake
        val a = app()
        a.debugTokenMode = GraineDebugTokenMode.NEVER
        val p = proofs(a)
        a.onSession(KEY, security("report"))
        sched.drain()
        assertEquals("the debug build proves itself like a release one", 1, fake.nonces.size)
        send(p, "ticket")
        val app = embed.to("ticket").single().getJSONObject("app")
        assertFalse(app.has("debugToken"))
        assertEquals("ap1.first", app.getString("pass"))
        assertNull(a.debugToken())
    }

    @Test fun inDevelopmentModeARefusalIsNotProvenAndSaysWhy() {
        debuggable = true
        embed.answer("ticket", FakeEmbed.refused(403, "app_not_verified", "debug_token_unknown"))
        val a = app()
        val p = proofs(a)
        a.onSession(KEY, security("enforce"))
        send(p, "ticket")
        assertEquals(listOf("ticket"), paths())
        assertEquals(GraineAppVerdict.UNVERIFIED to "debug_token_unknown", a.verdict(KEY))
        embed.answer("ticket", FakeEmbed.ok(JSONObject().put("verification", JSONObject().put("app", "debug_token").put("user", "off"))))
        send(p, "ticket")
        assertEquals(GraineAppVerdict.DEBUG_TOKEN, a.verdict(KEY).first)
    }

    // ── the verdict ─────────────────────────────────────────────────────────

    @Test fun theVerdictFollowsTheSessionTheProofAndGraine() {
        val a = app()
        val p = proofs(a)
        assertEquals("off before the session", GraineAppVerdict.OFF, a.verdict(KEY).first)
        a.onSession(KEY, security("off"))
        assertEquals(GraineAppVerdict.OFF, a.verdict(KEY).first)
        a.onSession(KEY, security("report"))
        assertEquals(GraineAppVerdict.CHECKING, a.verdict(KEY).first)
        sched.drain()
        assertEquals(GraineAppVerdict.VERIFIED, a.verdict(KEY).first)
        // Graine says the pass that went with a request is no good: it goes, and the app is proven again.
        embed.answer("ticket", FakeEmbed.ok(JSONObject().put("verification", JSONObject().put("app", "unverified").put("user", "off"))))
        embed.answer("app-verify", FakeEmbed.pass("ap1.test", verdict = "test_build"))
        send(p, "ticket")
        assertEquals(GraineAppVerdict.CHECKING, a.verdict(KEY).first)
        sched.drain()
        assertEquals(GraineAppVerdict.TEST_BUILD, a.verdict(KEY).first)
    }

    // ── the user ────────────────────────────────────────────────────────────

    private val alice = mapOf("user_id" to "user-123", "name" to "Alice")
    private val bob = mapOf("user_id" to "user-456", "name" to "Bob")
    private fun who(value: String?) = IdentityEnvelope(value, "inst-1", true, "Asia/Kolkata")

    private fun userPass(pass: String) = FakeEmbed.ok(JSONObject().put("verification",
        JSONObject().put("app", "off").put("user", "verified").put("userPass", pass).put("userPassExpiresIn", 3600)))

    @Test fun theTokenAndThePassRideInsideTheIdentityAndNowhereElse() {
        val u = user()
        val p = proofs(user = u)
        u.identify(alice, "tok-alice-6c249fec", sameUser = false)
        embed.answer("ticket", userPass("up1.alice"))
        send(p, "ticket", who("user-123")) {
            put("metadata", JSONObject().put("name", "Alice"))
            put("variables", JSONObject().put("plan", "gold"))
        }
        val first = embed.to("ticket").single()
        assertEquals("tok-alice-6c249fec", first.getJSONObject("identity").getString("token"))
        assertFalse(first.getJSONObject("identity").has("pass"))
        val rest = JSONObject(first.toString()).apply { remove("identity") }.toString()
        assertFalse("the token is nowhere but the identity", rest.contains("tok-alice"))
        send(p, "memory/list", who("user-123"))
        assertEquals("up1.alice", embed.to("memory/list").single().getJSONObject("identity").getString("pass"))
        // Without a value there is no identity, and nothing rides.
        send(p, "calls/list", who(null))
        assertFalse(embed.to("calls/list").single().toString().contains("tok-alice"))
    }

    @Test fun anAccountChangeNeverSendsTheOldTokenOrPass() {
        val u = user()
        val p = proofs(user = u)
        u.identify(alice, "tok-alice", sameUser = false)
        embed.answer("ticket", userPass("up1.alice"))
        send(p, "ticket", who("user-123"))
        // A body built for Alice, sent after she signed out: nothing of hers rides.
        val late = who("user-123").into(JSONObject().put("publishableKey", KEY))
        u.identify(null, null, sameUser = false)
        p.stamp(late, KEY)
        assertFalse(late.getJSONObject("identity").has("token"))
        assertFalse(late.getJSONObject("identity").has("pass"))
        // Bob signs in without a token: none of Alice's.
        u.identify(bob, null, sameUser = false)
        send(p, "memory/list", who("user-456"))
        val bobs = embed.to("memory/list").single().getJSONObject("identity")
        assertFalse(bobs.has("token"))
        assertFalse(bobs.has("pass"))
        // …and Alice's value with Bob's session: still nothing.
        send(p, "calls/list", who("user-123"))
        assertFalse(embed.to("calls/list").single().getJSONObject("identity").has("token"))
        // Bob with his own token: his alone.
        u.identify(bob, "tok-bob", sameUser = false)
        send(p, "calls/list", who("user-456"))
        assertEquals("tok-bob", embed.to("calls/list").last().getJSONObject("identity").getString("token"))
    }

    @Test fun aPassForTheLastUserIsNotKeptWhenItsAnswerComesAfterTheChange() {
        val u = user()
        val p = proofs(user = u)
        u.identify(alice, "tok-alice", sameUser = false)
        val body = who("user-123").into(JSONObject().put("publishableKey", KEY))
        val sent = p.stamp(body, KEY)
        u.identify(bob, null, sameUser = false)
        u.heard(KEY, "user-123", sent.generation, JSONObject().put("user", "verified").put("userPass", "up1.late").put("userPassExpiresIn", 3600))
        u.identify(alice, null, sameUser = false)
        send(p, "memory/list", who("user-123"))
        assertFalse(embed.to("memory/list").single().getJSONObject("identity").has("pass"))
    }

    @Test fun theSameUserKeepsTheirTokenAndSetIdentityTokenKeepsTheIdentity() {
        val u = user()
        val p = proofs(user = u)
        u.identify(alice, "tok-1", sameUser = false)
        u.identify(alice, null, sameUser = true)
        send(p, "memory/list", who("user-123"))
        assertEquals("tok-1", embed.to("memory/list").last().getJSONObject("identity").getString("token"))
        assertTrue(u.setToken("tok-2"))
        assertFalse("the same token again changes nothing", u.setToken("tok-2"))
        send(p, "memory/list", who("user-123"))
        val identity = embed.to("memory/list").last().getJSONObject("identity")
        assertEquals("user-123", identity.getString("value"))
        assertEquals("tok-2", identity.getString("token"))
        u.setToken(null)
        send(p, "memory/list", who("user-123"))
        assertFalse(embed.to("memory/list").last().getJSONObject("identity").has("token"))
    }

    @Test fun aTokenLongerThanTheContractAllowsIsNeverSent() {
        val u = user()
        val p = proofs(user = u)
        u.identify(alice, "x".repeat(UserProof.MAX_TOKEN_LENGTH + 1), sameUser = false)
        send(p, "memory/list", who("user-123"))
        assertFalse(embed.to("memory/list").single().getJSONObject("identity").has("token"))
        u.identify(alice, "y".repeat(UserProof.MAX_TOKEN_LENGTH), sameUser = true)
        send(p, "memory/list", who("user-123"))
        assertEquals(UserProof.MAX_TOKEN_LENGTH, embed.to("memory/list").last().getJSONObject("identity").getString("token").length)
    }

    // ── the refresher ───────────────────────────────────────────────────────

    private class Refresher(var next: () -> String?) : GraineIdentityTokenRefresher {
        var calls = 0
        var held: GraineIdentityTokenCallback? = null
        var hold = false
        override fun refresh(done: GraineIdentityTokenCallback) {
            calls++
            if (hold) held = done else done.onToken(next())
        }
    }

    @Test fun anExpiredTokenAsksTheRefresher() {
        val u = user()
        val p = proofs(user = u)
        val r = Refresher { "tok-fresh" }
        u.refresher = r
        u.identify(alice, "tok-old", sameUser = false)
        embed.answer("ticket", FakeEmbed.ok(JSONObject().put("verification", JSONObject().put("user", "invalid").put("userReason", "expired"))))
        send(p, "ticket", who("user-123"))
        assertEquals(1, r.calls)
        assertEquals(GraineUserVerdict.INVALID to "expired", u.verdict(KEY, checksUsers = true))
        send(p, "memory/list", who("user-123"))
        assertEquals("tok-fresh", embed.to("memory/list").single().getJSONObject("identity").getString("token"))
    }

    @Test fun identityUnverifiedIsRefreshedAndSentOnceMore() {
        val u = user()
        val p = proofs(user = u)
        val r = Refresher { "tok-fresh" }
        u.refresher = r
        u.identify(alice, "tok-old", sameUser = false)
        embed.answer("memory/list", FakeEmbed.refused(403, "identity_unverified"))
        val reply = send(p, "memory/list", who("user-123"))
        assertEquals(403, reply.code)
        val bodies = embed.to("memory/list")
        assertEquals("sent once more, and no more", 2, bodies.size)
        assertEquals("tok-old", bodies[0].getJSONObject("identity").getString("token"))
        assertEquals("tok-fresh", bodies[1].getJSONObject("identity").getString("token"))
        assertEquals(GraineUserVerdict.UNVERIFIED, u.verdict(KEY, checksUsers = true).first)
    }

    @Test fun withoutARefresherIdentityUnverifiedIsSaidAtOnce() {
        val u = user()
        val p = proofs(user = u)
        u.identify(alice, "tok-old", sameUser = false)
        embed.answer("sessions/list", FakeEmbed.refused(403, "identity_unverified"))
        val reply = send(p, "sessions/list", who("user-123"))
        assertEquals(GraineErrorCode.IDENTITY_UNVERIFIED, EmbedApi.errorFor(reply.code, JSONObject(reply.body)).code)
        assertEquals(1, embed.to("sessions/list").size)
    }

    @Test fun theRefresherIsAskedAtMostEveryThirtySecondsAndNeverTwiceAtOnce() {
        val u = user()
        val r = Refresher { "tok-${sched.now()}" }
        u.refresher = r
        u.identify(alice, "tok-0", sameUser = false)
        u.refreshInBackground()
        u.refreshInBackground()
        assertEquals(1, r.calls)
        sched.advance(29_999)
        u.refreshInBackground()
        assertEquals(1, r.calls)
        sched.advance(1)
        u.refreshInBackground()
        assertEquals(2, r.calls)
        // One that has not answered yet is never joined by a second.
        r.hold = true
        sched.advance(30_000)
        u.refreshInBackground()
        assertEquals(3, r.calls)
        sched.advance(31_000)
        u.refreshInBackground()
        assertEquals("never twice at once", 3, r.calls)
        r.held!!.onToken("tok-late")
        assertTrue(u.proofFor(KEY, "user-123", "user_id", false).token == "tok-late")
    }

    @Test fun aRefresherThatAnswersNullOrThrowsKeepsTheToken() {
        val u = user()
        u.identify(alice, "tok-0", sameUser = false)
        u.refresher = Refresher { null }
        assertFalse(u.refreshNow(1_000))
        assertEquals("tok-0", u.proofFor(KEY, "user-123", "user_id", false).token)
        sched.advance(30_000)
        u.refresher = GraineIdentityTokenRefresher { throw IllegalStateException("offline") }
        assertFalse(u.refreshNow(1_000))
        assertEquals("tok-0", u.proofFor(KEY, "user-123", "user_id", false).token)
    }

    @Test fun aRefreshForTheLastUserNeverLandsOnTheNext() {
        val u = user()
        val r = Refresher { "tok-for-alice" }.apply { hold = true }
        u.refresher = r
        u.identify(alice, "tok-0", sameUser = false)
        u.refreshInBackground()
        u.identify(bob, null, sameUser = false)
        r.held!!.onToken("tok-for-alice")
        assertNull(u.proofFor(KEY, "user-456", "user_id", false).token)
        assertNull(u.proofFor(KEY, "user-123", "user_id", false).token)
    }

    private fun jwt(exp: Long): String {
        fun b64(s: String) = DebugTokens.base64Url(s.toByteArray())
        return b64("""{"alg":"RS256","kid":"k1"}""") + "." + b64("""{"sub":"user-123","exp":$exp}""") + ".c2ln"
    }

    @Test fun aJwtAboutToLapseIsRefreshedBeforeAConversation() {
        val u = user()
        val p = proofs(user = u)
        val r = Refresher { jwt(now() / 1000 + 3600) }
        u.refresher = r
        u.identify(alice, jwt(now() / 1000 + 30), sameUser = false)
        send(p, "ticket", who("user-123"))
        assertEquals(1, r.calls)
        val sent = embed.to("ticket").single().getJSONObject("identity").getString("token")
        assertEquals("the ticket carries the fresh one", now() / 1000 + 3600, Jwt.expiresAt(sent))
        // Plenty of time left: nobody is asked.
        sched.advance(60_000)
        send(p, "rtc-session", who("user-123"))
        assertEquals(1, r.calls)
        // Not a conversation: never refreshed ahead.
        u.setToken(jwt(now() / 1000 + 10))
        sched.advance(60_000)
        send(p, "memory/list", who("user-123"))
        assertEquals(1, r.calls)
        // An HMAC token is never read as a JWT.
        u.setToken("6c249fec755515370f7f8bbc1ea341f1a9b2a40d62d1be9480ae9dece9c103a8")
        send(p, "ticket", who("user-123"))
        assertEquals(1, r.calls)
    }

    @Test fun theUserVerdictIsOffUntilTheAgentChecksUsers() {
        val u = user()
        val p = proofs(user = u)
        assertEquals(GraineUserVerdict.OFF to null, u.verdict(KEY, checksUsers = false))
        assertEquals(GraineUserVerdict.UNVERIFIED to null, u.verdict(KEY, checksUsers = true))
        u.identify(alice, null, sameUser = false)
        embed.answer("ticket", FakeEmbed.ok(JSONObject().put("verification", JSONObject().put("user", "test_user"))))
        send(p, "ticket", who("user-123"))
        assertEquals(GraineUserVerdict.TEST_USER, u.verdict(KEY, checksUsers = true).first)
        u.identify(bob, null, sameUser = false)
        assertEquals("another user: nothing heard about them yet", GraineUserVerdict.UNVERIFIED to null, u.verdict(KEY, checksUsers = true))
    }

    @Test fun sameUserComparesWhatIdentifiesTheUser() {
        assertTrue(IdentityRules.sameUser(alice, alice + ("plan" to "gold"), listOf("user_id")))
        assertFalse(IdentityRules.sameUser(alice, bob, listOf("user_id")))
        assertFalse(IdentityRules.sameUser(null, alice, listOf("user_id")))
        assertFalse(IdentityRules.sameUser(alice, null, listOf("user_id")))
        // Before a session names the trait, every trait that could be one is compared.
        assertFalse(IdentityRules.sameUser(alice, alice + ("name" to "Alicia"), listOf(null)))
        assertTrue(IdentityRules.sameUser(alice, LinkedHashMap(alice), listOf(null)))
        // Two agents: every trait either names.
        val withEmail = alice + ("email" to "alice@example.com")
        assertFalse(IdentityRules.sameUser(withEmail, alice + ("email" to "other@example.com"), listOf("user_id", "email")))
    }

    companion object {
        const val KEY = "pk_test_app"
        const val BASE = "https://www.graine.ai"
        /** Every route's prefix, from a route the contract names. */
        val EMBED = AppProofs.CHALLENGE_PATH.removeSuffix("app-verify/challenge").let { BASE + it }
        val CERT = AppDeclarations.sha256Hex("the app's certificate".toByteArray())
        internal val DECLARED = AppDeclaration("com.acme.app", listOf(CERT))

        /** Proofs with nothing configured: a release build, no Play Integrity, no session. */
        internal fun appProofs(): AppProofs = AppProofs({ DECLARED }, AppPassStore.memory(), { DeviceStore(null) }, { false }, { null }, ManualScheduler())

        internal fun proofs(): Proofs = Proofs(appProofs(), UserProof(), { "user_id" })
    }
}
