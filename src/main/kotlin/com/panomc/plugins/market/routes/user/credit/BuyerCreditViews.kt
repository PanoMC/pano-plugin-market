package com.panomc.plugins.market.routes.user.credit

import com.panomc.platform.model.PageRequest
import com.panomc.plugins.market.routes.base.pageJson
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.service.BuyerLedgerEntry
import com.panomc.plugins.market.service.CreditService
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * The buyer's own credit views (04 section 4, 07 section 11.3): `GET /me/credits` and `GET /me/summary`. Amounts are decimal credits (the ledger's
 * x100 divided by 100, like every money value of the API). Both work while `creditsEnabled = false` (the balance and the ledger are kept, 07 section 14.1);
 * the theme hides the tab when the balance is 0 and credits are off.
 */
class BuyerCreditViews(
    private val config: () -> MarketConfig,
    private val credits: CreditService,
    private val prefix: String
) {
    /** `GET /me/credits`: `balance`, `creditName`, `items[{id, type, amount (signed), balanceAfter, note, orderPublicId, createdAt}]` (the ledger entries) and `page`. */
    suspend fun credits(userId: Long, window: PageRequest, sqlClient: SqlClient): JsonObject {
        val ledger = credits.ledgerOf(userId, window.offset, window.size, sqlClient)

        return pageJson(
            ledger.entries.map { entry(it) }, ledger.entryCount, window,
            mapOf("balance" to MoneyUtil.toDecimal(credits.balance(userId, sqlClient)), "creditName" to config().creditName)
        )
    }

    /**
     * `GET /me/summary`: `creditsEnabled`, `creditBalance`, `creditName`, `cartItemCount` (the sum of the quantities of the server cart, like the theme's
     * `cart.count`), `activeSubscriptionCount` (`ACTIVE`, `PAST_DUE`, `PAUSED`), `subscriptionCount` (every status), `isCreator` (owns a creator code that is not deleted).
     */
    suspend fun summary(userId: Long, sqlClient: SqlClient): JsonObject {
        val c = config()
        val cart = sqlClient.preparedQuery(
            "SELECT COALESCE(SUM(i.`quantity`), 0) AS n FROM `${prefix}market_cart_item` i JOIN `${prefix}market_cart` c ON c.`id` = i.`cartId` WHERE c.`userId` = ?"
        ).execute(Tuple.of(userId)).coAwait().first().getLong("n")
        val subscriptions = sqlClient.preparedQuery(
            "SELECT COUNT(*) AS total, COALESCE(SUM(`status` IN ('ACTIVE', 'PAST_DUE', 'PAUSED')), 0) AS active FROM `${prefix}market_subscription` WHERE `userId` = ?"
        ).execute(Tuple.of(userId)).coAwait().first()
        val creator = sqlClient.preparedQuery(
            "SELECT 1 AS found FROM `${prefix}market_creator_code` WHERE `creatorUserId` = ? AND `deletedAt` IS NULL LIMIT 1"
        ).execute(Tuple.of(userId)).coAwait().size() > 0

        return JsonObject()
            .put("creditsEnabled", c.creditsEnabled)
            .put("creditBalance", MoneyUtil.toDecimal(credits.balance(userId, sqlClient)))
            .put("creditName", c.creditName)
            .put("cartItemCount", cart)
            .put("activeSubscriptionCount", subscriptions.getLong("active"))
            .put("subscriptionCount", subscriptions.getLong("total"))
            .put("isCreator", creator)
    }

    private fun entry(e: BuyerLedgerEntry) = JsonObject()
        .put("id", e.id)
        .put("type", e.type.name)
        .put("amount", MoneyUtil.toDecimal(e.amount))
        .put("balanceAfter", MoneyUtil.toDecimal(e.balanceAfter))
        .put("note", e.note)
        .put("orderPublicId", e.orderPublicId)
        .put("createdAt", e.createdAt)
}
