// Pure rules of the store webhook settings (13 §18, 08 §15.2). No Svelte, no SDK import.
import { SECRET_MASK } from './schema-form.js';

export const WEBHOOK_FORMATS = ['JSON', 'DISCORD'];
export const WEBHOOK_SIGNINGS = ['NONE', 'HMAC_SHA256'];

/** Subscribable events of 01 §9.3; the server's `eventNames` wins when it sends a list. */
export const WEBHOOK_EVENTS = [
  'order.paid',
  'order.refunded',
  'order.chargeback',
  'order.chargeback.won',
  'subscription.started',
  'subscription.renewed',
  'subscription.cancelled',
  'subscription.expired',
  'shipment.shipped',
  'shipment.delivered',
  'test.ping',
];

export const MAX_HEADERS = 10;
export const DEFAULT_MAX_ATTEMPTS = 8;
export const MAX_URL_LENGTH = 1024;
export const MAX_NAME_LENGTH = 128;
export const MAX_TEMPLATE_LENGTH = 8000;
export const URL_DISPLAY_LENGTH = 48;
export const HEADER_NAME_PATTERN = /^[A-Za-z0-9-]{1,64}$/;
// 13 §18.2 names three, 08 §15.2 (the rule the server enforces) five; the stricter list applies.
const FORBIDDEN_HEADERS = [
  'host',
  'content-length',
  'content-type',
  'transfer-encoding',
  'connection',
  'user-agent',
];
const MAX_HEADER_VALUE_LENGTH = 512;

/** Locale key of an event name: dots become dashes (`order.chargeback.won` -> `order-chargeback-won`). */
export const eventKey = (name) => `enums.webhook-event.${String(name).replace(/\./g, '-')}`;

/** Event names the modal offers: the server list, else the built-in one; `test.ping` is never subscribable. */
export function subscribableEvents(eventNames) {
  const list = Array.isArray(eventNames) && eventNames.length > 0 ? eventNames : WEBHOOK_EVENTS;
  return list.filter((name) => name !== 'test.ping');
}

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';

// ---------------------------------------------------------------------------------------------
// URL
// ---------------------------------------------------------------------------------------------

/** `new URL` result when the text is an http(s) URL, else null. */
export function parseHttpUrl(text) {
  try {
    const url = new URL(String(text ?? '').trim());
    return url.protocol === 'http:' || url.protocol === 'https:' ? url : null;
  } catch {
    return null;
  }
}

/** Discord webhook address: host ends with discord.com / discordapp.com, path starts /api/webhooks/. */
export function isDiscordUrl(text) {
  const url = parseHttpUrl(text);
  if (!url) return false;
  const host = url.hostname.toLowerCase();
  const hostOk = ['discord.com', 'discordapp.com'].some((d) => host === d || host.endsWith('.' + d));
  return hostOk && url.pathname.startsWith('/api/webhooks/');
}

/** Error code of the URL input or null: REQUIRED, TOO_LONG, INVALID_URL, INVALID_DISCORD_URL. */
export function validateUrl(text, format) {
  const value = String(text ?? '').trim();
  if (value === '') return 'REQUIRED';
  if (value.length > MAX_URL_LENGTH) return 'TOO_LONG';
  if (!parseHttpUrl(value)) return 'INVALID_URL';
  if (format === 'DISCORD' && !isDiscordUrl(value)) return 'INVALID_DISCORD_URL';
  return null;
}

/** URL shortened for a table cell (the full text goes in a tooltip). */
export function shortUrl(text, max = URL_DISPLAY_LENGTH) {
  const value = String(text ?? '');
  return value.length > max ? value.slice(0, max - 1) + '…' : value;
}

// ---------------------------------------------------------------------------------------------
// Headers
// ---------------------------------------------------------------------------------------------

/** Error code of one header name or null. */
export function headerNameError(name) {
  const value = String(name ?? '');
  if (!HEADER_NAME_PATTERN.test(value)) return 'INVALID_HEADER_NAME';
  const lower = value.toLowerCase();
  if (FORBIDDEN_HEADERS.includes(lower) || lower.startsWith('x-pano-')) return 'FORBIDDEN_HEADER';
  return null;
}

