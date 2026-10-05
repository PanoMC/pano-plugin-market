import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import {
  FIELD_ERROR_CODES,
  TAB_IDS,
  addBundleRow,
  applyRules,
  buildPayload,
  bundleCandidates,
  currencyExponent,
  defaultProduct,
  ensurePriceRows,
  fieldErrorKey,
  firstErrorPath,
  fromApi,
  isMulti,
  mapServerErrors,
  slugify,
  snapshot,
  tabOfPath,
  tabsWithErrors,
  unitsFor,
  validateProduct,
  visibleTabs,
} from './model.js';
import { blankVariant, generateVariants, keyFromLabel } from '../../utils/variants.js';

const SINGLE = {
  currency: 'USD',
  currencyMode: 'SINGLE',
  additionalCurrencies: [],
  currencies: [
    { code: 'USD', symbol: '$', exponent: 2 },
    { code: 'EUR', symbol: 'E', exponent: 2 },
    { code: 'JPY', symbol: 'Y', exponent: 0 },
  ],
  creditsEnabled: true,
  vatPercent: 20,
  productMetaSchemas: [],
};
const MULTI = { ...SINGLE, currencyMode: 'MULTI', additionalCurrencies: ['EUR', 'JPY'] };

function valid(overrides = {}, ctx = SINGLE) {
  return { ...defaultProduct(ctx), name: 'Rank VIP', slug: 'rank-vip', price: 10, ...overrides };
}
const errorsOf = (product, ctx = SINGLE, options) => validateProduct(product, ctx, options).errors;

describe('defaults and helpers', () => {
  test('a default product with a name and slug is valid', () => {
    expect(errorsOf(valid())).toEqual({});
  });

  test('slugify matches the slug pattern', () => {
    expect(slugify('Rank VIP')).toBe('rank-vip');
    expect(slugify('  Şişe Çanta!!  ')).toBe('sise-canta');
    expect(slugify('Ölüm / Hayat')).toBe('olum-hayat');
    expect(slugify('---')).toBe('');
    expect(slugify('a'.repeat(300)).length).toBe(255);
  });

  test('currencyExponent and isMulti', () => {
    expect(currencyExponent(SINGLE)).toBe(2);
    expect(currencyExponent(SINGLE, 'JPY')).toBe(0);
    expect(currencyExponent(null)).toBe(2);
    expect(isMulti(SINGLE)).toBe(false);
    expect(isMulti(MULTI)).toBe(true);
    expect(isMulti({ ...MULTI, additionalCurrencies: [] })).toBe(false);
    expect(isMulti(null)).toBe(false);
  });

  test('ensurePriceRows gives one row per additional currency and keeps values', () => {
    const rows = ensurePriceRows(
      [
        { currency: 'JPY', price: 5, compareAtPrice: 9 },
        { currency: 'GBP', price: 1 },
      ],
      MULTI,
    );
    expect(rows).toEqual([
      { currency: 'EUR', price: null, compareAtPrice: null },
      { currency: 'JPY', price: 5, compareAtPrice: 9 },
    ]);
    expect(ensurePriceRows([], SINGLE)).toEqual([]);
  });

  test('unitsFor', () => {
    expect(unitsFor('TIMED')).toEqual(['MINUTE', 'HOUR', 'DAY', 'WEEK', 'MONTH', 'YEAR']);
    expect(unitsFor('SUBSCRIPTION')).toEqual(['DAY', 'WEEK', 'MONTH', 'YEAR']);
    expect(unitsFor('ONE_TIME')).toEqual([]);
  });
});

describe('forced field rules', () => {
  test('CREDIT_PACK forces one-time, no physical, no credit price, no variants', () => {
    const p = valid({
      kind: 'CREDIT_PACK',
      billingMode: 'SUBSCRIPTION',
      physical: true,
      creditPrice: 5,
      hasVariants: true,
      creditAmount: 100,
    });
    expect(applyRules(p, SINGLE)).toBe(true);
    expect(p.billingMode).toBe('ONE_TIME');
    expect(p.physical).toBe(false);
    expect(p.creditPrice).toBe(0);
    expect(p.hasVariants).toBe(false);
    expect(p.creditAmount).toBe(100);
  });

  test('BUNDLE forces one-time and not physical', () => {
    const p = valid({ kind: 'BUNDLE', billingMode: 'TIMED', periodCount: 3, physical: true });
    applyRules(p, SINGLE);
    expect(p.billingMode).toBe('ONE_TIME');
    expect(p.physical).toBe(false);
    expect(p.periodCount).toBeNull();
  });

  test('TIMED / SUBSCRIPTION cannot be physical; units follow the mode', () => {
    const p = valid({
      billingMode: 'SUBSCRIPTION',
      periodCount: 1,
      periodUnit: 'HOUR',
      physical: true,
      creditPrice: 3,
    });
    applyRules(p, SINGLE);
    expect(p.physical).toBe(false);
    expect(p.periodUnit).toBe('DAY');
    expect(p.creditPrice).toBe(0);
    const t = valid({ billingMode: 'TIMED', periodUnit: 'HOUR' });
    applyRules(t, SINGLE);
    expect(t.periodUnit).toBe('HOUR');
  });

  test('max cycles only for subscriptions; a stored credit price survives disabled credits', () => {
    const p = valid({ creditPrice: 4, subscriptionMaxCycles: 3 });
    applyRules(p, { ...SINGLE, creditsEnabled: false });
    expect(p.creditPrice).toBe(4);
    expect(p.subscriptionMaxCycles).toBeNull();
    applyRules(p, null);
    expect(p.creditPrice).toBe(4);
  });

  test('tier rank is cleared when the category is not tiered, left alone when categories are unknown', () => {
    const p = valid({ tierRank: 3 });
    expect(applyRules(p, SINGLE, undefined)).toBe(false);
    expect(p.tierRank).toBe(3);
    expect(applyRules(p, SINGLE, { tiered: true })).toBe(false);
    expect(applyRules(p, SINGLE, { tiered: false })).toBe(true);
    expect(p.tierRank).toBeNull();
  });

  test('is idempotent', () => {
    const p = valid({ kind: 'CREDIT_PACK', creditAmount: 10 });
    applyRules(p, SINGLE);
    expect(applyRules(p, SINGLE)).toBe(false);
  });
});

