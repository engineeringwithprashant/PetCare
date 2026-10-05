package com.prash.petcare.ui.place

import com.prash.petcare.BuildConfig

/** Where the map's tiles and its cartography come from. */
object MapStyles {

    /** True when a MapTiler key is present in this build. */
    val isConfigured: Boolean get() = BuildConfig.MAPTILER_API_KEY.isNotBlank()

    /** The style document for one layer choice. */
    fun urlFor(type: MapType, night: Boolean): String {
        val style = when (type) {
            MapType.NORMAL -> if (night) STREETS_DARK else STREETS
            MapType.SATELLITE -> SATELLITE_HYBRID
        }
        return "https://api.maptiler.com/maps/$style/style.json?key=${BuildConfig.MAPTILER_API_KEY}"
    }

    /** Where the credit line leads. MapTiler's own copyright page. */
    const val ATTRIBUTION_URL = "https://www.maptiler.com/copyright/"

    /** MapTiler's style identifiers. */
    private const val STREETS = "streets-v2"
    private const val STREETS_DARK = "streets-v2-dark"

    /** MapTiler publish Satellite Hybrid under the id `hybrid`; their plain imagery, which this app does not offer. */
    private const val SATELLITE_HYBRID = "hybrid"
}
