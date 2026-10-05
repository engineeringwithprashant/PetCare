package com.prash.petcare.ui.auth.reset

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.auth.PasswordResetResult

/** Helpers shared by the two reset screens' ViewModels: ResetRequestViewModel and ResetSentViewModel. */

/** The message to show for a failed reset request, or null when the request succeeded. */
@StringRes
fun PasswordResetResult.failureMessage(): Int? = when (this) {
    PasswordResetResult.Sent -> null
    PasswordResetResult.NoNetwork -> R.string.reset_error_network
    PasswordResetResult.RateLimited -> R.string.reset_error_rate_limited
    PasswordResetResult.InvalidEmail -> R.string.reset_error_invalid_email
    PasswordResetResult.Failed -> R.string.reset_error_unknown
}

/** The arithmetic behind the confirmation screen's "RESEND IN 0:42" countdown. */
object ResendCooldown {

    /** How long "RESEND" stays disabled after each send. */
    const val DURATION_MILLIS = 60_000L

    /** Milliseconds until resending is allowed, between 0 and [DURATION_MILLIS]. */
    fun millisLeft(nowMillis: Long, readyAtMillis: Long): Long =
        (readyAtMillis - nowMillis).coerceIn(0L, DURATION_MILLIS)

    /** Whole seconds to show, rounded up. With 400 ms left the link still reads "0:01". */
    fun secondsToShow(millisLeft: Long): Int = ((millisLeft + 999L) / 1000L).toInt()
}
