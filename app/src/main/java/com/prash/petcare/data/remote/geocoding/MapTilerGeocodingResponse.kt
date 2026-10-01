package com.prash.petcare.data.remote.geocoding

import com.google.gson.annotations.SerializedName
import com.prash.petcare.util.Coordinates
import com.prash.petcare.util.GeoDistance

/**
 * The reply to [MapTilerGeocodingApi.reverseGeocode] — a GeoJSON `FeatureCollection`.
 *
 * Dedicated classes rather than anything from the app's own model, because these describe *MapTiler's*
 * JSON and must be free to change with that API without dragging the rest of the app along. The
 * repository reads the one or two names it wants out of them and hands on a plain
 * [com.prash.petcare.data.location.ResolvedAddress], so nothing above the data layer ever sees a
 * network shape.
 *
 * **Every field is nullable on purpose.** Gson fills these by reflection and will happily leave a
 * property null when the key is absent from the reply — including one Kotlin declared non-null, which
 * then throws at some unrelated later line. Declaring what the network genuinely might not send as
 * nullable turns that crash into a value the code below already handles.
 *
 * The functions at the bottom of this file are the interesting part: they are pure, Android-free, and
 * hold the whole "which of these names should the card show?" decision, which is what makes that
 * decision unit-testable without a device or a network (see MapTilerGeocodingResponseTest).
 */
data class MapTilerGeocodingResponse(
    @SerializedName("features") val features: List<GeocodedFeature>? = null
)

/**
 * One named thing MapTiler found: a hospital, a street, a neighbourhood, a city.
 *
 * @property id `"poi.123"`, `"municipality.46425"` — the part before the dot names the *kind* of
 *   feature, which is how [kind] works out what it is looking at.
 * @property text The feature's own name alone: "Patan Hospital".
 * @property placeName The same name with its context appended: "Patan Hospital, Lagankhel, Lalitpur".
 *   Used only as a last resort, when no field below could be classified.
 * @property placeTypes What kind of feature this is, as MapTiler's own list — `["poi"]`, `["street"]`.
 * @property center The feature's representative point, `[longitude, latitude]`.
 * @property geometry The same point in GeoJSON form. Either one may be the one that arrives, so
 *   [coordinates] reads whichever is present.
 * @property context The broader features containing this one, narrowest first — the neighbourhood,
 *   then the city, then the region, then the country.
 */
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

/**
 * One entry in a feature's `context`: a broader place that contains it.
 *
 * Carries an [id] of the same shape as a feature's, so the same [kind] rules apply to both and the
 * repository can treat "the feature itself" and "the places around it" as one list of candidates.
 */
data class GeocodedContext(
    @SerializedName("id") val id: String? = null,
    @SerializedName("text") val text: String? = null
)

// ---------------------------------------------------------------------------------------
// Reading an answer out of a reply — pure functions, no Android, no network
// ---------------------------------------------------------------------------------------

/**
 * Where this feature is, or null when MapTiler sent no usable point.
 *
 * **GeoJSON writes longitude first**, which is the opposite of the order this app uses everywhere
 * else, and getting it the wrong way round is the kind of mistake that still produces a plausible
 * answer: 85° N, 27° E is in the Arctic Ocean rather than in Lalitpur, so a transposed pair would
 * simply never match a nearby POI and the feature would silently stop working. Unpacked here, once.
 */
val GeocodedFeature.coordinates: Coordinates?
    get() {
        val point = center?.takeIf { it.size >= 2 } ?: geometry?.coordinates?.takeIf { it.size >= 2 }
            ?: return null
        return Coordinates(latitude = point[1], longitude = point[0])
    }

/**
 * What kind of thing this is — "poi", "street", "municipality" — in lower case, or null if unknown.
 *
 * `place_type` is asked first because it is the field that means this; the prefix of the id is read
 * only as a fallback, for the context entries, which carry an id but no `place_type` of their own.
 */
val GeocodedFeature.kind: String?
    get() = (placeTypes?.firstOrNull() ?: id?.substringBefore('.'))
        ?.lowercase()
        ?.takeIf { it.isNotBlank() }

