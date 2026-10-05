package com.panomc.plugins.market.support

import io.vertx.kotlin.coroutines.coAwait
import io.vertx.mysqlclient.MySQLException
import io.vertx.sqlclient.Pool

/** A violated global invariant (17 sections 7 and 14): [id] is the invariant (`I5`), [rows] the offending rows. */
class InvariantViolation(val id: String, val rows: List<String>) :
    AssertionError("invariant $id violated by ${rows.size} row(s): ${rows.take(10)}")

/**
 * The global invariants of 17 section 7 (I1 to I22, with I3b and I11b): every check is one query that must return
 * zero rows; the table placeholders are written without the prefix (`{market_product}`). A check whose tables do not
 * exist in the database, or whose columns do not exist yet (error 1054: a database in the middle of the migration
 * chain, whose tables are there but not yet widened), is skipped and reported through [lastRun]; `MarketSchemaIT` asserts
 * the schema and `InvariantCheckerSelfIT` asserts that on the complete schema nothing is skipped, so a typo in a query
 * cannot hide there.
 *
 * The database test bases call `assertAll(pool)` after every test and the migration base `assertAll(pool, true)`
 * (the legacy-aware forms of I6, I8, I11 and I17, see [Options.legacy]). [violations] returns every violated invariant
 * instead of throwing, which is what `InvariantCheckerSelfIT` asserts on.
 *
 * Judgement calls where the table of 17 section 7 leaves room (recorded in `evidence/MK-032.md`):
 * - I3b: the second half ("an order without a `HOLD` tx has `creditAmount = 0`") skips `source = 'RENEWAL'` orders,
 *   which I22 owns, so one broken row names one id.
 * - I12: the "stock of released items returned" part is a per-test assertion on the product's expected stock, as the
 *   table says; the checker covers the order and its redemptions.
 * - I11 and I12 leave `reservationState = 'HELD'` to I16: every `HELD` order outside `PENDING` / `REVIEW` is either a paid
 *   or a released one, so without this the same row would name I16 and I11 or I12.
 * - I14: in legacy mode the earnings of a creator code are compared only when it has earning rows (a version 2 code
 *   carries earnings from before the table existed).
 * - I14, I15: the duplicate halves are made impossible by unique indexes (`SchemaVerifierIT` proves the indexes); they
 *   are still queried, only the other halves can be seeded in the self-test.
 * - I17: the paid, non-test orders are `COMPLETED` and `PARTIALLY_REFUNDED` ones; a `REFUNDED` or `CHARGEBACK` order
 *   contributes zero by definition (its units are all refunded) and the one-shot `soldCount` fixup agrees.
 * - I19 / I21: a `SENDING` row without `claimedUntil` counts as stuck; "effective" rows of I21 follow 08 section 13
 *   (highest `attemptGroup` per logical delivery, `CANCELLED` rows dropped).
 */
object InvariantChecker {
    /** Parameters of a run: what the checks need that is not in the database. */
    data class Options(
        /**
         * The database may still carry rows of a version 2 install that the one-shot fixups did not convert yet:
         * I6 and I17 wait for the marker row of their fixup (`fixup:legacyUsedCount`, `fixup:soldCount`), I8 and I11 leave
         * unconverted (`publicId IS NULL AND buyerKey = ''`) and `LEGACY` orders alone (they carry no market pricing).
         */
        val legacy: Boolean = false,
        /** "Now" for the age based checks I18 and I19; `null` = [nowProvider]. */
        val nowMs: Long? = null,
        /** `MarketConfig.revokeOnRefund` / `revokeOnChargeback`, read by I20. */
        val revokeOnRefund: Boolean = true,
        val revokeOnChargeback: Boolean = true
    )

    class Check(val id: String, val tables: List<String>, val sql: (Context) -> String) {
        constructor(id: String, tables: List<String>, sql: String) : this(id, tables, { sql })
    }

    class Context(val options: Options, val nowMs: Long) {
        val legacy get() = options.legacy

        /** The order rows a legacy install has not converted yet (the predicate of the first fixup, 01 section 14.2). */
        fun skipLegacyOrders(alias: String) =
            if (legacy) " AND NOT ($alias.`publicId` IS NULL AND $alias.`buyerKey` = '') AND $alias.`source` <> 'LEGACY'" else ""

