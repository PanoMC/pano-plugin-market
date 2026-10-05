package com.panomc.plugins.market.routes.panel.product

import com.panomc.platform.auth.AuthProvider
import com.panomc.platform.error.NoPermission
import com.panomc.platform.auth.panel.permission.ManagePermissionGroupsPermission
import com.panomc.platform.auth.panel.permission.ManageServerConsolePermission
import com.panomc.plugins.market.MarketPlugin
import com.panomc.plugins.market.core.abuse.ActionGuard
import com.panomc.plugins.market.core.delivery.ActionParser
import com.panomc.plugins.market.core.delivery.ProductAction
import com.panomc.plugins.market.core.delivery.WebhookSigning
import com.panomc.plugins.market.core.webhook.TargetPolicy
import com.panomc.plugins.market.db.model.BillingMode
import com.panomc.plugins.market.db.model.DeliveryActionType
import com.panomc.plugins.market.error.InvalidProduct
import com.panomc.plugins.market.provider.SecretCipher
import com.panomc.plugins.market.service.platform.ServerRoster
import io.vertx.core.json.JsonArray
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.SqlClient
import java.security.SecureRandom
import java.util.Base64

/**
 * What a product save asks of the action rules (08 section 2.2 and 11 section 14.4): `CatalogService` calls it inside the save
 * transaction, after the product row is locked, so the stored actions it compares with are the ones the new text replaces.
 * [ProductActionRules] is the implementation; a failure is thrown (`InvalidProduct` with `fieldErrors["actions.<i>.<key>"]`, or
 * `NoPermission` from [ActionGuard]).
 */
interface ProductActionCheck {
    /**
     * [stored] / [storedServerChoices] are the columns before the save (`null` on create), [submitted] the normalised `actions` text of the
     * request and [product] the product as it will be stored (billing mode, `maxQuantityPerOrder`, `serverChoices` after the save).
     * [fields] maps `fieldKey` to `usableInCommands` for the fields the product will have.
     */
    class Request(
        val stored: String?,
        val storedServerChoices: String?,
        val submitted: String,
        val billingMode: BillingMode,
        val maxQuantityPerOrder: Int?,
        val serverChoices: String?,
        val fields: Map<String, Boolean>,
        val caller: ActionGuard.Caller?
    )

    /** [json] is the canonical text to store (secrets encrypted); [generatedSecrets] maps an action id to a webhook secret created now, shown once. */
    class Outcome(val json: String, val generatedSecrets: Map<String, String> = emptyMap())

    suspend fun onSave(conn: SqlClient, request: Request): Outcome

    /**
     * A save that does not submit `actions` but changes what the stored actions depend on (`serverChoices`, billing mode, `maxQuantityPerOrder`,
     * the fields a command may use) re-checks the stored actions against the new product: the strict rules (`InvalidProduct`) and [ActionGuard]
     * (`NoPermission`). Nothing is rewritten: the stored text, with its encrypted secrets, stays as it is.
     */
    class Unchanged(
        val stored: String,
        val storedServerChoices: String?,
        val billingMode: BillingMode,
        val maxQuantityPerOrder: Int?,
        val serverChoices: String?,
        val fields: Map<String, Boolean>,
        val caller: ActionGuard.Caller?
    )

    suspend fun onUnchanged(conn: SqlClient, request: Unchanged)

    /** A clone counts as new: every action of [source] needs the privilege of [ActionGuard]. The stored text (secrets already encrypted) is copied as it is. */
    suspend fun onClone(conn: SqlClient, source: String?, serverChoices: String?, caller: ActionGuard.Caller?)
}

/**
 * Action validation on product save (08 section 2.2, 11 section 14.4): [ActionParser] with the context of the catalogue (billing
 * mode, `maxQuantityPerOrder`, the fields a command may use, the granted servers, `serverChoices`, the webhook URL policy), then
 * [ActionGuard], then the webhook secrets (the write protocol of 11 section 8.2) and the canonical text that is stored.
 *
 * The defaults fail closed: without a [roster] no server action can be saved (`NO_SERVERS`), without a [cipher] no webhook action can
 * (`INVALID`), and a save without a caller has no privileges.
 */
