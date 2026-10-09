// Theme browser scenarios 43 to 46 of 14 section 20.3 (the profile pages: purchases, credits, subscriptions, creator), vanilla theme.
// Ids TH-43 .. TH-46 are the numbers of the spec. Data is made through the storefront and panel APIs like the owner and the buyers would;
// every scenario fails on a console error or a page error that it did not provoke on purpose.
import { must, MARKET_API, PANEL_MARKET_API, listOf } from '../lib/api.mjs';
import { actions, grantCredits, product as panelProduct, run } from '../lib/bootstrap.mjs';
import { assert, assertEqual, hydrated, open } from '../lib/ui.mjs';
import {
  holdAtGateway,
  openCheckout,
  payBuyer,
  placeButton,
  setCreditSettings,
  signedIn,
  takeProvokedErrors,
  text,
  textRe,
  waitForOrderPage,
  waitQuoted,
  withBankTransfer,
  withSettingsAndWait,
} from './lib/checkout.mjs';
import { addFromCard, status as httpStatus } from './lib/helpers.mjs';
import {
  checkout,
  idem,
  likeRe,
  orderRowId,
  paidOrder,
  sleep,
  sql,
  table,
  until,
  view,
  waitUntil,
  withLiveStore,
} from './lib/orders.mjs';

const profileUrl = (env, path) => `${env.url}${path}`;
const rowsOf = (page) => page.locator('section[aria-labelledby="market-orders-title"] tbody tr');

/** A free order (the "Free Kit" product): COMPLETED at once. */
const freeOrder = (api, free) => checkout(api, { items: [{ productId: free.id, quantity: 1 }] });

/** The numbers of the credits page (the balance card), as the buyer reads them. */
const balanceText = (page) => page.locator('#market-balance-title + div').innerText();

/** A unique, valid gift code (at least 8 characters, letters and digits). */
const giftCode = (label) => `${label}${run.tag}${run.next()}`.toUpperCase();

