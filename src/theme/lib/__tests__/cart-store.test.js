import { describe, expect, test } from 'bun:test';
import { get } from 'svelte/store';
import './sdkMocks.js';
import { COUNT_KEY, STORAGE_KEY } from '../cartModel.js';
import { lineKey } from '../lineKey.js';

const { createCartStore } = await import('../../stores/cart.js');

function fakeStorage(initial = {}) {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => data.set(k, String(v)),
    removeItem: (k) => data.delete(k),
  };
}

const wireCart = (items, extra = {}) => ({
  ok: true,
  cart: {
    items: items.map((it, i) => ({
      id: 100 + i,
      variantId: 0,
      fieldValues: {},
      targetServerId: null,
      ...it,
    })),
    ...extra,
  },
  quote: { currency: 'USD', lines: [], messages: [], ...extra.quote },
});

const quoteLine = (productId, extra = {}) => ({
  productId,
  variantId: 0,
  fieldValues: null,
  targetServerId: null,
  kind: 'PRODUCT',
  name: `P${productId}`,
  slug: `p${productId}`,
  unitPrice: 2,
  quantity: 1,
  maxQuantity: 99,
  billingMode: 'ONE_TIME',
  errors: [],
  ...extra,
});

// builds a store whose api is a queue of scripted answers; every call is recorded
function setup({ user = null, local = {}, answers = [], locale = 'en-US', currency } = {}) {
  const calls = [];
  const toasts = [];
  const sleeps = [];
  const env = {
    user,
    local: fakeStorage(local),
    session: fakeStorage(),
    answers: [...answers],
    onMarketPath: true,
  };
  const store = createCartStore({
    call: async (method, path, options = {}) => {
      calls.push({ method, path, ...options });
      const next = env.answers.shift();
      return typeof next === 'function'
        ? next({ method, path, ...options })
        : (next ?? { ok: false, code: 'NETWORK' });
    },
    getUser: () => env.user,
    getCurrency: () => currency,
    getLocale: () => locale,
    local: () => env.local,
    session: () => env.session,
    toast: (key) => toasts.push(key),
    now: () => 5_000,
    onMarketPath: () => env.onMarketPath,
    timing: { debounce: 0, sleep: async (ms) => sleeps.push(ms) },
  });
  return { store, env, calls, toasts, sleeps, state: () => get(store) };
}

const stored = (items) => JSON.stringify({ v: 2, items });
const sl = (productId, quantity = 1, extra = {}) => ({
  productId,
  variantId: 0,
  quantity,
  fieldValues: {},
  targetServerId: null,
  ...extra,
});
const tick = () => new Promise((r) => setTimeout(r, 5));
const subscriptionProduct = {
  id: 50,
  name: 'Sub',
  slug: 'sub',
  price: 5,
  billingMode: 'SUBSCRIPTION',
};

describe('initial state', () => {
  test('NONE until init ran: nothing is read from storage', () => {
    const { store, env } = setup({ local: { [STORAGE_KEY]: stored([sl(1)]) } });
    expect(get(store)).toMatchObject({
      mode: 'NONE',
      status: 'IDLE',
      lines: [],
      quote: null,
      count: 0,
      error: null,
    });
    expect(env.local.data.size).toBe(1);
  });

  test('the shared instance starts in NONE after import', async () => {
    const mod = await import('../../stores/cart.js');
    expect(get(mod.cart).mode).toBe('NONE');
  });
});

