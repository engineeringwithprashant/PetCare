package com.prash.petcare.data.location

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.prash.petcare.data.remote.geocoding.GeocodedFeature
import com.prash.petcare.data.remote.geocoding.MapTilerGeocodingApi
import com.prash.petcare.data.remote.geocoding.MapTilerGeocodingResponse
import com.prash.petcare.data.remote.geocoding.coordinates
import com.prash.petcare.data.remote.geocoding.fullPlaceName
import com.prash.petcare.data.remote.geocoding.kind
import com.prash.petcare.data.remote.geocoding.namesByScale
import com.prash.petcare.data.remote.geocoding.nearestPoiNameWithin
import com.prash.petcare.data.remote.geocoding.placeNamesByScale
import com.prash.petcare.util.Coordinates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** A coordinate turned back into words. */
data class ResolvedAddress(
    val placeName: String?,
    val addressLine: String?,
    val isPointOfInterest: Boolean = false
) {

    /** True when the lookup produced nothing worth showing, so the caller can treat it as a miss. */
    val isEmpty: Boolean get() = placeName.isNullOrBlank() && addressLine.isNullOrBlank()
}

/** One place the user could mean, offered in answer to what they typed in the search field. */
data class PlaceSuggestion(
    val id: String,
    val name: String,
    val addressLine: String?,
    val coordinates: Coordinates,
    val isPointOfInterest: Boolean
)

