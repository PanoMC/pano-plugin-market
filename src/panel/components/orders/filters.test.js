import { describe, expect, test } from 'bun:test';
import { loadListWith } from '../../utils/list-core.js';
import {
  DEFAULT_EXPORT_COLUMNS,
  EXPORT_COLUMNS,
  ORDER_PARAMS,
  STATUS_TABS,
  activeModalFilters,
  activeTab,
  allowedExportColumns,
  exportParams,
  exportUrl,
  hiddenStatus,
  listParams,
  normalizeFilters,
  paymentOptions,
  productsCell,
  refundedLine,
  rowActions,
  sanitizeColumns,
} from './filters.js';

const buildQueryParams = (params) => {
  const qs = Object.keys(params)
    .filter((k) => params[k])
    .map((k) => `${encodeURIComponent(k)}=${encodeURIComponent(params[k])}`)
    .join('&');
  return qs === '' ? '' : '?' + qs;
};

const ov = { permissions: ['pano.plugin.pano-plugin-market.view.market.orders'] };
const om = { permissions: ['pano.plugin.pano-plugin-market.manage.market.orders'] };
const pay = { permissions: ['pano.plugin.pano-plugin-market.manage.market.payments'] };
const admin = { admin: true };

describe('status tabs', () => {
  test('each tab maps to its csv and back', () => {
    for (const tab of STATUS_TABS) expect(activeTab(tab.value)).toBe(tab.key);
  });
  test('csv order does not matter, unknown csv matches no tab', () => {
    expect(activeTab('PARTIALLY_REFUNDED,COMPLETED')).toBe('completed');
    expect(activeTab('EXPIRED,CANCELLED,FAILED')).toBe('failed');
    expect(activeTab('COMPLETED')).toBeNull();
  });
  test('tab csv values are exactly those of 13 5', () => {
    expect(STATUS_TABS.map((t) => t.value)).toEqual([
      null,
      'PENDING',
      'REVIEW',
      'COMPLETED,PARTIALLY_REFUNDED',
      'REFUNDED',
      'FAILED,CANCELLED,EXPIRED',
      'CHARGEBACK',
    ]);
  });
});

describe('URL list state', () => {
  test('normalizeFilters keeps every param, drops a bad testMode', () => {
    const f = normalizeFilters({ search: ' x ', testMode: 'maybe', source: 'PANEL' });
    expect(Object.keys(f)).toEqual(ORDER_PARAMS);
    expect(f.testMode).toBe('');
    expect(f.source).toBe('PANEL');
    expect(normalizeFilters(null).search).toBe('');
  });
  test('listParams has no page and removes emptied filters', () => {
    const q = listParams({ status: 'REFUNDED', search: 'bob', page: 3 }, { status: null });
    expect(q).toEqual({ search: 'bob' });
    expect('page' in listParams({ status: 'X' })).toBe(false);
  });
  test('activeModalFilters ignores search and status, and shipping without shipping', () => {
    const f = { search: 's', status: 'PENDING', shippingStatus: 'SHIPPED', source: 'PANEL' };
    expect(activeModalFilters(f, { shippingEnabled: false })).toEqual(['source']);
    expect(activeModalFilters(f, { shippingEnabled: true })).toEqual(['shippingStatus', 'source']);
    expect(activeModalFilters({ from: '1' })).toEqual(['from']);
    expect(activeModalFilters({})).toEqual([]);
  });
});

describe('list loading around the page', () => {
  const opts = { path: '/orders', params: ORDER_PARAMS, nodes: ['OV'], emptyKey: 'orders' };
  const event = (query) => ({
    url: new URL('http://x/market/orders' + query),
    parent: async () => ({ user: admin, pageTitle: { set() {} } }),
  });
  test('stale page falls back to page 1 with the same filters; filters are returned', async () => {
    const paths = [];
    const deps = {
      buildQueryParams,
      get: async ({ path }) => {
        paths.push(path);
        if (path.endsWith('/context')) return { currency: 'USD' };
        if (path.includes('page=9')) return { error: 'PAGE_NOT_FOUND' };
        return { orders: [{ id: 1 }], orderCount: 1, totalPage: 1 };
      },
    };
    const { data } = await loadListWith(deps, event('?page=9&status=REFUNDED&testMode=true'), opts);
    expect(data.page).toBe(1);
    expect(data.orders).toHaveLength(1);
    expect(data.filters.status).toBe('REFUNDED');
    expect(data.filters.testMode).toBe('true');
    const orderPaths = paths.filter((p) => p.includes('/orders?'));
    expect(orderPaths[0]).toContain('page=9');
    expect(orderPaths[1]).not.toContain('page=');
    expect(orderPaths[1]).toContain('status=REFUNDED');
    expect(orderPaths[1]).toContain('testMode=true');
  });
});

