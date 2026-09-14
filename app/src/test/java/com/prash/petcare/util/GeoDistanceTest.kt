package com.prash.petcare.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [GeoDistance], the arithmetic behind every distance on the Places tab.
 *
 * Plain JVM tests with no device, possible because [GeoDistance] uses no Android classes. They matter
 * for the same reason as CareScheduleTest: a distance formula with a sign error or a degrees/radians
 * mix-up still produces a plausible-looking "3.4 km" on screen, and nobody clicking through the app
 * would notice it was wrong.
 */
class GeoDistanceTest {

    // ---------------------------------------------------------------------------------------
    // distanceKm
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the same point is zero kilometres away`() {
        val point = Coordinates(27.6766, 85.3162)
        assertEquals(0.0, GeoDistance.distanceKm(point, point), 1e-9)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        // A degree of latitude is the same length everywhere on a sphere: 2πR / 360.
        val distance = GeoDistance.distanceKm(Coordinates(0.0, 0.0), Coordinates(1.0, 0.0))
        assertEquals(111.19, distance, 0.05)
    }

    @Test
    fun `a degree of longitude shrinks towards the poles`() {
        // At 60° north a degree of longitude is half its length at the equator (cos 60° = 0.5) —
        // the case that catches forgetting the cos(latitude) term.
        val atEquator = GeoDistance.distanceKm(Coordinates(0.0, 0.0), Coordinates(0.0, 1.0))
        val atSixty = GeoDistance.distanceKm(Coordinates(60.0, 0.0), Coordinates(60.0, 1.0))
        assertEquals(atEquator / 2, atSixty, 0.1)
    }

    @Test
    fun `a known city pair matches the published distance`() {
        // Kathmandu to Pokhara is about 142 km in a straight line.
        val kathmandu = Coordinates(27.7172, 85.3240)
        val pokhara = Coordinates(28.2096, 83.9856)
        assertEquals(142.0, GeoDistance.distanceKm(kathmandu, pokhara), 2.0)
    }

    @Test
    fun `short walking distances stay accurate`() {
        // 0.009° of latitude is almost exactly 1 km — the range the Places tab actually shows, and
        // where a less stable formula would drown in rounding error.
        val distance = GeoDistance.distanceKm(Coordinates(27.6700, 85.3200), Coordinates(27.6790, 85.3200))
        assertEquals(1.0, distance, 0.01)
    }

    @Test
    fun `distance is the same in both directions`() {
        val a = Coordinates(27.6766, 85.3162)
        val b = Coordinates(27.7000, 85.3000)
        assertEquals(GeoDistance.distanceKm(a, b), GeoDistance.distanceKm(b, a), 1e-9)
    }

    // ---------------------------------------------------------------------------------------
    // tickStepKm and axisMaxKm
    // ---------------------------------------------------------------------------------------

    @Test
    fun `walking distances use the design's half-kilometre ticks`() {
        // The design: places up to 1.9 km away, ticks at 0.5, 1, 1.5, 2 — "within 2 km".
        val step = GeoDistance.tickStepKm(1.9)
        assertEquals(0.5, step, 1e-9)
        assertEquals(2.0, GeoDistance.axisMaxKm(1.9, step), 1e-9)
    }

    @Test
    fun `a place exactly on a tick does not round the axis up a step`() {
        assertEquals(1.5, GeoDistance.axisMaxKm(1.5, 0.5), 1e-9)
    }

    @Test
    fun `a very near place still gets one step of axis`() {
        assertEquals(0.5, GeoDistance.axisMaxKm(0.05, GeoDistance.tickStepKm(0.05)), 1e-9)
        assertEquals(0.5, GeoDistance.axisMaxKm(0.0, 0.5), 1e-9)
    }

    @Test
    fun `longer distances widen the step so the ticks stay readable`() {
        // 2.6 km at 0.5 km would need six ticks; 1 km steps need three.
        assertEquals(1.0, GeoDistance.tickStepKm(2.6), 1e-9)
        // 30 km: 0.5→60, 1→30, 2→15, 5→6, 10→3 ticks.
        assertEquals(10.0, GeoDistance.tickStepKm(30.0), 1e-9)
        assertEquals(30.0, GeoDistance.axisMaxKm(30.0, 10.0), 1e-9)
    }

    @Test
    fun `no step ever produces more than the maximum number of ticks`() {
        listOf(0.1, 0.7, 2.49, 2.51, 4.9, 9.99, 17.0, 123.0, 4_321.0).forEach { furthest ->
            val step = GeoDistance.tickStepKm(furthest)
            val ticks = GeoDistance.axisMaxKm(furthest, step) / step
            assertTrue("$furthest km gave $ticks ticks at $step km", ticks <= 5.0 + 1e-9)
        }
    }
}