describe('guest init', () => {
  test('GUEST/IDLE with the stored lines', async () => {
    const { store, state } = setup({ local: { [STORAGE_KEY]: stored([sl(1, 2), sl(2, 1)]) } });
    await store.init();
    expect(state()).toMatchObject({
      mode: 'GUEST',
      status: 'IDLE',
      count: 3,
      quote: null,
      quoteStale: true,
    });
    expect(state().lines).toHaveLength(2);
  });

  test('legacy array is migrated and the normalised form is written back once', async () => {
    const legacy = JSON.stringify([{ productId: 4, quantity: 2 }]);
    const { store, env, state } = setup({ local: { [STORAGE_KEY]: legacy } });
    await store.init();
    expect(state().lines).toEqual([sl(4, 2)]);
    expect(JSON.parse(env.local.data.get(STORAGE_KEY))).toEqual({ v: 2, items: [sl(4, 2)] });
  });

  test('garbage JSON => empty cart', async () => {
    const { store, state } = setup({ local: { [STORAGE_KEY]: '{oops' } });
    await store.init();
    expect(state()).toMatchObject({ mode: 'GUEST', lines: [], count: 0, quoteStale: false });
  });

  test('unavailable storage => empty cart, no throw', async () => {
    const { store, env, state } = setup();
    env.local = {
      getItem: () => {
        throw new Error('denied');
      },
      setItem: () => {
        throw new Error('denied');
      },
    };
    await store.init();
    expect(state().mode).toBe('GUEST');
    await store.add({ productId: 1 }, { name: 'A' });
    expect(state().count).toBe(1);
  });

  test('init is idempotent for the same user and shares a concurrent promise', async () => {
    const { store, calls } = setup({ local: { [STORAGE_KEY]: stored([sl(1)]) } });
    const a = store.init();
    const b = store.init();
    expect(a).toBe(b);
    await a;
    await store.init();
    expect(calls).toHaveLength(0);
  });
});

describe('guest mutations', () => {
  test('add merges by lineKey, caps at 99, persists and toasts', async () => {
    const { store, env, state, toasts } = setup();
    await store.init();
    expect(await store.add({ productId: 1, quantity: 60 }, { name: 'A' })).toBe(true);
    expect(await store.add({ productId: 1, quantity: 60 }, { name: 'A' })).toBe(true);
    expect(state().lines).toHaveLength(1);
    expect(state().lines[0].quantity).toBe(99);
    expect(state().count).toBe(99);
    expect(state().quoteStale).toBe(true);
    expect(toasts).toEqual(['store.added-to-cart', 'store.added-to-cart']);
    expect(JSON.parse(env.local.data.get(STORAGE_KEY)).items[0].quantity).toBe(99);
  });

  test('add keeps display meta and a variant / field line apart', async () => {
    const { store, state } = setup();
    await store.init();
    await store.add(
      { productId: 1, variantId: 3, fieldValues: { n: 'a' } },
      { name: 'A', slug: 'a', price: 4, imageFileName: 'a.png' },
    );
    await store.add({ productId: 1 }, { name: 'A' });
    expect(state().lines).toHaveLength(2);
    expect(state().lines[0].meta).toMatchObject({
      name: 'A',
      slug: 'a',
      price: 4,
      imageFileName: 'a.png',
    });
  });

  test('add rejects an invalid line', async () => {
    const { store, state } = setup();
    await store.init();
    expect(await store.add({ productId: 0 }, {})).toBe(false);
    expect(await store.add({ productId: 1, quantity: 0 }, {})).toBe(false);
    expect(state().count).toBe(0);
  });

  test('add without a prior init initialises first', async () => {
    const { store, state } = setup();
    expect(await store.add({ productId: 2 }, { name: 'B' })).toBe(true);
    expect(state().mode).toBe('GUEST');
    expect(state().count).toBe(1);
  });

  test('a 51st distinct line is refused with the full-cart toast', async () => {
    const lines = Array.from({ length: 50 }, (_, i) => sl(i + 1));
    const { store, toasts, state } = setup({ local: { [STORAGE_KEY]: stored(lines) } });
    await store.init();
    expect(await store.add({ productId: 999 }, {})).toBe(false);
    expect(toasts).toEqual(['cart.too-many-lines']);
    expect(state().lines).toHaveLength(50);
  });

  test('setQuantity, remove, update and clear', async () => {
    const { store, state, env } = setup({ local: { [STORAGE_KEY]: stored([sl(1, 2), sl(2, 1)]) } });
    await store.init();
    await store.setQuantity(lineKey(sl(1)), 5);
    expect(state().lines[0].quantity).toBe(5);
    await store.setQuantity(lineKey(sl(1)), 0);
    expect(state().lines.map((l) => l.productId)).toEqual([2]);
    await store.update(lineKey(sl(2)), { fieldValues: { n: 'x' } });
    expect(state().lines[0].fieldValues).toEqual({ n: 'x' });
    await store.remove(lineKey({ productId: 2, fieldValues: { n: 'x' } }));
    expect(state().count).toBe(0);
    await store.add({ productId: 3 }, {});
    await store.clear();
    expect(state().lines).toEqual([]);
    expect(JSON.parse(env.local.data.get(STORAGE_KEY)).items).toEqual([]);
  });

  test('unknown line keys are ignored', async () => {
    const { store, state } = setup({ local: { [STORAGE_KEY]: stored([sl(1)]) } });
    await store.init();
    expect(await store.remove('nope')).toBe(false);
    expect(await store.setQuantity('nope', 3)).toBe(false);
    expect(state().count).toBe(1);
  });

  test('afterCheckout empties the guest cart and its storage without a request', async () => {
    const { store, state, env, calls } = setup({ local: { [STORAGE_KEY]: stored([sl(1)]) } });
    await store.init();
    store.afterCheckout();
    expect(state()).toMatchObject({ lines: [], count: 0, quote: null });
    expect(JSON.parse(env.local.data.get(STORAGE_KEY)).items).toEqual([]);
    expect(calls).toHaveLength(0);
  });
});

