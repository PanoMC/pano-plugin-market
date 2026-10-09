<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.order-filters.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          {#if canSettings && providers.length > 0}
            <select
              class="form-select"
              aria-label={$_('modals.order-filters.payment-method')}
              bind:value={values.paymentMethodId}>
              <option value="">{$_('modals.order-filters.payment-method')}</option>
              {#each options as option (option.id)}
                <option value={option.id}>{option.label}</option>
              {/each}
            </select>
          {:else}
            <input
              class="form-control"
              type="text"
              placeholder={$_('modals.order-filters.payment-method')}
              aria-label={$_('modals.order-filters.payment-method')}
              bind:value={values.paymentMethodId} />
          {/if}

          <select
            class="form-select"
            aria-label={$_('modals.order-filters.fulfillment')}
            bind:value={values.fulfillmentStatus}>
            <option value="">{$_('modals.order-filters.fulfillment')}</option>
            {#each FULFILLMENT_VALUES as value (value)}
              <option {value}>{$_(`enums.fulfillment.${value}`)}</option>
            {/each}
          </select>

          {#if ctx?.shippingEnabled}
            <select
              class="form-select"
              aria-label={$_('modals.order-filters.shipping')}
              bind:value={values.shippingStatus}>
              <option value="">{$_('modals.order-filters.shipping')}</option>
              {#each SHIPPING_VALUES as value (value)}
                <option {value}>{$_(`enums.shipping.${value}`)}</option>
              {/each}
            </select>
          {/if}

          <select
            class="form-select"
            aria-label={$_('modals.order-filters.source')}
            bind:value={values.source}>
            <option value="">{$_('modals.order-filters.source')}</option>
            {#each SOURCE_VALUES as value (value)}
              <option {value}>{$_(`modals.order-filters.sources.${value}`)}</option>
            {/each}
          </select>

          <select
            class="form-select"
            aria-label={$_('modals.order-filters.test-mode')}
            bind:value={values.testMode}>
            <option value="">{$_('modals.order-filters.test-mode-all')}</option>
            <option value="false">{$_('modals.order-filters.test-mode-live')}</option>
            <option value="true">{$_('modals.order-filters.test-mode-test')}</option>
          </select>

          <div class="input-group">
            <span class="input-group-text">{$_('components.date-range.from')}</span>
            <input
              class="form-control"
              class:is-invalid={reversed}
              type="date"
              aria-label={$_('components.date-range.from')}
              bind:value={fromText} />
            <span class="input-group-text">{$_('components.date-range.to')}</span>
            <input
              class="form-control"
              class:is-invalid={reversed}
              type="date"
              aria-label={$_('components.date-range.to')}
              bind:value={toText} />
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit" disabled={reversed}>
            {$_('common.apply')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { call } from '../../utils/api.js';
  import { dayToEpoch, toDateInput } from '../../utils/format.js';
  import { can } from '../../utils/permissions.js';
  import {
    FULFILLMENT_VALUES,
    SHIPPING_VALUES,
    SOURCE_VALUES,
    normalizeFilters,
    paymentOptions,
  } from '../orders/filters.js';

  // filters: the URL filters of the list; ctx: GET /context (or null);
  // onApply(values): called with { paymentMethodId, fulfillmentStatus, shippingStatus, source,
  // testMode, from, to } (strings, '' = off); the page navigates.
  let { filters = {}, ctx = null, onApply = () => {} } = $props();

  let modalElement = $state(null);
  let values = $state(blank());
  let fromText = $state('');
  let toText = $state('');
  let providers = $state([]);

  const user = $derived($page.data?.user);
  const canSettings = $derived(can(user, 'SET'));
  const options = $derived(paymentOptions(providers));
  const fromMs = $derived(dayToEpoch(fromText, false));
  const toMs = $derived(dayToEpoch(toText, true));
  const reversed = $derived(fromMs !== null && toMs !== null && fromMs > toMs);

  function blank() {
    return {
      paymentMethodId: '',
      fulfillmentStatus: '',
      shippingStatus: '',
      source: '',
      testMode: '',
    };
  }

  async function loadProviders() {
    if (!canSettings) return;
    const result = await call(api.panel.get({ path: '/payment-providers' }));
    providers = result.ok && Array.isArray(result.body.items) ? result.body.items : [];
  }

  export function open() {
    const f = normalizeFilters(filters);
    values = {
      paymentMethodId: f.paymentMethodId,
      fulfillmentStatus: f.fulfillmentStatus,
      shippingStatus: f.shippingStatus,
      source: f.source,
      testMode: f.testMode,
    };
    fromText = toDateInput(f.from === '' ? null : Number(f.from));
    toText = toDateInput(f.to === '' ? null : Number(f.to));
    loadProviders();
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
    }
  }

  function submit(event) {
    event.preventDefault();
    if (reversed) return;
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
    }
    onApply({
      ...values,
      shippingStatus: ctx?.shippingEnabled ? values.shippingStatus : '',
      from: fromMs === null ? '' : String(fromMs),
      to: toMs === null ? '' : String(toMs),
    });
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
