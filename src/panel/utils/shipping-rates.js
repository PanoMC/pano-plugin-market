// Pure rules of the shipping settings (13 §19, 10 §4.2 / §5.1): zone form, method form, the per-zone
// rate editor (`validateRates`, test 24 of 13 §25.1) and the carrier row behaviour. No Svelte, no SDK
// import, so it is unit tested.
import { rowBehavior } from './payment-methods.js';
import { parseInteger } from './format.js';

export const RATE_BASES = ['FLAT', 'WEIGHT', 'AMOUNT', 'QUANTITY'];
export const RATE_SOURCES = ['RULES', 'CARRIER', 'CARRIER_WITH_FALLBACK'];
export const MANUAL_PROVIDER = 'manual';

export const MAX_ZONES = 100;
export const MAX_ZONE_NAME = 128;
export const MAX_POSTAL_PATTERNS = 200;
export const MAX_STATES = 100;
export const MAX_STATE_LENGTH = 64;
/** Limits of the server for the states of one country (10 §4.2): used while the region editor is hidden. */
export const MAX_SERVER_STATES = 200;
export const MAX_SERVER_STATE_LENGTH = 100;
/** Regions are edited per country only while at most this many countries are selected (13 §19.1). */
export const MAX_REGION_COUNTRIES = 5;
export const COUNTRIES_PREVIEW = 5;

export const MAX_METHOD_NAME = 128;
export const MAX_METHOD_DESCRIPTION = 512;
export const MAX_SERVICE_CODE = 128;
export const MAX_CARRIER_NAME = 128;
export const MAX_TRACKING_TEMPLATE = 512;
export const MAX_DELIVERY_DAYS = 365;
export const MAX_WEIGHT_GRAMS = 2_000_000_000;
export const MAX_RATE_ROWS = 200;

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';
const isNum = (value) => typeof value === 'number' && Number.isFinite(value);
const isMissing = (value) => value === null || value === undefined || value === '';

// ---------------------------------------------------------------------------------------------
// Rate rows (13 §19.2, test 24)
// ---------------------------------------------------------------------------------------------

/** Bases that carry a per-unit price: grams (per started kg) and quantity (per extra item). */
export const basisHasPerUnit = (basis) => basis === 'WEIGHT' || basis === 'QUANTITY';
/** Bases whose ranges are typed as integers (grams, units). AMOUNT ranges are money. */
export const basisRangeIsInteger = (basis) => basis === 'WEIGHT' || basis === 'QUANTITY';

/** A fresh row of a basis; FLAT is always `[0, open)` with no per-unit price. */
export function blankRow(basis, { rangeFrom = 0, price = null } = {}) {
  return {
    rangeFrom: basis === 'FLAT' ? 0 : rangeFrom,
    rangeTo: null,
    price,
    perUnitPrice: basisHasPerUnit(basis) ? null : 0,
  };
}

/** One block (one zone) with a single open row, the shape a newly added zone starts in. */
export function blankBlock(zoneId, basis = 'FLAT') {
  return { zoneId, basis, rows: [blankRow(basis)] };
}

/**
 * Switching the basis changes the unit of the ranges (grams / money / units), so the old ranges are
 * meaningless: the block restarts with one open row that keeps the first row's price.
 */
export function withBasis(block, basis) {
  if (block.basis === basis) return block;
  const price = block.rows?.[0]?.price ?? null;
  return { ...block, basis, rows: [blankRow(basis, { price })] };
}

/**
 * Smallest step between two typeable range values: 1 gram / 1 unit, or one minor unit of the currency
 * (`10^-exponent`) for AMOUNT. Ranges are inclusive on both ends (10 §5.1, RateEngine `rangeFrom <= m <= rangeTo`),
 * so the next row of a band ending at `to` starts at `to + quantum`.
 */
export function rangeQuantum(basis, exponent = 2) {
  if (basis !== 'AMOUNT') return 1;
  const digits = Number.isInteger(exponent) && exponent >= 0 ? exponent : 2;
  return Number(`1e-${digits}`);
}

/** Decimals of a quantum (1 -> 0, 0.01 -> 2), used to keep `to + quantum` free of float noise. */
const quantumDecimals = (quantum) => Math.max(0, Math.round(-Math.log10(quantum)));

/**
 * Start of a row appended below the sorted rows: the `rangeTo` of the last one plus one quantum (the
 * ranges are inclusive, so the same value would overlap), null while it is open.
 */
