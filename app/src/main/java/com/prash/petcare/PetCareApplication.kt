package com.prash.petcare

import android.app.Application
import com.prash.petcare.data.local.PetCareDatabase
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.reminder.ReminderManager
import com.prash.petcare.reminder.ReminderNotifier
import com.prash.petcare.reminder.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's [Application] class, used here as a simple dependency container.
 *
 * ViewModels need repositories, and repositories need DAOs from the database. Something has to
 * create those objects once and hand them out; a full dependency-injection framework such as
 * Hilt would do it automatically, but for an app this size the Application class is the
 * standard lightweight alternative and keeps the wiring visible in one readable place.
 *
 * Every property is `by lazy`, so nothing is constructed until something actually asks for it —
 * opening the database during app start-up would slow the first screen down for no reason. Each
 * `lazy` also caches its result, which is what makes these effectively singletons.
 *
 * ViewModels reach this class through `ViewModelProvider`'s APPLICATION_KEY rather than storing
 * a static reference, which keeps them testable.
 *
 * It is also the only place with a home for work that belongs to the app as a whole rather than to
 * any screen — see [onCreate], which starts the care reminders. That work needs a lifetime longer
 * than any Activity, and this class is the only thing that has one.
 *
 * Registered in AndroidManifest.xml via `android:name=".PetCareApplication"` — without that
 * entry Android would use the default Application and none of this would ever run.
 */
class PetCareApplication : Application() {

    /** The single Room database instance for the whole process. */
    private val database: PetCareDatabase by lazy { PetCareDatabase.getInstance(this) }

    val userRepository: UserRepository by lazy { UserRepository(database.userDao()) }

    val petRepository: PetRepository by lazy { PetRepository(database.petDao()) }

    val careRoutineRepository: CareRoutineRepository by lazy {
        CareRoutineRepository(database.careRoutineDao())
    }

    val careTaskRepository: CareTaskRepository by lazy {
        CareTaskRepository(database.careTaskDao())
    }

    val expenseRepository: ExpenseRepository by lazy {
        ExpenseRepository(database.expenseDao())
    }

    /**
     * A coroutine scope that lives as long as the process.
     *
     * Care reminders have to stay correct while no screen is open — that is the whole point of them
     * — so their work cannot hang off a `viewModelScope` or a fragment's lifecycle, both of which are
     * cancelled as soon as the user leaves. It is also the scope the reminder broadcast receivers use
     * while they hold the process open with `goAsync`.
     *
     * [SupervisorJob] rather than a plain `Job` so one failed child does not cancel the others: a
     * reminder failing to reschedule must not take the rest of the app's background work with it.
     * There is deliberately no `cancel()` anywhere — an Application is never destroyed in a way that
     * would give the app a chance to run one, since the process is simply killed.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Builds and posts reminder notifications; also owns their notification channel. */
    private val reminderNotifier: ReminderNotifier by lazy { ReminderNotifier(this) }

    /** Registers and cancels the system alarms that fire the reminders. */
    private val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(this) }

    /**
     * Keeps the registered alarms matching the care tasks in the database.
     *
     * Public because the two reminder [android.content.BroadcastReceiver]s reach it through this
     * class — a receiver is created by the system with nothing injected into it, so the Application
     * is how it finds the objects it needs.
     */
    val reminderManager: ReminderManager by lazy {
        ReminderManager(
            careTaskRepository = careTaskRepository,
            scheduler = reminderScheduler,
            notifier = reminderNotifier
        )
    }

    /**
     * Runs once per process, before any Activity or receiver exists.
     *
     * Both calls here have to happen this early. The notification channel must exist before any
     * reminder could be posted, or the notification is dropped without a word; and the reminder
     * observer has to be collecting before the user can reach a screen that changes a task.
     *
     * This runs on every process start, including one the system creates purely to deliver a
     * reminder broadcast — which is exactly what makes a reminder work when the app is not open.
     */
    override fun onCreate() {
        super.onCreate()

        reminderNotifier.createNotificationChannel()

        // From here on, every change to a task, routine or pet re-plans the alarms by itself. See
        // ReminderManager for why reminders are derived from the data rather than updated by hand at
        // each place a task is saved.
        reminderManager.startObserving(applicationScope)
    }
}
