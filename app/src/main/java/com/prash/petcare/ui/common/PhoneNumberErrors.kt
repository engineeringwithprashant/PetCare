package com.prash.petcare.ui.common

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.util.PhoneCheck

/**
 * Turns the result of a phone-number check into the message a form shows.
 *
 * **Why this is a file of its own rather than a method on [PhoneCheck].** [com.prash.petcare.util.PhoneNumbers]
 * lives in `util`, beside the other pure helpers, and nothing in that package knows the app's
 * wording or references `R` — so the rules stay testable on the JVM and reusable by any screen. The
 * *wording* is a UI decision, and this is where UI decisions live. It is still only a resource id, so
 * a ViewModel can call it without holding a Context, exactly as it does with
 * [com.prash.petcare.ui.auth.AuthValidation].
 *
 * @param requireNumber Whether an empty field is an error on this particular form. Registration
 *   insists on a number and passes true; the profile form and the place form treat it as optional and
 *   pass false, which is the one part of the rule that genuinely differs per screen.
 */
@StringRes
fun PhoneCheck.messageRes(requireNumber: Boolean): Int? = when (this) {
    PhoneCheck.EMPTY -> if (requireNumber) R.string.error_phone_required else null
    PhoneCheck.INVALID -> R.string.error_phone_invalid
    PhoneCheck.VALID -> null
}
