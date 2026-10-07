import { describe, expect, test } from 'bun:test';
import { COOKIE, createRouter, createSeam, parseCookie, readVolume, cookieString } from './core.js';
import { developmentMode, resetDevCache } from './dev.js';
import { countFor, listBody, paginate, rng, rows, uuidFor } from './kit.js';

const items = rows('t', 45, (i) => ({
  id: i + 1,
  name: `Item ${i + 1}`,
  status: i % 3 ? 'A' : 'B',
}));
const router = createRouter([
  {
    method: 'GET',
    path: '/api/x/items',
    handler: ({ query }) => {
      const filtered = items.filter(
        (it) =>
          (!query.status || it.status === query.status) && it.name.includes(query.search || ''),
      );
      return listBody('items', 'count', filtered, query, 20);
    },
  },
  { method: 'GET', path: '/api/x/items/:id', handler: ({ params }) => ({ id: Number(params.id) }) },
  { method: 'POST', path: '/api/x/quote', safe: true, handler: () => ({ result: 'ok', total: 5 }) },
]);

function makeSeam({ cookie, dev = true }) {
  const calls = [];
  const toasts = [];
  const real = {};
  for (const m of ['get', 'post', 'put', 'delete', 'customRequest']) {
    real[m] = async (o) => (calls.push([m, o.path]), { result: 'ok', real: true });
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
    expect(router.find('GET', '/api/x/items/7/').params.id).toBe('7');
    expect(router.find('GET', '/api/x/nope')).toBeNull();
    expect(router.find('POST', '/api/x/items')).toBeNull();
  });
  test('paging, search and status filters', () => {
    const p1 = router.answer('GET', '/api/x/items', 'many');
    expect(p1.items).toHaveLength(20);
    expect(p1.totalPage).toBe(3);
    const p3 = router.answer('GET', '/api/x/items?page=3', 'many');
    expect(p3.items).toHaveLength(5);
    expect(router.answer('GET', '/api/x/items?page=4', 'many').error).toBe('PAGE_NOT_FOUND');
    const b = router.answer('GET', '/api/x/items?status=B', 'many');
    expect(b.items.every((it) => it.status === 'B')).toBe(true);
    expect(router.answer('GET', '/api/x/items?search=Item%2041', 'many').count).toBe(1);
  });
  test('deterministic', () => {
    expect(router.answer('GET', '/api/x/items?page=2', 'few')).toEqual(
      router.answer('GET', '/api/x/items?page=2', 'few'),
    );
    expect(rng('a')()).toBe(rng('a')());
    expect(uuidFor('s', 1)).toBe(uuidFor('s', 1));
  });
  test('kit helpers', () => {
    expect(countFor('empty')).toBe(0);
    expect(countFor('many')).toBeGreaterThan(countFor('few'));
    expect(paginate([], {}).totalPage).toBe(1);
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
    expect((await seam.get({ path: '/api/x/items' })).items.length).toBe(20);
    expect((await seam.get({ path: '/api/unknown' })).real).toBe(true);
    expect(calls).toEqual([['get', '/api/unknown']]);
  });
  test('mutations never reach the backend and toast', async () => {
    const { seam, calls, toasts } = makeSeam({ cookie: 'few' });
    expect((await seam.post({ path: '/api/x/items', body: {} })).result).toBe('ok');
    await seam.put({ path: '/api/x/items/1', body: {} });
    await seam.delete({ path: '/api/x/items/1' });
    await seam.customRequest({ path: '/api/x/items/1', data: { method: 'POST' } });
    expect(calls).toEqual([]);
    expect(toasts).toHaveLength(4);
  });
  test('a safe POST is answered without a toast', async () => {
    const { seam, toasts } = makeSeam({ cookie: 'few' });
    expect((await seam.post({ path: '/api/x/quote', body: '{}' })).total).toBe(5);
    expect(toasts).toHaveLength(0);
  });
  test('inactive when development mode is off, whatever the cookie says', async () => {
    const { seam, calls, toasts } = makeSeam({ cookie: 'many', dev: false });
    expect((await seam.get({ path: '/api/x/items' })).real).toBe(true);
    await seam.post({ path: '/api/x/items', body: {} });
    expect(calls).toEqual([
      ['get', '/api/x/items'],
      ['post', '/api/x/items'],
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
    expect((await seam.get({ path: '/api/x/items' })).real).toBe(true);
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
    expect((await seam.get({ path: '/api/x/items' })).real).toBe(true);
  });
});

describe('development mode probe', () => {
  test('reads siteInfo.developmentMode, caches, unknown = false', async () => {
    resetDevCache();
    let n = 0;
    const api = { get: async () => (n++, { developmentMode: true }) };
    expect(await developmentMode(api, undefined, () => 1000)).toBe(true);
    expect(await developmentMode(api, undefined, () => 1001)).toBe(true);
    expect(n).toBe(1);
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
