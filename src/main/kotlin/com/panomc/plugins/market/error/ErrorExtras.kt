package com.panomc.plugins.market.error

/** Extras of an error body: pairs with a null value are left out (the optional extras of 04 section 11). */
internal fun extrasOf(vararg pairs: Pair<String, Any?>): Map<String, Any?> =
    pairs.filter { it.second != null }.toMap()
