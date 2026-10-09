<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable modal-lg">
    <div class="modal-content">
      {#if carrier}
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
                    {carrier.state === 'INCOMPATIBLE'
                      ? $_('settings.shipping-carriers.incompatible', {
                          values: { spiVersion: carrier.spiVersion ?? '' },
                        })
                      : $_('settings.shipping-carriers.unavailable')}
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

              {#key seedCount}
                <SchemaForm
                  schema={carrier.schema}
                  bind:values={working}
                  {baseline}
                  errors={schemaErrors}
                  {readOnly}
                  webhookUrls={{}}
                  idPrefix="carrier-field"
                  revealPath="/shipping/carriers/{carrier.id}/reveal" />
              {/key}

              <PluginHook
                name="market:panel:shipping-carrier:settings:{carrier.id}"
                props={{ provider: carrier, values: working, readOnly }} />

              {#if testControl !== 'hidden'}
                <div class="mt-3">
                  <div class="form-check form-switch">
                    <input
                      id="carrier-test-mode"
                      class="form-check-input"
                      type="checkbox"
                      role="switch"
                      disabled={readOnly || testControl === 'derived'}
                      checked={testControl === 'derived' ? caps.derivedTestMode === true : testMode}
                      onchange={(e) => (testMode = e.currentTarget.checked)} />
                    <label class="form-check-label user-select-none" for="carrier-test-mode">
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
                  class="alert {actionResult.success
                    ? 'alert-success'
                    : 'alert-danger'} d-flex align-items-start mt-3 mb-0"
                  role="alert">
                  <i
                    class="fa-solid {actionResult.success
                      ? 'fa-circle-check'
                      : 'fa-circle-exclamation'} me-3 mt-1"
                    aria-hidden="true"></i>
                  <div>{actionResult.message}</div>
                </div>
              {/if}
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

{#snippet status()}
  <div class="vstack gap-3">
    <div>
      <div class="text-body-secondary small text-uppercase mb-2">
        {$_('modals.carrier.webhook-url')}
      </div>
      {#if carrier.webhookUrl}
        <div class="input-group">
          <input
            class="form-control"
            type="text"
            readonly
            value={carrier.webhookUrl}
            aria-label={$_('modals.carrier.webhook-url')} />
          <CopyButton text={carrier.webhookUrl} />
        </div>
      {:else}
        <div class="text-body-secondary">{$_('modals.carrier.no-webhook-url')}</div>
      {/if}
    </div>

    <dl class="row mb-0">
      <dt class="col-sm-5">{$_('modals.provider.last-inbound')}</dt>
      <dd class="col-sm-7">
        {carrier.lastInboundAt
          ? new Date(carrier.lastInboundAt).toLocaleString(currentLocale())
          : $_('common.never')}
      </dd>
      <dt class="col-sm-5">{$_('modals.provider.last-error')}</dt>
      <dd class="col-sm-7">
        {#if carrier.lastError}
          <div class="text-break">{carrier.lastError}</div>
          {#if carrier.lastErrorAt}
            <div class="text-body-secondary">
              {new Date(carrier.lastErrorAt).toLocaleString(currentLocale())}
            </div>
          {/if}
        {:else}
          {$_('modals.provider.no-error')}
        {/if}
      </dd>
      {#if balance}
        <dt class="col-sm-5">{$_('modals.carrier.balance')}</dt>
        <dd class="col-sm-7">{fmt.money(balance.amount, balance.currency)}</dd>
      {/if}
      <dt class="col-sm-5">{$_('modals.provider.plugin')}</dt>
      <dd class="col-sm-7">
        {carrier.pluginId ?? ''}
        <span class="text-body-secondary">
          {$_('modals.provider.spi-version', { values: { version: carrier.spiVersion ?? '' } })}
        </span>
      </dd>
    </dl>
  </div>
{/snippet}

<script>
  import { tick } from 'svelte';
  import { api } from '@panomc/sdk/plugin-api';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ as rawI18n } from '@panomc/sdk/utils/language';
  import { _, showSuccessToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import CopyButton from '../CopyButton.svelte';
  import PluginHook from '../PluginHook.svelte';
  import SchemaForm from '../schema/SchemaForm.svelte';
  import { call } from '../../utils/api.js';
  import { currentLocale, fmt } from '../../utils/locale.js';
  import { actionBlocked, providerName, testModeControl } from '../../utils/payment-methods.js';
  import {
    buildSettingsPayload,
    initialValues,
    isDirty,
    resolveText,
    validate,
  } from '../../utils/schema-form.js';
  import { carrierBalance, carrierBehavior } from '../../utils/shipping-rates.js';
  import { toastError } from '../../utils/toast.js';
  import { hideModal, showModal } from '../order-detail/send.js';

  const TABS = ['settings', 'status'];

  // `onRefresh()` reloads the carrier list of the page and `getCarrier(id)` reads the fresh row back.
  // The form is the payment method modal's SchemaForm (13 §16.3) pointed at the shipping endpoints.
  let { onRefresh = async () => {}, getCarrier = () => null } = $props();

  let modalElement = $state(null);
  let confirmModal = $state(null);
  let carrier = $state.raw(null);
  let working = $state({});
  let baseline = $state.raw({});
  // bumped on every seed: the form remounts so reveal / prompt state never leaks between carriers
  let seedCount = $state(0);
  let schemaErrors = $state({});
  let testMode = $state(false);
  let tab = $state('settings');
  let saving = $state(false);
  let runningAction = $state(null);
  let actionResult = $state(null);

  const rawTranslate = (key) => $rawI18n(key, { default: key });
  const txt = (text) => resolveText(text, currentLocale(), rawTranslate);

  const caps = $derived(carrier?.capabilities ?? {});
  const readOnly = $derived(carrier ? carrierBehavior(carrier).readOnly : true);
  const name = $derived(carrier ? providerName(carrier, currentLocale(), rawTranslate) : '');
  const actions = $derived(carrier?.schema?.actions ?? []);
  const dirty = $derived(carrier ? isDirty(carrier.schema, working, baseline) : false);
  const testControl = $derived(testModeControl(caps));
  const balance = $derived(carrierBalance(carrier));
  // A carrier that is not registered (UNAVAILABLE) may come without a schema: its stored, masked
  // settings are listed read-only instead of an empty form.
  const storedRows = $derived(
    carrier && (carrier.schema?.fields ?? []).length === 0
      ? Object.entries(carrier.settings ?? {}).map(([key, value]) => [key, String(value ?? '')])
      : [],
  );

  function seed(next) {
    carrier = next;
    const values = initialValues(next.schema, next.settings ?? {});
    working = { ...values };
    baseline = values;
    seedCount++;
    testMode = Boolean(next.config?.testMode);
    schemaErrors = {};
  }

  export async function open(next) {
    seed(next);
    tab = 'settings';
    saving = false;
    runningAction = null;
    actionResult = null;
    // show only after the form rendered for this carrier (a stale one would flash otherwise)
    await tick();
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !carrier || readOnly) return;

    const nextErrors = validate(carrier.schema, working);
    schemaErrors = nextErrors;
    if (Object.keys(nextErrors).length > 0) {
      tab = 'settings';
      return;
    }

    saving = true;
    let result;
    try {
      const config = {};
      if (testControl === 'flag') config.testMode = testMode;
      result = await call(
        api.panel.post({
          path: `/shipping/carriers/${encodeURIComponent(carrier.id)}`,
          body: { settings: buildSettingsPayload(carrier.schema, working), config },
        }),
      );
    } finally {
      saving = false;
    }

    if (!result.ok) {
      if (result.error === 'INVALID_PROVIDER_SETTINGS') {
        const fieldErrors = result.body.fieldErrors ?? {};
        const schemaKeys = new Set((carrier.schema?.fields ?? []).map((f) => f.key));
        schemaErrors = Object.fromEntries(
          Object.entries(fieldErrors).filter(([key]) => schemaKeys.has(key)),
        );
        tab = 'settings';
      }
      toastError($_, result);
      if (result.error === 'PROVIDER_UNAVAILABLE' || result.error === 'NOT_FOUND') {
        await onRefresh();
        const fresh = getCarrier(carrier.id);
        if (fresh && result.error === 'PROVIDER_UNAVAILABLE') carrier = fresh;
        else hideModal(modalElement);
      }
      return;
    }

    // The carrier's own message is API text, so only the fixed key is toasted.
    showSuccessToast($_('settings.shipping-carriers.toast-saved'));
    hideModal(modalElement);
    await onRefresh();
  }

  function runAction(action) {
    if (!carrier || runningAction !== null) return;
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
    const current = carrier;
    runningAction = action.id;
    actionResult = null;
    let result;
    try {
      result = await call(
        api.panel.post({
          path: `/shipping/carriers/${encodeURIComponent(current.id)}/actions/${encodeURIComponent(action.id)}`,
          body: { input: {} },
        }),
      );
    } finally {
      runningAction = null;
    }

    if (!result.ok) {
      if (result.error === 'SHIPPING_PROVIDER_ERROR') {
        actionResult = { success: false, message: $_('errors.SHIPPING_PROVIDER_ERROR') };
        return undefined;
      }
      toastError($_, result);
      return false;
    }

    const body = result.body;
    actionResult = {
      success: body.success !== false,
      message: typeof body.message === 'string' ? body.message : '',
    };
    // a SettingsPatch may have changed stored settings: reload the row and re-seed the form
    await onRefresh();
    const fresh = getCarrier(current.id);
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
