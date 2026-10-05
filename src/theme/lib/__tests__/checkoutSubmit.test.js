import { describe, expect, test } from 'bun:test';
import {
  CHECKOUT_PATH,
  IDEMPOTENCY_HEADER,
  ORDER_TOKEN_PREFIX,
  buildSubmitBody,
  failurePlan,
  prepareSubmit,
  quoteHeld,
  quoteHoldAfter,
  saveOrderToken,
  submitCheckout,
  successPlan,
} from '../checkoutSubmit.js';

const UUID4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const quote = (extra = {}) => ({
  total: 25.5,
  gatewayAmount: 25.5,
  credits: { enabled: true, applied: 0, maxApplicable: 40 },
  ...extra,
});

/** A draft store like the checkout draft: get / patch. */
function draftStore(initial = {}) {
  let state = { idempotencyKey: null, bodyHash: null, ...initial };

  return {
    get: () => state,
    patch: (patch) => (state = { ...state, ...patch }),
  };
}

const ok = (extra = {}) => ({
  ok: true,
  order: { publicId: 'ord123' },
  orderToken: 'tok-secret',
  payment: { kind: 'REDIRECT', url: 'https://pay.test/s' },
  ...extra,
});

describe('buildSubmitBody', () => {
  test('adds expectedTotal from the quote, never a computed total', () => {
    const body = buildSubmitBody({
      quoteBody: { items: [], paymentMethodId: 'stripe' },
      quote: quote({ total: 99.99 }),
    });

    expect(body).toEqual({ items: [], paymentMethodId: 'stripe', expectedTotal: 99.99 });
  });

  test('does not mutate the quote body', () => {
    const quoteBody = { useCredits: 'MAX' };

    buildSubmitBody({ quoteBody, quote: quote({ credits: { applied: 12 } }) });

    expect(quoteBody).toEqual({ useCredits: 'MAX' });
  });

  test('"MAX" becomes the number of credits the quote applied; never the word MAX', () => {
    const body = buildSubmitBody({
      quoteBody: { useCredits: 'MAX' },
      quote: quote({ credits: { enabled: true, applied: 12.5, maxApplicable: 12.5 } }),
    });

    expect(body.useCredits).toBe(12.5);
    expect(typeof body.useCredits).toBe('number');
  });

  test('a typed amount is sent as the number the quote applied', () => {
    const body = buildSubmitBody({
      quoteBody: { useCredits: 30 },
      quote: quote({ credits: { applied: 20 } }),
    });

    expect(body.useCredits).toBe(20);
  });

  test.each([
    ['nothing applied', { credits: { applied: 0 } }],
    ['no credits in the quote', { credits: null }],
    ['garbage applied', { credits: { applied: 'lots' } }],
  ])('useCredits is left out when %s', (_, over) => {
    const body = buildSubmitBody({ quoteBody: { useCredits: 'MAX' }, quote: quote(over) });

    expect('useCredits' in body).toBe(false);
  });

  test('credits are not in the body unless the buyer chose them', () => {
    const body = buildSubmitBody({
      quoteBody: { paymentMethodId: 'a' },
      quote: quote({ credits: { applied: 40 } }),
    });

    expect('useCredits' in body).toBe(false);
    expect('payWithCredits' in body).toBe(false);
  });

  test('paying in credits sends payWithCredits and no amount', () => {
    const body = buildSubmitBody({
      quoteBody: { payWithCredits: true, useCredits: 5 },
      quote: quote(),
    });

    expect(body.payWithCredits).toBe(true);
    expect('useCredits' in body).toBe(false);
  });

  test('legal acceptance and text id only when the legal text is required', () => {
    const required = { legal: { required: true, id: 7 } };

    expect(
      buildSubmitBody({ quoteBody: {}, quote: quote(), config: required, accepted: true }),
    ).toMatchObject({
      acceptLegal: true,
      legalTextId: 7,
    });
    expect(
      buildSubmitBody({ quoteBody: {}, quote: quote(), config: required, accepted: false })
        .acceptLegal,
    ).toBe(false);

    const optional = buildSubmitBody({
      quoteBody: {},
      quote: quote(),
      config: { legal: { required: false, id: 7 } },
      accepted: true,
    });

    expect('acceptLegal' in optional).toBe(false);
    expect('legalTextId' in optional).toBe(false);
    expect('acceptLegal' in buildSubmitBody({ quoteBody: {}, quote: quote(), config: null })).toBe(
      false,
    );
  });

  test('hideFromBroadcast only when ticked', () => {
    expect(buildSubmitBody({ quoteBody: {}, quote: quote(), hide: true }).hideFromBroadcast).toBe(
      true,
    );
    expect(
      'hideFromBroadcast' in buildSubmitBody({ quoteBody: {}, quote: quote(), hide: false }),
    ).toBe(false);
  });

  test('tolerates a missing quote body or quote', () => {
    expect(buildSubmitBody({ quoteBody: null, quote: null })).toEqual({});
  });
});

