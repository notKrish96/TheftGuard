package dev.krishgoel.theftguard.alert

import android.content.Context
import dev.krishgoel.theftguard.security.SecureStore

/** Emergency contacts and IMEIs, always stored encrypted (SecureStore). */
object AlertSettings {
    const val MAX_CONTACTS = 3

    fun contacts(ctx: Context): List<String> =
        SecureStore.get(ctx, "contacts")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()

    fun setContacts(ctx: Context, numbers: List<String>) {
        val clean = numbers.map(AlertValidation::normalizePhone).filter { it.isNotEmpty() }
        SecureStore.put(ctx, "contacts", clean.joinToString("\n").ifEmpty { null })
    }

    /** IMEI 1 and IMEI 2, in that order (empty ones left out). */
    fun imeis(ctx: Context): List<String> =
        listOfNotNull(SecureStore.get(ctx, "imei1"), SecureStore.get(ctx, "imei2"))

    fun imei(ctx: Context, slot: Int): String = SecureStore.get(ctx, "imei$slot") ?: ""

    fun setImei(ctx: Context, slot: Int, value: String) {
        SecureStore.put(ctx, "imei$slot", value.filter { it.isDigit() }.ifEmpty { null })
    }
}
