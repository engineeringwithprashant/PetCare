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

/**
 * A location fix: where the phone is, and how sure the device is about it.
 *
 * [Coordinates] alone is enough for most of the app — a distance to a vet does not change if the fix
 * is ten metres out — so it stays the ordinary currency and this type is only used where the *quality*
 * of the fix is shown to the user. The Add a place screen's "I'm here" card prints "±8 m accuracy",
 * which is what lets somebody judge whether the position is worth saving or whether they should step
 * outside first.
 *
 * @property accuracyMetres The radius the device believes the true position lies within, at about 68%
 *   confidence — the figure `Location.getAccuracy` reports. Null when the platform gave none, which
 *   is possible for a fix derived from cell towers alone.
 */
data class LocationFix(val coordinates: Coordinates, val accuracyMetres: Float?)

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
     * True when the device can produce a position at all — as distinct from whether it is allowed to.
     *
     * **Why the two questions are separate.** A refused permission is something the user can change,
     * and a screen that hits one should offer the way to grant it. A device with no location hardware
     * and no network positioning — a display unit, a stripped-down emulator image, a tablet with the
     * feature absent — can never answer, and offering "try again" there would be a button that does
     * nothing forever. The Add a place screen tells the two apart before it asks; see
     * [com.prash.petcare.ui.place.AddPlaceViewModel.locate].
     *
     * The `location` feature covers any provider the platform recognises, not GPS specifically, which
     * is the right test: a fix from Wi-Fi is still a fix.
     */
    fun hasLocationCapability(): Boolean =
        appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)

    /**
     * True when location is switched on in the device's own settings.
     *
     * **The third question, and the one that used to be missed.** Capability asks whether the hardware
     * exists and the permission asks whether this app may use it; this asks whether the *user* has
     * turned location on at all. A phone with the master switch off has every capability and may hold
     * every permission, and will still never produce a fix — so without this test the screen can only
     * report "couldn't get your location", which invites the user to walk outside and try again when
     * what they need is one tap in quick settings. See [com.prash.petcare.ui.place.PlaceLocationPicker].
     *
     * `LocationManagerCompat` rather than the platform call, because `isLocationEnabled` only exists
     * from Android 9 and the compatibility version falls back to reading the settings provider below
     * that — which the app's minimum of API 24 still has to cope with.
     */
    fun isLocationEnabled(): Boolean {
        val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

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
    suspend fun getCurrentLocation(highAccuracy: Boolean, allowCached: Boolean): Coordinates? =
        getCurrentFix(highAccuracy, allowCached)?.coordinates

    /**
     * The same fix as [getCurrentLocation], with the device's own estimate of how good it is.
     *
     * Everything said above about freshness, timeouts and null being an ordinary answer applies here
     * unchanged — [getCurrentLocation] is this method with the accuracy dropped. Two entry points
     * rather than one because only the Add a place screen has any use for the accuracy figure, and
     * every other caller would otherwise have to unwrap a value it does not care about.
     *
     * @param timeoutMillis How long to wait for a *fresh* fix before falling back. Defaults to
     *   [FIX_TIMEOUT_MILLIS], which suits measuring distances; the Add a place screen shortens it,
     *   because there a spinner is the only thing between the user and a save.
     * @param maxCacheAgeMillis How old a cached fix may be and still be accepted when [allowCached]
     *   is true. Unbounded by default, since for a distance on a list any recent position will do.
     *   A caller about to *store* the position narrows it: the Add a place screen allows thirty
     *   seconds, which is roughly how long somebody stays put while opening a screen, and refuses
     *   anything older rather than geotagging a clinic at the bus stop the user walked from.
     */
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
                    // hasAccuracy() first: on a fix without one, getAccuracy returns 0.0, which would
                    // be printed as "±0 m" — a claim of perfect precision where there is none.
                    accuracyMetres = if (it.hasAccuracy()) it.accuracy else null
                )
            }
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

    /**
     * Whether a cached fix is recent enough to stand in for a fresh one.
     *
     * Measured with [Location.getElapsedRealtimeNanos] — nanoseconds since the device last booted —
     * rather than with [Location.getTime], which is wall-clock and therefore moves when the user or
     * the network changes the clock. A fix "from the future" or one suddenly an hour old because a
     * time zone changed would be a judgement about the clock, not about the position; the monotonic
     * counter cannot be adjusted and so answers the question actually being asked.
     *
     * An unbounded limit short-circuits, both to skip the arithmetic and to keep the common case —
     * any recent position will do — obviously free.
     */
    private fun isWithinAge(location: Location, maxAgeMillis: Long): Boolean {
        if (maxAgeMillis == Long.MAX_VALUE) return true

        val ageMillis = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000
        return ageMillis <= maxAgeMillis
    }

    private companion object {
        /**
         * How long to wait for a fresh fix. A cold GPS start outdoors typically takes a few seconds;
         * indoors it may never come, and a spinner that never ends is worse than a clear "couldn't
         * get your location — try again by a window".
         */
        const val FIX_TIMEOUT_MILLIS = 15_000L
    }
}
