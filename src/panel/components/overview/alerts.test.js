import { describe, expect, test } from 'bun:test';
import {
  MC_DOWNLOAD_URL,
  mcReleaseUrl,
  mcDownloads,
  mcJarOf,
  MC_JARS,
  alertsFor,
  mcPluginNeedsAttention,
  safeUrl,
  MAX_MISSING_SHOWN,
} from './alerts.js';

const keys = (alerts) => alerts.map((a) => a.key);
const HEALTHY = {
  runtimeState: 'READY',
  schema: { ok: true, missing: [], unfixed: [] },
  queues: { deliveriesFailed: 0, deferredEvents: 0 },
  providers: [{ id: 'stripe', state: 'AVAILABLE' }],
  mail: 'OK',
};

describe('alertsFor', () => {
  test('nothing for a healthy store', () => {
    expect(alertsFor(HEALTHY, [], { testMode: false, mailEnabled: true })).toEqual([]);
    expect(alertsFor(null, [], null)).toEqual([]);
    expect(alertsFor(undefined, undefined, undefined)).toEqual([]);
  });

  test('schema degraded lists at most 10 missing objects plus the rest', () => {
    const missing = Array.from({ length: 13 }, (_, i) => `t${i}`);
    const [alert] = alertsFor({ ...HEALTHY, schema: { ok: false, missing } }, [], {});
    expect(alert.key).toBe('schema-degraded');
    expect(alert.variant).toBe('danger');
    expect(alert.title).toBe('schema-degraded.title');
    expect(alert.missing).toHaveLength(MAX_MISSING_SHOWN);
    expect(alert.more).toBe(3);
    expect(alert.links[0].href).toBe('/market/settings?section=health');
  });

  test('test mode warns and links to settings only with SET', () => {
    const [withSet] = alertsFor(null, [], { testMode: true });
    expect(withSet).toMatchObject({ key: 'test-mode', variant: 'warning' });
    expect(withSet.links[0].href).toBe('/market/settings');
    const [without] = alertsFor(
      null,
      [],
      { testMode: true },
      { can: (...k) => !k.includes('SET') },
    );
    expect(without.links).toEqual([]);
  });

  test('component needs attention: one alert with the downloads link', () => {
    const servers = [
      { id: 1, name: 'Survival', marketState: 'COMPONENT_MISSING', waitingDeliveries: 4 },
      {
        id: 2,
        name: 'Skyblock',
        marketState: 'VERSION_MISMATCH',
        mcComponentVersion: '1.0.0',
        requiredVersion: '1.2.0',
        waitingDeliveries: 0,
      },
      { id: 3, name: 'Fine', marketState: 'READY', waitingDeliveries: 9 },
    ];
    const [alert] = alertsFor(null, servers, {});
    expect(alert.key).toBe('mc-plugin');
    expect(alert.variant).toBe('warning');
    expect(alert.downloadUrl).toBe(`${MC_DOWNLOAD_URL}/tag/v1.2.0`);
    expect(alert.links).toEqual([]);
    expect(alert.items).toBeUndefined();
  });

  test('offline servers only with waiting deliveries', () => {
    expect(
      alertsFor(null, [{ id: 1, name: 'A', marketState: 'OFFLINE', waitingDeliveries: 0 }], {}),
    ).toEqual([]);
    expect(
      mcPluginNeedsAttention([{ id: 1, name: 'A', marketState: 'OFFLINE', waitingDeliveries: 0 }]),
    ).toBe(false);
    const [alert] = alertsFor(
      null,
      [{ id: 1, name: 'A', marketState: 'OFFLINE', waitingDeliveries: 2 }],
      {},
    );
    expect(alert.key).toBe('mc-plugin');
    expect(alert.downloadUrl).toBe(MC_DOWNLOAD_URL);
    expect(mcPluginNeedsAttention([{ id: 1, name: 'A', marketState: 'OFFLINE', waitingDeliveries: 2 }])).toBe(true);
  });

  test('download url must be http(s)', () => {
    expect(safeUrl('javascript:alert(1)')).toBeNull();
    expect(safeUrl('data:text/html,x')).toBeNull();
    expect(safeUrl('not a url')).toBeNull();
    expect(safeUrl(null)).toBeNull();
    expect(safeUrl('https://panomc.com/x')).toBe('https://panomc.com/x');
  });

  test('review orders link to the REVIEW list', () => {
    const [alert] = alertsFor(null, [], {}, { reviewCount: 3 });
    expect(alert).toMatchObject({ key: 'review', variant: 'warning', count: 3 });
    expect(alert.links[0].href).toBe('/market/orders?status=REVIEW');
    expect(alertsFor(null, [], {}, { reviewCount: 0 })).toEqual([]);
  });

  test('failed deliveries and deferred events', () => {
    const health = { ...HEALTHY, queues: { deliveriesFailed: 2, deferredEvents: 5 } };
    const alerts = alertsFor(health, [], {});
    expect(keys(alerts)).toEqual(['failed-deliveries', 'deferred-events']);
    expect(alerts[0]).toMatchObject({ variant: 'danger', count: 2 });
    expect(alerts[0].links[0].href).toBe('/market/deliveries?status=FAILED');
    expect(alerts[1]).toMatchObject({ variant: 'warning', count: 5 });
    expect(alerts[1].links[0].href).toBe('/market/payment-events?status=DEFERRED');
  });

  test('provider unavailable or incompatible', () => {
    const health = {
      ...HEALTHY,
      providers: [
        { id: 'a', state: 'UNAVAILABLE' },
        { id: 'b', state: 'INCOMPATIBLE' },
        { id: 'c', state: 'AVAILABLE' },
      ],
    };
    const [alert] = alertsFor(health, [], {});
    expect(alert).toMatchObject({
      key: 'provider-unavailable',
      variant: 'warning',
      providers: ['a', 'b'],
    });
    expect(alert.links[0].href).toBe('/market/settings?section=payments');
  });

  test('mail disabled: info when the context says so, warning when the host is too old (one alert)', () => {
    const [info] = alertsFor(HEALTHY, [], { mailEnabled: false });
    expect(info).toMatchObject({
      key: 'mail-disabled',
      variant: 'info',
      title: 'mail-disabled.title',
    });
    const both = alertsFor({ ...HEALTHY, mail: 'HOST_TOO_OLD' }, [], { mailEnabled: false });
    expect(both).toHaveLength(1);
    expect(both[0]).toMatchObject({
      key: 'mail-host-too-old',
      variant: 'warning',
      title: 'mail-disabled.title',
    });
    expect(alertsFor(HEALTHY, [], { mailEnabled: true })).toEqual([]);
  });

  test('store unavailable when the runtime is not READY', () => {
    const health = {
      ...HEALTHY,
      runtimeState: 'DEGRADED',
      schema: { ok: false, missing: ['a'], unfixed: ['b'] },
    };
    const alerts = alertsFor(health, [], {});
    expect(keys(alerts)).toEqual(['schema-degraded', 'store-unavailable']);
    expect(alerts[1]).toMatchObject({ variant: 'danger', missing: ['a', 'b'] });
  });

  test('revoke failed, or revoke pending for more than 10 minutes', () => {
    const now = 1_000_000_000;
    const orders = [
      { id: 1, revokeFailed: 1, revokePending: 0 },
      { id: 2, revokeFailed: 0, revokePending: 2, revokePendingSince: now - 11 * 60_000 },
      { id: 3, revokeFailed: 0, revokePending: 2, revokePendingSince: now - 5 * 60_000 },
      { id: 4, revokeFailed: 0, revokePending: 0 },
    ];
    const alerts = alertsFor(null, [], {}, { orders, now });
    expect(alerts.map((a) => a.orderId)).toEqual([1, 2]);
    expect(alerts[0]).toMatchObject({ variant: 'danger', title: 'revoke-pending.title' });
    expect(alerts[0].links[0].href).toBe('/market/orders/detail/1');
  });

  test('order of the table', () => {
    const health = {
      runtimeState: 'DEGRADED',
      schema: { ok: false, missing: ['x'] },
      queues: { deliveriesFailed: 1, deferredEvents: 1 },
      providers: [{ id: 'p', state: 'UNAVAILABLE' }],
      mail: 'HOST_TOO_OLD',
    };
    const servers = [{ id: 1, name: 'S', marketState: 'COMPONENT_MISSING' }];
    const alerts = alertsFor(
      health,
      servers,
      { testMode: true },
      {
        reviewCount: 1,
        orders: [{ id: 9, revokeFailed: 1 }],
      },
    );
    expect(keys(alerts)).toEqual([
      'schema-degraded',
      'test-mode',
      'mc-plugin',
      'mail-host-too-old',
      'store-unavailable',
      'revoke-pending-9',
      'review',
      'failed-deliveries',
      'deferred-events',
      'provider-unavailable',
    ]);
  });
});

