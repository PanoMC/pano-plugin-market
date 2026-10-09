import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import {
  MANUAL_PROVIDER,
  STATUS_TABS,
  activeTab,
  addressRequest,
  cancelRequest,
  canRelease,
  clampQuantity,
  createRequest,
  defaultProviderId,
  failureText,
  initialLines,
  initialParcels,
  labelPath,
  listParams,
  offersForceCancel,
  preselectRate,
  releaseRequest,
  requiredAddressFields,
  rowActions,
  selectedItems,
  shippableOf,
  statusChoices,
  updateRequest,
  validateParcels,
} from './shipments.js';
import { NODE } from './permissions.js';

const om = { admin: false, permissions: [NODE.OM] };
const ov = { admin: false, permissions: [NODE.OV] };

const lines = [
  {
    orderItemId: 1,
    name: 'Mug',
    quantity: 5,
    refundedQuantity: 1,
    shippedQuantity: 1,
    shippable: 3,
    weightGrams: 300,
  },
  {
    orderItemId: 2,
    name: 'Cap',
    quantity: 2,
    refundedQuantity: 0,
    shippedQuantity: 2,
    weightGrams: 100,
  },
  { orderItemId: 3, name: 'Pin', quantity: 4, refundedQuantity: 1, shippedQuantity: 1 },
];
const parcels = [{ weightGrams: '500', lengthMm: '', widthMm: '', heightMm: '' }];
const manual = { carrierName: 'DHL', trackingNumber: 'AB 123', trackingUrl: '' };
const base = {
  lines: initialLines(lines),
  parcels,
  providerId: 'manual',
  provider: null,
  manual,
  note: '',
};

describe('list', () => {
  test('tabs: To Ship links to orders, others filter by csv', () => {
    expect(STATUS_TABS.find((t) => t.key === 'to-ship').href).toBe(
      '/market/orders?shippingStatus=PENDING,PARTIAL',
    );
    expect(activeTab(null)).toBe('all');
    expect(activeTab('LOST,EXCEPTION,RETURNED,RETURNING')).toBe('problems');
    expect(activeTab('DELIVERED')).toBe('delivered');
    expect(activeTab('NOPE')).toBeNull();
  });
  test('listParams drops empty values and keeps filters', () => {
    expect(listParams({ search: 'x', status: '' }, { providerId: 'manual' })).toEqual({
      search: 'x',
      providerId: 'manual',
    });
  });
  test('row actions: OV only views, OM adds the rest by state', () => {
    expect(rowActions({ status: 'CREATED' }, ov)).toEqual(['view']);
    expect(rowActions({ status: 'IN_TRANSIT', labelFile: 'a.pdf' }, om)).toEqual([
      'view',
      'label',
      'track',
    ]);
    expect(rowActions({ status: 'CANCELLED' }, om)).toEqual(['view', 'generic-label', 'track']);
    expect(
      rowActions({ status: 'CREATED', entryMode: 'CARRIER', lastErrorCode: 'TIMEOUT' }, om),
    ).toEqual(['view', 'generic-label', 'track', 'retry', 'cancel']);
    expect(rowActions({ status: 'LOST' }, om)).toContain('release');
    expect(rowActions({ status: 'LOST', itemsReleased: true }, om)).not.toContain('release');
    expect(rowActions({ status: 'LABEL_READY' }, om)).toContain('cancel');
    expect(rowActions({ status: 'IN_TRANSIT' }, om)).not.toContain('cancel');
  });
  test('release only for returned / lost parcels not yet released', () => {
    expect(canRelease({ status: 'RETURNED' })).toBe(true);
    expect(canRelease({ status: 'RETURNED', itemsReleased: true })).toBe(false);
    expect(canRelease({ status: 'DELIVERED' })).toBe(false);
  });
});

describe('label is a download link, never inline', () => {
  test('paths', () => {
    expect(labelPath('/p', 7)).toBe('/api/plugins/pano-plugin-market/panel/shipments/7/label');
    expect(labelPath('', 7, true)).toBe(
      '/api/plugins/pano-plugin-market/panel/shipments/7/label?generic=true',
    );
  });
  test('no component renders a label inline', () => {
    for (const file of [
      'src/panel/pages/Shipments.svelte',
      'src/panel/components/modals/ShipmentModal.svelte',
      'src/panel/components/modals/CreateShipmentModal.svelte',
    ]) {
      const source = fs.readFileSync(file, 'utf8');
      expect(source).not.toMatch(/<(iframe|embed|object|img)\b/i);
      expect(source).not.toContain('{@html');
    }
    const page = fs.readFileSync('src/panel/pages/Shipments.svelte', 'utf8');
    expect(page).toMatch(
      /labelPath\(base[^)]*\)[\s\S]{0,120}target="_blank"[\s\S]{0,40}rel="noopener"[\s\S]{0,60}download/,
    );
  });
});

