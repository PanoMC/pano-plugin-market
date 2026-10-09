// Scenario 68 of 13 section 25.4, after MK-15: the webhook screens moved to the core panel (`/panel/settings/webhooks`); Market only registers
// its events there. What stays testable from this plugin: the core screen opens for the admin with the Market source preselected
// (`?source=market`), shows the Endpoints and Deliveries tabs and leaves no console error and no raw i18n key. The HMAC secret, the test ping
// and the private-target refusal are tests of the core webhook system (platform tests), not of this plugin.
import { assert, panelOpen, rawKeys } from '../lib/ui.mjs';
import { signedIn } from './lib/panel.mjs';

export const scenarios = [
  {
    id: 'PANEL-68',
    title:
      'webhooks: the core screen /settings/webhooks?source=market opens for the admin with its Endpoints and Deliveries tabs, no console error, no raw i18n key',
    async run(ctx) {
      const { env, admin } = ctx;
      const { pc, page } = await signedIn(ctx.browser, admin);

      try {
        await panelOpen(page, env, '/settings/webhooks?source=market', (p) =>
          p.getByText('Endpoints', { exact: true }).first().waitFor({ timeout: 60000 }),
        );

        const text = await page.locator('body').innerText();
        assert(text.includes('Deliveries'), 'PANEL-68: the Deliveries tab is on the core screen');
        assert(
          !text.includes('Enderman blocked this page from loading.'),
          'PANEL-68: the core webhook screen is not a 404',
        );
        const keys = await rawKeys(page);
        assert(keys.length === 0, `PANEL-68: no raw i18n key: ${JSON.stringify(keys)}`);
        pc.expectNoErrors('PANEL-68');
      } finally {
        await pc.close();
      }
    },
  },
];
