package com.panomc.plugins.market.service.platform

import com.panomc.plugins.market.core.delivery.TargetResolver
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient

/**
 * One `permission_node` row of a user as the delivery engine reads it (08 section 7.2). A node is identified by the tuple
 * `(holder = USER, userId, node, context)`, never by [id] alone: a server-originated snapshot truncates and rewrites the
 * permission tables with new ids (`SavePermissionsSnapshotEvent`).
 */
class StoredPermissionNode(val id: Long, val node: String, val context: JsonObject, val expiresAt: Long?, val active: Boolean)

/**
 * The write seam over the platform's `permission_node` table (17 section 4 S9, 08 section 7.2). Every method runs on the
 * connection of the caller's market transaction, so a grant commits or rolls back together with the delivery row that
 * records it. [publish] is the only method that must never run inside a transaction.
 */
interface PermissionWriter {
    /** Every `USER` row of [userId], active or not, oldest id first. */
    suspend fun userNodes(userId: Long, sqlClient: SqlClient): List<StoredPermissionNode>

    /** Inserts an active `USER` row and answers its id. */
    suspend fun add(userId: Long, node: String, context: JsonObject, expiresAt: Long?, sqlClient: SqlClient): Long

    suspend fun deleteByIds(ids: List<Long>, sqlClient: SqlClient)

    /**
     * After the commit (08 section 7.2): the platform reloads its permission cache and tells every connected server with
     * `permissionIntegration` to pull the snapshot. Best effort, never throws: a server that is offline pulls on reconnect.
     */
    suspend fun publish()
}

/**
 * Players as accounts (08 section 4.1). `CREDIT` never calls this (no user => `NO_ACCOUNT`); `PERMISSION via=PANO` does: a
 * rank bought by a player who never registered is written for the account a join of that player would create.
 */
interface PlayerAccounts {
    /**
     * The id of the user named [username] (case-insensitive), created the way `OnPlayerJoinEvent` does (no e-mail, empty
     * IP and uuid) when there is none. A concurrent creation (MariaDB 1062) is read back. `null` for a name the platform
     * refuses.
     */
    suspend fun findOrCreate(username: String, sqlClient: SqlClient): Long?
}

/** The servers a delivery plan sees (08 section 4.2): granted ids, connected ids, names for `{server.name}`. */
fun interface ServerRoster {
    suspend fun snapshot(sqlClient: SqlClient): Roster

    /** [granted] = `serverDao.getAllByPermissionGranted()`; a server that is not granted cannot be targeted, so it is also the "exists" set. */
    class Roster(val granted: List<Long>, val connected: List<Long>, val names: Map<Long, String>) {
        fun lookup() = TargetResolver.ServerLookup(grantedIds = granted, existingIds = granted, connectedIds = connected)
    }

    companion object {
        val NONE = ServerRoster { Roster(emptyList(), emptyList(), emptyMap()) }
    }
}