describe('guest quotes', () => {
  test('requestQuote posts the wire items with currency and locale and stores the quote', async () => {
    const quote = { currency: 'EUR', lines: [quoteLine(1, { quantity: 2 })], messages: [] };
    const { store, calls, state } = setup({
      local: {
        [STORAGE_KEY]: stored([
          sl(1, 2, {
            meta: {
              name: 'x',
              variantName: '',
              slug: 'x',
              imageFileName: '',
              price: 1,
              billingMode: 'ONE_TIME',
            },
          }),
        ]),
      },
      answers: [{ ok: true, quote }],
      currency: 'EUR',
      locale: 'tr',
    });
    await store.init();
    expect(await store.requestQuote()).toEqual(quote);
    expect(calls[0]).toMatchObject({
      method: 'POST',
      path: '/api/market/checkout/quote',
      body: { items: [{ productId: 1, quantity: 2 }], currency: 'EUR', locale: 'tr' },
    });
    expect(state().quote).toEqual(quote);
    expect(state().quoteStale).toBe(false);
    expect(state().status).toBe('IDLE');
  });

  test('clamp after quote: quantity set to maxQuantity and persisted, lines kept when unavailable', async () => {
    const quote = {
      lines: [
        quoteLine(1, { quantity: 3, maxQuantity: 3, errors: ['QUANTITY_REDUCED'] }),
        quoteLine(2, { maxQuantity: 0, errors: ['PRODUCT_UNAVAILABLE'] }),
      ],
      messages: [],
    };
    const { store, state, env } = setup({
      local: { [STORAGE_KEY]: stored([sl(1, 8), sl(2, 2)]) },
      answers: [{ ok: true, quote }],
    });
    await store.init();
    await store.requestQuote();
    expect(state().lines.map((l) => [l.productId, l.quantity])).toEqual([
      [1, 3],
      [2, 2],
    ]);
    expect(JSON.parse(env.local.data.get(STORAGE_KEY)).items.map((i) => i.quantity)).toEqual([
      3, 2,
    ]);
    expect(state().quoteStale).toBe(false);
  });

  test('no lines: no request', async () => {
    const { store, calls } = setup();
    await store.init();
    expect(await store.requestQuote()).toBeNull();
    expect(calls).toHaveLength(0);
  });

  test('burst of requests is debounced into one call', async () => {
    const quote = { lines: [quoteLine(1)], messages: [] };
    const { store, calls } = setup({
      local: { [STORAGE_KEY]: stored([sl(1)]) },
      answers: [{ ok: true, quote }],
    });
    await store.init();
    const [a, b, c] = await Promise.all([
      store.requestQuote(),
      store.requestQuote(),
      store.requestQuote(),
    ]);
    expect(calls).toHaveLength(1);
    expect([a, b, c]).toEqual([quote, quote, quote]);
  });

  test('a stale answer is dropped when the lines changed meanwhile', async () => {
    let release;
    const gate = new Promise((r) => (release = r));
    const stale = {
      lines: [quoteLine(1, { quantity: 1, maxQuantity: 1, errors: ['QUANTITY_REDUCED'] })],
      messages: [],
    };
    const { store, state } = setup({
      local: { [STORAGE_KEY]: stored([sl(1, 5)]) },
      answers: [async () => (await gate, { ok: true, quote: stale })],
    });
    await store.init();
    const pending = store.requestQuote();
    await tick();
    await store.add({ productId: 2 }, {});
    release();
    expect(await pending).toBeNull();
    expect(state().quote).toBeNull();
    expect(state().lines.find((l) => l.productId === 1).quantity).toBe(5);
  });

  test('429 waits min(retryAfter, 30) s and retries once', async () => {
    const quote = { lines: [quoteLine(1)], messages: [] };
    const { store, calls, sleeps, state } = setup({
      local: { [STORAGE_KEY]: stored([sl(1)]) },
      answers: [
        { ok: false, code: 'TOO_MANY_REQUESTS', retryAfter: 90 },
        { ok: true, quote },
      ],
    });
    await store.init();
    await store.requestQuote();
    expect(sleeps).toEqual([30000]);
    expect(calls).toHaveLength(2);
    expect(state().quote).toEqual(quote);
  });

  test('429 twice: stale stays true and the error is set', async () => {
    const { store, calls, state } = setup({
      local: { [STORAGE_KEY]: stored([sl(1)]) },
      answers: [
        { ok: false, code: 'TOO_MANY_REQUESTS', retryAfter: 2 },
        { ok: false, code: 'TOO_MANY_REQUESTS', retryAfter: 2 },
      ],
    });
    await store.init();
    await store.requestQuote();
    expect(calls).toHaveLength(2);
    expect(state()).toMatchObject({
      status: 'ERROR',
      error: 'TOO_MANY_REQUESTS',
      quoteStale: true,
    });
  });

  test('a network failure sets ERROR and retry asks again', async () => {
    const quote = { lines: [quoteLine(1)], messages: [] };
    const { store, state, calls } = setup({
      local: { [STORAGE_KEY]: stored([sl(1)]) },
      answers: [
        { ok: false, code: 'NETWORK' },
        { ok: true, quote },
      ],
    });
    await store.init();
    await store.requestQuote();
    expect(state()).toMatchObject({ status: 'ERROR', error: 'NETWORK' });
    await store.retry();
    expect(calls).toHaveLength(2);
    expect(state()).toMatchObject({ status: 'IDLE', error: null });
  });

  test('while watched every change asks for a new quote; unwatching stops it', async () => {
    const quote = { lines: [quoteLine(1)], messages: [] };
    const { store, calls } = setup({
      answers: [
        { ok: true, quote },
        { ok: true, quote },
        { ok: true, quote },
      ],
    });
    await store.init();
    const unwatch = store.watchQuotes();
    await store.add({ productId: 1 }, {});
    await tick();
    expect(calls).toHaveLength(1);
    unwatch();
    unwatch();
    await store.add({ productId: 1 }, {});
    await tick();
    expect(calls).toHaveLength(1);
  });

  test('clampToQuote (checkout page) clamps the guest lines', async () => {
    const { store, state } = setup({ local: { [STORAGE_KEY]: stored([sl(1, 9)]) } });
    await store.init();
    store.clampToQuote({ lines: [quoteLine(1, { quantity: 9, maxQuantity: 4 })] });
    expect(state().lines[0].quantity).toBe(4);
  });
});

