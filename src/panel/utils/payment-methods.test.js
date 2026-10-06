import { describe, expect, test } from 'bun:test';
import {
  actionBlocked,
  buildConfig,
  capabilityRows,
  configErrorCode,
  configToForm,
  currencyChoices,
  effectiveTestMode,
  filterProviders,
  importedCounts,
  isHttpUrl,
  onlyBuiltIns,
  logoPath,
  movedIds,
  pricingLocked,
  providerName,
  regionOf,
  rowBehavior,
  sortProviders,
  tabOfFirstClientError,
  tabOfFirstError,
  testModeControl,
  validateConfig,
  webhookRows,
} from './payment-methods.js';

const provider = (id, extra = {}) => ({
  id,
  state: 'ACTIVE',
  descriptor: { name: { default: id.toUpperCase() }, region: 'global' },
  config: { position: 0, ...(extra.config ?? {}) },
  capabilities: {},
  ...Object.fromEntries(Object.entries(extra).filter(([k]) => k !== 'config')),
});

describe('list order and filters', () => {
  test('ascending position, ties by descriptor name, custom label ignored for sorting', () => {
    const list = [
      provider('c', { config: { position: 2 } }),
      provider('b', { config: { position: 1, customLabel: 'AAA first by label' } }),
      provider('a', { config: { position: 1 } }),
      provider('d', { config: { position: 0 } }),
    ];
    expect(sortProviders(list).map((p) => p.id)).toEqual(['d', 'a', 'b', 'c']);
  });
  test('the input array is not mutated', () => {
    const list = [provider('b', { config: { position: 1 } }), provider('a', { config: { position: 0 } })];
    sortProviders(list);
    expect(list.map((p) => p.id)).toEqual(['b', 'a']);
  });
  test('region filter and search over name and id', () => {
    const list = [
      provider('stripe'),
      provider('paytr', { descriptor: { name: { default: 'PayTR' }, region: 'tr' } }),
      provider('iyzico', { descriptor: { name: { default: 'Iyzico' }, region: 'tr' }, config: { customLabel: 'Kart' } }),
    ];
    expect(filterProviders(list, { region: 'tr' }).map((p) => p.id)).toEqual(['paytr', 'iyzico']);
    expect(filterProviders(list, { region: 'global' }).map((p) => p.id)).toEqual(['stripe']);
    expect(filterProviders(list, { search: 'PAY' }).map((p) => p.id)).toEqual(['paytr']);
    expect(filterProviders(list, { search: 'kart' }).map((p) => p.id)).toEqual(['iyzico']);
    expect(filterProviders(list, { search: 'iyz' }).map((p) => p.id)).toEqual(['iyzico']);
    expect(filterProviders(list, {}).length).toBe(3);
  });
  test('regionOf only accepts tr and global', () => {
    expect(regionOf('tr')).toBe('tr');
    expect(regionOf('global')).toBe('global');
    expect(regionOf('de')).toBe('all');
    expect(regionOf(null)).toBe('all');
  });
  test('name: custom label, else the resolved descriptor name', () => {
    expect(providerName(provider('x', { config: { customLabel: 'My Card' } }))).toBe('My Card');
    expect(providerName(provider('x', { config: { customLabel: '  ' } }))).toBe('X');
    const p = { id: 'y', descriptor: { name: { default: 'Y', translations: { tr: 'Why' } } }, config: {} };
    expect(providerName(p, 'tr-TR')).toBe('Why');
  });
  test('logo route is the public provider logo path', () => {
    expect(logoPath('/panel', 'stripe')).toBe('/panel/api/market/payment-providers/stripe/logo');
    expect(logoPath('', 'a b')).toBe('/api/market/payment-providers/a%20b/logo');
    expect(logoPath('', 'x')).not.toContain('favicon');
  });
  test('the provider card asks for the logo at the site root, not below the /panel base', async () => {
    // The browser smoke (E2E-13) saw five 404s: /panel/api/... is the panel UI's own API prefix, the market route lives at /api/market/...
    const source = await Bun.file(new URL('../components/settings/PaymentMethods.svelte', import.meta.url)).text();
    expect(source).toContain("logoPath('', provider.id)");
    expect(source).not.toContain('logoPath(base,');
    // a provider without a logo answers 404 on that route, which the browser reports as a console error: no request is made for it
    expect(source).toContain('provider.descriptor?.logoUrl && !logoFailed[provider.id]');
  });
});

