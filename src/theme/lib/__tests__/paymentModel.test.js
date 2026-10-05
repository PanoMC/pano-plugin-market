import { describe, expect, test } from 'bun:test';
import {
  DEFAULT_ICON,
  TITLE_SENTINEL,
  creditsPatchAfterQuote,
  creditsRadio,
  isExternalPricing,
  legalChanged,
  logoSource,
  mixedActive,
  mixedControlsVisible,
  noticeLinks,
  parseCreditAmount,
  restoredCreditsPatch,
  pickerView,
  placeOrderState,
  quoteIsFresh,
  safeColor,
  safeHttpUrl,
  safeIcon,
  selectCreditsPatch,
  selectMethodPatch,
  selectedMethod,
  splitLegalLabel,
  unavailableKey,
} from '../paymentModel.js';
import { applyQuoteSelections } from '../checkoutModel.js';

const credits = (extra = {}) => ({
  enabled: true,
  name: 'Coins',
  balance: 100,
  payableInCredits: true,
  creditTotal: 40,
  maxApplicable: 40,
  applied: 0,
  appliedValue: 0,
  ...extra,
});

describe('values that end up in markup', () => {
  test.each(['#fff', '#FFFA', '#a1b2c3', '#a1b2c3d4'])('safeColor accepts %s', (c) => {
    expect(safeColor(c)).toBe(c);
  });

  test.each([
    'red',
    '#ggg',
    '#12345',
    'url(javascript:alert(1))',
    '#fff;background:url(x)',
    'red" onmouseover="x',
    '',
    null,
    undefined,
    7,
  ])('safeColor rejects %p', (c) => {
    expect(safeColor(c)).toBeNull();
  });

  test('safeIcon keeps a Font Awesome class list and rejects anything else', () => {
    expect(safeIcon('fa-brands fa-paypal')).toBe('fa-brands fa-paypal');
    expect(safeIcon('  fa-solid   fa-wallet ')).toBe('fa-solid fa-wallet');
    expect(safeIcon('fa-solid')).toBe(DEFAULT_ICON);
    expect(safeIcon('btn btn-danger')).toBe(DEFAULT_ICON);
    expect(safeIcon('fa-solid fa-x" onclick="alert(1)')).toBe(DEFAULT_ICON);
    expect(safeIcon('fa-solid fa-credit-card position-fixed')).toBe(DEFAULT_ICON);
    expect(safeIcon(null)).toBe(DEFAULT_ICON);
    expect(safeIcon('')).toBe(DEFAULT_ICON);
    expect(safeIcon('fa-a fa-b fa-c fa-d fa-e fa-f fa-g')).toBe(DEFAULT_ICON);
  });

  test('safeHttpUrl accepts absolute http(s) only', () => {
    expect(safeHttpUrl('https://example.com/terms')).toBe('https://example.com/terms');
    expect(safeHttpUrl('http://example.com')).toBe('http://example.com');

    for (const bad of [
      'javascript:alert(1)',
      'data:text/html,x',
      '//example.com',
      '/relative',
      'ftp://example.com',
      'https://exa mple.com',
      'https://example.com/\n',
      '',
      null,
      5,
    ])
      expect(safeHttpUrl(bad)).toBeNull();
  });

  test('logoSource: absolute http(s) or a root-relative path, nothing else', () => {
    expect(logoSource('https://cdn.example.com/l.png')).toEqual({
      url: 'https://cdn.example.com/l.png',
      relative: false,
    });
    expect(logoSource('/api/market/payment-providers/stripe/logo')).toEqual({
      url: '/api/market/payment-providers/stripe/logo',
      relative: true,
    });

    for (const bad of [
      'javascript:alert(1)',
      '//evil.example.com/l.png',
      '/\\evil.example.com',
      'data:image/svg+xml,<svg onload=alert(1)>',
      'logo.png',
      '/a b',
      '',
      null,
    ])
      expect(logoSource(bad)).toBeNull();
  });

  test('noticeLinks keeps http(s) links with a label in server order', () => {
    const links = noticeLinks({
      notices: [
        { label: 'Terms', url: 'https://pay.test/terms' },
        { label: 'Evil', url: 'javascript:alert(1)' },
        { label: '', url: 'https://pay.test/empty' },
        { label: 'Privacy', url: 'http://pay.test/privacy' },
        null,
        { label: 'Relative', url: '/terms' },
      ],
    });

    expect(links).toEqual([
      { label: 'Terms', url: 'https://pay.test/terms' },
      { label: 'Privacy', url: 'http://pay.test/privacy' },
    ]);
    expect(noticeLinks(null)).toEqual([]);
    expect(noticeLinks({ notices: 'x' })).toEqual([]);
  });
});

