package ai.graine.inapp

/*
 * WHAT A CALL NEEDS FROM THE PHONE AND THE APP — the call permissions and the
 * setup self-check, as rules with no Android in them, so a JVM test proves
 * them. CallPermissions.kt and GraineCalls read the facts on a phone (the
 * permissions, the manifest, the push token, the sessions) and ask these
 * rules what they mean; FirebasePush says what the SDK found of Firebase.
 *
 * A problem is never an exception: calls still work wherever they can. The
 * check says what is missing, in plain words with the exact fix, once in the
 * log and whenever the app reads `callSetupProblems`.
 */

/** A permission a WhatsApp-style call needs. [wire] is the word every SDK uses. */
enum class GraineCallPermission(val wire: String) {
    /** The customer's voice. Asked by the SDK when a call starts without it, or by `requestCallPermissions`. */
    MICROPHONE("microphone"),
    /** A call that rings while the app is closed or in the background: POST_NOTIFICATIONS on Android 13+. */
    NOTIFICATIONS("notifications"),
    /** A ring that fills a locked screen: Android 14+ lets the customer allow it in Settings. */
    FULL_SCREEN("fullScreen");

    companion object {
        /** By its word — `microphone`, `notifications`, `fullScreen` (`full_screen` and any case too); null for any other. */
        @JvmStatic
        fun of(word: String?): GraineCallPermission? {
            val folded = word?.trim()?.replace("_", "")?.lowercase() ?: return null
            return entries.firstOrNull { it.wire.lowercase() == folded }
        }
    }
}

/** Where a call permission stands. [wire] is the word every SDK uses. */
enum class GraineCallPermissionStatus(val wire: String) {
    /** Allowed. */
    GRANTED("granted"),
    /** Not allowed yet: `requestCallPermissions` can ask for it. */
    DENIED("denied"),
    /** Refused for good, or only allowed in Settings: only the Settings app can change it (`openCallSettings`). */
    BLOCKED("blocked"),
    /** This phone needs no such permission. */
    NOT_NEEDED("notNeeded");

    /** Granted, or nothing to grant. */
    val isSatisfied: Boolean get() = this == GRANTED || this == NOT_NEEDED
}

/** Every call permission's status, as the phone holds it when read. */
data class GraineCallPermissions(
    val microphone: GraineCallPermissionStatus,
    val notifications: GraineCallPermissionStatus,
    val fullScreen: GraineCallPermissionStatus,
) {
    /** One permission's status. */
    operator fun get(item: GraineCallPermission): GraineCallPermissionStatus = when (item) {
        GraineCallPermission.MICROPHONE -> microphone
        GraineCallPermission.NOTIFICATIONS -> notifications
        GraineCallPermission.FULL_SCREEN -> fullScreen
    }

    /** Every permission is granted or not needed: calls can ring, fill the locked screen and connect. */
    val allGranted: Boolean get() = GraineCallPermission.entries.all { get(it).isSatisfied }

    /** `{microphone, notifications, fullScreen}`, each a status word — for a bridge. */
    internal fun toMap(): Map<String, Any?> =
        GraineCallPermission.entries.associate { it.wire to get(it).wire }
}

/** Receives the call permissions once asking is over. Main thread. */
fun interface GraineCallPermissionsCallback {
    fun onResult(permissions: GraineCallPermissions)
}

/**
 * Something the app's setup still lacks for calls: a stable [code] to act on
 * and a [message] in plain words with the exact fix. Calls still work where
 * they can.
 *
 * Codes: `manifest_entry_missing`, `no_push_token`, `notifications_not_allowed`,
 * `full_screen_not_allowed`, `microphone_blocked`, `hosted_ringing_off`.
 */
data class GraineCallSetupProblem(val code: String, val message: String) {
    /** `{code, message}`, for a bridge. */
    internal fun toMap(): Map<String, Any?> = mapOf("code" to code, "message" to message)
}

/** The rules: what a permission's state means, and what the setup lacks. */
internal object CallPermissionRules {
    /**
     * A runtime permission: granted; else blocked once asking has been
     * refused for good (Android stops showing its dialog); else denied, which
     * asking may still change.
     */
    fun runtime(granted: Boolean, asked: Boolean, refusedForGood: Boolean): GraineCallPermissionStatus = when {
        granted -> GraineCallPermissionStatus.GRANTED
        asked && refusedForGood -> GraineCallPermissionStatus.BLOCKED
        else -> GraineCallPermissionStatus.DENIED
    }

