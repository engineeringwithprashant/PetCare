package com.prash.petcare.data.auth

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Makes and checks the single-use recovery codes that stand in for an authenticator code. */
object RecoveryCodes {

    /** How many codes a set contains. The setup screen draws exactly this many. */
    const val COUNT = 10

    /** How many digits each code has — the same as the number of boxes on the keypad that types them. */
    const val LENGTH = TotpAuthenticator.CODE_DIGITS

    /** One shared [SecureRandom]. */
    private val secureRandom = SecureRandom()

    /** Generates a fresh set of [COUNT] codes, as plain text. */
    fun generate(): List<String> {
        val codes = LinkedHashSet<String>(COUNT)
        while (codes.size < COUNT) {
            codes += randomCode()
        }
        return codes.toList()
    }

    /** A random salt for one code, Base64-encoded. */
    fun newSalt(): String {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    /** Hashes [code] with [salt]. */
    fun hash(code: String, salt: String): String {
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        digest.update(Base64.decode(salt, Base64.NO_WRAP))
        digest.update(code.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    /** Whether [code] is the code that produced [expectedHash] under [salt]. */
    fun matches(code: String, salt: String, expectedHash: String): Boolean = MessageDigest.isEqual(
        Base64.decode(hash(code, salt), Base64.NO_WRAP),
        Base64.decode(expectedHash, Base64.NO_WRAP)
    )

    /** True when [code] is shaped like a recovery code at all — [LENGTH] digits. */
    fun isWellFormed(code: String): Boolean = code.length == LENGTH && code.all(Char::isDigit)

    /** One random code, left-padded so that a draw such as 4,821 is still six digits long. */
    private fun randomCode(): String {
        val value = secureRandom.nextInt(CODE_SPACE)
        return value.toString().padStart(LENGTH, '0')
    }

    /** Ten to the power of [LENGTH]: how many distinct codes there are. */
    private const val CODE_SPACE = 1_000_000

    private const val SALT_BYTES = 16

    /** SHA-256. See the class note on why a deliberately fast hash is the right choice for a value this app generated at random. */
    private const val HASH_ALGORITHM = "SHA-256"
}
