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

/**
 * A coordinate turned back into words.
 *
 * Two lines rather than one string, because the screen shows them with different weight: the first is
 * the answer to "where is this?" and is read at a glance, the second is the detail underneath it. A
 * postal address line crammed into one field would be read as neither.
 *
 * @property placeName The headline. A *named place* when the pin is on one — "Patan Hospital" — and
 *   otherwise the narrowest address-like name available, in the order address → street →
 *   neighbourhood → locality. Null when nothing could be found, which is an ordinary answer: the
 *   coordinates are still shown, and a place with no name is still perfectly saveable.
 * @property addressLine The fuller context under it — "Lagankhel, Lalitpur". Never repeats
 *   [placeName]. Null when there is nothing to add that the name has not already said.
 * @property isPointOfInterest True when [placeName] is a real POI or business name rather than an
 *   address or an area. The forms read this and nothing else: a POI name is worth *suggesting* as the
 *   place's own name, because "Patan Hospital" is what the user would have typed, while "Lalitpur" is
 *   a city and would be a nonsense name for a saved vet.
 */
data class ResolvedAddress(
    val placeName: String?,
    val addressLine: String?,
    val isPointOfInterest: Boolean = false
) {

    /** True when the lookup produced nothing worth showing, so the caller can treat it as a miss. */
    val isEmpty: Boolean get() = placeName.isNullOrBlank() && addressLine.isNullOrBlank()
}

/**
 * One place the user could mean, offered in answer to what they typed in the search field.
 *
 * The mirror image of [ResolvedAddress]: that one answers "what is at this coordinate?", this one
 * answers "where is this name?" — and so it carries the coordinate as part of the answer rather than
 * as the question. Picking one of these *is* choosing a location, which is why nothing further has to
 * be looked up when a row is tapped: the name, the address and the point all arrived together and
 * belong to each other, in exactly the way [SelectedLocation] insists on.
 *
 * @property id MapTiler's own id for the feature, such as `poi.9372216`. Carried only so the results
 *   list can tell one row from another across updates — a search is re-run on every keystroke after
 *   the debounce, and DiffUtil needs a stable identity to avoid rebuilding rows that have not moved.
 * @property name What to show on the row and, for a POI, to offer as the place's name:
 *   "Patan Hospital".
 * @property addressLine Where that is — "Kumaripati, Lalitpur Metropolitan City" — or null for a
 *   result MapTiler gave no context for.
 * @property coordinates The point to put the pin on.
 * @property isPointOfInterest True when this is a named business or landmark rather than a street or
 *   an area; read for exactly one purpose, see [ResolvedAddress.isPointOfInterest].
 */
data class PlaceSuggestion(
    val id: String,
    val name: String,
    val addressLine: String?,
    val coordinates: Coordinates,
    val isPointOfInterest: Boolean
)

