package ai.graine.inapp

import android.app.Application
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutionException

/*
 * Pushes with no code in the app, proven on the JVM: what the SDK does at start
 * with Firebase missing, not set up, holding back its tokens, or giving one;
 * that a token it already holds is never sent again; and that Firebase stays
 * optional — compiled against, never shipped, never touched outside one file,
 * declared below an app's own messaging service.
 */
class FirebasePushTest {
    private class TestApp : Application()

    /** Firebase as a test wants it: its automatic token creation, and the token it gives (or not). */
    private class FakeFirebase(
        private val automatic: () -> Boolean = { true },
        private val give: ((String?, Throwable?) -> Unit) -> Unit = { it("token-1", null) },
    ) : PushTokenSource {
        var asked = 0
        override fun autoInitEnabled(): Boolean = automatic()
        override fun token(done: (token: String?, error: Throwable?) -> Unit) {
            asked++
            give(done)
        }
    }

    private val handedOver = ArrayList<String>()

    private fun check(firebase: FakeFirebase, held: String? = null, present: Boolean = true): PushSetup =
        FirebasePush.check(present = present, source = { firebase }, held = { held }, handOver = { handedOver += it })

    @Before fun fresh() = FirebasePush.reset()

    @After fun clean() = FirebasePush.reset()

    // ── at start ────────────────────────────────────────────────────────────

    @Test fun withoutFirebaseNothingOfItIsTouched() {
        var made = false
        val found = FirebasePush.check(present = false, source = { made = true; FakeFirebase() }, held = { null }, handOver = { handedOver += it })
        assertEquals(PushSetup.NO_FIREBASE, found)
        assertFalse("the token source is not even made", made)
        assertTrue(handedOver.isEmpty())
        assertEquals(PushSetup.NO_FIREBASE, FirebasePush.setup)
    }

    @Test fun aFirebaseMissingAPieceCountsAsNone() {
        val found = FirebasePush.check(present = true, source = { throw NoClassDefFoundError("com/google/android/gms/tasks/Task") }, held = { null }, handOver = { handedOver += it })
        assertEquals(PushSetup.NO_FIREBASE, found)
    }

    @Test fun firebaseWithoutItsSetupAsksForNothing() {
        val firebase = FakeFirebase(automatic = { throw IllegalStateException("Default FirebaseApp is not initialized in this process") })
        assertEquals(PushSetup.NOT_SET_UP, check(firebase))
        assertEquals(0, firebase.asked)
        assertTrue(handedOver.isEmpty())
    }

    @Test fun anAppThatTurnedTokenCreationOffIsLeftToItsChoice() {
        val firebase = FakeFirebase(automatic = { false })
        assertEquals(PushSetup.AUTO_INIT_OFF, check(firebase))
        assertEquals("the SDK creates no token the app chose not to create", 0, firebase.asked)
    }

    @Test fun aTokenTheSdkDoesNotHoldGoesTheWayANewTokenGoes() {
        assertEquals(PushSetup.ASKED, check(FakeFirebase(), held = null))
        assertEquals(listOf("token-1"), handedOver)
    }

    @Test fun theTokenTheSdkAlreadyHoldsIsNotSentAgain() {
        assertEquals(PushSetup.ASKED, check(FakeFirebase(), held = "token-1"))
        assertTrue("registered when it was due, not at every launch", handedOver.isEmpty())
    }

    @Test fun aTokenThatChangedSinceTheLastLaunchIsHandedOver() {
        check(FakeFirebase(), held = "token-0")
        assertEquals(listOf("token-1"), handedOver)
    }

    @Test fun aTokenThatArrivesLaterIsHandedOverWhenItDoes() {
        var later: ((String?, Throwable?) -> Unit)? = null
        assertEquals(PushSetup.ASKED, check(FakeFirebase(give = { later = it })))
        assertTrue(handedOver.isEmpty())
        later!!("token-2", null)
        assertEquals(listOf("token-2"), handedOver)
        assertEquals(PushSetup.ASKED, FirebasePush.setup)
    }

    @Test fun noTokenIsAFailureInFirebasesOwnWord() {
        assertEquals(PushSetup.FAILED, check(FakeFirebase(give = { it(null, IOException("SERVICE_NOT_AVAILABLE")) })))
        assertEquals("SERVICE_NOT_AVAILABLE", FirebasePush.failure)
        check(FakeFirebase(give = { it(null, ExecutionException(IOException("java.io.IOException: AUTHENTICATION_FAILED"))) }))
        assertEquals("AUTHENTICATION_FAILED", FirebasePush.failure)
        check(FakeFirebase(give = { it("  ", null) }))
        assertEquals(PushSetup.FAILED, FirebasePush.setup)
        assertEquals("no token", FirebasePush.failure)
        assertTrue(handedOver.isEmpty())
    }

    @Test fun nothingFirebaseThrowsEscapes() {
        assertEquals(PushSetup.FAILED, check(FakeFirebase(give = { throw IllegalArgumentException("broken") })))
        assertEquals("broken", FirebasePush.failure)
        assertEquals(PushSetup.FAILED, check(FakeFirebase(automatic = { throw SecurityException("denied") })))
    }

