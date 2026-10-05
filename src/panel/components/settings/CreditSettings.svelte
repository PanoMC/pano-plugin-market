{#if showRefundSplitWarning(draft)}
  <div class="alert alert-warning d-flex align-items-start mb-3" role="alert">
    <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
    <div>
      <b>{$_('settings.credits.refund-split.title')}</b>
      <div>{$_('settings.credits.refund-split.body')}</div>
    </div>
  </div>
{/if}

{#if mismatch > 0}
  <div class="alert alert-warning d-flex align-items-start mb-3" role="alert">
    <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
    <div>
      <b>{$_('settings.credits.price-mismatch.title')}</b>
      <div>{$_('settings.credits.price-mismatch.body', { values: { count: mismatch } })}</div>
    </div>
  </div>
{/if}

<div class="alert alert-info d-flex align-items-start mb-3" role="alert">
  <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
  <div>
    <b>{$_('settings.credits.packs.title')}</b>
    <div>{$_('settings.credits.packs.body')}</div>
    <a class="alert-link" href="{base}/market/products?kind=CREDIT_PACK">
      {$_('settings.credits.packs.open')}
    </a>
  </div>
</div>

<div class="card">
  <div class="card-body">
    <SwitchRow
      id="setting-creditsEnabled"
      label={$_('settings.credits.credits-label')}
      hint={$_('settings.credits.credits-desc')}
      error={message('creditsEnabled')}
      bind:checked={draft.creditsEnabled} />
    <SwitchRow
      id="setting-onlyAcceptCredits"
      label={$_('settings.credits.only-credits-label')}
      hint={$_('settings.credits.only-credits-desc')}
      error={message('onlyAcceptCredits')}
      disabled={!draft.creditsEnabled}
      bind:checked={
        () => effective.onlyAcceptCredits, (value) => (draft.onlyAcceptCredits = value)
      } />

    <SettingRow
      id="setting-creditName"
      label={$_('settings.credits.credit-name-label')}
      hint={$_('settings.credits.credit-name-desc')}
      error={message('creditName')}>
      <input
        id="setting-creditName"
        type="text"
        class="form-control"
        class:is-invalid={shown.creditName}
        maxlength="32"
        autocomplete="off"
        placeholder={$_('settings.credits.credit-name-default')}
        bind:value={draft.creditName} />
    </SettingRow>

    <SettingRow
      id="setting-cashbackPercent"
      label={$_('settings.credits.cashback-label')}
      hint={$_('settings.credits.cashback-desc')}
      error={message('cashbackPercent')}>
      <div class="input-group">
        <input
          id="setting-cashbackPercent"
          type="number"
          min="0"
          max="100"
          step="any"
          class="form-control"
          class:is-invalid={shown.cashbackPercent}
          placeholder={$_('settings.credits.cashback-placeholder')}
          bind:value={draft.cashbackPercent} />
        <span class="input-group-text">%</span>
      </div>
    </SettingRow>

    <SettingRow
      id="setting-creditValue"
      label={$_('settings.credits.credit-value-label')}
      hint={$_('settings.credits.credit-value-hint')}
      error={message('creditValue')}>
      <div class="input-group">
        <span class="input-group-text">
          {$_('settings.credits.credit-value-prefix', { values: { name: creditLabel } })}
        </span>
        <input
          id="setting-creditValue"
          type="number"
          min="0.01"
          step="any"
          class="form-control"
          class:is-invalid={shown.creditValue}
          aria-label={$_('settings.credits.credit-value-label')}
          bind:value={draft.creditValue} />
        <span class="input-group-text">{currency}</span>
      </div>
    </SettingRow>

    <SwitchRow
      id="setting-allowMixedCreditPayment"
      label={$_('settings.credits.mixed-label')}
      hint={$_('settings.credits.mixed-hint')}
      error={message('allowMixedCreditPayment')}
      bind:checked={draft.allowMixedCreditPayment} />

    <SwitchRow
      id="setting-creditTopUpEnabled"
      label={$_('settings.credits.top-up-label')}
      hint={$_('settings.credits.top-up-desc')}
      error={message('creditTopUpEnabled')}
      bind:checked={draft.creditTopUpEnabled} />

    {#if draft.creditTopUpEnabled}
      <SwitchRow
        id="setting-creditTopUpFreeAmount"
        label={$_('settings.credits.top-up-free-label')}
        hint={$_('settings.credits.top-up-free-desc')}
        error={message('creditTopUpFreeAmount')}
        bind:checked={draft.creditTopUpFreeAmount} />

      <SettingRow
        id="setting-creditTopUpMin"
        label={$_('settings.credits.top-up-min-label')}
        error={message('creditTopUpMin')}>
        <div class="input-group">
          <input
            id="setting-creditTopUpMin"
            type="number"
            min="0.01"
            step="any"
            class="form-control"
            class:is-invalid={shown.creditTopUpMin}
            placeholder={$_('settings.credits.top-up-min-label')}
            bind:value={draft.creditTopUpMin} />
          <span class="input-group-text">{creditLabel}</span>
        </div>
      </SettingRow>

      <SettingRow
        id="setting-creditTopUpMax"
        label={$_('settings.credits.top-up-max-label')}
        error={message('creditTopUpMax')}>
        <div class="input-group">
          <input
            id="setting-creditTopUpMax"
            type="number"
            min="0.01"
            step="any"
            class="form-control"
            class:is-invalid={shown.creditTopUpMax}
            placeholder={$_('settings.credits.top-up-max-label')}
            bind:value={draft.creditTopUpMax} />
          <span class="input-group-text">{creditLabel}</span>
        </div>
      </SettingRow>
    {/if}
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
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, postCreditSettings, reportFailure, saveSection } from './save.js';
  import {
    SECTION_KEYS,
    buildSettingsBody,
    fieldErrorKey,
    isDirtyBody,
    seedValues,
  } from '../../utils/settings.js';
  import {
    activeCreditKeys,
    normalizeCredits,
    priceMismatchCount,
    showRefundSplitWarning,
    validateCredits,
  } from '../../utils/settings-extra.js';

  // settings = GET /settings (credit keys and creditPriceMismatchCount), ctx = GET /context.
  // Credits are saved through POST /settings/credits (13 §17).
  let { settings: initial = {}, ctx = null } = $props();

  const KEYS = SECTION_KEYS.credits;
  const start = untrack(() => initial ?? {});

  let settings = $state.raw(start);
  let draft = $state(seedValues(start, KEYS));
  let submitted = $state(false);
  let saving = $state(false);
  let serverMark = $state.raw(null);

  // The values a save sends: the dependent switches follow their parent switch.
  const effective = $derived(normalizeCredits(draft));
  const currency = $derived(ctx?.currency ?? settings.currency ?? '');
  const creditLabel = $derived(
    String(draft.creditName ?? '').trim() || $_('settings.credits.credit-name-default'),
  );
  const mismatch = $derived(priceMismatchCount(settings, draft));
  const draftKey = $derived(JSON.stringify($state.snapshot(draft)));
  const clientErrors = $derived(validateCredits(draft));
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const keys = $derived(activeCreditKeys(effective));
  const isDirty = $derived(isDirtyBody(buildSettingsBody(settings, effective, keys)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

  async function onSave() {
    if (saving || !isDirty) return;
    submitted = true;
    if (Object.keys(clientErrors).length > 0) {
      reportFailure({ status: 'invalid', errors: clientErrors }, KEYS);
      return;
    }
    saving = true;
    try {
      const result = await saveSection({
        baseline: settings,
        values: effective,
        keys,
        errors: clientErrors,
        order: KEYS,
        post: postCreditSettings,
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
</script>
