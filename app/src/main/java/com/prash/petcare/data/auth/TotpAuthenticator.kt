package com.prash.petcare.data.auth

import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.DefaultCodeVerifier
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.qr.QrData
import dev.samstevens.totp.secret.DefaultSecretGenerator
import dev.samstevens.totp.time.SystemTimeProvider
import dev.samstevens.totp.time.TimeProvider

/**
 * The app's one route to time-based one-time passwords (TOTP, RFC 6238) — the six-digit codes Google
 * Authenticator and every compatible app produce.
 *
 * It sits in the **data layer**, beside [FirebasePasswordResetRepository] and the Firebase services:
 * checking a code is a data-source operation, not something a screen or a ViewModel does. Only
 * [com.prash.petcare.data.repository.TwoFactorRepository] calls it.
 *
 * **Why a library and not a hand-written implementation.** TOTP is about twenty lines — HMAC-SHA1 the
 * current 30-second counter with the secret, truncate the result, take it modulo a million. It is
 * also twenty lines in which an off-by-one on the counter, a byte order, the dynamic-truncation
 * offset or the Base32 alphabet produces codes that look completely normal and simply never match
 * what the user's authenticator shows. That failure is undebuggable from inside this app, because
 * there is nothing to compare against. A library that thousands of projects already check against
 * Google Authenticator every day is the one that has had those mistakes found in it.
 *
 * **The parameters are the ones Google Authenticator assumes**, and they have to be: an authenticator
 * app reads them from the otpauth:// URI it scans, but several — Google's own included — ignore
 * anything but the defaults. So SHA-1, six digits and a thirty-second step are not choices so much as
 * the interoperable settings, and they are written into [otpAuthUri] explicitly anyway so a stricter
 * app is told rather than left to guess.
 *
 * **Clock drift.** The code depends on the current time, and the phone running PetCare and the phone
 * (or watch, or desktop) running the authenticator need not agree to the second. [ALLOWED_DRIFT_STEPS]
 * of 1 tells the verifier to accept the previous and the next thirty-second window as well as the
 * current one, so the two clocks can be up to about half a minute apart and a correct code is still
 * correct. That is the standard allowance. Widening it would mean each code stays valid for longer,
 * which is exactly the property a one-time password is trying not to have.
 *
 * @param timeProvider Where "now" comes from, in whole seconds. Injectable so a unit test can move
 *   the clock and check that a code expires — and that one step of drift either side is accepted —
 *   without waiting thirty real seconds. The default reads the device clock.
 */
class TotpAuthenticator(timeProvider: TimeProvider = SystemTimeProvider()) {

    /**
     * Makes new secrets. 32 Base32 characters, the library's default: 5 bits per character, so 160
     * bits of randomness from a [java.security.SecureRandom] — the same length as the SHA-1 HMAC key
     * the codes are derived with, and far beyond guessing.
     */
    private val secretGenerator = DefaultSecretGenerator()

    /** Turns a secret and a counter into six digits. SHA-1 and six digits are this class's defaults. */
    private val codeGenerator = DefaultCodeGenerator(HashingAlgorithm.SHA1, CODE_DIGITS)

    /**
     * Checks a typed code against a secret, allowing one time step either side.
     *
     * Both settings are made explicit rather than left to the library's defaults, so that what this
     * app accepts is readable here rather than in a dependency's source.
     */
    private val verifier = DefaultCodeVerifier(codeGenerator, timeProvider).apply {
        setTimePeriod(TIME_STEP_SECONDS)
        setAllowedTimePeriodDiscrepancy(ALLOWED_DRIFT_STEPS)
    }

    /**
     * A brand-new random secret, Base32-encoded — the string the QR code carries and the user can
     * type into their authenticator by hand.
     *
     * Note this only *creates* one. Nothing is stored until a code generated from it has been typed
     * back correctly; see [com.prash.petcare.data.repository.TwoFactorRepository.enable].
     */
    fun newSecret(): String = secretGenerator.generate()

    /**
     * True when [code] is the current code for [secret], or the one either side of it.
     *
     * Returns false rather than throwing for anything that is not a plausible code — empty, letters,
     * the wrong length — so every caller has one thing to handle. The library itself rejects those,
     * and the guard here means a malformed secret (a row hand-edited in the database, say) cannot
     * crash a sign-in either.
     */
    fun isCodeValid(secret: String, code: String): Boolean {
        if (secret.isBlank() || code.length != CODE_DIGITS || !code.all(Char::isDigit)) return false
        return runCatching { verifier.isValidCode(secret, code) }.getOrDefault(false)
    }

    /**
     * The `otpauth://totp/...` URI for an account, which is what the QR code on the setup screen
     * encodes and what an authenticator app understands.
     *
     * The **issuer** is what the user sees as the entry's name in their authenticator, and the
     * **label** is the account under it — so the entry reads "PetCare" / their email address, which
     * is what makes it findable among the dozen other codes they already have in there.
     *
     * The algorithm, digit count and period are stated even though they are the defaults, because a
     * URI that says what it means cannot be misread by an app that happens to default differently.
     */
    fun otpAuthUri(secret: String, accountEmail: String): String = QrData.Builder()
        .label(accountEmail)
        .secret(secret)
        .issuer(ISSUER)
        .algorithm(HashingAlgorithm.SHA1)
        .digits(CODE_DIGITS)
        .period(TIME_STEP_SECONDS)
        .build()
        .uri

    companion object {

        /**
         * How many digits a code has.
         *
         * Deliberately the same number as [com.prash.petcare.ui.auth.CodeEntry.LENGTH], which is how
         * many boxes the on-screen keypad draws. The two are not connected in code — one is a
         * protocol constant and the other is a layout — but if either ever changed, the other would
         * have to change with it or the user could not type a whole code.
         */
        const val CODE_DIGITS = 6

        /** The window each code is valid for, in seconds. Thirty is what authenticator apps assume. */
        const val TIME_STEP_SECONDS = 30

        /** How many windows either side of "now" are also accepted. See the class note on drift. */
        private const val ALLOWED_DRIFT_STEPS = 1

        /** The name the account appears under in the user's authenticator app. */
        private const val ISSUER = "PetCare"
    }
}
