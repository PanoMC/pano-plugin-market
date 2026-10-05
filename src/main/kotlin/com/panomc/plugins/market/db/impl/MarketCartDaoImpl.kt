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

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
