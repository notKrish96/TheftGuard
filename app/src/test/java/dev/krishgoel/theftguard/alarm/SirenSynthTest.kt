package dev.krishgoel.theftguard.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SirenSynthTest {

    private val pcm = SirenSynth.cycle()

    @Test fun oneSecondOfAudio() {
        assertEquals(SirenSynth.SAMPLE_RATE, pcm.size)
    }

    /** Loud: close to full scale, but never clipping past it. */
    @Test fun nearFullScale() {
        val peak = pcm.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak too quiet", peak > 0.95 * Short.MAX_VALUE)
        assertTrue(peak <= Short.MAX_VALUE)
    }

    /** Soft clipping makes it "fat": most samples sit near the top, unlike a thin sine wave. */
    @Test fun loudOnAverage() {
        val meanAbs = pcm.sumOf { abs(it.toInt()).toLong() } / pcm.size
        assertTrue("mean $meanAbs", meanAbs > 0.75 * Short.MAX_VALUE)
    }

    /**
     * The loop joins smoothly: the cycle starts at 0, and the last sample is
     * small, about one step of a 650 Hz wave (the cycle ends on a whole number of waves).
     */
    @Test fun loopsWithoutClick() {
        assertEquals(0, pcm.first().toInt())
        assertTrue("last sample ${pcm.last()}", abs(pcm.last().toInt()) < 0.35 * Short.MAX_VALUE)
    }
}
