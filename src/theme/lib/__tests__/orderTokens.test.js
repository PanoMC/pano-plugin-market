import { afterEach, beforeEach, describe, expect, test } from 'bun:test';
import * as orderTokens from '../../stores/orderTokens.js';
import { ORDER_TOKEN_PREFIX } from '../checkoutSubmit.js';

const ID = 'AbCdEfGhIjKlMnOpQrSt';

function fakeStorage(over = {}) {
  const map = new Map();
  return {
    map,
    getItem: (k) => (map.has(k) ? map.get(k) : null),
    setItem: (k, v) => void map.set(k, String(v)),
    removeItem: (k) => void map.delete(k),
    ...over,
  };
}

let original;
beforeEach(() => {
  original = Object.getOwnPropertyDescriptor(globalThis, 'sessionStorage');
});
afterEach(() => {
  if (original) Object.defineProperty(globalThis, 'sessionStorage', original);
  else delete globalThis.sessionStorage;
});

const install = (storage) =>
  Object.defineProperty(globalThis, 'sessionStorage', {
    value: storage,
    configurable: true,
    writable: true,
  });

describe('orderTokens', () => {
  test('key format is pano-plugin-market-order:<publicId> and equals the checkout writer prefix', () => {
    expect(orderTokens.ORDER_TOKEN_PREFIX).toBe('pano-plugin-market-order:');
    expect(orderTokens.ORDER_TOKEN_PREFIX).toBe(ORDER_TOKEN_PREFIX);
    expect(orderTokens.orderTokenKey(ID)).toBe(`pano-plugin-market-order:${ID}`);
  });

  test('save, get, remove use sessionStorage, one key per order', () => {
    const store = fakeStorage();
    install(store);
    expect(orderTokens.save(ID, 'tok-1')).toBe(true);
    expect(orderTokens.save('Other0000000000000000', 'tok-2')).toBe(true);
    expect(store.map.get(`pano-plugin-market-order:${ID}`)).toBe('tok-1');
    expect(orderTokens.get(ID)).toBe('tok-1');
    orderTokens.remove(ID);
    expect(orderTokens.get(ID)).toBeNull();
    expect(orderTokens.get('Other0000000000000000')).toBe('tok-2');
  });

  test('never touches localStorage', () => {
    const local = fakeStorage();
    Object.defineProperty(globalThis, 'localStorage', {
      value: local,
      configurable: true,
      writable: true,
    });
    install(fakeStorage());
    orderTokens.save(ID, 'tok-1');
    expect(local.map.size).toBe(0);
    delete globalThis.localStorage;
  });

  test('refuses an unusable token or id', () => {
    const store = fakeStorage();
    install(store);
    for (const token of ['', 'a b', 'x\ny', null, undefined, 'x'.repeat(300)])
      expect(orderTokens.save(ID, token)).toBe(false);
    expect(orderTokens.save('', 'tok')).toBe(false);
    expect(store.map.size).toBe(0);
  });

  test('a poisoned stored value reads as no token', () => {
    install(fakeStorage());
    sessionStorage.setItem(orderTokens.orderTokenKey(ID), 'bad value\r\n');
    expect(orderTokens.get(ID)).toBeNull();
  });

  test('no storage (SSR) and throwing storage never throw', () => {
    delete globalThis.sessionStorage;
    expect(orderTokens.save(ID, 'tok')).toBe(false);
    expect(orderTokens.get(ID)).toBeNull();
    expect(() => orderTokens.remove(ID)).not.toThrow();

    const boom = () => {
      throw new Error('blocked');
    };
    install(fakeStorage({ getItem: boom, setItem: boom, removeItem: boom }));
    expect(orderTokens.save(ID, 'tok')).toBe(false);
    expect(orderTokens.get(ID)).toBeNull();
    expect(() => orderTokens.remove(ID)).not.toThrow();
  });

  test('tokenHeaders prefers the explicit token, then the stored one, else nothing', () => {
    install(fakeStorage());
    expect(orderTokens.tokenHeaders(ID)).toBeUndefined();
    orderTokens.save(ID, 'stored');
    expect(orderTokens.tokenHeaders(ID)).toEqual({ 'X-Order-Token': 'stored' });
    expect(orderTokens.tokenHeaders(ID, 'explicit')).toEqual({ 'X-Order-Token': 'explicit' });
    expect(orderTokens.tokenHeaders(ID, 'bad token')).toEqual({ 'X-Order-Token': 'stored' });
  });
});
