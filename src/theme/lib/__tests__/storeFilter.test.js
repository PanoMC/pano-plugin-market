import { describe, expect, test } from 'bun:test';
import {
  DEFAULT_FILTER,
  canonicalQuery,
  createSequencer,
  categoryIds,
  flattenCategories,
  indentClass,
  isDefaultFilter,
  isLatest,
  listQuery,
  normalizeSearch,
  pagerItems,
  storeSearch,
  parseCurrency,
  parseFilter,
  withChange,
  withoutPageParam,
} from '../storeFilter.js';

const params = (query) => new URLSearchParams(query);

describe('normalizeSearch', () => {
  test('trims, caps at 64 and treats one character as empty', () => {
    expect(normalizeSearch('  sword ')).toBe('sword');
    expect(normalizeSearch('a')).toBe('');
    expect(normalizeSearch(' a ')).toBe('');
    expect(normalizeSearch('ab')).toBe('ab');
    expect(normalizeSearch('x'.repeat(100))).toHaveLength(64);
    expect(normalizeSearch(null)).toBe('');
  });
});

describe('parseFilter', () => {
  test('defaults', () => {
    expect(parseFilter(params(''))).toEqual(DEFAULT_FILTER);
    expect(parseFilter(undefined)).toEqual(DEFAULT_FILTER);
  });

  test('reads every parameter', () => {
    expect(parseFilter(params('category=4&search=rank&sort=newest&page=3'))).toEqual({
      category: 4,
      search: 'rank',
      sort: 'newest',
      page: 3,
    });
  });

  test('rejects garbage', () => {
    const f = parseFilter(params('category=abc&sort=hax&page=0&search=a'));
    expect(f).toEqual(DEFAULT_FILTER);
    expect(parseFilter(params('category=-1&page=1.5')).category).toBe(null);
    expect(parseFilter(params('page=1.5')).page).toBe(1);
    expect(parseFilter(params('category=1e3')).category).toBe(null);
  });
});

describe('parseCurrency', () => {
  test('only upper-case ISO codes', () => {
    expect(parseCurrency(params('currency=EUR'))).toBe('EUR');
    expect(parseCurrency(params('currency=eur'))).toBe(null);
    expect(parseCurrency(params('currency=EURO'))).toBe(null);
    expect(parseCurrency(params(''))).toBe(null);
  });
});

describe('canonicalQuery', () => {
  test('default filter has no query', () => {
    expect(canonicalQuery(DEFAULT_FILTER)).toBe('');
    expect(isDefaultFilter(DEFAULT_FILTER)).toBe(true);
  });

  test('only non-default values, in the order category, search, sort, page', () => {
    expect(canonicalQuery({ page: 2, sort: 'newest', search: 'a b', category: 7 })).toBe(
      'category=7&search=a%20b&sort=newest&page=2',
    );
    expect(canonicalQuery({ ...DEFAULT_FILTER, sort: 'priority', page: 1 })).toBe('');
    expect(canonicalQuery({ ...DEFAULT_FILTER, page: 3 })).toBe('page=3');
  });

  test('isDefaultFilter notices every field', () => {
    expect(isDefaultFilter({ ...DEFAULT_FILTER, category: 1 })).toBe(false);
    expect(isDefaultFilter({ ...DEFAULT_FILTER, search: 'ab' })).toBe(false);
    expect(isDefaultFilter({ ...DEFAULT_FILTER, sort: 'newest' })).toBe(false);
    expect(isDefaultFilter({ ...DEFAULT_FILTER, page: 2 })).toBe(false);
  });
});

describe('listQuery', () => {
  test('keeps page 1 and the sort, leaves empty values undefined', () => {
    expect(listQuery(DEFAULT_FILTER, null)).toEqual({
      category: undefined,
      search: undefined,
      sort: 'priority',
      page: 1,
      currency: undefined,
    });
    expect(listQuery({ category: 3, search: 'ab', sort: 'newest', page: 2 }, 'EUR')).toEqual({
      category: 3,
      search: 'ab',
      sort: 'newest',
      page: 2,
      currency: 'EUR',
    });
  });
});

describe('withChange', () => {
  const base = { category: 1, search: 'ab', sort: 'newest', page: 4 };

  test('category, sort and search reset the page', () => {
    expect(withChange(base, { category: 2 }).page).toBe(1);
    expect(withChange(base, { sort: 'price-asc' }).page).toBe(1);
    expect(withChange(base, { search: 'cd' }).page).toBe(1);
  });

  test('a page change keeps everything else', () => {
    expect(withChange(base, { page: 5 })).toEqual({ ...base, page: 5 });
  });

  test('a search of one character becomes empty', () => {
    expect(withChange(base, { search: 'x' }).search).toBe('');
  });

  test('does not mutate its input', () => {
    withChange(base, { category: null });
    expect(base.category).toBe(1);
  });
});

describe('categories', () => {
  const tree = [
    {
      id: 1,
      name: 'Ranks',
      productsCount: 3,
      children: [
        {
          id: 2,
          name: 'VIP',
          children: [{ id: 3, name: 'Gold', children: [{ id: 4, name: 'Deep' }] }],
        },
      ],
    },
    { id: 5, name: 'Keys', icon: 'fa-key', color: '#ff0000' },
  ];

  test('flattens depth first with the depth', () => {
    expect(flattenCategories(tree).map((r) => [r.id, r.depth])).toEqual([
      [1, 0],
      [2, 1],
      [3, 2],
      [4, 3],
      [5, 0],
    ]);
    expect(flattenCategories(tree)[4]).toMatchObject({
      icon: 'fa-key',
      color: '#ff0000',
      productsCount: 0,
    });
    expect(flattenCategories(undefined)).toEqual([]);
  });

  test('collects ids', () => {
    expect([...categoryIds(tree)].sort()).toEqual([1, 2, 3, 4, 5]);
  });

  test('indent classes: depth 3 and deeper use ps-5', () => {
    expect([0, 1, 2, 3, 6].map(indentClass)).toEqual(['', 'ps-3', 'ps-4', 'ps-5', 'ps-5']);
  });
});

