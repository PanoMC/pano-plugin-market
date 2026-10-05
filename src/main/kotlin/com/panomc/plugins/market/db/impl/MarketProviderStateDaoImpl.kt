package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketProviderStateDao
import com.panomc.plugins.market.db.dao.isDuplicateKey
import com.panomc.plugins.market.db.model.*
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLClient
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Scope

@Dao
@Lazy
@Scope(value = ConfigurableBeanFactory.SCOPE_SINGLETON)
class MarketProviderStateDaoImpl : MarketProviderStateDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.PROVIDER_STATE, prefix())
    }

    override suspend fun add(providerState: MarketProviderState, sqlClient: SqlClient): Long? {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`kind`, `providerId`, `stateKey`, `value`, `expiresAt`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?)"
        val values = Tuple.tuple()
            .addValue(providerState.kind.name)
            .addValue(providerState.providerId)
            .addValue(providerState.stateKey)
            .addValue(providerState.value)
            .addValue(providerState.expiresAt)
            .addValue(providerState.createdAt)
            .addValue(providerState.updatedAt)

        return try {
            sqlClient.preparedQuery(query).execute(values).coAwait().property(MySQLClient.LAST_INSERTED_ID)
        } catch (e: Exception) {
            if (e.isDuplicateKey()) null else throw e
        }
    }

    override suspend fun getById(id: Long, sqlClient: SqlClient): MarketProviderState? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `id` = ?")
            .execute(Tuple.of(id))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun get(kind: ProviderStateKind, providerId: String, stateKey: String, sqlClient: SqlClient): MarketProviderState? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `kind` = ? AND `providerId` = ? AND `stateKey` = ?")
            .execute(Tuple.of(kind.name, providerId, stateKey))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
