package dev.krishgoel.theftguard.gate

import android.content.Context
import android.os.SystemClock
import dev.krishgoel.theftguard.Storage

/**
 * Remembers "someone opened the power menu on the LOCKED phone".
 *
 * Why this exists: the first force-restart detector watched for Volume Down
 * being held. Testing on the S23 FE (2026-11-04) showed that when Side +
 * Volume down are held TOGETHER, Samsung keeps the Volume Down key for its
 * own button-combo handling and never passes it on. So we use the one signal
 * we reliably get, the power menu popping up, in two ways:
 *
 *  1. BEFORE: the moment the menu appears on a locked phone, text the
 *     contacts (~6 s before a force restart could finish).
 *  2. AFTER: the attempt time is saved to disk. If the phone then boots within
 *     RESTART_WINDOW_MS, with no correct PIN entered in between, it was
 *     force-restarted. AlarmController takes it from there (siren + SMS).
 *
 * A correct PIN (TheftGuard's or the lock screen's) clears the record, so
 * you powering off your own phone never counts as theft.
 *
 * Times are wall-clock (System.currentTimeMillis), because elapsedRealtime
 * restarts from 0 at every boot and can't be compared across a restart.
 */
object PowerAttempt {
    private const val PREFS = "power_attempt"
    private const val KEY_AT = "at"
    private const val KEY_ALERTED = "alerted"
    private const val RESTART_WINDOW_MS = 2 * 60_000L

    fun record(ctx: Context, alerted: Boolean) {
        Storage.prefs(ctx, PREFS).edit()
            .putLong(KEY_AT, System.currentTimeMillis())
            .putBoolean(KEY_ALERTED, alerted)
            .commit()               // on disk NOW: a power cut may follow within seconds
    }

    /** Was an attempt alert sent that hasn't been cleared by a correct PIN? */
    fun wasAlerted(ctx: Context): Boolean = Storage.prefs(ctx, PREFS).getBoolean(KEY_ALERTED, false)

    fun clear(ctx: Context) {
        Storage.prefs(ctx, PREFS).edit().clear().commit()
    }

    /**
     * Call once when the service starts. True if this boot happened shortly
     * after an uncleared power-off attempt, i.e. the phone was force-restarted.
     * The record is cleared either way, so this fires once per restart.
     */
    fun consumeForcedRestart(ctx: Context): Boolean {
        val at = Storage.prefs(ctx, PREFS).getLong(KEY_AT, 0)
        if (at == 0L) return false
        // When did this boot start? "now" minus "time since boot".
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        // Not yet rebooted since the attempt (service just reconnected): keep it.
        if (at > bootAt) return false
        clear(ctx)
        return bootAt - at <= RESTART_WINDOW_MS
    }
}
