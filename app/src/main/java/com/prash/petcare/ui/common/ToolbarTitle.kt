package com.prash.petcare.ui.common

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/**
 * Sets the title shown in the app's top app bar.
 *
 * Most screens need nothing of the sort: NavigationUI already fills the bar from each destination's
 * `android:label` in the navigation graph. This is for the screens whose title depends on their
 * data — a pet's name, a routine's name, or whether a form is adding or editing — which a fixed
 * label in XML cannot express.
 *
 * Safe to call whenever the screen redraws. It runs after NavigationUI has applied the label, so
 * the dynamic title wins, and setting the same string twice does nothing.
 */
fun Fragment.setToolbarTitle(title: CharSequence) {
    (activity as? AppCompatActivity)?.supportActionBar?.title = title
}