describe('one Idempotency-Key per unchanged body', () => {
  test('the same body reuses the stored key; a changed body gets a new UUID v4', () => {
    const first = prepareSubmit({ draft: {}, body: { a: 1 } });
    const key = first.headers[IDEMPOTENCY_HEADER];

    expect(key).toMatch(UUID4);
    expect(first.reused).toBe(false);

    const retry = prepareSubmit({ draft: first.patch, body: { a: 1 } });
    expect(retry.headers[IDEMPOTENCY_HEADER]).toBe(key);
    expect(retry.reused).toBe(true);

    const changed = prepareSubmit({ draft: first.patch, body: { a: 2 } });
    expect(changed.headers[IDEMPOTENCY_HEADER]).not.toBe(key);
    expect(changed.headers[IDEMPOTENCY_HEADER]).toMatch(UUID4);
    expect(changed.reused).toBe(false);
  });

  test('member order of the body does not change the key', () => {
    const first = prepareSubmit({ draft: {}, body: { a: 1, b: { c: 2, d: 3 } } });
    const again = prepareSubmit({ draft: first.patch, body: { b: { d: 3, c: 2 }, a: 1 } });

    expect(again.reused).toBe(true);
    expect(again.headers[IDEMPOTENCY_HEADER]).toBe(first.headers[IDEMPOTENCY_HEADER]);
  });

  test('a different expectedTotal is a different order', () => {
    const first = prepareSubmit({ draft: {}, body: { expectedTotal: 10 } });
    const next = prepareSubmit({ draft: first.patch, body: { expectedTotal: 11 } });

    expect(next.headers[IDEMPOTENCY_HEADER]).not.toBe(first.headers[IDEMPOTENCY_HEADER]);
  });

  test('submitCheckout posts with the header, stores key and hash, and replays after a lost answer', async () => {
    const store = draftStore();
    const calls = [];
    const call = async (method, path, options) => {
      calls.push({ method, path, options });
      return calls.length === 1 ? { ok: false, code: 'NETWORK' } : ok();
    };
    const args = {
      call,
      draftStore: store,
      quoteBody: { items: [{ productId: 1, quantity: 1 }], paymentMethodId: 'stripe' },
      quote: quote(),
      config: {},
      accepted: false,
      hide: false,
      fresh: true,
    };

    const lost = await submitCheckout(args);
    expect(lost.ok).toBe(false);
    expect(lost.code).toBe('NETWORK');
    expect(calls[0].method).toBe('POST');
    expect(calls[0].path).toBe(CHECKOUT_PATH);
    expect(store.get().idempotencyKey).toBe(calls[0].options.headers[IDEMPOTENCY_HEADER]);
    expect(store.get().bodyHash).toBeTruthy();

    const replay = await submitCheckout(args);
    expect(replay.ok).toBe(true);
    expect(calls[1].options.headers[IDEMPOTENCY_HEADER]).toBe(
      calls[0].options.headers[IDEMPOTENCY_HEADER],
    );
    expect(calls[1].options.body).toEqual(calls[0].options.body);
  });

  test('a changed order (new total) goes out under a new key', async () => {
    const store = draftStore();
    const keys = [];
    const call = async (_m, _p, options) => {
      keys.push(options.headers[IDEMPOTENCY_HEADER]);
      return { ok: false, code: 'NETWORK' };
    };
    const base = {
      call,
      draftStore: store,
      quoteBody: { a: 1 },
      config: {},
      accepted: false,
      hide: false,
      fresh: true,
    };

    await submitCheckout({ ...base, quote: quote({ total: 10 }) });
    await submitCheckout({ ...base, quote: quote({ total: 10 }) });
    await submitCheckout({ ...base, quote: quote({ total: 12 }) });

    expect(keys[1]).toBe(keys[0]);
    expect(keys[2]).not.toBe(keys[0]);
  });

  test('the key is sent as typed, the body is the one hashed, a throwing call is a lost answer', async () => {
    const store = draftStore();
    const result = await submitCheckout({
      call: async () => {
        throw new Error('boom');
      },
      draftStore: store,
      quoteBody: {},
      quote: quote(),
      fresh: true,
    });

    expect(result.ok).toBe(false);
    expect(result.code).toBe('NETWORK');
    expect(result.key).toMatch(UUID4);
    expect(result.body.expectedTotal).toBe(25.5);

    const odd = await submitCheckout({
      call: async () => null,
      draftStore: store,
      quoteBody: {},
      quote: quote(),
      fresh: true,
    });
    expect(odd).toMatchObject({ ok: false, code: 'NETWORK' });
  });

  test('a quote that is not fresh never becomes a body: nothing is sent, nothing is stored', async () => {
    const store = draftStore();
    const calls = [];
    const base = {
      call: async (...args) => {
        calls.push(args);
        return ok();
      },
      draftStore: store,
      // the buyer lowered the mixed credits to 2, the re-quote failed: the quote still says 10 were applied
      quoteBody: {
        items: [{ productId: 1, quantity: 1 }],
        useCredits: 2,
        paymentMethodId: 'stripe',
      },
      quote: quote({ credits: { enabled: true, applied: 10, maxApplicable: 40 } }),
      config: {},
    };

    for (const fresh of [undefined, false, 'yes', null]) {
      const out = await submitCheckout({ ...base, fresh });

      expect(out).toMatchObject({ ok: false, code: 'STALE_QUOTE', body: null, key: null });
    }

    expect(calls).toHaveLength(0);
    expect(store.get()).toEqual({ idempotencyKey: null, bodyHash: null });

    // a fresh quote of the same input builds the body from its own applied credits
    const sent = await submitCheckout({ ...base, fresh: true });
    expect(sent.ok).toBe(true);
    expect(calls[0][2].body.useCredits).toBe(10);
  });

  test('NETWORK then retry replays the identical body and key with no quote call in between', async () => {
    const store = draftStore();
    const calls = [];
    const call = async (method, path, options) => {
      calls.push({ method, path, options });
      return calls.length === 1 ? { ok: false, code: 'NETWORK' } : ok();
    };
    const args = {
      call,
      draftStore: store,
      quoteBody: {
        items: [{ productId: 1, quantity: 1 }],
        useCredits: 5,
        paymentMethodId: 'stripe',
      },
      quote: quote({ credits: { enabled: true, applied: 5, maxApplicable: 40 } }),
      config: {},
      accepted: false,
      hide: false,
      fresh: true,
    };
    const signature = 'sig-a';

    const lost = await submitCheckout(args);
    const plan = failurePlan({
      res: lost,
      draft: store.get(),
      context: { origin: 'https://shop.test', base: '' },
    });

    expect(plan.keyPatch).toEqual({});
    // the page holds the quote: the effect that follows the return to IDLE asks nothing for this input
    const hold = quoteHoldAfter(plan.action, signature);
    expect(quoteHeld(hold, signature)).toBe(true);

    const replay = await submitCheckout(args);

    expect(replay.ok).toBe(true);
    expect(calls.every((c) => c.path === CHECKOUT_PATH)).toBe(true);
    expect(calls).toHaveLength(2);
    expect(calls[1].options.body).toEqual(calls[0].options.body);
    expect(calls[1].options.headers[IDEMPOTENCY_HEADER]).toBe(
      calls[0].options.headers[IDEMPOTENCY_HEADER],
    );
  });
});

