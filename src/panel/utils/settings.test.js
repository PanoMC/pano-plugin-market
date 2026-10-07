import { describe, expect, test } from 'bun:test';
import {
  BILLING_INFO_MODES,
  CURRENCY_MODES,
  MULTI_FALLBACKS,
  RATE_MODES,
  FIELDS,
  FIELD_ERROR_CODES,
  OWNED_KEYS,
  SECTION_KEYS,
  activeKeys,
  activeTextFor,
  additionalChoices,
  buildLegalBody,
  buildRatesPayload,
  buildSettingsBody,
  confirmPlan,
  currencyChoices,
  currencyExponent,
  currencySymbol,
  fieldErrorKey,
  hasActiveText,
  isContentEmpty,
  languageOptions,
  leavesMulti,
  legalDraftDirty,
  legalRequiredBlocked,
  normalizeFieldErrors,
  PAGE_SECTIONS,
  extraPathFor,
  parseRate,
  resolveSection,
  rateError,
  rateRowsFrom,
  ratesDirty,
  saveCurrencies,
  sameValue,
  sellerNameNotice,
  seedValues,
  sequenceError,
  seriesError,
  sortVersions,
  stripTags,
  submitSettings,
  syncRateRows,
  timeZoneOptions,
  validateBilling,
  validateCheckout,
  validateCurrencies,
  validateGeneral,
  validateLegalDraft,
  validateValue,
} from './settings.js';
import en from '../../locales/panel/en-US.json';
import tr from '../../locales/panel/tr.json';
import ru from '../../locales/panel/ru.json';

const ok = (body = {}) => ({ ok: true, body });
const fail = (error, body = {}) => ({ ok: false, error, body });

const ctx = {
  currency: 'USD',
  currencies: [
    { code: 'USD', symbol: '$', exponent: 2 },
    { code: 'EUR', symbol: '€', exponent: 2 },
    { code: 'JPY', symbol: '¥', exponent: 0 },
    { code: 'TRY', symbol: '₺', exponent: 2 },
  ],
};

// The keys of 00 §12 that belong to general / checkout / currencies / billing / legal.
const SPEC_KEYS = [
  'storeEnabled',
  'storeTimeZone',
  'storePageSize',
  'storeName',
  'storeDescription',
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
  'allowGuestCheckout',
  'allowGiftPurchase',
  'minimumOrderAmount',
  'combineDiscountsAndCoupons',
  'orderExpiryMinutes',
  'bankTransferExpiryHours',
  'autoRefundDuplicatePayments',
  'subscriptionManualFallback',
  'currencyMode',
  'additionalCurrencies',
  'multiCurrencyFallback',
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
  'legalTextRequired',
];

describe('field table', () => {
  test('every key of 00 §12 that belongs to these sections has a control (a table entry owned by a section)', () => {
    for (const key of SPEC_KEYS) expect(OWNED_KEYS).toContain(key);
    expect(new Set(OWNED_KEYS).size).toBe(OWNED_KEYS.length);
    for (const key of OWNED_KEYS) expect(FIELDS[key]).toBeDefined();
  });

  test('each default passes its own validation', () => {
    for (const key of OWNED_KEYS) expect(validateValue(key, FIELDS[key].def)).toBeNull();
  });

  test('documented defaults of 00 §12', () => {
    expect(FIELDS.storeEnabled.def).toBe(true);
    expect(FIELDS.storePageSize.def).toBe(24);
    expect(FIELDS.orderExpiryMinutes.def).toBe(60);
    expect(FIELDS.bankTransferExpiryHours.def).toBe(72);
    expect(FIELDS.billingInfoMode.def).toBe('OPTIONAL');
    expect(FIELDS.invoiceSeries.def).toBe('INV');
    expect(FIELDS.invoiceCreditNoteSeries.def).toBe('CN');
    expect(FIELDS.legalTextRequired.def).toBe(false);
    expect(FIELDS.currencyMode.def).toBe('SINGLE');
  });
});

