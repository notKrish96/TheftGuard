package dev.krishgoel.theftguard.pause

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import dev.krishgoel.theftguard.Storage
import dev.krishgoel.theftguard.gate.EventRecorder
import dev.krishgoel.theftguard.gate.PowerGateService

/**
 * "Pause for payments".
 *
 * Why it exists: banking/UPI apps (Paytm, tested 2026-11-05) refuse to work
 * while ANY third-party accessibility service is on, because accessibility
 * is what banking malware abuses. Even a minimal-access TheftGuard was
 * refused. Hiding from that check would be malware behaviour, so instead
 * you can pause the gate for a payment, honestly and visibly.
 *
 * Rules:
 *  - Starting a pause needs the TheftGuard PIN (callers check it).
 *  - It ends by itself after PAUSE_MS, or the moment the screen locks,
 *    whichever comes first, so you can't forget to re-arm it.
 *  - Backups: an alarm at the end time, and a boot check, so a dead
 *    process or a restart can't leave the phone unprotected.
 *  - The wrong-PIN alarm and the SMS alerts keep working while paused
 *    (they run through device admin, not accessibility).
 *
 * Turning the gate back ON: an app may switch its own accessibility
 * service OFF (disableSelf), but switching it ON needs WRITE_SECURE_SETTINGS.
 * That permission can't be requested in an app; it's granted once over USB:
 *     adb shell pm grant dev.krishgoel.theftguard android.permission.WRITE_SECURE_SETTINGS
 * With it, resuming is automatic. Without it, we post a notification that
 * opens the Accessibility screen so the user flips it back on.
 */
object PauseController {
    const val PAUSE_MS = 10 * 60_000L
    const val CHANNEL = "pause"
    const val NOTIF_PAUSED = 41
    const val NOTIF_TURN_ON = 42
    private const val PREFS = "pause"
    private const val KEY_UNTIL = "until_wall"        // wall clock: survives restarts
    private const val KEY_ACTIVE = "active"

    /** Set just before we switch our own service off, so it doesn't send a tamper SMS. */
    @Volatile var pausingOnPurpose = false

    fun isPaused(ctx: Context): Boolean = Storage.prefs(ctx, PREFS).getBoolean(KEY_ACTIVE, false)

    fun pausedUntil(ctx: Context): Long = Storage.prefs(ctx, PREFS).getLong(KEY_UNTIL, 0)

    fun canAutoResume(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Call only after the TheftGuard PIN was entered. */
    fun pause(ctx: Context) {
        val app = ctx.applicationContext ?: ctx
        val until = System.currentTimeMillis() + PAUSE_MS
        Storage.prefs(app, PREFS).edit().putBoolean(KEY_ACTIVE, true).putLong(KEY_UNTIL, until).commit()
        EventRecorder.line(app, "PAUSE: gate paused for payments until ${java.time.Instant.ofEpochMilli(until)}")

        // 1. Foreground service: shows the "paused" notification, watches for the
        //    screen locking, and keeps our process (and the alarm) alive.
        app.startForegroundService(Intent(app, PauseService::class.java))

        // 2. Backup alarm at the end time, in case the process is killed.
        val am = app.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + PAUSE_MS,
            resumeIntent(app),
        )

        // 3. Switch the accessibility service off (Paytm can now work).
        pausingOnPurpose = true
        PowerGateService.instance?.pauseSelf()
    }

    /** End the pause: re-arm the gate (or ask the user to). Safe to call repeatedly. */
    fun resume(ctx: Context, why: String) {
        val app = ctx.applicationContext ?: ctx
        if (!isPaused(app)) return
        Storage.prefs(app, PREFS).edit().putBoolean(KEY_ACTIVE, false).remove(KEY_UNTIL).commit()
        app.getSystemService(AlarmManager::class.java).cancel(resumeIntent(app))
        app.stopService(Intent(app, PauseService::class.java))
        pausingOnPurpose = false
        EventRecorder.line(app, "PAUSE: resuming ($why)")

        if (!reEnableService(app)) askUserToTurnOn(app)
    }

    /** True if our accessibility service is (now) enabled. */
    private fun reEnableService(app: Context): Boolean {
        val ours = ComponentName(app, PowerGateService::class.java).flattenToString()
        val cr = app.contentResolver
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        if (current.split(':').any { it.equals(ours, ignoreCase = true) }) return true
        if (!canAutoResume(app)) return false
        return try {
            // Add only OUR component; leave everyone else's services untouched.
            val updated = if (current.isBlank()) ours else "$current:$ours"
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            EventRecorder.line(app, "PAUSE: gate switched back on automatically")
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun askUserToTurnOn(app: Context) {
        ensureChannel(app)
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = android.app.Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("TheftGuard is OFF: turn it back on")
            .setContentText("Your payment pause ended. Tap, then switch on \"TheftGuard power gate\".")
            .setContentIntent(open)
            .setOngoing(true)              // stays until the gate is back
            .setCategory(android.app.Notification.CATEGORY_REMINDER)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(NOTIF_TURN_ON, n)
    }

    /** Called when the service connects again: clear the "turn it back on" reminder. */
    fun onServiceBack(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIF_TURN_ON)
    }

    /** After a restart: a pause can't outlive a reboot. */
    fun onBoot(ctx: Context) {
        if (isPaused(ctx)) resume(ctx, "phone restarted")
    }

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Payment pause", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Shows while the power-off gate is paused, and when it needs turning back on"
                }
            )
        }
    }

    private fun resumeIntent(app: Context): PendingIntent =
        PendingIntent.getBroadcast(
            app, 7, Intent(app, ResumeReceiver::class.java).setAction(ResumeReceiver.ACTION_RESUME),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
