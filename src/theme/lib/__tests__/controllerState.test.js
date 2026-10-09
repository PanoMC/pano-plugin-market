import { afterEach, beforeEach, describe, expect, test } from 'bun:test';
import './sdkMocks.js';
import { createEnv } from './controllerEnv.js';
import { STORAGE_KEY } from '../../controllers/_currency.js';

// The session, currency, settings and clock controllers run against a scripted host and a registry that mirrors
// pano.controllers (controllerEnv.js); a view reaches the same objects through plugin('market').require(name).

let env;
afterEach(() => env?.dispose());

const controller = (name) => env.use(name);
const state = (name) => controller(name).get();
const actions = (name) => controller(name).actions;
const SETTINGS_PATH = '/store';

// slice readers
const session = {
  user: () => state('session').user,
  isLoggedIn: () => state('session').isLoggedIn,
  csrfToken: () => state('session').csrfToken,
  onChange: (fn) => actions('session').onChange(fn),
};

describe('session', () => {
  test('user, isLoggedIn and csrfToken follow the host session', () => {
    env = createEnv();
    env.setSession({ user: { id: 1, username: 'Steve' }, csrfToken: 'tok' });
    expect(session.user()).toEqual({ id: 1, username: 'Steve' });
    expect(session.isLoggedIn()).toBe(true);
    expect(session.csrfToken()).toBe('tok');
    env.logout();
    expect(session.user()).toBeNull();
    expect(session.isLoggedIn()).toBe(false);
    expect(session.csrfToken()).toBeNull();
  });

  test('a session known before the controller exists is its first state', () => {
    env = createEnv({ user: { id: 3, username: 'Early' }, csrfToken: 'c' });
    expect(session.user()).toEqual({ id: 3, username: 'Early' });
    expect(session.csrfToken()).toBe('c');
  });

  test('nothing bound means logged out', () => {
    env = createEnv();
    expect(session.user()).toBeNull();
    expect(session.isLoggedIn()).toBe(false);
  });

  test('the state follows each session change', () => {
    env = createEnv();
    const seen = [];
    const stop = controller('session').subscribe((st) => seen.push(st.isLoggedIn));
    env.setSession({ user: { id: 1, username: 'A' }, csrfToken: 'x' });
    env.setSession({ user: { id: 1, username: 'A' }, csrfToken: 'y' });
    env.logout();
    stop();
    expect(seen).toEqual([false, true, false]);
  });

  test('server side the host never runs initializers', () => {
    env = createEnv({ browser: false });
    let runs = 0;
    session.onChange(() => runs++);
    env.setSession({});
    expect(runs).toBe(0);
  });

  test('browser: initializers run on first bind once and again on login / logout', () => {
    env = createEnv();
    let runs = 0;
    session.onChange(() => runs++);
    env.setSession({});
    env.setSession({});
    expect(runs).toBe(1);
    env.setSession({ user: { id: 2, username: 'Alex' } });
    expect(runs).toBe(2);
    env.setSession({ user: { id: 2, username: 'Alex' }, csrfToken: 'x' });
    expect(runs).toBe(2);
    env.logout();
    expect(runs).toBe(3);
  });

  test('onChange returns the unregister function', () => {
    env = createEnv();
    let runs = 0;
    const off = session.onChange(() => runs++);
    env.setSession({});
    off();
    env.setSession({ user: { id: 1 } });
    expect(runs).toBe(1);
  });

  test('a throwing initializer does not stop the others', () => {
    env = createEnv();
    const error = console.error;
    console.error = () => {};
    let ran = false;
    session.onChange(() => {
      throw new Error('boom');
    });
    session.onChange(() => (ran = true));
    env.setSession({});
    console.error = error;
    expect(ran).toBe(true);
  });
});

