package ai.graine.inapp

import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/*
 * PUSHES WITH NO CODE IN THE APP — with Firebase Cloud Messaging in the app,
 * the calls Graine rings need nothing more from it. The SDK's own messaging
 * service (GraineMessagingService, declared in its manifest) receives every new
 * token and Graine's call pushes; and once a process, when the SDK starts, it
 * asks Firebase for this install's current token, since Firebase announces a
 * token only when it changes — an install whose token is older than this SDK
 * would otherwise never hand one over.
 *
 * Firebase stays optional: the SDK ships without it. Every touch of Firebase is
 * in GraineMessagingService.kt, reached from here only after [FirebasePush.present]
 * found its classes, so an app without Firebase builds and runs as before.
 * What the SDK found is the setup check's (CallSetup: `no_push_token`).
 */

/** Where the app's Firebase setup stands, as the SDK found it when it started. */
internal enum class PushSetup {
    /** Firebase Cloud Messaging is not in the app. */
    NO_FIREBASE,
    /** It is, but Firebase is not set up: no google-services.json, or the Google services plugin is not applied. */
    NOT_SET_UP,
    /** The app turned Firebase's automatic token creation off, so the SDK creates no token itself. */
    AUTO_INIT_OFF,
    /** The SDK asked Firebase for this install's token. */
    ASKED,
    /** Firebase could not give one: no Google Play services, no connection… */
    FAILED,
}

/** What the SDK asks of Firebase Cloud Messaging: Firebase's own in an app (GraineMessagingService.kt), a fake in a test. */
internal interface PushTokenSource {
    /** Whether the app lets Firebase create tokens by itself. Throws IllegalStateException when Firebase is not set up. */
    fun autoInitEnabled(): Boolean

    /** This install's token, to [done]: the token, or why there is none. */
    fun token(done: (token: String?, error: Throwable?) -> Unit)
}

/** The decisions, with no Android in them. */
internal object PushRules {
    /** The SDK's messaging service, as the manifest names it. */
    const val SDK_SERVICE = "ai.graine.inapp.GraineMessagingService"
    /** Firebase's built-in one, which takes a message when no app or SDK service does (priority -500). */
    const val FIREBASE_SERVICE = "com.google.firebase.messaging.FirebaseMessagingService"

    /**
     * Whether a token Firebase gave is news to the SDK. One it does not hold
     * yet goes the way a new token does (kept, and registered with Graine at
     * once); the one it already holds was registered when it was due, so
     * nothing is sent again.
     */
    fun isNew(held: String?, given: String?): Boolean = !given.isNullOrBlank() && given != held

    /** Why there is no token, in Firebase's own word when it gave one (SERVICE_NOT_AVAILABLE…), else the failure's kind. */
    fun reason(error: Throwable?): String? {
        if (error == null) return null
        // A few causes deep at most: a chain may loop.
        val text = generateSequence(error) { it.cause }.take(6).mapNotNull { it.message?.trim() }.firstOrNull { it.isNotEmpty() }
            ?: return error.javaClass.simpleName
        // A wrapped failure repeats the classes it went through ("java.io.IOException: SERVICE_NOT_AVAILABLE"): its word is after them.
        var word = text
        while (true) word = CLASS_PREFIX.find(word)?.let { word.substring(it.range.last + 1).trimStart() } ?: break
        return if (word.length <= REASON_MAX) word else word.take(REASON_MAX - 1).trimEnd() + "…"
    }

    private val CLASS_PREFIX = Regex("^(?:[A-Za-z_$][\\w$]*\\.)+[A-Za-z_$][\\w$]*:")
    private const val REASON_MAX = 120

    /**
     * What a debug build is told when another messaging service outranks the
     * SDK's, so that one receives every push and token (its name: the first
     * service the system resolves); null when the SDK's own one receives them.
     */
    fun outranked(first: String?): String? = when (first) {
        null, SDK_SERVICE, FIREBASE_SERVICE -> null
        else -> "The app's own messaging service ($first) receives every Firebase message, so the SDK's does not: " +
            "hand Graine's call pushes to GraineCalls.handlePush(context, message.data) from it " +
            "(React Native and Flutter: handlePush; Kotlin Multiplatform: Graine.calls.handlePush). " +
            "The SDK reads the push token itself when it starts."
    }
}

