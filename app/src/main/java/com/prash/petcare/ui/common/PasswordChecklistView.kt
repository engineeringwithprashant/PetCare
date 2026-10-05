package com.prash.petcare.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import com.prash.petcare.R
import com.prash.petcare.databinding.ViewPasswordChecklistBinding
import com.prash.petcare.ui.auth.AuthValidation
import com.google.android.material.R as MaterialR

/** The live checklist of password rules shown under every field where a password is chosen. */
class PasswordChecklistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // view_password_checklist.xml is a <merge> layout, so this inflates its rows straight into this LinearLayout.
    private val binding = ViewPasswordChecklistBinding.inflate(LayoutInflater.from(context), this)

    /** Each row paired with the rule it reports, so [show] is one loop rather than six near-copies. */
    private val rows: List<Pair<TextView, (AuthValidation.PasswordRequirements) -> Boolean>> =
        with(binding) {
            listOf(
                ruleLength to { it: AuthValidation.PasswordRequirements -> it.hasLength },
                ruleUppercase to { it: AuthValidation.PasswordRequirements -> it.hasUppercase },
                ruleLowercase to { it: AuthValidation.PasswordRequirements -> it.hasLowercase },
                ruleDigit to { it: AuthValidation.PasswordRequirements -> it.hasDigit },
                ruleSpecial to { it: AuthValidation.PasswordRequirements -> it.hasSpecial },
                rulePattern to { it: AuthValidation.PasswordRequirements -> it.isNotPredictable }
            )
        }

    init {
        orientation = VERTICAL

        // The length rule names the real numbers, built from the constants the validation uses.
        binding.ruleLength.text = context.getString(
            R.string.password_rule_length,
            AuthValidation.MIN_PASSWORD_LENGTH,
            AuthValidation.MAX_PASSWORD_LENGTH
        )

        // The rows are read as one group rather than six — see below.
        rows.forEach { (row, _) -> row.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        // Draw the "nothing typed yet" state immediately, so the checklist is on screen before the first keystroke.
        show(AuthValidation.PasswordRequirements())
    }

    /** Applies the measured requirements. */
    fun show(requirements: AuthValidation.PasswordRequirements) {
        val metColour = ContextCompat.getColor(context, R.color.password_rule_met)
        val unmetColour = MaterialColors.getColor(this, MaterialR.attr.colorOnSurfaceVariant)

        var metCount = 0
        rows.forEach { (row, isMet) ->
            val met = isMet(requirements)
            if (met) metCount++
            applyState(row, met, if (met) metColour else unmetColour)
        }

        contentDescription = context.getString(
            R.string.cd_password_rules_progress,
            metCount,
            rows.size
        )
    }

    /** Sets one row's icon and colour. The icon is tinted rather than swapped for a coloured copy. */
    private fun applyState(row: TextView, isMet: Boolean, @ColorInt colour: Int) {
        TextViewCompat.setCompoundDrawablesRelativeWithIntrinsicBounds(
            row,
            ContextCompat.getDrawable(
                context,
                if (isMet) R.drawable.ic_rule_met else R.drawable.ic_rule_unmet
            ),
            null,
            null,
            null
        )
        TextViewCompat.setCompoundDrawableTintList(row, ColorStateList.valueOf(colour))
        row.setTextColor(colour)
    }

    /** Shows or hides the whole checklist. */
    fun setShown(shown: Boolean) {
        visibility = if (shown) View.VISIBLE else View.GONE
    }
}
