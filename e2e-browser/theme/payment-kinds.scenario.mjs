// Theme browser scenarios 30 to 36 of 14 section 20.3 (one per payment kind), vanilla theme. Ids TH-30 .. TH-36 are the numbers of the spec.
// The fake provider is configured per kind (`startKind`); a signed-in buyer with the PAY node pays (the fake gateway is a test-mode method).
// The gateway's pay page is answered locally (holdAtGateway) so that every step the browser takes towards the gateway is recorded and the
// payment stays unpaid until the scenario releases it (signed webhook), which is what the order page then polls for.
import { must, MARKET_API, PANEL_MARKET_API } from '../lib/api.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { addFromCard } from './lib/helpers.mjs';
import {
  holdAtGateway,
  openCheckout,
  orderView,
  panelOrder,
  payBuyer,
  placeButton,
  signedIn,
  sleep,
  text,
  waitForOrderPage,
  waitQuoted,
  withBankTransfer,
  withStartKind,
} from './lib/checkout.mjs';

const methodRadio = (page, id) => page.locator(`input[type="radio"][value="${id}"]`);

/** Signed-in buyer on the checkout with the vip product and the given method chosen; returns what the scenario needs. */
async function prepare({
  browser,
  env,
  admin,
  gateway,
  buyer,
  vip,
  label,
  method = 'fake',
  hold = {},
}) {
  const account = await payBuyer(buyer, admin, label);
  const ctx = await signedIn(browser, account);
  const page = await ctx.page();
  const held = await holdAtGateway(page, gateway, hold);
  const checkouts = [];

  // the checkout answer is read on its way (the page navigates away from the document as soon as it has it)
  await page.route(`${env.url}${MARKET_API}/checkout`, async (route) => {
    if (route.request().method() !== 'POST') return route.continue();

    const response = await route.fetch();

    try {
      checkouts.push(await response.json());
    } catch {
      /* not JSON */
    }

    await route.fulfill({ response });
  });

  await addFromCard(page, env, vip, vip.name);
  await openCheckout(page, env);
  await waitQuoted(page);
  await methodRadio(page, method).check();
  await waitQuoted(page);

  return { account, ctx, page, held, checkouts };
}

const paid = (page) =>
  page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 90000 });

/** Registers a view-slot item through the plugin API of the host: the market's own bundle exposes `pano` to the test (see patchPluginBundle). */
async function patchPluginBundle(context) {
  await context.route(
    /\/plugins\/pano-plugin-market\/resources\/plugin-ui\/client\/main-[^/]+\.js$/,
    async (route) => {
      const response = await route.fetch();
      const source = await response.text();

      // the only change: the plugin instance hands its `pano` object to the test (a stand-in for a second plugin's own entry point)
      await route.fulfill({
        response,
        body: source.replace('onLoad(){', 'onLoad(){globalThis.__E2E_PANO__=this.pano;'),
      });
    },
  );
}

