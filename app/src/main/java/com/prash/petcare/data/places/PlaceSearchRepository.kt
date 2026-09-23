package com.prash.petcare.data.places

import com.prash.petcare.data.local.entity.PlaceCategory
import com.prash.petcare.util.Coordinates
import java.util.Locale

/**
 * One place the search came back with, before the user has decided to save it.
 *
 * Deliberately *not* a [com.prash.petcare.data.local.entity.Place]: a suggestion is a result somebody
 * might pick, with no owner, no note and no row in the database. Mixing the two would mean either
 * inventing an owner for a result nobody has chosen, or letting a half-built Place exist — and the
 * moment an entity can be half-built, every screen reading it has to cope with that.
 *
 * @property id Stable within a set of results, so the screen can remember which one is chosen across
 *   a rotation without holding the object itself.
 * @property address The street or neighbourhood line shown under the name.
 * @property coordinates **Where the place is, according to the search.** This is the whole reason the
 *   search method exists: the position comes from the lookup, never from the phone.
 * @property distanceKm How far the result is from the area the search is centred on. Supplied with
 *   the result rather than measured here, exactly as a places API supplies it.
 */
data class PlaceSuggestion(
    val id: String,
    val name: String,
    val address: String,
    val category: PlaceCategory,
    val coordinates: Coordinates,
    val distanceKm: Double
)

/**
 * Looks places up by name or address for the Add a place screen.
 *
 * In MVVM this is a **Repository**: [com.prash.petcare.ui.place.AddPlaceViewModel] asks it for
 * results and is told nothing about where they come from. That indirection is the point of the layer
 * here more than anywhere else in the app, because this is the one data source that is genuinely
 * provisional.
 *
 * **What this stands in for.** In production the body of [search] is a call to a geocoding or places
 * service — Google Places Text Search, Mapbox Search or a Nominatim instance — and the coordinates
 * saved with the chosen result are the ones that service returns. The app has no such key and this is
 * coursework, so the lookup is served from a small fixed directory of real places around Lalitpur and
 * Kathmandu, matched the same way a text search matches: a case-insensitive substring of the name,
 * the address or the category.
 *
 * **What does not change when the real service arrives.** The type the ViewModel receives, the fact
 * that matching happens outside the ViewModel, and — most importantly — that the coordinates come
 * back *with the result*. No part of this method reads the device's location, and the real
 * implementation would not either. Searching for a vet is how a user saves a clinic on the other side
 * of the country without going there.
 *
 * The one visible difference would be that a network lookup is slow and can fail, which is why
 * [search] is a `suspend` function even though this implementation answers instantly: callers already
 * treat it as something to wait for, so swapping in a real client changes no calling code.
 */
class PlaceSearchRepository {

    /**
     * The places a query matches, nearest first.
     *
     * An **empty query is not an empty answer**: the screen opens on this method with nothing typed,
     * and a blank column under the search box would look broken. So a blank query returns the first
     * few entries as suggestions, which is also what a places API does when asked for results "near
     * here" with no text.
     *
     * Matching is a case-insensitive substring test against the name, the address *and* the category,
     * so "vet", "jhamsikhel" and "hospital" all find something. The device's locale is used for the
     * lower-casing rather than the root locale, because the words being matched are the ones the user
     * is reading on screen in that locale.
     */
    suspend fun search(query: String): List<PlaceSuggestion> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return DIRECTORY.take(SUGGESTION_COUNT)

        val locale = Locale.getDefault()
        val needle = trimmed.lowercase(locale)

        return DIRECTORY.filter { suggestion ->
            suggestion.name.lowercase(locale).contains(needle) ||
                suggestion.address.lowercase(locale).contains(needle) ||
                suggestion.category.name.lowercase(locale).contains(needle)
        }
    }

    private companion object {

        /** How many entries stand in as suggestions before anything has been typed. */
        const val SUGGESTION_COUNT = 4

        /**
         * The stand-in for the places service, in distance order so results need no sorting.
         *
         * Real places with real coordinates, so that a place saved from here opens in a maps app at
         * the right spot — a directory of invented positions would make "Open in maps" point at empty
         * fields, and the feature would look broken for a reason that has nothing to do with it.
         */
        val DIRECTORY = listOf(
            PlaceSuggestion(
                id = "jawalakhel-dog-park",
                name = "Jawalakhel Dog Park",
                address = "Jawalakhel, Lalitpur",
                category = PlaceCategory.PARK,
                coordinates = Coordinates(27.6725, 85.3122),
                distanceKm = 0.5
            ),
            PlaceSuggestion(
                id = "fluff-and-paws-salon",
                name = "Fluff & Paws Salon",
                address = "Jhamsikhel, Lalitpur",
                category = PlaceCategory.GROOMING,
                coordinates = Coordinates(27.6772, 85.3085),
                distanceKm = 0.8
            ),
            PlaceSuggestion(
                id = "patan-animal-hospital",
                name = "Patan Animal Hospital",
                address = "Pulchowk Rd, Lalitpur",
                category = PlaceCategory.VET,
                coordinates = Coordinates(27.6766, 85.3159),
                distanceKm = 1.2
            ),
            PlaceSuggestion(
                id = "happy-tails-pet-store",
                name = "Happy Tails Pet Store",
                address = "Kupondole, Lalitpur",
                category = PlaceCategory.STORE,
                coordinates = Coordinates(27.6836, 85.3170),
                distanceKm = 1.6
            ),
            PlaceSuggestion(
                id = "sanepa-vet-clinic",
                name = "Sanepa Veterinary Clinic",
                address = "Sanepa Ring Rd, Lalitpur",
                category = PlaceCategory.VET,
                coordinates = Coordinates(27.6842, 85.3052),
                distanceKm = 2.1
            ),
            PlaceSuggestion(
                id = "ratna-park-green",
                name = "Ratna Park Green",
                address = "Ratna Park, Kathmandu",
                category = PlaceCategory.PARK,
                coordinates = Coordinates(27.7044, 85.3145),
                distanceKm = 4.3
            ),
            PlaceSuggestion(
                id = "kathmandu-pet-supplies",
                name = "Kathmandu Pet Supplies",
                address = "Thamel, Kathmandu",
                category = PlaceCategory.STORE,
                coordinates = Coordinates(27.7154, 85.3123),
                distanceKm = 5.6
            ),
            PlaceSuggestion(
                id = "kathmandu-animal-treatment",
                name = "Kathmandu Animal Treatment Centre",
                address = "Budhanilkantha, Kathmandu",
                category = PlaceCategory.VET,
                coordinates = Coordinates(27.7628, 85.3617),
                distanceKm = 8.4
            )
        )
    }
}
