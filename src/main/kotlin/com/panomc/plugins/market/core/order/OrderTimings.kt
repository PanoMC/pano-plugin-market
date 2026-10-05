package com.panomc.plugins.market.core.order

/** The two store settings that drive every window (`orderExpiryMinutes`, `bankTransferExpiryHours`). */
class TimingConfig(val orderExpiryMinutes: Int, val bankTransferExpiryHours: Int)

/**
 * Windows and caps of an order and its payment attempts (06 section 9.1). Pure: callers pass `now` and the values
 * read from the provider's capabilities. All values are epoch milliseconds UTC or durations in milliseconds.
 */
object OrderTimings {
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 60 * MINUTE_MS
    const val DAY_MS = 24 * HOUR_MS

    const val BANK_TRANSFER_PROVIDER = "bank-transfer"
    const val FREE_PROVIDER = "free"
    const val CREDITS_PROVIDER = "credits"

    /** Bounds of a provider-given attempt `expiresAt` (`StartPaymentResult.expiresAt`). */
    const val MIN_ATTEMPT_MS = MINUTE_MS
    const val MAX_ATTEMPT_MS = 30 * DAY_MS

    /** The hard cap never shrinks below a day. */
    const val MIN_HARD_CAP_MS = DAY_MS

    /** A retry is refused when the hard cap is closer than this. */
    const val RETRY_MIN_REMAINING_MS = 5 * MINUTE_MS

    /** A `CREATED` attempt younger than this may still have a start call in flight (06 section 12). */
    const val CREATED_IN_FLIGHT_MS = 2 * MINUTE_MS

    /** `PROCESSING` because the provider reported `Pending`: expired at `expiresAt` plus this (`longPending`: the long one). */
    const val PROCESSING_GRACE_MS = DAY_MS
    const val LONG_PROCESSING_GRACE_MS = 14 * DAY_MS

    /**
     * `W(p)`: the provider window. `bank-transfer` uses `bankTransferExpiryHours`; `free` / `credits` the store's
     * `orderExpiryMinutes`; any other provider its `paymentWindowMinutes`, else `orderExpiryMinutes`.
     */
    fun providerWindowMs(providerId: String, paymentWindowMinutes: Int?, config: TimingConfig): Long = when (providerId) {
        BANK_TRANSFER_PROVIDER -> config.bankTransferExpiryHours * HOUR_MS
        FREE_PROVIDER, CREDITS_PROVIDER -> config.orderExpiryMinutes * MINUTE_MS
        else -> (paymentWindowMinutes ?: config.orderExpiryMinutes) * MINUTE_MS
    }

    /** Order `expiresAt` at O1: `now + W(first provider)`; a manual pending order (06 section 14) uses the bank transfer window. */
    fun orderExpiresAtOnCreate(
        now: Long,
        providerId: String,
        paymentWindowMinutes: Int?,
        config: TimingConfig,
        manualPending: Boolean = false
    ): Long =
        if (manualPending) now + config.bankTransferExpiryHours * HOUR_MS
        else now + providerWindowMs(providerId, paymentWindowMinutes, config)

    /** Hard cap `H = order.createdAt + max(24 h, W(p))`: retries cannot keep stock reserved beyond it. */
    fun hardCap(orderCreatedAt: Long, providerWindowMs: Long): Long = orderCreatedAt + maxOf(MIN_HARD_CAP_MS, providerWindowMs)

    /**
     * Attempt `expiresAt`: `min(now + W(p), H)`, replaced by the provider's own value when it gave one, which is
     * clamped to `[now + 1 min, now + 30 d]`.
     */
    fun attemptExpiresAt(now: Long, providerWindowMs: Long, hardCap: Long, providerExpiresAt: Long? = null): Long =
        if (providerExpiresAt != null) providerExpiresAt.coerceIn(now + MIN_ATTEMPT_MS, now + MAX_ATTEMPT_MS)
        else minOf(now + providerWindowMs, hardCap)

    /** Order `expiresAt` after a new attempt: `max(order.expiresAt, attempt.expiresAt)`. */
    fun orderExpiresAtAfterAttempt(orderExpiresAt: Long?, attemptExpiresAt: Long): Long =
        if (orderExpiresAt == null) attemptExpiresAt else maxOf(orderExpiresAt, attemptExpiresAt)

    /** A retry through `/pay` is refused (409 `ORDER_NOT_PAYABLE`) when `H - now < 5 min`. */
    fun retryAllowed(now: Long, hardCap: Long): Boolean = hardCap - now >= RETRY_MIN_REMAINING_MS

    /**
     * Extra time a `PROCESSING` attempt lives past `expiresAt`. A buyer notice (`AWAITING_BANK`) gets none: an
     * unauthenticated "I have paid" must not lock stock for weeks.
     */
    fun processingGraceMs(longPending: Boolean, buyerNotice: Boolean): Long = when {
        buyerNotice -> 0L
        longPending -> LONG_PROCESSING_GRACE_MS
        else -> PROCESSING_GRACE_MS
    }

    /** The expiry job expires a `PROCESSING` attempt once `expiresAt + grace <= now`. */
    fun processingExpired(now: Long, attemptExpiresAt: Long, longPending: Boolean, buyerNotice: Boolean): Boolean =
        attemptExpiresAt + processingGraceMs(longPending, buyerNotice) <= now

    /** A `CREATED` attempt is old enough for the expiry job to treat the order as idle. */
    fun createdAttemptSettled(now: Long, createdAt: Long): Boolean = now - createdAt >= CREATED_IN_FLIGHT_MS
}
