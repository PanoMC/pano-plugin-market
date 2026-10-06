// The fake payment gateway of the browser runs: the wire protocol of 17 section 6.3 (the Kotlin FakePayGateway lives in the test JVM
// and does not exist while `bun run e2e:browser` runs, so the runner serves its own on MARKET_E2E_GATEWAY_PORT).
// The pay page marks the payment paid, delivers the signed `payment.succeeded` webhook and returns the buyer ("auto-returns").
import crypto from 'node:crypto';
import http from 'node:http';

export const GATEWAY_SECRET = 'e2e_browser_secret_0123456789';

export async function startGateway(port) {
  const payments = new Map(); // reference -> { id, amount, currency, status, notifyUrl, returnSuccess, returnCancel }
  // gateway transaction ids are unique per provider in the market database (uq_provider_txn) and the instance database outlives a run
  const runTag = Date.now().toString(36);
  const refunds = new Map(); // idempotency key -> refund
  const refundsById = new Map();
  let sequence = 0;

  async function sendWebhook(payment, type) {
    const body = JSON.stringify({
      id: `evt_b${Date.now().toString(36)}${sequence++}`,
      type,
      data: {
        reference: payment.reference,
        paymentId: payment.id, // the provider keeps it as the gateway transaction id, which a refund needs (E2E-14)
        amount: payment.amount,
        currency: payment.currency,
        // a subscription payment hands the provider a stored method, which is what merchant-initiated renewals charge
        ...(payment.subscription
          ? {
              storedMethod: {
                token: `tok_b${Date.now().toString(36)}${sequence}`,
                label: 'Visa 4242',
              },
            }
          : {}),
      },
    });
    const t = Math.floor(Date.now() / 1000);
    const v1 = crypto.createHmac('sha256', GATEWAY_SECRET).update(`${t}.${body}`).digest('hex');

    return fetch(payment.notifyUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Fake-Signature': `t=${t},v1=${v1}` },
      body,
    });
  }

  const json = (res, status, value) => {
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify(value));
  };

  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, `http://127.0.0.1:${port}`);
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const raw = Buffer.concat(chunks).toString('utf8');

    try {
      if (req.method === 'GET' && url.pathname === '/v1/ping') return json(res, 200, { ok: true });

      if (req.method === 'POST' && url.pathname === '/v1/payments') {
        const body = JSON.parse(raw);
        const payment = { id: `pay_${runTag}${++sequence}`, status: 'pending', ...body };
        payments.set(body.reference, payment);
        return json(res, 201, {
          id: payment.id,
          payUrl: `http://127.0.0.1:${port}/pay/${encodeURIComponent(body.reference)}`,
        });
      }

      const query = url.pathname.match(/^\/v1\/payments\/([^/]+)$/);

      if (req.method === 'GET' && query) {
        const payment = payments.get(decodeURIComponent(query[1]));
        if (!payment) return json(res, 404, { message: 'unknown' });
        return json(res, 200, {
          id: payment.id,
          status: payment.status,
          paidAmount: payment.status === 'paid' ? payment.amount : '0.00',
          currency: payment.currency,
        });
      }

      // refunds (E2E-14): always succeeds; one refund per Idempotency-Key, like the Kotlin FakePayGateway
      if (req.method === 'POST' && url.pathname === '/v1/refunds') {
        const key = req.headers['idempotency-key'] || `auto-${sequence}`;
        let refund = refunds.get(key);
        if (!refund) {
          const body = JSON.parse(raw);
          refund = { id: `rf_${runTag}${++sequence}`, status: 'succeeded', ...body };
          refunds.set(key, refund);
          refundsById.set(refund.id, refund);
        }
        return json(res, 200, { id: refund.id, status: refund.status });
      }

      const refundQuery = url.pathname.match(/^\/v1\/refunds\/([^/]+)$/);

      if (req.method === 'GET' && refundQuery) {
        const refund = refundsById.get(decodeURIComponent(refundQuery[1]));
        if (!refund) return json(res, 404, { message: 'unknown' });
        return json(res, 200, { id: refund.id, status: refund.status });
      }

      const cancel = url.pathname.match(/^\/v1\/payments\/([^/]+)\/cancel$/);

      if (req.method === 'POST' && cancel) {
        const payment = payments.get(decodeURIComponent(cancel[1]));
        if (!payment || payment.status !== 'pending')
          return json(res, 409, { message: 'not cancellable' });
        payment.status = 'expired';
        return json(res, 200, {});
      }

      const page = url.pathname.match(/^\/pay\/([^/]+)$/);

      if (req.method === 'GET' && page) {
        const payment = payments.get(decodeURIComponent(page[1]));
        if (!payment) return json(res, 404, { message: 'unknown' });

        const cancelled = url.searchParams.get('outcome') === 'cancel';

        if (cancelled) {
          payment.status = 'expired';
        } else {
          payment.status = 'paid';
          await sendWebhook(payment, 'payment.succeeded');
        }

        const target = cancelled ? payment.returnCancel : payment.returnSuccess;
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        return res.end(
          `<!doctype html><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=${target}"><title>Fake gateway</title>` +
            `<body><p>Fake gateway (TEST ONLY): ${cancelled ? 'cancelled' : 'paid'}, returning to the store.</p></body>`,
        );
      }

      json(res, 404, { message: 'not found' });
    } catch (e) {
      json(res, 500, { message: String(e?.message || e) });
    }
  });

  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(port, '127.0.0.1', resolve);
  });

  return {
    baseUrl: `http://127.0.0.1:${port}`,
    secret: GATEWAY_SECRET,
    payments,
    close: () => new Promise((resolve) => server.close(() => resolve())),
  };
}

/**
 * What a buyer's browser does at the gateway without a browser: opens the pay page (the gateway marks the payment paid and delivers the
 * signed webhook) and follows its auto-return to the store, which is what makes the platform query the payment (06 section 7).
 */
export async function completePayment(payUrl) {
  const page = await fetch(payUrl);
  if (!page.ok) throw new Error(`the fake gateway page answered ${page.status}`);

  const html = await page.text();
  const target = /http-equiv="refresh" content="0;url=([^"]+)"/.exec(html)?.[1];

  if (target) await fetch(target.replace(/&amp;/g, '&'), { redirect: 'manual' });
}
