package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity
import com.panomc.plugins.market.util.OrderStatus
import java.math.BigDecimal

/** `market_order.source` (01 section 5.1). `LEGACY` marks rows that existed before scheme version 5. */
enum class OrderSource { STOREFRONT, PANEL, GIFT_CODE, RENEWAL, EXTERNAL, INGAME, LEGACY }

enum class ReservationState { NONE, HELD, COMMITTED, RELEASED }

enum class PricingMode { MARKET, EXTERNAL_TAX, EXTERNAL }

enum class DisputeStatus { NONE, OPEN, WON, LOST }

enum class FulfillmentStatus { NONE, PENDING, PARTIAL, FULFILLED, FAILED, REVOKED }

enum class FulfillmentBy { MARKET, GATEWAY }

enum class ShippingStatus { NOT_REQUIRED, PENDING, PARTIAL, SHIPPED, DELIVERED, RETURNED }

/**
 * `market_order` (01 section 5.1). Money columns are x100 minor units of [currency]; percentages are basis points
 * (percent x 100). JSON columns ([shippingAddress], [shippingQuote], [billingInfo]) are carried as JSON text.
 * Every field added in scheme version 5 has the default of its column, so an entity built with only the version 2
 * fields inserts the same row the old code did.
 */
open class MarketOrder(
    val id: Long = -1,
    val userId: Long? = null,
    val playerUsername: String = "",
    val totalPrice: Long = 0,
    val currency: String = "TRY",
    val paymentMethodId: String = "",
    val paymentLabel: String = "",
    val status: OrderStatus = OrderStatus.PENDING,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val exchangeRate: Double? = null,
    // --- scheme version 5 ---
    /** Crockford base32, 20 characters; URL id `/store/order/<publicId>`. */
    val publicId: String? = null,
    /** Hex, 40 characters; full guest access. */
    val accessToken: String? = null,
    val source: OrderSource = OrderSource.STOREFRONT,
    /** `u:<userId>` or `g:<lower-cased minecraft username>`. */
    val buyerKey: String = "",
    val idempotencyKey: String? = null,
    val idempotencyHash: String? = null,
    val email: String? = null,
    val locale: String? = null,
    val clientIp: String? = null,
    val userAgent: String? = null,
    val recipientUsername: String = "",
    val recipientUserId: Long? = null,
    val recipientKey: String = "",
    val isGift: Boolean = false,
    val giftMessage: String? = null,
    val hideFromBroadcast: Boolean = false,
    val reservationState: ReservationState = ReservationState.NONE,
    val expiresAt: Long? = null,
    val baseCurrency: String = "",
    val fxRate: BigDecimal = BigDecimal.ONE,
    val displayCurrency: String? = null,
    val displayRate: BigDecimal? = null,
    val pricingMode: PricingMode = PricingMode.MARKET,
    val pricesIncludeVat: Boolean = true,
    val subtotal: Long = 0,
    val discountTotal: Long = 0,
    val couponDiscount: Long = 0,
    val creatorDiscount: Long = 0,
    val upgradeDiscount: Long = 0,
    val shippingTotal: Long = 0,
    val shippingVatPercent: Long = 0,
    val shippingVatAmount: Long = 0,
    val paymentFee: Long = 0,
    val paymentFeeVatPercent: Long = 0,
    val paymentFeeVatAmount: Long = 0,
    val vatTotal: Long = 0,
    val creditAmount: Long = 0,
    val creditValue: Long = 0,
    val gatewayAmount: Long = 0,
    val paidAmount: Long = 0,
    val refundedTotal: Long = 0,
    val refundedGatewayAmount: Long = 0,
    val refundedCreditAmount: Long = 0,
    val couponId: Long? = null,
    val creatorCodeId: Long? = null,
    val giftId: Long? = null,
    val couponCode: String? = null,
    val creatorCode: String? = null,
    val paymentId: Long? = null,
    val paidAt: Long? = null,
    val testMode: Boolean = false,
    val statusBeforeDispute: OrderStatus? = null,
    val disputeStatus: DisputeStatus = DisputeStatus.NONE,
    val reviewReason: String? = null,
    val fulfillmentStatus: FulfillmentStatus = FulfillmentStatus.NONE,
    val fulfillmentBy: FulfillmentBy = FulfillmentBy.MARKET,
    val requiresShipping: Boolean = false,
    val shippingStatus: ShippingStatus = ShippingStatus.NOT_REQUIRED,
    val shippingAddress: String? = null,
    val shippingMethodId: Long? = null,
    val shippingMethodName: String? = null,
    val shippingQuote: String? = null,
    val shippingWeightGrams: Int? = null,
    val billingInfo: String? = null,
    val legalTextId: Long? = null,
    val legalAcceptedAt: Long? = null,
    val subscriptionId: Long? = null,
    val invoiceId: Long? = null,
    val note: String? = null,
    val createdBy: Long? = null,
) : DBEntity()
