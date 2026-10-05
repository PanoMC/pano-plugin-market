import { describe, expect, test } from 'bun:test';
import {
  STATUS_TABS,
  WAITING_STATUSES,
  activeModalFilters,
  activeTab,
  canCancel,
  canRetry,
  listParams,
  mayHaveRun,
  retryKind,
  rowActions,
  showCancelRequested,
  whenCell,
} from './deliveries.js';
import { NODE } from './permissions.js';

const NOW = 1_800_000_000_000;
const DAY = 24 * 60 * 60 * 1000;
const om = { admin: false, permissions: [NODE.OM] };
const ov = { admin: false, permissions: [NODE.OV] };

describe('status tabs', () => {
  test('waiting contains SENT (offered, not an end state)', () => {
    expect(WAITING_STATUSES).toContain('SENT');
    expect(STATUS_TABS.find((t) => t.key === 'waiting').value.split(',')).toContain('SENT');
  });
  test('tab matching ignores csv order and unknown values give null', () => {
    expect(activeTab(null)).toBe('all');
    expect(activeTab('CONFIRMED')).toBe('done');
    expect(activeTab([...WAITING_STATUSES].reverse().join(','))).toBe('waiting');
    expect(activeTab('PENDING')).toBeNull();
  });
});

describe('retry rules (04 §7)', () => {
  test('FAILED is retryable except the four hard codes', () => {
    expect(canRetry({ status: 'FAILED', lastErrorCode: 'COMMAND_ERROR' }, NOW)).toBe(true);
    for (const code of ['RENDER_ERROR', 'NO_TARGET_SERVER', 'SERVER_REMOVED', 'INVALID_PLAYER'])
      expect(canRetry({ status: 'FAILED', lastErrorCode: code }, NOW)).toBe(false);
  });
  test('FAILED with sentAt older than 30 days is not retryable', () => {
    expect(canRetry({ status: 'FAILED', sentAt: NOW - 31 * DAY }, NOW)).toBe(false);
    expect(canRetry({ status: 'FAILED', sentAt: NOW - 29 * DAY }, NOW)).toBe(true);
    expect(canRetry({ status: 'FAILED', sentAt: null }, NOW)).toBe(true);
  });
  test('WAITING_SERVER and SENT retry, every other status does not', () => {
    expect(canRetry({ status: 'WAITING_SERVER' }, NOW)).toBe(true);
    expect(canRetry({ status: 'SENT' }, NOW)).toBe(true);
    for (const status of ['PENDING', 'SCHEDULED', 'QUEUED', 'SENDING', 'CONFIRMED', 'CANCELLED', 'WAITING_PLAYER'])
      expect(canRetry({ status }, NOW)).toBe(false);
  });
  test('SENT is offered again, the rest retries', () => {
    expect(retryKind({ status: 'SENT' })).toBe('offer-again');
    expect(retryKind({ status: 'FAILED' })).toBe('retry');
  });
});

describe('cancel rules (04 §7)', () => {
  test('only the five open states', () => {
    for (const status of ['PENDING', 'SCHEDULED', 'WAITING_SERVER', 'SENT', 'QUEUED'])
      expect(canCancel({ status })).toBe(true);
    for (const status of ['FAILED', 'CONFIRMED', 'CANCELLED', 'SENDING', 'WAITING_PLAYER'])
      expect(canCancel({ status })).toBe(false);
  });
  test('cancel requested badge only for SENT / QUEUED', () => {
    expect(showCancelRequested({ status: 'QUEUED', cancelRequested: true })).toBe(true);
    expect(showCancelRequested({ status: 'SENT', cancelRequested: true })).toBe(true);
    expect(showCancelRequested({ status: 'PENDING', cancelRequested: true })).toBe(false);
    expect(showCancelRequested({ status: 'SENT', cancelRequested: false })).toBe(false);
  });
});

describe('labels and actions', () => {
  test('UNKNOWN_OUTCOME is only "may have run" on a FAILED row', () => {
    expect(mayHaveRun({ status: 'FAILED', lastErrorCode: 'UNKNOWN_OUTCOME' })).toBe(true);
    expect(mayHaveRun({ status: 'CONFIRMED', lastErrorCode: 'UNKNOWN_OUTCOME' })).toBe(false);
    expect(mayHaveRun({ status: 'FAILED', lastErrorCode: 'COMMAND_ERROR' })).toBe(false);
  });
  test('retry / cancel need OM, view is always there', () => {
    const row = { status: 'SENT' };
    expect(rowActions(row, om, NOW)).toEqual(['offer-again', 'cancel', 'view']);
    expect(rowActions(row, ov, NOW)).toEqual(['view']);
    expect(rowActions({ status: 'CONFIRMED' }, om, NOW)).toEqual(['view']);
    expect(rowActions({ status: 'FAILED', lastErrorCode: 'RENDER_ERROR' }, om, NOW)).toEqual(['view']);
  });
  test('when cell and scheduled prefix', () => {
    expect(whenCell({ confirmedAt: 3, sentAt: 2, runAfter: 1 }, NOW)).toEqual({ at: 3, scheduled: false });
    expect(whenCell({ sentAt: 2, runAfter: NOW + 5 }, NOW)).toEqual({ at: 2, scheduled: false });
    expect(whenCell({ runAfter: NOW + 5 }, NOW)).toEqual({ at: NOW + 5, scheduled: true });
    expect(whenCell({ runAfter: NOW - 5 }, NOW)).toEqual({ at: NOW - 5, scheduled: false });
  });
});

describe('filters', () => {
  test('listParams drops empty values and honours overrides', () => {
    expect(listParams({ search: 'a', status: 'FAILED', serverId: '' }, { phase: 'GRANT', search: '' })).toEqual({
      status: 'FAILED',
      phase: 'GRANT',
    });
  });
  test('modal filters count server and action type only', () => {
    expect(activeModalFilters({ serverId: '2', phase: 'GRANT', actionType: '' })).toEqual(['serverId']);
  });
});
