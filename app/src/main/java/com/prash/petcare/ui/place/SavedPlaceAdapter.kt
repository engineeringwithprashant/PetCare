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
import com.prash.petcare.ui.common.emojiRes
import com.prash.petcare.ui.common.formatDistanceKm
import com.prash.petcare.ui.common.placeSubtitle

/** Draws the "SAVED PLACES" list on the Places tab: one row per place, with a category badge, the name. */
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

            // The category's emoji rather than "Vet" / "Grm" / "Shop".
            binding.placeBadge.setText(place.category.emojiRes())
            binding.placeNameText.text = place.name
            binding.placeSubtitleText.text = placeSubtitle(context, place)

            // A distance is only shown when one could be measured; "—" would read as a value.
            binding.placeDistanceText.isVisible = row.distanceKm != null
            binding.placeDistanceText.text = row.distanceKm?.let { formatDistanceKm(context, it) }

            // The featured place is marked the same way here as on the distance axis: its badge takes the accent.
            val rowColor = MaterialColors.getColor(
                binding.root,
                if (row.isFeatured) MaterialR.attr.colorPrimaryContainer
                else MaterialR.attr.colorSurface
            )
            binding.root.setCardBackgroundColor(rowColor)

            // Only the tile behind the emoji is coloured. A text colour is not set any more.
            binding.placeBadge.backgroundTintList = if (row.isFeatured) {
                ContextCompat.getColorStateList(context, R.color.accent_yellow)
            } else {
                ColorStateList.valueOf(
                    MaterialColors.getColor(binding.root, MaterialR.attr.colorSurfaceVariant)
                )
            }

            // One spoken sentence for the row, in place of "Vet", the name.
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

    /** Rows are the same row when they are the same place; their contents have changed when anything drawn has. */
    private object PlaceRowDiffCallback : DiffUtil.ItemCallback<PlaceRow>() {
        override fun areItemsTheSame(oldItem: PlaceRow, newItem: PlaceRow): Boolean =
            oldItem.place.id == newItem.place.id

        override fun areContentsTheSame(oldItem: PlaceRow, newItem: PlaceRow): Boolean =
            oldItem == newItem
    }
}