/** Error code of one header value or null. A masked value (stored one kept as is) is always valid. */
export function headerValueError(value) {
  const text = String(value ?? '');
  if (text === SECRET_MASK) return null;
  if (text === '') return 'REQUIRED';
  if (text.length > MAX_HEADER_VALUE_LENGTH) return 'TOO_LONG';
  if (/[\r\n]/.test(text)) return 'INVALID_HEADER_VALUE';
  return null;
}

/** Rows (`[{key, value}]`) of the key-value list from the `headers` object; values stay masked. */
export function headerRows(headers) {
  return Object.entries(headers && typeof headers === 'object' ? headers : {}).map(([key, value]) => ({
    key,
    value: String(value ?? ''),
  }));
}

/** Row errors by index plus a list error for too many rows. Blank rows (both empty) are ignored. */
export function validateHeaderRows(rows) {
  const used = (rows ?? []).filter((row) => row.key !== '' || row.value !== '');
  const byIndex = {};
  const seen = new Set();
  (rows ?? []).forEach((row, index) => {
    if (row.key === '' && row.value === '') return;
    const lower = String(row.key).toLowerCase();
    const error =
      (row.key === '' ? 'INVALID_HEADER_NAME' : headerNameError(row.key)) ||
      (seen.has(lower) ? 'DUPLICATE_HEADER' : null) ||
      headerValueError(row.value);
    seen.add(lower);
    if (error) byIndex[index] = error;
  });
  return { byIndex, tooMany: used.length > MAX_HEADERS };
}

/** `headers` object of the request body from the rows; a masked value is sent back so the server keeps it. */
export function headersBody(rows) {
  const out = {};
  for (const row of rows ?? []) {
    if (row.key === '' && row.value === '') continue;
    out[row.key] = row.value;
  }
  return out;
}

// ---------------------------------------------------------------------------------------------
// Secret and template
// ---------------------------------------------------------------------------------------------

/** A typed signing secret: 16..128 printable ASCII. Empty and the mask are not typed secrets. */
export function secretError(value) {
  const text = String(value ?? '');
  if (text === '' || text === SECRET_MASK) return null;
  if (text.length < 16 || text.length > 128) return 'INVALID_SECRET_LENGTH';
  if (!/^[\x20-\x7e]+$/.test(text)) return 'INVALID_SECRET_CHARS';
  return null;
}

/** Error code of a custom Discord template or null: REQUIRED, TOO_LONG, INVALID_JSON. */
export function templateError(text) {
  const value = String(text ?? '');
  if (value.trim() === '') return 'REQUIRED';
  if (value.length > MAX_TEMPLATE_LENGTH) return 'TOO_LONG';
  try {
    JSON.parse(value);
  } catch {
    return 'INVALID_JSON';
  }
  return null;
}

// ---------------------------------------------------------------------------------------------
// Form model
// ---------------------------------------------------------------------------------------------

/** Empty create form. */
export function blankForm() {
  return {
    name: '',
    url: '',
    format: 'JSON',
    allEvents: true,
    events: [],
    signing: 'NONE',
    secret: '',
    headers: [],
    customTemplate: false,
    template: '',
    maxAttempts: String(DEFAULT_MAX_ATTEMPTS),
    enabled: true,
  };
}

/** Edit form of a stored endpoint (secret and header values arrive masked and stay masked). */
export function formFromEndpoint(endpoint, defaults = {}) {
  const events = Array.isArray(endpoint?.events) ? endpoint.events : [];
  const format = WEBHOOK_FORMATS.includes(endpoint?.format) ? endpoint.format : 'JSON';
  const signing = format === 'DISCORD' ? 'NONE' : endpoint?.signing === 'HMAC_SHA256' ? 'HMAC_SHA256' : 'NONE';
  const template = typeof endpoint?.template === 'string' ? endpoint.template : '';
  return {
    name: endpoint?.name ?? '',
    url: endpoint?.url ?? '',
    format,
    allEvents: events.includes('*'),
    events: events.filter((name) => name !== '*'),
    signing,
    secret: signing === 'HMAC_SHA256' && present(endpoint?.secret) ? SECRET_MASK : '',
    headers: headerRows(endpoint?.headers),
    customTemplate: format === 'DISCORD' && template !== '',
    template: template || defaults?.discordTemplate || '',
    maxAttempts: String(endpoint?.maxAttempts ?? DEFAULT_MAX_ATTEMPTS),
    enabled: endpoint?.enabled !== false,
  };
}

