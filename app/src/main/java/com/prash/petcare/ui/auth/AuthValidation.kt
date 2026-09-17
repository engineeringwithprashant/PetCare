package com.prash.petcare.ui.auth

import androidx.annotation.StringRes
import com.prash.petcare.R

/**
 * The input rules for every sign-in screen: email, phone, password, and "do the two passwords match".
 *
 * **Where this sits in MVVM.** These rules are business logic, so they belong to the ViewModel layer,
 * not to a fragment. They are pulled out of any one ViewModel into this object because more than one
 * screen needs the same rules — [AuthViewModel] (log in / register) and
 * [com.prash.petcare.ui.auth.reset.ResetRequestViewModel] (the reset email address) — and a rule
 * written twice is a rule that will one day disagree with itself.
 *
 * **Why a plain object with no Android types.** Every function takes strings and returns a string
 * *resource id* (or null for "fine"). Nothing here touches a Context or a View, so the rules run in an
 * ordinary JVM unit test with no emulator — see `AuthValidationTest`. The fragment turns the returned
 * id into text at display time.
 */
object AuthValidation {

    /** Shortest password the app accepts. Also shown to the user as the field's placeholder. */
    const val MIN_PASSWORD_LENGTH = 8

    /**
     * The fewest and most digits a phone number may have.
     *
     * 15 is the ITU E.164 maximum for any international number. 7 is a conservative floor — the
     * shortest national numbers in use are around that length — so a real number is never rejected
     * while an obvious typo ("98") still is.
     */
    private const val MIN_PHONE_DIGITS = 7
    private const val MAX_PHONE_DIGITS = 15

    /**
     * A pragmatic email check: some text, an `@`, a dotted domain, no spaces.
     *
     * `android.util.Patterns.EMAIL_ADDRESS` is the obvious alternative, but it belongs to the Android
     * framework and is null in plain JVM unit tests. A fully RFC-compliant pattern is not the goal
     * either: the only real proof an address works is mail arriving at it, so this just catches typos.
     */
    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    /**
     * The characters a typed phone number may contain: an optional leading `+`, then a digit or an
     * opening bracket (for an area code, "(01) 552 1234"), then digits with spaces, dashes, dots or
     * brackets between them, which people naturally type as separators ("+977 981-234 5678"). The
     * digit count is checked separately, since a regex that also counted digits between arbitrary
     * separators would be far harder to read.
     */
    private val PHONE_PATTERN = Regex("^\\+?[0-9(][0-9 ().-]*$")

    /** Returns the error for an email address, or null when it is acceptable. */
    @StringRes
    fun validateEmail(email: String): Int? = when {
        email.isBlank() -> R.string.error_email_required
        !isEmail(email) -> R.string.error_email_invalid
        else -> null
    }

    /** Returns the error for a phone number, or null when it is acceptable. */
    @StringRes
    fun validatePhone(phone: String): Int? = when {
        phone.isBlank() -> R.string.error_phone_required
        !isPhone(phone) -> R.string.error_phone_invalid
        else -> null
    }

    /**
     * Returns the error for a *newly chosen* password, or null when it is acceptable.
     *
     * Only for creating or resetting a password. Signing in checks nothing but emptiness — see
     * [AuthViewModel.login] — because an older account may predate the current length rule.
     */
    @StringRes
    fun validateNewPassword(password: String): Int? = when {
        password.isEmpty() -> R.string.error_password_required
        password.length < MIN_PASSWORD_LENGTH -> R.string.error_password_too_short
        else -> null
    }

    /**
     * Returns the error for the "confirm password" field, or null when it matches.
     *
     * The mismatch is reported on the confirmation field rather than the first one: the user is being
     * asked to retype what they meant, so that is the field to look at again.
     */
    @StringRes
    fun validateConfirmation(password: String, confirmation: String): Int? = when {
        confirmation.isEmpty() -> R.string.error_confirm_password_required
        confirmation != password -> R.string.error_password_mismatch
        else -> null
    }

    /** True when [text] looks like an email address. Leading and trailing spaces are ignored. */
    fun isEmail(text: String): Boolean = EMAIL_PATTERN.matches(text.trim())

    /**
     * True when [text] looks like a phone number: only phone characters, and a plausible number of
     * digits once the separators are ignored.
     */
    fun isPhone(text: String): Boolean {
        val trimmed = text.trim()
        if (!PHONE_PATTERN.matches(trimmed)) return false
        val digitCount = trimmed.count { it.isDigit() }
        return digitCount in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS
    }
}
