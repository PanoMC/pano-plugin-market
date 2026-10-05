package com.panomc.plugins.market.routes.user.address

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.annotation.Endpoint
import com.panomc.platform.db.DatabaseManager
import com.panomc.platform.model.Path
import com.panomc.platform.model.Result
import com.panomc.platform.model.RouteType
import com.panomc.platform.model.Successful
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketAddressDao
import com.panomc.plugins.market.db.dao.MarketCartDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.base.MarketUserApi
import com.panomc.plugins.market.routes.base.parseId
import com.panomc.plugins.market.routes.user.cart.buyerId
import com.panomc.plugins.market.routes.user.cart.jsonBodyValidation
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import io.vertx.ext.web.validation.builder.Bodies
import io.vertx.ext.web.validation.builder.Parameters.param
import io.vertx.ext.web.validation.builder.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool

/** The address book service of the routes, built on the plugin's beans (stateless: a route keeps one). */
internal fun addressBookService(plugin: MarketPlugin): AddressBookService {
    val context = plugin.beans
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }

    return AddressBookService(
        db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock),
        clock = SystemClock,
        addresses = context.getBean(MarketAddressDao::class.java),
        carts = context.getBean(MarketCartDao::class.java)
    )
}

private fun idAndBodyValidation(schemaRepository: SchemaRepository): ValidationHandler =
    ValidationHandlerBuilder.create(schemaRepository)
        .pathParameter(param("id", stringSchema()))
        .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
        .predicate(RequestPredicate.BODY_REQUIRED)
        .build()

/** `GET /api/market/me/addresses` (`USER`): `{addresses: Address & {id, label, isDefault}[]}`, the default one first. */
@Endpoint
class GetAddressesAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/addresses", RouteType.GET))

    private val book by lazy { addressBookService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler? = null

    override suspend fun handleMarket(context: RoutingContext): Result =
        Successful(AddressBookService.renderAll(book.list(buyerId(plugin, context))).map)
}

/** `POST /api/market/me/addresses` (`USER`): an `Address` plus `label?`, `isDefault?`; `{id}`. 400 `SHIPPING_ADDRESS_REQUIRED {fields}` for an address that is not complete; at most 10 per user. */
@Endpoint
class CreateAddressAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/addresses", RouteType.POST))

    private val book by lazy { addressBookService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = jsonBodyValidation(schemaRepository)

    override suspend fun handleMarket(context: RoutingContext): Result {
        val input = AddressBookService.parse(getParameters(context).body().jsonObject)

        return Successful(mapOf("id" to book.create(buyerId(plugin, context), input)))
    }
}

/** `PUT /api/market/me/addresses/:id` (`USER`): replaces the address; `{}`; 404 for the address of another user. */
@Endpoint
class UpdateAddressAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/addresses/:id", RouteType.PUT))

    private val book by lazy { addressBookService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler = idAndBodyValidation(schemaRepository)

    override suspend fun handleMarket(context: RoutingContext): Result {
        val parameters = getParameters(context)
        val body: JsonObject = parameters.body().jsonObject

        book.update(buyerId(plugin, context), parseId(parameters.pathParameter("id").string, "id"), AddressBookService.parse(body))

        return Successful()
    }
}

/** `DELETE /api/market/me/addresses/:id` (`USER`): `{}`; 404 for the address of another user. */
@Endpoint
class DeleteAddressAPI(private val plugin: MarketPlugin) : MarketUserApi() {
    override val paths = listOf(Path("/api/market/me/addresses/:id", RouteType.DELETE))

    private val book by lazy { addressBookService(plugin) }

    override fun getValidationHandler(schemaRepository: SchemaRepository): ValidationHandler =
        ValidationHandlerBuilder.create(schemaRepository).pathParameter(param("id", stringSchema())).build()

    override suspend fun handleMarket(context: RoutingContext): Result {
        val parameters = getParameters(context)

        book.delete(buyerId(plugin, context), parseId(parameters.pathParameter("id").string, "id"))

        return Successful()
    }
}
