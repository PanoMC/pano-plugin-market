// Pure core of the settings sections credits / delivery / modules / security / mail (13 §17).
// Same contract as utils/settings.js (field table, partial body, one code per field) and no Svelte or
// SDK import, so every rule here is unit tested. The Minecraft and health sections live in
// utils/minecraft-settings.js and utils/health.js.
import { actionFromApi, newAction, serializeActions, validateActions } from './actions.js';
import { MAIL_KINDS, SECTION_KEYS, validateValue } from './settings.js';

function checkKeys(keys, values, errors) {
  for (const key of keys) {
    const code = validateValue(key, values[key]);
    if (code) errors[key] = code;
  }
}

/** Items of `order` that are in `list` (in `order` order), then the stored items `order` does not know. */
export function canonicalList(list, order) {
  const items = Array.isArray(list) ? list : [];
  const known = order.filter((item) => items.includes(item));
  return [...known, ...items.filter((item) => !order.includes(item))];
}

/** `list` with `value` added or removed, kept in the canonical order so the dirty check is stable. */
export function toggleListValue(list, value, on, order) {
  const items = Array.isArray(list) ? list : [];
  const next = on
    ? items.includes(value)
      ? items
      : [...items, value]
    : items.filter((item) => item !== value);
  return canonicalList(next, order);
}

// ---------------------------------------------------------------------------------------------
// Credits (POST /settings/credits)
// ---------------------------------------------------------------------------------------------

/**
 * The values a credits save sends. The dependent switches follow their parent (the controls are
 * disabled while the parent is off): without credits nothing can be "only credits", without top-up
 * there is no free amount. The server refuses both combinations (REQUIRES_CREDITS / REQUIRES_TOP_UP).
 */
export function normalizeCredits(values) {
  const out = { ...values };
  if (!out.creditsEnabled) out.onlyAcceptCredits = false;
  if (!out.creditTopUpEnabled) out.creditTopUpFreeAmount = false;
  return out;
}

/** Keys that count for the current values: the top-up amounts only while top-up is on. */
export function activeCreditKeys(values) {
  return SECTION_KEYS.credits.filter(
    (key) => values.creditTopUpEnabled || (key !== 'creditTopUpMin' && key !== 'creditTopUpMax'),
  );
}

export function validateCredits(rawValues) {
  const values = normalizeCredits(rawValues);
  const errors = {};
  checkKeys(activeCreditKeys(values), values, errors);
  if (
    values.creditTopUpEnabled &&
    !errors.creditTopUpMin &&
    !errors.creditTopUpMax &&
    Number(values.creditTopUpMax) < Number(values.creditTopUpMin)
  )
    errors.creditTopUpMax = 'MIN_ABOVE_MAX';
  return errors;
}

/** The refund-split warning above the credits card (owner decision, 07 §6.5 item 1). */
export function showRefundSplitWarning(values) {
  return Boolean(values?.allowMixedCreditPayment);
}

/**
 * Products whose credit price and cash price disagree (07 §6.5 item 3): shown while mixed payment is
 * on and GET /settings reports a positive `creditPriceMismatchCount`. Returns the count or 0.
 */
export function priceMismatchCount(settings, values) {
  const count = Number(settings?.creditPriceMismatchCount);
  return values?.allowMixedCreditPayment && Number.isFinite(count) && count > 0 ? count : 0;
}

// ---------------------------------------------------------------------------------------------
// Delivery: chargeback actions
// ---------------------------------------------------------------------------------------------

export const CHARGEBACK_ACTION_TYPES = ['COMMAND', 'WEBHOOK'];
export const MAX_CHARGEBACK_ACTIONS = 10;

/** Variables the chargeback editors offer: the order is known, the product is not one fixed thing. */
export const CHARGEBACK_VARIABLES = [
  'username',
  'uuid',
  'buyer.username',
  'order.id',
  'order.publicId',
  'order.total',
  'order.currency',
  'server.id',
  'server.name',
  'date',
  'time',
];

