import { describe, expect, test } from 'bun:test';
import {
  STATUS_TABS,
  activeTab,
  canReplay,
  canSeeBodies,
  detailRows,
  eventTypesText,
  isUnverified,
  listParams,
  prettyText,
} from './payment-events.js';
import { NODE } from './permissions.js';

const pay = { admin: false, permissions: [NODE.PAY] };
const ov = { admin: false, permissions: [NODE.OV] };
const set = { admin: false, permissions: [NODE.SET] };

describe('replay', () => {
  test('only DEFERRED and FAILED rows, and only with PAY', () => {
    expect(canReplay({ status: 'DEFERRED' }, pay)).toBe(true);
    expect(canReplay({ status: 'FAILED' }, pay)).toBe(true);
    for (const status of ['REJECTED', 'PROCESSED', 'RECEIVED', 'DUPLICATE', 'SUPERSEDED'])
      expect(canReplay({ status }, pay)).toBe(false);
    expect(canReplay({ status: 'FAILED' }, ov)).toBe(false);
    expect(canReplay({ status: 'FAILED' }, null)).toBe(false);
  });
  test('bodies need SET', () => {
    expect(canSeeBodies(set)).toBe(true);
    expect(canSeeBodies(pay)).toBe(false);
    expect(canSeeBodies({ admin: true, permissions: [] })).toBe(true);
  });
});

describe('list and cells', () => {
  test('tabs', () => {
    expect(STATUS_TABS.map((t) => t.value)).toEqual([null, 'DEFERRED', 'FAILED', 'REJECTED']);
    expect(activeTab('FAILED')).toBe('failed');
    expect(activeTab(null)).toBe('all');
    expect(activeTab('X')).toBeNull();
  });
  test('listParams keeps providerId', () => {
    expect(listParams({ status: 'FAILED', providerId: 'stripe' }, { status: null })).toEqual({
      providerId: 'stripe',
    });
  });
  test('unverified only when verified is exactly false', () => {
    expect(isUnverified({ verified: false })).toBe(true);
    expect(isUnverified({ verified: true })).toBe(false);
    expect(isUnverified({ verified: null })).toBe(false);
    expect(isUnverified({})).toBe(false);
  });
  test('event types from array or csv', () => {
    expect(eventTypesText({ eventTypes: ['Succeeded', 'Failed'] })).toBe('Succeeded, Failed');
    expect(eventTypesText({ eventTypes: 'a,b' })).toBe('a,b');
    expect(eventTypesText({})).toBe('');
  });
  test('detail rows', () => {
    const rows = Object.fromEntries(
      detailRows({ providerId: 'stripe', responseStatus: 200, eventTypes: [] }),
    );
    expect(rows.provider).toBe('stripe');
    expect(rows.http).toBe(200);
    expect(rows['event-types']).toBeNull();
  });
  test('prettyText pretty-prints JSON and keeps other text verbatim', () => {
    expect(prettyText('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(prettyText('<xml/>')).toBe('<xml/>');
    expect(prettyText({ a: 1 })).toBe('{\n  "a": 1\n}');
    expect(prettyText(null)).toBe('');
  });
});
