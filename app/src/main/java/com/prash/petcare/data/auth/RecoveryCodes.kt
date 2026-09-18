package com.prash.petcare.data.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Makes and checks the single-use recovery codes that stand in for an authenticator code.
 *
 * Pure functions on strings with no Android view types and no database access, so the rules are
 * unit-testable on their own; [com.prash.petcare.data.repository.TwoFactorRepository] is what puts
 * the results in the database. It lives in the data layer beside [TotpAuthenticator] because it
 * produces and verifies credentials, which is that layer's job.
 *
 * ## Why the codes are six digits
 *
 * They are typed on the app's existing on-screen keypad ([com.prash.petcare.ui.auth.CodeKeypadView]),
 * the same component the two-factor screen uses — so a recovery code has to be something that keypad
 * can produce: six digits, and nothing else. That is a real trade against the sixteen-character
 * alphanumeric codes a web service would hand out, and it is worth being honest about the size of it.
 *
 * A six-digit code is one chance in a million per guess, and there are [COUNT] of them live at once,
 * so a single guess has roughly a one-in-a-hundred-thousand chance. What makes that acceptable here
 * rather than reckless is what surrounds it: entry goes through the same failed-attempt lockout that
 * password sign-in uses ([com.prash.petcare.data.local.LoginAttemptStore] — five tries, then fifteen
 * minutes), each code dies the moment it is used, and the attacker needs the account's password
 * before this screen appears at all. At five guesses a quarter-hour, working through even a
 * thousandth of the space takes years.
 *
 * ## Why a fast hash, when passwords need a slow one
 *
 * Password hashes are deliberately slow (bcrypt, PBKDF2 with many iterations) because people choose
 * passwords from a small, predictable set, so the only defence is making each of an attacker's
 * millions of guesses expensive. That reasoning does not transfer: these codes are not chosen by
 * anyone, they are drawn from [SecureRandom], and there are exactly one million possibilities whether
 * the hash is fast or slow. Someone who has stolen the database file can enumerate all million
 * against a fast hash *or* a slow one — a hundredfold slowdown does not save a space that small.
 *
 * So the hash here is doing the job it can actually do: stopping a casual read of the database from
 * handing over working codes, and — through the per-code salt — stopping one pre-computed table from
 * cracking every account at once. What protects against an attacker who already has the database file
 * is not this function; it is that they would also need the password, and that Android keeps one
 * app's files away from other apps in the first place.
 *
 * SHA-256 over `salt + code`, both sides Base64-encoded for storage in a TEXT column.
 */
object RecoveryCodes {

    /** How many codes a set contains. The setup screen draws exactly this many. */
    const val COUNT = 10

    /**
     * How many digits each code has — the same as the number of boxes on the keypad that types them,
     * for the reason in the class note.
     */
    const val LENGTH = TotpAuthenticator.CODE_DIGITS

    /**
     * One shared [SecureRandom].
     *
     * `SecureRandom` rather than `Random`: an ordinary Random is a predictable arithmetic sequence
     * from a seed, so someone who saw one code could work out the rest of the set. SecureRandom draws
     * from the operating system's entropy pool. One instance rather than one per call, because
     * constructing it is the expensive part and it is thread-safe.
     */
    private val secureRandom = SecureRandom()

    /**
     * Generates a fresh set of [COUNT] codes, as plain text.
     *
     * **This is the only moment they exist in readable form.** The caller shows them to the user once
     * and stores only their hashes; there is no way back to them afterwards, which is the entire
     * reason the setup flow insists the user writes them down before it finishes.
     *
     * Duplicates within a set are filtered out — two identical codes would silently make the set nine
     * strong instead of ten — by generating until [COUNT] distinct codes exist. With a million
     * possibilities and ten draws a collision is vanishingly unlikely, so this loop effectively never
     * runs twice; it is here because "effectively never" is not the same as never.
     */
    fun generate(): List<String> {
        val codes = LinkedHashSet<String>(COUNT)
        while (codes.size < COUNT) {
            codes += randomCode()
        }
        return codes.toList()
    }

    /**
     * A random salt for one code, Base64-encoded.
     *
     * Sixteen bytes is the usual size: long enough that no two rows will ever share one, which is all
     * a salt has to achieve. It is not a secret and is stored in the clear beside the hash.
     */
    fun newSalt(): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    /**
     * Hashes [code] with [salt], producing the value stored in
     * [com.prash.petcare.data.local.entity.RecoveryCode.codeHash].
     *
     * `Base64.NO_WRAP` on both sides, because the default inserts newlines every 76 characters, which
     * would put line breaks inside a database column for no reason.
     */
    fun hash(code: String, salt: String): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        digest.update(Base64.decode(salt, Base64.NO_WRAP))
        digest.update(code.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    /**
     * Whether [code] is the code that produced [expectedHash] under [salt].
     *
     * [MessageDigest.isEqual] rather than `==`, because it compares the whole array every time rather
     * than stopping at the first differing byte. A comparison that returns sooner for a nearly-right
     * value leaks, in how long it took, how much of the value was right — which is the kind of thing
     * that is free to avoid and awkward to retrofit.
     */
    fun matches(code: String, salt: String, expectedHash: String): Boolean = MessageDigest.isEqual(
        Base64.decode(hash(code, salt), Base64.NO_WRAP),
        Base64.decode(expectedHash, Base64.NO_WRAP)
    )

    /**
     * True when [code] is shaped like a recovery code at all — [LENGTH] digits.
     *
     * Checked before any hashing, so obviously wrong input is refused without work, and so the
     * "already used" and "not one of your codes" messages are only ever reached by something that
     * could plausibly have been a code.
     */
    fun isWellFormed(code: String): Boolean = code.length == LENGTH && code.all(Char::isDigit)

    /**
     * One random code, left-padded so that a draw such as 4,821 is still six digits long.
     *
     * The padding matters: without it, low numbers would produce short codes the keypad could not
     * finish, and the set would quietly be weaker than it looks.
     */
    private fun randomCode(): String {
        val value = secureRandom.nextInt(CODE_SPACE)
        return value.toString().padStart(LENGTH, '0')
    }

    /** Ten to the power of [LENGTH]: how many distinct codes there are. */
    private const val CODE_SPACE = 1_000_000

    private const val SALT_BYTES = 16

    /**
     * SHA-256. See the class note on why a deliberately fast hash is the right choice for a value
     * this app generated at random, rather than one a person chose.
     */
    private const val HASH_ALGORITHM = "SHA-256"
}