    /**
     * The ring's notifications. Before Android 13 there is nothing to ask:
     * they are on unless the customer turned them off in Settings. From 13,
     * POST_NOTIFICATIONS — and the app's notifications still switched on.
     */
    fun notifications(sdk: Int, permissionGranted: Boolean, enabled: Boolean, asked: Boolean, refusedForGood: Boolean): GraineCallPermissionStatus = when {
        sdk < 33 -> if (enabled) GraineCallPermissionStatus.GRANTED else GraineCallPermissionStatus.BLOCKED
        permissionGranted -> if (enabled) GraineCallPermissionStatus.GRANTED else GraineCallPermissionStatus.BLOCKED
        else -> runtime(false, asked, refusedForGood)
    }

    /**
     * The full-screen ring. Before Android 10 it needs no permission; until
     * 14 it is granted with the app (when the manifest declares it); from 14
     * only Settings can allow it (`canUseFullScreenIntent`).
     */
    fun fullScreen(sdk: Int, declared: Boolean, canUse: Boolean): GraineCallPermissionStatus = when {
        sdk < 29 -> GraineCallPermissionStatus.NOT_NEEDED
        !declared -> GraineCallPermissionStatus.DENIED
        sdk < 34 -> GraineCallPermissionStatus.GRANTED
        canUse -> GraineCallPermissionStatus.GRANTED
        else -> GraineCallPermissionStatus.BLOCKED
    }
}

/** What the self-check reads from the phone and the app. */
internal data class CallSetupFacts(
    /** What calls need that the merged manifest lacks: permissions and components, by name. */
    val missingManifest: List<String> = emptyList(),
    /** A push token has reached the SDK (now, or on an earlier launch). */
    val hasPushToken: Boolean = false,
    /** What the SDK found of Firebase when it started (FirebasePush); null before. */
    val pushSetup: PushSetup? = null,
    /** Firebase's reason for giving no token, when it gave one. */
    val pushFailure: String? = null,
    /** Milliseconds since the calls were configured; null before. */
    val msSinceConfigure: Long? = null,
    val permissions: GraineCallPermissions = GraineCallPermissions(
        GraineCallPermissionStatus.GRANTED, GraineCallPermissionStatus.GRANTED, GraineCallPermissionStatus.GRANTED,
    ),
    /** Whether Graine rings this phone for any configured agent; null while no session is known. */
    val hostedRinging: Boolean? = null,
)

internal object CallSetup {
    /** How long after `configure` a push token that never arrived is a problem. */
    const val SETTLE_MS = 15_000L

    /** What calls need from the merged manifest: the library's own entries. */
    val REQUIRED_PERMISSIONS = listOf(
        "android.permission.INTERNET",
        "android.permission.RECORD_AUDIO",
        "android.permission.MODIFY_AUDIO_SETTINGS",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.USE_FULL_SCREEN_INTENT",
        "android.permission.FOREGROUND_SERVICE",
        "android.permission.FOREGROUND_SERVICE_MICROPHONE",
    )

