import { describe, expect, test } from 'bun:test';
import { createQuoteRunner } from '../quoteRunner.js';

function harness(answers) {
  const timers = new Map();
  let nextId = 1;
  const calls = [];
  const states = [];
  const quotes = [];
  const gates = [];

  const runner = createQuoteRunner({
    call: (method, path, options) => {
      calls.push({ method, path, body: options.body });
      const answer = answers.shift();
      return typeof answer === 'function' ? answer() : Promise.resolve(answer);
    },
    setTimer: (fn, ms) => {
      timers.set(nextId, { fn, ms });
      return nextId++;
    },
    clearTimer: (id) => timers.delete(id),
    onState: (s) => states.push(s),
    onQuote: (q) => quotes.push(q),
  });

  const fire = async () => {
    const [id, { fn }] = [...timers][0];
    timers.delete(id);
    fn(); // not awaited: the run may wait for a gated answer
    await new Promise((r) => setTimeout(r, 0));
  };

  return { runner, timers, calls, states, quotes, fire, gates };
}

const tick = () => new Promise((r) => setTimeout(r, 0));

describe('createQuoteRunner', () => {
  test('debounces 300 ms, asks once and applies the quote', async () => {
    const h = harness([{ ok: true, quote: { total: 5 } }]);

    h.runner.request({ couponCode: 'A' }, 'a');
    h.runner.request({ couponCode: 'AB' }, 'ab');

    expect(h.timers.size).toBe(1);
    expect([...h.timers.values()][0].ms).toBe(300);
    expect(h.states.map((s) => s.status)).toEqual(['QUOTING', 'QUOTING']);

    await h.fire();
    await tick();

    expect(h.calls).toHaveLength(1);
    expect(h.calls[0]).toEqual({
      method: 'POST',
      path: '/api/market/checkout/quote',
      body: { couponCode: 'AB' },
    });
    expect(h.quotes).toEqual([{ total: 5 }]);
    expect(h.states.at(-1)).toEqual({ status: 'IDLE', quote: { total: 5 }, code: null });
  });

  test('an unchanged signature asks nothing', async () => {
    const h = harness([{ ok: true, quote: { total: 1 } }]);

    h.runner.request({ a: 1 }, 's');
    h.runner.request({ a: 1 }, 's');

    expect(h.timers.size).toBe(1);
    await h.fire();
    await tick();
    h.runner.request({ a: 1 }, 's');
    expect(h.timers.size).toBe(0);
  });

  test('a change of the shipping address waits 500 ms', () => {
    const h = harness([]);

    h.runner.request({ shippingAddress: { city: 'A' } }, '1');
    h.runner.request({ shippingAddress: { city: 'Ab' } }, '2');

    expect([...h.timers.values()][0].ms).toBe(500);
  });

  test('only the latest response is applied (sequence counter)', async () => {
    let releaseFirst;
    const first = new Promise((resolve) => (releaseFirst = resolve));
    const h = harness([() => first, { ok: true, quote: { total: 2 } }]);

    h.runner.request({ n: 1 }, '1');
    await h.fire(); // first request in flight
    h.runner.request({ n: 2 }, '2');
    await h.fire(); // second request
    await tick();

    expect(h.quotes).toEqual([{ total: 2 }]);

    releaseFirst({ ok: true, quote: { total: 1 } }); // the stale answer arrives late
    await tick();

    expect(h.quotes).toEqual([{ total: 2 }]);
    expect(h.states.at(-1).quote).toEqual({ total: 2 });
  });

  test('TOO_MANY_REQUESTS retries after min(retryAfter, 30) seconds', async () => {
    const h = harness([
      { ok: false, code: 'TOO_MANY_REQUESTS', retryAfter: 120 },
      { ok: true, quote: { total: 7 } },
    ]);

    h.runner.request({ n: 1 }, '1');
    await h.fire();
    await tick();

    expect(h.states.at(-1)).toEqual({
      status: 'RATE_LIMITED',
      code: 'TOO_MANY_REQUESTS',
      retryAfter: 30,
    });
    expect([...h.timers.values()][0].ms).toBe(30000);

    await h.fire(); // the automatic retry schedules the request
    await h.fire();
    await tick();

    expect(h.calls).toHaveLength(2);
    expect(h.quotes).toEqual([{ total: 7 }]);
  });

  test('TOO_MANY_REQUESTS without a usable retryAfter waits 1 second', async () => {
    const h = harness([{ ok: false, code: 'TOO_MANY_REQUESTS' }]);

    h.runner.request({}, '1');
    await h.fire();
    await tick();

    expect(h.states.at(-1).retryAfter).toBe(1);
  });

  test('STORE_DISABLED and STORE_UNAVAILABLE end in DISABLED', async () => {
    for (const code of ['STORE_DISABLED', 'STORE_UNAVAILABLE']) {
      const h = harness([{ ok: false, code }]);

      h.runner.request({}, '1');
      await h.fire();
      await tick();

      expect(h.states.at(-1)).toEqual({ status: 'DISABLED', code });
      expect(h.timers.size).toBe(0);
    }
  });

  test('NETWORK is an ERROR; Retry asks the last body again at once', async () => {
    const h = harness([
      { ok: false, code: 'NETWORK' },
      { ok: true, quote: { total: 3 } },
    ]);

    h.runner.request({ n: 1 }, '1');
    await h.fire();
    await tick();
    expect(h.states.at(-1)).toEqual({ status: 'ERROR', code: 'NETWORK' });

    h.runner.retry();
    expect([...h.timers.values()][0].ms).toBe(0);
    await h.fire();
    await tick();

    expect(h.calls.map((c) => c.body)).toEqual([{ n: 1 }, { n: 1 }]);
    expect(h.quotes).toEqual([{ total: 3 }]);
  });

  test('after an error the same input may be asked again', async () => {
    const h = harness([{ ok: false, code: 'NETWORK' }]);

    h.runner.request({ n: 1 }, 's');
    await h.fire();
    await tick();
    h.runner.request({ n: 1 }, 's');

    expect(h.timers.size).toBe(1);
  });

  test('a throwing call and an ok answer without a quote are ERROR NETWORK', async () => {
    const h = harness([() => Promise.reject(new Error('boom')), { ok: true }]);

    h.runner.request({ n: 1 }, '1');
    await h.fire();
    await tick();
    expect(h.states.at(-1)).toEqual({ status: 'ERROR', code: 'NETWORK' });

    h.runner.request({ n: 2 }, '2');
    await h.fire();
    await tick();
    expect(h.states.at(-1)).toEqual({ status: 'ERROR', code: 'NETWORK' });
    expect(h.quotes).toEqual([]);
  });

  test('stop drops pending timers and late answers', async () => {
    let release;
    const gate = new Promise((resolve) => (release = resolve));
    const h = harness([() => gate]);

    h.runner.request({ n: 1 }, '1');
    await h.fire();
    h.runner.stop();
    release({ ok: true, quote: { total: 9 } });
    await tick();

    expect(h.quotes).toEqual([]);

    h.runner.request({ n: 2 }, '2');
    h.runner.stop();
    expect(h.timers.size).toBe(0);
  });
});
