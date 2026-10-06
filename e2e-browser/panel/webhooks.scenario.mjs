// Scenario 68 of 13 section 25.4: store webhooks in the panel (HMAC with a generated secret shown once, a test ping to a local listener and its delivery row,
// a Discord endpoint with a non-Discord URL, a private target address).
import fs from 'node:fs';
import http from 'node:http';
import { must } from '../lib/api.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import {
  signedIn,
  openMarket,
  waitFor,
  modalsClosed,
  settingsPatch,
  provoked,
} from './lib/panel.mjs';

const enUS = JSON.parse(
  fs.readFileSync(new URL('../../src/locales/panel/en-US.json', import.meta.url), 'utf8'),
);

/** A local HTTP listener that answers 200 and keeps what it received. */
async function listener() {
  const received = [];
  const server = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    received.push({
      method: req.method,
      headers: req.headers,
      body: Buffer.concat(chunks).toString('utf8'),
    });
    res.writeHead(200, { 'Content-Type': 'text/plain' });
    res.end('ok');
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));

  return {
    url: `http://127.0.0.1:${server.address().port}/hook`,
    received,
    close: () => new Promise((resolve) => server.close(() => resolve())),
  };
}

export const scenarios = [
  {
    id: 'PANEL-68',
    title:
      'webhooks: HMAC with an empty secret shows the generated secret once, the test ping reaches a local listener and leaves a delivery row, a Discord format needs a Discord URL, a private target is refused',
    async run(ctx) {
      const { env, admin } = ctx;
      const sink = await listener();
      const name = `E2E hook ${Date.now().toString(36).slice(-5)}`;
      const { pc, page } = await signedIn(ctx.browser, admin);
      const hooksOf = async () =>
        must(await admin.get('/api/panel/market/webhooks'), 'webhooks').json.webhooks ?? [];
      let restore = null;

      try {
        await openMarket(page, env, '/market/settings?section=webhooks', (p) =>
          p.getByText(/^\d+ Webhooks?$/).waitFor({ timeout: 60000 }),
        );
        const openCreate = async () => {
          await page.getByRole('button', { name: 'Actions' }).first().click();
          await page.getByRole('button', { name: 'Create Webhook' }).click();
          const modal = page.locator('.modal.show');
          await modal.getByRole('heading', { name: 'Create Webhook' }).waitFor({ timeout: 15000 });
          return modal;
        };

        // --- 1. a Discord endpoint with a non-Discord URL is refused on the field, nothing is sent
        const created = [];
        page.on('request', (r) => {
          if (r.method() === 'POST' && /\/market\/webhooks$/.test(r.url())) created.push(r.url());
        });
        let modal = await openCreate();
        await modal.locator('#webhookNameInput').fill(name);
        await modal.getByText(enUS.enums['webhook-format'].DISCORD).click();
        await modal.locator('#webhookUrlInput').fill('https://example.com/not-discord');
        await modal.getByRole('button', { name: 'Create', exact: true }).click();
        await modal
          .getByText(enUS.settings.webhooks['invalid-discord-url'])
          .waitFor({ timeout: 15000 });
        assert(
          ((await modal.locator('#webhookUrlInput').getAttribute('class')) ?? '').includes(
            'is-invalid',
          ),
          'PANEL-68: the URL input is marked invalid for a Discord endpoint',
        );
        assertEqual(created.length, 0, 'PANEL-68: a refused Discord URL sends no request');
        assertEqual(
          (await hooksOf()).filter((h) => h.name === name).length,
          0,
          'PANEL-68: nothing was stored for the refused Discord URL',
        );

        // --- 2. back to JSON, HMAC SHA-256 with an empty secret: created, the generated secret is shown once
        await modal.getByText(enUS.enums['webhook-format'].JSON, { exact: true }).click();
        await modal.locator('#webhookUrlInput').fill(sink.url);
        await modal.locator('#webhookSigning').selectOption('HMAC_SHA256');
        await modal.getByRole('button', { name: 'Create', exact: true }).click();
        const secretModal = page
          .locator('.modal.show')
          .filter({ hasText: enUS.modals['webhook-secret'].title });
        await secretModal.waitFor({ timeout: 20000 });
        const secret = await secretModal.locator('input[readonly]').inputValue();
        assert(
          secret.length >= 16,
          `PANEL-68: a generated secret of at least 16 characters (${secret.length})`,
        );
        await secretModal.getByRole('button', { name: enUS.modals['webhook-secret'].done }).click();
        await modalsClosed(page);
        const stored = (await hooksOf()).find((h) => h.name === name);
        assert(stored, 'PANEL-68: the webhook is stored');
        assertEqual(stored.signing, 'HMAC_SHA256', 'PANEL-68: the signing is HMAC_SHA256');
        assert(
          stored.secret !== secret && !JSON.stringify(stored).includes(secret),
          'PANEL-68: the API never returns the secret again (masked)',
        );
        // opening the edit form does not show the secret again
        const row = page.locator('table tbody tr').filter({ hasText: name });
        await row.waitFor({ timeout: 30000 });
        await row.locator('button[data-bs-toggle="dropdown"]').click();
        await row.getByRole('button', { name: 'Edit' }).click();
        const edit = page.locator('.modal.show');
        await edit.getByRole('heading', { name: 'Edit Webhook' }).waitFor({ timeout: 15000 });
        const editHtml = await edit.innerHTML();
        assert(!editHtml.includes(secret), 'PANEL-68: the edit form does not contain the secret');
        await edit.getByRole('button', { name: 'Close' }).click();
        await modalsClosed(page);

        // --- 3. the test ping reaches the local listener: success toast and a delivery row
        await row.locator('button[data-bs-toggle="dropdown"]').click();
        await row.getByRole('button', { name: 'Send Test' }).click();
        await page
          .getByText(/Test delivered: HTTP 200/)
          .first()
          .waitFor({ timeout: 30000 });
        await waitFor('the listener to receive the ping', async () => sink.received.length >= 1);
        assertEqual(sink.received.length, 1, 'PANEL-68: the listener got exactly one request');
        assertEqual(sink.received[0].method, 'POST', 'PANEL-68: the ping is a POST');
        assert(
          Object.keys(sink.received[0].headers).some((h) => /signature/i.test(h)),
          `PANEL-68: the ping is signed (headers ${Object.keys(sink.received[0].headers).join(', ')})`,
        );
        assert(sink.received[0].body.length > 2, 'PANEL-68: the ping carries a body');

        await row.locator('button[data-bs-toggle="dropdown"]').click();
        await row.getByRole('link', { name: 'Deliveries' }).click();
        await page.getByText(/\d+ Deliveries/).waitFor({ timeout: 60000 });
        const delivery = page.locator('table tbody tr').first();
        await delivery.waitFor({ timeout: 30000 });
        assert(
          (await delivery.innerText()).includes('200'),
          `PANEL-68: the delivery row shows HTTP 200 (${(await delivery.innerText()).replace(/\s+/g, ' ')})`,
        );

        // --- 4. a private target address is refused (INVALID_WEBHOOK_URL) once private targets are not allowed
        restore = await settingsPatch(admin, { allowPrivateWebhookTargets: false });
        await openMarket(page, env, '/market/settings?section=webhooks', (p) =>
          p.getByText(/^\d+ Webhooks?$/).waitFor({ timeout: 60000 }),
        );
        modal = await openCreate();
        await modal.locator('#webhookNameInput').fill(`${name} private`);
        await modal.locator('#webhookUrlInput').fill(sink.url);
        const mark = pc.errors.length;
        await modal.getByRole('button', { name: 'Create', exact: true }).click();
        await waitFor('the URL field to be marked', async () =>
          ((await modal.locator('#webhookUrlInput').getAttribute('class')) ?? '').includes(
            'is-invalid',
          ),
        );
        assertEqual(
          (await modal.locator('.invalid-feedback').first().innerText()).trim(),
          enUS.modals.webhook.errors.URL_PRIVATE_ADDRESS,
          'PANEL-68: the field says private addresses are not allowed',
        );
        await waitFor('the refusal to be logged', async () => pc.errors.length > mark);
        provoked(pc, mark, /status of 4\d\d/, 'PANEL-68 private URL');
        assertEqual(
          (await hooksOf()).filter((h) => h.name === `${name} private`).length,
          0,
          'PANEL-68: the private target was not stored',
        );
        await modal.getByRole('button', { name: 'Close' }).click();
        await modalsClosed(page);
      } finally {
        if (restore) await restore();
        for (const hook of await hooksOf())
          if (hook.name.startsWith(name))
            await admin.delete(`/api/panel/market/webhooks/${hook.id}`);
        await sink.close();
      }

      pc.expectNoErrors('PANEL-68');
      await pc.close();
    },
  },
];
