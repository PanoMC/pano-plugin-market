package com.panomc.plugins.market.routes.api

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.error.NotFound
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.schema.EndpointDoc
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.routes.base.MarketApi
import com.panomc.plugins.market.util.ImageUtil
import com.panomc.plugins.market.util.StoreBackground
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import java.io.File

@Endpoint
class GetStoreBackgroundAPI(private val plugin: MarketPlugin) : MarketApi() {
    override val requiresStoreEnabled = false
    override val paths = listOf(Path("/store/background/:fileName", RouteType.GET))
    override val doc = EndpointDoc(summary = "The current store background image.", tag = "store", binary = true, errors = listOf(NotFound::class))

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).pathParameter(param("fileName", stringSchema())).build()

    override suspend fun handleMarket(context: RoutingContext): Result? {
        val name = getParameters(context).pathParameter("fileName")?.string ?: throw NotFound()
        if (!StoreBackground.isCurrentFile(name, configManager.config.storeBackgroundImage)) throw NotFound()
        val file = File(plugin.uploadsDir, name)
        if (!file.isFile) throw NotFound()
        context.response()
            .putHeader("Content-Type", ImageUtil.getSafeMimeType(name))
            .putHeader("Content-Disposition", "inline")
            .putHeader("X-Content-Type-Options", "nosniff")
            .putHeader("Cache-Control", "public, max-age=604800, immutable")
            .sendFile(file.absolutePath)
        return null
    }
}
