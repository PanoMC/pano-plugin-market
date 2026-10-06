package com.panomc.plugins.market.service

import com.panomc.platform.error.NotFound
import com.panomc.plugins.market.core.abuse.BlockEntry
import com.panomc.plugins.market.core.abuse.BlockMatcher
import com.panomc.plugins.market.core.abuse.BlockSubjects
import com.panomc.plugins.market.core.abuse.BlockValue
import com.panomc.plugins.market.core.abuse.PiiMask
import com.panomc.plugins.market.core.time.Clock
import com.panomc.plugins.market.db.dao.MarketBlockDao
import com.panomc.plugins.market.db.model.BlockSource
import com.panomc.plugins.market.db.model.MarketBlock
import com.panomc.plugins.market.db.model.MarketOrder
import com.panomc.plugins.market.db.tx.LockedOrder
import com.panomc.plugins.market.db.tx.MarketDb
import com.panomc.plugins.market.error.BlockAlreadyExists
import com.panomc.plugins.market.error.BuyerBlocked
import com.panomc.plugins.market.error.InvalidBlock
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.spi.payment.ReviewReason
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.SqlConnection
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import com.panomc.plugins.market.core.abuse.BlockType as CoreType
import com.panomc.plugins.market.db.model.BlockType as DbType

/** Which side of an order a block matched (11 section 9.2). */
enum class BlockRole { PAYER, RECIPIENT }

/** What a lookup found: the block row, its type name and the role of the matched subject. Never shown to the buyer (11 section 9.3). */
class BlockHit(val blockId: Long, val type: String, val role: BlockRole)

/**
 * The block list (11 sections 9 and 10; MK-151): the lookup behind every enforcement point, the three panel operations and the chargeback rows' removal by
 * hand. The matching itself is the pure [BlockMatcher]; this class feeds it with the rows a request can match:
 *
 * - one point lookup per exact candidate (`PLAYER` per lower-cased name, `USER` per id, `EMAIL` per address and its `@domain`), on the unique index of
 *   `(type, value)`, so a request never reads the whole table;
 * - the non-expired `IP` rows (ranges cannot be looked up by key), kept in memory and reloaded after every write through this service and after
 *   [ipReloadMs] (60 s), so a range written by another node is live within a minute. The expiry is judged at match time, so an expired range stops
 *   matching without a reload.
 *
 * A hit updates `hitCount` / `lastHitAt` at most once per minute per row (11 section 9.2 step 4): the first hit of a window wins an atomic claim in
 * memory and then adds one with the DAO's atomic `hitCount + 1`. A failing counter never fails the request that was being judged.
 *
 * The seams that enforce it: [asBuyerBlocks] for the quote, checkout, gift redemption and the subscription job; [PaidGuard] [BlockedBuyerGuard] for a verified
 * payment of a pending order (O3, `BLOCKED_BUYER`); [requireOrderBuyerAllowed] for `/pay`, `/payment/continue` and `/bank-transfer/notify`.
 */
