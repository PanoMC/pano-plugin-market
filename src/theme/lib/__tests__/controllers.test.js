import { afterEach, describe, expect, test } from 'bun:test';
import { nullHost } from '@panomc/plugin-kit/controller';
import './sdkMocks.js';
import { createEnv } from './controllerEnv.js';
import fs from 'node:fs';
import path from 'node:path';
import { ALL } from './controllerSet.js';
import { createApi } from '../api.js';
import { DRAFT_KEY, defaultDraft } from '../checkoutDraftModel.js';
import { initialState } from '../cartEngine.js';
import { STORAGE_KEY } from '../cartModel.js';

let env;
// the controller `name` of the scripted registry (what `plugin('market').require(name)` returns in a view)
const controllerFor = (name) => env.controllers.use(name);
const actionsOf = (name) => controllerFor(name).actions;
const cart = () => controllerFor('market/cart');
const draft = () => controllerFor('market/checkoutDraft');
afterEach(() => {
  env?.dispose();
  env = undefined;
});

// ---- the fifteen controllers ---------------------------------------------------------------------------------

describe('the controller set', () => {
  test('exactly the fifteen controllers of doc 02 section 3', () => {
    expect(Object.keys(ALL).sort()).toEqual(
      [
        'api',
        'cart',
        'checkout',
        'checkoutDraft',
        'clock',
        'currency',
        'format',
        'host',
        'order',
        'product',
        'profile',
        'session',
        'settings',
        'store',
        'widgets',
      ].sort(),
    );
    for (const [key, def] of Object.entries(ALL)) {
      expect(def.name).toBe(key);
      expect(def.version).toBe(1);
    }
  });

  test('scopes: the four page loaders and market/widgets are instance scope with a load, nothing else has one', () => {
    for (const name of ['store', 'product', 'order', 'checkout', 'widgets']) {
      expect(ALL[name].scope).toBe('instance');
      expect(typeof ALL[name].load).toBe('function');
    }
    for (const name of Object.keys(ALL).filter(
      (n) => !['store', 'product', 'order', 'checkout', 'widgets'].includes(n),
    )) {
      expect(ALL[name].scope).toBe('app');
      expect(ALL[name].load).toBeUndefined();
    }
  });

  test('eager ones: session, cart, checkoutDraft and profile', () => {
    expect(
      Object.entries(ALL)
        .filter(([, def]) => def.eager)
        .map(([name]) => name)
        .sort(),
    ).toEqual(['cart', 'checkoutDraft', 'profile', 'session']);
  });

  // "state keys are complete at creation": the initial state holds EVERY public key (doc 02 section 1)
  const KEYS = {
    format: [],
    api: [],
    host: [],
    session: ['csrfToken', 'isLoggedIn', 'user'],
    settings: ['settings'],
    clock: ['now'],
    cart: [...Object.keys(initialState()), 'replaceRequest'],
    currency: ['preferred'],
    checkoutDraft: Object.keys(defaultDraft()),
    store: [],
    product: [],
    order: [],
    checkout: [],
    profile: ['summary'],
    widgets: [],
  };

  for (const [name, keys] of Object.entries(KEYS)) {
    test(`${name}: state keys are complete at creation`, () => {
      // on the null host (guest, no storage) with empty params: what the build's key enumeration does
      const controller = ALL[name].create(nullHost, {}, undefined, {
        namespace: 'market',
        use: () => null,
      });
      expect(Object.keys(controller.get()).sort()).toEqual([...keys].sort());
      controller.destroy();
    });
  }

  test('nothing is read from storage or the network when a controller is created', () => {
    env = createEnv({ preregister: false });
    env.local.getItem = () => {
      throw new Error('storage read at creation');
    };
    env.sessionStorage.getItem = () => {
      throw new Error('storage read at creation');
    };
    env.registerAll();
    expect(env.requests).toHaveLength(0);
    expect(cart().get().mode).toBe('NONE');
  });
});

// ---- logout clears the stored draft from any page -------------------------------------------------------------

