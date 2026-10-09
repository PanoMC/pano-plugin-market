// Headless Chromium for the browser scenarios (17 section 10): Playwright pinned in package.json to the version whose Chromium build is in
// ~/.cache/ms-playwright, else the system Chromium. Every page of a context records its console errors and failed page requests.
import fs from 'node:fs';
import { chromium } from 'playwright';

const livePages = new Set();

/** Screenshots of every page still open when a scenario failed, into `dir` (default build/e2e-browser); returns the files. */
export async function captureFailure(
  name,
  dir = process.env.E2E_BROWSER_SHOTS || 'build/e2e-browser',
) {
  const files = [];
  fs.mkdirSync(dir, { recursive: true });
  let n = 0;

  for (const page of [...livePages]) {
    try {
      const file = `${dir}/${name.replace(/[^A-Za-z0-9_.-]+/g, '_')}-${n++}.png`;
      await page.screenshot({ path: file, fullPage: false, timeout: 10000 });
      // the markup next to the image: a selector that no longer matches is told apart from a page that did not render
      fs.writeFileSync(file.replace(/\.png$/, '.html'), await page.content());
      files.push(`${file} (${page.url()})`);
    } catch {
      /* the page is gone */
    }
  }

  return files;
}

export const VIEWPORTS = {
  mobile: { width: 390, height: 844 },
  desktop: { width: 1280, height: 800 },
};
export const LOCALES = { tr: 'tr', 'en-US': 'en-US', ru: 'ru' };

export async function launch() {
  try {
    return await chromium.launch({ headless: true });
  } catch (e) {
    if (!fs.existsSync('/usr/bin/chromium')) throw e;
    return chromium.launch({ headless: true, executablePath: '/usr/bin/chromium' });
  }
}

// Messages that are the browser's own noise or the dev server's, not the plugin's: nothing else is ignored.
// `<locale>.plugins.json` is the engine's optional plugin-text layer (a theme without plugin texts has none, the engine reads a 404 as empty).
// MARKET_E2E_IGNORE_CONSOLE (a regular expression, empty by default) is for an advisory run that has to see past a known failure of
// another repo; the gate never sets it.
const IGNORED_CONSOLE = [
  /\[vite\]/i,
  /favicon/i,
  /Download the Svelte DevTools/i,
  // `GET /auth/csrf` answers 401 to a visitor without a cookie session on purpose (doc 05 section 4: "the client just logs in", the engine
  // reads it as no token); Chromium still prints the failed load. Only that one URL and status.
  /Failed to load resource: the server responded with a status of 401.*\[[^\]]*\/api\/v1\/auth\/csrf\]/,
  /Failed to load resource.*\/theme-api\/languages\/[^/\]]+\.plugins\.json/,
  ...(process.env.MARKET_E2E_IGNORE_CONSOLE
    ? [new RegExp(process.env.MARKET_E2E_IGNORE_CONSOLE)]
    : []),
];

/**
 * Where the cookies plugin is installed (the bundle gate), its fixed banner covers the bottom of the page and intercepts clicks. A
 * locator handler accepts it whenever it is visible before an action; without the plugin the locator never matches and nothing happens.
 * Every page of every context gets it (`newContext`), so every scenario's page setup shares this one helper.
 */
export async function acceptCookieBanner(page) {
  await page
    .addLocatorHandler(
      page.locator('.cookies-cookie-banner [class*="cookies-cookie-banner__action"]').first(),
      async (button) => {
        await button.click();
      },
    )
    .catch(() => {});
}

/**
 * A context of one visitor: viewport, locale (sent as Accept-Language, which the theme reads), optional session cookies. Console errors
 * and uncaught page errors of every page land in `ctx.errors`; `ctx.expectNoErrors(label)` asserts that none appeared.
 */
export async function newContext(
  browser,
  { viewport = 'desktop', locale = 'en-US', cookies = [], colorScheme = 'light' } = {},
) {
  const context = await browser.newContext({
    viewport: VIEWPORTS[viewport] ?? viewport,
    locale: LOCALES[locale] ?? locale,
    colorScheme,
    ignoreHTTPSErrors: true,
  });

  if (cookies.length) await context.addCookies(cookies);

  const errors = [];

  context.on('page', (page) => {
    livePages.add(page);
    acceptCookieBanner(page);
    page.on('close', () => livePages.delete(page));
    page.on('console', (message) => {
      if (message.type() !== 'error') return;
      // "Failed to load resource: ... status of 404" names no URL; the message location does
      const url = /^Failed to load resource/.test(message.text()) ? message.location()?.url : '';
      const text = url ? `${message.text()} [${url}]` : message.text();
      if (IGNORED_CONSOLE.some((re) => re.test(text))) return;
      errors.push(`console.error on ${page.url()}: ${text}`.slice(0, 600));
    });
    page.on('pageerror', (error) =>
      errors.push(`pageerror on ${page.url()}: ${String(error?.message || error)}`.slice(0, 600)),
    );
  });

  return {
    context,
    errors,
    async page() {
      return context.newPage();
    },
    expectNoErrors(label) {
      if (errors.length)
        throw new Error(`${label}: ${errors.length} console error(s):\n  ${errors.join('\n  ')}`);
    },
    close: () => context.close(),
  };
}

/** Switches the Bootstrap colour mode of the page ("light" | "dark") the way the theme's own attribute does. */
export async function setColorMode(page, mode) {
  await page.evaluate((m) => document.documentElement.setAttribute('data-bs-theme', m), mode);
}
