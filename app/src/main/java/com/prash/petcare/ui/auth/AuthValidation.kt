package com.prash.petcare.ui.auth

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.util.PhoneNumbers

/** The input rules for every sign-in screen: email, password, and "do the two passwords match". */
object AuthValidation {

    /** Shortest password the app accepts. Also the first line of the checklist under the field. */
    const val MIN_PASSWORD_LENGTH = 8

    /** Longest password the app accepts. */
    const val MAX_PASSWORD_LENGTH = 64

    /** A pragmatic email check: some text, an `@`, a dotted domain, no spaces. */
    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    /** What counts as a "special character": anything that is not a letter, a digit or whitespace. */
    private fun Char.isSpecial(): Boolean = !isLetterOrDigit() && !isWhitespace()

    /** Returns the error for an email address, or null when it is acceptable. */
    @StringRes
    fun validateEmail(email: String): Int? = when {
        email.isBlank() -> R.string.error_email_required
        !isEmail(email) -> R.string.error_email_invalid
        else -> null
    }

    /** Which of the password rules a password currently satisfies. */
    data class PasswordRequirements(
        val hasLength: Boolean = false,
        val hasUppercase: Boolean = false,
        val hasLowercase: Boolean = false,
        val hasDigit: Boolean = false,
        val hasSpecial: Boolean = false,
        val isNotPredictable: Boolean = false
    ) {

        /** True only when every rule is satisfied, which is exactly when the form will accept it. */
        val allMet: Boolean
            get() = hasLength && hasUppercase && hasLowercase && hasDigit && hasSpecial &&
                isNotPredictable
    }

    /** Measures [password] against each rule. */
    fun requirementsFor(password: String): PasswordRequirements = PasswordRequirements(
        hasLength = password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH,
        hasUppercase = password.any { it.isUpperCase() },
        hasLowercase = password.any { it.isLowerCase() },
        hasDigit = password.any { it.isDigit() },
        hasSpecial = password.any { it.isSpecial() },
        isNotPredictable = password.isNotEmpty() && !isPredictable(password)
    )

    /** Returns the error for a newly chosen password, or null when it is acceptable. */
    @StringRes
    fun validateNewPassword(password: String): Int? {
        val requirements = requirementsFor(password)
        return when {
            password.isEmpty() -> R.string.error_password_required
            password.length < MIN_PASSWORD_LENGTH -> R.string.error_password_too_short
            password.length > MAX_PASSWORD_LENGTH -> R.string.error_password_too_long
            !requirements.hasUppercase || !requirements.hasLowercase ||
                !requirements.hasDigit || !requirements.hasSpecial ->
                R.string.error_password_missing_variety

            !requirements.isNotPredictable -> R.string.error_password_predictable
            else -> null
        }
    }

    /** Returns the error for the "confirm password" field, or null when it matches. */
    @StringRes
    fun validateConfirmation(password: String, confirmation: String): Int? = when {
        confirmation.isEmpty() -> R.string.error_confirm_password_required
        confirmation != password -> R.string.error_password_mismatch
        else -> null
    }

    /** True when [text] looks like an email address. Leading and trailing spaces are ignored. */
    fun isEmail(text: String): Boolean = EMAIL_PATTERN.matches(text.trim())

    /** True when the password is entirely one character repeated. */
    private fun isPredictable(password: String): Boolean {
        if (password.length < 3) return false
        val normalised = password.lowercase()

        // Every character the same: "aaaaaaaa", "88888888".
        if (normalised.all { it == normalised[0] }) return true

        // One unbroken run: each character exactly one code point above the last, or exactly one below.
        val step = normalised[1].code - normalised[0].code
        if (step != 1 && step != -1) return false
        return normalised.zipWithNext().all { (first, second) -> second.code - first.code == step }
    }
}
