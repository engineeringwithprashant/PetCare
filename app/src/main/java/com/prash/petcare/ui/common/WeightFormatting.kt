package com.prash.petcare.ui.common

import android.content.Context
import com.prash.petcare.R
import com.prash.petcare.data.local.SettingsStore
import com.prash.petcare.data.local.WeightUnit
import kotlin.math.abs
import kotlin.math.round

/*
 * Display formatting for a pet's weight, shared by the dashboard's pet cards, the Pets tab and the
 * pet detail screen.
 *
 * Part of the View layer's helpers: it turns a stored value into text and holds no state. It sits in
 * ui/common because three screens in different packages need identical output — the same pet must not
 * read "26 kg" on one tab and "26.0 kg" on the next.
 *
 * **Weight is always stored in kilograms**, whatever the user has chosen on the profile screen. The
 * pet form asks for kilograms and writes kilograms to [com.prash.petcare.data.local.entity.Pet.weightKg];
 * the imperial setting converts only at the moment a number is drawn. Converting the stored column
 * instead would lose a little precision on every switch, and would make a pet's recorded weight depend
 * on which setting happened to be active the last time it was edited.
 */

/** Pounds in one kilogram, to the precision the international definition fixes it at. */
private const val POUNDS_PER_KILOGRAM = 2.20462262185

/**
 * Renders a stored weight in the unit the user prefers, with its label: "26 kg", "57.3 lb".
 *
 * The single call every screen should make. It takes the [WeightUnit] rather than reading the
 * setting itself, so this file stays a pure function of its arguments — which is what lets a screen
 * re-render from a flow when the setting changes, instead of having to be told to refresh.
 *
 * @param context For the unit's label, so "kg" and "lb" can be translated.
 * @param kilograms The stored weight, always in kilograms — see the note at the top of this file.
 */
fun formatWeightWithUnit(context: Context, kilograms: Double, unit: WeightUnit): String =
    context.getString(
        when (unit) {
            WeightUnit.METRIC -> R.string.pet_detail_weight_format
            WeightUnit.IMPERIAL -> R.string.pet_detail_weight_format_imperial
        },
        formatWeight(kilograms, unit)
    )

/**
 * Renders the bare number, without its unit — for the Pets tab's stat block, where "WEIGHT" is
 * already written underneath and the figure is set in its own larger type.
 *
 * 26.0 becomes "26" and 4.5 stays "4.5". Weight is stored as a `Double` because half-kilograms
 * matter for a small animal, but most are recorded as whole numbers and `toString()` would put
 * "26.0" on every card.
 *
 * **Pounds are rounded to one decimal place first.** An exact conversion produces figures like
 * 57.32018816, and the wholeness test below would then be false for practically every pet — so every
 * imperial weight would show a long tail of digits no scale ever measured. One decimal is as fine as
 * a kitchen or bathroom scale reads, and the rounding is what lets a round number such as 5 kg still
 * print as "11" rather than "11.0".
 */
fun formatWeight(kilograms: Double, unit: WeightUnit = WeightUnit.METRIC): String {
    val value = when (unit) {
        WeightUnit.METRIC -> kilograms
        WeightUnit.IMPERIAL -> round(kilograms * POUNDS_PER_KILOGRAM * 10) / 10
    }

    // Compared against the value truncated to a whole number rather than against a rounded one, so
    // only a genuinely whole weight loses its decimal. The small tolerance covers the conversion
    // above landing a hair either side of a whole number.
    return if (abs(value - round(value)) < WHOLE_TOLERANCE) {
        round(value).toLong().toString()
    } else {
        value.toString()
    }
}

/** Anything this close to a whole unit is written as one. */
private const val WHOLE_TOLERANCE = 0.001

/**
 * The unit the app should currently write weights in, as a plain convenience for a screen that has
 * the store to hand and only needs the value once.
 *
 * Screens that have to *react* to the setting changing should collect
 * [SettingsStore.weightUnit] instead — this reads the current value and does not come back.
 */
fun SettingsStore.currentWeightUnit(): WeightUnit = weightUnit.value
