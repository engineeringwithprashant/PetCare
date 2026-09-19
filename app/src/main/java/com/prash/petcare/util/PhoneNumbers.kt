package com.prash.petcare.util

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.util.Locale

/** One country in the dialling-code selector.
 *
 * @property regionCode The two-letter ISO region ("NP", "GB", "IN"). This is what is stored and what
 *   every [PhoneNumbers] function is keyed by — never the dialling code, because a dialling code is
 *   not unique: +1 is the United States, Canada and nineteen Caribbean countries, and they do not
 *   share number rules.
 * @property displayName The country's name in the user's own language, from [Locale].
 * @property diallingCode The number after the "+" — 977 for Nepal.
 * @property flag The country's flag as an emoji, built from the region code (see [PhoneNumbers]).
 */
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

/**
 * What came of checking a typed number against the chosen country.
 *
 * An enum rather than a string resource id, because this file sits in `util` alongside
 * [CareSchedule] and [GeoDistance] — helpers with no knowledge of the app's screens or its wording.
 * The UI layer turns each case into a message; see `ui/common/PhoneNumberErrors.kt`.
 */
enum class PhoneCheck {

    /** Nothing was typed. Whether that is an error depends on the form, so the caller decides. */
    EMPTY,

    /** Something was typed, but it is not a real number for the country selected. */
    INVALID,

    /** A real number for that country. */
    VALID
}

/**
 * The app's phone-number rules: which countries can be chosen, how long a number may be, whether a
 * typed number is real, and what it looks like in storage.
 *
 * **Why a library and not a pattern of our own.** The rules differ per country and keep changing:
 * Nepal's mobile numbers are ten digits, the UK's are eleven with a leading zero that vanishes
 * internationally, Germany's run from seven digits to thirteen. A hand-written "ten digits" check —
 * which is what this app used to do — rejects most of the world's real numbers and accepts plenty of
 * numbers that cannot exist. Google's libphonenumber carries the same per-country metadata Android's
 * own dialler uses, so the rules are right now and stay right through a library upgrade.
 *
 * **Why it lives in `util`.** Everything here is a pure function of its arguments: no Context, no
 * views, and — like every other file in this package — no string resources. That is what lets a
 * ViewModel call it directly without breaking the one-way MVVM chain, and lets the whole file be
 * exercised by an ordinary JVM unit test with no emulator (see `PhoneNumbersTest`).
 *
 * **Storage format is E.164** — `+9779812345678`: a "+", the dialling code, then the national number,
 * with no spaces, brackets or dashes at all. One canonical form matters because a stored number is
 * compared against a typed one when somebody signs in by phone (see
 * [com.prash.petcare.data.repository.UserRepository.findEmailForPhone]), and "+977 981-234 5678" and
 * "09812345678" are the same number written two ways that no string comparison would ever match.
 */
object PhoneNumbers {

    /**
     * The country a form starts on.
     *
     * Nepal, because that is where the app's users are. Deliberately a fixed default rather than one
     * read from the SIM or the device locale: a handset bought abroad and carried to Kathmandu
     * reports the wrong country, and starting on the wrong one is worse than starting on the one the
     * user almost certainly wants — which they can still change in two taps.
     */
    const val DEFAULT_REGION = "NP"

    /**
     * The most digits any international number can have, from ITU-T E.164 — the dialling code and the
     * national number together. This is what the input cap is derived from; see [maxNationalDigits].
     */
    private const val E164_MAX_DIGITS = 15

    /** The characters people write between the digits of a number: "+977 (98) 123-4567". */
    private const val PHONE_PUNCTUATION = "+ ().-"

    /** Fewer digits than this is a stray number inside a typo, not an attempt at a phone number. */
    private const val MIN_PHONE_ATTEMPT_DIGITS = 4

    /**
     * The library's single shared instance.
     *
     * Built lazily because loading the per-country metadata is the expensive part, and a session that
     * never opens a form with a phone field should never pay for it.
     */
    private val util: PhoneNumberUtil by lazy { PhoneNumberUtil.getInstance() }

    /**
     * Every country the selector offers, in alphabetical order for the user's own language.
     *
     * Taken from the library rather than typed out here, so the list is exactly the set of countries
     * whose rules it can actually check. A hand-written list would eventually name a country the
     * validator knows nothing about, and numbers for it would then be accepted or refused at random.
     */
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