describe('market/checkoutDraft is eager', () => {
  test('logout on a non-store page clears the stored draft', () => {
    env = createEnv();

    // the buyer is signed in and typed on the checkout page, then left it (detach flushes to sessionStorage)
    env.setSession({ user: { id: 7, username: 'Steve' } });
    draft().actions.restore('u:7');
    draft().actions.patch({ couponCode: 'SAVE', guest: { username: 'Steve', email: 's@x.io' } });
    draft().actions.detach();
    expect(JSON.parse(env.sessionStorage.getItem(DRAFT_KEY)).couponCode).toBe('SAVE');

    // no checkout page is mounted: the logout arrives through the session alone
    env.logout();

    expect(env.sessionStorage.getItem(DRAFT_KEY)).toBeNull();
    expect(draft().get().couponCode).toBe('');
  });

  test('it listens from registration on, with no subscriber', () => {
    env = createEnv();
    env.setSession({ user: { id: 7, username: 'Steve' } });
    env.sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ couponCode: 'X', owner: 'u:7' }));
    env.setSession({ user: { id: 8, username: 'Alex' } }); // another user
    expect(env.sessionStorage.getItem(DRAFT_KEY)).toBeNull();
  });

  test('a login keeps the guest draft', () => {
    env = createEnv();
    env.setSession({});
    draft().actions.restore('');
    draft().actions.patch({ couponCode: 'KEEP' });
    env.setSession({ user: { id: 1, username: 'Steve' } });
    expect(draft().get().couponCode).toBe('KEEP');
  });

  test('on the server nothing listens and nothing is written', () => {
    env = createEnv({ browser: false });
    env.setSession({ user: { id: 7 } });
    env.logout();
    expect(env.sessionStorage.data.size).toBe(0);
  });
});

// ---- the cart controller ----------------------------------------------------------------------------------------

describe('market/cart', () => {
  test('state mirrors the engine and carries count and replaceRequest', async () => {
    env = createEnv({
      respond: () => ({ quote: { currency: 'USD', lines: [], messages: [] } }),
    });
    await cart().actions.init();
    expect(cart().get().mode).toBe('GUEST');
    expect(cart().get().count).toBe(0);

    expect(
      await cart().actions.add(
        { productId: 5, quantity: 2 },
        { id: 5, name: 'Rank', slug: 'rank', price: 3 },
      ),
    ).toBe(true);
    expect(cart().get().count).toBe(2);
    expect(cart().get().count).toBe(2);
    expect(JSON.parse(env.local.getItem(STORAGE_KEY)).items[0]).toMatchObject({
      productId: 5,
      quantity: 2,
    });
    expect(env.toasts).toContain('plugins.pano-plugin-market.theme.store.added-to-cart');
    expect(cart().get().replaceRequest).toBeNull();
  });

  test('a signed-in user gets the server cart; the session listener initialises it', async () => {
    env = createEnv({
      respond: (r) =>
        r.path.startsWith('/plugins/pano-plugin-market/me/cart')
          ? {
              cart: {
                items: [
                  {
                    id: 1,
                    productId: 9,
                    variantId: 0,
                    quantity: 3,
                    fieldValues: {},
                    targetServerId: null,
                  },
                ],
              },
              quote: { currency: 'USD', lines: [], messages: [] },
            }
          : { error: { code: 'NETWORK_ERROR' } },
    });
    await cart().actions.init(); // the cart is live, so a login re-initialises it (lazy outside market pages otherwise)
    expect(cart().get().mode).toBe('GUEST');

    env.setSession({ user: { id: 4, username: 'Alex' } });
    await Bun.sleep(5);

    expect(cart().get().mode).toBe('SERVER');
    expect(cart().get().count).toBe(3);
    expect(env.requests.some((r) => r.path.startsWith('/plugins/pano-plugin-market/me/cart'))).toBe(
      true,
    );
  });

  test('before the cart is used the session does not start it outside market pages', async () => {
    env = createEnv();
    env.setSession({});
    await Bun.sleep(0);
    expect(cart().get().mode).toBe('NONE');
    expect(env.requests).toHaveLength(0);
  });

  test('the development preview event reloads the cart', async () => {
    env = createEnv({
      respond: () => ({ quote: { currency: 'USD', lines: [], messages: [] } }),
    });
    const listeners = [];
    const add = globalThis.addEventListener;
    const remove = globalThis.removeEventListener;
    globalThis.addEventListener = (type, fn) => listeners.push([type, fn]);
    globalThis.removeEventListener = (type, fn) => {
      const i = listeners.findIndex((l) => l[0] === type && l[1] === fn);
      if (i >= 0) listeners.splice(i, 1);
    };
    try {
      env.dispose();
      env = createEnv();
      expect(listeners.map((l) => l[0])).toEqual(['pano-market-mock-changed']);
      env.dispose();
      expect(listeners).toHaveLength(0);
    } finally {
      globalThis.addEventListener = add;
      globalThis.removeEventListener = remove;
    }
  });
});

