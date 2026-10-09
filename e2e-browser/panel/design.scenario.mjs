// Scenarios 73 to 75 of 13 section 25.4: design conformance of every list page (73), no orphaned modal backdrop after a modal-driven mutation (74), and no
// missing translation key on any page in tr, en-US and ru (75).
import fs from 'node:fs';
import { must, MARKET_API, PANEL_MARKET_API, listOf } from '../lib/api.mjs';
import { run, grantCredits, product } from '../lib/bootstrap.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import {
  signedIn,
  openAt,
  blockDevBounce,
  devPanelBase,
  modalsClosed,
  sleep,
  waitFor,
} from './lib/panel.mjs';
import { newContext } from '../lib/browser.mjs';
import { rawKeys, rawPlaceholders, hydrated } from '../lib/ui.mjs';
import { checkout, awaitStatus, mixedOrder } from './lib/orders.mjs';
import { paidShippableOrder, physicalProduct, zoneAndMethod } from './lib/shipping.mjs';
import { subscribe } from './subscriptions.scenario.mjs';
import { actions } from '../lib/bootstrap.mjs';

import { ACTION_VARIABLES } from '../../src/panel/components/action-variables.js';

/**
 * Template variables the panel shows on purpose as literal text: the delivery command variables (13 section 8.9), the default broadcast template
 * (`{player}`, `{product}`, `{store}`) and the tracking token of a carrier URL template (`{tracking}`). Anything else in braces is an unreplaced argument.
 */
const LITERAL_VARIABLES = new Set([...ACTION_VARIABLES, 'player', 'product', 'store', 'tracking']);

const enUS = JSON.parse(
  fs.readFileSync(new URL('../../src/locales/panel/en-US.json', import.meta.url), 'utf8'),
);

// ------------------------------------------------------------------------------------------------------------------------------- fixtures

let fixtures = null;

