import { describe, expect, test } from 'bun:test';
import {
  applyQuoteSelections,
  billingRequirements,
  buildQuoteBody,
  canonicalBody,
  carrierExtrasFor,
  checkShippingAddress,
  derivePageState,
  effectiveBillingInfo,
  emptyAddress,
  extraShippingFields,
  fieldId,
  firstInvalidId,
  lineNamesWith,
  mapBuyerFields,
  mergeServerCodes,
  NO_CARRIER_EXTRAS,
  nextCarrierExtras,
  parseTopup,
  persistPatch,
  quoteDelay,
  quoteHasCode,
  resolveCheckoutLoad,
  sectionsVisible,
  shippingCountries,
  validateBilling,
  validateCheckout,
} from '../checkoutModel.js';
import { defaultDraft } from '../../stores/checkoutDraft.js';

const config = (extra = {}) => ({
  guestCheckout: true,
  giftPurchase: true,
  billingInfoMode: 'OFF',
  shippingCountries: ['TR', 'DE'],
  addressFields: {
    '*': ['firstName', 'lastName', 'phone', 'country', 'city', 'line1', 'postalCode'],
    TR: ['firstName', 'lastName', 'phone', 'country', 'city', 'district', 'line1'],
  },
  legal: { required: false },
  ...extra,
});

const trAddress = (extra = {}) => ({
  ...emptyAddress(),
  firstName: 'Ayse',
  lastName: 'Yilmaz',
  phone: '+905551112233',
  country: 'TR',
  city: 'Izmir',
  district: 'Konak',
  line1: 'Main St 1',
  ...extra,
});

const draftWith = (extra = {}) => ({ ...defaultDraft(), ...extra });

describe('resolveCheckoutLoad', () => {
  test('a flat config body becomes READY with the config', () => {
    const r = resolveCheckoutLoad({
      res: { ok: true, guestCheckout: true, billingInfoMode: 'OFF', addressFields: {} },
      settings: { storeName: 'S' },
      topup: 5,
    });

    expect(r.data.state).toBe('READY');
    expect(r.data.config.guestCheckout).toBe(true);
    expect('ok' in r.data.config).toBe(false);
    expect(r.data.topup).toBe(5);
    expect(r.data.settingsLoaded).toBe(true);
    expect(r.pageTitle).toBe('plugins.pano-plugin-market.theme.checkout.title');
  });

  test('a config member wins over the flat body', () => {
    const r = resolveCheckoutLoad({ res: { ok: true, config: { guestCheckout: false } } });

    expect(r.data.config).toEqual({ guestCheckout: false });
  });

  test('STORE_DISABLED / STORE_UNAVAILABLE => DISABLED, other failures => ERROR', () => {
    expect(resolveCheckoutLoad({ res: { ok: false, code: 'STORE_DISABLED' } }).data.state).toBe(
      'DISABLED',
    );
    expect(resolveCheckoutLoad({ res: { ok: false, code: 'STORE_UNAVAILABLE' } }).data.state).toBe(
      'DISABLED',
    );

    const err = resolveCheckoutLoad({ res: { ok: false, code: 'NETWORK' } });
    expect(err.data.state).toBe('ERROR');
    expect(err.data.code).toBe('NETWORK');
    expect(resolveCheckoutLoad({ res: undefined }).data.state).toBe('ERROR');
    expect(resolveCheckoutLoad({ res: { ok: true } }).data.state).toBe('ERROR');
  });

  test('meta noindex only with the page-meta feature; missing settings are flagged', () => {
    const on = resolveCheckoutLoad({
      res: { ok: true, guestCheckout: true },
      settings: null,
      features: { meta: true },
    });
    const off = resolveCheckoutLoad({ res: { ok: true, guestCheckout: true } });

    expect(on.meta).toEqual({ robots: 'noindex,nofollow' });
    expect(on.data.settingsLoaded).toBe(false);
    expect(on.data.settings).toEqual({});
    expect('meta' in off).toBe(false);
  });

  test('parseTopup accepts a positive decimal with at most two decimals', () => {
    expect(parseTopup('10')).toBe(10);
    expect(parseTopup('2.5')).toBe(2.5);
    expect(parseTopup('0')).toBeNull();
    expect(parseTopup('-1')).toBeNull();
    expect(parseTopup('1.234')).toBeNull();
    expect(parseTopup('abc')).toBeNull();
    expect(parseTopup(null)).toBeNull();
  });
});