// ---- the api controller ----------------------------------------------------------------------------------------

describe('lib/api createApi', () => {
  const hostWith = (answer) => {
    const sent = [];
    return {
      sent,
      request: async (r) => (sent.push(r), typeof answer === 'function' ? answer(r) : answer),
    };
  };

  test('a success keeps its keys, the path gets the plugin prefix and the query', async () => {
    const h = hostWith({ things: [1] });
    expect(await createApi(h).call('GET', '/x', { query: { a: 1, b: '', c: null } })).toEqual({
      ok: true,
      things: [1],
    });
    expect(h.sent[0]).toEqual({ method: 'GET', path: '/plugins/pano-plugin-market/x?a=1' });
  });

  test('an error in the controller shape becomes the code, details are spread and kept', async () => {
    const h = hostWith({ error: { code: 'OUT_OF_STOCK', details: { left: 2 } } });
    expect(await createApi(h).call('POST', '/p', { body: { a: 1 } })).toEqual({
      ok: false,
      code: 'OUT_OF_STOCK',
      left: 2,
      details: { left: 2 },
    });
    expect(h.sent[0]).toEqual({
      method: 'POST',
      path: '/plugins/pano-plugin-market/p',
      body: { a: 1 },
    });
  });

  test('error.details spread next to the code, error.fields and error.message are kept', async () => {
    expect(await createApi(hostWith({ error: { code: 'NOT_FOUND' } })).call('GET', '/p')).toEqual({
      ok: false,
      code: 'NOT_FOUND',
    });
    expect(
      await createApi(
        hostWith({
          error: {
            code: 'TOO_MANY_REQUESTS',
            details: { retryAfter: 4 },
            fields: { email: 'EXISTS' },
          },
        }),
      ).call('GET', '/p'),
    ).toEqual({
      ok: false,
      code: 'TOO_MANY_REQUESTS',
      retryAfter: 4,
      fields: { email: 'EXISTS' },
      details: { retryAfter: 4 },
    });
  });

  test('transport failures are NETWORK, as before', async () => {
    for (const answer of [
      { error: { code: 'NETWORK_ERROR' } },
      { error: { code: 'NULL_HOST' } },
      { error: { code: 'NO_REQUEST_EVENT' } },
      undefined,
      'text',
    ]) {
      expect(await createApi(hostWith(answer)).call('GET', '/p')).toEqual({
        ok: false,
        code: 'NETWORK',
      });
    }
    const throwing = {
      request: async () => {
        throw new Error('boom');
      },
    };
    expect(await createApi(throwing).call('GET', '/p')).toEqual({ ok: false, code: 'NETWORK' });
  });

  test('verbs, headers and blobs', async () => {
    const h = hostWith({});
    const api = createApi(h);
    await api.post('/a', { body: { x: 1 }, headers: { 'X-Order-Token': 't' } });
    await api.put('/b', { body: { y: 2 } });
    await api.del('/c', { body: { ignored: true } });
    await api.apiGet('/d');
    expect(h.sent).toEqual([
      {
        method: 'POST',
        path: '/plugins/pano-plugin-market/a',
        body: { x: 1 },
        headers: { 'X-Order-Token': 't' },
      },
      { method: 'PUT', path: '/plugins/pano-plugin-market/b', body: { y: 2 } },
      { method: 'DELETE', path: '/plugins/pano-plugin-market/c' },
      { method: 'GET', path: '/plugins/pano-plugin-market/d' },
    ]);
    expect(await api.call('PATCH', '/e')).toEqual({ ok: false, code: 'GENERIC' });

    const blob = new Blob(['x']);
    expect(await createApi(hostWith(blob)).call('GET', '/f', { blob: true })).toEqual({
      ok: true,
      blob,
    });
  });
});

