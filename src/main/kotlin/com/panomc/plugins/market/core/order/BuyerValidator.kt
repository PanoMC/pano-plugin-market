package com.panomc.plugins.market.core.order

import java.util.Locale

/**
 * Pure rules for the buyer of a checkout (06 section 6.1): the guest username and e-mail, and the order e-mail of a
 * logged-in buyer. No database, no clock. A failure is reported as the field paths of `BUYER_INFO_REQUIRED`
 * (`guest.username`, `guest.email`, `email`); the caller throws the error.
 */
object BuyerValidator {
    const val FIELD_USERNAME = "guest.username"
    const val FIELD_EMAIL = "guest.email"
    const val FIELD_ORDER_EMAIL = "email"

    const val MAX_EMAIL_LENGTH = 255

    /** Platform username alphabet with the Java-edition length; Bedrock prefixes are out of scope for v1. */
    private val USERNAME = Regex("[A-Za-z0-9_]{3,16}")

    /**
     * Local part 1..64 characters without whitespace or `@`, then one or more DNS labels. Deliberately not the platform
     * `Regexes.EMAIL` (it rejects TLDs longer than 4). Matched against the whole, lower-cased value.
     */
    private val EMAIL = Regex("[^\\s@]{1,64}@[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+")

    private val TLD = Regex("[a-z]{2,}")

    /** A guest that passed [validateGuest]: [username] as typed (trimmed), [usernameKey] lower-cased, [email] lower-cased. */
    class Guest(val username: String, val email: String) {
        val usernameKey: String get() = username.lowercase(Locale.ROOT)
        val buyerKey: String get() = "g:$usernameKey"
    }

    sealed class GuestResult {
        class Valid(val guest: Guest) : GuestResult()

        /** Every offending path, in the order `guest.username`, `guest.email`. */
        class Invalid(val fields: List<String>) : GuestResult()
    }

    fun normalizeUsername(raw: String?): String = raw?.trim().orEmpty()

    fun normalizeEmail(raw: String?): String = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()

    /** `true` when [name] (already trimmed) is a valid buyer-supplied Minecraft username. */
    fun isValidUsername(name: String): Boolean = USERNAME.matches(name)

    /** `true` when [email] (already trimmed and lower-cased) is acceptable. No DNS or MX lookup. */
    fun isValidEmail(email: String): Boolean {
        if (email.length > MAX_EMAIL_LENGTH || email.any { Character.isISOControl(it) } || !EMAIL.matches(email)) return false

        return TLD.matches(email.substringAfterLast('.'))
    }

    fun validateGuest(username: String?, email: String?): GuestResult {
        val name = normalizeUsername(username)
        val mail = normalizeEmail(email)
        val invalid = buildList {
            if (!isValidUsername(name)) add(FIELD_USERNAME)
            if (!isValidEmail(mail)) add(FIELD_EMAIL)
        }

        return if (invalid.isEmpty()) GuestResult.Valid(Guest(name, mail)) else GuestResult.Invalid(invalid)
    }

    /**
     * The order e-mail of a logged-in buyer: the account e-mail, else a valid `billingInfo.email`, else `null`
     * (the caller answers `BUYER_INFO_REQUIRED {fields: ["email"]}`). A guest's e-mail is never passed here.
     */
    fun orderEmailOfAccount(accountEmail: String?, billingEmail: String?): String? {
        val account = normalizeEmail(accountEmail)
        if (account.isNotEmpty()) return account

        return normalizeEmail(billingEmail).takeIf { isValidEmail(it) }
    }
}
