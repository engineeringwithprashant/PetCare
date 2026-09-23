package com.prash.petcare.util

/**
 * The fixed point the Places feature calls "home".
 *
 * **Why a constant rather than the phone's position.** The Places tab measures each saved place from
 * wherever the phone is, because "how far is the vet from me *now*" is the question that screen
 * answers. The Add a place screen cannot do that: the phone's position is the very thing being
 * saved, so measuring from it would print "0.0 km" every time. It measures from this reference
 * instead, which turns the figure into something useful — "you are 0.4 km from home", a sanity check
 * that the fix landed where the user thinks they are standing.
 *
 * The coordinates are the middle of Lalitpur, the neighbourhood the design's sample places sit in.
 * In a finished app this would be an address the user sets in their profile; it is a constant here
 * because the app has no home-address field, and inventing one would be a larger change than the
 * single figure it feeds.
 *
 * No Android types, so ViewModels may use it directly — the same reasoning as [GeoDistance].
 */
object HomeReference {

    /** Where distances on the Add a place screen are measured from. */
    val COORDINATES = Coordinates(latitude = 27.6750, longitude = 85.3100)

    /** How far a point is from [COORDINATES], in kilometres, by the haversine formula. */
    fun distanceKmFrom(point: Coordinates): Double =
        GeoDistance.distanceKm(COORDINATES, point)
}
