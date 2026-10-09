// Theme browser scenarios 47 and 48 of 14 section 20.3 (profile navigation, store widgets), vanilla theme. Ids TH-47 and TH-48 are the numbers of the spec.
//
// Both scenarios need a host that announces `profile-nav` / `page-sidebar-id` through `pano.features` (15 section 4.1): the checkout of
// themes/vanilla-theme that `e2e-instance.sh start --ui external:<theme>,<panel>` runs with `vite dev` (env.themeUrl; the theme zip bundled in the
// Pano jar is older than theme-core and announces nothing). Opening that vite port directly makes the theme's dev helper `checkDomainRedirection`
// (sdk core/js/variables.js) bounce the browser to the instance's own port, i.e. to the bundled theme; `patchBundle` switches that dev bounce off
// for the page, otherwise the plugin would never see the vite host. A half that needs a capability the host does not announce FAILS the scenario with
// that reason, it is never skipped silently and never counted as a pass.
import { must, MARKET_API, PANEL_MARKET_API } from '../lib/api.mjs';
import { actions, grantCredits, product as panelProduct, run } from '../lib/bootstrap.mjs';
import { newContext } from '../lib/browser.mjs';
import { assert, assertEqual, open } from '../lib/ui.mjs';
import { payBuyer, signedIn, text, withBankTransfer } from './lib/checkout.mjs';
import {
  checkout,
  orderRowId,
  paidOrder,
  sleep,
  until,
  waitUntil,
  withLiveStore,
} from './lib/orders.mjs';

const unique = () => `${run.tag}${run.next()}`;
const BUNDLE = /\/plugins\/pano-plugin-market\/resources\/plugin-ui\/client\/main-[^/]+\.js$/;

/** The links of the profile navigation card of the sidebar (and of the Store block), by href. */
const navLink = (page, href) => page.locator(`a.list-group-item-action[href="${href}"]`);

/** The account dropdown of the navbar, opened. */
async function openAccountMenu(page) {
  await page.locator('.nav-item.dropdown > button[data-bs-toggle="dropdown"]').first().click();
  await page.locator('.dropdown-menu.show').waitFor({ timeout: 10000 });
}

/**
 * Patches what the browser loads, before the page starts:
 * - the dev bounce of the theme (`checkDomainRedirection`, see the header) is switched off, so a page opened on the vite port stays there;
 * - the plugin's browser bundle records at its start (`onLoad`) what the host announces in `pano.features` into `window.__E2E_HOST_FEATURES__`
 *   (null when the host has no `pano.features`), and with `mask` the plugin is then told that the host has neither `profile-nav` nor
 *   `page-sidebar-id`, which is what an older theme build looks like to it (14 section 4.1). The mask reports what it did in `window.__E2E_MASKED__`
 *   (`[has('profile-nav'), has('page-sidebar-id')]` after masking, `'failed'` when it could not be applied), so a mask that does nothing is caught.
 * Only the browser bundle changes, so the server-rendered first page is the ordinary one: a masked scenario starts on a page that has nothing to do
 * with the profile and walks there client-side.
 */
async function patchBundle(context, { mask = false } = {}) {
  await context.route(/\/sdk\/core\/js\/variables\.js/, async (route) => {
    const response = await route.fetch();
    const source = await response.text();

    await route.fulfill({
      response,
      body: source.replace(
        'export function checkDomainRedirection() {',
        'export function checkDomainRedirection() { return;',
      ),
    });
  });
  await context.route(BUNDLE, async (route) => {
    const response = await route.fetch();
    const source = await response.text();
    const record =
      'window.__E2E_HOST_FEATURES__=(this.pano.features&&this.pano.features.list)?this.pano.features.list():null;';
    const hide = mask
      ? "try{const f=this.pano.features;const has=f.has;this.pano.features=Object.freeze({has:(id)=>id==='profile-nav'||id==='page-sidebar-id'?false:has(id),list:f.list});window.__E2E_MASKED__=[this.pano.features.has('profile-nav'),this.pano.features.has('page-sidebar-id')]}catch(e){window.__E2E_MASKED__='failed'}"
      : '';

    await route.fulfill({
      response,
      body: source.replace('onLoad(){', `onLoad(){${record}${hide}`),
    });
  });
}

