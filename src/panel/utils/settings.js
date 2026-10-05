// Pure core of the settings sections general / checkout / currencies / billing / legal (13 §17).
// No Svelte or SDK import: the components inject the host calls, so every rule here is unit tested.
//
// Ranges follow MarketConfigKeys.kt (the table that validates POST /settings). Where the design text
// allows more than the backend (store page size 60 vs 100, seller name 255 vs 120, seller address 1000
// vs 500) the stricter value wins, so a value the form accepts is never refused by the server.
import { parseInteger } from './format.js';

// ---------------------------------------------------------------------------------------------
// Field table
// ---------------------------------------------------------------------------------------------

const bool = (def) => ({ type: 'bool', def });
const int = (def, min, max) => ({ type: 'int', def, min, max });
const num = (def, min, max, extra = {}) => ({ type: 'number', def, min, max, ...extra });
const str = (def, max, extra = {}) => ({ type: 'string', def, max, ...extra });
const oneOf = (def, values) => ({ type: 'enum', def, values });

export const CURRENCY_MODES = ['SINGLE', 'DISPLAY', 'MULTI'];
export const MULTI_FALLBACKS = ['CONVERT', 'HIDE'];
export const RATE_MODES = ['AUTO', 'MANUAL'];
export const BILLING_INFO_MODES = ['OFF', 'OPTIONAL', 'REQUIRED'];
export const EXCHANGE_RATE_MODES = ['AUTO', 'MANUAL'];

export const INVOICE_SERIES = /^[A-Z0-9]{1,8}$/;
const LOCALE = /^[a-z]{2,3}(-[A-Za-z]{2,4})?$/;

/** Config keys of 00 §12 that these sections edit, with type, default and range. */
export const FIELDS = {
  // general
  storeName: str('Market', 64),
  storeDescription: str('', 1000),
  storeEnabled: bool(true),
  storeTimeZone: str('', 64),
  currency: str('TRY', 3, { code: true }),
  statsCurrency: str('TRY', 3, { code: true }),
  exchangeRateMode: oneOf('AUTO', EXCHANGE_RATE_MODES),
  exchangeRate: num(1, 0.000001, 1_000_000_000),
  exchangeRateAutoIntervalHours: int(6, 1, 168),
  vatPercent: num(20, 0, 100),
  showVatInPrice: bool(true),
  testMode: bool(false),
  removeCents: bool(false),
  showBestsellers: bool(true),
  showFeaturedProducts: bool(true),
  showComparisons: bool(true),
  storePageSize: int(24, 1, 60),
  // checkout
  allowGuestCheckout: bool(true),
  allowGiftPurchase: bool(true),
  minimumOrderAmount: num(0, 0, 1_000_000_000, { emptyAs: 0 }),
  combineDiscountsAndCoupons: bool(true),
  orderExpiryMinutes: int(60, 5, 10_080),
  bankTransferExpiryHours: int(72, 1, 720),
  autoRefundDuplicatePayments: bool(true),
  subscriptionManualFallback: bool(true),
  // currencies
  currencyMode: oneOf('SINGLE', CURRENCY_MODES),
  additionalCurrencies: { type: 'list', def: [] },
  multiCurrencyFallback: oneOf('CONVERT', MULTI_FALLBACKS),
  // billing
  billingInfoMode: oneOf('OPTIONAL', BILLING_INFO_MODES),
  invoiceEnabled: bool(true),
  invoiceSeries: str('INV', 8, { series: true }),
  invoiceCreditNoteSeries: str('CN', 8, { series: true }),
  invoiceCreditOrders: bool(false),
  invoiceLocale: str('', 16, { pattern: LOCALE }),
  invoiceShowLogo: bool(true),
  invoiceSellerName: str('', 120),
  invoiceSellerAddress: str('', 500),
  invoiceSellerTaxOffice: str('', 120),
  invoiceSellerTaxNumber: str('', 64),
  invoiceFooter: str('', 1000),
  // legal
  legalTextRequired: bool(false),
};

