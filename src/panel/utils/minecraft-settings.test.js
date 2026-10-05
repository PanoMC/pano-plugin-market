import { describe, expect, test } from 'bun:test';
import { FIELDS, SECTION_KEYS, buildSettingsBody, seedValues } from './settings.js';
import {
  BROADCAST_VARIABLES,
  MC_FEATURES,
  OVERRIDE_KEYS,
  activeMinecraftKeys,
  buildOverride,
  clearOverrideRequest,
  effectiveVaultMode,
  overrideCount,
  overrideForm,
  overrideRequest,
  previewSegments,
  previewValues,
  serverRow,
  serverRows,
  stateClass,
  validateMinecraft,
  validateOverride,
  vaultProviderWarning,
} from './minecraft-settings.js';

// The mc* keys of 00 §12.
const MC_KEYS = [
  'mcStoreCommand',
  'mcCreditsCommand',
  'mcJoinNotifications',
  'mcStoreMenu',
  'mcAdminCommands',
  'mcPlaceholders',
  'mcLuckPerms',
  'mcBroadcast',
  'mcBroadcastTemplate',
  'mcDisabledAdminCommands',
  'mcVaultMode',
  'mcVaultRate',
  'mcVaultDirection',
];

const values = () => seedValues({}, SECTION_KEYS.minecraft);
const defaults = () => values();

describe('panel defaults', () => {
  test('every mc* key of 00 §12 belongs to the section with its documented default', () => {
    expect([...SECTION_KEYS.minecraft].sort()).toEqual([...MC_KEYS].sort());
    for (const { key } of MC_FEATURES) expect(MC_KEYS).toContain(key);
    expect(MC_FEATURES).toHaveLength(8);
    expect(FIELDS.mcStoreMenu.def).toBe(true);
    expect(FIELDS.mcBroadcast.def).toBe(false);
    expect(FIELDS.mcVaultMode.def).toBe('OFF');
    expect(FIELDS.mcVaultRate.def).toBe(1);
    expect(FIELDS.mcVaultDirection.def).toBe('BOTH');
    expect(FIELDS.mcBroadcastTemplate.def).toContain('{player}');
    expect(buildSettingsBody({}, values(), SECTION_KEYS.minecraft)).toEqual({});
  });

  test('the defaults are valid', () => {
    expect(validateMinecraft(values())).toEqual({});
  });

  test('the Vault rate and direction count only in CONVERT mode', () => {
    expect(activeMinecraftKeys({ mcVaultMode: 'OFF' })).not.toContain('mcVaultRate');
    expect(activeMinecraftKeys({ mcVaultMode: 'PROVIDER' })).not.toContain('mcVaultDirection');
    expect(activeMinecraftKeys({ mcVaultMode: 'CONVERT' })).toEqual(SECTION_KEYS.minecraft);
    expect(validateMinecraft({ ...values(), mcVaultMode: 'OFF', mcVaultRate: 0 })).toEqual({});
    expect(
      validateMinecraft({ ...values(), mcVaultMode: 'CONVERT', mcVaultRate: 0 }).mcVaultRate,
    ).toBe('OUT_OF_RANGE');
    expect(
      validateMinecraft({ ...values(), mcVaultMode: 'CONVERT', mcVaultRate: '' }).mcVaultRate,
    ).toBe('REQUIRED');
    expect(validateMinecraft({ ...values(), mcVaultMode: 'CONVERT', mcVaultRate: 0.5 })).toEqual(
      {},
    );
  });

  test('the Vault mode and direction are enums', () => {
    expect(validateMinecraft({ ...values(), mcVaultMode: 'ALWAYS' }).mcVaultMode).toBe(
      'INVALID_VALUE',
    );
    expect(
      validateMinecraft({ ...values(), mcVaultMode: 'CONVERT', mcVaultDirection: 'NOWHERE' })
        .mcVaultDirection,
    ).toBe('INVALID_VALUE');
  });

  test('the broadcast template may not be blank and is at most 256 characters', () => {
    expect(validateMinecraft({ ...values(), mcBroadcastTemplate: '  ' }).mcBroadcastTemplate).toBe(
      'REQUIRED',
    );
    expect(
      validateMinecraft({ ...values(), mcBroadcastTemplate: 'x'.repeat(257) }).mcBroadcastTemplate,
    ).toBe('TOO_LONG');
    expect(validateMinecraft({ ...values(), mcBroadcastTemplate: 'x'.repeat(256) })).toEqual({});
  });

  test('the disabled admin commands are a subset of the five known sub-commands', () => {
    expect(validateMinecraft({ ...values(), mcDisabledAdminCommands: ['purchases'] })).toEqual({});
    expect(
      validateMinecraft({ ...values(), mcDisabledAdminCommands: ['purchases', 'purchases'] })
        .mcDisabledAdminCommands,
    ).toBe('INVALID_VALUE');
    expect(
      validateMinecraft({ ...values(), mcDisabledAdminCommands: ['format-disk'] })
        .mcDisabledAdminCommands,
    ).toBe('INVALID_VALUE');
  });

  test('PROVIDER mode shows the round-trip warning, nothing else does', () => {
    expect(vaultProviderWarning({ mcVaultMode: 'PROVIDER' })).toBe(true);
    expect(vaultProviderWarning({ mcVaultMode: 'CONVERT' })).toBe(false);
    expect(vaultProviderWarning({ mcVaultMode: 'OFF' })).toBe(false);
    expect(vaultProviderWarning(undefined)).toBe(false);
  });
});

