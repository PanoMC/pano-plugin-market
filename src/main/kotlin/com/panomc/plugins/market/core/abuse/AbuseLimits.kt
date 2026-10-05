package com.panomc.plugins.market.core.abuse

/** Every constant of 11 sections 8.3, 11 and 12 in one place. Pure. */
object AbuseLimits {
    // PT-2 (section 4.1)
    const val MAX_LINE_QUANTITY = 1000
    const val MAX_CART_LINES = 50
    const val MAX_FIELD_VALUE_KEYS = 20

    // L3 / L4
    const val MAX_ORDERS_PER_IP_PER_HOUR = 20
    const val ORDERS_PER_IP_WINDOW_MS = 60L * 60 * 1000
    const val MAX_OPEN_ORDERS = 3
    const val MAX_HELD_UNITS_PER_PRODUCT_PER_BUYER = 10

    // L2 .. L11 (per minute)
    const val DEFAULT_QUOTE_PER_MINUTE = 60
    const val ORDER_TOKEN_MISS_PER_MINUTE = 30
    const val ORDER_STATUS_PER_MINUTE = 120
    const val PANEL_ACTION_PER_MINUTE = 10
    const val INBOUND_REJECTED_PER_MINUTE = 60
    const val EXPORT_PER_MINUTE = 6

    // settings validation
    val CHECKOUT_RATE_RANGE = 0..100_000
    val QUOTE_RATE_RANGE = 1..100_000
    val COUPON_LOCK_THRESHOLD_RANGE = 0..100
    val COUPON_LOCK_MINUTES_RANGE = 1..1440
    const val DEFAULT_COUPON_LOCK_THRESHOLD = 10
    const val DEFAULT_COUPON_LOCK_MINUTES = 15

    // throttle scopes (market_throttle.scope)
    const val SCOPE_COUPON = "COUPON"
    const val SCOPE_GIFT = "GIFT"
    const val SCOPE_REVEAL = "REVEAL"
    const val SCOPE_CHECKOUT = "CHECKOUT"

    // 12.1 / 12.2
    const val MAX_SUBJECT_LENGTH = 191
    const val ANON_THRESHOLD_FACTOR = 10
    const val SUBJECT_ANON = "anon"
    const val CODE_DEDUP_HASHES_PER_SUBJECT = 32
    const val CODE_DEDUP_SUBJECTS = 10_000
    const val GENERATED_CODE_LENGTH = 12
    const val GENERATED_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    const val MIN_GIFT_CODE_LENGTH = 8

    // 8.3 secret reveal
    const val REVEAL_THRESHOLD = 5
    const val REVEAL_WINDOW_MINUTES = 10
    const val REVEAL_LOCK_MINUTES = 10

    // 9.1
    const val MAX_BLOCK_REASON_LENGTH = 255

    // 17 retention
    const val THROTTLE_RETENTION_DAYS = 2
    const val BLOCK_EXPIRED_RETENTION_DAYS = 30

    /** L1: refill one token per `60000 / perMinute` ms; 0 disables the limiter (null). */
    fun refillMs(perMinute: Int): Long? = if (perMinute <= 0) null else 60_000L / perMinute

    /** L4: units one unpaid offline-method order may hold of a stock-limited product. */
    fun heldUnitsCap(maxQuantityPerOrder: Int?): Int =
        minOf(maxQuantityPerOrder ?: MAX_HELD_UNITS_PER_PRODUCT_PER_BUYER, MAX_HELD_UNITS_PER_PRODUCT_PER_BUYER)
}
