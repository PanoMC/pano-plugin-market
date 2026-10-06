// UI-07 to UI-09 of 17 section 10 (T6 browser smoke, panel side), served by the instance at /panel. Every scenario fails on a console error.
import crypto from 'node:crypto';
import fs from 'node:fs';
import { Api, must } from '../lib/api.mjs';
import { newContext } from '../lib/browser.mjs';
import { completePayment } from '../lib/gateway.mjs';
import {
  BUYER_PASSWORD,
  adminSession,
  grantCredits,
  grantUserNode,
  product,
} from '../lib/bootstrap.mjs';
import { assert, assertEqual, open, panelOpen, rawKeys, rawPlaceholders } from '../lib/ui.mjs';

const PAY_NODE = 'pano-plugin-market.manage.market.payments';
const node = (suffix) => `pano.plugin.pano-plugin-market.${suffix}`;

/**
 * A buyer who paid partly with credits and partly through the fake gateway (a "mixed" order, 06 section 7), made through the
 * storefront API; the gateway page is opened to deliver the signed webhook, so the order ends COMPLETED.
 */
async function mixedOrder({ env, admin, buyer, gateway }, cat) {
  const api = await buyer('mix');
  await grantUserNode(admin, api.userId, node('manage.market.payments'));
  await grantCredits(admin, api.userId, 3);

  const res = must(
    await api.post(
      '/api/market/checkout',
      { items: [{ productId: cat.vip.id, quantity: 1 }], paymentMethodId: 'fake', useCredits: 3 },
      { 'Idempotency-Key': crypto.randomUUID() },
    ),
    'checkout of the mixed order',
  );
  const publicId = res.json.order.publicId;
  const payUrl = res.json.payment?.url ?? res.json.payment?.payUrl ?? res.json.payment?.redirectUrl;
  assert(
    payUrl,
    `UI-07: the checkout returned a payment URL (payment=${JSON.stringify(res.json.payment)?.slice(0, 200)})`,
  );
  await completePayment(payUrl); // the fake gateway page: marks paid, sends the signed webhook, returns the buyer

  for (let i = 0; i < 60; i++) {
    const view = await api.get(`/api/market/orders/${publicId}`);
    if (view.json?.order?.status === 'COMPLETED')
      return { api, publicId, number: view.json.order.number };
    await new Promise((resolve) => setTimeout(resolve, 500));
  }

  const last = await api.get(`/api/market/orders/${publicId}`);
  throw new Error(`UI-07: the mixed order did not complete: status ${last.json?.order?.status}`);
}

