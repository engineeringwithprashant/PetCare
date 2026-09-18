package com.prash.petcare.data.local.entity

import androidx.room.ColumnInfo
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
 * **No password here.** Firebase Authentication stores and checks the password (see
 * [com.prash.petcare.data.remote.FirebaseAuthService]). This row is the account's *profile* on this
 * device, and the owner that every other record points to. Not keeping a credential on the device
 * means there is nothing to steal from the database file.
 *
 * @property email The login identifier, and the link between this row and its Firebase account.
 *   The unique index declared below makes the database itself reject a duplicate address, so two
 *   profiles can never share one email, even if two logins race.
 * @property phone Optional contact number, shown on the profile screen. Nullable because every
 *   account created before this column existed has none, and because the app never requires one —
 *   nothing breaks without it. It is the user's *own* number, kept so the profile can display it;
 *   delegation still sends to a recipient chosen per message, never to this.
 * @property role How the user describes their part in the pets' care — see [UserRole]. Non-null with
 *   a default, because an account always plays *some* part, and [UserRole.OWNER] is the truthful
 *   answer for every account that existed before the app asked.
 * @property passwordChangedAt When the password was last changed, as epoch milliseconds, or null if
 *   it has never been changed since the account was made. Deliberately *not* defaulted to
 *   [createdAt]: "never changed" and "changed the day you signed up" are different facts, and the
 *   profile screen says so rather than implying a change that never happened.
 * @property photoPath Optional absolute path to a profile photo copied into the app's internal
 *   storage, exactly as [Pet.photoPath] works and for the same reason — see
 *   [com.prash.petcare.util.PetPhotoStorage] on why a picked image is copied rather than referenced.
 * @property twoFactorSecret The account's authenticator (TOTP) secret, Base32-encoded, or null when
 *   two-factor authentication is off. Null *is* the "off" switch: there is no separate boolean that
 *   could disagree with it, so an account can never be marked protected while holding no secret to
 *   check a code against.
 *
 * **What keeping this on the device does and does not protect against.** The secret is what turns
 * the clock into the six-digit code, so whoever holds it can produce codes. It lives here, in this
 * app's own database file, which means two-factor authentication in PetCare protects against
 * **someone who has learned the password but does not have this phone** — a reused password, a
 * leaked one, someone watching it being typed. It is **not** protection against someone holding the
 * unlocked handset: they have both the password prompt and the secret in front of them. That second
 * threat is the phone's own lock screen's job, not this app's. A service that checked codes on a
 * server would keep the secret away from the client and cover more, but Firebase's multi-factor
 * support requires the paid Identity Platform upgrade, so on-device is deliberately where this sits.
 *
 * @property twoFactorEnabledAt When two-factor authentication was switched on, as epoch
 *   milliseconds, or null when it is off. Kept beside the secret rather than derived from it because
 *   "protected since March" is a thing the profile screen can say and a secret alone cannot.
 * @property createdAt When the account was created, as epoch milliseconds.
 */
@Entity(
    tableName = "users",
    // Unique index: enforces one profile per email, and doubles as a fast lookup for the query
    // that matches a Firebase login to its profile by email.
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
    // Named "photoUri" in SQLite, matching Pet's identical column. The property says what the value
    // really is — an absolute file path — while the column keeps the name Pet already established,
    // so the two tables describe the same kind of thing the same way.
    @ColumnInfo(name = "photoUri")
    val photoPath: String? = null,
    val twoFactorSecret: String? = null,
    val twoFactorEnabledAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
