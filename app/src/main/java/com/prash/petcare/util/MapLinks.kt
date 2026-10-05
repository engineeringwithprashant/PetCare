package com.prash.petcare.util

import java.util.Locale

/** Builds the two Google Maps links the Places feature hands out to other apps. */
object MapLinks {

    /** Turn-by-turn directions to a point, from wherever the user is. */
    fun directionsUrl(to: Coordinates): String =
        "https://www.google.com/maps/dir/?api=1&destination=${format(to)}"

    /** The Google Maps listing for a point: the place card, its photos, reviews and phone number. */
    fun searchUrl(at: Coordinates): String =
        "https://www.google.com/maps/search/?api=1&query=${format(at)}"

    /** "27.6788,85.3170" — a coordinate pair as every Maps URL expects it. */
    private fun format(coordinates: Coordinates): String = String.format(
        Locale.US,
        "%.6f,%.6f",
        coordinates.latitude,
        coordinates.longitude
    )
}
