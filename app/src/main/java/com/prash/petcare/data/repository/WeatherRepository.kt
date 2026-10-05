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

/** What the home screen's walk-window card knows about the weather at any moment. */
sealed interface WeatherState {

    /** No reading is available yet and a fetch is under way. */
    data object Loading : WeatherState

    /** A temperature to show. It may have come from the network moments ago or from the cache. */
    data class Available(
        val temperatureCelsius: Double,
        val comfort: WalkComfort
    ) : WeatherState

    /** The fetch failed and nothing is cached, so there is no honest temperature to print. */
    data object Unavailable : WeatherState
}

/** Supplies the current temperature for the home screen's walk-window card. */
class WeatherRepository(
    private val weatherApi: WeatherApi,
    private val cache: WeatherCache,
    private val locationRepository: LocationRepository
) {

    private val _state = MutableStateFlow<WeatherState>(WeatherState.Loading)

    /** The current state, as a hot stream the ViewModel passes through to the screen. */
    val state: StateFlow<WeatherState> = _state.asStateFlow()

    /** Serialises refreshes. */
    private val refreshMutex = Mutex()

    /** Brings [state] up to date, fetching only if the cached reading has gone stale. */
    suspend fun refresh() {
        refreshMutex.withLock {
            // Dispatchers.IO because all three of the calls below wait on something outside the process.
            withContext(Dispatchers.IO) {
                val cached = cache.read()
                if (cached != null) {
                    _state.value = available(cached.temperatureCelsius)
                }

                val now = System.currentTimeMillis()
                // A timestamp in the future means the device's clock has been moved back.
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
                        // A 200 reply that carried no reading. Nothing to store.
                        fallback(cached?.temperatureCelsius)
                    } else {
                        cache.write(temperature, now)
                        available(temperature)
                    }
                } catch (e: CancellationException) {
                    // Never swallowed: a CancellationException is the coroutine machinery telling this work to stop.
                    throw e
                } catch (e: Exception) {
                    // An IOException when offline or timed out, an HttpException on a 4xx/5xx.
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

        /** How long a stored reading is treated as current. */
        const val CACHE_MAX_AGE_MILLIS = 30 * 60 * 1000L

        /** Where to report on when the device's position is unknown. */
        val KATHMANDU = Coordinates(latitude = 27.7172, longitude = 85.3240)
    }
}
