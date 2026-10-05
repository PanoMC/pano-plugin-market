import { describe, expect, test } from 'bun:test';
import {
  ACTION_TYPES,
  MAX_ACTIONS,
  NODE_MANAGE_PERMISSION_GROUPS,
  NODE_MANAGE_SERVER_CONSOLE,
  SECRET_MASK,
  actionErrorKey,
  actionId,
  actionLocked,
  addableActionTypes,
  allowedPhases,
  generateSecret,
  groupByPhase,
  insertVariable,
  isServerAction,
  newAction,
  normalizeAction,
  phasesForType,
  serializeAction,
  serializeActions,
  unknownVariables,
  usableFieldKeys,
  validateAction,
  validateActions,
  variableNames,
  variantKeys,
} from './actions.js';

const servers = [{ id: 1 }, { id: 2 }];
const product = (extra = {}) => ({ billingMode: 'ONE_TIME', serverChoices: [], ...extra });
const command = (extra = {}) => ({
  ...newAction('COMMAND'),
  value: ['give {username} diamond'],
  targetServers: [1],
  ...extra,
});

describe('test 34: allowedPhases', () => {
  test('ONE_TIME offers GRANT and REVOKE', () => {
    expect(allowedPhases('ONE_TIME')).toEqual(['GRANT', 'REVOKE']);
  });
  test('TIMED adds EXPIRE', () => {
    expect(allowedPhases('TIMED')).toEqual(['GRANT', 'EXPIRE', 'REVOKE']);
  });
  test('SUBSCRIPTION adds EXPIRE and RENEW', () => {
    expect(new Set(allowedPhases('SUBSCRIPTION'))).toEqual(
      new Set(['GRANT', 'RENEW', 'EXPIRE', 'REVOKE']),
    );
  });
  test('an unknown mode is treated as one-time', () => {
    expect(allowedPhases(undefined)).toEqual(['GRANT', 'REVOKE']);
  });
  test('CREDIT and PERMISSION only keep GRANT / RENEW', () => {
    const all = allowedPhases('SUBSCRIPTION');
    expect(phasesForType('CREDIT', all)).toEqual(['GRANT', 'RENEW']);
    expect(phasesForType('PERMISSION', allowedPhases('ONE_TIME'))).toEqual(['GRANT']);
    expect(phasesForType('COMMAND', all)).toEqual(all);
  });
});

