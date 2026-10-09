package com.panomc.plugins.market.routes.base

import com.panomc.platform.schema.CoreSchemas
import io.vertx.json.schema.common.dsl.ObjectSchemaBuilder
import io.vertx.json.schema.common.dsl.Schemas.arraySchema
import io.vertx.json.schema.common.dsl.Schemas.booleanSchema
import io.vertx.json.schema.common.dsl.Schemas.intSchema
import io.vertx.json.schema.common.dsl.Schemas.numberSchema
import io.vertx.json.schema.common.dsl.Schemas.objectSchema
import io.vertx.json.schema.common.dsl.Schemas.stringSchema

/**
 * The response shapes of the market's public endpoints for the OpenAPI document (04 section 5). The platform keeps its
 * shared shapes in `CoreSchemas`; a plugin keeps its own, here. Each named shape carries the `$id` `pano:Market<Name>`, which
 * the generator turns into `components.schemas.Market<Name>`, so a client names the type once and the market's names cannot
 * collide with core's.
 *
 * The shapes describe what the endpoints answer today and stay open (no `additionalProperties: false`): adding a field to
 * a response must never break a client (04 section 6). A property is required only when the code always writes it;
 * a value that can be `null` is declared `nullable`. Money is a decimal number (the wire format of 04 section 2).
 */
internal object MarketSchemas {
    private fun shape(name: String): ObjectSchemaBuilder = CoreSchemas.shape("Market$name")

    private fun text() = stringSchema()

    private fun money() = numberSchema()

    private fun objects() = arraySchema().items(objectSchema())

    private fun texts() = arraySchema().items(stringSchema())

    /** `page` of a paged answer: `{ number, size, totalItems, totalPages }` (04 section 4). */
    fun page(): ObjectSchemaBuilder = objectSchema()
        .requiredProperty("number", intSchema())
        .requiredProperty("size", intSchema())
        .requiredProperty("totalItems", intSchema())
        .requiredProperty("totalPages", intSchema())

    /** `{ items: [item], page }` plus the extra keys the caller adds to the returned builder. */
    fun paged(item: ObjectSchemaBuilder): ObjectSchemaBuilder = objectSchema()
        .requiredProperty("items", arraySchema().items(item))
        .requiredProperty("page", page())

    // ------------------------------------------------------------------------------------------------------ catalogue

    private fun saleBadge() = objectSchema()
        .optionalProperty("percent", numberSchema().nullable())
        .optionalProperty("amountOff", numberSchema().nullable())
        .optionalProperty("endsAt", intSchema().nullable())
        .nullable()

    private fun period() = objectSchema()
        .requiredProperty("unit", text())
        .requiredProperty("count", intSchema())
        .nullable()

    /** The properties of a product card, shared with the product page, which is a card plus more. */
    private fun cardProperties(builder: ObjectSchemaBuilder): ObjectSchemaBuilder = builder
        .requiredProperty("id", intSchema())
        .requiredProperty("slug", text())
        .requiredProperty("name", text())
        .requiredProperty("kind", text())
        .requiredProperty("price", money())
        .requiredProperty("currency", text())
        .requiredProperty("inStock", booleanSchema())
        .requiredProperty("featured", booleanSchema())
        .requiredProperty("physical", booleanSchema())
        .requiredProperty("billingMode", text())
        .requiredProperty("hasVariants", booleanSchema())
        .requiredProperty("needsOptions", booleanSchema())
        .optionalProperty("shortDescription", text().nullable())
        .optionalProperty("categoryId", intSchema().nullable())
        .optionalProperty("compareAtPrice", money().nullable())
        .optionalProperty("creditPrice", money())
        .optionalProperty("priceFrom", booleanSchema())
        .optionalProperty("stock", intSchema().nullable())
        .optionalProperty("priority", intSchema())
        .optionalProperty("icon", text().nullable())
        .optionalProperty("imageFileName", text().nullable())
        .optionalProperty("tierRank", intSchema().nullable())
        .optionalProperty("period", period())
        .optionalProperty("sale", saleBadge())
        .optionalProperty("owned", booleanSchema().nullable())

    /** One product in a listing: no description, the price already in the requested currency. */
    val productCard: ObjectSchemaBuilder = cardProperties(shape("ProductCard"))

