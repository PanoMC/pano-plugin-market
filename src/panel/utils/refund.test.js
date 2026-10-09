import { describe, expect, test } from 'bun:test';
import {
  amountError,
  buildRefundRequest,
  canOverrideSplit,
  canSubmitRefund,
  effectiveMax,
  initialForm,
  itemsPayload,
  latestWins,
  listedWarnings,
  manualForced,
  offeredModes,
  previewIsCurrent,
  previewPath,
  refundHeaders,
  refundOutcome,
  refundTotal,
  refundableItems,
  remainingCredits,
  remainingGateway,
  requiresSplitAck,
  splitWarningVisible,
  validateSplit,
} from './refund.js';
import { newIdempotency, resetIdempotency } from './api.js';

// 10.00 total: 6.00 paid at the gateway, 4.00 paid with 40 credits (1 credit = 0.10).
const mixed = {
  id: 7,
  currency: 'USD',
  totalPrice: 10,
  gatewayAmount: 6,
  paidAmount: 6,
  creditAmount: 40,
  creditValue: 4,
  refundedGatewayAmount: 0,
  refundedCreditAmount: 0,
};

const mixedWarning = { warnings: [{ code: 'MIXED_PAYMENT_SPLIT' }] };

describe('refund split validation (13 §25.1 test 32)', () => {
  test('32. gateway above remaining collected is rejected', () => {
    const r = validateSplit(mixed, 6.01, 0);
    expect(r.ok).toBe(false);
    expect(r.errors.gateway).toBe('ABOVE_REMAINING');
    const partial = { ...mixed, refundedGatewayAmount: 2 };
    expect(validateSplit(partial, 4, 0).ok).toBe(true);
    expect(validateSplit(partial, 4.01, 0).errors.gateway).toBe('ABOVE_REMAINING');
  });

  test('32. credits above remaining are rejected', () => {
    const r = validateSplit(mixed, 0, 41);
    expect(r.ok).toBe(false);
    expect(r.errors.credits).toBe('ABOVE_REMAINING');
    const partial = { ...mixed, refundedCreditAmount: 10 };
    expect(validateSplit(partial, 0, 30).ok).toBe(true);
    expect(validateSplit(partial, 0, 30.5).errors.credits).toBe('ABOVE_REMAINING');
  });

  test('32. a zero total is rejected, empty fields count as zero', () => {
    for (const [g, c] of [
      [0, 0],
      [null, null],
      ['', undefined],
    ]) {
      const r = validateSplit(mixed, g, c);
      expect(r.ok).toBe(false);
      expect(r.errors.total).toBe('ZERO_TOTAL');
    }
  });

  test('32. a valid split passes and the total is gateway + credits x unit value', () => {
    const r = validateSplit(mixed, 2.5, 20);
    expect(r).toEqual({ ok: true, errors: {}, total: 4.5 });
    expect(validateSplit(mixed, 6, 40).total).toBe(10);
    expect(validateSplit(mixed, 0, 15).total).toBe(1.5);
    expect(validateSplit(mixed, 1, 0).ok).toBe(true);
  });

  test('negative and non-numeric values are INVALID', () => {
    expect(validateSplit(mixed, -1, 0).errors.gateway).toBe('INVALID');
    expect(validateSplit(mixed, 0, NaN).errors.credits).toBe('INVALID');
    expect(validateSplit(mixed, 'abc', 5).errors.gateway).toBe('INVALID');
  });

  test('preview limits (refunds in flight) only tighten the order limits', () => {
    expect(validateSplit(mixed, 5, 0, { maxGateway: 4 }).errors.gateway).toBe('ABOVE_REMAINING');
    expect(validateSplit(mixed, 3, 0, { maxGateway: 4 }).ok).toBe(true);
    expect(validateSplit(mixed, 0, 30, { maxCredit: 25 }).errors.credits).toBe('ABOVE_REMAINING');
    // a limit above the order remainder does not loosen it
    expect(validateSplit(mixed, 7, 0, { maxGateway: 99 }).errors.gateway).toBe('ABOVE_REMAINING');
  });

  test('remaining helpers and the override switch', () => {
    expect(remainingGateway({ ...mixed, refundedGatewayAmount: 1.1 })).toBe(4.9);
    expect(remainingGateway({ paidAmount: 1, refundedGatewayAmount: 2 })).toBe(0);
    expect(remainingCredits({ ...mixed, refundedCreditAmount: 15 })).toBe(25);
    expect(canOverrideSplit(mixed)).toBe(true);
    expect(canOverrideSplit({ ...mixed, creditAmount: 0 })).toBe(false);
    expect(canOverrideSplit({ ...mixed, gatewayAmount: 0 })).toBe(false);
  });
});