/** Format change: Discord forces signing NONE and drops the secret; the template switch is Discord only. */
export function withFormat(form, format, defaults = {}) {
  const next = { ...form, format };
  if (format === 'DISCORD') {
    next.signing = 'NONE';
    next.secret = '';
    if (next.template === '') next.template = defaults?.discordTemplate ?? '';
  } else {
    next.customTemplate = false;
  }
  return next;
}

/** Signing change: leaving HMAC clears the secret box. */
export function withSigning(form, signing) {
  if (form.format === 'DISCORD') return { ...form, signing: 'NONE', secret: '' };
  return { ...form, signing, secret: signing === 'HMAC_SHA256' ? form.secret : '' };
}

/** Integer 1..20 or null. */
export function parseMaxAttempts(text) {
  const value = String(text ?? '').trim();
  if (!/^\d+$/.test(value)) return null;
  const number = Number(value);
  return number >= 1 && number <= 20 ? number : null;
}

/**
 * Validation + request body of POST / PUT /webhooks. Returns `{ errors }` (field -> code; `headers`
 * holds `{byIndex, tooMany}` when a row is wrong) or `{ body }`. Discord is always sent with
 * signing NONE and without a secret; a masked secret or header value is sent back as is (kept).
 */
export function buildBody(form, { isEdit = false } = {}) {
  const errors = {};
  const name = String(form.name ?? '').trim();
  if (name === '') errors.name = 'REQUIRED';
  else if (name.length > MAX_NAME_LENGTH) errors.name = 'TOO_LONG';

  const urlError = validateUrl(form.url, form.format);
  if (urlError) errors.url = urlError;

  if (!WEBHOOK_FORMATS.includes(form.format)) errors.format = 'INVALID';

  const discord = form.format === 'DISCORD';
  const signing = discord ? 'NONE' : form.signing;
  if (!WEBHOOK_SIGNINGS.includes(signing)) errors.signing = 'INVALID';

  if (!form.allEvents && (form.events ?? []).length === 0) errors.events = 'REQUIRED';

  const secret = signing === 'HMAC_SHA256' ? String(form.secret ?? '') : '';
  const secretProblem = secretError(secret);
  if (secretProblem) errors.secret = secretProblem;

  const rowErrors = validateHeaderRows(form.headers);
  if (Object.keys(rowErrors.byIndex).length > 0 || rowErrors.tooMany) errors.headers = rowErrors;

  const customTemplate = discord && form.customTemplate === true;
  if (customTemplate) {
    const problem = templateError(form.template);
    if (problem) errors.template = problem;
  }

  const attempts = parseMaxAttempts(form.maxAttempts);
  if (attempts === null) errors.maxAttempts = 'OUT_OF_RANGE';

  if (Object.keys(errors).length > 0) return { errors };

  const body = {
    name,
    url: String(form.url).trim(),
    events: form.allEvents ? ['*'] : [...form.events],
    format: form.format,
    signing,
    headers: headersBody(form.headers),
    template: customTemplate ? form.template : null,
    enabled: form.enabled !== false,
    maxAttempts: attempts,
  };
  // Create: an empty secret lets the server generate one. Edit: blank / mask keeps the stored one.
  if (secret !== '' && secret !== SECRET_MASK) body.secret = secret;
  else if (isEdit && signing === 'HMAC_SHA256' && secret === SECRET_MASK) body.secret = SECRET_MASK;
  return { body };
}

/** Field a server error belongs to: INVALID_WEBHOOK_URL -> url, INVALID_SETTINGS fieldErrors keys. */
export function serverFieldErrors(code, body) {
  if (code === 'INVALID_WEBHOOK_URL') return { url: body?.reason ? `URL_${body.reason}` : 'INVALID_URL' };
  if (code === 'INVALID_SETTINGS' && body?.fieldErrors && typeof body.fieldErrors === 'object')
    return Object.fromEntries(
      Object.entries(body.fieldErrors).map(([field, value]) => [field, String(value)]),
    );
  return {};
}

// ---------------------------------------------------------------------------------------------
// Endpoint list
// ---------------------------------------------------------------------------------------------

