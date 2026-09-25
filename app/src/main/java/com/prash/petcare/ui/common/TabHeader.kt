package com.prash.petcare.ui.common

import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewTabHeaderBinding
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.PetPhotoStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    // A null action unhooks the bell entirely rather than attaching a listener that does nothing:
    // the notifications screen is the only caller that passes one, and a bell that opens the screen
    // already being looked at is a dead end. Clickable and focusable are both cleared, so the bell
    // also stops taking a ripple and stops being a stop on the keyboard and TalkBack focus order —
    // the bell and its count stay, as a statement rather than a control. The same reasoning hides the
    // "Profile" item from the overflow menu while the profile screen is open
    // (MainActivity.onPrepareOptionsMenu).
    if (onBadgeClick != null) {
        notificationsBell.setOnClickListener { onBadgeClick() }
    } else {
        notificationsBell.setOnClickListener(null)
        notificationsBell.isClickable = false
        notificationsBell.isFocusable = false
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
 * Draws the user's profile photo over the monogram, or falls back to the yellow "p".
 *
 * The header's mark is the app's letter until a photo has been set and the user's face afterwards —
 * see the note on the two stacked layers in view_tab_header.xml. Only visibility changes here: the
 * letter is always underneath, so it is already the right answer whenever there is no photo.
 *
 * **Three ways there can be no photo**, and all of them land on the letter: the account has none
 * ([path] is null), the file has been deleted from under the app (the decode returns null), or the
 * decode has not finished yet (the photo starts hidden).
 *
 * The decode touches the disk, so it runs in [scope] — callers pass `viewLifecycleOwner.lifecycleScope`,
 * so leaving the screen cancels a load rather than letting it finish and write into a destroyed view.
 * The previous load is cancelled first, which matters because this is called again every time the
 * photo changes: without it, two decodes could finish out of order and leave the older image showing.
 *
 * @param path Absolute path to the stored photo, or null when the account has none.
 * @param scope The scope the decode runs in, tied to the view's lifetime.
 */
fun ViewTabHeaderBinding.showProfilePhoto(path: String?, scope: CoroutineScope): Job? {
    if (path == null) {
        monogramPhoto.setImageDrawable(null)
        monogramPhoto.isVisible = false
        return null
    }

    return scope.launch {
        val bitmap = PetPhotoStorage.load(path)
        monogramPhoto.setImageBitmap(bitmap)
        // Only reveal the photo once there is actually a bitmap behind it; a visible but empty
        // ImageView would punch a blank square where the mark should be.
        monogramPhoto.isVisible = bitmap != null
    }
}

/**
 * Keeps this screen's header showing what the *session* knows — the signed-in user's photo on the
 * monogram, and their unread alert count on the bell — for as long as the screen is visible.
 *
 * **One call per screen, because neither value is any screen's data.** A dozen screens draw this
 * header and every one of them has to show the same mark and the same number. The photo is published by [SessionViewModel],
 * which every screen already shares, so each fragment adds this single line rather than nine
 * ViewModels each growing a query for the same value.
 *
 * **This is what makes a new photo appear immediately.** The flow it collects is backed by a Room
 * query, so saving a photo on the profile screen re-emits here and every header still in memory
 * redraws on the spot — there is nothing to refresh by hand and no need to restart the app.
 *
 * `repeatOnLifecycle(STARTED)` collects only while the screen is actually on screen, and the
 * in-flight decode is cancelled when a newer path arrives so two loads finishing out of order cannot
 * leave the older image showing.
 */
fun Fragment.bindHeaderFromSession(header: ViewTabHeaderBinding, session: SessionViewModel) {
    bindHeaderAlertCount(header, session)

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var load: Job? = null
            session.profilePhotoPath.collect { path ->
                load?.cancel()
                load = header.showProfilePhoto(path, this)
            }
        }
    }
}

/**
 * Puts the number of unread alerts on the header's bell.
 *
 * **Zero hides the bubble rather than drawing a "0".** A bell with nothing waiting should look quiet;
 * a nought on its corner is a mark the eye stops on to read a non-answer.
 *
 * **Past [MAX_SHOWN_COUNT] the bubble reads "9+".** Two digits in a 16dp badge are unreadable, and the
 * difference between eleven and twelve unread alerts changes nothing the user would do about it. The
 * exact figure is still announced, because a screen reader has room for it.
 *
 * **Two descriptions, chosen by whether the bell does anything.** A screen reader announces the
 * description *instead of* the label, so on every screen but one it also has to carry the action
 * ("3 unread alerts. Open notifications."). On the notifications screen the bell was unhooked by
 * [setUp], and promising an action it no longer has would be worse than saying nothing — so the plain
 * count is announced there instead. Read from the bell's own clickable state rather than from a second
 * parameter, so the two can never be set to disagree.
 *
 * [NumberFormat] rather than `toString()`: `toString()` always produces Western Arabic digits, where a
 * device set to, say, Bengali or Persian expects the number in its own numerals.
 */
fun ViewTabHeaderBinding.showAlertCount(count: Int) {
    alertCountBadge.isVisible = count > 0
    alertCountBadge.text = if (count > MAX_SHOWN_COUNT) {
        root.context.getString(R.string.header_alert_count_overflow, MAX_SHOWN_COUNT)
    } else {
        NumberFormat.getInstance().format(count)
    }

    notificationsBell.contentDescription = root.resources.getQuantityString(
        if (notificationsBell.isClickable) R.plurals.cd_alerts_bell else R.plurals.cd_alerts_bell_plain,
        count,
        count
    )
}

/**
 * Keeps this screen's bell showing the signed-in user's unread alert count, for as long as the screen
 * is visible.
 *
 * **One call per screen, because the count is not any screen's data.** A dozen screens draw this
 * header and every one of them has to show the same number. It is published by [SessionViewModel],
 * which every screen already shares and which derives it from the one [AlertRepository] the
 * Application holds — so the bell can never disagree with the list the notifications screen shows.
 *
 * Called from [bindHeaderFromSession] alongside the photo, so screens bind both in one line.
 */
private fun Fragment.bindHeaderAlertCount(header: ViewTabHeaderBinding, session: SessionViewModel) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.unreadAlertCount.collect { header.showAlertCount(it) }
        }
    }
}

/** How high the bubble counts before it gives up and says "9+" — see [showAlertCount]. */
private const val MAX_SHOWN_COUNT = 9

/**
 * Shows the "AI" pill and attaches what pressing it does. Only the Home tab calls this; on every other
 * screen the pill stays gone, as view_tab_header.xml declares it.
 */
fun ViewTabHeaderBinding.showAssistantPill(onClick: () -> Unit) {
    aiPill.visibility = View.VISIBLE
    aiPill.setOnClickListener { onClick() }
}

/*
 * The Home tab used to have a variant of the badge carrying a red dot for overdue care. It is gone,
 * and nothing replaced it: overdue tasks already produce "missed" alerts, which arrive unread, so the
 * number on the bell counts them. A dot meaning "some care is late" beside a figure that was already
 * counting that same care was two marks for one fact.
 */
