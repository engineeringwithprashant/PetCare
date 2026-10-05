package com.prash.petcare.ui.assistant

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.assistant.AssistantFailure
import com.prash.petcare.databinding.ItemAssistantMessageBinding
import com.prash.petcare.databinding.ItemAssistantThinkingBinding
import com.prash.petcare.databinding.ItemAssistantUserMessageBinding

/** Draws the conversation on the assistant screen. */
class AssistantAdapter :
    ListAdapter<AssistantMessage, RecyclerView.ViewHolder>(MessageDiffCallback) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is AssistantMessage.Greeting,
        is AssistantMessage.FromAssistant,
        is AssistantMessage.Failed -> TYPE_ASSISTANT
        is AssistantMessage.FromUser -> TYPE_USER
        is AssistantMessage.Thinking -> TYPE_THINKING
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        // false: the RecyclerView attaches each row itself once it has measured it.
        return when (viewType) {
            TYPE_USER -> UserViewHolder(ItemAssistantUserMessageBinding.inflate(inflater, parent, false))
            TYPE_THINKING -> ThinkingViewHolder(ItemAssistantThinkingBinding.inflate(inflater, parent, false))
            else -> AssistantViewHolder(ItemAssistantMessageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val message = getItem(position)) {
            is AssistantMessage.Greeting -> (holder as AssistantViewHolder).bindGreeting(message)
            is AssistantMessage.FromAssistant -> (holder as AssistantViewHolder).bind(message.text)
            is AssistantMessage.Failed -> (holder as AssistantViewHolder).bindFailure(message.reason)
            is AssistantMessage.FromUser -> (holder as UserViewHolder).bind(message.text)
            // Nothing to bind: the placeholder animates by itself and says the same thing every time.
            is AssistantMessage.Thinking -> Unit
        }
    }

    /** The assistant's side: a light card on the left, whatever it is saying. */
    class AssistantViewHolder(
        private val binding: ItemAssistantMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(text: String) {
            binding.messageText.text = text
        }

        /** The opening line, named to whoever is signed in. */
        fun bindGreeting(greeting: AssistantMessage.Greeting) {
            val context = binding.root.context
            binding.messageText.text = if (greeting.firstName.isNotBlank()) {
                context.getString(R.string.assistant_greeting, greeting.firstName)
            } else {
                context.getString(R.string.assistant_greeting_no_name)
            }
        }

        /** A reply that failed, in words that say what to do about it. */
        fun bindFailure(reason: AssistantFailure) {
            binding.messageText.setText(
                when (reason) {
                    AssistantFailure.NO_NETWORK -> R.string.assistant_error_no_network
                    AssistantFailure.MISSING_KEY -> R.string.assistant_error_missing_key
                    AssistantFailure.INVALID_KEY -> R.string.assistant_error_invalid_key
                    AssistantFailure.RATE_LIMITED -> R.string.assistant_error_rate_limited
                    AssistantFailure.BAD_RESPONSE -> R.string.assistant_error_bad_response
                    AssistantFailure.UNKNOWN -> R.string.assistant_error_unknown
                }
            )
        }
    }

    /** The user's side: an accent card on the right. */
    class UserViewHolder(
        private val binding: ItemAssistantUserMessageBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(text: String) {
            binding.messageText.text = text
        }
    }

    /** The loading placeholder. It holds no state, so it has nothing to bind. */
    class ThinkingViewHolder(
        binding: ItemAssistantThinkingBinding
    ) : RecyclerView.ViewHolder(binding.root)

    /** How [ListAdapter] compares two conversations. */
    private object MessageDiffCallback : DiffUtil.ItemCallback<AssistantMessage>() {
        override fun areItemsTheSame(oldItem: AssistantMessage, newItem: AssistantMessage): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: AssistantMessage, newItem: AssistantMessage): Boolean =
            oldItem == newItem
    }

    private companion object {
        const val TYPE_ASSISTANT = 0
        const val TYPE_USER = 1
        const val TYPE_THINKING = 2
    }
}
