package com.panomc.plugins.market.routes.panel.category

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.log.UpdatedMarketCategoryLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.panel.product.deleteFile
import com.panomc.plugins.market.service.CategoryRules
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/**
 * `PUT /api/panel/market/categories/:id` (04 section 5, `P:CAT`): a partial update, a field that was not sent keeps its
 * value (the old route reset `parentId`, icon, colour and status); `409 CATEGORY_IN_USE` when un-tiering a category that
 * still has ACTIVE tier entitlements.
 */
@Endpoint
class PanelUpdateCategoryAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/api/panel/market/categories/:id", RouteType.PUT))

    override val nodes = setOf(MarketNode.CATALOG)

    private val catalog by lazy { categoryCatalog(plugin) }

    private val authProvider: AuthProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager: DatabaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(5 * 1024 * 1024)

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) throw BadRequest()
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.multipartFormData(categoryFormSchema()))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = parseId(context.pathParam("id"))
        val data: JsonObject = getParameters(context).body().jsonObject
        val removeImage = data.containsKey("removeImage") && (CategoryRules.asBoolean(data.getValue("removeImage")) ?: throw BadRequest())
        val upload = CategoryUpload(plugin, context.fileUploads())

        try {
            val saved = catalog.updateCategory(id, data, upload.store(removeImage))

            saved.orphanedFiles.forEach { deleteFile(plugin, it) }

            val sqlClient = databaseManager.getSqlClient()
            val userId = authProvider.getUserIdFromRoutingContext(context)
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!
            val changes = JsonObject().apply { saved.changes.forEach { (key, value) -> put(key, value) } }

            databaseManager.panelActivityLogDao.add(
                UpdatedMarketCategoryLog(userId, username, plugin.pluginId, saved.name, if (changes.isEmpty) null else changes),
                sqlClient
            )

            return Successful()
        } catch (e: Throwable) {
            upload.discard()

            throw e
        }
    }
}
