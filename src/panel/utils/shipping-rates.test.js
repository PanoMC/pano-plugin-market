import { describe, expect, test } from 'bun:test';
import {
  blankBlock,
  blankMethodForm,
  blankRow,
  blankZoneForm,
  blocksFromRates,
  buildMethodBody,
  buildRatesPayload,
  buildZoneBody,
  canQuote,
  carrierBalance,
  carrierBehavior,
  carrierBlocks,
  countrySummary,
  isPostalPattern,
  isTrackingTemplate,
  methodServerErrors,
  methodToForm,
  nextRowFrom,
  onlyManualCarrier,
  providerOptions,
  rateErrorMap,
  rateSourceAllowed,
  regionsEditable,
  sortCarriers,
  switchRateSource,
  validateBlocks,
  validateMethod,
  validateRates,
  validateZone,
  withBasis,
  withProvider,
  zoneCount,
  zoneServerErrors,
  zoneToForm,
} from './shipping-rates.js';

const row = (rangeFrom, rangeTo, price = 5, perUnitPrice = 0) => ({
  rangeFrom,
  rangeTo,
  price,
  perUnitPrice,
});
const codes = (result) => result.errors.map((e) => e.code);

describe('validateRates (test 24)', () => {
  test('non overlapping ranges with a gap between them are valid', () => {
    const result = validateRates([row(0, 1000), row(1000, 2000), row(2000, null)], 'WEIGHT');
    expect(result.ok).toBe(true);
    expect(result.errors).toEqual([]);
    expect(result.warnings).toEqual([]);
  });

  test('overlapping ranges are rejected, on the later row', () => {
    const result = validateRates([row(0, 1500), row(1000, 2000)], 'WEIGHT');
    expect(result.ok).toBe(false);
    expect(result.errors).toEqual([{ row: 1, field: 'rangeFrom', code: 'OVERLAP' }]);
  });

  test('a range inside another one is an overlap, whatever the typing order', () => {
    const result = validateRates([row(500, 800), row(0, 1000)], 'WEIGHT');
    expect(codes(result)).toEqual(['OVERLAP']);
    expect(result.errors[0].row).toBe(0);
  });

  test('two rows starting at the same value overlap', () => {
    expect(codes(validateRates([row(0, 100), row(0, 200)], 'QUANTITY'))).toEqual(['OVERLAP']);
  });

  test('touching ranges (to == next.from) are accepted', () => {
    const result = validateRates([row(0, 1000), row(1000, 2000)], 'WEIGHT');
    expect(result.ok).toBe(true);
    expect(result.warnings).toEqual([]);
  });

  test('touching ranges typed out of order are accepted too', () => {
    expect(validateRates([row(1000, 2000), row(0, 1000)], 'AMOUNT').ok).toBe(true);
  });

  test('a gap is reported as a warning, not an error', () => {
    const result = validateRates([row(0, 1000), row(2000, 3000)], 'WEIGHT');
    expect(result.ok).toBe(true);
    expect(result.errors).toEqual([]);
    expect(result.warnings).toEqual([{ row: 0, next: 1, code: 'GAP', from: 1000, to: 2000 }]);
  });

  test('an open row that is not the last one is rejected', () => {
    const result = validateRates([row(0, null), row(1000, 2000)], 'WEIGHT');
    expect(result.ok).toBe(false);
    expect(result.errors).toEqual([{ row: 0, field: 'rangeTo', code: 'OPEN_NOT_LAST' }]);
  });

  test('an open last row is fine', () => {
    expect(validateRates([row(0, 1000), row(1000, null)], 'WEIGHT').ok).toBe(true);
  });

  test('rangeTo <= rangeFrom is rejected', () => {
    expect(validateRates([row(1000, 1000)], 'WEIGHT').errors).toEqual([
      { row: 0, field: 'rangeTo', code: 'RANGE_ORDER' },
    ]);
    expect(codes(validateRates([row(1000, 500)], 'WEIGHT'))).toEqual(['RANGE_ORDER']);
  });

  test('FLAT takes exactly one row', () => {
    const flat = (price) => ({ rangeFrom: 0, rangeTo: null, price, perUnitPrice: 0 });
    expect(validateRates([flat(4.9)], 'FLAT').ok).toBe(true);
    expect(codes(validateRates([flat(1), flat(2)], 'FLAT'))).toContain('FLAT_SINGLE');
    expect(codes(validateRates([], 'FLAT'))).toContain('NO_ROWS');
  });

  test('FLAT ignores its range cells', () => {
    expect(
      validateRates([{ rangeFrom: NaN, rangeTo: NaN, price: 1, perUnitPrice: 0 }], 'FLAT').ok,
    ).toBe(true);
  });

  test('prices: missing, unparsable and negative values', () => {
    expect(validateRates([row(0, null, null)], 'WEIGHT').errors).toEqual([
      { row: 0, field: 'price', code: 'REQUIRED' },
    ]);
    expect(validateRates([row(0, null, NaN)], 'WEIGHT').errors).toEqual([
      { row: 0, field: 'price', code: 'INVALID' },
    ]);
    expect(validateRates([row(0, null, -1)], 'WEIGHT').errors).toEqual([
      { row: 0, field: 'price', code: 'NEGATIVE' },
    ]);
    expect(validateRates([row(0, null, 5, -2)], 'QUANTITY').errors).toEqual([
      { row: 0, field: 'perUnitPrice', code: 'NEGATIVE' },
    ]);
  });

  test('perUnitPrice is only read for WEIGHT and QUANTITY', () => {
    expect(validateRates([row(0, null, 5, NaN)], 'AMOUNT').ok).toBe(true);
    expect(validateRates([row(0, null, 5, null)], 'WEIGHT').ok).toBe(true);
  });

  test('rangeFrom is required and non negative; an unparsable rangeTo is invalid', () => {
    expect(validateRates([row(null, null)], 'WEIGHT').errors).toEqual([
      { row: 0, field: 'rangeFrom', code: 'REQUIRED' },
    ]);
    expect(validateRates([row(-1, null)], 'WEIGHT').errors).toEqual([
      { row: 0, field: 'rangeFrom', code: 'NEGATIVE' },
    ]);
    expect(validateRates([row(0, NaN)], 'WEIGHT').errors).toEqual([
      { row: 0, field: 'rangeTo', code: 'INVALID' },
    ]);
  });

  test('unknown basis and no rows are errors; more than 200 rows too', () => {
    expect(validateRates([row(0, null)], 'ZONE').ok).toBe(false);
    expect(codes(validateRates([], 'WEIGHT'))).toEqual(['NO_ROWS']);
    const many = Array.from({ length: 201 }, (_, i) => row(i, i + 1));
    expect(codes(validateRates(many, 'QUANTITY'))).toContain('TOO_MANY');
  });

  test('the first error per cell and the block errors are mapped', () => {
    const result = validateRates([row(0, 1500, null), row(1000, 2000)], 'WEIGHT');
    expect(rateErrorMap(result)).toEqual({ '0.price': 'REQUIRED', '1.rangeFrom': 'OVERLAP' });
    expect(rateErrorMap(validateRates([], 'WEIGHT'))).toEqual({ block: 'NO_ROWS' });
  });

  test('an error row does not hide the overlap of the other rows', () => {
    const result = validateRates([row(0, 1500), row(1000, 2000), row(NaN, 3000)], 'WEIGHT');
    expect(codes(result)).toContain('OVERLAP');
    expect(
      result.errors.some((e) => e.row === 2 && e.field === 'rangeFrom' && e.code === 'INVALID'),
    ).toBe(true);
  });
});

