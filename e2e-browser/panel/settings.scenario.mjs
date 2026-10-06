// Scenario 71 of 13 section 25.4: store settings in the panel (a legal text is published as version N+1 and the previous one loses "Active",
// "require acceptance" without a text is blocked, the currency mode MULTI puts the per-currency grid into the product form).
import fs from 'node:fs';
import { must } from '../lib/api.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { signedIn, openMarket, waitFor, modalsClosed, settingsPatch } from './lib/panel.mjs';

const enUS = JSON.parse(
  fs.readFileSync(new URL('../../src/locales/panel/en-US.json', import.meta.url), 'utf8'),
);

const textsOf = async (admin) =>
  must(await admin.get('/api/panel/market/settings/legal'), 'legal texts').json.texts ?? [];

export const scenarios = [
  {
    id: 'PANEL-71',
    title:
      'settings: requiring a legal text without one is blocked, publishing creates version N+1 and the previous version loses Active, currency mode MULTI shows the per-currency grid in the product form',
    async run(ctx) {
      const { env, admin } = ctx;
      const { pc, page } = await signedIn(ctx.browser, admin);
      const restore = await settingsPatch(admin, {
        legalTextRequired: false,
        currencyMode: 'SINGLE',
      });
      let restoreCurrencies = null;

      try {
        // --- legal: "require acceptance" is blocked while no locale has an active text
        const before = await textsOf(admin);
        await openMarket(page, env, '/market/settings?section=legal', (p) =>
          p.locator('#legal-title').waitFor({ timeout: 60000 }),
        );
        const required = page.locator('#setting-legalTextRequired');
        const saveRequired = page
          .locator('.card')
          .filter({ has: required })
          .getByRole('button', { name: 'Save' });
        if (!before.some((t) => t.active)) {
          await required.check();
          await page
            .getByText(enUS.settings.errors?.NO_ACTIVE_TEXT ?? 'Publish a legal text first.')
            .first()
            .waitFor({ timeout: 15000 });
          assert(
            ((await required.getAttribute('class')) ?? '').includes('is-invalid'),
            'PANEL-71: the switch is marked invalid',
          );
          assert(
            await saveRequired.isDisabled(),
            'PANEL-71: Save is disabled while no text is active',
          );
          await required.uncheck();
        } else {
          console.log(
            'PANEL-71: the block check of legalTextRequired needs a store without any active text (this instance has one from an earlier run)',
          );
        }

        // --- publish version N+1 twice: the latest is Active, the one before is not
        const publish = async (title, content) => {
          await page.locator('#legal-title').fill(title);
          const editor = page.locator('.ProseMirror').first();
          await editor.click();
          await page.keyboard.press('Control+A');
          await page.keyboard.type(content);
          await page.getByRole('button', { name: enUS.settings.legal.publish }).click();
          const confirm = page.locator('.modal.show');
          await confirm
            .getByRole('heading', { name: enUS.settings.legal['confirm-publish'].title })
            .waitFor({ timeout: 15000 });
          assertEqual(
            await confirm.locator('.modal-header').count(),
            0,
            'PANEL-71: the confirmation has no modal header',
          );
          await confirm.getByRole('button', { name: enUS.settings.legal.publish }).click();
          await page
            .getByText(enUS.settings.legal['toast-published'])
            .first()
            .waitFor({ timeout: 20000 });
          await modalsClosed(page);
        };
        const stamp = Date.now().toString(36).slice(-5);
        await publish(`Terms ${stamp} one`, `First text ${stamp}`);
        await publish(`Terms ${stamp} two`, `Second text ${stamp}`);

        const after = await textsOf(admin);
        const mine = after.filter((t) => t.title.startsWith(`Terms ${stamp}`));
        assertEqual(mine.length, 2, 'PANEL-71: both versions are stored');
        const [older, newer] = [...mine].sort((a, b) => a.version - b.version);
        assertEqual(newer.version, older.version + 1, 'PANEL-71: the second text is version N+1');
        assert(newer.active && !older.active, 'PANEL-71: only the newer version is active (API)');
        const rowOf = (version) =>
          page
            .locator('table tbody tr')
            .filter({ has: page.locator('td', { hasText: new RegExp(`^${version}$`) }) })
            .filter({ hasText: `Terms ${stamp}` });
        await rowOf(newer.version).waitFor({ timeout: 30000 });
        assert(
          (await rowOf(newer.version).innerText()).includes('Active'),
          'PANEL-71: the newer version shows the Active badge',
        );
        assert(
          !(await rowOf(older.version).innerText()).includes('Active'),
          'PANEL-71: the previous version lost the Active badge',
        );

        // --- with an active text the switch can be saved
        await required.check();
        assert(await saveRequired.isEnabled(), 'PANEL-71: Save is enabled once a text is active');
        await saveRequired.click();
        await page.getByText(enUS.settings['toast-saved']).first().waitFor({ timeout: 15000 });
        await waitFor(
          'legalTextRequired to be stored',
          async () =>
            must(await admin.get('/api/panel/market/settings'), 'settings').json.settings
              ?.legalTextRequired === true ||
            must(await admin.get('/api/panel/market/settings'), 'settings').json
              .legalTextRequired === true,
        );

        // --- currencies: SINGLE has no per-currency grid in the product form, MULTI has one
        const openPricing = async () => {
          await openMarket(page, env, '/market/products/create-product', (p) =>
            p.getByRole('tab', { name: /^Pricing/ }).waitFor({ timeout: 60000 }),
          );
          await page.getByRole('tab', { name: /^Pricing/ }).click();
        };
        await openPricing();
        await page
          .locator('#product-price')
          .waitFor({ timeout: 15000 })
          .catch(() => {});
        assertEqual(
          await page.getByText(enUS.pages['create-product']['currency-prices']).count(),
          0,
          'PANEL-71: no per-currency grid in SINGLE mode',
        );

        await openMarket(page, env, '/market/settings?section=currencies', (p) =>
          p.locator('#setting-currencyMode-MULTI').waitFor({ timeout: 60000 }),
        );
        await page.locator('#setting-currencyMode-MULTI').check();
        await page.getByLabel(enUS.settings.currencies.add).selectOption('USD');
        await page
          .locator('table tbody tr')
          .filter({ hasText: 'USD' })
          .locator('select')
          .selectOption('MANUAL');
        await page.locator('#setting-rate-USD').fill('1.10');
        await page.getByRole('button', { name: 'Save', exact: true }).click();
        await page.getByText(enUS.settings['toast-saved']).first().waitFor({ timeout: 20000 });
        restoreCurrencies = true;

        await openPricing();
        await page
          .getByText(enUS.pages['create-product']['currency-prices'])
          .first()
          .waitFor({ timeout: 30000 });
        assert(
          (await page.locator('body').innerText()).includes('USD'),
          'PANEL-71: the grid has a USD row next to the store currency',
        );
      } finally {
        await restore();
        if (restoreCurrencies)
          await admin.post('/api/panel/market/settings', { currencyMode: 'SINGLE' });
      }

      pc.expectNoErrors('PANEL-71');
      await pc.close();
    },
  },
];
