// Helpers of the development-only preview fixtures. Pure and deterministic: the same seed always
// yields the same data (SSR, client, reload), so nothing here may read Date.now() or Math.random().

/** Names of the data volumes the drawer offers. */
export const VOLUMES = ['empty', 'few', 'many'];
export const DEFAULT_VOLUME = 'few';

/** Row count of a list per volume. */
export function countFor(volume, few = 7, many = 83) {
  if (volume === 'empty') return 0;
  return volume === 'many' ? many : few;
}

/** Fixed "now" of every fixture (2026-10-01 12:00 UTC), so relative dates never drift. */
export const NOW = Date.UTC(2026, 9, 1, 12, 0, 0);
export const DAY = 86400000;

function hash(text) {
  let h = 1779033703 ^ text.length;
  for (let i = 0; i < text.length; i++) {
    h = Math.imul(h ^ text.charCodeAt(i), 3432918353);
    h = (h << 13) | (h >>> 19);
  }
  return h >>> 0;
}

/** mulberry32 seeded from a string: returns a function giving floats in [0, 1). */
export function rng(seed) {
  let a = hash(String(seed));
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Deterministic picker for row `i` of a collection: pick(seed, i, list). */
export function pick(seed, i, list) {
  return list[Math.floor(rng(`${seed}:${i}`)() * list.length)];
}

/** Integer in [min, max] for row `i`. */
export function int(seed, i, min, max) {
  return min + Math.floor(rng(`${seed}:${i}`)() * (max - min + 1));
}

/** Builds `n` rows with `make(i, random)`; `random` is seeded per row. */
export function rows(seed, n, make) {
  return Array.from({ length: n }, (_, i) => make(i, rng(`${seed}:${i}`)));
}

/** Plausible Minecraft-store vocabulary. */
export const PLAYERS = [
  'Steve_the_Builder',
  'xX_CreeperSlayer_Xx',
  'AlexMiner99',
  'Notch_Fan_2011',
  'EnderQueen',
  'DiamondDave',
  'RedstoneWizard',
  'Herobrine_Was_Here',
  'PixelPaladin',
  'TheVeryLongPlayerNameOfSteve',
  'NetherNomad',
  'SkyblockSally',
];
export const PRODUCT_NAMES = [
  'VIP Rank',
  'VIP+ Rank (30 days)',
  'MVP Rank - Lifetime',
  'Starter Kit',
  'Legendary Crate Key x5',
  'Mythic Crate Key',
  'Fly Pass (permanent, all survival worlds and the creative plot server)',
  '5000 In-Game Coins',
  'Mob Spawner Pack',
  'Cosmetic Wings',
  'Pet: Baby Dragon',
  'Island Expansion',
  'Unban Appeal Token',
  'Custom Prefix',
];
export const CATEGORY_NAMES = [
  'Ranks',
  'Kits',
  'Crates and Keys',
  'Cosmetics',
  'Coins',
  'Pets',
  'Perks and Passes',
  'Seasonal - Halloween Special Event Bundle',
];
export const CURRENCIES = ['USD', 'EUR', 'TRY', 'GBP'];

/** Same text as the real endpoints build ids: stable, UUID-looking. */
export function uuidFor(seed, i) {
  const r = rng(`${seed}:uuid:${i}`);
  const h = () =>
    Math.floor(r() * 0xffffffff)
      .toString(16)
      .padStart(8, '0');
  const a = h() + h() + h() + h();
  return `${a.slice(0, 8)}-${a.slice(8, 12)}-4${a.slice(13, 16)}-8${a.slice(17, 20)}-${a.slice(20, 32)}`;
}

/** Query params of `?a=1&b=2` as an object (last value wins). */
export function queryOf(search) {
  return Object.fromEntries(new URLSearchParams(search));
}

/** Comma separated filter value to a Set; null / empty = no filter. */
export function csvSet(value) {
  if (value === null || value === undefined || value === '') return null;
  return new Set(String(value).split(',').filter(Boolean));
}

/** Case-insensitive "does any of the texts contain the needle". */
export function matches(needle, ...texts) {
  const n = String(needle || '')
    .trim()
    .toLowerCase();
  if (!n) return true;
  return texts.some((t) =>
    String(t ?? '')
      .toLowerCase()
      .includes(n),
  );
}

/** The error envelope of doc 04 section 3: `{ error: { code, message?, details?, fields? } }`. No `result` key. */
export function failure(code, { message, details, fields } = {}) {
  const error = { code };
  if (message !== undefined) error.message = message;
  if (details !== undefined) error.details = details;
  if (fields !== undefined) error.fields = fields;
  return { error };
}

/** Core `Paging.MAX_SIZE`: the largest `pageSize` a list accepts unless its route sets a lower one. */
export const MAX_PAGE_SIZE = 100;

/**
 * The core page rule (Paging.parse / Paging.response, doc 04 section 4) on a list: `page` and `pageSize` are 1-based
 * integers (`pageSize` at most `maxSize`), a value outside the range is refused, never clamped (400 `INVALID_FIELDS`,
 * `fields: { page | pageSize: 'OUT_OF_RANGE' }`), a page past the last of a non-empty list is `PAGE_NOT_FOUND`, an empty
 * list is a normal answer with `totalPages: 0`.
 * Returns `{ rows, page: { number, size, totalItems, totalPages } }`, or `{ failure }` holding the envelope.
 */
export function paginate(items, query, defaultPageSize = 10, maxSize = MAX_PAGE_SIZE) {
  const whole = (value) => {
    const text = String(value).trim();
    return /^[+-]?\d+$/.test(text) ? Number(text) : null;
  };
  const number = query.page === undefined || query.page === '' ? 1 : whole(query.page);
  const size =
    query.pageSize === undefined || query.pageSize === '' ? defaultPageSize : whole(query.pageSize);
  const fields = {};
  if (number === null || number < 1) fields.page = 'OUT_OF_RANGE';
  if (size === null || size < 1 || size > maxSize) fields.pageSize = 'OUT_OF_RANGE';
  if (Object.keys(fields).length) return { failure: failure('INVALID_FIELDS', { fields }) };

  const totalItems = items.length;
  const totalPages = Math.ceil(totalItems / size);
  if (number > 1 && number > totalPages) return { failure: failure('PAGE_NOT_FOUND') };
  return {
    rows: items.slice((number - 1) * size, number * size),
    page: { number, size, totalItems, totalPages },
  };
}

/**
 * Wraps a paginate() result as a list answer: `{ items, page, ...extra }` (the extras are top-level keys beside the list,
 * as `Paging.response` takes them), or the failure envelope.
 */
export function listBody(items, query, defaultPageSize = 10, extra = {}, maxSize = MAX_PAGE_SIZE) {
  const p = paginate(items, query, defaultPageSize, maxSize);
  if (p.failure) return p.failure;
  return { items: p.rows, page: p.page, ...extra };
}

/** 20 alphanumeric characters, the shape of a real order public id. */
export function publicIdFor(seed, i) {
  const r = rng(`${seed}:public:${i}`);
  const chars = '0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ';
  return Array.from({ length: 20 }, () => chars[Math.floor(r() * chars.length)]).join('');
}
