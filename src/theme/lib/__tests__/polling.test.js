import { describe, expect, test } from 'bun:test';
import { SETTLED_STATES, VIEW_STATES } from '../orderState.js';
import {
  OFFLINE_AFTER,
  STOP_AFTER_MS,
  createPoller,
  schedule,
  settled,
  signatureOfOrder,
  statusChanged,
  statusSignature,
} from '../polling.js';

const S = 1000;
const MIN = 60 * S;

describe('schedule (14 §11.5)', () => {
  test('CONFIRMING and in-page AWAITING_PAYMENT: 2 s for 20 s, 5 s until 80 s, then 15 s', () => {
    for (const [state, opts] of [
      ['CONFIRMING', {}],
      ['AWAITING_PAYMENT', { inPage: true }],
    ]) {
      expect(schedule(state, 0, opts)).toBe(2 * S);
      expect(schedule(state, 19_999, opts)).toBe(2 * S);
      expect(schedule(state, 20_000, opts)).toBe(5 * S);
      expect(schedule(state, 79_999, opts)).toBe(5 * S);
      expect(schedule(state, 80_000, opts)).toBe(15 * S);
      expect(schedule(state, 9 * MIN, opts)).toBe(15 * S);
    }
  });

  test('other AWAITING_PAYMENT: 15 s, no fast phase', () => {
    expect(schedule('AWAITING_PAYMENT', 0)).toBe(15 * S);
    expect(schedule('AWAITING_PAYMENT', 30 * S)).toBe(15 * S);
    expect(schedule('AWAITING_PAYMENT', 0, { inPage: false })).toBe(15 * S);
  });

  test('PROCESSING and REVIEW: 30 s', () => {
    for (const state of ['PROCESSING', 'REVIEW']) {
      expect(schedule(state, 0)).toBe(30 * S);
      expect(schedule(state, 5 * MIN)).toBe(30 * S);
    }
  });

  test('PAID_DELIVERING and PAID_SHIPPING: 15 s for 2 min, then 60 s', () => {
    for (const state of ['PAID_DELIVERING', 'PAID_SHIPPING']) {
      expect(schedule(state, 0)).toBe(15 * S);
      expect(schedule(state, 2 * MIN - 1)).toBe(15 * S);
      expect(schedule(state, 2 * MIN)).toBe(60 * S);
      expect(schedule(state, 9 * MIN)).toBe(60 * S);
    }
  });

  test('every state stops at 10 minutes', () => {
    for (const state of [
      'CONFIRMING',
      'AWAITING_PAYMENT',
      'PROCESSING',
      'REVIEW',
      'PAID_DELIVERING',
      'PAID_SHIPPING',
    ]) {
      expect(schedule(state, STOP_AFTER_MS - 1, { inPage: true })).not.toBeNull();
      expect(schedule(state, STOP_AFTER_MS, { inPage: true })).toBeNull();
      expect(schedule(state, STOP_AFTER_MS + 1)).toBeNull();
    }
    expect(STOP_AFTER_MS).toBe(10 * MIN);
  });

  test('settled states and UNKNOWN are never polled', () => {
    for (const state of SETTLED_STATES) {
      expect(settled(state)).toBe(true);
      expect(schedule(state, 0)).toBeNull();
    }
    expect(schedule('UNKNOWN', 0)).toBeNull();
    expect(settled('PAID_SHIPPING')).toBe(false);
  });

  test('every non-settled view state has a delay at elapsed 0', () => {
    for (const state of VIEW_STATES.filter((s) => !SETTLED_STATES.includes(s)))
      expect(schedule(state, 0), state).toBeGreaterThan(0);
  });

  test('garbage elapsed counts as 0', () => {
    expect(schedule('PROCESSING', NaN)).toBe(30 * S);
    expect(schedule('PROCESSING', -5)).toBe(30 * S);
    expect(schedule('PROCESSING', undefined)).toBe(30 * S);
  });
});

