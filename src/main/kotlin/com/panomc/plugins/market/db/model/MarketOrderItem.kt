package com.panomc.plugins.market.db.model

import com.panomc.platform.db.DBEntity

/** `market_order_item.kind` (01 section 5.2). */
enum class OrderItemKind { PRODUCT, BUNDLE, BUNDLE_CHILD, CREDIT_TOPUP }

/** `market_order_item` (01 section 5.2). Money is x100 minor units of the order currency, [vatPercent] basis points. */
open class MarketOrderItem(
    val id: Long = -1,
    val orderId: Long = -1,
    val productId: Long? = null,
    val productName: String = "",
    val quantity: Int = 1,
    val unitPrice: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // --- scheme version 5 ---
    val kind: OrderItemKind = OrderItemKind.PRODUCT,
    val parentItemId: Long? = null,
    val variantId: Long? = null,
    val variantName: String? = null,
    val sku: String? = null,
    val listUnitPrice: Long = 0,
    val discountAmount: Long = 0,
    val upgradeAmount: Long = 0,
    val couponAmount: Long = 0,
    val vatPercent: Long = 0,
    val vatAmount: Long = 0,
    val lineTotal: Long = 0,
    val creditUnitPrice: Long? = null,
    val creditAmount: Long? = null,
    /** JSON text. */
    val fieldValues: String? = null,
    val targetServerId: Long? = null,
    /** JSON text: the product as sold. */
    val snapshot: String? = null,
    val physical: Boolean = false,
    val stockReserved: Int = 0,
    val refundedQuantity: Int = 0,
    val refundedAmount: Long = 0,
    val shippedQuantity: Int = 0,
    val gatewayItemRef: String? = null,
    val gatewayLineAmount: Long? = null,
    val upgradeFromEntitlementId: Long? = null,
) : DBEntity()
