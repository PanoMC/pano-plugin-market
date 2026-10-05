import { describe, expect, test } from 'bun:test';
import fs from 'node:fs';
import path from 'node:path';
import { writable } from 'svelte/store';
import {
  MAX_SCRIPTS,
  billingStep,
  buildValues,
  canPay,
  canRetry,
  continueFailure,
  controlId,
  createScriptLoader,
  creditsAmount,
  creditsForOrder,
  defaultMethodId,
  effectiveStart,
  embeddedFallback,
  embeddedFields,
  firstComponentItem,
  iframeAllow,
  iframeHeight,
  initialValues,
  instructionRows,
  isComponentId,
  isHttpsUrl,
  isVisible,
  nextStep,
  notifyBody,
  notifyFailure,
  panelModel,
  payBody,
  payFailure,
  pickerQuote,
  readonlyValue,
  resizerOptions,
  resolveComponent,
  scriptsPlan,
  showNotifyForm,
  startMode,
  startSignature,
  startUsable,
  validateForm,
  validateInput,
  viewItems,
  waitSeconds,
} from '../paymentPanel.js';

const ctx = { origin: 'https://shop.example.com', base: '' };
const ATTEMPT = 'https://shop.example.com/api/market/payments/attempts/TOKEN123/page';

const view = (over = {}) => ({
  limited: false,
  panels: { payment: true, instructions: false },
  ...over,
});

const iframeStart = (over = {}) => ({
  kind: 'IFRAME',
  expiresAt: 0,
  iframe: { url: 'https://pay.gateway.test/frame', scripts: [], heightPx: 700, allow: 'payment' },
  ...over,
});

const embedded = (over = {}) => ({
  kind: 'EMBEDDED',
  embedded: { component: null, props: {}, fields: [], scripts: [], ...over },
});

const order = (over = {}) => ({
  publicId: 'AbCdEfGhIjKlMnOpQrSt',
  status: 'PENDING',
  currency: 'USD',
  canRetryPayment: true,
  payment: { methodId: 'stripe', label: 'Card', status: 'PENDING', start: iframeStart() },
  paymentMethods: [
    { id: 'stripe', label: 'Card', available: true },
    { id: 'bank-transfer', label: 'Bank', available: true },
    { id: 'old', label: 'Old', available: false },
  ],
  totals: { gatewayAmount: 1000 },
  ...over,
});

describe('isHttpsUrl', () => {
  test.each(['https://a.test/x', 'HTTPS://A.TEST/x?y=1#z'])('accepts %s', (url) => {
    expect(isHttpsUrl(url)).toBe(true);
  });

  test.each([
    'http://a.test/x',
    'javascript:alert(1)',
    '//a.test/x',
    '/x',
    'https://',
    'https://a.test/ x',
    'https://a.test/\u0000',
    'https://user:pw@a.test/x',
    'data:text/javascript,1',
    '',
    null,
    undefined,
    7,
  ])('rejects %p', (url) => {
    expect(isHttpsUrl(url)).toBe(false);
  });
});

describe('scriptsPlan', () => {
  test('nothing asked is valid and empty', () => {
    expect(scriptsPlan(undefined)).toEqual({ urls: [], valid: true });
    expect(scriptsPlan(null)).toEqual({ urls: [], valid: true });
    expect(scriptsPlan([])).toEqual({ urls: [], valid: true });
  });

  test('https urls keep their order and collapse duplicates', () => {
    const plan = scriptsPlan(['https://a.test/1.js', 'https://b.test/2.js', 'https://a.test/1.js']);

    expect(plan).toEqual({ urls: ['https://a.test/1.js', 'https://b.test/2.js'], valid: true });
  });

  test.each([
    [['http://a.test/1.js']],
    [['https://a.test/1.js', 'javascript:alert(1)']],
    [['//a.test/1.js']],
    [['data:text/javascript,alert(1)']],
    [[42]],
    ['https://a.test/1.js'],
    [{ 0: 'https://a.test/1.js' }],
  ])('one entry that is not https makes the whole list invalid: %p', (list) => {
    const plan = scriptsPlan(list);

    expect(plan.valid).toBe(false);
    expect(plan.urls).toEqual([]);
  });

  test('more than the cap is invalid', () => {
    const list = Array.from({ length: MAX_SCRIPTS + 1 }, (_, i) => `https://a.test/${i}.js`);

    expect(scriptsPlan(list).valid).toBe(false);
    expect(scriptsPlan(list.slice(0, MAX_SCRIPTS)).valid).toBe(true);
  });
});

function fakeDocument() {
  const head = {
    children: [],
    appendChild(el) {
      this.children.push(el);
      // the browser settles the load later; tests settle it through el.onload / el.onerror
      return el;
    },
  };

  return {
    head,
    createElement(tag) {
      const el = {
        tag,
        dataset: {},
        removed: false,
        remove() {
          this.removed = true;
          head.children = head.children.filter((child) => child !== el);
        },
      };

      return el;
    },
  };
}

