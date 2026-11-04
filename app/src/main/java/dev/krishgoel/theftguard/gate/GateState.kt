package dev.krishgoel.theftguard.gate

import android.content.Context
import dev.krishgoel.theftguard.Storage
import android.os.SystemClock

/**
 * Small bits of gate state.
 *
 * - enabled: the on/off switch on the main screen (saved to disk).
 *
 * - allowance: after the right PIN, exactly ONE power menu may stay open,
 *   namely the one we reopen for you, and only for as long as it stays open.
 *
 *     NONE ──(right PIN)──► PENDING ──(our reopened menu appears)──► CLAIMED(windowId)
 *                              │                                         │
 *                     not claimed within 3 s                  that window closes
 *                              │                                (Back, tap outside…)
 *                              ▼                                         ▼
 *                            NONE ◄──────────────────────────────────── NONE
 *
 *   While CLAIMED, more events from that same window are fine (the menu fires
 *   several). Any OTHER power-menu window is blocked. There's no time limit
 *   on using the menu, but the moment it closes, the next one needs the PIN again.
 *
 *   Why PENDING has a 3 s deadline: if reopening the menu failed for some
 *   reason, an unclaimed allowance would otherwise wait forever, and the
 *   next menu a thief opened (hours later) would claim it.
 *
 *   Kept in memory only, so if the service restarts, the allowance is gone
 *   (fail closed).
 *
 * History: v1 allowed *every* menu for 20 s after the PIN, so in testing the
 * quick-panel power button worked freely right after a correct PIN.
 */
object GateState {
    private const val CLAIM_WINDOW_MS = 3_000L
    private const val NONE = Int.MIN_VALUE
    private const val PENDING = Int.MIN_VALUE + 1

    private const val PREFS = "gate"
    private const val KEY_ENABLED = "enabled"

    /** NONE, PENDING, or the claimed menu's window ID. */
    private var allowed = NONE
    private var claimDeadline = 0L

    fun isEnabled(ctx: Context): Boolean =
        Storage.prefs(ctx, PREFS).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        Storage.prefs(ctx, PREFS).edit()
            .putBoolean(KEY_ENABLED, on).apply()
    }

    /** Called after the right PIN. The next power menu to appear may stay open. */
    @Synchronized
    fun grantOneMenu() {
        allowed = PENDING
        claimDeadline = SystemClock.elapsedRealtime() + CLAIM_WINDOW_MS
    }

    /** Called on every power-menu event. True = let this menu window stay open. */
    @Synchronized
    fun isAllowed(menuWindowId: Int): Boolean = when (allowed) {
        NONE -> false
        PENDING ->
            if (SystemClock.elapsedRealtime() < claimDeadline) {
                allowed = menuWindowId          // our reopened menu claims the allowance
                true
            } else {
                allowed = NONE
                false
            }
        else -> allowed == menuWindowId
    }

    /** The window that currently holds the allowance, or null. */
    @Synchronized
    fun claimedWindowId(): Int? = allowed.takeIf { it != NONE && it != PENDING }

    /** The allowed menu has closed: the next power menu needs the PIN again. */
    @Synchronized
    fun revoke() {
        allowed = NONE
    }
}
