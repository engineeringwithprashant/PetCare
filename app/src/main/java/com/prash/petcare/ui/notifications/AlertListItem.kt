package com.prash.petcare.ui.notifications

import com.prash.petcare.data.alert.Alert

/** How the notifications screen arranges its alerts into rows. */

/** The two groups the list is split into, in the order they are shown. */
enum class AlertSection {
    TODAY,
    EARLIER
}

/** One row of the list, which is either a section heading or an alert card. */
sealed interface AlertListItem {

    /** Identity for DiffUtil: what makes this row *this* row between one list and the next. */
    val itemId: String

    /** "TODAY" or "EARLIER". */
    data class SectionHeader(val section: AlertSection) : AlertListItem {
        override val itemId: String get() = "section:${section.name}"
    }

    /** One alert card. */
    data class AlertRow(val alert: Alert) : AlertListItem {
        override val itemId: String get() = alert.id
    }
}
