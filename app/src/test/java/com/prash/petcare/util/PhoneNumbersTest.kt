package com.prash.petcare.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PhoneNumbers], the per-country rules behind every phone field in the app.
 *
 * Plain JVM tests: the object holds no Android types and libphonenumber is pure Java, which is
 * exactly why the rules live in `util` rather than being wired into a view.
 *
 * What these guard is the behaviour the old hand-written "seven to fifteen digits" check got wrong,
 * and the two things the app now *depends* on being right: that a valid number always reduces to one
 * canonical stored form, and that a number stored in any of the shapes an older version left behind
 * still reduces to the same one — because sign-in by phone number is a comparison between those two.
 */
class PhoneNumbersTest {

    /** A real Nepali mobile number's national part. */
    private val nepaliMobile = "9812345678"

    // ---------------------------------------------------------------------------------------
    // Checking a number against its country
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a real number for the country selected is valid`() {
        assertEquals(PhoneCheck.VALID, PhoneNumbers.check("NP", nepaliMobile))
        assertEquals(PhoneCheck.VALID, PhoneNumbers.check("GB", "7400123456"))
        assertEquals(PhoneCheck.VALID, PhoneNumbers.check("US", "4155550100"))
    }

    @Test
    fun `separators people actually type do not make a number invalid`() {
        listOf("981 234 5678", "981-234-5678", "(981) 234 5678", "0981 2345678").forEach {
            assertEquals(it, PhoneCheck.VALID, PhoneNumbers.check("NP", it))
        }
    }

    @Test
    fun `an empty box is reported as empty rather than invalid`() {
        // The distinction matters: registration treats empty as an error and the profile form does
        // not, and neither could tell the two apart from a single "invalid".
        assertEquals(PhoneCheck.EMPTY, PhoneNumbers.check("NP", ""))
        assertEquals(PhoneCheck.EMPTY, PhoneNumbers.check("NP", "   "))
        assertEquals(PhoneCheck.EMPTY, PhoneNumbers.check("NP", "()-"))
    }

    @Test
    fun `a number that is real for one country is not accepted for another`() {
        // The whole reason the country is asked for. A ten-digit Nepali mobile is not a UK number,
        // and the old digit-counting check had no way of knowing that.
        assertEquals(PhoneCheck.VALID, PhoneNumbers.check("NP", nepaliMobile))
        assertEquals(PhoneCheck.INVALID, PhoneNumbers.check("GB", nepaliMobile))
    }

    @Test
    fun `too few or too many digits are rejected`() {
        assertEquals(PhoneCheck.INVALID, PhoneNumbers.check("NP", "98123"))
        assertEquals(PhoneCheck.INVALID, PhoneNumbers.check("NP", "98123456789012"))
    }

    @Test
    fun `letters are rejected`() {
        assertEquals(PhoneCheck.INVALID, PhoneNumbers.check("NP", "98123abcd45"))
    }

    // ---------------------------------------------------------------------------------------
    // The stored form
    // ---------------------------------------------------------------------------------------

    @Test
    fun `every way of writing one number reduces to the same stored form`() {
        // This is the property sign-in by phone number rests on: the number typed at the log-in
        // screen has to match the number saved on the profile, however either was written.
        val expected = "+9779812345678"

        listOf(nepaliMobile, "981 234 5678", "0981-2345678", "+977 9812345678", "+9779812345678")
            .forEach { assertEquals(it, expected, PhoneNumbers.parseLoginNumber(it)) }

        assertEquals(expected, PhoneNumbers.toE164("NP", nepaliMobile))
    }

    @Test
    fun `toE164 refuses a number that is not valid for the country`() {
        assertNull(PhoneNumbers.toE164("GB", nepaliMobile))
        assertNull(PhoneNumbers.toE164("NP", "98123"))
    }

    // ---------------------------------------------------------------------------------------
    // Reading a stored number back into a form
    // ---------------------------------------------------------------------------------------

    @Test
    fun `a stored international number is split back into its own country`() {
        val split = requireNotNull(PhoneNumbers.split("+447400123456"))

        assertEquals("GB", split.first.regionCode)
        assertEquals("7400123456", split.second)
    }

    @Test
    fun `a bare number saved by an older version is read as a Nepali one`() {
        // The case that stops an existing profile's number vanishing from the form the first time it
        // is opened after the country selector was added.
        val split = requireNotNull(PhoneNumbers.split(nepaliMobile))

        assertEquals("NP", split.first.regionCode)
        assertEquals(nepaliMobile, split.second)
    }

    // ---------------------------------------------------------------------------------------
    // The log-in field, which has no country selector
    // ---------------------------------------------------------------------------------------

    @Test
    fun `an email address is never read as a phone number`() {
        assertNull(PhoneNumbers.parseLoginNumber("prashant@petcare.app"))
        // Even one with digits in it, which is where a looser check would go wrong.
        assertNull(PhoneNumbers.parseLoginNumber("prashant977@petcare.app"))
        assertFalse(PhoneNumbers.looksLikePhoneAttempt("prashant977@petcare.app"))
    }

    @Test
    fun `digits that are not a real number are recognised as an attempt at one`() {
        // So the log-in field can say "that is not a valid number" instead of complaining about an
        // email address the user plainly was not typing.
        assertNull(PhoneNumbers.parseLoginNumber("98123"))
        assertTrue(PhoneNumbers.looksLikePhoneAttempt("98123"))
        assertTrue(PhoneNumbers.looksLikePhoneAttempt("+977 981"))
    }

    @Test
    fun `a word is neither a number nor an attempt at one`() {
        assertNull(PhoneNumbers.parseLoginNumber("prashant"))
        assertFalse(PhoneNumbers.looksLikePhoneAttempt("prashant"))
    }

    // ---------------------------------------------------------------------------------------
    // The country list and the input cap
    // ---------------------------------------------------------------------------------------

    @Test
    fun `the country list carries Nepal and is not empty`() {
        val nepal = PhoneNumbers.countryFor(PhoneNumbers.DEFAULT_REGION)

        assertEquals("NP", nepal.regionCode)
        assertEquals(977, nepal.diallingCode)
        assertTrue(PhoneNumbers.countries.size > 200)
    }

    @Test
    fun `an unknown region falls back to the default rather than failing`() {
        assertEquals(PhoneNumbers.DEFAULT_REGION, PhoneNumbers.countryFor("ZZ").regionCode)
    }

    @Test
    fun `the digit cap can never be short enough to reject a valid number`() {
        // The cap exists to stop an over-long number being typed at all, so the one thing it must
        // never do is stop somebody typing their real one.
        listOf("NP" to nepaliMobile, "GB" to "7400123456", "US" to "4155550100").forEach {
            val (region, number) = it
            assertTrue(region, PhoneNumbers.maxNationalDigits(region) >= number.length)
        }
    }
}
