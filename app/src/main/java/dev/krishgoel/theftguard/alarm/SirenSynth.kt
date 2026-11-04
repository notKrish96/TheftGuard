package dev.krishgoel.theftguard.alarm

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Generates the siren sound in code, so the APK needs no audio file.
 *
 * One cycle = the pitch sweeps up from LOW_HZ to HIGH_HZ and back down over
 * PERIOD_S. AudioTrack loops this cycle forever.
 *
 * Two tricks:
 *  - Loudness: a pure sine wave sounds weak. Passing it through tanh()
 *    ("soft clipping") squares off the tops, which adds harsh harmonics. It
 *    sounds much louder at the same peak level, like a real alarm.
 *  - No click at the loop point: the number of waves in one cycle is
 *    average frequency × duration = 1150 Hz × 1.0 s = exactly 1150.
 *    A whole number of waves means the cycle ends where it started (at 0),
 *    so the loop joins smoothly.
 */
object SirenSynth {
    const val SAMPLE_RATE = 44_100
    private const val LOW_HZ = 650.0
    private const val HIGH_HZ = 1650.0
    private const val PERIOD_S = 1.0
    private const val DRIVE = 3.0           // how hard tanh() squashes the wave
    private const val LEVEL = 0.98          // just under full scale

    fun cycle(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val frames = (sampleRate * PERIOD_S).toInt()
        val out = ShortArray(frames)
        val norm = tanh(DRIVE)
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / frames                    // 0 → 1 across the cycle
            val sweep = if (t < 0.5) t * 2 else (1 - t) * 2   // triangle 0 → 1 → 0
            val hz = LOW_HZ + (HIGH_HZ - LOW_HZ) * sweep
            val sample = tanh(DRIVE * sin(phase)) / norm * LEVEL
            out[i] = (sample * Short.MAX_VALUE).toInt().toShort()
            phase += 2 * PI * hz / sampleRate
        }
        return out
    }
}