describe('refundable items (13 §25.1 test 33)', () => {
  const items = [
    { id: 1, kind: 'PRODUCT', quantity: 3, refundedQuantity: 1, productName: 'A' },
    { id: 2, kind: 'BUNDLE', quantity: 1, refundedQuantity: 0, productName: 'B' },
    { id: 3, kind: 'BUNDLE_CHILD', quantity: 1, refundedQuantity: 0, productName: 'B child' },
    { id: 4, kind: 'PRODUCT', quantity: 2, refundedQuantity: 2, productName: 'C' },
  ];

  test('33. BUNDLE_CHILD and fully refunded items are excluded', () => {
    const rows = refundableItems(items);
    expect(rows.map((r) => r.id)).toEqual([1, 2]);
    expect(rows.map((r) => r.remaining)).toEqual([2, 1]);
  });

  test('33. a missing refundedQuantity counts as 0, junk input yields nothing', () => {
    expect(refundableItems([{ id: 9, kind: 'PRODUCT', quantity: 2 }])[0].remaining).toBe(2);
    expect(refundableItems(null)).toEqual([]);
    expect(refundableItems([null, undefined])).toEqual([]);
  });

  test('itemsPayload: integers 0..remaining, at least one above 0', () => {
    const rows = refundableItems(items);
    expect(itemsPayload(rows, { 1: 2, 2: 0 })).toEqual({
      ok: true,
      items: [{ orderItemId: 1, quantity: 2 }],
      errors: {},
    });
    expect(itemsPayload(rows, { 1: 3 }).errors).toEqual({ 1: true });
    expect(itemsPayload(rows, { 1: 1.5 }).ok).toBe(false);
    expect(itemsPayload(rows, { 1: -1 }).ok).toBe(false);
    expect(itemsPayload(rows, { 1: 0, 2: '' }).ok).toBe(false);
    expect(itemsPayload(rows, {}).ok).toBe(false);
    // one bad row blocks the whole payload even when another row is fine
    expect(itemsPayload(rows, { 1: 5, 2: 1 }).ok).toBe(false);
  });
});

describe('modes, amount and preview request', () => {
  test('offeredModes follows allowed.refundModes (Full is always offered)', () => {
    expect(offeredModes(['FULL'])).toEqual(['FULL']);
    expect(offeredModes([])).toEqual(['FULL']);
    expect(offeredModes(undefined)).toEqual(['FULL']);
    expect(offeredModes(['PER_LINE'])).toEqual(['FULL', 'ITEMS']);
    expect(offeredModes(['PARTIAL'])).toEqual(['FULL', 'AMOUNT', 'ITEMS']);
    expect(offeredModes(['MANUAL'])).toEqual(['FULL', 'AMOUNT', 'ITEMS']);
    expect(offeredModes(['FULL', 'PARTIAL', 'PER_LINE', 'MANUAL'])).toEqual([
      'FULL',
      'AMOUNT',
      'ITEMS',
    ]);
  });

  test('effectiveMax takes the smaller of allowed.refundMax and preview.max', () => {
    expect(effectiveMax({ refundMax: 10 }, { max: 7 })).toBe(7);
    expect(effectiveMax({ refundMax: 5 }, { max: 7 })).toBe(5);
    expect(effectiveMax({ refundMax: 5 }, null)).toBe(5);
    expect(effectiveMax(null, null)).toBeNull();
  });

  test('amountError: 0 < amount <= max', () => {
    expect(amountError(null, 10)).toBe('INVALID');
    expect(amountError(0, 10)).toBe('INVALID');
    expect(amountError(NaN, 10)).toBe('INVALID');
    expect(amountError(10.01, 10)).toBe('ABOVE_MAX');
    expect(amountError(10, 10)).toBeNull();
    expect(amountError(0.01, 10)).toBeNull();
    expect(amountError(99, null)).toBeNull();
  });

  test('previewPath: FULL has no parameter, AMOUNT / ITEMS carry theirs, invalid input skips', () => {
    const rows = refundableItems([{ id: 1, kind: 'PRODUCT', quantity: 3, refundedQuantity: 0 }]);
    expect(previewPath(7, { mode: 'FULL' })).toBe('/orders/7/refund-preview');
    expect(previewPath(7, { mode: 'AMOUNT', amount: 2.5 })).toBe(
      '/orders/7/refund-preview?amount=2.5',
    );
    expect(previewPath(7, { mode: 'AMOUNT', amount: null })).toBeNull();
    const items = previewPath(7, { mode: 'ITEMS', quantities: { 1: 2 } }, rows);
    expect(items).toBe(
      '/orders/7/refund-preview?items=' + encodeURIComponent('[{"orderItemId":1,"quantity":2}]'),
    );
    expect(previewPath(7, { mode: 'ITEMS', quantities: { 1: 9 } }, rows)).toBeNull();
  });

  test('latestWins: only the newest request is current', () => {
    const gate = latestWins();
    const a = gate.next();
    const b = gate.next();
    expect(gate.isCurrent(a)).toBe(false);
    expect(gate.isCurrent(b)).toBe(true);
    gate.cancel();
    expect(gate.isCurrent(b)).toBe(false);
  });
});

