package com.panomc.plugins.market.core.cart

/**
 * What the database says about the product a line points at (06 section 2.1). `null` product facts mean the product row
 * does not exist.
 */
data class ProductFacts(
    /** The product exists and `deletedAt IS NULL`. */
    val available: Boolean,
    /** Ids of the product's variants with `deletedAt IS NULL`. */
    val variantIds: Set<Long> = emptySet(),
    /** `fieldKey` of every custom field of the product. */
    val fieldKeys: Set<String> = emptySet()
)

/**
 * Structural validity of one line, on cart write and on merge (06 section 2.1): the product exists and is not deleted;
 * `variantId` is 0 or a live variant of that product; `fieldValues` keys are fields of the product and fit the shape
 * limits; `targetServerId` is absent or a positive integer. Codes are line codes of 04 section 11. Whether the product
 * is sellable at all (`ACTIVE`, sale window, stock) is the quote's business, not the cart's. Pure.
 */
object CartValidator {
    const val PRODUCT_UNAVAILABLE = "PRODUCT_UNAVAILABLE"
    const val VARIANT_UNAVAILABLE = "VARIANT_UNAVAILABLE"
    const val FIELD_INVALID = "FIELD_INVALID"
    const val SERVER_UNAVAILABLE = "SERVER_UNAVAILABLE"

    /** Empty = the line is structurally valid. A missing product answers `PRODUCT_UNAVAILABLE` alone. */
    fun lineErrors(line: CartLine, facts: ProductFacts?): List<String> {
        if (facts == null || !facts.available || line.productId <= 0) return listOf(PRODUCT_UNAVAILABLE)

        val errors = ArrayList<String>(3)

        if (line.variantId != 0L && line.variantId !in facts.variantIds) errors += VARIANT_UNAVAILABLE

        if (!CartLimits.fieldValuesFit(line.fieldValues) || !facts.fieldKeys.containsAll(line.fieldValues.keys)) errors += FIELD_INVALID

        if (line.targetServerId != null && line.targetServerId <= 0) errors += SERVER_UNAVAILABLE

        return errors
    }

    fun isValid(line: CartLine, facts: ProductFacts?): Boolean = lineErrors(line, facts).isEmpty()
}
