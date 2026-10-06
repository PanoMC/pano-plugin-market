// Orders for the panel scenarios, made through the storefront API like a buyer would (no SQL).
import crypto from 'node:crypto';
import { must } from '../../lib/api.mjs';
import { completePayment } from '../../lib/gateway.mjs';
import { grantCredits, grantUserNode } from '../../lib/bootstrap.mjs';
import { sleep } from './panel.mjs';

const PAY_NODE = 'pano.plugin.pano-plugin-market.manage.market.payments';
export const idem = () => ({ 'Idempotency-Key': crypto.randomUUID() });

const payUrlOf = (json) => json.payment?.url ?? json.payment?.payUrl ?? json.payment?.redirectUrl;

/** Polls the buyer's view of an order until it has `status`. */
export async function awaitStatus(api, publicId, status, tries = 60) {
  let last;
  for (let i = 0; i < tries; i++) {
    last = (await api.get(`/api/market/orders/${publicId}`)).json?.order;
    if (last?.status === status) return last;
    await sleep(500);
  }
  throw new Error(`the order ${publicId} did not reach ${status} (is ${last?.status})`);
}

/** A checkout through the storefront API; returns `{ publicId, number, payUrl, json }`. */
export async function checkout(api, body) {
  const res = must(await api.post('/api/market/checkout', body, idem()), 'checkout');
  const view = (await api.get(`/api/market/orders/${res.json.order.publicId}`)).json?.order;

  return {
    publicId: res.json.order.publicId,
    number: view?.number ?? res.json.order.number,
    payUrl: payUrlOf(res.json),
    json: res.json,
  };
}

/** A buyer with the PAY node who paid partly with credits and partly at the fake gateway (a "mixed" order, 06 section 7). */
export async function mixedOrder(
  { admin, buyer },
  cat,
  { credits = 3, label = 'mix', productRef } = {},
) {
  const api = await buyer(label);
  await grantUserNode(admin, api.userId, PAY_NODE); // a test-mode method is only offered to a buyer who may pay (06 section 6.7)
  await grantCredits(admin, api.userId, credits);
  const placed = await checkout(api, {
    items: [{ productId: (productRef ?? cat.vip).id, quantity: 1 }],
    paymentMethodId: 'fake',
    useCredits: credits,
  });
  if (!placed.payUrl) throw new Error('the mixed order has no payment URL');
  await completePayment(placed.payUrl);
  const order = await awaitStatus(api, placed.publicId, 'COMPLETED');

  return { api, publicId: placed.publicId, number: order.number };
}

/** A fully paid gateway order (no credits), COMPLETED. */
export async function gatewayOrder({ admin, buyer }, cat, { label = 'gw', productRef } = {}) {
  const api = await buyer(label);
  await grantUserNode(admin, api.userId, PAY_NODE);
  const placed = await checkout(api, {
    items: [{ productId: (productRef ?? cat.vip).id, quantity: 1 }],
    paymentMethodId: 'fake',
  });
  await completePayment(placed.payUrl);
  const order = await awaitStatus(api, placed.publicId, 'COMPLETED');

  return { api, publicId: placed.publicId, number: order.number };
}

/** A PENDING order on the bank transfer method (the buyer has not paid; nothing is delivered). */
export async function bankTransferOrder({ admin, buyer }, productRef, { label = 'bank' } = {}) {
  must(
    await admin.post('/api/panel/market/payment-methods/bank-transfer', {
      settings: {
        accounts: JSON.stringify([
          {
            bank: 'E2E Bank',
            holder: 'E2E Store',
            iban: 'DE89370400440532013000',
            currency: 'EUR',
          },
        ]),
        instructions: 'Transfer the exact amount.',
      },
    }),
    'configure bank transfer',
  );
  must(
    await admin.post('/api/panel/market/payment-methods/bank-transfer/toggle', { enabled: true }),
    'enable bank transfer',
  );
  const api = await buyer(label);
  const placed = await checkout(api, {
    items: [{ productId: productRef.id, quantity: 1 }],
    paymentMethodId: 'bank-transfer',
  });

  return { api, publicId: placed.publicId, number: placed.number };
}