describe('derivePageState', () => {
  const base = { cartReady: true, count: 1, loggedIn: true, guestCheckout: true };

  test('INIT until the cart is ready', () => {
    expect(derivePageState({ ...base, cartReady: false })).toBe('INIT');
  });

  test('EMPTY with no lines and no top-up', () => {
    expect(derivePageState({ ...base, count: 0 })).toBe('EMPTY');
    expect(derivePageState({ ...base, count: 0, topup: 5 })).toBe('READY');
  });

  test('guest with guest checkout off => LOGIN_REQUIRED; on => READY', () => {
    expect(derivePageState({ ...base, loggedIn: false, guestCheckout: false })).toBe(
      'LOGIN_REQUIRED',
    );
    expect(derivePageState({ ...base, loggedIn: false })).toBe('READY');
  });

  test('a guest quote that carries LOGIN_REQUIRED (message or line error) => LOGIN_REQUIRED', () => {
    const lineQuote = { lines: [{ errors: ['LOGIN_REQUIRED'] }] };
    const msgQuote = { messages: [{ code: 'LOGIN_REQUIRED', level: 'error' }] };

    expect(derivePageState({ ...base, loggedIn: false, quote: lineQuote })).toBe('LOGIN_REQUIRED');
    expect(derivePageState({ ...base, loggedIn: false, quote: msgQuote })).toBe('LOGIN_REQUIRED');
    expect(derivePageState({ ...base, loggedIn: true, quote: msgQuote })).toBe('READY');
  });

  test('top-up needs a logged-in buyer', () => {
    expect(derivePageState({ ...base, loggedIn: false, topup: 5 })).toBe('LOGIN_REQUIRED');
  });

  test('BLOCKED for BUYER_BLOCKED at level error only', () => {
    const error = { messages: [{ code: 'BUYER_BLOCKED', level: 'error' }] };
    const warning = { messages: [{ code: 'BUYER_BLOCKED', level: 'warning' }] };

    expect(derivePageState({ ...base, quote: error })).toBe('BLOCKED');
    expect(derivePageState({ ...base, quote: warning })).toBe('READY');
    expect(derivePageState({ ...base, forced: 'BLOCKED' })).toBe('BLOCKED');
  });

  test('QUOTING while a quote is pending, SUBMITTING / LEAVING win over the rest', () => {
    expect(derivePageState({ ...base, quoting: true })).toBe('QUOTING');
    expect(derivePageState({ ...base, quoting: true, submit: 'SUBMITTING' })).toBe('SUBMITTING');
    expect(derivePageState({ ...base, submit: 'LEAVING' })).toBe('LEAVING');
  });

  test('DISABLED wins over everything', () => {
    expect(derivePageState({ ...base, cartReady: false, forced: 'DISABLED' })).toBe('DISABLED');
    expect(derivePageState({ ...base, submit: 'LEAVING', forced: 'DISABLED' })).toBe('DISABLED');
  });

  test('forced EMPTY and LOGIN_REQUIRED (error mapping) apply', () => {
    expect(derivePageState({ ...base, forced: 'EMPTY' })).toBe('EMPTY');
    expect(derivePageState({ ...base, forced: 'LOGIN_REQUIRED' })).toBe('LOGIN_REQUIRED');
  });
});

describe('quote helpers', () => {
  test('quoteHasCode looks in messages and line errors, with an optional level', () => {
    const quote = {
      messages: [{ code: 'RECIPIENT_UNKNOWN', level: 'warning' }],
      lines: [
        { name: 'A', errors: ['GIFT_NOT_ALLOWED'] },
        { name: 'B', errors: [] },
      ],
    };

    expect(quoteHasCode(quote, 'RECIPIENT_UNKNOWN')).toBe(true);
    expect(quoteHasCode(quote, 'RECIPIENT_UNKNOWN', 'error')).toBe(false);
    expect(quoteHasCode(quote, 'GIFT_NOT_ALLOWED')).toBe(true);
    expect(quoteHasCode(quote, 'NOPE')).toBe(false);
    expect(quoteHasCode(null, 'X')).toBe(false);
    expect(lineNamesWith(quote, 'GIFT_NOT_ALLOWED')).toEqual(['A']);
    expect(lineNamesWith(null, 'X')).toEqual([]);
  });

  test('extraShippingFields reads the carrier fields of SHIPPING_ADDRESS_INVALID', () => {
    const quote = {
      messages: [
        {
          code: 'SHIPPING_ADDRESS_INVALID',
          level: 'error',
          fields: ['district', 'bogus', 'district'],
        },
      ],
    };

    expect(extraShippingFields(quote)).toEqual(['district']);
    expect(extraShippingFields({})).toEqual([]);
  });
});

describe('sectionsVisible', () => {
  test('gift needs the setting and no top-up; shipping needs requiresShipping; billing mode or named field', () => {
    expect(sectionsVisible({ config: config(), quote: null })).toEqual({
      gift: true,
      shipping: false,
      billing: false,
    });
    expect(
      sectionsVisible({ config: config({ giftPurchase: false }), quote: null, topup: null }).gift,
    ).toBe(false);
    expect(sectionsVisible({ config: config(), quote: null, topup: 5 }).gift).toBe(false);
    expect(sectionsVisible({ config: config(), quote: { requiresShipping: true } }).shipping).toBe(
      true,
    );
    expect(
      sectionsVisible({ config: config({ billingInfoMode: 'OPTIONAL' }), quote: null }).billing,
    ).toBe(true);
    expect(
      sectionsVisible({ config: config(), quote: { requiredBuyerFields: ['IDENTITY_NUMBER'] } })
        .billing,
    ).toBe(true);
    expect(
      sectionsVisible({
        config: config(),
        quote: { requiredBuyerFields: ['EMAIL', 'SHIPPING_ADDRESS'] },
      }).billing,
    ).toBe(false);
  });
});

