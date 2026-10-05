package com.panomc.plugins.market.db

import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.tx.MarketDb
import io.vertx.kotlin.coroutines.coAwait
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import io.vertx.sqlclient.Tuple
import org.slf4j.LoggerFactory

/**
 * The one place of the market database schema (01 section 14.1 rule 1): the DDL of every table, the data fixups and
 * the start-up routine that applies both. `Dao.init`, the migration handlers and `MarketBootstrap` all go through
 * here, so a fresh install and a migrated install end with the same schema.
 *
 * A table is declared once as a [Table] (columns, keys). [Table.createSql] renders the idempotent
 * `CREATE TABLE IF NOT EXISTS` from the declaration, and [SchemaVerifier] derives what it expects from the same
 * declaration, so DDL and verification cannot drift apart.
 *
 * Nothing in this object throws for a database error: every statement is wrapped, logged and recorded in the
 * [EnsureReport] (01 section 14.1 rule 3). A schema the platform has to live with is reported by
 * [SchemaVerifier], not by an exception that would shut the host down.
 */
object MarketSchema {
    private val logger = LoggerFactory.getLogger(MarketSchema::class.java)

    // --- declaration --------------------------------------------------------------------------------------------

    /**
     * One column. [type] is the SQL type as written (`VARCHAR(255)`, `BIGINT(20)`, `MEDIUMTEXT`); [default] is the
     * literal SQL default (`0`, `'ACTIVE'`) or `null` for none.
     */
    data class Column(
        val name: String,
        val type: String,
        val nullable: Boolean = false,
        val default: String? = null,
        val autoIncrement: Boolean = false
    ) {
        /** `information_schema.COLUMNS.DATA_TYPE` this column must have. */
        val dataType: String = type.substringBefore('(').trim().lowercase()

        /** `CHARACTER_MAXIMUM_LENGTH` for the character types, `null` otherwise (text types are not length-checked). */
        val charLength: Long? =
            if (dataType == "varchar" || dataType == "char")
                type.substringAfter('(', "").substringBefore(')').trim().toLongOrNull()
            else null

        internal fun ddl(): String = buildString {
            append('`').append(name).append("` ").append(type)
            if (!nullable) append(" NOT NULL")
            if (autoIncrement) append(" AUTO_INCREMENT")
            if (default != null) append(" DEFAULT ").append(default)
        }
    }

    /** One index. [name] is `PRIMARY` for the primary key. */
    data class Key(val name: String, val columns: List<String>, val unique: Boolean) {
        val primary: Boolean get() = name == PRIMARY

        internal fun ddl(): String {
            val cols = columns.joinToString(", ") { "`$it`" }
            return when {
                primary -> "PRIMARY KEY ($cols)"
                unique -> "UNIQUE KEY `$name` ($cols)"
                else -> "KEY `$name` ($cols)"
            }
        }

        companion object {
            const val PRIMARY = "PRIMARY"
        }
    }

    /**
     * A table without its prefix ([name] = `market_coupon`). [alters] are idempotent `ALTER TABLE` statements
     * (with `{t}` standing for the prefixed table name) that bring a table created by an older plugin version up to
     * the declared shape; [ensure] runs them after the `CREATE`.
     */
    class Table(
        val name: String,
        val comment: String,
        val columns: List<Column>,
        val keys: List<Key>,
        val alters: List<String> = emptyList()
    ) {
        fun physicalName(prefix: String) = prefix + name

        /** The idempotent `CREATE TABLE IF NOT EXISTS` statement. */
        fun createSql(prefix: String): String {
            val parts = columns.map { it.ddl() } + keys.map { it.ddl() }
            return "CREATE TABLE IF NOT EXISTS `${physicalName(prefix)}` (\n  " + parts.joinToString(",\n  ") +
                "\n) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='$comment'"
        }

        /** Idempotent `ALTER` statements with the table name filled in. */
        fun alterSql(prefix: String): List<String> = alters.map { it.replace("{t}", "`${physicalName(prefix)}`") }
    }