describe('tabs', () => {
  test('visibility', () => {
    expect(visibleTabs(valid(), SINGLE)).toEqual(
      TAB_IDS.filter((id) => !['bundle', 'shipping', 'providers'].includes(id)),
    );
    expect(visibleTabs(valid({ kind: 'BUNDLE' }), SINGLE)).toContain('bundle');
    expect(visibleTabs(valid({ physical: true }), SINGLE)).toContain('shipping');
    const withSchemas = {
      ...SINGLE,
      productMetaSchemas: [{ providerId: 'x', name: 'X', schema: {} }],
    };
    expect(visibleTabs(valid(), withSchemas)).toContain('providers');
  });

  test('dotted paths map to their tab; sidebar fields to null', () => {
    expect(tabOfPath('name')).toBe('general');
    expect(tabOfPath('slug')).toBe('general');
    expect(tabOfPath('price')).toBe('pricing');
    expect(tabOfPath('prices.0.price')).toBe('pricing');
    expect(tabOfPath('prices.EUR.price')).toBe('pricing');
    expect(tabOfPath('variants.2.price')).toBe('variants');
    expect(tabOfPath('variantOptions.0.label')).toBe('variants');
    expect(tabOfPath('fields.0.fieldKey')).toBe('fields');
    expect(tabOfPath('bundleItems.1.quantity')).toBe('bundle');
    expect(tabOfPath('actions.1.value')).toBe('actions');
    expect(tabOfPath('weightGrams')).toBe('shipping');
    expect(tabOfPath('limitPerPlayer')).toBe('limits');
    expect(tabOfPath('providerMeta.x.key')).toBe('providers');
    expect(tabOfPath('metaTitle')).toBe('seo');
    expect(tabOfPath('physical')).toBeNull();
    expect(tabOfPath('tierRank')).toBeNull();
    expect(tabOfPath('')).toBeNull();
  });

  test('tabsWithErrors lists tabs in display order; firstErrorPath prefers sidebar then tab order', () => {
    const errors = { 'variants.1.name': 'REQUIRED', price: 'REQUIRED', name: 'REQUIRED' };
    expect(tabsWithErrors(errors)).toEqual(['general', 'pricing', 'variants']);
    expect(firstErrorPath(errors)).toBe('name');
    expect(firstErrorPath({ 'variants.0.name': 'REQUIRED', 'fields.0.label': 'REQUIRED' })).toBe(
      'variants.0.name',
    );
    expect(firstErrorPath({ tierRank: 'REQUIRED', name: 'REQUIRED' })).toBe('tierRank');
    expect(firstErrorPath({})).toBeNull();
  });
});