describe('test 35: COMMAND', () => {
  test('a valid command passes', () => {
    expect(validateAction(command(), product(), servers)).toEqual({});
  });
  test('no commands is invalid', () => {
    expect(validateAction(command({ value: [] }), product(), servers).value).toBe('INVALID_VALUE');
  });
  test('an empty row is invalid on the row', () => {
    const errors = validateAction(command({ value: ['say hi', '   '] }), product(), servers);
    expect(errors['value.1']).toBe('INVALID_VALUE');
    expect(errors['value.0']).toBeUndefined();
  });
  test('a newline is invalid', () => {
    const errors = validateAction(command({ value: ['say a\nsay b'] }), product(), servers);
    expect(errors['value.0']).toBe('INVALID_VALUE');
  });
  test('a carriage return and a control character are invalid', () => {
    expect(validateAction(command({ value: ['a\rb'] }), product(), servers)['value.0']).toBe(
      'INVALID_VALUE',
    );
    expect(validateAction(command({ value: ['a\u0000b'] }), product(), servers)['value.0']).toBe(
      'INVALID_VALUE',
    );
  });
  test('longer than 512 characters is invalid', () => {
    expect(
      validateAction(command({ value: ['x'.repeat(513)] }), product(), servers)['value.0'],
    ).toBe('INVALID_VALUE');
    expect(validateAction(command({ value: ['x'.repeat(512)] }), product(), servers)).toEqual({});
  });
  test('more than 20 rows is invalid', () => {
    const rows = Array.from({ length: 21 }, (_, i) => `say ${i}`);
    expect(validateAction(command({ value: rows }), product(), servers).value).toBe(
      'INVALID_VALUE',
    );
  });
  test('FIXED with no servers selected is invalid', () => {
    expect(validateAction(command({ targetServers: [] }), product(), servers).targetServers).toBe(
      'SERVER_REQUIRED',
    );
  });
  test('FIXED with an id that is not a server is invalid', () => {
    expect(validateAction(command({ targetServers: [9] }), product(), servers).targetServers).toBe(
      'UNKNOWN_SERVER',
    );
  });
  test('with an unknown server list the ids are not checked', () => {
    expect(validateAction(command({ targetServers: [9] }), product(), null)).toEqual({});
  });
  test('a site without servers cannot have a server action', () => {
    expect(validateAction(command(), product(), []).serverMode).toBe('NO_SERVERS');
  });
  test('ALL_CONNECTED needs no target', () => {
    expect(
      validateAction(
        command({ serverMode: 'ALL_CONNECTED', targetServers: [] }),
        product(),
        servers,
      ),
    ).toEqual({});
  });
  test('a leading slash is normalised away and does not make the row invalid', () => {
    const action = command({ value: ['/give {username} diamond', ' /say hi '] });
    expect(validateAction(action, product(), servers)).toEqual({});
    expect(normalizeAction(action).value).toEqual(['give {username} diamond', 'say hi']);
  });
  test('only one leading slash is stripped', () => {
    expect(normalizeAction(command({ value: ['//x'] })).value).toEqual(['/x']);
  });
});

describe('test 36: BUYER_CHOICE', () => {
  const choice = command({ serverMode: 'BUYER_CHOICE', targetServers: [] });
  test('empty serverChoices is invalid', () => {
    expect(validateAction(choice, product({ serverChoices: [] }), servers).serverMode).toBe(
      'SERVER_CHOICES_REQUIRED',
    );
  });
  test('with serverChoices it passes', () => {
    expect(validateAction(choice, product({ serverChoices: [1] }), servers)).toEqual({});
  });
  test('a PERMISSION with via PANO is not a server action and never asks for a server', () => {
    const permission = {
      ...newAction('PERMISSION'),
      value: ['vip.fly'],
      serverMode: 'BUYER_CHOICE',
    };
    expect(isServerAction(permission)).toBe(false);
    expect(validateAction(permission, product(), servers)).toEqual({});
  });
  test('a PERMISSION with via SERVER follows the server rules', () => {
    const permission = {
      ...newAction('PERMISSION'),
      value: ['vip.fly'],
      via: 'SERVER',
      serverMode: 'BUYER_CHOICE',
    };
    expect(validateAction(permission, product(), servers).serverMode).toBe(
      'SERVER_CHOICES_REQUIRED',
    );
    expect(validateAction({ ...permission, serverMode: 'FIXED' }, product(), servers)).toEqual({});
  });
});

