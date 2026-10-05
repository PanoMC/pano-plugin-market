// Pure validators shared by the panel forms (13 §13, §8.1, §8.5). No Svelte, no SDK import.

const IPV4 = /^(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?:\.(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/;

function isIPv4(text) {
  return IPV4.test(text);
}

function isIPv6(text) {
  if (!text.includes(':') || /[^0-9a-fA-F:.]/.test(text)) return false;
  let head = text;
  let groups = 0;
  // an embedded IPv4 tail counts as two groups
  const lastColon = text.lastIndexOf(':');
  const tail = text.slice(lastColon + 1);
  if (tail.includes('.')) {
    if (!isIPv4(tail)) return false;
    head = text.slice(0, lastColon + 1) + '0:0';
  }
  const doubles = head.split('::');
  if (doubles.length > 2) return false;
  const parts = (s) => (s === '' ? [] : s.split(':'));
  const left = parts(doubles[0]);
  const right = doubles.length === 2 ? parts(doubles[1]) : [];
  for (const g of [...left, ...right]) {
    if (!/^[0-9a-fA-F]{1,4}$/.test(g)) return false;
    groups++;
  }
  return doubles.length === 2 ? groups <= 7 : groups === 8;
}

/** IPv4 / IPv6 literal with an optional /prefix (IPv4 0-32, IPv6 0-128). */
export function isIpOrCidr(value) {
  const text = String(value ?? '').trim();
  if (text === '') return false;
  const slash = text.indexOf('/');
  const address = slash === -1 ? text : text.slice(0, slash);
  const prefix = slash === -1 ? null : text.slice(slash + 1);
  const v4 = isIPv4(address);
  if (!v4 && !isIPv6(address)) return false;
  if (prefix === null) return true;
  if (!/^\d{1,3}$/.test(prefix)) return false;
  return Number(prefix) <= (v4 ? 32 : 128);
}

/** Admin-supplied Minecraft username (00 8.4): `*` / `.` are Bedrock prefixes, one alphanumeric needed. */
export function isMinecraftUsername(value) {
  const text = String(value ?? '');
  return /^[A-Za-z0-9_.*]{1,32}$/.test(text) && /[A-Za-z0-9]/.test(text);
}

/** Custom product field key: variable name `{field.<fieldKey>}` (01 2.5). */
export const FIELD_KEY_PATTERN = /^[a-z][a-z0-9_]{0,31}$/;
export const isFieldKey = (value) => FIELD_KEY_PATTERN.test(String(value ?? ''));

export const SLUG_PATTERN = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;
export const RESERVED_SLUGS = ['checkout', 'order', 'cart'];

/** null when valid, otherwise 'INVALID_SLUG' or 'RESERVED_SLUG' (the latter is an `errors.*` code). */
export function slugError(value) {
  const slug = String(value ?? '');
  if (!SLUG_PATTERN.test(slug)) return 'INVALID_SLUG';
  if (RESERVED_SLUGS.includes(slug)) return 'RESERVED_SLUG';
  return null;
}

/**
 * Custom-field `pattern` guard (01 2.5, RE2-safe subset): at most 255 characters, compiles as
 * `^(?:p)$`, no backreference (\1-\9, \k<name>) and no lookaround. null when acceptable, otherwise
 * 'TOO_LONG' | 'INVALID_FORMAT' | 'BACKREFERENCE' | 'LOOKAROUND'.
 */
export function patternError(pattern) {
  const p = String(pattern ?? '');
  if (p.length > 255) return 'TOO_LONG';
  for (let i = 0; i < p.length; i++) {
    const c = p[i];
    if (c === '\\') {
      const next = p[i + 1];
      if (next !== undefined && /[1-9]/.test(next)) return 'BACKREFERENCE';
      if (next === 'k' && p[i + 2] === '<') return 'BACKREFERENCE';
      i++;
    } else if (c === '(' && p[i + 1] === '?') {
      const rest = p.slice(i + 2, i + 4);
      if (rest[0] === '=' || rest[0] === '!' || rest === '<=' || rest === '<!') return 'LOOKAROUND';
    }
  }
  try {
    new RegExp('^(?:' + p + ')$');
  } catch {
    return 'INVALID_FORMAT';
  }
  return null;
}

/** Exactly one `@` with text on both sides, at most 255 characters. */
export function isEmailLike(value) {
  const text = String(value ?? '');
  if (text.length === 0 || text.length > 255) return false;
  const parts = text.split('@');
  return parts.length === 2 && parts[0].length > 0 && parts[1].length > 0 && !/\s/.test(text);
}