describe('rate blocks', () => {
  test('switching the basis restarts the block with one open row and keeps the price', () => {
    const block = { zoneId: 1, basis: 'WEIGHT', rows: [row(0, 1000, 7.5, 1), row(1000, null, 9)] };
    expect(withBasis(block, 'WEIGHT')).toBe(block);
    expect(withBasis(block, 'FLAT')).toEqual({
      zoneId: 1,
      basis: 'FLAT',
      rows: [{ rangeFrom: 0, rangeTo: null, price: 7.5, perUnitPrice: 0 }],
    });
    expect(withBasis(block, 'AMOUNT').rows).toEqual([
      { rangeFrom: 0, rangeTo: null, price: 7.5, perUnitPrice: 0 },
    ]);
    expect(withBasis(block, 'QUANTITY').rows[0].perUnitPrice).toBeNull();
  });

  test('blank rows follow the basis', () => {
    expect(blankRow('FLAT', { rangeFrom: 9 })).toEqual({
      rangeFrom: 0,
      rangeTo: null,
      price: null,
      perUnitPrice: 0,
    });
    expect(blankRow('WEIGHT').perUnitPrice).toBeNull();
    expect(blankBlock(3, 'AMOUNT')).toEqual({
      zoneId: 3,
      basis: 'AMOUNT',
      rows: [blankRow('AMOUNT')],
    });
  });

  test('the next row starts where the last one ends', () => {
    expect(nextRowFrom([])).toBe(0);
    expect(nextRowFrom([row(0, 1000), row(1000, 2500)])).toBe(2500);
    expect(nextRowFrom([row(1000, 2500), row(0, 1000)])).toBe(2500);
    expect(nextRowFrom([row(0, null)])).toBeNull();
  });

  test('validateBlocks: rule sources need at least one zone, CARRIER needs a ticked zone', () => {
    const zones = [{ id: 1 }, { id: 2 }];
    expect(validateBlocks([], { zones, rateSource: 'RULES' }).general).toBe('RATES_REQUIRED');
    expect(validateBlocks([], { zones, rateSource: 'CARRIER_WITH_FALLBACK' }).general).toBe(
      'RATES_REQUIRED',
    );
    expect(validateBlocks([], { zones, rateSource: 'CARRIER' }).general).toBe('ZONE_REQUIRED');
    expect(validateBlocks(carrierBlocks([1, 2]), { zones, rateSource: 'CARRIER' }).ok).toBe(true);
  });

  test('validateBlocks reports a bad row under its zone and flags duplicate / unknown zones', () => {
    const zones = [{ id: 1 }];
    const bad = { zoneId: 1, basis: 'WEIGHT', rows: [row(0, 1500), row(1000, 2000)] };
    const result = validateBlocks([bad], { zones, rateSource: 'RULES' });
    expect(result.ok).toBe(false);
    expect(result.byZone[1].ok).toBe(false);
    expect(validateBlocks([blankBlock(1), blankBlock(1)], { zones }).general).toBe(
      'DUPLICATE_ZONE',
    );
    expect(validateBlocks([blankBlock(9)], { zones }).general).toBe('UNKNOWN_ZONE');
  });

  test('validateBlocks accepts a good rule set', () => {
    const block = {
      zoneId: 1,
      basis: 'FLAT',
      rows: [{ rangeFrom: 0, rangeTo: null, price: 3, perUnitPrice: 0 }],
    };
    expect(validateBlocks([block], { zones: [{ id: 1 }], rateSource: 'RULES' })).toEqual({
      ok: true,
      general: null,
      byZone: { 1: { ok: true, errors: [], warnings: [] } },
    });
  });

  test('the payload follows the row order of each block, sorted by rangeFrom, normalised per basis', () => {
    const blocks = [
      { zoneId: 1, basis: 'WEIGHT', rows: [row(1000, null, 9, 1.5), row(0, 1000, 5, 0)] },
      {
        zoneId: 2,
        basis: 'FLAT',
        rows: [{ rangeFrom: 7, rangeTo: 9, price: 4.9, perUnitPrice: 3 }],
      },
      {
        zoneId: 3,
        basis: 'AMOUNT',
        rows: [{ rangeFrom: 0, rangeTo: 50, price: 6, perUnitPrice: 2 }],
      },
    ];
    expect(buildRatesPayload(blocks)).toEqual([
      { zoneId: 1, basis: 'WEIGHT', rangeFrom: 0, rangeTo: 1000, price: 5, perUnitPrice: 0 },
      { zoneId: 1, basis: 'WEIGHT', rangeFrom: 1000, rangeTo: null, price: 9, perUnitPrice: 1.5 },
      { zoneId: 2, basis: 'FLAT', rangeFrom: 0, rangeTo: null, price: 4.9, perUnitPrice: 0 },
      { zoneId: 3, basis: 'AMOUNT', rangeFrom: 0, rangeTo: 50, price: 6, perUnitPrice: 0 },
    ]);
  });

  test('a CARRIER method sends one FLAT price 0 row per ticked zone', () => {
    expect(buildRatesPayload(carrierBlocks([4, 2]), 'CARRIER')).toEqual([
      { zoneId: 4, basis: 'FLAT', rangeFrom: 0, rangeTo: null, price: 0, perUnitPrice: 0 },
      { zoneId: 2, basis: 'FLAT', rangeFrom: 0, rangeTo: null, price: 0, perUnitPrice: 0 },
    ]);
  });

  test('rates of a loaded method become blocks; a second basis in a zone is dropped and flagged', () => {
    const rates = [
      { zoneId: 1, basis: 'WEIGHT', rangeFrom: 0, rangeTo: 1000, price: 5, perUnitPrice: 0 },
      { zoneId: 1, basis: 'WEIGHT', rangeFrom: 1000, rangeTo: null, price: 8, perUnitPrice: 1 },
      { zoneId: 2, basis: 'FLAT', rangeFrom: 0, rangeTo: null, price: 3, perUnitPrice: 0 },
    ];
    const { blocks, lossy } = blocksFromRates(rates);
    expect(lossy).toBe(false);
    expect(blocks.map((b) => [b.zoneId, b.basis, b.rows.length])).toEqual([
      [1, 'WEIGHT', 2],
      [2, 'FLAT', 1],
    ]);
    expect(zoneCount(rates)).toBe(2);

    const mixed = blocksFromRates([
      ...rates,
      { zoneId: 2, basis: 'QUANTITY', rangeFrom: 0, rangeTo: null, price: 1, perUnitPrice: 0 },
    ]);
    expect(mixed.lossy).toBe(true);
    expect(mixed.blocks[1].rows.length).toBe(1);
    expect(blocksFromRates(undefined)).toEqual({ blocks: [], lossy: false });
  });

  test('entering CARRIER keeps the zones with price 0 and stashes the rule rows', () => {
    const rules = [
      { zoneId: 1, basis: 'WEIGHT', rows: [row(0, null, 7)] },
      { zoneId: 2, basis: 'FLAT', rows: [row(0, null, 3)] },
    ];
    const entered = switchRateSource(rules, 'RULES', 'CARRIER');
    expect(entered.blocks).toEqual(carrierBlocks([1, 2]));
    expect(entered.stash).toBe(rules);
    expect(switchRateSource(rules, 'RULES', 'RULES')).toEqual({ blocks: rules, stash: null });
    expect(switchRateSource(rules, 'RULES', 'CARRIER_WITH_FALLBACK').blocks).toBe(rules);
  });

  test('leaving CARRIER restores the stash; without one the prices start empty, never 0', () => {
    const rules = [{ zoneId: 1, basis: 'WEIGHT', rows: [row(0, null, 7)] }];
    const carrier = carrierBlocks([1]);
    expect(switchRateSource(carrier, 'CARRIER', 'RULES', rules)).toEqual({
      blocks: rules,
      stash: null,
    });
    const fresh = switchRateSource(carrier, 'CARRIER', 'CARRIER_WITH_FALLBACK', null);
    expect(fresh.blocks).toEqual([blankBlock(1)]);
    expect(fresh.blocks[0].rows[0].price).toBeNull();
    // a zone ticked while in CARRIER mode gets an empty block; a zone un-ticked there is dropped from the stash
    const edited = switchRateSource(carrierBlocks([2]), 'CARRIER', 'RULES', [
      { zoneId: 1, basis: 'WEIGHT', rows: [row(0, null, 7)] },
      { zoneId: 2, basis: 'FLAT', rows: [row(0, null, 4)] },
    ]);
    expect(edited.blocks.map((b) => b.zoneId)).toEqual([2]);
  });

  test('a round trip keeps the rows', () => {
    const rates = [
      { zoneId: 1, basis: 'WEIGHT', rangeFrom: 0, rangeTo: 1000, price: 5, perUnitPrice: 0 },
      { zoneId: 1, basis: 'WEIGHT', rangeFrom: 1000, rangeTo: null, price: 8, perUnitPrice: 1 },
    ];
    expect(buildRatesPayload(blocksFromRates(rates).blocks)).toEqual(rates);
  });
});

