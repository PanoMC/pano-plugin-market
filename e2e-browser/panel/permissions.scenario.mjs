// Scenarios 56 and 57 of 13 section 25.4: what a restricted staff account sees, and that the legacy umbrella node still opens everything.
import { assert, assertEqual } from '../lib/ui.mjs';
import { bodyText, openMarket, signedIn, staffAccount, waitFor } from './lib/panel.mjs';
import { must } from '../lib/api.mjs';
import { mixedOrder } from './lib/orders.mjs';

const NO_PERMISSION = 'You do not have permission to do this.';
const ready = (p) => p.locator('.nav, nav').first().waitFor({ timeout: 60000 });

/** Static (parameter free) page routes of src/panel/register.js: the umbrella node must open each one. */
export const PAGES = [
  '/market',
  '/market/orders',
  '/market/deliveries',
  '/market/shipments',
  '/market/subscriptions',
  '/market/payment-events',
  '/market/products',
  '/market/products/create-product',
  '/market/categories',
  '/market/comparisons',
  '/market/comparisons/create-comparison',
  '/market/goals',
  '/market/discounts',
  '/market/gifts',
  '/market/credits',
  '/market/blocks',
  '/market/orders/create-order',
  '/market/settings',
  '/market/settings/shipping-method',
];

export const scenarios = [
  {
    id: 'PANEL-56',
    title:
      'permissions: only view.market.orders sees Overview + Orders, settings is refused, no actions menu, GET /context 200 but GET /settings 403',
    async run(ctx) {
      const { browser, env, admin, catalogue } = ctx;
      const cat = await catalogue();
      const order = await mixedOrder(ctx, cat);
      const api = await staffAccount(ctx, 'p56', ['view.market.orders']);
      const { pc, page } = await signedIn(browser, api);

      // the API half of the scenario: the context is readable with any market node, the settings need their own node
      assertEqual(
        (await api.get('/api/panel/market/context')).status,
        200,
        'PANEL-56: GET /context succeeds',
      );
      assertEqual(
        (await api.get('/api/panel/market/settings')).status,
        403,
        'PANEL-56: GET /settings is 403',
      );

      // Overview + Orders (and nothing else) in the area navigation
      await openMarket(page, env, '/market/orders', (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );
      const areas = await page
        .locator('a[href*="/panel/market"]')
        .evaluateAll((els) => els.map((e) => e.getAttribute('href')));
      const hrefs = new Set(areas.map((h) => h.replace(/^.*\/panel/, '')));
      assert(hrefs.has('/market'), 'PANEL-56: the Overview area is linked');
      assert(hrefs.has('/market/orders'), 'PANEL-56: the Orders area is linked');
      for (const hidden of ['/market/products', '/market/discounts', '/market/credits'])
        assert(
          ![...hrefs].some((h) => h.startsWith(hidden)),
          `PANEL-56: no link to ${hidden} for a view-only account`,
        );
      assertEqual(
        [...hrefs].filter((h) => h.startsWith('/market/settings')).length,
        0,
        'PANEL-56: no link into the Settings area',
      );

      // /market/settings is refused: the host answers the route, the page renders no settings and says why
      await openMarket(page, env, '/market/settings');
      await waitFor('the permission notice on /market/settings', async () =>
        (await bodyText(page)).includes(NO_PERMISSION),
      );
      const settingsText = await bodyText(page);
      assert(
        !settingsText.includes('Payment Methods') && !settingsText.includes('Test Mode'),
        'PANEL-56: /market/settings renders no settings',
      );

      // the order detail carries no actions menu: no page-level "Actions" toggle (the host's own header menus are not the page's), and the
      // only entry left in the cards' menus is the read-only "Events" of a payment attempt
      await openMarket(page, env, `/market/orders/detail/${order.number}`, (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );
      const pageToggle = '[data-bs-toggle="dropdown"][aria-label="Actions"]:not(.card *)';
      const cardItems = '.card .dropdown-menu .dropdown-item';
      assertEqual(
        await page.locator(pageToggle).count(),
        0,
        'PANEL-56: the order detail has no page-level actions menu',
      );
      const items = (await page.locator(cardItems).allInnerTexts()).map((t) => t.trim());
      assertEqual(
        JSON.stringify([...new Set(items)]),
        JSON.stringify(['Events']),
        'PANEL-56: the card menus offer only the read-only Events entry',
      );

      // positive control of the selectors: the admin's order detail does have them
      const full = await signedIn(browser, admin);
      await openMarket(full.page, env, `/market/orders/detail/${order.number}`, (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );
      assert(
        (await full.page.locator(pageToggle).count()) >= 1,
        'PANEL-56: control: the admin order detail has the page-level actions menu',
      );
      assert(
        (await full.page.locator(cardItems).count()) > items.length,
        'PANEL-56: control: the admin card menus offer more than the view-only account',
      );
      await full.pc.close();

      pc.expectNoErrors('PANEL-56');
      await pc.close();
    },
  },
  {
    id: 'PANEL-57',
    title: 'permissions: a holder of only the legacy umbrella node opens every page',
    async run(ctx) {
      const { browser, env } = ctx;
      const api = await staffAccount(ctx, 'p57', ['manage.market']);
      const { pc, page } = await signedIn(browser, api);

      for (const route of PAGES) {
        await openMarket(page, env, route, ready);
        await page.waitForTimeout(1200);
        const text = await bodyText(page);
        assert(
          !text.includes(NO_PERMISSION),
          `PANEL-57: ${route} says the account lacks permission`,
        );
        assert(!text.includes('Could Not Load Data'), `PANEL-57: ${route} could not load its data`);
      }

      // the umbrella node also reaches the API behind the settings page
      assertEqual(
        (await api.get('/api/panel/market/settings')).status,
        200,
        'PANEL-57: GET /settings',
      );
      must(await api.get('/api/panel/market/orders'), 'PANEL-57: GET /orders');
      pc.expectNoErrors('PANEL-57');
      await pc.close();
    },
  },
];
