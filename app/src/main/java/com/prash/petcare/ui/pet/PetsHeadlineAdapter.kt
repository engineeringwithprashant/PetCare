package com.prash.petcare.ui.pet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.databinding.ItemPetsHeadlineBinding

/** Shows the Pets tab's headline — "Two pets,\none household" — as the first item in the list. */
class PetsHeadlineAdapter : RecyclerView.Adapter<PetsHeadlineAdapter.HeadlineViewHolder>() {

    /** The sentence to show, or null to show nothing. */
    var headline: CharSequence? = null
        set(value) {
            val old = field
            if (old == value) return
            field = value

            when {
                old == null -> notifyItemInserted(0)
                value == null -> notifyItemRemoved(0)
                else -> notifyItemChanged(0)
            }
        }

    override fun getItemCount(): Int = if (headline == null) 0 else 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HeadlineViewHolder =
        HeadlineViewHolder(
            ItemPetsHeadlineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        )

    override fun onBindViewHolder(holder: HeadlineViewHolder, position: Int) {
        holder.binding.headlineText.text = headline
    }

    /** Holds the headline's single TextView, resolved once by ViewBinding. */
    class HeadlineViewHolder(val binding: ItemPetsHeadlineBinding) :
        RecyclerView.ViewHolder(binding.root)
}