describe('test 37: WEBHOOK', () => {
  const hook = (value = {}) => ({
    ...newAction('WEBHOOK'),
    value: { url: 'https://example.com/h', format: 'JSON', signing: 'NONE', secret: '', ...value },
  });
  test('an ftp URL is invalid', () => {
    expect(validateAction(hook({ url: 'ftp://example.com/x' }), product())['value.url']).toBe(
      'INVALID_WEBHOOK_URL',
    );
  });
  test('http and https pass; a malformed or empty URL fails', () => {
    expect(validateAction(hook({ url: 'http://a.test/x' }), product())).toEqual({});
    expect(validateAction(hook({ url: 'not a url' }), product())['value.url']).toBe(
      'INVALID_WEBHOOK_URL',
    );
    expect(validateAction(hook({ url: '' }), product())['value.url']).toBe('INVALID_WEBHOOK_URL');
  });
  test('a URL with a template brace is invalid (the server rejects it too)', () => {
    expect(validateAction(hook({ url: 'https://a.test/{username}' }), product())['value.url']).toBe(
      'INVALID_WEBHOOK_URL',
    );
  });
  test('DISCORD with HMAC_SHA256 is normalised to NONE', () => {
    const normalized = normalizeAction(hook({ format: 'DISCORD', signing: 'HMAC_SHA256' }));
    expect(normalized.value.signing).toBe('NONE');
    expect(normalized.value.format).toBe('DISCORD');
  });
  test('JSON with HMAC_SHA256 keeps its signing and may have an empty secret', () => {
    const action = hook({ signing: 'HMAC_SHA256' });
    expect(normalizeAction(action).value.signing).toBe('HMAC_SHA256');
    expect(validateAction(action, product())).toEqual({});
  });
  test('unknown format / signing are invalid', () => {
    const errors = validateAction(hook({ format: 'XML', signing: 'MD5' }), product());
    expect(errors['value.format']).toBe('INVALID');
    expect(errors['value.signing']).toBe('INVALID');
  });
  test('the masked secret passes through unchanged', () => {
    const wire = serializeAction(hook({ signing: 'HMAC_SHA256', secret: SECRET_MASK }));
    expect(wire.value.secret).toBe(SECRET_MASK);
  });
});

describe('test 38: CREDIT', () => {
  const credit = (value) => ({ ...newAction('CREDIT'), value });
  test('0 is invalid', () => {
    expect(validateAction(credit(0), product()).value).toBe('INVALID_VALUE');
  });
  test('1.234 is invalid', () => {
    expect(validateAction(credit(1.234), product()).value).toBe('INVALID_VALUE');
  });
  test('negative, empty, null and text are invalid', () => {
    for (const bad of [-1, '', null, undefined, 'abc', NaN])
      expect(validateAction(credit(bad), product()).value).toBe('INVALID_VALUE');
  });
  test('above 1 000 000 is invalid, 12.5 and 1000000 pass', () => {
    expect(validateAction(credit(1_000_000.01), product()).value).toBe('INVALID_VALUE');
    expect(validateAction(credit(12.5), product())).toEqual({});
    expect(validateAction(credit(1_000_000), product())).toEqual({});
  });
  test('a numeric string is read as a number', () => {
    expect(validateAction(credit('12.5'), product())).toEqual({});
    expect(normalizeAction(credit('12.5')).value).toBe(12.5);
  });
  test('a CREDIT never carries server fields on the wire', () => {
    const wire = serializeAction({
      ...credit(5),
      serverMode: 'FIXED',
      targetServers: [1],
      perUnit: true,
      requiresOnline: true,
    });
    expect(wire).toEqual({ type: 'CREDIT', phase: 'GRANT', delay: 0, value: 5 });
  });
});

describe('test 39: action ids', () => {
  test('a loaded action keeps its id verbatim', () => {
    const loaded = { ...command(), id: 'a7' };
    expect(serializeAction(loaded).id).toBe('a7');
    expect(actionId(loaded)).toBe('a7');
  });
  test('a new action serialises without an id', () => {
    const wire = serializeAction(command());
    expect('id' in wire).toBe(false);
    expect('id' in serializeAction({ ...command(), id: '' })).toBe(false);
    expect('id' in serializeAction({ ...command(), id: undefined })).toBe(false);
  });
  test('ids of a list survive in order, new rows have none', () => {
    const wire = serializeActions([
      { ...command(), id: 'a1' },
      command(),
      { ...command(), id: 'a3' },
    ]);
    expect(wire.map((a) => a.id)).toEqual(['a1', undefined, 'a3']);
  });
  test('a round trip of a loaded action is lossless', () => {
    const loaded = {
      id: 'a2',
      type: 'COMMAND',
      phase: 'GRANT',
      value: ['give {username} diamond'],
      delay: 30,
      serverMode: 'FIXED',
      targetServers: [1, 2],
      requiresOnline: true,
      perUnit: false,
    };
    expect(serializeAction(loaded)).toEqual(loaded);
  });
  test('a PERMISSION keeps `via`, a WEBHOOK drops server fields', () => {
    const permission = { ...newAction('PERMISSION'), id: 'a1', value: ['vip'], via: 'SERVER' };
    expect(serializeAction(permission).via).toBe('SERVER');
    const hook = serializeAction({
      ...newAction('WEBHOOK'),
      serverMode: 'FIXED',
      targetServers: [1],
    });
    expect('serverMode' in hook).toBe(false);
    expect('targetServers' in hook).toBe(false);
  });
  test('duplicate ids are reported on the second row', () => {
    const errors = validateActions(
      [
        { ...command(), id: 'a1' },
        { ...command(), id: 'a1' },
      ],
      product(),
      servers,
    );
    expect(errors['actions.1.id']).toBe('DUPLICATE_ID');
  });
});

