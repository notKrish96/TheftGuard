package dev.krishgoel.theftguard.gate

import android.content.Context
import dev.krishgoel.theftguard.BuildConfig
import dev.krishgoel.theftguard.Storage
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Phase 0 helper: writes what the accessibility service sees to two places.
 *
 *  1. Logcat (tag "TGRec"), so we can read it live from the PC:
 *        adb logcat -s TGRec
 *  2. A file in the app's private storage (files/recorder.log), so the in-app
 *     screen can show it even if the phone wasn't plugged in at the time.
 *
 * Recording is OFF by default and only runs while the switch in the app is on.
 * This code is debug tooling and will be removed before the real gate ships.
 */
object EventRecorder {
    const val TAG = "TGRec"

    private const val FILE_NAME = "recorder.log"
    private const val MAX_BYTES = 512 * 1024L          // start a fresh file past 512 KB
    private const val PREFS = "recorder"
    private const val KEY_RECORDING = "recording"
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    /**
     * The window recorder dumps on-screen text from system windows (it can
     * include notification snippets), so it exists in debug builds only.
     */
    fun isRecording(ctx: Context): Boolean =
        BuildConfig.DEBUG && Storage.prefs(ctx, PREFS).getBoolean(KEY_RECORDING, false)

    fun setRecording(ctx: Context, on: Boolean) {
        Storage.prefs(ctx, PREFS).edit()
            .putBoolean(KEY_RECORDING, on)
            .apply()
    }

    /** Append one timestamped line to logcat and the log file. */
    fun line(ctx: Context, text: String) {
        val stamped = "${LocalTime.now().format(clock)} $text"
        // Logcat only in debug builds. (Other apps can't read logcat, but any
        // computer with USB debugging can, and release builds have no reason to.)
        if (BuildConfig.DEBUG) Log.i(TAG, stamped)
        // The accessibility service and the activity both write here; synchronized
        // stops two lines from interleaving mid-write.
        synchronized(this) {
            val file = File(Storage.filesDir(ctx), FILE_NAME)
            if (file.length() > MAX_BYTES) file.writeText("[log rotated]\n")
            // fd.sync() forces the line onto flash storage right now. Without it,
            // Linux keeps recent writes in RAM for a while, and a force restart
            // (power cut) silently loses the last seconds of log. That's exactly
            // the part we need to see. Found in testing 2026-11-04.
            FileOutputStream(file, true).use { out ->
                out.write((stamped + "\n").toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }
    }

    fun readTail(ctx: Context, maxLines: Int = 300): String = synchronized(this) {
        val file = File(Storage.filesDir(ctx), FILE_NAME)
        if (!file.exists()) return ""
        file.readLines().takeLast(maxLines).joinToString("\n")
    }

    fun clear(ctx: Context) = synchronized(this) {
        File(Storage.filesDir(ctx), FILE_NAME).delete()
    }
}