describe('subscription replace', () => {
  test('a subscription meeting other content opens the request; confirm replaces the guest cart', async () => {
    const { store, state } = setup({ local: { [STORAGE_KEY]: stored([sl(1, 2)]) } });
    await store.init();
    const result = store.add({ productId: 50 }, subscriptionProduct);
    await tick();
    expect(get(store.replaceRequest)?.line.productId).toBe(50);
    expect(state().lines.map((l) => l.productId)).toEqual([1]);
    await store.confirmReplace();
    expect(await result).toBe(true);
    expect(state().lines.map((l) => l.productId)).toEqual([50]);
    expect(get(store.replaceRequest)).toBeNull();
  });

  test('cancel leaves the cart alone and resolves false', async () => {
    const { store, state } = setup({ local: { [STORAGE_KEY]: stored([sl(1, 2)]) } });
    await store.init();
    const result = store.add({ productId: 50 }, subscriptionProduct);
    await tick();
    store.cancelReplace();
    expect(await result).toBe(false);
    expect(state().lines.map((l) => l.productId)).toEqual([1]);
    expect(get(store.replaceRequest)).toBeNull();
  });

  test('a newer question cancels the older one', async () => {
    const { store } = setup({ local: { [STORAGE_KEY]: stored([sl(1)]) } });
    await store.init();
    const first = store.add({ productId: 50 }, subscriptionProduct);
    await tick();
    const second = store.add({ productId: 51 }, { ...subscriptionProduct, id: 51 });
    await tick();
    expect(await first).toBe(false);
    expect(get(store.replaceRequest).line.productId).toBe(51);
    store.cancelReplace();
    expect(await second).toBe(false);
  });

  test('empty cart: the subscription is added without a question; the same one again is a no-op', async () => {
    const { store, state } = setup();
    await store.init();
    expect(await store.add({ productId: 50 }, subscriptionProduct)).toBe(true);
    expect(get(store.replaceRequest)).toBeNull();
    expect(await store.add({ productId: 50 }, subscriptionProduct)).toBe(true);
    expect(state().lines).toHaveLength(1);
    expect(state().lines[0].quantity).toBe(1);
  });

  test('a product added to a subscription cart asks too', async () => {
    const { store } = setup();
    await store.init();
    await store.add({ productId: 50 }, subscriptionProduct);
    const result = store.add({ productId: 2 }, { name: 'Other', billingMode: 'ONE_TIME' });
    await tick();
    expect(get(store.replaceRequest)?.line.productId).toBe(2);
    store.cancelReplace();
    expect(await result).toBe(false);
  });
});

