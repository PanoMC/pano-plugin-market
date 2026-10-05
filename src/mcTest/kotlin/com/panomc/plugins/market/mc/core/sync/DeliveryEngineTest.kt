package com.panomc.plugins.market.mc.core.sync

import com.panomc.plugins.market.mc.core.platform.DispatchResult
import com.panomc.plugins.market.mc.core.platform.PermissionOutcome
import com.panomc.plugins.market.mc.core.store.FileAppendSink
import com.panomc.plugins.market.mc.core.store.StoreOptions
import com.panomc.plugins.market.mc.core.support.EngineHarness
import com.panomc.plugins.market.mc.core.support.FlakySink
import com.panomc.plugins.market.mc.core.support.delivery
import com.panomc.plugins.market.mc.core.support.permissionDelivery
import com.panomc.plugins.market.mc.core.support.response
import com.panomc.plugins.market.mc.core.wire.SyncBroadcast
import com.panomc.plugins.market.mc.core.wire.SyncDelivery
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/** MC-U1 to MC-U5, MC-U8, MC-U9 (19 section 13) plus the rules of 19 section 6 they rest on. */
class DeliveryEngineTest {
    @TempDir
    lateinit var dir: Path

    @TempDir
    lateinit var scratch: Path

    private fun harness(
        storeOptions: StoreOptions = StoreOptions(),
        engineOptions: EngineOptions = EngineOptions(),
        at: Path = dir
    ) = EngineHarness(at, storeOptions = storeOptions, engineOptions = engineOptions)

    private val day = 24L * 60 * 60 * 1000

    // ======== MC-U1: executed once; the same key offered again; the result is re-reported until acked =============

    @Test
    fun `u1 a delivery is executed once and its result is reported until acknowledged`() {
        val h = harness()
        h.platform.join("Steve")
        val first = h.sync(response(deliveries = listOf(delivery("k1", 1, commands = listOf("give Steve diamond 3")))))
        assertTrue(first.results.isEmpty(), "nothing to report in the request that fetched it")
        assertEquals(listOf("give Steve diamond 3"), h.platform.console)

        // Reported in every request until acked.
        repeat(3) {
            val results = h.request().results
            assertEquals(1, results.size)
            assertEquals("DONE", results[0].status)
            assertEquals("k1", results[0].key)
            assertEquals(listOf(true), results[0].commands.map { it.ok })
            assertNotNull(results[0].executedAt)
        }
        h.sync(response(acked = listOf("k1")))
        assertTrue(h.request().results.isEmpty(), "acknowledged: no longer reported")
        assertEquals(1, h.platform.console.size)
    }

    @Test
    fun `u1 the same key offered again never runs again and its stored result is reported again`() {
        val h = harness()
        h.platform.join("Steve")
        val d = delivery("k1", 1)
        h.sync(response(deliveries = listOf(d, d)))
        assertEquals(1, h.platform.console.size, "twice in one response runs once")

        h.sync(response(deliveries = listOf(d)))
        assertEquals(1, h.platform.console.size, "offered again before the ack")
        assertEquals("DONE", h.result("k1")!!.status)

        h.sync(response(acked = listOf("k1")))
        assertTrue(h.request().results.isEmpty())
        h.sync(response(deliveries = listOf(d)))
        assertEquals(1, h.platform.console.size, "offered again after the ack: Pano did not take the result, so it is reported again")
        assertEquals("DONE", h.result("k1")!!.status)
    }

    @Test
    fun `u1 a key stays known across a crash and across a clean restart`() {
        val h = harness()
        h.platform.join("Steve")
        val d = delivery("k1", 1)
        h.sync(response(deliveries = listOf(d)))
        h.crash()
        h.sync(response(deliveries = listOf(d)))
        h.restart()
        h.sync(response(deliveries = listOf(d)))
        assertEquals(1, h.platform.console.size)
        assertEquals("DONE", h.result("k1")!!.status, "still reported after two restarts")
    }

    @Test
    fun `u1 an acknowledgement only counts for a result the request carried`() {
        val h = harness()
        h.platform.join("Steve")
        // k1 runs while this response is applied, so the request that fetched it did not carry it: the ack is not valid.
        h.sync(response(deliveries = listOf(delivery("k1", 1)), acked = listOf("k1")))
        assertEquals("DONE", h.result("k1")!!.status, "a server cannot make the component forget a result it never received")
        h.sync(response(acked = listOf("nobody")))
        assertEquals(1, h.request().results.size)
    }

    @Test
    fun `u1 the acknowledgement of a queued report does not swallow the final result`() {
        val h = harness()
        h.sync(response(deliveries = listOf(delivery("k1", 1, requiresOnline = true))))
        val prepared = h.engine.buildSyncRequest()
        assertEquals("QUEUED", prepared.request.results.single().status)
        // The player joins while the request is on the wire; the delivery runs; then Pano's ack of the QUEUED report arrives.
        h.platform.join("Steve")
        h.engine.onPlayerPresent("Steve")
        h.engine.applyResponse(prepared, response(acked = listOf("k1")))
        assertEquals("DONE", h.result("k1")!!.status, "the DONE result is still to be reported")
        h.sync(response(acked = listOf("k1")))
        assertTrue(h.request().results.isEmpty())
    }

    // ======== MC-U2: queue until present, id order, EXPIRED =======================================================

    @Test
    fun `u2 requires online and absent is queued and reported queued once, then runs on join in id order`() {
        val h = harness()
        h.sync(
            response(
                deliveries = listOf(
                    delivery("k2", 12, commands = listOf("say second"), requiresOnline = true),
                    delivery("k1", 11, commands = listOf("say first"), requiresOnline = true)
                )
            )
        )
        assertTrue(h.platform.console.isEmpty())
        var req = h.request()
        assertEquals(2, req.queued)
        assertEquals(listOf("k1" to "QUEUED", "k2" to "QUEUED"), req.results.map { it.key to it.status }.sortedBy { it.first })

        h.sync(response(acked = listOf("k1", "k2")))
        req = h.request()
        assertTrue(req.results.isEmpty(), "a queued report acknowledged by Pano is not repeated")
        assertEquals(2, req.queued)

        h.platform.join("Steve")
        val ran = h.engine.onPlayerPresent("Steve")
        assertEquals(listOf("say first", "say second"), h.platform.console, "ascending delivery id")
        assertEquals(listOf("k1", "k2"), ran.map { it.key })
        assertEquals(listOf("k1", "k2"), h.callbacks.drains.single().second.map { it.key })
        req = h.request()
        assertEquals(0, req.queued)
        assertEquals(listOf("DONE", "DONE"), req.results.sortedBy { it.key }.map { it.status })
    }

