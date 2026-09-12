package com.prash.petcare.ui.expense

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.databinding.ItemExpenseBinding
import com.prash.petcare.databinding.ItemExpenseCategoryTotalBinding
import com.prash.petcare.databinding.ItemExpenseSectionHeaderBinding
import com.prash.petcare.databinding.ItemExpenseSummaryBinding
import com.prash.petcare.ui.common.formatCurrency
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.common.iconRes
import com.prash.petcare.ui.common.labelRes
import kotlin.math.roundToInt

/**
 * Renders the whole expenses screen: the total, the category breakdown and the expense list.
 *
 * Four different row layouts share one list, which RecyclerView supports through *view types*.
 * [getItemViewType] labels each position, [onCreateViewHolder] inflates the matching layout, and
 * the adapter keeps a separate recycling pool per type, so a summary card is never reused as an
 * expense row.
 *
 * The alternative — stacking separate views and lists inside a scroll view — would inflate every
 * expense at once instead of recycling them, and a pet's spending history only ever grows.
 *
 * Uses [ListAdapter], which diffs each new list against the previous one on a background thread and
 * animates only the rows that actually changed, rather than redrawing everything whenever the
 * database emits.
 *
 * @property onExpenseClick Called with the tapped expense. Kept as a lambda so navigation decisions
 *   stay in the fragment and this adapter stays reusable.
 */
class ExpenseAdapter(
    private val onExpenseClick: (Expense) -> Unit
) : ListAdapter<ExpenseListItem, RecyclerView.ViewHolder>(ExpenseDiffCallback) {

    /** Labels each position so RecyclerView knows which layout and holder pool to use. */
    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ExpenseListItem.Summary -> VIEW_TYPE_SUMMARY
        is ExpenseListItem.SectionHeader -> VIEW_TYPE_SECTION_HEADER
        is ExpenseListItem.CategoryTotal -> VIEW_TYPE_CATEGORY_TOTAL
        is ExpenseListItem.ExpenseRow -> VIEW_TYPE_EXPENSE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            VIEW_TYPE_SUMMARY -> SummaryViewHolder(
                ItemExpenseSummaryBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_SECTION_HEADER -> SectionHeaderViewHolder(
                ItemExpenseSectionHeaderBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_CATEGORY_TOTAL -> CategoryTotalViewHolder(
                ItemExpenseCategoryTotalBinding.inflate(inflater, parent, false)
            )

            VIEW_TYPE_EXPENSE -> ExpenseViewHolder(
                ItemExpenseBinding.inflate(inflater, parent, false),
                onExpenseClick
            )

            // Unreachable: getItemViewType only ever returns the four constants above. Throwing
            // rather than returning a default means a future fifth row type fails loudly here
            // instead of silently rendering as the wrong thing.
            else -> throw IllegalArgumentException("Unknown expense view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ExpenseListItem.Summary -> (holder as SummaryViewHolder).bind(item)
            is ExpenseListItem.SectionHeader -> (holder as SectionHeaderViewHolder).bind(item)
            is ExpenseListItem.CategoryTotal -> (holder as CategoryTotalViewHolder).bind(item)
            is ExpenseListItem.ExpenseRow -> (holder as ExpenseViewHolder).bind(item)
        }
    }

    /** The headline card: everything spent on this pet. */
    class SummaryViewHolder(
        private val binding: ItemExpenseSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExpenseListItem.Summary) {
            val context = binding.root.context

            binding.totalAmountText.text = formatCurrency(item.total)

            // A plurals resource rather than a plain string, because the wording has to change with
            // the count and which plural forms exist differs by language. Android picks the right
            // entry from the quantity, so this never has to test for "is it 1?" itself.
            binding.totalCaptionText.text = context.resources.getQuantityString(
                R.plurals.expenses_total_caption,
                item.expenseCount,
                item.expenseCount
            )
        }
    }

    /** A heading introducing a group of rows. */
    class SectionHeaderViewHolder(
        private val binding: ItemExpenseSectionHeaderBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExpenseListItem.SectionHeader) {
            binding.sectionHeaderText.setText(item.titleRes)
        }
    }

    /** One category's share of the total. */
    class CategoryTotalViewHolder(
        private val binding: ItemExpenseCategoryTotalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExpenseListItem.CategoryTotal) {
            val context = binding.root.context

            binding.categoryNameText.setText(item.category.labelRes())
            // The icon repeats what the label says, which is the point: a symbol is read far faster
            // than a word when scanning the breakdown for where the money went.
            //
            // Set as a compound drawable on the label rather than as a separate ImageView, which
            // keeps this to one view. "Relative" places it at the start, so it moves to the
            // right-hand side automatically in a right-to-left language.
            binding.categoryNameText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                item.category.iconRes(), 0, 0, 0
            )

            binding.categoryAmountText.text = formatCurrency(item.amount)

            // The share is carried as a 0-1 fraction and turned into the two things shown here: a
            // percentage caption and the bar's own scale. The bar is the part actually read at a
            // glance — comparing lengths is far quicker than comparing numbers — and the caption
            // gives the precise figure for anyone who wants it.
            val percent = (item.share * 100).roundToInt()
            binding.categoryShareText.text =
                context.getString(R.string.expenses_category_share_format, percent)
            binding.categoryShareBar.progress = percent
        }
    }

    /** A single recorded expense. */
    class ExpenseViewHolder(
        private val binding: ItemExpenseBinding,
        private val onExpenseClick: (Expense) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExpenseListItem.ExpenseRow) {
            val context = binding.root.context
            val expense = item.expense

            // Re-set on every bind: rows are recycled, so a listener left from a previous bind
            // would open whichever expense used to occupy this row.
            binding.root.setOnClickListener { onExpenseClick(expense) }

            binding.expenseTitleText.text = expense.title
            binding.expenseAmountText.text = formatCurrency(expense.amount)

            // Category and date read as one line, e.g. "Vet · 4 Mar 2026", which is the pair of
            // facts that tells the user which receipt this is without opening it.
            binding.expenseSubtitleText.text = context.getString(
                R.string.expenses_row_subtitle_format,
                context.getString(expense.category.labelRes()),
                formatStoredDate(expense.incurredOn)
            )

            val notes = expense.notes?.takeIf { it.isNotBlank() }
            binding.expenseNotesText.text = notes.orEmpty()
            // Set explicitly both ways, because this row may be recycled from an expense that had
            // notes when this one does not.
            binding.expenseNotesText.visibility = if (notes != null) View.VISIBLE else View.GONE
        }
    }

    /**
     * Tells [ListAdapter] how to compare two versions of the screen.
     *
     * Identity comes from [ExpenseListItem.itemId] rather than list position, so the summary, the
     * headers, the breakdown rows and the expenses are never confused with one another as the list
     * is rebuilt after every change.
     */
    private object ExpenseDiffCallback : DiffUtil.ItemCallback<ExpenseListItem>() {

        override fun areItemsTheSame(oldItem: ExpenseListItem, newItem: ExpenseListItem): Boolean =
            oldItem.itemId == newItem.itemId

        // Every variant is a data class, so `==` already compares each property — including an
        // amount or a category share — which is what makes a corrected receipt redraw its row and
        // the breakdown beside it.
        override fun areContentsTheSame(
            oldItem: ExpenseListItem,
            newItem: ExpenseListItem
        ): Boolean = oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_SUMMARY = 0
        private const val VIEW_TYPE_SECTION_HEADER = 1
        private const val VIEW_TYPE_CATEGORY_TOTAL = 2
        private const val VIEW_TYPE_EXPENSE = 3
    }
}