export const scenarios = [
  {
    id: 'UI-07',
    title:
      'panel: product create with a variant, order list, order detail, refund dialog warns about a split refund',
    async run(ctx) {
      const { browser, env, admin, catalogue } = ctx;
      const cat = await catalogue();
      const order = await mixedOrder(ctx, cat);
      const pc = await newContext(browser, {
        viewport: 'desktop',
        cookies: admin.playwrightCookies(),
      });
      const page = await pc.page();

      // 1. a product with a variant, through the editor
      const name = `Panel Variant ${crypto.randomUUID().slice(0, 6)}`;
      await panelOpen(page, env, '/market/products/create-product', (p) =>
        p.locator('#product-name').waitFor({ timeout: 60000 }),
      );
      await page.locator('#product-name').fill(name);
      await page.getByRole('tab', { name: 'Pricing' }).click();
      await page.locator('#product-price').fill('5.00');
      await page.getByRole('tab', { name: 'Variants' }).click();
      await page.locator('#product-has-variants').check();
      await page.getByRole('button', { name: 'Actions' }).first().click();
      await page.getByRole('button', { name: 'Add Option' }).click();
      await page.getByLabel('Option name, e.g. Size').fill('Size');
      await page.getByRole('button', { name: 'Add Value' }).click();
      await page.getByLabel('Value, e.g. Large').first().fill('Small');
      await page.getByRole('button', { name: 'Add Value' }).click();
      await page.getByLabel('Value, e.g. Large').nth(1).fill('Large');
      await page.getByRole('button', { name: 'Generate Variants' }).click();
      await page.getByText(/2 added/).waitFor({ timeout: 15000 });
      await page.getByRole('button', { name: 'Save' }).click();

      // saved: the catalogue holds the product with its two variants (the editor saves, then moves to the product's own route)
      let created;
      for (let i = 0; i < 60 && !created; i++) {
        const found = must(
          await admin.get(`/api/panel/market/products?search=${encodeURIComponent(name)}`),
          'find the new product',
        ).json;
        created = (found.products ?? [])[0];
        if (!created) await new Promise((resolve) => setTimeout(resolve, 500));
      }
      assert(created, 'UI-07: the product created in the editor exists');
      const detail = must(
        await admin.get(`/api/panel/market/products/${created.id}`),
        'product detail',
      ).json;
      assertEqual(
        (detail.product?.variants ?? detail.variants ?? []).length,
        2,
        'UI-07: the product has its two variants',
      );

      // 2. the order list shows the mixed order
      await panelOpen(page, env, '/market/orders', (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );

      // 3. the order detail and its refund dialog: a split order warns before it refunds
      await panelOpen(page, env, `/market/orders/detail/${order.number}`, (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );
      await page
        .getByRole('button', { name: /Refund/ })
        .first()
        .click()
        .catch(async () => {
          await page.getByRole('button', { name: 'Actions' }).first().click();
          await page
            .getByRole('button', { name: /Refund/ })
            .first()
            .click();
        });
      await page.getByRole('heading', { name: 'Refund Order' }).waitFor({ timeout: 30000 });
      await page.getByText('This Refund Is Split', { exact: true }).waitFor({ timeout: 30000 });
      await page.getByLabel('I understand this refund is split').waitFor({ timeout: 15000 });

      pc.expectNoErrors('UI-07');
      await pc.close();
    },
  },
];

scenarios.push(
  {
    id: 'UI-08',
    title:
      'panel: payment provider form rendered from the schema (secret masked, webhook URL copyable), toggle',
    async run({ browser, env, admin, gateway }) {
      const pc = await newContext(browser, {
        viewport: 'desktop',
        cookies: admin.playwrightCookies(),
      });
      await pc.context.grantPermissions(['clipboard-read', 'clipboard-write'], { origin: env.url });
      const page = await pc.page();

      await panelOpen(page, env, '/market/settings?section=payments', (p) =>
        p.locator('.card', { hasText: 'Fake gateway' }).first().waitFor({ timeout: 60000 }),
      );

      // the schema form: required URL, masked secret, select, switch, read-only webhook URL
      const card = page
        .locator('.card', {
          hasText: 'Test-only gateway that talks to the fake payment simulator.',
        })
        .first();
      await card.getByRole('button', { name: /Configure/ }).click();
      const secret = page.locator('#pm-field-secret');
      await secret.waitFor({ timeout: 30000 });
      assertEqual(
        await secret.getAttribute('type'),
        'password',
        'UI-08: the secret field is a password input',
      );
      assert(
        (await secret.inputValue()) !== gateway.secret,
        'UI-08: the stored secret is never sent back in clear',
      );
      assertEqual(
        await page.locator('#pm-field-gatewayUrl').getAttribute('type'),
        'url',
        'UI-08: the gateway URL is a url field',
      );
      assertEqual(
        await page.locator('#pm-field-startKind').evaluate((el) => el.tagName),
        'SELECT',
        'UI-08: the start kind is a select',
      );
      assertEqual(
        await page.locator('#pm-field-statusQuery').getAttribute('type'),
        'checkbox',
        'UI-08: the status query is a switch',
      );

      // the webhook URL is read-only and has a copy button
      const webhook = page.locator('#pm-field-webhook');
      assert(
        (await webhook.isDisabled()) || (await webhook.getAttribute('readonly')) !== null,
        'UI-08: the webhook URL is read-only',
      );
      const url = await webhook.inputValue();
      assert(
        url.endsWith('/api/market/payments/fake/webhook'),
        `UI-08: the webhook URL points at the provider route (${url})`,
      );
      await page.locator('.modal.show').getByRole('button', { name: 'Copy' }).click();
      await page.waitForFunction(
        async (expected) => (await navigator.clipboard.readText()) === expected,
        url,
        { timeout: 15000 },
      );
      await page.locator('.modal.show').getByRole('button', { name: 'Close' }).first().click();
      await page.locator('.modal.show').waitFor({ state: 'detached', timeout: 15000 });

      // the toggle: off, then on again (the provider's own enable switch, checked against the API)
      const stateOf = async () =>
        must(
          await admin.get('/api/panel/market/payment-providers'),
          'providers',
        ).json.providers.find((p) => p.id === 'fake').config.enabled;
      const toggle = card.getByLabel(/Enable Fake gateway/);
      assertEqual(await stateOf(), true, 'UI-08: the provider starts enabled');
      await toggle.uncheck();
      for (let i = 0; i < 40 && (await stateOf()) !== false; i++)
        await new Promise((r) => setTimeout(r, 250));
      assertEqual(await stateOf(), false, 'UI-08: the switch disabled the provider');
      await toggle.check();
      for (let i = 0; i < 40 && (await stateOf()) !== true; i++)
        await new Promise((r) => setTimeout(r, 250));
      assertEqual(await stateOf(), true, 'UI-08: the switch enabled the provider again');

      pc.expectNoErrors('UI-08');
      await pc.close();
    },
  },

  {
    id: 'UI-09',
    title:
      'panel: a user with only view.market.orders sees the orders page and no settings / refund / create button',
    async run(ctx) {
      const { browser, env, admin, catalogue } = ctx;
      const cat = await catalogue();
      const order = await mixedOrder(ctx, cat);
      const api = await ctx.buyer('view');
      await grantUserNode(admin, api.userId, 'pano.panel.access.panel');
      await grantUserNode(admin, api.userId, node('view.market.orders'));

      const pc = await newContext(browser, {
        viewport: 'desktop',
        cookies: api.playwrightCookies(),
      });
      const page = await pc.page();

      await panelOpen(page, env, '/market/orders', (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );
      assert(
        (await page.getByRole('link', { name: 'Create Order' }).count()) === 0,
        'UI-09: no "Create Order" button',
      );

      // the section navigation of the market has no Settings entry for this user
      const marketNav = page.getByRole('navigation').filter({ hasText: 'Orders' });
      assert(
        !(await marketNav.filter({ hasText: 'Payment Methods' }).count()),
        'UI-09: no settings section in the market navigation',
      );

      // the order detail has no refund entry
      await panelOpen(page, env, `/market/orders/detail/${order.number}`, (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );
      assertEqual(
        await page.getByRole('button', { name: /Refund/ }).count(),
        0,
        'UI-09: no refund button on the order detail',
      );

      // the settings page is refused, not rendered
      await panelOpen(page, env, '/market/settings');
      await page.waitForTimeout(1500);
      const text = await page.locator('body').innerText();
      assert(
        !text.includes('Payment Methods') && !text.includes('Test Mode'),
        'UI-09: the settings page shows no settings',
      );
      assert(
        text.includes('You do not have permission'),
        'UI-09: the settings page says the user lacks the permission',
      );

      pc.expectNoErrors('UI-09');
      await pc.close();
    },
  },
);

scenarios.push({
  id: 'UI-10',
  title:
    'language switch tr / en-US / ru on /store and one panel page: texts follow the language, no raw i18n key',
  async run({ browser, env, admin, catalogue, buyer }) {
    const cat = await catalogue();
    const read = (side, lang) =>
      JSON.parse(
        fs.readFileSync(new URL(`../../src/locales/${side}/${lang}.json`, import.meta.url), 'utf8'),
      );
    const seen = { store: new Set(), panel: new Set() };

    // the language of a signed-in user is the account's `localeCode` (a visitor gets the platform language), so one staff account switches it
    const api = await buyer('lang');
    await grantUserNode(admin, api.userId, 'pano.panel.access.panel');
    await grantUserNode(admin, api.userId, node('view.market.orders'));
    await api.post('/api/panel/dismissWhatsNew', { version: '1' });

    for (const lang of ['tr', 'en-US', 'ru']) {
      must(await api.put('/api/profile', { localeCode: lang }), `switch the account to ${lang}`);
      const theme = read('theme', lang).theme.store;
      const panel = read('panel', lang).pages.orders;

      const shopper = await newContext(browser, {
        locale: lang,
        viewport: 'desktop',
        cookies: api.playwrightCookies(),
      });
      const storePage = await shopper.page();
      await open(
        storePage,
        `${env.url}/store?search=${encodeURIComponent(cat.vip.name.split(' ').pop())}`,
        (p) => p.locator('.card h3 a').first().waitFor({ timeout: 60000 }),
      );
      const storeText = await storePage.locator('body').innerText();
      assert(
        storeText.includes(theme['all-products']),
        `UI-10 ${lang}: /store shows "${theme['all-products']}"`,
      );
      assert(
        (await storePage.getByLabel(theme['search-label']).count()) > 0,
        `UI-10 ${lang}: the search field is labelled "${theme['search-label']}"`,
      );
      const keys = await rawKeys(storePage);
      assertEqual(
        keys.length,
        0,
        `UI-10 ${lang}: no raw i18n key on /store: ${JSON.stringify(keys)}`,
      );
      const holes = await rawPlaceholders(storePage);
      assertEqual(
        holes.length,
        0,
        `UI-10 ${lang}: no unreplaced placeholder on /store: ${JSON.stringify(holes)}`,
      );
      seen.store.add(theme['all-products']);
      shopper.expectNoErrors(`UI-10 ${lang} store`);
      await shopper.close();

      const staff = await newContext(browser, {
        locale: lang,
        viewport: 'desktop',
        cookies: api.playwrightCookies(),
      });
      const panelPage = await staff.page();
      await panelOpen(panelPage, env, '/market/orders', (p) =>
        p.getByText(panel.tab.pending, { exact: true }).first().waitFor({ timeout: 60000 }),
      );
      const panelText = await panelPage.locator('body').innerText();
      assert(
        panelText.includes(panel.tab.all) && panelText.includes(panel.tab.pending),
        `UI-10 ${lang}: the orders page is in the language`,
      );
      const panelKeys = await rawKeys(panelPage);
      assertEqual(
        panelKeys.length,
        0,
        `UI-10 ${lang}: no raw i18n key on the orders page: ${JSON.stringify(panelKeys)}`,
      );
      assertEqual(
        (await rawPlaceholders(panelPage)).length,
        0,
        `UI-10 ${lang}: no unreplaced placeholder on the orders page`,
      );
      seen.panel.add(panel.tab.pending);
      staff.expectNoErrors(`UI-10 ${lang} panel`);
      await staff.close();
    }

    assertEqual(seen.store.size, 3, 'UI-10: /store reads differently in the three languages');
    assertEqual(
      seen.panel.size,
      3,
      'UI-10: the orders page reads differently in the three languages',
    );
  },
});
