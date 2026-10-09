// Helpers of the checkout scenarios 16 to 36 (14 section 20.3). Nothing here is a scenario: the runner skips every `lib` directory.
import crypto from 'node:crypto';
import fs from 'node:fs';
import { must, MARKET_API, PANEL_MARKET_API, listOf } from '../../lib/api.mjs';
import { grantUserNode } from '../../lib/bootstrap.mjs';
import { assert, hydrated, open } from '../../lib/ui.mjs';
import { verifiedBuyer } from './helpers.mjs';

export const PAY_NODE = 'pano.plugin.pano-plugin-market.manage.market.payments';
export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const en = JSON.parse(
  fs.readFileSync(new URL('../../../src/locales/theme/en-US.json', import.meta.url), 'utf8'),
);

/** The English text of a `theme.*` key (the browser runs in en-US), with `{name}` placeholders filled from `values`. */
export function text(key, values = {}) {
  const found = key.split('.').reduce((node, part) => node?.[part], en);

  if (typeof found !== 'string') throw new Error(`no English text for ${key}`);

  return found.replace(/\{(\w+)\}/g, (all, name) => (name in values ? String(values[name]) : all));
}

/** A regular expression that matches the text of `key` literally (placeholders left out are not matched). */
export const textRe = (key, values) =>
  new RegExp(text(key, values).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));

export const idem = () => ({ 'Idempotency-Key': crypto.randomUUID() });

/** A signed-in buyer who may use a test-mode method (the fake gateway is one: a guest can never use it, 06 section 6.7). */
export async function payBuyer(buyer, admin, label) {
  const account = await verifiedBuyer(buyer, admin, label);

  await grantUserNode(admin, account.userId, PAY_NODE);

  return account;
}

/** The pay / complete button of the checkout page. */
export const placeButton = (page) =>
  page.getByRole('button', { name: /^(Pay [^ ]|Complete order|Redirecting)/ });

/** Opens `/store/checkout` and waits until the page shows the form (not the loading block, not the empty card). */
export async function openCheckout(page, env, query = '') {
  await open(page, `${env.url}/store/checkout${query}`);
  await page.waitForFunction(
    () =>
      document.querySelector('#market-checkout-summary-card') ||
      document.body.innerText.includes('Your cart is empty') ||
      document.body.innerText.includes('Please sign in or create an account'),
    null,
    { timeout: 120000 },
  );
}

/** Waits until the order summary shows a settled quote (the totals list is not busy and has its rows). */
export async function waitQuoted(page) {
  await page.waitForFunction(
    () => {
      const dl = document.querySelector('#market-checkout-summary-card dl');

      return dl && dl.getAttribute('aria-busy') === 'false' && dl.children.length > 0;
    },
    null,
    { timeout: 60000 },
  );
}

/** The visible text of the order summary card. */
export const summaryText = (page) => page.locator('#market-checkout-summary-card').innerText();

/** Types the guest's username and e-mail (blur at the end commits them, which asks for the quote). */
export async function fillGuest(page, username, email) {
  await page.getByLabel('Minecraft username').fill(username);
  await page.getByLabel('E-mail').fill(email);
  await page.getByLabel('E-mail').blur();
}

