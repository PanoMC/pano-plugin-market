package com.panomc.plugins.market.job

import com.panomc.plugins.market.service.RefundReconcileReport
import com.panomc.plugins.market.service.RefundService

/**
 * `RefundReconcileJob` (21 sections 3.3 and 3.5, every 60 s): the work is [RefundService.reconcile], one pass per call.
 * - `revokeFirst` rows (`REQUESTED`, never sent): the undo rows of the refunded lines all `CONFIRMED` (or `CANCELLED` as `NOTHING_TO_REVOKE`) release the row (the
 *   gateway call, or the settlement of a credit-only / manual refund); a `FAILED` undo row keeps it waiting and raises `REVOKE_FAILED`; still open 24 h after the
 *   request it becomes `CANCELLED` (`REVOKE_TIMEOUT`, alert), the undo rows are left as they are;
 * - `PENDING` rows and stale `REQUESTED` rows are asked with `queryRefund` at 5 min, 30 min, then every 6 h for 30 days (the answer is applied like the answer of
 *   the call itself, `Unknown` changes nothing);
 * - `SYSTEM` rows (duplicate payment, rejected review) that nobody sent are sent with their own idempotency key.
 * A row that throws is logged and left for the next pass; the others still run.
 */
class RefundReconcileJob(private val service: RefundService) {
    /** One pass with its report (what the tests read). */
    suspend fun run(): RefundReconcileReport = service.reconcile()

    /** One pass for the scheduler: the number of rows it moved. */
    suspend fun runOnce(): Int = run().let { it.released + it.sent + it.polled + it.timedOut }
}
