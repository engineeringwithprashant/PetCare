package com.prash.petcare.ui.auth

import com.prash.petcare.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AuthValidation], the rules behind every inline error on the sign-in screens.
 *
 * Plain JVM tests, possible because the rules use no Android classes. They guard the cases a quick
 * manual check misses — a password exactly at the minimum length, one that meets every variety rule
 * but is still a straight run — where a wrong rule would either lock a real user out or let a bad
 * value through.
 *
 * Phone numbers are no longer tested here: they moved to `PhoneNumbersTest`, alongside the rules
 * themselves.
 */
class AuthValidationTest {

    /** A password satisfying all six rules, used wherever the test is about something else. */
    private val goodPassword = "Biscuits1!"

    // ---------------------------------------------------------------------------------------
    // Email
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a normal address is accepted`() {
        assertNull(AuthValidation.validateEmail("prashant@petcare.app"))
    }

    @Test
    fun `a blank address asks for one`() {
        assertEquals(R.string.error_email_required, AuthValidation.validateEmail("   "))
    }

    @Test
    fun `addresses missing a part are rejected`() {
        listOf("prashant", "prashant@", "@petcare.app", "prashant@petcare", "pra shant@petcare.app")
            .forEach { assertEquals(it, R.string.error_email_invalid, AuthValidation.validateEmail(it)) }
    }

    // ---------------------------------------------------------------------------------------
    // Passwords: the whole rule
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a password meeting every rule is accepted`() {
        assertNull(AuthValidation.validateNewPassword(goodPassword))
    }

    @Test
    fun `a password exactly at the minimum length is accepted`() {
        // Exactly MIN_PASSWORD_LENGTH characters, and still carrying all four kinds.
        val password = "Ab1!" + "cD2?".take(AuthValidation.MIN_PASSWORD_LENGTH - 4)
        assertEquals(AuthValidation.MIN_PASSWORD_LENGTH, password.length)
        assertNull(AuthValidation.validateNewPassword(password))
    }

    @Test
    fun `a password one short of the minimum is rejected for length`() {
        val password = goodPassword.take(AuthValidation.MIN_PASSWORD_LENGTH - 1)
        assertEquals(R.string.error_password_too_short, AuthValidation.validateNewPassword(password))
    }

    @Test
    fun `a password past the maximum length is rejected`() {
        // One character too long, built so nothing but the length rule can be what refuses it.
        val password = "Aa1!" + "x".repeat(AuthValidation.MAX_PASSWORD_LENGTH - 3)
        assertEquals(AuthValidation.MAX_PASSWORD_LENGTH + 1, password.length)
        assertEquals(R.string.error_password_too_long, AuthValidation.validateNewPassword(password))
    }

    @Test
    fun `an empty password asks for one`() {
        assertEquals(R.string.error_password_required, AuthValidation.validateNewPassword(""))
    }

    @Test
    fun `a long password missing any one of the four kinds is rejected`() {
        listOf(
            "biscuits1!",   // no upper case
            "BISCUITS1!",   // no lower case
            "Biscuits!!",   // no digit
            "Biscuits11"    // no symbol
        ).forEach {
            assertEquals(
                it,
                R.string.error_password_missing_variety,
                AuthValidation.validateNewPassword(it)
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // Passwords: repeated and sequential runs
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a whole password that is one repeated or sequential run is rejected`() {
        // Each of these fails the variety rules too, which is why the *length* rule is checked first
        // and the variety rule second — so the message names the nearest problem, not this one.
        listOf("aaaaaaaa", "88888888", "abcdefgh", "87654321", "ABCDEFGH").forEach {
            assertFalse(it, AuthValidation.requirementsFor(it).isNotPredictable)
        }
    }

    @Test
    fun `a run inside an otherwise good password is not treated as a pattern`() {
        // "Abcd1234!" contains two runs, and is accepted: judging fragments rather than the whole
        // password would start rejecting ordinary passwords for merely containing "123".
        assertTrue(AuthValidation.requirementsFor("Abcd1234!").isNotPredictable)
        assertNull(AuthValidation.validateNewPassword("Abcd1234!"))
    }

    @Test
    fun `an untouched form has no rule already ticked`() {
        // The checklist is a list of things still to do, so a line ticked before a key has been
        // pressed would read as progress the user has not made.
        val requirements = AuthValidation.requirementsFor("")

        assertFalse(requirements.hasLength)
        assertFalse(requirements.hasUppercase)
        assertFalse(requirements.hasLowercase)
        assertFalse(requirements.hasDigit)
        assertFalse(requirements.hasSpecial)
        assertFalse(requirements.isNotPredictable)
    }

    @Test
    fun `the pattern rule is satisfied as soon as something ordinary is typed`() {
        assertTrue(AuthValidation.requirementsFor("B").isNotPredictable)
        assertTrue(AuthValidation.requirementsFor("Bis").isNotPredictable)
    }

    // ---------------------------------------------------------------------------------------
    // Passwords: the checklist's measurements
    // ---------------------------------------------------------------------------------------

    @Test
    fun `requirements report each rule separately`() {
        val requirements = AuthValidation.requirementsFor("Biscuit1")

        assertTrue(requirements.hasLength)
        assertTrue(requirements.hasUppercase)
        assertTrue(requirements.hasLowercase)
        assertTrue(requirements.hasDigit)
        assertFalse(requirements.hasSpecial)
        assertTrue(requirements.isNotPredictable)
        assertFalse(requirements.allMet)
    }

    @Test
    fun `allMet agrees with the rule the form enforces`() {
        // The checklist and the submit must never disagree — six green ticks over a form that then
        // refuses the password would be worse than no checklist at all.
        listOf(goodPassword, "short1!A", "biscuits", "", "Aa1!aaaa").forEach { password ->
            assertEquals(
                password,
                AuthValidation.validateNewPassword(password) == null,
                AuthValidation.requirementsFor(password).allMet
            )
        }
    }

    @Test
    fun `a symbol from outside the ASCII set still counts as one`() {
        // The rule is "not a letter, digit or space" rather than a list of allowed symbols, so a
        // password using a currency sign or a dash from another keyboard is not wrongly refused.
        assertTrue(AuthValidation.requirementsFor("Biscuits1£").hasSpecial)
        assertTrue(AuthValidation.requirementsFor("Biscuits1—").hasSpecial)
    }

    // ---------------------------------------------------------------------------------------
    // Confirmation
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the confirmation must be present and match exactly`() {
        assertEquals(
            R.string.error_confirm_password_required,
            AuthValidation.validateConfirmation(goodPassword, "")
        )
        assertEquals(
            R.string.error_password_mismatch,
            AuthValidation.validateConfirmation(goodPassword, goodPassword.lowercase())
        )
        assertNull(AuthValidation.validateConfirmation(goodPassword, goodPassword))
    }

    @Test
    fun `an email is not mistaken for something else`() {
        assertFalse(AuthValidation.isEmail("+977 9812345678"))
        assertTrue(AuthValidation.isEmail("  prashant@petcare.app  "))
    }
}
