package com.prash.petcare.ui.common

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.fragment.app.Fragment

/**
 * Hands a web address to whichever app on the device wants it.
 *
 * Shared by the two Places screens, which between them open four Google Maps links — directions, a
 * listing, and anything the user taps inside an embedded map. One helper rather than four copies of
 * the same three lines, so "a map link leaves the app" is stated once.
 *
 * **Why this reaches the Maps app and not only a browser.** Google Maps registers
 * `google.com/maps/...` as an Android App Link, so a plain `ACTION_VIEW` on an `https` URL is
 * delivered to the app when it is installed and to the browser when it is not. Building a `geo:` URI
 * instead would reach a maps app but lose the `dir/?api=1` request for *directions*, arriving at a
 * dropped pin the user then has to press "Directions" on themselves.
 *
 * **Why the launch is attempted and the failure caught,** rather than checked first with
 * `resolveActivity`: from Android 11 that check only sees apps declared in a `<queries>` block, and
 * would report "nothing can open this" on a phone that has both Maps and Chrome.
 *
 * @return true when something opened, false when the device has no app for the link — which is rare
 *   but real on a stripped-down build with no browser, and which the caller reports rather than
 *   letting the tap do nothing.
 */
fun Fragment.openExternalLink(url: String): Boolean = try {
    // NEW_TASK so the link opens as its own entry in the recents list. Without it the browser or
    // Maps would be stacked on top of PetCare, and the back gesture from there would land the user
    // on this screen instead of where they came from.
    startActivity(
        Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
    true
} catch (e: ActivityNotFoundException) {
    false
}
