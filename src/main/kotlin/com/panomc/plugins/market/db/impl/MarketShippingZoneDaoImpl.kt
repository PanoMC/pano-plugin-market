package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketShippingZoneDao
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
class MarketShippingZoneDaoImpl : MarketShippingZoneDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SHIPPING_ZONE, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketShippingZone? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketShippingZone> =
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

    override suspend fun add(zone: MarketShippingZone, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`name`, `countries`, `regions`, `postalPatterns`, `position`, `status`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(zone.name)
            .addValue(zone.countries)
            .addValue(zone.regions)
            .addValue(zone.postalPatterns)
            .addValue(zone.position)
            .addValue(zone.status)
            .addValue(zone.createdAt)
            .addValue(zone.updatedAt)

        return sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketShippingZone? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun update(zone: MarketShippingZone, sqlClient: SqlClient): Boolean {
        val values = Tuple.tuple()
            .addValue(zone.name)
            .addValue(zone.countries)
            .addValue(zone.regions)
            .addValue(zone.postalPatterns)
            .addValue(zone.position)
            .addValue(zone.status)
            .addValue(zone.updatedAt)
            .addValue(zone.id)
        return try {
            change("`name` = ?, `countries` = ?, `regions` = ?, `postalPatterns` = ?, `position` = ?, `status` = ?, `updatedAt` = ?", "`id` = ?", values, sqlClient) > 0
        } catch (e: Exception) {
            if (e.isDuplicateKey()) false else throw e
        }
    }

    override suspend fun getAll(sqlClient: SqlClient): List<MarketShippingZone> =
        many("1 = 1", "`position` ASC, `id` ASC", Tuple.tuple(), sqlClient)

    override suspend fun getActive(sqlClient: SqlClient): List<MarketShippingZone> =
        many("`status` = ?", "`position` ASC, `id` ASC", Tuple.of("ACTIVE"), sqlClient)

    override suspend fun delete(id: Long, sqlClient: SqlClient): Boolean = remove("`id` = ?", Tuple.of(id), sqlClient) > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
