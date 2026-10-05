package com.prash.petcare.ui.auth

/** The rules for typing a six-digit code on the on-screen keypad: digits fill left to right. */
object CodeEntry {

    /** How many digits a code has. CodeKeypadView draws exactly this many boxes. */
    const val LENGTH = 6

    /** Returns [code] with [digit] added at the end. */
    fun append(code: String, digit: Int): String =
        if (code.length >= LENGTH || digit !in 0..9) code else code + digit

    /** Returns [code] without its last digit; an empty code stays empty. */
    fun backspace(code: String): String = code.dropLast(1)

    /** Returns an empty code, for the CLEAR key. A function rather than a bare "" at the call site. */
    fun clear(): String = ""

    /** True once every box is filled — the point at which the verify button becomes pressable. */
    fun isComplete(code: String): Boolean = code.length == LENGTH
}
