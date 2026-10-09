import { describe, expect, test } from 'bun:test';
import {
  ADMIN_COMMANDS,
  FIELDS,
  MAIL_KINDS,
  OWNED_KEYS,
  SECTION_KEYS,
  buildSettingsBody,
  fieldErrorKey,
  normalizeFieldErrors,
  seedValues,
  validateValue,
} from './settings.js';
import {
  CHARGEBACK_VARIABLES,
  DELIVERY_PLAIN_KEYS,
  MAX_CHARGEBACK_ACTIONS,
  SERVICE_MAIL_KINDS,
  activeCreditKeys,
  activeModuleKeys,
  banPreset,
  canonicalList,
  chargebackPair,
  deliveryValid,
  isServiceMailKind,
  mailAlert,
  mailKindEnabled,
  normalizeCredits,
  parseChargebackActions,
  priceMismatchCount,
  rowErrors,
  serializeChargebackActions,
  setMailKindEnabled,
  showRefundSplitWarning,
  testMailRequest,
  toggleListValue,
  validateChargebackActions,
  validateCredits,
  validateDelivery,
  validateMail,
  validateModules,
  validateSecurity,
  withChargeback,
} from './settings-extra.js';
import { ACTION_VARIABLES } from '../components/action-variables.js';
import { newAction } from './actions.js';

// The keys of 00 §12 that the sections of this file own (credits, delivery, modules, security, mail).
const SPEC_KEYS = {
  credits: [
    'creditsEnabled',
    'creditName',
    'cashbackPercent',
    'onlyAcceptCredits',
    'creditValue',
    'allowMixedCreditPayment',
    'creditTopUpEnabled',
    'creditTopUpFreeAmount',
    'creditTopUpMin',
    'creditTopUpMax',
  ],
  delivery: [
    'revokeOnRefund',
    'revokeOnChargeback',
    'deliveryMaxAttempts',
    'deliveryOnlineWaitDays',
    'deliveryAckTimeoutSeconds',
    'subscriptionGraceDays',
    'subscriptionReminderDays',
    'autoBlockOnChargeback',
    'revokeCreditOrdersOnTopUpChargeback',
    'creatorEarningHoldDays',
    'chargebackActions',
  ],
  modules: [
    'moduleRecentBuyers',
    'moduleRecentBuyersCount',
    'moduleRecentBuyersShowAmount',
    'moduleTopSupporters',
    'moduleTopSupportersPeriod',
    'moduleTopSupportersCount',
    'moduleGoal',
    'moduleSaleBadges',
    'moduleSaleCountdown',
    'moduleStats',
    'moduleSidebars',
  ],
  security: [
    'checkoutRateLimitPerMinute',
    'quoteRateLimitPerMinute',
    'couponLockThreshold',
    'couponLockMinutes',
  ],
  mail: [
    'sendEmailAfterPurchase',
    'mailDisabledKinds',
    'mailAttachInvoice',
    'mailReplyTo',
    'mailOrderDeliveredDelayMinutes',
  ],
};

const defaults = (section) => seedValues({}, SECTION_KEYS[section]);

