package com.panomc.plugins.market.service.platform

import io.vertx.sqlclient.SqlClient

/**
 * Thin seams over the platform tables and managers (00 section 5, `service.platform`): services take these instead of
 * reaching for `DatabaseManager` / `PermissionManager`, so a test wires an in-memory directory (17 section 4 S9).
 */
interface UserDirectory {
    /** A registered player by name, case-insensitively; the stored spelling comes back. */
    suspend fun byUsername(username: String, sqlClient: SqlClient): DirectoryUser?

    suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String?

    suspend fun emailOf(userId: Long, sqlClient: SqlClient): String?

    /** The platform permission check for a node of the user (global scope). A user that does not exist has no permission. */
    suspend fun hasPermission(userId: Long, node: String): Boolean
}

class DirectoryUser(val id: Long, val username: String)

/** The servers a delivery can target: which of the asked ids still exist. */
fun interface ServerDirectory {
    suspend fun existing(ids: Collection<Long>, sqlClient: SqlClient): Set<Long>
}
