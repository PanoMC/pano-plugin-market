package com.panomc.plugins.market.error

/**
 * A request value (query parameter, header, body field) is outside what the contract allows. Pure on purpose: the
 * parsers of `routes.base.RequestParsers` and `util.Paging` throw it and the route base classes turn it into the
 * platform's 400 `BAD_REQUEST` with a `bodyValidationError` text (04 section 1), never a 500.
 */
class RequestValueException(val field: String, val reason: String) :
    IllegalArgumentException("$field: $reason")
