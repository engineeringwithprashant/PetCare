package com.prash.petcare.data.auth

import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.DefaultCodeVerifier
import dev.samstevens.totp.code.HashingAlgorithm
import dev.samstevens.totp.qr.QrData
import dev.samstevens.totp.secret.DefaultSecretGenerator
import dev.samstevens.totp.time.SystemTimeProvider
import dev.samstevens.totp.time.TimeProvider

/** The app's one route to time-based one-time passwords (TOTP, RFC 6238). */
class TotpAuthenticator(timeProvider: TimeProvider = SystemTimeProvider()) {

    /** Makes new secrets. 32 Base32 characters, the library's default: 5 bits per character. */
    private val secretGenerator = DefaultSecretGenerator()

    /** Turns a secret and a counter into six digits. SHA-1 and six digits are this class's defaults. */
    private val codeGenerator = DefaultCodeGenerator(HashingAlgorithm.SHA1, CODE_DIGITS)

    /** Checks a typed code against a secret, allowing one time step either side. */
    private val verifier = DefaultCodeVerifier(codeGenerator, timeProvider).apply {
        setTimePeriod(TIME_STEP_SECONDS)
        setAllowedTimePeriodDiscrepancy(ALLOWED_DRIFT_STEPS)
    }

    /** A brand-new random secret, Base32-encoded. */
    fun newSecret(): String = secretGenerator.generate()

    /** True when [code] is the current code for [secret], or the one either side of it. */
    fun isCodeValid(secret: String, code: String): Boolean {
        if (secret.isBlank() || code.length != CODE_DIGITS || !code.all(Char::isDigit)) return false
        return runCatching { verifier.isValidCode(secret, code) }.getOrDefault(false)
    }

    /** The `otpauth://totp/...` URI for an account. */
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

        /** How many digits a code has. */
        const val CODE_DIGITS = 6

        /** The window each code is valid for, in seconds. Thirty is what authenticator apps assume. */
        const val TIME_STEP_SECONDS = 30

        /** How many windows either side of "now" are also accepted. See the class note on drift. */
        private const val ALLOWED_DRIFT_STEPS = 1

        /** The name the account appears under in the user's authenticator app. */
        private const val ISSUER = "PetCare"
    }
}