describe('broadcast preview', () => {
  const sample = previewValues('Pano Store');
  const text = (segments) => segments.map((s) => s.text).join('');

  test('the four variables are replaced', () => {
    expect(BROADCAST_VARIABLES).toEqual(['player', 'product', 'quantity', 'store']);
    expect(text(previewSegments('{player} bought {quantity}x {product} at {store}', sample))).toBe(
      'Steve bought 1x VIP Rank at Pano Store',
    );
  });

  test('an unknown variable stays as typed', () => {
    expect(text(previewSegments('hi {nobody}', sample))).toBe('hi {nobody}');
    expect(text(previewSegments('{constructor} {__proto__}', sample))).toBe(
      '{constructor} {__proto__}',
    );
  });

  test('colour codes start a coloured segment and are not shown', () => {
    const segments = previewSegments('&aGreen &cRed', sample);
    expect(segments).toEqual([
      { text: 'Green ', color: '#55ff55', bold: false, italic: false },
      { text: 'Red', color: '#ff5555', bold: false, italic: false },
    ]);
  });

  test('bold, italic and reset', () => {
    const segments = previewSegments('&6&lGold&r plain &oit', sample);
    expect(segments[0]).toEqual({ text: 'Gold', color: '#ffaa00', bold: true, italic: false });
    expect(segments[1]).toEqual({ text: ' plain ', color: null, bold: false, italic: false });
    expect(segments[2]).toEqual({ text: 'it', color: null, bold: false, italic: true });
  });

  test('an ampersand that is not a code stays', () => {
    expect(text(previewSegments('Tom & Jerry &z', sample))).toBe('Tom & Jerry &z');
    expect(text(previewSegments('trailing &', sample))).toBe('trailing &');
  });

  test('markup in the template is plain text (never parsed)', () => {
    const segments = previewSegments('<img src=x onerror=alert(1)>&a{player}', sample);
    expect(text(segments)).toBe('<img src=x onerror=alert(1)>Steve');
  });

  test('the preview store name falls back when the store has none', () => {
    expect(previewValues('').store).toBe('Store');
    expect(previewValues('Shop').store).toBe('Shop');
  });
});