describe('field table of the credits / delivery / modules / security / mail sections', () => {
  test('every key of 00 §12 for these sections is owned by exactly its section', () => {
    for (const [section, keys] of Object.entries(SPEC_KEYS)) {
      expect([...SECTION_KEYS[section]].sort()).toEqual([...keys].sort());
      for (const key of keys) {
        expect(OWNED_KEYS).toContain(key);
        expect(FIELDS[key]).toBeDefined();
      }
    }
    expect(new Set(OWNED_KEYS).size).toBe(OWNED_KEYS.length);
  });

  test('the documented defaults of 00 §12', () => {
    expect(FIELDS.creditValue.def).toBe(1);
    expect(FIELDS.allowMixedCreditPayment.def).toBe(false);
    expect(FIELDS.creditTopUpMin.def).toBe(1);
    expect(FIELDS.creditTopUpMax.def).toBe(10000);
    expect(FIELDS.deliveryMaxAttempts.def).toBe(5);
    expect(FIELDS.deliveryOnlineWaitDays.def).toBe(0);
    expect(FIELDS.deliveryAckTimeoutSeconds.def).toBe(30);
    expect(FIELDS.subscriptionGraceDays.def).toBe(3);
    expect(FIELDS.creatorEarningHoldDays.def).toBe(14);
    expect(FIELDS.chargebackActions.def).toBe('[]');
    expect(FIELDS.moduleRecentBuyersCount.def).toBe(10);
    expect(FIELDS.moduleTopSupportersCount.def).toBe(5);
    expect(FIELDS.moduleStats.def).toBe(false);
    expect(FIELDS.moduleSidebars.def).toEqual(['home']);
    expect(FIELDS.checkoutRateLimitPerMinute.def).toBe(6);
    expect(FIELDS.quoteRateLimitPerMinute.def).toBe(60);
    expect(FIELDS.couponLockThreshold.def).toBe(5);
    expect(FIELDS.couponLockMinutes.def).toBe(15);
    expect(FIELDS.mailOrderDeliveredDelayMinutes.def).toBe(10);
    expect(FIELDS.mailAttachInvoice.def).toBe(true);
  });

  test('each default passes its own validation and an untouched section sends nothing', () => {
    for (const keys of Object.values(SPEC_KEYS))
      for (const key of keys)
        expect([key, validateValue(key, FIELDS[key].def)]).toEqual([key, null]);
    for (const section of Object.keys(SPEC_KEYS)) {
      const values = defaults(section);
      expect(buildSettingsBody({}, values, SECTION_KEYS[section])).toEqual({});
    }
  });

  test('the ranges of 13 §17 (stricter than the backend where they differ)', () => {
    expect(validateValue('deliveryMaxAttempts', 20)).toBeNull();
    expect(validateValue('deliveryMaxAttempts', 21)).toBe('OUT_OF_RANGE');
    expect(validateValue('deliveryMaxAttempts', 0)).toBe('OUT_OF_RANGE');
    expect(validateValue('deliveryAckTimeoutSeconds', 4)).toBe('OUT_OF_RANGE');
    expect(validateValue('deliveryAckTimeoutSeconds', 600)).toBeNull();
    expect(validateValue('deliveryAckTimeoutSeconds', 601)).toBe('OUT_OF_RANGE');
    expect(validateValue('creatorEarningHoldDays', 90)).toBeNull();
    expect(validateValue('creatorEarningHoldDays', 91)).toBe('OUT_OF_RANGE');
    expect(validateValue('checkoutRateLimitPerMinute', 0)).toBeNull();
    expect(validateValue('checkoutRateLimitPerMinute', 100001)).toBe('OUT_OF_RANGE');
    expect(validateValue('quoteRateLimitPerMinute', 0)).toBe('OUT_OF_RANGE');
    expect(validateValue('couponLockThreshold', 100)).toBeNull();
    expect(validateValue('couponLockThreshold', 101)).toBe('OUT_OF_RANGE');
    expect(validateValue('couponLockMinutes', 1440)).toBeNull();
    expect(validateValue('couponLockMinutes', 1441)).toBe('OUT_OF_RANGE');
    expect(validateValue('moduleRecentBuyersCount', 51)).toBe('OUT_OF_RANGE');
    expect(validateValue('mailOrderDeliveredDelayMinutes', 1440)).toBeNull();
    expect(validateValue('mailOrderDeliveredDelayMinutes', -1)).toBe('OUT_OF_RANGE');
    expect(validateValue('deliveryOnlineWaitDays', 1.5)).toBe('INVALID_TYPE');
  });

  test('list fields: duplicates and values outside the allowed set are invalid', () => {
    expect(validateValue('moduleSidebars', [])).toBeNull();
    expect(validateValue('moduleSidebars', ['home', 'profile'])).toBeNull();
    expect(validateValue('moduleSidebars', ['home', 'home'])).toBe('INVALID_VALUE');
    expect(validateValue('moduleSidebars', ['footer'])).toBe('INVALID_VALUE');
    expect(validateValue('mcDisabledAdminCommands', ['purchases'])).toBeNull();
    expect(validateValue('mcDisabledAdminCommands', ['rm-rf'])).toBe('INVALID_VALUE');
    expect(validateValue('mailDisabledKinds', ['ANYTHING'])).toBeNull();
  });

  test('the new server codes have a text key', () => {
    expect(fieldErrorKey('REQUIRES_TOP_UP')).toBe('settings.field-error.REQUIRES_TOP_UP');
    expect(fieldErrorKey('REQUIRES_CREDITS')).toBe('settings.field-error.REQUIRES_CREDITS');
    expect(normalizeFieldErrors({ creditTopUpFreeAmount: 'REQUIRES_TOP_UP' })).toEqual({
      creditTopUpFreeAmount: 'REQUIRES_TOP_UP',
    });
  });
});