import { readFileSync } from 'node:fs';

describe('alert locale keys', () => {
  const lookup = (obj, path) => path.split('.').reduce((o, k) => o?.[k], obj);
  const everything = alertsFor(
    {
      runtimeState: 'DEGRADED',
      schema: { ok: false, missing: ['x'] },
      queues: { deliveriesFailed: 1, deferredEvents: 1 },
      providers: [{ id: 'p', state: 'UNAVAILABLE' }],
      mail: 'HOST_TOO_OLD',
    },
    [
      {
        id: 1,
        name: 'A',
        marketState: 'COMPONENT_MISSING',
        waitingDeliveries: 1,
        downloadUrl: 'https://x.y',
      },
      { id: 2, name: 'B', marketState: 'VERSION_MISMATCH' },
      { id: 3, name: 'C', marketState: 'OFFLINE', waitingDeliveries: 1 },
    ],
    { testMode: true, mailEnabled: false },
    { reviewCount: 1, orders: [{ id: 1, revokeFailed: 1 }] },
  );
  const info = alertsFor(null, [], { mailEnabled: false });

  for (const lang of ['en-US', 'tr', 'ru']) {
    test(`${lang} has every title, body, link and action key`, () => {
      const alerts = JSON.parse(readFileSync(`src/locales/panel/${lang}.json`, 'utf8')).alerts;
      const wanted = [];
      for (const a of [...everything, ...info]) {
        wanted.push(a.title, a.body, ...(a.links ?? []).map((l) => `actions.${l.label}`));
      }
      if (everything.some((a) => a.downloadUrl)) wanted.push('mc-plugin.download', 'mc-plugin.releases');
      if (everything.some((a) => a.downloads?.length)) wanted.push(...MC_JARS.map((jar) => `mc-plugin.platforms.${jar}`));
      wanted.push('more');
      for (const key of wanted) expect(typeof lookup(alerts, key)).toBe('string');
    });
  }
});