describe('payment methods', () => {
  test('pickerView: list, nothing available, nothing to pay', () => {
    const methods = [
      { id: 'a', available: true },
      { id: 'b', available: false, unavailableReason: 'TEST_MODE' },
    ];

    expect(pickerView({ gatewayAmount: 10, paymentMethods: methods })).toEqual({
      mode: 'LIST',
      methods,
    });
    expect(pickerView({ gatewayAmount: 10, paymentMethods: [methods[1]] }).mode).toBe('NO_METHOD');
    expect(pickerView({ gatewayAmount: 10, paymentMethods: [] }).mode).toBe('NO_METHOD');
    expect(pickerView({ gatewayAmount: 0, paymentMethods: methods }).mode).toBe('NONE_NEEDED');
    expect(pickerView(null)).toEqual({ mode: 'LIST', methods: [] });
  });

  test('server order is kept and a missing `available` counts as available', () => {
    const methods = [{ id: 'z' }, { id: 'a', available: true }];

    expect(
      pickerView({ gatewayAmount: 5, paymentMethods: methods }).methods.map((m) => m.id),
    ).toEqual(['z', 'a']);
    expect(pickerView({ gatewayAmount: 5, paymentMethods: methods }).mode).toBe('LIST');
  });

  test('unavailableKey: a known reason, else the generic method text', () => {
    expect(unavailableKey('TEST_MODE')).toBe('theme.errors.TEST_MODE');
    expect(unavailableKey('CURRENCY_NOT_SUPPORTED')).toBe('theme.errors.CURRENCY_NOT_SUPPORTED');
    expect(unavailableKey('TOTALLY_NEW')).toBe('theme.errors.PAYMENT_METHOD_UNAVAILABLE');
    expect(unavailableKey(null)).toBe('theme.errors.PAYMENT_METHOD_UNAVAILABLE');
  });

  test('isExternalPricing: EXTERNAL and EXTERNAL_TAX', () => {
    expect(isExternalPricing({ pricing: 'EXTERNAL' })).toBe(true);
    expect(isExternalPricing({ pricing: 'EXTERNAL_TAX' })).toBe(true);
    expect(isExternalPricing({ pricing: 'MARKET' })).toBe(false);
    expect(isExternalPricing(null)).toBe(false);
  });

  test('selectedMethod finds the option by id', () => {
    const quote = { paymentMethods: [{ id: 'a' }, { id: 7 }] };

    expect(selectedMethod(quote, 'a')).toEqual({ id: 'a' });
    expect(selectedMethod(quote, 7)).toEqual({ id: 7 });
    expect(selectedMethod(quote, 'zzz')).toBeNull();
    expect(selectedMethod(quote, null)).toBeNull();
    expect(selectedMethod(null, 'a')).toBeNull();
  });

  test('choosing a method turns credit payment off; choosing credits clears the method and the mixed amount', () => {
    expect(selectMethodPatch({ useCredits: null }, 'stripe', { pricing: 'MARKET' })).toEqual({
      paymentMethodId: 'stripe',
      payWithCredits: false,
    });
    expect(selectCreditsPatch()).toEqual({
      payWithCredits: true,
      paymentMethodId: null,
      useCredits: null,
    });
  });

  test('choosing an EXTERNAL method clears a mixed amount', () => {
    expect(selectMethodPatch({ useCredits: 10 }, 'ext', { pricing: 'EXTERNAL' })).toMatchObject({
      useCredits: null,
    });
    expect(
      selectMethodPatch({ useCredits: 10 }, 'm', { pricing: 'MARKET' }).useCredits,
    ).toBeUndefined();
  });
});

