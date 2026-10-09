package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.catalog.ProductRequestParser
import com.panomc.plugins.market.log.CreatedMarketProductLog
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

/** `POST /api/panel/market/products` (04 section 5, `P:CAT`): multipart form, see `ProductRequestParser`. */
@Endpoint
class PanelCreateProductAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/products", RouteType.POST))

    override val nodes = setOf(MarketNode.CATALOG)

    private val catalog by lazy { catalogService(plugin) }

    private val authProvider: AuthProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager: DatabaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun bodyHandler(): Handler<RoutingContext> =
        BodyHandler.create()
            .setDeleteUploadedFilesOnEnd(true)
            .setBodyLimit(ProductUploads.BODY_LIMIT)

    override suspend fun getFailureHandler(context: RoutingContext) {
        if (context.failure() == null) throw BadRequest()
    }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.multipartFormData(productFormSchema()))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val data = getParameters(context).body().jsonObject
        val uploads = ProductUploads(plugin, context.fileUploads())

        try {
            val stored = uploads.store()
            val input = ProductRequestParser.parse(data, stored.productImage, stored.variantImages)
            val saved = catalog.create(input, RoutingCaller(plugin, context))

            val sqlClient = databaseManager.getSqlClient()
            val userId = authProvider.getUserIdFromRoutingContext(context)
            val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

            databaseManager.panelActivityLogDao.add(CreatedMarketProductLog(userId, username, plugin.pluginId, saved.name), sqlClient)

            return Successful(saved.response())
        } catch (e: Throwable) {
            uploads.discard()

            throw e
        }
    }
}
