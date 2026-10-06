package com.panomc.plugins.market.routes.panel.player

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.abuse.BlockValue
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketCreditAccountDao
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.model.SubscriptionStatus
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.api.checkout.PlatformUserDirectory
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.runtime.beans
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple

/**
 * `GET /players/:username/summary` (04 section 7): what the market knows about one player, for the player page's tab / card: `user{id, username}` (`null`
 * for a name that is no account: a guest buyer), `creditBalance`, `totals{orders, spent, refunded, currency}`, the latest [LATEST] `orders[]`, the
 * active `entitlements[]`, the `subscriptions[]` and the `blocks[]` that name the player.
 *
 * A player is addressed by the buyer / owner keys of 00 (`u:<userId>` and `g:<lower-cased name>`; a registered player is both, because an earlier guest
 * purchase under the same name still belongs to them). `totals` count paid, non-test orders the player *bought* in the store currency (`/ fxRate`);
 * `orders[]` also lists the gifts they received (`role`). No e-mail, address or IP is part of it (nothing here needs the PII tier of 11 section 14.5).
 */
class PlayerSummaryService(
    private val prefix: () -> String,
    private val users: UserDirectory,
    private val creditAccounts: MarketCreditAccountDao,
    private val config: () -> MarketConfig,
    private val clock: Clock,
    private val entitlementsOf: suspend (ownerKey: String, now: Long, client: SqlClient) -> List<EntitlementRow>
) {
    companion object {
        const val LATEST = 10
    }

    /** What the entitlement lookup returns for the summary (the DAO row without the columns that are not shown). */
    class EntitlementRow(
        val id: Long, val productId: Long, val variantId: Long, val orderId: Long, val subscriptionId: Long?, val quantity: Int, val startsAt: Long, val expiresAt: Long?
    )

    private fun table(name: String) = "`${prefix()}$name`"

    suspend fun summary(username: String, client: SqlClient): JsonObject {
        val name = BlockValue.normalize(com.panomc.plugins.market.core.abuse.BlockType.PLAYER, username) ?: throw RequestValueException("username", "INVALID")
        val user = users.byUsername(name, client)
        val keys = (listOfNotNull(user?.let { "u:${it.id}" }) + "g:$name").distinct()
        val now = clock.now()
        val cfg = config()

        val balance = user?.let { creditAccounts.getByUserId(it.id, client)?.balance } ?: 0L

        return JsonObject()
            .put("user", user?.let { JsonObject().put("id", it.id).put("username", it.username) })
            .put("creditBalance", MoneyUtil.toDecimal(balance))
            .put("totals", totals(keys, cfg, client))
            .put("orders", orders(keys, client))
            .put("entitlements", entitlements(keys, now, client))
            .put("subscriptions", subscriptions(keys, client))
            .put("blocks", blocks(name, user?.id, now, client))
    }

    private fun marks(keys: List<*>) = keys.joinToString(", ") { "?" }

    private suspend fun totals(keys: List<String>, cfg: MarketConfig, client: SqlClient): JsonObject {
        val row = client.preparedQuery(
            "SELECT COUNT(*) AS cnt, COALESCE(SUM(`totalPrice` / IF(`fxRate` > 0, `fxRate`, 1)), 0) AS spent, COALESCE(SUM(`refundedTotal` / IF(`fxRate` > 0, `fxRate`, 1)), 0) AS refunded " +
                "FROM ${table("market_order")} WHERE `buyerKey` IN (${marks(keys)}) AND `paidAt` IS NOT NULL AND `testMode` = 0"
        ).execute(Tuple.from(keys)).coAwait().first()

        return JsonObject()
            .put("orders", row.getLong("cnt"))
            .put("spent", Math.round(row.getDouble("spent")) / 100.0)
            .put("refunded", Math.round(row.getDouble("refunded")) / 100.0)
            .put("currency", cfg.currency.name)
    }

    private suspend fun orders(keys: List<String>, client: SqlClient): JsonArray {
        val rows = client.preparedQuery(
            "SELECT `id`, `publicId`, `status`, `source`, `totalPrice`, `currency`, `refundedTotal`, `isGift`, `testMode`, `createdAt`, `paidAt`, `buyerKey`, `recipientKey` " +
                "FROM ${table("market_order")} WHERE `buyerKey` IN (${marks(keys)}) OR `recipientKey` IN (${marks(keys)}) ORDER BY `createdAt` DESC, `id` DESC LIMIT ?"
        ).execute(Tuple.from(keys + keys + LATEST)).coAwait().toList()

        if (rows.isEmpty()) return JsonArray()

        val ids = rows.map { it.getLong("id") }
        val names = LinkedHashMap<Long, LinkedHashSet<String>>()

        client.preparedQuery("SELECT `orderId`, `productName` FROM ${table("market_order_item")} WHERE `kind` IN ('PRODUCT', 'BUNDLE') AND `orderId` IN (${marks(ids)}) ORDER BY `id`")
            .execute(Tuple.from(ids)).coAwait().forEach { names.getOrPut(it.getLong("orderId")) { LinkedHashSet() }.add(it.getString("productName")) }

        return JsonArray(rows.map { row ->
            JsonObject()
                .put("id", row.getLong("id")).put("publicId", row.getString("publicId")).put("status", row.getString("status")).put("source", row.getString("source"))
                .put("role", if (row.getString("buyerKey") in keys) "BUYER" else "RECIPIENT")
                .put("total", MoneyUtil.toDecimal(row.getLong("totalPrice"))).put("currency", row.getString("currency"))
                .put("refundedTotal", MoneyUtil.toDecimal(row.getLong("refundedTotal")))
                .put("isGift", row.getValue("isGift").let { (it as? Boolean) ?: ((it as? Number)?.toInt() == 1) })
                .put("testMode", row.getValue("testMode").let { (it as? Boolean) ?: ((it as? Number)?.toInt() == 1) })
                .put("productNames", JsonArray(names[row.getLong("id")]?.toList() ?: emptyList<String>()))
                .put("createdAt", row.getLong("createdAt")).put("paidAt", row.getLong("paidAt"))
        })
    }

    private suspend fun entitlements(keys: List<String>, now: Long, client: SqlClient): JsonArray {
        val rows = keys.flatMap { entitlementsOf(it, now, client) }.sortedBy { it.id }

        if (rows.isEmpty()) return JsonArray()

        val ids = rows.map { it.productId }.distinct()
        val names = HashMap<Long, String>()

        client.preparedQuery("SELECT `id`, `name` FROM ${table("market_product")} WHERE `id` IN (${marks(ids)})").execute(Tuple.from(ids)).coAwait()
            .forEach { names[it.getLong("id")] = it.getString("name") }

        return JsonArray(rows.map {
            JsonObject()
                .put("id", it.id).put("productId", it.productId).put("productName", names[it.productId]).put("variantId", it.variantId).put("orderId", it.orderId)
                .put("subscriptionId", it.subscriptionId).put("quantity", it.quantity).put("startsAt", it.startsAt).put("expiresAt", it.expiresAt)
        })
    }

    private suspend fun subscriptions(keys: List<String>, client: SqlClient): JsonArray {
        val rows = client.preparedQuery(
            "SELECT `id`, `productName`, `status`, `mode`, `price`, `currency`, `intervalUnit`, `intervalCount`, `currentPeriodEnd`, `nextChargeAt`, `cancelAtPeriodEnd`, `createdAt` " +
                "FROM ${table("market_subscription")} WHERE `ownerKey` IN (${marks(keys)}) AND `status` <> '${SubscriptionStatus.PENDING.name}' ORDER BY `createdAt` DESC, `id` DESC"
        ).execute(Tuple.from(keys)).coAwait()

        return JsonArray(rows.map { row ->
            JsonObject()
                .put("id", row.getLong("id")).put("productName", row.getString("productName")).put("status", row.getString("status")).put("mode", row.getString("mode"))
                .put("price", MoneyUtil.toDecimal(row.getLong("price"))).put("currency", row.getString("currency"))
                .put("intervalUnit", row.getString("intervalUnit")).put("intervalCount", row.getInteger("intervalCount"))
                .put("currentPeriodEnd", row.getLong("currentPeriodEnd")).put("nextChargeAt", row.getLong("nextChargeAt"))
                .put("cancelAtPeriodEnd", row.getValue("cancelAtPeriodEnd").let { (it as? Boolean) ?: ((it as? Number)?.toInt() == 1) })
                .put("createdAt", row.getLong("createdAt"))
        })
    }

    /** The active `PLAYER` block of the name and the active `USER` block of the account. */
    private suspend fun blocks(name: String, userId: Long?, now: Long, client: SqlClient): JsonArray {
        val args = ArrayList<Any?>()
        var match = "(`type` = 'PLAYER' AND `value` = ?)"

        args += name

        if (userId != null) {
            match += " OR (`type` = 'USER' AND `value` = ?)"
            args += userId.toString()
        }

        args += now

        val rows = client.preparedQuery(
            "SELECT `id`, `type`, `value`, `reason`, `source`, `orderId`, `hitCount`, `expiresAt`, `createdAt` FROM ${table("market_block")} " +
                "WHERE ($match) AND (`expiresAt` IS NULL OR `expiresAt` > ?) ORDER BY `id`"
        ).execute(Tuple.from(args)).coAwait()

        return JsonArray(rows.map { row ->
            JsonObject()
                .put("id", row.getLong("id")).put("type", row.getString("type")).put("value", row.getString("value")).put("reason", row.getString("reason"))
                .put("source", row.getString("source")).put("orderId", row.getLong("orderId")).put("hitCount", row.getInteger("hitCount"))
                .put("expiresAt", row.getLong("expiresAt")).put("createdAt", row.getLong("createdAt"))
        })
    }
}

