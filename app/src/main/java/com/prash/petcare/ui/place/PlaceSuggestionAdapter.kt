package com.prash.petcare.ui.place

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R
import com.prash.petcare.data.places.PlaceSuggestion
import com.prash.petcare.databinding.ItemPlaceSuggestionBinding
import com.prash.petcare.ui.common.badgeRes
import com.prash.petcare.ui.common.formatDistanceKm

/**
 * One search result, together with whether it is the one the user has chosen.
 *
 * The chosen flag is kept beside the suggestion rather than as a field on it for the same reason
 * PlaceRow keeps `isFeatured` beside a Place: "which one is selected" is a fact about the screen, not
 * about the place, and folding it into the data type would mean the repository had to know about it.
 * It also gives DiffUtil something to compare — the generated `equals` covers both, so a change of
 * selection redraws exactly the two rows that changed.
 */
data class PlaceSuggestionRow(val suggestion: PlaceSuggestion, val isSelected: Boolean)

/**
 * Draws the list of search results on the Add a place screen.
 *
 * Part of the **View** layer: it binds rows the ViewModel has already filtered and reports taps. It
 * decides nothing — not what matches, not what "selected" means, not what happens when a row is
 * pressed.
 *
 * A [ListAdapter] so that typing another letter re-diffs the list and animates only the rows that
 * actually changed, rather than rebuilding the whole column on every keystroke — which at this
 * screen's refresh rate would flicker under the user's finger.
 *
 * @param onSuggestionClick A result was tapped. The fragment forwards it; the ViewModel fills the
 *   name field, selects the category and stores the result's coordinates.
 */
class PlaceSuggestionAdapter(
    private val onSuggestionClick: (PlaceSuggestion) -> Unit
) : ListAdapter<PlaceSuggestionRow, PlaceSuggestionAdapter.SuggestionViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SuggestionViewHolder {
        val binding = ItemPlaceSuggestionBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return SuggestionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SuggestionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class SuggestionViewHolder(
        private val binding: ItemPlaceSuggestionBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: PlaceSuggestionRow) {
            val context = binding.root.context
            val suggestion = row.suggestion

            binding.suggestionGlyph.setText(suggestion.category.badgeRes())
            binding.suggestionNameText.text = suggestion.name
            binding.suggestionAddressText.text = suggestion.address
            binding.suggestionDistanceText.text = formatDistanceKm(context, suggestion.distanceKm)

            applySelection(row.isSelected)

            // One spoken sentence for the row, in place of the category, name, address and distance
            // announced as four disconnected fragments. TalkBack adds "selected" from isSelected.
            binding.root.contentDescription = context.getString(
                R.string.cd_add_place_result,
                suggestion.name,
                suggestion.address,
                formatDistanceKm(context, suggestion.distanceKm)
            )
            binding.root.isSelected = row.isSelected

            binding.root.setOnClickListener { onSuggestionClick(suggestion) }
        }

        /**
         * The chosen card is marked three ways at once: an accent border, a tinted fill and an
         * accent-filled glyph.
         *
         * Three rather than one because each covers a different reader. The border is the strongest
         * signal at a glance; the fill survives being looked at out of the corner of the eye; and the
         * glyph, which is the brightest thing in the row, is what the eye lands on first when
         * scanning the column. None of them is colour alone doing the work on its own.
         *
         * The unselected values are resolved from theme attributes so they follow dark mode; the
         * accent is fixed, for the reason given in colors.xml.
         */
        private fun applySelection(isSelected: Boolean) {
            val context = binding.root.context

            binding.root.setCardBackgroundColor(
                MaterialColors.getColor(
                    binding.root,
                    if (isSelected) R.attr.colorPlaceResultSelected else MaterialR.attr.colorSurface
                )
            )
            binding.root.strokeColor = if (isSelected) {
                ContextCompat.getColor(context, R.color.accent_yellow)
            } else {
                MaterialColors.getColor(binding.root, MaterialR.attr.colorOutlineVariant)
            }

            if (isSelected) {
                binding.suggestionGlyph.backgroundTintList =
                    ContextCompat.getColorStateList(context, R.color.accent_yellow)
                binding.suggestionGlyph.setTextColor(
                    ContextCompat.getColor(context, R.color.on_accent_yellow)
                )
            } else {
                binding.suggestionGlyph.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(binding.root, MaterialR.attr.colorSurfaceVariant)
                )
                binding.suggestionGlyph.setTextColor(
                    MaterialColors.getColor(binding.root, MaterialR.attr.colorOnSurfaceVariant)
                )
            }
        }
    }

    /** Rows are the same result when they share an id; their contents change when the flag does. */
    private object DiffCallback : DiffUtil.ItemCallback<PlaceSuggestionRow>() {
        override fun areItemsTheSame(oldItem: PlaceSuggestionRow, newItem: PlaceSuggestionRow): Boolean =
            oldItem.suggestion.id == newItem.suggestion.id

        override fun areContentsTheSame(oldItem: PlaceSuggestionRow, newItem: PlaceSuggestionRow): Boolean =
            oldItem == newItem
    }
}
