import { describe, expect, test } from 'bun:test';
import {
  activeVariants,
  addLine,
  buildBody,
  buildLine,
  canSendMail,
  canonicalFieldValues,
  createQuoteRunner,
  detailPath,
  fieldError,
  formErrors,
  initialForm,
  lineIdentity,
  mapLineErrors,
  matchQuoteLines,
  overLimitIndexes,
  playerFromSearch,
  quantityError,
  removeLine,
  serverOptions,
  setQuantity,
  submitFailure,
  submitHeaders,
  submitState,
} from './model.js';
import { newIdempotency } from '../../utils/api.js';

const line = (extra = {}) => ({ productId: 1, quantity: 1, ...extra });

describe('?player=', () => {
  test('pre-fills the player', () => {
    expect(playerFromSearch('?player=Notch')).toBe('Notch');
    expect(playerFromSearch('?x=1&player=%20Steve%20')).toBe('Steve');
    expect(playerFromSearch('')).toBe('');
    expect(playerFromSearch(null)).toBe('');
    expect(playerFromSearch('?player=' + 'a'.repeat(40))).toHaveLength(32);
  });
  test('the pre-filled form carries it into the body', () => {
    const form = initialForm(playerFromSearch('?player=Notch'));
    expect(form.playerUsername).toBe('Notch');
    expect(buildBody(form, [line()]).playerUsername).toBe('Notch');
  });
});

describe('cart lines', () => {
  test('identical lines merge by summing the quantity', () => {
    const a = line({
      variantId: 2,
      fieldValues: { nick: 'x', empty: '' },
      targetServerId: 3,
      quantity: 2,
    });
    const b = line({ variantId: 2, fieldValues: { nick: 'x' }, targetServerId: 3, quantity: 3 });
    const lines = addLine(addLine([], a), b);
    expect(lines).toHaveLength(1);
    expect(lines[0].quantity).toBe(5);
  });
  test('differing variant, field value or server stays separate', () => {
    let lines = addLine([], line());
    lines = addLine(lines, line({ variantId: 1 }));
    lines = addLine(lines, line({ fieldValues: { a: '1' } }));
    lines = addLine(lines, line({ targetServerId: 9 }));
    expect(lines).toHaveLength(4);
  });
  test('field value order and number/string do not matter', () => {
    expect(lineIdentity(line({ fieldValues: { a: 1, b: 'x' } }))).toBe(
      lineIdentity(line({ fieldValues: { b: 'x', a: '1' } })),
    );
    expect(canonicalFieldValues({ b: true, a: null, c: '' })).toEqual({ b: 'true' });
  });
  test('remove and set quantity', () => {
    const lines = [line(), line({ productId: 2 })];
    expect(removeLine(lines, 0)).toEqual([line({ productId: 2 })]);
    expect(setQuantity(lines, 1, 4)[1].quantity).toBe(4);
  });
  test('quantityError', () => {
    expect(quantityError(1)).toBeNull();
    expect(quantityError('')).toBe('REQUIRED');
    expect(quantityError(0)).toBe('INVALID');
    expect(quantityError(1.5)).toBe('INVALID');
    expect(quantityError(5, { max: 3 })).toBe('MAX_QUANTITY');
    expect(quantityError(5, { max: 3, force: true })).toBeNull();
  });
});

