package com.prash.petcare.ui.auth

import android.os.Bundle
import android.text.Annotation
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.os.bundleOf
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentAuthBinding
import com.prash.petcare.ui.auth.twofactor.TwoFactorFragment
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The log in / register screen — the app's front door.
 *
 * In MVVM this fragment is the **View**. It draws whatever [AuthViewModel] reports (which form is
 * showing, which fields are in error, whether a check is running) and forwards taps and submitted text
 * back. It contains no validation and no database access: both live in the ViewModel, which survives
 * rotation, and in [AuthValidation], which is unit-tested.
 *
 * Log in and register are one screen with a toggle rather than two destinations, as in the design.
 * Both forms are in the layout at once and only one is visible, which is how typed text survives
 * switching back and forth — see the note at the top of fragment_auth.xml.
 */
class AuthFragment : Fragment() {

    /**
     * ViewBinding for fragment_auth.xml. Nullable because the fragment can outlive its view (when it
     * is on the back stack, for example); holding the binding past [onDestroyView] would leak the whole
     * destroyed view hierarchy, so it is cleared there.
     */
    private var _binding: FragmentAuthBinding? = null

    /** Non-null accessor, valid only between [onCreateView] and [onDestroyView]. */
    private val binding get() = _binding!!

    /** Scoped to this fragment, so a sign-in in progress survives a rotation. */
    private val viewModel: AuthViewModel by viewModels { AuthViewModel.Factory }

    /**
     * The Activity-wide record of who is signed in. `activityViewModels` gives the very instance every
     * later screen reads the account from; see [SessionViewModel].
     */
    private val sessionViewModel: SessionViewModel by activityViewModels()

    /**
     * Each text field paired with the error key it clears when edited, and the TextInputLayout that
     * shows that error. Built once per view so [render] and the edit listeners share one list instead
     * of naming every field twice.
     */
    private lateinit var fields: List<Triple<AuthField, TextInputLayout, EditText>>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAuthBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        fields = with(binding) {
            listOf(
                Triple(AuthField.IDENTIFIER, identifierLayout, identifierInput),
                Triple(AuthField.LOGIN_PASSWORD, loginPasswordLayout, loginPasswordInput),
                Triple(AuthField.FULL_NAME, fullNameLayout, fullNameInput),
                Triple(AuthField.EMAIL, emailLayout, emailInput),
                Triple(AuthField.PHONE, phoneLayout, phoneInput),
                Triple(AuthField.REGISTER_PASSWORD, registerPasswordLayout, registerPasswordInput),
                Triple(AuthField.CONFIRM_PASSWORD, confirmPasswordLayout, confirmPasswordInput)
            )
        }

        // The placeholder states the real rule, built from the same constant the validation uses so
        // the two can never drift apart. getQuantityString picks the right plural for the language.
        binding.registerPasswordInput.hint = resources.getQuantityString(
            R.plurals.register_password_hint,
            AuthValidation.MIN_PASSWORD_LENGTH,
            AuthValidation.MIN_PASSWORD_LENGTH
        )

