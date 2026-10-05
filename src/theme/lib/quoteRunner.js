// Quote requests of the checkout page (14 §10.6): one 300 ms debounce, one sequence counter (only the latest
// response is applied), an automatic retry after TOO_MANY_REQUESTS, DISABLED on STORE_DISABLED /
// STORE_UNAVAILABLE. Pure of the SDK and the DOM: `call` and the timers are injected, unit-tested.
import { quoteDelay } from './checkoutModel.js';

export const QUOTE_PATH = '/api/market/checkout/quote';
export const MAX_RETRY_SECONDS = 30;

const DISABLED_CODES = ['STORE_DISABLED', 'STORE_UNAVAILABLE'];

/**
 * `onState({ status, quote?, code?, retryAfter? })` with status IDLE | QUOTING | RATE_LIMITED | ERROR | DISABLED;
 * `onQuote(quote)` after every applied quote (the caller applies the selection rules there).
 */
export function createQuoteRunner(overrides = {}) {
  const d = {
    call: async () => ({ ok: false, code: 'NETWORK' }),
    setTimer: (fn, ms) => setTimeout(fn, ms),
    clearTimer: (id) => clearTimeout(id),
    onState: () => {},
    onQuote: () => {},
    ...overrides,
  };

  let seq = 0;
  let timer = null;
  let retryTimer = null;
  let lastSig = null;
  let lastBody = null;

  function clearTimers() {
    if (timer !== null) d.clearTimer(timer);
    if (retryTimer !== null) d.clearTimer(retryTimer);
    timer = null;
    retryTimer = null;
  }

  async function run(body, mine) {
    timer = null;

    let res;
    try {
      res = await d.call('POST', QUOTE_PATH, { body });
    } catch (e) {
      res = { ok: false, code: 'NETWORK' };
    }

    if (mine !== seq) return; // a newer request made this answer stale

    if (res?.ok && res.quote && typeof res.quote === 'object') {
      d.onState({ status: 'IDLE', quote: res.quote, code: null });
      d.onQuote(res.quote);
      return;
    }

    const code = res?.code || 'NETWORK';

    if (DISABLED_CODES.includes(code)) {
      d.onState({ status: 'DISABLED', code });
    } else if (code === 'TOO_MANY_REQUESTS') {
      const wait = Math.min(
        Number(res.retryAfter) > 0 ? Number(res.retryAfter) : 1,
        MAX_RETRY_SECONDS,
      );
      d.onState({ status: 'RATE_LIMITED', code, retryAfter: wait });
      retryTimer = d.setTimer(() => {
        retryTimer = null;
        if (mine === seq) schedule(body, 0);
      }, wait * 1000);
    } else {
      lastSig = null; // the same input may be asked again (Retry)
      d.onState({ status: 'ERROR', code });
    }
  }

  function schedule(body, delay) {
    clearTimers();
    const mine = ++seq;

    d.onState({ status: 'QUOTING' });
    timer = d.setTimer(() => run(body, mine), delay);
  }

  /** Asks for a quote of `body` unless `sig` (its signature) is the one asked last. */
  function request(body, sig) {
    if (sig === lastSig) return;

    const delay = quoteDelay(lastBody, body);
    lastSig = sig;
    lastBody = body;
    schedule(body, delay);
  }

  /** "Retry": asks again for the last body at once. */
  function retry() {
    if (lastBody) schedule(lastBody, 0);
  }

  /** The page unmounted: pending timers and answers are dropped. */
  function stop() {
    seq++;
    clearTimers();
    lastSig = null;
    lastBody = null;
  }

  return { request, retry, stop };
}
