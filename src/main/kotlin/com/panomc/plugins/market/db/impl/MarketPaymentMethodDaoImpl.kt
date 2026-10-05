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

    override suspend fun saveConfig(row: MarketPaymentMethod, sqlClient: SqlClient) {
        val query = """
            INSERT INTO `${prefix() + tableName}` (`methodId`, `enabled`, `settings`, `createdAt`, `updatedAt`, `position`, `customLabel`,
                `customDescription`, `feeMode`, `feePercent`, `feeFixed`, `minAmount`, `maxAmount`, `currencies`, `testMode`, `settingsUpdatedAt`)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE `enabled` = VALUES(`enabled`), `settings` = VALUES(`settings`), `updatedAt` = VALUES(`updatedAt`),
                `position` = VALUES(`position`), `customLabel` = VALUES(`customLabel`), `customDescription` = VALUES(`customDescription`),
                `feeMode` = VALUES(`feeMode`), `feePercent` = VALUES(`feePercent`), `feeFixed` = VALUES(`feeFixed`),
                `minAmount` = VALUES(`minAmount`), `maxAmount` = VALUES(`maxAmount`), `currencies` = VALUES(`currencies`),
                `testMode` = VALUES(`testMode`), `settingsUpdatedAt` = VALUES(`settingsUpdatedAt`)
        """.trimIndent()
        val tuple = Tuple.tuple()
            .addValue(row.methodId).addValue(row.enabled).addValue(row.settings).addValue(row.createdAt).addValue(row.updatedAt)
            .addValue(row.position).addValue(row.customLabel).addValue(row.customDescription).addValue(row.feeMode.name)
            .addValue(row.feePercent).addValue(row.feeFixed).addValue(row.minAmount).addValue(row.maxAmount).addValue(row.currencies)
            .addValue(row.testMode).addValue(row.settingsUpdatedAt)
        sqlClient.preparedQuery(query).execute(tuple).coAwait()
    }

    override suspend fun setLastError(methodId: String, error: String?, at: Long?, sqlClient: SqlClient): Boolean {
        val query = "UPDATE `${prefix() + tableName}` SET `lastError` = ?, `lastErrorAt` = ? WHERE `methodId` = ?"
        val result = sqlClient.preparedQuery(query).execute(Tuple.tuple().addValue(error?.take(512)).addValue(at).addValue(methodId)).coAwait()
        return result.rowCount() > 0
    }

    override suspend fun setPosition(methodId: String, position: Int, sqlClient: SqlClient): Boolean {
        val query = "UPDATE `${prefix() + tableName}` SET `position` = ? WHERE `methodId` = ?"
        val result = sqlClient.preparedQuery(query).execute(Tuple.of(position, methodId)).coAwait()
        return result.rowCount() > 0
    }

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient.query("DROP TABLE IF EXISTS `${prefix() + tableName}`").execute().coAwait()
    }
}
