// Pure rules of the shipment screens (13 §20, 10 §9): list tabs and row actions, CreateShipmentModal
// (lines bounded by the unshipped remainder, parcels, rates, request bodies), ShipmentModal edit form,
// the shipping-address form, cancel / release confirmations. No Svelte and no SDK import.
import { can } from './permissions.js';
import { PANEL_URL, errorKey, errorParams } from './api.js';
import { parseInteger } from './format.js';

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';
const clean = (value) => String(value ?? '').trim();
const req = (method, path, body = {}) => ({ method, path, body });

export const MANUAL_PROVIDER = 'manual';

// ---- list ---------------------------------------------------------------------------------------

/** URL names forwarded to GET /shipments (page is handled by loadList). */
export const SHIPMENT_PARAMS = ['search', 'status', 'providerId'];

/** `href` tabs are links to another list (To Ship lists orders, not shipments). */
export const STATUS_TABS = [
  { key: 'all', value: null },
  { key: 'to-ship', href: '/market/orders?shippingStatus=PENDING,PARTIAL' },
  { key: 'in-transit', value: 'CREATED,LABEL_READY,IN_TRANSIT,OUT_FOR_DELIVERY' },
  { key: 'delivered', value: 'DELIVERED' },
  { key: 'problems', value: 'EXCEPTION,RETURNING,RETURNED,LOST' },
];

export function normalizeFilters(filters = {}) {
  const out = {};
  for (const name of SHIPMENT_PARAMS)
    out[name] = present(filters?.[name]) ? String(filters[name]) : '';
  return out;
}

/** Key of the status tab matching `status` (csv order is not significant); null = none matches. */
export function activeTab(status) {
  if (!present(status)) return 'all';
  const wanted = String(status).split(',').filter(Boolean).sort().join(',');
  const tab = STATUS_TABS.find((t) => t.value && t.value.split(',').sort().join(',') === wanted);
  return tab?.key ?? null;
}

/** Query of the list URL (no `page`); '' / null in `overrides` removes a filter. */
export function listParams(filters, overrides = {}) {
  return Object.fromEntries(
    Object.entries({ ...normalizeFilters(filters), ...overrides }).filter(([, v]) => present(v)),
  );
}

export const CANCELLABLE_STATUSES = ['CREATED', 'LABEL_READY'];
export const RELEASABLE_STATUSES = ['RETURNED', 'LOST'];

/** True when the shipment creation failed and a retry is possible (13 §20.1). */
export const canRetry = (shipment) =>
  shipment?.entryMode !== 'MANUAL' &&
  shipment?.status === 'CREATED' &&
  Boolean(shipment?.lastErrorCode || shipment?.lastError);

export const canCancel = (shipment) => CANCELLABLE_STATUSES.includes(shipment?.status);

/** Returned / lost parcel whose items are not freed yet (10 §9.6). `itemsReleased` unknown = offered. */
export const canRelease = (shipment) =>
  RELEASABLE_STATUSES.includes(shipment?.status) && shipment?.itemsReleased !== true;

/**
 * Row actions in menu order. `view` needs only OV (the page itself); everything else needs OM.
 * `label` when a label file is stored, `generic-label` otherwise (never both).
 */
export function rowActions(shipment, user) {
  if (!shipment) return [];
  const out = ['view'];
  if (!can(user, 'OM')) return out;
  out.push(shipment.labelFile ? 'label' : 'generic-label', 'track');
  if (canRetry(shipment)) out.push('retry');
  if (canRelease(shipment)) out.push('release');
  if (canCancel(shipment)) out.push('cancel');
  return out;
}

/**
 * A label is only ever a download link opened in a new tab: never an iframe, img, embed or inline
 * object (a carrier PDF / ZPL is untrusted content).
 */
export const labelPath = (base, shipmentId, generic = false) =>
  `${PANEL_URL}/shipments/${shipmentId}/label${generic ? '?generic=true' : ''}`;

export const isHttpUrl = (value) => {
  if (!present(value)) return false;
  try {
    const url = new URL(String(value));
    return url.protocol === 'http:' || url.protocol === 'https:';
  } catch {
    return false;
  }
};

export const recipientName = (address) =>
  [address?.firstName, address?.lastName].filter(present).join(' ');

// ---- cancel / release / track / retry -----------------------------------------------------------