describe('mcDownloads', () => {
  const href = (jar) => `/api/plugins/pano-plugin-market/panel/mc-component/download?platform=${jar}`;

  test('every bundled jar, the platforms of the servers that need attention first', () => {
    const servers = [
      { id: 1, type: 'VELOCITY', marketState: 'COMPONENT_MISSING' },
      { id: 2, type: 'PAPER', platform: 'FABRIC', marketState: 'VERSION_MISMATCH' },
      { id: 3, type: 'BUNGEECORD', marketState: 'READY' },
      { id: 4, type: 'FOLIA', marketState: 'OFFLINE', waitingDeliveries: 0 },
    ];
    expect(mcDownloads(servers)).toEqual(
      ['velocity', 'fabric', 'spigot', 'bungee'].map((platform) => ({ platform, href: href(platform) })),
    );
    expect(mcDownloads(null).map((d) => d.platform)).toEqual(MC_JARS);
  });

  test('the jar of a server: the reported platform wins over the type, no jar for Vanilla', () => {
    expect(mcJarOf({ type: 'PURPUR' })).toBe('spigot');
    expect(mcJarOf({ type: 'WATERFALL' })).toBe('bungee');
    expect(mcJarOf({ type: 'PAPER', platform: 'FABRIC' })).toBe('fabric');
    expect(mcJarOf({ type: 'VANILLA' })).toBeNull();
    expect(mcJarOf(null)).toBeNull();
  });

  test('the alert carries the downloads', () => {
    const [alert] = alertsFor(null, [{ id: 1, type: 'BUNGEECORD', marketState: 'COMPONENT_MISSING' }], {});
    expect(alert.downloads[0]).toEqual({ platform: 'bungee', href: href('bungee') });
  });
});

describe('mcReleaseUrl', () => {
  test('the release of the required version, or the list for an unreleased build', () => {
    expect(mcReleaseUrl([{ requiredVersion: '1.0.0-dev.30' }])).toBe(`${MC_DOWNLOAD_URL}/tag/v1.0.0-dev.30`);
    expect(mcReleaseUrl([{ requiredVersion: 'local-build' }])).toBe(MC_DOWNLOAD_URL);
    expect(mcReleaseUrl([])).toBe(MC_DOWNLOAD_URL);
    expect(mcReleaseUrl(null)).toBe(MC_DOWNLOAD_URL);
  });
});
