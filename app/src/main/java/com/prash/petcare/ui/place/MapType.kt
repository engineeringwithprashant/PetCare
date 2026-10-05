package com.prash.petcare.ui.place

import androidx.annotation.StringRes
import com.prash.petcare.R

/** Which layer the map draws itself with. */
enum class MapType(@param:StringRes val labelRes: Int) {

    /** The drawn map: roads, buildings, parks and labels. */
    NORMAL(R.string.map_type_normal),

    /** Aerial photography with the road network and place names drawn over it. */
    SATELLITE(R.string.map_type_satellite);

    /** True when this layer is built on a photograph of the ground. */
    val isImagery: Boolean get() = this != NORMAL
}
