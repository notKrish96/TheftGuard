package dev.krishgoel.theftguard.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import dev.krishgoel.theftguard.Storage
import dev.krishgoel.theftguard.TestMode
import dev.krishgoel.theftguard.alert.AlertSender
import dev.krishgoel.theftguard.gate.EventRecorder

/**
 * The wrong-attempt alarm: loud, looping, hard to silence.
 *
 * Where it runs: inside our app process, which the accessibility service
 * keeps alive at foreground priority. A foreground service would be the
 * textbook choice, but Android 12+ blocks starting one from the background
 * (and a failed-unlock broadcast isn't an exemption). Audio playback itself
 * has no such restriction.
 *
 * What makes it hard to silence:
 *  - USAGE_ALARM: plays even in silent / vibrate mode.
 *  - Forced to the built-in speaker, so plugging in headphones doesn't help.
 *  - Every 200 ms we reset the alarm volume to maximum, so pressing
 *    Volume Down does nothing useful.
 *  - The gate blocks the power menu.
 *  - The "active" flag is saved to storage. If the thief force-restarts the
 *    phone (the one thing we can't block), the alarm starts again as soon as
 *    the accessibility service comes back after boot.
 *
 * What stops it: the right TheftGuard PIN, or the right lock-screen PIN
 * (onPasswordSucceeded). Fingerprint/face unlock deliberately does NOT stop it
 * (Krish's decision, 2026-11-04): a fingerprint can be forced, e.g. by
 * pressing a sleeping or restrained owner's finger to the sensor. A PIN
 * has to be known.
 *
 * Honest limits: Do Not Disturb set to block alarms may still mute it, and a
 * hardware force-restart pauses it for the length of the reboot.
 */
object AlarmController {
    private const val PREFS = "alarm"
    private const val KEY_ACTIVE = "active"
    private const val KEY_REASON = "reason"
    private const val VOLUME_CHECK_MS = 200L
    private const val RESTARTED = "restarted during alarm"

    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    /** True while a quiet-test-mode alarm is "sounding" (vibration only, no AudioTrack). */
    private var quietTrack = false
    private var appContext: Context? = null

    /** Told when the alarm starts/stops, so the service can show or hide the PIN screen. */
    var listener: ((active: Boolean) -> Unit)? = null

    fun isActive(ctx: Context): Boolean = Storage.prefs(ctx, PREFS).getBoolean(KEY_ACTIVE, false)

    fun reason(ctx: Context): String = Storage.prefs(ctx, PREFS).getString(KEY_REASON, "") ?: ""

    /**
     * Start the alarm (no-op if already sounding). autoStopMs > 0 is only for the
     * test button. sendSms = false when the caller has already sent its own alert
     * (the force-restart detector does, to save every millisecond).
     */
    fun start(ctx: Context, reason: String, autoStopMs: Long = 0, sendSms: Boolean = true) = main.post {
        val app = ctx.applicationContext ?: ctx
        appContext = app
        if (autoStopMs == 0L) {
            // commit(): must be on disk before anything else, so a restart can't lose it.
            Storage.prefs(app, PREFS).edit()
                .putBoolean(KEY_ACTIVE, true).putString(KEY_REASON, reason).commit()
        }
        if (track == null && !quietTrack) {
            EventRecorder.line(app, "!!! ALARM START: $reason")
            // SMS first: it's the part that matters most if the thief manages to
            // switch the phone off in the next few seconds.
            if (autoStopMs == 0L && sendSms) AlertSender.alert(app, reason)
            if (TestMode.isOn(app)) {
                // Quiet test mode: vibration only, no siren, no volume forcing.
                EventRecorder.line(app, "!!! quiet test mode: siren muted")
                quietTrack = true
            } else {
                startSound(app)
                main.post(volumeEnforcer)
            }
            startVibration(app)
        }
        listener?.invoke(true)
        if (autoStopMs > 0) main.postDelayed({ stop(app) }, autoStopMs)
    }

    fun stop(ctx: Context) = main.post {
        val app = ctx.applicationContext ?: ctx
        Storage.prefs(app, PREFS).edit().putBoolean(KEY_ACTIVE, false).commit()
        if (track != null || quietTrack) EventRecorder.line(app, "!!! ALARM STOP")
        quietTrack = false
        AlertSender.onAlarmStopped(app)
        main.removeCallbacks(volumeEnforcer)
        track?.run { stop(); release() }
        track = null
        app.getSystemService(VibratorManager::class.java).defaultVibrator.cancel()
        listener?.invoke(false)
    }

    /** Called when the service (re)connects, e.g. after a forced restart. */
    fun resumeIfActive(ctx: Context) {
        if (isActive(ctx)) {
            val before = reason(ctx).ifEmpty { "alarm" }
            EventRecorder.line(ctx, "!!! resuming alarm after restart (was: $before)")
            start(ctx, RESTARTED)
        }
    }

    private fun startSound(ctx: Context) {
        val pcm = SirenSynth.cycle()
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SirenSynth.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            // STATIC: the whole sound is written once and the hardware loops it,
            // so nothing has to keep feeding audio while the phone is busy.
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)          // -1 = loop forever

        val am = ctx.getSystemService(AudioManager::class.java)
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            ?.let { t.setPreferredDevice(it) }

        forceMaxVolume(am)
        t.play()
        track = t
    }

    private fun startVibration(ctx: Context) {
        val vibrator = ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        // 600 ms on, 300 ms off, repeat from index 0 forever.
        vibrator.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0, 600, 300), 0),
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
        )
    }

    private val volumeEnforcer = object : Runnable {
        override fun run() {
            appContext?.let { forceMaxVolume(it.getSystemService(AudioManager::class.java)) }
            main.postDelayed(this, VOLUME_CHECK_MS)
        }
    }

    private fun forceMaxVolume(am: AudioManager) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        if (am.getStreamVolume(AudioManager.STREAM_ALARM) < max) {
            am.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
        }
    }
}
