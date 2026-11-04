package dev.krishgoel.theftguard

import android.content.Context

/**
 * Quiet test mode (debug switch on the main screen), for testing at night:
 *  - no siren, vibration only
 *  - live location every 4 s instead of 3 min, sent even if the phone hasn't
 *    moved, max 3 updates
 *  - every SMS starts with "[TEST]"
 *
 * Everything else (triggers, PINs, force-restart detector) behaves exactly
 * like the real thing, so a test in this mode still proves the logic works.
 * Remember to switch it OFF afterwards: the status screen warns while it's on.
 */
object TestMode {
    private const val PREFS = "debug"
    private const val KEY = "quiet_test"

    /** Always false in release builds: a muted siren must never be possible on a real phone. */
    fun isOn(ctx: Context): Boolean = BuildConfig.DEBUG && Storage.prefs(ctx, PREFS).getBoolean(KEY, false)

    fun set(ctx: Context, on: Boolean) {
        Storage.prefs(ctx, PREFS).edit().putBoolean(KEY, on).commit()
    }
}