describe('server mode', () => {
  const user = { id: 7, username: 'Steve' };

  test('logged in, empty browser cart: GET /me/cart with the currency', async () => {
    const res = wireCart([{ productId: 1, quantity: 2 }], {
      couponCode: 'SAVE',
      quote: { lines: [quoteLine(1, { quantity: 2, name: 'VIP' })] },
    });
    const { store, calls, state, env } = setup({ user, answers: [res], currency: 'EUR' });
    const promise = store.init();
    expect(state()).toMatchObject({ mode: 'SERVER', status: 'LOADING' });
    await promise;
    expect(calls).toEqual([
      { method: 'GET', path: '/api/market/me/cart', query: { currency: 'EUR' } },
    ]);
    expect(state()).toMatchObject({ mode: 'SERVER', status: 'IDLE', count: 2, quoteStale: false });
    expect(state().lines[0]).toMatchObject({ itemId: 100, productId: 1, quantity: 2 });
    expect(state().lines[0].meta.name).toBe('VIP');
    expect(state().codes).toMatchObject({ couponCode: 'SAVE', creatorCode: null });
    expect(JSON.parse(env.session.data.get(COUNT_KEY))).toEqual({ userId: 7, count: 2, at: 5000 });
  });

  test('local items: POST /me/cart/merge without meta, then the browser cart is cleared', async () => {
    const meta = {
      name: 'x',
      variantName: '',
      slug: 'x',
      imageFileName: '',
      price: 1,
      billingMode: 'ONE_TIME',
    };
    const res = wireCart([{ productId: 1, quantity: 3 }]);
    const { store, calls, state, env, toasts } = setup({
      user,
      local: { [STORAGE_KEY]: stored([sl(1, 3, { meta, fieldValues: { a: 'b' } })]) },
      answers: [res],
    });
    await store.init();
    expect(calls).toHaveLength(1);
    expect(calls[0]).toMatchObject({
      method: 'POST',
      path: '/api/market/me/cart/merge',
      body: { items: [{ productId: 1, quantity: 3, fieldValues: { a: 'b' } }] },
    });
    expect(JSON.stringify(calls[0].body)).not.toContain('meta');
    expect(state()).toMatchObject({ mode: 'SERVER', status: 'IDLE' });
    expect(JSON.parse(env.local.data.get(STORAGE_KEY)).items).toEqual([]);
    expect(toasts).toEqual([]);
  });

  test('merge messages with a code show one toast', async () => {
    const res = wireCart([{ productId: 1, quantity: 1 }], {
      quote: { messages: [{ code: 'PRODUCT_UNAVAILABLE', level: 'warning' }, { code: 'X' }] },
    });
    const { store, toasts } = setup({
      user,
      local: { [STORAGE_KEY]: stored([sl(1), sl(2)]) },
      answers: [res],
    });
    await store.init();
    expect(toasts).toEqual(['cart.merge-dropped']);
  });

  test('merge failure keeps the local items (shown) and the storage, status ERROR; Retry loads again', async () => {
    const res = wireCart([{ productId: 1, quantity: 1 }]);
    const { store, state, env, calls } = setup({
      user,
      local: { [STORAGE_KEY]: stored([sl(1)]) },
      answers: [{ ok: false, code: 'NETWORK' }, res],
    });
    await store.init();
    expect(state()).toMatchObject({ mode: 'SERVER', status: 'ERROR', error: 'NETWORK', count: 1 });
    expect(JSON.parse(env.local.data.get(STORAGE_KEY)).items).toHaveLength(1);
    await store.retry();
    expect(calls[1].path).toBe('/api/market/me/cart/merge');
    expect(state()).toMatchObject({ status: 'IDLE', error: null });
    expect(JSON.parse(env.local.data.get(STORAGE_KEY)).items).toEqual([]);
  });

  test('add: POST /me/cart/items, lines and quote replaced by the response, toast on success', async () => {
    const { store, state, calls, toasts } = setup({
      user,
      answers: [
        wireCart([]),
        wireCart([{ productId: 1, quantity: 2 }], {
          quote: { lines: [quoteLine(1, { quantity: 2 })] },
        }),
      ],
    });
    await store.init();
    expect(await store.add({ productId: 1, quantity: 2 }, { name: 'A' })).toBe(true);
    expect(calls[1]).toMatchObject({
      method: 'POST',
      path: '/api/market/me/cart/items',
      body: { productId: 1, quantity: 2 },
    });
    expect(state()).toMatchObject({ status: 'IDLE', count: 2 });
    expect(toasts).toEqual(['store.added-to-cart']);
  });

  test('a failed mutation leaves the lines, sets ERROR, error toast; init retries', async () => {
    const { store, state, toasts, calls } = setup({
      user,
      answers: [
        wireCart([{ productId: 1, quantity: 1 }]),
        { ok: false, code: 'INVALID_CART' },
        wireCart([{ productId: 1, quantity: 1 }]),
      ],
    });
    await store.init();
    expect(await store.add({ productId: 2 }, {})).toBe(false);
    expect(state()).toMatchObject({ status: 'ERROR', error: 'INVALID_CART', count: 1 });
    expect(toasts).toEqual(['errors.GENERIC']);
    await store.init();
    expect(calls[2].method).toBe('GET');
    expect(state().status).toBe('IDLE');
  });

  test('setQuantity / remove / clear use the server item id', async () => {
    const two = [
      { productId: 1, quantity: 1 },
      { productId: 2, quantity: 1 },
    ];
    const { store, calls, state } = setup({
      user,
      answers: [
        wireCart(two),
        wireCart(two),
        wireCart([{ productId: 1, quantity: 1 }]),
        wireCart([]),
      ],
    });
    await store.init();
    await store.setQuantity(lineKey(sl(2)), 4);
    expect(calls[1]).toMatchObject({
      method: 'PUT',
      path: '/api/market/me/cart/items/101',
      body: { quantity: 4 },
    });
    await store.remove(lineKey(sl(2)));
    expect(calls[2]).toMatchObject({ method: 'DELETE', path: '/api/market/me/cart/items/101' });
    await store.clear();
    expect(calls[3]).toMatchObject({ method: 'DELETE', path: '/api/market/me/cart' });
    expect(state().count).toBe(0);
  });

  test('mutations are serialised: a second call waits for the first', async () => {
    let release;
    const gate = new Promise((r) => (release = r));
    const { store, calls } = setup({
      user,
      answers: [
        wireCart([]),
        async () => (await gate, wireCart([{ productId: 1, quantity: 1 }])),
        wireCart([
          { productId: 1, quantity: 1 },
          { productId: 2, quantity: 1 },
        ]),
      ],
    });
    await store.init();
    const first = store.add({ productId: 1 }, {});
    const second = store.add({ productId: 2 }, {});
    await tick();
    expect(calls).toHaveLength(2);
    release();
    await Promise.all([first, second]);
    expect(calls).toHaveLength(3);
    expect(calls[2].body.productId).toBe(2);
  });

  test('subscription replace in server mode PUTs the complete cart with codes and currency', async () => {
    const { store, calls, state } = setup({
      user,
      currency: 'EUR',
      answers: [
        wireCart([{ productId: 1, quantity: 2 }], {
          couponCode: 'SAVE',
          recipientUsername: 'Alex',
        }),
        wireCart([{ productId: 50, quantity: 1 }], {
          couponCode: 'SAVE',
          recipientUsername: 'Alex',
        }),
      ],
    });
    await store.init();
    const result = store.add({ productId: 50 }, subscriptionProduct);
    await tick();
    await store.confirmReplace();
    expect(await result).toBe(true);
    expect(calls[1]).toMatchObject({ method: 'PUT', path: '/api/market/me/cart' });
    expect(calls[1].body).toEqual({
      items: [{ productId: 50, quantity: 1 }],
      couponCode: 'SAVE',
      recipientUsername: 'Alex',
      currency: 'EUR',
    });
    expect(state().lines.map((l) => l.productId)).toEqual([50]);
  });

  test('putCart sends the complete cart; setCurrency re-prices the server cart', async () => {
    const { store, calls } = setup({
      user,
      answers: [
        wireCart([{ productId: 1, quantity: 1 }], { couponCode: 'C' }),
        wireCart([{ productId: 1, quantity: 1 }], { couponCode: 'C' }),
        wireCart([{ productId: 1, quantity: 1 }], { couponCode: 'C' }),
      ],
    });
    await store.init();
    await store.putCart({ shippingMethodId: 'fast', creatorCode: 'YT' });
    expect(calls[1].body).toEqual({
      items: [{ productId: 1, quantity: 1 }],
      couponCode: 'C',
      creatorCode: 'YT',
      shippingMethodId: 'fast',
    });
    await store.setCurrency('USD');
    expect(calls[2].body).toMatchObject({
      currency: 'USD',
      shippingMethodId: 'fast',
      couponCode: 'C',
    });
  });

  test('requestQuote in server mode does not call the API', async () => {
    const quote = { currency: 'USD', lines: [quoteLine(1)], messages: [] };
    const { store, calls } = setup({
      user,
      answers: [{ ok: true, cart: { items: [{ id: 1, productId: 1, quantity: 1 }] }, quote }],
    });
    await store.init();
    expect(await store.requestQuote()).toEqual(quote);
    expect(calls).toHaveLength(1);
  });

  test('afterCheckout empties the server cart locally without a request', async () => {
    const { store, calls, state } = setup({
      user,
      answers: [wireCart([{ productId: 1, quantity: 1 }])],
    });
    await store.init();
    store.afterCheckout();
    expect(state()).toMatchObject({ lines: [], count: 0, quote: null });
    expect(calls).toHaveLength(1);
  });
});

