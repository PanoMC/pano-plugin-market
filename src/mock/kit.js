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

/**
 * Slices `items` for the request (`page`, `pageSize`) like Paging.totalPages of the backend:
 * returns { rows, count, totalPage }. A page past the end throws { error: 'PAGE_NOT_FOUND' }
 * (as the real endpoints do), page 1 of an empty list is fine.
 */
export function paginate(items, query, defaultPageSize = 20) {
  const pageSize = Math.max(1, parseInt(query.pageSize) || defaultPageSize);
  const page = Math.max(1, parseInt(query.page) || 1);
  const count = items.length;
  const totalPage = Math.max(1, Math.ceil(count / pageSize));
  if (page > totalPage) return { error: 'PAGE_NOT_FOUND', count, totalPage };
  return { rows: items.slice((page - 1) * pageSize, page * pageSize), count, totalPage };
}

/** Wraps a paginate() result: `{ result: 'ok', [key]: rows, [countKey]: count, totalPage }`. */
export function listBody(key, countKey, items, query, defaultPageSize = 20) {
  const p = paginate(items, query, defaultPageSize);
  if (p.error) return { result: 'error', error: p.error };
  return { result: 'ok', [key]: p.rows, [countKey]: p.count, totalPage: p.totalPage };
}