    private class TableBuilder(private val name: String, private val comment: String) {
        private val columns = ArrayList<Column>()
        private val keys = ArrayList<Key>()
        private val alters = ArrayList<String>()

        fun id() {
            columns += Column("id", "bigint", autoIncrement = true)
            keys += Key(Key.PRIMARY, listOf("id"), unique = true)
        }

        fun str(name: String, length: Int, default: String? = null, nullable: Boolean = false) {
            columns += Column(name, "VARCHAR($length)", nullable, default?.let { "'$it'" })
        }

        fun char(name: String, length: Int) {
            columns += Column(name, "CHAR($length)")
        }

        fun text(name: String, nullable: Boolean = true) {
            columns += Column(name, "MEDIUMTEXT", nullable)
        }

        fun decimal(name: String, precision: Int, scale: Int, nullable: Boolean = false) {
            columns += Column(name, "DECIMAL($precision,$scale)", nullable)
        }

        fun bigint(name: String, nullable: Boolean = false, default: Long? = null) {
            columns += Column(name, "BIGINT", nullable, default?.toString())
        }

        fun int(name: String, nullable: Boolean = false, default: Int? = null) {
            columns += Column(name, "INT", nullable, default?.toString())
        }

        fun flag(name: String, default: Int = 0) {
            columns += Column(name, "TINYINT(1)", default = default.toString())
        }

        fun double(name: String, nullable: Boolean = true) {
            columns += Column(name, "DOUBLE", nullable)
        }

        fun timestamps() {
            columns += Column("createdAt", "BIGINT(20)")
            columns += Column("updatedAt", "BIGINT(20)")
        }

        fun key(name: String, vararg columns: String) {
            keys += Key(name, columns.toList(), unique = false)
        }

        fun unique(name: String, vararg columns: String) {
            keys += Key(name, columns.toList(), unique = true)
        }

        fun alter(statement: String) {
            alters += statement
        }

        /**
         * Columns and keys declared inside [block] were added to an existing table by a later scheme version: they
         * are appended after the old columns (so a migrated table and a fresh one have the same column order) and
         * their idempotent `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` / `CREATE INDEX IF NOT EXISTS` statements are
         * derived from the same declaration, one statement each (01 section 14.1 rule 2).
         */
        fun added(block: TableBuilder.() -> Unit) {
            val firstColumn = columns.size
            val firstKey = keys.size
            block()
            for (column in columns.drop(firstColumn)) alters += "ALTER TABLE {t} ADD COLUMN IF NOT EXISTS ${column.ddl()}"
            for (key in keys.drop(firstKey)) {
                val cols = key.columns.joinToString(", ") { "`$it`" }
                alters += "CREATE ${if (key.unique) "UNIQUE " else ""}INDEX IF NOT EXISTS `${key.name}` ON {t} ($cols)"
            }
        }

        fun build() = Table(name, comment, columns.toList(), keys.toList(), alters.toList())
    }

    private fun table(name: String, comment: String, block: TableBuilder.() -> Unit): Table =
        TableBuilder(name, comment).apply(block).build()

    // --- the ten tables of scheme version 2 (category and product also carry the version 3 additions) ----------
    // The version 2 part is frozen against src/test/resources/fixtures/schema-v2.sql by MigrationChainIT.

    val CATEGORY = table("market_category", "Market category table.") {
        id()
        str("name", 255)
        text("description")
        str("icon", 64, "fa-folder")
        str("color", 16, "#0d6efd")
        str("status", 16, "ACTIVE")
        bigint("parentId", nullable = true)
        int("position", default = 0)
        str("imageFileName", 255, nullable = true)
        timestamps()
        key("parentId", "parentId", "position")
        // Scheme version 3 (01 section 2.1).
        added {
            flag("tiered", 0)
            str("upgradeMode", 24, "DIFFERENCE")
        }
    }

