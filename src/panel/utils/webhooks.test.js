import { describe, expect, test } from 'bun:test';
import { SECRET_MASK } from './schema-form.js';
import {
  DELIVERY_TABS,
  WEBHOOK_EVENTS,
  blankForm,
  buildBody,
  canRedeliver,
  deliveriesPath,
  deliveryTab,
  endpointStatus,
  eventKey,
  eventsCount,
  formFromEndpoint,
  headerNameError,
  headerRows,
  headerValueError,
  isDiscordUrl,
  orderLabel,
  parseEndpointId,
  parseMaxAttempts,
  prettyText,
  secretError,
  serverFieldErrors,
  shortUrl,
  subscribableEvents,
  templateError,
  testOutcome,
  validateHeaderRows,
  validateUrl,
  withFormat,
  withSigning,
} from './webhooks.js';
import { isKnownStatus } from './status.js';

const DISCORD = 'https://discord.com/api/webhooks/123/abc';
const filled = (over = {}) => ({
  ...blankForm(),
  name: 'Discord',
  url: 'https://example.com/hook',
  ...over,
});

describe('webhook URL', () => {
  test('plain http(s) addresses pass, others fail', () => {
    expect(validateUrl('https://example.com/a', 'JSON')).toBeNull();
    expect(validateUrl('http://example.com', 'JSON')).toBeNull();
    expect(validateUrl('', 'JSON')).toBe('REQUIRED');
    expect(validateUrl('ftp://example.com', 'JSON')).toBe('INVALID_URL');
    expect(validateUrl('javascript:alert(1)', 'JSON')).toBe('INVALID_URL');
    expect(validateUrl('not a url', 'JSON')).toBe('INVALID_URL');
    expect(validateUrl('https://e.com/' + 'a'.repeat(1030), 'JSON')).toBe('TOO_LONG');
  });

  test('a Discord webhook must be a discord.com / discordapp.com /api/webhooks/ address', () => {
    expect(validateUrl(DISCORD, 'DISCORD')).toBeNull();
    expect(validateUrl('https://discordapp.com/api/webhooks/1/x', 'DISCORD')).toBeNull();
    expect(validateUrl('https://canary.discord.com/api/webhooks/1/x', 'DISCORD')).toBeNull();
    expect(validateUrl('https://example.com/hook', 'DISCORD')).toBe('INVALID_DISCORD_URL');
    expect(validateUrl('https://discord.com/other', 'DISCORD')).toBe('INVALID_DISCORD_URL');
    expect(validateUrl('https://evildiscord.com/api/webhooks/1/x', 'DISCORD')).toBe(
      'INVALID_DISCORD_URL',
    );
    expect(validateUrl('https://discord.com.evil.io/api/webhooks/1/x', 'DISCORD')).toBe(
      'INVALID_DISCORD_URL',
    );
    expect(isDiscordUrl('nonsense')).toBe(false);
    // The same address is fine for a JSON endpoint.
    expect(validateUrl('https://example.com/hook', 'JSON')).toBeNull();
  });

  test('shortUrl truncates at 48 characters', () => {
    expect(shortUrl('https://a.io')).toBe('https://a.io');
    const long = 'https://example.com/' + 'x'.repeat(60);
    expect(shortUrl(long).length).toBe(48);
    expect(shortUrl(long).endsWith('…')).toBe(true);
  });
});

