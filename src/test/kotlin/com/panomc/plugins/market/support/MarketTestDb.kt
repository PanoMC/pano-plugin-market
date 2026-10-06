package com.panomc.plugins.market.support

import com.google.gson.GsonBuilder
import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.core.time.SystemClock
import io.vertx.core.Vertx
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLBuilder
import io.vertx.mysqlclient.MySQLConnectOptions
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PoolOptions
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Database helpers of tier T2 (17 section 5.1): connection from the environment, throwaway databases, the safety
 * guard, script loading, raw SQL helpers. The password is never logged or put into an exception message.
 *
 * Every database this object creates, drops or truncates must have a name starting with [NAME_PREFIX]; anything
 * else (the `pano` database in particular) is refused before a statement is sent.
 */
object MarketTestDb {
    const val NAME_PREFIX = "pano_market_it_"
    const val TABLE_PREFIX = "pano_"

    /** A throwaway database left by a crashed run is dropped once it is older than this. */
    const val STALE_AFTER_MS = 3_600_000L

    private const val COMMENT_TAG = "market-it:"
    private val NAME_REGEX = Regex("^pano_market_it_[A-Za-z0-9_]{1,40}$")
    private val counter = AtomicInteger()

    /** The one Vert.x instance of the test JVM; pools are closed per test class, this idles until the JVM exits. */
    val vertx: Vertx by lazy { Vertx.vertx() }

    /** Where the database runs. [toString] hides the password. */
    class Connection(val host: String, val port: Int, val user: String, internal val password: String) {
        override fun toString() = "$user@$host:$port"
    }

    /** Reads `PANO_IT_MARIADB` (`host:port`) and `PANO_IT_MARIADB_PASSWORD`; user is `root` (17 section 5.1). */
    fun connectionFromEnv(env: Map<String, String?> = System.getenv()): Connection {
        val address = env["PANO_IT_MARIADB"]
        require(!address.isNullOrBlank()) { "database tests need PANO_IT_MARIADB=host:port and PANO_IT_MARIADB_PASSWORD" }
        val parts = address.split(":")
        require(parts.size in 1..2 && parts[0].isNotBlank()) { "PANO_IT_MARIADB must look like host:port" }
        val port = if (parts.size == 2) parts[1].toIntOrNull() else 3306
        require(port != null && port in 1..65535) { "PANO_IT_MARIADB has no valid port" }
        return Connection(parts[0], port, "root", env["PANO_IT_MARIADB_PASSWORD"] ?: "")
    }

    // --- guard --------------------------------------------------------------------------------------------------

    /** `true` for a name this object may touch: [NAME_PREFIX] plus a short identifier of letters, digits, `_`. */
    fun isThrowawayName(name: String): Boolean = NAME_REGEX.matches(name)

    /** Returns [name] or throws before anything reaches the server (guard against the `pano` database). */
    fun requireThrowawayName(name: String): String {
        require(isThrowawayName(name)) {
            "refusing to touch database '$name': a test database name must start with $NAME_PREFIX"
        }
        return name
    }

    /** `pano_market_it_<pid>_<counter>` (17 section 5.1). */
    fun newDatabaseName(): String = "$NAME_PREFIX${ProcessHandle.current().pid()}_${counter.incrementAndGet()}"

    /** A database found on the server; [createdAtMs] is `null` when its age cannot be told. */
    class Candidate(val name: String, val createdAtMs: Long?)

    /**
     * The databases a cleanup may drop: prefix-guarded, age known and at least [olderThanMs]. A database of unknown
     * age is kept (it may belong to a run that is just starting); so is everything outside the prefix.
     */
    fun staleDatabases(candidates: List<Candidate>, nowMs: Long, olderThanMs: Long = STALE_AFTER_MS): List<String> =
        candidates.filter { isThrowawayName(it.name) && it.createdAtMs != null && nowMs - it.createdAtMs >= olderThanMs }
            .map { it.name }

    // --- pools and databases ------------------------------------------------------------------------------------

    private fun options(connection: Connection, database: String?): MySQLConnectOptions =
        MySQLConnectOptions()
            .setHost(connection.host)
            .setPort(connection.port)
            .setUser(connection.user)
            .setPassword(connection.password)
            .also { if (database != null) it.setDatabase(requireThrowawayName(database)) }