export function nextRowFrom(rows, quantum = 1) {
  const closed = rows.filter((r) => isNum(r.rangeFrom));
  if (closed.length === 0) return 0;
  const last = [...closed].sort((a, b) => a.rangeFrom - b.rangeFrom).at(-1);
  if (!isNum(last.rangeTo)) return null;
  return Number((last.rangeTo + quantum).toFixed(quantumDecimals(quantum)));
}

/**
 * Validation of ONE zone's rows. Ranges are INCLUSIVE on both ends, like the server (10 §5.1 RATE_OVERLAP,
 * 10 §5.2 `rangeFrom <= m <= rangeTo`). Row values are Number, null (empty) or NaN (unparsable text).
 * `quantum` is the smallest step of the range unit (`rangeQuantum`). Returns `{ ok, errors, warnings }`:
 *  - `errors`: `{ row, field, code }` (`row` null = the whole block). Codes: `NO_ROWS`, `FLAT_SINGLE`,
 *    `REQUIRED`, `INVALID`, `NEGATIVE`, `RANGE_ORDER` (`to < from`; `to == from` is a single-value band),
 *    `OVERLAP` (a row starts at or below the previous `to`, a shared boundary included), `OPEN_NOT_LAST`.
 *  - `warnings`: `{ row, next, code: 'GAP', from, to }` when more than one quantum separates the `to` of a
 *    row from the `from` of the next sorted one (allowed: nothing is offered in between).
 * Adjacent ranges (`to + quantum == next.from`) are accepted.
 */
export function validateRates(rows, basis, quantum = 1) {
  const errors = [];
  const warnings = [];
  const list = Array.isArray(rows) ? rows : [];

  if (!RATE_BASES.includes(basis)) errors.push({ row: null, field: 'basis', code: 'INVALID' });
  if (list.length === 0) errors.push({ row: null, field: 'rows', code: 'NO_ROWS' });
  if (basis === 'FLAT' && list.length > 1)
    errors.push({ row: null, field: 'rows', code: 'FLAT_SINGLE' });
  if (list.length > MAX_RATE_ROWS) errors.push({ row: null, field: 'rows', code: 'TOO_MANY' });

  const money = (row, field, { required }) => {
    const value = row[field];
    if (isMissing(value)) {
      if (required) errors.push({ row: row.index, field, code: 'REQUIRED' });
      return null;
    }
    if (!isNum(value)) {
      errors.push({ row: row.index, field, code: 'INVALID' });
      return null;
    }
    if (value < 0) {
      errors.push({ row: row.index, field, code: 'NEGATIVE' });
      return null;
    }
    return value;
  };

  const indexed = list.map((row, index) => ({ ...row, index }));
  // true when the row has usable bounds and can take part in the overlap / gap pass
  const ranged = [];

  for (const row of indexed) {
    money(row, 'price', { required: true });
    if (basisHasPerUnit(basis)) money(row, 'perUnitPrice', { required: false });
    if (basis === 'FLAT') continue;

    const from = money(row, 'rangeFrom', { required: true });
    let to = null;
    let toValid = true;
    if (!isMissing(row.rangeTo)) {
      to = money(row, 'rangeTo', { required: false });
      toValid = to !== null;
    }
    if (from !== null && toValid && to !== null && to < from)
      errors.push({ row: row.index, field: 'rangeTo', code: 'RANGE_ORDER' });
    else if (from !== null && toValid) ranged.push({ index: row.index, from, to });
  }

  const sorted = [...ranged].sort((a, b) => a.from - b.from || a.index - b.index);
  for (let i = 0; i < sorted.length - 1; i++) {
    const a = sorted[i];
    const b = sorted[i + 1];
    if (a.to === null) errors.push({ row: a.index, field: 'rangeTo', code: 'OPEN_NOT_LAST' });
    else if (a.to >= b.from) errors.push({ row: b.index, field: 'rangeFrom', code: 'OVERLAP' });
    else if (b.from - a.to > quantum * 1.5)
      warnings.push({ row: a.index, next: b.index, code: 'GAP', from: a.to, to: b.from });
  }

  return { ok: errors.length === 0, errors, warnings };
}

/** `{ 'row.field': code }` of the first error per cell; block-level errors under `'block'`. */
export function rateErrorMap(result) {
  const map = {};
  for (const error of result?.errors ?? []) {
    const key = error.row === null ? 'block' : `${error.row}.${error.field}`;
    if (!(key in map)) map[key] = error.code;
  }
  return map;
}

