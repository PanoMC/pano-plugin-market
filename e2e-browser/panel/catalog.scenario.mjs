// Scenarios 62 and 63 of 13 section 25.4: the product editor and the stock dialog.
import crypto from 'node:crypto';
import { must } from '../lib/api.mjs';
import { product } from '../lib/bootstrap.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { signedIn, openMarket, waitFor, bodyText, modalsClosed } from './lib/panel.mjs';
import { grantedServer, removeServer } from './lib/servers.mjs';

const MASK = '********';

async function productByName(admin, name) {
  const found = must(
    await admin.get(`/api/panel/market/products?search=${encodeURIComponent(name)}`),
    'find the product',
  ).json;
  return (found.products ?? [])[0] ?? null;
}

/** The product detail as the editor loads it. */
async function detailOf(admin, id) {
  const body = must(await admin.get(`/api/panel/market/products/${id}`), 'product detail').json;
  return body.product ?? body;
}

async function openTab(page, name) {
  // an invalid tab carries a bullet and a hidden hint after its name, so the name is matched as a prefix
  await page.getByRole('tab', { name: new RegExp(`^${name}`) }).click();
}

export const scenarios = [
  {
    id: 'PANEL-62',
    title:
      'product form: 2 axes (4 variants), a custom field, a COMMAND action with BUYER_CHOICE and a WEBHOOK action round-trip; action ids survive a second save, the webhook secret is masked, the reserved slug is rejected on the field, tab switches keep input',
    async run(ctx) {
      const { env, admin } = ctx;
      const server = await grantedServer(env, admin);
      const { pc, page } = await signedIn(ctx.browser, admin);
      const name = `Form Product ${crypto.randomUUID().slice(0, 6)}`;
      const slug = `e2eb-form-${crypto.randomUUID().slice(0, 8)}`;

      try {
        await openMarket(page, env, '/market/products/create-product', (p) =>
          p.locator('#product-name').waitFor({ timeout: 60000 }),
        );
        await page.locator('#product-name').fill(name);

        // the reserved slug is refused on the slug field itself
        await page.locator('#product-slug').fill('checkout');
        await page.getByRole('button', { name: 'Save' }).click();
        await page.locator('#product-slug.is-invalid').waitFor({ timeout: 15000 });
        assert(
          (await page
            .locator('#product-slug ~ .invalid-feedback, .invalid-feedback:near(#product-slug)')
            .count()) > 0,
          'PANEL-62: the reserved slug shows an error message next to the field',
        );
        await page.locator('#product-slug').fill(slug);

        // switching tabs keeps unsaved input (the general tab stays mounted, the others hold their state in the product)
        await openTab(page, 'Pricing');
        await page.locator('#product-price').fill('12.50');
        await openTab(page, 'Variants');
        await openTab(page, 'Pricing');
        assertEqual(
          Number(await page.locator('#product-price').inputValue()),
          12.5,
          'PANEL-62: the price survives a tab switch',
        );
        await openTab(page, 'General');
        assertEqual(
          await page.locator('#product-name').inputValue(),
          name,
          'PANEL-62: the name survives a tab switch',
        );
        assertEqual(
          await page.locator('#product-slug').inputValue(),
          slug,
          'PANEL-62: the slug survives a tab switch',
        );

        // two axes, four variants
        await openTab(page, 'Variants');
        await page.locator('#product-has-variants').check();
        for (const [axis, label, values] of [
          [0, 'Size', ['Small', 'Large']],
          [1, 'Color', ['Red', 'Blue']],
        ]) {
          await page.getByRole('button', { name: 'Actions' }).first().click();
          await page.getByRole('button', { name: 'Add Option' }).click();
          await page.getByLabel('Option name, e.g. Size').nth(axis).fill(label);
          for (const [i, value] of values.entries()) {
            await page.getByRole('button', { name: 'Add Value' }).nth(axis).click();
            await page
              .getByLabel('Value, e.g. Large')
              .nth(axis * 2 + i)
              .fill(value);
          }
        }
        await page.getByRole('button', { name: 'Generate Variants' }).click();
        await page.getByText(/4 added/).waitFor({ timeout: 15000 });

        // one custom field
        await openTab(page, 'Custom Fields');
        await page.getByRole('button', { name: 'Actions' }).first().click();
        await page.getByRole('button', { name: 'Add Field' }).click();
        const fieldModal = page.locator('.modal.show');
        await fieldModal.getByLabel('Label', { exact: true }).fill('Engraving');
        await fieldModal.getByLabel('Key, e.g. rank').fill('engraving');
        await fieldModal.getByRole('button', { name: 'Save' }).click();
        await modalsClosed(page);
        await page.getByText('1 Custom Fields').waitFor({ timeout: 15000 });

        // actions: a COMMAND for the buyer's choice of server and a signed WEBHOOK
        await openTab(page, 'Actions');
        await page.getByLabel(server.name, { exact: false }).first().check();
        await addAction(page, 'Command');
        await page
          .getByPlaceholder('Command without the leading slash')
          .first()
          .fill('give {username} diamond 1');
        // the radio is a visually hidden btn-check: its label is what a person clicks
        await page.locator('label[for$="-mode-BUYER_CHOICE"]').first().click();
        await addAction(page, 'Webhook');
        await page.getByPlaceholder('https://example.com/hook').fill('https://example.com/hook');
        await page.getByLabel('Signing').selectOption('HMAC_SHA256');
        await page.getByRole('button', { name: 'Generate' }).click();
        const clearSecret = await page.getByPlaceholder('Leave empty to generate one').inputValue();
        assert(clearSecret.length >= 16, 'PANEL-62: a secret was generated for the webhook');

        await page.getByRole('button', { name: 'Save' }).click();
        const created = await waitFor('the product to exist', () => productByName(admin, name), {
          timeout: 30000,
        });

        // reload: everything round-trips
        const saved = await detailOf(admin, created.id);
        assertEqual((saved.variants ?? []).length, 4, 'PANEL-62: four variants saved');
        assertEqual((saved.variantOptions ?? []).length, 2, 'PANEL-62: two axes saved');
        assertEqual((saved.fields ?? []).length, 1, 'PANEL-62: one custom field saved');
        assertEqual((saved.actions ?? []).length, 2, 'PANEL-62: two actions saved');
        const command = saved.actions.find((a) => a.type === 'COMMAND');
        const webhook = saved.actions.find((a) => a.type === 'WEBHOOK');
        assertEqual(
          command?.serverMode,
          'BUYER_CHOICE',
          "PANEL-62: the command targets the buyer's choice",
        );
        assertEqual(
          webhook?.value?.secret,
          MASK,
          'PANEL-62: the API returns the webhook secret masked',
        );
        assert(
          !JSON.stringify(saved).includes(clearSecret),
          'PANEL-62: the API never returns the webhook secret in clear',
        );

        await page.reload({ waitUntil: 'domcontentloaded' });

        await page.locator('#product-name').waitFor({ timeout: 60000 });
        assertEqual(
          await page.locator('#product-name').inputValue(),
          name,
          'PANEL-62: the name after the reload',
        );
        await openTab(page, 'Variants');
        await page.getByText('4 Variants').waitFor({ timeout: 15000 });
        await openTab(page, 'Custom Fields');
        await page.getByText('1 Custom Fields').waitFor({ timeout: 15000 });
        await openTab(page, 'Actions');
        await page.getByText('2 Actions').waitFor({ timeout: 15000 });
        const shown = await page.getByPlaceholder('Leave empty to generate one').inputValue();
        assertEqual(shown, MASK, 'PANEL-62: the editor shows the stored secret masked');
        await page.screenshot({ path: 'build/e2e-browser/PANEL-62-actions.png' }).catch(() => {});

        // a second save: the ids of the actions stay
        await openTab(page, 'General');
        await page.locator('#product-short-description').fill('saved twice');
        await page.getByRole('button', { name: 'Save' }).click();
        await waitFor(
          'the second save',
          async () => (await detailOf(admin, created.id)).shortDescription === 'saved twice',
          {
            timeout: 30000,
          },
        );
        const again = await detailOf(admin, created.id);
        assertEqual(
          JSON.stringify(again.actions.map((a) => a.id)),
          JSON.stringify(saved.actions.map((a) => a.id)),
          'PANEL-62: the action ids are unchanged after a second save',
        );

        pc.expectNoErrors('PANEL-62');
      } finally {
        await pc.close();
        await removeServer(env, admin, server).catch(() => {});
      }
    },
  },

  {
    id: 'PANEL-63',
    title:
      'stock: ADJUST -1 on a stock of 0 marks the input invalid, SET unlimited shows "Unlimited"',
    async run(ctx) {
      const { env, admin } = ctx;
      const stocked = await product(admin, 'Stock Probe', { price: '2.00', stock: 0 });
      const { pc, page } = await signedIn(ctx.browser, admin);

      await openMarket(page, env, `/market/products/create-product?id=${stocked.id}`, (p) =>
        p.locator('#product-name').waitFor({ timeout: 60000 }),
      );
      await openTab(page, 'Pricing');
      await page.getByRole('button', { name: 'Adjust Stock' }).click();
      const modal = page.locator('.modal.show');
      await modal.getByRole('heading', { name: 'Adjust Stock' }).waitFor({ timeout: 15000 });

      // take one off an empty stock: the server refuses, the quantity input is marked invalid, the dialog stays open
      await modal.locator('label[for="stock-mode-ADJUST"]').click();
      const quantity = modal.getByLabel('Quantity');
      await quantity.fill('-1');
      const mark = pc.errors.length;
      await modal.getByRole('button', { name: 'Save' }).click();
      await waitFor('the quantity input to be marked invalid', async () =>
        (await quantity.getAttribute('class')).includes('is-invalid'),
      );
      assertEqual(
        await modal.isVisible(),
        true,
        'PANEL-63: the dialog stays open after the refusal',
      );
      // the refused request logs its status as a browser console error: expected exactly once
      const refused = pc.errors.slice(mark).filter((e) => /status of 4\d\d/.test(e));
      assertEqual(refused.length, 1, 'PANEL-63: the refused adjustment logged one 4xx');
      pc.errors.splice(pc.errors.indexOf(refused[0]), 1);
      assertEqual(
        (await detailOf(admin, stocked.id)).stock,
        0,
        'PANEL-63: the stock of the product is still 0',
      );

      // SET to unlimited: the page reads "Unlimited" and the API holds no stock limit
      await modal.locator('label[for="stock-mode-SET"]').click();
      await modal.locator('#stock-unlimited').check();
      await modal.getByRole('button', { name: 'Save' }).click();
      await modalsClosed(page);
      await page.getByText('Unlimited', { exact: true }).first().waitFor({ timeout: 15000 });
      const after = await detailOf(admin, stocked.id);
      assertEqual(after.stock ?? null, null, 'PANEL-63: the product has unlimited stock');

      pc.expectNoErrors('PANEL-63');
      await pc.close();
    },
  },
];

async function addAction(page, type) {
  await page.getByRole('button', { name: 'Actions' }).first().click();
  await page.getByRole('button', { name: 'Add Action' }).click();
  await page
    .locator('.modal.show')
    .getByRole('button', { name: new RegExp(`^${type}`) })
    .click();
  await modalsClosed(page);
}
