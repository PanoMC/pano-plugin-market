package com.panomc.plugins.market.db

import com.panomc.plugins.market.db.impl.MarketDisputeDaoImpl
import com.panomc.plugins.market.db.impl.MarketProviderStateDaoImpl
import com.panomc.plugins.market.db.model.DisputeOrigin
import com.panomc.plugins.market.db.model.DisputeRecordStatus
import com.panomc.plugins.market.db.model.MarketDispute
import com.panomc.plugins.market.db.model.MarketProviderState
import com.panomc.plugins.market.db.model.ProviderStateKind
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** `market_dispute` (01 section 6.5) and `market_provider_state` (01 section 6.6). */
class MarketDisputeDaoIT : MarketDaoITBase() {
    private val disputes = MarketDisputeDaoImpl()
    private val states = MarketProviderStateDaoImpl()

    private fun dispute(provider: String? = "stripe", gatewayId: String? = "dp_1", order: Long = 1) = MarketDispute(
        orderId = order, paymentId = 2, providerId = provider, gatewayDisputeId = gatewayId, status = DisputeRecordStatus.LOST, origin = DisputeOrigin.MANUAL,
        amount = 999, currency = "EUR", reason = "fraudulent", openedAt = 100, resolvedAt = 200, createdBy = 3, createdAt = 10, updatedAt = 20
    )

    @Test
    fun `a dispute round-trips every column`(): Unit = runBlocking {
        val written = dispute()
        EntityRoundTrip.differsFromDefaults(written, MarketDispute())
        val id = disputes.add(written, pool)!!
        val read = disputes.getById(id, pool)!!
        assertEquals(id, read.id)
        EntityRoundTrip.assertSame(written, read)
        assertNull(disputes.getById(9999, pool))
    }

    @Test
    fun `a dispute with only the required columns reads back OPEN`(): Unit = runBlocking {
        val minimal = MarketDispute(orderId = 4, origin = DisputeOrigin.GATEWAY, amount = 5, currency = "EUR", openedAt = 6, createdAt = 1, updatedAt = 2)
        val read = disputes.getById(disputes.add(minimal, pool)!!, pool)!!
        EntityRoundTrip.assertSame(minimal, read)
        assertEquals(DisputeRecordStatus.OPEN, read.status)
    }

    @Test
    fun `a gateway dispute id belongs to one dispute per provider and manual disputes do not collide`(): Unit = runBlocking {
        assertNotNull(disputes.add(dispute(), pool))
        assertNull(disputes.add(dispute(), pool))
        assertNotNull(disputes.add(dispute(provider = "paypal"), pool))
        assertNotNull(disputes.add(dispute(provider = null, gatewayId = null), pool))
        assertNotNull(disputes.add(dispute(provider = null, gatewayId = null), pool))
        assertEquals(1L, disputes.getByProviderDispute("stripe", "dp_1", pool)!!.id)
        assertNull(disputes.getByProviderDispute("stripe", "dp_x", pool))
        assertEquals(4, disputes.getByOrderId(1, pool).size)
        assertEquals(emptyList<MarketDispute>(), disputes.getByOrderId(55, pool))
    }

    private fun state(kind: ProviderStateKind = ProviderStateKind.SHIPPING, provider: String = "mng", key: String = "oauth", value: String = "v1:QUJD") =
        MarketProviderState(kind = kind, providerId = provider, stateKey = key, value = value, expiresAt = 5_000, createdAt = 10, updatedAt = 20)

    @Test
    fun `a provider state entry round-trips and its ENC value is stored verbatim`(): Unit = runBlocking {
        val written = state(value = "v1:" + "Zm9v".repeat(30_000))
        EntityRoundTrip.differsFromDefaults(written, MarketProviderState())
        val id = states.add(written, pool)!!
        val read = states.getById(id, pool)!!
        EntityRoundTrip.assertSame(written, read)
        assertEquals(written.value, states.get(ProviderStateKind.SHIPPING, "mng", "oauth", pool)!!.value)
        assertNull(states.get(ProviderStateKind.PAYMENT, "mng", "oauth", pool))
    }

    @Test
    fun `a state key is stored once per kind and provider`(): Unit = runBlocking {
        assertNotNull(states.add(state(), pool))
        assertNull(states.add(state(value = "v1:other"), pool))
        assertNotNull(states.add(state(kind = ProviderStateKind.PAYMENT), pool))
        assertNotNull(states.add(state(provider = "ups"), pool))
        assertNotNull(states.add(state(key = "webhookId"), pool))
        assertEquals(4L, count("market_provider_state"))
    }

    @Test
    fun `a state key of per-buyer data fits the 191 character limit`(): Unit = runBlocking {
        val key = "user:42:" + "k".repeat(191 - 8)
        assertNotNull(states.add(state(key = key), pool))
        assertEquals(key, states.get(ProviderStateKind.SHIPPING, "mng", key, pool)!!.stateKey)
    }
}
