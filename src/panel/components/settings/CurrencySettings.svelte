<ConfirmModal bind:this={confirm} />

{#if loadError}
  <LoadError error={loadError} onRetry={reload} />
{:else}
  <div class="card">
    <div class="card-body">
      <div class="mb-3">
        <div class="mb-2">{$_('settings.currencies.mode')}</div>
        {#each CURRENCY_MODES as mode (mode)}
          <div class="form-check">
            <input
              id="setting-currencyMode-{mode}"
              class="form-check-input"
              class:is-invalid={shown.currencyMode}
              type="radio"
              name="currencyMode"
              value={mode}
              bind:group={draft.currencyMode} />
            <label class="form-check-label" for="setting-currencyMode-{mode}">
              {$_(`enums.currency-mode.${mode}`)}
              <span class="form-text d-block mt-0"
                >{$_(`settings.currencies.mode-hint.${mode}`)}</span>
            </label>
          </div>
        {/each}
        {#if shown.currencyMode}
          <div class="invalid-feedback d-block">{message('currencyMode')}</div>
        {/if}
      </div>

      {#if showRates}
        <div class="mb-3">
          <div class="mb-2">{$_('settings.currencies.additional')}</div>
          <div class="d-flex flex-wrap gap-2 mb-2">
            {#each draft.additionalCurrencies as code (code)}
              <span class="badge text-bg-secondary d-inline-flex align-items-center">
                {code}
                <button
                  type="button"
                  class="btn-close btn-close-white ms-2"
                  aria-label={$_('common.remove')}
                  onclick={() => removeCurrency(code)}></button>
              </span>
            {/each}
          </div>
          {#if choices.length > 0}
            <select
              id="setting-additionalCurrencies"
              class="form-select"
              class:is-invalid={shown.additionalCurrencies}
              aria-label={$_('settings.currencies.add')}
              value=""
              onchange={addCurrency}>
              <option value="">{$_('settings.currencies.add')}</option>
              {#each choices as code (code)}
                <option value={code}>{code} ({currencySymbol(ctx, code)})</option>
              {/each}
            </select>
          {/if}
          {#if shown.additionalCurrencies}
            <div class="invalid-feedback d-block">{message('additionalCurrencies')}</div>
          {/if}
        </div>

        <div class="mb-3">
          <div class="d-flex align-items-center justify-content-between mb-2">
            <span>{$_('settings.currencies.rates')}</span>
            <div class="dropdown">
              <button
                type="button"
                class="btn btn-sm btn-link"
                data-bs-toggle="dropdown"
                aria-expanded="false"
                title={$_('common.actions')}
                aria-label={$_('common.actions')}>
                <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
              </button>
              <div class="dropdown-menu dropdown-menu-end">
                <button
                  type="button"
                  class="dropdown-item"
                  disabled={refreshing || draft.additionalCurrencies.length === 0}
                  onclick={refreshRates}>
                  <i class="fa-solid fa-rotate me-2" aria-hidden="true"></i>
                  {$_('settings.currencies.refresh-rates')}
                </button>
              </div>
            </div>
          </div>

          {#if rows.length === 0}
            <div class="text-body-secondary small">
              <i class="fa-solid fa-circle-info me-1" aria-hidden="true"></i>
              {$_('settings.currencies.no-rates')}
            </div>
          {:else}
            <div class="table-responsive">
              <table class="table table-hover mb-0">
                <thead>
                  <tr>
                    <th class="align-middle text-nowrap" scope="col">
                      {$_('settings.currencies.table.currency')}
                    </th>
                    <th class="align-middle text-nowrap" scope="col">
                      {$_('settings.currencies.table.mode')}
                    </th>
                    <th class="align-middle text-nowrap" scope="col">
                      {$_('settings.currencies.table.rate')}
                    </th>
                    <th class="align-middle text-nowrap" scope="col">
                      {$_('settings.currencies.table.fetched')}
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {#each rows as row (row.currency)}
                    <tr>
                      <td class="align-middle text-nowrap">
                        {baseCurrency} / {row.currency}
                      </td>
                      <td class="align-middle">
                        <select
                          class="form-select form-select-sm"
                          aria-label={$_('settings.currencies.table.mode')}
                          value={row.mode}
                          onchange={(event) => setRowMode(row, event.currentTarget.value)}>
                          {#each RATE_MODES as mode (mode)}
                            <option value={mode}>{$_(`enums.rate-source.${mode}`)}</option>
                          {/each}
                        </select>
                      </td>
                      <td class="align-middle">
                        {#if row.mode === 'MANUAL'}
                          <input
                            id="setting-rate-{row.currency}"
                            type="text"
                            inputmode="decimal"
                            autocomplete="off"
                            class="form-control form-control-sm"
                            class:is-invalid={rateErrors[row.currency]}
                            aria-label={$_('settings.currencies.table.rate')}
                            bind:value={row.text} />
                          {#if rateErrors[row.currency]}
                            <div class="invalid-feedback d-block">
                              {$_(fieldErrorKey(rateErrors[row.currency]))}
                            </div>
                          {/if}
                        {:else}
                          <span class="text-body-secondary">
                            {row.rate === null ? '—' : formatRate(row.rate)}
                          </span>
                        {/if}
                      </td>
                      <td class="align-middle text-nowrap">
                        {#if row.fetchedAt}
                          <DateComponent time={row.fetchedAt} relativeFormat={true} />
                        {:else}
                          <span class="text-body-secondary">{$_('common.never')}</span>
                        {/if}
                      </td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {/if}
        </div>
      {/if}

      {#if draft.currencyMode === 'MULTI'}
        <div class="mb-3">
          <div class="mb-2">{$_('settings.currencies.fallback')}</div>
          {#each MULTI_FALLBACKS as fallback (fallback)}
            <div class="form-check">
              <input
                id="setting-multiCurrencyFallback-{fallback}"
                class="form-check-input"
                class:is-invalid={shown.multiCurrencyFallback}
                type="radio"
                name="multiCurrencyFallback"
                value={fallback}
                bind:group={draft.multiCurrencyFallback} />
              <label class="form-check-label" for="setting-multiCurrencyFallback-{fallback}">
                {$_(`settings.currencies.fallback-option.${fallback}`)}
                <span class="form-text d-block mt-0">
                  {$_(`settings.currencies.fallback-hint.${fallback}`)}
                </span>
              </label>
            </div>
          {/each}
          {#if shown.multiCurrencyFallback}
            <div class="invalid-feedback d-block">{message('multiCurrencyFallback')}</div>
          {/if}
        </div>
      {/if}
    </div>

    <div class="card-footer d-flex justify-content-start">
      <button
        type="button"
        class="btn btn-secondary"
        onclick={onSave}
        disabled={saving || !isDirty}>
        {#if saving}
          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
        {/if}
        {$_('common.save')}
      </button>
    </div>
  </div>
{/if}

<script>
  import { untrack } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { Date as DateComponent } from '@panomc/sdk/components/panel';
  import { _, showSuccessToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import LoadError from '../LoadError.svelte';
  import { fetchSettings, reportFailure, postSettings } from './save.js';
  import { call, marketPath } from '../../utils/api.js';
  import { currentLocale } from '../../utils/locale.js';
  import { toastError } from '../../utils/toast.js';
  import {
    CURRENCY_MODES,
    MULTI_FALLBACKS,
    RATE_MODES,
    SECTION_KEYS,
    additionalChoices,
    buildSettingsBody,
    currencySymbol,
    fieldErrorKey,
    isDirtyBody,
    leavesMulti,
    ratesDirty,
    rateRowsFrom,
    saveCurrencies,
    seedValues,
    syncRateRows,
    usesRates,
    validateCurrencies,
  } from '../../utils/settings.js';

  // settings = GET /settings; extra = GET /settings/currencies ({ currencyMode, baseCurrency, rates[] }),
  // null when that request failed.
  let {
    settings: initial = {},
    ctx = null,
    extra: initialExtra = null,
    extraError = null,
  } = $props();

  const KEYS = SECTION_KEYS.currencies;
  const start = untrack(() => initial ?? {});
  const startExtra = untrack(() => initialExtra);

  let confirm = $state(null);
  let settings = $state.raw(start);
  let extra = $state.raw(startExtra);
  let draft = $state(seedValues(start, KEYS));
  let rows = $state(
    rateRowsFrom(seedValues(start, KEYS).additionalCurrencies, startExtra?.rates ?? []),
  );
  let submitted = $state(false);
  let saving = $state(false);
  let refreshing = $state(false);
  let serverMark = $state.raw(null);
  let reloadError = $state(null);

  const loadError = $derived(
    extra === null ? (reloadError ?? extraError ?? 'NETWORK_ERROR') : null,
  );
  const baseCurrency = $derived(extra?.baseCurrency ?? settings.currency ?? ctx?.currency ?? '');
  const showRates = $derived(usesRates(draft.currencyMode));
  const savedAdditional = $derived(
    seedValues(settings, ['additionalCurrencies']).additionalCurrencies,
  );
  const loadedRows = $derived(rateRowsFrom(savedAdditional, extra?.rates ?? []));
  const choices = $derived(additionalChoices(ctx, baseCurrency, draft.additionalCurrencies));

  const draftKey = $derived(JSON.stringify([$state.snapshot(draft), $state.snapshot(rows)]));
  const checked = $derived(validateCurrencies(draft, rows, baseCurrency));
  const clientErrors = $derived({
    ...checked.errors,
    ...Object.fromEntries(
      Object.entries(checked.rates).map(([code, value]) => [`rate-${code}`, value]),
    ),
  });
  const serverErrors = $derived(
    serverMark && serverMark.draftKey === draftKey ? serverMark.errors : {},
  );
  const shown = $derived(submitted ? { ...clientErrors, ...serverErrors } : serverErrors);
  const rateErrors = $derived(
    Object.fromEntries(rows.map((row) => [row.currency, shown[`rate-${row.currency}`] ?? null])),
  );

  const body = $derived(buildSettingsBody(settings, draft, KEYS));
  const isDirty = $derived(isDirtyBody(body) || (showRates && ratesDirty(rows, loadedRows)));

  const message = (key) => (shown[key] ? $_(fieldErrorKey(shown[key])) : '');

  function formatRate(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return String(value ?? '');
    return number.toLocaleString(currentLocale(), { maximumFractionDigits: 10 });
  }

  async function fetchCurrencies() {
    const result = await call(ApiUtil.get({ path: marketPath('/settings/currencies') }));
    return result.ok ? { body: result.body } : { error: result.error };
  }

  function adopt(nextSettings, nextExtra) {
    settings = nextSettings;
    extra = nextExtra;
    draft = seedValues(nextSettings, KEYS);
    rows = rateRowsFrom(draft.additionalCurrencies, nextExtra?.rates ?? []);
    submitted = false;
    serverMark = null;
  }

  async function reload() {
    const [nextSettings, currencies] = await Promise.all([fetchSettings(), fetchCurrencies()]);
    if (nextSettings && currencies.body) {
      reloadError = null;
      adopt(nextSettings, currencies.body);
    } else {
      reloadError = currencies.error ?? 'NETWORK_ERROR';
    }
  }

  function addCurrency(event) {
    const code = event.currentTarget.value;
    event.currentTarget.value = '';
    if (!code || draft.additionalCurrencies.includes(code)) return;
    draft.additionalCurrencies = [...draft.additionalCurrencies, code];
    rows = syncRateRows(rows, draft.additionalCurrencies, extra?.rates ?? []);
  }

  function removeCurrency(code) {
    draft.additionalCurrencies = draft.additionalCurrencies.filter((c) => c !== code);
    rows = syncRateRows(rows, draft.additionalCurrencies, extra?.rates ?? []);
  }

  function setRowMode(row, mode) {
    row.mode = mode;
    // Switching to MANUAL starts from the rate in use, so the admin edits it instead of retyping.
    if (mode === 'MANUAL' && row.text.trim() === '' && row.rate !== null)
      row.text = String(row.rate);
  }

  async function refreshRates() {
    refreshing = true;
    try {
      const result = await call(ApiUtil.post({ path: marketPath('/settings/currencies/refresh') }));
      if (!result.ok) {
        toastError($_, result);
        return;
      }
      extra = result.body;
      rows = rateRowsFrom(draft.additionalCurrencies, result.body.rates ?? []);
      showSuccessToast($_('settings.currencies.toast-refreshed'));
    } finally {
      refreshing = false;
    }
  }

  async function persist() {
    saving = true;
    try {
      const result = await saveCurrencies({
        post: postSettings,
        put: (payload) =>
          call(ApiUtil.put({ path: marketPath('/settings/currencies'), body: payload })),
        baseline: settings,
        values: draft,
        rows,
        loadedRows,
      });
      if (result.status === 'saved') {
        showSuccessToast($_('settings.toast-saved'));
        const [nextSettings, currencies] = await Promise.all([fetchSettings(), fetchCurrencies()]);
        if (nextSettings && currencies.body) adopt(nextSettings, currencies.body);
      } else if (result.status === 'failed') {
        reportFailure(result, [...KEYS]);
        if (Object.keys(result.errors).length > 0) serverMark = { errors: result.errors, draftKey };
      }
    } finally {
      saving = false;
    }
  }

  function onSave() {
    if (saving || !isDirty) return;
    submitted = true;
    if (Object.keys(clientErrors).length > 0) {
      reportFailure({ status: 'invalid', errors: clientErrors }, [
        ...KEYS,
        ...rows.map((r) => `rate-${r.currency}`),
      ]);
      return;
    }
    if (leavesMulti(settings.currencyMode ?? 'SINGLE', draft.currencyMode)) {
      confirm?.open({
        title: $_('settings.currencies.confirm-leave-multi.title'),
        description: $_('settings.currencies.confirm-leave-multi.description'),
        confirmLabel: $_('settings.currencies.confirm-leave-multi.cta'),
        onConfirm: persist,
      });
      return;
    }
    persist();
  }
</script>
