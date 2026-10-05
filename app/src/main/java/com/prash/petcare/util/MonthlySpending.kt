package com.prash.petcare.util

import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.ExpenseCategory
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/** One category's slice of a month's spending. */
data class CategorySpend(
    val category: ExpenseCategory,
    val amount: Double,
    val share: Float
)

/** The figures on the Money tab's dark total card, for one calendar month. */
data class MonthSummary(
    val monthStart: Long,
    val previousMonthStart: Long,
    val total: Double,
    val expenseCount: Int,
    val petCount: Int,
    val changePercent: Int?,
    val breakdown: List<CategorySpend>
)

/** Works out the Money tab's monthly figures from a list of expenses. */
object MonthlySpending {

    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** Summarises the calendar month containing [nowMillis]. */
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

    /** The percentage change from [previous] to [current], rounded: +12 for a rise of 12%. */
    fun changePercent(current: Double, previous: Double): Int? =
        if (previous > 0.0) ((current - previous) / previous * 100).roundToInt() else null

    /** Spending per category, largest first. */
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

    /** Midnight UTC on the 1st of the month [monthOffset] months away from the local month containing [nowMillis]. */
    private fun monthStartUtc(nowMillis: Long, localTimeZone: TimeZone, monthOffset: Int): Long {
        // Step 1: what year and month is it where the user is?
        val local = Calendar.getInstance(localTimeZone).apply { timeInMillis = nowMillis }
        val year = local.get(Calendar.YEAR)
        val month = local.get(Calendar.MONTH)

        // Step 2: that month's 1st.
        return Calendar.getInstance(UTC).apply {
            clear()
            set(year, month + monthOffset, 1)
        }.timeInMillis
    }
}
