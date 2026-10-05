package com.prash.petcare.util

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** One country in the dialling-code selector. */
data class Country(
    val regionCode: String,
    val displayName: String,
    val diallingCode: Int,
    val flag: String
) {

    /** "🇳🇵 +977" — what the selector shows once a country is chosen, short enough to sit beside a field. */
    val shortLabel: String get() = "$flag +$diallingCode"

    /** "🇳🇵  Nepal  +977" — the full row in the chooser, where there is room to name the country. */
    val listLabel: String get() = "$flag  $displayName  +$diallingCode"
}

/** What came of checking a typed number against the chosen country. */
enum class PhoneCheck {

    /** Nothing was typed. Whether that is an error depends on the form, so the caller decides. */
    EMPTY,

    /** Something was typed, but it is not a real number for the country selected. */
    INVALID,

    /** A real number for that country. */
    VALID
}

/** The app's phone-number rules: which countries can be chosen, how long a number may. */
object PhoneNumbers {

    /** The country a form starts on. */
    const val DEFAULT_REGION = "NP"

    /** The most digits any international number can have, from ITU-T E.164. */
    private const val E164_MAX_DIGITS = 15

    /** The characters people write between the digits of a number: "+977 (98) 123-4567". */
    private const val PHONE_PUNCTUATION = "+ ().-"

    /** Fewer digits than this is a stray number inside a typo, not an attempt at a phone number. */
    private const val MIN_PHONE_ATTEMPT_DIGITS = 4

    /** The library's single shared instance. */
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /** Every country the selector offers, in alphabetical order for the user's own language. */
    val countries: List<Country> by lazy {
        util.supportedRegions
            .map { region ->
                Country(
                    regionCode = region,
                    displayName = displayNameFor(region),
                    diallingCode = util.getCountryCodeForRegion(region),
                    flag = flagFor(region)
                )
            }
            .sortedBy { it.displayName.lowercase(Locale.getDefault()) }
    }

    /** The country for [regionCode], falling back to [DEFAULT_REGION] if it is not one we offer. */
    fun countryFor(regionCode: String): Country =
        countries.firstOrNull { it.regionCode == regionCode }
            ?: countries.first { it.regionCode == DEFAULT_REGION }

    /** The most digits the national part of a number may have in [regionCode] — what the input box is capped. */
    fun maxNationalDigits(regionCode: String): Int {
        val diallingDigits = util.getCountryCodeForRegion(regionCode).toString().length
        return (E164_MAX_DIGITS - diallingDigits).coerceAtLeast(1)
    }

    /** Checks a number typed beside a country selector. */
    fun check(regionCode: String, nationalNumber: String): PhoneCheck = when {
        nationalNumber.none { it.isDigit() } -> PhoneCheck.EMPTY
        toE164(regionCode, nationalNumber) == null -> PhoneCheck.INVALID
        else -> PhoneCheck.VALID
    }

    /** Turns a country and a typed national number into the stored `+9779812345678` form. */
    fun toE164(regionCode: String, nationalNumber: String): String? {
        val parsed = try {
            util.parse(nationalNumber, regionCode)
        } catch (e: NumberParseException) {
            // Not a number at all — letters, or nothing but separators.
            return null
        }
        if (!util.isValidNumberForRegion(parsed, regionCode)) return null
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /** Reads a stored number back into the country and national digits a form shows, or null. */
    fun split(storedNumber: String, fallbackRegion: String = DEFAULT_REGION): Pair<Country, String>? {
        val parsed = try {
            util.parse(storedNumber, fallbackRegion)
        } catch (e: NumberParseException) {
            return null
        }
        // getRegionCodeForNumber returns null for a dialling code shared by no single country.
        val region = util.getRegionCodeForNumber(parsed) ?: fallbackRegion
        return countryFor(region) to parsed.nationalNumber.toString()
    }

    /** Turns anything typed into the log-in box into a stored-format number, or null. */
    fun parseLoginNumber(text: String): String? {
        val trimmed = text.trim()
        // An address can contain digits, and "@" can never appear in a phone number.
        if (trimmed.isEmpty() || trimmed.contains('@')) return null
        if (trimmed.none { it.isDigit() }) return null

        val parsed = try {
            util.parse(trimmed, DEFAULT_REGION)
        } catch (e: NumberParseException) {
            return null
        }
        if (!util.isValidNumber(parsed)) return null
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /** True when [text] looks like somebody attempting a phone number rather than an email address. */
    fun looksLikePhoneAttempt(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.contains('@')) return false
        return trimmed.all { it.isDigit() || it in PHONE_PUNCTUATION } &&
            trimmed.count { it.isDigit() } >= MIN_PHONE_ATTEMPT_DIGITS
    }

    /** The country's name in the user's language, falling back to the region code itself. */
    private fun displayNameFor(regionCode: String): String =
        Locale.Builder().setRegion(regionCode).build()
            .getDisplayCountry(Locale.getDefault())
            .takeIf { it.isNotBlank() && it != regionCode }
            ?: regionCode

    /** The flag emoji for a region code. */
    private fun flagFor(regionCode: String): String {
        if (regionCode.length != 2) return ""
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + regionCode[0].code)) +
            String(Character.toChars(base + regionCode[1].code))
    }
}
