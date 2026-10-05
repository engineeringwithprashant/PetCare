package com.prash.petcare.ui.common

import android.content.Context
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.prash.petcare.R
import com.prash.petcare.data.local.entity.ExpenseCategory
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.round

/** Turns spending values into text for the screen. */

/** Display name for a spending category, e.g. MEDICATION becomes "Medication". */
@StringRes
fun ExpenseCategory.labelRes(): Int = when (this) {
    ExpenseCategory.FOOD -> R.string.expense_category_food
    ExpenseCategory.VET -> R.string.expense_category_vet
    ExpenseCategory.MEDICATION -> R.string.expense_category_medication
    ExpenseCategory.GROOMING -> R.string.expense_category_grooming
    ExpenseCategory.TOYS -> R.string.expense_category_toys
    ExpenseCategory.ACCESSORIES -> R.string.expense_category_accessories
    ExpenseCategory.INSURANCE -> R.string.expense_category_insurance
    ExpenseCategory.TRAINING -> R.string.expense_category_training
    ExpenseCategory.OTHER -> R.string.expense_category_other
}

/** Icon for a spending category. */
@DrawableRes
fun ExpenseCategory.iconRes(): Int = when (this) {
    ExpenseCategory.FOOD -> R.drawable.ic_category_feeding
    ExpenseCategory.VET -> R.drawable.ic_category_vet
    ExpenseCategory.MEDICATION -> R.drawable.ic_category_medication
    ExpenseCategory.GROOMING -> R.drawable.ic_category_grooming
    ExpenseCategory.TOYS -> R.drawable.ic_category_toys
    ExpenseCategory.ACCESSORIES -> R.drawable.ic_category_accessories
    ExpenseCategory.INSURANCE -> R.drawable.ic_category_insurance
    ExpenseCategory.TRAINING -> R.drawable.ic_category_training
    ExpenseCategory.OTHER -> R.drawable.ic_category_other
}

/** The emoji that stands for a spending category: 💊 for medication, 🍖 for food, 🎾 for toys. */
@StringRes
fun ExpenseCategory.emojiRes(): Int = when (this) {
    ExpenseCategory.FOOD -> R.string.expense_emoji_food
    ExpenseCategory.VET -> R.string.expense_emoji_vet
    ExpenseCategory.MEDICATION -> R.string.expense_emoji_medication
    ExpenseCategory.GROOMING -> R.string.expense_emoji_grooming
    ExpenseCategory.TOYS -> R.string.expense_emoji_toys
    ExpenseCategory.ACCESSORIES -> R.string.expense_emoji_accessories
    ExpenseCategory.INSURANCE -> R.string.expense_emoji_insurance
    ExpenseCategory.TRAINING -> R.string.expense_emoji_training
    ExpenseCategory.OTHER -> R.string.expense_emoji_other
}

/** The category as it appears where it is chosen — "💊 Medication". */
fun ExpenseCategory.emojiLabel(context: Context): String = context.getString(
    R.string.category_emoji_label,
    context.getString(emojiRes()),
    context.getString(labelRes())
)

/** The colour that stands for a spending category: its segment in the total card's stacked bar. */
@ColorRes
fun ExpenseCategory.colorRes(): Int = when (this) {
    ExpenseCategory.FOOD -> R.color.expense_category_food
    ExpenseCategory.VET -> R.color.expense_category_vet
    ExpenseCategory.MEDICATION -> R.color.expense_category_medication
    ExpenseCategory.GROOMING -> R.color.expense_category_grooming
    ExpenseCategory.TOYS -> R.color.expense_category_toys
    ExpenseCategory.ACCESSORIES -> R.color.expense_category_accessories
    ExpenseCategory.INSURANCE -> R.color.expense_category_insurance
    ExpenseCategory.TRAINING -> R.color.expense_category_training
    ExpenseCategory.OTHER -> R.color.expense_category_other
}

/** Builds the prefix written before every amount: the chosen symbol and a non-breaking space. */
private fun prefixFor(symbol: String): String = symbol + " "

/** Formats a stored amount as money for display: "Rs 4,665", or "Rs 425.50". */
fun formatCurrency(amount: Double, symbol: String): String {
    val isWhole = abs(amount - round(amount)) < HALF_PAISA
    val format = NumberFormat.getNumberInstance().apply {
        val decimals = if (isWhole) 0 else 2
        minimumFractionDigits = decimals
        maximumFractionDigits = decimals
    }
    return prefixFor(symbol) + format.format(amount)
}

/** An amount split into the part before the decimal separator and the part from it onwards: ("Rs 5,240", ".00"). */
fun splitCurrency(amount: Double, symbol: String): Pair<String, String> {
    val formatted = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }.format(amount)

    val prefix = prefixFor(symbol)
    val separatorIndex = formatted.lastIndexOf(DecimalFormatSymbols.getInstance().decimalSeparator)
    return if (separatorIndex < 0) {
        (prefix + formatted) to ""
    } else {
        (prefix + formatted.substring(0, separatorIndex)) to formatted.substring(separatorIndex)
    }
}

/** Anything closer to a whole rupee than half a paisa prints as one. */
private const val HALF_PAISA = 0.005

/** Formats a stored amount for an editable text field, e.g. 24.5 becomes "24.5" or "24,5". */
fun formatAmountForInput(amount: Double): String {
    val formatter = NumberFormat.getNumberInstance().apply {
        isGroupingUsed = false
        maximumFractionDigits = 2
    }
    return formatter.format(amount)
}
