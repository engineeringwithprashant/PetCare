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

/** The shared half of every "type the six-digit code" screen — currently the two-factor check. */
abstract class CodeEntryViewModel(
    protected val savedStateHandle: SavedStateHandle
) : ViewModel() {

    /** The digits typed so far, in order, as a String such as "4821". */
    val code: StateFlow<String> = savedStateHandle.getStateFlow(KEY_CODE, "")

    /** The message to show under the boxes when a code was rejected, or null. */
    val codeError: StateFlow<Int?> = savedStateHandle.getStateFlow(KEY_CODE_ERROR, null)

    /** Whether the verify button can be pressed: only once all six digits are in. */
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

    /** The keypad's CLEAR was pressed: empty every box so the code can be retyped from the start. */
    fun onClear() {
        setCode(CodeEntry.clear())
    }

    /** What the screen's verify button does. Each screen decides. */
    abstract fun onVerify()

    /** Marks the current code as wrong: shows [messageRes] under the boxes, outlines them in the error colour. */
    protected fun rejectCode(@StringRes messageRes: Int = R.string.error_code_incorrect) {
        savedStateHandle[KEY_CODE] = ""
        savedStateHandle[KEY_CODE_ERROR] = messageRes
    }

    private fun setCode(newCode: String) {
        savedStateHandle[KEY_CODE] = newCode
        savedStateHandle[KEY_CODE_ERROR] = null
    }

    private companion object {
        // Namespaced so they cannot collide with a navigation argument of the same name, since the handle holds both.
        const val KEY_CODE = "code_entry.code"
        const val KEY_CODE_ERROR = "code_entry.error"
    }
}
