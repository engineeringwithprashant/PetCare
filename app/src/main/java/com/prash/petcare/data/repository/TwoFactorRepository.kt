package com.prash.petcare.data.repository

import com.prash.petcare.data.auth.RecoveryCodes
import com.prash.petcare.data.auth.TotpAuthenticator
import com.prash.petcare.data.local.dao.RecoveryCodeDao
import com.prash.petcare.data.local.dao.UserDao
import com.prash.petcare.data.local.entity.RecoveryCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Everything the app knows about an account's second factor: whether it is on, setting it up. */
class TwoFactorRepository(
    private val userDao: UserDao,
    private val recoveryCodeDao: RecoveryCodeDao,
    private val totp: TotpAuthenticator = TotpAuthenticator()
) {

    /** Whether this account is protected, and how many recovery codes it has left, as a live value. */
    fun observeStatus(userId: Long): Flow<TwoFactorStatus> = combine(
        userDao.observeById(userId).map { it?.twoFactorSecret != null },
        recoveryCodeDao.observeUnusedCount(userId)
    ) { isEnabled, remaining ->
        // A disabled account reports no codes even if rows somehow survived.
        TwoFactorStatus(
            isEnabled = isEnabled,
            recoveryCodesRemaining = if (isEnabled) remaining else 0
        )
    }

    /** Whether [userId] must pass the code screen after its password. */
    suspend fun isEnabled(userId: Long): Boolean =
        userDao.getById(userId)?.twoFactorSecret != null

    /** Starts a setup: a brand-new secret, and the `otpauth://` URI for the QR code beside it. */
    suspend fun beginSetup(userId: Long): TwoFactorSetup? {
        val user = userDao.getById(userId) ?: return null
        val secret = totp.newSecret()
        return TwoFactorSetup(
            secret = secret,
            otpAuthUri = totp.otpAuthUri(secret, user.email),
            accountEmail = user.email
        )
    }

    /** The QR code's `otpauth://` URI for a secret that already exists. */
    fun otpAuthUriFor(secret: String, accountEmail: String): String =
        totp.otpAuthUri(secret, accountEmail)

    /** Whether [code] is a valid authenticator code for [secret] — the confirmation step of setup. */
    fun isSetupCodeValid(secret: String, code: String): Boolean = totp.isCodeValid(secret, code)

    /** A fresh set of recovery codes, as plain text. Generated but not stored; see [enable]. */
    fun generateRecoveryCodes(): List<String> = RecoveryCodes.generate()

    /** Turns two-factor authentication on. */
    suspend fun enable(userId: Long, secret: String, recoveryCodes: List<String>): Boolean {
        val user = userDao.getById(userId) ?: return false
        val now = System.currentTimeMillis()

        recoveryCodeDao.deleteAllForUser(userId)
        recoveryCodeDao.insertAll(
            recoveryCodes.map { code ->
                // A salt per code, not per account.
                val salt = RecoveryCodes.newSalt()
                RecoveryCode(
                    userId = userId,
                    salt = salt,
                    codeHash = RecoveryCodes.hash(code, salt),
                    createdAt = now
                )
            }
        )

        userDao.update(user.copy(twoFactorSecret = secret, twoFactorEnabledAt = now))
        return true
    }

    /** Turns two-factor authentication off: clears the secret and every remaining code. */
    suspend fun disable(userId: Long): Boolean {
        val user = userDao.getById(userId) ?: return false
        recoveryCodeDao.deleteAllForUser(userId)
        userDao.update(user.copy(twoFactorSecret = null, twoFactorEnabledAt = null))
        return true
    }

    /** Checks a code typed at sign-in, or on the way to turning two-factor authentication off. */
    suspend fun verify(
        userId: Long,
        code: String,
        acceptRecoveryCodes: Boolean = true
    ): TwoFactorCheck {
        val secret = userDao.getById(userId)?.twoFactorSecret ?: return TwoFactorCheck.NotEnabled

        if (totp.isCodeValid(secret, code)) return TwoFactorCheck.Correct

        if (!acceptRecoveryCodes || !RecoveryCodes.isWellFormed(code)) {
            return TwoFactorCheck.Incorrect
        }
        return consumeRecoveryCode(userId, code)
    }

    /** Looks for [code] among the account's recovery codes and spends it if it is there. */
    private suspend fun consumeRecoveryCode(userId: Long, code: String): TwoFactorCheck {
        val match = recoveryCodeDao.getAllForUser(userId)
            .firstOrNull { RecoveryCodes.matches(code, it.salt, it.codeHash) }
            ?: return TwoFactorCheck.Incorrect

        if (match.usedAt != null) return TwoFactorCheck.RecoveryCodeAlreadyUsed

        // The UPDATE only lands if the row is still unused.
        val spent = recoveryCodeDao.markUsed(match.id, System.currentTimeMillis()) > 0
        if (!spent) return TwoFactorCheck.RecoveryCodeAlreadyUsed

        val remaining = recoveryCodeDao.getAllForUser(userId).count { it.usedAt == null }
        return TwoFactorCheck.RecoveryCodeAccepted(remaining)
    }
}

/** Whether an account is protected, and how much of its recovery is left. */
data class TwoFactorStatus(
    val isEnabled: Boolean = false,
    val recoveryCodesRemaining: Int = 0
)

/** A setup in progress — a secret that exists but is not yet stored anywhere. */
data class TwoFactorSetup(
    val secret: String,
    val otpAuthUri: String,
    val accountEmail: String
)

/** The outcome of checking a code. */
sealed interface TwoFactorCheck {

    /** The authenticator code was right. */
    data object Correct : TwoFactorCheck

    /** A recovery code was right, and has now been spent. */
    data class RecoveryCodeAccepted(val remaining: Int) : TwoFactorCheck

    /** The code is one of this account's recovery codes, but it has already been used once. */
    data object RecoveryCodeAlreadyUsed : TwoFactorCheck

    /** Not a current authenticator code and not one of this account's recovery codes. */
    data object Incorrect : TwoFactorCheck

    /** The account has no second factor set up, so there was nothing to check against. */
    data object NotEnabled : TwoFactorCheck
}