export const trackRequest = (id) => req('POST', `/shipments/${id}/track`);
export const retryRequest = (id) => req('POST', `/shipments/${id}/retry`);
export const cancelRequest = (id, force = false) =>
  req('POST', `/shipments/${id}/cancel`, force === true ? { force: true } : {});
export const releaseRequest = (id) => req('PUT', `/shipments/${id}`, { releaseItems: true });

/**
 * After a refused cancel: may the admin cancel anyway (`force`, local cancel only)? 10 §9.5: provider
 * unavailable, cancel unsupported by the carrier, or the carrier refused. Every other failure is final.
 */
export function offersForceCancel(error, body) {
  if (error === 'PROVIDER_UNAVAILABLE') return true;
  if (error === 'SHIPMENT_NOT_CANCELLABLE') return body?.reason === 'UNSUPPORTED';
  if (error === 'SHIPPING_PROVIDER_ERROR') return body?.code === 'GATEWAY_REJECTED';
  return false;
}

/**
 * Toast key + optional provider-error key for a failed call. A SHIPPING_PROVIDER_ERROR carries the
 * ProviderErrorCode in `code`; the text of `enums.provider-error.<code>` is appended by the caller
 * when `known(code)` says it exists. Never API text.
 */
export function providerErrorCode(error, body) {
  return error === 'SHIPPING_PROVIDER_ERROR' && present(body?.code) ? String(body.code) : null;
}

/**
 * Toast text of a failed shipment call (`$_` = plugin i18n). `errors.<CODE>` plus, for a
 * SHIPPING_PROVIDER_ERROR, the text of `enums.provider-error.<code>` when that code is known.
 * Never API text.
 */
export function failureText($_, error, body) {
  const base = $_(errorKey(error), errorParams(error, body));
  const code = providerErrorCode(error, body);
  if (!code) return base;
  const key = `enums.provider-error.${code}`;
  const detail = $_(key);
  if (!detail || detail === key || detail === `plugins.pano-plugin-market.${key}`) return base;
  return `${base} ${detail}`;
}

// ---- CreateShipmentModal ------------------------------------------------------------------------

export const NOTE_MAX = 255;
export const CARRIER_MAX = 128;
export const TRACKING_MAX = 128;
export const URL_MAX = 1024;
export const MAX_PARCELS = 20;
export const MAX_PARCEL_WEIGHT = 1_000_000_000;
export const MAX_DIMENSION = 5000;
const TRACKING_PATTERN = /^[A-Za-z0-9 ._/-]+$/;

/** Units still shippable on a line: 10 §9.2 `shippable`, else quantity - refunded - shipped. */
export function shippableOf(line) {
  if (Number.isInteger(line?.shippable)) return Math.max(0, line.shippable);
  const quantity = Number(line?.quantity ?? 0);
  const refunded = Number(line?.refundedQuantity ?? 0);
  const shipped = Number(line?.shippedQuantity ?? 0);
  return Math.max(0, quantity - refunded - shipped);
}

/** One input row per line that still has units; the default is everything that is left. */
export function initialLines(lines) {
  return (lines ?? [])
    .map((line) => ({ line, remaining: shippableOf(line) }))
    .filter(({ remaining }) => remaining > 0)
    .map(({ line, remaining }) => ({
      orderItemId: line.orderItemId,
      name: line.name ?? '',
      variantName: line.variantName ?? null,
      sku: line.sku ?? null,
      remaining,
      quantity: String(remaining),
      weightGrams: line.weightGrams ?? null,
    }));
}

/** Typed quantity -> integer in 0..remaining (an over-large value is bounded, junk becomes 0). */
export function clampQuantity(value, remaining) {
  const text = clean(value);
  if (text === '' || !/^\d+$/.test(text)) return 0;
  const n = Number(text);
  if (!Number.isSafeInteger(n)) return remaining;
  return Math.min(Math.max(0, n), Math.max(0, remaining));
}

/** Lines with a quantity > 0, as `[{ orderItemId, quantity }]`; quantities are bounded by the remainder. */
export function selectedItems(lines) {
  return (lines ?? [])
    .map((l) => ({ orderItemId: l.orderItemId, quantity: clampQuantity(l.quantity, l.remaining) }))
    .filter((l) => l.quantity > 0);
}

