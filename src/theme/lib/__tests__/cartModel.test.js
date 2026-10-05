import { describe, expect, test } from 'bun:test';
import {
  MAX_LINES,
  addLine,
  applyQuote,
  buildMeta,
  countOf,
  hasMessageCodes,
  linesFromServer,
  mergeLines,
  parseCountCache,
  parseStored,
  removeLine,
  replaceDecision,
  serialize,
  toWire,
  updateLine,
} from '../cartModel.js';
import { lineKey } from '../lineKey.js';

const line = (productId, quantity = 1, extra = {}) => ({
  productId,
  variantId: 0,
  quantity,
  fieldValues: {},
  targetServerId: null,
  ...extra,
});
const stored = (items) => JSON.stringify({ v: 2, items });

describe('parseStored', () => {
  test('legacy array is migrated to version 2', () => {
    const r = parseStored(
      JSON.stringify([
        { productId: 4, quantity: 2 },
        { productId: 5, quantity: 1 },
      ]),
    );
    expect(r.v).toBe(2);
    expect(r.items).toEqual([
      { productId: 4, variantId: 0, quantity: 2, fieldValues: {}, targetServerId: null },
      { productId: 5, variantId: 0, quantity: 1, fieldValues: {}, targetServerId: null },
    ]);
  });

  test('legacy items: floats floored, zero / negative / non-numeric dropped, no meta', () => {
    const r = parseStored(
      JSON.stringify([
        { productId: 1, quantity: 2.9 },
        { productId: 2, quantity: 0 },
        { productId: 3, quantity: -1 },
        { productId: 4, quantity: 0.5 },
        { productId: '5', quantity: 1 },
        { productId: 6, quantity: '1' },
        null,
        { productId: 7, quantity: 1 },
      ]),
    );
    expect(r.items.map((i) => [i.productId, i.quantity])).toEqual([
      [1, 2],
      [7, 1],
    ]);
    expect(r.items.every((i) => !('meta' in i))).toBe(true);
  });

  test('legacy duplicates are merged', () => {
    const r = parseStored(
      JSON.stringify([
        { productId: 1, quantity: 2 },
        { productId: 1, quantity: 3 },
      ]),
    );
    expect(r.items).toHaveLength(1);
    expect(r.items[0].quantity).toBe(5);
  });

  test.each([
    null,
    undefined,
    '',
    'not json',
    '{',
    '12',
    'null',
    'true',
    '"x"',
    '{"v":1,"items":[]}',
    '{"v":3}',
    '{}',
  ])('garbage %p => empty cart', (raw) => {
    expect(parseStored(raw)).toEqual({ v: 2, items: [] });
  });

  test('non-string input => empty cart', () => {
    expect(parseStored({ v: 2, items: [line(1)] })).toEqual({ v: 2, items: [] });
  });

  test('version 2: valid items kept with meta', () => {
    const meta = {
      name: 'VIP',
      variantName: 'Gold',
      slug: 'vip',
      imageFileName: 'a.png',
      price: 9.5,
      billingMode: 'TIMED',
    };
    const r = parseStored(
      stored([line(1, 2, { variantId: 3, fieldValues: { a: 'x' }, targetServerId: 8, meta })]),
    );
    expect(r.items).toEqual([
      line(1, 2, { variantId: 3, fieldValues: { a: 'x' }, targetServerId: 8, meta }),
    ]);
  });

  test('version 2: invalid members drop the item', () => {
    const bad = [
      line(0),
      line(-1),
      line(1.5),
      line('1'),
      line(1, 0),
      line(1, -2),
      line(1, 1.5),
      line(1, 100),
      line(1, 1, { variantId: -1 }),
      line(1, 1, { variantId: 1.5 }),
      line(1, 1, { fieldValues: [] }),
      line(1, 1, { fieldValues: 'x' }),
      line(1, 1, { fieldValues: { a: { nested: 1 } } }),
      line(1, 1, { targetServerId: '3' }),
      line(1, 1, { targetServerId: 1.5 }),
      null,
      'x',
    ];
    expect(parseStored(stored([...bad, line(9)])).items.map((i) => i.productId)).toEqual([9]);
  });

  test('version 2: fieldValues with more than 20 keys drop the item, 20 are kept', () => {
    const many = (n) => Object.fromEntries(Array.from({ length: n }, (_, i) => [`k${i}`, 'v']));
    expect(parseStored(stored([line(1, 1, { fieldValues: many(21) })])).items).toEqual([]);
    expect(parseStored(stored([line(1, 1, { fieldValues: many(20) })])).items).toHaveLength(1);
  });

  test('version 2: missing variantId, fieldValues and targetServerId default', () => {
    const r = parseStored(JSON.stringify({ v: 2, items: [{ productId: 1, quantity: 1 }] }));
    expect(r.items).toEqual([line(1)]);
  });

  test('version 2: duplicates (equal lineKey) are merged and capped at 99', () => {
    const r = parseStored(stored([line(1, 60), line(1, 60), line(2, 1)]));
    expect(r.items.map((i) => [i.productId, i.quantity])).toEqual([
      [1, 99],
      [2, 1],
    ]);
  });

  test('version 2: lines differing in fieldValues are not merged', () => {
    const r = parseStored(
      stored([line(1, 1, { fieldValues: { n: 'a' } }), line(1, 1, { fieldValues: { n: 'b' } })]),
    );
    expect(r.items).toHaveLength(2);
  });

  test('more than 50 lines are truncated', () => {
    const items = Array.from({ length: 70 }, (_, i) => line(i + 1));
    const r = parseStored(stored(items));
    expect(r.items).toHaveLength(MAX_LINES);
    expect(r.items[49].productId).toBe(50);
  });

  test('a corrupt meta is dropped or normalised, never trusted', () => {
    const r = parseStored(
      stored([
        line(1, 1, { meta: 'x' }),
        line(2, 1, { meta: { name: 5, price: 'free', billingMode: 'EVIL' } }),
      ]),
    );
    expect('meta' in r.items[0]).toBe(false);
    expect(r.items[1].meta).toEqual({
      name: '',
      variantName: '',
      slug: '',
      imageFileName: '',
      price: 0,
      billingMode: 'ONE_TIME',
    });
  });

  test('serialize round-trips through parseStored and drops runtime keys', () => {
    const lines = [
      line(1, 2, {
        itemId: 77,
        meta: buildMeta({ name: 'A', slug: 'a', price: 3, billingMode: 'ONE_TIME' }),
      }),
    ];
    const text = serialize(lines);
    expect(text).not.toContain('itemId');
    expect(parseStored(text).items).toEqual(
      [{ ...lines[0], itemId: undefined }].map(({ itemId, ...rest }) => rest),
    );
  });
});