/**
 * Turns the coordinates under the map's pin into a place name — the app's third data source, after
 * Room and the device's own position.
 *
 * **Why a repository, when there is no database involved.** The project's rule is that ViewModels
 * reach data only through repositories and never touch Android framework types. An address looked up
 * from a coordinate is data in exactly that sense: something outside the ViewModel that it asks for
 * and waits on. Wrapping the lookup here means the Places ViewModels see one suspend function
 * returning a plain [ResolvedAddress], hold no `Context`, and could be tested with a fake that returns
 * a fixed name — the same arrangement [LocationRepository] uses for the device's position.
 *
 * ### Why a pin on a hospital used to say "Lalitpur"
 *
 * This class originally asked `android.location.Geocoder` and nothing else, and that API answers with
 * an *administrative address*: country, region, city, sub-locality, street. A hospital is none of
 * those. It is a **point of interest** — a named thing standing at a coordinate — and the platform
 * geocoder has no field for one and no way to be asked for one. So the nearest thing it could offer
 * for a pin in the middle of Patan Hospital's grounds was the locality, and the card dutifully
 * reported "Lalitpur": not a bug in the formatting, a question that was never asked.
 *
 * ### What it asks now: two lookups per point, and a distance check
 *
 * MapTiler's geocoding API *can* be asked, but only explicitly — POI results are off by default. So
 * every lookup now makes both requests, in parallel because neither depends on the other:
 *
 *  1. **"What named places are near here?"** — `types=poi` with a limit above one, which turns reverse
 *     geocoding into a nearest-neighbour search over POIs. See [MapTilerGeocodingApi].
 *  2. **"What is the address here?"** — the ordinary reverse lookup, which supplies the line under the
 *     name whichever way the first one goes.
 *
 * **The distance check is what makes this safe.** The POI search reaches about a kilometre, so its
 * closest hit is emphatically *not* "the place the pin is on" — at city scale it can be a different
 * neighbourhood. A candidate is therefore accepted only when it is within [POI_MATCH_METRES] of the
 * pin, which is the width of a building and its grounds. Beyond that it is ignored entirely and the
 * address answers instead, because a plausible but wrong business name is worse than an honest street:
 * the user would save their own front door as somebody else's clinic and nothing on screen would look
 * amiss.
 *
 * The result, for a pin on the hospital's grounds, is "Patan Hospital" over "Lagankhel, Lalitpur"; for
 * a pin in the road outside, "Lagankhel" over "Lalitpur"; and for a pin in the middle of a field, the
 * coordinates alone.
 *
 * ### Why the platform geocoder is still here
 *
 * As a fallback, for the two cases MapTiler cannot cover: a clone of the project with no
 * `MAPTILER_API_KEY` in `local.properties`, and a device that reaches its own geocoder but not the
 * open internet. It gives no POI names — that is the limitation this class exists to work around — but
 * a city name is better than a bare coordinate, and it costs nothing when MapTiler has already
 * answered.
 *
 * **Null is an ordinary answer, never an error.** The service can be offline, the key can be absent,
 * some devices ship no geocoder at all, and plenty of coordinates in the world have no name to give —
 * the middle of a field is still a perfectly good place to save a walk. Every caller is expected to
 * carry on with the latitude and longitude alone.
 *
 * @param context Any context; only the application context is kept, so no screen can be leaked.
 * @param api The MapTiler geocoding endpoint — the only data source that can name a business.
 * @param apiKey The MapTiler key, which may be blank in a build that has none; see [isAvailable].
 */
