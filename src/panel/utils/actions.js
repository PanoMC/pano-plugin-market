// Pure model of a product action (13 §8.9, 08 §2.2, 01 §2.2): defaults, phases, normalisation,
// validation and the wire form. Used by the product form (ActionsTab / ActionEditor), by the creator
// payout modal (`phases = ['GRANT']`) and by the chargeback actions of the settings. No Svelte, no SDK.
import { FIELD_KEY_PATTERN } from './validate.js';

export const ACTION_TYPES = ['CREDIT', 'PERMISSION', 'COMMAND', 'WEBHOOK'];
export const PHASES = ['GRANT', 'RENEW', 'EXPIRE', 'REVOKE'];
export const SERVER_MODES = ['FIXED', 'BUYER_CHOICE', 'ALL_CONNECTED'];
export const WEBHOOK_FORMATS = ['JSON', 'DISCORD'];
export const WEBHOOK_SIGNINGS = ['NONE', 'HMAC_SHA256'];
export const PERMISSION_VIAS = ['PANO', 'SERVER'];

export const MAX_ACTIONS = 30;
export const MAX_COMMANDS = 20;
export const MAX_NODES = 20;
export const MAX_COMMAND_LENGTH = 512;
export const MAX_DELAY_SECONDS = 2_592_000;
export const MAX_CREDIT = 1_000_000;
export const SECRET_MASK = '********';

export const NODE_PATTERN = /^[A-Za-z0-9_.*-]{1,128}$/;
// eslint-disable-next-line no-control-regex
const CONTROL_CHARS = /[\u0000-\u001f\u007f]/;

/** Phases the product's billing mode offers (13 §8.9, test 34). */
export function allowedPhases(billingMode) {
  if (billingMode === 'SUBSCRIPTION') return ['GRANT', 'RENEW', 'EXPIRE', 'REVOKE'];
  if (billingMode === 'TIMED') return ['GRANT', 'EXPIRE', 'REVOKE'];
  return ['GRANT', 'REVOKE'];
}

/** CREDIT and PERMISSION only run in GRANT / RENEW; their inverse is automatic (08 §2.2, §2.3). */
export function phasesForType(type, phases) {
  const list = phases ?? PHASES;
  if (type === 'CREDIT' || type === 'PERMISSION')
    return list.filter((p) => p === 'GRANT' || p === 'RENEW');
  return list;
}

/** True for the actions that run on a Minecraft server (COMMAND, PERMISSION with via=SERVER). */
export function isServerAction(action) {
  if (action?.type === 'COMMAND') return true;
  return action?.type === 'PERMISSION' && action.via === 'SERVER';
}

/** The types that carry the delay control (13 §8.9). */
export const hasDelay = (type) => type === 'COMMAND' || type === 'WEBHOOK' || type === 'PERMISSION';
export const hasPerUnit = (type) => type === 'COMMAND' || type === 'WEBHOOK';

/** A fresh action in the wire shape, without an `id` (the server generates it). */
export function newAction(type, { phase = 'GRANT' } = {}) {
  const base = { type, phase, delay: 0 };
  switch (type) {
    case 'CREDIT':
      return { ...base, value: null };
    case 'PERMISSION':
      return {
        ...base,
        value: [],
        via: 'PANO',
        serverMode: 'FIXED',
        targetServers: [],
      };
    case 'COMMAND':
      return {
        ...base,
        value: [''],
        serverMode: 'FIXED',
        targetServers: [],
        requiresOnline: false,
        perUnit: false,
      };
    case 'WEBHOOK':
      return {
        ...base,
        value: { url: '', format: 'JSON', signing: 'NONE', secret: '' },
        perUnit: false,
      };
    default:
      return base;
  }
}

