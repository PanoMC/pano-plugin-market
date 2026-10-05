// Idempotency-Key handling for POST /api/market/checkout (14 §10.7 step 3). Pure: no SDK, no browser globals at top level.

/** JSON with object keys sorted at every depth (undefined members dropped, like JSON.stringify). */
export function canonicalJson(value) {
  if (value === undefined) return 'null';
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null';
  if (Array.isArray(value)) return `[${value.map((v) => canonicalJson(v)).join(',')}]`;

  const parts = [];

  for (const key of Object.keys(value).sort()) {
    if (value[key] === undefined || typeof value[key] === 'function') continue;
    parts.push(`${JSON.stringify(key)}:${canonicalJson(value[key])}`);
  }

  return `{${parts.join(',')}}`;
}

/** cyrb53, a fast 53-bit string hash (not cryptographic: it only tells "same body" from "changed body"). */
export function cyrb53(str, seed = 0) {
  let h1 = 0xdeadbeef ^ seed;
  let h2 = 0x41c6ce57 ^ seed;

  for (let i = 0; i < str.length; i++) {
    const ch = str.charCodeAt(i);
    h1 = Math.imul(h1 ^ ch, 2654435761);
    h2 = Math.imul(h2 ^ ch, 1597334677);
  }

  h1 = Math.imul(h1 ^ (h1 >>> 16), 2246822507);
  h1 ^= Math.imul(h2 ^ (h2 >>> 13), 3266489909);
  h2 = Math.imul(h2 ^ (h2 >>> 16), 2246822507);
  h2 ^= Math.imul(h1 ^ (h1 >>> 13), 3266489909);

  return 4294967296 * (2097151 & h2) + (h1 >>> 0);
}

/** Hash of the canonical JSON of a request body, as a hex string. */
export function bodyHash(body) {
  return cyrb53(canonicalJson(body)).toString(16);
}

function defaultRandomBytes(length) {
  const bytes = new Uint8Array(length);
  const c = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;

  if (c && typeof c.getRandomValues === 'function') return c.getRandomValues(bytes);

  // No Web Crypto at all (very old browser): the key only has to differ between orders, not be unguessable.
  for (let i = 0; i < length; i++) bytes[i] = Math.floor(Math.random() * 256);

  return bytes;
}

/** UUID v4. `randomBytes(n)` is injectable for tests. */
export function uuidV4(randomBytes) {
  const c = typeof globalThis !== 'undefined' ? globalThis.crypto : undefined;

  if (!randomBytes && c && typeof c.randomUUID === 'function') return c.randomUUID();

  const b = (randomBytes || defaultRandomBytes)(16);
  b[6] = (b[6] & 0x0f) | 0x40;
  b[8] = (b[8] & 0x3f) | 0x80;

  const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');

  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * The key for a checkout body: the draft's key while the body is unchanged (a retry after a lost response
 * replays), otherwise a fresh UUID v4. The caller stores both returned values in the draft.
 */
export function resolveIdempotency(draft, body, randomBytes) {
  const hash = bodyHash(body);

  if (draft && draft.idempotencyKey && draft.bodyHash === hash)
    return { idempotencyKey: draft.idempotencyKey, bodyHash: hash, reused: true };

  return { idempotencyKey: uuidV4(randomBytes), bodyHash: hash, reused: false };
}