describe('status comparison', () => {
  const base = {
    status: 'PENDING',
    paymentStatus: 'CREATED',
    fulfillmentStatus: 'NONE',
    shippingStatus: 'NOT_REQUIRED',
    updatedAt: 5,
  };

  test('any of the five members differing is a change', () => {
    for (const [key, value] of [
      ['status', 'COMPLETED'],
      ['paymentStatus', 'PENDING'],
      ['fulfillmentStatus', 'PENDING'],
      ['shippingStatus', 'PENDING'],
      ['updatedAt', 6],
    ])
      expect(statusChanged(statusSignature(base), { ...base, [key]: value }), key).toBe(true);
  });

  test('identical answers (and extra members) are no change', () => {
    expect(statusChanged(statusSignature(base), { ...base, extra: 1 })).toBe(false);
  });

  test('no previous signature is a change', () => {
    expect(statusChanged(null, base)).toBe(true);
  });

  test('the signature of an order on screen leaves updatedAt unknown (no pointless first reload)', () => {
    const sig = signatureOfOrder({
      status: 'PENDING',
      payment: { status: 'CREATED' },
      fulfillmentStatus: 'NONE',
      shippingStatus: 'NOT_REQUIRED',
    });
    expect(sig.updatedAt).toBeNull();
    expect(statusChanged(sig, base)).toBe(false);
    expect(statusChanged(sig, { ...base, paymentStatus: 'PENDING' })).toBe(true);
  });
});

// ---- runner with every effect injected --------------------------------------------------------------------

function harness(opts = {}) {
  const h = {
    clock: 1_000_000,
    timers: [],
    state: opts.state ?? 'AWAITING_PAYMENT',
    inPage: opts.inPage ?? false,
    hidden: false,
    statusCalls: 0,
    refetchCalls: 0,
    states: [],
    answers: [],
    onRefetch: () => {},
    refetchResult: undefined,
    sig: opts.sig ?? null,
  };
  h.poller = createPoller({
    viewState: () => h.state,
    inPage: () => h.inPage,
    fetchStatus: async () => {
      h.statusCalls += 1;
      const next = h.answers.length ? h.answers.shift() : { ok: true, status: 'PENDING' };
      if (next instanceof Error) throw next;
      return next;
    },
    refetch: async () => {
      h.refetchCalls += 1;
      h.onRefetch();
      if (h.refetchResult instanceof Error) throw h.refetchResult;
      return h.refetchResult;
    },
    now: () => h.clock,
    setTimer: (fn, ms) => {
      const timer = { fn, ms, at: h.clock + ms, cleared: false };
      h.timers.push(timer);
      return timer;
    },
    clearTimer: (timer) => {
      if (timer) timer.cleared = true;
    },
    isHidden: () => h.hidden,
    onState: (s) => h.states.push({ ...s }),
    signature: () => h.sig,
  });
  h.live = () => h.timers.filter((t) => !t.cleared);
  /** Fires the newest live timer after moving the clock to its due time. */
  h.fire = async () => {
    const timer = h.live().at(-1);
    if (!timer) throw new Error('no live timer');
    timer.cleared = true;
    h.clock = timer.at;
    await timer.fn();
    await Promise.resolve();
    await Promise.resolve();
  };
  h.last = () => h.states.at(-1);
  return h;
}

const ok = (over = {}) => ({
  ok: true,
  status: 'PENDING',
  paymentStatus: 'PENDING',
  fulfillmentStatus: 'NONE',
  shippingStatus: 'NOT_REQUIRED',
  updatedAt: 1,
  ...over,
});

