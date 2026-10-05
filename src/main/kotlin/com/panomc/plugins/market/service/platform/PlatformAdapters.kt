package com.panomc.plugins.market.service.platform

import com.panomc.platform.auth.PermissionManager
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PermissionNode
import com.panomc.platform.db.model.User
import com.panomc.platform.server.ServerManager
import com.panomc.platform.server.message.PermissionsSnapshotUpdatedMessage
import io.vertx.core.json.JsonObject
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The platform's `permission_node` table behind the [PermissionWriter] seam (08 section 7.2). The platform DAO has no per-holder query
 * (the optional D-P2 of 08 section 19), so a user's rows are the filtered full table; the table holds group and a few user rows, not
 * per-purchase data.
 */
internal class PlatformPermissionWriter(
    private val databaseManager: () -> DatabaseManager,
    private val permissionManager: () -> PermissionManager,
    private val serverManager: () -> ServerManager
) : PermissionWriter {
    override suspend fun userNodes(userId: Long, sqlClient: SqlClient): List<StoredPermissionNode> =
        databaseManager().permissionNodeDao.getPermissionNodes(sqlClient)
            .filter { it.holderType == PermissionNode.Companion.HolderType.USER && it.holderId == userId }
            .map { StoredPermissionNode(it.id, it.node, it.context, it.expiresAt, it.active) }
            .sortedBy { it.id }

    override suspend fun add(userId: Long, node: String, context: JsonObject, expiresAt: Long?, sqlClient: SqlClient): Long =
        databaseManager().permissionNodeDao.add(
            PermissionNode(holderType = PermissionNode.Companion.HolderType.USER, holderId = userId, node = node, active = true, context = context, expiresAt = expiresAt),
            sqlClient
        )

    override suspend fun deleteByIds(ids: List<Long>, sqlClient: SqlClient) {
        databaseManager().permissionNodeDao.deleteByIds(ids, sqlClient)
    }

    /** `PermissionManager.refresh()`, then the snapshot message to every connected server with `permissionIntegration` (the pattern of `PanelPermissionSnapshotSaveAPI`). */
    override suspend fun publish() {
        permissionManager().refresh()

        val message = PermissionsSnapshotUpdatedMessage()
        val servers = serverManager()

        for (server in servers.getConnectedServers().keys.filter { it.settings.permissionIntegration }) {
            try {
                servers.sendMessage(message, server)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                // a server that cannot be reached pulls the snapshot when it reconnects
                logger.warn("the permission snapshot message to server {} failed: {}", server.id, t.toString())
            }
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(PlatformPermissionWriter::class.java)
    }
}

/** Users the way `OnPlayerJoinEvent` creates them (08 section 4.1): no e-mail, empty IP and uuid; a concurrent creation (1062) is read back. */
internal class PlatformPlayerAccounts(private val databaseManager: () -> DatabaseManager) : PlayerAccounts {
    override suspend fun findOrCreate(username: String, sqlClient: SqlClient): Long? {
        if (username.isBlank()) return null

        val users = databaseManager().userDao

        users.getUserIdFromUsername(username, sqlClient)?.let { return it }

        return try {
            users.add(User(username = username, email = null, registeredIp = "", mcUuid = ""), null, sqlClient, false)
        } catch (e: MySQLException) {
            if (e.errorCode == 1062) users.getUserIdFromUsername(username, sqlClient) else throw e
        }
    }
}

/** Granted servers (a server the owner never accepted cannot take a delivery) and the live connections. */
internal class PlatformServerRoster(
    private val databaseManager: () -> DatabaseManager,
    private val serverManager: () -> ServerManager
) : ServerRoster {
    override suspend fun snapshot(sqlClient: SqlClient): ServerRoster.Roster {
        val granted = databaseManager().serverDao.getAllByPermissionGranted(sqlClient)

        return ServerRoster.Roster(
            granted = granted.map { it.id },
            connected = serverManager().getConnectedServers().keys.map { it.id },
            names = granted.associate { it.id to (it.customName?.takeIf { name -> name.isNotBlank() } ?: it.name) }
        )
    }
}
