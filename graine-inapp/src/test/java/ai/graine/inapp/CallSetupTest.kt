package ai.graine.inapp

import ai.graine.inapp.GraineCallPermissionStatus.BLOCKED
import ai.graine.inapp.GraineCallPermissionStatus.DENIED
import ai.graine.inapp.GraineCallPermissionStatus.GRANTED
import ai.graine.inapp.GraineCallPermissionStatus.NOT_NEEDED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/*
 * What a call needs from the phone and the app, proven on the JVM: what each
 * permission's state means on each Android version, the setup self-check's
 * problems and fixes, that each one is logged once, the words a bridge reads,
 * and that the library's manifest declares the screen that asks.
 */
class CallSetupTest {
    private val allGranted = GraineCallPermissions(GRANTED, GRANTED, GRANTED)

    /** An app set up for calls as the README says: nothing to report. */
    private fun ready() = CallSetupFacts(
        missingManifest = emptyList(),
        hasPushToken = true,
        msSinceConfigure = 60_000L,
        permissions = allGranted,
        hostedRinging = true,
    )

    private fun codes(f: CallSetupFacts) = CallSetup.problems(f).map { it.code }

    // ── permissions ─────────────────────────────────────────────────────────

    @Test fun aRuntimePermissionIsBlockedOnlyOnceAskingWasRefusedForGood() {
        assertEquals(GRANTED, CallPermissionRules.runtime(granted = true, asked = true, refusedForGood = true))
        assertEquals("never asked: the dialog can still show", DENIED, CallPermissionRules.runtime(false, asked = false, refusedForGood = false))
        assertEquals("refused once: Android shows the dialog again", DENIED, CallPermissionRules.runtime(false, asked = true, refusedForGood = false))
        assertEquals(BLOCKED, CallPermissionRules.runtime(false, asked = true, refusedForGood = true))
        assertEquals("a flag from no ask of ours claims nothing", DENIED, CallPermissionRules.runtime(false, asked = false, refusedForGood = true))
    }

    @Test fun notificationsBeforeThirteenAreOnUnlessSettingsTurnedThemOff() {
        assertEquals(GRANTED, CallPermissionRules.notifications(32, permissionGranted = false, enabled = true, asked = false, refusedForGood = false))
        assertEquals(BLOCKED, CallPermissionRules.notifications(26, permissionGranted = false, enabled = false, asked = false, refusedForGood = false))
    }

    @Test fun notificationsFromThirteenNeedThePermissionAndTheSwitch() {
        assertEquals(GRANTED, CallPermissionRules.notifications(33, permissionGranted = true, enabled = true, asked = true, refusedForGood = false))
        assertEquals("granted, then switched off in Settings", BLOCKED, CallPermissionRules.notifications(34, true, enabled = false, asked = true, refusedForGood = false))
        assertEquals(DENIED, CallPermissionRules.notifications(33, false, enabled = false, asked = false, refusedForGood = false))
        assertEquals(BLOCKED, CallPermissionRules.notifications(35, false, enabled = false, asked = true, refusedForGood = true))
    }

    @Test fun theFullScreenRingIsSettingsAloneFromFourteen() {
        assertEquals("Android 9 and before: no such permission", NOT_NEEDED, CallPermissionRules.fullScreen(28, declared = false, canUse = false))
        assertEquals("10 to 13: granted with the app", GRANTED, CallPermissionRules.fullScreen(33, declared = true, canUse = false))
        assertEquals("removed from the manifest", DENIED, CallPermissionRules.fullScreen(31, declared = false, canUse = true))
        assertEquals(GRANTED, CallPermissionRules.fullScreen(34, declared = true, canUse = true))
        assertEquals("only Settings can allow it", BLOCKED, CallPermissionRules.fullScreen(35, declared = true, canUse = false))
    }

    @Test fun allGrantedCountsNotNeededAsGranted() {
        assertTrue(GraineCallPermissions(GRANTED, GRANTED, NOT_NEEDED).allGranted)
        assertFalse(GraineCallPermissions(GRANTED, GRANTED, BLOCKED).allGranted)
        assertFalse(GraineCallPermissions(DENIED, GRANTED, GRANTED).allGranted)
        val p = GraineCallPermissions(BLOCKED, DENIED, GRANTED)
        assertEquals(BLOCKED, p[GraineCallPermission.MICROPHONE])
        assertEquals(DENIED, p[GraineCallPermission.NOTIFICATIONS])
        assertEquals(GRANTED, p[GraineCallPermission.FULL_SCREEN])
    }

    @Test fun aBridgeReadsTheSameWordsAsOnIos() {
        assertEquals(
            mapOf("microphone" to "granted", "notifications" to "denied", "fullScreen" to "notNeeded"),
            GraineCallPermissions(GRANTED, DENIED, NOT_NEEDED).toMap(),
        )
        assertEquals(mapOf("code" to "x", "message" to "y"), GraineCallSetupProblem("x", "y").toMap())
        assertEquals(GraineCallPermission.FULL_SCREEN, CallsHostValues.permission("fullScreen"))
        assertEquals(GraineCallPermission.FULL_SCREEN, CallsHostValues.permission("full_screen"))
        assertEquals(GraineCallPermission.NOTIFICATIONS, CallsHostValues.permission(" Notifications "))
        assertEquals(GraineCallPermission.MICROPHONE, GraineCallPermission.of("MICROPHONE"))
        assertNull(CallsHostValues.permission("camera"))
        assertNull(CallsHostValues.permission(7))
        assertNull(CallsHostValues.permission(null))
    }