describe('postal patterns', () => {
  test('prefix, numeric range and exact code are accepted', () => {
    for (const ok of ['34*', 'SW1*', '1000-1999', '0100-0199', '34000', 'SW1A1AA'])
      expect(isPostalPattern(ok)).toBe(true);
  });

  test('malformed patterns are rejected', () => {
    for (const bad of [
      '',
      '*',
      '34**',
      '3 4*',
      '1999-1000',
      '100-1999',
      'a-b',
      '12345678901*',
      '-1',
      'ab cd',
      '34*x',
    ])
      expect(isPostalPattern(bad)).toBe(false);
  });
});

describe('zones', () => {
  const zones = [
    { id: 1, name: 'Everywhere', countries: ['*'] },
    { id: 2, name: 'EU', countries: ['DE', 'FR'] },
  ];
  const form = (over = {}) => ({ ...blankZoneForm(), name: 'Turkey', countries: ['TR'], ...over });

  test('a valid zone passes', () => {
    expect(validateZone(form(), { zones })).toEqual({});
  });

  test('name is required and at most 128 characters', () => {
    expect(validateZone(form({ name: '  ' }), { zones }).name).toBe('REQUIRED');
    expect(validateZone(form({ name: 'x'.repeat(129) }), { zones }).name).toBe('TOO_LONG');
    expect(validateZone(form({ name: 'x'.repeat(128) }), { zones }).name).toBeUndefined();
  });

  test('countries: at least one unless "Everywhere Else"; only one Everywhere zone', () => {
    expect(validateZone(form({ countries: [] }), { zones }).countries).toBe('COUNTRY_REQUIRED');
    expect(validateZone(form({ everywhere: true, countries: [] }), { zones }).countries).toBe(
      'EVERYWHERE_EXISTS',
    );
    expect(
      validateZone(form({ everywhere: true, countries: [] }), { zones, id: 1 }).countries,
    ).toBeUndefined();
    expect(
      validateZone(form({ everywhere: true, countries: [] }), { zones: [zones[1]] }).countries,
    ).toBeUndefined();
  });

  test('regions are edited for up to five countries and each state is 1 to 64 characters', () => {
    expect(regionsEditable(form())).toBe(true);
    expect(regionsEditable(form({ countries: ['A1', 'B1', 'C1', 'D1', 'E1', 'F1'] }))).toBe(false);
    expect(regionsEditable(form({ everywhere: true, countries: [] }))).toBe(false);
    expect(validateZone(form({ regions: { TR: ['x'.repeat(65)] } }), { zones }).regions).toBe(
      'INVALID',
    );
    expect(
      validateZone(form({ regions: { TR: Array.from({ length: 101 }, (_, i) => `s${i}`) } }), {
        zones,
      }).regions,
    ).toBe('TOO_MANY');
    expect(
      validateZone(form({ regions: { TR: ['Istanbul'] } }), { zones }).regions,
    ).toBeUndefined();
  });

  test('postal patterns are validated and capped at 200', () => {
    expect(
      validateZone(form({ postalPatterns: ['34*', '1000-1999'] }), { zones }).postalPatterns,
    ).toBeUndefined();
    expect(validateZone(form({ postalPatterns: ['34*', '9-1'] }), { zones }).postalPatterns).toBe(
      'INVALID',
    );
    const many = Array.from({ length: 201 }, (_, i) => `A${i}`);
    expect(validateZone(form({ postalPatterns: many }), { zones }).postalPatterns).toBe('TOO_MANY');
  });

  test('the request body: Everywhere Else, regions only for selected countries, de-duplication', () => {
    expect(
      buildZoneBody(form({ everywhere: true, countries: ['TR'], regions: { TR: ['x'] } })),
    ).toEqual({
      name: 'Turkey',
      countries: ['*'],
      regions: [],
      postalPatterns: [],
      status: 'ACTIVE',
    });
    expect(
      buildZoneBody(
        form({
          name: ' Mix ',
          countries: ['DE', 'FR', 'DE'],
          regions: { DE: [' Bayern ', 'Bayern', ' '], XX: ['gone'], FR: [] },
          postalPatterns: ['34*', '34*'],
          active: false,
        }),
      ),
    ).toEqual({
      name: 'Mix',
      countries: ['DE', 'FR'],
      regions: [{ country: 'DE', states: ['Bayern'] }],
      postalPatterns: ['34*'],
      status: 'INACTIVE',
    });
  });

  test('regions are dropped from the body when the region editor is hidden', () => {
    const six = ['A1', 'B1', 'C1', 'D1', 'E1', 'F1'];
    expect(buildZoneBody(form({ countries: six, regions: { A1: ['s'] } })).regions).toEqual([]);
  });

  test('a stored zone becomes a form and back', () => {
    const zone = {
      id: 5,
      name: 'Turkey',
      countries: ['TR'],
      regions: [{ country: 'TR', states: ['Istanbul', 'Ankara'] }],
      postalPatterns: ['34*'],
      status: 'INACTIVE',
    };
    const f = zoneToForm(zone);
    expect(f).toEqual({
      name: 'Turkey',
      everywhere: false,
      countries: ['TR'],
      regions: { TR: ['Istanbul', 'Ankara'] },
      postalPatterns: ['34*'],
      active: false,
    });
    expect(buildZoneBody(f)).toEqual({
      name: 'Turkey',
      countries: ['TR'],
      regions: [{ country: 'TR', states: ['Istanbul', 'Ankara'] }],
      postalPatterns: ['34*'],
      status: 'INACTIVE',
    });
    expect(zoneToForm({ name: 'E', countries: ['*'] }).everywhere).toBe(true);
    expect(zoneToForm(null)).toEqual(blankZoneForm());
  });

  test('country summary: first five codes and +N, or Everywhere Else', () => {
    expect(countrySummary({ countries: ['*'] })).toEqual({ everywhere: true, shown: [], more: 0 });
    expect(countrySummary({ countries: ['A1', 'B1', 'C1'] })).toEqual({
      everywhere: false,
      shown: ['A1', 'B1', 'C1'],
      more: 0,
    });
    expect(countrySummary({ countries: ['A1', 'B1', 'C1', 'D1', 'E1', 'F1', 'G1'] })).toEqual({
      everywhere: false,
      shown: ['A1', 'B1', 'C1', 'D1', 'E1'],
      more: 2,
    });
  });

  test('server field errors keep the first code per zone field', () => {
    expect(
      zoneServerErrors({
        name: 'REQUIRED',
        'regions[0].states': 'WEIRD',
        other: 'X',
        countries: 5,
      }),
    ).toEqual({
      name: 'REQUIRED',
      regions: 'INVALID',
      countries: 'INVALID',
    });
    expect(zoneServerErrors(undefined)).toEqual({});
  });
});

