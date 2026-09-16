package com.prash.petcare.util

import kotlin.math.roundToInt

/**
 * Whether it is pleasant outside for a walk, judged from the air temperature.
 *
 * Plain Kotlin with no Android types and no clock of its own, for the same reason [CareSchedule] and
 * [MonthlySpending] are: this is the one *rule* in the weather feature — everything around it is
 * plumbing — and a rule with boundaries is exactly the kind of thing that goes wrong by one degree
 * without anyone noticing. Keeping it here lets WalkComfortTest check each edge on a plain JVM.
 *
 * It says nothing about *words* or *colours*. The result is data, and HomeFragment turns it into
 * "GOOD NOW" or "TOO HOT" through string resources, so the wording can be translated and no part of
 * the data layer ever needs a Context.
 */
enum class WalkComfort {
    /** Colder than [COMFORTABLE_MIN_CELSIUS]. */
    TOO_COLD,

    /** Within the comfortable range — the card's "GOOD NOW". */
    GOOD,

    /** Warmer than [COMFORTABLE_MAX_CELSIUS]. */
    TOO_HOT;

    companion object {

        /**
         * The coldest and warmest it can be for a walk still to read as a good idea.
         *
         * 10–25 °C is chosen for the animal rather than the owner: below about 10 °C small and
         * short-coated dogs start to feel it, and above about 25 °C pavement heat and the fact that
         * dogs shed heat by panting rather than sweating make a midday walk genuinely risky. The
         * range is deliberately a little narrower than a person's own comfort, since the card is
         * advising on the pet's walk, not the owner's coat.
         */
        const val COMFORTABLE_MIN_CELSIUS = 10
        const val COMFORTABLE_MAX_CELSIUS = 25

        /**
         * Which band a reading falls in.
         *
         * The temperature is **rounded first**, to the same whole number the card displays. Judging
         * the raw value instead would let a reading of 25.4 °C print "25°C" beside a "TOO HOT" pill,
         * which reads as a bug even though both halves are correct on their own terms. Deciding from
         * what the user can actually see makes the two agree by construction.
         */
        fun at(temperatureCelsius: Double): WalkComfort = when {
            temperatureCelsius.roundToInt() < COMFORTABLE_MIN_CELSIUS -> TOO_COLD
            temperatureCelsius.roundToInt() > COMFORTABLE_MAX_CELSIUS -> TOO_HOT
            else -> GOOD
        }
    }
}