describe('canonical lists', () => {
  test('known items first in the given order, unknown stored items kept', () => {
    expect(canonicalList(['b', 'z', 'a'], ['a', 'b', 'c'])).toEqual(['a', 'b', 'z']);
    expect(canonicalList(null, ['a'])).toEqual([]);
  });

  test('toggling keeps the order so the dirty check does not flicker', () => {
    expect(toggleListValue(['profile'], 'home', true, ['home', 'profile'])).toEqual([
      'home',
      'profile',
    ]);
    expect(toggleListValue(['home', 'profile'], 'home', false, ['home', 'profile'])).toEqual([
      'profile',
    ]);
    expect(toggleListValue(['home'], 'home', true, ['home', 'profile'])).toEqual(['home']);
  });
});

describe('credits', () => {
  const base = () => ({ ...defaults('credits') });

  test('the defaults are valid', () => {
    expect(validateCredits(base())).toEqual({});
  });

  test('credit value must be at least 0.01', () => {
    expect(validateCredits({ ...base(), creditValue: 0 }).creditValue).toBe('OUT_OF_RANGE');
    expect(validateCredits({ ...base(), creditValue: '' }).creditValue).toBe('REQUIRED');
    expect(validateCredits({ ...base(), creditValue: 'x' }).creditValue).toBe('INVALID_TYPE');
    expect(validateCredits({ ...base(), creditValue: 0.01 })).toEqual({});
    expect(validateCredits({ ...base(), creditValue: '2.5' })).toEqual({});
  });

  test('credit name is at most 32 characters, cashback 0-100', () => {
    expect(validateCredits({ ...base(), creditName: 'x'.repeat(33) }).creditName).toBe('TOO_LONG');
    expect(validateCredits({ ...base(), creditName: 'x'.repeat(32) })).toEqual({});
    expect(validateCredits({ ...base(), cashbackPercent: 101 }).cashbackPercent).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateCredits({ ...base(), cashbackPercent: -1 }).cashbackPercent).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateCredits({ ...base(), cashbackPercent: 100 })).toEqual({});
  });

  test('the top-up amounts count only while top-up is on, and min may not exceed max', () => {
    const off = { ...base(), creditTopUpMin: 'garbage', creditTopUpMax: 0 };
    expect(validateCredits(off)).toEqual({});
    expect(activeCreditKeys(off)).not.toContain('creditTopUpMin');
    expect(activeCreditKeys(off)).not.toContain('creditTopUpMax');
    const on = { ...base(), creditTopUpEnabled: true };
    expect(activeCreditKeys(on)).toEqual(SECTION_KEYS.credits);
    expect(validateCredits({ ...on, creditTopUpMin: 5, creditTopUpMax: 4 }).creditTopUpMax).toBe(
      'MIN_ABOVE_MAX',
    );
    expect(validateCredits({ ...on, creditTopUpMin: 5, creditTopUpMax: 5 })).toEqual({});
    expect(validateCredits({ ...on, creditTopUpMin: 0, creditTopUpMax: 5 }).creditTopUpMin).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateCredits({ ...on, creditTopUpMax: '' }).creditTopUpMax).toBe('REQUIRED');
  });

  test('the dependent switches follow their parent (REQUIRES_CREDITS / REQUIRES_TOP_UP never sent)', () => {
    const values = {
      ...base(),
      creditsEnabled: false,
      onlyAcceptCredits: true,
      creditTopUpEnabled: false,
      creditTopUpFreeAmount: true,
    };
    const normalized = normalizeCredits(values);
    expect(normalized.onlyAcceptCredits).toBe(false);
    expect(normalized.creditTopUpFreeAmount).toBe(false);
    expect(values.onlyAcceptCredits).toBe(true);
    const kept = normalizeCredits({
      ...base(),
      onlyAcceptCredits: true,
      creditTopUpEnabled: true,
      creditTopUpFreeAmount: true,
    });
    expect(kept.onlyAcceptCredits).toBe(true);
    expect(kept.creditTopUpFreeAmount).toBe(true);
  });

  test('the body holds only the changed credit keys, numbers as Number', () => {
    const baseline = { creditValue: 1, creditName: 'Coin', allowMixedCreditPayment: false };
    const values = seedValues(baseline, SECTION_KEYS.credits);
    values.creditValue = '2.5';
    values.allowMixedCreditPayment = true;
    const body = buildSettingsBody(baseline, values, activeCreditKeys(values));
    expect(body).toEqual({ creditValue: 2.5, allowMixedCreditPayment: true });
  });

  test('mixed payment shows the refunds-get-split warning (owner decision, 07 §6.5)', () => {
    expect(showRefundSplitWarning({ allowMixedCreditPayment: true })).toBe(true);
    expect(showRefundSplitWarning({ allowMixedCreditPayment: false })).toBe(false);
    expect(showRefundSplitWarning(undefined)).toBe(false);
  });

  test('the price mismatch notice needs mixed payment and a positive count', () => {
    const on = { allowMixedCreditPayment: true };
    expect(priceMismatchCount({ creditPriceMismatchCount: 3 }, on)).toBe(3);
    expect(priceMismatchCount({ creditPriceMismatchCount: 0 }, on)).toBe(0);
    expect(
      priceMismatchCount({ creditPriceMismatchCount: 3 }, { allowMixedCreditPayment: false }),
    ).toBe(0);
    expect(priceMismatchCount({}, on)).toBe(0);
    expect(priceMismatchCount({ creditPriceMismatchCount: 'x' }, on)).toBe(0);
  });
});