export const SECTION_KEYS = {
  general: [
    'storeName',
    'storeDescription',
    'storeEnabled',
    'storeTimeZone',
    'storePageSize',
    'currency',
    'statsCurrency',
    'exchangeRateMode',
    'exchangeRate',
    'exchangeRateAutoIntervalHours',
    'vatPercent',
    'showVatInPrice',
    'testMode',
    'removeCents',
    'showBestsellers',
    'showFeaturedProducts',
    'showComparisons',
  ],
  checkout: [
    'allowGuestCheckout',
    'allowGiftPurchase',
    'minimumOrderAmount',
    'combineDiscountsAndCoupons',
    'orderExpiryMinutes',
    'bankTransferExpiryHours',
    'autoRefundDuplicatePayments',
    'subscriptionManualFallback',
  ],
  currencies: ['currencyMode', 'additionalCurrencies', 'multiCurrencyFallback'],
  billing: [
    'billingInfoMode',
    'invoiceEnabled',
    'invoiceSeries',
    'invoiceCreditNoteSeries',
    'invoiceCreditOrders',
    'invoiceLocale',
    'invoiceShowLogo',
    'invoiceSellerName',
    'invoiceSellerAddress',
    'invoiceSellerTaxOffice',
    'invoiceSellerTaxNumber',
    'invoiceFooter',
  ],
  legal: ['legalTextRequired'],
};

/** Every key the five sections own (used by the coverage test against 00 §12). */
export const OWNED_KEYS = Object.values(SECTION_KEYS).flat();

const clone = (value) => (Array.isArray(value) ? [...value] : value);

/** Loaded value of a key, or the documented default when the server did not send it. */
export function seedValue(settings, key) {
  const value = settings?.[key];
  return clone(value === undefined || value === null ? FIELDS[key].def : value);
}

/** `{ key: value }` of the keys, from GET /settings. Lists are copied. */
export function seedValues(settings, keys) {
  return Object.fromEntries(keys.map((key) => [key, seedValue(settings, key)]));
}

// ---------------------------------------------------------------------------------------------
// Values: compare, normalise, body
// ---------------------------------------------------------------------------------------------

function isEmptyNumber(value) {
  return value === null || value === undefined || value === '';
}

/** Value as the API wants it (numbers as Number, empty `emptyAs` fields as their fallback). */
export function normalizeValue(key, value) {
  const field = FIELDS[key];
  if (field.type === 'int' || field.type === 'number') {
    if (isEmptyNumber(value)) return field.emptyAs ?? null;
    return Number(value);
  }
  if (field.type === 'list') return [...(value ?? [])];
  if (field.type === 'string' && field.series && typeof value === 'string') return value.trim();
  return value;
}

export function sameValue(key, a, b) {
  const x = normalizeValue(key, a);
  const y = normalizeValue(key, b);
  if (Array.isArray(x) || Array.isArray(y)) return JSON.stringify(x) === JSON.stringify(y);
  if (typeof x === 'number' && typeof y === 'number')
    return x === y || (Number.isNaN(x) && Number.isNaN(y));
  return x === y;
}

/**
 * Keys of `keys` that the current values do not make irrelevant. The manual exchange rate only
 * counts in MANUAL mode and only while the two currencies differ, so a save never overwrites a
 * freshly fetched rate with a stale editor value.
 */
export function activeKeys(section, values) {
  const keys = SECTION_KEYS[section];
  // While invoices are off the invoice details are hidden, so they are neither validated nor sent.
  if (section === 'billing')
    return values.invoiceEnabled ? keys : ['billingInfoMode', 'invoiceEnabled'];
  if (section !== 'general') return keys;
  const converts = values.currency !== values.statsCurrency;
  return keys.filter((key) => {
    if (key === 'exchangeRate') return converts && values.exchangeRateMode === 'MANUAL';
    if (key === 'exchangeRateAutoIntervalHours')
      return converts && values.exchangeRateMode === 'AUTO';
    return true;
  });
}