/**
 * Validates every block of a method: `{ ok, general, byZone }`. `general` is `RATES_REQUIRED` when a rule
 * based source has no zone at all, `DUPLICATE_ZONE` when a zone appears twice, `UNKNOWN_ZONE` for a zone
 * that is not in `zones`, `TOO_MANY` over 200 rows in total. `exponent` = decimals of the base currency
 * (the quantum of AMOUNT ranges). `CARRIER` methods carry no rule rows:
 * their blocks are the zone ticks (`carrierBlocks`) and only the zone checks apply.
 */
export function validateBlocks(blocks, { zones = [], rateSource = 'RULES', exponent = 2 } = {}) {
  const byZone = {};
  let general = null;
  const list = Array.isArray(blocks) ? blocks : [];
  const known = new Set(zones.map((z) => z.id));
  const seen = new Set();

  if (rateSource !== 'CARRIER' && list.length === 0) general = 'RATES_REQUIRED';
  if (rateSource === 'CARRIER' && list.length === 0) general = 'ZONE_REQUIRED';

  const totalRows = list.reduce((sum, b) => sum + (b.rows?.length ?? 0), 0);
  if (!general && totalRows > MAX_RATE_ROWS) general = 'TOO_MANY';

  let ok = general === null;
  for (const block of list) {
    if (seen.has(block.zoneId)) general ??= 'DUPLICATE_ZONE';
    seen.add(block.zoneId);
    if (known.size > 0 && !known.has(block.zoneId)) general ??= 'UNKNOWN_ZONE';
    if (rateSource === 'CARRIER') continue;
    const result = validateRates(block.rows, block.basis, rangeQuantum(block.basis, exponent));
    byZone[block.zoneId] = result;
    if (!result.ok) ok = false;
  }
  if (general !== null) ok = false;
  return { ok, general, byZone };
}

/** The request rows of one method: one entry per row, FLAT / AMOUNT normalised, rows sorted by `rangeFrom`. */
export function buildRatesPayload(blocks, rateSource = 'RULES') {
  const out = [];
  for (const block of blocks ?? []) {
    if (rateSource === 'CARRIER') {
      // a CARRIER method needs one FLAT row with price 0 per zone it is offered in (10 §5.1)
      out.push({
        zoneId: block.zoneId,
        basis: 'FLAT',
        rangeFrom: 0,
        rangeTo: null,
        price: 0,
        perUnitPrice: 0,
      });
      continue;
    }
    const rows = [...block.rows];
    if (block.basis !== 'FLAT') rows.sort((a, b) => a.rangeFrom - b.rangeFrom);
    for (const row of rows) {
      const flat = block.basis === 'FLAT';
      out.push({
        zoneId: block.zoneId,
        basis: block.basis,
        rangeFrom: flat ? 0 : row.rangeFrom,
        rangeTo: flat || isMissing(row.rangeTo) ? null : row.rangeTo,
        price: row.price,
        perUnitPrice:
          basisHasPerUnit(block.basis) && isNum(row.perUnitPrice) ? row.perUnitPrice : 0,
      });
    }
  }
  return out;
}

/**
 * Blocks (one per zone, first-appearance order) from the `rates[]` of a loaded method. A zone holding
 * several bases (the API tolerates it, the editor does not) keeps the rows of its first row's basis;
 * `lossy` is then true so the page can warn.
 */
export function blocksFromRates(rates) {
  const order = [];
  const groups = new Map();
  let lossy = false;
  for (const rate of Array.isArray(rates) ? rates : []) {
    if (!groups.has(rate.zoneId)) {
      groups.set(rate.zoneId, { zoneId: rate.zoneId, basis: rate.basis, rows: [] });
      order.push(rate.zoneId);
    }
    const block = groups.get(rate.zoneId);
    if (rate.basis !== block.basis) {
      lossy = true;
      continue;
    }
    block.rows.push({
      rangeFrom: block.basis === 'FLAT' ? 0 : Number(rate.rangeFrom ?? 0),
      rangeTo: rate.rangeTo === null || rate.rangeTo === undefined ? null : Number(rate.rangeTo),
      price: rate.price === null || rate.price === undefined ? null : Number(rate.price),
      perUnitPrice: Number(rate.perUnitPrice ?? 0),
    });
  }
  return { blocks: order.map((id) => groups.get(id)), lossy };
}

