package com.prash.petcare.ui.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.prash.petcare.R
import com.prash.petcare.databinding.FragmentAssistantBinding
import com.prash.petcare.ui.common.bindHeaderFromSession
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/** The Assistant screen, opened from the dashboard's "Ask the assistant" button and its AI pill. */
class AssistantFragment : Fragment() {

    /** ViewBinding for fragment_assistant.xml. */
    private var _binding: FragmentAssistantBinding? = null
    private val binding get() = _binding!!

    /** Which account is signed in, read from the Activity-wide session. */
    private val sessionViewModel: SessionViewModel by activityViewModels { SessionViewModel.Factory }

    private val viewModel: AssistantViewModel by viewModels {
        AssistantViewModel.factory(sessionViewModel.userId.value)
    }

    /** The conversation adapter, rebuilt with each view rather than held for the fragment's life. */
    private var messageAdapter: AssistantAdapter? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAssistantBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // The shared header, as on every redesigned screen. The AI pill is deliberately not shown here.
        binding.header.setUp(
            title = R.string.nav_assistant,
            onBadgeClick = ::openNotifications,
            onMonogramClick = ::openProfile
        )

        // Keeps the header's mark showing the user's profile photo, falling back to the app's monogram.
        bindHeaderFromSession(binding.header, sessionViewModel)

        setUpList()
        setUpInput()
        keepInputClearOfNavBar()
        keepNewestMessageVisibleWhenListShrinks()
        observeViewModel()
    }

    /** Keeps the question field and the send button out from under the floating navigation bar. */
    private fun keepInputClearOfNavBar() {
        val clearance = resources.getDimensionPixelSize(R.dimen.assistant_input_nav_clearance)

        ViewCompat.setOnApplyWindowInsetsListener(binding.inputRow) { row, windowInsets ->
            val isKeyboardVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
            row.updatePadding(bottom = if (isKeyboardVisible) 0 else clearance)
            windowInsets
        }
    }

    /** Keeps the newest message in view when the list is made shorter. */
    private fun keepNewestMessageVisibleWhenListShrinks() {
        binding.messagesRecyclerView.addOnLayoutChangeListener {
            view, _, top, _, bottom, _, oldTop, _, oldBottom ->
            val shrank = (bottom - top) < (oldBottom - oldTop)
            if (shrank) view.post { scrollToNewestMessage() }
        }
    }

    /** Puts the last message at the bottom of the list, if there is one. */
    private fun scrollToNewestMessage() {
        val binding = _binding ?: return
        val lastPosition = (messageAdapter?.itemCount ?: 0) - 1
        if (lastPosition >= 0) {
            binding.messagesRecyclerView.scrollToPosition(lastPosition)
        }
    }

    /** Prepares the conversation list. */
    private fun setUpList() {
        messageAdapter = AssistantAdapter()
        binding.messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = messageAdapter
        }
    }

    /** Wires the question field and the send button. */
    private fun setUpInput() {
        binding.sendButton.setOnClickListener { sendTypedQuestion() }

        binding.questionInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTypedQuestion()
                true
            } else {
                false
            }
        }

        // The send button's enabled state depends on the field as well as on the ViewModel.
        binding.questionInput.doAfterTextChanged { updateSendEnabled() }
    }

    /** Sends whatever is typed and clears the field. Blank input is ignored by the ViewModel. */
    private fun sendTypedQuestion() {
        val question = binding.questionInput.text?.toString().orEmpty()
        if (question.isBlank()) return

        viewModel.send(question)
        binding.questionInput.text?.clear()
    }

    /** Subscribes to the ViewModel. */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    /** Applies one state to the header, the conversation, the chips and the input row. */
    private fun render(state: AssistantUiState) {
        renderTitle(state.petNames)
        renderMessages(state.messages)
        renderSuggestions(state)
        updateSendEnabled(state.isReplying)
    }

    /** "Ask about Krishala & Ddjw", built from the account's actual pets. */
    private fun renderTitle(petNames: List<String>) {
        binding.assistantTitleText.text = if (petNames.isEmpty()) {
            getString(R.string.assistant_header_title_no_pets)
        } else {
            getString(
                R.string.assistant_header_title,
                petNames.joinToString(getString(R.string.assistant_pet_name_separator))
            )
        }
    }

    /** Submits the conversation and keeps the newest message in view. */
    private fun renderMessages(messages: List<AssistantMessage>) {
        // The scroll runs in submitList's completion callback, which fires after the new item has been laid out.
        messageAdapter?.submitList(messages) { scrollToNewestMessage() }
    }

    /** Rebuilds the suggested-question row, or hides it once the conversation has started. */
    private fun renderSuggestions(state: AssistantUiState) {
        binding.suggestionsScroll.isVisible = state.showSuggestions
        binding.suggestionsGroup.removeAllViews()

        if (!state.showSuggestions) return

        state.suggestions.forEach { suggestion ->
            val question = questionFor(suggestion) ?: return@forEach
            val chip = layoutInflater.inflate(
                R.layout.item_assistant_suggestion,
                binding.suggestionsGroup,
                false
            ) as Chip

            chip.text = question
            // Tapping a chip sends its question as though the user had typed.
            chip.setOnClickListener { viewModel.send(question) }
            binding.suggestionsGroup.addView(chip)
        }
    }

    /** The wording for one suggestion. */
    private fun questionFor(suggestion: Suggestion): String? = when (suggestion.kind) {
        SuggestionKind.OPEN_TASKS -> getString(R.string.assistant_chip_open_tasks)
        SuggestionKind.MONTH_SPEND -> getString(R.string.assistant_chip_month_spend)
        SuggestionKind.PET -> suggestion.petName?.let { getString(R.string.assistant_chip_pet, it) }
    }

    /** Enables the send button only when there is something to send and nothing already in flight. */
    private fun updateSendEnabled(isReplying: Boolean = viewModel.uiState.value.isReplying) {
        val hasQuestion = !binding.questionInput.text.isNullOrBlank()
        binding.sendButton.isEnabled = hasQuestion && !isReplying
    }

    /** Opens the notifications screen, from the pet-count badge — as the tabs' headers do. */
    private fun openNotifications() {
        findNavController().navigate(R.id.notificationsFragment)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Detach the adapter: a RecyclerView keeps its adapter and the adapter its holders.
        binding.messagesRecyclerView.adapter = null
        messageAdapter = null
        _binding = null
    }
}
