package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
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

    private val orderTableName get() = getTablePrefix() + "market_order"

    override suspend fun init(sqlClient: SqlClient) {
        sqlClient
            .query(
                """
                            CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                              `id` bigint NOT NULL AUTO_INCREMENT,
                              `orderId` bigint NOT NULL,
                              `productId` bigint,
                              `productName` VARCHAR(255) NOT NULL,
                              `quantity` INT NOT NULL DEFAULT 1,
                              `unitPrice` BIGINT NOT NULL,
                              `createdAt` BIGINT(20) NOT NULL,
                              `updatedAt` BIGINT(20) NOT NULL,
                              PRIMARY KEY (`id`),
                              INDEX (`orderId`),
                              INDEX (`productId`)
                            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market order item table.';
                        """
            )
            .execute()
            .coAwait()
    }

    override suspend fun add(orderItem: MarketOrderItem, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${getTablePrefix() + tableName}` (`orderId`, `productId`, `productName`, `quantity`, `unitPrice`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    orderItem.orderId,
                    orderItem.productId,
                    orderItem.productName,
                    orderItem.quantity,
                    orderItem.unitPrice,
                    orderItem.createdAt,
                    orderItem.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getByOrderIds(orderIds: List<Long>, sqlClient: SqlClient): List<MarketOrderItem> {
        if (orderIds.isEmpty()) return emptyList()

        val placeholders = orderIds.joinToString(",") { "?" }
        val query =
            "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `orderId` IN ($placeholders) ORDER BY `id` ASC"

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
                    " FROM `${getTablePrefix() + tableName}` i INNER JOIN `$orderTableName` o ON i.`orderId` = o.`id`" +
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
                    " FROM `${getTablePrefix() + tableName}` i INNER JOIN `$orderTableName` o ON i.`orderId` = o.`id`" +
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
            .query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