describe('cancel and release', () => {
  test('cancel body carries force only when forced', () => {
    expect(cancelRequest(4).body).toEqual({});
    expect(cancelRequest(4, true)).toEqual({
      method: 'POST',
      path: '/shipments/4/cancel',
      body: { force: true },
    });
    expect(releaseRequest(4)).toEqual({
      method: 'PUT',
      path: '/shipments/4',
      body: { releaseItems: true },
    });
  });
  test('Cancel Anyway is offered only after the three refusals of 10 §9.5', () => {
    expect(offersForceCancel('PROVIDER_UNAVAILABLE', {})).toBe(true);
    expect(offersForceCancel('SHIPMENT_NOT_CANCELLABLE', { reason: 'UNSUPPORTED' })).toBe(true);
    expect(offersForceCancel('SHIPPING_PROVIDER_ERROR', { code: 'GATEWAY_REJECTED' })).toBe(true);
    expect(offersForceCancel('SHIPMENT_NOT_CANCELLABLE', { reason: 'TERMINAL' })).toBe(false);
    expect(offersForceCancel('SHIPMENT_NOT_CANCELLABLE', { reason: 'HANDED_OVER' })).toBe(false);
    expect(offersForceCancel('SHIPMENT_NOT_CANCELLABLE', { reason: 'IN_PROGRESS' })).toBe(false);
    expect(offersForceCancel('SHIPPING_PROVIDER_ERROR', { code: 'TIMEOUT' })).toBe(false);
    expect(offersForceCancel('NETWORK_ERROR', {})).toBe(false);
  });
  test('provider error text is appended only for a known code', () => {
    const table = {
      'errors.SHIPPING_PROVIDER_ERROR': 'Carrier error.',
      'enums.provider-error.INSUFFICIENT_BALANCE': 'Low balance.',
    };
    const $_ = (key) => table[key] ?? key;
    expect(failureText($_, 'SHIPPING_PROVIDER_ERROR', { code: 'INSUFFICIENT_BALANCE' })).toBe(
      'Carrier error. Low balance.',
    );
    expect(failureText($_, 'SHIPPING_PROVIDER_ERROR', { code: 'WHATEVER' })).toBe('Carrier error.');
    expect(failureText($_, 'SHIPPING_PROVIDER_ERROR', {})).toBe('Carrier error.');
  });
});

describe('partial quantities are bounded by the unshipped remainder', () => {
  test('shippable', () => {
    expect(shippableOf(lines[0])).toBe(3);
    expect(shippableOf(lines[1])).toBe(0);
    expect(shippableOf(lines[2])).toBe(2);
    expect(shippableOf({ quantity: 1, shippedQuantity: 5 })).toBe(0);
  });
  test('only lines with units left, default = remainder', () => {
    const rows = initialLines(lines);
    expect(rows.map((r) => [r.orderItemId, r.remaining, r.quantity])).toEqual([
      [1, 3, '3'],
      [3, 2, '2'],
    ]);
  });
  test('clamp', () => {
    expect(clampQuantity('2', 3)).toBe(2);
    expect(clampQuantity('9', 3)).toBe(3);
    expect(clampQuantity('-1', 3)).toBe(0);
    expect(clampQuantity('abc', 3)).toBe(0);
    expect(clampQuantity('99999999999999999999', 3)).toBe(3);
    expect(clampQuantity('', 3)).toBe(0);
  });
  test('selectedItems clamps even an unclamped typed value and drops zeros', () => {
    const rows = initialLines(lines);
    rows[0].quantity = '50';
    rows[1].quantity = '0';
    expect(selectedItems(rows)).toEqual([{ orderItemId: 1, quantity: 3 }]);
  });
  test('nothing selected is an error', () => {
    const rows = initialLines(lines).map((r) => ({ ...r, quantity: '0' }));
    expect(createRequest(9, { ...base, lines: rows }).error.items).toBe(true);
  });
});