describe('runner', () => {
  test('start schedules the first delay of the view state', () => {
    const h = harness();
    h.poller.start();
    expect(h.live()).toHaveLength(1);
    expect(h.live()[0].ms).toBe(15 * S);
    expect(h.last()).toEqual({ status: 'RUNNING', offline: false });
  });

  test('a settled order is never polled and reports SETTLED', () => {
    const h = harness({ state: 'PAID_DELIVERED' });
    h.poller.start();
    expect(h.live()).toHaveLength(0);
    expect(h.last().status).toBe('SETTLED');
  });

  test('an unchanged answer re-plans with a grown elapsed time (CONFIRMING fast phase ends at 20 s)', async () => {
    const h = harness({ state: 'CONFIRMING', sig: statusSignature(ok()) });
    h.answers = Array.from({ length: 12 }, () => ok());
    h.poller.start();
    const delays = [h.live()[0].ms];
    for (let i = 0; i < 11; i += 1) {
      await h.fire();
      delays.push(h.live()[0].ms);
    }
    expect(delays.slice(0, 11)).toEqual(
      [2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000, 2000]
        .slice(0, 10)
        .concat([5000]),
    );
    expect(h.refetchCalls).toBe(0);
  });

  test('a changed answer re-fetches the order and restarts the elapsed time from 0', async () => {
    const h = harness({ state: 'CONFIRMING', sig: statusSignature(ok()) });
    h.answers = [
      ok(),
      ok(),
      ok(),
      ok(),
      ok(),
      ok(),
      ok(),
      ok(),
      ok(),
      ok(),
      ok({ status: 'COMPLETED' }),
    ];
    h.poller.start();
    for (let i = 0; i < 10; i += 1) await h.fire();
    expect(h.live()[0].ms).toBe(5000); // 20 s elapsed
    h.onRefetch = () => (h.state = 'CONFIRMING');
    await h.fire();
    expect(h.refetchCalls).toBe(1);
    expect(h.live()[0].ms).toBe(2000); // back in the fast phase
  });

  test('seeded from the order on screen (updatedAt unknown): the first poll learns it, a later updatedAt-only change re-fetches', async () => {
    const order = {
      status: 'PENDING',
      payment: { status: 'PENDING' },
      fulfillmentStatus: 'NONE',
      shippingStatus: 'NOT_REQUIRED',
    };
    const h = harness({ sig: signatureOfOrder(order) });
    h.answers = [ok({ updatedAt: 5 }), ok({ updatedAt: 6 }), ok({ updatedAt: 6 })];
    h.poller.start();
    await h.fire();
    expect(h.refetchCalls).toBe(0);
    await h.fire();
    expect(h.refetchCalls).toBe(1);
    await h.fire();
    expect(h.refetchCalls).toBe(1); // 6 was adopted after the successful re-fetch
  });

  for (const [label, failure] of [
    ['resolves false', false],
    ['throws', new Error('boom')],
  ])
    test(`a re-fetch that ${label} keeps the change unseen: the next poll re-fetches again and the elapsed time is not restarted`, async () => {
      const h = harness({ state: 'CONFIRMING', sig: statusSignature(ok()) });
      h.answers = [
        ok({ status: 'COMPLETED' }),
        ok({ status: 'COMPLETED' }),
        ok({ status: 'COMPLETED' }),
        ok({ status: 'COMPLETED' }),
      ];
      h.refetchResult = failure;
      h.poller.start();
      const startedAt = h.clock;
      await h.fire();
      expect(h.refetchCalls).toBe(1);
      expect(h.live()[0].ms).toBe(2000); // still the fast phase, elapsed measured from the start
      await h.fire();
      expect(h.refetchCalls).toBe(2);
      expect(h.clock - startedAt).toBe(4000);
      h.refetchResult = undefined; // now it works
      await h.fire();
      expect(h.refetchCalls).toBe(3);
      await h.fire();
      expect(h.refetchCalls).toBe(3); // seen
    });

  test('after the change the state decides: settled stops the runner', async () => {
    const h = harness({ state: 'AWAITING_PAYMENT', inPage: true, sig: statusSignature(ok()) });
    h.answers = [ok({ status: 'COMPLETED' })];
    h.onRefetch = () => (h.state = 'PAID_DELIVERED');
    h.poller.start();
    await h.fire();
    expect(h.refetchCalls).toBe(1);
    expect(h.live()).toHaveLength(0);
    expect(h.last().status).toBe('SETTLED');
  });

  test('stops at 10 minutes and reports STOPPED; refresh() polls once and restarts the schedule', async () => {
    const h = harness({ state: 'PROCESSING', sig: statusSignature(ok()) });
    h.answers = Array.from({ length: 40 }, () => ok());
    h.poller.start();
    let guard = 0;
    while (h.live().length && guard++ < 40) await h.fire();
    expect(h.last().status).toBe('STOPPED');
    expect(h.clock - 1_000_000).toBeGreaterThanOrEqual(STOP_AFTER_MS - 30_000);
    const calls = h.statusCalls;
    h.poller.refresh();
    await Promise.resolve();
    await Promise.resolve();
    expect(h.statusCalls).toBe(calls + 1);
    expect(h.last().status).toBe('RUNNING');
    expect(h.live()).toHaveLength(1);
  });

  test('three consecutive NETWORK answers raise the offline flag, polling continues, one ok clears it', async () => {
    const h = harness({ sig: statusSignature(ok()) });
    h.answers = [
      { ok: false, code: 'NETWORK' },
      { ok: false, code: 'NETWORK' },
      { ok: false, code: 'NETWORK' },
      ok(),
    ];
    h.poller.start();
    await h.fire();
    await h.fire();
    expect(h.last().offline).toBe(false);
    await h.fire();
    expect(OFFLINE_AFTER).toBe(3);
    expect(h.last().offline).toBe(true);
    expect(h.live()).toHaveLength(1);
    await h.fire();
    expect(h.last().offline).toBe(false);
  });

  test('a thrown request counts as NETWORK', async () => {
    const h = harness({ sig: statusSignature(ok()) });
    h.answers = [new Error('boom'), new Error('boom'), new Error('boom')];
    h.poller.start();
    await h.fire();
    await h.fire();
    await h.fire();
    expect(h.last().offline).toBe(true);
  });

  test('a non-NETWORK error neither counts toward offline nor stops', async () => {
    const h = harness({ sig: statusSignature(ok()) });
    h.answers = [
      { ok: false, code: 'TOO_MANY_REQUESTS' },
      { ok: false, code: 'TOO_MANY_REQUESTS' },
      { ok: false, code: 'TOO_MANY_REQUESTS' },
    ];
    h.poller.start();
    await h.fire();
    await h.fire();
    await h.fire();
    expect(h.last().offline).toBe(false);
    expect(h.live()).toHaveLength(1);
  });

  test('NOT_FOUND stops polling', async () => {
    const h = harness();
    h.answers = [{ ok: false, code: 'NOT_FOUND' }];
    h.poller.start();
    await h.fire();
    expect(h.live()).toHaveLength(0);
    expect(h.last().status).toBe('NOT_FOUND');
  });

  test('hidden tab: the timer is dropped (PAUSED) and becoming visible polls immediately', async () => {
    const h = harness({ sig: statusSignature(ok()) });
    h.poller.start();
    h.hidden = true;
    h.poller.visibilityChanged();
    expect(h.live()).toHaveLength(0);
    expect(h.last().status).toBe('PAUSED');
    expect(h.statusCalls).toBe(0);

    h.hidden = false;
    h.poller.visibilityChanged();
    await Promise.resolve();
    await Promise.resolve();
    expect(h.statusCalls).toBe(1);
    expect(h.live()).toHaveLength(1);
    expect(h.last().status).toBe('RUNNING');
  });

  test('a timer that fires while hidden does not request and pauses', async () => {
    const h = harness();
    h.poller.start();
    h.hidden = true;
    await h.fire();
    expect(h.statusCalls).toBe(0);
    expect(h.last().status).toBe('PAUSED');
  });

  test('pollNow polls once without or with an elapsed reset', async () => {
    const h = harness({ state: 'PAID_SHIPPING', sig: statusSignature(ok()) });
    h.answers = [ok(), ok()];
    h.poller.start();
    h.clock += 3 * MIN;
    h.poller.pollNow({ reset: false });
    await Promise.resolve();
    await Promise.resolve();
    expect(h.statusCalls).toBe(1);
    expect(h.live()[0].ms).toBe(60 * S); // elapsed 3 min kept
    h.poller.pollNow({ reset: true });
    await Promise.resolve();
    await Promise.resolve();
    expect(h.live()[0].ms).toBe(15 * S); // elapsed back to 0
  });

  test('reset() restarts the elapsed time and re-plans; stop() clears everything', async () => {
    const h = harness({ state: 'PAID_SHIPPING', sig: statusSignature(ok()) });
    h.poller.start();
    h.clock += 3 * MIN;
    h.state = 'PAID_SHIPPING';
    h.poller.reset();
    expect(h.live().at(-1).ms).toBe(15 * S);
    h.poller.stop();
    expect(h.live()).toHaveLength(0);
    h.poller.pollNow();
    expect(h.statusCalls).toBe(0);
  });

  test('an answer that arrives after stop() is dropped', async () => {
    const h = harness({ sig: statusSignature(ok()) });
    let release;
    const pending = new Promise((resolve) => (release = resolve));
    const poller = createPoller({
      viewState: () => 'AWAITING_PAYMENT',
      fetchStatus: () => pending,
      refetch: async () => {
        h.refetchCalls += 1;
      },
      now: () => h.clock,
      setTimer: (fn, ms) => {
        const timer = { fn, ms, cleared: false };
        h.timers.push(timer);
        return timer;
      },
      clearTimer: (t) => (t.cleared = true),
    });
    poller.start();
    h.live()[0].fn();
    poller.stop();
    release(ok({ status: 'COMPLETED' }));
    await Promise.resolve();
    await Promise.resolve();
    expect(h.refetchCalls).toBe(0);
  });
});
