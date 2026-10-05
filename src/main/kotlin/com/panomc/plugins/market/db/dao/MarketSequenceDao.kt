package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketSequence
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection

/** Named counters and fixup markers (01 section 6.7). */
abstract class MarketSequenceDao : MarketDao<MarketSequence>(MarketSequence::class.java) {
    /** The value of the counter [name], or `null` when there is no such row (a fixup marker has value 1). */
    abstract suspend fun getValue(name: String, sqlClient: SqlClient): Long?

    /**
     * The next value of the counter [name] (the first call answers 1). It must run on the connection of the
     * transaction that uses the number (`MarketDb.tx`): the row lock taken by the `UPDATE` is held until that
     * transaction ends, so concurrent callers queue up and a rolled-back transaction leaves no gap. Never call it on a
     * pool: `LAST_INSERT_ID()` is per connection.
     */
    abstract suspend fun next(name: String, connection: SqlConnection): Long
}