/** Delivery keys that are plain controls (the action list is edited by the action editors). */
export const DELIVERY_PLAIN_KEYS = SECTION_KEYS.delivery.filter(
  (key) => key !== 'chargebackActions',
);

/**
 * The stored `chargebackActions` string as editor rows. A string that is not a JSON array gives an
 * empty list and `invalid: true` (the section shows a note; saving replaces it).
 */
export function parseChargebackActions(text) {
  const raw = typeof text === 'string' && text.trim() !== '' ? text : '[]';
  try {
    const parsed = JSON.parse(raw);
    if (!Array.isArray(parsed)) return { actions: [], invalid: true };
    return {
      actions: parsed.map((entry) => ({
        ...actionFromApi(entry && typeof entry === 'object' ? entry : {}),
        phase: 'GRANT',
      })),
      invalid: false,
    };
  } catch {
    return { actions: [], invalid: true };
  }
}

/** Wire string of the rows: phase fixed to GRANT, `perUnit` never set (11 §10). */
export function serializeChargebackActions(actions) {
  const rows = (actions ?? []).map((action) => ({ ...action, phase: 'GRANT', perUnit: false }));
  return JSON.stringify(serializeActions(rows));
}

/** The preset of 11 §10: a ban command on every connected server. */
export function banPreset() {
  const action = newAction('COMMAND', { phase: 'GRANT' });
  action.value = ['ban {username} Chargeback'];
  action.serverMode = 'ALL_CONNECTED';
  return action;
}

/**
 * Errors of the action rows as `{ 'actions.<i>.<key>': CODE, actions?: CODE }` (the keys the action
 * editors read): the product rules of utils/actions.js with the phase list [GRANT], plus the rules of
 * 11 §10 (COMMAND or WEBHOOK only, at most 10, no BUYER_CHOICE).
 */
export function validateChargebackActions(actions, servers = null) {
  const list = actions ?? [];
  const errors = validateActions(list, {}, servers, { phases: ['GRANT'] });
  if (list.length > MAX_CHARGEBACK_ACTIONS) errors.actions = 'TOO_MANY';
  list.forEach((action, index) => {
    if (!CHARGEBACK_ACTION_TYPES.includes(action?.type))
      errors[`actions.${index}.type`] = 'INVALID';
  });
  return errors;
}

/** Errors of one action row with the `actions.<i>.` prefix removed (the editor keys are relative). */
export function rowErrors(errors, index) {
  const prefix = `actions.${index}.`;
  const out = {};
  for (const [path, code] of Object.entries(errors ?? {}))
    if (path.startsWith(prefix)) out[path.slice(prefix.length)] = code;
  return out;
}

/**
 * Baseline and value of `chargebackActions` for the partial body. A stored string that differs only by
 * formatting is equal to its canonical form (no phantom "unsaved changes"); an unparsable one stays
 * raw, so the first save replaces it.
 */
export function chargebackPair(settings, actions) {
  const stored = parseChargebackActions(settings?.chargebackActions);
  return {
    baseline: stored.invalid
      ? String(settings?.chargebackActions ?? '')
      : serializeChargebackActions(stored.actions),
    value: serializeChargebackActions(actions),
  };
}

/** `{ values, baseline }` with the chargeback string merged in, ready for buildSettingsBody. */
export function withChargeback(settings, values, actions) {
  const pair = chargebackPair(settings, actions);
  return {
    baseline: { ...settings, chargebackActions: pair.baseline },
    values: { ...values, chargebackActions: pair.value },
  };
}

/** Field errors of the delivery section (the action rows are validated by validateChargebackActions). */
export function validateDelivery(values, actions, servers = null) {
  const errors = {};
  checkKeys(DELIVERY_PLAIN_KEYS, values, errors);
  const serialized = serializeChargebackActions(actions);
  const code = validateValue('chargebackActions', serialized);
  if (code) errors.chargebackActions = code;
  return { errors, actions: validateChargebackActions(actions, servers) };
}