/**
 * Partial-update body for POST /settings: only the keys that differ from the loaded values
 * (the endpoint takes any subset and refuses unknown keys, so a section never resends the others).
 */
export function buildSettingsBody(baseline, values, keys) {
  const body = {};
  for (const key of keys) {
    const base = seedValue(baseline, key);
    if (!sameValue(key, base, values[key])) body[key] = normalizeValue(key, values[key]);
  }
  return body;
}

export function isDirtyBody(body) {
  return Object.keys(body).length > 0;
}

// ---------------------------------------------------------------------------------------------
// Validation (one code per field; the component renders settings.field-error.<CODE>)
// ---------------------------------------------------------------------------------------------

/** Codes the forms render. The server's own codes (04 §8 fieldErrors) map into this set. */
export const FIELD_ERROR_CODES = [
  'REQUIRED',
  'INVALID_TYPE',
  'INVALID_VALUE',
  'OUT_OF_RANGE',
  'TOO_LONG',
  'SAME_AS_OTHER_SERIES',
  'CONTAINS_BASE_CURRENCY',
  'NO_ACTIVE_TEXT',
  'TOO_FEW',
  'NOT_UPWARDS',
  'RATE_REQUIRED',
  'TOO_MANY_DECIMALS',
];

export function fieldErrorKey(code) {
  return `settings.field-error.${FIELD_ERROR_CODES.includes(code) ? code : 'INVALID_VALUE'}`;
}

/** Server `fieldErrors` ({ key: CODE | LocalizedText }) -> { key: CODE } with unknown shapes as INVALID_VALUE. */
export function normalizeFieldErrors(fieldErrors) {
  const out = {};
  if (!fieldErrors || typeof fieldErrors !== 'object') return out;
  for (const [key, value] of Object.entries(fieldErrors)) {
    out[key] =
      typeof value === 'string' && FIELD_ERROR_CODES.includes(value) ? value : 'INVALID_VALUE';
  }
  return out;
}

/** Code of one value against its table entry, or null. */
export function validateValue(key, value) {
  const field = FIELDS[key];
  switch (field.type) {
    case 'bool':
      return typeof value === 'boolean' ? null : 'INVALID_TYPE';
    case 'enum':
      return field.values.includes(value) ? null : 'INVALID_VALUE';
    case 'int':
    case 'number': {
      if (isEmptyNumber(value)) return field.emptyAs !== undefined ? null : 'REQUIRED';
      const n = Number(value);
      if (typeof value === 'string' && value.trim() === '') return 'REQUIRED';
      if (!Number.isFinite(n)) return 'INVALID_TYPE';
      if (field.type === 'int' && !Number.isInteger(n)) return 'INVALID_TYPE';
      if (n < field.min || n > field.max) return 'OUT_OF_RANGE';
      return null;
    }
    case 'string': {
      if (typeof value !== 'string') return 'INVALID_TYPE';
      const text = field.series ? value.trim() : value;
      if (text.length > field.max) return 'TOO_LONG';
      if (field.code && !/^[A-Z]{3}$/.test(text)) return 'INVALID_VALUE';
      if (field.series && (!INVOICE_SERIES.test(text) || text === 'TEST')) return 'INVALID_VALUE';
      if (field.pattern && text !== '' && !field.pattern.test(text)) return 'INVALID_VALUE';
      return null;
    }
    case 'list':
      return Array.isArray(value) ? null : 'INVALID_TYPE';
    default:
      return null;
  }
}

function checkKeys(keys, values, errors) {
  for (const key of keys) {
    const code = validateValue(key, values[key]);
    if (code) errors[key] = code;
  }
}