    private fun variant() = objectSchema()
        .requiredProperty("id", intSchema())
        .requiredProperty("name", text())
        .requiredProperty("price", money())
        .requiredProperty("inStock", booleanSchema())
        .optionalProperty("optionValues", objectSchema().nullable())
        .optionalProperty("compareAtPrice", money().nullable())
        .optionalProperty("creditPrice", money())
        .optionalProperty("stock", intSchema().nullable())
        .optionalProperty("imageFileName", text().nullable())
        .optionalProperty("periodCount", intSchema().nullable())

    private fun field() = objectSchema()
        .requiredProperty("fieldKey", text())
        .requiredProperty("label", text())
        .requiredProperty("type", text())
        .requiredProperty("required", booleanSchema())
        .optionalProperty("helpText", text().nullable())
        .optionalProperty("options", arraySchema().nullable())
        .optionalProperty("pattern", text().nullable())
        .optionalProperty("placeholder", text().nullable())
        .optionalProperty("defaultValue", text().nullable())

    /** The product page: a [productCard] with the description, variants, custom fields, bundle items and the buy advice. */
    val productDetail: ObjectSchemaBuilder = cardProperties(shape("ProductDetail"))
        .optionalProperty("description", text().nullable())
        .optionalProperty("categoryName", text().nullable())
        .optionalProperty("metaTitle", text().nullable())
        .optionalProperty("metaDescription", text().nullable())
        .requiredProperty("variants", arraySchema().items(variant()))
        .requiredProperty("fields", arraySchema().items(field()))
        .requiredProperty("bundleItems", objects())
        .requiredProperty("requiredProducts", objects())
        .requiredProperty("serverChoices", objects())
        .requiredProperty(
            "purchasable",
            objectSchema().requiredProperty("ok", booleanSchema()).optionalProperty("reason", text().nullable())
        )
        .optionalProperty("upgrade", objectSchema().nullable())
        .optionalProperty("vatPercent", numberSchema())
        .optionalProperty("pricesIncludeVat", booleanSchema())

    private fun storeSettings() = objectSchema()
        .requiredProperty("storeName", text())
        .requiredProperty("currency", text())
        .requiredProperty("currencySymbol", text())
        .requiredProperty("currencies", texts())
        .requiredProperty("creditsEnabled", booleanSchema())
        .requiredProperty("allowGuestCheckout", booleanSchema())
        .requiredProperty("allowGiftPurchase", booleanSchema())
        .requiredProperty("modules", objectSchema())
        .requiredProperty("pageSize", intSchema())

    /** `GET /store`: the first page of cards and what the store front needs around it. */
    val store: ObjectSchemaBuilder = paged(productCard)
        .requiredProperty("settings", storeSettings())
        .requiredProperty("categories", objects())
        .requiredProperty("featured", arraySchema().items(productCard))
        .requiredProperty("bestsellers", arraySchema().items(productCard))
        .requiredProperty("comparisons", objects())
        .requiredProperty("comparisonProducts", arraySchema().items(productCard))

    /** `GET /widgets`: each module is present only when its flag is on. */
    val widgets: ObjectSchemaBuilder = objectSchema()
        .optionalProperty("recentBuyers", objects())
        .optionalProperty("topSupporters", objects())
        .optionalProperty("goals", objects())
        .optionalProperty("stats", objectSchema())
        .requiredProperty("sidebars", texts())

    // ------------------------------------------------------------------------------------------------------- checkout

    /** `GET /checkout/config`. */
    val checkoutConfig: ObjectSchemaBuilder = objectSchema()
        .requiredProperty("guestCheckout", booleanSchema())
        .requiredProperty("giftPurchase", booleanSchema())
        .requiredProperty("billingInfoMode", text())
        .requiredProperty("creditsEnabled", booleanSchema())
        .requiredProperty("mixedCredit", booleanSchema())
        .requiredProperty("currencies", texts())
        .requiredProperty("addressFields", objectSchema())
        .requiredProperty("shippingCountries", texts())
        .requiredProperty("minimumOrderAmount", money())
        .requiredProperty("creditTopUp", objectSchema())
        .optionalProperty("creditName", text())
        .optionalProperty("legal", objectSchema().nullable())

    private fun quoteLine() = objectSchema()
        .requiredProperty("lineKey", text())
        .requiredProperty("name", text())
        .requiredProperty("quantity", intSchema())
        .requiredProperty("unitPrice", money())
        .requiredProperty("lineTotal", money())
        .requiredProperty("kind", text())
        .requiredProperty("errors", texts())
        .optionalProperty("productId", intSchema().nullable())
        .optionalProperty("variantId", intSchema())
        .optionalProperty("variantName", text().nullable())
        .optionalProperty("slug", text().nullable())
        .optionalProperty("imageFileName", text().nullable())
        .optionalProperty("listUnitPrice", money())
        .optionalProperty("discountAmount", money())
        .optionalProperty("couponAmount", money())
        .optionalProperty("vatAmount", money())
        .optionalProperty("parentLineKey", text().nullable())

