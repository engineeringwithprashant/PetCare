package com.prash.petcare.ui.auth.reset

import com.prash.petcare.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [DestinationMasking.maskPhone] and [ResetChannel]: the logic behind which format reset
 * step 1's account field requires, and what the reset screens say about where a code went.
 *
 * The masking is worth pinning down because a mistake in it is a privacy leak that looks fine at a
 * glance — showing one digit too many is easy to miss by eye.
 */
class DestinationMaskingTest {

    @Test
    fun `a spaced Nepali number masks to the design's format`() {
        assertEquals("+977 98•• ••12", DestinationMasking.maskPhone("+977 9812345612"))
    }

    @Test
    fun `an unspaced number keeps the same digits visible`() {
        assertEquals("+97798•• ••12", DestinationMasking.maskPhone("+9779812345612"))
    }

    @Test
    fun `a separator just before the mask is dropped`() {
        assertEquals("+977 98•• ••12", DestinationMasking.maskPhone("+977 98 1234 5612"))
    }

    @Test
    fun `a local ten-digit number shows its first two and last two digits`() {
        assertEquals("98•• ••78", DestinationMasking.maskPhone("9812345678"))
    }

    @Test
    fun `a short number shows only its last two digits`() {
        assertEquals("•• ••34", DestinationMasking.maskPhone("5521234"))
    }

    @Test
    fun `surrounding spaces are ignored`() {
        assertEquals("98•• ••78", DestinationMasking.maskPhone("  9812345678 "))
    }

    @Test
    fun `the email channel accepts only an email address`() {
        assertNull(ResetChannel.EMAIL.validate("prashant@petcare.app"))
        assertEquals(R.string.error_email_invalid, ResetChannel.EMAIL.validate("+977 9812345678"))
        assertEquals(R.string.error_email_required, ResetChannel.EMAIL.validate(""))
    }

    @Test
    fun `the SMS channel accepts only a phone number`() {
        assertNull(ResetChannel.SMS.validate("+977 9812345678"))
        assertEquals(R.string.error_phone_invalid, ResetChannel.SMS.validate("prashant@petcare.app"))
        assertEquals(R.string.error_phone_required, ResetChannel.SMS.validate(""))
    }

    @Test
    fun `step 2 shows an email as typed and a phone number masked`() {
        assertEquals("prashant@petcare.app", ResetChannel.EMAIL.display("  prashant@petcare.app "))
        assertEquals("+977 98•• ••12", ResetChannel.SMS.display("+977 9812345612"))
    }

    @Test
    fun `channel names round-trip, and anything unknown falls back to SMS`() {
        ResetChannel.entries.forEach { assertEquals(it, ResetChannel.fromName(it.name)) }
        assertEquals(ResetChannel.SMS, ResetChannel.fromName(null))
        assertEquals(ResetChannel.SMS, ResetChannel.fromName("PIGEON"))
    }
}
