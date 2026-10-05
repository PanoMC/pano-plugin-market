<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable modal-lg">
    <div class="modal-content">
      {#if provider}
        <div class="modal-header">
          <h5 class="modal-title">{name}</h5>
          <button
            type="button"
            class="btn-close"
            data-bs-dismiss="modal"
            aria-label={$_('common.close')}></button>
        </div>
        <form onsubmit={submit} novalidate>
          <div class="modal-body">
            <ul class="nav nav-tabs mb-3" role="tablist">
              {#each TABS as item (item)}
                <li class="nav-item" role="presentation">
                  <button
                    type="button"
                    class="nav-link"
                    class:active={tab === item}
                    role="tab"
                    aria-selected={tab === item}
                    onclick={() => (tab = item)}>
                    {$_(`modals.provider.tab-${item}`)}
                  </button>
                </li>
              {/each}
            </ul>

            {#if readOnly}
              <div class="alert alert-warning d-flex align-items-start" role="alert">
                <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
                <div>
                  <b>{$_('modals.provider.read-only-title')}</b>
                  <div>
                    {provider.state === 'INCOMPATIBLE'
                      ? $_('settings.payments.incompatible', {
                          values: { spiVersion: provider.spiVersion ?? '' },
                        })
                      : $_('settings.payments.unavailable')}
                  </div>
                </div>
              </div>
            {/if}

            {#if tab === 'settings'}
              {#if storedRows.length > 0}
                <div class="text-body-secondary small text-uppercase mb-2">
                  {$_('modals.provider.stored-settings')}
                </div>
                <dl class="row mb-3">
                  {#each storedRows as [key, value] (key)}
                    <dt class="col-sm-5 text-break">{key}</dt>
                    <dd class="col-sm-7 text-break">{value}</dd>
                  {/each}
                </dl>
              {/if}

              <SchemaForm
                schema={provider.schema}
                bind:values={working}
                errors={schemaErrors}
                {readOnly}
                webhookUrls={provider.webhookUrls ?? {}}
                idPrefix="pm-field"
                revealPath="/payment-methods/{provider.id}/reveal" />

              <PluginHook
                name="market:panel:payment-method:settings:{provider.id}"
                props={{ provider, values: working, readOnly }} />

              {#if actions.length > 0}
                <div class="d-flex flex-wrap gap-2 mt-3">
                  {#each actions as action (action.id)}
                    {@const blocked = actionBlocked(action, dirty)}
                    {@const tip = blocked
                      ? $_('modals.provider.save-first')
                      : action.description
                        ? txt(action.description)
                        : ''}
                    {#snippet actionButton()}
                      <button
                        type="button"
                        class="btn btn-outline-secondary"
                        disabled={readOnly || blocked || runningAction !== null || saving}
                        onclick={() => runAction(action)}>
                        {#if runningAction === action.id}
                          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"
                          ></span>
                        {/if}
                        {txt(action.label)}
                      </button>
                    {/snippet}
                    {#if tip}
                      <span use:tooltip={[tip]}>{@render actionButton()}</span>
                    {:else}
                      {@render actionButton()}
                    {/if}
                  {/each}
                </div>
              {/if}

              {#if actionResult}
                <div
                  class="alert {actionResult.success ? 'alert-success' : 'alert-danger'} d-flex align-items-start mt-3 mb-0"
                  role="alert">
                  <i
                    class="fa-solid {actionResult.success ? 'fa-circle-check' : 'fa-circle-exclamation'} me-3 mt-1"
                    aria-hidden="true"></i>
                  <div>
                    {#if actionResult.message}
                      <div>{actionResult.message}</div>
                    {/if}
                    {#if actionResult.counts}
                      <div>
                        {$_('modals.provider.imported', { values: actionResult.counts })}
                      </div>
                    {/if}
                  </div>
                </div>
              {/if}
            {:else if tab === 'rules'}
              {@render rules()}
            {:else}
              {@render status()}
            {/if}
          </div>
          <div class="modal-footer">
            <button type="submit" class="btn btn-primary w-100" disabled={saving || readOnly}>
              {#if saving}
                <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
              {/if}
              {$_('common.save')}
            </button>
          </div>
        </form>
      {/if}
    </div>
  </div>
</div>

<ConfirmModal bind:this={confirmModal} />

{#snippet rules()}
  {@const locked = pricingLocked(caps)}
  {@const testControl = testModeControl(caps)}
  <div class="vstack gap-3">
    <div>
      <input
        id="pm-rules-label"
        type="text"
        class="form-control"
        class:is-invalid={configErrors.customLabel}
        autocomplete="off"
        placeholder={txt(provider.descriptor?.name)}
        aria-label={$_('modals.provider.custom-label')}
        disabled={readOnly}
        bind:value={form.customLabel} />
      {#if configErrors.customLabel}
        <div class="invalid-feedback d-block">{$_(`modals.provider.error.${configErrors.customLabel}`)}</div>
      {/if}
    </div>

    <div>
      <textarea
        id="pm-rules-description"
        class="form-control"
        class:is-invalid={configErrors.customDescription}
        rows="3"
        placeholder={$_('modals.provider.custom-description')}
        aria-label={$_('modals.provider.custom-description')}
        disabled={readOnly}
        bind:value={form.customDescription}></textarea>
      {#if configErrors.customDescription}
        <div class="invalid-feedback d-block">
          {$_(`modals.provider.error.${configErrors.customDescription}`)}
        </div>
      {/if}
    </div>

    {#if locked}
      <div class="alert alert-info d-flex align-items-start mb-0" role="alert">
        <i class="fa-solid fa-circle-info me-3 mt-1" aria-hidden="true"></i>
        <div>{$_('settings.payments.external-pricing')}</div>
      </div>
    {/if}

    <div class="form-check form-switch">
      <input
        id="pm-rules-fee-buyer"
        class="form-check-input"
        type="checkbox"
        role="switch"
        disabled={readOnly || locked}
        bind:checked={form.feeBuyer} />
      <label class="form-check-label user-select-none" for="pm-rules-fee-buyer">
        {$_('modals.provider.fee-buyer')}
      </label>
    </div>

    {#if form.feeBuyer}
      <div class="row g-3">
        <div class="col-sm-6">
          <label class="form-label" for="pm-rules-fee-percent">{$_('modals.provider.fee-percent')}</label>
          <MoneyInput
            id="pm-rules-fee-percent"
            currency="%"
            invalid={!!configErrors.feePercent}
            disabled={readOnly || locked}
            bind:value={form.feePercent} />
          {#if configErrors.feePercent}
            <div class="invalid-feedback d-block">
              {$_(`modals.provider.error.${configErrors.feePercent}`)}
            </div>
          {/if}
        </div>
        <div class="col-sm-6">
          <label class="form-label" for="pm-rules-fee-fixed">{$_('modals.provider.fee-fixed')}</label>
          <MoneyInput
            id="pm-rules-fee-fixed"
            currency={ctx?.currency ?? ''}
            {exponent}
            invalid={!!configErrors.feeFixed}
            disabled={readOnly || locked}
            bind:value={form.feeFixed} />
          {#if configErrors.feeFixed}
            <div class="invalid-feedback d-block">
              {$_(`modals.provider.error.${configErrors.feeFixed}`)}
            </div>
          {/if}
        </div>
      </div>
    {/if}

    <div class="row g-3">
      <div class="col-sm-6">
        <label class="form-label" for="pm-rules-min">{$_('modals.provider.min-amount')}</label>
        <MoneyInput
          id="pm-rules-min"
          currency={ctx?.currency ?? ''}
          {exponent}
          invalid={!!configErrors.minAmount}
          disabled={readOnly || locked}
          bind:value={form.minAmount} />
        {#if configErrors.minAmount}
          <div class="invalid-feedback d-block">
            {$_(`modals.provider.error.${configErrors.minAmount}`)}
          </div>
        {/if}
      </div>
      <div class="col-sm-6">
        <label class="form-label" for="pm-rules-max">{$_('modals.provider.max-amount')}</label>
        <MoneyInput
          id="pm-rules-max"
          currency={ctx?.currency ?? ''}
          {exponent}
          invalid={!!configErrors.maxAmount}
          disabled={readOnly || locked}
          bind:value={form.maxAmount} />
        {#if configErrors.maxAmount}
          <div class="invalid-feedback d-block">
            {$_(`modals.provider.error.${configErrors.maxAmount}`)}
          </div>
        {/if}
      </div>
    </div>

    <div>
      <div class="form-check form-switch">
        <input
          id="pm-rules-all-currencies"
          class="form-check-input"
          type="checkbox"
          role="switch"
          disabled={readOnly}
          bind:checked={form.allCurrencies} />
        <label class="form-check-label user-select-none" for="pm-rules-all-currencies">
          {$_('modals.provider.all-currencies')}
        </label>
      </div>
      {#if !form.allCurrencies}
        <div class="mt-2">
          {#each choices as code (code)}
            <div class="form-check form-check-inline">
              <input
                id="pm-rules-cur-{code}"
                class="form-check-input"
                class:is-invalid={configErrors.currencies}
                type="checkbox"
                value={code}
                disabled={readOnly}
                bind:group={form.currencies} />
              <label class="form-check-label" for="pm-rules-cur-{code}">{code}</label>
            </div>
          {/each}
          {#if configErrors.currencies}
            <div class="invalid-feedback d-block">
              {$_(`modals.provider.error.${configErrors.currencies}`)}
            </div>
          {/if}
        </div>
      {/if}
    </div>

    {#if testControl !== 'hidden'}
      <div>
        <div class="form-check form-switch">
          <input
            id="pm-rules-test-mode"
            class="form-check-input"
            type="checkbox"
            role="switch"
            disabled={readOnly || testControl === 'derived'}
            checked={testControl === 'derived' ? caps.derivedTestMode === true : form.testMode}
            onchange={(e) => (form.testMode = e.currentTarget.checked)} />
          <label class="form-check-label user-select-none" for="pm-rules-test-mode">
            {$_('modals.provider.test-mode')}
          </label>
        </div>
        <div class="form-text">
          {testControl === 'derived'
            ? $_('modals.provider.test-mode-derived')
            : $_('modals.provider.test-mode-hint')}
        </div>
      </div>
    {/if}
  </div>
{/snippet}

{#snippet status()}
  <div class="vstack gap-3">
    <div>
      <div class="text-body-secondary small text-uppercase mb-2">{$_('modals.provider.webhook-urls')}</div>
      {#each webhooks as [channel, url] (channel)}
        <div class="input-group mb-2">
          <span class="input-group-text">{channel}</span>
          <input
            class="form-control"
            type="text"
            readonly
            value={url}
            aria-label={$_('modals.provider.webhook-url', { values: { channel } })} />
          <CopyButton text={url} />
        </div>
      {:else}
        <div class="text-body-secondary">{$_('modals.provider.no-webhook-urls')}</div>
      {/each}
    </div>

    <dl class="row mb-0">
      <dt class="col-sm-5">{$_('modals.provider.last-inbound')}</dt>
      <dd class="col-sm-7">
        {provider.lastInboundAt
          ? new Date(provider.lastInboundAt).toLocaleString(currentLocale())
          : $_('common.never')}
      </dd>
      <dt class="col-sm-5">{$_('modals.provider.last-error')}</dt>
      <dd class="col-sm-7">
        {#if provider.lastError}
          <div class="text-break">{provider.lastError}</div>
          {#if provider.lastErrorAt}
            <div class="text-body-secondary">
              {new Date(provider.lastErrorAt).toLocaleString(currentLocale())}
            </div>
          {/if}
        {:else}
          {$_('modals.provider.no-error')}
        {/if}
      </dd>
      {#each capabilities as row (row.key)}
        <dt class="col-sm-5">{$_(`modals.provider.cap-${row.key}`)}</dt>
        <dd class="col-sm-7">
          {#if row.kind === 'enum'}
            {$_(`enums.${row.enum}.${row.value}`)}
          {:else if row.kind === 'bool'}
            {row.value ? $_('common.yes') : $_('common.no')}
          {:else}
            {row.value.length > 0 ? row.value.join(', ') : $_('modals.provider.all-supported')}
          {/if}
        </dd>
      {/each}
      <dt class="col-sm-5">{$_('modals.provider.plugin')}</dt>
      <dd class="col-sm-7">
        {provider.pluginId ?? ''}
        <span class="text-body-secondary">
          {$_('modals.provider.spi-version', { values: { version: provider.spiVersion ?? '' } })}
        </span>
      </dd>
    </dl>
  </div>
{/snippet}

<script>
  import { tick } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ as rawI18n } from '@panomc/sdk/utils/language';
  import { _, showSuccessToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import CopyButton from '../CopyButton.svelte';
  import MoneyInput from '../MoneyInput.svelte';
  import PluginHook from '../PluginHook.svelte';
  import SchemaForm from '../schema/SchemaForm.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { currentLocale } from '../../utils/locale.js';
  import {
    actionBlocked,
    buildConfig,
    capabilityRows,
    configErrorCode,
    configToForm,
    currencyChoices,
    importedCounts,
    pricingLocked,
    providerName,
    rowBehavior,
    tabOfFirstClientError,
    tabOfFirstError,
    testModeControl,
    validateConfig,
    webhookRows,
  } from '../../utils/payment-methods.js';
  import {
    buildSettingsPayload,
    initialValues,
    isDirty,
    resolveText,
    validate,
  } from '../../utils/schema-form.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  const TABS = ['settings', 'rules', 'status'];

  // `ctx` = GET /context (currency, currencies, additionalCurrencies). `onRefresh()` reloads the
  // provider list of the page and `getProvider(id)` reads the fresh row back.
  let { ctx = null, onRefresh = async () => {}, getProvider = () => null } = $props();

  let modalElement = $state(null);
  let confirmModal = $state(null);
  let provider = $state.raw(null);
  let working = $state({});
  let baseline = $state.raw({});
  let form = $state(configToForm());
  let schemaErrors = $state({});
  let configErrors = $state({});
  let tab = $state('settings');
  let saving = $state(false);
  let runningAction = $state(null);
  let actionResult = $state(null);

  const rawTranslate = (key) => $rawI18n(key, { default: key });
  const txt = (text) => resolveText(text, currentLocale(), rawTranslate);

  const caps = $derived(provider?.capabilities ?? {});
  const readOnly = $derived(provider ? rowBehavior(provider).readOnly : true);
  const name = $derived(provider ? providerName(provider, currentLocale(), rawTranslate) : '');
  const actions = $derived(provider?.schema?.actions ?? []);
  const dirty = $derived(provider ? isDirty(provider.schema, working, baseline) : false);
  const choices = $derived(currencyChoices(caps, ctx));
  const exponent = $derived(
    (ctx?.currencies ?? []).find((c) => c.code === ctx?.currency)?.exponent ?? 2,
  );
  const webhooks = $derived(webhookRows(provider?.webhookUrls));
  const capabilities = $derived(capabilityRows(caps, ctx));
  // A provider that is not registered (UNAVAILABLE) may come without a schema: its stored, masked
  // settings are listed read-only instead of an empty form.
  const storedRows = $derived(
    provider && (provider.schema?.fields ?? []).length === 0
      ? Object.entries(provider.settings ?? {}).map(([key, value]) => [key, String(value ?? '')])
      : [],
  );

  function seed(next) {
    provider = next;
    const values = initialValues(next.schema, next.settings ?? {});
    working = { ...values };
    baseline = values;
    form = configToForm(next.config ?? {});
    schemaErrors = {};
    configErrors = {};
  }

  export async function open(next) {
    seed(next);
    tab = 'settings';
    saving = false;
    runningAction = null;
    actionResult = null;
    // show only after the form rendered for this provider (a stale one would flash otherwise)
    await tick();
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !provider || readOnly) return;

    const nextSchemaErrors = validate(provider.schema, working);
    const nextConfigErrors = validateConfig(form, { capabilities: caps });
    schemaErrors = nextSchemaErrors;
    configErrors = nextConfigErrors;
    const failing = tabOfFirstClientError(nextSchemaErrors, nextConfigErrors);
    if (failing) {
      tab = failing;
      return;
    }

    saving = true;
    let result;
    try {
      result = await call(
        ApiUtil.post({
          path: marketPath(`/payment-methods/${provider.id}`),
          body: {
            settings: buildSettingsPayload(provider.schema, working),
            config: buildConfig(form, { capabilities: caps, original: provider.config ?? {} }),
          },
        }),
      );
    } finally {
      saving = false;
    }

    if (!result.ok) {
      if (result.error === 'INVALID_PROVIDER_SETTINGS') {
        const fieldErrors = result.body.fieldErrors ?? {};
        const schemaKeys = new Set((provider.schema?.fields ?? []).map((f) => f.key));
        schemaErrors = Object.fromEntries(
          Object.entries(fieldErrors).filter(([key]) => schemaKeys.has(key)),
        );
        configErrors = Object.fromEntries(
          Object.entries(fieldErrors)
            .filter(([key]) => !schemaKeys.has(key))
            .map(([key, value]) => [key, configErrorCode(value)]),
        );
        tab = tabOfFirstError(fieldErrors, provider.schema);
      }
      toastError($_, result);
      if (result.error === 'PROVIDER_UNAVAILABLE' || result.error === 'NOT_FOUND') {
        await onRefresh();
        const fresh = getProvider(provider.id);
        if (fresh && result.error === 'PROVIDER_UNAVAILABLE') provider = fresh;
        else hideModal(modalElement);
      }
      return;
    }

    // The provider's own message is API text, so only the fixed key is toasted (13 §16.2).
    showSuccessToast($_('settings.payments.toast-saved'));
    hideModal(modalElement);
    await onRefresh();
  }

  function runAction(action) {
    if (!provider || runningAction !== null) return;
    if (action.confirm) {
      confirmModal?.open({
        icon: 'fa-solid fa-circle-question',
        title: txt(action.label),
        description: txt(action.confirm),
        confirmLabel: txt(action.label),
        onConfirm: () => executeAction(action),
      });
    } else {
      executeAction(action);
    }
  }

  async function executeAction(action) {
    const current = provider;
    runningAction = action.id;
    actionResult = null;
    let result;
    try {
      result = await call(
        ApiUtil.post({
          path: marketPath(`/payment-methods/${current.id}/actions/${encodeURIComponent(action.id)}`),
          body: { input: {} },
        }),
      );
    } finally {
      runningAction = null;
    }

    if (!result.ok) {
      if (result.error === 'PAYMENT_PROVIDER_ERROR') {
        actionResult = { success: false, message: $_('errors.PAYMENT_PROVIDER_ERROR'), counts: null };
      } else {
        toastError($_, result);
      }
      return result.error === 'PAYMENT_PROVIDER_ERROR' ? undefined : false;
    }

    const body = result.body;
    actionResult = {
      success: body.success !== false,
      message: typeof body.message === 'string' ? body.message : '',
      counts: importedCounts(body),
    };
    // a SettingsPatch may have changed stored settings: reload the row and re-seed the form
    await onRefresh();
    const fresh = getProvider(current.id);
    if (fresh) {
      const keep = actionResult;
      seed(fresh);
      actionResult = keep;
    }
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
