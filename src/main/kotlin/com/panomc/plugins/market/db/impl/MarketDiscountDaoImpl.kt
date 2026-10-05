package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketDiscountDao
import com.panomc.plugins.market.db.model.MarketDiscount
import io.vertx.core.json.JsonArray
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
class MarketDiscountDaoImpl : MarketDiscountDao() {
    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.DISCOUNT, prefix())
    }

    override suspend fun add(discount: MarketDiscount, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`name`, `value`, `unit`, `minPaymentAmount`, `scope`, `productIds`, `categoryIds`, `startDate`, `expiryDate`, `usageLimit`, `usedCount`, `status`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    discount.name,
                    discount.value,
                    discount.unit.name,
                    discount.minPaymentAmount,
                    discount.scope.name,
                    discount.productIds?.let { JsonArray(it).encode() },
                    discount.categoryIds?.let { JsonArray(it).encode() },
                    discount.startDate,
                    discount.expiryDate,
                    discount.usageLimit,
                    discount.usedCount,
                    discount.status.name,
                    discount.createdAt,
                    discount.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    // The counters (usedCount) are not written by the generic update (00 section 8.3, bug 2 of the backend map):
    // they change only through guarded atomic statements, never from a stale read.
    override suspend fun update(discount: MarketDiscount, sqlClient: SqlClient) {
        val query =
            "UPDATE `${prefix() + tableName}` SET `name` = ?, `value` = ?, `unit` = ?, `minPaymentAmount` = ?, `scope` = ?, `productIds` = ?, `categoryIds` = ?, `startDate` = ?, `expiryDate` = ?, `usageLimit` = ?, `status` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    discount.name,
                    discount.value,
                    discount.unit.name,
                    discount.minPaymentAmount,
                    discount.scope.name,
                    discount.productIds?.let { JsonArray(it).encode() },
                    discount.categoryIds?.let { JsonArray(it).encode() },
                    discount.startDate,
                    discount.expiryDate,
                    discount.usageLimit,
                    discount.status.name,
                    discount.updatedAt,
                    discount.id
                )
            )
            .coAwait()
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketDiscount? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketDiscount> {
        val offset = (page - 1) * 10
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (!status.isNullOrBlank()) {
            query.append(" AND `status` = ?")
            params.addString(status)
        }

        if (!search.isNullOrBlank()) {
            query.append(" AND `name` LIKE ?")
            params.addString("%$search%")
        }

        query.append(" ORDER BY `id` DESC LIMIT 10 OFFSET ?")
        params.addLong(offset)

        val rows: RowSet<Row> = sqlClient.preparedQuery(query.toString()).execute(params).coAwait()
        return rows.toEntities()
    }

    override suspend fun count(search: String?, status: String?, sqlClient: SqlClient): Long {
        val query = StringBuilder("SELECT COUNT(`id`) FROM `${prefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (!status.isNullOrBlank()) {
            query.append(" AND `status` = ?")
            params.addString(status)
        }

        if (!search.isNullOrBlank()) {
            query.append(" AND `name` LIKE ?")
            params.addString("%$search%")
        }

        val rows: RowSet<Row> = sqlClient.preparedQuery(query.toString()).execute(params).coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${prefix() + tableName}`").execute().coAwait()
    }
}
