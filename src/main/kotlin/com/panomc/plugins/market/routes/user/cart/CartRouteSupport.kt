package com.panomc.plugins.market.routes.user.cart

import com.panomc.plugins.market.runtime.beans
import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.db.DatabaseManager
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.MarketAddressDao
import com.panomc.plugins.market.db.dao.MarketCartDao
import com.panomc.plugins.market.db.dao.MarketCartItemDao
import com.panomc.plugins.market.db.dao.MarketProductDao
import com.panomc.plugins.market.db.dao.MarketProductFieldDao
import com.panomc.plugins.market.db.dao.MarketProductVariantDao
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.routes.panel.settings.currentConfig
import com.panomc.plugins.market.service.CartService
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.RequestPredicate
import io.vertx.ext.web.validation.ValidationHandler
import com.panomc.platform.schema.dsl.Bodies
import com.panomc.platform.schema.dsl.Parameters.param
import com.panomc.platform.schema.dsl.ValidationHandlerBuilder
import io.vertx.json.schema.SchemaRepository
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema
import io.vertx.sqlclient.Pool

/** The service of the cart routes, built on the plugin's beans (stateless: a route keeps one). */
internal fun cartService(plugin: MarketPlugin): CartService {
    val context = plugin.beans
    val databaseManager by lazy { context.getBean(DatabaseManager::class.java) }

    return CartService(
        db = MarketDb({ databaseManager.getSqlClient() as Pool }, SystemClock),
        clock = SystemClock,
        config = { currentConfig(plugin) },
        addresses = context.getBean(MarketAddressDao::class.java),
        carts = context.getBean(MarketCartDao::class.java),
        cartItems = context.getBean(MarketCartItemDao::class.java),
        products = context.getBean(MarketProductDao::class.java),
        variants = context.getBean(MarketProductVariantDao::class.java),
        fields = context.getBean(MarketProductFieldDao::class.java)
    )
}

/** The id of the logged-in buyer (the route base already refused an anonymous request). */
internal fun buyerId(plugin: MarketPlugin, context: RoutingContext): Long =
    plugin.applicationContext.getBean(AuthProvider::class.java).getUserIdFromRoutingContext(context)

/** A JSON object body that must be present; the fields are judged by the parsers of `CartRequests`. */
internal fun jsonBodyValidation(schemaRepository: SchemaRepository): ValidationHandler =
    ValidationHandlerBuilder.create(schemaRepository)
        .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
        .predicate(RequestPredicate.BODY_REQUIRED)
        .build()

/** [jsonBodyValidation] plus the `itemId` path parameter (read as a string and judged by `parseId`, never a schema 500). */
internal fun jsonBodyValidationWithItemId(schemaRepository: SchemaRepository): ValidationHandler =
    ValidationHandlerBuilder.create(schemaRepository)
        .pathParameter(param("itemId", stringSchema()))
        .body(Bodies.json(objectSchema().allowAdditionalProperties(true)))
        .predicate(RequestPredicate.BODY_REQUIRED)
        .build()
