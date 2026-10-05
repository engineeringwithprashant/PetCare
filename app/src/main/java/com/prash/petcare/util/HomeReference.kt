package com.prash.petcare.util

/** The fixed point the Places feature calls "home". */
object HomeReference {

    /** Where distances on the Add a place screen are measured from. */
    val COORDINATES = Coordinates(latitude = 27.6750, longitude = 85.3100)

    /** How far a point is from [COORDINATES], in kilometres, by the haversine formula. */
    fun distanceKmFrom(point: Coordinates): Double =
        GeoDistance.distanceKm(COORDINATES, point)
}
