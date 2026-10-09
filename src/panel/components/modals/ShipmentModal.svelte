<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {$_('modals.shipment.title')}
          {#if shipment}#{shipment.id}{/if}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>

      {#if loading}
        <div class="modal-body text-center py-5">
          <span class="spinner-border" aria-hidden="true"></span>
          <span class="visually-hidden">{$_('common.loading')}</span>
        </div>
      {:else if loadError}
        <div class="modal-body">
          <div class="alert alert-danger d-flex align-items-start mb-0" role="alert">
            <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
            <div>
              <b>{$_('common.load-error-title')}</b>
              <div>{$_(errorKey(loadError))}</div>
            </div>
          </div>
        </div>
      {:else if shipment}
        <form onsubmit={submit}>
          <div class="modal-body vstack gap-3">
            <dl class="row mb-0">
              <dt class="col-sm-4">{$_('common.status')}</dt>
              <dd class="col-sm-8">
                <StatusBadge kind="shipment" value={shipment.status} />
                {#if shipment.stale}
                  <span class="badge text-bg-warning ms-1">{$_('pages.shipments.stale')}</span>
                {/if}
              </dd>
              <dt class="col-sm-4">{$_('modals.shipment.order')}</dt>
              <dd class="col-sm-8">
                <a href="{base}/market/orders/detail/{shipment.orderId}">#{shipment.orderId}</a>
              </dd>
              <dt class="col-sm-4">{$_('modals.shipment.recipient')}</dt>
              <dd class="col-sm-8">
                {recipientName(shipment.toAddress) || '—'}
                {#if shipment.toAddress?.city}
                  <span class="text-body-secondary">· {shipment.toAddress.city}</span>
                {/if}
              </dd>
              <dt class="col-sm-4">{$_('modals.shipment.provider')}</dt>
              <dd class="col-sm-8">{shipment.carrierName ?? shipment.providerId ?? '—'}</dd>
              <dt class="col-sm-4">{$_('modals.shipment.tracking')}</dt>
              <dd class="col-sm-8">
                {#if shipment.trackingNumber}
                  {#if isHttpUrl(shipment.trackingUrl)}
                    <a href={shipment.trackingUrl} target="_blank" rel="noopener">
                      {shipment.trackingNumber}
                    </a>
                  {:else}
                    {shipment.trackingNumber}
                  {/if}
                  <CopyButton text={shipment.trackingNumber} />
                {:else}
                  —
                {/if}
              </dd>
              <dt class="col-sm-4">{$_('modals.shipment.shipped')}</dt>
              <dd class="col-sm-8">{formatWhen(shipment.shippedAt)}</dd>
              <dt class="col-sm-4">{$_('modals.shipment.delivered')}</dt>
              <dd class="col-sm-8">{formatWhen(shipment.deliveredAt)}</dd>
              {#if shipment.lastErrorCode}
                <dt class="col-sm-4">{$_('modals.shipment.last-error')}</dt>
                <dd class="col-sm-8 text-danger">{errorLabel(shipment.lastErrorCode)}</dd>
              {/if}
              {#if shipment.note}
                <dt class="col-sm-4">{$_('modals.shipment.note')}</dt>
                <dd class="col-sm-8" style="white-space: pre-wrap;">{shipment.note}</dd>
              {/if}
            </dl>

            {#if items.length > 0}
              <div class="table-responsive">
                <table class="table table-sm align-middle mb-0">
                  <thead>
                    <tr>
                      <th class="text-nowrap" scope="col">{$_('modals.shipment.items.product')}</th>
                      <th class="text-nowrap" scope="col"
                        >{$_('modals.shipment.items.quantity')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {#each items as item, index (item.id ?? index)}
                      <tr>
                        <td>{item.name ?? item.productName ?? `#${item.orderItemId}`}</td>
                        <td>{item.quantity}</td>
                      </tr>
                    {/each}
                  </tbody>
                </table>
              </div>
            {/if}

            {#if events.length > 0}
              <ul class="list-unstyled vstack gap-2 mb-0">
                {#each events as event, index (event.id ?? index)}
                  <li class="border-start border-3 ps-3">
                    <div class="d-flex flex-wrap align-items-center gap-2">
                      <StatusBadge kind="shipment" value={event.status} />
                      <span class="text-body-secondary">{formatWhen(event.occurredAt)}</span>
                      {#if event.source}
                        <span class="badge text-bg-secondary">{event.source}</span>
                      {/if}
                    </div>
                    {#if event.rawStatus}<div class="small">{event.rawStatus}</div>{/if}
                    {#if event.description}<div>{event.description}</div>{/if}
                    {#if event.location}
                      <div class="text-body-secondary">{event.location}</div>
                    {/if}
                  </li>
                {/each}
              </ul>
            {/if}

            {#if canEdit}
              <hr class="my-0" />
              <input
                class="form-control"
                class:is-invalid={invalid.carrierName}
                type="text"
                maxlength={CARRIER_MAX}
                placeholder={$_('modals.shipment.carrier-name')}
                aria-label={$_('modals.shipment.carrier-name')}
                bind:value={form.carrierName} />
              <input
                class="form-control"
                class:is-invalid={invalid.trackingNumber}
                type="text"
                maxlength={TRACKING_MAX}
                disabled={trackingLocked}
                placeholder={$_('modals.shipment.tracking-number')}
                aria-label={$_('modals.shipment.tracking-number')}
                bind:value={form.trackingNumber} />
              <input
                class="form-control"
                class:is-invalid={invalid.trackingUrl}
                type="url"
                maxlength={URL_MAX}
                placeholder={$_('modals.shipment.tracking-url')}
                aria-label={$_('modals.shipment.tracking-url')}
                bind:value={form.trackingUrl} />
              <div>
                <label class="form-label" for="shipment-status"
                  >{$_('modals.shipment.status')}</label>
                <select
                  id="shipment-status"
                  class="form-select"
                  class:is-invalid={invalid.status}
                  bind:value={form.status}>
                  {#each choices as value (value)}
                    <option {value}>{$_(`enums.shipment.${value}`)}</option>
                  {/each}
                </select>
              </div>
              {#if releasable}
                <button type="button" class="btn btn-outline-secondary" onclick={release}>
                  <i class="fa-solid fa-box-open me-1" aria-hidden="true"></i>
                  {$_('pages.shipments.actions.release')}
                </button>
              {/if}
            {/if}
          </div>
          {#if canEdit}
            <div class="modal-footer">
              <button class="btn btn-primary w-100" type="submit" disabled={saving}>
                {#if saving}
                  <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                {/if}
                {$_('common.save')}
              </button>
            </div>
          {/if}
        </form>
      {/if}
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { base } from '@panomc/sdk/svelte';
  import { _, showErrorToast } from '../../../i18n';
  import CopyButton from '../CopyButton.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import { isStaleError } from '../order-detail/actions.js';
  import { hideModal, showModal } from '../order-detail/send.js';
  import { call, errorKey } from '../../utils/api.js';
  import { currentLocale } from '../../utils/locale.js';
  import { can } from '../../utils/permissions.js';
  import {
    CARRIER_MAX,
    TRACKING_MAX,
    URL_MAX,
    canRelease,
    editable,
    failureText,
    initialEdit,
    isHttpUrl,
    recipientName,
    statusChoices,
    trackingReadOnly,
    updateRequest,
  } from '../../utils/shipments.js';

  // onDone(toastKey) refreshes the host list and toasts; onStale() refreshes after a stale failure;
  // onRelease(shipment) opens the "Release Items" confirmation (the modal hides itself first).
  let {
    user = null,
    onDone = async () => {},
    onStale = async () => {},
    onRelease = () => {},
  } = $props();

  let modalElement = $state(null);
  let loading = $state(false);
  let loadError = $state(null);
  let saving = $state(false);
  let shipment = $state(null);
  let items = $state([]);
  let events = $state([]);
  let form = $state(initialEdit(null));
  let invalid = $state({});

  const canEdit = $derived(can(user, 'OM') && shipment !== null && editable(shipment));
  const trackingLocked = $derived(trackingReadOnly(shipment));
  const choices = $derived(statusChoices(shipment?.status));
  const releasable = $derived(can(user, 'OM') && canRelease(shipment));

  function formatWhen(epoch) {
    return epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—';
  }

  function errorLabel(code) {
    const key = `enums.delivery-error.${code}`;
    const text = $_(key);
    return text === key || text === `plugins.pano-plugin-market.${key}` ? code : text;
  }

  async function load(id) {
    loading = true;
    loadError = null;
    try {
      const result = await call(api.panel.get({ path: `/shipments/${id}` }));
      if (!result.ok) {
        loadError = result.error;
        return;
      }
      shipment = result.body.shipment ?? null;
      items = Array.isArray(result.body.items) ? result.body.items : [];
      events = Array.isArray(result.body.events) ? result.body.events : [];
      form = initialEdit(shipment);
    } finally {
      loading = false;
    }
  }

  export async function open(id) {
    shipment = null;
    items = [];
    events = [];
    invalid = {};
    saving = false;
    showModal(modalElement);
    await load(id);
  }

  function release() {
    const current = shipment;
    hideModal(modalElement);
    onRelease(current);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !shipment) return;
    const built = updateRequest(shipment, form);
    invalid = built.error ?? {};
    if (built.error) return;
    if (built.unchanged) {
      hideModal(modalElement);
      return;
    }
    saving = true;
    try {
      const result = await call(
        api.panel.put({ path: built.request.path, body: built.request.body }),
      );
      if (!result.ok) {
        showErrorToast(failureText($_, result.error, result.body));
        if (isStaleError(result.error)) {
          hideModal(modalElement);
          await onStale();
        }
        return;
      }
      hideModal(modalElement);
      await onDone('pages.shipments.toast.updated');
    } finally {
      saving = false;
    }
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