describe('shipping address', () => {
  test('required sets per country come from checkout/config and drive validation', () => {
    const tr = checkShippingAddress(config(), trAddress());
    expect(tr.ok).toBe(true);
    expect([...tr.required].sort()).toEqual(
      ['city', 'country', 'district', 'firstName', 'lastName', 'line1', 'phone'].sort(),
    );

    // Germany falls back to '*': postalCode is required there
    const de = checkShippingAddress(config(), trAddress({ country: 'DE', district: '' }));
    expect(de.ok).toBe(false);
    expect(de.errors).toEqual({ postalCode: 'FIELD_REQUIRED' });

    const deOk = checkShippingAddress(
      config(),
      trAddress({ country: 'DE', district: '', postalCode: '10115' }),
    );
    expect(deOk.ok).toBe(true);
  });

  test('a country outside config.shippingCountries is invalid; a missing country is required', () => {
    expect(checkShippingAddress(config(), trAddress({ country: 'US' })).errors.country).toBe(
      'FIELD_INVALID',
    );
    expect(checkShippingAddress(config(), trAddress({ country: '' })).errors.country).toBe(
      'FIELD_REQUIRED',
    );
    expect(checkShippingAddress({}, trAddress({ country: '', district: '' })).errors.country).toBe(
      'FIELD_REQUIRED',
    );
  });

  test('phone must be E.164, texts at most 255 and postalCode 16', () => {
    expect(checkShippingAddress(config(), trAddress({ phone: '0555' })).errors.phone).toBe(
      'FIELD_INVALID',
    );
    expect(checkShippingAddress(config(), trAddress({ line1: 'x'.repeat(256) })).errors.line1).toBe(
      'FIELD_INVALID',
    );
    expect(
      checkShippingAddress(config(), trAddress({ postalCode: '1'.repeat(17) })).errors.postalCode,
    ).toBe('FIELD_INVALID');
  });

  test('carrier extras add required fields', () => {
    expect(checkShippingAddress(config(), trAddress(), ['neighborhood']).errors).toEqual({
      neighborhood: 'FIELD_REQUIRED',
    });
  });

  test('shippingCountries falls back to every country when the config has no list', () => {
    expect(shippingCountries(config())).toEqual(['TR', 'DE']);
    expect(shippingCountries({}).length).toBe(249);
    expect(shippingCountries({ shippingCountries: [] }).length).toBe(249);
  });
});

describe('billing', () => {
  const fields = (names) => names;

  test('buyer field mapping', () => {
    const set = mapBuyerFields(
      ['FIRST_NAME', 'PHONE', 'IDENTITY_NUMBER', 'EMAIL', 'SHIPPING_ADDRESS'],
      config().addressFields,
      'TR',
    );

    expect([...set].sort()).toEqual(['firstName', 'identityNumber', 'phone']);

    const addr = mapBuyerFields(['BILLING_ADDRESS'], config().addressFields, 'TR');
    expect([...addr].sort()).toEqual(
      ['city', 'country', 'district', 'firstName', 'lastName', 'line1', 'phone'].sort(),
    );
    expect(mapBuyerFields(['COUNTRY'], config().addressFields, '')).toEqual(new Set(['country']));
  });

  test('mode OFF: only the named fields are shown and required; hidden without names', () => {
    const none = billingRequirements({ config: config(), quote: {}, info: {} });
    expect(none.visible).toBe(false);
    expect(none.open).toBe(false);

    const req = billingRequirements({
      config: config(),
      quote: { requiredBuyerFields: ['COUNTRY', 'IDENTITY_NUMBER'] },
      info: { type: 'INDIVIDUAL', country: 'TR' },
    });

    expect(req.visible).toBe(true);
    expect(req.open).toBe(true);
    expect(req.forcedOpen).toBe(true);
    expect(req.fields).toEqual(fields(['country']));
    expect(req.showIdentity).toBe(true);
    expect(req.identityRequired).toBe(true);
  });

  test('mode OFF with the COMPANY type still shows the company input', () => {
    const req = billingRequirements({
      config: config(),
      quote: { requiredBuyerFields: ['COUNTRY'] },
      info: { type: 'COMPANY' },
    });

    expect(req.fields).toEqual(['company', 'country']);
    expect(req.required.has('company')).toBe(true);
  });

  test('mode OPTIONAL: collapsed until opened, forced open by a named field', () => {
    const optional = config({ billingInfoMode: 'OPTIONAL' });

    const closed = billingRequirements({ config: optional, quote: {}, info: {}, open: false });
    expect(closed.visible).toBe(true);
    expect(closed.open).toBe(false);
    expect(closed.showAddress).toBe(false);

    const opened = billingRequirements({ config: optional, quote: {}, info: {}, open: true });
    expect(opened.open).toBe(true);
    expect(opened.forcedOpen).toBe(false);
    expect(opened.required.has('line1')).toBe(true);

    const forced = billingRequirements({
      config: optional,
      quote: { requiredBuyerFields: ['PHONE'] },
      info: {},
      open: false,
    });
    expect(forced.open).toBe(true);
    expect(forced.forcedOpen).toBe(true);
    expect([...forced.required]).toEqual(['phone']);
  });

  test('mode REQUIRED: always open with the base set plus the per-country set', () => {
    const req = billingRequirements({
      config: config({ billingInfoMode: 'REQUIRED' }),
      quote: {},
      info: { country: 'TR' },
    });

    expect(req.open).toBe(true);
    for (const f of ['firstName', 'lastName', 'country', 'city', 'line1', 'district', 'phone'])
      expect(req.required.has(f)).toBe(true);
    expect(req.required.has('postalCode')).toBe(false);
  });

  test('COMPANY adds company and tax number as required; identity shown for TR individuals', () => {
    const company = billingRequirements({
      config: config({ billingInfoMode: 'REQUIRED' }),
      quote: {},
      info: { type: 'COMPANY', country: 'DE' },
    });
    expect(company.showCompany).toBe(true);
    expect(company.fields.includes('company')).toBe(true);
    expect(company.required.has('company')).toBe(true);
    expect(company.required.has('taxNumber')).toBe(true);
    expect(company.showIdentity).toBe(false);

    const individual = billingRequirements({
      config: config({ billingInfoMode: 'REQUIRED' }),
      quote: {},
      info: { type: 'INDIVIDUAL', country: 'TR' },
    });
    expect(individual.fields.includes('company')).toBe(false);
    expect(individual.showIdentity).toBe(true);
    expect(individual.identityRequired).toBe(false);
  });

  test('same as shipping hides the address inputs and copies the shipping address', () => {
    const shipping = trAddress({ line2: 'Flat 3' });
    const req = billingRequirements({
      config: config({ billingInfoMode: 'REQUIRED' }),
      quote: {},
      info: { type: 'INDIVIDUAL', identityNumber: '12345678901' },
      sameAsShipping: true,
      shippingAddress: shipping,
      shippingRequired: true,
    });

    expect(req.copy).toBe(true);
    expect(req.showAddress).toBe(false);
    expect(req.showIdentity).toBe(true);

    const info = effectiveBillingInfo(
      req,
      { type: 'INDIVIDUAL', identityNumber: '12345678901' },
      shipping,
    );
    expect(info.firstName).toBe('Ayse');
    expect(info.line2).toBe('Flat 3');
    expect(info.type).toBe('INDIVIDUAL');
    expect(info.identityNumber).toBe('12345678901');
    expect(validateBilling(req, { identityNumber: '12345678901' })).toEqual({});

    const off = billingRequirements({
      config: config({ billingInfoMode: 'REQUIRED' }),
      quote: {},
      info: {},
      sameAsShipping: false,
      shippingAddress: shipping,
      shippingRequired: true,
    });
    expect(off.copy).toBe(false);
    expect(off.showAddress).toBe(true);
  });

  test('validation: TR identity is 11 digits, other countries up to 32; company needs a tax number', () => {
    const req = (info, quote = {}) =>
      billingRequirements({
        config: config({ billingInfoMode: 'REQUIRED' }),
        quote,
        info,
      });

    const trInfo = { ...trAddress(), type: 'INDIVIDUAL', identityNumber: '123' };
    expect(validateBilling(req(trInfo), trInfo).identityNumber).toBe('FIELD_INVALID');

    const okInfo = { ...trInfo, identityNumber: '12345678901' };
    expect(validateBilling(req(okInfo), okInfo)).toEqual({});

    const required = { ...trInfo, identityNumber: '' };
    expect(
      validateBilling(req(required, { requiredBuyerFields: ['IDENTITY_NUMBER'] }), required)
        .identityNumber,
    ).toBe('FIELD_REQUIRED');

    const company = { ...trAddress({ country: 'DE' }), type: 'COMPANY', postalCode: '1' };
    const errors = validateBilling(req(company), company);
    expect(errors.company).toBe('FIELD_REQUIRED');
    expect(errors.taxNumber).toBe('FIELD_REQUIRED');

    const longTax = { ...company, company: 'ACME', taxNumber: 'x'.repeat(33) };
    expect(validateBilling(req(longTax), longTax).taxNumber).toBe('FIELD_INVALID');
  });

  test('a closed section validates to nothing and sends no billingInfo', () => {
    const closed = billingRequirements({
      config: config({ billingInfoMode: 'OPTIONAL' }),
      quote: {},
      info: {},
      open: false,
    });

    expect(validateBilling(closed, {})).toEqual({});
    expect(effectiveBillingInfo(closed, {})).toBeNull();
  });

  test('effectiveBillingInfo omits empty and hidden fields', () => {
    const info = {
      ...trAddress({ line2: '' }),
      type: 'COMPANY',
      company: 'ACME',
      taxNumber: '99',
      taxOffice: '',
    };
    const req = billingRequirements({
      config: config({ billingInfoMode: 'REQUIRED' }),
      quote: {},
      info,
    });
    const body = effectiveBillingInfo(req, info);

    expect(body.company).toBe('ACME');
    expect(body.taxNumber).toBe('99');
    expect('taxOffice' in body).toBe(false);
    expect('line2' in body).toBe(false);
    expect(body.type).toBe('COMPANY');
  });
});

