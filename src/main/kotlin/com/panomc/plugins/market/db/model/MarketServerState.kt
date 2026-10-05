package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/**
 * `market_server_state` (01 section 9.2): what market knows about each Minecraft server. [mcComponentVersion],
 * [capabilities] (csv), [platform] (`SPIGOT, PAPER, FOLIA, BUNGEECORD, VELOCITY, FABRIC`), [protocol], [queuedCount]
 * and [lastSeenAt] are written only from the last `MARKET_SYNC` request of that server; [settings] (JSON, `null` =
 * panel defaults) only by the panel.
 */
open class MarketServerState(
    val id: Long = -1,
    val serverId: Long = 0,
    val mcComponentVersion: String? = null,
    val capabilities: String? = null,
    val platform: String? = null,
    val protocol: Int? = null,
    val queuedCount: Int? = null,
    val lastSeenAt: Long? = null,
    val settings: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DBEntity()
