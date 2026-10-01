package com.prash.petcare.util

import java.util.Locale

/**
 * Builds the two Google Maps links the Places feature hands out to other apps.
 *
 * **Why one object rather than string templates at each call site.** The same pair of coordinates is
 * written into both URLs — the directions link and the "open the listing" link — and they must agree
 * with each other and with the map drawn inside the app. A place whose card showed one point while its
 * Directions button navigated to another would be worse than no map at all, so the formatting happens
 * once, here, and the two builders differ only in the address they wrap it in.
 *
 * **This is not how the in-app map is drawn.** It used to be: an `output=embed` Google map inside a
 * WebView's iframe. That page was cross-origin, so the app could point it somewhere but never ask it
 * where the user had panned to, and everything about choosing a location on it had to be faked. The
 * map is now MapLibre drawing MapTiler's vector tiles as a native Android view — see
 * [com.prash.petcare.ui.place.PlaceMapView]. Only these two *hand-off* links remain here, and they are
 * the documented, stable Maps URL scheme: the app draws its own map, but the user already navigates
 * with Google Maps, so "take me there" belongs in it.
 */
object MapLinks {

    /**
     * Turn-by-turn directions to a point, from wherever the user is.
     *
     * The `api=1` universal URL scheme: on a device with Google Maps installed Android hands this
     * straight to the app, and on one without it the same link opens in the browser. That is why the
     * app needs no maps dependency of its own to offer directions — see
     * [com.prash.petcare.ui.place.PlacesFragment.openLink].
     *
     * No origin is given, so Maps starts the route from the user's current position — which is the
     * only sensible answer for "take me there", and it is worked out by Maps rather than by this app.
     */
    fun directionsUrl(to: Coordinates): String =
        "https://www.google.com/maps/dir/?api=1&destination=${format(to)}"

    /**
     * The Google Maps listing for a point: the place card, its photos, reviews and phone number.
     *
     * The same `api=1` scheme as [directionsUrl], and the same hand-off to the Maps app. Searching by
     * coordinates rather than by name deliberately: a saved place's name is whatever the user typed,
     * and "Dad's vet" would find nothing, whereas the geotag always resolves to the right spot.
     */
    fun searchUrl(at: Coordinates): String =
        "https://www.google.com/maps/search/?api=1&query=${format(at)}"

    /**
     * "27.6788,85.3170" — a coordinate pair as every Maps URL expects it.
     *
     * [Locale.US] is not a detail. A locale-aware format would write "27,6788" in much of Europe, and
     * a comma is the character that separates latitude from longitude — so the URL would carry four
     * numbers where Maps expects two, and point somewhere else entirely.
     *
     * Six decimal places is about 10 cm, which is finer than any phone's GPS and well inside what a
     * URL can carry without being truncated.
     */
    private fun format(coordinates: Coordinates): String = String.format(
        Locale.US,
        "%.6f,%.6f",
        coordinates.latitude,
        coordinates.longitude
    )
}
