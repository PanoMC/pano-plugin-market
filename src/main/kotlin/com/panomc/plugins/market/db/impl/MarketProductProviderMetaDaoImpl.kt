package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketProductProviderMetaDao
import com.panomc.plugins.market.db.model.MarketProductProviderMeta
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
class MarketProductProviderMetaDaoImpl : MarketProductProviderMetaDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PRODUCT_PROVIDER_META, prefix())
    }

    override suspend fun upsert(meta: MarketProductProviderMeta, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`productId`, `variantId`, `providerId`, `meta`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `meta` = VALUES(`meta`), `updatedAt` = VALUES(`updatedAt`), `id` = LAST_INSERT_ID(`id`)"

        return sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.tuple()
                    .addValue(meta.productId)
                    .addValue(meta.variantId)
                    .addValue(meta.providerId)
                    .addValue(meta.meta)
                    .addValue(meta.createdAt)
                    .addValue(meta.updatedAt)
            )
            .coAwait()
            .property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun deleteById(id: Long, sqlClient: SqlClient) {
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
    }

    override suspend fun delete(productId: Long, variantId: Long, providerId: String, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `productId` = ? AND `variantId` = ? AND `providerId` = ?")
            .execute(Tuple.of(productId, variantId, providerId))
            .coAwait()
            .rowCount()

    override suspend fun deleteByProductId(productId: Long, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `productId` = ?")
            .execute(Tuple.of(productId))
            .coAwait()
            .rowCount()

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketProductProviderMeta? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun get(productId: Long, variantId: Long, providerId: String, sqlClient: SqlClient): MarketProductProviderMeta? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ? AND `variantId` = ? AND `providerId` = ?"
            )
            .execute(Tuple.of(productId, variantId, providerId))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByProductId(productId: Long, sqlClient: SqlClient): List<MarketProductProviderMeta> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `productId` = ? ORDER BY `variantId` ASC, `providerId` ASC"
            )
            .execute(Tuple.of(productId))
            .coAwait()

        return rows.toEntities()
    }

    override suspend fun getByProviderId(providerId: String, sqlClient: SqlClient): List<MarketProductProviderMeta> {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery(
                "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `providerId` = ? ORDER BY `productId` ASC, `variantId` ASC"
            )
            .execute(Tuple.of(providerId))
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