    private fun quoteMessage() = objectSchema()
        .requiredProperty("code", text())
        .requiredProperty("level", text())
        .optionalProperty("lineKey", text())
        .optionalProperty("fields", texts())
        .optionalProperty("reason", text())

    /** What a checkout of a cart would charge, and why it cannot be done when it cannot. */
    val quote: ObjectSchemaBuilder = shape("Quote")
        .requiredProperty("currency", text())
        .requiredProperty("baseCurrency", text())
        .requiredProperty("lines", arraySchema().items(quoteLine()))
        .requiredProperty("subtotal", money())
        .requiredProperty("discountTotal", money())
        .requiredProperty("shippingTotal", money())
        .requiredProperty("paymentFee", money())
        .requiredProperty("vatTotal", money())
        .requiredProperty("total", money())
        .requiredProperty("gatewayAmount", money())
        .requiredProperty("requiresShipping", booleanSchema())
        .requiredProperty("paymentMethods", objects())
        .requiredProperty("requiredBuyerFields", texts())
        .requiredProperty("messages", arraySchema().items(quoteMessage()))
        .requiredProperty("canCheckout", booleanSchema())
        .optionalProperty("displayCurrency", text())
        .optionalProperty("pricingMode", text())
        .optionalProperty("pricesIncludeVat", booleanSchema())
        .optionalProperty("couponDiscount", money())
        .optionalProperty("creatorDiscount", money())
        .optionalProperty("upgradeDiscount", money())
        .optionalProperty("minimumOrderAmount", money())
        .optionalProperty("credits", objectSchema().nullable())
        .optionalProperty("coupon", objectSchema().nullable())
        .optionalProperty("creatorCode", objectSchema().nullable())
        .optionalProperty("shippingOptions", objects())
        .optionalProperty("shippingMethodId", intSchema().nullable())
        .optionalProperty("legal", objectSchema().nullable())
        .optionalProperty("display", objectSchema().nullable())

    /** How the buyer is sent on to pay: the `kind` says what to do (redirect, show instructions, nothing). */
    private fun paymentStart() = objectSchema()
        .requiredProperty("kind", text())
        .nullable()

    private fun orderItem() = objectSchema()
        .optionalProperty("name", text())
        .optionalProperty("variantName", text().nullable())
        .optionalProperty("imageFileName", text().nullable())
        .optionalProperty("quantity", intSchema())

    /**
     * An order as its caller may see it. The owner (the payer's session, or the holder of the order token) sees all of it;
     * a recipient or a stranger gets a cut-down view with `limited: true`.
     */
    val order: ObjectSchemaBuilder = shape("Order")
        .requiredProperty("publicId", text())
        .requiredProperty("status", text())
        .requiredProperty("fulfillmentStatus", text())
        .requiredProperty("shippingStatus", text())
        .requiredProperty("limited", booleanSchema())
        .requiredProperty("createdAt", intSchema())
        .requiredProperty("currency", text())
        .requiredProperty("isGift", booleanSchema())
        .requiredProperty("items", arraySchema().items(orderItem()))
        .optionalProperty("number", intSchema().nullable())
        .optionalProperty("paidAt", intSchema().nullable())
        .optionalProperty("expiresAt", intSchema().nullable())
        .optionalProperty("testMode", booleanSchema())
        .optionalProperty("totals", objectSchema().nullable())
        .optionalProperty("recipientUsername", text().nullable())
        .optionalProperty(
            "payment",
            objectSchema()
                .optionalProperty("methodId", text().nullable())
                .optionalProperty("label", text().nullable())
                .optionalProperty("status", text())
                .optionalProperty("start", paymentStart())
                .nullable()
        )
        .optionalProperty("shipping", objectSchema().nullable())
        .optionalProperty("shipments", objects())
        .optionalProperty("shippingAddress", objectSchema().nullable())
        .optionalProperty("billingInfo", objectSchema().nullable())
        .optionalProperty("email", text().nullable())
        .optionalProperty("invoiceAvailable", booleanSchema())
        .optionalProperty("canCancel", booleanSchema())
        .optionalProperty("canRetryPayment", booleanSchema())
        .optionalProperty("refundPending", booleanSchema())
        .optionalProperty("paymentMethods", objects())
        .optionalProperty("credits", objectSchema().nullable())

