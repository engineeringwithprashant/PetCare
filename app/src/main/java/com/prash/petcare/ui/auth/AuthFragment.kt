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

/**
 * The log in / register screen — the app's front door.
 *
 * In MVVM this fragment is the **View**. It draws whatever [AuthViewModel] reports (which form is
 * showing, which fields are in error, whether a check is running) and forwards taps and submitted text
 * back. It contains no validation and no database access: both live in the ViewModel, which survives
 * rotation, and in [AuthValidation], which is unit-tested.
 *
 * Log in and register are one screen rather than two destinations, as in the design; the link at the
 * foot of each form switches between them. Both forms are in the layout at once and only one is
 * visible, which is how typed text survives switching back and forth — see the note at the top of
 * fragment_auth.xml.
 *
 * It also draws the two faces of the failed-attempt lockout: the inline warning card after a wrong
 * password, and the locked panel that replaces the form once there have been five. Neither decides
 * anything — the ViewModel works out what to show, including the live countdown, and this only turns
 * its state into views. See [com.prash.petcare.data.local.LoginAttemptStore] for what the lock is and
 * is not.
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
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    /**
     * Each text field paired with the error key it clears when edited, and the TextInputLayout that
     * shows that error. Built once per view so [render] and the edit listeners share one list instead
     * of naming every field twice.
     */
    private lateinit var fields: List<Triple<AuthField, TextInputLayout, EditText>>

    /** The Google account picker currently open, if any. See [startGoogleSignIn]. */
    private var googleRequest: Job? = null

    /**
     * The country selector and number box on the register form, driven as one control.
     *
     * Created in [onViewCreated] and dropped in [onDestroyView] with the binding it holds views from,
     * since it would otherwise keep the destroyed view hierarchy alive.
     */
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

        // The placeholder states the real rule, built from the same constant the validation uses so
        // the two can never drift apart. getQuantityString picks the right plural for the language.
        binding.registerPasswordInput.hint = resources.getQuantityString(
            R.plurals.register_password_hint,
            AuthValidation.MIN_PASSWORD_LENGTH,
            AuthValidation.MIN_PASSWORD_LENGTH
        )

        // The upper bound, applied from the same constant the rule uses rather than typed into the
        // layout. Capping the field means an over-long paste is trimmed as it lands instead of being
        // refused after the user has pressed the button.
        listOf(binding.registerPasswordInput, binding.confirmPasswordInput).forEach { field ->
            field.filters = arrayOf(InputFilter.LengthFilter(AuthValidation.MAX_PASSWORD_LENGTH))
        }

        // The country selector and the number box are one control; this is what makes them behave as
        // one. Editing either clears a stale error from the previous submit, exactly as the plain
        // fields do through the listeners in onViewStateRestored.
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

        // The log in address is reported as well as cleared, because the failed-attempt count and the
        // lock belong to the account being typed rather than to the screen. See
        // AuthViewModel.onIdentifierChanged.
        binding.identifierInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onIdentifierChanged(text?.toString().orEmpty())
        }

        // The chosen password is reported as well as cleared, because the checklist beneath it has to
        // answer every keystroke. The measuring is done by the ViewModel — see
        // AuthViewModel.onPasswordChanged for why the rules are applied there and not here.
        binding.registerPasswordInput.doOnTextChanged { text, _, _, _ ->
            viewModel.onPasswordChanged(text?.toString().orEmpty())
        }
        // Attached after the restore, like the listeners above, but the password still has to be
        // measured once now: after a rotation the field has its text back and the checklist would
        // otherwise sit on six empty rings until the next key was pressed.
        viewModel.onPasswordChanged(binding.registerPasswordInput.textString())

        // On a cold start the field is empty but the ViewModel may already hold the address of a
        // lock left over from a previous run (see its init block). Putting it into the field is what
        // keeps the two in step, so that when the wait ends the form returns with the account that
        // was locked already filled in. Setting the text fires the listener above with the same
        // value, which onIdentifierChanged ignores, so this cannot loop.
        val restored = viewModel.uiState.value.identifier
        if (restored.isNotEmpty() && binding.identifierInput.textString().isEmpty()) {
            binding.identifierInput.setText(restored)
        }
        viewModel.onIdentifierChanged(binding.identifierInput.textString())
    }

    /**
     * Turns the consent sentence into text with a tappable "terms" link.
     *
     * The link's position comes from the `<annotation link="terms">` tag in strings.xml, not from
     * searching for the word, so a translation can move it anywhere. The rest of the sentence toggles
     * the checkbox, the way a checkbox's own label would.
     */
    private fun bindConsentText() {
        binding.consentText.text = linkedText(R.string.register_consent, "terms") {
            // TODO(terms): there is no terms page yet. Navigate to it (or open its URL in a Custom
            //  Tab) once it exists; until then this only says so.
            showMessage(getString(R.string.auth_terms_unavailable))
        }
        // Without a LinkMovementMethod a ClickableSpan draws as a link but never receives the tap.
        binding.consentText.movementMethod = LinkMovementMethod.getInstance()
        binding.consentText.setOnClickListener { binding.consentCheckBox.toggle() }
    }

    /**
     * Draws the footer line for [mode] with its last words as a link to the other form.
     *
     * This replaced the toggle that used to sit above the fields — see the note at the top of
     * fragment_auth.xml for why the switch moved to the bottom of the page. It is rebuilt on each
     * render rather than once, because the sentence and the destination both change with the mode.
     */
    private fun bindSwitchFormText(mode: AuthMode) {
        val isLogin = mode == AuthMode.LOGIN
        val sentence = if (isLogin) R.string.login_register_prompt else R.string.register_login_prompt
        val target = if (isLogin) AuthMode.REGISTER else AuthMode.LOGIN

        binding.switchFormText.text = linkedText(sentence, if (isLogin) "register" else "login") {
            viewModel.setMode(target)
        }
        binding.switchFormText.movementMethod = LinkMovementMethod.getInstance()
    }

    /**
     * Reads [sentenceRes] and makes the range marked `<annotation link="[key]">` tappable, running
     * [onClick] when it is.
     *
     * Shared by the consent sentence and the footer link because both are the same idea: a line of
     * ordinary prose with two or three words that do something. Taking the range from the annotation
     * rather than searching for the words is what lets a translation move the link — see the note on
     * register_consent in strings.xml.
     */
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

    /**
     * A tappable range inside a sentence: "terms" in the consent line, "Create an account" in the
     * footer.
     *
     * Tapping a link also counts as tapping the TextView that contains it — the view queues its own
     * click before the link handles the touch — which would tick the consent checkbox every time
     * someone only wanted to read the terms. cancelPendingInputEvents removes that queued click.
     */
    private inner class InlineLinkSpan(private val onClick: () -> Unit) : ClickableSpan() {

        override fun onClick(widget: View) {
            widget.cancelPendingInputEvents()
            onClick.invoke()
        }

        /**
         * Underlined in the full text colour, as in the mockups, rather than the default link blue.
         * The colour comes from the host TextView's own link colour, so each style decides its own —
         * both happen to set it to the strong text colour against their muted sentence.
         */
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
        // The register form's last field is left to the default "done" (close the keyboard), because
        // the consent box still sits between it and the button.
        binding.loginPasswordInput.setOnEditorActionListener { _, actionId, _ ->
            (actionId == EditorInfo.IME_ACTION_DONE).also { if (it) submitLogin() }
        }

        binding.forgotPasswordButton.setOnClickListener { openPasswordReset() }

        // "RESET PASSWORD INSTEAD" on the locked screen goes to the same place: it is the one action
        // that ends the lock early, because sending a reset link clears it (see ResetRequestViewModel).
        binding.lockedPanel.lockResetButton.setOnClickListener { openPasswordReset() }

        // "TRY AGAIN IN n MIN" is disabled for the whole lock, so this only ever runs once the wait is
        // over — and by then the ViewModel's countdown has already put the form back. It is wired
        // anyway so the button is never a dead control if that ordering ever changes.
        binding.lockedPanel.lockRetryButton.setOnClickListener {
            binding.identifierInput.requestFocus()
        }
    }

    /**
     * Opens the password reset flow.
     *
     * No arguments: the reset screen asks for the account's email itself, starting empty. Navigating
     * by action id keeps the route itself declared in nav_graph.xml.
     */
    private fun openPasswordReset() {
        findNavController().navigate(R.id.action_auth_to_reset_request)
    }

    /**
     * "Continue with Google", on either form.
     *
     * The ViewModel first decides whether to start (the register form needs the terms ticked). Then
     * this fragment shows Google's account picker, because only the View layer holds the Activity
     * the picker draws over (see [GoogleSignInLauncher]). Whatever comes back is passed to the
     * ViewModel, which signs in to Firebase and decides what to show.
     *
     * The picker runs in the *view's* lifecycle scope, so leaving the screen cancels it.
     * [googleRequest] stops a quick second tap from opening a second picker while the first is on
     * its way.
     */
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

    /**
     * Hands the register form's text and the consent tick to the ViewModel.
     *
     * The phone number goes over as the country *and* the local digits, unjoined: the ViewModel is
     * what decides whether the two make a real number and what the stored international form is, so
     * handing it a pre-assembled string would mean assembling it in the View layer — and then having
     * nothing to validate against if it turned out to be wrong.
     */
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
        val isLocked = state.lock != null

        // The heading and subtitle follow the mode, and step aside entirely for the locked panel,
        // which carries a heading of its own — see fragment_auth.xml.
        binding.header.show(
            title = if (isLogin) R.string.login_title else R.string.register_title,
            subtitle = getString(if (isLogin) R.string.login_subtitle else R.string.register_subtitle)
        )
        binding.header.title.isVisible = !isLocked
        binding.header.subtitle.isVisible = !isLocked

        // The lock replaces the whole form rather than covering it: there is nothing useful to do
        // with a password field that cannot be submitted.
        binding.authContent.isVisible = !isLocked
        binding.lockedPanel.root.isVisible = isLocked
        state.lock?.let(::renderLock)

        binding.loginForm.isVisible = isLogin
        binding.registerForm.isVisible = !isLogin
        bindSwitchFormText(state.mode)

        // The wrong-password warning, on the log in form only. The register form has no attempts to
        // count, and the ViewModel never sets it there.
        renderAlert(state.alert)

        // The password checklist. Drawn from the ViewModel's measurement of the field, so what is
        // ticked here and what the form enforces on submit are the same answer.
        binding.registerPasswordChecklist.show(state.passwordRequirements)

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
            binding.loginButton, binding.registerButton, binding.googleButton
        ).forEach { it.isEnabled = !state.isSubmitting }
        binding.switchFormText.isEnabled = !state.isSubmitting
    }

    /**
     * Draws the wrong-password warning, or hides it when there is nothing to warn about.
     *
     * **One card, two colour schemes.** The design's yellow and red versions are the same layout with
     * a different fill, edge, badge and title colour, so those four are applied here rather than
     * duplicating view_auth_alert.xml — see the note at the top of that file. Resolving the colours in
     * the fragment is also what keeps them out of the ViewModel, which knows only that this is a
     * [LoginAlertStyle.WARNING] or a [LoginAlertStyle.LAST_CHANCE].
     */
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
            // The red card's title carries the warning's weight, so it takes the alert colour; the
            // yellow card's stays in the ordinary text colour, with the badge doing the signalling.
            // Both are set every time rather than only the red one, so switching back from red to
            // yellow restores the ordinary colour instead of leaving the last one applied.
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

    /**
     * Draws the locked panel: the countdown ring, the explanation, the last attempt, and the two
     * buttons.
     *
     * Called on every tick of the ViewModel's countdown — once a second — so it only sets values and
     * does no allocation beyond the formatted strings.
     *
     * The two times are formatted here rather than in the ViewModel because
     * [DateFormat.getTimeFormat] needs a Context: it follows the user's own 12-/24-hour setting, so
     * the same moment reads as "5:53 PM" or "17:53" depending on how their phone is set.
     */
    private fun renderLock(lock: LoginLock) {
        val panel = binding.lockedPanel
        val timeFormat = DateFormat.getTimeFormat(requireContext())

        // The ring empties over the whole lock, so it is a picture of the wait rather than of the
        // last minute. setProgressCompat animates between values, which at one tick a second reads as
        // a smooth sweep instead of a series of jumps.
        val progress = lock.minutesRemaining * 100 / lock.lockDurationMinutes.coerceAtLeast(1)
        panel.lockRingProgress.setProgressCompat(progress.coerceIn(0, 100), true)
        // Through the locale's number format rather than toString(), so the figure is drawn in the
        // digits the user's language actually uses — Devanagari numerals for a Nepali locale, say.
        panel.lockMinutesText.text = NumberFormat.getIntegerInstance().format(lock.minutesRemaining)

        // The ring is two unlabelled numbers to TalkBack, so the pair is described on the frame that
        // holds them and both children are hidden from it — see view_auth_locked.xml.
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
        // Build.MODEL is the handset's own name ("Pixel 7"), which is what makes "this device" mean
        // something to someone reading the row on a different phone later.
        panel.lockAttemptsDetail.text = getString(
            R.string.login_locked_attempts_detail,
            timeFormat.format(Date(lock.lastAttemptAt)),
            Build.MODEL
        )

        panel.lockRetryButton.text = getString(R.string.login_locked_retry, lock.minutesRemaining)
        // Disabled for as long as the lock stands. The ViewModel puts the form back the moment it
        // expires, so this never actually reaches its enabled state — it is the countdown in words.
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
        // Dropped with the binding: it holds views from this hierarchy, and keeping it would keep the
        // destroyed hierarchy alive for as long as the fragment sits on the back stack.
        phoneInput = null
        _binding = null
    }

    private companion object {

        /**
         * The lock's length in whole minutes, for the warning card's "locks for 15 minutes" line.
         *
         * Derived from [LoginAttemptStore.LOCK_DURATION_MILLIS] rather than written as 15, so the
         * sentence cannot end up promising a different wait from the one actually enforced.
         */
        val LOCK_MINUTES = (LoginAttemptStore.LOCK_DURATION_MILLIS / 60_000L).toInt()
    }
}

/** The field's current text, never null. Shared by the sign-in screens that have text fields. */
internal fun EditText.textString(): String = text?.toString().orEmpty()
