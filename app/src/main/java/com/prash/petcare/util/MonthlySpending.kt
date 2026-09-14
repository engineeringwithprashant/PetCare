package com.prash.petcare.util

import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * One category's slice of a month's spending.
 *
 * @property amount Everything spent in this category during the month.
 * @property share This category's fraction of the month's total, from 0 to 1.
 */
data class CategorySpend(
    val category: ExpenseCategory,
    val amount: Double,
    val share: Float
)

/**
 * The figures on the Money tab's dark total card, for one calendar month.
 *
 * @property monthStart Midnight UTC on the 1st of the month, as stored dates are — see [MonthlySpending].
 *   Carried so the screen can print the month's name without working the month out a second time.
 * @property previousMonthStart The same for the month before, for "vs August".
 * @property total Everything spent this month.
 * @property expenseCount How many expenses make up [total].
 * @property petCount How many different pets those expenses were for.
 * @property changePercent The change against last month's total, rounded to a whole percent — or null
 *   when last month's total was zero, since a change from nothing has no meaningful percentage.
 * @property breakdown Every category spent on this month, largest first.
 */
data class MonthSummary(
    val monthStart: Long,
    val previousMonthStart: Long,
    val total: Double,
    val expenseCount: Int,
    val petCount: Int,
    val changePercent: Int?,
    val breakdown: List<CategorySpend>
)

/**
 * Works out the Money tab's monthly figures from a list of expenses.
 *
 * Pure Kotlin with no Android types, so the month arithmetic can be unit-tested on a plain JVM (see
 * MonthlySpendingTest). That matters here because the easy mistakes are quiet ones: an expense
 * logged on the 1st landing in the previous month, or a percentage change dividing by zero, both
 * look like perfectly reasonable numbers on screen.
 *
 * **Why the month boundaries are in UTC.** An expense's `incurredOn` is the date the user picked in
 * Material's date picker, which is stored as *midnight UTC* on that day — see
 * `ui.common.formatStoredDate`. A boundary in local time would put the 1st of the month on the wrong
 * side of it for anyone west of Greenwich (their local midnight on the 1st is *after* UTC midnight),
 * and move an expense into the previous month. Comparing UTC midnights with UTC midnights has no
 * such edge.
 *
 * *Which* month it is, on the other hand, is decided in the device's own time zone: at 23:30 on
 * 30 September in Kathmandu it is still September for the user, whatever the date is in London.
 */
object MonthlySpending {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /**
     * Summarises the calendar month containing [nowMillis].
     *
     * The breakdown's shares are divided by the month's own total, so they always add up to 1 (give
     * or take rounding) and the bar's segments exactly fill it.
     *
     * @param expenses Every expense in scope — any number of months; only the current and previous
     *   months are read.
     * @param nowMillis The current moment. A parameter rather than read from the clock, so tests can
     *   pin the date.
     * @param localTimeZone The device's time zone, which decides what month "now" is.
     */
    fun summarise(
        expenses: List<Expense>,
        nowMillis: Long,
        localTimeZone: TimeZone = TimeZone.getDefault()
    ): MonthSummary {
        val monthStart = monthStartUtc(nowMillis, localTimeZone, monthOffset = 0)
        val nextMonthStart = monthStartUtc(nowMillis, localTimeZone, monthOffset = 1)
        val previousMonthStart = monthStartUtc(nowMillis, localTimeZone, monthOffset = -1)

        val thisMonth = expenses.filter { it.incurredOn >= monthStart && it.incurredOn < nextMonthStart }
        val lastMonth = expenses.filter { it.incurredOn >= previousMonthStart && it.incurredOn < monthStart }

        val total = thisMonth.sumOf { it.amount }
        val previousTotal = lastMonth.sumOf { it.amount }

        return MonthSummary(
            monthStart = monthStart,
            previousMonthStart = previousMonthStart,
            total = total,
            expenseCount = thisMonth.size,
            petCount = thisMonth.map { it.petId }.distinct().size,
            changePercent = changePercent(total, previousTotal),
            breakdown = breakdown(thisMonth, total)
        )
    }

    /**
     * The percentage change from [previous] to [current], rounded: +12 for a rise of 12%.
     *
     * Null when [previous] is zero. "+∞%" is not a number anyone can use, and "+100%" would be a lie
     * — the honest statement is that there is nothing to compare against, so the screen leaves the
     * comparison out.
     */
    fun changePercent(current: Double, previous: Double): Int? =
        if (previous > 0.0) ((current - previous) / previous * 100).roundToInt() else null

    /**
     * Spending per category, largest first.
     *
     * Only categories actually used appear: nine rows with seven zeroes would bury the two that
     * matter. Ties are broken by the enum's order so the legend does not reshuffle between redraws.
     * A month whose expenses are all 0.00 gets shares of zero rather than dividing by zero.
     */
    private fun breakdown(expenses: List<Expense>, total: Double): List<CategorySpend> =
        expenses
            .groupBy { it.category }
            .map { (category, inCategory) ->
                val amount = inCategory.sumOf { it.amount }
                CategorySpend(
                    category = category,
                    amount = amount,
                    share = if (total > 0.0) (amount / total).toFloat() else 0f
                )
            }
            .sortedWith(compareByDescending<CategorySpend> { it.amount }.thenBy { it.category.ordinal })

    /**
     * Midnight UTC on the 1st of the month [monthOffset] months away from the local month containing
     * [nowMillis] — the same representation stored dates use.
     */
    private fun monthStartUtc(nowMillis: Long, localTimeZone: TimeZone, monthOffset: Int): Long {
        // Step 1: what year and month is it where the user is?
        val local = Calendar.getInstance(localTimeZone).apply { timeInMillis = nowMillis }
        val year = local.get(Calendar.YEAR)
        val month = local.get(Calendar.MONTH)

        // Step 2: that month's 1st, at midnight UTC. Calendar rolls month 12 into January of the next
        // year and month -1 into December of the last, so the offset needs no special cases.
        return Calendar.getInstance(UTC).apply {
            clear()
            set(year, month + monthOffset, 1)
        }.timeInMillis
    }
}
