// UI-01 to UI-06 of 17 section 10 (T6 browser smoke, storefront side). Every scenario fails on a console error.
import fs from 'node:fs';
import { must } from '../lib/api.mjs';
import { newContext, setColorMode } from '../lib/browser.mjs';
import { BUYER_PASSWORD, coupon, grantCredits, grantUserNode } from '../lib/bootstrap.mjs';
import {
  assert,
  assertEqual,
  bodyBackground,
  noHorizontalScroll,
  open,
  rawPlaceholders,
} from '../lib/ui.mjs';

const PAY_NODE = 'pano.plugin.pano-plugin-market.manage.market.payments';

/** The store page restricted to this run's products (a search keeps the page small however many runs the instance has seen). */
const storeUrl = (env, tag) => `${env.url}/store?search=${encodeURIComponent(tag)}`;

/** The cart button of the navigation; on a phone the navbar is collapsed, so it is opened first. */
async function cartButton(page) {
  const button = page.getByRole('button', { name: 'Your cart' }).first();

  if (!(await button.isVisible().catch(() => false))) {
    const toggler = page.locator('.navbar-toggler:visible').first();
    if (await toggler.count()) await toggler.click();
  }

  return button;
}

async function waitForCards(page, count = 1) {
  await page
    .locator('.card h3 a')
    .nth(count - 1)
    .waitFor({ timeout: 60000 });
}

export const scenarios = [
  {
    id: 'UI-01',
    title:
      '/store renders categories and products, light and dark, 390 and 1280 px, tr / en-US / ru',
    async run({ browser, env, catalogue, buyer }) {
      const cat = await catalogue();
      // a visitor gets the platform language (en-US); tr and ru are the language of a signed-in account (`localeCode`)
      const reader = await buyer('lang');
      const combos = [
        { locale: 'en-US', viewport: 'desktop' },
        { locale: 'en-US', viewport: 'mobile' },
        { locale: 'tr', viewport: 'desktop', account: true },
        { locale: 'tr', viewport: 'mobile', account: true },
        { locale: 'ru', viewport: 'desktop', account: true },
        { locale: 'ru', viewport: 'mobile', account: true },
      ];

      for (const { locale, viewport, account } of combos) {
        if (account)
          must(
            await reader.put('/api/v1/profile', { localeCode: locale }),
            `switch the account to ${locale}`,
          );

        const ctx = await newContext(browser, {
          locale,
          viewport,
          cookies: account ? reader.playwrightCookies() : [],
        });
        const page = await ctx.page();
        const label = `UI-01 ${locale} ${viewport}${account ? ' (account)' : ''}`;

        await open(page, storeUrl(env, cat.vip.name.split(' ').pop()), (p) => waitForCards(p, 3));

        const text = await page.locator('body').innerText();
        const heading = JSON.parse(
          fs.readFileSync(
            new URL(`../../src/locales/theme/${locale}.json`, import.meta.url),
            'utf8',
          ),
        ).theme.store['all-products'];
        assert(text.includes(heading), `${label}: the store is in the language ("${heading}")`);
        assert(text.includes(cat.vip.name), `${label}: the product ${cat.vip.name} is listed`);
        assert(text.includes(cat.crate.name), `${label}: the product ${cat.crate.name} is listed`);

        const html = await page.content();
        assert(
          html.includes(cat.cat.name) && html.includes(cat.other.name),
          `${label}: both categories are in the category tree`,
        );

        const modes = {};

        for (const mode of ['light', 'dark']) {
          await setColorMode(page, mode);
          assertEqual(
            await page.evaluate(() => document.documentElement.getAttribute('data-bs-theme')),
            mode,
            `${label}: data-bs-theme`,
          );
          modes[mode] = await bodyBackground(page);
          await noHorizontalScroll(page, `${label} ${mode}`);
          assert(
            await page.locator('.card h3 a').first().isVisible(),
            `${label} ${mode}: a product card is visible`,
          );
        }

        assertEqual(
          (await rawPlaceholders(page)).length,
          0,
          `${label}: no unreplaced translation placeholder (${JSON.stringify(await rawPlaceholders(page))})`,
        );
        assert(
          modes.light !== modes.dark,
          `${label}: light and dark paint the page differently (${modes.light} vs ${modes.dark})`,
        );
        ctx.expectNoErrors(label);
        await ctx.close();
      }
    },
  },

  {
    id: 'UI-02',
    title: 'add to cart, badge count, cart offcanvas, /store/checkout',
    async run({ browser, env, catalogue }) {
      const cat = await catalogue();

      for (const viewport of ['desktop', 'mobile']) {
        const ctx = await newContext(browser, { viewport });
        const page = await ctx.page();
        const label = `UI-02 ${viewport}`;

        await open(page, storeUrl(env, cat.vip.name.split(' ').pop()), (p) => waitForCards(p, 3));

        const card = page.locator('.card', { hasText: cat.vip.name }).first();
        await card.getByRole('button', { name: 'Add to Cart' }).click();

        const button = await cartButton(page);
        await button.waitFor({ state: 'visible', timeout: 30000 });
        assertEqual(
          (await button.locator('.badge [aria-hidden="true"]').first().innerText()).trim(),
          '1',
          `${label}: the cart badge counts the line`,
        );

        await button.click();
        const offcanvas = page.locator('#marketCartOffcanvas');
        await offcanvas.waitFor({ state: 'visible', timeout: 30000 });
        await offcanvas.getByText(cat.vip.name).first().waitFor({ timeout: 30000 });
        assert(
          (await offcanvas.innerText()).includes('€10.00'),
          `${label}: the offcanvas shows the subtotal`,
        );
        assertEqual(
          (await rawPlaceholders(page)).length,
          0,
          `${label}: no unreplaced translation placeholder in the cart`,
        );

        // the offcanvas slides in (class `showing` until the transition ends): a click on the moving link never gets "stable"
        await page.waitForFunction(
          () => {
            const el = document.querySelector('#marketCartOffcanvas');
            return !!el && el.classList.contains('show') && !el.classList.contains('showing');
          },
          undefined,
          { timeout: 30000 },
        );
        await offcanvas.getByRole('link', { name: 'Checkout' }).click();
        await page.waitForURL('**/store/checkout', { timeout: 30000 });
        await page
          .getByRole('heading', { name: 'Order summary' })
          .first()
          .waitFor({ timeout: 60000 });
        assert(
          (await page.locator('body').innerText()).includes(cat.vip.name),
          `${label}: the checkout lists the product`,
        );

        ctx.expectNoErrors(label);
        await ctx.close();
      }
    },
  },
];

