package com.prash.petcare.util

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** A point on the Earth's surface, in decimal degrees. */
data class Coordinates(val latitude: Double, val longitude: Double)

/** Distance arithmetic for the Places tab, done by hand instead of with the Google Maps SDK. */
object GeoDistance {

    /** The Earth's mean radius in kilometres — the figure the haversine formula is normally given. */
    private const val EARTH_RADIUS_KM = 6371.0088

    /** The great-circle ("as the crow flies") distance between two points, in kilometres. */
    fun distanceKm(from: Coordinates, to: Coordinates): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = Math.toRadians(to.latitude - from.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)

        // a is the square of half the chord length between the points.
        val a = sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        // coerceAtMost guards against `a` rounding to a fraction above 1 for antipodal points.
        val c = 2 * asin(sqrt(a).coerceAtMost(1.0))

        return EARTH_RADIUS_KM * c
    }

    /** The spacing of the labelled ticks on the distance axis, in kilometres. */
    fun tickStepKm(maxDistanceKm: Double, maxTicks: Int = 5): Double {
        var magnitude = 1.0
        while (true) {
            for (base in doubleArrayOf(0.5, 1.0, 2.0)) {
                val step = base * magnitude
                if (ceil(maxDistanceKm / step - EPSILON) <= maxTicks) return step
            }
            magnitude *= 10
        }
    }

    /** Where the distance axis ends: the furthest place rounded up to the next tick. */
    fun axisMaxKm(furthestKm: Double, stepKm: Double): Double =
        (ceil(furthestKm / stepKm - EPSILON) * stepKm).coerceAtLeast(stepKm)

    private const val EPSILON = 1e-9
}