describe('Discord format', () => {
  test('forces signing NONE and drops the secret', () => {
    const form = withFormat(filled({ signing: 'HMAC_SHA256', secret: 'a'.repeat(20) }), 'DISCORD', {
      discordTemplate: '{"embeds":[]}',
    });
    expect(form.signing).toBe('NONE');
    expect(form.secret).toBe('');
    expect(form.template).toBe('{"embeds":[]}');
    expect(withSigning(form, 'HMAC_SHA256').signing).toBe('NONE');
  });

  test('the request body of a Discord endpoint is never signed and has no secret', () => {
    const form = filled({
      url: DISCORD,
      format: 'DISCORD',
      signing: 'HMAC_SHA256',
      secret: 'a'.repeat(20),
    });
    const { body, errors } = buildBody(form);
    expect(errors).toBeUndefined();
    expect(body.signing).toBe('NONE');
    expect('secret' in body).toBe(false);
  });

  test('a non-Discord address is rejected for the Discord format', () => {
    const { errors } = buildBody(filled({ format: 'DISCORD' }));
    expect(errors.url).toBe('INVALID_DISCORD_URL');
  });

  test('leaving Discord switches the custom template off', () => {
    const form = withFormat(
      filled({ url: DISCORD, format: 'DISCORD', customTemplate: true, template: '{}' }),
      'JSON',
    );
    expect(form.customTemplate).toBe(false);
    expect(buildBody(form).body.template).toBeNull();
  });

  test('a stored Discord endpoint always opens with signing NONE', () => {
    const form = formFromEndpoint({ format: 'DISCORD', signing: 'HMAC_SHA256', secret: SECRET_MASK });
    expect(form.signing).toBe('NONE');
    expect(form.secret).toBe('');
  });

  test('the template only travels for Discord with the switch on, and must be JSON', () => {
    const on = filled({ url: DISCORD, format: 'DISCORD', customTemplate: true, template: '{"a":1}' });
    expect(buildBody(on).body.template).toBe('{"a":1}');
    const off = { ...on, customTemplate: false };
    expect(buildBody(off).body.template).toBeNull();
    expect(buildBody({ ...on, template: '{nope' }).errors.template).toBe('INVALID_JSON');
    expect(templateError('')).toBe('REQUIRED');
    expect(templateError('x'.repeat(8001))).toBe('TOO_LONG');
    expect(templateError('[]')).toBeNull();
  });
});

describe('headers (values masked on read)', () => {
  test('a stored endpoint opens with masked values and sends them back unchanged', () => {
    const endpoint = {
      name: 'n',
      url: 'https://example.com/hook',
      events: ['*'],
      format: 'JSON',
      signing: 'NONE',
      headers: { Authorization: SECRET_MASK, 'X-Team': SECRET_MASK },
      maxAttempts: 8,
      enabled: true,
    };
    const form = formFromEndpoint(endpoint);
    expect(form.headers).toEqual([
      { key: 'Authorization', value: SECRET_MASK },
      { key: 'X-Team', value: SECRET_MASK },
    ]);
    const { body } = buildBody(form, { isEdit: true });
    expect(body.headers).toEqual({ Authorization: SECRET_MASK, 'X-Team': SECRET_MASK });
  });

  test('a typed value replaces the mask of that header only', () => {
    const form = formFromEndpoint({
      name: 'n',
      url: 'https://example.com/h',
      events: ['*'],
      headers: { Authorization: SECRET_MASK, Other: SECRET_MASK },
    });
    form.headers[0] = { key: 'Authorization', value: 'Bearer abc' };
    expect(buildBody(form, { isEdit: true }).body.headers).toEqual({
      Authorization: 'Bearer abc',
      Other: SECRET_MASK,
    });
  });

  test('name rules', () => {
    expect(headerNameError('Authorization')).toBeNull();
    expect(headerNameError('X-Custom-1')).toBeNull();
    expect(headerNameError('bad name')).toBe('INVALID_HEADER_NAME');
    expect(headerNameError('')).toBe('INVALID_HEADER_NAME');
    expect(headerNameError('a'.repeat(65))).toBe('INVALID_HEADER_NAME');
    for (const name of ['Host', 'content-length', 'Content-Type', 'x-pano-event', 'X-Pano-Signature'])
      expect(headerNameError(name)).toBe('FORBIDDEN_HEADER');
    for (const name of ['Transfer-Encoding', 'Connection', 'User-Agent'])
      expect(headerNameError(name)).toBe('FORBIDDEN_HEADER');
  });

  test('value rules', () => {
    expect(headerValueError('Bearer x')).toBeNull();
    expect(headerValueError(SECRET_MASK)).toBeNull();
    expect(headerValueError('')).toBe('REQUIRED');
    expect(headerValueError('a\r\nb')).toBe('INVALID_HEADER_VALUE');
    expect(headerValueError('a\nb')).toBe('INVALID_HEADER_VALUE');
    expect(headerValueError('a'.repeat(513))).toBe('TOO_LONG');
  });

  test('row validation: duplicates (case-insensitive), blank rows ignored, at most 10', () => {
    const rows = [
      { key: 'A', value: '1' },
      { key: 'a', value: '2' },
      { key: '', value: '' },
      { key: 'Bad Name', value: '1' },
    ];
    const { byIndex } = validateHeaderRows(rows);
    expect(byIndex[1]).toBe('DUPLICATE_HEADER');
    expect(byIndex[2]).toBeUndefined();
    expect(byIndex[3]).toBe('INVALID_HEADER_NAME');
    const eleven = Array.from({ length: 11 }, (_v, i) => ({ key: `H${i}`, value: 'v' }));
    expect(validateHeaderRows(eleven).tooMany).toBe(true);
    expect(validateHeaderRows(eleven.slice(0, 10)).tooMany).toBe(false);
    expect(buildBody(filled({ headers: eleven })).errors.headers.tooMany).toBe(true);
  });

  test('blank rows are not sent', () => {
    const { body } = buildBody(filled({ headers: [{ key: '', value: '' }] }));
    expect(body.headers).toEqual({});
    expect(headerRows(null)).toEqual([]);
  });
});

