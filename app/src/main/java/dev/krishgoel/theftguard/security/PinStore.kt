package dev.krishgoel.theftguard.security

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import dev.krishgoel.theftguard.Storage

/**
 * Stores the TheftGuard PIN (as salt + Keystore HMAC, never the PIN itself)
 * and enforces the wrong-attempt lockout.
 *
 * Lives in normal private SharedPreferences. That's fine: what's stored is
 * useless without the hardware key, and backups are disabled in the manifest.
 */
class PinStore(context: Context) {
    private val appContext = context.applicationContext ?: context

    sealed interface Result {
        data object Ok : Result
        /** failures = wrong tries in a row; waitMs = lockout that now applies (0 = none). */
        data class Wrong(val failures: Int, val waitMs: Long) : Result
        data class LockedOut(val remainingMs: Long) : Result
        /** The hardware key couldn't be used right now. Fail closed: the gate stays shut. */
        data object Unavailable : Result
    }

    // Device-protected storage, so the PIN can be checked even before the
    // first unlock after a restart (see Storage.kt).
    private val prefs = Storage.prefs(context, "pin_store")
    private val hasher = PinHasher(KeystoreMac::mac)

    fun isSet(): Boolean = prefs.contains(KEY_HASH)

    fun failures(): Int = prefs.getInt(KEY_FAILURES, 0)

    fun setPin(pin: String) {
        require(PinHasher.isValidFormat(pin)) { "PIN must be 6–12 digits" }
        val salt = hasher.newSalt()
        prefs.edit()
            .putString(KEY_SALT, salt.b64())
            .putString(KEY_HASH, hasher.hash(pin, salt).b64())
            .putInt(KEY_FAILURES, 0)
            .putLong(KEY_LOCKED_UNTIL, 0)
            .remove(KEY_LAST_FAIL)
            .remove(KEY_LOCKED_UNTIL_ELAPSED)
            .apply()
    }

    @Synchronized
    fun check(pin: String): Result {
        val now = System.currentTimeMillis()
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0)
        if (now < lockedUntil) return Result.LockedOut(lockedUntil - now)

        // Second lockout clock: time since boot (elapsedRealtime). Nobody can
        // change it, so moving the wall clock FORWARD doesn't skip the lockout
        // either. It restarts at 0 on every boot, so it's only trusted within
        // the same boot (Android's boot counter tells us which boot it was).
        val elapsedNow = SystemClock.elapsedRealtime()
        if (prefs.getInt(KEY_BOOT, -1) == bootCount()) {
            val untilElapsed = prefs.getLong(KEY_LOCKED_UNTIL_ELAPSED, 0)
            if (elapsedNow < untilElapsed) return Result.LockedOut(untilElapsed - elapsedNow)
        }

        // Clock-rollback guard: the lockout is stored as wall-clock time (it must
        // survive restarts). Someone holding an UNLOCKED phone could set the clock
        // forward to skip it, then back. If the clock is now earlier than our last
        // wrong try, it was changed, so restart the full lockout from now.
        val lastFail = prefs.getLong(KEY_LAST_FAIL, 0)
        if (now + CLOCK_SLACK_MS < lastFail) {
            val wait = LockoutPolicy.delayMs(failures()).coerceAtLeast(LockoutPolicy.delayMs(LockoutPolicy.FREE_ATTEMPTS + 1))
            prefs.edit().putLong(KEY_LOCKED_UNTIL, now + wait).putLong(KEY_LAST_FAIL, now).commit()
            return Result.LockedOut(wait)
        }

        val salt = prefs.getString(KEY_SALT, null)?.unb64()
        val hash = prefs.getString(KEY_HASH, null)?.unb64()
        if (salt == null || hash == null) return Result.Wrong(0, 0)

        val ok = try {
            hasher.matches(pin, salt, hash)
        } catch (e: Exception) {
            // e.g. Keystore not ready yet very early after boot. Never treat this
            // as "PIN correct", and don't count it as a wrong try either.
            return Result.Unavailable
        }

        if (ok) {
            prefs.edit().putInt(KEY_FAILURES, 0).putLong(KEY_LOCKED_UNTIL, 0)
                .remove(KEY_LAST_FAIL).remove(KEY_LOCKED_UNTIL_ELAPSED).apply()
            return Result.Ok
        }

        val failures = failures() + 1
        val wait = LockoutPolicy.delayMs(failures)
        // commit() (synchronous) rather than apply(): the failure count must be on
        // disk before we return, or a thief could kill the app to reset it.
        prefs.edit()
            .putInt(KEY_FAILURES, failures)
            .putLong(KEY_LOCKED_UNTIL, if (wait > 0) now + wait else 0)
            .putLong(KEY_LAST_FAIL, now)
            .putLong(KEY_LOCKED_UNTIL_ELAPSED, if (wait > 0) elapsedNow + wait else 0)
            .putInt(KEY_BOOT, bootCount())
            .commit()
        return Result.Wrong(failures, wait)
    }

    private fun bootCount(): Int =
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unb64() = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val KEY_SALT = "salt"
        const val KEY_HASH = "hmac"
        const val KEY_FAILURES = "failures"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_LAST_FAIL = "last_fail"
        const val KEY_LOCKED_UNTIL_ELAPSED = "locked_until_elapsed"
        const val KEY_BOOT = "boot"
        const val CLOCK_SLACK_MS = 5 * 60_000L      // normal network time corrections are smaller
    }
}
