package com.panomc.plugins.market.mc.core.support

import com.panomc.plugins.market.mc.core.store.StateStore
import com.panomc.plugins.market.mc.core.store.StoreOptions
import com.panomc.plugins.market.mc.core.sync.ApplyOutcome
import com.panomc.plugins.market.mc.core.sync.DeliveryEngine
import com.panomc.plugins.market.mc.core.sync.EngineOptions
import com.panomc.plugins.market.mc.core.wire.MarketSyncMessage
import com.panomc.plugins.market.mc.core.wire.MarketSyncRequest
import com.panomc.plugins.market.mc.core.wire.SyncResult
import java.nio.file.Path

/** A store + engine on a real temp directory with fake platform, clock and callbacks; restartable. */
class EngineHarness(
    val dir: Path,
    val version: String = "1.4.0",
    val storeOptions: StoreOptions = StoreOptions(),
    val engineOptions: EngineOptions = EngineOptions(),
    val clock: TestClock = TestClock(),
    val log: TestLog = TestLog(),
    val platform: FakeMcPlatform = FakeMcPlatform(),
    val settings: TestSettings = TestSettings(),
    val callbacks: RecordingCallbacks = RecordingCallbacks()
) {
    var store: StateStore = StateStore.open(dir, clock, log, storeOptions)
        private set
    var engine: DeliveryEngine = newEngine()
        private set

    private fun newEngine() = DeliveryEngine(store, platform, settings, clock, log, callbacks, version, engineOptions)

    /** One round trip: build the request, apply [response] to it. Returns the request that was sent. */
    fun sync(response: MarketSyncMessage): MarketSyncRequest = syncOutcome(response).first

    fun syncOutcome(response: MarketSyncMessage): Pair<MarketSyncRequest, ApplyOutcome> {
        val prepared = engine.buildSyncRequest()
        val outcome = engine.applyResponse(prepared, response)
        return prepared.request to outcome
    }

    /** The next request without answering it. */
    fun request(): MarketSyncRequest = engine.buildSyncRequest().request

    fun result(key: String): SyncResult? = request().results.firstOrNull { it.key == key }

    /** A clean shutdown (compacts the journal) and a new start on the same directory. */
    fun restart() {
        store.close()
        reopen()
    }

    /** A crash: the files are exactly as they are now, nothing is flushed or compacted. */
    fun crash() = reopen()

    private fun reopen() {
        store = StateStore.open(dir, clock, log, storeOptions)
        engine = newEngine()
    }

    /** Replaces the store (and engine) by one opened from another directory state, e.g. a copy taken mid-dispatch. */
    fun openCopy(copy: Path) {
        store = StateStore.open(copy, clock, log, storeOptions)
        engine = newEngine()
    }
}