describe('toWire', () => {
  test('meta and local keys are stripped, optional members only when set', () => {
    const meta = buildMeta({ name: 'A' });
    expect(toWire(line(1, 2, { meta, itemId: 5 }))).toEqual({ productId: 1, quantity: 2 });
    expect(
      toWire(line(1, 2, { variantId: 4, fieldValues: { a: 'x' }, targetServerId: 9, meta })),
    ).toEqual({
      productId: 1,
      quantity: 2,
      variantId: 4,
      fieldValues: { a: 'x' },
      targetServerId: 9,
    });
  });
});

describe('line operations', () => {
  test('addLine merges an identical line and caps at 99', () => {
    const r = addLine([line(1, 98)], line(1, 5));
    expect(r.lines).toEqual([line(1, 99)]);
    expect(r.full).toBe(false);
  });

  test('addLine appends a different line', () => {
    expect(addLine([line(1)], line(2)).lines.map((l) => l.productId)).toEqual([1, 2]);
  });

  test('addLine refuses a 51st line but still merges into an existing one', () => {
    const lines = Array.from({ length: MAX_LINES }, (_, i) => line(i + 1));
    expect(addLine(lines, line(999)).full).toBe(true);
    expect(addLine(lines, line(999)).lines).toBe(lines);
    expect(addLine(lines, line(1)).full).toBe(false);
  });

  test('addLine does not mutate its input', () => {
    const input = [line(1, 1)];
    addLine(input, line(1, 1));
    expect(input[0].quantity).toBe(1);
  });

  test('updateLine sets, clamps and removes', () => {
    const key = lineKey(line(1));
    expect(updateLine([line(1, 1)], key, { quantity: 4 })[0].quantity).toBe(4);
    expect(updateLine([line(1, 1)], key, { quantity: 500 })[0].quantity).toBe(99);
    expect(updateLine([line(1, 1)], key, { quantity: 0 })).toEqual([]);
    expect(updateLine([line(1, 1)], key, { quantity: -3 })).toEqual([]);
  });

  test('updateLine on an unknown key changes nothing', () => {
    const lines = [line(1)];
    expect(updateLine(lines, 'nope', { quantity: 3 })).toBe(lines);
  });

  test('updateLine making two lines identical merges them', () => {
    const a = line(1, 2, { fieldValues: { n: 'a' } });
    const b = line(1, 3, { fieldValues: { n: 'b' } });
    const merged = updateLine([a, b], lineKey(b), { fieldValues: { n: 'a' } });
    expect(merged).toHaveLength(1);
    expect(merged[0].quantity).toBe(5);
  });

  test('removeLine and countOf', () => {
    const lines = [line(1, 2), line(2, 3)];
    expect(removeLine(lines, lineKey(line(1))).map((l) => l.productId)).toEqual([2]);
    expect(countOf(lines)).toBe(5);
    expect(countOf([])).toBe(0);
  });

  test('mergeLines does not alter inputs', () => {
    const a = line(1, 1);
    mergeLines([a, line(1, 1)]);
    expect(a.quantity).toBe(1);
  });
});

