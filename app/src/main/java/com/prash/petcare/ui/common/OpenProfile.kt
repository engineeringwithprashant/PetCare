package com.prash.petcare.ui.common

import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.prash.petcare.R

/**
 * Opens the profile screen — what pressing the app's monogram in the shared header does.
 *
 * **Why this is one function rather than a copy in each fragment.** Seven screens draw
 * res/layout/view_tab_header.xml and every one of them has to do exactly the same thing when the
 * monogram is pressed. Written out seven times it would be seven chances for one of them to drift —
 * to pop the back stack differently, or to be forgotten entirely when an eighth screen is added.
 *
 * It stays in the **View** layer, and deliberately in `ui/common` rather than in
 * [com.prash.petcare.ui.common.setUp]: TabHeader.kt is kept free of navigation on purpose, so that
 * the header knows nothing about the graph and each screen decides what its controls mean. Passing
 * this function in as that decision keeps both properties — no NavController inside TabHeader, and no
 * seven-fold duplication.
 *
 * **Why a global action.** The profile screen is reachable from seven destinations, and an ordinary
 * `<action>` belongs to a single one — so seven near-identical actions would have to be declared in
 * the graph and kept in step. A global action is the Navigation Component's answer to exactly this:
 * declared once at the graph's root, available from every destination in it. See
 * `action_global_to_profile` in res/navigation/nav_graph.xml.
 *
 * Nothing is popped on the way. The profile screen is a step *sideways* out of whichever tab the user
 * is on, like the notifications screen and the assistant, so back should return them to where they
 * were — which is what an ordinary forward navigation already does.
 */
fun Fragment.openProfile() {
    findNavController().navigate(R.id.action_global_to_profile)
}
