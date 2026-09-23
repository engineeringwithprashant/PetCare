package com.prash.petcare.util

import kotlin.math.hypot

/**
 * Turns a position *on the pin map* into a position *on the Earth*.
 *
 * This is the whole of the "Drop pin" method's geography, and it is deliberately the only place in
 * the app that knows it. The map on the Add a place screen is a drawing, not a tile server: the user
 * moves a marker over it and the app has to answer "which latitude and longitude is that?". That is
 * exactly the question a real map library answers with `unproject` or `latLngFromPoint`, and the
 * answer has the same shape — a linear mapping from a fraction across the visible bounding box onto
 * the degrees that box spans.
 *
 * **Nothing here reads the device's GPS.** A pin dropped in Kathmandu while the phone is in London
 * produces Kathmandu's coordinates, which is the entire point of the feature: a place can be saved
 * from anywhere, and physical presence is never required.
 *
 * **The bounding box** is the stretch of Lalitpur the design draws — about 3 km north to south and
 * 2.6 km east to west, with Jawalakhel and Pulchowk inside it. Latitude *decreases* down the screen
 * because y grows downwards in every graphics system and north is up on every map, so the two run
 * opposite ways. Longitude and x both grow to the right, so that one is a plain addition.
 *
 * **Why an object of pure functions.** The same reasoning as [GeoDistance]: no Android types, so the
 * ViewModel can use it without breaking the project's rule against framework types in that layer,
 * and every figure below can be checked by a plain JVM unit test.
 *
 * Swapping the drawing for a real map later replaces the two functions here and nothing else — the
 * ViewModel asks for coordinates at a percentage and is not told where they come from.
 */
object PinMapProjection {

    /** The latitude of the map's top edge — its north-west corner, with [WEST_LONGITUDE]. */
    const val NORTH_LATITUDE = 27.6820

    /** How many degrees of latitude the map spans from its top edge to its bottom, about 3.1 km. */
    const val LATITUDE_SPAN = 0.028

    /** The longitude of the map's left edge. */
    const val WEST_LONGITUDE = 85.3020

    /** How many degrees of longitude the map spans from left to right, about 2.6 km at this latitude. */
    const val LONGITUDE_SPAN = 0.026

    /**
     * Where "home" is drawn on the map, as a percentage across and down it — the blue dot the
     * distance under the map is measured from.
     *
     * The Places tab measures distances from wherever the phone is (see PlacesViewModel). This screen
     * cannot: it must work with location switched off, so it measures from the same fixed reference
     * the map itself is drawn around. The figure shown under the map is therefore "how far the pin is
     * from the middle of this map", which is what the user is actually judging as they move it.
     */
    const val HOME_X_PERCENT = 44f
    const val HOME_Y_PERCENT = 58f

    /**
     * How wide the map is on the ground, in kilometres.
     *
     * Used to turn a distance measured *in percentage points* into one in kilometres. The map is very
     * nearly square in both percentage and ground terms, so one figure serves both axes and the
     * straight-line distance is an ordinary Pythagoras rather than a great-circle calculation — over
     * three kilometres the difference is centimetres.
     */
    const val MAP_WIDTH_KM = 4.2

    /**
     * The narrowest and widest the marker may be placed, as percentages.
     *
     * Clamped so the marker cannot be dragged off the map: its label pill sits *above* the point it
     * marks, which is why the top limit (12%) is further in than the others — at y = 0 the label would
     * be drawn outside the map's rounded edge and clipped in half.
     */
    const val MIN_X_PERCENT = 6f
    const val MAX_X_PERCENT = 94f
    const val MIN_Y_PERCENT = 12f
    const val MAX_Y_PERCENT = 94f

    /**
     * The coordinates under a point on the map.
     *
     * @param xPercent How far across the map, 0 at the left edge and 100 at the right.
     * @param yPercent How far down the map, 0 at the top edge and 100 at the bottom.
     */
    fun coordinatesAt(xPercent: Float, yPercent: Float): Coordinates = Coordinates(
        latitude = NORTH_LATITUDE - (yPercent / 100.0) * LATITUDE_SPAN,
        longitude = WEST_LONGITUDE + (xPercent / 100.0) * LONGITUDE_SPAN
    )

    /** Where the blue "you are here" dot sits, as coordinates — the origin of [distanceFromHomeKm]. */
    fun home(): Coordinates = coordinatesAt(HOME_X_PERCENT, HOME_Y_PERCENT)

    /**
     * How far a point on the map is from [home], in kilometres.
     *
     * Measured across the drawing rather than with the haversine formula in [GeoDistance]: the two
     * agree to within a few metres at this scale, and doing it in the map's own units keeps the
     * figure under the map consistent with what the user can see — a marker dragged twice as far
     * shows twice the distance.
     */
    fun distanceFromHomeKm(xPercent: Float, yPercent: Float): Double =
        hypot(
            (xPercent - HOME_X_PERCENT).toDouble(),
            (yPercent - HOME_Y_PERCENT).toDouble()
        ) / 100.0 * MAP_WIDTH_KM

    /** Holds a marker position inside the map — see [MIN_X_PERCENT] for why the four limits differ. */
    fun clampX(xPercent: Float): Float = xPercent.coerceIn(MIN_X_PERCENT, MAX_X_PERCENT)

    /** @see clampX */
    fun clampY(yPercent: Float): Float = yPercent.coerceIn(MIN_Y_PERCENT, MAX_Y_PERCENT)
}