class GeocodingRepository(
    context: Context,
    private val api: MapTilerGeocodingApi,
    private val apiKey: String
) {

    private val appContext = context.applicationContext

    /** True when this build has a MapTiler key, which is what the network lookup needs. */
    private val hasMapTilerKey: Boolean get() = apiKey.isNotBlank()

    /**
     * True when *some* lookup is possible on this device and in this build.
     *
     * Checked before a lookup rather than discovered through a failure, so a screen can simply not
     * promise an address when neither source could ever produce one. Either source is enough: a build
     * with a key can name places over the network, and a device with a geocoder can at least name the
     * city offline.
     */
    fun isAvailable(): Boolean = hasMapTilerKey || Geocoder.isPresent()

    /**
     * The name of the place at a coordinate, or null when there is none to be had.
     *
     * MapTiler first, because it is the only one of the two that can answer "which business is this?".
     * The platform geocoder is tried only when that produced nothing at all — an absent key, a failed
     * request, or a genuinely unnamed point — and never merely to add to an answer that already exists,
     * since two sources describing one coordinate in two vocabularies would read as a contradiction.
     *
     * **The timeout is the point of the wrapper.** The lookup goes over the network, and the screen
     * calling it has already drawn the coordinates and is only waiting to put a name above them — so
     * an answer that never comes must turn into "no name" rather than into a line that says
     * "Finding address…" for the rest of the session.
     */
    suspend fun reverseGeocode(at: Coordinates): ResolvedAddress? {
        if (hasMapTilerKey) {
            val named = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) { lookUpWithMapTiler(at) }
            if (named != null && !named.isEmpty) return named
        }

        if (!Geocoder.isPresent()) return null

        val address = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) { lookUpWithPlatform(at) } ?: return null
        return describe(address).takeIf { !it.isEmpty }
    }

    // ---------------------------------------------------------------------------------------
    // Searching by name
    // ---------------------------------------------------------------------------------------

    /**
     * The places matching what the user typed, best match first, or an empty list.
     *
     * The other direction of the same service, and the third way a location can be chosen — beside
     * the device's position and the map's centre pin.
     *
     * **An empty list is the only kind of failure there is.** No key, a blank field, a query too short
     * to mean anything, no network, a rejected request, nothing matched: all of them are "no
     * suggestions", because a search field has exactly one way to say that and the user's next move is
     * the same in every case — keep typing, or move the map instead. There is deliberately no error
     * state for the list to show.
     *
     * **There is no platform fallback here**, unlike [reverseGeocode]. `Geocoder.getFromLocationName`
     * exists, but it has the same blind spot that caused this whole feature — it cannot return a
     * business — so for a field whose purpose is finding "Patan Hospital" by name it would be an
     * offline mode that never found what was being searched for. A clone with no key gets a field that
     * returns nothing, and the two other ways of choosing a point, both of which still work.
     *
     * @param query What is in the field. Trimmed here, and anything shorter than
     *   [MIN_QUERY_LENGTH] is declined rather than sent: one or two letters match a great deal of the
     *   planet, so the request would cost a round trip to produce a list nobody could use.
     * @param near Where to bias the results towards — the pin's own position, or wherever the map is
     *   looking. Null is accepted and means "no preference"; see [MapTilerGeocodingApi.forwardGeocode]
     *   for why passing it matters so much to a short query.
     */
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
                // Rethrown, not swallowed: the next keystroke cancelling this request must cancel the
                // coroutine waiting on it rather than be recorded as "nothing matched".
                throw e
            } catch (e: Exception) {
                null
            }
        } ?: return emptyList()

        return response.features.orEmpty().mapNotNull(::describeSuggestion)
    }

    /**
     * Turns one search result into a row the list can draw and the picker can select.
     *
     * Dropped entirely — rather than shown with something missing — when it has no name or no
     * coordinate, since a row the user cannot read is no use and a row that cannot move the map is
     * worse: it would look exactly like the others and do nothing.
     *
     * The second line comes from the feature's *own* context rather than from `place_name`, which is
     * a detail worth the extra step. MapTiler's `place_name` for a POI is only "Patan Hospital,
     * Nepal" — the country and nothing between — while the context underneath it holds the street,
     * the locality and the city. Reading the context also means a search result is described by
     * exactly the same rules as a dropped pin, so tapping a row and then nudging the map cannot change
     * how the same place is worded.
     */
    private fun describeSuggestion(feature: GeocodedFeature): PlaceSuggestion? {
        val name = feature.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val at = feature.coordinates ?: return null

        return PlaceSuggestion(
            // MapTiler always sends an id; the name and coordinate are a sound fallback identity for
            // a row, since two results at one point with one name would be one place anyway.
            id = feature.id ?: "$name@${at.latitude},${at.longitude}",
            name = name,
            addressLine = feature.namesByScale(excluding = name)
                .take(ADDRESS_LINE_PARTS)
                .joinLines(),
            coordinates = at,
            isPointOfInterest = feature.kind == MapTilerGeocodingApi.POI_TYPE
        )
    }

    // ---------------------------------------------------------------------------------------
    // MapTiler: the source that can name a hospital
    // ---------------------------------------------------------------------------------------

    /**
     * Asks MapTiler both questions about one point and merges the two answers.
     *
     * `coroutineScope` with two `async`s rather than one request after the other: they are independent,
     * so running them in sequence would double the wait for no benefit. The scope also ties their
     * lifetimes together — when the map moves on and the caller's job is cancelled, both requests go
     * with it rather than one being left to finish into a screen that has changed.
     */
    private suspend fun lookUpWithMapTiler(at: Coordinates): ResolvedAddress = coroutineScope {
        val nearbyPois = async { query(at, limit = POI_LIMIT, types = MapTilerGeocodingApi.POI_TYPE) }
        val address = async { query(at, limit = ADDRESS_LIMIT, types = null) }

        merge(at, nearbyPois.await(), address.await())
    }

    /**
     * One request, with a failure reported as null.
     *
     * Everything that can go wrong here means the same thing to every caller — "no name": the device
     * is offline (`IOException`), the key was rejected or the coordinate refused (`HttpException`), or
     * the body was not the JSON expected. A cancellation is the one exception and is rethrown, because
     * the map having moved on must cancel the coroutine that was waiting rather than be recorded as a
     * place with no name.
     */
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

    /**
     * Turns the two replies into the two lines the card shows.
     *
     * The order of preference is the whole feature, written out once:
     *
     *  1. a POI or business name, **if** one is within [POI_MATCH_METRES] of the pin;
     *  2. the address;
     *  3. the road or street;
     *  4. the neighbourhood;
     *  5. the locality or city;
     *  6. nothing — and the card falls back to the coordinates, which are always on screen.
     *
     * Steps 2 to 5 are not written here as a chain of `?:` calls: they *are*
     * [placeNamesByScale], which returns every administrative name in the reply ordered narrowest
     * first. Taking its first element is step 2-to-5, and taking the next couple is the detail line
     * under it — one list, so the headline and the line below it can never disagree about which of
     * them is the broader.
     *
     * When a POI *was* matched, the whole list goes underneath it instead, since the pin's own name is
     * no longer part of the address: "Patan Hospital" over "Lagankhel, Lalitpur".
     */
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
            // The long "name, context, country" string only when not one name could be classified —
            // a long answer beats none, exactly as on the platform side below.
            placeName = names.firstOrNull() ?: address?.fullPlaceName(),
            addressLine = names.drop(1).take(DETAIL_LINE_PARTS).joinLines(),
            isPointOfInterest = false
        )
    }

    /**
     * `"85.3157,27.6681"` — the path segment MapTiler's reverse endpoint takes.
     *
     * **Longitude first**, which is the GeoJSON convention and the reverse of how this app writes a
     * coordinate everywhere else; built in exactly one place for that reason. [Locale.US] so the
     * numbers always carry a decimal point: formatted under a European locale they would come out as
     * "85,3157,27,6681", which is four numbers to a parser expecting two.
     */
    private fun position(at: Coordinates): String =
        String.format(Locale.US, "%.6f,%.6f", at.longitude, at.latitude)

    /**
     * The language to ask for names in, or null to let MapTiler choose.
     *
     * Read per call rather than once, since the user can change the device's language while the app is
     * running. Only a genuine two-letter ISO 639-1 code is sent: a locale with no language set returns
     * an empty string, and a three-letter code would be rejected by the API — in both cases sending
     * nothing is better than sending something that turns the whole request into an error.
     */
    private fun deviceLanguage(): String? =
        Locale.getDefault().language.takeIf { it.length == ISO_LANGUAGE_LENGTH }

    /** "Lagankhel, Lalitpur", or null when the list was empty and there is no line to draw. */
    private fun List<String>.joinLines(): String? =
        takeIf { it.isNotEmpty() }?.joinToString(", ")

    // ---------------------------------------------------------------------------------------
    // The platform geocoder: the offline fallback
    // ---------------------------------------------------------------------------------------

    /**
     * One address for a coordinate, by whichever of the platform's two APIs this device has.
     *
     * From Android 13 the lookup takes a callback and does its own network work off the caller's
     * thread; before that the only entry point is a blocking call, which is why that branch is moved
     * to [Dispatchers.IO] by hand. The older method is deprecated precisely *because* it blocks, so
     * the warning it raises is suppressed rather than worked around — on API 24 to 32 there is no
     * other way to ask.
     */
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

                            // A failure and an empty result mean the same thing to every caller: no
                            // name. Reported as null rather than as an exception for that reason.
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
            // Never swallowed: cancelling the lookup because the map moved again must cancel the
            // coroutine that was waiting for it, not be mistaken for "this place has no name".
            throw e
        } catch (e: Exception) {
            // IOException when the geocoder is offline, IllegalArgumentException for a coordinate the
            // service will not accept. Both are "no name", which every caller already handles.
            null
        }
    }

    /**
     * Picks the two lines worth showing out of everything the platform returned.
     *
     * An [Address] carries up to a dozen fields and most devices fill in only some of them, so each
     * line is built from a list of candidates in order of usefulness with the blanks dropped. The
     * narrowest name comes first because it is the one a person recognises: "Pulchowk" says more to
     * somebody standing in it than "Bagmati Province" ever will.
     *
     * Note what is *not* here: a business name. There is no field on [Address] that could hold one,
     * which is the whole reason the MapTiler path above exists — so anything from this source is
     * reported with `isPointOfInterest = false` and the forms never suggest it as a place's name.
     *
     * Duplicates are removed rather than tolerated, because the same word genuinely does arrive in two
     * fields on some devices — a city that is also its own district would otherwise read "Lalitpur,
     * Lalitpur".
     *
     * **The full postal line is the fallback, not the first choice.** `getAddressLine(0)` is a complete
     * address including the country and often a postcode, which is more than this card has room for;
     * it is used only when the individual fields were all empty, where a long answer beats none.
     */
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

        // With no named fields at all there is still usually a postal line, which is better than
        // showing the user nothing above their coordinates.
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

    /**
     * Public only for [MIN_QUERY_LENGTH]: the view and the picker both need to know when a query is
     * long enough to produce suggestions, so that a single letter neither fires a request nor opens an
     * empty list claiming nothing was found. Everything else here is an implementation detail and
     * stays private.
     */
    companion object {

        /**
         * The shortest query worth sending: two characters.
         *
         * A single letter matches a sizeable fraction of the planet, so the request would spend a
         * round trip to produce a list of five arbitrary places — worse than showing nothing, because
         * it looks like an answer. Two is still broad, but it is the point at which "Zo" meaningfully
         * narrows towards the zoo.
         */
        const val MIN_QUERY_LENGTH = 2

        /**
         * How long to wait for a name before deciding there is none.
         *
         * Five seconds: long enough for a lookup on a slow connection, and short enough that the line
         * above the coordinates settles while the user is still reading the screen rather than after
         * they have moved the map somewhere else. Applied to the MapTiler pair as a whole, since they
         * run together and a card cannot be half drawn.
         *
         * The platform fallback is given its own five seconds rather than what is left of these,
         * which is a deliberate choice about the worst case: the two only ever run in sequence when
         * the network lookup produced *nothing at all*, and in that situation a slow answer naming the
         * city is worth more to the user than a fast "Dropped pin". The screen is not blocked either
         * way — the coordinates are already drawn, and the map stays draggable throughout.
         */
        private const val LOOKUP_TIMEOUT_MILLIS = 5_000L

        /**
         * How close a POI has to be to the centre pin to *be* the selected place: 40 metres.
         *
         * The figure is a compromise between the two ways this can read wrongly. Too small and a pin
         * dropped on the corner of a hospital's grounds misses the hospital, because MapTiler's point
         * for a large site is one representative coordinate rather than its outline. Too large and the
         * shop next door wins — or worse, the clinic across the junction, which is the failure that
         * actually matters: an invented business name looks exactly as confident as a real one.
         *
         * 40 metres is roughly a building and its yard, and comfortably inside the typical 50-metre
         * spacing of separate premises on a street.
         */
        private const val POI_MATCH_METRES = 40.0

        /**
         * Five nearby POIs, which is what makes the distance check meaningful rather than a yes/no on
         * whatever MapTiler happened to rank first. Its own ranking blends distance with prominence,
         * so the closest feature is not always the first one returned: a hospital a hundred metres
         * away can outrank the shop the pin is actually inside. Asking for a handful and measuring all
         * of them is what finds the shop.
         */
        private const val POI_LIMIT = 5

        /**
         * One address. A limit above one would turn this request into a nearest-neighbour search too,
         * and MapTiler only allows that alongside a single `types` value — which this request
         * deliberately does not send, because it wants the whole hierarchy containing the point rather
         * than the nearest few of one kind.
         */
        private const val ADDRESS_LIMIT = 1

        /**
         * How many names go on the line under a matched POI: two — "Lagankhel, Lalitpur".
         *
         * The neighbourhood and the city are what place a named business for somebody who knows the
         * town. A third would add the province, which is true, already implied, and the thing that
         * pushes the line onto a second row.
         */
        private const val ADDRESS_LINE_PARTS = 2

        /**
         * And how many when the headline is itself an address: two more after it, so "Lagankhel" is
         * followed by "Lalitpur, Bagmati" rather than by the whole chain down to the country.
         */
        private const val DETAIL_LINE_PARTS = 2

        /** ISO 639-1 codes are two letters; anything else is not one — see [deviceLanguage]. */
        private const val ISO_LANGUAGE_LENGTH = 2

        /**
         * How many suggestions the search field offers: five.
         *
         * Enough that the place being looked for is almost always among them, and few enough that the
         * list fits over the map without becoming a page of its own to scroll — which it must not
         * become, since the whole point is that the user never leaves this screen.
         */
        private const val SEARCH_LIMIT = 5
    }
}
