package com.panomc.plugins.market.util

/** `market_order.status` (00 section 7.1). `COMPLETED` keeps its existing meaning "paid". */
enum class OrderStatus {
    PENDING, REVIEW, COMPLETED, PARTIALLY_REFUNDED, REFUNDED, CHARGEBACK, FAILED, CANCELLED, EXPIRED
}
