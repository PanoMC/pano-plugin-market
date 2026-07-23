package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
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
        sqlClient
            .query(
                """
                    CREATE TABLE IF NOT EXISTS `${getTablePrefix() + tableName}` (
                      `id` bigint NOT NULL AUTO_INCREMENT,
                      `methodId` VARCHAR(64) NOT NULL,
                      `enabled` tinyint(1) NOT NULL DEFAULT 0,
                      `settings` MEDIUMTEXT,
                      `createdAt` BIGINT(20) NOT NULL,
                      `updatedAt` BIGINT(20) NOT NULL,
                      PRIMARY KEY (`id`),
                      UNIQUE KEY `unique_method_id` (`methodId`)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Market payment methods table.';
                """
            )
            .execute()
            .coAwait()
    }

    override suspend fun getAll(sqlClient: SqlClient): List<MarketPaymentMethod> {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}`"
        val rows = sqlClient.query(query).execute().coAwait()
        return rows.toEntities()
    }

    override suspend fun getByMethodId(methodId: String, sqlClient: SqlClient): MarketPaymentMethod? {
        val query = "SELECT ${fields.toTableQuery()} FROM `${getTablePrefix() + tableName}` WHERE `methodId` = ?"
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
            INSERT INTO `${getTablePrefix() + tableName}` (`methodId`, `enabled`, `settings`, `createdAt`, `updatedAt`)
            VALUES (?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE `enabled` = VALUES(`enabled`), `settings` = VALUES(`settings`), `updatedAt` = VALUES(`updatedAt`)
        """.trimIndent()
        sqlClient.preparedQuery(query).execute(Tuple.of(methodId, enabled, settings, now, now)).coAwait()
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${getTablePrefix() + tableName}`").execute().coAwait()
    }
}
