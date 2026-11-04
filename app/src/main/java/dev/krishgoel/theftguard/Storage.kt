package dev.krishgoel.theftguard

import android.content.Context
import android.content.SharedPreferences
import android.os.UserManager
import java.io.File

/**
 * Where TheftGuard keeps its data: DEVICE-protected storage.
 *
 * Android has two storage areas per app ("Direct Boot"):
 *  - Credential-encrypted (CE, the default): unlocked only after you enter
 *    your PIN once after a restart. Before that, it simply can't be read.
 *  - Device-encrypted (DE): readable as soon as the phone boots.
 *
 * Bug this fixes (found in testing 2026-11-04): after a restart the gate did
 * nothing until the first unlock, because the service wasn't allowed to start
 * and its settings were in CE storage. A thief who restarts your phone gets
 * exactly that window.
 *
 * Is DE storage safe enough? What we store is the PIN's HMAC (useless without
 * the hardware key), a few switches, and the debug log. Other apps can't read
 * it either way. The trade-off is fine.
 *
 * Old data written to CE storage by earlier builds is moved over automatically
 * the first time we run while the phone is unlocked.
 */
object Storage {
    private val PREF_FILES = listOf("pin_store", "gate", "recorder", "alarm", "debug")
    private const val LOG_FILE = "recorder.log"

    @Volatile
    private var migrated = false

    fun prefs(ctx: Context, name: String): SharedPreferences {
        migrateIfPossible(ctx)
        return de(ctx).getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    fun filesDir(ctx: Context): File {
        migrateIfPossible(ctx)
        return de(ctx).filesDir
    }

    private fun de(ctx: Context): Context =
        (ctx.applicationContext ?: ctx).createDeviceProtectedStorageContext()

    @Synchronized
    private fun migrateIfPossible(ctx: Context) {
        if (migrated) return
        val app = ctx.applicationContext ?: ctx
        // CE storage can only be touched once the user has unlocked since boot.
        if (!app.getSystemService(UserManager::class.java).isUserUnlocked) return

        val de = de(app)
        for (name in PREF_FILES) de.moveSharedPreferencesFrom(app, name)
        val oldLog = File(app.filesDir, LOG_FILE)
        if (oldLog.exists()) oldLog.renameTo(File(de.filesDir, LOG_FILE))
        migrated = true
    }
}