const stripSlash = (text) => String(text ?? '').replace(/^\//, '');

/**
 * Normalises an action in place of the user's typing: a leading `/` of a command is removed and the
 * row trimmed, a DISCORD webhook is never signed, a numeric credit value becomes a number. Returns a
 * new object; unknown keys (such as `via` of a loaded action) pass through.
 */
export function normalizeAction(action) {
  const out = { ...action };
  if (out.type === 'COMMAND') {
    const rows = Array.isArray(out.value) ? out.value : [];
    out.value = rows.map((row) => stripSlash(String(row ?? '').trim()));
  } else if (out.type === 'WEBHOOK') {
    const value = { ...(out.value ?? {}) };
    if (value.format === 'DISCORD') value.signing = 'NONE';
    out.value = value;
  } else if (out.type === 'CREDIT') {
    if (typeof out.value === 'string' && out.value.trim() !== '' && out.value.trim() !== '-') {
      const n = Number(out.value);
      if (Number.isFinite(n)) out.value = n;
    }
  }
  return out;
}

/**
 * A loaded action with the defaults the server applies to a missing key filled in (phase GRANT, delay 0,
 * scope FIXED, ...), so the editor can bind to every key. Nothing the server sent is changed or dropped.
 */
export function actionFromApi(action) {
  const out = { ...action };
  out.phase ??= 'GRANT';
  if (hasDelay(out.type)) out.delay ??= 0;
  if (out.type === 'COMMAND') {
    if (!Array.isArray(out.value)) out.value = out.value ? [String(out.value)] : [''];
    out.requiresOnline ??= false;
  } else if (out.type === 'PERMISSION') {
    if (!Array.isArray(out.value)) out.value = out.value ? [String(out.value)] : [];
    out.via ??= 'PANO';
  } else if (out.type === 'WEBHOOK') {
    out.value = { url: '', format: 'JSON', signing: 'NONE', secret: '', ...(out.value ?? {}) };
  }
  if (isServerAction(out)) {
    out.serverMode ??= 'FIXED';
    out.targetServers ??= [];
  }
  if (hasPerUnit(out.type)) out.perUnit ??= false;
  return out;
}

/** The id of a loaded action (a non-empty string) or null. */
export const actionId = (action) =>
  typeof action?.id === 'string' && action.id !== '' ? action.id : null;

/** Wire form of one action: a loaded action keeps its `id` verbatim, a new one is sent without. */
export function serializeAction(action) {
  const out = normalizeAction(action);
  const id = actionId(action);
  if (id === null) delete out.id;
  else out.id = id;
  for (const key of Object.keys(out)) if (out[key] === undefined) delete out[key];
  if (out.type === 'CREDIT') {
    delete out.serverMode;
    delete out.targetServers;
    delete out.requiresOnline;
    delete out.perUnit;
  } else if (out.type === 'PERMISSION') {
    delete out.requiresOnline;
    delete out.perUnit;
  } else if (out.type === 'WEBHOOK') {
    delete out.serverMode;
    delete out.targetServers;
    delete out.requiresOnline;
  }
  return out;
}

export const serializeActions = (actions) => (actions ?? []).map(serializeAction);

const isInt = (value) => Number.isInteger(Number(value)) && String(value).trim() !== '';

function validWebhookUrl(url) {
  const text = String(url ?? '').trim();
  if (text === '' || text.includes('{')) return false;
  try {
    const parsed = new URL(text);
    return parsed.protocol === 'http:' || parsed.protocol === 'https:';
  } catch {
    return false;
  }
}

/**
 * Validates one action (13 §8.9 + 08 §2.2). `product` supplies `billingMode`, `serverChoices`,
 * `maxQuantityPerOrder` (and `fields` for the variable check). `servers` is the platform server list
 * (`[{id}]`) or null when it is unknown (then ids are not checked). `phases` overrides the product's
 * allowed phases (payout / chargeback editors). Returns `{ <key>: CODE }` with keys relative to the
 * action: `type`, `phase`, `value`, `value.<row>`, `value.url`, `delay`, `serverMode`, `targetServers`,
 * `perUnit`. Empty = valid.
 */
export function validateAction(action, product = {}, servers = null, { phases = null } = {}) {
  const errors = {};
  const type = action?.type;
  if (!ACTION_TYPES.includes(type)) {
    errors.type = 'INVALID';
    return errors;
  }

  const phase = action.phase ?? 'GRANT';
  const offered = phases ?? allowedPhases(product.billingMode);
  if (!PHASES.includes(phase)) errors.phase = 'INVALID';
  else if (!offered.includes(phase) || !phasesForType(type, [phase]).includes(phase))
    errors.phase = 'INVALID_PHASE';

  if (
    hasDelay(type) &&
    action.delay !== undefined &&
    action.delay !== null &&
    action.delay !== ''
  ) {
    if (!isInt(action.delay)) errors.delay = 'NOT_INTEGER';
    else if (Number(action.delay) < 0 || Number(action.delay) > MAX_DELAY_SECONDS)
      errors.delay = 'OUT_OF_RANGE';
  }

  if (type === 'CREDIT') {
    const n = action.value;
    const value = typeof n === 'string' ? (n.trim() === '' ? NaN : Number(n)) : n;
    if (value === null || value === undefined || !Number.isFinite(value) || value <= 0)
      errors.value = 'INVALID_VALUE';
    else if (value > MAX_CREDIT || Math.round(value * 100) / 100 !== value)
      errors.value = 'INVALID_VALUE';
  } else if (type === 'PERMISSION') {
    const nodes = Array.isArray(action.value) ? action.value : [];
    if (nodes.length < 1 || nodes.length > MAX_NODES) errors.value = 'INVALID_VALUE';
    nodes.forEach((node, i) => {
      if (!NODE_PATTERN.test(String(node ?? ''))) errors[`value.${i}`] = 'INVALID_VALUE';
    });
    if (action.via !== undefined && !PERMISSION_VIAS.includes(action.via)) errors.via = 'INVALID';
  } else if (type === 'COMMAND') {
    const rows = Array.isArray(action.value) ? action.value : [];
    if (rows.length < 1 || rows.length > MAX_COMMANDS) errors.value = 'INVALID_VALUE';
    rows.forEach((row, i) => {
      const text = stripSlash(String(row ?? '').trim());
      if (text === '' || text.length > MAX_COMMAND_LENGTH || CONTROL_CHARS.test(text))
        errors[`value.${i}`] = 'INVALID_VALUE';
    });
  } else if (type === 'WEBHOOK') {
    const value = action.value ?? {};
    if (!validWebhookUrl(value.url)) errors['value.url'] = 'INVALID_WEBHOOK_URL';
    if (!WEBHOOK_FORMATS.includes(value.format)) errors['value.format'] = 'INVALID';
    if (!WEBHOOK_SIGNINGS.includes(value.signing)) errors['value.signing'] = 'INVALID';
  }

  if (isServerAction(action)) {
    const mode = action.serverMode ?? 'FIXED';
    const known = Array.isArray(servers) ? new Set(servers.map((s) => s.id)) : null;
    if (!SERVER_MODES.includes(mode)) errors.serverMode = 'INVALID';
    else if (known !== null && known.size === 0) errors.serverMode = 'NO_SERVERS';
    else if (mode === 'BUYER_CHOICE') {
      if ((product.serverChoices ?? []).length < 1) errors.serverMode = 'SERVER_CHOICES_REQUIRED';
    } else if (mode === 'FIXED') {
      const targets = action.targetServers ?? [];
      if (type === 'COMMAND' && targets.length < 1) errors.targetServers = 'SERVER_REQUIRED';
      else if (known !== null && targets.some((id) => !known.has(id)))
        errors.targetServers = 'UNKNOWN_SERVER';
    }
  }

  if (hasPerUnit(type) && action.perUnit === true) {
    const max = product.maxQuantityPerOrder;
    if (
      max === null ||
      max === undefined ||
      max === '' ||
      !(Number(max) >= 1 && Number(max) <= 100)
    )
      errors.perUnit = 'PER_UNIT_NEEDS_MAX_QUANTITY';
  }

  return errors;
}

/**
 * Validates the product-level action list. Returns `{ 'actions.<i>.<key>': CODE }` plus
 * `actions` = TOO_MANY / DUPLICATE_ID and `serverChoices` unknown ids. Empty = valid.
 */
export function validateActions(actions, product = {}, servers = null, options = {}) {
  const errors = {};
  const list = actions ?? [];
  if (list.length > MAX_ACTIONS) errors.actions = 'TOO_MANY';
  const seen = new Set();
  list.forEach((action, i) => {
    const id = actionId(action);
    if (id !== null) {
      if (seen.has(id)) errors[`actions.${i}.id`] = 'DUPLICATE_ID';
      seen.add(id);
    }
    for (const [key, code] of Object.entries(validateAction(action, product, servers, options)))
      errors[`actions.${i}.${key}`] = code;
  });
  if (Array.isArray(servers)) {
    const known = new Set(servers.map((s) => s.id));
    if ((product.serverChoices ?? []).some((id) => !known.has(id)))
      errors.serverChoices = 'UNKNOWN_SERVER';
  }
  return errors;
}

/** `{ phase, items: [{ action, index }] }` for each phase that has actions, in phase order. */
export function groupByPhase(actions) {
  const known = new Map(PHASES.map((phase) => [phase, []]));
  const other = [];
  (actions ?? []).forEach((action, index) => {
    const bucket = known.get(action?.phase ?? 'GRANT');
    (bucket ?? other).push({ action, index });
  });
  const out = PHASES.filter((phase) => known.get(phase).length > 0).map((phase) => ({
    phase,
    items: known.get(phase),
  }));
  if (other.length > 0) out.push({ phase: 'OTHER', items: other });
  return out;
}

/** 32 random bytes as 64 hex characters; `getRandomValues` is injected (crypto in the browser). */
export function generateSecret(getRandomValues) {
  const bytes = new Uint8Array(32);
  getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

/** `{ text, caret }` after inserting `{name}` into `text` at `[start, end)`. */
export function insertVariable(text, start, end, name) {
  const value = String(text ?? '');
  const from = Math.max(0, Math.min(start ?? value.length, value.length));
  const to = Math.max(from, Math.min(end ?? from, value.length));
  const token = `{${name}}`;
  return { text: value.slice(0, from) + token + value.slice(to), caret: from + token.length };
}

const VARIABLE_TOKEN = /\{(field|variant)\.([A-Za-z0-9_]+)(?:\|[^}]*)?\}/g;

/** Attribute keys of every variant of the product (rows `[{key, value}]` or a plain object). */
export function variantKeys(product) {
  const keys = new Set();
  for (const variant of product?.variants ?? []) {
    const attributes = variant.attributes;
    if (Array.isArray(attributes)) {
      for (const row of attributes) if (row?.key) keys.add(row.key);
    } else if (attributes && typeof attributes === 'object') {
      for (const key of Object.keys(attributes)) keys.add(key);
    }
  }
  return [...keys];
}

/** Keys of the custom fields a command may use (`usableInCommands`, default on). */
export function usableFieldKeys(product) {
  return (product?.fields ?? [])
    .filter((f) => f.fieldKey && f.usableInCommands !== false && f.type !== 'CHECKBOX')
    .map((f) => f.fieldKey);
}

/**
 * Variables a command may use: the static catalogue plus `field.<key>` for every usable custom field
 * and `variant.<key>` for the union of variant attribute keys.
 */
export function variableNames(product, catalogue) {
  return [
    ...catalogue,
    ...usableFieldKeys(product).map((k) => `field.${k}`),
    ...variantKeys(product).map((k) => `variant.${k}`),
  ];
}

/**
 * `{field.x}` / `{variant.x}` tokens of a COMMAND action whose key does not exist (non-blocking
 * warning, 13 §8.9). Returns the unique token names, e.g. `['field.rank']`.
 */
export function unknownVariables(action, product) {
  if (action?.type !== 'COMMAND') return [];
  const fieldKeys = new Set(usableFieldKeys(product));
  const attrKeys = new Set(variantKeys(product));
  const out = new Set();
  for (const row of Array.isArray(action.value) ? action.value : []) {
    for (const match of String(row ?? '').matchAll(VARIABLE_TOKEN)) {
      const [, kind, key] = match;
      if (kind === 'field' ? !fieldKeys.has(key) : !attrKeys.has(key)) out.add(`${kind}.${key}`);
    }
  }
  return [...out];
}

/** Codes this module and the server return for an action field, mapped to a locale key. */
export const ACTION_ERROR_CODES = [
  'REQUIRED',
  'TOO_LONG',
  'INVALID',
  'INVALID_VALUE',
  'INVALID_PHASE',
  'OUT_OF_RANGE',
  'NOT_INTEGER',
  'TOO_MANY',
  'DUPLICATE_ID',
  'INVALID_WEBHOOK_URL',
  'PER_UNIT_NEEDS_MAX_QUANTITY',
  'FIELD_NOT_USABLE',
  'UNKNOWN_SERVER',
  'SERVER_CHOICES_REQUIRED',
  'SERVER_REQUIRED',
  'NO_SERVERS',
  'PERMISSION_ADMIN_REQUIRED',
];

export function actionErrorKey(code) {
  return `pages.create-product.action-errors.${ACTION_ERROR_CODES.includes(code) ? code : 'INVALID'}`;
}

/** Is `key` something `{field.<key>}` can refer to (sanity helper for tests / tooling). */
export const isFieldKeyLike = (key) => FIELD_KEY_PATTERN.test(String(key ?? ''));