    // ── the self-check ──────────────────────────────────────────────────────

    @Test fun anAppSetUpAsTheReadmeSaysHasNothingToFix() {
        assertTrue(CallSetup.problems(ready()).isEmpty())
    }

    @Test fun aManifestEntryTheMergeDroppedIsNamed() {
        val p = CallSetup.problems(ready().copy(missingManifest = listOf("android.permission.USE_FULL_SCREEN_INTENT", "GraineCallService")))
        assertEquals(listOf("manifest_entry_missing"), p.map { it.code })
        assertTrue(p[0].message.contains("android.permission.USE_FULL_SCREEN_INTENT, GraineCallService"))
        assertTrue(p[0].message.contains("tools:node=\"remove\""))
    }

    @Test fun aPushTokenHasItsSecondsBeforeItIsAProblem() {
        val noToken = ready().copy(hasPushToken = false)
        assertTrue(codes(noToken.copy(msSinceConfigure = 2_000L)).isEmpty())
        assertTrue("nothing is late before configure", codes(noToken.copy(msSinceConfigure = null)).isEmpty())
        assertEquals(listOf("no_push_token"), codes(noToken.copy(msSinceConfigure = CallSetup.SETTLE_MS)))
        val message = CallSetup.problems(noToken).single().message
        assertTrue(message.contains("GraineCalls.onNewToken(context, token)"))
        assertTrue(message.contains("registerPushToken"))
    }

    private fun noTokenMessage(setup: PushSetup?, failure: String? = null): String =
        CallSetup.problems(ready().copy(hasPushToken = false, pushSetup = setup, pushFailure = failure)).single { it.code == "no_push_token" }.message

    @Test fun withoutFirebaseTheFixIsAddingFirebaseNotWritingAService() {
        val m = noTokenMessage(PushSetup.NO_FIREBASE)
        assertTrue(m.contains("Firebase Cloud Messaging is not in this app"))
        for (step in listOf("google-services.json", "Google services Gradle plugin", "firebase-messaging", "android.googleServicesFile")) assertTrue(step, m.contains(step))
        assertTrue(m.contains("the SDK registers for pushes by itself"))
        assertFalse("no messaging service to write", m.contains("FirebaseMessagingService"))
    }

    @Test fun firebaseNotSetUpNamesTheSetupLeft() {
        val m = noTokenMessage(PushSetup.NOT_SET_UP)
        assertTrue(m.contains("Firebase is not set up"))
        assertTrue(m.contains("google-services.json"))
        assertTrue(m.contains("Google services Gradle plugin"))
        assertTrue(m.contains("registers for pushes by itself"))
        assertFalse(m.contains("FirebaseMessagingService"))
    }

    @Test fun tokenCreationTurnedOffIsTheAppsChoiceToUndo() {
        val m = noTokenMessage(PushSetup.AUTO_INIT_OFF)
        assertTrue(m.contains("automatic token creation off"))
        assertTrue(m.contains("isAutoInitEnabled = true"))
        assertTrue(m.contains("GraineCalls.onNewToken(context, token)"))
    }

    @Test fun firebaseThatGaveNoTokenSaysWhyInItsOwnWord() {
        val m = noTokenMessage(PushSetup.FAILED, "SERVICE_NOT_AVAILABLE")
        assertTrue(m.contains("could not give this install a token (SERVICE_NOT_AVAILABLE)"))
        assertTrue(m.contains("Google Play services"))
        assertTrue(m.contains("registerPushToken"))
        assertFalse("no empty brackets without a reason", noTokenMessage(PushSetup.FAILED).contains("()"))
        assertTrue(noTokenMessage(PushSetup.FAILED).contains("could not give this install a token. "))
    }

    @Test fun askedOrUnknownSaysTheSdkRegistersItselfAndNamesTheHelpers() {
        for (setup in listOf(PushSetup.ASKED, null)) {
            val m = noTokenMessage(setup)
            assertTrue(m.contains("the SDK registers for pushes by itself"))
            assertTrue("for an app with its own service", m.contains("An app whose own FirebaseMessagingService receives the messages"))
            for (helper in listOf("GraineCalls.onNewToken(context, token)", "registerPushToken", "Graine.calls.onNewToken")) assertTrue(helper, m.contains(helper))
        }
    }

    @Test fun noMessageAsksForAServiceAtLaunch() {
        for (setup in PushSetup.entries.toList() + listOf<PushSetup?>(null)) {
            val m = noTokenMessage(setup, "SERVICE_NOT_AVAILABLE")
            assertFalse("$setup: the old fix", m.contains("Hand FCM's token over at launch"))
            assertFalse("$setup: the old fix", m.contains("in your FirebaseMessagingService.onNewToken"))
            assertTrue(m.startsWith("No push token has reached the SDK 15 seconds after the calls were configured, so calls can't ring this phone"))
        }
    }