describe('validateValue', () => {
  test('integers: range, fraction and empty', () => {
    expect(validateValue('orderExpiryMinutes', 4)).toBe('OUT_OF_RANGE');
    expect(validateValue('orderExpiryMinutes', 5)).toBeNull();
    expect(validateValue('orderExpiryMinutes', 10_080)).toBeNull();
    expect(validateValue('orderExpiryMinutes', 10_081)).toBe('OUT_OF_RANGE');
    expect(validateValue('orderExpiryMinutes', 60.5)).toBe('INVALID_TYPE');
    expect(validateValue('orderExpiryMinutes', null)).toBe('REQUIRED');
    expect(validateValue('orderExpiryMinutes', '')).toBe('REQUIRED');
    expect(validateValue('orderExpiryMinutes', NaN)).toBe('INVALID_TYPE');
    expect(validateValue('bankTransferExpiryHours', 0)).toBe('OUT_OF_RANGE');
    expect(validateValue('bankTransferExpiryHours', 720)).toBeNull();
    expect(validateValue('bankTransferExpiryHours', 721)).toBe('OUT_OF_RANGE');
  });

  test('store page size is 1 to 60', () => {
    expect(validateValue('storePageSize', 0)).toBe('OUT_OF_RANGE');
    expect(validateValue('storePageSize', 1)).toBeNull();
    expect(validateValue('storePageSize', 60)).toBeNull();
    expect(validateValue('storePageSize', 61)).toBe('OUT_OF_RANGE');
  });

  test('vat percent 0 to 100, exchange rate above 0', () => {
    expect(validateValue('vatPercent', -0.1)).toBe('OUT_OF_RANGE');
    expect(validateValue('vatPercent', 0)).toBeNull();
    expect(validateValue('vatPercent', 100)).toBeNull();
    expect(validateValue('vatPercent', 100.5)).toBe('OUT_OF_RANGE');
    expect(validateValue('vatPercent', null)).toBe('REQUIRED');
    expect(validateValue('exchangeRate', 0)).toBe('OUT_OF_RANGE');
    expect(validateValue('exchangeRate', 0.0001)).toBeNull();
  });

  test('minimum order amount: empty means 0, negative is refused', () => {
    expect(validateValue('minimumOrderAmount', null)).toBeNull();
    expect(validateValue('minimumOrderAmount', 0)).toBeNull();
    expect(validateValue('minimumOrderAmount', -1)).toBe('OUT_OF_RANGE');
    expect(validateValue('minimumOrderAmount', NaN)).toBe('INVALID_TYPE');
  });

  test('strings: length, currency code, locale pattern', () => {
    expect(validateValue('storeName', 'x'.repeat(64))).toBeNull();
    expect(validateValue('storeName', 'x'.repeat(65))).toBe('TOO_LONG');
    expect(validateValue('currency', 'usd')).toBe('INVALID_VALUE');
    expect(validateValue('invoiceLocale', '')).toBeNull();
    expect(validateValue('invoiceLocale', 'en-US')).toBeNull();
    expect(validateValue('invoiceLocale', 'English')).toBe('INVALID_VALUE');
    expect(validateValue('invoiceSellerName', 'x'.repeat(121))).toBe('TOO_LONG');
    expect(validateValue('invoiceSellerAddress', 'x'.repeat(501))).toBe('TOO_LONG');
    expect(validateValue('invoiceFooter', 'x'.repeat(1001))).toBe('TOO_LONG');
    expect(validateValue('invoiceSellerTaxNumber', 'x'.repeat(65))).toBe('TOO_LONG');
  });

  test('enums and booleans', () => {
    expect(validateValue('billingInfoMode', 'OFF')).toBeNull();
    expect(validateValue('billingInfoMode', 'ALWAYS')).toBe('INVALID_VALUE');
    expect(validateValue('storeEnabled', 'true')).toBe('INVALID_TYPE');
  });
});

describe('seeding, comparison and the partial body', () => {
  test('a key the server did not send takes the documented default', () => {
    const values = seedValues({ storeName: 'Shop' }, [
      'storeName',
      'storePageSize',
      'additionalCurrencies',
    ]);
    expect(values).toEqual({ storeName: 'Shop', storePageSize: 24, additionalCurrencies: [] });
  });

  test('list seeds are copies', () => {
    const settings = { additionalCurrencies: ['EUR'] };
    const values = seedValues(settings, ['additionalCurrencies']);
    values.additionalCurrencies.push('JPY');
    expect(settings.additionalCurrencies).toEqual(['EUR']);
  });

  test('the body holds only the changed keys, numbers as Number', () => {
    const baseline = { storeName: 'A', storePageSize: 24, vatPercent: 20, storeEnabled: true };
    const values = seedValues(baseline, SECTION_KEYS.general);
    expect(buildSettingsBody(baseline, values, SECTION_KEYS.general)).toEqual({});
    values.storePageSize = '30';
    values.storeName = 'B';
    expect(buildSettingsBody(baseline, values, SECTION_KEYS.general)).toEqual({
      storeName: 'B',
      storePageSize: 30,
    });
  });

  test('sameValue treats 20 and "20" as equal and compares lists by content', () => {
    expect(sameValue('vatPercent', 20, '20')).toBe(true);
    expect(sameValue('additionalCurrencies', ['EUR', 'JPY'], ['EUR', 'JPY'])).toBe(true);
    expect(sameValue('additionalCurrencies', ['EUR'], ['EUR', 'JPY'])).toBe(false);
    expect(sameValue('minimumOrderAmount', null, 0)).toBe(true);
  });

  test('series are trimmed in the body', () => {
    const baseline = { invoiceSeries: 'INV' };
    const body = buildSettingsBody(baseline, { invoiceSeries: ' ABC ' }, ['invoiceSeries']);
    expect(body).toEqual({ invoiceSeries: 'ABC' });
  });

  test('the manual rate only counts in MANUAL mode with differing currencies', () => {
    const same = activeKeys('general', {
      currency: 'USD',
      statsCurrency: 'USD',
      exchangeRateMode: 'MANUAL',
    });
    expect(same).not.toContain('exchangeRate');
    expect(same).not.toContain('exchangeRateAutoIntervalHours');
    const auto = activeKeys('general', {
      currency: 'USD',
      statsCurrency: 'EUR',
      exchangeRateMode: 'AUTO',
    });
    expect(auto).not.toContain('exchangeRate');
    expect(auto).toContain('exchangeRateAutoIntervalHours');
    const manual = activeKeys('general', {
      currency: 'USD',
      statsCurrency: 'EUR',
      exchangeRateMode: 'MANUAL',
    });
    expect(manual).toContain('exchangeRate');
    expect(manual).not.toContain('exchangeRateAutoIntervalHours');
    expect(activeKeys('checkout', {})).toEqual(SECTION_KEYS.checkout);
    expect(activeKeys('billing', { invoiceEnabled: true })).toEqual(SECTION_KEYS.billing);
    expect(activeKeys('billing', { invoiceEnabled: false })).toEqual([
      'billingInfoMode',
      'invoiceEnabled',
    ]);
  });
});

