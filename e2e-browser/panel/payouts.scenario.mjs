// Scenarios 66 and 67 of 13 section 25.4: creator payouts (field rejection, CREATOR_HAS_NO_ACCOUNT, report CSV) and the block list (CIDR, duplicate, removal).
import fs from 'node:fs';
import { must, MARKET_API, PANEL_MARKET_API, listOf } from '../lib/api.mjs';
import { run } from '../lib/bootstrap.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { enableBankTransfer, checkout } from './lib/orders.mjs';
import {
  signedIn,
  openMarket,
  waitFor,
  modalsClosed,
  bodyText,
  settingsPatch,
  provoked,
  refreshSettled,
} from './lib/panel.mjs';

const enUS = JSON.parse(
  fs.readFileSync(new URL('../../src/locales/panel/en-US.json', import.meta.url), 'utf8'),
);

/** A creator code of a fresh player and one live order that used it: a test order never earns a commission, so the store goes live for a moment. */
async function creatorWithEarning({ admin, buyer }, product) {
  // a name no account carries: the code has no linked user (creatorUserId null), so a CREDIT payout has no account to credit
  const creator = { username: `ncr${run.tag}${run.next()}`.slice(0, 16) };
  const code = `C${Date.now().toString(36).toUpperCase()}${run.next()}`;
  const id = must(
    await admin.post(`${PANEL_MARKET_API}/creator-codes`, {
      creator: creator.username,
      code,
      discount: 0,
      unit: 'PERCENT',
      commissionPercent: 10,
    }),
    'creator code',
  ).json.id;

  await enableBankTransfer(admin);
  const restore = await settingsPatch(admin, { testMode: false, creatorEarningHoldDays: 0 });
  try {
    const customer = await buyer('cu');
    const placed = await checkout(customer, {
      items: [{ productId: product.id, quantity: 1 }],
      paymentMethodId: 'bank-transfer',
      creatorCode: code,
    });
    const detail = must(
      await admin.get(`${PANEL_MARKET_API}/orders/${placed.number}`),
      'order',
    ).json;
    must(
      await admin.post(`${PANEL_MARKET_API}/orders/${detail.order.id}/bank-transfer`, {
        decision: 'APPROVE',
      }),
      'approve the bank transfer',
    );
    await waitFor('the order to be completed', async () => {
      const view = (await customer.get(`${MARKET_API}/orders/${placed.publicId}`)).json?.order;
      return view?.status === 'COMPLETED';
    });
  } finally {
    await restore();
  }

  const report = must(await admin.get(`${PANEL_MARKET_API}/creator-codes/report`), 'report').json;
  const row = report.creators.find((c) => c.id === id);
  assert(
    row && Number(row.available) > 0,
    `the creator has an available earning (${JSON.stringify(row)})`,
  );

  return { creator, code, id, available: Number(row.available) };
}

