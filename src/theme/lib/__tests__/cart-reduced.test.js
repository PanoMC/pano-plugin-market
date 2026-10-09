import { describe, expect, test } from 'bun:test';
import { get } from 'svelte/store';
import './sdkMocks.js';
import { STORAGE_KEY } from '../cartModel.js';
import { lineKey } from '../lineKey.js';
import { metaRows, quoteRows } from '../../components/cart/cartView.js';

const { createCartStore } = await import('../cartEngine.js');

// The browser scenario TH-12 (E2E-16): the stock of a product drops while a visitor's cart holds 3. The real quote answers
// `quantity: 3, maxQuantity: 1, errors: ['MAX_QUANTITY']`; the theme clamps the line to 1, which makes that quote stale, so the
// offcanvas fell back to the meta rows and the buyer was never told why the quantity changed.
function fakeStorage(initial = {}) {
  const data = new Map(Object.entries(initial));

  return {
    data,
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => data.set(k, String(v)),
    removeItem: (k) => data.delete(k),
  };
}

const line = (productId, quantity) => ({
  productId,
  variantId: 0,
  quantity,
  fieldValues: {},
  targetServerId: null,
});

const quoteLine = (productId, extra) => ({
  productId,
  variantId: 0,
  fieldValues: null,
  targetServerId: null,
  kind: 'PRODUCT',
  name: `P${productId}`,
  slug: `p${productId}`,
  unitPrice: 3,
  quantity: 1,
  maxQuantity: 99,
  billingMode: 'ONE_TIME',
  errors: [],
  ...extra,
});

function setup(items, answers) {
  const local = fakeStorage({ [STORAGE_KEY]: JSON.stringify({ v: 2, items }) });
  const queue = [...answers];
  const store = createCartStore({
    call: async () => queue.shift() ?? { ok: false, code: 'NETWORK' },
    getUser: () => null,
    getCurrency: () => undefined,
    getLocale: () => 'en-US',
    local: () => local,
    session: () => fakeStorage(),
    toast: () => {},
    now: () => 5_000,
    onMarketPath: () => true,
    timing: { debounce: 0, sleep: async () => {} },
  });

  return { store, local, state: () => get(store) };
}

describe('a quantity the theme lowered is announced', () => {
  test('the clamped line carries a QUANTITY_REDUCED notice although its quote is stale', async () => {
    const quote = {
      lines: [quoteLine(1, { quantity: 3, maxQuantity: 1, errors: ['MAX_QUANTITY'] })],
      messages: [],
    };
    const { store, state, local } = setup([line(1, 3)], [{ ok: true, quote }]);

    await store.init();
    expect(state().reduced).toEqual([]);
    await store.requestQuote();

    expect(state().lines[0].quantity).toBe(1);
    expect(JSON.parse(local.data.get(STORAGE_KEY)).items[0].quantity).toBe(1);
    expect(state().quoteStale).toBe(true);
    expect(state().reduced).toEqual([lineKey(line(1, 1))]);

    const rows = metaRows(state().lines, state().reduced);
    expect(rows[0].errors).toEqual(['QUANTITY_REDUCED']);
    // an untouched line has no notice
    expect(metaRows([line(2, 1)], state().reduced)[0].errors).toEqual([]);
  });

  test('the notice stays on the fresh quote rows and is not doubled when the server says it too', async () => {
    const stale = {
      lines: [quoteLine(1, { quantity: 3, maxQuantity: 1, errors: ['MAX_QUANTITY'] })],
      messages: [],
    };
    const fresh = { lines: [quoteLine(1, { quantity: 1, maxQuantity: 1 })], messages: [] };
    const { store, state } = setup(
      [line(1, 3)],
      [
        { ok: true, quote: stale },
        { ok: true, quote: fresh },
      ],
    );

    await store.init();
    await store.requestQuote();
    await store.requestQuote();

    expect(state().quoteStale).toBe(false);
    expect(state().reduced).toHaveLength(1);
    expect(quoteRows(state().quote, state().reduced)[0].errors).toEqual(['QUANTITY_REDUCED']);

    const told = { lines: [quoteLine(1, { errors: ['QUANTITY_REDUCED'] })] };
    expect(quoteRows(told, [lineKey(quoteLine(1, {}))])[0].errors).toEqual(['QUANTITY_REDUCED']);
  });

  test('the notice goes when the buyer changes the cart', async () => {
    const quote = {
      lines: [quoteLine(1, { quantity: 3, maxQuantity: 2, errors: ['MAX_QUANTITY'] })],
      messages: [],
    };
    const { store, state } = setup([line(1, 3)], [{ ok: true, quote }]);

    await store.init();
    await store.requestQuote();
    expect(state().reduced).toHaveLength(1);

    await store.setQuantity(lineKey(line(1, 2)), 1);
    expect(state().reduced).toEqual([]);
  });

  test('a quote that needs no clamp announces nothing', async () => {
    const quote = { lines: [quoteLine(1, { quantity: 2, maxQuantity: 5 })], messages: [] };
    const { store, state } = setup([line(1, 2)], [{ ok: true, quote }]);

    await store.init();
    await store.requestQuote();

    expect(state().reduced).toEqual([]);
    expect(quoteRows(state().quote, state().reduced)[0].errors).toEqual([]);
  });
});
