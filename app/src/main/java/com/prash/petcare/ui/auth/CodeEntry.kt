package com.prash.petcare.ui.auth

/**
 * The rules for typing a six-digit code on the on-screen keypad: digits fill left to right, backspace
 * removes the last one, and nothing goes past six.
 *
 * Pure functions on a String, with no Android types, so the rules are unit-tested on the JVM (see
 * `CodeEntryTest`) and shared by every code screen through [CodeEntryViewModel]. The typed code is
 * modelled as a plain String of digits because that is exactly what it is — the six boxes are only a
 * way of *drawing* it, and CodeKeypadView takes care of that.
 */
object CodeEntry {

    /** How many digits a code has. CodeKeypadView draws exactly this many boxes. */
    const val LENGTH = 6

    /**
     * Returns [code] with [digit] added at the end, or [code] unchanged if it is already full or
     * [digit] is not 0–9. Ignoring an extra digit (rather than replacing the last one) matches how a
     * phone's own PIN entry behaves.
     */
    fun append(code: String, digit: Int): String =
        if (code.length >= LENGTH || digit !in 0..9) code else code + digit

    /** Returns [code] without its last digit; an empty code stays empty. */
    fun backspace(code: String): String = code.dropLast(1)

    /**
     * Returns an empty code, for the CLEAR key. A function rather than a bare "" at the call site so
     * every way of changing the code is named in this one object.
     */
    fun clear(): String = ""

    /** True once every box is filled — the point at which the verify button becomes pressable. */
    fun isComplete(code: String): Boolean = code.length == LENGTH
}
