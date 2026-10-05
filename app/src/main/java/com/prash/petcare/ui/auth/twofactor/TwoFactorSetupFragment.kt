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

/** Setting two-factor authentication up: scan the QR code, then prove it worked. */
class TwoFactorSetupFragment : Fragment() {

    private var _binding: FragmentTwoFactorSetupBinding? = null
    private val binding get() = _binding!!

    /** Whose account is being protected — the signed-in one, as on every screen inside the app. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: TwoFactorSetupViewModel by viewModels {
        TwoFactorSetupViewModel.factory(sessionViewModel.userId.value)
    }

    /** The URI the QR image currently shows. */
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

        // Keypad, boxes and the confirm button, wired by the same helper the sign-in code screens use.
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

        // The account no longer exists — deleted on another device and pulled down by a sync.
        binding.setupFailedText.isVisible = state.startFailed
        binding.setupContent.isVisible = !state.isStarting && !state.startFailed

        val secret = state.secret ?: return

        // Grouped in fours. A 32-character Base32 string typed by hand is where transcription errors come.
        binding.secretText.text = secret.chunked(SECRET_GROUP_SIZE).joinToString(" ")

        binding.setupAccountText.text = getString(
            R.string.two_factor_setup_account,
            state.accountEmail.orEmpty()
        )

        state.otpAuthUri?.let(::showQrCode)
    }

    /** Encodes the URI and puts it in the image. */
    private fun showQrCode(uri: String) {
        if (renderedUri == uri) return
        renderedUri = uri

        // The two views are captured here, before the post, rather than reached through `binding` inside it.
        val image = binding.qrImage
        val fallback = binding.qrFallbackText

        // Posted because the width is needed and is zero until the view has been measured.
        image.post {
            val size = image.width
            if (size <= 0) return@post

            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) { QrCodeRenderer.encode(uri, size) }

                image.setImageBitmap(bitmap)

                // A QR code that could not be drawn is not the end of setup.
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
                    // A String array rather than a list: that is what a nav argument of type `string[]` carries.
                    RecoveryCodesFragment.ARG_CODES to event.recoveryCodes.toTypedArray()
                )
            )
        }
    }

    /** Copies the secret to the clipboard, for setting up an authenticator on this same phone. */
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
        renderedUri = null
    }

    private companion object {

        /** How many characters of the secret are shown between spaces. */
        const val SECRET_GROUP_SIZE = 4

        /** The clip extra that marks a copied value as sensitive, as `ClipDescription` documents it. */
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
