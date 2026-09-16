package com.prash.petcare.ui.common

import android.view.View
import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewTabHeaderBinding
import java.text.NumberFormat

/*
 * Wires up the header shared by the redesigned tabs and the pet detail screen
 * (res/layout/view_tab_header.xml), including the two Home-only extras — the "AI" pill and the unread
 * dot — which stay hidden unless a screen asks for them.
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
 * @param onBadgeClick What pressing the pet-count badge does — every screen opens the notifications
 *   screen. Null on the notifications screen itself, which unhooks the badge: see below.
 * @param onMonogramClick What pressing the app's monogram does — every screen opens the profile
 *   screen, through [openProfile]. Null on the profile screen itself, which unhooks it for the same
 *   reason the notifications screen unhooks the badge.
 */
fun ViewTabHeaderBinding.setUp(
    @StringRes title: Int,
    onBadgeClick: (() -> Unit)?,
    onMonogramClick: (() -> Unit)?
) {
    setUp(
        title = root.context.getString(title),
        onBadgeClick = onBadgeClick,
        onMonogramClick = onMonogramClick
    )
}

/**
 * The same, for a title that is data rather than a fixed resource — the pet detail screen shows the
 * pet's first name here. The resource version above simply resolves its string and calls this one, so
 * both routes draw the toggle and attach the taps identically.
 *
 * The title can be set again later with [showTitle] once the data it depends on has loaded, without
 * re-attaching the listeners.
 */
fun ViewTabHeaderBinding.setUp(
    title: CharSequence,
    onBadgeClick: (() -> Unit)?,
    onMonogramClick: (() -> Unit)?
) {
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

    // A null action unhooks the badge entirely rather than attaching a listener that does nothing:
    // the notifications screen is the only caller that passes one, and a badge that opens the screen
    // already being looked at is a dead end. Clickable and focusable are both cleared, so the badge
    // also stops taking a ripple and stops being a stop on the keyboard and TalkBack focus order —
    // the count stays, as a fact rather than a control. The same reasoning hides the "Profile" item
    // from the overflow menu while the profile screen is open (MainActivity.onPrepareOptionsMenu).
    if (onBadgeClick != null) {
        petCountBadge.setOnClickListener { onBadgeClick() }
    } else {
        petCountBadge.setOnClickListener(null)
        petCountBadge.isClickable = false
        petCountBadge.isFocusable = false
    }

    // The same treatment for the monogram, and for the same reason: on the profile screen it would
    // reopen the screen already being looked at. Unhooking it clears the ripple, takes it out of the
    // keyboard and TalkBack focus order, and removes the "Open your profile" label — a description
    // promising an action the view no longer has would be worse than the mark being silent. The
    // layout's own `importantForAccessibility` is left alone; clearing the description is enough,
    // since an unfocusable, unlabelled decorative square is already skipped.
    if (onMonogramClick != null) {
        monogram.setOnClickListener { onMonogramClick() }
    } else {
        monogram.setOnClickListener(null)
        monogram.isClickable = false
        monogram.isFocusable = false
        monogram.contentDescription = null
    }
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
 * **Two descriptions, chosen by whether the badge does anything.** A screen reader announces the
 * description *instead of* the label, so on every screen but one it also has to carry the action
 * ("2 pets in care. Open notifications."). On the notifications screen the badge was unhooked by
 * [setUp], and promising an action it no longer has would be worse than saying nothing — so the plain
 * count is announced there instead. Read from the badge's own clickable state rather than from a
 * second parameter, so the two can never be set to disagree.
 *
 * [NumberFormat] rather than `toString()`: `toString()` always produces Western Arabic digits,
 * where a device set to, say, Bengali or Persian expects the number in its own numerals.
 */
fun ViewTabHeaderBinding.showPetCount(count: Int) {
    petCountBadge.text = NumberFormat.getInstance().format(count)
    petCountBadge.contentDescription = root.resources.getQuantityString(
        if (petCountBadge.isClickable) R.plurals.cd_pets_badge else R.plurals.cd_pets_badge_plain,
        count,
        count
    )
}

/**
 * Shows the "AI" pill and attaches what pressing it does. Only the Home tab calls this; on every other
 * screen the pill stays gone, as view_tab_header.xml declares it.
 */
fun ViewTabHeaderBinding.showAssistantPill(onClick: () -> Unit) {
    aiPill.visibility = View.VISIBLE
    aiPill.setOnClickListener { onClick() }
}

/**
 * Shows the badge's pet count with the red dot for overdue care — the Home tab's version of
 * [showPetCount].
 *
 * The dot is a shape and cannot be read aloud, so when it shows, the badge's description gains a
 * sentence saying what it means ("2 pets in care. Open your account. 1 task is overdue."). Both the dot
 * and the description are set in either direction on every call, because the count changes as tasks
 * are ticked off and as their times pass.
 *
 * @param overdueCount How many of today's tasks are past their time and not done; 0 hides the dot.
 */
fun ViewTabHeaderBinding.showPetCount(count: Int, overdueCount: Int) {
    showPetCount(count)

    unreadDot.visibility = if (overdueCount > 0) View.VISIBLE else View.GONE
    if (overdueCount > 0) {
        val resources = root.resources
        petCountBadge.contentDescription = resources.getString(
            R.string.cd_badge_with_overdue,
            petCountBadge.contentDescription,
            resources.getQuantityString(R.plurals.cd_overdue_tasks, overdueCount, overdueCount)
        )
    }
}
