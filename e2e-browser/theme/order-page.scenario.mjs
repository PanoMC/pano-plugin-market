// Theme browser scenarios 37 to 42 of 14 section 20.3 (the order page), vanilla theme. Ids TH-37 .. TH-42 are the numbers of the spec.
// Orders are made through the storefront API and opened the way a buyer lands on them (a gateway return, a mail link, a profile link); every
// scenario fails on a console error or a page error that it did not provoke on purpose.
import { must } from '../lib/api.mjs';
import { newContext } from '../lib/browser.mjs';
import { assert, assertEqual, hydrated, open } from '../lib/ui.mjs';
import {
  holdAtGateway,
  openCheckout,
  payBuyer,
  placeButton,
  signedIn,
  text,
  textRe,
  waitForOrderPage,
  waitQuoted,
  withBankTransfer,
  withFakeSettings,
  withStartKind,
} from './lib/checkout.mjs';
import { addFromCard, product } from './lib/helpers.mjs';
import {
  checkout,
  guestApi,
  idem,
  likeRe,
  orderRowId,
  paidOrder,
  panelDetail,
  sleep,
  sql,
  table,
  until,
  view,
  waitUntil,
} from './lib/orders.mjs';
import { germanAddress, withShipping } from './lib/shipping.mjs';

const orderUrl = (env, id, query = '') => `${env.url}/store/order/${id}${query}`;
const methodRadio = (page, id) => page.locator(`input[type="radio"][value="${id}"]`);
const payButton = (page) =>
  page.getByRole('button', { name: text('theme.order.payment-pay'), exact: true });

/** Opens an order page and waits until the status block is there. */
async function openOrder(page, env, id, query = '') {
  await open(page, orderUrl(env, id, query), (p) =>
    p.locator('[role="status"]').first().waitFor({ timeout: 60000 }),
  );
}

/** Walks the checkout form of a signed-in buyer with one product and a chosen method, and presses the pay button. */
async function checkoutByForm(page, env, item, method) {
  await addFromCard(page, env, item, item.name);
  await openCheckout(page, env);
  await waitQuoted(page);
  await methodRadio(page, method).check();
  await waitQuoted(page);
  await placeButton(page).click();
}

