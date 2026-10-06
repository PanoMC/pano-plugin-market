import { afterEach, beforeEach, describe, expect, mock, test } from 'bun:test';
import { get, writable } from 'svelte/store';
import './sdkMocks.js';

const calls = [];
let nextResult = { ok: true, settings: { storeName: 'S' } };
mock.module('../../utils/api.js', () => ({
  call: async (...args) => {
    calls.push(args);
    return nextResult;
  },
}));

const session = await import('../../stores/session.js');
const currency = await import('../../stores/currency.js');
const settingsMod = await import('../../stores/storeSettings.js');
const clock = await import('../../stores/clock.js');

function fakeStorage() {
  const data = new Map();
  return {
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => data.set(k, String(v)),
    removeItem: (k) => data.delete(k),
  };
}

describe('session', () => {
  beforeEach(() => session.resetSession());
  afterEach(() => {
    delete globalThis.window;
  });

  test('derived user, isLoggedIn and csrfToken follow the bound store', () => {
    const store = writable({ user: { id: 1, username: 'Steve' }, csrfToken: 'tok' });
    session.bindSession(store);
    expect(get(session.user)).toEqual({ id: 1, username: 'Steve' });
    expect(get(session.isLoggedIn)).toBe(true);
    expect(get(session.csrfToken)).toBe('tok');
    store.set({});
    expect(get(session.isLoggedIn)).toBe(false);
    expect(get(session.csrfToken)).toBeNull();
  });

  test('nothing bound means logged out', () => {
    expect(get(session.user)).toBeNull();
    expect(get(session.isLoggedIn)).toBe(false);
  });

  test('server side bind never runs initializers', () => {
    let runs = 0;
    session.onSessionInit(() => runs++);
    session.bindSession(writable({}));
    expect(runs).toBe(0);
  });

  test('browser: initializers run on first bind once and again on login / logout', () => {
    globalThis.window = {};
    let runs = 0;
    session.onSessionInit(() => runs++);
    const store = writable({});
    session.bindSession(store);
    session.bindSession(store);
    expect(runs).toBe(1);
    store.set({ user: { id: 2, username: 'Alex' } });
    expect(runs).toBe(2);
    store.set({ user: { id: 2, username: 'Alex' }, csrfToken: 'x' });
    expect(runs).toBe(2);
    store.set({});
    expect(runs).toBe(3);
  });

  test('hostSession: server side never calls getContext; the browser reads the context; a throwing getContext is a guest', () => {
    let calls = 0;
    const read = (key) => {
      calls++;
      return key === 'session' ? 'the-store' : undefined;
    };
    expect(session.hostSession(read)).toBeNull();
    expect(calls).toBe(0);

    globalThis.window = {};
    expect(session.hostSession(read)).toBe('the-store');
    expect(session.hostSession(() => undefined)).toBeNull();
    expect(
      session.hostSession(() => {
        throw new Error('lifecycle_outside_component');
      }),
    ).toBeNull();
  });

  test('a throwing initializer does not stop the others', () => {
    globalThis.window = {};
    const warn = console.warn;
    console.warn = () => {};
    let ran = false;
    session.onSessionInit(() => {
      throw new Error('boom');
    });
    session.onSessionInit(() => (ran = true));
    session.bindSession(writable({}));
    console.warn = warn;
    expect(ran).toBe(true);
  });
});

