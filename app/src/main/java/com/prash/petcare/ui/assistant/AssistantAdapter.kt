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

/**
 * Draws the conversation on the assistant screen.
 *
 * Part of the **View** layer: it turns each [AssistantMessage] into a bubble and decides nothing.
 * Which messages exist, what they say and when the reply arrives are all
 * [AssistantViewModel]'s.
 *
 * **Four view types, because there are four genuinely different bubbles** — the assistant's card on
 * the left, the user's accent card on the right, the loading placeholder, and the failure notice.
 * The greeting and a normal answer share a type: they are the same object on screen, and only their
 * text is built differently. Returning the right type from [getItemViewType] is what lets a
 * RecyclerView recycle a left-hand bubble only into another left-hand bubble.
 *
 * Extends [ListAdapter], so appending a message diffs against the previous list on a background
 * thread and animates only what changed. That matters here for one specific reason: a reply
 * *replaces* its loading placeholder under the same id, so DiffUtil sees one item change rather than
 * one removed and one inserted, and the list does not jump under the user's eyes as the answer
 * lands.
 *
 * Every sentence the app itself speaks — the greeting, the failure notice — is resolved from string
 * resources here rather than carried in the state, which is why the ViewModel needs no Context.
 */
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

        /**
         * The opening line, named to whoever is signed in.
         *
         * Falls back to the unnamed wording when the account has no name recorded rather than
         * greeting "Hi ." — the two are separate strings so each reads naturally in translation
         * instead of one being patched with an empty placeholder.
         */
        fun bindGreeting(greeting: AssistantMessage.Greeting) {
            val context = binding.root.context
            binding.messageText.text = if (greeting.firstName.isNotBlank()) {
                context.getString(R.string.assistant_greeting, greeting.firstName)
            } else {
                context.getString(R.string.assistant_greeting_no_name)
            }
        }

        /**
         * A reply that failed, in words that say what to do about it.
         *
         * One sentence per reason rather than a single apology, because the reasons need different
         * things from the user: turning the network back on, adding a key, waiting a moment, or just
         * trying again. A generic "something went wrong" would leave a missing key looking like a
         * glitch — the case most likely to happen and the one with the clearest fix.
         */
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

    /**
     * How [ListAdapter] compares two conversations.
     *
     * Identity is the message id, which is what makes replacing a placeholder with its answer read
     * as a change to one row. Contents compare the whole object, and every [AssistantMessage] is a
     * data class, so `==` already means "would draw identically".
     */
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