/** A `CARRIER` method's blocks: one FLAT, price 0 block for every ticked zone, in `zoneIds` order. */
export const carrierBlocks = (zoneIds) =>
  zoneIds.map((zoneId) => ({ zoneId, basis: 'FLAT', rows: [blankRow('FLAT', { price: 0 })] }));

/**
 * Blocks and the stash of the rule rows after the rate source changed from `from` to `to`.
 * Entering `CARRIER` keeps only the zones (FLAT, price 0) and stashes the rule rows; leaving it restores
 * the stash, or - when there is none - restarts every zone with an EMPTY price: the price-0 rows of a
 * CARRIER method must never turn into a free rule price by themselves.
 */
export function switchRateSource(blocks, from, to, stash = null) {
  if (from === to) return { blocks, stash };
  if (to === 'CARRIER')
    return { blocks: carrierBlocks(blocks.map((b) => b.zoneId)), stash: blocks };
  if (from === 'CARRIER') {
    const zoneIds = new Set(blocks.map((b) => b.zoneId));
    const restored = (stash ?? [])
      .filter((b) => zoneIds.has(b.zoneId))
      .concat(
        blocks
          .filter((b) => !(stash ?? []).some((s) => s.zoneId === b.zoneId))
          .map((b) => blankBlock(b.zoneId)),
      );
    return { blocks: restored, stash: null };
  }
  return { blocks, stash };
}

/** Number of distinct zones of a method's `rates[]` (the list's Zones column). */
export const zoneCount = (rates) => new Set((rates ?? []).map((r) => r.zoneId)).size;

/** Total rows over all blocks. */
export const rowCount = (blocks) =>
  (blocks ?? []).reduce((sum, b) => sum + (b.rows?.length ?? 0), 0);

// ---------------------------------------------------------------------------------------------
// Zones (13 §19.1, 10 §4.2)
// ---------------------------------------------------------------------------------------------

const PREFIX_PATTERN = /^[A-Za-z0-9]{1,10}\*$/;
const RANGE_PATTERN = /^(\d{1,10})-(\d{1,10})$/;
const EXACT_PATTERN = /^[A-Za-z0-9]{1,16}$/;

/**
 * Postal pattern of 10 §4.2: a prefix `34*`, a numeric range `1000-1999` (same length, from <= to) or
 * an exact code. (13 §19.1 also lists space and dash in the prefix; the server rule is the stricter one.)
 */
export function isPostalPattern(text) {
  const value = String(text ?? '');
  if (PREFIX_PATTERN.test(value) || EXACT_PATTERN.test(value)) return true;
  const range = RANGE_PATTERN.exec(value);
  return (
    range !== null && range[1].length === range[2].length && Number(range[1]) <= Number(range[2])
  );
}

/** A state name: 1 .. 64 characters (13 §19.1; the server accepts up to 100). */
export const isStateName = (text) => {
  const value = String(text ?? '').trim();
  return value.length >= 1 && value.length <= MAX_STATE_LENGTH;
};

export const isEverywhere = (zone) =>
  Array.isArray(zone?.countries) && zone.countries.includes('*');

/** `{ everywhere, shown, more }` for the Countries column: first 5 codes and `+N`. */
export function countrySummary(zone) {
  if (isEverywhere(zone)) return { everywhere: true, shown: [], more: 0 };
  const countries = Array.isArray(zone?.countries) ? zone.countries : [];
  return {
    everywhere: false,
    shown: countries.slice(0, COUNTRIES_PREVIEW),
    more: Math.max(0, countries.length - COUNTRIES_PREVIEW),
  };
}

/** Counts shown in the list: regions = states over all entries? No: number of region entries (13 §19.1). */
export const regionCount = (zone) => (Array.isArray(zone?.regions) ? zone.regions.length : 0);
export const postalCount = (zone) =>
  Array.isArray(zone?.postalPatterns) ? zone.postalPatterns.length : 0;

export function blankZoneForm() {
  return {
    name: '',
    everywhere: false,
    countries: [],
    regions: {},
    postalPatterns: [],
    active: true,
  };
}

export function zoneToForm(zone) {
  if (!zone) return blankZoneForm();
  const everywhere = isEverywhere(zone);
  const regions = {};
  for (const entry of Array.isArray(zone.regions) ? zone.regions : [])
    if (entry?.country) regions[entry.country] = [...(entry.states ?? [])];
  return {
    name: zone.name ?? '',
    everywhere,
    countries: everywhere ? [] : [...(zone.countries ?? [])],
    regions,
    postalPatterns: [...(zone.postalPatterns ?? [])],
    active: zone.status !== 'INACTIVE',
  };
}