describe('currency', () => {
  const multi = { currencyMode: 'MULTI', currencies: ['USD', 'EUR'], displayCurrency: 'USD' };
  beforeEach(() => {
    globalThis.localStorage = fakeStorage();
    currency.preferred.set(null);
  });
  afterEach(() => {
    delete globalThis.localStorage;
  });

  test('url currency wins, then preferred, then nothing', () => {
    expect(currency.effectiveCurrency(multi, 'EUR', 'USD')).toBe('EUR');
    expect(currency.effectiveCurrency(multi, 'XXX', 'EUR')).toBe('EUR');
    expect(currency.effectiveCurrency(multi, undefined, 'GBP')).toBeUndefined();
    expect(currency.effectiveCurrency(multi, undefined, null)).toBeUndefined();
  });

  test('SINGLE mode sends nothing and has no selector', () => {
    const single = { currencyMode: 'SINGLE', currencies: ['USD'] };
    expect(currency.effectiveCurrency(single, 'USD', 'USD')).toBeUndefined();
    expect(currency.isMultiCurrency(single)).toBe(false);
    expect(currency.isMultiCurrency(multi)).toBe(true);
    expect(currency.needsCurrencyRefetch(single, 'USD')).toBe(false);
  });

  test('currencies may be objects with a code', () => {
    const s = { currencyMode: 'MULTI', currencies: [{ code: 'TRY' }, { code: 'USD' }] };
    expect(currency.effectiveCurrency(s, 'TRY')).toBe('TRY');
  });

  test('setPreferred persists, initCurrency reads it back, bad codes are ignored', () => {
    currency.setPreferred('EUR');
    expect(localStorage.getItem(currency.STORAGE_KEY)).toBe('EUR');
    currency.preferred.set(null);
    currency.initCurrency();
    expect(get(currency.preferred)).toBe('EUR');
    localStorage.setItem(currency.STORAGE_KEY, 'garbage');
    currency.initCurrency();
    expect(get(currency.preferred)).toBeNull();
    currency.setPreferred('eur');
    expect(localStorage.getItem(currency.STORAGE_KEY)).toBeNull();
  });

  test('unavailable storage does not throw', () => {
    globalThis.localStorage = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => {},
    };
    currency.initCurrency();
    expect(get(currency.preferred)).toBeNull();
    currency.setPreferred('EUR');
    expect(get(currency.preferred)).toBe('EUR');
  });

  test('adoptUrlCurrency saves an offered url currency only', () => {
    currency.adoptUrlCurrency(multi, 'EUR');
    expect(get(currency.preferred)).toBe('EUR');
    currency.adoptUrlCurrency(multi, 'JPY');
    expect(get(currency.preferred)).toBe('EUR');
  });

  test('refetch needed only when preferred differs from the displayed currency', () => {
    expect(currency.needsCurrencyRefetch(multi, 'EUR')).toBe(true);
    expect(currency.needsCurrencyRefetch(multi, 'USD')).toBe(false);
    expect(currency.needsCurrencyRefetch(multi, 'JPY')).toBe(false);
  });
});

describe('storeSettings', () => {
  beforeEach(() => {
    calls.length = 0;
    nextResult = { ok: true, settings: { storeName: 'S' } };
    settingsMod.storeSettings.set(null);
  });
  afterEach(() => {
    delete globalThis.window;
  });

  test('server: always fetches and never writes the store', async () => {
    expect(await settingsMod.ensureSettings({ x: 1 })).toEqual({ storeName: 'S' });
    expect(await settingsMod.ensureSettings()).toEqual({ storeName: 'S' });
    expect(calls).toHaveLength(2);
    expect(calls[0][2].event).toEqual({ x: 1 });
    expect(get(settingsMod.storeSettings)).toBeNull();
    settingsMod.setSettings({ storeName: 'X' });
    expect(get(settingsMod.storeSettings)).toBeNull();
  });

  test('browser: fetches once, concurrent callers share one request', async () => {
    globalThis.window = {};
    const [a, b] = await Promise.all([settingsMod.ensureSettings(), settingsMod.ensureSettings()]);
    expect(a).toBe(b);
    expect(await settingsMod.ensureSettings()).toEqual({ storeName: 'S' });
    expect(calls).toHaveLength(1);
    expect(get(settingsMod.storeSettings)).toEqual({ storeName: 'S' });
  });

  test('failure resolves to null and is retried next time', async () => {
    globalThis.window = {};
    nextResult = { ok: false, code: 'NETWORK' };
    expect(await settingsMod.ensureSettings()).toBeNull();
    nextResult = { ok: true, settings: { storeName: 'S' } };
    expect(await settingsMod.ensureSettings()).toEqual({ storeName: 'S' });
    expect(calls).toHaveLength(2);
  });
});

describe('clock', () => {
  afterEach(() => {
    delete globalThis.window;
  });

  test('server value is 0 and nothing ticks', () => {
    const values = [];
    const stop = clock.now.subscribe((v) => values.push(v));
    stop();
    expect(values).toEqual([0]);
  });

  test('browser: ticks while subscribed and stops after', async () => {
    globalThis.window = {};
    const values = [];
    const stop = clock.now.subscribe((v) => values.push(v));
    expect(values[0]).toBeGreaterThan(0);
    stop();
    const count = values.length;
    await new Promise((r) => setTimeout(r, 30));
    expect(values).toHaveLength(count);
  });
});
