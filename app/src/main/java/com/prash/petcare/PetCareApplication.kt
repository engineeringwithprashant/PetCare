package com.prash.petcare

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore
import com.prash.petcare.data.assistant.AssistantRepository
import com.prash.petcare.data.assistant.OpenAiAssistantRepository
import com.prash.petcare.data.remote.assistant.OpenAiApi
import com.prash.petcare.data.local.PetCareDatabase
import com.prash.petcare.data.local.WeatherCache
import com.prash.petcare.data.remote.FirestoreSyncService
import com.prash.petcare.data.remote.weather.WeatherApi
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.location.LocationRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.PlaceRepository
import com.prash.petcare.data.repository.SyncDeletionRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.data.repository.WeatherRepository
import com.prash.petcare.data.sync.NetworkMonitor
import com.prash.petcare.data.sync.SyncManager
import com.prash.petcare.reminder.ReminderManager
import com.prash.petcare.reminder.ReminderNotifier
import com.prash.petcare.reminder.ReminderScheduler
import com.prash.petcare.ui.common.ThemePreference
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

    /**
     * The queue of deleted rows waiting to be removed from Firestore.
     *
     * The five repositories below each take the same DAO, because every one of them can delete
     * something — see [PetRepository] for why recording a deletion belongs in that layer.
     */
    val syncDeletionRepository: SyncDeletionRepository by lazy {
        SyncDeletionRepository(database.syncDeletionDao())
    }

    val petRepository: PetRepository by lazy {
        PetRepository(database.petDao(), database.syncDeletionDao())
    }

    val careRoutineRepository: CareRoutineRepository by lazy {
        CareRoutineRepository(database.careRoutineDao(), database.syncDeletionDao())
    }

    /** Tasks, and the completion history kept beside them — see [CareTaskRepository]. */
    val careTaskRepository: CareTaskRepository by lazy {
        CareTaskRepository(
            database.careTaskDao(),
            database.syncDeletionDao(),
            database.careCompletionDao()
        )
    }

    val expenseRepository: ExpenseRepository by lazy {
        ExpenseRepository(database.expenseDao(), database.syncDeletionDao())
    }

    /** The user's saved vets, groomers, parks and shops, for the Places tab. */
    val placeRepository: PlaceRepository by lazy {
        PlaceRepository(database.placeDao(), database.syncDeletionDao())
    }

    /**
     * Where the phone is, for capturing a new place's geotag and measuring distances to saved ones.
     *
     * Given the Application rather than an Activity: it outlives every screen, and a location client
     * holding an Activity would leak it. See [LocationRepository] for why location is treated as a
     * data source like Room rather than as something a screen reads directly.
     */
    val locationRepository: LocationRepository by lazy { LocationRepository(this) }

    /**
     * The live temperature behind the home screen's walk-window card.
     *
     * Created here, like everything else, but for one reason beyond consistency: this repository
     * holds the current reading in a [kotlinx.coroutines.flow.StateFlow] of its own, so sharing a
     * single instance is what lets the card redraw instantly when the user returns to the home tab
     * instead of re-fetching. A per-ViewModel instance would throw that away on every rotation.
     *
     * It is given the other two data sources it arbitrates between rather than building them: the
     * location repository is the app's single location client, and [WeatherApi.create] is called
     * once here because a Retrofit instance carries an HTTP client, a connection pool and a thread
     * pool that should exist once per process rather than once per request.
     */
    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(
            weatherApi = WeatherApi.create(),
            cache = WeatherCache(this),
            locationRepository = locationRepository
        )
    }

    /**
     * Where the assistant screen's answers come from.
     *
     * Declared as the [AssistantRepository] interface rather than the concrete class, which is the
     * point of the interface: this line is the only place in the app that names an implementation,
     * so the backend was swapped from an on-device stub to OpenAI without a single screen, ViewModel
     * or layout changing.
     *
     * **The key may be blank, and that is handled rather than prevented.** BuildConfig.OPENAI_API_KEY
     * is empty for anyone who has cloned the project without adding one to local.properties — see
     * app/build.gradle.kts — so the app still builds and runs, and the assistant says plainly that
     * the key is missing instead of failing silently or crashing.
     */
    val assistantRepository: AssistantRepository by lazy {
        OpenAiAssistantRepository(
            api = OpenAiApi.create(),
            apiKey = BuildConfig.OPENAI_API_KEY
        )
    }

    /**
     * Mirrors the signed-in account's data between Room and Firestore.
     *
     * Public because the home screen's ViewModel needs two things from it: to say who has signed
     * in, and to observe the status shown in the indicator. Nothing else in the app refers to it —
     * screens read from Room exactly as they did before this layer existed.
     *
     * `FirebaseFirestore.getInstance()` is safe to call at this point: the google-services Gradle
     * plugin turns app/google-services.json into resources that FirebaseApp reads through its own
     * content provider, which the system runs before this class is even constructed. There is
     * therefore no initialisation call to make here.
     */
    val syncManager: SyncManager by lazy {
        SyncManager(
            userRepository = userRepository,
            petRepository = petRepository,
            careRoutineRepository = careRoutineRepository,
            careTaskRepository = careTaskRepository,
            expenseRepository = expenseRepository,
            placeRepository = placeRepository,
            syncDeletionRepository = syncDeletionRepository,
            firestoreSyncService = FirestoreSyncService(FirebaseFirestore.getInstance()),
            // `this` is the Application, so the network callback it registers lives as long as the
            // process rather than as long as whichever screen happened to be open.
            networkMonitor = NetworkMonitor(this)
        )
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

        // Before anything else, and before the first Activity exists. The user's light/dark choice
        // decides which resources every screen resolves against, so applying it after a screen had
        // been created would draw that screen in the wrong mode and then recreate it — visible as a
        // flash on every launch. See ThemePreference for why this one setting lives outside Room.
        ThemePreference.apply(this)

        reminderNotifier.createNotificationChannel()

        // From here on, every change to a task, routine or pet re-plans the alarms by itself. See
        // ReminderManager for why reminders are derived from the data rather than updated by hand at
        // each place a task is saved.
        reminderManager.startObserving(applicationScope)

        // The sync watches the data the same way, and for the same reason. It does nothing at all
        // until someone signs in — there is no account yet whose data could be synced — and from
        // that moment it uploads and downloads by itself whenever anything changes. Starting it
        // here rather than from a screen is what lets a sync outlive the screen that triggered it.
        syncManager.startObserving(applicationScope)
    }
}