describe('general validation', () => {
  const valid = () => seedValues({ currency: 'USD', statsCurrency: 'USD' }, SECTION_KEYS.general);

  test('defaults are valid', () => {
    expect(validateGeneral(valid(), { ctx })).toEqual({});
  });

  test('vat, page size and a currency the server does not list', () => {
    const values = { ...valid(), vatPercent: 101, storePageSize: 61, currency: 'XXX' };
    expect(validateGeneral(values, { ctx })).toEqual({
      vatPercent: 'OUT_OF_RANGE',
      storePageSize: 'OUT_OF_RANGE',
      currency: 'INVALID_VALUE',
    });
  });

  test('exchange rate must be above 0 in MANUAL mode and is ignored otherwise', () => {
    const base = { ...valid(), statsCurrency: 'EUR', exchangeRateMode: 'MANUAL', exchangeRate: 0 };
    expect(validateGeneral(base, { ctx }).exchangeRate).toBe('OUT_OF_RANGE');
    expect(
      validateGeneral({ ...base, exchangeRateMode: 'AUTO' }, { ctx }).exchangeRate,
    ).toBeUndefined();
  });

  test('time zone is checked only when the runtime lists them', () => {
    const values = { ...valid(), storeTimeZone: 'Mars/Base' };
    expect(validateGeneral(values, { ctx, zones: ['Europe/Istanbul'] }).storeTimeZone).toBe(
      'INVALID_VALUE',
    );
    expect(validateGeneral(values, { ctx, zones: null }).storeTimeZone).toBeUndefined();
    expect(
      validateGeneral({ ...values, storeTimeZone: '' }, { ctx, zones: ['Europe/Istanbul'] }),
    ).toEqual({});
  });

  test('without a context any three letter code passes', () => {
    expect(validateGeneral({ ...valid(), currency: 'CHF' }, { ctx: null })).toEqual({});
  });
});

describe('checkout validation', () => {
  const valid = () => seedValues({}, SECTION_KEYS.checkout);
  test('defaults valid', () => expect(validateCheckout(valid())).toEqual({}));
  test('order expiry 5 to 10080 and bank transfer 1 to 720', () => {
    expect(
      validateCheckout({ ...valid(), orderExpiryMinutes: 4, bankTransferExpiryHours: 721 }),
    ).toEqual({
      orderExpiryMinutes: 'OUT_OF_RANGE',
      bankTransferExpiryHours: 'OUT_OF_RANGE',
    });
  });
  test('minimum order amount below 0', () => {
    expect(validateCheckout({ ...valid(), minimumOrderAmount: -5 })).toEqual({
      minimumOrderAmount: 'OUT_OF_RANGE',
    });
  });
});

describe('invoice series rule of 12 §10', () => {
  test('pattern, TEST and the other series', () => {
    expect(seriesError('INV', 'CN')).toBeNull();
    expect(seriesError('inv', 'CN')).toBe('INVALID_VALUE');
    expect(seriesError('TOOLONGSER', 'CN')).toBe('TOO_LONG');
    expect(seriesError('A-B', 'CN')).toBe('INVALID_VALUE');
    expect(seriesError('', 'CN')).toBe('INVALID_VALUE');
    expect(seriesError('TEST', 'CN')).toBe('INVALID_VALUE');
    expect(seriesError('CN', 'CN')).toBe('SAME_AS_OTHER_SERIES');
    expect(seriesError('A1B2C3D4', 'CN')).toBeNull();
  });

  const valid = () => ({
    ...seedValues({}, SECTION_KEYS.billing),
    invoiceEnabled: true,
  });

  test('defaults valid, equal series flag the credit note field', () => {
    expect(validateBilling(valid())).toEqual({});
    expect(validateBilling({ ...valid(), invoiceCreditNoteSeries: 'INV' })).toEqual({
      invoiceCreditNoteSeries: 'SAME_AS_OTHER_SERIES',
    });
  });

  test('a bad series is flagged on its own field', () => {
    expect(validateBilling({ ...valid(), invoiceSeries: 'bad!' }).invoiceSeries).toBe(
      'INVALID_VALUE',
    );
    expect(
      validateBilling({ ...valid(), invoiceCreditNoteSeries: 'TEST' }).invoiceCreditNoteSeries,
    ).toBe('INVALID_VALUE');
  });

  test('while invoices are off only the mode is validated', () => {
    expect(validateBilling({ ...valid(), invoiceEnabled: false, invoiceSeries: 'bad!' })).toEqual(
      {},
    );
    expect(validateBilling({ ...valid(), invoiceEnabled: false, billingInfoMode: 'X' })).toEqual({
      billingInfoMode: 'INVALID_VALUE',
    });
  });

  test('seller name notice', () => {
    expect(sellerNameNotice({ invoiceEnabled: true, invoiceSellerName: '  ' })).toBe(true);
    expect(sellerNameNotice({ invoiceEnabled: true, invoiceSellerName: 'Shop' })).toBe(false);
    expect(sellerNameNotice({ invoiceEnabled: false, invoiceSellerName: '' })).toBe(false);
  });

  test('sequence edit only goes upwards', () => {
    expect(sequenceError('', 5)).toBe('REQUIRED');
    expect(sequenceError('abc', 5)).toBe('INVALID_VALUE');
    expect(sequenceError('1.5', 5)).toBe('INVALID_VALUE');
    expect(sequenceError('5', 5)).toBe('NOT_UPWARDS');
    expect(sequenceError('4', 5)).toBe('NOT_UPWARDS');
    expect(sequenceError('6', 5)).toBeNull();
    expect(sequenceError('0', 0)).toBe('INVALID_VALUE');
  });
});

