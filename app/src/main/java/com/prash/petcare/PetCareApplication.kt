package com.prash.petcare

import android.app.Application
import com.prash.petcare.data.local.PetCareDatabase
import com.prash.petcare.data.repository.CareRoutineRepository
import com.prash.petcare.data.repository.CareTaskRepository
import com.prash.petcare.data.repository.ExpenseRepository
import com.prash.petcare.data.repository.PetRepository
import com.prash.petcare.data.repository.UserRepository

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
}