describe('validation: general and pricing', () => {
  test('name and slug', () => {
    expect(errorsOf(valid({ name: '  ' })).name).toBe('REQUIRED');
    expect(errorsOf(valid({ name: 'x'.repeat(256) })).name).toBe('TOO_LONG');
    expect(errorsOf(valid({ slug: '' })).slug).toBe('REQUIRED');
    expect(errorsOf(valid({ slug: 'Bad Slug' })).slug).toBe('INVALID_FORMAT');
    expect(errorsOf(valid({ slug: 'a--b' })).slug).toBe('INVALID_FORMAT');
    for (const reserved of ['checkout', 'order', 'cart'])
      expect(errorsOf(valid({ slug: reserved })).slug).toBe('RESERVED_SLUG');
    expect(errorsOf(valid({ slug: 'cart-2' })).slug).toBeUndefined();
  });

  test('short description is limited to 512', () => {
    expect(errorsOf(valid({ shortDescription: 'x'.repeat(512) })).shortDescription).toBeUndefined();
    expect(errorsOf(valid({ shortDescription: 'x'.repeat(513) })).shortDescription).toBe(
      'TOO_LONG',
    );
  });

  test('sale window must end after it starts', () => {
    const open = valid({
      saleWindow: true,
      durationStart: '2026-01-02T10:00',
      durationExpiry: '2026-01-02T10:00',
    });
    expect(errorsOf(open).durationExpiry).toBe('INVALID_DATE_RANGE');
    expect(
      errorsOf({ ...open, durationExpiry: '2026-01-03T10:00' }).durationExpiry,
    ).toBeUndefined();
    expect(errorsOf({ ...open, saleWindow: false }).durationExpiry).toBeUndefined();
  });

  test('price: required, zero allowed (free), negative / malformed / over-precise rejected', () => {
    expect(errorsOf(valid({ price: null })).price).toBe('REQUIRED');
    expect(errorsOf(valid({ price: 0 })).price).toBeUndefined();
    expect(errorsOf(valid({ price: -1 })).price).toBe('OUT_OF_RANGE');
    expect(errorsOf(valid({ price: NaN })).price).toBe('INVALID');
    expect(errorsOf(valid({ price: 1.234 })).price).toBe('MAX_DECIMALS');
  });

  test('whole-number currency refuses decimals', () => {
    const jpy = { ...SINGLE, currency: 'JPY' };
    expect(errorsOf(valid({ price: 10.5 }, jpy), jpy).price).toBe('MAX_DECIMALS');
    expect(errorsOf(valid({ price: 10 }, jpy), jpy).price).toBeUndefined();
  });

  test('compare-at price must exceed the price', () => {
    expect(errorsOf(valid({ compareAtPrice: 10 })).compareAtPrice).toBe('COMPARE_NOT_ABOVE_PRICE');
    expect(errorsOf(valid({ compareAtPrice: 9 })).compareAtPrice).toBe('COMPARE_NOT_ABOVE_PRICE');
    expect(errorsOf(valid({ compareAtPrice: 10.01 })).compareAtPrice).toBeUndefined();
    expect(errorsOf(valid({ compareAtPrice: null })).compareAtPrice).toBeUndefined();
  });

  test('credit price only when credits are enabled, not for packs or subscriptions', () => {
    expect(errorsOf(valid({ creditPrice: -1 })).creditPrice).toBe('OUT_OF_RANGE');
    expect(
      errorsOf(valid({ creditPrice: -1 }), { ...SINGLE, creditsEnabled: false }).creditPrice,
    ).toBeUndefined();
    expect(
      errorsOf(
        valid({
          creditPrice: -1,
          billingMode: 'SUBSCRIPTION',
          periodCount: 1,
          periodUnit: 'MONTH',
        }),
      ).creditPrice,
    ).toBeUndefined();
  });

  test('credit amount is required and positive for a pack', () => {
    const pack = (creditAmount) => valid({ kind: 'CREDIT_PACK', creditAmount });
    expect(errorsOf(pack(null)).creditAmount).toBe('REQUIRED');
    expect(errorsOf(pack(0)).creditAmount).toBe('OUT_OF_RANGE');
    expect(errorsOf(pack(1.234)).creditAmount).toBe('MAX_DECIMALS');
    expect(errorsOf(pack(50)).creditAmount).toBeUndefined();
    expect(errorsOf(valid({ creditAmount: 0 })).creditAmount).toBeUndefined();
  });

  test('VAT override: 0-100, at most 2 decimals, null = store default', () => {
    expect(errorsOf(valid({ vatPercent: null })).vatPercent).toBeUndefined();
    expect(errorsOf(valid({ vatPercent: 0 })).vatPercent).toBeUndefined();
    expect(errorsOf(valid({ vatPercent: 100 })).vatPercent).toBeUndefined();
    expect(errorsOf(valid({ vatPercent: 100.01 })).vatPercent).toBe('OUT_OF_RANGE');
    expect(errorsOf(valid({ vatPercent: -1 })).vatPercent).toBe('OUT_OF_RANGE');
    expect(errorsOf(valid({ vatPercent: 7.255 })).vatPercent).toBe('MAX_DECIMALS');
  });

  test('period and cycles', () => {
    const timed = (over) =>
      valid({ billingMode: 'TIMED', periodCount: 7, periodUnit: 'DAY', ...over });
    expect(errorsOf(timed({})).periodCount).toBeUndefined();
    expect(errorsOf(timed({ periodCount: null })).periodCount).toBe('REQUIRED');
    expect(errorsOf(timed({ periodCount: 0 })).periodCount).toBe('OUT_OF_RANGE');
    expect(errorsOf(timed({ periodCount: 1.5 })).periodCount).toBe('NOT_INTEGER');
    expect(errorsOf(timed({ periodUnit: null })).periodUnit).toBe('REQUIRED');
    const sub = (over) =>
      valid({ billingMode: 'SUBSCRIPTION', periodCount: 1, periodUnit: 'MONTH', ...over });
    expect(errorsOf(sub({ periodUnit: 'HOUR' })).periodUnit).toBe('REQUIRED');
    expect(errorsOf(sub({ subscriptionMaxCycles: 0 })).subscriptionMaxCycles).toBe('OUT_OF_RANGE');
    expect(errorsOf(sub({ subscriptionMaxCycles: 12 })).subscriptionMaxCycles).toBeUndefined();
    expect(errorsOf(valid()).periodCount).toBeUndefined();
  });

  test('stock is validated on create only, and not with variants', () => {
    expect(errorsOf(valid({ hasStockLimit: true, stock: -1 })).stock).toBe('OUT_OF_RANGE');
    expect(errorsOf(valid({ hasStockLimit: true, stock: 1.5 })).stock).toBe('NOT_INTEGER');
    expect(errorsOf(valid({ hasStockLimit: true, stock: null })).stock).toBe('REQUIRED');
    expect(
      errorsOf(valid({ hasStockLimit: true, stock: -1 }), SINGLE, { isEdit: true }).stock,
    ).toBeUndefined();
    expect(
      errorsOf(valid({ hasStockLimit: true, stock: -1, hasVariants: true })).stock,
    ).toBeUndefined();
  });

  test('tier rank is required in a tiered category', () => {
    expect(
      errorsOf(valid({ tierRank: null }), SINGLE, { category: { tiered: true } }).tierRank,
    ).toBe('REQUIRED');
    expect(errorsOf(valid({ tierRank: -1 }), SINGLE, { category: { tiered: true } }).tierRank).toBe(
      'OUT_OF_RANGE',
    );
    expect(
      errorsOf(valid({ tierRank: 0 }), SINGLE, { category: { tiered: true } }).tierRank,
    ).toBeUndefined();
    expect(
      errorsOf(valid({ tierRank: null }), SINGLE, { category: { tiered: false } }).tierRank,
    ).toBeUndefined();
  });

  test('physical products need a weight', () => {
    expect(errorsOf(valid({ physical: true })).weightGrams).toBe('REQUIRED');
    expect(errorsOf(valid({ physical: true, weightGrams: 0 })).weightGrams).toBe('OUT_OF_RANGE');
    expect(errorsOf(valid({ physical: true, weightGrams: 250 })).weightGrams).toBeUndefined();
  });
});

