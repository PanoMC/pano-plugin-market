package com.panomc.plugins.market.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The composition roots are the one place the host's beans are needed, so they cannot run in a test; what can be proven is that they hand the goal progress
 * writer to the two seams it belongs to (MK-171, evidence `WIRE-2` / `MK-051`): the order service's `ForeignEffects` chain and the refund effects. The
 * behaviour behind each seam is `GoalProgressIT`.
 */
class GoalWiringTest {
    private val root = File("src/main/kotlin/com/panomc/plugins/market")

    private fun source(path: String) = File(root, path).readText()

    @Test
    fun `the order service chain ends in GoalEffects before the pending-slices default`() {
        val text = source("routes/api/order/OrderRouteSupport.kt")

        assertTrue(text.contains("GoalEffects({ goalProgress(plugin) }, ForeignEffects.PENDING_SLICES)"), "AdvanceGoalProgress must reach the goal writer")
        assertTrue(!text.contains("MailEffects(orderMails(plugin), orderDao, ForeignEffects.PENDING_SLICES)"), "nothing may bypass GoalEffects")
    }

    @Test
    fun `the refund service gets the writer and step 10 calls it`() {
        assertTrue(source("routes/panel/refund/RefundRoutes.kt").contains("goals = goalProgress(plugin)"))
        assertTrue(source("service/RefundService.kt").contains("effects.goalProgress(conn, order, after, effective, lineDeltas.quantities)"))
        assertTrue(source("service/RefundEffects.kt").contains("goals?.onRefundSucceeded(conn, before, after, refund, quantities)"))
    }

    @Test
    fun `the new routes extend the market bases and use the shared services`() {
        assertTrue(source("routes/api/store/GetWidgetsAPI.kt").contains("class GetWidgetsAPI(private val plugin: MarketPlugin) : MarketApi()"))
        assertTrue(source("routes/panel/payment/PaymentEventRoutes.kt").contains("inboundDispatcher(plugin).replay(id)"), "the replay is the pipeline's, not a second implementation")
        assertTrue(source("routes/panel/payment/PaymentEventRoutes.kt").contains("FieldGating.rawTier(context)"))
        assertTrue(source("routes/panel/payment/PaymentEventRoutes.kt").contains("ReplayedMarketPaymentEventLog("))
        assertTrue(source("routes/panel/stats/PanelGetMarketStatsAPI.kt").contains("StatsService("))
    }
}
