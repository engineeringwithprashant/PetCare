package com.prash.petcare.ui.pet

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.databinding.ItemPetsHeadlineBinding

/**
 * Shows the Pets tab's headline — "Two pets,\none household" — as the first item in the list.
 *
 * Part of the **View** layer. The fragment composes the sentence (it needs string resources, which
 * a ViewModel must not touch) and hands it to [headline]; this adapter only puts it on screen.
 *
 * **Why an adapter for a single line of text.** The headline has to scroll with the cards, and the
 * cards live in a RecyclerView. The fragment joins this, [PetAdapter] and [AddPetRowAdapter] with a
 * `ConcatAdapter`, which presents several adapters to one RecyclerView as a single list. Each part
 * stays a small class with one job, and none of them has to offset its positions around the others.
 *
 * It holds zero or one items: none while there is no headline to show (still loading, or no pets,
 * when the empty state takes over), one otherwise.
 */
class PetsHeadlineAdapter : RecyclerView.Adapter<PetsHeadlineAdapter.HeadlineViewHolder>() {

    /**
     * The sentence to show, or null to show nothing.
     *
     * The setter tells RecyclerView precisely what happened — the item appeared, disappeared or
     * changed — rather than calling notifyDataSetChanged, so only this row is touched and the cards
     * below keep their animations and scroll position. Setting the same text again does nothing.
     */
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