    @Test
    fun `u2 a queued delivery is expired after expiresAt and never runs`() {
        val h = harness()
        h.sync(response(deliveries = listOf(delivery("k1", 1, requiresOnline = true, expiresAt = h.clock.now() + 60_000))))
        h.clock.advance(61_000)
        h.platform.join("Steve")
        val ran = h.engine.onPlayerPresent("Steve")
        assertTrue(h.platform.console.isEmpty())
        assertEquals("EXPIRED", ran.single().state.name)
        assertEquals("EXPIRED", h.result("k1")!!.status)
    }

    @Test
    fun `u2 expiry also happens while the player stays away`() {
        val h = harness()
        h.sync(response(deliveries = listOf(delivery("k1", 1, requiresOnline = true, expiresAt = h.clock.now() + 60_000))))
        assertEquals(0, h.engine.expireDue())
        val before = h.callbacks.localChanges
        h.clock.advance(60_000)
        assertEquals(1, h.engine.expireDue())
        assertEquals("EXPIRED", h.result("k1")!!.status)
        assertEquals(before + 1, h.callbacks.localChanges, "expiry is a local change: the loop reports early")
        h.platform.join("Steve")
        h.engine.onPlayerPresent("Steve")
        assertTrue(h.platform.console.isEmpty())
    }

    @Test
    fun `u2 an offered delivery that is already past its expiry is expired at once`() {
        val h = harness()
        h.platform.join("Steve")
        h.sync(response(deliveries = listOf(delivery("k1", 1, expiresAt = h.clock.now() - 1))))
        assertTrue(h.platform.console.isEmpty())
        assertEquals("EXPIRED", h.result("k1")!!.status)
    }

    @Test
    fun `u2 a delivery that does not require online runs at once while an earlier requires-online one of the player stays queued`() {
        val h = harness()
        h.sync(
            response(
                deliveries = listOf(
                    delivery("a", 5, commands = listOf("say five"), requiresOnline = true),
                    delivery("b", 6, commands = listOf("lp user Steve parent remove vip"), requiresOnline = false, phase = "REVOKE")
                )
            )
        )
        assertEquals(listOf("lp user Steve parent remove vip"), h.platform.console, "the undo of an absent player runs at once (19 section 6.2 step 4)")
        var req = h.request()
        assertEquals(1, req.queued, "only the requires-online delivery is queued")
        assertEquals(mapOf("a" to "QUEUED", "b" to "DONE"), req.results.associate { it.key to it.status })

        // A later delivery in a later response is not held either.
        h.sync(response(deliveries = listOf(delivery("c", 7, commands = listOf("say seven")))))
        assertEquals(listOf("lp user Steve parent remove vip", "say seven"), h.platform.console)
        assertEquals(1, h.request().queued)

        // The player joins: the one waiting delivery runs, nothing else is left.
        h.platform.join("Steve")
        h.engine.onPlayerPresent("Steve")
        assertEquals(listOf("lp user Steve parent remove vip", "say seven", "say five"), h.platform.console)
        assertEquals(0, h.request().queued)
    }

    @Test
    fun `u2 after the queued blocker is cancelled nothing of the player is left waiting`() {
        val h = harness()
        h.sync(
            response(
                deliveries = listOf(
                    delivery("a", 5, commands = listOf("say five"), requiresOnline = true),
                    delivery("b", 6, commands = listOf("say six"), requiresOnline = false, phase = "REVOKE")
                )
            )
        )
        assertEquals(1, h.request().queued)
        h.sync(response(cancel = listOf("a")))
        assertEquals("CANCELLED", h.result("a")!!.status)
        assertEquals(0, h.request().queued)
        assertTrue(h.store.waitingFor("Steve").isEmpty(), "nothing of the player waits any more")
        h.platform.join("Steve")
        h.engine.onPlayerPresent("Steve")
        assertEquals(listOf("say six"), h.platform.console, "the cancelled one never runs, the undo ran exactly once")
    }

    @Test
    fun `u2 after the queued blocker has expired nothing of the player is left waiting`() {
        val h = harness()
        h.sync(
            response(
                deliveries = listOf(
                    delivery("a", 5, commands = listOf("say five"), requiresOnline = true, expiresAt = h.clock.now() + 60_000),
                    delivery("b", 6, commands = listOf("say six"), requiresOnline = false, phase = "EXPIRE")
                )
            )
        )
        assertEquals(listOf("say six"), h.platform.console)
        assertEquals(1, h.request().queued)
        h.clock.advance(60_000)
        assertEquals(1, h.engine.expireDue())
        assertEquals("EXPIRED", h.result("a")!!.status)
        assertEquals(0, h.request().queued)
        assertTrue(h.store.waitingFor("Steve").isEmpty())
        h.platform.join("Steve")
        h.engine.onPlayerPresent("Steve")
        assertEquals(listOf("say six"), h.platform.console)
    }

    @Test
    fun `u2 a present players waiting queue runs before a newly offered delivery of that player`() {
        val h = harness()
        h.sync(response(deliveries = listOf(delivery("a", 5, commands = listOf("say five"), requiresOnline = true))))
        assertEquals(1, h.request().queued)
        h.platform.join("Steve") // the join event has not reached the engine yet
        h.sync(response(deliveries = listOf(delivery("b", 6, commands = listOf("say six")))))
        assertEquals(listOf("say five", "say six"), h.platform.console, "id order for a present player")
        assertEquals(0, h.request().queued)
        assertEquals("a", h.callbacks.drains.single().second.single().key)
    }

    @Test
    fun `u2 other players are not held back by a queue`() {
        val h = harness()
        h.platform.join("Alex")
        h.sync(
            response(
                deliveries = listOf(
                    delivery("a", 5, player = "Steve", requiresOnline = true),
                    delivery("b", 6, player = "Alex", commands = listOf("say alex"))
                )
            )
        )
        assertEquals(listOf("say alex"), h.platform.console)
    }

