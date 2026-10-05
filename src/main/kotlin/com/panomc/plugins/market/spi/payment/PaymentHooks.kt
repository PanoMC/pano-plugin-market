package com.panomc.plugins.market.spi.payment

import com.panomc.plugins.market.spi.common.LocalizedText
import com.panomc.plugins.market.spi.common.Money
import io.vertx.core.json.JsonObject

/** Result of `validateSettings`. Build it with [ok] or [invalid]. */
class SettingsValidation private constructor(
    val ok: Boolean,
    val fieldErrors: Map<String, LocalizedText>,
    val message: LocalizedText?
) {
    companion object {
        fun ok(): SettingsValidation = SettingsValidation(true, emptyMap(), null)

        fun invalid(fieldErrors: Map<String, LocalizedText>, message: LocalizedText? = null): SettingsValidation {
            require(fieldErrors.isNotEmpty() || message != null) { "An invalid result needs a field error or a message" }
            return SettingsValidation(false, fieldErrors, message)
        }
    }
}

/** Result of `onSettingsSaved` and `runAction`, shown to the admin. */
sealed class ActionResult {
    class None : ActionResult()

    class Message(val text: LocalizedText, val success: Boolean) : ActionResult()

    /** Provider-derived settings to persist (for example a webhook secret returned by a registration call). */
    class SettingsPatch(val values: Map<String, String?>, val text: LocalizedText) : ActionResult()

    /** Catalogue for market to upsert (Tebex / PayNow import). Market does the writes and links the provider meta. */
    class CatalogImport(val categories: List<ImportedCategory>, val products: List<ImportedProduct>) : ActionResult()

    companion object {
        fun none(): ActionResult = None()
    }
}

class ImportedCategory(val externalId: String, val name: String, val parentExternalId: String?)

class ImportedProduct(
    val externalId: String,
    val name: String,
    val descriptionHtml: String?,
    val price: Money,
    val categoryExternalId: String?,
    val imageUrl: String?,
    /** Non-null = recurring package. */
    val intervalUnit: IntervalUnit?,
    val intervalCount: Int?,
    /** Stored as this provider's product meta. */
    val meta: JsonObject
)

/** Verdict of `checkEligibility`. Build it with [eligible], [ineligible] or [oneOffOnly]. */
class Eligibility private constructor(
    val eligible: Boolean,
    val code: String?,
    val reason: LocalizedText?,
    /** Eligible as a one-off payment but not as the recurring plan (subscription checkouts only). */
    val oneOffOnly: Boolean
) {
    companion object {
        fun eligible(): Eligibility = Eligibility(true, null, null, false)

        fun ineligible(code: String, reason: LocalizedText): Eligibility {
            require(code.isNotBlank()) { "An ineligible verdict needs a code" }
            return Eligibility(false, code, reason, false)
        }

        /** "I can take this payment but not as this recurring plan": market sells the offer as MANUAL instead of removing the method. */
        fun oneOffOnly(code: String): Eligibility {
            require(code.isNotBlank()) { "A one-off verdict needs a code" }
            return Eligibility(true, code, null, true)
        }
    }
}