    /** A small pool without a default database, used to create and drop the throwaway databases. */
    fun adminPool(connection: Connection = connectionFromEnv()): Pool =
        MySQLBuilder.pool().with(PoolOptions().setMaxSize(2)).connectingTo(options(connection, null)).using(vertx).build()

    /** A pool on one throwaway database. [connectionTimeoutMs] bounds the wait for a free connection. */
    fun pool(
        database: String,
        maxSize: Int = 16,
        connectionTimeoutMs: Int = 30_000,
        connection: Connection = connectionFromEnv()
    ): Pool = MySQLBuilder.pool()
        .with(
            PoolOptions().setMaxSize(maxSize)
                .setConnectionTimeout(connectionTimeoutMs).setConnectionTimeoutUnit(TimeUnit.MILLISECONDS)
        )
        .connectingTo(options(connection, database))
        .using(vertx)
        .build()

    /** `CREATE DATABASE ... utf8mb4 / utf8mb4_unicode_ci`, with the creation time in the schema comment. */
    suspend fun createDatabase(admin: Pool, name: String, clock: Clock = SystemClock) {
        requireThrowawayName(name)
        val create = "CREATE DATABASE `$name` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
        try {
            admin.query("$create COMMENT '$COMMENT_TAG${clock.now()}'").execute().coAwait()
        } catch (e: Exception) {
            // Servers before MariaDB 10.5 have no schema comment; the age then comes from the table creation times.
            admin.query(create).execute().coAwait()
        }
    }

    suspend fun dropDatabase(admin: Pool, name: String) {
        requireThrowawayName(name)
        admin.query("DROP DATABASE IF EXISTS `$name`").execute().coAwait()
    }

    /** Drops leftover throwaway databases of crashed runs (older than one hour); returns their names. */
    suspend fun dropStaleDatabases(admin: Pool, clock: Clock = SystemClock, olderThanMs: Long = STALE_AFTER_MS): List<String> {
        val found = admin.preparedQuery(
            "SELECT s.SCHEMA_NAME AS name, s.SCHEMA_COMMENT AS comment, " +
                "(SELECT UNIX_TIMESTAMP(MIN(t.CREATE_TIME)) * 1000 FROM information_schema.TABLES t " +
                "WHERE t.TABLE_SCHEMA = s.SCHEMA_NAME) AS tableTime " +
                "FROM information_schema.SCHEMATA s WHERE s.SCHEMA_NAME LIKE ?"
        ).execute(Tuple.of("pano\\_market\\_it\\_%")).coAwait().map { row ->
            val fromComment = row.getString("comment")?.takeIf { it.startsWith(COMMENT_TAG) }
                ?.removePrefix(COMMENT_TAG)?.toLongOrNull()
            Candidate(row.getString("name"), fromComment ?: row.getValue("tableTime")?.let { (it as Number).toLong() })
        }
        val stale = staleDatabases(found, clock.now(), olderThanMs)
        stale.forEach { dropDatabase(admin, it) }
        return stale
    }

    /** `DBEntity.gson` is a lateinit set by the platform's Spring config; set it once when nobody did (17 section 4). */
    fun ensureGson() {
        try {
            DBEntity.gson
        } catch (e: UninitializedPropertyAccessException) {
            DBEntity.gson = GsonBuilder().create()
        }
    }

    // --- tables -------------------------------------------------------------------------------------------------

    /** Rows of one table, kept so [resetMarketTables] can put back what the schema installer seeded. */
    class TableSnapshot(val columns: List<String>, val rows: List<List<Any?>>)

    /** Base tables of the current database whose name starts with `pano_market_`. */
    suspend fun marketTables(client: SqlClient): List<String> =
        client.preparedQuery(
            "SELECT TABLE_NAME AS name FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' AND TABLE_NAME LIKE ? ORDER BY TABLE_NAME"
        ).execute(Tuple.of("pano\\_market\\_%")).coAwait().map { it.getString("name") }

    /** Every `pano_market_*` table with its rows, taken right after the schema was installed. */
    suspend fun snapshotMarketTables(client: SqlClient): Map<String, TableSnapshot> =
        marketTables(client).associateWith { table ->
            val rows = client.query("SELECT * FROM `$table`").execute().coAwait()
            val columns = rows.columnsNames()
            TableSnapshot(columns, rows.map { row -> columns.indices.map { row.getValue(it) } })
        }