/** The currency codes the form may pick: `ctx.currencies`, plus the codes already stored. */
export function currencyChoices(ctx, ...stored) {
  const codes = (ctx?.currencies ?? []).map((c) => c.code);
  for (const code of stored.flat()) if (code && !codes.includes(code)) codes.push(code);
  return codes;
}

export function currencyExponent(ctx, code) {
  const found = (ctx?.currencies ?? []).find((c) => c.code === code);
  return Number.isInteger(found?.exponent) ? found.exponent : 2;
}

export function currencySymbol(ctx, code) {
  return (ctx?.currencies ?? []).find((c) => c.code === code)?.symbol ?? code;
}

/**
 * General section. `ctx.currencies`, when present, is the list the server accepts; `zones` is the
 * list of `Intl.supportedValuesOf('timeZone')` (null when unavailable: a free text input is used).
 */
export function validateGeneral(values, { ctx = null, zones = null } = {}) {
  const errors = {};
  checkKeys(activeKeys('general', values), values, errors);
  const choices = currencyChoices(ctx);
  for (const key of ['currency', 'statsCurrency']) {
    if (!errors[key] && choices.length > 0 && !choices.includes(values[key]))
      errors[key] = 'INVALID_VALUE';
  }
  const zone = values.storeTimeZone;
  if (!errors.storeTimeZone && zone !== '' && Array.isArray(zones) && !zones.includes(zone)) {
    errors.storeTimeZone = 'INVALID_VALUE';
  }
  return errors;
}

export function validateCheckout(values) {
  const errors = {};
  checkKeys(SECTION_KEYS.checkout, values, errors);
  return errors;
}

/** Invoice series rule of 12 §10: pattern, not TEST, different from the other series. */
export function seriesError(series, other) {
  const code = validateValue('invoiceSeries', series);
  if (code) return code;
  if (typeof other === 'string' && series.trim() === other.trim()) return 'SAME_AS_OTHER_SERIES';
  return null;
}

export function validateBilling(values) {
  const errors = {};
  checkKeys(['billingInfoMode', 'invoiceEnabled'], values, errors);
  if (!values.invoiceEnabled) return errors;
  checkKeys(
    [
      'invoiceCreditOrders',
      'invoiceLocale',
      'invoiceShowLogo',
      'invoiceSellerName',
      'invoiceSellerAddress',
      'invoiceSellerTaxOffice',
      'invoiceSellerTaxNumber',
      'invoiceFooter',
    ],
    values,
    errors,
  );
  const series = seriesError(values.invoiceSeries, values.invoiceCreditNoteSeries);
  const credit = seriesError(values.invoiceCreditNoteSeries, values.invoiceSeries);
  if (series && series !== 'SAME_AS_OTHER_SERIES') errors.invoiceSeries = series;
  if (credit) errors.invoiceCreditNoteSeries = credit;
  return errors;
}

/** Invoices are issued without a seller name (the site name shows); the form only notes it. */
export function sellerNameNotice(values) {
  return Boolean(values.invoiceEnabled) && String(values.invoiceSellerName ?? '').trim() === '';
}

// ---------------------------------------------------------------------------------------------
// Confirmations of the general section
// ---------------------------------------------------------------------------------------------

/**
 * Which confirmations a save needs. `ordersExist` is true / false when known, null when the order
 * count could not be read (then the currency change is confirmed to be safe).
 */
export function confirmPlan(baseline, values, { ordersExist = null } = {}) {
  const reasons = [];
  if (seedValue(baseline, 'storeEnabled') === true && values.storeEnabled === false)
    reasons.push('STORE_DISABLED');
  if (seedValue(baseline, 'currency') !== values.currency && ordersExist !== false)
    reasons.push('CURRENCY_CHANGED');
  return { needed: reasons.length > 0, danger: reasons.includes('STORE_DISABLED'), reasons };
}