describe('split warning and acknowledgement', () => {
  test('the split warning is shown whenever credits are involved', () => {
    expect(splitWarningVisible(mixed, null)).toBe(true); // before the preview arrives
    expect(splitWarningVisible({ creditAmount: 0 }, { creditAmount: 12 })).toBe(true);
    expect(splitWarningVisible({ creditAmount: 0 }, mixedWarning)).toBe(true);
    expect(
      splitWarningVisible({ creditAmount: 0 }, { warnings: [{ code: 'CREDIT_ONLY_REFUND' }] }),
    ).toBe(true);
    expect(splitWarningVisible({ creditAmount: 0 }, { creditAmount: 0, warnings: [] })).toBe(false);
    expect(splitWarningVisible({ creditAmount: 0 }, null)).toBe(false);
  });

  test('the acknowledgement is required only for a refund that goes both ways', () => {
    expect(requiresSplitAck(mixed, mixedWarning)).toBe(true);
    expect(requiresSplitAck(mixed, { gatewayAmount: 3, creditAmount: 10, warnings: [] })).toBe(
      true,
    );
    expect(requiresSplitAck(mixed, { gatewayAmount: 0, creditAmount: 10, warnings: [] })).toBe(
      false,
    );
    expect(requiresSplitAck(mixed, { gatewayAmount: 5, creditAmount: 0 })).toBe(false);
    // override: judged on the typed parts, not on the preview
    expect(requiresSplitAck(mixed, null, { override: true, gateway: 1, credits: 5 })).toBe(true);
    expect(requiresSplitAck(mixed, mixedWarning, { override: true, gateway: 1, credits: 0 })).toBe(
      false,
    );
  });

  test('warning list leaves the mixed warning to the split box, keeps unknown codes', () => {
    const list = listedWarnings({
      warnings: [
        { code: 'MIXED_PAYMENT_SPLIT' },
        { code: 'CASHBACK_REVERSAL', amount: 2 },
        { code: 'NEW' },
      ],
    });
    expect(list.map((w) => w.code)).toEqual(['CASHBACK_REVERSAL', 'NEW']);
    expect(listedWarnings(null)).toEqual([]);
  });

  test('refundTotal: override sum, else the preview amount', () => {
    expect(refundTotal(mixed, { override: true, gateway: 2, credits: 10 }, { amount: 99 })).toBe(3);
    expect(refundTotal(mixed, { override: false }, { amount: 9.5 })).toBe(9.5);
    expect(refundTotal(mixed, { override: false }, null)).toBeNull();
    // the override switch does not exist on a gateway-only order
    expect(
      refundTotal({ ...mixed, creditAmount: 0 }, { override: true, gateway: 2 }, { amount: 6 }),
    ).toBe(6);
  });
});

