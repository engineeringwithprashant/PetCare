package com.prash.petcare.ui.common

import android.content.Context
import com.prash.petcare.R
import com.prash.petcare.data.local.SettingsStore
import com.prash.petcare.data.local.WeightUnit
import kotlin.math.abs
import kotlin.math.round

/* Display formatting for a pet's weight, shared by the dashboard's pet cards. */

/** Pounds in one kilogram, to the precision the international definition fixes it at. */
private const val POUNDS_PER_KILOGRAM = 2.20462262185

/** Renders a stored weight in the unit the user prefers, with its label: "26 kg", "57.3 lb". */
fun formatWeightWithUnit(context: Context, kilograms: Double, unit: WeightUnit): String =
    context.getString(
        when (unit) {
            WeightUnit.METRIC -> R.string.pet_detail_weight_format
            WeightUnit.IMPERIAL -> R.string.pet_detail_weight_format_imperial
        },
        formatWeight(kilograms, unit)
    )

/** Renders the bare number, without its unit — for the Pets tab's stat block. */
fun formatWeight(kilograms: Double, unit: WeightUnit = WeightUnit.METRIC): String {
    val value = when (unit) {
        WeightUnit.METRIC -> kilograms
        WeightUnit.IMPERIAL -> round(kilograms * POUNDS_PER_KILOGRAM * 10) / 10
    }

    // Compared against the value truncated to a whole number rather than against a rounded.
    return if (abs(value - round(value)) < WHOLE_TOLERANCE) {
        round(value).toLong().toString()
    } else {
        value.toString()
    }
}

/** Anything this close to a whole unit is written as one. */
private const val WHOLE_TOLERANCE = 0.001

/** The unit the app should currently write weights. */
fun SettingsStore.currentWeightUnit(): WeightUnit = weightUnit.value
