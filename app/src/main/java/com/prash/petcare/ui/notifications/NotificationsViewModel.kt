package com.prash.petcare.ui.notifications

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.prash.petcare.PetCareApplication
import com.prash.petcare.data.alert.Alert
import com.prash.petcare.data.alert.AlertContent
import com.prash.petcare.data.repository.AlertRepository
import com.prash.petcare.util.CareSchedule
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn

/**
 * Everything the notifications screen displays.
 *
 * @property items The list, headings included, already grouped and in the order it is drawn.
 * @property unreadCount How many alerts have not been read — the "3 new alerts" headline, and what
 *   decides whether the "MARK READ" button has anything to do.
 * @property isLoading True until the first results arrive, so the screen can tell "still loading"
 *   apart from "genuinely nothing to show" — without it the empty state flashes up on every launch.
 */
data class NotificationsUiState(
    val items: List<AlertListItem> = emptyList(),
    val unreadCount: Int = 0,
    val isLoading: Boolean = true
) {
    /** True when the friendly empty state should replace the list. */
    val hasNoAlerts: Boolean get() = !isLoading && items.isEmpty()
}

/**
 * Backs the notifications screen.
 *
 * Sits in the middle of the MVVM chain: it takes the alerts [AlertRepository] derives and arranges
 * them into the rows [NotificationsFragment] draws. It holds no views and no Context, so it survives
 * rotation with its data intact, and it formats nothing — turning [AlertContent.Milestone] into
 * "Krishala turns 1 month old this week" needs string resources and the user's locale, which belong
 * to the View.
 *
 * **The rules themselves are not here.** They used to be, and they moved: the header on every screen
 * now carries a bell with the unread count on it, so the same derivation is wanted from a dozen
 * places rather than from this one. It lives in [AlertRepository], which explains what an alert is
 * and where each kind comes from. What is left here is this screen's own business — grouping the
 * alerts under TODAY and EARLIER, counting the unread ones for the headline, and marking them read.
 *
 * @param alertRepository Where the alerts come from.
 * @param userId Whose alerts to show, read from the session — see
 *   [com.prash.petcare.ui.session.SessionViewModel].
 */
class NotificationsViewModel(
    private val alertRepository: AlertRepository,
    userId: Long
) : ViewModel() {

    /**
     * The whole screen.
     *
     * The [onEach] afterwards is the one side effect in the chain: it tells the read store which ids
     * still exist so it can forget the rest. This screen does it rather than the repository because
     * it is the only place that holds the complete list — see [AlertRepository.forgetAlertsOtherThan].
     *
     * [stateIn] turns the cold result into a hot [StateFlow] holding the latest value, so a rotation
     * re-attaches to data that is already there instead of re-querying and flashing empty.
     * `WhileSubscribed(5_000)` keeps the queries alive for five seconds after the last observer
     * leaves — long enough to survive a rotation, short enough that a backgrounded screen stops
     * working.
     */
    val uiState: StateFlow<NotificationsUiState> = alertRepository.observeAlerts(userId)
        .map { alerts ->
            NotificationsUiState(
                items = groupIntoSections(alerts, CareSchedule.startOfToday()),
                unreadCount = alerts.count { !it.isRead },
                isLoading = false
            )
        }
        .onEach { state -> alertRepository.forgetAlertsOtherThan(state.alertIds()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = NotificationsUiState()
        )

    /**
     * Marks one alert read — what tapping a card does, alongside opening the screen it points at.
     *
     * The ViewModel writes it rather than the fragment so the View never touches a data source
     * directly, which is the same rule that keeps it away from the DAOs.
     */
    fun markRead(alertId: String) {
        alertRepository.markRead(alertId)
    }

    /** Marks every alert on screen read — the "MARK READ" button. */
    fun markAllRead() {
        alertRepository.markRead(uiState.value.alertIds())
    }

    /** The ids of every alert currently listed, for marking and for pruning. */
    private fun NotificationsUiState.alertIds(): Set<String> =
        items.filterIsInstance<AlertListItem.AlertRow>()
            .map { it.alert.id }
            .toSet()

    /**
     * Splits the sorted alerts into TODAY and EARLIER, inserting a heading before each group.
     *
     * A heading is only added when its group has something in it, so a screen holding nothing but
     * older alerts does not show an empty "TODAY".
     *
     * The alerts arrive already sorted newest first, which is what guarantees every TODAY item
     * precedes every EARLIER one — so this only has to find the boundary rather than sort again.
     */
    private fun groupIntoSections(alerts: List<Alert>, todayStart: Long): List<AlertListItem> {
        if (alerts.isEmpty()) return emptyList()

        val (today, earlier) = alerts.partition { it.occurredAt >= todayStart }
        val items = mutableListOf<AlertListItem>()

        if (today.isNotEmpty()) {
            items += AlertListItem.SectionHeader(AlertSection.TODAY)
            items += today.map { AlertListItem.AlertRow(it) }
        }
        if (earlier.isNotEmpty()) {
            items += AlertListItem.SectionHeader(AlertSection.EARLIER)
            items += earlier.map { AlertListItem.AlertRow(it) }
        }

        return items
    }

    companion object {

        /**
         * Builds the ViewModel for a particular account.
         *
         * A function rather than a property because the user id is only known at runtime and has to
         * be captured here to reach the constructor. The repository comes from the Application, which
         * creates it once — so this screen shares its Room subscriptions with every header in the app
         * rather than opening its own.
         */
        fun factory(userId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val application =
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PetCareApplication
                NotificationsViewModel(
                    alertRepository = application.alertRepository,
                    userId = userId
                )
            }
        }
    }
}