/** One row of every list the pages show, made through the API (no SQL), once per run. */
async function ensureFixtures(ctx) {
  if (fixtures) return fixtures;
  const { admin, gateway } = ctx;
  const cat = await ctx.catalogue();
  const tag = `${run.tag}${run.next()}`;

  await mixedOrder(ctx, cat, { label: 'd73m' }); // orders, payment attempts and events
  const gift = await ctx.buyer('d73f');
  const free = await checkout(gift, { items: [{ productId: cat.free.id, quantity: 1 }] }); // deliveries (a CREDIT action)
  await awaitStatus(gift, free.publicId, 'COMPLETED');

  const plan = await product(admin, `Plan 73 ${tag}`, {
    price: '5.00',
    billingMode: 'SUBSCRIPTION',
    periodUnit: 'MONTH',
    periodCount: '1',
    actions: actions.credit(1),
  });
  await subscribe(ctx, plan, 'd73s');

  const shipping = await zoneAndMethod(admin, `d73-${tag}`);
  const parcel = await physicalProduct(admin, `Parcel 73 ${tag}`);
  // the instance's catch-all zone shadows nothing for DE: the first active zone that matches wins, so the order is quoted with this zone's method
  const zones = listOf(
    must(await admin.get(`${PANEL_MARKET_API}/shipping/zones`), 'zones').json,
    'zones',
  );
  const off = zones
    .filter((z) => z.status === 'ACTIVE' && z.id !== shipping.zoneId)
    .map((z) => z.id);
  for (const id of off)
    await admin.put(`${PANEL_MARKET_API}/shipping/zones/${id}`, { status: 'INACTIVE' });
  try {
    const order = await paidShippableOrder(ctx, parcel, 2, shipping.methodId, 'd73p');
    must(
      await admin.post(`${PANEL_MARKET_API}/orders/${order.id}/shipments`, {
        providerId: 'manual',
        items: [{ orderItemId: order.detail.items[0].id, quantity: 1 }],
        parcels: [{ weightGrams: 500 }],
        manual: { carrierName: 'E2E Post', trackingNumber: `E2E-D73-${tag}` },
      }),
      'shipment',
    );
  } finally {
    for (const id of off)
      await admin.put(`${PANEL_MARKET_API}/shipping/zones/${id}`, { status: 'ACTIVE' });
  }

  must(
    await admin.post(`${PANEL_MARKET_API}/comparisons`, {
      name: `Comparison ${tag}`,
      status: 'ACTIVE',
      selectedProducts: [cat.vip.id],
    }),
    'comparison',
  );
  must(
    await admin.post(`${PANEL_MARKET_API}/goals`, {
      name: `Goal ${tag}`,
      description: '',
      metric: 'REVENUE',
      target: 100,
      period: 'ONE_TIME',
      startsAt: null,
      endsAt: null,
      status: 'ACTIVE',
      showOnStore: false,
      productIds: [],
    }),
    'goal',
  );
  // an ALL-products discount would change every price of the scenarios that run after this one (the theme scenarios): the row is made inactive at once
  const discount = must(
    await admin.post(`${PANEL_MARKET_API}/discounts`, {
      name: `Discount ${tag}`,
      value: 10,
      unit: 'PERCENT',
      scope: 'ALL',
    }),
    'discount',
  ).json;
  must(
    await admin.put(`${PANEL_MARKET_API}/discounts/${discount.id}`, { status: 'INACTIVE' }),
    'switch the discount off',
  );
  must(
    await admin.post(`${PANEL_MARKET_API}/coupons`, {
      name: `Coupon ${tag}`,
      code: `D73C${tag}`.toUpperCase(),
      discount: 5,
      unit: 'PERCENT',
    }),
    'coupon',
  );
  must(
    await admin.post(`${PANEL_MARKET_API}/creator-codes`, {
      creator: `d73${tag}`.slice(0, 16),
      code: `D73R${tag}`.toUpperCase(),
      discount: 5,
      unit: 'PERCENT',
      commissionPercent: 10,
    }),
    'creator code',
  );
  must(
    await admin.post(`${PANEL_MARKET_API}/gifts`, {
      name: `Gift ${tag}`,
      code: `D73G${tag}`.toUpperCase(),
      type: 'PRODUCT',
      productId: cat.vip.id,
    }),
    'gift',
  );
  await grantCredits(admin, gift.userId, 3);
  must(
    await admin.post(`${PANEL_MARKET_API}/blocks`, {
      type: 'EMAIL',
      value: `d73-${tag}@example.com`,
      reason: 'e2e',
    }),
    'block',
  );

  // a payment event: the stored request of an inbound call that was refused (a webhook with a wrong signature is REJECTED)
  const refused = await fetch(`${ctx.env.url}${MARKET_API}/payments/fake/webhook`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Fake-Signature': 't=1,v1=bad' },
    body: JSON.stringify({
      id: `evt_bad_${tag}`,
      type: 'payment.succeeded',
      data: { reference: 'NOPE', amount: '1.00', currency: 'EUR' },
    }),
  });
  assert(refused.status === 400, `the refused webhook was answered ${refused.status}`);
  must(
    await admin.post(`${PANEL_MARKET_API}/settings/legal`, {
      locale: 'en-US',
      title: `Terms 73 ${tag}`,
      content: '<p>Terms</p>',
    }),
    'legal text',
  );

  fixtures = { tag };

  return fixtures;
}

// ------------------------------------------------------------------------------------------------------------------------------- the pages

/** Every list page of the plugin: route, a count line that proves the list is loaded, and whether it must hold rows after `ensureFixtures`. */
const LIST_PAGES = [
  { path: '/market/orders', rows: true },
  { path: '/market/deliveries', rows: true },
  { path: '/market/shipments', rows: true },
  { path: '/market/subscriptions', rows: true },
  { path: '/market/payment-events', rows: true },
  { path: '/market/products', rows: true },
  { path: '/market/categories', rows: true },
  { path: '/market/comparisons', rows: true },
  { path: '/market/goals', rows: true },
  { path: '/market/discounts?section=general', rows: true },
  { path: '/market/discounts?section=coupons', rows: true },
  { path: '/market/discounts?section=creators', rows: true },
  { path: '/market/discounts?section=payouts', rows: true },
  { path: '/market/gifts', rows: true },
  { path: '/market/credits', rows: true },
  { path: '/market/blocks', rows: true },
  { path: '/market/settings?section=shipping-zones', rows: true },
  { path: '/market/settings?section=shipping-methods', rows: true },
  { path: '/market/settings?section=shipping-carriers', rows: false },
  { path: '/market/settings?section=legal', rows: true },
];

