package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One single-use recovery code for an account with two-factor authentication turned on. */
@Entity(
    tableName = "recovery_codes",
    foreignKeys = [
        ForeignKey(
            entity = User::class,
            parentColumns = ["id"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    // Every query here is "the codes for this account", so the column those queries filter on is indexed.
    indices = [Index(value = ["userId"])]
)
data class RecoveryCode(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val userId: Long,
    val salt: String,
    val codeHash: String,
    val createdAt: Long = System.currentTimeMillis(),
    val usedAt: Long? = null
)