describe('login and logout', () => {
  test('login after guest use runs the merge path', async () => {
    const res = wireCart([{ productId: 1, quantity: 1 }]);
    const { store, env, calls, state } = setup({ answers: [res] });
    await store.init();
    await store.add({ productId: 1 }, { name: 'A' });
    env.user = { id: 3, username: 'Alex' };
    await store.init();
    expect(calls[0].path).toBe('/api/market/me/cart/merge');
    expect(state().mode).toBe('SERVER');
  });

  test('logout goes back to GUEST with an empty cart (the server cart is not copied)', async () => {
    const { store, env, state } = setup({
      user: { id: 3, username: 'Alex' },
      answers: [wireCart([{ productId: 1, quantity: 2 }])],
    });
    await store.init();
    expect(state().count).toBe(2);
    env.user = null;
    await store.init();
    expect(state()).toMatchObject({ mode: 'GUEST', lines: [], count: 0, codes: null });
    expect(JSON.parse(env.local.data.get(STORAGE_KEY) ?? '{"items":[]}').items).toEqual([]);
  });

  test('a different user re-inits; a late answer of the previous user is discarded', async () => {
    let release;
    const gate = new Promise((r) => (release = r));
    const { store, env, state } = setup({
      user: { id: 1, username: 'A' },
      answers: [
        async () => (await gate, wireCart([{ productId: 1, quantity: 5 }])),
        wireCart([{ productId: 2, quantity: 1 }]),
      ],
    });
    const first = store.init();
    env.user = { id: 2, username: 'B' };
    const second = store.init();
    expect(first).not.toBe(second);
    await second;
    release();
    await first;
    expect(state().lines.map((l) => l.productId)).toEqual([2]);
  });
});

