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
        Place::class,
        CareCompletion::class,
        RecoveryCode::class,
        VaccinationRecord::class
    ],
    version = 12,
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
    abstract fun careCompletionDao(): CareCompletionDao
    abstract fun recoveryCodeDao(): RecoveryCodeDao
    abstract fun vaccinationRecordDao(): VaccinationRecordDao

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
         * Version 7 to 8: adds the `care_completions` history table behind the dashboard's care streak.
         *
         * A new table, so — like [MIGRATION_5_6] — no existing row is touched. The CREATE statements
         * reproduce what Room generates from [CareCompletion] exactly (column types, `NOT NULL`s, both
         * foreign keys with their CASCADE rules, and the three indexes named the way Room names them),
         * because Room validates the file against the entity on every open and refuses to start on any
         * difference.
         *
         * **Seeding from what the tasks already know.** Before this version the only record of a tick
         * was each task's `completedAt` — the *last* time it was done. That single timestamp per task
         * is copied in as one history row each, so a task ticked off earlier today still counts towards
         * today's streak box after the upgrade, rather than the history starting completely empty.
         * Older days cannot be recovered: the timestamps that described them were overwritten long ago,
         * which is the very problem this table exists to fix.
         *
         * `dayStart` has to be *local* midnight, matching what the repository writes from Kotlin. SQLite
         * works it out in one expression: `'unixepoch'` reads the seconds, `'localtime'` shifts them into
         * the device's time zone, `'start of day'` truncates to midnight there, and `'utc'` shifts that
         * midnight back so `strftime('%s')` returns it as an epoch value. `OR IGNORE` is belt and braces:
         * the table is empty and each task contributes at most one row, so no conflict can arise.
         */
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

        /**
         * Version 8 to 9: adds the profile fields to the `users` table.
         *
         * Four ADD COLUMN statements, so — like [MIGRATION_1_2] — no rows are copied and nothing
         * existing is at risk. What each new column has to contain for the accounts already on the
         * device is the whole of the thinking here:
         *
         * **`phone`, `passwordChangedAt` and `photoUri` are nullable with no default**, which is
         * exactly how Room maps `String?` and `Long?`. Null is the truthful value in all three
         * cases: the app never asked for a number or a photo, and a password that has never been
         * changed since sign-up genuinely has no change date. Defaulting `passwordChangedAt` to
         * `createdAt` was tempting and is wrong — it would have the profile screen report a change
         * that never took place.
         *
         * **`role` is `TEXT NOT NULL DEFAULT 'OWNER'`**, the same shape as the `gender` column in
         * [MIGRATION_6_7] and for the same reason: SQLite will not add a NOT NULL column to a table
         * that already has rows unless it is told what to put in them. 'OWNER' is written as the
         * enum constant's *name*, because that is the text
         * [com.prash.petcare.data.local.converter.Converters] reads back — a value such as 'Owner'
         * would make `UserRole.valueOf` throw the first time an existing account was loaded.
         *
         * **`photoUri`, not `photoPath`.** The property is `photoPath`, but [User] maps it to a
         * column named `photoUri` with `@ColumnInfo`, matching how [Pet] stores the same thing. Room
         * validates the file against the entity on every open, so the column has to be created under
         * the name the entity declares rather than the name the property has.
         *
         * As explained on [MIGRATION_3_4], the entity itself declares no `defaultValue`. Room only
         * compares a column's default when the entity states one, so the SQL default here satisfies
         * SQLite without making a migrated database disagree with a freshly created one.
         *
         * Nothing needs marking as pending sync: the `users` table is not synced to Firestore at all
         * — only the account's email is used, as the remote key — so these columns never leave the
         * device. See [com.prash.petcare.data.sync.SyncManager].
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE users ADD COLUMN phone TEXT")
                db.execSQL("ALTER TABLE users ADD COLUMN role TEXT NOT NULL DEFAULT 'OWNER'")
                db.execSQL("ALTER TABLE users ADD COLUMN passwordChangedAt INTEGER")
                db.execSQL("ALTER TABLE users ADD COLUMN photoUri TEXT")
            }
        }

        /**
         * Version 9 to 10: removes the `passwordHash` column from the `users` table, because
         * Firebase Authentication now stores and checks passwords (see
         * [com.prash.petcare.data.remote.FirebaseAuthService]).
         *
         * **Why the table is rebuilt.** SQLite only gained `ALTER TABLE … DROP COLUMN` in version
         * 3.35, and Android 7 (this app's minSdk) ships a much older SQLite. So this follows
         * SQLite's own procedure for changing a table's shape: create the new table under a
         * temporary name, copy the rows across, drop the old table, and rename the new one into
         * place. Every row keeps its `id`, so pets, places and every other table that points to a
         * user by id still point to the same person.
         *
         * **Why the drop does not delete everyone's pets.** Those tables declare
         * `ON DELETE CASCADE` against `users`, so dropping `users` could look as if it would take
         * their rows with it. It does not, because foreign keys are not enforced while a
         * migration runs. Room turns enforcement on only after the database has opened and every
         * migration has finished. Room's own generated migrations rely on the same behaviour.
         *
         * **The SQL matches Room's exactly.** The `CREATE TABLE` repeats what Room generates from
         * [User] (column order, types and `NOT NULL`s), and the index is recreated under the name
         * Room gives it. Room checks the file against the entity every time it opens the database,
         * so any difference would stop the app from starting.
         *
         * The old password hashes are simply not copied. Existing local accounts cannot log in after
         * this anyway, because Firebase has never heard of them. They have to be registered again.
         */
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

        /**
         * Version 10 to 11: adds two-factor authentication — the authenticator secret on the `users`
         * table, and the `recovery_codes` table beside it.
         *
         * **Nothing existing is touched, so no reinstall is needed.** Two ADD COLUMNs and a CREATE
         * TABLE: every pet, task, expense, place and account on the device survives untouched, and
         * every account simply starts with two-factor authentication off — which is exactly what was
         * true of them before this version.
         *
         * **Why both new columns are nullable with no default.** That is how Room maps `String?` and
         * `Long?`, and null is the truthful value: an account that has never set up an authenticator
         * has no secret and was never switched on. `twoFactorSecret` being null *is* how the app
         * knows two-factor authentication is off (see [User]), so the accounts already on the device
         * arrive in the off state without a single UPDATE.
         *
         * **The `recovery_codes` CREATE reproduces exactly what Room generates from [RecoveryCode]** —
         * the same column types, `NOT NULL` on precisely the non-nullable properties, the foreign key
         * with its CASCADE rule, and the index under the name Room gives it
         * (`index_<table>_<columns>`). As on [MIGRATION_3_4] and [MIGRATION_5_6], Room compares the
         * file against the entities every time it opens the database and refuses to start on any
         * difference, so "close enough" here would be a crash on launch.
         *
         * Nothing is marked pending sync and nothing is added to the Firestore sync: an authenticator
         * secret is a fact about *this device* — see the note on [User.twoFactorSecret] about what
         * keeping it here does and does not protect — and uploading it would both defeat that and
         * carry the protection onto a handset the user may not be holding.
         */
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

        /**
         * Version 11 to 12: adds the `vaccination_records` table behind the pet detail screen's
         * vaccination history card, and seeds it from the pets already on the device.
         *
         * **Nothing existing is touched.** One CREATE TABLE and a handful of INSERTs into it: every
         * pet, task, expense, place and account survives untouched, exactly as in [MIGRATION_10_11].
         *
         * As on [MIGRATION_5_6] and [MIGRATION_7_8], the CREATE reproduces precisely what Room
         * generates from [VaccinationRecord] — the same column types, `NOT NULL` on exactly the
         * non-nullable properties, the foreign key with its CASCADE rule, and the index under the
         * name Room gives it (`index_<table>_<columns>`). Room compares the file against the
         * entities every time it opens the database and refuses to start on any difference, so
         * "close enough" here is a crash on launch rather than a cosmetic mismatch.
         *
         * **Why the table is seeded at all.** An empty history is a truthful but useless first
         * impression: the card's whole point is the *range* of states a due date can be in, and
         * with no rows neither the colours nor the status rule can be seen working. The seeds below
         * are real, plausible Nepali-clinic records that between them land on overdue, due within
         * the month and comfortably up to date — see
         * [com.prash.petcare.ui.pet.VaccinationStatus] for the rule they exercise.
         *
         * **Why they are attached by species rather than to every pet.** A dog has no business
         * carrying an FVRCP record and a cat none carrying DHPP, so each INSERT selects only the
         * pets it makes sense for. `SELECT id … FROM pets WHERE species = 'CAT'` also means a
         * household with three cats gets the history on all three, and one with none gets nothing
         * rather than a row pointing at no pet. The species is matched against the enum constant's
         * *name*, because that is the text [converter.Converters] writes into the column.
         *
         * **A device with no pets yet is seeded with nothing, and that is correct.** A fresh install
         * creates this table through Room rather than through this migration, and has no pets for a
         * record to belong to; the card shows its empty state until the owner records a shot. There
         * is no way to seed history for an animal that does not exist.
         *
         * The dates are written as ISO days and converted in SQL: `strftime('%s', '2025-09-22')`
         * gives the seconds at **midnight UTC** on that day, which is the convention every calendar
         * date in this app is stored in (see [VaccinationRecord]), and the `* 1000` makes it the
         * milliseconds Room reads back into a `Long`. Writing the raw epoch numbers instead would
         * work identically and be impossible to check by eye.
         */
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

        /**
         * One seeded vaccination, as the dates read on a vet's card.
         *
         * A tiny private holder rather than four positional values in a list, so the seed table
         * below can be read as a record of what was given and when, and a typo in it is visible
         * rather than hidden in argument order.
         */
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

        /**
         * Writes one species' seed records against every pet of that species.
         *
         * The INSERT … SELECT does the join in SQLite rather than reading the pets into Kotlin
         * first: it is one statement per record whatever the household holds, and it cannot leave a
         * row pointing at a pet id that was not actually there.
         *
         * Values are bound as `?` parameters rather than concatenated into the SQL. Nothing here is
         * user input, so this is not about injection — it is that a clinic name containing an
         * apostrophe ("Bhat-Bhateni's") would otherwise end the string literal early and make the
         * statement fail to parse.
         */
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
