package com.prash.petcare.ui.common

/*
 * Display formatting for a pet's weight, shared by the dashboard's pet cards and the Pets tab.
 *
 * Part of the View layer's helpers: it turns a stored value into text and holds no state. It sits in
 * ui/common because two adapters in different packages need identical output — the same pet must not
 * read "26 kg" on one tab and "26.0 kg" on the next.
 */

/**
 * Renders a stored weight without a pointless decimal place: 26.0 becomes "26", 4.5 stays "4.5".
 *
 * Weight is stored as a `Double` because half-kilograms matter for a small animal, but most are
 * recorded as whole numbers and `toString()` would put "26.0 kg" on every card.
 *
 * The comparison is against the value truncated to a whole number rather than against a rounded one,
 * so only a genuinely whole weight loses its decimal.
 */
fun formatWeight(kilograms: Double): String =
    if (kilograms == kilograms.toLong().toDouble()) {
        kilograms.toLong().toString()
    } else {
        kilograms.toString()
    }