describe('confirmations of the general section', () => {
  const baseline = { storeEnabled: true, currency: 'USD' };
  test('no change needs none', () => {
    expect(
      confirmPlan(baseline, { storeEnabled: true, currency: 'USD' }, { ordersExist: true }),
    ).toEqual({
      needed: false,
      danger: false,
      reasons: [],
    });
  });
  test('turning the store off is a danger confirmation, turning it on is not', () => {
    const off = confirmPlan(baseline, { storeEnabled: false, currency: 'USD' });
    expect(off).toEqual({ needed: true, danger: true, reasons: ['STORE_DISABLED'] });
    expect(
      confirmPlan({ storeEnabled: false, currency: 'USD' }, { storeEnabled: true, currency: 'USD' })
        .needed,
    ).toBe(false);
  });
  test('a currency change is confirmed while orders exist or are unknown, not when there are none', () => {
    const values = { storeEnabled: true, currency: 'EUR' };
    expect(confirmPlan(baseline, values, { ordersExist: true }).reasons).toEqual([
      'CURRENCY_CHANGED',
    ]);
    expect(confirmPlan(baseline, values, { ordersExist: null }).reasons).toEqual([
      'CURRENCY_CHANGED',
    ]);
    expect(confirmPlan(baseline, values, { ordersExist: false }).needed).toBe(false);
  });
  test('both reasons come together and the danger variant wins', () => {
    const plan = confirmPlan(
      baseline,
      { storeEnabled: false, currency: 'EUR' },
      { ordersExist: true },
    );
    expect(plan.reasons).toEqual(['STORE_DISABLED', 'CURRENCY_CHANGED']);
    expect(plan.danger).toBe(true);
  });
  test('a baseline without storeEnabled counts as enabled (default)', () => {
    expect(
      confirmPlan({ currency: 'USD' }, { storeEnabled: false, currency: 'USD' }).reasons,
    ).toEqual(['STORE_DISABLED']);
  });
});

describe('time zones and currency lists', () => {
  test('timeZoneOptions reads Intl.supportedValuesOf, keeps a stored unknown zone, null without support', () => {
    const intl = {
      supportedValuesOf: (kind) => (kind === 'timeZone' ? ['Europe/Istanbul', 'UTC'] : []),
    };
    expect(timeZoneOptions(intl)).toEqual(['Europe/Istanbul', 'UTC']);
    expect(timeZoneOptions(intl, 'Asia/Old')).toEqual(['Europe/Istanbul', 'UTC', 'Asia/Old']);
    expect(timeZoneOptions({})).toBeNull();
    expect(timeZoneOptions(null)).toBeNull();
    expect(
      timeZoneOptions({
        supportedValuesOf: () => {
          throw new RangeError('x');
        },
      }),
    ).toBeNull();
  });

  test('currency choices come from the context plus stored codes', () => {
    expect(currencyChoices(ctx)).toEqual(['USD', 'EUR', 'JPY', 'TRY']);
    expect(currencyChoices(ctx, 'CHF', 'EUR')).toEqual(['USD', 'EUR', 'JPY', 'TRY', 'CHF']);
    expect(currencyChoices(null, 'USD', 'TRY')).toEqual(['USD', 'TRY']);
    expect(currencyExponent(ctx, 'JPY')).toBe(0);
    expect(currencyExponent(ctx, 'ZZZ')).toBe(2);
    expect(currencySymbol(ctx, 'EUR')).toBe('€');
    expect(currencySymbol(null, 'EUR')).toBe('EUR');
  });

  test('additional currencies: context minus base minus chosen', () => {
    expect(additionalChoices(ctx, 'USD', ['EUR'])).toEqual(['JPY', 'TRY']);
    expect(additionalChoices(ctx, 'USD', [])).toEqual(['EUR', 'JPY', 'TRY']);
    expect(additionalChoices(null, 'USD', [])).toEqual([]);
  });
});

