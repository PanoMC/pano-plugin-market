// Scenarios 58 to 61 of 13 section 25.4: orders list, order detail, refund, manual order (panel, admin session, fake payment provider).
import fs from 'node:fs';
import { must, PANEL_MARKET_API } from '../lib/api.mjs';
import { actions, product } from '../lib/bootstrap.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { newContext } from '../lib/browser.mjs';
import { signedIn, openMarket, waitFor, bodyText, sleep } from './lib/panel.mjs';
import {
  awaitStatus,
  bankTransferOrder,
  checkout,
  gatewayOrder,
  mixedOrder,
} from './lib/orders.mjs';

const adminPage = async (ctx) => signedIn(ctx.browser, ctx.admin);
const orderRows = (page) => page.locator('table tbody tr');

/** Orders table row of the order number, or null. */
const rowOf = (page, number) =>
  orderRows(page).filter({ has: page.locator(`a:text-is("#${number}")`) });

async function openOrders(page, env, query = '') {
  await openMarket(page, env, `/market/orders${query}`, (p) =>
    p.getByRole('combobox', { name: 'Status', exact: true }).waitFor({ timeout: 60000 }),
  );
  // the list data is loaded before the card renders; the count text confirms it
  await page
    .getByText(/^\d+ Orders?$/)
    .first()
    .waitFor({ timeout: 30000 });
}

/** Types into the card header search and waits for the URL to carry it (debounced 300 ms). */
async function search(page, text) {
  const input = page.getByPlaceholder('Find...');
  await input.fill(text);
  await page.waitForFunction(
    (value) => new URL(location.href).searchParams.get('search') === value,
    text,
    { timeout: 15000 },
  );
  await page.waitForTimeout(600);
}

