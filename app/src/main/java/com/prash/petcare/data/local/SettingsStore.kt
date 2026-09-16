package com.prash.petcare.data.local

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Calendar

/**
 * Which units a weight is written in.
 *
 * Stored rather than read from the device's locale, because the two questions are different: a phone
 * bought in the US is set to imperial, and a vet in Kathmandu still writes kilograms on the chart.
 * The value the user typed into the pet form is always kilograms — see [WeightUnit.IMPERIAL] — so
 * this setting changes only how that number is *displayed*.
 */
enum class WeightUnit {

    /** Kilograms, as the weight is stored and as the pet form asks for it. */
    METRIC,

    /**
     * Pounds, converted at display time.
     *
     * Nothing in the database changes when this is chosen. Converting the stored column instead
     * would lose precision a little more on every switch, and would make the same pet's weight
     * depend on which setting happened to be active when it was last edited.
     */
    IMPERIAL
}

/** How the user would rather be told a task is due. */
enum class ReminderChannel {

    /** A notification from the app, which is what the reminder alarms already post. */
    PUSH,

    /**
     * A text message instead.
     *
     * Chosen here and honoured by the delegation flow, which is the one part of the app that can
     * actually send an SMS. Care reminders themselves stay notifications whatever this says — the app
     * has no number of its own to text and sending one per task would cost the user money per
     * reminder. The profile row says so rather than letting the choice imply more than it does.
     */
    SMS
}

/**
 * Which day a week begins on.
 *
 * Kept as this enum rather than as a raw [Calendar] day constant so the stored value is readable in
 * the preferences file and cannot be set to something that is not a week start. [calendarDay]
 * converts on the way out, because that is what [com.prash.petcare.util.CareSchedule] takes.
 */
enum class WeekStart(val calendarDay: Int) {
    SUNDAY(Calendar.SUNDAY),
    MONDAY(Calendar.MONDAY)
}

/**
 * Which currency symbol money is written with.
 *
 * **The amount itself is never converted**, and that is a deliberate limit rather than an oversight.
 * An expense is stored as a bare number with no currency attached, and the app has no exchange-rate
 * source — so turning 4,665 rupees into dollars would mean inventing a rate that goes stale the day
 * it is written. Changing the symbol alone is the honest half of the feature, and the profile row
 * says plainly that amounts are not converted.
 */
enum class CurrencyPreference(val symbol: String) {
    NPR("Rs"),
    USD("$")
}

/**
 * Every setting the profile screen's preference and security rows control.
 *
 * **Why SharedPreferences rather than Room.** The same reasoning as [AlertReadStore] and
 * [com.prash.petcare.ui.common.ThemePreference], which set it out at more length. Everything in the
 * Room database describes the user's *animals* and is synced to Firestore so it follows the account
 * to another handset. None of these is that: they are a handful of single values describing how this
 * installation should present itself, they are read on nearly every screen, and carrying "I prefer
 * pounds" to a borrowed tablet would be presumptuous rather than helpful. Adding an entity, a DAO, a
 * migration and a place in the sync for six values would be a great deal of machinery around a
 * preferences file.
 *
 * It lives under `data.local` because it *is* local persistence, alongside [AlertReadStore] and
 * [WeatherCache]. Being in the data layer is also what lets a ViewModel depend on it without holding
 * an Android view type, which is why it takes a [Context] and immediately forgets everything but the
 * application one.
 *
 * **Why every value is published as a [StateFlow].** These settings are not read once at start-up —
 * they change the Pets tab's weight pills, the Money tab's symbol and the Tasks tab's week strip
 * while those screens are open. Exposing each as a flow lets a ViewModel combine it with the database
 * like any other source, so flipping a toggle on the profile screen redraws the rest of the app
 * through exactly the same path a database change does, with no manual refresh anywhere.
 *
 * **What is deliberately absent: two-factor authentication.** Its switch on the profile screen is
 * disabled and stores nothing. Persisting a flag for a feature that does not exist would leave a
 * value in the file claiming the account is protected in a way it is not — see the note card the
 * screen draws beneath those rows.
 *
 * @param context Any context; only the application context is kept, so no screen can be leaked.
 */
class SettingsStore(context: Context) {

    private val preferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    // ---------------------------------------------------------------------------------------------
    // Preferences
    // ---------------------------------------------------------------------------------------------

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

