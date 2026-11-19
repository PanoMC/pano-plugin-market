import { describe, expect, test } from 'bun:test';
import { productHref } from './product-link.js';

describe('productHref', () => {
  test('links the edit page for a catalog user', () => {
    expect(productHref(12, { admin: true })).toBe('/market/products/create-product?id=12');
    expect(productHref('7', { admin: true })).toBe('/market/products/create-product?id=7');
  });
  test('no id or no catalog node: no link', () => {
    expect(productHref(null, { admin: true })).toBe(null);
    expect(productHref(0, { admin: true })).toBe(null);
    expect(productHref(12, null)).toBe(null);
    expect(productHref(12, { permissions: [] })).toBe(null);
  });
});