describe('autoInit (session hook)', () => {
  test('on a market page it initialises', async () => {
    const { store, state } = setup();
    await store.autoInit();
    expect(state().mode).toBe('GUEST');
  });

  test('elsewhere it stays lazy until the cart is live', async () => {
    const { store, env, state, calls } = setup({
      user: { id: 1, username: 'A' },
      answers: [wireCart([])],
    });
    env.onMarketPath = false;
    await store.autoInit();
    expect(state().mode).toBe('NONE');
    expect(calls).toHaveLength(0);
    await store.init();
    expect(state().mode).toBe('SERVER');
    env.user = null;
    await store.autoInit();
    expect(state().mode).toBe('GUEST');
  });
});

describe('reset', () => {
  test('returns to NONE and drops a pending question', async () => {
    const { store, state } = setup({ local: { [STORAGE_KEY]: stored([sl(1)]) } });
    await store.init();
    const pending = store.add({ productId: 50 }, subscriptionProduct);
    await tick();
    store.reset();
    expect(await pending).toBe(false);
    expect(state().mode).toBe('NONE');
    expect(get(store.replaceRequest)).toBeNull();
  });
});

describe('toast keys', () => {
  test('every toast key the store can raise exists in the three theme locales', async () => {
    const { readFileSync } = await import('node:fs');
    const keys = [
      'store.added-to-cart',
      'cart.merge-dropped',
      'cart.too-many-lines',
      'errors.NETWORK',
      'errors.GENERIC',
    ];

    for (const lang of ['en-US', 'tr', 'ru']) {
      const json = JSON.parse(
        readFileSync(new URL(`../../../locales/theme/${lang}.json`, import.meta.url), 'utf8'),
      );
      for (const key of keys) {
        const value = key.split('.').reduce((o, part) => o?.[part], json.theme);
        expect(typeof value, `${lang}: theme.${key}`).toBe('string');
        expect(value.length).toBeGreaterThan(0);
      }
    }
  });
});
