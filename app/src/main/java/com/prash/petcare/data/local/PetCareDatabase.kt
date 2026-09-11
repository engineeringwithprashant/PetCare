package com.prash.petcare.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
    version = 3,
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
         * Version 1 to 2: adds the detailed care fields to the `pets` table.
         *
         * A migration describes, in SQL, how to reshape an existing database so it matches the new
         * schema. Without one Room refuses to open a file created by an older version, because the
         * alternative — silently deleting the user's pets — would be far worse.
         *
         * Three plain ADD COLUMN statements are enough here. SQLite can append a column to an
         * existing table cheaply, so no data is copied and nothing is at risk. The columns are
         * nullable TEXT with no default, which is exactly how Room maps a `String?` property, and
         * rows created before this upgrade simply have null in the new fields.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pets ADD COLUMN dietaryNotes TEXT")
                db.execSQL("ALTER TABLE pets ADD COLUMN allergies TEXT")
                db.execSQL("ALTER TABLE pets ADD COLUMN favouriteToys TEXT")
            }
        }

        /**
         * Version 2 to 3: adds the scheduling and supplies fields to the `care_tasks` table.
         *
         * Both columns are nullable with no default, which is exactly how Room maps `Int?` and
         * `String?`. That matters: Room compares the declared default against the real one when it
         * opens the file, so adding `NOT NULL DEFAULT 0` here would not match the entity and the
         * app would refuse to start.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE care_tasks ADD COLUMN timeOfDayMinutes INTEGER")
                db.execSQL("ALTER TABLE care_tasks ADD COLUMN requiredSupplies TEXT")
            }
        }

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
                    // Every upgrade path must be listed here. Note there is deliberately no
                    // .fallbackToDestructiveMigration(): that flag wipes all user data whenever
                    // the schema version changes, so any future version adds a Migration instead.
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
