package com.panomc.plugins.market.core.shipping

import com.panomc.plugins.market.core.order.AddressFieldSets
import com.panomc.plugins.market.core.order.BuyerValidator
import com.panomc.plugins.market.spi.common.Address
import com.panomc.plugins.market.spi.shipping.AddressField
import java.util.Locale

/** Outcome of [AddressValidator.check]: `fields = missing + invalid` is what the API reports (address property names). */
class AddressCheck(val valid: Boolean, val missing: List<String>, val invalid: List<String>) {
    val fields: List<String> get() = missing + invalid
}

/** Shipping address normalisation and validation (10 sections 3.2 and 3.3). Pure. */
object AddressValidator {
    private val MAX_LENGTH = mapOf(
        "firstName" to 100, "lastName" to 100, "company" to 100, "state" to 100, "city" to 100, "district" to 100,
        "neighborhood" to 100, "line1" to 255, "line2" to 255, "postalCode" to 16
    )

    private val POSTAL_FORMATS: Map<String, Regex> = buildMap {
        val five = Regex("^\\d{5}$")
        listOf("TR", "DE", "FR", "IT", "ES").forEach { put(it, five) }
        put("US", Regex("^\\d{5}(-\\d{4})?$"))
        put("CA", Regex("^[A-Z]\\d[A-Z] ?\\d[A-Z]\\d$"))
        put("GB", Regex("^[A-Z]{1,2}\\d[A-Z\\d]? ?\\d[A-Z]{2}$"))
        put("NL", Regex("^\\d{4} ?[A-Z]{2}$"))
        put("AU", Regex("^\\d{4}$"))
    }
    private val POSTAL_OTHER = Regex("^[A-Z0-9][A-Z0-9 -]{1,14}$")
    private val IDENTITY_TR = Regex("^\\d{11}$")
    private val IDENTITY_OTHER = Regex("^[A-Za-z0-9-]{5,20}$")

    /** The provider field names of 10 section 3.3 (`DISTRICT -> district`, ...). */
    fun propertyOf(field: AddressField): String = when (field) {
        AddressField.DISTRICT -> "district"
        AddressField.NEIGHBORHOOD -> "neighborhood"
        AddressField.POSTAL_CODE -> "postalCode"
        AddressField.STATE -> "state"
        AddressField.PHONE -> "phone"
        AddressField.EMAIL -> "email"
        AddressField.IDENTITY_NUMBER -> "identityNumber"
    }

    /** Control characters removed, trimmed, inner whitespace collapsed to one space; empty becomes `null`. */
    fun clean(value: String?): String? {
        if (value == null) return null

        val cleaned = value.replace(Regex("\\p{Cc}"), " ").trim().replace(Regex("\\s+"), " ")

        return cleaned.ifEmpty { null }
    }

    /**
     * Normalisation applied before validation and before storing (10 section 3.2). Billing-only keys (`taxOffice`,
     * `taxNumber`) are dropped; `identityNumber` is kept only when [keepIdentityNumber] (a provider requires it).
     * A phone that is not a valid number keeps its cleaned text so that [check] reports it as invalid.
     */
    fun normalize(address: Address, orderEmail: String? = null, keepIdentityNumber: Boolean = false): Address {
        val country = clean(address.country)?.uppercase(Locale.ROOT)
        val rawPhone = clean(address.phone)
        val email = clean(address.email)?.lowercase(Locale.ROOT) ?: clean(orderEmail)?.lowercase(Locale.ROOT)

        return Address(
            firstName = clean(address.firstName),
            lastName = clean(address.lastName),
            company = clean(address.company),
            phone = rawPhone?.let { PhoneNormalizer.toE164(it, country) ?: it },
            email = email,
            country = country,
            state = clean(address.state),
            city = clean(address.city),
            district = clean(address.district),
            neighborhood = clean(address.neighborhood),
            line1 = clean(address.line1),
            line2 = clean(address.line2),
            postalCode = clean(address.postalCode)?.uppercase(Locale.ROOT),
            taxOffice = null,
            taxNumber = null,
            identityNumber = if (keepIdentityNumber) clean(address.identityNumber) else null
        )
    }

    /** The required address properties of [country] plus those of the selected method's provider (10 section 3.3). */
    fun requiredFields(country: String?, providerFields: Collection<AddressField> = emptyList()): List<String> {
        val base = AddressFieldSets.forCountry(country?.uppercase(Locale.ROOT) ?: "")

        return (base + providerFields.map { propertyOf(it) }).distinct()
    }

    /** Validates an address that went through [normalize]. */
    fun check(address: Address, providerFields: Collection<AddressField> = emptyList()): AddressCheck {
        val values = valuesOf(address)
        val missing = requiredFields(address.country, providerFields).filter { values[it].isNullOrBlank() }
        val invalid = ArrayList<String>()

        for ((name, value) in values) {
            if (value == null) continue

            if (!isValid(name, value, address.country)) invalid += name
        }

        return AddressCheck(missing.isEmpty() && invalid.isEmpty(), missing, invalid)
    }

    private fun valuesOf(a: Address): Map<String, String?> = linkedMapOf(
        "firstName" to a.firstName, "lastName" to a.lastName, "company" to a.company, "phone" to a.phone,
        "email" to a.email, "country" to a.country, "state" to a.state, "city" to a.city, "district" to a.district,
        "neighborhood" to a.neighborhood, "line1" to a.line1, "line2" to a.line2, "postalCode" to a.postalCode,
        "identityNumber" to a.identityNumber
    )

    private fun isValid(name: String, value: String, country: String?): Boolean {
        MAX_LENGTH[name]?.let { if (value.length > it) return false }

        return when (name) {
            "country" -> Countries.isValid(value)
            "phone" -> PhoneNormalizer.isE164(value)
            "email" -> BuyerValidator.isValidEmail(value)
            "postalCode" -> (POSTAL_FORMATS[country] ?: POSTAL_OTHER).matches(value)
            "identityNumber" -> (if (country == "TR") IDENTITY_TR else IDENTITY_OTHER).matches(value)
            else -> true
        }
    }
}
