package com.prash.petcare.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.prash.petcare.util.Coordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the phone is right now — the app's second data source after Room.
 *
 * **Why a repository, when there is no database involved.** The project's rule is that ViewModels
 * reach data only through repositories and never touch Android framework types. The device's
 * location is data in exactly the same sense as a row in Room: something outside the ViewModel that
 * it asks for and waits on. Wrapping Google's location client here means the Places ViewModels see
 * a single suspend function returning plain [Coordinates], hold no `Context`, and could be tested
 * with a fake that returns a fixed point.
 *
 * **The fused location provider** (Google Play services) is used rather than the platform's
 * `LocationManager`. It blends GPS, Wi-Fi and cell signals and picks whichever gives the requested
 * accuracy for the least battery, and it copes with the fiddly parts — choosing a provider, waiting
 * for a first fix — that `LocationManager` leaves to the caller. This is the location half of Play
 * services only; the Maps SDK is not used anywhere in the app.
 *
 * **Permission is the screen's job, and checked again here.** Asking the user needs an Activity
 * and a rationale dialog, so the fragments do that. This class still checks before every call,
 * because the permission can be revoked from system settings while the app is in the background, and
 * calling the client without it throws a `SecurityException` rather than returning nothing.
 *
 * @param context Any context; only the application context is kept, so no screen can be leaked.
 */
class LocationRepository(context: Context) {

    private val appContext = context.applicationContext

    private val fusedClient = LocationServices.getFusedLocationProviderClient(appContext)

    /** True when the user has granted precise or approximate location access. */
    fun hasLocationPermission(): Boolean =
        isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** True only for *precise* access — what capturing a place's geotag really wants. */
    fun hasPreciseLocationPermission(): Boolean = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

    /**
     * Asks for a fresh location fix, or null when none can be had.
     *
     * Null is an ordinary answer rather than an error, and callers show a sentence rather than crash:
     * permission may have been withdrawn, location may be switched off in quick settings, or the
     * phone may be indoors with no fix to give.
     *
     * **Fresh first, cached as a fallback.** `getCurrentLocation` actively computes a new fix, which
     * is what a geotag must be — the last cached location could be from the other side of town an
     * hour ago. If that fails or times out, `lastLocation` is tried, since for measuring distances a
     * slightly stale position beats none at all; [allowCached] lets the "capture this place" flow opt
     * out of that, because a stale geotag would be saved permanently.
     *
     * The [CancellationTokenSource] is what lets the timeout actually stop the request: without it,
     * abandoning the wait would leave Play services searching for satellites in the background.
     *
     * @param highAccuracy True to insist on GPS-level accuracy (capturing a geotag); false to accept a
     *   Wi-Fi/cell estimate, which arrives faster and costs less battery (measuring distances).
     */
    // The permission is checked on the first line; lint cannot follow a check made through a helper.
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(highAccuracy: Boolean, allowCached: Boolean): Coordinates? {
        if (!hasLocationPermission()) return null

        val priority = if (highAccuracy) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val cancellation = CancellationTokenSource()

        return try {
            val fresh = withTimeoutOrNull(FIX_TIMEOUT_MILLIS) {
                fusedClient.getCurrentLocation(priority, cancellation.token).await()
            }
            val location = fresh ?: if (allowCached) fusedClient.lastLocation.await() else null
            location?.let { Coordinates(it.latitude, it.longitude) }
        } catch (e: CancellationException) {
            // Never swallowed — see SyncManager.runSyncCycle for why.
            throw e
        } catch (e: Exception) {
            // SecurityException if permission vanished between the check and the call, or an
            // ApiException if Play services is missing or out of date. Either way there is no
            // location to report, which is an answer the caller already handles.
            null
        } finally {
            cancellation.cancel()
        }
    }

    /**
     * The last position the device happened to record, or null when there is none to be had.
     *
     * **Why this exists beside [getCurrentLocation].** That method actively computes a new fix,
     * which is right for a geotag and for measuring distances, but it can take seconds and it wakes
     * the radios to do it. The weather card needs neither: a temperature is the same across a whole
     * city, so yesterday's cached position is every bit as good an answer as a fresh satellite lock,
     * and the card should appear at once rather than after a spinner. `lastLocation` returns
     * whatever fix any app on the phone obtained most recently, from memory, costing nothing.
     *
     * Null is an ordinary answer, not an error: permission may not have been granted, or the device
     * may have no stored fix at all — common on a fresh install. Callers are expected to have a
     * fallback, as [com.prash.petcare.data.repository.WeatherRepository] does.
     */
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
            // As in getCurrentLocation: a SecurityException if the permission vanished between the
            // check and the call, or an ApiException if Play services is missing or out of date.
            null
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        /**
         * How long to wait for a fresh fix. A cold GPS start outdoors typically takes a few seconds;
         * indoors it may never come, and a spinner that never ends is worse than a clear "couldn't
         * get your location — try again by a window".
         */
        const val FIX_TIMEOUT_MILLIS = 15_000L
    }
}