class ProductActionRules(
    private val roster: ServerRoster = ServerRoster.NONE,
    private val cipher: SecretCipher? = null,
    private val allowPrivateWebhookTargets: () -> Boolean = { false },
    private val hosted: () -> Boolean = { false },
    private val random: SecureRandom = SecureRandom()
) : ProductActionCheck {
    private suspend fun context(conn: SqlClient, billingMode: BillingMode, maxQuantityPerOrder: Int?, fields: Map<String, Boolean>, choices: List<Long>): ActionParser.Context {
        val granted = roster.snapshot(conn).granted.toSet()

        return ActionParser.Context(
            kind = ActionParser.Kind.PRODUCT,
            billingMode = billingMode,
            maxQuantityPerOrder = maxQuantityPerOrder,
            fields = fields,
            serverIds = granted,
            hasServers = granted.isNotEmpty(),
            hasServerChoices = choices.isNotEmpty(),
            webhookUrlOk = { url -> TargetPolicy.syntaxOk(url, TargetPolicy.effectiveAllowPrivate(allowPrivateWebhookTargets(), hosted())) }
        )
    }

    override suspend fun onUnchanged(conn: SqlClient, request: ProductActionCheck.Unchanged) {
        val choices = idList(request.serverChoices)
        val parsed = ActionParser.parse(request.stored, context(conn, request.billingMode, request.maxQuantityPerOrder, request.fields, choices))

        if (!parsed.ok) throw InvalidProduct(parsed.errors)

        val stored = ActionParser.parseStored(request.stored).actions

        if (ActionGuard.violations(stored, stored, request.caller, choices, idList(request.storedServerChoices)).isNotEmpty()) throw NoPermission()
    }

    override suspend fun onSave(conn: SqlClient, request: ProductActionCheck.Request): ProductActionCheck.Outcome {
        val choices = idList(request.serverChoices)
        val context = context(conn, request.billingMode, request.maxQuantityPerOrder, request.fields, choices)

        val parsed = ActionParser.parse(request.submitted, context)

        if (!parsed.ok) throw InvalidProduct(parsed.errors)

        val stored = ActionParser.parseStored(request.stored).actions
        val actions = secrets(parsed.actions, stored)

        if (actions.errors.isNotEmpty()) throw InvalidProduct(actions.errors)

        if (ActionGuard.violations(stored, parsed.actions, request.caller, choices, idList(request.storedServerChoices)).isNotEmpty()) throw NoPermission()

        return ProductActionCheck.Outcome(ActionParser.toJson(actions.actions).encode(), actions.generated)
    }

    override suspend fun onClone(conn: SqlClient, source: String?, serverChoices: String?, caller: ActionGuard.Caller?) {
        if (ActionGuard.violations(emptyList(), ActionParser.parseStored(source).actions, caller, idList(serverChoices)).isNotEmpty()) throw NoPermission()
    }

    private class Secrets(val actions: List<ProductAction>, val errors: Map<String, String>, val generated: Map<String, String>)

    /**
     * 11 section 8.2: a webhook secret is stored encrypted and never read back. No signing means no secret. With `HMAC_SHA256`: a blank
     * value or the mask keeps the secret the same action id has stored, a new value (16..128 printable ASCII) replaces it, and when there
     * is none at all one is created (`whsec_` + base64url of 32 random bytes) and returned once.
     */
    private fun secrets(actions: List<ProductAction>, stored: List<ProductAction>): Secrets {
        val byId = stored.associateBy { it.id }
        val errors = linkedMapOf<String, String>()
        val generated = linkedMapOf<String, String>()
        val out = actions.mapIndexed { index, action ->
            val spec = action.webhook

            if (action.type != DeliveryActionType.WEBHOOK || spec == null) return@mapIndexed action

            val c = cipher

            if (c == null) {
                errors["actions.$index.value"] = "INVALID"

                return@mapIndexed action
            }

            if (spec.signing == WebhookSigning.NONE) return@mapIndexed action.copy(webhook = spec.copy(secret = null))

            val given = spec.secret
            val keep = byId[action.id]?.webhook?.secret?.takeIf { it.isNotEmpty() }
            val text = when {
                given != null && given != MASK -> {
                    if (given.length !in 16..128 || given.any { it.code !in 0x20..0x7e }) {
                        errors["actions.$index.value.secret"] = "INVALID_VALUE"

                        return@mapIndexed action
                    }

                    c.encrypt(given)
                }

                keep != null -> keep
                else -> {
                    val fresh = SECRET_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })

                    generated[action.id] = fresh
                    c.encrypt(fresh)
                }
            }

            action.copy(webhook = spec.copy(secret = text))
        }

        return Secrets(out, errors, generated)
    }

    companion object {
        const val MASK = "********"
        private const val SECRET_PREFIX = "whsec_"

        /** `[1,2]` text of the `serverChoices` column; anything else is "no choices". */
        fun idList(raw: String?): List<Long> =
            runCatching { JsonArray(raw ?: "[]").mapNotNull { (it as? Number)?.toLong() } }.getOrDefault(emptyList())
    }
}

/** The panel session as an [ActionGuard.Caller]: `*` is the platform's `isAdmin` flag, the rest are the platform permission checks. */
internal class RoutingCaller(private val plugin: MarketPlugin, private val context: RoutingContext) : ActionGuard.Caller {
    private val auth: AuthProvider get() = plugin.applicationContext.getBean(AuthProvider::class.java)

    override suspend fun isAdmin(): Boolean = context.get<Boolean>("isAdmin") == true

    override suspend fun canManagePermissionGroups(): Boolean = auth.hasPermission(ManagePermissionGroupsPermission(), context)

    override suspend fun canConsole(serverId: Long): Boolean = auth.hasPermission(ManageServerConsolePermission(), context, serverId)

    override suspend fun canConsoleGlobally(): Boolean = auth.hasPermission(ManageServerConsolePermission(), context)
}
