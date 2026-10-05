package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketOrderItemDao
import com.panomc.plugins.market.db.model.MarketOrderItem
import com.panomc.plugins.market.util.OrderStatus
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketOrderItemDaoImpl : MarketOrderItemDao() {

    private val orderTableName get() = prefix() + "market_order"

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.ORDER_ITEM, prefix())
    }

    override suspend fun add(orderItem: MarketOrderItem, sqlClient: SqlClient): Long {
        val columns = columnValues(orderItem)
        val query =
            "INSERT INTO `${prefix() + tableName}` (${columns.joinToString(", ") { "`${it.first}`" }}) VALUES (${columns.joinToString(", ") { "?" }})"

        val values = Tuple.tuple()
        columns.forEach { values.addValue(it.second) }

        val rows: RowSet<Row> = sqlClient.preparedQuery(query).execute(values).coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    /** Column name to bound value, in the order of the table (enums by name). */
    private fun columnValues(i: MarketOrderItem): List<Pair<String, Any?>> = listOf(
        "orderId" to i.orderId,
        "productId" to i.productId,
        "productName" to i.productName,
        "quantity" to i.quantity,
        "unitPrice" to i.unitPrice,
        "createdAt" to i.createdAt,
        "updatedAt" to i.updatedAt,
        "kind" to i.kind.name,
        "parentItemId" to i.parentItemId,
        "variantId" to i.variantId,
        "variantName" to i.variantName,
        "sku" to i.sku,
        "listUnitPrice" to i.listUnitPrice,
        "discountAmount" to i.discountAmount,
        "upgradeAmount" to i.upgradeAmount,
        "couponAmount" to i.couponAmount,
        "vatPercent" to i.vatPercent,
        "vatAmount" to i.vatAmount,
        "lineTotal" to i.lineTotal,
        "creditUnitPrice" to i.creditUnitPrice,
        "creditAmount" to i.creditAmount,
        "fieldValues" to i.fieldValues,
        "targetServerId" to i.targetServerId,
        "snapshot" to i.snapshot,
        "physical" to i.physical,
        "stockReserved" to i.stockReserved,
        "refundedQuantity" to i.refundedQuantity,
        "refundedAmount" to i.refundedAmount,
        "shippedQuantity" to i.shippedQuantity,
        "gatewayItemRef" to i.gatewayItemRef,
        "gatewayLineAmount" to i.gatewayLineAmount,
        "upgradeFromEntitlementId" to i.upgradeFromEntitlementId,
    )

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketOrderItem? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByOrderIds(orderIds: List<Long>, sqlClient: SqlClient): List<MarketOrderItem> {
        if (orderIds.isEmpty()) return emptyList()

        val placeholders = orderIds.joinToString(",") { "?" }
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `orderId` IN ($placeholders) ORDER BY `id` ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.from(orderIds))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun topProductsBetween(from: Long, to: Long, limit: Int, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): List<Pair<String, Double>> {
        // Per-order conversion factor: frozen rate if set, else the currency-based fallback. Bind
        // order: statsCurrency, salesCurrency, exchangeRate.
        val conversionFactor =
            "COALESCE(o.`exchangeRate`, CASE WHEN o.`currency` = ? THEN 1.0 WHEN o.`currency` = ? THEN ? ELSE 1.0 END)"

        val query =
            "SELECT i.`productName` AS name, COALESCE(SUM(i.`quantity` * i.`unitPrice` * $conversionFactor), 0) AS revenue" +
                    " FROM `${prefix() + tableName}` i INNER JOIN `$orderTableName` o ON i.`orderId` = o.`id`" +
                    " WHERE o.`status` = ? AND o.`createdAt` >= ? AND o.`createdAt` < ?" +
                    " GROUP BY i.`productName` ORDER BY revenue DESC LIMIT ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(statsCurrency, salesCurrency, exchangeRate, OrderStatus.COMPLETED.name, from, to, limit))
            .coAwait()

        return rows.map { row -> row.getString("name") to (row.getDouble("revenue") / 100.0) }
    }

    override suspend fun topProductIds(limit: Int, sqlClient: SqlClient): List<Long> {
        val query =
            "SELECT i.`productId` AS productId, SUM(i.`quantity`) AS sold" +
                    " FROM `${prefix() + tableName}` i INNER JOIN `$orderTableName` o ON i.`orderId` = o.`id`" +
                    " WHERE o.`status` = ? AND i.`productId` IS NOT NULL" +
                    " GROUP BY i.`productId` ORDER BY sold DESC LIMIT ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(OrderStatus.COMPLETED.name, limit))
            .coAwait()

        return rows.map { it.getLong("productId") }
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