    @Test
    fun `u2 deliveries of one response run in ascending id and a present player runs at once`() {
        val h = harness()
        h.platform.join("Steve")
        h.sync(
            response(
                deliveries = listOf(
                    delivery("n9", 9, commands = listOf("say nine")),
                    delivery("n3", 3, commands = listOf("say three")),
                    delivery("n5", 5, commands = listOf("say five"))
                )
            )
        )
        assertEquals(listOf("say three", "say five", "say nine"), h.platform.console)
    }

    @Test
    fun `u2 usernames are compared case-insensitively`() {
        val h = harness()
        h.platform.join("steve")
        h.sync(response(deliveries = listOf(delivery("k1", 1, player = "STEVE", requiresOnline = true))))
        assertEquals(1, h.platform.console.size)
        val h2 = harness(at = dir.resolve("two"))
        h2.sync(response(deliveries = listOf(delivery("k1", 1, player = "STEVE", requiresOnline = true))))
        h2.platform.join("Steve")
        h2.engine.onPlayerPresent("sTeVe")
        assertEquals(1, h2.platform.console.size)
    }

    // ======== MC-U2 (review fixes): the join is verified and never the only way to release a record ===============

    @Test
    fun `u2 a join event of a player who is not present runs nothing and the record stays queued`() {
        val h = harness()
        h.sync(response(deliveries = listOf(delivery("k1", 1, commands = listOf("say hello"), requiresOnline = true))))
        assertTrue(h.engine.onPlayerPresent("Steve").isEmpty(), "the player left (or never was authenticated) by the time the event arrived")
        assertTrue(h.platform.console.isEmpty(), "a command for an absent player would do nothing and still be reported DONE")
        assertEquals(1, h.request().queued)
        assertEquals("QUEUED", h.result("k1")!!.status)
        assertTrue(h.callbacks.drains.isEmpty())

        h.platform.join("Steve")
        assertEquals(listOf("k1"), h.engine.onPlayerPresent("Steve").map { it.key })
        assertEquals(listOf("say hello"), h.platform.console)
        assertEquals("DONE", h.result("k1")!!.status)
    }

    @Test
    fun `u2 a player who leaves while the queue runs leaves the remaining deliveries queued`() {
        val h = harness()
        h.sync(
            response(
                deliveries = listOf(
                    delivery("a", 1, commands = listOf("say a"), requiresOnline = true),
                    delivery("b", 2, commands = listOf("say b"), requiresOnline = true)
                )
            )
        )
        h.platform.join("Steve")
        h.platform.dispatcher = {
            h.platform.leave("Steve") // lobby auto-transfer, kick, quit: the player is gone after the first command
            DispatchResult.OK
        }
        val ran = h.engine.onPlayerPresent("Steve")
        assertEquals(listOf("a"), ran.map { it.key })
        assertEquals(listOf("say a"), h.platform.console)
        assertEquals("DONE", h.result("a")!!.status)
        assertEquals("QUEUED", h.result("b")!!.status)
        assertEquals(1, h.request().queued)
        assertEquals(listOf("a"), h.callbacks.drains.single().second.map { it.key })

        h.platform.dispatcher = { DispatchResult.OK }
        h.platform.join("Steve")
        assertEquals(listOf("b"), h.engine.onPlayerPresent("Steve").map { it.key })
        assertEquals(listOf("say a", "say b"), h.platform.console)
        assertEquals(0, h.request().queued)
    }

    @Test
    fun `u2 a platform that cannot tell whether the player is online is treated as not online`() {
        val h = harness()
        h.platform.join("Steve")
        h.platform.presenceError = IllegalStateException("auth plugin not ready")
        h.sync(response(deliveries = listOf(delivery("k1", 1, requiresOnline = true))))
        assertTrue(h.platform.console.isEmpty(), "nothing is executed on a guess")
        assertEquals(1, h.request().queued)
        assertTrue(h.log.has("could not tell whether Steve is online"))

        h.platform.presenceError = null
        h.engine.onPlayerPresent("Steve")
        assertEquals(1, h.platform.console.size)
    }

    @Test
    fun `u2 the tick runs the queue of a present player whose join event was missed`() {
        val h = harness()
        h.sync(
            response(
                deliveries = listOf(
                    delivery("s", 1, player = "Steve", commands = listOf("say steve"), requiresOnline = true),
                    delivery("x", 2, player = "Alex", commands = listOf("say alex"), requiresOnline = true)
                )
            )
        )
        assertEquals(2, h.request().queued)
        h.platform.join("Steve") // no onPlayerPresent: the event was lost
        val before = h.callbacks.localChanges
        assertEquals(0, h.engine.expireDue(), "nothing expired")
        assertEquals(listOf("say steve"), h.platform.console, "only the player who is present")
        assertEquals(1, h.request().queued)
        assertEquals("DONE", h.result("s")!!.status)
        assertEquals("QUEUED", h.result("x")!!.status)
        assertEquals(listOf("Steve"), h.callbacks.drains.map { it.first })
        assertTrue(h.callbacks.localChanges > before, "the loop reports the result early")

        assertEquals(0, h.engine.expireDue())
        assertEquals(1, h.platform.console.size, "a second tick runs nothing again")
    }

    @Test
    fun `u2 a join that meets a failing journal write is made up by the next tick once the journal works`() {
        val (h, flaky) = flakyHarness()
        h.sync(response(deliveries = listOf(delivery("k1", 1, commands = listOf("say hello"), requiresOnline = true))))
        assertEquals(1, h.request().queued)
        h.platform.join("Steve")
        flaky().failNextAppend = true
        assertTrue(h.engine.onPlayerPresent("Steve").isEmpty())
        assertTrue(h.platform.console.isEmpty(), "no durable 'started' entry, no dispatch")
        assertFalse(h.engine.status().storeHealthy)
        assertEquals(1, h.store.waitingCount(), "it stays queued")

        assertEquals(0, h.engine.expireDue())
        assertEquals(listOf("say hello"), h.platform.console, "the tick probed the journal and ran the queue of the present player")
        assertTrue(h.engine.status().storeHealthy)
        assertEquals("DONE", h.result("k1")!!.status)
        assertEquals(0, h.request().queued)
    }

