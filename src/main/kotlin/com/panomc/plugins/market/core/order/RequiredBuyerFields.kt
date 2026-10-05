package com.panomc.plugins.market.core.order

import com.panomc.plugins.market.config.BillingInfoMode
import com.panomc.plugins.market.spi.payment.BuyerField

/**
 * `requiredFields` of 06 section 8.2: the paths a checkout must carry, from `billingInfoMode` united with the fields the
 * selected provider makes mandatory (`capabilities.requiredBuyerFields`). Pure; the quote reports the union as
 * `requiredBuyerFields`, checkout answers `BUYER_INFO_REQUIRED {fields}` for the ones it lacks.
 */
object RequiredBuyerFields {
    private val BILLING_REQUIRED = listOf("billingInfo.type", "billingInfo.firstName", "billingInfo.lastName", "billingInfo.country", "billingInfo.city", "billingInfo.line1")
    private val COMPANY = listOf("billingInfo.company", "billingInfo.taxNumber")
    private val ADDRESS = listOf("billingInfo.country", "billingInfo.city", "billingInfo.line1")

    /**
     * @param company the buyer chose `type = COMPANY` (adds the company paths under `REQUIRED`)
     * @param country `billingInfo.country` as sent; the postal code is not required for `TR`
     * @param requiresShipping the cart has a physical line (`SHIPPING_ADDRESS` then means the order's shipping address)
     */
    fun of(mode: BillingInfoMode, provider: Set<BuyerField>, company: Boolean, country: String?, requiresShipping: Boolean): List<String> {
        val out = LinkedHashSet<String>()

        if (mode == BillingInfoMode.REQUIRED) {
            out += BILLING_REQUIRED

            if (company) out += COMPANY
        }

        // a stable order, whatever the set's iteration order is
        for (field in BuyerField.entries) {
            if (field !in provider) continue

            when (field) {
                BuyerField.EMAIL -> out += "email"
                BuyerField.FIRST_NAME -> out += "billingInfo.firstName"
                BuyerField.LAST_NAME -> out += "billingInfo.lastName"
                BuyerField.PHONE -> out += "billingInfo.phone"
                BuyerField.COUNTRY -> out += "billingInfo.country"
                BuyerField.IDENTITY_NUMBER -> out += "billingInfo.identityNumber"
                BuyerField.BILLING_ADDRESS -> out += billingAddress(country)
                BuyerField.SHIPPING_ADDRESS -> if (requiresShipping) out += "shippingAddress" else out += billingAddress(country)
            }
        }

        return out.toList()
    }

    private fun billingAddress(country: String?): List<String> =
        if (country.equals("TR", ignoreCase = true)) ADDRESS else ADDRESS + "billingInfo.postalCode"
}
