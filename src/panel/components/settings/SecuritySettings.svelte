<div class="card">
  <div class="card-body">
    <SettingRow
      id="setting-checkoutRateLimitPerMinute"
      label={$_('settings.security.checkout-rate-limit')}
      hint={$_('settings.security.checkout-rate-limit-hint')}
      error={message('checkoutRateLimitPerMinute')}>
      <div class="input-group">
        <input
          id="setting-checkoutRateLimitPerMinute"
          type="number"
          min="0"
          max="100000"
          step="1"
          class="form-control"
          class:is-invalid={shown.checkoutRateLimitPerMinute}
          bind:value={draft.checkoutRateLimitPerMinute} />
        <span class="input-group-text">{$_('settings.security.per-minute')}</span>
      </div>
    </SettingRow>

    <SettingRow
      id="setting-quoteRateLimitPerMinute"
      label={$_('settings.security.quote-rate-limit')}
      hint={$_('settings.security.quote-rate-limit-hint')}
      error={message('quoteRateLimitPerMinute')}>
      <div class="input-group">
        <input
          id="setting-quoteRateLimitPerMinute"
          type="number"
          min="1"
          max="100000"
          step="1"
          class="form-control"
          class:is-invalid={shown.quoteRateLimitPerMinute}
          bind:value={draft.quoteRateLimitPerMinute} />
        <span class="input-group-text">{$_('settings.security.per-minute')}</span>
      </div>
    </SettingRow>

    <SettingRow
      id="setting-couponLockThreshold"
      label={$_('settings.security.coupon-lock-threshold')}
      hint={$_('settings.security.coupon-lock-threshold-hint')}
      error={message('couponLockThreshold')}>
      <input
        id="setting-couponLockThreshold"
        type="number"
        min="1"
        max="100"
        step="1"
        class="form-control"
        class:is-invalid={shown.couponLockThreshold}
        bind:value={draft.couponLockThreshold} />
    </SettingRow>

    <SettingRow
      id="setting-couponLockMinutes"
      label={$_('settings.security.coupon-lock-minutes')}
      hint={$_('settings.security.coupon-lock-minutes-hint')}
      error={message('couponLockMinutes')}>
      <div class="input-group">
        <input
          id="setting-couponLockMinutes"
          type="number"
          min="1"
          max="1440"
          step="1"
          class="form-control"
          class:is-invalid={shown.couponLockMinutes}
          bind:value={draft.couponLockMinutes} />
        <span class="input-group-text">{$_('settings.security.minutes')}</span>
      </div>
    </SettingRow>
  </div>

  <div class="card-footer d-flex justify-content-start">
    <button type="button" class="btn btn-secondary" onclick={onSave} disabled={saving || !isDirty}>
      {#if saving}
        <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
      {/if}
      {$_('common.save')}
    </button>
  </div>
</div>

<script>
  import { untrack } from 'svelte';
  import { _ } from '../../../i18n';
  import SettingRow from './SettingRow.svelte';
  import { fetchSettings, reportFailure, saveSection } from './save.js';
  import {
    SECTION_KEYS,
    buildSettingsBody,
    fieldErrorKey,
    isDirtyBody,
    seedValues,
  } from '../../utils/settings.js';
  import { validateSecurity } from '../../utils/settings-extra.js';

  let { settings: initial = {} } = $props();

  const KEYS = SECTION_KEYS.security;
  const start = untrack(() => initial ?? {});

  let settings = $state.raw(start);
  let draft = $state(seedValues(start, KEYS));
  let submitted = $state(false);
  let saving = $state(false);
  let serverMark = $state.raw(null);

  const draftKey = $derived(JSON.stringify($state.snapshot(draft)));
  const clientErrors = $derived(validateSecurity(draft));
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const isDirty = $derived(isDirtyBody(buildSettingsBody(settings, draft, KEYS)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

  async function persist() {
    saving = true;
    try {
      const result = await saveSection({
        baseline: settings,
        values: draft,
        keys: KEYS,
        errors: clientErrors,
        order: KEYS,
      });
      if (result.status === 'saved') {
        const next = (await fetchSettings()) ?? { ...settings, ...result.sent };
        settings = next;
        draft = seedValues(next, KEYS);
        submitted = false;
        serverMark = null;
      } else if (result.status === 'failed' && Object.keys(result.errors).length > 0) {
        serverMark = { errors: result.errors, draftKey };
      }
    } finally {
      saving = false;
    }
  }

  async function onSave() {
    if (saving || !isDirty) return;
    submitted = true;
    if (Object.keys(clientErrors).length > 0) {
      reportFailure({ status: 'invalid', errors: clientErrors }, KEYS);
      return;
    }
    await persist();
  }
</script>