describe('signing secret', () => {
  test('create: an empty secret is left out so the server generates one', () => {
    const { body } = buildBody(filled({ signing: 'HMAC_SHA256', secret: '' }));
    expect('secret' in body).toBe(false);
    expect(body.signing).toBe('HMAC_SHA256');
  });

  test('a typed secret is validated and sent', () => {
    const good = 'whsec_' + 'a'.repeat(20);
    expect(buildBody(filled({ signing: 'HMAC_SHA256', secret: good })).body.secret).toBe(good);
    expect(buildBody(filled({ signing: 'HMAC_SHA256', secret: 'short' })).errors.secret).toBe(
      'INVALID_SECRET_LENGTH',
    );
    expect(secretError('é'.repeat(20))).toBe('INVALID_SECRET_CHARS');
    expect(secretError('a'.repeat(129))).toBe('INVALID_SECRET_LENGTH');
    expect(secretError('')).toBeNull();
    expect(secretError(SECRET_MASK)).toBeNull();
  });

  test('edit: the mask keeps the stored secret, a new value replaces it', () => {
    const stored = formFromEndpoint({
      name: 'n',
      url: 'https://example.com/h',
      events: ['*'],
      signing: 'HMAC_SHA256',
      secret: SECRET_MASK,
    });
    expect(stored.secret).toBe(SECRET_MASK);
    expect(buildBody(stored, { isEdit: true }).body.secret).toBe(SECRET_MASK);
    expect(buildBody({ ...stored, secret: 'b'.repeat(24) }, { isEdit: true }).body.secret).toBe(
      'b'.repeat(24),
    );
  });

  test('switching signing off clears the secret and sends none', () => {
    const form = withSigning(filled({ signing: 'HMAC_SHA256', secret: 'c'.repeat(20) }), 'NONE');
    expect(form.secret).toBe('');
    expect('secret' in buildBody(form).body).toBe(false);
  });
});

