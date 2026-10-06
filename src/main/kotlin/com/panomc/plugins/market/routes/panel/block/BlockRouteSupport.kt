package com.panomc.plugins.market.routes.panel.block

import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketBlockDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.BlockListService
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool

private object BlockWiringHolder

@Volatile
private var cachedBlockList: Pair<MarketPlugin, BlockListService>? = null

/**
 * The block list on the plugin's beans (MK-151); one per plugin instance, so the in-memory IP ranges and the once-a-minute hit counter are shared by the
 * quote, checkout, payment, subscription and panel routes.
 */
internal fun blockListService(plugin: MarketPlugin): BlockListService {
    cachedBlockList?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(BlockWiringHolder) {
        cachedBlockList?.takeIf { it.first === plugin }?.second ?: buildBlockListService(plugin).also { cachedBlockList = plugin to it }
    }
}

private fun buildBlockListService(plugin: MarketPlugin): BlockListService {
    val context = plugin.beans
    val databaseManager = { context.getBean(DatabaseManager::class.java) }

    return BlockListService(
        clock = SystemClock,
        blocks = context.getBean(MarketBlockDao::class.java),
        users = PlatformUserDirectory(databaseManager),
        db = MarketDb({ databaseManager().getSqlClient() as Pool }, SystemClock)
    )
}

/** The wire shape of a block row (04 section 7); `hitCount` and `lastHitAt` are part of it, the values are unmasked on purpose. */
internal fun blockJson(view: BlockListService.BlockView): JsonObject {
    val block = view.block

    return JsonObject()
        .put("id", block.id)
        .put("type", block.type.name)
        .put("value", block.value)
        .put("reason", block.reason)
        .put("source", block.source.name)
        .put("orderId", block.orderId)
        .put("createdBy", block.createdBy)
        .put("createdByUsername", view.createdByUsername)
        .put("hitCount", block.hitCount)
        .put("lastHitAt", block.lastHitAt)
        .put("expiresAt", block.expiresAt)
        .put("createdAt", block.createdAt)
}