export const scenarios = [
  {
    id: 'TH-37',
    title:
      'return cancel: the order page says the payment was cancelled and "Pay another way" pays it at the second method',
    async run({ browser, env, admin, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'o37');
      const placed = await checkout(account, {
        items: [{ productId: vip.id, quantity: 1 }],
        paymentMethodId: 'fake',
      });
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();

      // the buyer is at the gateway and presses "cancel": the gateway's own cancel outcome, then its redirect back to the store
      await page.goto(`${placed.json.payment.url}?outcome=cancel`, {
        waitUntil: 'domcontentloaded',
      });
      await page.waitForURL((url) => url.pathname === `/store/order/${placed.publicId}`, {
        timeout: 60000,
      });
      await hydrated(page);
      await page.getByText(text('theme.order.state.awaiting')).first().waitFor({ timeout: 60000 });
      await page
        .getByText(text('theme.order.state.cancelled-at-gateway'))
        .first()
        .waitFor({ timeout: 30000 });
      assert(
        !page.url().includes('return='),
        `the return hint left the address bar (${page.url()})`,
      );
      assertEqual(
        (await view(account, placed.publicId)).status,
        'PENDING',
        'the order itself is still open',
      );

      // "Pay another way": the picker, the other method, Pay, the gateway, the return
      const other = page.getByRole('button', { name: text('theme.order.payment-other') });
      await other.waitFor({ timeout: 30000 });
      if ((await other.getAttribute('aria-expanded')) !== 'true') await other.click();
      await methodRadio(page, 'fake-eur').check();
      await payButton(page).click();
      await page
        .waitForURL(
          (url) =>
            url.pathname === `/store/order/${placed.publicId}` &&
            url.searchParams.get('return') === null,
          { timeout: 90000 },
        )
        .catch(() => {});
      await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 90000 });

      const paid = await view(account, placed.publicId);
      assertEqual(paid.status, 'COMPLETED', 'the order is completed');
      assertEqual(paid.payment.methodId, 'fake-eur', 'the paying attempt is the second method');
      ctx.expectNoErrors('TH-37');
      await ctx.close();
    },
  },

  {
    id: 'TH-38',
    title:
      'provider error at start: the buyer lands on the order page with the picker open and the second method succeeds',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'o38');
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();
      const swallowed = [];

      // the first method points at an address nobody listens on, so the gateway cannot be reached when the payment starts
      must(
        await admin.post('/api/panel/market/payment-methods/fake', {
          settings: { gatewayUrl: 'http://127.0.0.1:1', secret: gateway.secret },
        }),
        'break the first method',
      );
      await sleep(1000);

      try {
        page.on('response', (response) => {
          if (response.url() === `${env.url}/api/market/checkout`)
            swallowed.push(response.status());
        });
        await checkoutByForm(page, env, vip, 'fake');
        const publicId = await waitForOrderPage(page);

        assertEqual(
          swallowed.join(','),
          '502',
          'the checkout answered 502 PAYMENT_PROVIDER_ERROR once',
        );
        await page
          .getByText(text('theme.order.state.awaiting'))
          .first()
          .waitFor({ timeout: 60000 });
        await page
          .getByText(text('theme.order.state.attempt-failed'))
          .first()
          .waitFor({ timeout: 30000 });
        // the picker is open without a click and offers the other method
        assert(
          await methodRadio(page, 'fake-eur').isVisible(),
          'the picker shows the second method',
        );
        assertEqual(
          (await view(account, publicId)).payment.status,
          'FAILED',
          'the first attempt failed',
        );

        await methodRadio(page, 'fake-eur').check();
        await payButton(page).click();
        await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 90000 });

        const paid = await view(account, publicId);
        assertEqual(paid.status, 'COMPLETED', 'the order is completed by the second method');
        assertEqual(paid.payment.methodId, 'fake-eur', 'the paying attempt is the second method');
      } finally {
        must(
          await admin.post('/api/panel/market/payment-methods/fake', {
            settings: { gatewayUrl: gateway.baseUrl, secret: gateway.secret },
          }),
          'repair the first method',
        );
      }

      // the browser logs one console error for the 502 the scenario provoked
      const provoked = ctx.errors.filter((error) => /502/.test(error));
      assertEqual(provoked.length, 1, `one provoked 502 console error (${ctx.errors.join(' | ')})`);
      for (const error of provoked) ctx.errors.splice(ctx.errors.indexOf(error), 1);
      ctx.expectNoErrors('TH-38');
      await ctx.close();
    },
  },

  {
    id: 'TH-39',
    title: 'expiry: the countdown reaches 0 and the page turns to EXPIRED without a reload',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'o39');

      // a gateway without a status query (webhook only): with one, the gateway's "not paid yet" moves the attempt to PROCESSING, which an order
      // is not expired from for a day (06 section 12), and the countdown would end on "being processed"
      await withFakeSettings(admin, gateway, { statusQuery: false }, async () => {
        const placed = await checkout(account, {
          items: [{ productId: vip.id, quantity: 1 }],
          paymentMethodId: 'fake',
        });

        // time travel by row rewind (what the Kotlin E2E classes do): the order and its open attempt run out 12 seconds from now
        const at = Date.now() + 12000;

        sql(`UPDATE ${table('order')} SET expiresAt = ${at} WHERE publicId = '${placed.publicId}'`);
        sql(
          `UPDATE ${table('payment')} SET expiresAt = ${at} WHERE orderId = (SELECT id FROM ${table('order')} WHERE publicId = '${placed.publicId}')`,
        );

        const ctx = await signedIn(browser, account);
        const page = await ctx.page();

        await openOrder(page, env, placed.publicId);
        await page
          .getByText(text('theme.order.state.awaiting'))
          .first()
          .waitFor({ timeout: 30000 });
        await page.getByText(likeRe('theme.order.expires-in')).first().waitFor({ timeout: 10000 });
        // the page must not be reloaded from here on: a marker on the window disappears with the document
        await page.evaluate(() => {
          window.__E2E_SAME_DOCUMENT__ = true;
        });

        // the countdown runs out, the page asks once right away, and the expiry job (every 30 s) moves the order; the page learns it by polling
        await page
          .getByText(text('theme.order.state.EXPIRED'))
          .first()
          .waitFor({ timeout: 120000 });
        assertEqual(
          await page.evaluate(() => window.__E2E_SAME_DOCUMENT__ === true),
          true,
          'the document was not reloaded',
        );
        assertEqual(
          await page.getByText(likeRe('theme.order.expires-in')).count(),
          0,
          'no countdown any more',
        );
        assertEqual(await payButton(page).count(), 0, 'nothing left to pay');
        assert(
          await page.getByRole('link', { name: text('theme.order.back-to-store') }).isVisible(),
          'the way back to the store is offered',
        );
        assertEqual(
          (await view(account, placed.publicId)).status,
          'EXPIRED',
          'the order is EXPIRED on the server',
        );
        ctx.expectNoErrors('TH-39');
        await ctx.close();
      });
    },
  },

  {
    id: 'TH-40',
    title:
      'a guest opens the order URL in a clean browser: limited view; with ?token= the full view and the token leaves the address bar',
    async run({ browser, env, admin, catalogue }) {
      const { vip } = await catalogue();

      await withBankTransfer(admin, async () => {
        const guest = guestApi(env, 'g40');
        const name = `Gst${Date.now().toString(36).slice(-6)}`;
        const placed = await checkout(guest, {
          items: [{ productId: vip.id, quantity: 1 }],
          guest: { username: name, email: `${name.toLowerCase()}@example.com` },
          paymentMethodId: 'bank-transfer',
        });

        assert(placed.token, 'the guest got an access token with the order');

        // 1. a clean browser with nothing but the URL: the limited view (no payment details, no number)
        const bare = await newContext(browser, { viewport: 'desktop' });
        const bareTab = await bare.page();

        await openOrder(bareTab, env, placed.publicId);
        await bareTab.getByText(text('theme.order.limited')).waitFor({ timeout: 30000 });
        const limited = await bareTab.locator('body').innerText();

        assert(
          !limited.includes('DE89370400440532013000'),
          'the limited view shows no account details',
        );
        assert(
          !limited.includes(`${name.toLowerCase()}@example.com`),
          'the limited view shows no e-mail',
        );
        assertEqual(
          await bareTab.getByRole('button', { name: text('theme.order.cancel') }).count(),
          0,
          'and no action',
        );
        assert(
          await bareTab.getByRole('link', { name: text('theme.order.sign-in') }).isVisible(),
          'it offers to sign in',
        );
        bare.expectNoErrors('TH-40 limited');
        await bare.close();

        // 2. the mail link: ?token= gives the full view and the token is gone from the address bar (and from the history entry)
        const mail = await newContext(browser, { viewport: 'desktop' });
        const tab = await mail.page();

        await tab.goto(orderUrl(env, placed.publicId, `?token=${placed.token}`), {
          waitUntil: 'domcontentloaded',
        });
        await hydrated(tab);
        await tab.getByText('DE89370400440532013000').first().waitFor({ timeout: 60000 });
        await tab.waitForFunction(() => !location.search.includes('token'), null, {
          timeout: 15000,
        });
        assert(!tab.url().includes('token'), `the token left the address bar (${tab.url()})`);
        assertEqual(
          await tab.evaluate(() => JSON.stringify(history.state ?? {}).includes('token')),
          false,
          'and the history state',
        );
        assert(
          !(await tab.locator('body').innerText()).includes(text('theme.order.limited')),
          'the full view has no limited note',
        );
        assertEqual(
          await tab.evaluate(
            (id) => sessionStorage.getItem(`pano-plugin-market-order:${id}`),
            placed.publicId,
          ),
          placed.token,
          'the token lives in this tab only (sessionStorage)',
        );

        // a reload of the clean address keeps the full view of this tab
        await tab.reload({ waitUntil: 'domcontentloaded' });
        await hydrated(tab);
        await tab.getByText('DE89370400440532013000').first().waitFor({ timeout: 60000 });
        mail.expectNoErrors('TH-40 token');

        // 3. another tab of the same browser has no token: limited again; a wrong token never unlocks and is not kept
        const other = await mail.page();

        await openOrder(other, env, placed.publicId);
        await other.getByText(text('theme.order.limited')).waitFor({ timeout: 30000 });

        const wrong = await newContext(browser, { viewport: 'desktop' });
        const wrongTab = await wrong.page();

        await wrongTab.goto(orderUrl(env, placed.publicId, '?token=not-the-token'), {
          waitUntil: 'domcontentloaded',
        });
        await hydrated(wrongTab);
        await wrongTab.getByText(text('theme.order.limited')).waitFor({ timeout: 30000 });
        await wrongTab.waitForFunction(() => !location.search.includes('token'), null, {
          timeout: 15000,
        });
        assertEqual(
          await wrongTab.evaluate(
            (id) => sessionStorage.getItem(`pano-plugin-market-order:${id}`),
            placed.publicId,
          ),
          null,
          'a token that unlocks nothing is not stored',
        );
        await mail.close();
        await wrong.close();
      });
    },
  },

  {
    id: 'TH-41',
    title:
      'cancel an order; invoice download on the session and the token path; shipment with its tracking link; a refunded order',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();

      // ---- cancel -------------------------------------------------------------------------------------------------------------------
      {
        const account = await payBuyer(buyer, admin, 'o41c');
        const placed = await checkout(account, {
          items: [{ productId: vip.id, quantity: 1 }],
          paymentMethodId: 'fake',
        });
        const ctx = await signedIn(browser, account);
        const page = await ctx.page();

        await openOrder(page, env, placed.publicId);
        await page
          .locator('button.btn-outline-danger', { hasText: text('theme.order.cancel') })
          .click();

        const modal = page.locator('#marketCancelOrderModal');

        await modal.getByText(text('theme.order.cancel-message')).waitFor({ timeout: 10000 });
        // "Keep order" closes the dialog and leaves the order alone
        await modal.getByRole('button', { name: text('theme.order.cancel-keep') }).click();
        await modal.waitFor({ state: 'hidden', timeout: 10000 });
        assertEqual(
          (await view(account, placed.publicId)).status,
          'PENDING',
          'keeping the order changes nothing',
        );

        await page
          .locator('button.btn-outline-danger', { hasText: text('theme.order.cancel') })
          .click();
        await modal
          .getByRole('button', { name: text('theme.order.cancel-confirm'), exact: true })
          .click();
        await page
          .getByText(text('theme.order.state.CANCELLED'))
          .first()
          .waitFor({ timeout: 30000 });
        assertEqual(
          (await view(account, placed.publicId)).status,
          'CANCELLED',
          'the order is cancelled on the server',
        );
        assertEqual(
          await page
            .locator('button.btn-outline-danger', { hasText: text('theme.order.cancel') })
            .count(),
          0,
          'no cancel button on a cancelled order',
        );
        assert(
          await page.getByRole('link', { name: text('theme.order.back-to-store') }).isVisible(),
          'back to the store is offered',
        );
        ctx.expectNoErrors('TH-41 cancel');
        await ctx.close();
      }

      // ---- invoice, session path: a plain link to the API route ---------------------------------------------------------------------
      {
        const account = await payBuyer(buyer, admin, 'o41i');
        const placed = await paidOrder(account, [{ productId: vip.id, quantity: 1 }]);

        await until(
          account,
          placed.publicId,
          (o) => o.invoiceAvailable === true,
          'the invoice of the paid order',
        );

        const ctx = await signedIn(browser, account);
        const page = await ctx.page();

        await openOrder(page, env, placed.publicId);
        const link = page.getByRole('link', { name: text('theme.order.invoice') });

        await link.waitFor({ timeout: 30000 });
        assertEqual(
          await link.getAttribute('href'),
          `/api/market/orders/${placed.publicId}/invoice`,
          'the session path is a plain link to the route',
        );

        const [download] = await Promise.all([
          page.waitForEvent('download', { timeout: 30000 }),
          link.click(),
        ]);
        const file = await download.path();

        assert(
          (await Bun.file(file).slice(0, 5).text()) === '%PDF-',
          'the downloaded file is a PDF',
        );
        ctx.expectNoErrors('TH-41 invoice session');
        await ctx.close();
      }

      // ---- invoice, token path: a button that fetches the PDF with the token header and hands it over as a blob -----------------------
      {
        await withBankTransfer(admin, async () => {
          const guest = guestApi(env, 'g41');
          const name = `Gin${Date.now().toString(36).slice(-6)}`;
          const placed = await checkout(guest, {
            items: [{ productId: vip.id, quantity: 1 }],
            guest: { username: name, email: `${name.toLowerCase()}@example.com` },
            paymentMethodId: 'bank-transfer',
          });

          must(
            await admin.post(
              `/api/panel/market/orders/${await orderRowId(admin, placed.publicId)}/bank-transfer`,
              { decision: 'APPROVE' },
            ),
            'approve the transfer',
          );
          await until(
            guest,
            placed.publicId,
            (o) => o.invoiceAvailable === true,
            'the invoice of the guest order',
            { token: placed.token },
          );

          const ctx = await newContext(browser, { viewport: 'desktop' });
          const page = await ctx.page();

          await page.goto(orderUrl(env, placed.publicId, `?token=${placed.token}`), {
            waitUntil: 'domcontentloaded',
          });
          await hydrated(page);

          const button = page.getByRole('button', { name: text('theme.order.invoice') });

          await button.waitFor({ timeout: 60000 });
          assertEqual(
            await page.getByRole('link', { name: text('theme.order.invoice') }).count(),
            0,
            'with a token there is a button, not a link',
          );

          const [download] = await Promise.all([
            page.waitForEvent('download', { timeout: 30000 }),
            button.click(),
          ]);

          assert(
            /^invoice-\d+\.pdf$/.test(download.suggestedFilename()),
            `the file is named invoice-<number>.pdf (${download.suggestedFilename()})`,
          );
          assert(
            (await Bun.file(await download.path())
              .slice(0, 5)
              .text()) === '%PDF-',
            'the downloaded file is a PDF',
          );
          ctx.expectNoErrors('TH-41 invoice token');
          await ctx.close();
        });
      }

      // ---- shipment with a tracking link ----------------------------------------------------------------------------------------------
      await withShipping(admin, async (shipping) => {
        const parcel = await product(admin, 'Parcel41', {
          price: '20.00',
          stock: 20,
          physical: 'true',
          weightGrams: '500',
        });
        const account = await payBuyer(buyer, admin, 'o41s');
        const placed = await paidOrder(account, [{ productId: parcel.id, quantity: 1 }], {
          shippingAddress: germanAddress(),
          shippingMethodId: shipping.methodId,
        });
        const detail = await panelDetail(admin, placed.publicId);
        const orderItem = (detail.items ?? detail.order?.items).find(
          (i) => i.productId === parcel.id,
        );
        const trackingUrl = 'https://tracking.example.com/t/E2E41TRACK';
        const created = must(
          await admin.post(
            `/api/panel/market/orders/${await orderRowId(admin, placed.publicId)}/shipments`,
            {
              providerId: 'manual',
              items: [{ orderItemId: orderItem.id, quantity: 1 }],
              parcels: [{ weightGrams: 500 }],
              manual: { carrierName: 'E2E Post', trackingNumber: 'E2E41TRACK', trackingUrl },
            },
          ),
          'create the shipment',
        ).json.shipment;

        must(
          await admin.put(`/api/panel/market/shipments/${created.id}`, { status: 'IN_TRANSIT' }),
          'in transit',
        );

        const ctx = await signedIn(browser, account);
        const page = await ctx.page();

        await openOrder(page, env, placed.publicId);
        await page
          .getByText(text('theme.order.state.shipping.SHIPPED'))
          .first()
          .waitFor({ timeout: 30000 });
        await page.getByText('E2E Post', { exact: true }).waitFor({ timeout: 10000 });
        await page
          .getByText(text('theme.order.shipment.IN_TRANSIT'))
          .first()
          .waitFor({ timeout: 10000 });
        await page.locator('code', { hasText: 'E2E41TRACK' }).waitFor({ timeout: 10000 });

        const track = page.getByRole('link', { name: text('theme.order.track') });

        assertEqual(
          await track.getAttribute('href'),
          trackingUrl,
          'the tracking link points at the carrier',
        );
        assertEqual(await track.getAttribute('target'), '_blank', 'it opens in a new tab');
        assert((await track.getAttribute('rel')).includes('noopener'), 'without an opener');
        assert(
          (await page.locator('address').first().innerText()).includes('Unter den Linden 1'),
          'the shipping address is shown to its owner',
        );

        // the shipment is delivered while the page is open: the page polls and turns to "paid"
        must(
          await admin.put(`/api/panel/market/shipments/${created.id}`, { status: 'DELIVERED' }),
          'delivered',
        );
        await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 60000 });
        await page
          .getByText(text('theme.order.shipment.DELIVERED'))
          .first()
          .waitFor({ timeout: 10000 });
        ctx.expectNoErrors('TH-41 shipment');
        await ctx.close();
      });

      // ---- refunded order -------------------------------------------------------------------------------------------------------------
      {
        const account = await payBuyer(buyer, admin, 'o41r');
        const placed = await paidOrder(account, [{ productId: vip.id, quantity: 1 }]);
        const total = (await view(account, placed.publicId)).totals.total;

        must(
          await admin.post(
            `/api/panel/market/orders/${await orderRowId(admin, placed.publicId)}/refunds`,
            { amount: total, reason: 'E2E refund' },
            idem(),
          ),
          'refund the order',
        );
        await until(account, placed.publicId, (o) => o.status === 'REFUNDED', 'the refunded order');

        const ctx = await signedIn(browser, account);
        const page = await ctx.page();

        await openOrder(page, env, placed.publicId);
        await page
          .getByText(text('theme.order.state.refunded'))
          .first()
          .waitFor({ timeout: 30000 });
        await page
          .getByText(text('theme.order.totals.refunded'))
          .first()
          .waitFor({ timeout: 10000 });
        assertEqual(await payButton(page).count(), 0, 'nothing to pay on a refunded order');
        ctx.expectNoErrors('TH-41 refunded');
        await ctx.close();
      }
    },
  },

  {
    id: 'TH-42',
    title:
      'polling pauses in a hidden tab and resumes (and learns what happened meanwhile) when the tab is visible again',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'o42');
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();
      // the gateway keeps the payment in a status the provider does not know, so a poll never moves the attempt by itself
      const held = await holdAtGateway(page, gateway);

      await withStartKind(admin, gateway, 'EMBEDDED', async () => {
        const placed = await checkout(account, {
          items: [{ productId: vip.id, quantity: 1 }],
          paymentMethodId: 'fake',
        });
        const reference = [...gateway.payments.keys()].at(-1);
        const statusCalls = [];

        page.on('request', (request) => {
          if (request.url().includes(`/api/market/orders/${placed.publicId}/status`))
            statusCalls.push(Date.now());
        });

        await openOrder(page, env, placed.publicId);
        await page
          .getByText(text('theme.order.state.awaiting'))
          .first()
          .waitFor({ timeout: 30000 });

        // an in-page payment (EMBEDDED) is polled every 2 seconds at first: it is polling
        await waitUntil(() => statusCalls.length >= 2, 30000, 'two status polls');

        // the tab goes to the background: the page pauses
        await page.evaluate(() => {
          Object.defineProperty(document, 'hidden', { configurable: true, get: () => true });
          Object.defineProperty(document, 'visibilityState', {
            configurable: true,
            get: () => 'hidden',
          });
          document.dispatchEvent(new Event('visibilitychange'));
        });
        await sleep(1500); // a poll already in flight lands
        const pausedAt = statusCalls.length;

        // the payment is completed meanwhile (signed webhook)
        await held.release(reference);
        await until(
          account,
          placed.publicId,
          (o) => o.status === 'COMPLETED',
          'the order paid while the tab was hidden',
        );
        await sleep(8000); // longer than any interval of the schedule (2 s, then 5 s)
        assertEqual(
          statusCalls.length,
          pausedAt,
          'no status request was made while the tab was hidden',
        );
        assert(
          await page.getByText(text('theme.order.state.awaiting')).first().isVisible(),
          'the hidden page still shows what it knew',
        );

        // the tab is back: one request right away, and the page shows the payment
        await page.evaluate(() => {
          Object.defineProperty(document, 'hidden', { configurable: true, get: () => false });
          Object.defineProperty(document, 'visibilityState', {
            configurable: true,
            get: () => 'visible',
          });
          document.dispatchEvent(new Event('visibilitychange'));
        });
        await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 20000 });
        assert(statusCalls.length > pausedAt, 'polling resumed with a request');
        ctx.expectNoErrors('TH-42');
        await ctx.close();
      });
    },
  },
];
