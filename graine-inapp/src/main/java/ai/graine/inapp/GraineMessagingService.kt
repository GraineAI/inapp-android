package ai.graine.inapp

import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/*
 * EVERYTHING OF FIREBASE THE SDK TOUCHES, IN ONE FILE. The SDK is compiled
 * against Firebase Cloud Messaging and ships without it (a compile-only
 * dependency): an app that rings adds Firebase itself, and an app that does
 * not never loads anything here. Android starts the service below only for a
 * Firebase message, which never comes without Firebase; FirebasePush builds
 * the token source only once it has found Firebase's classes. No other file
 * names a Firebase class (a test holds every file to that).
 */

/**
 * The SDK's own Firebase messaging service: with Firebase Cloud Messaging in
 * the app, the calls Graine rings need no messaging service of the app's own.
 * A new token goes to [GraineCalls.onNewToken], a message to
 * [GraineCalls.handlePush], which rings Graine's calls and leaves every other
 * message alone.
 *
 * Declared in the SDK's manifest at priority -1, below an app's own service
 * (0 unless it says otherwise) and above Firebase's built-in one (-500). An app
 * with a FirebaseMessagingService of its own therefore keeps receiving every
 * message and token, and hands Graine's over from it as before:
 * `GraineCalls.onNewToken(this, token)` and
 * `if (GraineCalls.handlePush(this, message.data)) return`.
 * To leave this service out entirely, remove it in the app's manifest with
 * `tools:node="remove"`.
 */
internal class GraineMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        try {
            GraineCalls.onNewToken(this, token)
        } catch (t: Throwable) {
            Log.e(GraineInApp.TAG, "A new push token could not be kept.", t)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        try {
            // True for Graine's call pushes, which ring; any other message is not the SDK's, and nothing is done with it.
            GraineCalls.handlePush(this, message.data)
        } catch (t: Throwable) {
            Log.e(GraineInApp.TAG, "A push could not be handled.", t)
        }
    }
}

/** Firebase's answers, for FirebasePush. Made only once Firebase's classes were found. */
internal class FirebaseTokenSource : PushTokenSource {
    override fun autoInitEnabled(): Boolean = FirebaseMessaging.getInstance().isAutoInitEnabled

    override fun token(done: (token: String?, error: Throwable?) -> Unit) {
        // Completes on the main thread.
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) done(task.result, null) else done(null, task.exception)
        }
    }
}
