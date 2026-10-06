// Theme browser scenarios 23 to 29 of 14 section 20.3 (shipping, credits, free order, double submit, stock, unavailable methods, rate limit), vanilla theme.
// Ids TH-23 .. TH-29 are the numbers of the spec.
import { must } from '../lib/api.mjs';
import { coupon, grantCredits } from '../lib/bootstrap.mjs';
import { newContext } from '../lib/browser.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { addFromCard, product } from './lib/helpers.mjs';
import {
  address,
  applyCode,
  checkoutConfig,
  countCheckoutPosts,
  fillGuest,
  holdAtGateway,
  idem,
  myOrders,
  openCheckout,
  orderView,
  panelOrder,
  payBuyer,
  placeButton,
  signedIn,
  sleep,
  summaryText,
  takeProvokedErrors,
  text,
  waitForOrderPage,
  waitQuoted,
  withBankTransfer,
  withSettingsAndWait,
} from './lib/checkout.mjs';

const unique = () => Date.now().toString(36).slice(-6) + Math.floor(Math.random() * 99);

const methodRadio = (page, id) => page.locator(`input[type="radio"][value="${id}"]`);

async function pickMethod(page, id) {
  await methodRadio(page, id).check();
  await page.waitForFunction(
    (value) => document.querySelector(`input[type="radio"][value="${value}"]`)?.checked === true,
    id,
  );
}

/** Adds `quantity` units of `item` to the server cart of a signed-in buyer (the page then opens on a ready cart). */
async function putInCart(api, item, quantity = 1) {
  must(
    await api.post('/api/market/me/cart/items', { productId: item.id, quantity }),
    `cart item ${item.name}`,
  );
}

/**
 * A zone for DE and TR with two weight-priced methods and the catch-all zones switched off for the length of `fn` (restored afterwards):
 * "Standard" (0 to 1999 g = 4.90, free from an order value of 15) and "Express" (0 to 1999 g = 9.90).
 */
async function withShipping(admin, fn) {
  const n = unique();
  const zones = must(await admin.get('/api/panel/market/shipping/zones'), 'zones').json.zones;
  const others = zones.filter((z) => z.status === 'ACTIVE').map((z) => z.id);

  for (const id of others)
    must(
      await admin.put(`/api/panel/market/shipping/zones/${id}`, { status: 'INACTIVE' }),
      'zone off',
    );

  try {
    const zoneId = must(
      await admin.post('/api/panel/market/shipping/zones', {
        name: `E2E zone ${n}`,
        countries: ['DE', 'TR'],
        status: 'ACTIVE',
      }),
      'zone',
    ).json.id;
    const methodIds = [];

    try {
      for (const [name, price, free] of [
        [`E2E Standard ${n}`, 4.9, 15],
        [`E2E Express ${n}`, 9.9, null],
      ]) {
        const body = {
          name,
          providerId: 'manual',
          rateSource: 'RULES',
          status: 'ACTIVE',
          rates: [{ zoneId, basis: 'WEIGHT', rangeFrom: 0, rangeTo: 1999, price }],
        };

        if (free !== null) body.freeShippingThreshold = free;
        methodIds.push(
          must(await admin.post('/api/panel/market/shipping/methods', body), name).json.id,
        );
      }

      await sleep(1000);

      return await fn({ zoneId, standard: methodIds[0], express: methodIds[1], n });
    } finally {
      for (const id of methodIds)
        await admin.request('DELETE', `/api/panel/market/shipping/methods/${id}`);
      await admin.request('DELETE', `/api/panel/market/shipping/zones/${zoneId}`);
    }
  } finally {
    for (const id of others)
      await admin.put(`/api/panel/market/shipping/zones/${id}`, { status: 'ACTIVE' });
  }
}

/** The ids of the required fields of the shipping address form (the form lists every field; the country decides which are required). */
const shippingFieldIds = (page) =>
  page.locator('[id^="market-checkout-shipping-"]').evaluateAll((nodes) =>
    nodes
      .filter(
        (n) =>
          ['INPUT', 'SELECT'].includes(n.tagName) &&
          n.type !== 'radio' &&
          n.getAttribute('aria-required') === 'true',
      )
      .map((n) => n.id.replace('market-checkout-shipping-', ''))
      .filter((id) => id !== 'country' && id !== 'method')
      .sort(),
  );