    @Test fun aFailureReadsAsFirebasesWordOrItsKind() {
        assertNull(PushRules.reason(null))
        assertEquals("SERVICE_NOT_AVAILABLE", PushRules.reason(IOException("SERVICE_NOT_AVAILABLE")))
        assertEquals("IOException", PushRules.reason(IOException()))
        assertEquals("from the cause", PushRules.reason(RuntimeException(null, IOException("from the cause"))))
        assertEquals("each wrapping class goes", "SERVICE_NOT_AVAILABLE",
            PushRules.reason(ExecutionException(null, IOException("java.util.concurrent.ExecutionException: java.io.IOException: SERVICE_NOT_AVAILABLE"))))
        assertEquals("a sentence keeps its colon", "Please set a valid API key: it authenticates your project",
            PushRules.reason(IllegalArgumentException("Please set a valid API key: it authenticates your project")))
        val long = PushRules.reason(IOException("x".repeat(300)))!!
        assertEquals(120, long.length)
        assertTrue(long.endsWith("…"))
        // Two causes of each other, neither with a message: the walk still ends.
        val looped = RuntimeException(null as String?)
        val other = RuntimeException(null as String?)
        looped.initCause(other)
        other.initCause(looped)
        assertEquals("a cause chain that loops still ends", "RuntimeException", PushRules.reason(looped))
    }

    @Test fun isNewIsAnythingButNothingOrTheSame() {
        assertTrue(PushRules.isNew(null, "a"))
        assertTrue(PushRules.isNew("a", "b"))
        assertFalse(PushRules.isNew("a", "a"))
        assertFalse(PushRules.isNew("a", null))
        assertFalse(PushRules.isNew(null, " "))
    }

    @Test fun startRunsOnceAProcessAndNeverThrows() {
        FirebasePush.start(TestApp(), debug = true)
        assertEquals("the tests run without Firebase, as an app without it does", PushSetup.NO_FIREBASE, FirebasePush.setup)
        check(FakeFirebase(automatic = { false }))
        FirebasePush.start(TestApp(), debug = true)
        assertEquals("the second start did nothing", PushSetup.AUTO_INIT_OFF, FirebasePush.setup)
    }

    @Test fun firebaseIsFoundByItsClassAlone() {
        assertFalse("compiled against, never shipped: absent at run time", FirebasePush.present())
        assertTrue(FirebasePush.present(className = "java.lang.String"))
        assertFalse(FirebasePush.present(className = "com.example.Missing"))
        assertEquals("com.google.firebase.messaging.FirebaseMessaging", FirebasePush.MESSAGING_CLASS)
    }

    @Test fun aDebugBuildHearsWhenAnotherServiceReceivesThePushes() {
        assertNull(PushRules.outranked(null))
        assertNull(PushRules.outranked(PushRules.SDK_SERVICE))
        assertNull("Firebase's built-in one takes only what nobody else does", PushRules.outranked(PushRules.FIREBASE_SERVICE))
        val said = PushRules.outranked("com.acme.AcmeMessagingService")
        assertNotNull(said)
        assertTrue(said!!.contains("com.acme.AcmeMessagingService"))
        assertTrue(said.contains("GraineCalls.handlePush(context, message.data)"))
    }

    // ── what ships ──────────────────────────────────────────────────────────

    private fun module(path: String): File = listOf(File(path), File("graine-inapp/$path")).first { it.exists() }

    @Test fun theManifestDeclaresTheServiceBelowAnAppsOwn() {
        val manifest = module("src/main/AndroidManifest.xml").readText()
        val service = manifest.substringAfter("android:name=\"${PushRules.SDK_SERVICE}\"").substringBefore("</service>")
        assertTrue("not callable by another app", service.contains("android:exported=\"false\""))
        assertTrue(service.contains("<action android:name=\"${FirebasePush.MESSAGING_EVENT}\" />"))
        assertTrue("an app's own service (priority 0) outranks it", service.contains("<intent-filter android:priority=\"-1\">"))
    }

    @Test fun firebaseIsCompiledAgainstAndNeverADependency() {
        val build = module("build.gradle.kts").readText()
        assertTrue(build.contains("compileOnly(\"com.google.firebase:firebase-messaging:"))
        for (kind in listOf("implementation", "api", "runtimeOnly")) {
            assertFalse("$kind would put Firebase in every app", build.contains("$kind(\"com.google.firebase"))
        }
        // An app without Firebase minifies without its classes: R8 is told they may be missing.
        val rules = module("consumer-rules.pro").readText()
        assertTrue(rules.contains("-dontwarn com.google.firebase.messaging.**"))
        assertTrue(rules.contains("-dontwarn com.google.android.gms.tasks.**"))
    }

    @Test fun onlyOneFileTouchesFirebase() {
        val sources = module("src/main/java/ai/graine/inapp").listFiles { f -> f.name.endsWith(".kt") }!!.toList()
        assertTrue(sources.size > 10)
        val touching = sources.filter { f -> f.readLines().any { it.startsWith("import com.google.firebase") || it.startsWith("import com.google.android.gms") } }
        assertEquals(listOf("GraineMessagingService.kt"), touching.map { it.name })
        // Its token source is made in one place only — after the presence check.
        val makers = sources.filter { f -> f.readText().contains("FirebaseTokenSource()") }
        assertEquals(listOf("FirebasePush.kt"), makers.map { it.name })
        // The class the manifest and PushRules name is the one declared there (read, not loaded: its superclass is Firebase's).
        val service = sources.single { it.name == "GraineMessagingService.kt" }.readText()
        assertTrue(service.startsWith("package ai.graine.inapp\n"))
        assertTrue(service.contains("internal class GraineMessagingService : FirebaseMessagingService()"))
        assertEquals("ai.graine.inapp.GraineMessagingService", PushRules.SDK_SERVICE)
    }
}
