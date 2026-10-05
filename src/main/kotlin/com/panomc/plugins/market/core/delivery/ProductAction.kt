package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.db.model.DeliveryPhase
import com.panomc.plugins.market.util.MoneyUtil
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/** Where a server action runs (01 section 2.2). Missing = [FIXED]. */
enum class ServerMode { FIXED, BUYER_CHOICE, ALL_CONNECTED }

/** `PERMISSION` only: written by Pano itself or applied by the Minecraft component on the target servers. Missing = [PANO]. */
enum class PermissionVia { PANO, SERVER }

enum class WebhookFormat { JSON, DISCORD }

enum class WebhookSigning { NONE, HMAC_SHA256 }

/** `WEBHOOK` action payload. [secret] is the stored (encrypted or masked) text, never interpreted here. */
data class WebhookSpec(
    val url: String,
    val format: WebhookFormat = WebhookFormat.JSON,
    val signing: WebhookSigning = WebhookSigning.NONE,
    val secret: String? = null
)

/**
 * One validated action of a product (08 section 2.1). Field meaning by type:
 * - `CREDIT`: [credit] = credits x100 per purchased unit.
 * - `PERMISSION`: [nodes], [via]; [serverMode] / [targetServers] give the node context scope.
 * - `COMMAND`: [commands], [serverMode], [targetServers], [requiresOnline], [perUnit].
 * - `WEBHOOK`: [webhook], [perUnit].
 *
 * Fields that do not apply to a type hold their neutral value (the parser drops them).
 */
data class ProductAction(
    val id: String,
    val type: DeliveryActionType,
    val phase: DeliveryPhase = DeliveryPhase.GRANT,
    val delaySeconds: Int = 0,
    val serverMode: ServerMode = ServerMode.FIXED,
    val targetServers: List<Long> = emptyList(),
    val requiresOnline: Boolean = false,
    val perUnit: Boolean = false,
    val via: PermissionVia = PermissionVia.PANO,
    val credit: Long? = null,
    val nodes: List<String> = emptyList(),
    val commands: List<String> = emptyList(),
    val webhook: WebhookSpec? = null
) {
    /** Server actions are `COMMAND` and `PERMISSION` with `via=SERVER`; everything else runs inline (08 section 2.1). */
    val isServerAction: Boolean
        get() = type == DeliveryActionType.COMMAND || (type == DeliveryActionType.PERMISSION && via == PermissionVia.SERVER)

    /** Stored JSON shape of `01` section 2.2. [ActionParser.parse] of the result gives back an equal action. */
    fun toJson(): JsonObject {
        val json = JsonObject().put("id", id).put("type", type.name).put("phase", phase.name)

        when (type) {
            DeliveryActionType.CREDIT -> json.put("value", MoneyUtil.toDecimal(credit ?: 0L))
            DeliveryActionType.PERMISSION -> json.put("value", JsonArray(nodes)).put("via", via.name)
            DeliveryActionType.COMMAND -> json.put("value", JsonArray(commands))
            DeliveryActionType.WEBHOOK -> {
                val spec = webhook
                val value = JsonObject().put("url", spec?.url).put("format", spec?.format?.name).put("signing", spec?.signing?.name)

                if (spec?.secret != null) value.put("secret", spec.secret)

                json.put("value", value)
            }
        }

        if (delaySeconds != 0) json.put("delay", delaySeconds)

        if (type == DeliveryActionType.COMMAND || type == DeliveryActionType.PERMISSION) {
            json.put("serverMode", serverMode.name)

            if (serverMode == ServerMode.FIXED) json.put("targetServers", JsonArray(targetServers))
        }

        if (type == DeliveryActionType.COMMAND) json.put("requiresOnline", requiresOnline)
        if (type == DeliveryActionType.COMMAND || type == DeliveryActionType.WEBHOOK) json.put("perUnit", perUnit)

        return json
    }
}