export const scenarios = [
  {
    id: 'TH-30',
    title:
      'REDIRECT: the browser leaves for the gateway page, comes back and the order is paid after the webhook',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      await withStartKind(admin, gateway, 'REDIRECT', async () => {
        const { account, ctx, page, held, checkouts } = await prepare({
          browser,
          env,
          admin,
          gateway,
          buyer,
          vip,
          label: 'k30',
        });

        await placeButton(page).click();
        const publicId = await waitForOrderPage(page);

        assertEqual(checkouts.length, 1, 'one checkout answer');
        assertEqual(checkouts[0].payment.kind, 'REDIRECT', 'the start kind is REDIRECT');
        assert(/\/pay\//.test(checkouts[0].payment.url), 'it carries the gateway page URL');
        assertEqual(held.requests.length, 1, 'the browser asked the gateway page once');
        assertEqual(held.requests[0].method, 'GET', 'with a GET');
        assertEqual(held.requests[0].topLevel, true, 'as the top-level page (a real redirect)');

        await held.release();
        await paid(page);
        assertEqual(
          (await orderView(account, publicId)).status,
          'COMPLETED',
          'the order is completed',
        );
        ctx.expectNoErrors('TH-30');
        await ctx.close();
      });
    },
  },

  {
    id: 'TH-31',
    title: 'FORM_POST: the market page auto-submits a POST form to the gateway',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      await withStartKind(admin, gateway, 'FORM_POST', async () => {
        const { account, ctx, page, held, checkouts } = await prepare({
          browser,
          env,
          admin,
          gateway,
          buyer,
          vip,
          label: 'k31',
        });

        await placeButton(page).click();
        const publicId = await waitForOrderPage(page);

        assertEqual(checkouts[0].payment.kind, 'FORM_POST', 'the start kind is FORM_POST');
        assert(
          checkouts[0].payment.url.includes(`${MARKET_API}/payments/attempts/`),
          `the buyer is sent to the market attempt page, not to the gateway: ${checkouts[0].payment.url}`,
        );
        assertEqual(held.requests.length, 1, 'the gateway was reached once');
        assertEqual(
          held.requests[0].method,
          'POST',
          'with a POST, made by the auto-submitted form',
        );
        assertEqual(held.requests[0].topLevel, true, 'as the top-level page');
        const posted = new URLSearchParams(held.requests[0].postData ?? '');
        assertEqual(
          posted.get('reference'),
          held.requests[0].reference,
          'the form carries the payment reference',
        );
        assertEqual(posted.get('amount'), '10.00', 'and the amount');
        assertEqual(posted.get('currency'), 'EUR', 'and the currency');

        await held.release();
        await paid(page);
        assertEqual(
          (await orderView(account, publicId)).status,
          'COMPLETED',
          'the order is completed',
        );
        ctx.expectNoErrors('TH-31');
        await ctx.close();
      });
    },
  },

  {
    id: 'TH-32',
    title:
      'IFRAME: the gateway page is rendered inside the order page; the order turns paid without leaving it',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      // The theme only frames an https URL (14 section 11.4) and the fake gateway speaks http: in the browser the start of the order is shown
      // with the same path on this https host, and the host is answered locally with the gateway's pay page. Nothing else is changed.
      const FRAMED = 'https://fake-gateway.e2e.test';

      await withStartKind(admin, gateway, 'IFRAME', async () => {
        const { account, ctx, page, held, checkouts } = await prepare({
          browser,
          env,
          admin,
          gateway,
          buyer,
          vip,
          label: 'k32',
          hold: { refresh: false },
        });
        const framed = [];

        await page.route(`${env.url}${MARKET_API}/orders/*`, async (route) => {
          if (route.request().method() !== 'GET' || /\/status$/.test(route.request().url()))
            return route.continue();

          const response = await route.fetch();
          const json = await response.json();
          const iframe = json.order?.payment?.start?.iframe;

          if (iframe) iframe.url = iframe.url.replace(/^http:\/\/127\.0\.0\.1:\d+/, FRAMED);

          await route.fulfill({ response, json });
        });
        await page.route(`${FRAMED}/**`, async (route) => {
          const request = route.request();
          const reference = decodeURIComponent(new URL(request.url()).pathname.split('/').pop());

          framed.push({ reference, topLevel: request.frame() === page.mainFrame() });
          held.held.push(reference);
          await route.fulfill({
            status: 200,
            contentType: 'text/html; charset=utf-8',
            body: '<!doctype html><meta charset="utf-8"><title>Fake gateway</title><body><p id="held">Fake gateway (TEST ONLY): waiting for the buyer</p></body>',
          });
        });

        await placeButton(page).click();
        const publicId = await waitForOrderPage(page);

        assertEqual(checkouts[0].payment.kind, 'IFRAME', 'the start kind is IFRAME');
        const frame = page.locator('iframe');
        await frame.waitFor({ timeout: 60000 });
        assert(
          /\/pay\//.test((await frame.getAttribute('src')) ?? ''),
          'the iframe points at the gateway page',
        );
        assertEqual(
          await frame.getAttribute('referrerpolicy'),
          'strict-origin-when-cross-origin',
          'with the strict referrer policy',
        );
        await page.frameLocator('iframe').locator('#held').waitFor({ timeout: 30000 });
        assertEqual(framed.length, 1, 'the gateway page was loaded once');
        assertEqual(framed[0].topLevel, false, 'as a sub-frame, not as the page');
        assertEqual(
          new URL(page.url()).pathname,
          `/store/order/${publicId}`,
          'the buyer is still on the order page',
        );
        assertEqual(held.requests.length, 0, 'the buyer was never sent away from the order page');
        await page
          .getByRole('heading', { name: text('theme.order.payment-title'), exact: true })
          .waitFor({ timeout: 10000 });

        await held.release();
        await paid(page);
        await page.locator('iframe').waitFor({ state: 'detached', timeout: 30000 });
        assertEqual(
          (await orderView(account, publicId)).status,
          'COMPLETED',
          'the order is completed',
        );
        ctx.expectNoErrors('TH-32');
        await ctx.close();
      });
    },
  },

  {
    id: 'TH-33',
    title:
      'EMBEDDED with generic fields: the order page renders the form, "Continue" leads on to the gateway',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      await withStartKind(admin, gateway, 'EMBEDDED', async () => {
        const { account, ctx, page, held, checkouts } = await prepare({
          browser,
          env,
          admin,
          gateway,
          buyer,
          vip,
          label: 'k33',
        });
        const continues = [];

        page.on('request', (request) => {
          if (request.method() === 'POST' && /\/payment\/continue$/.test(request.url()))
            continues.push(JSON.parse(request.postData() || '{}'));
        });

        await placeButton(page).click();
        const publicId = await waitForOrderPage(page);

        assertEqual(checkouts[0].payment.kind, 'EMBEDDED', 'the start kind is EMBEDDED');
        const code = page.getByLabel('Code');
        await code.waitFor({ timeout: 60000 });
        assertEqual(
          held.requests.length,
          0,
          'nothing went to the gateway yet: the step is on the order page',
        );

        await code.fill('E2E-1234');
        await page.getByRole('button', { name: text('theme.order.payment-form-submit') }).click();

        // the provider's `continue` answers a redirect to its page, which the order page follows
        await page.waitForFunction(() => document.body.innerText.length > 0);
        await sleep(500);
        await page.waitForURL((url) => url.pathname.startsWith('/store/order/'), {
          timeout: 60000,
        });
        assertEqual(continues.length, 1, 'one continue request');
        assertEqual(continues[0].values?.code, 'E2E-1234', 'it carried the typed value');
        assertEqual(held.requests.length, 1, 'and the buyer was sent on to the gateway page');
        assertEqual(held.requests[0].topLevel, true, 'as the top-level page');

        await held.release();
        await paid(page);
        assertEqual(
          (await orderView(account, publicId)).status,
          'COMPLETED',
          'the order is completed',
        );
        ctx.expectNoErrors('TH-33');
        await ctx.close();
      });
    },
  },

  {
    id: 'TH-34',
    title:
      'EMBEDDED with a plugin component: it renders and can call continuePayment; a missing component falls back to the generic form, or to the notice without fields',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const COMPONENT = 'market:checkout:payment:e2e-test';

      await withStartKind(admin, gateway, 'EMBEDDED', async () => {
        for (const variant of ['registered', 'missing-with-fields', 'missing-without-fields']) {
          const account = await payBuyer(buyer, admin, `k34${variant.slice(0, 3)}`);
          const ctx = await signedIn(browser, account);

          await patchPluginBundle(ctx.context);

          const page = await ctx.page();
          const held = await holdAtGateway(page, gateway);
          const continues = [];

          // the checkout navigates to the order page on the client, whose load asks for the order: that answer is what the variant changes
          await page.route(`${env.url}${MARKET_API}/orders/*`, async (route) => {
            const request = route.request();

            if (request.method() !== 'GET' || /\/status$/.test(request.url()))
              return route.continue();

            const response = await route.fetch();
            const json = await response.json();
            const embedded = json.order?.payment?.start?.embedded;

            if (embedded) {
              embedded.component =
                variant === 'registered' ? COMPONENT : 'market:checkout:payment:e2e-absent';
              if (variant === 'missing-without-fields') embedded.fields = [];
            }

            await route.fulfill({ response, json });
          });
          page.on('request', (request) => {
            if (request.method() === 'POST' && /\/payment\/continue$/.test(request.url()))
              continues.push(JSON.parse(request.postData() || '{}'));
          });

          await addFromCard(page, env, vip, vip.name);
          await openCheckout(page, env);

          // registered on the checkout document: the order page is then reached by a client-side navigation, which keeps the registry
          await page.waitForFunction(() => globalThis.__E2E_PANO__ !== undefined, null, {
            timeout: 60000,
          });

          // a stand-in for another plugin: a view-slot item under the component id, a plain component function (anchor, props)
          await page.evaluate((id) => {
            globalThis.__E2E_PANO__.ui.view.register({
              viewId: id,
              id: 'e2e-test-component',
              component: function E2eTestComponent(anchor, props) {
                const root = document.createElement('div');

                root.id = 'e2e-test-plugin';
                root.innerHTML =
                  '<p id="e2e-test-plugin-text"></p><button type="button" id="e2e-test-plugin-pay" class="btn btn-primary">Pay with the test plugin</button><p id="e2e-test-plugin-result"></p>';
                root.querySelector('#e2e-test-plugin-text').textContent =
                  `Test plugin payment for ${props.order.publicId} in ${props.locale}`;
                root.querySelector('#e2e-test-plugin-pay').addEventListener('click', async () => {
                  const result = await props.continuePayment({ code: 'from-plugin' });

                  root.querySelector('#e2e-test-plugin-result').textContent =
                    JSON.stringify(result);
                });
                anchor.before(root);
              },
            });
          }, COMPONENT);

          await waitQuoted(page);
          await methodRadio(page, 'fake').check();
          await waitQuoted(page);
          await placeButton(page).click();
          const publicId = await waitForOrderPage(page);

          if (variant === 'registered') {
            await page.locator('#e2e-test-plugin').waitFor({ timeout: 60000 });
            assert(
              (await page.locator('#e2e-test-plugin-text').innerText()).includes(`for ${publicId}`),
              'the component got the order',
            );
            assertEqual(
              await page.getByLabel('Code').count(),
              0,
              'the generic form is not shown next to it',
            );

            await page.locator('#e2e-test-plugin-pay').click();
            // the provider answers a redirect to its page and the order page follows it: the buyer comes back to the order page, CONFIRMING
            await page
              .getByText(text('theme.order.state.confirming'))
              .first()
              .waitFor({ timeout: 60000 });
            assertEqual(continues.length, 1, 'continuePayment posted once');
            assertEqual(
              continues[0].values?.code,
              'from-plugin',
              'with the values the component passed',
            );
            assert(held.requests.length >= 1, 'and the buyer was sent on to the gateway');
            await held.release();
            await paid(page);
            assertEqual(
              (await orderView(account, publicId)).status,
              'COMPLETED',
              'the order is completed',
            );
          } else if (variant === 'missing-with-fields') {
            await page.getByLabel('Code').waitFor({ timeout: 60000 });
            assertEqual(await page.locator('#e2e-test-plugin').count(), 0, 'no plugin component');
            assertEqual(held.requests.length, 0, 'the buyer is still on the order page');
          } else {
            await page
              .getByText(text('theme.order.payment-ui-missing'))
              .waitFor({ timeout: 60000 });
            await page
              .getByRole('button', { name: text('theme.order.payment-other') })
              .waitFor({ timeout: 10000 });
          }

          ctx.expectNoErrors(`TH-34 ${variant}`);
          await ctx.close();
        }
      });
    },
  },

  {
    id: 'TH-35',
    title:
      'INSTRUCTIONS (bank transfer): copy buttons, "I have made the transfer" => PROCESSING, the panel approval => paid',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      await withBankTransfer(admin, async () => {
        const account = await payBuyer(buyer, admin, 'k35');
        const ctx = await signedIn(browser, account);

        await ctx.context.grantPermissions(['clipboard-read', 'clipboard-write'], {
          origin: env.url,
        });

        const page = await ctx.page();
        const transfers = [];

        page.on('request', (request) => {
          if (request.method() === 'POST' && /\/bank-transfer\/notify$/.test(request.url()))
            transfers.push(JSON.parse(request.postData() || '{}'));
        });

        await addFromCard(page, env, vip, vip.name);
        await openCheckout(page, env);
        await waitQuoted(page);
        await methodRadio(page, 'bank-transfer').check();
        await waitQuoted(page);
        await placeButton(page).click();
        const publicId = await waitForOrderPage(page);

        // the instructions: the account details, each with a copy button that really copies
        await page.getByText('DE89370400440532013000').first().waitFor({ timeout: 60000 });
        await page.getByText('E2E Store').first().waitFor({ timeout: 10000 });
        const copyIban = page.getByRole('button', { name: /IBAN/ }).first();
        await copyIban.waitFor({ timeout: 10000 });
        await copyIban.click();
        assertEqual(
          await page.evaluate(() => navigator.clipboard.readText()),
          'DE89370400440532013000',
          'the copy button puts the IBAN on the clipboard',
        );
        assert(
          (await page.locator('body').innerText()).includes(text('theme.order.state.awaiting')),
          'the order waits for the buyer first',
        );

        // "I have made the transfer"
        await page.getByLabel(text('theme.order.transfer-sender')).fill('Ada Lovelace');
        await page.getByLabel(text('theme.order.transfer-note')).fill('paid from E2E');
        await page.getByRole('button', { name: text('theme.order.transfer-submit') }).click();
        await page
          .getByText(text('theme.order.state.processing-bank'))
          .first()
          .waitFor({ timeout: 60000 });
        assertEqual(transfers.length, 1, 'one notice was sent');
        assertEqual(transfers[0].senderName, 'Ada Lovelace', 'with the sender');
        assertEqual(
          (await orderView(account, publicId)).status,
          'PENDING',
          'the order is still PENDING for the owner',
        );

        // the owner approves it in the panel
        const stored = await panelOrder(admin, publicId);
        const orderId = stored.order?.id ?? stored.id;
        must(
          await admin.post(`${PANEL_MARKET_API}/orders/${orderId}/bank-transfer`, {
            decision: 'APPROVE',
          }),
          'approve the transfer',
        );
        await paid(page);
        assertEqual(
          (await orderView(account, publicId)).status,
          'COMPLETED',
          'the order is completed',
        );
        ctx.expectNoErrors('TH-35');
        await ctx.close();
      });
    },
  },

  {
    id: 'TH-36',
    title:
      'COMPLETED: the start itself is the payment; the order page is paid at once and nothing is held at a gateway',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      await withStartKind(admin, gateway, 'COMPLETED', async () => {
        const { account, ctx, page, held, checkouts } = await prepare({
          browser,
          env,
          admin,
          gateway,
          buyer,
          vip,
          label: 'k36',
        });

        await placeButton(page).click();
        const publicId = await waitForOrderPage(page);

        assertEqual(checkouts[0].payment.kind, 'COMPLETED', 'the start kind is COMPLETED');
        await paid(page);
        assertEqual(held.requests.length, 0, 'the buyer never went to a gateway page');
        assertEqual(
          (await page.locator('iframe').count()) +
            (await page.getByRole('button', { name: text('theme.order.payment-pay') }).count()),
          0,
          'there is nothing left to pay on the page',
        );
        assertEqual(
          (await orderView(account, publicId)).status,
          'COMPLETED',
          'the order is completed',
        );
        ctx.expectNoErrors('TH-36');
        await ctx.close();
      });
    },
  },
];
