package com.prash.petcare.ui.place

import android.content.Context
import androidx.core.view.isVisible
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewSelectedLocationBinding
import com.prash.petcare.util.Coordinates
import java.util.Locale

/**
 * Draws the "Selected location" card from a [LocationPickerState].
 *
 * An extension on the generated binding rather than a class, for the same reason the app's chip rows
 * are: there is no state to keep here. The card is a pure function of the picker's state, and both
 * Places forms call this with their own binding — which is what stops the two screens describing the
 * same selection in two different ways.
 *
 * **Why the name line always says something.** Its three possible contents are not interchangeable:
 * "Finding address…" invites a moment's wait, a real name confirms the place, and "Dropped pin" says
 * plainly that this point has no name — which is a perfectly ordinary answer for the middle of a
 * field, and a very different message from a line that had simply gone blank.
 *
 * **What counts as "a real name" is decided before it gets here.** This function draws
 * `placeName` over `addressLine` and asks no questions about either, which is why a pin on a hospital
 * now reads "Patan Hospital" over "Lagankhel, Lalitpur" with no change to this file: the headline
 * became the business name in [com.prash.petcare.data.location.GeocodingRepository], where there is
 * both a POI lookup to ask and a distance to the pin to check it against. A card that tried to work
 * out which of two strings was the better name would be doing that with the one thing it does not
 * have — the knowledge of where each came from.
 *
 * The coordinates underneath are the last rung of that ladder and are never hidden, so the card still
 * answers "where is this?" when every name above it is blank.
 */
fun ViewSelectedLocationBinding.render(state: LocationPickerState) {
    val context = root.context
    val selected = state.selected

    // Nothing chosen yet: the card explains what to do rather than showing an empty frame. The pin
    // and the coordinates go with it — there is no point to mark and no figures to print.
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

/**
 * "27.6771° N, 85.3132° E".
 *
 * **Four decimal places**, which is about eleven metres — as fine as a phone's GPS honestly claims,
 * and fine enough to tell one doorway from the next. More digits would be a precision the figure does
 * not have; fewer would round two neighbouring shops onto the same point.
 *
 * **The hemisphere letters are derived rather than assumed.** The screen this replaced printed "° N"
 * and "° E" as fixed text, which is correct in Nepal and wrong in most of the world — a place saved in
 * Sydney would have been labelled as being in the northern hemisphere. The sign of each number decides
 * its letter, and the number itself is printed without the sign, since the letter now carries it.
 *
 * [Locale.US] so the numbers always use a decimal point, the way coordinates are written in every maps
 * app the user might compare them with. A locale-aware format would print "27,6771" in much of Europe,
 * where the comma is the character that separates latitude from longitude.
 */
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
