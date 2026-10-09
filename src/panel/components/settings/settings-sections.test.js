import { describe, expect, test } from 'bun:test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import en from '../../../locales/panel/en-US.json';
import ru from '../../../locales/panel/ru.json';
import tr from '../../../locales/panel/tr.json';
import {
  ADMIN_COMMANDS,
  MAIL_KINDS,
  PLATFORM_WEBHOOKS_PATH,
  SECTION_KEYS,
  SIDEBARS,
} from '../../utils/settings.js';
import { QUEUE_CARDS } from '../../utils/health.js';
import { MARKET_STATES, MC_FEATURES } from '../../utils/minecraft-settings.js';

// Source-level checks for the sections of MPU-15: there is no DOM harness in this repo, so what a
// component must render is pinned against its source, and the logic behind it is unit tested in
// utils/settings-extra.test.js, minecraft-settings.test.js and health.test.js.
const here = import.meta.dir;
const source = (name) => readFileSync(join(here, name), 'utf8');
const modal = (name) => readFileSync(join(here, '../modals', name), 'utf8');
const get = (locale, path) => path.split('.').reduce((node, part) => node?.[part], locale);

const SECTIONS = {
  credits: 'CreditSettings.svelte',
  delivery: 'DeliverySettings.svelte',
  modules: 'StoreModuleSettings.svelte',
  security: 'SecuritySettings.svelte',
  mail: 'MailSettings.svelte',
  minecraft: 'MinecraftSettings.svelte',
};

describe('every key of 00 §12 of these sections has a control', () => {
  test('credits, delivery, modules, security and mail keys appear as a control id', () => {
    for (const [section, file] of Object.entries(SECTIONS)) {
      if (section === 'minecraft') continue;
      const text = source(file);
      for (const key of SECTION_KEYS[section]) {
        // chargebackActions is edited by the action editors inside a card with that id
        const present = text.includes(`setting-${key}`) || text.includes(`setting-${key}-`);
        expect([section, key, present]).toEqual([section, key, true]);
      }
    }
  });

  test('every mc* key has a control in the Minecraft section (feature rows, admin list, template, vault)', () => {
    const text = source(SECTIONS.minecraft);
    for (const key of SECTION_KEYS.minecraft) {
      const direct = text.includes(`setting-${key}`);
      const viaFeature =
        MC_FEATURES.some((f) => f.key === key) && text.includes('setting-{feature.key}');
      expect([key, direct || viaFeature]).toEqual([key, true]);
    }
  });

  test('every module* key has a control in the modules section', () => {
    const keys = SECTION_KEYS.modules.filter((key) => key.startsWith('module'));
    expect(keys).toHaveLength(11);
    const text = source(SECTIONS.modules);
    for (const key of keys) expect(text).toContain(`setting-${key}`);
  });

  test('the override modal has a control for every in-game feature', () => {
    const text = modal('ServerOverrideModal.svelte');
    expect(text).toContain('override-{feature.key}');
    expect(text).toContain('override-mcVaultMode');
    expect(text).toContain('override-mcBroadcastTemplate');
    expect(text).toContain('override-mcVaultRate');
    expect(text).toContain('override-mcVaultDirection');
    expect(text).toContain('mcDisabledAdminCommands');
    expect(text).toContain('MC_FEATURES');
  });
});

