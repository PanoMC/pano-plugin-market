package com.panomc.plugins.market.db.dao

import com.panomc.plugins.market.db.MarketDao
import com.panomc.plugins.market.db.model.MarketSequence
import io.vertx.sqlclient.SqlClient

/** Named counters and fixup markers (01 section 6.7). The invoice slice adds the gap-free `next`. */
abstract class MarketSequenceDao : MarketDao<MarketSequence>(MarketSequence::class.java) {
    /** The value of the counter [name], or `null` when there is no such row (a fixup marker has value 1). */
    abstract suspend fun getValue(name: String, sqlClient: SqlClient): Long?
}