describe('createScriptLoader', () => {
  test('appends one script per url, once, and resolves on load', async () => {
    const doc = fakeDocument();
    const loader = createScriptLoader(doc);

    const first = loader.load('https://a.test/1.js');
    const again = loader.load('https://a.test/1.js');

    expect(doc.head.children).toHaveLength(1);
    expect(doc.head.children[0].src).toBe('https://a.test/1.js');
    expect(doc.head.children[0].tag).toBe('script');
    expect(first).toBe(again);

    doc.head.children[0].onload();

    expect(await first).toBe('https://a.test/1.js');
  });

  test('loadAll waits for each script before appending the next (order kept)', async () => {
    const doc = fakeDocument();
    const loader = createScriptLoader(doc);

    const all = loader.loadAll(['https://a.test/1.js', 'https://b.test/2.js']);

    expect(doc.head.children.map((el) => el.src)).toEqual(['https://a.test/1.js']);

    doc.head.children[0].onload();
    await Promise.resolve();
    await Promise.resolve();

    expect(doc.head.children.map((el) => el.src)).toEqual([
      'https://a.test/1.js',
      'https://b.test/2.js',
    ]);

    doc.head.children[1].onload();
    await all;
  });

  test('a failed load rejects, removes the element and can be tried again', async () => {
    const doc = fakeDocument();
    const loader = createScriptLoader(doc);

    const failing = loader.load('https://a.test/1.js');
    const el = doc.head.children[0];

    el.onerror();

    await expect(failing).rejects.toThrow('SCRIPT_LOAD');
    expect(el.removed).toBe(true);
    expect(doc.head.children).toHaveLength(0);

    const retry = loader.load('https://a.test/1.js');

    expect(doc.head.children).toHaveLength(1);
    doc.head.children[0].onload();
    await retry;
  });

  test('a url that is not https never reaches the document', async () => {
    const doc = fakeDocument();
    const loader = createScriptLoader(doc);

    for (const url of ['http://a.test/1.js', 'javascript:alert(1)', '/local.js', '//a.test/x.js'])
      await expect(loader.load(url)).rejects.toThrow('SCRIPT_URL');

    await expect(
      loader.loadAll(['https://a.test/ok.js', 'http://a.test/no.js'].slice(1)),
    ).rejects.toThrow();
    expect(doc.head.children).toHaveLength(0);
  });
});

describe('iframe attributes', () => {
  test('height is an integer within bounds, else 640', () => {
    expect(iframeHeight(700)).toBe(700);
    expect(iframeHeight('900')).toBe(900);
    expect(iframeHeight(undefined)).toBe(640);
    expect(iframeHeight(null)).toBe(640);
    expect(iframeHeight(50)).toBe(640);
    expect(iframeHeight(99999)).toBe(640);
    expect(iframeHeight(700.5)).toBe(640);
    expect(iframeHeight('x')).toBe(640);
  });

  test('allow keeps plain permission tokens only', () => {
    expect(iframeAllow('payment')).toBe('payment');
    expect(iframeAllow(" payment *; camera 'none' ")).toBe("payment *; camera 'none'");
    expect(iframeAllow('payment" onload="x')).toBeUndefined();
    expect(iframeAllow('<script>')).toBeUndefined();
    expect(iframeAllow('')).toBeUndefined();
    expect(iframeAllow(null)).toBeUndefined();
  });

  test('the resizer is asked for the origin of the iframe url only', () => {
    expect(resizerOptions({ url: 'https://pay.test/a/b?c=1', resizer: 'IFRAME_RESIZER' })).toEqual({
      checkOrigin: ['https://pay.test'],
    });
    expect(resizerOptions({ url: 'https://pay.test/a', resizer: 'NONE' })).toBeNull();
    expect(resizerOptions({ url: 'http://pay.test/a', resizer: 'IFRAME_RESIZER' })).toBeNull();
    expect(resizerOptions(null)).toBeNull();
  });
});

describe('startMode (14 §11.4 rows)', () => {
  test('REDIRECT needs an http(s) url', () => {
    expect(startMode({ kind: 'REDIRECT', url: 'https://gw.test/pay' }, ctx)).toBe('LINK');
    expect(startMode({ kind: 'REDIRECT', url: 'javascript:alert(1)' }, ctx)).toBe('NONE');
    expect(startMode({ kind: 'REDIRECT', url: '//evil.test/x' }, ctx)).toBe('NONE');
  });

  test('FORM_POST and HTML need the same-origin attempt page', () => {
    expect(startMode({ kind: 'FORM_POST', url: ATTEMPT }, ctx)).toBe('LINK');
    expect(
      startMode({ kind: 'HTML', url: '/api/market/payments/attempts/TOKEN123/page' }, ctx),
    ).toBe('LINK');
    expect(
      startMode({ kind: 'HTML', url: 'https://gw.test/api/market/payments/attempts/T/page' }, ctx),
    ).toBe('NONE');
    expect(startMode({ kind: 'FORM_POST', url: ATTEMPT }, {})).toBe('NONE');
  });

  test('IFRAME needs an https url, else a UI error', () => {
    expect(startMode(iframeStart(), ctx)).toBe('IFRAME');
    expect(startMode(iframeStart({ iframe: { url: 'http://pay.test/f' } }), ctx)).toBe('UI_ERROR');
    expect(startMode(iframeStart({ iframe: { url: 'javascript:alert(1)' } }), ctx)).toBe(
      'UI_ERROR',
    );
    expect(startMode({ kind: 'IFRAME' }, ctx)).toBe('UI_ERROR');
  });

  test('EMBEDDED: a component id wins, then fields, then the missing notice', () => {
    expect(startMode(embedded({ component: 'market:checkout:payment:iyzico' }), ctx)).toBe(
      'COMPONENT',
    );
    expect(startMode(embedded({ fields: [{ key: 'phone', type: 'TEXT' }] }), ctx)).toBe('GENERIC');
    expect(startMode(embedded(), ctx)).toBe('MISSING');
  });

  test('a component outside the payment namespace is ignored (falls to fields or missing)', () => {
    const bad = 'navbar-right';

    expect(startMode(embedded({ component: bad }), ctx)).toBe('MISSING');
    expect(startMode(embedded({ component: bad, fields: [{ key: 'a', type: 'TEXT' }] }), ctx)).toBe(
      'GENERIC',
    );
  });

  test('INSTRUCTIONS, COMPLETED and unknown kinds', () => {
    expect(startMode({ kind: 'INSTRUCTIONS', instructions: { body: 'x', fields: [] } }, ctx)).toBe(
      'INSTRUCTIONS',
    );
    expect(startMode({ kind: 'INSTRUCTIONS' }, ctx)).toBe('NONE');
    expect(startMode({ kind: 'COMPLETED' }, ctx)).toBe('NONE');
    expect(startMode({ kind: 'SOMETHING_NEW' }, ctx)).toBe('NONE');
    expect(startMode(null, ctx)).toBe('NONE');
  });
});

