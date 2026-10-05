package com.panomc.plugins.market.config

import com.panomc.plugins.market.error.InvalidSettings
import io.vertx.core.json.JsonObject
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.Schemas.*

/**
 * The pure core of `POST /settings` (04 section 8): the body is a partial update validated against the key table of
 * [MarketConfigKeys], then merged onto the current configuration. Never a raw-body overwrite, never `version`.
 */
object SettingsRequest {
    /** The body schema of [scope]: every writable key optional with its type, `additionalProperties: false`. */
    fun schema(scope: ConfigScope = ConfigScope.GENERAL): ObjectSchemaBuilder {
        var schema = objectSchema()

        MarketConfigKeys.writable(scope).forEach { key ->
            val property = when (key.kind) {
                ConfigKind.BOOL -> booleanSchema()
                ConfigKind.INT -> intSchema()
                ConfigKind.DOUBLE -> numberSchema()
                ConfigKind.STRING -> stringSchema()
                ConfigKind.ENUM -> enumSchema(*key.enumValues.toTypedArray())
                ConfigKind.STRING_LIST -> arraySchema().items(stringSchema())
            }

            schema = schema.optionalProperty(key.name, property)
        }

        return schema.allowAdditionalProperties(false)
    }

    /**
     * Returns the merged configuration to save, or throws 400 `INVALID_SETTINGS` `{fieldErrors}` (the body is not
     * applied at all when any field is invalid). `version` is refused like any unknown key.
     */
    fun apply(body: JsonObject, current: JsonObject, scope: ConfigScope = ConfigScope.GENERAL): JsonObject {
        val errors = MarketConfigKeys.validate(body, current, scope)

        if (errors.isNotEmpty()) throw InvalidSettings(errors)

        return current.copy().mergeIn(body)
    }
}
