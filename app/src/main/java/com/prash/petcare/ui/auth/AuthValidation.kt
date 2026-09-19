package com.prash.petcare.ui.auth

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.util.PhoneNumbers

/**
 * The input rules for every sign-in screen: email, password, and "do the two passwords match".
 *
 * **Where this sits in MVVM.** These rules are business logic, so they belong to the ViewModel layer,
 * not to a fragment. They are pulled out of any one ViewModel into this object because more than one
 * screen needs the same rules — [AuthViewModel] (log in / register),
 * [com.prash.petcare.ui.auth.reset.ResetRequestViewModel] (the reset email address) and
 * [com.prash.petcare.ui.profile.ChangePasswordViewModel] — and a rule written twice is a rule that
 * will one day disagree with itself.
 *
 * **Why a plain object with no Android types.** Every function takes strings and returns a string
 * *resource id* (or null for "fine"). Nothing here touches a Context or a View, so the rules run in an
 * ordinary JVM unit test with no emulator — see `AuthValidationTest`. The fragment turns the returned
 * id into text at display time.
 *
 * **Phone numbers are no longer here.** They moved to [PhoneNumbers], which checks a number against
 * the country the user picked rather than against one hand-written pattern — see the note at the top
 * of that file for why a pattern of our own was the wrong tool.
 */
object AuthValidation {

    /** Shortest password the app accepts. Also the first line of the checklist under the field. */
    const val MIN_PASSWORD_LENGTH = 8

    /**
     * Longest password the app accepts.
     *
     * A maximum on a *hashed* credential is not about storage — a hash is the same size whatever
     * goes into it. It is about the things a very long value passes through on its way: a field
     * without an upper bound accepts a pasted megabyte, and every layer that then handles it (the
     * network call, Firebase's own limits, the error paths) is being asked to cope with an input
     * nobody designed for. 64 characters is comfortably longer than any password a person types or a
     * password manager generates, so the cap is invisible in ordinary use.
     */
    const val MAX_PASSWORD_LENGTH = 64

    /**
     * A pragmatic email check: some text, an `@`, a dotted domain, no spaces.
     *
     * `android.util.Patterns.EMAIL_ADDRESS` is the obvious alternative, but it belongs to the Android
     * framework and is null in plain JVM unit tests. A fully RFC-compliant pattern is not the goal
     * either: the only real proof an address works is mail arriving at it, so this just catches typos.
     */
    private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$")

    /**
     * What counts as a "special character": anything that is not a letter, a digit or whitespace.
     *
     * Defined by exclusion rather than as a list of allowed symbols, so a password containing `£`,
     * `—` or a symbol from a non-Latin keyboard counts just as `!` does. A fixed list would quietly
     * tell somebody their perfectly good password has no special character in it.
     */
    private fun Char.isSpecial(): Boolean = !isLetterOrDigit() && !isWhitespace()

    /** Returns the error for an email address, or null when it is acceptable. */
    @StringRes
    fun validateEmail(email: String): Int? = when {
        email.isBlank() -> R.string.error_email_required
        !isEmail(email) -> R.string.error_email_invalid
        else -> null
    }

    /**
     * Which of the password rules a password currently satisfies.
     *
     * This is what the live checklist under a password field is drawn from: it describes the password
     * as it stands rather than saying only whether it passes, so each line can tick itself off as the
     * user types instead of the whole thing failing at the moment they press the button.
     *
     * @property hasLength Between [MIN_PASSWORD_LENGTH] and [MAX_PASSWORD_LENGTH] characters.
     * @property hasUppercase At least one capital letter.
     * @property hasLowercase At least one small letter.
     * @property hasDigit At least one number.
     * @property hasSpecial At least one symbol — see [isSpecial] for what counts.
     * @property isNotPredictable Something has been typed, and it is not a single character repeated
     *   ("aaaaaaaa") nor a straight run up or down the alphabet or the digits ("abcdefgh",
     *   "87654321"). See [isPredictable].
     */
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

    /**
     * Measures [password] against each rule.
     *
     * An empty password satisfies nothing, including the predictability rule — which is a statement
     * about the checklist rather than about blank passwords. The list is a set of things still to do,
     * so an untouched form should show six outstanding items; a single line already ticked before a
     * key has been pressed reads as progress the user has not made. The rule turns green the moment
     * anything is typed that is not a repeated or sequential run.
     */
    fun requirementsFor(password: String): PasswordRequirements = PasswordRequirements(
        hasLength = password.length in MIN_PASSWORD_LENGTH..MAX_PASSWORD_LENGTH,
        hasUppercase = password.any { it.isUpperCase() },
        hasLowercase = password.any { it.isLowerCase() },
        hasDigit = password.any { it.isDigit() },
        hasSpecial = password.any { it.isSpecial() },
        isNotPredictable = password.isNotEmpty() && !isPredictable(password)
    )

    /**
     * Returns the error for a *newly chosen* password, or null when it is acceptable.
     *
     * Only for creating or resetting a password. Signing in checks nothing but emptiness — see
     * [AuthViewModel.login] — because an older account may predate the current rules, and refusing to
     * even *attempt* a sign-in with a password the account genuinely has would lock its owner out of
     * their own data.
     *
     * The rules are reported one at a time, nearest problem first, so the message under the field
     * names a single thing to fix. The checklist above it is what shows the whole picture; this is
     * the fallback for a form submitted without reading it.
     */
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
     * True when the password is *entirely* one character repeated, or one unbroken run up or down
     * the alphabet or the digits.
     *
     * **Why this rule exists on top of the four variety rules.** In practice it rarely fires — a
     * password needing an upper case letter, a lower case letter, a digit and a symbol can hardly be
     * a straight run of anything. It is here because the variety rules can be satisfied in ways that
     * still leave a password guessable, and because the rule is cheap, exact, and never rejects
     * anything a person would have chosen deliberately. It refuses only the whole password being a
     * pattern, never a pattern *inside* one: "Abcd1234!" contains two runs and is accepted, which is
     * right — judging fragments would start rejecting ordinary passwords for containing "123".
     *
     * Comparison is case-insensitive so "ABCDEFGH" and "abcdefgh" are treated alike, and a password
     * of one or two characters is left to the length rule rather than being called a pattern.
     */
    private fun isPredictable(password: String): Boolean {
        if (password.length < 3) return false
        val normalised = password.lowercase()

        // Every character the same: "aaaaaaaa", "88888888".
        if (normalised.all { it == normalised[0] }) return true

        // One unbroken run: each character exactly one code point above the last, or exactly one
        // below. Letters and digits both step by one in Unicode, so a single comparison covers both.
        val step = normalised[1].code - normalised[0].code
        if (step != 1 && step != -1) return false
        return normalised.zipWithNext().all { (first, second) -> second.code - first.code == step }
    }
}
