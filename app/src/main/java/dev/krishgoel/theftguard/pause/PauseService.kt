package dev.krishgoel.theftguard.pause

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/**
 * Runs only while a payment pause is active (at most 10 minutes).
 *
 *  - Shows the "TheftGuard paused" notification with a "Resume now" button.
 *  - Ends the pause the moment the screen turns off (phone locked), or when
 *    the 10 minutes are up.
 *  - As a foreground service it keeps our process alive, so the wrong-PIN
 *    alarm can still sound while the accessibility service is off.
 *
 * Type "specialUse": none of Android's standard types (media, location…)
 * fits "keep anti-theft alive during a short user-requested pause". Fine
 * for a sideloaded app; Play would ask for a justification.
 */
class PauseService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            PauseController.resume(context, "screen locked")
        }
    }

    override fun onCreate() {
        super.onCreate()
        PauseController.ensureChannel(this)
        startForeground(
            PauseController.NOTIF_PAUSED, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        val left = (PauseController.pausedUntil(this) - System.currentTimeMillis()).coerceAtLeast(0)
        handler.postDelayed({ PauseController.resume(this, "10 minutes up") }, left)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESUME_NOW) PauseController.resume(this, "Resume now tapped")
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val resumeNow = PendingIntent.getService(
            this, 1, Intent(this, PauseService::class.java).setAction(ACTION_RESUME_NOW),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val until = java.time.LocalTime.ofInstant(
            java.time.Instant.ofEpochMilli(PauseController.pausedUntil(this)), java.time.ZoneId.systemDefault(),
        ).withSecond(0).withNano(0)
        return Notification.Builder(this, PauseController.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("TheftGuard paused for payments")
            .setContentText("Power-off gate is off until $until, or until you lock the screen. Alarm and SMS alerts stay on.")
            .setStyle(Notification.BigTextStyle().bigText(
                "Power-off gate is off until $until, or until you lock the screen. Alarm and SMS alerts stay on."))
            .addAction(Notification.Action.Builder(null, "Resume now", resumeNow).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_RESUME_NOW = "dev.krishgoel.theftguard.RESUME_NOW"
    }
}