describe('parcels', () => {
  test('weight integer >= 1, dimensions all or none', () => {
    expect(validateParcels(parcels).parcels).toEqual([{ weightGrams: 500 }]);
    expect(validateParcels([{ weightGrams: '0' }]).error.parcels[0].weightGrams).toBe(true);
    expect(validateParcels([{ weightGrams: '1.5' }]).error.parcels[0].weightGrams).toBe(true);
    expect(
      validateParcels([{ weightGrams: '5', lengthMm: '10' }]).error.parcels[0].dimensions,
    ).toBe(true);
    expect(
      validateParcels([{ weightGrams: '5', lengthMm: '10', widthMm: '10', heightMm: '10' }])
        .parcels,
    ).toEqual([{ weightGrams: 5, lengthMm: 10, widthMm: 10, heightMm: 10 }]);
    expect(
      validateParcels([{ weightGrams: '5', lengthMm: '5001', widthMm: '1', heightMm: '1' }]).error,
    ).toBeDefined();
    expect(validateParcels([]).error.parcels).toBe('REQUIRED');
    expect(validateParcels(Array.from({ length: 21 }, () => parcels[0])).error.parcels).toBe(
      'TOO_MANY',
    );
    expect(validateParcels(parcels, { requiresDimensions: true }).error).toBeDefined();
  });
  test('suggested parcels prefill, an empty row otherwise', () => {
    expect(
      initialParcels([{ weightGrams: 450, lengthMm: 10, widthMm: 20, heightMm: 30 }])[0],
    ).toEqual({
      weightGrams: '450',
      lengthMm: '10',
      widthMm: '20',
      heightMm: '30',
    });
    expect(initialParcels([])).toHaveLength(1);
  });
});

describe('create request', () => {
  test('manual entry sends manual{} and no rate fields', () => {
    const { request } = createRequest(9, { ...base, note: ' hello ' });
    expect(request.path).toBe('/orders/9/shipments');
    expect(request.body).toEqual({
      items: [
        { orderItemId: 1, quantity: 3 },
        { orderItemId: 3, quantity: 2 },
      ],
      parcels: [{ weightGrams: 500 }],
      providerId: 'manual',
      manual: { carrierName: 'DHL', trackingNumber: 'AB 123' },
      note: 'hello',
    });
  });
  test('manual validation: required names, charset, url', () => {
    const bad = (m) => createRequest(9, { ...base, manual: { ...manual, ...m } }).error;
    expect(bad({ carrierName: '' }).carrierName).toBe(true);
    expect(bad({ trackingNumber: '' }).trackingNumber).toBe(true);
    expect(bad({ trackingNumber: 'a<b>' }).trackingNumber).toBe(true);
    expect(bad({ trackingUrl: 'javascript:alert(1)' }).trackingUrl).toBe(true);
    expect(bad({ trackingUrl: 'https://t.example/1' })).toBeUndefined();
  });
  const carrier = {
    id: 'yurtici',
    capabilities: { maxParcels: 3, rateQuote: true },
    services: [{ code: 'STD', name: 'Standard' }],
  };
  const rate = { serviceCode: 'STD', rateRef: 'r1', price: 10, currency: 'TRY' };
  test('carrier needs a chosen rate, then sends serviceCode + rateRef', () => {
    const form = { ...base, providerId: 'yurtici', provider: carrier };
    expect(createRequest(9, form).error.rate).toBe(true);
    const { request } = createRequest(9, { ...form, rate, rates: [rate] });
    expect(request.body.serviceCode).toBe('STD');
    expect(request.body.rateRef).toBe('r1');
    expect(request.body.manual).toBeUndefined();
  });
  test('provider without rate shopping submits with the service select only', () => {
    const form = {
      ...base,
      providerId: 'yurtici',
      provider: carrier,
      serviceCode: 'STD',
      ratesFetched: true,
      rates: [],
    };
    expect(createRequest(9, form).request.body.serviceCode).toBe('STD');
    expect(createRequest(9, { ...form, ratesFailed: true }).error.rate).toBe(true);
    const noQuote = { ...carrier, capabilities: { rateQuote: false } };
    expect(
      createRequest(9, { ...form, provider: noQuote, ratesFetched: false }).request,
    ).toBeDefined();
  });
  test('parcel cap of the provider applies', () => {
    const form = {
      ...base,
      providerId: 'yurtici',
      provider: carrier,
      rate,
      rates: [rate],
      parcels: Array.from({ length: 4 }, () => parcels[0]),
    };
    expect(createRequest(9, form).error.parcels).toBe('TOO_MANY');
  });
  test('note over 255 is rejected', () => {
    expect(createRequest(9, { ...base, note: 'x'.repeat(256) }).error.note).toBe(true);
  });
  test('defaults: quoted provider if offered else manual; rate matching the quote preselected', () => {
    expect(defaultProviderId({ providerId: 'yurtici' }, [{ id: 'yurtici' }])).toBe('yurtici');
    expect(defaultProviderId({ providerId: 'gone' }, [{ id: 'yurtici' }])).toBe(MANUAL_PROVIDER);
    expect(defaultProviderId(null, [])).toBe(MANUAL_PROVIDER);
    expect(
      preselectRate([{ serviceCode: 'A' }, { serviceCode: 'B' }], { serviceCode: 'B' }),
    ).toEqual({ serviceCode: 'B' });
    expect(preselectRate([{ serviceCode: 'A' }], { serviceCode: 'B' })).toBeNull();
  });
});