describe('component lookup and the generic fallback', () => {
  test('component ids live under market:checkout:payment:', () => {
    expect(isComponentId('market:checkout:payment:iyzico')).toBe(true);
    expect(isComponentId('market:checkout:payment:')).toBe(false);
    expect(isComponentId('market:checkout:payment:a b')).toBe(false);
    expect(isComponentId('market:checkout:payment:../x')).toBe(false);
    expect(isComponentId('navbar-right')).toBe(false);
    expect(isComponentId(undefined)).toBe(false);
  });

  test('viewItems reads arrays and stores, never throws', () => {
    expect(viewItems([{ id: 1 }])).toEqual([{ id: 1 }]);
    expect(viewItems(writable([{ id: 2 }]))).toEqual([{ id: 2 }]);
    expect(viewItems(writable('nope'))).toEqual([]);
    expect(viewItems(undefined)).toEqual([]);
    expect(
      viewItems({
        subscribe: () => {
          throw new Error('broken');
        },
      }),
    ).toEqual([]);
  });

  test('the first item with a component is used', () => {
    const items = [{ id: 'a' }, { id: 'b', component: () => 1 }, { id: 'c', component: () => 2 }];

    expect(firstComponentItem(items).id).toBe('b');
    expect(firstComponentItem([])).toBeNull();
    expect(firstComponentItem(writable([{ id: 'a' }]))).toBeNull();
  });

  test('a thunk resolves to the default export of its module; a module and a plain component pass through', async () => {
    function Plain($$anchor, $$props) {}
    const thunk = async () => ({ default: Plain, mount: () => {} });
    const viaViewComponent = Object.assign(async () => ({ default: Plain }), {
      _importer: () => {},
    });

    expect(await resolveComponent({ component: thunk })).toBe(Plain);
    expect(await resolveComponent({ component: viaViewComponent })).toBe(Plain);
    expect(await resolveComponent({ component: { default: Plain } })).toBe(Plain);
    expect(await resolveComponent({ component: Plain })).toBe(Plain);
  });

  test('a missing, broken or empty component resolves to null', async () => {
    expect(await resolveComponent(null)).toBeNull();
    expect(await resolveComponent({})).toBeNull();
    expect(await resolveComponent({ component: async () => ({}) })).toBeNull();
    expect(
      await resolveComponent({
        component: async () => {
          throw new Error('chunk failed');
        },
      }),
    ).toBeNull();
    expect(await resolveComponent({ component: 'text' })).toBeNull();
  });

  test('without a plugin component the start falls back to the generic form, or to the notice without fields', () => {
    const withFields = embedded({
      component: 'market:checkout:payment:x',
      fields: [{ key: 'phone', type: 'TEXT', label: 'Phone' }],
    });
    const without = embedded({ component: 'market:checkout:payment:x' });

    expect(embeddedFallback(withFields)).toBe('GENERIC');
    expect(embeddedFallback(without)).toBe('MISSING');
    expect(embeddedFields(withFields)).toHaveLength(1);
  });

  test('embeddedFields drops entries without a string key', () => {
    const start = embedded({
      fields: [null, 'x', { type: 'TEXT' }, { key: '', type: 'TEXT' }, { key: 'ok', type: 'TEXT' }],
    });

    expect(embeddedFields(start).map((f) => f.key)).toEqual(['ok']);
  });
});

describe('effectiveStart / startUsable', () => {
  test('an override only holds while the order carries the start it was made for', () => {
    const o = order();
    const next = { kind: 'INSTRUCTIONS' };
    const override = { base: o.payment.start, value: next };

    expect(effectiveStart(o, override)).toBe(next);
    expect(effectiveStart(order(), override)).not.toBe(next);
    expect(effectiveStart(o, null)).toBe(o.payment.start);
  });

  test('usable: PENDING attempt with a start that has not expired', () => {
    const start = iframeStart({ expiresAt: 5000 });

    expect(startUsable(order(), start, 4999)).toBe(true);
    expect(startUsable(order(), start, 5000)).toBe(false);
    expect(startUsable(order(), iframeStart({ expiresAt: null }), 9e12)).toBe(true);
    expect(startUsable(order(), iframeStart({ expiresAt: 0 }), 9e12)).toBe(true);
    expect(startUsable(order(), null, 0)).toBe(false);

    for (const status of ['CREATED', 'FAILED', 'CANCELLED', 'EXPIRED', 'PROCESSING', 'SUCCEEDED'])
      expect(startUsable(order({ payment: { status, start } }), start, 1)).toBe(false);
  });
});

describe('startSignature', () => {
  test('equal content gives the same signature, new objects included', () => {
    expect(startSignature(iframeStart())).toBe(startSignature(iframeStart()));
    expect(startSignature(iframeStart())).not.toBe(
      startSignature(iframeStart({ iframe: { url: 'https://other.test/f' } })),
    );
    expect(startSignature(null)).toBe('');
    expect(startSignature('x')).toBe('');
    const cyclic = {};
    cyclic.self = cyclic;
    expect(startSignature(cyclic)).toBe('');
  });
});

