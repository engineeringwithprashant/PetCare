package com.prash.petcare.ui.place

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.location.PlaceSuggestion
import com.prash.petcare.databinding.ItemPlaceSearchResultBinding

/**
 * Draws the place-search suggestions under the field above the map — one row per [PlaceSuggestion].
 *
 * Part of the **View** layer, and the thinnest part of it: the rows arrive already found, named,
 * described and ordered by [PlaceLocationPicker] and the repository behind it, so this binds text and
 * reports taps. It filters nothing and sorts nothing — a search adapter that did either would be a
 * second opinion about what the user asked for.
 *
 * A [ListAdapter] for a reason that matters more here than on the saved-places list: this list is
 * replaced wholesale every time the debounced search returns, which for a user typing a word is every
 * few hundred milliseconds. `notifyDataSetChanged` would rebuild and flash all five rows each time,
 * including the ones that had not moved — and a row flashing under a finger on its way down is a row
 * that gets mis-tapped. DiffUtil redraws only what actually changed.
 *
 * @param onResultClick A row was tapped. The fragment hands it to the picker, which selects the place
 *   and flies the map to it; nothing is decided here.
 */
class PlaceSearchAdapter(
    private val onResultClick: (PlaceSuggestion) -> Unit
) : ListAdapter<PlaceSuggestion, PlaceSearchAdapter.SuggestionViewHolder>(SuggestionDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemPlaceSearchResultBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SuggestionViewHolder(
        private val binding: ItemPlaceSearchResultBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(suggestion: PlaceSuggestion) {
            binding.resultNameText.text = suggestion.name

            // Hidden rather than blanked, so a result with no context is a one-line row instead of a
            // two-line row with an empty second line.
            binding.resultAddressText.isVisible = !suggestion.addressLine.isNullOrBlank()
            binding.resultAddressText.text = suggestion.addressLine.orEmpty()

            // One spoken phrase for the row — "Patan Hospital, Kumaripati, Lalitpur" — in place of the
            // name and the address announced as two unrelated fragments.
            binding.root.contentDescription = listOfNotNull(
                suggestion.name,
                suggestion.addressLine?.takeIf { it.isNotBlank() }
            ).joinToString(binding.root.context.getString(R.string.list_separator))

            binding.root.setOnClickListener { onResultClick(suggestion) }
        }
    }

    /**
     * Rows are the same row when they are the same MapTiler feature, which is what [PlaceSuggestion.id]
     * is carried for; their contents have changed when anything drawn has, which the data class's
     * generated `equals` covers.
     */
    private object SuggestionDiffCallback : DiffUtil.ItemCallback<PlaceSuggestion>() {
        override fun areItemsTheSame(oldItem: PlaceSuggestion, newItem: PlaceSuggestion): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PlaceSuggestion, newItem: PlaceSuggestion): Boolean =
            oldItem == newItem
    }
}