describe('product line builder', () => {
  const product = {
    id: 7,
    hasVariants: true,
    maxQuantityPerOrder: 2,
    variants: [
      { id: 1, status: 'ACTIVE' },
      { id: 2, status: 'INACTIVE' },
    ],
    fields: [
      { fieldKey: 'nick', type: 'USERNAME', required: true },
      { fieldKey: 'gift', type: 'CHECKBOX', required: false },
    ],
    actions: [{ serverMode: 'BUYER_CHOICE' }],
    serverChoices: [10, 11, 99],
  };
  const servers = [
    { id: 10, name: 'a' },
    { id: 11, name: 'b' },
  ];

  test('only active variants, server choices intersect the servers', () => {
    expect(activeVariants(product).map((v) => v.id)).toEqual([1]);
    expect(serverOptions(product, servers).map((s) => s.id)).toEqual([10, 11]);
  });
  test('valid selection builds the CartLine', () => {
    const r = buildLine(
      product,
      { variantId: '1', quantity: 2, values: { nick: 'Notch', gift: false }, serverId: '10' },
      { servers },
    );
    expect(r.line).toEqual({
      productId: 7,
      quantity: 2,
      variantId: 1,
      fieldValues: { nick: 'Notch' },
      targetServerId: 10,
    });
  });
  test('missing variant, field and server are reported', () => {
    const r = buildLine(product, { quantity: 1, values: {} }, { servers });
    expect(r.errors.variant).toBe('REQUIRED');
    expect(r.errors.fields.nick).toBe('REQUIRED');
    expect(r.errors.serverId).toBe('REQUIRED');
  });
  test('inactive variant and removed server are rejected', () => {
    const r = buildLine(
      product,
      { variantId: 2, quantity: 1, values: { nick: 'a' }, serverId: 99 },
      { servers },
    );
    expect(r.errors.variant).toBe('REQUIRED');
    expect(r.errors.serverId).toBe('REQUIRED');
  });
  test('quantity above the product maximum needs force', () => {
    const sel = { variantId: 1, quantity: 3, values: { nick: 'a' }, serverId: 10 };
    expect(buildLine(product, sel, { servers }).errors.quantity).toBe('MAX_QUANTITY');
    expect(buildLine(product, sel, { servers, force: true }).line.quantity).toBe(3);
  });
  test('field rules per type', () => {
    expect(fieldError({ type: 'DISCORD_ID' }, '123')).toBe('INVALID');
    expect(fieldError({ type: 'DISCORD_ID' }, '12345678901234567')).toBeNull();
    expect(fieldError({ type: 'EMAIL' }, 'nope')).toBe('INVALID');
    expect(fieldError({ type: 'NUMBER', minValue: 1, maxValue: 5 }, '9')).toBe('INVALID');
    expect(fieldError({ type: 'NUMBER' }, '1.5')).toBe('INVALID');
    expect(fieldError({ type: 'SELECT', options: [{ value: 'a' }] }, 'b')).toBe('INVALID');
    expect(fieldError({ type: 'TEXT', pattern: '[a-z]+' }, 'ABC')).toBe('INVALID');
    expect(fieldError({ type: 'TEXT', maxLength: 3 }, 'abcd')).toBe('INVALID');
    expect(fieldError({ type: 'TEXT' }, 'a\nb')).toBe('INVALID');
    expect(fieldError({ type: 'TEXT', required: true }, '')).toBe('REQUIRED');
    expect(fieldError({ type: 'CHECKBOX', required: true }, false)).toBe('REQUIRED');
  });
});

describe('form validation and body', () => {
  test('buyer pattern, gift recipient and e-mail', () => {
    const form = { ...initialForm('bad name') };
    expect(formErrors(form).playerUsername).toBe('INVALID');
    form.playerUsername = '.Bedrock';
    expect(formErrors(form).playerUsername).toBeUndefined();
    form.gift = true;
    form.recipientUsername = '.bedrock';
    expect(formErrors(form).recipientUsername).toBe('SAME_AS_BUYER');
    form.recipientUsername = 'Other';
    form.email = 'x@';
    expect(formErrors(form).recipientUsername).toBeUndefined();
    expect(formErrors(form).email).toBe('INVALID');
    form.email = '';
    expect(formErrors(form)).toEqual({});
  });
  test('override, label and note limits', () => {
    const form = { ...initialForm('a'), override: true, priceOverride: null };
    expect(formErrors(form).priceOverride).toBe('INVALID');
    form.priceOverride = 0;
    expect(formErrors(form).priceOverride).toBeUndefined();
    form.paymentLabel = 'x'.repeat(256);
    form.note = 'y'.repeat(2001);
    expect(formErrors(form)).toEqual({ paymentLabel: 'TOO_LONG', note: 'TOO_LONG' });
  });
  test('default body: paid, deliveries on, default label', () => {
    const body = buildBody(initialForm('Notch'), [line({ fieldValues: { a: '' } })], {
      defaultLabel: 'Manual payment',
    });
    expect(body).toEqual({
      playerUsername: 'Notch',
      items: [{ productId: 1, quantity: 1 }],
      markPaid: true,
      runDeliveries: true,
      sendMail: false,
      paymentLabel: 'Manual payment',
    });
  });
  test('not paid: no deliveries, no label; optional keys only when set', () => {
    const form = {
      ...initialForm('a'),
      markPaid: false,
      gift: true,
      recipientUsername: 'b',
      email: 'a@b.c',
      override: true,
      priceOverride: 5,
      note: ' hi ',
      force: true,
      sendMail: true,
    };
    const body = buildBody(form, [line({ variantId: 3, targetServerId: 2 })]);
    expect(body.runDeliveries).toBe(false);
    expect('paymentLabel' in body).toBe(false);
    expect(body).toMatchObject({
      recipientUsername: 'b',
      email: 'a@b.c',
      priceOverride: 5,
      note: 'hi',
      force: true,
      sendMail: true,
    });
    expect(body.items[0]).toEqual({ productId: 1, quantity: 1, variantId: 3, targetServerId: 2 });
  });
  test('gift off drops the recipient', () => {
    const form = { ...initialForm('a'), gift: false, recipientUsername: 'b' };
    expect('recipientUsername' in buildBody(form, [line()])).toBe(false);
  });
  test('mail switch: needs an address unless the buyer is known', () => {
    expect(canSendMail(initialForm('a'), null)).toBe(true);
    expect(canSendMail(initialForm('a'), { buyerResolved: false })).toBe(false);
    expect(canSendMail({ ...initialForm('a'), email: 'a@b.c' }, { buyerResolved: false })).toBe(
      true,
    );
  });
});

