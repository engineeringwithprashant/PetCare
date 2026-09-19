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

/**
 * Drives the pair of boxes every phone field in the app is now made of: a country selector showing
 * "🇳🇵 +977", and the local number typed beside it.
 *
 * **Why a binder rather than a custom View.** The four screens that ask for a number are styled
 * differently — the sign-in screen's cream outlined fields, the profile form's standard Material
 * boxes, two dialogs — and a compound View would have had to carry one of those looks and impose it
 * everywhere, or grow a set of styling attributes that only re-invented what a style already does.
 * So each layout declares its own two `TextInputLayout`s in its own style, and this class supplies
 * the *behaviour* they share: choosing a country, capping the length, reading the number back in
 * storage format, and showing the inline error. It is the same division the app already uses for
 * [PetPhotoBinder] and [TabHeader].
 *
 * **Where it sits in MVVM.** Firmly in the View layer: it holds views, opens a dialog and needs a
 * Context. Every *rule* it applies comes from [PhoneNumbers], which holds none of those things and is
 * unit-tested on its own — so the ViewModel that finally validates the submitted form applies exactly
 * the same rules this was showing live, without either side duplicating them.
 *
 * @param countryLayout The box holding the selector. Its own click opens the chooser too, so tapping
 *   anywhere in it works rather than only the text inside.
 * @param countryInput The non-editable field inside [countryLayout] that displays the chosen country.
 * @param numberLayout The box holding the number. Errors are shown here, under the number, because
 *   that is the part the user usually has to change.
 * @param numberInput The field the local number is typed into.
 * @param onChanged Called whenever the country or the number changes, so the screen can clear a
 *   stale error from a previous submit.
 */
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
        // Not focusable rather than merely read-only: a field that takes focus and opens the keyboard
        // but accepts nothing is a field the user fights with. This way a tap can only ever do the
        // one thing the field is for, and TalkBack announces it as a button rather than a text box.
        countryInput.isFocusable = false
        countryInput.isFocusableInTouchMode = false
        countryInput.isCursorVisible = false
        countryInput.setOnClickListener { showCountryChooser() }
        // The layout around it is clickable too, so the whole box is one target rather than just the
        // text baseline inside it.
        countryLayout.setOnClickListener { showCountryChooser() }

        numberInput.doAfterTextChanged { onChanged() }

        applyCountry(country)
    }

    /**
     * Fills both boxes from a stored number, for a form that is *editing* rather than creating.
     *
     * A number the app stored itself carries its own country, so the selector follows it. A number
     * saved before this screen had a selector is bare digits, and [PhoneNumbers.split] reads those
     * against Nepal — which is what stops an existing profile's number vanishing from the form the
     * first time it is opened after this change.
     *
     * A blank or unreadable value leaves the form on the default country with an empty box, which is
     * exactly the state a new entry starts in.
     */
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

    /**
     * The number in stored `+9779812345678` form, or null when what is typed is not a valid number
     * for the chosen country.
     */
    fun storedNumber(): String? = PhoneNumbers.toE164(country.regionCode, nationalNumber())

    /**
     * Shows [messageRes] under the number box, or clears the message when it is null.
     *
     * **Why the error comes *in* rather than being worked out here.** Whether a number is acceptable
     * is a rule, and rules belong to the ViewModel — including the one part that genuinely differs
     * per screen, whether an empty field is allowed at all. This class shows what it is told, which
     * also means there is only ever one owner of the error slot: a binder that decided for itself
     * would fight the ViewModel's own rendering pass for the same line of red text.
     *
     * The message is put under the *number*, not the country, because that is the part the user
     * usually has to change — and the message itself names the country as the other possibility.
     */
    fun showError(messageRes: Int?) {
        numberLayout.error = messageRes?.let(numberLayout.context::getString)
    }

    /**
     * Opens the country chooser: one row per country, the current one already ticked.
     *
     * A single-choice dialog rather than a dropdown menu attached to the field. There are over two
     * hundred countries, and an exposed dropdown renders them in a floating list pinned to the
     * field's width — which on a phone is a narrow column two rows tall. A dialog gets the full
     * screen, opens scrolled to the current choice, and is the standard Material component for
     * picking one value out of a long list.
     */
    private fun showCountryChooser() {
        val options = PhoneNumbers.countries
        val labels = options.map { it.listLabel }.toTypedArray()
        val checkedIndex = options.indexOfFirst { it.regionCode == country.regionCode }

        MaterialAlertDialogBuilder(countryLayout.context)
            .setTitle(R.string.phone_country_dialog_title)
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                applyCountry(options[which])
                // Changing the country changes what counts as a valid number, so an error earned
                // under the old country is no longer about anything the user can see. Reporting the
                // change clears it, the same way editing the number does.
                onChanged()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.delegate_cancel, null)
            .show()
    }

    /**
     * Applies a country: updates the selector's label, its spoken description, and the cap on how
     * many digits the number box will accept.
     *
     * The cap is re-applied on every change because it depends on the dialling code's length — see
     * [PhoneNumbers.maxNationalDigits]. Anything already typed that no longer fits is trimmed, since
     * leaving text in a box longer than the box now allows would give the user a field they could
     * not fix by typing.
     */
    private fun applyCountry(newCountry: Country) {
        country = newCountry

        countryInput.setText(newCountry.shortLabel)
        // The label is a flag and a number, which TalkBack would read as "flag plus nine seven
        // seven". Naming the country in full is what makes the control usable without sight of it.
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

    /**
     * Stops more than [maxDigits] digits being typed into the number box, while still allowing the
     * spaces, dashes and brackets people write numbers with.
     *
     * **Why not the standard `InputFilter.LengthFilter`.** That one counts *characters*, so a number
     * typed as "981-234-5678" would hit a ten-character limit after "981-234-56" and silently stop
     * accepting the rest of a perfectly ordinary number. Counting only the digits is what makes the
     * cap mean what it says.
     *
     * The contract of an `InputFilter` is unusual and worth stating: it is handed the replacement
     * text and both strings, and returns either null ("allow it as is") or the text to insert
     * instead. Returning an empty string is therefore how a keystroke is rejected.
     */
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

            // Take the incoming text up to the point where it would overflow, so pasting a long
            // number still fills the field rather than being rejected outright.
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
