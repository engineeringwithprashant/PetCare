package com.prash.petcare.ui.assistant

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.core.view.isVisible
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
import com.prash.petcare.ui.common.openProfile
import com.prash.petcare.ui.common.setUp
import com.prash.petcare.ui.common.showPetCount
import com.prash.petcare.ui.session.SessionViewModel
import kotlinx.coroutines.launch

/**
 * The Assistant screen, opened from the dashboard's "Ask the assistant" button and its AI pill.
 *
 * In MVVM this is the **View**. It draws whatever [AssistantViewModel] publishes and forwards what
 * the user types — it decides nothing about the conversation. What it *does* own is everything that
 * needs a Context: resolving each message and chip into words from string resources, keeping the
 * list scrolled to the newest message, and enabling the send button.
 *
 * **Why the chips' text lives here rather than in the state.** A suggestion is published as a
 * [Suggestion] — a kind, and a pet's name where one applies — and the wording is resolved here. That
 * is the same split every other screen in this app uses, and it is what keeps the ViewModel free of
 * a Context and the words translatable.
 */
class AssistantFragment : Fragment() {

    /**
     * ViewBinding for fragment_assistant.xml.
     *
     * Nullable because a fragment's view can be destroyed while the fragment itself lives on;
     * holding the binding past that point would leak the whole view hierarchy. Cleared in
     * [onDestroyView].
     */
    private var _binding: FragmentAssistantBinding? = null
    private val binding get() = _binding!!

    /**
     * Which account is signed in, read from the Activity-wide session — this screen is reached by
     * destination id from the dashboard and has no navigation argument to carry it.
     */
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

        // The shared header, as on every redesigned screen. The AI pill is deliberately *not* shown
        // here: it is the control that opens this screen, and a button that reopens the screen it is
        // already on reads as broken.
        binding.header.setUp(
            title = R.string.nav_assistant,
            onBadgeClick = ::openNotifications,
            onMonogramClick = ::openProfile
        )

        setUpList()
        setUpInput()
        keepNewestMessageVisibleWhenListShrinks()
        observeViewModel()
    }

    /**
     * Keeps the newest message in view when the list is made shorter — which is what the keyboard
     * opening amounts to.
     *
     * The Activity makes the room: it applies the keyboard's inset as padding on the root, so the
     * whole layout re-measures and this list is handed a shorter box. See
     * MainActivity.applyWindowInsets.
     *
     * **Why a layout listener rather than an inset listener here.** Both would work, but an
     * `OnApplyWindowInsetsListener` on a fragment's root *replaces* the default dispatch and would
     * stop insets reaching anything inside it — a trap for whatever is added to this screen later.
     * Watching the height instead needs no such care, and it is also the more honest trigger: the
     * list should return to the bottom whenever it shrinks, whatever shrank it.
     *
     * `stackFromEnd` already holds the bottom through an ordinary re-measure. This covers the case
     * it does not: when the last message is taller than the space left, the anchor is kept but the
     * end of a long bubble can finish below the viewport.
     *
     * The scroll is posted so it runs after the layout pass that triggered it, rather than against
     * the geometry being replaced.
     */
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

    /**
     * Prepares the conversation list.
     *
     * `stackFromEnd` pins the list to its bottom, which is what a chat wants: with few messages they
     * sit at the bottom near the input rather than floating at the top, and with many the newest is
     * already in view. It is the layout manager's job rather than a scroll call, so it holds through
     * rotation without the fragment having to restore a position.
     */
    private fun setUpList() {
        messageAdapter = AssistantAdapter()
        binding.messagesRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
            adapter = messageAdapter
        }
    }

    /**
     * Wires the question field and the send button.
     *
     * The field's own "send" key does the same thing as the button, because a user who has just
     * typed a question expects the keyboard's action to send it rather than to insert a newline.
     */
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

        // The send button's enabled state depends on the field as well as on the ViewModel, and the
        // field changes far more often than the state does — so it is refreshed on every keystroke.
        binding.questionInput.doAfterTextChanged { updateSendEnabled() }
    }

    /** Sends whatever is typed and clears the field. Blank input is ignored by the ViewModel. */
    private fun sendTypedQuestion() {
        val question = binding.questionInput.text?.toString().orEmpty()
        if (question.isBlank()) return

        viewModel.send(question)
        binding.questionInput.text?.clear()
    }

    /**
     * Subscribes to the ViewModel.
     *
     * `repeatOnLifecycle(STARTED)` collects while the screen is visible and cancels when it stops,
     * so the fragment never touches views in the background.
     */
    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect(::render)
            }
        }
    }

    /** Applies one state to the header, the conversation, the chips and the input row. */
    private fun render(state: AssistantUiState) {
        binding.header.showPetCount(state.petNames.size)
        renderTitle(state.petNames)
        renderMessages(state.messages)
        renderSuggestions(state)
        updateSendEnabled(state.isReplying)
    }

    /**
     * "Ask about Krishala & Ddjw", built from the account's actual pets.
     *
     * The separator is a string resource rather than a literal "&": it carries the spaces around it,
     * and a language that joins names differently can change it without touching this code. An
     * account with no pets yet gets its own sentence rather than "Ask about " trailing into nothing.
     */
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

    /**
     * Submits the conversation and keeps the newest message in view.
     *
     * The scroll is deferred to `submitList`'s completion callback, which runs after the new item
     * has been laid out. Scrolling before that would target a position the list does not have yet,
     * and the answer would land just off the bottom edge.
     */
    private fun renderMessages(messages: List<AssistantMessage>) {
        // The scroll runs in submitList's completion callback, which fires after the new item has
        // been laid out — scrolling before that would target a position the list does not have yet,
        // and a message sent with the keyboard open would land just off the bottom edge.
        messageAdapter?.submitList(messages) { scrollToNewestMessage() }
    }

    /**
     * Rebuilds the suggested-question row, or hides it once the conversation has started.
     *
     * The chips are recreated rather than updated in place: there are at most three, they change
     * only when the pet list does, and rebuilding is what keeps this from having to reconcile an
     * existing row against a new list of suggestions.
     */
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
            // Tapping a chip sends its question as though the user had typed it, which is what makes
            // the row a shortcut rather than a separate mechanism.
            chip.setOnClickListener { viewModel.send(question) }
            binding.suggestionsGroup.addView(chip)
        }
    }

    /**
     * The wording for one suggestion.
     *
     * Null for a per-pet chip with no pet to name, which cannot normally happen — the ViewModel only
     * offers that suggestion when there is a pet — but returning null rather than asking about a
     * blank keeps the two independent of each other.
     */
    private fun questionFor(suggestion: Suggestion): String? = when (suggestion.kind) {
        SuggestionKind.OPEN_TASKS -> getString(R.string.assistant_chip_open_tasks)
        SuggestionKind.MONTH_SPEND -> getString(R.string.assistant_chip_month_spend)
        SuggestionKind.PET -> suggestion.petName?.let { getString(R.string.assistant_chip_pet, it) }
    }

    /**
     * Enables the send button only when there is something to send and nothing already in flight.
     *
     * Called both from the state and from every keystroke, so the reply-in-flight flag is read from
     * the latest state rather than passed in from the text watcher, which does not know it.
     */
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
        // Detach the adapter: a RecyclerView keeps its adapter and the adapter its holders, which
        // would keep the destroyed hierarchy alive behind them.
        binding.messagesRecyclerView.adapter = null
        messageAdapter = null
        _binding = null
    }
}
