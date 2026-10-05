package com.prash.petcare.ui.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [CodeEntry], the keypad rules behind the reusable code-entry component (used by two-factor). */
class CodeEntryTest {

    /** Types [digits] one key press at a time, as the keypad would. */
    private fun type(vararg digits: Int): String =
        digits.fold("") { code, digit -> CodeEntry.append(code, digit) }

    @Test
    fun `digits fill from the left in the order pressed`() {
        assertEquals("482", type(4, 8, 2))
    }

    @Test
    fun `a seventh digit is ignored`() {
        assertEquals("123456", type(1, 2, 3, 4, 5, 6, 7))
    }

    @Test
    fun `a value that is not a single digit is ignored`() {
        assertEquals("1", CodeEntry.append("1", 10))
        assertEquals("1", CodeEntry.append("1", -1))
    }

    @Test
    fun `backspace removes only the last digit`() {
        assertEquals("48", CodeEntry.backspace("482"))
    }

    @Test
    fun `backspace on an empty code does nothing`() {
        assertEquals("", CodeEntry.backspace(""))
    }

    @Test
    fun `clear empties a full code, and typing starts again from the first box`() {
        val cleared = CodeEntry.clear()
        assertEquals("", cleared)
        // An empty code is what puts the "next box" outline back on box one (index 0).
        assertEquals(0, cleared.length)
        assertEquals("7", CodeEntry.append(cleared, 7))
    }

    @Test
    fun `the code is complete only at six digits`() {
        assertFalse(CodeEntry.isComplete(type(1, 2, 3, 4, 5)))
        assertTrue(CodeEntry.isComplete(type(1, 2, 3, 4, 5, 6)))
        // Completing and then deleting one digit makes it incomplete again.
        assertFalse(CodeEntry.isComplete(CodeEntry.backspace(type(1, 2, 3, 4, 5, 6))))
    }
}
