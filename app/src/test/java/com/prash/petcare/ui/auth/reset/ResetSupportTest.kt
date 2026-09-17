package com.prash.petcare.ui.auth.reset

import com.prash.petcare.R
import com.prash.petcare.data.auth.PasswordResetResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the password-reset helpers in ResetSupport.kt: the countdown arithmetic behind the
 * "RESEND" cooldown, and the message chosen for each way a reset request can fail.
 *
 * Both are plain Kotlin with no Android types, which is why they can be checked here on the JVM
 * without an emulator.
 */
class ResetSupportTest {

    private val cooldown = ResendCooldown.DURATION_MILLIS

    @Test
    fun `a cooldown that has just started has the full duration left`() {
        assertEquals(cooldown, ResendCooldown.millisLeft(nowMillis = 1_000L, readyAtMillis = 1_000L + cooldown))
    }

    @Test
    fun `a cooldown whose end has passed has nothing left`() {
        assertEquals(0L, ResendCooldown.millisLeft(nowMillis = 5_000L, readyAtMillis = 4_000L))
    }

    @Test
    fun `a clock moved backwards cannot stretch the cooldown beyond its duration`() {
        // "Now" is an hour before the cooldown even started.
        val readyAt = 10_000_000L
        val now = readyAt - cooldown - 3_600_000L
        assertEquals(cooldown, ResendCooldown.millisLeft(now, readyAt))
    }

    @Test
    fun `seconds shown round up so the link never reads zero while still disabled`() {
        assertEquals(1, ResendCooldown.secondsToShow(400L))
        assertEquals(43, ResendCooldown.secondsToShow(42_300L))
        assertEquals(42, ResendCooldown.secondsToShow(42_000L))
    }

    @Test
    fun `no time left shows zero seconds`() {
        assertEquals(0, ResendCooldown.secondsToShow(0L))
    }

    @Test
    fun `a successful send has no failure message`() {
        assertNull(PasswordResetResult.Sent.failureMessage())
    }

    @Test
    fun `each failure maps to its own readable message`() {
        assertEquals(R.string.reset_error_network, PasswordResetResult.NoNetwork.failureMessage())
        assertEquals(R.string.reset_error_rate_limited, PasswordResetResult.RateLimited.failureMessage())
        assertEquals(R.string.reset_error_invalid_email, PasswordResetResult.InvalidEmail.failureMessage())
        assertEquals(R.string.reset_error_unknown, PasswordResetResult.Failed.failureMessage())
    }
}