export const scenarios = [
  {
    id: 'PANEL-66',
    title:
      'creator payout: an amount above available is rejected on the field, a CREDIT payout to a creator without an account shows CREATOR_HAS_NO_ACCOUNT, the report downloads as CSV',
    async run(ctx) {
      const { env, admin } = ctx;
      const { vip } = await ctx.catalogue();
      const made = await creatorWithEarning(ctx, vip);
      const { pc, page } = await signedIn(ctx.browser, admin);

      await openMarket(
        page,
        env,
        `/market/discounts/creator/${made.id}?creator=${made.creator.username}`,
        (p) =>
          p
            .getByRole('button', { name: /Pay Out/ })
            .first()
            .waitFor({ timeout: 60000 }),
      );
      await page
        .getByText(/^Available: /)
        .first()
        .waitFor({ timeout: 15000 });

      await page
        .getByRole('button', { name: /Pay Out/ })
        .first()
        .click();
      const modal = page.locator('.modal.show');
      await modal.getByRole('heading', { name: 'Pay Out Creator' }).waitFor({ timeout: 15000 });
      const amount = modal.getByPlaceholder('Amount');

      // --- above the available amount: the field is marked, nothing is sent
      const sent = [];
      page.on('request', (r) => {
        if (r.method() === 'POST' && /creator-codes\/\d+\/payouts/.test(r.url()))
          sent.push(r.url());
      });
      await amount.fill(String(made.available + 5));
      await modal.getByRole('button', { name: 'Pay Out', exact: true }).click();
      await waitFor('the amount field to be marked', async () =>
        ((await amount.getAttribute('class')) ?? '').includes('is-invalid'),
      );
      assertEqual(
        (await modal.locator('.invalid-feedback').first().innerText()).trim(),
        enUS.modals.payout.error.EXCEEDS_AVAILABLE,
        'PANEL-66: the field message for an amount above available',
      );
      assertEqual(sent.length, 0, 'PANEL-66: a payout above available sends no request');
      const before = listOf(
        must(await admin.get(`${PANEL_MARKET_API}/creator-codes/${made.id}/payouts`), 'payouts')
          .json,
        'payouts',
      ).length;

      // --- CREDIT to a creator without a credit account: the radio is marked with CREATOR_HAS_NO_ACCOUNT, the toast names it too
      await amount.fill(String(made.available));
      await modal.getByLabel(enUS.enums['payout-method'].CREDIT).check();
      const mark = pc.errors.length;
      await modal.getByRole('button', { name: 'Pay Out', exact: true }).click();
      await modal.getByText(enUS.errors.CREATOR_HAS_NO_ACCOUNT).first().waitFor({ timeout: 15000 });
      assert(
        ((await modal.locator('#payout-method-CREDIT').getAttribute('class')) ?? '').includes(
          'is-invalid',
        ),
        'PANEL-66: the CREDIT radio is marked invalid',
      );
      await waitFor('the refusal to be logged', async () => pc.errors.length > mark);
      provoked(pc, mark, /status of 4\d\d/, 'PANEL-66 CREATOR_HAS_NO_ACCOUNT');
      const after = listOf(
        must(await admin.get(`${PANEL_MARKET_API}/creator-codes/${made.id}/payouts`), 'payouts')
          .json,
        'payouts',
      ).length;
      assertEqual(after, before, 'PANEL-66: the refused CREDIT payout wrote nothing');

      // --- the same amount as MANUAL goes through: available drops to 0 and the Pay Out button goes away
      await modal.getByLabel(enUS.enums['payout-method'].MANUAL).check();
      // a MANUAL payout is the record of a payment made outside the store: the note is required, marked on the field, nothing is sent
      const note = modal.locator('#payout-note');
      assertEqual(
        await note.getAttribute('placeholder'),
        enUS.modals.payout['note-required'],
        'PANEL-66: the note of a MANUAL payout is not labelled optional',
      );
      sent.length = 0;
      await modal.getByRole('button', { name: 'Pay Out', exact: true }).click();
      await waitFor('the note to be marked', async () =>
        ((await note.getAttribute('class')) ?? '').includes('is-invalid'),
      );
      assertEqual(sent.length, 0, 'PANEL-66: a MANUAL payout without a note sends no request');
      await note.fill('paid outside the store');
      // one click, then (in the page, the moment the success toast shows, while the modal is still fading out, filled in and mounted) a second submit by
      // Enter / requestSubmit and by click: the form is locked from the response until it is reopened, so exactly one payout is posted and recorded
      sent.length = 0;
      const window = await page.evaluate(async (toast) => {
        const form = document.querySelector('.modal.show form');
        const button = form.querySelector('button[type=submit]');
        button.click();
        const deadline = Date.now() + 15000;
        while (!document.body.innerText.includes(toast)) {
          if (Date.now() > deadline) return { toast: false };
          await new Promise((resolve) => requestAnimationFrame(resolve));
        }
        // hide() drops `.show` at once and the fade-out follows: the form is in the window while it is attached and its modal is still displayed
        const stillMounted =
          form.isConnected && getComputedStyle(form.closest('.modal')).display !== 'none';
        const disabled = button.disabled;
        form.requestSubmit();
        form.requestSubmit();
        button.click();
        return { toast: true, stillMounted, disabled };
      }, enUS.modals.payout['toast-paid']);
      assert(window.toast, 'PANEL-66: the payout toast showed');
      assert(
        window.stillMounted,
        'PANEL-66: the second submit was attempted while the modal was still mounted (the window was exercised)',
      );
      assert(window.disabled, 'PANEL-66: the Pay Out button is disabled during the fade-out');
      await modalsClosed(page);
      await refreshSettled(page);
      assertEqual(sent.length, 1, 'PANEL-66: a repeated submit during the fade-out posts nothing');
      assertEqual(
        listOf(
          must(await admin.get(`${PANEL_MARKET_API}/creator-codes/${made.id}/payouts`), 'payouts')
            .json,
          'payouts',
        ).length,
        before + 1,
        'PANEL-66: the MANUAL payout was recorded once',
      );

      // --- the report CSV
      await openMarket(page, env, '/market/discounts?section=payouts', (p) =>
        p.getByText(/^\d+ Creators$/).waitFor({ timeout: 60000 }),
      );
      await page.getByRole('button', { name: 'Actions' }).first().click(); // the card's own menu, the rows' menus come after it
      const [download] = await Promise.all([
        page.waitForEvent('download', { timeout: 30000 }),
        page.getByRole('button', { name: /Export CSV/ }).click(),
      ]);
      const raw = fs.readFileSync(await download.path());
      assert(download.suggestedFilename().endsWith('.csv'), 'PANEL-66: the download is a .csv');
      const lines = raw.toString('utf8').replace(/^﻿/, '').split(/\r?\n/).filter(Boolean);
      const header = lines[0].split(',').map((c) => c.replace(/^"|"$/g, ''));
      assertEqual(
        JSON.stringify(header),
        JSON.stringify([
          'creator',
          'code',
          'uses',
          'revenue',
          'earned',
          'paidOut',
          'available',
          'currency',
        ]),
        'PANEL-66: the CSV columns',
      );
      const mine = lines.find((l) => l.includes(made.code));
      assert(mine, `PANEL-66: the CSV holds the creator code ${made.code}`);
      assert(mine.includes(made.creator.username), 'PANEL-66: the CSV row names the creator');
      assert(
        (await bodyText(page)).includes(made.code),
        'PANEL-66: the report page lists the creator code',
      );

      pc.expectNoErrors('PANEL-66');
      await pc.close();
    },
  },

  {
    id: 'PANEL-67',
    title:
      'blocks: an IP CIDR is added, a duplicate is rejected on the field, removal goes through the confirmation modal',
    async run(ctx) {
      const { env, admin } = ctx;
      const { pc, page } = await signedIn(ctx.browser, admin);
      const octet = 1 + (parseInt(run.tag, 36) % 250);
      const cidr = `198.51.${octet}.0/24`;
      const blocksOf = async () =>
        listOf(
          must(await admin.get(`${PANEL_MARKET_API}/blocks?search=198.51`), 'blocks').json,
          'blocks',
        );

      try {
        await openMarket(page, env, '/market/blocks', (p) =>
          p
            .getByRole('button', { name: /Add Block/ })
            .first()
            .waitFor({ timeout: 60000 }),
        );
        await page
          .getByRole('button', { name: /Add Block/ })
          .first()
          .click();
        const modal = page.locator('.modal.show');
        await modal.getByRole('heading', { name: 'Add Block' }).waitFor({ timeout: 15000 });
        await modal.locator('#block-type').selectOption('IP');
        await modal.locator('#block-value').fill(cidr);
        await modal.locator('#block-reason').fill('e2e browser block');
        await modal.getByRole('button', { name: 'Add Block' }).click();
        await page.getByText(enUS.modals.block['toast-added']).first().waitFor({ timeout: 15000 });
        await modalsClosed(page);
        assert(
          (await blocksOf()).some((b) => b.type === 'IP' && b.value === cidr),
          `PANEL-67: the CIDR ${cidr} is stored`,
        );
        // the list shows it (the host remounted the page after the save)
        await page.getByRole('cell', { name: cidr, exact: true }).waitFor({ timeout: 30000 });

        // --- the same value again: the field is marked (BLOCK_ALREADY_EXISTS), one block only
        await page
          .getByRole('button', { name: /Add Block/ })
          .first()
          .click();
        const again = page.locator('.modal.show');
        await again.getByRole('heading', { name: 'Add Block' }).waitFor({ timeout: 15000 });
        await again.locator('#block-type').selectOption('IP');
        await again.locator('#block-value').fill(cidr);
        const mark = pc.errors.length;
        await again.getByRole('button', { name: 'Add Block' }).click();
        await again
          .getByText(enUS.modals.block.error['value-BLOCK_ALREADY_EXISTS'])
          .waitFor({ timeout: 15000 });
        assert(
          ((await again.locator('#block-value').getAttribute('class')) ?? '').includes(
            'is-invalid',
          ),
          'PANEL-67: the value input is marked invalid',
        );
        await waitFor('the refusal to be logged', async () => pc.errors.length > mark);
        provoked(pc, mark, /status of 4\d\d/, 'PANEL-67 duplicate');
        assertEqual(
          (await blocksOf()).filter((b) => b.value === cidr).length,
          1,
          'PANEL-67: still one block with that value',
        );
        await again.getByRole('button', { name: 'Close' }).click();
        await modalsClosed(page);

        // --- remove through the confirmation modal (it has no header; Cancel keeps the row)
        const row = page.locator('table tbody tr').filter({ hasText: cidr });
        await row.locator('button[data-bs-toggle="dropdown"]').click();
        await row.getByRole('button', { name: 'Remove' }).click();
        const confirm = page.locator('.modal.show');
        await confirm
          .getByRole('heading', { name: enUS.modals['confirm-delete'].block.title })
          .waitFor({ timeout: 15000 });
        assertEqual(await confirm.locator('.modal-header').count(), 0, 'PANEL-67: no modal header');
        await confirm.getByRole('button', { name: 'Cancel' }).click();
        await modalsClosed(page);
        assert(
          (await blocksOf()).some((b) => b.value === cidr),
          'PANEL-67: Cancel kept the block',
        );

        await row.locator('button[data-bs-toggle="dropdown"]').click();
        await row.getByRole('button', { name: 'Remove' }).click();
        await confirm.getByRole('button', { name: 'Remove' }).click();
        await page
          .getByText(enUS.pages.blocks['toast-removed'])
          .first()
          .waitFor({ timeout: 15000 });
        await waitFor(
          'the block to be gone',
          async () => !(await blocksOf()).some((b) => b.value === cidr),
        );
        await modalsClosed(page);
      } finally {
        for (const b of await blocksOf())
          if (b.value === cidr) await admin.delete(`${PANEL_MARKET_API}/blocks/${b.id}`);
      }

      pc.expectNoErrors('PANEL-67');
      await pc.close();
    },
  },
];