describe('chargeback actions', () => {
  const command = (overrides = {}) => ({
    ...newAction('COMMAND'),
    value: ['ban {username} Chargeback'],
    serverMode: 'ALL_CONNECTED',
    ...overrides,
  });

  test('an empty, blank or missing value is an empty list', () => {
    for (const text of ['[]', '', '   ', undefined, null])
      expect(parseChargebackActions(text)).toEqual({ actions: [], invalid: false });
  });

  test('an unparsable stored value is an empty list with the invalid note', () => {
    for (const text of ['{', 'nope', '{"a":1}', '"x"', '12'])
      expect(parseChargebackActions(text)).toEqual({ actions: [], invalid: true });
  });

  test('a stored list round-trips with its ids and phase GRANT', () => {
    const stored = JSON.stringify([
      { id: 'cb1', type: 'COMMAND', value: ['ban {username} x'], serverMode: 'ALL_CONNECTED' },
      {
        type: 'WEBHOOK',
        value: { url: 'https://example.com/hook', format: 'JSON', signing: 'NONE' },
      },
    ]);
    const { actions, invalid } = parseChargebackActions(stored);
    expect(invalid).toBe(false);
    expect(actions).toHaveLength(2);
    expect(actions.every((a) => a.phase === 'GRANT')).toBe(true);
    const wire = JSON.parse(serializeChargebackActions(actions));
    expect(wire[0].id).toBe('cb1');
    expect(wire[0].type).toBe('COMMAND');
    expect(wire[0].phase).toBe('GRANT');
    expect(wire[0].perUnit).toBe(false);
    expect(wire[1].id).toBeUndefined();
    expect(wire[1].value.url).toBe('https://example.com/hook');
    // no server targeting keys leak onto a webhook
    expect(wire[1].serverMode).toBeUndefined();
  });

  test('serialising nothing gives the empty array string', () => {
    expect(serializeChargebackActions([])).toBe('[]');
    expect(serializeChargebackActions(undefined)).toBe('[]');
  });

  test('the ban preset is a command on every connected server with the username variable', () => {
    const preset = banPreset();
    expect(preset.type).toBe('COMMAND');
    expect(preset.value).toEqual(['ban {username} Chargeback']);
    expect(preset.serverMode).toBe('ALL_CONNECTED');
    expect(preset.phase).toBe('GRANT');
    expect(validateChargebackActions([preset], [{ id: 1 }])).toEqual({});
  });

  test('a valid command and webhook pass', () => {
    const webhook = {
      ...newAction('WEBHOOK'),
      value: { url: 'https://example.com/hook', format: 'JSON', signing: 'NONE', secret: '' },
    };
    expect(validateChargebackActions([command(), webhook], [{ id: 1 }])).toEqual({});
  });

  test('only COMMAND and WEBHOOK are accepted (a CREDIT or PERMISSION row is refused)', () => {
    const credit = { ...newAction('CREDIT'), value: 5 };
    const permission = { ...newAction('PERMISSION'), value: ['a.b'] };
    expect(validateChargebackActions([credit], null)['actions.0.type']).toBe('INVALID');
    expect(validateChargebackActions([permission], null)['actions.0.type']).toBe('INVALID');
  });

  test('more than ten actions are refused', () => {
    const rows = Array.from({ length: MAX_CHARGEBACK_ACTIONS + 1 }, () => command());
    expect(validateChargebackActions(rows, null).actions).toBe('TOO_MANY');
    expect(validateChargebackActions(rows.slice(0, 10), null).actions).toBeUndefined();
  });

  test('BUYER_CHOICE and per-unit delivery are refused', () => {
    expect(
      validateChargebackActions([command({ serverMode: 'BUYER_CHOICE' })], [{ id: 1 }])[
        'actions.0.serverMode'
      ],
    ).toBe('SERVER_CHOICES_REQUIRED');
    expect(
      validateChargebackActions([command({ perUnit: true })], [{ id: 1 }])['actions.0.perUnit'],
    ).toBe('PER_UNIT_NEEDS_MAX_QUANTITY');
  });

  test('a command without a target server and an unknown server are marked', () => {
    expect(
      validateChargebackActions([command({ serverMode: 'FIXED', targetServers: [] })], [{ id: 1 }])[
        'actions.0.targetServers'
      ],
    ).toBe('SERVER_REQUIRED');
    expect(
      validateChargebackActions(
        [command({ serverMode: 'FIXED', targetServers: [9] })],
        [{ id: 1 }],
      )['actions.0.targetServers'],
    ).toBe('UNKNOWN_SERVER');
  });

  test('an empty command row is invalid; the leading slash is tolerated', () => {
    expect(validateChargebackActions([command({ value: [''] })], null)['actions.0.value.0']).toBe(
      'INVALID_VALUE',
    );
    expect(validateChargebackActions([command({ value: ['/ban {username}'] })], null)).toEqual({});
  });

  test('a phase other than GRANT is refused by the editor rules', () => {
    expect(validateChargebackActions([command({ phase: 'REVOKE' })], null)['actions.0.phase']).toBe(
      'INVALID_PHASE',
    );
  });

  test('rowErrors strips the row prefix for the action editor', () => {
    const errors = {
      'actions.0.value.0': 'INVALID_VALUE',
      'actions.1.delay': 'OUT_OF_RANGE',
      actions: 'TOO_MANY',
    };
    expect(rowErrors(errors, 0)).toEqual({ 'value.0': 'INVALID_VALUE' });
    expect(rowErrors(errors, 1)).toEqual({ delay: 'OUT_OF_RANGE' });
    expect(rowErrors(errors, 2)).toEqual({});
    expect(rowErrors(undefined, 0)).toEqual({});
  });

  test('a stored string that differs only by formatting is not dirty', () => {
    const stored =
      '[ { "type" : "COMMAND", "value": ["ban {username} Chargeback"], "serverMode": "ALL_CONNECTED" } ]';
    const settings = { chargebackActions: stored };
    const { actions } = parseChargebackActions(stored);
    const pair = chargebackPair(settings, actions);
    expect(pair.baseline).toBe(pair.value);
    const merged = withChargeback(settings, defaults('delivery'), actions);
    const body = buildSettingsBody(merged.baseline, merged.values, SECTION_KEYS.delivery);
    expect(body).toEqual({});
  });

  test('adding or removing an action makes the body carry the new string', () => {
    const settings = { chargebackActions: '[]' };
    const merged = withChargeback(settings, defaults('delivery'), [banPreset()]);
    const body = buildSettingsBody(merged.baseline, merged.values, SECTION_KEYS.delivery);
    expect(Object.keys(body)).toEqual(['chargebackActions']);
    const sent = JSON.parse(body.chargebackActions);
    expect(sent).toHaveLength(1);
    expect(sent[0].value).toEqual(['ban {username} Chargeback']);
    expect(sent[0].phase).toBe('GRANT');
  });

  test('an unparsable stored value stays raw, so saving the empty list replaces it', () => {
    const settings = { chargebackActions: 'not json' };
    const merged = withChargeback(settings, defaults('delivery'), []);
    expect(merged.baseline.chargebackActions).toBe('not json');
    const body = buildSettingsBody(merged.baseline, merged.values, SECTION_KEYS.delivery);
    expect(body).toEqual({ chargebackActions: '[]' });
  });

  test('chargeback variables are known action variables', () => {
    for (const name of CHARGEBACK_VARIABLES) expect(ACTION_VARIABLES).toContain(name);
    expect(CHARGEBACK_VARIABLES).not.toContain('gift.message');
  });
});

