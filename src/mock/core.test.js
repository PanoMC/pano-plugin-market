import { describe, expect, test } from 'bun:test';
import {
  COOKIE,
  gate,
  createRouter,
  createSeam,
  parseCookie,
  readVolume,
  cookieString,
  scopedPluginApi,
} from './core.js';
import { developmentMode, resetDevCache } from './dev.js';
import { countFor, failure, listBody, paginate, rng, rows, uuidFor } from './kit.js';

const items = rows('t', 45, (i) => ({
  id: i + 1,
  name: `Item ${i + 1}`,
  status: i % 3 ? 'A' : 'B',
}));
const router = createRouter([
  {
    method: 'GET',
    path: '/x/items',
    handler: ({ query }) => {
      const filtered = items.filter(
        (it) =>
          (!query.status || it.status === query.status) && it.name.includes(query.search || ''),
      );
      return listBody(filtered, query, 20);
    },
  },
  { method: 'GET', path: '/x/items/:id', handler: ({ params }) => ({ id: Number(params.id) }) },
  { method: 'POST', path: '/x/quote', safe: true, handler: () => ({ total: 5 }) },
]);

function makeSeam({ cookie, dev = true }) {
  const calls = [];
  const toasts = [];
  const real = {};
  for (const m of ['get', 'post', 'put', 'delete', 'customRequest']) {
    real[m] = async (o) => (calls.push([m, o.path]), { real: true });
  }
  const seam = createSeam({
    real,
    getDevMode: async () => dev,
    loadRouter: async () => router,
    notify: () => toasts.push(1),
    readVolumeFn: () => cookie,
  });
  return { seam, calls, toasts };
}

describe('router', () => {
  test('matches static, param and trailing slash paths', () => {
    expect(router.find('GET', '/x/items/7/').params.id).toBe('7');
    expect(router.find('GET', '/x/nope')).toBeNull();
    expect(router.find('POST', '/x/items')).toBeNull();
  });
  test('paging, search and status filters', () => {
    const p1 = router.answer('GET', '/x/items', 'many');
    expect(p1.items).toHaveLength(20);
    expect(p1.page).toEqual({ number: 1, size: 20, totalItems: 45, totalPages: 3 });
    expect(p1).not.toHaveProperty('result');
    expect(p1).not.toHaveProperty('totalPage');
    const p3 = router.answer('GET', '/x/items?page=3', 'many');
    expect(p3.items).toHaveLength(5);
    expect(router.answer('GET', '/x/items?page=4', 'many')).toEqual({
      error: { code: 'PAGE_NOT_FOUND' },
    });
    const b = router.answer('GET', '/x/items?status=B', 'many');
    expect(b.items.every((it) => it.status === 'B')).toBe(true);
    expect(router.answer('GET', '/x/items?search=Item%2041', 'many').page.totalItems).toBe(1);
  });
  test('deterministic', () => {
    expect(router.answer('GET', '/x/items?page=2', 'few')).toEqual(
      router.answer('GET', '/x/items?page=2', 'few'),
    );
    expect(rng('a')()).toBe(rng('a')());
    expect(uuidFor('s', 1)).toBe(uuidFor('s', 1));
  });
  test('kit helpers', () => {
    expect(countFor('empty')).toBe(0);
    expect(countFor('many')).toBeGreaterThan(countFor('few'));
    expect(paginate([], {}).page).toEqual({ number: 1, size: 10, totalItems: 0, totalPages: 0 });
  });
  test('the core page rule: a value out of range is refused, never clamped', () => {
    const refused = (query, fields) =>
      expect(listBody(items, query, 20)).toEqual({ error: { code: 'INVALID_FIELDS', fields } });
    refused({ page: '0' }, { page: 'OUT_OF_RANGE' });
    refused({ pageSize: '0' }, { pageSize: 'OUT_OF_RANGE' });
    refused({ pageSize: '101' }, { pageSize: 'OUT_OF_RANGE' });
    refused({ page: 'x', pageSize: '-1' }, { page: 'OUT_OF_RANGE', pageSize: 'OUT_OF_RANGE' });
    expect(listBody(items, { pageSize: '100' }, 20).page.size).toBe(100);
    expect(listBody(items, { pageSize: '60' }, 20, {}, 60).page.size).toBe(60);
    expect(listBody(items, { pageSize: '61' }, 20, {}, 60).error.code).toBe('INVALID_FIELDS');
    expect(listBody(items, {}, 20, { balance: 3 })).toMatchObject({ balance: 3 });
  });
  test('failure builds the envelope and leaves out what is not given', () => {
    expect(failure('NOT_FOUND')).toEqual({ error: { code: 'NOT_FOUND' } });
    expect(failure('X', { message: 'm', details: { a: 1 }, fields: { f: 'C' } })).toEqual({
      error: { code: 'X', message: 'm', details: { a: 1 }, fields: { f: 'C' } },
    });
  });
});

describe('cookie', () => {
  test('parse / build', () => {
    expect(parseCookie(`a=b; ${COOKIE}=many`)).toBe('many');
    expect(parseCookie(`${COOKIE}=bogus`)).toBe('few');
    expect(parseCookie('a=b')).toBeNull();
    expect(cookieString(null)).toContain('Max-Age=0');
  });
  test('server reads the request header, browser the document', () => {
    const event = { request: { headers: new Headers({ cookie: `${COOKIE}=empty` }) } };
    expect(readVolume(event, null)).toBe('empty');
    expect(readVolume({}, { cookie: `${COOKIE}=many` })).toBe('many');
  });
});

