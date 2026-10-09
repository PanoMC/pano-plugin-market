// Theme browser scenarios 11 to 15 of 14 section 20.3 (cart), vanilla theme. Ids TH-11 .. TH-15 are the numbers of the spec.
import fs from 'node:fs';
import { Api, must, MARKET_API, PANEL_MARKET_API } from '../lib/api.mjs';
import { newContext } from '../lib/browser.mjs';
import { BUYER_PASSWORD } from '../lib/bootstrap.mjs';
import { assert, assertEqual, open } from '../lib/ui.mjs';
import {
  CART_KEY,
  addFromCard,
  badgeCount,
  cartButton,
  cartCount,
  cartRaw,
  openProduct,
  product,
  reload,
  storedCart,
  verifiedBuyer,
  waitForCards,
} from './lib/helpers.mjs';

const text = (key) => {
  const en = JSON.parse(
    fs.readFileSync(new URL('../../src/locales/theme/en-US.json', import.meta.url), 'utf8'),
  );

  return key.split('.').reduce((node, part) => node[part], en);
};

/** Opens the cart offcanvas and waits until it shows `count` lines (the quote has arrived when the subtotal is a number). */
async function openCart(page) {
  await (await cartButton(page)).click();
  const offcanvas = page.locator('#marketCartOffcanvas');
  await offcanvas.waitFor({ state: 'visible', timeout: 30000 });

  return offcanvas;
}