describe('panelModel', () => {
  test('AWAITING_PAYMENT with a usable start shows it and keeps "pay another way" collapsed', () => {
    const model = panelModel({ order: order(), view: view(), now: 1000, context: ctx });

    expect(model).toMatchObject({
      show: true,
      readonly: false,
      mode: 'IFRAME',
      canRetry: true,
      methodsOpen: false,
    });
    expect(model.start).toBe(order().payment.start === model.start ? model.start : model.start);
    expect(model.start.kind).toBe('IFRAME');
  });

  test.each(['FAILED', 'CANCELLED', 'EXPIRED', 'CREATED'])(
    'ps %s: no usable start, "pay another way" is expanded',
    (status) => {
      const o = order({ payment: { methodId: 'stripe', status, start: null } });
      const model = panelModel({ order: o, view: view(), now: 1000, context: ctx });

      expect(model.mode).toBe('NONE');
      expect(model.start).toBeNull();
      expect(model.show).toBe(true);
      expect(model.methodsOpen).toBe(true);
    },
  );

  test('an expired start counts as no start', () => {
    const o = order({
      payment: { methodId: 'stripe', status: 'PENDING', start: iframeStart({ expiresAt: 500 }) },
    });
    const model = panelModel({ order: o, view: view(), now: 1000, context: ctx });

    expect(model.mode).toBe('NONE');
    expect(model.methodsOpen).toBe(true);
  });

  test('a start whose UI is missing or unusable opens the method picker', () => {
    const o = (start) => order({ payment: { methodId: 'x', status: 'PENDING', start } });

    expect(panelModel({ order: o(embedded()), view: view(), now: 1, context: ctx })).toMatchObject({
      mode: 'MISSING',
      methodsOpen: true,
    });
    expect(
      panelModel({
        order: o(iframeStart({ iframe: { url: 'http://x.test' } })),
        view: view(),
        now: 1,
        context: ctx,
      }),
    ).toMatchObject({ mode: 'UI_ERROR', methodsOpen: true });
  });

  test('without canRetryPayment there is no "pay another way" and nothing to show without a start', () => {
    const none = order({ canRetryPayment: false, payment: { status: 'FAILED', start: null } });

    expect(panelModel({ order: none, view: view(), now: 1, context: ctx })).toMatchObject({
      show: false,
      canRetry: false,
    });

    const withStart = order({ canRetryPayment: false });

    expect(panelModel({ order: withStart, view: view(), now: 1, context: ctx })).toMatchObject({
      show: true,
      canRetry: false,
      methodsOpen: false,
    });
  });

  test('canRetry needs at least one available method', () => {
    expect(canRetry(order())).toBe(true);
    expect(canRetry(order({ paymentMethods: [{ id: 'a', available: false }] }))).toBe(false);
    expect(canRetry(order({ paymentMethods: undefined }))).toBe(false);
    expect(canRetry(order({ canRetryPayment: false }))).toBe(false);
  });

  test('PROCESSING shows the instructions read-only, never the rest', () => {
    const start = {
      kind: 'INSTRUCTIONS',
      instructions: { body: 'IBAN', fields: [], buyerConfirms: true },
    };
    const o = order({ payment: { methodId: 'bank-transfer', status: 'PROCESSING', start } });
    const processing = view({ panels: { payment: false, instructions: true } });
    const model = panelModel({ order: o, view: processing, now: 1, context: ctx });

    expect(model).toMatchObject({
      show: true,
      readonly: true,
      mode: 'INSTRUCTIONS',
      canRetry: false,
    });

    const iframeOnly = order({ payment: { status: 'PROCESSING', start: iframeStart() } });

    expect(panelModel({ order: iframeOnly, view: processing, now: 1, context: ctx }).show).toBe(
      false,
    );
  });

  test('a limited view, other states and a missing order show nothing', () => {
    expect(
      panelModel({ order: order(), view: view({ limited: true }), now: 1, context: ctx }).show,
    ).toBe(false);
    expect(
      panelModel({
        order: order(),
        view: view({ panels: { payment: false, instructions: false } }),
        now: 1,
        context: ctx,
      }).show,
    ).toBe(false);
    expect(panelModel({}).show).toBe(false);
    expect(panelModel({ order: null, view: view() }).show).toBe(false);
  });

  test('a start returned by continue replaces the order start until the order is re-fetched', () => {
    const o = order();
    const next = { kind: 'INSTRUCTIONS', instructions: { body: 'x' } };
    const override = { base: o.payment.start, value: next };
    const model = panelModel({ order: o, view: view(), now: 1, override, context: ctx });

    expect(model.mode).toBe('INSTRUCTIONS');
    expect(model.start).toBe(next);
  });
});

