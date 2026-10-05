package com.panomc.plugins.market.util

enum class MarketStatus {
    ACTIVE, INACTIVE, HIDDEN,

    /** Products only: hidden everywhere, kept for history (soft delete sets it). */
    ARCHIVED
}
