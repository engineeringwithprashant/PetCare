package com.prash.petcare.ui.common

import android.text.InputFilter
import android.text.Spanned
import android.widget.EditText
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import com.prash.petcare.R
import com.prash.petcare.util.Country
import com.prash.petcare.util.PhoneNumbers

/** Drives the pair of boxes every phone field in the app is now made of: a country selector showing "🇳🇵 +977". */
class PhoneNumberInput(
    private val countryLayout: TextInputLayout,
    private val countryInput: EditText,
    private val numberLayout: TextInputLayout,
    private val numberInput: EditText,
    private val onChanged: () -> Unit = {}
) {

    /** The country currently selected. Starts at Nepal — see [PhoneNumbers.DEFAULT_REGION]. */
    var country: Country = PhoneNumbers.countryFor(PhoneNumbers.DEFAULT_REGION)
        private set

    init {
        // Not focusable rather than merely read-only.
        countryInput.isFocusable = false
        countryInput.isFocusableInTouchMode = false
        countryInput.isCursorVisible = false
        countryInput.setOnClickListener { showCountryChooser() }
        // The layout around it is clickable too, so the whole box is one target.
        countryLayout.setOnClickListener { showCountryChooser() }

        numberInput.doAfterTextChanged { onChanged() }

        applyCountry(country)
    }

    /** Fills both boxes from a stored number, for a form that is editing rather than creating. */
    fun setStoredNumber(storedNumber: String?) {
        val split = storedNumber?.takeIf { it.isNotBlank() }?.let(PhoneNumbers::split)

        if (split == null) {
            numberInput.setText("")
            return
        }

        val (storedCountry, nationalNumber) = split
        applyCountry(storedCountry)
        numberInput.setText(nationalNumber)
    }

    /** The digits typed beside the selector, exactly as they were typed. */
    fun nationalNumber(): String = numberInput.text?.toString().orEmpty().trim()

    /** The chosen country's region code ("NP"), which is what the ViewModel validates against. */
    fun regionCode(): String = country.regionCode

    /** True when nothing has been typed at all — what an *optional* number field treats as "none given". */
    fun isEmpty(): Boolean = nationalNumber().none { it.isDigit() }

    /** The number in stored `+9779812345678` form, or null. */
    fun storedNumber(): String? = PhoneNumbers.toE164(country.regionCode, nationalNumber())

    /** Shows [messageRes] under the number box, or clears the message when it is null. */
    fun showError(messageRes: Int?) {
        numberLayout.error = messageRes?.let(numberLayout.context::getString)
    }

    /** Opens the country chooser: one row per country, the current one already ticked. */
    private fun showCountryChooser() {
        val options = PhoneNumbers.countries
        val labels = options.map { it.listLabel }.toTypedArray()
        val checkedIndex = options.indexOfFirst { it.regionCode == country.regionCode }

        MaterialAlertDialogBuilder(countryLayout.context)
            .setTitle(R.string.phone_country_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                applyCountry(options[which])
                // Changing the country changes what counts as a valid number.
                onChanged()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.delegate_cancel, null)
            .show()
    }

    /** Applies a country: updates the selector's label, its spoken description. */
    private fun applyCountry(newCountry: Country) {
        country = newCountry

        countryInput.setText(newCountry.shortLabel)
        // The label is a flag and a number, which TalkBack would read as "flag plus nine seven seven".
        countryLayout.contentDescription = countryLayout.context.getString(
            R.string.cd_phone_country,
            newCountry.displayName,
            newCountry.diallingCode
        )

        val maxDigits = PhoneNumbers.maxNationalDigits(newCountry.regionCode)
        numberInput.filters = arrayOf(DigitCountFilter(maxDigits))

        val current = numberInput.text?.toString().orEmpty()
        if (current.count { it.isDigit() } > maxDigits) {
            numberInput.setText(current.filter { it.isDigit() }.take(maxDigits))
        }
    }

    /** Stops more than [maxDigits] digits being typed into the number box, while still allowing the spaces. */
    private class DigitCountFilter(private val maxDigits: Int) : InputFilter {

        override fun filter(
            source: CharSequence,
            start: Int,
            end: Int,
            dest: Spanned,
            dstart: Int,
            dend: Int
        ): CharSequence? {
            // Digits that will survive this edit: everything outside the replaced range.
            val keptDigits = dest.subSequence(0, dstart).count { it.isDigit() } +
                dest.subSequence(dend, dest.length).count { it.isDigit() }
            val room = maxDigits - keptDigits
            if (room <= 0) return ""

            // Take the incoming text up to the point where it would overflow.
            var allowedDigits = 0
            for (index in start until end) {
                if (source[index].isDigit()) {
                    if (allowedDigits == room) return source.subSequence(start, index)
                    allowedDigits++
                }
            }
            return null
        }
    }
}