describe('delivery', () => {
  test('the plain keys exclude the action string', () => {
    expect(DELIVERY_PLAIN_KEYS).not.toContain('chargebackActions');
    expect(DELIVERY_PLAIN_KEYS).toHaveLength(SECTION_KEYS.delivery.length - 1);
  });

  test('defaults are valid', () => {
    const result = validateDelivery(defaults('delivery'), [], null);
    expect(deliveryValid(result)).toBe(true);
  });

  test('field errors and action errors are reported separately', () => {
    const values = { ...defaults('delivery'), deliveryMaxAttempts: 99, creatorEarningHoldDays: -1 };
    const bad = { ...newAction('COMMAND'), value: [''] };
    const result = validateDelivery(values, [bad], null);
    expect(result.errors.deliveryMaxAttempts).toBe('OUT_OF_RANGE');
    expect(result.errors.creatorEarningHoldDays).toBe('OUT_OF_RANGE');
    expect(result.actions['actions.0.value.0']).toBe('INVALID_VALUE');
    expect(deliveryValid(result)).toBe(false);
  });

  test('a serialised list longer than the 8000 character key limit is refused', () => {
    const long = banPreset();
    long.value = ['x'.repeat(500)];
    const rows = Array.from({ length: 10 }, () => ({ ...long, value: ['x'.repeat(900)] }));
    const result = validateDelivery(defaults('delivery'), rows, null);
    expect(result.errors.chargebackActions).toBe('TOO_LONG');
  });
});