/** Waits for the checkout to settle (no quote in flight, no pending request), the way a person pauses before the pay button. */
async function settle(page) {
  await page.keyboard.press('Tab');
  await page.waitForLoadState('networkidle');
  await page.locator('.row[aria-busy="false"]').first().waitFor({ timeout: 30000 });
  await page.waitForTimeout(600);
}

async function addFromStore(page, env, product, tag) {
  await open(page, storeUrl(env, tag), (p) => waitForCards(p, 2));
  await page
    .locator('.card', { hasText: product.name })
    .first()
    .getByRole('button', { name: 'Add to Cart' })
    .click();
  await page.getByText('Added to cart.').first().waitFor({ timeout: 30000 });
}

async function openCheckout(page, env) {
  await page.goto(`${env.url}/store/checkout`, { waitUntil: 'domcontentloaded' });
  await page.getByRole('heading', { name: 'Order summary' }).first().waitFor({ timeout: 60000 });
}

const buyerCookies = (api) => api.playwrightCookies();

scenarios.push(
  {
    id: 'UI-03',
    title: 'guest checkout: validation messages, order placed, order page shows paid',
    async run({ browser, env, catalogue }) {
      const cat = await catalogue();
      const tag = cat.free.name.split(' ').pop();
      const ctx = await newContext(browser, { viewport: 'desktop' });
      const page = await ctx.page();

      await addFromStore(page, env, cat.free, tag);
      await openCheckout(page, env);

      // 06 section 6.7 (V-03): a guest never uses a test-mode method, so the fake gateway is not offered; the free kit needs no method
      const submit = page.getByRole('button', { name: /Complete order|^Pay/ });
      await submit.waitFor({ timeout: 30000 });
      await settle(page);
      await submit.click({ force: true });

      const required = page.locator('.invalid-feedback:visible');
      await required.first().waitFor({ timeout: 15000 });
      assertEqual(await required.count(), 2, 'UI-03: both buyer fields report "required"');
      assert(
        (await required.first().innerText()).includes('required'),
        'UI-03: the message says the field is required',
      );

      const username = `Guest${Date.now().toString(36).slice(-7)}`;
      await page.getByLabel('Minecraft username').fill(username);
      await page.getByLabel('E-mail').fill('not-an-email');
      await page.getByLabel('E-mail').blur();
      await page.getByText('Enter a valid e-mail address.').waitFor({ timeout: 15000 });

      await page.getByLabel('E-mail').fill(`${username.toLowerCase()}@example.com`);
      await settle(page);
      await page.getByRole('button', { name: 'Complete order' }).click();

      await page.waitForURL('**/store/order/**', { timeout: 60000 });
      await page.getByText('Payment received. Thank you!').first().waitFor({ timeout: 60000 });
      ctx.expectNoErrors('UI-03');
      await ctx.close();
    },
  },

  {
    id: 'UI-04',
    title:
      'logged-in checkout: coupon, credits never pre-applied, fake gateway redirect and return, paid after polling',
    async run({ browser, env, catalogue, admin, buyer }) {
      const cat = await catalogue();
      const tag = cat.vip.name.split(' ').pop();
      const api = await buyer('pay');
      await grantUserNode(admin, api.userId, PAY_NODE);
      await grantCredits(admin, api.userId, 3);
      const code = await coupon(admin, 10);

      const ctx = await newContext(browser, { viewport: 'desktop', cookies: buyerCookies(api) });
      const page = await ctx.page();

      await addFromStore(page, env, cat.vip, tag);
      await openCheckout(page, env);

      // the logged-in buyer is shown, not asked
      await page.getByText(api.username).first().waitFor({ timeout: 30000 });

      // credits are never pre-applied: the box is unchecked and the order total is the full price
      const useCredits = page.getByLabel(/Use my .* for part of this order/);
      await useCredits.waitFor({ timeout: 30000 });
      assert(!(await useCredits.isChecked()), 'UI-04: credits are not pre-applied');
      const summary = page.locator('#market-checkout-summary');
      assert(
        (await summary.innerText()).includes('€10.00'),
        'UI-04: the total is the full price before credits',
      );

      // coupon
      await page.getByLabel('Coupon code').fill(code.code);
      await page.getByRole('button', { name: 'Apply' }).first().click();
      await page.getByText(`Coupon ${code.code}`).first().waitFor({ timeout: 30000 });
      assert(
        (await summary.innerText()).includes('€9.00'),
        'UI-04: 10 % off a 10.00 product leaves 9.00',
      );

      // the buyer opts in to the credits (3 credits = 3.00): 6.00 is left for the gateway
      await useCredits.check();
      await page.getByText('Applied to this order').waitFor({ timeout: 30000 });
      await settle(page);
      assert(
        (await summary.innerText()).includes('€6.00'),
        'UI-04: 3.00 of credits leave 6.00 to pay',
      );

      const fake = page.getByRole('radio', { name: /Fake gateway/ }).first();
      await fake.check();
      await settle(page);

      await page.getByRole('button', { name: /^Pay/ }).click();

      // the fake gateway page marks the payment paid, sends the webhook and returns the buyer to the order page
      await page.waitForURL(/\/store\/order\//, { timeout: 90000 });
      await page.getByText('Payment received. Thank you!').first().waitFor({ timeout: 90000 });
      assert(
        (await page.locator('body').innerText()).includes('Order #'),
        'UI-04: the order page shows the order number',
      );
      ctx.expectNoErrors('UI-04');
      await ctx.close();
    },
  },

  {
    id: 'UI-05',
    title:
      'product page: variant change updates the price, a required custom field blocks add to cart',
    async run({ browser, env, catalogue }) {
      const cat = await catalogue();
      const ctx = await newContext(browser, { viewport: 'desktop' });
      const page = await ctx.page();

      await open(page, `${env.url}/store/${cat.crate.slug}`, (p) =>
        p.getByRole('heading', { name: cat.crate.name }).first().waitFor({ timeout: 60000 }),
      );
      const main = page.locator('main, .container').first();

      // the first variant is preselected; the options are btn-check radios, so the visible labels are clicked
      await page.getByText('€4.00').first().waitFor({ timeout: 15000 });
      await page.locator('label.btn', { hasText: /^L$/ }).click();
      await page.getByText('€7.00').first().waitFor({ timeout: 15000 });
      assert(
        !(await main.innerText()).includes('€4.00'),
        'UI-05: the price of S is gone after choosing L',
      );

      // a required custom field: add to cart is refused until it is filled
      await open(page, `${env.url}/store/${cat.engraved.slug}`, (p) =>
        p.getByRole('heading', { name: cat.engraved.name }).first().waitFor({ timeout: 60000 }),
      );
      // the cart button shows on every market page (count 0 included), so the number on its badge is what is read
      const cartCount = async () => {
        const badge = page
          .getByRole('button', { name: 'Your cart' })
          .first()
          .locator('.badge [aria-hidden="true"]');
        if (!(await badge.count())) return 0;
        return Number((await badge.first().innerText()).trim()) || 0;
      };
      await page.getByRole('button', { name: 'Add to Cart' }).click();
      const field = page.getByLabel('Engraving text');
      await page.locator('.invalid-feedback:visible').first().waitFor({ timeout: 15000 });
      assertEqual(
        await field.getAttribute('aria-invalid'),
        'true',
        'UI-05: the empty required field is marked invalid',
      );
      assertEqual(await cartCount(), 0, 'UI-05: nothing was added to the cart');

      await field.fill('Steve');
      await page.getByRole('button', { name: 'Add to Cart' }).click();
      await page.getByText('Added to cart.').first().waitFor({ timeout: 30000 });
      await page.waitForFunction(
        () =>
          document
            .querySelector('button[aria-label="Your cart"] .badge [aria-hidden="true"]')
            ?.textContent?.trim() === '1',
        null,
        { timeout: 30000 },
      );
      assertEqual(await cartCount(), 1, 'UI-05: the filled product is in the cart');

      ctx.expectNoErrors('UI-05');
      await ctx.close();
    },
  },
);

scenarios.push({
  id: 'UI-06',
  title: 'profile tabs: purchase history, credits, subscriptions (cancel)',
  async run({ browser, env, admin, buyer }) {
    const { product } = await import('../lib/bootstrap.mjs');
    const plan = await product(admin, 'Monthly Plan', {
      price: '6.00',
      billingMode: 'SUBSCRIPTION',
      periodUnit: 'MONTH',
      periodCount: '1',
      actions: JSON.stringify([{ id: 'a1', type: 'CREDIT', phase: 'GRANT', value: 1 }]),
    });
    const api = await buyer('prof');
    await grantUserNode(admin, api.userId, PAY_NODE);
    await grantCredits(admin, api.userId, 2);

    const ctx = await newContext(browser, { viewport: 'desktop', cookies: buyerCookies(api) });
    const page = await ctx.page();

    // subscribe through the storefront and the fake gateway (it hands over a stored method, which the renewals charge)
    await open(page, `${env.url}/store/${plan.slug}`, (p) =>
      p.getByRole('heading', { name: plan.name }).first().waitFor({ timeout: 60000 }),
    );
    await page.getByRole('button', { name: 'Subscribe' }).click();
    await page.waitForURL('**/store/checkout', { timeout: 60000 });
    await page.getByRole('heading', { name: 'Order summary' }).first().waitFor({ timeout: 60000 });
    await page
      .getByRole('radio', { name: /Fake gateway/ })
      .first()
      .check();
    await settle(page);
    await page.getByRole('button', { name: /^Pay/ }).click();
    await page.waitForURL(/\/store\/order\//, { timeout: 90000 });
    await page.getByText('Payment received. Thank you!').first().waitFor({ timeout: 90000 });

    // purchase history lists the order
    await page.goto(`${env.url}/profile/purchases`, { waitUntil: 'domcontentloaded' });
    await page.getByRole('heading', { name: 'Orders' }).first().waitFor({ timeout: 60000 });
    await page.locator('table').first().waitFor({ timeout: 30000 });
    assert(
      (await page.locator('table').first().innerText()).includes(plan.name),
      'UI-06: the purchase history lists the subscription order',
    );

    // credits: balance and the ledger rows (the grant of 2 and the plan's reward of 1)
    await page.getByRole('link', { name: 'Credits' }).first().click();
    await page.waitForURL('**/profile/credits', { timeout: 30000 });
    await page.getByRole('heading', { name: 'Credit history' }).waitFor({ timeout: 60000 });
    const credits = await page.locator('body').innerText();
    assert(credits.includes('Credits granted'), 'UI-06: the ledger shows the grant');

    // subscriptions: the active one, cancelled through the confirmation dialog
    await page.getByRole('link', { name: 'Subscriptions' }).first().click();
    await page.waitForURL('**/profile/subscriptions', { timeout: 30000 });
    await page.getByText(plan.name).first().waitFor({ timeout: 60000 });
    await page.waitForLoadState('networkidle');
    assertEqual(
      (await rawPlaceholders(page)).length,
      0,
      `UI-06: no unreplaced translation placeholder on the subscriptions page (${JSON.stringify(await rawPlaceholders(page))})`,
    );
    await page.getByRole('button', { name: 'Cancel subscription' }).first().click();
    await page
      .getByRole('heading', { name: 'Cancel this subscription?' })
      .waitFor({ timeout: 15000 });
    await page.locator('.modal.show').getByRole('button', { name: 'Cancel subscription' }).click();
    await page
      .getByText(/Ends on .* It will not renew\./)
      .first()
      .waitFor({ timeout: 60000 });

    ctx.expectNoErrors('UI-06');
    await ctx.close();
  },
});
