package dev.krishgoel.theftguard.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import dev.krishgoel.theftguard.alarm.AlarmController
import dev.krishgoel.theftguard.alert.AlertSender
import dev.krishgoel.theftguard.gate.PowerAttempt
import dev.krishgoel.theftguard.gate.EventRecorder

/**
 * Device admin: Android tells us about every lock-screen attempt.
 *
 * You switch this on once (TheftGuard → "Activate device admin"). Android
 * then sends this receiver a broadcast on each wrong and right PIN/password
 * at the lock screen. These are system-only broadcasts: the manifest
 * protects the receiver with BIND_DEVICE_ADMIN, so no other app can fake one.
 *
 * Notes:
 *  - Only PIN/pattern/password attempts count. A failed fingerprint or face
 *    unlock isn't a "password" attempt.
 *  - The counter is Android's own (getCurrentFailedPasswordAttempts): wrong
 *    tries in a row since the last correct one. It survives restarts.
 */
class TheftGuardAdminReceiver : DeviceAdminReceiver() {

    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val failed = getManager(context).currentFailedPasswordAttempts
        EventRecorder.line(context, "ADMIN: wrong lock-screen attempt #$failed")
        if (failed >= ALARM_AFTER) {
            AlarmController.start(context, "$failed wrong lock-screen PINs")
        }
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        // The right lock-screen PIN/password was typed, so it's in the right hands.
        // (Only fires for PIN/pattern/password, never fingerprint or face.)
        if (PowerAttempt.wasAlerted(context)) AlertSender.ownerConfirmed(context)
        PowerAttempt.clear(context)
        if (AlarmController.isActive(context)) {
            EventRecorder.line(context, "ADMIN: correct unlock, stopping alarm")
            AlarmController.stop(context)
        }
    }

    /** Shown by Android on the "Deactivate device admin?" screen. */
    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "Turning this off disables TheftGuard's wrong-PIN alarm, and TheftGuard will text your emergency contacts."

    /** Device admin was switched off: tell the contacts (only possible on an unlocked phone). */
    override fun onDisabled(context: Context, intent: Intent) {
        EventRecorder.line(context, "TAMPER: device admin switched off")
        AlertSender.alert(context, "alarm admin switched off")
    }

    companion object {
        /** Spec: 3 wrong attempts. */
        const val ALARM_AFTER = 3

        fun component(ctx: Context) = ComponentName(ctx, TheftGuardAdminReceiver::class.java)
    }
}