describe('credits: never pre-applied', () => {
  test('the default draft has no credit choice and mixedActive says so', () => {
    expect(mixedActive({ useCredits: null })).toBe(false);
    expect(mixedActive({})).toBe(false);
    expect(mixedActive(undefined)).toBe(false);
    expect(mixedActive({ useCredits: 0 })).toBe(true);
    expect(mixedActive({ useCredits: 'MAX' })).toBe(true);
  });

  test('a credit choice stored by an earlier visit is dropped when the draft is restored', () => {
    expect(restoredCreditsPatch({ payWithCredits: true, useCredits: null })).toEqual({
      payWithCredits: false,
      useCredits: null,
    });
    expect(restoredCreditsPatch({ payWithCredits: false, useCredits: 12 })).toEqual({
      payWithCredits: false,
      useCredits: null,
    });
    expect(restoredCreditsPatch({ payWithCredits: false, useCredits: 'MAX' })).toEqual({
      payWithCredits: false,
      useCredits: null,
    });
    expect(restoredCreditsPatch({ payWithCredits: false, useCredits: null })).toEqual({});
    expect(restoredCreditsPatch(undefined)).toEqual({});
  });

  test('a quote never switches credits on: patches only clear', () => {
    const draft = { payWithCredits: false, useCredits: null, paymentMethodId: 'stripe' };
    const quote = { credits: credits(), paymentMethods: [{ id: 'stripe', pricing: 'MARKET' }] };

    expect(creditsPatchAfterQuote({ draft, quote, config: { mixedCredit: true } })).toEqual({});
  });

  test('applyQuoteSelections does not touch the credit choice either', () => {
    const patch = applyQuoteSelections(
      { payWithCredits: false, useCredits: null, paymentMethodId: null },
      {
        gatewayAmount: 10,
        paymentMethods: [{ id: 'only', available: true }],
        credits: credits(),
      },
    );

    expect(patch).toEqual({ paymentMethodId: 'only' });
    expect('useCredits' in patch).toBe(false);
    expect('payWithCredits' in patch).toBe(false);
  });

  test('credits that become unavailable are cleared', () => {
    const config = { mixedCredit: true };

    expect(
      creditsPatchAfterQuote({
        draft: { payWithCredits: true, useCredits: null },
        quote: { credits: credits({ payableInCredits: false }) },
        config,
      }),
    ).toEqual({ payWithCredits: false });

    expect(
      creditsPatchAfterQuote({
        draft: { payWithCredits: false, useCredits: 10 },
        quote: { credits: null },
        config,
      }),
    ).toEqual({ useCredits: null });

    expect(
      creditsPatchAfterQuote({
        draft: { payWithCredits: false, useCredits: 10 },
        quote: { credits: credits({ enabled: false }) },
        config,
      }),
    ).toEqual({ useCredits: null });
  });

  test('a mixed amount is cleared when the store stops allowing it', () => {
    const draft = { payWithCredits: false, useCredits: 10, paymentMethodId: 'm' };
    const methods = [{ id: 'm', pricing: 'MARKET' }];

    expect(
      creditsPatchAfterQuote({
        draft,
        quote: { credits: credits(), paymentMethods: methods },
        config: { mixedCredit: false },
      }),
    ).toEqual({ useCredits: null });
    expect(
      creditsPatchAfterQuote({
        draft,
        quote: { credits: credits({ maxApplicable: 0 }), paymentMethods: methods },
        config: { mixedCredit: true },
      }),
    ).toEqual({ useCredits: null });
    expect(
      creditsPatchAfterQuote({
        draft,
        quote: { credits: credits(), paymentMethods: [{ id: 'm', pricing: 'EXTERNAL' }] },
        config: { mixedCredit: true },
      }),
    ).toEqual({ useCredits: null });
  });

  test('paying in credits clears a stale mixed amount', () => {
    expect(
      creditsPatchAfterQuote({
        draft: { payWithCredits: true, useCredits: 5 },
        quote: { credits: credits() },
        config: { mixedCredit: true },
      }),
    ).toEqual({ useCredits: null });
  });

  test('a still valid explicit choice is kept', () => {
    const draft = { payWithCredits: false, useCredits: 12, paymentMethodId: 'm' };
    const quote = { credits: credits(), paymentMethods: [{ id: 'm', pricing: 'MARKET' }] };

    expect(creditsPatchAfterQuote({ draft, quote, config: { mixedCredit: true } })).toEqual({});
  });
});

