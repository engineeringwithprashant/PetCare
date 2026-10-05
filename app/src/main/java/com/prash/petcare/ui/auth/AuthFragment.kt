package com.prash.petcare.ui.auth

import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.text.Annotation
import android.text.InputFilter
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.format.DateFormat
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.color.MaterialColors
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.prash.petcare.R
import com.prash.petcare.data.local.LoginAttemptStore
import com.prash.petcare.databinding.FragmentAuthBinding
import com.prash.petcare.ui.auth.twofactor.TwoFactorFragment
import com.prash.petcare.ui.common.PhoneNumberInput
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Date
import com.google.android.material.R as MaterialR

/** The log in / register screen — the app's front door. */
class AuthFragment : Fragment() {

    /** ViewBinding for fragment_auth.xml. Nullable because the fragment can outlive its view. */
    private var _binding: FragmentAuthBinding? = null

    /** Non-null accessor, valid only between [onCreateView] and [onDestroyView]. */
    private val binding get() = _binding!!

    /** Scoped to this fragment, so a sign-in in progress survives a rotation. */
    private val viewModel: AuthViewModel by viewModels { AuthViewModel.Factory }

    /** The Activity-wide record of who is signed in — the same instance every later screen reads. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    /** Each text field paired with the error key it clears when edited. */
    private lateinit var fields: List<Triple<AuthField, TextInputLayout, EditText>>

    /** The Google account picker currently open, if any. See [startGoogleSignIn]. */
    private var googleRequest: Job? = null

    /** The country selector and number box on the register form, driven as one control. */
    private var phoneInput: PhoneNumberInput? = null

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

        // The placeholder states the real rule, built from the same constant the validation uses.
        binding.registerPasswordInput.hint = resources.getQuantityString(
            R.plurals.register_password_hint,
            AuthValidation.MIN_PASSWORD_LENGTH,
            AuthValidation.MIN_PASSWORD_LENGTH
        )

        // The upper bound, applied from the same constant the rule uses rather than typed into the layout.
        listOf(binding.registerPasswordInput, binding.confirmPasswordInput).forEach { field ->
            field.filters = arrayOf(InputFilter.LengthFilter(AuthValidation.MAX_PASSWORD_LENGTH))
        }

        // The country selector and the number box are one control; this is what makes them behave as one.
        phoneInput = PhoneNumberInput(
            countryLayout = binding.phoneCountryLayout,
            countryInput = binding.phoneCountryInput,
            numberLayout = binding.phoneLayout,
            numberInput = binding.phoneInput,
            onChanged = { viewModel.clearError(AuthField.PHONE) }
        )

