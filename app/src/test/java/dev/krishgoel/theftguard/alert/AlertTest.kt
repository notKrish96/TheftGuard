package dev.krishgoel.theftguard.alert

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertTest {

    // ── IMEI / Luhn ──

    /** 490154203237518 is the standard example IMEI from the GSMA/Wikipedia. */
    @Test fun validImei() = assertTrue(AlertValidation.isValidImei("490154203237518"))

    @Test fun imeiWithSpacesIsFine() = assertTrue(AlertValidation.isValidImei("49015420 3237518"))

    @Test fun oneWrongDigitIsCaught() = assertFalse(AlertValidation.isValidImei("490154203237519"))

    @Test fun swappedDigitsAreCaught() = assertFalse(AlertValidation.isValidImei("490154203235718"))

    @Test fun wrongLength() {
        assertFalse(AlertValidation.isValidImei("49015420323751"))
        assertFalse(AlertValidation.isValidImei("4901542032375180"))
    }

    // ── phone numbers ──

    @Test fun phoneNormalising() {
        assertEquals("+919876543210", AlertValidation.normalizePhone(" +91 98765-43210 "))
        assertEquals("07700900123", AlertValidation.normalizePhone("07700 900123"))
    }

    @Test fun phoneValidity() {
        assertTrue(AlertValidation.isValidPhone("+91 98765 43210"))
        assertTrue(AlertValidation.isValidPhone("+44 7700 900123"))
        assertFalse(AlertValidation.isValidPhone("12345"))
        assertFalse(AlertValidation.isValidPhone("+1234567890123456"))
    }

    // ── message text ──

    private val fix = Fix(lat = 28.613939, lng = 77.209023, accuracyM = 12.7f, ageSeconds = 40)

    @Test fun alertContainsEverything() {
        val text = AlertMessage.alert("3 wrong lock-screen PINs",
            listOf("490154203237518", "356938035643809"), fix, 54)
        assertTrue(text, text.startsWith("TheftGuard ALERT: 3 wrong lock-screen PINs."))
        assertTrue(text, "IMEI 490154203237518 356938035643809" in text)
        assertTrue(text, "https://maps.google.com/?q=28.61394,77.20902" in text)
        assertTrue(text, "+-12m 40s ago" in text)
        assertTrue(text, text.endsWith("Bat 54%"))
    }

    /**
     * Worst case must still be ONE SMS: the longest reason we use, negative
     * coordinates with 3-digit longitude, 4-digit accuracy, longest age, 100%.
     */
    @Test fun worstCaseFitsOneSms() {
        val worst = Fix(lat = -33.868820, lng = -151.209290, accuracyM = 25_000f, ageSeconds = 89 * 60)
        val reasons = listOf(
            "12 wrong lock-screen PINs", "12 wrong TheftGuard PINs",
            "power-off try while locked", "forced restart detected", "restarted during alarm",
        )
        for (r in reasons) {
            val text = AlertMessage.alert(r, listOf("490154203237518", "356938035643809"), worst, 100)
            assertTrue("${text.length}: $text", text.length <= AlertMessage.SMS_LIMIT)
        }
        val live = AlertMessage.update(worst, 100)
        assertTrue("${live.length}: $live", live.length <= AlertMessage.SMS_LIMIT)
        assertTrue(AlertMessage.stopped().length <= AlertMessage.SMS_LIMIT)
        assertTrue(AlertMessage.ownerOk().length <= AlertMessage.SMS_LIMIT)
    }

    /** Pure GSM-7 ASCII, so each SMS part fits 160 characters, not 70 (see AlertMessage). */
    @Test fun messagesAreAscii() {
        val texts = listOf(
            AlertMessage.alert("x", listOf("490154203237518"), fix, 10),
            AlertMessage.alert("x", emptyList(), null, null),
            AlertMessage.update(fix, 80),
            AlertMessage.stopped(),
            AlertMessage.ownerOk(),
        )
        for (t in texts) assertTrue(t, t.all { it.code in 32..126 })
    }

    @Test fun unknownLocation() {
        val text = AlertMessage.alert("x", emptyList(), null, null)
        assertTrue(text, "Location unknown" in text)
        assertFalse(text, "Bat" in text)
    }

    @Test fun ageFormatting() {
        assertEquals("5s", AlertMessage.age(5))
        assertEquals("3min", AlertMessage.age(200))
        assertEquals("2h", AlertMessage.age(7_300))
    }
}
