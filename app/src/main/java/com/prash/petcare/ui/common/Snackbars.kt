package com.prash.petcare.ui.common

import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R

/** Lifts a Snackbar clear of the floating bottom navigation bar. */
fun Snackbar.anchorAboveNavBar(fragment: Fragment): Snackbar = apply {
    fragment.activity
        ?.findViewById<View>(R.id.bottomNav)
        ?.takeIf { it.isVisible }
        ?.let { anchorView = it }
}
