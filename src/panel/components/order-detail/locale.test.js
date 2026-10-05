import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import { ACTION_DEFS } from './actions.js';
import {
  ACTION_TYPES,
  ACTOR_TYPES,
  DELIVERY_ERRORS,
  DISPUTE_ORIGINS,
  INVOICE_TYPES,
  MAIL_KINDS,
  ORDER_EVENT_TYPES,
  ORDER_SOURCES,
  PHASES,
  REFUND_ORIGINS,
  totalsRows,
} from './model.js';
import { DISPUTE_RESOLUTIONS, RESEND_KINDS, RERUN_SCOPES, STATUS_MODES } from './requests.js';

// Keys built at run time (`$_(`...${x}`)`) are invisible to check:i18n, so every family the order
// detail page builds dynamically is verified here, in all three locales.
const dir = path.resolve(import.meta.dir, '../../../locales/panel');
const LOCALES = Object.fromEntries(
  ['en-US', 'tr', 'ru'].map((l) => [
    l,
    JSON.parse(fs.readFileSync(path.join(dir, `${l}.json`), 'utf8')),
  ]),
);
const get = (tree, key) => key.split('.').reduce((node, part) => node?.[part], tree);
const missing = (keys) =>
  Object.entries(LOCALES).flatMap(([lang, tree]) =>
    keys
      .filter((k) => typeof get(tree, k) !== 'string' || get(tree, k).trim() === '')
      .map((k) => `${lang}:${k}`),
  );

const family = (prefix, values) => values.map((v) => `${prefix}.${v}`);

