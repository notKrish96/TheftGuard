package dev.krishgoel.theftguard.alert

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.LocationRequest
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager

/**
 * Location via the platform LocationManager (no Google Play Services dependency).
 *
 * Two speeds:
 *  - lastKnown(): instant, but possibly old. Used for the FIRST SMS, because
 *    speed beats accuracy when a thief may switch the phone off any second.
 *  - current(): asks for a fresh high-accuracy fix (GPS), with a timeout.
 *    Used for the follow-up and the live updates.
 *
 * Permissions: ACCESS_FINE_LOCATION, plus ACCESS_BACKGROUND_LOCATION ("Allow
 * all the time"), because alerts fire while TheftGuard isn't on screen.
 * The phone's Location switch must also be on; an app can't turn it on
 * itself (Phase 3b handles that).
 */
object LocationFetcher {
    private val main = Handler(Looper.getMainLooper())

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackgroundPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun isLocationOn(ctx: Context): Boolean = lm(ctx).isLocationEnabled

    /** Most recent fix any provider still remembers, or null. */
    fun lastKnown(ctx: Context): Fix? {
        if (!hasPermission(ctx)) return null
        val lm = lm(ctx)
        return try {
            lm.getProviders(true)
                .mapNotNull { lm.getLastKnownLocation(it) }
                .maxByOrNull { it.elapsedRealtimeNanos }
                ?.toFix()
        } catch (e: SecurityException) {
            null
        }
    }

    /** Fresh fix, delivered on the main thread; null on timeout / no permission / location off. */
    fun current(ctx: Context, timeoutMs: Long, callback: (Fix?) -> Unit) {
        if (!hasPermission(ctx) || !isLocationOn(ctx)) {
            callback(null)
            return
        }
        val lm = lm(ctx)
        // "fused" mixes GPS, Wi-Fi and cell towers; fall back to raw GPS.
        // Before the first unlock after a restart, fused (part of Google Play
        // services) may not be running yet, so go straight to the GPS chip.
        val beforeFirstUnlock = !ctx.getSystemService(UserManager::class.java).isUserUnlocked
        val provider = when {
            beforeFirstUnlock && lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.hasProvider(LocationManager.FUSED_PROVIDER) &&
                lm.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
        val cancel = CancellationSignal()
        var done = false
        fun finish(fix: Fix?) {
            if (done) return
            done = true
            callback(fix)
        }
        val request = LocationRequest.Builder(0L)
            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
            .setDurationMillis(timeoutMs)
            .build()
        try {
            lm.getCurrentLocation(provider, request, cancel, ctx.mainExecutor) { finish(it?.toFix()) }
        } catch (e: Exception) {
            finish(null)
            return
        }
        main.postDelayed({ cancel.cancel(); finish(null) }, timeoutMs + 1_000)
    }

    /** Straight-line distance in metres. */
    fun distanceM(a: Fix, b: Fix): Float {
        val out = FloatArray(1)
        Location.distanceBetween(a.lat, a.lng, b.lat, b.lng, out)
        return out[0]
    }

    private fun lm(ctx: Context) = ctx.getSystemService(LocationManager::class.java)

    private fun Location.toFix() = Fix(
        lat = latitude,
        lng = longitude,
        accuracyM = if (hasAccuracy()) accuracy else 9_999f,
        ageSeconds = (SystemClock.elapsedRealtimeNanos() - elapsedRealtimeNanos) / 1_000_000_000,
    )
}
