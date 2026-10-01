package com.prash.petcare.ui.place

import com.prash.petcare.BuildConfig

/**
 * Where the map's tiles and its cartography come from.
 *
 * A MapLibre style is not a tile URL — it is a JSON document describing the whole map: which vector
 * tile source to read, and then, for every kind of feature in it, how to draw it at every zoom level.
 * That is why switching between the layers below is a single call that changes the map's entire
 * appearance while the camera stays exactly where it is, and why the dark map is a genuinely dark
 * *design* rather than a light one with the colours inverted.
 *
 * **Why MapTiler, and which of their styles.** Both are MapTiler's own standard published styles,
 * built on OpenStreetMap data — nothing here is a custom style, so there is no style to maintain in a
 * console and no second place for the map's appearance to be defined:
 *
 *  - **Streets v2** for the drawn map — the closest freely available style to the familiar modern
 *    look: pale ground, clear road hierarchy, building footprints filled rather than outlined, and
 *    landmark and place labels that stay legible while the map moves. It is the style that answers
 *    "which building is this?", which is the only question this app's map is ever asked.
 *  - **Satellite Hybrid** for the imagery — the aerial photograph with the street network and place
 *    names drawn over it. Bare imagery is deliberately not offered: a vet is recognised from the look
 *    of its roof and its yard, but only once the name of the street it stands on can be read, so a
 *    photograph with nothing written on it would be a strictly worse version of this.
 *
 * **Attribution is not optional.** MapTiler's terms and OpenStreetMap's licence both require the
 * credit to be visible on the map, so [PlaceMapView] draws it over every map it shows rather than
 * hiding it behind an information button, and tapping it opens [ATTRIBUTION_URL].
 *
 * **The key.** It is read from `local.properties` through `BuildConfig` — see the note in
 * app/build.gradle.kts — so it is never committed. [isConfigured] is what the map checks before it
 * tries to load anything, so a clone without a key gets an honest "the map could not be loaded"
 * message instead of a blank grey rectangle.
 */
object MapStyles {

    /**
     * True when a MapTiler key is present in this build.
     *
     * Tested before the style is requested rather than discovered through a failed download: without
     * a key the request comes back as an authorisation error that surfaces as an empty map, and an
     * empty map looks like a bug rather than like a missing setting.
     */
    val isConfigured: Boolean get() = BuildConfig.MAPTILER_API_KEY.isNotBlank()

    /**
     * The style document for one layer choice.
     *
     * @param night True when the app is drawing its dark theme. Only the drawn map has a dark
     *   counterpart, and that is a fact about cartography rather than an omission: a dark style is a
     *   different palette for shapes the renderer draws itself, and there is no dark version of a
     *   photograph of the ground. Imagery is therefore shown as it is in both themes.
     */
    fun urlFor(type: MapType, night: Boolean): String {
        val style = when (type) {
            MapType.NORMAL -> if (night) STREETS_DARK else STREETS
            MapType.SATELLITE -> SATELLITE_HYBRID
        }
        return "https://api.maptiler.com/maps/$style/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
    }

    /**
     * Where the credit line leads. MapTiler's own copyright page, which names OpenStreetMap and its
     * contributors as well — one destination that covers both parties the map owes credit to.
     */
    const val ATTRIBUTION_URL = "https://www.maptiler.com/copyright/"

    /**
     * MapTiler's style identifiers.
     *
     * Named constants rather than strings at the call site because these are the one part of the URL
     * that decides what the user sees, and a typo in one would produce a map that simply failed to
     * load with nothing to say why.
     */
    private const val STREETS = "streets-v2"
    private const val STREETS_DARK = "streets-v2-dark"

    /**
     * MapTiler publish Satellite Hybrid under the id `hybrid`; their plain imagery, which this app
     * does not offer, is the one called `satellite`. The two are easy to transpose, which is why the
     * constant is named for what it *shows* rather than for the id it carries.
     */
    private const val SATELLITE_HYBRID = "hybrid"
}
