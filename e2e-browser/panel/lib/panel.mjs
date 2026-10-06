// Helpers of the panel scenarios 56 to 65 (13 section 25.4). They only use the HTTP API (as a person with a session would) and the browser; no SQL.
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
