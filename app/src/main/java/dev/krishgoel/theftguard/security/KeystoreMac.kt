package dev.krishgoel.theftguard.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory

/**
 * An HMAC-SHA256 key that lives in the Android Keystore.
 *
 * "AndroidKeyStore" isn't a file; it's a door into secure hardware:
 *  - StrongBox: a separate security chip (strongest), if the phone has one
 *  - TEE: a locked-off area of the main processor (still hardware-protected)
 * Our app only gets a *handle* to the key. We can ask the hardware to compute
 * an HMAC with it, but the key bytes never enter our process, so they can't
 * be copied even by someone who roots the phone and dumps our storage.
 *
 * We deliberately do NOT set setUnlockedDeviceRequired(true): the gate has to
 * check your PIN while the phone is LOCKED.
 */
object KeystoreMac {
    private const val ALIAS = "theftguard_pin_hmac_v1"
    private const val PROVIDER = "AndroidKeyStore"

    fun mac(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(getOrCreateKey())
        return mac.doFinal(data)
    }

    /** For the status screen: where does the key actually live on this phone? */
    fun securityLevelName(): String = try {
        val key = getOrCreateKey()
        val info = SecretKeyFactory.getInstance(key.algorithm, PROVIDER)
            .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        when (info.securityLevel) {
            KeyProperties.SECURITY_LEVEL_STRONGBOX -> "StrongBox (separate secure chip)"
            KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> "TEE (secure area of the processor)"
            KeyProperties.SECURITY_LEVEL_SOFTWARE -> "software only (weak!)"
            else -> "unknown (${info.securityLevel})"
        }
    } catch (e: Exception) {
        "error: ${e.javaClass.simpleName}"
    }

    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }

        // Prefer the separate chip; fall back to the TEE if this phone has no StrongBox.
        return try {
            generate(strongBox = true)
        } catch (e: StrongBoxUnavailableException) {
            generate(strongBox = false)
        }
    }

    private fun generate(strongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setIsStrongBoxBacked(strongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, PROVIDER)
            .apply { init(spec) }
            .generateKey()
    }
}