describe('quote matching and CTA', () => {
  const quote = (lines, extra = {}) => ({ canCheckout: true, lines, ...extra });

  test('matches by index and folds bundle children into the parent', () => {
    const q = quote([
      { kind: 'BUNDLE', errors: [] },
      { kind: 'BUNDLE_CHILD', errors: ['OUT_OF_STOCK'] },
      { kind: 'PRODUCT', errors: [] },
    ]);
    const matched = matchQuoteLines(q, [line(), line({ productId: 2 })]);
    expect(matched[0].errors).toEqual(['OUT_OF_STOCK']);
    expect(matched[1].errors).toEqual([]);
    expect(matchQuoteLines(null, [line()])).toEqual([null]);
    expect(matchQuoteLines(quote([]), [line()])).toEqual([null]);
  });
  test('over-limit line blocks until force is on', () => {
    const lines = [line()];
    const q = quote([{ kind: 'PRODUCT', errors: ['OUT_OF_STOCK'] }], { canCheckout: false });
    const form = initialForm('Notch');
    expect(overLimitIndexes(matchQuoteLines(q, lines))).toEqual([0]);
    expect(submitState({ form, lines, quote: q })).toEqual({
      canSubmit: false,
      reason: 'OVER_LIMIT',
    });
    expect(submitState({ form: { ...form, force: true }, lines, quote: q }).canSubmit).toBe(true);
  });
  test('max quantity, purchase limit and cooldown are lifted by force too', () => {
    for (const code of [
      'MAX_QUANTITY',
      'PURCHASE_LIMIT_REACHED',
      'COOLDOWN_ACTIVE',
      'REQUIREMENT_NOT_MET',
    ]) {
      const q = quote([{ kind: 'PRODUCT', errors: [code] }]);
      const form = initialForm('a');
      expect(submitState({ form, lines: [line()], quote: q }).canSubmit).toBe(false);
      expect(
        submitState({ form: { ...form, force: true }, lines: [line()], quote: q }).canSubmit,
      ).toBe(true);
    }
  });
  test('a non-limit line error is never lifted by force', () => {
    const q = quote([{ kind: 'PRODUCT', errors: ['VARIANT_UNAVAILABLE'] }], { canCheckout: false });
    const form = { ...initialForm('a'), force: true };
    expect(submitState({ form, lines: [line()], quote: q })).toEqual({
      canSubmit: false,
      reason: 'LINE_ERROR',
    });
  });
  test('server lineErrors count like quote errors', () => {
    const form = initialForm('a');
    expect(
      submitState({ form, lines: [line()], quote: null, serverErrors: [['OUT_OF_STOCK']] })
        .canSubmit,
    ).toBe(false);
  });
  test('no quote keeps the CTA enabled; empty cart, bad field and saving disable it', () => {
    const form = initialForm('a');
    expect(submitState({ form, lines: [line()], quote: null }).canSubmit).toBe(true);
    expect(submitState({ form, lines: [], quote: null }).reason).toBe('NO_LINES');
    expect(submitState({ form: initialForm(''), lines: [line()], quote: null }).reason).toBe(
      'INVALID_FORM',
    );
    expect(submitState({ form, lines: [line()], quote: null, saving: true }).reason).toBe('SAVING');
    expect(submitState({ form, lines: [line({ quantity: '' })], quote: null }).reason).toBe(
      'INVALID_LINE',
    );
    expect(submitState({ form, lines: [line({ quantity: 0 })], quote: null }).reason).toBe(
      'INVALID_LINE',
    );
  });
  test('canCheckout false without a line error blocks only without force', () => {
    const q = quote([{ kind: 'PRODUCT', errors: [] }], { canCheckout: false });
    const form = initialForm('a');
    expect(submitState({ form, lines: [line()], quote: q }).canSubmit).toBe(false);
    expect(
      submitState({ form: { ...form, force: true }, lines: [line()], quote: q }).canSubmit,
    ).toBe(true);
  });
});

