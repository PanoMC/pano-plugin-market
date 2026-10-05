package com.panomc.plugins.market.db.dao

import io.vertx.mysqlclient.MySQLException

/** MariaDB / MySQL `ER_DUP_ENTRY`. */
private const val ER_DUP_ENTRY = 1062

/**
 * `true` when this failure is a unique-key violation (`ER_DUP_ENTRY`). A DAO whose table has a natural unique key
 * maps it to its documented result (`null` from `add`, `false` from `update`) instead of leaking the driver
 * exception; every other failure is rethrown.
 */
fun Throwable.isDuplicateKey(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth++ < 8) {
        if (current is MySQLException && current.errorCode == ER_DUP_ENTRY) return true
        current = current.cause
    }
    return false
}
