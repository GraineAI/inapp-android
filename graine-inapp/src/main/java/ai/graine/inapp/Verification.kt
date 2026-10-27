package ai.graine.inapp

/*
 * YOUR APP AND YOUR USERS — what Graine has proven about this app and about
 * the signed-in user, for one agent (new in 0.14.4). Two features, each set
 * per agent in the dashboard and each off until someone turns it on:
 *
 *   · "Your apps": which apps may use the agent's publishable key. The SDK
 *     says which app it is on every request (the package name and the
 *     signing certificates' SHA-256), and, when the agent asks for a verified
 *     app, proves it with Play Integrity. Graine answers with an app pass the
 *     SDK keeps for 24 hours.
 *   · "Verified users": the identity identify() names is proven by an
 *     identity token — an HMAC your server computes, or the ID token (JWT) of
 *     your own sign-in. Graine answers with a user pass the SDK keeps for an
 *     hour.
 *
 * The types here are what an app reads and passes; the work is in
 * AppProofs.kt (the app), UserProof.kt (the user) and EmbedRequest.kt (every
 * request carries both).
 */

/** Whether Graine knows this app is yours, for one agent: [GraineVerification.app]. */
enum class GraineAppVerdict(
    /** The word every Graine SDK uses for it: `off`, `checking`, `verified`, `testBuild`, `debugToken`, `unverified`. */
    val word: String,
) {
    /** The agent does not ask for a verified app — or its settings have not arrived yet. */
    OFF("off"),

    /** The SDK is proving the app right now, and holds no app pass yet. */
    CHECKING("checking"),

    /** Proven: Play Integrity recognised this build, and Graine gave it an app pass. */
    VERIFIED("verified"),

    /** A development build the agent accepts while its "Allow development builds" is on. */
    TEST_BUILD("testBuild"),

    /** A debug build, accepted through a debug token registered on the agent ([GraineInApp.debugToken]). */
    DEBUG_TOKEN("debugToken"),

    /** Not proven: [GraineVerification.appReason] says why when Graine said. */
    UNVERIFIED("unverified");

    internal companion object {
        /** The verdict for one of the server's words — `verified`, `test_build`, `debug_token`, `unverified`, `off` — or null. */
        fun fromWire(word: Any?): GraineAppVerdict? = when ((word as? String)?.trim()?.lowercase()) {
            "verified" -> VERIFIED
            "test_build" -> TEST_BUILD
            "debug_token" -> DEBUG_TOKEN
            "unverified" -> UNVERIFIED
            "off" -> OFF
            else -> null
        }
    }
}

/** Whether Graine knows the signed-in user is who identify() says, for one agent: [GraineVerification.user]. */
enum class GraineUserVerdict(
    /** The word every Graine SDK uses for it: `off`, `verified`, `testUser`, `unverified`, `invalid`. */
    val word: String,
) {
    /** The agent does not check its users — or its settings have not arrived yet. */
    OFF("off"),

    /** Proven by the identity token, or by the user pass Graine gave for it. */
    VERIFIED("verified"),

    /** A test user the agent lists, from an install whose debug token is registered on it. Counts as verified. */
    TEST_USER("testUser"),

    /** No identity token was sent, or nobody is identified: [GraineVerification.userReason] says why when Graine said. */
    UNVERIFIED("unverified"),

    /** A token was sent and refused: [GraineVerification.userReason] says why (`expired`, `signature`, `mismatch`…). */
    INVALID("invalid");

    internal companion object {
        /** The verdict for one of the server's words — `verified`, `test_user`, `unverified`, `invalid`, `off` — or null. */
        fun fromWire(word: Any?): GraineUserVerdict? = when ((word as? String)?.trim()?.lowercase()) {
            "verified" -> VERIFIED
            "test_user" -> TEST_USER
            "unverified" -> UNVERIFIED
            "invalid" -> INVALID
            "off" -> OFF
            else -> null
        }
    }
}

/**
 * WHAT GRAINE HAS PROVEN, for one agent: the app ([app]) and the signed-in
 * user ([user]), each with Graine's reason when it is not proven. Both are
 * [GraineAppVerdict.OFF] / [GraineUserVerdict.OFF] until the agent's settings
 * say otherwise. Read it from [GraineInApp.verification]; hear it change with
 * [GraineInApp.addVerificationListener].
 */
data class GraineVerification @JvmOverloads constructor(
    val app: GraineAppVerdict = GraineAppVerdict.OFF,
    /**
     * Why [app] is [GraineAppVerdict.UNVERIFIED], in Graine's word, when it
     * said: `missing`, `expired`, `invalid`, `wrong_app`, `test_build`,
     * `not_configured`, `debug_token_unknown`, `debug_token_expired`… Null
     * otherwise.
     */
    val appReason: String? = null,
    val user: GraineUserVerdict = GraineUserVerdict.OFF,
    /**
     * Why [user] is [GraineUserVerdict.UNVERIFIED] or
     * [GraineUserVerdict.INVALID], in Graine's word, when it said: `missing`,
     * `expired`, `not_yet_valid`, `signature`, `audience`, `issuer`, `claim`,
     * `mismatch`, `malformed`, `unknown_key`, `unavailable`. Null otherwise.
     */
    val userReason: String? = null,
)

/** Hears [GraineInApp.verification] change, on the main thread. */
fun interface GraineVerificationListener {
    fun onVerification(verification: GraineVerification)
}

/** Hears the verdict [GraineInApp.prepareAppVerification] came to, on the main thread. */
fun interface GraineAppVerdictCallback {
    fun onVerdict(verdict: GraineAppVerdict)
}

/**
 * DEVELOPMENT MODE: when this build uses a debug token instead of proving
 * itself with Play Integrity ([GraineInApp.debugTokenMode]).
 */
enum class GraineDebugTokenMode(
    /** The word every Graine SDK uses for it: `automatic`, `always`, `never`. */
    val word: String,
) {
    /**
     * The default: a debuggable build (`android:debuggable`, every build
     * Android Studio runs) uses a debug token and never asks Play Integrity;
     * a release build never makes, logs or sends one.
     */
    AUTOMATIC("automatic"),

    /** This build uses a debug token whatever its kind — an internal QA build signed for release, say. */
    ALWAYS("always"),

    /** No debug token, even in a debug build: it proves itself with Play Integrity like a release build. */
    NEVER("never"),
}

/** Answers [GraineIdentityTokenRefresher.refresh]: the fresh token, or null to keep the one the SDK has. Once, from any thread. */
fun interface GraineIdentityTokenCallback {
    fun onToken(token: String?)
}

/**
 * HOW THE SDK ASKS YOUR APP FOR A FRESH IDENTITY TOKEN
 * ([GraineInApp.identityTokenRefresher]): fetch one for the signed-in user
 * from your server (or your sign-in's SDK), then call [done] with it — or
 * with null to keep the current one. Called on the main thread, at most once
 * every 30 seconds and never twice at once.
 */
fun interface GraineIdentityTokenRefresher {
    fun refresh(done: GraineIdentityTokenCallback)
}