    // ---------------------------------------------------------------------------------------------
    // Security
    // ---------------------------------------------------------------------------------------------

    private val _fingerprintUnlock =
        MutableStateFlow(preferences.getBoolean(KEY_FINGERPRINT_UNLOCK, false))

    /**
     * Whether the user has asked for the app to be locked behind their fingerprint.
     *
     * **Stored, but not yet enforced.** Actually gating the app would need the biometric library and
     * a lock screen in front of the navigation graph, neither of which exists — so the preference is
     * remembered and the profile row says exactly that, in the same way the two-factor row does.
     * Recording the intent now is what makes adding the gate later a change to one screen rather than
     * a change to the settings as well.
     */
    val fingerprintUnlock: StateFlow<Boolean> = _fingerprintUnlock.asStateFlow()

    private val _careRemindersEnabled =
        MutableStateFlow(preferences.getBoolean(KEY_CARE_REMINDERS, true))

    /**
     * Whether care reminders are posted at all.
     *
     * Unlike [fingerprintUnlock] this one is genuinely wired: [com.prash.petcare.reminder.ReminderManager]
     * reads it and withdraws every scheduled alarm when it is off. Defaulting to true keeps the
     * behaviour every existing installation already has — reminders were unconditional before this
     * switch existed, and an upgrade that silently turned them off would make people miss doses.
     */
    val careRemindersEnabled: StateFlow<Boolean> = _careRemindersEnabled.asStateFlow()

    // ---------------------------------------------------------------------------------------------
    // Writes
    // ---------------------------------------------------------------------------------------------

    fun setWeightUnit(value: WeightUnit) = write(KEY_WEIGHT_UNIT, value.name, _weightUnit, value)

    fun setReminderChannel(value: ReminderChannel) =
        write(KEY_REMINDER_CHANNEL, value.name, _reminderChannel, value)

    fun setWeekStart(value: WeekStart) = write(KEY_WEEK_START, value.name, _weekStart, value)

    fun setCurrency(value: CurrencyPreference) = write(KEY_CURRENCY, value.name, _currency, value)

    fun setFingerprintUnlock(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_FINGERPRINT_UNLOCK, enabled) }
        _fingerprintUnlock.value = enabled
    }

    fun setCareRemindersEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_CARE_REMINDERS, enabled) }
        _careRemindersEnabled.value = enabled
    }

    /**
     * Stores an enum choice and publishes it.
     *
     * Both in one place, like [AlertReadStore.write], so the file and the flow can never disagree.
     * The `edit` extension commits with `apply()`, which writes on a background thread; nothing waits
     * on the result, and the in-memory value is updated immediately either way.
     */
    private fun <T> write(key: String, stored: String, flow: MutableStateFlow<T>, value: T) {
        preferences.edit { putString(key, stored) }
        flow.value = value
    }

    /**
     * Reads an enum back by name, falling back to [fallback] if the stored text is not a constant.
     *
     * Names rather than ordinals, for the reason given in
     * [com.prash.petcare.data.local.converter.Converters]: an ordinal is an index, so inserting a
     * constant in the middle of an enum would silently re-point an existing preference at the wrong
     * value. The `runCatching` guards a file hand-edited or left behind by an older version — a
     * settings file is not worth crashing the app over.
     */
    private inline fun <reified T : Enum<T>> readEnum(key: String, fallback: T): T {
        val stored = preferences.getString(key, null) ?: return fallback
        return runCatching { enumValueOf<T>(stored) }.getOrDefault(fallback)
    }

    /**
     * What the week starts on before the user says otherwise.
     *
     * Taken from the device's locale rather than hard-coded, because the app already builds its week
     * that way — [com.prash.petcare.util.CareSchedule.weekDays] defaults to
     * `Calendar.getInstance().firstDayOfWeek`. Seeding from it means switching this setting on is not
     * a change to how the app already behaves; the user is only being given the switch.
     *
     * Any locale starting its week somewhere other than Sunday or Monday (Saturday, in parts of the
     * Middle East) lands on Monday, since the row offers only two choices.
     */
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
        const val KEY_FINGERPRINT_UNLOCK = "fingerprint_unlock"
        const val KEY_CARE_REMINDERS = "care_reminders_enabled"
    }
}
