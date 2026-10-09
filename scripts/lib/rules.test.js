import { describe, expect, test } from 'bun:test';
import { placeholders } from './icu.js';
import { compareLocales, keyResolves, usedKeys } from './i18n-rules.js';
import { classTokens, hardcodedTexts, parseTags, splitSvelte, topLevelGlobals } from './scan.js';
import { checkJsFile, checkSvelteFile } from './static-rules.js';

const allowlist = {
  entries: [
    { file: 'src/panel/Ok.svelte', wrapper: null },
    { file: 'Wrapped.svelte', wrapper: 'clean' },
  ],
  allowedTexts: ['ID'],
};
const svelte = (file, src) => checkSvelteFile(file, src, { allowlist }).map((v) => v.rule);

describe('icu placeholders', () => {
  test('plain, plural and nested', () => {
    expect(
      placeholders('Hi {name}, {count, plural, one {# item of {shop}} other {# items}}'),
    ).toEqual(['count', 'name', 'shop']);
    expect(placeholders('no vars')).toEqual([]);
  });
});

describe('locale comparison', () => {
  const m = (o) => new Map(Object.entries(o));
  test('identical sets pass', () => {
    const maps = { tr: m({ a: 'x {n}' }), 'en-US': m({ a: 'y {n}' }), ru: m({ a: 'z {n}' }) };
    expect(compareLocales(maps, 'f')).toEqual([]);
  });
  test('missing ru key, empty value and placeholder drift fail', () => {
    const maps = {
      tr: m({ a: 'x', b: '' }),
      'en-US': m({ a: 'y {n}', b: 'q' }),
      ru: m({ a: 'z' }),
    };
    const p = compareLocales(maps, 'f').join('\n');
    expect(p).toContain("key 'a' missing in ru".replace('a', 'b'));
    expect(p).toContain('empty or non-string value');
    expect(p).toContain("placeholders of 'a'");
  });
  test('a whitespace-only separator passes, an empty one and other blanks fail', () => {
    const maps = (sep, other) => ({
      tr: m({ 'server-format.group-separator': '.', x: 'a' }),
      'en-US': m({ 'server-format.group-separator': ',', x: 'a' }),
      ru: m({ 'server-format.group-separator': sep, x: other }),
    });
    expect(compareLocales(maps('\u00a0', 'a'), 'f')).toEqual([]);
    expect(compareLocales(maps('', 'a'), 'f').join('\n')).toContain('empty or non-string');
    expect(compareLocales(maps('\u00a0', ' '), 'f').join('\n')).toContain('empty or non-string');
  });
  test('used keys', () => {
    const src = "$_('a.b') $_(`x.${y}`) $_('dyn.' + k) $_('plugins.pano-plugin-market.c', {})";
    expect(usedKeys(src).map((k) => k.key)).toEqual(['a.b', 'c']);
    expect(keyResolves('errors.NOPE', new Map())).toBe(true);
    expect(keyResolves('nope.x', new Map())).toBe(false);
  });
});

describe('static rules', () => {
  test('clean runes file passes', () => {
    expect(
      svelte(
        'a.svelte',
        '<script>let { a } = $props();</script>\n<button onclick={() => a()}>{a}</button>',
      ),
    ).toEqual([]);
  });
  test('export let, $:, on:click, dispatcher, slot', () => {
    expect(
      svelte(
        'a.svelte',
        '<script>export let a;\n$: b = a;\nimport { createEventDispatcher } from "svelte";</script>',
      ),
    ).toEqual(['runes', 'runes', 'runes']);
    expect(svelte('a.svelte', '<button on:click={f}>{t}</button>')).toEqual(['runes']);
    expect(svelte('a.svelte', '<Pagination on:pageLinkClick={f} />')).toEqual([]);
    expect(svelte('a.svelte', '<slot />')).toEqual(['runes']);
  });
  test('html sinks need the allow-list and the wrapper', () => {
    expect(svelte('x.svelte', '<div>{@html a}</div>')).toEqual(['html']);
    expect(svelte('src/panel/Ok.svelte', '<div>{@html a}</div>')).toEqual([]);
    expect(svelte('src/Wrapped.svelte', '<div>{@html clean(a)}</div>')).toEqual([]);
    expect(svelte('src/Wrapped.svelte', '<div>{@html a}</div>')).toEqual(['html']);
  });
  test('hard-coded text', () => {
    expect(svelte('a.svelte', '<p>Hello</p>')).toEqual(['text']);
    expect(svelte('a.svelte', '<p>{$_("k")} <b>{a > 1 ? "x" : "y"}</b> &nbsp;</p>')).toEqual([]);
    expect(svelte('a.svelte', '<p>ID: #{a}</p>')).toEqual([]);
    expect(hardcodedTexts('{#if a}<i class="x"></i>{:else}{b}{/if}')).toEqual([]);
  });
  test('the class and style rules of the theme side are the kit lint (styles.test.js), not a static rule', () => {
    expect(svelte('t.svelte', '<style>a{}</style><div class="my-custom">{a}</div>')).toEqual([]);
  });
  test('storage at module top level', () => {
    expect(checkJsFile('a.js', "const v = localStorage.getItem('k');").map((v) => v.rule)).toEqual([
      'storage',
    ]);
    expect(
      checkJsFile('a.js', "export function f() { return localStorage.getItem('k'); }"),
    ).toEqual([]);
    expect(checkJsFile('a.js', "export const f = () => localStorage.getItem('k');")).toEqual([]);
    expect(checkJsFile('a.js', "// localStorage in a comment\nconst s = 'localStorage';")).toEqual(
      [],
    );
    expect(svelte('a.svelte', '<script module>const v = localStorage.x;</script>')).toEqual([
      'storage',
    ]);
    expect(svelte('a.svelte', '<script>const v = localStorage.x;</script>')).toEqual([]);
    expect(topLevelGlobals('if (a) { window.x }', ['window'])).toEqual([]);
  });
  test('scanner helpers', () => {
    expect(parseTags('<a href="x>y" on:click={() => a > b}>t</a>').map((t) => t.name)).toEqual([
      'a',
    ]);
    expect(classTokens(' class="a {b} c" class:d={e}')).toEqual(['a', 'c', 'd']);
    expect(splitSvelte('<script module>1</script><style>a</style>x').scripts[0].module).toBe(true);
  });
});
