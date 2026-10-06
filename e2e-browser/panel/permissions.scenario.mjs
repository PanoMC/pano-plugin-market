// Scenarios 56 and 57 of 13 section 25.4: what a restricted staff account sees, and that the legacy umbrella node still opens everything.
import fs from 'node:fs';
import { assert, assertEqual } from '../lib/ui.mjs';
import {
  bodyText,
  openMarket,
  openMarketStatus,
  signedIn,
  staffAccount,
  waitFor,
} from './lib/panel.mjs';
import { must } from '../lib/api.mjs';
import { actions, grantCredits, grantUserNode, product } from '../lib/bootstrap.mjs';
import { awaitStatus, checkout, mixedOrder } from './lib/orders.mjs';
import { completePayment } from '../lib/gateway.mjs';

const PAY_NODE = 'pano.plugin.pano-plugin-market.manage.market.payments';
const NO_PERMISSION = 'You do not have permission to do this.';
const READY_TIMEOUT = 60000;
const text = (page, pattern) => page.getByText(pattern).first().waitFor({ timeout: READY_TIMEOUT });
const field = (page, selector) =>
  page.locator(selector).first().waitFor({ timeout: READY_TIMEOUT });

/**
 * Every page the plugin registers (src/panel/register.js), as { pattern, route(fixtures), ready(page, fixtures) }: `pattern` is the registered
 * path, `route` the URL the umbrella node opens, `ready` waits for an element only that page renders (a heading, a count line or a form field),
 * so a page that renders nothing, or is still loading, fails. Parameter pages need the fixtures PANEL-57 creates through the API.
 */
export const PAGES = [
  { pattern: '/market', route: () => '/market', ready: (p) => text(p, 'Recent Orders') },
  {
    pattern: '/market/orders',
    route: () => '/market/orders',
    ready: (p) => text(p, /^\d+ Orders?$/),
  },
  {
    pattern: '/market/orders/detail/[id]',
    route: (f) => `/market/orders/detail/${f.order.number}`,
    ready: (p, f) => text(p, `#${f.order.number}`),
  },
  {
    pattern: '/market/orders/create-order',
    route: () => '/market/orders/create-order',
    ready: (p) => p.getByPlaceholder('Player Username').waitFor({ timeout: READY_TIMEOUT }),
  },
  {
    pattern: '/market/deliveries',
    route: () => '/market/deliveries',
    ready: (p) => text(p, /^\d+ Deliver(?:y|ies)$/),
  },
  {
    pattern: '/market/shipments',
    route: () => '/market/shipments',
    ready: (p) => text(p, /^\d+ Shipments?$/),
  },
  {
    pattern: '/market/subscriptions',
    route: () => '/market/subscriptions',
    ready: (p) => text(p, /^\d+ Subscriptions?$/),
  },
  {
    pattern: '/market/subscriptions/detail/[id]',
    route: (f) => `/market/subscriptions/detail/${f.subscriptionId}`,
    ready: (p, f) => text(p, `Subscription #${f.subscriptionId}`),
  },
  {
    pattern: '/market/payment-events',
    route: () => '/market/payment-events',
    ready: (p) => text(p, /^\d+ Events?$/),
  },
  {
    pattern: '/market/products',
    route: () => '/market/products',
    ready: (p) => text(p, /^\d+ Products?$/),
  },
  {
    pattern: '/market/products/create-product',
    route: () => '/market/products/create-product',
    ready: (p) => field(p, '#product-name'),
  },
  {
    // the saved-record view: its description holds content, which the host editor cannot render on the server (the SSR 500 this slice found)
    pattern: '/market/products/create-product',
    route: (f) => `/market/products/create-product?id=${f.product.id}`,
    ready: async (p, f) => {
      await field(p, '#product-name');
      await p.waitForFunction(
        (name) => document.querySelector('#product-name')?.value === name,
        f.product.name,
        { timeout: READY_TIMEOUT },
      );
    },
  },
  {
    pattern: '/market/categories',
    route: () => '/market/categories',
    ready: (p) => text(p, /^\d+ Categor(?:y|ies)$/),
  },
  {
    pattern: '/market/comparisons',
    route: () => '/market/comparisons',
    ready: (p) => text(p, /^\d+ Comparisons?$/),
  },
  {
    pattern: '/market/comparisons/create-comparison',
    route: () => '/market/comparisons/create-comparison',
    ready: (p) => field(p, '#comparisonName'),
  },
  { pattern: '/market/goals', route: () => '/market/goals', ready: (p) => text(p, /^\d+ Goals?$/) },
  {
    pattern: '/market/discounts',
    route: () => '/market/discounts',
    ready: (p) => text(p, /^\d+ Discounts?$/),
  },
  {
    pattern: '/market/discounts/creator/[id]',
    route: (f) => `/market/discounts/creator/${f.creatorCodeId}`,
    ready: async (p, f) => {
      await text(p, f.creatorCode);
      await text(p, /^\d+ Earnings$/);
    },
  },
  { pattern: '/market/gifts', route: () => '/market/gifts', ready: (p) => text(p, /^\d+ Gifts?$/) },
  {
    pattern: '/market/credits',
    route: () => '/market/credits',
    ready: (p) => text(p, /^\d+ Accounts?$/),
  },
  {
    pattern: '/market/credits/account/[userId]',
    route: (f) => `/market/credits/account/${f.buyer.userId}?player=${f.buyer.username}`,
    ready: (p) => text(p, /^\d+ Entries$/),
  },
  {
    pattern: '/market/blocks',
    route: () => '/market/blocks',
    ready: (p) => text(p, /^\d+ Blocks?$/),
  },
  {
    pattern: '/market/settings',
    route: () => '/market/settings',
    ready: (p) => field(p, '#setting-storeName'),
  },
  {
    pattern: '/market/settings/shipping-method',
    route: () => '/market/settings/shipping-method',
    ready: (p) => field(p, '#shippingMethodName'),
  },
];

