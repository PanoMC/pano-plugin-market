import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import { PLUGIN_API_PREFIX, PLUGIN_ID, SITE_API_ROOT, hostPath, siteUrl } from '../paths.js';

describe('market site paths', () => {
  test('the prefix is the full plugin id, never the namespace', () => {
    expect(PLUGIN_ID).toBe('pano-plugin-market');
    expect(PLUGIN_API_PREFIX).toBe('/plugins/pano-plugin-market');
    expect(SITE_API_ROOT).toBe('/api/plugins/pano-plugin-market');
  });

  test('hostPath is what follows /api/v1, siteUrl is the absolute path', () => {
    expect(hostPath('/store/products')).toBe('/plugins/pano-plugin-market/store/products');
    expect(hostPath('store')).toBe('/plugins/pano-plugin-market/store');
    expect(siteUrl('/products/image/a.png')).toBe(
      '/api/plugins/pano-plugin-market/products/image/a.png',
    );
  });

  test('the plugin id is the one of gradle.properties', () => {
    const props = fs.readFileSync(
      path.resolve(import.meta.dir, '../../../../gradle.properties'),
      'utf8',
    );

    expect(props).toMatch(new RegExp(`^pluginId=${PLUGIN_ID}$`, 'm'));
  });

  test('every market-relative path the theme writes is a route of the committed old/new list', () => {
    const pairs = JSON.parse(
      fs.readFileSync(path.resolve(import.meta.dir, '../../../../api/paths.old-new.json'), 'utf8'),
    );
    const known = pairs
      .map((p) => p.relative)
      .filter((r) => r.startsWith(PLUGIN_API_PREFIX))
      .map((r) => r.slice(PLUGIN_API_PREFIX.length));
    const asRegex = (route) =>
      new RegExp(`^${route.replace(/:[A-Za-z]+/g, '[^/]+').replace(/\//g, '\\/')}$`);
    const root = path.resolve(import.meta.dir, '../..');
    const used = new Set();
    const walk = (dir) => {
      for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);

        if (entry.isDirectory()) {
          if (entry.name !== '__tests__') walk(full);
        } else if (/\.(js|svelte)$/.test(entry.name) && !entry.name.endsWith('.test.js')) {
          const source = fs.readFileSync(full, 'utf8');

          for (const m of source.matchAll(
            /\b(?:call|apiGet|post|put|del)\(\s*(?:'(?:GET|POST|PUT|DELETE)',\s*)?(['`])(\/[^'`?]*)\1/g,
          ))
            used.add(m[2].replace(/\$\{[^}]*\}/g, 'X'));

          // RETURN_PATH is a page route of the site, not an API path
          for (const m of source.matchAll(/(?<!RETURN)_PATH\s*=\s*(['`])(\/[^'`?]*)\1/g))
            used.add(m[2].replace(/\$\{[^}]*\}/g, 'X'));
        }
      }
    };

    walk(root);

    expect(used.size).toBeGreaterThan(10);

    for (const route of used)
      expect(
        known.some((k) => asRegex(k).test(route)),
        route,
      ).toBe(true);
  });
});
