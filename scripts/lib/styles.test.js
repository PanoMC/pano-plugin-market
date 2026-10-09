import { afterEach, describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { STYLE_ATTR_FILES, STYLE_RULES, lintStyles } from './styles.js';

const dirs = [];

// A throw-away plugin root with the given view files (paths below src/theme/components).
async function lint(files) {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'market-styles-'));
  dirs.push(dir);
  for (const [file, content] of Object.entries(files)) {
    const full = path.join(dir, 'src/theme/components', file);
    fs.mkdirSync(path.dirname(full), { recursive: true });
    fs.writeFileSync(full, content);
  }
  return lintStyles(dir);
}

afterEach(() => {
  while (dirs.length) fs.rmSync(dirs.pop(), { recursive: true, force: true });
});

describe('the kit style lint at badge level', () => {
  test('the style= allow-list is the three views of pano.plugin.js', () => {
    expect([...STYLE_ATTR_FILES].sort()).toEqual([
      'CategoryNode',
      'GoalWidget',
      'PaymentMethodPicker',
    ]);
    expect(STYLE_RULES).toEqual(['style-block-scope', 'style-attr']);
  });

  test('a view with a root class, Bootstrap classes and part classes is clean', async () => {
    const found = await lint({
      'a/Card.svelte':
        '<div class="market-card card"><h5 class="market-card__title card-title">x</h5></div>\n',
    });
    expect(found).toEqual([]);
  });

  test('a missing root class, an own class outside the namespace and a <style> block fail', async () => {
    const found = await lint({
      'a/Card.svelte':
        '<div class="card my-custom">x</div>\n<style>.card { color: red; }</style>\n',
    });
    const rules = found.map((f) => f.rule);
    expect(rules).toContain('root-class');
    expect(rules).toContain('class-allowed');
    expect(rules).toContain('style-block-scope');
    expect(found.every((f) => f.level === 'error' || f.level === 'warn')).toBe(true);
  });

  test('style= outside the allow-list fails, inside it passes', async () => {
    const bad = await lint({
      'a/Box.svelte': '<div class="market-box" style="width: 3px"></div>\n',
    });
    expect(bad.map((f) => f.rule)).toContain('style-attr');
    const ok = await lint({
      'a/GoalWidget.svelte': '<div class="market-goal-widget" style="width: 3px"></div>\n',
    });
    expect(ok.map((f) => f.rule)).not.toContain('style-attr');
  });

  test('a Bootstrap component element without a part class fails part-coverage', async () => {
    const found = await lint({
      'a/Note.svelte': '<div class="market-note"><div class="alert alert-info">x</div></div>\n',
    });
    expect(found.map((f) => f.rule)).toContain('part-coverage');
  });
});