    /**
     * The subset of [tables] that is not pristine: non-empty (one UNION ALL of `LIMIT 1` probes, one round trip) or
     * empty with `AUTO_INCREMENT > 1` (read live from information_schema, MariaDB does not cache it). TRUNCATE is slow
     * in InnoDB (it recreates the tablespace), so the untouched tables are skipped.
     */
    private suspend fun dirtyTables(conn: SqlClient, tables: List<String>): List<String> {
        if (tables.isEmpty()) return tables
        val dirty = HashSet<String>()
        conn.query(
            tables.joinToString(" UNION ALL ") { "(SELECT '$it' AS name FROM `$it` LIMIT 1)" }
        ).execute().coAwait().forEach { dirty += it.getString("name") }
        conn.query(
            "SELECT TABLE_NAME AS name FROM information_schema.TABLES " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE' AND AUTO_INCREMENT > 1"
        ).execute().coAwait().forEach { dirty += it.getString("name") }
        return tables.filter { it in dirty }
    }

    /**
     * Empties every `pano_market_*` table and puts the snapshot rows back (the five system credit accounts and the
     * other rows the schema installer seeds), so each test starts from a fresh install. Tables outside the market
     * prefix (platform stubs) are not touched.
     */
    suspend fun resetMarketTables(pool: Pool, baseline: Map<String, TableSnapshot>) {
        val conn = pool.connection.coAwait()
        try {
            conn.query("SET FOREIGN_KEY_CHECKS = 0").execute().coAwait()
            val tables = marketTables(conn)
            dirtyTables(conn, tables).forEach { conn.query("TRUNCATE TABLE `$it`").execute().coAwait() }
            for ((table, snapshot) in baseline) {
                if (table !in tables || snapshot.rows.isEmpty()) continue
                val columns = snapshot.columns.joinToString(", ") { "`$it`" }
                val marks = snapshot.columns.joinToString(", ") { "?" }
                conn.preparedQuery("INSERT INTO `$table` ($columns) VALUES ($marks)")
                    .executeBatch(snapshot.rows.map { Tuple.from(it) }).coAwait()
            }
        } finally {
            runCatching { conn.query("SET FOREIGN_KEY_CHECKS = 1").execute().coAwait() }
            runCatching { conn.close().coAwait() }
        }
    }

    /** Drops every table and view of the current database (the empty start of a migration test). */
    suspend fun dropAllTables(pool: Pool) {
        val conn = pool.connection.coAwait()
        try {
            conn.query("SET FOREIGN_KEY_CHECKS = 0").execute().coAwait()
            val objects = conn.query(
                "SELECT TABLE_NAME AS name, TABLE_TYPE AS type FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()"
            ).execute().coAwait().map { it.getString("name") to it.getString("type") }
            for ((name, type) in objects) {
                conn.query(if (type == "VIEW") "DROP VIEW `$name`" else "DROP TABLE `$name`").execute().coAwait()
            }
        } finally {
            runCatching { conn.query("SET FOREIGN_KEY_CHECKS = 1").execute().coAwait() }
            runCatching { conn.close().coAwait() }
        }
    }

