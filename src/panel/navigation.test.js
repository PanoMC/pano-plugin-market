import { describe, expect, test } from 'bun:test';
import {
  AREAS,
  SECTIONS,
  activeSection,
  firstHref,
  sectionsFor,
  visibleAreas,
} from './navigation.js';
import { NODE } from './utils/permissions.js';

const user = (...perms) => ({ admin: false, permissions: perms });
const keys = (u) => visibleAreas(u).map((a) => a.key);

describe('navigation (13 25.1 tests 41-44)', () => {
  test('41. OM-only user sees Overview and Customers, Customers href is /market/blocks', () => {
    const u = user(NODE.OM);
    expect(keys(u)).toEqual(['overview', 'customers']);
    expect(firstHref('customers', u)).toBe('/market/blocks');
    expect(visibleAreas(u).find((a) => a.key === 'customers').href).toBe('/market/blocks');
  });

  test('42. PAY-only user: Customers href is /market/credits', () => {
    const u = user(NODE.PAY);
    expect(firstHref('customers', u)).toBe('/market/credits');
    expect(keys(u)).toContain('customers');
  });

  test('43. the umbrella node shows all six areas', () => {
    const u = user(NODE.ALL);
    expect(keys(u)).toEqual(AREAS.map((a) => a.key));
    expect(keys(u)).toHaveLength(6);
    expect(keys({ admin: true })).toHaveLength(6);
  });

  test('44. STATS-only user sees Overview only', () => {
    const u = user(NODE.STATS);
    expect(keys(u)).toEqual(['overview']);
    expect(firstHref('overview', u)).toBe('/market');
    expect(firstHref('orders', u)).toBeNull();
  });

  test('a user without any market node sees nothing', () => {
    expect(visibleAreas(user())).toEqual([]);
    expect(visibleAreas(null)).toEqual([]);
  });

  test('each level-1 href is the first section the user can open', () => {
    expect(firstHref('catalog', user(NODE.CAT))).toBe('/market/products');
    expect(firstHref('orders', user(NODE.OV))).toBe('/market/orders');
    expect(firstHref('discounts', user(NODE.DISC))).toBe('/market/discounts?section=general');
    expect(firstHref('settings', user(NODE.SET))).toBe('/market/settings?section=general');
  });

  test('sections are filtered with can()', () => {
    expect(sectionsFor('customers', user(NODE.OM)).map((s) => s.key)).toEqual(['blocks']);
    expect(sectionsFor('customers', user(NODE.PAY)).map((s) => s.key)).toEqual(['credits']);
    expect(sectionsFor('settings', user(NODE.OV))).toEqual([]);
  });

  test('section declarations are unique per area and carry nav-section labels', () => {
    const seen = new Set();
    for (const s of SECTIONS) {
      const id = `${s.area}/${s.key}`;
      expect(seen.has(id)).toBe(false);
      seen.add(id);
      expect(s.label).toBe(`nav-section-${s.key}`);
      expect(AREAS.some((a) => a.key === s.area)).toBe(true);
    }
  });
});

describe('activeSection', () => {
  const settings = sectionsFor('settings', { admin: true });
  const discounts = sectionsFor('discounts', { admin: true });
  const orders = sectionsFor('orders', { admin: true });
  const qs = (s) => new URLSearchParams(s);

  test('?section= picks the section, default is the first one', () => {
    expect(activeSection(settings, '/market/settings', qs('section=payments'))).toBe('payments');
    expect(activeSection(settings, '/market/settings', qs(''))).toBe('general');
    expect(activeSection(discounts, '/market/discounts', qs('section=creators'))).toBe('creators');
    expect(activeSection(discounts, '/market/gifts', qs(''))).toBe('gifts');
  });

  test('a detail page highlights its list section', () => {
    expect(activeSection(orders, '/market/orders/detail/5', qs(''))).toBe('orders');
    expect(activeSection(orders, '/market/subscriptions/detail/9', qs(''))).toBe('subscriptions');
  });

  test('no match is null', () => {
    expect(activeSection(orders, '/market/credits', qs(''))).toBeNull();
  });
});
