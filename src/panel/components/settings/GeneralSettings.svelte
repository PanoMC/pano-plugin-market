<ConfirmModal bind:this={confirm} />

<div class="card">
  <div class="card-body">
    <SwitchRow
      id="setting-storeEnabled"
      label={$_('settings.general.store-enabled')}
      hint={$_('settings.general.store-enabled-hint')}
      error={message('storeEnabled')}
      bind:checked={draft.storeEnabled} />

    <SettingRow
      id="setting-storeName"
      label={$_('settings.general.store-name')}
      error={message('storeName')}>
      <input
        id="setting-storeName"
        type="text"
        class="form-control"
        class:is-invalid={shown.storeName}
        autocomplete="off"
        placeholder={$_('settings.general.store-name')}
        bind:value={draft.storeName} />
    </SettingRow>

    <SettingRow
      id="setting-storeDescription"
      label={$_('settings.general.store-description')}
      error={message('storeDescription')}>
      <textarea
        id="setting-storeDescription"
        class="form-control"
        class:is-invalid={shown.storeDescription}
        rows="3"
        placeholder={$_('settings.general.store-description')}
        bind:value={draft.storeDescription}></textarea>
    </SettingRow>

    <SettingRow
      id="setting-storeTimeZone"
      label={$_('settings.general.time-zone')}
      hint={$_('settings.general.time-zone-hint')}
      error={message('storeTimeZone')}>
      {#if zones}
        <select
          id="setting-storeTimeZone"
          class="form-select"
          class:is-invalid={shown.storeTimeZone}
          bind:value={draft.storeTimeZone}>
          <option value="">{$_('settings.general.time-zone-default')}</option>
          {#each zones as zone (zone)}
            <option value={zone}>{zone}</option>
          {/each}
        </select>
      {:else}
        <input
          id="setting-storeTimeZone"
          type="text"
          class="form-control"
          class:is-invalid={shown.storeTimeZone}
          autocomplete="off"
          placeholder={$_('settings.general.time-zone-placeholder')}
          bind:value={draft.storeTimeZone} />
      {/if}
    </SettingRow>

    <SettingRow
      id="setting-storePageSize"
      label={$_('settings.general.page-size')}
      hint={$_('settings.general.page-size-hint')}
      error={message('storePageSize')}>
      <input
        id="setting-storePageSize"
        type="number"
        min="1"
        max="60"
        step="1"
        class="form-control"
        class:is-invalid={shown.storePageSize}
        bind:value={draft.storePageSize} />
    </SettingRow>

    <hr class="my-4" />

    <SettingRow
      id="setting-currency"
      label={$_('settings.general.sales-currency-label')}
      error={message('currency')}>
      <select
        id="setting-currency"
        class="form-select"
        class:is-invalid={shown.currency}
        bind:value={draft.currency}>
        {#each currencies as code (code)}
          <option value={code}>{currencyLabel(code)}</option>
        {/each}
      </select>
    </SettingRow>

    <SettingRow
      id="setting-statsCurrency"
      label={$_('settings.general.stats-currency-label')}
      error={message('statsCurrency')}>
      <select
        id="setting-statsCurrency"
        class="form-select"
        class:is-invalid={shown.statsCurrency}
        bind:value={draft.statsCurrency}>
        {#each currencies as code (code)}
          <option value={code}>{currencyLabel(code)}</option>
        {/each}
      </select>
    </SettingRow>

    <div class="mb-3">
      {#if needsConversion}
        <div class="border rounded p-3">
          <div class="d-flex align-items-center mb-3">
            <i class="fas fa-right-left me-2 text-body-secondary" aria-hidden="true"></i>
            <h6 class="mb-0">{$_('settings.general.exchange-rate.heading')}</h6>
          </div>

          <div class="row mb-3">
            <div class="col-md-6 col-form-label">
              {$_('settings.general.exchange-rate.current-label')}
            </div>
            <div class="col-md-6 d-flex align-items-center">
              <span class="fw-semibold">
                {$_('settings.general.exchange-rate.rate-display', {
                  values: {
                    salesSymbol,
                    rate: formatRate(settings.exchangeRate),
                    statsSymbol,
                  },
                })}
              </span>
            </div>
          </div>

          <SettingRow
            id="setting-exchangeRateMode"
            label={$_('settings.general.exchange-rate.mode-label')}>
            <select
              id="setting-exchangeRateMode"
              class="form-select"
              bind:value={draft.exchangeRateMode}>
              <option value="AUTO">{$_('settings.general.exchange-rate.mode-auto')}</option>
              <option value="MANUAL">{$_('settings.general.exchange-rate.mode-manual')}</option>
            </select>
          </SettingRow>

          {#if draft.exchangeRateMode === 'AUTO'}
            <SettingRow
              id="setting-exchangeRateAutoIntervalHours"
              label={$_('settings.general.exchange-rate.interval-label')}
              error={message('exchangeRateAutoIntervalHours')}>
              <div class="input-group">
                <input
                  id="setting-exchangeRateAutoIntervalHours"
                  type="number"
                  min="1"
                  max="168"
                  step="1"
                  class="form-control"
                  class:is-invalid={shown.exchangeRateAutoIntervalHours}
                  bind:value={draft.exchangeRateAutoIntervalHours} />
                <span class="input-group-text">
                  {$_('settings.general.exchange-rate.interval-suffix')}
                </span>
              </div>
            </SettingRow>

            <div class="row mb-3">
              <div class="col-md-6 col-form-label">
                {$_('settings.general.exchange-rate.last-update-label')}
              </div>
              <div class="col-md-6 d-flex align-items-center">
                {#if settings.exchangeRateUpdatedAt}
                  <span class="text-body-secondary">
                    <DateComponent time={settings.exchangeRateUpdatedAt} relativeFormat={true} />
                  </span>
                {:else}
                  <span class="text-body-secondary">
                    {$_('settings.general.exchange-rate.never-updated')}
                  </span>
                {/if}
              </div>
            </div>

            <div class="row">
              <div class="col-md-6 offset-md-6">
                <button
                  type="button"
                  class="btn btn-outline-secondary btn-sm"
                  onclick={refreshRate}
                  disabled={refreshingRate}>
                  {#if refreshingRate}
                    <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
                    {$_('settings.general.exchange-rate.refreshing')}
                  {:else}
                    <i class="fas fa-rotate me-2" aria-hidden="true"></i>
                    {$_('settings.general.exchange-rate.refresh-now')}
                  {/if}
                </button>
              </div>
            </div>
          {:else}
            <SettingRow
              id="setting-exchangeRate"
              label={$_('settings.general.exchange-rate.manual-label')}
              error={message('exchangeRate')}>
              <div class="input-group">
                <span class="input-group-text">
                  {$_('settings.general.exchange-rate.manual-prefix', { values: { salesSymbol } })}
                </span>
                <input
                  id="setting-exchangeRate"
                  type="number"
                  min="0"
                  step="0.0001"
                  class="form-control"
                  class:is-invalid={shown.exchangeRate}
                  bind:value={draft.exchangeRate} />
                <span class="input-group-text">{statsSymbol}</span>
              </div>
            </SettingRow>
          {/if}
        </div>
      {:else}
        <div class="text-body-secondary small">
          <i class="fas fa-circle-info me-1" aria-hidden="true"></i>
          {$_('settings.general.exchange-rate.no-conversion')}
        </div>
      {/if}
    </div>

    <SettingRow
      id="setting-vatPercent"
      label={$_('settings.general.vat-label')}
      error={message('vatPercent')}>
      <div class="input-group">
        <input
          id="setting-vatPercent"
          type="number"
          min="0"
          max="100"
          step="0.5"
          class="form-control"
          class:is-invalid={shown.vatPercent}
          placeholder={$_('settings.general.vat-placeholder')}
          bind:value={draft.vatPercent} />
        <span class="input-group-text">%</span>
      </div>
    </SettingRow>

    <hr class="my-4" />

    <SwitchRow
      id="setting-showVatInPrice"
      label={$_('settings.general.show-vat-in-price')}
      error={message('showVatInPrice')}
      bind:checked={draft.showVatInPrice} />
    <SwitchRow
      id="setting-testMode"
      label={$_('settings.general.test-mode')}
      hint={testModeHint}
      error={message('testMode')}
      bind:checked={draft.testMode} />
    <SwitchRow
      id="setting-removeCents"
      label={$_('settings.general.remove-cents')}
      error={message('removeCents')}
      bind:checked={draft.removeCents} />
    <SwitchRow
      id="setting-showBestsellers"
      label={$_('settings.general.show-bestsellers')}
      error={message('showBestsellers')}
      bind:checked={draft.showBestsellers} />
    <SwitchRow
      id="setting-showFeaturedProducts"
      label={$_('settings.general.show-featured-products')}
      error={message('showFeaturedProducts')}
      bind:checked={draft.showFeaturedProducts} />
    <SwitchRow
      id="setting-showComparisons"
      label={$_('settings.general.show-comparisons')}
      error={message('showComparisons')}
      bind:checked={draft.showComparisons} />
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
  import { api } from '@panomc/sdk/plugin-api';
  import { Date as DateComponent } from '@panomc/sdk/components/panel';
  import { _, showSuccessToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import SettingRow from './SettingRow.svelte';
  import SwitchRow from './SwitchRow.svelte';
  import { fetchSettings, reportFailure, saveSection } from './save.js';
  import { call } from '../../utils/api.js';
  import { pageOf } from '../../utils/page.js';
  import { toastError } from '../../utils/toast.js';
  import { currentLocale } from '../../utils/locale.js';
  import {
    SECTION_KEYS,
    activeKeys,
    buildSettingsBody,
    confirmPlan,
    currencyChoices,
    currencySymbol,
    fieldErrorKey,
    isDirtyBody,
    seedValues,
    timeZoneOptions,
    validateGeneral,
  } from '../../utils/settings.js';

  // settings = GET /settings, ctx = GET /context (may be null: then the currency lists fall back to the
  // stored codes). The section keeps its own copy and refreshes it after a save (13 §1.4).
  let { settings: initial = {}, ctx = null } = $props();

  const KEYS = SECTION_KEYS.general;
  const start = untrack(() => initial ?? {});

  let confirm = $state(null);
  let settings = $state.raw(start);
  let draft = $state(seedValues(start, KEYS));
  let submitted = $state(false);
  let saving = $state(false);
  let refreshingRate = $state(false);
  // Errors the server returned; they hold only for the exact draft they answered for.
  let serverMark = $state.raw(null);

  const zones = timeZoneOptions(
    typeof Intl !== 'undefined' ? Intl : null,
    start.storeTimeZone ?? '',
  );
  const currencies = $derived(
    currencyChoices(
      ctx,
      settings.currency,
      settings.statsCurrency,
      draft.currency,
      draft.statsCurrency,
    ),
  );
  const draftKey = $derived(JSON.stringify($state.snapshot(draft)));
  const clientErrors = $derived(validateGeneral(draft, { ctx, zones }));
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  // A field shows its error after the first save attempt (client rules) or when the server refused this very draft.
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);

  const keys = $derived(activeKeys('general', draft));
  const isDirty = $derived(isDirtyBody(buildSettingsBody(settings, draft, keys)));
  const needsConversion = $derived(draft.statsCurrency !== draft.currency);
  const salesSymbol = $derived(settings.currencySymbol ?? currencySymbol(ctx, settings.currency));
  const statsSymbol = $derived(
    settings.statsCurrencySymbol ?? currencySymbol(ctx, settings.statsCurrency),
  );
  const creditsOn = $derived(Boolean(settings.creditsEnabled ?? ctx?.creditsEnabled));
  const testModeHint = $derived(
    creditsOn
      ? `${$_('settings.general.test-mode-hint')} ${$_('settings.general.test-mode-credits-hint')}`
      : $_('settings.general.test-mode-hint'),
  );

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');
  const currencyLabel = (code) => `${code} (${currencySymbol(ctx, code)})`;

  function formatRate(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return String(value ?? '');
    return number.toLocaleString(currentLocale(), { maximumFractionDigits: 6 });
  }

  function adopt(next) {
    settings = next;
    draft = seedValues(next, KEYS);
    submitted = false;
    serverMark = null;
  }

  async function refreshRate() {
    refreshingRate = true;
    try {
      const result = await call(api.panel.post({ path: '/settings/exchange-rate/refresh' }));
      if (!result.ok) {
        toastError($_, result);
        return;
      }
      // Only the rate fields move: the rest of the form may hold unsaved edits.
      const body = await fetchSettings();
      if (body) {
        settings = {
          ...settings,
          exchangeRate: body.exchangeRate,
          exchangeRateUpdatedAt: body.exchangeRateUpdatedAt,
        };
        if (draft.exchangeRateMode === 'AUTO') draft.exchangeRate = body.exchangeRate;
      }
      showSuccessToast($_('settings.general.exchange-rate.toast-refresh-success'));
    } finally {
      refreshingRate = false;
    }
  }

  async function ordersExist() {
    const result = await call(api.panel.get({ path: '/orders' }));
    if (!result.ok) return null;
    return pageOf(result.body).totalItems > 0;
  }

  async function persist() {
    saving = true;
    try {
      const result = await saveSection({
        baseline: settings,
        values: draft,
        keys,
        errors: clientErrors,
        order: KEYS,
      });
      if (result.status === 'saved') {
        adopt((await fetchSettings()) ?? { ...settings, ...result.sent });
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
    saving = true;
    let exists = null;
    try {
      if (settings.currency !== draft.currency) exists = await ordersExist();
    } finally {
      saving = false;
    }
    const plan = confirmPlan(settings, draft, { ordersExist: exists });
    if (!plan.needed) {
      await persist();
      return;
    }
    const single = plan.reasons.length === 1 ? plan.reasons[0] : null;
    confirm?.open({
      icon: plan.danger ? 'fa-solid fa-triangle-exclamation' : 'fa-solid fa-circle-question',
      title: single
        ? $_(`settings.general.confirm.${single}.title`)
        : $_('settings.general.confirm.MANY.title'),
      description: plan.reasons
        .map((reason) => $_(`settings.general.confirm.${reason}.description`))
        .join(' '),
      confirmLabel: $_('settings.general.confirm.cta'),
      variant: plan.danger ? 'danger' : 'primary',
      onConfirm: persist,
    });
  }
</script>