describe('test 40: phases', () => {
  test('EXPIRE on a ONE_TIME product is invalid', () => {
    expect(validateAction(command({ phase: 'EXPIRE' }), product(), servers).phase).toBe(
      'INVALID_PHASE',
    );
  });
  test('RENEW needs a subscription', () => {
    expect(
      validateAction(command({ phase: 'RENEW' }), product({ billingMode: 'TIMED' }), servers).phase,
    ).toBe('INVALID_PHASE');
    expect(
      validateAction(
        command({ phase: 'RENEW' }),
        product({ billingMode: 'SUBSCRIPTION' }),
        servers,
      ),
    ).toEqual({});
  });
  test('EXPIRE passes on a TIMED product', () => {
    expect(
      validateAction(command({ phase: 'EXPIRE' }), product({ billingMode: 'TIMED' }), servers),
    ).toEqual({});
  });
  test('a CREDIT in REVOKE is invalid (the inverse is automatic)', () => {
    expect(
      validateAction({ ...newAction('CREDIT'), value: 5, phase: 'REVOKE' }, product()).phase,
    ).toBe('INVALID_PHASE');
  });
  test('an unknown phase is INVALID, a missing phase counts as GRANT', () => {
    expect(validateAction(command({ phase: 'LATER' }), product(), servers).phase).toBe('INVALID');
    expect(validateAction(command({ phase: undefined }), product(), servers)).toEqual({});
  });
  test('the payout / chargeback editors pass their own phase list', () => {
    expect(
      validateAction(command({ phase: 'REVOKE' }), product(), servers, { phases: ['GRANT'] }).phase,
    ).toBe('INVALID_PHASE');
    expect(validateAction(command(), product(), servers, { phases: ['GRANT'] })).toEqual({});
  });
});

