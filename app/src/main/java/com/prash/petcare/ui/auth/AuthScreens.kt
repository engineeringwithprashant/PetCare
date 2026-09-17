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

/**
 * Small View-layer helpers shared by the five sign-in screens.
 *
 * Each is an extension function on the ViewBinding of a shared layout (view_auth_header.xml,
 * view_auth_step_progress.xml), so a fragment writes `binding.header.show(...)` and every screen fills
 * the shared pieces the same way. None of them contains any business logic — they only put values
 * that the fragment already has into views.
 */

/** Fills in this screen's heading and subtitle under the shared logo block. */
fun ViewAuthHeaderBinding.show(@StringRes title: Int, subtitle: CharSequence) {
    this.title.setText(title)
    this.subtitle.text = subtitle
}

/**
 * Shows "STEP [step] OF [total]" and fills the first [step] segments of the bar.
 *
 * One call sets both, so the bar and the label can never disagree about which step this is.
 *
 * @param segments How many segments to draw. Normally the same as [total]; the two-factor screen
 *   passes 3 for a two-step flow because the mockup draws its bar with three segments, all filled.
 */
fun ViewAuthStepProgressBinding.showStep(step: Int, total: Int, segments: Int = total) {
    stepBar.setSegments(List(segments) { index -> index < step || step >= total })
    stepLabel.text = root.resources.getString(R.string.auth_step_of, step, total)
}

/**
 * Closes the system keyboard if it is open, for the two screens that enter codes with their own
 * keypad.
 *
 * Those screens have no text field, so nothing on them can *open* the keyboard — but it can still be
 * showing when they arrive, left over from typing a password on the previous screen. The insets
 * controller is the current API for this: it asks the window to hide the IME, and works the same from
 * Android 7 to the latest release. Clearing the old focus stops the keyboard coming back for a field
 * that is no longer on screen.
 */
fun Fragment.keepSystemKeyboardClosed() {
    val window = requireActivity().window
    window.currentFocus?.clearFocus()
    WindowCompat.getInsetsController(window, requireView()).hide(WindowInsetsCompat.Type.ime())
}

/**
 * Connects a [CodeKeypadView] and its screen's verify button to a [CodeEntryViewModel], in both
 * directions:
 *  - **out**: keypad presses go to [CodeEntryViewModel.onDigit], [CodeEntryViewModel.onBackspace] and
 *    [CodeEntryViewModel.onClear], and the verify button to [CodeEntryViewModel.onVerify];
 *  - **in**: the typed code and any error are drawn into the keypad, and the button is enabled only
 *    while [CodeEntryViewModel.canVerify] is true.
 *
 * Both code screens call this one function, so their behaviour cannot drift apart. It must be called
 * from `onViewCreated`: the collection is tied to the *view's* lifecycle, and `repeatOnLifecycle`
 * pauses it whenever the screen is not visible.
 */
fun Fragment.bindCodeEntry(keypad: CodeKeypadView, verifyButton: View, viewModel: CodeEntryViewModel) {
    keypad.listener = object : CodeKeypadView.Listener {
        override fun onDigit(digit: Int) = viewModel.onDigit(digit)
        override fun onBackspace() = viewModel.onBackspace()
        override fun onClear() = viewModel.onClear()
    }
    verifyButton.setOnClickListener { viewModel.onVerify() }

    viewLifecycleOwner.lifecycleScope.launch {
        viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The boxes need the code and its error together, so the two flows are combined and the
            // keypad redrawn once per change of either.
            launch {
                combine(viewModel.code, viewModel.codeError) { code, error -> code to error }
                    .collect { (code, error) -> keypad.render(code, error?.let(::getString)) }
            }
            // Disabled until all six digits are in; the charcoal resting look comes from the button's
            // colour state list.
            launch { viewModel.canVerify.collect { verifyButton.isEnabled = it } }
        }
    }
}