describe('server rows', () => {
  const server = {
    id: 7,
    name: 'Survival',
    type: 'SPIGOT',
    connected: true,
    mcComponentVersion: '1.2.0',
    requiredVersion: '1.3.0',
    marketState: 'VERSION_MISMATCH',
    waitingDeliveries: 4,
    downloadUrl: 'https://panel.example.com/api/market/component.jar',
    integrations: ['Vault', 'LuckPerms'],
    settings: { mcStoreMenu: false, mcBroadcast: true },
  };

  test('a row carries version, required version, waiting count, download link and override', () => {
    const row = serverRow(server);
    expect(row).toMatchObject({
      id: 7,
      name: 'Survival',
      type: 'SPIGOT',
      state: 'VERSION_MISMATCH',
      stateClass: 'text-bg-warning',
      version: '1.2.0',
      required: '1.3.0',
      integrations: ['Vault', 'LuckPerms'],
      waiting: 4,
      overrides: 2,
      downloadUrl: 'https://panel.example.com/api/market/component.jar',
      needsComponent: true,
    });
  });

  test('a download link that is not http(s) is dropped', () => {
    expect(serverRow({ ...server, downloadUrl: 'javascript:alert(1)' }).downloadUrl).toBeNull();
    expect(serverRow({ ...server, downloadUrl: 'data:text/html,x' }).downloadUrl).toBeNull();
    expect(serverRow({ ...server, downloadUrl: undefined }).downloadUrl).toBeNull();
  });

  test('the four market states and their badge classes', () => {
    expect(stateClass('READY')).toBe('text-bg-success');
    expect(stateClass('OFFLINE')).toBe('text-bg-secondary');
    expect(stateClass('COMPONENT_MISSING')).toBe('text-bg-danger');
    expect(stateClass('VERSION_MISMATCH')).toBe('text-bg-warning');
    expect(stateClass('SOMETHING')).toBe('text-bg-secondary');
    expect(serverRow({ ...server, marketState: 'READY' }).needsComponent).toBe(false);
    expect(serverRow({ ...server, marketState: 'COMPONENT_MISSING' }).needsComponent).toBe(true);
    expect(serverRow({ ...server, marketState: 'WHATEVER' }).state).toBeNull();
  });

  test('missing fields give safe values', () => {
    const row = serverRow({ id: 1, name: 'A' });
    expect(row).toMatchObject({
      type: '',
      version: '',
      required: '',
      integrations: [],
      waiting: 0,
      overrides: 0,
      downloadUrl: null,
      state: null,
    });
    expect(serverRow({ id: 1, waitingDeliveries: -3 }).waiting).toBe(0);
    expect(serverRow({ id: 1, waitingDeliveries: 'x' }).waiting).toBe(0);
  });

  test('override count: null, empty and a populated object', () => {
    expect(overrideCount({ settings: null })).toBe(0);
    expect(overrideCount({ settings: {} })).toBe(0);
    expect(overrideCount({})).toBe(0);
    expect(overrideCount({ settings: { mcStoreMenu: true } })).toBe(1);
  });

  test('serverRows of a non-array is empty', () => {
    expect(serverRows(null)).toEqual([]);
    expect(serverRows([server, server])).toHaveLength(2);
  });
});