describe('rates', () => {
  test('parseRate', () => {
    expect(parseRate('')).toBeNull();
    expect(parseRate('  ')).toBeNull();
    expect(parseRate('1,25')).toBe(1.25);
    expect(parseRate('32.5')).toBe(32.5);
    expect(parseRate('0.0000000001')).toBe(1e-10);
    expect(parseRate('0.00000000001')).toBeNaN();
    expect(parseRate('0')).toBeNaN();
    expect(parseRate('-1')).toBeNaN();
    expect(parseRate('1e3')).toBeNaN();
    expect(parseRate('1 000')).toBeNaN();
    expect(parseRate('1,2,3')).toBeNaN();
    expect(parseRate('abc')).toBeNaN();
  });

  test('only MANUAL rows have a rate error', () => {
    expect(rateError({ mode: 'AUTO', text: 'abc' })).toBeNull();
    expect(rateError({ mode: 'MANUAL', text: '' })).toBe('RATE_REQUIRED');
    expect(rateError({ mode: 'MANUAL', text: '0' })).toBe('INVALID_VALUE');
    expect(rateError({ mode: 'MANUAL', text: 'x' })).toBe('INVALID_VALUE');
    expect(rateError({ mode: 'MANUAL', text: '0.00000000001' })).toBe('TOO_MANY_DECIMALS');
    expect(rateError({ mode: 'MANUAL', text: '30,5' })).toBeNull();
  });

  const loaded = [
    { currency: 'EUR', rate: 0.92, mode: 'AUTO', fetchedAt: 1000 },
    { currency: 'JPY', rate: 150, mode: 'MANUAL', fetchedAt: 2000 },
  ];

  test('rows follow the chosen list and fill from the loaded rates', () => {
    const rows = rateRowsFrom(['JPY', 'EUR', 'TRY'], loaded);
    expect(rows.map((r) => [r.currency, r.mode, r.text])).toEqual([
      ['JPY', 'MANUAL', '150'],
      ['EUR', 'AUTO', ''],
      ['TRY', 'AUTO', ''],
    ]);
    expect(rows[1].rate).toBe(0.92);
    expect(rows[2].rate).toBeNull();
    expect(rows[2].fetchedAt).toBeNull();
  });

  test('syncRateRows keeps edited rows, drops removed currencies and adds new ones', () => {
    const rows = rateRowsFrom(['EUR', 'JPY'], loaded);
    rows[0].mode = 'MANUAL';
    rows[0].text = '0.9';
    const next = syncRateRows(rows, ['EUR', 'TRY'], loaded);
    expect(next.map((r) => r.currency)).toEqual(['EUR', 'TRY']);
    expect(next[0].text).toBe('0.9');
    expect(next[1].mode).toBe('AUTO');
  });

  test('the payload sends a rate only for MANUAL rows', () => {
    const rows = rateRowsFrom(['EUR', 'JPY'], loaded);
    expect(buildRatesPayload(rows)).toEqual({
      rates: [
        { currency: 'EUR', mode: 'AUTO' },
        { currency: 'JPY', mode: 'MANUAL', rate: 150 },
      ],
    });
  });

  test('ratesDirty sees a mode switch, a changed manual rate and a changed list', () => {
    const base = rateRowsFrom(['EUR', 'JPY'], loaded);
    expect(ratesDirty(rateRowsFrom(['EUR', 'JPY'], loaded), base)).toBe(false);
    const modeSwitch = rateRowsFrom(['EUR', 'JPY'], loaded);
    modeSwitch[0].mode = 'MANUAL';
    expect(ratesDirty(modeSwitch, base)).toBe(true);
    const rate = rateRowsFrom(['EUR', 'JPY'], loaded);
    rate[1].text = '151';
    expect(ratesDirty(rate, base)).toBe(true);
    const typingAuto = rateRowsFrom(['EUR', 'JPY'], loaded);
    typingAuto[0].text = '5';
    expect(ratesDirty(typingAuto, base)).toBe(false);
    expect(ratesDirty(rateRowsFrom(['EUR'], loaded), base)).toBe(true);
  });
});

describe('currency section validation', () => {
  const rows = rateRowsFrom(['EUR'], []);
  test('SINGLE needs nothing else', () => {
    expect(
      validateCurrencies(
        { currencyMode: 'SINGLE', additionalCurrencies: [], multiCurrencyFallback: 'CONVERT' },
        [],
        'USD',
      ),
    ).toEqual({
      errors: {},
      rates: {},
    });
  });
  test('DISPLAY and MULTI need at least one additional currency', () => {
    for (const currencyMode of ['DISPLAY', 'MULTI']) {
      const result = validateCurrencies(
        { currencyMode, additionalCurrencies: [], multiCurrencyFallback: 'CONVERT' },
        [],
        'USD',
      );
      expect(result.errors.additionalCurrencies).toBe('TOO_FEW');
    }
  });
  test('the base currency cannot be additional', () => {
    const result = validateCurrencies(
      { currencyMode: 'DISPLAY', additionalCurrencies: ['USD'], multiCurrencyFallback: 'CONVERT' },
      [],
      'USD',
    );
    expect(result.errors.additionalCurrencies).toBe('CONTAINS_BASE_CURRENCY');
  });
  test('a MANUAL row without a usable rate is flagged by currency', () => {
    const manual = [{ currency: 'EUR', mode: 'MANUAL', text: '' }];
    const result = validateCurrencies(
      { currencyMode: 'MULTI', additionalCurrencies: ['EUR'], multiCurrencyFallback: 'HIDE' },
      manual,
      'USD',
    );
    expect(result.rates).toEqual({ EUR: 'RATE_REQUIRED' });
  });
  test('rates are not checked for SINGLE, the fallback only for MULTI', () => {
    const manual = [{ currency: 'EUR', mode: 'MANUAL', text: '' }];
    expect(
      validateCurrencies(
        { currencyMode: 'SINGLE', additionalCurrencies: ['EUR'], multiCurrencyFallback: 'X' },
        manual,
        'USD',
      ),
    ).toEqual({
      errors: {},
      rates: {},
    });
    expect(
      validateCurrencies(
        { currencyMode: 'MULTI', additionalCurrencies: ['EUR'], multiCurrencyFallback: 'X' },
        rows,
        'USD',
      ).errors.multiCurrencyFallback,
    ).toBe('INVALID_VALUE');
    expect(
      validateCurrencies(
        { currencyMode: 'DISPLAY', additionalCurrencies: ['EUR'], multiCurrencyFallback: 'X' },
        rows,
        'USD',
      ).errors,
    ).toEqual({});
  });
  test('leaving MULTI is the only mode change that needs a confirmation', () => {
    expect(leavesMulti('MULTI', 'SINGLE')).toBe(true);
    expect(leavesMulti('MULTI', 'DISPLAY')).toBe(true);
    expect(leavesMulti('MULTI', 'MULTI')).toBe(false);
    expect(leavesMulti('DISPLAY', 'SINGLE')).toBe(false);
    expect(leavesMulti('SINGLE', 'MULTI')).toBe(false);
  });
});

