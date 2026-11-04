package dev.krishgoel.theftguard.security

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Turns a PIN into something safe to store (decision D2).
 *
 * Why not a normal hash (SHA-256, PBKDF2)?
 *   A 6-digit PIN has only 1,000,000 possible values. If someone copies our
 *   storage file, a PC can try them all in seconds, however slow the hash is.
 *
 * What we do instead:
 *   stored = HMAC-SHA256(key, salt ‖ PIN)
 *   where `key` lives inside the phone's secure hardware (Android Keystore,
 *   see KeystoreMac) and can never be copied out. Without the key, the stored
 *   value is useless. Every guess has to go through the hardware on THIS
 *   phone, where PinStore limits how fast you can guess.
 *
 * The MAC function is passed in, so unit tests can use a normal software key
 * (the Keystore only exists on a real phone).
 */
class PinHasher(
    private val mac: (ByteArray) -> ByteArray,
    private val random: SecureRandom = SecureRandom(),
) {
    /** 16 random bytes, new for every PIN. Same PIN on two phones → different stored values. */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also(random::nextBytes)

    fun hash(pin: String, salt: ByteArray): ByteArray =
        mac(salt + pin.toByteArray(Charsets.UTF_8))

    /**
     * MessageDigest.isEqual compares in constant time: it always checks every
     * byte, so the time it takes doesn't reveal how many leading bytes matched
     * (a "timing side channel").
     */
    fun matches(pin: String, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(hash(pin, salt), expected)

    companion object {
        const val SALT_BYTES = 16
        const val MIN_LENGTH = 6
        const val MAX_LENGTH = 12

        /** Digits only, 6–12 long. */
        fun isValidFormat(pin: String): Boolean =
            pin.length in MIN_LENGTH..MAX_LENGTH && pin.all { it in '0'..'9' }
    }
}

/**
 * How long to make someone wait after wrong PINs.
 * The first 4 mistakes are free (fat fingers). From the 5th: 30 s, then
 * doubling (60 s, 2 min, 4 min…), capped at 1 hour.
 * Even at the cap's pace, trying all 1,000,000 six-digit PINs would take
 * over 100 years.
 */
object LockoutPolicy {
    const val FREE_ATTEMPTS = 4
    private const val BASE_MS = 30_000L
    private const val MAX_MS = 60 * 60_000L

    fun delayMs(failures: Int): Long {
        if (failures <= FREE_ATTEMPTS) return 0
        val doublings = (failures - FREE_ATTEMPTS - 1).coerceAtMost(20)   // avoid overflow
        return (BASE_MS shl doublings).coerceAtMost(MAX_MS)
    }
}