    val COMPARISON = table("market_comparison", "Market comparison table.") {
        id()
        str("name", 255)
        str("status", 16, "ACTIVE")
        int("priority", default = 0)
        text("productIds")
        text("features")
        text("cellValues")
        timestamps()
    }

    val COUPON = table("market_coupon", "Market coupons table.") {
        id()
        str("name", 255, "")
        str("code", 64)
        str("scope", 16, "ALL")
        text("productIds")
        bigint("discount")
        str("unit", 8, "PERCENT")
        bigint("minPaymentAmount", nullable = true)
        bigint("startDate", nullable = true)
        bigint("expiryDate", nullable = true)
        int("redeemLimit", nullable = true)
        int("customerRedeemLimit", nullable = true)
        int("usedCount", default = 0)
        str("status", 16, "ACTIVE")
        timestamps()
        unique("unique_code", "code")
        // Scheme version 4 (01 section 3.2).
        added {
            text("categoryIds")
            bigint("deletedAt", nullable = true)
            int("legacyUsedCount", default = 0)
        }
    }

    val CREATOR_CODE = table("market_creator_code", "Market creator codes table.") {
        id()
        str("creator", 64)
        str("code", 64)
        bigint("discount")
        str("unit", 8, "PERCENT")
        bigint("commissionPercent", default = 0)
        bigint("startDate", nullable = true)
        bigint("expiryDate", nullable = true)
        int("redeemLimit", nullable = true)
        int("usedCount", default = 0)
        bigint("earnings", default = 0)
        str("status", 16, "ACTIVE")
        timestamps()
        unique("unique_code", "code")
        // Scheme version 4 (01 section 3.3).
        added {
            bigint("creatorUserId", nullable = true)
            bigint("paidOut", default = 0)
            bigint("deletedAt", nullable = true)
            int("legacyUsedCount", default = 0)
            key("idx_creatorUser", "creatorUserId")
        }
    }

    val DISCOUNT = table("market_discount", "Market automatic discounts table.") {
        id()
        str("name", 255)
        bigint("value")
        str("unit", 8, "PERCENT")
        bigint("minPaymentAmount", nullable = true)
        str("scope", 16, "ALL")
        text("productIds")
        text("categoryIds")
        bigint("startDate", nullable = true)
        bigint("expiryDate", nullable = true)
        int("usageLimit", nullable = true)
        int("usedCount", default = 0)
        str("status", 16, "ACTIVE")
        timestamps()
        // Scheme version 4 (01 section 3.1).
        added {
            flag("showBadge", 1)
            bigint("deletedAt", nullable = true)
            int("legacyUsedCount", default = 0)
        }
    }

    val GIFT = table("market_gift", "Market gift codes table.") {
        id()
        str("code", 64)
        str("type", 16)
        bigint("productId", nullable = true)
        bigint("creditAmount", nullable = true)
        text("productIds")
        str("status", 16, "ACTIVE")
        bigint("startDate", nullable = true)
        bigint("expiryDate", nullable = true)
        timestamps()
        unique("unique_code", "code")
        // Scheme version 4 (01 section 3.4). redeemLimit / customerRedeemLimit default to 1 in the column itself: an
        // existing row gets 1 from the default, there is no backfill statement (NULL is a legitimate value).
        added {
            str("name", 255, "")
            int("redeemLimit", nullable = true, default = 1)
            int("customerRedeemLimit", nullable = true, default = 1)
            int("usedCount", default = 0)
            bigint("deletedAt", nullable = true)
        }
    }

    val ORDER = table("market_order", "Market order table.") {
        id()
        bigint("userId", nullable = true)
        str("playerUsername", 64)
        bigint("totalPrice")
        str("currency", 8, "TRY")
        str("paymentMethodId", 64, "")
        str("paymentLabel", 255, "")
        str("status", 16, "PENDING")
        timestamps()
        double("exchangeRate")
        key("userId", "userId")
        key("status", "status", "createdAt")
        // Version 1 -> 2: tables created by plugin versions before the exchange rate column existed.
        alter("ALTER TABLE {t} ADD COLUMN IF NOT EXISTS `exchangeRate` DOUBLE")
    }

