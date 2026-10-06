// Shipping set-up of the order page scenarios (a zone, one manual method). Nothing here is a scenario: the runner skips every `lib` directory.
import { must } from '../../lib/api.mjs';

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
const unique = () => Date.now().toString(36).slice(-6) + Math.floor(Math.random() * 99);

/**
 * A zone for DE with one weight-priced manual method ("E2E Post", 0 to 1999 g = 4.90) and every other zone switched off for the length of `fn`
 * (restored afterwards, the zone and the method deleted). `fn` gets `{ zoneId, methodId, n }`.
 */
export async function withShipping(admin, fn) {
  const n = unique();
  const zones = must(await admin.get('/api/panel/market/shipping/zones'), 'zones').json.zones;
  const others = zones.filter((z) => z.status === 'ACTIVE').map((z) => z.id);

  for (const id of others)
    must(
      await admin.put(`/api/panel/market/shipping/zones/${id}`, { status: 'INACTIVE' }),
      'zone off',
    );

  try {
    const zoneId = must(
      await admin.post('/api/panel/market/shipping/zones', {
        name: `E2E zone ${n}`,
        countries: ['DE'],
        status: 'ACTIVE',
      }),
      'zone',
    ).json.id;
    let methodId = null;

    try {
      methodId = must(
        await admin.post('/api/panel/market/shipping/methods', {
          name: `E2E Post ${n}`,
          providerId: 'manual',
          rateSource: 'RULES',
          status: 'ACTIVE',
          rates: [{ zoneId, basis: 'WEIGHT', rangeFrom: 0, rangeTo: 1999, price: 4.9 }],
        }),
        'method',
      ).json.id;
      await sleep(1000);

      return await fn({ zoneId, methodId, n });
    } finally {
      if (methodId !== null)
        await admin.request('DELETE', `/api/panel/market/shipping/methods/${methodId}`);
      await admin.request('DELETE', `/api/panel/market/shipping/zones/${zoneId}`);
    }
  } finally {
    for (const id of others)
      await admin.put(`/api/panel/market/shipping/zones/${id}`, { status: 'ACTIVE' });
  }
}

/** The plain German address of the shipping scenarios (the wire shape of the checkout body). */
export const germanAddress = () => ({
  firstName: 'Ada',
  lastName: 'Lovelace',
  phone: '+4915112345678',
  country: 'DE',
  city: 'Berlin',
  line1: 'Unter den Linden 1',
  postalCode: '10117',
});