describe('validateCheckout and focus', () => {
  test('firstInvalidId follows the DOM order of the sections', () => {
    expect(firstInvalidId({})).toBeNull();
    expect(
      firstInvalidId({ guest: { email: 'x' }, shipping: { line1: 'x' }, billing: { city: 'x' } }),
    ).toBe(fieldId('guest', 'email'));
    expect(firstInvalidId({ gift: { message: 'x' }, shipping: { firstName: 'x' } })).toBe(
      'market-checkout-gift-message',
    );
    expect(firstInvalidId({ shipping: { line1: 'x', city: 'x' } })).toBe(
      'market-checkout-shipping-city',
    );
    expect(firstInvalidId({ billing: { taxNumber: 'x', identityNumber: 'x' } })).toBe(
      'market-checkout-billing-identityNumber',
    );
  });

  test('a guest needs a valid username and e-mail; focus goes to the first invalid field', () => {
    const r = validateCheckout({
      config: config(),
      quote: {},
      draft: draftWith({ guest: { username: 'Steve', email: 'nope' } }),
    });

    expect(r.valid).toBe(false);
    expect(r.errors.guest).toEqual({ email: 'FIELD_INVALID' });
    expect(r.firstId).toBe('market-checkout-guest-email');

    const empty = validateCheckout({ config: config(), quote: {}, draft: defaultDraft() });
    expect(empty.firstId).toBe('market-checkout-guest-username');
  });

  test('a logged-in buyer is not asked for username and e-mail', () => {
    const r = validateCheckout({
      config: config(),
      quote: {},
      draft: defaultDraft(),
      user: { username: 'Steve', email: 's@x.io' },
    });

    expect(r.valid).toBe(true);
    expect(r.firstId).toBeNull();
  });

  test('gift: recipient required, must differ from the buyer (GIFT_SELF), message at most 255', () => {
    const user = { username: 'Steve' };
    const run = (extra) =>
      validateCheckout({
        config: config(),
        quote: {},
        user,
        draft: draftWith({ isGift: true, ...extra }),
      });

    expect(run({ recipientUsername: '' }).errors.gift.recipient).toBe('FIELD_REQUIRED');
    expect(run({ recipientUsername: 'sTeVe' }).errors.gift.recipient).toBe('GIFT_SELF');
    expect(run({ recipientUsername: 'bad name' }).errors.gift.recipient).toBe('FIELD_INVALID');
    expect(
      run({ recipientUsername: 'Alex', giftMessage: 'x'.repeat(256) }).errors.gift.message,
    ).toBe('FIELD_INVALID');
    expect(run({ recipientUsername: 'Alex' }).valid).toBe(true);
    expect(run({ recipientUsername: 'Alex' }).firstId).toBeNull();
    expect(run({ recipientUsername: '' }).firstId).toBe('market-checkout-gift-recipient');

    // a guest cannot gift to their own name either
    const guest = validateCheckout({
      config: config(),
      quote: {},
      draft: draftWith({
        guest: { username: 'Steve', email: 'a@b.co' },
        isGift: true,
        recipientUsername: 'steve',
      }),
    });
    expect(guest.errors.gift.recipient).toBe('GIFT_SELF');
  });

  test('gift off or gift purchases disabled: no gift validation', () => {
    const off = validateCheckout({
      config: config(),
      quote: {},
      user: { username: 'Steve' },
      draft: draftWith({ isGift: false, recipientUsername: '' }),
    });
    const disabled = validateCheckout({
      config: config({ giftPurchase: false }),
      quote: {},
      user: { username: 'Steve' },
      draft: draftWith({ isGift: true, recipientUsername: '' }),
    });

    expect(off.errors.gift).toEqual({});
    expect(disabled.errors.gift).toEqual({});
  });

  test('shipping: typed address is validated, focus goes to the first invalid address field', () => {
    const quote = { requiresShipping: true, shippingOptions: [] };
    const user = { username: 'Steve' };
    const draft = draftWith({
      shippingAddress: trAddress({ city: '', line1: '' }),
    });
    const r = validateCheckout({ config: config(), quote, user, draft });

    expect(r.errors.shipping).toEqual({ city: 'FIELD_REQUIRED', line1: 'FIELD_REQUIRED' });
    expect(r.firstId).toBe('market-checkout-shipping-city');
  });

  test('shipping: a saved address skips the address validation', () => {
    const quote = { requiresShipping: true, shippingOptions: [{ methodId: 'a' }] };
    const r = validateCheckout({
      config: config(),
      quote,
      user: { username: 'Steve' },
      draft: draftWith({ shippingAddressId: 4, shippingMethodId: 'a' }),
      saved: [{ id: 4, ...trAddress() }],
    });

    expect(r.errors.shipping).toEqual({});
    expect(r.valid).toBe(true);
  });

  test('shipping: options offered but none chosen => method error with its own id', () => {
    const r = validateCheckout({
      config: config(),
      quote: { requiresShipping: true, shippingOptions: [{ methodId: 'a' }, { methodId: 'b' }] },
      user: { username: 'Steve' },
      draft: draftWith({ shippingAddress: trAddress() }),
    });

    expect(r.errors.shipping).toEqual({ method: 'FIELD_REQUIRED' });
    expect(r.firstId).toBe('market-checkout-shipping-method');
  });

  test('billing errors are reported after the shipping ones', () => {
    const r = validateCheckout({
      config: config({ billingInfoMode: 'REQUIRED' }),
      quote: { requiresShipping: true, shippingOptions: [] },
      user: { username: 'Steve' },
      draft: draftWith({
        shippingAddress: trAddress({ line1: '' }),
        billingSameAsShipping: false,
      }),
    });

    expect(r.firstId).toBe('market-checkout-shipping-line1');
    expect(r.errors.billing.firstName).toBe('FIELD_REQUIRED');
  });
});

