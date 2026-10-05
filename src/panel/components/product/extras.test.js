import { describe, expect, test } from 'bun:test';
import { newAction } from '../../utils/actions.js';
import { blankField } from './fields.js';
import {
  extraErrorKey,
  joinCooldown,
  maxQuantityLocked,
  splitCooldown,
  validateExtras,
  validateLimits,
  validateSeo,
  validateShipping,
} from './extras.js';
import { COUNTRY_CODES, countryName, countryOptions } from './countries.js';

const physical = (extra = {}) => ({ physical: true, ...extra });

describe('validateShipping', () => {
  test('nothing is checked for a non-physical product', () => {
    expect(validateShipping({ physical: false, hsCode: 'x', sku: 'y'.repeat(99) })).toEqual({});
  });
  test('dimensions: all three or none, integers >= 1', () => {
    expect(validateShipping(physical({ lengthMm: 10, widthMm: 5 })).heightMm).toBe('ALL_OR_NONE');
    expect(validateShipping(physical({ lengthMm: 10, widthMm: 5, heightMm: 2 }))).toEqual({});
    expect(validateShipping(physical({ lengthMm: 0, widthMm: 5, heightMm: 2 })).lengthMm).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateShipping(physical({ lengthMm: 1.5, widthMm: 5, heightMm: 2 })).lengthMm).toBe(
      'NOT_INTEGER',
    );
    expect(validateShipping(physical({}))).toEqual({});
  });
  test('weight is an integer >= 1 when given', () => {
    expect(validateShipping(physical({ weightGrams: 0 })).weightGrams).toBe('OUT_OF_RANGE');
    expect(validateShipping(physical({ weightGrams: 250 }))).toEqual({});
  });
  test('sku <= 64, hsCode 4-16 digits and dots, country must be ISO alpha-2', () => {
    expect(validateShipping(physical({ sku: 'x'.repeat(65) })).sku).toBe('TOO_LONG');
    expect(validateShipping(physical({ hsCode: '123' })).hsCode).toBe('INVALID_FORMAT');
    expect(validateShipping(physical({ hsCode: '6109.10' }))).toEqual({});
    expect(validateShipping(physical({ hsCode: '1'.repeat(17) })).hsCode).toBe('INVALID_FORMAT');
    expect(validateShipping(physical({ originCountry: 'XX' })).originCountry).toBe('INVALID');
    expect(validateShipping(physical({ originCountry: 'tr' })).originCountry).toBe('INVALID');
    expect(validateShipping(physical({ originCountry: 'TR' }))).toEqual({});
  });
});

describe('limits and SEO', () => {
  test('limits are integers >= 1 or empty', () => {
    expect(validateLimits({ limitPerPlayer: 0 }).limitPerPlayer).toBe('OUT_OF_RANGE');
    expect(validateLimits({ maxQuantityPerOrder: 1.5 }).maxQuantityPerOrder).toBe('NOT_INTEGER');
    expect(validateLimits({ cooldownSeconds: -60 }).cooldownSeconds).toBe('OUT_OF_RANGE');
    expect(
      validateLimits({ limitPerPlayer: null, maxQuantityPerOrder: '', cooldownSeconds: 60 }),
    ).toEqual({});
  });
  test('maxQuantityPerOrder is locked to 1 for timed / subscription and tiered products', () => {
    expect(maxQuantityLocked({ billingMode: 'ONE_TIME', tierRank: null })).toBe(false);
    expect(maxQuantityLocked({ billingMode: 'TIMED', tierRank: null })).toBe(true);
    expect(maxQuantityLocked({ billingMode: 'SUBSCRIPTION' })).toBe(true);
    expect(maxQuantityLocked({ billingMode: 'ONE_TIME', tierRank: 0 })).toBe(true);
  });
  test('SEO lengths: title 255, description 512', () => {
    expect(validateSeo({ metaTitle: 'x'.repeat(256) }).metaTitle).toBe('TOO_LONG');
    expect(validateSeo({ metaDescription: 'x'.repeat(513) }).metaDescription).toBe('TOO_LONG');
    expect(validateSeo({ metaTitle: 'x'.repeat(255), metaDescription: 'x'.repeat(512) })).toEqual(
      {},
    );
  });
});

