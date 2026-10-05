package com.prash.petcare.ui.common

import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.util.PhoneCheck

/** Turns the result of a phone-number check into the message a form shows. */
@StringRes
fun PhoneCheck.messageRes(requireNumber: Boolean): Int? = when (this) {
    PhoneCheck.EMPTY -> if (requireNumber) R.string.error_phone_required else null
    PhoneCheck.INVALID -> R.string.error_phone_invalid
    PhoneCheck.VALID -> null
}
