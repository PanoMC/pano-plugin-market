package com.panomc.plugins.market.config

import com.panomc.platform.annotation.Migration
import com.panomc.platform.api.config.PluginConfigMigration
import io.vertx.core.json.JsonObject

/**
 * Config version 1 -> 2 (00 section 12): only the version is bumped (by the manager). Every new key takes its Kotlin
 * default when the file is read, and every existing value is kept as it is.
 */
@Migration
class MarketConfigMigration1to2 : PluginConfigMigration(1, 2, "Market config version 2: new keys take their defaults") {
    override fun migrate(config: JsonObject) {
        // Nothing to rewrite: `currency` / `statsCurrency` keep their file representation (the ISO code).
    }
}
