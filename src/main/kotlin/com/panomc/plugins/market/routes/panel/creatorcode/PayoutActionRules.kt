package com.panomc.plugins.market.routes.panel.creatorcode

import com.panomc.plugins.market.core.abuse.ActionGuard
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.WebhookSigning
import com.panomc.plugins.market.db.model.DeliveryActionType
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject

/**
 * Validation of `actions[]` of `POST /creator-codes/:id/payouts` (`method = ACTION`; 08 section 2.2 last paragraph, 11 section 14.4): a JSON array of 1 to
 * [MAX_ACTIONS] actions, each of type `COMMAND` or `WEBHOOK`, phase absent or `GRANT`, `serverMode` `FIXED` or `ALL_CONNECTED` (no `BUYER_CHOICE`), `perUnit` absent
 * or false (an id that is left out is generated as `p<n>`); then [ActionGuard] for the caller: every action of a new payout is a changed one, so a command needs the
 * console permission of its servers. [Verdict.Ok] carries the canonical text that is stored. As for chargeback actions a `WEBHOOK` action must be unsigned (the
 * payout stores no secret): `HMAC_SHA256` and any `secret` are refused.
 *
 * Pure: the rights of the caller and the servers come in as arguments.
 */
object PayoutActionRules {
    const val MAX_ACTIONS = 10

    sealed class Verdict {
        /** [canonical] is `ActionParser.toJson` of the parsed actions: what `market_creator_payout.actions` stores. */
        class Ok(val canonical: String) : Verdict()

        /** [reason] is `<path>:<code>` of the first rule that failed (`actions.0.value:INVALID`) or `REQUIRED` / `INVALID` / `TOO_MANY` for the list itself. */
        class Invalid(val reason: String) : Verdict()

        /** The caller may not run these actions ([ActionGuard]). */
        data object Forbidden : Verdict()
    }

    suspend fun check(submitted: Any?, caller: ActionGuard.Caller?, serverIds: Set<Long>, hasServers: Boolean, webhookUrlOk: (String) -> Boolean = { true }): Verdict {
        val array = submitted as? JsonArray ?: return Verdict.Invalid(if (submitted == null) "REQUIRED" else "INVALID")

        if (array.isEmpty) return Verdict.Invalid("REQUIRED")

        if (array.size() > MAX_ACTIONS) return Verdict.Invalid("TOO_MANY")

        // the parser normalises these two quietly for a list that is not a product's (phase GRANT, no perUnit); a payout list refuses what it would have to change
        for (index in 0 until array.size()) {
            val entry = array.getValue(index) as? JsonObject ?: return Verdict.Invalid("actions.$index:INVALID")
            val phase = entry.getValue("phase")

            if (phase != null && phase != "GRANT") return Verdict.Invalid("actions.$index.phase:INVALID")

            if (entry.getValue("perUnit") == true) return Verdict.Invalid("actions.$index.perUnit:INVALID")
        }

        val parsed = ActionParser.parse(array, ActionParser.Context(kind = ActionParser.Kind.PAYOUT, serverIds = serverIds, hasServers = hasServers, webhookUrlOk = webhookUrlOk))

        if (!parsed.ok) return Verdict.Invalid(parsed.errors.entries.first().let { "${it.key}:${it.value}" })

        for ((index, action) in parsed.actions.withIndex()) {
            if (action.type != DeliveryActionType.COMMAND && action.type != DeliveryActionType.WEBHOOK) return Verdict.Invalid("actions.$index.type:INVALID")

            val webhook = action.webhook ?: continue

            if (webhook.signing == WebhookSigning.HMAC_SHA256) return Verdict.Invalid("actions.$index.value.signing:INVALID")

            if (webhook.secret != null) return Verdict.Invalid("actions.$index.value.secret:INVALID")
        }

        if (ActionGuard.violations(emptyList(), parsed.actions, caller).isNotEmpty()) return Verdict.Forbidden

        return Verdict.Ok(ActionParser.toJson(parsed.actions).encode())
    }
}
