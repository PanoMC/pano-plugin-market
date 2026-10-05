// Pure core of the Minecraft settings section (13 §17 minecraft, 19 §9, 19 §10): panel defaults of the
// in-game features, the per-server table and the per-server override. No Svelte or SDK import.
import { safeUrl } from '../components/overview/alerts.js';
import {
  ADMIN_COMMANDS,
  DEFAULT_BROADCAST_TEMPLATE,
  FIELDS,
  SECTION_KEYS,
  VAULT_DIRECTIONS,
  VAULT_MODES,
  validateValue,
} from './settings.js';
import { canonicalList } from './settings-extra.js';

/** Boolean in-game features, in panel order. `key` is the config key, `id` the locale suffix. */
export const MC_FEATURES = [
  { key: 'mcStoreCommand', id: 'store-command' },
  { key: 'mcCreditsCommand', id: 'credits-command' },
  { key: 'mcJoinNotifications', id: 'join-notifications' },
  { key: 'mcStoreMenu', id: 'store-menu' },
  { key: 'mcAdminCommands', id: 'admin-commands' },
  { key: 'mcPlaceholders', id: 'placeholders' },
  { key: 'mcLuckPerms', id: 'luckperms' },
  { key: 'mcBroadcast', id: 'broadcast' },
];

export const BROADCAST_VARIABLES = ['player', 'product', 'quantity', 'store'];

/** Every key a server override may carry (the `mc*` keys of 00 §12). */
export const OVERRIDE_KEYS = SECTION_KEYS.minecraft;

// ---------------------------------------------------------------------------------------------
// Panel defaults
// ---------------------------------------------------------------------------------------------

/** The Vault rate and direction only mean something in CONVERT mode (19 §10). */
export function activeMinecraftKeys(values) {
  return SECTION_KEYS.minecraft.filter((key) => {
    if (key === 'mcVaultRate' || key === 'mcVaultDirection')
      return values.mcVaultMode === 'CONVERT';
    return true;
  });
}

export function validateMinecraft(values) {
  const errors = {};
  for (const key of activeMinecraftKeys(values)) {
    const code = validateValue(key, values[key]);
    if (code) errors[key] = code;
  }
  return errors;
}

/** PROVIDER turns every economy call into a round trip to Pano: the section warns (19 §10). */
export const vaultProviderWarning = (values) => values?.mcVaultMode === 'PROVIDER';

// ---------------------------------------------------------------------------------------------
// Broadcast preview
// ---------------------------------------------------------------------------------------------

const COLORS = {
  0: '#000000',
  1: '#0000aa',
  2: '#00aa00',
  3: '#00aaaa',
  4: '#aa0000',
  5: '#aa00aa',
  6: '#ffaa00',
  7: '#aaaaaa',
  8: '#555555',
  9: '#5555ff',
  a: '#55ff55',
  b: '#55ffff',
  c: '#ff5555',
  d: '#ff55ff',
  e: '#ffff55',
  f: '#ffffff',
};

/** Sample values of the preview (never real buyer data). */
export function previewValues(storeName) {
  return { player: 'Steve', product: 'VIP Rank', quantity: '1', store: storeName || 'Store' };
}

/**
 * The template as text segments `{ text, color, bold, italic }`: `{variable}` replaced from `values`
 * (unknown ones stay as typed), `&0`-`&f` colours, `&l` bold, `&o` italic, `&r` reset. The segments are
 * rendered as text nodes, so nothing of the template is ever parsed as HTML.
 */
export function previewSegments(template, values) {
  const text = String(template ?? '').replace(/\{([a-z]+)\}/g, (match, name) =>
    Object.hasOwn(values ?? {}, name) ? String(values[name]) : match,
  );
  const segments = [];
  let style = { color: null, bold: false, italic: false };
  let buffer = '';
  const flush = () => {
    if (buffer !== '') segments.push({ text: buffer, ...style });
    buffer = '';
  };
  for (let i = 0; i < text.length; i++) {
    const char = text[i];
    const code = text[i + 1]?.toLowerCase();
    if (char === '&' && code !== undefined && /[0-9a-folkmnr]/.test(code)) {
      flush();
      if (code in COLORS) style = { color: COLORS[code], bold: false, italic: false };
      else if (code === 'l') style = { ...style, bold: true };
      else if (code === 'o') style = { ...style, italic: true };
      else if (code === 'r') style = { color: null, bold: false, italic: false };
      // k, m, n: obfuscated / strikethrough / underline are ignored by the preview
      i++;
      continue;
    }
    buffer += char;
  }
  flush();
  return segments;
}

// ---------------------------------------------------------------------------------------------
// Server table
// ---------------------------------------------------------------------------------------------

export const MARKET_STATES = ['READY', 'OFFLINE', 'COMPONENT_MISSING', 'VERSION_MISMATCH'];

const STATE_CLASS = {
  READY: 'text-bg-success',
  OFFLINE: 'text-bg-secondary',
  COMPONENT_MISSING: 'text-bg-danger',
  VERSION_MISMATCH: 'text-bg-warning',
};

/** Badge class of a marketState; an unknown state is neutral. */
export const stateClass = (state) => STATE_CLASS[state] ?? 'text-bg-secondary';

const hasSettings = (settings) =>
  settings !== null && typeof settings === 'object' && Object.keys(settings).length > 0;

/** Number of keys a server overrides (0 = follows the panel defaults). */
export const overrideCount = (server) =>
  hasSettings(server?.settings) ? Object.keys(server.settings).length : 0;