private object PlayerWiringHolder

@Volatile
private var cachedSummary: Pair<MarketPlugin, PlayerSummaryService>? = null

internal fun playerSummaryService(plugin: MarketPlugin): PlayerSummaryService {
    cachedSummary?.takeIf { it.first === plugin }?.let { return it.second }

    return synchronized(PlayerWiringHolder) {
        cachedSummary?.takeIf { it.first === plugin }?.second ?: run {
            val context = plugin.beans
            val orders = context.getBean(MarketOrderDao::class.java)
            val entitlements = context.getBean(com.panomc.plugins.market.db.dao.MarketEntitlementDao::class.java)

            PlayerSummaryService(
                { orders.prefix() }, PlatformUserDirectory { context.getBean(DatabaseManager::class.java) }, context.getBean(MarketCreditAccountDao::class.java),
                { currentConfig(plugin) }, SystemClock
            ) { key, now, client ->
                entitlements.getActiveByOwner(key, now, client).map {
                    PlayerSummaryService.EntitlementRow(it.id, it.productId, it.variantId, it.orderId, it.subscriptionId, it.quantity, it.startsAt, it.expiresAt)
                }
            }.also { cachedSummary = plugin to it }
        }
    }
}

/**
 * `GET /api/panel/market/players/:username/summary` (`P:OV` or `P:PAY`, 04 section 7): see [PlayerSummaryService]. The name follows the admin-username rule of
 * 00 section 8.4 (`A-Z a-z 0-9 _ . *`, 1 to 32); anything else is 400 `BAD_REQUEST`.
 */
@Endpoint
class PanelGetPlayerSummaryAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/players/:username/summary", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_VIEW, MarketNode.PAYMENTS)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result =
        Successful(playerSummaryService(plugin).summary(context.pathParam("username") ?: "", databaseManager.getSqlClient()).map)
}