describe('order detail locale keys (tr, en-US, ru)', () => {
  test('page and row actions', () => {
    const rowActions = [
      'rerun',
      'rerun-all',
      'revoke-all',
      'revoke-item',
      'create-shipment',
      'events',
      'check-status',
      'retry',
      'cancel-row',
      'offer-again',
      'track',
      'label',
      'generic-label',
      'regenerate',
    ];
    expect(
      missing([
        ...family(
          'pages.order-detail.actions',
          ACTION_DEFS.map((d) => d.id),
        ),
        ...family('pages.order-detail.actions', rowActions),
        ...family(
          'pages.order-detail.actions',
          DISPUTE_RESOLUTIONS.map((s) => `dispute-${s}`),
        ),
      ]),
    ).toEqual([]);
  });

  test('confirmation modals: title, description and CTA', () => {
    const names = [
      'revoke-all',
      'revoke-item',
      'chargeback',
      'anonymize',
      'invoice-regenerate',
      'refund-retry',
      'refund-cancel',
      'delivery-retry',
      'delivery-offer-again',
      'delivery-cancel',
      'shipment-cancel',
      ...DISPUTE_RESOLUTIONS.map((s) => `dispute-${s}`),
    ];
    expect(
      missing(
        names.flatMap((n) =>
          ['title', 'description', 'cta'].map((p) => `pages.order-detail.confirm.${n}.${p}`),
        ),
      ),
    ).toEqual([]);
  });

  test('every toast key the page uses', () => {
    const source = fs.readFileSync(
      path.resolve(import.meta.dir, '../../pages/OrderDetail.svelte'),
      'utf8',
    );
    const keys = [
      ...new Set([...source.matchAll(/'(pages\.order-detail\.toast\.[a-z-]+)'/g)].map((m) => m[1])),
    ];
    expect(keys.length).toBeGreaterThan(10);
    expect(missing(keys)).toEqual([]);
    const cards = ['TotalsCard', 'NoteCard'].flatMap((n) =>
      [
        ...fs
          .readFileSync(path.resolve(import.meta.dir, `${n}.svelte`), 'utf8')
          .matchAll(/'(pages\.order-detail\.toast\.[a-z-]+)'/g),
      ].map((m) => m[1]),
    );
    expect(cards.length).toBe(3);
    expect(missing(cards)).toEqual([]);
  });

  test('status, review, dispute, rerun, resend and events modals', () => {
    const modes = Object.keys(STATUS_MODES);
    expect(
      missing([
        ...modes.flatMap((m) =>
          ['title', 'cta', 'toast'].map((p) => `modals.order-status.${p}.${m}`),
        ),
        ...['ACCEPT', 'REJECT'].flatMap((d) =>
          ['decision', 'cta', 'toast'].map((p) => `modals.review.${p}.${d}`),
        ),
        ...family('modals.rerun.scope', RERUN_SCOPES),
        ...[
          'title',
          'toast',
          'toast-skipped',
          'toast-none',
          'phase',
          'grant-again',
          'grant-again-title',
          'cta',
        ].map((k) => `modals.rerun.${k}`),
        ...['title', 'kind', 'shipment', 'recipient', 'cta', 'toast'].map(
          (k) => `modals.resend-mail.${k}`,
        ),
        ...['title', 'amount', 'reason', 'cta', 'toast'].map((k) => `modals.dispute.${k}`),
        ...[
          'review.title',
          'review.refund',
          'review.force',
          'review.note',
          'order-status.note',
        ].map((k) => `modals.${k}`),
        ...[
          'title',
          'headers',
          'body',
          'table.date',
          'table.direction',
          'table.channel',
          'table.event',
          'table.verified',
          'table.response',
          'table.ip',
        ].map((k) => `modals.payment-events.${k}`),
      ]),
    ).toEqual([]);
  });

  test('resend kinds and re-run toasts have the placeholders the code passes', () => {
    expect(missing(family('enums.mail-kind', RESEND_KINDS))).toEqual([]);
    for (const tree of Object.values(LOCALES)) {
      expect(get(tree, 'modals.rerun.toast')).toContain('{count');
      expect(get(tree, 'modals.rerun.toast-skipped')).toContain('{skipped}');
      expect(get(tree, 'modals.rerun.toast-skipped')).toContain('{count}');
    }
  });

  test('totals rows, delivery states, invoice types and address labels', () => {
    const full = totalsRows({
      subtotal: 1,
      discountTotal: 1,
      couponDiscount: 1,
      creatorDiscount: 1,
      upgradeDiscount: 1,
      shippingTotal: 1,
      paymentFee: 1,
      vatTotal: 1,
      totalPrice: 1,
      creditAmount: 1,
      creditValue: 1,
      gatewayAmount: 1,
      paidAmount: 2,
      refundedTotal: 1,
      displayCurrency: 'USD',
    });
    expect(
      missing([
        ...full.map((r) => `pages.order-detail.totals.${r.key}`),
        'pages.order-detail.totals.vat-included',
        'pages.order-detail.totals.refunded-gateway',
        'pages.order-detail.totals.refunded-credits',
      ]),
    ).toEqual([]);
    expect(
      missing([
        ...family('pages.order-detail.delivery-state', ['fulfilled', 'failed', 'pending']),
        ...family('pages.order-detail.invoice-type', INVOICE_TYPES),
        ...family('pages.order-detail.address', [
          'type',
          'taxOffice',
          'taxNumber',
          'identityNumber',
        ]),
      ]),
    ).toEqual([]);
  });

  test('enum families rendered by the cards', () => {
    expect(
      missing([
        ...family('enums.order-event', ORDER_EVENT_TYPES),
        ...family('enums.actor', ACTOR_TYPES),
        ...family('enums.refund-origin', REFUND_ORIGINS),
        ...family('enums.dispute-origin', DISPUTE_ORIGINS),
        ...family('enums.order-source', ORDER_SOURCES),
        ...family('enums.mail-kind', MAIL_KINDS),
        ...family('enums.action-type', ACTION_TYPES),
        ...family('enums.phase', PHASES),
        ...family('enums.delivery-error', DELIVERY_ERRORS),
      ]),
    ).toEqual([]);
  });

  test('card, table and customer labels', () => {
    const t = LOCALES['en-US'].pages['order-detail'];
    const groups = { cards: 15, table: 27, customer: 8 };
    for (const [group, min] of Object.entries(groups))
      expect(Object.keys(t[group]).length).toBeGreaterThanOrEqual(min);
    expect(missing(Object.keys(t.cards).map((k) => `pages.order-detail.cards.${k}`))).toEqual([]);
    expect(missing(Object.keys(t.table).map((k) => `pages.order-detail.table.${k}`))).toEqual([]);
  });
});