/** The same question for a context entry, which only ever has its id to go on. */
val GeocodedContext.kind: String?
    get() = id?.substringBefore('.')?.lowercase()?.takeIf { it.isNotBlank() }

/**
 * The name of the closest point of interest to [at], but **only** if it is genuinely at that point.
 *
 * This is the guard the whole feature turns on. MapTiler's nearest-neighbour search returns POIs
 * within roughly a kilometre, which at city scale is a different neighbourhood: labelling the pin with
 * the nearest of those would mean a user marking their own front door saves it as "Patan Hospital".
 * So the reply is treated as a list of *candidates*, every one is measured against the pin, and the
 * closest is accepted only when it is inside [maxMetres] — tens of metres, the width of a building
 * and its yard. Outside that, this returns null and the caller falls back to the address.
 *
 * Features with no coordinates and features with no real name are dropped rather than guessed at:
 * neither can be measured or shown. The second of those is not hypothetical — MapTiler returns plenty
 * of genuinely unnamed POIs with a `text` of `"-"`, and a card reading "-" over a pair of coordinates
 * would look like the app had broken rather than like the bench it actually is. See [asPlaceName].
 *
 * @param at The coordinate under the centre pin.
 * @param maxMetres How close a POI has to be to be called the selected place.
 */
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

/**
 * Every administrative name in this reply, ordered **narrowest first**.
 *
 * This is the fallback chain the card and the address box read from, in one list: an address, then the
 * street it is on, then the neighbourhood, then the city, then the region and country. The caller takes
 * the first as the headline and the next couple as the detail line underneath — so "Lagankhel" ends up
 * above "Lalitpur" rather than the other way around, which is the whole point. A reply that knows only
 * the city yields a single-element list, and the card shows the city with nothing under it.
 *
 * **Why the order is computed rather than taken as given.** The reply's own order is MapTiler's to
 * change, and `context` entries are not guaranteed to arrive narrowest-first. Ranking each name by
 * what *kind* of place it is makes the result independent of that — and [kindRank] is sorted on with a
 * stable sort, so two names of the same kind keep the order they arrived in.
 *
 * Duplicates are dropped case-insensitively, because the same word really does arrive twice: a city
 * that is also its own district would otherwise read "Lalitpur, Lalitpur".
 *
 * @param excluding A name already being shown as the headline — the POI's — so the address line
 *   underneath it does not repeat it.
 */
fun MapTilerGeocodingResponse.placeNamesByScale(excluding: String? = null): List<String> =
    namesByScale(features.orEmpty().flatMap { it.candidateNames() }, excluding)

/**
 * The same list for **one** feature: its own name and the places containing it, narrowest first.
 *
 * What a search result's second line is built from. A reply to a search holds several unrelated
 * features — Patan Hospital, then an animal hospital in Thailand — so reading the whole collection
 * the way [placeNamesByScale] does would mix one result's city into another's address. Each row has
 * to be described from its own feature alone.
 *
 * @param excluding The row's headline, so a feature that is its own narrowest name — a neighbourhood
 *   called "Jawalakhel" — does not repeat it on the line below.
 */
fun GeocodedFeature.namesByScale(excluding: String? = null): List<String> =
    namesByScale(candidateNames(), excluding)

/** A feature's own (kind, name) pair followed by one for each place containing it. */
private fun GeocodedFeature.candidateNames(): List<Pair<String?, String?>> =
    listOf(kind to text) + context.orEmpty().map { it.kind to it.text }

/**
 * The shared ranking: drop what is not a place name, order narrowest first, remove repeats.
 *
 * Private, and taking a flat list of (kind, name) pairs rather than any MapTiler type, so the two
 * public functions above differ only in *which* features they gather from and cannot drift in how
 * they sort what they gathered.
 */
