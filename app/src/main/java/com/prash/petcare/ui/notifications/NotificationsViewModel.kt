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

/** Everything the notifications screen displays. */
data class NotificationsUiState(
    val items: List<AlertListItem> = emptyList(),
    val unreadCount: Int = 0,
    val isLoading: Boolean = true
) {
    /** True when the friendly empty state should replace the list. */
    val hasNoAlerts: Boolean get() = !isLoading && items.isEmpty()
}

/** Backs the notifications screen. */
class NotificationsViewModel(
    private val alertRepository: AlertRepository,
    userId: Long
) : ViewModel() {

    /** The whole screen. */
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

    /** Marks one alert read — what tapping a card does, alongside opening the screen it points at. */
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

    /** Splits the sorted alerts into TODAY and EARLIER, inserting a heading before each group. */
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

        /** Builds the ViewModel for a particular account. */
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
