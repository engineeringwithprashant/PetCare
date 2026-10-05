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

/** Draws the place-search suggestions under the field above the map — one row per [PlaceSuggestion]. */
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

            // Hidden rather than blanked.
            binding.resultAddressText.isVisible = !suggestion.addressLine.isNullOrBlank()
            binding.resultAddressText.text = suggestion.addressLine.orEmpty()

            // One spoken phrase for the row — "Patan Hospital, Kumaripati, Lalitpur".
            binding.root.contentDescription = listOfNotNull(
                suggestion.name,
                suggestion.addressLine?.takeIf { it.isNotBlank() }
            ).joinToString(binding.root.context.getString(R.string.list_separator))

            binding.root.setOnClickListener { onResultClick(suggestion) }
        }
    }

    /** Rows are the same row when they are the same MapTiler feature. */
    private object SuggestionDiffCallback : DiffUtil.ItemCallback<PlaceSuggestion>() {
        override fun areItemsTheSame(oldItem: PlaceSuggestion, newItem: PlaceSuggestion): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: PlaceSuggestion, newItem: PlaceSuggestion): Boolean =
            oldItem == newItem
    }
}