describe('applyQuote (clamp after quote)', () => {
  const qline = (extra) => ({
    productId: 1,
    variantId: 0,
    fieldValues: null,
    targetServerId: null,
    quantity: 5,
    kind: 'PRODUCT',
    ...extra,
  });

  test('QUANTITY_REDUCED sets the quantity to maxQuantity', () => {
    const r = applyQuote([line(1, 5)], {
      lines: [qline({ quantity: 3, maxQuantity: 3, errors: ['QUANTITY_REDUCED'] })],
    });
    expect(r.lines[0].quantity).toBe(3);
    expect(r.changed).toBe(true);
    expect(r.stale).toBe(false);
  });

  test('quantity above maxQuantity is clamped and the quote is stale when it was priced with the old amount', () => {
    const r = applyQuote([line(1, 10)], {
      lines: [qline({ quantity: 10, maxQuantity: 4, errors: [] })],
    });
    expect(r.lines[0].quantity).toBe(4);
    expect(r.stale).toBe(true);
  });

  test('a quantity within the limit is left alone', () => {
    const lines = [line(1, 2)];
    const r = applyQuote(lines, { lines: [qline({ quantity: 2, maxQuantity: 5, errors: [] })] });
    expect(r.changed).toBe(false);
    expect(r.lines).toBe(lines);
  });

  test('maxQuantity below 1 / missing does not clamp', () => {
    expect(
      applyQuote([line(1, 2)], { lines: [qline({ maxQuantity: 0, errors: ['QUANTITY_REDUCED'] })] })
        .changed,
    ).toBe(false);
    expect(
      applyQuote([line(1, 2)], { lines: [qline({ errors: ['QUANTITY_REDUCED'] })] }).changed,
    ).toBe(false);
  });

  test('unavailable lines are kept', () => {
    const lines = [line(1, 2)];
    const r = applyQuote(lines, {
      lines: [qline({ maxQuantity: 0, errors: ['PRODUCT_UNAVAILABLE'] })],
    });
    expect(r.lines).toBe(lines);
    expect(r.lines).toHaveLength(1);
  });

  test('bundle children and unmatched quote lines are ignored; null quote is fine', () => {
    const lines = [line(1, 2)];
    expect(
      applyQuote(lines, {
        lines: [qline({ kind: 'BUNDLE_CHILD', maxQuantity: 1, errors: ['QUANTITY_REDUCED'] })],
      }).changed,
    ).toBe(false);
    expect(
      applyQuote(lines, {
        lines: [qline({ productId: 99, maxQuantity: 1, errors: ['QUANTITY_REDUCED'] })],
      }).changed,
    ).toBe(false);
    expect(applyQuote(lines, null).changed).toBe(false);
  });

  test('matches variants and field values through lineKey', () => {
    const l = line(1, 5, { variantId: 2, fieldValues: { n: 'a' } });
    const r = applyQuote([l], {
      lines: [qline({ variantId: 2, fieldValues: { n: 'a' }, quantity: 5, maxQuantity: 2 })],
    });
    expect(r.lines[0].quantity).toBe(2);
  });
});

