// Helpers of the theme scenarios 1 to 15 (14 section 20.3). Nothing here is a scenario: the runner skips every `lib` directory.
import crypto from 'node:crypto';
import zlib from 'node:zlib';
import { Api, must } from '../../lib/api.mjs';
import { product as createProduct, run } from '../../lib/bootstrap.mjs';
import { assert, hydrated, open } from '../../lib/ui.mjs';

export const CART_KEY = 'pano-plugin-market-cart';

/** Raw server-rendered HTML of a storefront path, fetched without a browser (no JavaScript, no cookies). */
export async function html(env, path, headers = {}) {
  const res = await fetch(`${env.url}${path}`, { redirect: 'manual', headers });

  return { status: res.status, text: await res.text() };
}

/** The slug the backend stored (it slugifies whatever was asked for: ASCII only). */
export async function storedSlug(admin, id) {
  const res = must(await admin.get(`/api/panel/market/products/${id}`), `product ${id}`);

  return res.json.product.slug;
}

/** One ACTIVE product through the panel form, with the slug the backend really stored. */
export async function product(admin, name, fields = {}) {
  const made = await createProduct(admin, name, fields);

  return { ...made, slug: await storedSlug(admin, made.id) };
}

/** Panel market settings (partial update), restored by the caller in a `finally`. */
export async function setSettings(admin, patch) {
  must(await admin.post('/api/panel/market/settings', patch), `settings ${JSON.stringify(patch)}`);
}

/** Runs `fn` with `patch` applied and puts `restore` back afterwards, whatever happens. */
export async function withSettings(admin, patch, restore, fn) {
  await setSettings(admin, patch);
  // the store reads its settings through a small cache: a first request right after the PUT can still see the old values (TH-01 flaked on it)
  await new Promise((resolve) => setTimeout(resolve, 1500));

  try {
    return await fn();
  } finally {
    await setSettings(admin, restore);
  }
}