    @Test
    fun `u2 a join that arrives while the journal is already failing is not lost`() {
        val (h, flaky) = flakyHarness()
        h.sync(response(deliveries = listOf(delivery("k1", 1, commands = listOf("say hello"), requiresOnline = true))))
        flaky().failAppends = true
        h.sync(response(deliveries = listOf(delivery("k2", 2, player = "Alex")))) // this write fails and the store turns unhealthy
        assertFalse(h.engine.status().storeHealthy)
        h.platform.join("Steve")
        assertTrue(h.engine.onPlayerPresent("Steve").isEmpty(), "dropped: the journal is not writable")
        assertEquals(0, h.engine.expireDue(), "still failing: the probe fails and nothing runs")
        assertTrue(h.platform.console.isEmpty())

        flaky().failAppends = false
        assertEquals(0, h.engine.expireDue())
        assertEquals(listOf("say hello"), h.platform.console, "k1 ran once the journal worked, without a second join")
        assertEquals("DONE", h.result("k1")!!.status)
        assertNull(h.store.get("k2"), "the delivery whose write failed was never stored and is offered again by Pano")
    }

    // ======== MC-U3: cancel =======================================================================================

    @Test
    fun `u3 cancel of a queued key cancels it and it never runs`() {
        val h = harness()
        h.sync(response(deliveries = listOf(delivery("k1", 1, requiresOnline = true))))
        val before = h.callbacks.localChanges
        h.sync(response(cancel = listOf("k1")))
        assertEquals("CANCELLED", h.result("k1")!!.status)
        assertTrue(h.callbacks.localChanges > before)
        h.sync(response(acked = listOf("k1")))
        assertTrue(h.request().results.isEmpty())
        h.platform.join("Steve")
        h.engine.onPlayerPresent("Steve")
        h.sync(response(deliveries = listOf(delivery("k1", 1, requiresOnline = true))))
        assertTrue(h.platform.console.isEmpty())
        assertEquals(0, h.request().queued)
    }

    @Test
    fun `u3 cancel of a finished key reports the stored result`() {
        val h = harness()
        h.platform.join("Steve")
        h.sync(response(deliveries = listOf(delivery("k1", 1))))
        h.sync(response(cancel = listOf("k1")))
        val r = h.result("k1")!!
        assertEquals("DONE", r.status, "it already ran: the stored result, not CANCELLED")

        h.sync(response(acked = listOf("k1")))
        assertTrue(h.request().results.isEmpty())
        h.sync(response(cancel = listOf("k1")))
        assertEquals("DONE", h.result("k1")!!.status, "even after the ack: Pano asked again, so it is reported again")
        assertEquals(1, h.platform.console.size)
    }

    @Test
    fun `u3 cancel of an unknown key is answered UNKNOWN until acknowledged`() {
        val h = harness()
        h.sync(response(cancel = listOf("ghost")))
        val r = h.result("ghost")!!
        assertEquals("UNKNOWN", r.status)
        h.sync(response(acked = listOf("ghost")))
        assertTrue(h.request().results.isEmpty())
    }

    // ======== MC-U4: crash after started; journal replay; 30-day purge ============================================