describe('validation: per-currency grid (MULTI)', () => {
  const rows = (eur, jpy) => [
    { currency: 'EUR', ...eur },
    { currency: 'JPY', ...jpy },
  ];
  const none = { price: null, compareAtPrice: null };

  test('an empty grid is valid (fallback applies)', () => {
    expect(errorsOf(valid({ prices: rows(none, none) }, MULTI), MULTI)).toEqual({});
  });

  test('rules per row; zero-exponent currencies take whole numbers only', () => {
    const e = errorsOf(
      valid(
        { prices: rows({ price: 5, compareAtPrice: 5 }, { price: 10.5, compareAtPrice: null }) },
        MULTI,
      ),
      MULTI,
    );
    expect(e['prices.EUR.compareAtPrice']).toBe('COMPARE_NOT_ABOVE_PRICE');
    expect(e['prices.JPY.price']).toBe('MAX_DECIMALS');
    expect(e['prices.EUR.price']).toBeUndefined();
  });

  test('compare-at without a price is refused', () => {
    const e = errorsOf(
      valid({ prices: rows({ price: null, compareAtPrice: 4 }, none) }, MULTI),
      MULTI,
    );
    expect(e['prices.EUR.compareAtPrice']).toBe('REQUIRES_PRICE');
  });

  test('negative price is out of range', () => {
    const e = errorsOf(
      valid({ prices: rows({ price: -1, compareAtPrice: null }, none) }, MULTI),
      MULTI,
    );
    expect(e['prices.EUR.price']).toBe('OUT_OF_RANGE');
  });

  test('the grid is not validated outside MULTI', () => {
    const p = valid({ prices: rows({ price: -1, compareAtPrice: null }, none) });
    expect(errorsOf(p, SINGLE)['prices.EUR.price']).toBeUndefined();
  });
});

describe('validation: variants', () => {
  const axes = () => [
    {
      key: 'size',
      label: 'Size',
      values: [
        { key: 'a', label: 'A' },
        { key: 'b', label: 'B' },
      ],
    },
  ];
  const withVariants = (variants, over = {}) =>
    valid({ hasVariants: true, variantOptions: axes(), variants, ...over });
  const variant = (over = {}) => blankVariant({ name: 'A', optionValues: { size: 'a' }, ...over });

  test('needs at least one active variant', () => {
    expect(errorsOf(withVariants([])).variants).toBe('NEEDS_ACTIVE_VARIANT');
    expect(errorsOf(withVariants([variant({ status: 'INACTIVE' })])).variants).toBe(
      'NEEDS_ACTIVE_VARIANT',
    );
    expect(errorsOf(withVariants([variant()])).variants).toBeUndefined();
    expect(errorsOf(valid({ hasVariants: false, variants: [] })).variants).toBeUndefined();
  });

  test('at most 100 variants', () => {
    const many = Array.from({ length: 101 }, (_, i) => variant({ name: `v${i}` }));
    expect(errorsOf(withVariants(many)).variants).toBe('TOO_MANY');
  });

  test('name required, 255, unique (case-insensitive) and both rows are marked', () => {
    const e = errorsOf(
      withVariants([variant({ name: 'X' }), variant({ name: 'x' }), variant({ name: ' ' })]),
    );
    expect(e['variants.0.name']).toBe('DUPLICATE');
    expect(e['variants.1.name']).toBe('DUPLICATE');
    expect(e['variants.2.name']).toBe('REQUIRED');
    expect(errorsOf(withVariants([variant({ name: 'y'.repeat(256) })]))['variants.0.name']).toBe(
      'TOO_LONG',
    );
  });

  test('sku, price, compare-at against the effective price, stock of new rows only', () => {
    const e = errorsOf(
      withVariants([
        variant({ name: 'a', sku: 's'.repeat(65), price: -2 }),
        variant({ name: 'b', price: 20, compareAtPrice: 20 }),
        variant({ name: 'c', compareAtPrice: 10 }),
        variant({ name: 'd', compareAtPrice: 10.5 }),
        variant({ name: 'e', stock: -1 }),
        variant({ id: 9, name: 'f', stock: -1 }),
        variant({ name: 'g', stock: 2.5 }),
      ]),
    );
    expect(e['variants.0.sku']).toBe('TOO_LONG');
    expect(e['variants.0.price']).toBe('OUT_OF_RANGE');
    expect(e['variants.1.compareAtPrice']).toBe('COMPARE_NOT_ABOVE_PRICE');
    expect(e['variants.2.compareAtPrice']).toBe('COMPARE_NOT_ABOVE_PRICE'); // product price 10
    expect(e['variants.3.compareAtPrice']).toBeUndefined();
    expect(e['variants.4.stock']).toBe('OUT_OF_RANGE');
    expect(e['variants.5.stock']).toBeUndefined();
    expect(e['variants.6.stock']).toBe('NOT_INTEGER');
  });

  test('weight only for physical, period count only for timed / subscription', () => {
    const phys = errorsOf(
      withVariants([variant({ weightGrams: 0 })], { physical: true, weightGrams: 10 }),
    );
    expect(phys['variants.0.weightGrams']).toBe('OUT_OF_RANGE');
    expect(
      errorsOf(withVariants([variant({ weightGrams: 0 })]))['variants.0.weightGrams'],
    ).toBeUndefined();
    const timed = errorsOf(
      withVariants([variant({ periodCount: 0 })], {
        billingMode: 'TIMED',
        periodCount: 3,
        periodUnit: 'DAY',
      }),
    );
    expect(timed['variants.0.periodCount']).toBe('OUT_OF_RANGE');
  });

  test('attribute keys follow the pattern, are unique, at most 20, values 255', () => {
    const attrs = [
      { key: 'Color', value: 'x' },
      { key: 'ok', value: 'y' },
      { key: 'ok', value: 'z'.repeat(256) },
    ];
    const e = errorsOf(withVariants([variant({ attributes: attrs })]));
    expect(e['variants.0.attributes.0.key']).toBe('INVALID_FORMAT');
    expect(e['variants.0.attributes.1.key']).toBeUndefined();
    expect(e['variants.0.attributes.2.key']).toBe('DUPLICATE');
    expect(e['variants.0.attributes.2.value']).toBe('TOO_LONG');
    const many = Array.from({ length: 21 }, (_, i) => ({ key: `k${i}`, value: 'v' }));
    expect(errorsOf(withVariants([variant({ attributes: many })]))['variants.0.attributes']).toBe(
      'TOO_MANY',
    );
  });

  test('variant per-currency rows use variants.<i>.prices.<CUR>.<field>', () => {
    const v = variant({
      prices: [
        { currency: 'EUR', price: 3, compareAtPrice: 2 },
        { currency: 'JPY', price: null, compareAtPrice: null },
      ],
    });
    const e = errorsOf(withVariants([v], { prices: ensurePriceRows([], MULTI) }), MULTI);
    expect(e['variants.0.prices.EUR.compareAtPrice']).toBe('COMPARE_NOT_ABOVE_PRICE');
  });

  test('axes: labels required, at most 3 axes and 20 values', () => {
    const e = errorsOf(
      withVariants([variant()], {
        variantOptions: [{ key: 'a', label: '', values: [{ key: 'x', label: '' }] }],
      }),
    );
    expect(e['variantOptions.0.label']).toBe('REQUIRED');
    expect(e['variantOptions.0.values.0.label']).toBe('REQUIRED');
    const four = Array.from({ length: 4 }, (_, i) => ({
      key: `a${i}`,
      label: `A${i}`,
      values: [],
    }));
    expect(errorsOf(withVariants([variant()], { variantOptions: four })).variantOptions).toBe(
      'TOO_MANY',
    );
    const wide = [
      {
        key: 'a',
        label: 'A',
        values: Array.from({ length: 21 }, (_, i) => ({ key: `v${i}`, label: `V${i}` })),
      },
    ];
    expect(
      errorsOf(withVariants([variant()], { variantOptions: wide }))['variantOptions.0.values'],
    ).toBe('TOO_MANY');
  });
});