class BlockListService(
    private val clock: Clock,
    private val blocks: MarketBlockDao,
    private val users: UserDirectory,
    private val db: MarketDb? = null,
    private val ipReloadMs: Long = IP_RELOAD_MS
) {
    private class IpCache(val loadedAt: Long, val entries: List<BlockEntry>)

    private val ipCache = AtomicReference<IpCache?>(null)
    private val lastHitWritten = ConcurrentHashMap<Long, Long>()

    // ------------------------------------------------------------------------------------------------------ lookup

    /**
     * The first block that matches [payer] (role `PAYER`), else [recipient] (role `RECIPIENT`), else `null`. First hit wins in the order `USER`, `PLAYER`,
     * `EMAIL`, `IP` within one subject set. [recordHit] false: the caller runs inside a transaction that must not touch the block row (a payment's `COMMIT` locks).
     */
    suspend fun check(payer: BlockSubjects, recipient: BlockSubjects? = null, sqlClient: SqlClient, recordHit: Boolean = true): BlockHit? {
        val hit = match(payer, sqlClient)?.let { BlockHit(it.id, it.type.name, BlockRole.PAYER) }
            ?: recipient?.let { match(it, sqlClient) }?.let { BlockHit(it.id, it.type.name, BlockRole.RECIPIENT) }

        if (hit != null && recordHit) recordHit(hit.blockId, sqlClient)

        return hit
    }

    /** The single-set form of 11 section 9.2: every subject is the payer. */
    suspend fun check(subjects: BlockSubjects, sqlClient: SqlClient): BlockHit? = check(subjects, null, sqlClient)

    private suspend fun match(subjects: BlockSubjects, sqlClient: SqlClient): BlockEntry? {
        val now = clock.now()
        val candidates = ArrayList<BlockEntry>()

        suspend fun point(type: DbType, value: String) {
            blocks.getByTypeAndValue(type, value, sqlClient)?.let { candidates += BlockEntry(it.id, coreType(it.type), it.value, it.expiresAt) }
        }

        for (name in subjects.usernames.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()) point(DbType.PLAYER, name)

        for (id in subjects.userIds) point(DbType.USER, id.toString())

        for (email in subjects.emails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()) {
            point(DbType.EMAIL, email)

            BlockValue.domainOf(email)?.let { point(DbType.EMAIL, "@$it") }
        }

        if (subjects.ip != null) candidates += ipEntries(sqlClient, now)

        return BlockMatcher(candidates).match(subjects, now)
    }

    private suspend fun ipEntries(sqlClient: SqlClient, now: Long): List<BlockEntry> {
        val cached = ipCache.get()

        if (cached != null && now - cached.loadedAt < ipReloadMs && now >= cached.loadedAt) return cached.entries

        val loaded = blocks.getActiveByType(DbType.IP, now, sqlClient).map { BlockEntry(it.id, CoreType.IP, it.value, it.expiresAt) }

        ipCache.set(IpCache(now, loaded))

        return loaded
    }

    /** The IP rows are read again by the next lookup (called after every write through this service). */
    fun reloadIps() = ipCache.set(null)

    private suspend fun recordHit(id: Long, sqlClient: SqlClient) {
        // never on a transaction's connection: the counter would take an X lock on the block row under the caller's order / subscription locks and could be the
        // deadlock victim of a block write (O11), rolling that transaction back while its owner carries on (11 section 9.2 step 4)
        if (sqlClient is SqlConnection) return

        val now = clock.now()
        var claimed = false

        lastHitWritten.compute(id) { _, previous ->
            if (previous == null || now - previous >= HIT_INTERVAL_MS || now < previous) {
                claimed = true

                now
            } else previous
        }

        if (!claimed) return

        try {
            blocks.recordHit(id, now, sqlClient)
        } catch (e: Exception) {
            logger.warn("the hit of block {} could not be counted: {}", id, e.message)
        }
    }

    // ------------------------------------------------------------------------------------------------------ enforcement seams

    /** The seam of [CheckoutService] and [SubscriptionService]: `true` when the payer or the recipient is blocked (the payer's own IP counts, the recipient has none). */
    fun asBuyerBlocks(recordHit: Boolean = true): BuyerBlocks = object : SourcedBuyerBlocks {
        override suspend fun blockedBy(payerUsername: String?, recipientUsername: String?, email: String?, clientIp: String?, userId: Long?, sqlClient: SqlClient): BlockSource? =
            sourceOfHit(payerUsername, recipientUsername, email, clientIp, userId, sqlClient, recordHit)
    }

    /**
     * [recordHit] false: the caller runs under row locks and must not touch the block row (the subscription job). A call on a transaction connection never counts
     * a hit either way.
     */
    suspend fun blocked(payerUsername: String?, recipientUsername: String?, email: String?, clientIp: String?, userId: Long?, sqlClient: SqlClient, recordHit: Boolean = true): Boolean =
        hitOf(payerUsername, recipientUsername, email, clientIp, userId, sqlClient, recordHit) != null

    /** The source (`MANUAL` / `CHARGEBACK`) of the block [blocked] matches, `null` when there is none (WIRE-2: the end reason of a blocked subscription). */
    private suspend fun sourceOfHit(
        payerUsername: String?, recipientUsername: String?, email: String?, clientIp: String?, userId: Long?, sqlClient: SqlClient, recordHit: Boolean
    ): BlockSource? {
        val hit = hitOf(payerUsername, recipientUsername, email, clientIp, userId, sqlClient, recordHit) ?: return null

        // a row deleted between the lookup and this read still blocked the buyer a moment ago: it counts as a manual block
        return blocks.getById(hit.blockId, sqlClient)?.source ?: BlockSource.MANUAL
    }

    private suspend fun hitOf(
        payerUsername: String?, recipientUsername: String?, email: String?, clientIp: String?, userId: Long?, sqlClient: SqlClient, recordHit: Boolean
    ): BlockHit? {
        val payer = BlockSubjects(
            usernames = setOfNotNull(payerUsername?.takeIf { it.isNotBlank() }), userIds = setOfNotNull(userId), emails = setOfNotNull(email?.takeIf { it.isNotBlank() }), ip = clientIp
        )
        // the goods-receiving name: the typed recipient, else the payer. A logged-in buyer's own account is the payer's userId already; every other name (a gift, or a guest
        // typing a registered name, which RecipientResolver.self() puts on that account) is looked up so a USER block follows the account (11 section 9.1)
        val name = recipientUsername?.takeIf { it.isNotBlank() } ?: payerUsername?.takeIf { it.isNotBlank() }
        val ownAccount = userId != null && name != null && name.equals(payerUsername, ignoreCase = true)
        val recipient = if (name == null || ownAccount) null else BlockSubjects(usernames = setOf(name), userIds = setOfNotNull(users.byUsername(name, sqlClient)?.id))

        return check(payer, recipient, sqlClient, recordHit)
    }

    /**
     * `/pay`, `/payment/continue`, `/bank-transfer/notify` (11 section 9.3): the payer and the recipient of [order] (the judgement of the payment's guard, so a
     * blocked account is not charged first and diverted after) and the caller's current IP. 403 `BUYER_BLOCKED` without any detail. Never judges an order that is not the buyer's to pay (the route resolved the owner already).
     */
    suspend fun requireOrderBuyerAllowed(order: MarketOrder, clientIp: String?, sqlClient: SqlClient) {
        if (check(payerOf(order, clientIp), recipientOf(order), sqlClient) != null) throw BuyerBlocked()
    }

    /** O2 -> O3 (11 section 9.3, PP-5): the payer and the recipient of the order, without an IP, under the payment's `COMMIT` locks (no counter update there). */
    suspend fun orderBlocked(order: MarketOrder, sqlClient: SqlClient): Boolean =
        check(payerOf(order, null), recipientOf(order), sqlClient, recordHit = false) != null

    private fun payerOf(order: MarketOrder, clientIp: String?) = BlockSubjects(
        usernames = setOfNotNull(order.playerUsername.takeIf { it.isNotBlank() }), userIds = setOfNotNull(order.userId),
        emails = setOfNotNull(order.email?.takeIf { it.isNotBlank() }), ip = clientIp
    )

    private fun recipientOf(order: MarketOrder): BlockSubjects? {
        if (order.recipientKey == order.buyerKey) return null

        val name = order.recipientUsername.takeIf { it.isNotBlank() }
        val id = order.recipientKey.takeIf { it.startsWith("u:") }?.substring(2)?.toLongOrNull()

        return if (name == null && id == null) null else BlockSubjects(usernames = setOfNotNull(name), userIds = setOfNotNull(id))
    }

    // ------------------------------------------------------------------------------------------------------ panel operations

    /** A block row as the panel list shows it (04 section 7): the values are unmasked on purpose, the list is the tool to manage them. */
    class BlockView(val block: MarketBlock, val createdByUsername: String?)

    class BlockPage(val rows: List<BlockView>, val total: Long)

    /** `GET /blocks`: newest first; `search` is a prefix match on the value. */
    suspend fun list(type: DbType?, source: BlockSource?, search: String?, page: Int, pageSize: Int, sqlClient: SqlClient): BlockPage {
        val needle = search?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val all = blocks.getAll(sqlClient).filter { (type == null || it.type == type) && (source == null || it.source == source) && (needle == null || it.value.lowercase().startsWith(needle)) }
        val window = all.drop((page - 1) * pageSize).take(pageSize)
        val names = HashMap<Long, String?>()

        for (id in window.mapNotNull { it.createdBy }.distinct()) names[id] = users.usernameOf(id, sqlClient)

        return BlockPage(window.map { BlockView(it, it.createdBy?.let(names::get)) }, all.size.toLong())
    }

    /** Who creates a block: the `SELF` rule needs the actor's account and name (11 section 9.1). */
    class Actor(val userId: Long, val username: String)

    /**
     * `POST /blocks` (11 section 9.4): `source = MANUAL`, `createdBy` = the actor. 400 `INVALID_BLOCK` (`reason`: `TYPE`, `VALUE`, `RANGE_TOO_WIDE`, `SELF`, `REASON`,
     * `EXPIRES`), 409 `BLOCK_ALREADY_EXISTS`; an existing but expired row is replaced instead. Returns the new row.
     */
    suspend fun create(type: String?, value: String?, reason: String?, expiresAt: Long?, actor: Actor): MarketBlock {
        val database = db ?: throw IllegalStateException("this BlockListService was built without a transaction helper")
        val coreType = CoreType.entries.firstOrNull { it.name == type?.trim()?.uppercase() } ?: throw InvalidBlock("TYPE")
        val stored = BlockValue.normalize(coreType, value) ?: throw InvalidBlock(if (coreType == CoreType.IP && BlockValue.isRangeTooWide(value)) "RANGE_TOO_WIDE" else "VALUE")
        val note = reason?.trim()?.takeIf { it.isNotEmpty() }

        if (note != null && note.length > MAX_REASON_LENGTH) throw InvalidBlock("REASON")

        val now = clock.now()

        if (expiresAt != null && expiresAt <= now) throw InvalidBlock("EXPIRES")

        if ((coreType == CoreType.USER && stored == actor.userId.toString()) || (coreType == CoreType.PLAYER && stored == actor.username.lowercase())) throw InvalidBlock("SELF")

        val dbType = DbType.valueOf(coreType.name)

        val created = database.tx { conn ->
            if (coreType == CoreType.USER && users.usernameOf(stored.toLong(), conn) == null) throw InvalidBlock("VALUE")

            val row = MarketBlock(
                type = dbType, value = stored, reason = note, source = BlockSource.MANUAL, orderId = null, createdBy = actor.userId, expiresAt = expiresAt,
                createdAt = now, updatedAt = now
            )

            var id = blocks.add(row, conn)

            if (id == null) {
                val existing = blocks.getByTypeAndValue(dbType, stored, conn)

                // a row that has expired blocks nobody: it is replaced, not a conflict (the unique key needs it gone first)
                if (existing != null && existing.expiresAt != null && existing.expiresAt <= now) {
                    blocks.delete(existing.id, conn)

                    id = blocks.add(row, conn)
                }
            }

            if (id == null) throw BlockAlreadyExists()

            blocks.getById(id, conn) ?: throw IllegalStateException("block $id was not written")
        }

        reloadIps()

        return created
    }

    /** `DELETE /blocks/:id`: 404 when missing; the deleted row comes back for the activity log. A `CHARGEBACK` row may be removed by hand too. */
    suspend fun delete(id: Long, sqlClient: SqlClient): MarketBlock {
        val row = blocks.getById(id, sqlClient) ?: throw NotFound()

        if (!blocks.delete(id, sqlClient)) throw NotFound()

        lastHitWritten.remove(id)
        reloadIps()

        return row
    }

    companion object {
        const val IP_RELOAD_MS = 60_000L
        const val HIT_INTERVAL_MS = 60_000L
        const val MAX_REASON_LENGTH = 255

        private val logger = LoggerFactory.getLogger(BlockListService::class.java)

        private fun coreType(type: DbType): CoreType = CoreType.valueOf(type.name)

        /** The value of a block as an activity log shows it (11 section 15): an e-mail and an IP are masked, a name or an id is not personal data of a third party. */
        fun maskedValue(type: DbType, value: String): String = when (type) {
            DbType.EMAIL -> if (value.startsWith("@")) value else PiiMask.email(value) ?: value
            DbType.IP -> PiiMask.ip(value) ?: value
            else -> value
        }
    }
}

/**
 * The blocked-buyer check of a verified payment (06 section 6.8, 11 section 9.3 and PP-5): the payer or the recipient of the `PENDING` order is blocked now, so
 * O2 becomes O3 (`REVIEW`, `BLOCKED_BUYER`): the money is recorded on the order and nothing is delivered; the admin accepts or refunds.
 */
class BlockedBuyerGuard(private val blockList: BlockListService) : PaidGuard {
    override suspend fun divert(conn: SqlConnection, locked: LockedOrder, order: MarketOrder): PaidDiversion? =
        if (blockList.orderBlocked(order, conn)) PaidDiversion(ReviewReason.BLOCKED_BUYER, NOTE) else null

    companion object {
        const val NOTE = "blocked buyer"
    }
}
