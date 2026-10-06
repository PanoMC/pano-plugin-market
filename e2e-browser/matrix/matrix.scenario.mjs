// E2E-20: the theme matrix (14 section 20.4, 17 section 10): vanilla, blaze, blocky, frost, banana as the active theme x light / dark x
// 360 / 768 / 1280 px. Per theme one scenario `MX-<theme>`: UI-01 to UI-03 of the smoke run against that theme, then the pages of 14 section 20.4
// (store, a product with variants, the open cart, checkout with shipping, an order awaiting payment and a paid one, profile purchases, the
// home sidebar widgets) are screenshotted in both colour modes at the three widths, with these checks on every one of them:
// no horizontal scroll, no unreplaced translation placeholder / raw key, no element in the browser-default look, no hydration warning, no
// console error; the cart button and the offcanvas are exercised at every width.
//
// The scenarios only exist when MARKET_E2E_MATRIX lists the themes (`name=url,name=url`, see matrix/themes.sh): each url is a `vite dev` of that
// theme checkout whose API calls are proxied to the instance. Screenshots go to MARKET_E2E_MATRIX_SHOTS (default: the E2E-20 evidence folder).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { must } from '../lib/api.mjs';
import { newContext, setColorMode } from '../lib/browser.mjs';
import {
  assert,
  assertEqual,
  noHorizontalScroll,
  open,
  rawKeys,
  rawPlaceholders,
} from '../lib/ui.mjs';
import { scenarios as smoke } from '../smoke/theme.scenario.mjs';
import { payBuyer, withBankTransfer } from '../theme/lib/checkout.mjs';
import { product as plainProduct, storedSlug } from '../theme/lib/helpers.mjs';
import { cartButton } from '../theme/lib/helpers.mjs';
import { checkout, orderRowId, sleep, until, withLiveStore } from '../theme/lib/orders.mjs';
import { withShipping } from '../theme/lib/shipping.mjs';

const here = path.dirname(fileURLToPath(import.meta.url));
const SHOTS =
  process.env.MARKET_E2E_MATRIX_SHOTS ||
  path.resolve(here, '../../../../../pano-market-plugin-spec/evidence/E2E-20');

export const WIDTHS = [360, 768, 1280];
export const MODES = ['light', 'dark'];
/** blaze hard-codes data-bs-theme="dark" in app.html and has no colour-mode switch: dark is its only mode (recorded in the E2E-20 evidence). */
export const THEME_MODES = { blaze: ['dark'] };
export const modesOf = (theme) => THEME_MODES[theme] ?? MODES;
let activeModes = MODES;

