package com.panomc.plugins.market.service

import com.panomc.plugins.market.config.MarketConfig
import com.panomc.plugins.market.core.order.OrderEffect
import com.panomc.plugins.market.db.MarketDaoITBase
import com.panomc.plugins.market.db.model.CreatorEarningState
import com.panomc.plugins.market.db.tx.OrderLockScope
import com.panomc.plugins.market.db.tx.txRestartingOnOrderChange
import com.panomc.plugins.market.job.HousekeepingJob
import com.panomc.plugins.market.service.platform.DirectoryUser
import com.panomc.plugins.market.service.platform.UserDirectory
import com.panomc.plugins.market.support.MarketTestDb
import com.panomc.plugins.market.support.TestWiring
import io.vertx.core.Vertx
import io.vertx.sqlclient.SqlClient
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * WIRE-3: the hourly release of due creator earnings by the housekeeping job (the open seam MK-153 named: "NOT wired: hourly `CreatorService.releaseDue`"). The job
 * runs the real [CreatorService] on a real MariaDB, no balance is read in between, so only the job can have released the row (evidence/WIRE-3.md).
 */
class Wire3EarningsIT : MarketDaoITBase() {
    private lateinit var w: TestWiring
    private lateinit var r: RefundWorld
    private lateinit var creators: CreatorService
    private lateinit var effects: CreatorEffects
    private val vertx: Vertx = Vertx.vertx()
    private val day = 86_400_000L

    @AfterAll
    fun closeVertx() {
        vertx.close()
    }

    @BeforeEach
    fun wire() {
        w = TestWiring(pool)
        r = RefundWorld(w, vertx)
        w.configure {
            MarketConfig(
                currency = com.panomc.plugins.market.util.CurrencyType.EUR, vatPercent = 20.0, showVatInPrice = true, creditValue = 1.0, storeTimeZone = "UTC",
                creatorEarningHoldDays = 14, creditsEnabled = true
            )
        }

        val directory = object : UserDirectory {
            override suspend fun byUsername(username: String, sqlClient: SqlClient) = w.users.idOf(username)?.let { DirectoryUser(it, w.users.nameOf(it)!!) }

            override suspend fun usernameOf(userId: Long, sqlClient: SqlClient): String? = w.users.nameOf(userId)

            override suspend fun emailOf(userId: Long, sqlClient: SqlClient): String? = null

            override suspend fun hasPermission(userId: Long, node: String) = false
        }
        val ds = DeliveryService(
            w.db, r.d.locks, w.clock, w.ids, { w.config }, w.orders, w.orderItems, w.orderEvents, w.deliveries, w.entitlements, w.creditAccounts, w.products, w.fields,
            FakeRoster(), directory, FakePlayerAccounts(w.users), r.d.credits, r.d.permissionService, Random(7)
        )

        creators = CreatorService(w.db, w.clock, { w.config }, w.creatorEarnings, w.creatorPayouts, r.d.credits, ds, directory, { MarketTestDb.TABLE_PREFIX }, { pool })
        effects = CreatorEffects({ creators }, w.orders, ForeignEffects { _, _, _ -> })
    }

    private fun job() = HousekeepingJob(w.clock, { MarketTestDb.TABLE_PREFIX }, { pool }, null, null, null, null, creatorEarnings = { client -> creators.releaseDue(client) })

    private suspend fun accrued(): Pair<Long, Long> {
        val streamer = w.fixtures.user("Streamer")
        val code = w.fixtures.creatorCode(code = "STREAM", creator = "Streamer", commissionPercent = 1000)

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_creator_code` SET `creatorUserId` = ? WHERE `id` = ?", streamer.id, code.id)

        val paid = r.place(w.fixtures.user("Buyer"), listOf(RefundLine(10_000)))

        MarketTestDb.sql(pool, "UPDATE `${MarketTestDb.TABLE_PREFIX}market_order` SET `creatorCodeId` = ?, `source` = 'STOREFRONT' WHERE `id` = ?", code.id, paid.order.id)

        w.db.txRestartingOnOrderChange { conn ->
            r.d.locks.forOrder(conn, paid.order.id, OrderLockScope.COMMIT) { locked -> effects.apply(conn, locked, OrderEffect.AccrueCreatorEarning) }
        }

        return paid.order.id to code.id
    }

    private suspend fun state(orderId: Long, codeId: Long) = w.creatorEarnings.get(orderId, codeId, pool)!!.state

    @Test
    fun `the earnings task releases the due earnings of every creator and only those, without a balance read`(): Unit = runBlocking {
        val (orderId, codeId) = accrued()

        assertEquals(CreatorEarningState.PENDING, state(orderId, codeId))
        assertEquals(0, job().run(HousekeepingJob.Task.EARNINGS), "nothing is due on the day of the order")
        assertEquals(CreatorEarningState.PENDING, state(orderId, codeId))

        w.clock.advance(13 * day)

        assertEquals(0, job().run(HousekeepingJob.Task.EARNINGS), "the hold lasts 14 days")
        assertEquals(CreatorEarningState.PENDING, state(orderId, codeId))

        w.clock.advance(2 * day)

        assertEquals(1, job().run(HousekeepingJob.Task.EARNINGS))
        assertEquals(CreatorEarningState.AVAILABLE, state(orderId, codeId))
        assertEquals(0, job().run(HousekeepingJob.Task.EARNINGS), "a second run changes nothing")
    }

    @Test
    fun `the scheduled tick runs the earnings task on its own hourly cadence`(): Unit = runBlocking {
        val (orderId, codeId) = accrued()
        val job = job()

        job.runOnce()

        assertEquals(CreatorEarningState.PENDING, state(orderId, codeId))

        // the hold is over, but the task ran at the first tick: it is not due again before the hour is over
        w.clock.advance(15 * day)

        // every other task is due again after 15 days as well; the earnings task must have been among them
        job.runOnce()

        assertEquals(CreatorEarningState.AVAILABLE, state(orderId, codeId))
        assertEquals(HousekeepingJob.HOUR_MS, HousekeepingJob.Task.EARNINGS.everyMs)
    }

    @Test
    fun `a job without the seam still runs, and the composition root passes the creator service`() {
        runBlocking { assertEquals(0, HousekeepingJob(w.clock, { MarketTestDb.TABLE_PREFIX }, { pool }, null, null, null, null).run(HousekeepingJob.Task.EARNINGS)) }

        val source = Files.readString(Path.of("src/main/kotlin/com/panomc/plugins/market/job/HousekeepingJob.kt"))

        assertTrue(source.contains("creatorEarnings = { sqlClient -> creatorService(plugin).releaseDue(sqlClient) }"))
    }
}