/** `{ kind, auto }`: kind 'enabled' | 'disabled'; auto = disabled by the server after repeated failures. */
export function endpointStatus(endpoint) {
  if (endpoint?.enabled) return { kind: 'enabled', auto: false };
  return { kind: 'disabled', auto: present(endpoint?.disabledReason) };
}

/** Events cell: null = "All", else the number of subscribed events. */
export function eventsCount(endpoint) {
  const events = Array.isArray(endpoint?.events) ? endpoint.events : [];
  return events.includes('*') ? null : events.length;
}

/** Send Test result -> `{ ok, status, ms }`. Success = a 2xx status and no error text. */
export function testOutcome(result) {
  const status = Number(result?.statusCode);
  const ok = !result?.error && status >= 200 && status < 300;
  return {
    ok,
    status: Number.isFinite(status) ? status : 0,
    ms: Number.isFinite(Number(result?.durationMs)) ? Number(result.durationMs) : 0,
  };
}

// ---------------------------------------------------------------------------------------------
// Delivery log
// ---------------------------------------------------------------------------------------------

export const DELIVERY_TABS = [
  { key: 'all', value: null },
  { key: 'pending', value: 'PENDING,SENDING' },
  { key: 'succeeded', value: 'SUCCEEDED' },
  { key: 'failed', value: 'FAILED' },
  { key: 'dead', value: 'DEAD' },
];

export function deliveryTab(status) {
  if (!present(status)) return 'all';
  return DELIVERY_TABS.find((tab) => tab.value === status)?.key ?? null;
}

/** Endpoint id from `?endpointId=`: a positive integer or null. */
export function parseEndpointId(value) {
  const text = String(value ?? '');
  return /^[1-9]\d*$/.test(text) ? Number(text) : null;
}

/** GET path of the delivery log (all endpoints, or one). */
export function deliveriesPath({ endpointId = null, status = null, page = 1 } = {}) {
  const base = endpointId ? `/webhooks/${endpointId}/deliveries` : '/webhook-deliveries';
  const params = [];
  if (present(status)) params.push(`status=${encodeURIComponent(status)}`);
  if (page > 1) params.push(`page=${page}`);
  return base + (params.length > 0 ? `?${params.join('&')}` : '');
}

/** Redeliver is offered from SUCCEEDED, FAILED and DEAD (never while PENDING / SENDING). */
export const canRedeliver = (delivery) => ['SUCCEEDED', 'FAILED', 'DEAD'].includes(delivery?.status);

/** Order cell: `#id` or null (shown as a dash). */
export const orderLabel = (delivery) =>
  delivery?.orderId === null || delivery?.orderId === undefined ? null : `#${delivery.orderId}`;

/** Detail body / response as the text of a `<pre>`; JSON is pretty-printed. */
export function prettyText(value) {
  if (value === null || value === undefined || value === '') return '';
  if (typeof value !== 'string') {
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  }
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return value;
  }
}

// ---------------------------------------------------------------------------------------------
// Error texts
// ---------------------------------------------------------------------------------------------

/** Codes with a text under `modals.webhook.errors.<CODE>`. */
export const WEBHOOK_ERROR_CODES = [
  'REQUIRED',
  'TOO_LONG',
  'INVALID',
  'INVALID_URL',
  'INVALID_DISCORD_URL',
  'URL_MALFORMED',
  'URL_SCHEME',
  'URL_USERINFO',
  'URL_HOST',
  'URL_PORT',
  'URL_DISCORD_URL',
  'URL_DNS',
  'URL_PRIVATE_ADDRESS',
  'OUT_OF_RANGE',
  'INVALID_JSON',
  'INVALID_SECRET_LENGTH',
  'INVALID_SECRET_CHARS',
  'INVALID_HEADER_NAME',
  'FORBIDDEN_HEADER',
  'INVALID_HEADER_VALUE',
  'DUPLICATE_HEADER',
  'LIMIT_REACHED',
  'TOO_MANY_HEADERS',
];

/** Locale key of a field error code; an unknown (server) code reads as INVALID. */
export const errorTextKey = (code) =>
  `modals.webhook.errors.${WEBHOOK_ERROR_CODES.includes(code) ? code : 'INVALID'}`;
