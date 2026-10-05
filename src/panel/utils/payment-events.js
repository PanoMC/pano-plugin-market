// Pure rules of the payment events page and PaymentEventModal (13 §15, 04 §7 payment-events). No Svelte, no SDK import.
import { can } from './permissions.js';

/** URL names forwarded to GET /payment-events (page is handled by loadList). */
export const EVENT_PARAMS = ['status', 'providerId'];

export const STATUS_TABS = [
  { key: 'all', value: null },
  { key: 'deferred', value: 'DEFERRED' },
  { key: 'failed', value: 'FAILED' },
  { key: 'rejected', value: 'REJECTED' },
];

/** Statuses a stored request can be replayed from. */
export const REPLAYABLE_STATUSES = ['DEFERRED', 'FAILED'];

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';

export function normalizeFilters(filters = {}) {
  const out = {};
  for (const name of EVENT_PARAMS)
    out[name] = present(filters?.[name]) ? String(filters[name]) : '';
  return out;
}

export function activeTab(status) {
  if (!present(status)) return 'all';
  return STATUS_TABS.find((tab) => tab.value === status)?.key ?? null;
}

export function listParams(filters, overrides = {}) {
  const merged = { ...normalizeFilters(filters), ...overrides };
  return Object.fromEntries(Object.entries(merged).filter(([, value]) => present(value)));
}

/** Replay needs PAY and a DEFERRED / FAILED row. */
export const canReplay = (event, user) =>
  can(user, 'PAY') && REPLAYABLE_STATUSES.includes(event?.status);

/** Raw bodies and headers are only returned (and shown) with SET. */
export const canSeeBodies = (user) => can(user, 'SET');

/** `verified === false` (not null / undefined) is the "Unverified" badge. */
export const isUnverified = (event) => event?.verified === false;

/** Event types arrive as an array or a csv string; shown as text. */
export function eventTypesText(event) {
  const types = event?.eventTypes;
  if (Array.isArray(types)) return types.join(', ');
  return present(types) ? String(types) : '';
}

/** Rows of the PaymentEventModal definition list (value null = shown as a dash); bodies are separate. */
export function detailRows(event) {
  return [
    ['provider', event?.providerId ?? null],
    ['direction', event?.direction ?? null],
    ['channel', event?.channel ?? null],
    ['event-key', event?.eventKey ?? null],
    ['event-types', eventTypesText(event) || null],
    ['http', event?.responseStatus ?? null],
    ['error', event?.error ?? null],
    ['ip', event?.remoteIp ?? null],
  ];
}

/** The body / headers value as the text of a <pre>; objects are pretty-printed. */
export function prettyText(value) {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value === 'string') {
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}
