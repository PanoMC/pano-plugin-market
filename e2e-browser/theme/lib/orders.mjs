// Helpers of the order page and profile scenarios 37 to 48 (14 section 20.3). Nothing here is a scenario: the runner skips every `lib` directory.
// Orders are made through the storefront API like a buyer would; the only writes to the instance database are the clock rewinds of `sql()`
// (an order's expiry, a subscription's status), the same "time travel by row rewind" the Kotlin E2E classes use (17 section 9.6).
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { Api, listOf, must, MARKET_API, PANEL_MARKET_API } from '../../lib/api.mjs';
import { completePayment } from '../../lib/gateway.mjs';
import { assert } from '../../lib/ui.mjs';
import { text } from './checkout.mjs';

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** Polls `condition()` (sync or async) every 250 ms until it is true; fails with `label` after `timeout` ms. */
export async function waitUntil(condition, timeout, label) {
  const end = Date.now() + timeout;

  while (Date.now() < end) {
    if (await condition()) return;
    await sleep(250);
  }

  throw new Error(`timed out waiting for ${label}`);
}

export const idem = () => ({ 'Idempotency-Key': crypto.randomUUID() });

/**
 * One SQL statement against the instance database (`MARKET_E2E_DB`, exported by e2e-instance.sh start, else `PANO_OF_SLOT_DB`), through the database container the way the
 * instance script does it. The password is only read from the environment, never printed. Returns the tab-separated rows of a SELECT as arrays.
 */
export function sql(statement) {
  const password = process.env.PANO_IT_MARIADB_PASSWORD;
  const db = process.env.MARKET_E2E_DB || process.env.PANO_OF_SLOT_DB;
  const container = process.env.MARKET_E2E_DB_CONTAINER || 'pano-web-platform-db-1';

  if (!password) throw new Error('PANO_IT_MARIADB_PASSWORD is not set');
  if (!db || !/^pano_market_e2e_[a-z0-9_]+$/.test(db))
    throw new Error(`refusing to write to ${db ?? '(no MARKET_E2E_DB)'}`);

  const run = spawnSync(
    'docker',
    ['exec', '-i', '-e', 'MYSQL_PWD', container, 'mariadb', '-uroot', '-N', '-B', db],
    { input: `${statement};\n`, encoding: 'utf8', env: { ...process.env, MYSQL_PWD: password } },
  );

  if (run.status !== 0) throw new Error(`sql failed: ${(run.stderr || '').slice(0, 300)}`);

  return run.stdout
    .split('\n')
    .filter((line) => line !== '')
    .map((line) => line.split('\t'));
}

/** The table prefix of the plugin in the instance database (`pano_market_` here), found once. */
let prefix = null;

export function table(name) {
  if (prefix === null) {
    const found = sql("SHOW TABLES LIKE '%market_order'")[0]?.[0];

    assert(found, 'the market tables exist in the instance database');
    prefix = found.replace(/market_order$/, '');
  }

  return `\`${prefix}market_${name}\``;
}

/** A checkout through the storefront API; returns `{ publicId, token, json }` (`token` is the guest's access token). */
export async function checkout(api, body) {
  const res = must(await api.post(`${MARKET_API}/checkout`, body, idem()), 'checkout');

  return {
    publicId: res.json.order.publicId,
    number: res.json.order.number,
    token: res.json.orderToken ?? null,
    json: res.json,
  };
}

/** The buyer-side view of an order through the API (`token` for a guest). */
export async function view(api, publicId, token) {
  const res = await api.get(
    `${MARKET_API}/orders/${publicId}`,
    token ? { 'X-Order-Token': token } : undefined,
  );

  return res.json?.order ?? null;
}

/** Polls the buyer view until `done(order)`; fails with the last state. */
export async function until(api, publicId, done, label = 'the order', { token, tries = 80 } = {}) {
  let last = null;

  for (let i = 0; i < tries; i++) {
    last = await view(api, publicId, token);
    if (last && done(last)) return last;
    await sleep(500);
  }

  throw new Error(`${label} did not reach the expected state (is ${last?.status})`);
}

/** A buyer's order paid at the fake gateway (`fake`, REDIRECT) and COMPLETED. */
export async function paidOrder(api, items, extra = {}) {
  const placed = await checkout(api, { items, paymentMethodId: 'fake', ...extra });
  const url = placed.json.payment?.url;

  assert(url, 'the order has a gateway page to pay at');
  await completePayment(url);
  await until(api, placed.publicId, (o) => o.status === 'COMPLETED', 'the paid order');

  return placed;
}

/** The numeric id of an order (the panel routes take it), found through the panel list by public id. */
export async function orderRowId(admin, publicId) {
  const list = must(
    await admin.get(`${PANEL_MARKET_API}/orders?search=${encodeURIComponent(publicId)}&page=1`),
    `panel order search ${publicId}`,
  ).json;
  const row =
    listOf(list, 'orders').find((o) => o.publicId === publicId) ?? listOf(list, 'orders')[0];

  assert(row, `the panel lists the order ${publicId}`);

  return row.id;
}

/** The same order as the panel shows it (items with their numeric ids, refunds, ...). */
export async function panelDetail(admin, publicId) {
  const id = await orderRowId(admin, publicId);

  return must(await admin.get(`${PANEL_MARKET_API}/orders/${id}`), `panel order ${id}`).json;
}

/** Runs `fn` with the store out of test mode (widgets only count real sales), puts it back afterwards and waits for the settings cache. */
export async function withLiveStore(admin, fn) {
  must(await admin.post(`${PANEL_MARKET_API}/settings`, { testMode: false }), 'test mode off');
  await sleep(1500);

  try {
    return await fn();
  } finally {
    must(await admin.post(`${PANEL_MARKET_API}/settings`, { testMode: true }), 'test mode on');
    await sleep(1500);
  }
}

/** A fresh anonymous API client (a guest). */
export const guestApi = (env, label = 'guest') => new Api(env.url, label);

/** A regular expression for the English text of `key` in which every `{placeholder}` matches anything (a date, a count, a countdown). */
export function likeRe(key) {
  const escaped = text(key).replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

  return new RegExp(escaped.replace(/\\\{\w+\\\}/g, '.+'));
}