describe('saveCurrencies runs sequentially and stops at the first failure', () => {
  const baseline = {
    currencyMode: 'SINGLE',
    additionalCurrencies: [],
    multiCurrencyFallback: 'CONVERT',
  };
  const loadedRows = [];
  const makeCalls = (results) => {
    const calls = [];
    return {
      calls,
      post: async (body) => {
        calls.push(['POST', body]);
        return results.post ?? ok();
      },
      put: async (body) => {
        calls.push(['PUT', body]);
        return results.put ?? ok({ rates: [] });
      },
    };
  };

  test('nothing changed sends nothing', async () => {
    const { calls, post, put } = makeCalls({});
    const values = seedValues(baseline, SECTION_KEYS.currencies);
    expect(await saveCurrencies({ post, put, baseline, values, rows: [], loadedRows })).toEqual({
      status: 'clean',
    });
    expect(calls).toEqual([]);
  });

  test('SINGLE saves the mode only and skips the rates', async () => {
    const { calls, post, put } = makeCalls({});
    const base = { ...baseline, currencyMode: 'MULTI', additionalCurrencies: ['EUR'] };
    const values = { ...seedValues(base, SECTION_KEYS.currencies), currencyMode: 'SINGLE' };
    const rows = rateRowsFrom(['EUR'], []);
    const result = await saveCurrencies({
      post,
      put,
      baseline: base,
      values,
      rows,
      loadedRows: rows,
    });
    expect(result.status).toBe('saved');
    expect(calls).toEqual([['POST', { currencyMode: 'SINGLE' }]]);
  });

  test('DISPLAY posts mode and list, then puts the rates (no fallback key)', async () => {
    const { calls, post, put } = makeCalls({});
    const values = {
      ...seedValues(baseline, SECTION_KEYS.currencies),
      currencyMode: 'DISPLAY',
      additionalCurrencies: ['EUR'],
      multiCurrencyFallback: 'HIDE',
    };
    const rows = rateRowsFrom(['EUR'], []);
    const result = await saveCurrencies({ post, put, baseline, values, rows, loadedRows: [] });
    expect(result.status).toBe('saved');
    expect(calls).toEqual([
      ['POST', { currencyMode: 'DISPLAY', additionalCurrencies: ['EUR'] }],
      ['PUT', { rates: [{ currency: 'EUR', mode: 'AUTO' }] }],
    ]);
  });

  test('MULTI includes the fallback', async () => {
    const { calls, post, put } = makeCalls({});
    const values = {
      ...seedValues(baseline, SECTION_KEYS.currencies),
      currencyMode: 'MULTI',
      additionalCurrencies: ['EUR'],
      multiCurrencyFallback: 'HIDE',
    };
    await saveCurrencies({
      post,
      put,
      baseline,
      values,
      rows: rateRowsFrom(['EUR'], []),
      loadedRows: [],
    });
    expect(calls[0]).toEqual([
      'POST',
      { currencyMode: 'MULTI', additionalCurrencies: ['EUR'], multiCurrencyFallback: 'HIDE' },
    ]);
  });

  test('a failed first step stops before the rates and keeps the server field errors', async () => {
    const { calls, post, put } = makeCalls({
      post: fail('INVALID_SETTINGS', {
        fieldErrors: { additionalCurrencies: 'CONTAINS_BASE_CURRENCY' },
      }),
    });
    const values = {
      ...seedValues(baseline, SECTION_KEYS.currencies),
      currencyMode: 'DISPLAY',
      additionalCurrencies: ['EUR'],
    };
    const result = await saveCurrencies({
      post,
      put,
      baseline,
      values,
      rows: rateRowsFrom(['EUR'], []),
      loadedRows: [],
    });
    expect(result).toEqual({
      status: 'failed',
      step: 'settings',
      error: 'INVALID_SETTINGS',
      errors: { additionalCurrencies: 'CONTAINS_BASE_CURRENCY' },
    });
    expect(calls.map((c) => c[0])).toEqual(['POST']);
  });

  test('a failed second step reports the rates step', async () => {
    const { calls, post, put } = makeCalls({ put: fail('INVALID_SETTINGS') });
    const values = {
      ...seedValues(baseline, SECTION_KEYS.currencies),
      currencyMode: 'DISPLAY',
      additionalCurrencies: ['EUR'],
    };
    const result = await saveCurrencies({
      post,
      put,
      baseline,
      values,
      rows: rateRowsFrom(['EUR'], []),
      loadedRows: [],
    });
    expect(result.status).toBe('failed');
    expect(result.step).toBe('rates');
    expect(calls.map((c) => c[0])).toEqual(['POST', 'PUT']);
  });

  test('a rate-only change skips the settings post', async () => {
    const { calls, post, put } = makeCalls({});
    const base = {
      currencyMode: 'DISPLAY',
      additionalCurrencies: ['EUR'],
      multiCurrencyFallback: 'CONVERT',
    };
    const values = seedValues(base, SECTION_KEYS.currencies);
    const loadedEur = rateRowsFrom(['EUR'], [{ currency: 'EUR', rate: 0.9, mode: 'AUTO' }]);
    const rows = rateRowsFrom(['EUR'], [{ currency: 'EUR', rate: 0.9, mode: 'AUTO' }]);
    rows[0].mode = 'MANUAL';
    rows[0].text = '0.95';
    const result = await saveCurrencies({
      post,
      put,
      baseline: base,
      values,
      rows,
      loadedRows: loadedEur,
    });
    expect(result.status).toBe('saved');
    expect(calls).toEqual([['PUT', { rates: [{ currency: 'EUR', mode: 'MANUAL', rate: 0.95 }] }]]);
  });
});

