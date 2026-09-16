package com.prash.petcare.data.repository

import com.prash.petcare.data.local.WeatherCache
import com.prash.petcare.data.location.LocationRepository
import com.prash.petcare.data.remote.weather.WeatherApi
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.WalkComfort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * What the home screen's walk-window card knows about the weather at any moment.
 *
 * Three states rather than a nullable temperature and a boolean, because "we have no reading yet"
 * and "we tried and there is none to be had" call for completely different things on screen — a
 * placeholder in the first case, and a fall back to the next walk's time in the second. A nullable
 * value cannot tell those apart, and the card would flicker through the wrong one on every launch.
 */
sealed interface WeatherState {

    /**
     * No reading is available yet and a fetch is under way.
     *
     * Only ever seen on a device that has never fetched successfully: once anything is cached, a
     * refresh shows that value throughout rather than dropping back to this — which is why the card
     * does not blank out every time the screen is opened.
     */
    data object Loading : WeatherState

    /**
     * A temperature to show. It may have come from the network moments ago or from the cache while
     * the device is offline; the card draws both the same way, because a reading is a reading.
     *
     * @property temperatureCelsius The reading, unrounded — the card rounds it for display.
     * @property comfort Whether this suits a walk. Decided here rather than in the fragment: it is a
     *   rule about the data (see [WalkComfort]), and the View layer's job is only to pick the words.
     */
    data class Available(
        val temperatureCelsius: Double,
        val comfort: WalkComfort
    ) : WeatherState

    /**
     * The fetch failed and nothing is cached, so there is no honest temperature to print. The card
     * falls back to reporting the next scheduled walk instead — see HomeFragment.renderWalkWindow.
     */
    data object Unavailable : WeatherState
}

/**
 * Supplies the current temperature for the home screen's walk-window card.
 *
 * In the MVVM chain this is the **Repository**, and it is the only layer that knows the temperature
 * has three possible origins. [HomeViewModel][com.prash.petcare.ui.home.HomeViewModel] asks it for a
 * state and gets one; it never sees a network call, a SharedPreferences file, or a coordinate.
 *
 * **What it decides, and why that belongs here rather than in the ViewModel.** Three data sources
 * have to be arbitrated:
 *
 * - **the cache**, which answers instantly and may be good enough;
 * - **the device's location**, which says *where* to ask about;
 * - **the network**, which is slow and often absent.
 *
 * Choosing between them — serve the cache when it is fresh, fall back to Kathmandu when there is no
 * fix, keep the old reading rather than blanking the card when the request fails — is precisely the
 * "where does this data come from" question a repository exists to answer. A ViewModel doing it
 * would have to hold all three dependencies and would stop being testable with a fake.
 *
 * **Why a single shared instance.** It is created once by
 * [PetCareApplication][com.prash.petcare.PetCareApplication], so its [state] outlives any one
 * fragment. Switching to the Places tab and back therefore redraws the temperature immediately from
 * memory, with no second request, and a rotation costs nothing at all.
 *
 * @param weatherApi The Open-Meteo endpoint.
 * @param cache The last successful reading, kept across launches.
 * @param locationRepository Where to ask about. Already a repository of its own, so location
 *   permission handling and the fused client stay in exactly one place.
 */
