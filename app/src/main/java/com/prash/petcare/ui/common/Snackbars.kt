package com.prash.petcare.ui.common

import android.view.View
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R

/**
 * Lifts a Snackbar clear of the floating bottom navigation bar.
 *
 * **Why it is needed.** The bar is a genuine overlay — a card pinned to the bottom of the Activity's
 * FrameLayout, drawn *over* whatever screen is in the NavHostFragment (see activity_main.xml). A
 * Snackbar shown from inside a screen is a child of that screen's CoordinatorLayout, which is
 * underneath, so by default it appears behind the bar: the message is unreadable and any action on
 * it unreachable. That matters most for a Snackbar that carries one, such as the undo after a place
 * is saved.
 *
 * `anchorView` is Material's own answer to this — it places the Snackbar directly above the view
 * given rather than at the bottom of its parent — and it is set here rather than in each screen so
 * that "a message clears the navigation bar" is one fact about the app instead of a thing each new
 * screen has to remember.
 *
 * The bar is looked up on the **Activity**, because it does not belong to any fragment's hierarchy.
 * When it is not showing — the keyboard is open, or the screen is one of the sign-in screens that
 * hides it — no anchor is set and the Snackbar sits at the bottom of the screen, which is where it
 * should be when there is nothing in the way.
 *
 * Returns the same Snackbar, so it reads as part of the builder chain: `Snackbar.make(…)
 * .setAction(…) .anchorAboveNavBar(this) .show()`.
 */
fun Snackbar.anchorAboveNavBar(fragment: Fragment): Snackbar = apply {
    fragment.activity
        ?.findViewById<View>(R.id.bottomNav)
        ?.takeIf { it.isVisible }
        ?.let { anchorView = it }
}