/** Waits until the page holds its list (a table or the empty state) and no loading spinner is left. */
async function settle(page) {
  const visibleCard = () =>
    [...document.querySelectorAll('.card')].some((card) => card.getClientRects().length > 0);
  await page.waitForFunction(visibleCard, null, { timeout: 60000 });
  await page.waitForFunction(
    () =>
      ![...document.querySelectorAll('.card .spinner-border')].some(
        (e) => e.getClientRects().length > 0,
      ),
    null,
    { timeout: 60000 },
  );
  await sleep(500);
}

/** The DOM facts of 13 section 25.4 item 73 for the page that is open. */
const inspect = (page) =>
  page.evaluate(() => {
    const problems = [];
    const visible = (element) => element.getClientRects().length > 0;
    const cards = [...document.querySelectorAll('.card')].filter(visible);
    const tables = cards.flatMap((card) => [...card.querySelectorAll('table')]).filter(visible);
    let rows = 0;

    for (const table of tables) {
      const head = table.querySelector('thead tr');
      const first = head?.querySelector('th');
      if (!first || first.textContent.trim() !== '')
        problems.push(
          `the first header cell of a table is not the empty control cell (${first?.textContent.trim()})`,
        );

      for (const tr of table.querySelectorAll('tbody tr')) {
        rows++;
        const cell = tr.firstElementChild;
        if (!cell || cell.tagName !== 'TH' || cell.getAttribute('scope') !== 'row')
          problems.push(
            `a row starts with <${cell?.tagName.toLowerCase()}> instead of th[scope=row]: ${tr.textContent.trim().slice(0, 40)}`,
          );
        else if (
          cell.querySelector('button, a') &&
          !cell.querySelector('[data-bs-toggle="dropdown"]')
        )
          problems.push(
            `the control cell of a row holds something other than the dropdown: ${tr.textContent.trim().slice(0, 40)}`,
          );
      }
    }

    const search =
      cards.map((card) => card.querySelector('input.focus-ring')).find(Boolean) ?? null;
    const alerts = cards
      .filter((card) => card.querySelector('table'))
      .flatMap((card) => [...card.querySelectorAll('.alert')])
      .filter(visible);

    return {
      problems,
      tables: tables.length,
      rows,
      hasSearch: Boolean(search),
      searchFocused: Boolean(search) && document.activeElement === search,
      alerts: alerts.length,
      // the empty state is the NoContent component (no icon on a list page)
      noContentIcon: cards.some((card) =>
        card.querySelector('.no-content img, .no-content svg, .no-content i'),
      ),
    };
  });

/** Opens a CTA that shows a modal, checks the footer rule and closes it again. */
async function formModalFooter(page, label, trigger) {
  await trigger();
  const modal = page.locator('.modal.show');
  await modal.first().waitFor({ timeout: 20000 });
  const footers = await modal.first().locator('.modal-footer button:visible').count();
  const header = await modal.first().locator('.modal-header').count();
  // Bootstrap ignores a hide() while the show transition still runs: close once the dialog has settled, and ask again if it did not go
  for (let attempt = 0; attempt < 4; attempt++) {
    await sleep(500);
    const close = modal.first().locator('.btn-close');
    if (await close.count()) await close.first().click();
    else await page.keyboard.press('Escape');
    try {
      await modalsClosed(page, 5000);
      break;
    } catch (error) {
      if (attempt === 3) throw error;
    }
  }

  return { label, footers, header };
}

// ------------------------------------------------------------------------------------------------------------------------------- scenarios