    val ORDER_ITEM = table("market_order_item", "Market order item table.") {
        id()
        bigint("orderId")
        bigint("productId", nullable = true)
        str("productName", 255)
        int("quantity", default = 1)
        bigint("unitPrice")
        timestamps()
        key("orderId", "orderId")
        key("productId", "productId")
    }

    val PAYMENT_METHOD = table("market_payment_method", "Market payment methods table.") {
        id()
        str("methodId", 64)
        flag("enabled", 0)
        text("settings")
        timestamps()
        unique("unique_method_id", "methodId")
    }

    val PRODUCT = table("market_product", "Market product table.") {
        id()
        str("slug", 255)
        str("name", 255)
        text("description")
        bigint("categoryId", nullable = true)
        bigint("price", default = 0)
        bigint("creditPrice", default = 0)
        int("stock", nullable = true)
        text("requiredProducts")
        flag("requireOnlyOne", 0)
        str("requiredPermission", 255, nullable = true)
        str("status", 16, "ACTIVE")
        flag("featured", 0)
        str("durationType", 16, "LIFETIME")
        bigint("durationStart", nullable = true)
        bigint("durationExpiry", nullable = true)
        int("priority", default = 0)
        str("icon", 64, "fa-box")
        str("imageFileName", 255, nullable = true)
        text("actions")
        timestamps()
        unique("unique_slug", "slug")
        key("categoryId", "categoryId")
        key("status", "status")
        // Scheme version 3: the 29 columns of 01 section 2.2 and its three new indexes.
        added {
            str("kind", 24, "STANDARD")
            str("shortDescription", 512, nullable = true)
            bigint("compareAtPrice", nullable = true)
            bigint("vatPercent", nullable = true)
            flag("physical", 0)
            str("sku", 64, nullable = true)
            int("weightGrams", nullable = true)
            int("lengthMm", nullable = true)
            int("widthMm", nullable = true)
            int("heightMm", nullable = true)
            str("hsCode", 16, nullable = true)
            str("originCountry", 2, nullable = true)
            str("billingMode", 24, "ONE_TIME")
            str("periodUnit", 24, nullable = true)
            int("periodCount", nullable = true)
            int("subscriptionMaxCycles", nullable = true)
            int("limitPerPlayer", nullable = true)
            int("maxQuantityPerOrder", nullable = true)
            bigint("cooldownSeconds", nullable = true)
            int("tierRank", nullable = true)
            bigint("creditAmount", nullable = true)
            flag("allowGift", 1)
            text("serverChoices")
            flag("hasVariants", 0)
            text("variantOptions")
            str("metaTitle", 255, nullable = true)
            str("metaDescription", 512, nullable = true)
            int("soldCount", default = 0)
            bigint("deletedAt", nullable = true)
            key("idx_imageFileName", "imageFileName")
            key("idx_kind", "kind")
            key("idx_category_tier", "categoryId", "tierRank")
        }
    }

    // --- scheme version 3: catalogue tables (01 sections 2.3 - 2.7, 2.9) ----------------------------------------

    val PRODUCT_VARIANT = table("market_product_variant", "Market product variant table.") {
        id()
        bigint("productId")
        str("name", 255)
        str("sku", 64, nullable = true)
        text("optionValues")
        text("attributes")
        bigint("price", nullable = true)
        bigint("creditPrice", nullable = true)
        bigint("compareAtPrice", nullable = true)
        int("stock", nullable = true)
        int("weightGrams", nullable = true)
        int("periodCount", nullable = true)
        str("imageFileName", 255, nullable = true)
        int("position", default = 0)
        str("status", 16, "ACTIVE")
        bigint("deletedAt", nullable = true)
        timestamps()
        key("idx_product", "productId", "position")
    }

