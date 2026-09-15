package com.prash.petcare.ui.common

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewTabHeaderBinding
import java.text.NumberFormat

/*
 * Wires up the header shared by the redesigned tabs and the pet detail screen
 * (res/layout/view_tab_header.xml).
 *
 * In MVVM terms this is still part of the **View** layer: it only sets text, icons and click
 * listeners. It is a pair of extension functions on the generated [ViewTabHeaderBinding] rather than
 * a custom View subclass because there is no drawing or measuring to customise — only a few lines
 * of binding code that would otherwise be copied into both fragments and drift apart.
 *
 * Kept free of navigation on purpose: the fragment passes in what a tap on the badge should do, so
 * each screen decides its own navigation action and this file never needs a NavController.
 */

/**
 * Sets the title, draws the theme toggle for the mode in use, and attaches both controls' taps.
 *
 * Called once per view creation. Nothing can change the display mode while a tab is on screen
 * without AppCompat recreating the Activity, which brings a fresh view — and so a fresh call — here
 * anyway.
 *
 * **Why the display mode is read here rather than from a ViewModel.** The mode is neither this
 * account's data nor this screen's: it belongs to the installation, is read before any ViewModel
 * exists, and is applied by recreating the Activity. See [ThemePreference].
 *
 * @param title The screen's name, e.g. [R.string.nav_pets].
 * @param onBadgeClick What pressing the pet-count badge does — both tabs open the profile screen.
 */
fun ViewTabHeaderBinding.setUp(@StringRes title: Int, onBadgeClick: () -> Unit) {
    setUp(title = root.context.getString(title), onBadgeClick = onBadgeClick)
}

/**
 * The same, for a title that is data rather than a fixed resource — the pet detail screen shows the
 * pet's first name here. The resource version above simply resolves its string and calls this one, so
 * both routes draw the toggle and attach the taps identically.
 *
 * The title can be set again later with [showTitle] once the data it depends on has loaded, without
 * re-attaching the listeners.
 */
fun ViewTabHeaderBinding.setUp(title: CharSequence, onBadgeClick: () -> Unit) {
    val context = root.context
    showTitle(title)

    // The control reports the *present* mode rather than the one a tap would bring about, which is
    // how a light/dark switch is nearly always read. Its content description says the opposite,
    // because TalkBack announces the description in place of the label and so has to carry the
    // action instead of the state.
    val isDark = ThemePreference.isNightModeActive(context)

    themeToggleIcon.setImageResource(
        if (isDark) R.drawable.ic_theme_dark else R.drawable.ic_theme_light
    )
    themeToggleLabel.setText(if (isDark) R.string.home_theme_dark else R.string.home_theme_light)
    themeToggle.contentDescription = context.getString(
        if (isDark) R.string.cd_switch_to_light_theme else R.string.cd_switch_to_dark_theme
    )

    // Switching the mode recreates the Activity, so there is nothing to update afterwards — the
    // rebuilt screen calls this function again and draws the new mode.
    themeToggle.setOnClickListener { ThemePreference.toggle(context) }

    petCountBadge.setOnClickListener { onBadgeClick() }
}

/** Replaces the header's title — for a screen whose title arrives with its data. */
fun ViewTabHeaderBinding.showTitle(title: CharSequence) {
    headerTitle.text = title
}

/**
 * Shows how many pets the user has in the header badge.
 *
 * The badge shows the bare number because a circle that size has room for nothing else, so its
 * content description spells out what is being counted — otherwise a screen reader would announce
 * a lone digit.
 *
 * [NumberFormat] rather than `toString()`: `toString()` always produces Western Arabic digits,
 * where a device set to, say, Bengali or Persian expects the number in its own numerals.
 */
fun ViewTabHeaderBinding.showPetCount(count: Int) {
    petCountBadge.text = NumberFormat.getInstance().format(count)
    petCountBadge.contentDescription =
        root.resources.getQuantityString(R.plurals.cd_pets_badge, count, count)
}
