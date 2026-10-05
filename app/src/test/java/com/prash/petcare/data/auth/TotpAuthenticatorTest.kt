package com.prash.petcare.data.auth

import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.time.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [TotpAuthenticator]. */
class TotpAuthenticatorTest {

    /** A clock the test moves by hand. */
    private class FixedClock(var epochSeconds: Long) : TimeProvider {
        override fun getTime(): Long = epochSeconds
    }

    private val clock = FixedClock(START_SECONDS)
    private val authenticator = TotpAuthenticator(clock)

    /** A secret with no meaning beyond being valid Base32, so the tests do not depend on randomness. */
    private val secret = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"

    /** The code the protocol says is correct at [atSeconds], produced by the library's own generator. */
    private fun codeAt(atSeconds: Long): String =
        DefaultCodeGenerator().generate(secret, atSeconds / TotpAuthenticator.TIME_STEP_SECONDS)

    @Test
    fun `accepts the code for the current time step`() {
        assertTrue(authenticator.isCodeValid(secret, codeAt(START_SECONDS)))
    }

    @Test
    fun `accepts a code from the previous step, for a phone running slightly behind`() {
        val oneStepAgo = codeAt(START_SECONDS - TotpAuthenticator.TIME_STEP_SECONDS)

        assertTrue(authenticator.isCodeValid(secret, oneStepAgo))
    }

    @Test
    fun `accepts a code from the next step, for a phone running slightly ahead`() {
        val oneStepAhead = codeAt(START_SECONDS + TotpAuthenticator.TIME_STEP_SECONDS)

        assertTrue(authenticator.isCodeValid(secret, oneStepAhead))
    }

    /** The other half of the drift allowance: it has to stop somewhere. */
    @Test
    fun `refuses a code two steps old`() {
        val twoStepsAgo = codeAt(START_SECONDS - 2 * TotpAuthenticator.TIME_STEP_SECONDS)

        assertFalse(authenticator.isCodeValid(secret, twoStepsAgo))
    }

    @Test
    fun `a code stops working once the clock has moved past its window`() {
        val code = codeAt(START_SECONDS)

        // Two steps on, the code that was correct at the start is outside the window either side.
        clock.epochSeconds = START_SECONDS + 2 * TotpAuthenticator.TIME_STEP_SECONDS

        assertFalse(authenticator.isCodeValid(secret, code))
    }

    @Test
    fun `refuses a code belonging to a different secret`() {
        val otherSecret = "KRSXG5DFMZSXIZLSEBSGKZBAKRSXG5DF"
        val theirCode = DefaultCodeGenerator()
            .generate(otherSecret, START_SECONDS / TotpAuthenticator.TIME_STEP_SECONDS)

        assertFalse(authenticator.isCodeValid(secret, theirCode))
    }

    /** Anything that is not six digits is turned away without reaching the library. */
    @Test
    fun `refuses input that is not a six-digit code`() {
        listOf("", "12345", "1234567", "12a456", "      ").forEach { notACode ->
            assertFalse("expected '$notACode' to be refused", authenticator.isCodeValid(secret, notACode))
        }
    }

    /** A blank secret cannot check anything, and must not throw on the way to saying so. */
    @Test
    fun `refuses any code when the secret is blank`() {
        assertFalse(authenticator.isCodeValid("", codeAt(START_SECONDS)))
    }

    /** The QR code's URI has to carry the parameters an authenticator app reads. */
    @Test
    fun `builds an otpauth uri naming the issuer, the account and the parameters`() {
        val uri = authenticator.otpAuthUri(secret, "prashant@example.com")

        assertTrue(uri.startsWith("otpauth://totp/"))
        assertTrue(uri.contains("issuer=PetCare"))
        assertTrue(uri.contains("secret=$secret"))
        assertTrue(uri.contains("digits=${TotpAuthenticator.CODE_DIGITS}"))
        assertTrue(uri.contains("period=${TotpAuthenticator.TIME_STEP_SECONDS}"))
        assertTrue(uri.contains("prashant%40example.com") || uri.contains("prashant@example.com"))
    }

    /** Two secrets in a row must not be the same one, or every account would share a code. */
    @Test
    fun `generates a different secret each time`() {
        val first = authenticator.newSecret()
        val second = authenticator.newSecret()

        assertFalse(first == second)
        // 32 Base32 characters — 160 bits, the library's default and the length the class documents.
        assertEquals(32, first.length)
    }

    private companion object {

        /** A fixed moment to measure from: 1 000 000 000 seconds after the epoch. */
        const val START_SECONDS = 1_000_000_000L
    }
}
