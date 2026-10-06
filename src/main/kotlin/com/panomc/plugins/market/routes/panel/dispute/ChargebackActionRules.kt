package com.panomc.plugins.market.routes.panel.dispute

import com.panomc.plugins.market.core.abuse.ActionGuard
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.WebhookSigning
import com.panomc.plugins.market.db.model.DeliveryActionType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Validation of `MarketConfig.chargebackActions` on `POST /settings` (11 section 10 "Validation of chargebackActions", 08 section 2.2 last paragraph): a JSON array of
 * at most [MAX_ACTIONS] actions, each of type `COMMAND` or `WEBHOOK`, phase absent or `GRANT`, `serverMode` `FIXED` or `ALL_CONNECTED` (no
 * `BUYER_CHOICE`), `perUnit` absent or false (an id that is left out is generated as `c<n>`); then [ActionGuard] for the caller (a changed command needs the console permission of its servers). A failure is
 * `INVALID_SETTINGS {fieldErrors: {chargebackActions: <reason>}}` (the route throws it), a refusal of the guard is 403 `NO_PERMISSION`.
 *
 * A `WEBHOOK` action must be unsigned (`signing` `NONE`, no `secret`): `HMAC_SHA256` is refused (`actions.<i>.value.signing:INVALID`) until the settings route can store
 * its secret encrypted (see the comment in [check]).
 *
 * Pure: the rights of the caller and the servers come in as arguments. An empty list (or `[]`) is always allowed: switching the actions off needs no privilege.
 */
object ChargebackActionRules {
    const val MAX_ACTIONS = 10

    sealed class Verdict {
        data object Ok : Verdict()

        /** [reason] is `<path>:<code>` of the first rule that failed (`actions.0.value:INVALID`) or `INVALID` / `TOO_MANY` for the list itself. */
        class Invalid(val reason: String) : Verdict()

        /** The caller may not save this change ([ActionGuard]). */
        data object Forbidden : Verdict()
    }

    suspend fun check(
        submitted: String,
        stored: String?,
        caller: ActionGuard.Caller?,
        serverIds: Set<Long>,
        hasServers: Boolean,
        webhookUrlOk: (String) -> Boolean = { true }
    ): Verdict {
        val raw = submitted.trim()

        if (raw.isEmpty()) return Verdict.Ok

        val array = try {
            JsonArray(raw)
        } catch (e: Exception) {
            return Verdict.Invalid("INVALID")
        }

        if (array.isEmpty) return Verdict.Ok

        if (array.size() > MAX_ACTIONS) return Verdict.Invalid("TOO_MANY")

        // the parser normalises these two quietly for a list that is not a product's (phase GRANT, no perUnit); a chargeback list refuses what it would have to change
        for (index in 0 until array.size()) {
            val entry = array.getValue(index) as? JsonObject ?: return Verdict.Invalid("actions.$index:INVALID")
            val phase = entry.getValue("phase")

            if (phase != null && phase != "GRANT") return Verdict.Invalid("actions.$index.phase:INVALID")

            if (entry.getValue("perUnit") == true) return Verdict.Invalid("actions.$index.perUnit:INVALID")
        }

        val parsed = ActionParser.parse(array, ActionParser.Context(kind = ActionParser.Kind.CHARGEBACK, serverIds = serverIds, hasServers = hasServers, webhookUrlOk = webhookUrlOk))

        if (!parsed.ok) return Verdict.Invalid(parsed.errors.entries.first().let { "${it.key}:${it.value}" })

        for ((index, action) in parsed.actions.withIndex()) {
            if (action.type != DeliveryActionType.COMMAND && action.type != DeliveryActionType.WEBHOOK) return Verdict.Invalid("actions.$index.type:INVALID")

            val webhook = action.webhook ?: continue

            // Fail closed until `POST /settings` has the secret protocol of 08 section 2.2 / 11 section 8.2 (encrypted at rest, masked in every response, generated when
            // missing): a signed webhook would keep its secret in plaintext in the config, copy it into the delivery rows and answer it from `GET /settings`, or, without
            // a secret, die at send time with SECRET_UNREADABLE. A secret on an unsigned action is just as pointless and would be stored the same way.
            if (webhook.signing == WebhookSigning.HMAC_SHA256) return Verdict.Invalid("actions.$index.value.signing:INVALID")

            if (webhook.secret != null) return Verdict.Invalid("actions.$index.value.secret:INVALID")
        }

        val before = ActionParser.parseStored(stored, ActionParser.Kind.CHARGEBACK).actions

        return if (ActionGuard.violations(before, parsed.actions, caller).isEmpty()) Verdict.Ok else Verdict.Forbidden
    }
}
