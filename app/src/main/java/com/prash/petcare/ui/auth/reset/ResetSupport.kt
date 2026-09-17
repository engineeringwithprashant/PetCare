package com.prash.petcare.ui.auth.reset

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.auth.PasswordResetResult

/**
 * Helpers shared by the two reset screens' ViewModels: ResetRequestViewModel and
 * ResetSentViewModel.
 *
 * None of this uses an Android type, and it returns string *resource ids* rather than text. That
 * keeps Context out of the ViewModels, as the project's MVVM rules require. The fragment turns the
 * id into text when it draws the screen. It also means these helpers run in plain JVM unit tests
 * (ResetSupportTest).
 */

/**
 * The message to show for a failed reset request, or null when the request succeeded.
 *
 * [PasswordResetResult.InvalidEmail] gets a general sentence here, for the confirmation screen,
 * which has no email field. The request screen handles that case itself and puts the error under
 * the field instead.
 */
@StringRes
fun PasswordResetResult.failureMessage(): Int? = when (this) {
    PasswordResetResult.Sent -> null
    PasswordResetResult.NoNetwork -> R.string.reset_error_network
    PasswordResetResult.RateLimited -> R.string.reset_error_rate_limited
    PasswordResetResult.InvalidEmail -> R.string.reset_error_invalid_email
    PasswordResetResult.Failed -> R.string.reset_error_unknown
}

/**
 * The arithmetic behind the confirmation screen's "RESEND IN 0:42" countdown.
 *
 * **Why a cooldown.** Without one, someone could tap "RESEND" over and over and fill a person's
 * inbox, and would soon hit Firebase's own rate limit. A short wait after each send prevents both.
 *
 * The cooldown is stored as the time it ends ("ready at"), not as seconds remaining. The time it
 * ends stays correct if the screen is rotated or the app is killed and restored. A remaining-seconds
 * number would have to be updated every second to stay right.
 */
object ResendCooldown {

    /** How long "RESEND" stays disabled after each send. */
    const val DURATION_MILLIS = 60_000L

    /**
     * Milliseconds until resending is allowed, between 0 and [DURATION_MILLIS].
     *
     * The upper limit matters if the phone's clock is set backwards while the countdown is running.
     * Without it, the gap between "now" and "ready at" could become hours and lock the link.
     */
    fun millisLeft(nowMillis: Long, readyAtMillis: Long): Long =
        (readyAtMillis - nowMillis).coerceIn(0L, DURATION_MILLIS)

    /**
     * Whole seconds to show, rounded **up**. With 400 ms left the link still reads "0:01". Rounding
     * down would show "0:00" while the link is still disabled.
     */
    fun secondsToShow(millisLeft: Long): Int = ((millisLeft + 999L) / 1000L).toInt()
}
