package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketBundleItemDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.MarketBundleItem
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
class MarketBundleItemDaoImpl : MarketBundleItemDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.BUNDLE_ITEM, prefix())
    }

    override suspend fun add(item: MarketBundleItem, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`bundleProductId`, `productId`, `variantId`, `quantity`, `position`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?)"

        return try {
            sqlClient
                .preparedQuery(query)
                .execute(
                    Tuple.tuple()
                        .addValue(item.bundleProductId)
                        .addValue(item.productId)
                        .addValue(item.variantId)
                        .addValue(item.quantity)
                        .addValue(item.position)
                        .addValue(item.createdAt)
                        .addValue(item.updatedAt)
                )
                .coAwait()
                .property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun update(item: MarketBundleItem, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `quantity` = ?, `position` = ?, `updatedAt` = ? WHERE `id` = ?")
            .execute(Tuple.tuple().addValue(item.quantity).addValue(item.position).addValue(item.updatedAt).addValue(item.id))
            .coAwait()
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun deleteByBundleProductId(bundleProductId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `bundleProductId` = ?")
            .execute(Tuple.of(bundleProductId))
            .coAwait()
            .rowCount()

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketBundleItem? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByBundleProductId(bundleProductId: Long, sqlClient: SqlClient): List<MarketBundleItem> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `bundleProductId` = ? ORDER BY `position` ASC, `id` ASC"
            )
            .execute(Tuple.of(bundleProductId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByChildProductId(productId: Long, sqlClient: SqlClient): List<MarketBundleItem> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ? ORDER BY `bundleProductId` ASC, `id` ASC"
            )
            .execute(Tuple.of(productId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