/** `Intl.supportedValuesOf('timeZone')` or null when the runtime has no such function. */
export function timeZoneOptions(intl = typeof Intl !== 'undefined' ? Intl : null, stored = '') {
  let list = null;
  try {
    if (intl && typeof intl.supportedValuesOf === 'function')
      list = [...intl.supportedValuesOf('timeZone')];
  } catch {
    list = null;
  }
  if (!list) return null;
  if (stored && !list.includes(stored)) list.push(stored);
  return list;
}

// ---------------------------------------------------------------------------------------------
// Submit core (injected post, so the flow is tested without the host)
// ---------------------------------------------------------------------------------------------

/**
 * Validates, builds the partial body and posts it.
 * Returns `{ status: 'clean' }` (nothing changed), `{ status: 'invalid', errors }` (nothing sent),
 * `{ status: 'failed', error, errors }` (server refused: `errors` = its fieldErrors) or
 * `{ status: 'saved', body }`.
 * `post(body)` resolves to the `call()` result of utils/api.js.
 */
export async function submitSettings({ post, baseline, values, keys, errors = {} }) {
  if (Object.keys(errors).length > 0) return { status: 'invalid', errors };
  const body = buildSettingsBody(baseline, values, keys);
  if (!isDirtyBody(body)) return { status: 'clean' };
  const result = await post(body);
  if (!result.ok) {
    return {
      status: 'failed',
      error: result.error,
      errors:
        result.error === 'INVALID_SETTINGS' ? normalizeFieldErrors(result.body?.fieldErrors) : {},
    };
  }
  return { status: 'saved', body: result.body, sent: body };
}

/** Focuses the input of the first key in `order` that has an error (client only). */
export function focusFirstInvalid(errors, order, prefix = 'setting-') {
  if (typeof document === 'undefined') return;
  const key = order.find((k) => errors[k]) ?? Object.keys(errors)[0];
  if (key) document.getElementById(prefix + key)?.focus();
}

// ---------------------------------------------------------------------------------------------
// Currencies
// ---------------------------------------------------------------------------------------------

/** Currencies the admin may still add: `ctx.currencies` minus the base currency and the chosen ones. */
export function additionalChoices(ctx, base, chosen) {
  return currencyChoices(ctx).filter((code) => code !== base && !chosen.includes(code));
}

/**
 * Rate text -> Number. `null` for empty, NaN for anything that is not a positive decimal with at
 * most 10 decimals (one `.` or `,` as separator, no thousands separators, no exponent).
 */
export function parseRate(text) {
  const trimmed = String(text ?? '').trim();
  if (trimmed === '') return null;
  if (!/^\d+([.,]\d+)?$/.test(trimmed)) return NaN;
  const [, decimals = ''] = trimmed.split(/[.,]/);
  if (decimals.length > 10) return NaN;
  const value = Number(trimmed.replace(',', '.'));
  return value > 0 ? value : NaN;
}

/** Error code of a rate row (only MANUAL rows carry a rate). */
export function rateError(row) {
  if (row.mode !== 'MANUAL') return null;
  const text = String(row.text ?? '').trim();
  if (text === '') return 'RATE_REQUIRED';
  const value = parseRate(text);
  if (Number.isNaN(value)) {
    return /^\d+([.,]\d+)?$/.test(text) && Number(text.replace(',', '.')) > 0
      ? 'TOO_MANY_DECIMALS'
      : 'INVALID_VALUE';
  }
  return null;
}

/** Table rows of `additional`, filled from the loaded rates (`[{currency, mode, rate, fetchedAt}]`). */
export function rateRowsFrom(additional, rates) {
  const byCode = new Map((rates ?? []).map((r) => [r.currency, r]));
  return additional.map((currency) => {
    const known = byCode.get(currency);
    return {
      currency,
      mode: known?.mode === 'MANUAL' ? 'MANUAL' : 'AUTO',
      rate: known?.rate ?? null,
      text: known?.mode === 'MANUAL' && known.rate != null ? String(known.rate) : '',
      fetchedAt: known?.fetchedAt ?? null,
    };
  });
}