    @Test fun notificationsNotYetAskedAndTurnedOffSayDifferentFixes() {
        val asked = CallSetup.problems(ready().copy(permissions = allGranted.copy(notifications = DENIED))).single()
        assertEquals("notifications_not_allowed", asked.code)
        assertTrue(asked.message.contains("requestCallPermissions()"))
        val off = CallSetup.problems(ready().copy(permissions = allGranted.copy(notifications = BLOCKED))).single()
        assertEquals("notifications_not_allowed", off.code)
        assertTrue(off.message.contains("openCallSettings(notifications)"))
    }

    @Test fun aRingThatCannotFillTheLockedScreenIsSaid() {
        assertEquals(listOf("full_screen_not_allowed"), codes(ready().copy(permissions = allGranted.copy(fullScreen = BLOCKED))))
        assertTrue(codes(ready().copy(permissions = allGranted.copy(fullScreen = NOT_NEEDED))).isEmpty())
    }

    @Test fun aMicrophoneNotAskedYetIsNoProblemButABlockedOneIs() {
        assertTrue("the SDK asks when a call starts", codes(ready().copy(permissions = allGranted.copy(microphone = DENIED))).isEmpty())
        assertEquals(listOf("microphone_blocked"), codes(ready().copy(permissions = allGranted.copy(microphone = BLOCKED))))
    }

    @Test fun hostedRingingIsJudgedOnlyOnceASessionIsKnown() {
        assertTrue(codes(ready().copy(hostedRinging = null)).isEmpty())
        assertEquals(listOf("hosted_ringing_off"), codes(ready().copy(hostedRinging = false)))
        assertNull(CallSetup.hostedRinging(emptyList()))
        val ios = GraineSchedulingRules(hostedRinging = true, platforms = listOf("ios"))
        val android = GraineSchedulingRules(hostedRinging = true, platforms = listOf("android"))
        assertEquals(false, CallSetup.hostedRinging(listOf(ios, GraineSchedulingRules())))
        assertEquals("any configured agent ringing this phone is enough", true, CallSetup.hostedRinging(listOf(ios, android)))
    }

    @Test fun everyProblemSaysItsFixInClientWords() {
        val all = CallSetup.problems(CallSetupFacts(
            missingManifest = listOf("GraineCallReceiver"),
            hasPushToken = false,
            msSinceConfigure = 60_000L,
            permissions = GraineCallPermissions(BLOCKED, BLOCKED, BLOCKED),
            hostedRinging = false,
        ))
        assertEquals(
            listOf("manifest_entry_missing", "no_push_token", "notifications_not_allowed", "full_screen_not_allowed", "microphone_blocked", "hosted_ringing_off"),
            all.map { it.code },
        )
        // Every way of saying no_push_token too, whatever the SDK found of Firebase.
        val noToken = (PushSetup.entries.toList() + listOf<PushSetup?>(null)).map { setup ->
            CallSetup.problems(CallSetupFacts(hasPushToken = false, msSinceConfigure = 60_000L, pushSetup = setup, pushFailure = "SERVICE_NOT_AVAILABLE")).single()
        }
        for (p in all + noToken) for (word in listOf("Apollo", "Hermes", "gateway", "Jambonz", "LiveKit", "mem0")) {
            assertFalse("${p.code} names $word", p.message.contains(word, ignoreCase = true))
        }
    }

    @Test fun eachProblemIsLoggedOnceALaunch() {
        val log = CallSetupLog()
        val a = GraineCallSetupProblem("no_push_token", "…")
        val b = GraineCallSetupProblem("microphone_blocked", "…")
        assertEquals(listOf(a), log.fresh(listOf(a)))
        assertEquals(listOf(b), log.fresh(listOf(a, b)))
        assertTrue(log.fresh(listOf(a, b)).isEmpty())
    }

    // ── the manifest ────────────────────────────────────────────────────────

    @Test fun theLibraryManifestDeclaresEverythingTheCheckLooksFor() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("graine-inapp/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        for (p in CallSetup.REQUIRED_PERMISSIONS) assertTrue(p, manifest.contains("android:name=\"$p\""))
        for (c in listOf("GraineCallActivity", "GraineCallPermissionActivity", "GraineCallService", "GraineCallReceiver", "GraineMessagingService")) {
            assertTrue(c, manifest.contains("android:name=\"ai.graine.inapp.$c\""))
        }
        // The screen that asks is the SDK's alone, never in Recents, and transparent.
        val asker = manifest.substringAfter("ai.graine.inapp.GraineCallPermissionActivity").substringBefore("/>")
        assertTrue(asker.contains("android:exported=\"false\""))
        assertTrue(asker.contains("android:excludeFromRecents=\"true\""))
        assertTrue(asker.contains("Theme.Translucent"))
    }
}