describe('buildRefundRequest (13 §6.3 step 7)', () => {
  const allowed = { refundMax: 10, refundModes: ['PARTIAL', 'PER_LINE'] };
  const rows = refundableItems([{ id: 1, kind: 'PRODUCT', quantity: 3, refundedQuantity: 0 }]);
  const base = (patch = {}) => ({ ...initialForm({ revokeOnRefund: true }), ack: true, ...patch });
  const build = (form, preview = { amount: 10 }) =>
    buildRefundRequest(mixed, form, preview, rows, allowed);

  test('initialForm: revoke follows ctx.revokeOnRefund, restock off', () => {
    expect(initialForm({ revokeOnRefund: true })).toMatchObject({ revoke: true, restock: false });
    expect(initialForm({ revokeOnRefund: false }).revoke).toBe(false);
    expect(initialForm(null).revoke).toBe(false);
  });

  test('full refund sends no amount and no items', () => {
    const { request, error } = build(base());
    expect(error).toBeUndefined();
    expect(request.method).toBe('POST');
    expect(request.path).toBe('/orders/7/refunds');
    expect(request.body).toEqual({ revoke: true, restock: false, manual: false });
  });

  test('amount mode sends the amount and enforces 0 < amount <= max', () => {
    expect(build(base({ mode: 'AMOUNT', amount: 4.5 })).request.body.amount).toBe(4.5);
    expect(build(base({ mode: 'AMOUNT', amount: 10.01 })).error).toEqual({ amount: true });
    expect(build(base({ mode: 'AMOUNT', amount: 0 })).error).toEqual({ amount: true });
    // the preview max (refunds in flight) tightens allowed.refundMax
    expect(build(base({ mode: 'AMOUNT', amount: 6 }), { max: 5 }).error).toEqual({ amount: true });
  });

  test('items mode sends [{orderItemId, quantity}] and needs one above 0', () => {
    const ok = build(base({ mode: 'ITEMS', quantities: { 1: 2 } }));
    expect(ok.request.body.items).toEqual([{ orderItemId: 1, quantity: 2 }]);
    expect(build(base({ mode: 'ITEMS', quantities: {} })).error).toEqual({ items: true });
  });

  test('override sends gatewayAmount + creditAmount and omits amount', () => {
    const { request } = build(
      base({ mode: 'AMOUNT', amount: 3, override: true, gateway: 2, credits: 10 }),
      { amount: 3, ...mixedWarning },
    );
    expect(request.body).toMatchObject({ gatewayAmount: 2, creditAmount: 10 });
    expect('amount' in request.body).toBe(false);
    const bad = build(base({ override: true, gateway: 7, credits: 0 }));
    expect(bad.error.gateway).toBe(true);
    expect(build(base({ override: true, gateway: 0, credits: 0 })).error.total).toBe(true);
    // credit-only override (gateway 0) is allowed
    expect(
      build(base({ override: true, gateway: 0, credits: 10 })).request.body.gatewayAmount,
    ).toBe(0);
  });

  test('override is ignored on an order that was not paid both ways', () => {
    const gatewayOnly = {
      ...mixed,
      creditAmount: 0,
      creditValue: 0,
      gatewayAmount: 10,
      paidAmount: 10,
    };
    const { request } = buildRefundRequest(
      gatewayOnly,
      base({ override: true, gateway: 1, credits: 1 }),
      { amount: 10 },
      rows,
      allowed,
    );
    expect('gatewayAmount' in request.body).toBe(false);
  });

  test('a mixed refund needs the acknowledgement', () => {
    const preview = { amount: 10, gatewayAmount: 6, creditAmount: 40, ...mixedWarning };
    expect(build(base({ ack: false }), preview).error).toEqual({ ack: true });
    expect(build(base({ ack: true }), preview).request).toBeDefined();
    // a credit-only refund needs none
    expect(
      build(base({ ack: false }), { amount: 4, gatewayAmount: 0, creditAmount: 40 }).request,
    ).toBeDefined();
  });

  test('manual: switch value, forced by the preview, reason 3..255 required', () => {
    expect(build(base({ manual: true, reason: 'cash back' })).request.body.manual).toBe(true);
    expect(build(base({ manual: true })).error).toEqual({ reason: true });
    expect(build(base({ manual: true, reason: 'ab' })).error).toEqual({ reason: true });
    expect(manualForced({ mode: 'MANUAL' })).toBe(true);
    expect(manualForced({ mode: 'GATEWAY' })).toBe(false);
    const forced = build(base({ manual: false, reason: 'bank transfer' }), {
      amount: 10,
      mode: 'MANUAL',
    });
    expect(forced.request.body.manual).toBe(true);
    expect(build(base({ manual: false }), { amount: 10, mode: 'MANUAL' }).error).toEqual({
      reason: true,
    });
  });

  test('reason: trimmed, omitted when empty, at most 255 characters', () => {
    expect(build(base({ reason: '  defect  ' })).request.body.reason).toBe('defect');
    expect('reason' in build(base({ reason: '   ' })).request.body).toBe(false);
    expect(build(base({ reason: 'x'.repeat(256) })).error).toEqual({ reason: true });
    expect(build(base({ reason: 'x'.repeat(255) })).error).toBeUndefined();
  });

  test('revoke / restock switches and revokeFirst only when the preview recommends it', () => {
    const body = build(base({ revoke: false, restock: true })).request.body;
    expect(body).toMatchObject({ revoke: false, restock: true });
    expect('revokeFirst' in body).toBe(false);
    const recommended = build(base(), { amount: 10, recommendRevokeFirst: true }).request.body;
    expect(recommended.revokeFirst).toBe(true);
    const unticked = build(base({ revokeFirst: false }), {
      amount: 10,
      recommendRevokeFirst: true,
    });
    expect('revokeFirst' in unticked.request.body).toBe(false);
  });

  test('UPGRADE_DEPENDENT needs an explicit cascade choice and sends it', () => {
    const preview = { amount: 10, warnings: [{ code: 'UPGRADE_DEPENDENT', successorOrderId: 9 }] };
    expect(build(base(), preview).error).toEqual({ cascade: true });
    expect(build(base({ cascade: true }), preview).request.body.cascadeUpgrade).toBe(true);
    expect(build(base({ cascade: false }), preview).request.body.cascadeUpgrade).toBe(false);
    expect('cascadeUpgrade' in build(base()).request.body).toBe(false);
  });
});