    /** Every problem the facts show: the build's first, then what this launch has or has not done. */
    fun problems(f: CallSetupFacts): List<GraineCallSetupProblem> {
        val out = ArrayList<GraineCallSetupProblem>()
        if (f.missingManifest.isNotEmpty()) {
            out += GraineCallSetupProblem(
                "manifest_entry_missing",
                "The app's merged manifest lacks what calls need: ${f.missingManifest.joinToString(", ")}. " +
                    "The SDK's manifest brings every one; look for tools:node=\"remove\" in your manifest, or a build step that drops them.",
            )
        }
        if (!f.hasPushToken && (f.msSinceConfigure ?: 0L) >= SETTLE_MS) {
            out += GraineCallSetupProblem("no_push_token", noPushToken(f.pushSetup, f.pushFailure))
        }
        when (f.permissions.notifications) {
            GraineCallPermissionStatus.DENIED -> out += GraineCallSetupProblem(
                "notifications_not_allowed",
                "Notifications are not allowed yet, so a call can't ring while the app is closed or in the background. " +
                    "Ask at a moment you choose, such as onboarding: requestCallPermissions().",
            )
            GraineCallPermissionStatus.BLOCKED -> out += GraineCallSetupProblem(
                "notifications_not_allowed",
                "Notifications are turned off for this app, so a call can't ring while the app is closed or in the background. " +
                    "Only Settings can turn them back on: openCallSettings(notifications) opens the page.",
            )
            else -> Unit
        }
        if (!f.permissions.fullScreen.isSatisfied) {
            out += GraineCallSetupProblem(
                "full_screen_not_allowed",
                "This app may not show a full-screen ring, so on a locked phone a call rings as a heads-up notification instead. " +
                    "Offer the setting: openCallSettings(fullScreen).",
            )
        }
        if (f.permissions.microphone == GraineCallPermissionStatus.BLOCKED) {
            out += GraineCallSetupProblem(
                "microphone_blocked",
                "The microphone is turned off for this app, so calls can't connect. " +
                    "Ask the customer to turn it on: openCallSettings(microphone) opens the page.",
            )
        }
        if (f.hostedRinging == false) {
            out += GraineCallSetupProblem(
                "hosted_ringing_off",
                "Graine can't ring this phone: no push credentials for Android are uploaded for this agent. " +
                    "Upload them in the dashboard (Workspace settings → In-app calling), unless your own server sends the call pushes.",
            )
        }
        return out
    }

    /**
     * Why no push token has come, with the fix. With Firebase set up in the
     * app the SDK registers for pushes by itself (FirebasePush), so the fix is
     * whatever the SDK found missing when it started: Firebase itself, its
     * setup (google-services.json and the Google services plugin), the
     * app's choice to create no tokens, or a phone Firebase could not reach.
     */
    private fun noPushToken(setup: PushSetup?, failure: String?): String {
        val lead = "No push token has reached the SDK ${SETTLE_MS / 1000} seconds after the calls were configured, so calls can't ring this phone"
        val ownService = "An app whose own FirebaseMessagingService receives the messages hands each new token over from its onNewToken: " +
            "GraineCalls.onNewToken(context, token) (React Native and Flutter: registerPushToken; Kotlin Multiplatform: Graine.calls.onNewToken)."
        return when (setup) {
            PushSetup.NO_FIREBASE ->
                "$lead: Firebase Cloud Messaging is not in this app. Add Firebase to the app — google-services.json in the app module, " +
                    "the Google services Gradle plugin and the firebase-messaging library (Expo: android.googleServicesFile, and the " +
                    "@graineai/inapp-react-native-calls plugin) — and the SDK registers for pushes by itself."
            PushSetup.NOT_SET_UP ->
                "$lead: Firebase Cloud Messaging is in this app, but Firebase is not set up. Add your google-services.json to the app module " +
                    "and apply the Google services Gradle plugin; the SDK then registers for pushes by itself."
            PushSetup.AUTO_INIT_OFF ->
                "$lead: this app turned Firebase's automatic token creation off, so the SDK creates no token itself. Turn it back on once " +
                    "the app may receive pushes (FirebaseMessaging.getInstance().isAutoInitEnabled = true) and the token reaches the SDK by itself, " +
                    "or hand one over: GraineCalls.onNewToken(context, token)."
            PushSetup.FAILED ->
                "$lead: Firebase could not give this install a token${failure?.let { " ($it)" }.orEmpty()}. The phone needs Google Play services " +
                    "and a connection; the SDK asks again when the app next starts. $ownService"
            PushSetup.ASKED, null ->
                "$lead. With Firebase set up in the app (google-services.json and the Google services Gradle plugin) the SDK registers for " +
                    "pushes by itself. $ownService"
        }
    }

    /** Whether Graine rings this phone, from every known session's rules; null when none is known. */
    fun hostedRinging(rules: List<GraineSchedulingRules>): Boolean? = if (rules.isEmpty()) null else rules.any { it.ringsAndroid }
}

/** The log says each problem once a launch. */
internal class CallSetupLog {
    private val said = HashSet<String>()

    /** The problems not yet said; they count as said from now. */
    @Synchronized
    fun fresh(problems: List<GraineCallSetupProblem>): List<GraineCallSetupProblem> =
        problems.filter { said.add(it.code) }
}
