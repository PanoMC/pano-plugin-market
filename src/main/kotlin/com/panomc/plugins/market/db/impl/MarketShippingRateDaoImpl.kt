package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketShippingRateDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.*
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketShippingRateDaoImpl : MarketShippingRateDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SHIPPING_RATE, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketShippingRate? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketShippingRate> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where ORDER BY $order")
            .execute(values)
            .coAwait()
            .toEntities()

    private suspend fun change(set: String, where: String, values: Tuple, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET $set WHERE $where")
            .execute(values)
            .coAwait()
            .rowCount()

    private suspend fun remove(where: String, values: Tuple, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE $where")
            .execute(values)
            .coAwait()
            .rowCount()

    override suspend fun add(rate: MarketShippingRate, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`methodId`, `zoneId`, `basis`, `rangeFrom`, `rangeTo`, `price`, `perUnitPrice`, `position`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(rate.methodId)
            .addValue(rate.zoneId)
            .addValue(rate.basis.name)
            .addValue(rate.rangeFrom)
            .addValue(rate.rangeTo)
            .addValue(rate.price)
            .addValue(rate.perUnitPrice)
            .addValue(rate.position)
            .addValue(rate.createdAt)
            .addValue(rate.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketShippingRate? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun update(rate: MarketShippingRate, sqlClient: SqlClient): Boolean {
        val values = Tuple.tuple()
            .addValue(rate.methodId)
            .addValue(rate.zoneId)
            .addValue(rate.basis.name)
            .addValue(rate.rangeFrom)
            .addValue(rate.rangeTo)
            .addValue(rate.price)
            .addValue(rate.perUnitPrice)
            .addValue(rate.position)
            .addValue(rate.updatedAt)
            .addValue(rate.id)
        return try {
            change("`methodId` = ?, `zoneId` = ?, `basis` = ?, `rangeFrom` = ?, `rangeTo` = ?, `price` = ?, `perUnitPrice` = ?, `position` = ?, `updatedAt` = ?", "`id` = ?", values, sqlClient) > 0
        } catch (e: Exception) {
            if (e.isDuplicateKey()) false else throw e
        }
    }

    override suspend fun getByMethodId(methodId: Long, sqlClient: SqlClient): List<MarketShippingRate> =
        many("`methodId` = ?", "`zoneId` ASC, `position` ASC, `id` ASC", Tuple.of(methodId), sqlClient)

    override suspend fun getByMethodAndZone(methodId: Long, zoneId: Long, sqlClient: SqlClient): List<MarketShippingRate> =
        many("`methodId` = ? AND `zoneId` = ?", "`position` ASC, `id` ASC", Tuple.of(methodId, zoneId), sqlClient)

    override suspend fun delete(id: Long, sqlClient: SqlClient): Boolean = remove("`id` = ?", Tuple.of(id), sqlClient) > 0

    override suspend fun deleteByMethodId(methodId: Long, sqlClient: SqlClient): Int =
        remove("`methodId` = ?", Tuple.of(methodId), sqlClient)

    override suspend fun deleteByZoneId(zoneId: Long, sqlClient: SqlClient): Int =
        remove("`zoneId` = ?", Tuple.of(zoneId), sqlClient)

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