describe('modules', () => {
  test('the dependent inputs count only while their switch is on', () => {
    const off = { ...defaults('modules'), moduleRecentBuyers: false, moduleTopSupporters: false };
    expect(activeModuleKeys(off)).not.toContain('moduleRecentBuyersCount');
    expect(activeModuleKeys(off)).not.toContain('moduleRecentBuyersShowAmount');
    expect(activeModuleKeys(off)).not.toContain('moduleTopSupportersCount');
    expect(activeModuleKeys(off)).not.toContain('moduleTopSupportersPeriod');
    expect(activeModuleKeys(off)).toContain('moduleGoal');
    expect(activeModuleKeys(off)).toContain('moduleSidebars');
    expect(
      activeModuleKeys({ ...off, moduleRecentBuyers: true, moduleTopSupporters: true }),
    ).toEqual(SECTION_KEYS.modules);
  });

  test('counts are 1-50, the period is MONTH or ALL_TIME, sidebars home or profile', () => {
    const values = defaults('modules');
    expect(validateModules(values)).toEqual({});
    expect(validateModules({ ...values, moduleRecentBuyersCount: 0 }).moduleRecentBuyersCount).toBe(
      'OUT_OF_RANGE',
    );
    expect(
      validateModules({ ...values, moduleTopSupportersCount: 51 }).moduleTopSupportersCount,
    ).toBe('OUT_OF_RANGE');
    expect(
      validateModules({ ...values, moduleTopSupportersPeriod: 'WEEK' }).moduleTopSupportersPeriod,
    ).toBe('INVALID_VALUE');
    expect(validateModules({ ...values, moduleTopSupportersPeriod: 'ALL_TIME' })).toEqual({});
    expect(validateModules({ ...values, moduleSidebars: ['x'] }).moduleSidebars).toBe(
      'INVALID_VALUE',
    );
    expect(validateModules({ ...values, moduleSidebars: [] })).toEqual({});
  });

  test('an invalid count behind a switched-off module is not an error', () => {
    const values = {
      ...defaults('modules'),
      moduleRecentBuyers: false,
      moduleRecentBuyersCount: 0,
    };
    expect(validateModules(values)).toEqual({});
  });
});