describe('quote hold after a failed submit', () => {
  const action = (code) => failurePlan({ res: { ok: false, code }, draft: {}, context: {} }).action;

  test.each(['NETWORK', 'STORE_BUSY', 'TOO_MANY_REQUESTS', 'INVALID_CSRF_TOKEN'])(
    '%s keeps the key, so the quote is held for that input',
    (code) => {
      expect(quoteHoldAfter(action(code), 'sig')).toBe('sig');
    },
  );

  test.each([
    'PRICE_CHANGED',
    'INVALID_CART',
    'OUT_OF_STOCK',
    'IDEMPOTENCY_CONFLICT',
    'SOMETHING_NEW',
  ])('%s drops the key and is not held', (code) => {
    expect(quoteHoldAfter(action(code), 'sig')).toBeNull();
  });

  test('a hold only suppresses the input it was set for; any change releases it', () => {
    expect(quoteHeld('sig', 'sig')).toBe(true);
    expect(quoteHeld('sig', 'sig-2')).toBe(false);
    expect(quoteHeld(null, 'sig')).toBe(false);
    expect(quoteHeld('', '')).toBe(false);
    expect(quoteHoldAfter(action('NETWORK'), null)).toBeNull();
  });
});

describe('saveOrderToken', () => {
  const storage = () => {
    const map = new Map();
    return { map, setItem: (k, v) => map.set(k, v) };
  };

  test('writes pano-plugin-market-order:<publicId>', () => {
    const s = storage();

    expect(saveOrderToken(s, 'ord123', 'secret')).toBe(true);
    expect(s.map.get(`${ORDER_TOKEN_PREFIX}ord123`)).toBe('secret');
    expect(ORDER_TOKEN_PREFIX).toBe('pano-plugin-market-order:');
  });

  test.each([
    [null, 'a', 't'],
    [storage(), '', 't'],
    [storage(), 'a', ''],
    [storage(), 7, 't'],
    [storage(), 'a', null],
  ])('ignores %#', (s, id, token) => {
    expect(saveOrderToken(s, id, token)).toBe(false);
    if (s) expect(s.map.size).toBe(0);
  });

  test('a storage that throws is not an error', () => {
    const throwing = {
      setItem: () => {
        throw new Error('quota');
      },
    };

    expect(saveOrderToken(throwing, 'a', 't')).toBe(false);
  });
});