describe('per-state behaviour (13 §16.1)', () => {
  test('ACTIVE: on and enabled, editable', () => {
    expect(rowBehavior({ state: 'ACTIVE' })).toMatchObject({
      switchOn: true,
      switchDisabled: false,
      readOnly: false,
      danger: false,
    });
  });
  test('DISABLED: off but enabled, editable', () => {
    expect(rowBehavior({ state: 'DISABLED' })).toMatchObject({
      switchOn: false,
      switchDisabled: false,
      readOnly: false,
    });
  });
  test('NOT_CONFIGURED: switch disabled with the configure-first hint, still editable', () => {
    expect(rowBehavior({ state: 'NOT_CONFIGURED' })).toMatchObject({
      switchOn: false,
      switchDisabled: true,
      switchHint: 'configure-first',
      readOnly: false,
    });
  });
  test('INCOMPATIBLE: read-only, danger, incompatible reason', () => {
    expect(rowBehavior({ state: 'INCOMPATIBLE' })).toMatchObject({
      switchOn: false,
      switchDisabled: true,
      readOnly: true,
      danger: true,
      reason: 'incompatible',
    });
  });
  test('UNAVAILABLE rows are read-only with the unavailable reason', () => {
    expect(rowBehavior({ state: 'UNAVAILABLE' })).toMatchObject({
      switchOn: false,
      switchDisabled: true,
      readOnly: true,
      danger: true,
      reason: 'unavailable',
    });
  });
  test('an unknown state is read-only and locked', () => {
    expect(rowBehavior({ state: 'WHATEVER' })).toMatchObject({ readOnly: true, switchDisabled: true, switchOn: false });
    expect(rowBehavior(null).readOnly).toBe(true);
  });
});

describe('reordering', () => {
  const list = ['a', 'b', 'c'].map((id) => provider(id));
  test('moves one entry and returns the full id list', () => {
    expect(movedIds(list, 'b', -1)).toEqual(['b', 'a', 'c']);
    expect(movedIds(list, 'b', 1)).toEqual(['a', 'c', 'b']);
  });
  test('edges and unknown ids return null', () => {
    expect(movedIds(list, 'a', -1)).toBeNull();
    expect(movedIds(list, 'c', 1)).toBeNull();
    expect(movedIds(list, 'zz', 1)).toBeNull();
  });
});

describe('test mode', () => {
  test('control kind follows the capability', () => {
    expect(testModeControl({ testMode: 'NONE' })).toBe('hidden');
    expect(testModeControl({ testMode: 'DERIVED' })).toBe('derived');
    expect(testModeControl({ testMode: 'FLAG' })).toBe('flag');
    expect(testModeControl({})).toBe('flag');
  });
  test('effective test mode per capability', () => {
    expect(effectiveTestMode(provider('a', { config: { testMode: true } }), { testMode: false })).toBe(true);
    expect(effectiveTestMode(provider('a'), { testMode: true })).toBe(true);
    expect(effectiveTestMode(provider('a'), { testMode: false })).toBe(false);
    const derived = provider('a', { capabilities: { testMode: 'DERIVED', derivedTestMode: true } });
    expect(effectiveTestMode(derived, { testMode: false })).toBe(true);
    const none = provider('a', { capabilities: { testMode: 'NONE' }, config: { testMode: true } });
    expect(effectiveTestMode(none, { testMode: true })).toBe(false);
  });
});

