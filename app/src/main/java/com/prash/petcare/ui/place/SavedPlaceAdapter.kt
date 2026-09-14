package com.prash.petcare.ui.place

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.R as MaterialR
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R
import com.prash.petcare.databinding.ItemPlaceRowBinding
import com.prash.petcare.ui.common.badgeRes
import com.prash.petcare.ui.common.formatDistanceKm
import com.prash.petcare.ui.common.placeSubtitle

/**
 * Draws the "SAVED PLACES" list on the Places tab: one row per place, with a category badge, the
 * name, what and where it is, and how far away.
 *
 * Part of the **View** layer. It binds [PlaceRow]s the ViewModel has already filtered, sorted and
 * measured, and reports taps; it decides nothing.
 *
 * A [ListAdapter] so that when state changes — a new featured place, a distance updated by a fresh
 * location fix — DiffUtil works out which rows actually changed and redraws only those, instead of
 * `notifyDataSetChanged` rebinding and flashing the whole list.
 *
 * @param onPlaceClick A row was tapped — the fragment features that place.
 * @param onPlaceLongClick A row was long-pressed — the fragment opens it for editing. A long press
 *   rather than a visible button because editing a saved place is rare, and a pencil on every row
 *   would clutter a list the user mostly reads.
 */
class SavedPlaceAdapter(
    private val onPlaceClick: (placeId: Long) -> Unit,
    private val onPlaceLongClick: (placeId: Long) -> Unit
) : ListAdapter<PlaceRow, SavedPlaceAdapter.PlaceViewHolder>(PlaceRowDiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PlaceViewHolder {
        val binding = ItemPlaceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PlaceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PlaceViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PlaceViewHolder(
        private val binding: ItemPlaceRowBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: PlaceRow) {
            val context = binding.root.context
            val place = row.place

            binding.placeBadge.setText(place.category.badgeRes())
            binding.placeNameText.text = place.name
            binding.placeSubtitleText.text = placeSubtitle(context, place)

            // A distance is only shown when one could be measured; "—" would read as a value.
            binding.placeDistanceText.isVisible = row.distanceKm != null
            binding.placeDistanceText.text = row.distanceKm?.let { formatDistanceKm(context, it) }

            // The featured place is marked the same way here as on the distance axis: its badge takes
            // the accent, and its row a pale accent wash. Colours are resolved from theme attributes so
            // the wash follows dark mode; the badge's accent is fixed, see colors.xml.
            val rowColor = MaterialColors.getColor(
                binding.root,
                if (row.isFeatured) MaterialR.attr.colorPrimaryContainer
                else MaterialR.attr.colorSurface
            )
            binding.root.setCardBackgroundColor(rowColor)

            if (row.isFeatured) {
                binding.placeBadge.backgroundTintList =
                    ContextCompat.getColorStateList(context, R.color.accent_yellow)
                binding.placeBadge.setTextColor(ContextCompat.getColor(context, R.color.on_accent_yellow))
            } else {
                binding.placeBadge.backgroundTintList = ColorStateList.valueOf(
                    MaterialColors.getColor(binding.root, MaterialR.attr.colorSurfaceVariant)
                )
                binding.placeBadge.setTextColor(
                    MaterialColors.getColor(binding.root, MaterialR.attr.colorOnSurfaceVariant)
                )
            }

            // One spoken sentence for the row, in place of "Vet", the name, the subtitle and the
            // distance read as four separate fragments. TalkBack adds "selected" from isSelected.
            binding.root.contentDescription = listOfNotNull(
                place.name,
                placeSubtitle(context, place),
                row.distanceKm?.let { formatDistanceKm(context, it) }
            ).joinToString(context.getString(R.string.list_separator))
            binding.root.isSelected = row.isFeatured

            binding.root.setOnClickListener { onPlaceClick(place.id) }
            binding.root.setOnLongClickListener {
                onPlaceLongClick(place.id)
                true
            }
        }
    }

    /**
     * Rows are the same row when they are the same place; their contents have changed when anything
     * drawn has — which the data class's generated `equals` covers, including the featured flag and
     * the distance.
     */
    private object PlaceRowDiffCallback : DiffUtil.ItemCallback<PlaceRow>() {
        override fun areItemsTheSame(oldItem: PlaceRow, newItem: PlaceRow): Boolean =
            oldItem.place.id == newItem.place.id

        override fun areContentsTheSame(oldItem: PlaceRow, newItem: PlaceRow): Boolean =
            oldItem == newItem
    }
}