describe('credits radio and mixed controls', () => {
  test('creditsRadio: payable, insufficient', () => {
    expect(creditsRadio(credits())).toEqual({ enabled: true, payable: true, insufficient: false });
    expect(creditsRadio(credits({ balance: 10 }))).toEqual({
      enabled: true,
      payable: true,
      insufficient: true,
    });
    expect(creditsRadio(credits({ payableInCredits: false }))).toEqual({
      enabled: true,
      payable: false,
      insufficient: false,
    });
    expect(creditsRadio(null)).toEqual({ enabled: false, payable: false, insufficient: false });
  });

  const visible = (over = {}) =>
    mixedControlsVisible({
      config: { mixedCredit: true },
      credits: credits(),
      payWithCredits: false,
      method: { pricing: 'MARKET' },
      ...over,
    });

  test('shown for mixed credit, something to apply, no whole-order credit payment, a MARKET method', () => {
    expect(visible()).toBe(true);
    expect(visible({ method: null })).toBe(true);
  });

  test.each([
    ['mixed credit off', { config: { mixedCredit: false } }],
    ['no config', { config: null }],
    ['nothing applicable', { credits: credits({ maxApplicable: 0 }) }],
    ['credits disabled', { credits: credits({ enabled: false }) }],
    ['no credits', { credits: null }],
    ['paying in credits', { payWithCredits: true }],
    ['EXTERNAL pricing', { method: { pricing: 'EXTERNAL' } }],
    ['EXTERNAL_TAX pricing', { method: { pricing: 'EXTERNAL_TAX' } }],
    [
      'method cannot mix',
      { method: { pricing: 'MARKET', unavailableReason: 'MIXED_CREDIT_NOT_SUPPORTED' } },
    ],
  ])('hidden: %s', (_, over) => {
    expect(visible(over)).toBe(false);
  });

  test('parseCreditAmount reads non-negative numbers with at most two kept decimals', () => {
    expect(parseCreditAmount('12')).toEqual({ ok: true, value: 12 });
    expect(parseCreditAmount('12.5')).toEqual({ ok: true, value: 12.5 });
    expect(parseCreditAmount('12,75')).toEqual({ ok: true, value: 12.75 });
    expect(parseCreditAmount('0')).toEqual({ ok: true, value: 0 });
    expect(parseCreditAmount('1.239')).toEqual({ ok: true, value: 1.24 });

    for (const bad of [
      '',
      '-1',
      'abc',
      '1e3',
      '1.2.3',
      ' ',
      '12 coins',
      'Infinity',
      '1'.repeat(14),
    ])
      expect(parseCreditAmount(bad)).toEqual({ ok: false });
  });
});

