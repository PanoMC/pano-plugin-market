// Pure rules of the block list and BlockModal (13 §13, 04 §7 blocks). No Svelte, no SDK import.
import { can } from './permissions.js';
import { isIpOrCidr } from './validate.js';

/** URL names forwarded to GET /blocks (page is handled by loadList). */
export const BLOCK_PARAMS = ['type', 'source', 'search'];

/** Types an admin may create; USER rows are created only by the system. */
export const BLOCK_TYPES = ['PLAYER', 'EMAIL', 'IP'];

/** Type tabs of the CardFilters (13 §13): key -> API value (null = all). */
export const TYPE_TABS = [
  { key: 'all', value: null },
  { key: 'players', value: 'PLAYER' },
  { key: 'emails', value: 'EMAIL' },
  { key: 'ips', value: 'IP' },
  { key: 'users', value: 'USER' },
];

export const SOURCES = ['MANUAL', 'CHARGEBACK'];

export const VALUE_MAX = 255;
export const REASON_MAX = 255;
export const PLAYER_PATTERN = /^[A-Za-z0-9_.*]{1,32}$/;

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';

export function normalizeFilters(filters = {}) {
  const out = {};
  for (const name of BLOCK_PARAMS)
    out[name] = present(filters?.[name]) ? String(filters[name]) : '';
  return out;
}

/** Key of the type tab matching `type`; null when no tab matches. */
export function activeTypeTab(type) {
  if (!present(type)) return 'all';
  return TYPE_TABS.find((tab) => tab.value === type)?.key ?? null;
}

/** Query of the list URL (no `page`); '' / null in `overrides` removes a filter. */
export function listParams(filters, overrides = {}) {
  const merged = { ...normalizeFilters(filters), ...overrides };
  return Object.fromEntries(Object.entries(merged).filter(([, value]) => present(value)));
}

/** Error code of a block value (`INVALID` / `TOO_LONG` / `REQUIRED`) or null. `value` is the raw text. */
export function valueError(type, value) {
  const text = String(value ?? '').trim();
  if (text === '') return 'REQUIRED';
  if (text.length > VALUE_MAX) return 'TOO_LONG';
  if (type === 'PLAYER') return PLAYER_PATTERN.test(text) ? null : 'INVALID';
  if (type === 'EMAIL') {
    const parts = text.split('@');
    return parts.length === 2 && parts[0] !== '' && parts[1] !== '' && !/\s/.test(text)
      ? null
      : 'INVALID';
  }
  if (type === 'IP') return isIpOrCidr(text) ? null : 'INVALID';
  return 'INVALID';
}

/**
 * Validates the form of BlockModal. `form` = { type, value, reason, expires (bool), expiresAt (epoch ms or null) }.
 * Returns { ok: true } or { ok: false, errors: { value?, reason?, expiresAt? } } (codes, never API text).
 */
export function validateBlock(form, now = Date.now()) {
  const errors = {};
  if (!BLOCK_TYPES.includes(form?.type)) errors.type = 'INVALID';
  else {
    const value = valueError(form.type, form.value);
    if (value) errors.value = value;
  }
  if (String(form?.reason ?? '').trim().length > REASON_MAX) errors.reason = 'TOO_LONG';
  if (form?.expires) {
    if (form.expiresAt === null || form.expiresAt === undefined || !Number.isFinite(form.expiresAt))
      errors.expiresAt = 'REQUIRED';
    else if (form.expiresAt <= now) errors.expiresAt = 'PAST';
  }
  return Object.keys(errors).length ? { ok: false, errors } : { ok: true };
}

/** Body of POST /blocks: the value is lower-cased; empty reason / no expiry are omitted. */
export function buildBlockBody(form) {
  const body = { type: form.type, value: String(form.value).trim().toLowerCase() };
  const reason = String(form.reason ?? '').trim();
  if (reason !== '') body.reason = reason;
  if (form.expires && Number.isFinite(form.expiresAt)) body.expiresAt = form.expiresAt;
  return body;
}

/** 'never' (no expiry), 'expired' (past) or 'active' (future). */
export function expiryState(block, now = Date.now()) {
  if (block?.expiresAt === null || block?.expiresAt === undefined) return 'never';
  return Number(block.expiresAt) <= now ? 'expired' : 'active';
}

/** Row menu: 'remove' needs OM, 'view-order' needs an order and OV. UI gating is cosmetic. */
export function rowActions(block, user) {
  const actions = [];
  if (can(user, 'OM')) actions.push('remove');
  if (block?.orderId && can(user, 'OV')) actions.push('view-order');
  return actions;
}

/** Server error -> { field, code } to mark in the modal, or null (toast only). */
export function blockFieldError(errorCode) {
  if (errorCode === 'BLOCK_ALREADY_EXISTS') return { field: 'value', code: 'BLOCK_ALREADY_EXISTS' };
  if (errorCode === 'INVALID_BLOCK') return { field: 'value', code: 'INVALID_BLOCK' };
  return null;
}