describe('CSV export', () => {
  test('PII columns exist only with OM or PAY', () => {
    expect(allowedExportColumns(ov)).not.toContain('email');
    expect(allowedExportColumns(ov)).not.toContain('country');
    for (const user of [om, pay, admin]) {
      expect(allowedExportColumns(user)).toContain('email');
      expect(allowedExportColumns(user)).toContain('country');
    }
    expect(allowedExportColumns(ov)).toHaveLength(EXPORT_COLUMNS.length - 2);
  });
  test('sanitizeColumns drops PII for OV, unknown keys, keeps table order', () => {
    expect(sanitizeColumns(['status', 'email', 'bogus', 'orderId', 'country'], ov)).toEqual([
      'orderId',
      'status',
    ]);
    expect(sanitizeColumns(['email', 'orderId'], pay)).toEqual(['orderId', 'email']);
  });
  test('default columns carry no PII and all exist', () => {
    const keys = EXPORT_COLUMNS.map((c) => c.key);
    for (const c of DEFAULT_EXPORT_COLUMNS) expect(keys).toContain(c);
    expect(DEFAULT_EXPORT_COLUMNS).not.toContain('email');
    expect(DEFAULT_EXPORT_COLUMNS).not.toContain('country');
  });
  test('the key set is the one of 04 7', () => {
    expect(EXPORT_COLUMNS.map((c) => c.key).join(',')).toBe(
      'orderId,publicId,createdAt,paidAt,status,source,playerUsername,recipientUsername,email,productName,variantName,sku,quantity,unitPrice,lineTotal,currency,orderTotal,couponCode,creatorCode,paymentMethod,gatewayTransactionId,gatewayAmount,creditValue,refundedTotal,fulfillmentStatus,shippingStatus,country,testMode',
    );
  });
  test('exportParams applies the current filters implicitly, never page', () => {
    const q = exportParams({ status: 'PENDING', search: 'a', page: 4 }, ['orderId', 'status'], ';');
    expect(q).toEqual({
      search: 'a',
      status: 'PENDING',
      columns: 'orderId,status',
      delimiter: ';',
    });
    expect(exportParams({}, ['orderId'], ',').delimiter).toBeUndefined();
    expect(exportParams({}, ['orderId'], 'tab').delimiter).toBe('tab');
  });
  test('exportUrl', () => {
    const url = exportUrl('/panel', { status: 'REFUNDED' }, ['orderId'], ',', buildQueryParams);
    expect(url).toBe('/panel/api/panel/market/orders/export?status=REFUNDED&columns=orderId');
  });
});

describe('row cells', () => {
  test('paymentOptions: label from custom label, then descriptor, deduplicated', () => {
    expect(
      paymentOptions([
        { id: 'a', config: { customLabel: 'Card' }, descriptor: { name: 'Stripe' } },
        { id: 'b', descriptor: { name: 'PayPal' } },
        { id: 'a', descriptor: { name: 'dup' } },
        { id: 'c' },
        null,
      ]),
    ).toEqual([
      { id: 'a', label: 'Card' },
      { id: 'b', label: 'PayPal' },
      { id: 'c', label: 'c' },
    ]);
    expect(paymentOptions(undefined)).toEqual([]);
  });
  test('productsCell: first item, +N, text tooltip', () => {
    const order = {
      items: [
        { productName: 'VIP', variantName: 'Monthly' },
        { productName: 'Key' },
        { productName: '<b>x</b>' },
      ],
    };
    expect(productsCell(order)).toEqual({
      first: 'VIP (Monthly)',
      more: 2,
      tooltip: 'VIP (Monthly), Key, <b>x</b>',
    });
    expect(productsCell({})).toEqual({ first: '', more: 0, tooltip: '' });
  });
  test('hidden delivery / shipping values show a dash', () => {
    expect(hiddenStatus('fulfillment', 'NONE')).toBe(true);
    expect(hiddenStatus('fulfillment', 'FAILED')).toBe(false);
    expect(hiddenStatus('shipping', 'NOT_REQUIRED')).toBe(true);
    expect(hiddenStatus('shipping', 'SHIPPED')).toBe(false);
    expect(hiddenStatus('shipping', null)).toBe(true);
  });
  test('refunded second line only when > 0', () => {
    expect(refundedLine({ refundedTotal: 5 })).toBe(5);
    expect(refundedLine({ refundedTotal: 0 })).toBeNull();
    expect(refundedLine({})).toBeNull();
  });
  test('re-run only with OM and FAILED / PARTIAL fulfilment', () => {
    expect(rowActions({ fulfillmentStatus: 'FAILED' }, om)).toEqual(['view', 'copy', 'rerun']);
    expect(rowActions({ fulfillmentStatus: 'PARTIAL' }, admin)).toContain('rerun');
    expect(rowActions({ fulfillmentStatus: 'FAILED' }, ov)).toEqual(['view', 'copy']);
    expect(rowActions({ fulfillmentStatus: 'FAILED' }, pay)).toEqual(['view', 'copy']);
    expect(rowActions({ fulfillmentStatus: 'FULFILLED' }, om)).toEqual(['view', 'copy']);
  });
});
