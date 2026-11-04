package dev.krishgoel.theftguard.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Runs on the PC (no phone needed). The Keystore only exists on a real phone,
 * so here we hand PinHasher an ordinary software HMAC key. The logic under
 * test (salting, comparing, format rules, lockout) is the same.
 */
class PinHasherTest {

    private fun softwareMac(key: ByteArray): (ByteArray) -> ByteArray = { data ->
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
    }

    private val hasher = PinHasher(softwareMac(ByteArray(32) { it.toByte() }))

    @Test fun rightPinMatches() {
        val salt = hasher.newSalt()
        val stored = hasher.hash("482915", salt)
        assertTrue(hasher.matches("482915", salt, stored))
    }

    @Test fun wrongPinDoesNotMatch() {
        val salt = hasher.newSalt()
        val stored = hasher.hash("482915", salt)
        assertFalse(hasher.matches("482916", salt, stored))
        assertFalse(hasher.matches("", salt, stored))
    }

    @Test fun samePinDifferentSaltGivesDifferentHash() {
        assertNotEquals(
            hasher.hash("482915", hasher.newSalt()).toList(),
            hasher.hash("482915", hasher.newSalt()).toList(),
        )
    }

    /** The point of the hardware key: a different key (an attacker's PC) can't reproduce the hash. */
    @Test fun differentKeyCannotVerify() {
        val salt = hasher.newSalt()
        val stored = hasher.hash("482915", salt)
        val attacker = PinHasher(softwareMac(ByteArray(32) { 7 }))
        assertFalse(attacker.matches("482915", salt, stored))
    }

    @Test fun pinFormatRules() {
        assertTrue(PinHasher.isValidFormat("123456"))
        assertTrue(PinHasher.isValidFormat("123456789012"))
        assertFalse(PinHasher.isValidFormat("12345"))          // too short
        assertFalse(PinHasher.isValidFormat("1234567890123"))  // too long
        assertFalse(PinHasher.isValidFormat("12345a"))         // not digits
        assertFalse(PinHasher.isValidFormat("１２３４５６"))   // full-width digits aren't 0–9
    }

    @Test fun lockoutSchedule() {
        assertEquals(0, LockoutPolicy.delayMs(1))
        assertEquals(0, LockoutPolicy.delayMs(4))
        assertEquals(30_000, LockoutPolicy.delayMs(5))
        assertEquals(60_000, LockoutPolicy.delayMs(6))
        assertEquals(120_000, LockoutPolicy.delayMs(7))
        assertEquals(3_600_000, LockoutPolicy.delayMs(50))    // capped at 1 h
        assertEquals(3_600_000, LockoutPolicy.delayMs(Int.MAX_VALUE))
    }
}