describe('successPlan', () => {
  const ctx = { origin: 'https://shop.test', base: '' };
  const draft = (extra = {}) => ({
    saveAddress: false,
    shippingAddressId: null,
    shippingAddress: { firstName: ' Ada ', country: 'tr', city: 'Izmir', line1: 'Street 1' },
    ...extra,
  });

  test('stores the token, clears draft and cart, follows the payment', () => {
    const plan = successPlan({ res: ok(), loggedIn: true, draft: draft(), context: ctx });

    expect(plan.token).toEqual({ publicId: 'ord123', value: 'tok-secret' });
    expect(plan.clearDraft).toBe(true);
    expect(plan.clearCart).toBe(true);
    expect(plan.saveAddress).toBeNull();
    expect(plan.navigation).toEqual({ type: 'ASSIGN', url: 'https://pay.test/s' });
  });

  test('an in-page payment goes to the order page', () => {
    const plan = successPlan({
      res: ok({ payment: { kind: 'IFRAME' } }),
      draft: draft(),
      context: ctx,
    });

    expect(plan.navigation).toEqual({ type: 'GOTO', path: '/store/order/ord123' });
  });

  test('a javascript: redirect never leaves the site', () => {
    const plan = successPlan({
      res: ok({ payment: { kind: 'REDIRECT', url: 'javascript:alert(1)' } }),
      draft: draft(),
      context: ctx,
    });

    expect(plan.navigation).toEqual({ type: 'GOTO', path: '/store/order/ord123' });
  });

  test('a top-up leaves the cart alone and saves no address', () => {
    const plan = successPlan({
      res: ok(),
      topup: 10,
      loggedIn: true,
      draft: draft({ saveAddress: true }),
      context: ctx,
    });

    expect(plan.clearCart).toBe(false);
    expect(plan.saveAddress).toBeNull();
  });

  test('the typed address is saved for a logged-in buyer who ticked the box, trimmed', () => {
    const plan = successPlan({
      res: ok(),
      loggedIn: true,
      draft: draft({ saveAddress: true }),
      context: ctx,
    });

    expect(plan.saveAddress).toEqual({
      firstName: 'Ada',
      country: 'TR',
      city: 'Izmir',
      line1: 'Street 1',
    });
  });

  test.each([
    ['a guest', { loggedIn: false, draft: draft({ saveAddress: true }) }],
    ['the box unticked', { loggedIn: true, draft: draft({ saveAddress: false }) }],
    [
      'a saved address chosen',
      { loggedIn: true, draft: draft({ saveAddress: true, shippingAddressId: 4 }) },
    ],
  ])('no address is saved for %s', (_, over) => {
    expect(successPlan({ res: ok(), context: ctx, ...over }).saveAddress).toBeNull();
  });

  test('no token is stored when the answer has none', () => {
    expect(
      successPlan({ res: ok({ orderToken: undefined }), draft: draft(), context: ctx }).token,
    ).toBeNull();
  });
});