describe('submitSettings', () => {
  const baseline = { storeName: 'A', storePageSize: 24 };
  const keys = ['storeName', 'storePageSize'];
  const post =
    (result, calls = []) =>
    async (body) => {
      calls.push(body);
      return result;
    };

  test('clean values send nothing', async () => {
    const calls = [];
    const r = await submitSettings({
      post: post(ok(), calls),
      baseline,
      values: seedValues(baseline, keys),
      keys,
    });
    expect(r).toEqual({ status: 'clean' });
    expect(calls).toEqual([]);
  });

  test('client-side errors block the request', async () => {
    const calls = [];
    const r = await submitSettings({
      post: post(ok(), calls),
      baseline,
      values: { storeName: 'B', storePageSize: 24 },
      keys,
      errors: { storeName: 'TOO_LONG' },
    });
    expect(r).toEqual({ status: 'invalid', errors: { storeName: 'TOO_LONG' } });
    expect(calls).toEqual([]);
  });

  test('a change posts only the changed key', async () => {
    const calls = [];
    const r = await submitSettings({
      post: post(ok(), calls),
      baseline,
      values: { storeName: 'B', storePageSize: 24 },
      keys,
    });
    expect(r.status).toBe('saved');
    expect(calls).toEqual([{ storeName: 'B' }]);
  });

  test('INVALID_SETTINGS hands back the field errors, other errors none', async () => {
    const values = { storeName: 'B', storePageSize: 24 };
    const refused = await submitSettings({
      post: post(
        fail('INVALID_SETTINGS', {
          fieldErrors: { storeName: 'TOO_LONG', storePageSize: { key: 'x' } },
        }),
      ),
      baseline,
      values,
      keys,
    });
    expect(refused).toEqual({
      status: 'failed',
      error: 'INVALID_SETTINGS',
      errors: { storeName: 'TOO_LONG', storePageSize: 'INVALID_VALUE' },
    });
    const network = await submitSettings({
      post: post(fail('NETWORK_ERROR')),
      baseline,
      values,
      keys,
    });
    expect(network).toEqual({ status: 'failed', error: 'NETWORK_ERROR', errors: {} });
  });

  test('field errors: unknown codes become INVALID_VALUE and the key is always translated', () => {
    expect(normalizeFieldErrors({ a: 'OUT_OF_RANGE', b: 'UNKNOWN_KEY', c: null })).toEqual({
      a: 'OUT_OF_RANGE',
      b: 'INVALID_VALUE',
      c: 'INVALID_VALUE',
    });
    expect(normalizeFieldErrors(null)).toEqual({});
    expect(fieldErrorKey('OUT_OF_RANGE')).toBe('settings.field-error.OUT_OF_RANGE');
    expect(fieldErrorKey('???')).toBe('settings.field-error.INVALID_VALUE');
  });

  test('every field error code has a text in all three locales', () => {
    for (const locale of [en, tr, ru])
      for (const code of FIELD_ERROR_CODES)
        expect(typeof locale.settings['field-error'][code]).toBe('string');
  });
});

