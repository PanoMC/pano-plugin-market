package com.panomc.plugins.market.routes.panel.creatorcode

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.error.BadRequest
import com.panomc.platform.model.*
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.dao.MarketCreatorCodeDao
import com.panomc.plugins.market.log.DeletedMarketCreatorCodeLog
import com.panomc.plugins.market.permission.ManageMarketPermission
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.numberSchema

@Endpoint
class PanelDeleteCreatorCodeAPI(
    private val plugin: MarketPlugin,
    private val creatorCodeDao: MarketCreatorCodeDao
) : PanelApi() {
    override val paths = listOf(Path("/api/panel/market/creator-codes/:id", RouteType.DELETE))

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }
    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .pathParameter(param("id", numberSchema()))
            .build()

    override suspend fun handle(context: RoutingContext): Result {
        authProvider.requirePermission(ManageMarketPermission(), context)

        val id = context.pathParam("id").toLong()

        val sqlClient = databaseManager.getSqlClient()
        val creatorCode = creatorCodeDao.getById(id, sqlClient) ?: throw BadRequest()

        creatorCodeDao.deleteById(id, sqlClient)

        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!
        databaseManager.panelActivityLogDao.add(DeletedMarketCreatorCodeLog(userId, username, plugin.pluginId, creatorCode.code), sqlClient)

        return Successful()
    }
}