describe('form body', () => {
  test('defaults of a new endpoint', () => {
    const { body } = buildBody(filled());
    expect(body).toEqual({
      name: 'Discord',
      url: 'https://example.com/hook',
      events: ['*'],
      format: 'JSON',
      signing: 'NONE',
      headers: {},
      template: null,
      enabled: true,
      maxAttempts: 8,
    });
  });

  test('events: all = ["*"], else at least one', () => {
    expect(buildBody(filled({ allEvents: false, events: [] })).errors.events).toBe('REQUIRED');
    expect(
      buildBody(filled({ allEvents: false, events: ['order.paid', 'order.refunded'] })).body.events,
    ).toEqual(['order.paid', 'order.refunded']);
  });

  test('name, attempts', () => {
    expect(buildBody(filled({ name: '  ' })).errors.name).toBe('REQUIRED');
    expect(buildBody(filled({ name: 'x'.repeat(129) })).errors.name).toBe('TOO_LONG');
    expect(parseMaxAttempts('1')).toBe(1);
    expect(parseMaxAttempts('20')).toBe(20);
    expect(parseMaxAttempts('0')).toBeNull();
    expect(parseMaxAttempts('21')).toBeNull();
    expect(parseMaxAttempts('1.5')).toBeNull();
    expect(parseMaxAttempts('')).toBeNull();
    expect(buildBody(filled({ maxAttempts: '99' })).errors.maxAttempts).toBe('OUT_OF_RANGE');
  });

  test('editing a stored endpoint round-trips its fields', () => {
    const endpoint = {
      name: 'Orders',
      url: 'https://example.com/h',
      events: ['order.paid'],
      format: 'JSON',
      signing: 'NONE',
      headers: {},
      template: null,
      maxAttempts: 3,
      enabled: false,
    };
    const form = formFromEndpoint(endpoint, { discordTemplate: '{}' });
    expect(form.allEvents).toBe(false);
    expect(form.enabled).toBe(false);
    expect(form.customTemplate).toBe(false);
    const { body } = buildBody(form, { isEdit: true });
    expect(body.events).toEqual(['order.paid']);
    expect(body.maxAttempts).toBe(3);
    expect(body.enabled).toBe(false);
  });

  test('a stored custom template reopens switched on', () => {
    const form = formFromEndpoint(
      { format: 'DISCORD', url: DISCORD, template: '{"x":1}', events: ['*'] },
      { discordTemplate: '{}' },
    );
    expect(form.customTemplate).toBe(true);
    expect(form.template).toBe('{"x":1}');
  });
});

describe('server errors', () => {
  test('INVALID_WEBHOOK_URL marks the url input, with the reason', () => {
    expect(serverFieldErrors('INVALID_WEBHOOK_URL', { reason: 'PRIVATE_ADDRESS' })).toEqual({
      url: 'URL_PRIVATE_ADDRESS',
    });
    expect(serverFieldErrors('INVALID_WEBHOOK_URL', {})).toEqual({ url: 'INVALID_URL' });
  });

  test('INVALID_SETTINGS maps its fieldErrors', () => {
    expect(serverFieldErrors('INVALID_SETTINGS', { fieldErrors: { name: 'LIMIT_REACHED' } })).toEqual({
      name: 'LIMIT_REACHED',
    });
    expect(serverFieldErrors('NOT_FOUND', {})).toEqual({});
  });
});

describe('endpoint list', () => {
  test('status badge', () => {
    expect(endpointStatus({ enabled: true })).toEqual({ kind: 'enabled', auto: false });
    expect(endpointStatus({ enabled: false })).toEqual({ kind: 'disabled', auto: false });
    expect(endpointStatus({ enabled: false, disabledReason: 'TOO_MANY_FAILURES' })).toEqual({
      kind: 'disabled',
      auto: true,
    });
  });

  test('events cell', () => {
    expect(eventsCount({ events: ['*'] })).toBeNull();
    expect(eventsCount({ events: ['order.paid', 'order.refunded'] })).toBe(2);
    expect(eventsCount({})).toBe(0);
  });

  test('send test outcome', () => {
    expect(testOutcome({ statusCode: 204, durationMs: 31 })).toEqual({ ok: true, status: 204, ms: 31 });
    expect(testOutcome({ statusCode: 500, durationMs: 9 }).ok).toBe(false);
    expect(testOutcome({ statusCode: 0, durationMs: 5, error: 'TIMEOUT' }).ok).toBe(false);
    expect(testOutcome({ statusCode: 200, durationMs: 5, error: 'X' }).ok).toBe(false);
    expect(testOutcome(null)).toEqual({ ok: false, status: 0, ms: 0 });
  });

  test('event labels use dashes for dots and never offer test.ping', () => {
    expect(eventKey('order.chargeback.won')).toBe('enums.webhook-event.order-chargeback-won');
    expect(subscribableEvents()).not.toContain('test.ping');
    expect(subscribableEvents(['order.paid', 'test.ping'])).toEqual(['order.paid']);
    expect(subscribableEvents([])).toHaveLength(WEBHOOK_EVENTS.length - 1);
  });
});

