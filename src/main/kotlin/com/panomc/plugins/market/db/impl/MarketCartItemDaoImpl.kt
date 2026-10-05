package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketCartItemDao
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
class MarketCartItemDaoImpl : MarketCartItemDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.CART_ITEM, prefix())
    }

    override suspend fun add(item: MarketCartItem, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`cartId`, `productId`, `variantId`, `quantity`, `fieldValues`, `targetServerId`, `lineKey`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(item.cartId)
            .addValue(item.productId)
            .addValue(item.variantId)
            .addValue(item.quantity)
            .addValue(item.fieldValues)
            .addValue(item.targetServerId)
            .addValue(item.lineKey)
            .addValue(item.createdAt)
            .addValue(item.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCartItem? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByCartId(cartId: Long, sqlClient: SqlClient): List<MarketCartItem> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `cartId` = ? ORDER BY `id` ASC")
            .execute(Tuple.of(cartId))
            .coAwait()
            .toEntities()

    override suspend fun deleteByCartId(cartId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `cartId` = ?")
            .execute(Tuple.of(cartId))
            .coAwait()
            .rowCount()

    override suspend fun upsertAdd(item: MarketCartItem, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`cartId`, `productId`, `variantId`, `quantity`, `fieldValues`, `targetServerId`, `lineKey`, `createdAt`, `updatedAt`) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `quantity` = LEAST(`quantity` + VALUES(`quantity`), 999), `updatedAt` = VALUES(`updatedAt`), `id` = LAST_INSERT_ID(`id`)"
        val values = Tuple.tuple()
            .addValue(item.cartId)
            .addValue(item.productId)
            .addValue(item.variantId)
            .addValue(item.quantity)
            .addValue(item.fieldValues)
            .addValue(item.targetServerId)
            .addValue(item.lineKey)
            .addValue(item.createdAt)
            .addValue(item.updatedAt)

        val id = sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)

        if (id != null && id > 0) return id

        return getByCartIdAndLineKey(item.cartId, item.lineKey, sqlClient)!!.id
    }

    override suspend fun getByIdInCart(id: Long, cartId: Long, sqlClient: SqlClient): MarketCartItem? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ? AND `cartId` = ?")
            .execute(Tuple.of(id, cartId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getByCartIdAndLineKey(cartId: Long, lineKey: String, sqlClient: SqlClient): MarketCartItem? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `cartId` = ? AND `lineKey` = ?")
            .execute(Tuple.of(cartId, lineKey))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun countByCartId(cartId: Long, sqlClient: SqlClient): Long =
        sqlClient
            .preparedQuery("SELECT COUNT(*) AS c FROM `${prefix() + tableName}` WHERE `cartId` = ?")
            .execute(Tuple.of(cartId))
            .coAwait()
            .first()
            .getLong("c")

    override suspend fun setQuantity(id: Long, cartId: Long, quantity: Int, now: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `quantity` = ?, `updatedAt` = ? WHERE `id` = ? AND `cartId` = ?")
            .execute(Tuple.of(quantity, now, id, cartId))
            .coAwait()
            .rowCount() > 0

    override suspend fun deleteByIdInCart(id: Long, cartId: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ? AND `cartId` = ?")
            .execute(Tuple.of(id, cartId))
            .coAwait()
            .rowCount() > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
