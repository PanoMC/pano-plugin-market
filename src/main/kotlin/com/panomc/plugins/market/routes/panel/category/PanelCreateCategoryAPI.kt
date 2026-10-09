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
import com.panomc.plugins.market.log.CreatedMarketCategoryLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import io.vertx.core.Handler
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository

/** `POST /api/panel/market/categories` (04 section 5, `P:CAT`): multipart form with `tiered` and `upgradeMode`. */
@Endpoint
class PanelCreateCategoryAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/categories", RouteType.POST))

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
        val data = getParameters(context).body().jsonObject
        val upload = CategoryUpload(plugin, context.fileUploads())

        try {
            val saved = catalog.createCategory(data, upload.store(removeImage = false))

            val sqlClient = databaseManager.getSqlClient()
            val userId = authProvider.getUserIdFromRoutingContext(context)
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

            databaseManager.panelActivityLogDao.add(CreatedMarketCategoryLog(userId, username, plugin.pluginId, saved.name), sqlClient)

            return Successful(mapOf("id" to saved.id))
        } catch (e: Throwable) {
            upload.discard()

            throw e
        }
    }
}
