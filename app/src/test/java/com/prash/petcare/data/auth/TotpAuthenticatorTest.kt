package com.prash.petcare.data.auth

import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.time.TimeProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TotpAuthenticator] — the one part of two-factor authentication whose behaviour
 * depends on the clock, and therefore the one part that cannot be checked by using the app for a
 * minute.
 *
 * These run on the JVM with no device, which they can because the class touches no Android type: it
 * wraps the TOTP library and takes its "now" from an injected [TimeProvider]. That injection is the
 * whole reason the tests are possible — a class reading `System.currentTimeMillis()` directly could
 * only be tested by waiting thirty real seconds per assertion.
 *
 * **What is worth testing here, and what is not.** Whether HMAC-SHA1 truncates correctly is the
 * library's business and is not re-checked. What *this* app decided is the acceptance window: one
 * thirty-second step either side, so the phone and the authenticator can disagree by up to about half
 * a minute. Too narrow and correct codes are rejected on a phone whose clock has drifted; too wide
 * and each code stays valid for minutes, which is the property a one-time password exists to avoid.
 * Both failures are silent in ordinary use, which is exactly why they are pinned down here.
 *
 * The recovery codes are deliberately not tested alongside them: [RecoveryCodes] hashes through
 * `android.util.Base64`, which is a stub on the JVM, so testing it would need an instrumented test or
 * Robolectric rather than this plain suite.
 */
class TotpAuthenticatorTest {

    /**
     * A clock the test moves by hand.
     *
     * `getTime()` returns whole seconds, which is what the TOTP library works in — it divides by the
     * thirty-second step to get the counter the code is generated from.
     */
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

    /**
     * The other half of the drift allowance: it has to *stop* somewhere, or a code would stay usable
     * long after it was shown.
     */
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

    /**
     * Anything that is not six digits is turned away without reaching the library.
     *
     * This matters because the values come from a keypad and a text field, and the screens rely on
     * getting `false` rather than an exception for input that was never going to be a code.
     */
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

    /**
     * The QR code's URI has to carry the parameters an authenticator app reads, under the names the
     * otpauth scheme defines — a URI that is subtly wrong produces an entry whose codes never match,
     * with nothing on either screen to explain why.
     */
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

        /**
         * A fixed moment to measure from: 1 000 000 000 seconds after the epoch, which lands exactly
         * on a thirty-second boundary. Starting mid-step would make "one step ago" ambiguous by a few
         * seconds and the drift tests harder to read.
         */
        const val START_SECONDS = 1_000_000_000L
    }
}
