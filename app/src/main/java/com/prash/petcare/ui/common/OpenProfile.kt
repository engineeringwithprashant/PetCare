package com.prash.petcare.ui.common

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.prash.petcare.R

/** Opens the profile screen — what pressing the app's monogram in the shared header does. */
fun Fragment.openProfile() {
    findNavController().navigate(R.id.action_global_to_profile)
}