export const scenarios = [
  {
    id: 'PANEL-58',
    title:
      'orders list: status filter, e-mail search, stale ?page=99 falls back to page 1, empty result without an icon, CSV export with BOM and the selected columns',
    async run(ctx) {
      const { env, admin, catalogue } = ctx;
      const cat = await catalogue();

      // one buyer with a pending order (fake gateway, unpaid) and a completed one
      const buyer = await gatewayOrder(ctx, cat, { label: 'l58' });
      const open = await checkout(buyer.api, {
        items: [{ productId: cat.vip.id, quantity: 1 }],
        paymentMethodId: 'fake',
      });
      const email = `${buyer.api.username}@example.com`;
      const { pc, page } = await adminPage(ctx);

      await openOrders(page, env);

      // search by e-mail finds both orders of the buyer (the search covers the e-mail, 04 section 7)
      await search(page, email);
      await rowOf(page, buyer.number).first().waitFor({ timeout: 15000 });
      assertEqual(
        await rowOf(page, buyer.number).count(),
        1,
        'PANEL-58: the completed order is listed',
      );
      assertEqual(
        await rowOf(page, open.number).count(),
        1,
        'PANEL-58: the pending order is listed',
      );

      // status filter: Pending keeps only the pending one, Completed only the completed one
      await page.getByRole('combobox', { name: 'Status', exact: true }).selectOption('pending');
      await page.waitForFunction(
        () => new URL(location.href).searchParams.get('status') === 'PENDING',
        null,
        { timeout: 15000 },
      );
      await waitFor(
        'only the pending order',
        async () => (await rowOf(page, buyer.number).count()) === 0,
      );
      assertEqual(
        await rowOf(page, open.number).count(),
        1,
        'PANEL-58: Pending shows the pending order',
      );
      assertEqual(
        await page.getByRole('combobox', { name: 'Status', exact: true }).inputValue(),
        'pending',
        'PANEL-58: the Pending option is selected',
      );
      await page.getByRole('combobox', { name: 'Status', exact: true }).selectOption('completed');
      await waitFor(
        'only the completed order',
        async () => (await rowOf(page, open.number).count()) === 0,
      );
      assertEqual(
        await rowOf(page, buyer.number).count(),
        1,
        'PANEL-58: Completed shows the completed order',
      );

      // a stale ?page=99 (bookmark) shows page 1 instead of an error
      await openOrders(page, env, '?page=99');
      assert(
        (await orderRows(page).count()) > 0,
        'PANEL-58: ?page=99 falls back to page 1 and lists orders',
      );
      assert(
        !(await bodyText(page)).includes('Could Not Load Data'),
        'PANEL-58: ?page=99 shows no load error',
      );

      // an empty result: NoContent, and no icon in it (13 section 1.8)
      await openOrders(
        page,
        env,
        `?search=${encodeURIComponent(`no-such-order-${buyer.number}-zz`)}`,
      );
      // (the notifications dropdown of the panel header has its own "Here is empty." text, so the block is addressed by its card)
      const empty = page.locator('.card.opacity-50:visible:has(p:text-is("Here is empty."))');
      await empty.first().waitFor({ timeout: 15000 });
      assertEqual(await empty.count(), 1, 'PANEL-58: one NoContent block');
      assertEqual(
        await empty.locator('i, svg, img').count(),
        0,
        'PANEL-58: NoContent carries no icon',
      );

      // CSV export: BOM first, the columns of the selection, in table order
      await openOrders(page, env, `?search=${encodeURIComponent(email)}`);
      await page.getByRole('button', { name: 'Actions' }).first().click();
      await page.getByRole('button', { name: 'Export CSV' }).click();
      const modal = page.locator('.modal.show');
      await modal.getByText('Export Orders').waitFor({ timeout: 15000 });
      for (const label of ['Delivery status', 'Payment method', 'Variant'])
        await modal.getByLabel(label, { exact: true }).uncheck();
      await modal.getByLabel('Public ID', { exact: true }).check();
      const expected = [
        'orderId',
        'publicId',
        'createdAt',
        'status',
        'playerUsername',
        'productName',
        'quantity',
        'lineTotal',
        'currency',
        'orderTotal',
      ];
      const exportSeen = [];
      page.on('response', (r) => {
        if (r.url().includes('/orders/export')) exportSeen.push(`${r.status()} ${r.url()}`);
      });
      page.on('requestfailed', (r) => {
        if (r.url().includes('/orders/export'))
          exportSeen.push(`failed ${r.failure()?.errorText} ${r.url()}`);
      });
      const [download] = await Promise.all([
        page.waitForEvent('download', { timeout: 30000 }),
        modal.getByRole('button', { name: 'Download' }).click(),
      ]);
      const file = await download.path().catch(async (error) => {
        throw new Error(
          `PANEL-58: the export download failed (${await download.failure()}; ${error.message}); export responses: ${exportSeen.join(' | ') || 'none'}`,
        );
      });
      const raw = fs.readFileSync(file);
      assertEqual(
        raw.subarray(0, 3).toString('hex'),
        'efbbbf',
        'PANEL-58: the CSV starts with a UTF-8 BOM',
      );
      const text = raw.toString('utf8').replace(/^﻿/, '');
      const lines = text.split(/\r?\n/).filter(Boolean);
      const header = lines[0].split(',').map((cell) => cell.replace(/^"|"$/g, ''));
      assertEqual(
        JSON.stringify(header),
        JSON.stringify(expected),
        'PANEL-58: the CSV columns equal the selection',
      );
      assert(
        lines.some((line) => line.includes(buyer.api.username)),
        "PANEL-58: the CSV holds the buyer's orders",
      );
      assert(
        download.suggestedFilename().endsWith('.csv'),
        'PANEL-58: the download is a .csv file',
      );
      // the export of the filtered list: only this buyer's two orders (two rows, one item each)
      assertEqual(lines.length - 1, 2, 'PANEL-58: the export applies the list filter (two rows)');

      pc.expectNoErrors('PANEL-58');
      await pc.close();
    },
  },

  {
    id: 'PANEL-59',
    title:
      'order detail: breakdown rows equal the API totals, mark-as-paid on a bank transfer order completes it and the deliveries appear without a reload',
    async run(ctx) {
      const { env, admin } = ctx;
      // a product whose action runs inside the platform (no game server needed): 2 store credits
      const kit = await product(admin, 'Bank Kit', { price: '5.00', actions: actions.credit(2) });
      const order = await bankTransferOrder(ctx, kit, { label: 'd59' });
      const { pc, page } = await signedIn(ctx.browser, admin);

      const api = (await admin.get(`${PANEL_MARKET_API}/orders/${order.number}`)).json;
      assertEqual(api.order.status, 'PENDING', 'PANEL-59: the bank transfer order starts PENDING');
      assertEqual(api.allowed.markPaid, true, 'PANEL-59: mark-as-paid is allowed on it');

      await openMarket(page, env, `/market/orders/detail/${order.number}`, (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );

      // the breakdown rows of the Totals card equal the API figures (money format of the locale: 5.00)
      const totals = await totalsOf(page);
      const money = (n) => Number(n).toFixed(2);
      assertEqual(totals.Subtotal, money(api.order.subtotal), 'PANEL-59: Subtotal row');
      assertEqual(totals.Total, money(api.order.totalPrice), 'PANEL-59: Total row');
      if (Number(api.order.vatTotal) > 0)
        assertEqual(totals.VAT, money(api.order.vatTotal), 'PANEL-59: VAT row');
      assert(!('Paid With Credits' in totals), 'PANEL-59: no credits row on an unpaid order');
      const before = await page
        .getByText(/^\d+ Deliveries$/)
        .first()
        .innerText();
      assertEqual(before, '0 Deliveries', 'PANEL-59: no delivery before payment');

      // a flag on the window proves the page is never reloaded while the status moves
      await page.evaluate(() => (window.__e2eNoReload = true));
      await page.getByRole('button', { name: 'Actions' }).first().click();
      await page.getByRole('button', { name: 'Mark As Paid' }).click();
      const modal = page.locator('.modal.show');
      await modal.getByRole('heading', { name: 'Mark As Paid' }).waitFor({ timeout: 15000 });
      await modal.getByRole('button', { name: 'Mark As Paid' }).click();

      await waitFor(
        'the order to read Completed on the page',
        async () => (await page.locator('.badge', { hasText: 'Completed' }).count()) > 0,
        { timeout: 30000 },
      );
      assertEqual(
        (await admin.get(`${PANEL_MARKET_API}/orders/${order.number}`)).json.order.status,
        'COMPLETED',
        'PANEL-59: the API agrees: COMPLETED',
      );

      // the deliveries card fills by itself (the page re-reads the order while it is moving)
      await waitFor(
        'a delivery row on the order detail',
        async () => (await page.getByText(/^[1-9]\d* Deliveries$/).count()) > 0,
        { timeout: 90000, interval: 1000 },
      );
      assertEqual(
        await page.evaluate(() => window.__e2eNoReload === true),
        true,
        'PANEL-59: the deliveries appeared without a page reload',
      );

      pc.expectNoErrors('PANEL-59');
      await pc.close();
    },
  },

  {
    id: 'PANEL-60',
    title:
      'refund: a partial refund of a mixed order warns about the split, the previewed split equals the created refund, a double click creates one refund, REFUND_NOT_SUPPORTED turns the manual switch on',
    async run(ctx) {
      const { env, admin, catalogue, gateway } = ctx;
      const cat = await catalogue();
      const order = await mixedOrder(ctx, cat, { label: 'r60' });
      const refundsBefore = gateway.refunds.length;
      const { pc, page } = await signedIn(ctx.browser, admin);

      await openMarket(page, env, `/market/orders/detail/${order.number}`, (p) =>
        p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
      );
      const modal = await openRefund(page);

      // Amount mode, 4.00 of the 10.00 (3 paid in credits): the split is previewed and has to be confirmed
      await modal.getByLabel('Amount', { exact: true }).check();
      await modal.getByPlaceholder('Amount').fill('4.00');
      await modal.getByText('This Refund Is Split', { exact: true }).waitFor({ timeout: 30000 });
      const previewed = await previewSplit(modal, '4.00');
      assertEqual(previewed.total, 4, 'PANEL-60: the total of the preview');
      assert(
        previewed.gateway > 0 && previewed.credits > 0,
        `PANEL-60: the preview splits between gateway and credits (${JSON.stringify(previewed)})`,
      );
      const cta = modal.getByRole('button', { name: /^Refund/ });
      assert(
        await cta.isDisabled(),
        'PANEL-60: the CTA waits for the acknowledgement of the split',
      );
      await modal.getByLabel('I understand this refund is split').check();
      await waitFor('the refund CTA', async () => !(await cta.isDisabled()));

      await cta.dblclick();
      await page.locator('.modal.show').waitFor({ state: 'detached', timeout: 30000 });
      await waitFor(
        'the refund to be listed',
        async () =>
          ((await admin.get(`${PANEL_MARKET_API}/orders/${order.number}`)).json.refunds ?? [])
            .length > 0,
      );
      await sleep(1500); // a second request, if the double click had sent one, would have landed by now
      const detail = (await admin.get(`${PANEL_MARKET_API}/orders/${order.number}`)).json;
      assertEqual(
        detail.refunds.length,
        1,
        'PANEL-60: the double click created exactly one refund',
      );
      const row = detail.refunds[0];
      assertEqual(row.amount, 4, 'PANEL-60: refund amount');
      assertEqual(
        row.gatewayAmount,
        previewed.gateway,
        'PANEL-60: the previewed gateway part equals the refund row',
      );
      assertEqual(
        row.creditAmount,
        previewed.credits,
        'PANEL-60: the previewed credit part equals the refund row',
      );

      // the gateway received exactly one refund, of the previewed gateway part, against the payment of this order (the credit part never
      // leaves the platform)
      const sent = gateway.refunds.slice(refundsBefore);
      assertEqual(sent.length, 1, 'PANEL-60: the gateway received exactly one refund');
      assertEqual(
        Math.round(Number(sent[0].amount) * 100),
        Math.round(previewed.gateway * 100),
        'PANEL-60: the gateway refund equals the previewed gateway part',
      );
      assertEqual(sent[0].currency, 'EUR', 'PANEL-60: the gateway refund currency');
      const paid = [...gateway.payments.values()].find((p) => p.id === sent[0].paymentId);
      assert(paid, 'PANEL-60: the refund names a payment the gateway knows');
      assertEqual(
        Math.round(Number(paid.amount) * 100),
        Math.round((10 - 3) * 100),
        'PANEL-60: the refunded payment is the gateway part (10.00 minus 3 credits) of this order',
      );
      assertEqual(
        paid.refunded,
        Math.round(previewed.gateway * 100),
        'PANEL-60: the gateway keeps the refunded sum of the payment',
      );
      assertEqual(
        (await admin.get(`${PANEL_MARKET_API}/orders/${order.number}`)).json.refunds.length,
        1,
        'PANEL-60: the platform lists the same single refund',
      );

      // REFUND_NOT_SUPPORTED: the provider stops supporting refunds after the dialog previewed one; the server refuses and the
      // dialog turns the manual switch on (a manual refund records money returned outside the gateway)
      const second = await gatewayOrder(ctx, cat, { label: 'r60b' });
      await openMarket(page, env, `/market/orders/detail/${second.number}`, (p) =>
        p.getByText(`#${second.number}`).first().waitFor({ timeout: 60000 }),
      );
      const dialog = await openRefund(page);
      await dialog.getByLabel('Amount', { exact: true }).check();
      await dialog.getByPlaceholder('Amount').fill('2.00');
      const submit = dialog.getByRole('button', { name: /^Refund/ });
      await waitFor('the refund CTA of the second order', async () => !(await submit.isDisabled()));
      await setFakeRefundSupport(ctx, 'NONE');
      try {
        const manual = dialog.getByLabel('Money Was Returned Outside The Gateway');
        assertEqual(await manual.isChecked(), false, 'PANEL-60: the manual switch starts off');
        const mark = pc.errors.length;
        await submit.click();
        await waitFor('the manual switch to turn on', async () => manual.isChecked());
        // the refused request is the point of the step: the browser logs its 400 as a console error, which is expected exactly once
        provoked(pc, mark, /status of 400/);
        await dialog
          .getByText('This payment method cannot refund automatically. Refund manually instead.')
          .waitFor({ timeout: 15000 });
        assert(
          (await manual.getAttribute('class')).includes('is-invalid'),
          'PANEL-60: the manual switch is marked',
        );
        // the manual refund needs a reason, then it goes through without the gateway
        await dialog
          .getByPlaceholder('Reason (required for a manual refund)')
          .fill('returned in cash at the shop');
        await waitFor('the CTA after the reason', async () => !(await submit.isDisabled()));
        await submit.click();
        await page.locator('.modal.show').waitFor({ state: 'detached', timeout: 30000 });
        const refunds = (await admin.get(`${PANEL_MARKET_API}/orders/${second.number}`)).json
          .refunds;
        assertEqual(refunds.length, 1, 'PANEL-60: the manual refund exists');
        assertEqual(
          gateway.refunds.length - refundsBefore,
          1,
          'PANEL-60: the manual refund never reached the gateway (still only the first order refund)',
        );
      } finally {
        await setFakeRefundSupport(ctx, 'PARTIAL');
      }

      pc.expectNoErrors('PANEL-60');
      await pc.close();
    },
  },

  {
    id: 'PANEL-61',
    title:
      'manual order: an over-limit product is blocked, then created with Ignore Limits And Stock; the order has source PANEL',
    async run(ctx) {
      const { env, admin } = ctx;
      const scarce = await product(admin, 'Scarce Kit', { price: '3.00', stock: 1 });
      const buyer = await ctx.buyer('m61');
      const { pc, page } = await signedIn(ctx.browser, admin);

      await openMarket(page, env, '/market/orders/create-order', (p) =>
        p.getByPlaceholder('Player Username').waitFor({ timeout: 60000 }),
      );
      await page.getByPlaceholder('Player Username').fill(buyer.username);
      await page.getByRole('button', { name: 'Add Product' }).click();
      const modal = page.locator('.modal.show');
      await modal.getByPlaceholder('Search').first().fill(scarce.name);
      await modal.getByText(scarce.name, { exact: false }).first().click();
      await modal.getByRole('button', { name: 'Add', exact: true }).click();
      await page.locator('.modal.show').waitFor({ state: 'detached', timeout: 15000 });

      // two pieces of a product that has one in stock: blocked
      const quantity = page.getByLabel('Qty');
      await quantity.fill('2');
      const create = page.getByRole('button', { name: 'Create Order' }).last();
      await page.getByText(/A line is over a limit or out of stock/).waitFor({ timeout: 30000 });
      assert(
        await create.isDisabled(),
        'PANEL-61: the create button is blocked while a line is over its limit',
      );

      // Ignore Limits And Stock lifts the block
      await page.getByLabel('Ignore Limits And Stock').check();
      await waitFor('the create button', async () => !(await create.isDisabled()), {
        timeout: 30000,
      });
      await create.click();
      await page.waitForURL(/\/panel\/market\/orders\/detail\//, { timeout: 30000 });

      const number = page.url().split('/').pop().split(/[?#]/)[0];
      const view = (await admin.get(`${PANEL_MARKET_API}/orders/${number}`)).json;
      assertEqual(view.order.source, 'PANEL', 'PANEL-61: the new order has source PANEL');
      assertEqual(view.items[0].quantity, 2, 'PANEL-61: both pieces were ordered');

      pc.expectNoErrors('PANEL-61');
      await pc.close();
    },
  },
];

/** The console errors since `mark` that match `pattern` were provoked on purpose: exactly one must exist, and it is removed from the run's errors. */
function provoked(pc, mark, pattern) {
  const hits = pc.errors.slice(mark).filter((e) => pattern.test(e));
  assertEqual(hits.length, 1, `the provoked request logged one console error matching ${pattern}`);
  pc.errors.splice(pc.errors.indexOf(hits[0]), 1);
}

/** Label -> amount text ("5.00") of the rows of the Totals card. */
async function totalsOf(page) {
  return page.evaluate(() => {
    const card = [...document.querySelectorAll('.card')].find(
      (c) => c.querySelector(':scope > .card-body > div > span')?.textContent.trim() === 'Totals',
    );
    const rows = {};
    for (const dt of card?.querySelectorAll('dl > dt') ?? []) {
      const label = dt.childNodes[0].textContent.trim();
      rows[label] = dt.nextElementSibling?.childNodes[0].textContent.replace(/[^\d.,-]/g, '');
    }
    return rows;
  });
}

/** Actions menu -> "Refund..." -> the open refund modal. */
async function openRefund(page) {
  await page.getByRole('button', { name: 'Actions' }).first().click();
  await page
    .getByRole('button', { name: /^Refund/ })
    .first()
    .click();
  const modal = page.locator('.modal.show');
  await modal.getByRole('heading', { name: 'Refund Order' }).waitFor({ timeout: 30000 });
  return modal;
}

/** Reads the preview block of the open refund modal: { gateway, credits, total } as numbers. */
async function previewSplit(modal, total) {
  await waitFor(
    'the preview for the entered amount',
    async () =>
      (await modal
        .getByRole('button', { name: new RegExp(`^Refund .*${total.replace('.', '\\.')}`) })
        .count()) > 0,
  );
  return modal.evaluate((el) => {
    const read = (label) => {
      const dt = [...el.querySelectorAll('dl > dt')].find((d) => d.textContent.trim() === label);
      const text = dt?.nextElementSibling?.childNodes[0].textContent ?? '';
      return Number(text.replace(/[^\d.-]/g, ''));
    };
    return { gateway: read('Gateway'), credits: read('Credits'), total: read('Total') };
  });
}

/** The fake provider's own refund-support setting (credentials stay as they are). */
async function setFakeRefundSupport({ admin, gateway }, value) {
  must(
    await admin.post(`${PANEL_MARKET_API}/payment-methods/fake`, {
      settings: { gatewayUrl: gateway.baseUrl, secret: gateway.secret, refundSupport: value },
    }),
    `fake refundSupport ${value}`,
  );
}