        fun skipUnconvertedOrders(alias: String) =
            if (legacy) " AND NOT ($alias.`publicId` IS NULL AND $alias.`buyerKey` = '')" else ""
    }

    private const val UNKNOWN_COLUMN = 1054
    private const val UNKNOWN_TABLE = 1146
    private const val PAID = "'COMPLETED','PARTIALLY_REFUNDED','REFUNDED','CHARGEBACK'"
    private const val RELEASED_STATUS = "'EXPIRED','CANCELLED','FAILED'"
    private const val OPEN_PAYMENT = "'CREATED','PENDING','PROCESSING'"

    private fun fixupMarker(name: String, context: Context) =
        if (context.legacy) " AND EXISTS (SELECT 1 FROM `{market_sequence}` s WHERE s.`name` = 'fixup:$name')" else ""

    private fun counter(table: String, kind: String, limit: String, context: Context): String =
        "SELECT x.`id` FROM `{$table}` x WHERE x.`usedCount` - x.`legacyUsedCount` <> " +
            "(SELECT COUNT(*) FROM `{market_redemption}` r WHERE r.`kind` = '$kind' AND r.`refId` = x.`id` AND r.`state` IN ('HELD','APPLIED'))" +
            fixupMarker("legacyUsedCount", context) + limit

    val checks: List<Check> = listOf(
        // --- credit ledger (07, 01 section 7) ---
        Check("I1", listOf("market_credit_entry"), "SELECT `txId` FROM `{market_credit_entry}` GROUP BY `txId` HAVING SUM(`amount`) <> 0"),
        Check(
            "I2", listOf("market_credit_account", "market_credit_entry"),
            "SELECT a.`id` FROM `{market_credit_account}` a LEFT JOIN `{market_credit_entry}` e ON e.`accountId` = a.`id` " +
                "GROUP BY a.`id`, a.`balance` HAVING a.`balance` <> COALESCE(SUM(e.`amount`), 0)"
        ),
        Check(
            "I3", listOf("market_credit_account", "market_credit_tx"),
            "SELECT a.`id` FROM `{market_credit_account}` a WHERE a.`balance` < 0 AND (a.`systemKey` = 'HOLD' OR (a.`type` = 'USER' AND NOT EXISTS " +
                "(SELECT 1 FROM `{market_credit_tx}` t WHERE t.`userId` = a.`userId` AND t.`idempotencyKey` LIKE 'dispute:%')))"
        ),
        Check(
            "I3b", listOf("market_order", "market_credit_tx"),
            "SELECT o.`id` FROM `{market_order}` o WHERE o.`reservationState` = 'HELD' AND " +
                "(SELECT COALESCE(SUM(CASE t.`type` WHEN 'HOLD' THEN t.`amount` WHEN 'RELEASE' THEN -t.`amount` ELSE 0 END), 0) " +
                "FROM `{market_credit_tx}` t WHERE t.`orderId` = o.`id` AND t.`type` IN ('HOLD','RELEASE')) <> o.`creditAmount`"
        ),
        Check(
            "I3b", listOf("market_order", "market_credit_tx"),
            "SELECT o.`id` FROM `{market_order}` o WHERE o.`creditAmount` > 0 AND o.`source` <> 'RENEWAL' AND " +
                "NOT EXISTS (SELECT 1 FROM `{market_credit_tx}` t WHERE t.`orderId` = o.`id` AND t.`type` = 'HOLD')"
        ),
        Check(
            "I4", listOf("market_credit_account", "market_order"),
            "SELECT a.`id` FROM `{market_credit_account}` a WHERE a.`systemKey` = 'HOLD' AND " +
                "a.`balance` <> (SELECT COALESCE(SUM(o.`creditAmount`), 0) FROM `{market_order}` o WHERE o.`reservationState` = 'HELD')"
        ),
        // --- stock and codes ---
        Check("I5", listOf("market_product"), "SELECT `id` FROM `{market_product}` WHERE `stock` < 0"),
        Check("I5", listOf("market_product_variant"), "SELECT `id` FROM `{market_product_variant}` WHERE `stock` < 0"),
        Check("I6", listOf("market_coupon", "market_redemption", "market_sequence")) { counter("market_coupon", "COUPON", "", it) },
        Check("I6", listOf("market_creator_code", "market_redemption", "market_sequence")) { counter("market_creator_code", "CREATOR_CODE", "", it) },
        Check("I6", listOf("market_discount", "market_redemption", "market_sequence")) { counter("market_discount", "DISCOUNT", "", it) },
        Check(
            "I6", listOf("market_gift", "market_redemption"),
            "SELECT x.`id` FROM `{market_gift}` x WHERE x.`usedCount` <> " +
                "(SELECT COUNT(*) FROM `{market_redemption}` r WHERE r.`kind` = 'GIFT' AND r.`refId` = x.`id` AND r.`state` IN ('HELD','APPLIED'))"
        ),
        Check("I7", listOf("market_coupon"), "SELECT `id` FROM `{market_coupon}` WHERE `redeemLimit` IS NOT NULL AND `usedCount` > `redeemLimit`"),
        Check("I7", listOf("market_gift"), "SELECT `id` FROM `{market_gift}` WHERE `redeemLimit` IS NOT NULL AND `usedCount` > `redeemLimit`"),
        // --- orders and payments ---
        Check("I8", listOf("market_order", "market_order_item")) {
            "SELECT o.`id` FROM `{market_order}` o WHERE o.`pricingMode` = 'MARKET'${it.skipLegacyOrders("o")} AND " +
                "(o.`totalPrice` <> (SELECT COALESCE(SUM(i.`lineTotal`), 0) FROM `{market_order_item}` i WHERE i.`orderId` = o.`id`) + o.`shippingTotal` + o.`paymentFee` " +
                "OR o.`gatewayAmount` + o.`creditValue` <> o.`totalPrice`)"
        },
        Check(
            "I9", listOf("market_order", "market_refund"),
            "SELECT o.`id` FROM `{market_order}` o WHERE o.`refundedTotal` > o.`totalPrice` OR o.`refundedGatewayAmount` > o.`paidAmount` OR " +
                "o.`refundedTotal` <> (SELECT COALESCE(SUM(r.`amount`), 0) FROM `{market_refund}` r WHERE r.`orderId` = o.`id` AND r.`status` = 'SUCCEEDED')"
        ),
        Check(
            "I10", listOf("market_payment"),
            "SELECT `orderId` FROM `{market_payment}` WHERE `status` IN ($OPEN_PAYMENT) GROUP BY `orderId` HAVING COUNT(*) > 1"
        ),
        Check("I11", listOf("market_order")) {
            "SELECT o.`id` FROM `{market_order}` o WHERE o.`status` IN ($PAID)${it.skipUnconvertedOrders("o")} AND " +
                "(o.`paidAt` IS NULL OR o.`reservationState` NOT IN ('COMMITTED','HELD') OR " +
                "(o.`paymentId` IS NULL AND o.`source` <> 'LEGACY' AND o.`paymentMethodId` <> 'manual'))"
        },
        Check(
            "I11b", listOf("market_order", "market_payment"),
            "SELECT o.`id` FROM `{market_order}` o JOIN `{market_payment}` p ON p.`id` = o.`paymentId` " +
                "WHERE o.`status` = 'COMPLETED' AND p.`status` = 'SUCCEEDED' AND p.`duplicate` = 0 AND " +
                "(p.`amount` <> o.`gatewayAmount` OR p.`creditAmount` <> o.`creditAmount`)"
        ),
        Check(
            "I12", listOf("market_order"),
            "SELECT `id` FROM `{market_order}` WHERE `status` IN ($RELEASED_STATUS) AND `reservationState` NOT IN ('RELEASED','NONE','HELD')"
        ),
        Check(
            "I12", listOf("market_order", "market_redemption"),
            "SELECT r.`id` FROM `{market_redemption}` r JOIN `{market_order}` o ON o.`id` = r.`orderId` " +
                "WHERE o.`status` IN ($RELEASED_STATUS) AND r.`state` <> 'RELEASED'"
        ),
        Check(
            "I13", listOf("market_payment"),
            "SELECT `orderId` FROM `{market_payment}` WHERE `status` = 'SUCCEEDED' AND `duplicate` = 0 GROUP BY `orderId` HAVING COUNT(*) > 1"
        ),
        // --- creators, invoices ---
        Check(
            "I14", listOf("market_creator_earning"),
            "SELECT `orderId` FROM `{market_creator_earning}` GROUP BY `orderId`, `creatorCodeId` HAVING COUNT(*) > 1"
        ),
        Check("I14", listOf("market_creator_code", "market_creator_earning")) {
            "SELECT c.`id` FROM `{market_creator_code}` c WHERE c.`earnings` <> " +
                "(SELECT COALESCE(SUM(e.`amount` - e.`reversedAmount`), 0) FROM `{market_creator_earning}` e WHERE e.`creatorCodeId` = c.`id`)" +
                // a version 2 code carries earnings that were counted before the earning rows existed: in legacy mode only codes with rows are compared
                (if (it.legacy) " AND EXISTS (SELECT 1 FROM `{market_creator_earning}` x WHERE x.`creatorCodeId` = c.`id`)" else "")
        },
        Check("I15", listOf("market_invoice"), "SELECT `orderId` FROM `{market_invoice}` GROUP BY `orderId`, `type`, `refundId` HAVING COUNT(*) > 1"),
        Check("I15", listOf("market_invoice"), "SELECT `series` FROM `{market_invoice}` GROUP BY `series` HAVING MAX(`sequence`) - MIN(`sequence`) + 1 <> COUNT(*)"),
        Check(
            "I16", listOf("market_order"),
            "SELECT `id` FROM `{market_order}` WHERE `reservationState` = 'HELD' AND `status` NOT IN ('PENDING','REVIEW')"
        ),
        Check("I17", listOf("market_product", "market_order_item", "market_order", "market_sequence")) {
            "SELECT p.`id` FROM `{market_product}` p WHERE p.`soldCount` <> COALESCE((SELECT SUM(i.`quantity` - i.`refundedQuantity`) " +
                "FROM `{market_order_item}` i JOIN `{market_order}` o ON o.`id` = i.`orderId` " +
                "WHERE i.`productId` = p.`id` AND o.`status` IN ('COMPLETED','PARTIALLY_REFUNDED') AND o.`testMode` = 0), 0)" +
                fixupMarker("soldCount", it)
        },
        // --- queues ---
        Check("I18", listOf("market_payment_event")) {
            "SELECT `id` FROM `{market_payment_event}` WHERE `direction` = 'IN' AND " +
                "((`status` = 'RECEIVED' AND `createdAt` < ${it.nowMs - 60_000}) OR " +
                "(`status` = 'FAILED' AND `attempts` < 10 AND `nextAttemptAt` IS NOT NULL AND `nextAttemptAt` < ${it.nowMs - 120_000}))"
        },
        Check(
            "I19", listOf("market_delivery"),
            "SELECT `orderItemId` FROM `{market_delivery}` WHERE `orderItemId` IS NOT NULL " +
                "GROUP BY `orderItemId`, `actionId`, `serverId`, `unitIndex`, `phase`, `attemptGroup` HAVING COUNT(*) > 1"
        ),
        Check("I19", listOf("market_delivery")) {
            "SELECT `id` FROM `{market_delivery}` WHERE `status` = 'SENDING' AND (`claimedUntil` IS NULL OR `claimedUntil` < ${it.nowMs - 60_000})"
        },
        Check("I20", listOf("market_entitlement", "market_order")) {
            val clauses = listOfNotNull(
                "o.`status` = 'REFUNDED'".takeIf { _ -> it.options.revokeOnRefund },
                "o.`status` = 'CHARGEBACK'".takeIf { _ -> it.options.revokeOnChargeback }
            )
            if (clauses.isEmpty()) "SELECT 1 FROM DUAL WHERE 1 = 0"
            else "SELECT e.`id` FROM `{market_entitlement}` e JOIN `{market_order}` o ON o.`id` = e.`orderId` " +
                "WHERE e.`status` = 'ACTIVE' AND (${clauses.joinToString(" OR ")})"
        },
        Check(
            "I21", listOf("market_order", "market_delivery"),
            "SELECT o.`id` FROM `{market_order}` o WHERE o.`fulfillmentStatus` = 'REVOKED' AND EXISTS (" +
                "SELECT 1 FROM `{market_delivery}` d WHERE d.`orderId` = o.`id` AND d.`phase` IN ('REVOKE','EXPIRE') AND d.`status` NOT IN ('CONFIRMED','CANCELLED') " +
                "AND d.`attemptGroup` = (SELECT MAX(d2.`attemptGroup`) FROM `{market_delivery}` d2 WHERE d2.`orderItemId` <=> d.`orderItemId` AND d2.`actionId` = d.`actionId` " +
                "AND d2.`serverId` = d.`serverId` AND d2.`unitIndex` = d.`unitIndex` AND d2.`phase` = d.`phase` AND d2.`status` <> 'CANCELLED'))"
        ),
        Check(
            "I22", listOf("market_order", "market_credit_tx"),
            "SELECT o.`id` FROM `{market_order}` o WHERE o.`source` = 'RENEWAL' AND o.`creditAmount` > 0 AND " +
                "NOT EXISTS (SELECT 1 FROM `{market_credit_tx}` t WHERE t.`orderId` = o.`id` AND t.`type` = 'HOLD')"
        )
    )

