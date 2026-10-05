package com.prash.petcare

import android.app.Application
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.prash.petcare.data.assistant.AssistantRepository
import com.prash.petcare.data.assistant.OpenAiAssistantRepository
import com.prash.petcare.data.auth.FirebasePasswordResetRepository
import com.prash.petcare.data.auth.PasswordResetRepository
import com.prash.petcare.data.remote.assistant.OpenAiApi
import com.prash.petcare.data.remote.geocoding.MapTilerGeocodingApi
import com.prash.petcare.data.local.AlertReadStore
import com.prash.petcare.data.local.DelegationContactStore
import com.prash.petcare.data.local.LoginAttemptStore
import com.prash.petcare.data.local.PetCareDatabase
import com.prash.petcare.data.local.SettingsStore
import com.prash.petcare.data.local.WeatherCache
import com.prash.petcare.data.remote.FirebaseAuthService
import com.prash.petcare.data.remote.FirestoreSyncService
import com.prash.petcare.data.remote.weather.WeatherApi
import com.prash.petcare.data.repository.AlertRepository
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.location.GeocodingRepository
import com.prash.petcare.data.location.LocationRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.PlaceRepository
import com.prash.petcare.data.repository.SyncDeletionRepository
import com.prash.petcare.data.repository.TwoFactorRepository
import com.prash.petcare.data.repository.UserRepository
import com.prash.petcare.data.repository.VaccinationRepository
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

/** The app's [Application] class, used here as a simple dependency container. */
class PetCareApplication : Application() {

    /** The single Room database instance for the whole process. */
    private val database: PetCareDatabase by lazy { PetCareDatabase.getInstance(this) }

    /** The app's single route to Firebase Authentication. */
    private val firebaseAuthService: FirebaseAuthService by lazy {
        FirebaseAuthService(FirebaseAuth.getInstance())
    }

    /** Accounts: Firebase Authentication for the password, Room for the profile. */
    val userRepository: UserRepository by lazy {
        UserRepository(database.userDao(), firebaseAuthService)
    }

    /** The account's second factor: the authenticator secret, the recovery codes, and the checking of both. */
    val twoFactorRepository: TwoFactorRepository by lazy {
        TwoFactorRepository(database.userDao(), database.recoveryCodeDao())
    }
    /** The queue of deleted rows waiting to be removed from Firestore. */
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

    /** Each pet's vaccination history, for the card on its detail screen and the screen that adds to it. */
    val vaccinationRepository: VaccinationRepository by lazy {
        VaccinationRepository(database.vaccinationRecordDao())
    }

    /** The user's saved vets, groomers, parks and shops, for the Places tab. */
    val placeRepository: PlaceRepository by lazy {
        PlaceRepository(database.placeDao(), database.syncDeletionDao())
    }

    /** Which alerts the user has already read, for the notifications screen. */
    val alertReadStore: AlertReadStore by lazy { AlertReadStore(this) }

    /** What the user should be told about, derived from the pets, tasks, expenses and routines they already have. */
    val alertRepository: AlertRepository by lazy {
        AlertRepository(
            petRepository = petRepository,
            careRoutineRepository = careRoutineRepository,
            careTaskRepository = careTaskRepository,
            expenseRepository = expenseRepository,
            alertReadStore = alertReadStore
        )
    }

    /** How many wrong passwords each email address has collected on this device. */
    val loginAttemptStore: LoginAttemptStore by lazy { LoginAttemptStore(this) }

    /** The display and security settings the profile screen controls. */
    val settingsStore: SettingsStore by lazy { SettingsStore(this) }

    /** The people the user hands a checklist to, for the profile screen's delegation card. */
    val delegationContactStore: DelegationContactStore by lazy { DelegationContactStore(this) }

    /** Where the phone is, for capturing a new place's geotag and measuring distances to saved ones. */
    val locationRepository: LocationRepository by lazy { LocationRepository(this) }

    /** The name of the point under the map's pin, for the Places forms. */
    val geocodingRepository: GeocodingRepository by lazy {
        GeocodingRepository(
            context = this,
            api = MapTilerGeocodingApi.create(),
            apiKey = BuildConfig.MAPTILER_API_KEY
        )
    }

    /** The live temperature behind the home screen's walk-window card. */
    val weatherRepository: WeatherRepository by lazy {
        WeatherRepository(
            weatherApi = WeatherApi.create(),
            cache = WeatherCache(this),
            locationRepository = locationRepository
        )
    }

    /** Where the assistant screen's answers come from. */
    val assistantRepository: AssistantRepository by lazy {
        OpenAiAssistantRepository(
            api = OpenAiApi.create(),
            apiKey = BuildConfig.OPENAI_API_KEY
        )
    }

    /** Sends password-reset emails through Firebase Authentication. */
    val passwordResetRepository: PasswordResetRepository by lazy {
        FirebasePasswordResetRepository(firebaseAuthService)
    }

    /** Mirrors the signed-in account's data between Room and Firestore. */
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
            // `this` is the Application, so the network callback it registers lives as long as the process.
            networkMonitor = NetworkMonitor(this)
        )
    }

    /** A coroutine scope that lives as long as the process. */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Builds and posts reminder notifications; also owns their notification channel. */
    private val reminderNotifier: ReminderNotifier by lazy { ReminderNotifier(this) }

    /** Registers and cancels the system alarms that fire the reminders. */
    private val reminderScheduler: ReminderScheduler by lazy { ReminderScheduler(this) }

    /** Keeps the registered alarms matching the care tasks in the database. */
    val reminderManager: ReminderManager by lazy {
        ReminderManager(
            careTaskRepository = careTaskRepository,
            scheduler = reminderScheduler,
            notifier = reminderNotifier,
            settingsStore = settingsStore
        )
    }

    /** Runs once per process, before any Activity or receiver exists. */
    override fun onCreate() {
        super.onCreate()

        // Before anything else, and before the first Activity exists.
        ThemePreference.apply(this)

        reminderNotifier.createNotificationChannel()

        // From here on, every change to a task, routine or pet re-plans the alarms by itself.
        reminderManager.startObserving(applicationScope)

        // The sync watches the data the same way, and for the same reason.
        syncManager.startObserving(applicationScope)
    }
}