describe('generic form', () => {
  const fields = [
    {
      key: 'operator',
      type: 'SELECT',
      label: 'Operator',
      required: true,
      options: [
        { value: 'a', label: 'A' },
        { value: 'b', label: 'B' },
      ],
    },
    {
      key: 'phone',
      type: 'TEXT',
      label: 'Phone',
      required: true,
      pattern: '\\+\\d{7,14}',
      visibleWhen: { field: 'operator', anyOf: ['a'] },
    },
    { key: 'pin', type: 'PASSWORD', label: 'PIN', required: false },
    { key: 'amount', type: 'NUMBER', label: 'Amount', min: 10, max: 500 },
    { key: 'site', type: 'URL', label: 'Site' },
    { key: 'about', type: 'TEXTAREA', label: 'About' },
    { key: 'terms', type: 'SWITCH', label: 'Terms', default: false },
    { key: 'info', type: 'NOTICE', label: 'Heads up', noticeLevel: 'WARNING' },
    { key: 'acct', type: 'READONLY', label: 'Account', readonly: { value: 'TR12 3456' } },
  ];

  test('initialValues: inputs only, defaults, switch false', () => {
    expect(initialValues(fields)).toEqual({
      operator: '',
      phone: '',
      pin: '',
      amount: '',
      site: '',
      about: '',
      terms: false,
    });
    expect(
      initialValues([
        { key: 'a', type: 'TEXT', default: 5 },
        { key: 'b', type: 'SWITCH', default: 'true' },
      ]),
    ).toEqual({
      a: '5',
      b: true,
    });
  });

  test('visibleWhen follows the referenced field and its own visibility', () => {
    const phone = fields[1];

    expect(isVisible(phone, { operator: 'a' }, fields)).toBe(true);
    expect(isVisible(phone, { operator: 'b' }, fields)).toBe(false);
    expect(isVisible(phone, {}, fields)).toBe(false);

    const chain = [
      { key: 'a', type: 'SWITCH' },
      { key: 'b', type: 'TEXT', visibleWhen: { field: 'a', anyOf: ['true'] } },
      { key: 'c', type: 'TEXT', visibleWhen: { field: 'b', anyOf: ['x'] } },
    ];

    expect(isVisible(chain[2], { a: false, b: 'x' }, chain)).toBe(false);
    expect(isVisible(chain[2], { a: true, b: 'x' }, chain)).toBe(true);
  });

  test('a visibleWhen cycle is treated as visible and terminates', () => {
    const cyc = [
      { key: 'a', type: 'TEXT', visibleWhen: { field: 'b', anyOf: ['1'] } },
      { key: 'b', type: 'TEXT', visibleWhen: { field: 'a', anyOf: ['1'] } },
    ];

    expect(isVisible(cyc[0], { a: '1', b: '1' }, cyc)).toBe(true);
    expect(isVisible(cyc[0], { a: '', b: '' }, cyc)).toBe(false);
  });

  test('validateInput rules per type', () => {
    const f = (key) => fields.find((x) => x.key === key);

    expect(validateInput(f('operator'), '')).toBe('FIELD_REQUIRED');
    expect(validateInput(f('operator'), '  ')).toBe('FIELD_REQUIRED');
    expect(validateInput(f('operator'), 'zzz')).toBe('FIELD_INVALID');
    expect(validateInput(f('operator'), 'a')).toBeNull();
    expect(validateInput(f('phone'), '12345')).toBe('FIELD_INVALID');
    expect(validateInput(f('phone'), '+905551112233')).toBeNull();
    expect(validateInput(f('pin'), '')).toBeNull();
    expect(validateInput(f('amount'), '9')).toBe('FIELD_INVALID');
    expect(validateInput(f('amount'), '501')).toBe('FIELD_INVALID');
    expect(validateInput(f('amount'), '12.5')).toBe('FIELD_INVALID');
    expect(validateInput(f('amount'), 'abc')).toBe('FIELD_INVALID');
    expect(validateInput(f('amount'), '100')).toBeNull();
    expect(validateInput(f('site'), 'javascript:alert(1)')).toBe('FIELD_INVALID');
    expect(validateInput(f('site'), 'ftp://x.test')).toBe('FIELD_INVALID');
    expect(validateInput(f('site'), 'https://x.test/a')).toBeNull();
    expect(validateInput(f('about'), 'x'.repeat(4001))).toBe('FIELD_INVALID');
    expect(validateInput(f('about'), 'x'.repeat(4000))).toBeNull();
    expect(validateInput(f('pin'), 'x'.repeat(513))).toBe('FIELD_INVALID');
    expect(validateInput(f('terms'), false)).toBeNull();
    expect(validateInput(f('info'), '')).toBeNull();
    expect(validateInput({ key: 'p', type: 'TEXT', pattern: '([' }, 'abc')).toBeNull();
  });

  test('validateForm skips hidden fields', () => {
    expect(validateForm(fields, { operator: 'b', phone: '' })).toEqual({});
    expect(validateForm(fields, { operator: 'a', phone: '' })).toEqual({ phone: 'FIELD_REQUIRED' });
    expect(validateForm(fields, { operator: '' })).toEqual({ operator: 'FIELD_REQUIRED' });
  });

  test('buildValues: trimmed, numbers as numbers, switch as boolean, hidden and empty left out', () => {
    expect(
      buildValues(fields, {
        operator: 'a',
        phone: ' +905551112233 ',
        pin: '',
        amount: ' 120 ',
        site: 'https://x.test',
        about: '',
        terms: true,
      }),
    ).toEqual({
      operator: 'a',
      phone: '+905551112233',
      amount: 120,
      site: 'https://x.test',
      terms: true,
    });

    expect(buildValues(fields, { operator: 'b', phone: '+905551112233', terms: false })).toEqual({
      operator: 'b',
      terms: false,
    });
  });

  test('notices and read-only values never become input values', () => {
    const values = buildValues(fields, { operator: 'a', phone: '+905551112233', terms: false });

    expect('info' in values).toBe(false);
    expect('acct' in values).toBe(false);
    expect(readonlyValue(fields.at(-1))).toBe('TR12 3456');
    expect(readonlyValue({ type: 'READONLY' })).toBe('');
  });

  test('controlId is a safe DOM id', () => {
    expect(controlId('phone')).toBe('market-order-pay-phone');
    expect(controlId('a b"c')).toBe('market-order-pay-a_b_c');
  });
});

