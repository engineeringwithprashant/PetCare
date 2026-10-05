package com.prash.petcare.ui.common

import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment

/** Sets the title shown in the app's top app bar. */
fun Fragment.setToolbarTitle(title: CharSequence) {
    (activity as? AppCompatActivity)?.supportActionBar?.title = title
}
