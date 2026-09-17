package com.prash.petcare.ui.auth

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.prash.petcare.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The shared half of every "type the six-digit code" screen: the two-factor check and reset step 2.
 *
 * **Why a base class.** Both screens hold a partly typed code, change it from the same keypad, show the
 * same "wrong code" state and enable their verify button by the same rule. Only what *verify* does
 * differs. So that shared behaviour lives here once, and
 * [com.prash.petcare.ui.auth.twofactor.TwoFactorViewModel] and
 * [com.prash.petcare.ui.auth.reset.ResetCodeViewModel] extend it and add their own `onVerify`. On the
 * View side the same split is made by [CodeKeypadView], which both screens' layouts include.
 *
 * It sits in the ViewModel layer of MVVM: the fragment forwards key presses here and draws [code],
 * [codeError] and [canVerify]; the rules themselves are in [CodeEntry].
 *
 * **Why SavedStateHandle.** A ViewModel alone survives rotation but not Android killing the app in the
 * background. Keeping the digits in the handle means someone who switched to their authenticator app —
 * exactly what this screen asks them to do — comes back to the digits they had typed, even if the
 * system reclaimed PetCare's process meanwhile.
 *
 * @param savedStateHandle Supplied by the default ViewModel factory; it also carries the screen's
 *   navigation arguments, which the subclasses read.
 */
abstract class CodeEntryViewModel(
    protected val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** The digits typed so far, in order, as a String such as "4821". */
    val code: StateFlow<String> = savedStateHandle.getStateFlow(KEY_CODE, "")

    /** The message to show under the boxes when a code was rejected, or null. */
    val codeError: StateFlow<Int?> = savedStateHandle.getStateFlow(KEY_CODE_ERROR, null)

    /**
     * Whether the verify button can be pressed: only once all six digits are in.
     *
     * Derived from [code] rather than stored, so it can never disagree with it. `stateIn` turns the
     * mapped flow back into a StateFlow with a current value; Eagerly because it is cheap and the
     * fragment reads it as soon as it starts.
     */
    val canVerify: StateFlow<Boolean> = code
        .map(CodeEntry::isComplete)
        .stateIn(viewModelScope, SharingStarted.Eagerly, CodeEntry.isComplete(code.value))

    /** A keypad digit was pressed. A new digit also clears any "wrong code" message. */
    fun onDigit(digit: Int) {
        setCode(CodeEntry.append(code.value, digit))
    }

    /** The keypad's backspace was pressed. */
    fun onBackspace() {
        setCode(CodeEntry.backspace(code.value))
    }

    /**
     * The keypad's CLEAR was pressed: empty every box so the code can be retyped from the start.
     *
     * An empty code is all it takes to "return focus to the first box": CodeKeypadView outlines the box
     * at index `code.length`, which is now 0. Any "wrong code" message goes too, as with any edit.
     */
    fun onClear() {
        setCode(CodeEntry.clear())
    }

    /** What the screen's verify button does. Each screen decides. */
    abstract fun onVerify()

    /**
     * Marks the current code as wrong: shows [messageRes] under the boxes, outlines them in the error
     * colour, and empties them so the user can type the code again straight away.
     *
     * Not called yet — nothing can reject a code until verification is connected. The subclasses'
     * TODOs say where to call it from.
     */
    protected fun rejectCode(@StringRes messageRes: Int = R.string.error_code_incorrect) {
        savedStateHandle[KEY_CODE] = ""
        savedStateHandle[KEY_CODE_ERROR] = messageRes
    }

    private fun setCode(newCode: String) {
        savedStateHandle[KEY_CODE] = newCode
        savedStateHandle[KEY_CODE_ERROR] = null
    }

    private companion object {
        // Namespaced so they cannot collide with a navigation argument of the same name, since the
        // handle holds both.
        const val KEY_CODE = "code_entry.code"
        const val KEY_CODE_ERROR = "code_entry.error"
    }
}