    val PRODUCT_PRICE = table("market_product_price", "Market per-currency product price table.") {
        id()
        bigint("productId")
        bigint("variantId", default = 0)
        str("currency", 8)
        bigint("price")
        bigint("compareAtPrice", nullable = true)
        timestamps()
        unique("uq_product_variant_currency", "productId", "variantId", "currency")
    }

    val PRODUCT_FIELD = table("market_product_field", "Market product custom field table.") {
        id()
        bigint("productId")
        str("fieldKey", 32)
        str("label", 255)
        str("helpText", 512, nullable = true)
        str("type", 24, "TEXT")
        flag("required", 0)
        text("options")
        str("pattern", 255, nullable = true)
        int("minLength", nullable = true)
        int("maxLength", nullable = true)
        bigint("minValue", nullable = true)
        bigint("maxValue", nullable = true)
        str("placeholder", 255, nullable = true)
        str("defaultValue", 255, nullable = true)
        flag("usableInCommands", 1)
        int("position", default = 0)
        timestamps()
        unique("uq_product_key", "productId", "fieldKey")
    }

    val BUNDLE_ITEM = table("market_bundle_item", "Market bundle item table.") {
        id()
        bigint("bundleProductId")
        bigint("productId")
        bigint("variantId", default = 0)
        int("quantity", default = 1)
        int("position", default = 0)
        timestamps()
        unique("uq_bundle_child", "bundleProductId", "productId", "variantId")
        key("idx_product", "productId")
    }

    val PRODUCT_PROVIDER_META = table("market_product_provider_meta", "Market product data owned by a payment provider.") {
        id()
        bigint("productId")
        bigint("variantId", default = 0)
        str("providerId", 64)
        text("meta", nullable = false)
        timestamps()
        unique("uq_product_variant_provider", "productId", "variantId", "providerId")
        key("idx_provider", "providerId")
    }

    val CURRENCY_RATE = table("market_currency_rate", "Market additional currency rate table.") {
        id()
        str("currency", 8)
        decimal("rate", 20, 10)
        str("mode", 24, "AUTO")
        bigint("fetchedAt", nullable = true)
        timestamps()
        unique("uq_currency", "currency")
    }

    // --- scheme version 4: promotions (01 sections 3.5 and 8) ---------------------------------------------------

    val REDEMPTION = table("market_redemption", "Market code and discount redemption table.") {
        id()
        str("kind", 24)
        bigint("refId")
        str("code", 64, nullable = true)
        bigint("orderId")
        bigint("userId", nullable = true)
        str("buyerKey", 80)
        str("email", 255, nullable = true)
        str("recipientKey", 80, "")
        bigint("amount", default = 0)
        str("currency", 8)
        str("state", 16, "HELD")
        timestamps()
        unique("uq_kind_ref_order", "kind", "refId", "orderId")
        key("idx_limit", "kind", "refId", "buyerKey", "state")
        key("idx_limit_recipient", "kind", "refId", "recipientKey", "state")
        key("idx_order", "orderId")
    }

    val CREATOR_EARNING = table("market_creator_earning", "Market creator commission earning table.") {
        id()
        bigint("creatorCodeId")
        bigint("creatorUserId", nullable = true)
        bigint("orderId")
        bigint("baseAmount")
        bigint("commissionPercent")
        bigint("amount")
        str("currency", 8)
        str("state", 16, "PENDING")
        bigint("availableAt", nullable = true)
        bigint("reversedAmount", default = 0)
        bigint("payoutId", nullable = true)
        timestamps()
        unique("uq_order_code", "orderId", "creatorCodeId")
        key("idx_code_state", "creatorCodeId", "state")
        key("idx_available", "state", "availableAt")
    }