/** Form rows of the parcel list from `suggestedParcels[]`; at least one empty row when nothing is suggested. */
export function initialParcels(suggested) {
  const rows = (Array.isArray(suggested) ? suggested : []).map((p) => ({
    weightGrams: p?.weightGrams == null ? '' : String(p.weightGrams),
    lengthMm: p?.lengthMm == null ? '' : String(p.lengthMm),
    widthMm: p?.widthMm == null ? '' : String(p.widthMm),
    heightMm: p?.heightMm == null ? '' : String(p.heightMm),
  }));
  return rows.length > 0 ? rows : [emptyParcel()];
}

export const emptyParcel = () => ({ weightGrams: '', lengthMm: '', widthMm: '', heightMm: '' });

/**
 * `{ parcels }` or `{ error: { parcels: [{ weightGrams?, dimensions? }] | 'REQUIRED' | 'TOO_MANY' } }`.
 * weightGrams integer 1..1e9; dimensions all three or none (each 1..5000).
 */
export function validateParcels(
  rows,
  { maxParcels = MAX_PARCELS, requiresDimensions = false } = {},
) {
  if (!Array.isArray(rows) || rows.length === 0) return { error: { parcels: 'REQUIRED' } };
  if (rows.length > maxParcels) return { error: { parcels: 'TOO_MANY' } };
  const parcels = [];
  const problems = [];
  let failed = false;
  for (const row of rows) {
    const problem = {};
    const weight = parseInteger(row.weightGrams, { min: 1, max: MAX_PARCEL_WEIGHT });
    if (weight === null || Number.isNaN(weight)) problem.weightGrams = true;
    const dims = ['lengthMm', 'widthMm', 'heightMm'].map((k) =>
      parseInteger(row[k], { min: 1, max: MAX_DIMENSION }),
    );
    const given = dims.filter((d) => d !== null).length;
    const bad = dims.some((d) => Number.isNaN(d));
    if (bad || (given > 0 && given < 3) || (requiresDimensions && given === 0))
      problem.dimensions = true;
    if (problem.weightGrams || problem.dimensions) failed = true;
    problems.push(problem);
    if (!failed) {
      const parcel = { weightGrams: weight };
      if (given === 3)
        Object.assign(parcel, { lengthMm: dims[0], widthMm: dims[1], heightMm: dims[2] });
      parcels.push(parcel);
    }
  }
  return failed ? { error: { parcels: problems } } : { parcels };
}

export const isManual = (providerId) => providerId === MANUAL_PROVIDER;

/** Default provider: the quoted one when it is offered, else `manual`. */
export function defaultProviderId(quote, providers) {
  const quoted = quote?.providerId;
  if (present(quoted) && (providers ?? []).some((p) => p.id === quoted)) return quoted;
  return MANUAL_PROVIDER;
}

/** Providers of the select: `manual` always first, the rest as returned (never duplicated). */
export function providerChoices(providers) {
  return [{ id: MANUAL_PROVIDER }, ...(providers ?? []).filter((p) => p.id !== MANUAL_PROVIDER)];
}

/** Service code to preselect for a provider: the quoted one if the provider offers it. */
export function defaultServiceCode(quote, provider) {
  const code = quote?.serviceCode;
  return present(code) && (provider?.services ?? []).some((s) => s.code === code) ? code : '';
}

/** Rate shopping button: not for `manual`, nor for a provider that says it cannot quote. */
export const offersRates = (providerId, provider) =>
  !isManual(providerId) && provider?.capabilities?.rateQuote !== false;

/** The rate matching the quoted service is preselected. */
export function preselectRate(rates, quote) {
  if (!Array.isArray(rates) || !present(quote?.serviceCode)) return null;
  return rates.find((r) => r.serviceCode === quote.serviceCode) ?? null;
}

const rateKey = (rate) => `${rate?.serviceCode ?? ''}\u0000${rate?.rateRef ?? ''}`;
export { rateKey };

export function ratesRequest(orderId, { providerId, parcels, serviceCode = '' }) {
  const body = { providerId, parcels };
  if (present(serviceCode)) body.serviceCode = clean(serviceCode);
  return req('POST', `/orders/${orderId}/shipping/rates`, body);
}

/** Manual tracking entry: carrierName and trackingNumber required (<= 128, charset), trackingUrl http(s). */
export function validateManual(manual) {
  const error = {};
  const carrier = clean(manual?.carrierName);
  const tracking = clean(manual?.trackingNumber);
  const url = clean(manual?.trackingUrl);
  if (carrier === '' || carrier.length > CARRIER_MAX) error.carrierName = true;
  if (tracking === '' || tracking.length > TRACKING_MAX || !TRACKING_PATTERN.test(tracking))
    error.trackingNumber = true;
  if (url !== '' && (url.length > URL_MAX || !isHttpUrl(url))) error.trackingUrl = true;
  return error;
}

