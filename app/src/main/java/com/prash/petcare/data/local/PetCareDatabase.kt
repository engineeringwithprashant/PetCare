package com.prash.petcare.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.prash.petcare.data.local.converter.Converters
import com.prash.petcare.data.local.dao.CareRoutineDao
import com.prash.petcare.data.local.dao.CareTaskDao
import com.prash.petcare.data.local.dao.ExpenseDao
import com.prash.petcare.data.local.dao.PetDao
import com.prash.petcare.data.local.dao.UserDao
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.User

/**
 * The app's Room database — the single SQLite file holding every record.
 *
 * Room generates the implementation of this abstract class at build time from the `entities`
 * list below, creating the tables, foreign keys and indexes each entity declares.
 *
 * **Version** must be incremented whenever the schema changes (a new column, table or index).
 * Room compares the declared version against the one stored in the file on disk and refuses to
 * open a mismatched database without a migration.
 */
@Database(
    entities = [
        User::class,
        Pet::class,
        CareRoutine::class,
        CareTask::class,
        Expense::class
    ],
    version = 1,
    // Schema export writes a JSON description of the database to disk for migration testing.
    // Disabled here because this coursework app ships at version 1 with no migrations; leaving
    // it on without configuring an output directory produces a build warning.
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PetCareDatabase : RoomDatabase() {

    // Room implements each of these to return a working DAO. They are declared abstract with no
    // body because the generated subclass supplies the code.
    abstract fun userDao(): UserDao
    abstract fun petDao(): PetDao
    abstract fun careRoutineDao(): CareRoutineDao
    abstract fun careTaskDao(): CareTaskDao
    abstract fun expenseDao(): ExpenseDao

    companion object {

        private const val DATABASE_NAME = "petcare.db"

        /**
         * The one shared instance.
         *
         * `@Volatile` means a write here is immediately visible to every thread, which stops one
         * thread seeing a half-constructed instance created by another.
         */
        @Volatile
        private var INSTANCE: PetCareDatabase? = null

        /**
         * Returns the shared database, creating it on first use.
         *
         * Building a database is expensive and each instance keeps its own connection pool, so
         * the app must only ever have one. The double-checked lock below achieves that: the
         * fast path returns the existing instance without locking, and the `synchronized` block
         * guarantees that two threads arriving together still create only one.
         *
         * [Context.getApplicationContext] is used deliberately — holding an Activity context in
         * a long-lived singleton would leak that Activity for the lifetime of the process.
         */
        fun getInstance(context: Context): PetCareDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PetCareDatabase::class.java,
                    DATABASE_NAME
                )
                    // No .fallbackToDestructiveMigration() here: that flag wipes all user data
                    // whenever the schema version changes. Any future version must add a proper
                    // Migration instead.
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