/** Turns the coordinates under the map's pin into a place name — the app's third data source. */
class GeocodingRepository(
    context: Context,
    private val api: MapTilerGeocodingApi,
    private val apiKey: String
) {

    private val appContext = context.applicationContext

    /** True when this build has a MapTiler key, which is what the network lookup needs. */
    private val hasMapTilerKey: Boolean get() = apiKey.isNotBlank()

    /** True when some lookup is possible on this device and in this build. */
    fun isAvailable(): Boolean = hasMapTilerKey || Geocoder.isPresent()

    /** The name of the place at a coordinate, or null when there is none to be had. */
    suspend fun reverseGeocode(at: Coordinates): ResolvedAddress? {
        if (hasMapTilerKey) {
            val named = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) { lookUpWithMapTiler(at) }
            if (named != null && !named.isEmpty) return named
        }

        if (!Geocoder.isPresent()) return null

        val address = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) { lookUpWithPlatform(at) } ?: return null
        return describe(address).takeIf { !it.isEmpty }
    }

    // Searching by name.

    /** The places matching what the user typed, best match first, or an empty list. */
    suspend fun searchPlaces(query: String, near: Coordinates?): List<PlaceSuggestion> {
        if (!hasMapTilerKey) return emptyList()

        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) return emptyList()

        val response = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) {
            try {
                api.forwardGeocode(
                    query = trimmed,
                    key = apiKey,
                    limit = SEARCH_LIMIT,
                    types = MapTilerGeocodingApi.SEARCH_TYPES,
                    autocomplete = true,
                    proximity = near?.let { position(it) },
                    language = deviceLanguage()
                )
            } catch (e: CancellationException) {
                // Rethrown, not swallowed.
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: return emptyList()

        return response.features.orEmpty().mapNotNull(::describeSuggestion)
    }

    /** Turns one search result into a row the list can draw and the picker can select. */
    private fun describeSuggestion(feature: GeocodedFeature): PlaceSuggestion? {
        val name = feature.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val at = feature.coordinates ?: return null

        return PlaceSuggestion(
            // MapTiler always sends an id; the name and coordinate are a sound fallback identity for a row.
            id = feature.id ?: "$name@${at.latitude},${at.longitude}",
            name = name,
            addressLine = feature.namesByScale(excluding = name)
                .take(ADDRESS_LINE_PARTS)
                .joinLines(),
            coordinates = at,
            isPointOfInterest = feature.kind == MapTilerGeocodingApi.POI_TYPE
        )
    }

    // MapTiler: the source that can name a hospital.

    /** Asks MapTiler both questions about one point and merges the two answers. */
    private suspend fun lookUpWithMapTiler(at: Coordinates): ResolvedAddress = coroutineScope {
        val nearbyPois = async { query(at, limit = POI_LIMIT, types = MapTilerGeocodingApi.POI_TYPE) }
        val address = async { query(at, limit = ADDRESS_LIMIT, types = null) }

        merge(at, nearbyPois.await(), address.await())
    }

    /** One request, with a failure reported as null. */
    private suspend fun query(
        at: Coordinates,
        limit: Int,
        types: String?
    ): MapTilerGeocodingResponse? = try {
        api.reverseGeocode(
            position = position(at),
            key = apiKey,
            limit = limit,
            types = types,
            language = deviceLanguage()
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** Turns the two replies into the two lines the card shows. */
    private fun merge(
        at: Coordinates,
        nearbyPois: MapTilerGeocodingResponse?,
        address: MapTilerGeocodingResponse?
    ): ResolvedAddress {
        val poiName = nearbyPois?.nearestPoiNameWithin(at, POI_MATCH_METRES)
        val names = address?.placeNamesByScale(excluding = poiName).orEmpty()

        if (poiName != null) {
            return ResolvedAddress(
                placeName = poiName,
                addressLine = names.take(ADDRESS_LINE_PARTS).joinLines(),
                isPointOfInterest = true
            )
        }

        return ResolvedAddress(
            // The long "name, context, country" string only when not one name could be classified.
            placeName = names.firstOrNull() ?: address?.fullPlaceName(),
            addressLine = names.drop(1).take(DETAIL_LINE_PARTS).joinLines(),
            isPointOfInterest = false
        )
    }

    /** `"85.3157,27.6681"` — the path segment MapTiler's reverse endpoint takes. */
    private fun position(at: Coordinates): String =
        String.format(Locale.US, "%.6f,%.6f", at.longitude, at.latitude)

    /** The language to ask for names in, or null to let MapTiler choose. */
    private fun deviceLanguage(): String? =
        Locale.getDefault().language.takeIf { it.length == ISO_LANGUAGE_LENGTH }

    /** "Lagankhel, Lalitpur", or null when the list was empty and there is no line to draw. */
    private fun List<String>.joinLines(): String? =
        takeIf { it.isNotEmpty() }?.joinToString(", ")

    // The platform geocoder: the offline fallback.

    /** One address for a coordinate, by whichever of the platform's two APIs this device has. */
    @Suppress("DEPRECATION")
    private suspend fun lookUpWithPlatform(at: Coordinates): Address? {
        val geocoder = Geocoder(appContext, Locale.getDefault())

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                suspendCancellableCoroutine { continuation ->
                    geocoder.getFromLocation(
                        at.latitude,
                        at.longitude,
                        1,
                        object : Geocoder.GeocodeListener {
                            override fun onGeocode(addresses: MutableList<Address>) {
                                if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                            }

                            // A failure and an empty result mean the same thing to every caller: no name.
                            override fun onError(errorMessage: String?) {
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                    )
                }
            } else {
                withContext(Dispatchers.IO) {
                    geocoder.getFromLocation(at.latitude, at.longitude, 1)?.firstOrNull()
                }
            }
        } catch (e: CancellationException) {
            // Never swallowed: cancelling the lookup.
            throw e
        } catch (e: Exception) {
            // IOException when the geocoder is offline.
            null
        }
    }

    /** Picks the two lines worth showing out of everything the platform returned. */
    private fun describe(address: Address): ResolvedAddress {
        val name = joinDistinct(
            address.subLocality,
            address.locality ?: address.subAdminArea
        )

        val detail = joinDistinct(
            address.subAdminArea.takeIf { it != address.locality },
            address.adminArea,
            address.countryName
        )

        // With no named fields at all there is still usually a postal line.
        if (name == null) {
            return ResolvedAddress(
                placeName = address.getAddressLine(0)?.takeIf { it.isNotBlank() },
                addressLine = null
            )
        }

        return ResolvedAddress(placeName = name, addressLine = detail)
    }

    /** Joins the parts that are actually there, once each, as "Pulchowk, Lalitpur". */
    private fun joinDistinct(vararg parts: String?): String? = parts
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .distinct()
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

    /** Public only for [MIN_QUERY_LENGTH]: the view and the picker both need to know. */
    companion object {

        /** The shortest query worth sending: two characters. */
        const val MIN_QUERY_LENGTH = 2

        /** How long to wait for a name before deciding there is none. */
        private const val LOOKUP_TIMEOUT_MILLIS = 5_000L

        /** How close a POI has to be to the centre pin to be the selected place: 40 metres. */
        private const val POI_MATCH_METRES = 40.0

        /** Five nearby POIs, which is what makes the distance check meaningful. */
        private const val POI_LIMIT = 5

        /** One address. A limit above one would turn this request into a nearest-neighbour search too. */
        private const val ADDRESS_LIMIT = 1

        /** How many names go on the line under a matched POI: two — "Lagankhel, Lalitpur". */
        private const val ADDRESS_LINE_PARTS = 2

        /** And how many when the headline is itself an address: two more. */
        private const val DETAIL_LINE_PARTS = 2

        /** ISO 639-1 codes are two letters; anything else is not one — see [deviceLanguage]. */
        private const val ISO_LANGUAGE_LENGTH = 2

        /** How many suggestions the search field offers: five. */
        private const val SEARCH_LIMIT = 5
    }
}
