package com.panomc.plugins.market.core.delivery

import com.panomc.plugins.market.db.model.DeliverySourceType

/**
 * Who and where a delivery goes (08 section 4). Pure: the caller passes what it read from the database.
 * Servers are evaluated at plan time, one delivery row per returned id.
 */
object TargetResolver {
    const val NO_TARGET_SERVER = "NO_TARGET_SERVER"
    const val INVALID_PLAYER = "INVALID_PLAYER"
    const val NO_ACCOUNT = "NO_ACCOUNT"

    /** What the planner knows about servers: [grantedIds] = `serverDao.getAllByPermissionGranted()`, [existingIds] all rows, [connectedIds] connected now. */
    class ServerLookup(
        val grantedIds: Collection<Long> = emptyList(),
        val existingIds: Collection<Long> = emptyList(),
        val connectedIds: Collection<Long> = emptyList()
    )

    /** The order item's own choice and the snapshot's allowed choices (bundle child: the parent line's). */
    class ItemTarget(val targetServerId: Long? = null, val serverChoices: List<Long> = emptyList())

    /**
     * [serverIds] are the ids to create one row for; when [errorCode] is set the single id is `0` and the caller inserts
     * one `FAILED` row with that code. An inline action yields `[0]` and no error.
     */
    class ServerTargets(val serverIds: List<Long>, val errorCode: String? = null) {
        val failed: Boolean get() = errorCode != null
    }

    private fun noTarget() = ServerTargets(listOf(0L), NO_TARGET_SERVER)

    fun servers(action: ProductAction, item: ItemTarget, lookup: ServerLookup): ServerTargets {
        // `PERMISSION via=PANO` is one inline row; the server scope goes into the node context, not into rows.
        if (!action.isServerAction) return ServerTargets(listOf(0L))

        return when (action.serverMode) {
            ServerMode.FIXED ->
                if (action.targetServers.isNotEmpty()) {
                    val existing = lookup.existingIds.toSet()
                    val ids = action.targetServers.distinct().filter { it in existing }

                    if (ids.isEmpty()) noTarget() else ServerTargets(ids)
                } else {
                    val ids = lookup.grantedIds.distinct()

                    if (ids.isEmpty()) noTarget() else ServerTargets(ids)
                }

            ServerMode.BUYER_CHOICE -> {
                val chosen = item.targetServerId

                if (chosen == null || chosen == 0L || chosen !in item.serverChoices) noTarget() else ServerTargets(listOf(chosen))
            }

            ServerMode.ALL_CONNECTED -> {
                val ids = lookup.connectedIds.distinct().sorted()

                if (ids.isEmpty()) noTarget() else ServerTargets(ids)
            }
        }
    }

    /** Payer and recipient of an order as the planner reads them. [payerUserId] is `null` for a guest. */
    class Parties(
        val recipientUsername: String,
        val recipientMcUuid: String? = null,
        val payerUsername: String = recipientUsername,
        val payerUserId: Long? = null,
        val payerMcUuid: String? = null
    )

    /**
     * [username] is the player of the row, [uuidHint] a hint only (may be wrong or absent). [needsConfirmation]: a
     * chargeback action for a guest order whose payer and recipient differ must not run unattended (the payer name of a
     * guest is unauthenticated input and never the target of a ban); the row is created `CANCELLED` / `NEEDS_CONFIRMATION`.
     */
    class Player(val username: String, val uuidHint: String?, val needsConfirmation: Boolean = false) {
        /** `false` means the inline executor answers `FAILED (INVALID_PLAYER)`. */
        val valid: Boolean get() = CommandRenderer.isAdminUsername(username)
    }

    /** [creator] is `market_creator_code.creator`, used for `CREATOR_PAYOUT`. */
    fun player(sourceType: DeliverySourceType, parties: Parties, creator: String? = null): Player =
        when (sourceType) {
            DeliverySourceType.ORDER_ITEM -> Player(parties.recipientUsername, parties.recipientMcUuid)

            DeliverySourceType.CHARGEBACK_ACTION ->
                if (parties.payerUserId != null) {
                    Player(parties.payerUsername, parties.payerMcUuid)
                } else {
                    val differs = !parties.payerUsername.equals(parties.recipientUsername, ignoreCase = true)

                    Player(parties.recipientUsername, parties.recipientMcUuid, needsConfirmation = differs)
                }

            DeliverySourceType.CREATOR_PAYOUT -> Player(creator.orEmpty().trim(), null)
        }

    /**
     * The user id a `CREDIT` goes to: [recipientUserId] when set, else the case-insensitive lookup by name. `null` means
     * `FAILED (NO_ACCOUNT)`: a balance is never parked on an auto-created shell account.
     */
    fun creditAccountUserId(recipientUserId: Long?, username: String, findByUsername: (String) -> Long?): Long? =
        recipientUserId?.takeIf { it > 0 } ?: findByUsername(username)?.takeIf { it > 0 }
}
