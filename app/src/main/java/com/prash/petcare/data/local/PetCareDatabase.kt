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
import com.prash.petcare.data.local.dao.PlaceDao
import com.prash.petcare.data.local.dao.SyncDeletionDao
import com.prash.petcare.data.local.dao.UserDao
import com.prash.petcare.data.local.entity.CareRoutine
import com.prash.petcare.data.local.entity.CareTask
import com.prash.petcare.data.local.entity.Expense
import com.prash.petcare.data.local.entity.Pet
import com.prash.petcare.data.local.entity.Place
import com.prash.petcare.data.local.entity.SyncDeletion
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
        Expense::class,
        SyncDeletion::class,
        Place::class
    ],
    version = 7,
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
    abstract fun syncDeletionDao(): SyncDeletionDao
    abstract fun placeDao(): PlaceDao

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
         * Version 3 to 4: adds the Firestore sync layer's bookkeeping.
         *
         * Two separate changes, both needed before any syncing can happen:
         *
         * **`updatedAt` on the four synced tables.** This is the timestamp last-write-wins compares,
         * so every synced row needs one. `NOT NULL DEFAULT 0` is what lets the column be added to a
         * table that already has rows in it — SQLite has to put *something* in the existing rows,
         * and a NOT NULL column with no default would leave it no choice but to refuse.
         *
         * The `UPDATE` after each `ALTER` is the part that matters for correctness. Left at 0, every
         * pre-existing row would claim it was last changed in 1970 and the first sync would treat
         * the remote copy of anything as newer, quietly overwriting data the user has on their
         * device. Seeding from `createdAt` says "as far as this device knows, the row has not
         * changed since it was made", which is both true and safe.
         *
         * Note the entity properties deliberately declare no `@ColumnInfo(defaultValue = ...)`. Room
         * only validates a column's default when the *entity* states one, so the `DEFAULT 0` written
         * here satisfies SQLite without making the migrated schema disagree with a freshly created
         * one.
         *
         * **The `sync_deletions` table.** Created by hand to match exactly what Room generates from
         * [SyncDeletion] — same column types, the same `NOT NULL`s, and the index named the way Room
         * names it (`index_<table>_<columns>`). Room compares the file against the entities every
         * time it opens the database and refuses to start on any difference, so a migration that
         * creates a table has to reproduce it precisely rather than approximately.
         */
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

        /**
         * Version 4 to 5: adds the `isPendingSync` flag to the four synced tables.
         *
         * This is what stops a change made with no connection from being forgotten. Before it, the
         * only record that a row still needed uploading was the fact that a sync had failed — which
         * lives in memory and is gone the moment the app closes.
         *
         * **Existing rows are marked pending (`DEFAULT 1`), not clean.** The two choices are not
         * equally risky. Marking them clean would assume every row already reached Firestore, and
         * any row that had not — the exact rows this column exists to rescue — would be stranded
         * for good. Marking them pending costs one extra upload of data that is probably already
         * there, which last-write-wins makes harmless.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("pets", "care_routines", "care_tasks", "expenses").forEach { table ->
                    db.execSQL(
                        "ALTER TABLE $table ADD COLUMN isPendingSync INTEGER NOT NULL DEFAULT 1"
                    )
                }
            }
        }

        /**
         * Version 5 to 6: adds the `places` table behind the Places tab.
         *
         * A brand-new table rather than a change to an existing one, so no user data is touched —
         * every existing row stays exactly where it is, and the account simply starts with no saved
         * places.
         *
         * As with `sync_deletions` in [MIGRATION_3_4], the SQL has to reproduce what Room itself would
         * generate from [Place] *exactly*: the same column types (REAL for the two `Double`
         * coordinates, INTEGER for `Int`, `Long` and `Boolean`), `NOT NULL` on precisely the
         * non-nullable properties, the same foreign key with its CASCADE rule, and the index named the
         * way Room names it. Room compares the file against the entity every time the database opens
         * and refuses to start on any difference, so "close enough" here is a crash on launch.
         *
         * No `updatedAt` seeding or pending flag is needed, unlike the earlier sync migrations: the
         * table is empty, so there are no pre-existing rows to make claims about.
         */
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

        /**
         * Version 6 to 7: adds the `gender` column to the `pets` table.
         *
         * One ADD COLUMN, so — like [MIGRATION_1_2] — no rows are copied and nothing is at risk.
         * The difference from that migration is that [Pet.gender] is a *non-null* property, which
         * Room maps to `TEXT NOT NULL`. SQLite will not add a NOT NULL column to a table that
         * already has rows unless it is told what to put in them, so the statement carries
         * `DEFAULT 'UNKNOWN'`: every pet that existed before the upgrade becomes "not recorded",
         * which is exactly the truth — the app never asked.
         *
         * The default is written with the enum constant's *name*, because that is the text
         * [com.prash.petcare.data.local.converter.Converters] reads back. A value such as 'Unknown'
         * would make `PetGender.valueOf` throw the first time an old pet was loaded.
         *
         * As explained on [MIGRATION_3_4], the entity itself declares no default. Room only compares
         * a column's default when the entity states one, so the SQL default here satisfies SQLite
         * without making a migrated database disagree with a freshly created one.
         *
         * The rows are deliberately *not* marked as pending sync. Nothing the user entered has
         * changed, and the next edit to each pet uploads the new field anyway.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pets ADD COLUMN gender TEXT NOT NULL DEFAULT 'UNKNOWN'")
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
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
