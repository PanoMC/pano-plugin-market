package com.panomc.plugins.market.routes.panel.block

import com.panomc.platform.model.PageRequest
import com.panomc.platform.model.Paging
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.db.model.BlockSource
import com.panomc.plugins.market.db.model.BlockType
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.CreatedMarketBlockLog
import com.panomc.plugins.market.log.DeletedMarketBlockLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.base.parseOptionalEnum
import com.panomc.plugins.market.routes.base.parsePageRequest
import com.panomc.plugins.market.routes.base.rejectUnknownKeys
import com.panomc.plugins.market.routes.panel.order.actingUserId
import com.panomc.plugins.market.routes.panel.order.logOrderDecision
import com.panomc.plugins.market.service.BlockListService
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters.optionalParam
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

private val CREATE_KEYS = setOf("type", "value", "reason", "expiresAt")

/**
 * The query of `GET /blocks` (04 section 7): `type?`, `source?`, `search?` (a prefix of the value), `page?`, `pageSize?`. A value outside the contract is a
 * 400 (never ignored), the window is the usual `PageRequest`.
 */
internal class BlockListQuery(val type: BlockType?, val source: BlockSource?, val search: String?, val window: PageRequest)

internal fun parseBlockListQuery(type: String?, source: String?, search: String?, page: String?, pageSize: String?): BlockListQuery = BlockListQuery(
    type = parseOptionalEnum(BlockType.entries.toTypedArray(), type?.trim()?.takeIf { it.isNotEmpty() }, "type"),
    source = parseOptionalEnum(BlockSource.entries.toTypedArray(), source?.trim()?.takeIf { it.isNotEmpty() }, "source"),
    search = search?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 255) throw RequestValueException("search", "TOO_LONG") },
    window = parsePageRequest(page, pageSize)
)

/**
 * `POST /blocks` body: `type*`, `value*` (text), `reason?` (text), `expiresAt?` (epoch ms, a whole number). The value rules, the SELF rule and the future
 * expiry are the service's (`INVALID_BLOCK` with a `reason`), a key or a JSON type outside the contract is a 400.
 */
internal class BlockCreateRequest(val type: String?, val value: String?, val reason: String?, val expiresAt: Long?)

internal fun parseBlockCreateRequest(body: JsonObject): BlockCreateRequest {
    rejectUnknownKeys(body, CREATE_KEYS)

    fun text(key: String): String? = when (val v = body.getValue(key)) {
        null -> null
        is String -> v
        else -> throw RequestValueException(key, "MUST_BE_A_STRING")
    }

    val expires = when (val v = body.getValue("expiresAt")) {
        null -> null
        is Int -> v.toLong()
        is Long -> v
        else -> throw RequestValueException("expiresAt", "MUST_BE_AN_INTEGER")
    }

    return BlockCreateRequest(text("type"), text("value"), text("reason"), expires)
}

/** `GET /api/panel/market/blocks` (`P:OM`, 04 section 7, 11 section 9.4): `items[]` (the blocks) and `page`; values are unmasked (the list is the tool to manage them). */
@Endpoint
class PanelGetBlocksAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/blocks", RouteType.GET))

    override val nodes = setOf(MarketNode.ORDERS_MANAGE)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = Paging.params(ValidationHandlerBuilder.create(schemaRepository))

        for (name in listOf("type", "source", "search")) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = context.request()
        val query = parseBlockListQuery(request.getParam("type"), request.getParam("source"), request.getParam("search"), request.getParam("page"), request.getParam("pageSize"))
        val page = blockListService(plugin).list(query.type, query.source, query.search, query.window.number, query.window.size, databaseManager.getSqlClient())

        return Successful(Paging.response(page.rows.map { blockJson(it) }, page.total, query.window))
    }
}

/**
 * `POST /api/panel/market/blocks` (`P:OM`, 04 section 7): `{id}`. 400 `INVALID_BLOCK {reason}` (`TYPE`, `VALUE`, `RANGE_TOO_WIDE`, `SELF`, `REASON`, `EXPIRES`), 409
 * `BLOCK_ALREADY_EXISTS` (an expired duplicate is replaced). Activity log `CREATED_MARKET_BLOCK` with the masked value.
 */
@Endpoint
class PanelCreateBlockAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/blocks", RouteType.POST))

    override val nodes = setOf(MarketNode.ORDERS_MANAGE)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val request = parseBlockCreateRequest(getParameters(context).body().jsonObject)
        val userId = actingUserId(plugin, context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, databaseManager.getSqlClient()) ?: ""
        val block = blockListService(plugin).create(request.type, request.value, request.reason, request.expiresAt, BlockListService.Actor(userId, username))

        logOrderDecision(plugin, context) { id, name ->
            CreatedMarketBlockLog(id, name, plugin.pluginId, block.type.name, BlockListService.maskedValue(block.type, block.value), block.source.name)
        }

        return Successful(mapOf("id" to block.id))
    }
}

/** `DELETE /api/panel/market/blocks/:id` (`P:OM`, 04 section 7): `{}`; 404 when missing. A chargeback row may be removed by hand. Activity log `DELETED_MARKET_BLOCK`. */
@Endpoint
class PanelDeleteBlockAPI(private val plugin: MarketPlugin) : MarketPanelApi() {
    override val paths = listOf(Path("/blocks/:id", RouteType.DELETE))

    override val nodes = setOf(MarketNode.ORDERS_MANAGE)

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val block = blockListService(plugin).delete(parseId(context.pathParam("id")), databaseManager.getSqlClient())

        logOrderDecision(plugin, context) { id, name ->
            DeletedMarketBlockLog(id, name, plugin.pluginId, block.type.name, BlockListService.maskedValue(block.type, block.value), block.source.name)
        }

        return Successful()
    }
}
