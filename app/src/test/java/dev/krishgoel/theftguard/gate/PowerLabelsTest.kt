package dev.krishgoel.theftguard.gate

import dev.krishgoel.theftguard.gate.PowerLabels.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerLabelsTest {

    /** English fallbacks plus a pretend Hindi translation, as Android would supply at runtime. */
    private val labels = PowerLabels.ENGLISH.mapValues { (kind, set) ->
        when (kind) {
            Kind.POWER_OFF -> set + "पावर बंद"
            Kind.RESTART -> set + "रीस्टार्ट करें"
            else -> set
        }
    }

    @Test fun normalizesOemQuirks() {
        assertEquals("power off", PowerLabels.normalize("Power off,"))     // Samsung screen-reader text
        assertEquals("restart", PowerLabels.normalize("  Restart. "))
        assertEquals("power off", PowerLabels.normalize("Power   off"))
    }

    @Test fun recognisesLabels() {
        assertEquals(Kind.POWER_OFF, PowerLabels.kindOf("Power off,", labels))
        assertEquals(Kind.POWER_OFF, PowerLabels.kindOf("Shut down", labels))
        assertEquals(Kind.RESTART, PowerLabels.kindOf("Reboot", labels))
        assertEquals(Kind.EMERGENCY, PowerLabels.kindOf("Emergency call", labels))
        assertEquals(Kind.POWER_OFF, PowerLabels.kindOf("पावर बंद", labels))
    }

    @Test fun ignoresOtherText() {
        assertNull(PowerLabels.kindOf("Power off menu", labels))      // Samsung container, not a button
        assertNull(PowerLabels.kindOf("Lockdown mode", labels))
        assertNull(PowerLabels.kindOf("Restart your phone to finish installing the update", labels))
        assertNull(PowerLabels.kindOf("", labels))
    }

    @Test fun decision() {
        assertTrue(PowerLabels.looksLikePowerMenu(setOf(Kind.POWER_OFF, Kind.RESTART)))
        assertTrue(PowerLabels.looksLikePowerMenu(setOf(Kind.POWER_OFF, Kind.RESTART, Kind.EMERGENCY)))
        // Locked Samsung quick panel: power icon + lock screen's Emergency button. Must NOT match.
        assertFalse(PowerLabels.looksLikePowerMenu(setOf(Kind.POWER_OFF, Kind.EMERGENCY)))
        assertFalse(PowerLabels.looksLikePowerMenu(setOf(Kind.RESTART)))
        assertFalse(PowerLabels.looksLikePowerMenu(setOf(Kind.POWER_OFF)))
        assertFalse(PowerLabels.looksLikePowerMenu(emptySet()))
    }
}