describe('pagerItems', () => {
  const shape = (items) =>
    items.map((i) =>
      i.type === 'gap'
        ? '...'
        : i.type === 'page'
          ? i.current
            ? `[${i.page}]`
            : String(i.page)
          : i.type,
    );

  test('few pages', () => {
    expect(shape(pagerItems(1, 3))).toEqual(['prev', '[1]', '2', '3', 'next']);
  });

  test('previous / next are disabled at the ends', () => {
    const first = pagerItems(1, 3);
    expect(first[0]).toMatchObject({ type: 'prev', disabled: true });
    expect(first.at(-1)).toMatchObject({ type: 'next', disabled: false, page: 2 });
    const last = pagerItems(3, 3);
    expect(last.at(-1)).toMatchObject({ type: 'next', disabled: true });
    expect(last[0]).toMatchObject({ type: 'prev', disabled: false, page: 2 });
  });

  test('window of 2 around the current page with gaps', () => {
    expect(shape(pagerItems(10, 20))).toEqual([
      'prev',
      '1',
      '...',
      '8',
      '9',
      '[10]',
      '11',
      '12',
      '...',
      '20',
      'next',
    ]);
  });

  test('a gap that would hide one page shows that page', () => {
    expect(shape(pagerItems(5, 9))).toEqual([
      'prev',
      '1',
      '2',
      '3',
      '4',
      '[5]',
      '6',
      '7',
      '8',
      '9',
      'next',
    ]);
    expect(shape(pagerItems(4, 9))).toEqual([
      'prev',
      '1',
      '2',
      '3',
      '[4]',
      '5',
      '6',
      '...',
      '9',
      'next',
    ]);
  });

  test('near the start and end', () => {
    expect(shape(pagerItems(1, 20))).toEqual(['prev', '[1]', '2', '3', '...', '20', 'next']);
    expect(shape(pagerItems(20, 20))).toEqual(['prev', '1', '...', '18', '19', '[20]', 'next']);
  });

  test('out-of-range input is clamped', () => {
    expect(shape(pagerItems(99, 3))).toEqual(['prev', '1', '2', '[3]', 'next']);
    expect(shape(pagerItems(0, 0))).toEqual(['prev', '[1]', 'next']);
  });
});

describe('misc', () => {
  test('isLatest', () => {
    expect(isLatest(3, 3)).toBe(true);
    expect(isLatest(2, 3)).toBe(false);
  });

  test('withoutPageParam keeps every other parameter', () => {
    expect(withoutPageParam('/store?category=2&page=9&sort=newest')).toBe(
      '/store?category=2&sort=newest',
    );
    expect(withoutPageParam('/store?page=9')).toBe('/store');
    expect(withoutPageParam('/store')).toBe('/store');
  });
});

describe('createSequencer', () => {
  test('a filter change during a currency reload keeps the store refresh', () => {
    const seq = createSequencer();
    const reload = seq.beginReload();
    const filterTicket = seq.beginGrid(); // category click while the reload is in flight

    expect(seq.isStoreLatest(reload.store)).toBe(true); // settings, cards, first page still apply
    expect(seq.isGridLatest(reload.grid)).toBe(false); // the reload no longer owns the grid
    expect(seq.isGridLatest(filterTicket)).toBe(true);
  });

  test('an older reload is dropped completely', () => {
    const seq = createSequencer();
    const first = seq.beginReload();
    const second = seq.beginReload();

    expect(seq.isStoreLatest(first.store)).toBe(false);
    expect(seq.isGridLatest(first.grid)).toBe(false);
    expect(seq.isStoreLatest(second.store)).toBe(true);
    expect(seq.isGridLatest(second.grid)).toBe(true);
  });

  test('a reload supersedes an older filter request', () => {
    const seq = createSequencer();
    const filterTicket = seq.beginGrid();
    const reload = seq.beginReload();

    expect(seq.isGridLatest(filterTicket)).toBe(false);
    expect(seq.isGridLatest(reload.grid)).toBe(true);
  });

  test('a newer filter request supersedes an older one', () => {
    const seq = createSequencer();
    const a = seq.beginGrid();
    const b = seq.beginGrid();

    expect(seq.isGridLatest(a)).toBe(false);
    expect(seq.isGridLatest(b)).toBe(true);
  });

  test('invalidate drops everything in flight', () => {
    const seq = createSequencer();
    const reload = seq.beginReload();
    const filterTicket = seq.beginGrid();
    seq.invalidate();

    expect(seq.isStoreLatest(reload.store)).toBe(false);
    expect(seq.isGridLatest(filterTicket)).toBe(false);
  });
});

describe('storeSearch', () => {
  const filter = { category: 3, search: 'rank', sort: 'newest', page: 2 };

  test('keeps ?currency= while the URL pins it', () => {
    expect(storeSearch(filter, 'EUR')).toBe('category=3&search=rank&sort=newest&page=2&currency=EUR');
    expect(storeSearch({ ...DEFAULT_FILTER }, 'EUR')).toBe('currency=EUR');
  });

  test('a changed currency (urlCurrency null) removes the currency parameter', () => {
    expect(storeSearch(filter, null)).toBe('category=3&search=rank&sort=newest&page=2');
    expect(storeSearch({ ...DEFAULT_FILTER }, null)).toBe('');
    expect(storeSearch({ ...DEFAULT_FILTER }, undefined)).toBe('');
  });
});