    /** Minimal platform tables the SQL-level adapter tests read (17 section 5.1); service tests use fakes instead. */
    suspend fun createPlatformStubs(client: SqlClient) {
        client.query(
            "CREATE TABLE IF NOT EXISTS `${TABLE_PREFIX}user` (" +
                "`id` bigint NOT NULL AUTO_INCREMENT, `username` varchar(16) NOT NULL UNIQUE, `email` varchar(255) UNIQUE, " +
                "`emailVerified` TINYINT(1) NOT NULL DEFAULT 0, `registerDate` BIGINT(20) NOT NULL, PRIMARY KEY (`id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        ).execute().coAwait()
        client.query(
            "CREATE TABLE IF NOT EXISTS `${TABLE_PREFIX}server` (" +
                "`id` bigint NOT NULL AUTO_INCREMENT, `name` varchar(255) NOT NULL, PRIMARY KEY (`id`)" +
                ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
        ).execute().coAwait()
    }

    // --- raw SQL ------------------------------------------------------------------------------------------------

    /** Runs one statement; with arguments it is a prepared statement (`?` placeholders), without a plain query. */
    suspend fun sql(client: SqlClient, statement: String, vararg args: Any?): List<Row> =
        if (args.isEmpty()) client.query(statement).execute().coAwait().toList()
        else client.preparedQuery(statement).execute(Tuple.from(args.toList())).coAwait().toList()

    /** `SELECT COUNT(*)` on `pano_<table>`; [table] is given without the prefix, [where] without `WHERE`. */
    suspend fun count(client: SqlClient, table: String, where: String? = null, vararg args: Any?): Long {
        val statement = "SELECT COUNT(*) AS c FROM `$TABLE_PREFIX$table`" + (where?.let { " WHERE $it" } ?: "")
        return sql(client, statement, *args).first().getLong("c")
    }

    /** Runs every statement of [script] (see [SqlScript.split]) one after the other. */
    suspend fun runScript(client: SqlClient, script: String) {
        SqlScript.split(script).forEach { client.query(it).execute().coAwait() }
    }
}

/** Splits a `.sql` file into statements: `;` ends one, but not inside quotes, backticks or comments. */
object SqlScript {
    fun split(script: String): List<String> {
        val statements = ArrayList<String>()
        val current = StringBuilder()
        var i = 0
        val n = script.length

        fun flush() {
            val s = current.toString().trim()
            if (s.isNotEmpty()) statements += s
            current.setLength(0)
        }

        while (i < n) {
            val c = script[i]
            when {
                // -- comment (MySQL requires a blank or end of line after the dashes) and # comment
                (c == '-' && script.startsWith("--", i) && (i + 2 >= n || script[i + 2].isWhitespace())) || c == '#' -> {
                    while (i < n && script[i] != '\n') i++
                }
                c == '/' && script.startsWith("/*", i) -> {
                    val end = script.indexOf("*/", i + 2)
                    i = if (end < 0) n else end + 2
                    current.append(' ')
                }
                c == '\'' || c == '"' || c == '`' -> {
                    current.append(c)
                    i++
                    while (i < n) {
                        val d = script[i]
                        current.append(d)
                        i++
                        if (d == '\\' && c != '`' && i < n) {
                            current.append(script[i])
                            i++
                        } else if (d == c) {
                            if (i < n && script[i] == c) { // doubled quote stays inside the literal
                                current.append(script[i])
                                i++
                            } else {
                                break
                            }
                        }
                    }
                }
                c == ';' -> {
                    flush()
                    i++
                }
                else -> {
                    current.append(c)
                    i++
                }
            }
        }
        flush()
        return statements
    }
}

/**
 * Calls a suspend function of a Kotlin `object` that a later slice adds (`MarketSchema.ensure`,
 * `InvariantChecker.assertAll`), so the database base classes pick it up without being edited again.
 * An absent class is skipped; a present class whose function does not fit any argument variant is an error, never
 * a silent skip.
 */
internal object LateBound {
    /**
     * Tries the argument [variants] in order and invokes the first function `className.method(args..., Continuation)`
     * that accepts one. Returns `false` when the class does not exist yet.
     */
    suspend fun callFirst(className: String, method: String, variants: List<Array<Any?>>): Boolean {
        val type = try {
            Class.forName(className)
        } catch (e: ClassNotFoundException) {
            return false
        }
        val instance = type.getField("INSTANCE").get(null)
        for (args in variants) {
            val target = type.methods.singleOrNull { m ->
                m.name == method && m.parameterCount == args.size + 1 &&
                    Continuation::class.java.isAssignableFrom(m.parameterTypes.last()) &&
                    args.indices.all { i -> args[i]?.let { m.parameterTypes[i].kotlin.javaObjectType.isInstance(it) } ?: true }
            } ?: continue
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try {
                    target.invoke(instance, *args, continuation)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
            return true
        }
        error(
            "$className exists but has no suspend $method for any of the argument lists " +
                variants.map { v -> v.map { it?.javaClass?.simpleName } }
        )
    }

    suspend fun call(className: String, method: String, vararg args: Any?): Boolean =
        callFirst(className, method, listOf(arrayOf(*args)))
}
