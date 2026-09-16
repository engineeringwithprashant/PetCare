package com.prash.petcare.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Checks the one rule in the weather feature: which temperatures suit a walk.
 *
 * A plain JVM test with no Android dependencies, which is exactly why [WalkComfort] was kept free of
 * them. Everything else in the feature — the HTTP call, the SharedPreferences cache, the location
 * fix — needs a device or a fake to exercise; this is the part where an off-by-one would actually
 * mislead a user, so it is the part worth pinning down.
 */
class WalkComfortTest {

    @Test
    fun `a mild afternoon is good walking weather`() {
        assertEquals(WalkComfort.GOOD, WalkComfort.at(18.0))
    }

    @Test
    fun `both ends of the comfortable range are included`() {
        // The bounds are inclusive: 10 and 25 degrees are comfortable, not the first degree past it.
        assertEquals(WalkComfort.GOOD, WalkComfort.at(10.0))
        assertEquals(WalkComfort.GOOD, WalkComfort.at(25.0))
    }

    @Test
    fun `one degree outside either bound is not`() {
        assertEquals(WalkComfort.TOO_COLD, WalkComfort.at(9.0))
        assertEquals(WalkComfort.TOO_HOT, WalkComfort.at(26.0))
    }

    @Test
    fun `the verdict follows the rounded figure the card shows`() {
        // 25.4 prints as "25°C", so it must not be judged too hot; 25.5 prints as "26°C" and must be.
        // This is the pairing described in WalkComfort.at — the pill can never contradict the number
        // beside it.
        assertEquals(WalkComfort.GOOD, WalkComfort.at(25.4))
        assertEquals(WalkComfort.TOO_HOT, WalkComfort.at(25.5))

        // The same at the cold end: 9.5 rounds up to 10 and is comfortable.
        assertEquals(WalkComfort.GOOD, WalkComfort.at(9.5))
        assertEquals(WalkComfort.TOO_COLD, WalkComfort.at(9.4))
    }

    @Test
    fun `freezing and blazing are handled, including below zero`() {
        assertEquals(WalkComfort.TOO_COLD, WalkComfort.at(-12.0))
        assertEquals(WalkComfort.TOO_COLD, WalkComfort.at(0.0))
        assertEquals(WalkComfort.TOO_HOT, WalkComfort.at(41.0))
    }
}