describe('continue / pay answers', () => {
  test('waitSeconds clamps retryAfter', () => {
    expect(waitSeconds(7)).toBe(7);
    expect(waitSeconds(7.2)).toBe(8);
    expect(waitSeconds(0)).toBe(60);
    expect(waitSeconds(undefined)).toBe(60);
    expect(waitSeconds(-5)).toBe(60);
    expect(waitSeconds(999999)).toBe(3600);
  });

  test('continueFailure maps the codes of 14 §11.4 and never shows a gateway message', () => {
    expect(continueFailure({ code: 'BAD_REQUEST', message: 'secret provider text' })).toEqual({
      alertKey: 'theme.order.payment-form-invalid',
      refetch: false,
      openMethods: false,
    });
    expect(
      continueFailure({ code: 'PAYMENT_PROVIDER_ERROR', message: 'card 4242 declined' }),
    ).toEqual({
      alertKey: 'theme.errors.PAYMENT_PROVIDER_ERROR',
      refetch: true,
      openMethods: true,
    });
    expect(continueFailure({ code: 'ORDER_NOT_PAYABLE' })).toMatchObject({
      refetch: true,
      openMethods: false,
    });
    expect(continueFailure({ code: 'TOO_MANY_REQUESTS', retryAfter: 12 })).toMatchObject({
      seconds: 12,
    });
    expect(continueFailure({ code: 'WHATEVER_NEW' }).alertKey).toBe('theme.errors.GENERIC');
    expect(continueFailure(undefined).alertKey).toBe('theme.errors.NETWORK');
  });

  test('nextStep: leaving kinds navigate to a checked url, in-page kinds show, the rest re-fetches', () => {
    const o = { publicId: 'AbCdEfGhIjKlMnOpQrSt' };

    expect(nextStep({ kind: 'REDIRECT', url: 'https://gw.test/p' }, o, ctx)).toEqual({
      type: 'ASSIGN',
      url: 'https://gw.test/p',
    });
    expect(nextStep({ kind: 'FORM_POST', url: ATTEMPT }, o, ctx)).toEqual({
      type: 'ASSIGN',
      url: ATTEMPT,
    });
    expect(nextStep({ kind: 'REDIRECT', url: 'javascript:alert(1)' }, o, ctx)).toEqual({
      type: 'REFETCH',
    });
    expect(nextStep({ kind: 'HTML', url: 'https://evil.test/x' }, o, ctx)).toEqual({
      type: 'REFETCH',
    });
    expect(nextStep({ kind: 'COMPLETED' }, o, ctx)).toEqual({ type: 'REFETCH' });
    expect(nextStep(undefined, o, ctx)).toEqual({ type: 'REFETCH' });
    expect(nextStep({ kind: 'SOMETHING' }, o, ctx)).toEqual({ type: 'REFETCH' });

    for (const kind of ['IFRAME', 'EMBEDDED', 'INSTRUCTIONS']) {
      const payment = { kind };

      expect(nextStep(payment, o, ctx)).toEqual({ type: 'SHOW', start: payment });
    }
  });
});