describe('rules form', () => {
  const ctx = {
    currency: 'USD',
    additionalCurrencies: ['EUR', 'TRY'],
    currencies: [{ code: 'USD' }, { code: 'EUR' }, { code: 'TRY' }, { code: 'GBP' }],
  };
  test('currency choices are limited to the store currencies', () => {
    expect(currencyChoices({ currencies: null }, ctx)).toEqual(['USD', 'EUR', 'TRY']);
    expect(currencyChoices({ currencies: ['EUR', 'GBP'] }, ctx)).toEqual(['EUR']);
    expect(currencyChoices({}, null)).toEqual([]);
  });
  test('configToForm maps null currencies to the all-supported switch', () => {
    expect(configToForm({ currencies: null }).allCurrencies).toBe(true);
    const f = configToForm({ currencies: ['EUR'], feeMode: 'BUYER', feePercent: 2.9, feeFixed: 0.3 });
    expect(f).toMatchObject({ allCurrencies: false, currencies: ['EUR'], feeBuyer: true, feePercent: 2.9, feeFixed: 0.3 });
    expect(configToForm().feeBuyer).toBe(false);
  });
  const base = () => configToForm({ currencies: null });
  test('a valid form has no errors', () => {
    expect(validateConfig(base())).toEqual({});
  });
  test('label and description length', () => {
    expect(validateConfig({ ...base(), customLabel: 'x'.repeat(256) }).customLabel).toBe('TOO_LONG');
    expect(validateConfig({ ...base(), customLabel: 'x'.repeat(255) }).customLabel).toBeUndefined();
    expect(validateConfig({ ...base(), customDescription: 'x'.repeat(513) }).customDescription).toBe('TOO_LONG');
  });
  test('buyer fee with both values 0 marks the percent field', () => {
    expect(validateConfig({ ...base(), feeBuyer: true, feePercent: 0, feeFixed: 0 }).feePercent).toBe('FEE_REQUIRED');
    expect(validateConfig({ ...base(), feeBuyer: true, feePercent: 0, feeFixed: 1 })).toEqual({});
    expect(validateConfig({ ...base(), feeBuyer: true, feePercent: 2.5, feeFixed: 0 })).toEqual({});
  });
  test('percent range and decimals', () => {
    expect(validateConfig({ ...base(), feeBuyer: true, feePercent: 100.5, feeFixed: 0 }).feePercent).toBe('OUT_OF_RANGE');
    expect(validateConfig({ ...base(), feeBuyer: true, feePercent: -1, feeFixed: 0 }).feePercent).toBe('OUT_OF_RANGE');
    expect(validateConfig({ ...base(), feeBuyer: true, feePercent: 1.234, feeFixed: 0 }).feePercent).toBe('INVALID');
    expect(validateConfig({ ...base(), feeBuyer: true, feePercent: NaN, feeFixed: 0 }).feePercent).toBe('INVALID');
    expect(validateConfig({ ...base(), feeBuyer: true, feePercent: 100, feeFixed: 0 })).toEqual({});
  });
  test('fee values are not validated while the fee is not passed on', () => {
    expect(validateConfig({ ...base(), feeBuyer: false, feePercent: NaN, feeFixed: NaN })).toEqual({});
  });
  test('min must not exceed max when both are set', () => {
    expect(validateConfig({ ...base(), minAmount: 10, maxAmount: 5 }).maxAmount).toBe('MIN_GT_MAX');
    expect(validateConfig({ ...base(), minAmount: 5, maxAmount: 5 })).toEqual({});
    expect(validateConfig({ ...base(), minAmount: 10, maxAmount: null })).toEqual({});
    expect(validateConfig({ ...base(), minAmount: NaN }).minAmount).toBe('INVALID');
  });
  test('at least one currency when "all supported" is off', () => {
    expect(validateConfig({ ...base(), allCurrencies: false, currencies: [] }).currencies).toBe('REQUIRED');
    expect(validateConfig({ ...base(), allCurrencies: false, currencies: ['USD'] })).toEqual({});
  });
  test('fee and amount rules are skipped while the gateway owns the price', () => {
    const caps = { priceAuthority: 'GATEWAY_CATALOG' };
    expect(pricingLocked(caps)).toBe(true);
    expect(pricingLocked({ priceAuthority: 'MARKET' })).toBe(false);
    expect(pricingLocked({})).toBe(false);
    expect(
      validateConfig({ ...base(), feeBuyer: true, feePercent: 0, feeFixed: 0, minAmount: 9, maxAmount: 1 }, { capabilities: caps }),
    ).toEqual({});
  });
  test('buildConfig sends decimals, null for empty text and null for all currencies', () => {
    const form = { ...base(), customLabel: '  Card  ', customDescription: '', feeBuyer: true, feePercent: 2.9, feeFixed: 0.3, minAmount: 1, testMode: true };
    expect(buildConfig(form)).toEqual({
      customLabel: 'Card',
      customDescription: null,
      feeMode: 'BUYER',
      feePercent: 2.9,
      feeFixed: 0.3,
      minAmount: 1,
      maxAmount: null,
      currencies: null,
      testMode: true,
    });
    expect(buildConfig({ ...base(), allCurrencies: false, currencies: ['EUR'] }).currencies).toEqual(['EUR']);
    expect(buildConfig({ ...base(), feeBuyer: false }).feeMode).toBe('NONE');
  });
  test('buildConfig keeps the loaded values while the gateway owns the price and the test flag it cannot edit', () => {
    const original = { feeMode: 'BUYER', feePercent: 3, feeFixed: 1, minAmount: 2, maxAmount: 99, testMode: true };
    const form = { ...configToForm(original), feePercent: 50, minAmount: 7, testMode: false };
    const out = buildConfig(form, { capabilities: { priceAuthority: 'GATEWAY_ADDS_TAX', testMode: 'DERIVED' }, original });
    expect(out).toMatchObject({ feeMode: 'BUYER', feePercent: 3, feeFixed: 1, minAmount: 2, maxAmount: 99, testMode: true });
    expect(buildConfig(form, { capabilities: { testMode: 'NONE' }, original: { testMode: false } }).testMode).toBe(false);
  });
});