/** The origin of the host that announces its features: the theme checkout started by e2e-instance.sh --ui external (the instance itself when there is none). */
const hostBase = (env) => env.themeUrl ?? env.url;

const hostFeatures = (page) => page.evaluate(() => window.__E2E_HOST_FEATURES__ ?? null);

/** Fails unless the host announced both ids before the mask and the mask turned both off: a mask that is a no-op proves nothing. */
async function assertMaskEffective(page, label) {
  const announced = await hostFeatures(page);

  assert(
    announced?.includes('profile-nav') && announced?.includes('page-sidebar-id'),
    `${label}: the host announced profile-nav and page-sidebar-id before the mask (got ${JSON.stringify(announced)})`,
  );
  assertEqual(
    JSON.stringify(await page.evaluate(() => window.__E2E_MASKED__ ?? null)),
    JSON.stringify([false, false]),
    `${label}: after the mask the plugin sees neither profile-nav nor page-sidebar-id`,
  );
}

export const scenarios = [
  {
    id: 'TH-47',
    title:
      'profile navigation: without profile-nav the Store block on /profile and the pill navigation; with it the sidebar links that follow the buyer',
    async run({ browser, env, admin, catalogue, buyer }) {
      await catalogue();

      const sub = await panelProduct(admin, 'Sub47', {
        price: '6.00',
        billingMode: 'SUBSCRIPTION',
        periodUnit: 'MONTH',
        periodCount: '1',
        actions: actions.credit(1),
      });
      const plain = await payBuyer(buyer, admin, 'v47');
      const rich = await payBuyer(buyer, admin, 'w47');

      await grantCredits(admin, plain.userId, 7);
      await grantCredits(admin, rich.userId, 3);

      // the second buyer holds a creator code and a subscription
      must(
        await admin.post(`${PANEL_MARKET_API}/creator-codes`, {
          creator: rich.username,
          creatorUserId: rich.userId,
          code: `N47${unique()}`.toUpperCase(),
          discount: 5,
          unit: 'PERCENT',
          commissionPercent: 10,
          status: 'ACTIVE',
        }),
        'creator code',
      );
      await paidOrder(rich, [{ productId: sub.id, quantity: 1 }]);
      await waitUntil(
        async () => ((await rich.get(`${MARKET_API}/me/summary`)).json?.subscriptionCount ?? 0) > 0,
        30000,
        'the subscription of the second buyer',
      );

      // the balance of the second buyer settles at the 3 granted credits plus the credit the subscription's first payment delivers (delivery is
      // asynchronous: wait for it, so the pages below are compared with a balance that no longer moves)
      const richBalance = '4';

      await waitUntil(
        async () =>
          String((await rich.get(`${MARKET_API}/me/summary`)).json?.creditBalance) === richBalance,
        30000,
        'the credit of the subscription delivered to the second buyer',
      );

      // ---- without profile-nav and page-sidebar-id (an older theme build, as the plugin sees it) ----------------------------------
      // Same host as the half below (the vite checkout announces both ids); the plugin is told it announces neither, and `assertMaskEffective`
      // proves the mask took (announced before, gone after) so this half cannot pass on a host that never announced anything.
      const themeBase = hostBase(env);
      const old = await signedIn(browser, plain);

      await patchBundle(old.context, { mask: true });

      const oldPage = await old.page();

      await open(oldPage, `${themeBase}/store`, (p) =>
        p.locator('.card h3 a, h1').first().waitFor({ timeout: 60000 }),
      );
      await assertMaskEffective(oldPage, 'TH-47 without, plain buyer');
      await oldPage.evaluate(() => {
        window.__E2E_SAME_DOCUMENT__ = true;
      });
      await openAccountMenu(oldPage);
      await oldPage.locator('.dropdown-menu a.dropdown-item[href="/profile"]').first().click();
      await oldPage.waitForURL((url) => url.pathname === '/profile', { timeout: 30000 });

      // the Store block of /profile: a card with the title and the links the summary of this buyer allows (credits only for the plain buyer)
      const blockOf = (p) =>
        p.locator('.card', {
          has: p.locator('.card-header', { hasText: text('theme.profile.block.title') }),
        });
      const block = blockOf(oldPage);

      await block.waitFor({ timeout: 60000 });
      await block.locator('a[href="/profile/purchases"]').waitFor({ timeout: 30000 });
      await block.locator('a[href="/profile/credits"]').waitFor({ timeout: 30000 });
      assertEqual(
        (await block.locator('a[href="/profile/credits"] .badge').innerText()).trim(),
        '7',
        'the credits link of the block carries the balance of this buyer',
      );
      assertEqual(
        await block.locator('a[href="/profile/creator"]').count(),
        0,
        'no creator link for a buyer without a creator code',
      );
      assertEqual(
        await block.locator('a[href="/profile/subscriptions"]').count(),
        0,
        'and none to subscriptions without a subscription',
      );
      assertEqual(
        await oldPage.locator('a.list-group-item-action[href="/profile/purchases"]').count(),
        1,
        'the sidebar navigation has no market entries of its own, only the block',
      );

      // the page of the block has the pill navigation instead of a sidebar entry
      await block.locator('a[href="/profile/purchases"]').click();
      await oldPage.waitForURL((url) => url.pathname === '/profile/purchases', { timeout: 30000 });
      await oldPage.locator('#market-orders-filter').waitFor({ timeout: 60000 });

      const pills = oldPage.locator('nav.nav.nav-pills');

      await pills.waitFor({ timeout: 30000 });
      assertEqual(
        (await pills.locator('a.nav-link.active').innerText()).trim().split(/\s+/)[0],
        text('theme.profile.nav.purchases'),
        'the pill of the open page is active',
      );
      await pills.locator('a[href="/profile/credits"]').click();
      await oldPage.waitForURL((url) => url.pathname === '/profile/credits', { timeout: 30000 });
      await oldPage.locator('#market-balance-title').waitFor({ timeout: 60000 });
      await oldPage
        .locator('nav.nav-pills a.nav-link.active', { hasText: text('theme.profile.nav.credits') })
        .waitFor({ timeout: 15000 });
      assertEqual(
        await oldPage.evaluate(() => window.__E2E_SAME_DOCUMENT__ === true),
        true,
        'the whole walk was client-side',
      );
      old.expectNoErrors('TH-47 without');
      await old.close();

      // the buyer with a creator code and a subscription, masked the same way: the block shows the links that this buyer's summary adds (the
      // positive counterpart of the "none" assertions above: the block does follow the summary)
      const oldRich = await signedIn(browser, rich);

      await patchBundle(oldRich.context, { mask: true });

      const oldRichPage = await oldRich.page();

      await open(oldRichPage, `${themeBase}/profile`);
      await assertMaskEffective(oldRichPage, 'TH-47 without, rich buyer');

      const richBlock = blockOf(oldRichPage);

      await richBlock.waitFor({ timeout: 60000 });
      await richBlock.locator('a[href="/profile/creator"]').waitFor({ timeout: 30000 });
      await richBlock.locator('a[href="/profile/subscriptions"]').waitFor({ timeout: 30000 });
      assertEqual(
        (await richBlock.locator('a[href="/profile/credits"] .badge').innerText()).trim(),
        richBalance,
        'the block of the second buyer carries the balance of that buyer',
      );
      oldRich.expectNoErrors('TH-47 without, rich buyer');
      await oldRich.close();

      // ---- with profile-nav: the host as it is (no mask) -----------------------------------------------------------------------------
      const ctx = await signedIn(browser, plain);

      await patchBundle(ctx.context);

      const page = await ctx.page();

      await open(page, `${themeBase}/profile`);

      const features = await hostFeatures(page);

      if (!features?.includes('profile-nav') || !features?.includes('page-sidebar-id')) {
        await ctx.close();
        throw new Error(
          `TH-47: the "without" half passed; the "with profile-nav" half cannot run: ${themeBase} does not announce profile-nav / page-sidebar-id ` +
            `to the plugin (pano.features of the plugin's pano is ${JSON.stringify(features)}; a theme-core host announces both)`,
        );
      }

      await navLink(page, '/profile/purchases').first().waitFor({ timeout: 60000 });
      await navLink(page, '/profile/credits').first().waitFor({ timeout: 30000 });
      // the summary decides: no subscription, no creator code: those two links are not there
      await page.waitForFunction(
        () =>
          document.querySelectorAll('a.list-group-item-action[href="/profile/credits"] .badge')
            .length > 0,
        null,
        { timeout: 30000 },
      );
      assertEqual(
        await navLink(page, '/profile/subscriptions').count(),
        0,
        'no subscriptions link without a subscription',
      );
      assertEqual(
        await navLink(page, '/profile/creator').count(),
        0,
        'no creator link without a creator code',
      );
      assertEqual(
        (await navLink(page, '/profile/credits').first().locator('.badge').innerText()).trim(),
        '7',
        'the credits link carries the balance',
      );
      assertEqual(
        await page.locator('.card-header', { hasText: text('theme.profile.block.title') }).count(),
        0,
        'the Store block of older hosts is not shown',
      );
      assert(
        (await navLink(page, '/profile/purchases').first().locator('i').count()) === 1,
        'each link has its icon',
      );

      // the account menu has the purchases entry
      await openAccountMenu(page);
      assert(
        await page.locator('.dropdown-menu a[href="/profile/purchases"]').first().isVisible(),
        'the account menu has Purchases',
      );
      await page.keyboard.press('Escape');

      // client-side navigation through the sidebar; the host marks the open page
      await page.evaluate(() => {
        window.__E2E_SAME_DOCUMENT__ = true;
      });
      await navLink(page, '/profile/purchases').first().click();
      await page.waitForURL((url) => url.pathname === '/profile/purchases', { timeout: 30000 });
      await page.locator('#market-orders-filter').waitFor({ timeout: 60000 });
      await page.waitForFunction(
        () =>
          document
            .querySelector('a.list-group-item-action[href="/profile/purchases"]')
            ?.getAttribute('aria-current') === 'page',
        null,
        { timeout: 15000 },
      );
      assertEqual(
        await page.locator('nav.nav-pills').count(),
        0,
        'no pill navigation next to a sidebar',
      );
      assertEqual(
        await page.evaluate(() => window.__E2E_SAME_DOCUMENT__ === true),
        true,
        'the sidebar link navigated without a reload',
      );
      ctx.expectNoErrors('TH-47 with, plain buyer');
      await ctx.close();

      // the buyer with a subscription and a creator code sees all four, in the order of the table
      const richCtx = await signedIn(browser, rich);
      const richPage = await richCtx.page();

      await open(richPage, `${themeBase}/profile`, (p) =>
        navLink(p, '/profile/purchases').first().waitFor({ timeout: 60000 }),
      );
      await navLink(richPage, '/profile/creator').first().waitFor({ timeout: 30000 });
      await navLink(richPage, '/profile/subscriptions').first().waitFor({ timeout: 30000 });
      assertEqual(
        await richPage.evaluate(() =>
          [...document.querySelectorAll('a.list-group-item-action')]
            .map((a) => a.getAttribute('href'))
            .filter((href) =>
              [
                '/profile/purchases',
                '/profile/credits',
                '/profile/subscriptions',
                '/profile/creator',
              ].includes(href),
            )
            .join(','),
        ),
        '/profile/purchases,/profile/credits,/profile/subscriptions,/profile/creator',
        'all four links, purchases first',
      );
      assertEqual(
        (await navLink(richPage, '/profile/credits').first().locator('.badge').innerText()).trim(),
        richBalance,
        'with the balance of this buyer',
      );
      richCtx.expectNoErrors('TH-47 with, rich buyer');
      await richCtx.close();
    },
  },

  {
    id: 'TH-48',
    title:
      'widgets: each module flag off removes its widget from the store page and the home sidebar; amounts follow moduleRecentBuyersShowAmount; a goal at 100 %',
    async run({ browser, env, admin, catalogue, buyer }) {
      const { vip } = await catalogue();
      const modules = {
        moduleRecentBuyers: true,
        moduleRecentBuyersShowAmount: false,
        moduleTopSupporters: true,
        moduleGoal: true,
        moduleStats: true,
        moduleSidebars: ['home'],
      };
      const set = async (patch) => {
        must(
          await admin.post(`${PANEL_MARKET_API}/settings`, patch),
          `settings ${JSON.stringify(patch)}`,
        );
        await sleep(1500);
      };

      await withLiveStore(admin, async () => {
        await withBankTransfer(admin, async () => {
          const goalName = `Goal48 ${unique()}`;
          const goal = must(
            await admin.post(`${PANEL_MARKET_API}/goals`, {
              name: goalName,
              metric: 'ORDERS',
              target: 2,
              period: 'ONE_TIME',
              status: 'ACTIVE',
              showOnStore: true,
            }),
            'goal',
          );
          const names = [];

          try {
            // two real sales (a test order is never shown): the goal of 2 orders is complete
            for (const label of ['x48', 'y48']) {
              const customer = await buyer(label);
              const placed = await checkout(customer, {
                items: [{ productId: vip.id, quantity: 1 }],
                paymentMethodId: 'bank-transfer',
              });

              must(
                await admin.post(
                  `${PANEL_MARKET_API}/orders/${await orderRowId(admin, placed.publicId)}/bank-transfer`,
                  { decision: 'APPROVE' },
                ),
                'approve the transfer',
              );
              await until(customer, placed.publicId, (o) => o.status === 'COMPLETED', 'the sale');
              names.push(customer.username);
            }

            await set(modules);

            const ctx = await newContext(browser, { viewport: 'desktop' });

            await patchBundle(ctx.context);

            const page = await ctx.page();
            // the store page holds each module twice (a block under the categories and one below the grid, one of them hidden by Bootstrap)
            const shown = (label) =>
              page.getByText(label, { exact: true }).filter({ visible: true });
            // the store page on the instance's own theme; the home sidebar on the host that announces page-sidebar-id (see the half below)
            let origin = env.url;
            const visit = async (path) => {
              await open(page, `${origin}${path}`);
              await sleep(1200); // absence is only meaningful once the page has settled
            };
            const title = (key) => text(key);
            const absent = async (path, key, label) => {
              await visit(path);
              assertEqual(await shown(title(key)).count(), 0, label);
            };

            // ---- the store page, everything on ---------------------------------------------------------------------------------------
            await visit('/store');
            await shown(title('theme.widgets.recent-buyers.title'))
              .first()
              .waitFor({ timeout: 30000 });
            await shown(title('theme.widgets.top-supporters.title'))
              .first()
              .waitFor({ timeout: 10000 });
            await shown(goalName).first().waitFor({ timeout: 10000 });
            for (const name of names)
              assert(
                (await page.locator('body').innerText()).includes(name),
                `the store page names the buyer ${name}`,
              );
            assertEqual(
              await page
                .locator('[role="progressbar"][aria-valuenow="100"] .progress-bar.bg-success')
                .filter({ visible: true })
                .count(),
              1,
              'the complete goal is at 100 % and green',
            );
            assert(
              (await shown('100%').count()) === 1,
              'with its percent (the number of the goal, not capped text)',
            );

            // amounts are hidden while moduleRecentBuyersShowAmount is false, shown when it is true
            const recent = () =>
              page
                .locator('.card', { hasText: title('theme.widgets.recent-buyers.title') })
                .filter({ visible: true })
                .first();

            assert(
              !(await recent().innerText()).includes('€10.00'),
              'no amount in the recent buyers while the flag is off',
            );
            await set({ moduleRecentBuyersShowAmount: true });
            await visit('/store');
            await waitUntil(
              async () => (await recent().innerText()).includes('€10.00'),
              15000,
              'the amount of a sale in the recent buyers',
            );
            await set({ moduleRecentBuyersShowAmount: false });

            // ---- each flag off, alone: gone from the store page, the others stay -----------------------------------------------------------
            for (const [flag, key, label] of [
              ['moduleRecentBuyers', 'theme.widgets.recent-buyers.title', 'recent buyers'],
              ['moduleTopSupporters', 'theme.widgets.top-supporters.title', 'top supporters'],
            ]) {
              await set({ [flag]: false });
              await absent('/store', key, `${label} are gone from the store page`);
              await shown(goalName).first().waitFor({ timeout: 10000 });
              await set({ [flag]: true });
            }

            await set({ moduleGoal: false });
            await visit('/store');
            assertEqual(await shown(goalName).count(), 0, 'the goal is gone from the store page');
            await shown(title('theme.widgets.recent-buyers.title'))
              .first()
              .waitFor({ timeout: 10000 });
            await set({ moduleGoal: true });
            ctx.expectNoErrors('TH-48 store page');

            // ---- the home sidebar: needs a host that announces page-sidebar-id --------------------------------------------------------------
            origin = hostBase(env);
            await visit('/');

            const announced = await hostFeatures(page);

            if (!announced?.includes('page-sidebar-id')) {
              await ctx.close();
              throw new Error(
                `TH-48: the store page half passed; the home sidebar half cannot run: ${origin} does not announce page-sidebar-id to the plugin ` +
                  `(pano.features of the plugin's pano is ${JSON.stringify(announced)}), so the plugin registers no sidebar widget`,
              );
            }

            await shown(title('theme.widgets.recent-buyers.title'))
              .first()
              .waitFor({ timeout: 30000 });
            await shown(title('theme.widgets.top-supporters.title'))
              .first()
              .waitFor({ timeout: 10000 });
            await shown(goalName).first().waitFor({ timeout: 10000 });
            await shown(title('theme.widgets.stats.title')).first().waitFor({ timeout: 10000 });

            for (const [flag, key, label] of [
              ['moduleRecentBuyers', 'theme.widgets.recent-buyers.title', 'recent buyers'],
              ['moduleTopSupporters', 'theme.widgets.top-supporters.title', 'top supporters'],
              ['moduleStats', 'theme.widgets.stats.title', 'stats'],
            ]) {
              await set({ [flag]: false });
              await absent('/', key, `${label} are gone from the home sidebar`);
              await set({ [flag]: true });
            }

            await set({ moduleGoal: false });
            await visit('/');
            assertEqual(await shown(goalName).count(), 0, 'the goal is gone from the home sidebar');
            await set({ moduleGoal: true });
            ctx.expectNoErrors('TH-48 home sidebar');
            await ctx.close();
          } finally {
            await admin
              .request('DELETE', `${PANEL_MARKET_API}/goals/${goal.json.id}`)
              .catch(() => {});
            await set({
              moduleRecentBuyers: true,
              moduleRecentBuyersShowAmount: false,
              moduleTopSupporters: true,
              moduleGoal: true,
              moduleStats: false,
              moduleSidebars: ['home'],
            }).catch(() => {});
          }
        });
      });
    },
  },
];