describe('submit', () => {
  test('one idempotency key per unchanged body', () => {
    const state = newIdempotency();
    const a = submitHeaders(state, { x: 1 });
    expect(submitHeaders(state, { x: 1 })).toEqual(a);
    expect(submitHeaders(state, { x: 2 })['Idempotency-Key']).not.toBe(a['Idempotency-Key']);
  });
  test('INVALID_CART lineErrors map onto rows in request order', () => {
    expect(mapLineErrors({ k1: ['A'], k2: ['B', 'C'] }, 3)).toEqual([['A'], ['B', 'C'], []]);
    expect(mapLineErrors({ k1: ['A'], k2: ['B'] }, 1)).toEqual([['A']]);
    expect(mapLineErrors(null, 2)).toEqual([[], []]);
  });
  test('failure outcomes', () => {
    expect(
      submitFailure('INVALID_CART', { lineErrors: { a: ['FIELD_REQUIRED'] } }, 1).lineErrors,
    ).toEqual([['FIELD_REQUIRED']]);
    for (const c of [
      'OUT_OF_STOCK',
      'PURCHASE_LIMIT_REACHED',
      'COOLDOWN_ACTIVE',
      'PRODUCT_REQUIREMENT_NOT_MET',
    ]) {
      const o = submitFailure(c, {}, 1);
      expect(o.offerForce).toBe(true);
      expect(o.toast).toBe(c);
    }
    expect(submitFailure('INVALID_RECIPIENT', {}, 1).field).toBe('recipientUsername');
    expect(submitFailure('SUBSCRIPTION_MUST_BE_ALONE', {}, 2).lineErrors).toEqual([
      ['SUBSCRIPTION_MUST_BE_ALONE'],
      ['SUBSCRIPTION_MUST_BE_ALONE'],
    ]);
    expect(submitFailure('IDEMPOTENCY_CONFLICT', {}, 1).reset).toBe(true);
    expect(submitFailure('NETWORK_ERROR', {}, 1)).toMatchObject({
      offerForce: false,
      reset: false,
    });
  });
  test('detail path', () => {
    expect(detailPath({ id: 12 })).toBe('/market/orders/detail/12');
    expect(detailPath({})).toBeNull();
  });
});

describe('quote runner', () => {
  function harness(sendImpl) {
    const timers = [];
    const results = [];
    const sent = [];
    const runner = createQuoteRunner({
      send: (body) => {
        sent.push(body);
        return sendImpl(body);
      },
      onResult: (q) => results.push(q),
      timers: {
        set: (fn) => timers.push({ fn, live: true }) - 1,
        clear: (id) => (timers[id].live = false),
      },
    });
    const fire = async () => {
      for (const t of timers)
        if (t.live) {
          t.live = false;
          await t.fn();
        }
    };
    return { runner, results, sent, fire };
  }

  test('debounce: a burst of changes sends one request', async () => {
    const h = harness(async () => ({ ok: true, body: { quote: { total: 1 } } }));
    h.runner.schedule({ n: 1 });
    h.runner.schedule({ n: 2 });
    h.runner.schedule({ n: 3 });
    await h.fire();
    expect(h.sent).toEqual([{ n: 3 }]);
    expect(h.results).toEqual([{ total: 1 }]);
  });
  test('last wins: a slow older answer is dropped', async () => {
    const resolvers = [];
    const h = harness((body) => new Promise((r) => resolvers.push({ body, r })));
    h.runner.schedule({ n: 1 });
    const first = h.fire();
    h.runner.schedule({ n: 2 });
    const second = h.fire();
    resolvers[1].r({ ok: true, body: { quote: { total: 2 } } });
    await second;
    resolvers[0].r({ ok: true, body: { quote: { total: 1 } } });
    await first;
    expect(h.results).toEqual([{ total: 2 }]);
  });
  test('failure and missing quote give null; cancel drops the answer', async () => {
    const h = harness(async () => ({ ok: false }));
    h.runner.schedule({});
    await h.fire();
    expect(h.results).toEqual([null]);
    const g = harness(async () => ({ ok: true, body: { quote: { total: 1 } } }));
    g.runner.schedule({});
    g.runner.cancel();
    await g.fire();
    expect(g.results).toEqual([]);
  });
  test('every change schedules a new request', async () => {
    const h = harness(async () => ({ ok: true, body: { quote: {} } }));
    for (const n of [1, 2, 3]) {
      h.runner.schedule({ n });
      await h.fire();
    }
    expect(h.sent).toHaveLength(3);
  });
});

describe('line error texts', () => {
  test('known codes map to their key, unknown to UNKNOWN', async () => {
    const { lineErrorKey, LINE_ERROR_CODES } = await import('./model.js');
    expect(lineErrorKey('OUT_OF_STOCK')).toBe('enums.line-error.OUT_OF_STOCK');
    expect(lineErrorKey('WHATEVER')).toBe('enums.line-error.UNKNOWN');
    expect(LINE_ERROR_CODES).toContain('MAX_QUANTITY');
  });
});