    private fun copyDir(from: Path, to: Path) {
        Files.createDirectories(to)
        Files.walk(from).use { stream ->
            stream.forEach { p ->
                val target = to.resolve(from.relativize(p).toString())
                if (Files.isDirectory(p)) Files.createDirectories(target) else Files.copy(p, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    @Test
    fun `u4 a crash after started fails the delivery as interrupted and it is not run again`() {
        val h = harness()
        h.platform.join("Steve")
        val crashImage = scratch.resolve("crash-image-1")
        h.platform.dispatcher = {
            // The files at the moment of the first dispatch: "started" is written, "finished" is not.
            if (!Files.exists(crashImage)) copyDir(dir, crashImage)
            DispatchResult.OK
        }
        h.sync(response(deliveries = listOf(delivery("k1", 1, commands = listOf("give Steve diamond 1", "give Steve diamond 2")))))
        assertEquals(2, h.platform.console.size)

        val restarted = EngineHarness(crashImage, clock = h.clock)
        restarted.platform.join("Steve")
        val result = restarted.result("k1")!!
        assertEquals("FAILED", result.status)
        assertEquals("INTERRUPTED", result.code)
        restarted.sync(response(deliveries = listOf(delivery("k1", 1, commands = listOf("give Steve diamond 1", "give Steve diamond 2")))))
        assertTrue(restarted.platform.console.isEmpty(), "never run again automatically")
        assertEquals("INTERRUPTED", restarted.result("k1")!!.code)
        assertTrue(restarted.log.has("interrupted"))
    }

    @Test
    fun `u4 a crash after started while the player queue is running fails only the running delivery`() {
        val h = harness()
        h.sync(
            response(
                deliveries = listOf(
                    delivery("a", 1, commands = listOf("say a"), requiresOnline = true),
                    delivery("b", 2, commands = listOf("say b"), requiresOnline = true)
                )
            )
        )
        val crashImage = scratch.resolve("crash-image-2")
        h.platform.dispatcher = { if (!Files.exists(crashImage)) copyDir(dir, crashImage); DispatchResult.OK }
        h.platform.join("Steve")
        h.engine.onPlayerPresent("Steve") // first dispatch copies the image: a started, b still waiting

        val restarted = EngineHarness(crashImage, clock = h.clock)
        assertEquals("INTERRUPTED", restarted.result("a")!!.code)
        assertEquals("QUEUED", restarted.result("b")!!.status)
        restarted.platform.join("Steve")
        restarted.engine.onPlayerPresent("Steve")
        assertEquals(listOf("say b"), restarted.platform.console)
    }

    @Test
    fun `u4 snapshot plus journal replay equals the state, with compaction during the run`() {
        val h = harness(storeOptions = StoreOptions(maxJournalBytes = 2_000))
        h.platform.join("Steve")
        for (i in 1..40) {
            val away = i % 4 == 0
            h.sync(
                response(
                    deliveries = listOf(delivery("k$i", i.toLong(), player = if (away) "Alex" else "Steve", requiresOnline = away)),
                    acked = if (i > 2) listOf("k${i - 2}") else emptyList()
                )
            )
            if (i == 10) h.sync(response(cancel = listOf("k8", "ghost")))
        }
        assertTrue(Files.exists(dir.resolve("state/snapshot.json")), "the journal was compacted during the run")
        val live = h.store.dump()
        h.crash()
        assertEquals(live, h.store.dump(), "snapshot + journal tail")
        h.restart()
        assertEquals(live, h.store.dump(), "snapshot after a clean shutdown")
        h.platform.join("Alex")
        h.engine.onPlayerPresent("Alex")
        assertTrue(h.platform.console.any { it.contains("Alex") }, "the queue survived the restarts")
    }

    @Test
    fun `u4 the thirty day purge keeps unacknowledged records`() {
        val h = harness()
        h.platform.join("Steve")
        h.sync(response(deliveries = listOf(delivery("acked", 1), delivery("unacked", 2))))
        h.sync(response(acked = listOf("acked")))
        h.sync(response(deliveries = listOf(delivery("waiting", 3, player = "Alex", requiresOnline = true))))

        h.clock.advance(29 * day)
        assertEquals(0, h.engine.purge())
        h.clock.advance(2 * day)
        assertEquals(1, h.engine.purge(), "only the acknowledged record is older than 30 days after ackedAt")
        h.clock.advance(400 * day)
        assertEquals(0, h.engine.purge())
        assertEquals(setOf("unacked", "waiting"), h.store.all().map { it.key }.toSet())
        assertEquals("DONE", h.result("unacked")!!.status)
        h.crash()
        assertEquals(setOf("unacked", "waiting"), h.store.all().map { it.key }.toSet(), "the purge was journaled")
    }

    // ======== MC-U5: corrupt store => capacity 0 until recover ====================================================

    private fun corruptJournalOf(base: Path) {
        val journal = base.resolve("state/journal.log")
        val lines = String(Files.readAllBytes(journal)).split('\n').toMutableList()
        lines[1] = "this is not json"
        Files.write(journal, lines.joinToString("\n").toByteArray())
    }

    @Test
    fun `u5 a corrupt store is quarantined, capacity is 0 and nothing runs until recover is confirmed`() {
        val first = harness()
        first.platform.join("Steve")
        first.sync(response(deliveries = listOf(delivery("old1", 1), delivery("old2", 2))))
        assertEquals(2, first.platform.console.size)
        corruptJournalOf(dir)

        val h = harness()
        assertTrue(Files.exists(dir.resolve("recovery.pending")))
        assertTrue(Files.list(dir).use { s -> s.anyMatch { it.fileName.toString().startsWith("state.corrupt-") } })
        assertEquals(0, h.request().capacity)
        assertTrue(h.engine.status().recoveryMode)

        // Pano offers the lost keys anyway (a server that ignores capacity 0): they are not executed.
        h.platform.join("Steve")
        h.sync(response(deliveries = listOf(delivery("old1", 1), delivery("old2", 2)), pollAfterMs = 0))
        assertTrue(h.platform.console.isEmpty())
        assertEquals(0, h.store.size)
        assertTrue(h.log.has("recover"))

        val preview = h.engine.recoveryPreview()!!
        assertTrue(preview.panoHasWaiting)
        assertNotNull(preview.quarantine)
        assertTrue(preview.salvagedUnackedKeys >= 1)

        // A pollAfterMs of 0 ("more waiting") must not become a busy loop while recovering.
        val (_, outcome) = h.syncOutcome(response(pollAfterMs = 0))
        assertEquals(5000L, outcome.pollAfterMs)

        // The operator confirms.
        assertTrue(h.engine.confirmRecovery())
        assertNull(h.engine.recoveryPreview())
        assertEquals(20, h.request().capacity)
        h.sync(response(deliveries = listOf(delivery("old1", 1))))
        assertEquals(1, h.platform.console.size, "re-offered keys are executed as new ones after the confirmation")
        assertFalse(h.engine.confirmRecovery())
    }

    @Test
    fun `u5 recovery mode survives a restart`() {
        val first = harness()
        first.platform.join("Steve")
        first.sync(response(deliveries = listOf(delivery("old1", 1))))
        first.store.close()
        Files.write(dir.resolve("state/snapshot.json"), "{broken".toByteArray())
        val h = harness()
        h.store.close()
        val again = harness()
        assertEquals(0, again.request().capacity, "the fresh empty store must not look like a healthy first start")
        assertTrue(again.engine.confirmRecovery())
        assertEquals(20, again.request().capacity)
    }

    @Test
    fun `u5 a first start is not recovery mode`() {
        val h = harness(at = dir.resolve("fresh"))
        assertEquals(20, h.request().capacity)
        assertFalse(h.engine.status().recoveryMode)
        assertNull(h.engine.recoveryPreview())
    }

    // ======== MC-U8: a throwing dispatcher ========================================================================

    @Test
    fun `u8 a throwing command is recorded and the next command still runs`() {
        val h = harness()
        h.platform.join("Steve")
        h.platform.dispatcher = { c -> if (c == "boom") throw IllegalStateException("explode") else DispatchResult.OK }
        h.sync(response(deliveries = listOf(delivery("k1", 1, commands = listOf("boom", "say after")))))
        assertEquals(listOf("boom", "say after"), h.platform.console)
        val r = h.result("k1")!!
        assertEquals("FAILED", r.status)
        assertEquals("COMMAND_ERROR", r.code)
        assertEquals(listOf(false, true), r.commands.map { it.ok })
        assertEquals("explode", r.commands[0].error)
        assertNull(r.commands[1].error)
        assertEquals(listOf(0, 1), r.commands.map { it.index })
        assertEquals("1 of 2 commands failed", r.message)
    }

    @Test
    fun `u8 an unknown command reported by the platform is a command error too`() {
        val h = harness()
        h.platform.join("Steve")
        h.platform.dispatcher = { c -> if (c.startsWith("nope")) DispatchResult(false, "Unknown command") else DispatchResult.OK }
        h.sync(response(deliveries = listOf(delivery("k1", 1, commands = listOf("say ok", "nope")))))
        val r = h.result("k1")!!
        assertEquals("COMMAND_ERROR", r.code)
        assertEquals(listOf(true, false), r.commands.map { it.ok })
        assertEquals("Unknown command", r.commands[1].error)
    }

    @Test
    fun `u8 an error text from the platform is cut to a sane length`() {
        val h = harness()
        h.platform.join("Steve")
        h.platform.dispatcher = { DispatchResult(false, "x".repeat(5000)) }
        h.sync(response(deliveries = listOf(delivery("k1", 1))))
        assertEquals(200, h.result("k1")!!.commands[0].error!!.length)
    }

    // ======== MC-U9: deliveries off ================================================================================

    @Test
    fun `u9 with deliveries off every delivery is failed as disabled locally and never dropped silently`() {
        val h = harness()
        h.settings.deliveriesEnabled = false
        h.platform.join("Steve")
        h.sync(
            response(
                deliveries = listOf(
                    delivery("k1", 1),
                    delivery("k2", 2, requiresOnline = true),
                    permissionDelivery("k3", 3)
                )
            )
        )
        assertTrue(h.platform.console.isEmpty())
        assertTrue(h.platform.permissionCalls.isEmpty())
        val results = h.request().results.sortedBy { it.key }
        assertEquals(listOf("k1", "k2", "k3"), results.map { it.key })
        results.forEach {
            assertEquals("FAILED", it.status)
            assertEquals("DISABLED_LOCALLY", it.code)
        }
        assertEquals(20, h.request().capacity, "Pano must keep offering so that it learns the code")
    }

    @Test
    fun `u9 a delivery queued before the switch was turned off is failed at join`() {
        val h = harness()
        h.sync(response(deliveries = listOf(delivery("k1", 1, requiresOnline = true))))
        h.settings.deliveriesEnabled = false
        h.platform.join("Steve")
        h.engine.onPlayerPresent("Steve")
        assertTrue(h.platform.console.isEmpty())
        assertEquals("DISABLED_LOCALLY", h.result("k1")!!.code)
    }

    // ======== PERMISSION deliveries ===============================================================================

    @Test
    fun `permission deliveries go through the LuckPerms executor`() {
        val h = harness()
        h.platform.join("Steve", "11111111-2222-3333-4444-555555555555")
        h.sync(
            response(
                deliveries = listOf(
                    permissionDelivery("add", 1, nodes = listOf("group.vip", "essentials.fly"), expiresAt = 1_800_000_000_000L),
                    permissionDelivery("rem", 2, op = "REMOVE", nodes = listOf("group.vip"))
                )
            )
        )
        assertEquals(2, h.platform.permissionCalls.size)
        val add = h.platform.permissionCalls[0]
        assertEquals("ADD", add.op)
        assertEquals(listOf("group.vip", "essentials.fly"), add.nodes)
        assertEquals(1_800_000_000_000L, add.expiresAt)
        assertEquals("11111111-2222-3333-4444-555555555555", add.uuidHint)
        assertEquals("REMOVE", h.platform.permissionCalls[1].op)
        assertNull(h.platform.permissionCalls[1].expiresAt)
        assertEquals(listOf("DONE", "DONE"), h.request().results.map { it.status })
        assertTrue(h.platform.console.isEmpty())
    }

    @Test
    fun `permission delivery without LuckPerms or with the switch off fails as LUCKPERMS_MISSING`() {
        val h = harness()
        h.platform.join("Steve")
        h.platform.luckPerms = false
        h.sync(response(deliveries = listOf(permissionDelivery("a", 1))))
        assertEquals("LUCKPERMS_MISSING", h.result("a")!!.code)
        h.platform.luckPerms = true
        h.settings.luckPermsEnabled = false
        h.sync(response(deliveries = listOf(permissionDelivery("b", 2))))
        assertEquals("LUCKPERMS_MISSING", h.result("b")!!.code)
        assertTrue(h.platform.permissionCalls.isEmpty())
    }

    @Test
    fun `a failing LuckPerms call is a failed result and does not throw out of the engine`() {
        val h = harness()
        h.platform.join("Steve")
        h.platform.permissionHandler = { PermissionOutcome(false, "group does not exist") }
        h.sync(response(deliveries = listOf(permissionDelivery("a", 1))))
        assertEquals("PERMISSION_ERROR", h.result("a")!!.code)
        assertEquals("group does not exist", h.result("a")!!.message)
        h.platform.permissionHandler = { throw IllegalStateException("storage offline") }
        h.sync(response(deliveries = listOf(permissionDelivery("b", 2))))
        assertEquals("PERMISSION_ERROR", h.result("b")!!.code)
        assertEquals("storage offline", h.result("b")!!.message)
    }

    // ======== {uuid} ==============================================================================================

    @Test
    fun `uuid is the present players uuid, else Panos hint, else the platforms offline uuid`() {
        val h = harness()
        h.platform.join("Alex", "aaaaaaaa-0000-0000-0000-000000000001")
        h.sync(response(deliveries = listOf(delivery("k1", 1, player = "Alex", commands = listOf("lp user {uuid} info", "say {uuid} {uuid}"), uuid = "hint-ignored"))))
        assertEquals(listOf("lp user aaaaaaaa-0000-0000-0000-000000000001 info", "say aaaaaaaa-0000-0000-0000-000000000001 aaaaaaaa-0000-0000-0000-000000000001"), h.platform.console)

        h.platform.console.clear()
        h.sync(response(deliveries = listOf(delivery("k2", 2, player = "Bob", commands = listOf("lp user {uuid} info"), uuid = "bbbbbbbb-0000-0000-0000-000000000002"))))
        assertEquals(listOf("lp user bbbbbbbb-0000-0000-0000-000000000002 info"), h.platform.console)

        h.platform.console.clear()
        h.platform.offline = { "offline-$it" }
        h.sync(response(deliveries = listOf(delivery("k3", 3, player = "Carl", commands = listOf("lp user {uuid} info")))))
        assertEquals(listOf("lp user offline-Carl info"), h.platform.console)
    }

    @Test
    fun `commands without uuid never ask the platform for one`() {
        val h = harness()
        h.platform.join("Steve")
        h.platform.offline = { error("must not be called") }
        h.sync(response(deliveries = listOf(delivery("k1", 1))))
        assertEquals("DONE", h.result("k1")!!.status)
    }

    // ======== malformed deliveries ================================================================================

    @Test
    fun `malformed deliveries are failed as INVALID_PAYLOAD without dispatching anything`() {
        val h = harness()
        h.platform.join("Steve")
        val base = delivery("x", 1)
        h.sync(
            response(
                deliveries = listOf(
                    delivery("blank-player", 1, player = " "),
                    base.copy(key = "no-commands", id = 2, commands = emptyList()),
                    base.copy(key = "newline", id = 3, commands = listOf("say a\nop Steve")),
                    base.copy(key = "empty-command", id = 4, commands = listOf("say a", " ")),
                    base.copy(key = "kind", id = 5, kind = "SHELL"),
                    base.copy(key = "perm-none", id = 6, kind = "PERMISSION", permission = null, commands = emptyList()),
                    permissionDelivery("perm-op", 7).let { it.copy(permission = it.permission!!.copy(op = "SET")) },
                    permissionDelivery("perm-nodes", 8, nodes = emptyList()),
                    base.copy(key = "  ", id = 9)
                )
            )
        )
        assertTrue(h.platform.console.isEmpty())
        assertTrue(h.platform.permissionCalls.isEmpty())
        val results = h.request().results.associateBy { it.key }
        assertEquals(8, results.size, "the keyless one is ignored, the other eight are answered")
        results.values.forEach {
            assertEquals("FAILED", it.status, it.key)
            assertEquals("INVALID_PAYLOAD", it.code, it.key)
            assertNotNull(it.message, it.key)
        }
    }

    @Test
    fun `a delivery with null fields from a sloppy decoder does not break the engine`() {
        val h = harness()
        h.platform.join("Steve")
        // Gson sets fields reflectively and can leave a Kotlin non-null field null.
        val sloppy = delivery("sloppy", 1).let { d ->
            val copy = d.copy()
            SyncDelivery::class.java.getDeclaredField("commands").apply { isAccessible = true }.set(copy, null)
            SyncDelivery::class.java.getDeclaredField("phase").apply { isAccessible = true }.set(copy, null)
            copy
        }
        h.sync(response(deliveries = listOf(sloppy, delivery("fine", 2))))
        assertEquals("INVALID_PAYLOAD", h.result("sloppy")!!.code)
        assertEquals("DONE", h.result("fine")!!.status)
    }

    @Test
    fun `no more than 100 deliveries of one response are taken`() {
        val h = harness()
        h.platform.join("Steve")
        h.sync(response(deliveries = (1..130).map { delivery("k$it", it.toLong(), commands = listOf("say $it")) }))
        assertEquals(100, h.store.size)
        assertEquals(100, h.platform.console.size)
    }

    // ======== request fields ======================================================================================

    @Test
    fun `the request carries the platform, flags, queue size and capacity`() {
        val h = harness()
        h.platform.luckPerms = true
        h.platform.vault = true
        h.platform.placeholders = false
        h.callbacks.cachedHash = "9f2c"
        val r = h.request()
        assertEquals("1.4.0", r.componentVersion)
        assertEquals(1, r.protocol)
        assertEquals("PAPER", r.platform)
        assertTrue(r.luckPerms)
        assertTrue(r.vault)
        assertFalse(r.placeholderApi)
        assertEquals(0, r.queued)
        assertEquals(20, r.capacity)
        assertEquals("9f2c", r.configHash)
        assertTrue(r.results.isEmpty())
    }

    @Test
    fun `capacity is min of 20 and 100 minus what is in flight`() {
        val h = harness()
        h.sync(response(deliveries = (1..90).map { delivery("k$it", it.toLong(), requiresOnline = true) }))
        var r = h.request()
        assertEquals(90, r.queued)
        assertEquals(10, r.capacity)
        h.sync(response(deliveries = (91..100).map { delivery("k$it", it.toLong(), requiresOnline = true) }))
        r = h.request()
        assertEquals(100, r.queued)
        assertEquals(0, r.capacity)
        h.sync(response(deliveries = listOf(delivery("k101", 101, requiresOnline = true))))
        assertEquals(101, h.request().queued)
        assertEquals(0, h.request().capacity)
    }

    @Test
    fun `at most 100 results are reported, oldest first`() {
        val h = harness()
        h.platform.join("Steve")
        h.sync(response(deliveries = (1..60).map { delivery("k%03d".format(it), it.toLong(), commands = listOf("say $it")) }))
        h.clock.advance(1_000)
        h.sync(response(deliveries = (61..120).map { delivery("k%03d".format(it), it.toLong(), commands = listOf("say $it")) }))
        val keys = h.request().results.map { it.key }
        assertEquals(100, keys.size)
        assertEquals((1..100).map { "k%03d".format(it) }, keys)
        h.sync(response(acked = keys))
        assertEquals((101..120).map { "k%03d".format(it) }, h.request().results.map { it.key })
    }

    // ======== refusals ============================================================================================

    @Test
    fun `a refused response still applies its acknowledgements, runs nothing and logs once per change`() {
        val h = harness()
        h.platform.join("Steve")
        h.sync(response(deliveries = listOf(delivery("k1", 1))))
        val (_, outcome) = h.syncOutcome(
            response(
                accepted = false, reason = "VERSION_MISMATCH", marketVersion = "1.5.0", acked = listOf("k1"),
                deliveries = listOf(delivery("k2", 2)), cancel = listOf("k1"), pollAfterMs = 0
            )
        )
        assertFalse(outcome.accepted)
        assertEquals(30_000L, outcome.pollAfterMs, "refused: poll every 30 s, whatever pollAfterMs says")
        assertEquals(1, h.platform.console.size, "nothing is executed while refused")
        assertEquals(0, h.store.waitingCount())
        assertNull(h.store.get("k2"))
        assertTrue(h.request().results.isEmpty(), "the ack of the refused response was applied")
        assertEquals(false, h.engine.status().accepted)
        assertEquals("VERSION_MISMATCH", h.engine.status().rejectionReason)

        h.sync(response(accepted = false, reason = "VERSION_MISMATCH", marketVersion = "1.5.0"))
        assertEquals(1, h.log.count("Pano runs Market 1.5.0 but this component is 1.4.0"), "logged once")
        h.sync(response(accepted = false, reason = "VERSION_MISMATCH", marketVersion = "1.6.0"))
        assertEquals(2, h.log.count("Pano runs Market"), "a changed refusal is logged again")

        val (_, back) = h.syncOutcome(response())
        assertTrue(back.accepted)
        assertTrue(h.log.has("accepts this Market component again"))
        assertEquals(true, h.engine.status().accepted)
        h.sync(response(deliveries = listOf(delivery("k2", 2))))
        assertEquals(2, h.platform.console.size, "runs again once accepted")
    }

    @Test
    fun `other refusals are remembered and logged`() {
        val h = harness()
        h.sync(response(accepted = false, reason = "PROTOCOL_UNSUPPORTED"))
        assertTrue(h.log.has("protocol 1"))
        h.sync(response(accepted = false, reason = "MARKET_NOT_READY"))
        assertTrue(h.log.has("not ready"))
        assertEquals("MARKET_NOT_READY", h.engine.status().rejectionReason)
    }

    // ======== broadcasts and config hash ==========================================================================

    @Test
    fun `a broadcast is shown once per id and remembers the last 200 ids`() {
        val h = harness()
        h.sync(response(broadcasts = listOf(SyncBroadcast(1, "one"), SyncBroadcast(2, "two"))))
        h.sync(response(broadcasts = listOf(SyncBroadcast(1, "one"), SyncBroadcast(3, "three"))))
        assertEquals(listOf("one", "two", "three"), h.callbacks.broadcasts)
        h.sync(response(broadcasts = (4L..203L).map { SyncBroadcast(it, "b$it") }))
        h.callbacks.broadcasts.clear()
        h.sync(response(broadcasts = listOf(SyncBroadcast(1, "one again"), SyncBroadcast(203, "dup"))))
        assertEquals(listOf("one again"), h.callbacks.broadcasts, "id 1 fell out of the last 200, id 203 is still remembered")
    }

    @Test
    fun `broadcasts are dropped while switched off locally`() {
        val h = harness()
        h.settings.broadcastEnabled = false
        h.sync(response(broadcasts = listOf(SyncBroadcast(1, "one"))))
        assertTrue(h.callbacks.broadcasts.isEmpty())
    }

    @Test
    fun `a different config hash asks for MARKET_CONFIG once per hash`() {
        val h = harness()
        h.callbacks.cachedHash = "aaa"
        h.sync(response(configHash = "aaa"))
        assertTrue(h.callbacks.configHashChanges.isEmpty())
        h.sync(response(configHash = "bbb"))
        h.sync(response(configHash = "bbb"))
        assertEquals(listOf("bbb"), h.callbacks.configHashChanges)
        h.clock.advance(31_000)
        h.sync(response(configHash = "bbb"))
        assertEquals(listOf("bbb", "bbb"), h.callbacks.configHashChanges, "a pull that never completed is asked for again")
        h.callbacks.cachedHash = "bbb"
        h.sync(response(configHash = "bbb"))
        assertEquals(2, h.callbacks.configHashChanges.size)
        h.sync(response(configHash = null))
        assertEquals(2, h.callbacks.configHashChanges.size)
    }

    // ======== journal failures ====================================================================================

    private fun flakyHarness(): Pair<EngineHarness, () -> FlakySink> {
        var flaky: FlakySink? = null
        val h = harness(storeOptions = StoreOptions(sinkFactory = { FlakySink(FileAppendSink(it)).also { s -> flaky = s } }))
        return h to { flaky!! }
    }

    @Test
    fun `a delivery whose journal write fails is not executed and not reported`() {
        val (h, flaky) = flakyHarness()
        h.platform.join("Steve")
        flaky().failNextAppend = true
        h.sync(response(deliveries = listOf(delivery("k1", 1), delivery("k2", 2))))
        assertTrue(h.platform.console.isEmpty(), "no durable 'started' entry, no dispatch")
        assertEquals(0, h.store.size)
        assertTrue(h.request().results.isEmpty())
        assertEquals(0, h.request().capacity, "a failing journal asks Pano to offer nothing")
        assertFalse(h.engine.status().storeHealthy)
        assertTrue(h.log.has("journal"))

        // The disk works again: the next tick probes it, Pano re-offers the keys, they run once.
        h.engine.expireDue()
        assertTrue(h.engine.status().storeHealthy)
        assertEquals(20, h.request().capacity)
        h.sync(response(deliveries = listOf(delivery("k1", 1), delivery("k2", 2))))
        assertEquals(2, h.platform.console.size)
    }

    @Test
    fun `a result whose journal write fails is kept and written before anything else happens`() {
        val (h, flaky) = flakyHarness()
        h.platform.join("Steve")
        h.platform.dispatcher = {
            flaky().failNextAppend = true // the 'finished' entry cannot be written
            DispatchResult.OK
        }
        h.engine.applyResponse(h.engine.buildSyncRequest(), response(deliveries = listOf(delivery("k1", 1))))
        assertEquals(1, h.platform.console.size)
        assertEquals(1, h.store.runningCount(), "still 'running' on disk")
        assertFalse(h.engine.status().storeHealthy)

        // The next request retries the write first, so the result is reported at once.
        h.platform.dispatcher = { DispatchResult.OK }
        val r = h.sync(response(deliveries = listOf(delivery("k2", 2))))
        assertEquals(listOf("k1"), r.results.map { it.key })
        assertEquals("DONE", r.results[0].status)
        assertEquals(0, h.store.runningCount())
        assertEquals(2, h.platform.console.size, "k2 ran once the result of k1 was safe")
        h.crash()
        assertEquals("DONE", h.result("k1")!!.status, "and it reached the disk")
        assertEquals("DONE", h.result("k2")!!.status)
    }

    @Test
    fun `while a result cannot be written nothing new runs and capacity is 0`() {
        val (h, flaky) = flakyHarness()
        h.platform.join("Steve")
        h.platform.dispatcher = {
            flaky().failAppends = true
            DispatchResult.OK
        }
        h.engine.applyResponse(h.engine.buildSyncRequest(), response(deliveries = listOf(delivery("k1", 1))))
        h.platform.dispatcher = { DispatchResult.OK }
        val r = h.sync(response(deliveries = listOf(delivery("k2", 2))))
        assertTrue(r.results.isEmpty(), "k1 is not reportable: its result is not durable")
        assertEquals(0, r.capacity)
        assertEquals(1, h.platform.console.size, "k2 was ignored")
        assertNull(h.store.get("k2"))
        assertTrue(h.log.has("not writable"))

        flaky().failAppends = false
        val ok = h.request()
        assertEquals(listOf("k1"), ok.results.map { it.key })
        assertEquals(20, ok.capacity)
    }

    // ======== status ==============================================================================================

    @Test
    fun `the status is readable from any thread and follows the state`() {
        val h = harness()
        h.platform.join("Steve")
        h.sync(response(deliveries = listOf(delivery("k1", 1), delivery("k2", 2, player = "Alex", requiresOnline = true)), marketVersion = "1.4.0"))
        val s = h.engine.status()
        assertEquals(1, s.queued)
        assertEquals(0, s.running)
        assertEquals(2, s.unacked, "the done result and the queued report")
        assertEquals(2, s.records)
        assertEquals(true, s.accepted)
        assertEquals("1.4.0", s.marketVersion)
        assertNotNull(s.lastSyncAt)
    }
}
