import { describe, expect, test } from 'bun:test';
import { emptyList, emptyPage, pageOf } from './page.js';

describe('pageOf (04 section 4: { items, page })', () => {
  test('reads items and the page object', () => {
    const body = {
      items: [{ id: 1 }, { id: 2 }],
      page: { number: 2, size: 2, totalItems: 5, totalPages: 3 },
      filters: { status: 'X' },
    };
    expect(pageOf(body)).toEqual({
      items: [{ id: 1 }, { id: 2 }],
      number: 2,
      size: 2,
      totalItems: 5,
      totalPages: 3,
    });
  });

  test('an empty result has no pages and page 1', () => {
    const view = pageOf({ items: [], page: { number: 1, size: 10, totalItems: 0, totalPages: 0 } });
    expect(view).toEqual({ items: [], number: 1, size: 10, totalItems: 0, totalPages: 0 });
  });

  test('a body without items / page (failure shape, nothing loaded) is an empty first page', () => {
    for (const body of [
      undefined,
      null,
      {},
      { error: 'NOT_FOUND' },
      '<html>',
      { items: 'x', page: 3 },
    ]) {
      expect(pageOf(body)).toEqual({ items: [], number: 1, size: 0, totalItems: 0, totalPages: 0 });
    }
  });

  test('missing page numbers fall back to the length of items; junk numbers fall back too', () => {
    expect(pageOf({ items: [1, 2, 3] })).toEqual({
      items: [1, 2, 3],
      number: 1,
      size: 3,
      totalItems: 3,
      totalPages: 0,
    });
    expect(pageOf({ items: [], page: { number: 0, totalItems: 'x' } }).number).toBe(1);
  });

  test('emptyList is what pageOf reads as nothing', () => {
    const data = emptyList('NO_PERMISSION', { ctx: null });
    expect(data).toEqual({ items: [], page: emptyPage(), error: 'NO_PERMISSION', ctx: null });
    expect(pageOf(data).totalItems).toBe(0);
  });
});
