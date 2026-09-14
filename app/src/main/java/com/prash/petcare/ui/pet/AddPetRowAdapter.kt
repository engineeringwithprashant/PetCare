package com.prash.petcare.ui.pet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.databinding.ItemAddPetRowBinding

/**
 * Shows the "+ ADD A PET" row as the last item in the Pets tab's list.
 *
 * Part of the **View** layer: it draws one button and reports taps through [onAddPetClick], leaving
 * the navigation itself to the fragment. It is the last of the three adapters the fragment joins
 * with a ConcatAdapter — see [PetsHeadlineAdapter] for why the list is assembled that way.
 *
 * Like the headline it holds zero or one items, controlled by [isShown]: the row is kept out of the
 * list while the first query is loading, so it does not appear alone on a blank screen for a moment
 * before the cards arrive above it.
 *
 * @property onAddPetClick Called when the row is tapped.
 */
class AddPetRowAdapter(
    private val onAddPetClick: () -> Unit
) : RecyclerView.Adapter<AddPetRowAdapter.AddPetRowViewHolder>() {

    /** Whether the row is in the list. Notifies RecyclerView of the exact change, as the headline does. */
    var isShown: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) notifyItemInserted(0) else notifyItemRemoved(0)
        }

    override fun getItemCount(): Int = if (isShown) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AddPetRowViewHolder {
        val binding =
            ItemAddPetRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)

        // Attached once here rather than in onBindViewHolder: the row never shows different data, so
        // there is nothing a recycled holder could carry over from a previous bind.
        binding.addPetButton.setOnClickListener { onAddPetClick() }

        return AddPetRowViewHolder(binding)
    }

    // Nothing to bind — the row's text and icon are fixed in its layout.
    override fun onBindViewHolder(holder: AddPetRowViewHolder, position: Int) = Unit

    /** Holds the row's button. */
    class AddPetRowViewHolder(binding: ItemAddPetRowBinding) : RecyclerView.ViewHolder(binding.root)
}
