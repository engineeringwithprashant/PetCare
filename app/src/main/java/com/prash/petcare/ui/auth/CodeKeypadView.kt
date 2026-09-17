package com.prash.petcare.ui.auth

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.core.view.isVisible
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewCodeKeypadBinding

/**
 * The six code boxes and the numeric keypad under them, as one reusable component.
 *
 * Both the two-factor screen and reset step 2 place this in their layout with a single tag, so the
 * boxes, the keypad and their behaviour exist in exactly one place (this class and
 * res/layout/view_code_keypad.xml) instead of being copied into two screens.
 *
 * **Its part in MVVM.** It is purely View layer, and deliberately "dumb": it does not remember the code
 * itself. Key presses go *out* through [listener] to the fragment, which forwards them to a
 * [CodeEntryViewModel]; the ViewModel's code comes back *in* through [render]. Keeping the digits in
 * the ViewModel rather than in this view is what lets them survive rotation and process death, and lets
 * the enable-the-verify-button rule live somewhere testable.
 *
 * **Why a compound view (a LinearLayout subclass).** The alternative — an `<include>` plus a helper
 * function — would still need somewhere to keep the list of boxes and the key-to-digit wiring. A
 * custom view owns both and gives the screen a typed object (`binding.codeKeypad`) with a small API.
 *
 * The system keyboard is never involved: nothing here is an EditText, so nothing can request it.
 */
class CodeKeypadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    /** Receives key presses. Two callbacks rather than one "key" event so digits arrive typed as Int. */
    interface Listener {
        fun onDigit(digit: Int)
        fun onBackspace()
    }

    /** Where key presses are sent. Set by the fragment; null presses are simply ignored. */
    var listener: Listener? = null

    // view_code_keypad.xml is a <merge> layout, so this binding inflates its children straight into
    // this LinearLayout rather than wrapping them in another one.
    private val binding = ViewCodeKeypadBinding.inflate(LayoutInflater.from(context), this)

    private val boxes = with(binding) { listOf(box1, box2, box3, box4, box5, box6) }

    init {
        orientation = VERTICAL

        // The layout and the rules must agree on the code length; failing loudly here catches a box
        // added or removed from the XML without CodeEntry.LENGTH changing with it.
        check(boxes.size == CodeEntry.LENGTH) { "view_code_keypad.xml must have ${CodeEntry.LENGTH} boxes" }

        // Index in this list == the digit the key types, so one loop wires all ten.
        val digitKeys = with(binding) { listOf(key0, key1, key2, key3, key4, key5, key6, key7, key8, key9) }
        digitKeys.forEachIndexed { digit, key ->
            key.setOnClickListener { listener?.onDigit(digit) }
        }
        binding.keyBackspace.setOnClickListener { listener?.onBackspace() }

        // Draw the empty state straight away, so the preview and the first frame show the first box
        // outlined before any ViewModel state has arrived.
        render(code = "", errorText = null)
    }

    /**
     * Draws [code] into the boxes, and [errorText] under them when it is not null. Called by the
     * fragment every time the ViewModel's code or error changes (see bindCodeEntry).
     *
     * Each box's look comes from two standard view states read by bg_auth_code_box.xml:
     *  - **activated** marks the box the next digit will go into — the yellow "focused" box. When the
     *    code is full, no box is activated, since there is nowhere left to type.
     *  - **selected** marks every box as part of a rejected code — the red outline.
     */
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