    /** `POST /checkout`: the order, its access token (returned only here) and how to pay. */
    val checkoutResult: ObjectSchemaBuilder = objectSchema()
        .requiredProperty("order", order)
        .requiredProperty("orderToken", text())
        .optionalProperty("payment", paymentStart())

    /** `POST /orders/:publicId/pay` and `/payment/continue`. */
    val paymentAnswer: ObjectSchemaBuilder = objectSchema()
        .optionalProperty("payment", paymentStart())

    /** `GET /orders/:publicId/status`: the small answer a page polls while a payment settles. */
    val orderStatus: ObjectSchemaBuilder = objectSchema()
        .requiredProperty("status", text())
        .requiredProperty("fulfillmentStatus", text())
        .requiredProperty("shippingStatus", text())
        .requiredProperty("updatedAt", intSchema())
        .optionalProperty("paymentStatus", text().nullable())

    /** An answer with no data: `{}`. */
    fun empty(): ObjectSchemaBuilder = objectSchema()

    // ------------------------------------------------------------------------------------------------------- buyer

    /** `{ cart, quote }`, the answer of every cart route: the cart as stored and the real quote of it. */
    val cartAnswer: ObjectSchemaBuilder = objectSchema()
        .requiredProperty(
            "cart",
            objectSchema()
                .requiredProperty("items", objects())
                .optionalProperty("couponCode", text().nullable())
                .optionalProperty("creatorCode", text().nullable())
                .optionalProperty("recipientUsername", text().nullable())
                .optionalProperty("giftMessage", text().nullable())
                .optionalProperty("shippingAddressId", intSchema().nullable())
                .optionalProperty("shippingMethodId", intSchema().nullable())
                .optionalProperty("currency", text().nullable())
        )
        .requiredProperty("quote", quote)

    /** One row of `GET /me/orders`. */
    val buyerOrder: ObjectSchemaBuilder = objectSchema()
        .requiredProperty("publicId", text())
        .requiredProperty("status", text())
        .requiredProperty("total", money())
        .requiredProperty("currency", text())
        .requiredProperty("createdAt", intSchema())
        .requiredProperty("itemNames", texts())
        .requiredProperty("isGift", booleanSchema())
        .requiredProperty("received", booleanSchema())
        .optionalProperty("number", intSchema())
        .optionalProperty("fulfillmentStatus", text())
        .optionalProperty("shippingStatus", text())
        .optionalProperty("paidAt", intSchema().nullable())
        .optionalProperty("recipientUsername", text().nullable())

    /** One row of `GET /me/entitlements`. */
    val entitlement: ObjectSchemaBuilder = objectSchema()
        .requiredProperty("id", intSchema())
        .requiredProperty("productId", intSchema())
        .requiredProperty("status", text())
        .optionalProperty("productName", text().nullable())
        .optionalProperty("variantName", text().nullable())
        .optionalProperty("startsAt", intSchema().nullable())
        .optionalProperty("expiresAt", intSchema().nullable())
        .optionalProperty("subscriptionId", intSchema().nullable())
        .optionalProperty("orderPublicId", text().nullable())

    /** One entry of the credit ledger; `amount` is signed. */
    val creditEntry: ObjectSchemaBuilder = objectSchema()
        .requiredProperty("id", intSchema())
        .requiredProperty("type", text())
        .requiredProperty("amount", money())
        .requiredProperty("balanceAfter", money())
        .requiredProperty("createdAt", intSchema())
        .optionalProperty("note", text().nullable())
        .optionalProperty("orderPublicId", text().nullable())

    /** `GET /me/credits`. */
    val credits: ObjectSchemaBuilder = paged(creditEntry)
        .requiredProperty("balance", money())
        .optionalProperty("creditName", text())

    /** `GET /me/summary`: what a navbar badge and the profile navigation need in one call. */
    val summary: ObjectSchemaBuilder = objectSchema()
        .requiredProperty("creditsEnabled", booleanSchema())
        .requiredProperty("creditBalance", money())
        .requiredProperty("cartItemCount", intSchema())
        .requiredProperty("activeSubscriptionCount", intSchema())
        .requiredProperty("subscriptionCount", intSchema())
        .requiredProperty("isCreator", booleanSchema())
        .optionalProperty("creditName", text())

    /** `GET /me/subscriptions` and the `subscription` the cancel and resume answers carry. */
    val subscription: ObjectSchemaBuilder = objectSchema()
}
