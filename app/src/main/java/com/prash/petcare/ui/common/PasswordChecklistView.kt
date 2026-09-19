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

/**
 * The live checklist of password rules shown under every field where a password is *chosen*.
 *
 * **Why a checklist rather than an error on submit.** A password that is refused after the button is
 * pressed tells the user only that they were wrong, and they then guess at which rule they broke —
 * usually by adding characters until something sticks, which produces worse passwords, not better
 * ones. Showing the six rules while the password is being invented turns the same policy into
 * instructions, and each line ticking off as it is satisfied is immediate confirmation that the thing
 * they just typed counted.
 *
 * **Its part in MVVM.** Purely View layer, and deliberately without a thought of its own: it holds no
 * password and decides nothing. The password lives in the ViewModel, which measures it with
 * [AuthValidation.requirementsFor] and publishes the answer; this class only draws that answer. So the
 * rules on screen and the rules actually enforced at submit are the same function's output, and
 * cannot drift apart.
 *
 * **Why a compound view.** Two screens show the identical checklist. A custom view keeps the six rows,
 * their icons, their colours and the accessibility announcement in one place — see the note at the top
 * of res/layout/view_password_checklist.xml — and gives each screen a typed object with a one-method
 * API.
 */
class PasswordChecklistView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    // view_password_checklist.xml is a <merge> layout, so this inflates its rows straight into this
    // LinearLayout rather than wrapping them in another one.
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

        // The length rule names the real numbers, built from the constants the validation uses, so
        // the figures the user is shown can never be figures the form does not actually enforce.
        binding.ruleLength.text = context.getString(
            R.string.password_rule_length,
            AuthValidation.MIN_PASSWORD_LENGTH,
            AuthValidation.MAX_PASSWORD_LENGTH
        )

        // The rows are read as one group rather than six — see below — so each is hidden from
        // TalkBack and the group's own description carries the summary.
        rows.forEach { (row, _) -> row.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES

        // Draw the "nothing typed yet" state immediately, so the checklist is on screen before the
        // first keystroke rather than appearing once the user starts.
        show(AuthValidation.PasswordRequirements())
    }

    /**
     * Applies the measured requirements: every satisfied rule turns to a filled tick in the app's
     * "all good" green, and every outstanding one stays an open ring in the muted text colour.
     *
     * **Both the icon and the colour change**, not just the colour. Roughly one man in twelve cannot
     * reliably separate the green from the grey, so a checklist that signalled only with colour would
     * be six identical lines to them. The ring filling in is the change they read.
     *
     * The whole list is also announced as a single "4 of 6 requirements met" rather than as six
     * separate lines, because it is updated on every keystroke: a screen reader given six live rows
     * would interrupt itself continuously while the user typed.
     */
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

    /**
     * Shows or hides the whole checklist.
     *
     * GONE rather than INVISIBLE, so a hidden checklist takes no space and the button below it does
     * not sit in a gap — see where the change-password form uses this.
     */
    fun setShown(shown: Boolean) {
        visibility = if (shown) View.VISIBLE else View.GONE
    }
}
