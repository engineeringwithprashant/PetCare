package com.prash.petcare.data.remote.geocoding

import com.google.gson.annotations.SerializedName
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.GeoDistance

/** The reply to [MapTilerGeocodingApi.reverseGeocode] — a GeoJSON `FeatureCollection`. */
data class MapTilerGeocodingResponse(
    @SerializedName("features") val features: List<GeocodedFeature>? = null
)

/** One named thing MapTiler found: a hospital, a street, a neighbourhood, a city. */
data class GeocodedFeature(
    @SerializedName("id") val id: String? = null,
    @SerializedName("text") val text: String? = null,
    @SerializedName("place_name") val placeName: String? = null,
    @SerializedName("place_type") val placeTypes: List<String>? = null,
    @SerializedName("center") val center: List<Double>? = null,
    @SerializedName("geometry") val geometry: GeocodedGeometry? = null,
    @SerializedName("context") val context: List<GeocodedContext>? = null
)

/** The `geometry` block, kept only for the coordinates inside it. */
data class GeocodedGeometry(
    @SerializedName("coordinates") val coordinates: List<Double>? = null
)

/** One entry in a feature's `context`: a broader place that contains it. */
data class GeocodedContext(
    @SerializedName("id") val id: String? = null,
    @SerializedName("text") val text: String? = null
)

// Reading an answer out of a reply — pure functions, no Android, no network.

/** Where this feature is, or null when MapTiler sent no usable point. */
val GeocodedFeature.coordinates: Coordinates?
    get() {
        val point = center?.takeIf { it.size >= 2 } ?: geometry?.coordinates?.takeIf { it.size >= 2 }
            ?: return null
        return Coordinates(latitude = point[1], longitude = point[0])
    }

/** What kind of thing this is — "poi", "street", "municipality" — in lower case, or null if unknown. */
val GeocodedFeature.kind: String?
    get() = (placeTypes?.firstOrNull() ?: id?.substringBefore('.'))
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

/** The same question for a context entry, which only ever has its id to go on. */
val GeocodedContext.kind: String?
    get() = id?.substringBefore('.')?.lowercase()?.takeIf { it.isNotBlank() }

/** The name of the closest point of interest to [at], but only if it is genuinely at that point. */
fun MapTilerGeocodingResponse.nearestPoiNameWithin(at: Coordinates, maxMetres: Double): String? =
    features
        .orEmpty()
        .mapNotNull { feature ->
            val name = feature.text.asPlaceName() ?: return@mapNotNull null
            val point = feature.coordinates ?: return@mapNotNull null
            name to GeoDistance.distanceKm(at, point) * METRES_PER_KM
        }
        .filter { (_, metres) -> metres <= maxMetres }
        .minByOrNull { (_, metres) -> metres }
        ?.first

/** Every administrative name in this reply, ordered narrowest first. */
fun MapTilerGeocodingResponse.placeNamesByScale(excluding: String? = null): List<String> =
    namesByScale(features.orEmpty().flatMap { it.candidateNames() }, excluding)

/** The same list for one feature: its own name and the places containing it, narrowest first. */
fun GeocodedFeature.namesByScale(excluding: String? = null): List<String> =
    namesByScale(candidateNames(), excluding)

/** A feature's own (kind, name) pair followed by one for each place containing it. */
private fun GeocodedFeature.candidateNames(): List<Pair<String?, String?>> =
    listOf(kind to text) + context.orEmpty().map { it.kind to it.text }

/** The shared ranking: drop what is not a place name, order narrowest first, remove repeats. */
private fun namesByScale(
    candidates: List<Pair<String?, String?>>,
    excluding: String?
): List<String> =
    candidates
        .mapNotNull { (kind, text) ->
            val name = text.asPlaceName() ?: return@mapNotNull null
            if (kind != null && kind in IGNORED_KINDS) return@mapNotNull null
            // A POI's own name is not an address, and this list is the address chain.
            if (kind == MapTilerGeocodingApi.POI_TYPE) return@mapNotNull null
            if (excluding != null && name.equals(excluding, ignoreCase = true)) return@mapNotNull null
            kind to name
        }
        // Sorted before the duplicates are dropped.
        .sortedBy { (kind, _) -> kindRank(kind) }
        .distinctBy { (_, name) -> name.lowercase() }
        .map { (_, name) -> name }

/** The full "name, context, context" string MapTiler built for the first feature, or null. */
fun MapTilerGeocodingResponse.fullPlaceName(): String? =
    features.orEmpty().firstNotNullOfOrNull { it.placeName?.trim()?.takeIf { n -> n.isNotEmpty() } }

/** A name worth showing a person, or null. */
private fun String?.asPlaceName(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }

/** How broad a kind of place is: smaller is narrower, and narrower is shown first. */
private fun kindRank(kind: String?): Int {
    val index = KIND_ORDER.indexOfFirst { group -> kind != null && kind in group }
    return if (index >= 0) index else KIND_ORDER.size
}

/** The rungs of the place hierarchy, narrowest first — see [kindRank]. */
private val KIND_ORDER: List<Set<String>> = listOf(
    setOf("address", "poi_address", "housenumber"),
    setOf("street", "road"),
    setOf("neighbourhood", "neighborhood", "suburb", "quarter", "subdistrict", "subplace"),
    setOf("locality", "place", "village", "town", "city", "municipality"),
    setOf("municipal_district", "borough", "city_district"),
    setOf("district", "county", "subregion"),
    setOf("region", "state", "province"),
    setOf("country")
)

/** Kinds that are never a name for this place. */
private val IGNORED_KINDS = setOf(
    "postal_code",
    "postcode",
    "major_landform",
    "continental_marine",
    "marine_area",
    "joint_municipality"
)

private const val METRES_PER_KM = 1_000.0
