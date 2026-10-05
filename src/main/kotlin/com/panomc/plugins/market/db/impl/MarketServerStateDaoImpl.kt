package com.panomc.plugins.market.db.impl

import com.panomc.platform.annotation.Dao
import com.panomc.plugins.market.db.MarketSchema
import com.panomc.plugins.market.db.dao.MarketServerStateDao
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
class MarketServerStateDaoImpl : MarketServerStateDao() {

    override suspend fun init(sqlClient: SqlClient) {
        // Never throws (01 section 14.1 rule 3): a failed CREATE is logged and left to MarketSchema.ensure / SchemaVerifier.
        MarketSchema.installTable(sqlClient, MarketSchema.SERVER_STATE, prefix())
    }

    override suspend fun upsertSync(state: MarketServerState, sqlClient: SqlClient) {
        val query =
            "INSERT INTO `${prefix() + tableName}` (`serverId`, `mcComponentVersion`, `capabilities`, `platform`, `protocol`, `queuedCount`, `lastSeenAt`, `settings`, `createdAt`, `updatedAt`) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE `mcComponentVersion` = VALUES(`mcComponentVersion`), `capabilities` = VALUES(`capabilities`), `platform` = VALUES(`platform`), `protocol` = VALUES(`protocol`), `queuedCount` = VALUES(`queuedCount`), `lastSeenAt` = VALUES(`lastSeenAt`), `updatedAt` = VALUES(`updatedAt`)"
        val values = Tuple.tuple()
            .addValue(state.serverId)
            .addValue(state.mcComponentVersion)
            .addValue(state.capabilities)
            .addValue(state.platform)
            .addValue(state.protocol)
            .addValue(state.queuedCount)
            .addValue(state.lastSeenAt)
            .addValue(state.settings)
            .addValue(state.createdAt)
            .addValue(state.updatedAt)

        sqlClient.preparedQuery(query).execute(values).coAwait()
    }

    override suspend fun getByServerId(serverId: Long, sqlClient: SqlClient): MarketServerState? =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` WHERE `serverId` = ?")
            .execute(Tuple.of(serverId))
            .coAwait()
            .toEntities()
            .getOrNull(0)

    override suspend fun getAll(sqlClient: SqlClient): List<MarketServerState> =
        sqlClient
            .preparedQuery("SELECT ${fields.toTableQuery()} FROM `${prefix() + tableName}` ORDER BY `serverId` ASC")
            .execute()
            .coAwait()
            .toEntities()

    override suspend fun updateSettings(serverId: Long, settings: String?, now: Long, sqlClient: SqlClient): Boolean =
        sqlClient
            .preparedQuery("UPDATE `${prefix() + tableName}` SET `settings` = ?, `updatedAt` = ? WHERE `serverId` = ?")
            .execute(Tuple.of(settings, now, serverId))
            .coAwait()
            .rowCount() > 0

    override suspend fun uninstall(sqlClient: SqlClient) {
        sqlClient
            .query("DROP TABLE IF EXISTS `${prefix() + tableName}`")
            .execute()
            .coAwait()
    }
}
