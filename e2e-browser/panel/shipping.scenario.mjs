// Scenario 69 of 13 section 25.4: shipping in the panel (a zone and a method with overlapping weight ranges is refused, a valid method is saved, a manual
// shipment of part of an order makes the order PARTIAL).
import fs from 'node:fs';
import { must, PANEL_MARKET_API, listOf } from '../lib/api.mjs';
import { run } from '../lib/bootstrap.mjs';
import { assert, assertEqual } from '../lib/ui.mjs';
import { paidShippableOrder, physicalProduct, withOtherZonesOff } from './lib/shipping.mjs';
import { signedIn, openMarket, waitFor, modalsClosed } from './lib/panel.mjs';

const enUS = JSON.parse(
  fs.readFileSync(new URL('../../src/locales/panel/en-US.json', import.meta.url), 'utf8'),
);

export const scenarios = [
  {
    id: 'PANEL-69',
    title:
      'shipping: a zone is created, a method with overlapping weight ranges is refused and a valid one saved, a manual shipment of part of an order makes it PARTIAL',
    async run(ctx) {
      const { env, admin } = ctx;
      const label = `${run.tag}${run.next()}`;
      const zoneName = `E2E UI zone ${label}`;
      const methodName = `E2E UI method ${label}`;
      const { pc, page } = await signedIn(ctx.browser, admin);
      let zoneId = null;
      let methodId = null;

      try {
        await withOtherZonesOff(admin, [], async () => {
          // --- the zone: Germany only, created in the modal
          await openMarket(page, env, '/market/settings?section=shipping-zones', (p) =>
            p.getByText(/^\d+ Zones?$/).waitFor({ timeout: 60000 }),
          );
          await page.getByRole('button', { name: 'Actions' }).first().click();
          await page.getByRole('button', { name: 'Create Zone' }).click();
          const modal = page.locator('.modal.show');
          await modal.getByRole('heading', { name: 'Create Zone' }).waitFor({ timeout: 15000 });
          await modal.locator('#shippingZoneName').fill(zoneName);
          await modal.getByPlaceholder('Search countries').fill('Germany');
          await modal.locator('#shippingZoneCountry-DE').check();
          assertEqual(
            await modal.locator('.modal-footer button').count(),
            1,
            'PANEL-69: the zone form has exactly one footer button',
          );
          await modal.locator('.modal-footer button[type="submit"]').click();
          await page
            .getByText(enUS.modals['shipping-zone']['toast-created'])
            .first()
            .waitFor({ timeout: 15000 });
          await modalsClosed(page);
          const zones = listOf(
            must(await admin.get(`${PANEL_MARKET_API}/shipping/zones`), 'zones').json,
            'zones',
          );
          const zone = zones.find((z) => z.name === zoneName);
          assert(zone, 'PANEL-69: the zone is stored');
          zoneId = zone.id;
          assertEqual(
            JSON.stringify(zone.countries),
            JSON.stringify(['DE']),
            'PANEL-69: the zone covers DE',
          );

          // --- the method: overlapping weight ranges are refused on the block and nothing is sent
          await openMarket(page, env, '/market/settings/shipping-method', (p) =>
            p.locator('#shippingMethodName').waitFor({ timeout: 60000 }),
          );
          const posts = [];
          page.on('request', (r) => {
            if (r.method() === 'POST' && /\/market\/shipping\/methods$/.test(r.url()))
              posts.push(r.url());
          });
          await page.locator('#shippingMethodName').fill(methodName);
          await page.locator('#shippingMethodProvider').selectOption('manual');
          await page.getByLabel('Add zone').selectOption({ label: zoneName });
          await page.getByLabel('Basis').selectOption('WEIGHT');
          await page.locator(`#rate-${zoneId}-0-from`).fill('0');
          await page.locator(`#rate-${zoneId}-0-to`).fill('1999');
          await page.locator(`#rate-${zoneId}-0-price`).fill('4.90');
          await page.getByRole('button', { name: 'Add Row' }).click();
          await page.locator(`#rate-${zoneId}-1-from`).fill('1500');
          await page.locator(`#rate-${zoneId}-1-price`).fill('9.90');
          await page.getByRole('button', { name: 'Save', exact: true }).click();
          await page
            .getByText(enUS.pages['shipping-method'].errors.OVERLAP)
            .first()
            .waitFor({ timeout: 15000 });
          assert(
            ((await page.locator(`#rate-${zoneId}-1-from`).getAttribute('class')) ?? '').includes(
              'is-invalid',
            ),
            'PANEL-69: the overlapping row is marked',
          );
          assertEqual(posts.length, 0, 'PANEL-69: overlapping ranges send no request');
          assertEqual(
            listOf(
              must(await admin.get(`${PANEL_MARKET_API}/shipping/methods`), 'methods').json,
              'methods',
            ).filter((m) => m.name === methodName).length,
            0,
            'PANEL-69: nothing stored for the overlapping ranges',
          );

          // --- the valid method: the second range starts at 2000 and is open ended
          await page.locator(`#rate-${zoneId}-1-from`).fill('2000');
          await page.getByRole('button', { name: 'Save', exact: true }).click();
          await page
            .getByText(enUS.pages['shipping-method']['toast-created'])
            .first()
            .waitFor({ timeout: 15000 });
          await page.getByText(methodName).first().waitFor({ timeout: 60000 });
          const method = listOf(
            must(await admin.get(`${PANEL_MARKET_API}/shipping/methods`), 'methods').json,
            'methods',
          ).find((m) => m.name === methodName);
          assert(method, 'PANEL-69: the method is stored');
          methodId = method.id;
          const rates = (method.rates ?? []).filter((r) => r.zoneId === zoneId);
          assertEqual(rates.length, 2, 'PANEL-69: two weight rows');
          assertEqual(
            JSON.stringify(rates.map((r) => [r.rangeFrom, r.rangeTo ?? null])),
            JSON.stringify([
              [0, 1999],
              [2000, null],
            ]),
            'PANEL-69: the saved ranges',
          );

          // --- a paid order of 3 parcels, one manual shipment of 1 => PARTIAL
          const parcel = await physicalProduct(admin, 'Parcel 69');
          const order = await paidShippableOrder(ctx, parcel, 3, methodId, 'p69');
          assertEqual(
            order.detail.order.shippingStatus,
            'PENDING',
            'PANEL-69: nothing shipped yet',
          );

          // --- an active method exists, so GET /context reports shippingEnabled and the orders list shows its shipping status column (13 section 3.1)
          assertEqual(
            must(await admin.get(`${PANEL_MARKET_API}/context`), 'context').json.shippingEnabled,
            true,
            'PANEL-69: the context reports shippingEnabled with an active method',
          );
          await openMarket(page, env, '/market/orders', (p) =>
            p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
          );
          assertEqual(
            await page.locator('thead th', { hasText: enUS.pages.orders.table.shipping }).count(),
            1,
            'PANEL-69: the orders list shows the shipping status column',
          );

          await openMarket(page, env, `/market/orders/detail/${order.number}`, (p) =>
            p.getByText(`#${order.number}`).first().waitFor({ timeout: 60000 }),
          );
          const trigger = page
            .getByText(/^\d+ Shipments?$/)
            .first()
            .locator('xpath=ancestor::div[contains(@class, "card")][1]');
          await trigger.waitFor({ timeout: 30000 });
          await trigger.locator('button[data-bs-toggle="dropdown"]').first().click();
          await page
            .getByRole('button', { name: enUS.pages['order-detail'].actions['create-shipment'] })
            .click();
          const shipment = page.locator('.modal.show');
          await shipment
            .getByRole('heading', { name: 'Create Shipment' })
            .waitFor({ timeout: 30000 });
          await shipment.getByLabel('Quantity').fill('1');
          await shipment.getByLabel('Quantity').dispatchEvent('change');
          await shipment.getByPlaceholder('Weight (g)').first().fill('500');
          await shipment.locator('#create-shipment-provider').selectOption('manual');
          await shipment.getByPlaceholder('Carrier name').fill('E2E Post');
          await shipment.getByPlaceholder('Tracking number').fill(`E2E-${label}`);
          await shipment.getByRole('button', { name: 'Create Shipment' }).last().click();
          await page
            .getByText(enUS.modals['create-shipment'].toast)
            .first()
            .waitFor({ timeout: 30000 });
          await modalsClosed(page);

          await waitFor('the order to be PARTIAL', async () => {
            const view = must(
              await admin.get(`${PANEL_MARKET_API}/orders/${order.number}`),
              'order',
            ).json;
            return view.order.shippingStatus === 'PARTIAL';
          });
          const view = must(
            await admin.get(`${PANEL_MARKET_API}/orders/${order.number}`),
            'order',
          ).json;
          assertEqual(view.shipments.length, 1, 'PANEL-69: one shipment');
          assert(
            JSON.stringify(view).includes(`E2E-${label}`),
            'PANEL-69: the tracking number is stored on the shipment',
          );
        });
      } finally {
        if (methodId) await admin.delete(`${PANEL_MARKET_API}/shipping/methods/${methodId}`);
        if (zoneId) await admin.delete(`${PANEL_MARKET_API}/shipping/zones/${zoneId}`);
      }

      pc.expectNoErrors('PANEL-69');
      await pc.close();
    },
  },
];