    /**
     * The most digits the *national* part of a number may have in [regionCode] — what the input box
     * is capped at, so an over-long number cannot be typed in the first place.
     *
     * Derived from the E.164 maximum minus the dialling code's own digits, rather than from the
     * country's own typical length. That is deliberate: many countries have numbers of several
     * different lengths, so a cap set to the length of a common one would silently stop somebody
     * typing their real number, with no error to explain why the keyboard had stopped responding. A
     * cap that can never reject a valid number, plus a clear inline message from [check] when the
     * number is the wrong length for the country, puts the refusal somewhere the user can read it.
     */
    fun maxNationalDigits(regionCode: String): Int {
        val diallingDigits = util.getCountryCodeForRegion(regionCode).toString().length
        return (E164_MAX_DIGITS - diallingDigits).coerceAtLeast(1)
    }

    /**
     * Checks a number typed beside a country selector.
     *
     * [nationalNumber] is what the user typed *next to* the selector, so it may carry the spaces,
     * dashes and brackets people write numbers with, and may carry a leading zero — the national
     * trunk prefix, which the library strips as it parses.
     */
    fun check(regionCode: String, nationalNumber: String): PhoneCheck = when {
        nationalNumber.none { it.isDigit() } -> PhoneCheck.EMPTY
        toE164(regionCode, nationalNumber) == null -> PhoneCheck.INVALID
        else -> PhoneCheck.VALID
    }

    /**
     * Turns a country and a typed national number into the stored `+9779812345678` form, or null if
     * the two do not make a valid number for that country.
     *
     * `isValidNumberForRegion` rather than `isPossibleNumber`: "possible" checks only the digit
     * count, so it would accept a ten-digit Nepali number beginning with a prefix that has never been
     * allocated. Validity checks the prefix too, which is what makes a typo in the leading digits
     * fail here rather than at the first attempt to text the number.
     */
    fun toE164(regionCode: String, nationalNumber: String): String? {
        val parsed = try {
            util.parse(nationalNumber, regionCode)
        } catch (e: NumberParseException) {
            // Not a number at all — letters, or nothing but separators. There is nothing more
            // specific worth reporting than "not valid", which is what a null becomes.
            return null
        }
        if (!util.isValidNumberForRegion(parsed, regionCode)) return null
        return util.format(parsed, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    /**
     * Reads a stored number back into the country and national digits a form shows, or null when
     * [storedNumber] cannot be understood as a number at all.
     *
     * Needed because every form that *edits* a number has to put it back into two boxes, and because
     * numbers saved before these forms had a country selector are stored however the user typed them.
     * [fallbackRegion] is what those older numbers are read against — a bare "9812345678" saved by
     * the old form is a Nepali number, and treating it as one is what stops an existing profile's
     * number vanishing from the form the first time it is opened after this change.
     */
    fun split(storedNumber: String, fallbackRegion: String = DEFAULT_REGION): Pair<Country, String>? {
        val parsed = try {
            util.parse(storedNumber, fallbackRegion)
        } catch (e: NumberParseException) {
            return null
        }
        // getRegionCodeForNumber returns null for a dialling code shared by no single country;
        // falling back keeps the form usable rather than dropping the user's number on the floor.
        val region = util.getRegionCodeForNumber(parsed) ?: fallbackRegion
        return countryFor(region) to parsed.nationalNumber.toString()
    }

    /**
     * Turns anything typed into the log-in box into a stored-format number, or null when it is not a
     * phone number.
     *
     * This is the *log-in* path, which has no country selector: the field takes an email address or a
     * phone number, so a number arrives whole. A number written internationally ("+977 981…") carries
     * its own country; a bare local one ("981…") is read as [DEFAULT_REGION], which is what lets
     * somebody who registered in Nepal sign in by typing their number the way they say it aloud.
     *
     * Deliberately strict — it returns null rather than guessing — because the caller uses the answer
     * to decide whether the text is a number at all, and a guess would send a mistyped email address
     * down the phone path.
     */
    fun parseLoginNumber(text: String): String? {
        val trimmed = text.trim()
        // An address can contain digits, and "@" can never appear in a phone number, so this is the
        // one cheap test that keeps a mistyped email out of the phone branch entirely.
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

    /**
     * True when [text] looks like somebody attempting a phone number rather than an email address.
     *
     * Used only to decide *which* message an unusable log-in identifier gets: "that is not a valid
     * number" reads far better than "that is not a valid email address" to somebody who plainly typed
     * digits. It is not a validity check — [parseLoginNumber] is.
     */
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

    /**
     * The flag emoji for a region code.
     *
     * Every flag emoji is simply its two letters written as "regional indicator symbols" — a block of
     * code points sitting 0x1F1E6 above 'A'. So this is arithmetic rather than a table of 240 flags,
     * and a country added to the library in a later release gets its flag with no change here.
     */
    private fun flagFor(regionCode: String): String {
        if (regionCode.length != 2) return ""
        val base = 0x1F1E6 - 'A'.code
        return String(Character.toChars(base + regionCode[0].code)) +
            String(Character.toChars(base + regionCode[1].code))
    }
}
