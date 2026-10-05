package com.panomc.plugins.market.e2e.support

import com.panomc.plugins.market.support.MarketTestDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Row
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/**
 * SQL on the database of the instance (17 section 8.3): used in T4 **only** for assertions, for row rewinds that make something due, and
 * for `emailVerified`; never to create business state. Tables are written without the `pano_` prefix.
 */
class E2eDb(val databaseName: String) : AutoCloseable {
    val pool: Pool

    init {
        E2eInstanceGuard.checkDatabase(databaseName)
        val connection = MarketTestDb.connectionFromEnv()
        pool = MySQLBuilder.pool()
            .with(PoolOptions().setMaxSize(6).setConnectionTimeout(30_000).setConnectionTimeoutUnit(TimeUnit.MILLISECONDS))
            .connectingTo(
                MySQLConnectOptions().setHost(connection.host).setPort(connection.port).setUser(connection.user)
                    .setPassword(connection.password).setDatabase(databaseName)
            )
            .using(MarketTestDb.vertx)
            .build()
    }

    fun sql(statement: String, vararg args: Any?): List<Row> = runBlocking { MarketTestDb.sql(pool, statement, *args) }

    /** `pano_<table>` rows matching [where] (without the `WHERE` keyword). */
    fun count(table: String, where: String? = null, vararg args: Any?): Long = runBlocking { MarketTestDb.count(pool, table, where, *args) }

    fun long(statement: String, vararg args: Any?): Long? = sql(statement, *args).firstOrNull()?.let { row -> row.getValue(0)?.let { (it as Number).toLong() } }

    fun string(statement: String, vararg args: Any?): String? = sql(statement, *args).firstOrNull()?.getValue(0)?.toString()

    /** Moves [column] of one row [deltaMs] into the past (`E2eDb.rewind`, 17 section 8.3), e.g. `market_order.expiresAt`. */
    fun rewind(table: String, id: Long, column: String, deltaMs: Long) {
        require(Regex("^[A-Za-z_]+$").matches(table) && Regex("^[A-Za-z]+$").matches(column)) { "bad table or column" }
        val changed = runBlocking {
            pool.preparedQuery("UPDATE `pano_$table` SET `$column` = `$column` - ? WHERE `id` = ?")
                .execute(io.vertx.sqlclient.Tuple.of(deltaMs, id)).coAwait().rowCount()
        }
        check(changed == 1) { "rewind of $table#$id.$column changed $changed rows" }
    }

    /** `emailVerified` is the one user column a scenario may write (17 section 8.3). */
    fun verifyEmail(userId: Long) {
        runBlocking { pool.preparedQuery("UPDATE `pano_user` SET `emailVerified` = 1 WHERE `id` = ?").execute(io.vertx.sqlclient.Tuple.of(userId)).coAwait() }
    }

    override fun close() {
        runBlocking { runCatching { pool.close().coAwait() } }
    }
}
