package com.panomc.plugins.market.mc.core.sync

import com.panomc.plugins.market.mc.core.store.DeliveryRecord

/**
 * What the engine tells the rest of the component. All calls run on the engine thread, so an implementation must be
 * quick and hand real work (chat, network) to the platform scheduler.
 */
interface EngineCallbacks {
    /** A delivery was executed, queued, expired or cancelled: the sync loop wakes early (08 section 8.2 rule 1). */
    fun onLocalChange() {}

    /**
     * `MARKET_SYNC.configHash` differs from [cachedConfigHash]: pull `MARKET_CONFIG` (19 section 6.2 step 6). Called
     * at most once per distinct hash and 30 s.
     */
    fun onConfigHashChanged(current: String) {}

    /** The `MARKET_CONFIG` hash the component currently holds (sent as `configHash`), `null` when it holds none. */
    fun cachedConfigHash(): String? = null

    /** A purchase broadcast to show, already de-duplicated by id and only while broadcasts are enabled locally. */
    fun showBroadcast(text: String) {}

    /** A player became present and these queued deliveries were run (or expired / failed) for them, in id order. */
    fun onQueueDrained(username: String, records: List<DeliveryRecord>) {}
}
