package dev.krishgoel.theftguard.pause

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Backups that end a payment pause even if our process was killed:
 *  - ACTION_RESUME: the alarm set for the end of the pause.
 *  - LOCKED_BOOT_COMPLETED / BOOT_COMPLETED: a pause never survives a restart.
 *
 * Not exported: only the system (boot broadcasts) and our own PendingIntent reach it.
 * directBootAware: runs right after a restart, before the first unlock.
 */
class ResumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RESUME -> PauseController.resume(context, "backup timer")
            Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_BOOT_COMPLETED -> PauseController.onBoot(context)
        }
    }

    companion object {
        const val ACTION_RESUME = "dev.krishgoel.theftguard.PAUSE_END"
    }
}