describe('shipment edit', () => {
  const shipment = {
    id: 5,
    status: 'IN_TRANSIT',
    trackingNumber: 'T1',
    trackingUrl: '',
    carrierName: 'DHL',
    entryMode: 'MANUAL',
  };
  test('only changed fields are sent, unchanged is a no-op', () => {
    expect(
      updateRequest(shipment, {
        trackingNumber: 'T1',
        trackingUrl: '',
        carrierName: 'DHL',
        status: 'IN_TRANSIT',
      }),
    ).toEqual({ unchanged: true });
    const { request } = updateRequest(shipment, {
      trackingNumber: 'T2',
      trackingUrl: 'https://x.example/t',
      carrierName: 'DHL',
      status: 'DELIVERED',
    });
    expect(request).toEqual({
      method: 'PUT',
      path: '/shipments/5',
      body: { trackingNumber: 'T2', trackingUrl: 'https://x.example/t', status: 'DELIVERED' },
    });
  });
  test('url must be http(s); tracking number charset', () => {
    expect(updateRequest(shipment, { ...shipment, trackingUrl: 'ftp://x' }).error.trackingUrl).toBe(
      true,
    );
    expect(
      updateRequest(shipment, { ...shipment, trackingNumber: '<x>' }).error.trackingNumber,
    ).toBe(true);
  });
  test('carrier-owned tracking number is read-only', () => {
    const owned = { ...shipment, entryMode: 'CARRIER', carrierReference: 'ref' };
    expect(updateRequest(owned, { ...owned, trackingNumber: 'other' })).toEqual({
      unchanged: true,
    });
  });
  test('status choices keep the current non-settable status and never offer CREATED / CANCELLED', () => {
    expect(statusChoices('IN_TRANSIT')).not.toContain('CREATED');
    expect(statusChoices('IN_TRANSIT')).not.toContain('CANCELLED');
    expect(statusChoices('LABEL_READY')[0]).toBe('LABEL_READY');
    expect(
      updateRequest({ ...shipment, status: 'LABEL_READY' }, { ...shipment, status: 'LABEL_READY' }),
    ).toEqual({ unchanged: true });
  });
});

describe('shipping address', () => {
  const full = {
    firstName: 'A',
    lastName: 'B',
    phone: '+905551112233',
    country: 'tr',
    city: 'Ankara',
    district: 'Cankaya',
    line1: 'Street 1',
  };
  test('required sets', () => {
    expect(requiredAddressFields('TR')).not.toContain('postalCode');
    expect(requiredAddressFields('TR')).toContain('district');
    expect(requiredAddressFields('DE')).toContain('postalCode');
    expect(requiredAddressFields('US')).toContain('state');
    expect(requiredAddressFields('AE')).not.toContain('postalCode');
  });
  test('request upper-cases the country and omits empty fields', () => {
    const { request } = addressRequest(3, { ...full, company: '' });
    expect(request.method).toBe('PUT');
    expect(request.path).toBe('/orders/3/shipping-address');
    expect(request.body.country).toBe('TR');
    expect(request.body.company).toBeUndefined();
  });
  test('missing required and bad email are marked', () => {
    const { error } = addressRequest(3, { ...full, city: '', email: 'nope' });
    expect(error.city).toBe(true);
    expect(error.email).toBe(true);
    expect(addressRequest(3, { country: 'DE' }).error.postalCode).toBe(true);
  });
});
