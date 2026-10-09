import { afterEach, describe, expect, test } from 'bun:test';
import { createApi, PREVIEW_HOOK } from '../api.js';

const host = (calls) => ({
  request: async (req) => {
    calls.push(req);
    return { done: true };
  },
});

afterEach(() => {
  delete globalThis[PREVIEW_HOOK];
});

describe('theme api in preview mode', () => {
  test('without the hook every call goes to the host', async () => {
    const calls = [];
    const r = await createApi(host(calls)).apiGet('/store');
    expect(calls.map((c) => c.path)).toEqual(['/plugins/pano-plugin-market/store']);
    expect(r.ok).toBe(true);
  });

  test('a hook that answers keeps the call off the host', async () => {
    const calls = [];
    globalThis[PREVIEW_HOOK] = async (method, path) => ({ items: [{ method, path }] });
    const r = await createApi(host(calls)).apiGet('/me/addresses');
    expect(calls).toEqual([]);
    expect(r.ok).toBe(true);
    expect(r.items[0].path).toBe('/plugins/pano-plugin-market/me/addresses');
  });

  test('a hook that returns undefined lets the call through', async () => {
    const calls = [];
    globalThis[PREVIEW_HOOK] = async () => undefined;
    await createApi(host(calls)).post('/cart/items', { body: { a: 1 } });
    expect(calls).toHaveLength(1);
  });
});