/**
 * `{ request }` or `{ error }` for POST /orders/:id/shipments.
 * form: { lines, parcels, providerId, provider, serviceCode, rate, rates, ratesFetched, ratesFailed,
 *         manual, note }
 * A carrier provider needs a chosen rate; providers without rate shopping (rates fetched, none
 * returned, no error) or that cannot quote at all submit with the service select only.
 */
export function createRequest(orderId, form) {
  const error = {};
  const items = selectedItems(form.lines);
  if (items.length === 0) error.items = true;
  const caps = form.provider?.capabilities ?? {};
  const checked = validateParcels(form.parcels, {
    maxParcels: isManual(form.providerId) ? MAX_PARCELS : caps.maxParcels || MAX_PARCELS,
    requiresDimensions: !isManual(form.providerId) && caps.requiresDimensions === true,
  });
  if (checked.error) Object.assign(error, checked.error);
  if (clean(form.note).length > NOTE_MAX) error.note = true;

  const body = { items, parcels: checked.parcels ?? [], providerId: form.providerId };
  if (isManual(form.providerId)) {
    Object.assign(error, validateManual(form.manual));
    const manual = {
      carrierName: clean(form.manual?.carrierName),
      trackingNumber: clean(form.manual?.trackingNumber),
    };
    if (clean(form.manual?.trackingUrl) !== '') manual.trackingUrl = clean(form.manual.trackingUrl);
    body.manual = manual;
  } else {
    const rate = form.rate ?? null;
    if (rate) {
      body.serviceCode = rate.serviceCode;
      if (present(rate.rateRef)) body.rateRef = rate.rateRef;
    } else {
      const rateless =
        !offersRates(form.providerId, form.provider) ||
        (form.ratesFetched === true &&
          form.ratesFailed !== true &&
          (form.rates ?? []).length === 0);
      if (!rateless) error.rate = true;
      if (present(form.serviceCode)) body.serviceCode = clean(form.serviceCode);
    }
  }
  if (clean(form.note) !== '') body.note = clean(form.note);
  if (Object.keys(error).length > 0) return { error };
  return { request: req('POST', `/orders/${orderId}/shipments`, body) };
}

/** Keys of `INVALID_SHIPMENT.fieldErrors` -> `{ field: 'KEY_CODE' }` for the locale lookup. */
export function shipmentFieldErrors(body) {
  const raw = body?.fieldErrors;
  if (!raw || typeof raw !== 'object') return {};
  return Object.fromEntries(
    Object.entries(raw)
      .filter(([, code]) => typeof code === 'string')
      .map(([field, code]) => [field, code]),
  );
}

// ---- ShipmentModal edit form --------------------------------------------------------------------

/** The ten ShipmentStatus values (13 §20.1); only SETTABLE_STATUSES can be applied by hand (10 §9.6). */
export const SHIPMENT_STATUSES = [
  'CREATED',
  'LABEL_READY',
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'EXCEPTION',
  'RETURNING',
  'RETURNED',
  'CANCELLED',
  'LOST',
];
export const SETTABLE_STATUSES = [
  'IN_TRANSIT',
  'OUT_FOR_DELIVERY',
  'EXCEPTION',
  'RETURNING',
  'RETURNED',
  'DELIVERED',
  'LOST',
];

/** Values the edit form can choose: the settable ones plus the current status (kept as "unchanged"). */
export function statusChoices(current) {
  return SETTABLE_STATUSES.includes(current) || !current
    ? SETTABLE_STATUSES
    : [current, ...SETTABLE_STATUSES];
}

/** A carrier-created shipment owns its tracking number (10 §9.6). */
export const trackingReadOnly = (shipment) =>
  shipment?.entryMode === 'CARRIER' && present(shipment?.carrierReference);

export const editable = (shipment) => shipment?.status !== 'CANCELLED';

export function initialEdit(shipment) {
  return {
    trackingNumber: shipment?.trackingNumber ?? '',
    trackingUrl: shipment?.trackingUrl ?? '',
    carrierName: shipment?.carrierName ?? '',
    status: shipment?.status ?? '',
  };
}