describe('what the sections must show', () => {
  test('the mixed-payment switch shows the refunds-get-split warning', () => {
    const text = source(SECTIONS.credits);
    expect(text).toContain('setting-allowMixedCreditPayment');
    expect(text).toContain('showRefundSplitWarning(draft)');
    expect(text).toContain('settings.credits.refund-split.title');
    expect(text).toContain('settings.credits.mixed-hint');
    // the always-visible line beside the switch, and the alert above the card (13 §17 + 07 §6.5)
    for (const locale of [en, tr, ru]) {
      expect(get(locale, 'settings.credits.refund-split.title').length).toBeGreaterThan(3);
      expect(get(locale, 'settings.credits.mixed-hint').length).toBeGreaterThan(10);
    }
    expect(en.settings.credits['refund-split'].title).toBe('Refunds Are Split');
  });

  test('credits are saved through POST /settings/credits', () => {
    expect(source(SECTIONS.credits)).toContain('post: postCreditSettings');
    expect(source('save.js')).toContain("path: '/settings/credits'");
  });

  test('the credit value row reads "1 <name> = [ ] <currency>" and carries the revaluation hint', () => {
    const text = source(SECTIONS.credits);
    expect(text).toContain('settings.credits.credit-value-prefix');
    expect(text).toContain('settings.credits.credit-value-hint');
    expect(en.settings.credits['credit-value-prefix']).toBe('1 {name} =');
  });

  test('the Vault PROVIDER warning is shown', () => {
    const text = source(SECTIONS.minecraft);
    expect(text).toContain('vaultProviderWarning(draft)');
    expect(text).toContain('settings.minecraft.vault-provider-warning.title');
    for (const locale of [en, tr, ru])
      expect(get(locale, 'settings.minecraft.vault-provider-warning.body').length).toBeGreaterThan(
        10,
      );
  });

  test('the per-server override modal shows the same PROVIDER warning, gated on the effective mode', () => {
    const text = modal('ServerOverrideModal.svelte');
    expect(text).toContain('settings.minecraft.vault-provider-warning.title');
    expect(text).toContain('settings.minecraft.vault-provider-warning.body');
    expect(text).toContain("effectiveVaultMode(form, defaults) === 'PROVIDER'");
    expect(text).toMatch(/\{#if providerMode\}[\s\S]*vault-provider-warning/);
  });

  test('the warning body carries the round trip and the compromised-server risk (19 §10)', () => {
    const body = get(en, 'settings.minecraft.vault-provider-warning.body');
    expect(body).toContain('round trip');
    expect(body).toContain('compromised game server can move its players');
    for (const locale of [tr, ru])
      expect(get(locale, 'settings.minecraft.vault-provider-warning.body')).not.toBe(body);
    expect(get(tr, 'settings.minecraft.vault-provider-warning.body')).toContain('Ele geçirilmiş');
    expect(get(ru, 'settings.minecraft.vault-provider-warning.body')).toContain(
      'Скомпрометированный',
    );
  });

  test('the Minecraft section lists version, marketState, waiting count, download link and override', () => {
    const text = source(SECTIONS.minecraft);
    for (const needle of [
      'row.version',
      'row.required',
      'row.state',
      'row.waiting',
      'row.downloadUrl',
      'row.overrides',
      'settings.minecraft.servers.override-settings',
      'settings.minecraft.servers.clear-override',
      'clearOverrideRequest',
      'overrideModal?.open',
    ])
      expect([needle, text.includes(needle)]).toEqual([needle, true]);
  });

  test('the override modal saves through PUT /servers/:id/settings with the three-state request', () => {
    const text = modal('ServerOverrideModal.svelte');
    expect(text).toContain('api.panel.put');
    expect(text).toContain('/settings`');
    expect(text).toContain('overrideRequest(form, defaults)');
  });

  test('the mail section warns when mail is off and sends a test mail', () => {
    const text = source(SECTIONS.mail);
    expect(text).toContain('mailAlert(settings, extra)');
    expect(text).toContain('alert-danger');
    expect(text).toContain('alert-warning');
    expect(text).toContain('MailTestModal');
    expect(modal('MailTestModal.svelte')).toContain("path: '/settings/mail/test'");
  });

  test('the security section no longer owns the private webhook targets switch (it is the platform setting webhooks.allow-private-targets)', () => {
    const text = source(SECTIONS.security);
    expect(text).not.toContain('allowPrivateWebhookTargets');
    expect(text).not.toContain('needsPrivateTargetConfirm');
  });

  test('the webhooks section is one row that opens the platform page filtered to the market', () => {
    const text = source('WebhooksLink.svelte');
    expect(text).toContain('PLATFORM_WEBHOOKS_PATH');
    expect(text).toContain('href=');
    expect(text).not.toContain('api.panel');
    expect(PLATFORM_WEBHOOKS_PATH).toBe('/settings/webhooks?source=market');
  });

  test('the delivery section edits the chargeback actions with the action editors', () => {
    const text = source(SECTIONS.delivery);
    expect(text).toContain('<ActionEditor');
    expect(text).toContain('showPhase={false}');
    expect(text).toContain("phases={['GRANT']}");
    expect(text).toContain('types={CHARGEBACK_ACTION_TYPES}');
    expect(text).toContain('withChargeback(');
  });

  test('the health panel is read-only and re-reads the report', () => {
    const text = source('HealthPanel.svelte');
    expect(text).not.toContain('api.panel.post');
    expect(text).not.toContain('api.panel.put');
    expect(text).toContain('RECHECK_CREDITS_PATH');
    expect(text).toContain('StatusBadge kind="provider"');
  });

  test('every section is registered', () => {
    const registry = source('registry.js');
    for (const [key, component] of [
      ['credits', 'CreditSettings'],
      ['delivery', 'DeliverySettings'],
      ['modules', 'StoreModuleSettings'],
      ['security', 'SecuritySettings'],
      ['mail', 'MailSettings'],
      ['minecraft', 'MinecraftSettings'],
      ['health', 'HealthPanel'],
      ['webhooks', 'WebhooksLink'],
    ])
      expect(registry).toContain(`${key}: ${component},`);
  });
});

describe('locale keys built at run time by these sections', () => {
  const keys = [
    ...MC_FEATURES.flatMap((f) => [
      `settings.minecraft.feature.${f.id}`,
      `settings.minecraft.feature-hint.${f.id}`,
    ]),
    ...ADMIN_COMMANDS.map((c) => `settings.minecraft.admin-command.${c}`),
    ...['OFF', 'PROVIDER', 'CONVERT'].flatMap((m) => [
      `settings.minecraft.vault-option.${m}`,
      `settings.minecraft.vault-hint.${m}`,
    ]),
    ...['BOTH', 'TO_SERVER', 'TO_CREDITS'].map((d) => `settings.minecraft.direction.${d}`),
    ...MARKET_STATES.map((s) => `settings.minecraft.state.${s}`),
    ...['MONTH', 'ALL_TIME'].map((p) => `settings.modules.period.${p}`),
    ...SIDEBARS.map((s) => `settings.modules.sidebar.${s}`),
    ...QUEUE_CARDS.map((c) => `settings.health.queue.${c.id}`),
    ...['OK', 'DISABLED', 'HOST_TOO_OLD'].map((m) => `settings.health.mail.${m}`),
    ...MAIL_KINDS.map((k) => `enums.mail-kind.${k}`),
    ...['default', 'on', 'off'].map((s) => `modals.server-override.state.${s}`),
    ...['REQUIRES_TOP_UP', 'REQUIRES_CREDITS', 'MIN_ABOVE_MAX'].map(
      (c) => `settings.field-error.${c}`,
    ),
  ];

  test('every key exists as a non-empty string in tr, en-US and ru', () => {
    for (const [name, locale] of [
      ['en-US', en],
      ['tr', tr],
      ['ru', ru],
    ])
      for (const key of keys) {
        const value = get(locale, key);
        expect([name, key, typeof value]).toEqual([name, key, 'string']);
        expect(value.trim()).not.toBe('');
      }
  });

  test('the placeholders of the parametrised strings match in the three languages', () => {
    const placeholders = {
      'settings.credits.credit-value-prefix': ['name'],
      'settings.credits.price-mismatch.body': ['count'],
      'settings.delivery.chargeback-actions': ['count'],
      'settings.minecraft.servers.title': ['count'],
      'settings.minecraft.servers.version-pair': ['have', 'want'],
      'settings.minecraft.servers.override-badge': ['count'],
      'settings.minecraft.broadcast-template-hint': ['variables'],
      'settings.health.jobs.title': ['count'],
      'settings.health.jobs.lag-seconds': ['seconds'],
      'settings.health.providers.title': ['count'],
      'settings.health.servers.title': ['count'],
      'modals.server-override.title': ['name'],
      'modals.server-override.state.default': ['value'],
    };
    for (const [key, names] of Object.entries(placeholders))
      for (const locale of [en, tr, ru]) {
        const text = get(locale, key);
        for (const name of names) expect([key, text.includes(`{${name}}`)]).toEqual([key, true]);
      }
  });

  test('the obsolete card header texts of the old credit section are gone (13 §17)', () => {
    for (const locale of [en, tr, ru]) {
      expect(locale.settings.credits.heading).toBeUndefined();
      expect(locale.settings.credits.subtitle).toBeUndefined();
    }
  });
});