describe('validation: bundle', () => {
  const bundle = (bundleItems) => valid({ kind: 'BUNDLE', bundleItems });
  const row = (over = {}) => ({
    productId: 1,
    variantId: 0,
    quantity: 1,
    hasVariants: false,
    ...over,
  });

  test('needs 1 to 50 rows', () => {
    expect(errorsOf(bundle([])).bundleItems).toBe('NEEDS_ITEMS');
    expect(errorsOf(bundle([row()])).bundleItems).toBeUndefined();
    const fifty1 = Array.from({ length: 51 }, (_, i) => row({ productId: i + 1 }));
    expect(errorsOf(bundle(fifty1)).bundleItems).toBe('TOO_MANY');
    expect(errorsOf(valid({ bundleItems: [] })).bundleItems).toBeUndefined();
  });

  test('quantity is an integer 1-99; a child with variants needs a variant', () => {
    const e = errorsOf(
      bundle([
        row({ quantity: 0 }),
        row({ productId: 2, quantity: 100 }),
        row({ productId: 3, quantity: 1.5 }),
        row({ productId: 4, hasVariants: true }),
      ]),
    );
    expect(e['bundleItems.0.quantity']).toBe('OUT_OF_RANGE');
    expect(e['bundleItems.1.quantity']).toBe('OUT_OF_RANGE');
    expect(e['bundleItems.2.quantity']).toBe('NOT_INTEGER');
    expect(e['bundleItems.3.variantId']).toBe('REQUIRED');
    expect(
      errorsOf(bundle([row({ productId: 4, hasVariants: true, variantId: 8 })]))[
        'bundleItems.0.variantId'
      ],
    ).toBeUndefined();
  });

  test('duplicate (productId, variantId) merges quantities, capped at 99', () => {
    let rows = addBundleRow([], { productId: 1, variantId: 0, quantity: 2 });
    rows = addBundleRow(rows, { productId: 1, variantId: 0, quantity: 3 });
    rows = addBundleRow(rows, { productId: 1, variantId: 5, quantity: 1 });
    expect(rows.map((r) => [r.productId, r.variantId, r.quantity])).toEqual([
      [1, 0, 5],
      [1, 5, 1],
    ]);
    expect(addBundleRow(rows, { productId: 1, variantId: 0, quantity: 98 })[0].quantity).toBe(99);
    expect(addBundleRow([], { productId: 1, quantity: 0 })[0].quantity).toBe(1);
    expect(rows).toHaveLength(2);
  });

  test('candidates: STANDARD, not a subscription, not itself', () => {
    const products = [
      { id: 1, kind: 'STANDARD', billingMode: 'ONE_TIME' },
      { id: 2, kind: 'BUNDLE', billingMode: 'ONE_TIME' },
      { id: 3, kind: 'STANDARD', billingMode: 'SUBSCRIPTION' },
      { id: 4, kind: 'CREDIT_PACK', billingMode: 'ONE_TIME' },
      { id: 5, billingMode: 'TIMED' },
      { id: 6, kind: 'STANDARD', billingMode: 'TIMED' },
    ];
    expect(bundleCandidates(products, 6).map((p) => p.id)).toEqual([1, 5]);
    expect(bundleCandidates(products, null).map((p) => p.id)).toEqual([1, 5, 6]);
  });
});

