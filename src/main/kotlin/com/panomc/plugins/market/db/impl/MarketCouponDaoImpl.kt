package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketCouponDao
import com.panomc.plugins.market.db.model.MarketCoupon
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
class MarketCouponDaoImpl : MarketCouponDao() {
    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.COUPON, prefix())
    }

    override suspend fun add(coupon: MarketCoupon, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`name`, `code`, `scope`, `productIds`, `discount`, `unit`, `minPaymentAmount`, `startDate`, `expiryDate`, `redeemLimit`, `customerRedeemLimit`, `usedCount`, `status`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    coupon.name,
                    coupon.code,
                    coupon.scope.name,
                    coupon.productIds?.let { JsonArray(it).encode() },
                    coupon.discount,
                    coupon.unit.name,
                    coupon.minPaymentAmount,
                    coupon.startDate,
                    coupon.expiryDate,
                    coupon.redeemLimit,
                    coupon.customerRedeemLimit,
                    coupon.usedCount,
                    coupon.status.name,
                    coupon.createdAt,
                    coupon.updatedAt
                )
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    // The counters (usedCount) are not written by the generic update (00 section 8.3, bug 2 of the backend map):
    // they change only through guarded atomic statements, never from a stale read.
    override suspend fun update(coupon: MarketCoupon, sqlClient: SqlClient) {
        val query =
            "UPDATE `${prefix() + tableName}` SET `name` = ?, `code` = ?, `scope` = ?, `productIds` = ?, `discount` = ?, `unit` = ?, `minPaymentAmount` = ?, `startDate` = ?, `expiryDate` = ?, `redeemLimit` = ?, `customerRedeemLimit` = ?, `status` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.of(
                    coupon.name,
                    coupon.code,
                    coupon.scope.name,
                    coupon.productIds?.let { JsonArray(it).encode() },
                    coupon.discount,
                    coupon.unit.name,
                    coupon.minPaymentAmount,
                    coupon.startDate,
                    coupon.expiryDate,
                    coupon.redeemLimit,
                    coupon.customerRedeemLimit,
                    coupon.status.name,
                    coupon.updatedAt,
                    coupon.id
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

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCoupon? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByCode(code: String, sqlClient: SqlClient): MarketCoupon? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `code` = ?")
            .execute(Tuple.of(code))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAll(page: Long, search: String?, status: String?, sqlClient: SqlClient): List<MarketCoupon> {
        val offset = (page - 1) * 10
        val query = StringBuilder("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE 1=1")
        val params = Tuple.tuple()

        if (!status.isNullOrBlank()) {
            query.append(" AND `status` = ?")
            params.addString(status)
        }

        if (!search.isNullOrBlank()) {
            query.append(" AND (`code` LIKE ? OR `name` LIKE ?)")
            val searchParam = "%$search%"
            params.addString(searchParam)
            params.addString(searchParam)
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
            query.append(" AND (`code` LIKE ? OR `name` LIKE ?)")
            val searchParam = "%$search%"
            params.addString(searchParam)
            params.addString(searchParam)
        }

        val rows: RowSet<Row> = sqlClient.preparedQuery(query.toString()).execute(params).coAwait()

        if (rows.size() == 0) return 0L
        return rows.toList()[0].getLong(0)
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${prefix() + tableName}`").execute().coAwait()
    }
}