describe('other rules', () => {
  test('PERMISSION nodes: 1 to 20, pattern per node', () => {
    const permission = (value) => ({ ...newAction('PERMISSION'), value });
    expect(validateAction(permission([]), product()).value).toBe('INVALID_VALUE');
    expect(validateAction(permission(['group.vip', 'essentials.fly']), product())).toEqual({});
    expect(validateAction(permission(['bad node']), product())['value.0']).toBe('INVALID_VALUE');
    expect(validateAction(permission(['x'.repeat(129)]), product())['value.0']).toBe(
      'INVALID_VALUE',
    );
    const many = Array.from({ length: 21 }, (_, i) => `n${i}`);
    expect(validateAction(permission(many), product()).value).toBe('INVALID_VALUE');
  });
  test('delay is an integer between 0 and 2 592 000 seconds', () => {
    expect(validateAction(command({ delay: 2_592_000 }), product(), servers)).toEqual({});
    expect(validateAction(command({ delay: 2_592_001 }), product(), servers).delay).toBe(
      'OUT_OF_RANGE',
    );
    expect(validateAction(command({ delay: -1 }), product(), servers).delay).toBe('OUT_OF_RANGE');
    expect(validateAction(command({ delay: 1.5 }), product(), servers).delay).toBe('NOT_INTEGER');
    expect(validateAction(command({ delay: '' }), product(), servers)).toEqual({});
  });
  test('perUnit needs maxQuantityPerOrder of at most 100', () => {
    const action = command({ perUnit: true });
    expect(validateAction(action, product(), servers).perUnit).toBe('PER_UNIT_NEEDS_MAX_QUANTITY');
    expect(validateAction(action, product({ maxQuantityPerOrder: 101 }), servers).perUnit).toBe(
      'PER_UNIT_NEEDS_MAX_QUANTITY',
    );
    expect(validateAction(action, product({ maxQuantityPerOrder: 100 }), servers)).toEqual({});
  });
  test('an unknown type is invalid', () => {
    expect(validateAction({ type: 'MAIL' }, product()).type).toBe('INVALID');
  });
  test('validateActions prefixes the index and caps the list', () => {
    const errors = validateActions([command(), command({ value: [] })], product(), servers);
    expect(Object.keys(errors)).toEqual(['actions.1.value']);
    const tooMany = Array.from({ length: MAX_ACTIONS + 1 }, () => command());
    expect(validateActions(tooMany, product(), servers).actions).toBe('TOO_MANY');
  });
  test('serverChoices ids that are not servers are reported', () => {
    expect(validateActions([], product({ serverChoices: [1, 9] }), servers).serverChoices).toBe(
      'UNKNOWN_SERVER',
    );
    expect(validateActions([], product({ serverChoices: [1] }), servers)).toEqual({});
  });
  test('every code of the validator has a locale key path', () => {
    expect(actionErrorKey('INVALID_VALUE')).toBe(
      'pages.create-product.action-errors.INVALID_VALUE',
    );
    expect(actionErrorKey('SOMETHING_NEW')).toBe('pages.create-product.action-errors.INVALID');
  });
});

describe('grouping and helpers', () => {
  test('groupByPhase keeps the original index and the phase order', () => {
    const list = [
      { ...command(), phase: 'REVOKE' },
      command(),
      { ...command(), phase: 'GRANT' },
      { ...command(), phase: 'WEIRD' },
    ];
    const groups = groupByPhase(list);
    expect(groups.map((g) => g.phase)).toEqual(['GRANT', 'REVOKE', 'OTHER']);
    expect(groups[0].items.map((i) => i.index)).toEqual([1, 2]);
    expect(groups[1].items.map((i) => i.index)).toEqual([0]);
  });
  test('generateSecret gives 64 hex characters from 32 random bytes', () => {
    let seen = 0;
    const secret = generateSecret((bytes) => {
      seen = bytes.length;
      bytes.forEach((_, i) => (bytes[i] = i * 8));
    });
    expect(seen).toBe(32);
    expect(secret).toMatch(/^[0-9a-f]{64}$/);
    expect(secret.slice(0, 6)).toBe('000810');
  });
  test('insertVariable inserts at the caret and replaces a selection', () => {
    expect(insertVariable('give  diamond', 5, 5, 'username')).toEqual({
      text: 'give {username} diamond',
      caret: 15,
    });
    expect(insertVariable('say XXX', 4, 7, 'quantity').text).toBe('say {quantity}');
    expect(insertVariable('abc', undefined, undefined, 'x').text).toBe('abc{x}');
    expect(insertVariable('abc', 99, 120, 'x').text).toBe('abc{x}');
  });
});

