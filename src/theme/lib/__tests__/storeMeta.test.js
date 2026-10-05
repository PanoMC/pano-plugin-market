import { describe, expect, test } from 'bun:test';
import { DESCRIPTION_MAX, cutDescription, storeMeta, storePageTitle } from '../storeMeta.js';
import { DEFAULT_FILTER } from '../storeFilter.js';

const origin = 'https://example.com';

describe('cutDescription', () => {
  test('short text is kept, whitespace collapsed', () => {
    expect(cutDescription('  hello \n  world ')).toBe('hello world');
    expect(cutDescription(undefined)).toBe('');
  });

  test('long text is cut at 160 characters on a word boundary', () => {
    const text = `${'word '.repeat(60)}end`;
    const cut = cutDescription(text);
    expect(cut.length).toBeLessThanOrEqual(DESCRIPTION_MAX);
    expect(cut.endsWith('word')).toBe(true);
    expect(cutDescription('x'.repeat(300))).toHaveLength(DESCRIPTION_MAX);
  });

  test('a cut that lands exactly on a space does not drop a word', () => {
    const text = `${'a'.repeat(159)} bbb`;
    expect(cutDescription(text)).toBe('a'.repeat(159));
    const exact = `${'a'.repeat(160)}`;
    expect(cutDescription(exact)).toBe(exact);
  });
});

describe('storeMeta', () => {
  const settings = { storeDescription: 'The best store' };

  test('default view', () => {
    expect(storeMeta({ settings, filter: DEFAULT_FILTER, origin })).toEqual({
      type: 'website',
      description: 'The best store',
      canonical: 'https://example.com/store',
    });
  });

  test('category in the canonical, still indexable', () => {
    const meta = storeMeta({ settings, filter: { ...DEFAULT_FILTER, category: 4 }, origin });
    expect(meta.canonical).toBe('https://example.com/store?category=4');
    expect(meta.robots).toBeUndefined();
  });

  test('search, page > 1 or a sort => noindex,follow', () => {
    for (const change of [{ search: 'ab' }, { page: 2 }, { sort: 'newest' }])
      expect(storeMeta({ settings, filter: { ...DEFAULT_FILTER, ...change }, origin }).robots).toBe(
        'noindex,follow',
      );
  });

  test('no description without one', () => {
    expect(storeMeta({ settings: {}, filter: DEFAULT_FILTER, origin }).description).toBeUndefined();
  });
});

describe('storePageTitle', () => {
  test('plain key without a store name', () => {
    expect(storePageTitle({})).toEqual({ title: 'plugins.pano-plugin-market.theme.store.title' });
    expect(storePageTitle({ storeName: '   ' })).toEqual({
      title: 'plugins.pano-plugin-market.theme.store.title',
    });
  });

  test('the store name and description go through fixed keys with values', () => {
    expect(storePageTitle({ storeName: 'My {shop}', storeDescription: 'Desc' })).toEqual({
      title: 'plugins.pano-plugin-market.theme.store.title-with-name',
      titleValues: { storeName: 'My {shop}' },
      subtitle: 'plugins.pano-plugin-market.theme.store.subtitle',
      subtitleValues: { storeDescription: 'Desc' },
    });
  });

  test('no subtitle without a description', () => {
    expect(storePageTitle({ storeName: 'Shop' }).subtitle).toBeUndefined();
  });
});