describe('payload', () => {
  const scalar = (payload, name) => payload.scalars.find(([k]) => k === name)?.[1];

  test('scalars of a simple product', () => {
    const p = valid({
      shortDescription: 'Short',
      categoryId: 4,
      compareAtPrice: 15,
      vatPercent: 7,
    });
    const payload = buildPayload(p, SINGLE);
    expect(scalar(payload, 'name')).toBe('Rank VIP');
    expect(scalar(payload, 'slug')).toBe('rank-vip');
    expect(scalar(payload, 'shortDescription')).toBe('Short');
    expect(scalar(payload, 'categoryId')).toBe('4');
    expect(scalar(payload, 'kind')).toBe('STANDARD');
    expect(scalar(payload, 'status')).toBe('ACTIVE');
    expect(scalar(payload, 'price')).toBe('10');
    expect(scalar(payload, 'compareAtPrice')).toBe('15');
    expect(scalar(payload, 'vatPercent')).toBe('7');
    expect(scalar(payload, 'billingMode')).toBe('ONE_TIME');
    expect(scalar(payload, 'allowGift')).toBe('true');
    expect(scalar(payload, 'physical')).toBe('false');
    expect(scalar(payload, 'durationType')).toBe('LIFETIME');
    expect(scalar(payload, 'periodCount')).toBeUndefined();
    expect(scalar(payload, 'creditAmount')).toBeUndefined();
  });

  test('empty nullable scalars are omitted; no category sends -1', () => {
    const payload = buildPayload(valid(), SINGLE);
    for (const name of [
      'shortDescription',
      'compareAtPrice',
      'vatPercent',
      'tierRank',
      'limitPerPlayer',
      'stock',
    ])
      expect(scalar(payload, name)).toBeUndefined();
    expect(scalar(payload, 'categoryId')).toBe('-1');
  });

  test('stock is sent on create only, and not with variants', () => {
    const limited = valid({ hasStockLimit: true, stock: 7 });
    expect(scalar(buildPayload(limited, SINGLE), 'stock')).toBe('7');
    expect(scalar(buildPayload(limited, SINGLE, { isEdit: true }), 'stock')).toBeUndefined();
    expect(
      scalar(buildPayload({ ...limited, hasVariants: true }, SINGLE), 'stock'),
    ).toBeUndefined();
  });

  test('billing fields: period for timed, cycles for subscriptions, credit amount for packs', () => {
    const sub = buildPayload(
      valid({
        billingMode: 'SUBSCRIPTION',
        periodCount: 1,
        periodUnit: 'MONTH',
        subscriptionMaxCycles: 6,
      }),
      SINGLE,
    );
    expect(scalar(sub, 'periodCount')).toBe('1');
    expect(scalar(sub, 'periodUnit')).toBe('MONTH');
    expect(scalar(sub, 'subscriptionMaxCycles')).toBe('6');
    const pack = buildPayload(valid({ kind: 'CREDIT_PACK', creditAmount: 250 }), SINGLE);
    expect(scalar(pack, 'creditAmount')).toBe('250');
  });

  test('sale window sends epoch milliseconds only when on', () => {
    const on = valid({
      saleWindow: true,
      durationStart: '2026-03-01T08:00',
      durationExpiry: '2026-03-02T08:00',
    });
    const payload = buildPayload(on, SINGLE);
    expect(scalar(payload, 'durationType')).toBe('TEMPORARY');
    expect(
      Number(scalar(payload, 'durationExpiry')) - Number(scalar(payload, 'durationStart')),
    ).toBe(86400000);
    const off = buildPayload({ ...on, saleWindow: false }, SINGLE);
    expect(scalar(off, 'durationType')).toBe('LIFETIME');
    expect(scalar(off, 'durationStart')).toBeUndefined();
  });

  test('image parts and removeImage', () => {
    const file = { name: 'a.png' };
    expect(buildPayload(valid(), SINGLE, { imageFile: file }).files).toEqual([
      { part: 'image', file },
    ]);
    expect(
      scalar(buildPayload(valid(), SINGLE, { isEdit: true, removeImage: true }), 'removeImage'),
    ).toBe('true');
    expect(
      scalar(
        buildPayload(valid(), SINGLE, { isEdit: true, removeImage: true, imageFile: file }),
        'removeImage',
      ),
    ).toBeUndefined();
    expect(
      scalar(buildPayload(valid(), SINGLE, { isEdit: false, removeImage: true }), 'removeImage'),
    ).toBeUndefined();
  });

  test('json parts: variants carry positions, attributes object, stock only for new rows', () => {
    const generated = generateVariants(
      [
        {
          key: 'size',
          label: 'Size',
          values: [
            { key: 'a', label: 'A' },
            { key: 'b', label: 'B' },
          ],
        },
      ],
      [],
    ).variants;
    generated[0].stock = 5;
    generated[0].attributes = [{ key: 'color', value: 'red' }];
    generated[0].sku = '';
    generated[1] = { ...generated[1], id: 12, stock: 99, price: 4 };
    const p = valid({
      hasVariants: true,
      variantOptions: [
        {
          key: 'size',
          label: 'Size',
          locked: true,
          values: [
            { key: 'a', label: 'A', locked: true },
            { key: 'b', label: 'B' },
          ],
        },
      ],
      variants: generated,
    });
    const { json } = buildPayload(p, SINGLE);
    expect(json.variants).toHaveLength(2);
    expect(json.variants[0]).toMatchObject({
      name: 'A',
      position: 0,
      stock: 5,
      sku: null,
      status: 'ACTIVE',
    });
    expect(json.variants[0].attributes).toEqual({ color: 'red' });
    expect(json.variants[0].id).toBeUndefined();
    expect(json.variants[1]).toMatchObject({ id: 12, price: 4, position: 1 });
    expect('stock' in json.variants[1]).toBe(false);
    expect(json.variantOptions).toEqual([
      {
        key: 'size',
        label: 'Size',
        values: [
          { key: 'a', label: 'A' },
          { key: 'b', label: 'B' },
        ],
      },
    ]);
    for (const wire of json.variants) {
      expect('_key' in wire).toBe(false);
      expect('orphan' in wire).toBe(false);
      expect('imageFile' in wire).toBe(false);
    }
  });

  test('without hasVariants nothing of the variant state is sent', () => {
    const p = valid({
      variants: [blankVariant({ name: 'x' })],
      variantOptions: [{ key: 'a', label: 'A', values: [] }],
    });
    const { json } = buildPayload(p, SINGLE);
    expect(json.variants).toEqual([]);
    expect(json.variantOptions).toEqual([]);
    expect(scalar(buildPayload(p, SINGLE), 'hasVariants')).toBe('false');
  });

  test('variant images become variantImage_<index> parts; a removed image is flagged', () => {
    const file = { name: 'v.png' };
    const p = valid({
      hasVariants: true,
      variants: [
        blankVariant({ name: 'a' }),
        blankVariant({ name: 'b', imageFile: file }),
        blankVariant({ id: 3, name: 'c', removeImage: true, imageFileName: 'old.png' }),
      ],
    });
    const { files, json } = buildPayload(p, SINGLE);
    expect(files).toEqual([{ part: 'variantImage_1', file }]);
    expect(json.variants[2].removeImage).toBe(true);
    expect(json.variants[1].removeImage).toBeUndefined();
  });

  test('bundle rows only for bundles', () => {
    const rows = [{ productId: 1, variantId: 0, quantity: 2, name: 'x', hasVariants: false }];
    expect(
      buildPayload(valid({ kind: 'BUNDLE', bundleItems: rows }), SINGLE).json.bundleItems,
    ).toEqual([{ productId: 1, variantId: 0, quantity: 2 }]);
    expect(buildPayload(valid({ bundleItems: rows }), SINGLE).json.bundleItems).toEqual([]);
  });

  test('prices are sent in MULTI mode only; empty prices are dropped, base currency never sent', () => {
    const p = valid({
      prices: [
        { currency: 'EUR', price: 9, compareAtPrice: 12 },
        { currency: 'JPY', price: null, compareAtPrice: null },
        { currency: 'GBP', price: 3, compareAtPrice: null },
      ],
    });
    expect(buildPayload(p, SINGLE).json.prices).toBeUndefined();
    expect(buildPayload(p, MULTI).json.prices).toEqual([
      { variantId: 0, currency: 'EUR', price: 9, compareAtPrice: 12 },
    ]);
    expect(buildPayload(valid({ prices: [] }), MULTI).json.prices).toEqual([]);
  });

  test('saved variants send their prices at top level, new ones inside the variant', () => {
    const p = valid({
      hasVariants: true,
      prices: [{ currency: 'EUR', price: 9, compareAtPrice: null }],
      variants: [
        blankVariant({
          id: 4,
          name: 'saved',
          prices: [
            { currency: 'EUR', price: 2, compareAtPrice: null },
            { currency: 'JPY', price: 300, compareAtPrice: null },
          ],
        }),
        blankVariant({
          name: 'new',
          prices: [
            { currency: 'EUR', price: 5, compareAtPrice: 6 },
            { currency: 'JPY', price: null, compareAtPrice: null },
          ],
        }),
      ],
    });
    const { json, pathMap } = buildPayload(p, MULTI);
    expect(json.prices).toEqual([
      { variantId: 0, currency: 'EUR', price: 9, compareAtPrice: null },
      { variantId: 4, currency: 'EUR', price: 2, compareAtPrice: null },
      { variantId: 4, currency: 'JPY', price: 300, compareAtPrice: null },
    ]);
    expect(json.variants[0].prices).toBeUndefined();
    expect(json.variants[1].prices).toEqual([{ currency: 'EUR', price: 5, compareAtPrice: 6 }]);
    expect(pathMap['prices.0.price']).toBe('prices.EUR.price');
    expect(pathMap['prices.2.price']).toBe('variants.0.prices.JPY.price');
    expect(pathMap['variants.1.prices.0.compareAtPrice']).toBe(
      'variants.1.prices.EUR.compareAtPrice',
    );
  });

  test('pass-through parts keep action ids and fields untouched', () => {
    const actions = [
      { id: 'a1', type: 'CREDIT', phase: 'GRANT', value: 5 },
      { type: 'COMMAND', value: ['x'] },
    ];
    const p = valid({
      actions,
      serverChoices: [1, 2],
      requiredProducts: [3],
      providerMeta: { x: { a: 1 } },
    });
    const { json } = buildPayload(p, SINGLE);
    expect(json.actions).toEqual(actions);
    expect(json.actions[0].id).toBe('a1');
    expect('id' in json.actions[1]).toBe(false);
    expect(json.serverChoices).toEqual([1, 2]);
    expect(json.requiredProducts).toEqual([3]);
    expect(json.providerMeta).toEqual({ x: { a: 1 } });
  });

  test('shipping scalars only when physical', () => {
    const p = valid({
      physical: true,
      weightGrams: 100,
      lengthMm: 10,
      hsCode: '1234.56',
      originCountry: 'DE',
    });
    const on = buildPayload(p, SINGLE);
    expect(scalar(on, 'weightGrams')).toBe('100');
    expect(scalar(on, 'hsCode')).toBe('1234.56');
    const off = buildPayload({ ...p, physical: false }, SINGLE);
    expect(scalar(off, 'weightGrams')).toBeUndefined();
    expect(scalar(off, 'hsCode')).toBeUndefined();
  });
});

