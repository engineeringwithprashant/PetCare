package com.prash.petcare.ui.place

import androidx.annotation.StringRes
import com.prash.petcare.R

/**
 * Which layer the map draws itself with.
 *
 * Part of the **View** layer's vocabulary, but held in a ViewModel (see [AddPlaceViewModel.mapType])
 * so the choice survives a rotation — it is a preference about how the map is *drawn*, which is the
 * kind of thing a user expects to stay put when they turn the phone.
 *
 * **Why two, and why the second one is hybrid imagery rather than bare imagery.** A map with nothing
 * written on it is a photograph, and a photograph cannot answer the question this feature asks — "is
 * this the right doorway?" — because finding a doorway needs the name of the street as well as the
 * shape of the roof. So "Satellite" here is MapTiler's *Satellite Hybrid*: the aerial photograph with
 * the road network and place names still drawn over it, which is also what the word "Satellite" turns
 * on in every mainstream maps app. Offering bare imagery as a third choice would be offering a strictly
 * worse version of this one.
 *
 * @property labelRes The name shown in the map-type menu.
 */
enum class MapType(@param:StringRes val labelRes: Int) {

    /** The drawn map: roads, buildings, parks and labels. The default, and the only one with a dark
     *  counterpart. */
    NORMAL(R.string.map_type_normal),

    /** Aerial photography with the road network and place names drawn over it. */
    SATELLITE(R.string.map_type_satellite);

    /**
     * True when this layer is built on a photograph of the ground.
     *
     * Asked when choosing a style: only the drawn map has a dark counterpart, because a dark map is a
     * different palette for shapes the renderer draws itself, and there is no dark version of a
     * photograph. See [MapStyles.urlFor].
     */
    val isImagery: Boolean get() = this != NORMAL
}
