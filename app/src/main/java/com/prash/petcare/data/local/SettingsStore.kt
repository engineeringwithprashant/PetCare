package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/** Which units a weight is written in. */
enum class WeightUnit {

    /** Kilograms, as the weight is stored and as the pet form asks for it. */
    METRIC,

    /** Pounds, converted at display time. */
    IMPERIAL
}

/** How the user would rather be told a task is due. */
enum class ReminderChannel {

    /** A notification from the app, which is what the reminder alarms already post. */
    PUSH,

    /** A text message instead. */
    SMS
}

/** Which day a week begins on. */
enum class WeekStart(val calendarDay: Int) {
    SUNDAY(Calendar.SUNDAY),
    MONDAY(Calendar.MONDAY)
}

/** Which currency symbol money is written with. */
enum class CurrencyPreference(val symbol: String) {
    NPR("Rs"),
    USD("$")
}

/** Every setting the profile screen's preference and security rows control. */
class SettingsStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // Preferences.

    private val _weightUnit = MutableStateFlow(readEnum(KEY_WEIGHT_UNIT, WeightUnit.METRIC))

    /** Whether weights read as kilograms or pounds, on the pet cards and the pet detail screen. */
    val weightUnit: StateFlow<WeightUnit> = _weightUnit.asStateFlow()

    private val _reminderChannel =
        MutableStateFlow(readEnum(KEY_REMINDER_CHANNEL, ReminderChannel.PUSH))

    /** How the user would rather be told about care — see [ReminderChannel] on what this reaches. */
    val reminderChannel: StateFlow<ReminderChannel> = _reminderChannel.asStateFlow()

    private val _weekStart = MutableStateFlow(readEnum(KEY_WEEK_START, defaultWeekStart()))

    /** Which day the Tasks tab's week strip and the dashboard's care streak begin on. */
    val weekStart: StateFlow<WeekStart> = _weekStart.asStateFlow()

    private val _currency = MutableStateFlow(readEnum(KEY_CURRENCY, CurrencyPreference.NPR))

    /** The symbol written before every amount. Amounts themselves are never converted. */
    val currency: StateFlow<CurrencyPreference> = _currency.asStateFlow()

    // Security.

    private val _careRemindersEnabled =
        MutableStateFlow(preferences.getBoolean(KEY_CARE_REMINDERS, true))

    /** Whether care reminders are posted at all. */
    val careRemindersEnabled: StateFlow<Boolean> = _careRemindersEnabled.asStateFlow()

    init {
        forgetFingerprintUnlock()
    }

    /** Deletes the "unlock with fingerprint" preference left behind by earlier versions. */
    private fun forgetFingerprintUnlock() {
        if (!preferences.contains(KEY_REMOVED_FINGERPRINT_UNLOCK)) return
        preferences.edit { remove(KEY_REMOVED_FINGERPRINT_UNLOCK) }
    }

    // Writes.

    fun setWeightUnit(value: WeightUnit) = write(KEY_WEIGHT_UNIT, value.name, _weightUnit, value)

    fun setReminderChannel(value: ReminderChannel) =
        write(KEY_REMINDER_CHANNEL, value.name, _reminderChannel, value)

    fun setWeekStart(value: WeekStart) = write(KEY_WEEK_START, value.name, _weekStart, value)

    fun setCurrency(value: CurrencyPreference) = write(KEY_CURRENCY, value.name, _currency, value)

    fun setCareRemindersEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_CARE_REMINDERS, enabled) }
        _careRemindersEnabled.value = enabled
    }

    /** Stores an enum choice and publishes it. */
    private fun <T> write(key: String, stored: String, flow: MutableStateFlow<T>, value: T) {
        preferences.edit { putString(key, stored) }
        flow.value = value
    }

    /** Reads an enum back by name, falling back to [fallback] if the stored text is not a constant. */
    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T {
        val stored = preferences.getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(stored) }.getOrDefault(fallback)
    }

    /** What the week starts on before the user says otherwise. */
    private fun defaultWeekStart(): WeekStart =
        if (Calendar.getInstance().firstDayOfWeek == Calendar.SUNDAY) {
            WeekStart.SUNDAY
        } else {
            WeekStart.MONDAY
        }

    private companion object {
        /** Its own file rather than sharing another: unrelated settings, unrelated lifetimes. */
        const val PREFERENCES_NAME = "petcare_settings"

        const val KEY_WEIGHT_UNIT = "weight_unit"
        const val KEY_REMINDER_CHANNEL = "reminder_channel"
        const val KEY_WEEK_START = "week_start"
        const val KEY_CURRENCY = "currency"
        const val KEY_CARE_REMINDERS = "care_reminders_enabled"

        /** The key the removed "unlock with fingerprint" switch used to write to. */
        const val KEY_REMOVED_FINGERPRINT_UNLOCK = "fingerprint_unlock"
    }
}
