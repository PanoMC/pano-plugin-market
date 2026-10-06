// Helpers of the panel scenarios 56 to 65 (13 section 25.4). They only use the HTTP API (as a person with a session would) and the browser; no SQL.
import { must } from '../../lib/api.mjs';
import { grantUserNode } from '../../lib/bootstrap.mjs';
import { newContext } from '../../lib/browser.mjs';
import { hydrated, panelOpen } from '../../lib/ui.mjs';

export const NODE_PREFIX = 'pano.plugin.pano-plugin-market.';
export const node = (suffix) => `${NODE_PREFIX}${suffix}`;
export const PANEL_ACCESS = 'pano.panel.access.panel';
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/** A registered account with panel access and exactly `suffixes` market nodes (e.g. 'view.market.orders'): the "role" of 56 / 57. */
export async function staffAccount({ buyer, admin }, label, suffixes) {
  const api = await buyer(label);
  await grantUserNode(admin, api.userId, PANEL_ACCESS);
  for (const suffix of suffixes) await grantUserNode(admin, api.userId, node(suffix));
  await api.post('/api/panel/dismissWhatsNew', { version: '1' });
  return api;
}

/** A browser context signed in as `api`, with its first page. */
export async function signedIn(browser, api, options = {}) {
  const pc = await newContext(browser, {
    viewport: 'desktop',
    cookies: api.playwrightCookies(),
    ...options,
  });
  const page = await pc.page();
  return { pc, page };
}

/** Opens a market panel route and waits for the layout to show `ready`; the default waits for the market section navigation. */
export async function openMarket(page, env, route, ready) {
  await panelOpen(page, env, route, ready);
}

/** Like openMarket, but returns the HTTP status of the navigation (the document the platform answered for the route). */
export async function openMarketStatus(page, env, route, ready) {
  const response = await page.goto(`${env.url}/panel${route}`, {
    waitUntil: 'domcontentloaded',
    timeout: 240000,
  });
  await hydrated(page);
  if (ready) await ready(page);
  return response?.status() ?? 0;
}

/** Polls `fn` until it returns a truthy value (or throws at the deadline with `what`). */
export async function waitFor(what, fn, { timeout = 30000, interval = 300 } = {}) {
  const deadline = Date.now() + timeout;
  let last;
  while (Date.now() < deadline) {
    last = await fn();
    if (last) return last;
    await sleep(interval);
  }
  throw new Error(`timed out waiting for ${what}`);
}

/** Text of the whole page body. */
export const bodyText = (page) => page.locator('body').innerText();

/** Waits until every Bootstrap modal is fully closed (no backdrop, none displayed): a fading modal still catches the pointer. */
export async function modalsClosed(page, timeout = 15000) {
  await page.waitForFunction(
    () =>
      document.querySelectorAll('.modal-backdrop').length === 0 &&
      [...document.querySelectorAll('.modal')].every((m) => getComputedStyle(m).display === 'none'),
    null,
    { timeout },
  );
}

/**
 * Sets store settings (a partial POST) and returns the function that puts the previous values back: settings are global state of the one
 * instance, so a scenario that changes them restores them in a `finally`.
 */
export async function settingsPatch(admin, changes) {
  const res = must(await admin.get('/api/panel/market/settings'), 'read the market settings');
  const before = res.json.settings ?? res.json;
  const restore = {};
  for (const key of Object.keys(changes)) if (key in before) restore[key] = before[key];
  must(await admin.post('/api/panel/market/settings', changes), 'change the market settings');

  return async () => {
    await admin.post('/api/panel/market/settings', restore);
  };
}

/** The console errors since `mark` that match `pattern` were provoked on purpose: exactly `count` must exist, and they leave the run's errors. */
export function provoked(pc, mark, pattern, label, count = 1) {
  const hits = pc.errors.slice(mark).filter((e) => pattern.test(e));
  if (hits.length !== count)
    throw new Error(
      `${label}: expected ${count} provoked console error(s) matching ${pattern}, got ${hits.length}: ${hits.join(' | ')}`,
    );
  for (const hit of hits) pc.errors.splice(pc.errors.indexOf(hit), 1);
}

/**
 * The panel of the local panel-ui checkout (`e2e-instance.sh start --ui external:<theme>,<panel>`), or null. The panel the platform serves is the one
 * bundled in the jar (an older release); the checkout is the host the design guidelines and the X-9 menu API are written against.
 */
export const devPanelBase = (env) => (env.panelUrl ? `${env.panelUrl}/panel` : null);

/**
 * A vite dev port opened directly bounces the browser into the Pano dev server (the SDK's dev-only checkDomainRedirection assumes that server proxies
 * vite; the isolated instance serves the bundled panel instead). The bounce is a document navigation to the instance: answer it with 204 (no
 * navigation), the dev page stays and talks to the instance through vite's own API proxy.
 */
export async function blockDevBounce(pc, env) {
  await pc.context.route(`${env.url}/panel/**`, (route) =>
    route.request().isNavigationRequest() ? route.fulfill({ status: 204 }) : route.continue(),
  );
}

/** Opens `route` below `base` ("<url>/panel" or devPanelBase) and waits until the host booted and `ready(page)` holds. */
export async function openAt(page, base, route, ready) {
  await page.goto(`${base}${route}`, { waitUntil: 'domcontentloaded', timeout: 240000 });
  await hydrated(page);
  if (ready) await ready(page);
}

/**
 * Waits until the page refresh that follows a modal-driven save has finished. A modal now hides first and the page refreshes when it is gone
 * (hide-then.js), so a scenario that navigates away right after `modalsClosed` would abort that refresh and the browser would log "Failed to fetch".
 */
export async function refreshSettled(page) {
  await page.waitForLoadState('networkidle', { timeout: 10000 }).catch(() => {});
  await sleep(500);
}
