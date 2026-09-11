package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A person who uses the app and owns one or more pets.
 *
 * This is the root of the data model: every other record hangs off a User, either directly
 * (a [Pet]) or indirectly (a [CareTask] or [Expense] belongs to a Pet, which belongs to a User).
 *
 * Dates throughout this app are stored as `Long` epoch milliseconds rather than `java.time`
 * types, because the project targets minSdk 24 and `java.time` only became available on
 * API 26 without extra library desugaring.
 *
 * @property id Primary key. `autoGenerate = true` means SQLite assigns the value, so new
 *   objects are built with the default 0 and receive their real id on insert.
 * @property fullName Display name shown in the app's profile area.
 * @property email Login identifier. The unique index declared below makes the database itself
 *   reject a duplicate address, so two accounts can never share one email even if a bug in the
 *   sign-up screen submits the same form twice.
 * @property passwordHash The *hashed* password, never the plain text the user typed. Storing a
 *   raw password would expose the account to anyone who could read the device's database file.
 * @property createdAt When the account was created, as epoch milliseconds.
 */
@Entity(
    tableName = "users",
    // Unique index: enforces one account per email, and doubles as a fast lookup for the
    // login query, which searches by email.
    indices = [Index(value = ["email"], unique = true)]
)
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fullName: String,
    val email: String,
    val passwordHash: String,
    val createdAt: Long = System.currentTimeMillis()
)