/**
 * The region editor is offered only for 1 .. 5 selected countries and never with "Everywhere Else". This
 * only decides whether the editor is SHOWN: the stored regions of a zone are sent whenever the zone has
 * countries (`buildZoneBody`), otherwise saving a zone of 6+ countries would silently drop its state
 * restrictions and change which zone matches a buyer.
 */
export const regionsEditable = (form) =>
  !form.everywhere && form.countries.length >= 1 && form.countries.length <= MAX_REGION_COUNTRIES;

/**
 * Client validation of the zone modal: `{ name, countries, regions, postalPatterns }` -> code. `zones` is
 * the loaded list, `id` the zone being edited (excluded from the one-"Everywhere Else" check).
 */
export function validateZone(form, { zones = [], id = null } = {}) {
  const errors = {};
  const name = String(form.name ?? '').trim();
  if (name === '') errors.name = 'REQUIRED';
  else if (name.length > MAX_ZONE_NAME) errors.name = 'TOO_LONG';

  if (form.everywhere) {
    if (zones.some((z) => z.id !== id && isEverywhere(z))) errors.countries = 'EVERYWHERE_EXISTS';
  } else if (form.countries.length === 0) errors.countries = 'COUNTRY_REQUIRED';

  // Regions are validated whenever they are sent (every selected country). While the editor is hidden
  // (more than 5 countries) the stored states cannot be fixed by hand, so the server limits apply
  // (200 states of 1 .. 100 characters, 10 §4.2) instead of the stricter editor limits.
  if (!form.everywhere) {
    const editable = regionsEditable(form);
    const maxStates = editable ? MAX_STATES : MAX_SERVER_STATES;
    const maxLength = editable ? MAX_STATE_LENGTH : MAX_SERVER_STATE_LENGTH;
    for (const country of form.countries) {
      const states = form.regions[country] ?? [];
      const bad = (s) => {
        const length = String(s ?? '').trim().length;
        return length < 1 || length > maxLength;
      };
      if (states.length > maxStates || states.some(bad)) {
        errors.regions = states.length > maxStates ? 'TOO_MANY' : 'INVALID';
        break;
      }
    }
  }

  const patterns = form.postalPatterns ?? [];
  if (patterns.length > MAX_POSTAL_PATTERNS) errors.postalPatterns = 'TOO_MANY';
  else if (patterns.some((p) => !isPostalPattern(p))) errors.postalPatterns = 'INVALID';

  return errors;
}

/**
 * True when the region editor is hidden (more than 5 countries) but some selected country still has stored
 * states: they are kept on save and the modal says so with a read-only note.
 */
export const hasHiddenRegions = (form) =>
  !form.everywhere &&
  !regionsEditable(form) &&
  form.countries.some((country) => (form.regions[country] ?? []).length > 0);

/** Request body of POST / PUT `/shipping/zones`. Regions follow the selected countries, not the editor. */
export function buildZoneBody(form) {
  const countries = form.everywhere ? ['*'] : [...new Set(form.countries)];
  const regions = [];
  if (!form.everywhere)
    for (const country of countries) {
      const states = (form.regions[country] ?? [])
        .map((s) => String(s).trim())
        .filter((s) => s !== '');
      if (states.length > 0) regions.push({ country, states: [...new Set(states)] });
    }
  return {
    name: String(form.name).trim(),
    countries,
    regions,
    postalPatterns: [...new Set(form.postalPatterns)],
    status: form.active ? 'ACTIVE' : 'INACTIVE',
  };
}

/** Codes the zone modal and the method page have a text for; any other server code reads as `INVALID`. */
export const FIELD_ERROR_CODES = [
  'REQUIRED',
  'TOO_LONG',
  'TOO_MANY',
  'INVALID',
  'OUT_OF_RANGE',
  'COUNTRY_REQUIRED',
  'EVERYWHERE_EXISTS',
  'MIN_GT_MAX',
  'INVALID_TEMPLATE',
  'CARRIER_QUOTE_UNSUPPORTED',
];
const knownCode = (value) => (FIELD_ERROR_CODES.includes(value) ? value : 'INVALID');