    val CREATOR_PAYOUT = table("market_creator_payout", "Market creator payout table.") {
        id()
        bigint("creatorCodeId")
        bigint("creatorUserId", nullable = true)
        bigint("amount")
        str("currency", 8)
        str("method", 16)
        str("state", 16, "PENDING")
        bigint("creditTxId", nullable = true)
        text("actions")
        str("note", 255, nullable = true)
        bigint("paidBy", nullable = true)
        bigint("paidAt", nullable = true)
        str("idempotencyKey", 64)
        char("idempotencyHash", 64)
        timestamps()
        key("idx_code", "creatorCodeId")
        unique("uq_idem", "idempotencyKey")
    }

    /** Every table the plugin owns, in creation order. Later migration slices append their tables here. */
    val tables: List<Table> = listOf(
        CATEGORY, COMPARISON, COUPON, CREATOR_CODE, DISCOUNT, GIFT, ORDER, ORDER_ITEM, PAYMENT_METHOD, PRODUCT,
        PRODUCT_VARIANT, PRODUCT_PRICE, PRODUCT_FIELD, BUNDLE_ITEM, PRODUCT_PROVIDER_META, CURRENCY_RATE,
        REDEMPTION, CREATOR_EARNING, CREATOR_PAYOUT
    )

    /** The table declared under [name] (without prefix), or an error naming it. */
    fun table(name: String): Table = tables.firstOrNull { it.name == name } ?: error("no market table '$name'")

    // --- fixups -------------------------------------------------------------------------------------------------

    /**
     * A data fixup (01 section 14.1 rule 7). Every fixup is re-runnable: [pendingSql] is a `SELECT COUNT(*)` of the
     * rows still unfixed (zero = done; [SchemaVerifier] reports a non-zero count) and [apply] touches only those
     * rows. A fixup without such a predicate is [oneShot]: it runs in one transaction together with a marker row
     * `fixup:<id>` and is skipped once the marker exists; [pendingSql] is then `null`.
     *
     * [requires] lists the tables (without prefix) the fixup reads or writes; while one is missing the fixup is
     * skipped and the verifier already reports the missing table.
     */
    class Fixup(
        val id: String,
        val requires: List<String> = emptyList(),
        val pendingSql: ((prefix: String) -> String)? = null,
        val oneShot: Boolean = false,
        val apply: suspend (client: SqlClient, prefix: String) -> Unit
    ) {
        init {
            require(oneShot == (pendingSql == null)) { "fixup '$id': a one-shot fixup has no predicate and every other one has" }
        }
    }

    /**
     * The fixups of the schema, in order. None exist for the ten tables of scheme version 2 (the legacy order
     * conversion arrives with the order migration slice, which appends here).
     */
    fun fixups(): List<Fixup> = emptyList()

    /** Marker table of the one-shot fixups (`market_sequence`, 01 section 5.6, arrives with the sequence slice). */
    const val ONE_SHOT_MARKER_TABLE = "market_sequence"

    // --- statements ---------------------------------------------------------------------------------------------

    /** Idempotent DDL of one table: the `CREATE` followed by its idempotent `ALTER`s. */
    fun ddl(table: Table, prefix: String): List<String> = listOf(table.createSql(prefix)) + table.alterSql(prefix)

    /**
     * Runs the DDL of one table through [client], swallowing and logging an error (the `Dao.init` contract: it never
     * throws, 01 section 14.1 rule 3). Returns the error messages, empty when everything succeeded.
     */
    suspend fun installTable(client: SqlClient, table: Table, prefix: String): List<String> {
        val errors = ArrayList<String>()
        for (statement in ddl(table, prefix)) {
            try {
                client.query(statement).execute().coAwait()
            } catch (e: Exception) {
                val message = "${table.physicalName(prefix)}: ${e.message}"
                errors += message
                logger.error("Market schema statement failed for {}: {}", table.physicalName(prefix), e.message)
            }
        }
        return errors
    }

    // --- ensure -------------------------------------------------------------------------------------------------

