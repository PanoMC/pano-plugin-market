// Brings the isolated instance into the state the browser scenarios need (the HTTP twin of E2eSession.bootstrap, 17 section 8.2):
// admin session, buyers get a session on registration, market settings, the fake providers pointed at the runner's gateway, and
// a catalogue of this run's own products (named with a run tag, so repeated runs never collide).
import crypto from 'node:crypto';
import { Api, must, PANEL_MARKET_API } from './api.mjs';

export const BUYER_PASSWORD = 'E2e-Browser-Passw0rd!';

export const run = {
  tag: Date.now().toString(36).slice(-6),
  seq: 0,
  next() {
    return ++this.seq;
  },
};

export async function adminSession(env) {
  const admin = new Api(env.url, 'admin');
  const res = await admin.login(env.adminUser, env.adminPassword(), true);

  if (res.status !== 200 || !admin.csrfToken)
    throw new Error(`admin login failed: ${res.status} ${res.error}`);

  return admin;
}

async function snapshot(admin) {
  return must(await admin.get('/api/v1/panel/permission/snapshot'), 'permission snapshot').json;
}

async function saveSnapshot(admin, snap, groups, nodes) {
  must(
    await admin.post('/api/v1/panel/permission/snapshot', {
      groups,
      tracks: snap.tracks ?? [],
      nodes,
    }),
    'save permission snapshot',
  );
}

/** Gives the user `node` (read-modify-write of the whole permission grid, the snapshot route replaces it). */
export async function grantUserNode(admin, userId, node) {
  const snap = await snapshot(admin);
  const nodes = snap.nodes ?? [];

  if (nodes.some((n) => n.holderType === 'USER' && n.holderId === userId && n.node === node))
    return;

  nodes.push({ holderType: 'USER', holderId: userId, node, active: true, context: {} });
  await saveSnapshot(admin, snap, snap.groups ?? [], nodes);
}

export async function bootstrap(env, gateway) {
  const admin = await adminSession(env);

  must(
    await admin.multipart('PUT', '/api/v1/panel/settings', { requireEmailVerification: 'false' }),
    'settings',
  );
  must(
    await admin.multipart('PUT', '/api/v1/panel/settings', {
      email: JSON.stringify({
        enabled: false,
        hostname: '',
        port: 587,
        ssl: false,
        starttls: 'DISABLED',
        username: '',
        password: '',
        sender: '',
      }),
    }),
    'mail switch',
  );

  must(
    await admin.post(`${PANEL_MARKET_API}/settings`, {
      testMode: true,
      currency: 'EUR',
      statsCurrency: 'EUR',
      vatPercent: 20,
      showVatInPrice: true,
      allowGuestCheckout: true,
      allowGiftPurchase: true,
      orderExpiryMinutes: 60,
      checkoutRateLimitPerMinute: 100000,
      quoteRateLimitPerMinute: 100000,
      couponLockThreshold: 1000,
      invoiceEnabled: true,
      sendEmailAfterPurchase: true,
      storeTimeZone: 'UTC',
      storeEnabled: true,
      minimumOrderAmount: 0,
    }),
    'market settings',
  );

  const credits = await admin.post(`${PANEL_MARKET_API}/settings/credits`, {
    creditsEnabled: true,
    creditValue: 1.0,
    allowMixedCreditPayment: true,
  });
  if (credits.status === 400)
    must(
      await admin.post(`${PANEL_MARKET_API}/settings/credits`, { creditsEnabled: true }),
      'credit settings',
    );
  else must(credits, 'credit settings');

  for (const id of ['fake', 'fake-eur']) {
    must(
      await admin.post(`${PANEL_MARKET_API}/payment-methods/${id}`, {
        settings: { gatewayUrl: gateway.baseUrl, secret: gateway.secret },
      }),
      `configure ${id}`,
    );
    must(
      await admin.post(`${PANEL_MARKET_API}/payment-methods/${id}/toggle`, { enabled: true }),
      `enable ${id}`,
    );
  }

  // the panel's "What's new" modal covers every panel page of an admin who has not dismissed it; the dismissal is stored on the account
  await admin.post('/api/v1/panel/dismissWhatsNew', { version: '1' });

  return admin;
}