describe('cooldown helpers', () => {
  test('split chooses the largest unit that divides evenly', () => {
    expect(splitCooldown(86400)).toEqual({ value: 1, unit: 'DAY' });
    expect(splitCooldown(172800)).toEqual({ value: 2, unit: 'DAY' });
    expect(splitCooldown(7200)).toEqual({ value: 2, unit: 'HOUR' });
    expect(splitCooldown(90)).toEqual({ value: 2, unit: 'MINUTE' });
    expect(splitCooldown(300)).toEqual({ value: 5, unit: 'MINUTE' });
    expect(splitCooldown(null)).toEqual({ value: '', unit: 'MINUTE' });
    expect(splitCooldown(0)).toEqual({ value: '', unit: 'MINUTE' });
  });
  test('join multiplies and rejects non-integers', () => {
    expect(joinCooldown(2, 'HOUR')).toBe(7200);
    expect(joinCooldown('3', 'DAY')).toBe(259200);
    expect(joinCooldown('', 'DAY')).toBeNull();
    expect(joinCooldown(0, 'DAY')).toBeNaN();
    expect(joinCooldown(1.5, 'HOUR')).toBeNaN();
  });
  test('split then join is stable', () => {
    for (const s of [60, 3600, 86400, 604800, 120]) {
      const { value, unit } = splitCooldown(s);
      expect(joinCooldown(value, unit)).toBe(s);
    }
  });
});

describe('countries', () => {
  test('codes are unique upper-case alpha-2', () => {
    expect(new Set(COUNTRY_CODES).size).toBe(COUNTRY_CODES.length);
    for (const code of COUNTRY_CODES) expect(code).toMatch(/^[A-Z]{2}$/);
    expect(COUNTRY_CODES).toContain('TR');
    expect(COUNTRY_CODES).toContain('DE');
  });
  test('names resolve through Intl, options are sorted, unknown falls back to the code', () => {
    expect(countryName('DE', 'en-US')).toBe('Germany');
    const options = countryOptions('en-US');
    expect(options).toHaveLength(COUNTRY_CODES.length);
    const names = options.map((o) => o.name);
    expect([...names].sort((a, b) => a.localeCompare(b, 'en-US'))).toEqual(names);
  });
});

describe('validateExtras', () => {
  const server = [{ id: 1 }];
  test('a default product has no extra errors', () => {
    const product = {
      physical: false,
      billingMode: 'ONE_TIME',
      fields: [],
      actions: [],
      serverChoices: [],
      providerMeta: {},
    };
    expect(validateExtras(product, null, { servers: server })).toEqual({});
  });
  test('collects field, shipping, limit, SEO, meta and action errors under their paths', () => {
    const product = {
      physical: true,
      billingMode: 'ONE_TIME',
      hsCode: 'x',
      limitPerPlayer: 0,
      metaTitle: 'x'.repeat(300),
      fields: [blankField({ fieldKey: '', label: 'A' })],
      actions: [{ ...newAction('COMMAND'), value: [] }],
      serverChoices: [],
      providerMeta: {},
    };
    const ctx = {
      productMetaSchemas: [
        {
          providerId: 'p',
          name: 'P',
          schema: { fields: [{ key: 'k', type: 'TEXT', required: true }] },
        },
      ],
    };
    const errors = validateExtras(product, ctx, { servers: server });
    expect(errors['fields.0.fieldKey']).toBe('REQUIRED');
    expect(errors.hsCode).toBe('INVALID_FORMAT');
    expect(errors.limitPerPlayer).toBe('OUT_OF_RANGE');
    expect(errors.metaTitle).toBe('TOO_LONG');
    expect(errors['providerMeta.p.k']).toBe('REQUIRED');
    expect(errors['actions.0.value']).toBe('INVALID_VALUE');
  });
});

describe('extraErrorKey', () => {
  test('every code the checks raise has its own key, unknown ones read as invalid', () => {
    expect(extraErrorKey('ALL_OR_NONE')).toBe('pages.create-product.field-errors.ALL_OR_NONE');
    expect(extraErrorKey('OUT_OF_RANGE')).toBe('pages.create-product.field-errors.OUT_OF_RANGE');
    expect(extraErrorKey('???')).toBe('pages.create-product.field-errors.INVALID');
  });
});