describe('idempotent submit (one Idempotency-Key per unchanged body)', () => {
  const body = { revoke: true, restock: false, manual: false, amount: 4 };

  test('the same body keeps its key, a changed body gets a new one, a reset starts over', () => {
    const state = newIdempotency();
    const first = refundHeaders(state, body)['Idempotency-Key'];
    expect(first).toMatch(/^[0-9a-f-]{36}$/);
    expect(refundHeaders(state, { ...body })['Idempotency-Key']).toBe(first);
    const changed = refundHeaders(state, { ...body, amount: 5 })['Idempotency-Key'];
    expect(changed).not.toBe(first);
    expect(refundHeaders(state, { ...body, amount: 5 })['Idempotency-Key']).toBe(changed);
    resetIdempotency(state);
    expect(refundHeaders(state, { ...body, amount: 5 })['Idempotency-Key']).not.toBe(changed);
  });

  test('two submits of the unchanged built request (double click, network retry) share the key', () => {
    const state = newIdempotency();
    const form = { ...initialForm({ revokeOnRefund: true }) };
    const a = buildRefundRequest(mixed, { ...form, ack: true }, { amount: 10 }, [], null).request;
    const b = buildRefundRequest(mixed, { ...form, ack: true }, { amount: 10 }, [], null).request;
    expect(refundHeaders(state, a.body)['Idempotency-Key']).toBe(
      refundHeaders(state, b.body)['Idempotency-Key'],
    );
  });
});