/** Keeps the rows of the currencies still chosen, adds an empty AUTO row for a new one. */
export function syncRateRows(rows, additional, loaded = []) {
  const byCode = new Map(rows.map((r) => [r.currency, r]));
  const loadedRows = rateRowsFrom(additional, loaded);
  return additional.map((code, index) => byCode.get(code) ?? loadedRows[index]);
}

export function buildRatesPayload(rows) {
  return {
    rates: rows.map((row) =>
      row.mode === 'MANUAL'
        ? { currency: row.currency, mode: 'MANUAL', rate: parseRate(row.text) }
        : { currency: row.currency, mode: 'AUTO' },
    ),
  };
}

/** True when the table differs from the loaded one (mode, or the rate of a MANUAL row). */
export function ratesDirty(rows, loadedRows) {
  if (rows.length !== loadedRows.length) return true;
  return rows.some((row, index) => {
    const base = loadedRows[index];
    if (row.currency !== base.currency || row.mode !== base.mode) return true;
    return row.mode === 'MANUAL' && parseRate(row.text) !== parseRate(base.text);
  });
}

export function usesRates(mode) {
  return mode === 'DISPLAY' || mode === 'MULTI';
}

/** Leaving MULTI keeps the per-currency product prices but stops using them (confirmed). */
export function leavesMulti(baselineMode, mode) {
  return baselineMode === 'MULTI' && mode !== 'MULTI';
}

/** `{ errors: { <key>: CODE }, rates: { <currency>: CODE } }`; empty objects = valid. */
export function validateCurrencies(values, rows, baseCurrency) {
  const errors = {};
  const rates = {};
  checkKeys(['currencyMode'], values, errors);
  if (usesRates(values.currencyMode)) {
    const chosen = values.additionalCurrencies ?? [];
    if (chosen.length === 0) errors.additionalCurrencies = 'TOO_FEW';
    else if (chosen.includes(baseCurrency)) errors.additionalCurrencies = 'CONTAINS_BASE_CURRENCY';
    else if (new Set(chosen).size !== chosen.length) errors.additionalCurrencies = 'INVALID_VALUE';
    for (const row of rows) {
      const code = rateError(row);
      if (code) rates[row.currency] = code;
    }
  }
  if (values.currencyMode === 'MULTI') checkKeys(['multiCurrencyFallback'], values, errors);
  return { errors, rates };
}

/**
 * Save algorithm of 13 §17 currencies: 1. POST /settings, 2. PUT /settings/currencies (skipped for
 * SINGLE). Sequential, stops at the first failure and reports where.
 * `post(body)` / `put(body)` resolve to `call()` results; `skipSettings` when nothing changed.
 */
export async function saveCurrencies({ post, put, baseline, values, rows, loadedRows }) {
  const keys = SECTION_KEYS.currencies.filter((key) => {
    if (values.currencyMode === 'SINGLE') return key === 'currencyMode';
    if (values.currencyMode === 'DISPLAY') return key !== 'multiCurrencyFallback';
    return true;
  });
  const body = buildSettingsBody(baseline, values, keys);
  if (isDirtyBody(body)) {
    const first = await post(body);
    if (!first.ok) {
      return {
        status: 'failed',
        step: 'settings',
        error: first.error,
        errors:
          first.error === 'INVALID_SETTINGS' ? normalizeFieldErrors(first.body?.fieldErrors) : {},
      };
    }
  }
  if (usesRates(values.currencyMode) && (ratesDirty(rows, loadedRows) || isDirtyBody(body))) {
    const second = await put(buildRatesPayload(rows));
    if (!second.ok) return { status: 'failed', step: 'rates', error: second.error, errors: {} };
    return { status: 'saved', rates: second.body };
  }
  return isDirtyBody(body) ? { status: 'saved', rates: null } : { status: 'clean' };
}