describe('market/api controller', () => {
  test('call goes through the host; a server request gets a controller made for that request', async () => {
    env = createEnv({ browser: false, respond: () => ({ value: 1 }) });
    const event = { id: 'req-1' };
    const api = env.controllers.use('market/api', { event });
    expect(await api.actions.call('GET', '/x', { event })).toEqual({ ok: true, value: 1 });
    expect(env.requests[0].event).toBe(event);
  });

  test('in the browser one host serves the page: a request carries no event', async () => {
    env = createEnv({ respond: () => ({}) });
    await actionsOf('market/api').call('GET', '/x');
    expect(env.requests[0].event).toBeUndefined();
  });
});

// ---- the registered set -----------------------------------------------------------------------------------------

describe('the registered set', () => {
  test('controllerSet.js lists every public file of src/theme/controllers (what the build registers)', () => {
    const dir = path.resolve(import.meta.dir, '../../controllers');
    const files = fs
      .readdirSync(dir)
      .filter((f) => f.endsWith('.js') && !f.startsWith('_') && f !== 'types.js')
      .map((f) => f.slice(0, -3))
      .sort();
    expect(Object.keys(ALL).sort()).toEqual(files);
  });

  test('the registry resolves a controller once per page in the browser', () => {
    env = createEnv();
    const a = controllerFor('market/clock');
    expect(controllerFor('market/clock')).toBe(a);
  });

  test('disposing the environment drops the held instances', () => {
    env = createEnv();
    const a = controllerFor('market/settings');
    env.dispose();
    env = createEnv();
    expect(controllerFor('market/settings')).not.toBe(a);
  });

  test('an unknown controller is null, never a throw', () => {
    env = createEnv();
    expect(env.controllers.use('market/nope')).toBeNull();
  });
});

// ---- the page loaders ------------------------------------------------------------------------------------------

