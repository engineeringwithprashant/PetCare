package com.prash.petcare.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

/**
 * Placeholder landing screen shown once a user is signed in.
 *
 * It exists so the authentication flow has a real destination and can be tested end to end. The
 * pet list, care tasks and expense summary will be built here next.
 *
 * See [com.prash.petcare.ui.auth.LoginFragment] for the detailed notes on the ViewBinding
 * lifecycle and flow-collection pattern used here.
 */
class HomeFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in, supplied as the navigation argument declared on this
     * destination in nav_graph.xml.
     *
     * `requireArguments` rather than the nullable `arguments`: reaching this screen without a
     * user id is a bug in the navigation graph, and failing loudly here is far easier to diagnose
     * than silently showing an empty screen.
     */
    private val userId: Long by lazy { requireArguments().getLong(ARG_USER_ID) }

    private val viewModel: HomeViewModel by viewModels { HomeViewModel.factory(userId) }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.user.collect { user ->
                    // Null while the first database read is still in flight, so the greeting is
                    // left blank for that moment rather than showing a placeholder name.
                    binding.welcomeText.text = user?.let {
                        getString(R.string.home_welcome, it.fullName)
                    }.orEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /**
         * Key for the user id argument. Must match the `userId` argument name declared for this
         * destination in nav_graph.xml, and the key the auth fragments put in the Bundle.
         */
        const val ARG_USER_ID = "userId"
    }
}
