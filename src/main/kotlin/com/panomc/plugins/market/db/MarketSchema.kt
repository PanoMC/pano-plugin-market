package com.panomc.plugins.market.db

import com.panomc.plugins.market.core.time.Ids
import com.panomc.plugins.market.core.time.SecureIds
import com.panomc.plugins.market.core.time.SystemClock
import com.panomc.plugins.market.db.dao.isDuplicateKey
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

        /**
         * One idempotent `CREATE [UNIQUE] INDEX IF NOT EXISTS` per declared non-primary key: an index dropped by hand (or lost) comes back at the next
         * start, also when it belongs to the table's first `CREATE` and not to an `added { }` block (CP-2, E2E L-04b). A unique index that cannot be
         * built (duplicate rows) fails here, is reported by [ensure] and leaves the store degraded through the verifier.
         */
        fun indexSql(prefix: String): List<String> = keys.filterNot { it.primary }.map {
            val cols = it.columns.joinToString(", ") { c -> "`$c`" }

            "CREATE ${if (it.unique) "UNIQUE " else ""}INDEX IF NOT EXISTS `${it.name}` ON `${physicalName(prefix)}` ($cols)"
        }
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

        fun char(name: String, length: Int, nullable: Boolean = false) {
            columns += Column(name, "CHAR($length)", nullable)
        }

        fun text(name: String, nullable: Boolean = true) {
            columns += Column(name, "MEDIUMTEXT", nullable)
        }

        fun decimal(name: String, precision: Int, scale: Int, nullable: Boolean = false, default: Int? = null) {
            columns += Column(name, "DECIMAL($precision,$scale)", nullable, default?.toString())
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

        /** A nullable `BOOL` without a default: `NULL` = "not stated". */
        fun flagOrNull(name: String) {
            columns += Column(name, "TINYINT(1)", nullable = true)
        }

        fun double(name: String, nullable: Boolean = true) {
            columns += Column(name, "DOUBLE", nullable)
        }

        /** [defaults] gives both columns `DEFAULT 0`, for a table raw SQL inserts into (the one-shot fixup markers). */
        fun timestamps(defaults: Boolean = false) {
            columns += Column("createdAt", "BIGINT(20)", default = if (defaults) "0" else null)
            columns += Column("updatedAt", "BIGINT(20)", default = if (defaults) "0" else null)
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
        str("status", 24, "PENDING") // VARCHAR(16) until scheme version 5 (01 section 5.1), widened by the MODIFY below
        timestamps()
        double("exchangeRate")
        key("userId", "userId")
        key("status", "status", "createdAt")
        // Version 1 -> 2: tables created by plugin versions before the exchange rate column existed.
        alter("ALTER TABLE {t} ADD COLUMN IF NOT EXISTS `exchangeRate` DOUBLE")
        // Scheme version 5 (01 section 5.1): the status column is widened (widening only, idempotent) and 70 columns
        // and 10 indexes are added. Enum columns are VARCHAR(24), JSON columns MEDIUMTEXT, money BIGINT (x100).
        added {
            alter("ALTER TABLE {t} MODIFY COLUMN `status` VARCHAR(24) NOT NULL DEFAULT 'PENDING'")
            char("publicId", 20, nullable = true)
            char("accessToken", 40, nullable = true)
            str("source", 24, "STOREFRONT")
            str("buyerKey", 80, "")
            str("idempotencyKey", 64, nullable = true)
            char("idempotencyHash", 64, nullable = true)
            str("email", 255, nullable = true)
            str("locale", 16, nullable = true)
            str("clientIp", 45, nullable = true)
            str("userAgent", 255, nullable = true)
            str("recipientUsername", 64, "")
            bigint("recipientUserId", nullable = true)
            str("recipientKey", 80, "")
            flag("isGift", 0)
            str("giftMessage", 255, nullable = true)
            flag("hideFromBroadcast", 0)
            str("reservationState", 24, "NONE")
            bigint("expiresAt", nullable = true)
            str("baseCurrency", 8, "")
            decimal("fxRate", 20, 10, default = 1)
            str("displayCurrency", 8, nullable = true)
            decimal("displayRate", 20, 10, nullable = true)
            str("pricingMode", 24, "MARKET")
            flag("pricesIncludeVat", 1)
            bigint("subtotal", default = 0)
            bigint("discountTotal", default = 0)
            bigint("couponDiscount", default = 0)
            bigint("creatorDiscount", default = 0)
            bigint("upgradeDiscount", default = 0)
            bigint("shippingTotal", default = 0)
            bigint("shippingVatPercent", default = 0)
            bigint("shippingVatAmount", default = 0)
            bigint("paymentFee", default = 0)
            bigint("paymentFeeVatPercent", default = 0)
            bigint("paymentFeeVatAmount", default = 0)
            bigint("vatTotal", default = 0)
            bigint("creditAmount", default = 0)
            bigint("creditValue", default = 0)
            bigint("gatewayAmount", default = 0)
            bigint("paidAmount", default = 0)
            bigint("refundedTotal", default = 0)
            bigint("refundedGatewayAmount", default = 0)
            bigint("refundedCreditAmount", default = 0)
            bigint("couponId", nullable = true)
            bigint("creatorCodeId", nullable = true)
            bigint("giftId", nullable = true)
            str("couponCode", 64, nullable = true)
            str("creatorCode", 64, nullable = true)
            bigint("paymentId", nullable = true)
            bigint("paidAt", nullable = true)
            flag("testMode", 0)
            str("statusBeforeDispute", 24, nullable = true)
            str("disputeStatus", 24, "NONE")
            str("reviewReason", 32, nullable = true)
            str("fulfillmentStatus", 24, "NONE")
            str("fulfillmentBy", 24, "MARKET")
            flag("requiresShipping", 0)
            str("shippingStatus", 24, "NOT_REQUIRED")
            text("shippingAddress")
            bigint("shippingMethodId", nullable = true)
            str("shippingMethodName", 255, nullable = true)
            text("shippingQuote")
            int("shippingWeightGrams", nullable = true)
            text("billingInfo")
            bigint("legalTextId", nullable = true)
            bigint("legalAcceptedAt", nullable = true)
            bigint("subscriptionId", nullable = true)
            bigint("invoiceId", nullable = true)
            text("note")
            bigint("createdBy", nullable = true)
            unique("uq_publicId", "publicId")
            unique("uq_buyer_idem", "buyerKey", "idempotencyKey")
            key("idx_recipient", "recipientKey")
            key("idx_expiry", "status", "expiresAt")
            key("idx_paidAt", "paidAt")
            key("idx_email", "email")
            key("idx_player", "playerUsername")
            key("idx_subscription", "subscriptionId")
            key("idx_coupon", "couponId")
            key("idx_creator", "creatorCodeId")
        }
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
        // Scheme version 5 (01 section 5.2).
        added {
            str("kind", 24, "PRODUCT")
            bigint("parentItemId", nullable = true)
            bigint("variantId", nullable = true)
            str("variantName", 255, nullable = true)
            str("sku", 64, nullable = true)
            bigint("listUnitPrice", default = 0)
            bigint("discountAmount", default = 0)
            bigint("upgradeAmount", default = 0)
            bigint("couponAmount", default = 0)
            bigint("vatPercent", default = 0)
            bigint("vatAmount", default = 0)
            bigint("lineTotal", default = 0)
            bigint("creditUnitPrice", nullable = true)
            bigint("creditAmount", nullable = true)
            text("fieldValues")
            bigint("targetServerId", nullable = true)
            text("snapshot")
            flag("physical", 0)
            int("stockReserved", default = 0)
            int("refundedQuantity", default = 0)
            bigint("refundedAmount", default = 0)
            int("shippedQuantity", default = 0)
            str("gatewayItemRef", 128, nullable = true)
            bigint("gatewayLineAmount", nullable = true)
            bigint("upgradeFromEntitlementId", nullable = true)
        }
    }

    val PAYMENT_METHOD = table("market_payment_method", "Market payment methods table.") {
        id()
        str("methodId", 64)
        flag("enabled", 0)
        text("settings")
        timestamps()
        unique("unique_method_id", "methodId")
        // Scheme version 6 (01 section 6.1): provider configuration beyond the settings JSON.
        added {
            int("position", default = 0)
            str("customLabel", 255, nullable = true)
            str("customDescription", 512, nullable = true)
            str("feeMode", 24, "NONE")
            bigint("feePercent", default = 0)
            bigint("feeFixed", default = 0)
            bigint("minAmount", nullable = true)
            bigint("maxAmount", nullable = true)
            text("currencies")
            flag("testMode", 0)
            bigint("lastInboundAt", nullable = true)
            str("lastError", 512, nullable = true)
            bigint("lastErrorAt", nullable = true)
            bigint("settingsUpdatedAt", nullable = true)
        }
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

    // --- scheme version 5: orders (01 sections 5.3, 5.4 and 6.7) -------------------------------------------------

    val ORDER_EVENT = table("market_order_event", "Market order timeline table.") {
        id()
        bigint("orderId")
        str("type", 48)
        str("fromStatus", 24, nullable = true)
        str("toStatus", 24, nullable = true)
        str("actorType", 24)
        bigint("actorUserId", nullable = true)
        str("message", 512, nullable = true)
        text("data")
        timestamps()
        key("idx_order", "orderId", "id")
    }

    val LEGAL_TEXT = table("market_legal_text", "Market legal text version table.") {
        id()
        int("version")
        str("locale", 16)
        str("title", 255)
        text("content", nullable = false)
        char("contentHash", 64)
        flag("active", 0)
        bigint("createdBy", nullable = true)
        timestamps()
        unique("uq_version_locale", "version", "locale")
        key("idx_active", "active", "locale")
    }

    /**
     * Named counters (01 section 6.7). The marker rows `fixup:<id>` of the one-shot data fixups live here, so the table
     * arrives with the first one-shot fixup; the invoice slice adds its DAO methods. Raw marker inserts name only
     * `name` and `value`, hence the timestamp defaults.
     */
    val SEQUENCE = table("market_sequence", "Market named counters and fixup markers.") {
        id()
        str("name", 64)
        bigint("value", default = 0)
        timestamps(defaults = true)
        unique("uq_name", "name")
    }

    // --- scheme version 5, second half: entitlement, address, cart, invoice (01 sections 4, 5.5, 5.6, 6.7) --------

    val ENTITLEMENT = table("market_entitlement", "Market entitlement table.") {
        id()
        bigint("userId", nullable = true)
        str("playerUsername", 64)
        str("ownerKey", 80)
        bigint("productId")
        bigint("variantId", default = 0)
        bigint("orderId")
        bigint("orderItemId")
        bigint("subscriptionId", nullable = true)
        int("quantity", default = 1)
        str("status", 24, "ACTIVE")
        bigint("startsAt")
        bigint("expiresAt", nullable = true)
        bigint("tierCategoryId", nullable = true)
        int("tierRank", nullable = true)
        bigint("pricePaid", default = 0)
        bigint("replacedById", nullable = true)
        str("endReason", 32, nullable = true)
        bigint("reminderSentAt", nullable = true)
        bigint("endedAt", nullable = true)
        timestamps()
        key("idx_owner_product", "ownerKey", "productId", "status")
        key("idx_expiry", "status", "expiresAt")
        key("idx_orderItem", "orderItemId")
        key("idx_subscription", "subscriptionId")
        key("idx_tier", "tierCategoryId", "ownerKey", "status")
    }

    val ADDRESS = table("market_address", "Market address book table.") {
        id()
        bigint("userId")
        str("label", 64, nullable = true)
        flag("isDefault", 0)
        str("firstName", 255, nullable = true)
        str("lastName", 255, nullable = true)
        str("company", 255, nullable = true)
        str("phone", 255, nullable = true)
        str("email", 255, nullable = true)
        str("country", 255, nullable = true)
        str("state", 255, nullable = true)
        str("city", 255, nullable = true)
        str("district", 255, nullable = true)
        str("neighborhood", 255, nullable = true)
        str("line1", 255, nullable = true)
        str("line2", 255, nullable = true)
        str("postalCode", 16, nullable = true)
        str("identityNumber", 255, nullable = true)
        str("type", 255, nullable = true)
        str("taxOffice", 255, nullable = true)
        str("taxNumber", 255, nullable = true)
        timestamps()
        key("idx_user", "userId")
    }

    val CART = table("market_cart", "Market cart table.") {
        id()
        bigint("userId")
        str("currency", 8, nullable = true)
        str("couponCode", 64, nullable = true)
        str("creatorCode", 64, nullable = true)
        str("recipientUsername", 64, nullable = true)
        str("giftMessage", 255, nullable = true)
        bigint("shippingAddressId", nullable = true)
        bigint("shippingMethodId", nullable = true)
        timestamps()
        unique("uq_user", "userId")
    }

    val CART_ITEM = table("market_cart_item", "Market cart line table.") {
        id()
        bigint("cartId")
        bigint("productId")
        bigint("variantId", default = 0)
        int("quantity", default = 1)
        text("fieldValues")
        bigint("targetServerId", nullable = true)
        char("lineKey", 40)
        timestamps()
        unique("uq_cart_line", "cartId", "lineKey")
        key("idx_product", "productId")
    }

    val INVOICE = table("market_invoice", "Market invoice and credit note table.") {
        id()
        bigint("orderId")
        str("type", 24)
        bigint("refundId", default = 0)
        str("series", 16)
        bigint("sequence")
        str("number", 32)
        str("locale", 16)
        str("currency", 8)
        bigint("total")
        bigint("vatTotal")
        text("snapshot", nullable = false)
        str("fileName", 255, nullable = true)
        bigint("issuedAt")
        timestamps()
        unique("uq_series_seq", "series", "sequence")
        unique("uq_order_type_refund", "orderId", "type", "refundId")
    }

    // --- scheme version 6: payments (01 sections 6.2 to 6.6) ----------------------------------------------------

    val PAYMENT = table("market_payment", "Market payment attempt table.") {
        id()
        bigint("orderId")
        bigint("subscriptionId", nullable = true)
        str("providerId", 64)
        str("methodLabel", 255, "")
        str("status", 24, "CREATED")
        str("reference", 24)
        char("token", 40)
        bigint("amount")
        str("currency", 8)
        bigint("feeAmount", default = 0)
        bigint("creditAmount", default = 0)
        bigint("creditValue", default = 0)
        bigint("orderTotal", default = 0)
        str("startKind", 16, nullable = true)
        text("startPayload") // ENC
        str("gatewayTransactionId", 191, nullable = true)
        text("gatewayRefs")
        text("providerData") // ENC
        bigint("paidAmount", nullable = true)
        str("paidCurrency", 8, nullable = true)
        bigint("gatewayFee", nullable = true)
        bigint("netAmount", nullable = true)
        str("settlementCurrency", 16, nullable = true)
        str("settlementAmount", 64, nullable = true)
        int("installments", nullable = true)
        str("methodDetail", 128, nullable = true)
        flag("testMode", 0)
        flag("duplicate", 0)
        bigint("refundedAmount", default = 0)
        str("failureCode", 64, nullable = true)
        str("failureMessage", 512, nullable = true)
        str("adminMessage", 512, nullable = true)
        str("clientIp", 45, nullable = true)
        str("userAgent", 255, nullable = true)
        bigint("startedAt", nullable = true)
        bigint("paidAt", nullable = true)
        bigint("expiresAt", nullable = true)
        bigint("closedAt", nullable = true)
        bigint("nextQueryAt", nullable = true)
        int("queryCount", default = 0)
        bigint("lastQueriedAt", nullable = true)
        timestamps()
        unique("uq_reference", "reference")
        unique("uq_token", "token")
        unique("uq_provider_txn", "providerId", "gatewayTransactionId")
        key("idx_order", "orderId")
        key("idx_reconcile", "status", "nextQueryAt")
        key("idx_subscription", "subscriptionId")
    }

    val PAYMENT_EVENT = table("market_payment_event", "Market raw provider traffic table.") {
        id()
        str("providerId", 64)
        str("direction", 24)
        str("channel", 16)
        str("subChannel", 64, nullable = true)
        str("eventKey", 128)
        char("requestHash", 64, nullable = true)
        bigint("paymentId", nullable = true)
        bigint("orderId", nullable = true)
        bigint("refundId", nullable = true)
        bigint("subscriptionId", nullable = true)
        str("method", 8, nullable = true)
        str("url", 1024, nullable = true)
        text("headers")
        text("body")
        str("remoteIp", 45, nullable = true)
        flagOrNull("verified")
        str("eventTypes", 255, nullable = true)
        str("status", 24, "RECEIVED")
        int("attempts", default = 0)
        int("duplicateCount", default = 0)
        bigint("nextAttemptAt", nullable = true)
        int("responseStatus", nullable = true)
        str("error", 512, nullable = true)
        int("durationMs", nullable = true)
        bigint("processedAt", nullable = true)
        timestamps()
        unique("uq_event", "providerId", "direction", "eventKey")
        key("idx_request", "requestHash")
        key("idx_payment", "paymentId")
        key("idx_order", "orderId")
        key("idx_status", "status", "createdAt")
        key("idx_retry", "status", "nextAttemptAt")
    }

    val REFUND = table("market_refund", "Market refund table.") {
        id()
        bigint("orderId")
        bigint("paymentId", nullable = true)
        str("providerId", 64, nullable = true)
        str("status", 24, "REQUESTED")
        str("origin", 24)
        str("idempotencyKey", 64)
        char("idempotencyHash", 64, nullable = true)
        bigint("amount")
        bigint("gatewayAmount", default = 0)
        bigint("gatewayRefundedAmount", nullable = true)
        bigint("creditAmount", default = 0)
        bigint("creditValue", default = 0)
        str("currency", 8)
        str("reason", 255, nullable = true)
        str("gatewayRefundId", 191, nullable = true)
        str("buyerActionUrl", 1024, nullable = true)
        flag("revoke", 1)
        flag("revokeFirst", 0)
        flagOrNull("cascadeUpgrade")
        flag("restock", 0)
        bigint("creditTxId", nullable = true)
        bigint("initiatedBy", nullable = true)
        str("failureCode", 64, nullable = true)
        str("failureMessage", 512, nullable = true)
        bigint("nextQueryAt", nullable = true)
        int("queryCount", default = 0)
        bigint("completedAt", nullable = true)
        timestamps()
        unique("uq_idem", "idempotencyKey")
        unique("uq_provider_refund", "providerId", "gatewayRefundId")
        key("idx_order", "orderId")
        key("idx_reconcile", "status", "nextQueryAt")
    }

    val REFUND_ITEM = table("market_refund_item", "Market refund line table.") {
        id()
        bigint("refundId")
        bigint("orderItemId")
        int("quantity", default = 0)
        bigint("amount")
        timestamps()
        unique("uq_refund_item", "refundId", "orderItemId")
    }

    val DISPUTE = table("market_dispute", "Market dispute table.") {
        id()
        bigint("orderId")
        bigint("paymentId", nullable = true)
        str("providerId", 64, nullable = true)
        str("gatewayDisputeId", 191, nullable = true)
        str("status", 24, "OPEN")
        str("origin", 24)
        bigint("amount")
        str("currency", 8)
        str("reason", 255, nullable = true)
        bigint("openedAt")
        bigint("resolvedAt", nullable = true)
        bigint("createdBy", nullable = true)
        timestamps()
        unique("uq_provider_dispute", "providerId", "gatewayDisputeId")
        key("idx_order", "orderId")
    }

    val PROVIDER_STATE = table("market_provider_state", "Market provider key value state table.") {
        id()
        str("kind", 24)
        str("providerId", 64)
        str("stateKey", 191)
        text("value", nullable = false) // ENC
        bigint("expiresAt", nullable = true)
        timestamps()
        unique("uq_kind_provider_key", "kind", "providerId", "stateKey")
    }

    // --- scheme version 7: the credit ledger (01 section 7) -------------------------------------------------------

    /** `market_credit_account.type`. */
    const val CREDIT_ACCOUNT_USER = "USER"
    const val CREDIT_ACCOUNT_SYSTEM = "SYSTEM"

    /** The five system credit accounts, in the order they are seeded (01 section 7.1). */
    val CREDIT_SYSTEM_KEYS: List<String> = listOf("ISSUANCE", "SPENT", "HOLD", "REVOKED", "EXTERNAL")

    val CREDIT_ACCOUNT = table("market_credit_account", "Market credit account table.") {
        id()
        str("type", 16)
        bigint("userId", nullable = true)
        str("systemKey", 32, nullable = true)
        bigint("balance", default = 0)
        timestamps()
        unique("uq_user", "userId")
        unique("uq_system", "systemKey")
    }

    val CREDIT_TX = table("market_credit_tx", "Market credit transaction table.") {
        id()
        str("type", 24)
        str("idempotencyKey", 128)
        bigint("userId", nullable = true)
        bigint("amount")
        bigint("shortfall", default = 0)
        bigint("orderId", nullable = true)
        bigint("refundId", nullable = true)
        bigint("deliveryId", nullable = true)
        bigint("actorUserId", nullable = true)
        str("note", 255, nullable = true)
        timestamps()
        unique("uq_idem", "idempotencyKey")
        key("idx_user", "userId", "id")
        key("idx_order", "orderId")
        key("idx_type", "type", "id")
    }

    val CREDIT_ENTRY = table("market_credit_entry", "Market credit ledger entry table.") {
        id()
        bigint("txId")
        bigint("accountId")
        bigint("amount")
        bigint("balanceAfter")
        timestamps()
        key("idx_account", "accountId", "id")
        key("idx_tx", "txId")
    }

    /**
     * The statement that seeds the five system credit accounts (01 section 7.1): one `INSERT IGNORE`, so a second run
     * (or a second caller: `Dao.init`, the migration, [ensure]) leaves five rows, and a row an admin deleted by hand is
     * restored. Ids 1 to 5 on a fresh table, in the order of [CREDIT_SYSTEM_KEYS].
     */
    fun seedCreditSystemAccountsSql(prefix: String, now: Long = System.currentTimeMillis()): String {
        val rows = CREDIT_SYSTEM_KEYS.joinToString(", ") { key -> "('$CREDIT_ACCOUNT_SYSTEM', '$key', 0, $now, $now)" }
        return "INSERT IGNORE INTO `${CREDIT_ACCOUNT.physicalName(prefix)}` (`type`, `systemKey`, `balance`, `createdAt`, `updatedAt`) VALUES $rows"
    }

    // --- scheme version 8: delivery, server state, mail outbox (01 section 9; the webhook tables moved to core, see WebhookImport) ---------------------------

    val DELIVERY = table("market_delivery", "Market delivery table.") {
        id()
        str("sourceType", 24, "ORDER_ITEM")
        bigint("orderId", nullable = true)
        bigint("orderItemId", nullable = true)
        bigint("sourceId", nullable = true)
        bigint("entitlementId", nullable = true)
        bigint("subscriptionId", nullable = true)
        str("phase", 16)
        str("actionId", 32)
        str("actionType", 16)
        int("unitIndex", default = 0)
        int("attemptGroup", default = 0)
        bigint("serverId", default = 0)
        str("idempotencyKey", 191)
        str("status", 24, "PENDING")
        flag("requiresOnline", 0)
        str("playerUsername", 64)
        str("playerUuid", 36, nullable = true)
        text("payload", nullable = false) // JSON
        text("result") // JSON
        str("transport", 16, nullable = true)
        flag("guaranteed", 0)
        int("attempts", default = 0)
        bigint("runAfter")
        bigint("nextAttemptAt", nullable = true)
        bigint("cancelRequestedAt", nullable = true)
        bigint("waitUntil", nullable = true)
        str("claimToken", 36, nullable = true)
        bigint("claimedUntil", nullable = true)
        bigint("sentAt", nullable = true)
        bigint("confirmedAt", nullable = true)
        str("lastErrorCode", 48, nullable = true)
        str("lastError", 512, nullable = true)
        timestamps()
        unique("uq_idem", "idempotencyKey")
        key("idx_due", "status", "nextAttemptAt")
        key("idx_order", "orderId")
        key("idx_item", "orderItemId")
        key("idx_server", "serverId", "status")
        key("idx_player", "playerUsername", "status")
        key("idx_entitlement", "entitlementId")
    }

    val SERVER_STATE = table("market_server_state", "Market Minecraft server state table.") {
        id()
        bigint("serverId")
        str("mcComponentVersion", 32, nullable = true)
        str("capabilities", 255, nullable = true)
        str("platform", 16, nullable = true)
        int("protocol", nullable = true)
        int("queuedCount", nullable = true)
        bigint("lastSeenAt", nullable = true)
        text("settings") // JSON
        timestamps()
        unique("uq_server", "serverId")
    }

    val MAIL_OUTBOX = table("market_mail_outbox", "Market mail outbox table.") {
        id()
        str("kind", 48)
        str("refType", 32)
        bigint("refId")
        str("refKey", 64, "")
        bigint("orderId", nullable = true)
        bigint("userId", nullable = true)
        str("recipient", 255)
        str("locale", 16)
        text("params", nullable = false) // JSON
        str("status", 16, "PENDING")
        int("attempts", default = 0)
        bigint("nextAttemptAt", nullable = true)
        bigint("claimedUntil", nullable = true)
        str("lastError", 512, nullable = true)
        bigint("sentAt", nullable = true)
        timestamps()
        unique("uq_mail", "kind", "refType", "refId", "refKey", "recipient")
        key("idx_due", "status", "nextAttemptAt")
        key("idx_order", "orderId")
    }

    // --- scheme version 9: subscriptions (01 section 10); version 10 part a: abuse and store modules (01 section 12) ---

    val SUBSCRIPTION = table("market_subscription", "Market subscription table.") {
        id()
        bigint("userId", nullable = true)
        str("playerUsername", 64)
        str("ownerKey", 80)
        str("email", 255, nullable = true)
        bigint("productId")
        bigint("variantId", default = 0)
        str("productName", 255)
        bigint("initialOrderId")
        bigint("initialOrderItemId")
        bigint("entitlementId", nullable = true)
        str("providerId", 64)
        str("mode", 16)
        str("status", 16, "PENDING")
        str("intervalUnit", 8)
        int("intervalCount")
        bigint("price")
        str("currency", 8)
        int("maxCycles", nullable = true)
        int("cycleCount", default = 0)
        bigint("currentPeriodStart", nullable = true)
        bigint("currentPeriodEnd", nullable = true)
        bigint("nextChargeAt", nullable = true)
        bigint("nextQueryAt", nullable = true)
        bigint("lastQueriedAt", nullable = true)
        str("remoteCancelState", 16, "NONE")
        int("remoteCancelAttempts", default = 0)
        bigint("graceEndsAt", nullable = true)
        flag("cancelAtPeriodEnd", 0)
        bigint("cancelRequestedAt", nullable = true)
        bigint("cancelledAt", nullable = true)
        bigint("endedAt", nullable = true)
        str("endReason", 32, nullable = true)
        str("gatewaySubscriptionId", 191, nullable = true)
        str("gatewayCustomerId", 191, nullable = true)
        text("storedMethod") // ENC
        str("storedMethodLabel", 64, nullable = true)
        int("failCount", default = 0)
        bigint("lastFailureAt", nullable = true)
        bigint("reminderSentAt", nullable = true)
        bigint("targetServerId", nullable = true)
        text("fieldValues") // JSON
        text("providerData") // ENC
        flag("testMode", 0)
        timestamps()
        unique("uq_provider_sub", "providerId", "gatewaySubscriptionId")
        key("idx_charge", "status", "nextChargeAt")
        key("idx_period", "status", "currentPeriodEnd")
        key("idx_query", "nextQueryAt")
        key("idx_owner", "ownerKey")
        key("idx_user", "userId")
    }

    val SUBSCRIPTION_RENEWAL = table("market_subscription_renewal", "Market subscription renewal table.") {
        id()
        bigint("subscriptionId")
        int("periodIndex")
        bigint("periodStart")
        bigint("periodEnd")
        bigint("orderId", nullable = true)
        bigint("paymentId", nullable = true)
        str("status", 16, "PENDING")
        bigint("amount")
        str("currency", 8)
        int("attempts", default = 0)
        bigint("nextAttemptAt", nullable = true)
        str("lastError", 512, nullable = true)
        timestamps()
        unique("uq_sub_period", "subscriptionId", "periodIndex")
        key("idx_due", "status", "nextAttemptAt")
    }

    val BLOCK = table("market_block", "Market block list table.") {
        id()
        str("type", 16)
        str("value", 255)
        str("reason", 255, nullable = true)
        str("source", 16)
        bigint("orderId", nullable = true)
        bigint("createdBy", nullable = true)
        bigint("expiresAt", nullable = true)
        int("hitCount", default = 0)
        bigint("lastHitAt", nullable = true)
        timestamps()
        unique("uq_type_value", "type", "value")
    }

    val THROTTLE = table("market_throttle", "Market throttle table.") {
        id()
        str("scope", 32)
        str("subject", 191)
        int("count", default = 0)
        bigint("windowStart")
        bigint("lockedUntil", nullable = true)
        timestamps()
        unique("uq_scope_subject", "scope", "subject")
    }

    val GOAL = table("market_goal", "Market goal table.") {
        id()
        str("name", 255)
        str("description", 512, nullable = true)
        str("metric", 16)
        text("productIds") // JSON
        bigint("target")
        bigint("progress", default = 0)
        str("currency", 8, nullable = true)
        str("period", 16, "ONE_TIME")
        bigint("periodStart", nullable = true)
        bigint("startsAt", nullable = true)
        bigint("endsAt", nullable = true)
        str("status", 16, "ACTIVE")
        flag("showOnStore", 1)
        bigint("completedAt", nullable = true)
        int("position", default = 0)
        timestamps()
    }

    // --- scheme version 10 part b: shipping (01 section 11) ---

    val SHIPPING_ZONE = table("market_shipping_zone", "Market shipping zone table.") {
        id()
        str("name", 128)
        text("countries", nullable = false) // JSON
        text("regions") // JSON
        text("postalPatterns") // JSON
        int("position", default = 0)
        str("status", 16, "ACTIVE")
        timestamps()
    }

    val SHIPPING_METHOD = table("market_shipping_method", "Market shipping method table.") {
        id()
        str("name", 128)
        str("description", 512, nullable = true)
        str("providerId", 64, "manual")
        str("serviceCode", 128, nullable = true)
        str("rateSource", 24, "RULES")
        bigint("freeShippingThreshold", nullable = true)
        bigint("handlingFee", default = 0)
        bigint("vatPercent", nullable = true)
        int("minDeliveryDays", nullable = true)
        int("maxDeliveryDays", nullable = true)
        int("maxWeightGrams", nullable = true)
        str("carrierName", 128, nullable = true)
        str("trackingUrlTemplate", 512, nullable = true)
        text("settings") // JSON
        int("position", default = 0)
        str("status", 16, "ACTIVE")
        bigint("deletedAt", nullable = true)
        timestamps()
    }

    val SHIPPING_RATE = table("market_shipping_rate", "Market shipping rate table.") {
        id()
        bigint("methodId")
        bigint("zoneId")
        str("basis", 24)
        bigint("rangeFrom", default = 0)
        bigint("rangeTo", nullable = true)
        bigint("price")
        bigint("perUnitPrice", default = 0)
        int("position", default = 0)
        timestamps()
        key("idx_method_zone", "methodId", "zoneId", "position")
    }

    val SHIPPING_CARRIER = table("market_shipping_carrier", "Market shipping carrier table.") {
        id()
        str("providerId", 64)
        flag("enabled", 0)
        text("settings") // ENC
        flag("testMode", 0)
        char("webhookToken", 40)
        bigint("lastInboundAt", nullable = true)
        str("lastError", 512, nullable = true)
        bigint("lastErrorAt", nullable = true)
        timestamps()
        unique("uq_provider", "providerId")
    }

    val SHIPMENT = table("market_shipment", "Market shipment table.") {
        id()
        bigint("orderId")
        bigint("methodId", nullable = true)
        str("providerId", 64)
        str("serviceCode", 128, nullable = true)
        str("status", 24, "CREATED")
        str("entryMode", 24, "CARRIER")
        str("merchantReference", 64)
        str("carrierReference", 191, nullable = true)
        str("trackingNumber", 128, nullable = true)
        str("trackingUrl", 1024, nullable = true)
        str("carrierName", 128, nullable = true)
        str("labelFile", 255, nullable = true)
        str("labelFormat", 8, nullable = true)
        text("documents") // JSON
        str("rateRef", 255, nullable = true)
        bigint("cost", nullable = true)
        str("costCurrency", 8, nullable = true)
        int("weightGrams", nullable = true)
        text("packages") // JSON
        bigint("estimatedDeliveryAt", nullable = true)
        str("note", 512, nullable = true)
        str("lastErrorCode", 32, nullable = true)
        str("lastError", 512, nullable = true)
        bigint("claimedUntil", nullable = true)
        flag("itemsReleased", 0)
        flag("stale", 0)
        text("fromAddress", nullable = false) // JSON
        text("toAddress", nullable = false) // JSON
        bigint("codAmount", nullable = true)
        text("providerData") // ENC
        flag("testMode", 0)
        bigint("nextPollAt", nullable = true)
        int("pollCount", default = 0)
        bigint("lastPolledAt", nullable = true)
        bigint("shippedAt", nullable = true)
        bigint("deliveredAt", nullable = true)
        bigint("cancelledAt", nullable = true)
        bigint("trackingMailSentAt", nullable = true)
        bigint("createdBy", nullable = true)
        timestamps()
        unique("uq_merchantRef", "merchantReference")
        key("idx_order", "orderId")
        key("idx_poll", "status", "nextPollAt")
        key("idx_carrierRef", "providerId", "carrierReference")
        key("idx_tracking", "trackingNumber")
    }

    val SHIPMENT_ITEM = table("market_shipment_item", "Market shipment item table.") {
        id()
        bigint("shipmentId")
        bigint("orderItemId")
        int("quantity")
        timestamps()
        unique("uq_shipment_item", "shipmentId", "orderItemId")
    }

    val SHIPMENT_EVENT = table("market_shipment_event", "Market shipment event table.") {
        id()
        bigint("shipmentId")
        str("status", 24)
        str("rawStatus", 128, nullable = true)
        str("description", 512, nullable = true)
        str("location", 255, nullable = true)
        bigint("occurredAt")
        str("source", 16)
        str("dedupeKey", 128)
        timestamps()
        unique("uq_shipment_event", "shipmentId", "dedupeKey")
        key("idx_shipment", "shipmentId", "occurredAt")
    }

    /** Every table the plugin owns, in creation order. Later migration slices append their tables here. */
    val tables: List<Table> = listOf(
        CATEGORY, COMPARISON, COUPON, CREATOR_CODE, DISCOUNT, GIFT, ORDER, ORDER_ITEM, PAYMENT_METHOD, PRODUCT,
        PRODUCT_VARIANT, PRODUCT_PRICE, PRODUCT_FIELD, BUNDLE_ITEM, PRODUCT_PROVIDER_META, CURRENCY_RATE,
        REDEMPTION, CREATOR_EARNING, CREATOR_PAYOUT,
        ORDER_EVENT, LEGAL_TEXT, SEQUENCE,
        ENTITLEMENT, ADDRESS, CART, CART_ITEM, INVOICE,
        PAYMENT, PAYMENT_EVENT, REFUND, REFUND_ITEM, DISPUTE, PROVIDER_STATE,
        CREDIT_ACCOUNT, CREDIT_TX, CREDIT_ENTRY,
        DELIVERY, SERVER_STATE, MAIL_OUTBOX,
        SUBSCRIPTION, SUBSCRIPTION_RENEWAL, BLOCK, THROTTLE, GOAL,
        SHIPPING_ZONE, SHIPPING_METHOD, SHIPPING_RATE, SHIPPING_CARRIER, SHIPMENT, SHIPMENT_ITEM, SHIPMENT_EVENT
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
     * The fixups of the schema, in order (01 section 14.2): the conversion of the rows an install carried before scheme
     * version 5. [ids] supplies `publicId` and `accessToken` (never SQL `UUID()`, 01 section 14.1 rule 7).
     *
     * Every fixup selects only unfixed rows, so a fixup that failed once simply runs again. The `LEGACY` marker has to
     * be set before the others look at `source = 'LEGACY'`; since a failing marker statement must not let a later
     * fixup fill `publicId` or `buyerKey` (the marker's own predicate would then never match again), every dependent
     * fixup runs the same marker `UPDATE` first and aborts when it fails.
     */
    fun fixups(ids: Ids = SecureIds()): List<Fixup> {
        val order = listOf("market_order")
        val buyerKeySql = "CASE WHEN `userId` IS NOT NULL THEN CONCAT('u:', `userId`) ELSE CONCAT('g:', LOWER(`playerUsername`)) END"
        val legacyOrder = "`orderId` IN (SELECT `id` FROM `{o}` WHERE `source` = 'LEGACY')"

        /** A fixup of one `UPDATE`: [where] is both the pending predicate and the `UPDATE`'s `WHERE`. */
        fun sqlFixup(id: String, table: String, where: String, set: String, extra: List<String> = emptyList()) = Fixup(
            id = id,
            requires = listOf(table) + extra,
            pendingSql = { prefix -> "SELECT COUNT(*) FROM `${prefix}$table` WHERE ${bind(where, prefix)}" },
            apply = { client, prefix ->
                markLegacyOrders(client, prefix)
                client.query("UPDATE `${prefix}$table` SET ${bind(set, prefix)} WHERE ${bind(where, prefix)}").execute().coAwait()
            }
        )

        return listOf(
            Fixup(
                id = "legacy-order-marker",
                requires = order,
                pendingSql = { prefix -> "SELECT COUNT(*) FROM `${prefix}market_order` WHERE $LEGACY_WHERE" },
                apply = { client, prefix -> markLegacyOrders(client, prefix) }
            ),
            Fixup(
                id = "order-public-ids",
                requires = order,
                pendingSql = { prefix -> "SELECT COUNT(*) FROM `${prefix}market_order` WHERE `publicId` IS NULL" },
                apply = { client, prefix -> fillOrderIds(client, prefix, ids) }
            ),
            sqlFixup("order-buyer-key", "market_order", "`buyerKey` = ''", "`buyerKey` = $buyerKeySql"),
            sqlFixup(
                "order-recipient-username", "market_order", "`recipientUsername` = '' AND `playerUsername` <> ''",
                "`recipientUsername` = `playerUsername`"
            ),
            // The payer of a version 2 order is also its recipient: the player named on the order.
            sqlFixup(
                "order-recipient-key", "market_order", "`recipientKey` = ''",
                "`recipientKey` = $buyerKeySql, `recipientUserId` = COALESCE(`recipientUserId`, `userId`)"
            ),
            sqlFixup("order-base-currency", "market_order", "`baseCurrency` = '' AND `currency` <> ''", "`baseCurrency` = `currency`"),
            sqlFixup(
                "order-legacy-totals", "market_order", "`source` = 'LEGACY' AND `subtotal` = 0 AND `totalPrice` > 0",
                "`subtotal` = `totalPrice`, `gatewayAmount` = `totalPrice`"
            ),
            // Only the items of LEGACY orders: a later order may legitimately carry a priced line that totals 0 (gift code).
            sqlFixup(
                "order-item-money", "market_order_item", "`lineTotal` = 0 AND `unitPrice` > 0 AND $legacyOrder",
                "`listUnitPrice` = `unitPrice`, `lineTotal` = `unitPrice` * `quantity`", extra = order
            ),
            sqlFixup(
                "legacy-paid-orders", "market_order",
                "`source` = 'LEGACY' AND `status` IN ('COMPLETED', 'REFUNDED') AND `paidAt` IS NULL",
                "`paidAt` = `updatedAt`, `paidAmount` = `totalPrice`, `reservationState` = 'COMMITTED'"
            ),
            Fixup(
                id = "soldCount",
                requires = listOf("market_order", "market_order_item", "market_product"),
                oneShot = true,
                apply = { client, prefix ->
                    markLegacyOrders(client, prefix)
                    client.query(
                        "UPDATE `${prefix}market_product` p JOIN (" +
                            "SELECT i.`productId` AS pid, SUM(i.`quantity`) AS sold FROM `${prefix}market_order_item` i " +
                            "JOIN `${prefix}market_order` o ON o.`id` = i.`orderId` " +
                            "WHERE o.`source` = 'LEGACY' AND o.`status` = 'COMPLETED' AND i.`productId` IS NOT NULL GROUP BY i.`productId`" +
                            ") s ON s.pid = p.`id` SET p.`soldCount` = s.sold"
                    ).execute().coAwait()
                }
            ),
            Fixup(
                id = "legacyUsedCount",
                requires = listOf("market_discount", "market_coupon", "market_creator_code"),
                oneShot = true,
                apply = { client, prefix ->
                    for (table in listOf("market_discount", "market_coupon", "market_creator_code")) {
                        client.query("UPDATE `${prefix}$table` SET `legacyUsedCount` = `usedCount`").execute().coAwait()
                    }
                }
            )
        )
    }

    private const val LEGACY_WHERE = "`publicId` IS NULL AND `buyerKey` = '' AND `source` <> 'LEGACY'"

    private fun bind(sql: String, prefix: String) = sql.replace("{o}", "${prefix}market_order")

    /** The first fixup of 01 section 14.2: rows with neither a public id nor a payer key predate scheme version 5. */
    private suspend fun markLegacyOrders(client: SqlClient, prefix: String) {
        client.query("UPDATE `${prefix}market_order` SET `source` = 'LEGACY' WHERE $LEGACY_WHERE").execute().coAwait()
    }

    /** Fills `publicId` and `accessToken` of every order without one, in chunks; a colliding public id is drawn again. */
    private suspend fun fillOrderIds(client: SqlClient, prefix: String, ids: Ids) {
        markLegacyOrders(client, prefix)
        while (true) {
            val rows = client.query("SELECT `id` FROM `${prefix}market_order` WHERE `publicId` IS NULL ORDER BY `id` LIMIT 500")
                .execute().coAwait().map { it.getLong("id") }
            if (rows.isEmpty()) return
            for (id in rows) {
                var attempts = 0
                while (true) {
                    try {
                        client.preparedQuery(
                            "UPDATE `${prefix}market_order` SET `publicId` = ?, `accessToken` = ? WHERE `id` = ? AND `publicId` IS NULL"
                        ).execute(Tuple.of(ids.publicId(), ids.hexToken(ACCESS_TOKEN_BYTES), id)).coAwait()
                        break
                    } catch (e: Exception) {
                        // ER_DUP_ENTRY on uq_publicId: 100 random bits colliding is practically impossible, but never fatal.
                        if (++attempts >= 5 || !e.isDuplicateKey()) throw e
                    }
                }
            }
        }
    }

    /** 160 bits, 40 hex characters (01 section 5.1 `accessToken`). */
    private const val ACCESS_TOKEN_BYTES = 20

    /** Marker table of the one-shot fixups (`market_sequence`, 01 section 5.6, arrives with the sequence slice). */
    const val ONE_SHOT_MARKER_TABLE = "market_sequence"

    // --- statements ---------------------------------------------------------------------------------------------

    /** Idempotent DDL of one table: the `CREATE` followed by its idempotent `ALTER`s. */
    fun ddl(table: Table, prefix: String): List<String> = listOf(table.createSql(prefix)) + table.alterSql(prefix) + table.indexSql(prefix)

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

    /**
     * Seeds the five system credit accounts through [client] ([seedCreditSystemAccountsSql]), swallowing and logging an
     * error like [installTable]. Returns the error messages, empty when the statement succeeded.
     */
    suspend fun seedCreditSystemAccounts(client: SqlClient, prefix: String): List<String> =
        try {
            client.query(seedCreditSystemAccountsSql(prefix)).execute().coAwait()
            emptyList()
        } catch (e: Exception) {
            logger.error("Market system credit account seed failed for {}: {}", CREDIT_ACCOUNT.physicalName(prefix), e.message)
            listOf("${CREDIT_ACCOUNT.physicalName(prefix)} seed: ${e.message}")
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
        for (table in tables) {
            val errors = installTable(pool, table, prefix)
            ddlErrors += errors
            // the system accounts are restored on every start (a deleted row comes back); a table that failed to create has no seed to report
            if (table === CREDIT_ACCOUNT && errors.isEmpty()) ddlErrors += seedCreditSystemAccounts(pool, prefix)
        }

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
