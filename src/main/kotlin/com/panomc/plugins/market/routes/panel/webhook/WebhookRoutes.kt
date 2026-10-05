package com.panomc.plugins.market.routes.panel.webhook

import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.db.model.PluginActivityLog
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.error.RequestValueException
import com.panomc.plugins.market.log.CreatedMarketWebhookLog
import com.panomc.plugins.market.log.DeletedMarketWebhookLog
import com.panomc.plugins.market.log.TestedMarketWebhookLog
import com.panomc.plugins.market.log.UpdatedMarketWebhookLog
import com.panomc.plugins.market.permission.MarketNode
import com.panomc.plugins.market.routes.base.MarketPanelApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.util.Paging
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.optionalParam
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/** Base of the store webhook routes (`P:SET`, 04 section 8): the service, the acting user and the activity log. */
abstract class WebhookAdminRoute(protected val plugin: MarketPlugin) : MarketPanelApi() {
    override val nodes: Set<MarketNode> = setOf(MarketNode.SETTINGS)

    private val authProvider by lazy { plugin.applicationContext.getBean(AuthProvider::class.java) }

    private val databaseManager by lazy { plugin.applicationContext.getBean(DatabaseManager::class.java) }

    protected val service: WebhookAdminService get() = webhookAdminService(plugin)

    protected fun bodyValidation(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository)
            .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
            .predicate(RequestPredicate.BODY_REQUIRED)
            .build()

    protected fun noBodyValidation(schemaRepository: SchemaRepository): ValidationHandler = ValidationHandlerBuilder.create(schemaRepository).build()

    protected fun pagingValidation(schemaRepository: SchemaRepository): ValidationHandler {
        var builder = ValidationHandlerBuilder.create(schemaRepository)

        for (name in listOf("page", "pageSize", "status")) builder = builder.queryParameter(optionalParam(name, stringSchema()))

        return builder.build()
    }

    protected suspend fun userId(context: RoutingContext): Long = authProvider.getUserIdFromRoutingContext(context)

    protected suspend fun log(context: RoutingContext, build: (userId: Long, username: String) -> PluginActivityLog) {
        val sqlClient = databaseManager.getSqlClient()
        val userId = authProvider.getUserIdFromRoutingContext(context)
        val username = databaseManager.userDao.getUsernameFromUserId(userId, sqlClient)!!

        databaseManager.panelActivityLogDao.add(build(userId, username), sqlClient)
    }

    protected fun idOf(context: RoutingContext): Long = parseId(context.pathParam("id"), "id")

    protected fun window(context: RoutingContext): Paging.Window {
        fun number(name: String): Long? = context.request().getParam(name)?.let { it.trim().toLongOrNull() ?: throw RequestValueException(name, "MUST_BE_A_NUMBER") }

        return Paging.window(number("page"), number("pageSize"))
    }

    protected suspend fun page(context: RoutingContext, endpointId: Long?): Result {
        context.response().putHeader("Cache-Control", "no-store")

        val page = service.deliveries(endpointId, service.statusOf(context.request().getParam("status")), window(context))

        return Successful(
            mapOf(
                "deliveries" to JsonArray(page.deliveries.map { service.listView(it) }),
                "deliveryCount" to page.count, "totalPage" to page.totalPage
            )
        )
    }
}

/** `GET /api/panel/market/webhooks` (`P:SET`): `webhooks[]` (secret and header values masked), `defaults{discordTemplate}`, `eventNames[]`. */
@Endpoint
class PanelGetWebhooksAPI(plugin: MarketPlugin) : WebhookAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/webhooks", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        return Successful(service.list().map)
    }
}

/**
 * `POST /webhooks` (create: `{id, secret?}`, the generated HMAC secret is shown once) and `PUT /webhooks/:id` (update: `{}`, plus `secret` only when one had
 * to be generated). 400 `INVALID_WEBHOOK_URL {reason}` / `INVALID_SETTINGS {fieldErrors}`; 404.
 */
