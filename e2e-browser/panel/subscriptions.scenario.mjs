// Scenario 70 of 13 section 25.4: subscriptions in the panel (cancel at period end sets the badge, Retry Charge only for a MERCHANT subscription that is PAST_DUE).
import fs from 'node:fs';
import { must, PANEL_MARKET_API, listOf } from '../lib/api.mjs';
import { grantUserNode, product, actions } from '../lib/bootstrap.mjs';
import { payByWebhookOnly } from '../lib/gateway.mjs';
import { rewind } from '../lib/db.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { awaitStatus, checkout } from './lib/orders.mjs';
import { signedIn, openMarket, waitFor, modalsClosed, node } from './lib/panel.mjs';

const enUS = JSON.parse(
  fs.readFileSync(new URL('../../src/locales/panel/en-US.json', import.meta.url), 'utf8'),
);

/** A subscriber of `plan` who paid the first period at the fake gateway; returns `{ api, subscriptionId }`. */
export async function subscribe(ctx, plan, label) {
  const { admin } = ctx;
  const api = await ctx.buyer(label);
  await grantUserNode(admin, api.userId, node('manage.market.payments'));
  const placed = await checkout(api, {
    items: [{ productId: plan.id, quantity: 1 }],
    paymentMethodId: 'fake',
  });
  await payByWebhookOnly(placed.payUrl);
  await awaitStatus(api, placed.publicId, 'COMPLETED');
  const subscriptionId = await waitFor('the subscription to exist', async () => {
    const list = must(
      await admin.get(
        `${PANEL_MARKET_API}/subscriptions?search=${encodeURIComponent(api.username)}`,
      ),
      'subscriptions',
    ).json;
    return listOf(list, 'subscriptions')[0]?.id ?? null;
  });

  return { api, subscriptionId };
}

export const subscriptionOf = async (admin, username) =>
  listOf(
    must(
      await admin.get(`${PANEL_MARKET_API}/subscriptions?search=${encodeURIComponent(username)}`),
      'subscriptions',
    ).json,
    'subscriptions',
  )[0];