describe('methods', () => {
  const carriers = [
    { id: 'manual', state: 'ACTIVE', config: { enabled: true }, capabilities: {} },
    {
      id: 'yurtici',
      state: 'ACTIVE',
      config: { enabled: true },
      capabilities: { rateQuote: true },
    },
    {
      id: 'old',
      state: 'UNAVAILABLE',
      config: { enabled: true },
      capabilities: { rateQuote: true },
    },
    { id: 'off', state: 'DISABLED', config: { enabled: false }, capabilities: {} },
  ];
  const form = (over = {}) => ({ ...blankMethodForm(), name: 'Standard', ...over });

  test('a default form is valid once it has a name', () => {
    expect(validateMethod(form(), {})).toEqual({});
    expect(validateMethod(blankMethodForm(), {}).name).toBe('REQUIRED');
  });

  test('field limits', () => {
    expect(validateMethod(form({ name: 'x'.repeat(129) })).name).toBe('TOO_LONG');
    expect(validateMethod(form({ description: 'x'.repeat(513) })).description).toBe('TOO_LONG');
    expect(validateMethod(form({ providerId: '' })).providerId).toBe('REQUIRED');
    expect(validateMethod(form({ carrierName: 'x'.repeat(129) })).carrierName).toBe('TOO_LONG');
  });

  test('free shipping threshold must be above zero, handling fee not negative', () => {
    expect(validateMethod(form({ freeShippingThreshold: 0 })).freeShippingThreshold).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateMethod(form({ freeShippingThreshold: NaN })).freeShippingThreshold).toBe(
      'INVALID',
    );
    expect(
      validateMethod(form({ freeShippingThreshold: 50 })).freeShippingThreshold,
    ).toBeUndefined();
    expect(
      validateMethod(form({ freeShippingThreshold: null })).freeShippingThreshold,
    ).toBeUndefined();
    expect(validateMethod(form({ handlingFee: -1 })).handlingFee).toBe('OUT_OF_RANGE');
    expect(validateMethod(form({ handlingFee: 0 })).handlingFee).toBeUndefined();
  });

  test('VAT: store default or 0 to 100', () => {
    expect(validateMethod(form({ vatDefault: true, vatPercent: 500 })).vatPercent).toBeUndefined();
    expect(validateMethod(form({ vatDefault: false, vatPercent: null })).vatPercent).toBe(
      'REQUIRED',
    );
    expect(validateMethod(form({ vatDefault: false, vatPercent: 100.5 })).vatPercent).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateMethod(form({ vatDefault: false, vatPercent: 0 })).vatPercent).toBeUndefined();
    expect(validateMethod(form({ vatDefault: false, vatPercent: 100 })).vatPercent).toBeUndefined();
  });

  test('delivery days are 0 to 365 and min <= max', () => {
    expect(validateMethod(form({ minDeliveryDays: '366' })).minDeliveryDays).toBe('OUT_OF_RANGE');
    expect(validateMethod(form({ maxDeliveryDays: 'x' })).maxDeliveryDays).toBe('OUT_OF_RANGE');
    expect(
      validateMethod(form({ minDeliveryDays: '5', maxDeliveryDays: '3' })).maxDeliveryDays,
    ).toBe('MIN_GT_MAX');
    expect(validateMethod(form({ minDeliveryDays: '3', maxDeliveryDays: '3' }))).toEqual({});
    expect(validateMethod(form({ minDeliveryDays: '0' }))).toEqual({});
    expect(validateMethod(form({ minDeliveryDays: '5' }))).toEqual({});
  });

  test('max weight is at least 1 gram', () => {
    expect(validateMethod(form({ maxWeightGrams: '0' })).maxWeightGrams).toBe('OUT_OF_RANGE');
    expect(validateMethod(form({ maxWeightGrams: '2000000001' })).maxWeightGrams).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateMethod(form({ maxWeightGrams: '1' }))).toEqual({});
  });

  test('tracking template needs http(s) and {tracking}; only the manual provider has one', () => {
    expect(isTrackingTemplate('https://t.example/{tracking}')).toBe(true);
    expect(isTrackingTemplate('http://t.example/?n={tracking}')).toBe(true);
    expect(isTrackingTemplate('ftp://t.example/{tracking}')).toBe(false);
    expect(isTrackingTemplate('https://t.example/')).toBe(false);
    expect(isTrackingTemplate('javascript:alert({tracking})')).toBe(false);
    expect(
      validateMethod(form({ trackingUrlTemplate: 'https://t.example/' })).trackingUrlTemplate,
    ).toBe('INVALID_TEMPLATE');
    expect(validateMethod(form({ trackingUrlTemplate: 'x'.repeat(513) })).trackingUrlTemplate).toBe(
      'TOO_LONG',
    );
    expect(validateMethod(form({ trackingUrlTemplate: 'https://t.example/{tracking}' }))).toEqual(
      {},
    );
    expect(
      validateMethod(form({ providerId: 'yurtici', trackingUrlTemplate: 'bad' }), {
        provider: carriers[1],
      }),
    ).toEqual({});
  });

  test('carrier rate sources need a provider that can quote live rates', () => {
    expect(canQuote(carriers[1])).toBe(true);
    expect(canQuote(carriers[0])).toBe(false);
    expect(canQuote(null)).toBe(false);
    expect(rateSourceAllowed('RULES', carriers[0])).toBe(true);
    expect(rateSourceAllowed('CARRIER', carriers[0])).toBe(false);
    expect(rateSourceAllowed('CARRIER_WITH_FALLBACK', carriers[1])).toBe(true);
    expect(validateMethod(form({ rateSource: 'CARRIER' })).rateSource).toBe(
      'CARRIER_QUOTE_UNSUPPORTED',
    );
    expect(
      validateMethod(form({ providerId: 'yurtici', rateSource: 'CARRIER' }), {
        provider: carriers[1],
      }),
    ).toEqual({});
    expect(
      validateMethod(form({ providerId: 'yurtici', rateSource: 'CARRIER' }), { provider: null })
        .rateSource,
    ).toBe('CARRIER_QUOTE_UNSUPPORTED');
    expect(validateMethod(form({ rateSource: 'WHATEVER' })).rateSource).toBe('INVALID');
  });

  test('choosing another provider clears the service and drops an unsupported rate source', () => {
    const start = form({ providerId: 'yurtici', serviceCode: 'EXPRESS', rateSource: 'CARRIER' });
    expect(withProvider(start, 'yurtici', carriers)).toMatchObject({
      serviceCode: '',
      rateSource: 'CARRIER',
    });
    expect(withProvider(start, 'manual', carriers)).toMatchObject({
      providerId: 'manual',
      serviceCode: '',
      rateSource: 'RULES',
    });
    expect(withProvider(start, 'gone', carriers).rateSource).toBe('RULES');
  });

  test('provider options: manual first, enabled next, the stored provider is kept', () => {
    const options = providerOptions(carriers, 'legacy', (c) => c.id);
    expect(options.map((o) => o.id)).toEqual(['manual', 'yurtici', 'legacy', 'off', 'old']);
    expect(options.find((o) => o.id === 'old').enabled).toBe(false);
    expect(options.find((o) => o.id === 'legacy')).toMatchObject({
      enabled: false,
      name: 'legacy',
    });
    expect(providerOptions(carriers, 'yurtici').filter((o) => o.id === 'yurtici')).toHaveLength(1);
  });

  test('the request body: normalised optionals, manual vs carrier fields, rates', () => {
    const blocks = [
      {
        zoneId: 1,
        basis: 'FLAT',
        rows: [{ rangeFrom: 0, rangeTo: null, price: 4.9, perUnitPrice: 0 }],
      },
    ];
    const body = buildMethodBody(
      form({
        name: ' Standard ',
        description: ' ',
        serviceCode: 'IGNORED',
        freeShippingThreshold: 75,
        handlingFee: null,
        vatDefault: false,
        vatPercent: 8,
        minDeliveryDays: '2',
        maxDeliveryDays: '5',
        maxWeightGrams: '20000',
        carrierName: ' Local Post ',
        trackingUrlTemplate: ' https://t.example/{tracking} ',
        active: false,
      }),
      blocks,
    );
    expect(body).toEqual({
      name: 'Standard',
      description: null,
      providerId: 'manual',
      serviceCode: null,
      rateSource: 'RULES',
      freeShippingThreshold: 75,
      handlingFee: 0,
      vatPercent: 8,
      minDeliveryDays: 2,
      maxDeliveryDays: 5,
      maxWeightGrams: 20000,
      carrierName: 'Local Post',
      trackingUrlTemplate: 'https://t.example/{tracking}',
      status: 'INACTIVE',
      rates: [
        { zoneId: 1, basis: 'FLAT', rangeFrom: 0, rangeTo: null, price: 4.9, perUnitPrice: 0 },
      ],
    });
  });

  test('a carrier method sends its service code and no manual fields; CARRIER sends price 0 rows', () => {
    const body = buildMethodBody(
      form({
        providerId: 'yurtici',
        serviceCode: ' EXPRESS ',
        rateSource: 'CARRIER',
        carrierName: 'x',
        trackingUrlTemplate: 'https://t.example/{tracking}',
      }),
      carrierBlocks([3]),
    );
    expect(body).toMatchObject({
      providerId: 'yurtici',
      serviceCode: 'EXPRESS',
      carrierName: null,
      trackingUrlTemplate: null,
      vatPercent: null,
      freeShippingThreshold: null,
      minDeliveryDays: null,
    });
    expect(body.rates).toEqual([
      { zoneId: 3, basis: 'FLAT', rangeFrom: 0, rangeTo: null, price: 0, perUnitPrice: 0 },
    ]);
  });

  test('a stored method becomes a form', () => {
    const f = methodToForm({
      name: 'Express',
      description: null,
      providerId: 'yurtici',
      serviceCode: 'EXPRESS',
      rateSource: 'CARRIER_WITH_FALLBACK',
      freeShippingThreshold: '100',
      handlingFee: 2,
      vatPercent: 0,
      minDeliveryDays: 1,
      maxDeliveryDays: null,
      maxWeightGrams: 30000,
      status: 'INACTIVE',
    });
    expect(f).toMatchObject({
      name: 'Express',
      description: '',
      providerId: 'yurtici',
      serviceCode: 'EXPRESS',
      rateSource: 'CARRIER_WITH_FALLBACK',
      freeShippingThreshold: 100,
      handlingFee: 2,
      vatDefault: false,
      vatPercent: 0,
      minDeliveryDays: '1',
      maxDeliveryDays: '',
      maxWeightGrams: '30000',
      active: false,
    });
    expect(methodToForm({ name: 'A' })).toMatchObject({
      providerId: 'manual',
      rateSource: 'RULES',
      vatDefault: true,
      handlingFee: 0,
      active: true,
    });
    expect(methodToForm(null)).toEqual(blankMethodForm());
  });

  test('server field errors: rate codes collapse into one message', () => {
    expect(
      methodServerErrors({ name: 'REQUIRED', 'rates[1]': 'RATE_OVERLAP', 'rates[2].price': 'X' }),
    ).toEqual({
      fields: { name: 'REQUIRED' },
      rates: 'RATE_OVERLAP',
    });
    expect(methodServerErrors({ rates: 'RATE_UNREACHABLE' }).rates).toBe('RATE_UNREACHABLE');
    expect(methodServerErrors({ 'rates.0.price': 'whatever' }).rates).toBe('INVALID');
    expect(methodServerErrors({ name: 'SOMETHING_NEW' }).fields.name).toBe('INVALID');
    expect(methodServerErrors({ providerId: 7 })).toEqual({
      fields: { providerId: 'INVALID' },
      rates: null,
    });
    expect(methodServerErrors(null)).toEqual({ fields: {}, rates: null });
  });
});

