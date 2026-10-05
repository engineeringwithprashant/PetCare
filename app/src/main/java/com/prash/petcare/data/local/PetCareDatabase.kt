package com.prash.petcare.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.prash.petcare.data.local.converter.Converters
import com.prash.petcare.data.local.dao.CareCompletionDao
import com.prash.petcare.data.local.dao.CareRoutineDao
import com.prash.petcare.data.local.dao.CareTaskDao
import com.prash.petcare.data.local.dao.ExpenseDao
import com.prash.petcare.data.local.dao.PetDao
import com.prash.petcare.data.local.dao.PlaceDao
import com.prash.petcare.data.local.dao.RecoveryCodeDao
import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.dao.UserDao
import com.prash.petcare.data.local.dao.VaccinationRecordDao
import com.prash.petcare.data.local.entity.CareCompletion
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.RecoveryCode
import com.prash.petcare.data.local.entity.SyncDeletion
import com.prash.petcare.data.local.entity.User
import com.prash.petcare.data.local.entity.VaccinationRecord

/** The app's Room database — the single SQLite file holding every record. */
@Database(
    entities = [
        User::class,
        Pet::class,
        CareRoutine::class,
        CareTask::class,
        Expense::class,
        SyncDeletion::class,
        Place::class,
        CareCompletion::class,
        RecoveryCode::class,
        VaccinationRecord::class
    ],
    version = 12,
    // Schema export writes a JSON description of the database to disk for migration testing.
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class PetCareDatabase : RoomDatabase() {

    // Room implements each of these to return a working DAO.
    abstract fun userDao(): UserDao
    abstract fun petDao(): PetDao
    abstract fun careRoutineDao(): CareRoutineDao
    abstract fun careTaskDao(): CareTaskDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun syncDeletionDao(): SyncDeletionDao
    abstract fun placeDao(): PlaceDao
    abstract fun careCompletionDao(): CareCompletionDao
    abstract fun recoveryCodeDao(): RecoveryCodeDao
    abstract fun vaccinationRecordDao(): VaccinationRecordDao

    companion object {

        private const val DATABASE_NAME = "petcare.db"

        /** Version 1 to 2: adds the detailed care fields to the `pets` table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pets ADD COLUMN dietaryNotes TEXT")
                db.execSQL("ALTER TABLE pets ADD COLUMN allergies TEXT")
                db.execSQL("ALTER TABLE pets ADD COLUMN favouriteToys TEXT")
            }
        }

        /** Version 2 to 3: adds the scheduling and supplies fields to the `care_tasks` table. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE care_tasks ADD COLUMN timeOfDayMinutes INTEGER")
                db.execSQL("ALTER TABLE care_tasks ADD COLUMN requiredSupplies TEXT")
            }
        }

        /** Version 3 to 4: adds the Firestore sync layer's bookkeeping. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("pets", "care_routines", "care_tasks", "expenses").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE $table SET updatedAt = createdAt")
                }

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `sync_deletions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `entityType` TEXT NOT NULL,
                        `entityId` INTEGER NOT NULL,
                        `deletedAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_deletions_entityType_entityId`
                    ON `sync_deletions` (`entityType`, `entityId`)
                    """.trimIndent()
                )
            }
        }

        /** Version 4 to 5: adds the `isPendingSync` flag to the four synced tables. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("pets", "care_routines", "care_tasks", "expenses").forEach { table ->
                    db.execSQL(
                        "ALTER TABLE $table ADD COLUMN isPendingSync INTEGER NOT NULL DEFAULT 1"
                    )
                }
            }
        }

        /** Version 5 to 6: adds the `places` table behind the Places tab. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `places` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `ownerId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `area` TEXT,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `phone` TEXT,
                        `openingMinutes` INTEGER,
                        `closingMinutes` INTEGER,
                        `notes` TEXT,
                        `isPinned` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `isPendingSync` INTEGER NOT NULL,
                        FOREIGN KEY(`ownerId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_places_ownerId` ON `places` (`ownerId`)"
                )
            }
        }

        /** Version 6 to 7: adds the `gender` column to the `pets` table. */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pets ADD COLUMN gender TEXT NOT NULL DEFAULT 'UNKNOWN'")
            }
        }

        /** Version 7 to 8: adds the `care_completions` history table behind the dashboard's care streak. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `care_completions` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `taskId` INTEGER NOT NULL,
                        `petId` INTEGER NOT NULL,
                        `dayStart` INTEGER NOT NULL,
                        `completedAt` INTEGER NOT NULL,
                        FOREIGN KEY(`taskId`) REFERENCES `care_tasks`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(`petId`) REFERENCES `pets`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_care_completions_taskId_dayStart`
                    ON `care_completions` (`taskId`, `dayStart`)
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_care_completions_petId` ON `care_completions` (`petId`)"
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_care_completions_completedAt`
                    ON `care_completions` (`completedAt`)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO `care_completions` (`taskId`, `petId`, `dayStart`, `completedAt`)
                    SELECT `id`, `petId`,
                        CAST(strftime('%s', `completedAt` / 1000, 'unixepoch', 'localtime',
                            'start of day', 'utc') AS INTEGER) * 1000,
                        `completedAt`
                    FROM `care_tasks`
                    WHERE `completedAt` IS NOT NULL
                    """.trimIndent()
                )
            }
        }

        /** Version 8 to 9: adds the profile fields to the `users` table. */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN phone TEXT")
                db.execSQL("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'OWNER'")
                db.execSQL("ALTER TABLE users ADD COLUMN passwordChangedAt INTEGER")
                db.execSQL("ALTER TABLE users ADD COLUMN photoUri TEXT")
            }
        }

        /** Version 9 to 10: removes the `passwordHash` column from the `users` table. */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `users_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fullName` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `phone` TEXT,
                        `role` TEXT NOT NULL,
                        `passwordChangedAt` INTEGER,
                        `photoUri` TEXT,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO `users_new`
                        (`id`, `fullName`, `email`, `phone`, `role`, `passwordChangedAt`, `photoUri`, `createdAt`)
                    SELECT `id`, `fullName`, `email`, `phone`, `role`, `passwordChangedAt`, `photoUri`, `createdAt`
                    FROM `users`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE `users`")
                db.execSQL("ALTER TABLE `users_new` RENAME TO `users`")
                // Dropping the table dropped its index too, so it is recreated on the renamed table.
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_users_email` ON `users` (`email`)")
            }
        }

        /** Version 10 to 11: adds two-factor authentication — the authenticator secret on the `users` table. */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN twoFactorSecret TEXT")
                db.execSQL("ALTER TABLE users ADD COLUMN twoFactorEnabledAt INTEGER")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recovery_codes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `userId` INTEGER NOT NULL,
                        `salt` TEXT NOT NULL,
                        `codeHash` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `usedAt` INTEGER,
                        FOREIGN KEY(`userId`) REFERENCES `users`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_recovery_codes_userId`
                    ON `recovery_codes` (`userId`)
                    """.trimIndent()
                )
            }
        }

        /** Version 11 to 12: adds the `vaccination_records` table behind the pet detail screen's vaccination history card. */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `vaccination_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `petId` INTEGER NOT NULL,
                        `vaccineName` TEXT NOT NULL,
                        `dateGiven` INTEGER NOT NULL,
                        `dateDue` INTEGER,
                        `clinic` TEXT,
                        FOREIGN KEY(`petId`) REFERENCES `pets`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS `index_vaccination_records_petId`
                    ON `vaccination_records` (`petId`)
                    """.trimIndent()
                )

                seedRecords(db, "CAT", CAT_SEED_RECORDS)
                seedRecords(db, "DOG", DOG_SEED_RECORDS)
            }
        }

        /** One seeded vaccination, as the dates read on a vet's card. */
        private data class SeedRecord(
            val vaccineName: String,
            val givenOn: String,
            val dueOn: String?,
            val clinic: String
        )

        /** The starter history for a cat: its core vaccine, and rabies a fortnight later. */
        private val CAT_SEED_RECORDS = listOf(
            SeedRecord("FVRCP (core)", "2025-09-22", "2026-09-22", "Patan Animal Hospital"),
            SeedRecord("Rabies", "2025-10-04", "2026-10-04", "Patan Animal Hospital")
        )

        /** The starter history for a dog: the annual booster, DHPP, and leptospirosis. */
        private val DOG_SEED_RECORDS = listOf(
            SeedRecord("Rabies booster", "2025-09-24", "2026-09-24", "Patan Animal Hospital"),
            SeedRecord("DHPP", "2026-03-12", "2027-03-12", "Bhaisepati Vet Clinic"),
            SeedRecord("Leptospirosis", "2025-06-02", "2026-06-02", "Bhaisepati Vet Clinic")
        )

        /** Writes one species' seed records against every pet of that species. */
        private fun seedRecords(
            db: SupportSQLiteDatabase,
            species: String,
            records: List<SeedRecord>
        ) {
            records.forEach { record ->
                db.execSQL(
                    """
                    INSERT INTO `vaccination_records`
                        (`petId`, `vaccineName`, `dateGiven`, `dateDue`, `clinic`)
                    SELECT `id`, ?,
                        CAST(strftime('%s', ?) AS INTEGER) * 1000,
                        CAST(strftime('%s', ?) AS INTEGER) * 1000,
                        ?
                    FROM `pets` WHERE `species` = ?
                    """.trimIndent(),
                    arrayOf(
                        record.vaccineName,
                        record.givenOn,
                        record.dueOn,
                        record.clinic,
                        species
                    )
                )
            }
        }

        /** The one shared instance. */
        @Volatile
        private var INSTANCE: PetCareDatabase? = null

        /** Returns the shared database, creating it on first use. */
        fun getInstance(context: Context): PetCareDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PetCareDatabase::class.java,
                    DATABASE_NAME
                )
                    // Every upgrade path must be listed here. Note there is deliberately no .fallbackToDestructiveMigration().
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
