package com.prash.petcare.ui.auth

import android.view.View
import androidx.annotation.StringRes
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewAuthHeaderBinding
import com.prash.petcare.databinding.ViewAuthStepProgressBinding
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Small View-layer helpers shared by the sign-in screens. */

/** Fills in this screen's heading and subtitle under the shared logo block. */
fun ViewAuthHeaderBinding.show(@StringRes title: Int, subtitle: CharSequence) {
    this.title.setText(title)
    this.subtitle.text = subtitle
}

/** Shows "STEP [step] OF [total]" and fills the first [step] segments of the bar. */
fun ViewAuthStepProgressBinding.showStep(step: Int, total: Int, segments: Int = total) {
    stepBar.setSegments(List(segments) { index -> index < step || step >= total })
    stepLabel.text = root.resources.getString(R.string.auth_step_of, step, total)
}

/** Closes the system keyboard if it is open, for screens with no text field of their own: the two-factor screen. */
fun Fragment.keepSystemKeyboardClosed() {
    val window = requireActivity().window
    window.currentFocus?.clearFocus()
    WindowCompat.getInsetsController(window, requireView()).hide(WindowInsetsCompat.Type.ime())
}

/** Connects a [CodeKeypadView] and its screen's verify button to a [CodeEntryViewModel], in both directions: - out. */
fun Fragment.bindCodeEntry(keypad: CodeKeypadView, verifyButton: View, viewModel: CodeEntryViewModel) {
    keypad.listener = object : CodeKeypadView.Listener {
        override fun onDigit(digit: Int) = viewModel.onDigit(digit)
        override fun onBackspace() = viewModel.onBackspace()
        override fun onClear() = viewModel.onClear()
    }
    verifyButton.setOnClickListener { viewModel.onVerify() }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The boxes need the code and its error together.
            launch {
                combine(viewModel.code, viewModel.codeError) { code, error -> code to error }
                    .collect { (code, error) -> keypad.render(code, error?.let(::getString)) }
            }
            // Disabled until all six digits.
            launch { viewModel.canVerify.collect { verifyButton.isEnabled = it } }
        }
    }
}