describe('currency', () => {
  const currency = {
    STORAGE_KEY,
    effectiveCurrency: (...a) => actions('currency').effective(...a),
    setPreferred: (code) => actions('currency').setPreferred(code),
    needsCurrencyRefetch: (...a) => actions('currency').needsRefetch(...a),
    isMultiCurrency: (settings) => actions('currency').isMulti(settings),
    initCurrency: () => actions('currency').init(),
    adoptUrlCurrency: (...a) => actions('currency').adoptUrl(...a),
    preferred: () => state('currency').preferred,
  };
  const multi = { currencyMode: 'MULTI', currencies: ['USD', 'EUR'], displayCurrency: 'USD' };
  beforeEach(() => {
    env = createEnv();
  });

  test('url currency wins, then preferred, then nothing', () => {
    expect(currency.effectiveCurrency(multi, 'EUR', 'USD')).toBe('EUR');
    expect(currency.effectiveCurrency(multi, 'XXX', 'EUR')).toBe('EUR');
    expect(currency.effectiveCurrency(multi, undefined, 'GBP')).toBeUndefined();
    expect(currency.effectiveCurrency(multi, undefined, null)).toBeUndefined();
  });

  test('the preferred argument defaults to the stored preference', () => {
    currency.setPreferred('EUR');
    expect(currency.effectiveCurrency(multi, undefined)).toBe('EUR');
    expect(currency.needsCurrencyRefetch(multi)).toBe(true);
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
    expect(env.local.getItem(currency.STORAGE_KEY)).toBe('EUR');
    currency.setPreferred(null);
    expect(env.local.getItem(currency.STORAGE_KEY)).toBeNull();
    env.local.setItem(currency.STORAGE_KEY, 'EUR');
    currency.initCurrency();
    expect(currency.preferred()).toBe('EUR');
    env.local.setItem(currency.STORAGE_KEY, 'garbage');
    currency.initCurrency();
    expect(currency.preferred()).toBeNull();
    currency.setPreferred('eur');
    expect(env.local.getItem(currency.STORAGE_KEY)).toBeNull();
  });

  test('unavailable storage does not throw', () => {
    const blocked = () => {
      throw new Error('blocked');
    };
    env.local.getItem = blocked;
    env.local.setItem = blocked;
    currency.initCurrency();
    expect(currency.preferred()).toBeNull();
    currency.setPreferred('EUR');
    expect(currency.preferred()).toBe('EUR');
  });

  test('no storage at all does not throw', () => {
    env.host.storage = () => null;
    currency.initCurrency();
    currency.setPreferred('EUR');
    expect(currency.preferred()).toBe('EUR');
  });

  test('adoptUrlCurrency saves an offered url currency only', () => {
    currency.adoptUrlCurrency(multi, 'EUR');
    expect(currency.preferred()).toBe('EUR');
    currency.adoptUrlCurrency(multi, 'JPY');
    expect(currency.preferred()).toBe('EUR');
  });

  test('refetch needed only when preferred differs from the displayed currency', () => {
    expect(currency.needsCurrencyRefetch(multi, 'EUR')).toBe(true);
    expect(currency.needsCurrencyRefetch(multi, 'USD')).toBe(false);
    expect(currency.needsCurrencyRefetch(multi, 'JPY')).toBe(false);
  });
});

describe('settings', () => {
  const settingsMod = {
    SETTINGS_PATH,
    // a server request has its own controller, made with the request event (the browser has one for the page)
    ensureSettings: (event) =>
      (event
        ? env.controllers.use('market/settings', { event })
        : controller('settings')
      ).actions.ensure(event),
    setSettings: (settings) => actions('settings').set(settings),
    storeSettings: () => state('settings').settings,
  };
  let answer;
  const setup = (options) => {
    answer = { settings: { storeName: 'S' } };
    env = createEnv({ ...options, respond: () => answer });
  };

  test('server: always fetches, with the request event, and never writes the store', async () => {
    setup({ browser: false });
    expect(await settingsMod.ensureSettings({ x: 1 })).toEqual({ storeName: 'S' });
    expect(await settingsMod.ensureSettings()).toEqual({ storeName: 'S' });
    expect(env.requests).toHaveLength(2);
    expect(env.requests[0]).toMatchObject({
      method: 'GET',
      path: `/plugins/pano-plugin-market${settingsMod.SETTINGS_PATH}`,
      event: { x: 1 },
    });
    expect(settingsMod.storeSettings()).toBeNull();
    settingsMod.setSettings({ storeName: 'X' });
    expect(settingsMod.storeSettings()).toBeNull();
  });

  test('browser: fetches once, concurrent callers share one request', async () => {
    setup();
    const [a, b] = await Promise.all([settingsMod.ensureSettings(), settingsMod.ensureSettings()]);
    expect(a).toBe(b);
    expect(await settingsMod.ensureSettings()).toEqual({ storeName: 'S' });
    expect(env.requests).toHaveLength(1);
    expect(settingsMod.storeSettings()).toEqual({ storeName: 'S' });
  });

  test('browser: setSettings stores what a page already fetched', async () => {
    setup();
    settingsMod.setSettings({ storeName: 'Page' });
    expect(settingsMod.storeSettings()).toEqual({ storeName: 'Page' });
    expect(await settingsMod.ensureSettings()).toEqual({ storeName: 'Page' });
    expect(env.requests).toHaveLength(0);
  });

  test('failure resolves to null and is retried next time', async () => {
    setup();
    answer = { error: { code: 'NETWORK_ERROR' } };
    expect(await settingsMod.ensureSettings()).toBeNull();
    answer = { settings: { storeName: 'S' } };
    expect(await settingsMod.ensureSettings()).toEqual({ storeName: 'S' });
    expect(env.requests).toHaveLength(2);
  });
});

describe('clock', () => {
  const clock = {
    now: { subscribe: (run) => controller('clock').subscribe((st) => run(st.now)) },
  };

  test('server value is 0 and nothing ticks', () => {
    env = createEnv({ browser: false });
    const values = [];
    const stop = clock.now.subscribe((v) => values.push(v));
    stop();
    expect(values).toEqual([0]);
  });

  test('browser: ticks while subscribed and stops after', async () => {
    env = createEnv();
    const values = [];
    const stop = clock.now.subscribe((v) => values.push(v));
    expect(values[0]).toBeGreaterThan(0);
    stop();
    const count = values.length;
    await new Promise((r) => setTimeout(r, 30));
    expect(values).toHaveLength(count);
  });

  test('browser: the value follows host.now', () => {
    env = createEnv();
    env.nowValue = 1234;
    const values = [];
    const stop = clock.now.subscribe((v) => values.push(v));
    stop();
    expect(values).toEqual([1234]);
  });
});
