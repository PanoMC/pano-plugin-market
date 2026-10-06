package com.panomc.plugins.market.notification

import com.panomc.platform.annotation.NotificationDefinition
import com.panomc.platform.notification.PanelUserNotificationType

/**
 * The panel notification types market defines (15 section 2.6, X-5). Names are global, so every type carries the `Market` prefix; the name the host stores is the
 * snake-cased class name without `Notification` (`MARKET_ORDER_REVIEW`). The text key is `notifications.<NAME>` in the plugin's locale files (served to the panel as
 * `plugins.pano-plugin-market.notifications.<NAME>`); `href` (a site-relative panel path) makes the row clickable. Market defines `PanelUserNotificationType`
 * types only, never a `UserNotificationType`, so a theme on an older sdk is never shown one (15 section 8.1).
 *
 * These classes are only instantiated when the host has `NotificationTypeRegistry` ([com.panomc.plugins.market.runtime.MarketRuntime.HostCapabilities.notifications]).
 */
@NotificationDefinition
data class MarketOrderReviewNotification(
    val orderId: Long? = null,
    /** The order's `reviewReason` (a `ReviewReason` name or a market alert code such as `DUPLICATE_PAYMENT`). */
    val reason: String? = null,
    val href: String? = null,
    val faIcon: String? = "fa-triangle-exclamation"
) : PanelUserNotificationType()

/** A refund or dispute alert on an order (`OVER_REFUND`, `REVOKE_TIMEOUT`, `REVOKE_FAILED`, `CHARGEBACK_OPENED`, `CLAWBACK_SHORTFALL`, ...). */
@NotificationDefinition
data class MarketOrderAlertNotification(
    val orderId: Long? = null,
    val code: String? = null,
    val href: String? = null,
    val faIcon: String? = "fa-circle-exclamation"
) : PanelUserNotificationType()

/**
 * Deliveries wait for a Minecraft server that cannot take them (08 section 8.5), or an undo (`REVOKE` / `EXPIRE`) failed or waits too long (the buyer may hold both
 * the money and the rank). [urgent] marks the second kind; [orderId] / [deliveryId] are set for it, [count] / [serverName] for the first.
 */
@NotificationDefinition
data class MarketDeliveryWaitingNotification(
    val serverId: Long? = null,
    val serverName: String? = null,
    val count: Long? = null,
    val orderId: Long? = null,
    val deliveryId: Long? = null,
    val urgent: Boolean = false,
    val failed: Boolean = false,
    /** `WAITING`, `UNDO_FAILED` or `UNDO_WAITING`: picks the text (an ICU select in the locale files). */
    val kind: String = "WAITING",
    val href: String? = null,
    val faIcon: String? = "fa-box-open"
) : PanelUserNotificationType()