describe('buildQuoteBody', () => {
  const draft = draftWith({ couponCode: ' SAVE ', creatorCode: 'YT', shippingMethodId: 'std' });

  test('guest: items without meta, guest, codes, gift, shipping and payment; logged in: no items', () => {
    const items = [{ productId: 1, quantity: 2, meta: { name: 'x' } }];
    const guest = { username: 'Steve', email: 'a@b.co' };

    const body = buildQuoteBody({
      loggedIn: false,
      items,
      guest,
      currency: 'USD',
      locale: 'en-US',
      draft: { ...draft, paymentMethodId: 'stripe' },
      recipientUsername: 'Alex',
      shippingAddress: { country: 'TR' },
    });

    expect(body).toEqual({
      items: [{ productId: 1, quantity: 2 }],
      guest,
      currency: 'USD',
      couponCode: 'SAVE',
      creatorCode: 'YT',
      recipientUsername: 'Alex',
      shippingAddress: { country: 'TR' },
      shippingMethodId: 'std',
      paymentMethodId: 'stripe',
      locale: 'en-US',
    });

    const logged = buildQuoteBody({
      loggedIn: true,
      items,
      guest,
      locale: 'tr',
      draft: defaultDraft(),
    });
    expect('items' in logged).toBe(false);
    expect('guest' in logged).toBe(false);
    // a signed-in buyer says "no code" explicitly: the server would otherwise fall back to the codes stored on the cart
    expect(logged).toEqual({ locale: 'tr', couponCode: '', creatorCode: '' });
  });

  test('a signed-in buyer who removed a code sends an empty code, a guest sends none', () => {
    const removed = draftWith({ couponCode: '', creatorCode: 'YT' });

    expect(buildQuoteBody({ loggedIn: true, draft: removed })).toEqual({
      couponCode: '',
      creatorCode: 'YT',
    });
    expect(buildQuoteBody({ loggedIn: false, items: [], draft: removed })).toEqual({
      items: [],
      creatorCode: 'YT',
    });
    expect(buildQuoteBody({ topup: 5, loggedIn: true, draft: removed })).toEqual({
      creditTopUp: 5,
    });
  });

  test('a saved address id wins over the typed address; gift message needs a recipient', () => {
    const body = buildQuoteBody({
      loggedIn: true,
      draft: draftWith({ shippingAddressId: 9, giftMessage: 'hi' }),
      shippingAddress: { country: 'TR' },
    });

    expect(body.shippingAddressId).toBe(9);
    expect('shippingAddress' in body).toBe(false);
    expect('giftMessage' in body).toBe(false);

    expect(
      buildQuoteBody({
        loggedIn: true,
        draft: draftWith({ giftMessage: 'hi' }),
        recipientUsername: 'Alex',
      }).giftMessage,
    ).toBe('hi');
  });

  test('credits: payWithCredits drops the payment method; useCredits keeps numbers and MAX', () => {
    const pay = buildQuoteBody({
      loggedIn: true,
      draft: draftWith({ payWithCredits: true, paymentMethodId: 'stripe' }),
    });
    expect(pay.payWithCredits).toBe(true);
    expect('paymentMethodId' in pay).toBe(false);

    expect(
      buildQuoteBody({ loggedIn: true, draft: draftWith({ useCredits: 'MAX' }) }).useCredits,
    ).toBe('MAX');
    expect(buildQuoteBody({ loggedIn: true, draft: draftWith({ useCredits: 0 }) }).useCredits).toBe(
      0,
    );
    expect('useCredits' in buildQuoteBody({ loggedIn: true, draft: draftWith() })).toBe(false);
  });

  test('top-up mode: creditTopUp, no items, codes or shipping', () => {
    const body = buildQuoteBody({
      topup: 12.5,
      loggedIn: true,
      items: [{ productId: 1 }],
      currency: 'EUR',
      locale: 'ru',
      draft: draftWith({ couponCode: 'X', paymentMethodId: 'stripe', shippingMethodId: 'a' }),
      billingInfo: { type: 'INDIVIDUAL' },
    });

    expect(body).toEqual({
      creditTopUp: 12.5,
      paymentMethodId: 'stripe',
      billingInfo: { type: 'INDIVIDUAL' },
      currency: 'EUR',
      locale: 'ru',
    });
  });

  test('canonicalBody is key-order independent and quoteDelay debounces typing of the address longer', () => {
    expect(canonicalBody({ b: 1, a: { d: [1, { y: 1, x: 2 }], c: undefined } })).toBe(
      canonicalBody({ a: { c: undefined, d: [1, { x: 2, y: 1 }] }, b: 1 }),
    );
    expect(quoteDelay({}, { couponCode: 'A' })).toBe(300);
    expect(
      quoteDelay({ shippingAddress: { city: 'A' } }, { shippingAddress: { city: 'Ab' } }),
    ).toBe(500);
    expect(quoteDelay({ shippingAddress: { city: 'A' } }, { shippingAddress: { city: 'A' } })).toBe(
      300,
    );
  });
});