describe('loading and round trip', () => {
  const api = {
    id: 41,
    slug: 'vip',
    name: 'VIP',
    description: '<p>x</p>',
    categoryId: 2,
    kind: 'STANDARD',
    status: 'ARCHIVED',
    price: 9.5,
    creditPrice: 0,
    compareAtPrice: 12,
    vatPercent: null,
    billingMode: 'TIMED',
    periodCount: 30,
    periodUnit: 'DAY',
    stock: null,
    allowGift: false,
    hasVariants: true,
    durationType: 'TEMPORARY',
    durationStart: 1767225600000,
    durationExpiry: 1769904000000,
    variantOptions: [{ key: 'tier', label: 'Tier', values: [{ key: 'gold', label: 'Gold' }] }],
    variants: [
      {
        id: 7,
        name: 'Gold',
        optionValues: { tier: 'gold' },
        attributes: { color: 'gold' },
        price: 20,
        stock: 3,
        status: 'ACTIVE',
        position: 1,
        imageFileName: 'g.png',
      },
      { id: 6, name: 'Plain', optionValues: {}, status: 'INACTIVE', position: 0 },
    ],
    prices: [
      { variantId: 0, currency: 'EUR', price: 8, compareAtPrice: null },
      { variantId: 7, currency: 'EUR', price: 18, compareAtPrice: null },
    ],
    actions: [{ id: 'a1', type: 'CREDIT', phase: 'GRANT', value: 3 }],
    bundleItems: [],
  };

  test('maps columns, locks saved keys, splits per-currency rows between product and variants', () => {
    const p = fromApi(api, MULTI);
    expect(p.dbId).toBe(41);
    expect(p.status).toBe('ARCHIVED');
    expect(p.allowGift).toBe(false);
    expect(p.hasStockLimit).toBe(false);
    expect(p.saleWindow).toBe(true);
    expect(p.durationStart).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/);
    expect(p.variantOptions[0].locked).toBe(true);
    expect(p.variantOptions[0].values[0].locked).toBe(true);
    expect(p.variants.map((v) => v.id)).toEqual([6, 7]);
    expect(p.variants[1].attributes).toEqual([{ key: 'color', value: 'gold' }]);
    expect(p.variants[1].imageFileName).toBe('g.png');
    expect(p.variants[1].status).toBe('ACTIVE');
    expect(p.variants[0].status).toBe('INACTIVE');
    expect(p.prices.find((r) => r.currency === 'EUR').price).toBe(8);
    expect(p.variants[1].prices.find((r) => r.currency === 'EUR').price).toBe(18);
    expect(p.variants[1].prices.find((r) => r.currency === 'JPY').price).toBeNull();
    expect(p.actions).toEqual(api.actions);
  });

  test('unknown enum values fall back instead of leaking into the form', () => {
    const p = fromApi({ ...api, kind: 'X', status: 'Y', billingMode: 'Z' }, SINGLE);
    expect([p.kind, p.status, p.billingMode]).toEqual(['STANDARD', 'ACTIVE', 'ONE_TIME']);
  });

  test('a loaded product validates and saves back with the same ids and prices', () => {
    const p = fromApi(api, MULTI);
    expect(errorsOf(p, MULTI, { isEdit: true })).toEqual({});
    const { json, scalars } = buildPayload(p, MULTI, { isEdit: true });
    expect(json.variants.map((v) => v.id)).toEqual([6, 7]);
    expect(json.variants.every((v) => !('stock' in v))).toBe(true);
    expect(json.prices).toEqual([
      { variantId: 0, currency: 'EUR', price: 8, compareAtPrice: null },
      { variantId: 7, currency: 'EUR', price: 18, compareAtPrice: null },
    ]);
    expect(json.actions[0].id).toBe('a1');
    expect(scalars.find(([k]) => k === 'stock')).toBeUndefined();
  });

  test('keyFromLabel keys of a loaded axis stay untouched by the form', () => {
    const p = fromApi(api, SINGLE);
    expect(p.variantOptions[0].key).toBe(keyFromLabel('Tier'));
  });
});

