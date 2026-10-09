import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { router, PAGES } from './router.js';

// The preview router answers the paths of the plugin on the v1 API: the `relative` column of the old to new path list (paths
// relative to the API root, what the plugin-scoped client sends to the seam), not the old ones.
const pairs = JSON.parse(
  readFileSync(path.join(import.meta.dir, '..', '..', 'api', 'paths.old-new.json'), 'utf8'),
);
const shape = (path) => path.replace(/:[A-Za-z]+/g, ':');
const known = new Set(pairs.map((p) => `${p.method} ${shape(p.new.replace(/^\/api/, ""))}`));

describe('preview router paths', () => {
  test('every fixture route is a route of the plugin on the v1 API', () => {
    const unknown = router.routes
      .filter((r) => !known.has(`${r.method} ${shape(r.path)}`))
      .map((r) => `${r.method} ${r.path}`);
    expect(unknown).toEqual([]);
  });

  test('site routes sit under /plugins/<id>, panel routes under /plugins/<id>/panel', () => {
    for (const route of router.routes) {
      expect(route.path).toMatch(/^\/plugins\/pano-plugin-market\//);
      expect(route.path).not.toMatch(/^\/api/);
    }
  });

  test('a path with the old /api prefix or a missing prefix answers nothing', () => {
    // the seam gets paths relative to the API root: the absolute form and the unscoped form are not fixtures
    expect(router.answer('GET', '/api/plugins/pano-plugin-market/store', 'few')).toBeUndefined();
    expect(router.answer('GET', '/market/store', 'few')).toBeUndefined();
    expect(router.answer('GET', '/store', 'few')).toBeUndefined();
    expect(router.answer('GET', '/plugins/pano-plugin-market/store', 'few')).toBeDefined();
  });

  test('every list answers items and page, every failure the envelope', () => {
    for (const route of router.routes) {
      if (route.method !== 'GET' || route.path.includes(':')) continue;
      for (const volume of ['empty', 'few', 'many']) {
        const body = router.answer('GET', route.path, volume);
        expect(body).not.toHaveProperty('result');
        expect(body).not.toHaveProperty('totalPage');
        if (body.error !== undefined) {
          expect(Object.keys(body)).toEqual(['error']);
          expect(typeof body.error.code).toBe('string');
        }
        if (body.items !== undefined && body.page !== undefined)
          expect(typeof body.page.totalItems).toBe('number');
      }
    }
  });

  test('no fixture answers a list under a key other than items', () => {
    // a list answer is `{ items }` or `{ items, page }`; a body whose only content is one array under another key is a list
    // that kept its old name (composite answers such as health, widgets or the store home carry several keys)
    for (const route of router.routes) {
      if (route.method !== 'GET' || route.path.includes(':')) continue;
      for (const volume of ['empty', 'few', 'many']) {
        const body = router.answer('GET', route.path, volume);
        if (!body || typeof body !== 'object' || Array.isArray(body)) continue;
        const keys = Object.keys(body).filter((k) => k !== 'page');
        const named = keys.length === 1 && keys[0] !== 'items' && Array.isArray(body[keys[0]]);
        expect({ path: route.path, named }).toEqual({ path: route.path, named: false });
      }
    }
  });

  test('the drawer pages exist', () => {
    expect(PAGES.length).toBeGreaterThan(10);
  });
});
