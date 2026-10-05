// Pure rules of the deliveries page (13 §9, 04 §7, 08 §14). No Svelte and no SDK import.
import { can } from './permissions.js';

/** URL names forwarded to GET /deliveries (page is handled by loadList). */
export const DELIVERY_PARAMS = ['search', 'status', 'phase', 'serverId', 'actionType'];

/** Filters the DeliveryFiltersModal edits. */
export const MODAL_FILTERS = ['serverId', 'actionType'];

/** SENT is "offered, waiting for the server", not an end state: it belongs to Waiting. */
export const WAITING_STATUSES = ['PENDING', 'SCHEDULED', 'WAITING_SERVER', 'SENT', 'QUEUED', 'SENDING'];

export const STATUS_TABS = [
  { key: 'all', value: null },
  { key: 'waiting', value: WAITING_STATUSES.join(',') },
  { key: 'failed', value: 'FAILED' },
  { key: 'done', value: 'CONFIRMED' },
];

export const PHASES = ['GRANT', 'RENEW', 'EXPIRE', 'REVOKE'];
export const ACTION_TYPES = ['CREDIT', 'PERMISSION', 'COMMAND', 'WEBHOOK'];

/** FAILED rows with these codes are never retried (the cause is not fixed by offering again). */
export const NOT_RETRYABLE_CODES = ['RENDER_ERROR', 'NO_TARGET_SERVER', 'SERVER_REMOVED', 'INVALID_PLAYER'];
export const CANCELLABLE_STATUSES = ['PENDING', 'SCHEDULED', 'WAITING_SERVER', 'SENT', 'QUEUED'];
/** The game server may have forgotten the key after 30 days (08 §14.2). */
export const RETRY_WINDOW_MS = 30 * 24 * 60 * 60 * 1000;

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';

export function compact(params) {
  return Object.fromEntries(Object.entries(params).filter(([, value]) => present(value)));
}

export function normalizeFilters(filters = {}) {
  const out = {};
  for (const name of DELIVERY_PARAMS) out[name] = present(filters?.[name]) ? String(filters[name]) : '';
  return out;
}

/** Key of the status tab matching `status` (csv order is not significant); null = no tab matches. */
export function activeTab(status) {
  if (!present(status)) return 'all';
  const wanted = String(status).split(',').filter(Boolean).sort().join(',');
  const tab = STATUS_TABS.find(
    (t) => t.value !== null && t.value.split(',').sort().join(',') === wanted,
  );
  return tab?.key ?? null;
}

export function activeModalFilters(filters) {
  const f = normalizeFilters(filters);
  return MODAL_FILTERS.filter((name) => present(f[name]));
}

/** Query of the list URL (no `page`); '' / null in `overrides` removes a filter. */
export function listParams(filters, overrides = {}) {
  return compact({ ...normalizeFilters(filters), ...overrides });
}

/** Retry is offered only where 04 §7 allows it. `now` is injected for tests. */
export function canRetry(delivery, now = Date.now()) {
  const status = delivery?.status;
  if (status === 'WAITING_SERVER' || status === 'SENT') return true;
  if (status !== 'FAILED') return false;
  if (NOT_RETRYABLE_CODES.includes(delivery.lastErrorCode)) return false;
  const sentAt = Number(delivery.sentAt);
  if (Number.isFinite(sentAt) && sentAt > 0 && sentAt < now - RETRY_WINDOW_MS) return false;
  return true;
}

export const canCancel = (delivery) => CANCELLABLE_STATUSES.includes(delivery?.status);

/** 'offer-again' for SENT (same key re-offered, the server de-duplicates), else 'retry'. */
export const retryKind = (delivery) => (delivery?.status === 'SENT' ? 'offer-again' : 'retry');

/** FAILED + UNKNOWN_OUTCOME: the action may have run on the game server. */
export const mayHaveRun = (delivery) =>
  delivery?.status === 'FAILED' && delivery?.lastErrorCode === 'UNKNOWN_OUTCOME';

/** SENT / QUEUED with a cancel request: the row waits for the server's answer. */
export const showCancelRequested = (delivery) =>
  delivery?.cancelRequested === true && ['SENT', 'QUEUED'].includes(delivery?.status);

/** Row dropdown items for this user and row (view is always there, retry / cancel need OM). */
export function rowActions(delivery, user, now = Date.now()) {
  const actions = [];
  if (can(user, 'OM')) {
    if (canRetry(delivery, now)) actions.push(retryKind(delivery));
    if (canCancel(delivery)) actions.push('cancel');
  }
  actions.push('view');
  return actions;
}

/** "When" cell: confirmedAt ?? sentAt ?? runAfter; a future runAfter is flagged as scheduled. */
export function whenCell(delivery, now = Date.now()) {
  const at = delivery?.confirmedAt ?? delivery?.sentAt ?? delivery?.runAfter ?? null;
  const scheduled =
    !delivery?.confirmedAt && !delivery?.sentAt && Number(delivery?.runAfter) > now;
  return { at, scheduled };
}
