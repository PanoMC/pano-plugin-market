package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketCurrencyRateDao
import com.panomc.plugins.market.db.model.MarketCurrencyRate
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
class MarketCurrencyRateDaoImpl : MarketCurrencyRateDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.CURRENCY_RATE, prefix())
    }

    override suspend fun upsert(rate: MarketCurrencyRate, sqlClient: SqlClient): Long {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`currency`, `rate`, `mode`, `fetchedAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `rate` = VALUES(`rate`), `mode` = VALUES(`mode`), `fetchedAt` = VALUES(`fetchedAt`), `updatedAt` = VALUES(`updatedAt`), `id` = LAST_INSERT_ID(`id`)"

        return sqlClient
            .preparedQuery(query)
            .execute(
                Tuple.tuple()
                    .addValue(rate.currency)
                    .addValue(rate.rate)
                    .addValue(rate.mode.name)
                    .addValue(rate.fetchedAt)
                    .addValue(rate.createdAt)
                    .addValue(rate.updatedAt)
            )
            .coAwait()
            .property(MySQLClient.LAST_INSERTED_ID)
    }

    override suspend fun deleteByCurrency(currency: String, sqlClient: SqlClient): Int =
        sqlClient
            .preparedQuery("DELETE FROM `${prefix() + tableName}` WHERE `currency` = ?")
            .execute(Tuple.of(currency))
            .coAwait()
            .rowCount()

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketCurrencyRate? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getByCurrency(currency: String, sqlClient: SqlClient): MarketCurrencyRate? {
        val rows: RowSet<Row> = sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `currency` = ?")
            .execute(Tuple.of(currency))
            .coAwait()

        return rows.toEntities().getOrNull(0)
    }

    override suspend fun getAll(sqlClient: SqlClient): List<MarketCurrencyRate> {
        val rows: RowSet<Row> = sqlClient
            .query("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` ORDER BY `currency` ASC")
            .execute()
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