describe('applyQuoteSelections', () => {
  const opts = (...ids) => ids.map((methodId) => ({ methodId }));

  test('shipping: exactly one option is preselected', () => {
    expect(
      applyQuoteSelections(defaultDraft(), { requiresShipping: true, shippingOptions: opts('a') }),
    ).toEqual({ shippingMethodId: 'a' });
  });

  test('shipping: the server preselection is adopted when several are offered', () => {
    expect(
      applyQuoteSelections(defaultDraft(), {
        requiresShipping: true,
        shippingOptions: opts('a', 'b'),
        shippingMethodId: 'b',
      }),
    ).toEqual({ shippingMethodId: 'b' });
    expect(
      applyQuoteSelections(defaultDraft(), {
        requiresShipping: true,
        shippingOptions: opts('a', 'b'),
      }),
    ).toEqual({});
  });

  test('shipping: a selected id that is no longer offered is cleared, a valid one is kept', () => {
    const draft = draftWith({ shippingMethodId: 'gone' });

    expect(
      applyQuoteSelections(draft, { requiresShipping: true, shippingOptions: opts('a', 'b') }),
    ).toEqual({ shippingMethodId: null });
    expect(
      applyQuoteSelections(draftWith({ shippingMethodId: 'b' }), {
        requiresShipping: true,
        shippingOptions: opts('a', 'b'),
        shippingMethodId: 'a',
      }),
    ).toEqual({});
    expect(applyQuoteSelections(draft, { requiresShipping: true, shippingOptions: [] })).toEqual({
      shippingMethodId: null,
    });
    expect(applyQuoteSelections(draft, { requiresShipping: false, shippingOptions: [] })).toEqual({
      shippingMethodId: null,
    });
  });

  test('payment: keep the available choice, else the single available method, else none', () => {
    const methods = [
      { id: 'a', available: true },
      { id: 'b', available: false },
    ];

    expect(
      applyQuoteSelections(draftWith({ paymentMethodId: 'a' }), {
        gatewayAmount: 5,
        paymentMethods: methods,
      }),
    ).toEqual({});
    expect(
      applyQuoteSelections(draftWith({ paymentMethodId: 'b' }), {
        gatewayAmount: 5,
        paymentMethods: methods,
      }),
    ).toEqual({ paymentMethodId: 'a' });
    expect(
      applyQuoteSelections(defaultDraft(), {
        gatewayAmount: 5,
        paymentMethods: [
          { id: 'a', available: true },
          { id: 'c', available: true },
        ],
      }),
    ).toEqual({});
    expect(
      applyQuoteSelections(draftWith({ paymentMethodId: 'x' }), {
        gatewayAmount: 5,
        paymentMethods: [
          { id: 'a', available: true },
          { id: 'c', available: true },
        ],
      }),
    ).toEqual({ paymentMethodId: null });
  });

  test('payment: untouched when paying with credits or when nothing is left to pay', () => {
    const methods = [{ id: 'a', available: true }];

    expect(
      applyQuoteSelections(draftWith({ payWithCredits: true }), {
        gatewayAmount: 5,
        paymentMethods: methods,
      }),
    ).toEqual({});
    expect(
      applyQuoteSelections(defaultDraft(), { gatewayAmount: 0, paymentMethods: methods }),
    ).toEqual({});
    expect(applyQuoteSelections(defaultDraft(), null)).toEqual({});
  });
});

