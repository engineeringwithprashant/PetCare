package com.prash.petcare.data.repository

import com.prash.petcare.data.auth.RecoveryCodes
import com.prash.petcare.data.auth.TotpAuthenticator
import com.prash.petcare.data.local.dao.RecoveryCodeDao
import com.prash.petcare.data.local.dao.UserDao
import com.prash.petcare.data.local.entity.RecoveryCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Everything the app knows about an account's second factor: whether it is on, setting it up,
 * checking a code at sign-in, and turning it off again.
 *
 * The **Repository** in the MVVM chain, and the only class that joins the three pieces this feature
 * is made of — the TOTP algorithm ([TotpAuthenticator]), the recovery codes ([RecoveryCodes]), and
 * the two tables they are stored in. ViewModels call this and never see a DAO, a Base32 secret or a
 * hash; screens never see even this.
 *
 * ## What this protects against, and what it does not
 *
 * The authenticator secret is stored in this app's own Room database, on the handset. That is a
 * deliberate choice with a clear boundary, and the boundary is worth stating plainly rather than
 * leaving for someone to discover:
 *
 *  - **It does protect against someone who knows the password but does not have this phone.** A
 *    password reused from a site that leaked, one shoulder-surfed at a desk, one guessed — none of
 *    them gets past the code screen, because producing the code needs the secret that was scanned
 *    into the user's authenticator app.
 *  - **It does not protect against someone holding the unlocked device.** They have the password
 *    prompt and the secret in the same place, and the recovery codes' hashes alongside. Guarding
 *    against that is the phone's own lock screen, not this app.
 *
 * Checking codes on a server would move the secret off the client and cover the second case too, but
 * Firebase's multi-factor authentication needs the paid Identity Platform upgrade, which this project
 * does not have. So this is on-device by design, and is honest about its reach rather than implying
 * more. Nothing here is uploaded by the Firestore sync either — see
 * [com.prash.petcare.data.sync.SyncManager] — so the protection stays attached to the device that was
 * set up, which is the only place it means anything.
 *
 * @property userDao Holds the secret, on the account's own row. Two-factor authentication is a
 *   property of an account, so it lives with the account rather than in a table of its own.
 * @property recoveryCodeDao The ten single-use codes, which do need a table: there are many per
 *   account and each has its own state.
 * @property totp The algorithm. Injected rather than constructed here so a test can hand in a
 *   [TotpAuthenticator] with a fixed clock.
 */
