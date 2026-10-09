import { afterEach, describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const script = path.resolve(import.meta.dir, 'check-theme.js');
const dirs = [];

// Builds a throw-away plugin root with the given theme files and runs check-theme.js on it.
function run(files, extraArgs = []) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'check-theme-'));
  dirs.push(dir);
  for (const lang of ['tr', 'en-US', 'ru']) {
    fs.mkdirSync(path.join(dir, 'src/locales/theme'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'src/locales/theme', `${lang}.json`), '{}');
  }
  for (const [file, content] of Object.entries(files)) {
    const full = path.join(dir, 'src/theme', file);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, content);
  }
  const result = Bun.spawnSync(['bun', script, '--root', dir, ...extraArgs]);
  return { code: result.exitCode, out: result.stdout.toString() + result.stderr.toString() };
}

afterEach(() => {
  while (dirs.length) fs.rmSync(dirs.pop(), { recursive: true, force: true });
});

describe('check-theme rules 1 and 3 fail without --strict', () => {
  test('clean tree passes', () => {
    expect(run({ 'components/A.svelte': '<div class="p-2"></div>\n' }).code).toBe(0);
  });

  test('style= outside the allow-list fails', () => {
    const r = run({ 'components/A.svelte': '<div style="width: 3px"></div>\n' });
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('[style-attr]');
    expect(r.out).toContain('styleAttrAllow');
  });

  test('style= in an allow-listed file passes', () => {
    expect(run({ 'components/GoalWidget.svelte': '<div style="width: 3px"></div>\n' }).code).toBe(
      0,
    );
  });

  test('a <style> block fails', () => {
    const r = run({
      'components/A.svelte': '<div class="p-2"></div>\n<style>.a { color: red; }</style>\n',
    });
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('[style-block-scope]');
  });

  test('{@html} outside the allow-list fails', () => {
    const r = run({ 'components/A.svelte': '<div>{@html body}</div>\n' });
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('{@html} outside');
  });

  test('an allow-listed {@html} file without {@html} fails', () => {
    const r = run({ 'pages/ProductPage.svelte': '<div></div>\n' });
    expect(r.code).not.toBe(0);
    expect(r.out).toContain('allow-listed for {@html} but has none');
  });

  test('an allow-listed {@html} file with {@html} passes', () => {
    expect(run({ 'pages/ProductPage.svelte': '<div>{@html body}</div>\n' }).code).toBe(0);
  });
});

describe('check-theme rule 7: market API paths', () => {
  test('relative call paths and full plugin urls pass', () => {
    const files = {
      'lib/a.js': "export const A = call('GET', '/store/products');\n",
      'components/B.svelte':
        '<img src="{base}/api/plugins/pano-plugin-market/products/image/a.png" alt="" />\n',
    };
    expect(run(files).code).toBe(0);
  });

  test('an old /api/market path fails, in code and in markup', () => {
    const code = run({ 'lib/a.js': "export const A = '/api/market/store';\n" });
    expect(code.code).not.toBe(0);
    expect(code.out).toContain("'/api/market/store' is not a market API path");
    const markup = run({
      'components/B.svelte': '<img src="{base}/api/market/products/image/a.png" alt="" />\n',
    });
    expect(markup.code).not.toBe(0);
  });

  test('comments may name the old path', () => {
    expect(run({ 'lib/a.js': "// was '/api/market/store'\nexport const A = 1;\n" }).code).toBe(0);
  });

  test('a path of another api version prefix of the plugin is not a market path either', () => {
    expect(run({ 'lib/a.js': "export const A = '/api/v1/store';\n" }).code).not.toBe(0);
  });
});