describe('delivery log', () => {
  test('tabs map to the statuses of 13 §18.3', () => {
    expect(DELIVERY_TABS.map((t) => t.value)).toEqual([
      null,
      'PENDING,SENDING',
      'SUCCEEDED',
      'FAILED',
      'DEAD',
    ]);
    expect(deliveryTab(null)).toBe('all');
    expect(deliveryTab('PENDING,SENDING')).toBe('pending');
    expect(deliveryTab('DEAD')).toBe('dead');
    expect(deliveryTab('NOPE')).toBeNull();
  });

  test('every status is known to the webhook badge', () => {
    for (const status of ['PENDING', 'SENDING', 'SUCCEEDED', 'FAILED', 'DEAD'])
      expect(isKnownStatus('webhook', status)).toBe(true);
  });

  test('path: all endpoints or one, status and page', () => {
    expect(deliveriesPath()).toBe('/webhook-deliveries');
    expect(deliveriesPath({ endpointId: 7 })).toBe('/webhooks/7/deliveries');
    expect(deliveriesPath({ endpointId: 7, status: 'PENDING,SENDING', page: 3 })).toBe(
      '/webhooks/7/deliveries?status=PENDING%2CSENDING&page=3',
    );
    expect(deliveriesPath({ page: 1, status: 'DEAD' })).toBe('/webhook-deliveries?status=DEAD');
    expect(parseEndpointId('12')).toBe(12);
    expect(parseEndpointId('0')).toBeNull();
    expect(parseEndpointId('1abc')).toBeNull();
    expect(parseEndpointId(null)).toBeNull();
  });

  test('redeliver only from a finished status', () => {
    expect(canRedeliver({ status: 'SUCCEEDED' })).toBe(true);
    expect(canRedeliver({ status: 'FAILED' })).toBe(true);
    expect(canRedeliver({ status: 'DEAD' })).toBe(true);
    expect(canRedeliver({ status: 'PENDING' })).toBe(false);
    expect(canRedeliver({ status: 'SENDING' })).toBe(false);
  });

  test('order cell and pretty text', () => {
    expect(orderLabel({ orderId: 41 })).toBe('#41');
    expect(orderLabel({ orderId: null })).toBeNull();
    expect(prettyText('{"a":1}')).toBe('{\n  "a": 1\n}');
    expect(prettyText('plain text')).toBe('plain text');
    expect(prettyText(null)).toBe('');
    expect(prettyText({ a: 1 })).toBe('{\n  "a": 1\n}');
  });
});

describe('error texts', () => {
  test('unknown codes fall back to INVALID', async () => {
    const { errorTextKey, WEBHOOK_ERROR_CODES } = await import('./webhooks.js');
    expect(errorTextKey('SOMETHING_NEW')).toBe('modals.webhook.errors.INVALID');
    for (const code of WEBHOOK_ERROR_CODES) expect(errorTextKey(code)).toBe(`modals.webhook.errors.${code}`);
  });
});

describe('locale coverage', () => {
  const get = (obj, path) => path.split('.').reduce((o, k) => o?.[k], obj);
  for (const lang of ['en-US', 'tr', 'ru']) {
    test(`${lang} has every webhook text`, async () => {
      const { WEBHOOK_ERROR_CODES } = await import('./webhooks.js');
      const locale = JSON.parse(await Bun.file(`src/locales/panel/${lang}.json`).text());
      const keys = [
        ...WEBHOOK_EVENTS.map(eventKey),
        ...WEBHOOK_ERROR_CODES.map((c) => `modals.webhook.errors.${c}`),
        'enums.webhook-format.JSON',
        'enums.webhook-format.DISCORD',
        'enums.webhook-signing.NONE',
        'enums.webhook-signing.HMAC_SHA256',
        'settings.webhooks.toast-test-ok',
        'settings.webhooks.toast-test-failed',
        'settings.webhooks.invalid-discord-url',
        ...DELIVERY_TABS.map((t) => `settings.webhook-deliveries.tab.${t.key}`),
        ...['PENDING', 'SENDING', 'SUCCEEDED', 'FAILED', 'DEAD'].map((s) => `enums.webhook.${s}`),
      ];
      for (const key of keys) expect(typeof get(locale, key), key).toBe('string');
    });
  }
});
