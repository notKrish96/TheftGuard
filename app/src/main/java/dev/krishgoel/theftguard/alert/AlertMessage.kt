package dev.krishgoel.theftguard.alert

import java.util.Locale

/** A location fix, reduced to what the SMS needs. */
data class Fix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float,
    val ageSeconds: Long,
)

/**
 * Builds the SMS text. Pure Kotlin (unit-tested on the PC).
 *
 * Two hard rules, both tested:
 *
 * 1. ONE SMS (≤ 160 characters). Measured on the S23 FE, 2026-11-04: the
 *    carrier accepts about one SMS part every 6 s. A 2-part alert took ~13 s
 *    to leave the phone, far too slow for the ~5 s force-restart warning.
 *    So: no phone name (contacts already see which number it came from),
 *    short wording, short reasons.
 *
 * 2. Plain ASCII. SMS has a 7-bit "GSM" alphabet that fits 160 characters.
 *    One non-GSM character (like "±" or an emoji) switches the whole text to
 *    UCS-2, which fits only 70. Hence "+-" instead of "±".
 *
 * Example (148 chars):
 *   TheftGuard ALERT: 3 wrong lock-screen PINs. https://maps.google.com/?q=28.61394,77.20902 +-12m 40s ago. IMEI 490154203237518 356938035643809. Bat 54%
 */
object AlertMessage {
    const val SMS_LIMIT = 160

    fun alert(reason: String, imeis: List<String>, fix: Fix?, batteryPct: Int?): String =
        buildString {
            append("TheftGuard ALERT: ").append(reason).append(". ")
            append(location(fix)).append('.')
            if (imeis.isNotEmpty()) append(" IMEI ").append(imeis.joinToString(" ")).append('.')
            batteryPct?.let { append(" Bat ").append(it).append('%') }
        }

    fun update(fix: Fix, batteryPct: Int?): String =
        buildString {
            append("TheftGuard live: ").append(location(fix)).append('.')
            batteryPct?.let { append(" Bat ").append(it).append('%') }
        }

    fun ownerOk(): String = "TheftGuard: false alarm. The correct PIN was entered, so it was the owner."

    fun stopped(): String = "TheftGuard: alarm stopped with the correct PIN. Live location ended."

    fun location(fix: Fix?): String =
        if (fix == null) "Location unknown (may be off)"
        else String.format(
            Locale.US, "https://maps.google.com/?q=%.5f,%.5f +-%dm %s ago",
            fix.lat, fix.lng, fix.accuracyM.toInt().coerceAtMost(9_999), age(fix.ageSeconds),
        )

    fun age(seconds: Long): String = when {
        seconds < 90 -> "${seconds}s"
        seconds < 90 * 60 -> "${seconds / 60}min"
        else -> "${seconds / 3600}h"
    }
}