function crc32(buffer) {
  let c;
  let crc = 0xffffffff;

  for (const byte of buffer) {
    c = (crc ^ byte) & 0xff;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crc = (crc >>> 8) ^ c;
  }

  return (crc ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const head = Buffer.alloc(8);
  head.writeUInt32BE(data.length, 0);
  head.write(type, 4, 'ascii');
  const tail = Buffer.alloc(4);
  tail.writeUInt32BE(crc32(Buffer.concat([head.subarray(4), data])), 0);

  return Buffer.concat([head, data, tail]);
}

/** A solid-colour 16 x 16 PNG (the product form sniffs the magic bytes and makes a thumbnail, so it has to be a real image). */
export function png(r, g, b) {
  const size = 16;
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 2; // RGB
  const row = Buffer.concat([Buffer.from([0]), Buffer.from(Array(size).fill([r, g, b]).flat())]);
  const raw = Buffer.concat(Array(size).fill(row));

  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

/**
 * A product with a multipart form that carries image parts: `images` maps a part name (`image`, `variantImage_0`) to a PNG buffer.
 * Returns the product with its real slug.
 */
export async function productWithImages(admin, name, fields, images) {
  const slug = `e2eb-${name.toLowerCase().replace(/[^a-z0-9]+/g, '-')}-${run.tag}${run.next()}`;
  const form = new FormData();
  const all = { name: `${name} ${run.tag}`, slug, price: '10.00', status: 'ACTIVE', ...fields };

  for (const [key, value] of Object.entries(all)) form.append(key, String(value));
  for (const [part, buffer] of Object.entries(images))
    form.append(part, new Blob([buffer], { type: 'image/png' }), `${part}.png`);

  const res = must(
    await admin.request('POST', '/api/panel/market/products', form),
    `product ${name}`,
  );

  return {
    id: res.json.id,
    slug: await storedSlug(admin, res.json.id),
    name: all.name,
    price: all.price,
  };
}

/**
 * A Minecraft server the platform knows (what `/api/server/connect` + the panel's accept do for a real component; no socket is opened).
 * Returns { id, name }.
 */
export async function registerServer(env, admin, label) {
  const keys = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
  // the match key is a JSON number, the connect schema wants text
  const platformCode = String(
    must(await admin.get('/api/panel/basicData'), 'basicData').json.platformServerMatchKey,
  );
  const name = `e2eb-${label}-${run.tag}${run.next()}`;
  const anon = new Api(env.url, `mc-${label}`);

  must(
    await anon.post('/api/server/connect', {
      platformCode,
      serverName: name,
      host: '127.0.0.1',
      port: 25565,
      playerCount: 0,
      maxPlayerCount: 20,
      serverType: 'PAPER',
      serverVersion: '1.21',
      startTime: Date.now(),
      publicKey: keys.publicKey.export({ type: 'spki', format: 'der' }).toString('base64'),
    }),
    `connect ${name}`,
  );

  // a connect request waits for the owner's accept; it is listed as pending until then
  const list = must(await admin.get('/api/panel/servers/pending'), 'pending servers').json;
  const found = (list.servers || []).find((s) => s.name === name);

  assert(found, `the server ${name} is listed after connect`);
  must(await admin.post(`/api/panel/servers/${found.id}/accept`, {}), `accept ${name}`);

  return { id: found.id, name };
}

/** The stored guest cart of the page (parsed), or null when the key is absent. */
export async function storedCart(page) {
  const raw = await page.evaluate((key) => localStorage.getItem(key), CART_KEY);

  return raw === null ? null : JSON.parse(raw);
}

export async function cartRaw(page) {
  return page.evaluate((key) => localStorage.getItem(key), CART_KEY);
}

export async function waitForCards(page, count = 1) {
  await page
    .locator('.card h3 a')
    .nth(count - 1)
    .waitFor({ timeout: 60000 });
}

/** The cart button of the navbar; on a phone width the navbar is collapsed, so it is opened first. */
export async function cartButton(page) {
  const button = page.getByRole('button', { name: 'Your cart' }).first();

  if (!(await button.isVisible().catch(() => false))) {
    const toggler = page.locator('.navbar-toggler:visible').first();
    if (await toggler.count()) await toggler.click();
  }

  return button;
}

/** The number the cart badge shows (the visible, aria-hidden digit). */
export async function badgeCount(page) {
  const button = await cartButton(page);
  await button.waitFor({ state: 'visible', timeout: 30000 });

  return Number((await button.locator('.badge [aria-hidden="true"]').first().innerText()).trim());
}

/** Opens a product page and waits for the app. */
export async function openProduct(page, env, slug, query = '') {
  await open(page, `${env.url}/store/${encodeURIComponent(slug)}${query}`, (p) =>
    p.locator('.fs-4').first().waitFor({ timeout: 60000 }),
  );
}

/** Adds a plain product to the guest cart from its card on `/store?search=...`. */
export async function addFromCard(page, env, item, search) {
  await open(page, `${env.url}/store?search=${encodeURIComponent(search ?? item.name)}`, (p) =>
    waitForCards(p, 1),
  );
  await page
    .locator('.card', { hasText: item.name })
    .first()
    .getByRole('button', { name: 'Add to Cart' })
    .click();
  await page.getByText('Added to cart.').first().waitFor({ timeout: 30000 });
}

/** Total of the quantities of a stored cart. */
export const cartCount = (cart) => (cart?.items ?? []).reduce((sum, l) => sum + l.quantity, 0);

/** The text of the 404 / error page of the host (status is what is asserted; the text only has to exist). */
export async function status(page, url) {
  const response = await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 240000 });

  return response.status();
}

/** Reloads the page and waits until the app is hydrated again (the cart store initialises on hydration, not on the server render). */
export async function reload(page) {
  await page.reload({ waitUntil: 'domcontentloaded' });
  await hydrated(page);
  await waitForCards(page, 1);
}

/**
 * A buyer who can sign in through the form: the registration session of `newBuyer` needs no verified e-mail, but the login does
 * (LOGIN_EMAIL_NOT_VERIFIED), so the owner marks the address verified in the panel, the way an owner would.
 */
export async function verifiedBuyer(buyer, admin, label) {
  const account = await buyer(label);

  must(
    await admin.put(`/api/panel/players/${account.userId}`, {
      username: account.username,
      email: `${account.username}@example.com`,
      newPassword: '',
      newPasswordRepeat: '',
      isEmailVerified: true,
      canCreateTicket: true,
      localeCode: 'en-US',
      clearPassword: false,
    }),
    `verify ${account.username}`,
  );

  return account;
}