export const scenarios = [
  {
    id: 'PANEL-73',
    title:
      'design conformance on every list page: the control cell is the first cell of each row, the search input is focused after load, no alert inside a table card, confirmation modals have no header, form modals have one footer button',
    async run(ctx) {
      const { env, admin } = ctx;
      await ensureFixtures(ctx);
      const { pc, page } = await signedIn(ctx.browser, admin);
      // The design rules are written against the current panel host (the panel-ui checkout); the panel bundled in the jar predates the SearchInput
      // `autofocus` prop, so the focus rule can only be asserted against the checkout. Everything else is asserted on whichever host is used.
      const dev = devPanelBase(env);
      const base = dev ?? `${env.url}/panel`;
      if (dev) await blockDevBounce(pc, env);
      else
        console.log(
          'PANEL-73: no panel-ui checkout (--ui external): the search focus rule is not asserted against the bundled host',
        );
      const openMarket = (p, _env, route, ready) => openAt(p, base, route, ready);
      const failures = [];
      let withSearch = 0;
      let withRows = 0;

      for (const entry of LIST_PAGES) {
        await openMarket(page, env, entry.path, settle);
        const facts = await inspect(page);

        for (const problem of facts.problems) failures.push(`${entry.path}: ${problem}`);
        if (entry.rows && facts.rows === 0)
          failures.push(
            `${entry.path}: the page holds no row (a positive control: the fixtures should have made one)`,
          );
        if (facts.rows > 0) withRows++;
        if (facts.hasSearch) {
          withSearch++;
          if (dev && !facts.searchFocused)
            failures.push(`${entry.path}: the search input is not focused after load`);
        }
        if (facts.alerts > 0)
          failures.push(`${entry.path}: ${facts.alerts} .alert inside a table card`);
        if (facts.tables === 0 && facts.noContentIcon)
          failures.push(`${entry.path}: the empty state shows an icon`);
      }

      assert(withRows >= 15, `PANEL-73: only ${withRows} pages were inspected with rows`);
      assert(
        !dev || withSearch >= 8,
        `PANEL-73: only ${withSearch} pages with a search input were inspected`,
      );

      // --- confirmation modals: no header (a blocks row, a subscription retry is covered by PANEL-70)
      await openMarket(page, env, '/market/blocks', settle);
      const row = page.locator('table tbody tr').first();
      await row.locator('button[data-bs-toggle="dropdown"]').click();
      await row.getByRole('button', { name: 'Remove' }).click();
      const confirm = page.locator('.modal.show');
      await confirm.first().waitFor({ timeout: 15000 });
      if ((await confirm.first().locator('.modal-header').count()) !== 0)
        failures.push('/market/blocks: the confirmation modal has a .modal-header');
      await confirm.first().getByRole('button', { name: 'Cancel' }).click();
      await modalsClosed(page);

      // --- form modals: one footer button, a header title
      const forms = [];
      const open = (path, name) => async () => {
        await openMarket(page, env, path, settle);
        return name;
      };
      const cta = (path, name, menu) => async () => {
        await openMarket(page, env, path, settle);
        if (menu) await page.getByRole('button', { name: 'Actions' }).first().click();
        await page.getByRole('button', { name }).first().click();
      };
      void open;
      for (const [label, trigger] of [
        ['blocks', cta('/market/blocks', /Add Block/)],
        ['credits grant', cta('/market/credits', /Grant Credits/)],
        ['categories', cta('/market/categories', /Add Category/)],
        ['goals', cta('/market/goals', /Create Goal/)],
        ['discounts', cta('/market/discounts?section=general', /Create Discount/)],
        ['coupons', cta('/market/discounts?section=coupons', /Create Coupon Code/)],
        ['creator codes', cta('/market/discounts?section=creators', /Create Creator Code/)],
        ['gifts', cta('/market/gifts', /Create Gift/)],
        ['shipping zones', cta('/market/settings?section=shipping-zones', 'Create Zone', true)],
      ])
        forms.push(await formModalFooter(page, label, trigger));

      for (const form of forms) {
        if (form.footers !== 1)
          failures.push(
            `form modal "${form.label}": ${form.footers} footer buttons instead of one`,
          );
        if (form.header < 1) failures.push(`form modal "${form.label}": no .modal-header title`);
      }

      assertEqual(
        failures.length,
        0,
        `PANEL-73: design conformance failures:\n  ${failures.join('\n  ')}\n`,
      );
      pc.expectNoErrors('PANEL-73');
      await pc.close();
    },
  },

  {
    id: 'PANEL-74',
    title:
      'remount safety: after a modal-driven mutation (block, credit grant, category) no modal backdrop is left behind and the page raises no error',
    async run(ctx) {
      const { env, admin } = ctx;
      const { pc, page } = await signedIn(ctx.browser, admin);
      const dev = devPanelBase(env);
      const base = dev ?? `${env.url}/panel`;
      if (dev) await blockDevBounce(pc, env);
      const open = (route, ready) => openAt(page, base, route, ready);
      const tag = `${run.tag}${run.next()}`;

      // the host remounts the page whenever load() re-runs: a backdrop that belongs to the old mount must not survive it
      const noOrphans = async (label) => {
        await sleep(1500);
        const state = await page.evaluate(() => ({
          backdrops: document.querySelectorAll('.modal-backdrop').length,
          open: document.body.classList.contains('modal-open'),
          overflow: document.body.style.overflow,
          shown: [...document.querySelectorAll('.modal')].filter(
            (m) => getComputedStyle(m).display !== 'none',
          ).length,
        }));
        assertEqual(state.backdrops, 0, `PANEL-74 ${label}: .modal-backdrop left behind`);
        assert(!state.open, `PANEL-74 ${label}: body keeps the modal-open class`);
        assert(state.overflow !== 'hidden', `PANEL-74 ${label}: body overflow is still hidden`);
        assertEqual(state.shown, 0, `PANEL-74 ${label}: a modal is still displayed`);
      };

      // --- blocks: add (list refresh), remove (confirmation, list refresh)
      const email = `d74-${tag}@example.com`;
      await open('/market/blocks', settle);
      await page
        .getByRole('button', { name: /Add Block/ })
        .first()
        .click();
      const block = page.locator('.modal.show');
      await block.locator('#block-type').selectOption('EMAIL');
      await block.locator('#block-value').fill(email);
      await block.getByRole('button', { name: 'Add Block' }).click();
      await page.getByText(enUS.modals.block['toast-added']).first().waitFor({ timeout: 15000 });
      await modalsClosed(page);
      await noOrphans('block added');
      const row = page.locator('table tbody tr').filter({ hasText: email });
      await row.waitFor({ timeout: 30000 });
      await row.locator('button[data-bs-toggle="dropdown"]').click();
      await row.getByRole('button', { name: 'Remove' }).click();
      await page.locator('.modal.show').getByRole('button', { name: 'Remove' }).click();
      await page.getByText(enUS.pages.blocks['toast-removed']).first().waitFor({ timeout: 15000 });
      await modalsClosed(page);
      await noOrphans('block removed');

      // --- credits: grant to a player (the modal hides, then the list refreshes)
      const player = await ctx.buyer('d74');
      await open('/market/credits', settle);
      await page
        .getByRole('button', { name: /Grant Credits/ })
        .first()
        .click();
      const grant = page.locator('.modal.show');
      await grant.getByPlaceholder('Player username').fill(player.username);
      await grant.getByPlaceholder('Player username').blur();
      await grant.getByText(/^Balance: /).waitFor({ timeout: 15000 });
      await grant.getByPlaceholder('Amount').fill('2');
      await grant.getByPlaceholder('Reason').fill('e2e remount');
      await grant.getByRole('button', { name: 'Grant Credits' }).click();
      await page.getByText('Credits granted.').first().waitFor({ timeout: 15000 });
      await modalsClosed(page);
      await noOrphans('credits granted');

      // --- categories: create in the modal
      await open('/market/categories', settle);
      await page
        .getByRole('button', { name: /Add Category/ })
        .first()
        .click();
      const category = page.locator('.modal.show');
      await category.locator('#categoryNameInput').fill(`Cat 74 ${tag}`);
      await category.locator('.modal-footer').getByRole('button', { name: 'Create' }).click();
      await page
        .getByText(enUS.modals.category['toast-created'])
        .first()
        .waitFor({ timeout: 15000 });
      await modalsClosed(page);
      await noOrphans('category created');

      pc.expectNoErrors('PANEL-74');
      await pc.close();
    },
  },

  {
    id: 'PANEL-75',
    title:
      'every page renders without a console error, a missing translation key or a raw key / placeholder in tr, en-US and ru',
    async run(ctx) {
      const { env, admin } = ctx;
      await ensureFixtures(ctx);
      const dev = devPanelBase(env);
      const base = dev ?? `${env.url}/panel`;

      // routes that need a record: taken from the API
      const firstOf = async (path, what, legacyKey) =>
        listOf(must(await admin.get(`${PANEL_MARKET_API}${path}`), what).json, legacyKey)[0];
      const order = await firstOf('/orders', 'orders', 'orders');
      const subscription = await firstOf('/subscriptions', 'subscriptions', 'subscriptions');
      const creator = await firstOf('/creator-codes/report', 'creators', 'creators');
      const credit = await firstOf('/credits/accounts', 'credits', 'accounts');
      assert(
        order && subscription && creator && credit,
        'PANEL-75: the fixtures made an order, a subscription, a creator code and a credit account',
      );

      const routes = [
        '/market',
        ...LIST_PAGES.map((entry) => entry.path),
        ...[
          'general',
          'checkout',
          'currencies',
          'billing',
          'payments',
          'credits',
          'delivery',
          'modules',
          'security',
          'mail',
          'minecraft',
          'health',
        ].map((key) => `/market/settings?section=${key}`),
        '/market/products/create-product',
        '/market/orders/create-order',
        '/market/comparisons/create-comparison',
        '/market/settings/shipping-method',
        `/market/orders/detail/${order.number ?? order.id}`,
        `/market/subscriptions/detail/${subscription.id}`,
        `/market/discounts/creator/${creator.id}`,
        `/market/credits/account/${credit.userId ?? credit.id}`,
      ];
      const marker = { tr: 'Siparişler', 'en-US': 'Orders', ru: 'Заказы' };
      const problems = [];

      const checkLocale = async (locale) => {
        const pc = await newContext(ctx.browser, {
          viewport: 'desktop',
          locale,
          cookies: admin.playwrightCookies(),
        });
        const page = await pc.page();
        const warnings = [];
        if (dev) await blockDevBounce(pc, env);
        page.on('console', (message) => {
          if (
            ['warning', 'warn', 'error'].includes(message.type()) &&
            /not found in|missing|\[svelte-i18n\]/i.test(message.text())
          )
            warnings.push(message.text().slice(0, 240));
        });

        for (const route of routes) {
          const before = warnings.length;
          const errorsBefore = pc.errors.length;

          try {
            await openAt(page, base, route, settle);
          } catch (error) {
            problems.push(
              `[${locale}] ${route}: did not load (${String(error.message).split('\n')[0]})`,
            );
            continue;
          }
          await sleep(300);
          for (const text of warnings.slice(before)) problems.push(`[${locale}] ${route}: ${text}`);
          for (const text of pc.errors.slice(errorsBefore))
            problems.push(`[${locale}] ${route}: ${text}`);
          for (const key of await rawKeys(page))
            problems.push(`[${locale}] ${route}: raw translation key ${key}`);
          for (const holder of await rawPlaceholders(page))
            if (!LITERAL_VARIABLES.has(holder.slice(1, -1)))
              problems.push(`[${locale}] ${route}: unreplaced placeholder ${holder}`);
        }

        // the language really was the requested one (a positive control for the run above)
        await openAt(page, base, '/market/orders', settle);
        assert(
          (await page.locator('body').innerText()).includes(marker[locale]),
          `PANEL-75: the pages are shown in ${locale} (no "${marker[locale]}" on the orders page)`,
        );
        await pc.close();
      };

      // The panel language is the platform's language setting (not the browser's): it is changed through the settings API for each round and put back.
      const setLocale = async (locale) =>
        must(
          await admin.multipart('PUT', '/api/v1/panel/settings', { locale }),
          `set the panel language to ${locale}`,
        );

      try {
        for (const locale of ['tr', 'ru', 'en-US']) {
          await setLocale(locale);
          await checkLocale(locale);
        }
      } finally {
        await setLocale('en-US');
      }

      assertEqual(
        problems.length,
        0,
        `PANEL-75: ${problems.length} problem(s):\n  ${problems.slice(0, 40).join('\n  ')}\n`,
      );
    },
  },
];
