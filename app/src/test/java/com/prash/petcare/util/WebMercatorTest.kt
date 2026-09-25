package com.prash.petcare.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [WebMercator], the arithmetic that turns a finger's movement across the map on
 * "Add a place" into the coordinate the place is saved at.
 *
 * Plain JVM tests with no device, possible because [WebMercator] uses no Android classes — the same
 * arrangement, and the same reasoning, as [GeoDistanceTest].
 *
 * **Why this particular maths is worth testing rather than trusting.** Every mistake it can make
 * produces a coordinate that looks entirely reasonable. A flipped sign saves the place the same
 * distance on the wrong side of the street; a missing `cos(latitude)` term is invisible in Kathmandu
 * and badly wrong in Reykjavík; a zoom read one level out puts the pin twice as far from where the
 * user dropped it. None of those would look like a bug on screen — the map would simply be *slightly*
 * wrong, and the only person who would ever find out is somebody following the directions.
 *
 * The gesture itself is not tested here, because it cannot be: a pinch needs two real fingers, and
 * both this project's emulator and its test device refuse synthetic multi-touch. What is testable is
 * everything the gesture *decides with*, which is all of it below.
 */
class WebMercatorTest {

    // ---------------------------------------------------------------------------------------
    // Direction: the sign convention is the single easiest thing to get backwards
    // ---------------------------------------------------------------------------------------

    @Test
    fun `not moving the map leaves the centre alone`() {
        val from = Coordinates(27.6937, 85.3442)
        val centre = WebMercator.centreAfterDrag(from, dragX = 0.0, dragY = 0.0, zoom = 17)

        assertEquals(from.latitude, centre.latitude, 1e-9)
        assertEquals(from.longitude, centre.longitude, 1e-9)
    }

    @Test
    fun `dragging the map right moves the view west`() {
        // The land comes with the finger, so the viewport ends up looking further west — which is the
        // opposite of what "+x" suggests, and why the subtraction lives in one place.
        val from = Coordinates(27.6937, 85.3442)
        val centre = WebMercator.centreAfterDrag(from, dragX = 120.0, dragY = 0.0, zoom = 17)

        assertTrue(
            "dragging right should decrease longitude, got ${centre.longitude}",
            centre.longitude < from.longitude
        )
        assertEquals(from.latitude, centre.latitude, 1e-9)
    }

    @Test
    fun `dragging the map down moves the view north`() {
        val from = Coordinates(27.6937, 85.3442)
        val centre = WebMercator.centreAfterDrag(from, dragX = 0.0, dragY = 120.0, zoom = 17)

        assertTrue(
            "dragging down should increase latitude, got ${centre.latitude}",
            centre.latitude > from.latitude
        )
        assertEquals(from.longitude, centre.longitude, 1e-9)
    }

    // ---------------------------------------------------------------------------------------
    // Scale: how far a pixel goes
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a whole world width of pixels comes back to the same longitude`() {
        // At zoom 17 the world is 256 x 2^17 pixels round. Dragging by exactly that must wrap to where
        // it started — which is also the test that the wrap in pixelXToLongitude is a wrap and not a
        // clamp.
        val from = Coordinates(0.0, 10.0)
        val worldPixels = 256.0 * (1 shl 17)
        val centre = WebMercator.centreAfterDrag(from, dragX = worldPixels, dragY = 0.0, zoom = 17)

        assertEquals(10.0, centre.longitude, 1e-6)
    }

    @Test
    fun `each zoom level halves what a pixel is worth`() {
        val from = Coordinates(27.6937, 85.3442)
        val atSeventeen = WebMercator.centreAfterDrag(from, dragX = 256.0, dragY = 0.0, zoom = 17)
        val atSixteen = WebMercator.centreAfterDrag(from, dragX = 256.0, dragY = 0.0, zoom = 16)

        val shiftAtSeventeen = abs(from.longitude - atSeventeen.longitude)
        val shiftAtSixteen = abs(from.longitude - atSixteen.longitude)

        // Zoomed out one level, the same drag must cover exactly twice the ground.
        assertEquals(2.0, shiftAtSixteen / shiftAtSeventeen, 1e-9)
    }

    @Test
    fun `a vertical drag covers less ground the further north it starts`() {
        // Mercator stretches the poles, so the same pixels are worth fewer degrees of latitude near
        // Iceland than on the equator. This is the case a naive "degrees per pixel" constant fails.
        val drag = 200.0
        val atEquator = WebMercator.centreAfterDrag(Coordinates(0.0, 0.0), 0.0, drag, zoom = 12)
        val atSixty = WebMercator.centreAfterDrag(Coordinates(60.0, 0.0), 0.0, drag, zoom = 12)

        val shiftAtEquator = abs(atEquator.latitude - 0.0)
        val shiftAtSixty = abs(atSixty.latitude - 60.0)

        assertTrue(
            "$shiftAtSixty should be well under $shiftAtEquator",
            shiftAtSixty < shiftAtEquator
        )
        // cos(60°) = 0.5, so the northern drag covers about half as much latitude. Loose, because the
        // relationship only holds exactly in the limit of a small drag.
        assertEquals(0.5, shiftAtSixty / shiftAtEquator, 0.02)
    }

    // ---------------------------------------------------------------------------------------
    // Reversibility and limits
    // ---------------------------------------------------------------------------------------

    @Test
    fun `dragging back returns to where the map started`() {
        // Two half-gestures that cancel. This is what the user does when they overshoot, and any
        // rounding that accumulated per drag would show up here first.
        val from = Coordinates(27.6937, 85.3442)
        val there = WebMercator.centreAfterDrag(from, dragX = 260.0, dragY = -180.0, zoom = 17)
        val back = WebMercator.centreAfterDrag(there, dragX = -260.0, dragY = 180.0, zoom = 17)

        assertEquals(from.latitude, back.latitude, 1e-9)
        assertEquals(from.longitude, back.longitude, 1e-9)
    }

    @Test
    fun `a drag off the top of the world stops at the projection's edge`() {
        // Beyond about 85.05 degrees Mercator runs to infinity and no tiles exist, so the pin has to
        // stop there rather than being handed a latitude no map can draw.
        val centre = WebMercator.centreAfterDrag(
            from = Coordinates(84.0, 0.0),
            dragX = 0.0,
            dragY = -100_000.0,
            zoom = 10
        )

        assertTrue("latitude ${centre.latitude} escaped the projection", centre.latitude <= 85.06)
        assertTrue("latitude ${centre.latitude} is not a number", !centre.latitude.isNaN())
    }

    @Test
    fun `longitude always comes back inside minus 180 to 180`() {
        // Whatever the drag, the result has to be a longitude a Maps URL will accept — see
        // MapLinks, which writes it straight into one.
        val from = Coordinates(27.6937, 179.9)
        for (drag in listOf(-5_000_000.0, -100_000.0, 100_000.0, 5_000_000.0)) {
            val centre = WebMercator.centreAfterDrag(from, dragX = drag, dragY = 0.0, zoom = 14)
            assertTrue(
                "drag $drag produced longitude ${centre.longitude}",
                centre.longitude >= -180.0 && centre.longitude <= 180.0
            )
        }
    }
}