describe('page loaders', () => {
  const settings = {
    storeName: 'Shop',
    currencyMode: 'SINGLE',
    currencies: ['USD'],
    displayCurrency: 'USD',
  };
  const route = (table) => (r) => {
    for (const [prefix, answer] of Object.entries(table))
      if (r.path.startsWith(prefix)) return typeof answer === 'function' ? answer(r) : answer;
    return { error: { code: 'NETWORK_ERROR' } };
  };
  const load = (name, params) => env.controllers.load(`market/${name}`, { params });

  test('store: reads the filter from the url, asks the three endpoints, resolves the view result', async () => {
    env = createEnv({
      features: ['page-meta'],
      respond: route({
        '/plugins/pano-plugin-market/store/products': {
          items: [{ id: 1 }],
          page: { number: 1, size: 10, totalItems: 1, totalPages: 1 },
        },
        '/plugins/pano-plugin-market/store': {
          settings,
          categories: [{ id: 3, name: 'Ranks', children: [] }],
          items: [],
          page: { number: 1, size: 10, totalItems: 0, totalPages: 0 },
        },
        '/plugins/pano-plugin-market/widgets': { recentBuyers: [] },
      }),
    });
    const result = await load('store', { url: 'https://shop.test/store?search=rank&sort=price' });

    expect(result.data.state).toBe('READY');
    expect(result.data.grid.products).toEqual([{ id: 1 }]);
    expect(result.data.widgets).toEqual({ recentBuyers: [] });
    expect(result.meta).toBeDefined();
    const paths = env.requests.map((r) => r.path);
    expect(
      paths.some(
        (p) =>
          p.startsWith('/plugins/pano-plugin-market/store/products?') && p.includes('search=rank'),
      ),
    ).toBe(true);
    expect(paths).toContain('/plugins/pano-plugin-market/store');
    expect(paths.some((p) => p.startsWith('/plugins/pano-plugin-market/widgets?include='))).toBe(
      true,
    );
  });

  test('store: a page past the end resolves a redirect without ?page', async () => {
    env = createEnv({
      respond: route({
        '/plugins/pano-plugin-market/store/products': { error: { code: 'PAGE_NOT_FOUND' } },
        '/plugins/pano-plugin-market/store': {
          settings,
          categories: [],
          items: [],
          page: { number: 1, size: 10, totalItems: 0, totalPages: 0 },
        },
        '/plugins/pano-plugin-market/widgets': {},
      }),
    });
    const result = await load('store', { url: 'https://shop.test/store?page=99&search=x' });
    expect(result.redirect).toEqual({ status: 302, location: '/store?search=x' });
  });

  test('store: a disabled store', async () => {
    env = createEnv({
      respond: route({ '/plugins/pano-plugin-market': { error: { code: 'STORE_DISABLED' } } }),
    });
    expect((await load('store', {})).data.state).toBe('DISABLED');
  });

  test('product: slug from the route, 404 marker, settings travel with the result', async () => {
    env = createEnv({
      respond: route({
        '/plugins/pano-plugin-market/products/rank': {
          product: { id: 2, name: 'Rank', slug: 'rank', price: 3 },
        },
        '/plugins/pano-plugin-market/products/gone': { error: { code: 'NOT_FOUND' } },
        '/plugins/pano-plugin-market/store': { settings },
      }),
    });
    const ok = await load('product', {
      slug: 'rank',
      url: 'https://shop.test/store/rank?currency=EUR',
    });
    expect(ok.data).toMatchObject({ state: 'READY', slug: 'rank', settingsLoaded: true });
    expect(
      env.requests.find((r) => r.path.startsWith('/plugins/pano-plugin-market/products/rank')).path,
    ).toBe('/plugins/pano-plugin-market/products/rank?currency=EUR');
    expect(await load('product', { slug: 'gone' })).toEqual({ notFound: true });
  });

  test('order: a bad id is a 404 marker, a good one asks with the locale', async () => {
    env = createEnv({
      locale: 'tr',
      respond: route({
        '/plugins/pano-plugin-market/orders/': {
          order: { publicId: 'ABCDEFGHJKLMNPQRSTUV', status: 'COMPLETED', items: [], totals: {} },
        },
        '/plugins/pano-plugin-market/store': { settings },
      }),
    });
    expect(await load('order', { id: '' })).toEqual({ notFound: true });
    const res = await load('order', {
      id: 'ABCDEFGHJKLMNPQRSTUV',
      url: 'https://shop.test/store/order/x?token=t',
    });
    expect(res.notFound).toBeUndefined();
    expect(env.requests.some((r) => r.path.includes('locale=tr'))).toBe(true);
  });

  test('checkout: the config load', async () => {
    env = createEnv({
      respond: route({
        '/plugins/pano-plugin-market/checkout/config': { config: {} },
        '/plugins/pano-plugin-market/store': { settings },
      }),
    });
    const res = await load('checkout', { url: 'https://shop.test/store/checkout?topup=5' });
    expect(res.data).toBeDefined();
    expect(
      env.requests.some((r) => r.path.startsWith('/plugins/pano-plugin-market/checkout/config')),
    ).toBe(true);
  });

  test('a loader without an url reads an empty query', async () => {
    env = createEnv({
      respond: route({ '/plugins/pano-plugin-market': { error: { code: 'STORE_DISABLED' } } }),
    });
    expect((await load('checkout', undefined)).data).toBeDefined();
  });
});

// ---- small controllers ------------------------------------------------------------------------------------------

describe('market/host and market/session', () => {
  test('host: has, loginUrl, registerUrl over the host', () => {
    env = createEnv({ features: ['page-meta'] });
    const a = actionsOf('market/host');
    expect(a.has('page-meta')).toBe(true);
    expect(a.has('profile-nav')).toBe(false);
    expect(a.loginUrl('/store')).toBe('/login?redirect=/store');
    expect(a.registerUrl()).toBe('/register');
  });

  test('session: initial state from the host, then follows onSession', () => {
    env = createEnv({ user: { id: 1, username: 'A' }, csrfToken: 't' });
    expect(controllerFor('market/session').get()).toEqual({
      user: { id: 1, username: 'A' },
      isLoggedIn: true,
      csrfToken: 't',
    });
    env.logout();
    expect(controllerFor('market/session').get()).toEqual({
      user: null,
      isLoggedIn: false,
      csrfToken: null,
    });
  });
});