/** The player's market tab: registered only when the panel host has the player detail menu (X-9); else the market card sits on the overview. */
export const PLAYER_TAB = '/players/detail/[username]/market';

/** The page paths register.js registers (the PAGES table and the player tab), read from its text so a page added there fails PANEL-57 until it is covered. */
export function registeredPaths(source) {
  const paths = [...source.matchAll(/^\s*\[\s*'(\/[^']+)'\s*,/gm)].map((m) => m[1]);
  const player = /path:\s*'(\/players\/[^']+)'/.exec(source)?.[1];
  return [...paths, ...(player ? [player] : [])];
}

const REGISTER_JS = new URL('../../src/panel/register.js', import.meta.url);

/** The drift check: the routes PANEL-57 opens cover exactly the paths of src/panel/register.js. */
export function assertCoversRegisteredPages(source = fs.readFileSync(REGISTER_JS, 'utf8')) {
  const registered = new Set(registeredPaths(source));
  const covered = new Set([...PAGES.map((page) => page.pattern), PLAYER_TAB]);
  const missing = [...registered].filter((path) => !covered.has(path));
  const stale = [...covered].filter((path) => !registered.has(path));
  assert(
    missing.length === 0 && stale.length === 0,
    `PANEL-57: the page list differs from src/panel/register.js (not covered: ${JSON.stringify(missing)}, no longer registered: ${JSON.stringify(stale)})`,
  );
  assert(
    registered.size >= 24,
    `PANEL-57: register.js lists ${registered.size} pages, expected at least 24`,
  );
}

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
    title:
      'permissions: a holder of only the legacy umbrella node opens every registered page (all 24 routes incl. the detail pages and the saved product) with status 200 and its own content',
    async run(ctx) {
      const { browser, env, admin, catalogue } = ctx;
      assertCoversRegisteredPages();
      const cat = await catalogue();
      const fixtures = await createFixtures(ctx, cat);
      const api = await staffAccount(ctx, 'p57', ['manage.market']);
      const { pc, page } = await signedIn(browser, api);

      for (const entry of PAGES) {
        const route = entry.route(fixtures);
        const status = await openMarketStatus(page, env, route, (p) => entry.ready(p, fixtures));
        assertEqual(status, 200, `PANEL-57: ${route} answers 200`);
        const body = await bodyText(page);
        assert(
          !body.includes(NO_PERMISSION),
          `PANEL-57: ${route} says the account lacks permission`,
        );
        assert(!body.includes('Could Not Load Data'), `PANEL-57: ${route} could not load its data`);
      }

      // the player's market tab (X-9) or, on a host without the player detail menu, the market card on the player overview
      const playerBase = `/players/detail/${fixtures.buyer.username}`;
      const tabResponse = await page.goto(`${env.url}/panel${playerBase}/market`, {
        waitUntil: 'domcontentloaded',
        timeout: 240000,
      });
      const tabOutcome = await waitFor('the player market tab to render or 404', async () => {
        const body = await bodyText(page);
        if (body.includes('Latest Orders')) return 'TAB';
        if (body.includes('Error: 404')) return 'MISSING';
        return null;
      });
      if (tabOutcome === 'TAB') {
        assertEqual(tabResponse.status(), 200, 'PANEL-57: the player market tab answers 200');
        console.log('  PANEL-57: the player market tab rendered');
      } else {
        // the probe of a tab this host does not register logs its 404 as a browser console error: expected exactly once, removed here
        const probed = pc.errors.filter((e) => /status of 404/.test(e) && e.includes('/market'));
        assertEqual(probed.length, 1, 'PANEL-57: the probe of the missing tab logged one 404');
        pc.errors.splice(pc.errors.indexOf(probed[0]), 1);
        // the overview itself belongs to the host's player page, which needs the host's own players node besides the market umbrella node
        await grantUserNode(admin, api.userId, 'pano.panel.manage.players');
        const overview = await openMarketStatus(page, env, playerBase, (p) =>
          p.getByText('Latest Orders').first().waitFor({ timeout: 60000 }),
        );
        assertEqual(overview, 200, 'PANEL-57: the player overview (market card) answers 200');
        console.log(
          '  PANEL-57: this panel host has no player market tab; the market card on the overview answered 200',
        );
      }
      assert(
        !(await bodyText(page)).includes(NO_PERMISSION),
        'PANEL-57: the player market view says the account lacks permission',
      );

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

/** The records the parameter pages need, all made through the API like a person would (no SQL). */
async function createFixtures(ctx, cat) {
  const { admin, buyer: newBuyer } = ctx;
  const order = await mixedOrder(ctx, cat, { label: 'p57o' });

  // a subscription: a SUBSCRIPTION product paid at the fake gateway, which hands over the stored method
  const plan = await product(admin, 'Plan 57', {
    price: '6.00',
    billingMode: 'SUBSCRIPTION',
    periodUnit: 'MONTH',
    periodCount: '1',
    actions: actions.credit(1),
  });
  const subscriber = await newBuyer('p57s');
  await grantUserNode(admin, subscriber.userId, PAY_NODE);
  const placed = await checkout(subscriber, {
    items: [{ productId: plan.id, quantity: 1 }],
    paymentMethodId: 'fake',
  });
  await completePayment(placed.payUrl);
  await awaitStatus(subscriber, placed.publicId, 'COMPLETED');
  const subscriptionId = await waitFor('the subscription to exist', async () => {
    const list = must(
      await admin.get(
        `/api/panel/market/subscriptions?search=${encodeURIComponent(subscriber.username)}`,
      ),
      'subscriptions',
    ).json;
    return list.subscriptions?.[0]?.id ?? null;
  });

  // a creator code of a registered player, a credit account and a saved product whose description holds content
  const creator = await newBuyer('p57c');
  const code = `P57${Date.now().toString(36).toUpperCase()}`;
  const creatorCodeId = must(
    await admin.post('/api/panel/market/creator-codes', {
      creator: creator.username,
      code,
      discount: 5,
      unit: 'PERCENT',
      commissionPercent: 10,
    }),
    'creator code',
  ).json.id;
  await grantCredits(admin, creator.userId, 2);
  const saved = await product(admin, 'Saved 57', {
    price: '3.00',
    description: '<p>Saved with <b>content</b></p>',
  });

  return {
    order,
    subscriptionId,
    creatorCodeId,
    creatorCode: code,
    buyer: creator,
    product: saved,
  };
}