/** A registered buyer; the session comes with the registration (its cookies are the buyer's browser login). */
export async function newBuyer(env, admin, label = 'buyer') {
  const n = run.next();
  const username = `b${label.slice(0, 3)}${run.tag}${n}`.slice(0, 16);
  const api = new Api(env.url, username);
  const res = await api.post('/api/v1/auth/register', {
    username,
    email: `${username}@example.com`,
    password: BUYER_PASSWORD,
    passwordRepeat: BUYER_PASSWORD,
    agreement: true,
  });

  must(res, `register ${username}`);
  api.csrfToken = res.json?.csrfToken ?? null;
  api.username = username;

  const player = must(
    await admin.get(`/api/v1/panel/players/${encodeURIComponent(username)}`),
    'find user',
  );
  api.userId = player.json?.player?.id ?? null;

  return api;
}

export async function grantCredits(admin, userId, amount) {
  must(
    await admin.post(
      `${PANEL_MARKET_API}/credits/accounts/${userId}/grant`,
      { amount, note: 'e2e browser' },
      { 'Idempotency-Key': crypto.randomUUID() },
    ),
    'grant credits',
  );
}

const unique = () => `${run.tag}${run.next()}`;

export async function category(admin, name) {
  const res = must(
    await admin.multipart('POST', `${PANEL_MARKET_API}/categories`, {
      name: `${name} ${run.tag}`,
      status: 'ACTIVE',
    }),
    `category ${name}`,
  );

  return { id: res.json.id, name: `${name} ${run.tag}` };
}

/** One ACTIVE product of the panel form; `fields` override / extend the form. */
export async function product(admin, name, fields = {}) {
  const slug = `e2eb-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-${unique()}`;
  const form = { name: `${name} ${run.tag}`, slug, price: '10.00', status: 'ACTIVE', ...fields };
  const res = must(
    await admin.multipart('POST', `${PANEL_MARKET_API}/products`, form),
    `product ${name}`,
  );

  return { id: res.json.id, slug, name: form.name, price: form.price };
}

const action = (id, type, phase, value) => ({ id, type, phase, value });

export const actions = {
  credit: (amount) => JSON.stringify([action('a1', 'CREDIT', 'GRANT', amount)]),
};

export async function coupon(admin, discountPercent) {
  const code = `B${run.tag}${run.next()}`.toUpperCase();
  const res = must(
    await admin.post(`${PANEL_MARKET_API}/coupons`, {
      name: `Browser ${code}`,
      code,
      discount: discountPercent,
      unit: 'PERCENT',
    }),
    'coupon',
  );

  return { id: res.json.id, code };
}

/** The shared catalogue of a run. */
export async function seedCatalogue(admin) {
  const cat = await category(admin, 'Ranks');
  const other = await category(admin, 'Kits');
  const vip = await product(admin, 'VIP Rank', {
    price: '10.00',
    creditPrice: '10.00',
    categoryId: cat.id,
    shortDescription: 'A shiny rank',
  });
  const free = await product(admin, 'Free Kit', {
    price: '0.00',
    categoryId: other.id,
    actions: actions.credit(1),
  });
  const crate = await product(admin, 'Crate', {
    price: '4.00',
    categoryId: other.id,
    hasVariants: 'true',
    variantOptions: JSON.stringify([
      {
        key: 'size',
        label: 'Size',
        values: [
          { key: 'S', label: 'S' },
          { key: 'L', label: 'L' },
        ],
      },
    ]),
    variants: JSON.stringify([
      { name: 'S', price: '4.00', stock: 5, position: 0, optionValues: { size: 'S' } },
      { name: 'L', price: '7.00', stock: 5, position: 1, optionValues: { size: 'L' } },
    ]),
  });
  const engraved = await product(admin, 'Engraved Pick', {
    price: '6.00',
    categoryId: other.id,
    fields: JSON.stringify([
      { fieldKey: 'engraving', label: 'Engraving text', type: 'TEXT', required: true },
    ]),
  });

  return { cat, other, vip, free, crate, engraved };
}