    /** The ids I1 to I22 in order, with the two lettered sub-invariants after their parent. */
    val ids: List<String> = (1..22).flatMap { n ->
        when (n) {
            3 -> listOf("I3", "I3b")
            11 -> listOf("I11", "I11b")
            else -> listOf("I$n")
        }
    }

    /** What the most recent run did: the ids of the checks that ran and the ones skipped for a missing table. */
    data class Run(val ran: List<String>, val skipped: List<String>)

    @Volatile
    var lastRun: Run = Run(emptyList(), emptyList())
        private set

    /** "Now" of I18 and I19 when a run does not pass one: `TestWiring` points it at its `FakeClock`. */
    @Volatile
    var nowProvider: () -> Long = { System.currentTimeMillis() }

    suspend fun assertAll(pool: Pool) = assertAll(pool, Options())

    /** [legacy] = the database may still carry unconverted rows of a version 2 install ([Options.legacy]). */
    suspend fun assertAll(pool: Pool, legacy: Boolean) = assertAll(pool, Options(legacy = legacy))

    suspend fun assertAll(pool: Pool, options: Options) {
        val violations = violations(pool, options)
        if (violations.isNotEmpty()) {
            throw violations.first().also { first -> violations.drop(1).forEach { first.addSuppressed(it) } }
        }
    }

