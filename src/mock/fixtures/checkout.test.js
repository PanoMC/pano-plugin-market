import { beforeEach, describe, expect, test } from 'bun:test';
import { createRouter, parseScenario, scenarioCookieString, SCENARIO_COOKIE } from '../core.js';
import { SCENARIOS } from '../kit.js';
import { normalize } from '../../theme/lib/api-result.js';
import { afterCheckout } from '../../theme/lib/paymentStart.js';
import { failurePlan } from '../../theme/lib/checkoutSubmit.js';
import { viewState, VIEW_STATES } from '../../theme/lib/orderState.js';
import { routes as storeRoutes } from './storefront.js';
import { pages, resetSession, routes, STATES, statePublicId } from './checkout.js';

const API = '/plugins/pano-plugin-market';
const router = createRouter([...routes, ...storeRoutes]);
const body = { paymentMethodId: 'mock-card', currency: 'USD' };
const checkout = (scenario, b = body) =>
  router.answer('POST', `${API}/checkout`, 'few', b, scenario);
const order = (id) => router.answer('GET', `${API}/orders/${id}`, 'few').order;

beforeEach(resetSession);

describe('checkout scenarios', () => {
  test('the scenario cookie round-trips, unknown values are the default', () => {
    for (const s of SCENARIOS) expect(parseScenario(scenarioCookieString(s).split(';')[0])).toBe(s);
    expect(parseScenario(`${SCENARIO_COOKIE}=bogus`)).toBe('auto');
    expect(parseScenario('')).toBe('auto');
  });

  test('every success scenario creates an order the order page shows with the same start', () => {
    const kinds = {
      auto: 'REDIRECT',
      redirect: 'REDIRECT',
      iframe: 'IFRAME',
      'embedded-component': 'EMBEDDED',
      'embedded-form': 'EMBEDDED',
      instructions: 'INSTRUCTIONS',
      paid: 'COMPLETED',
    };
    for (const [scenario, kind] of Object.entries(kinds)) {
      const res = normalize(checkout(scenario));
      expect(res.ok).toBe(true);
      expect(res.payment.kind).toBe(kind);
      expect(res.orderToken).toMatch(/^preview-/);
      expect(order(res.order.publicId)).toEqual(res.order);
    }
    expect(checkout('auto', { paymentMethodId: 'bank-transfer' }).payment.kind).toBe(
      'INSTRUCTIONS',
    );
    expect(checkout('auto', { payWithCredits: true }).payment.kind).toBe('COMPLETED');
    const redirect = afterCheckout(checkout('redirect').payment, checkout('redirect').order);
    expect(redirect.type).toBe('ASSIGN');
    expect(redirect.url).toContain('?return=success');
  });

  test('failures answer the envelope the page acts on', () => {
    const ppe = normalize(checkout('PAYMENT_PROVIDER_ERROR'));
    expect(ppe.code).toBe('PAYMENT_PROVIDER_ERROR');
    expect(failurePlan({ res: ppe }).success.navigation.path).toBe(
      `/store/order/${ppe.order.publicId}`,
    );
    expect(viewState(order(ppe.order.publicId)).subKey).toBe('theme.order.state.attempt-failed');

    const buyer = normalize(checkout('PAYMENT_PROVIDER_ERROR-INVALID_BUYER'));
    expect(buyer.providerCode).toBe('INVALID_BUYER');
    expect(viewState(order(buyer.order.publicId)).subKey).toBe(
      'theme.order.state.attempt-failed-buyer',
    );

    expect(normalize(checkout('OUT_OF_STOCK')).lines.length).toBe(1);
    expect(normalize(checkout('BUYER_INFO_REQUIRED')).fields).toContain('BILLING_ADDRESS');
    const changed = normalize(checkout('PRICE_CHANGED'));
    expect(changed.code).toBe('PRICE_CHANGED');
    expect(changed.quote.total).toBeGreaterThan(0);
    expect(normalize(checkout('PRICE_CHANGED')).ok).toBe(true);
    expect(() => checkout('NETWORK')).toThrow();
  });

  test('pay, continue and cancel move the preview order', () => {
    const id = statePublicId('attempt-failed');
    const paid = router.answer('POST', `${API}/orders/${id}/pay`, 'few', body, 'iframe');
    expect(paid.payment.kind).toBe('IFRAME');
    expect(order(id).payment.status).toBe('PENDING');

    const form = statePublicId('awaiting-form');
    expect(
      router.answer('POST', `${API}/orders/${form}/payment/continue`, 'few', {}, 'auto'),
    ).toEqual({
      payment: { kind: 'COMPLETED', expiresAt: null },
    });
    expect(order(form).status).toBe('COMPLETED');

    const pending = statePublicId('awaiting');
    expect(router.answer('POST', `${API}/orders/${pending}/cancel`, 'few', {})).toEqual({});
    expect(order(pending).status).toBe('CANCELLED');
    expect(router.answer('POST', `${API}/orders/${pending}/cancel`, 'few', {}).error.code).toBe(
      'ORDER_NOT_CANCELLABLE',
    );
    expect(router.answer('POST', `${API}/orders/${pending}/pay`, 'few', body).error.code).toBe(
      'ORDER_NOT_PAYABLE',
    );
  });

  test('the drawer links reach every view state of the order page', () => {
    const seen = new Set();
    for (const page of pages) {
      const [path, search] = page.href.split('?');
      const id = path.split('/').pop();
      expect(id).toMatch(/^[0-9A-Za-z]{20}$/);
      const hint = new URLSearchParams(search ?? '').get('return');
      const view = viewState(order(id), hint, 1, 1);
      seen.add(view.state);
      if (view.limited) seen.add('limited');
      if (view.subKey) seen.add(view.subKey);
    }
    for (const state of VIEW_STATES) expect(seen.has(state)).toBe(true);
    expect(seen.has('limited')).toBe(true);
    expect(seen.has('theme.order.state.cancelled-at-gateway')).toBe(true);
    expect(pages.length).toBe(STATES.length);
  });
});
