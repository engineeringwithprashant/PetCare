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
 * manual check misses — a phone number typed with dashes, a password exactly at the minimum length —
 * where a wrong rule would either lock a real user out or let a bad value through.
 */
class AuthValidationTest {

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
    // Phone
    // ---------------------------------------------------------------------------------------

    @Test
    fun `international and local numbers with common separators are accepted`() {
        listOf("+977 9812345678", "9812345678", "+977-981-234-5678", "(01) 552 1234", "+1 415.555.0100")
            .forEach { assertNull(it, AuthValidation.validatePhone(it)) }
    }

    @Test
    fun `a blank phone asks for one`() {
        assertEquals(R.string.error_phone_required, AuthValidation.validatePhone(""))
    }

    @Test
    fun `too few or too many digits are rejected`() {
        assertEquals(R.string.error_phone_invalid, AuthValidation.validatePhone("98123"))
        assertEquals(R.string.error_phone_invalid, AuthValidation.validatePhone("+1234567890123456"))
    }

    @Test
    fun `letters and a plus in the middle are rejected`() {
        assertEquals(R.string.error_phone_invalid, AuthValidation.validatePhone("98123abcd45"))
        assertEquals(R.string.error_phone_invalid, AuthValidation.validatePhone("977+9812345678"))
    }

    @Test
    fun `an email is not mistaken for a phone, nor the other way round`() {
        assertFalse(AuthValidation.isPhone("prashant@petcare.app"))
        assertFalse(AuthValidation.isEmail("+977 9812345678"))
        assertTrue(AuthValidation.isPhone("  +977 9812345678  "))
    }

    // ---------------------------------------------------------------------------------------
    // Passwords
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a password exactly at the minimum length is accepted`() {
        val password = "a".repeat(AuthValidation.MIN_PASSWORD_LENGTH)
        assertNull(AuthValidation.validateNewPassword(password))
    }

    @Test
    fun `a password one short of the minimum is rejected`() {
        val password = "a".repeat(AuthValidation.MIN_PASSWORD_LENGTH - 1)
        assertEquals(R.string.error_password_too_short, AuthValidation.validateNewPassword(password))
    }

    @Test
    fun `an empty password asks for one`() {
        assertEquals(R.string.error_password_required, AuthValidation.validateNewPassword(""))
    }

    @Test
    fun `the confirmation must be present and match exactly`() {
        assertEquals(
            R.string.error_confirm_password_required,
            AuthValidation.validateConfirmation("biscuits1", "")
        )
        assertEquals(
            R.string.error_password_mismatch,
            AuthValidation.validateConfirmation("biscuits1", "Biscuits1")
        )
        assertNull(AuthValidation.validateConfirmation("biscuits1", "biscuits1"))
    }
}