describe('per-server override form', () => {
  test('no stored override: every control on Default', () => {
    for (const stored of [null, undefined, {}]) {
      const form = overrideForm(stored);
      for (const { key } of MC_FEATURES) expect(form[key]).toBe('default');
      expect(form.mcVaultMode).toBe('default');
      expect(form.mcVaultDirection).toBe('default');
      expect(form.mcVaultRate).toBe('');
      expect(form.mcBroadcastTemplate).toBe('');
      expect(form.adminCommandsCustom).toBe(false);
      expect(buildOverride(form, defaults())).toBeNull();
    }
  });

  test('a stored override fills the three-state controls', () => {
    const form = overrideForm({
      mcStoreMenu: false,
      mcBroadcast: true,
      mcVaultMode: 'CONVERT',
      mcVaultRate: 2.5,
      mcVaultDirection: 'TO_SERVER',
      mcBroadcastTemplate: '&aHi {player}',
      mcDisabledAdminCommands: ['purchases', 'give-credits'],
    });
    expect(form.mcStoreMenu).toBe('off');
    expect(form.mcBroadcast).toBe('on');
    expect(form.mcStoreCommand).toBe('default');
    expect(form.mcVaultMode).toBe('CONVERT');
    expect(form.mcVaultRate).toBe('2.5');
    expect(form.mcVaultDirection).toBe('TO_SERVER');
    expect(form.mcBroadcastTemplate).toBe('&aHi {player}');
    expect(form.adminCommandsCustom).toBe(true);
    expect(form.mcDisabledAdminCommands).toEqual(['give-credits', 'purchases']);
  });

  test('the override holds only the controls that are not on Default', () => {
    const form = overrideForm(null);
    form.mcStoreMenu = 'off';
    form.mcBroadcast = 'on';
    expect(buildOverride(form, defaults())).toEqual({ mcStoreMenu: false, mcBroadcast: true });
  });

  test('saving with everything on Default clears the override', () => {
    expect(overrideRequest(overrideForm({ mcStoreMenu: true }), defaults())).toEqual({
      settings: { mcStoreMenu: true },
    });
    const reset = overrideForm({ mcStoreMenu: true });
    reset.mcStoreMenu = 'default';
    expect(overrideRequest(reset, defaults())).toEqual({ settings: null });
    expect(clearOverrideRequest()).toEqual({ settings: null });
  });

  test('Vault rate and direction travel only when the effective mode is CONVERT', () => {
    const form = overrideForm(null);
    form.mcVaultRate = '3';
    form.mcVaultDirection = 'BOTH';
    // panel default OFF, no override of the mode: rate and direction are irrelevant
    expect(buildOverride(form, defaults())).toBeNull();
    // the panel default is CONVERT: they apply to the server
    expect(buildOverride(form, { ...defaults(), mcVaultMode: 'CONVERT' })).toEqual({
      mcVaultRate: 3,
      mcVaultDirection: 'BOTH',
    });
    // the server forces PROVIDER over a CONVERT default: they are dropped again
    form.mcVaultMode = 'PROVIDER';
    expect(buildOverride(form, { ...defaults(), mcVaultMode: 'CONVERT' })).toEqual({
      mcVaultMode: 'PROVIDER',
    });
    // the server forces CONVERT over an OFF default: they travel
    form.mcVaultMode = 'CONVERT';
    expect(buildOverride(form, defaults())).toEqual({
      mcVaultMode: 'CONVERT',
      mcVaultRate: 3,
      mcVaultDirection: 'BOTH',
    });
    expect(effectiveVaultMode(form, defaults())).toBe('CONVERT');
    expect(effectiveVaultMode(overrideForm(null), { mcVaultMode: 'PROVIDER' })).toBe('PROVIDER');
    expect(effectiveVaultMode(overrideForm(null), undefined)).toBe('OFF');
  });

  test('a custom admin command list is sent even when empty (it re-enables every command)', () => {
    const form = overrideForm(null);
    form.adminCommandsCustom = true;
    expect(buildOverride(form, defaults())).toEqual({ mcDisabledAdminCommands: [] });
    form.mcDisabledAdminCommands = ['purchases', 'give-credits'];
    expect(buildOverride(form, defaults())).toEqual({
      mcDisabledAdminCommands: ['give-credits', 'purchases'],
    });
  });

  test('validation covers only what is set', () => {
    const form = overrideForm(null);
    expect(validateOverride(form, defaults())).toEqual({});
    form.mcBroadcastTemplate = 'x'.repeat(257);
    expect(validateOverride(form, defaults()).mcBroadcastTemplate).toBe('TOO_LONG');
    form.mcBroadcastTemplate = '';
    form.mcVaultMode = 'CONVERT';
    form.mcVaultRate = '0';
    expect(validateOverride(form, defaults()).mcVaultRate).toBe('OUT_OF_RANGE');
    form.mcVaultRate = 'abc';
    expect(validateOverride(form, defaults()).mcVaultRate).toBe('INVALID_TYPE');
    form.mcVaultRate = '1,5';
    expect(validateOverride(form, defaults())).toEqual({});
    form.mcVaultRate = '';
    expect(validateOverride(form, defaults())).toEqual({});
    // an invalid rate behind a non-CONVERT mode is not sent and not an error
    form.mcVaultMode = 'OFF';
    form.mcVaultRate = 'abc';
    expect(validateOverride(form, defaults())).toEqual({});
  });

  test('the override can carry any mc* key', () => {
    expect([...OVERRIDE_KEYS].sort()).toEqual([...MC_KEYS].sort());
  });
});
