package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketShippingMethodDao
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
class MarketShippingMethodDaoImpl : MarketShippingMethodDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SHIPPING_METHOD, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketShippingMethod? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketShippingMethod> =
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

    override suspend fun add(method: MarketShippingMethod, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`name`, `description`, `providerId`, `serviceCode`, `rateSource`, `freeShippingThreshold`, `handlingFee`, `vatPercent`, `minDeliveryDays`, `maxDeliveryDays`, `maxWeightGrams`, `carrierName`, `trackingUrlTemplate`, `settings`, `position`, `status`, `deletedAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(method.name)
            .addValue(method.description)
            .addValue(method.providerId)
            .addValue(method.serviceCode)
            .addValue(method.rateSource.name)
            .addValue(method.freeShippingThreshold)
            .addValue(method.handlingFee)
            .addValue(method.vatPercent)
            .addValue(method.minDeliveryDays)
            .addValue(method.maxDeliveryDays)
            .addValue(method.maxWeightGrams)
            .addValue(method.carrierName)
            .addValue(method.trackingUrlTemplate)
            .addValue(method.settings)
            .addValue(method.position)
            .addValue(method.status)
            .addValue(method.deletedAt)
            .addValue(method.createdAt)
            .addValue(method.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketShippingMethod? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun update(method: MarketShippingMethod, sqlClient: SqlClient): Boolean {
        val values = Tuple.tuple()
            .addValue(method.name)
            .addValue(method.description)
            .addValue(method.providerId)
            .addValue(method.serviceCode)
            .addValue(method.rateSource.name)
            .addValue(method.freeShippingThreshold)
            .addValue(method.handlingFee)
            .addValue(method.vatPercent)
            .addValue(method.minDeliveryDays)
            .addValue(method.maxDeliveryDays)
            .addValue(method.maxWeightGrams)
            .addValue(method.carrierName)
            .addValue(method.trackingUrlTemplate)
            .addValue(method.settings)
            .addValue(method.position)
            .addValue(method.status)
            .addValue(method.updatedAt)
            .addValue(method.id)
        return try {
            change("`name` = ?, `description` = ?, `providerId` = ?, `serviceCode` = ?, `rateSource` = ?, `freeShippingThreshold` = ?, `handlingFee` = ?, `vatPercent` = ?, `minDeliveryDays` = ?, `maxDeliveryDays` = ?, `maxWeightGrams` = ?, `carrierName` = ?, `trackingUrlTemplate` = ?, `settings` = ?, `position` = ?, `status` = ?, `updatedAt` = ?", "`id` = ?", values, sqlClient) > 0
        } catch (e: Exception) {
            if (e.isDuplicateKey()) false else throw e
        }
    }

    override suspend fun getAll(sqlClient: SqlClient): List<MarketShippingMethod> =
        many("1 = 1", "`position` ASC, `id` ASC", Tuple.tuple(), sqlClient)

    override suspend fun getActive(sqlClient: SqlClient): List<MarketShippingMethod> =
        many("`status` = ? AND `deletedAt` IS NULL", "`position` ASC, `id` ASC", Tuple.of("ACTIVE"), sqlClient)

    override suspend fun softDelete(id: Long, now: Long, sqlClient: SqlClient): Boolean =
        change("`deletedAt` = ?, `updatedAt` = ?", "`id` = ? AND `deletedAt` IS NULL", Tuple.of(now, now, id), sqlClient) > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
