package com.panomc.plugins.market.routes.panel.settings

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.api.config.PluginConfigManager
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.log.UpdatedMarketSettingsLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.util.ImageUtil
import com.panomc.plugins.market.util.StoreBackground
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

@Endpoint
class PanelUploadStoreBackgroundAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes = setOf(MarketNode.SETTINGS)
    override val paths = listOf(Path("/settings/store-background", RouteType.POST))

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create().setDeleteUploadedFilesOnEnd(true).setBodyLimit(StoreBackground.MAX_IMAGE_BYTES + 65536)

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.multipartFormData(objectSchema()))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) throw BadRequest()
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val upload = context.fileUploads().singleOrNull() ?: throw BadRequest()
        if (upload.name() != "image" || upload.size() > StoreBackground.MAX_IMAGE_BYTES) throw BadRequest()
        val source = File(upload.uploadedFileName())
        val extension = ImageUtil.detectImageExtension(source) ?: throw BadRequest()
        val name = StoreBackground.newFileName(extension)
        val target = File(plugin.uploadsDir, name)
        val old = configManager.config.storeBackgroundImage
        try {
            source.copyTo(target)
            configManager.saveConfig(JsonObject.mapFrom(configManager.config).put("storeBackgroundImage", name))
        } catch (e: Exception) {
            try {
                Files.deleteIfExists(target.toPath())
            } catch (cleanup: java.io.IOException) {
                e.addSuppressed(cleanup)
            }
            throw e
        }
        deleteBackground(plugin, old)
        logBackgroundChange(plugin, context)
        return Successful(mapOf("storeBackgroundImage" to name))
    }
}

@Endpoint
class PanelDeleteStoreBackgroundAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes = setOf(MarketNode.SETTINGS)
    override val paths = listOf(Path("/settings/store-background", RouteType.DELETE))

    @Suppress("UNCHECKED_CAST")
    private val configManager by lazy {
        plugin.pluginBeanContext.getBean(PluginConfigManager::class.java) as PluginConfigManager<MarketConfig>
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val old = configManager.config.storeBackgroundImage
        configManager.saveConfig(JsonObject.mapFrom(configManager.config).put("storeBackgroundImage", ""))
        deleteBackground(plugin, old)
        logBackgroundChange(plugin, context)
        return Successful(mapOf("storeBackgroundImage" to ""))
    }
}

private suspend fun logBackgroundChange(plugin: MarketPlugin, context: RoutingContext) {
    val auth = plugin.applicationContext.getBean(AuthProvider::class.java)
    val database = plugin.applicationContext.getBean(DatabaseManager::class.java)
    val sql = database.getSqlClient()
    val userId = auth.getUserIdFromRoutingContext(context)
    val username = database.userDao.getUsernameFromUserId(userId, sql)!!
    database.panelActivityLogDao.add(UpdatedMarketSettingsLog(userId, username, plugin.pluginId), sql)
}

private fun deleteBackground(plugin: MarketPlugin, name: String) {
    if (!StoreBackground.isFileName(name)) return
    try {
        Files.deleteIfExists(File(plugin.uploadsDir, name).toPath())
    } catch (e: java.io.IOException) {
        LoggerFactory.getLogger("Market:StoreBackground").warn("Could not remove old store background {}", name, e)
    }
}