/** The SDK's side of Firebase: the presence check, the token at start, and what the setup check reads. */
internal object FirebasePush {
    /** The class whose presence says Firebase Cloud Messaging is in the app. */
    const val MESSAGING_CLASS = "com.google.firebase.messaging.FirebaseMessaging"
    /** What Firebase starts a messaging service with. */
    const val MESSAGING_EVENT = "com.google.firebase.MESSAGING_EVENT"

    /** What the SDK found when it started; null before. */
    @Volatile var setup: PushSetup? = null
        private set

    /** Why Firebase gave no token, when it said ([PushSetup.FAILED]). */
    @Volatile var failure: String? = null
        private set

    private val started = AtomicBoolean(false)

    /** Firebase Cloud Messaging is in the app: its class ([className]) can be found. Initialises nothing of it. */
    fun present(loader: ClassLoader? = FirebasePush::class.java.classLoader, className: String = MESSAGING_CLASS): Boolean = try {
        Class.forName(className, false, loader)
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * Once a process, when the SDK starts (GraineInApp.init, however it is
     * reached): with Firebase in the app and set up, ask for this install's
     * token and hand one the SDK does not hold yet to [GraineCalls.onNewToken] —
     * unless the app turned Firebase's automatic token creation off, a choice
     * the SDK keeps. Never throws.
     */
    fun start(context: Context, debug: Boolean) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext ?: context
        try {
            val found = check(
                present = present(),
                source = { FirebaseTokenSource() },
                held = { runCatching { DeviceStore.of(app).pushToken }.getOrNull() },
                handOver = { token -> GraineCalls.onNewToken(app, token) },
            )
            if (found == PushSetup.ASKED && debug) sayWhoReceives(app)
        } catch (t: Throwable) {
            record(PushSetup.FAILED, PushRules.reason(t))
        }
    }

    /**
     * What [start] does, on what it was given: the setup found (also kept for
     * the setup check), and the token, when Firebase gives one, handed over if
     * it is news to the SDK.
     */
    internal fun check(present: Boolean, source: () -> PushTokenSource, held: () -> String?, handOver: (String) -> Unit): PushSetup {
        if (!present) return record(PushSetup.NO_FIREBASE)
        val firebase = try {
            source()
        } catch (t: Throwable) {
            // Part of Firebase is missing (a class it needs, from another library): as good as none.
            return record(PushSetup.NO_FIREBASE)
        }
        val automatic = try {
            firebase.autoInitEnabled()
        } catch (e: IllegalStateException) {
            return record(PushSetup.NOT_SET_UP)
        } catch (t: Throwable) {
            return record(PushSetup.FAILED, PushRules.reason(t))
        }
        if (!automatic) return record(PushSetup.AUTO_INIT_OFF)
        record(PushSetup.ASKED)
        try {
            firebase.token { token, error ->
                if (token.isNullOrBlank()) {
                    record(PushSetup.FAILED, PushRules.reason(error) ?: "no token")
                    return@token
                }
                if (PushRules.isNew(held(), token)) handOver(token)
            }
        } catch (t: Throwable) {
            return record(PushSetup.FAILED, PushRules.reason(t))
        }
        return setup ?: PushSetup.ASKED
    }

    private fun record(found: PushSetup, why: String? = null): PushSetup {
        failure = why
        setup = found
        return found
    }

    /** A debug build hears which messaging service receives the app's Firebase messages, when it is not the SDK's. */
    private fun sayWhoReceives(app: Context) {
        val pm = app.packageManager ?: return
        @Suppress("DEPRECATION")
        val first = pm.queryIntentServices(Intent(MESSAGING_EVENT).setPackage(app.packageName), 0).firstOrNull()?.serviceInfo?.name
        PushRules.outranked(first)?.let { Log.i(GraineInApp.TAG, it) }
    }

    /** Back to before anything started: for the tests. */
    internal fun reset() {
        started.set(false)
        setup = null
        failure = null
    }
}
