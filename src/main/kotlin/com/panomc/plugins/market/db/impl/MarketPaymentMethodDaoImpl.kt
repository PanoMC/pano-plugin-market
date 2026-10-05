package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketPaymentMethodDao
import com.panomc.plugins.market.db.model.MarketPaymentMethod
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketPaymentMethodDaoImpl : MarketPaymentMethodDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PAYMENT_METHOD, prefix())
    }

    override suspend fun getAll(sqlClient: SqlClient): List<MarketPaymentMethod> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}`"
        val rows = sqlClient.query(query).execute().coAwait()
        return rows.toEntities()
    }

    override suspend fun getByMethodId(methodId: String, sqlClient: SqlClient): MarketPaymentMethod? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `methodId` = ?"
        val rows = sqlClient.preparedQuery(query).execute(Tuple.of(methodId)).coAwait()
        return rows.toEntities().getOrNull(0)
    }

    override suspend fun upsertByMethodId(
        methodId: String,
        enabled: Boolean,
        settings: String,
        sqlClient: SqlClient
    ) {
        val now = System.currentTimeMillis()
        val query = """
            INSERT INTO `${prefix() + tableName}` (`methodId`, `enabled`, `settings`, `createdAt`, `updatedAt`)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE `enabled` = VALUES(`enabled`), `settings` = VALUES(`settings`), `updatedAt` = VALUES(`updatedAt`)
        """.trimIndent()
        sqlClient.preparedQuery(query).execute(Tuple.of(methodId, enabled, settings, now, now)).coAwait()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${prefix() + tableName}`").execute().coAwait()
    }
}
