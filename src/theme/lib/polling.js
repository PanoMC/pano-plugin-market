// Order polling (14 §11.5): a pure schedule plus a thin runner whose effects are injected (timers, visibility,
// requests, clock), so the whole behaviour is unit-tested without a browser. No SDK, no DOM.
import { SETTLED_STATES } from './orderState.js';

const SECOND = 1000;

/** Polling stops this long after the last change (the "Refresh status" button takes over). */
export const STOP_AFTER_MS = 10 * 60 * SECOND;

/** Consecutive NETWORK answers before the offline note is shown. */
export const OFFLINE_AFTER = 3;

/** True when the view state is final: nothing is polled any more. */
export function settled(state) {
  return SETTLED_STATES.includes(state);
}

/**
 * Next delay in ms, or null (stop). `state` = view state id, `elapsedMs` = time since the last change (or since
 * mount), `inPage` = the pending order has a payment start of kind IFRAME / EMBEDDED (the buyer is paying on
 * this page, so it is polled faster).
 */
export function schedule(state, elapsedMs, { inPage = false } = {}) {
  const elapsed = Number.isFinite(elapsedMs) && elapsedMs > 0 ? elapsedMs : 0;

  if (settled(state) || state === 'UNKNOWN') return null;
  if (elapsed >= STOP_AFTER_MS) return null;

  if (state === 'CONFIRMING' || (state === 'AWAITING_PAYMENT' && inPage)) {
    if (elapsed < 20 * SECOND) return 2 * SECOND;
    if (elapsed < 80 * SECOND) return 5 * SECOND;

    return 15 * SECOND;
  }

  if (state === 'AWAITING_PAYMENT') return 15 * SECOND;
  if (state === 'PROCESSING' || state === 'REVIEW') return 30 * SECOND;

  if (state === 'PAID_DELIVERING' || state === 'PAID_SHIPPING')
    return elapsed < 2 * 60 * SECOND ? 15 * SECOND : 60 * SECOND;

  return null;
}

const WATCHED = ['status', 'paymentStatus', 'fulfillmentStatus', 'shippingStatus', 'updatedAt'];

/** The members of a status answer the runner compares. */
export function statusSignature(status) {
  const out = {};
  for (const key of WATCHED) out[key] = status?.[key] ?? null;

  return out;
}

/**
 * True when any of `status, paymentStatus, fulfillmentStatus, shippingStatus, updatedAt` differs. An OrderView
 * carries no `updatedAt`, so a previous signature taken from the order on screen has `updatedAt = null`: that
 * member is then unknown and not compared (otherwise every first poll would reload the order for nothing).
 */
export function statusChanged(previous, next) {
  if (!previous) return true;

  return WATCHED.some((key) => {
    if (key === 'updatedAt' && (previous[key] ?? null) === null) return false;

    return (previous[key] ?? null) !== (next?.[key] ?? null);
  });
}

/** The status signature of a full OrderView (so the first poll compares against what is on screen). */
export function signatureOfOrder(order) {
  return statusSignature({
    status: order?.status,
    paymentStatus: order?.payment?.status,
    fulfillmentStatus: order?.fulfillmentStatus,
    shippingStatus: order?.shippingStatus,
    updatedAt: order?.updatedAt,
  });
}

/**
 * The runner. Dependencies (all required except where defaulted):
 *  - `viewState()` -> the current view state id, `inPage()` -> boolean
 *  - `fetchStatus()` -> Promise<ApiResult> of GET orders/:id/status
 *  - `refetch()` -> Promise (loads the full order; the runner then restarts the elapsed time)
 *  - `now()` -> epoch ms, `setTimer(fn, ms)` -> handle, `clearTimer(handle)`, `isHidden()` -> boolean
 *  - `onState({ status, offline })` where status is RUNNING | PAUSED | SETTLED | STOPPED | NOT_FOUND
 *  - `signature()` -> the signature of the order on screen (default: none, first answer counts as a change)
 * Methods: `start()`, `stop()`, `reset()`, `pollNow({ reset })`, `visibilityChanged()`, `refresh()` (the manual
 * button), `state()`.
 */
export function createPoller(deps) {
  const {
    viewState,
    inPage = () => false,
    fetchStatus,
    refetch,
    now = () => Date.now(),
    setTimer = (fn, ms) => setTimeout(fn, ms),
    clearTimer = (handle) => clearTimeout(handle),
    isHidden = () => false,
    onState = () => {},
    signature = () => null,
  } = deps;

  let timer = null;
  let running = false;
  let startedAt = 0;
  let failures = 0;
  let last = null;
  let status = 'STOPPED';
  let offline = false;
  let generation = 0;
  let inflight = false;

  const publish = (next) => {
    status = next;
    onState({ status, offline });
  };

  const clear = () => {
    if (timer !== null) clearTimer(timer);
    timer = null;
  };

  function plan() {
    clear();
    if (!running) return;

    const state = viewState();

    if (settled(state) || state === 'UNKNOWN') {
      running = false;
      publish('SETTLED');
      return;
    }

    if (isHidden()) {
      publish('PAUSED');
      return;
    }

    const delay = schedule(state, now() - startedAt, { inPage: inPage() });

    if (delay === null) {
      running = false;
      publish('STOPPED');
      return;
    }

    publish('RUNNING');
    timer = setTimer(tick, delay);
  }

  async function tick() {
    timer = null;
    if (!running || inflight) return;
    if (isHidden()) return plan();

    inflight = true;
    const mine = generation;
    let res;

    try {
      res = await fetchStatus();
    } catch (e) {
      res = { ok: false, code: 'NETWORK' };
    }

    if (mine !== generation) return;
    inflight = false;
    if (!running) return;

    if (res?.ok === false && res.code === 'NOT_FOUND') {
      running = false;
      publish('NOT_FOUND');
      return;
    }

    if (res?.ok === false && res.code === 'NETWORK') {
      failures += 1;
      if (failures >= OFFLINE_AFTER) offline = true;
    } else if (res?.ok) {
      failures = 0;
      offline = false;
    }

    if (res?.ok) {
      const next = statusSignature(res);

      if (statusChanged(last, next)) {
        last = next;

        try {
          await refetch();
        } catch (e) {
          // the order is kept as it is; the next poll tries again
        }

        if (mine !== generation || !running) return;
        startedAt = now();
      }
    }

    plan();
  }

  return {
    state: () => ({ status, offline }),

    start() {
      running = true;
      generation += 1;
      inflight = false;
      startedAt = now();
      failures = 0;
      offline = false;
      last = signature();
      plan();
    },

    stop() {
      running = false;
      generation += 1;
      inflight = false;
      clear();
      status = 'STOPPED';
    },

    /** The order changed from outside (an action, a manual refetch): restart the elapsed time and re-plan. */
    reset() {
      if (!running) return;
      startedAt = now();
      last = signature();
      plan();
    },

    /** One poll now (visibility, expiry). `reset: false` keeps the elapsed time. */
    pollNow({ reset = false } = {}) {
      if (!running) return;
      if (reset) startedAt = now();
      clear();
      tick();
    },

    visibilityChanged() {
      if (!running) return;

      if (isHidden()) {
        clear();
        publish('PAUSED');
      } else {
        clear();
        tick();
      }
    },

    /** "Refresh status" after the stop time: one manual poll, then the schedule starts over. */
    refresh() {
      running = true;
      generation += 1;
      inflight = false;
      startedAt = now();
      failures = 0;
      last = signature();
      clear();
      tick();
    },
  };
}