describe('placeOrderState', () => {
  const quote = (over = {}) => ({ canCheckout: true, gatewayAmount: 25, total: 25, ...over });
  const draft = (over = {}) => ({ paymentMethodId: 'stripe', payWithCredits: false, ...over });

  test('enabled with a method and a payable quote; the amount is the server gatewayAmount', () => {
    expect(
      placeOrderState({ pageState: 'READY', quote: quote(), draft: draft(), quoteFresh: true }),
    ).toEqual({
      disabled: false,
      mode: 'PAY',
      amount: 25,
    });
  });

  test('complete mode (nothing to pay) needs no method', () => {
    expect(
      placeOrderState({
        pageState: 'READY',
        quoteFresh: true,
        quote: quote({ gatewayAmount: 0 }),
        draft: draft({ paymentMethodId: null }),
      }),
    ).toEqual({ disabled: false, mode: 'COMPLETE', amount: 0 });
  });

  test.each(['QUOTING', 'SUBMITTING', 'LEAVING', 'BLOCKED', 'INIT', 'EMPTY'])(
    'disabled in page state %s',
    (pageState) => {
      expect(
        placeOrderState({ pageState, quote: quote(), draft: draft(), quoteFresh: true }).disabled,
      ).toBe(true);
    },
  );

  test('disabled without a quote, without canCheckout and without a method while something is left to pay', () => {
    expect(
      placeOrderState({ pageState: 'READY', quote: null, draft: draft(), quoteFresh: true })
        .disabled,
    ).toBe(true);
    expect(
      placeOrderState({
        pageState: 'READY',
        quote: quote({ canCheckout: false }),
        draft: draft(),
        quoteFresh: true,
      }).disabled,
    ).toBe(true);
    expect(
      placeOrderState({
        pageState: 'READY',
        quoteFresh: true,
        quote: quote(),
        draft: draft({ paymentMethodId: null }),
      }).disabled,
    ).toBe(true);
  });

  test('paying in credits needs no gateway method', () => {
    expect(
      placeOrderState({
        pageState: 'READY',
        quoteFresh: true,
        quote: quote({ gatewayAmount: 0 }),
        draft: draft({ paymentMethodId: null, payWithCredits: true }),
      }).disabled,
    ).toBe(false);
  });

  test('disabled while the quote is not fresh, whatever the page state says', () => {
    const base = { pageState: 'READY', quote: quote(), draft: draft() };

    expect(placeOrderState({ ...base, quoteFresh: true }).disabled).toBe(false);
    expect(placeOrderState({ ...base, quoteFresh: false }).disabled).toBe(true);
    expect(placeOrderState(base).disabled).toBe(true); // absent = stale
    expect(placeOrderState({ ...base, quoteFresh: 'true' }).disabled).toBe(true);
  });

  test.each(['ERROR', 'RATE_LIMITED', 'QUOTING', 'DISABLED'])(
    'runner %s with an earlier quote on screen: the page is READY but the button is disabled',
    (status) => {
      const current = 'sig-a';
      const fresh = quoteIsFresh({
        status,
        quoteSignature: current,
        currentSignature: current,
      });

      expect(fresh).toBe(false);
      expect(
        placeOrderState({ pageState: 'READY', quote: quote(), draft: draft(), quoteFresh: fresh })
          .disabled,
      ).toBe(true);
    },
  );

  test('a quote for an older input is stale even when the runner is idle again', () => {
    expect(
      quoteIsFresh({ status: 'IDLE', quoteSignature: 'sig-a', currentSignature: 'sig-b' }),
    ).toBe(false);
    expect(quoteIsFresh({ status: 'IDLE', quoteSignature: null, currentSignature: 'sig-b' })).toBe(
      false,
    );
    expect(quoteIsFresh({ status: 'IDLE', quoteSignature: '', currentSignature: '' })).toBe(false);
  });

  test('an idle runner and the signature the quote was asked for is fresh', () => {
    expect(
      quoteIsFresh({ status: 'IDLE', quoteSignature: 'sig-a', currentSignature: 'sig-a' }),
    ).toBe(true);
  });
});

describe('legal', () => {
  test('splitLegalLabel puts the title between the two halves', () => {
    expect(splitLegalLabel(`I accept the ${TITLE_SENTINEL} today`)).toEqual({
      before: 'I accept the ',
      after: ' today',
    });
    expect(splitLegalLabel(`${TITLE_SENTINEL} kabul`)).toEqual({ before: '', after: ' kabul' });
    expect(splitLegalLabel('no title here')).toEqual({ before: 'no title here', after: '' });
    expect(splitLegalLabel(undefined)).toEqual({ before: '', after: '' });
  });

  test('legalChanged: the quote was priced against another text than the config shows', () => {
    expect(legalChanged({ legal: { id: 1 } }, { legal: { id: 2 } })).toBe(true);
    expect(legalChanged({ legal: { id: 2 } }, { legal: { id: 2 } })).toBe(false);
    expect(legalChanged({ legal: null }, { legal: { id: 2 } })).toBe(false);
    expect(legalChanged({ legal: { id: 1 } }, { legal: null })).toBe(false);
    expect(legalChanged({ legal: { id: 1 } }, null)).toBe(false);
  });
});
