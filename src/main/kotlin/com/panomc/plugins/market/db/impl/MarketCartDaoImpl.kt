package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketCartDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.*
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
class MarketCartDaoImpl : MarketCartDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.CART, prefix())
    }

    override suspend fun add(cart: MarketCart, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`userId`, `currency`, `couponCode`, `creatorCode`, `recipientUsername`, `giftMessage`, `shippingAddressId`, `shippingMethodId`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(cart.userId)
            .addValue(cart.currency)
            .addValue(cart.couponCode)
            .addValue(cart.creatorCode)
            .addValue(cart.recipientUsername)
            .addValue(cart.giftMessage)
            .addValue(cart.shippingAddressId)
            .addValue(cart.shippingMethodId)
            .addValue(cart.createdAt)
            .addValue(cart.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCart? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByUserId(userId: Long, sqlClient: SqlClient): MarketCart? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `userId` = ?")
            .execute(Tuple.of(userId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun deleteById(id: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .rowCount() > 0

    override suspend fun ensure(userId: Long, now: Long, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`userId`, `createdAt`, `updatedAt`) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE `id` = LAST_INSERT_ID(`id`)"
        val result = sqlClient.preparedQuery(query).execute(Tuple.of(userId, now, now)).coAwait()
        val id = result.property(MySQLClient.LAST_INSERTED_ID)

        if (id != null && id > 0) return id

        return getByUserId(userId, sqlClient)!!.id
    }

    override suspend fun getByIdForUpdate(id: Long, sqlClient: SqlClient): MarketCart? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ? FOR UPDATE")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun updateFields(id: Long, changes: Map<String, Any?>, now: Long, sqlClient: SqlClient) {
        require(changes.keys.all { it in UPDATABLE }) { "unknown cart column in ${changes.keys}" }

        val columns = changes.keys.toList()
        val set = (columns.map { "`$it` = ?" } + "`updatedAt` = ?").joinToString(", ")
        val values = Tuple.tuple()

        columns.forEach { values.addValue(changes[it]) }
        values.addValue(now).addValue(id)

        sqlClient.preparedQuery("UPDATE `${prefix() + tableName}` SET $set WHERE `id` = ?").execute(values).coAwait()
    }

    override suspend fun clearFields(id: Long, now: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery(
                "UPDATE `${prefix() + tableName}` SET `couponCode` = NULL, `creatorCode` = NULL, `recipientUsername` = NULL, `giftMessage` = NULL, " +
                    "`shippingAddressId` = NULL, `shippingMethodId` = NULL, `updatedAt` = ? WHERE `id` = ?"
            )
            .execute(Tuple.of(now, id))
            .coAwait()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }

    private companion object {
        val UPDATABLE = setOf("currency", "couponCode", "creatorCode", "recipientUsername", "giftMessage", "shippingAddressId", "shippingMethodId")
    }
}
