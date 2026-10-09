// Scenario 64 of 13 section 25.4: the payment methods settings (provider registry list, schema form, checkout rules, order).
// The isolated instance runs the real fake provider; where the scenario needs a state the instance cannot be in (only built-ins, an
// UNAVAILABLE plugin, a schema with visibleWhen / optional secrets / every field type) the panel's own GET /payment-providers answer is
// rewritten in the browser (page.route), the panel renders and posts exactly as it would for such a provider.
import { must, PANEL_MARKET_API, listOf } from '../lib/api.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { signedIn, openMarket, waitFor, modalsClosed, sleep } from './lib/panel.mjs';

const BUILT_INS = new Set(['bank-transfer', 'credits', 'free']);
const LIST = `**${PANEL_MARKET_API}/payment-providers`;

/** Rewrites the answer of the provider list with `edit(json)` for every request of the page. */
async function rewriteProviders(page, edit) {
  await page.route(LIST, async (route) => {
    const response = await route.fetch();
    const json = await response.json();
    edit(json);
    await route.fulfill({ response, json });
  });
}

async function openPayments(page, env, query = '') {
  await openMarket(page, env, `/market/settings?section=payments${query}`, (p) =>
    p.getByText(/^\d+ Payment Methods$/).waitFor({ timeout: 60000 }),
  );
}

/**
 * Opens the payment methods through a client-side navigation (another settings section, then the "Payment Methods" link): a hard load
 * fetches the list on the panel's server, where `page.route` cannot see it; the client-side load is the browser's own request.
 */
async function openPaymentsClientSide(page, env) {
  await openMarket(page, env, '/market/settings?section=general', (p) =>
    p.getByRole('link', { name: 'Payment Methods', exact: true }).waitFor({ timeout: 60000 }),
  );
  await page.getByRole('link', { name: 'Payment Methods', exact: true }).click();
  await page.getByText(/^\d+ Payment Methods$/).waitFor({ timeout: 60000 });
}

const cardOf = (page, id) => page.locator('.card', { has: page.locator(`#pm-toggle-${id}`) });

/** Ids of the provider cards in DOM order. */
const order = (page) =>
  page
    .locator('[id^="pm-toggle-"]')
    .evaluateAll((els) => els.map((e) => e.id.replace('pm-toggle-', '')));

async function openProvider(page, id) {
  await cardOf(page, id)
    .getByRole('button', { name: /^(Configure|View)$/ })
    .click();
  const modal = page.locator('.modal.show');
  await modal.getByRole('tab', { name: 'Settings' }).waitFor({ timeout: 15000 });
  return modal;
}

async function closeModal(page) {
  await page.locator('.modal.show').getByRole('button', { name: 'Close' }).first().click();
  await modalsClosed(page);
}

/** Counts the POSTs to /payment-methods/<id> (the save request) the page sends and records their bodies. */
function watchSaves(page, id) {
  const saves = [];
  page.on('request', (request) => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname.endsWith(`/payment-methods/${id}`)
    )
      saves.push(request.postDataJSON());
  });
  return saves;
}