describe('legal text', () => {
  const texts = [
    { id: 1, version: 1, locale: 'en-US', title: 'Terms', content: '<p>v1</p>', active: false },
    { id: 2, version: 2, locale: 'en-US', title: 'Terms 2', content: '<p>v2</p>', active: true },
    { id: 3, version: 1, locale: 'tr', title: 'Sartlar', content: '<p>t1</p>', active: true },
  ];

  test('stripTags and emptiness', () => {
    expect(stripTags('<p>Hello <b>you</b></p>')).toBe('Hello you');
    expect(isContentEmpty('')).toBe(true);
    expect(isContentEmpty(null)).toBe(true);
    expect(isContentEmpty('<p></p>')).toBe(true);
    expect(isContentEmpty('<p>&nbsp;</p><p> </p>')).toBe(true);
    expect(isContentEmpty('<p>x</p>')).toBe(false);
  });

  test('versions are listed newest first', () => {
    expect(sortVersions(texts).map((t) => t.id)).toEqual([2, 1, 3]);
    expect(sortVersions(undefined)).toEqual([]);
  });

  test('the active text of a locale', () => {
    expect(activeTextFor(texts, 'en-US').id).toBe(2);
    expect(activeTextFor(texts, 'tr').id).toBe(3);
    expect(activeTextFor(texts, 'ru')).toBeNull();
    expect(activeTextFor(undefined, 'ru')).toBeNull();
  });

  test('legalTextRequired cannot be enabled without an active text', () => {
    expect(hasActiveText([])).toBe(false);
    expect(hasActiveText(texts.map((t) => ({ ...t, active: false })))).toBe(false);
    expect(hasActiveText(texts)).toBe(true);
    expect(legalRequiredBlocked(true, [])).toBe(true);
    expect(
      legalRequiredBlocked(
        true,
        texts.map((t) => ({ ...t, active: false })),
      ),
    ).toBe(true);
    expect(legalRequiredBlocked(true, undefined)).toBe(true);
    expect(legalRequiredBlocked(true, texts)).toBe(false);
    expect(legalRequiredBlocked(false, [])).toBe(false);
  });

  test('draft validation: locale, title and content', () => {
    expect(validateLegalDraft({ locale: 'en-US', title: 'T', content: '<p>x</p>' })).toEqual({});
    expect(validateLegalDraft({ locale: '', title: ' ', content: '<p></p>' })).toEqual({
      locale: 'REQUIRED',
      title: 'REQUIRED',
      content: 'REQUIRED',
    });
    expect(validateLegalDraft({ locale: 'tr', title: 'x'.repeat(256), content: 'a' }).title).toBe(
      'TOO_LONG',
    );
    expect(validateLegalDraft({ locale: 'tr', title: 'x'.repeat(255), content: 'a' })).toEqual({});
  });

  test('the publish body trims the title and keeps the HTML as written', () => {
    expect(buildLegalBody({ locale: 'tr', title: '  Sartlar ', content: '<p>x</p>' })).toEqual({
      locale: 'tr',
      title: 'Sartlar',
      content: '<p>x</p>',
    });
  });

  test('the editor is dirty when title or content differ from its baseline', () => {
    const baseline = { title: 'A', content: '<p>x</p>' };
    expect(legalDraftDirty({ title: 'A', content: '<p>x</p>' }, baseline)).toBe(false);
    expect(legalDraftDirty({ title: 'B', content: '<p>x</p>' }, baseline)).toBe(true);
    expect(legalDraftDirty({ title: 'A', content: '<p>y</p>' }, baseline)).toBe(true);
    expect(
      legalDraftDirty({ title: undefined, content: undefined }, { title: '', content: '' }),
    ).toBe(false);
  });

  test('the language list becomes options', () => {
    expect(
      languageOptions({ tr: { code: 'tr', name: 'Turkce' }, 'en-US': { code: 'en-US' }, x: null }),
    ).toEqual([
      { code: 'tr', name: 'Turkce' },
      { code: 'en-US', name: 'en-US' },
    ]);
    expect(languageOptions(null)).toEqual([]);
  });
});

describe('settings page', () => {
  test('?section= resolves to a known section, general otherwise', () => {
    expect(resolveSection('billing')).toBe('billing');
    expect(resolveSection('webhook-deliveries')).toBe('webhook-deliveries');
    expect(resolveSection('nope')).toBe('general');
    expect(resolveSection(null)).toBe('general');
    expect(resolveSection(undefined)).toBe('general');
  });

  test('the section list is the nav list of navigation.js', async () => {
    const { SECTIONS } = await import('../navigation.js');
    const nav = SECTIONS.filter((s) => s.area === 'settings').map((s) => s.key);
    expect(PAGE_SECTIONS).toEqual(nav);
  });

  test('only the sections of the 13 §17 table load extra data', () => {
    const extra = {
      currencies: '/settings/currencies',
      legal: '/settings/legal',
      delivery: '/servers',
      minecraft: '/servers',
      mail: '/health',
      health: '/health',
      payments: '/payment-providers',
    };
    for (const [key, path] of Object.entries(extra)) expect(extraPathFor(key)).toBe(path);
    for (const key of PAGE_SECTIONS.filter((k) => !(k in extra)))
      expect(extraPathFor(key)).toBeNull();
  });
});

describe('locale keys built at run time by the settings components', () => {
  const get = (locale, path) => path.split('.').reduce((node, part) => node?.[part], locale);
  const keys = [
    ...CURRENCY_MODES.flatMap((m) => [
      `enums.currency-mode.${m}`,
      `settings.currencies.mode-hint.${m}`,
    ]),
    ...MULTI_FALLBACKS.flatMap((f) => [
      `settings.currencies.fallback-option.${f}`,
      `settings.currencies.fallback-hint.${f}`,
    ]),
    ...RATE_MODES.map((m) => `enums.rate-source.${m}`),
    ...BILLING_INFO_MODES.flatMap((m) => [
      `enums.billing-info-mode.${m}`,
      `settings.billing.info-mode-hint.${m}`,
    ]),
    ...['STORE_DISABLED', 'CURRENCY_CHANGED'].flatMap((r) => [
      `settings.general.confirm.${r}.title`,
      `settings.general.confirm.${r}.description`,
    ]),
  ];

  test('every key exists as a non-empty string in tr, en-US and ru', () => {
    for (const [name, locale] of [
      ['en-US', en],
      ['tr', tr],
      ['ru', ru],
    ])
      for (const key of keys) {
        const value = get(locale, key);
        expect([name, key, typeof value]).toEqual([name, key, 'string']);
        expect(value.trim()).not.toBe('');
      }
  });

  test('the card header descriptions of the old general settings are gone (13 §17)', () => {
    for (const locale of [en, tr, ru]) {
      expect(locale.settings.general.heading).toBeUndefined();
      expect(locale.settings.general.subheading).toBeUndefined();
    }
  });
});