/** View model of one `GET /servers` row: only what the table renders, links already vetted. */
export function serverRow(server) {
  const state = MARKET_STATES.includes(server?.marketState) ? server.marketState : null;
  const integrations = Array.isArray(server?.integrations) ? server.integrations.map(String) : [];
  const waiting = Number(server?.waitingDeliveries);
  return {
    id: server?.id,
    name: String(server?.name ?? ''),
    type: server?.type ? String(server.type) : '',
    state,
    stateClass: stateClass(state),
    version: server?.mcComponentVersion ? String(server.mcComponentVersion) : '',
    required: server?.requiredVersion ? String(server.requiredVersion) : '',
    integrations,
    waiting: Number.isFinite(waiting) && waiting > 0 ? waiting : 0,
    overrides: overrideCount(server),
    downloadUrl: safeUrl(server?.downloadUrl),
    needsComponent: state === 'COMPONENT_MISSING' || state === 'VERSION_MISMATCH',
  };
}

export const serverRows = (servers) => (Array.isArray(servers) ? servers.map(serverRow) : []);

// ---------------------------------------------------------------------------------------------
// Per-server override (three-state form, 19 §9)
// ---------------------------------------------------------------------------------------------

export const TRI_STATES = ['default', 'on', 'off'];

/** Form model of a server's stored override (`null` / `{}` = every control on "Default"). */
export function overrideForm(settings) {
  const stored = hasSettings(settings) ? settings : {};
  const form = {};
  for (const { key } of MC_FEATURES)
    form[key] = typeof stored[key] === 'boolean' ? (stored[key] ? 'on' : 'off') : 'default';
  form.mcBroadcastTemplate =
    typeof stored.mcBroadcastTemplate === 'string' ? stored.mcBroadcastTemplate : '';
  form.mcVaultMode = VAULT_MODES.includes(stored.mcVaultMode) ? stored.mcVaultMode : 'default';
  form.mcVaultRate =
    stored.mcVaultRate !== undefined && stored.mcVaultRate !== null
      ? String(stored.mcVaultRate)
      : '';
  form.mcVaultDirection = VAULT_DIRECTIONS.includes(stored.mcVaultDirection)
    ? stored.mcVaultDirection
    : 'default';
  form.adminCommandsCustom = Array.isArray(stored.mcDisabledAdminCommands);
  form.mcDisabledAdminCommands = Array.isArray(stored.mcDisabledAdminCommands)
    ? canonicalList(stored.mcDisabledAdminCommands, ADMIN_COMMANDS)
    : [];
  return form;
}

/** The Vault mode that applies on the server: its own, else the panel default. */
export function effectiveVaultMode(form, defaults) {
  return form.mcVaultMode !== 'default' ? form.mcVaultMode : (defaults?.mcVaultMode ?? 'OFF');
}

const parseRate = (text) => {
  const trimmed = String(text ?? '')
    .trim()
    .replace(',', '.');
  if (!/^\d+(\.\d+)?$/.test(trimmed)) return NaN;
  return Number(trimmed);
};

/** Errors of the override form: `{ field: CODE }`. Only the fields that are set are checked. */
export function validateOverride(form, defaults) {
  const errors = {};
  if (form.mcBroadcastTemplate.trim() !== '') {
    const code = validateValue('mcBroadcastTemplate', form.mcBroadcastTemplate);
    if (code) errors.mcBroadcastTemplate = code;
  }
  if (effectiveVaultMode(form, defaults) === 'CONVERT' && form.mcVaultRate.trim() !== '') {
    const rate = parseRate(form.mcVaultRate);
    const field = FIELDS.mcVaultRate;
    if (!Number.isFinite(rate)) errors.mcVaultRate = 'INVALID_TYPE';
    else if (rate < field.min || rate > field.max) errors.mcVaultRate = 'OUT_OF_RANGE';
  }
  return errors;
}

/**
 * The override object for `PUT /servers/:id/settings`: only the controls that are not on "Default".
 * Nothing set returns `null`, which clears the override. Vault rate and direction travel only when the
 * server's effective mode is CONVERT.
 */
export function buildOverride(form, defaults) {
  const out = {};
  for (const { key } of MC_FEATURES)
    if (form[key] === 'on' || form[key] === 'off') out[key] = form[key] === 'on';
  if (form.mcBroadcastTemplate.trim() !== '') out.mcBroadcastTemplate = form.mcBroadcastTemplate;
  if (form.mcVaultMode !== 'default') out.mcVaultMode = form.mcVaultMode;
  if (effectiveVaultMode(form, defaults) === 'CONVERT') {
    if (form.mcVaultRate.trim() !== '') out.mcVaultRate = parseRate(form.mcVaultRate);
    if (form.mcVaultDirection !== 'default') out.mcVaultDirection = form.mcVaultDirection;
  }
  if (form.adminCommandsCustom)
    out.mcDisabledAdminCommands = canonicalList(form.mcDisabledAdminCommands, ADMIN_COMMANDS);
  return Object.keys(out).length > 0 ? out : null;
}

/** Request body of the save: `{ settings: {...} }`, or `{ settings: null }` to clear. */
export const overrideRequest = (form, defaults) => ({ settings: buildOverride(form, defaults) });

export const clearOverrideRequest = () => ({ settings: null });

export { DEFAULT_BROADCAST_TEMPLATE };