export const scenarios = [
  {
    id: 'PANEL-70',
    title:
      'subscriptions: cancel at period end sets the badge, Retry Charge is offered only for a MERCHANT subscription that is PAST_DUE',
    async run(ctx) {
      const { env, admin, gateway } = ctx;
      const plan = await product(admin, 'Plan 70', {
        price: '5.00',
        billingMode: 'SUBSCRIPTION',
        periodUnit: 'MONTH',
        periodCount: '1',
        actions: actions.credit(1),
      });
      const first = await subscribe(ctx, plan, 'p70a');
      const second = await subscribe(ctx, plan, 'p70b');
      const { pc, page } = await signedIn(ctx.browser, admin);

      const rowOf = (username) => page.locator('table tbody tr').filter({ hasText: username });
      const menuOf = async (username) => {
        const row = rowOf(username);
        await row.locator('button[data-bs-toggle="dropdown"]').click();
        return row.locator('.dropdown-menu.show .dropdown-item');
      };
      const openList = (username) =>
        openMarket(page, env, `/market/subscriptions?search=${encodeURIComponent(username)}`, (p) =>
          p
            .locator('table tbody tr')
            .filter({ hasText: username })
            .first()
            .waitFor({ timeout: 60000 }),
        );

      // --- the fake provider's subscriptions are charged by the store itself
      const a = await subscriptionOf(admin, first.api.username);
      const b = await subscriptionOf(admin, second.api.username);
      assertEqual(a.mode, 'MERCHANT', 'PANEL-70: subscriber A is a MERCHANT subscription');
      assertEqual(b.mode, 'MERCHANT', 'PANEL-70: subscriber B is a MERCHANT subscription');
      assertEqual(a.status, 'ACTIVE', 'PANEL-70: A is ACTIVE');

      // --- cancel at period end: the badge, the subscription stays ACTIVE, no Retry for an ACTIVE row
      await openList(first.api.username);
      const items = await menuOf(first.api.username);
      assertEqual(
        JSON.stringify((await items.allInnerTexts()).map((t) => t.trim())),
        JSON.stringify([
          enUS.pages.subscriptions.actions.view,
          enUS.pages.subscriptions.actions.cancel,
        ]),
        'PANEL-70: an ACTIVE MERCHANT subscription offers View and Cancel, not Retry Charge',
      );
      await items.filter({ hasText: enUS.pages.subscriptions.actions.cancel }).click();
      const modal = page.locator('.modal.show');
      await modal.getByRole('heading', { name: 'Cancel Subscription' }).waitFor({ timeout: 15000 });
      assert(
        await modal.locator('#cancel-sub-period-end').isChecked(),
        'PANEL-70: "At Period End" is the default timing',
      );
      await modal.getByRole('button', { name: 'Cancel Subscription' }).click();
      await page
        .getByText(enUS.modals['cancel-subscription']['toast-cancelled'])
        .first()
        .waitFor({ timeout: 15000 });
      await modalsClosed(page);
      await rowOf(first.api.username)
        .getByText(enUS.pages.subscriptions['cancels-at-period-end'])
        .waitFor({ timeout: 30000 });
      const cancelled = await subscriptionOf(admin, first.api.username);
      assertEqual(cancelled.cancelAtPeriodEnd, true, 'PANEL-70: the API says cancelAtPeriodEnd');
      assertEqual(
        cancelled.status,
        'ACTIVE',
        'PANEL-70: the period is paid, the status stays ACTIVE',
      );
      assert(
        !(await (await menuOf(first.api.username)).allInnerTexts()).some((t) =>
          t.includes(enUS.pages.subscriptions.actions.retry),
        ),
        'PANEL-70: no Retry Charge after a cancel at period end',
      );
      await page.keyboard.press('Escape');

      // --- B: the renewal is declined, the subscription goes PAST_DUE and only now Retry Charge is offered
      await openList(second.api.username);
      assert(
        !(await (await menuOf(second.api.username)).allInnerTexts()).some((t) =>
          t.includes(enUS.pages.subscriptions.actions.retry),
        ),
        'PANEL-70: no Retry Charge while B is ACTIVE',
      );
      await page.keyboard.press('Escape');

      gateway.declineNextCharge(1);
      const chargesBefore = gateway.charges.length;
      rewind('market_subscription', second.subscriptionId, 'nextChargeAt', 40);
      await waitFor(
        'B to go PAST_DUE',
        async () => (await subscriptionOf(admin, second.api.username)).status === 'PAST_DUE',
        { timeout: 240000, interval: 2000 },
      );
      assertEqual(
        gateway.charges.length - chargesBefore,
        1,
        'PANEL-70: the declined renewal reached the gateway once',
      );

      await openList(second.api.username);
      await rowOf(second.api.username)
        .getByText(enUS.enums.subscription.PAST_DUE)
        .first()
        .waitFor({ timeout: 15000 });
      const pastDue = await menuOf(second.api.username);
      assert(
        (await pastDue.allInnerTexts()).some((t) =>
          t.includes(enUS.pages.subscriptions.actions.retry),
        ),
        'PANEL-70: Retry Charge is offered for a MERCHANT subscription that is PAST_DUE',
      );

      // --- Retry Charge: the confirmation, the gateway pays this time, the subscription is ACTIVE again and the entry is gone
      await pastDue.filter({ hasText: enUS.pages.subscriptions.actions.retry }).click();
      const confirm = page.locator('.modal.show');
      await confirm
        .getByRole('heading', { name: enUS.pages.subscriptions['retry-title'] })
        .waitFor({ timeout: 15000 });
      assertEqual(await confirm.locator('.modal-header').count(), 0, 'PANEL-70: no modal header');
      await confirm.getByRole('button', { name: enUS.pages.subscriptions.actions.retry }).click();
      await page
        .getByText(enUS.pages.subscriptions['toast-retry'])
        .first()
        .waitFor({ timeout: 15000 });
      await waitFor(
        'B to be ACTIVE again',
        async () => (await subscriptionOf(admin, second.api.username)).status === 'ACTIVE',
        { timeout: 60000 },
      );
      assertEqual(
        gateway.charges.length - chargesBefore,
        2,
        'PANEL-70: the retry reached the gateway (second charge)',
      );
      await modalsClosed(page);
      await waitFor('the list to show B as Active', async () =>
        (await rowOf(second.api.username).innerText()).includes(enUS.enums.subscription.ACTIVE),
      );
      assert(
        !(await (await menuOf(second.api.username)).allInnerTexts()).some((t) =>
          t.includes(enUS.pages.subscriptions.actions.retry),
        ),
        'PANEL-70: Retry Charge is gone once the subscription is ACTIVE again',
      );

      pc.expectNoErrors('PANEL-70');
      await pc.close();
    },
  },
];