export const deliveryValid = (result) =>
  Object.keys(result.errors).length === 0 && Object.keys(result.actions).length === 0;

// ---------------------------------------------------------------------------------------------
// Modules
// ---------------------------------------------------------------------------------------------

/** A module's own settings count only while the module is on (the inputs are disabled otherwise). */
export function activeModuleKeys(values) {
  return SECTION_KEYS.modules.filter((key) => {
    if (key.startsWith('moduleRecentBuyers') && key !== 'moduleRecentBuyers')
      return Boolean(values.moduleRecentBuyers);
    if (key.startsWith('moduleTopSupporters') && key !== 'moduleTopSupporters')
      return Boolean(values.moduleTopSupporters);
    return true;
  });
}

export function validateModules(values) {
  const errors = {};
  checkKeys(activeModuleKeys(values), values, errors);
  return errors;
}

// ---------------------------------------------------------------------------------------------
// Security
// ---------------------------------------------------------------------------------------------

export function validateSecurity(values) {
  const errors = {};
  checkKeys(SECTION_KEYS.security, values, errors);
  return errors;
}

/** Turning the private-target switch on is a danger confirmation (13 §17 security). */
export function needsPrivateTargetConfirm(baseline, values) {
  return (
    baseline?.allowPrivateWebhookTargets !== true && values?.allowPrivateWebhookTargets === true
  );
}

// ---------------------------------------------------------------------------------------------
// Mail
// ---------------------------------------------------------------------------------------------

/** Service mails are sent whatever `sendEmailAfterPurchase` says (12 §10); they can still be switched off one by one. */
export const SERVICE_MAIL_KINDS = [
  'BANK_TRANSFER_INSTRUCTIONS',
  'SUBSCRIPTION_REMINDER',
  'SUBSCRIPTION_PAYMENT_FAILED',
  'SUBSCRIPTION_CANCELLED',
  'SUBSCRIPTION_ENDED',
  'EXPIRY_REMINDER',
];

export const isServiceMailKind = (kind) => SERVICE_MAIL_KINDS.includes(kind);

/** A mail kind is on unless it is listed in `mailDisabledKinds`. */
export const mailKindEnabled = (disabledKinds, kind) => !(disabledKinds ?? []).includes(kind);

/** Checkbox change: unchecked = disabled. Returns the new `mailDisabledKinds` list. */
export const setMailKindEnabled = (disabledKinds, kind, enabled) =>
  toggleListValue(disabledKinds, kind, !enabled, MAIL_KINDS);

export function validateMail(values) {
  const errors = {};
  checkKeys(SECTION_KEYS.mail, values, errors);
  return errors;
}

/**
 * Which alert tops the mail section: `HOST_TOO_OLD` (danger, from the health report) wins over
 * `DISABLED` (warning, the platform switch is off); null when mail works.
 */
export function mailAlert(settings, health) {
  if (health?.mail === 'HOST_TOO_OLD') return 'HOST_TOO_OLD';
  if (settings?.mailEnabled === false || health?.mailEnabled === false) return 'DISABLED';
  return null;
}

const MAIL_ADDRESS = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

/** Test-mail form `{ kind, recipient }` -> `{ errors, body }`; the recipient may stay empty (the admin's own address). */
export function testMailRequest(form) {
  const errors = {};
  const kind = String(form?.kind ?? '');
  const recipient = String(form?.recipient ?? '').trim();
  if (kind !== '' && !MAIL_KINDS.includes(kind)) errors.kind = 'INVALID_VALUE';
  if (recipient !== '' && (recipient.length > 254 || !MAIL_ADDRESS.test(recipient)))
    errors.recipient = 'INVALID_VALUE';
  if (Object.keys(errors).length > 0) return { errors, body: null };
  const body = {};
  if (kind !== '') body.kind = kind;
  if (recipient !== '') body.recipient = recipient;
  return { errors: {}, body };
}