export const scenarios = [
  {
    id: 'TH-43',
    title:
      'purchases: paging and the status filter keep one document, a received gift is marked, gift codes redeem / refuse / lock',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip, free } = await catalogue();
      const account = await payBuyer(buyer, admin, 'p43');

      // 9 free orders, an open one, a cancelled one, a refunded one: 12 orders of the buyer
      for (let i = 0; i < 9; i++) await freeOrder(account, free);
      const pending = await checkout(account, {
        items: [{ productId: vip.id, quantity: 1 }],
        paymentMethodId: 'fake',
      });
      const dropped = await checkout(account, {
        items: [{ productId: vip.id, quantity: 1 }],
        paymentMethodId: 'fake',
      });

      must(await account.post(`${MARKET_API}/orders/${dropped.publicId}/cancel`, {}), 'cancel');

      const refunded = await paidOrder(account, [{ productId: vip.id, quantity: 1 }]);

      must(
        await admin.post(
          `${PANEL_MARKET_API}/orders/${await orderRowId(admin, refunded.publicId)}/refunds`,
          { amount: 10, reason: 'E2E refund' },
          idem(),
        ),
        'refund',
      );
      await until(account, refunded.publicId, (o) => o.status === 'REFUNDED', 'the refunded order');

      // a gift for this buyer, bought and paid by someone else: the newest row
      const gifter = await payBuyer(buyer, admin, 'g43');
      const gift = await paidOrder(gifter, [{ productId: vip.id, quantity: 1 }], {
        recipientUsername: account.username,
      });
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();

      await open(page, profileUrl(env, '/profile/purchases'), (p) =>
        p
          .locator('section[aria-labelledby="market-orders-title"] tbody tr')
          .first()
          .waitFor({ timeout: 60000 }),
      );
      await page.evaluate(() => {
        window.__E2E_SAME_DOCUMENT__ = true;
      });

      // paging: 13 orders, 10 a page
      assertEqual(await rowsOf(page).count(), 10, 'the first page holds 10 orders');
      assert(
        await rowsOf(page)
          .first()
          .getByText(text('theme.profile.purchases.gift-received'))
          .isVisible(),
        'the newest row is the gift this buyer received, and it says so',
      );
      assertEqual(
        await rowsOf(page).first().locator('a').first().getAttribute('href'),
        `/store/order/${gift.publicId}`,
        'it links to the gift order',
      );

      await page.getByRole('button', { name: text('theme.store.page-n', { page: 2 }) }).click();
      await waitUntil(
        async () => (await rowsOf(page).count()) === 3,
        15000,
        'the second page of 3 orders',
      );
      assert(
        page.url().includes('page=2'),
        `the page number is written to the address (${page.url()})`,
      );

      // the status filter, without a reload, one status at a time
      const filter = page.locator('#market-orders-filter');
      const expectOnly = async (value, count, label) => {
        await filter.selectOption(value);
        // the address follows the answer: wait for it before looking at the rows (two filters can hold the same number of rows)
        await page.waitForFunction(
          (wanted) => new URL(location.href).searchParams.get('status') === wanted,
          value,
          { timeout: 15000 },
        );
        await waitUntil(
          async () => (await rowsOf(page).count()) === count,
          15000,
          `${count} ${label} row(s)`,
        );
        assert(!page.url().includes('page='), 'the page is back to the first');
        const badges = await rowsOf(page).locator('.badge').allInnerTexts();

        assert(
          badges.every((b) => b === text(`theme.status.order.${value}`)),
          `${label}: every row carries the badge ${value} (${badges.join(', ')})`,
        );
      };

      await expectOnly('PENDING', 1, 'open');
      await expectOnly('CANCELLED', 1, 'cancelled');
      await expectOnly('REFUNDED', 1, 'refunded');
      await expectOnly('COMPLETED', 10, 'completed');
      await filter.selectOption('');
      await waitUntil(async () => (await rowsOf(page).count()) === 10, 15000, 'all orders again');
      assertEqual(
        await page.evaluate(() => window.__E2E_SAME_DOCUMENT__ === true),
        true,
        'paging and filtering never reloaded the document',
      );
      assert(pending.publicId && dropped.publicId, 'the fixtures exist');

      // a deep link: ?page=2&status=COMPLETED is rendered by the server
      await open(page, profileUrl(env, '/profile/purchases?status=PENDING'), (p) =>
        p.locator('#market-orders-filter').waitFor({ timeout: 60000 }),
      );
      assertEqual(await filter.inputValue(), 'PENDING', 'a deep link restores the filter');
      assertEqual(await rowsOf(page).count(), 1, 'and its rows');

      // ---- gift codes ---------------------------------------------------------------------------------------------------------------
      await open(page, profileUrl(env, '/profile/purchases'), (p) =>
        p.locator('#market-gift-code').waitFor({ timeout: 60000 }),
      );

      const code = giftCode('G43');
      const created = must(
        await admin.post(`${PANEL_MARKET_API}/gifts`, {
          name: `Gift ${code}`,
          code,
          type: 'CREDIT',
          creditAmount: 5,
          redeemLimit: 5,
          status: 'ACTIVE',
        }),
        'create the gift code',
      );

      assert(created.json.id, 'the gift code exists');

      // an unknown code: the field explains, nothing else happens
      await page.locator('#market-gift-code').fill('NO-SUCH-CODE-43');
      await page
        .getByRole('button', { name: text('theme.profile.purchases.redeem-submit') })
        .click();
      await page.getByText(text('theme.errors.CODE_NOT_FOUND')).waitFor({ timeout: 15000 });
      assertEqual(
        await page.locator('#market-gift-code').getAttribute('aria-invalid'),
        'true',
        'the field is marked invalid',
      );
      takeProvokedErrors(ctx, /status of 400/, 1, 'TH-43 unknown gift code');

      // the real code: the buyer lands on the zero-total order page, paid, and the credits arrived
      await page.locator('#market-gift-code').fill(code.toLowerCase());
      await page
        .getByRole('button', { name: text('theme.profile.purchases.redeem-submit') })
        .click();
      const redeemedId = await waitForOrderPage(page);

      await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 60000 });
      assertEqual(
        (await view(account, redeemedId)).status,
        'COMPLETED',
        'the redeemed order is completed',
      );
      ctx.expectNoErrors('TH-43');
      await ctx.close();

      // too many wrong codes lock the redeem form, even for the right code, with a countdown
      await withSettingsAndWait(
        admin,
        { couponLockThreshold: 3 },
        { couponLockThreshold: 1000 },
        async () => {
          const locked = await payBuyer(buyer, admin, 'l43');
          const lctx = await signedIn(browser, locked);
          const lpage = await lctx.page();

          await open(lpage, profileUrl(env, '/profile/purchases'), (p) =>
            p.locator('#market-gift-code').waitFor({ timeout: 60000 }),
          );

          for (const wrong of ['WRONG-CODE-ONE', 'WRONG-CODE-TWO', 'WRONG-CODE-THREE']) {
            await lpage.locator('#market-gift-code').fill(wrong);
            await lpage
              .getByRole('button', { name: text('theme.profile.purchases.redeem-submit') })
              .click();
            await lpage.getByText(text('theme.errors.CODE_NOT_FOUND')).waitFor({ timeout: 15000 });
            await lpage.locator('#market-gift-code').fill('');
          }

          // the next try is refused before any lookup, valid code or not
          const fresh = giftCode('G43L');

          must(
            await admin.post(`${PANEL_MARKET_API}/gifts`, {
              name: `Gift ${fresh}`,
              code: fresh,
              type: 'CREDIT',
              creditAmount: 1,
              redeemLimit: 5,
              status: 'ACTIVE',
            }),
            'create the second gift code',
          );
          await lpage.locator('#market-gift-code').fill(fresh);
          await lpage
            .getByRole('button', { name: text('theme.profile.purchases.redeem-submit') })
            .click();
          await lpage
            .getByText(likeRe('theme.profile.purchases.gift-locked'))
            .waitFor({ timeout: 15000 });
          assert(await lpage.locator('#market-gift-code').isDisabled(), 'the code field is locked');
          assert(
            await lpage
              .getByRole('button', { name: text('theme.profile.purchases.redeem-submit') })
              .isDisabled(),
            'and so is the button',
          );
          assertEqual(
            (await locked.get(`${MARKET_API}/me/orders?page=1`)).json.items?.length,
            0,
            'the valid code was not redeemed while locked',
          );
          takeProvokedErrors(lctx, /status of 400/, 3, 'TH-43 wrong codes');
          takeProvokedErrors(lctx, /status of 429/, 1, 'TH-43 locked');
          lctx.expectNoErrors('TH-43 locked');
          await lctx.close();
        },
      );
    },
  },

  {
    id: 'TH-44',
    title:
      'credits: ledger signs, a credit pack and a free-amount top-up end to end, out-of-range amounts are refused',
    async run({ browser, env, admin, gateway, catalogue, buyer }) {
      const { vip } = await catalogue();
      const account = await payBuyer(buyer, admin, 'c44');
      const pack = await panelProduct(admin, 'Pack44', {
        price: '5.00',
        kind: 'CREDIT_PACK',
        creditAmount: '50.00',
      });

      await grantCredits(admin, account.userId, 10);

      // the buyer spends the 10 on the vip rank (creditPrice 10): a minus row
      must(
        await account.post(
          `${MARKET_API}/checkout`,
          { items: [{ productId: vip.id, quantity: 1 }], payWithCredits: true },
          idem(),
        ),
        'pay with credits',
      );

      await setCreditSettings(admin, {
        creditTopUpEnabled: true,
        creditTopUpFreeAmount: true,
        creditTopUpMin: 5,
        creditTopUpMax: 50,
      });
      await sleep(1500);

      const ctx = await signedIn(browser, account);
      const page = await ctx.page();
      const held = await holdAtGateway(page, gateway);

      try {
        await open(page, profileUrl(env, '/profile/credits'), (p) =>
          p.locator('#market-balance-title').waitFor({ timeout: 60000 }),
        );
        assert(
          (await balanceText(page)).replace(/\s/g, '').startsWith('0'),
          `the grant (10) and the order (10) leave nothing (${await balanceText(page)})`,
        );

        // ledger signs: the grant is green with a plus, the payment red with a minus; the order row links to the order
        const ledger = page.locator('section[aria-labelledby="market-ledger-title"] tbody tr');

        await ledger.first().waitFor({ timeout: 30000 });
        const grant = ledger.filter({ hasText: text('theme.profile.credits.type.GRANT') }).first();
        const spend = ledger.filter({ hasText: text('theme.profile.credits.type.HOLD') }).first();

        assert(
          (await grant.locator('td').nth(2).innerText()).trim().startsWith('+'),
          'a grant has a plus sign',
        );
        assert(
          (await grant.locator('td').nth(2).getAttribute('class')).includes('text-success'),
          'and the success colour',
        );
        assert(
          /^[-\u2212]/.test((await spend.locator('td').nth(2).innerText()).trim()),
          'a payment has a minus sign',
        );
        assert(
          (await spend.locator('td').nth(2).getAttribute('class')).includes('text-danger'),
          'and the danger colour',
        );
        assertEqual(await spend.locator('a').count(), 1, 'the payment links to its order');

        // out-of-range amounts of the free-amount top-up: explained inline, nothing is sent
        const amount = page.locator('#market-topup-amount');
        const submit = page.getByRole('button', {
          name: text('theme.profile.credits.topup-submit'),
        });

        await amount.fill('2');
        await submit.click();
        await page
          .getByText(likeRe('theme.errors.INVALID_CREDIT_AMOUNT_BELOW_MINIMUM'))
          .first()
          .waitFor({ timeout: 10000 });
        assert(page.url().endsWith('/profile/credits'), 'a too small amount stays on the page');
        await amount.fill('500');
        await submit.click();
        await page
          .getByText(likeRe('theme.errors.INVALID_CREDIT_AMOUNT_ABOVE_MAXIMUM'))
          .first()
          .waitFor({ timeout: 10000 });
        assert(page.url().endsWith('/profile/credits'), 'a too large amount stays on the page');

        // a valid amount: the checkout of the top-up, paid at the gateway, the balance and a TOPUP row follow
        await amount.fill('12');
        await submit.click();
        await page.waitForURL(
          (url) => url.pathname === '/store/checkout' && url.searchParams.get('topup') === '12',
          { timeout: 60000 },
        );
        await openCheckoutReady(page);
        await page.locator('input[type="radio"][value="fake"]').check();
        await waitQuoted(page);
        await placeButton(page).click();
        const topUpOrder = await waitForOrderPage(page);

        await held.release();
        await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 90000 });
        assertEqual(
          (await view(account, topUpOrder)).status,
          'COMPLETED',
          'the top-up order is completed',
        );

        // a credit pack from the same page: card -> cart -> checkout -> paid
        await open(page, profileUrl(env, '/profile/credits'), (p) =>
          p.locator('#market-balance-title').waitFor({ timeout: 60000 }),
        );
        assert(
          (await balanceText(page)).includes('12'),
          `the top-up arrived (${await balanceText(page)})`,
        );
        await addFromCardOnPage(page, pack.name);
        await openCheckout(page, env);
        await waitQuoted(page);
        await page.locator('input[type="radio"][value="fake"]').check();
        await waitQuoted(page);
        await placeButton(page).click();
        const packOrder = await waitForOrderPage(page);

        await held.release();
        await page.getByText(text('theme.order.state.paid')).first().waitFor({ timeout: 90000 });
        assertEqual(
          (await view(account, packOrder)).status,
          'COMPLETED',
          'the pack order is completed',
        );

        await open(page, profileUrl(env, '/profile/credits'), (p) =>
          p.locator('#market-balance-title').waitFor({ timeout: 60000 }),
        );
        assert(
          (await balanceText(page)).includes('62'),
          `10 + 12 + 50 - 10 = 62 (${await balanceText(page)})`,
        );
        const topUps = page
          .locator('section[aria-labelledby="market-ledger-title"] tbody tr')
          .filter({ hasText: text('theme.profile.credits.type.TOPUP') });

        assert(
          (await topUps.count()) >= 2,
          'the ledger has a TOPUP row for the free amount and one for the pack',
        );
        assert(
          (await topUps.first().locator('td').nth(2).innerText()).trim().startsWith('+'),
          'a top-up has a plus sign',
        );
        ctx.expectNoErrors('TH-44');
      } finally {
        await setCreditSettings(admin, {
          creditTopUpEnabled: false,
          creditTopUpFreeAmount: false,
          creditTopUpMin: 1,
          creditTopUpMax: 10000,
        }).catch(() => {});
        await ctx.close();
      }
    },
  },

  {
    id: 'TH-45',
    title: 'subscriptions: cancel at period end, keep it again, and the past-due banner',
    async run({ browser, env, admin, catalogue, buyer }) {
      await catalogue();
      const sub = await panelProduct(admin, 'Sub45', {
        price: '6.00',
        billingMode: 'SUBSCRIPTION',
        periodUnit: 'MONTH',
        periodCount: '1',
        actions: actions.credit(1),
      });
      const account = await payBuyer(buyer, admin, 's45');

      await paidOrder(account, [{ productId: sub.id, quantity: 1 }]);

      const mine = async () =>
        listOf((await account.get(`${MARKET_API}/me/subscriptions`)).json, 'subscriptions');

      await waitUntil(
        async () => (await mine()).some((s) => s.status === 'ACTIVE'),
        30000,
        'the subscription to turn ACTIVE',
      );

      const row = (await mine()).find((s) => s.status === 'ACTIVE');
      const ctx = await signedIn(browser, account);
      const page = await ctx.page();

      await open(page, profileUrl(env, '/profile/subscriptions'), (p) =>
        p.locator('article').first().waitFor({ timeout: 60000 }),
      );
      await page.evaluate(() => {
        window.__E2E_SAME_DOCUMENT__ = true;
      });

      const card = page.locator('article', { hasText: sub.name });
      const cancelButton = card.getByRole('button', {
        name: text('theme.profile.subscriptions.cancel'),
        exact: true,
      });
      const keepButton = card.getByRole('button', {
        name: text('theme.profile.subscriptions.keep'),
      });

      await card
        .getByText(text('theme.status.subscription.ACTIVE'), { exact: true })
        .waitFor({ timeout: 10000 });
      await card
        .getByText(likeRe('theme.profile.subscriptions.renews-on'))
        .waitFor({ timeout: 10000 });
      assertEqual(await keepButton.count(), 0, 'nothing to keep while it renews');

      // cancel at the end of the period: the dialog names the date, "Keep it" leaves everything alone
      await cancelButton.click();
      const modal = page.locator(`#marketCancelSubscriptionModal-${row.id}`);

      await modal
        .getByText(likeRe('theme.profile.subscriptions.cancel-confirm'))
        .waitFor({ timeout: 10000 });
      await modal
        .getByRole('button', { name: text('theme.profile.subscriptions.cancel-keep') })
        .click();
      await modal.waitFor({ state: 'hidden', timeout: 10000 });
      assertEqual((await mine())[0].cancelAtPeriodEnd, false, 'keeping it changes nothing');

      await cancelButton.click();
      await modal
        .getByRole('button', { name: text('theme.profile.subscriptions.cancel-yes') })
        .click();
      await card
        .getByText(likeRe('theme.profile.subscriptions.ends-on'))
        .waitFor({ timeout: 15000 });
      assertEqual(
        (await mine())[0].cancelAtPeriodEnd,
        true,
        'the subscription is set to end with its period',
      );
      assertEqual((await mine())[0].status, 'ACTIVE', 'and stays ACTIVE until then');
      assertEqual(await cancelButton.count(), 0, 'no second cancel');
      await keepButton.waitFor({ timeout: 10000 });

      // keep it: renews again
      await keepButton.click();
      await card
        .getByText(likeRe('theme.profile.subscriptions.renews-on'))
        .waitFor({ timeout: 15000 });
      assertEqual((await mine())[0].cancelAtPeriodEnd, false, 'the cancellation is withdrawn');
      await cancelButton.waitFor({ timeout: 10000 });
      assertEqual(
        await page.evaluate(() => window.__E2E_SAME_DOCUMENT__ === true),
        true,
        'cancel and keep never reloaded the document',
      );

      // a renewal that failed: the owner of a subscription sees the banner and the badge (the status is rewound, like the Kotlin E2E classes do;
      // the grace end is a day away so nothing expires meanwhile)
      sql(
        `UPDATE ${table('subscription')} SET status = 'PAST_DUE', graceEndsAt = ${Date.now() + 86_400_000}, failCount = 1 WHERE id = ${row.id}`,
      );
      await page.reload({ waitUntil: 'domcontentloaded' });
      await hydrated(page);
      await card
        .getByText(text('theme.profile.subscriptions.past-due'))
        .waitFor({ timeout: 30000 });
      await card
        .getByText(text('theme.status.subscription.PAST_DUE'), { exact: true })
        .waitFor({ timeout: 10000 });
      ctx.expectNoErrors('TH-45');
      await ctx.close();
    },
  },

  {
    id: 'TH-46',
    title: 'creator page: 404 for a non-creator; codes, totals and earnings for a creator',
    async run({ browser, env, admin, catalogue, buyer }) {
      const { vip } = await catalogue();
      const plain = await payBuyer(buyer, admin, 'n46');
      const plainCtx = await signedIn(browser, plain);
      const plainPage = await plainCtx.page();

      assertEqual(
        await httpStatus(plainPage, profileUrl(env, '/profile/creator')),
        404,
        'a buyer who owns no creator code gets a 404',
      );
      assertEqual(
        (await plain.get(`${MARKET_API}/me/creator`)).status,
        404,
        'the API says the same',
      );
      await plainCtx.close();

      const creator = await payBuyer(buyer, admin, 'k46');
      const code = giftCode('C46');

      await withLiveStore(admin, async () => {
        await withBankTransfer(admin, async () => {
          must(
            await admin.post(`${PANEL_MARKET_API}/creator-codes`, {
              creator: creator.username,
              creatorUserId: creator.userId,
              code,
              discount: 10,
              unit: 'PERCENT',
              commissionPercent: 20,
              status: 'ACTIVE',
            }),
            'create the creator code',
          );

          // a real sale (a test order earns nothing) with the code, approved by the owner
          const customer = await buyer('u46');
          const placed = await checkout(customer, {
            items: [{ productId: vip.id, quantity: 1 }],
            paymentMethodId: 'bank-transfer',
            creatorCode: code,
          });

          must(
            await admin.post(
              `${PANEL_MARKET_API}/orders/${await orderRowId(admin, placed.publicId)}/bank-transfer`,
              { decision: 'APPROVE' },
            ),
            'approve the transfer',
          );
          await until(
            customer,
            placed.publicId,
            (o) => o.status === 'COMPLETED',
            'the creator order',
          );

          const api = (await creator.get(`${MARKET_API}/me/creator`)).json;

          assert(
            api.items?.length === 1,
            `the creator has one earning (${JSON.stringify(api.items)})`,
          );

          const ctx = await signedIn(browser, creator);
          const page = await ctx.page();
          const euro = (n) => `€${Number(n).toFixed(2)}`;

          await open(page, profileUrl(env, '/profile/creator'), (p) =>
            p.locator('#market-creator-codes').waitFor({ timeout: 60000 }),
          );

          // totals: the three cards are the server's numbers
          for (const [key, value] of [
            ['theme.profile.creator.earned', api.totals.earned],
            ['theme.profile.creator.paid-out', api.totals.paidOut],
            ['theme.profile.creator.available', api.totals.available],
          ]) {
            const card = page.locator('.card', { hasText: text(key) }).first();

            assert(
              (await card.innerText()).includes(euro(value)),
              `${key}: ${euro(value)} (${await card.innerText()})`,
            );
          }

          assert(api.totals.earned > 0, 'the creator earned something');

          // the code, its discount, commission and use
          const codeRow = page
            .locator('section[aria-labelledby="market-creator-codes"] tbody tr')
            .first();

          assertEqual(await codeRow.locator('code').innerText(), code, 'the code is listed');
          assertEqual(
            (await codeRow.locator('td').nth(1).innerText()).trim(),
            '10%',
            'with its discount',
          );
          assertEqual(
            (await codeRow.locator('td').nth(2).innerText()).trim(),
            '20%',
            'and its commission',
          );
          assertEqual((await codeRow.locator('td').nth(3).innerText()).trim(), '1', 'used once');
          assertEqual(
            (await codeRow.locator('td').nth(4).innerText()).trim(),
            text('theme.profile.creator.code-status.ACTIVE'),
            'and active',
          );

          // the earning: the order number, the amount, the state; no buyer data
          const earning = page
            .locator('section[aria-labelledby="market-creator-earnings"] tbody tr')
            .first();

          assertEqual(
            (await earning.locator('td').nth(0).innerText()).trim(),
            `#${api.items[0].orderNumber}`,
            'the earning names its order',
          );
          assertEqual(
            (await earning.locator('td').nth(1).innerText()).trim(),
            euro(api.items[0].amount),
            'and the commission',
          );
          assertEqual(
            (await earning.locator('td').nth(2).innerText()).trim(),
            text(`theme.profile.creator.state.${api.items[0].state}`),
            'and its state',
          );
          assert(
            !(await page.locator('body').innerText()).includes(customer.username),
            'the customer is not named',
          );
          ctx.expectNoErrors('TH-46');
          await ctx.close();
        });
      });
    },
  },
];

/** The checkout page of a top-up (it has no cart): waits for the form. */
async function openCheckoutReady(page) {
  await page.waitForFunction(() => document.querySelector('#market-checkout-summary-card'), null, {
    timeout: 120000,
  });
  await waitQuoted(page);
}

/** Presses "Add to Cart" on the card of the product with `name` on the credits page. */
async function addFromCardOnPage(page, name) {
  const card = page.locator('.card', { hasText: name }).first();

  await card.waitFor({ timeout: 30000 });
  await card.getByRole('button', { name: 'Add to Cart' }).click();
  await page.getByText('Added to cart.').first().waitFor({ timeout: 30000 });
}
