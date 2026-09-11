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
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentHomeBinding
import kotlinx.coroutines.launch

/**
 * The screen shown once a user is signed in: a greeting, today's care summary, and their pets.
 *
 * In MVVM this is the **View**. It renders whatever [HomeViewModel] publishes and forwards taps
 * back — it performs no queries and calculates nothing itself, which is what allows it to be
 * destroyed and rebuilt on rotation without losing or duplicating work.
 */
class HomeFragment : Fragment() {

    /**
     * ViewBinding for fragment_home.xml.
     *
     * Nullable because a fragment's view can be destroyed while the fragment itself lives on;
     * holding the binding past that point would leak the whole view hierarchy. Cleared in
     * [onDestroyView].
     */
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in, supplied as the navigation argument declared on this
     * destination in nav_graph.xml.
     *
     * `requireArguments` rather than the nullable `arguments`: arriving here without a user id
     * means the navigation graph is wrong, and failing loudly is easier to diagnose than a
     * silently empty screen.
     */
    private val userId: Long by lazy { requireArguments().getLong(ARG_USER_ID) }

    private val viewModel: HomeViewModel by viewModels { HomeViewModel.factory(userId) }

    /**
     * Created once and kept for the life of the view, rather than being rebuilt on each update.
     * A new adapter would discard the previous list, so DiffUtil would have nothing to compare
     * against and every row would be redrawn.
     */
    private val petAdapter = PetAdapter()

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
        setUpRecyclerView()
        setUpClickListeners()
        observeViewModel()
    }

    /** Prepares the pet list. */
    private fun setUpRecyclerView() {
        binding.petsRecyclerView.apply {
            // The layout manager decides how rows are positioned; a vertical linear list here.
            layoutManager = LinearLayoutManager(requireContext())
            adapter = petAdapter

            // Every row has identical dimensions, so the RecyclerView can skip re-measuring
            // itself each time the contents change — a small scrolling win.
            setHasFixedSize(true)
        }
    }

    private fun setUpClickListeners() {
        binding.addPetFab.setOnClickListener {
            // Placeholder until the add-pet screen exists. It is wired up now so the button is
            // never silently dead, and so the navigation call has an obvious home later.
            Snackbar.make(
                binding.root,
                getString(R.string.home_add_pet_coming_soon),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Subscribes to the ViewModel's state.
     *
     * `repeatOnLifecycle(STARTED)` begins collecting when the screen becomes visible and cancels
     * when it stops, so the fragment never does work in the background or touches views after
     * they have been destroyed. `viewLifecycleOwner` is used because the view has a shorter life
     * than the fragment, and these are the view's updates.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state -> render(state) }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: HomeUiState) {
        // Blank rather than a half-finished greeting while the name is still being read.
        binding.welcomeText.text = if (state.userName.isNotEmpty()) {
            getString(R.string.home_welcome, state.userName)
        } else {
            ""
        }

        binding.todaySummaryText.text = summaryText(state)

        // submitList hands the whole new list to DiffUtil, which works out what changed and
        // animates only those rows.
        petAdapter.submitList(state.pets)

        // The list and the empty state occupy the same space, so exactly one is ever visible.
        // Both stay hidden during the initial load, which stops "No pets yet" flashing up before
        // the first query returns.
        binding.petsRecyclerView.visibility = if (state.pets.isNotEmpty()) View.VISIBLE else View.GONE
        binding.emptyStateContainer.visibility = if (state.hasNoPets) View.VISIBLE else View.GONE
    }

    /** Builds today's care summary line. */
    private fun summaryText(state: HomeUiState): String =
        if (state.tasksDueToday == 0) {
            getString(R.string.home_tasks_none)
        } else {
            // getQuantityString picks the right plural form for the device's language. The first
            // argument selects the form; the two after it fill the placeholders.
            resources.getQuantityString(
                R.plurals.home_tasks_summary,
                state.tasksDueToday,
                state.tasksCompletedToday,
                state.tasksDueToday
            )
        }

    override fun onDestroyView() {
        super.onDestroyView()
        // Release the reference to the destroyed view hierarchy — see the note on [_binding].
        _binding = null
    }

    companion object {
        /**
         * Key for the user id argument. Must match the `userId` argument declared for this
         * destination in nav_graph.xml, and the key the auth fragments put in the Bundle.
         */
        const val ARG_USER_ID = "userId"
    }
}
