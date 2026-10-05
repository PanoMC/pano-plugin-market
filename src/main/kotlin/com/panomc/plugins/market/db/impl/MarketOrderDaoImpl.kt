package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketOrderDao
import com.panomc.plugins.market.db.model.MarketOrder
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
class MarketOrderDaoImpl : MarketOrderDao() {

    private val itemTableName get() = prefix() + "market_order_item"

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.ORDER, prefix())
    }

    override suspend fun add(order: MarketOrder, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`userId`, `playerUsername`, `totalPrice`, `currency`, `paymentMethodId`, `paymentLabel`, `status`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    order.userId,
                    order.playerUsername,
                    order.totalPrice,
                    order.currency,
                    order.paymentMethodId,
                    order.paymentLabel,
                    order.status.name,
                    order.createdAt,
                    order.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getAllPaged(page: Long, search: String?, status: OrderStatus?, sqlClient: SqlClient): List<MarketOrder> {
        val offset = (page - 1) * 10
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` o WHERE 1=1")
        val params = Tuple.tuple()

        appendFilters(query, params, search, status)

        query.append(" ORDER BY `createdAt` DESC LIMIT 10 OFFSET ?")
        params.addLong(offset)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun count(search: String?, status: OrderStatus?, sqlClient: SqlClient): Long {
        val query = StringBuilder("SELECT COUNT(`id`) FROM `${prefix() + tableName}` o WHERE 1=1")
        val params = Tuple.tuple()

        appendFilters(query, params, search, status)

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query.toString())
            .execute(params)
            .coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    // Search matches playerUsername / order id / paymentLabel and any order item productName (EXISTS subquery).
    private fun appendFilters(query: StringBuilder, params: Tuple, search: String?, status: OrderStatus?) {
        if (status != null) {
            query.append(" AND `status` = ?")
            params.addString(status.name)
        }

        if (!search.isNullOrBlank()) {
            val like = "%$search%"
            query.append(
                " AND (`playerUsername` LIKE ? OR `paymentLabel` LIKE ? OR CAST(`id` AS CHAR) LIKE ?" +
                        " OR EXISTS (SELECT 1 FROM `$itemTableName` i WHERE i.`orderId` = o.`id` AND i.`productName` LIKE ?))"
            )
            params.addString(like)
            params.addString(like)
            params.addString(like)
            params.addString(like)
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketOrder? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun updateStatus(id: Long, status: OrderStatus, sqlClient: SqlClient) {
        val query = "UPDATE `${prefix() + tableName}` SET `status` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(status.name, System.currentTimeMillis(), id))
            .coAwait()
    }

    override suspend fun updateExchangeRate(id: Long, exchangeRate: Double, sqlClient: SqlClient) {
        val query = "UPDATE `${prefix() + tableName}` SET `exchangeRate` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(exchangeRate, System.currentTimeMillis(), id))
            .coAwait()
    }

    override suspend fun anonymizeByUserId(userId: Long, sqlClient: SqlClient) {
        val query = "UPDATE `${prefix() + tableName}` SET `userId` = NULL WHERE `userId` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(userId))
            .coAwait()
    }

    // Per-order conversion factor: frozen rate if set, otherwise the currency-based fallback
    // (statsCurrency -> 1.0, salesCurrency -> the current view rate, otherwise 1.0). Bind order:
    // statsCurrency, salesCurrency, exchangeRate.
    private val conversionFactor =
        "COALESCE(`exchangeRate`, CASE WHEN `currency` = ? THEN 1.0 WHEN `currency` = ? THEN ? ELSE 1.0 END)"

    override suspend fun countAndRevenueBetween(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Pair<Long, Double> {
        val query =
            "SELECT COUNT(`id`), COALESCE(SUM(`totalPrice` * $conversionFactor), 0) AS revenue FROM `${prefix() + tableName}` WHERE `status` = ? AND `createdAt` >= ? AND `createdAt` < ?"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(statsCurrency, salesCurrency, exchangeRate, OrderStatus.COMPLETED.name, from, to))
            .coAwait()

        val row = rows.toList()[0]
        return row.getLong(0) to (row.getDouble("revenue") / 100.0)
    }

    override suspend fun revenueByDay(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double> =
        revenueGrouped("%Y-%m-%d", from, to, statsCurrency, salesCurrency, exchangeRate, sqlClient)

    override suspend fun revenueByWeek(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double> =
        revenueGrouped("%x%v", from, to, statsCurrency, salesCurrency, exchangeRate, sqlClient)

    override suspend fun revenueByMonth(from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double> =
        revenueGrouped("%Y-%m", from, to, statsCurrency, salesCurrency, exchangeRate, sqlClient)

    private suspend fun revenueGrouped(format: String, from: Long, to: Long, statsCurrency: String, salesCurrency: String, exchangeRate: Double, sqlClient: SqlClient): Map<String, Double> {
        val query =
            "SELECT DATE_FORMAT(FROM_UNIXTIME(`createdAt` / 1000), '$format') AS bucket, COALESCE(SUM(`totalPrice` * $conversionFactor), 0) AS revenue" +
                    " FROM `${prefix() + tableName}` WHERE `status` = ? AND `createdAt` >= ? AND `createdAt` < ?" +
                    " GROUP BY bucket ORDER BY bucket ASC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(statsCurrency, salesCurrency, exchangeRate, OrderStatus.COMPLETED.name, from, to))
            .coAwait()

        val result = LinkedHashMap<String, Double>()
        rows.forEach { row -> result[row.getString("bucket")] = row.getDouble("revenue") / 100.0 }
        return result
    }

    override suspend fun paymentMethodDistribution(sqlClient: SqlClient): Map<String, Long> {
        val query =
            "SELECT `paymentLabel` AS bucket, COUNT(`id`) AS cnt FROM `${prefix() + tableName}` WHERE `status` = ? GROUP BY `paymentLabel` ORDER BY cnt DESC"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(Tuple.of(OrderStatus.COMPLETED.name))
            .coAwait()

        val result = LinkedHashMap<String, Long>()
        rows.forEach { row -> result[row.getString("bucket")] = row.getLong("cnt") }
        return result
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
