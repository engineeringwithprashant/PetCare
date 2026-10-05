package com.prash.petcare.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.prash.petcare.util.Coordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/** A location fix: where the phone is, and how sure the device is about it. */
data class LocationFix(val coordinates: Coordinates, val accuracyMetres: Float?)

/** Where the phone is right now — the app's second data source after Room. */
class LocationRepository(context: Context) {

    private val appContext = context.applicationContext

    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

    /** True when the user has granted precise or approximate location access. */
    fun hasLocationPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** True only for *precise* access — what capturing a place's geotag really wants. */
    fun hasPreciseLocationPermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    /** True when the device can produce a position at all — as distinct from whether it is allowed to. */
    fun hasLocationCapability(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)

    /** True when location is switched on in the device's own settings. */
    fun isLocationEnabled(): Boolean {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** Asks for a fresh location fix, or null when none can be had. */
    suspend fun getCurrentLocation(highAccuracy: Boolean, allowCached: Boolean): Coordinates? =
        getCurrentFix(highAccuracy, allowCached)?.coordinates

    /** The same fix as [getCurrentLocation], with the device's own estimate of how good it is. */
    // The permission is checked on the first line; lint cannot follow a check made through a helper.
    @SuppressLint("MissingPermission")
    suspend fun getCurrentFix(
        highAccuracy: Boolean,
        allowCached: Boolean,
        timeoutMillis: Long = FIX_TIMEOUT_MILLIS,
        maxCacheAgeMillis: Long = Long.MAX_VALUE
    ): LocationFix? {
        if (!hasLocationPermission()) return null

        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val cancellation = CancellationTokenSource()

        return try {
            val fresh = withTimeoutOrNull(timeoutMillis) {
                fusedClient.getCurrentLocation(priority, cancellation.token).await()
            }
            val location = fresh
                ?: if (allowCached) fusedClient.lastLocation.await()?.takeIf {
                    isWithinAge(it, maxCacheAgeMillis)
                } else {
                    null
                }
            location?.let {
                LocationFix(
                    coordinates = Coordinates(it.latitude, it.longitude),
                    // hasAccuracy() first: on a fix without one, getAccuracy returns 0.0.
                    accuracyMetres = if (it.hasAccuracy()) it.accuracy else null
                )
            }
        } catch (e: CancellationException) {
            // Never swallowed — see SyncManager.runSyncCycle for why.
            throw e
        } catch (e: Exception) {
            // SecurityException if permission vanished between the check and the call.
            null
        } finally {
            cancellation.cancel()
        }
    }

    /** The last position the device happened to record, or null when there is none to be had. */
    // The permission is checked on the first line; lint cannot follow a check made through a helper.
    @SuppressLint("MissingPermission")
    suspend fun getLastKnownLocation(): Coordinates? {
        if (!hasLocationPermission()) return null

        return try {
            fusedClient.lastLocation.await()?.let { Coordinates(it.latitude, it.longitude) }
        } catch (e: CancellationException) {
            // Never swallowed — see SyncManager.runSyncCycle for why.
            throw e
        } catch (e: Exception) {
            // As in getCurrentLocation: a SecurityException if the permission vanished between the check and the call.
            null
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    /** Whether a cached fix is recent enough to stand in for a fresh one. */
    private fun isWithinAge(location: Location, maxAgeMillis: Long): Boolean {
        if (maxAgeMillis == Long.MAX_VALUE) return true

        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return ageMillis <= maxAgeMillis
    }

    private companion object {
        /** How long to wait for a fresh fix. A cold GPS start outdoors typically takes a few seconds. */
        const val FIX_TIMEOUT_MILLIS = 15_000L
    }
}