/**
 * `{ request }` / `{ error }` / `{ unchanged: true }` for PUT /shipments/:id. Only changed fields are
 * sent; an unchanged status is never re-applied (it would fail the transition check).
 */
export function updateRequest(shipment, form) {
  if (!shipment) return { error: { shipment: true } };
  const before = initialEdit(shipment);
  const error = {};
  const body = {};
  const tracking = clean(form.trackingNumber);
  const url = clean(form.trackingUrl);
  const carrier = clean(form.carrierName);
  if (tracking !== clean(before.trackingNumber) && !trackingReadOnly(shipment)) {
    if (tracking.length > TRACKING_MAX || (tracking !== '' && !TRACKING_PATTERN.test(tracking)))
      error.trackingNumber = true;
    else body.trackingNumber = tracking;
  }
  if (url !== clean(before.trackingUrl)) {
    if (url.length > URL_MAX || (url !== '' && !isHttpUrl(url))) error.trackingUrl = true;
    else body.trackingUrl = url;
  }
  if (carrier !== clean(before.carrierName)) {
    if (carrier.length > CARRIER_MAX) error.carrierName = true;
    else body.carrierName = carrier;
  }
  if (form.status && form.status !== before.status) {
    if (SETTABLE_STATUSES.includes(form.status)) body.status = form.status;
    else error.status = true;
  }
  if (Object.keys(error).length > 0) return { error };
  if (Object.keys(body).length === 0) return { unchanged: true };
  return { request: req('PUT', `/shipments/${shipment.id}`, body) };
}

// ---- shipping address form ----------------------------------------------------------------------

export const ADDRESS_FIELDS = [
  'firstName',
  'lastName',
  'company',
  'phone',
  'email',
  'country',
  'state',
  'city',
  'district',
  'neighborhood',
  'line1',
  'line2',
  'postalCode',
];
const ADDRESS_MAX = {
  firstName: 100,
  lastName: 100,
  company: 100,
  state: 100,
  city: 100,
  district: 100,
  neighborhood: 100,
  line1: 255,
  line2: 255,
  postalCode: 16,
  phone: 20,
  email: 255,
  country: 2,
};
const STATE_COUNTRIES = ['US', 'CA', 'AU', 'BR', 'MX', 'IN'];
const NO_POSTAL = [
  'AE',
  'HK',
  'MO',
  'QA',
  'PA',
  'BS',
  'JM',
  'FJ',
  'GH',
  'KE',
  'UG',
  'AO',
  'BW',
  'BZ',
  'ZW',
  'CI',
];

/** Required set of the base address (10 §3.3 without provider fields). */
export function requiredAddressFields(country) {
  const code = clean(country).toUpperCase();
  if (code === 'TR')
    return ['firstName', 'lastName', 'phone', 'country', 'city', 'district', 'line1'];
  let fields = ['firstName', 'lastName', 'phone', 'country', 'city', 'line1', 'postalCode'];
  if (STATE_COUNTRIES.includes(code)) fields = [...fields, 'state'];
  if (NO_POSTAL.includes(code)) fields = fields.filter((f) => f !== 'postalCode');
  return fields;
}

export function initialAddress(address) {
  return Object.fromEntries(
    ADDRESS_FIELDS.map((f) => [f, address?.[f] == null ? '' : String(address[f])]),
  );
}

/**
 * `{ request }` or `{ error: { field: true } }` for PUT /orders/:id/shipping-address. The server
 * normalises (trim, upper-case country, E.164 phone); the panel only checks presence and length.
 */
export function addressRequest(orderId, form) {
  const error = {};
  const body = {};
  for (const field of ADDRESS_FIELDS) {
    const value = clean(form?.[field]);
    if (value.length > ADDRESS_MAX[field]) error[field] = true;
    if (value !== '') body[field] = field === 'country' ? value.toUpperCase() : value;
  }
  for (const field of requiredAddressFields(body.country))
    if (!present(body[field])) error[field] = true;
  if (body.email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(body.email)) error.email = true;
  if (Object.keys(error).length > 0) return { error };
  return { request: req('PUT', `/orders/${orderId}/shipping-address`, body) };
}

/** `SHIPPING_ADDRESS_REQUIRED {fields}` -> `{ field: true }` for the address properties we know. */
export function addressFieldErrors(body) {
  const fields = Array.isArray(body?.fields) ? body.fields : [];
  return Object.fromEntries(fields.filter((f) => ADDRESS_FIELDS.includes(f)).map((f) => [f, true]));
}
