package com.panomc.plugins.market.error

/**
 * A database transaction hit a deadlock (MariaDB 1213) or a lock wait timeout (1205) on every one of its [attempts]
 * (00 section 8.2). Thrown by `MarketDb.tx` after the last attempt; the route layer maps it to 503 `STORE_BUSY` with
 * `Retry-After: 2` on buyer and panel routes and to 500 on inbound routes (the gateway redelivers).
 *
 * Deliberately not a platform `Error`: it is an infrastructure failure, not a request error. [cause] is the last
 * database exception (a `MySQLException` carrying the error code).
 */
class MarketBusyException(val attempts: Int, cause: Throwable?) :
    RuntimeException("database busy: transaction failed $attempts times on a deadlock or lock wait timeout", cause)
