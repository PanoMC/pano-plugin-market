package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_provider_state.kind` (01 section 6.6). */
enum class ProviderStateKind { PAYMENT, SHIPPING }

/**
 * `market_provider_state` (01 section 6.6): the key/value store handed to providers. [value] is an `ENC` column: the
 * `v1:...` text of `SecretCipher`, stored verbatim.
 */
open class MarketProviderState(
    val id: Long = -1,
    val kind: ProviderStateKind = ProviderStateKind.PAYMENT,
    val providerId: String = "",
    val stateKey: String = "",
    val value: String = "",
    val expiresAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
