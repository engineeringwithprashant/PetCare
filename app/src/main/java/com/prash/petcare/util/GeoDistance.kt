package com.prash.petcare.util

import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A point on the Earth's surface, in decimal degrees.
 *
 * The app's own type rather than `android.location.Location` or a Maps SDK `LatLng`: the Places
 * ViewModel and the distance maths below must not depend on Android classes — the ViewModel so it
 * stays free of framework types, as the project's MVVM rules require, and the maths so it can be
 * unit-tested on a plain JVM (see GeoDistanceTest). Two doubles is all either of them needs.
 */
data class Coordinates(val latitude: Double, val longitude: Double)

/**
 * Distance arithmetic for the Places tab, done by hand instead of with the Google Maps SDK.
 *
 * The screen needs exactly two things from geography: "how far is this place?" and "how long should
 * the distance axis be?". Neither needs a map, tiles or an API key, and pulling in the Maps SDK for
 * them would add megabytes to the app for a formula that fits in a dozen lines.
 *
 * Pure functions with no Android dependencies, which is what makes them testable without a device.
 */
object GeoDistance {

    /** The Earth's mean radius in kilometres — the figure the haversine formula is normally given. */
    private const val EARTH_RADIUS_KM = 6371.0088

    /**
     * The great-circle ("as the crow flies") distance between two points, in kilometres.
     *
     * Uses the **haversine formula**. The Earth is treated as a sphere, which is wrong by at most
     * about 0.5% — a few metres over the walking distances this screen shows, far below GPS error.
     *
     * Why haversine rather than the simpler spherical law of cosines: for two points very close
     * together the cosine formula takes `acos` of a number a hair's breadth below 1, where
     * floating-point rounding swamps the answer. Haversine stays accurate at a few metres, which is
     * precisely the range a "0.2 km away" park lives in.
     *
     * Note this is *not* a walking route. Real paths bend around buildings and rivers; without a
     * routing service the straight line is the honest figure to show, and the maps app the user hands
     * off to works out the actual route.
     */
    fun distanceKm(from: Coordinates, to: Coordinates): Double {
        val lat1 = Math.toRadians(from.latitude)
        val lat2 = Math.toRadians(to.latitude)
        val deltaLat = Math.toRadians(to.latitude - from.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)

        // a is the square of half the chord length between the points; c is the angle they subtend
        // at the Earth's centre. Multiplying that angle by the radius gives the arc length.
        val a = sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
        // coerceAtMost guards against `a` rounding to a fraction above 1 for antipodal points, where
        // sqrt would still work but asin would return NaN.
        val c = 2 * asin(sqrt(a).coerceAtMost(1.0))

        return EARTH_RADIUS_KM * c
    }

    /**
     * The spacing of the labelled ticks on the distance axis, in kilometres.
     *
     * The design calls for ticks every 0.5 km, and that is used whenever it produces a readable axis.
     * But a saved place 30 km away would need sixty labels at that spacing, crushed on top of one
     * another, so the step widens through a "nice" series — 0.5, 1, 2, 5, 10, 20, 50… — until the
     * axis needs no more than [maxTicks] of them. Round steps keep the labels easy to read at a glance.
     */
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

    /**
     * Where the distance axis ends: the furthest place rounded *up* to the next tick.
     *
     * Ending exactly on a tick is what lets the last label ("2 km") sit at the right-hand end of the
     * line, and rounding up rather than to the nearest keeps the furthest place's chip on the axis
     * instead of past its end. An axis is never shorter than one step, so a single place 50 metres
     * away still gets a line to sit on.
     *
     * [EPSILON] absorbs floating-point noise: a place at exactly 1.5 km can come out as 1.5000000001,
     * which would otherwise round up to a needless 2 km axis.
     */
    fun axisMaxKm(furthestKm: Double, stepKm: Double): Double =
        (ceil(furthestKm / stepKm - EPSILON) * stepKm).coerceAtLeast(stepKm)

    private const val EPSILON = 1e-9
}