/** `INVALID_SETTINGS.fieldErrors` of a zone request -> `{ field: code }` for the zone modal. */
export function zoneServerErrors(fieldErrors) {
  const out = {};
  for (const [key, value] of Object.entries(fieldErrors ?? {})) {
    const field = key.split(/[.[]/)[0];
    const target = ['name', 'countries', 'regions', 'postalPatterns'].includes(field)
      ? field
      : null;
    if (target && !(target in out)) out[target] = knownCode(value);
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Methods (13 §19.2, 10 §5.1)
// ---------------------------------------------------------------------------------------------

export function blankMethodForm() {
  return {
    name: '',
    description: '',
    providerId: MANUAL_PROVIDER,
    serviceCode: '',
    rateSource: 'RULES',
    freeShippingThreshold: null,
    handlingFee: 0,
    vatDefault: true,
    vatPercent: null,
    minDeliveryDays: '',
    maxDeliveryDays: '',
    maxWeightGrams: '',
    carrierName: '',
    trackingUrlTemplate: '',
    active: true,
  };
}

const text = (value) => (value === null || value === undefined ? '' : String(value));

export function methodToForm(method) {
  if (!method) return blankMethodForm();
  const vat = method.vatPercent;
  return {
    name: text(method.name),
    description: text(method.description),
    providerId: method.providerId || MANUAL_PROVIDER,
    serviceCode: text(method.serviceCode),
    rateSource: RATE_SOURCES.includes(method.rateSource) ? method.rateSource : 'RULES',
    freeShippingThreshold:
      method.freeShippingThreshold === null || method.freeShippingThreshold === undefined
        ? null
        : Number(method.freeShippingThreshold),
    handlingFee:
      method.handlingFee === null || method.handlingFee === undefined
        ? 0
        : Number(method.handlingFee),
    vatDefault: vat === null || vat === undefined,
    vatPercent: vat === null || vat === undefined ? null : Number(vat),
    minDeliveryDays: text(method.minDeliveryDays),
    maxDeliveryDays: text(method.maxDeliveryDays),
    maxWeightGrams: text(method.maxWeightGrams),
    carrierName: text(method.carrierName),
    trackingUrlTemplate: text(method.trackingUrlTemplate),
    active: method.status !== 'INACTIVE',
  };
}

/** The carrier row of a provider id (`manual` is always there). */
export const findCarrier = (carriers, id) => (carriers ?? []).find((c) => c.id === id) ?? null;

/** Carrier rate sources need a provider that can quote live rates (10 §5.1); `manual` never can. */
export function canQuote(provider) {
  return (
    provider !== null &&
    provider !== undefined &&
    provider.id !== MANUAL_PROVIDER &&
    provider.capabilities?.rateQuote === true
  );
}

/** True when the rate source can be chosen for the provider. `RULES` always can. */
export function rateSourceAllowed(source, provider) {
  return source === 'RULES' || canQuote(provider);
}

/**
 * Carrier options of the provider select: enabled ones first (market's own `manual` leading), then the
 * rest; the provider a stored method already uses stays in the list even when the registry lost it.
 */
export function providerOptions(carriers, selectedId, nameOf = (c) => c.id) {
  const rows = (carriers ?? []).map((c) => ({
    id: c.id,
    name: nameOf(c),
    enabled: c.config?.enabled !== false && c.state !== 'UNAVAILABLE' && c.state !== 'INCOMPATIBLE',
    manual: c.id === MANUAL_PROVIDER,
  }));
  if (selectedId && !rows.some((r) => r.id === selectedId))
    rows.push({ id: selectedId, name: selectedId, enabled: false, manual: false });
  const rank = (r) => (r.manual ? 0 : r.enabled ? 1 : 2);
  return rows.sort((a, b) => rank(a) - rank(b) || a.name.localeCompare(b.name));
}

/**
 * Form after choosing a provider: the service code is cleared, and a rate source the provider cannot
 * serve falls back to `RULES`.
 */
export function withProvider(form, providerId, carriers) {
  const provider = findCarrier(carriers, providerId);
  return {
    ...form,
    providerId,
    serviceCode: '',
    rateSource: rateSourceAllowed(form.rateSource, provider) ? form.rateSource : 'RULES',
  };
}

/** `https://…/{tracking}`: http(s) and contains the `{tracking}` placeholder. */
export function isTrackingTemplate(value) {
  const v = String(value ?? '').trim();
  return /^https?:\/\//i.test(v) && v.includes('{tracking}');
}

function numberError(value, { min = 0, max = Infinity, exclusiveMin = false } = {}) {
  if (isMissing(value)) return null;
  if (!isNum(value)) return 'INVALID';
  if (exclusiveMin ? value <= min : value < min) return 'OUT_OF_RANGE';
  if (value > max) return 'OUT_OF_RANGE';
  return null;
}

/** Digits text -> Number | null | NaN, bounded. */
export const parseDays = (value) => parseInteger(value, { min: 0, max: MAX_DELIVERY_DAYS });
export const parseWeight = (value) => parseInteger(value, { min: 1, max: MAX_WEIGHT_GRAMS });

/** A provider row that is missing from the list or whose plugin is stopped / incompatible (10 §5.1). */
export const providerGone = (provider) =>
  provider === null ||
  provider === undefined ||
  provider.state === 'UNAVAILABLE' ||
  provider.state === 'INCOMPATIBLE';

/**
 * The stored rate source stays valid while its provider is gone: "provider unavailable => accepted as
 * stored, method is simply not offered" (10 §5.1). Only CHOOSING a carrier source newly is blocked.
 */
export function keepsStoredSource(
  source,
  provider,
  storedRateSource = null,
  { providerId = null, storedProviderId = null } = {},
) {
  if (storedRateSource === null || source !== storedRateSource) return false;
  // a provider chosen anew is not "stored": only the provider the method was saved with counts
  if (storedProviderId !== null && providerId !== storedProviderId) return false;
  return providerGone(provider);
}

/**
 * Client validation of the method fields (not the rates): `{ field: code }`. `provider` = the carrier row of
 * `form.providerId` (null when unknown). `storedRateSource` = the rate source the method was loaded with
 * (null for a new method) and `storedProviderId` its provider: while that provider is unavailable the stored
 * value is accepted as stored.
 */
export function validateMethod(
  form,
  { provider = null, storedRateSource = null, storedProviderId = null } = {},
) {
  const errors = {};
  const manual = form.providerId === MANUAL_PROVIDER;
  const name = String(form.name ?? '').trim();
  if (name === '') errors.name = 'REQUIRED';
  else if (name.length > MAX_METHOD_NAME) errors.name = 'TOO_LONG';
  if (String(form.description ?? '').length > MAX_METHOD_DESCRIPTION)
    errors.description = 'TOO_LONG';
  if (!present(form.providerId)) errors.providerId = 'REQUIRED';
  if (!RATE_SOURCES.includes(form.rateSource)) errors.rateSource = 'INVALID';
  else if (
    !rateSourceAllowed(form.rateSource, manual ? findManualStub() : provider) &&
    !(
      !manual &&
      keepsStoredSource(form.rateSource, provider, storedRateSource, {
        providerId: form.providerId,
        storedProviderId,
      })
    )
  )
    errors.rateSource = 'CARRIER_QUOTE_UNSUPPORTED';
  if (!manual && String(form.serviceCode ?? '').length > MAX_SERVICE_CODE)
    errors.serviceCode = 'TOO_LONG';

  const threshold = numberError(form.freeShippingThreshold, { min: 0, exclusiveMin: true });
  if (threshold) errors.freeShippingThreshold = threshold;
  const fee = numberError(form.handlingFee, { min: 0 });
  if (fee) errors.handlingFee = fee;
  if (!form.vatDefault) {
    if (isMissing(form.vatPercent)) errors.vatPercent = 'REQUIRED';
    else {
      const vat = numberError(form.vatPercent, { min: 0, max: 100 });
      if (vat) errors.vatPercent = vat;
    }
  }

  const min = parseDays(form.minDeliveryDays);
  const max = parseDays(form.maxDeliveryDays);
  if (Number.isNaN(min)) errors.minDeliveryDays = 'OUT_OF_RANGE';
  if (Number.isNaN(max)) errors.maxDeliveryDays = 'OUT_OF_RANGE';
  if (
    !errors.minDeliveryDays &&
    !errors.maxDeliveryDays &&
    min !== null &&
    max !== null &&
    min > max
  )
    errors.maxDeliveryDays = 'MIN_GT_MAX';

  if (Number.isNaN(parseWeight(form.maxWeightGrams))) errors.maxWeightGrams = 'OUT_OF_RANGE';

  if (manual) {
    if (String(form.carrierName ?? '').length > MAX_CARRIER_NAME) errors.carrierName = 'TOO_LONG';
    const template = String(form.trackingUrlTemplate ?? '').trim();
    if (template !== '') {
      if (template.length > MAX_TRACKING_TEMPLATE) errors.trackingUrlTemplate = 'TOO_LONG';
      else if (!isTrackingTemplate(template)) errors.trackingUrlTemplate = 'INVALID_TEMPLATE';
    }
  }
  return errors;
}

// `validateMethod` treats the always-present manual provider as unable to quote without needing its row.
function findManualStub() {
  return { id: MANUAL_PROVIDER, capabilities: {} };
}

const intOrNull = (value) => {
  const parsed = parseInteger(value);
  return parsed === null || Number.isNaN(parsed) ? null : parsed;
};

/** Request body of POST / PUT `/shipping/methods` from a valid form and its blocks. */
export function buildMethodBody(form, blocks) {
  const manual = form.providerId === MANUAL_PROVIDER;
  const trim = (value) => String(value ?? '').trim();
  return {
    name: trim(form.name),
    description: trim(form.description) === '' ? null : trim(form.description),
    providerId: form.providerId,
    serviceCode: !manual && trim(form.serviceCode) !== '' ? trim(form.serviceCode) : null,
    rateSource: form.rateSource,
    freeShippingThreshold: isNum(form.freeShippingThreshold) ? form.freeShippingThreshold : null,
    handlingFee: isNum(form.handlingFee) ? form.handlingFee : 0,
    vatPercent: form.vatDefault || !isNum(form.vatPercent) ? null : form.vatPercent,
    minDeliveryDays: intOrNull(form.minDeliveryDays),
    maxDeliveryDays: intOrNull(form.maxDeliveryDays),
    maxWeightGrams: intOrNull(form.maxWeightGrams),
    carrierName: manual && trim(form.carrierName) !== '' ? trim(form.carrierName) : null,
    trackingUrlTemplate:
      manual && trim(form.trackingUrlTemplate) !== '' ? trim(form.trackingUrlTemplate) : null,
    status: form.active ? 'ACTIVE' : 'INACTIVE',
    rates: buildRatesPayload(blocks, form.rateSource),
  };
}

/**
 * Server `INVALID_SETTINGS.fieldErrors` of a method request -> `{ fields: { field: code }, rates: code|null }`.
 * Keys starting with `rates` (e.g. `rates[2].rangeFrom`) collapse into `rates`; the codes
 * `RATE_OVERLAP` / `RATE_UNREACHABLE` are kept, anything else on a rate key is `INVALID`.
 */
export function methodServerErrors(fieldErrors) {
  const fields = {};
  let rates = null;
  for (const [key, value] of Object.entries(fieldErrors ?? {})) {
    const code = typeof value === 'string' ? value : 'INVALID';
    if (key === 'rates' || key.startsWith('rates[') || key.startsWith('rates.')) {
      rates ??= code === 'RATE_OVERLAP' || code === 'RATE_UNREACHABLE' ? code : 'INVALID';
    } else if (!(key in fields)) fields[key] = knownCode(code);
  }
  return { fields, rates };
}

// ---------------------------------------------------------------------------------------------
// Carriers (13 §19.3)
// ---------------------------------------------------------------------------------------------

/** Row behaviour of a carrier card: the provider rules of 13 §16.1, and `manual` can never be switched off. */
export function carrierBehavior(provider) {
  const base = rowBehavior(provider);
  if (provider?.id !== MANUAL_PROVIDER) return base;
  return { ...base, switchDisabled: true, switchHint: null, manualLocked: true };
}

/** `{ amount, currency }` of a carrier balance when it is usable, else null (the row is hidden). */
export function carrierBalance(provider) {
  const balance = provider?.balance;
  if (!balance || !isNum(Number(balance.amount)) || !present(balance.currency)) return null;
  return { amount: Number(balance.amount), currency: String(balance.currency) };
}

/** Carriers sorted: enabled first, then by name, `manual` first overall. */
export function sortCarriers(carriers, nameOf = (c) => c.id) {
  const rank = (c) => (c.id === MANUAL_PROVIDER ? 0 : c.config?.enabled ? 1 : 2);
  return [...(carriers ?? [])].sort(
    (a, b) =>
      rank(a) - rank(b) ||
      nameOf(a).localeCompare(nameOf(b)) ||
      String(a.id).localeCompare(String(b.id)),
  );
}

/** True when there is no carrier plugin besides market's own `manual` provider. */
export const onlyManualCarrier = (carriers) =>
  (carriers ?? []).every((c) => c.id === MANUAL_PROVIDER);
