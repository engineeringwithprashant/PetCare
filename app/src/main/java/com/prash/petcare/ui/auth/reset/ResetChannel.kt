package com.prash.petcare.ui.auth.reset

import androidx.annotation.StringRes
import com.prash.petcare.ui.auth.AuthValidation

/**
 * Where a password-reset code is sent. Chosen on reset step 1 and carried to step 2 as a navigation
 * argument (by [name], which is what [fromName] reads back).
 *
 * Each channel also knows which format its account field must be in, so step 1 asks the chosen
 * channel rather than branching on it — see [validate].
 */
enum class ResetChannel {
    EMAIL {
        override fun validate(destination: String): Int? = AuthValidation.validateEmail(destination)
        override fun display(destination: String): String = destination.trim()
    },
    SMS {
        override fun validate(destination: String): Int? = AuthValidation.validatePhone(destination)
        override fun display(destination: String): String = DestinationMasking.maskPhone(destination)
    };

    /**
     * Returns the error for [destination] typed into this channel's account field — an email format
     * for [EMAIL], a phone format for [SMS] — or null when it is acceptable. An empty field is an
     * error too, so "valid" always means "ready to send".
     */
    @StringRes
    abstract fun validate(destination: String): Int?

    /**
     * How step 2 names [destination] on screen: an email as typed, a phone number masked so only its
     * opening and last two digits show.
     */
    abstract fun display(destination: String): String

    companion object {
        /** Reads a channel back from a navigation argument, falling back to SMS for anything unknown. */
        fun fromName(name: String?): ResetChannel = entries.firstOrNull { it.name == name } ?: SMS
    }
}

/**
 * Hides most of a phone number, as the reset screens show it: "+977 9812345612" → "+977 98•• ••12".
 *
 * **The rule.** The last two digits stay visible, so the user can recognise their own number. The last
 * eight digits are replaced by "•• ••" + those two, and everything *before* them — the country code and
 * the first digits of the number — is kept exactly as typed, spaces included. Keeping the opening lets
 * someone with two numbers tell which one this is, without revealing enough to be useful to anyone
 * else. A number of eight digits or fewer shows only its last two.
 *
 * Pure Kotlin with no Android types, so it is unit-tested on the JVM (DestinationMaskingTest).
 */
object DestinationMasking {

    /** How many trailing digits are replaced by the mask (two of which are shown again after it). */
    private const val MASKED_DIGITS = 8
    private const val VISIBLE_TAIL = 2
    private const val MASK = "•• ••"

    fun maskPhone(phone: String): String {
        val trimmed = phone.trim()
        val digits = trimmed.filter(Char::isDigit)
        val tail = digits.takeLast(VISIBLE_TAIL)
        if (digits.length <= MASKED_DIGITS) return MASK + tail

        // Copy the typed text, separators and all, and stop at the first digit that belongs to the
        // masked part. A separator left dangling at the end ("+977 98 ") is trimmed so the mask sits
        // directly against the visible digits, as in the design.
        val keepDigits = digits.length - MASKED_DIGITS
        val prefix = StringBuilder()
        var digitsKept = 0
        for (char in trimmed) {
            if (char.isDigit()) {
                if (digitsKept == keepDigits) break
                digitsKept++
            }
            prefix.append(char)
        }
        return prefix.toString().trimEnd(' ', '-', '.', '(', ')') + MASK + tail
    }
}
