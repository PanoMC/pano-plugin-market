package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_payment_method.feeMode` (01 section 6.1). */
enum class PaymentFeeMode { NONE, BUYER }

// `settings` is a raw JSON object string (may hold gateway secrets) — kept typed String so Gson
// row-deserialization maps the column verbatim; endpoints encode/decode it with JsonObject.
// The columns after `updatedAt` arrived with scheme version 6 (01 section 6.1); money is x100 in the base currency.
open class MarketPaymentMethod(
    val id: Long = -1,
    val methodId: String = "",
    val enabled: Boolean = false,
    val settings: String = "{}",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Order at checkout. */
    val position: Int = 0,
    /** Replaces the provider's display name. */
    val customLabel: String? = null,
    val customDescription: String? = null,
    val feeMode: PaymentFeeMode = PaymentFeeMode.NONE,
    /** Percent x100. */
    val feePercent: Long = 0,
    val feeFixed: Long = 0,
    /** Admin window in the base currency, compared with the cart total before fee and credits. */
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    /** JSON array of currency codes; `null` = everything the provider supports. */
    val currencies: String? = null,
    val testMode: Boolean = false,
    /** Last authenticated webhook. */
    val lastInboundAt: Long? = null,
    val lastError: String? = null,
    val lastErrorAt: Long? = null,
    val settingsUpdatedAt: Long? = null,
) : DBEntity()
