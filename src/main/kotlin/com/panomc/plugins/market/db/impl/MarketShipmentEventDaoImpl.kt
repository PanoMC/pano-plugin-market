package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketShipmentEventDao
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
class MarketShipmentEventDaoImpl : MarketShipmentEventDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SHIPMENT_EVENT, prefix())
    }

    private suspend fun one(where: String, values: Tuple, sqlClient: SqlClient): MarketShipmentEvent? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE $where LIMIT 1")
            .execute(values)
            .coAwait()
            .toEntities()
            .getOrNull(0)

    private suspend fun many(where: String, order: String, values: Tuple, sqlClient: SqlClient): List<MarketShipmentEvent> =
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

    override suspend fun add(event: MarketShipmentEvent, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`shipmentId`, `status`, `rawStatus`, `description`, `location`, `occurredAt`, `source`, `dedupeKey`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(event.shipmentId)
            .addValue(event.status.name)
            .addValue(event.rawStatus)
            .addValue(event.description)
            .addValue(event.location)
            .addValue(event.occurredAt)
            .addValue(event.source.name)
            .addValue(event.dedupeKey)
            .addValue(event.createdAt)
            .addValue(event.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketShipmentEvent? = one("`id` = ?", Tuple.of(id), sqlClient)

    override suspend fun update(event: MarketShipmentEvent, sqlClient: SqlClient): Boolean {
        val values = Tuple.tuple()
            .addValue(event.rawStatus)
            .addValue(event.description)
            .addValue(event.location)
            .addValue(event.updatedAt)
            .addValue(event.id)
        return try {
            change("`rawStatus` = ?, `description` = ?, `location` = ?, `updatedAt` = ?", "`id` = ?", values, sqlClient) > 0
        } catch (e: Exception) {
            if (e.isDuplicateKey()) false else throw e
        }
    }

    override suspend fun getByShipmentId(shipmentId: Long, sqlClient: SqlClient): List<MarketShipmentEvent> =
        many("`shipmentId` = ?", "`occurredAt` ASC, `id` ASC", Tuple.of(shipmentId), sqlClient)

    override suspend fun getByDedupeKey(shipmentId: Long, dedupeKey: String, sqlClient: SqlClient): MarketShipmentEvent? =
        one("`shipmentId` = ? AND `dedupeKey` = ?", Tuple.of(shipmentId, dedupeKey), sqlClient)

    override suspend fun countByShipmentId(shipmentId: Long, sqlClient: SqlClient): Long =
        sqlClient
            .preparedQuery("SELECT COUNT(*) AS c FROM `${prefix() + tableName}` WHERE `shipmentId` = ?")
            .execute(Tuple.of(shipmentId))
            .coAwait()
            .first()
            .getLong("c")

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