describe('variables', () => {
  const rich = {
    fields: [
      { fieldKey: 'rank', type: 'SELECT', usableInCommands: true },
      { fieldKey: 'secret', type: 'TEXT', usableInCommands: false },
      { fieldKey: 'flag', type: 'CHECKBOX', usableInCommands: true },
      { fieldKey: 'note', type: 'TEXT' },
    ],
    variants: [
      {
        attributes: [
          { key: 'color', value: 'red' },
          { key: 'size', value: 'L' },
        ],
      },
      { attributes: { color: 'blue', weight: '1' } },
    ],
  };
  test('usable fields exclude usableInCommands=false and checkboxes', () => {
    expect(usableFieldKeys(rich)).toEqual(['rank', 'note']);
  });
  test('variant keys are the union of attribute keys', () => {
    expect(variantKeys(rich).sort()).toEqual(['color', 'size', 'weight']);
  });
  test('variableNames appends field.* and variant.* to the catalogue', () => {
    const names = variableNames(rich, ['username', 'quantity']);
    expect(names).toContain('username');
    expect(names).toContain('field.rank');
    expect(names).toContain('variant.size');
    expect(names).not.toContain('field.secret');
  });
  test('unknownVariables flags missing field / variant keys only', () => {
    const action = command({
      value: [
        'give {username} {field.rank} {field.nope}',
        'tag {variant.color|x} {variant.shape} {Enchantments:[]}',
      ],
    });
    expect(unknownVariables(action, rich).sort()).toEqual(['field.nope', 'variant.shape']);
    expect(unknownVariables({ type: 'CREDIT', value: 1 }, rich)).toEqual([]);
  });
});

describe('actionLocked (11 §14.4)', () => {
  const admin = { admin: true, permissions: [] };
  const star = { admin: false, permissions: ['*'] };
  const groups = { admin: false, permissions: [NODE_MANAGE_PERMISSION_GROUPS] };
  const console_ = { admin: false, permissions: [NODE_MANAGE_SERVER_CONSOLE.toUpperCase()] };
  const editor = {
    admin: false,
    permissions: ['pano.plugin.pano-plugin-market.manage.market.catalog'],
  };
  const command = newAction('COMMAND');
  const permission = (...nodes) => ({ ...newAction('PERMISSION'), value: nodes });

  test('CREDIT and WEBHOOK are never locked', () => {
    for (const user of [admin, editor, null]) {
      expect(actionLocked(newAction('CREDIT'), user)).toBe(false);
      expect(actionLocked(newAction('WEBHOOK'), user)).toBe(false);
    }
  });

  test('admin and a holder of * can save everything', () => {
    for (const user of [admin, star]) {
      expect(actionLocked(command, user)).toBe(false);
      expect(actionLocked(permission('vip'), user)).toBe(false);
      expect(actionLocked(permission('*', 'pano.panel.x'), user)).toBe(false);
    }
  });

  test('COMMAND needs the server console node', () => {
    expect(actionLocked(command, editor)).toBe(true);
    expect(actionLocked(command, null)).toBe(true);
    expect(actionLocked(command, console_)).toBe(false);
    expect(actionLocked(command, groups)).toBe(true);
  });

  test('PERMISSION needs the permission groups node', () => {
    expect(actionLocked(permission('vip'), editor)).toBe(true);
    expect(actionLocked(permission('vip'), groups)).toBe(false);
    expect(actionLocked(permission(), groups)).toBe(false);
    expect(actionLocked(permission('vip'), console_)).toBe(true);
  });

  test('a * or pano. node needs * even with the permission groups node', () => {
    expect(actionLocked(permission('*'), groups)).toBe(true);
    expect(actionLocked(permission('vip', 'pano.panel.manage.users'), groups)).toBe(true);
    expect(actionLocked(permission('panoramic.vip'), groups)).toBe(false);
  });

  test('the Add Action modal offers only the types the caller could save', () => {
    expect(addableActionTypes(admin)).toEqual(ACTION_TYPES);
    expect(addableActionTypes(editor)).toEqual(['CREDIT', 'WEBHOOK']);
    expect(addableActionTypes(groups)).toEqual(['CREDIT', 'PERMISSION', 'WEBHOOK']);
    expect(addableActionTypes(console_)).toEqual(['CREDIT', 'COMMAND', 'WEBHOOK']);
  });
});
