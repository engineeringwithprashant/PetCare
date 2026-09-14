package com.prash.petcare.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.PlaceCategory
import java.text.NumberFormat

/*
 * Turns saved-place values into text for the screen: category names in their three lengths, and
 * distances in kilometres.
 *
 * In the UI layer rather than on [PlaceCategory] itself for the reason given in CareLabels.kt — the
 * data layer stays free of Android resources. Each `when` has no `else`, so adding a category will
 * not compile until all of its labels have been written.
 */

/** The category's name on a filter chip and in the form's dropdown: "Vet", "Grooming". */
@StringRes
fun PlaceCategory.labelRes(): Int = when (this) {
    PlaceCategory.VET -> R.string.place_category_vet
    PlaceCategory.GROOMING -> R.string.place_category_grooming
    PlaceCategory.PARK -> R.string.place_category_park
    PlaceCategory.STORE -> R.string.place_category_store
}

/**
 * The longer description used under a place's name: "Vet clinic", "Pet store".
 *
 * A separate string from [labelRes] because the two jobs want different words. A chip row reads best
 * as short parallel nouns ("Vet · Grooming · Park · Store"), whereas a line describing one place reads
 * better as what it *is*.
 */
@StringRes
fun PlaceCategory.descriptionRes(): Int = when (this) {
    PlaceCategory.VET -> R.string.place_category_vet_description
    PlaceCategory.GROOMING -> R.string.place_category_grooming_description
    PlaceCategory.PARK -> R.string.place_category_park_description
    PlaceCategory.STORE -> R.string.place_category_store_description
}

/**
 * The three-or-four letter abbreviation in the small category badges and on the distance axis:
 * "Vet", "Grm", "Park", "Shop".
 *
 * Abbreviated because a badge is 40dp square and an axis chip has to fit between its neighbours —
 * "Grooming" at a legible size is wider than either.
 */
@StringRes
fun PlaceCategory.badgeRes(): Int = when (this) {
    PlaceCategory.VET -> R.string.place_badge_vet
    PlaceCategory.GROOMING -> R.string.place_badge_grooming
    PlaceCategory.PARK -> R.string.place_badge_park
    PlaceCategory.STORE -> R.string.place_badge_store
}

/**
 * "Vet clinic · Pulchowk" — what a place is and where. Falls back to the description alone when no
 * area was entered, rather than leaving a dangling separator.
 */
fun placeSubtitle(context: Context, place: Place): String {
    val description = context.getString(place.category.descriptionRes())
    val area = place.area?.trim().orEmpty()
    return if (area.isEmpty()) {
        description
    } else {
        context.getString(R.string.place_subtitle_format, description, area)
    }
}

/**
 * A distance to a place: "1.2 km", "0.8 km", "14 km".
 *
 * One decimal place under 10 km, where the tenth is the difference between a five- and a
 * fifteen-minute walk; none beyond that, where it is noise — the figure is a straight line, not a
 * route, and "23.4 km" would claim a precision it does not have.
 *
 * [NumberFormat] so the decimal separator is the locale's own ("1,2 km" in much of Europe).
 */
fun formatDistanceKm(context: Context, km: Double): String {
    val format = NumberFormat.getNumberInstance().apply {
        val decimals = if (km < 10) 1 else 0
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }
    return context.getString(R.string.place_distance_km, format.format(km))
}

/**
 * A tick label on the distance axis: "0.5 km", "1 km", "1.5 km".
 *
 * Unlike [formatDistanceKm], whole numbers drop their ".0" — ticks are round numbers by construction
 * (see util.GeoDistance.tickStepKm), and "1.0 km, 2.0 km" along a short axis is clutter.
 */
fun formatAxisKm(context: Context, km: Double): String {
    val format = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
    }
    return context.getString(R.string.place_distance_km, format.format(km))
}
