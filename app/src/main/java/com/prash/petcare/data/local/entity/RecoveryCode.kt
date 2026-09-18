package com.prash.petcare.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One single-use recovery code for an account with two-factor authentication turned on.
 *
 * **Why these exist at all.** The authenticator secret lives on one phone. Lose that phone, wipe it,
 * or delete the authenticator app, and the six-digit code can never be produced again — which, with
 * no way round it, would mean a permanently locked account. Ten codes written down at setup are that
 * way round. They are not an optional extra on top of two-factor authentication; they are what makes
 * turning it on a safe thing to do.
 *
 * **Why the code itself is not stored.** A recovery code is a credential: anything that can read this
 * table could otherwise sign in as the user. Only [codeHash] is kept — the result of running the code
 * through a one-way hash — so the table can *check* a code someone types without being able to
 * produce one. This mirrors how [User] deliberately holds no password.
 *
 * **Why each row carries its own [salt].** Without one, the same code would always hash to the same
 * value, so identical codes across accounts would be visibly identical, and an attacker could
 * pre-compute the hashes of all one million six-digit codes once and look them up. A random salt per
 * row means that table would have to be rebuilt for every single row, which is the whole point of
 * salting. See [com.prash.petcare.data.auth.RecoveryCodes] for the hashing itself, and for why a fast
 * hash is the right choice for a code the *app* generated rather than one a person chose.
 *
 * **Why a used code is kept rather than deleted.** "You have already used that code" and "that is not
 * one of your codes" are different things for the user to be told, and only a row that survives being
 * used can tell them apart. [usedAt] is what marks a code spent; nothing ever accepts a row whose
 * [usedAt] is not null.
 *
 * @property id Primary key, assigned by SQLite on insert.
 * @property userId The account these codes belong to. The foreign key below cascades, so deleting an
 *   account takes its codes with it — there is no path by which a deleted user's codes outlive them.
 * @property salt Random bytes, Base64-encoded, mixed into the code before hashing. Not a secret: it
 *   is stored in the clear beside the hash, exactly as intended.
 * @property codeHash The hash of salt + code, Base64-encoded. The only record of what the code was.
 * @property createdAt When this set of codes was generated, as epoch milliseconds — the same unit
 *   every other time in this app uses.
 * @property usedAt When the code was spent, or null while it is still usable.
 */
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
    // Every query here is "the codes for this account", so the column those queries filter on is
    // indexed. Not unique: an account has ten of these rows by design.
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
