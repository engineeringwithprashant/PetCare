package com.prash.petcare.ui.auth

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewCodeKeypadBinding

/** The six code boxes and the numeric keypad under them, as one reusable component. */
class CodeKeypadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Receives key presses. One callback per kind of key rather than a single "key" event. */
    interface Listener {
        fun onDigit(digit: Int)

        /** Backspace: remove the last digit. */
        fun onBackspace()

        /** CLEAR: remove every digit. */
        fun onClear()
    }

    /** Where key presses are sent. Set by the fragment; null presses are simply ignored. */
    var listener: Listener? = null

    // view_code_keypad.xml is a <merge> layout, so this binding inflates its children straight into this LinearLayout.
    private val binding = ViewCodeKeypadBinding.inflate(LayoutInflater.from(context), this)

    private val boxes = with(binding) { listOf(box1, box2, box3, box4, box5, box6) }

    init {
        orientation = VERTICAL

        // The layout and the rules must agree on the code length.
        check(boxes.size == CodeEntry.LENGTH) { "view_code_keypad.xml must have ${CodeEntry.LENGTH} boxes" }

        // Index in this list == the digit the key types, so one loop wires all ten.
        val digitKeys = with(binding) { listOf(key0, key1, key2, key3, key4, key5, key6, key7, key8, key9) }
        digitKeys.forEachIndexed { digit, key ->
            key.setOnClickListener { listener?.onDigit(digit) }
        }
        binding.keyBackspace.setOnClickListener { listener?.onBackspace() }
        // CLEAR only reports the press. The boxes empty — and the yellow "next box" outline returns to the first.
        binding.keyClear.setOnClickListener { listener?.onClear() }

        // Draw the empty state straight away.
        render(code = "", errorText = null)
    }

    /** Draws [code] into the boxes, and [errorText] under them when it is not null. */
    fun render(code: String, errorText: CharSequence?) {
        val hasError = errorText != null

        boxes.forEachIndexed { index, box ->
            box.text = code.getOrNull(index)?.toString().orEmpty()
            box.isActivated = !hasError && index == code.length
            box.isSelected = hasError
        }

        binding.codeError.text = errorText
        binding.codeError.isVisible = hasError

        // The boxes are hidden from TalkBack individually, so the row describes itself as a whole.
        binding.codeBoxes.contentDescription =
            resources.getString(R.string.auth_code_progress, code.length, CodeEntry.LENGTH)
    }
}
