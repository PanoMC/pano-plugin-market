package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/**
 * `market_legal_text` (01 section 5.4): one version of the legal text of one locale. Rows are never edited or deleted
 * (orders reference them); only [active] moves, exactly one row per locale is active.
 */
open class MarketLegalText(
    val id: Long = -1,
    /** Increases on every edit. */
    val version: Int = 1,
    val locale: String = "",
    val title: String = "",
    /** Sanitised HTML. */
    val content: String = "",
    /** SHA-256 of [content], hex. */
    val contentHash: String = "",
    val active: Boolean = false,
    val createdBy: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