describe('server cart codes and persistence', () => {
  test('mergeServerCodes: the server cart wins over the draft', () => {
    const draft = draftWith({ couponCode: 'OLD', creatorCode: 'OLDC', isGift: false });
    const merged = mergeServerCodes(draft, {
      couponCode: 'NEW',
      creatorCode: null,
      recipientUsername: 'Alex',
      giftMessage: 'hi',
    });

    expect(merged.couponCode).toBe('NEW');
    expect(merged.creatorCode).toBe('');
    expect(merged.isGift).toBe(true);
    expect(merged.recipientUsername).toBe('Alex');
    expect(merged.giftMessage).toBe('hi');
    expect(mergeServerCodes(draft, null)).toBe(draft);
  });

  test('mergeServerCodes keeps a draft gift when the server cart has no recipient', () => {
    const draft = draftWith({ isGift: true, recipientUsername: 'Alex' });

    expect(mergeServerCodes(draft, { couponCode: null }).recipientUsername).toBe('Alex');
  });

  test('persistPatch lists only what differs from the server cart', () => {
    const held = { couponCode: 'A', creatorCode: null, recipientUsername: null };

    expect(
      persistPatch(held, {
        couponCode: 'A',
        creatorCode: 'YT',
        recipientUsername: null,
        giftMessage: null,
      }),
    ).toEqual({ creatorCode: 'YT' });
    expect(persistPatch(held, { couponCode: null })).toEqual({ couponCode: null });
    expect(persistPatch(held, { couponCode: 'A' })).toEqual({});
  });
});

describe('sticky carrier extras (no quote loop)', () => {
  const invalid = (fields) => ({
    messages: [{ code: 'SHIPPING_ADDRESS_INVALID', level: 'error', fields }],
  });
  const required = { messages: [{ code: 'SHIPPING_ADDRESS_REQUIRED', level: 'error' }] };
  const draft = (country = 'DE', method = 5) => ({
    shippingAddress: { country },
    shippingMethodId: method,
  });

  test('the extras are kept by a quote without the message while country and method are unchanged', () => {
    const first = nextCarrierExtras(NO_CARRIER_EXTRAS, invalid(['district']), draft());
    expect(first).toEqual({ key: 'DE|5', fields: ['district'] });

    const second = nextCarrierExtras(first, required, draft());
    expect(second).toBe(first);
    expect(carrierExtrasFor(second, draft())).toEqual(['district']);

    // a second message adds to the set
    expect(nextCarrierExtras(first, invalid(['neighborhood']), draft()).fields).toEqual([
      'district',
      'neighborhood',
    ]);
  });

  test('a new country or shipping method drops them', () => {
    const held = nextCarrierExtras(NO_CARRIER_EXTRAS, invalid(['district']), draft());

    expect(carrierExtrasFor(held, draft('TR'))).toEqual([]);
    expect(carrierExtrasFor(held, draft('DE', 6))).toEqual([]);
    expect(carrierExtrasFor(held, draft('de'))).toEqual(['district']);
    expect(nextCarrierExtras(held, required, draft('TR'))).toBe(NO_CARRIER_EXTRAS);
    expect(nextCarrierExtras(held, invalid(['district']), draft('TR'))).toEqual({
      key: 'TR|5',
      fields: ['district'],
    });
  });

  test('quote sequence: the address is not sent and withheld in turn', () => {
    const cfg = config({
      shippingCountries: ['DE'],
      addressFields: { '*': ['firstName', 'lastName', 'country', 'city', 'line1'] },
    });
    const d = draft('DE', 5);
    const address = trAddress({ country: 'DE', district: '', phone: '' });

    // the server of the carrier needs a district; without an address it asks for the address
    const server = (body) =>
      !body.shippingAddress
        ? required
        : body.shippingAddress.district
          ? { messages: [] }
          : invalid(['district']);

    let extras = NO_CARRIER_EXTRAS;
    const bodies = [];

    for (let i = 0; i < 8; i++) {
      const check = checkShippingAddress(cfg, address, carrierExtrasFor(extras, d));
      const body = { shippingAddress: check.ok ? address : null };
      bodies.push(canonicalBody(body));
      extras = nextCarrierExtras(extras, server(body), d);
    }

    // one probe with the address, one without, then the request never changes again
    expect(bodies.slice(2).every((b) => b === bodies[1])).toBe(true);
    expect(new Set(bodies).size).toBe(2);
    expect(carrierExtrasFor(extras, d)).toEqual(['district']);

    // the buyer fills the field: the address is sent again and the extras stay required
    const filled = { ...address, district: 'Mitte' };
    expect(checkShippingAddress(cfg, filled, carrierExtrasFor(extras, d)).ok).toBe(true);
    extras = nextCarrierExtras(extras, server({ shippingAddress: filled }), d);
    expect(carrierExtrasFor(extras, d)).toEqual(['district']);
  });

  test('validateCheckout requires the sticky extras', () => {
    const cfg = config({
      addressFields: { '*': ['firstName', 'lastName', 'country', 'city', 'line1'] },
    });
    const base = draftWith({
      shippingAddress: trAddress({ country: 'DE', district: '' }),
    });
    const run = (carrierExtras) =>
      validateCheckout({
        config: cfg,
        quote: { requiresShipping: true },
        draft: base,
        user: { username: 'Steve' },
        carrierExtras,
      });

    expect(run([]).valid).toBe(true);
    expect(run(['district']).errors.shipping).toEqual({ district: 'FIELD_REQUIRED' });
    expect(run(['district']).firstId).toBe('market-checkout-shipping-district');
  });
});