@Endpoint
class PanelSaveWebhookAPI(plugin: MarketPlugin) : WebhookAdminRoute(plugin) {
    override val paths = listOf(
        Path("/api/panel/market/webhooks", RouteType.POST),
        Path("/api/panel/market/webhooks/:id", RouteType.PUT)
    )

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = bodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val body = context.body().asJsonObject() ?: JsonObject()

        context.response().putHeader("Cache-Control", "no-store")

        if (context.request().method().name() == "PUT") {
            val id = idOf(context)
            val saved = service.update(id, body)

            log(context) { u, n -> UpdatedMarketWebhookLog(u, n, body.getString("name") ?: "#$id", plugin.pluginId) }

            return Successful(if (saved.secret != null) mapOf("secret" to saved.secret) else emptyMap())
        }

        val saved = service.create(body)

        log(context) { u, n -> CreatedMarketWebhookLog(u, n, body.getString("name") ?: "#${saved.id}", plugin.pluginId) }

        return Successful(if (saved.secret != null) mapOf("id" to saved.id, "secret" to saved.secret) else mapOf("id" to saved.id))
    }
}

/** `DELETE /webhooks/:id`: a hard delete; its open rows become `DEAD (ENDPOINT_DELETED)`. */
@Endpoint
class PanelDeleteWebhookAPI(plugin: MarketPlugin) : WebhookAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/webhooks/:id", RouteType.DELETE))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = idOf(context)

        service.delete(id)
        log(context) { u, n -> DeletedMarketWebhookLog(u, n, "#$id", plugin.pluginId) }

        return Successful()
    }
}

/** `POST /webhooks/:id/test` (L8, 10 a minute per panel user): sends `test.ping` synchronously; `{statusCode, durationMs, error?}`. */
@Endpoint
class PanelTestWebhookAPI(plugin: MarketPlugin) : WebhookAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/webhooks/:id/test", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        val id = idOf(context)
        val result = service.test(id, userId(context))

        log(context) { u, n -> TestedMarketWebhookLog(u, n, "#$id", plugin.pluginId) }

        val out = LinkedHashMap<String, Any?>()

        out["statusCode"] = result.statusCode
        out["durationMs"] = result.durationMs

        if (result.error != null) out["error"] = result.error

        return Successful(out)
    }
}

/** `GET /webhooks/:id/deliveries` (`P:SET`): `deliveries[]`, `deliveryCount`, `totalPage`; `status?`. */
@Endpoint
class PanelGetWebhookEndpointDeliveriesAPI(plugin: MarketPlugin) : WebhookAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/webhooks/:id/deliveries", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagingValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = page(context, idOf(context))
}

/** `GET /webhook-deliveries` (`P:SET`): the global log, newest first. */
@Endpoint
class PanelGetWebhookDeliveriesAPI(plugin: MarketPlugin) : WebhookAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/webhook-deliveries", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = pagingValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result = page(context, null)
}

/** `GET /webhook-deliveries/:id` (`P:SET`): the row with the stored `body` and `lastResponse`. */
@Endpoint
class PanelGetWebhookDeliveryAPI(plugin: MarketPlugin) : WebhookAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/webhook-deliveries/:id", RouteType.GET))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        context.response().putHeader("Cache-Control", "no-store")

        return Successful(service.delivery(idOf(context)).map)
    }
}

/** `POST /webhook-deliveries/:id/redeliver` (`P:SET`): the same row back to `PENDING`; 400 `BAD_REQUEST` while `SENDING`. */
@Endpoint
class PanelRedeliverWebhookAPI(plugin: MarketPlugin) : WebhookAdminRoute(plugin) {
    override val paths = listOf(Path("/api/panel/market/webhook-deliveries/:id/redeliver", RouteType.POST))

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = noBodyValidation(schemaRepository)

    override suspend fun handleAuthorized(context: RoutingContext): Result {
        service.redeliver(idOf(context))

        return Successful()
    }
}
