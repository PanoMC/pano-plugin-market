// Readers of the core list shape (04 section 4): `{ items, page: { number, size, totalItems, totalPages }, ...extra }`.
// Pure: no Svelte, no SDK import.

const num = (value, fallback) => (Number.isFinite(Number(value)) ? Number(value) : fallback);

/** The `page` object of a failed or empty list (page 1, nothing in it). */
export const emptyPage = () => ({ number: 1, size: 0, totalItems: 0, totalPages: 0 });

/** The empty list data a failed load returns: the shape of a list with no rows plus the error code. */
export const emptyList = (error, extra = {}) => ({ items: [], page: emptyPage(), error, ...extra });

/**
 * Reads a list answer (or list page data) into plain numbers. A body without `items` / `page` (a failure
 * shape, a not-yet-loaded page) reads as an empty first page, never as an error.
 */
export function pageOf(body) {
  const items = Array.isArray(body?.items) ? body.items : [];
  const page = body?.page && typeof body.page === 'object' ? body.page : {};
  return {
    items,
    number: Math.max(1, num(page.number, 1)),
    size: num(page.size, items.length),
    totalItems: num(page.totalItems, items.length),
    totalPages: num(page.totalPages, 0),
  };
}