private fun namesByScale(
    candidates: List<Pair<String?, String?>>,
    excluding: String?
): List<String> =
    candidates
        .mapNotNull { (kind, text) ->
            val name = text.asPlaceName() ?: return@mapNotNull null
            if (kind != null && kind in IGNORED_KINDS) return@mapNotNull null
            // A POI's own name is not an address, and this list is the address chain. It is handled
            // separately by nearestPoiNameWithin, where it can be distance-checked first.
            if (kind == MapTilerGeocodingApi.POI_TYPE) return@mapNotNull null
            if (excluding != null && name.equals(excluding, ignoreCase = true)) return@mapNotNull null
            kind to name
        }
        // Sorted before the duplicates are dropped, so when the same word arrives as two kinds the
        // narrower classification is the one that survives and the name keeps its place in the chain.
        .sortedBy { (kind, _) -> kindRank(kind) }
        .distinctBy { (_, name) -> name.lowercase() }
        .map { (_, name) -> name }

/**
 * The full "name, context, context" string MapTiler built for the first feature, or null.
 *
 * The very last fallback, for a reply whose features carry kinds this file has never heard of: a long
 * answer that includes the country is more than the card wants, but it beats showing the user nothing
 * above their coordinates. The same role `Geocoder`'s `getAddressLine(0)` plays on the platform side.
 */
fun MapTilerGeocodingResponse.fullPlaceName(): String? =
    features.orEmpty().firstNotNullOfOrNull { it.placeName?.trim()?.takeIf { n -> n.isNotEmpty() } }

/**
 * A name worth showing a person, or null.
 *
 * Trimmed, and required to contain at least one letter or digit. That last condition looks pedantic
 * and is not: MapTiler returns unnamed features — a bench, an unlabelled building, a gate — with a
 * `text` of `"-"`, and a card reading "-" above a pair of coordinates looks like a broken app rather
 * than like an honest "this has no name". Anything made only of punctuation is treated as the absence
 * of a name, which is what it is.
 */
private fun String?.asPlaceName(): String? =
    this?.trim()?.takeIf { it.isNotEmpty() && it.any(Char::isLetterOrDigit) }

/**
 * How broad a kind of place is: smaller is narrower, and narrower is shown first.
 *
 * The groups rather than the individual words are what matter — MapTiler names the same rung of the
 * hierarchy differently in different countries ("municipality" in Nepal, "city" or "place" elsewhere)
 * — so each rung lists every spelling that can arrive on it and they all rank the same. Names sharing
 * a rung keep the order they arrived in, since the sort is stable, and that is a deliberate division
 * of labour rather than an accident: the ranks fix the coarse order, and MapTiler's own narrowest-first
 * ordering of `context` settles the ties inside a rung without this file having to know which of
 * "place" and "municipality" is the smaller in any particular country.
 *
 * Anything unrecognised sorts to the end, where it can still be shown if nothing better came back but
 * can never displace a name that was understood.
 */
private fun kindRank(kind: String?): Int {
    val index = KIND_ORDER.indexOfFirst { group -> kind != null && kind in group }
    return if (index >= 0) index else KIND_ORDER.size
}

/**
 * The rungs of the place hierarchy, narrowest first — see [kindRank].
 *
 * **Why the ward sits below the city rather than above it.** `municipal_district` is genuinely the
 * narrower of the two — in Lalitpur it arrives as "Lalitpur-05" — but it is usually a *number*, and a
 * number is a reference rather than a place: "Jawalakhel Chowk, Lalitpur Metropolitan City" tells the
 * user where their vet is and "Jawalakhel Chowk, Lalitpur-05" does not. It stays in the list, because
 * in some countries this rung carries a real name, but it ranks below the settlement that contains it
 * so it only surfaces when the city is missing.
 */
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

/**
 * Kinds that are never a name for *this* place.
 *
 * A postcode is the clear case: a perfectly valid feature and a useless headline — "44600" above a
 * pair of coordinates tells the user nothing about where their vet is. The rest are the outermost
 * rungs MapTiler attaches to every point on Earth: a reverse lookup in Lalitpur really does come back
 * carrying "Himalayas" and "Asia", and both are true, enormous, and no help at all in finding a
 * groomer. Excluded rather than merely ranked last, so they can never be the one name on the card.
 */
private val IGNORED_KINDS = setOf(
    "postal_code",
    "postcode",
    "major_landform",
    "continental_marine",
    "marine_area",
    "joint_municipality"
)

private const val METRES_PER_KM = 1_000.0
