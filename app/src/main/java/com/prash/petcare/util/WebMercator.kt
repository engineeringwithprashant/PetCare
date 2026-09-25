package com.prash.petcare.util

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh

/**
 * Turns "the user dragged the map 120 pixels left" into "the middle of the map is now at this
 * latitude and longitude".
 *
 * ### Why this file exists
 *
 * The map inside the app is Google's keyless `output=embed` page in a `WebView` — see
 * [com.prash.petcare.ui.place.MapEmbedView] for why. That page lives in a cross-origin iframe, so the
 * app can *tell* it where to centre but can never *ask* it where the user panned to: reading the
 * frame's position would be a cross-origin access the browser forbids, and the keyless embed offers no
 * message channel either.
 *
 * "Drag the map to place the pin" therefore has to be worked out on this side of the frame. The
 * picker measures the drag in pixels itself, this object converts those pixels into a new centre, and
 * the embed is then re-pointed at that centre. The app always knows the coordinate because the app is
 * the one that computed it — which is also what lets the saved place's Directions link point at
 * exactly the spot the user dropped the pin on.
 *
 * ### The projection
 *
 * Google Maps — and every other slippy map — draws the world with the **Web Mercator** projection onto
 * a square whose side is `256 × 2^zoom` pixels. Longitude maps linearly across that square, so a
 * horizontal drag is simple proportion. Latitude does not: Mercator stretches the poles, so a vertical
 * drag covers fewer degrees near the equator than near Iceland, and the conversion has to go through
 * the projection's logarithm rather than a constant "degrees per pixel".
 *
 * Pure functions with no Android dependencies, so the arithmetic can be unit-tested on a plain JVM —
 * the same reason [GeoDistance] is written this way.
 */
object WebMercator {

    /**
     * The point that ends up in the middle of the map after the map's *content* has been dragged by
     * [dragX] / [dragY] pixels.
     *
     * The sign convention is the one a finger produces, not the one the maths wants: dragging the map
     * to the **right** hands in a positive [dragX] and moves the viewport **west**, because the land
     * came with the finger. So the drag is subtracted from the centre's pixel position rather than
     * added to it — the single easiest thing to get backwards in a map picker, which is why it is done
     * once, here.
     *
     * @param from The point currently in the middle of the map.
     * @param dragX How far the content moved horizontally, in **CSS pixels** — the units the embedded
     *   page is laid out in. A caller measuring an Android touch event must divide its pixel deltas by
     *   the display density first; see MapEmbedView.
     * @param dragY How far the content moved vertically, in CSS pixels. Positive is downwards, as in
     *   every Android and CSS coordinate system.
     * @param zoom Google's zoom scale, the same figure written into the embed URL.
     */
    fun centreAfterDrag(from: Coordinates, dragX: Double, dragY: Double, zoom: Int): Coordinates {
        val worldPixels = worldSizePixels(zoom)

        val x = longitudeToPixelX(from.longitude, worldPixels) - dragX
        val y = latitudeToPixelY(from.latitude, worldPixels) - dragY

        return Coordinates(
            latitude = pixelYToLatitude(y, worldPixels),
            longitude = pixelXToLongitude(x, worldPixels)
        )
    }

    /** The width of the whole world at one zoom level: one 256-pixel tile, doubling per level. */
    private fun worldSizePixels(zoom: Int): Double = TILE_SIZE * Math.pow(2.0, zoom.toDouble())

    /** Longitude is linear in Mercator: −180° at the left edge of the square, +180° at the right. */
    private fun longitudeToPixelX(longitude: Double, worldPixels: Double): Double =
        (longitude + 180.0) / 360.0 * worldPixels

    /**
     * Latitude is *not* linear: this is the Mercator projection itself.
     *
     * `ln((1 + sin φ) / (1 - sin φ)) / 2` is the projection's vertical coordinate (the inverse
     * Gudermannian function); dividing by 2π scales it onto the unit square and subtracting from 0.5
     * flips it, because pixel y grows downwards while latitude grows upwards.
     *
     * The latitude is clamped first. At exactly ±90° the ratio inside the logarithm divides by zero
     * and the answer is infinite — which is why every slippy map stops at about ±85.05°, the latitude
     * where the square's bottom edge falls.
     */
    private fun latitudeToPixelY(latitude: Double, worldPixels: Double): Double {
        val sinLatitude = sin(Math.toRadians(latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
        return (0.5 - ln((1 + sinLatitude) / (1 - sinLatitude)) / (4 * PI)) * worldPixels
    }

    /**
     * Longitude back from a pixel, wrapped into −180°…180°.
     *
     * Wrapping rather than clamping: dragging west past the date line should come out at the far side
     * of the world, which is what the map itself does, whereas a clamp would stick the pin to the
     * edge of the projection.
     */
    private fun pixelXToLongitude(x: Double, worldPixels: Double): Double {
        val longitude = x / worldPixels * 360.0 - 180.0
        // rem can return a negative for a negative input, so 180 is added back before the final rem.
        return ((longitude + 180.0).rem(360.0) + 360.0).rem(360.0) - 180.0
    }

    /**
     * Latitude back from a pixel — the inverse of [latitudeToPixelY].
     *
     * `atan(sinh(…))` is the Gudermannian function, the standard inverse of the Mercator formula
     * above. Clamped to the projection's own limits, so a drag that runs off the top of the world
     * stops at the edge instead of producing a latitude no map can draw.
     */
    private fun pixelYToLatitude(y: Double, worldPixels: Double): Double {
        val projected = PI - 2 * PI * y / worldPixels
        return Math.toDegrees(atan(sinh(projected))).coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
    }

    /** One map tile, in CSS pixels. 256 is the size every slippy-map zoom level is defined against. */
    private const val TILE_SIZE = 256.0

    /**
     * Where Web Mercator stops: 85.0511°, the latitude whose projected y is exactly one world-width
     * from the equator. Beyond it the projection runs to infinity, so no tiles exist.
     */
    private const val MAX_LATITUDE = 85.05112878
}