export const scenarios = [
  {
    id: 'PANEL-64',
    title:
      'payment methods: no-plugins alert with built-ins only, every field type rendered, visibleWhen, required field blocks the save, secret reveal prompt and removal, fee validation, reorder persists, an UNAVAILABLE row is read-only',
    async run(ctx) {
      const { env, admin, gateway } = ctx;
      const { pc, page } = await signedIn(ctx.browser, admin);

      // --- 1. only built-ins => the "no plugins" alert (the instance itself has the fake provider, so it never shows without help)
      await page.route(LIST, async (route) => {
        const response = await route.fetch();
        const json = await response.json();
        const key = 'items' in json ? 'items' : 'providers';
        json[key] = json[key].filter((p) => BUILT_INS.has(p.id));
        await route.fulfill({ response, json });
      });
      await openPaymentsClientSide(page, env);
      await page.getByText('No Payment Plugins Installed').waitFor({ timeout: 15000 });
      await page.unroute(LIST);
      await openPayments(page, env);
      assertEqual(
        await page.getByText('No Payment Plugins Installed').count(),
        0,
        'PANEL-64: with the fake provider installed there is no "no plugins" alert',
      );

      // --- 2. the schema form of the fake provider: every field type it declares, as the control a person fills
      const modal = await openProvider(page, 'fake');
      assertEqual(
        await page.locator('#pm-field-gatewayUrl').getAttribute('type'),
        'url',
        'PANEL-64: URL field',
      );
      assertEqual(
        await page.locator('#pm-field-secret').getAttribute('type'),
        'password',
        'PANEL-64: PASSWORD field',
      );
      assertEqual(
        await page.locator('#pm-field-startKind').evaluate((e) => e.tagName),
        'SELECT',
        'PANEL-64: SELECT field',
      );
      assertEqual(
        await page.locator('#pm-field-statusQuery').getAttribute('type'),
        'checkbox',
        'PANEL-64: SWITCH field',
      );
      assertEqual(
        await page.locator('#pm-field-timeoutMs').getAttribute('inputmode'),
        'numeric',
        'PANEL-64: NUMBER field',
      );
      assert(
        (await page.locator('#pm-field-webhook').getAttribute('readonly')) !== null,
        'PANEL-64: READONLY (webhook URL) field',
      );
      await modal.getByRole('button', { name: 'Test connection' }).waitFor({ timeout: 5000 }); // the schema's action button
      await closeModal(page);

      // --- 3. a required field left empty marks it invalid and sends nothing
      const saves = watchSaves(page, 'fake');
      const form = await openProvider(page, 'fake');
      const url = page.locator('#pm-field-gatewayUrl');
      const keep = await url.inputValue();
      await url.fill('');
      await form.getByRole('button', { name: 'Save' }).click();
      await waitFor('the URL field to be marked', async () =>
        (await url.getAttribute('class')).includes('is-invalid'),
      );
      await sleep(600);
      assertEqual(saves.length, 0, 'PANEL-64: an empty required field sends no request');
      await url.fill(keep);

      // --- 4. the reveal prompt: a wrong password marks the prompt (the refused request is logged by the browser, expected once)
      const mark = pc.errors.length;
      await form.getByRole('button', { name: 'Show secret value' }).first().click();
      const prompt = page.locator('#pm-field-secret-reveal-password');
      await prompt.fill('definitely-not-the-password');
      await form.getByRole('button', { name: 'Show', exact: true }).click();
      await waitFor('the password prompt to be marked', async () =>
        (await prompt.getAttribute('class')).includes('is-invalid'),
      );
      const refused = pc.errors.slice(mark).filter((e) => /status of 4\d\d/.test(e));
      assertEqual(refused.length, 1, 'PANEL-64: the refused reveal logged one 4xx');
      pc.errors.splice(pc.errors.indexOf(refused[0]), 1);
      await closeModal(page);

      // --- 5. fee: "pass the fee to the buyer" without a percentage or a fixed fee is invalid, nothing is sent
      const rules = await openProvider(page, 'fake');
      await rules.getByRole('tab', { name: 'Checkout Rules' }).click();
      await rules.getByLabel('Pass The Fee To The Buyer').check();
      await rules.getByRole('button', { name: 'Save' }).click();
      await rules
        .getByText('Enter a percentage or a fixed fee, or turn the buyer fee off.')
        .first()
        .waitFor({ timeout: 15000 });
      await sleep(600);
      assertEqual(saves.length, 0, 'PANEL-64: a fee without values sends no request');
      await closeModal(page);

      // --- 6. reorder persists after a reload (move the fake-eur card up one place, then check the API and the reloaded page)
      await openPayments(page, env);
      const before = await order(page);
      const at = before.indexOf('fake-eur');
      assert(at > 0, `PANEL-64: fake-eur has a card to move up (${before})`);
      await cardOf(page, 'fake-eur').getByRole('button', { name: 'Actions' }).click();
      await cardOf(page, 'fake-eur').getByRole('button', { name: 'Move up' }).click();
      const moved = [...before];
      [moved[at - 1], moved[at]] = [moved[at], moved[at - 1]];
      await waitFor(
        'the new order on the page',
        async () => JSON.stringify(await order(page)) === JSON.stringify(moved),
      );
      await page.reload({ waitUntil: 'domcontentloaded' });
      await page.getByText(/^\d+ Payment Methods$/).waitFor({ timeout: 60000 });
      assertEqual(
        JSON.stringify(await order(page)),
        JSON.stringify(moved),
        'PANEL-64: the order survives a reload',
      );
      // and back, so the instance keeps its order for the other scenarios
      await cardOf(page, 'fake-eur').getByRole('button', { name: 'Actions' }).click();
      await cardOf(page, 'fake-eur').getByRole('button', { name: 'Move down' }).click();
      await waitFor(
        'the original order again',
        async () => JSON.stringify(await order(page)) === JSON.stringify(before),
      );
      await sleep(500);

      // --- 7. rewritten provider list: an UNAVAILABLE row is read-only; a schema with every other field type, visibleWhen and an optional secret
      const fresh = await signedIn(ctx.browser, admin);
      const page2 = fresh.page;
      await rewriteProviders(page2, (json) => {
        for (const provider of listOf(json, 'providers')) {
          if (provider.id === 'fake-eur') provider.state = 'UNAVAILABLE';
          if (provider.id !== 'fake') continue;
          const schema = provider.schema;
          schema.fields = schema.fields.map((f) =>
            f.key === 'secret' ? { ...f, required: false } : f,
          );
          const label = (text) => ({ default: text, translations: {} });
          schema.fields.push(
            {
              key: 'extraText',
              type: 'TEXT',
              label: label('Extra text'),
              required: false,
              secret: false,
            },
            {
              key: 'extraArea',
              type: 'TEXTAREA',
              label: label('Extra area'),
              required: false,
              secret: false,
            },
            {
              key: 'extraKey',
              type: 'SECRET_TEXTAREA',
              label: label('Extra key block'),
              required: false,
              secret: true,
            },
            {
              key: 'extraNotice',
              type: 'NOTICE',
              label: label('Extra notice'),
              noticeLevel: 'WARNING',
              required: false,
              secret: false,
            },
            {
              key: 'iframeOnly',
              type: 'TEXT',
              label: label('Only for iframes'),
              required: false,
              secret: false,
              visibleWhen: { field: 'startKind', anyOf: ['IFRAME'] },
            },
          );
        }
      });
      const saves2 = watchSaves(page2, 'fake');
      await openPaymentsClientSide(page2, env);

      // an UNAVAILABLE provider: the switch is off and disabled, the dialog is read-only
      const row = cardOf(page2, 'fake-eur');
      assert(
        await row.locator('#pm-toggle-fake-eur').isDisabled(),
        'PANEL-64: the UNAVAILABLE row has a disabled switch',
      );
      await row.getByRole('button', { name: 'View' }).click();
      const readOnly = page2.locator('.modal.show');
      await readOnly.getByText('Read Only').waitFor({ timeout: 15000 });
      assert(
        await readOnly.getByRole('button', { name: 'Save' }).isDisabled(),
        'PANEL-64: the UNAVAILABLE dialog cannot save',
      );
      assert(
        await page2.locator('#pm-field-gatewayUrl').isDisabled(),
        'PANEL-64: its fields are disabled',
      );
      await closeModal(page2);

      // every remaining field type renders; visibleWhen hides and shows
      const schemaModal = await openProvider(page2, 'fake');
      assertEqual(await page2.locator('#pm-field-extraText').count(), 1, 'PANEL-64: TEXT field');
      assertEqual(
        await page2.locator('#pm-field-extraArea').evaluate((e) => e.tagName),
        'TEXTAREA',
        'PANEL-64: TEXTAREA field',
      );
      assertEqual(
        await page2.locator('#pm-field-extraKey').evaluate((e) => e.tagName),
        'TEXTAREA',
        'PANEL-64: SECRET_TEXTAREA field',
      );
      await schemaModal.getByText('Extra notice').waitFor({ timeout: 5000 });
      assert(
        (await schemaModal.locator('.alert-warning', { hasText: 'Extra notice' }).count()) > 0,
        'PANEL-64: NOTICE (warning) field',
      );
      assertEqual(
        await page2.locator('#pm-field-iframeOnly').count(),
        0,
        'PANEL-64: visibleWhen hides the field for REDIRECT',
      );
      await page2.locator('#pm-field-startKind').selectOption('IFRAME');
      await page2.locator('#pm-field-iframeOnly').waitFor({ timeout: 5000 });
      await page2.locator('#pm-field-startKind').selectOption('REDIRECT');
      await waitFor(
        'the field to hide again',
        async () => (await page2.locator('#pm-field-iframeOnly').count()) === 0,
      );

      // removing the stored secret posts null (the secret is optional in this rewritten schema)
      await schemaModal.getByRole('button', { name: 'Remove Stored Value' }).first().click();
      await page2.locator('#pm-field-secret').waitFor();
      assertEqual(
        await page2.locator('#pm-field-secret').getAttribute('placeholder'),
        'Will be removed when you save',
        'PANEL-64: the removed secret says so',
      );
      const mark2 = fresh.pc.errors.length;
      await schemaModal.getByRole('button', { name: 'Save' }).click();
      await waitFor('the save request', async () => saves2.length > 0);
      assertEqual(saves2[0].settings.secret, null, 'PANEL-64: removing a secret posts null');
      // the real backend still requires the secret, so it refuses; the browser logs that 4xx (expected once) and the stored secret stays
      await waitFor('the refusal to be logged', async () => fresh.pc.errors.length > mark2);
      const refusal = fresh.pc.errors.slice(mark2).filter((e) => /status of 4\d\d/.test(e));
      assertEqual(refusal.length, 1, 'PANEL-64: the refused save logged one 4xx');
      fresh.pc.errors.splice(fresh.pc.errors.indexOf(refusal[0]), 1);
      const stored = listOf(
        must(await admin.get(`${PANEL_MARKET_API}/payment-providers`), 'providers').json,
        'providers',
      ).find((p) => p.id === 'fake');
      assertEqual(stored.settings.secret, '********', 'PANEL-64: the stored secret is untouched');
      assertEqual(
        stored.settings.gatewayUrl,
        gateway.baseUrl,
        'PANEL-64: the stored settings are untouched',
      );

      pc.expectNoErrors('PANEL-64');
      fresh.pc.expectNoErrors('PANEL-64 (rewritten list)');
      await pc.close();
      await fresh.pc.close();
    },
  },
];
