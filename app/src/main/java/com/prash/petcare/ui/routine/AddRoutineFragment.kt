package com.prash.petcare.ui.routine

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.google.android.material.snackbar.Snackbar
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.RepeatUnit
import com.prash.petcare.databinding.FragmentAddRoutineBinding
import com.prash.petcare.ui.common.setToolbarTitle
import kotlinx.coroutines.launch

/**
 * Form for creating a care routine, or renaming and rescheduling an existing one.
 *
 * A routine is the schedule a group of tasks share — "every morning", "every Sunday". The class keeps
 * its original "Add" name because creating is still what it is mostly for; editing arrives through
 * the same destination with a real `routineId`, from the "Edit" item on a routine card's menu on the
 * pet detail screen. Which mode is in use is entirely the ViewModel's to interpret — this fragment
 * renders whatever state comes back.
 *
 * As the **View** in MVVM it holds no logic: validation and persistence live in [RoutineViewModel].
 */
class AddRoutineFragment : Fragment() {

    /** Nulled in [onDestroyView] to avoid leaking the destroyed view hierarchy. */
    private var _binding: FragmentAddRoutineBinding? = null
    private val binding get() = _binding!!

    private val petId: Long by lazy { requireArguments().getLong(ARG_PET_ID) }

    private val routineId: Long by lazy {
        requireArguments().getLong(ARG_ROUTINE_ID, RoutineViewModel.NO_ROUTINE_ID)
    }

    private val viewModel: RoutineViewModel by viewModels {
        RoutineViewModel.factory(petId, routineId)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAddRoutineBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setUpListeners()
        observeViewModel()
    }

    private fun setUpListeners() {
        /*
         * The group reports both the button being selected and the one being cleared, so the
         * `isChecked` flag is tested — without it the schedule would be set twice on every tap,
         * once from the option the user chose and once from the option they moved away from.
         */
        binding.scheduleToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener

            val repeatUnit = when (checkedId) {
                R.id.dailyButton -> RepeatUnit.DAY
                R.id.weeklyButton -> RepeatUnit.WEEK
                // Unreachable: the group holds only those two buttons.
                else -> return@addOnButtonCheckedListener
            }
            viewModel.onScheduleSelected(repeatUnit)
        }

        binding.saveRoutineButton.setOnClickListener {
            viewModel.save(binding.routineNameInput.text?.toString().orEmpty())
        }

        binding.routineNameInput.doOnTextChanged { _, _, _, _ -> viewModel.clearTitleError() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.formState.collect { state -> render(state) }
                }
                launch {
                    viewModel.events.collect { event -> handleEvent(event) }
                }
            }
        }
    }

    /** Applies the current state to the views. */
    private fun render(state: RoutineFormState) {
        // The mode is the screen's title, and the save button says what pressing it will do.
        setToolbarTitle(
            getString(if (state.isEditMode) R.string.edit_routine_title else R.string.add_routine_title)
        )
        binding.saveRoutineButton.setText(
            if (state.isEditMode) R.string.routine_save_changes else R.string.routine_save
        )

        binding.routineNameInputLayout.error = state.titleError?.let { getString(it) }

        // Reflects the ViewModel's choice back onto the toggle. `check` is a no-op when the right
        // button is already selected, so this does not fight the user or loop with the listener.
        val buttonId = when (state.repeatUnit) {
            RepeatUnit.DAY -> R.id.dailyButton
            RepeatUnit.WEEK -> R.id.weeklyButton
            // The form offers only daily and weekly; anything else falls back to the daily button
            // so the group is never left with nothing selected.
            else -> R.id.dailyButton
        }
        binding.scheduleToggleGroup.check(buttonId)

        binding.progressIndicator.visibility = if (state.isSaving) View.VISIBLE else View.GONE
        binding.saveRoutineButton.isEnabled = !state.isSaving
    }

    private fun handleEvent(event: RoutineEvent) {
        when (event) {
            // Written once, from a one-shot event, so it never overwrites the user's typing. After a
            // rotation the EditText restores its own text, and the event is not repeated.
            is RoutineEvent.PrefillForm -> binding.routineNameInput.setText(event.title)

            // The pet detail screen behind this one is already observing the database, so it shows
            // the new or changed routine as soon as this screen closes.
            is RoutineEvent.Saved -> findNavController().navigateUp()

            is RoutineEvent.Error ->
                Snackbar.make(binding.root, getString(event.messageRes), Snackbar.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** Navigation argument names; must match those declared in nav_graph.xml. */
        const val ARG_PET_ID = "petId"
        const val ARG_ROUTINE_ID = "routineId"
    }
}