describe('error tabs, status rows and actions', () => {
  const schema = { fields: [{ key: 'merchantId' }, { key: 'apiKey' }] };
  test('first invalid key picks the tab', () => {
    expect(tabOfFirstError({ apiKey: {} }, schema)).toBe('settings');
    expect(tabOfFirstError({ feePercent: 'x' }, schema)).toBe('rules');
    expect(tabOfFirstError({ feePercent: 'x', merchantId: 'y' }, schema)).toBe('settings');
    expect(tabOfFirstError({}, schema)).toBe('settings');
    expect(tabOfFirstClientError({ a: 'X' }, { b: 'Y' })).toBe('settings');
    expect(tabOfFirstClientError({}, { b: 'Y' })).toBe('rules');
    expect(tabOfFirstClientError({}, {})).toBeNull();
  });
  test('capability rows', () => {
    const rows = capabilityRows({ refund: 'PARTIAL', recurring: 'GATEWAY_MANAGED', statusQuery: true, guests: false, currencies: ['USD'] });
    const by = Object.fromEntries(rows.map((r) => [r.key, r]));
    expect(by.refund).toMatchObject({ kind: 'enum', enum: 'refund-support', value: 'PARTIAL' });
    expect(by.recurring.value).toBe('GATEWAY_MANAGED');
    expect(by['status-query'].value).toBe(true);
    expect(by.disputes.value).toBe(false);
    expect(by.guests.value).toBe(false);
    expect(by['physical-goods'].value).toBe(true);
    expect(by['mixed-credit'].value).toBe(true);
    expect(by.currencies).toMatchObject({ kind: 'list', value: ['USD'] });
    expect(capabilityRows({}, { currencies: [{ code: 'EUR' }] }).find((r) => r.key === 'currencies').value).toEqual(['EUR']);
  });
  test('webhook rows: default channel first', () => {
    expect(webhookRows({ subscription: 'u2', default: 'u1', a: 'u3' }).map(([c]) => c)).toEqual(['default', 'a', 'subscription']);
    expect(webhookRows(null)).toEqual([]);
  });
  test('an action that needs saved settings is blocked while the form is dirty', () => {
    expect(actionBlocked({ requiresSavedSettings: true }, true)).toBe(true);
    expect(actionBlocked({ requiresSavedSettings: true }, false)).toBe(false);
    expect(actionBlocked({ requiresSavedSettings: false }, true)).toBe(false);
  });
  test('server errors of a rules key map to a known code', () => {
    expect(configErrorCode('MIN_GT_MAX')).toBe('MIN_GT_MAX');
    expect(configErrorCode('SOMETHING')).toBe('INVALID');
    expect(configErrorCode({ default: 'x' })).toBe('INVALID');
  });
  test('built-in detection and http(s) links', () => {
    expect(onlyBuiltIns([])).toBe(true);
    expect(onlyBuiltIns([{ id: 'bank-transfer' }, { id: 'x', pluginId: 'pano-plugin-market' }])).toBe(true);
    expect(onlyBuiltIns([{ id: 'bank-transfer' }, { id: 'stripe', pluginId: 'pano-plugin-market-stripe' }])).toBe(false);
    expect(isHttpUrl('https://panomc.com/store')).toBe(true);
    expect(isHttpUrl('javascript:alert(1)')).toBe(false);
    expect(isHttpUrl(null)).toBe(false);
  });
  test('imported counts', () => {
    expect(importedCounts({ imported: { categories: 2, products: 5 } })).toEqual({ categories: 2, products: 5 });
    expect(importedCounts({})).toBeNull();
    expect(importedCounts({ imported: { categories: 'x' } })).toEqual({ categories: 0, products: 0 });
  });
});