describe('security', () => {
  test('defaults are valid and ranges follow 13 §17', () => {
    const values = defaults('security');
    expect(validateSecurity(values)).toEqual({});
    expect(validateSecurity({ ...values, checkoutRateLimitPerMinute: 0 })).toEqual({});
    expect(
      validateSecurity({ ...values, quoteRateLimitPerMinute: 0 }).quoteRateLimitPerMinute,
    ).toBe('OUT_OF_RANGE');
    expect(validateSecurity({ ...values, couponLockMinutes: 0 }).couponLockMinutes).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateSecurity({ ...values, couponLockThreshold: '' }).couponLockThreshold).toBe(
      'REQUIRED',
    );
  });
});

describe('mail', () => {
  test('the thirteen kinds, the six service kinds among them', () => {
    expect(MAIL_KINDS).toHaveLength(13);
    for (const kind of SERVICE_MAIL_KINDS) expect(MAIL_KINDS).toContain(kind);
    expect(isServiceMailKind('SUBSCRIPTION_REMINDER')).toBe(true);
    expect(isServiceMailKind('BANK_TRANSFER_INSTRUCTIONS')).toBe(true);
    expect(isServiceMailKind('ORDER_CONFIRMATION')).toBe(false);
    expect(isServiceMailKind('GIFT_RECEIVED')).toBe(false);
  });

  test('a checkbox is checked unless its kind is in mailDisabledKinds', () => {
    expect(mailKindEnabled([], 'ORDER_CONFIRMATION')).toBe(true);
    expect(mailKindEnabled(['ORDER_CONFIRMATION'], 'ORDER_CONFIRMATION')).toBe(false);
    expect(mailKindEnabled(undefined, 'ORDER_CONFIRMATION')).toBe(true);
  });

  test('unchecking disables a kind, checking enables it; the order stays canonical', () => {
    let list = setMailKindEnabled([], 'SHIPMENT_SHIPPED', false);
    list = setMailKindEnabled(list, 'ORDER_RECEIVED', false);
    expect(list).toEqual(['ORDER_RECEIVED', 'SHIPMENT_SHIPPED']);
    expect(setMailKindEnabled(list, 'ORDER_RECEIVED', true)).toEqual(['SHIPMENT_SHIPPED']);
    expect(setMailKindEnabled(['LEGACY_KIND'], 'ORDER_RECEIVED', false)).toEqual([
      'ORDER_RECEIVED',
      'LEGACY_KIND',
    ]);
  });

  test('reply-to is an address or empty; the delay is 0-1440 minutes', () => {
    const values = defaults('mail');
    expect(validateMail(values)).toEqual({});
    expect(validateMail({ ...values, mailReplyTo: 'support@example.com' })).toEqual({});
    expect(validateMail({ ...values, mailReplyTo: 'support' }).mailReplyTo).toBe('INVALID_VALUE');
    expect(validateMail({ ...values, mailReplyTo: 'a b@example.com' }).mailReplyTo).toBe(
      'INVALID_VALUE',
    );
    expect(
      validateMail({ ...values, mailReplyTo: `${'a'.repeat(250)}@example.com` }).mailReplyTo,
    ).toBe('TOO_LONG');
    expect(
      validateMail({ ...values, mailOrderDeliveredDelayMinutes: 1441 })
        .mailOrderDeliveredDelayMinutes,
    ).toBe('OUT_OF_RANGE');
  });

  test('a disabled platform mail switch warns, a host that is too old is a danger and wins', () => {
    expect(mailAlert({ mailEnabled: true }, { mail: 'OK' })).toBeNull();
    expect(mailAlert({}, null)).toBeNull();
    expect(mailAlert({ mailEnabled: false }, null)).toBe('DISABLED');
    expect(mailAlert({ mailEnabled: true }, { mail: 'DISABLED', mailEnabled: false })).toBe(
      'DISABLED',
    );
    expect(mailAlert({ mailEnabled: false }, { mail: 'HOST_TOO_OLD' })).toBe('HOST_TOO_OLD');
    expect(mailAlert({ mailEnabled: true }, { mail: 'HOST_TOO_OLD' })).toBe('HOST_TOO_OLD');
  });

  test('the test mail body carries only what was filled in', () => {
    expect(testMailRequest({ kind: 'ORDER_CONFIRMATION', recipient: '' })).toEqual({
      errors: {},
      body: { kind: 'ORDER_CONFIRMATION' },
    });
    expect(testMailRequest({ kind: '', recipient: ' me@example.com ' })).toEqual({
      errors: {},
      body: { recipient: 'me@example.com' },
    });
    expect(testMailRequest({})).toEqual({ errors: {}, body: {} });
  });

  test('a bad kind or recipient blocks the test mail', () => {
    expect(testMailRequest({ kind: 'NOPE', recipient: '' }).errors.kind).toBe('INVALID_VALUE');
    expect(testMailRequest({ kind: '', recipient: 'nope' }).errors.recipient).toBe('INVALID_VALUE');
    expect(testMailRequest({ kind: '', recipient: 'nope' }).body).toBeNull();
  });

  test('admin command names match the backend list', () => {
    expect(ADMIN_COMMANDS).toEqual([
      'give-credits',
      'take-credits',
      'set-credits',
      'grant-product',
      'purchases',
    ]);
  });
});