class TwoFactorRepository(
    private val userDao: UserDao,
    private val recoveryCodeDao: RecoveryCodeDao,
    private val totp: TotpAuthenticator = TotpAuthenticator()
) {

    /**
     * Whether this account is protected, and how many recovery codes it has left, as a live value.
     *
     * A [Flow] combining the two tables, so the profile screen's row redraws by itself — the moment a
     * recovery code is spent at sign-in, or the switch is turned off, the subtitle is already right
     * with nothing to refresh. [combine] re-emits when either side changes, which is what makes "On ·
     * 9 recovery codes left" a single fact rather than two that could disagree.
     */
    fun observeStatus(userId: Long): Flow<TwoFactorStatus> = combine(
        userDao.observeById(userId).map { it?.twoFactorSecret != null },
        recoveryCodeDao.observeUnusedCount(userId)
    ) { isEnabled, remaining ->
        // A disabled account reports no codes even if rows somehow survived, so the pair can never
        // describe a state the app does not have — "off, with 4 codes left" is not a thing.
        TwoFactorStatus(
            isEnabled = isEnabled,
            recoveryCodesRemaining = if (isEnabled) remaining else 0
        )
    }

    /**
     * Whether [userId] must pass the code screen after its password.
     *
     * A one-shot read rather than the flow above, because the sign-in path asks the question once and
     * acts on the answer; observing it would mean a change mid-sign-in could rewrite a decision
     * already taken.
     */
    suspend fun isEnabled(userId: Long): Boolean =
        userDao.getById(userId)?.twoFactorSecret != null

    /**
     * Starts a setup: a brand-new secret, and the `otpauth://` URI for the QR code beside it.
     *
     * **Nothing is written to the database here**, and that is the point. The secret exists only in
     * the setup screen's state until a code generated from it has been typed back correctly, so a
     * scan that silently failed — a blurred camera, the wrong app, a mistyped manual entry — leaves
     * the account exactly as it was rather than protected by a secret the user does not actually
     * hold. Abandoning the screen at any point before that needs no cleanup, because there is nothing
     * to clean up.
     *
     * @return The new secret and its URI, or null if the account has gone (deleted on another device
     *   and pulled down by a sync while the screen was open).
     */
    suspend fun beginSetup(userId: Long): TwoFactorSetup? {
        val user = userDao.getById(userId) ?: return null
        val secret = totp.newSecret()
        return TwoFactorSetup(
            secret = secret,
            otpAuthUri = totp.otpAuthUri(secret, user.email),
            accountEmail = user.email
        )
    }

    /**
     * The QR code's `otpauth://` URI for a secret that already exists.
     *
     * Needed because the setup screen keeps only the secret across the process being killed, and
     * rebuilds everything else from it — see [com.prash.petcare.ui.auth.twofactor.TwoFactorSetupViewModel].
     * Saving the URI alongside would be a second copy of the same fact, and two copies of one fact
     * can be restored out of step.
     */
    fun otpAuthUriFor(secret: String, accountEmail: String): String =
        totp.otpAuthUri(secret, accountEmail)

    /**
     * Whether [code] is a valid authenticator code for [secret] — the confirmation step of setup.
     *
     * Takes the secret as an argument rather than reading it from the account, precisely because at
     * this point it is *not* on the account yet.
     */
    fun isSetupCodeValid(secret: String, code: String): Boolean = totp.isCodeValid(secret, code)

    /** A fresh set of recovery codes, as plain text. Generated but not stored; see [enable]. */
    fun generateRecoveryCodes(): List<String> = RecoveryCodes.generate()

    /**
     * Turns two-factor authentication on: stores [secret] on the account and the hashes of
     * [recoveryCodes] beside it.
     *
     * Called at the *end* of setup — after a correct code has proved the authenticator is working,
     * and after the user has confirmed they have saved the codes. Doing both writes in one call is
     * what stops the two halves ever existing apart: an account with a secret but no recovery codes
     * is one lost phone away from being locked for good, and codes with no secret are ten numbers
     * that open nothing.
     *
     * Any codes left over from a previous setup are cleared first. An old printed list must not
     * survive into a new set — the user believes the sheet they are holding now is the whole story.
     *
     * @return false if the account has gone, in which case nothing was written.
     */
    suspend fun enable(userId: Long, secret: String, recoveryCodes: List<String>): Boolean {
        val user = userDao.getById(userId) ?: return false
        val now = System.currentTimeMillis()

        recoveryCodeDao.deleteAllForUser(userId)
        recoveryCodeDao.insertAll(
            recoveryCodes.map { code ->
                // A salt per code, not per account: see RecoveryCode for why that is what makes a
                // pre-computed table useless.
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

    /**
     * Turns two-factor authentication off: clears the secret and every remaining code.
     *
     * The caller must have checked a current code or a recovery code first — the profile screen does,
     * through [verify]. This method deliberately does not check one itself: it is the *write*, and
     * mixing a permission check into it would mean two places (here, and whichever screen also
     * checked) could each assume the other had done it.
     *
     * Spent codes go too. They are useless, but they are also a record of how the account was
     * recovered, and there is no reason to keep it once the feature is off.
     */
    suspend fun disable(userId: Long): Boolean {
        val user = userDao.getById(userId) ?: return false
        recoveryCodeDao.deleteAllForUser(userId)
        userDao.update(user.copy(twoFactorSecret = null, twoFactorEnabledAt = null))
        return true
    }

    /**
     * Checks a code typed at sign-in, or on the way to turning two-factor authentication off.
     *
     * **The order matters.** An authenticator code is tried first, because that is what all but one
     * sign-in in a hundred will be, and because a recovery code is *spent* by matching — trying those
     * first would burn one of the ten every time someone typed their ordinary code with a digit
     * wrong. Only once TOTP has said no is the typed value looked for among the recovery codes.
     *
     * A recovery code that matches is consumed here, in the same call that accepts it, through a
     * conditional UPDATE — see [RecoveryCodeDao.markUsed] for why the single-use rule is enforced by
     * the database rather than by an `if` in Kotlin.
     *
     * **Why "already used" is a separate answer.** Someone working down a printed list will cross off
     * the wrong line sooner or later, and "you have already used that one, try the next" sends them
     * somewhere useful, where "that code is not right" sends them to check a list that is perfectly
     * correct.
     *
     * @param acceptRecoveryCodes False on screens where only an authenticator code makes sense. True
     *   everywhere a lost phone is the case being handled.
     */
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

    /**
     * Looks for [code] among the account's recovery codes and spends it if it is there.
     *
     * Every row is read and re-hashed against its own salt, because a salted hash cannot be looked up
     * with a `WHERE` — see [RecoveryCodeDao.getAllForUser]. Ten rows makes that a non-question.
     *
     * The search covers spent codes as well as live ones, which is the only way to tell "you have
     * used that one" from "that is not yours".
     */
    private suspend fun consumeRecoveryCode(userId: Long, code: String): TwoFactorCheck {
        val match = recoveryCodeDao.getAllForUser(userId)
            .firstOrNull { RecoveryCodes.matches(code, it.salt, it.codeHash) }
            ?: return TwoFactorCheck.Incorrect

        if (match.usedAt != null) return TwoFactorCheck.RecoveryCodeAlreadyUsed

        // The UPDATE only lands if the row is still unused, so two sign-ins racing on the same code
        // cannot both be let in. A return of 0 means the other one got there first.
        val spent = recoveryCodeDao.markUsed(match.id, System.currentTimeMillis()) > 0
        if (!spent) return TwoFactorCheck.RecoveryCodeAlreadyUsed

        val remaining = recoveryCodeDao.getAllForUser(userId).count { it.usedAt == null }
        return TwoFactorCheck.RecoveryCodeAccepted(remaining)
    }
}

/**
 * Whether an account is protected, and how much of its recovery is left.
 *
 * @property isEnabled True when the account holds an authenticator secret.
 * @property recoveryCodesRemaining How many of the [RecoveryCodes.COUNT] codes are still unspent.
 *   Worth surfacing rather than hiding: an account down to its last code is one bad day from being
 *   locked out, and the only way the user finds that out is if the app says so.
 */
data class TwoFactorStatus(
    val isEnabled: Boolean = false,
    val recoveryCodesRemaining: Int = 0
)

/**
 * A setup in progress — a secret that exists but is not yet stored anywhere.
 *
 * @property secret The Base32 secret, shown as text under the QR code for anyone who would rather
 *   type it in than scan.
 * @property otpAuthUri What the QR code encodes.
 * @property accountEmail The address the authenticator will file the entry under, shown on screen so
 *   the user can see which account they are protecting.
 */
data class TwoFactorSetup(
    val secret: String,
    val otpAuthUri: String,
    val accountEmail: String
)

/**
 * The outcome of checking a code.
 *
 * A sealed type rather than a Boolean, because every branch of it leads to a different message and
 * one of them carries a number. A true/false could not distinguish a wrong code from a used one, and
 * the difference between those two messages is the difference between a user who knows what to do
 * next and one who does not.
 */
sealed interface TwoFactorCheck {

    /** The authenticator code was right. */
    data object Correct : TwoFactorCheck

    /**
     * A recovery code was right, and has now been spent.
     *
     * @property remaining How many are left, so the screen can say so — this is the moment the user
     *   is most able to act on "you have two left".
     */
    data class RecoveryCodeAccepted(val remaining: Int) : TwoFactorCheck

    /** The code is one of this account's recovery codes, but it has already been used once. */
    data object RecoveryCodeAlreadyUsed : TwoFactorCheck

    /** Not a current authenticator code and not one of this account's recovery codes. */
    data object Incorrect : TwoFactorCheck

    /** The account has no second factor set up, so there was nothing to check against. */
    data object NotEnabled : TwoFactorCheck
}