async function fillAddress(page, kind, data, fields) {
  for (const field of fields) {
    if (field === 'country') {
      await page.locator(`#market-checkout-${kind}-country`).selectOption(data.country);
    } else {
      await page.locator(`#market-checkout-${kind}-${field}`).fill(data[field] ?? '');
    }
  }
}

export const scenarios = [
  {
    id: 'TH-23',
    title:
      'physical product: the address form follows the country, options appear after the address, free badge, no rate for the weight => shipping unavailable; the order keeps address and method',
    async run({ browser, env, admin, gateway, buyer }) {
      const parcel = await product(admin, 'Parcel', {
        price: '20.00',
        stock: 20,
        physical: 'true',
        weightGrams: '500',
      });
      const account = await payBuyer(buyer, admin, 'ship');
      const config = await checkoutConfig(account);

      await withShipping(admin, async (shipping) => {
        await putInCart(account, parcel, 1);

        const ctx = await signedIn(browser, account);
        const page = await ctx.page();
        const held = await holdAtGateway(page, gateway);

        await openCheckout(page, env);
        await waitQuoted(page);
        await page.getByRole('heading', { name: text('theme.checkout.shipping') }).waitFor({
          timeout: 30000,
        });
        assertEqual(
          await page.getByRole('group', { name: text('theme.checkout.shipping-method') }).count(),
          0,
          'no shipping options before an address is complete',
        );

        // the form follows the country
        const required = (code) =>
          [...(config.addressFields[code] ?? config.addressFields['*'])].sort();
        const shown = async (code) => {
          await page.locator('#market-checkout-shipping-country').selectOption(code);
          await page.waitForTimeout(300);

          return (await shippingFieldIds(page)).join(',');
        };

        assertEqual(
          await shown('TR'),
          required('TR')
            .filter((f) => f !== 'country')
            .sort()
            .join(','),
          'the TR form lists the TR fields of the checkout config',
        );
        assert((await shippingFieldIds(page)).includes('district'), 'TR requires the district');
        assert(
          !(await shippingFieldIds(page)).includes('postalCode'),
          'TR does not require a postal code',
        );
        assertEqual(
          await shown('DE'),
          required('DE')
            .filter((f) => f !== 'country')
            .sort()
            .join(','),
          'the DE form lists the default fields',
        );
        assert((await shippingFieldIds(page)).includes('postalCode'), 'DE requires a postal code');

        // an empty address: the button cannot be pressed (the order is not ready), and the required fields are marked
        await page.locator('input[type="radio"][value="fake"]').check();
        assertEqual(
          await placeButton(page).isDisabled(),
          true,
          'Pay is disabled without an address',
        );

        // a complete address: both methods appear; the free one carries the badge and no price
        const data = address('DE');
        await fillAddress(page, 'shipping', data, required('DE'));
        await page.locator('#market-checkout-shipping-postalCode').blur();

        const standard = page
          .locator('label.list-group-item')
          .filter({ hasText: `E2E Standard ${shipping.n}` });
        const express = page
          .locator('label.list-group-item')
          .filter({ hasText: `E2E Express ${shipping.n}` });

        await standard.waitFor({ timeout: 30000 });
        await express.waitFor({ timeout: 30000 });
        assert(
          (await standard.innerText()).includes(text('theme.checkout.shipping-free')),
          'Standard is free (the order value is above its threshold): the badge shows',
        );
        assert((await express.innerText()).includes('€9.90'), 'Express shows its price');

        await express.locator('input').check();
        await waitQuoted(page);
        await page.waitForFunction(
          () =>
            /Shipping\s*€9\.90/.test(
              document.querySelector('#market-checkout-summary-card')?.innerText ?? '',
            ),
          null,
          { timeout: 30000 },
        );
        assert(
          (await summaryText(page)).includes('€29.90'),
          `the total adds the shipping: ${await summaryText(page)}`,
        );

        await standard.locator('input').check();
        await page.waitForFunction(
          () =>
            /Total\s*€20\.00/.test(
              document.querySelector('#market-checkout-summary-card')?.innerText ?? '',
            ),
          null,
          { timeout: 30000 },
        );

        await placeButton(page).click();
        const publicId = await waitForOrderPage(page);
        assertEqual(held.held.length, 1, 'sent to the gateway once');

        const order = await panelOrder(admin, publicId);
        const dump = JSON.stringify(order);
        assert(
          dump.includes(data.line1) && dump.includes('Berlin'),
          'the order holds the shipping address',
        );
        assert(
          dump.includes(`E2E Standard ${shipping.n}`) ||
            dump.includes(`"${shipping.standard}"`) ||
            dump.includes(String(shipping.standard)),
          'the order holds the chosen method',
        );
        ctx.expectNoErrors('TH-23 options');
        await ctx.close();

        // no rate for 2500 g (5 units): no option can be offered
        const heavy = await payBuyer(buyer, admin, 'heavy');
        await putInCart(heavy, parcel, 5);
        const ctx2 = await signedIn(browser, heavy);
        const page2 = await ctx2.page();

        await openCheckout(page2, env);
        await waitQuoted(page2);
        await fillAddress(page2, 'shipping', data, required('DE'));
        await page2.locator('#market-checkout-shipping-postalCode').blur();
        await page2
          .getByText(text('theme.errors.SHIPPING_UNAVAILABLE'))
          .first()
          .waitFor({ timeout: 30000 });
        assertEqual(
          await page2.locator('input[name="market-checkout-shipping-method"]').count(),
          0,
          'no method is offered when no rate covers the weight',
        );
        await ctx2.close();
      });
    },
  },

  {
    id: 'TH-24',
    title:
      'credits: never pre-applied; full credit payment; mixed payment unchecked by default and "Use maximum" sends "MAX"; insufficient balance',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      // ---- full credit payment ----------------------------------------------------------------------------------------------------
      const rich = await payBuyer(buyer, admin, 'rich');
      await grantCredits(admin, rich.userId, 25);
      const ctx = await signedIn(browser, rich);
      const page = await ctx.page();

      await addFromCard(page, env, vip, vip.name);
      await openCheckout(page, env);
      await waitQuoted(page);

      const payRadio = methodRadio(page, 'credits');
      const mixed = page.getByLabel(/^Use my\s.*for part of this order$/);

      await payRadio.waitFor({ timeout: 30000 });
      assertEqual(await payRadio.isChecked(), false, 'paying with credits is not pre-selected');
      assertEqual(await mixed.isChecked(), false, 'the mixed credit box is not pre-ticked');

      // a choice made before a reload is not restored: the buyer confirms it again
      await mixed.check();
      await page
        .getByRole('button', { name: text('theme.checkout.credits-use-max') })
        .waitFor({ timeout: 30000 });
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.waitForFunction(() => window.__PANO_APP_BOOTED__ === true);
      await page.locator('#market-checkout-summary-card').waitFor({ timeout: 60000 });
      await waitQuoted(page);
      assertEqual(
        await page.getByLabel(/^Use my\s.*for part of this order$/).isChecked(),
        false,
        'after a reload the credits are not applied again on their own',
      );

      await methodRadio(page, 'credits').check();
      await waitQuoted(page);
      await page
        .getByRole('button', { name: /^(Pay |Complete order)/ })
        .waitFor({ timeout: 30000 });
      await placeButton(page).click();
      const full = await waitForOrderPage(page);
      await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 60000 });
      const paid = await orderView(rich, full);
      assertEqual(paid.status, 'COMPLETED', 'the credit order is completed at once');
      const balance = must(await rich.get('/api/market/me/credits'), 'credits').json;
      assertEqual(
        Number(balance.balance ?? balance.credits?.balance),
        15,
        'the order took 10 of the 25 credits',
      );
      ctx.expectNoErrors('TH-24 full');
      await ctx.close();

      // ---- mixed payment ------------------------------------------------------------------------------------------------------------
      const some = await payBuyer(buyer, admin, 'mixed');
      await grantCredits(admin, some.userId, 4);
      const ctx2 = await signedIn(browser, some);
      const page2 = await ctx2.page();
      const quotes = [];

      page2.on('request', (request) => {
        if (request.method() === 'POST' && request.url() === `${env.url}/api/market/checkout/quote`)
          quotes.push(JSON.parse(request.postData() || '{}'));
      });

      await addFromCard(page2, env, vip, vip.name);
      await openCheckout(page2, env);
      await waitQuoted(page2);
      await pickMethod(page2, 'fake');

      const box = page2.getByLabel(/^Use my\s.*for part of this order$/);
      assertEqual(await box.isChecked(), false, 'mixed payment starts unchecked');
      await box.check();
      await page2.getByRole('button', { name: text('theme.checkout.credits-use-max') }).click();
      await page2.waitForFunction(
        () =>
          /Credits used[^\n]*\n−€4\.00/.test(
            document.querySelector('#market-checkout-summary-card')?.innerText ?? '',
          ),
        null,
        { timeout: 30000 },
      );
      assert(
        quotes.some((q) => q.useCredits === 'MAX'),
        `"Use maximum" asks the quote with "MAX": ${JSON.stringify(quotes.map((q) => q.useCredits))}`,
      );
      assert(
        /To pay\s*€6\.00/.test(await summaryText(page2)),
        `6.00 is left for the gateway: ${await summaryText(page2)}`,
      );

      await placeButton(page2).click();
      const mixedId = await waitForOrderPage(page2);
      await page2.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 60000 });
      assertEqual(
        (await orderView(some, mixedId)).status,
        'COMPLETED',
        'the mixed order is completed',
      );
      const left = must(await some.get('/api/market/me/credits'), 'credits').json;
      assertEqual(Number(left.balance ?? left.credits?.balance), 0, 'all 4 credits were used');
      assert(
        [...gateway.payments.values()].some((p) => p.amount === '6.00'),
        'the gateway was asked for the remaining 6.00',
      );
      await ctx2.close();

      // ---- insufficient balance -------------------------------------------------------------------------------------------------------
      const poor = await payBuyer(buyer, admin, 'poor');
      await grantCredits(admin, poor.userId, 3);
      const ctx3 = await signedIn(browser, poor);
      const page3 = await ctx3.page();

      await addFromCard(page3, env, vip, vip.name);
      await openCheckout(page3, env);
      await waitQuoted(page3);
      await methodRadio(page3, 'credits').waitFor({ timeout: 30000 });
      assertEqual(
        await methodRadio(page3, 'credits').isDisabled(),
        true,
        'cannot pay with too few credits',
      );
      await page3
        .getByText(text('theme.errors.INSUFFICIENT_CREDITS'))
        .first()
        .waitFor({ timeout: 10000 });
      await ctx3.close();
    },
  },

  {
    id: 'TH-25',
    title: 'free order (100 % coupon): no picker, "Complete order", lands on a paid order page',
    async run({ browser, env, admin, catalogue }) {
      const { vip } = await catalogue();
      const full = await coupon(admin, 100);
      const ctx = await newContext(browser, { viewport: 'desktop' });
      const page = await ctx.page();
      const posts = countCheckoutPosts(page, env);

      await addFromCard(page, env, vip, vip.name);
      await openCheckout(page, env);
      await waitQuoted(page);
      assert(
        (await placeButton(page).innerText()).includes('€10.00'),
        'before the code the button says Pay €10.00',
      );

      const username = `Free_${unique()}`.slice(0, 16);
      await fillGuest(page, username, `${username.toLowerCase()}@example.com`);
      await waitQuoted(page);
      await applyCode(page, 'coupon', full.code);

      await page.getByText(text('theme.checkout.no-payment-needed')).waitFor({ timeout: 30000 });
      assertEqual(
        await page.locator('input[type="radio"][name="market-checkout-pay"]').count(),
        0,
        'no payment method is offered for a zero total',
      );
      const button = page.getByRole('button', { name: text('theme.checkout.complete') });
      await button.waitFor({ timeout: 30000 });
      assertEqual(await button.isDisabled(), false, 'the "Complete order" button is enabled');

      await button.click();
      const publicId = await waitForOrderPage(page);
      await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 60000 });
      assertEqual(posts.length, 1, 'one checkout request');
      assertEqual(
        JSON.parse(posts[0].body).paymentMethodId,
        undefined,
        'no payment method was sent for a free order',
      );

      const stored = await panelOrder(admin, publicId);
      assert(JSON.stringify(stored).includes('COMPLETED'), 'the order is completed in the panel');
      ctx.expectNoErrors('TH-25');
      await ctx.close();
    },
  },

  {
    id: 'TH-26',
    title:
      'a double click on Pay makes one order (same Idempotency-Key); a request aborted mid-flight and retried makes one order',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      // ---- double click -------------------------------------------------------------------------------------------------------------
      const first = await payBuyer(buyer, admin, 'dbl');
      const ctx = await signedIn(browser, first);
      const page = await ctx.page();
      const posts = countCheckoutPosts(page, env);

      await holdAtGateway(page, gateway);
      await addFromCard(page, env, vip, vip.name);
      await openCheckout(page, env);
      await waitQuoted(page);
      await pickMethod(page, 'fake');
      await placeButton(page).dblclick({ delay: 10 });
      await waitForOrderPage(page);

      assert(posts.length >= 1, 'a checkout request was sent');
      assertEqual(
        new Set(posts.map((p) => p.key)).size,
        1,
        'every request of the double click carries the same key',
      );
      assertEqual((await myOrders(first)).length, 1, 'the double click made exactly one order');
      await ctx.close();

      // the same key replayed through the API (what a lost answer retried does) returns the same order
      const replay = await payBuyer(buyer, admin, 'rpl');
      const key = idem();
      const body = { items: [{ productId: vip.id, quantity: 1 }], paymentMethodId: 'fake' };
      const one = must(await replay.post('/api/market/checkout', body, key), 'first checkout');
      const two = must(await replay.post('/api/market/checkout', body, key), 'replayed checkout');

      assertEqual(
        two.json.order.publicId,
        one.json.order.publicId,
        'the replay returns the same order',
      );
      assertEqual((await myOrders(replay)).length, 1, 'still one order after the replay');

      // ---- aborted mid-flight, then retried -------------------------------------------------------------------------------------------
      const second = await payBuyer(buyer, admin, 'abt');
      const ctx2 = await signedIn(browser, second);
      const page2 = await ctx2.page();
      const posts2 = countCheckoutPosts(page2, env);
      let aborted = false;

      await holdAtGateway(page2, gateway);
      await addFromCard(page2, env, vip, vip.name);
      await openCheckout(page2, env);
      await waitQuoted(page2);
      await pickMethod(page2, 'fake');

      await page2.route(`${env.url}/api/market/checkout`, async (route) => {
        if (route.request().method() !== 'POST' || aborted) return route.continue();

        aborted = true;
        await route.fetch(); // the server has the request and makes the order, the browser never sees the answer
        await route.abort('connectionreset');
      });

      await placeButton(page2).click();
      await page2.getByText(text('theme.checkout.network')).waitFor({ timeout: 30000 });
      assertEqual(
        (await myOrders(second)).length,
        1,
        'the order exists although the answer was lost',
      );

      await placeButton(page2).click();
      await waitForOrderPage(page2);
      assertEqual(posts2.length, 2, 'the page sent the request twice');
      assertEqual(posts2[0].key, posts2[1].key, 'the retry reuses the Idempotency-Key');
      assertEqual((await myOrders(second)).length, 1, 'the retry made no second order');
      takeProvokedErrors(
        ctx2,
        /ERR_|net::|Failed to load resource/,
        ctx2.errors.length,
        'TH-26 abort',
      );
      await ctx2.close();
    },
  },

  {
    id: 'TH-27',
    title: 'OUT_OF_STOCK between quote and submit: a line error and an alert, no order',
    async run({ browser, env, admin, buyer }) {
      const scarce = await product(admin, 'Scarce Pick', { price: '5.00', stock: 1 });
      const account = await payBuyer(buyer, admin, 'stock');
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();
      const posts = countCheckoutPosts(page, env);

      await putInCart(account, scarce, 1);
      await openCheckout(page, env);
      await waitQuoted(page);
      await pickMethod(page, 'fake');
      await waitQuoted(page);
      await page.waitForFunction(
        () =>
          [...document.querySelectorAll('button.btn-lg')].some(
            (b) => /Pay/.test(b.innerText) && !b.disabled,
          ),
        null,
        { timeout: 30000 },
      );
      await sleep(1500);

      // the last unit goes elsewhere after the buyer saw the quote
      must(
        await admin.post(`/api/panel/market/products/${scarce.id}/stock`, {
          mode: 'SET',
          value: 0,
        }),
        'sell out',
      );
      await placeButton(page).click();

      await page.getByText(text('theme.errors.OUT_OF_STOCK')).first().waitFor({ timeout: 30000 });
      assertEqual(posts.length, 1, 'the request was sent');
      assertEqual((await myOrders(account)).length, 0, 'no order was created');
      assert(
        (await page.locator('[role="alert"]').allInnerTexts()).some(
          (t) =>
            t.includes(text('theme.errors.OUT_OF_STOCK')) ||
            t.includes(text('theme.errors.INVALID_CART')),
        ),
        'an alert tells the buyer which line needs attention',
      );
      assertEqual(await placeButton(page).count(), 1, 'the form is still there to fix the cart');
      takeProvokedErrors(
        ctx,
        /status of 4\d\d/,
        ctx.errors.filter((e) => /status of 4\d\d/.test(e)).length,
        'TH-27',
      );
      ctx.expectNoErrors('TH-27');
      await ctx.close();
    },
  },

  {
    id: 'TH-28',
    title:
      'a method that cannot serve the order is a disabled row with the reason (test mode, guests, below the minimum)',
    async run({ browser, env, admin, buyer, catalogue }) {
      const { vip } = await catalogue();

      // a guest: the fake gateway is a test-mode method, the EUR one is for signed-in buyers
      // (with the bank transfer on, so that the picker lists its rows: with nothing available it only says so)
      await withBankTransfer(admin, async () => {
        const guest = await newContext(browser, { viewport: 'desktop' });
        const page = await guest.page();

        await addFromCard(page, env, vip, vip.name);
        await openCheckout(page, env);
        await waitQuoted(page);
        assertEqual(
          await methodRadio(page, 'fake').isDisabled(),
          true,
          'fake is disabled for a guest',
        );
        assertEqual(
          await methodRadio(page, 'fake-eur').isDisabled(),
          true,
          'fake-eur is disabled for a guest',
        );
        await page
          .locator('label.list-group-item')
          .filter({ has: methodRadio(page, 'fake') })
          .getByText(text('theme.errors.TEST_MODE'))
          .waitFor({ timeout: 10000 });
        await page
          .locator('label.list-group-item')
          .filter({ has: methodRadio(page, 'fake-eur') })
          .getByText(text('theme.errors.GUESTS_NOT_SUPPORTED'))
          .waitFor({ timeout: 10000 });
        await guest.close();
      });

      // a signed-in buyer, an order below the 1.00 minimum of the EUR gateway
      const tiny = await product(admin, 'Tiny Pick', { price: '0.50' });
      const account = await payBuyer(buyer, admin, 'min');
      const ctx = await signedIn(browser, account);
      const page2 = await ctx.page();

      await putInCart(account, tiny, 1);
      await openCheckout(page2, env);
      await waitQuoted(page2);
      assertEqual(
        await methodRadio(page2, 'fake').isDisabled(),
        false,
        'fake can serve this order',
      );
      assertEqual(
        await methodRadio(page2, 'fake-eur').isDisabled(),
        true,
        'fake-eur is below its minimum',
      );
      await page2
        .locator('label.list-group-item')
        .filter({ has: methodRadio(page2, 'fake-eur') })
        .getByText(text('theme.errors.AMOUNT_BELOW_MINIMUM'))
        .waitFor({ timeout: 10000 });
      await ctx.close();

      // a method whose admin currency restriction leaves out the store's currency (EUR): the `fake` method is
      // limited to USD (the provider itself takes any currency), the store sells in EUR
      const setCurrencies = async (currencies) =>
        must(
          await admin.post('/api/panel/market/payment-methods/fake', { config: { currencies } }),
          `fake currencies ${JSON.stringify(currencies)}`,
        );
      const account3 = await payBuyer(buyer, admin, 'cur');

      await setCurrencies(['USD']);

      try {
        await sleep(1500);

        const ctx3 = await signedIn(browser, account3);
        const page3 = await ctx3.page();

        await putInCart(account3, vip, 1);
        await openCheckout(page3, env);
        await waitQuoted(page3);
        assertEqual(
          await methodRadio(page3, 'fake').isDisabled(),
          true,
          'fake is disabled: its currency list leaves out EUR',
        );
        await page3
          .locator('label.list-group-item')
          .filter({ has: methodRadio(page3, 'fake') })
          .getByText(text('theme.errors.CURRENCY_NOT_SUPPORTED'))
          .waitFor({ timeout: 10000 });
        assertEqual(
          await methodRadio(page3, 'fake-eur').isDisabled(),
          false,
          'fake-eur still serves the EUR order',
        );
        await ctx3.close();
      } finally {
        await setCurrencies(null);
        await sleep(1500);
      }
    },
  },

  {
    id: 'TH-29',
    title:
      'rate limit: the quote limit shows a countdown and recovers on its own; the checkout limit locks the button with a countdown that ends',
    async run({ browser, env, admin, buyer, catalogue }) {
      const { vip } = await catalogue();

      // ---- the quote limit ------------------------------------------------------------------------------------------------------------
      const first = await payBuyer(buyer, admin, 'rlq');

      await withSettingsAndWait(
        admin,
        { quoteRateLimitPerMinute: 1 },
        { quoteRateLimitPerMinute: 100000 },
        async () => {
          const ctx = await signedIn(browser, first);
          const page = await ctx.page();

          await putInCart(first, vip, 1);
          await openCheckout(page, env);
          await waitQuoted(page);

          // the first quote used the allowance: the next input that needs a quote is answered "too many requests"
          const gift = page.getByRole('switch', { name: text('theme.checkout.gift-switch') });
          await gift.check();
          const recipient = page.getByLabel(text('theme.checkout.gift-recipient'));
          await recipient.fill('SomeoneElse');
          await recipient.blur();

          await page
            .getByText(/Too many requests\. Trying again in \d+ seconds\./)
            .first()
            .waitFor({
              timeout: 30000,
            });
          const seconds = Number(
            /in (\d+) seconds/.exec(
              await page
                .getByText(/Too many requests\. Trying again in \d+ seconds\./)
                .first()
                .innerText(),
            )?.[1],
          );
          assert(seconds > 0 && seconds <= 60, `the countdown is a real wait (${seconds}s)`);

          // the allowance comes back after a minute and the page asks again by itself
          await page
            .getByText(/Too many requests\. Trying again in \d+ seconds\./)
            .first()
            .waitFor({ state: 'detached', timeout: 120000 });
          await waitQuoted(page);
          takeProvokedErrors(
            ctx,
            /status of 429/,
            ctx.errors.filter((e) => /status of 429/.test(e)).length,
            'TH-29 quote',
          );
          ctx.expectNoErrors('TH-29 quote');
          await ctx.close();
        },
      );

      // ---- the checkout limit ---------------------------------------------------------------------------------------------------------
      const second = await payBuyer(buyer, admin, 'rlc');
      const free = await product(admin, 'Limit Pick', { price: '0.00' });

      await withSettingsAndWait(
        admin,
        { checkoutRateLimitPerMinute: 1 },
        { checkoutRateLimitPerMinute: 100000 },
        async () => {
          const ctx = await signedIn(browser, second);
          const page = await ctx.page();

          await putInCart(second, free, 1);
          await openCheckout(page, env);
          await waitQuoted(page);

          // another tab (here: the API) uses the one allowed checkout of this minute
          must(
            await second.post(
              '/api/market/checkout',
              { items: [{ productId: free.id, quantity: 1 }] },
              idem(),
            ),
            'the checkout that uses the allowance',
          );

          await placeButton(page).click();
          const note = page.getByText(/Too many requests\. Trying again in \d+ seconds\./);
          await note.first().waitFor({ timeout: 30000 });
          assertEqual(
            await placeButton(page).isDisabled(),
            true,
            'the button is locked while the countdown runs',
          );

          // the countdown runs down to 0 by itself (it is capped at 30 seconds) and the button comes back; the note stays until the buyer edits
          const secondsLeft = async () =>
            Number(/in (\d+) seconds/.exec(await note.first().innerText())?.[1]);
          const start = await secondsLeft();

          assert(start > 0 && start <= 30, `the countdown starts at a real wait (${start}s)`);
          await page.waitForFunction(
            () => {
              const button = [...document.querySelectorAll('button.btn-lg')].find((b) =>
                /Pay|Complete/.test(b.innerText),
              );

              return button && !button.disabled;
            },
            null,
            { timeout: 60000 },
          );
          assertEqual(await secondsLeft(), 0, 'the countdown ended at 0 when the button came back');
          takeProvokedErrors(
            ctx,
            /status of 429/,
            ctx.errors.filter((e) => /status of 429/.test(e)).length,
            'TH-29 checkout',
          );
          ctx.expectNoErrors('TH-29 checkout');
          await ctx.close();
        },
      );
    },
  },
];
