// Small helpers shared by the scenarios. English labels are used for selectors unless a scenario is about the language itself.
import { setColorMode } from './browser.mjs';

export const LABEL = {
  addToCart: 'Add to Cart',
  checkout: 'Checkout',
  cartTitle: 'Your cart',
};

export function assert(condition, message) {
  if (!condition) throw new Error(message);
}

export function assertEqual(actual, expected, message) {
  if (actual !== expected)
    throw new Error(
      `${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`,
    );
}

/** Waits until the page is hydrated (the host sets this flag in hooks.client once the app booted). */
export async function hydrated(page, timeout = 60000) {
  await page.waitForFunction(() => window.__PANO_APP_BOOTED__ === true, null, { timeout });
}

export async function open(page, url, ready) {
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 240000 });
  await hydrated(page);
  if (ready) await ready(page);
}

export async function noHorizontalScroll(page, label) {
  const { overflow, culprits } = await page.evaluate(() => {
    const overflow = document.documentElement.scrollWidth - window.innerWidth;
    const culprits = [];

    if (overflow > 1)
      for (const el of document.querySelectorAll('body *')) {
        const r = el.getBoundingClientRect();

        if (r.width > 0 && r.right > window.innerWidth + 1 && !el.closest('.offcanvas:not(.show)'))
          culprits.push(
            `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}.${String(
              el.className?.baseVal ?? el.className,
            )
              .trim()
              .split(/\s+/)
              .slice(0, 3)
              .join('.')} right=${Math.round(r.right)}`,
          );
      }

    return { overflow, culprits: culprits.slice(0, 6) };
  });
  assert(
    overflow <= 1,
    `${label}: the page scrolls horizontally by ${overflow}px (${culprits.join(' | ')})`,
  );
}

export async function bodyBackground(page) {
  return page.evaluate(() => getComputedStyle(document.body).backgroundColor);
}

/**
 * Text nodes that are a raw i18n key (17 section 10 UI-10): the whole text is a dotted key whose first segment is one of the namespaces of
 * the plugin's locale files or of a plugin text (`plugins.pano-plugin-market.*`). Host names such as `play.example.com` are not keys.
 */
export async function rawKeys(page) {
  return page.evaluate(() => {
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const found = [];
    const key =
      /^(?:theme|pages|modals|common|settings|components|errors|plugins|permissions|notifications|activity-logs|nav-store)\.[a-z0-9-]+(?:\.[A-Za-z0-9_-]+)+$/;
    let node;

    while ((node = walker.nextNode())) {
      const parent = node.parentElement;
      if (!parent || ['SCRIPT', 'STYLE', 'NOSCRIPT'].includes(parent.tagName)) continue;
      const text = node.textContent.trim();
      if (key.test(text)) found.push(text.slice(0, 120));
    }

    return found;
  });
}

/** A panel page of the instance (served by the platform at /panel), opened as the signed-in user of the context. */
export async function panelOpen(page, env, route, ready) {
  await page.goto(`${env.url}/panel${route}`, { waitUntil: 'domcontentloaded', timeout: 240000 });
  await hydrated(page);
  if (ready) await ready(page);
}

/** Text of a page that still holds an unreplaced ICU / `{name}` placeholder of a translation. */
export async function rawPlaceholders(page) {
  return page.evaluate(() => {
    const text = document.body.innerText;
    return [...text.matchAll(/\{[a-zA-Z]+(?:, (?:plural|select)[^}]*)?\}/g)]
      .map((m) => m[0])
      .slice(0, 5);
  });
}

export { setColorMode };
