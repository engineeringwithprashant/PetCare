package com.prash.petcare.ui.expense

import android.content.res.ColorStateList
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.databinding.ItemExpenseBinding
import com.prash.petcare.databinding.ItemExpenseCategoryTotalBinding
import com.prash.petcare.databinding.ItemExpenseSectionHeaderBinding
import com.prash.petcare.databinding.ItemExpenseSummaryBinding
import com.prash.petcare.ui.common.abbreviationRes
import com.prash.petcare.ui.common.colorRes
import com.prash.petcare.ui.common.formatCurrency
import com.prash.petcare.ui.common.formatStoredDate
import com.prash.petcare.ui.common.labelRes
import com.prash.petcare.ui.common.splitCurrency
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Draws the whole Money tab below the chips: the dark total card, the category legend, the "ALL
 * EXPENSES" heading and the expense cards.
 *
 * Part of the **View** layer: it binds [ExpenseListItem]s the ViewModel has already built and reports
 * taps, and decides nothing. Four row layouts share one list through RecyclerView *view types* —
 * [getItemViewType] labels each position, and the adapter keeps a separate recycling pool per type, so
 * the total card is never reused as an expense card.
 *
 * A [ListAdapter], which diffs each new list against the previous one off the main thread and redraws
 * only the rows that changed, rather than every row whenever the database emits.
 *
 * @property onExpenseClick Called with a tapped expense. A lambda so navigation stays in the fragment.
 */