// ---------------------------------------------------------------------------------------------
// Billing: invoice number sequences
// ---------------------------------------------------------------------------------------------

/** Next-number edit: a positive integer, strictly above the current one (only upwards). */
export function sequenceError(text, current) {
  const value = parseInteger(text, { min: 1, max: 2_000_000_000 });
  if (value === null) return 'REQUIRED';
  if (Number.isNaN(value)) return 'INVALID_VALUE';
  if (value <= Number(current)) return 'NOT_UPWARDS';
  return null;
}

// ---------------------------------------------------------------------------------------------
// Legal text
// ---------------------------------------------------------------------------------------------

export const LEGAL_TITLE_MAX = 255;

/** Text of an editor value without its tags; `&nbsp;` counts as blank. */
export function stripTags(html) {
  return String(html ?? '')
    .replace(/<[^>]*>/g, '')
    .replace(/&nbsp;|&#160;/gi, ' ')
    .trim();
}

export function isContentEmpty(html) {
  return stripTags(html) === '';
}

/** Versions newest first (higher version, then locale). */
export function sortVersions(texts) {
  return [...(texts ?? [])].sort(
    (a, b) => b.version - a.version || String(a.locale).localeCompare(b.locale),
  );
}

export function activeTextFor(texts, locale) {
  return (texts ?? []).find((t) => t.active && t.locale === locale) ?? null;
}

export function hasActiveText(texts) {
  return (texts ?? []).some((t) => t.active);
}

/** Turning legalTextRequired on needs an active text in at least one locale. */
export function legalRequiredBlocked(wanted, texts) {
  return Boolean(wanted) && !hasActiveText(texts);
}

/** Draft of the editor: `{ locale, title, content }`. */
export function validateLegalDraft(draft) {
  const errors = {};
  if (!String(draft.locale ?? '').trim()) errors.locale = 'REQUIRED';
  const title = String(draft.title ?? '').trim();
  if (title === '') errors.title = 'REQUIRED';
  else if (title.length > LEGAL_TITLE_MAX) errors.title = 'TOO_LONG';
  if (isContentEmpty(draft.content)) errors.content = 'REQUIRED';
  return errors;
}

export function buildLegalBody(draft) {
  return { locale: draft.locale, title: String(draft.title).trim(), content: draft.content };
}

/** Editor differs from what it was pre-filled with (title, or the content seen after the editor settled). */
export function legalDraftDirty(draft, baseline) {
  return (
    String(draft.title ?? '') !== String(baseline.title ?? '') ||
    String(draft.content ?? '') !== String(baseline.content ?? '')
  );
}

/** Locales of the panel language list (`{ code: { code, name } }`) as `[{ code, name }]`. */
export function languageOptions(languages) {
  return Object.values(languages ?? {})
    .filter((l) => l && l.code)
    .map((l) => ({ code: l.code, name: l.name ?? l.code }));
}

// ---------------------------------------------------------------------------------------------
// Settings page: which section opens, what it loads on top of GET /settings
// ---------------------------------------------------------------------------------------------

/** Section keys of 13 §17 in nav order (the same list navigation.js declares). */
export const PAGE_SECTIONS = [
  'general',
  'checkout',
  'currencies',
  'billing',
  'legal',
  'payments',
  'credits',
  'delivery',
  'shipping-methods',
  'shipping-zones',
  'shipping-carriers',
  'webhooks',
  'webhook-deliveries',
  'modules',
  'security',
  'mail',
  'minecraft',
  'health',
];

/** `?section=` value -> a known section, `general` for anything else. */
export function resolveSection(value) {
  return PAGE_SECTIONS.includes(value) ? value : 'general';
}

/** Extra GET a section needs next to GET /settings (13 §17 table); null = none. */
export function extraPathFor(section) {
  if (section === 'currencies') return '/settings/currencies';
  if (section === 'legal') return '/settings/legal';
  return null;
}