    /** Runs every check and returns one [InvariantViolation] per violated check, in the order of [checks]. */
    suspend fun violations(pool: Pool, options: Options = Options()): List<InvariantViolation> {
        val prefix = MarketTestDb.TABLE_PREFIX
        val existing = pool.query("SELECT `table_name` AS t FROM information_schema.tables WHERE table_schema = DATABASE()")
            .execute().coAwait().map { it.getString("t").lowercase() }.toSet()
        val context = Context(options, options.nowMs ?: nowProvider())
        val ran = ArrayList<String>()
        val skipped = ArrayList<String>()
        val violations = ArrayList<InvariantViolation>()
        for (check in checks) {
            val names = check.tables.associateWith { "$prefix$it" }
            if (names.values.any { it.lowercase() !in existing }) {
                skipped += check.id
                continue
            }
            var statement = check.sql(context)
            names.forEach { (short, full) -> statement = statement.replace("{$short}", full) }
            val rows = try {
                pool.query(statement).execute().coAwait().map { row ->
                    (0 until row.size()).joinToString(",") { i -> row.getValue(i)?.toString() ?: "null" }
                }
            } catch (e: MySQLException) {
                if (e.errorCode != UNKNOWN_COLUMN && e.errorCode != UNKNOWN_TABLE) throw e
                skipped += check.id
                continue
            }
            ran += check.id
            if (rows.isNotEmpty()) violations += InvariantViolation(check.id, rows)
        }
        lastRun = Run(ran, skipped)
        return violations
    }
}
