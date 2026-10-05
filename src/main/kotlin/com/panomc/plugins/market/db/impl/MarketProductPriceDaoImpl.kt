package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketProductPriceDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketProductPrice
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
class MarketProductPriceDaoImpl : MarketProductPriceDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PRODUCT_PRICE, prefix())
    }

    private fun insertValues(price: MarketProductPrice): Tuple = Tuple.tuple()
        .addValue(price.productId)
        .addValue(price.variantId)
        .addValue(price.currency)
        .addValue(price.price)
        .addValue(price.compareAtPrice)
        .addValue(price.createdAt)
        .addValue(price.updatedAt)

    override suspend fun add(price: MarketProductPrice, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`productId`, `variantId`, `currency`, `price`, `compareAtPrice`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?)"

        return try {
            sqlClient.preparedQuery(query).execute(insertValues(price)).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun upsert(price: MarketProductPrice, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`productId`, `variantId`, `currency`, `price`, `compareAtPrice`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `price` = VALUES(`price`), `compareAtPrice` = VALUES(`compareAtPrice`), `updatedAt` = VALUES(`updatedAt`), `id` = LAST_INSERT_ID(`id`)"

        return sqlClient.preparedQuery(query).execute(insertValues(price)).coAwait().property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun update(price: MarketProductPrice, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `price` = ?, `compareAtPrice` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.tuple().addValue(price.price).addValue(price.compareAtPrice).addValue(price.updatedAt).addValue(price.id))
            .coAwait()
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun deleteByProductId(productId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `productId` = ?")
            .execute(Tuple.of(productId))
            .coAwait()
            .rowCount()

    override suspend fun deleteByVariantId(variantId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `variantId` = ?")
            .execute(Tuple.of(variantId))
            .coAwait()
            .rowCount()

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketProductPrice? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun get(productId: Long, variantId: Long, currency: String, sqlClient: SqlClient): MarketProductPrice? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ? AND `variantId` = ? AND `currency` = ?"
            )
            .execute(Tuple.of(productId, variantId, currency))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByProductId(productId: Long, sqlClient: SqlClient): List<MarketProductPrice> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ? ORDER BY `variantId` ASC, `currency` ASC"
            )
            .execute(Tuple.of(productId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByProductAndVariant(productId: Long, variantId: Long, sqlClient: SqlClient): List<MarketProductPrice> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ? AND `variantId` = ? ORDER BY `currency` ASC"
            )
            .execute(Tuple.of(productId, variantId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }

    override suspend fun getAll(sqlClient: SqlClient): List<MarketProductPrice> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` ORDER BY `productId` ASC, `variantId` ASC, `currency` ASC"
            )
            .execute()
            .coAwait()

        return rows.toEntities()
    }
}