describe('carriers', () => {
  test('the manual carrier can never be switched off, others follow the provider states', () => {
    const manual = carrierBehavior({ id: 'manual', state: 'ACTIVE' });
    expect(manual).toMatchObject({ switchOn: true, switchDisabled: true, manualLocked: true });
    expect(carrierBehavior({ id: 'x', state: 'ACTIVE' })).toMatchObject({
      switchOn: true,
      switchDisabled: false,
    });
    expect(carrierBehavior({ id: 'x', state: 'DISABLED' })).toMatchObject({
      switchOn: false,
      switchDisabled: false,
    });
    expect(carrierBehavior({ id: 'x', state: 'NOT_CONFIGURED' })).toMatchObject({
      switchDisabled: true,
      switchHint: 'configure-first',
    });
    expect(carrierBehavior({ id: 'x', state: 'UNAVAILABLE' })).toMatchObject({
      readOnly: true,
      reason: 'unavailable',
    });
    expect(carrierBehavior({ id: 'x', state: 'INCOMPATIBLE' })).toMatchObject({
      readOnly: true,
      reason: 'incompatible',
    });
  });

  test('balance is shown only when it is a usable amount and currency', () => {
    expect(carrierBalance({ balance: { amount: '12.5', currency: 'EUR' } })).toEqual({
      amount: 12.5,
      currency: 'EUR',
    });
    expect(carrierBalance({ balance: null })).toBeNull();
    expect(carrierBalance({ balance: { amount: 'x', currency: 'EUR' } })).toBeNull();
    expect(carrierBalance({ balance: { amount: 1 } })).toBeNull();
    expect(carrierBalance({})).toBeNull();
  });

  test('sorting keeps manual first and enabled before disabled', () => {
    const list = [
      { id: 'b', config: { enabled: false } },
      { id: 'a', config: { enabled: true } },
      { id: 'manual', config: { enabled: true } },
    ];
    expect(sortCarriers(list).map((c) => c.id)).toEqual(['manual', 'a', 'b']);
    expect(onlyManualCarrier([{ id: 'manual' }])).toBe(true);
    expect(onlyManualCarrier(list)).toBe(false);
    expect(onlyManualCarrier(undefined)).toBe(true);
  });
});