describe('refundOutcome (13 §6.3 step 7)', () => {
  test('SUCCEEDED toasts success, PENDING / REQUESTED toast pending, both keep the key', () => {
    expect(refundOutcome({ ok: true, body: { refund: { status: 'SUCCEEDED' } } })).toEqual({
      kind: 'done',
      toast: 'modals.refund.toast-success',
      reset: false,
    });
    for (const status of ['PENDING', 'REQUESTED']) {
      expect(refundOutcome({ ok: true, body: { refund: { status } } })).toEqual({
        kind: 'done',
        toast: 'modals.refund.toast-pending',
        reset: false,
      });
    }
  });

  test('REFUND_NOT_SUPPORTED switches the manual toggle on and stays open', () => {
    expect(refundOutcome({ ok: false, error: 'REFUND_NOT_SUPPORTED', body: {} })).toEqual({
      kind: 'manual',
      reset: false,
    });
  });

  test('PAYMENT_PROVIDER_ERROR closes (the refund row is FAILED) and keeps the key', () => {
    expect(refundOutcome({ ok: false, error: 'PAYMENT_PROVIDER_ERROR', body: {} })).toEqual({
      kind: 'providerError',
      reset: false,
    });
  });

  test('INVALID_REFUND_AMOUNT carries max, other codes stay open, a network error keeps the key', () => {
    expect(
      refundOutcome({ ok: false, error: 'INVALID_REFUND_AMOUNT', body: { max: 3.5 } }),
    ).toEqual({ kind: 'invalidAmount', max: 3.5, reset: false });
    expect(refundOutcome({ ok: false, error: 'INVALID_REFUND_AMOUNT', body: {} }).max).toBeNull();
    expect(refundOutcome({ ok: false, error: 'CASCADE_DECISION_REQUIRED', body: {} })).toEqual({
      kind: 'error',
      reset: false,
    });
    expect(refundOutcome({ ok: false, error: 'NETWORK_ERROR', body: {} }).reset).toBe(false);
    expect(refundOutcome({ ok: false, error: 'IDEMPOTENCY_CONFLICT', body: {} })).toEqual({
      kind: 'error',
      reset: true,
    });
    expect(refundOutcome({ ok: false, error: 'INVALID_ORDER_TRANSITION', body: {} }).kind).toBe(
      'stale',
    );
  });
});

describe('submit gate: a preview for the form as it is, nothing sent once closing', () => {
  const ready = {
    status: 'READY',
    closing: false,
    previewCurrent: true,
    hasPreview: true,
    loadError: null,
    amountMax: null,
    valid: true,
  };

  test('previewIsCurrent: a changed amount is not submittable until the preview for its path arrived', () => {
    const shown = previewPath(7, { mode: 'FULL' }, []);
    const typed = previewPath(7, { mode: 'AMOUNT', amount: 10 }, []);
    expect(typed).not.toBe(shown);
    // FULL preview on screen, the form already switched to the typed amount (debounce / in flight)
    expect(previewIsCurrent(typed, shown, 'READY')).toBe(false);
    expect(previewIsCurrent(typed, shown, 'PREVIEWING')).toBe(false);
    // the preview for that path arrived
    expect(previewIsCurrent(typed, typed, 'READY')).toBe(true);
    // still in flight for the same path, or invalid input (no path at all)
    expect(previewIsCurrent(typed, typed, 'PREVIEWING')).toBe(false);
    expect(previewIsCurrent(null, null, 'READY')).toBe(false);
    expect(previewIsCurrent(shown, null, 'READY')).toBe(false);
    // typing back to the amount that is on screen needs no new preview
    expect(previewIsCurrent(shown, shown, 'READY')).toBe(true);
  });

  test('canSubmitRefund is true only for a ready, current, valid, open form', () => {
    expect(canSubmitRefund(ready)).toBe(true);
    expect(canSubmitRefund({ ...ready, previewCurrent: false })).toBe(false);
    expect(canSubmitRefund({ ...ready, hasPreview: false })).toBe(false);
    expect(canSubmitRefund({ ...ready, valid: false })).toBe(false);
    expect(canSubmitRefund({ ...ready, loadError: 'NETWORK_ERROR' })).toBe(false);
    expect(canSubmitRefund({ ...ready, amountMax: 3.5 })).toBe(false);
    for (const status of ['SUBMITTING', 'LOADING', 'PREVIEWING']) {
      expect(canSubmitRefund({ ...ready, status })).toBe(false);
    }
  });

  test('closing blocks every further submit, also after the status fell back to READY', () => {
    expect(canSubmitRefund({ ...ready, closing: true })).toBe(false);
    expect(
      canSubmitRefund({
        status: 'READY',
        closing: true,
        previewCurrent: true,
        hasPreview: true,
        valid: true,
      }),
    ).toBe(false);
  });

  test('an unchanged body keeps its key after success and provider error (late replay is the same refund)', () => {
    const state = newIdempotency();
    const body = { amount: 5, revoke: false, restock: false, manual: false };
    const first = refundHeaders(state, body)['Idempotency-Key'];
    expect(refundOutcome({ ok: true, body: { refund: { status: 'SUCCEEDED' } } }).reset).toBe(
      false,
    );
    expect(refundOutcome({ ok: false, error: 'PAYMENT_PROVIDER_ERROR', body: {} }).reset).toBe(
      false,
    );
    expect(refundHeaders(state, { ...body })['Idempotency-Key']).toBe(first);
  });
});
