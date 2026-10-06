package com.panomc.plugins.market.routes.base

import io.vertx.core.http.HttpMethod
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.validation.ValidationHandler

/**
 * One class answers GET and a write on the same path (the legal text, the currency rates). The route has one validation handler for all of its paths, and a JSON
 * body validator answers `400 BAD_REQUEST` "Null body" to a GET that carries `Content-Type: application/json` without a body, which is exactly what the panel's
 * `ApiUtil.get` sends: the section loaded from the browser failed with "The request is invalid", although the same call from a plain HTTP client passed. A read has
 * no body, so only the other methods are validated.
 */
fun ValidationHandler.exceptReads(): ValidationHandler {
    val validator = this

    return object : ValidationHandler {
        override fun handle(context: RoutingContext) {
            if (context.request().method() == HttpMethod.GET) context.next() else validator.handle(context)
        }
    }
}