describe('failurePlan', () => {
  const ctx = { origin: 'https://shop.test', base: '' };
  const plan = (res, extra = {}) =>
    failurePlan({
      res,
      draft: { saveAddress: false, shippingAddress: {} },
      context: ctx,
      ...extra,
    });

  test('PRICE_CHANGED drops the key and hash and carries the fresh quote', () => {
    const fresh = { total: 30 };
    const result = plan({ ok: false, code: 'PRICE_CHANGED', quote: fresh });

    expect(result.action.kind).toBe('PRICE_CHANGED');
    expect(result.action.details.quote).toBe(fresh);
    expect(result.keyPatch).toEqual({ idempotencyKey: null, bodyHash: null });
    expect(result.success).toBeNull();
  });

  test('NETWORK, STORE_BUSY and TOO_MANY_REQUESTS keep the key', () => {
    for (const code of ['NETWORK', 'STORE_BUSY', 'TOO_MANY_REQUESTS'])
      expect(plan({ ok: false, code }).keyPatch).toEqual({});
  });

  test('IDEMPOTENCY_CONFLICT and unknown codes drop the key', () => {
    expect(plan({ ok: false, code: 'IDEMPOTENCY_CONFLICT' }).keyPatch).toEqual({
      idempotencyKey: null,
      bodyHash: null,
    });
    expect(plan({ ok: false, code: 'SOMETHING_NEW' }).keyPatch).toEqual({
      idempotencyKey: null,
      bodyHash: null,
    });
  });

  test('a missing code reads as a lost answer', () => {
    expect(plan(undefined).action.kind).toBe('NETWORK');
    expect(plan({}).action.kind).toBe('NETWORK');
  });

  test('PAYMENT_PROVIDER_ERROR with an order is a success for storage and goes to the order page', () => {
    const result = plan({
      ok: false,
      code: 'PAYMENT_PROVIDER_ERROR',
      order: { publicId: 'ord9' },
      orderToken: 'tok9',
    });

    expect(result.action.kind).toBe('ORDER_CREATED');
    expect(result.success.token).toEqual({ publicId: 'ord9', value: 'tok9' });
    expect(result.success.clearDraft).toBe(true);
    expect(result.success.navigation).toEqual({ type: 'GOTO', path: '/store/order/ord9' });
  });

  test('PAYMENT_PROVIDER_ERROR without an order is a generic failure that drops the key', () => {
    const result = plan({ ok: false, code: 'PAYMENT_PROVIDER_ERROR' });

    expect(result.success).toBeNull();
    expect(result.action.kind).toBe('GENERIC');
    expect(result.keyPatch).toEqual({ idempotencyKey: null, bodyHash: null });
  });

  test('a provider error for a top-up does not clear the cart', () => {
    const result = plan(
      { ok: false, code: 'PAYMENT_PROVIDER_ERROR', order: { publicId: 'o' }, orderToken: 't' },
      { topup: 5 },
    );

    expect(result.success.clearCart).toBe(false);
  });
});