describe('seam gate', () => {
  test('GET with a fixture is answered, without one passes through', async () => {
    const { seam, calls } = makeSeam({ cookie: 'few' });
    expect((await seam.get({ path: '/x/items' })).items.length).toBe(20);
    expect((await seam.get({ path: '/unknown' })).real).toBe(true);
    expect(calls).toEqual([['get', '/unknown']]);
  });
  test('mutations never reach the backend and toast', async () => {
    const { seam, calls, toasts } = makeSeam({ cookie: 'few' });
    expect(await seam.post({ path: '/x/items', body: {} })).toEqual({ id: 1 });
    await seam.put({ path: '/x/items/1', body: {} });
    await seam.delete({ path: '/x/items/1' });
    await seam.customRequest({ path: '/x/items/1', data: { method: 'POST' } });
    expect(calls).toEqual([]);
    expect(toasts).toHaveLength(4);
  });
  test('a safe POST is answered without a toast', async () => {
    const { seam, toasts } = makeSeam({ cookie: 'few' });
    expect((await seam.post({ path: '/x/quote', body: '{}' })).total).toBe(5);
    expect(toasts).toHaveLength(0);
  });
  test('inactive when development mode is off, whatever the cookie says', async () => {
    const { seam, calls, toasts } = makeSeam({ cookie: 'many', dev: false });
    expect((await seam.get({ path: '/x/items' })).real).toBe(true);
    await seam.post({ path: '/x/items', body: {} });
    expect(calls).toEqual([
      ['get', '/x/items'],
      ['post', '/x/items'],
    ]);
    expect(toasts).toHaveLength(0);
  });
  test('inactive without the cookie, and the mode is not even asked', async () => {
    let asked = 0;
    const seam = createSeam({
      real: { get: async () => ({ real: true }) },
      getDevMode: async () => (asked++, true),
      loadRouter: async () => router,
      notify() {},
      readVolumeFn: () => null,
    });
    expect((await seam.get({ path: '/x/items' })).real).toBe(true);
    expect(asked).toBe(0);
  });
  test('a failing mode probe means off', async () => {
    const seam = createSeam({
      real: { get: async () => ({ real: true }) },
      getDevMode: async () => {
        throw new Error('x');
      },
      loadRouter: async () => router,
      notify() {},
      readVolumeFn: () => 'few',
    });
    expect((await seam.get({ path: '/x/items' })).real).toBe(true);
  });
});

describe('hydration guard', () => {
  test('deferred = pass-through, released = fixtures', async () => {
    const { seam } = makeSeam({ cookie: 'few' });
    gate.deferred = true;
    expect((await seam.get({ path: '/x/items' })).real).toBe(true);
    gate.deferred = false;
    expect((await seam.get({ path: '/x/items' })).items).toBeDefined();
  });
});

describe('development mode probe', () => {
  test('reads /site-info developmentMode, caches, unknown = false', async () => {
    resetDevCache();
    let n = 0;
    const asked = [];
    const api = {
      get: async (options) => (n++, asked.push(options.path), { developmentMode: true }),
    };
    expect(await developmentMode(api, undefined, () => 1000)).toBe(true);
    expect(await developmentMode(api, undefined, () => 1001)).toBe(true);
    expect(n).toBe(1);
    // relative to the API root: the host answers it, a path with the API prefix in front would be a 404
    expect(asked).toEqual(['/site-info']);
    resetDevCache();
    expect(await developmentMode({ get: async () => ({}) })).toBe(false);
    resetDevCache();
    expect(
      await developmentMode({
        get: async () => {
          throw new Error('x');
        },
      }),
    ).toBe(false);
    resetDevCache();
  });
});

describe('plugin-scoped client', () => {
  function scopedSeam() {
    const calls = [];
    const api = {};
    for (const m of ['get', 'post', 'put', 'delete', 'customRequest']) {
      api[m] = async (o) => (calls.push([m, o.path, o]), { m });
    }
    return { api, calls };
  }
  test('prefixes the site and the panel paths with the plugin id', async () => {
    const { api, calls } = scopedSeam();
    const client = scopedPluginApi(api, 'pano-plugin-market');
    for (const m of ['get', 'post', 'put', 'delete', 'customRequest']) {
      expect(await client[m]({ path: '/store/products' })).toEqual({ m });
      expect(await client.panel[m]({ path: '/orders/1', body: 'b' })).toEqual({ m });
    }
    expect(
      calls.filter(([, path]) => path.startsWith('/plugins/pano-plugin-market/store/products')),
    ).toHaveLength(5);
    expect(
      calls.filter(([, path]) => path === '/plugins/pano-plugin-market/panel/orders/1'),
    ).toHaveLength(5);
    expect(calls.find(([m, , o]) => m === 'post' && o.body === 'b')).toBeDefined();
  });
  test('needs the full plugin id', () => {
    expect(() => scopedPluginApi({}, undefined)).toThrow('full plugin id');
    expect(() => scopedPluginApi({}, 'a b')).toThrow('full plugin id');
  });
  test('preview mode answers it: the seam gets the prefixed path', async () => {
    const preview = createRouter([
      { method: 'GET', path: '/plugins/p/things', handler: () => ({ items: [], page: {} }) },
      { method: 'GET', path: '/plugins/p/panel/things', handler: () => ({ items: [1], page: {} }) },
    ]);
    const seam = createSeam({
      real: { get: async () => ({ real: true }) },
      getDevMode: async () => true,
      loadRouter: async () => preview,
      notify() {},
      readVolumeFn: () => 'few',
    });
    const client = scopedPluginApi(seam, 'p');
    expect((await client.get({ path: '/things' })).items).toEqual([]);
    expect((await client.panel.get({ path: '/things' })).items).toEqual([1]);
    expect((await client.get({ path: '/other' })).real).toBe(true);
  });
});