describe('server errors', () => {
  test('dotted paths pass through, indexed price paths map to currency paths', () => {
    const map = { 'prices.0.price': 'prices.EUR.price' };
    expect(
      mapServerErrors(
        {
          'variants.2.price': 'OUT_OF_RANGE',
          'prices.0.price': 'OUT_OF_RANGE',
          slug: 'RESERVED_SLUG',
        },
        map,
      ),
    ).toEqual({
      'variants.2.price': 'OUT_OF_RANGE',
      'prices.EUR.price': 'OUT_OF_RANGE',
      slug: 'RESERVED_SLUG',
    });
    expect(mapServerErrors({ a: { x: 1 } })).toEqual({ a: 'INVALID' });
    expect(mapServerErrors(null)).toEqual({});
  });

  test('a mapped server error switches to the tab of its input', () => {
    const errors = mapServerErrors({
      'variants.2.price': 'OUT_OF_RANGE',
      'fields.0.fieldKey': 'DUPLICATE',
    });
    expect(tabsWithErrors(errors)).toEqual(['variants', 'fields']);
    expect(tabOfPath(firstErrorPath(errors))).toBe('variants');
  });
});

describe('dirty snapshot', () => {
  test('changes with state and counts a selected file', () => {
    const p = valid();
    const a = snapshot(p);
    expect(snapshot(valid())).toBe(a);
    expect(snapshot({ ...p, name: 'Other' })).not.toBe(a);
    const withFile = { ...p, variants: [{ imageFile: new File(['x'], 'v.png') }] };
    const withOther = { ...p, variants: [{ imageFile: new File(['xy'], 'v.png') }] };
    expect(snapshot(withFile)).not.toBe(snapshot(withOther));
  });
});

describe('locales', () => {
  const dir = path.resolve(import.meta.dir, '../../../locales/panel');
  const locales = ['en-US', 'tr', 'ru'].map((l) => [
    l,
    JSON.parse(readFileSync(path.join(dir, `${l}.json`), 'utf8')),
  ]);
  const lookup = (obj, key) => key.split('.').reduce((o, k) => (o == null ? undefined : o[k]), obj);

  test.each(locales)('%s has a text for every field error code and tab', (_name, json) => {
    for (const code of FIELD_ERROR_CODES) {
      const key = fieldErrorKey(code).replace(/^/, '');
      expect(typeof lookup(json, key)).toBe('string');
    }
    for (const id of TAB_IDS)
      expect(typeof lookup(json, `pages.create-product.tabs.${id}`)).toBe('string');
  });

  test('unknown codes read as INVALID', () => {
    expect(fieldErrorKey('NOPE')).toBe('pages.create-product.field-errors.INVALID');
    expect(fieldErrorKey('TOO_LONG')).toBe('pages.create-product.field-errors.TOO_LONG');
  });
});
