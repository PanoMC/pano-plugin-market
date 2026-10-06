// Shipping fixtures of the panel scenarios (69, 73), made through the HTTP API like ShippingE2E does.
import { must } from '../../lib/api.mjs';
import { grantUserNode, product } from '../../lib/bootstrap.mjs';
import { completePayment } from '../../lib/gateway.mjs';
import { awaitStatus, checkout } from './orders.mjs';
import { node } from './panel.mjs';

export const ADDRESS = {
  firstName: 'Ada',
  lastName: 'Lovelace',
  phone: '+4915112345678',
  country: 'DE',
  city: 'Berlin',
  line1: 'Unter den Linden 1',
  postalCode: '10117',
};

/**
 * Runs `block` while every ACTIVE zone except the ones in `keep` is switched off (the instance ships with the catch-all zone "Everywhere",
 * which would shadow the zone of a scenario) and puts them back afterwards.
 */
export async function withOtherZonesOff(admin, keep, block) {
  const zones = must(await admin.get('/api/panel/market/shipping/zones'), 'zones').json.zones ?? [];
  const off = zones.filter((z) => z.status === 'ACTIVE' && !keep.includes(z.id)).map((z) => z.id);

  for (const id of off)
    must(
      await admin.put(`/api/panel/market/shipping/zones/${id}`, { status: 'INACTIVE' }),
      'zone off',
    );

  try {
    return await block();
  } finally {
    for (const id of off)
      await admin.put(`/api/panel/market/shipping/zones/${id}`, { status: 'ACTIVE' });
  }
}

/** A zone for DE and one manual weight-rated method, made through the API; returns `{ zoneId, methodId, remove }`. */
export async function zoneAndMethod(admin, label) {
  const zoneId = must(
    await admin.post('/api/panel/market/shipping/zones', {
      name: `E2E zone ${label}`,
      countries: ['DE'],
      status: 'ACTIVE',
    }),
    'zone',
  ).json.id;
  const methodId = must(
    await admin.post('/api/panel/market/shipping/methods', {
      name: `E2E method ${label}`,
      providerId: 'manual',
      rateSource: 'RULES',
      status: 'ACTIVE',
      rates: [
        { zoneId, basis: 'WEIGHT', rangeFrom: 0, rangeTo: 1999, price: 4.9 },
        { zoneId, basis: 'WEIGHT', rangeFrom: 2000, price: 9.9 },
      ],
    }),
    'method',
  ).json.id;

  return {
    zoneId,
    methodId,
    async remove() {
      await admin.delete(`/api/panel/market/shipping/methods/${methodId}`);
      await admin.delete(`/api/panel/market/shipping/zones/${zoneId}`);
    },
  };
}

/** A physical product of 500 g with stock for `quantity` units. */
export const physicalProduct = (admin, name, stock = 10) =>
  product(admin, name, {
    price: '20.00',
    physical: 'true',
    weightGrams: '500',
    stock: String(stock),
  });

/** A paid order of `quantity` units of a physical product shipped to Berlin with `methodId`; returns `{ api, publicId, number, id }`. */
export async function paidShippableOrder(
  { admin, buyer },
  item,
  quantity,
  methodId,
  label = 'ship',
) {
  const api = await buyer(label);
  await grantUserNode(admin, api.userId, node('manage.market.payments'));
  const placed = await checkout(api, {
    items: [{ productId: item.id, quantity }],
    paymentMethodId: 'fake',
    shippingAddress: ADDRESS,
    shippingMethodId: methodId,
  });
  await completePayment(placed.payUrl);
  const order = await awaitStatus(api, placed.publicId, 'COMPLETED');
  const detail = must(await admin.get(`/api/panel/market/orders/${order.number}`), 'order').json;

  return { api, publicId: placed.publicId, number: order.number, id: detail.order.id, detail };
}
