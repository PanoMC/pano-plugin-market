import { describe, expect, test } from 'bun:test';
import {
  TYPE_TABS,
  activeTypeTab,
  blockFieldError,
  buildBlockBody,
  expiryState,
  listParams,
  rowActions,
  validateBlock,
  valueError,
} from './blocks.js';
import { NODE } from './permissions.js';

const NOW = 1_800_000_000_000;
const om = { admin: false, permissions: [NODE.OM] };
const omOv = { admin: false, permissions: [NODE.OM, NODE.OV] };

describe('block value validation (isIpOrCidr, test 25)', () => {
  test('IP type accepts literals and CIDR', () => {
    for (const ok of ['1.2.3.4', '1.2.3.0/24', '::1', '2001:db8::/32'])
      expect(valueError('IP', ok)).toBeNull();
  });
  test('IP type rejects bad prefixes and garbage', () => {
    for (const bad of ['1.2.3.4/33', '256.1.1.1', 'abc', '1.2.3.4/'])
      expect(valueError('IP', bad)).toBe('INVALID');
    expect(valueError('IP', '')).toBe('REQUIRED');
  });
  test('PLAYER pattern', () => {
    for (const ok of ['Steve', '.Bedrock_1', '*Geyser'])
      expect(valueError('PLAYER', ok)).toBeNull();
    for (const bad of ['a b', 'x'.repeat(33)]) expect(valueError('PLAYER', bad)).toBe('INVALID');
  });
  test('EMAIL needs exactly one @ and at most 255 chars', () => {
    expect(valueError('EMAIL', 'a@b.c')).toBeNull();
    expect(valueError('EMAIL', 'a@@b.c')).toBe('INVALID');
    expect(valueError('EMAIL', 'ab.c')).toBe('INVALID');
    expect(valueError('EMAIL', '@b.c')).toBe('INVALID');
    expect(valueError('EMAIL', 'a'.repeat(252) + '@b.cd')).toBe('TOO_LONG');
  });
  test('USER cannot be created by hand', () => {
    expect(validateBlock({ type: 'USER', value: '5' }, NOW).ok).toBe(false);
  });
});

describe('validateBlock', () => {
  const base = { type: 'IP', value: '1.2.3.4', reason: '', expires: false, expiresAt: null };
  test('valid form', () => expect(validateBlock(base, NOW)).toEqual({ ok: true }));
  test('reason over 255 is rejected', () => {
    const r = validateBlock({ ...base, reason: 'x'.repeat(256) }, NOW);
    expect(r.errors.reason).toBe('TOO_LONG');
  });
  test('expiry switch needs a future date', () => {
    expect(validateBlock({ ...base, expires: true }, NOW).errors.expiresAt).toBe('REQUIRED');
    expect(
      validateBlock({ ...base, expires: true, expiresAt: NOW - 1 }, NOW).errors.expiresAt,
    ).toBe('PAST');
    expect(validateBlock({ ...base, expires: true, expiresAt: NOW + 1000 }, NOW).ok).toBe(true);
    expect(validateBlock({ ...base, expires: false, expiresAt: NOW - 1 }, NOW).ok).toBe(true);
  });
});

describe('buildBlockBody', () => {
  test('lower-cases the value and omits empty reason / expiry', () => {
    expect(
      buildBlockBody({ type: 'EMAIL', value: ' Foo@Bar.COM ', reason: '  ', expires: false }),
    ).toEqual({
      type: 'EMAIL',
      value: 'foo@bar.com',
    });
  });
  test('includes reason and expiresAt', () => {
    expect(
      buildBlockBody({ type: 'IP', value: '::1', reason: ' spam ', expires: true, expiresAt: NOW }),
    ).toEqual({ type: 'IP', value: '::1', reason: 'spam', expiresAt: NOW });
  });
});

describe('list helpers', () => {
  test('type tabs', () => {
    expect(TYPE_TABS.map((t) => t.value)).toEqual([null, 'PLAYER', 'EMAIL', 'IP', 'USER']);
    expect(activeTypeTab(null)).toBe('all');
    expect(activeTypeTab('IP')).toBe('ips');
    expect(activeTypeTab('WAT')).toBeNull();
  });
  test('listParams drops empty and applies overrides', () => {
    expect(
      listParams({ type: 'IP', source: '', search: 'x' }, { source: 'CHARGEBACK', search: '' }),
    ).toEqual({
      type: 'IP',
      source: 'CHARGEBACK',
    });
  });
  test('expiry state', () => {
    expect(expiryState({ expiresAt: null }, NOW)).toBe('never');
    expect(expiryState({ expiresAt: NOW - 1 }, NOW)).toBe('expired');
    expect(expiryState({ expiresAt: NOW + 1 }, NOW)).toBe('active');
  });
  test('row actions need OM to remove and OV to view the order', () => {
    expect(rowActions({ orderId: 4 }, om)).toEqual(['remove']);
    expect(rowActions({ orderId: 4 }, omOv)).toEqual(['remove', 'view-order']);
    expect(rowActions({ orderId: null }, omOv)).toEqual(['remove']);
    expect(rowActions({}, { admin: false, permissions: [NODE.OV] })).toEqual([]);
  });
  test('server errors map to the value field', () => {
    expect(blockFieldError('BLOCK_ALREADY_EXISTS')).toEqual({
      field: 'value',
      code: 'BLOCK_ALREADY_EXISTS',
    });
    expect(blockFieldError('NETWORK_ERROR')).toBeNull();
  });
});
