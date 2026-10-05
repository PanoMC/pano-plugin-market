package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketShipmentItemDao
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
class MarketShipmentItemDaoImpl : MarketShipmentItemDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SHIPMENT_ITEM, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketShipmentItem? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketShipmentItem> =
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

    override suspend fun add(item: MarketShipmentItem, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`shipmentId`, `orderItemId`, `quantity`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(item.shipmentId)
            .addValue(item.orderItemId)
            .addValue(item.quantity)
            .addValue(item.createdAt)
            .addValue(item.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketShipmentItem? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun update(item: MarketShipmentItem, sqlClient: SqlClient): Boolean {
        val values = Tuple.tuple()
            .addValue(item.quantity)
            .addValue(item.updatedAt)
            .addValue(item.id)
        return try {
            change("`quantity` = ?, `updatedAt` = ?", "`id` = ?", values, sqlClient) > 0
        } catch (e: Exception) {
            if (e.isDuplicateKey()) false else throw e
        }
    }

    override suspend fun getByShipmentId(shipmentId: Long, sqlClient: SqlClient): List<MarketShipmentItem> =
        many("`shipmentId` = ?", "`id` ASC", Tuple.of(shipmentId), sqlClient)

    override suspend fun getByOrderItemId(orderItemId: Long, sqlClient: SqlClient): List<MarketShipmentItem> =
        many("`orderItemId` = ?", "`id` ASC", Tuple.of(orderItemId), sqlClient)

    override suspend fun deleteByShipmentId(shipmentId: Long, sqlClient: SqlClient): Int =
        remove("`shipmentId` = ?", Tuple.of(shipmentId), sqlClient)

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
