package com.prash.petcare.ui.place

import android.content.Context
import androidx.core.view.isVisible
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewSelectedLocationBinding
import com.prash.petcare.util.Coordinates
import java.util.Locale

/** Draws the "Selected location" card from a [LocationPickerState]. */
fun ViewSelectedLocationBinding.render(state: LocationPickerState) {
    val context = root.context
    val selected = state.selected

    // Nothing chosen yet: the card explains what to do rather than showing an empty frame.
    if (selected == null) {
        selectedNameText.setText(R.string.selected_location_empty)
        selectedAddressText.isVisible = false
        selectedCoordinatesText.isVisible = false
        selectedNoteText.isVisible = false
        selectedPinIcon.isVisible = false
        return
    }

    // Only for a fix the device gave with approximate access — see the note in the layout.
    selectedNoteText.isVisible = state.isApproximateFix

    selectedPinIcon.isVisible = true
    selectedCoordinatesText.isVisible = true
    selectedCoordinatesText.text = formatCoordinates(context, selected.coordinates)

    selectedNameText.text = when {
        !selected.placeName.isNullOrBlank() -> selected.placeName
        state.isResolvingAddress -> context.getString(R.string.selected_location_resolving)
        else -> context.getString(R.string.selected_location_unnamed)
    }

    selectedAddressText.isVisible = !selected.addressLine.isNullOrBlank()
    selectedAddressText.text = selected.addressLine.orEmpty()
}

/** "27.6771° N, 85.3132° E". */
fun formatCoordinates(context: Context, at: Coordinates): String = context.getString(
    R.string.map_coordinates_format,
    String.format(Locale.US, "%.4f", kotlin.math.abs(at.latitude)),
    context.getString(
        if (at.latitude >= 0) R.string.coordinate_north else R.string.coordinate_south
    ),
    String.format(Locale.US, "%.4f", kotlin.math.abs(at.longitude)),
    context.getString(
        if (at.longitude >= 0) R.string.coordinate_east else R.string.coordinate_west
    )
)