describe('replaceDecision (subscription replace decision table)', () => {
  const meta = (billingMode) => ({
    name: 'x',
    variantName: '',
    slug: 'x',
    imageFileName: '',
    price: 1,
    billingMode,
  });
  const sub = (id) => line(id, 1, { meta: meta('SUBSCRIPTION') });
  const one = (id) => line(id, 1, { meta: meta('ONE_TIME') });
  const guest = (lines) => ({ mode: 'GUEST', lines, quote: null, count: countOf(lines) });

  test('empty cart: always add, even a subscription', () => {
    expect(replaceDecision(guest([]), sub(1))).toBe('ADD');
    expect(replaceDecision(guest([]), one(1))).toBe('ADD');
  });

  test('no subscription involved: add', () => {
    expect(replaceDecision(guest([one(1)]), one(2))).toBe('ADD');
  });

  test('subscription added to a cart with another product: replace', () => {
    expect(replaceDecision(guest([one(1)]), sub(2))).toBe('REPLACE');
  });

  test('product added to a cart holding a subscription: replace', () => {
    expect(replaceDecision(guest([sub(1)]), one(2))).toBe('REPLACE');
  });

  test('a different subscription added to a subscription cart: replace', () => {
    expect(replaceDecision(guest([sub(1)]), sub(2))).toBe('REPLACE');
    expect(
      replaceDecision(guest([sub(1)]), line(1, 1, { variantId: 5, meta: meta('SUBSCRIPTION') })),
    ).toBe('REPLACE');
  });

  test('the same subscription again: already in the cart', () => {
    expect(replaceDecision(guest([sub(1)]), sub(1))).toBe('ALREADY_IN_CART');
  });

  test('server cart: the subscription is read from the quote', () => {
    const quote = {
      lines: [
        {
          productId: 1,
          variantId: 0,
          fieldValues: null,
          targetServerId: null,
          kind: 'PRODUCT',
          billingMode: 'SUBSCRIPTION',
        },
      ],
    };
    const state = { mode: 'SERVER', lines: [line(1)], quote, count: 1 };
    expect(replaceDecision(state, one(2))).toBe('REPLACE');
    expect(replaceDecision(state, sub(1))).toBe('ALREADY_IN_CART');
  });

  test('server cart without a quote falls back to meta', () => {
    expect(
      replaceDecision({ mode: 'SERVER', lines: [sub(1)], quote: null, count: 1 }, one(2)),
    ).toBe('REPLACE');
  });
});

describe('server lines, merge messages and the count cache', () => {
  test('linesFromServer keeps the server id and takes meta from the quote', () => {
    const quote = {
      lines: [
        {
          productId: 1,
          variantId: 0,
          fieldValues: null,
          targetServerId: null,
          kind: 'PRODUCT',
          name: 'VIP',
          variantName: '',
          slug: 'vip',
          imageFileName: 'v.png',
          unitPrice: 4.5,
          billingMode: 'TIMED',
        },
      ],
    };
    const lines = linesFromServer(
      [
        {
          id: 11,
          productId: 1,
          variantId: null,
          quantity: 2,
          fieldValues: null,
          targetServerId: null,
        },
        {
          id: 12,
          productId: 2,
          variantId: 3,
          quantity: 1,
          fieldValues: { a: 'b' },
          targetServerId: 4,
        },
      ],
      quote,
    );
    expect(lines[0]).toMatchObject({
      itemId: 11,
      productId: 1,
      variantId: 0,
      quantity: 2,
      fieldValues: {},
      targetServerId: null,
    });
    expect(lines[0].meta).toEqual({
      name: 'VIP',
      variantName: '',
      slug: 'vip',
      imageFileName: 'v.png',
      price: 4.5,
      billingMode: 'TIMED',
    });
    expect(lines[1].itemId).toBe(12);
    expect('meta' in lines[1]).toBe(false);
    expect(linesFromServer(undefined, null)).toEqual([]);
  });

  test('hasMessageCodes', () => {
    expect(hasMessageCodes({ messages: [{ code: 'X' }] })).toBe(true);
    expect(hasMessageCodes({ messages: [] })).toBe(false);
    expect(hasMessageCodes({})).toBe(false);
    expect(hasMessageCodes(null)).toBe(false);
  });

  test('parseCountCache: valid for the same user within 60 s only', () => {
    const raw = JSON.stringify({ userId: 7, count: 3, at: 1_000_000 });
    expect(parseCountCache(raw, 7, 1_030_000)).toBe(3);
    expect(parseCountCache(raw, 7, 1_060_000)).toBe(3);
    expect(parseCountCache(raw, 7, 1_060_001)).toBeNull();
    expect(parseCountCache(raw, 8, 1_010_000)).toBeNull();
    expect(parseCountCache(raw, 7, 999_999)).toBeNull();
    expect(parseCountCache('garbage', 7, 1_010_000)).toBeNull();
    expect(parseCountCache(null, 7, 1_010_000)).toBeNull();
    expect(
      parseCountCache(JSON.stringify({ userId: 7, count: -1, at: 1_000_000 }), 7, 1_010_000),
    ).toBeNull();
  });
});
