import { beforeEach, describe, expect, test } from 'bun:test';
import { ensureFeatures, featureOn, featureState, resetFeatures, setFeatures } from './features.js';
import { firstHref, sectionsFor, settingsFeature } from '../navigation.js';
import { NODE } from './permissions.js';

const admin = { admin: true, permissions: [] };
const keys = (sections) => sections.map((s) => s.key);

beforeEach(() => resetFeatures());

describe('feature switches', () => {
  test('nothing known, or a context without switches, leaves every feature on', () => {
    expect(featureState('physicalShipping')).toBe('on');
    setFeatures({ currency: 'USD' });
    expect(featureOn('physicalShipping')).toBe(true);
    expect(featureState(null)).toBe('on');
  });

  test('a switched-off feature is off, and passive in development mode', () => {
    setFeatures({ features: { physicalShipping: false, shippingCarriers: false } });
    expect(featureState('physicalShipping')).toBe('off');
    expect(featureOn('physicalShipping')).toBe(false);

    setFeatures({ features: { physicalShipping: false }, featurePreview: true });
    expect(featureState('physicalShipping')).toBe('passive');
    expect(featureOn('physicalShipping')).toBe(false);
    expect(featureState('shippingCarriers')).toBe('on');
  });

  test('the context is read once; a failed read is tried again', async () => {
    let calls = 0;
    const failing = async () => {
      calls++;
      throw new Error('offline');
    };
    await ensureFeatures(failing);
    expect(featureOn('physicalShipping')).toBe(true);

    const answer = async () => {
      calls++;
      return { features: { physicalShipping: false } };
    };
    await Promise.all([ensureFeatures(answer), ensureFeatures(answer)]);
    await ensureFeatures(answer);
    expect(calls).toBe(2);
    expect(featureState('physicalShipping')).toBe('off');
  });
});

describe('navigation follows the switches', () => {
  test('every shipping section is there while the features are on', () => {
    expect(keys(sectionsFor('orders', admin))).toContain('shipments');
    expect(keys(sectionsFor('settings', admin))).toEqual(
      expect.arrayContaining(['shipping-methods', 'shipping-zones', 'shipping-carriers']),
    );
  });

  test('switched off: the shipping sections are gone, the others stay in order', () => {
    setFeatures({ features: { physicalShipping: false, shippingCarriers: false } });
    expect(keys(sectionsFor('orders', admin))).toEqual([
      'orders',
      'deliveries',
      'subscriptions',
      'payment-events',
    ]);
    const settings = keys(sectionsFor('settings', admin));
    expect(settings.some((key) => key.startsWith('shipping'))).toBe(false);
    expect(settings).toContain('delivery');
    expect(settings).toContain('minecraft');
  });

  test('carriers alone off: methods and zones stay', () => {
    setFeatures({ features: { shippingCarriers: false } });
    const settings = keys(sectionsFor('settings', admin));
    expect(settings).toContain('shipping-methods');
    expect(settings).toContain('shipping-zones');
    expect(settings).not.toContain('shipping-carriers');
  });

  test('development mode keeps them as passive entries that are never a landing page', () => {
    setFeatures({ features: { physicalShipping: false }, featurePreview: true });
    const shipments = sectionsFor('orders', admin).find((s) => s.key === 'shipments');
    expect(shipments.passive).toBe(true);
    expect(sectionsFor('orders', admin).find((s) => s.key === 'orders').passive).toBeUndefined();
    expect(firstHref('orders', { admin: false, permissions: [NODE.OV] })).toBe('/market/orders');
  });

  test('settings sections name their switch', () => {
    expect(settingsFeature('shipping-zones')).toBe('physicalShipping');
    expect(settingsFeature('shipping-carriers')).toBe('shippingCarriers');
    expect(settingsFeature('general')).toBeNull();
  });
});