class WeatherRepository(
    private val weatherApi: WeatherApi,
    private val cache: WeatherCache,
    private val locationRepository: LocationRepository
) {

    private val _state = MutableStateFlow<WeatherState>(WeatherState.Loading)

    /**
     * The current state, as a hot stream the ViewModel passes through to the screen.
     *
     * `asStateFlow()` hands out a read-only view: the mutable flow above stays private, so nothing
     * outside this class can publish a temperature the repository did not fetch.
     */
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    /**
     * Serialises refreshes.
     *
     * [refresh] is called whenever the home tab comes into view and again the moment location
     * permission is granted, so two calls can genuinely overlap. Without this lock both would see an
     * empty cache, both would fetch, and the card could settle on whichever reply happened to arrive
     * second. A coroutine [Mutex] suspends the waiting caller rather than blocking its thread.
     */
    private val refreshMutex = Mutex()

    /**
     * Brings [state] up to date, fetching only if the cached reading has gone stale.
     *
     * The order of the steps is the whole design, and each one leaves the card showing the best
     * value known so far:
     *
     * 1. **Publish the cache**, whatever its age. From here on the card has something to draw, so
     *    nothing below can ever blank it.
     * 2. **Stop if that reading is still fresh.** Inside [CACHE_MAX_AGE_MILLIS] the stored value is
     *    the answer, and no request is made at all — which is what stops the app calling a free
     *    public API every time the user glances at the home tab.
     * 3. **Find out where we are**, falling back to [KATHMANDU] when there is no fix.
     * 4. **Fetch, and keep the old reading if that fails.** A failure is expected rather than
     *    exceptional — a phone is offline all the time — so it downgrades to whatever was already
     *    on screen, and only reports [WeatherState.Unavailable] when there is nothing to keep.
     *
     * Safe to call as often as a caller likes; steps 1 and 2 make the common case a memory read.
     */
    suspend fun refresh() {
        refreshMutex.withLock {
            // Dispatchers.IO because all three of the calls below wait on something outside the
            // process — a preferences file, the location client, a socket — and none of that has any
            // business happening on the thread that is drawing the dashboard.
            withContext(Dispatchers.IO) {
                val cached = cache.read()
                if (cached != null) {
                    _state.value = available(cached.temperatureCelsius)
                }

                val now = System.currentTimeMillis()
                // A timestamp in the future means the device's clock has been moved back since the
                // reading was stored. Treating the age as "not yet elapsed" would pin the card to a
                // stale value indefinitely, so an impossible age counts as stale and refetches.
                val ageMillis = now - (cached?.fetchedAtMillis ?: 0L)
                if (cached != null && ageMillis in 0 until CACHE_MAX_AGE_MILLIS) return@withContext

                if (cached == null) _state.value = WeatherState.Loading

                val at = locationRepository.getLastKnownLocation() ?: KATHMANDU

                _state.value = try {
                    val temperature = weatherApi
                        .currentWeather(latitude = at.latitude, longitude = at.longitude)
                        .current
                        ?.temperatureCelsius

                    if (temperature == null) {
                        // A 200 reply that carried no reading. Nothing to store, and nothing gained
                        // by pretending otherwise.
                        fallback(cached?.temperatureCelsius)
                    } else {
                        cache.write(temperature, now)
                        available(temperature)
                    }
                } catch (e: CancellationException) {
                    // Never swallowed: a CancellationException is the coroutine machinery telling
                    // this work to stop, and catching it would leave the scope believing a cancelled
                    // job is still running. Rethrown as the rest of the app's data layer does.
                    throw e
                } catch (e: Exception) {
                    // An IOException when offline or timed out, an HttpException on a 4xx/5xx, or a
                    // parse error from an unexpected body. The card's answer is the same for all of
                    // them — show what we had — so they are handled together rather than repeating
                    // one branch three times.
                    fallback(cached?.temperatureCelsius)
                }
            }
        }
    }

    /** A reading, with the walking verdict that goes with it. */
    private fun available(temperatureCelsius: Double): WeatherState.Available =
        WeatherState.Available(
            temperatureCelsius = temperatureCelsius,
            comfort = WalkComfort.at(temperatureCelsius)
        )

    /** What to show when a fetch produced nothing: the previous reading, or an admission. */
    private fun fallback(cachedTemperature: Double?): WeatherState =
        cachedTemperature?.let { available(it) } ?: WeatherState.Unavailable

    companion object {

        /**
         * How long a stored reading is treated as current.
         *
         * Thirty minutes is a deliberate compromise. Air temperature moves slowly enough that a
         * half-hour-old figure is still honest to the degree the card prints, and Open-Meteo itself
         * only updates on a fifteen-minute cadence, so asking more often would mostly return the
         * same number. It also keeps the app comfortably within the free tier's fair use — a user
         * who opens the home tab twenty times a day causes at most a couple of dozen requests.
         */
        const val CACHE_MAX_AGE_MILLIS = 30 * 60 * 1000L

        /**
         * Where to report on when the device's position is unknown.
         *
         * Kathmandu, because that is where the app is being built and used, and a card showing a
         * plausible local temperature is far better than one showing a dash. It is reached whenever
         * location permission was refused or the device has no stored fix — a fresh install that has
         * not used maps yet, most often — so this is a normal path rather than an error case.
         */
        val KATHMANDU = Coordinates(latitude = 27.7172, longitude = 85.3240)
    }
}