describe('pay another way', () => {
  test('defaultMethodId keeps the current method only when it is still available', () => {
    expect(defaultMethodId(order())).toBe('stripe');
    expect(defaultMethodId(order({ payment: { methodId: 'old' } }))).toBeNull();
    expect(defaultMethodId(order({ payment: { methodId: 'gone' } }))).toBeNull();
    expect(defaultMethodId({})).toBeNull();
  });

  test('pickerQuote always has something left to pay (never the "nothing to pay" mode)', () => {
    expect(pickerQuote(order()).gatewayAmount).toBe(1000);
    expect(pickerQuote(order({ totals: { gatewayAmount: 0 } })).gatewayAmount).toBe(1);
    expect(pickerQuote({}).paymentMethods).toEqual([]);
  });

  test('creditsAmount: numbers and MAX only', () => {
    const credits = { maxApplicable: 12.5 };

    expect(creditsAmount(5, credits)).toBe(5);
    expect(creditsAmount(0, credits)).toBe(0);
    expect(creditsAmount('MAX', credits)).toBe(12.5);
    expect(creditsAmount('MAX', { maxApplicable: 0 })).toBeNull();
    expect(creditsAmount(null, credits)).toBeNull();
    expect(creditsAmount('5', credits)).toBeNull();
    expect(creditsAmount(-1, credits)).toBeNull();
    expect(creditsAmount(NaN, credits)).toBeNull();
  });

  test('creditsForOrder feeds the shared control without a "pay it all" radio', () => {
    const credits = { enabled: true, name: 'Coins', balance: 50, maxApplicable: 20 };

    expect(creditsForOrder(order({ credits }))).toMatchObject({
      ...credits,
      payableInCredits: false,
    });
    expect(creditsForOrder(order({ credits: { ...credits, enabled: false } }))).toBeNull();
    expect(creditsForOrder(order({ credits: null }))).toBeNull();
    expect(creditsForOrder(order())).toBeNull();
  });

  test('payBody carries the method, a numeric useCredits and a non-empty billingInfo', () => {
    expect(payBody({ methodId: 'stripe' })).toEqual({ paymentMethodId: 'stripe' });
    expect(payBody({ methodId: 'stripe', useCredits: 3, credits: { maxApplicable: 9 } })).toEqual({
      paymentMethodId: 'stripe',
      useCredits: 3,
    });
    expect(
      payBody({ methodId: 's', useCredits: 'MAX', credits: { maxApplicable: 9 } }).useCredits,
    ).toBe(9);
    expect(payBody({ methodId: 's', billingInfo: { firstName: 'A', type: 'INDIVIDUAL' } })).toEqual(
      {
        paymentMethodId: 's',
        billingInfo: { firstName: 'A', type: 'INDIVIDUAL' },
      },
    );
    expect(payBody({ methodId: 's', billingInfo: {} })).toEqual({ paymentMethodId: 's' });
  });

  test('canPay: an available chosen method, not busy, not waiting', () => {
    const o = order();

    expect(canPay({ order: o, methodId: 'stripe' })).toBe(true);
    expect(canPay({ order: o, methodId: null })).toBe(false);
    expect(canPay({ order: o, methodId: 'old' })).toBe(false);
    expect(canPay({ order: o, methodId: 'unknown' })).toBe(false);
    expect(canPay({ order: o, methodId: 'stripe', busy: true })).toBe(false);
    expect(canPay({ order: o, methodId: 'stripe', waiting: true })).toBe(false);
    expect(canPay({})).toBe(false);
  });

  test('payFailure follows 14 §11.4', () => {
    expect(payFailure({ code: 'ORDER_NOT_PAYABLE' })).toMatchObject({
      refetch: true,
      alertKey: 'theme.errors.ORDER_NOT_PAYABLE',
    });
    expect(payFailure({ code: 'PAYMENT_METHOD_UNAVAILABLE', reason: 'TEST_MODE' })).toMatchObject({
      refetch: true,
      clearMethod: true,
      alertKey: 'theme.errors.TEST_MODE',
    });
    expect(payFailure({ code: 'PAYMENT_METHOD_UNAVAILABLE', reason: 'weird' }).alertKey).toBe(
      'theme.errors.PAYMENT_METHOD_UNAVAILABLE',
    );
    expect(
      payFailure({ code: 'BUYER_INFO_REQUIRED', fields: ['FIRST_NAME', 'PHONE', 7] }),
    ).toMatchObject({
      billingFields: ['FIRST_NAME', 'PHONE'],
      alertKey: 'theme.errors.BUYER_INFO_REQUIRED',
      refetch: false,
    });
    expect(
      payFailure({ code: 'PAYMENT_PROVIDER_ERROR', message: 'gateway said no' }),
    ).toMatchObject({
      alertKey: 'theme.errors.PAYMENT_PROVIDER_ERROR',
    });
    expect(payFailure({ code: 'TOO_MANY_REQUESTS', retryAfter: 30 })).toMatchObject({
      seconds: 30,
    });
    expect(payFailure({ code: 'INSUFFICIENT_CREDITS' })).toMatchObject({ clearCredits: true });
    expect(payFailure({ code: 'NOPE' }).alertKey).toBe('theme.errors.GENERIC');
    expect(payFailure(null).alertKey).toBe('theme.errors.NETWORK');
  });

  test('billingStep limits the billing part to the fields the server named', () => {
    const step = billingStep(['FIRST_NAME', 'PHONE'], { type: 'INDIVIDUAL' });

    expect(step.open).toBe(true);
    expect(step.req.fields.sort()).toEqual(['firstName', 'phone']);
    expect(step.errors).toMatchObject({ firstName: 'FIELD_REQUIRED', phone: 'FIELD_REQUIRED' });
    expect(step.body).toBeNull();
    expect(step.firstId).toBe('market-checkout-billing-firstName');

    const done = billingStep(['FIRST_NAME', 'PHONE'], {
      type: 'INDIVIDUAL',
      firstName: 'Ada',
      phone: '+905551112233',
    });

    expect(done.errors).toEqual({});
    expect(done.body).toEqual({ firstName: 'Ada', phone: '+905551112233', type: 'INDIVIDUAL' });
  });

  test('billingStep stays closed when the server named nothing this theme can ask for', () => {
    expect(billingStep([], {}).open).toBe(false);
    expect(billingStep(['EMAIL'], {}).open).toBe(false);
    expect(billingStep(null, {}).body).toBeNull();
  });
});

describe('bank transfer notice', () => {
  const instructions = { body: 'IBAN', buyerConfirms: true, fields: [] };
  const bank = order({ payment: { methodId: 'bank-transfer', status: 'PENDING' } });

  test('the form shows for buyerConfirms on bank-transfer only, never read-only', () => {
    expect(showNotifyForm({ order: bank, instructions })).toBe(true);
    expect(showNotifyForm({ order: bank, instructions, readonly: true })).toBe(false);
    expect(
      showNotifyForm({ order: bank, instructions: { ...instructions, buyerConfirms: false } }),
    ).toBe(false);
    expect(showNotifyForm({ order: order(), instructions })).toBe(false);
  });

  test('notifyBody: optional trimmed fields, control characters removed, 255 cap', () => {
    expect(notifyBody({})).toEqual({ ok: true, body: {} });
    expect(notifyBody({ senderName: '  Ada Lovelace ', note: ' ref 42 ' })).toEqual({
      ok: true,
      body: { senderName: 'Ada Lovelace', note: 'ref 42' },
    });
    expect(notifyBody({ senderName: 'Ada\u0000\nL', note: '' })).toEqual({
      ok: true,
      body: { senderName: 'Ada  L' },
    });
    expect(notifyBody({ senderName: 'x'.repeat(255) }).ok).toBe(true);
    expect(notifyBody({ senderName: 'x'.repeat(256), note: 'y'.repeat(256) })).toEqual({
      ok: false,
      errors: { senderName: 'FIELD_INVALID', note: 'FIELD_INVALID' },
    });
  });

  test('notifyFailure: ORDER_NOT_PAYABLE re-fetches, others alert', () => {
    expect(notifyFailure({ code: 'ORDER_NOT_PAYABLE' })).toEqual({
      alertKey: 'theme.errors.ORDER_NOT_PAYABLE',
      refetch: true,
    });
    expect(notifyFailure({ code: 'NETWORK' })).toEqual({
      alertKey: 'theme.errors.NETWORK',
      refetch: false,
    });
  });

  test('instructionRows keeps labelled rows and copy only for non-empty values', () => {
    expect(
      instructionRows({
        fields: [
          { label: 'IBAN', value: 'TR00 1', copyable: true },
          { label: 'Reference', value: 'ABC', copyable: false },
          { label: 'Empty', value: '', copyable: true },
          { label: '', value: 'x' },
          null,
        ],
      }),
    ).toEqual([
      { label: 'IBAN', value: 'TR00 1', copyable: true },
      { label: 'Reference', value: 'ABC', copyable: false },
      { label: 'Empty', value: '', copyable: false },
    ]);
    expect(instructionRows(undefined)).toEqual([]);
  });
});

