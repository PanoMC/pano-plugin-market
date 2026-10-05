// Pure state helpers of the orders list (13 §5): URL filters, status tabs, row actions, CSV export.
// No Svelte and no SDK import, so everything here is unit tested.
import { can } from '../../utils/permissions.js';

/** URL names forwarded to GET /orders (page is handled by loadList). */
export const ORDER_PARAMS = [
  'search',
  'status',
  'paymentMethodId',
  'fulfillmentStatus',
  'shippingStatus',
  'from',
  'to',
  'testMode',
  'source',
];

/** The filters the modal edits (everything but search and the status tabs). */
export const MODAL_FILTERS = [
  'paymentMethodId',
  'fulfillmentStatus',
  'shippingStatus',
  'source',
  'testMode',
  'from',
  'to',
];

/** Status tabs of the card header: `value` is the csv sent as `status`; null = all. */
export const STATUS_TABS = [
  { key: 'all', value: null },
  { key: 'pending', value: 'PENDING' },
  { key: 'review', value: 'REVIEW' },
  { key: 'completed', value: 'COMPLETED,PARTIALLY_REFUNDED' },
  { key: 'refunded', value: 'REFUNDED' },
  { key: 'failed', value: 'FAILED,CANCELLED,EXPIRED' },
  { key: 'chargeback', value: 'CHARGEBACK' },
];

export const FULFILLMENT_VALUES = ['NONE', 'PENDING', 'PARTIAL', 'FULFILLED', 'FAILED', 'REVOKED'];
export const SHIPPING_VALUES = [
  'NOT_REQUIRED',
  'PENDING',
  'PARTIAL',
  'SHIPPED',
  'DELIVERED',
  'RETURNED',
];
export const SOURCE_VALUES = ['STOREFRONT', 'PANEL', 'GIFT_CODE', 'RENEWAL', 'EXTERNAL'];
export const TEST_MODE_VALUES = ['all', 'false', 'true'];

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';

/** Drops empty values; the rest stay strings or numbers as given. */
export function compact(params) {
  return Object.fromEntries(Object.entries(params).filter(([, value]) => present(value)));
}

/** Filters from the load() data (`data.filters`, values are URL strings or null). */
export function normalizeFilters(filters = {}) {
  const out = {};
  for (const name of ORDER_PARAMS)
    out[name] = present(filters?.[name]) ? String(filters[name]) : '';
  if (out.testMode !== 'true' && out.testMode !== 'false') out.testMode = '';
  return out;
}

/** Key of the status tab matching the `status` filter (csv order is not significant). */
export function activeTab(status) {
  if (!present(status)) return 'all';
  const wanted = String(status).split(',').filter(Boolean).sort().join(',');
  const tab = STATUS_TABS.find(
    (t) => t.value !== null && t.value.split(',').sort().join(',') === wanted,
  );
  return tab?.key ?? null;
}

/**
 * Modal filters in use. `shippingEnabled` false hides the shipping filter, so it never counts as
 * active (and is dropped from the query by `listParams`).
 */
export function activeModalFilters(filters, { shippingEnabled = false } = {}) {
  const f = normalizeFilters(filters);
  return MODAL_FILTERS.filter(
    (name) => present(f[name]) && (name !== 'shippingStatus' || shippingEnabled),
  );
}

/**
 * Query of the list URL (no `page`: a filter or search change always goes back to page 1).
 * `overrides` replace single filters; '' / null removes one.
 */
export function listParams(filters, overrides = {}) {
  return compact({ ...normalizeFilters(filters), ...overrides });
}

/** Query of the CSV export: the current filters (no page) + the chosen columns and delimiter. */
export function exportParams(filters, columns, delimiter) {
  return compact({
    ...normalizeFilters(filters),
    columns: columns.join(','),
    delimiter: delimiter && delimiter !== ',' ? delimiter : null,
  });
}