class ExpenseAdapter(
    private val onExpenseClick: (Expense) -> Unit
) : ListAdapter<ExpenseListItem, RecyclerView.ViewHolder>(ExpenseDiffCallback) {

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is ExpenseListItem.Summary -> VIEW_TYPE_SUMMARY
        is ExpenseListItem.CategoryTotal -> VIEW_TYPE_CATEGORY_TOTAL
        is ExpenseListItem.AllExpensesHeader -> VIEW_TYPE_SECTION_HEADER
        is ExpenseListItem.ExpenseRow -> VIEW_TYPE_EXPENSE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            VIEW_TYPE_SUMMARY -> SummaryViewHolder(ItemExpenseSummaryBinding.inflate(inflater, parent, false))
            VIEW_TYPE_CATEGORY_TOTAL -> CategoryTotalViewHolder(
                ItemExpenseCategoryTotalBinding.inflate(inflater, parent, false)
            )
            VIEW_TYPE_SECTION_HEADER -> SectionHeaderViewHolder(
                ItemExpenseSectionHeaderBinding.inflate(inflater, parent, false)
            )
            VIEW_TYPE_EXPENSE -> ExpenseViewHolder(ItemExpenseBinding.inflate(inflater, parent, false), onExpenseClick)
            // Unreachable: getItemViewType only returns the constants above. Throwing means a future
            // row type fails loudly here instead of silently drawing as the wrong thing.
            else -> throw IllegalArgumentException("Unknown expense view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is ExpenseListItem.Summary -> (holder as SummaryViewHolder).bind(item)
            is ExpenseListItem.CategoryTotal -> (holder as CategoryTotalViewHolder).bind(item)
            is ExpenseListItem.AllExpensesHeader -> Unit // Static text, set in the layout.
            is ExpenseListItem.ExpenseRow -> (holder as ExpenseViewHolder).bind(item)
        }
    }

    /** The dark total card: "TOTAL SPENT · SEPTEMBER", the figure, the bar and the caption. */
    class SummaryViewHolder(
        private val binding: ItemExpenseSummaryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExpenseListItem.Summary) {
            val context = binding.root.context
            val month = item.month

            binding.totalLabelText.text = context.getString(
                R.string.expenses_total_label_format,
                monthName(month.monthStart)
            )

            // "Rs 5,240" at full size and ".00" at half, in one TextView. A RelativeSizeSpan scales just
            // the characters it covers, so the two parts share a baseline and line up exactly as in
            // the design — two TextViews side by side would need baseline constraints and would still
            // drift at large font scales.
            val (whole, fraction) = splitCurrency(month.total)
            binding.totalAmountText.text = SpannableStringBuilder(whole).apply {
                val start = length
                append(fraction)
                setSpan(RelativeSizeSpan(FRACTION_SCALE), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // Spoken as a plain amount; the span changes nothing a screen reader would say.
            binding.totalAmountText.contentDescription = whole + fraction

            binding.categoryBar.setSegments(
                month.breakdown.map {
                    CategoryBarView.Segment(it.share, ContextCompat.getColor(context, it.category.colorRes()))
                }
            )

            binding.totalCaptionText.text = caption(month.expenseCount, month.petCount, month.changePercent, month.previousMonthStart)
        }

        /**
         * "3 expenses · 2 pets · +12% vs August". The comparison is left off when last month had no
         * spending, since there is no percentage to give — see MonthlySpending.changePercent.
         */
        private fun caption(expenses: Int, pets: Int, change: Int?, previousMonthStart: Long): String {
            val resources = binding.root.resources
            val parts = mutableListOf(
                resources.getQuantityString(R.plurals.expenses_caption_expenses, expenses, expenses),
                resources.getQuantityString(R.plurals.expenses_caption_pets, pets, pets)
            )

            if (change != null) {
                val template = when {
                    change > 0 -> R.string.expenses_caption_change_up
                    change < 0 -> R.string.expenses_caption_change_down
                    else -> R.string.expenses_caption_change_flat
                }
                parts += resources.getString(template, abs(change), monthName(previousMonthStart))
            }

            return parts.joinToString(resources.getString(R.string.expenses_caption_separator))
        }

        /**
         * The month's full name, "September", in the device's language.
         *
         * "LLLL" is the *standalone* month form, which matters in languages that inflect month names:
         * Russian writes "сентябрь" on its own but "сентября" inside a date, and "MMMM" would give
         * the second. Formatted in UTC because the month start is stored as midnight UTC — see
         * MonthlySpending — and a zone west of Greenwich would otherwise print the previous month.
         */
        private fun monthName(monthStartUtc: Long): String =
            SimpleDateFormat("LLLL", Locale.getDefault())
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(monthStartUtc))
    }

    /** One legend row: a dot in the category's colour, its name, its share and its amount. */
    class CategoryTotalViewHolder(
        private val binding: ItemExpenseCategoryTotalBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExpenseListItem.CategoryTotal) {
            val context = binding.root.context
            val spend = item.spend

            // The dot is a shape tinted per row, so one drawable serves every category and the dot
            // always matches its bar segment exactly — both read the same colorRes.
            binding.categoryDot.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(context, spend.category.colorRes()))
            binding.categoryNameText.setText(spend.category.labelRes())
            binding.categoryShareText.text =
                context.getString(R.string.expenses_category_share_format, (spend.share * 100).roundToInt())
            binding.categoryAmountText.text = formatCurrency(spend.amount)
        }
    }

    /** "ALL EXPENSES" — nothing to bind; the view holder exists so it can be recycled like any row. */
    class SectionHeaderViewHolder(binding: ItemExpenseSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    /** One expense card: category badge, title, "category · date", amount. */
    class ExpenseViewHolder(
        private val binding: ItemExpenseBinding,
        private val onExpenseClick: (Expense) -> Unit
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: ExpenseListItem.ExpenseRow) {
            val context = binding.root.context
            val expense = item.expense
            val categoryName = context.getString(expense.category.labelRes())

            // Re-set on every bind: rows are recycled, and a listener left from a previous bind would
            // open whichever expense used to occupy this card.
            binding.root.setOnClickListener { onExpenseClick(expense) }

            binding.expenseBadgeText.setText(expense.category.abbreviationRes())
            binding.expenseTitleText.text = expense.title
            // "Medication · Sep 12, 2026". formatStoredDate reads the stored date in UTC and in the
            // locale's own medium style, so it is the day the user picked, written their way.
            binding.expenseSubtitleText.text = context.getString(
                R.string.expenses_row_subtitle_format,
                categoryName,
                formatStoredDate(expense.incurredOn)
            )
            binding.expenseAmountText.text = formatCurrency(expense.amount)

            // Notes are not shown on the card — the design gives each expense exactly two lines —
            // but they are still spoken, so a TalkBack user loses nothing the sighted user could open
            // the expense to read.
            val notes = expense.notes?.takeIf { it.isNotBlank() }
            binding.root.contentDescription = listOfNotNull(
                expense.title,
                formatCurrency(expense.amount),
                binding.expenseSubtitleText.text,
                notes
            ).joinToString(context.getString(R.string.list_separator))
        }
    }

    /**
     * Compares rows by [ExpenseListItem.itemId] for identity and by data-class equality for content,
     * so a corrected receipt redraws its own card, the legend and the total — and nothing else.
     */
    private object ExpenseDiffCallback : DiffUtil.ItemCallback<ExpenseListItem>() {
        override fun areItemsTheSame(oldItem: ExpenseListItem, newItem: ExpenseListItem): Boolean =
            oldItem.itemId == newItem.itemId

        override fun areContentsTheSame(oldItem: ExpenseListItem, newItem: ExpenseListItem): Boolean =
            oldItem == newItem
    }

    companion object {
        private const val VIEW_TYPE_SUMMARY = 0
        private const val VIEW_TYPE_CATEGORY_TOTAL = 1
        private const val VIEW_TYPE_SECTION_HEADER = 2
        private const val VIEW_TYPE_EXPENSE = 3

        /** The paise in the total are drawn at half the size of the rupees, as in the design. */
        private const val FRACTION_SCALE = 0.5f
    }
}
