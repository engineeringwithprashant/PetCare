package com.prash.petcare.util

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Turns a plain-text password into a value that is safe to store, and checks a typed password
 * against a stored one.
 *
 * **Why passwords are never stored as typed.** If the database file were ever read — a stolen
 * phone, a backup, a shared emulator image — plain-text passwords would hand over every account
 * immediately, including accounts the person reused that password on elsewhere.
 *
 * **Why a plain hash is not enough either.** Running SHA-256 over a password is fast, and fast is
 * the problem: an attacker can test billions of guesses per second, and two users with the same
 * password would produce identical hashes, which is visible at a glance.
 *
 * This class therefore uses PBKDF2, which addresses both issues:
 *  - a random **salt** per password means identical passwords hash differently, and a pre-computed
 *    lookup table ("rainbow table") is useless;
 *  - a high **iteration count** makes each guess deliberately slow, so brute-forcing becomes
 *    impractical while a single login check stays fast enough not to be noticed.
 *
 * The salt and iteration count are not secrets — they are stored alongside the hash, because
 * verifying a password later requires repeating the exact same computation.
 */
object PasswordHasher {

    /**
     * PBKDF2 with HMAC-SHA1.
     *
     * SHA-1 is a weak choice for a plain digest, but PBKDF2's security here comes from the salt
     * and iteration count rather than the underlying hash, so this remains a sound construction.
     * The stronger `PBKDF2WithHmacSHA256` is only guaranteed from API 26 onwards, and this app
     * supports minSdk 24.
     */
    private const val ALGORITHM = "PBKDF2WithHmacSHA1"

    /**
     * How many times the hash is applied. Higher is safer but slower; this value is a deliberate
     * balance that keeps a login check to a fraction of a second on a mid-range phone while still
     * making mass guessing expensive.
     */
    private const val ITERATIONS = 100_000

    /** Length of the derived key in bits. */
    private const val KEY_LENGTH_BITS = 256

    /** 16 random bytes is the usual salt size — long enough never to repeat in practice. */
    private const val SALT_LENGTH_BYTES = 16

    /** Separates the three stored parts. Colon is safe: Base64 output never contains one. */
    private const val SEPARATOR = ":"

    /**
     * Hashes [password] and returns a single string safe to put in
     * [com.prash.petcare.data.local.entity.User.passwordHash].
     *
     * The result has the form `iterations:salt:hash`, with salt and hash Base64-encoded. Packing
     * all three together means a future change to [ITERATIONS] will not invalidate existing
     * accounts — each stored value remembers the settings it was created with.
     *
     * This is CPU-bound work by design, so call it from a background coroutine, never the main
     * thread.
     */
    fun hash(password: String): String {
        // A fresh salt for every password — this is what stops two identical passwords from
        // producing identical stored values.
        val salt = ByteArray(SALT_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt, ITERATIONS)

        return listOf(
            ITERATIONS.toString(),
            salt.toBase64(),
            hash.toBase64()
        ).joinToString(SEPARATOR)
    }

    /**
     * Checks a typed [password] against a [storedHash] previously produced by [hash].
     *
     * Returns false rather than throwing if the stored value is malformed, so a corrupted row
     * fails the login instead of crashing the app.
     */
    fun verify(password: String, storedHash: String): Boolean {
        val parts = storedHash.split(SEPARATOR)
        if (parts.size != 3) return false

        val iterations = parts[0].toIntOrNull() ?: return false
        val salt = parts[1].fromBase64() ?: return false
        val expectedHash = parts[2].fromBase64() ?: return false

        // Re-run the identical computation using the salt and iteration count that were stored
        // with the original hash, then compare the results.
        val actualHash = derive(password, salt, iterations)

        // MessageDigest.isEqual compares in constant time — it always examines every byte instead
        // of stopping at the first difference. A comparison that returned early would take
        // measurably longer the more of the hash an attacker guessed correctly, leaking the
        // answer one byte at a time.
        return MessageDigest.isEqual(expectedHash, actualHash)
    }

    /** Runs the PBKDF2 key derivation itself. */
    private fun derive(password: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).encoded
        } finally {
            // Wipes the password copy held inside the spec as soon as it is no longer needed,
            // shortening the window in which it sits in memory.
            spec.clearPassword()
        }
    }

    /** NO_WRAP keeps the output on one line, so it never breaks the `:` separated format. */
    private fun ByteArray.toBase64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.fromBase64(): ByteArray? =
        try {
            Base64.decode(this, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            null
        }
}