    /** What [ensure] did: the DDL and fixup errors it swallowed, the fixups it skipped and the ones that ran. */
    class EnsureReport(
        val ddlErrors: List<String>,
        val fixupErrors: List<String>,
        val fixupsRun: List<String>,
        val fixupsSkipped: List<String>
    ) {
        val clean: Boolean get() = ddlErrors.isEmpty() && fixupErrors.isEmpty()
    }

    /** `ensure(pool, prefix)` with the declared tables and fixups: the call every plugin start and every test makes. */
    suspend fun ensure(pool: Pool, prefix: String): EnsureReport = ensure(pool, prefix, tables, fixups())

    /**
     * Re-runs all idempotent DDL ([tables], in order) and then every fixup, at every plugin start (01 section 14.1
     * rule 3 and 7, section 14.4 steps 3 and 4). Never throws for a database error; the verdict is the job of
     * [SchemaVerifier.verify]. Running it twice changes nothing. [markerTable] holds the `fixup:<id>` rows of the
     * one-shot fixups (a seam for tests; production uses [ONE_SHOT_MARKER_TABLE]).
     */
    suspend fun ensure(
        pool: Pool,
        prefix: String,
        tables: List<Table>,
        fixups: List<Fixup>,
        markerTable: String = ONE_SHOT_MARKER_TABLE
    ): EnsureReport {
        val ddlErrors = ArrayList<String>()
        for (table in tables) ddlErrors += installTable(pool, table, prefix)

        val errors = ArrayList<String>()
        val run = ArrayList<String>()
        val skipped = ArrayList<String>()
        val db by lazy { MarketDb({ pool }, SystemClock) }

        for (fixup in fixups) {
            try {
                if (!tablesExist(pool, prefix, fixup.requires + listOfNotNull(markerTable.takeIf { fixup.oneShot }))) {
                    skipped += fixup.id
                    logger.warn("Market fixup {} skipped: a required table is missing", fixup.id)
                    continue
                }
                if (fixup.oneShot) {
                    val ran = db.tx { conn ->
                        val marker = "fixup:${fixup.id}"
                        val seen = conn.preparedQuery(
                            "SELECT 1 FROM `${prefix}$markerTable` WHERE `name` = ? FOR UPDATE"
                        ).execute(Tuple.of(marker)).coAwait().size() > 0
                        if (seen) {
                            false
                        } else {
                            fixup.apply(conn, prefix)
                            conn.preparedQuery("INSERT INTO `${prefix}$markerTable` (`name`, `value`) VALUES (?, 1)")
                                .execute(Tuple.of(marker)).coAwait()
                            true
                        }
                    }
                    if (ran) run += fixup.id
                } else {
                    val pending = pendingCount(pool, fixup, prefix)
                    if (pending > 0) {
                        fixup.apply(pool, prefix)
                        run += fixup.id
                    }
                }
            } catch (e: Exception) {
                errors += "fixup ${fixup.id}: ${e.message}"
                logger.error("Market fixup {} failed: {}", fixup.id, e.message)
            }
        }

        return EnsureReport(ddlErrors, errors, run, skipped)
    }

    /** Rows a fixup still has to fix (`0` for a one-shot fixup, which has no predicate). */
    suspend fun pendingCount(client: SqlClient, fixup: Fixup, prefix: String): Long {
        val sql = fixup.pendingSql?.invoke(prefix) ?: return 0L
        return client.query(sql).execute().coAwait().first().getLong(0)
    }

    /** `true` when every table of [names] (without prefix) exists as a base table of the current database. */
    suspend fun tablesExist(client: SqlClient, prefix: String, names: List<String>): Boolean {
        if (names.isEmpty()) return true
        val rows = client.preparedQuery(
            "SELECT TABLE_NAME AS name FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_TYPE = 'BASE TABLE'"
        ).execute().coAwait()
        val present = rows.map { it.getString("name") }.toSet()
        return names.all { (prefix + it) in present }
    }
}