/** `vanilla=http://127.0.0.1:18511,blaze=...` -> [{ name, url }] */
export function parseThemes(value) {
  return (value ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((entry) => {
      const at = entry.indexOf('=');

      if (at < 1) throw new Error(`MARKET_E2E_MATRIX entry "${entry}" is not name=url`);

      return { name: entry.slice(0, at), url: entry.slice(at + 1).replace(/\/$/, '') };
    });
}

/**
 * The elements that still look like the browser's defaults although the theme's stylesheet is loaded: a button with an outset border, a text
 * field with an inset border, a link in the default link blue, text in the default serif. Returns short descriptions (empty = styled).
 */
export async function unstyled(page) {
  return page.evaluate(() => {
    const found = [];
    const visible = (el) => {
      const r = el.getBoundingClientRect();
      const cs = getComputedStyle(el);

      return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none';
    };
    const label = (el) =>
      `${el.tagName.toLowerCase()}${el.id ? `#${el.id}` : ''}${typeof el.className === 'string' && el.className ? `.${el.className.trim().split(/\s+/).join('.')}` : ''} "${(el.innerText || el.value || '').trim().slice(0, 30)}"`;

    for (const el of document.querySelectorAll('button, input, select, textarea, a')) {
      if (!visible(el)) continue;
      const cs = getComputedStyle(el);
      const type = el.getAttribute('type');

      if (el.tagName === 'BUTTON' && cs.borderTopStyle === 'outset')
        found.push(`default button ${label(el)}`);
      else if (
        ['INPUT', 'SELECT', 'TEXTAREA'].includes(el.tagName) &&
        !['checkbox', 'radio', 'hidden', 'range', 'file'].includes(type) &&
        cs.borderTopStyle === 'inset'
      )
        found.push(`default field ${label(el)}`);
      else if (el.tagName === 'A' && el.hasAttribute('href') && cs.color === 'rgb(0, 0, 238)')
        found.push(`default link ${label(el)}`);
    }

    for (const el of document.querySelectorAll('main *, #app *, body > div *')) {
      if (
        !el.childNodes.length ||
        ![...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())
      )
        continue;
      if (!visible(el)) continue;
      if (/^["']?Times New Roman/i.test(getComputedStyle(el).fontFamily)) {
        found.push(`default serif ${label(el)}`);
        if (found.length > 12) break;
      }
    }

    if (document.styleSheets.length === 0) found.push('no stylesheet at all');

    return found.slice(0, 12);
  });
}

/**
 * A browser that (1) switches the dev-only domain bounce of the theme off (opening a vite port directly would bounce to the instance's own
 * bundled theme, see TH-47) and (2) records the hydration warnings of every page into `warnings`.
 */
export function matrixBrowser(browser, warnings) {
  return {
    async newContext(options) {
      const context = await browser.newContext(options);
      let patched = 0;

      await context.route(/variables\.js/, async (route) => {
        const response = await route.fetch();
        const source = await response.text();
        const body = source.replace(
          'export function checkDomainRedirection() {',
          'export function checkDomainRedirection() { return;',
        );

        if (body !== source) patched++;
        await route.fulfill({ response, body });
      });
      context.matrixPatched = () => patched;
      context.on('page', (page) =>
        page.on('console', (message) => {
          if (!['warning', 'error'].includes(message.type())) return;
          const text = message.text();

          if (/hydrat|mismatch/i.test(text))
            warnings.push(`${message.type()} on ${page.url()}: ${text}`.slice(0, 400));
        }),
      );

      return context;
    },
  };
}

function shot(theme) {
  const dir = path.join(SHOTS, theme);

  fs.mkdirSync(dir, { recursive: true });

  return async (page, name, mode, width) => {
    await page.screenshot({
      path: path.join(dir, `${name}-${mode}-${width}.jpg`),
      type: 'jpeg',
      quality: 60,
      fullPage: true,
      timeout: 30000,
    });
  };
}

/** Light then dark: the screenshot and the per-mode checks of one page at one width. */
async function inBothModes(page, label, name, width, take, extra) {
  const paint = {};

  for (const mode of activeModes) {
    // a single-mode theme must come up in that mode by itself, before anything switches it
    if (activeModes.length === 1)
      assertEqual(
        await page.evaluate(() => document.documentElement.getAttribute('data-bs-theme')),
        mode,
        `${label}: the theme starts in its only mode`,
      );
    await setColorMode(page, mode);
    await page.waitForTimeout(250);
    assertEqual(
      await page.evaluate(() => document.documentElement.getAttribute('data-bs-theme')),
      mode,
      `${label} ${mode}: data-bs-theme`,
    );
    await noHorizontalScroll(page, `${label} ${mode}`);
    assertEqual(
      JSON.stringify(await rawPlaceholders(page)),
      '[]',
      `${label} ${mode}: no unreplaced translation placeholder`,
    );
    assertEqual(JSON.stringify(await rawKeys(page)), '[]', `${label} ${mode}: no raw i18n key`);
    assertEqual(
      JSON.stringify(await unstyled(page)),
      '[]',
      `${label} ${mode}: no unstyled element`,
    );
    paint[mode] = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    if (extra) await extra(mode);
    await take(page, name, mode, width);
  }

  if (activeModes.length === 2)
    assert(paint.light !== paint.dark, `${label}: light and dark paint the page differently`);
}

async function exerciseCart(page, label, productName) {
  const button = await cartButton(page);

  await button.waitFor({ state: 'visible', timeout: 30000 });
  await button.click();

  const offcanvas = page.locator('#marketCartOffcanvas');

  await offcanvas.waitFor({ state: 'visible', timeout: 30000 });
  await offcanvas
    .getByText(productName)
    .first()
    .waitFor({ timeout: 30000 })
    .catch(async () => {
      const seen = await offcanvas.innerText().catch(() => '');

      throw new Error(
        `${label}: the offcanvas never lists ${productName}: ${JSON.stringify(seen.slice(0, 300))}`,
      );
    });
  await page.waitForTimeout(500);

  return offcanvas;
}

async function closeCart(page) {
  const offcanvas = page.locator('#marketCartOffcanvas');

  if (!(await offcanvas.isVisible().catch(() => false))) return;
  await offcanvas.getByRole('button', { name: /close/i }).first().click();
  await offcanvas.waitFor({ state: 'hidden', timeout: 15000 });
  await page.waitForTimeout(300);
}

/** Text compared the way CSS `text-transform: uppercase` leaves it (blaze and blocky upper-case headings and product names). */
const norm = (value) =>
  value
    .normalize('NFD')
    .replace(/\u0307/g, '')
    .toLowerCase()
    .replace(/\u0131/g, 'i');

/**
 * UI-01 of 17 section 10 for one theme: `/store` lists categories and products in en-US (visitor) and tr / ru (signed-in account), on a phone
 * and a desktop width. Same checks as the smoke scenario, but case-insensitive because some forks upper-case what the plugin renders.
 */
async function uiOne({ theme, browser, env, catalogue, buyer }) {
  const cat = await catalogue();
  const reader = await buyer('lang');
  const search = encodeURIComponent(cat.vip.name.split(' ').pop());

  for (const { locale, width, account } of [
    { locale: 'en-US', width: 390 },
    { locale: 'en-US', width: 1280 },
    { locale: 'tr', width: 390, account: true },
    { locale: 'ru', width: 1280, account: true },
  ]) {
    if (account)
      must(await reader.put('/api/profile', { localeCode: locale }), `switch to ${locale}`);

    const ctx = await newContext(browser, {
      locale,
      viewport: { width, height: 844 },
      cookies: account ? reader.playwrightCookies() : [],
    });
    const page = await ctx.page();
    const label = `${theme.name} UI-01 ${locale} ${width}px`;

    await open(page, `${env.url}/store?search=${search}`, (p) =>
      p.locator('.card h3 a').nth(2).waitFor({ timeout: 60000 }),
    );

    const text = norm(await page.locator('body').innerText());
    const heading = JSON.parse(
      fs.readFileSync(new URL(`../../src/locales/theme/${locale}.json`, import.meta.url), 'utf8'),
    ).theme.store['all-products'];
    const html = norm(await page.content());

    assert(text.includes(norm(heading)), `${label}: the store is in the language ("${heading}")`);
    assert(text.includes(norm(cat.vip.name)), `${label}: ${cat.vip.name} is listed`);
    assert(text.includes(norm(cat.crate.name)), `${label}: ${cat.crate.name} is listed`);
    assert(
      html.includes(norm(cat.cat.name)) && html.includes(norm(cat.other.name)),
      `${label}: both categories are in the category tree`,
    );

    for (const mode of activeModes) {
      await setColorMode(page, mode);
      await noHorizontalScroll(page, `${label} ${mode}`);
    }

    ctx.expectNoErrors(label);
    await ctx.close();
  }
}

/** One theme, every page, every width, both modes. */
async function pageMatrix({ theme, browser, env, admin, catalogue, buyer, warnings }) {
  const cat = await catalogue();
  const take = shot(theme.name);
  const tenv = { ...env, url: theme.url };

  await withLiveStore(admin, () =>
    withBankTransfer(admin, () =>
      withShipping(admin, async (shipping) => {
        const parcel = await plainProduct(admin, `Parcel ${theme.name}`, {
          price: '20.00',
          stock: 50,
          physical: 'true',
          weightGrams: '500',
        });
        // sales are of their own product: the home / store widgets list the bought product's name, and the smoke scenarios look a card up by the
        // name of the VIP product
        const sold = await plainProduct(admin, `Sold ${theme.name}`, { price: '5.00' });
        const account = await payBuyer(buyer, admin, `mx${theme.name}`);
        const other = await payBuyer(buyer, admin, `my${theme.name}`);
        const sale = async (api) => {
          const placed = await checkout(api, {
            items: [{ productId: sold.id, quantity: 1 }],
            paymentMethodId: 'bank-transfer',
          });

          return placed;
        };
        const approve = async (api, placed) => {
          must(
            await admin.post(
              `/api/panel/market/orders/${await orderRowId(admin, placed.publicId)}/bank-transfer`,
              { decision: 'APPROVE' },
            ),
            'approve the transfer',
          );
          await until(api, placed.publicId, (o) => o.status === 'COMPLETED', 'the paid sale');
        };
        const paid = await sale(account);

        await approve(account, paid);
        await approve(other, await sale(other));

        const awaiting = await sale(account);

        for (const patch of [
          { moduleRecentBuyers: true, moduleRecentBuyersShowAmount: false },
          { moduleTopSupporters: true, moduleStats: true, moduleGoal: false },
          { moduleSidebars: ['home'] },
        ]) {
          must(await admin.post('/api/panel/market/settings', patch), 'widget settings');
        }

        await sleep(1500);

        const parcelSlug = await storedSlug(admin, parcel.id);
        const crateSlug = await storedSlug(admin, cat.crate.id);

        for (const width of WIDTHS) {
          // store, product, cart and checkout run as a guest (local cart): the cart routes of a signed-in buyer still answer the unpriced
          // advice quote (open seam of E2E-16, CartJson), which would leave nameless rows in the offcanvas; orders and the profile need the account
          const viewport = { width, height: width >= 1280 ? 800 : 900 };
          const guestCtx = await newContext(matrixBrowser(browser, warnings), { viewport });
          const ctx = guestCtx;
          const page = await ctx.page();
          const at = (name) => `${theme.name} ${name} ${width}px`;
          const search = encodeURIComponent(cat.vip.name.split(' ').pop());

          // 1. the store
          await open(page, `${tenv.url}/store?search=${search}`, (p) =>
            p.locator('.card h3 a').nth(1).waitFor({ timeout: 90000 }),
          );
          assert(
            ctx.context.matrixPatched() > 0,
            `${at('store')}: the dev bounce of the theme was switched off`,
          );
          await inBothModes(page, at('store'), 'store', width, take);

          // 2. a product with variants
          await open(page, `${tenv.url}/store/${encodeURIComponent(crateSlug)}`, (p) =>
            p.locator('.fs-4').first().waitFor({ timeout: 60000 }),
          );
          await inBothModes(page, at('product'), 'product-variants', width, take);

          // 3. the cart: add the physical parcel, open the offcanvas from the navbar
          await open(page, `${tenv.url}/store/${encodeURIComponent(parcelSlug)}`, (p) =>
            p.locator('.fs-4').first().waitFor({ timeout: 60000 }),
          );
          await page.getByRole('button', { name: 'Add to Cart' }).first().click();
          await page.getByText('Added to cart.').first().waitFor({ timeout: 30000 });

          const offcanvas = await exerciseCart(page, at('cart'), parcel.name);

          assert(
            (await offcanvas.innerText()).includes(parcel.name),
            `${at('cart')}: the offcanvas lists the product`,
          );
          assert(
            (await offcanvas.getByRole('link', { name: 'Checkout' }).count()) > 0,
            `${at('cart')}: the offcanvas has its checkout link`,
          );
          await inBothModes(page, at('cart'), 'cart-offcanvas', width, take);
          await closeCart(page);

          // 4. checkout with shipping
          await open(page, `${tenv.url}/store/checkout`, (p) =>
            p.locator('#market-checkout-summary-card').waitFor({ timeout: 120000 }),
          );
          await page.locator('#market-checkout-shipping-country').selectOption('DE');
          for (const [field, value] of [
            ['firstName', 'Ada'],
            ['lastName', 'Lovelace'],
            ['phone', '+4915112345678'],
            ['city', 'Berlin'],
            ['line1', 'Unter den Linden 1'],
            ['postalCode', '10117'],
          ]) {
            const input = page.locator(`#market-checkout-shipping-${field}`);

            if (await input.count()) await input.fill(value);
          }
          await page.locator('#market-checkout-shipping-postalCode').blur();
          await page.getByText(`E2E Post ${shipping.n}`).first().waitFor({ timeout: 60000 });
          await page.waitForTimeout(800);
          await inBothModes(page, at('checkout'), 'checkout-shipping', width, take);

          // 5. orders: awaiting payment (instructions) and paid, as the signed-in buyer
          const ownerCtx = await newContext(matrixBrowser(browser, warnings), {
            viewport,
            cookies: account.playwrightCookies(),
          });
          const owner = await ownerCtx.page();

          await open(owner, `${tenv.url}/store/order/${awaiting.publicId}`, (p) =>
            p.locator('[role="status"]').first().waitFor({ timeout: 60000 }),
          );
          assert(
            (await owner.locator('body').innerText()).includes('DE89370400440532013000'),
            `${at('order awaiting')}: the bank transfer instructions are shown`,
          );
          await inBothModes(owner, at('order awaiting'), 'order-awaiting-payment', width, take);

          await open(owner, `${tenv.url}/store/order/${paid.publicId}`, (p) =>
            p.locator('[role="status"]').first().waitFor({ timeout: 60000 }),
          );
          await owner.getByText('Payment received. Thank you!').first().waitFor({ timeout: 60000 });
          await inBothModes(owner, at('order paid'), 'order-paid-delivered', width, take);

          // 6. profile purchases
          await open(owner, `${tenv.url}/profile/purchases`, (p) =>
            p.getByRole('heading', { name: 'Purchases' }).first().waitFor({ timeout: 60000 }),
          );
          await inBothModes(owner, at('profile purchases'), 'profile-purchases', width, take);

          // 7. home sidebar widgets
          await open(owner, `${tenv.url}/`, (p) =>
            p.getByText('Recent buyers', { exact: true }).first().waitFor({ timeout: 60000 }),
          );
          await inBothModes(owner, at('home widgets'), 'home-widgets', width, take);

          ctx.expectNoErrors(at('matrix guest'));
          ownerCtx.expectNoErrors(at('matrix buyer'));
          await ctx.close();
          await ownerCtx.close();
        }
      }),
    ),
  );
}

export const scenarios = parseThemes(process.env.MARKET_E2E_MATRIX).map((theme) => ({
  id: `MX-${theme.name}`,
  title: `${theme.name} as the active theme: UI-01 to UI-03, then the page matrix x light / dark x ${WIDTHS.join(' / ')} px`,
  async run(ctx) {
    const warnings = [];

    activeModes = modesOf(theme.name);

    const env = { ...ctx.env, url: theme.url };
    const wrapped = { ...ctx, env, browser: matrixBrowser(ctx.browser, warnings) };

    await uiOne({
      theme,
      browser: wrapped.browser,
      env,
      catalogue: ctx.catalogue,
      buyer: ctx.buyer,
    });

    for (const id of ['UI-02', 'UI-03']) {
      const scenario = smoke.find((s) => s.id === id);

      await scenario.run(wrapped);
    }

    await pageMatrix({
      theme,
      browser: ctx.browser,
      env: ctx.env,
      admin: ctx.admin,
      catalogue: ctx.catalogue,
      buyer: ctx.buyer,
      warnings,
    });
    assertEqual(
      JSON.stringify(warnings),
      '[]',
      `${theme.name}: no hydration warning in the console`,
    );
  },
}));
