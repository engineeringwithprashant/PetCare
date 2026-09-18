package com.prash.petcare.ui.auth.twofactor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentTwoFactorSetupBinding
import com.prash.petcare.ui.auth.bindCodeEntry
import com.prash.petcare.ui.session.SessionViewModel
import com.prash.petcare.util.QrCodeRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Setting two-factor authentication up: scan the QR code, then prove it worked.
 *
 * Reached from the two-factor switch on the profile screen. The **View** in MVVM — it draws what
 * [TwoFactorSetupViewModel] publishes, forwards keypad presses to it, and moves to the recovery-codes
 * screen when the ViewModel reports the code was right. It decides three things of its own, each
 * genuinely a screen's job: turning the URI into a picture, putting the secret on the clipboard, and
 * the wording.
 *
 * **Nothing is switched on by this screen.** The secret is not stored until the *next* screen's
 * "I have saved them" — see [TwoFactorSetupViewModel] for why proof-then-store is the order, and why
 * that makes backing out of here cost nothing at all.
 */
class TwoFactorSetupFragment : Fragment() {

    private var _binding: FragmentTwoFactorSetupBinding? = null
    private val binding get() = _binding!!

    /** Whose account is being protected — the signed-in one, as on every screen inside the app. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: TwoFactorSetupViewModel by viewModels {
        TwoFactorSetupViewModel.factory(sessionViewModel.userId.value)
    }

    /**
     * The URI the QR image currently shows.
     *
     * Kept so the code is encoded once rather than on every state emission: the state changes each
     * time a digit is typed, and re-encoding a QR code per keystroke would be real work for a picture
     * that has not changed.
     */
    private var renderedUri: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTwoFactorSetupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keypad, boxes and the confirm button, wired by the same helper the sign-in code screens
        // use — so the boxes fill, clear and show their error identically here.
        bindCodeEntry(binding.codeKeypad, binding.confirmButton, viewModel)

        binding.copySecretButton.setOnClickListener { copySecret() }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.uiState.collect(::render) }
                launch { viewModel.events.collect(::handleEvent) }
            }
        }
    }

    private fun render(state: TwoFactorSetupUiState) {
        binding.setupProgress.isVisible = state.isStarting

        // The account no longer exists — deleted on another device and pulled down by a sync while
        // this screen was opening. Everything that would describe a setup is hidden, because there is
        // no account left to set anything up for.
        binding.setupFailedText.isVisible = state.startFailed
        binding.setupContent.isVisible = !state.isStarting && !state.startFailed

        val secret = state.secret ?: return

        // Grouped in fours. A 32-character Base32 string typed by hand is where transcription errors
        // come from, and spacing it is the cheapest thing that helps; authenticator apps ignore the
        // spaces. monospace in the layout keeps the groups lined up.
        binding.secretText.text = secret.chunked(SECRET_GROUP_SIZE).joinToString(" ")

        binding.setupAccountText.text = getString(
            R.string.two_factor_setup_account,
            state.accountEmail.orEmpty()
        )

        state.otpAuthUri?.let(::showQrCode)
    }

    /**
     * Encodes the URI and puts it in the image.
     *
     * **Off the main thread.** Encoding a QR code is a few milliseconds of real computation, and this
     * runs while the screen is being drawn — on the main thread it is the kind of thing that shows up
     * as a dropped frame on an older handset. `withContext(Dispatchers.Default)` moves the work and
     * comes back to the main thread for the view, and running inside the *view's* lifecycle scope
     * means an encode still in flight when the user leaves is cancelled rather than finishing and
     * writing into a destroyed view.
     *
     * The size comes from the view rather than a constant, so the bitmap is generated at exactly the
     * size it will be drawn and never resampled — a resampled QR code goes soft at the module edges,
     * which is precisely what a scanner is looking at.
     */
    private fun showQrCode(uri: String) {
        if (renderedUri == uri) return
        renderedUri = uri

        // The two views are captured here, before the post, rather than reached through `binding`
        // inside it. `binding` throws once the view is destroyed, and a posted runnable can still run
        // after that; holding the views directly means the worst case is writing into a view nobody
        // is looking at, which is harmless.
        val image = binding.qrImage
        val fallback = binding.qrFallbackText

        // Posted because the width is needed and is zero until the view has been measured.
        image.post {
            val size = image.width
            if (size <= 0) return@post

            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) { QrCodeRenderer.encode(uri, size) }

                image.setImageBitmap(bitmap)

                // A QR code that could not be drawn is not the end of setup: the secret is printed
                // underneath and can be typed into the authenticator by hand, which is exactly what
                // the fallback line says.
                fallback.isVisible = bitmap == null
            }
        }
    }

    private fun handleEvent(event: TwoFactorSetupEvent) {
        when (event) {
            is TwoFactorSetupEvent.Confirmed -> findNavController().navigate(
                R.id.action_two_factor_setup_to_recovery_codes,
                bundleOf(
                    RecoveryCodesFragment.ARG_SECRET to event.secret,
                    // A String array rather than a list: that is what a nav argument of type
                    // `string[]` carries, and what Bundle can put through saved instance state.
                    RecoveryCodesFragment.ARG_CODES to event.recoveryCodes.toTypedArray()
                )
            )
        }
    }

    /**
     * Copies the secret to the clipboard, for setting up an authenticator on this same phone.
     *
     * The *unspaced* secret is copied, not the grouped text on screen — the spaces are a reading aid
     * and pasting them into an app that is stricter than it needs to be would be a puzzling failure.
     *
     * The confirmation message is suppressed on Android 13 and above, where the system shows its own
     * "copied" popup; showing both would tell the user the same thing twice, in two different places.
     *
     * The clip is marked **sensitive**, so Android 13 and above leaves the value out of the preview
     * it pops up, and clipboard managers that honour the flag keep it out of their history. This is a
     * credential — see [com.prash.petcare.data.repository.TwoFactorRepository] on exactly what it
     * does and does not protect. The flag is set through its documented extra name rather than
     * `ClipDescription.EXTRA_IS_SENSITIVE`, which is a constant only API 33 has; an older system
     * simply ignores an extra it does not recognise, so no version check is needed.
     */
    private fun copySecret() {
        val secret = viewModel.uiState.value.secret ?: return

        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.two_factor_setup_secret_label), secret)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(EXTRA_IS_SENSITIVE, true)
        }
        clipboard.setPrimaryClip(clip)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Snackbar.make(
                binding.root,
                R.string.two_factor_setup_secret_copied,
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        // The bitmap went with the destroyed ImageView, so the next view needs the code drawn again.
        // Without this, returning to the screen from the recovery-codes step would find the URI
        // unchanged, skip the encode, and leave an empty square.
        renderedUri = null
    }

    private companion object {

        /** How many characters of the secret are shown between spaces. */
        const val SECRET_GROUP_SIZE = 4

        /**
         * The clip extra that marks a copied value as sensitive, as `ClipDescription` documents it.
         * Spelled out because the constant itself only exists from API 33; see [copySecret].
         */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