// ---- the components (no DOM harness in this repo: the source rules of 14 §2 / §11.4 are pinned here) ---------

const here = path.dirname(new URL(import.meta.url).pathname);
const orderDir = path.resolve(here, '../../components/order');
const read = (name) => fs.readFileSync(path.join(orderDir, name), 'utf8');
const PANEL_FILES = [
  'PaymentPanel.svelte',
  'PaymentIframe.svelte',
  'PaymentEmbedded.svelte',
  'GenericPaymentForm.svelte',
  'PaymentInstructions.svelte',
];

describe('payment panel sources', () => {
  test('{@html} appears only in PaymentInstructions and only for instructions.body', () => {
    for (const name of PANEL_FILES) {
      const sinks = [...read(name).matchAll(/\{@html\s+([^}]*)\}/g)].map((m) => m[1].trim());

      if (name === 'PaymentInstructions.svelte') expect(sinks).toEqual(['instructions.body']);
      else expect(sinks).toEqual([]);
    }
  });

  test('no component creates a script element or sets an iframe src from anything but the checked start', () => {
    for (const name of PANEL_FILES) {
      const source = read(name);

      expect(source).not.toMatch(/createElement\(\s*['"]script/);
      expect(source).not.toMatch(/\.innerHTML\s*=/);
      expect(source).not.toMatch(/document\.write/);
      expect(source).not.toMatch(/\beval\(|new Function\(/);
    }

    // scripts go through loadScripts() with a scriptsPlan() list; the iframe is rendered only after that
    expect(read('PaymentIframe.svelte')).toMatch(/scriptsPlan\(current\?\.scripts\)/);
    expect(read('PaymentIframe.svelte')).toMatch(/\{:else\}\s*<iframe/);
    expect(read('PaymentEmbedded.svelte')).toMatch(/scriptsPlan\(current\?\.embedded\?\.scripts\)/);
  });

  test('the iframe carries the attributes of 14 §11.4', () => {
    const source = read('PaymentIframe.svelte');

    expect(source).toContain('class="w-100 border-0 rounded"');
    expect(source).toContain('referrerpolicy="strict-origin-when-cross-origin"');
    expect(source).toMatch(/height=\{iframeHeight\(iframe\.heightPx\)\}/);
    expect(source).toMatch(/allow=\{iframeAllow\(iframe\.allow\)\}/);
  });

  test('the bank transfer notice posts to bank-transfer/notify, the form posts to payment/continue and pay to /pay', () => {
    expect(read('PaymentInstructions.svelte')).toContain('/bank-transfer/notify');
    expect(read('PaymentPanel.svelte')).toContain('/payment/continue');
    expect(read('PaymentPanel.svelte')).toMatch(
      /\/api\/market\/orders\/\$\{encodeURIComponent\(id\)\}\/pay`/,
    );
  });

  test('the plugin component is rendered with the documented props and falls back to the generic form', () => {
    const source = read('PaymentEmbedded.svelte');

    expect(source).toMatch(
      /<Plugin\s+\{order\}\s+\{payment\}\s+props=\{[^\n]*\}\s+locale=\{[^\n]*\}\s+\{continuePayment\}\s+\{refresh\}/s,
    );
    expect(source).toContain('setStatus(embeddedFallback(current))');
    expect(source).toContain('<GenericPaymentForm');
    // browser only: the lookup lives in onMount
    expect(source.indexOf('onMount(')).toBeGreaterThan(source.indexOf('resolveComponent'));
  });

  test('the panel reads the payment start from the order and offers "pay another way" with the shared pieces', () => {
    const source = read('PaymentPanel.svelte');

    expect(source).toContain('<PaymentMethodPicker');
    expect(source).toContain('<CreditsSection');
    expect(source).toContain('<BillingSection');
    expect(source).toContain('theme.order.payment-other');
  });

  test('every theme.order.payment-* / transfer-* key used by the five components exists in the three locales', () => {
    const used = new Set();

    for (const name of PANEL_FILES)
      for (const m of read(name).matchAll(
        /['"`](theme\.order\.(?:payment|transfer)-[a-z-]+)['"`]/g,
      ))
        used.add(m[1]);

    expect(used.size).toBeGreaterThan(10);

    for (const lang of ['en-US', 'tr', 'ru']) {
      const json = JSON.parse(
        fs.readFileSync(path.resolve(here, `../../../locales/theme/${lang}.json`), 'utf8'),
      );

      for (const key of used) {
        const value = key.split('.').reduce((node, part) => node?.[part], json);

        expect(typeof value, `${lang}: ${key}`).toBe('string');
        expect(value.length).toBeGreaterThan(0);
      }
    }
  });
});
