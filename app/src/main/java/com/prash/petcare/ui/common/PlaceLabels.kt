package com.prash.petcare.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.PlaceCategory
import java.text.NumberFormat

/* Turns saved-place values into text for the screen: category names in their three lengths. */

/** The category's name on a filter chip and in the form's dropdown: "Vet", "Grooming". */
@StringRes
fun PlaceCategory.labelRes(): Int = when (this) {
    PlaceCategory.VET -> R.string.place_category_vet
    PlaceCategory.GROOMING -> R.string.place_category_grooming
    PlaceCategory.PARK -> R.string.place_category_park
    PlaceCategory.STORE -> R.string.place_category_store
}

/** The longer description used under a place's name: "Vet clinic", "Pet store". */
@StringRes
fun PlaceCategory.descriptionRes(): Int = when (this) {
    PlaceCategory.VET -> R.string.place_category_vet_description
    PlaceCategory.GROOMING -> R.string.place_category_grooming_description
    PlaceCategory.PARK -> R.string.place_category_park_description
    PlaceCategory.STORE -> R.string.place_category_store_description
}

/** The emoji that stands for a category: 🏥 vet, ✂️ grooming, 🌳 park, 🏪 store. */
@StringRes
fun PlaceCategory.emojiRes(): Int = when (this) {
    PlaceCategory.VET -> R.string.place_emoji_vet
    PlaceCategory.GROOMING -> R.string.place_emoji_grooming
    PlaceCategory.PARK -> R.string.place_emoji_park
    PlaceCategory.STORE -> R.string.place_emoji_store
}

/** The category as it appears where it is chosen — "🏥 Vet" on the filter and form chips. */
fun PlaceCategory.emojiLabel(context: Context): String = context.getString(
    R.string.category_emoji_label,
    context.getString(emojiRes()),
    context.getString(labelRes())
)

/** "Vet clinic · Pulchowk" — what a place is and where. */
fun placeSubtitle(context: Context, place: Place): String {
    val description = context.getString(place.category.descriptionRes())
    val area = place.area?.trim().orEmpty()
    return if (area.isEmpty()) {
        description
    } else {
        context.getString(R.string.place_subtitle_format, description, area)
    }
}

/** A distance to a place: "1.2 km", "0.8 km", "14 km". */
fun formatDistanceKm(context: Context, km: Double): String {
    val format = NumberFormat.getNumberInstance().apply {
        val decimals = if (km < 10) 1 else 0
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }
    return context.getString(R.string.place_distance_km, format.format(km))
}

/** A tick label on the distance axis: "0.5 km", "1 km", "1.5 km". */
fun formatAxisKm(context: Context, km: Double): String {
    val format = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 1
    }
    return context.getString(R.string.place_distance_km, format.format(km))
}