/** Absolute-path URL of the export download. */
export function exportUrl(base, filters, columns, delimiter, buildQueryParams) {
  return `${base}/api/panel/market/orders/export${buildQueryParams(
    exportParams(filters, columns, delimiter),
  )}`;
}

// 04 §7 column keys. `pii` columns are selectable only with OM / PAY (403 otherwise).
export const EXPORT_COLUMNS = [
  { key: 'orderId' },
  { key: 'publicId' },
  { key: 'createdAt' },
  { key: 'paidAt' },
  { key: 'status' },
  { key: 'source' },
  { key: 'playerUsername' },
  { key: 'recipientUsername' },
  { key: 'email', pii: true },
  { key: 'productName' },
  { key: 'variantName' },
  { key: 'sku' },
  { key: 'quantity' },
  { key: 'unitPrice' },
  { key: 'lineTotal' },
  { key: 'currency' },
  { key: 'orderTotal' },
  { key: 'couponCode' },
  { key: 'creatorCode' },
  { key: 'paymentMethod' },
  { key: 'gatewayTransactionId' },
  { key: 'gatewayAmount' },
  { key: 'creditValue' },
  { key: 'refundedTotal' },
  { key: 'fulfillmentStatus' },
  { key: 'shippingStatus' },
  { key: 'country', pii: true },
  { key: 'testMode' },
];

export const DEFAULT_EXPORT_COLUMNS = [
  'orderId',
  'createdAt',
  'status',
  'playerUsername',
  'productName',
  'variantName',
  'quantity',
  'lineTotal',
  'currency',
  'orderTotal',
  'paymentMethod',
  'fulfillmentStatus',
];

export const DELIMITERS = [',', ';', 'tab'];

/** PII columns are for OM / PAY holders only (admins and the umbrella node pass `can`). */
export const canExportPii = (user) => can(user, 'OM', 'PAY');

/** Columns the user may request. */
export function allowedExportColumns(user) {
  const pii = canExportPii(user);
  return EXPORT_COLUMNS.filter((c) => !c.pii || pii).map((c) => c.key);
}

/** Checked columns reduced to what the user may request, in table order. */
export function sanitizeColumns(checked, user) {
  const allowed = new Set(allowedExportColumns(user));
  const wanted = new Set(checked);
  return EXPORT_COLUMNS.map((c) => c.key).filter((k) => wanted.has(k) && allowed.has(k));
}

/** Select options of the payment method filter from GET /payment-providers rows. */
export function paymentOptions(providers) {
  const seen = new Map();
  for (const p of Array.isArray(providers) ? providers : []) {
    if (!p || p.id === undefined || p.id === null || seen.has(String(p.id))) continue;
    const label = p.config?.customLabel || p.descriptor?.name || String(p.id);
    seen.set(String(p.id), label);
  }
  return [...seen].map(([id, label]) => ({ id, label }));
}

/** `Product (Variant)` of one order item. */
export function itemName(item) {
  if (!item?.productName) return '';
  return item.variantName ? `${item.productName} (${item.variantName})` : item.productName;
}

/** Products cell: first item and how many more; `tooltip` = every item as text joined by ', '. */
export function productsCell(order) {
  const names = (order?.items ?? []).map(itemName).filter(Boolean);
  return {
    first: names[0] ?? '',
    more: Math.max(0, names.length - 1),
    tooltip: names.join(', '),
  };
}

/** Delivery / shipping cell: the hidden value shows a dash. */
export const hiddenStatus = (kind, value) =>
  !value ||
  (kind === 'fulfillment' && value === 'NONE') ||
  (kind === 'shipping' && value === 'NOT_REQUIRED');

/** Row dropdown: only the actions this user and order may use. */
export function rowActions(order, user) {
  const actions = ['view', 'copy'];
  if (can(user, 'OM') && ['FAILED', 'PARTIAL'].includes(order?.fulfillmentStatus)) {
    actions.push('rerun');
  }
  return actions;
}

/** Second line of the Total cell, or null. */
export const refundedLine = (order) =>
  Number(order?.refundedTotal) > 0 ? order.refundedTotal : null;