        bindConsentText()
        bindButtons()
        observeViewModel()
    }

    /** Attaches the listeners that clear a field's error as it is edited. */
    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)

        fields.forEach { (field, _, input) ->
            input.doOnTextChanged { _, _, _, _ -> viewModel.clearError(field) }
        }
        binding.consentCheckBox.setOnCheckedChangeListener { _, _ ->
            viewModel.clearError(AuthField.CONSENT)
        }

        // The log in address is reported as well as cleared.
        binding.identifierInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onIdentifierChanged(text?.toString().orEmpty())
        }

        // The chosen password is reported as well as cleared.
        binding.registerPasswordInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onPasswordChanged(text?.toString().orEmpty())
        }
        // Attached after the restore, like the listeners above, but the password still has to be measured once now.
        viewModel.onPasswordChanged(binding.registerPasswordInput.textString())

        // On a cold start the field is empty but the ViewModel may already hold the address of a lock left over from a previous run.
        val restored = viewModel.uiState.value.identifier
        if (restored.isNotEmpty() && binding.identifierInput.textString().isEmpty()) {
            binding.identifierInput.setText(restored)
        }
        viewModel.onIdentifierChanged(binding.identifierInput.textString())
    }

    /** Turns the consent sentence into text with a tappable "terms" link. */
    private fun bindConsentText() {
        binding.consentText.text = linkedText(R.string.register_consent, "terms") {
            // TODO(terms): there is no terms page yet. Navigate.
            showMessage(getString(R.string.auth_terms_unavailable))
        }
        // Without a LinkMovementMethod a ClickableSpan draws as a link but never receives the tap.
        binding.consentText.movementMethod = LinkMovementMethod.getInstance()
        binding.consentText.setOnClickListener { binding.consentCheckBox.toggle() }
    }

    /** Draws the footer line for [mode] with its last words as a link to the other form. */
    private fun bindSwitchFormText(mode: AuthMode) {
        val isLogin = mode == AuthMode.LOGIN
        val sentence = if (isLogin) R.string.login_register_prompt else R.string.register_login_prompt
        val target = if (isLogin) AuthMode.REGISTER else AuthMode.LOGIN

        binding.switchFormText.text = linkedText(sentence, if (isLogin) "register" else "login") {
            viewModel.setMode(target)
        }
        binding.switchFormText.movementMethod = LinkMovementMethod.getInstance()
    }

    /** Reads [sentenceRes] and makes the range marked `<annotation link="[key]">` tappable, running [onClick]. */
    private fun linkedText(
        @StringRes sentenceRes: Int,
        key: String,
        onClick: () -> Unit
    ): CharSequence {
        val text = SpannableString(getText(sentenceRes))

        text.getSpans(0, text.length, Annotation::class.java)
            .filter { it.key == "link" && it.value == key }
            .forEach { annotation ->
                text.setSpan(
                    InlineLinkSpan(onClick),
                    text.getSpanStart(annotation),
                    text.getSpanEnd(annotation),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        return text
    }

    /** A tappable range inside a sentence: "terms" in the consent line, "Create an account" in the footer. */
    private inner class InlineLinkSpan(private val onClick: () -> Unit) : ClickableSpan() {

        override fun onClick(widget: View) {
            widget.cancelPendingInputEvents()
            onClick.invoke()
        }

        /** Underlined in the full text colour, as in the mockups, rather than the default link blue. */
        override fun updateDrawState(paint: TextPaint) {
            super.updateDrawState(paint)
            paint.isUnderlineText = true
        }
    }

    /** Wires every button and the keyboard's "done" key. */
    private fun bindButtons() {
        binding.loginButton.setOnClickListener { submitLogin() }
        binding.registerButton.setOnClickListener { submitRegistration() }
        binding.googleButton.setOnClickListener { startGoogleSignIn() }

        // "Done" on the log-in password submits, so a keyboard user never has to reach for the button.
        binding.loginPasswordInput.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) submitLogin() }
        }

        binding.forgotPasswordButton.setOnClickListener { openPasswordReset() }

        // "RESET PASSWORD INSTEAD" on the locked screen goes to the same place.
        binding.lockedPanel.lockResetButton.setOnClickListener { openPasswordReset() }

        // "TRY AGAIN IN n MIN" is disabled for the whole lock, so this only ever runs once the wait.
        binding.lockedPanel.lockRetryButton.setOnClickListener {
            binding.identifierInput.requestFocus()
        }
    }

    /** Opens the password reset flow. */
    private fun openPasswordReset() {
        findNavController().navigate(R.id.action_auth_to_reset_request)
    }

    /** "Continue with Google", on either form. */
    private fun startGoogleSignIn() {
        if (googleRequest?.isActive == true) return
        val hasAcceptedTerms = binding.consentCheckBox.isChecked
        if (!viewModel.onGoogleSignInClicked(hasAcceptedTerms)) return

        val launcher = GoogleSignInLauncher(requireActivity())
        googleRequest = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.onGoogleCredentialResult(launcher.requestIdToken())
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
        val phone = phoneInput ?: return

        viewModel.register(
            fullName = binding.fullNameInput.textString(),
            email = binding.emailInput.textString(),
            phoneRegion = phone.regionCode(),
            phone = phone.nationalNumber(),
            password = binding.registerPasswordInput.textString(),
            confirmPassword = binding.confirmPasswordInput.textString(),
            hasAcceptedTerms = binding.consentCheckBox.isChecked
        )
    }

    /** Collects the ViewModel's state and events while the screen is visible. */
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
        val isLocked = state.lock != null

        // The heading and subtitle follow the mode, and step aside entirely for the locked panel.
        binding.header.show(
            title = if (isLogin) R.string.login_title else R.string.register_title,
            subtitle = getString(if (isLogin) R.string.login_subtitle else R.string.register_subtitle)
        )
        binding.header.title.isVisible = !isLocked
        binding.header.subtitle.isVisible = !isLocked

        // The lock replaces the whole form rather than covering.
        binding.authContent.isVisible = !isLocked
        binding.lockedPanel.root.isVisible = isLocked
        state.lock?.let(::renderLock)

        binding.loginForm.isVisible = isLogin
        binding.registerForm.isVisible = !isLogin
        bindSwitchFormText(state.mode)

        // The wrong-password warning, on the log in form only.
        renderAlert(state.alert)

        // The password checklist. Drawn from the ViewModel's measurement of the field.
        binding.registerPasswordChecklist.show(state.passwordRequirements)

        // Resolving the string here, in the View layer, is what keeps Context out of the ViewModel.
        fields.forEach { (field, layout, _) ->
            layout.error = state.errors[field]?.let(::getString)
        }
        val consentError = state.errors[AuthField.CONSENT]
        binding.consentError.text = consentError?.let(::getString)
        binding.consentError.isVisible = consentError != null

        // While a check runs, nothing that could start a second one (or change the form under it) is pressable.
        binding.progressIndicator.isVisible = state.isSubmitting
        listOf(
            binding.loginButton, binding.registerButton, binding.googleButton
        ).forEach { it.isEnabled = !state.isSubmitting }
        binding.switchFormText.isEnabled = !state.isSubmitting
    }

    /** Draws the wrong-password warning, or hides it when there is nothing to warn about. */
    private fun renderAlert(alert: LoginAlert?) {
        binding.loginAlert.root.isVisible = alert != null
        if (alert == null) return

        val isLastChance = alert.style == LoginAlertStyle.LAST_CHANCE

        binding.loginAlert.root.apply {
            setCardBackgroundColor(
                themeColor(
                    if (isLastChance) R.attr.colorAuthAlertDanger else R.attr.colorAuthAlertWarning
                )
            )
            strokeColor = themeColor(
                if (isLastChance) R.attr.colorAuthAlertDangerEdge else R.attr.colorAuthAlertWarningEdge
            )
        }

        binding.loginAlert.loginAlertBadge.apply {
            backgroundTintList = ColorStateList.valueOf(
                resolveColor(
                    if (isLastChance) R.color.auth_alert_danger_badge
                    else R.color.auth_alert_warning_badge
                )
            )
            setTextColor(
                resolveColor(
                    if (isLastChance) R.color.on_auth_alert_danger_badge
                    else R.color.on_auth_alert_warning_badge
                )
            )
        }

        binding.loginAlert.loginAlertTitle.apply {
            setText(
                if (isLastChance) R.string.login_alert_last_try
                else R.string.login_alert_wrong_password
            )
            // The red card's title carries the warning's weight, so it takes the alert colour.
            setTextColor(
                themeColor(
                    if (isLastChance) R.attr.colorOnAuthAlertDanger
                    else MaterialR.attr.colorOnSurface
                )
            )
        }

        binding.loginAlert.loginAlertDetail.text = getString(
            R.string.login_alert_detail,
            alert.attemptsRemaining,
            LoginAttemptStore.MAX_ATTEMPTS,
            LOCK_MINUTES
        )
    }

    /** Draws the locked panel: the countdown ring, the explanation, the last attempt, and the two buttons. */
    private fun renderLock(lock: LoginLock) {
        val panel = binding.lockedPanel
        val timeFormat = DateFormat.getTimeFormat(requireContext())

        // The ring empties over the whole lock, so it is a picture of the wait rather than of the last minute.
        val progress = lock.minutesRemaining * 100 / lock.lockDurationMinutes.coerceAtLeast(1)
        panel.lockRingProgress.setProgressCompat(progress.coerceIn(0, 100), true)
        // Through the locale's number format rather than toString().
        panel.lockMinutesText.text = NumberFormat.getIntegerInstance().format(lock.minutesRemaining)

        // The ring is two unlabelled numbers to TalkBack.
        panel.lockRing.contentDescription = resources.getQuantityString(
            R.plurals.login_locked_ring_description,
            lock.minutesRemaining,
            lock.minutesRemaining
        )

        panel.lockBodyText.text = resources.getQuantityString(
            R.plurals.login_locked_body,
            lock.failedAttempts,
            lock.failedAttempts,
            timeFormat.format(Date(lock.unlockAt))
        )

        panel.lockAttemptsTitle.text = resources.getQuantityString(
            R.plurals.login_locked_attempts_title,
            lock.failedAttempts,
            lock.failedAttempts
        )
        // Build.MODEL is the handset's own name ("Pixel 7").
        panel.lockAttemptsDetail.text = getString(
            R.string.login_locked_attempts_detail,
            timeFormat.format(Date(lock.lastAttemptAt)),
            Build.MODEL
        )

        panel.lockRetryButton.text = getString(R.string.login_locked_retry, lock.minutesRemaining)
        // Disabled for as long as the lock stands. The ViewModel puts the form back the moment it expires.
        panel.lockRetryButton.isEnabled = false
    }

    /** Resolves a theme colour *attribute* (?attr/…) to the colour it stands for in this theme. */
    @ColorInt
    private fun themeColor(@AttrRes attribute: Int): Int =
        MaterialColors.getColor(binding.root, attribute)

    /** Resolves a colour *resource* against the view's context, so it follows the current theme. */
    @ColorInt
    private fun resolveColor(@ColorRes colorRes: Int): Int =
        ContextCompat.getColor(requireContext(), colorRes)

    /** Reacts to a one-off event from the ViewModel. */
    private fun handleEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.Authenticated -> {
                // Record the account before navigating.
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
        // Dropped with the binding: it holds views from this hierarchy.
        phoneInput = null
        _binding = null
    }

    private companion object {

        /** The lock's length in whole minutes, for the warning card's "locks for 15 minutes" line. */
        val LOCK_MINUTES = (LoginAttemptStore.LOCK_DURATION_MILLIS / 60_000L).toInt()
    }
}

/** The field's current text, never null. Shared by the sign-in screens that have text fields. */
internal fun EditText.textString(): String = text?.toString().orEmpty()