describe('billing with the shipping address copied (required fields it lacks)', () => {
  // the shipping set has no phone, the billing side asks for it
  const lean = { '*': ['firstName', 'lastName', 'country', 'city', 'line1'] };
  const shipping = trAddress({ country: 'DE', phone: '' });
  const user = { username: 'Steve' };

  const run = (billingInfoMode, requiredBuyerFields, info = {}, address = shipping) => {
    const draft = draftWith({
      shippingAddress: address,
      billingOpen: true,
      billingSameAsShipping: true,
      billingInfo: { ...defaultDraft().billingInfo, ...info },
    });

    return validateCheckout({
      config: config({ billingInfoMode, addressFields: lean }),
      quote: { requiresShipping: true, requiredBuyerFields },
      draft,
      user,
    });
  };

  test('mode OFF, PHONE named: the phone input is shown and required', () => {
    const r = run('OFF', ['PHONE']);

    expect(r.billing.copy).toBe(true);
    expect(r.billing.showAddress).toBe(false);
    expect(r.billing.copyFields).toEqual(['phone']);
    expect(r.valid).toBe(false);
    expect(r.errors.billing).toEqual({ phone: 'FIELD_REQUIRED' });
    expect(r.firstId).toBe('market-checkout-billing-phone');
  });

  test('mode OPTIONAL forced open by BILLING_ADDRESS + PHONE', () => {
    const r = run('OPTIONAL', ['BILLING_ADDRESS', 'PHONE']);

    expect(r.billing.copy).toBe(true);
    expect(r.billing.copyFields).toEqual(['phone']);
    expect(r.errors.billing).toEqual({ phone: 'FIELD_REQUIRED' });
    expect(r.firstId).toBe('market-checkout-billing-phone');
  });

  test('mode REQUIRED, PHONE named', () => {
    const r = run('REQUIRED', ['PHONE']);

    expect(r.billing.copyFields).toEqual(['phone']);
    expect(r.errors.billing).toEqual({ phone: 'FIELD_REQUIRED' });
  });

  test('typing the phone makes the billing valid and the body carries it', () => {
    const r = run('REQUIRED', ['PHONE'], { phone: '+4915112345678' });

    expect(r.errors.billing).toEqual({});
    expect(r.valid).toBe(true);

    const info = effectiveBillingInfo(
      r.billing,
      { phone: '+4915112345678', type: 'INDIVIDUAL' },
      shipping,
    );
    expect(info.phone).toBe('+4915112345678');
    expect(info.firstName).toBe('Ayse');
    expect(info.line1).toBe('Main St 1');

    // an invalid phone is an error on the same input
    expect(run('REQUIRED', ['PHONE'], { phone: '0555' }).errors.billing).toEqual({
      phone: 'FIELD_INVALID',
    });
  });

  test('a copied address that already has the field needs no extra input', () => {
    const r = run('OFF', ['PHONE'], {}, trAddress({ country: 'DE', phone: '+4915112345678' }));

    expect(r.billing.copyFields).toEqual([]);
    expect(r.valid).toBe(true);
  });

  test('fields the shipping section enforces never show up while the address is being typed', () => {
    // firstName is in the shipping set: an incomplete address is the shipping section's error, not billing's
    const r = run(
      'REQUIRED',
      ['FIRST_NAME'],
      {},
      trAddress({ country: 'DE', firstName: '', phone: '' }),
    );

    expect(r.billing.copyFields).toEqual([]);
    expect(r.errors.shipping.firstName).toBe('FIELD_REQUIRED');
  });

  test('a saved shipping address is checked against every required billing field', () => {
    const draft = draftWith({
      shippingAddressId: 9,
      billingOpen: true,
      billingInfo: { ...defaultDraft().billingInfo },
    });
    const r = validateCheckout({
      config: config({ billingInfoMode: 'REQUIRED', addressFields: lean }),
      quote: { requiresShipping: true, requiredBuyerFields: ['PHONE'] },
      draft,
      user,
      saved: [{ id: 9, ...trAddress({ country: 'DE', phone: '', city: '' }) }],
    });

    expect(r.billing.copyFields).toEqual(['phone', 'city']);
    expect(r.errors.billing).toEqual({ phone: 'FIELD_REQUIRED', city: 'FIELD_REQUIRED' });
  });

  test('the shipping carrier extras count as enforced by the shipping section', () => {
    const r = validateCheckout({
      config: config({ billingInfoMode: 'REQUIRED', addressFields: lean }),
      quote: { requiresShipping: true, requiredBuyerFields: ['BILLING_ADDRESS'] },
      draft: draftWith({
        shippingAddress: trAddress({ country: 'DE', phone: '', district: '' }),
        billingOpen: true,
      }),
      user,
      carrierExtras: ['district'],
    });

    expect(r.billing.copyFields).toEqual([]);
  });
});
