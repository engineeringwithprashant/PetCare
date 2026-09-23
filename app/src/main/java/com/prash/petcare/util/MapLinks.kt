package com.prash.petcare.util

import java.util.Locale

/**
 * Builds the three Google Maps URLs the Places feature needs.
 *
 * **Why one object rather than string templates at each call site.** The same pair of coordinates is
 * written into three different URLs — the map shown inside the app, the directions link and the
 * "open the listing" link — and all three must agree. A place whose map card showed one point while
 * its Directions button navigated to another would be worse than no map at all, so the formatting
 * happens once, here, and the three builders differ only in the address they wrap it in.
 *
 * **Why no API key.** [embedUrl] uses the keyless `output=embed` form of Google Maps, which serves an
 * ordinary interactive map page that any browser — or, in this app, a `WebView` — can display. That
 * keeps a real map in a university project with no billing account, no key to leak into version
 * control and no Maps SDK dependency.
 *
 * **Production note.** For a shipped app, swap [embedUrl] for the Google Maps *Embed API*
 * (`https://www.google.com/maps/embed/v1/place?key=…`) or the Maps SDK for Android. Both take your
 * own API key, both are supported for production traffic, and the SDK additionally offers a native
 * dark map style — which would let the CSS invert filter in
 * [com.prash.petcare.ui.place.MapEmbedView] be deleted rather than kept as an approximation. The two
 * link builders below are already the documented, stable Maps URL scheme and would not change.
 */
object MapLinks {

    /**
     * The interactive map page for a point, as shown inside the app.
     *
     * `output=embed` is what strips the page down to the map itself — no Google header, no search
     * box, no side panel — while leaving panning and zooming intact. `q=` drops the pin, `z=` sets
     * the starting zoom and `hl=en` fixes the interface language, so the map does not change
     * character with the device's locale while the app around it stays in English.
     *
     * @param zoom Google's zoom scale: 16 shows a neighbourhood, 17 a street.
     */
    fun embedUrl(at: Coordinates, zoom: Int): String =
        "https://maps.google.com/maps?q=${format(at)}&z=$zoom&hl=en&output=embed"

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
