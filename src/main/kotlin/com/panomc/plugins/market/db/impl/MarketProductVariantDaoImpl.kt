package com.panomc.plugins.market.db.impl

import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.model.MarketProductVariant
import com.panomc.platform.annotation.Dao
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
class MarketProductVariantDaoImpl : MarketProductVariantDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PRODUCT_VARIANT, prefix())
    }

    override suspend fun add(variant: MarketProductVariant, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`productId`, `name`, `sku`, `optionValues`, `attributes`, `price`, `creditPrice`, `compareAtPrice`, `stock`, `weightGrams`, `periodCount`, `imageFileName`, `position`, `status`, `deletedAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

        val rows: RowSet<Row> = sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.tuple()
                    .addValue(variant.productId)
                    .addValue(variant.name)
                    .addValue(variant.sku)
                    .addValue(variant.optionValues)
                    .addValue(variant.attributes)
                    .addValue(variant.price)
                    .addValue(variant.creditPrice)
                    .addValue(variant.compareAtPrice)
                    .addValue(variant.stock)
                    .addValue(variant.weightGrams)
                    .addValue(variant.periodCount)
                    .addValue(variant.imageFileName)
                    .addValue(variant.position)
                    .addValue(variant.status.name)
                    .addValue(variant.deletedAt)
                    .addValue(variant.createdAt)
                    .addValue(variant.updatedAt)
            )
            .coAwait()

        return rows.property(MySQLClient.LAST_INSERTED_ID)
    }

    // The counter (stock) and the soft-delete marker are not written by the generic update (00 section 8.3).
    override suspend fun update(variant: MarketProductVariant, sqlClient: SqlClient) {
        val query =
            "UPDATE `${prefix() + tableName}` SET `name` = ?, `sku` = ?, `optionValues` = ?, `attributes` = ?, `price` = ?, `creditPrice` = ?, `compareAtPrice` = ?, `weightGrams` = ?, `periodCount` = ?, `imageFileName` = ?, `position` = ?, `status` = ?, `updatedAt` = ? WHERE `id` = ?"

        sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.tuple()
                    .addValue(variant.name)
                    .addValue(variant.sku)
                    .addValue(variant.optionValues)
                    .addValue(variant.attributes)
                    .addValue(variant.price)
                    .addValue(variant.creditPrice)
                    .addValue(variant.compareAtPrice)
                    .addValue(variant.weightGrams)
                    .addValue(variant.periodCount)
                    .addValue(variant.imageFileName)
                    .addValue(variant.position)
                    .addValue(variant.status.name)
                    .addValue(variant.updatedAt)
                    .addValue(variant.id)
            )
            .coAwait()
    }

    override suspend fun setStock(id: Long, stock: Int?, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `stock` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.tuple().addValue(stock).addValue(System.currentTimeMillis()).addValue(id))
            .coAwait()
    }

    override suspend fun adjustStock(id: Long, delta: Int, sqlClient: SqlClient): Boolean {
        val rows = sqlClient
            .preparedQuery(
                "UPDATE `${prefix() + tableName}` SET `stock` = `stock` + ?, `updatedAt` = ? WHERE `id` = ? AND `stock` IS NOT NULL AND `stock` + ? >= 0 AND `stock` + ? <= ?"
            )
            .execute(
                Tuple.tuple()
                    .addLong(delta.toLong())
                    .addLong(System.currentTimeMillis())
                    .addLong(id)
                    .addLong(delta.toLong())
                    .addLong(delta.toLong())
                    .addLong(com.panomc.plugins.market.db.dao.MarketProductDao.MAX_STOCK.toLong())
            )
            .coAwait()
        return rows.rowCount() > 0
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun reserveStock(id: Long, quantity: Int, sqlClient: SqlClient): Boolean {
        require(quantity > 0) { "quantity must be positive" }
        val rows = sqlClient
            .preparedQuery(
                "UPDATE `${prefix() + tableName}` SET `stock` = `stock` - ?, `updatedAt` = ? WHERE `id` = ? AND `stock` IS NOT NULL AND `stock` >= ?"
            )
            .execute(Tuple.of(quantity, System.currentTimeMillis(), id, quantity))
            .coAwait()
        return rows.rowCount() > 0
    }

    override suspend fun releaseStock(id: Long, quantity: Int, sqlClient: SqlClient) {
        require(quantity > 0) { "quantity must be positive" }
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `stock` = `stock` + ?, `updatedAt` = ? WHERE `id` = ? AND `stock` IS NOT NULL")
            .execute(Tuple.of(quantity, System.currentTimeMillis(), id))
            .coAwait()
    }

    override suspend fun markDeleted(id: Long, deletedAt: Long, sqlClient: SqlClient): Boolean {
        val rows = sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `deletedAt` = ?, `updatedAt` = ? WHERE `id` = ? AND `deletedAt` IS NULL")
            .execute(Tuple.of(deletedAt, deletedAt, id))
            .coAwait()
        return rows.rowCount() > 0
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketProductVariant? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByIds(ids: List<Long>, sqlClient: SqlClient): List<MarketProductVariant> {
        if (ids.isEmpty()) return emptyList()

        val placeholders = ids.joinToString(", ") { "?" }
        val params = Tuple.tuple()
        ids.forEach { params.addLong(it) }

        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` IN ($placeholders) ORDER BY `id` ASC")
            .execute(params)
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByProductId(productId: Long, includeDeleted: Boolean, sqlClient: SqlClient): List<MarketProductVariant> {
        val deleted = if (includeDeleted) "" else " AND `deletedAt` IS NULL"
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ?$deleted ORDER BY `position` ASC, `id` ASC"
            )
            .execute(Tuple.of(productId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun countByProductId(productId: Long, includeDeleted: Boolean, sqlClient: SqlClient): Long {
        val deleted = if (includeDeleted) "" else " AND `deletedAt` IS NULL"
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT COUNT(`id`) FROM `${prefix() + tableName}` WHERE `productId` = ?$deleted")
            .execute(Tuple.of(productId))
            .coAwait()

        return rows.first().getLong(0)
    }

    override suspend fun deleteByProductId(productId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `productId` = ?")
            .execute(Tuple.of(productId))
            .coAwait()
            .rowCount()

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