export const scenarios = [
  {
    id: 'TH-11',
    title:
      'a guest cart survives a reload; a legacy [{productId, quantity}] value is migrated to the v2 format',
    async run({ browser, env, catalogue }) {
      const { vip, free } = await catalogue();
      const ctxB = await newContext(browser, { viewport: 'desktop' });
      const page = await ctxB.page();

      await addFromCard(page, env, vip, vip.name);
      await addFromCard(page, env, free, free.name);

      const before = await storedCart(page);
      assertEqual(before.v, 2, 'the cart is stored in the v2 format');
      assertEqual(before.items.length, 2, 'two lines are stored');
      assertEqual(await badgeCount(page), 2, 'the badge counts both products');

      await reload(page);
      assertEqual(await badgeCount(page), 2, 'the badge still counts both after a reload');
      assertEqual(
        JSON.stringify((await storedCart(page)).items.map((l) => l.productId).sort()),
        JSON.stringify([vip.id, free.id].sort()),
        'the same two products are stored after the reload',
      );

      const offcanvas = await openCart(page);
      await offcanvas.getByText(vip.name).first().waitFor({ timeout: 30000 });
      await offcanvas.getByText(free.name).first().waitFor({ timeout: 30000 });
      await ctxB.close();

      // a legacy value of an older theme build: a bare array
      const legacy = await newContext(browser, { viewport: 'desktop' });
      const old = await legacy.page();
      await open(old, `${env.url}/store?search=${encodeURIComponent(vip.name)}`, (p) =>
        waitForCards(p, 1),
      );
      await old.evaluate(
        ([key, id]) => localStorage.setItem(key, JSON.stringify([{ productId: id, quantity: 2 }])),
        [CART_KEY, vip.id],
      );
      await reload(old);

      const migrated = await storedCart(old);
      assertEqual(migrated?.v, 2, 'the legacy value was rewritten as version 2');
      assertEqual(migrated.items.length, 1, 'one line survived the migration');
      assertEqual(migrated.items[0].productId, vip.id, 'with the product id');
      assertEqual(migrated.items[0].quantity, 2, 'and the quantity');
      assertEqual(migrated.items[0].variantId, 0, 'and the normalised variant id');
      assertEqual(await badgeCount(old), 2, 'the badge shows the migrated quantity');

      const cart = await openCart(old);
      await cart.getByText(vip.name).first().waitFor({ timeout: 30000 });
      assert(
        (await cart.innerText()).includes('€20.00'),
        'the migrated line is priced (2 x €10.00)',
      );
      legacy.expectNoErrors('TH-11');
      await legacy.close();
    },
  },

  {
    id: 'TH-12',
    title: 'stock lowered in the panel: opening the cart clamps the quantity and shows the notice',
    async run({ browser, env, admin }) {
      const item = await product(admin, 'Limited Pick', { price: '3.00', stock: 5 });
      const ctxB = await newContext(browser, { viewport: 'desktop' });
      const page = await ctxB.page();

      await open(page, `${env.url}/store?search=${encodeURIComponent(item.name)}`, (p) =>
        waitForCards(p, 1),
      );
      await page.evaluate(
        ([key, id]) =>
          localStorage.setItem(
            key,
            JSON.stringify({
              v: 2,
              items: [
                { productId: id, variantId: 0, quantity: 3, fieldValues: {}, targetServerId: null },
              ],
            }),
          ),
        [CART_KEY, item.id],
      );
      await reload(page);
      assertEqual(await badgeCount(page), 3, 'the cart starts with 3 units');

      // the owner lowers the stock to 1 while the visitor has the page open
      must(
        await admin.post(`${PANEL_MARKET_API}/products/${item.id}/stock`, {
          mode: 'SET',
          value: 1,
        }),
        'lower the stock',
      );

      const offcanvas = await openCart(page);
      await offcanvas.getByText(text('theme.errors.QUANTITY_REDUCED')).waitFor({ timeout: 30000 });
      await page.waitForFunction(
        ([key]) => JSON.parse(localStorage.getItem(key)).items[0].quantity === 1,
        [CART_KEY],
        { timeout: 30000 },
      );

      // one unit left: the row shows the fixed quantity instead of the +/- control
      await offcanvas.getByText(text('theme.cart.quantity-short').replace('{count}', '1')).waitFor({
        timeout: 30000,
      });
      assertEqual(
        (await storedCart(page)).items[0].quantity,
        1,
        'the stored quantity is clamped to the stock',
      );
      assertEqual(await badgeCount(page), 1, 'the badge follows the clamp');
      ctxB.expectNoErrors('TH-12');
      await ctxB.close();
    },
  },

  {
    id: 'TH-13',
    title:
      'a guest with a cart signs in: lines merge into the server cart, the local key empties, a second device shows the same cart',
    async run({ browser, env, admin, catalogue, buyer }) {
      const { vip, free } = await catalogue();
      const account = await verifiedBuyer(buyer, admin, 'merge');
      const guest = await newContext(browser, { viewport: 'desktop' });
      const page = await guest.page();

      await addFromCard(page, env, vip, vip.name);
      await addFromCard(page, env, free, free.name);
      assertEqual((await storedCart(page)).items.length, 2, 'the guest has two lines');

      await open(page, `${env.url}/login?redirect=${encodeURIComponent('/store')}`);
      // the login is two steps: the account name, then the password
      await page.locator('#usernameOrEmail').fill(account.username);
      await page.locator('button[type="submit"]').click();
      await page.locator('#password').fill(BUYER_PASSWORD);
      await page.locator('button[type="submit"]').click();
      // the host theme of the bundled zip may not carry `login-return-url`: the sign-in then lands on the home page (or on /store when
      // it does); either way the buyer goes on to the store through the navbar, a client-side navigation with the session changed
      await page.waitForURL((url) => !url.pathname.startsWith('/login'), { timeout: 60000 });
      if (new URL(page.url()).pathname !== '/store') {
        await page.getByRole('link', { name: 'Store', exact: true }).first().click();
        await page.waitForURL('**/store', { timeout: 60000 });
      }
      await waitForCards(page, 1);

      // the merge ran: the browser key is empty, the server holds both lines
      await page.waitForFunction(
        ([key]) => {
          const raw = localStorage.getItem(key);
          return raw === null || JSON.parse(raw).items.length === 0;
        },
        [CART_KEY],
        { timeout: 60000 },
      );
      assert(
        (await cartRaw(page)) === null || (await storedCart(page)).items.length === 0,
        'the local cart key is emptied after the merge',
      );
      assertEqual(await badgeCount(page), 2, 'the badge counts the merged lines');

      const api = new Api(env.url, account.username);
      must(await api.login(account.username, BUYER_PASSWORD), 'second device login');
      const server = must(await api.get(`${MARKET_API}/me/cart`), 'server cart').json;
      const pairs = (server.cart?.items ?? server.items ?? [])
        .map((i) => [i.productId, i.quantity])
        .sort((x, y) => (x[0] < y[0] ? -1 : x[0] > y[0] ? 1 : 0));
      assertEqual(
        JSON.stringify(pairs),
        JSON.stringify(
          [
            [vip.id, 1],
            [free.id, 1],
          ].sort((x, y) => (x[0] < y[0] ? -1 : x[0] > y[0] ? 1 : 0)),
        ),
        'the server cart holds both products once each (a doubled merge fails here)',
      );

      // second device: a clean browser signed in as the same account
      const second = await newContext(browser, {
        viewport: 'desktop',
        cookies: api.playwrightCookies(),
      });
      const other = await second.page();
      await open(other, `${env.url}/store`, (p) => waitForCards(p, 1));
      assertEqual(await badgeCount(other), 2, 'the second device shows the same count');
      const offcanvas = await openCart(other);
      await offcanvas.locator('.list-group-item').nth(1).waitFor({ timeout: 30000 });
      assertEqual(
        await offcanvas.locator('.list-group-item').count(),
        2,
        'the second device lists both lines',
      );

      // The rows of a signed-in buyer carry the full quote (06 section 3: embedded in the cart answers): named rows, their price and the
      // subtotal are asserted unconditionally, so a backend that still answers the unpriced advice quote fails this scenario.
      const rowOf = (item) => offcanvas.locator('.list-group-item').filter({ hasText: item.name });
      await rowOf(vip).first().waitFor({ timeout: 30000 });
      await rowOf(free).first().waitFor({ timeout: 30000 });
      assertEqual(await rowOf(vip).count(), 1, 'the second device lists the paid product once');
      assertEqual(await rowOf(free).count(), 1, 'the second device lists the free product once');
      assert(
        (await rowOf(vip).first().innerText()).includes(`€${vip.price}`),
        `the paid row shows its price €${vip.price}`,
      );
      assert(
        (await rowOf(free).first().innerText()).includes(`€${free.price}`),
        `the free row shows its price €${free.price}`,
      );
      const subtotal = (Number(vip.price) + Number(free.price)).toFixed(2);
      const footer = offcanvas
        .locator('.fw-bold.fs-5')
        .filter({ hasText: text('theme.cart.subtotal') });
      await footer.waitFor({ timeout: 30000 });
      assert(
        (await footer.innerText()).includes(`€${subtotal}`),
        `the subtotal shows €${subtotal}: ${await footer.innerText()}`,
      );

      assertEqual(
        (await cartRaw(other)) === null || (await storedCart(other)).items.length === 0,
        true,
        'the second device keeps no local cart',
      );

      // the host's two-step sign-in form answers its first step with a 422 the browser logs; that is the host's, not the store's
      const own = guest.errors.filter((e) => !/\/login\?.*status of 422/.test(e));
      assertEqual(
        own.length,
        0,
        `TH-13 guest: no console error besides the sign-in step: ${own.join(' | ')}`,
      );
      second.expectNoErrors('TH-13 second device');
      await guest.close();
      await second.close();
      void admin;
    },
  },

  {
    id: 'TH-14',
    title:
      'a subscription meeting a non-empty cart asks to replace it; Cancel keeps the cart, Replace swaps it',
    async run({ browser, env, admin, catalogue, buyer }) {
      const { vip } = await catalogue();
      const sub = await product(admin, 'Monthly Pass', {
        price: '6.00',
        billingMode: 'SUBSCRIPTION',
        periodUnit: 'MONTH',
        periodCount: '1',
      });

      // a guest cannot subscribe: the product page offers the sign-in link instead of the button
      const visitor = await newContext(browser, { viewport: 'desktop' });
      const anon = await visitor.page();
      await openProduct(anon, env, sub.slug);
      assertEqual(
        await anon.getByRole('button', { name: text('theme.product.subscribe') }).count(),
        0,
        'no Subscribe button for a guest',
      );
      await anon
        .getByRole('link', { name: text('theme.product.sign-in-to-buy') })
        .waitFor({ timeout: 30000 });
      await visitor.close();

      // a signed-in buyer with one product in the cart
      const account = await buyer('sub');
      const ctxB = await newContext(browser, {
        viewport: 'desktop',
        cookies: account.playwrightCookies(),
      });
      const page = await ctxB.page();
      await addFromCard(page, env, vip, vip.name);

      const serverCart = async () => {
        const body = must(await account.get(`${MARKET_API}/me/cart`), 'server cart').json;

        return (body.cart?.items ?? body.items ?? []).map((i) => i.productId);
      };

      assertEqual(
        JSON.stringify(await serverCart()),
        JSON.stringify([vip.id]),
        'the account cart holds the product',
      );

      await openProduct(page, env, sub.slug);
      const modal = page.locator('#marketReplaceCartModal');
      const subscribe = page.getByRole('button', { name: text('theme.product.subscribe') });

      // Cancel keeps the cart
      await subscribe.click();
      await modal.waitFor({ state: 'visible', timeout: 30000 });
      await modal.getByText(text('theme.cart.replace-title')).waitFor();
      await modal.getByRole('button', { name: text('theme.common.cancel') }).click();
      await modal.waitFor({ state: 'hidden', timeout: 30000 });
      await page.waitForTimeout(500);
      assertEqual(
        JSON.stringify(await serverCart()),
        JSON.stringify([vip.id]),
        'Cancel leaves the cart as it was',
      );
      assertEqual(await badgeCount(page), 1, 'the badge still counts the original product');
      assertEqual(
        await page.getByText(text('theme.store.added-to-cart')).count(),
        0,
        'nothing was added',
      );

      // closing with the X is a cancel too
      await subscribe.click();
      await modal.waitFor({ state: 'visible', timeout: 30000 });
      await modal.locator('.btn-close').click();
      await modal.waitFor({ state: 'hidden', timeout: 30000 });
      assertEqual(
        JSON.stringify(await serverCart()),
        JSON.stringify([vip.id]),
        'closing the dialog leaves the cart as it was',
      );

      // Replace swaps the content for the subscription
      await subscribe.click();
      await modal.waitFor({ state: 'visible', timeout: 30000 });
      await modal.getByRole('button', { name: text('theme.cart.replace-confirm') }).click();
      await modal.waitFor({ state: 'hidden', timeout: 30000 });
      await page.getByText(text('theme.store.added-to-cart')).first().waitFor({ timeout: 30000 });
      assertEqual(
        JSON.stringify(await serverCart()),
        JSON.stringify([sub.id]),
        'Replace leaves only the subscription',
      );
      assertEqual(await badgeCount(page), 1, 'the badge counts the subscription');
      ctxB.expectNoErrors('TH-14');
      await ctxB.close();
    },
  },

  {
    id: 'TH-15',
    title:
      'the nav cart badge equals the cart count on a non-store page and is hidden at 0 outside /store',
    async run({ browser, env, admin, catalogue, buyer }) {
      const { vip, free } = await catalogue();

      // guest: the count comes from the browser cart
      const guest = await newContext(browser, { viewport: 'desktop' });
      const page = await guest.page();

      await open(page, `${env.url}/`);
      assertEqual(
        await page.getByRole('button', { name: text('theme.cart.title') }).count(),
        0,
        'an empty cart shows no cart button on the home page',
      );

      await addFromCard(page, env, vip, vip.name);
      await addFromCard(page, env, free, free.name);
      const stored = await storedCart(page);
      const expected = cartCount(stored);
      assertEqual(expected, 2, 'two units are in the stored cart');

      await open(page, `${env.url}/`);
      assertEqual(
        await badgeCount(page),
        expected,
        'the badge on the home page equals the cart count',
      );
      assertEqual(
        await page
          .getByRole('button', { name: text('theme.cart.title') })
          .first()
          .getAttribute('aria-controls'),
        'marketCartOffcanvas',
        'it opens the cart offcanvas',
      );

      // a second unit of the same product: the count follows the quantity, not the line count
      await open(page, `${env.url}/store?search=${encodeURIComponent(vip.name)}`, (p) =>
        waitForCards(p, 1),
      );
      await page
        .locator('.card', { hasText: vip.name })
        .first()
        .getByRole('button', { name: 'Add to Cart' })
        .click();
      await page.getByText(text('theme.store.added-to-cart')).first().waitFor({ timeout: 30000 });
      await open(page, `${env.url}/`);
      assertEqual(cartCount(await storedCart(page)), 3, 'three units are stored');
      assertEqual(await badgeCount(page), 3, 'the badge shows 3');

      // emptied: hidden outside /store, 0 on /store
      await open(page, `${env.url}/store`, (p) => waitForCards(p, 1));
      const offcanvas = await openCart(page);
      await offcanvas.getByRole('button', { name: text('theme.cart.clear') }).click();
      await page
        .locator('#marketClearCartModal')
        .getByRole('button', { name: text('theme.cart.clear') })
        .click();
      await offcanvas.getByText(text('theme.cart.empty')).waitFor({ timeout: 30000 });
      assertEqual(await badgeCount(page), 0, 'the badge reads 0 on /store after clearing');

      await open(page, `${env.url}/`);
      assertEqual(
        await page.getByRole('button', { name: text('theme.cart.title') }).count(),
        0,
        'the button is hidden on the home page once the cart is empty',
      );
      await open(page, `${env.url}/store`, (p) => waitForCards(p, 1));
      assertEqual(await badgeCount(page), 0, 'on /store the empty button is still there (0)');
      guest.expectNoErrors('TH-15 guest');
      await guest.close();

      // signed in: the count comes from one summary request, not from the browser
      const account = await buyer('nav');
      must(
        await account.post(`${MARKET_API}/me/cart/items`, {
          productId: vip.id,
          variantId: 0,
          quantity: 2,
          fieldValues: {},
          targetServerId: null,
        }),
        'add to the server cart',
      );

      const member = await newContext(browser, {
        viewport: 'desktop',
        cookies: account.playwrightCookies(),
      });
      const home = await member.page();
      let summaries = 0;
      home.on(
        'request',
        (r) => new URL(r.url()).pathname === `${MARKET_API}/me/summary` && summaries++,
      );
      await open(home, `${env.url}/`);
      assertEqual(
        await badgeCount(home),
        2,
        'the signed-in badge on the home page equals the server cart count',
      );
      assertEqual(summaries, 1, 'one summary request produced it');
      member.expectNoErrors('TH-15 member');
      await member.close();
      void admin;
    },
  },
];
