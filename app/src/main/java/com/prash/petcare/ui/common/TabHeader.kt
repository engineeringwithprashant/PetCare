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

/* Wires up the header shared by the redesigned tabs and the pet detail screen (res/layout/view_tab_header.xml). */

/** Sets the title, draws the theme toggle for the mode in use, and attaches both controls' taps. */
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

/** The same, for a title that is data rather than a fixed resource. */
fun ViewTabHeaderBinding.setUp(
    title: CharSequence,
    onBadgeClick: (() -> Unit)?,
    onMonogramClick: (() -> Unit)?
) {
    val context = root.context
    showTitle(title)

    // The control reports the present mode rather than the one a tap would bring.
    val isDark = ThemePreference.isNightModeActive(context)

    themeToggleIcon.setImageResource(
        if (isDark) R.drawable.ic_theme_dark else R.drawable.ic_theme_light
    )
    themeToggleLabel.setText(if (isDark) R.string.home_theme_dark else R.string.home_theme_light)
    themeToggle.contentDescription = context.getString(
        if (isDark) R.string.cd_switch_to_light_theme else R.string.cd_switch_to_dark_theme
    )

    // Switching the mode recreates the Activity, so there is nothing to update afterwards.
    themeToggle.setOnClickListener { ThemePreference.toggle(context) }

    // A null action unhooks the bell entirely rather than attaching a listener that does nothing.
    if (onBadgeClick != null) {
        notificationsBell.setOnClickListener { onBadgeClick() }
    } else {
        notificationsBell.setOnClickListener(null)
        notificationsBell.isClickable = false
        notificationsBell.isFocusable = false
    }

    // The same treatment for the monogram, and for the same reason.
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

/** Draws the user's profile photo over the monogram, or falls back to the yellow "p". */
fun ViewTabHeaderBinding.showProfilePhoto(path: String?, scope: CoroutineScope): Job? {
    if (path == null) {
        monogramPhoto.setImageDrawable(null)
        monogramPhoto.isVisible = false
        return null
    }

    return scope.launch {
        val bitmap = PetPhotoStorage.load(path)
        monogramPhoto.setImageBitmap(bitmap)
        // Only reveal the photo once there is actually a bitmap behind.
        monogramPhoto.isVisible = bitmap != null
    }
}

/** Keeps this screen's header showing what the session knows — the signed-in user's photo on the monogram. */
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

/** Puts the number of unread alerts on the header's bell. */
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

/** Keeps this screen's bell showing the signed-in user's unread alert count, for as long as the screen is visible. */
private fun Fragment.bindHeaderAlertCount(header: ViewTabHeaderBinding, session: SessionViewModel) {
    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            session.unreadAlertCount.collect { header.showAlertCount(it) }
        }
    }
}

/** How high the bubble counts before it gives up and says "9+" — see [showAlertCount]. */
private const val MAX_SHOWN_COUNT = 9

/** Shows the "AI" pill and attaches what pressing it does. */
fun ViewTabHeaderBinding.showAssistantPill(onClick: () -> Unit) {
    aiPill.visibility = View.VISIBLE
    aiPill.setOnClickListener { onClick() }
}

/* The Home tab used to have a variant of the badge carrying a red dot for overdue care. */
