package com.prash.petcare.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [OpeningHours.isOpen], which decides whether the featured place shows "OPEN NOW". */
class OpeningHoursTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    @Test
    fun `unknown hours are never reported as open`() {
        assertFalse(OpeningHours.isOpen(null, null, at(12)))
        assertFalse(OpeningHours.isOpen(at(8), null, at(12)))
        assertFalse(OpeningHours.isOpen(null, at(20), at(12)))
    }

    @Test
    fun `a same-day window is open between its times`() {
        // 8:00 – 20:00, the design's clinic.
        assertTrue(OpeningHours.isOpen(at(8), at(20), at(12)))
        assertFalse(OpeningHours.isOpen(at(8), at(20), at(7, 59)))
        assertFalse(OpeningHours.isOpen(at(8), at(20), at(23)))
    }

    @Test
    fun `open at the opening minute and closed at the closing minute`() {
        assertTrue(OpeningHours.isOpen(at(8), at(20), at(8)))
        assertTrue(OpeningHours.isOpen(at(8), at(20), at(19, 59)))
        assertFalse(OpeningHours.isOpen(at(8), at(20), at(20)))
    }

    @Test
    fun `hours past midnight are open late evening and early morning`() {
        // 18:00 – 02:00.
        assertTrue(OpeningHours.isOpen(at(18), at(2), at(18)))
        assertTrue(OpeningHours.isOpen(at(18), at(2), at(23, 59)))
        assertTrue(OpeningHours.isOpen(at(18), at(2), at(0)))
        assertTrue(OpeningHours.isOpen(at(18), at(2), at(1, 59)))
        assertFalse(OpeningHours.isOpen(at(18), at(2), at(2)))
        assertFalse(OpeningHours.isOpen(at(18), at(2), at(12)))
    }

    @Test
    fun `identical opening and closing times mean open around the clock`() {
        assertTrue(OpeningHours.isOpen(at(0), at(0), at(3)))
        assertTrue(OpeningHours.isOpen(at(0), at(0), at(15)))
    }
}