/** The public id of the order page the browser is on (`/store/order/<id>`), or null. */
export const orderIdOf = (url) => /\/store\/order\/([^/?#]+)/.exec(url)?.[1] ?? null;

/** Waits for the order page (after the gateway round trip) and returns the public id. */
export async function waitForOrderPage(page, timeout = 120000) {
  await page.waitForURL((url) => url.pathname.startsWith('/store/order/'), { timeout });
  await hydrated(page);

  return orderIdOf(page.url());
}

/** Order status text block of the order page. */
export const statusBlock = (page) => page.locator('[role="status"]').first();

/** A panel read of an order by its public id (the panel route takes the numeric id, found through the list). */
export async function panelOrder(admin, publicId) {
  const list = must(
    await admin.get(`${PANEL_MARKET_API}/orders?search=${encodeURIComponent(publicId)}&page=1`),
    `panel order search ${publicId}`,
  ).json;
  const row =
    listOf(list, 'orders').find((o) => o.publicId === publicId) ?? listOf(list, 'orders')[0];

  assert(row, `the panel lists the order ${publicId}`);

  return must(await admin.get(`${PANEL_MARKET_API}/orders/${row.id}`), `panel order ${row.id}`)
    .json;
}

/** The buyer-side view of an order through the API. */
export async function orderView(api, publicId, token) {
  const res = await api.get(
    `${MARKET_API}/orders/${publicId}${token ? `?token=${encodeURIComponent(token)}` : ''}`,
  );

  return res.json?.order ?? null;
}

/** Polls the order of `api` until `done(order)`; fails with the last state. */
export async function awaitOrder(api, publicId, done, label = 'the order', tries = 80) {
  let last = null;

  for (let i = 0; i < tries; i++) {
    last = await orderView(api, publicId);
    if (last && done(last)) return last;
    await sleep(500);
  }

  throw new Error(`${label} did not reach the expected state (is ${last?.status})`);
}

/**
 * Keeps the buyer's browser away from the fake gateway's pay page: the page of the gateway is answered locally (a redirect to the store's
 * return URL) so the payment stays unpaid, and the reference is recorded. `release(reference)` then does what the gateway does after a
 * payment (marks it paid and delivers the signed `payment.succeeded` webhook). Used to see the CONFIRMING state of the order page.
 */
export async function holdAtGateway(page, gateway, { refresh = true } = {}) {
  const held = [];
  /** What the browser asked the gateway for: { reference, method, postData, navigation, frame } in order. */
  const requests = [];

  // From its creation the payment is in a status the provider does not know ("the gateway has not decided yet"): a status query of the order
  // page (it polls) is then ignored instead of reporting "pending", which would move the attempt to PROCESSING and take the payment form
  // (an iframe, an embedded step) off the page while the buyer is still paying.
  const originalSet = gateway.payments.set;

  gateway.payments.set = function (reference, payment) {
    payment.status = 'settling';

    return originalSet.call(this, reference, payment);
  };
  page.on('close', () => {
    gateway.payments.set = originalSet;
  });

  await page.route(
    (url) => url.port === String(new URL(gateway.baseUrl).port) && url.pathname.startsWith('/pay/'),
    async (route) => {
      const request = route.request();
      const reference = decodeURIComponent(new URL(request.url()).pathname.split('/').pop());
      const payment = gateway.payments.get(reference);

      held.push(reference);
      requests.push({
        reference,
        method: request.method(),
        postData: request.postData(),
        navigation: request.isNavigationRequest(),
        topLevel: request.frame() === page.mainFrame(),
      });
      // a status the provider does not know: its return handler learns nothing and the order stays CREATED (CONFIRMING)
      if (payment.status === 'pending') payment.status = 'settling';

      await route.fulfill({
        status: 200,
        contentType: 'text/html; charset=utf-8',
        body: `<!doctype html><meta charset="utf-8">${
          refresh ? `<meta http-equiv="refresh" content="0;url=${payment.returnSuccess}">` : ''
        }<title>Fake gateway</title><body><p id="held">Fake gateway (TEST ONLY): payment ${reference} is waiting</p></body>`,
      });
    },
  );

  return {
    held,
    requests,
    /** Marks the held payment paid and delivers the signed webhook the way the gateway does. */
    async release(reference = held.at(-1)) {
      const payment = gateway.payments.get(reference);

      payment.status = 'paid';

      const body = JSON.stringify({
        id: `evt_h${Date.now().toString(36)}${Math.floor(Math.random() * 1e6)}`,
        type: 'payment.succeeded',
        data: {
          reference,
          paymentId: payment.id,
          amount: payment.amount,
          currency: payment.currency,
        },
      });
      const t = Math.floor(Date.now() / 1000);
      const v1 = crypto.createHmac('sha256', gateway.secret).update(`${t}.${body}`).digest('hex');
      // the `notify` URL of a start is the Mollie-style form callback (it wants a form `id`); the signed JSON webhook goes to the provider's webhook route
      const webhookUrl = payment.notifyUrl.replace(/\/notify\/[^/?#]+(\?.*)?$/, '/webhook');
      const res = await fetch(webhookUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Fake-Signature': `t=${t},v1=${v1}` },
        body,
      });

      assert(
        res.status < 300,
        `the webhook was accepted (${res.status} ${(await res.text()).slice(0, 200)})`,
      );
    },
  };
}

/** Writes the settings of a fake provider (its URL and secret stay; `extra` is laid over them). */
export async function setFakeSettings(admin, gateway, extra, id = 'fake') {
  must(
    await admin.post(`${PANEL_MARKET_API}/payment-methods/${id}`, {
      settings: { gatewayUrl: gateway.baseUrl, secret: gateway.secret, ...extra },
    }),
    `fake settings ${JSON.stringify(extra)}`,
  );
}

/** Sets the start kind of the fake provider `id` (`REDIRECT`, `FORM_POST`, `IFRAME`, `INSTRUCTIONS`, `EMBEDDED`, `COMPLETED`). */
export const setStartKind = (admin, gateway, kind, id = 'fake') =>
  setFakeSettings(admin, gateway, { startKind: kind }, id);

/** Runs `fn` with the settings of the fake provider laid over its defaults, and puts REDIRECT / status query back. */
export async function withFakeSettings(admin, gateway, extra, fn) {
  await setFakeSettings(admin, gateway, extra);
  await sleep(1000);

  try {
    return await fn();
  } finally {
    await setFakeSettings(admin, gateway, { startKind: 'REDIRECT', statusQuery: true });
  }
}

/** Runs `fn` with the fake provider on `kind` and puts REDIRECT back. */
export const withStartKind = (admin, gateway, kind, fn) =>
  withFakeSettings(admin, gateway, { startKind: kind }, fn);

/** Panel settings of the credits section (partial update). */
export async function setCreditSettings(admin, patch) {
  must(await admin.post(`${PANEL_MARKET_API}/settings/credits`, patch), 'credit settings');
}

/** The checkout config as a visitor sees it. */
export async function checkoutConfig(api, query = '') {
  return must(await api.get(`${MARKET_API}/checkout/config${query}`), 'checkout config').json;
}

/** The plain address of the shipping scenarios. */
export const address = (country = 'DE') => ({
  firstName: 'Ada',
  lastName: 'Lovelace',
  phone: '+4915112345678',
  country,
  city: 'Berlin',
  line1: 'Unter den Linden 1',
  postalCode: '10117',
});

/** Settings with restore, plus a short pause: the store reads its settings through a small cache (E2E-16 saw a first read right after a PUT). */
export async function withSettingsAndWait(admin, patch, restore, fn) {
  const { withSettings } = await import('./helpers.mjs');

  return withSettings(admin, patch, restore, async () => {
    await sleep(1500);

    return fn();
  }).finally(() => sleep(1500));
}

/** The bank transfer method configured and switched on for the length of `fn`, switched off afterwards. */
export async function withBankTransfer(admin, fn) {
  must(
    await admin.post(`${PANEL_MARKET_API}/payment-methods/bank-transfer`, {
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
    await admin.post(`${PANEL_MARKET_API}/payment-methods/bank-transfer/toggle`, { enabled: true }),
    'enable bank transfer',
  );

  try {
    return await fn();
  } finally {
    await admin.post(`${PANEL_MARKET_API}/payment-methods/bank-transfer/toggle`, {
      enabled: false,
    });
  }
}

/** The "Apply" button of a code input (`coupon` or `creator`). */
export const codeApply = (page, kind) =>
  page
    .locator(kind === 'coupon' ? '#market-checkout-coupon' : '#market-checkout-creator-code')
    .locator('xpath=following-sibling::button');

/** Types a code and presses Apply, then waits until the quote is settled again. */
export async function applyCode(page, kind, code) {
  const input = page.locator(
    kind === 'coupon' ? '#market-checkout-coupon' : '#market-checkout-creator-code',
  );

  await input.fill(code);
  await codeApply(page, kind).click();
}

/** A signed-in page of `account` (cookies of its API session). */
export async function signedIn(browser, account, options = {}) {
  const { newContext } = await import('../../lib/browser.mjs');

  return newContext(browser, {
    viewport: 'desktop',
    cookies: account.playwrightCookies(),
    ...options,
  });
}

/** Counts the `POST /api/plugins/pano-plugin-market/checkout` requests of a page (an order is only asked for by this one request). */
export function countCheckoutPosts(page, env) {
  const seen = [];

  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url() === `${env.url}${MARKET_API}/checkout`)
      seen.push({ key: request.headers()['idempotency-key'], body: request.postData() });
  });

  return seen;
}

/** Order public ids of a signed-in buyer, newest first. */
export async function myOrders(api) {
  const res = must(await api.get(`${MARKET_API}/me/orders?page=1`), 'my orders').json;

  return listOf(res, 'orders');
}

/**
 * A request a scenario provokes on purpose (a refused checkout, a rate limit) makes the browser log one console error per answer. The scenario
 * says how many it expects: exactly that many errors matching `pattern` are removed from the context, any other error still fails `expectNoErrors`.
 */
export function takeProvokedErrors(context, pattern, count, label) {
  const matching = context.errors.filter((error) => pattern.test(error));

  assert(
    matching.length === count,
    `${label}: expected ${count} provoked console error(s) matching ${pattern}, saw ${matching.length}: ${context.errors.join(' | ')}`,
  );

  for (const error of matching) context.errors.splice(context.errors.indexOf(error), 1);
}
