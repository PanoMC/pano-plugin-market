import { describe, expect, test } from 'bun:test';
import {
  LAG_DANGER_SECONDS,
  QUEUE_CARDS,
  RECHECK_CREDITS_PATH,
  healthModel,
  isHealthReport,
  lagIsDanger,
} from './health.js';

const report = {
  runtimeState: 'READY',
  schema: {
    ok: false,
    missing: ['table market_goal', { kind: 'column', target: 'x' }],
    unfixed: ['index y'],
  },
  jobs: [
    { name: 'expire-orders', lastRunAt: 1700000000000, lagSeconds: 12, lastError: null },
    { name: 'deliveries', lastRunAt: 1700000000000, lagSeconds: 301, lastError: 'boom' },
    { name: 'never-ran', lastRunAt: null, lagSeconds: null },
  ],
  queues: {
    deliveriesPending: 3,
    deliveriesFailed: 1,
    mailsPending: 0,
    webhooksPending: 2,
    deferredEvents: 5,
    failedEvents: 4,
  },
  providers: [
    { id: 'stripe', state: 'ACTIVE' },
    { id: 'iyzico', state: 'NOT_CONFIGURED' },
  ],
  servers: [{ id: 2, marketState: 'COMPONENT_MISSING', waitingDeliveries: 6 }],
  credits: { ok: false, checkedAt: 1700000000000, problems: ['balance mismatch for user 5'] },
  mail: 'HOST_TOO_OLD',
  mailEnabled: false,
  ipTrust: 'UNCONFIGURED_PROXY',
  lockedSubjects: 3,
  rejectedEventsLastHour: 9,
};

describe('health model', () => {
  test('schema status with the missing objects as a text list', () => {
    const model = healthModel(report);
    expect(model.schemaOk).toBe(false);
    expect(model.missing[0]).toBe('table market_goal');
    expect(typeof model.missing[1]).toBe('string');
    expect(model.unfixed).toEqual(['index y']);
    expect(healthModel({ schema: { ok: true } }).schemaOk).toBe(true);
  });

  test('jobs: lag above five minutes is a danger, the boundary is not', () => {
    expect(LAG_DANGER_SECONDS).toBe(300);
    expect(lagIsDanger(300)).toBe(false);
    expect(lagIsDanger(301)).toBe(true);
    expect(lagIsDanger(null)).toBe(false);
    const jobs = healthModel(report).jobs;
    expect(jobs.map((j) => j.lagDanger)).toEqual([false, true, false]);
    expect(jobs[1].lastError).toBe('boom');
    expect(jobs[0].lastError).toBe('');
    expect(jobs[2].lagSeconds).toBeNull();
  });

  test('six queue cards with the variants of 13 §17', () => {
    const cards = healthModel(report).queues;
    expect(cards).toHaveLength(6);
    expect(QUEUE_CARDS.map((c) => c.variant)).toEqual([
      'secondary',
      'danger',
      'secondary',
      'secondary',
      'warning',
      'danger',
    ]);
    expect(cards.map((c) => c.value)).toEqual([3, 1, 0, 2, 5, 4]);
    expect(cards[1]).toMatchObject({ id: 'deliveries-failed', key: 'deliveriesFailed' });
  });

  test('providers, servers, credits self-check, mail, ip trust and the counters', () => {
    const model = healthModel(report);
    expect(model.providers).toEqual([
      { id: 'stripe', state: 'ACTIVE' },
      { id: 'iyzico', state: 'NOT_CONFIGURED' },
    ]);
    expect(model.servers).toEqual([{ id: 2, marketState: 'COMPONENT_MISSING', waiting: 6 }]);
    expect(model.credits).toEqual({
      ok: false,
      checkedAt: 1700000000000,
      problems: ['balance mismatch for user 5'],
    });
    expect(model.mail).toBe('HOST_TOO_OLD');
    expect(model.mailEnabled).toBe(false);
    expect(model.ipTrustWarning).toBe(true);
    expect(model.lockedSubjects).toBe(3);
    expect(model.rejectedEventsLastHour).toBe(9);
    expect(model.runtimeState).toBe('READY');
  });

  test('an OK report raises no flags', () => {
    const model = healthModel({
      ipTrust: 'OK',
      mail: 'OK',
      mailEnabled: true,
      credits: { ok: true, problems: [] },
    });
    expect(model.ipTrustWarning).toBe(false);
    expect(model.credits.ok).toBe(true);
    expect(model.mailEnabled).toBe(true);
  });

  test('an empty or missing report gives safe zeros, never a crash', () => {
    for (const input of [null, undefined, {}, 'x', 5]) {
      const model = healthModel(input);
      expect(model.jobs).toEqual([]);
      expect(model.providers).toEqual([]);
      expect(model.servers).toEqual([]);
      expect(model.credits).toBeNull();
      expect(model.queues.every((c) => c.value === 0)).toBe(true);
      expect(model.lockedSubjects).toBe(0);
      expect(model.ipTrustWarning).toBe(false);
    }
  });

  test('negative or non-numeric counters become zero', () => {
    const model = healthModel({
      queues: { deliveriesPending: -1, mailsPending: 'x' },
      lockedSubjects: -5,
    });
    expect(model.queues[0].value).toBe(0);
    expect(model.queues[2].value).toBe(0);
    expect(model.lockedSubjects).toBe(0);
  });

  test('the re-check button asks for the credit self-check', () => {
    expect(RECHECK_CREDITS_PATH).toBe('/health?recheck=credits');
  });

  test('a failed GET is not a report', () => {
    expect(isHealthReport({ error: 'NETWORK_ERROR' })).toBe(false);
    expect(isHealthReport(null)).toBe(false);
    expect(isHealthReport({ jobs: [] })).toBe(true);
  });
});
