package com.prash.petcare.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** A person who uses the app and owns one or more pets. */
@Entity(
    tableName = "users",
    // Unique index: enforces one profile per email.
    indices = [Index(value = ["email"], unique = true)]
)
data class User(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val fullName: String,
    val email: String,
    val phone: String? = null,
    val role: UserRole = UserRole.OWNER,
    val passwordChangedAt: Long? = null,
    // Named "photoUri" in SQLite, matching Pet's identical column.
    @ColumnInfo(name = "photoUri")
    val photoPath: String? = null,
    val twoFactorSecret: String? = null,
    val twoFactorEnabledAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