        bindModeToggle()
        bindConsentText()
        bindButtons()
        observeViewModel()
    }

    /**
     * Attaches the listeners that clear a field's error as it is edited.
     *
     * Done here rather than in [onViewCreated] on purpose. After a rotation Android restores each
     * EditText's text *between* those two callbacks, and a text-changed listener already attached would
     * fire for that restore — wiping the error messages the ViewModel had kept for the user. By this
     * point the restore is finished, so only real typing triggers the listeners.
     */
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        fields.forEach { (field, _, input) ->
            input.doOnTextChanged { _, _, _, _ -> viewModel.clearError(field) }
        }
        binding.consentCheckBox.setOnCheckedChangeListener { _, _ ->
            viewModel.clearError(AuthField.CONSENT)
        }
    }

    /** Wires the two halves of the log in / register toggle. */
    private fun bindModeToggle() {
        binding.loginModeButton.setOnClickListener { viewModel.setMode(AuthMode.LOGIN) }
        binding.registerModeButton.setOnClickListener { viewModel.setMode(AuthMode.REGISTER) }

        // The halves are plain TextViews, so TalkBack would otherwise announce them as text. Declaring
        // them as selectable tabs makes it say "Log in, tab, selected" — the same as a real tab bar.
        listOf(binding.loginModeButton, binding.registerModeButton).forEach { half ->
            ViewCompat.setAccessibilityDelegate(half, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfoCompat
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.roleDescription = getString(R.string.auth_tab_role)
                    info.isSelected = host.isSelected
                }
            })
        }
    }

    /**
     * Turns the consent sentence into text with a tappable "terms" link.
     *
     * The link's position comes from the `<annotation link="terms">` tag in strings.xml, not from
     * searching for the word, so a translation can move it anywhere. The rest of the sentence toggles
     * the checkbox, the way a checkbox's own label would.
     */
    private fun bindConsentText() {
        val source = getText(R.string.register_consent)
        val text = SpannableString(source)

        text.getSpans(0, text.length, Annotation::class.java)
            .filter { it.key == "link" && it.value == "terms" }
            .forEach { annotation ->
                text.setSpan(
                    TermsLinkSpan(),
                    text.getSpanStart(annotation),
                    text.getSpanEnd(annotation),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

        binding.consentText.text = text
        // Without a LinkMovementMethod a ClickableSpan draws as a link but never receives the tap.
        binding.consentText.movementMethod = LinkMovementMethod.getInstance()
        binding.consentText.setOnClickListener { binding.consentCheckBox.toggle() }
    }

    /**
     * The "terms" link inside the consent sentence.
     *
     * Tapping a link also counts as tapping the TextView that contains it — the view queues its own
     * click before the link handles the touch — which would tick the checkbox every time someone only
     * wanted to read the terms. cancelPendingInputEvents removes that queued click.
     */
    private inner class TermsLinkSpan : ClickableSpan() {

        override fun onClick(widget: View) {
            widget.cancelPendingInputEvents()
            // TODO(terms): there is no terms page yet. Navigate to it (or open its URL in a Custom
            //  Tab) once it exists; until then this only says so.
            showMessage(getString(R.string.auth_terms_unavailable))
        }

        /** Underlined in the full text colour, as in the mockup, rather than the default link blue. */
        override fun updateDrawState(paint: TextPaint) {
            super.updateDrawState(paint)
            paint.color = binding.consentText.linkTextColors.defaultColor
            paint.isUnderlineText = true
        }
    }

    /** Wires every button and the keyboard's "done" key. */
    private fun bindButtons() {
        binding.loginButton.setOnClickListener { submitLogin() }
        binding.registerButton.setOnClickListener { submitRegistration() }
        binding.googleButton.setOnClickListener { viewModel.onGoogleSignIn() }

        // "Done" on the log-in password submits, so a keyboard user never has to reach for the button.
        // The register form's last field is left to the default "done" (close the keyboard), because
        // the consent box still sits between it and the button.
        binding.loginPasswordInput.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) submitLogin() }
        }

        binding.forgotPasswordButton.setOnClickListener {
            // No arguments: the reset screen asks for the account's address itself, starting empty.
            // Navigating by action id keeps the route itself declared in nav_graph.xml.
            findNavController().navigate(R.id.action_auth_to_reset_choose)
        }
    }

    /** Hands the log-in form's text to the ViewModel, which decides whether it is valid. */
    private fun submitLogin() {
        viewModel.login(
            identifier = binding.identifierInput.textString(),
            password = binding.loginPasswordInput.textString()
        )
    }

    /** Hands the register form's text and the consent tick to the ViewModel. */
    private fun submitRegistration() {
        viewModel.register(
            fullName = binding.fullNameInput.textString(),
            email = binding.emailInput.textString(),
            phone = binding.phoneInput.textString(),
            password = binding.registerPasswordInput.textString(),
            confirmPassword = binding.confirmPasswordInput.textString(),
            hasAcceptedTerms = binding.consentCheckBox.isChecked
        )
    }

    /**
     * Collects the ViewModel's state and events while the screen is visible.
     *
     * `repeatOnLifecycle(STARTED)` starts collecting when the screen appears and cancels when it stops,
     * so nothing touches views in the background. The two collectors run in separate coroutines
     * because `collect` never returns — in sequence, the second would never start.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: AuthUiState) {
        val isLogin = state.mode == AuthMode.LOGIN

        // The heading and subtitle follow the mode.
        binding.header.show(
            title = if (isLogin) R.string.login_title else R.string.register_title,
            subtitle = getString(if (isLogin) R.string.login_subtitle else R.string.register_subtitle)
        )

        // `isSelected` is all the toggle needs — its drawable and text colour are state lists.
        binding.loginModeButton.isSelected = isLogin
        binding.registerModeButton.isSelected = !isLogin
        binding.loginForm.isVisible = isLogin
        binding.registerForm.isVisible = !isLogin

        // Resolving the string here, in the View layer, is what keeps Context out of the ViewModel.
        // A null error clears the message rather than leaving an empty red line.
        fields.forEach { (field, layout, _) ->
            layout.error = state.errors[field]?.let(::getString)
        }
        val consentError = state.errors[AuthField.CONSENT]
        binding.consentError.text = consentError?.let(::getString)
        binding.consentError.isVisible = consentError != null

        // While a check runs, nothing that could start a second one (or change the form under it) is
        // pressable.
        binding.progressIndicator.isVisible = state.isSubmitting
        listOf(
            binding.loginButton, binding.registerButton, binding.googleButton,
            binding.loginModeButton, binding.registerModeButton
        ).forEach { it.isEnabled = !state.isSubmitting }
    }

    /** Reacts to a one-off event from the ViewModel. */
    private fun handleEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.Authenticated -> {
                // Record the account *before* navigating: the home screen's ViewModel is created by
                // the navigation below and asks for the account immediately. See SessionViewModel for
                // why the account is held there rather than passed as an argument.
                sessionViewModel.signIn(event.userId)
                findNavController().navigate(R.id.action_auth_to_home)
            }

            // Not signed in yet: the two-factor screen does that once its code checks out.
            is AuthEvent.TwoFactorRequired -> findNavController().navigate(
                R.id.action_auth_to_two_factor,
                bundleOf(TwoFactorFragment.ARG_USER_ID to event.userId)
            )

            is AuthEvent.Message -> showMessage(getString(event.messageRes))
        }
    }

    private fun showMessage(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** The field's current text, never null. Shared by the sign-in screens that have text fields. */
internal fun EditText.textString(): String = text?.toString().orEmpty()
