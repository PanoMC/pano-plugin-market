// Pure state helpers of the products list (13 §8.11): URL filters, the status and kind groups, the
// badges of the Type column. No Svelte, no SDK import.
import { compact } from '../orders/filters.js';

/** URL names forwarded to GET /products (page is handled by loadList). */
export const PRODUCT_PARAMS = ['search', 'status', 'kind', 'categoryId'];

export const STATUS_FILTERS = ['ACTIVE', 'INACTIVE', 'ARCHIVED'];
export const KIND_FILTERS = ['STANDARD', 'BUNDLE', 'CREDIT_PACK'];

const present = (value) => value !== null && value !== undefined && String(value).trim() !== '';

/** Filters from the load() data; unknown status / kind values and a non-numeric category are dropped. */
export function normalizeFilters(filters = {}) {
  const status = String(filters?.status ?? '');
  const kind = String(filters?.kind ?? '');
  const categoryId = String(filters?.categoryId ?? '');
  return {
    search: present(filters?.search) ? String(filters.search) : '',
    status: STATUS_FILTERS.includes(status) ? status : '',
    kind: KIND_FILTERS.includes(kind) ? kind : '',
    categoryId: /^\d+$/.test(categoryId) ? categoryId : '',
  };
}

/** Query of the list URL (no `page`: a filter or search change always goes back to page 1). */
export function listParams(filters, overrides = {}) {
  return compact({ ...normalizeFilters(filters), ...overrides });
}

/** True when a filter other than search is set (the "Clear Filters" button is shown). */
export function hasExtraFilters(filters) {
  const f = normalizeFilters(filters);
  return f.kind !== '' || f.categoryId !== '';
}

/**
 * Badges of the Type column: `{ key, label }` where `key` is a locale key below the plugin root.
 * Kind first, then Physical, the billing mode when it is not a one-time purchase, then Variants.
 */
export function typeBadges(product) {
  const badges = [{ key: `enums.product-kind.${product.kind ?? 'STANDARD'}`, tone: 'primary' }];
  if (product.physical) badges.push({ key: 'pages.products.badge.physical', tone: 'secondary' });
  if (product.billingMode && product.billingMode !== 'ONE_TIME')
    badges.push({ key: `enums.billing-mode.${product.billingMode}`, tone: 'info' });
  if (product.hasVariants) badges.push({ key: 'pages.products.badge.variants', tone: 'secondary' });
  return badges;
}

/** Badge class of a product status. */
export function statusTone(status) {
  if (status === 'ACTIVE') return 'success';
  if (status === 'ARCHIVED') return 'dark';
  return 'secondary';
}

/** Stock cell: 'unlimited' for null / undefined, otherwise the number. */
export function stockCell(product) {
  return product.stock === null || product.stock === undefined
    ? { unlimited: true, count: null }
    : { unlimited: false, count: Number(product.stock) };
}

/** Page to show after a delete: one step back when the last row of a later page was removed. */
export function pageAfterDelete(rowsOnPage, currentPage) {
  return rowsOnPage === 1 && currentPage > 1 ? currentPage - 1 : currentPage;
}
