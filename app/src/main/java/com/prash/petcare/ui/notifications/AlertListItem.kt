package com.prash.petcare.ui.notifications

import com.prash.petcare.data.alert.Alert

/**
 * How the notifications screen arranges its alerts into rows.
 *
 * In MVVM terms this is the boundary between the **ViewModel** and the **View** for that one screen:
 * the alerts themselves are data ([Alert], derived by
 * [com.prash.petcare.data.repository.AlertRepository]), while *grouping them under headings* is a
 * decision about the list and belongs here. That split is also why the two live in different layers —
 * the alerts are needed app-wide for the header's bell, and these rows are needed by one adapter.
 */

/** The two groups the list is split into, in the order they are shown. */
enum class AlertSection {
    TODAY,
    EARLIER
}

/**
 * One row of the list, which is either a section heading or an alert card.
 *
 * The headings are rows of the same RecyclerView rather than separate views above it, so the whole
 * page scrolls as one and the cards still recycle — the same arrangement the Money tab uses for its
 * total card and "ALL EXPENSES" heading.
 */
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
