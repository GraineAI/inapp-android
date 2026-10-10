package ai.graine.inapp

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * THE CALL'S FOREGROUND SERVICE — a `microphone` foreground service, so the
 * call keeps its microphone and its process with the screen off and with no
 * activity at all, and the ongoing-call notification (with Hang up) is the way
 * back to it. GraineCalls holds the session and the audio; this service is what
 * lets them live. It is started from the call screen — Android forbids
 * starting a microphone service from the background — and stops with the call.
 * Declared in the SDK's manifest; nothing for the app to add.
 */
class GraineCallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val calls = GraineInApp.peek()?.calls
        val notification = calls?.ongoingNotification(this) ?: placeholder()
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(GraineCalls.ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                // Before Android 11 the type is the manifest's (foregroundServiceType="microphone").
                startForeground(GraineCalls.ONGOING_ID, notification)
            }
        } catch (e: Exception) {
            calls?.serviceFailed(e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (calls == null) { stopNow(); return START_NOT_STICKY }
        calls.serviceStarted(this)
        // A call cannot be resumed by a restarted process: never sticky.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        GraineInApp.peek()?.calls?.serviceStopped(this)
        super.onDestroy()
    }

    internal fun stopNow() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Only if the SDK was never initialised in this process: shown for the instant before the service stops. */
    private fun placeholder(): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).let { nm ->
                if (nm.getNotificationChannel(GraineCalls.ONGOING_CHANNEL) == null) {
                    nm.createNotificationChannel(android.app.NotificationChannel(GraineCalls.ONGOING_CHANNEL, "Ongoing calls", NotificationManager.IMPORTANCE_LOW))
                }
            }
            Notification.Builder(this, GraineCalls.ONGOING_CHANNEL)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return b.setSmallIcon(android.R.drawable.sym_call_incoming).setContentTitle("Call").build()
    }
}

/**
 * The ring's Decline and the ongoing call's Hang up. A notification action
 * that only changes state is a broadcast; Answer opens the call screen instead
 * (Android 12 forbids starting an activity from a receiver a notification
 * started).
 */
class GraineCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val calls = GraineInApp.peek()?.calls
        when (intent.action) {
            GraineCalls.ACTION_DECLINE -> {
                if (calls != null) calls.decline(intent.getStringExtra(GraineCalls.EXTRA_CALL_ID))
                // A ring left over from a process that died: nothing is ringing, so stop the sound.
                else (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(GraineCalls.RING_ID)
            }
            GraineCalls.ACTION_HANGUP -> {
                if (calls != null) calls.end()
                else (context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(GraineCalls.ONGOING_ID)
            }
        }
    }
}
