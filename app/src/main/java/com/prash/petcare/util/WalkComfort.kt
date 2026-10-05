package com.prash.petcare.util

import kotlin.math.roundToInt

/** Whether it is pleasant outside for a walk, judged from the air temperature. */
enum class WalkComfort {
    /** Colder than [COMFORTABLE_MIN_CELSIUS]. */
    TOO_COLD,

    /** Within the comfortable range — the card's "GOOD NOW". */
    GOOD,

    /** Warmer than [COMFORTABLE_MAX_CELSIUS]. */
    TOO_HOT;

    companion object {

        /** The coldest and warmest it can be for a walk still to read as a good idea. */
        const val COMFORTABLE_MIN_CELSIUS = 10
        const val COMFORTABLE_MAX_CELSIUS = 25

        /** Which band a reading falls in. */
        fun at(temperatureCelsius: Double): WalkComfort = when {
            temperatureCelsius.roundToInt() < COMFORTABLE_MIN_CELSIUS -> TOO_COLD
            temperatureCelsius.roundToInt() > COMFORTABLE_MAX_CELSIUS -> TOO_HOT
            else -> GOOD
        }
    }
}
