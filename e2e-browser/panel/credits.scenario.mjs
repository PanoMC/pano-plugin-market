// Scenario 65 of 13 section 25.4: store credits in the panel (grant by username, ledger with the admin as actor, revoke more than the balance).
import fs from 'node:fs';
import { must } from '../lib/api.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import {
  signedIn,
  openMarket,
  waitFor,
  modalsClosed,
  bodyText,
  refreshSettled,
} from './lib/panel.mjs';

const enUS = JSON.parse(
  fs.readFileSync(new URL('../../src/locales/panel/en-US.json', import.meta.url), 'utf8'),
);
const GRANT_LABEL = enUS.enums['credit-tx'].GRANT; // the badge text of a GRANT entry

export const scenarios = [
  {
    id: 'PANEL-65',
    title:
      'credits: grant to a player found by username, the ledger shows a GRANT entry by the admin, revoking more than the balance shows the shortfall toast and leaves balance 0',
    async run(ctx) {
      const { env, admin } = ctx;
      const buyer = await ctx.buyer('c65');
      const { pc, page } = await signedIn(ctx.browser, admin);

      // grant 5 to the player, found by username (the dialog looks the account up on blur)
      await openMarket(page, env, '/market/credits', (p) =>
        p
          .getByRole('button', { name: /Grant Credits/ })
          .first()
          .waitFor({ timeout: 60000 }),
      );
      await page
        .getByRole('button', { name: /Grant Credits/ })
        .first()
        .click();
      const modal = page.locator('.modal.show');
      await modal.getByRole('heading', { name: 'Grant Credits' }).waitFor({ timeout: 15000 });
      await modal.getByPlaceholder('Player username').fill(buyer.username);
      await modal.getByPlaceholder('Player username').blur();
      await modal.getByText(/^Balance: /).waitFor({ timeout: 15000 });
      await modal.getByPlaceholder('Amount').fill('5');
      await modal.getByPlaceholder('Reason').fill('e2e browser grant');
      await modal.getByRole('button', { name: 'Grant Credits' }).click();
      await page.getByText('Credits granted.').waitFor({ timeout: 15000 });
      await modalsClosed(page);
      await refreshSettled(page);

      // the ledger of the account: one GRANT entry, the admin is the actor
      await openMarket(
        page,
        env,
        `/market/credits/account/${buyer.userId}?player=${buyer.username}`,
        (p) => p.getByText(/^\d+ Entries$/).waitFor({ timeout: 60000 }),
      );
      const grant = page.locator('table tbody tr').filter({ hasText: GRANT_LABEL }).first();
      await grant.waitFor({ timeout: 15000 });
      const cells = (await grant.locator('td').allInnerTexts()).map((t) => t.trim());
      assertEqual(cells[0], GRANT_LABEL, 'PANEL-65: the entry is a GRANT');
      assert(
        cells.includes(ctx.env.adminUser),
        `PANEL-65: the admin is the actor of the entry (${JSON.stringify(cells)})`,
      );
      assert(cells.includes('e2e browser grant'), 'PANEL-65: the note is in the ledger');
      assert(/5/.test(cells[1]), `PANEL-65: the amount of the entry (${cells[1]})`);

      // revoke 8 of 5: the shortfall toast, balance 0
      await page
        .getByRole('button', { name: /Revoke/ })
        .first()
        .click();
      const dialog = page.locator('.modal.show');
      await dialog.getByRole('heading', { name: 'Revoke Credits' }).waitFor({ timeout: 15000 });
      await dialog.getByPlaceholder('Amount').fill('8');
      await dialog.getByPlaceholder('Reason').fill('e2e browser revoke');
      await dialog.getByRole('button', { name: 'Revoke Credits' }).click();
      const toast = page.getByText(/could be revoked\./).first();
      await toast.waitFor({ timeout: 15000 });
      const toastText = await toast.innerText();
      assert(
        /^Only .+ could be revoked\. .+ was missing from the balance\.$/.test(toastText.trim()),
        `PANEL-65: the shortfall toast (${toastText})`,
      );
      await modalsClosed(page);

      const account = must(
        await admin.get(`/api/panel/market/credits/accounts/${buyer.userId}`),
        'credit account',
      ).json;
      assertEqual(
        Number(account.balance ?? account.account?.balance),
        0,
        'PANEL-65: the balance is 0',
      );
      await waitFor('the ledger to show the REVOKE entry', async () =>
        (await bodyText(page)).includes('e2e browser revoke'),
      );
      pc.expectNoErrors('PANEL-65');
      await pc.close();
    },
  },
];
